# Stream Quality

Pick your own resolution and frame rate for screen sharing on Discord mobile, including values the app does not offer in its quality picker.

## What it does

When you start sharing your screen, Discord builds a Go Live request with a `qualityOptions` object (`preset`, `resolution`, `frameRate`). This plugin rewrites that object with your numbers, and optionally also forces them at the encoder boundary (`Connection.setDesktopEncodingOptions`) so a quality preset or clamp further down cannot undo them.

## Settings

- **Override screen share quality**: master switch.
- **Resolution**: 480p, 720p, 1080p, 1440p, 2160p or *Custom* (type any short-side size between 144 and 4320 px). The long side follows your screen's aspect ratio, so a portrait phone gets a portrait stream.
- **Frame rate**: 15 to 120 fps presets or *Custom* (1 to 240).
- **Exact dimensions**: set width and height yourself instead of deriving them from the screen.
- **Advanced**: toggle the encoder patch, the custom-preset flag, the settings-store override and debug logging.

Resolution and frame rate take effect the next time you start a share. The advanced switches need a plugin reload.

## Caveats

- Discord's servers and the viewer's client still decide what they accept. Very high values may be downscaled on the receiving end or cost a lot of upload bandwidth and battery.
- The patched module names come from the current Discord mobile builds and may move. If the plugin shows a "Go Live module not found" toast after an update, turn on debug logging and open an issue with the console output.
