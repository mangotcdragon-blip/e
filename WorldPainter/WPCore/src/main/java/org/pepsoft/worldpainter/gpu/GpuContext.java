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

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL11.CL_DEVICE_HOST_UNIFIED_MEMORY;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * The process-wide OpenCL context WorldPainter uses for compute work, together with everything needed to run a kernel
 * on it.
 *
 * <p>There is at most one of these, on one device. Exports are multi threaded, so the context hands out a command
 * queue and a set of kernel objects per thread: OpenCL contexts, programs and buffers are safe to share between
 * threads, but command queues and kernels are not (setting an argument on a kernel another thread is about to enqueue
 * would race).
 *
 * <p>Nothing here ever propagates a failure to the caller's caller. If the device disappears (a driver reset, a laptop
 * switching GPUs, an out of memory condition) the context latches itself off and every subsequent request returns
 * {@code null}, which puts the export back on the CPU path for the rest of the session.
 */
public final class GpuContext {
    private GpuContext(GpuDevice device, long contextHandle) {
        this.device = device;
        this.contextHandle = contextHandle;
    }

    /**
     * Get the shared context, creating it on first use.
     *
     * @return The context, or {@code null} if GPU acceleration is switched off, no suitable device could be found, or
     * the device previously failed.
     */
    public static synchronized GpuContext get() {
        if (GpuSettings.getMode() == GpuSettings.Mode.OFF) {
            return null;
        }
        if (instance != null) {
            return instance;
        }
        if (initialisationFailed) {
            return null;
        }
        try {
            instance = create();
            if (instance == null) {
                initialisationFailed = true;
                if (GpuSettings.getMode() == GpuSettings.Mode.FORCE) {
                    throw new GpuException("No suitable OpenCL device found, and GPU acceleration is set to FORCE");
                }
                logger.info("No suitable OpenCL device found; exports will use the CPU");
            } else {
                logger.info("Using GPU acceleration on {}", instance.device.getDescription());
            }
            return instance;
        } catch (GpuException e) {
            initialisationFailed = true;
            if (GpuSettings.getMode() == GpuSettings.Mode.FORCE) {
                throw e;
            }
            logger.warn("Could not initialise GPU acceleration ({}); exports will use the CPU", e.getMessage());
            return null;
        } catch (Throwable t) {
            // A broken or half installed driver can throw anything at all, including errors from the native layer.
            // None of it should ever stop someone exporting a map.
            initialisationFailed = true;
            if (GpuSettings.getMode() == GpuSettings.Mode.FORCE) {
                throw new GpuException("Could not initialise GPU acceleration", t);
            }
            logger.warn("Could not initialise GPU acceleration; exports will use the CPU", t);
            return null;
        }
    }

    /**
     * Release the context and everything created from it, and allow a subsequent {@link #get()} to try again. Safe to
     * call when there is no context.
     */
    public static synchronized void shutdown() {
        initialisationFailed = false;
        if (instance != null) {
            instance.release();
            instance = null;
        }
    }

    /**
     * Report that a GPU operation failed. The context is torn down and all further work goes to the CPU. Called from
     * the export path, which must never fail because of an accelerator.
     */
    public static synchronized void disable(String reason, Throwable cause) {
        if (instance != null) {
            logger.warn("Disabling GPU acceleration for the rest of this session: {}", reason, cause);
            instance.release();
            instance = null;
        }
        initialisationFailed = true;
    }

    /**
     * All OpenCL devices present on this machine, whether or not they are suitable. Intended for a settings screen and
     * for diagnostics; returns an empty list when there is no OpenCL runtime.
     */
    public static List<GpuDevice> enumerateDevices() {
        try {
            if (! OpenCLLoader.ensureLoaded()) {
                return emptyList();
            }
            return unmodifiableList(discoverDevices());
        } catch (Throwable t) {
            logger.debug("Could not enumerate OpenCL devices", t);
            return emptyList();
        }
    }

    public GpuDevice getDevice() {
        return device;
    }

    public long getHandle() {
        return contextHandle;
    }

    /**
     * The command queue for the calling thread, created on first use.
     */
    public long getQueue() {
        Long queue = threadQueue.get();
        if (queue == null) {
            synchronized (this) {
                if (released) {
                    throw new GpuException("Context has been released");
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    final IntBuffer errorCode = stack.mallocInt(1);
                    final long handle = clCreateCommandQueue(contextHandle, device.getDeviceId(), 0, errorCode);
                    CLErrors.check(errorCode.get(0), "clCreateCommandQueue");
                    queues.add(handle);
                    queue = handle;
                }
            }
            threadQueue.set(queue);
        }
        return queue;
    }

    /**
     * Compile (or return an already compiled) OpenCL program from a source file on the classpath, next to this class.
     *
     * @param resourceName The name of the {@code .cl} file, e.g. {@code "perlin.cl"}.
     * @param buildOptions Options to pass to the OpenCL compiler, or {@code null}. Programs built with different
     *                     options are compiled and cached separately.
     */
    public synchronized long getProgram(String resourceName, String buildOptions) {
        if (released) {
            throw new GpuException("Context has been released");
        }
        final String key = resourceName + ' ' + ((buildOptions != null) ? buildOptions : "");
        final Long existing = programs.get(key);
        if (existing != null) {
            return existing;
        }
        final long program = buildProgram(readSource(resourceName), buildOptions, resourceName);
        programs.put(key, program);
        return program;
    }

    /**
     * Get a kernel from a program, private to the calling thread. Kernel objects hold their arguments, so they cannot
     * be shared between threads that are enqueuing work concurrently.
     */
    public long getKernel(long program, String kernelName) {
        final Map<String, Long> perThread = threadKernels.get();
        final String key = program + "/" + kernelName;
        Long kernel = perThread.get(key);
        if (kernel == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final IntBuffer errorCode = stack.mallocInt(1);
                final long handle = clCreateKernel(program, kernelName, errorCode);
                CLErrors.check(errorCode.get(0), "clCreateKernel(" + kernelName + ')');
                synchronized (this) {
                    if (released) {
                        clReleaseKernel(handle);
                        throw new GpuException("Context has been released");
                    }
                    kernels.add(handle);
                }
                kernel = handle;
            }
            perThread.put(key, kernel);
        }
        return kernel;
    }

    /**
     * Allocate a device buffer and keep track of it, so that it is released along with the context even if the code
     * that allocated it is long gone. Intended for the buffers the export re-uses from chunk to chunk; short lived
     * buffers are better released explicitly with {@link #releaseBuffer(long)}.
     */
    public long createBuffer(long flags, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer errorCode = stack.mallocInt(1);
            final long buffer = clCreateBuffer(contextHandle, flags, size, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(" + size + " bytes)");
            synchronized (this) {
                if (released) {
                    clReleaseMemObject(buffer);
                    throw new GpuException("Context has been released");
                }
                buffers.add(buffer);
            }
            return buffer;
        }
    }

    /**
     * Release a buffer created by {@link #createBuffer(long, long)}. Safe to call with 0.
     */
    public synchronized void releaseBuffer(long buffer) {
        if (buffer == 0) {
            return;
        }
        if (buffers.remove(buffer) && (! released)) {
            clReleaseMemObject(buffer);
        }
    }

    /**
     * The largest single buffer this device will allocate, in bytes.
     */
    public long getMaxAllocationSize() {
        return device.getMaxAllocation();
    }

    /**
     * Round {@code globalSize} up to a multiple of {@code localSize}, as {@code clEnqueueNDRangeKernel} requires. The
     * kernels bounds check themselves, so the extra work items simply return.
     */
    public static int roundUp(int globalSize, int localSize) {
        final int remainder = globalSize % localSize;
        return (remainder == 0) ? globalSize : (globalSize + localSize - remainder);
    }

    private long buildProgram(String source, String buildOptions, String description) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer errorCode = stack.mallocInt(1);
            final long program = clCreateProgramWithSource(contextHandle, source, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateProgramWithSource(" + description + ')');
            final int result = clBuildProgram(program, device.getDeviceId(), (buildOptions != null) ? buildOptions : "", null, 0);
            if (result != CL_SUCCESS) {
                final String log = getBuildLog(program);
                clReleaseProgram(program);
                throw new GpuException("Could not compile " + description + ": " + log, result);
            }
            return program;
        }
    }

    private String getBuildLog(long program) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer size = stack.mallocPointer(1);
            if (clGetProgramBuildInfo(program, device.getDeviceId(), CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, size) != CL_SUCCESS) {
                return "<no build log available>";
            }
            final int length = (int) size.get(0);
            if (length <= 1) {
                return "<empty build log>";
            }
            final ByteBuffer log = MemoryUtil.memAlloc(length);
            try {
                clGetProgramBuildInfo(program, device.getDeviceId(), CL_PROGRAM_BUILD_LOG, log, null);
                return memUTF8(log, length - 1).trim();
            } finally {
                MemoryUtil.memFree(log);
            }
        }
    }

    /**
     * Read an OpenCL source file from the classpath, resolving {@code #include "name.cl"} directives against the other
     * source files in this package. OpenCL has no include path of its own when a program is built from a string, so
     * the shared code (the Perlin sampler, mostly) is stitched in here instead of being duplicated in every kernel.
     */
    static String readSource(String resourceName) {
        final StringBuilder source = new StringBuilder();
        appendSource(resourceName, source, new HashSet<>());
        return source.toString();
    }

    private static void appendSource(String resourceName, StringBuilder source, Set<String> alreadyIncluded) {
        if (! alreadyIncluded.add(resourceName)) {
            return;
        }
        final String text;
        try (InputStream in = GpuContext.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new GpuException("OpenCL source \"" + resourceName + "\" not found on the classpath");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GpuException("I/O error while reading OpenCL source \"" + resourceName + '"', e);
        }
        for (String line: text.split("\n", -1)) {
            final Matcher matcher = INCLUDE_PATTERN.matcher(line);
            if (matcher.matches()) {
                appendSource(matcher.group(1), source, alreadyIncluded);
            } else {
                source.append(line).append('\n');
            }
        }
    }

    private static GpuContext create() {
        if (! OpenCLLoader.ensureLoaded()) {
            return null;
        }
        final List<GpuDevice> devices = discoverDevices();
        if (devices.isEmpty()) {
            return null;
        }
        final GpuDevice device = selectDevice(devices);
        if (device == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("None of the {} OpenCL device(s) present met the requirements: {}", devices.size(), devices);
            }
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer contextProperties = stack.mallocPointer(3);
            contextProperties.put(0, CL_CONTEXT_PLATFORM).put(1, device.getPlatformId()).put(2, 0);
            final PointerBuffer deviceIds = stack.mallocPointer(1);
            deviceIds.put(0, device.getDeviceId());
            final IntBuffer errorCode = stack.mallocInt(1);
            final long handle = clCreateContext(contextProperties, deviceIds, null, 0, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateContext");
            return new GpuContext(device, handle);
        }
    }

    /**
     * Pick the device to use, honouring the configured preference and name filter. Package visible so that the
     * selection rules can be tested without an actual device.
     */
    static GpuDevice selectDevice(List<GpuDevice> devices) {
        final String nameFilter = GpuSettings.getDeviceNameFilter();
        final GpuSettings.DevicePreference preference = GpuSettings.getDevicePreference();
        GpuDevice best = null;
        long bestScore = Long.MIN_VALUE;
        for (GpuDevice candidate: devices) {
            if (GpuSettings.isRequireDoublePrecision() && (! candidate.isDoublePrecision())) {
                continue;
            }
            if ((nameFilter != null) && (! candidate.getName().toLowerCase().contains(nameFilter.toLowerCase()))) {
                continue;
            }
            final long score = candidate.getScore(preference);
            if (score == Long.MIN_VALUE) {
                continue;
            }
            if ((best == null) || (score > bestScore)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static List<GpuDevice> discoverDevices() {
        final List<GpuDevice> devices = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer platformCount = stack.mallocInt(1);
            if ((clGetPlatformIDs(null, platformCount) != CL_SUCCESS) || (platformCount.get(0) == 0)) {
                return devices;
            }
            final PointerBuffer platformIds = stack.mallocPointer(platformCount.get(0));
            CLErrors.check(clGetPlatformIDs(platformIds, (IntBuffer) null), "clGetPlatformIDs");
            for (int p = 0; p < platformIds.capacity(); p++) {
                final long platformId = platformIds.get(p);
                final String platformName = getPlatformInfoString(platformId, CL_PLATFORM_NAME);
                final IntBuffer deviceCount = stack.mallocInt(1);
                if ((clGetDeviceIDs(platformId, CL_DEVICE_TYPE_ALL, null, deviceCount) != CL_SUCCESS) || (deviceCount.get(0) == 0)) {
                    continue;
                }
                final PointerBuffer deviceIds = stack.mallocPointer(deviceCount.get(0));
                if (clGetDeviceIDs(platformId, CL_DEVICE_TYPE_ALL, deviceIds, (IntBuffer) null) != CL_SUCCESS) {
                    continue;
                }
                for (int d = 0; d < deviceIds.capacity(); d++) {
                    final long deviceId = deviceIds.get(d);
                    try {
                        final String extensions = getDeviceInfoString(deviceId, CL_DEVICE_EXTENSIONS);
                        devices.add(new GpuDevice(platformId, deviceId, platformName,
                                getDeviceInfoString(deviceId, CL_DEVICE_NAME),
                                getDeviceInfoString(deviceId, CL_DEVICE_VENDOR),
                                getDeviceInfoString(deviceId, CL_DEVICE_VERSION),
                                getDeviceInfoLong(deviceId, CL_DEVICE_TYPE),
                                (int) getDeviceInfoLong(deviceId, CL_DEVICE_MAX_COMPUTE_UNITS),
                                (int) getDeviceInfoLong(deviceId, CL_DEVICE_MAX_CLOCK_FREQUENCY),
                                getDeviceInfoLong(deviceId, CL_DEVICE_GLOBAL_MEM_SIZE),
                                getDeviceInfoLong(deviceId, CL_DEVICE_MAX_MEM_ALLOC_SIZE),
                                getDeviceInfoLong(deviceId, CL_DEVICE_MAX_WORK_GROUP_SIZE),
                                getDeviceInfoLong(deviceId, CL_DEVICE_HOST_UNIFIED_MEMORY) != 0,
                                extensions.contains("cl_khr_fp64")));
                    } catch (RuntimeException e) {
                        logger.debug("Skipping OpenCL device which could not be queried", e);
                    }
                }
            }
        }
        return devices;
    }

    private static String getPlatformInfoString(long platformId, int parameter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer size = stack.mallocPointer(1);
            CLErrors.check(clGetPlatformInfo(platformId, parameter, (ByteBuffer) null, size), "clGetPlatformInfo");
            final int length = (int) size.get(0);
            if (length <= 1) {
                return "";
            }
            final ByteBuffer value = stack.malloc(length);
            CLErrors.check(clGetPlatformInfo(platformId, parameter, value, null), "clGetPlatformInfo");
            return memUTF8(value, length - 1);
        }
    }

    private static String getDeviceInfoString(long deviceId, int parameter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer size = stack.mallocPointer(1);
            CLErrors.check(clGetDeviceInfo(deviceId, parameter, (ByteBuffer) null, size), "clGetDeviceInfo");
            final int length = (int) size.get(0);
            if (length <= 1) {
                return "";
            }
            final ByteBuffer value = stack.malloc(length);
            CLErrors.check(clGetDeviceInfo(deviceId, parameter, value, null), "clGetDeviceInfo");
            return memUTF8(value, length - 1);
        }
    }

    private static long getDeviceInfoLong(long deviceId, int parameter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer size = stack.mallocPointer(1);
            if (clGetDeviceInfo(deviceId, parameter, (ByteBuffer) null, size) != CL_SUCCESS) {
                // Not every optional parameter is supported by every driver; treat a missing one as zero
                return 0;
            }
            final int length = (int) size.get(0);
            final ByteBuffer value = stack.malloc(Math.max(length, 8));
            if (clGetDeviceInfo(deviceId, parameter, value, null) != CL_SUCCESS) {
                return 0;
            }
            switch (length) {
                case 1:
                    return value.get(0) & 0xffL;
                case 4:
                    return value.getInt(0) & 0xffffffffL;
                default:
                    return value.getLong(0);
            }
        }
    }

    private synchronized void release() {
        if (released) {
            return;
        }
        released = true;
        for (Long buffer: buffers) {
            clReleaseMemObject(buffer);
        }
        buffers.clear();
        for (Long kernel: kernels) {
            clReleaseKernel(kernel);
        }
        kernels.clear();
        for (Long program: programs.values()) {
            clReleaseProgram(program);
        }
        programs.clear();
        for (Long queue: queues) {
            clReleaseCommandQueue(queue);
        }
        queues.clear();
        clReleaseContext(contextHandle);
    }

    private final GpuDevice device;
    private final long contextHandle;
    private final Map<String, Long> programs = new HashMap<>();
    private final List<Long> queues = new ArrayList<>();
    private final List<Long> kernels = new ArrayList<>();
    private final Set<Long> buffers = new HashSet<>();
    private final ThreadLocal<Long> threadQueue = new ThreadLocal<>();
    private final ThreadLocal<Map<String, Long>> threadKernels = ThreadLocal.withInitial(HashMap::new);
    private volatile boolean released;

    private static GpuContext instance;
    private static boolean initialisationFailed;

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("\\s*#include\\s+\"([^\"]+)\"\\s*");

    private static final Logger logger = LoggerFactory.getLogger(GpuContext.class);
}
