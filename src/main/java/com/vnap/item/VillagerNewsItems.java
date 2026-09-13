package com.vnap.item;

import com.vnap.VillagerNewsAddonPort;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class VillagerNewsItems {
	private static final DeferredRegister<Item> ITEMS =
		DeferredRegister.create(ForgeRegistries.ITEMS, VillagerNewsAddonPort.NAMESPACE);
	private static final DeferredRegister<CreativeModeTab> TABS =
		DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.Keys.CREATIVE_MODE_TABS,
			VillagerNewsAddonPort.NAMESPACE);

	private static final int VILLAGER_EGG_BACKGROUND = 0x563C33;
	private static final int VILLAGER_EGG_HIGHLIGHT = 0x845E42;
	private static final int SHEEP_EGG_BACKGROUND = 0xE7E7E7;
	private static final int SHEEP_EGG_HIGHLIGHT = 0xFFB5B5;

	public static final RegistryObject<Item> HANDBOOK =
		ITEMS.register("handbook", () -> new Item(new Item.Properties().stacksTo(1)));
	public static final RegistryObject<Item> MAYOR_HAT =
		ITEMS.register("mayor_hat", () -> new CosmeticItem(new Item.Properties().stacksTo(1)));
	public static final RegistryObject<Item> MICROPHONE =
		ITEMS.register("microphone", () -> new Item(new Item.Properties().stacksTo(1)));
	public static final RegistryObject<Item> MOUSTACHE =
		ITEMS.register("moustache", () -> new CosmeticItem(new Item.Properties().stacksTo(1)));
	public static final RegistryObject<Item> TESTIFICATE_MAN_HELMET =
		ITEMS.register("testificate_man_helmet", () -> new CosmeticItem(new Item.Properties().stacksTo(1)));
	public static final RegistryObject<Item> VILLAGER_NOSE =
		ITEMS.register("villager_nose", () -> new CosmeticItem(new Item.Properties().stacksTo(1)));

	public static final RegistryObject<Item> MAYOR_VILLAGER_SPAWN_EGG =
		spawnEgg("mayor_villager_spawn_egg", EntityType.VILLAGER, "Mayor Villager", true);
	public static final RegistryObject<Item> TESTIFICATE_MAN_SPAWN_EGG =
		spawnEgg("testificate_man_spawn_egg", EntityType.VILLAGER, "Testificate Man", true);
	public static final RegistryObject<Item> VILLAGER_5_SPAWN_EGG =
		spawnEgg("villager_5_spawn_egg", EntityType.VILLAGER, "Villager #5", true);
	public static final RegistryObject<Item> VILLAGER_9_SPAWN_EGG =
		spawnEgg("villager_9_spawn_egg", EntityType.VILLAGER, "Villager #9", true);
	public static final RegistryObject<Item> UNTOUCHABLE_VILLAGER_SPAWN_EGG =
		spawnEgg("untouchable_villager_spawn_egg", EntityType.VILLAGER, "Villager Unreachable", true);
	public static final RegistryObject<Item> WOOLY_SPAWN_EGG =
		spawnEgg("wooly_spawn_egg", EntityType.SHEEP, "Wooly The Sheep", false);

	public static final RegistryObject<CreativeModeTab> TAB = TABS.register("items", () -> CreativeModeTab.builder()
		.title(Component.translatable("itemGroup.villager-news-addon-port.items"))
		.icon(() -> new ItemStack(HANDBOOK.get()))
		.displayItems((parameters, output) -> {
			output.accept(HANDBOOK.get());
			output.accept(MAYOR_HAT.get());
			output.accept(TESTIFICATE_MAN_HELMET.get());
			output.accept(MICROPHONE.get());
			output.accept(MOUSTACHE.get());
			output.accept(VILLAGER_NOSE.get());
			// Spawn eggs carry their name in NBT, so the tab must show the
			// default *instance* rather than a bare stack of the item.
			output.accept(MAYOR_VILLAGER_SPAWN_EGG.get().getDefaultInstance());
			output.accept(TESTIFICATE_MAN_SPAWN_EGG.get().getDefaultInstance());
			output.accept(VILLAGER_5_SPAWN_EGG.get().getDefaultInstance());
			output.accept(VILLAGER_9_SPAWN_EGG.get().getDefaultInstance());
			output.accept(UNTOUCHABLE_VILLAGER_SPAWN_EGG.get().getDefaultInstance());
			output.accept(WOOLY_SPAWN_EGG.get().getDefaultInstance());
		})
		.build());

	private static final Map<Item, Integer> COSMETICS = new LinkedHashMap<>();

	private VillagerNewsItems() {
	}

	public static void register(IEventBus modBus) {
		ITEMS.register(modBus);
		TABS.register(modBus);
	}

	public static int cosmetic(Item item) {
		ensureCosmetics();
		return COSMETICS.getOrDefault(item, 0);
	}

	public static Item cosmeticItem(int cosmetic) {
		ensureCosmetics();
		return COSMETICS.entrySet().stream().filter(entry -> entry.getValue() == cosmetic)
			.map(Map.Entry::getKey).findFirst().orElse(null);
	}

	/**
	 * Built lazily: {@link RegistryObject#get()} throws until registration has run,
	 * so this cannot be a static initialiser the way it was upstream.
	 */
	private static synchronized void ensureCosmetics() {
		if (!COSMETICS.isEmpty()) return;
		COSMETICS.put(MAYOR_HAT.get(), 1);
		COSMETICS.put(TESTIFICATE_MAN_HELMET.get(), 2);
		COSMETICS.put(MICROPHONE.get(), 3);
		COSMETICS.put(MOUSTACHE.get(), 4);
	}

	private static RegistryObject<Item> spawnEgg(String path, EntityType<? extends net.minecraft.world.entity.Mob> type,
	                                             String entityName, boolean villagerColours) {
		return ITEMS.register(path, () -> new NamedSpawnEggItem(
			type,
			villagerColours ? VILLAGER_EGG_BACKGROUND : SHEEP_EGG_BACKGROUND,
			villagerColours ? VILLAGER_EGG_HIGHLIGHT : SHEEP_EGG_HIGHLIGHT,
			entityName,
			new Item.Properties()
		));
	}
}
