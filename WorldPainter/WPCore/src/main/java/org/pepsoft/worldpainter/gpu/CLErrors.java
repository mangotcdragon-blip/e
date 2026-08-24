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

import java.util.HashMap;
import java.util.Map;

/**
 * Translates OpenCL error codes into their symbolic names, so that a failure in a driver is at least readable in the
 * log without having to look the number up.
 */
final class CLErrors {
    private CLErrors() {
        // Prevent instantiation
    }

    static String describe(int errorCode) {
        return NAMES.getOrDefault(errorCode, "unknown error");
    }

    /**
     * Throws a {@link GpuException} if {@code errorCode} is not {@code CL_SUCCESS}.
     */
    static void check(int errorCode, String what) {
        if (errorCode != 0) {
            throw new GpuException(what + " failed", errorCode);
        }
    }

    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        NAMES.put(     0, "CL_SUCCESS");
        NAMES.put(   - 1, "CL_DEVICE_NOT_FOUND");
        NAMES.put(   - 2, "CL_DEVICE_NOT_AVAILABLE");
        NAMES.put(   - 3, "CL_COMPILER_NOT_AVAILABLE");
        NAMES.put(   - 4, "CL_MEM_OBJECT_ALLOCATION_FAILURE");
        NAMES.put(   - 5, "CL_OUT_OF_RESOURCES");
        NAMES.put(   - 6, "CL_OUT_OF_HOST_MEMORY");
        NAMES.put(   - 7, "CL_PROFILING_INFO_NOT_AVAILABLE");
        NAMES.put(   - 8, "CL_MEM_COPY_OVERLAP");
        NAMES.put(   - 9, "CL_IMAGE_FORMAT_MISMATCH");
        NAMES.put(   -10, "CL_IMAGE_FORMAT_NOT_SUPPORTED");
        NAMES.put(   -11, "CL_BUILD_PROGRAM_FAILURE");
        NAMES.put(   -12, "CL_MAP_FAILURE");
        NAMES.put(   -30, "CL_INVALID_VALUE");
        NAMES.put(   -31, "CL_INVALID_DEVICE_TYPE");
        NAMES.put(   -32, "CL_INVALID_PLATFORM");
        NAMES.put(   -33, "CL_INVALID_DEVICE");
        NAMES.put(   -34, "CL_INVALID_CONTEXT");
        NAMES.put(   -35, "CL_INVALID_QUEUE_PROPERTIES");
        NAMES.put(   -36, "CL_INVALID_COMMAND_QUEUE");
        NAMES.put(   -37, "CL_INVALID_HOST_PTR");
        NAMES.put(   -38, "CL_INVALID_MEM_OBJECT");
        NAMES.put(   -39, "CL_INVALID_IMAGE_FORMAT_DESCRIPTOR");
        NAMES.put(   -40, "CL_INVALID_IMAGE_SIZE");
        NAMES.put(   -41, "CL_INVALID_SAMPLER");
        NAMES.put(   -42, "CL_INVALID_BINARY");
        NAMES.put(   -43, "CL_INVALID_BUILD_OPTIONS");
        NAMES.put(   -44, "CL_INVALID_PROGRAM");
        NAMES.put(   -45, "CL_INVALID_PROGRAM_EXECUTABLE");
        NAMES.put(   -46, "CL_INVALID_KERNEL_NAME");
        NAMES.put(   -47, "CL_INVALID_KERNEL_DEFINITION");
        NAMES.put(   -48, "CL_INVALID_KERNEL");
        NAMES.put(   -49, "CL_INVALID_ARG_INDEX");
        NAMES.put(   -50, "CL_INVALID_ARG_VALUE");
        NAMES.put(   -51, "CL_INVALID_ARG_SIZE");
        NAMES.put(   -52, "CL_INVALID_KERNEL_ARGS");
        NAMES.put(   -53, "CL_INVALID_WORK_DIMENSION");
        NAMES.put(   -54, "CL_INVALID_WORK_GROUP_SIZE");
        NAMES.put(   -55, "CL_INVALID_WORK_ITEM_SIZE");
        NAMES.put(   -56, "CL_INVALID_GLOBAL_OFFSET");
        NAMES.put(   -57, "CL_INVALID_EVENT_WAIT_LIST");
        NAMES.put(   -58, "CL_INVALID_EVENT");
        NAMES.put(   -59, "CL_INVALID_OPERATION");
        NAMES.put(   -61, "CL_INVALID_BUFFER_SIZE");
        NAMES.put(   -63, "CL_INVALID_GLOBAL_WORK_SIZE");
        NAMES.put(   -64, "CL_INVALID_PROPERTY");
    }
}
