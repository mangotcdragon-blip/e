package com.vnap.mixin;

import com.vnap.dialogue.ContextualDialogueController;
import com.vnap.entity.VillagerNewsData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public abstract class VillagerDataMixin implements VillagerNewsData {
	@Unique
	private static final EntityDataAccessor<Boolean> VNAP_HAS_NOSE = SynchedEntityData.defineId(Villager.class, EntityDataSerializers.BOOLEAN);
	@Unique
	private static final EntityDataAccessor<Integer> VNAP_COSMETIC = SynchedEntityData.defineId(Villager.class, EntityDataSerializers.INT);
	@Unique
	private static final EntityDataAccessor<Integer> VNAP_SIGN_MESSAGE = SynchedEntityData.defineId(Villager.class, EntityDataSerializers.INT);

	/**
	 * 1.20.1 has no {@code SynchedEntityData.Builder}; entries are defined straight
	 * onto the entity's own {@code entityData} from the no-argument overload.
	 */
	@Inject(method = "defineSynchedData", at = @At("TAIL"))
	private void vnap$defineData(CallbackInfo ci) {
		SynchedEntityData data = ((Villager) (Object) this).getEntityData();
		data.define(VNAP_HAS_NOSE, true);
		data.define(VNAP_COSMETIC, 0);
		data.define(VNAP_SIGN_MESSAGE, -1);
	}

	/** 1.20.1 saves through {@link CompoundTag}, not {@code ValueOutput}. */
	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void vnap$saveData(CompoundTag tag, CallbackInfo ci) {
		tag.putBoolean("VillagerNewsHasNose", vnap$hasNose());
		tag.putInt("VillagerNewsCosmetic", vnap$cosmetic());
		tag.putInt("VillagerNewsSignMessage", vnap$signMessage());
	}

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void vnap$loadData(CompoundTag tag, CallbackInfo ci) {
		vnap$setHasNose(!tag.contains("VillagerNewsHasNose") || tag.getBoolean("VillagerNewsHasNose"));
		vnap$setCosmetic(tag.contains("VillagerNewsCosmetic") ? tag.getInt("VillagerNewsCosmetic") : 0);
		vnap$setSignMessage(tag.contains("VillagerNewsSignMessage") ? tag.getInt("VillagerNewsSignMessage") : -1);
	}

	@Redirect(
		method = "customServerAiStep",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;stopTrading()V")
	)
	private void vnap$keepSpecialTradeOpen(Villager villager) {
		if (!ContextualDialogueController.isSpecialTrader(villager)) villager.setTradingPlayer(null);
	}

	@Override
	public boolean vnap$hasNose() {
		return ((Villager) (Object) this).getEntityData().get(VNAP_HAS_NOSE);
	}

	@Override
	public void vnap$setHasNose(boolean value) {
		((Villager) (Object) this).getEntityData().set(VNAP_HAS_NOSE, value);
	}

	@Override
	public int vnap$cosmetic() {
		return ((Villager) (Object) this).getEntityData().get(VNAP_COSMETIC);
	}

	@Override
	public void vnap$setCosmetic(int value) {
		((Villager) (Object) this).getEntityData().set(VNAP_COSMETIC, Math.max(0, Math.min(4, value)));
	}

	@Override
	public int vnap$signMessage() {
		return ((Villager) (Object) this).getEntityData().get(VNAP_SIGN_MESSAGE);
	}

	@Override
	public void vnap$setSignMessage(int value) {
		((Villager) (Object) this).getEntityData().set(VNAP_SIGN_MESSAGE, Math.max(-1, Math.min(86, value)));
	}
}
