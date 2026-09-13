package com.vnap.network;

import com.vnap.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Clientbound: "entity X started saying line (group, variant) for N ticks". */
public record DialogueAnimationPacket(UUID entityId, String groupId, int variantIndex, int durationTicks) {

	public static void encode(DialogueAnimationPacket packet, FriendlyByteBuf buffer) {
		buffer.writeUUID(packet.entityId());
		buffer.writeUtf(packet.groupId(), 64);
		buffer.writeVarInt(packet.variantIndex());
		buffer.writeVarInt(packet.durationTicks());
	}

	public static DialogueAnimationPacket decode(FriendlyByteBuf buffer) {
		return new DialogueAnimationPacket(
			buffer.readUUID(),
			buffer.readUtf(64),
			buffer.readVarInt(),
			buffer.readVarInt()
		);
	}

	public static void handle(DialogueAnimationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
			() -> () -> ClientPacketHandler.onDialogueAnimation(packet)));
		context.setPacketHandled(true);
	}
}
