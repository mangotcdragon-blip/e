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
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.PerlinNoise;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.memAllocShort;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.pepsoft.worldpainter.Constants.SMALL_BLOBS;

/**
 * Generates the Stone Mix subsurface terrain of an entire chunk in one dispatch.
 *
 * <p>Stone Mix is WorldPainter's default subsurface material, so on a typical map this runs for every block from
 * bedrock up to a few below the surface: three Perlin noise evaluations each, tens of millions of times.
 *
 * <p>The blocks between y = -4 and y = -1 are not produced here. The Java implementation decides whether they are
 * stone or deepslate by drawing from a shared {@link java.util.Random} that has already been advanced an unknowable
 * number of times by other threads, so there is nothing deterministic to reproduce. Those four layers come back marked
 * {@link #HOST} and the caller generates them the old way, which is what keeps an accelerated export byte for byte
 * identical to an unaccelerated one.
 */
public final class StoneMixKernel {
    private StoneMixKernel(GpuContext context, long seed) {
        this.context = context;
        this.program = context.getProgram(PROGRAM, null);
        ShortBuffer permutations = null;
        try {
            permutations = memAllocShort(3 * PerlinPermutation.SIZE);
            permutations.put(PerlinPermutation.forSeed(seed + GRANITE_SEED_OFFSET));
            permutations.put(PerlinPermutation.forSeed(seed + DIORITE_SEED_OFFSET));
            permutations.put(PerlinPermutation.forSeed(seed + ANDESITE_SEED_OFFSET));
            permutations.flip();
            this.permutationBuffer = createAndUpload(context, permutations);
        } finally {
            memFree(permutations);
        }
    }

    /**
     * Get the kernel for a dimension's seed, or {@code null} if there is no usable GPU.
     *
     * <p>Instances are shared by every thread exporting the same map: the tables they upload depend only on the seed,
     * and the per-chunk scratch space is per thread.
     */
    public static StoneMixKernel get(long seed) {
        if (! GpuPerlinNoise.isVerified()) {
            return null;
        }
        final GpuContext context = GpuContext.get();
        if (context == null) {
            return null;
        }
        try {
            final StoneMixKernel existing = INSTANCES.get(seed);
            if ((existing != null) && (existing.context == context)) {
                return existing;
            }
            // Either there is no kernel for this seed yet, or the one there is belongs to a context that has since
            // been torn down and whose buffers are gone. Build a fresh one.
            final StoneMixKernel kernel = new StoneMixKernel(context, seed);
            INSTANCES.put(seed, kernel);
            return kernel;
        } catch (GpuException e) {
            GpuContext.disable("could not set up the Stone Mix kernel", e);
            return null;
        } catch (Throwable t) {
            GpuContext.disable("unexpected failure while setting up the Stone Mix kernel", t);
            return null;
        }
    }

    /**
     * Generate the subsurface of one chunk.
     *
     * @param columns     Four ints per column, in column order: world X, world Y, the lowest y to generate and the
     *                    highest y to generate (inclusive). A column whose highest y is below its lowest is skipped.
     * @param columnCount The number of columns described by {@code columns}.
     * @param yBase       The y coordinate the returned volume starts at.
     * @param depth       The number of y levels in the returned volume.
     * @param layerOffset The offset added to y before evaluating the pattern, for terrain anchored patterns.
     * @return {@code columnCount * depth} palette indices into {@link #PALETTE}, indexed
     * {@code column * depth + (y - yBase)}, or {@code null} if the work could not be done on the GPU. A value of
     * {@link #NONE} means the block is outside the column's range and {@link #HOST} means the caller has to generate
     * that block itself.
     */
    public byte[] generate(int[] columns, int columnCount, int yBase, int depth, int layerOffset) {
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
            final long kernel = context.getKernel(program, "wp_stone_mix");
            CLErrors.check(clEnqueueWriteBuffer(queue, scratch.getDeviceColumns(), false, 0, hostColumns, null, null),
                    "clEnqueueWriteBuffer(columns)");

            CLErrors.check(clSetKernelArg1p(kernel, 0, permutationBuffer), "clSetKernelArg(perms)");
            CLErrors.check(clSetKernelArg1p(kernel, 1, scratch.getDeviceColumns()), "clSetKernelArg(columns)");
            CLErrors.check(clSetKernelArg1p(kernel, 2, scratch.getDeviceResult()), "clSetKernelArg(result)");
            CLErrors.check(clSetKernelArg1i(kernel, 3, columnCount), "clSetKernelArg(columnCount)");
            CLErrors.check(clSetKernelArg1i(kernel, 4, yBase), "clSetKernelArg(yBase)");
            CLErrors.check(clSetKernelArg1i(kernel, 5, depth), "clSetKernelArg(depth)");
            CLErrors.check(clSetKernelArg1i(kernel, 6, layerOffset), "clSetKernelArg(layerOffset)");
            CLErrors.check(clSetKernelArg1f(kernel, 7, SMALL_BLOBS), "clSetKernelArg(smallBlobs)");
            CLErrors.check(clSetKernelArg1f(kernel, 8, GRANITE_CHANCE), "clSetKernelArg(graniteChance)");
            CLErrors.check(clSetKernelArg1f(kernel, 9, DIORITE_CHANCE), "clSetKernelArg(dioriteChance)");
            CLErrors.check(clSetKernelArg1f(kernel, 10, ANDESITE_CHANCE), "clSetKernelArg(andesiteChance)");

            try (MemoryStack stack = MemoryStack.stackPush()) {
                final int localSize = (int) Math.min(WORK_GROUP_SIZE, context.getDevice().getMaxWorkGroupSize());
                final PointerBuffer globalWorkSize = stack.mallocPointer(1).put(0, GpuContext.roundUp(blockCount, localSize));
                final PointerBuffer localWorkSize = stack.mallocPointer(1).put(0, localSize);
                CLErrors.check(clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, localWorkSize, null, null),
                        "clEnqueueNDRangeKernel(wp_stone_mix)");
            }

            final ByteBuffer hostResult = scratch.getHostResult();
            hostResult.limit(blockCount);
            CLErrors.check(clEnqueueReadBuffer(queue, scratch.getDeviceResult(), true, 0, hostResult, null, null),
                    "clEnqueueReadBuffer(result)");
            final byte[] result = new byte[blockCount];
            hostResult.get(result);
            return result;
        } catch (GpuException e) {
            GpuContext.disable("the Stone Mix kernel failed", e);
            return null;
        } catch (Throwable t) {
            GpuContext.disable("the Stone Mix kernel failed unexpectedly", t);
            return null;
        }
    }

    private ChunkScratch getScratch() {
        ChunkScratch scratch = this.scratch.get();
        if (scratch == null) {
            scratch = new ChunkScratch(context);
            this.scratch.set(scratch);
        }
        return scratch;
    }

    private static long createAndUpload(GpuContext context, ShortBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer errorCode = stack.mallocInt(1);
            final long buffer = clCreateBuffer(context.getHandle(), CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, errorCode);
            CLErrors.check(errorCode.get(0), "clCreateBuffer(permutations)");
            return buffer;
        }
    }

    private final GpuContext context;
    private final long program, permutationBuffer;
    private final ThreadLocal<ChunkScratch> scratch = new ThreadLocal<>();

    /**
     * The materials the palette indices produced by the kernel refer to. Both halves are laid out the same way, with
     * the deepslate equivalents four entries higher than their stone counterparts.
     */
    public static final Material[] PALETTE = {
            Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.DEEPSLATE_Y, Material.TUFF, Material.DEEPSLATE_X, Material.DEEPSLATE_Z
    };

    /** No block is to be placed here; the block is outside the column's range. */
    public static final byte NONE = (byte) 0xFF;

    /** The caller has to generate this block on the CPU. See the class comment. */
    public static final byte HOST = (byte) 0xFE;

    /** The number of ints per column in the array passed to {@link #generate}. */
    public static final int COLUMN_STRIDE = 4;

    static final String PROGRAM = "resources.cl";

    // These have to match the corresponding private constants in Terrain.STONE_MIX. TerrainKernelTest verifies that
    // they do, by comparing the kernel's output against Terrain.STONE_MIX.getMaterial() itself.
    static final int GRANITE_SEED_OFFSET  = 145827825;
    static final int DIORITE_SEED_OFFSET  =  59606124;
    static final int ANDESITE_SEED_OFFSET =  87772192;

    static final float GRANITE_CHANCE  = PerlinNoise.getLevelForPromillage(45);
    static final float DIORITE_CHANCE  = PerlinNoise.getLevelForPromillage(45);
    static final float ANDESITE_CHANCE = PerlinNoise.getLevelForPromillage(45);

    private static final int WORK_GROUP_SIZE = 64;
    private static final byte[] EMPTY = new byte[0];
    private static final ConcurrentMap<Long, StoneMixKernel> INSTANCES = new ConcurrentHashMap<>();
}
