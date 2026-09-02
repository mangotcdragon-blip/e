import { after, instead } from "@lib/api/patcher";
import { FluxDispatcher } from "@metro/common";
import { findByProps, findByStoreNameLazy } from "@metro";
import { logger } from "@lib/utils/logger";
import { AppState, type NativeEventSubscription } from "react-native";
import moment from "moment";

import { decoyGuardStorage } from "./storage";
import { isLocked, setLocked } from "./state";

// --- Discord internals we depend on ---------------------------------------
// These names come from well-established, long-stable Discord client Flux
// conventions (the same ones every Vendetta/Bunny-family plugin builds on),
// NOT from inspecting this specific build. Verify them on-device (see the
// README section in this folder) before trusting this around anyone.
const PrivateChannelSortStore = findByStoreNameLazy("PrivateChannelSortStore");
const ChannelStore = findByStoreNameLazy("ChannelStore");
const UserStore = findByStoreNameLazy("UserStore");
const MessageActions = findByProps("sendMessage");

const FAKE_MARKER = "kettuDecoyGuardFake";

function decoyIds(): string[] {
    return Object.keys(decoyGuardStorage.channels);
}

// Refuses to actually start hiding/blocking anything until you've picked at
// least one decoy channel AND changed the unlock phrase from the placeholder.
// Without this, an unconfigured (or misconfigured, e.g. emptied via a JSON
// edit) plugin would hide every DM with no visible channel left to type the
// unlock phrase into - a self-lockout. Configure everything in Settings
// first, THEN enable the plugin.
function isConfigured(): boolean {
    return decoyIds().length > 0
        && !!decoyGuardStorage.unlockPhrase
        && decoyGuardStorage.unlockPhrase !== "CHANGE-ME-IN-SETTINGS";
}

function isPrivateChannel(channelId: string): boolean {
    try {
        const channel = ChannelStore.getChannel(channelId);
        // DM = 1, group DM = 3
        return channel?.type === 1 || channel?.type === 3;
    } catch {
        return false;
    }
}

function otherRecipient(channelId: string) {
    try {
        const channel = ChannelStore.getChannel(channelId);
        const recipientId = channel?.recipients?.[0];
        return recipientId ? UserStore.getUser(recipientId) : undefined;
    } catch {
        return undefined;
    }
}

// --- Wiping / (re)populating a decoy channel's visible messages -----------

function clearChannelMessages(channelId: string) {
    try {
        FluxDispatcher.dispatch({
            type: "LOAD_MESSAGES_SUCCESS",
            channelId,
            messages: [],
            isBefore: true,
            isAfter: true,
            hasMoreBefore: false,
            hasMoreAfter: false,
        });
    } catch (e) {
        logger.error("[DecoyGuard] failed to clear messages for", channelId, e);
    }
}

function injectFakeMessages(channelId: string) {
    const config = decoyGuardStorage.channels[channelId];
    if (!config) return;

    const me = UserStore.getCurrentUser();
    const them = otherRecipient(channelId);
    const now = Date.now();
    const total = config.messages.length;

    config.messages.forEach((m, i) => {
        const author = m.fromMe ? me : (them ?? me);
        if (!author) return;

        const message = {
            id: `decoyguard-${channelId}-${i}`,
            channel_id: channelId,
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
                channelId,
                message,
                optimistic: false,
                isPushNotification: false,
                [FAKE_MARKER]: true,
            });
        } catch (e) {
            logger.error("[DecoyGuard] failed to inject fake message into", channelId, e);
        }
    });
}

export function lockDecoyChannels() {
    if (!isConfigured()) return;
    decoyIds().forEach(id => {
        clearChannelMessages(id);
        injectFakeMessages(id);
    });
}

export function unlockDecoyChannels() {
    decoyIds().forEach(id => {
        clearChannelMessages(id);
        try {
            // Best-effort: ask Discord to repull real history now. If this
            // particular action creator name is wrong in this build, nothing
            // bad happens - the channel just stays showing nothing/stale
            // decoy content until you reopen it, which resyncs normally.
            (MessageActions as any).fetchMessages?.({ channelId: id, limit: 50 });
        } catch (e) {
            logger.warn("[DecoyGuard] couldn't auto-refetch real history for", id, "- open the chat manually to resync", e);
        }
    });
}

// --- Blocking real DM traffic while locked ---------------------------------
// FluxDispatcher only supports ONE global interceptor slot, so we must chain
// through whatever was set before us instead of clobbering it.
let previousInterceptor: ((payload: any) => void | boolean) | undefined;
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

function myInterceptor(action: any): void | boolean {
    if (isLocked() && isConfigured() && !action?.[FAKE_MARKER] && BLOCKABLE_ACTIONS.has(action?.type)) {
        const channelId: string | undefined = action.channelId ?? action.message?.channel_id;

        if (channelId && isPrivateChannel(channelId)) {
            // Any private channel that isn't one of ours stays fully hidden.
            // Even our own decoy channels have their REAL traffic blocked -
            // we own what's rendered there while locked.
            return true;
        }
    }

    return previousInterceptor?.(action);
}

function installInterceptor() {
    if (interceptorInstalled) return;
    previousInterceptor = (FluxDispatcher as any)._interceptors?.[0];
    (FluxDispatcher as any).setInterceptor(myInterceptor);
    interceptorInstalled = true;
}

function restoreInterceptor() {
    if (!interceptorInstalled) return;
    (FluxDispatcher as any).setInterceptor(previousInterceptor);
    interceptorInstalled = false;
    previousInterceptor = undefined;
}

// --- DM list filtering -------------------------------------------------

let unpatchList: () => void = () => {};
let unpatchUnlock: () => void = () => {};

function patchPrivateChannelList() {
    unpatchList = after("getPrivateChannelIds", PrivateChannelSortStore, (_args, ret: string[]) => {
        if (!isLocked() || !isConfigured() || !Array.isArray(ret)) return ret;
        const allowed = new Set(decoyIds());
        return ret.filter(id => allowed.has(id));
    });
}

function patchUnlockTrigger() {
    unpatchUnlock = instead("sendMessage", MessageActions, function (this: unknown, args: any[], orig: Function) {
        const [, message] = args;
        const content = typeof message?.content === "string" ? message.content.trim() : "";
        const phrase = decoyGuardStorage.unlockPhrase?.trim();

        if (isConfigured() && phrase && content === phrase) {
            if (isLocked()) {
                setLocked(false);
                unlockDecoyChannels();
                logger.log("[DecoyGuard] unlocked");
            } else {
                setLocked(true);
                lockDecoyChannels();
                logger.log("[DecoyGuard] re-locked");
            }
            // Never actually send the unlock phrase.
            return Promise.resolve(undefined);
        }

        return orig.apply(this, args);
    });
}

// --- Auto re-lock on backgrounding ------------------------------------

let appStateSub: NativeEventSubscription | null = null;

function handleAppStateChange(nextState: string) {
    if (nextState !== "active" && !isLocked()) {
        setLocked(true);
        lockDecoyChannels();
        logger.log("[DecoyGuard] app backgrounded - auto re-locked");
    }
}

// --- Public entry points -------------------------------------------------

export function startDecoyGuard() {
    setLocked(true);
    installInterceptor();
    patchPrivateChannelList();
    patchUnlockTrigger();
    lockDecoyChannels();
    appStateSub = AppState.addEventListener("change", handleAppStateChange);
}

export function stopDecoyGuard() {
    unpatchList();
    unpatchUnlock();
    restoreInterceptor();
    appStateSub?.remove();
    appStateSub = null;
}
