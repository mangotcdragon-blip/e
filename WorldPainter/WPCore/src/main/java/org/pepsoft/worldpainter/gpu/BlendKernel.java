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

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Runs the terrain and height blending kernels.
 *
 * <p>Blending is a stencil operation: every column looks at a neighbourhood around it, and none of them depend on
 * each other. A map is millions of columns, and a height blend of radius four reads eighty-one of them per column, so
 * this is worth an awful lot more on a GPU than it costs to get the data there.
 *
 * <p>Each call allocates and frees its own buffers. Blending processes a map in large blocks rather than in chunks, so
 * there are only a handful of calls per operation and pooling would not earn its complexity.
 */
public final class BlendKernel {
    private BlendKernel() {
        // Prevent instantiation
    }

    /**
     * Whether blending can currently run on the GPU.
     */
    public static boolean isAvailable() {
        return GpuPerlinNoise.isVerified();
    }

    /**
     * Blend the terrain of one block of columns on the device.
     *
     * <p>The arguments mirror {@code BlendAlgorithm.blendTerrain}, which is what runs when this returns {@code false}.
     *
     * @param source      Terrain indices for the area plus {@code margin} blocks on every side, row major.
     * @param destination Receives {@code width * height} terrain indices, row major.
     * @return {@code true} if the block was blended. {@code false} means the caller has to do it on the CPU.
     */
    public static boolean blendTerrain(int[] source, int[] destination, int width, int height, int margin,
                                       int originX, int originY, int radius, float scale, float jitter,
                                       boolean coherent, boolean stochastic, boolean boundariesOnly,
                                       long warpSeedX, long warpSeedY, long hashSeed) {
        final GpuContext context = GpuContext.get();
        if ((context == null) || (! isAvailable())) {
            return false;
        }
        final int columnCount = width * height;
        final int sourceCount = (width + (2 * margin)) * (height + (2 * margin));
        if (columnCount < GpuSettings.getMinimumBatchSize()) {
            return false;
        }
        IntBuffer hostSource = null, hostDestination = null;
        ShortBuffer hostPermutations = null;
        long deviceSource = 0, deviceDestination = 0, devicePermX = 0, devicePermY = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer errorCode = stack.mallocInt(1);
            final long queue = context.getQueue();
            final long program = context.getProgram(PROGRAM, null);
            final long kernel = context.getKernel(program, "wp_blend_terrain");

            hostSource = memAllocInt(sourceCount);
            hostSource.put(source, 0, sourceCount).flip();
            deviceSource = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostSource, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(source)");

            hostPermutations = memAllocShort(PerlinPermutation.SIZE);
            hostPermutations.put(PerlinPermutation.forSeed(warpSeedX)).flip();
            devicePermX = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostPermutations, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(permX)");
            hostPermutations.clear();
            hostPermutations.put(PerlinPermutation.forSeed(warpSeedY)).flip();
            devicePermY = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostPermutations, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(permY)");

            deviceDestination = clCreateBuffer(context.getHandle(), CL_MEM_WRITE_ONLY, (long) columnCount * Integer.BYTES, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(destination)");

            CLErrors.check(clSetKernelArg1p(kernel, 0, deviceSource), "clSetKernelArg(source)");
            CLErrors.check(clSetKernelArg1p(kernel, 1, deviceDestination), "clSetKernelArg(destination)");
            CLErrors.check(clSetKernelArg1p(kernel, 2, devicePermX), "clSetKernelArg(permX)");
            CLErrors.check(clSetKernelArg1p(kernel, 3, devicePermY), "clSetKernelArg(permY)");
            CLErrors.check(clSetKernelArg1i(kernel, 4, width), "clSetKernelArg(width)");
            CLErrors.check(clSetKernelArg1i(kernel, 5, height), "clSetKernelArg(height)");
            CLErrors.check(clSetKernelArg1i(kernel, 6, margin), "clSetKernelArg(margin)");
            CLErrors.check(clSetKernelArg1i(kernel, 7, originX), "clSetKernelArg(originX)");
            CLErrors.check(clSetKernelArg1i(kernel, 8, originY), "clSetKernelArg(originY)");
            CLErrors.check(clSetKernelArg1i(kernel, 9, radius), "clSetKernelArg(radius)");
            CLErrors.check(clSetKernelArg1f(kernel, 10, scale), "clSetKernelArg(scale)");
            CLErrors.check(clSetKernelArg1f(kernel, 11, jitter), "clSetKernelArg(jitter)");
            CLErrors.check(clSetKernelArg1i(kernel, 12, coherent ? 1 : 0), "clSetKernelArg(coherent)");
            CLErrors.check(clSetKernelArg1i(kernel, 13, stochastic ? 1 : 0), "clSetKernelArg(stochastic)");
            CLErrors.check(clSetKernelArg1i(kernel, 14, boundariesOnly ? 1 : 0), "clSetKernelArg(boundariesOnly)");
            CLErrors.check(clSetKernelArg1i(kernel, 15, (int) hashSeed), "clSetKernelArg(seedLow)");
            CLErrors.check(clSetKernelArg1i(kernel, 16, (int) (hashSeed >>> 32)), "clSetKernelArg(seedHigh)");

            enqueue(context, queue, kernel, columnCount, stack, "wp_blend_terrain");

            hostDestination = memAllocInt(columnCount);
            CLErrors.check(clEnqueueReadBuffer(queue, deviceDestination, true, 0, hostDestination, null, null),
                    "clEnqueueReadBuffer(destination)");
            hostDestination.get(destination, 0, columnCount);
            return true;
        } catch (GpuException e) {
            GpuContext.disable("the terrain blending kernel failed", e);
            return false;
        } catch (Throwable t) {
            GpuContext.disable("the terrain blending kernel failed unexpectedly", t);
            return false;
        } finally {
            releaseAll(deviceDestination, devicePermY, devicePermX, deviceSource);
            memFree(hostDestination);
            memFree(hostPermutations);
            memFree(hostSource);
        }
    }

    /**
     * Smooth the height map of one block of columns on the device.
     *
     * @return {@code true} if the block was blended. {@code false} means the caller has to do it on the CPU.
     */
    public static boolean blendHeight(float[] source, float[] destination, int width, int height, int margin,
                                      float[] weights, float strength, float slopeThreshold) {
        final GpuContext context = GpuContext.get();
        if ((context == null) || (! isAvailable())) {
            return false;
        }
        final int columnCount = width * height;
        final int sourceCount = (width + (2 * margin)) * (height + (2 * margin));
        if (columnCount < GpuSettings.getMinimumBatchSize()) {
            return false;
        }
        FloatBuffer hostSource = null, hostWeights = null, hostDestination = null;
        long deviceSource = 0, deviceWeights = 0, deviceDestination = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer errorCode = stack.mallocInt(1);
            final long queue = context.getQueue();
            final long program = context.getProgram(PROGRAM, null);
            final long kernel = context.getKernel(program, "wp_blend_height");

            hostSource = memAllocFloat(sourceCount);
            hostSource.put(source, 0, sourceCount).flip();
            deviceSource = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostSource, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(source)");

            hostWeights = memAllocFloat(weights.length);
            hostWeights.put(weights).flip();
            deviceWeights = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, hostWeights, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(weights)");

            deviceDestination = clCreateBuffer(context.getHandle(), CL_MEM_WRITE_ONLY, (long) columnCount * Float.BYTES, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(destination)");

            CLErrors.check(clSetKernelArg1p(kernel, 0, deviceSource), "clSetKernelArg(source)");
            CLErrors.check(clSetKernelArg1p(kernel, 1, deviceDestination), "clSetKernelArg(destination)");
            CLErrors.check(clSetKernelArg1p(kernel, 2, deviceWeights), "clSetKernelArg(weights)");
            CLErrors.check(clSetKernelArg1i(kernel, 3, width), "clSetKernelArg(width)");
            CLErrors.check(clSetKernelArg1i(kernel, 4, height), "clSetKernelArg(height)");
            CLErrors.check(clSetKernelArg1i(kernel, 5, margin), "clSetKernelArg(margin)");
            CLErrors.check(clSetKernelArg1f(kernel, 6, strength), "clSetKernelArg(strength)");
            CLErrors.check(clSetKernelArg1f(kernel, 7, slopeThreshold), "clSetKernelArg(slopeThreshold)");

            enqueue(context, queue, kernel, columnCount, stack, "wp_blend_height");

            hostDestination = memAllocFloat(columnCount);
            CLErrors.check(clEnqueueReadBuffer(queue, deviceDestination, true, 0, hostDestination, null, null),
                    "clEnqueueReadBuffer(destination)");
            hostDestination.get(destination, 0, columnCount);
            return true;
        } catch (GpuException e) {
            GpuContext.disable("the height blending kernel failed", e);
            return false;
        } catch (Throwable t) {
            GpuContext.disable("the height blending kernel failed unexpectedly", t);
            return false;
        } finally {
            releaseAll(deviceDestination, deviceWeights, deviceSource);
            memFree(hostDestination);
            memFree(hostWeights);
            memFree(hostSource);
        }
    }

    private static void enqueue(GpuContext context, long queue, long kernel, int workItems, MemoryStack stack, String what) {
        final int localSize = (int) Math.min(WORK_GROUP_SIZE, context.getDevice().getMaxWorkGroupSize());
        final PointerBuffer globalWorkSize = stack.mallocPointer(1).put(0, GpuContext.roundUp(workItems, localSize));
        final PointerBuffer localWorkSize = stack.mallocPointer(1).put(0, localSize);
        CLErrors.check(clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, localWorkSize, null, null),
                "clEnqueueNDRangeKernel(" + what + ')');
    }

    private static void releaseAll(long... buffers) {
        for (long buffer: buffers) {
            if (buffer != 0) {
                clReleaseMemObject(buffer);
            }
        }
    }

    static final String PROGRAM = "blend.cl";

    private static final int WORK_GROUP_SIZE = 64;
}
