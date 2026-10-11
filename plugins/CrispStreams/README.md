# Crisp Streams

Watch screen shares (Go Live) on Discord mobile at full quality instead of the blurry, blocky version Discord normally picks for a phone.

## Why streams look pixelated

When you watch a stream, Discord tells the voice server how big the video is on your screen, and the server sends a stream sized to fit. On mobile that size is measured in layout points, not real pixels, so a portrait tile of about 411x231 points asks for roughly 95,000 pixels, a bit over 360p. The streamer may be sending 1080p, but you get a downscaled copy, and it stays soft even in full screen.

## What the plugin does

- Reports a large viewport (4K by default) whenever Discord asks for a stream, so the server sends the full-resolution stream. If Discord would already ask for more (for example while you pinch-zoom), its own value is used.
- When a stream is sent in several qualities (simulcast), subscribes to the highest one instead of whichever is listed first.
- Settings changes apply to open streams right away. Turning the plugin off puts Discord's own request back.

## Settings

- **Full quality streams**: master switch.
- **Prefer the best layer**: watch the top simulcast layer. Applies the next time a stream's layers are announced, for example when you reopen it.
- **Request streams at**: 720p, 1080p, 1440p or 4K. Lower values save mobile data.
- **Debug logging**: log each rewritten request.

## Caveats

- This only removes the downscaling done for your client. It can't fix a stream the streamer sends at low resolution or low bitrate, or a slow connection on either side. For your own shares, see Stream Quality.
- Full-quality streams use more data and battery.
- The patched code (`GoLiveQualityManager.request`, `updateAudioAndVideoStreamInfo`, the `VIDEO_SIZE_UPDATE` action and `StreamRTCConnectionStore`) is from Discord's current client and may change in a future update.
