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

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.pepsoft.worldpainter.Constants.SMALL_BLOBS;
import static org.pepsoft.worldpainter.Constants.TINY_BLOBS;

/**
 * Places the Resources layer's ores, dirt and gravel throughout a chunk in one dispatch.
 *
 * <p>This is the single most expensive thing WorldPainter does. For every block below the surface the exporter walks
 * a list of a dozen or so materials, evaluating a three dimensional Perlin noise field for each until one of them
 * comes out above its threshold. On a large map that is billions of noise evaluations, and it is exactly the shape of
 * work a GPU is built for: every block is independent and they all run the same code.
 *
 * <p>The kernel returns the index of the material to place, not the material itself, so all of the version dependent
 * substitutions (deepslate ore variants, nether gold) stay in {@code ResourcesExporter} where they belong.
 */
public final class ResourceLayerKernel {
    private ResourceLayerKernel(GpuContext context, Configuration configuration) {
        this.context = context;
        this.materialCount = configuration.seedOffsets.length;
        program = context.getProgram(PROGRAM, null);

        ShortBuffer permutations = null;
        FloatBuffer chances = null;
        IntBuffer levels = null;
        ByteBuffer scales = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer errorCode = stack.mallocInt(1);

            permutations = memAllocShort(materialCount * PerlinPermutation.SIZE);
            for (int i = 0; i < materialCount; i++) {
                permutations.put(PerlinPermutation.forSeed(configuration.seed + configuration.seedOffsets[i]));
            }
            permutations.flip();
            permutationBuffer = createBuffer(context, permutations, errorCode, "permutations");

            chances = memAllocFloat(materialCount * CHANCES_PER_MATERIAL);
            for (int i = 0; i < materialCount; i++) {
                chances.put(configuration.chances[i], 0, CHANCES_PER_MATERIAL);
            }
            chances.flip();
            chanceBuffer = createBuffer(context, chances, errorCode, "chances");

            levels = memAllocInt(materialCount);
            levels.put(configuration.minLevels, 0, materialCount).flip();
            minLevelBuffer = createBuffer(context, levels, errorCode, "minLevels");
            levels.clear();
            levels.put(configuration.maxLevels, 0, materialCount).flip();
            maxLevelBuffer = createBuffer(context, levels, errorCode, "maxLevels");

            scales = memAlloc(materialCount);
            for (int i = 0; i < materialCount; i++) {
                scales.put((byte) (configuration.smallBlobScale[i] ? 1 : 0));
            }
            scales.flip();
            scaleBuffer = createBuffer(context, scales, errorCode, "scales");
        } finally {
            memFree(scales);
            memFree(levels);
            memFree(chances);
            memFree(permutations);
        }
    }

    /**
     * Get the kernel for a Resources layer configuration, or {@code null} if there is no usable GPU or the
     * configuration is not one the kernel can handle.
     *
     * <p>Every region of an export is set up with the same configuration, so the instance and the tables it uploaded
     * are shared between all the export threads; only the per-chunk scratch space is per thread.
     *
     * @param seed          The dimension seed.
     * @param seedOffsets   The per-material seed offset, in the order the materials are to be tried.
     * @param minLevels     The lowest y each material may occur at.
     * @param maxLevels     The highest y each material may occur at.
     * @param chances       For each material, sixteen thresholds indexed by the value of the Resources layer.
     * @param smallBlobScale Whether each material is sampled at the "small blobs" scale rather than "tiny blobs".
     */
    public static ResourceLayerKernel get(long seed, long[] seedOffsets, int[] minLevels, int[] maxLevels,
                                          float[][] chances, boolean[] smallBlobScale) {
        if (! GpuPerlinNoise.isVerified()) {
            return null;
        }
        final int materialCount = seedOffsets.length;
        if ((materialCount == 0) || (materialCount > MAX_MATERIALS)) {
            // More materials than fit in a byte of palette index; vanishingly unlikely, but the CPU path handles it
            return null;
        }
        final GpuContext context = GpuContext.get();
        if (context == null) {
            return null;
        }
        try {
            final Configuration configuration = new Configuration(seed, seedOffsets, minLevels, maxLevels, chances, smallBlobScale);
            final ResourceLayerKernel existing = INSTANCES.get(configuration);
            if ((existing != null) && (existing.context == context)) {
                return existing;
            }
            final ResourceLayerKernel kernel = new ResourceLayerKernel(context, configuration);
            INSTANCES.put(configuration, kernel);
            return kernel;
        } catch (GpuException e) {
            GpuContext.disable("could not set up the Resources kernel", e);
            return null;
        } catch (Throwable t) {
            GpuContext.disable("unexpected failure while setting up the Resources kernel", t);
            return null;
        }
    }

    /**
     * Decide what the Resources layer places in every block of one chunk.
     *
     * @param columns     Five ints per column, in column order: world X, world Y, the lowest y to consider, the
     *                    highest y to consider (inclusive) and the value of the Resources layer for that column.
     * @param columnCount The number of columns described by {@code columns}.
     * @param yBase       The y coordinate the returned volume starts at.
     * @param depth       The number of y levels in the returned volume.
     * @return {@code columnCount * depth} material indices, indexed {@code column * depth + (y - yBase)}, or
     * {@code null} if the work could not be done on the GPU. {@link #NONE} means no resource goes in that block.
     */
    public byte[] generate(int[] columns, int columnCount, int yBase, int depth) {
        if (GpuContext.get() != context) {
            // The context was torn down under us; the caller will fall back to the CPU
            return null;
        }
        final int blockCount = columnCount * depth;
        if (blockCount <= 0) {
            return EMPTY;
        }
        try {
            final ChunkScratch scratch = getScratch();
            scratch.ensureCapacity(COLUMN_STRIDE, columnCount, blockCount);
            final IntBuffer hostColumns = scratch.getHostColumns();
            hostColumns.put(columns, 0, columnCount * COLUMN_STRIDE).flip();

            final long queue = context.getQueue();
            final long kernel = context.getKernel(program, "wp_resources_layer");
            CLErrors.check(clEnqueueWriteBuffer(queue, scratch.getDeviceColumns(), false, 0, hostColumns, null, null),
                    "clEnqueueWriteBuffer(columns)");

            CLErrors.check(clSetKernelArg1p(kernel, 0, permutationBuffer), "clSetKernelArg(perms)");
            CLErrors.check(clSetKernelArg1p(kernel, 1, scratch.getDeviceColumns()), "clSetKernelArg(columns)");
            CLErrors.check(clSetKernelArg1p(kernel, 2, chanceBuffer), "clSetKernelArg(chances)");
            CLErrors.check(clSetKernelArg1p(kernel, 3, minLevelBuffer), "clSetKernelArg(minLevels)");
            CLErrors.check(clSetKernelArg1p(kernel, 4, maxLevelBuffer), "clSetKernelArg(maxLevels)");
            CLErrors.check(clSetKernelArg1p(kernel, 5, scaleBuffer), "clSetKernelArg(scales)");
            CLErrors.check(clSetKernelArg1p(kernel, 6, scratch.getDeviceResult()), "clSetKernelArg(result)");
            CLErrors.check(clSetKernelArg1i(kernel, 7, materialCount), "clSetKernelArg(materialCount)");
            CLErrors.check(clSetKernelArg1i(kernel, 8, columnCount), "clSetKernelArg(columnCount)");
            CLErrors.check(clSetKernelArg1i(kernel, 9, yBase), "clSetKernelArg(yBase)");
            CLErrors.check(clSetKernelArg1i(kernel, 10, depth), "clSetKernelArg(depth)");
            CLErrors.check(clSetKernelArg1f(kernel, 11, TINY_BLOBS), "clSetKernelArg(tinyBlobs)");
            CLErrors.check(clSetKernelArg1f(kernel, 12, SMALL_BLOBS), "clSetKernelArg(smallBlobs)");

            try (MemoryStack stack = MemoryStack.stackPush()) {
                final int localSize = (int) Math.min(WORK_GROUP_SIZE, context.getDevice().getMaxWorkGroupSize());
                final PointerBuffer globalWorkSize = stack.mallocPointer(1).put(0, GpuContext.roundUp(blockCount, localSize));
                final PointerBuffer localWorkSize = stack.mallocPointer(1).put(0, localSize);
                CLErrors.check(clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, localWorkSize, null, null),
                        "clEnqueueNDRangeKernel(wp_resources_layer)");
            }

            DISPATCHES.incrementAndGet();
            final ByteBuffer hostResult = scratch.getHostResult();
            hostResult.limit(blockCount);
            CLErrors.check(clEnqueueReadBuffer(queue, scratch.getDeviceResult(), true, 0, hostResult, null, null),
                    "clEnqueueReadBuffer(result)");
            final byte[] result = new byte[blockCount];
            hostResult.get(result);
            return result;
        } catch (GpuException e) {
            GpuContext.disable("the Resources kernel failed", e);
            return null;
        } catch (Throwable t) {
            GpuContext.disable("the Resources kernel failed unexpectedly", t);
            return null;
        }
    }

    /**
     * How many chunks this kernel has generated since the process started. Diagnostic: it is the difference between
     * "the GPU is being used" and "the GPU was available but every chunk fell back to the CPU", which is otherwise
     * invisible because both produce the same map.
     */
    public static long getDispatchCount() {
        return DISPATCHES.get();
    }

    private ChunkScratch getScratch() {
        ChunkScratch scratch = this.scratch.get();
        if (scratch == null) {
            scratch = new ChunkScratch(context);
            this.scratch.set(scratch);
        }
        return scratch;
    }

    private static long createBuffer(GpuContext context, ShortBuffer data, IntBuffer errorCode, String what) {
        final long buffer = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, errorCode);
        CLErrors.check(errorCode.get(0), "clCreateBuffer(" + what + ')');
        return buffer;
    }

    private static long createBuffer(GpuContext context, FloatBuffer data, IntBuffer errorCode, String what) {
        final long buffer = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, errorCode);
        CLErrors.check(errorCode.get(0), "clCreateBuffer(" + what + ')');
        return buffer;
    }

    private static long createBuffer(GpuContext context, IntBuffer data, IntBuffer errorCode, String what) {
        final long buffer = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, errorCode);
        CLErrors.check(errorCode.get(0), "clCreateBuffer(" + what + ')');
        return buffer;
    }

    private static long createBuffer(GpuContext context, ByteBuffer data, IntBuffer errorCode, String what) {
        final long buffer = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, errorCode);
        CLErrors.check(errorCode.get(0), "clCreateBuffer(" + what + ')');
        return buffer;
    }

    /**
     * Everything about a Resources layer set-up that affects the tables uploaded to the device, so that two exports
     * configured the same way share one instance.
     */
    private static final class Configuration {
        Configuration(long seed, long[] seedOffsets, int[] minLevels, int[] maxLevels, float[][] chances, boolean[] smallBlobScale) {
            this.seed = seed;
            this.seedOffsets = seedOffsets.clone();
            this.minLevels = minLevels.clone();
            this.maxLevels = maxLevels.clone();
            this.chances = new float[chances.length][];
            for (int i = 0; i < chances.length; i++) {
                this.chances[i] = chances[i].clone();
            }
            this.smallBlobScale = smallBlobScale.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if ((o == null) || (getClass() != o.getClass())) {
                return false;
            }
            final Configuration that = (Configuration) o;
            return (seed == that.seed)
                    && Arrays.equals(seedOffsets, that.seedOffsets)
                    && Arrays.equals(minLevels, that.minLevels)
                    && Arrays.equals(maxLevels, that.maxLevels)
                    && Arrays.deepEquals(chances, that.chances)
                    && Arrays.equals(smallBlobScale, that.smallBlobScale);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(seed);
            result = 31 * result + Arrays.hashCode(seedOffsets);
            result = 31 * result + Arrays.hashCode(minLevels);
            result = 31 * result + Arrays.hashCode(maxLevels);
            result = 31 * result + Arrays.deepHashCode(chances);
            result = 31 * result + Arrays.hashCode(smallBlobScale);
            return result;
        }

        final long seed;
        final long[] seedOffsets;
        final int[] minLevels, maxLevels;
        final float[][] chances;
        final boolean[] smallBlobScale;
    }

    private final GpuContext context;
    private final int materialCount;
    private final long program, permutationBuffer, chanceBuffer, minLevelBuffer, maxLevelBuffer, scaleBuffer;
    private final ThreadLocal<ChunkScratch> scratch = new ThreadLocal<>();

    /** No resource is to be placed in this block. */
    public static final byte NONE = (byte) 0xFF;

    /** The number of ints per column in the array passed to {@link #generate}. */
    public static final int COLUMN_STRIDE = 5;

    /** The number of Resources layer values a chance table has an entry for. */
    public static final int CHANCES_PER_MATERIAL = 16;

    /** The most materials the kernel can handle, limited by the byte wide palette index it returns. */
    public static final int MAX_MATERIALS = 254;

    static final String PROGRAM = "resources.cl";

    private static final AtomicLong DISPATCHES = new AtomicLong();

    private static final int WORK_GROUP_SIZE = 64;
    private static final byte[] EMPTY = new byte[0];
    private static final ConcurrentMap<Configuration, ResourceLayerKernel> INSTANCES = new ConcurrentHashMap<>();
}
