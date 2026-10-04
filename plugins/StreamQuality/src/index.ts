import { logger } from "@vendetta";
import { findByName, findByProps, findByStoreName } from "@vendetta/metro";
import { after, before, instead } from "@vendetta/patcher";
import { storage } from "@vendetta/plugin";
import { showToast } from "@vendetta/ui/toasts";
import { getAssetIDByName } from "@vendetta/ui/assets";

import Settings from "./Settings";
import { describeTarget, getTarget, getTargetPreset, initStorage } from "./config";

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

// ---------------------------------------------------------------------------
// Discord modules (names from modules/go_live in the mobile bundle)
// ---------------------------------------------------------------------------

/** actions/AudioActionCreators: setGoLiveSource({desktopSettings, qualityOptions, context}) */
const AudioActions = () => findByProps("setGoLiveSource", "setVideoEnabled", "setUseSystemScreensharePicker");
/** modules/go_live/utils/getStreamSettingsForPreset: default, getMaxSettingsForPreset, canStreamWithPreset */
const PresetUtils = () => findByProps("getApplicationStreamPresetValues", "getMaxSettingsForPreset");
/** modules/go_live/utils/canStreamWithSettings: default(preset, resolution, fps, user, tier) */
const CanStreamWithSettings = () => findByName("canStreamWithSettings", false);
/** modules/go_live/native/MobileGoLiveActionSheet: default (memo), showMobileGoLiveActionSheet */
const SheetModule = () => findByProps("showMobileGoLiveActionSheet");
/** stores/ApplicationStreamingSettingsStore: getState() -> {preset, resolution, fps, soundshareEnabled} */
const SettingsStore = () => findByStoreName("ApplicationStreamingSettingsStore");
/** updateStreamSettings({preset, resolution, frameRate, soundshareEnabled, noTrack?}) */
const StreamSettingsActions = () => findByProps("updateStreamSettings");

function storePreset(): number | undefined {
    try {
        return SettingsStore()?.getState?.()?.preset;
    } catch {
        return undefined;
    }
}

/** Should the custom values apply to a share tagged with this preset? */
function applies(preset?: number) {
    if (!storage.enabled) return false;
    if (!storage.onlyWhenSelected) return true;
    const target = getTargetPreset();
    return preset === target || (preset == null && storePreset() === target);
}

/** Push the current custom values into Discord's persisted stream settings store. */
export function syncStore() {
    try {
        const store = SettingsStore();
        const actions = StreamSettingsActions();
        if (!store?.getState || typeof actions?.updateStreamSettings !== "function") return;
        const state = store.getState();
        const target = getTargetPreset();
        if (!storage.enabled || state?.preset !== target) return;
        const t = getTarget();
        if (state.resolution === t.resolution && state.fps === t.fps) return;
        actions.updateStreamSettings({
            preset: target,
            resolution: t.resolution,
            frameRate: t.fps,
            soundshareEnabled: state.soundshareEnabled,
            noTrack: true,
        });
        debug("synced store", describeTarget(t));
    } catch (e) {
        logger.error("[StreamQuality] syncStore failed", e);
    }
}

/** Put Discord's stock values back for the row we took over. */
function restoreStore() {
    try {
        const store = SettingsStore();
        const actions = StreamSettingsActions();
        const utils = PresetUtils();
        if (!store?.getState || !actions?.updateStreamSettings) return;
        const state = store.getState();
        const target = getTargetPreset();
        if (state?.preset !== target) return;
        const stock = utils?.getMaxSettingsForPreset?.(target);
        if (!stock) return;
        actions.updateStreamSettings({
            preset: target,
            resolution: stock.resolution,
            frameRate: stock.fps,
            soundshareEnabled: state.soundshareEnabled,
            noTrack: true,
        });
    } catch (e) {
        logger.error("[StreamQuality] restoreStore failed", e);
    }
}

// ---------------------------------------------------------------------------
// 1. Engine input: the Go Live request Discord hands to the media engine
// ---------------------------------------------------------------------------
function patchGoLiveSource() {
    const mod = AudioActions() ?? findByProps("setGoLiveSource");
    if (!mod) return false;

    return safePatch("AudioActionCreators.setGoLiveSource", () =>
        before("setGoLiveSource", mod, (args) => {
            const settings = args[0];
            if (!settings || typeof settings !== "object") return;
            const quality = settings.qualityOptions;
            if (!applies(quality?.preset)) return;

            const t = getTarget();
            settings.qualityOptions = {
                ...(quality ?? {}),
                preset: getTargetPreset(),
                resolution: t.resolution,
                frameRate: t.fps,
            };
            debug("setGoLiveSource ->", describeTarget(t), settings.qualityOptions);
        })
    );
}

// ---------------------------------------------------------------------------
// 2. Share sheet data: what the preset row resolves to, and whether it's allowed
// ---------------------------------------------------------------------------
function patchPresetUtils() {
    const utils = PresetUtils();
    if (!utils) return false;
    let ok = false;

    // Used by the sheet's row renderer for the "1080p · 60 FPS" sub label.
    if (typeof utils.getMaxSettingsForPreset === "function") {
        ok = safePatch("getMaxSettingsForPreset", () =>
            after("getMaxSettingsForPreset", utils, ([preset], res) => {
                if (!storage.enabled || preset !== getTargetPreset()) return res;
                const t = getTarget();
                return { ...(res ?? {}), resolution: t.resolution, fps: t.fps };
            })
        ) || ok;
    }

    // Used when a row is tapped: returns [resolution, fps] that go into the store.
    if (typeof utils.default === "function") {
        ok = safePatch("getStreamSettingsForPreset", () =>
            after("default", utils, ([preset], res) => {
                if (!storage.enabled || preset !== getTargetPreset()) return res;
                const t = getTarget();
                return [t.resolution, t.fps];
            })
        ) || ok;
    }

    // Gate for the row (Nitro / boost checks). Our row is always allowed.
    if (typeof utils.canStreamWithPreset === "function") {
        ok = safePatch("canStreamWithPreset", () =>
            after("canStreamWithPreset", utils, ([preset], res) => {
                if (!storage.enabled || preset !== getTargetPreset()) return res;
                return true;
            })
        ) || ok;
    }

    return ok;
}

function patchCanStreamWithSettings() {
    const mod = CanStreamWithSettings();
    if (!mod || typeof mod.default !== "function") return false;

    return safePatch("canStreamWithSettings", () =>
        instead("default", mod, (args, orig) => {
            if (storage.enabled && args[0] === getTargetPreset()) return true;
            return orig(...args);
        })
    );
}

// ---------------------------------------------------------------------------
// 3. Share sheet UI: relabel the row we took over
// ---------------------------------------------------------------------------
function walk(node: any, visit: (el: any) => void, depth = 0) {
    if (!node || depth > 60) return;
    if (Array.isArray(node)) {
        for (const child of node) walk(child, visit, depth + 1);
        return;
    }
    if (typeof node !== "object") return;
    visit(node);
    const props = node.props;
    if (props && typeof props === "object") walk(props.children, visit, depth + 1);
}

function relabelTree(tree: any) {
    if (!storage.enabled) return tree;
    const target = getTargetPreset();
    const t = getTarget();
    walk(tree, (el) => {
        const p = el?.props;
        if (!p || p.value !== target || !("label" in p)) return;
        try {
            p.label = "Custom quality";
            p.subLabel = `${describeTarget(t)} (Stream Quality plugin)`;
        } catch (e) {
            debug("could not relabel row", e);
        }
    });
    return tree;
}

function patchSheet() {
    const mod = SheetModule();
    const sheet = mod?.default;
    if (!sheet) return false;

    // React.memo wrapper: patch the inner function it holds.
    if (typeof sheet === "object" && typeof sheet.type === "function") {
        return safePatch("MobileGoLiveActionSheet (memo)", () =>
            after("type", sheet, (_args, tree) => relabelTree(tree))
        );
    }
    if (typeof sheet === "function") {
        return safePatch("MobileGoLiveActionSheet", () =>
            after("default", mod, (_args, tree) => relabelTree(tree))
        );
    }
    return false;
}

// ---------------------------------------------------------------------------
// 4. Encoder backstop: the last JS hop before the native encoder
// ---------------------------------------------------------------------------
function patchEncoder() {
    if (!storage.patchEncoder) return false;

    const Connection = findByName("Connection", false)?.default ?? findByName("Connection");
    const proto = Connection?.prototype;
    if (!proto || typeof proto.setDesktopEncodingOptions !== "function") return false;

    return safePatch("Connection.setDesktopEncodingOptions", () =>
        before("setDesktopEncodingOptions", proto, function (this: any, args) {
            if (!applies()) return;
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

export default {
    onLoad: () => {
        initStorage();

        const result = {
            goLive: patchGoLiveSource(),
            presetUtils: patchPresetUtils(),
            canStream: patchCanStreamWithSettings(),
            sheet: patchSheet(),
            encoder: patchEncoder(),
        };
        debug("load result", result);
        syncStore();

        if (!result.goLive) {
            logger.error("[StreamQuality] setGoLiveSource module not found; this Discord build is not supported yet.");
            showToast("Stream Quality: Go Live module not found", getAssetIDByName("Small"));
        } else if (!result.presetUtils || !result.sheet) {
            logger.log("[StreamQuality] share sheet modules not found; the sheet will not show the custom row", result);
            showToast("Stream Quality: share sheet not patched, see debug log", getAssetIDByName("Small"));
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
        restoreStore();
    },

    settings: Settings,
};
