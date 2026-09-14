# WiFi Heatmap

An Android app for mapping WiFi download speed across your house.

1. Upload a 2D floor plan image.
2. Adjust the grid slider to control how fine the squares are (2×2 up to 20×20).
3. Walk to a room, tap the square you're standing in, and hit **Run Speed
   Test Here**.
4. The square fills in with a color from red (slowest measured) to green
   (fastest measured), so the map builds into a heatmap as you test more
   squares.

## How the speed test works

The app performs a real download speed test — it isn't simulated. It streams
data from [speed.cloudflare.com](https://speed.cloudflare.com)'s public
speed-test backend (the same endpoint that site's own browser page uses) and
measures actual throughput: bytes received over elapsed time, in Mbps. No API
key is required. A native app can call this endpoint directly (the CORS
restriction that blocks a plain webpage from doing the same doesn't apply to
native HTTP clients), so results reflect your device's real connection at
wherever you're standing.

Each test streams for up to ~12 seconds (or until ~100 MB has been
transferred, whichever comes first) and requires normal internet access —
`INTERNET` and `ACCESS_NETWORK_STATE` are the only permissions the app
requests. No location or storage permission is needed; the floor plan image
is picked via the system document/photo picker and copied into the app's
private storage.

## Project structure

- `app/src/main/java/com/wifiheatmap/app/network/SpeedTestClient.kt` — streams
  the download and computes Mbps.
- `app/src/main/java/com/wifiheatmap/app/data/` — grid/result state and JSON
  persistence (`HeatmapRepository`, so your map and test results survive an
  app restart).
- `app/src/main/java/com/wifiheatmap/app/ui/` — the Compose UI: upload
  screen, grid overlay + tap handling, speed test panel, and the
  `HeatmapViewModel` tying it together.

## Building

This is a standard Gradle/Android Studio project (Kotlin + Jetpack Compose,
minSdk 26).

```
./gradlew assembleDebug
```

or open the repo root in Android Studio (Koala or newer) and let it sync —
it will download the Android SDK components and Gradle distribution it
needs automatically. This environment where the project was generated has
no Android SDK and no network access to `dl.google.com`, so the build could
not be run or verified here; build and test it locally or in CI with normal
internet access.

To install and run on a device or emulator: `./gradlew installDebug`, then
launch "WiFi Heatmap" from the app drawer.
