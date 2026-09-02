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
    const messagesByChannel = {}; // simulates MessageStore's real backing data

    function applyToMessageStore(action) {
        if (action?.type !== "MESSAGE_CREATE") return;
        const list = (messagesByChannel[action.channelId] ??= []);
        const idx = list.findIndex(m => m.id === action.message.id);
        if (idx === -1) list.push(action.message);
        else list[idx] = action.message; // stable id -> update in place, matches real dedup-by-id behavior
    }

    const stores = {
        ChannelStore: { getChannel: (id) => (id === "999" ? undefined : { type: 1, recipients: ["999"] }) },
        UserStore: {
            getCurrentUser: () => ({ id: "1", username: "me" }),
            getUser: (id) => ({ id, username: "them" }),
        },
        PrivateChannelSortStore: {
            getPrivateChannelIds: () => ["111", "222", "333"],
            emitChangeCount: 0,
            emitChange() { this.emitChangeCount++; },
        },
        MessageStore: {
            getMessages: (channelId) => (messagesByChannel[channelId] ?? []).slice(),
            emitChangeCount: 0,
            emitChange() { this.emitChangeCount++; },
        },
        SelectedChannelStore: { getChannelId: () => "222" },
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
                            applyToMessageStore(action);
                        },
                    },
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

    return { vendetta, stores, messageActions, dispatched, applied, messagesByChannel };
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
    const { vendetta, stores, messageActions, dispatched, applied, messagesByChannel } = makeVendetta({ storesAvailable: true, messageActionsAvailable: true });
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

    // Channel 111 already has real chat history from before it was ever
    // configured as a decoy - simulates the real-world case this bug was
    // about (an existing DM you actually talk in, now used as a decoy).
    messagesByChannel["111"] = [{ id: "real-msg-1", channel_id: "111", content: "hey did you get my last text", author: { id: "999" } }];

    evaled.onUnload();
    const evaled2 = evalPluginLike(vendetta);
    evaled2.onLoad();

    const filtered = stores.PrivateChannelSortStore.getPrivateChannelIds();
    if (JSON.stringify(filtered) !== JSON.stringify(["111"])) throw new Error("configured+locked should filter to decoy ids only, got " + JSON.stringify(filtered));
    if (!dispatched.some(a => a.type === "MESSAGE_CREATE")) throw new Error("expected fake messages to be dispatched");
    if (!applied.some(a => a.type === "MESSAGE_CREATE")) throw new Error("our own fake MESSAGE_CREATE dispatches should reach the stores (FAKE_MARKER lets them through)");
    if (stores.PrivateChannelSortStore.emitChangeCount < 1) throw new Error("onLoad (already configured) should call emitChange() so the DM list actually re-renders - this is the fix for 'nothing changed' on-device");
    console.log(`  emitChange() called ${stores.PrivateChannelSortStore.emitChangeCount}x so far - confirms the DM list is actually told to refresh`);

    console.log("  testing message content filter (the fix for the RangeError crash + real DM disappearing)...");
    const rawStoredMessages = messagesByChannel["111"];
    if (!rawStoredMessages.some(m => m.id === "real-msg-1")) throw new Error("test setup issue: real pre-existing message should still be sitting in the backing store");
    const shownWhileLocked = stores.MessageStore.getMessages("111");
    if (shownWhileLocked.some(m => m.id === "real-msg-1")) throw new Error("real pre-existing message should be hidden from the rendered view while locked");
    if (!shownWhileLocked.some(m => m.id?.startsWith("decoyguard-"))) throw new Error("fake messages should be shown while locked");
    for (const m of shownWhileLocked) {
        if (typeof m.timestamp !== "string" || Number.isNaN(Date.parse(m.timestamp))) {
            throw new Error("fake message timestamp must be a valid ISO string, not a moment object - got " + JSON.stringify(m.timestamp) + " (this is exactly the RangeError crash)");
        }
    }
    console.log("  real history hidden + fake shown while locked, and fake timestamps are valid ISO strings");

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
    const emitCountAfterUnlock = stores.PrivateChannelSortStore.emitChangeCount;
    if (emitCountAfterUnlock < 2) throw new Error("unlocking should also call emitChange() again to refresh the now-unfiltered list");

    const shownAfterUnlock = stores.MessageStore.getMessages("111");
    if (!shownAfterUnlock.some(m => m.id === "real-msg-1")) throw new Error("real message should reappear once unlocked - and critically, it should never have been destroyed to begin with");
    if (shownAfterUnlock.some(m => m.id?.startsWith("decoyguard-"))) throw new Error("fake messages should be hidden once unlocked, not mixed in with real ones");
    console.log("  real history correctly restored (never actually touched) + fake hidden, once unlocked");

    vendetta.__appStateCb("background");
    const relockedIds = stores.PrivateChannelSortStore.getPrivateChannelIds();
    if (JSON.stringify(relockedIds) !== JSON.stringify(["111"])) throw new Error("backgrounding should auto re-lock and re-filter");
    if (stores.PrivateChannelSortStore.emitChangeCount <= emitCountAfterUnlock) throw new Error("auto re-lock on backgrounding should also call emitChange()");

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

console.log("=== Scenario 4: GUI add-channel / add-message / remove flow (no JSON, exactly what tapping the buttons does) ===");
{
    function findByLabel(node, label) {
        if (!node) return null;
        if (Array.isArray(node)) {
            for (const n of node) {
                const found = findByLabel(n, label);
                if (found) return found;
            }
            return null;
        }
        if (node.props?.label === label) return node;
        return findByLabel(node.children, label);
    }

    const { vendetta, stores } = makeVendetta({ storesAvailable: true, messageActionsAvailable: true });
    const evaled = evalPluginLike(vendetta);
    evaled.onLoad();

    let tree = evaled.settings();
    const addCurrentBtn = findByLabel(tree, "+ Add current chat as decoy");
    if (!addCurrentBtn) throw new Error("couldn't find the 'Add current chat as decoy' button in the rendered tree");
    addCurrentBtn.props.onPress(); // SelectedChannelStore mock returns "222"

    if (!vendetta.plugin.storage.channels["222"]) throw new Error("adding the current chat should have added channel 222 to storage");
    const baselineCount = vendetta.plugin.storage.channels["222"].messages.length;
    if (baselineCount === 0) throw new Error("newly added channel should be auto-filled with placeholder messages, got none");
    console.log(`  'Add current chat as decoy' added channel 222 with ${baselineCount} auto-generated placeholder message(s)`);
    if (stores.PrivateChannelSortStore.emitChangeCount < 1) throw new Error("adding a channel from the GUI should call emitChange() to refresh the DM list");

    // Re-render (state lives in the closure over `store`, safe to call settings() again)
    tree = evaled.settings();
    const addAgainBtn = findByLabel(tree, "+ Add current chat as decoy");
    addAgainBtn.props.onPress();
    if (Object.keys(vendetta.plugin.storage.channels).length !== 1) throw new Error("adding the same current chat twice should be a no-op, not a duplicate");
    console.log("  adding the same chat twice is a no-op (duplicate guard works)");

    // The manual-ID "Add" button calls the same addChannel(id) function with
    // whatever was typed - already covered above. Just confirm the
    // unresolvable-channel guard it relies on behaves as expected:
    if (stores.ChannelStore.getChannel("999") !== undefined) throw new Error("test setup issue: channel 999 should be unresolvable");

    // Add a message to channel 222, then remove it.
    tree = evaled.settings();
    // ChannelCard is a nested component - our shallow createElement mock
    // doesn't execute its internal hooks/handlers (no real renderer), so we
    // exercise the storage-mutation helpers the same way ChannelCard's
    // buttons do, via the channel card's own onAddMessage/onRemoveMessage
    // props captured on the ChannelCard element itself.
    function findChannelCard(node, id) {
        if (!node) return null;
        if (Array.isArray(node)) {
            for (const n of node) { const f = findChannelCard(n, id); if (f) return f; }
            return null;
        }
        if (node.props?.id === id && node.props?.onAddMessage) return node;
        return findChannelCard(node.children, id);
    }
    const card = findChannelCard(tree, "222");
    if (!card) throw new Error("couldn't find the ChannelCard element for channel 222");

    card.props.onAddMessage("hey what's up", false);
    if (vendetta.plugin.storage.channels["222"].messages.length !== baselineCount + 1) throw new Error("onAddMessage should push a message");
    card.props.onAddMessage("not much, you?", true);
    if (vendetta.plugin.storage.channels["222"].messages.length !== baselineCount + 2) throw new Error("onAddMessage should push a second message");
    console.log("  add-message flow works (2 manual messages added on top of the placeholders)");

    tree = evaled.settings();
    const card2 = findChannelCard(tree, "222");
    const beforeRemove = vendetta.plugin.storage.channels["222"].messages.length;
    const lastMsg = vendetta.plugin.storage.channels["222"].messages[beforeRemove - 1].text;
    card2.props.onRemoveMessage(0);
    if (vendetta.plugin.storage.channels["222"].messages.length !== beforeRemove - 1) throw new Error("onRemoveMessage should splice out one message");
    if (vendetta.plugin.storage.channels["222"].messages[vendetta.plugin.storage.channels["222"].messages.length - 1].text !== lastMsg) throw new Error("onRemoveMessage removed the wrong message (removed from the wrong end)");
    console.log("  remove-message flow works");

    // Now configure the unlock phrase too and confirm the whole thing actually arms.
    vendetta.plugin.storage.unlockPhrase = "banana bread recipe";
    evaled.onUnload();
    const evaled2 = evalPluginLike(vendetta);
    evaled2.onLoad();
    const filtered = stores.PrivateChannelSortStore.getPrivateChannelIds();
    if (JSON.stringify(filtered) !== JSON.stringify(["222"])) throw new Error("after GUI-driven config, filtering should still kick in - got " + JSON.stringify(filtered));
    console.log("  after GUI-only configuration (no JSON touched), filtering arms correctly");

    tree = evaled2.settings();
    const card3 = findChannelCard(tree, "222");
    card3.props.onRemove();
    if (vendetta.plugin.storage.channels["222"]) throw new Error("onRemove should delete the channel from storage");
    console.log("  remove-channel flow works");

    evaled2.onUnload();
    console.log("PASSED\n");
}

console.log("ALL SMOKE TESTS PASSED");
