# Data Tracker

A dark-mode-only Android app that tracks your mobile data usage against a monthly allowance
you set, resets on a day/time you choose, and rolls over unused data from the previous cycle.

## How it works

- Actual usage numbers come from Android's `NetworkStatsManager` — the same source the
  system's own Settings → Data Usage screen uses, so it matches what you already see there.
- Your billing cycle boundary (day of month + time) is configurable. Usage totals for the
  current and previous cycle are computed on demand from that setting and the current time —
  nothing about cycle boundaries is stored separately, so there's no state that can drift out
  of sync with the clock.
- If "roll over unused data" is on, whatever was left unused at the end of the *previous*
  cycle is added to this cycle's total (only the immediately preceding cycle rolls over, not
  an indefinite accumulation).
- An ongoing (non-dismissible) notification shows remaining data, refreshed roughly every
  15 minutes via WorkManager — that's the fastest background update interval Android allows
  without a battery-draining foreground service.

## Required permission: Usage Access

Android does not allow apps to request access to `NetworkStatsManager` via a normal runtime
permission popup. On first launch, if you see the "Usage access needed" banner, tap
**Grant usage access** — it opens Settings, where you need to find **Data Tracker** and turn
the toggle on. Without this, the app can't read real usage numbers.

## Build

Open `data-usage-tracker/` in Android Studio and let it sync (it fetches its own Gradle
wrapper automatically), then Run or Build APK(s).

From the command line, with your own Gradle installed and `ANDROID_HOME` set:

```
cd data-usage-tracker
gradle wrapper          # generates gradlew (one-time, needs network)
./gradlew assembleDebug
```

For a signed release build, set `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` env vars pointing at your own keystore before
running `./gradlew assembleRelease`.

## Project layout

- `CycleCalculator.kt` — pure date math for billing-cycle boundaries (unit-tested directly,
  no Android dependency).
- `DataUsageRepository.kt` — queries `NetworkStatsManager` and computes remaining/rollover.
- `UsageUpdateWorker.kt` / `UpdateScheduler.kt` — periodic background refresh via WorkManager.
- `NotificationHelper.kt` — builds the ongoing notification.
- `MainActivity.kt` / `SettingsActivity.kt` — UI.
