/*
    Translucent geometry (water, stained glass, ice, slime, portals, translucent held
    items). Water only writes its normal and tint; composite renders it. Everything else
    is lit forward and alpha blended into colortex3.
*/
#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"

in vec4 mc_Entity;

out vec2 texcoord;
out vec2 lmcoord;
out vec4 glcolor;
out vec3 worldNormal;
out vec3 playerPos;
flat out int blockId;
flat out vec3 lightCol;
flat out vec3 ambientCol;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord  = normalizeLightmap((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy);
    glcolor  = gl_Color;
    blockId  = int(mc_Entity.x + 0.5);

    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    playerPos = viewToPlayer(viewPos.xyz);
    vec3 viewNormal = gl_NormalMatrix * gl_Normal;
    viewNormal = dot(viewNormal, viewNormal) > 1e-6 ? normalize(viewNormal) : vec3(0.0, 0.0, 1.0);
    worldNormal = mat3(gbufferModelViewInverse) * viewNormal;

    lightCol = directLightColor();
    ambientCol = analyticSkyAmbient();

    gl_Position = gl_ProjectionMatrix * viewPos;
    gl_Position.xy += taaOffset() * 2.0 * gl_Position.w;
}
