// Bloom: downsample into tiles + horizontal gaussian.
#include "/lib/common.glsl"
#include "/lib/bloom.glsl"

uniform sampler2D colortex0;

const bool colortex0MipmapEnabled = true;

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

        float lod = float(i + 2);
        float texel = exp2(lod) / viewWidth;
        vec3 sum = vec3(0.0);
        float wsum = 0.0;
        for (int k = 0; k < 9; k++) {
            vec2 uv = clamp(local + vec2(float(k - 4) * texel, 0.0), vec2(0.0), vec2(1.0));
            vec3 c = textureLod(colortex0, uv, lod).rgb;
            // Karis weighting on the first level keeps fireflies from blooming
            float w = bloomWeights[k] * (i == 0 ? 1.0 / (1.0 + luminance(c)) : 1.0);
            sum += c * w;
            wsum += w;
        }
        outBloom = vec4(sum / wsum, 1.0);
        break;
    }
#endif
}
