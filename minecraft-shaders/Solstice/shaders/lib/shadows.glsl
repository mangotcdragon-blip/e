#ifndef SHADOWS_GLSL
#define SHADOWS_GLSL

#include "/lib/common.glsl"

uniform sampler2D shadowtex0;
uniform sampler2D shadowtex1;
uniform sampler2D shadowcolor0;

// ---------------------------------------------------------------- distortion (shared with shadow.vsh)
float shadowDistortFactor(vec2 p) {
    return length(p) * SHADOW_DISTORTION + (1.0 - SHADOW_DISTORTION);
}
vec3 distortShadowClip(vec3 p) {
    return vec3(p.xy / shadowDistortFactor(p.xy), p.z * 0.2);
}

vec3 playerToShadowClip(vec3 playerPos) {
    return (shadowProjection * (shadowModelView * vec4(playerPos, 1.0))).xyz;
}

vec2 vogelDisk(int i, int n, float phi) {
    float r = sqrt((float(i) + 0.5) / float(n));
    float theta = float(i) * 2.39996323 + phi;
    return r * vec2(cos(theta), sin(theta));
}

// 0 inside the shadow map, 1 at its edge (used to fade to a lightmap estimate).
float shadowFade(vec3 playerPos) {
    float d = max(length(playerPos.xz), abs(playerPos.y) * 0.5);
    return smoothstep(shadowDistance * 0.8, shadowDistance * 0.98, d);
}

/*
    Percentage closer soft shadows with coloured translucent shadows.
    Returns the light colour multiplier; blockerDist receives the distance in blocks
    from the receiver to the average occluder (used for subsurface scattering).
*/
vec3 getShadow(vec3 playerPos, vec3 geoNormal, float NdotL, float dither, out float blockerDist) {
    blockerDist = 0.0;

    vec3 sc = playerToShadowClip(playerPos);
    float df = shadowDistortFactor(sc.xy);

    float texelWorld = 2.0 / (shadowProjection[0][0] * float(shadowMapResolution));
    float normalBias = texelWorld * df * (1.2 + 2.5 * (1.0 - saturate(NdotL)));
    sc = playerToShadowClip(playerPos + geoNormal * normalBias);
    vec3 suv = distortShadowClip(sc) * 0.5 + 0.5;

    if (any(lessThan(suv, vec3(0.0))) || any(greaterThan(suv, vec3(1.0)))) return vec3(1.0);

    float receiver = suv.z - 0.00002;
    float uvPerBlock  = shadowProjection[0][0] * 0.5 / df;
    float depthToWorld = 1.0 / (0.1 * abs(shadowProjection[2][2]));
    float texel = 1.0 / float(shadowMapResolution);
    float angle = dither * TAU;

    // Blocker search
    float searchRadius = max(uvPerBlock * 2.0 * SHADOW_SOFTNESS, texel * 2.0);
    float blockerSum = 0.0;
    float blockerCount = 0.0;
    for (int i = 0; i < 8; i++) {
        vec2 offset = vogelDisk(i, 8, angle) * searchRadius;
        float z = texture(shadowtex0, suv.xy + offset).r;
        if (z < receiver) { blockerSum += z; blockerCount += 1.0; }
    }
    if (blockerCount < 0.5) return vec3(1.0);

    float avgBlocker = blockerSum / blockerCount;
    blockerDist = max(receiver - avgBlocker, 0.0) * depthToWorld;

    float penumbra = blockerDist * 0.022 * SHADOW_SOFTNESS * uvPerBlock;
    float radius = clamp(penumbra, texel * 1.0, searchRadius);

    float litAll = 0.0;
    float litOpaque = 0.0;
    for (int i = 0; i < SHADOW_SAMPLES; i++) {
        vec2 uv = suv.xy + vogelDisk(i, SHADOW_SAMPLES, angle) * radius;
        litAll += step(receiver, texture(shadowtex0, uv).r);
    #ifdef COLORED_SHADOWS
        litOpaque += step(receiver, texture(shadowtex1, uv).r);
    #endif
    }
    litAll /= float(SHADOW_SAMPLES);

#ifdef COLORED_SHADOWS
    litOpaque /= float(SHADOW_SAMPLES);
    if (litOpaque - litAll > 0.001) {
        vec4 tint = texture(shadowcolor0, suv.xy);
        vec3 tintColor = mix(vec3(1.0), saturate(srgbToLinear(tint.rgb) * 1.5), tint.a);
        return vec3(litAll) + (litOpaque - litAll) * tintColor;
    }
#endif
    return vec3(litAll);
}

// Single tap, used by volumetric light and caustics.
vec3 getShadowFast(vec3 playerPos) {
    vec3 suv = distortShadowClip(playerToShadowClip(playerPos)) * 0.5 + 0.5;
    if (any(lessThan(suv, vec3(0.0))) || any(greaterThan(suv, vec3(1.0)))) return vec3(1.0);
    float receiver = suv.z - 0.0001;
    float lit = step(receiver, texture(shadowtex0, suv.xy).r);
#ifdef COLORED_SHADOWS
    float litOpaque = step(receiver, texture(shadowtex1, suv.xy).r);
    if (litOpaque > lit) {
        vec4 tint = texture(shadowcolor0, suv.xy);
        return mix(vec3(1.0), saturate(srgbToLinear(tint.rgb) * 1.5), tint.a);
    }
#endif
    return vec3(lit);
}

#endif
