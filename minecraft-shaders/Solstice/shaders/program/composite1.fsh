/*
    Temporal anti-aliasing and auto exposure.
    History lives in colortex5 (never cleared); its alpha channel carries the
    smoothed exposure value from frame to frame.
*/
#include "/lib/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex5;
uniform sampler2D depthtex0;

const bool colortex0MipmapEnabled = true;

in vec2 texcoord;

/* RENDERTARGETS: 0,5 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outHistory;

float computeExposure() {
    float maxLod = floor(log2(max(viewWidth, viewHeight)));
    float lod = max(maxLod - 4.0, 0.0);
    float logSum = 0.0;
    float weightSum = 0.0;
    for (int y = 0; y < 5; y++) {
        for (int x = 0; x < 5; x++) {
            vec2 uv = (vec2(x, y) + 0.5) / 5.0;
            float w = exp(-dot(uv - 0.5, uv - 0.5) * 6.0);
            float lum = luminance(textureLod(colortex0, uv, lod).rgb);
            logSum += log(max(lum, 1e-5)) * w;
            weightSum += w;
        }
    }
    float avgLum = exp(logSum / weightSum);
    // Partial adaptation: nights and caves stay darker than day, as they should.
    float target = 0.74 / pow(max(avgLum, 1e-4), 0.37);
    target = clamp(target, 0.2, 8.0);

    float prev = texelFetch(colortex5, ivec2(0), 0).a;
    if (!(prev > 0.0) || prev > 1e4) return target;
    // Adapt faster when it gets brighter (eyes close quickly, open slowly)
    float speed = (target < prev ? 2.2 : 1.1) * EXPOSURE_SPEED;
    return mix(prev, target, 1.0 - exp(-frameTime * speed));
}

#ifdef TAA
vec3 toYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}
vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}
vec3 tonemapWeight(vec3 c)  { return c / (1.0 + luminance(c)); }
vec3 untonemapWeight(vec3 c) { return c / max(1.0 - luminance(c), 1e-4); }

vec3 sampleHistory(vec2 uv) {
    vec2 texSize = vec2(viewWidth, viewHeight);
    vec2 samplePos = uv * texSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 tc0  = (texPos1 - 1.0) / texSize;
    vec2 tc3  = (texPos1 + 2.0) / texSize;
    vec2 tc12 = (texPos1 + w2 / w12) / texSize;

    vec3 result = vec3(0.0);
    result += textureLod(colortex5, vec2(tc12.x, tc0.y),  0.0).rgb * (w12.x * w0.y);
    result += textureLod(colortex5, vec2(tc0.x,  tc12.y), 0.0).rgb * (w0.x  * w12.y);
    result += textureLod(colortex5, vec2(tc12.x, tc12.y), 0.0).rgb * (w12.x * w12.y);
    result += textureLod(colortex5, vec2(tc3.x,  tc12.y), 0.0).rgb * (w3.x  * w12.y);
    result += textureLod(colortex5, vec2(tc12.x, tc3.y),  0.0).rgb * (w12.x * w3.y);
    float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(result / wsum, 0.0);
}

vec2 reproject(vec2 uv, float depth) {
    if (depth < 0.56) return uv; // hand moves with the camera
    vec3 viewPos = screenToView(vec3(uv, depth));
    vec3 playerPos = viewToPlayer(viewPos);
    if (depth >= 1.0) playerPos = normalize(playerPos) * far * 4.0;
    vec3 prevPlayer = playerPos + cameraPosition - previousCameraPosition;
    vec4 prevView = gbufferPreviousModelView * vec4(prevPlayer, 1.0);
    vec4 prevClip = gbufferPreviousProjection * prevView;
    return prevClip.xy / prevClip.w * 0.5 + 0.5;
}
#endif

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec3 current = texelFetch(colortex0, px, 0).rgb;
    // Only pixel (0, 0) is read back by final, so only it pays for the exposure probe.
    float exposure = px == ivec2(0) ? computeExposure() : 0.0;

#ifdef TAA
    // Front-most depth of a 3x3 cross so edges reproject with the foreground
    float depth = texelFetch(depthtex0, px, 0).r;
    ivec2 maxPx = ivec2(viewWidth, viewHeight) - 1;
    for (int i = 0; i < 4; i++) {
        ivec2 o = ivec2[4](ivec2(1, 1), ivec2(-1, 1), ivec2(1, -1), ivec2(-1, -1))[i];
        depth = min(depth, texelFetch(depthtex0, clamp(px + o, ivec2(0), maxPx), 0).r);
    }

    vec2 prevUv = reproject(texcoord, depth);
    bool offscreen = any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)));

    vec3 m1 = vec3(0.0);
    vec3 m2 = vec3(0.0);
    vec3 currentY = toYCoCg(tonemapWeight(current));
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec3 c = toYCoCg(tonemapWeight(texelFetch(colortex0, clamp(px + ivec2(x, y), ivec2(0), maxPx), 0).rgb));
            m1 += c;
            m2 += c * c;
        }
    }
    vec3 mean = m1 / 9.0;
    vec3 sigma = sqrt(max(m2 / 9.0 - mean * mean, 0.0));
    vec3 boxMin = mean - sigma * 1.25;
    vec3 boxMax = mean + sigma * 1.25;

    vec3 history = toYCoCg(tonemapWeight(sampleHistory(prevUv)));
    // Clip towards the box centre instead of clamping per channel
    vec3 center = 0.5 * (boxMax + boxMin);
    vec3 extents = max(0.5 * (boxMax - boxMin), 1e-5);
    vec3 offset = history - center;
    vec3 units = abs(offset / extents);
    float maxUnit = max(units.x, max(units.y, units.z));
    if (maxUnit > 1.0) history = center + offset / maxUnit;

    float velocity = length((texcoord - prevUv) * vec2(viewWidth, viewHeight));
    float weight = offscreen ? 0.0 : TAA_BLEND * mix(1.0, 0.85, saturate(velocity / 12.0));
    vec3 resolved = untonemapWeight(fromYCoCg(mix(currentY, history, weight)));
#else
    vec3 resolved = current;
#endif

    outColor = vec4(resolved, 1.0);
    outHistory = vec4(resolved, exposure);
}
