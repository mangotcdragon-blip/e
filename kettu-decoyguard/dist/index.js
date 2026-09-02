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
    const { patcher, metro, plugin, logger, ui } = vendetta;
    const { React, ReactNative, FluxDispatcher, moment } = metro.common;
    const { ScrollView, TextInput, Text, AppState } = ReactNative;
    const { FormSection, FormRow, FormDivider, FormText } = ui.components.Forms;
    const DEFAULT_PHRASE = "CHANGE-ME-IN-SETTINGS";
    const FAKE_MARKER = "kettuDecoyGuardFake";
    const store = plugin.storage;
    if (!store.unlockPhrase)
      store.unlockPhrase = DEFAULT_PHRASE;
    if (!store.channels)
      store.channels = {};
    let locked = true;
    const listeners = /* @__PURE__ */ new Set();
    function setLocked(v) {
      if (locked === v)
        return;
      locked = v;
      listeners.forEach((l) => l());
    }
    function decoyIds() {
      return Object.keys(store.channels);
    }
    function isConfigured() {
      return decoyIds().length > 0 && !!store.unlockPhrase && store.unlockPhrase !== DEFAULT_PHRASE;
    }
    function isPrivateChannel(id) {
      try {
        const ChannelStore = metro.findByStoreName("ChannelStore");
        const c = ChannelStore.getChannel(id);
        return c?.type === 1 || c?.type === 3;
      } catch {
        return false;
      }
    }
    function otherRecipient(id) {
      try {
        const ChannelStore = metro.findByStoreName("ChannelStore");
        const UserStore = metro.findByStoreName("UserStore");
        const c = ChannelStore.getChannel(id);
        const rid = c?.recipients?.[0];
        return rid ? UserStore.getUser(rid) : void 0;
      } catch {
        return void 0;
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
          hasMoreAfter: false
        });
      } catch (e) {
        logger.error("[DecoyGuard] clear failed", id, e);
      }
    }
    function injectFakeMessages(id) {
      const cfg = store.channels[id];
      if (!cfg)
        return;
      const UserStore = metro.findByStoreName("UserStore");
      const me = UserStore.getCurrentUser();
      const them = otherRecipient(id);
      const now = Date.now();
      const total = cfg.messages.length;
      cfg.messages.forEach((m, i) => {
        const author = m.fromMe ? me : them ?? me;
        if (!author)
          return;
        const message = {
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
          timestamp: moment(now - (total - i) * 6e4),
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
          flags: 0
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
      decoyIds().forEach((id) => {
        clearChannelMessages(id);
        injectFakeMessages(id);
      });
    }
    function unlockDecoyChannels() {
      decoyIds().forEach((id) => {
        clearChannelMessages(id);
        try {
          const MessageActions = metro.findByProps("sendMessage");
          MessageActions.fetchMessages?.({ channelId: id, limit: 50 });
        } catch (e) {
          logger.warn("[DecoyGuard] refetch failed", id, e);
        }
      });
    }
    let previousInterceptor;
    let interceptorInstalled = false;
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
    function myInterceptor(action) {
      if (locked && isConfigured() && !action?.[FAKE_MARKER] && BLOCKABLE_ACTIONS.has(action?.type)) {
        const channelId = action.channelId ?? action.message?.channel_id;
        if (channelId && isPrivateChannel(channelId))
          return true;
      }
      return previousInterceptor?.(action);
    }
    function installInterceptor() {
      if (interceptorInstalled)
        return;
      previousInterceptor = FluxDispatcher._interceptors?.[0];
      FluxDispatcher.setInterceptor(myInterceptor);
      interceptorInstalled = true;
    }
    function restoreInterceptor() {
      if (!interceptorInstalled)
        return;
      FluxDispatcher.setInterceptor(previousInterceptor);
      interceptorInstalled = false;
      previousInterceptor = void 0;
    }
    let unpatchList = () => {
    };
    let unpatchUnlock = () => {
    };
    let appStateSub = null;
    function patchPrivateChannelList() {
      const PrivateChannelSortStore = metro.findByStoreName("PrivateChannelSortStore");
      unpatchList = patcher.after("getPrivateChannelIds", PrivateChannelSortStore, (_args, ret) => {
        if (!locked || !isConfigured() || !Array.isArray(ret))
          return ret;
        const allowed = new Set(decoyIds());
        return ret.filter((id) => allowed.has(id));
      });
    }
    function patchUnlockTrigger() {
      const MessageActions = metro.findByProps("sendMessage");
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
    function Settings() {
      const [, forceUpdate] = React.useReducer((n) => n + 1, 0);
      React.useEffect(() => {
        const l = () => forceUpdate();
        listeners.add(l);
        return () => listeners.delete(l);
      }, []);
      const [json, setJson] = React.useState(() => JSON.stringify(store.channels, null, 2));
      const [error, setError] = React.useState("");
      function save() {
        try {
          store.channels = JSON.parse(json);
          setError("");
          if (locked)
            lockDecoyChannels();
        } catch (e) {
          setError("Invalid JSON: " + e.message);
        }
      }
      return React.createElement(
        ScrollView,
        { style: { flex: 1 }, contentContainerStyle: { padding: 12 } },
        React.createElement(
          FormSection,
          { title: "Status" },
          React.createElement(FormRow, {
            label: locked ? "Locked - showing decoys" : "Unlocked - showing real DMs",
            subLabel: "Always starts locked on app open/reload. Auto re-locks when backgrounded."
          }),
          React.createElement(FormRow, {
            label: locked ? "Force unlock now" : "Force re-lock now",
            onPress: () => {
              if (locked) {
                setLocked(false);
                unlockDecoyChannels();
              } else {
                setLocked(true);
                lockDecoyChannels();
              }
            }
          })
        ),
        React.createElement(FormDivider, null),
        React.createElement(
          FormSection,
          { title: "Unlock phrase" },
          React.createElement(FormRow, {
            label: "Change unlock phrase",
            subLabel: "Type into any DM box + send while locked to unlock (intercepted, never actually sent). Send again while unlocked to re-lock.",
            onPress: () => ui.alerts.showInputAlert({
              title: "Unlock phrase",
              placeholder: "your secret phrase",
              initialValue: store.unlockPhrase,
              confirmText: "Save",
              onConfirm: (value) => {
                const trimmed = value.trim();
                if (!trimmed)
                  throw new Error("Can't be empty");
                store.unlockPhrase = trimmed;
              }
            })
          })
        ),
        React.createElement(FormDivider, null),
        React.createElement(
          FormSection,
          { title: "Decoy channels (raw JSON)" },
          React.createElement(
            FormText,
            null,
            'Format: {"CHANNEL_ID": {"label": "your reference only", "messages": [{"fromMe": true, "text": "..."}, {"fromMe": false, "text": "..."}]}}'
          ),
          React.createElement(TextInput, {
            value: json,
            onChangeText: setJson,
            placeholder: "{}",
            multiline: true,
            style: { minHeight: 220, color: "white", textAlignVertical: "top", padding: 8, borderWidth: 1, borderColor: "#555", borderRadius: 4, marginBottom: 8 }
          }),
          error ? React.createElement(Text, { style: { color: "#f04747", marginBottom: 8 } }, error) : null,
          React.createElement(FormRow, { label: "Save decoy config", onPress: save })
        )
      );
    }
    return {
      onLoad() {
        setLocked(true);
        installInterceptor();
        patchPrivateChannelList();
        patchUnlockTrigger();
        lockDecoyChannels();
        appStateSub = AppState.addEventListener("change", onAppStateChange);
      },
      onUnload() {
        unpatchList();
        unpatchUnlock();
        restoreInterceptor();
        appStateSub?.remove();
        appStateSub = null;
      },
      settings: Settings
    };
  }
  var entry_default = start();
  return __toCommonJS(entry_exports);
})()