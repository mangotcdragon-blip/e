# Apex Plugins (rebuilt fork)

A fork of [ApexTeamPL/Apex-Plugins](https://github.com/ApexTeamPL/Apex-Plugins), a set of Vendetta-API plugins for Revenge, Kettu and other Bunny-based Discord mobile clients. The upstream GitHub Pages site has not been rebuilt since June 2026 because its deploy workflow fails, so this fork exists to publish a current build.

Changes from upstream:

- `plugins/ReadAll/manifest.json` pointed at `src/index.tsx`, which does not exist. It now points at `src/index.ts`.
- `plugins/ReadAll/src/Settings.ts` contained JSX, which the `.ts` extension cannot hold. It is now `Settings.tsx`. Together these two fixes let the build finish instead of aborting after MoreAlts.
- The deploy workflow uses current action versions, pins pnpm 10, installs with a frozen lockfile, and runs on pushes to `main`.
- MoreAlts no longer throws at load if the `TableRowIcon` component cannot be found, and tolerates settings sections without a `settings` array (both seen on newer Discord builds).

## Installing

Paste a plugin URL into the Plugins page of your client:

```
https://mangotcdragon-blip.github.io/e/<PLUGIN_NAME>
```

- [More Alts](https://mangotcdragon-blip.github.io/e/MoreAlts/) - use more than 5 alts and switch between them from the chat input. Also unlocks Discord's native multi-account switcher.
- [Read All](https://mangotcdragon-blip.github.io/e/ReadAll/) - mark every server and DM notification as read with one button in the guild bar.
- [Message Scheduler](https://mangotcdragon-blip.github.io/e/messageScheduler/) - schedule a message for a time or delay. Upstream notes this may count as self-botting. Use at your own risk.
- [BetterNSFWGateBypass](https://mangotcdragon-blip.github.io/e/nsfwbypass/) - see `docs/nsfwbypass` upstream before using.
- [Rick](https://mangotcdragon-blip.github.io/e/rick/)

Using any client mod is against Discord's Terms of Service.

## Building locally

```
pnpm install --frozen-lockfile
node ./build.mjs
```

Output lands in `dist/<PLUGIN_NAME>/` with a `manifest.json` carrying the bundle hash.

## License

CC0-1.0, same as upstream. Plugin authors are credited in each `manifest.json`.
