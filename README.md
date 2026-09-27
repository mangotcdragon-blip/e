# Puzzle Vault

An Android puzzle game. The 6-digit vault code is split into six chapters: each
digit only unlocks after solving 3 puzzles, and the puzzles get harder with every
digit (18 puzzles per run). Punch the finished code into the vault's keypad, sit
through the unlock sequence, and the vault plays its contents (the OIIA spinning
cat video).

Progress is saved after every puzzle, so a run can be continued from the main menu.

## Puzzles

Each chapter draws 3 different puzzle types. Every puzzle is freshly generated per run:

| Puzzle | What you do | Gets harder by |
| --- | --- | --- |
| Island of Liars | Knights always tell the truth, knaves always lie: work out who is who | More islanders, trickier statements (if/or) |
| Beat the Machine | Nim: take stones from one row, whoever takes the last stone wins | More/bigger rows, machine stops making mistakes |
| The Two Jugs | Measure an exact amount using two unmarked jugs | Longer shortest solution |
| The Tower | Tower of Hanoi | 3 to 5 disks |
| Slide Lock | Sliding tile puzzle | Deeper scramble, 4x4 board |
| Number Grid | Mini sudoku with a unique solution | 4x4 to 6x6, fewer clues |
| Colour Lock | Mastermind-style code breaking | More colours, repeats, fewer guesses |
| Lights Out | Turn every light off | 4x4 to 5x5, more lights |
| Intercepted Message | Crack a shift cipher from one known word | Two-word messages, reversed text |
| Echo Pads | Repeat a sequence of flashes | Longer sequences |

Wrong answers on guessable puzzles (liars, colour lock, Nim) reset them with a new
puzzle, so they have to be solved rather than guessed.

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
