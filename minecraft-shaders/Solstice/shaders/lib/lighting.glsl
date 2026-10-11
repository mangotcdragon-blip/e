#ifndef LIGHTING_GLSL
#define LIGHTING_GLSL

#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"
#ifdef WORLD_OVERWORLD
    #include "/lib/shadows.glsl"
    #include "/lib/clouds.glsl"
#endif

// ---------------------------------------------------------------- BRDF
vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow5(1.0 - saturate(cosTheta));
}

float fresnelDielectric(float cosTheta, float f0) {
    return f0 + (1.0 - f0) * pow5(1.0 - saturate(cosTheta));
}

// GGX specular lobe * NdotL.
vec3 specularGGX(vec3 N, vec3 V, vec3 L, float roughness, vec3 F0) {
    vec3 H = normalize(V + L);
    float NdotL = saturate(dot(N, L));
    float NdotV = max(dot(N, V), 1e-3);
    float NdotH = saturate(dot(N, H));
    float VdotH = saturate(dot(V, H));
    float a  = max(roughness * roughness, 0.002);
    float a2 = a * a;
    float d  = (NdotH * a2 - NdotH) * NdotH + 1.0;
    float D  = a2 / (PI * d * d);
    float k  = a * 0.5;
    float G  = (NdotL / (NdotL * (1.0 - k) + k)) * (NdotV / (NdotV * (1.0 - k) + k));
    vec3  F  = fresnelSchlick(VdotH, F0);
    return min(D * G * F / (4.0 * NdotV), vec3(64.0)) ;
}

// ---------------------------------------------------------------- light sources
vec3 blockLightColor() {
    return srgbToLinear(vec3(BLOCKLIGHT_R, BLOCKLIGHT_G, BLOCKLIGHT_B)) * 1.4;
}

float blockLightFalloff(float lm) {
    return (pow(lm, 3.5) * 1.3 + lm * 0.04) * BLOCKLIGHT_STRENGTH;
}

float handLight(vec3 playerPos) {
#ifdef HANDHELD_LIGHT
    float held = float(max(heldBlockLightValue, heldBlockLightValue2));
    return saturate((held - length(playerPos)) / 15.0);
#else
    return 0.0;
#endif
}

float skyLightFalloff(float lm) {
    return lm * lm * (3.0 - 2.0 * lm) * lm;
}

// ---------------------------------------------------------------- surface shading
struct Surface {
    vec3  albedo;      // linear
    vec3  normal;      // world space, may be normal mapped
    vec3  geoNormal;   // world space, flat
    vec2  lightmap;    // block, sky in [0, 1]
    float ao;
    float smoothness;
    float f0;          // > 0.9 means metal
    float emission;
    int   material;
};

vec3 shadeSurface(Surface s, vec3 playerPos, float dither, vec3 lightCol, vec3 ambientCol) {
    vec3 V = -normalize(playerPos);
    vec3 N = s.normal;
    bool metal = s.f0 > 0.9;
    vec3 F0 = metal ? s.albedo : vec3(s.f0);
    float roughness = sqr(1.0 - s.smoothness);
    bool foliage = s.material == MAT_FOLIAGE;
    bool flatLit = s.material == MAT_PARTICLE;

    vec3 color = vec3(0.0);

#if defined WORLD_OVERWORLD
    vec3 L = lightDirWorld();
    float NdotL = dot(N, L);
    float geoNdotL = dot(s.geoNormal, L);

    if (maxOf(lightCol) > 1e-5 && (geoNdotL > 0.0 || foliage || flatLit)) {
        float blockerDist = 0.0;
        vec3 biasDir = (foliage || flatLit) ? L : s.geoNormal;
        vec3 shadow = getShadow(playerPos, biasDir, (foliage || flatLit) ? 1.0 : geoNdotL, dither, blockerDist);

        float fade = shadowFade(playerPos);
        shadow = mix(shadow, vec3(smoothstep(0.82, 0.97, s.lightmap.y)), fade);
        shadow *= saturate(s.lightmap.y * 8.0 + (s.material == MAT_HAND ? 1.0 : 0.0));
        shadow *= cloudShadow(playerPos + cameraPosition, L);

        float diffuse;
        if (foliage)      diffuse = 0.35 + 0.65 * saturate(NdotL);
        else if (flatLit) diffuse = 0.75;
        else              diffuse = saturate(NdotL) * saturate(geoNdotL * 8.0);

        vec3 kd = metal ? vec3(0.0) : (1.0 - fresnelSchlick(max(dot(N, V), 0.0), F0) * 0.5);
        vec3 direct = s.albedo * kd * diffuse;
        if (!foliage && !flatLit && NdotL > 0.0) direct += specularGGX(N, V, L, roughness, F0);
        color += direct * shadow * lightCol;

    #ifdef SUBSURFACE_SCATTERING
        if (foliage) {
            float mu = dot(-V, L);
            float transmit = exp(-blockerDist * 1.8) * (1.0 - fade) + fade * smoothstep(0.8, 0.97, s.lightmap.y);
            transmit *= saturate(s.lightmap.y * 8.0) * cloudShadow(playerPos + cameraPosition, L);
            float phase = 0.3 + hgPhase(mu, 0.6) * 5.0;
            color += s.albedo * lightCol * transmit * phase * 0.45 * SSS_STRENGTH;
        }
    #endif
    }
#endif

    // Sky light, slightly directional (surfaces facing down see less sky)
    float skyAmount = skyLightFalloff(s.lightmap.y);
    vec3 ambient = ambientCol * skyAmount * (0.65 + 0.35 * N.y) * AMBIENT_STRENGTH;

    // Block light (+ handheld)
    float blockLm = max(s.lightmap.x, handLight(playerPos));
    vec3 blockLight = blockLightColor() * blockLightFalloff(blockLm);

    vec3 minLight = vec3(MIN_LIGHT) * (1.0 - skyAmount) + vec3(0.25) * nightVision;
#if !defined WORLD_OVERWORLD
    minLight += ambientCol * AMBIENT_STRENGTH;
#endif

    vec3 indirect = (ambient + blockLight + minLight) * s.ao;
    color += indirect * s.albedo * (metal ? 0.25 : 1.0);

    color += s.albedo * s.emission * EMISSIVE_STRENGTH;
    return color;
}

#endif
