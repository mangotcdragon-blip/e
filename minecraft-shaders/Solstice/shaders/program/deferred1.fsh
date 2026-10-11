/*
    Deferred lighting for all opaque geometry, plus the sky (atmosphere, sun, moon,
    stars and full resolution volumetric clouds).
*/
#include "/lib/common.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/lighting.glsl"
#include "/lib/skymap.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D depthtex0;

in vec2 texcoord;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

#ifdef SSAO
float computeSSAO(vec3 viewPos, vec3 viewNormal, float dither) {
    vec3 up = abs(viewNormal.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 t = normalize(cross(up, viewNormal));
    vec3 b = cross(viewNormal, t);
    mat3 tbn = mat3(t, b, viewNormal);

    float dither2 = ign(gl_FragCoord.xy + vec2(47.0, 113.0) + float(frameCounter & 63) * 7.31);
    vec2 texSize = vec2(viewWidth, viewHeight);
    float radius = SSAO_RADIUS;
    float occlusion = 0.0;

    for (int i = 0; i < SSAO_SAMPLES; i++) {
        float u = fract((float(i) + dither) / float(SSAO_SAMPLES));
        float phi = float(i) * 2.39996323 + dither2 * TAU;
        float r = sqrt(u);
        vec3 h = vec3(r * cos(phi), r * sin(phi), sqrt(1.0 - u));
        float scale = mix(0.15, 1.0, sqr(fract(float(i) * 0.618034 + dither2)));
        vec3 samplePos = viewPos + tbn * h * radius * scale;

        vec3 sp = viewToScreen(samplePos);
        if (any(lessThan(sp.xy, vec2(0.0))) || any(greaterThan(sp.xy, vec2(1.0)))) continue;
        float sd = texelFetch(depthtex0, ivec2(sp.xy * texSize), 0).r;
        if (sd < 0.56) continue;
        float sceneZ = screenToView(vec3(sp.xy, sd)).z;
        float diff = sceneZ - samplePos.z;
        if (diff > 0.02) occlusion += saturate(radius * 1.5 / abs(viewPos.z - sceneZ));
    }
    float ao = 1.0 - occlusion / float(SSAO_SAMPLES);
    return pow(saturate(ao), SSAO_STRENGTH * 1.5);
}
#endif

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(depthtex0, px, 0).r;
    vec4 c0 = texelFetch(colortex0, px, 0);

    vec3 viewPos = screenToView(vec3(texcoord, depth));
    vec3 playerPos = viewToPlayer(viewPos);
    vec3 dir = normalize(playerPos);
    float dither = ignTemporal(gl_FragCoord.xy);

    // ------------------------------------------------------------ sky
    if (depth >= 1.0) {
        vec3 sky = sampleSky(dir);
    #if defined WORLD_OVERWORLD
        vec3 celestial = srgbToLinear(c0.rgb) * 0.8 * atmosphereTransmittance(dir.y) * (1.0 - rainStrength);
        sky += celestial;
        sky += sunDisc(dir, sunDirWorld());
        #ifdef VOLUMETRIC_CLOUDS
        vec4 clouds = renderClouds(dir, dither, CLOUD_STEPS, lightDirWorld(), skyLightColor(), skyAmbientColor());
        sky = sky * clouds.a + clouds.rgb;
        #endif
    #elif defined WORLD_END
        sky += srgbToLinear(c0.rgb) * 0.15;
        #ifdef STARS
        sky += proceduralStars(dir) * 0.6;
        #endif
    #endif
        outColor = vec4(sky, 1.0);
        return;
    }

    // ------------------------------------------------------------ surfaces
    vec4 c1 = texelFetch(colortex1, px, 0);
    vec4 c2 = texelFetch(colortex2, px, 0);
    vec2 matAo = unpack2x8(c2.x);

    Surface s;
    s.albedo     = srgbToLinear(c0.rgb);
    s.normal     = octDecode(unpack2x8(c1.x));
    s.geoNormal  = octDecode(unpack2x8(c1.y));
    s.lightmap   = c1.zw;
    s.material   = int(matAo.x * 255.0 + 0.5);
    s.ao         = matAo.y;
    s.smoothness = c2.y;
    s.f0         = c2.z;
    s.emission   = c2.w;

#ifdef SSAO
    if (s.material != MAT_HAND && s.material != MAT_PARTICLE) {
        vec3 viewNormal = mat3(gbufferModelView) * s.normal;
        s.ao *= computeSSAO(viewPos, viewNormal, dither);
    }
#endif

    vec3 lightCol   = skyLightColor();
    vec3 ambientCol = skyAmbientColor();
    vec3 color = shadeSurface(s, playerPos, dither, lightCol, ambientCol);

    outColor = vec4(color, 1.0);
}
