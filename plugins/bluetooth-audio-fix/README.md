# BluetoothAudioFix

A Kettu plugin (it also works on other Vendetta-compatible mods) for Discord on Android.

**The problem:** you turn off call audio so your music keeps full quality and your mic
uses the phone. Discord then sends *all* audio, including other apps' media, to the phone
speaker, even though your Bluetooth earbuds are still connected.

**What the plugin does:**

- Stops Discord from putting the phone into call mode, in three parts, each with its own
  switch:
  - **Call mode:** Android's call (communication) audio mode stays on normal.
  - **Bluetooth call audio (SCO):** the earbuds stay on the media profile, so audio keeps full
    quality and your mic stays on the phone.
  - **System call integration:** Discord can't register voice chat as a phone call.
- Blocks Discord's calls that force the speakerphone on. With call audio off, Android
  then plays audio on the connected Bluetooth device again.
- If Discord selects the speaker as its output device, the plugin selects your Bluetooth
  device instead.
- Adds a "Send audio back to Bluetooth now" button for when audio is already stuck on the
  speaker.
- Adds a diagnostics log you can copy, listing which Discord audio functions it found and
  what they were called with.

## Install

In Kettu, go to Settings > Plugins > + and paste:

```
https://raw.githubusercontent.com/mangotcdragon-blip/e/claude/discord-bluetooth-audio-routing-37yn8i/plugins/bluetooth-audio-fix/
```

The repository has to be public for Kettu to download the plugin.

## If it doesn't work

Discord renames its internal audio functions between versions, and newer builds hide the
list of native modules. So the plugin finds Discord's audio code in three ways:

- by the source file path of each Discord module (Kettu records these)
- by logging which native modules Discord asks for after the plugin loads
- by probing likely module and function names

If it still doesn't find the right code:

1. Open the plugin settings and turn on "Log audio events".
2. Join a voice channel with call audio off so the problem happens.
3. If "Patched targets" is still empty, tap "Deep scan".
4. Tap "Copy diagnostics" and share the text. It shows which functions the plugin found
   (or that it found none), and every function on Discord's audio modules, so the name
   patterns can be updated.

To use the speaker on purpose, turn off "Block forced speaker", or turn on
"Only while Bluetooth is detected".
