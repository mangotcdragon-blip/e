# Applying to your Kettu fork

This wasn't committed into C0C0B01/Kettu directly — that's the upstream
author's repo and this session only has read access to it. Drop these files
into your own Kettu clone/fork instead:

1. Copy `src/core/plugins/decoyguard/` (the whole folder, from this package)
   into `<your-kettu-clone>/src/core/plugins/decoyguard/`.
2. In `<your-kettu-clone>/src/core/plugins/index.ts`, add one line inside
   `getCorePlugins()`:
   ```ts
   export const getCorePlugins = (): Record<string, CorePlugin> => ({
       "bunny.quickinstall": require("./quickinstall"),
       "bunny.badges": require("./badges"),
       "bunny.notrack": require("./notrack"),
       "bunny.messagefix": require("./messagefix"),
       "kettu.decoyguard": require("./decoyguard"), // <-- add this
   });
   ```
3. `bun i && bun run build` (or `bun run serve` for live dev, per Kettu's own
   README), load it on your device, then follow
   `src/core/plugins/decoyguard/README.md` for setup - configure everything
   there BEFORE enabling the plugin.

Verified: `bun run build` succeeds and `tsc --noEmit` reports zero errors
for these files against Kettu's current `main` (built/typechecked inside
this session against a fresh clone of C0C0B01/Kettu). The Discord-internal
store/action names it relies on are standard across the whole
Vendetta/Bunny plugin ecosystem but weren't verified against a live
decompiled build of the app - test it yourself per the README's "Test this
yourself" section before relying on it.
