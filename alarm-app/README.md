# Math Alarm

A small daily alarm app for Android 8.1+.

- Plays `res/raw/alarm_sound.mp3` on repeat.
- Holds the media volume at 80% or higher while ringing (it is pushed back up if you lower it).
- Stops only when you solve a two-number addition problem. Back and volume-down do nothing on that screen.
- If it is still ringing after 5 minutes, it stops playing through Bluetooth and switches to the phone's built-in speaker at 100% alarm volume.
- Your previous volumes are restored once it is dismissed.

## Build

```
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. It is signed with the debug key so it can be sideloaded directly.

## First run

Open the app once and allow notifications, plus "full screen notifications" if Android asks; that is what lets the problem screen appear over the lock screen. Use "Test ring now" to try it out.
