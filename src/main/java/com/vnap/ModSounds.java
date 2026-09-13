package com.vnap;

import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Sounds that are not dialogue lines.
 *
 * <p>{@link #SILENCE} stands in for Entity Sound Features. Upstream shipped
 * {@code assets/minecraft/esf/**}, whose every rule swaps a vanilla villager,
 * wandering trader or sheep vocalisation for a near-silent clip so that only the
 * mod's own voice lines are heard. ESF is Fabric-first and its 1.20.1 coverage
 * could not be confirmed, so the sound mixins return this instead, reproducing
 * the same result with no extra mod required.
 */
public final class ModSounds {
	private static final DeferredRegister<SoundEvent> SOUNDS =
		DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, VillagerNewsAddonPort.NAMESPACE);

	public static final RegistryObject<SoundEvent> SILENCE =
		SOUNDS.register("silence", () -> SoundEvent.createVariableRangeEvent(VillagerNewsAddonPort.id("silence")));

	private ModSounds() {
	}

	public static void register(IEventBus modBus) {
		SOUNDS.register(modBus);
	}
}
