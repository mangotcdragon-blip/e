#include "/lib/common.glsl"
#include "/lib/lighting.glsl"
#include "/lib/water.glsl"

uniform sampler2D gtexture;

in vec2 texcoord;
in vec2 lmcoord;
in vec4 glcolor;
in vec3 worldNormal;
in vec3 playerPos;
flat in int blockId;
flat in vec3 lightCol;
flat in vec3 ambientCol;

/* RENDERTARGETS: 3,4 */
layout(location = 0) out vec4 outTranslucent;
layout(location = 1) out vec4 outData;

void main() {
    vec3 geoNormal = normalize(worldNormal);
    if (!gl_FrontFacing) geoNormal = -geoNormal;

#ifndef PROGRAM_HAND_WATER
    if (blockId == ID_WATER) {
        vec3 worldPos = playerPos + cameraPosition;
        vec3 normal = geoNormal.y < -0.5 ? -waterNormal(worldPos, -geoNormal) : waterNormal(worldPos, geoNormal);
        outTranslucent = vec4(0.0);
        outData = vec4(octEncode(normal), pack2x8(vec2(float(MAT_WATER) / 255.0, lmcoord.y)), packRGB565(glcolor.rgb));
        return;
    }
#endif

    vec4 albedo = texture(gtexture, texcoord) * glcolor;
    if (albedo.a < 0.01) discard;

    Surface s;
    s.albedo     = srgbToLinear(albedo.rgb);
    s.normal     = geoNormal;
    s.geoNormal  = geoNormal;
    s.lightmap   = lmcoord;
    s.ao         = 1.0;
    s.smoothness = 0.92;
    s.f0         = 0.04;
    s.emission   = blockId == ID_PORTAL ? 0.8 : 0.0;
#ifdef PROGRAM_HAND_WATER
    s.material   = MAT_HAND;
#else
    s.material   = MAT_DEFAULT;
#endif

    float dither = ignTemporal(gl_FragCoord.xy);
    vec3 color = shadeSurface(s, playerPos, dither, lightCol, ambientCol);

    outTranslucent = vec4(color, albedo.a);
    outData = vec4(octEncode(geoNormal), pack2x8(vec2(float(MAT_GLASS) / 255.0, lmcoord.y)), 0.0);
}
