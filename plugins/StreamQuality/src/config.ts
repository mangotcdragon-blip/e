import { ReactNative as RN } from "@vendetta/metro/common";
import { storage } from "@vendetta/plugin";

/** Discord's "custom" stream preset. 1 = clear video, 2 = smooth video, 3 = custom. */
export const CUSTOM_PRESET = 3;

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
    /** Tag the stream with Discord's "custom" preset so presets don't override the numbers. */
    customPreset: true,
    /** Also force the numbers at the encoder level (Connection.setDesktopEncodingOptions). */
    patchEncoder: true,
    /** Also make ApplicationStreamingSettingsStore report the custom values. */
    patchStore: false,
    /** Log every patched call with its arguments to the debug console. */
    debug: false,
};

export function initStorage() {
    for (const key of Object.keys(DEFAULTS)) {
        if (storage[key] === undefined || storage[key] === null) {
            storage[key] = (DEFAULTS as any)[key];
        }
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

    let aspect = 9 / 16; // short / long, fallback for a typical phone
    try {
        const screen = RN.Dimensions.get("screen");
        const short = Math.min(screen.width, screen.height);
        const long = Math.max(screen.width, screen.height);
        if (short > 0 && long > 0) aspect = short / long;
    } catch {
        // Dimensions unavailable; keep the fallback.
    }

    const shortSide = even(resolution);
    const longSide = even(resolution / aspect);

    let portrait = true;
    try {
        const screen = RN.Dimensions.get("screen");
        portrait = screen.height >= screen.width;
    } catch {
        // keep portrait
    }

    return portrait
        ? { resolution: shortSide, fps, width: shortSide, height: longSide }
        : { resolution: shortSide, fps, width: longSide, height: shortSide };
}

export function describeTarget(t: StreamTarget = getTarget()) {
    return `${t.width}x${t.height} @ ${t.fps} fps`;
}
