#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"

out vec2 texcoord;
out vec2 lmcoord;
out vec4 glcolor;
flat out vec3 lightCol;
flat out vec3 ambientCol;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord  = normalizeLightmap((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy);
    glcolor  = gl_Color;
    lightCol = directLightColor();
    ambientCol = analyticSkyAmbient();
    gl_Position = ftransform();
    gl_Position.xy += taaOffset() * 2.0 * gl_Position.w;
}
