/*
    Pass 1 of the deferred stage. Renders small lookup tables into colortex7:
    an equirectangular sky map, a low resolution cloud map (for reflections),
    the cosine-weighted sky ambient light and the direct light colour.
*/
#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/clouds.glsl"
#include "/lib/skymap.glsl"

in vec2 texcoord;

/* RENDERTARGETS: 7 */
layout(location = 0) out vec4 outSky;

vec3 integrateAmbient() {
#if defined WORLD_OVERWORLD
    vec3 sum = vec3(0.0);
    const int N = 16;
    for (int i = 0; i < N; i++) {
        float u = (float(i) + 0.5) / float(N);
        float phi = float(i) * 2.39996323;
        float r = sqrt(u);
        vec3 dir = vec3(r * cos(phi), sqrt(1.0 - u), r * sin(phi));
        sum += computeSky(dir, 8, 3);
    }
    return sum / float(N);
#else
    return analyticSkyAmbient();
#endif
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    outSky = vec4(0.0);

    if (px.y < SKYMAP_H) {
        if (px.x < SKYMAP_W) {
            vec3 dir = skymapToDir((vec2(px) + 0.5) / vec2(SKYMAP_W, SKYMAP_H));
            outSky = vec4(computeSky(dir, 12, 4), 1.0);
        } else if (px.x < SKYMAP_W * 2) {
            outSky = vec4(0.0, 0.0, 0.0, 1.0);
        #if defined VOLUMETRIC_CLOUDS && defined WORLD_OVERWORLD
            vec3 dir = skymapToDir((vec2(px - ivec2(SKYMAP_W, 0)) + 0.5) / vec2(SKYMAP_W, SKYMAP_H));
            if (dir.y > 0.0) {
                float dither = ign(gl_FragCoord.xy);
                outSky = renderClouds(dir, dither, max(CLOUD_STEPS / 2, 6), lightDirWorld(), directLightColor(), analyticSkyAmbient());
            }
        #endif
        }
    } else if (px.y == SKYINFO_Y) {
        if (px.x == 0) outSky = vec4(integrateAmbient(), 1.0);
        else if (px.x == 1) outSky = vec4(directLightColor(), 1.0);
    }
}
