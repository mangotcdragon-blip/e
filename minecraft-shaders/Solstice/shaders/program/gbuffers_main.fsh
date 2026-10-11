#include "/lib/common.glsl"
#include "/lib/noise.glsl"

uniform sampler2D gtexture;
#if PBR_MODE == 1
uniform sampler2D normals;
uniform sampler2D specular;
#endif
uniform vec4 entityColor;

in vec2 texcoord;
in vec2 lmcoord;
in vec4 glcolor;
in vec3 worldNormal;
in vec3 playerPos;
flat in int blockId;
#if PBR_MODE == 1
in vec3 worldTangent;
in vec3 worldBitangent;
#endif

#ifdef PROGRAM_OVERLAY
/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;
#else
/* RENDERTARGETS: 0,1,2 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outMaterial;
#endif

void main() {
#if defined PROGRAM_CLOUDS && defined VOLUMETRIC_CLOUDS && defined WORLD_OVERWORLD
    discard;
#endif

#ifdef PROGRAM_BASIC
    vec4 albedo = glcolor;
#elif defined PROGRAM_TERRAIN
    // separateAo: vertex colour alpha carries vanilla ambient occlusion
    vec4 albedo = texture(gtexture, texcoord) * vec4(glcolor.rgb, 1.0);
#else
    vec4 albedo = texture(gtexture, texcoord) * glcolor;
#endif

    if (albedo.a < 0.1) discard;

#ifdef PROGRAM_OVERLAY
    outColor = albedo;
#else

#ifdef PROGRAM_ENTITIES
    albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);
#endif

    float ao = 1.0;
#ifdef PROGRAM_TERRAIN
    ao = mix(1.0, glcolor.a, VANILLA_AO_STRENGTH);
#endif

    vec3 geoNormal = normalize(worldNormal);
    if (!gl_FrontFacing) geoNormal = -geoNormal;
    vec3 normal = geoNormal;

    int   material   = MAT_DEFAULT;
    float smoothness = 0.12;
    float f0         = 0.04;
    float emission   = 0.0;
    vec2  lightmap   = lmcoord;

#if defined PROGRAM_TERRAIN || defined PROGRAM_BLOCK
    if (blockId == ID_PLANT || blockId == ID_TALL_LOWER || blockId == ID_TALL_UPPER ||
        blockId == ID_LEAVES || blockId == ID_VINES || blockId == ID_FOLIAGE) {
        material = MAT_FOLIAGE;
        smoothness = 0.25;
        f0 = 0.03;
    } else if (blockId == ID_LIGHT) {
        float m = maxOf(albedo.rgb);
        emission = smoothstep(0.55, 0.92, m) * (0.5 + 0.5 * m);
    } else if (blockId == ID_LIGHT_SOFT) {
        emission = pow(maxOf(albedo.rgb), 3.0) * 0.6;
    } else if (blockId == ID_LAVA) {
        material = MAT_LAVA;
        emission = 1.0;
    } else if (blockId == ID_FIRE) {
        emission = 1.0;
    } else if (blockId == ID_METAL) {
        smoothness = 0.72;
        f0 = 1.0;
    } else if (blockId == ID_POLISHED) {
        smoothness = 0.62;
    } else if (blockId == ID_ICE) {
        smoothness = 0.9;
        f0 = 0.02;
    }
#elif defined PROGRAM_ENTITIES
    material = MAT_ENTITY;
    smoothness = 0.3;
#elif defined PROGRAM_HAND
    material = MAT_HAND;
    smoothness = 0.3;
#elif defined PROGRAM_TEXTURED || defined PROGRAM_CLOUDS || defined PROGRAM_BASIC
    material = MAT_PARTICLE;
    smoothness = 0.0;
#elif defined PROGRAM_BEACON || defined PROGRAM_SPIDEREYES
    emission = 1.0;
    lightmap = vec2(1.0);
#endif

#if PBR_MODE == 1 && (defined PROGRAM_TERRAIN || defined PROGRAM_BLOCK || defined PROGRAM_ENTITIES || defined PROGRAM_HAND)
    vec4 nmap = texture(normals, texcoord);
    if (nmap.x + nmap.y > 0.001) {
        vec3 tn = vec3(nmap.xy * 2.0 - 1.0, 0.0);
        tn.xy *= NORMAL_MAP_STRENGTH;
        tn.z = sqrt(saturate(1.0 - dot(tn.xy, tn.xy)));
        mat3 tbn = mat3(normalize(worldTangent), normalize(worldBitangent), geoNormal);
        normal = normalize(tbn * tn);
        ao *= nmap.b;
    }
    vec4 spec = texture(specular, texcoord);
    if (dot(spec, spec) > 0.0) {
        smoothness = spec.r;
        f0 = spec.g * 255.0 >= 229.5 ? 1.0 : spec.g;
        emission = max(emission, spec.a < 0.999 ? spec.a * 255.0 / 254.0 : 0.0);
        if (spec.b * 255.0 >= 64.5 && material == MAT_DEFAULT) material = MAT_FOLIAGE;
    }
#endif

#if defined RAIN_PUDDLES && defined WORLD_OVERWORLD && defined PROGRAM_TERRAIN
    if (wetness > 0.001 && material != MAT_LAVA) {
        float exposed = smoothstep(0.86, 0.96, lightmap.y);
        float wet = wetness * exposed;
        float puddle = 0.0;
        if (geoNormal.y > 0.9) {
            vec2 wp = (playerPos.xz + cameraPosition.xz);
            float n = noise2D(wp * 0.07) * 0.65 + noise2D(wp * 0.21) * 0.35;
            puddle = smoothstep(0.62 - 0.35 * PUDDLE_AMOUNT, 0.7 - 0.35 * PUDDLE_AMOUNT, n) * wet;
        }
        float porosity = (1.0 - smoothness) * 0.5;
        albedo.rgb *= 1.0 - wet * porosity * 0.45;
        smoothness = mix(smoothness, max(smoothness, 0.55), wet * 0.6);
        smoothness = mix(smoothness, 0.96, puddle);
        f0 = f0 > 0.9 ? f0 : mix(f0, 0.02, puddle);
        normal = normalize(mix(normal, geoNormal, puddle));
    }
#endif

    outColor    = vec4(albedo.rgb, 1.0);
#if defined PROGRAM_TEXTURED || defined PROGRAM_CLOUDS || defined PROGRAM_BEACON || defined PROGRAM_SPIDEREYES
    outColor.a  = albedo.a;
#endif
    outNormal   = vec4(pack2x8(octEncode(normal)), pack2x8(octEncode(geoNormal)), lightmap);
    outMaterial = vec4(pack2x8(vec2(float(material) / 255.0, ao)), smoothness, f0, emission);
#endif
}
