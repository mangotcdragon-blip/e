# Calculator

A native Android app (Kotlin + Jetpack Compose) that looks and works like an ordinary calculator, but doubles as a tag-based image/video browser for e621 and Rule34.

## Unlocking

Open the app, type `6969`, then press `=`. That swaps the calculator out for the browser instead of evaluating the expression. Any normal use of the calculator (including typing 6969 as part of a real calculation, e.g. `6969 + 1 =`) behaves like a regular calculator.

The app re-locks itself back to the calculator screen every time it leaves the foreground (Home button, app switcher, screen off), so reopening it always shows the calculator first. While unlocked, the window sets `FLAG_SECURE`, which blocks screenshots/screen recording and hides the content from the recent-apps thumbnail.

## Features

- Browse e621.net and Rule34.xxx (switch source in the filter sheet), both via their public JSON APIs — no login required.
- Tag search bar (supports each site's normal tag syntax, including exclusions like `-tag`).
- Filters: rating (safe/questionable/explicit), media type (images/videos), sort (e621 only: newest/score/random).
- 200 posts per page, with infinite scroll.
- Full-screen viewer: pinch-to-zoom images, looping video playback (Media3/ExoPlayer), swipe between posts, tag list, share and save-to-device.
- Dark mode by default, with a Settings screen to switch to light or follow-system.
- Images/GIFs via Coil (animated GIF support included), video via ExoPlayer.

## Building

This was written without access to the Android SDK, so it hasn't been compiled in this environment — open it in Android Studio (Koala or newer) and it will fetch the Android Gradle Plugin / SDK platform automatically, or build from the command line once the SDK is installed:

```
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Requirements: JDK 17, Android SDK platform 35, minSdk 24 (Android 7.0+).

## Project layout

- `calculator/` — the disguise: a pure state-machine calculator (`CalculatorEngine`) plus its Compose UI.
- `data/` — network clients for e621 and Rule34, the unified `Post` model, and DataStore-backed settings.
- `ui/browser/`, `ui/detail/` — the real app: search/filter grid and the full-screen post viewer.
- `AppNavigation.kt` / `MainActivity.kt` — wires the lock state, re-locking, and screen switching together.
