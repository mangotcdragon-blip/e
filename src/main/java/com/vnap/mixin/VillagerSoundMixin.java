package com.vnap.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Silences vanilla villager vocalisations so the dialogue system's own voice
 * lines are the only thing heard.
 *
 * <p>Upstream returned {@code SoundEvents.EMPTY}, which does not exist in 1.20.1.
 * Here the accessors return {@code null}, which every 1.20.1 caller already
 * null-checks before playing.
 */
@Mixin(Villager.class)
public abstract class VillagerSoundMixin {
	@Inject(method = "getAmbientSound", at = @At("HEAD"), cancellable = true)
	private void vnap$removeUnreachableAmbientSound(CallbackInfoReturnable<SoundEvent> cir) {
		if (vnap$isUnreachable()) cir.setReturnValue(null);
	}

	@Inject(method = "getHurtSound", at = @At("HEAD"), cancellable = true)
	private void vnap$removeUnreachableHurtSound(DamageSource source, CallbackInfoReturnable<SoundEvent> cir) {
		if (vnap$isUnreachable()) cir.setReturnValue(null);
	}

	@Inject(method = "getDeathSound", at = @At("HEAD"), cancellable = true)
	private void vnap$removeVanillaDeathSound(CallbackInfoReturnable<SoundEvent> cir) {
		cir.setReturnValue(null);
	}

	private boolean vnap$isUnreachable() {
		String name = ((Villager) (Object) this).getName().getString();
		return name.equalsIgnoreCase("Villager Unreachable") || name.equalsIgnoreCase("Can't Catch Me!");
	}
}
