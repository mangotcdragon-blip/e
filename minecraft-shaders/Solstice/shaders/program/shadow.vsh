/*
    Shadow map pass. Geometry is distorted towards the player (more resolution up close)
    and plants/leaves wave exactly like in the main view so shadows stay attached.
*/
#include "/lib/common.glsl"
#include "/lib/waving.glsl"
#include "/lib/shadows.glsl"

in vec4 mc_Entity;
in vec2 mc_midTexCoord;

out vec2 texcoord;
out vec4 glcolor;
flat out int blockId;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    glcolor  = gl_Color;
    blockId  = int(mc_Entity.x + 0.5);

#ifndef WORLD_OVERWORLD
    gl_Position = vec4(-10.0, -10.0, -10.0, 1.0);
    return;
#endif

    vec4 shadowViewPos = gl_ModelViewMatrix * gl_Vertex;
    vec3 playerPos = (shadowModelViewInverse * shadowViewPos).xyz;

    float skyLight = normalizeLightmap((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy).y;
    vec3 offset = wavingOffset(playerPos + cameraPosition, blockId, texcoord, mc_midTexCoord, skyLight);
    if (dot(offset, offset) > 0.0) {
        shadowViewPos = shadowModelView * vec4(playerPos + offset, 1.0);
    }

    gl_Position = gl_ProjectionMatrix * shadowViewPos;
    gl_Position.xyz = distortShadowClip(gl_Position.xyz);
}
