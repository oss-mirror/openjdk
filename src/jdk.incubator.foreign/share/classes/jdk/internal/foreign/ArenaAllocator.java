/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.foreign;

import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SegmentAllocator;
import jdk.incubator.foreign.ResourceScope;

public abstract class ArenaAllocator implements SegmentAllocator {

    protected MemorySegment segment;

    protected long sp = 0L;

    ArenaAllocator(MemorySegment segment) {
        this.segment = segment;
    }

    MemorySegment trySlice(long bytesSize, long bytesAlignment) {
        long min = segment.address().toRawLongValue();
        long start = Utils.alignUp(min + sp, bytesAlignment) - min;
        if (segment.byteSize() - start < bytesSize) {
            return null;
        } else {
            MemorySegment slice = segment.asSlice(start, bytesSize);
            sp = start + bytesSize;
            return slice;
        }
    }

    void checkConfinementIfNeeded() {
        Thread ownerThread = scope().ownerThread();
        if (ownerThread != null && ownerThread != Thread.currentThread()) {
            throw new IllegalStateException("Attempt to allocate outside confinement thread");
        }
    }

    ResourceScope scope() {
        return segment.scope();
    }

    public static class UnboundedArenaAllocator extends ArenaAllocator {

        private static final long DEFAULT_BLOCK_SIZE = 4 * 1024;

        public UnboundedArenaAllocator(ResourceScope scope) {
            super(MemorySegment.allocateNative(DEFAULT_BLOCK_SIZE, 1, scope));
        }

        private MemorySegment newSegment(long size, long align) {
            return MemorySegment.allocateNative(size, align, segment.scope());
        }

        @Override
        public MemorySegment allocate(long bytesSize, long bytesAlignment) {
            checkConfinementIfNeeded();
            // try to slice from current segment first...
            MemorySegment slice = trySlice(bytesSize, bytesAlignment);
            if (slice != null) {
                return slice;
            } else {
                long maxPossibleAllocationSize = bytesSize + bytesAlignment - 1;
                if (maxPossibleAllocationSize > DEFAULT_BLOCK_SIZE) {
                    // too big
                    return newSegment(bytesSize, bytesAlignment);
                } else {
                    // allocate a new segment and slice from there
                    sp = 0L;
                    segment = newSegment(DEFAULT_BLOCK_SIZE, 1L);
                    return trySlice(bytesSize, bytesAlignment);
                }
            }
        }
    }

    public static class BoundedArenaAllocator extends ArenaAllocator {

        public BoundedArenaAllocator(ResourceScope scope, long size) {
            super(MemorySegment.allocateNative(size, 1, scope));
        }

        @Override
        public MemorySegment allocate(long bytesSize, long bytesAlignment) {
            checkConfinementIfNeeded();
            // try to slice from current segment first...
            MemorySegment slice = trySlice(bytesSize, bytesAlignment);
            if (slice != null) {
                return slice;
            } else {
                throw new OutOfMemoryError("Not enough space left to allocate");
            }
        }
    }

    public static class BoundedSharedArenaAllocator extends BoundedArenaAllocator {
        public BoundedSharedArenaAllocator(ResourceScope scope, long size) {
            super(scope, size);
        }

        @Override
        public synchronized MemorySegment allocate(long bytesSize, long bytesAlignment) {
            return super.allocate(bytesSize, bytesAlignment);
        }
    }

    public static class UnboundedSharedArenaAllocator implements SegmentAllocator {

        final ResourceScope scope;

        final ThreadLocal<ArenaAllocator> allocators = new ThreadLocal<>() {
            @Override
            protected ArenaAllocator initialValue() {
                return new UnboundedArenaAllocator(scope);
            }
        };

        public UnboundedSharedArenaAllocator(ResourceScope scope) {
            this.scope = scope;
        }

        @Override
        public MemorySegment allocate(long bytesSize, long bytesAlignment) {
            return allocators.get().allocate(bytesSize, bytesAlignment);
        }
    }
}
