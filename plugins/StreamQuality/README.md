# Stream Quality

Pick your own resolution and frame rate for screen sharing on Discord mobile, straight from Discord's own share sheet.

## How it works

Discord's mobile share sheet offers three fixed rows: Default (720p 30), Performance (480p 30) and High quality (1080p 60, Nitro only). This plugin takes over one of those rows (High quality by default) and turns it into **Custom quality** with the numbers you set in the plugin settings:

- The row's label and sub label show your values, for example `1080x2400 @ 90 fps`.
- The row is always selectable, with or without Nitro.
- Tapping it stores your values in Discord's stream settings, so Start, re-shares and the live quality indicator all use them.
- The Go Live request handed to the media engine is rewritten as a backstop, and optionally the encoder options too.

The other two rows keep working exactly as before, so you can switch between stock and custom quality from the sheet.

## Settings

- **Override screen share quality**: master switch.
- **Resolution**: 480p, 720p, 1080p, 1440p, 2160p or *Custom* (any value between 144 and 4320). The "p" number is the short side; the long side follows your panel's real pixel ratio, read through the device pixel ratio. On a 2400x1080 panel 1080p is 2400x1080, on a 2780x1264 panel it is 2376x1080. The settings page shows the detected panel size.
- **Short side first**: show and send sizes as 1080x2400 instead of 2400x1080.
- **Frame rate**: 15 to 120 fps presets or *Custom* (1 to 240).
- **Exact dimensions**: set width and height yourself instead of deriving them from the screen.
- **Share sheet row to replace**: High quality, Performance or Default.
- **Only when that row is selected**: on by default. Turn it off to force the custom quality for every share regardless of which row is picked.
- **Advanced**: encoder patch and debug logging.

Resolution and frame rate apply the next time you start a share. Changing the replaced row or the encoder switch needs a plugin reload.

## Caveats

- Discord's servers and the viewer's client still decide what they accept. Very high values may be downscaled on the receiving end or cost a lot of upload bandwidth and battery.
- The patched modules (`AudioActionCreators.setGoLiveSource`, `getStreamSettingsForPreset`, `canStreamWithSettings`, `MobileGoLiveActionSheet`) were taken from the current Discord Android bundle and may move in a future update. If the plugin shows a toast about a missing module after an update, turn on debug logging and open an issue with the console output.
