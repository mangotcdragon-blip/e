#ifndef ATMOSPHERE_GLSL
#define ATMOSPHERE_GLSL

#include "/lib/common.glsl"

/*
    Physically based single scattering sky (Rayleigh + Mie + ozone) and the
    matching analytic sun / moon light colours. Units are relative HDR values;
    the auto exposure in final brings them to display range.
*/

const float planetRadius = 6371e3;
const float atmosRadius  = 6471e3;
const float rayleighH    = 8000.0;
const float mieH         = 1200.0;
const vec3  rayleighBeta = vec3(5.802e-6, 13.558e-6, 33.1e-6);
const float mieBeta      = 3.996e-6;
const float mieExtBeta   = 4.44e-6;
const vec3  ozoneBeta    = vec3(0.650e-6, 1.881e-6, 0.085e-6);

const float skyScale     = 40.0;   // radiance scale of the scattering integral
const float sunIntensity = 3.0;    // direct sun illuminance at zenith
const float moonRelative = 0.02;   // moon light relative to sun light

float rayleighPhase(float mu) { return 0.0596831 * (1.0 + mu * mu); }

float hgPhase(float mu, float g) {
    float g2 = g * g;
    return 0.0795775 * (1.0 - g2) / pow(1.0 + g2 - 2.0 * g * mu, 1.5);
}

float cornetteShanks(float mu, float g) {
    float g2 = g * g;
    return 0.1193662 * ((1.0 - g2) * (1.0 + mu * mu)) / ((2.0 + g2) * pow(1.0 + g2 - 2.0 * g * mu, 1.5));
}

vec2 raySphere(vec3 ro, vec3 rd, float r) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - r * r;
    float d = b * b - c;
    if (d < 0.0) return vec2(-1.0);
    d = sqrt(d);
    return vec2(-b - d, -b + d);
}

float ozoneDensity(float h) { return max(0.0, 1.0 - abs(h - 25000.0) / 15000.0); }

float moonPhaseBrightness() {
    const float phases[8] = float[8](1.0, 0.8, 0.55, 0.3, 0.12, 0.3, 0.55, 0.8);
    return phases[moonPhase & 7];
}

// Analytic transmittance through the whole atmosphere (Kasten-Young air mass).
vec3 atmosphereTransmittance(float cosZenith) {
    float c  = max(cosZenith, 0.0);
    float zd = degrees(acos(c));
    float airmass = 1.0 / (c + 0.50572 * pow(max(96.07995 - zd, 1e-3), -1.6364));
    vec3 tau = rayleighBeta * rayleighH + mieExtBeta * mieH + ozoneBeta * 15000.0;
    tau += rainStrength * 0.12;
    return exp(-tau * airmass);
}

vec3 sunIlluminance(vec3 sunDir) {
    float horizon = smoothstep(-0.02, 0.05, sunDir.y);
    return sunIntensity * SUN_BRIGHTNESS * atmosphereTransmittance(sunDir.y) * horizon;
}

vec3 moonIlluminance(vec3 moonDir) {
    float horizon = smoothstep(-0.02, 0.05, moonDir.y);
    vec3 tint = vec3(0.70, 0.82, 1.0);
    return sunIntensity * moonRelative * MOON_BRIGHTNESS * moonPhaseBrightness() * tint
         * atmosphereTransmittance(moonDir.y) * horizon;
}

// Colour of the current shadow casting light (sun by day, moon by night).
vec3 directLightColor() {
#if defined WORLD_OVERWORLD
    vec3 sunDir = sunDirWorld();
    vec3 light = sunDir.y > 0.0 ? sunIlluminance(sunDir) : moonIlluminance(moonDirWorld());
    return light * (1.0 - rainStrength * 0.85);
#else
    return vec3(0.0);
#endif
}

// Single scattering integral for one light source, unit intensity.
vec3 scatterSky(vec3 rd, vec3 lightDir, int steps, int lightSteps) {
    vec3 ro = vec3(0.0, planetRadius + 300.0, 0.0);
    float tEnd = raySphere(ro, rd, atmosRadius).y;
    vec2 tGround = raySphere(ro, rd, planetRadius);
    if (tGround.x > 0.0) tEnd = tGround.x;

    float ds = tEnd / float(steps);
    float mu = dot(rd, lightDir);
    float phaseR = rayleighPhase(mu);
    float phaseM = cornetteShanks(mu, 0.76);

    vec3 odView = vec3(0.0); // rayleigh, mie, ozone optical depths along the view ray
    vec3 sumR = vec3(0.0);
    vec3 sumM = vec3(0.0);
    vec3 sumMS = vec3(0.0);
    // Multiple scattering approximation: every lit sample also scatters an isotropic,
    // sky coloured light field, which keeps the horizon from turning yellow.
    const vec3 msRadiance = vec3(0.012, 0.02, 0.035);

    for (int i = 0; i < steps; i++) {
        vec3 p = ro + rd * ((float(i) + 0.5) * ds);
        float h = length(p) - planetRadius;
        vec3 d = vec3(exp(-h / rayleighH), exp(-h / mieH), ozoneDensity(h)) * ds;
        odView += d;

        if (raySphere(p, lightDir, planetRadius).x > 0.0) continue;

        float tl  = raySphere(p, lightDir, atmosRadius).y;
        float dsl = tl / float(lightSteps);
        vec3 odLight = vec3(0.0);
        for (int j = 0; j < lightSteps; j++) {
            vec3 q = p + lightDir * ((float(j) + 0.5) * dsl);
            float hl = length(q) - planetRadius;
            odLight += vec3(exp(-hl / rayleighH), exp(-hl / mieH), ozoneDensity(hl)) * dsl;
        }

        vec3 viewT  = exp(-(rayleighBeta * odView.x + mieExtBeta * odView.y + ozoneBeta * odView.z));
        vec3 lightT = exp(-(rayleighBeta * odLight.x + mieExtBeta * odLight.y + ozoneBeta * odLight.z));
        vec3 att = viewT * lightT;
        sumR += d.x * att;
        sumM += d.y * att;
        sumMS += (rayleighBeta * d.x + mieBeta * d.y) * viewT * luminance(lightT);
    }
    return sumR * rayleighBeta * phaseR + sumM * mieBeta * phaseM + sumMS * msRadiance;
}

// Full sky radiance (sun + moon scattering + night airglow), no sun disc, no clouds.
vec3 computeSky(vec3 dir, int steps, int lightSteps) {
#if defined WORLD_OVERWORLD
    vec3 sunDir  = sunDirWorld();
    vec3 moonDir = moonDirWorld();
    vec3 rd = dir;
    rd.y = max(rd.y, 0.0005);
    rd = normalize(rd);

    vec3 sky = vec3(0.0);
    if (sunDir.y > -0.25)
        sky += scatterSky(rd, sunDir, steps, lightSteps) * skyScale * SUN_BRIGHTNESS;
    if (moonDir.y > -0.25) {
        vec3 moonSky = scatterSky(rd, moonDir, steps, lightSteps) * skyScale * moonRelative * MOON_BRIGHTNESS
                     * moonPhaseBrightness();
        // Moonlit skies read as a cool, desaturated blue
        sky += mix(vec3(luminance(moonSky)), moonSky, 0.4) * vec3(0.65, 0.8, 1.15);
    }

    sky += vec3(0.0010, 0.0016, 0.0030) * (1.0 - 0.5 * rd.y);       // airglow so nights are not pitch black

    // Rain: grey, flat overcast
    float rainLum = luminance(sky);
    sky = mix(sky, vec3(rainLum) * vec3(0.85, 0.9, 1.0) * 0.6, rainStrength * 0.85);

    // Below the horizon fade to a dark ground colour
    if (dir.y < 0.0) sky *= mix(1.0, 0.35, saturate(-dir.y * 6.0));

    return sky * SKY_BRIGHTNESS;
#elif defined WORLD_NETHER
    return srgbToLinear(fogColor) * 0.6;
#else
    vec3 base = vec3(0.012, 0.008, 0.02);
    return base * (1.0 + 2.0 * pow(1.0 - abs(dir.y), 6.0) * vec3(1.2, 0.8, 1.6));
#endif
}

// Sun disc with limb darkening, already attenuated by the atmosphere.
vec3 sunDisc(vec3 dir, vec3 sunDir) {
    float cosR = cos(radians(0.55 * SUN_DISC_SIZE));
    float mu = dot(dir, sunDir);
    if (mu < cosR - 0.0005) return vec3(0.0);
    float r = saturate((1.0 - mu) / (1.0 - cosR));
    float limb = 1.0 - 0.6 * (1.0 - sqrt(max(1.0 - r * r, 0.0)));
    float edge = smoothstep(cosR - 0.0005, cosR + 0.0002, mu);
    return atmosphereTransmittance(dir.y) * 120.0 * SUN_BRIGHTNESS * limb * edge * (1.0 - rainStrength);
}

// Procedural stars for dimensions/settings without vanilla stars.
vec3 proceduralStars(vec3 dir) {
    vec3 p = dir * 300.0;
    vec3 cell = floor(p);
    vec3 f = fract(p) - 0.5;
    vec3 h = hash32(cell.xy + cell.z * 17.13);
    float star = step(0.985 - 0.01 * STAR_AMOUNT, h.x);
    float d = length(f - (h - 0.5) * 0.6);
    return vec3(star * smoothstep(0.12, 0.0, d) * (0.5 + h.y)) * mix(vec3(1.0, 0.8, 0.6), vec3(0.7, 0.85, 1.0), h.z);
}

// Cheap ambient estimate used only where the sky info pixel is unavailable (forward translucents).
vec3 analyticSkyAmbient() {
#if defined WORLD_OVERWORLD
    vec3 sunDir = sunDirWorld();
    float day   = smoothstep(-0.15, 0.25, sunDir.y);
    vec3 dayCol = vec3(0.30, 0.48, 0.85) * (0.35 + 0.65 * smoothstep(0.0, 0.5, sunDir.y));
    vec3 twilight = vec3(0.35, 0.25, 0.25) * smoothstep(-0.15, 0.0, sunDir.y) * (1.0 - smoothstep(0.0, 0.3, sunDir.y));
    vec3 night  = vec3(0.004, 0.006, 0.012) * (0.5 + 0.5 * moonPhaseBrightness());
    vec3 amb = (dayCol * day + twilight + night) * SKY_BRIGHTNESS;
    return mix(amb, vec3(luminance(amb)) * 0.6, rainStrength * 0.8);
#elif defined WORLD_NETHER
    return srgbToLinear(fogColor) * 0.5 + vec3(0.03, 0.015, 0.01);
#else
    return vec3(0.05, 0.035, 0.08);
#endif
}

#endif
