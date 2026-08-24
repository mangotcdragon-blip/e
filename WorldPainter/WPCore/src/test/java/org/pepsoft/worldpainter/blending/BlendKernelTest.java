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
package org.pepsoft.worldpainter.blending;

import org.junit.After;
import org.junit.Test;
import org.pepsoft.worldpainter.gpu.BlendKernel;
import org.pepsoft.worldpainter.gpu.GpuSettings;

import java.util.Random;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Verifies that the blending kernels and the Java implementation of the same algorithm agree exactly.
 *
 * <p>They have to: whether a map was blended on a GPU or without one should not be something you can tell by looking
 * at it, or by comparing two exports of the same map.
 */
public class BlendKernelTest {
    @After
    public void resetGpuSettings() {
        GpuSettings.reset();
    }

    @Test
    public void terrainBlendingIsIdenticalOnTheGpuAndTheCpu() {
        requireDevice();

        final int width = 128, height = 128, margin = 8;
        final long seed = 0xABCDEF01L;
        for (BlendMode mode: new BlendMode[] { BlendMode.ORGANIC, BlendMode.SPECKLED, BlendMode.COMBINED }) {
            for (boolean boundariesOnly: new boolean[] { true, false }) {
                final BlendSettings settings = BlendSettings.builder()
                        .terrainMode(mode)
                        .terrainRadius(margin)
                        .terrainScale(24f)
                        .terrainStrength(1f)
                        .boundariesOnly(boundariesOnly)
                        .build();
                final int[] source = createPatchyTerrain(width + (2 * margin), height + (2 * margin), 9876L);
                final int[] fromGpu = new int[width * height];
                final int[] fromCpu = new int[width * height];

                assertTrue("the kernel declined work it should have taken",
                        BlendKernel.blendTerrain(source, fromGpu, width, height, margin, ORIGIN_X, ORIGIN_Y,
                                BlendAlgorithm.getEffectiveRadius(settings), settings.getTerrainScale(),
                                (mode == BlendMode.COMBINED) ? BlendAlgorithm.COMBINED_JITTER : 1f,
                                mode.isCoherent(), mode.isStochastic(), boundariesOnly,
                                seed + settings.getSeedOffset() + BlendAlgorithm.WARP_X_SEED_OFFSET,
                                seed + settings.getSeedOffset() + BlendAlgorithm.WARP_Y_SEED_OFFSET,
                                seed + settings.getSeedOffset()));
                BlendAlgorithm.blendTerrain(source, fromCpu, width, height, margin, ORIGIN_X, ORIGIN_Y, settings, seed);

                assertArrayEquals(mode + (boundariesOnly ? " (boundaries only)" : "") + " differs between GPU and CPU",
                        fromCpu, fromGpu);
                assertTrue("the blend should have moved at least some columns", countMoved(fromCpu, width, height, margin) > 0);
            }
        }
    }

    @Test
    public void heightBlendingIsIdenticalOnTheGpuAndTheCpu() {
        requireDevice();

        final int width = 128, height = 128, margin = 3;
        for (float slopeThreshold: new float[] { 0f, 1.5f }) {
            final BlendSettings settings = BlendSettings.builder()
                    .heightRadius(margin)
                    .heightStrength(0.75f)
                    .heightSlopeThreshold(slopeThreshold)
                    .build();
            final float[] source = createTerracedHeights(width + (2 * margin), height + (2 * margin));
            final float[] weights = BlendAlgorithm.createGaussianKernel(margin);
            final float[] fromGpu = new float[width * height];
            final float[] fromCpu = new float[width * height];

            assertTrue("the kernel declined work it should have taken",
                    BlendKernel.blendHeight(source, fromGpu, width, height, margin, weights,
                            settings.getHeightStrength(), settings.getHeightSlopeThreshold()));
            BlendAlgorithm.blendHeight(source, fromCpu, width, height, margin, weights, settings);

            for (int i = 0; i < fromCpu.length; i++) {
                assertEquals("height " + i + " differs between GPU and CPU with slope threshold " + slopeThreshold,
                        Float.floatToRawIntBits(fromCpu[i]), Float.floatToRawIntBits(fromGpu[i]));
            }
        }
    }

    /**
     * The Gaussian kernel has to be normalised, or blending would raise or lower the whole map.
     */
    @Test
    public void gaussianKernelSumsToOne() {
        for (int radius = 1; radius <= 8; radius++) {
            final float[] weights = BlendAlgorithm.createGaussianKernel(radius);
            assertEquals(((2 * radius) + 1) * ((2 * radius) + 1), weights.length);
            float total = 0f;
            for (float weight: weights) {
                assertTrue("weights may not be negative", weight >= 0f);
                total += weight;
            }
            assertEquals(1f, total, 1e-5f);
            // The centre has to be the heaviest weight, or this is not a blur
            final int centre = (radius * ((2 * radius) + 1)) + radius;
            for (float weight: weights) {
                assertTrue(weight <= weights[centre]);
            }
        }
    }

    /**
     * Smoothing flat ground has to leave it exactly as it was, whatever the strength.
     */
    @Test
    public void smoothingFlatGroundChangesNothing() {
        final int width = 32, height = 32, margin = 4;
        final float[] source = new float[(width + (2 * margin)) * (height + (2 * margin))];
        java.util.Arrays.fill(source, 62.5f);
        final float[] destination = new float[width * height];
        final BlendSettings settings = BlendSettings.builder()
                .heightRadius(margin)
                .heightStrength(1f)
                .heightSlopeThreshold(0f)
                .build();
        BlendAlgorithm.blendHeight(source, destination, width, height, margin,
                BlendAlgorithm.createGaussianKernel(margin), settings);
        for (float value: destination) {
            assertEquals(62.5f, value, 1e-3f);
        }
    }

    /**
     * The hash that drives a speckled blend has to depend on all of its inputs, or the scatter would be striped.
     */
    @Test
    public void columnHashVariesWithEveryInput() {
        assertNotEquals(BlendAlgorithm.hash(0, 0, 0L), BlendAlgorithm.hash(1, 0, 0L));
        assertNotEquals(BlendAlgorithm.hash(0, 0, 0L), BlendAlgorithm.hash(0, 1, 0L));
        assertNotEquals(BlendAlgorithm.hash(0, 0, 0L), BlendAlgorithm.hash(0, 0, 1L));
        assertNotEquals(BlendAlgorithm.hash(0, 0, 0L), BlendAlgorithm.hash(0, 0, 1L << 40));
        assertEquals(BlendAlgorithm.hash(-7, 13, 99L), BlendAlgorithm.hash(-7, 13, 99L));
        // Rough check that the values are spread out rather than clustered
        final boolean[] buckets = new boolean[256];
        int distinct = 0;
        for (int x = 0; x < 256; x++) {
            final int bucket = BlendAlgorithm.hash(x, x * 3, 5L) & 0xff;
            if (! buckets[bucket]) {
                buckets[bucket] = true;
                distinct++;
            }
        }
        assertTrue("the hash should fill most buckets, but only filled " + distinct, distinct > 128);
    }

    private static void requireDevice() {
        GpuSettings.setMode(GpuSettings.Mode.AUTO);
        GpuSettings.setDevicePreference(GpuSettings.DevicePreference.ANY_DEVICE);
        assumeTrue("No OpenCL device available; skipping", BlendKernel.isAvailable());
    }

    /**
     * A terrain map made of irregular patches, so that there is plenty of boundary to blend.
     */
    private static int[] createPatchyTerrain(int width, int height, long seed) {
        final Random random = new Random(seed);
        final int[] terrain = new int[width * height];
        final int patchCount = 24;
        final int[] centreX = new int[patchCount], centreY = new int[patchCount], type = new int[patchCount];
        for (int i = 0; i < patchCount; i++) {
            centreX[i] = random.nextInt(width);
            centreY[i] = random.nextInt(height);
            type[i] = random.nextInt(6);
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nearest = 0, nearestDistance = Integer.MAX_VALUE;
                for (int i = 0; i < patchCount; i++) {
                    final int dx = x - centreX[i], dy = y - centreY[i];
                    final int distance = (dx * dx) + (dy * dy);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = i;
                    }
                }
                terrain[(y * width) + x] = type[nearest];
            }
        }
        return terrain;
    }

    /**
     * A height map with stair steps in it, which is what a height brush leaves behind and what smoothing is for.
     */
    private static float[] createTerracedHeights(int width, int height) {
        final float[] heights = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                heights[(y * width) + x] = 62f + ((x / 16) * 4f) + ((y / 24) * 3f);
            }
        }
        return heights;
    }

    private static int countMoved(int[] sampleIndices, int width, int height, int margin) {
        final int sourceWidth = width + (2 * margin);
        int moved = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (sampleIndices[(y * width) + x] != (((y + margin) * sourceWidth) + x + margin)) {
                    moved++;
                }
            }
        }
        return moved;
    }

    private static final int ORIGIN_X = -1024, ORIGIN_Y = 512;
}
