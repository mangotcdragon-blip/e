#include "/lib/common.glsl"

uniform sampler2D gtexture;
uniform int renderStage;

in vec2 texcoord;
in vec4 glcolor;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
#ifdef SKY_BASIC
    #if defined STARS && defined MC_RENDER_STAGE_STARS
    if (renderStage != MC_RENDER_STAGE_STARS) discard;
    outColor = vec4(glcolor.rgb * glcolor.a * STAR_AMOUNT, 1.0);
    #else
    discard;
    #endif
#else
    #if defined WORLD_OVERWORLD && defined MC_RENDER_STAGE_SUN
    if (renderStage == MC_RENDER_STAGE_SUN) discard;
    #endif
    vec4 color = texture(gtexture, texcoord) * glcolor;
    outColor = vec4(color.rgb * color.a, 1.0);
#endif
}
