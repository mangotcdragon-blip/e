/*
    Final: sharpening, bloom, exposure, tonemapping and colour grading.
*/
#include "/lib/common.glsl"
#include "/lib/bloom.glsl"

/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16;
const int colortex2Format = RGBA16;
const int colortex3Format = RGBA16F;
const int colortex4Format = RGBA16;
const int colortex5Format = RGBA16F;
const int colortex6Format = RGBA16F;
const int colortex7Format = RGBA16F;

const bool colortex5Clear = false;

const vec4 colortex0ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
const vec4 colortex1ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex2ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex3ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex4ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex6ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex7ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
*/

uniform sampler2D colortex0;
uniform sampler2D colortex5;
uniform sampler2D colortex6;

in vec2 texcoord;

layout(location = 0) out vec4 fragColor;

// ---------------------------------------------------------------- tonemappers
vec3 tonemapACES(vec3 c) {
    const mat3 inputMat  = mat3(0.59719, 0.07600, 0.02840, 0.35458, 0.90834, 0.13383, 0.04823, 0.01566, 0.83777);
    const mat3 outputMat = mat3(1.60475, -0.10208, -0.00327, -0.53108, 1.10813, -0.07276, -0.07367, -0.00605, 1.07602);
    c = inputMat * c;
    c = (c * (c + 0.0245786) - 0.000090537) / (c * (0.983729 * c + 0.4329510) + 0.238081);
    return saturate(outputMat * c);
}

vec3 agxContrast(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4 - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
}

vec3 tonemapAgX(vec3 c) {
    const mat3 agxMat = mat3(0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                             0.0784335999999992, 0.878468636469772, 0.0784336,
                             0.0792237451477643, 0.0791661274605434, 0.879142973793104);
    const mat3 agxMatInv = mat3(1.19687900512017, -0.0528968517574562, -0.0529716355144438,
                                -0.0980208811401368, 1.15190312990417, -0.0980434501171241,
                                -0.0990297440797205, -0.0989611768448433, 1.15107367264116);
    const float minEv = -12.47393;
    const float maxEv = 4.026069;
    c = agxMat * max(c, 1e-10);
    c = clamp(log2(c), minEv, maxEv);
    c = (c - minEv) / (maxEv - minEv);
    c = agxContrast(c);
    // Mild "punchy" look
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = pow(max(c, 0.0), vec3(1.08));
    c = luma + 1.18 * (c - luma);
    c = agxMatInv * c;
    return saturate(pow(max(c, 0.0), vec3(2.2)));
}

vec3 hableCurve(vec3 x) {
    const float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}
vec3 tonemapHable(vec3 c) {
    return saturate(hableCurve(c * 2.0) / hableCurve(vec3(11.2)));
}

// ---------------------------------------------------------------- bloom
vec3 getBloom() {
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    vec2 halfTexel = 0.5 / vec2(viewWidth, viewHeight);
    for (int i = 0; i < BLOOM_TILES; i++) {
        vec3 tile = bloomTile(i);
        vec2 uv = tile.xy + texcoord * tile.z;
        uv = clamp(uv, tile.xy + halfTexel, tile.xy + tile.z - halfTexel);
        float w = pow(0.85, float(i));
        sum += textureLod(colortex6, uv, 0.0).rgb * w;
        wsum += w;
    }
    return sum / wsum;
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    ivec2 maxPx = ivec2(viewWidth, viewHeight) - 1;
    vec3 color = texelFetch(colortex0, px, 0).rgb;

    // Contrast adaptive sharpening (counteracts TAA softness)
    if (SHARPENING > 0.0) {
        vec3 n = texelFetch(colortex0, clamp(px + ivec2(0, 1), ivec2(0), maxPx), 0).rgb;
        vec3 s = texelFetch(colortex0, clamp(px - ivec2(0, 1), ivec2(0), maxPx), 0).rgb;
        vec3 e = texelFetch(colortex0, clamp(px + ivec2(1, 0), ivec2(0), maxPx), 0).rgb;
        vec3 w = texelFetch(colortex0, clamp(px - ivec2(1, 0), ivec2(0), maxPx), 0).rgb;
        vec3 mn = min(color, min(min(n, s), min(e, w)));
        vec3 mx = max(color, max(max(n, s), max(e, w)));
        float lmn = luminance(mn), lmx = luminance(mx);
        float amount = sqrt(saturate(min(lmn, max(1.0 - lmx, 0.0) + lmn) / max(lmx, 1e-4)));
        vec3 sharpened = color + (4.0 * color - n - s - e - w) * 0.25 * SHARPENING * amount;
        color = clamp(sharpened, mn, mx);
    }

#ifdef BLOOM
    color = mix(color, getBloom(), BLOOM_STRENGTH);
#endif

    // Scotopic vision: very dark scenes lose colour and shift towards blue.
    float sceneLum = luminance(color);
    float scotopic = (1.0 - smoothstep(0.0008, 0.03, sceneLum)) * 0.75;
    color = mix(color, sceneLum * vec3(0.62, 0.8, 1.25), scotopic);

#ifdef AUTO_EXPOSURE
    float exposure = texelFetch(colortex5, ivec2(0), 0).a;
    if (!(exposure > 0.0)) exposure = 1.0;
#else
    float eyeSky = eyeBrightnessSmooth.y / 240.0;
    float exposure = mix(2.5, 1.0, eyeSky);
#endif
    color *= exposure * EXPOSURE;

    // Grading in linear light
    float luma = luminance(color);
    color = max(mix(vec3(luma), color, SATURATION), 0.0);
    color = 0.18 * pow(color / 0.18 + 1e-6, vec3(CONTRAST));

#if TONEMAP == 0
    color = tonemapACES(color);
#elif TONEMAP == 1
    color = tonemapAgX(color);
#else
    color = tonemapHable(color);
#endif

    vec2 vc = texcoord - 0.5;
    color *= 1.0 - VIGNETTE * dot(vc, vc) * 1.6;

    color = linearToSrgb(color);
    color += (hash12(gl_FragCoord.xy + float(frameCounter & 255) * 1.618) - 0.5) / 255.0;
    fragColor = vec4(color, 1.0);
}
