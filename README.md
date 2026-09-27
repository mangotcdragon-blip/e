# Puzzle Vault

An Android puzzle game. Work through six puzzles, each of which unlocks one digit
of a freshly generated 6-digit vault code. Punch the code into the vault's keypad,
sit through the unlock sequence, and the vault plays its contents (the OIIA
spinning cat video).

## Puzzles

The six stages come in a random order, and their contents are randomised every run:

| Stage | What you do |
| --- | --- |
| Number Cruncher | Solve an arithmetic expression (order of operations matters) |
| Pattern Hunter | Give the next number in a sequence |
| Word Scramble | Unscramble a word (hints reveal letters) |
| Echo Pads | Watch 6 coloured flashes and repeat them |
| Lights Out | 4x4 board: turn every light off |
| Secret Message | Decode a Caesar-shifted word |

When the last puzzle is solved the full code is shown once. The vault (reachable from
the main menu too) only accepts the code from your most recent completed run.

## Getting the APK

Every push runs the **Build APK** GitHub Actions workflow. Open the run under the
repository's *Actions* tab and download the `PuzzleVault-apk` artifact, unzip it, copy
`app-release.apk` to your phone and install it (allow installs from unknown sources).
The APK is signed with the Android debug key, so it installs without any extra setup.

## Building locally

Requires JDK 17 and the Android SDK (API 34):

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

Minimum Android version: 5.0 (API 21).
