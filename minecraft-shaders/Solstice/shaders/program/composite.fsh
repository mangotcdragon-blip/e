/*
    Translucents and atmosphere:
      - water: refraction, absorption/scattering, caustics, reflections, sun glint
      - glass and other translucents: blending + reflections
      - screen space reflections on smooth/metallic/wet opaque surfaces
      - height fog, border fog, underwater/lava/snow fog
      - volumetric light (god rays), above and below water
*/
#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/lighting.glsl"
#include "/lib/skymap.glsl"
#include "/lib/water.glsl"
#include "/lib/ssr.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform sampler2D colortex4;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;

in vec2 texcoord;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

const vec3 defaultWaterTint = vec3(0.247, 0.463, 0.894); // vanilla #3F76E4

vec3 waterExtinction(vec3 tintSrgb) {
    vec3 absorb = vec3(0.20, 0.055, 0.035);
    vec3 tintAbsorb = (1.0 - srgbToLinear(tintSrgb)) * 0.2 + 0.015;
    absorb = mix(absorb, tintAbsorb, WATER_BIOME_TINT);
    return (absorb + 0.015) * WATER_FOG_DENSITY;
}

vec3 applyWaterFog(vec3 color, float dist, vec3 extinction, vec3 light) {
    vec3 T = exp(-extinction * dist);
    vec3 scatterColor = light * (0.015 * WATER_FOG_DENSITY) / extinction;
    return color * T + scatterColor * (1.0 - T);
}

vec3 getReflection(vec3 viewPos, vec3 viewNormal, float skyLight, float dither, bool allowSSR) {
    vec3 rv = reflect(normalize(viewPos), viewNormal);
    vec3 rw = mat3(gbufferModelViewInverse) * rv;
    vec3 reflection = sampleSkyWithClouds(rw) * smoothstep(0.2, 0.9, skyLight);
#if defined WORLD_OVERWORLD
    reflection += sunDisc(rw, sunDirWorld()) * 0.15 * smoothstep(0.5, 0.95, skyLight);
#endif
#ifdef SSR
    if (allowSSR) {
        vec3 hit = traceScreenSpace(depthtex1, viewPos, rv, dither);
        if (hit.z > 0.0) reflection = mix(reflection, texture(colortex0, hit.xy).rgb, hit.z);
    }
#endif
    return reflection;
}

#if defined VOLUMETRIC_LIGHT && defined WORLD_OVERWORLD
vec3 volumetricLight(vec3 endPos, float dither, vec3 lightCol, vec3 L, bool underwater, vec3 waterExt) {
    float len = min(length(endPos), underwater ? 48.0 : min(far, shadowDistance));
    vec3 dir = normalize(endPos);
    float stepLen = len / float(VL_STEPS);
    float mu = dot(dir, L);

    float density = underwater ? 0.05 : 0.0025 * FOG_DENSITY * (1.0 + 3.0 * rainStrength);
    vec3 accum = vec3(0.0);
    for (int i = 0; i < VL_STEPS; i++) {
        float t = (float(i) + dither) * stepLen;
        vec3 p = dir * t;
        vec3 lit = getShadowFast(p);
        float h = p.y + cameraPosition.y;
        float d = underwater ? 1.0 : exp(-max(h - 63.0, 0.0) / 48.0);
        vec3 att = underwater ? exp(-waterExt * t) : vec3(1.0);
        accum += lit * d * att;
    }
    accum *= density * stepLen;

    float phase = underwater ? (0.6 * hgPhase(mu, 0.7) + 0.4 * 0.0796)
                             : (0.5 * hgPhase(mu, 0.65) + 0.5 * 0.0796);
    float eyeSky = eyeBrightnessSmooth.y / 240.0;
    vec3 vl = accum * lightCol * phase * 4.0 * VL_STRENGTH;
    if (underwater) vl *= vec3(0.25, 0.75, 1.0) * 0.6;
    else vl *= mix(0.15, 1.0, eyeSky) * mix(1.0, 0.35, smoothstep(0.2, 0.7, L.y));
    return vl * cloudShadow(cameraPosition, L);
}
#endif

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float depth0 = texelFetch(depthtex0, px, 0).r;
    float depth1 = texelFetch(depthtex1, px, 0).r;
    vec3 color = texelFetch(colortex0, px, 0).rgb;

    vec3 viewPos0 = screenToView(vec3(texcoord, depth0));
    vec3 viewPos1 = screenToView(vec3(texcoord, depth1));
    vec3 playerPos0 = viewToPlayer(viewPos0);
    vec3 playerPos1 = viewToPlayer(viewPos1);
    vec3 dir = normalize(playerPos0);

    vec4 data4 = texelFetch(colortex4, px, 0);
    vec2 matSky = unpack2x8(data4.z);
    int transMat = int(matSky.x * 255.0 + 0.5);
    bool hasTranslucent = depth0 < depth1 && transMat != 0;
    bool isWater = hasTranslucent && transMat == MAT_WATER;
    bool isHand = depth0 < 0.56;

    float dither = ignTemporal(gl_FragCoord.xy);
    vec3 lightCol = skyLightColor();
    vec3 ambient  = skyAmbientColor();
#if defined WORLD_OVERWORLD
    vec3 L = lightDirWorld();
#else
    vec3 L = vec3(0.0, 1.0, 0.0);
#endif
    float eyeSky = eyeBrightnessSmooth.y / 240.0;
    vec3 camWaterExt = waterExtinction(defaultWaterTint);
    vec3 camWaterLight = (ambient * 0.9 + lightCol * 0.12) * mix(0.08, 1.0, eyeSky) + vec3(MIN_LIGHT);

    // ------------------------------------------------------------ reflections on opaque surfaces
    if (!hasTranslucent && depth1 < 1.0 && !isHand) {
        vec4 c2 = texelFetch(colortex2, px, 0);
        float smoothness = c2.y;
        if (smoothness > 0.45) {
            vec4 c1 = texelFetch(colortex1, px, 0);
            vec3 normal = octDecode(unpack2x8(c1.x));
            vec3 viewNormal = mat3(gbufferModelView) * normal;
            float NdotV = saturate(dot(normal, -dir));
            bool metal = c2.z > 0.9;
            vec3 F0 = metal ? color / max(maxOf(color), 1e-4) * 0.85 : vec3(c2.z);
            vec3 F = fresnelSchlick(NdotV, F0);
            float strength = smoothstep(0.45, 0.85, smoothness);
            vec3 reflection = getReflection(viewPos1, viewNormal, c1.w, dither, smoothness > 0.6);
            color = metal ? mix(color, reflection * F, strength) : color + reflection * F * strength;
        }
    }

    // ------------------------------------------------------------ translucents
    if (isWater) {
        vec3 normal = octDecode(data4.xy);
        vec3 viewNormal = mat3(gbufferModelView) * normal;
        vec3 tint = unpackRGB565(data4.w);
        vec3 ext = waterExtinction(tint);
        float skyLight = matSky.y;
        vec3 V = -dir;

        vec2 refrUv = texcoord;
    #ifdef WATER_REFRACTION
        {
            float thickness = length(playerPos1 - playerPos0);
            vec3 flatNormal = abs(normal.y) > 0.5 ? vec3(0.0, sign(normal.y), 0.0) : normal;
            vec3 dn = mat3(gbufferModelView) * (normal - flatNormal);
            vec2 offset = dn.xy * 0.12 * REFRACTION_STRENGTH * saturate(thickness * 0.35) / max(1.0, -viewPos0.z * 0.08);
            vec2 candidate = clamp(texcoord + offset, vec2(0.001), vec2(0.999));
            if (texture(depthtex1, candidate).r > depth0) refrUv = candidate;
        }
    #endif
        vec3 behind = texture(colortex0, refrUv).rgb;
        float depthBehind = texture(depthtex1, refrUv).r;
        vec3 behindPlayer = viewToPlayer(screenToView(vec3(refrUv, depthBehind)));
        float dist = depthBehind >= 1.0 ? 32.0 : length(behindPlayer - playerPos0);

        vec3 waterLight = (ambient * skyLight * 0.9 + lightCol * 0.12 * skyLight) + vec3(MIN_LIGHT);

        if (isEyeInWater != 1) {
        #if defined WATER_CAUSTICS && defined WORLD_OVERWORLD
            if (depthBehind < 1.0) {
                float sunlit = luminance(getShadowFast(behindPlayer)) * smoothstep(0.5, 0.95, skyLight);
                float caustic = waterCaustics(behindPlayer + cameraPosition, max(playerPos0.y - behindPlayer.y, 0.0));
                behind *= mix(1.0, caustic, sunlit * 0.85);
            }
        #endif
            behind = applyWaterFog(behind, dist, ext, waterLight);
        }

        float NdotV = dot(normal, V);
        vec3 reflection;
        float F;
        if (isEyeInWater == 1) {
            // Looking up at the surface from below: total internal reflection outside Snell's window.
            float cosT = abs(NdotV);
            float sinT2 = 1.7689 * (1.0 - cosT * cosT);
            F = sinT2 >= 1.0 ? 1.0 : fresnelDielectric(sqrt(1.0 - sinT2), 0.02);
            reflection = camWaterLight * 0.015 * WATER_FOG_DENSITY / camWaterExt;
        } else {
            F = fresnelDielectric(abs(NdotV), 0.02);
            reflection = getReflection(viewPos0, viewNormal, skyLight, dither, !isHand);
        }
        color = mix(behind, reflection, F);

    #if defined WORLD_OVERWORLD
        if (isEyeInWater != 1) {
            vec3 shadow = getShadowFast(playerPos0) * cloudShadow(playerPos0 + cameraPosition, L);
            color += specularGGX(normal, V, L, 0.06, vec3(0.02)) * lightCol * shadow * smoothstep(0.5, 0.95, skyLight);
        }
    #endif
    } else if (hasTranslucent) {
        vec4 trans = texelFetch(colortex3, px, 0);
        color = color * (1.0 - trans.a) + trans.rgb;
        if (!isHand) {
            vec3 normal = octDecode(data4.xy);
            vec3 viewNormal = mat3(gbufferModelView) * normal;
            float F = fresnelDielectric(abs(dot(normal, -dir)), 0.04);
            vec3 reflection = getReflection(viewPos0, viewNormal, matSky.y, dither, true);
            color = color * (1.0 - F) + reflection * F;
        }
    }

    // Rain, snow and anything else alpha blended into colortex3 over non-glass pixels
    if (!hasTranslucent || isWater) {
        vec4 trans = texelFetch(colortex3, px, 0);
        color = color * (1.0 - trans.a) + trans.rgb;
    }

    // ------------------------------------------------------------ fog
    float dist = depth0 >= 1.0 ? far : length(playerPos0);
#if defined WATER_CAUSTICS && defined WORLD_OVERWORLD
    if (isEyeInWater == 1 && !hasTranslucent && depth0 < 1.0 && !isHand) {
        float skyLight = texelFetch(colortex1, px, 0).w;
        float sunlit = luminance(getShadowFast(playerPos0)) * smoothstep(0.5, 0.95, skyLight);
        color *= mix(1.0, waterCaustics(playerPos0 + cameraPosition, 2.5), sunlit * 0.85);
    }
#endif
    if (isEyeInWater == 1) {
        color = applyWaterFog(color, dist, camWaterExt, camWaterLight);
    } else if (isEyeInWater == 2) {
        color = mix(color, vec3(1.0, 0.25, 0.03) * 2.0, 1.0 - exp(-dist * 1.2));
    } else if (isEyeInWater == 3) {
        color = mix(color, vec3(0.55, 0.65, 0.8) * (ambient + 0.05), 1.0 - exp(-dist * 0.9));
    } else if (depth0 < 1.0 && !isHand) {
    #if defined WORLD_OVERWORLD
        vec3 fogCol = sampleSky(dir);
        float camH = cameraPosition.y - 63.0;
        float falloff = 1.0 / 48.0;
        float density = 0.0018 * FOG_DENSITY * (1.0 + 7.0 * rainStrength);
        float dy = dir.y * dist * falloff;
        float heightTerm = abs(dy) > 1e-4 ? (1.0 - exp(-dy)) / dy : 1.0;
        float fogAmount = density * dist * exp(-max(camH, 0.0) * falloff) * heightTerm;
        float fog = 1.0 - exp(-fogAmount);
        fogCol *= mix(0.05, 1.0, eyeSky);
        color = mix(color, fogCol, fog);
        #ifdef BORDER_FOG
        float border = pow(saturate((dist / far - 0.72) / 0.28), 2.0);
        color = mix(color, sampleSkyWithClouds(dir), border);
        #endif
    #elif defined WORLD_NETHER
        vec3 fogCol = computeSky(dir, 1, 1);
        float fog = 1.0 - exp(-dist * 0.012 * FOG_DENSITY);
        fog = max(fog, pow(saturate((dist / far - 0.6) / 0.4), 2.0));
        color = mix(color, fogCol, fog);
    #else
        vec3 fogCol = computeSky(dir, 1, 1);
        float fog = 1.0 - exp(-dist * 0.006 * FOG_DENSITY);
        fog = max(fog, pow(saturate((dist / far - 0.6) / 0.4), 2.0));
        color = mix(color, fogCol, fog);
    #endif
    }
    if (blindness > 0.0 || darknessFactor > 0.0) {
        color *= exp(-dist * (blindness * 0.6 + darknessFactor * 0.12));
    }

    // ------------------------------------------------------------ volumetric light
#if defined VOLUMETRIC_LIGHT && defined WORLD_OVERWORLD
    if (!isHand && isEyeInWater <= 1 && maxOf(lightCol) > 1e-4) {
        vec3 endPos = depth0 >= 1.0 ? dir * far : playerPos0;
        color += volumetricLight(endPos, dither, lightCol, L, isEyeInWater == 1, camWaterExt);
    }
#endif

    outColor = vec4(max(color, 0.0), 1.0);
}
