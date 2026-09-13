package com.vnap.mixin;

import com.vnap.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mutes vanilla villager vocalisations so the dialogue system's voice lines are
 * the only thing heard.
 *
 * <p>This replaces the shipped {@code assets/minecraft/esf/entity/villager/*}
 * rules, which are unconditional and swap ambient, hurt, death, trade and "no"
 * sounds for a silent clip. A registered silent event is returned rather than
 * {@code null} because the trade callers pass the result straight to
 * {@code playSound} without a null check.
 */
@Mixin(Villager.class)
public abstract class VillagerSoundMixin {
	@Inject(method = "getAmbientSound", at = @At("HEAD"), cancellable = true)
	private void vnap$muteAmbientSound(CallbackInfoReturnable<SoundEvent> cir) {
		cir.setReturnValue(ModSounds.SILENCE.get());
	}

	@Inject(method = "getHurtSound", at = @At("HEAD"), cancellable = true)
	private void vnap$muteHurtSound(DamageSource source, CallbackInfoReturnable<SoundEvent> cir) {
		cir.setReturnValue(ModSounds.SILENCE.get());
	}

	@Inject(method = "getDeathSound", at = @At("HEAD"), cancellable = true)
	private void vnap$muteDeathSound(CallbackInfoReturnable<SoundEvent> cir) {
		cir.setReturnValue(ModSounds.SILENCE.get());
	}

	@Inject(method = "getTradeUpdatedSound", at = @At("HEAD"), cancellable = true)
	private void vnap$muteTradeUpdatedSound(boolean success, CallbackInfoReturnable<SoundEvent> cir) {
		cir.setReturnValue(ModSounds.SILENCE.get());
	}

	@Inject(method = "getNotifyTradeSound", at = @At("HEAD"), cancellable = true)
	private void vnap$muteNotifyTradeSound(CallbackInfoReturnable<SoundEvent> cir) {
		cir.setReturnValue(ModSounds.SILENCE.get());
	}
}
