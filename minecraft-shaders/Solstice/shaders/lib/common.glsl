#ifndef COMMON_GLSL
#define COMMON_GLSL

#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"

#define PI   3.14159265359
#define TAU  6.28318530718
#define RCP_PI 0.31830988618
#define saturate(x) clamp(x, 0.0, 1.0)

// ---------------------------------------------------------------- material ids (colortex2.r * 255)
#define MAT_DEFAULT   0
#define MAT_FOLIAGE   1
#define MAT_HAND      2
#define MAT_ENTITY    3
#define MAT_PARTICLE  4
#define MAT_LAVA      5
#define MAT_WATER     10
#define MAT_GLASS     11

// ---------------------------------------------------------------- block ids from block.properties
#define ID_PLANT        10001
#define ID_TALL_LOWER   10002
#define ID_TALL_UPPER   10003
#define ID_LEAVES       10004
#define ID_VINES        10005
#define ID_FOLIAGE      10006
#define ID_WATER        10010
#define ID_GLASS        10011
#define ID_PORTAL       10012
#define ID_LIGHT        10020
#define ID_LAVA         10021
#define ID_FIRE         10022
#define ID_LIGHT_SOFT   10023
#define ID_METAL        10030
#define ID_POLISHED     10031
#define ID_ICE          10032

// ---------------------------------------------------------------- small math helpers
float sqr(float x)  { return x * x; }
float pow4(float x) { x *= x; return x * x; }
float pow5(float x) { return pow4(x) * x; }
float maxOf(vec3 v) { return max(v.x, max(v.y, v.z)); }
float minOf(vec3 v) { return min(v.x, min(v.y, v.z)); }

float luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec3 srgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}
vec3 linearToSrgb(vec3 c) {
    c = max(c, 0.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

// ---------------------------------------------------------------- noise
float ign(vec2 p) {
    return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y));
}
// Interleaved gradient noise that changes every frame, so TAA can integrate it.
float ignTemporal(vec2 p) {
#ifdef TAA
    p += 5.588238 * float(frameCounter & 63);
#endif
    return ign(p);
}
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}
vec3 hash32(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yzz) * p3.zyx);
}

// ---------------------------------------------------------------- packing
vec2 signNotZero(vec2 v) { return vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0); }

vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * signNotZero(n.xy);
    return e * 0.5 + 0.5;
}
vec3 octDecode(vec2 e) {
    e = e * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    float t = saturate(-n.z);
    n.xy += vec2(n.x >= 0.0 ? -t : t, n.y >= 0.0 ? -t : t);
    return normalize(n);
}

// Two 8 bit values in one 16 bit unorm channel.
float pack2x8(vec2 v) {
    v = floor(saturate(v) * 255.0 + 0.5);
    return (v.x * 256.0 + v.y) / 65535.0;
}
vec2 unpack2x8(float f) {
    float x  = floor(f * 65535.0 + 0.5);
    float hi = floor(x / 256.0);
    return vec2(hi, x - hi * 256.0) / 255.0;
}
// RGB 5-6-5 in one 16 bit unorm channel.
float packRGB565(vec3 c) {
    c = floor(saturate(c) * vec3(31.0, 63.0, 31.0) + 0.5);
    return (c.r * 2048.0 + c.g * 32.0 + c.b) / 65535.0;
}
vec3 unpackRGB565(float f) {
    float x = floor(f * 65535.0 + 0.5);
    float r = floor(x / 2048.0);
    float g = floor((x - r * 2048.0) / 32.0);
    float b = x - r * 2048.0 - g * 32.0;
    return vec3(r, g, b) / vec3(31.0, 63.0, 31.0);
}

// ---------------------------------------------------------------- space conversions
vec3 projectAndDivide(mat4 m, vec3 p) {
    vec4 h = m * vec4(p, 1.0);
    return h.xyz / h.w;
}
vec3 screenToView(vec3 screenPos) {
    return projectAndDivide(gbufferProjectionInverse, screenPos * 2.0 - 1.0);
}
vec3 viewToScreen(vec3 viewPos) {
    return projectAndDivide(gbufferProjection, viewPos) * 0.5 + 0.5;
}
vec3 viewToPlayer(vec3 viewPos) {
    return mat3(gbufferModelViewInverse) * viewPos + gbufferModelViewInverse[3].xyz;
}
vec3 playerToView(vec3 playerPos) {
    return mat3(gbufferModelView) * (playerPos - gbufferModelViewInverse[3].xyz);
}
float linearizeDepth(float depth) {
    return (2.0 * near * far) / (far + near - (depth * 2.0 - 1.0) * (far - near));
}

// ---------------------------------------------------------------- world directions
vec3 sunDirWorld()   { return normalize(mat3(gbufferModelViewInverse) * sunPosition); }
vec3 moonDirWorld()  { return normalize(mat3(gbufferModelViewInverse) * moonPosition); }
vec3 lightDirWorld() { return normalize(mat3(gbufferModelViewInverse) * shadowLightPosition); }

// ---------------------------------------------------------------- TAA jitter
vec2 taaOffset() {
#ifdef TAA
    const vec2 halton[8] = vec2[8](
        vec2( 0.0,    -0.1667), vec2(-0.25,   0.1667), vec2( 0.25,  -0.3889), vec2(-0.375, -0.0556),
        vec2( 0.125,   0.2778), vec2(-0.125, -0.2778), vec2( 0.375,  0.0556), vec2(-0.4375, 0.3889));
    return halton[frameCounter & 7] / vec2(viewWidth, viewHeight);
#else
    return vec2(0.0);
#endif
}

// ---------------------------------------------------------------- lightmap helpers
// Vanilla lightmap coords live in [1/32, 31/32]; remap to [0, 1].
vec2 normalizeLightmap(vec2 lm) {
    return saturate((lm - 1.0 / 32.0) * (16.0 / 15.0));
}

#endif
