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
    width: 2400,
    height: 1080,
    /** Report and send dimensions short side first (1080x2400) instead of long side first (2400x1080). */
    portraitDims: false,
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
    /** Value handed to Discord's qualityOptions.resolution: the "p" number, i.e. the short side in px. */
    resolution: number;
    fps: number;
    /** Long side first (2400x1080) unless portraitDims is on. */
    width: number;
    height: number;
}

export interface ScreenInfo {
    /** Physical pixels of the panel, long side and short side. */
    longPx: number;
    shortPx: number;
    scale: number;
    /** False when the size had to be guessed. */
    detected: boolean;
}

/**
 * Physical panel size. RN reports the screen in density-independent points,
 * so multiply by the pixel ratio to get back to real pixels (412x915 points
 * at 2.625 is a 1080x2400 panel).
 */
export function getScreenInfo(): ScreenInfo {
    try {
        const screen = RN.Dimensions.get("screen");
        const scale = RN.PixelRatio.get() || 1;
        const w = screen.width * scale;
        const h = screen.height * scale;
        if (w > 0 && h > 0) {
            return {
                longPx: Math.round(Math.max(w, h)),
                shortPx: Math.round(Math.min(w, h)),
                scale,
                detected: true,
            };
        }
    } catch {
        // Dimensions unavailable; fall through to the typical-phone fallback.
    }
    return { longPx: 2400, shortPx: 1080, scale: 1, detected: false };
}

function orient(long: number, short: number) {
    return storage.portraitDims ? { width: short, height: long } : { width: long, height: short };
}

/**
 * Work out what we want the stream to be. The "p" number is the short side;
 * the long side follows the panel's real pixel ratio, so 1080p on a
 * 2400x1080 panel is 2400x1080 and on a 2780x1264 panel is 2376x1080.
 */
export function getTarget(): StreamTarget {
    const fps = clamp(Number(storage.fps) || DEFAULTS.fps, LIMITS.fps.min, LIMITS.fps.max);

    if (storage.useExactSize) {
        const a = even(clamp(Number(storage.width) || DEFAULTS.width, LIMITS.side.min, LIMITS.side.max));
        const b = even(clamp(Number(storage.height) || DEFAULTS.height, LIMITS.side.min, LIMITS.side.max));
        // Whatever order the user typed, resolution is the short side.
        return { resolution: Math.min(a, b), fps, width: a, height: b };
    }

    const resolution = clamp(
        Number(storage.resolution) || DEFAULTS.resolution,
        LIMITS.resolution.min,
        LIMITS.resolution.max
    );
    const screen = getScreenInfo();
    const shortSide = even(resolution);
    const longSide = even((resolution * screen.longPx) / screen.shortPx);

    return { resolution: shortSide, fps, ...orient(longSide, shortSide) };
}

export function describeTarget(t: StreamTarget = getTarget()) {
    return `${t.width}x${t.height} @ ${t.fps} fps`;
}
