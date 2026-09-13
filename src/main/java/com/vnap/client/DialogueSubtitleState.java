package com.vnap.client;

import com.vnap.dialogue.DialogueCatalog;
import com.vnap.network.DialogueAnimationPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Draws per-speaker dialogue transcripts above the vanilla subtitle area.
 *
 * <p>Fabric's {@code HudElementRegistry} has no 1.20.1 counterpart; on Forge the
 * equivalent is an {@code IGuiOverlay} registered through
 * {@code RegisterGuiOverlaysEvent} (see {@link VillagerNewsAddonPortClient}).
 * The overlay callback hands over the screen size directly, and 1.20.1 draws
 * through a {@link GuiGraphics} backed by a {@code PoseStack} rather than the
 * newer matrix-stack API.
 */
public final class DialogueSubtitleState {
	private static final double RANGE = 16.0;
	private static final double RANGE_SQUARED = RANGE * RANGE;
	private static final int MAX_LINES = 4;
	private static final Map<UUID, ActiveSubtitle> ACTIVE = new HashMap<>();

	private DialogueSubtitleState() {
	}

	public static void start(DialogueAnimationPacket packet) {
		if (packet.groupId().isEmpty() || packet.durationTicks() <= 0) {
			ACTIVE.remove(packet.entityId());
			return;
		}
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(packet.groupId());
		if (group == null) return;
		DialogueCatalog.DialogueVariant variant = group.variants().stream()
			.filter(candidate -> candidate.index() == packet.variantIndex()).findFirst().orElse(null);
		if (variant == null || variant.subtitles().isEmpty()) return;
		long startNanos = System.nanoTime();
		ACTIVE.put(packet.entityId(), new ActiveSubtitle(
			startNanos, startNanos + packet.durationTicks() * 50_000_000L, variant.subtitles()
		));
	}

	public static void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			ACTIVE.clear();
			return;
		}
		long now = System.nanoTime();
		Iterator<Map.Entry<UUID, ActiveSubtitle>> iterator = ACTIVE.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ActiveSubtitle> entry = iterator.next();
			Entity entity = minecraft.level.getEntity(entry.getKey());
			if (now >= entry.getValue().endNanos() || entity != null && !entity.isAlive()) iterator.remove();
		}
	}

	static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null || !minecraft.options.showSubtitles().get()) return;
		long now = System.nanoTime();
		List<VisibleSubtitle> visible = new ArrayList<>();
		for (Map.Entry<UUID, ActiveSubtitle> entry : ACTIVE.entrySet()) {
			ActiveSubtitle active = entry.getValue();
			if (now >= active.endNanos()) continue;
			Entity entity = minecraft.level.getEntity(entry.getKey());
			if (entity == null || !entity.isAlive()) continue;
			double distanceSquared = minecraft.player.distanceToSqr(entity);
			if (distanceSquared > RANGE_SQUARED) continue;
			int frame = active.frame(now);
			if (frame < 0) continue;
			Component transcript = Component.translatable(active.subtitles().get(frame).key());
			visible.add(new VisibleSubtitle(distanceSquared, subtitleLine(entity, transcript)));
		}
		visible.sort(Comparator.comparingDouble(VisibleSubtitle::distanceSquared));
		float y = screenHeight - 59.0F;
		for (int index = 0; index < Math.min(MAX_LINES, visible.size()); index++) {
			VisibleSubtitle subtitle = visible.get(index);
			float scale = subtitleScale(index, subtitle.distanceSquared());
			drawCentered(graphics, minecraft, subtitle.text(), screenWidth / 2.0F, y, scale);
			y -= (minecraft.font.lineHeight + 3.0F) * scale;
		}
	}

	private static Component subtitleLine(Entity entity, Component transcript) {
		Component name = entity.getName();
		if (entity instanceof Villager villager && !villager.hasCustomName()) {
			// 1.20.1 VillagerProfession carries a plain name string rather than a
			// Holder with a display component, so build the vanilla key by hand.
			name = Component.translatable("entity.minecraft.villager." + villager.getVillagerData().getProfession().name());
		}
		MutableComponent line = Component.empty();
		line.append(name.copy().withStyle(ChatFormatting.YELLOW));
		line.append(Component.literal(": ").withStyle(ChatFormatting.YELLOW));
		line.append(transcript.copy().withStyle(ChatFormatting.WHITE));
		return line;
	}

	private static float subtitleScale(int index, double distanceSquared) {
		if (index == 0) return 1.0F;
		double distance = Math.sqrt(distanceSquared);
		return (float) Math.max(0.65, Math.min(0.9, 0.95 - distance / RANGE * 0.3));
	}

	private static void drawCentered(GuiGraphics graphics, Minecraft minecraft, Component text, float centreX, float y, float scale) {
		int width = minecraft.font.width(text);
		graphics.pose().pushPose();
		graphics.pose().translate(centreX, y, 0.0F);
		graphics.pose().scale(scale, scale, 1.0F);
		graphics.drawString(minecraft.font, text, -width / 2, 0, 0xFFFFFFFF, true);
		graphics.pose().popPose();
	}

	private record VisibleSubtitle(double distanceSquared, Component text) {
	}

	private record ActiveSubtitle(
		long startNanos,
		long endNanos,
		List<DialogueCatalog.SubtitleFrame> subtitles
	) {
		int frame(long now) {
			double elapsed = (now - startNanos) / 1_000_000_000.0;
			int frame = -1;
			for (int index = 0; index < subtitles.size(); index++) {
				if (subtitles.get(index).time() > elapsed) break;
				frame = index;
			}
			return frame;
		}
	}
}
