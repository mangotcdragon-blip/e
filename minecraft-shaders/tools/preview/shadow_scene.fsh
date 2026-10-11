// Ray traces the scene from the light into a distorted shadow map, matching shadow.vsh.
#include "/lib/common.glsl"
#include "/lib/shadows.glsl"
#include "/tools/preview/scene.glsl"

in vec2 texcoord;
layout(location = 0) out vec4 outDepth;

void main() {
    vec2 d = texcoord * 2.0 - 1.0;
    float k = SHADOW_DISTORTION;
    float dl = max(length(d), 1e-6);
    float r = dl * (1.0 - k) / max(1.0 - dl * k, 1e-4);
    vec2 clipXY = d / dl * r;

    // A point on the near plane of the shadow frustum, then trace towards the scene.
    vec3 shadowView = (shadowProjectionInverse * vec4(clipXY, -1.0, 1.0)).xyz;
    vec3 playerPos = (shadowModelViewInverse * vec4(shadowView, 1.0)).xyz;
    vec3 rd = -lightDirWorld();
    vec3 ro = playerPos + cameraPosition;
    vec3 n; int idx;
    float t = traceScene(ro, rd, n, idx);
    float depth = 1.0;
    if (t > 0.0) {
        vec3 hitPlayer = ro + rd * t - cameraPosition;
        vec3 sc = playerToShadowClip(hitPlayer);
        depth = sc.z * 0.2 * 0.5 + 0.5;
    }
    outDepth = vec4(depth, 0.0, 0.0, 1.0);
}
