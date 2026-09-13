package com.vnap;

import com.vnap.config.VillagerNewsSettings;
import com.vnap.dialogue.ContextualDialogueController;
import com.vnap.dialogue.DialogueCatalog;
import com.vnap.item.VillagerNewsItems;
import com.vnap.network.ModNetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge entrypoint.
 *
 * <p>The Forge mod id and the resource namespace deliberately differ. Forge
 * validates mod ids against {@code ^[a-z][a-z0-9_]{1,63}$}, which the upstream
 * Fabric id {@code villager-news-addon-port} fails on account of its hyphens.
 * {@link net.minecraft.resources.ResourceLocation} namespaces do allow hyphens,
 * so the assets keep their original paths and only the loader-facing id changes.
 */
@Mod(VillagerNewsAddonPort.MOD_ID)
public class VillagerNewsAddonPort {
	/** Loader-facing mod id. Only ever used for Forge/mixin plumbing. */
	public static final String MOD_ID = "vnap";

	/** Resource namespace, unchanged from the Fabric build. */
	public static final String NAMESPACE = "villager-news-addon-port";

	public static final Logger LOGGER = LoggerFactory.getLogger(NAMESPACE);

	public VillagerNewsAddonPort() {
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

		VillagerNewsItems.register(modBus);
		modBus.addListener(this::commonSetup);

		VillagerNewsSettings.load();
		ContextualDialogueController.register(MinecraftForge.EVENT_BUS);
	}

	private void commonSetup(FMLCommonSetupEvent event) {
		ModNetwork.register();
		event.enqueueWork(DialogueCatalog::register);
		LOGGER.info("Villager News models, textures, and contextual dialogue are ready.");
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(NAMESPACE, path);
	}
}
