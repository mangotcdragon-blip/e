package com.vnap.network;

import com.vnap.client.ClientPacketHandler;
import com.vnap.config.VillagerNewsSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Bidirectional: client edits the settings screen, server stores and echoes them back. */
public record VillagerNewsSettingsPacket(int chattiness, int rareVoicelines, boolean spawnSpecialVillagers) {

	public static void encode(VillagerNewsSettingsPacket packet, FriendlyByteBuf buffer) {
		buffer.writeVarInt(packet.chattiness());
		buffer.writeVarInt(packet.rareVoicelines());
		buffer.writeBoolean(packet.spawnSpecialVillagers());
	}

	public static VillagerNewsSettingsPacket decode(FriendlyByteBuf buffer) {
		return new VillagerNewsSettingsPacket(buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
	}

	public static void handle(VillagerNewsSettingsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
				ServerPlayer sender = context.getSender();
				VillagerNewsSettings.update(packet.chattiness(), packet.rareVoicelines(), packet.spawnSpecialVillagers());
				if (sender != null) VillagerNewsSettingsNetwork.send(sender);
			} else {
				DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.onSettings(packet));
			}
		});
		context.setPacketHandled(true);
	}
}
