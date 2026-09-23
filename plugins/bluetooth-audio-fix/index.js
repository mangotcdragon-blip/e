(() => {
    "use strict";

    const { metro, patcher, plugin, storage: vdStorage, ui, logger } = vendetta;
    const { React, ReactNative: RN, FluxDispatcher, clipboard } = metro.common;

    const store = plugin.storage;
    const defaults = {
        blockCallMode: true,
        blockSco: true,
        blockTelecom: true,
        blockSpeaker: true,
        onlyWhenBluetooth: false,
        rerouteDevice: true,
        watchFlux: true,
        toasts: false,
    };
    for (const key in defaults) if (store[key] === undefined) store[key] = defaults[key];

    // Name patterns used to discover Discord's audio routing code at runtime.
    // Discord's internal names change between versions, so nothing is hardcoded to one module.
    const SPEAKER_SETTER = /^(set|force|enable|toggle|use)\w*speaker/i;
    const ROUTE_SETTER = /^(set|select|switch|change|use|update)\w*(device|route)$/i;
    const DEVICE_GETTER = /^get\w*(devices|routes|device|route)$/i;
    const AUDIO_CONTEXT = /audio|speaker|bluetooth|earpiece|headset|sco|a2dp|communication/i;
    const INPUT_ONLY = /input|mic(rophone)?|capture|record|video|camera/i;
    const SPEAKER_VALUE = /speaker|builtin_?speaker|loudspeaker/i;
    const BLUETOOTH_VALUE = /bluetooth|a2dp|\bbt\b|bt_|_bt|sco|ble_|headset|earbud|buds|airpods|hearing/i;
    const FLUX_AUDIO = /AUDIO|SPEAKER|OUTPUT_DEVICE|MEDIA_ENGINE_SET|BLUETOOTH|VOICE_DEVICE/i;
    const MODULE_NAME = /audio|sound|voice|media|rtc|call|speaker|bluetooth|route|telecom|connection/i;

    // Call mode: Android's MODE_IN_COMMUNICATION, the Bluetooth call profile (SCO) and the
    // system phone call integration (Telecom). Any of these pulls the phone into a call.
    const ACTIVATE = /^(set|enable|start|enter|use|request|force|begin|activate|update|connect|report|register|add|place|show|display|create)/i;
    const DEACTIVATE = /^(stop|disable|exit|leave|end|clear|release|reset|disconnect|unregister|remove|hide)/i;
    const CALL_MODE = /communicationmode|callmode|callaudio|incall|in_call|voicecall|voipmode|audiomode/i;
    const BARE_MODE = /^set(audio)?mode$/i;
    const COMM_DEVICE = /^set\w*communicationdevice/i;
    const SCO = /bluetoothsco|scoaudio|^startsco|^setsco|scoon$/i;
    const TELECOM = /telecom|connectionservice|phoneaccount|callkit|callkeep|callstyle|ongoingcall|selfmanaged|systemcall|nativecall/i;

    const CANDIDATE_NATIVE_NAMES = [
        "DCDAudioManager", "RTNAudioManager", "AudioManager", "AudioManagerModule",
        "DiscordAudioManager", "NativeAudioManager", "AndroidAudioManager", "AudioRouteManager",
        "DCDAudioRouteManager", "MediaEngine", "DCDMediaEngine", "MediaEngineModule",
        "NativeMediaEngine", "VoiceEngine", "DiscordVoice", "DCDVoiceEngine", "NativeVoiceModule",
        "InCallManager", "DCDInCallManager", "RTNAudioSession", "AudioSession", "DCDAudioSession",
        "CallManager", "DCDCallManager", "RTNCallManager", "DCDVoiceModule", "VoiceConnection",
        "DCDTelecom", "TelecomModule", "DCDTelecomManager", "CallConnectionService",
        "DCDConnectionService", "RNCallKeep", "DCDCallKeep", "AudioFocus", "DCDAudioFocus",
        "BluetoothManager", "DCDBluetoothManager", "BluetoothHeadsetModule",
    ];

    const unpatches = [];
    const patchedTargets = [];
    // Every audio-like native module that was found, with all of its functions, for diagnostics.
    const nativeSeen = {};
    const log = [];
    const MAX_LOG = 80;

    // Last known device list entries, filled from device getters and Flux payloads.
    let bluetoothDevice = null;

    function describe(value) {
        try {
            if (value === undefined) return "undefined";
            if (typeof value === "function") return "[fn]";
            if (typeof value !== "object" || value === null) return JSON.stringify(value);
            const str = JSON.stringify(value, (k, v) => (typeof v === "function" ? "[fn]" : v));
            return str && str.length > 300 ? str.slice(0, 300) + "..." : str;
        } catch {
            return String(value);
        }
    }

    function record(kind, where, detail) {
        log.push({ t: new Date().toISOString().slice(11, 19), kind, where, detail });
        if (log.length > MAX_LOG) log.shift();
    }

    function textOf(value) {
        if (value == null) return "";
        if (typeof value === "string") return value;
        if (typeof value !== "object") return "";
        const parts = [];
        for (const key of ["type", "deviceType", "name", "id", "deviceId", "label", "kind", "route", "productName"]) {
            if (typeof value[key] === "string" || typeof value[key] === "number") parts.push(String(value[key]));
        }
        return parts.join(" ");
    }

    function isSpeaker(value) {
        const text = textOf(value);
        return text !== "" && SPEAKER_VALUE.test(text) && !BLUETOOTH_VALUE.test(text);
    }

    function isBluetooth(value) {
        const text = textOf(value);
        return text !== "" && BLUETOOTH_VALUE.test(text);
    }

    function rememberDevices(list, where) {
        if (!Array.isArray(list)) {
            if (list && typeof list === "object") list = Object.values(list);
            else return;
        }
        const bt = list.find(d => isBluetooth(d) && !(d && typeof d === "object" && INPUT_ONLY.test(textOf(d))));
        if (bt) {
            bluetoothDevice = bt;
            record("bt", where, describe(bt));
        } else if (list.length > 0 && list.every(d => textOf(d) !== "")) {
            // A full list without any Bluetooth entry means the earbuds are gone.
            bluetoothDevice = null;
        }
    }

    function bluetoothKnown() {
        return bluetoothDevice != null;
    }

    function shouldBlockSpeaker() {
        if (!store.blockSpeaker) return false;
        return store.onlyWhenBluetooth ? bluetoothKnown() : true;
    }

    function notify(msg) {
        if (store.toasts) {
            try { ui.toasts.showToast(msg); } catch {}
        }
    }

    function safePatch(kind, method, target, cb) {
        try {
            const fn = target[method];
            if (typeof fn !== "function" || fn.__btfixPatched) return false;
            const un = patcher[kind](method, target, cb);
            try { target[method].__btfixPatched = true; } catch {}
            unpatches.push(un);
            return true;
        } catch (e) {
            logger.warn(`Could not patch ${method}`, e);
            return false;
        }
    }

    // Returns the arguments that keep the phone out of call mode, or null to skip the call entirely.
    function callModeArgs(args) {
        if (args.length === 0) return null;
        const first = args[0];
        const next = args.slice();
        if (first === true) next[0] = false;
        else if (first === 2 || first === 3) next[0] = 0; // MODE_IN_CALL / MODE_IN_COMMUNICATION -> MODE_NORMAL
        else if (typeof first === "string" && /communication|call|voip|voice/i.test(first)) {
            next[0] = /^MODE_/.test(first) ? "MODE_NORMAL" : first === first.toUpperCase() ? "NORMAL" : "normal";
        }
        return next;
    }

    function callModeRule(key, hasAudioContext) {
        if (DEACTIVATE.test(key) || /^(get|is|has|should|can)/i.test(key)) return null;
        if (COMM_DEVICE.test(key)) return { setting: "blockCallMode", what: "communication device", drop: true };
        if (SCO.test(key) && ACTIVATE.test(key)) return { setting: "blockSco", what: "Bluetooth call audio (SCO)" };
        if (TELECOM.test(key) && ACTIVATE.test(key)) return { setting: "blockTelecom", what: "system call", drop: true };
        if (CALL_MODE.test(key) && ACTIVATE.test(key)) return { setting: "blockCallMode", what: "call mode" };
        if (hasAudioContext && BARE_MODE.test(key)) return { setting: "blockCallMode", what: "audio mode" };
        return null;
    }

    function patchTarget(target, label) {
        if (!target || (typeof target !== "object" && typeof target !== "function")) return;
        if (patchedTargets.some(p => p.target === target)) return;

        let keys;
        try {
            keys = [];
            for (const k in target) keys.push(k);
            for (const k of Object.getOwnPropertyNames(target)) if (!keys.includes(k)) keys.push(k);
        } catch {
            return;
        }

        const hasAudioContext = AUDIO_CONTEXT.test(label) || keys.some(k => AUDIO_CONTEXT.test(k));
        const methods = [];

        for (const key of keys) {
            let fn;
            try { fn = target[key]; } catch { continue; }
            if (typeof fn !== "function") continue;

            const rule = callModeRule(key, hasAudioContext);
            if (rule) {
                const ok = safePatch("instead", key, target, function (args, orig) {
                    record("call", `${label}.${key}`, args.map(a => describe(a)).join(", "));
                    if (!store[rule.setting]) return orig.apply(this, args);
                    const next = rule.drop && args[0] !== false ? null : callModeArgs(args);
                    if (next === null) {
                        record("block", `${label}.${key}`, `${rule.what} skipped`);
                        notify(`Blocked ${rule.what}`);
                        return Promise.resolve();
                    }
                    if (next.some((v, i) => v !== args[i])) {
                        record("block", `${label}.${key}`, `${rule.what}: ${describe(args[0])} -> ${describe(next[0])}`);
                        notify(`Blocked ${rule.what}`);
                    }
                    return orig.apply(this, next);
                });
                if (ok) methods.push(key);
            } else if (SPEAKER_SETTER.test(key) && !INPUT_ONLY.test(key)) {
                const ok = safePatch("instead", key, target, function (args, orig) {
                    record("call", `${label}.${key}`, args.map(a => describe(a)).join(", "));
                    if (args[0] === true && shouldBlockSpeaker()) {
                        record("block", `${label}.${key}`, "forced speaker -> false");
                        notify("Blocked forced speaker");
                        const next = args.slice();
                        next[0] = false;
                        return orig.apply(this, next);
                    }
                    return orig.apply(this, args);
                });
                if (ok) methods.push(key);
            } else if (hasAudioContext && ROUTE_SETTER.test(key) && !INPUT_ONLY.test(key)) {
                const ok = safePatch("instead", key, target, function (args, orig) {
                    record("call", `${label}.${key}`, args.map(a => describe(a)).join(", "));
                    const idx = args.findIndex(isSpeaker);
                    if (store.rerouteDevice && idx !== -1 && shouldBlockSpeaker()) {
                        if (bluetoothKnown()) {
                            const next = args.slice();
                            const bt = bluetoothDevice;
                            // Keep the argument's shape: pass an id string if the caller used one.
                            next[idx] = typeof args[idx] === "string" && typeof bt === "object"
                                ? (bt.id ?? bt.deviceId ?? bt.type ?? textOf(bt))
                                : bt;
                            record("reroute", `${label}.${key}`, `speaker -> ${describe(next[idx])}`);
                            notify("Kept audio on Bluetooth");
                            return orig.apply(this, next);
                        }
                        if (!store.onlyWhenBluetooth) {
                            record("block", `${label}.${key}`, "speaker route dropped");
                            notify("Blocked switch to speaker");
                            return Promise.resolve();
                        }
                    }
                    return orig.apply(this, args);
                });
                if (ok) methods.push(key);
            } else if (hasAudioContext && DEVICE_GETTER.test(key) && !INPUT_ONLY.test(key)) {
                const ok = safePatch("after", key, target, (args, ret) => {
                    const handle = value => {
                        record("devices", `${label}.${key}`, describe(value));
                        if (Array.isArray(value) || (value && typeof value === "object" && !isSpeaker(value) && !isBluetooth(value))) {
                            rememberDevices(value, `${label}.${key}`);
                        } else if (isBluetooth(value)) {
                            rememberDevices([value], `${label}.${key}`);
                        }
                    };
                    try {
                        if (ret && typeof ret.then === "function") ret.then(handle, () => {});
                        else handle(ret);
                    } catch {}
                    return ret;
                });
                if (ok) methods.push(key);
            }
        }

        if (methods.length) {
            patchedTargets.push({ target, label, methods });
            logger.log(`Patched ${label}: ${methods.join(", ")}`);
        }
    }

    function getNativeModule(name) {
        try {
            const legacy = RN.NativeModules && RN.NativeModules[name];
            if (legacy) return legacy;
        } catch {}
        try {
            if (typeof globalThis.__turboModuleProxy === "function") {
                const turbo = globalThis.__turboModuleProxy(name);
                if (turbo) return turbo;
            }
        } catch {}
        try {
            if (globalThis.nativeModuleProxy && globalThis.nativeModuleProxy[name]) return globalThis.nativeModuleProxy[name];
        } catch {}
        return null;
    }

    function scanNativeModules() {
        const names = new Set(CANDIDATE_NATIVE_NAMES);
        try {
            for (const name of Object.keys(RN.NativeModules || {})) {
                if (MODULE_NAME.test(name)) names.add(name);
            }
        } catch {}
        for (const name of names) {
            const mod = getNativeModule(name);
            if (!mod) continue;
            try {
                const fns = [];
                for (const k in mod) if (typeof mod[k] === "function") fns.push(k);
                nativeSeen[name] = fns;
            } catch {
                nativeSeen[name] = ["(could not list)"];
            }
            patchTarget(mod, `Native:${name}`);
        }
    }

    function scanJsModules() {
        let found = [];
        try {
            found = metro.findAll(exp => {
                if (!exp || (typeof exp !== "object" && typeof exp !== "function")) return false;
                let keys;
                try { keys = Object.keys(exp); } catch { return false; }
                if (keys.length === 0 || keys.length > 200) return false;
                const speaker = keys.some(k => SPEAKER_SETTER.test(k) && !INPUT_ONLY.test(k));
                const route = keys.some(k => ROUTE_SETTER.test(k) && AUDIO_CONTEXT.test(k) && !INPUT_ONLY.test(k));
                const call = keys.some(k => callModeRule(k, AUDIO_CONTEXT.test(k)));
                return speaker || route || call;
            }) || [];
        } catch (e) {
            logger.warn("JS module scan failed", e);
        }
        found.forEach((mod, i) => {
            const hint = Object.keys(mod).find(k => callModeRule(k, true) || SPEAKER_SETTER.test(k) || ROUTE_SETTER.test(k));
            patchTarget(mod, `JS:${hint || i}`);
        });
    }

    function watchFlux() {
        if (!FluxDispatcher || typeof FluxDispatcher.dispatch !== "function") return;
        safePatch("before", "dispatch", FluxDispatcher, args => {
            const action = args[0];
            if (!store.watchFlux || !action || typeof action.type !== "string" || !FLUX_AUDIO.test(action.type)) return;
            const { type, ...payload } = action;
            record("flux", type, describe(payload));
            for (const key of Object.keys(payload)) {
                const value = payload[key];
                if (Array.isArray(value) || (value && typeof value === "object" && /device|route/i.test(key))) {
                    rememberDevices(value, `flux:${type}.${key}`);
                }
            }
        });
    }

    function scan() {
        scanNativeModules();
        scanJsModules();
    }

    // Undo a stuck speaker route: turn every discovered speaker switch off and
    // re-select the Bluetooth device if one has been seen.
    function restoreBluetooth() {
        let calls = 0;
        for (const { target, label, methods } of patchedTargets) {
            for (const m of methods) {
                try {
                    let ret;
                    if (SPEAKER_SETTER.test(m)) {
                        ret = target[m](false);
                        calls++;
                    } else if (ROUTE_SETTER.test(m) && bluetoothKnown()) {
                        ret = target[m](bluetoothDevice);
                        calls++;
                    }
                    if (ret && typeof ret.catch === "function") {
                        ret.catch(e => record("error", `${label}.${m}`, String(e && e.message || e)));
                    }
                } catch (e) {
                    record("error", `${label}.${m}`, String(e && e.message || e));
                }
            }
        }
        record("restore", "manual", `${calls} call(s)`);
        return calls;
    }

    function diagnostics() {
        const lines = [];
        lines.push("BluetoothAudioFix diagnostics");
        lines.push(`Platform: ${RN.Platform.OS} ${RN.Platform.Version}`);
        lines.push(`Settings: ${JSON.stringify({ ...store })}`);
        lines.push(`Bluetooth device: ${bluetoothKnown() ? describe(bluetoothDevice) : "none detected"}`);
        lines.push("");
        lines.push("Patched targets:");
        if (!patchedTargets.length) lines.push("  (none found)");
        for (const p of patchedTargets) lines.push(`  ${p.label}: ${p.methods.join(", ")}`);
        lines.push("");
        lines.push("Audio-like native modules and their functions:");
        const seen = Object.keys(nativeSeen);
        if (!seen.length) lines.push("  (none found)");
        for (const name of seen) lines.push(`  ${name}: ${nativeSeen[name].join(", ") || "(no functions listed)"}`);
        lines.push("");
        lines.push("All native module names:");
        try {
            lines.push("  " + (Object.keys(RN.NativeModules || {}).join(", ") || "(none enumerable)"));
        } catch {
            lines.push("  (not enumerable)");
        }
        lines.push("");
        lines.push("Recent events:");
        for (const e of log) lines.push(`  ${e.t} [${e.kind}] ${e.where} ${e.detail || ""}`);
        return lines.join("\n");
    }

    function Settings() {
        vdStorage.useProxy(store);
        const [, force] = React.useReducer(x => x + 1, 0);
        const { ScrollView, Text } = RN;
        const { FormSection, FormSwitchRow, FormRow, FormDivider } = ui.components.Forms;
        const h = React.createElement;

        const toggle = (key, label, subLabel) => h(FormSwitchRow, {
            label,
            subLabel,
            value: !!store[key],
            onValueChange: v => { store[key] = v; },
        });

        return h(ScrollView, { style: { flex: 1 } },
            h(FormSection, { title: "Call mode" },
                toggle("blockCallMode", "Block call mode", "Stop Discord from switching the phone into call (communication) mode"),
                h(FormDivider),
                toggle("blockSco", "Block Bluetooth call audio", "Keep the earbuds in media mode (full quality); your mic stays on the phone"),
                h(FormDivider),
                toggle("blockTelecom", "Block system call integration", "Stop Discord from registering the voice chat as a phone call"),
            ),
            h(FormSection, { title: "Routing" },
                toggle("blockSpeaker", "Block forced speaker", "Stop Discord from switching the phone speaker on while call audio is off"),
                h(FormDivider),
                toggle("onlyWhenBluetooth", "Only while Bluetooth is detected", "Allow the speaker when no Bluetooth device has been seen"),
                h(FormDivider),
                toggle("rerouteDevice", "Swap speaker for Bluetooth", "When Discord picks the speaker as output, pick the Bluetooth device instead"),
            ),
            h(FormSection, { title: "Actions" },
                h(FormRow, {
                    label: "Send audio back to Bluetooth now",
                    subLabel: "Use this if audio is already stuck on the speaker",
                    onPress: () => {
                        const n = restoreBluetooth();
                        ui.toasts.showToast(n ? "Asked Discord to release the speaker" : "No audio controls found - see diagnostics");
                        force();
                    },
                }),
                h(FormDivider),
                h(FormRow, {
                    label: "Rescan audio modules",
                    onPress: () => { scan(); force(); ui.toasts.showToast(`${patchedTargets.length} audio target(s) patched`); },
                }),
            ),
            h(FormSection, { title: "Diagnostics" },
                toggle("watchFlux", "Log audio events", "Record Discord's audio actions for troubleshooting"),
                h(FormDivider),
                toggle("toasts", "Show toasts", "Pop up a message whenever the plugin blocks something"),
                h(FormDivider),
                h(FormRow, {
                    label: "Copy diagnostics",
                    subLabel: "Paste this when reporting that the fix didn't work",
                    onPress: () => { clipboard.setString(diagnostics()); ui.toasts.showToast("Copied"); },
                }),
                h(Text, { selectable: true, style: { fontFamily: "monospace", fontSize: 11, padding: 12, color: "#999" } }, diagnostics()),
            ),
        );
    }

    return {
        onLoad() {
            watchFlux();
            scan();
            // Some audio modules are only initialised once voice code runs; scan again shortly after.
            const timer = setTimeout(scan, 8000);
            unpatches.push(() => clearTimeout(timer));
            logger.log(`Loaded, ${patchedTargets.length} audio target(s) patched`);
        },
        onUnload() {
            for (const un of unpatches.splice(0)) {
                try { un(); } catch {}
            }
            patchedTargets.length = 0;
        },
        settings: Settings,
    };
})()
