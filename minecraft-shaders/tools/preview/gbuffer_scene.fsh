// Writes the opaque G-buffer + both depth buffers + translucent data for the analytic scene.
#include "/lib/common.glsl"
#include "/lib/water.glsl"
#include "/tools/preview/scene.glsl"

in vec2 texcoord;
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outMaterial;
layout(location = 3) out vec4 outTranslucent;
layout(location = 4) out vec4 outData;
layout(location = 5) out vec4 outDepth0; // with water
layout(location = 6) out vec4 outDepth1; // opaque only

float worldToDepth(vec3 worldPos) {
    vec3 viewPos = playerToView(worldPos - cameraPosition);
    return viewToScreen(viewPos).z;
}

void main() {
    vec3 viewDir = normalize(screenToView(vec3(texcoord, 1.0)));
    vec3 rd = normalize(mat3(gbufferModelViewInverse) * viewDir);
    vec3 ro = cameraPosition;

    outColor = vec4(0.0, 0.0, 0.0, 1.0);
    outNormal = vec4(0.0);
    outMaterial = vec4(0.0);
    outTranslucent = vec4(0.0);
    outData = vec4(0.0);

    vec3 n; int idx;
    float t = traceScene(ro, rd, n, idx);
    float depth1 = 1.0;
    if (t > 0.0) {
        vec3 p = ro + rd * t;
        depth1 = worldToDepth(p);
        Box b = boxes[idx];
        // subtle per-block texture variation
        vec3 cell = floor(p - n * 0.01);
        float v = hash12(cell.xz * 1.7 + cell.y * 9.1);
        vec3 albedo = b.albedo * (0.85 + 0.3 * v);
        int material = MAT_DEFAULT;
        float smoothness = 0.12, f0 = 0.04, emission = 0.0;
        if (b.kind == 1) { material = MAT_FOLIAGE; smoothness = 0.25; }
        if (b.kind == 2) { smoothness = 0.75; f0 = 1.0; }
        if (b.kind == 3) { emission = 0.9; }
        if (b.kind == 4) { smoothness = 0.65; }
        float blockLm = saturate(1.0 - (distance(p, torchPos) - 0.5) / 14.0);
        if (b.kind == 3) blockLm = 1.0;
        float skyLm = 1.0;
        if (p.x > -10.0 && p.x < -2.0 && p.z > -24.0 && p.z < -16.0 && p.y < 69.0) skyLm = 0.3;
        outColor = vec4(albedo, 1.0);
        outNormal = vec4(pack2x8(octEncode(n)), pack2x8(octEncode(n)), blockLm, skyLm);
        outMaterial = vec4(pack2x8(vec2(float(material) / 255.0, 1.0)), smoothness, f0, emission);
    }

    float depth0 = depth1;
    float tw = traceWater(ro, rd);
    if (tw > 0.0 && (t < 0.0 || tw < t)) {
        vec3 p = ro + rd * tw;
        depth0 = worldToDepth(p);
        vec3 normal = waterNormal(p, vec3(0.0, 1.0, 0.0));
        outData = vec4(octEncode(normal), pack2x8(vec2(float(MAT_WATER) / 255.0, 1.0)), packRGB565(vec3(0.247, 0.463, 0.894)));
    }
    outDepth0 = vec4(depth0);
    outDepth1 = vec4(depth1);
}
