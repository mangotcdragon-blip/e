package com.vnap.item;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * A head-slot cosmetic.
 *
 * <p>1.20.1 has no {@code Item.Properties#equippable(EquipmentSlot)}; the equivalent
 * is implementing {@link Equipable}, which makes the stack go to the head slot for
 * mobs, dispensers and shift-equip, and lets {@link #use} swap it in on right click.
 */
public class CosmeticItem extends Item implements Equipable {
	public CosmeticItem(Properties properties) {
		super(properties);
	}

	@Override
	public EquipmentSlot getEquipmentSlot() {
		return EquipmentSlot.HEAD;
	}

	@Override
	public SoundEvent getEquipSound() {
		return SoundEvents.ARMOR_EQUIP_GENERIC;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		return swapWithEquipmentSlot(this, level, player, hand);
	}

	/**
	 * Routes drawing through {@code CosmeticItemRenderer} so the worn model can be
	 * substituted in the head slot, which 1.20.1 item JSON cannot express.
	 * Forge only calls this on a physical client, so the client-only classes the
	 * returned extension touches are never loaded on a server.
	 */
	@Override
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(new IClientItemExtensions() {
			@Override
			public net.minecraft.client.renderer.blockentity.BlockEntityWithoutLevelRenderer getCustomRenderer() {
				return com.vnap.client.CosmeticItemRenderer.get();
			}
		});
	}
}
