/*
    Vanilla sky geometry. The sky itself is rendered in deferred1; here only the stars
    (skybasic) and the moon (skytextured) are kept so their colour can be added on top.
*/
#include "/lib/common.glsl"

out vec2 texcoord;
out vec4 glcolor;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    glcolor = gl_Color;
    gl_Position = ftransform();
    gl_Position.xy += taaOffset() * 2.0 * gl_Position.w;
}
