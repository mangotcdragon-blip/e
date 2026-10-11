#ifndef SSR_GLSL
#define SSR_GLSL

#include "/lib/common.glsl"

/*
    Screen space ray march. Interpolates (x, y, ndc depth) linearly in screen space,
    which is exact for a straight 3D segment under perspective. Steps are distributed
    non-uniformly (denser near the origin) and the hit is refined with a binary search.
    Returns xy = hit uv, z = confidence (0 = miss).
*/
vec3 traceScreenSpace(sampler2D depthTex, vec3 viewPos, vec3 dir, float dither) {
    float maxLen = far * 1.5;
    if (dir.z > 0.0) maxLen = min(maxLen, (-near - viewPos.z) / dir.z * 0.98);
    if (maxLen <= 0.0) return vec3(0.0);

    vec3 start = viewToScreen(viewPos);
    vec3 end   = viewToScreen(viewPos + dir * maxLen);
    vec3 delta = end - start;

    // Clip the segment to the screen
    float tMax = 1.0;
    if (delta.x > 0.0) tMax = min(tMax, (1.0 - start.x) / delta.x);
    if (delta.x < 0.0) tMax = min(tMax, -start.x / delta.x);
    if (delta.y > 0.0) tMax = min(tMax, (1.0 - start.y) / delta.y);
    if (delta.y < 0.0) tMax = min(tMax, -start.y / delta.y);
    delta *= tMax;

    float startLinear = linearizeDepth(start.z);
    vec2 texSize = vec2(viewWidth, viewHeight);
    float prevT = 0.0;

    for (int i = 1; i <= SSR_STEPS; i++) {
        float t = pow((float(i) - 1.0 + dither) / float(SSR_STEPS), 1.7);
        vec3 p = start + delta * t;
        if (p.z >= 1.0) break;
        float sceneDepth = texelFetch(depthTex, ivec2(p.xy * texSize), 0).r;
        if (sceneDepth < 0.56) { prevT = t; continue; } // hand

        if (p.z > sceneDepth) {
            float rayLinear   = linearizeDepth(p.z);
            float sceneLinear = linearizeDepth(sceneDepth);
            float stepLinear  = abs(rayLinear - linearizeDepth((start + delta * prevT).z));
            float thickness   = max(stepLinear * 1.5, 0.25 + startLinear * 0.01);
            if (rayLinear - sceneLinear < thickness) {
                float lo = prevT, hi = t;
                for (int j = 0; j < SSR_REFINE_STEPS; j++) {
                    float mid = 0.5 * (lo + hi);
                    vec3 q = start + delta * mid;
                    float d = texelFetch(depthTex, ivec2(q.xy * texSize), 0).r;
                    if (q.z > d) hi = mid; else lo = mid;
                }
                vec2 hitUv = (start + delta * hi).xy;
                vec2 edge = smoothstep(0.0, 0.08, hitUv) * smoothstep(1.0, 0.92, hitUv);
                float confidence = edge.x * edge.y;
                return vec3(hitUv, confidence);
            }
        }
        prevT = t;
    }
    return vec3(0.0);
}

#endif
