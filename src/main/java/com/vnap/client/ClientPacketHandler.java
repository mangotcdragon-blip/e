package com.vnap.client;

import com.vnap.network.DialogueAnimationPacket;
import com.vnap.network.VillagerNewsSettingsPacket;

/**
 * Client-only landing point for incoming packets.
 *
 * <p>Kept separate from the packet classes themselves so the server never loads a
 * class that touches {@code net.minecraft.client}; the packets reach it through
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)}.
 */
public final class ClientPacketHandler {
	private ClientPacketHandler() {
	}

	public static void onDialogueAnimation(DialogueAnimationPacket packet) {
		DialogueSoundState.start(packet);
		DialogueAnimationState.start(packet);
		DialogueSubtitleState.start(packet);
	}

	public static void onSettings(VillagerNewsSettingsPacket packet) {
		VillagerNewsSettingsState.apply(packet);
	}
}
