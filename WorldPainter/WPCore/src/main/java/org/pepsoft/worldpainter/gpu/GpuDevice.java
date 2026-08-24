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

import java.util.Locale;

import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_CPU;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_GPU;

/**
 * An immutable description of one OpenCL device, along with everything WorldPainter needs to decide whether it is the
 * device the export should run on.
 */
public final class GpuDevice {
    GpuDevice(long platformId, long deviceId, String platformName, String name, String vendor, String version,
              long type, int computeUnits, int clockFrequency, long globalMemory, long maxAllocation,
              long maxWorkGroupSize, boolean unifiedMemory, boolean doublePrecision) {
        this.platformId = platformId;
        this.deviceId = deviceId;
        this.platformName = platformName;
        this.name = name;
        this.vendor = vendor;
        this.version = version;
        this.type = type;
        this.computeUnits = computeUnits;
        this.clockFrequency = clockFrequency;
        this.globalMemory = globalMemory;
        this.maxAllocation = maxAllocation;
        this.maxWorkGroupSize = maxWorkGroupSize;
        this.unifiedMemory = unifiedMemory;
        this.doublePrecision = doublePrecision;
    }

    public long getPlatformId() {
        return platformId;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public String getPlatformName() {
        return platformName;
    }

    public String getName() {
        return name;
    }

    public String getVendor() {
        return vendor;
    }

    public String getVersion() {
        return version;
    }

    public int getComputeUnits() {
        return computeUnits;
    }

    public int getClockFrequency() {
        return clockFrequency;
    }

    public long getGlobalMemory() {
        return globalMemory;
    }

    /**
     * The largest single buffer the device will allocate. Kernel batches have to be sized to stay under this.
     */
    public long getMaxAllocation() {
        return maxAllocation;
    }

    public long getMaxWorkGroupSize() {
        return maxWorkGroupSize;
    }

    public boolean isGpu() {
        return (type & CL_DEVICE_TYPE_GPU) != 0;
    }

    public boolean isCpu() {
        return (type & CL_DEVICE_TYPE_CPU) != 0;
    }

    /**
     * Whether the device supports {@code double}. Without it WorldPainter's Perlin noise cannot be reproduced exactly,
     * so by default such devices are not used. See {@link GpuSettings#isRequireDoublePrecision()}.
     */
    public boolean isDoublePrecision() {
        return doublePrecision;
    }

    /**
     * Whether the device shares its memory with the host. Integrated GPUs do; a dedicated graphics card has its own
     * memory and reports {@code false} here, which is the main signal used to tell the two apart.
     */
    public boolean isUnifiedMemory() {
        return unifiedMemory;
    }

    /**
     * Whether this device looks like a dedicated graphics card, as opposed to an integrated GPU or a CPU device.
     *
     * <p>There is no OpenCL query for this, so it is inferred: a GPU which does <em>not</em> share memory with the host
     * is dedicated. Some drivers get {@code CL_DEVICE_HOST_UNIFIED_MEMORY} wrong, so a GPU with at least
     * {@value #DEDICATED_MEMORY_THRESHOLD} bytes of its own memory also counts, which no integrated GPU of that era
     * reports.
     */
    public boolean isDedicated() {
        return isGpu() && ((! unifiedMemory) || (globalMemory >= DEDICATED_MEMORY_THRESHOLD));
    }

    /**
     * A rough ranking of how much throughput this device is likely to deliver, used to pick between several candidates.
     * Higher is better.
     */
    long getScore(GpuSettings.DevicePreference preference) {
        if ((! isGpu()) && (preference != GpuSettings.DevicePreference.ANY_DEVICE)) {
            return Long.MIN_VALUE;
        }
        if ((preference == GpuSettings.DevicePreference.DEDICATED_GPU) && (! isDedicated())) {
            // Not disqualified: an integrated GPU still beats the CPU for this work. Just ranked below any dedicated
            // card in the machine.
            return (long) computeUnits * Math.max(clockFrequency, 1);
        }
        final long throughput = (long) computeUnits * Math.max(clockFrequency, 1);
        return isGpu() ? (throughput * DEDICATED_GPU_WEIGHT) : throughput;
    }

    /**
     * A one line description suitable for the log and the user interface.
     */
    public String getDescription() {
        return String.format(Locale.ROOT, "%s (%s, %s) - %d compute units @ %d MHz, %d MB %s memory%s",
                name.trim(), vendor.trim(), platformName.trim(), computeUnits, clockFrequency,
                globalMemory / (1024 * 1024), isDedicated() ? "dedicated" : (unifiedMemory ? "shared" : "device"),
                doublePrecision ? "" : ", no double precision");
    }

    @Override
    public String toString() {
        return getDescription();
    }

    private final long platformId, deviceId, type, globalMemory, maxAllocation, maxWorkGroupSize;
    private final String platformName, name, vendor, version;
    private final int computeUnits, clockFrequency;
    private final boolean unifiedMemory, doublePrecision;

    /**
     * 1 GB. An integrated GPU carves its memory out of system RAM and reports that as shared; anything reporting this
     * much memory as not shared with the host is a real card.
     */
    static final long DEDICATED_MEMORY_THRESHOLD = 1024L * 1024 * 1024;

    /**
     * How much better a GPU is assumed to be than a CPU device with the same nominal compute units and clock. GPUs
     * report far fewer "compute units" than they have lanes, so without this a many core CPU would always win.
     */
    private static final long DEDICATED_GPU_WEIGHT = 64;
}
