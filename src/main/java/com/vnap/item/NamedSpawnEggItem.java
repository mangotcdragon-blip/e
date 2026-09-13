package com.vnap.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

/**
 * A vanilla-entity spawn egg that always spawns a specifically named mob.
 *
 * <p>Upstream attached a {@code DataComponents.ENTITY_DATA} component, which does not
 * exist before 1.20.5. The 1.20.1 equivalent is the {@code EntityTag} compound on the
 * stack's NBT, which {@link EntityType#updateCustomEntityTag} applies at spawn time.
 * Note that {@code CustomName} is a serialized-JSON string here, not a codec-encoded tag.
 */
public class NamedSpawnEggItem extends SpawnEggItem {
	private final String entityName;

	public NamedSpawnEggItem(EntityType<? extends Mob> type, int backgroundColor, int highlightColor,
	                         String entityName, Properties properties) {
		super(type, backgroundColor, highlightColor, properties);
		this.entityName = entityName;
	}

	public String entityName() {
		return entityName;
	}

	@Override
	public ItemStack getDefaultInstance() {
		ItemStack stack = new ItemStack(this);
		CompoundTag entityTag = new CompoundTag();
		entityTag.putString("CustomName", Component.Serializer.toJson(Component.literal(entityName)));
		entityTag.putBoolean("PersistenceRequired", true);
		stack.getOrCreateTag().put("EntityTag", entityTag);
		return stack;
	}
}
