package com.vnap.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vnap.VillagerNewsAddonPort;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Parses {@code dialogues.json} and registers one {@link SoundEvent} per voice line.
 *
 * <p>Upstream called {@code Registry.register(BuiltInRegistries.SOUND_EVENT, ...)}
 * lazily during setup. Forge freezes its registries after the registration events,
 * so on 1.20.1 the catalog has to be parsed during mod construction and fed into a
 * {@link DeferredRegister}. Variants therefore hold a supplier rather than a
 * resolved {@link SoundEvent}; call {@link DialogueVariant#sound()} once the
 * registry has been populated.
 */
public final class DialogueCatalog {
	private static final String CATALOG_PATH = "/assets/villager-news-addon-port/dialogues.json";
	private static final Map<String, DialogueGroup> GROUPS = new LinkedHashMap<>();
	private static final Map<String, List<DialogueGroup>> TITLES = new LinkedHashMap<>();
	private static final DeferredRegister<SoundEvent> SOUNDS =
		DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, VillagerNewsAddonPort.NAMESPACE);

	private DialogueCatalog() {
	}

	/** Must run during mod construction, before Forge fires the registry events. */
	public static void register(IEventBus modBus) {
		load();
		SOUNDS.register(modBus);
	}

	private static void load() {
		try (InputStream stream = DialogueCatalog.class.getResourceAsStream(CATALOG_PATH)) {
			if (stream == null) {
				throw new IOException("Missing " + CATALOG_PATH);
			}
			JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			int variantCount = 0;
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("groups").entrySet()) {
				String groupId = entry.getKey();
				JsonObject value = entry.getValue().getAsJsonObject();
				List<DialogueVariant> variants = new ArrayList<>();
				for (JsonElement variantElement : value.getAsJsonArray("variants")) {
					JsonObject variantValue = variantElement.getAsJsonObject();
					int index = variantValue.get("index").getAsInt();
					List<SubtitleFrame> subtitles = new ArrayList<>();
					for (JsonElement subtitleElement : variantValue.getAsJsonArray("subtitles")) {
						JsonObject subtitleValue = subtitleElement.getAsJsonObject();
						subtitles.add(new SubtitleFrame(
							subtitleValue.get("time").getAsDouble(),
							subtitleValue.get("key").getAsString()
						));
					}
					String soundPath = "dialogue." + groupId + "." + index;
					ResourceLocation soundId = VillagerNewsAddonPort.id(soundPath);
					RegistryObject<SoundEvent> sound =
						SOUNDS.register(soundPath, () -> SoundEvent.createVariableRangeEvent(soundId));
					variants.add(new DialogueVariant(
						index,
						variantValue.get("duration").getAsDouble(),
						variantValue.get("weight").getAsInt(),
						variantValue.get("animation").getAsString(),
						sound,
						List.copyOf(subtitles)
					));
					variantCount++;
				}
				DialogueGroup group = new DialogueGroup(
					groupId,
					value.get("title").getAsString(),
					value.get("body").getAsString(),
					value.get("speaker").getAsString(),
					value.get("maximumDuration").getAsDouble(),
					List.copyOf(variants)
				);
				GROUPS.put(groupId, group);
				if (!group.title().isBlank()) TITLES.computeIfAbsent(group.title(), ignored -> new ArrayList<>()).add(group);
			}
			VillagerNewsAddonPort.LOGGER.info("Registered {} contextual dialogue groups with {} synchronized variants", GROUPS.size(), variantCount);
		} catch (IOException | RuntimeException exception) {
			throw new IllegalStateException("Could not load Villager News dialogue catalog", exception);
		}
	}

	public static DialogueGroup byId(String id) {
		return GROUPS.get(id);
	}

	public static DialogueGroup byTitle(String title) {
		List<DialogueGroup> matches = TITLES.get(title);
		return matches == null || matches.isEmpty() ? null : matches.get(0);
	}

	public static DialogueGroup byTitle(String title, String speaker) {
		List<DialogueGroup> matches = TITLES.get(title);
		if (matches == null) return null;
		return matches.stream().filter(group -> group.speaker().equals(speaker)).findFirst().orElse(null);
	}

	public static Map<String, DialogueGroup> groups() {
		return Collections.unmodifiableMap(GROUPS);
	}

	public record DialogueGroup(
		String id,
		String title,
		String body,
		String speaker,
		double maximumDuration,
		List<DialogueVariant> variants
	) {
		public long durationTicks() {
			return Math.max(20L, (long) Math.ceil(maximumDuration * 20.0));
		}

		public DialogueVariant chooseVariant() {
			return chooseVariant(1);
		}

		public DialogueVariant chooseVariant(int rareVoicelines) {
			return chooseVariant(rareVoicelines, Set.of());
		}

		public DialogueVariant chooseVariant(int rareVoicelines, Set<Integer> excludedVariants) {
			if (variants.isEmpty()) return null;
			int minimum = variants.stream().mapToInt(DialogueVariant::weight).min().orElse(1);
			int maximum = variants.stream().mapToInt(DialogueVariant::weight).max().orElse(1);
			int[] weights = new int[variants.size()];
			int totalWeight = 0;
			for (int index = 0; index < variants.size(); index++) {
				int weight = variants.get(index).weight();
				if (rareVoicelines == 0 && weight < maximum * 0.8) weight = 0;
				else if (rareVoicelines == 2) weight = maximum + minimum - weight;
				if (excludedVariants.contains(variants.get(index).index())) weight = 0;
				weights[index] = Math.max(0, weight);
				totalWeight += weights[index];
			}
			if (totalWeight <= 0) {
				if (!excludedVariants.isEmpty()) return chooseVariant(rareVoicelines, Set.of());
				return variants.get(0);
			}
			int choice = ThreadLocalRandom.current().nextInt(Math.max(1, totalWeight));
			for (int index = 0; index < variants.size(); index++) {
				choice -= weights[index];
				if (choice < 0) return variants.get(index);
			}
			return variants.get(variants.size() - 1);
		}
	}

	public record DialogueVariant(
		int index,
		double duration,
		int weight,
		String animation,
		Supplier<SoundEvent> soundSupplier,
		List<SubtitleFrame> subtitles
	) {
		public SoundEvent sound() {
			return soundSupplier.get();
		}

		public long durationTicks() {
			return Math.max(20L, (long) Math.ceil(duration * 20.0));
		}
	}

	public record SubtitleFrame(double time, String key) {
	}
}
