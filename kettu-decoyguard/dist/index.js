(() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // entry.js
  var entry_exports = {};
  __export(entry_exports, {
    default: () => entry_default
  });
  function start() {
    const diagnostics = [];
    function record(step, ok, detail) {
      diagnostics.push({ step, ok, detail: detail ? String(detail) : "" });
      try {
        vendetta.logger[ok ? "log" : "error"](`[DecoyGuard] ${step}: ${ok ? "ok" : "FAILED"}${detail ? " - " + detail : ""}`);
      } catch {
      }
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
    const { React, ReactNative, FluxDispatcher } = metro.common;
    const { ScrollView, View, TextInput, Text, TouchableOpacity, AppState } = ReactNative;
    const DEFAULT_PHRASE = "CHANGE-ME-IN-SETTINGS";
    const FAKE_MARKER = "kettuDecoyGuardFake";
    const store = plugin.storage;
    if (!store.unlockPhrase)
      store.unlockPhrase = DEFAULT_PHRASE;
    if (!store.channels)
      store.channels = {};
    let locked = true;
    const listeners = /* @__PURE__ */ new Set();
    let sortStoreRef = null;
    function notifyChannelListChanged() {
      try {
        sortStoreRef?.emitChange?.();
      } catch (e) {
        logger.warn("[DecoyGuard] emitChange on PrivateChannelSortStore failed", e);
      }
    }
    let messageStoreRef = null;
    function notifyMessagesChanged() {
      try {
        messageStoreRef?.emitChange?.();
      } catch (e) {
        logger.warn("[DecoyGuard] emitChange on MessageStore failed", e);
      }
    }
    function setLocked(v) {
      if (locked === v)
        return;
      locked = v;
      listeners.forEach((l) => l());
      notifyChannelListChanged();
      notifyMessagesChanged();
    }
    function decoyIds() {
      return Object.keys(store.channels);
    }
    function isConfigured() {
      return decoyIds().length > 0 && !!store.unlockPhrase && store.unlockPhrase !== DEFAULT_PHRASE;
    }
    function findStore(name) {
      try {
        const s = metro.findByStoreName(name);
        if (!s)
          throw new Error("not found");
        return s;
      } catch (e) {
        return null;
      }
    }
    function findProps(...props) {
      try {
        const m = metro.findByProps(...props);
        if (!m)
          throw new Error("not found");
        return m;
      } catch (e) {
        return null;
      }
    }
    function isPrivateChannel(id) {
      try {
        const ChannelStore = findStore("ChannelStore");
        const c = ChannelStore?.getChannel(id);
        return c?.type === 1 || c?.type === 3;
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
        return rid ? UserStore?.getUser(rid) : void 0;
      } catch {
        return void 0;
      }
    }
    function getSelectedChannelId() {
      try {
        const SelectedChannelStore = findStore("SelectedChannelStore");
        return SelectedChannelStore?.getChannelId?.() ?? null;
      } catch {
        return null;
      }
    }
    function describeChannel(id) {
      try {
        const ChannelStore = findStore("ChannelStore");
        const c = ChannelStore?.getChannel(id);
        if (!c)
          return { exists: false, label: null };
        if (c.type === 1) {
          const other = otherRecipient(id);
          return { exists: true, label: other?.username ? `DM with ${other.username}` : `DM (${id})` };
        }
        if (c.type === 3) {
          return { exists: true, label: c.name ? `Group: ${c.name}` : `Group DM (${id})` };
        }
        return { exists: false, label: null };
      } catch {
        return { exists: false, label: null };
      }
    }
    const PLACEHOLDER_EXCHANGES = [
      [
        { fromMe: false, text: "yo you doing anything this weekend" },
        { fromMe: true, text: "not really, why what's up" },
        { fromMe: false, text: "might just chill, no real plans yet" }
      ],
      [
        { fromMe: true, text: "did you finish that thing for tmrw" },
        { fromMe: false, text: "working on it now lol" },
        { fromMe: true, text: "same, it's not too bad tho" }
      ],
      [
        { fromMe: false, text: "did you see that game last night" },
        { fromMe: true, text: "nah I missed it, was it good" },
        { fromMe: false, text: "yeah pretty close game ngl" }
      ],
      [
        { fromMe: true, text: "what do you wanna eat later" },
        { fromMe: false, text: "idk, pizza maybe?" },
        { fromMe: true, text: "bet, sounds good" }
      ],
      [
        { fromMe: false, text: "omw, be there in like 10" },
        { fromMe: true, text: "ok see you in a bit" }
      ],
      [
        { fromMe: true, text: "lol did you see that video" },
        { fromMe: false, text: "yes it was so dumb" },
        { fromMe: true, text: "I couldn't stop laughing" }
      ],
      [
        { fromMe: false, text: "can I borrow your charger later" },
        { fromMe: true, text: "yeah for sure, I'll bring it" },
        { fromMe: false, text: "appreciate it" }
      ],
      [
        { fromMe: true, text: "how was your day" },
        { fromMe: false, text: "pretty good, kinda tired tho" },
        { fromMe: true, text: "same here honestly" }
      ],
      [
        { fromMe: false, text: "you still coming over later?" },
        { fromMe: true, text: "yeah should be good, on schedule" }
      ],
      [
        { fromMe: true, text: "what do you have first tmrw" },
        { fromMe: false, text: "think it's just a free period" },
        { fromMe: true, text: "lucky, I have a test first thing" }
      ]
    ];
    function hashString(s) {
      let h = 0;
      for (let i = 0; i < s.length; i++)
        h = h * 31 + s.charCodeAt(i) >>> 0;
      return h;
    }
    function generatePlaceholderMessages(id) {
      const idx = hashString(String(id)) % PLACEHOLDER_EXCHANGES.length;
      return PLACEHOLDER_EXCHANGES[idx].map((m) => ({ ...m }));
    }
    function injectFakeMessages(id) {
      const cfg = store.channels[id];
      if (!cfg)
        return;
      const UserStore = findStore("UserStore");
      const me = UserStore?.getCurrentUser();
      const them = otherRecipient(id);
      const now = Date.now();
      const total = cfg.messages.length;
      cfg.messages.forEach((m, i) => {
        const author = m.fromMe ? me : them ?? me;
        if (!author)
          return;
        const message = {
          // Stable per (channel, index) id: re-dispatching on every
          // lock cycle just updates the same entry in place rather
          // than duplicating it.
          id: `decoyguard-${id}-${i}`,
          channel_id: id,
          author: {
            id: author.id,
            username: author.username,
            discriminator: author.discriminator ?? "0000",
            avatar: author.avatar ?? null,
            bot: false,
            global_name: author.globalName ?? author.username
          },
          content: m.text,
          // Real Discord messages carry timestamp as an ISO8601
          // string (that's the actual wire format) - NOT a moment()
          // instance. Passing a live moment object here is what threw
          // "RangeError: Invalid time value" deep in Discord's own
          // i18n time formatting the moment something else tried to
          // read this field expecting a string/Date-parseable value.
          timestamp: new Date(now - (total - i) * 6e4).toISOString(),
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
          nonce: null
        };
        try {
          FluxDispatcher.dispatch({
            type: "MESSAGE_CREATE",
            channelId: id,
            message,
            optimistic: false,
            isPushNotification: false,
            [FAKE_MARKER]: true
          });
        } catch (e) {
          logger.error("[DecoyGuard] inject failed", id, e);
        }
      });
    }
    function lockDecoyChannels() {
      if (!isConfigured())
        return;
      decoyIds().forEach((id) => injectFakeMessages(id));
      notifyMessagesChanged();
    }
    function unlockDecoyChannels() {
      notifyMessagesChanged();
    }
    let previousInterceptor;
    let interceptorInstalled = false;
    let interceptorMode = null;
    const BLOCKABLE_ACTIONS = /* @__PURE__ */ new Set([
      "MESSAGE_CREATE",
      "MESSAGE_UPDATE",
      "MESSAGE_DELETE",
      "MESSAGE_REACTION_ADD",
      "MESSAGE_REACTION_REMOVE",
      "LOAD_MESSAGES_SUCCESS",
      "CHANNEL_PINS_UPDATE",
      "TYPING_START"
    ]);
    function shouldBlock(action) {
      if (locked && isConfigured() && !action?.[FAKE_MARKER] && BLOCKABLE_ACTIONS.has(action?.type)) {
        const channelId = action.channelId ?? action.message?.channel_id;
        if (channelId && isPrivateChannel(channelId))
          return true;
      }
      return false;
    }
    function myInterceptorChained(action) {
      if (shouldBlock(action))
        return true;
      return previousInterceptor?.(action);
    }
    function myInterceptorArrayEntry(action) {
      return shouldBlock(action) || void 0;
    }
    function installInterceptor() {
      if (interceptorInstalled)
        return;
      if (typeof FluxDispatcher.setInterceptor === "function") {
        previousInterceptor = FluxDispatcher._interceptors?.[0];
        FluxDispatcher.setInterceptor(myInterceptorChained);
        interceptorMode = "setter";
        interceptorInstalled = true;
        return;
      }
      if (!Array.isArray(FluxDispatcher._interceptors)) {
        FluxDispatcher._interceptors = [];
      }
      FluxDispatcher._interceptors.push(myInterceptorArrayEntry);
      interceptorMode = "array";
      interceptorInstalled = true;
    }
    function restoreInterceptor() {
      if (!interceptorInstalled)
        return;
      if (interceptorMode === "setter") {
        FluxDispatcher.setInterceptor(previousInterceptor);
        previousInterceptor = void 0;
      } else if (interceptorMode === "array") {
        const idx = FluxDispatcher._interceptors?.indexOf(myInterceptorArrayEntry) ?? -1;
        if (idx > -1)
          FluxDispatcher._interceptors.splice(idx, 1);
      }
      interceptorMode = null;
      interceptorInstalled = false;
    }
    let unpatchList = () => {
    };
    let unpatchMessages = () => {
    };
    let unpatchUnlock = () => {
    };
    let appStateSub = null;
    function patchPrivateChannelList() {
      const PrivateChannelSortStore = findStore("PrivateChannelSortStore");
      if (!PrivateChannelSortStore || typeof PrivateChannelSortStore.getPrivateChannelIds !== "function") {
        throw new Error("PrivateChannelSortStore.getPrivateChannelIds not found in this build");
      }
      sortStoreRef = PrivateChannelSortStore;
      unpatchList = patcher.after("getPrivateChannelIds", PrivateChannelSortStore, (_args, ret) => {
        if (!locked || !isConfigured() || !Array.isArray(ret))
          return ret;
        const allowed = new Set(decoyIds());
        return ret.filter((id) => allowed.has(id));
      });
    }
    function patchMessageContent() {
      const MessageStore = findStore("MessageStore");
      if (!MessageStore || typeof MessageStore.getMessages !== "function") {
        throw new Error("MessageStore.getMessages not found in this build");
      }
      messageStoreRef = MessageStore;
      unpatchMessages = patcher.after("getMessages", MessageStore, (args, ret) => {
        const [channelId] = args;
        if (!store.channels[channelId] || !ret || typeof ret.filter !== "function")
          return ret;
        const showFake = locked && isConfigured();
        return ret.filter((m) => {
          const isFake = typeof m?.id === "string" && m.id.startsWith("decoyguard-");
          return showFake ? isFake : !isFake;
        });
      });
    }
    function patchUnlockTrigger() {
      const MessageActions = findProps("sendMessage");
      if (!MessageActions || typeof MessageActions.sendMessage !== "function") {
        throw new Error("MessageActions.sendMessage not found in this build");
      }
      unpatchUnlock = patcher.instead("sendMessage", MessageActions, function(args, orig) {
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
          return Promise.resolve(void 0);
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
        subLabel ? React.createElement(Text, { style: { color: "#999", fontSize: 12, marginTop: 2 } }, subLabel) : null
      );
      return onPress ? React.createElement(TouchableOpacity, { onPress }, content) : content;
    }
    function SectionTitle({ children }) {
      return React.createElement(Text, { style: { color: "#999", fontSize: 12, fontWeight: "bold", marginTop: 20, marginBottom: 6, textTransform: "uppercase" } }, children);
    }
    function Button({ label, onPress, danger }) {
      return React.createElement(
        TouchableOpacity,
        { onPress, style: { backgroundColor: danger ? "#3a1f1f" : "#2b2d31", borderRadius: 4, paddingVertical: 8, paddingHorizontal: 12, marginTop: 6, alignSelf: "flex-start" } },
        React.createElement(Text, { style: { color: danger ? "#f04747" : "white", fontSize: 13 } }, label)
      );
    }
    function ChannelCard({ id, config, expanded, onToggleExpand, onRemove, onAddMessage, onRemoveMessage }) {
      const [msgText, setMsgText] = React.useState("");
      const [fromMe, setFromMe] = React.useState(true);
      const desc = describeChannel(id);
      return React.createElement(
        View,
        { style: { borderWidth: 1, borderColor: "#333", borderRadius: 6, padding: 10, marginBottom: 8 } },
        React.createElement(
          TouchableOpacity,
          { onPress: onToggleExpand },
          React.createElement(Text, { style: { color: "white", fontSize: 14, fontWeight: "bold" } }, config.label || desc.label || id),
          React.createElement(
            Text,
            { style: { color: desc.exists ? "#999" : "#f04747", fontSize: 11, marginTop: 2 } },
            desc.exists ? `${config.messages.length} message(s) - tap to ${expanded ? "collapse" : "edit"}` : "Couldn't resolve this channel ID anymore - it may be wrong, or you're not in that DM"
          )
        ),
        expanded && React.createElement(
          View,
          { style: { marginTop: 10 } },
          ...config.messages.map(
            (m, i) => React.createElement(
              View,
              { key: i, style: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", paddingVertical: 4 } },
              React.createElement(Text, { style: { color: "white", fontSize: 12, flex: 1 } }, `${m.fromMe ? "[You] " : "[Them] "}${m.text}`),
              React.createElement(TouchableOpacity, { onPress: () => onRemoveMessage(i) }, React.createElement(Text, { style: { color: "#f04747", fontSize: 12, paddingHorizontal: 8 } }, "remove"))
            )
          ),
          React.createElement(TextInput, {
            value: msgText,
            onChangeText: setMsgText,
            placeholder: "message text",
            placeholderTextColor: "#666",
            style: { color: "white", borderWidth: 1, borderColor: "#555", borderRadius: 4, padding: 8, marginTop: 8 }
          }),
          React.createElement(
            TouchableOpacity,
            { onPress: () => setFromMe((v) => !v), style: { marginTop: 6 } },
            React.createElement(Text, { style: { color: "#5865f2", fontSize: 12 } }, fromMe ? "Sent by: you (tap to switch)" : "Sent by: them (tap to switch)")
          ),
          React.createElement(Button, {
            label: "Add message",
            onPress: () => {
              if (!msgText.trim())
                return;
              onAddMessage(msgText.trim(), fromMe);
              setMsgText("");
            }
          }),
          React.createElement(Button, { label: "Remove this channel", danger: true, onPress: onRemove })
        )
      );
    }
    function Settings() {
      const [, forceUpdate] = React.useReducer((n) => n + 1, 0);
      React.useEffect(() => {
        const l = () => forceUpdate();
        listeners.add(l);
        return () => listeners.delete(l);
      }, []);
      const [json, setJson] = React.useState(() => JSON.stringify(store.channels, null, 2));
      const [jsonError, setJsonError] = React.useState("");
      const [phrase, setPhrase] = React.useState(store.unlockPhrase);
      const [showAdvanced, setShowAdvanced] = React.useState(false);
      const [expandedId, setExpandedId] = React.useState(null);
      const [manualId, setManualId] = React.useState("");
      const [addError, setAddError] = React.useState("");
      function saveJson() {
        try {
          store.channels = JSON.parse(json);
          setJsonError("");
          if (locked)
            lockDecoyChannels();
          notifyChannelListChanged();
          forceUpdate();
        } catch (e) {
          setJsonError("Invalid JSON: " + e.message);
        }
      }
      function savePhrase() {
        const trimmed = phrase.trim();
        if (!trimmed)
          return;
        store.unlockPhrase = trimmed;
        forceUpdate();
      }
      function afterChannelsChanged() {
        setJson(JSON.stringify(store.channels, null, 2));
        if (locked)
          lockDecoyChannels();
        notifyChannelListChanged();
        forceUpdate();
      }
      function addChannel(id) {
        setAddError("");
        if (!id) {
          setAddError("Couldn't detect a chat - open the DM you want first (just tap into it, no need to send anything), then come back here and try again. Or paste a channel ID below.");
          return;
        }
        if (store.channels[id]) {
          setAddError("That chat is already in your decoy list.");
          return;
        }
        const desc = describeChannel(id);
        if (!desc.exists) {
          setAddError("That doesn't look like a DM/group channel Discord recognizes right now.");
          return;
        }
        store.channels[id] = { label: desc.label, messages: generatePlaceholderMessages(id) };
        setExpandedId(id);
        afterChannelsChanged();
      }
      function removeChannel(id) {
        delete store.channels[id];
        if (expandedId === id)
          setExpandedId(null);
        afterChannelsChanged();
      }
      function addMessage(id, text, fromMe) {
        store.channels[id].messages.push({ fromMe, text });
        afterChannelsChanged();
      }
      function removeMessage(id, index) {
        store.channels[id].messages.splice(index, 1);
        afterChannelsChanged();
      }
      const anyFailed = diagnostics.some((d) => !d.ok);
      return React.createElement(
        ScrollView,
        { style: { flex: 1, backgroundColor: "#000" }, contentContainerStyle: { padding: 16 } },
        anyFailed && React.createElement(
          View,
          { style: { backgroundColor: "#3a1f1f", borderRadius: 6, padding: 10, marginBottom: 16 } },
          React.createElement(Text, { style: { color: "#f04747", fontWeight: "bold", marginBottom: 4 } }, "Some internal lookups failed - functionality below is degraded:"),
          ...diagnostics.filter((d) => !d.ok).map(
            (d) => React.createElement(Text, { style: { color: "#f0a0a0", fontSize: 12 } }, `- ${d.step}: ${d.detail}`)
          )
        ),
        React.createElement(SectionTitle, null, "Status"),
        React.createElement(Row, {
          label: locked ? "Locked - showing decoys" : "Unlocked - showing real DMs",
          subLabel: "Always starts locked on app open/reload. Auto re-locks when backgrounded."
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
          }
        }),
        React.createElement(SectionTitle, null, "Diagnostics"),
        ...diagnostics.map(
          (d) => React.createElement(Text, { style: { color: d.ok ? "#43b581" : "#f04747", fontSize: 12, marginBottom: 2 } }, `${d.ok ? "OK" : "FAIL"}  ${d.step}${d.detail ? " - " + d.detail : ""}`)
        ),
        React.createElement(SectionTitle, null, "Unlock phrase"),
        React.createElement(
          Text,
          { style: { color: "#999", fontSize: 12, marginBottom: 8 } },
          "Type this into any DM box + send while locked to unlock (intercepted, never actually sent). Send it again while unlocked to re-lock."
        ),
        React.createElement(TextInput, {
          value: phrase,
          onChangeText: setPhrase,
          placeholder: "your secret phrase",
          placeholderTextColor: "#666",
          style: { color: "white", borderWidth: 1, borderColor: "#555", borderRadius: 4, padding: 8, marginBottom: 8 }
        }),
        React.createElement(Row, { label: "Save phrase", onPress: savePhrase }),
        React.createElement(SectionTitle, null, "Decoy channels"),
        React.createElement(
          Text,
          { style: { color: "#999", fontSize: 12, marginBottom: 8 } },
          "Open the DM you want to use as a decoy (just tap into it), then come back to this screen and tap the button below. Do this for each of your ~10 decoy chats. Each one is auto-filled with a generic placeholder chat you can edit or replace below."
        ),
        React.createElement(Button, { label: "+ Add current chat as decoy", onPress: () => addChannel(getSelectedChannelId()) }),
        React.createElement(Text, { style: { color: "#999", fontSize: 12, marginTop: 14, marginBottom: 4 } }, "Didn't work? Paste a channel ID instead (Settings > General > enable Developer Mode, then long-press the chat > Copy ID):"),
        React.createElement(
          View,
          { style: { flexDirection: "row", alignItems: "center" } },
          React.createElement(TextInput, {
            value: manualId,
            onChangeText: setManualId,
            placeholder: "channel ID",
            placeholderTextColor: "#666",
            style: { color: "white", borderWidth: 1, borderColor: "#555", borderRadius: 4, padding: 8, flex: 1, marginRight: 8 }
          }),
          React.createElement(Button, { label: "Add", onPress: () => {
            addChannel(manualId.trim());
            setManualId("");
          } })
        ),
        addError ? React.createElement(Text, { style: { color: "#f04747", marginTop: 6 } }, addError) : null,
        React.createElement(
          View,
          { style: { marginTop: 16 } },
          ...Object.keys(store.channels).map(
            (id) => React.createElement(ChannelCard, {
              key: id,
              id,
              config: store.channels[id],
              expanded: expandedId === id,
              onToggleExpand: () => setExpandedId(expandedId === id ? null : id),
              onRemove: () => removeChannel(id),
              onAddMessage: (text, fromMe) => addMessage(id, text, fromMe),
              onRemoveMessage: (i) => removeMessage(id, i)
            })
          )
        ),
        Object.keys(store.channels).length === 0 ? React.createElement(Text, { style: { color: "#666", fontSize: 12, marginTop: 8 } }, "No decoy channels added yet.") : null,
        React.createElement(
          TouchableOpacity,
          { onPress: () => setShowAdvanced((v) => !v), style: { marginTop: 20 } },
          React.createElement(Text, { style: { color: "#5865f2", fontSize: 12 } }, showAdvanced ? "Hide advanced JSON editor" : "Advanced: edit as raw JSON")
        ),
        showAdvanced && React.createElement(
          View,
          { style: { marginTop: 8 } },
          React.createElement(
            Text,
            { style: { color: "#999", fontSize: 12, marginBottom: 8 } },
            'Format: {"CHANNEL_ID": {"label": "your reference only", "messages": [{"fromMe": true, "text": "..."}, {"fromMe": false, "text": "..."}]}}. Overwrites everything above on save.'
          ),
          React.createElement(TextInput, {
            value: json,
            onChangeText: setJson,
            placeholder: "{}",
            placeholderTextColor: "#666",
            multiline: true,
            style: { minHeight: 220, color: "white", textAlignVertical: "top", padding: 8, borderWidth: 1, borderColor: "#555", borderRadius: 4, marginBottom: 8 }
          }),
          jsonError ? React.createElement(Text, { style: { color: "#f04747", marginBottom: 8 } }, jsonError) : null,
          React.createElement(Row, { label: "Save decoy config (JSON)", onPress: saveJson })
        )
      );
    }
    return {
      onLoad() {
        try {
          setLocked(true);
          safe("install Flux interceptor", installInterceptor);
          safe("patch DM list filtering", patchPrivateChannelList);
          safe("patch message content filtering", patchMessageContent);
          safe("patch unlock trigger (sendMessage)", patchUnlockTrigger);
          safe("populate decoy content", lockDecoyChannels);
          safe("subscribe to AppState", () => {
            appStateSub = AppState.addEventListener("change", onAppStateChange);
          });
          notifyChannelListChanged();
          notifyMessagesChanged();
        } catch (e) {
          logger.error("[DecoyGuard] unexpected error during onLoad", e);
        }
      },
      onUnload() {
        try {
          unpatchList();
        } catch (e) {
          logger.error("[DecoyGuard] unpatchList failed", e);
        }
        try {
          unpatchMessages();
        } catch (e) {
          logger.error("[DecoyGuard] unpatchMessages failed", e);
        }
        try {
          unpatchUnlock();
        } catch (e) {
          logger.error("[DecoyGuard] unpatchUnlock failed", e);
        }
        try {
          restoreInterceptor();
        } catch (e) {
          logger.error("[DecoyGuard] restoreInterceptor failed", e);
        }
        try {
          appStateSub?.remove();
        } catch {
        }
        appStateSub = null;
        sortStoreRef = null;
        messageStoreRef = null;
      },
      settings: Settings
    };
  }
  var started;
  try {
    started = start();
  } catch (e) {
    started = {
      onLoad() {
      },
      onUnload() {
      },
      settings() {
        const { React, ReactNative } = vendetta.metro.common;
        return React.createElement(
          ReactNative.View,
          { style: { flex: 1, padding: 16, backgroundColor: "#000" } },
          React.createElement(ReactNative.Text, { style: { color: "#f04747", fontWeight: "bold", marginBottom: 8 } }, "Decoy Guard failed to initialize"),
          React.createElement(ReactNative.Text, { style: { color: "#f0a0a0" } }, String(e?.stack ?? e))
        );
      }
    };
  }
  var entry_default = started;
  return __toCommonJS(entry_exports);
})()