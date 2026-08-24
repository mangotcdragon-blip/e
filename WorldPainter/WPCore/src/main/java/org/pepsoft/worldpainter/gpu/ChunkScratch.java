/*
 * WorldPainter, a graphical map generator for Minecraft. Copyright (C) 2011-2015  pepsoft.org
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.pepsoft.worldpainter.gpu;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opencl.CL10.CL_MEM_READ_ONLY;
import static org.lwjgl.opencl.CL10.CL_MEM_WRITE_ONLY;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * The per-thread staging buffers a chunk kernel works with: the column descriptions going to the device and the
 * material indices coming back.
 *
 * <p>An export generates tens of thousands of chunks, all the same size, so these are allocated once per thread and
 * re-used. They grow if a taller world comes along and are never shrunk.
 *
 * <p>Instances belong to one thread and are not synchronised.
 */
final class ChunkScratch implements AutoCloseable {
    ChunkScratch(GpuContext context) {
        this.context = context;
    }

    /**
     * Make sure there is room for {@code columnCount} columns of {@code columnStride} ints each, and for
     * {@code blockCount} result bytes.
     */
    void ensureCapacity(int columnStride, int columnCount, int blockCount) {
        final int columnInts = columnStride * columnCount;
        if (columnInts > columnCapacity) {
            releaseColumns();
            columnCapacity = columnInts;
            hostColumns = memAllocInt(columnInts);
            deviceColumns = context.createBuffer(CL_MEM_READ_ONLY, (long) columnInts * Integer.BYTES);
        }
        if (blockCount > resultCapacity) {
            releaseResult();
            resultCapacity = blockCount;
            hostResult = memAlloc(blockCount);
            deviceResult = context.createBuffer(CL_MEM_WRITE_ONLY, blockCount);
        }
        hostColumns.clear();
        hostResult.clear();
    }

    IntBuffer getHostColumns() {
        return hostColumns;
    }

    ByteBuffer getHostResult() {
        return hostResult;
    }

    long getDeviceColumns() {
        return deviceColumns;
    }

    long getDeviceResult() {
        return deviceResult;
    }

    @Override
    public void close() {
        releaseColumns();
        releaseResult();
    }

    private void releaseColumns() {
        if (deviceColumns != 0) {
            context.releaseBuffer(deviceColumns);
            deviceColumns = 0;
        }
        memFree(hostColumns);
        hostColumns = null;
        columnCapacity = 0;
    }

    private void releaseResult() {
        if (deviceResult != 0) {
            context.releaseBuffer(deviceResult);
            deviceResult = 0;
        }
        memFree(hostResult);
        hostResult = null;
        resultCapacity = 0;
    }

    private final GpuContext context;
    private IntBuffer hostColumns;
    private ByteBuffer hostResult;
    private long deviceColumns, deviceResult;
    private int columnCapacity, resultCapacity;
}
