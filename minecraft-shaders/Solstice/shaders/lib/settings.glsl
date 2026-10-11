#ifndef SETTINGS_GLSL
#define SETTINGS_GLSL

/*
    Solstice Shaders - user settings.
    Every option here is exposed in the in-game shader options menu
    (see shaders.properties for the menu layout and lang/en_US.lang for labels).
*/

// ---------------------------------------------------------------- engine constants
const int   shadowMapResolution     = 2048;  // [1024 1536 2048 3072 4096 6144 8192]
const float shadowDistance          = 160.0; // [64.0 96.0 128.0 160.0 192.0 224.0 256.0 320.0 384.0]
const float shadowDistanceRenderMul = 1.0;   // [-1.0 1.0]
const float shadowIntervalSize      = 2.0;
const bool  shadowHardwareFiltering = false;
const bool  shadowtex0Nearest       = true;
const bool  shadowtex1Nearest       = true;
const bool  shadowcolor0Nearest     = true;
const float sunPathRotation         = -30.0; // [-60.0 -50.0 -40.0 -30.0 -20.0 -10.0 0.0 10.0 20.0 30.0 40.0 50.0 60.0]
const float ambientOcclusionLevel   = 1.0;
const int   noiseTextureResolution  = 256;
const float wetnessHalflife         = 400.0;
const float drynessHalflife         = 200.0;
const float eyeBrightnessHalflife   = 8.0;

// ---------------------------------------------------------------- shadows
#define SHADOW_SAMPLES 16          // [8 12 16 24 32 48]
#define SHADOW_SOFTNESS 1.0        // [0.0 0.25 0.5 0.75 1.0 1.25 1.5 2.0 3.0]
#define SHADOW_DISTORTION 0.85     // [0.75 0.8 0.85 0.9 0.95]
#define COLORED_SHADOWS
#define SUBSURFACE_SCATTERING
#define SSS_STRENGTH 1.0           // [0.0 0.25 0.5 0.75 1.0 1.25 1.5 2.0]
#define CLOUD_SHADOWS

// ---------------------------------------------------------------- lighting
#define SUN_BRIGHTNESS 1.0         // [0.5 0.6 0.7 0.8 0.9 1.0 1.1 1.2 1.3 1.4 1.5 1.75 2.0]
#define MOON_BRIGHTNESS 1.0        // [0.25 0.5 0.75 1.0 1.25 1.5 2.0 3.0]
#define AMBIENT_STRENGTH 1.0       // [0.25 0.5 0.75 1.0 1.25 1.5 1.75 2.0]
#define BLOCKLIGHT_STRENGTH 1.0    // [0.25 0.5 0.75 1.0 1.25 1.5 1.75 2.0 2.5 3.0]
#define BLOCKLIGHT_R 1.00          // [0.00 0.05 0.10 0.15 0.20 0.25 0.30 0.35 0.40 0.45 0.50 0.55 0.60 0.65 0.70 0.75 0.80 0.85 0.90 0.95 1.00]
#define BLOCKLIGHT_G 0.65          // [0.00 0.05 0.10 0.15 0.20 0.25 0.30 0.35 0.40 0.45 0.50 0.55 0.60 0.65 0.70 0.75 0.80 0.85 0.90 0.95 1.00]
#define BLOCKLIGHT_B 0.40          // [0.00 0.05 0.10 0.15 0.20 0.25 0.30 0.35 0.40 0.45 0.50 0.55 0.60 0.65 0.70 0.75 0.80 0.85 0.90 0.95 1.00]
#define EMISSIVE_STRENGTH 4.0      // [1.0 2.0 3.0 4.0 5.0 6.0 8.0 10.0]
#define MIN_LIGHT 0.006            // [0.0 0.002 0.004 0.006 0.01 0.015 0.02 0.03 0.05]
#define VANILLA_AO_STRENGTH 0.8    // [0.0 0.2 0.4 0.6 0.8 1.0]
#define HANDHELD_LIGHT

#define SSAO
#define SSAO_SAMPLES 8             // [4 6 8 12 16]
#define SSAO_RADIUS 0.8            // [0.4 0.6 0.8 1.0 1.25 1.5 2.0]
#define SSAO_STRENGTH 1.0          // [0.25 0.5 0.75 1.0 1.25 1.5 2.0]

// ---------------------------------------------------------------- materials
#define PBR_MODE 0                 // [0 1]
#define NORMAL_MAP_STRENGTH 1.0    // [0.0 0.25 0.5 0.75 1.0 1.25 1.5 2.0]
#define RAIN_PUDDLES
#define PUDDLE_AMOUNT 0.5          // [0.0 0.25 0.5 0.75 1.0]

// ---------------------------------------------------------------- sky & atmosphere
#define SKY_BRIGHTNESS 1.0         // [0.5 0.75 1.0 1.25 1.5 2.0]
#define SUN_DISC_SIZE 1.0          // [0.5 0.75 1.0 1.5 2.0 3.0]
#define STARS
#define STAR_AMOUNT 1.0            // [0.5 1.0 1.5 2.0]
#define VOLUMETRIC_CLOUDS
#define CLOUD_STEPS 20             // [8 12 16 20 24 32 48]
#define CLOUD_LIGHT_STEPS 4        // [2 3 4 6 8]
#define CLOUD_COVERAGE 0.5         // [0.1 0.2 0.3 0.4 0.5 0.6 0.7 0.8 0.9]
#define CLOUD_ALTITUDE 320.0       // [192.0 256.0 320.0 384.0 448.0 512.0 640.0]
#define CLOUD_THICKNESS 240.0      // [96.0 128.0 160.0 200.0 240.0 300.0 400.0]
#define CLOUD_DENSITY 1.0          // [0.25 0.5 0.75 1.0 1.5 2.0 3.0]
#define CLOUD_SPEED 1.0            // [0.0 0.25 0.5 1.0 2.0 4.0 8.0]

#define FOG_DENSITY 1.0            // [0.0 0.25 0.5 0.75 1.0 1.5 2.0 3.0 4.0]
#define BORDER_FOG
#define VOLUMETRIC_LIGHT
#define VL_STEPS 10                // [6 8 10 12 16 24 32]
#define VL_STRENGTH 1.0            // [0.25 0.5 0.75 1.0 1.5 2.0 3.0]

// ---------------------------------------------------------------- water
#define WATER_WAVES
#define WAVE_HEIGHT 1.0            // [0.25 0.5 0.75 1.0 1.25 1.5 2.0 3.0]
#define WAVE_SPEED 1.0             // [0.25 0.5 0.75 1.0 1.5 2.0 3.0]
#define WATER_REFRACTION
#define REFRACTION_STRENGTH 1.0    // [0.25 0.5 0.75 1.0 1.5 2.0]
#define WATER_CAUSTICS
#define WATER_FOG_DENSITY 1.0      // [0.25 0.5 0.75 1.0 1.5 2.0 3.0]
#define WATER_BIOME_TINT 0.5       // [0.0 0.25 0.5 0.75 1.0]

#define SSR
#define SSR_STEPS 24               // [12 16 24 32 48 64]
#define SSR_REFINE_STEPS 5         // [0 3 5 8]

// ---------------------------------------------------------------- waving
#define WAVING_PLANTS
#define WAVING_LEAVES
#define WAVING_STRENGTH 1.0        // [0.25 0.5 0.75 1.0 1.5 2.0 3.0]

// ---------------------------------------------------------------- post processing
#define TAA
#define TAA_BLEND 0.9              // [0.8 0.85 0.9 0.92 0.94 0.96]
#define SHARPENING 0.4             // [0.0 0.1 0.2 0.3 0.4 0.5 0.6 0.8 1.0]
#define BLOOM
#define BLOOM_STRENGTH 0.08        // [0.02 0.04 0.06 0.08 0.1 0.12 0.15 0.2 0.3]
#define AUTO_EXPOSURE
#define EXPOSURE 1.0               // [0.25 0.35 0.5 0.7 0.85 1.0 1.2 1.4 1.7 2.0 2.5 3.0 4.0]
#define EXPOSURE_SPEED 1.0         // [0.25 0.5 1.0 2.0 4.0]
#define TONEMAP 1                  // [0 1 2]
#define SATURATION 1.05            // [0.5 0.6 0.7 0.8 0.9 0.95 1.0 1.05 1.1 1.15 1.2 1.3 1.5]
#define CONTRAST 1.0               // [0.8 0.85 0.9 0.95 1.0 1.05 1.1 1.15 1.2]
#define VIGNETTE 0.25              // [0.0 0.1 0.2 0.25 0.3 0.4 0.5 0.7]

#endif
