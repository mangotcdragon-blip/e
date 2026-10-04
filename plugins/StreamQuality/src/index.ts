import { logger } from "@vendetta";
import { findByName, findByProps, findByStoreName } from "@vendetta/metro";
import { after, before } from "@vendetta/patcher";
import { storage } from "@vendetta/plugin";
import { showToast } from "@vendetta/ui/toasts";
import { getAssetIDByName } from "@vendetta/ui/assets";

import Settings from "./Settings";
import { CUSTOM_PRESET, describeTarget, getTarget, initStorage } from "./config";

let patches: Array<() => void> = [];

function debug(...args: any[]) {
    if (storage.debug) logger.log("[StreamQuality]", ...args);
}

function safePatch(label: string, fn: () => (() => void) | undefined | null) {
    try {
        const unpatch = fn();
        if (typeof unpatch === "function") {
            patches.push(unpatch);
            debug(`patched ${label}`);
            return true;
        }
    } catch (e) {
        logger.error(`[StreamQuality] failed to patch ${label}`, e);
    }
    return false;
}

/**
 * Discord builds the Go Live request (source + qualityOptions) and hands it to
 * setGoLiveSource. Rewriting qualityOptions here is the main way the custom
 * numbers reach the media engine.
 */
function patchGoLiveSource() {
    const mod = findByProps("setGoLiveSource");
    if (!mod) return false;

    return safePatch("setGoLiveSource", () =>
        before("setGoLiveSource", mod, (args) => {
            if (!storage.enabled) return;
            const source = args[0];
            if (!source || typeof source !== "object") return;

            const target = getTarget();
            const quality = { ...(source.qualityOptions ?? {}) };

            quality.resolution = target.resolution;
            quality.frameRate = target.fps;
            // Some builds spell it differently; only touch keys that already exist.
            if ("fps" in quality) quality.fps = target.fps;
            if ("framerate" in quality) quality.framerate = target.fps;
            if (storage.customPreset) quality.preset = CUSTOM_PRESET;

            source.qualityOptions = quality;
            debug("setGoLiveSource ->", describeTarget(target), args);
        })
    );
}

/**
 * The last JS hop before the native encoder. Forcing the numbers here bypasses
 * any clamping Discord's quality manager applied on the way down.
 */
function patchEncoder() {
    if (!storage.patchEncoder) return false;

    const Connection = findByName("Connection", false)?.default ?? findByName("Connection");
    const proto = Connection?.prototype;
    if (!proto || typeof proto.setDesktopEncodingOptions !== "function") return false;

    return safePatch("Connection.setDesktopEncodingOptions", () =>
        before("setDesktopEncodingOptions", proto, function (this: any, args) {
            if (!storage.enabled) return;
            // Only touch screen-share connections when the connection says what it is.
            if (this?.context && this.context !== "stream") return;

            const target = getTarget();
            const first = args[0];

            if (first && typeof first === "object") {
                if ("width" in first) first.width = target.width;
                if ("height" in first) first.height = target.height;
                for (const key of ["framerate", "frameRate", "fps"]) {
                    if (key in first) first[key] = target.fps;
                }
            } else if (typeof first === "number") {
                args[0] = target.width;
                if (typeof args[1] === "number") args[1] = target.height;
                if (typeof args[2] === "number") args[2] = target.fps;
            }

            debug("setDesktopEncodingOptions ->", describeTarget(target), args);
        })
    );
}

/**
 * Optional: make the settings store itself report the custom values, for code
 * paths that read preset/resolution/fps from it instead of qualityOptions.
 */
function patchSettingsStore() {
    if (!storage.patchStore) return false;

    const store = findByStoreName("ApplicationStreamingSettingsStore");
    if (!store || typeof store.getState !== "function") return false;

    return safePatch("ApplicationStreamingSettingsStore.getState", () =>
        after("getState", store, (_args, state) => {
            if (!storage.enabled || !state || typeof state !== "object") return state;
            const target = getTarget();
            return {
                ...state,
                preset: storage.customPreset ? CUSTOM_PRESET : state.preset,
                resolution: target.resolution,
                fps: target.fps,
            };
        })
    );
}

export default {
    onLoad: () => {
        initStorage();

        const goLive = patchGoLiveSource();
        const encoder = patchEncoder();
        const store = patchSettingsStore();

        debug("load result", { goLive, encoder, store });

        if (!goLive) {
            logger.error("[StreamQuality] setGoLiveSource module not found; this Discord build is not supported yet.");
            showToast("Stream Quality: Go Live module not found", getAssetIDByName("Small"));
        } else if (storage.patchEncoder && !encoder) {
            logger.log("[StreamQuality] Connection.setDesktopEncodingOptions not found; relying on setGoLiveSource only.");
        }
    },

    onUnload: () => {
        for (const unpatch of patches) {
            try {
                unpatch();
            } catch (e) {
                logger.error("[StreamQuality] failed to unpatch", e);
            }
        }
        patches = [];
    },

    settings: Settings,
};
