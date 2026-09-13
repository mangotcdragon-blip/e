package com.vnap.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vnap.VillagerNewsAddonPort;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ModelEvent;

import java.util.List;

/**
 * Renders head cosmetics with a different model when worn.
 *
 * <p>Upstream relied on the 1.21.4+ item-definition format, whose
 * {@code minecraft:select} on {@code display_context} swaps in the {@code _worn}
 * model for the head slot. 1.20.1 item JSON cannot branch on display context at
 * all, so the equivalent is a custom item renderer: the item's own model is
 * {@code builtin/entity}, which routes every draw through here, and this picks
 * the worn geometry for {@link ItemDisplayContext#HEAD} and the flat inventory
 * sprite everywhere else.
 *
 * <p>Model transforms are applied by the item renderer before this runs, so the
 * item model's {@code display.head} entry is deliberately identity: the worn
 * models are already authored in head space.
 */
public final class CosmeticItemRenderer extends BlockEntityWithoutLevelRenderer {
	/** Cosmetics that have a distinct worn model. */
	public static final List<String> WORN_COSMETICS =
		List.of("mayor_hat", "moustache", "testificate_man_helmet", "villager_nose");

	private static CosmeticItemRenderer instance;

	private CosmeticItemRenderer() {
		super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
	}

	public static synchronized CosmeticItemRenderer get() {
		if (instance == null) instance = new CosmeticItemRenderer();
		return instance;
	}

	/** The flat sprite model, kept alongside the {@code builtin/entity} item model. */
	private static ModelResourceLocation flatModel(String name) {
		return new ModelResourceLocation(VillagerNewsAddonPort.NAMESPACE, "item/" + name + "_flat", "inventory");
	}

	private static ModelResourceLocation wornModel(String name) {
		return new ModelResourceLocation(VillagerNewsAddonPort.NAMESPACE, "item/" + name + "_worn", "inventory");
	}

	/**
	 * Neither model is reachable from a blockstate or an item, so both have to be
	 * requested explicitly or they are never baked.
	 */
	public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
		for (String name : WORN_COSMETICS) {
			event.register(flatModel(name));
			event.register(wornModel(name));
		}
	}

	@Override
	public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
	                         MultiBufferSource buffer, int packedLight, int packedOverlay) {
		Minecraft minecraft = Minecraft.getInstance();
		String name = itemName(stack);
		if (name == null) return;
		ModelResourceLocation location = displayContext == ItemDisplayContext.HEAD ? wornModel(name) : flatModel(name);
		BakedModel model = minecraft.getModelManager().getModel(location);
		if (model == null || model == minecraft.getModelManager().getMissingModel()) return;
		minecraft.getItemRenderer().render(stack, displayContext, false, poseStack, buffer, packedLight, packedOverlay, model);
	}

	private static String itemName(ItemStack stack) {
		var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
		return key != null && WORN_COSMETICS.contains(key.getPath()) ? key.getPath() : null;
	}
}
