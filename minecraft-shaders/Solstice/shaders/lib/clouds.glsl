#ifndef CLOUDS_GLSL
#define CLOUDS_GLSL

#include "/lib/common.glsl"
#include "/lib/noise.glsl"
#include "/lib/atmosphere.glsl"

/*
    Ray marched cumulus layer between CLOUD_ALTITUDE and CLOUD_ALTITUDE + CLOUD_THICKNESS
    (world Y). Lit by the sun or moon with a short light march, an approximation of
    multiple scattering (Wrenninge octaves) and a height based ambient term.
*/

const float cloudSigma = 0.045;

vec3 cloudWind() {
    return vec3(4.0, 0.0, 1.6) * frameTimeCounter * CLOUD_SPEED;
}

float cloudDensity(vec3 worldPos, bool detail) {
    float h = (worldPos.y - CLOUD_ALTITUDE) / CLOUD_THICKNESS;
    if (h <= 0.0 || h >= 1.0) return 0.0;

    vec3 q = worldPos + cloudWind();
    float heightShape = saturate(h * 6.0) * saturate((1.0 - h) * 2.2);

    float weather  = noise2D(q.xz * 0.0009);
    float coverage = saturate(CLOUD_COVERAGE + rainStrength * 0.3 + (weather - 0.5) * 0.45);
    float threshold = 1.0 - coverage;

    float n1 = noise3D(q * vec3(0.0055, 0.008, 0.0055));
    // Upper bound of the full fbm, used to skip empty space cheaply.
    if ((n1 * 0.5 + 0.375) / 0.875 * heightShape < threshold) return 0.0;

    float base = n1 * 0.5 + noise3D(q * 0.013 + 3.1) * 0.25 + noise3D(q * 0.028 + 7.7) * 0.125;
    float d = (base / 0.875) * heightShape - threshold;
    if (d <= 0.0) return 0.0;
    d /= max(coverage, 0.05);

    if (detail) {
        float erosion = noise3D(q * 0.075 + 13.3);
        d -= erosion * 0.18 * (1.0 - saturate(d * 3.0)) * (1.0 - 0.5 * h);
    }
    return max(d, 0.0) * CLOUD_DENSITY;
}

float cloudLightOpticalDepth(vec3 p, vec3 lightDir) {
    float od = 0.0;
    float stepLen = CLOUD_THICKNESS * 0.18 / float(CLOUD_LIGHT_STEPS);
    for (int j = 0; j < CLOUD_LIGHT_STEPS; j++) {
        float s = stepLen * (1.0 + float(j));
        p += lightDir * s;
        od += cloudDensity(p, false) * s;
    }
    return od * cloudSigma;
}

// Returns rgb = in-scattered light, a = transmittance.
vec4 renderClouds(vec3 dir, float dither, int steps, vec3 lightDir, vec3 lightCol, vec3 ambient) {
    float camY   = cameraPosition.y;
    float bottom = CLOUD_ALTITUDE;
    float top    = CLOUD_ALTITUDE + CLOUD_THICKNESS;

    float t0, t1;
    if (camY < bottom) {
        if (dir.y <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
        t0 = (bottom - camY) / dir.y;
        t1 = (top - camY) / dir.y;
    } else if (camY > top) {
        if (dir.y >= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
        t0 = (top - camY) / dir.y;
        t1 = (bottom - camY) / dir.y;
    } else {
        t0 = 0.0;
        t1 = dir.y > 0.0 ? (top - camY) / dir.y : (dir.y < 0.0 ? (bottom - camY) / dir.y : 4000.0);
    }
    if (t0 > 20000.0) return vec4(0.0, 0.0, 0.0, 1.0);
    t1 = min(t1, t0 + CLOUD_THICKNESS * 5.0);

    float stepLen = (t1 - t0) / float(steps);
    float mu = dot(dir, lightDir);
    vec3 origin = cameraPosition;

    float transmittance = 1.0;
    vec3 scattering = vec3(0.0);

    for (int i = 0; i < steps; i++) {
        float t = t0 + (float(i) + dither) * stepLen;
        vec3 p = origin + dir * t;
        float d = cloudDensity(p, true);
        if (d < 0.0005) continue;

        float h = (p.y - bottom) / CLOUD_THICKNESS;
        float od = cloudLightOpticalDepth(p, lightDir);

        // Multiple scattering approximation: three octaves with weaker extinction and phase.
        float ms = 0.0;
        float a = 1.0, b = 1.0, c = 1.0;
        for (int k = 0; k < 3; k++) {
            float phase = mix(hgPhase(mu, 0.7 * c), hgPhase(mu, -0.25 * c), 0.3);
            ms += a * exp(-od * b) * phase;
            a *= 0.55; b *= 0.4; c *= 0.5;
        }
        float powder = 1.0 - 0.6 * exp(-d * 12.0);

        vec3 inscatter = lightCol * ms * powder * 3.5
                       + ambient * (0.35 + 0.65 * h) * 0.9;

        float stepT = exp(-d * cloudSigma * stepLen);
        scattering += transmittance * inscatter * (1.0 - stepT);
        transmittance *= stepT;
        if (transmittance < 0.02) break;
    }

    // Aerial perspective: distant clouds dissolve into the horizon.
    float fade = exp(-max(t0, 0.0) * 0.00018);
    scattering *= fade;
    transmittance = mix(1.0, transmittance, fade);
    return vec4(scattering, transmittance);
}

// Shadow of the cloud layer on a world position (1 = lit).
float cloudShadow(vec3 worldPos, vec3 lightDir) {
#if defined CLOUD_SHADOWS && defined VOLUMETRIC_CLOUDS
    if (lightDir.y < 0.05) return 1.0;
    float mid = CLOUD_ALTITUDE + CLOUD_THICKNESS * 0.45;
    vec3 p = worldPos + lightDir * ((mid - worldPos.y) / lightDir.y);
    float d = cloudDensity(p, false);
    return mix(1.0, exp(-d * cloudSigma * CLOUD_THICKNESS * 0.5), 0.85);
#else
    return 1.0;
#endif
}

#endif
