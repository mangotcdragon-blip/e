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

import static org.junit.Assume.assumeTrue;

/**
 * Shared set-up for the tests that need an actual OpenCL device.
 *
 * <p>Build machines rarely have a graphics card, so these tests accept any OpenCL device, including a software
 * implementation such as PoCL or Intel's CPU runtime, and skip themselves entirely when there is none. That keeps the
 * suite green on a machine without OpenCL while still exercising the real kernels wherever a runtime is installed.
 */
final class GpuTestSupport {
    private GpuTestSupport() {
        // Prevent instantiation
    }

    /**
     * Configure the GPU layer for testing and skip the calling test if no OpenCL device can be used.
     */
    static GpuContext requireDevice() {
        GpuSettings.setMode(GpuSettings.Mode.AUTO);
        GpuSettings.setDevicePreference(GpuSettings.DevicePreference.ANY_DEVICE);
        GpuSettings.setDeviceNameFilter(null);
        GpuContext.shutdown();
        final GpuContext context = GpuContext.get();
        assumeTrue("No OpenCL device available; skipping", context != null);
        return context;
    }
}
