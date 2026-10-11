#ifndef WATER_GLSL
#define WATER_GLSL

#include "/lib/common.glsl"

/*
    Water surface: a sum of sharp-crested directional waves with a little domain
    warping between octaves, so crests bunch up like real wind waves.
    Height is returned in [0, 1]; WAVE_HEIGHT scales its effect.
*/
float waterHeight(vec2 p) {
    float t = frameTimeCounter * WAVE_SPEED;
    float h = 0.0;
    float weight = 0.0;
    float amp = 1.0;
    float freq = 0.55;
    float angle = 0.4;
    for (int i = 0; i < 6; i++) {
        vec2 d = vec2(sin(angle), cos(angle));
        float x = dot(d, p) * freq + t * (1.1 + 0.3 * float(i));
        float w = exp(sin(x) - 1.0);
        h += w * amp;
        weight += amp;
        p -= d * w * cos(x) * amp * 0.35 / freq;
        amp *= 0.7;
        freq *= 1.3;
        angle += 2.39996;
    }
    return h / weight;
}

const float waterWaveAmplitude = 0.12;

vec3 waterNormal(vec3 worldPos, vec3 geoNormal) {
#ifdef WATER_WAVES
    if (geoNormal.y < 0.5) return geoNormal;
    const float e = 0.04;
    vec2 p = worldPos.xz;
    float h0 = waterHeight(p);
    float hx = waterHeight(p + vec2(e, 0.0));
    float hz = waterHeight(p + vec2(0.0, e));
    float k = waterWaveAmplitude * WAVE_HEIGHT / e;
    return normalize(vec3((h0 - hx) * k, 1.0, (h0 - hz) * k));
#else
    return geoNormal;
#endif
}

// Light focusing under a wavy surface, from the curvature of the height field.
float waterCaustics(vec3 worldPos, float depth) {
    vec2 p = worldPos.xz + vec2(0.4, 0.25) * depth;    // light enters at an angle
    const float e = 0.12;
    float h  = waterHeight(p);
    float lap = waterHeight(p + vec2(e, 0.0)) + waterHeight(p - vec2(e, 0.0))
              + waterHeight(p + vec2(0.0, e)) + waterHeight(p - vec2(0.0, e)) - 4.0 * h;
    float focus = lap / (e * e) * waterWaveAmplitude * WAVE_HEIGHT * min(depth, 4.0) * 0.6;
    float c = clamp(1.0 - focus, 0.0, 3.0);
    return mix(1.0, c * c * 0.85, saturate(depth * 0.6));
}

#endif
