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
 * Reproduces the permutation table that {@code org.pepsoft.util.FastPerlin} builds for a given seed, so that it can be
 * uploaded to an OpenCL device.
 *
 * <p>The table is the only per-seed state a Perlin generator has: everything else is fixed arithmetic. Deriving it here
 * rather than reaching into {@code FastPerlin} keeps the accelerator free of reflection, at the cost of having to keep
 * the two shuffles in step. {@code PerlinNoiseKernelTest} guards that by comparing the GPU's output against
 * {@link org.pepsoft.util.PerlinNoise} itself for a large sample of coordinates and seeds, so any divergence in the
 * table shows up immediately as a test failure.
 *
 * <p>Each entry packs a <em>pair</em> of permutation values, {@code perm[i] | (perm[i + 1] << 8)}, exactly as
 * {@code FastPerlin} does; the gradient lookups index into the low or the high half depending on which corner of the
 * lattice cell is being sampled.
 */
public final class PerlinPermutation {
    private PerlinPermutation() {
        // Prevent instantiation
    }

    /**
     * Compute the packed permutation pair table for {@code seed}.
     *
     * @return An array of 256 values, each holding two permutation entries in its low and high bytes.
     */
    public static short[] forSeed(long seed) {
        final LinearRandom random = new LinearRandom(seed);
        final byte[] permutation = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            permutation[i] = (byte) i;
        }
        for (int i = 0; i < SIZE; i++) {
            final int j = random.nextInt(SIZE - i);
            final byte b = permutation[SIZE - 1 - i];
            permutation[SIZE - 1 - i] = permutation[j];
            permutation[j] = b;
        }
        final short[] permutationPairs = new short[SIZE];
        for (int i = 0; i < SIZE; i++) {
            permutationPairs[i] = (short) ((permutation[i] & 0xff) | ((permutation[(i + 1) & 0xff] & 0xff) << 8));
        }
        return permutationPairs;
    }

    /**
     * The linear congruential generator {@code FastPerlin} shuffles with. It is {@link java.util.Random}'s generator,
     * without the synchronisation and without the constructor's seed scrambling.
     */
    private static final class LinearRandom {
        LinearRandom(long seed) {
            this.seed = seed ^ MULTIPLIER;
        }

        int nextInt(int bound) {
            int r = next(31);
            final int m = bound - 1;
            if ((bound & m) == 0) {
                // Bound is a power of two
                r = (int) ((bound * (long) r) >> 31);
            } else {
                // Reject over-represented candidates
                for (int u = r; u - (r = u % bound) + m < 0; u = next(31)) {
                    // Do nothing
                }
            }
            return r;
        }

        private int next(int bits) {
            seed = seed * MULTIPLIER + ADDEND;
            return (int) ((seed & MASK) >>> (48 - bits));
        }

        private long seed;

        private static final long MULTIPLIER = 0x5DEECE66DL;
        private static final long ADDEND     = 0xBL;
        private static final long MASK       = (1L << 48) - 1;
    }

    /** The number of entries in a Perlin permutation table. */
    public static final int SIZE = 256;
}
