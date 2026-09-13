package com.vnap.network;

import com.vnap.config.VillagerNewsSettings;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class VillagerNewsSettingsNetwork {
	private VillagerNewsSettingsNetwork() {
	}

	/** Replaces Fabric's {@code ServerPlayConnectionEvents.JOIN}. */
	@SubscribeEvent
	public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) send(player);
	}

	public static void send(ServerPlayer player) {
		ModNetwork.sendTo(player, new VillagerNewsSettingsPacket(
			VillagerNewsSettings.chattiness(),
			VillagerNewsSettings.rareVoicelines(),
			VillagerNewsSettings.spawnSpecialVillagers()
		));
	}
}
