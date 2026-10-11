// Bloom: vertical gaussian inside each tile.
#include "/lib/common.glsl"
#include "/lib/bloom.glsl"

uniform sampler2D colortex6;

in vec2 texcoord;

/* RENDERTARGETS: 6 */
layout(location = 0) out vec4 outBloom;

void main() {
    outBloom = vec4(0.0);
#ifdef BLOOM
    for (int i = 0; i < BLOOM_TILES; i++) {
        vec3 tile = bloomTile(i);
        vec2 local = (texcoord - tile.xy) / tile.z;
        if (any(lessThan(local, vec2(0.0))) || any(greaterThan(local, vec2(1.0)))) continue;

        float texel = 1.0 / viewHeight;
        float yMin = tile.y + 0.5 * texel;
        float yMax = tile.y + tile.z - 0.5 * texel;
        vec3 sum = vec3(0.0);
        for (int k = 0; k < 9; k++) {
            vec2 uv = vec2(texcoord.x, clamp(texcoord.y + float(k - 4) * texel, yMin, yMax));
            sum += textureLod(colortex6, uv, 0.0).rgb * bloomWeights[k];
        }
        outBloom = vec4(sum, 1.0);
        break;
    }
#endif
}
