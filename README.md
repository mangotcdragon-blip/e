# ColorLab

An Android app that opens any image or video, displays or plays it, and lets you
tweak its look with live colour controls. Every slider applies instantly, even
while a video is playing.

## Features

**Open media**

- Pick from the system photo picker (photos and videos) or browse any file.
- Appears in "Open with" and the share sheet for images and videos.

**Light**: Exposure (stops), Brightness, Contrast, Fade (lifted blacks).

**Color**: Saturation, Hue rotation, Temperature, Tint, individual Red / Green / Blue
channel gains.

**Effects**: Sepia, Vignette, Blur (Android 12+), Monochrome, Invert.

**Presets**: Original, Vivid, Warm, Cool, Faded, Retro, Noir, Sepia, Dramatic,
Neon, Dreamy, Negative. Pick one, then fine-tune.

**Video playback**: play/pause, scrub bar, 5 second skip, loop, mute, speed
from 0.25x to 2x.

**Fullscreen / cinema mode**: tap the fullscreen button to hide everything but
the media. The system bars disappear, the screen stays awake, and the phone
rotates with the sensor even if auto-rotate is switched off, so you can turn it
sideways and watch like a movie. Tap the media to show or hide the controls;
press back or the exit button to leave.

**Preview tools**: pinch to zoom, drag to pan, double tap to reset, and a
"Hold to compare" button that shows the untouched original while pressed.

**Save**: edited images are written as JPEG to `Pictures/ColorLab`. Video export
is not included; the video effects are for live viewing only.

## How it works

All colour settings compile down to a single 4x5 colour matrix
(`color/ColorMath.kt`, `color/ColorAdjustments.kt`).

- Images are drawn with a Compose `ColorFilter.colorMatrix`.
- Video is decoded by Media3 ExoPlayer into a `TextureView`. A `TextureView` is
  always composited as a hardware layer, and the layer's `Paint` can carry a
  `ColorMatrixColorFilter`, so the same matrix recolours every frame on the GPU
  with no custom shaders.
- Vignette is a radial gradient overlay; blur uses Compose's `Modifier.blur`,
  which needs Android 12 (`RenderEffect`).

## Building

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35.0.0.
Point `local.properties` at your SDK (`sdk.dir=/path/to/sdk`) or set
`ANDROID_HOME`, then:

```bash
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install` or open the project in Android Studio and press Run.

Unit tests for the colour math:

```bash
./gradlew :app:testDebugUnitTest
```

Minimum Android version: 7.0 (API 24). Target: Android 15 (API 35).
