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

/**
 * Tunables for the OpenCL acceleration. Everything can be overridden with a system property so that a user with a
 * misbehaving driver can switch the acceleration off, or pin it to a specific device, without needing a new build.
 *
 * <p>The defaults are deliberately conservative: acceleration is attempted, but any problem at all results in a silent
 * fall back to the existing CPU code.
 */
public final class GpuSettings {
    private GpuSettings() {
        // Prevent instantiation
    }

    /**
     * What to do when GPU acceleration is requested.
     */
    public enum Mode {
        /** Never use the GPU. */
        OFF,
        /** Use the GPU when a suitable device is found, otherwise use the CPU. This is the default. */
        AUTO,
        /** Use the GPU, and fail loudly when that is not possible. Intended for testing and troubleshooting. */
        FORCE
    }

    /**
     * Which OpenCL device to prefer.
     */
    public enum DevicePreference {
        /**
         * Prefer a dedicated (discrete) GPU: one that reports its own, non-unified memory. Falls back to any GPU, and
         * then to any device. This is the default, and is what you want on a laptop with both an integrated and a
         * discrete GPU.
         */
        DEDICATED_GPU,
        /** Prefer any GPU, integrated or discrete, over a CPU device. */
        ANY_GPU,
        /** Use whichever device scores highest, including OpenCL CPU devices. */
        ANY_DEVICE
    }

    public static Mode getMode() {
        return mode;
    }

    public static synchronized void setMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        GpuSettings.mode = mode;
    }

    public static DevicePreference getDevicePreference() {
        return devicePreference;
    }

    public static synchronized void setDevicePreference(DevicePreference devicePreference) {
        if (devicePreference == null) {
            throw new NullPointerException("devicePreference");
        }
        GpuSettings.devicePreference = devicePreference;
    }

    /**
     * A case insensitive substring of the name of the device to use, or {@code null} to let WorldPainter pick. Useful
     * on a machine with several GPUs.
     */
    public static String getDeviceNameFilter() {
        return deviceNameFilter;
    }

    public static synchronized void setDeviceNameFilter(String deviceNameFilter) {
        GpuSettings.deviceNameFilter = ((deviceNameFilter != null) && deviceNameFilter.trim().isEmpty()) ? null : deviceNameFilter;
    }

    /**
     * Whether to insist on double precision support on the device. WorldPainter's Perlin noise is sampled at double
     * precision on the CPU; using single precision on the GPU would very occasionally place a different block, which
     * would make an accelerated export differ from an unaccelerated one. With this set (the default) devices without
     * {@code cl_khr_fp64} are rejected, guaranteeing that both paths produce byte for byte identical maps.
     */
    public static boolean isRequireDoublePrecision() {
        return requireDoublePrecision;
    }

    public static synchronized void setRequireDoublePrecision(boolean requireDoublePrecision) {
        GpuSettings.requireDoublePrecision = requireDoublePrecision;
    }

    /**
     * The smallest amount of work, in blocks, that is worth sending to the GPU. Below this the fixed cost of the
     * transfer and the kernel launch dominates and the CPU wins, so the CPU path is used instead.
     */
    public static int getMinimumBatchSize() {
        return minimumBatchSize;
    }

    public static synchronized void setMinimumBatchSize(int minimumBatchSize) {
        GpuSettings.minimumBatchSize = minimumBatchSize;
    }

    /**
     * Reset all settings to their defaults, re-reading the system properties. Also discards any device that was
     * already selected, so that the next request re-runs device discovery.
     */
    public static synchronized void reset() {
        loadDefaults();
        GpuContext.shutdown();
    }

    private static void loadDefaults() {
        mode                   = parseEnum(Mode.class, System.getProperty(MODE_PROPERTY), Mode.AUTO);
        devicePreference       = parseEnum(DevicePreference.class, System.getProperty(DEVICE_PREFERENCE_PROPERTY), DevicePreference.DEDICATED_GPU);
        deviceNameFilter       = System.getProperty(DEVICE_NAME_PROPERTY);
        requireDoublePrecision = ! "false".equalsIgnoreCase(System.getProperty(FP64_PROPERTY));
        minimumBatchSize       = parseInt(System.getProperty(MIN_BATCH_PROPERTY), DEFAULT_MINIMUM_BATCH_SIZE);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E defaultValue) {
        if ((value == null) || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static final String MODE_PROPERTY              = "org.pepsoft.worldpainter.gpu.mode";
    public static final String DEVICE_PREFERENCE_PROPERTY = "org.pepsoft.worldpainter.gpu.devicePreference";
    public static final String DEVICE_NAME_PROPERTY       = "org.pepsoft.worldpainter.gpu.deviceName";
    public static final String FP64_PROPERTY              = "org.pepsoft.worldpainter.gpu.requireFp64";
    public static final String MIN_BATCH_PROPERTY         = "org.pepsoft.worldpainter.gpu.minimumBatchSize";

    /**
     * A single chunk of 16 x 16 columns, 32 blocks deep. Anything smaller than this is not worth a round trip over the
     * bus, and the CPU path is used instead.
     */
    static final int DEFAULT_MINIMUM_BATCH_SIZE = 16 * 16 * 32;

    private static volatile Mode mode;
    private static volatile DevicePreference devicePreference;
    private static volatile String deviceNameFilter;
    private static volatile boolean requireDoublePrecision;
    private static volatile int minimumBatchSize;

    static {
        loadDefaults();
    }
}
