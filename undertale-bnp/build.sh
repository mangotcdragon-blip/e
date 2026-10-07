#!/usr/bin/env bash
# Rebuilds the UNDERTALE: Bits and Pieces touch APK.
#
# Inputs (not in this repo, they are the game's own files):
#   DATA_WIN   Steam v1.08 data.win
#   UBNP_DIR   extracted utbnp-v4.2.4 folder (ubnp.zip release asset)
#   TOUCH_APK  undertale-touch.apk (branch ccr-f0e23b74-wygqmt), used as the APK container
#   UTMT_CLI   UndertaleModCli binary built from UnderminersTeam/UndertaleModTool
# Needs: xdelta3, apktool, zipalign, apksigner, python3, javac; ANDROID_JAR (platforms/android-30/android.jar) and D8 (build-tools d8).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
work="${WORK:-$(mktemp -d)}"
: "${DATA_WIN:?}" "${UBNP_DIR:?}" "${TOUCH_APK:?}" "${UTMT_CLI:?}"

# 1. Apply the Bits and Pieces patch to the Steam data.win
xdelta3 -d -f -s "$DATA_WIN" "$UBNP_DIR/utbnp_v4.2.4.xdelta" "$work/ubnp.win"

# 2. Decompile it, generate the edited code entries, and add the touch controller
"$UTMT_CLI" dump "$work/ubnp.win" -c UMT_DUMP_ALL -o "$work/dump" </dev/null >/dev/null
mkdir -p "$work/port/gml" "$work/port/spr"
python3 -I "$here/tools/edits.py" "$work/dump/CodeEntries" "$work/port/gml"
cp "$here"/gml/*.gml "$work/port/gml/"
cp "$here"/sprites/*.png "$work/port/spr/"
PORT="$work/port" "$UTMT_CLI" load "$work/ubnp.win" -s "$here/tools/port.csx" -o "$work/game.droid" -f </dev/null

# 3. Repack the touch APK as a separate app with the modded data and music
apktool d -s -f -o "$work/apk" "$TOUCH_APK"
apktool d -f --no-res --no-assets -o "$work/smali" "$TOUCH_APK"
python3 -I "$here/tools/patch_apk_lookup.py" "$work/smali/smali/com/jockeholm/undertale/DemoRenderer.smali"
rm "$work/apk/classes.dex" && cp -r "$work/smali/smali" "$work/apk/smali"
python3 -I "$here/tools/hook_debuglog.py" "$work/apk/smali/com/jockeholm/undertale/RunnerActivity.smali"
# DebugLog: logcat + crash traces to Download/UndertaleBnP/ (needs ANDROID_JAR and D8)
mkdir -p "$work/java"
javac --release 8 -cp "$ANDROID_JAR" -d "$work/java" "$here/java/com/utmod/DebugLog.java"
"$D8" --min-api 21 --lib "$ANDROID_JAR" --output "$work/java" "$work"/java/com/utmod/*.class
cp "$work/java/classes.dex" "$work/apk/classes3.dex"
cp "$work/game.droid" "$work/apk/assets/game.droid"
cp "$UBNP_DIR"/*.ogg "$work/apk/assets/"
cp "$UBNP_DIR/Redone OST 'n More/mus_menu7.ogg" "$work/apk/assets/"
sed -i 's/<string name="app_name">UNDERTALE<\/string>/<string name="app_name">UNDERTALE BnP<\/string>/' "$work/apk/res/values/strings.xml"
sed -i 's/  renameManifestPackage: null/  renameManifestPackage: com.jockeholm.undertale.bnp/; s/  versionName: 2.0.0/  versionName: 2.0.0-bnp4.2.4/' "$work/apk/apktool.yml"
sed -i 's/^UseShaders=False/UseShaders=True/; s/^DisplayName="UNDERTALE"/DisplayName="UNDERTALE BnP"/' "$work/apk/assets/options.ini"
apktool b -o "$work/unsigned.apk" "$work/apk"
zipalign -p -f 4 "$work/unsigned.apk" "$work/aligned.apk"
apksigner sign --ks "$here/bnp.keystore" --ks-key-alias bnp --ks-pass pass:bnpmodkey --key-pass pass:bnpmodkey \
  --out "${OUT:-$work/undertale-bnp-touch.apk}" "$work/aligned.apk"
echo "built ${OUT:-$work/undertale-bnp-touch.apk}"
