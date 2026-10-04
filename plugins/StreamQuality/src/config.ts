import { ReactNative as RN } from "@vendetta/metro/common";
import { findByProps } from "@vendetta/metro";
import { storage } from "@vendetta/plugin";

/**
 * Discord's mobile stream presets (modules/go_live/StreamSettingsConstants).
 * Looked up at runtime; these numbers are the fallback if the lookup fails.
 */
const PRESET_FALLBACK = {
    PRESET_MOBILE_DEFAULT: 5,
    PRESET_MOBILE_PERFORMANCE: 6,
    PRESET_MOBILE_HIGH_QUALITY: 7,
};

export type PresetKey = keyof typeof PRESET_FALLBACK;

export function getPresets(): Record<PresetKey, number> {
    const mod = findByProps("ApplicationStreamPresets", "ApplicationStreamResolutions");
    const live = mod?.ApplicationStreamPresets ?? {};
    return {
        PRESET_MOBILE_DEFAULT: live.PRESET_MOBILE_DEFAULT ?? PRESET_FALLBACK.PRESET_MOBILE_DEFAULT,
        PRESET_MOBILE_PERFORMANCE: live.PRESET_MOBILE_PERFORMANCE ?? PRESET_FALLBACK.PRESET_MOBILE_PERFORMANCE,
        PRESET_MOBILE_HIGH_QUALITY: live.PRESET_MOBILE_HIGH_QUALITY ?? PRESET_FALLBACK.PRESET_MOBILE_HIGH_QUALITY,
    };
}

export const PRESET_LABELS: Record<PresetKey, string> = {
    PRESET_MOBILE_HIGH_QUALITY: "High quality",
    PRESET_MOBILE_PERFORMANCE: "Performance",
    PRESET_MOBILE_DEFAULT: "Default",
};

/** Numeric id of the share-sheet row the plugin takes over. */
export function getTargetPreset(): number {
    const key = (storage.targetPreset as PresetKey) in PRESET_FALLBACK
        ? (storage.targetPreset as PresetKey)
        : "PRESET_MOBILE_HIGH_QUALITY";
    return getPresets()[key];
}

export const RESOLUTION_PRESETS = [480, 720, 1080, 1440, 2160];
export const FPS_PRESETS = [15, 24, 30, 60, 90, 120];

export const LIMITS = {
    resolution: { min: 144, max: 4320 },
    fps: { min: 1, max: 240 },
    side: { min: 16, max: 7680 },
};

export const DEFAULTS = {
    /** Master switch. When off, every patch passes Discord's values through untouched. */
    enabled: true,
    /** Short side of the output in pixels (what "1080p" means on a phone). */
    resolution: 1080,
    /** Frames per second. */
    fps: 60,
    /** When true, width/height below are used verbatim instead of deriving them from the screen. */
    useExactSize: false,
    width: 1080,
    height: 2400,
    /** Which row of Discord's share sheet becomes the custom row. */
    targetPreset: "PRESET_MOBILE_HIGH_QUALITY" as PresetKey,
    /** Only apply the custom values when that row is selected; off forces them for every share. */
    onlyWhenSelected: true,
    /** Also force the numbers at the encoder level (Connection.setDesktopEncodingOptions). */
    patchEncoder: true,
    /** Log every patched call with its arguments to the debug console. */
    debug: false,
};

export function initStorage() {
    for (const key of Object.keys(DEFAULTS)) {
        if (storage[key] === undefined || storage[key] === null) {
            storage[key] = (DEFAULTS as any)[key];
        }
    }
    // Settings from the first release that no longer exist. Only delete when
    // present: every delete on the storage proxy emits a change event.
    for (const stale of ["patchStore", "customPreset"]) {
        if (stale in storage) delete storage[stale];
    }
}

export function clamp(value: number, min: number, max: number) {
    return Math.min(max, Math.max(min, value));
}

/** Round to the nearest even number; video encoders want even dimensions. */
export function even(value: number) {
    const r = Math.round(value);
    return r % 2 === 0 ? r : r + 1;
}

export function parseNumber(text: string): number | null {
    const n = parseInt(String(text).trim(), 10);
    return Number.isFinite(n) ? n : null;
}

export interface StreamTarget {
    /** Value handed to Discord's qualityOptions.resolution (short side in px). */
    resolution: number;
    fps: number;
    width: number;
    height: number;
}

function screenInfo() {
    try {
        const screen = RN.Dimensions.get("screen");
        if (screen.width > 0 && screen.height > 0) {
            return {
                aspect: Math.min(screen.width, screen.height) / Math.max(screen.width, screen.height),
                portrait: screen.height >= screen.width,
            };
        }
    } catch {
        // Dimensions unavailable; fall through to the typical-phone fallback.
    }
    return { aspect: 9 / 16, portrait: true };
}

/**
 * Work out what we want the stream to be. If exact dimensions are off, the
 * screen's aspect ratio is used so a portrait phone gets a portrait stream.
 */
export function getTarget(): StreamTarget {
    const fps = clamp(Number(storage.fps) || DEFAULTS.fps, LIMITS.fps.min, LIMITS.fps.max);

    if (storage.useExactSize) {
        const width = even(clamp(Number(storage.width) || DEFAULTS.width, LIMITS.side.min, LIMITS.side.max));
        const height = even(clamp(Number(storage.height) || DEFAULTS.height, LIMITS.side.min, LIMITS.side.max));
        return { resolution: Math.min(width, height), fps, width, height };
    }

    const resolution = clamp(
        Number(storage.resolution) || DEFAULTS.resolution,
        LIMITS.resolution.min,
        LIMITS.resolution.max
    );
    const { aspect, portrait } = screenInfo();
    const shortSide = even(resolution);
    const longSide = even(resolution / aspect);

    return portrait
        ? { resolution: shortSide, fps, width: shortSide, height: longSide }
        : { resolution: shortSide, fps, width: longSide, height: shortSide };
}

export function describeTarget(t: StreamTarget = getTarget()) {
    return `${t.width}x${t.height} @ ${t.fps} fps`;
}
