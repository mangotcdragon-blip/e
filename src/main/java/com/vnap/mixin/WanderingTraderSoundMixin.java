package com.vnap.mixin;

import com.vnap.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.npc.WanderingTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mutes vanilla wandering trader vocalisations, matching the unconditional
 * {@code assets/minecraft/esf/entity/wandering_trader/*} rules the add-on ships
 * (ambient, hurt, death, trade, yes and no).
 */
@Mixin(WanderingTrader.class)
public abstract class WanderingTraderSoundMixin {
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
