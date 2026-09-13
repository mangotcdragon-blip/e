package com.vnap.client;

import com.vnap.VillagerNewsAddonPort;
import com.vnap.item.VillagerNewsItems;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Client setup, replacing Fabric's {@code ClientModInitializer}.
 *
 * <p>Split across the two Forge buses: the mod bus carries setup and overlay
 * registration, the Forge bus carries per-tick updates and player interaction.
 */
@Mod.EventBusSubscriber(modid = VillagerNewsAddonPort.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class VillagerNewsAddonPortClient {
	private VillagerNewsAddonPortClient() {
	}

	@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			try {
				DialogueAnimationState.load();
			} catch (IOException | RuntimeException exception) {
				throw new IllegalStateException("Could not load Villager News animations", exception);
			}

			registerFloat("vnap_speaking", DialogueAnimationState::speaking, "Whether the Villager News character is speaking");
			registerFloat("vnap_mouth_open", DialogueAnimationState::mouthOpen, "Current Villager News mouth opening");
			registerFloat("vnap_mouth_width", DialogueAnimationState::mouthWidth, "Current Villager News mouth width");
			registerFloat("vnap_mouth_closed", DialogueAnimationState::mouthClosed, "Current Villager News closed-mouth layer");
			registerFloat("vnap_has_nose", DialogueAnimationState::hasNose, "Villager News nose visibility");
			registerFloat("vnap_cosmetic_mayor_hat", () -> DialogueAnimationState.cosmetic(1), "Villager News mayor hat visibility");
			registerFloat("vnap_cosmetic_helmet", () -> DialogueAnimationState.cosmetic(2), "Villager News helmet visibility");
			registerFloat("vnap_cosmetic_microphone", () -> DialogueAnimationState.cosmetic(3), "Villager News microphone visibility");
			registerFloat("vnap_cosmetic_moustache", () -> DialogueAnimationState.cosmetic(4), "Villager News moustache visibility");
			for (String variable : DialogueAnimationState.animationVariables()) {
				registerFloat(variable, () -> DialogueAnimationState.transform(variable), "Synchronized Villager News dialogue transform");
			}

			MinecraftForge.EVENT_BUS.register(ForgeBusEvents.class);
			VillagerNewsAddonPort.LOGGER.info("Registered synchronized EMF facial and dialogue animations");
		});
	}

	@SubscribeEvent
	public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
		CosmeticItemRenderer.registerAdditionalModels(event);
	}

	@SubscribeEvent
	public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAbove(VanillaGuiOverlay.SUBTITLES.id(), "dialogue_subtitles", DialogueSubtitleState::render);
	}

	private static void registerFloat(String name, Supplier<Float> supplier, String description) {
		EmfBridge.registerAnimationVariable(name, description, supplier);
	}

	/** Runtime events, registered on the Forge bus once setup has succeeded. */
	public static final class ForgeBusEvents {
		private ForgeBusEvents() {
		}

		@SubscribeEvent
		public static void onClientTick(TickEvent.ClientTickEvent event) {
			if (event.phase != TickEvent.Phase.END) return;
			Minecraft minecraft = Minecraft.getInstance();
			DialogueSoundState.tick(minecraft);
			DialogueAnimationState.tick(minecraft);
			DialogueSubtitleState.tick(minecraft);
		}

		/** Fabric's {@code UseItemCallback}; opens the handbook on the client only. */
		@SubscribeEvent
		public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
			if (!event.getLevel().isClientSide()) return;
			if (event.getItemStack().getItem() != VillagerNewsItems.HANDBOOK.get()) return;
			Minecraft.getInstance().setScreen(new HandbookScreen());
			event.setCanceled(true);
		}
	}
}
