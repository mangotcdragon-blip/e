#ifndef BLOOM_GLSL
#define BLOOM_GLSL

#include "/lib/common.glsl"

/*
    Bloom is stored as a row of tiles in colortex6: tile i is the scene at
    1/2^(i+2) resolution, blurred with a separable gaussian. The final pass
    upsamples and sums them.
*/
#define BLOOM_TILES 6

// xy = tile origin (uv), z = tile size (uv, relative to each axis)
vec3 bloomTile(int i) {
    float pad = 6.0 / viewWidth;
    float x = 0.0;
    for (int j = 0; j < i; j++) x += exp2(-float(j + 2)) + pad;
    return vec3(x, 6.0 / viewHeight, exp2(-float(i + 2)));
}

const float bloomWeights[9] = float[9](0.0276, 0.0663, 0.1238, 0.1802, 0.2042, 0.1802, 0.1238, 0.0663, 0.0276);

#endif
