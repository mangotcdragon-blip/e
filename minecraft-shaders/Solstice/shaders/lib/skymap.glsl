#ifndef SKYMAP_GLSL
#define SKYMAP_GLSL

#include "/lib/common.glsl"

/*
    colortex7 layout (written by deferred.fsh every frame):
      x [0, 256),   y [0, 128) : sky radiance, equirectangular
      x [256, 512), y [0, 128) : clouds, rgb = in-scattered light, a = transmittance
      y = 128                  : x = 0 ambient sky light, x = 1 direct light colour
*/
#define SKYMAP_W 256
#define SKYMAP_H 128
#define SKYINFO_Y 128

uniform sampler2D colortex7;

vec2 dirToSkymap(vec3 dir) {
    float u = atan(dir.z, dir.x) / TAU + 0.5;
    float v = 0.5 + 0.5 * sign(dir.y) * sqrt(abs(dir.y));
    return vec2(u, v);
}

vec3 skymapToDir(vec2 uv) {
    float phi = (uv.x - 0.5) * TAU;
    float s = uv.y * 2.0 - 1.0;
    float y = sign(s) * s * s;
    float r = sqrt(max(1.0 - y * y, 0.0));
    return vec3(r * cos(phi), y, r * sin(phi));
}

vec4 sampleSkymapRegion(vec3 dir, int xOffset) {
    vec2 uv = dirToSkymap(dir) * vec2(SKYMAP_W, SKYMAP_H) - 0.5;
    vec2 f = fract(uv);
    ivec2 i = ivec2(floor(uv));
    int x0 = (i.x % SKYMAP_W + SKYMAP_W) % SKYMAP_W;
    int x1 = (x0 + 1) % SKYMAP_W;
    int y0 = clamp(i.y, 0, SKYMAP_H - 1);
    int y1 = clamp(i.y + 1, 0, SKYMAP_H - 1);
    vec4 a = texelFetch(colortex7, ivec2(x0 + xOffset, y0), 0);
    vec4 b = texelFetch(colortex7, ivec2(x1 + xOffset, y0), 0);
    vec4 c = texelFetch(colortex7, ivec2(x0 + xOffset, y1), 0);
    vec4 d = texelFetch(colortex7, ivec2(x1 + xOffset, y1), 0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 sampleSky(vec3 dir) {
    return sampleSkymapRegion(dir, 0).rgb;
}

// Sky as seen in reflections: atmosphere with the low resolution clouds composited on top.
vec3 sampleSkyWithClouds(vec3 dir) {
    vec3 sky = sampleSkymapRegion(dir, 0).rgb;
#if defined VOLUMETRIC_CLOUDS && defined WORLD_OVERWORLD
    vec4 clouds = sampleSkymapRegion(dir, SKYMAP_W);
    sky = sky * clouds.a + clouds.rgb;
#endif
    return sky;
}

vec3 skyAmbientColor() { return texelFetch(colortex7, ivec2(0, SKYINFO_Y), 0).rgb; }
vec3 skyLightColor()   { return texelFetch(colortex7, ivec2(1, SKYINFO_Y), 0).rgb; }

#endif
