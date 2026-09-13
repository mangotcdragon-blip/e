package com.vnap.network;

import com.vnap.VillagerNewsAddonPort;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Forge replacement for Fabric's {@code PayloadTypeRegistry} / {@code ServerPlayNetworking}.
 *
 * <p>1.20.1 predates {@code CustomPacketPayload} and {@code StreamCodec}, so the two
 * payload records become plain messages on a versioned {@link SimpleChannel}.
 */
public final class ModNetwork {
	private static final String PROTOCOL_VERSION = "1";

	public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
		.named(VillagerNewsAddonPort.id("main"))
		.clientAcceptedVersions(PROTOCOL_VERSION::equals)
		.serverAcceptedVersions(PROTOCOL_VERSION::equals)
		.networkProtocolVersion(() -> PROTOCOL_VERSION)
		.simpleChannel();

	private static int nextId;

	private ModNetwork() {
	}

	public static void register() {
		CHANNEL.registerMessage(nextId++, DialogueAnimationPacket.class,
			DialogueAnimationPacket::encode, DialogueAnimationPacket::decode,
			DialogueAnimationPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));

		CHANNEL.registerMessage(nextId++, VillagerNewsSettingsPacket.class,
			VillagerNewsSettingsPacket::encode, VillagerNewsSettingsPacket::decode,
			VillagerNewsSettingsPacket::handle, Optional.empty());
	}

	public static void sendTo(ServerPlayer player, Object message) {
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
	}

	public static void sendToServer(Object message) {
		CHANNEL.sendToServer(message);
	}
}
