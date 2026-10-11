#ifndef NOISE_GLSL
#define NOISE_GLSL

/*
    tex/noise.png is 256x256 white noise where G(x, y) = R(x + 37, y).
    That lets one bilinear lookup return two neighbouring z slices of a 3D
    value noise. The offset is horizontal so it works whichever way the
    loader orders the rows, and filtering is done by hand with texelFetch.
*/
uniform sampler2D noisetex;

vec4 noiseTexel(ivec2 p) {
    return texelFetch(noisetex, p & 255, 0);
}

float noise2D(vec2 x) {
    vec2 p = floor(x);
    vec2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    ivec2 i = ivec2(p);
    float a = noiseTexel(i).r;
    float b = noiseTexel(i + ivec2(1, 0)).r;
    float c = noiseTexel(i + ivec2(0, 1)).r;
    float d = noiseTexel(i + ivec2(1, 1)).r;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float noise3D(vec3 x) {
    vec3 p = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    ivec2 i = ivec2(p.xy + vec2(37.0, 0.0) * p.z);
    vec2 a = noiseTexel(i).rg;
    vec2 b = noiseTexel(i + ivec2(1, 0)).rg;
    vec2 c = noiseTexel(i + ivec2(0, 1)).rg;
    vec2 d = noiseTexel(i + ivec2(1, 1)).rg;
    vec2 rg = mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    return mix(rg.x, rg.y, f.z);
}

#endif
