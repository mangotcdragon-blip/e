#ifndef WAVING_GLSL
#define WAVING_GLSL

#include "/lib/common.glsl"

vec3 windOffset(vec3 worldPos, float strength) {
    float t = frameTimeCounter * (1.0 + rainStrength * 0.8);
    vec2 p = worldPos.xz;
    float gust = sin(t * 0.35 + p.x * 0.018 + p.y * 0.011) * 0.5 + 0.5;
    gust = 0.4 + 0.6 * gust * gust;
    float wx = sin(t * 1.9 + p.x * 0.55 + p.y * 0.3 + worldPos.y * 0.4) + 0.4 * sin(t * 3.7 + p.x * 1.3 - p.y * 0.8);
    float wz = sin(t * 1.6 + p.y * 0.6 - p.x * 0.25 + worldPos.y * 0.3) + 0.4 * sin(t * 4.1 - p.y * 1.1 + p.x * 0.9);
    float wy = sin(t * 2.3 + p.x * 0.7 + p.y * 0.7) * 0.25;
    return vec3(wx, wy, wz) * strength * gust * (1.0 + rainStrength * 1.2) * WAVING_STRENGTH;
}

// worldPos: absolute world position of the vertex. Returns the displacement to add.
vec3 wavingOffset(vec3 worldPos, int id, vec2 texcoord, vec2 midTexcoord, float skyLight) {
    vec3 offset = vec3(0.0);
    float exposure = smoothstep(0.3, 0.9, skyLight);
    if (exposure <= 0.0) return offset;
    bool topVertex = texcoord.y < midTexcoord.y;

#ifdef WAVING_PLANTS
    if (id == ID_PLANT || id == ID_TALL_LOWER) {
        if (topVertex) offset = windOffset(worldPos, 0.07);
    } else if (id == ID_TALL_UPPER) {
        offset = windOffset(worldPos, topVertex ? 0.12 : 0.07);
    } else if (id == ID_VINES) {
        offset = windOffset(worldPos, 0.03);
    }
#endif
#ifdef WAVING_LEAVES
    if (id == ID_LEAVES) offset = windOffset(worldPos, 0.035);
#endif
    offset.y *= 0.5;
    return offset * exposure;
}

#endif
