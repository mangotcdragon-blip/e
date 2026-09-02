const fs = require("fs");
const pluginJs = fs.readFileSync(__dirname + "/index.js", "utf8");

// --- minimal mock of the `vendetta` object Kettu injects ---
let interceptor;
const dispatched = [];

function makeReactMock() {
    let hookCalls = 0;
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

const stores = {
    ChannelStore: {
        getChannel: (id) => ({ type: 1, recipients: ["999"] }),
    },
    UserStore: {
        getCurrentUser: () => ({ id: "1", username: "me" }),
        getUser: (id) => ({ id, username: "them" }),
    },
    PrivateChannelSortStore: {
        getPrivateChannelIds: () => ["111", "222", "333"],
    },
};

const messageActions = {
    sendMessage: (channelId, message) => ({ sent: true, channelId, message }),
    fetchMessages: (opts) => ({ fetched: opts }),
};

const patches = [];

const vendetta = {
    patcher: {
        after: (fn, parent, cb) => {
            const orig = parent[fn];
            parent[fn] = (...args) => {
                const ret = orig.apply(parent, args);
                return cb(args, ret) ?? ret;
            };
            const unpatch = () => { parent[fn] = orig; };
            patches.push(unpatch);
            return unpatch;
        },
        before: (fn, parent, cb) => () => {},
        instead: (fn, parent, cb) => {
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
        findByStoreName: (name) => stores[name],
        findByProps: (...props) => messageActions,
        common: {
            React: makeReactMock(),
            ReactNative: {
                ScrollView: "ScrollView",
                TextInput: "TextInput",
                Text: "Text",
                AppState: {
                    addEventListener: (evt, cb) => {
                        vendetta.__appStateCb = cb;
                        return { remove: () => { vendetta.__appStateCb = null; } };
                    },
                },
            },
            FluxDispatcher: {
                _interceptors: [],
                dispatch: (action) => {
                    dispatched.push(action);
                    if (interceptor) {
                        const blocked = interceptor(action);
                        if (blocked) return;
                    }
                },
                setInterceptor: (fn) => { interceptor = fn; },
            },
            moment: (ms) => ({ valueOf: () => ms }),
        },
    },
    plugin: {
        id: "test/",
        manifest: { name: "Decoy Guard Test" },
        storage: {},
    },
    logger: {
        log: (...a) => console.log("[log]", ...a),
        warn: (...a) => console.log("[warn]", ...a),
        error: (...a) => console.log("[error]", ...a),
    },
    ui: {
        components: {
            Forms: {
                FormSection: "FormSection",
                FormRow: "FormRow",
                FormDivider: "FormDivider",
                FormText: "FormText",
            },
        },
        alerts: {
            showInputAlert: (opts) => { console.log("[showInputAlert]", opts.title); },
        },
    },
};

// --- exactly mirror evalPlugin() from Kettu's src/core/vendetta/plugins.ts ---
const pluginString = `vendetta=>{return ${pluginJs}}\n//# sourceURL=test`;
const raw = (0, eval)(pluginString)(vendetta);
const ret = typeof raw === "function" ? raw() : raw;
const evaled = ret?.default ?? ret ?? {};

console.log("evaled keys:", Object.keys(evaled));
if (typeof evaled.onLoad !== "function") throw new Error("onLoad missing/not a function");
if (typeof evaled.onUnload !== "function") throw new Error("onUnload missing/not a function");
if (typeof evaled.settings !== "function") throw new Error("settings missing/not a function");

console.log("\n--- calling onLoad() unconfigured (should no-op safely) ---");
evaled.onLoad();
console.log("private channel ids after onLoad (unconfigured):", stores.PrivateChannelSortStore.getPrivateChannelIds());

console.log("\n--- rendering settings() unconfigured ---");
const tree1 = evaled.settings();
console.log("settings() rendered ok:", !!tree1);

console.log("\n--- configuring via storage then re-checking filtering ---");
vendetta.plugin.storage.unlockPhrase = "banana bread recipe";
vendetta.plugin.storage.channels = {
    "111": { label: "friend", messages: [{ fromMe: false, text: "yo" }, { fromMe: true, text: "hey" }] },
};
// force a fresh onLoad-equivalent recheck by calling lock behavior indirectly:
// re-run onUnload+onLoad to simulate a reload now that it's configured
evaled.onUnload();
const evaled2 = ((0, eval)(`vendetta=>{return ${pluginJs}}\n//# sourceURL=test2`))(vendetta);
const ret2 = typeof evaled2 === "function" ? evaled2() : evaled2;
const plugin2 = ret2?.default ?? ret2 ?? {};
plugin2.onLoad();

console.log("private channel ids after onLoad (configured, locked):", stores.PrivateChannelSortStore.getPrivateChannelIds());
console.log("dispatched action types so far:", dispatched.map(a => a.type));

console.log("\n--- simulating unlock phrase send ---");
const sendResult = messageActions.sendMessage("111", { content: "banana bread recipe" });
console.log("sendMessage intercepted result (should be a Promise, not {sent:true}):", sendResult);
console.log("private channel ids after unlock:", stores.PrivateChannelSortStore.getPrivateChannelIds());

console.log("\n--- simulating background -> auto re-lock ---");
vendetta.__appStateCb("background");
console.log("private channel ids after backgrounding:", stores.PrivateChannelSortStore.getPrivateChannelIds());

console.log("\n--- unloading ---");
plugin2.onUnload();
console.log("private channel ids after unload (patches removed):", stores.PrivateChannelSortStore.getPrivateChannelIds());

console.log("\nALL SMOKE TESTS PASSED");
