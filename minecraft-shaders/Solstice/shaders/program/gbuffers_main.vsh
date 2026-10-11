/*
    Opaque geometry: terrain, entities, block entities, hand, particles, lines, beacon
    beams, emissive overlays and (optionally) vanilla clouds. Writes the G-buffer that
    deferred1 lights. The wrapper file defines one PROGRAM_* macro.
*/
#include "/lib/common.glsl"
#include "/lib/waving.glsl"

uniform int blockEntityId;

in vec4 mc_Entity;
in vec2 mc_midTexCoord;
in vec4 at_tangent;

out vec2 texcoord;
out vec2 lmcoord;
out vec4 glcolor;
out vec3 worldNormal;
out vec3 playerPos;
flat out int blockId;
#if PBR_MODE == 1
out vec3 worldTangent;
out vec3 worldBitangent;
#endif

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord  = normalizeLightmap((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy);
    glcolor  = gl_Color;
#ifdef PROGRAM_BLOCK
    blockId  = blockEntityId;
#else
    blockId  = int(mc_Entity.x + 0.5);
#endif

    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    playerPos = viewToPlayer(viewPos.xyz);

#ifdef PROGRAM_TERRAIN
    vec3 offset = wavingOffset(playerPos + cameraPosition, blockId, texcoord, mc_midTexCoord, lmcoord.y);
    if (dot(offset, offset) > 0.0) {
        playerPos += offset;
        viewPos.xyz = playerToView(playerPos);
    }
#endif

    // Particles and lines can come without a normal; face them towards the camera.
    vec3 viewNormal = gl_NormalMatrix * gl_Normal;
    viewNormal = dot(viewNormal, viewNormal) > 1e-6 ? normalize(viewNormal) : vec3(0.0, 0.0, 1.0);
    worldNormal = mat3(gbufferModelViewInverse) * viewNormal;
#if PBR_MODE == 1
    vec3 viewTangent = normalize(gl_NormalMatrix * at_tangent.xyz);
    worldTangent   = mat3(gbufferModelViewInverse) * viewTangent;
    worldBitangent = cross(worldNormal, worldTangent) * (at_tangent.w < 0.0 ? -1.0 : 1.0);
#endif

    gl_Position = gl_ProjectionMatrix * viewPos;
    gl_Position.xy += taaOffset() * 2.0 * gl_Position.w;
}
