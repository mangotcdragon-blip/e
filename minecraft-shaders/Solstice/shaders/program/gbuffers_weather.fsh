#include "/lib/common.glsl"
#include "/lib/lighting.glsl"

uniform sampler2D gtexture;

in vec2 texcoord;
in vec2 lmcoord;
in vec4 glcolor;
flat in vec3 lightCol;
flat in vec3 ambientCol;

/* RENDERTARGETS: 3 */
layout(location = 0) out vec4 outTranslucent;

void main() {
    vec4 albedo = texture(gtexture, texcoord) * glcolor;
    if (albedo.a < 0.01) discard;
    vec3 base = srgbToLinear(albedo.rgb);
    vec3 light = ambientCol * 1.4 + lightCol * 0.15 + blockLightColor() * blockLightFalloff(lmcoord.x);
    outTranslucent = vec4(base * light, albedo.a * 0.45);
}
