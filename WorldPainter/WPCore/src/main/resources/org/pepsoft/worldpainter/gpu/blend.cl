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
 *
 * ---------------------------------------------------------------------------------------------------------------
 *
 * Terrain and height blending. A transliteration of org.pepsoft.worldpainter.blending.BlendAlgorithm; the two have to
 * agree exactly, and BlendKernelTest checks that they do.
 *
 * Both kernels read a source array covering the area being blended plus a margin on every side, and write a
 * destination array covering just the area, so a column at the edge of a block still sees its neighbours.
 */

#include "perlin.cl"

/* The MurmurHash3 finalising mix, in unsigned arithmetic so the shifts match Java's >>>. */
inline uint wp_blend_hash(int x, int y, int seedLow, int seedHigh) {
    uint h = ((uint) (x * 0x27d4eb2d)) ^ ((uint) (y * 0x165667b1)) ^ ((uint) seedLow) ^ ((uint) seedHigh);
    h ^= h >> 16;
    h *= 0x85ebca6b;
    h ^= h >> 13;
    h *= 0xc2b2ae35;
    h ^= h >> 16;
    return h;
}

/* Round to nearest, halves upwards. OpenCL's round() rounds halves away from zero, which differs for negatives. */
inline int wp_round(float value) {
    return (int) floor(value + 0.5f);
}

inline int wp_clamp_int(int value, int minimum, int maximum) {
    return max(minimum, min(maximum, value));
}

/*
 * Soften the boundaries between terrain types by taking each column's terrain from a slightly displaced neighbour.
 *
 * Writes the index of the column to take the terrain from, not the terrain itself, so that the host can pick up
 * whatever else it wants from that column. A column that is not to be changed gets its own index.
 *
 * source        terrain indices for the area plus margin blocks on every side, row major
 * destination   width * height indices into source, row major
 * permX, permY  the permutation tables of the two noise fields that drive a coherent displacement
 * radius        the furthest a terrain may bleed across its boundary, in blocks
 * scale         the size of the features in the coherent noise, in blocks
 * jitter        how much of the displacement the per-column randomness contributes
 */
__kernel void wp_blend_terrain(__global const int *source,
                               __global int *destination,
                               __global const ushort *permX,
                               __global const ushort *permY,
                               const int width,
                               const int height,
                               const int margin,
                               const int originX,
                               const int originY,
                               const int radius,
                               const float scale,
                               const float jitter,
                               const int coherent,
                               const int stochastic,
                               const int boundariesOnly,
                               const int seedLow,
                               const int seedHigh) {
    const int gid = get_global_id(0);
    if (gid >= (width * height)) {
        return;
    }
    const int y = gid / width;
    const int x = gid - (y * width);
    const int sourceWidth = width + (2 * margin);
    const int sourceHeight = height + (2 * margin);
    const int centreX = x + margin, centreY = y + margin;
    const int own = source[(centreY * sourceWidth) + centreX];

    if (boundariesOnly) {
        const int squaredRadius = radius * radius;
        bool uniform = true;
        for (int dy = -radius; (dy <= radius) && uniform; dy++) {
            const int row = (centreY + dy) * sourceWidth;
            for (int dx = -radius; dx <= radius; dx++) {
                if ((((dx * dx) + (dy * dy)) <= squaredRadius) && (source[row + centreX + dx] != own)) {
                    uniform = false;
                    break;
                }
            }
        }
        if (uniform) {
            destination[gid] = (centreY * sourceWidth) + centreX;
            return;
        }
    }

    const int worldX = originX + x, worldY = originY + y;
    const float displacement = (float) radius;
    float offsetX = 0.0f, offsetY = 0.0f;

    if (coherent) {
        /* As in Java, the coordinate is an int divided by a float, so the division is in single precision */
        const double nx = (double) ((float) worldX / scale);
        const double ny = (double) ((float) worldY / scale);
        offsetX += wp_perlin2(permX, nx, ny) * 2.0f * displacement;
        offsetY += wp_perlin2(permY, nx, ny) * 2.0f * displacement;
    }
    if (stochastic) {
        const uint hash = wp_blend_hash(worldX, worldY, seedLow, seedHigh);
        const float unitX = ((float) ((hash >> 8) & 0xffff) / 32768.0f) - 1.0f;
        const float unitY = ((float) ((hash >> 16) & 0xffff) / 32768.0f) - 1.0f;
        offsetX += unitX * unitX * unitX * displacement * jitter;
        offsetY += unitY * unitY * unitY * displacement * jitter;
    }

    const int sampleX = wp_clamp_int(centreX + wp_round(offsetX), 0, sourceWidth - 1);
    const int sampleY = wp_clamp_int(centreY + wp_round(offsetY), 0, sourceHeight - 1);
    destination[gid] = (sampleY * sourceWidth) + sampleX;
}

/*
 * Smooth the height map with a normalised kernel, optionally only where the ground is steep enough to need it.
 *
 * weights  (2 * margin + 1)^2 kernel weights, row major, already summing to one
 */
__kernel void wp_blend_height(__global const float *source,
                              __global float *destination,
                              __global const float *weights,
                              const int width,
                              const int height,
                              const int margin,
                              const float strength,
                              const float slopeThreshold) {
    const int gid = get_global_id(0);
    if (gid >= (width * height)) {
        return;
    }
    const int y = gid / width;
    const int x = gid - (y * width);
    const int sourceWidth = width + (2 * margin);
    const int kernelWidth = (2 * margin) + 1;
    const int centre = ((y + margin) * sourceWidth) + x + margin;
    const float own = source[centre];

    if (slopeThreshold > 0.0f) {
        const float slope = fmax(fmax(fabs(source[centre - 1] - own), fabs(source[centre + 1] - own)),
                fmax(fabs(source[centre - sourceWidth] - own), fabs(source[centre + sourceWidth] - own)));
        if (slope < slopeThreshold) {
            destination[gid] = own;
            return;
        }
    }

    /* Summed in the same order as the Java version, so that the rounding of the running total matches */
    float sum = 0.0f;
    for (int ky = 0; ky < kernelWidth; ky++) {
        const int row = ((y + ky) * sourceWidth) + x;
        for (int kx = 0; kx < kernelWidth; kx++) {
            sum += weights[(ky * kernelWidth) + kx] * source[row + kx];
        }
    }
    destination[gid] = own + ((sum - own) * strength);
}
