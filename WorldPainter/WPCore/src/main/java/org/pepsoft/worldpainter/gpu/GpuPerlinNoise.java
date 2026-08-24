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
import org.pepsoft.util.PerlinNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Random;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Evaluates WorldPainter's Perlin noise on an OpenCL device, and verifies that it does so <em>exactly</em> as the CPU
 * does.
 *
 * <p>The verification is the point. Everything WorldPainter generates below the surface comes out of this noise
 * function, so if a driver rounds one multiply differently the accelerated export would place a different block, and
 * the same map would depend on the machine that exported it. Rather than hope, {@link #selfTest()} samples the noise
 * at several thousand pseudo-random coordinates on the device and compares the raw bits against
 * {@link PerlinNoise}. A device that disagrees anywhere is not used.
 */
public final class GpuPerlinNoise {
    private GpuPerlinNoise() {
        // Prevent instantiation
    }

    /**
     * Whether the current device has been verified to reproduce WorldPainter's Perlin noise exactly. Runs the check
     * once per context; subsequent calls return the cached answer.
     *
     * @return {@code false} if there is no GPU, or if there is one but it cannot be trusted with this work.
     */
    public static synchronized boolean isVerified() {
        final GpuContext context = GpuContext.get();
        if (context == null) {
            verifiedContext = null;
            return false;
        }
        if (verifiedContext == context) {
            return verified;
        }
        verifiedContext = context;
        try {
            verified = selfTest();
            if (! verified) {
                logger.warn("The OpenCL device {} does not reproduce WorldPainter's noise exactly; not using it for "
                        + "terrain generation", context.getDevice().getName());
            }
        } catch (Throwable t) {
            logger.warn("Could not verify the OpenCL device; not using it for terrain generation", t);
            verified = false;
        }
        return verified;
    }

    /**
     * Sample the noise on the device at a large number of pseudo-random coordinates and seeds and compare the result,
     * bit for bit, against the CPU implementation.
     *
     * @return {@code true} if every sample matched.
     */
    public static boolean selfTest() {
        final Random random = new Random(SELF_TEST_SEED);
        for (int round = 0; round < SELF_TEST_ROUNDS; round++) {
            final long seed = random.nextLong();
            final double[] coordinates = new double[SELF_TEST_SAMPLES * 3];
            for (int i = 0; i < coordinates.length; i++) {
                // Cover both the tightly packed coordinates the resource patterns use and much larger ones, including
                // negatives, which is where a sloppy floor() or a truncating cast would show up
                coordinates[i] = (random.nextDouble() - 0.5) * ((round % 2 == 0) ? 512 : 32);
            }
            final float[] gpuValues = sample3(seed, coordinates, SELF_TEST_SAMPLES);
            if (gpuValues == null) {
                return false;
            }
            final PerlinNoise reference = new PerlinNoise(seed);
            for (int i = 0; i < SELF_TEST_SAMPLES; i++) {
                final float expected = reference.getPerlinNoise(coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]);
                if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(gpuValues[i])) {
                    logger.debug("Noise mismatch at ({}, {}, {}) with seed {}: CPU {}, GPU {}",
                            coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2], seed, expected, gpuValues[i]);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Evaluate three dimensional Perlin noise on the device.
     *
     * @param seed        The noise seed.
     * @param coordinates {@code count} triples of x, y and z coordinates.
     * @param count       The number of coordinate triples.
     * @return One noise value per triple, or {@code null} if there is no usable device.
     */
    public static float[] sample3(long seed, double[] coordinates, int count) {
        return sample(seed, coordinates, count, 3);
    }

    /**
     * Evaluate two dimensional Perlin noise on the device.
     *
     * @param seed        The noise seed.
     * @param coordinates {@code count} pairs of x and y coordinates.
     * @param count       The number of coordinate pairs.
     * @return One noise value per pair, or {@code null} if there is no usable device.
     */
    public static float[] sample2(long seed, double[] coordinates, int count) {
        return sample(seed, coordinates, count, 2);
    }

    private static float[] sample(long seed, double[] coordinates, int count, int dimensions) {
        final GpuContext context = GpuContext.get();
        if (context == null) {
            return null;
        }
        final long program = context.getProgram(PROGRAM, null);
        final long kernel = context.getKernel(program, "wp_perlin_sample");
        final long queue = context.getQueue();
        final short[] permutation = PerlinPermutation.forSeed(seed);

        ShortBuffer permutationBuffer = null;
        DoubleBuffer coordinateBuffer = null;
        FloatBuffer resultBuffer = null;
        long permutationMemory = 0, coordinateMemory = 0, resultMemory = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer errorCode = stack.mallocInt(1);

            permutationBuffer = memAllocShort(permutation.length);
            permutationBuffer.put(permutation).flip();
            permutationMemory = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, permutationBuffer, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(permutation)");

            coordinateBuffer = memAllocDouble(count * dimensions);
            coordinateBuffer.put(coordinates, 0, count * dimensions).flip();
            coordinateMemory = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, coordinateBuffer, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(coordinates)");

            resultMemory = clCreateBuffer(context.getHandle(), CL_MEM_WRITE_ONLY, (long) count * Float.BYTES, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(result)");

            CLErrors.check(clSetKernelArg1p(kernel, 0, permutationMemory), "clSetKernelArg(perm)");
            CLErrors.check(clSetKernelArg1p(kernel, 1, coordinateMemory), "clSetKernelArg(coordinates)");
            CLErrors.check(clSetKernelArg1p(kernel, 2, resultMemory), "clSetKernelArg(result)");
            CLErrors.check(clSetKernelArg1i(kernel, 3, dimensions), "clSetKernelArg(dimensions)");
            CLErrors.check(clSetKernelArg1i(kernel, 4, count), "clSetKernelArg(count)");

            final int localSize = (int) Math.min(WORK_GROUP_SIZE, context.getDevice().getMaxWorkGroupSize());
            final PointerBuffer globalWorkSize = stack.mallocPointer(1).put(0, GpuContext.roundUp(count, localSize));
            final PointerBuffer localWorkSize = stack.mallocPointer(1).put(0, localSize);
            CLErrors.check(clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, localWorkSize, null, null),
                    "clEnqueueNDRangeKernel(wp_perlin_sample)");

            resultBuffer = memAllocFloat(count);
            CLErrors.check(clEnqueueReadBuffer(queue, resultMemory, true, 0, resultBuffer, null, null),
                    "clEnqueueReadBuffer(result)");
            final float[] result = new float[count];
            resultBuffer.get(result);
            return result;
        } finally {
            if (resultMemory != 0) {
                clReleaseMemObject(resultMemory);
            }
            if (coordinateMemory != 0) {
                clReleaseMemObject(coordinateMemory);
            }
            if (permutationMemory != 0) {
                clReleaseMemObject(permutationMemory);
            }
            memFree(resultBuffer);
            memFree(coordinateBuffer);
            memFree(permutationBuffer);
        }
    }

    static final String PROGRAM = "perlin.cl";

    private static final int WORK_GROUP_SIZE = 64;
    private static final int SELF_TEST_ROUNDS = 8;
    private static final int SELF_TEST_SAMPLES = 4096;
    private static final long SELF_TEST_SEED = 0x5EEDCAFEL;

    private static GpuContext verifiedContext;
    private static boolean verified;

    private static final Logger logger = LoggerFactory.getLogger(GpuPerlinNoise.class);
}
