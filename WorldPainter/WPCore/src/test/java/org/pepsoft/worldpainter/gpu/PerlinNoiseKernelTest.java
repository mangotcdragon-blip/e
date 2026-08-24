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

import org.junit.Test;
import org.pepsoft.util.PerlinNoise;

import java.util.Random;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Constants.SMALL_BLOBS;
import static org.pepsoft.worldpainter.Constants.TINY_BLOBS;

/**
 * Verifies that the OpenCL port of WorldPainter's Perlin noise agrees with the Java implementation exactly.
 *
 * <p>"Exactly" means bit for bit, not within a tolerance: the noise value is compared against a threshold to decide
 * which block to place, so a difference of one ulp anywhere near a threshold changes the map. An accelerated export
 * has to be indistinguishable from an unaccelerated one.
 */
public class PerlinNoiseKernelTest {
    /**
     * The permutation table derived on the host has to be the one {@code FastPerlin} would have built. If it were not,
     * the noise would be self-consistent but completely different from the CPU's, so check a couple of seeds before
     * anything else: it makes a failure much easier to interpret than a wall of mismatched noise values.
     */
    @Test
    public void permutationTableIsAPermutation() {
        for (long seed: new long[] { 0L, 1L, -1L, 42L, 0x5EEDCAFEL, Long.MIN_VALUE, Long.MAX_VALUE }) {
            final short[] pairs = PerlinPermutation.forSeed(seed);
            assertEquals(256, pairs.length);
            final boolean[] seen = new boolean[256];
            for (int i = 0; i < 256; i++) {
                final int low = pairs[i] & 0xff;
                assertFalse("value " + low + " occurs twice for seed " + seed, seen[low]);
                seen[low] = true;
                // The high byte of entry i must be the low byte of entry i + 1
                assertEquals(pairs[(i + 1) & 0xff] & 0xff, (pairs[i] >> 8) & 0xff);
            }
        }
    }

    @Test
    public void threeDimensionalNoiseMatchesTheCpuBitForBit() {
        GpuTestSupport.requireDevice();

        final Random random = new Random(12345L);
        for (int round = 0; round < 4; round++) {
            final long seed = random.nextLong();
            final int count = 8192;
            final double[] coordinates = new double[count * 3];
            for (int i = 0; i < count; i++) {
                // Sample the way the resource terrains do: integer block coordinates divided by one of the blob sizes.
                // Note that the division happens in float, then widens to double, and that is what has to be
                // reproduced; doing it in double instead would give subtly different values.
                final int x = random.nextInt(20000) - 10000;
                final int y = random.nextInt(20000) - 10000;
                final int z = random.nextInt(384) - 64;
                final float scale = ((i % 2) == 0) ? TINY_BLOBS : SMALL_BLOBS;
                coordinates[i * 3] = x / scale;
                coordinates[i * 3 + 1] = y / scale;
                coordinates[i * 3 + 2] = z / scale;
            }

            final float[] actual = GpuPerlinNoise.sample3(seed, coordinates, count);
            assertNotNull(actual);

            final PerlinNoise expected = new PerlinNoise(seed);
            for (int i = 0; i < count; i++) {
                final float cpu = expected.getPerlinNoise(coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]);
                assertEquals("noise differs at sample " + i + " of round " + round
                                + " (" + coordinates[i * 3] + ", " + coordinates[i * 3 + 1] + ", " + coordinates[i * 3 + 2] + ')',
                        Float.floatToRawIntBits(cpu), Float.floatToRawIntBits(actual[i]));
            }
        }
    }

    @Test
    public void twoDimensionalNoiseMatchesTheCpuBitForBit() {
        GpuTestSupport.requireDevice();

        final long seed = 0xBEEFL;
        final int count = 4096;
        final Random random = new Random(999L);
        final double[] coordinates = new double[count * 2];
        for (int i = 0; i < count * 2; i++) {
            coordinates[i] = (random.nextInt(20000) - 10000) / SMALL_BLOBS;
        }

        final float[] actual = GpuPerlinNoise.sample2(seed, coordinates, count);
        assertNotNull(actual);

        final PerlinNoise expected = new PerlinNoise(seed);
        for (int i = 0; i < count; i++) {
            assertEquals(Float.floatToRawIntBits(expected.getPerlinNoise(coordinates[i * 2], coordinates[i * 2 + 1])),
                    Float.floatToRawIntBits(actual[i]));
        }
    }

    /**
     * The check WorldPainter itself runs before trusting a device with an export.
     */
    @Test
    public void selfTestPasses() {
        GpuTestSupport.requireDevice();
        assertTrue("the device failed WorldPainter's own noise verification", GpuPerlinNoise.selfTest());
        assertTrue(GpuPerlinNoise.isVerified());
    }
}
