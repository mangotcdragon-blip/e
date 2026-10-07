# UNDERTALE: Bits and Pieces, touch port

Ports the touch controls from `undertale-touch.apk` (branch `ccr-f0e23b74-wygqmt`) onto
UNDERTALE: Bits and Pieces v4.2.4, and packs it as a separate Android app
(`com.jockeholm.undertale.bnp`, shown as "UNDERTALE BnP"), so it installs next to the existing one.

No game data is stored here. `build.sh` needs your own Steam `data.win` and the mod files.

## What changes in the mod's code

- Removes the mod's Android kill switches: 34 `if (os_type == os_android) game_end();` blocks
  spread across `obj_time`, `obj_mainchara`, `obj_battlecontroller`, `SCR_GAMESTART` and boss fights.
- `obj_time` Create: sets up the touch globals and spawns `obj_mobilecontroller` on Android/iOS.
- NekoPresence (Windows Discord DLL): init/cleanup hooks cleared so they don't run on Android.
- `com.utmod.DebugLog` (`java/`): writes the app's logcat and crash traces to Download/UndertaleBnP/.
- `DemoRenderer`: finds its own APK via getPackageName() instead of the hardcoded original package.
- `obj_time` Begin Step: keeps `global.aspectratio` current for the controller.
- `obj_time` Pre-Draw: fractional scaling on phones (the mod's integer scaling would leave the
  game at 1x on 720p screens).
- `obj_mobilecontroller` (new): the touch controller from the touch APK. It works by sending key
  presses, so Android runs the mod's normal PC keyboard path (`osflavor` 2).
- `scr_namingscreen` / `obj_intromenu`: Export save / Import save in the title menus, using the
  APK's existing Java helper (`com.utmod.SaveBridge`), adapted to the PC-style `file0` save file.
- `*_fmns` scripts: return 0 on Android instead of falling through.
- `obj_mirrorbg` Draw: skips its shaders if the device can't compile them.

`bnp.keystore` (alias `bnp`, password `bnpmodkey`) signs the APK. Keep using it so updates
install over the previous build.
