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

import org.pepsoft.util.PerlinNoise;

/**
 * The blending itself, on plain arrays.
 *
 * <p>This is the reference implementation and the fallback when there is no GPU. {@code blend.cl} is a
 * transliteration of it, and {@code BlendKernelTest} checks that the two agree exactly, so anything changed here has
 * to be changed there as well.
 *
 * <p>Both operations read from a source array covering the area being blended plus a margin of {@code margin} blocks
 * on every side, and write to a destination array covering just that area. Working in blocks with a margin, rather
 * than in place, is what lets a column near the edge of a block see its neighbours in the next one and keeps the
 * result independent of how the work happened to be divided up.
 */
public final class BlendAlgorithm {
    private BlendAlgorithm() {
        // Prevent instantiation
    }

    /**
     * Work out, for every column of one block, which column its terrain should be taken from.
     *
     * <p>The result is an index into {@code source} rather than a terrain, so that the caller can pick up whatever
     * else it wants from the column that was chosen: its water level, for instance. A column that is not to be
     * changed gets its own index.
     *
     * @param source      Terrain indices for the area plus {@code margin} blocks on every side, row major.
     * @param destination Receives {@code width * height} indices into {@code source}, row major.
     * @param width       The width of the area to blend.
     * @param height      The height of the area to blend.
     * @param margin      The number of extra blocks {@code source} extends beyond the area on every side. Must be at
     *                    least the effective radius of the blend.
     * @param originX     The world X coordinate of the first column of the area.
     * @param originY     The world Y coordinate of the first column of the area.
     * @param settings    The blend to perform.
     * @param seed        The dimension seed. {@link BlendSettings#getSeedOffset()} is added to it.
     */
    public static void blendTerrain(int[] source, int[] destination, int width, int height, int margin,
                                    int originX, int originY, BlendSettings settings, long seed) {
        final BlendMode mode = settings.getTerrainMode();
        final int radius = getEffectiveRadius(settings);
        if ((mode == BlendMode.NONE) || (radius <= 0)) {
            fillWithOwnIndices(destination, width, height, margin);
            return;
        }
        final int sourceWidth = width + (2 * margin);
        final float scale = settings.getTerrainScale();
        final float displacement = radius;
        final PerlinNoise noiseX = new PerlinNoise(seed + settings.getSeedOffset() + WARP_X_SEED_OFFSET);
        final PerlinNoise noiseY = new PerlinNoise(seed + settings.getSeedOffset() + WARP_Y_SEED_OFFSET);
        final long hashSeed = seed + settings.getSeedOffset();
        final boolean coherent = mode.isCoherent(), stochastic = mode.isStochastic();
        final float jitter = (mode == BlendMode.COMBINED) ? COMBINED_JITTER : 1f;
        final boolean boundariesOnly = settings.isBoundariesOnly();

        for (int y = 0; y < height; y++) {
            final int worldY = originY + y;
            for (int x = 0; x < width; x++) {
                final int worldX = originX + x;
                final int ownIndex = ((y + margin) * sourceWidth) + x + margin;
                final int own = source[ownIndex];
                if (boundariesOnly && isUniform(source, sourceWidth, x + margin, y + margin, radius, own)) {
                    destination[(y * width) + x] = ownIndex;
                    continue;
                }
                float offsetX = 0f, offsetY = 0f;
                if (coherent) {
                    // getPerlinNoise returns roughly -0.5 to 0.5, so twice the radius spans the full range
                    offsetX += noiseX.getPerlinNoise(worldX / scale, worldY / scale) * 2f * displacement;
                    offsetY += noiseY.getPerlinNoise(worldX / scale, worldY / scale) * 2f * displacement;
                }
                if (stochastic) {
                    final int hash = hash(worldX, worldY, hashSeed);
                    // Cubed so that small displacements are far more likely than large ones, which is what makes the
                    // scatter thin out with distance instead of forming a hard edged band of noise
                    final float unitX = (((hash >>> 8) & 0xffff) / 32768f) - 1f;
                    final float unitY = (((hash >>> 16) & 0xffff) / 32768f) - 1f;
                    offsetX += unitX * unitX * unitX * displacement * jitter;
                    offsetY += unitY * unitY * unitY * displacement * jitter;
                }
                final int sampleX = clamp(x + margin + round(offsetX), 0, sourceWidth - 1);
                final int sampleY = clamp(y + margin + round(offsetY), 0, height + (2 * margin) - 1);
                destination[(y * width) + x] = (sampleY * sourceWidth) + sampleX;
            }
        }
    }

    /**
     * Smooth the height map of one block of columns.
     *
     * @param source      Heights for the area plus {@code margin} blocks on every side, row major.
     * @param destination Receives {@code width * height} heights, row major.
     * @param weights     {@code (2 * margin + 1)^2} kernel weights, row major, already normalised so that they sum to
     *                    one. Build them with {@link #createGaussianKernel(int)}.
     */
    public static void blendHeight(float[] source, float[] destination, int width, int height, int margin,
                                   float[] weights, BlendSettings settings) {
        final float strength = settings.getHeightStrength();
        if ((margin <= 0) || (strength <= 0f)) {
            copyInterior(source, destination, width, height, margin);
            return;
        }
        final int sourceWidth = width + (2 * margin), kernelWidth = (2 * margin) + 1;
        final float slopeThreshold = settings.getHeightSlopeThreshold();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int centre = ((y + margin) * sourceWidth) + x + margin;
                final float own = source[centre];
                if ((slopeThreshold > 0f) && (getSlope(source, sourceWidth, centre) < slopeThreshold)) {
                    destination[(y * width) + x] = own;
                    continue;
                }
                float sum = 0f;
                for (int ky = 0; ky < kernelWidth; ky++) {
                    final int row = ((y + ky) * sourceWidth) + x;
                    for (int kx = 0; kx < kernelWidth; kx++) {
                        sum += weights[(ky * kernelWidth) + kx] * source[row + kx];
                    }
                }
                destination[(y * width) + x] = own + ((sum - own) * strength);
            }
        }
    }

    /**
     * Build a normalised two dimensional Gaussian kernel of the given radius, as {@link #blendHeight} expects.
     *
     * <p>The standard deviation is a third of the radius, which puts the edge of the kernel three deviations out,
     * where the weights have fallen to about a hundredth of the centre's and truncating them costs nothing visible.
     */
    public static float[] createGaussianKernel(int radius) {
        if (radius <= 0) {
            return new float[] { 1f };
        }
        final int width = (2 * radius) + 1;
        final float[] weights = new float[width * width];
        final double deviation = radius / 3.0;
        final double denominator = 2 * deviation * deviation;
        double total = 0;
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                final int dx = x - radius, dy = y - radius;
                final double weight = Math.exp(-((dx * dx) + (dy * dy)) / denominator);
                weights[(y * width) + x] = (float) weight;
                total += weight;
            }
        }
        // Normalise in float, and in the same order the kernels sum in, so that the host and the device agree
        final float scale = (float) (1 / total);
        for (int i = 0; i < weights.length; i++) {
            weights[i] = weights[i] * scale;
        }
        return weights;
    }

    /**
     * The furthest a column's terrain can move, given the settings. This is the margin a block of work needs.
     */
    public static int getEffectiveRadius(BlendSettings settings) {
        if (settings.getTerrainMode() == BlendMode.NONE) {
            return 0;
        }
        return (int) Math.ceil(settings.getTerrainRadius() * settings.getTerrainStrength());
    }

    /**
     * A stable hash of a column and a seed, used to displace each column independently. Any change to this changes
     * every speckled blend, so it is fixed: the finalising mix from MurmurHash3, which passes avalanche tests and is
     * exactly reproducible in OpenCL because it is nothing but 32 bit shifts, xors and multiplies.
     */
    public static int hash(int x, int y, long seed) {
        int h = (x * X_PRIME) ^ (y * Y_PRIME) ^ ((int) seed) ^ ((int) (seed >>> 32));
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /**
     * Whether every column within {@code radius} of the given one has terrain {@code own}.
     */
    private static boolean isUniform(int[] source, int sourceWidth, int centreX, int centreY, int radius, int own) {
        final int squaredRadius = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            final int row = (centreY + dy) * sourceWidth;
            for (int dx = -radius; dx <= radius; dx++) {
                if ((((dx * dx) + (dy * dy)) <= squaredRadius) && (source[row + centreX + dx] != own)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The steepest of the four axial height differences around a column.
     */
    private static float getSlope(float[] source, int sourceWidth, int centre) {
        final float own = source[centre];
        return Math.max(Math.max(Math.abs(source[centre - 1] - own), Math.abs(source[centre + 1] - own)),
                Math.max(Math.abs(source[centre - sourceWidth] - own), Math.abs(source[centre + sourceWidth] - own)));
    }

    /**
     * Fill {@code destination} with the index each column has in the source array, which is what "change nothing"
     * looks like.
     */
    public static void fillWithOwnIndices(int[] destination, int width, int height, int margin) {
        final int sourceWidth = width + (2 * margin);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                destination[(y * width) + x] = ((y + margin) * sourceWidth) + x + margin;
            }
        }
    }

    private static void copyInterior(float[] source, float[] destination, int width, int height, int margin) {
        final int sourceWidth = width + (2 * margin);
        for (int y = 0; y < height; y++) {
            System.arraycopy(source, ((y + margin) * sourceWidth) + margin, destination, y * width, width);
        }
    }

    /**
     * Round to the nearest integer, halves upwards. Spelled out rather than using {@link Math#round(float)} so that
     * the kernel, where {@code round()} rounds halves away from zero instead, can do exactly the same thing.
     */
    private static int round(float value) {
        return (int) Math.floor(value + 0.5f);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** How much of the full displacement the per-column jitter contributes in {@link BlendMode#COMBINED}. */
    static final float COMBINED_JITTER = 0.35f;

    static final long WARP_X_SEED_OFFSET = 0x1F3A5C7L;
    static final long WARP_Y_SEED_OFFSET = 0x7C5A3F1L;

    private static final int X_PRIME = 0x27d4eb2d;
    private static final int Y_PRIME = 0x165667b1;
}
