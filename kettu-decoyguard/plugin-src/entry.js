// Kettu Decoy Guard - standalone plugin build (Vendetta/polymanifest format)
//
// This file is bundled into a single IIFE and eval'd inside Kettu with a
// `vendetta` object already in scope (see src/core/vendetta/plugins.ts ->
// evalPlugin in the Kettu source). It intentionally does NOT import
// anything - everything it needs comes off that `vendetta` object.
//
// IMPORTANT: Kettu's plugin loader (VdPluginManager.startPlugin) swallows any
// error thrown during eval or onLoad() completely silently - the toggle just
// snaps back off, no toast, no visible error. So NOTHING in this file is
// allowed to throw past its own boundary: every external lookup and every
// patch attempt is wrapped, failures are recorded into `diagnostics` instead
// of thrown, and that list is rendered at the top of the settings screen so
// you can see exactly what did/didn't work without needing a debugger.

function start() {
    const diagnostics = [];
    function record(step, ok, detail) {
        diagnostics.push({ step, ok, detail: detail ? String(detail) : "" });
        try {
            vendetta.logger[ok ? "log" : "error"](`[DecoyGuard] ${step}: ${ok ? "ok" : "FAILED"}${detail ? " - " + detail : ""}`);
        } catch {}
    }

    function safe(step, fn, fallback) {
        try {
            const result = fn();
            record(step, true);
            return result;
        } catch (e) {
            record(step, false, e?.message ?? e);
            return fallback;
        }
    }

    const { patcher, metro, plugin, logger } = vendetta;
    const { React, ReactNative, FluxDispatcher, moment } = metro.common;
    const { ScrollView, View, TextInput, Text, TouchableOpacity, AppState } = ReactNative;

    const DEFAULT_PHRASE = "CHANGE-ME-IN-SETTINGS";
    const FAKE_MARKER = "kettuDecoyGuardFake";

    // plugin.storage is a plain, synchronously-persisted object (MMKV-backed).
    const store = plugin.storage;
    if (!store.unlockPhrase) store.unlockPhrase = DEFAULT_PHRASE;
    if (!store.channels) store.channels = {};

    // Always starts locked - no "remember unlocked" persistence, on purpose.
    let locked = true;
    const listeners = new Set();
    function setLocked(v) {
        if (locked === v) return;
        locked = v;
        listeners.forEach(l => l());
    }

    function decoyIds() {
        return Object.keys(store.channels);
    }

    // Refuses to hide/block anything until configured (real phrase + at least
    // one decoy channel) - avoids a self-lockout where every DM is hidden with
    // no visible chat left to type the unlock phrase into.
    function isConfigured() {
        return decoyIds().length > 0 && !!store.unlockPhrase && store.unlockPhrase !== DEFAULT_PHRASE;
    }

    function findStore(name) {
        try {
            const s = metro.findByStoreName(name);
            if (!s) throw new Error("not found");
            return s;
        } catch (e) {
            return null;
        }
    }

    function findProps(...props) {
        try {
            const m = metro.findByProps(...props);
            if (!m) throw new Error("not found");
            return m;
        } catch (e) {
            return null;
        }
    }

    function isPrivateChannel(id) {
        try {
            const ChannelStore = findStore("ChannelStore");
            const c = ChannelStore?.getChannel(id);
            return c?.type === 1 || c?.type === 3; // DM or group DM
        } catch {
            return false;
        }
    }

    function otherRecipient(id) {
        try {
            const ChannelStore = findStore("ChannelStore");
            const UserStore = findStore("UserStore");
            const c = ChannelStore?.getChannel(id);
            const rid = c?.recipients?.[0];
            return rid ? UserStore?.getUser(rid) : undefined;
        } catch {
            return undefined;
        }
    }

    function clearChannelMessages(id) {
        try {
            FluxDispatcher.dispatch({
                type: "LOAD_MESSAGES_SUCCESS",
                channelId: id,
                messages: [],
                isBefore: true,
                isAfter: true,
                hasMoreBefore: false,
                hasMoreAfter: false,
            });
        } catch (e) {
            logger.error("[DecoyGuard] clear failed", id, e);
        }
    }

    function injectFakeMessages(id) {
        const cfg = store.channels[id];
        if (!cfg) return;

        const UserStore = findStore("UserStore");
        const me = UserStore?.getCurrentUser();
        const them = otherRecipient(id);
        const now = Date.now();
        const total = cfg.messages.length;

        cfg.messages.forEach((m, i) => {
            const author = m.fromMe ? me : (them ?? me);
            if (!author) return;

            const message = {
                id: `decoyguard-${id}-${i}`,
                channel_id: id,
                author: {
                    id: author.id,
                    username: author.username,
                    discriminator: author.discriminator ?? "0000",
                    avatar: author.avatar ?? null,
                    bot: false,
                    global_name: author.globalName ?? author.username,
                },
                content: m.text,
                timestamp: moment(now - (total - i) * 60_000),
                edited_timestamp: null,
                tts: false,
                mention_everyone: false,
                mentions: [],
                mention_roles: [],
                attachments: [],
                embeds: [],
                reactions: [],
                pinned: false,
                type: 0,
                flags: 0,
            };

            try {
                FluxDispatcher.dispatch({
                    type: "MESSAGE_CREATE",
                    channelId: id,
                    message,
                    optimistic: false,
                    isPushNotification: false,
                    [FAKE_MARKER]: true,
                });
            } catch (e) {
                logger.error("[DecoyGuard] inject failed", id, e);
            }
        });
    }

    function lockDecoyChannels() {
        if (!isConfigured()) return;
        decoyIds().forEach(id => {
            clearChannelMessages(id);
            injectFakeMessages(id);
        });
    }

    function unlockDecoyChannels() {
        decoyIds().forEach(id => {
            clearChannelMessages(id);
            try {
                const MessageActions = findProps("sendMessage");
                MessageActions?.fetchMessages?.({ channelId: id, limit: 50 });
            } catch (e) {
                logger.warn("[DecoyGuard] refetch failed", id, e);
            }
        });
    }

    // FluxDispatcher only has ONE global interceptor slot - chain through
    // whatever was set before us instead of clobbering it.
    let previousInterceptor;
    let interceptorInstalled = false;

    const BLOCKABLE_ACTIONS = new Set([
        "MESSAGE_CREATE",
        "MESSAGE_UPDATE",
        "MESSAGE_DELETE",
        "MESSAGE_REACTION_ADD",
        "MESSAGE_REACTION_REMOVE",
        "LOAD_MESSAGES_SUCCESS",
        "CHANNEL_PINS_UPDATE",
        "TYPING_START",
    ]);

    function myInterceptor(action) {
        if (locked && isConfigured() && !action?.[FAKE_MARKER] && BLOCKABLE_ACTIONS.has(action?.type)) {
            const channelId = action.channelId ?? action.message?.channel_id;
            if (channelId && isPrivateChannel(channelId)) return true;
        }
        return previousInterceptor?.(action);
    }

    function installInterceptor() {
        if (interceptorInstalled) return;
        previousInterceptor = FluxDispatcher._interceptors?.[0];
        FluxDispatcher.setInterceptor(myInterceptor);
        interceptorInstalled = true;
    }

    function restoreInterceptor() {
        if (!interceptorInstalled) return;
        FluxDispatcher.setInterceptor(previousInterceptor);
        interceptorInstalled = false;
        previousInterceptor = undefined;
    }

    let unpatchList = () => {};
    let unpatchUnlock = () => {};
    let appStateSub = null;

    function patchPrivateChannelList() {
        const PrivateChannelSortStore = findStore("PrivateChannelSortStore");
        if (!PrivateChannelSortStore || typeof PrivateChannelSortStore.getPrivateChannelIds !== "function") {
            throw new Error("PrivateChannelSortStore.getPrivateChannelIds not found in this build");
        }
        unpatchList = patcher.after("getPrivateChannelIds", PrivateChannelSortStore, (_args, ret) => {
            if (!locked || !isConfigured() || !Array.isArray(ret)) return ret;
            const allowed = new Set(decoyIds());
            return ret.filter(id => allowed.has(id));
        });
    }

    function patchUnlockTrigger() {
        const MessageActions = findProps("sendMessage");
        if (!MessageActions || typeof MessageActions.sendMessage !== "function") {
            throw new Error("MessageActions.sendMessage not found in this build");
        }
        unpatchUnlock = patcher.instead("sendMessage", MessageActions, function (args, orig) {
            const [, message] = args;
            const content = typeof message?.content === "string" ? message.content.trim() : "";
            const phrase = store.unlockPhrase?.trim();

            if (isConfigured() && phrase && content === phrase) {
                if (locked) {
                    setLocked(false);
                    unlockDecoyChannels();
                    logger.log("[DecoyGuard] unlocked");
                } else {
                    setLocked(true);
                    lockDecoyChannels();
                    logger.log("[DecoyGuard] re-locked");
                }
                return Promise.resolve(undefined); // never actually sent
            }

            return orig.apply(this, args);
        });
    }

    function onAppStateChange(nextState) {
        if (nextState !== "active" && !locked) {
            setLocked(true);
            lockDecoyChannels();
            logger.log("[DecoyGuard] app backgrounded - auto re-locked");
        }
    }

    function Row({ label, subLabel, onPress }) {
        const content = React.createElement(
            View,
            { style: { paddingVertical: 12, paddingHorizontal: 4, borderBottomWidth: 1, borderBottomColor: "#333" } },
            React.createElement(Text, { style: { color: "white", fontSize: 15 } }, label),
            subLabel ? React.createElement(Text, { style: { color: "#999", fontSize: 12, marginTop: 2 } }, subLabel) : null,
        );
        return onPress
            ? React.createElement(TouchableOpacity, { onPress }, content)
            : content;
    }

    function SectionTitle({ children }) {
        return React.createElement(Text, { style: { color: "#999", fontSize: 12, fontWeight: "bold", marginTop: 20, marginBottom: 6, textTransform: "uppercase" } }, children);
    }

    function Settings() {
        const [, forceUpdate] = React.useReducer(n => n + 1, 0);
        React.useEffect(() => {
            const l = () => forceUpdate();
            listeners.add(l);
            return () => listeners.delete(l);
        }, []);

        const [json, setJson] = React.useState(() => JSON.stringify(store.channels, null, 2));
        const [jsonError, setJsonError] = React.useState("");
        const [phrase, setPhrase] = React.useState(store.unlockPhrase);

        function saveJson() {
            try {
                store.channels = JSON.parse(json);
                setJsonError("");
                if (locked) lockDecoyChannels();
            } catch (e) {
                setJsonError("Invalid JSON: " + e.message);
            }
        }

        function savePhrase() {
            const trimmed = phrase.trim();
            if (!trimmed) return;
            store.unlockPhrase = trimmed;
            forceUpdate();
        }

        const anyFailed = diagnostics.some(d => !d.ok);

        return React.createElement(
            ScrollView,
            { style: { flex: 1, backgroundColor: "#000" }, contentContainerStyle: { padding: 16 } },

            anyFailed && React.createElement(
                View,
                { style: { backgroundColor: "#3a1f1f", borderRadius: 6, padding: 10, marginBottom: 16 } },
                React.createElement(Text, { style: { color: "#f04747", fontWeight: "bold", marginBottom: 4 } }, "Some internal lookups failed - functionality below is degraded:"),
                ...diagnostics.filter(d => !d.ok).map(d =>
                    React.createElement(Text, { style: { color: "#f0a0a0", fontSize: 12 } }, `- ${d.step}: ${d.detail}`),
                ),
            ),

            React.createElement(SectionTitle, null, "Status"),
            React.createElement(Row, {
                label: locked ? "Locked - showing decoys" : "Unlocked - showing real DMs",
                subLabel: "Always starts locked on app open/reload. Auto re-locks when backgrounded.",
            }),
            React.createElement(Row, {
                label: locked ? "Force unlock now" : "Force re-lock now",
                onPress: () => {
                    if (locked) {
                        setLocked(false);
                        unlockDecoyChannels();
                    } else {
                        setLocked(true);
                        lockDecoyChannels();
                    }
                    forceUpdate();
                },
            }),

            React.createElement(SectionTitle, null, "Diagnostics"),
            ...diagnostics.map(d =>
                React.createElement(Text, { style: { color: d.ok ? "#43b581" : "#f04747", fontSize: 12, marginBottom: 2 } }, `${d.ok ? "OK" : "FAIL"}  ${d.step}${d.detail ? " - " + d.detail : ""}`),
            ),

            React.createElement(SectionTitle, null, "Unlock phrase"),
            React.createElement(Text, { style: { color: "#999", fontSize: 12, marginBottom: 8 } },
                "Type this into any DM box + send while locked to unlock (intercepted, never actually sent). Send it again while unlocked to re-lock.",
            ),
            React.createElement(TextInput, {
                value: phrase,
                onChangeText: setPhrase,
                placeholder: "your secret phrase",
                placeholderTextColor: "#666",
                style: { color: "white", borderWidth: 1, borderColor: "#555", borderRadius: 4, padding: 8, marginBottom: 8 },
            }),
            React.createElement(Row, { label: "Save phrase", onPress: savePhrase }),

            React.createElement(SectionTitle, null, "Decoy channels (raw JSON)"),
            React.createElement(Text, { style: { color: "#999", fontSize: 12, marginBottom: 8 } },
                'Format: {"CHANNEL_ID": {"label": "your reference only", "messages": [{"fromMe": true, "text": "..."}, {"fromMe": false, "text": "..."}]}}',
            ),
            React.createElement(TextInput, {
                value: json,
                onChangeText: setJson,
                placeholder: "{}",
                placeholderTextColor: "#666",
                multiline: true,
                style: { minHeight: 220, color: "white", textAlignVertical: "top", padding: 8, borderWidth: 1, borderColor: "#555", borderRadius: 4, marginBottom: 8 },
            }),
            jsonError ? React.createElement(Text, { style: { color: "#f04747", marginBottom: 8 } }, jsonError) : null,
            React.createElement(Row, { label: "Save decoy config", onPress: saveJson }),
        );
    }

    return {
        onLoad() {
            try {
                setLocked(true);
                safe("install Flux interceptor", installInterceptor);
                safe("patch DM list filtering", patchPrivateChannelList);
                safe("patch unlock trigger (sendMessage)", patchUnlockTrigger);
                safe("populate decoy content", lockDecoyChannels);
                safe("subscribe to AppState", () => {
                    appStateSub = AppState.addEventListener("change", onAppStateChange);
                });
            } catch (e) {
                // Should be unreachable (every step above is wrapped by safe()),
                // but if something still slips through, log it loudly instead
                // of letting it propagate and silently kill activation.
                logger.error("[DecoyGuard] unexpected error during onLoad", e);
            }
        },
        onUnload() {
            try { unpatchList(); } catch (e) { logger.error("[DecoyGuard] unpatchList failed", e); }
            try { unpatchUnlock(); } catch (e) { logger.error("[DecoyGuard] unpatchUnlock failed", e); }
            try { restoreInterceptor(); } catch (e) { logger.error("[DecoyGuard] restoreInterceptor failed", e); }
            try { appStateSub?.remove(); } catch {}
            appStateSub = null;
        },
        settings: Settings,
    };
}

let started;
try {
    started = start();
} catch (e) {
    // Absolute last resort: if even start() itself throws (e.g. the
    // `vendetta` object's shape is unexpectedly different), still return
    // something that ACTIVATES and tells you what happened, instead of
    // silently failing to enable with zero feedback.
    started = {
        onLoad() {},
        onUnload() {},
        settings() {
            const { React, ReactNative } = vendetta.metro.common;
            return React.createElement(
                ReactNative.View,
                { style: { flex: 1, padding: 16, backgroundColor: "#000" } },
                React.createElement(ReactNative.Text, { style: { color: "#f04747", fontWeight: "bold", marginBottom: 8 } }, "Decoy Guard failed to initialize"),
                React.createElement(ReactNative.Text, { style: { color: "#f0a0a0" } }, String(e?.stack ?? e)),
            );
        },
    };
}

export default started;
