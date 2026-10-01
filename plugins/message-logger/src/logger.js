import { FluxDispatcher } from "@vendetta/metro/common";
import { findByStoreName } from "@vendetta/metro";
import { storage } from "@vendetta/plugin";
import * as store from "./store";

const ChannelStore = findByStoreName("ChannelStore");
const GuildStore = findByStoreName("GuildStore");
const UserStore = findByStoreName("UserStore");

const toMs = ts => (ts ? new Date(ts).getTime() || undefined : undefined);

export function channelMeta(channelId) {
    const channel = ChannelStore.getChannel(channelId);
    if (!channel) return { name: channelId };
    const guildId = channel.guild_id ?? channel.getGuildId?.();
    if (guildId) {
        return { name: `#${channel.name}`, guildId, guildName: GuildStore.getGuild(guildId)?.name ?? guildId };
    }
    // DM / group DM
    if (channel.name) return { name: channel.name, guildName: "Direct Messages" };
    const names = (channel.recipients ?? [])
        .map(id => UserStore.getUser(id))
        .filter(Boolean)
        .map(u => u.globalName ?? u.global_name ?? u.username);
    return { name: names.length ? `@${names.join(", ")}` : channelId, guildName: "Direct Messages" };
}

function shouldLog(channelId, guildId) {
    if (!storage.enabled) return false;
    if (storage.ignoredChannels?.[channelId]) return false;
    if (!guildId) {
        const channel = ChannelStore.getChannel(channelId);
        guildId = channel?.guild_id;
    }
    if (!guildId) return !!storage.logDMs;
    if (storage.guildMode === "selected") return !!storage.selectedGuilds?.[guildId];
    return !storage.ignoredGuilds?.[guildId];
}

function normalize(m) {
    const author = m.author ?? {};
    const msg = {
        id: m.id,
        t: toMs(m.timestamp) ?? Date.now(),
        a: {
            id: author.id,
            u: author.username,
            n: m.member?.nick ?? author.globalName ?? author.global_name ?? undefined,
            b: author.bot || undefined,
        },
        c: m.content ?? "",
    };
    const attachments = m.attachments ?? [];
    if (attachments.length) msg.at = attachments.map(a => a.url ?? a.proxy_url ?? a.filename);
    const stickers = m.sticker_items ?? m.stickerItems ?? m.stickers ?? [];
    if (stickers.length) msg.st = stickers.map(s => s.name);
    const embeds = m.embeds ?? [];
    if (embeds.length && !msg.c) {
        // Bot/system messages are often embed-only; keep something readable.
        msg.em = embeds.map(e => [e.title ?? e.rawTitle, e.description ?? e.rawDescription].filter(Boolean).join(" - ")).filter(Boolean);
        if (!msg.em.length) delete msg.em;
    }
    const ref = m.message_reference ?? m.messageReference;
    if (ref?.message_id ?? ref?.messageId) msg.r = ref.message_id ?? ref.messageId;
    const et = toMs(m.edited_timestamp ?? m.editedTimestamp);
    if (et) msg.et = et;
    return msg;
}

function keep(m) {
    if (!m?.id || !m.author) return false;
    if (!storage.logBots && m.author.bot) return false;
    return true;
}

const handlers = {
    MESSAGE_CREATE(action) {
        if (action.optimistic || action.isPushNotification) return;
        const m = action.message;
        const channelId = action.channelId ?? m?.channel_id;
        if (!channelId || !keep(m) || !shouldLog(channelId, action.guildId ?? m.guild_id)) return;
        return store.addMessages(channelId, [normalize(m)], channelMeta(channelId));
    },

    MESSAGE_UPDATE(action) {
        const m = action.message;
        const channelId = m?.channel_id ?? m?.channelId;
        // Embed-unfurl updates arrive without content; ignore those.
        if (!channelId || !m.id || m.content == null) return;
        if (!store.getIndex()[channelId]) {
            // Edit of a message we never saw: log it if the channel is in scope.
            if (keep(m) && shouldLog(channelId, m.guild_id)) {
                return store.addMessages(channelId, [normalize(m)], channelMeta(channelId));
            }
            return;
        }
        return store.editMessage(channelId, m.id, m.content, toMs(m.edited_timestamp));
    },

    MESSAGE_DELETE(action) {
        if (!action.channelId || !action.id) return;
        return store.deleteMessages(action.channelId, [action.id]);
    },

    MESSAGE_DELETE_BULK(action) {
        if (!action.channelId || !action.ids?.length) return;
        return store.deleteMessages(action.channelId, action.ids);
    },

    // Fires when you open/scroll a channel: lets us capture history you look at.
    LOAD_MESSAGES_SUCCESS(action) {
        if (!storage.logHistory) return;
        const { channelId, messages } = action;
        if (!channelId || !messages?.length || !shouldLog(channelId)) return;
        const msgs = messages.filter(keep).map(normalize);
        if (msgs.length) return store.addMessages(channelId, msgs, channelMeta(channelId));
    },
};

export function start() {
    for (const [type, fn] of Object.entries(handlers)) {
        FluxDispatcher.subscribe(type, wrap(fn));
    }
}

const wrapped = new Map();
function wrap(fn) {
    const w = action => {
        try {
            Promise.resolve(fn(action)).catch(e => console.error("[MessageLogger]", e));
        } catch (e) {
            console.error("[MessageLogger]", e);
        }
    };
    wrapped.set(fn, w);
    return w;
}

export function stop() {
    for (const [type, fn] of Object.entries(handlers)) {
        const w = wrapped.get(fn);
        if (w) FluxDispatcher.unsubscribe(type, w);
    }
    wrapped.clear();
}
