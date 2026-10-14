#!/usr/bin/env bash
# Builds SoundTimer.apk without Gradle. Needs ANDROID_HOME with
# platforms;android-35 and build-tools;35.0.0 installed, plus a JDK.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:?set ANDROID_HOME}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"

rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen -0 mp3 \
  -o build/unsigned.apk build/res.zip
javac --release 8 -Xlint:-options -classpath "$JAR" -d build/classes \
  $(find src build/gen -name '*.java')
"$BT/d8" --min-api 21 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

KS=debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Sound Timer" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out SoundTimer.apk build/aligned.apk
"$BT/apksigner" verify SoundTimer.apk
echo "Built $(pwd)/SoundTimer.apk"
