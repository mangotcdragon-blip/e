# Custom Autocorrect Keyboard (Android)

A system-wide Android keyboard (IME) that autocorrects your own custom words —
anywhere you type, not just in one app.

- Corrects a word as soon as you press Space/Enter, or after ~3s of no typing.
- Dictionary is managed from the app's main screen (add/remove words).
- Import/export your dictionary as JSON (`[{"from": "...", "to": "..."}]` or
  a plain `{"word": "replacement"}` object — same format the companion web
  app in this repo uses).

## Build

Open the `android/` folder in Android Studio (Hedgehog or newer) and let it
sync — it will fetch its own Gradle wrapper automatically. Then Run on a
device/emulator, or Build > Build Bundle(s)/APK(s) > Build APK(s).

From the command line, with your own Gradle installed and `ANDROID_HOME` set:

```
cd android
gradle wrapper          # generates gradlew (one-time, needs network)
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install & enable

1. Install the APK on your device (`adb install app-debug.apk`, or copy it
   over and open it).
2. Launch **Custom Autocorrect**, tap **Enable Keyboard**, and turn on
   "Custom Autocorrect Keyboard" in Android's keyboard list.
3. In any text field, tap **Switch Keyboard** (or long-press the keyboard's
   globe/switch key) and pick "Custom Autocorrect Keyboard".
4. Add words in the app; they take effect immediately, even if the keyboard
   is already open.
