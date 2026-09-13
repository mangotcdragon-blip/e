# Villager News — Bedrock add-on to Forge 1.20.1

## Status

Scaffolding only. The actual port is blocked: this environment's egress policy
blocks CurseForge, Dropbox, `maven.minecraftforge.net` and Mojang's servers, so
the source `.mcaddon` could not be downloaded and a Forge build cannot be run
here either.

## How to supply the source add-on

`raw.githubusercontent.com` is reachable from the build session, so committing
the file to this repository is the way in:

```
git checkout claude/forge-minecraft-mod-port-9wzyhg
cp "Villager-News-1.0-Add-On-RP.mcaddon" source/
git add source && git commit -m "Add source add-on" && git push
```

An unzipped copy works just as well, and avoids Git LFS if the archive is large.

## What the port involves

A `.mcaddon` is a zip of one or more packs:

- **Resource pack** (`manifest.json` + `textures/`, `sounds/`, `models/`,
  `texts/`, `sounds/sound_definitions.json`)
- **Behaviour pack** (`entities/`, `loot_tables/`, `trading/`, `scripts/`)

Mapping to Forge:

| Bedrock | Java / Forge 1.20.1 |
| --- | --- |
| `textures/entity/...` | `assets/villagernews/textures/entity/...`, same PNGs, different UV layout for some mobs |
| `sounds/sound_definitions.json` | `assets/villagernews/sounds.json` + `ModSounds` registry entries |
| `texts/en_US.lang` | `assets/villagernews/lang/en_us.json` |
| `entities/*.json` (client entity) | Java `EntityRenderer` / `EntityModel`, or a renderer replacement for vanilla villagers |
| `entities/*.json` (server entity) | `EntityType` + `Entity` subclass, registered via `DeferredRegister` |
| `trading/*.json` | `VillagerTrades` / `WandererTradesEvent` handlers |
| `loot_tables/`, `recipes/` | `data/villagernews/loot_tables/`, `data/villagernews/recipes/` (datapack, unchanged in spirit) |
| `scripts/` (GameTest / Script API) | Java event handlers on `MinecraftForge.EVENT_BUS` |

If the archive turns out to be resource-pack-only — the filename says "RP" —
then most of it is a straight Java resource pack and the Forge mod's job is
narrower: registering the custom sounds so they can be played, and swapping
villager textures/voices at runtime.

## Building

Requires JDK 17 and network access to `maven.minecraftforge.net`.

```
./gradlew build          # jar in build/libs/
./gradlew runClient      # dev client
```
