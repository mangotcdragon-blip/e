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
    const CALL_MODE = /communicationmode|callmode|callaudio|incallmode|in_call_mode|incallaudio|voipmode|audiomode/i;
    const BARE_MODE = /^set(audio)?mode$/i;
    const COMM_DEVICE = /^set\w*communicationdevice/i;
    const SCO = /bluetoothsco|scoaudio|^startsco|^setsco|scoon$/i;
    const TELECOM = /telecom|connectionservice|phoneaccount|callkit|callkeep|callstyle|ongoingcall|selfmanaged|systemcall|nativecall/i;

    // Discord's native modules follow three naming styles (Native<X>Module, RTN<X>Manager,
    // DCD<X>Manager). Newer Discord builds hide the module list, so probe likely names.
    const NATIVE_STEMS = [
        "Audio", "AudioManager", "AudioSession", "AudioRoute", "AudioDevice", "AudioOutput",
        "AudioFocus", "AudioMode", "Voice", "VoiceEngine", "VoiceConnection", "VoiceManager",
        "MediaEngine", "Media", "Call", "InCall", "CallManager", "CallService", "Telecom",
        "ConnectionService", "Bluetooth", "Sound", "SoundManager", "Speaker", "Rtc", "RTC",
        "WebRTC", "DiscordVoice", "Headset", "Communication",
    ];
    const CANDIDATE_NATIVE_NAMES = [];
    for (const stem of NATIVE_STEMS) {
        for (const name of [`Native${stem}Module`, `Native${stem}`, `RTN${stem}Manager`, `RTN${stem}`,
            `DCD${stem}Manager`, `DCD${stem}`, `${stem}Manager`, `${stem}Module`, stem]) {
            if (!CANDIDATE_NATIVE_NAMES.includes(name)) CANDIDATE_NATIVE_NAMES.push(name);
        }
    }
    CANDIDATE_NATIVE_NAMES.push("InCallManager", "RNCallKeep", "AndroidAudioManager", "DiscordAudioManager");

    // Native module functions can't be listed on newer Discord builds, so probe likely method names too.
    const METHOD_PREFIXES = ["set", "enable", "disable", "start", "stop", "force", "use", "select", "switch",
        "request", "toggle", "update", "enter", "exit", "report", "register", "end", "get", "is"];
    const METHOD_OBJECTS = ["SpeakerphoneOn", "Speakerphone", "SpeakerOn", "Speaker", "CommunicationModeOn",
        "CommunicationMode", "CallMode", "InCallMode", "AudioMode", "Mode", "BluetoothScoOn", "BluetoothSco",
        "Sco", "CommunicationDevice", "AudioDevice", "AudioOutputDevice", "OutputDevice", "AudioRoute", "Route",
        "Telecom", "TelecomCall", "ConnectionService", "VoiceCall", "CallAudio", "AudioSession", "AudioFocus",
        "AudioDevices", "OutputDevices", "AudioRoutes", "Devices", "Routes", "CallAudioEnabled", "UseCallAudio"];
    const PROBE_METHODS = [];
    for (const pre of METHOD_PREFIXES) for (const obj of METHOD_OBJECTS) PROBE_METHODS.push(pre + obj);

    // Discord source files worth scanning, by path.
    const AUDIO_PATH = /audio|voice|speaker|bluetooth|telecom|mediaengine|media_engine|rtc|incall|callkit|connectionservice|\bsco\b|headset/i;
    const SKIP_PATH = /\.(png|jpe?g|svg|json|lottie)$|assets\/|images\/|i18n|intl|locale|messages\//i;

    const unpatches = [];
    const patchedTargets = [];
    // Every audio-like native module that was found, with all of its functions, for diagnostics.
    const nativeSeen = {};
    // Discord source files that matched AUDIO_PATH, with their exported names, for diagnostics.
    const filesSeen = {};
    // Native module names Discord requested after the plugin loaded.
    const turboRequested = new Set();
    // Every Flux action type seen, with a count.
    const fluxTypes = {};
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
    function callModeArgs(args, key) {
        if (args.length === 0) return /mode|sco|communication|callaudio|telecom|connectionservice/i.test(key) ? null : args;
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

    // Own, inherited (class methods) and probed function names of an object.
    function keysOf(target) {
        const keys = [];
        const add = k => { if (typeof k === "string" && k !== "constructor" && !keys.includes(k)) keys.push(k); };
        try {
            for (const k in target) add(k);
            let obj = target;
            for (let depth = 0; obj && depth < 5; depth++) {
                if (obj === Object.prototype || obj === Function.prototype) break;
                for (const k of Object.getOwnPropertyNames(obj)) add(k);
                obj = Object.getPrototypeOf(obj);
            }
        } catch {
            if (!keys.length) return null;
        }
        for (const k of PROBE_METHODS) {
            if (keys.includes(k)) continue;
            try { if (typeof target[k] === "function") keys.push(k); } catch {}
        }
        return keys;
    }

    function patchTarget(target, label) {
        if (!target || (typeof target !== "object" && typeof target !== "function")) return;
        if (patchedTargets.some(p => p.target === target)) return;

        const keys = keysOf(target);
        if (!keys) return;

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
                    const next = rule.drop && args[0] !== false ? null : callModeArgs(args, key);
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
            const keys = keysOf(mod) || [];
            nativeSeen[name] = keys.filter(k => { try { return typeof mod[k] === "function"; } catch { return false; } });
            patchTarget(mod, `Native:${name}`);
        }
    }

    function functionNames(obj) {
        return (keysOf(obj) || []).filter(k => { try { return typeof obj[k] === "function"; } catch { return false; } });
    }

    // Scan Discord's own source files whose path mentions audio/voice/calls.
    function scanFilePaths(deep) {
        const modules = metro.modules || globalThis.modules;
        if (!modules) return;
        for (const id in modules) {
            const mod = modules[id];
            const path = mod && mod.__filePath;
            if (!path || SKIP_PATH.test(path) || !AUDIO_PATH.test(path)) continue;
            let exports;
            if (mod.isInitialized) exports = mod.publicModule && mod.publicModule.exports;
            else if (deep) {
                try { exports = globalThis.__r(Number(id)); } catch (e) { filesSeen[path] = `(failed to load: ${e && e.message})`; continue; }
            } else {
                if (!filesSeen[path]) filesSeen[path] = "(not loaded yet)";
                continue;
            }
            if (!exports || (typeof exports !== "object" && typeof exports !== "function")) continue;

            const short = path.split("/").slice(-2).join("/");
            const summary = [];
            const visit = (obj, name) => {
                if (!obj || (typeof obj !== "object" && typeof obj !== "function")) return;
                let fns = functionNames(obj);
                if (typeof obj === "function" && obj.prototype) {
                    const protoFns = functionNames(obj.prototype);
                    if (protoFns.length) {
                        summary.push(`${name}.prototype{${protoFns.join(",")}}`);
                        patchTarget(obj.prototype, `File:${short}:${name}.prototype`);
                    }
                }
                if (fns.length) summary.push(`${name}{${fns.slice(0, 40).join(",")}}`);
                patchTarget(obj, `File:${short}:${name}`);
                // Stores hand out the media engine object that talks to the native voice code.
                if (typeof obj.getMediaEngine === "function") {
                    try {
                        const engine = obj.getMediaEngine();
                        if (engine) {
                            summary.push(`getMediaEngine(){${functionNames(engine).join(",")}}`);
                            patchTarget(engine, `MediaEngine`);
                        }
                    } catch {}
                }
            };
            visit(exports, "exports");
            for (const key of Object.keys(exports)) {
                let value;
                try { value = exports[key]; } catch { continue; }
                if (value !== exports) visit(value, key);
            }
            filesSeen[path] = summary.join(" ") || "(no functions)";
        }
    }

    // Log (and patch) native modules Discord asks for from now on.
    function hookTurboModuleRegistry() {
        const registries = [];
        try { if (RN.TurboModuleRegistry) registries.push(RN.TurboModuleRegistry); } catch {}
        try {
            const found = metro.findByProps("getEnforcing", "get");
            if (found && !registries.includes(found)) registries.push(found);
        } catch {}
        for (const registry of registries) {
            for (const method of ["get", "getEnforcing"]) {
                safePatch("after", method, registry, (args, ret) => {
                    const name = args[0];
                    if (typeof name !== "string") return ret;
                    turboRequested.add(name);
                    if (ret && MODULE_NAME.test(name)) {
                        nativeSeen[name] = functionNames(ret);
                        patchTarget(ret, `Native:${name}`);
                    }
                    return ret;
                });
            }
        }
    }

    function scanJsModules() {
        let found = [];
        try {
            found = metro.findAll(exp => {
                if (!exp || (typeof exp !== "object" && typeof exp !== "function")) return false;
                let keys;
                try {
                    keys = Object.keys(exp);
                    if (keys.length > 200) return false;
                    const proto = Object.getPrototypeOf(exp);
                    if (proto && proto !== Object.prototype && proto !== Function.prototype) keys = keys.concat(Object.getOwnPropertyNames(proto));
                } catch { return false; }
                if (keys.length === 0) return false;
                const speaker = keys.some(k => SPEAKER_SETTER.test(k) && !INPUT_ONLY.test(k));
                const route = keys.some(k => ROUTE_SETTER.test(k) && AUDIO_CONTEXT.test(k) && !INPUT_ONLY.test(k));
                const call = keys.some(k => callModeRule(k, AUDIO_CONTEXT.test(k)));
                return speaker || route || call;
            }) || [];
        } catch (e) {
            logger.warn("JS module scan failed", e);
        }
        found.forEach((mod, i) => {
            const hint = (keysOf(mod) || []).find(k => callModeRule(k, true) || SPEAKER_SETTER.test(k) || ROUTE_SETTER.test(k));
            patchTarget(mod, `JS:${hint || i}`);
        });
    }

    function watchFlux() {
        if (!FluxDispatcher || typeof FluxDispatcher.dispatch !== "function") return;
        safePatch("before", "dispatch", FluxDispatcher, args => {
            const action = args[0];
            if (!action || typeof action.type !== "string") return;
            fluxTypes[action.type] = (fluxTypes[action.type] || 0) + 1;
            // Voice code may load only when a call starts, so scan again then.
            if (/^(VOICE_CHANNEL_SELECT|RTC_CONNECTION_STATE|CALL_CREATE|CALL_CONNECT)$/.test(action.type)) scheduleScan();
            if (!store.watchFlux || !FLUX_AUDIO.test(action.type)) return;
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

    let scanTimer = null;
    function scheduleScan() {
        if (scanTimer) return;
        scanTimer = setTimeout(() => { scanTimer = null; scan(false); }, 1500);
    }

    function scan(deep) {
        scanNativeModules();
        try { scanFilePaths(deep); } catch (e) { logger.warn("File scan failed", e); }
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
        lines.push("BluetoothAudioFix diagnostics v3");
        lines.push(`File paths available: ${Object.values(metro.modules || {}).some(m => m && m.__filePath)}`);
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
        lines.push("Native modules Discord requested:");
        lines.push("  " + ([...turboRequested].join(", ") || "(none yet)"));
        lines.push("");
        lines.push("Audio/voice source files:");
        const files = Object.keys(filesSeen);
        if (!files.length) lines.push("  (none found - file paths unavailable?)");
        for (const f of files) lines.push(`  ${f}: ${filesSeen[f]}`);
        lines.push("");
        lines.push("Flux action types seen (voice/call/audio related):");
        const types = Object.keys(fluxTypes).filter(t => /VOICE|RTC|CALL|AUDIO|MEDIA|SPEAKER|BLUETOOTH|DEVICE/.test(t));
        lines.push("  " + (types.map(t => `${t}x${fluxTypes[t]}`).join(", ") || "(none yet)"));
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
                    onPress: () => { scan(false); force(); ui.toasts.showToast(`${patchedTargets.length} audio target(s) patched`); },
                }),
                h(FormDivider),
                h(FormRow, {
                    label: "Deep scan",
                    subLabel: "Also load Discord's audio code that hasn't started yet. Try this if the normal scan finds nothing",
                    onPress: () => { scan(true); force(); ui.toasts.showToast(`${patchedTargets.length} audio target(s) patched`); },
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
                h(Text, { selectable: true, style: { fontFamily: "monospace", fontSize: 11, padding: 12, color: "#999" } }, diagnostics().slice(0, 6000)),
            ),
        );
    }

    return {
        onLoad() {
            watchFlux();
            hookTurboModuleRegistry();
            scan(false);
            // Some audio modules are only initialised once voice code runs; scan again shortly after.
            const timer = setTimeout(() => scan(false), 8000);
            unpatches.push(() => { clearTimeout(timer); if (scanTimer) clearTimeout(scanTimer); scanTimer = null; });
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
