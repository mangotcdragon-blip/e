const fs = require("fs");
const pluginJs = fs.readFileSync(__dirname + "/index.js", "utf8");

function makeReactMock() {
    return {
        useReducer: (fn, init) => [init, () => {}],
        useEffect: (fn) => { const cleanup = fn(); if (typeof cleanup === "function") cleanup(); },
        useState: (initOrFn) => {
            const v = typeof initOrFn === "function" ? initOrFn() : initOrFn;
            return [v, () => {}];
        },
        createElement: (type, props, ...children) => ({ type, props, children }),
    };
}

function makeVendetta({ storesAvailable, messageActionsAvailable, dispatcherHasSetter = false }) {
    const interceptorHolder = {};
    const dispatched = [];
    const applied = [];

    const stores = {
        ChannelStore: { getChannel: (id) => ({ type: 1, recipients: ["999"] }) },
        UserStore: {
            getCurrentUser: () => ({ id: "1", username: "me" }),
            getUser: (id) => ({ id, username: "them" }),
        },
        PrivateChannelSortStore: { getPrivateChannelIds: () => ["111", "222", "333"] },
    };

    const messageActions = {
        sendMessage: (channelId, message) => ({ sent: true, channelId, message }),
        fetchMessages: (opts) => ({ fetched: opts }),
    };

    const patches = [];

    const vendetta = {
        patcher: {
            after: (fn, parent, cb) => {
                if (!parent) throw new TypeError(`Cannot read properties of undefined (patching '${fn}')`);
                const orig = parent[fn];
                parent[fn] = (...args) => {
                    const ret = orig.apply(parent, args);
                    return cb(args, ret) ?? ret;
                };
                const unpatch = () => { parent[fn] = orig; };
                patches.push(unpatch);
                return unpatch;
            },
            before: () => () => {},
            instead: (fn, parent, cb) => {
                if (!parent) throw new TypeError(`Cannot read properties of undefined (patching '${fn}')`);
                const orig = parent[fn];
                parent[fn] = function (...args) {
                    return cb.call(this, args, orig.bind(parent));
                };
                const unpatch = () => { parent[fn] = orig; };
                patches.push(unpatch);
                return unpatch;
            },
        },
        metro: {
            findByStoreName: (name) => (storesAvailable ? stores[name] : undefined),
            findByProps: (...props) => (messageActionsAvailable ? messageActions : undefined),
            common: {
                React: makeReactMock(),
                ReactNative: {
                    ScrollView: "ScrollView",
                    View: "View",
                    TouchableOpacity: "TouchableOpacity",
                    TextInput: "TextInput",
                    Text: "Text",
                    AppState: {
                        addEventListener: (evt, cb) => {
                            vendetta.__appStateCb = cb;
                            return { remove: () => { vendetta.__appStateCb = null; } };
                        },
                    },
                },
                FluxDispatcher: dispatcherHasSetter
                    ? {
                        _interceptors: [],
                        dispatch: (action) => {
                            dispatched.push(action);
                            if (interceptorHolder.fn) {
                                const blocked = interceptorHolder.fn(action);
                                if (blocked) return;
                            }
                        },
                        setInterceptor: (fn) => { interceptorHolder.fn = fn; },
                    }
                    : {
                        // Matches the real device: no setInterceptor method at
                        // all, just a plain array that dispatch() runs
                        // through, stopping at the first truthy return.
                        _interceptors: [],
                        dispatch(action) {
                            dispatched.push(action);
                            for (const fn of this._interceptors) {
                                if (fn(action)) return; // blocked - never "applied"
                            }
                            applied.push(action); // reached stores
                        },
                    },
                moment: (ms) => ({ valueOf: () => ms }),
            },
        },
        plugin: { id: "test/", manifest: { name: "Decoy Guard Test" }, storage: {} },
        logger: {
            log: (...a) => console.log("  [log]", ...a),
            warn: (...a) => console.log("  [warn]", ...a),
            error: (...a) => console.log("  [error]", ...a),
        },
        ui: { components: {}, alerts: { showInputAlert: () => {} } },
    };

    return { vendetta, stores, messageActions, dispatched, applied };
}

function evalPluginLike(vendetta) {
    // Exactly mirrors evalPlugin() from Kettu's src/core/vendetta/plugins.ts
    const pluginString = `vendetta=>{return ${pluginJs}}\n//# sourceURL=test`;
    const raw = (0, eval)(pluginString)(vendetta);
    const ret = typeof raw === "function" ? raw() : raw;
    return ret?.default ?? ret ?? {};
}

console.log("=== Scenario 1: everything available (happy path) ===");
{
    const { vendetta, stores, messageActions, dispatched, applied } = makeVendetta({ storesAvailable: true, messageActionsAvailable: true });
    const evaled = evalPluginLike(vendetta);

    if (typeof evaled.onLoad !== "function") throw new Error("onLoad missing");
    if (typeof evaled.onUnload !== "function") throw new Error("onUnload missing");
    if (typeof evaled.settings !== "function") throw new Error("settings missing");

    evaled.onLoad(); // unconfigured - should no-op safely
    if (stores.PrivateChannelSortStore.getPrivateChannelIds().length !== 3) throw new Error("unconfigured onLoad should not filter");

    const tree1 = evaled.settings();
    if (!tree1) throw new Error("settings() should render something");

    vendetta.plugin.storage.unlockPhrase = "banana bread recipe";
    vendetta.plugin.storage.channels = { "111": { label: "friend", messages: [{ fromMe: false, text: "yo" }, { fromMe: true, text: "hey" }] } };

    evaled.onUnload();
    const evaled2 = evalPluginLike(vendetta);
    evaled2.onLoad();

    const filtered = stores.PrivateChannelSortStore.getPrivateChannelIds();
    if (JSON.stringify(filtered) !== JSON.stringify(["111"])) throw new Error("configured+locked should filter to decoy ids only, got " + JSON.stringify(filtered));
    if (!dispatched.some(a => a.type === "MESSAGE_CREATE")) throw new Error("expected fake messages to be dispatched");
    if (!applied.some(a => a.type === "MESSAGE_CREATE")) throw new Error("our own fake MESSAGE_CREATE dispatches should reach the stores (FAKE_MARKER lets them through)");

    console.log("  testing real-time blocking via the array-based interceptor (the actual fix for this device)...");
    const appliedCountBefore = applied.length;
    vendetta.metro.common.FluxDispatcher.dispatch({ type: "MESSAGE_CREATE", channelId: "222", message: { channel_id: "222", content: "real incoming text on a HIDDEN channel" } });
    vendetta.metro.common.FluxDispatcher.dispatch({ type: "MESSAGE_CREATE", channelId: "111", message: { channel_id: "111", content: "real incoming text on the DECOY channel itself" } });
    if (applied.length !== appliedCountBefore) throw new Error("real-time messages on private channels should be blocked while locked, but " + (applied.length - appliedCountBefore) + " got through");
    console.log("  confirmed: real-time DM traffic is blocked while locked via direct _interceptors array push");

    const sendResult = messageActions.sendMessage("111", { content: "banana bread recipe" });
    if (!(sendResult instanceof Promise)) throw new Error("unlock phrase should have been intercepted (promise), got " + JSON.stringify(sendResult));

    const unlockedIds = stores.PrivateChannelSortStore.getPrivateChannelIds();
    if (unlockedIds.length !== 3) throw new Error("after unlock, full DM list should be restored");

    vendetta.__appStateCb("background");
    const relockedIds = stores.PrivateChannelSortStore.getPrivateChannelIds();
    if (JSON.stringify(relockedIds) !== JSON.stringify(["111"])) throw new Error("backgrounding should auto re-lock and re-filter");

    evaled2.onUnload();
    const afterUnload = stores.PrivateChannelSortStore.getPrivateChannelIds();
    if (afterUnload.length !== 3) throw new Error("onUnload should remove the list-filter patch");

    console.log("PASSED\n");
}

console.log("=== Scenario 2: PrivateChannelSortStore/MessageActions NOT found (simulates wrong internal names) ===");
{
    const { vendetta } = makeVendetta({ storesAvailable: false, messageActionsAvailable: false });
    vendetta.plugin.storage.unlockPhrase = "banana bread recipe";
    vendetta.plugin.storage.channels = { "111": { label: "friend", messages: [{ fromMe: true, text: "hi" }] } };

    const evaled = evalPluginLike(vendetta);
    if (typeof evaled.onLoad !== "function") throw new Error("onLoad missing even in degraded mode - this is the bug that caused silent activation failure");

    // The whole point of this scenario: onLoad must NOT throw, so Kettu's
    // startPlugin() doesn't catch an error and silently flip enabled=false.
    evaled.onLoad();
    console.log("  onLoad() completed without throwing - plugin would stay enabled in real Kettu");

    const tree = evaled.settings();
    if (!tree) throw new Error("settings() should still render in degraded mode");
    console.log("  settings() still renders in degraded mode (diagnostics visible there)");

    evaled.onUnload(); // must also not throw
    console.log("PASSED\n");
}

console.log("=== Scenario 3: start() itself throws (absolute worst case) ===");
{
    const { vendetta } = makeVendetta({ storesAvailable: true, messageActionsAvailable: true });
    // Break something start() unconditionally touches at eval time.
    delete vendetta.plugin.storage;
    Object.defineProperty(vendetta.plugin, "storage", { get() { throw new Error("simulated storage failure"); } });

    const evaled = evalPluginLike(vendetta);
    if (typeof evaled.onLoad !== "function" || typeof evaled.settings !== "function") {
        throw new Error("even the last-resort fallback object should expose onLoad/onUnload/settings");
    }
    evaled.onLoad(); // no-op fallback, must not throw
    const tree = evaled.settings(); // should render the "failed to initialize" screen
    if (!tree) throw new Error("fallback settings() should still render something");
    console.log("  fallback plugin object activates cleanly and explains the failure in its settings screen");
    console.log("PASSED\n");
}

console.log("ALL SMOKE TESTS PASSED");
