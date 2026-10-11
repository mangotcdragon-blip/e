import { storage } from "@vendetta/plugin";

/** Size the plugin tells Discord the stream is shown at, by preset key. */
export const SIZES = {
    "720": { label: "720p", width: 1280, height: 720 },
    "1080": { label: "1080p", width: 1920, height: 1080 },
    "1440": { label: "1440p", width: 2560, height: 1440 },
    "2160": { label: "4K (source)", width: 3840, height: 2160 },
};

export type SizeKey = keyof typeof SIZES;

export const DEFAULTS = {
    /** Master switch. */
    enabled: true,
    /** Viewport size reported to Discord when asking for a stream. */
    size: "2160" as SizeKey,
    /** With simulcast streams, subscribe to the highest quality layer. */
    preferBestLayer: true,
    /** Log every rewritten request to the debug console. */
    debug: false,
};

export function initStorage() {
    for (const key of Object.keys(DEFAULTS)) {
        if (storage[key] === undefined || storage[key] === null) {
            storage[key] = (DEFAULTS as any)[key];
        }
    }
}

export function getSize() {
    return SIZES[storage.size as SizeKey] ?? SIZES[DEFAULTS.size];
}
