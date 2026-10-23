# Model Studio (Android)

A simple Android app for looking at, restyling, animating and converting 3D models, with first-class
support for Minecraft models.

## Getting the app

Every push that touches `apps/model-studio/` runs the **Model Studio Android app** workflow. Builds of
`main` are published to the [`model-studio-latest` release](../../releases/tag/model-studio-latest)
as `ModelStudio-<version>-release.apk`; builds of other branches are attached to the workflow run as
an artifact. Download the APK on your phone and open it (Android will ask you to allow installing
from your browser or file manager the first time). Android 8.0 or newer is needed.

## What it does

The app has seven tabs along the bottom. Tap a tab to open its panel; tap it again (or press Back) to close it.

| Tab | |
| --- | --- |
| **Open** | Open a model, convert a file, add an animation, or try the built-in sample robot. Shows facts about the model and anything that could not be found (missing textures or parent models). |
| **Look** | Change materials: colour, texture (replace or remove), **glow** (presets, strength, colour, or glowing in the texture's own colours), metal, shine, see-through, two-sided, faceted, wireframe. Tap the model to pick the material. "Show textures" switches every texture off and on. |
| **Light** | The studio light is **off by default**, so the model is evenly lit in its true colours. Switch it on to get a movable light with shadows: turn it around the model, raise or lower it, change brightness and colour, add fill light. Also reflections and how strong the glow effect is. |
| **Parts** | Tap a part of the model to select it. Drag the on-model handles to move, rotate or scale it (switch between Move / Rotate / Scale; the centre cube scales evenly), or open "Exact values" for sliders. Give just that part its own glow or colour, or hide it. Edits are kept in every export. |
| **Animate** | Play animations, scrub the timeline, step frame by frame, change speed. Make your own: tap a part and drag its Move / Rotate / Scale handles (or use the sliders); each change is saved as a keyframe at the current time. |
| **Save** | Export and convert (see below). On Android, files go to `Downloads/Model Studio`. |
| **View** | Camera presets, fit to view, screenshot, slow spin, floor grid, wireframe, Minecraft face shading, background colour. |

### Opening files

Tip: select the model **together with** its textures, `.mtl`, `.bin` and parent models, or open a
whole `.zip` (a resource pack works). Files can also be opened from other apps with *Open with* or *Share*.

* 3D models: GLB, glTF (+ .bin and textures, Draco and Meshopt compression), OBJ (+ MTL), FBX, STL, PLY, DAE, 3MF, 3DS, MagicaVoxel VOX
* Minecraft Java block and item models (`.json`), including `parent` chains, `item/generated` sprites, element rotations, tints and animated (`.mcmeta`) textures. Common vanilla parents (`cube_all`, `cube_column`, `orientable`, `cross`, `slab`, `stairs`, ...) are built in, so most block models open without the game's files.
* Minecraft Bedrock entity geometry (`.geo.json`)
* Blockbench projects (`.bbmodel`) with their embedded textures, groups and animations
* Animations: `.msanim`, Minecraft `.animation.json` (Bedrock / GeckoLib, with Molang), and animations inside GLB, FBX, DAE and `.bbmodel`

### Saving and converting

| Format | |
| --- | --- |
| GLB / glTF | Keeps textures, glow (emissive strength) and animations |
| OBJ | `.zip` with `.obj`, `.mtl` and textures |
| STL, PLY | For 3D printing / point tools |
| USDZ | Apple AR |
| Minecraft Java | Minecraft models are saved with their parents flattened into one file; anything else is converted into Minecraft cubes (voxels) with a generated palette texture, at low/normal/high detail. Glowing materials get `light_emission`. Saved as a resource-pack style `.zip`. |
| Minecraft Bedrock | `.geo.json` + texture, for Bedrock and Blockbench models |
| Picture | `.png` screenshot |
| `.msanim` | All animations in this app's own format |
| Minecraft animation | `.animation.json` for Bedrock add-ons, GeckoLib (Java mods) and Blockbench |
| GLB with animation | Model and animations together |

"Convert a file" on the Open tab is the quick path: pick a file, then pick the format to save it as.

## The `.msanim` animation format

See [docs/msanim.md](docs/msanim.md).

## How it is built

* `web/` is the app itself: plain JavaScript on [three.js](https://threejs.org), bundled with esbuild.
* `android/` is a small native shell: a full-screen WebView that loads the bundled app from the APK and
  adds the system file picker, saving to Downloads, sharing and "Open with".

Build locally (Node 18+, JDK 17+, Android SDK with platform 35):

```
cd apps/model-studio/web
npm ci
npm run build          # writes web/dist
cd ../android
./gradlew assembleRelease
# -> app/build/outputs/apk/release/ModelStudio-1.2.0-release.apk
```

`npm run dev` in `web/` serves the app at http://localhost:5173 for trying it in a desktop browser
(drag files onto the page to open them).

### Signing

Without configuration the APK is signed with a debug key. That installs fine, but each CI run makes a
new debug key, so a newer build only installs over an older one if you uninstall first. To keep one
key, create a keystore and add these repository secrets; the workflow then signs with it:

* `MS_KEYSTORE_BASE64`: the keystore file, base64-encoded
* `MS_KEYSTORE_PASSWORD`, `MS_KEY_ALIAS`, `MS_KEY_PASSWORD`

Locally, set `MS_KEYSTORE` (a path) and the same password/alias variables before running Gradle.
