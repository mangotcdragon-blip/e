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
 * An OpenCL port of org.pepsoft.util.FastPerlin (by MCRcortex), the Perlin noise generator behind every terrain,
 * resource and layer pattern WorldPainter produces.
 *
 * This is deliberately a transliteration rather than a re-implementation. An accelerated export has to place exactly
 * the same blocks as an unaccelerated one, or the same map would come out differently depending on which machine
 * exported it, so every operation here mirrors the Java original:
 *
 *   - the lattice coordinates are floored in double precision, because the Java code samples at double precision;
 *   - every multiply-add the Java code performs with Math.fma is performed here with fma(), and FP_CONTRACT is
 *     switched off so the compiler cannot fuse (or refuse to fuse) any of the others;
 *   - -cl-fast-relaxed-math and -cl-mad-enable must never be passed when building this program.
 *
 * PerlinNoiseKernelTest checks all of that by comparing the results bit for bit against org.pepsoft.util.PerlinNoise
 * over a large sample of seeds and coordinates.
 *
 * The permutation table is computed on the host by org.pepsoft.worldpainter.gpu.PerlinPermutation and passed in as a
 * buffer of 256 ushorts, each packing perm[i] in its low byte and perm[i + 1] in its high byte.
 */

#pragma OPENCL FP_CONTRACT OFF

#ifdef cl_khr_fp64
#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#endif

/* The gradient vectors, as 16 triples. Identical to FastPerlin.LUT2. */
__constant float WP_GRADIENTS[48] = {
     1.0f,  1.0f,  0.0f,
    -1.0f,  1.0f,  0.0f,
     1.0f, -1.0f,  0.0f,
    -1.0f, -1.0f,  0.0f,
     1.0f,  0.0f,  1.0f,
    -1.0f,  0.0f,  1.0f,
     1.0f,  0.0f, -1.0f,
    -1.0f,  0.0f, -1.0f,
     0.0f,  1.0f,  1.0f,
     0.0f, -1.0f,  1.0f,
     0.0f,  1.0f, -1.0f,
     0.0f, -1.0f, -1.0f,
     1.0f,  1.0f,  0.0f,
     0.0f, -1.0f,  1.0f,
    -1.0f,  1.0f,  0.0f,
     0.0f, -1.0f, -1.0f
};

/* PerlinNoise.FACTOR_2D and FACTOR_3D: the normalisation applied to the raw lattice value. */
#define WP_FACTOR_2D 0.5
#define WP_FACTOR_3D 0.4824607142760952

inline int wp_pair(__global const ushort *perm, int index) {
    return (int) perm[index & 0xFF];
}

inline float wp_fade(float v) {
    return v * v * v * fma(v, fma(v, 6.0f, -15.0f), 10.0f);
}

inline float wp_lerp(float progress, float a, float b) {
    return fma(b - a, progress, a);
}

inline float wp_grad1(int v, float x) {
    v = (v & 15) * 3;
    return x * WP_GRADIENTS[v];
}

inline float wp_grad2(int v, float x, float y) {
    v = (v & 15) * 3;
    return fma(x, WP_GRADIENTS[v], y * WP_GRADIENTS[v + 1]);
}

inline float wp_grad3(int v, float x, float y, float z) {
    v = (v & 15) * 3;
    return fma(x, WP_GRADIENTS[v], fma(y, WP_GRADIENTS[v + 1], z * WP_GRADIENTS[v + 2]));
}

/* FastPerlin.sampleResult(double) */
inline float wp_sample1(__global const ushort *perm, double X) {
    const double floorX = floor(X);
    const float lx = (float) (X - floorX);
    const int x = wp_pair(perm, (int) floorX);
    return wp_lerp(wp_fade(lx),
            wp_grad1(wp_pair(perm, wp_pair(perm, x)), lx),
            wp_grad1(wp_pair(perm, wp_pair(perm, x >> 8)), lx - 1.0f));
}

/* FastPerlin.sampleResult(double, double) */
inline float wp_sample2(__global const ushort *perm, double X, double Y) {
    const double floorX = floor(X);
    const double floorY = floor(Y);

    const int by = (int) floorY;

    const float lx = (float) (X - floorX);
    const float ly = (float) (Y - floorY);

    const int x = wp_pair(perm, (int) floorX);
    const int x0y = wp_pair(perm, x + by);
    const int x1y = wp_pair(perm, (x >> 8) + by);

    const float py = wp_fade(ly);

    return wp_lerp(wp_fade(lx),
            wp_lerp(py,
                    wp_grad2(wp_pair(perm, x0y), lx, ly),
                    wp_grad2(wp_pair(perm, x0y >> 8), lx, ly - 1.0f)),
            wp_lerp(py,
                    wp_grad2(wp_pair(perm, x1y), lx - 1.0f, ly),
                    wp_grad2(wp_pair(perm, x1y >> 8), lx - 1.0f, ly - 1.0f)));
}

/* FastPerlin.sampleResult(double, double, double) */
inline float wp_sample3(__global const ushort *perm, double X, double Y, double Z) {
    const double floorX = floor(X);
    const double floorY = floor(Y);
    const double floorZ = floor(Z);

    const int by = (int) floorY;
    const int bz = (int) floorZ;

    const float lx = (float) (X - floorX);
    const float ly = (float) (Y - floorY);
    const float lz = (float) (Z - floorZ);

    const int x = wp_pair(perm, (int) floorX);
    const int x0y = wp_pair(perm, x + by);
    const int x1y = wp_pair(perm, (x >> 8) + by);
    const int x0y0z = wp_pair(perm, x0y + bz);
    const int x0y1z = wp_pair(perm, (x0y >> 8) + bz);
    const int x1y0z = wp_pair(perm, x1y + bz);
    const int x1y1z = wp_pair(perm, (x1y >> 8) + bz);

    const float py = wp_fade(ly);
    const float pz = wp_fade(lz);

    return wp_lerp(wp_fade(lx),
            wp_lerp(py,
                    wp_lerp(pz,
                            wp_grad3(x0y0z, lx, ly, lz),
                            wp_grad3(x0y0z >> 8, lx, ly, lz - 1.0f)),
                    wp_lerp(pz,
                            wp_grad3(x0y1z, lx, ly - 1.0f, lz),
                            wp_grad3(x0y1z >> 8, lx, ly - 1.0f, lz - 1.0f))),
            wp_lerp(py,
                    wp_lerp(pz,
                            wp_grad3(x1y0z, lx - 1.0f, ly, lz),
                            wp_grad3(x1y0z >> 8, lx - 1.0f, ly, lz - 1.0f)),
                    wp_lerp(pz,
                            wp_grad3(x1y1z, lx - 1.0f, ly - 1.0f, lz),
                            wp_grad3(x1y1z >> 8, lx - 1.0f, ly - 1.0f, lz - 1.0f))));
}

/* PerlinNoise.getPerlinNoise(double), which returns the raw lattice value unscaled. */
inline float wp_perlin1(__global const ushort *perm, double X) {
    return wp_sample1(perm, X);
}

/* PerlinNoise.getPerlinNoise(double, double) */
inline float wp_perlin2(__global const ushort *perm, double X, double Y) {
    return (float) ((double) wp_sample2(perm, X, Y) * WP_FACTOR_2D);
}

/* PerlinNoise.getPerlinNoise(double, double, double) */
inline float wp_perlin3(__global const ushort *perm, double X, double Y, double Z) {
    return (float) ((double) wp_sample3(perm, X, Y, Z) * WP_FACTOR_3D);
}

/*
 * Evaluate the 1D, 2D and 3D noise for a list of coordinates. Used by PerlinNoiseKernelTest to verify that this port
 * agrees with the Java implementation; the export kernels call the wp_perlin* functions directly.
 */
__kernel void wp_perlin_sample(__global const ushort *perm,
                               __global const double *coordinates,
                               __global float *result,
                               const int dimensions,
                               const int count) {
    const int gid = get_global_id(0);
    if (gid >= count) {
        return;
    }
    if (dimensions == 1) {
        result[gid] = wp_perlin1(perm, coordinates[gid]);
    } else if (dimensions == 2) {
        result[gid] = wp_perlin2(perm, coordinates[gid * 2], coordinates[gid * 2 + 1]);
    } else {
        result[gid] = wp_perlin3(perm, coordinates[gid * 3], coordinates[gid * 3 + 1], coordinates[gid * 3 + 2]);
    }
}
