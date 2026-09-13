# Villager News Addon Port — Fabric/26.2 to Forge/1.20.1

A port of [MarcYohannTheScripter/villager-news-addon-port](https://github.com/MarcYohannTheScripter/villager-news-addon-port)
(Fabric loader, Minecraft 26.2, Java 25) to **Forge 47.3.0 on Minecraft 1.20.1, Java 17**.

This was two changes at once: a loader change *and* roughly three years of
Minecraft API regression. Around 40 of the upstream project's ~110 Minecraft
imports do not exist in 1.20.1.

## Build status

**Not compiled.** The environment this port was written in blocks
`maven.minecraftforge.net`, `maven.fabricmc.net`, `api.modrinth.com` and
Mojang's servers, and ships only JDK 21, so `gradlew build` could not be run.

What *was* verified: every source file parses as valid Java 17 (`javac` reports
no syntax errors across the tree — only unresolved Minecraft/Forge symbols,
which is expected without the classpath), and all 42 JSON resources parse.

Expect compile errors on the first real build. The most likely sources are
listed under "Needs checking" below.

## Naming

| | Upstream | Here |
| --- | --- | --- |
| Mod id | `villager-news-addon-port` | `vnap` |
| Resource namespace | `villager-news-addon-port` | `villager-news-addon-port` |

Forge validates mod ids against `^[a-z][a-z0-9_]{1,63}$`, which rejects hyphens.
`ResourceLocation` namespaces *do* permit them, so only the loader-facing id
changed and all 2,213 sounds and 743 textures keep their original paths.

## Platform translation

| Upstream (26.2 / Fabric) | Here (1.20.1 / Forge) |
| --- | --- |
| `ModInitializer` / `ClientModInitializer` | `@Mod` constructor + `@Mod.EventBusSubscriber` |
| `PayloadTypeRegistry`, `CustomPacketPayload`, `StreamCodec` | Forge `SimpleChannel` with plain message records |
| `RegistryFriendlyByteBuf` | `FriendlyByteBuf` |
| `Registry.register(BuiltInRegistries…)` | `DeferredRegister` |
| `FabricCreativeModeTab` | `DeferredRegister<CreativeModeTab>` |
| `FabricLoader.getConfigDir()` | `FMLPaths.CONFIGDIR` |
| `ServerTickEvents.END_SERVER_TICK` | `TickEvent.ServerTickEvent` |
| `ServerEntityEvents.ENTITY_LOAD` / `_UNLOAD` | `EntityJoinLevelEvent` / `EntityLeaveLevelEvent` |
| `PlayerBlockBreakEvents.AFTER` | `BlockEvent.BreakEvent` (pre-break; no post-break event exists) |
| `UseBlockCallback` / `UseItemCallback` / `UseEntityCallback` | `PlayerInteractEvent.RightClickBlock` / `.RightClickItem` / `.EntityInteract` |
| `AttackEntityCallback` | `AttackEntityEvent` |
| `EntitySleepEvents.STOP_SLEEPING` | `PlayerEvent.PlayerWakeUpEvent` + a mixin for villagers |
| `ServerLivingEntityEvents.AFTER_DAMAGE` / `AFTER_DEATH` | `LivingDamageEvent` / `LivingDeathEvent` |
| `HudElementRegistry` | `RegisterGuiOverlaysEvent` + `IGuiOverlay` |

## Minecraft API regression

| Upstream | 1.20.1 |
| --- | --- |
| `resources.Identifier`, `ResourceKey#identifier()` | `ResourceLocation`, `ResourceKey#location()` |
| `core.component.DataComponents`, `TypedEntityData` | `EntityTag` NBT compound |
| `storage.ValueInput` / `ValueOutput` | `CompoundTag` |
| `entity.npc.villager.Villager`, `.wanderingtrader.WanderingTrader` | `entity.npc.Villager`, `entity.npc.WanderingTrader` |
| `entity.animal.sheep.Sheep` | `entity.animal.Sheep` |
| `EntitySpawnReason`, `Entity#spawnReason()` | `MobSpawnType`, captured from `MobSpawnEvent.FinalizeSpawn` |
| `Item.Properties#equippable(slot)` | implement `Equipable` |
| `MerchantOffer(ItemCost, …)` | `MerchantOffer(ItemStack, …)` |
| `EnvironmentAttributes.SUN_ANGLE` | `Level#getSunAngle(float)` |
| `Level#getOverworldClockTime()` | `Level#getDayTime()` |
| `Level#getRespawnData().pos()` | `Level#getSharedSpawnPos()` |
| `Entity#entityTags()` | `Entity#getTags()` |
| `ScoreHolder` + `Score#get()/set()` | name strings + `Score#getScore()/setScore()` |
| `VillagerData#level()`, `#profession()` (Holder) | `#getLevel()`, `#getProfession()` (plain) |
| `MinecraftServer#tickRateManager()` | removed — arrived in 1.20.3, nothing to guard |
| `ServerPlayer#sendOverlayMessage` | `Player#displayClientMessage(component, true)` |
| `SoundEvents.EMPTY` | a registered silent event (see below) |
| `List#getFirst/getLast/removeFirst` (Java 21) | indexed access (Java 17 target) |
| `GuiGraphicsExtractor`, `DeltaTracker` | `GuiGraphics` + `PoseStack`, `float partialTick` |

## Design decisions worth knowing

**EMF goes through reflection.** `com.vnap.client.EmfBridge` wraps every Entity
Model Features call. Upstream imports `traben.entity_model_features.EMFAnimationApi`
directly, but that is the EMF 3.x surface and the 1.20.1 builds differ. Reflection
means the mod compiles with no EMF jar, a client without EMF still loads (vanilla
villager models, no facial animation), and adapting to EMF's real 1.20.1 API is a
change to one file. **This is the single most likely thing to need adjustment.**

**ESF is gone, replaced by mixins.** Every rule in the shipped
`assets/minecraft/esf/**` is unconditional and swaps a vanilla villager, wandering
trader or sheep sound for a near-silent clip — Entity Sound Features was only ever
being used as a mute switch. `VillagerSoundMixin`, `WanderingTraderSoundMixin` and
`SheepSoundMixin` now return a registered `villager-news-addon-port:silence` event
instead, which drops the dependency entirely. Note this mutes **all** sheep, not
just Wooly, matching the shipped rules. The ESF asset files are left in place but
are inert.

**Worn cosmetics use a custom renderer.** The 1.21.4+ item-definition format lets a
model branch on display context; 1.20.1 JSON cannot. `CosmeticItemRenderer` (a
`BlockEntityWithoutLevelRenderer`) picks the `_worn` model for
`ItemDisplayContext.HEAD` and a `_flat` sprite model otherwise. The four cosmetic
item models are now `builtin/entity` to route drawing through it, with the original
sprite models preserved as `<name>_flat.json`.

**`VillagerProfessionLayerMixin` was dropped.** It redirects a `noHatModel` field
read inside `submit`. 1.20.1's `VillagerProfessionLayer` has neither the field nor
the method — it already renders clothing through `getParentModel()`, so the bug
that mixin fixes does not exist here.

## Needs checking on first build

1. **EMF API shape** — `EmfBridge` looks up `registerSingletonAnimationVariable`
   and `getCurrentEntity` by name, plus `emf$age()` / `etf$getUuid()` on the
   entity. If EMF 1.20.1 names these differently, animation silently no-ops and
   the log says so. Fix in `EmfBridge`, not at the call sites.
2. **Mixin targets** — five mixins inject by name. `Villager#stopTrading`,
   `Villager#getTradeUpdatedSound`, `AbstractVillager#notifyTrade` and
   `LivingEntity#stopSleeping` all need to exist with these signatures;
   `defaultRequire: 1` means a miss is a hard crash, not a warning.
3. **Worn cosmetic placement** — the `display.head` transform is identity so the
   worn geometry sits where it was authored. This could not be checked visually
   and may need tuning.
4. **`EditBox#setHint`** in `HandbookScreen` — believed present in 1.20.1, unverified.
5. **`BlockEvent.BreakEvent` timing** — fires before the break, where upstream
   reacted after. Harmless for dialogue, but the block is still present.

## Building

Requires JDK 17 and access to `maven.minecraftforge.net`.

```
./gradlew build          # jar in build/libs/
./gradlew runClient
```

Install Entity Model Features and Entity Texture Features (1.20.1 Forge builds)
client-side for the Villager News models and textures. Without them the mod loads
and the dialogue system works, but villagers look vanilla.

## Licensing

Per the upstream `LICENSE`: the models, textures, sounds, dialogue and names remain
copyright of Oreville Studios Ltd and Element Animation. Only the small amount of
template-derived source code is CC0.
