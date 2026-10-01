import { storage } from "@vendetta/plugin";
import * as fs from "./fs";

// Logs live in their own files (one per channel) instead of plugin storage,
// because plugin storage rewrites one big JSON blob on every change.
//
// index.json:            { [channelId]: { name, guildId, guildName, count, last } }
// channels/<id>.json:    { id, messages: LoggedMessage[] }
//
// LoggedMessage: {
//   id, t (sent, ms), a: { id, u (username), n (display name), b (bot) },
//   c (content), at?: string[] (attachment urls), st?: string[] (sticker names),
//   r?: string (replied-to message id), e?: { t, c }[] (previous versions), d?: number (deleted at, ms)
// }

const INDEX = "index.json";
const FLUSH_DELAY = 4000;
const MAX_CACHED_CHANNELS = 40;

let index = {};
const channels = new Map(); // channelId -> { id, messages, ids: Map<id, msg> }
const loading = new Map(); // channelId -> Promise
const dirty = new Set();
let indexDirty = false;
let flushTimer = null;
const listeners = new Set();

export function subscribe(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
}

function emit() {
    for (const fn of listeners) {
        try { fn(); } catch {}
    }
}

export async function init() {
    index = await fs.readJSON(INDEX, {});
}

export function getIndex() {
    return index;
}

function hydrate(id, data) {
    const ch = { id, messages: Array.isArray(data?.messages) ? data.messages : [], ids: new Map() };
    for (const m of ch.messages) ch.ids.set(m.id, m);
    return ch;
}

export function getChannel(channelId) {
    const cached = channels.get(channelId);
    if (cached) {
        cached.used = Date.now();
        return Promise.resolve(cached);
    }
    if (loading.has(channelId)) return loading.get(channelId);

    const p = fs.readJSON(`channels/${channelId}.json`, null).then(data => {
        // A write may have created the channel while we were reading.
        const existing = channels.get(channelId);
        const ch = existing ?? hydrate(channelId, data);
        if (existing && data?.messages) {
            for (const m of data.messages) if (!existing.ids.has(m.id)) insert(existing, m);
        }
        ch.used = Date.now();
        channels.set(channelId, ch);
        loading.delete(channelId);
        return ch;
    });
    loading.set(channelId, p);
    return p;
}

// Snowflakes sort by length, then lexicographically.
function cmpId(a, b) {
    return a.length - b.length || (a < b ? -1 : a > b ? 1 : 0);
}

function insert(ch, msg) {
    const arr = ch.messages;
    // Fast path: newest message (the normal live case).
    if (!arr.length || cmpId(arr[arr.length - 1].id, msg.id) < 0) {
        arr.push(msg);
    } else {
        let lo = 0, hi = arr.length;
        while (lo < hi) {
            const mid = (lo + hi) >> 1;
            if (cmpId(arr[mid].id, msg.id) < 0) lo = mid + 1;
            else hi = mid;
        }
        arr.splice(lo, 0, msg);
    }
    ch.ids.set(msg.id, msg);
}

function trim(ch) {
    const max = Number(storage.maxPerChannel) || 0;
    if (max > 0 && ch.messages.length > max) {
        const removed = ch.messages.splice(0, ch.messages.length - max);
        for (const m of removed) ch.ids.delete(m.id);
    }
}

function touch(channelId, ch, meta) {
    const prev = index[channelId] ?? {};
    index[channelId] = {
        ...prev,
        ...(meta ?? {}),
        count: ch.messages.length,
        last: ch.messages.length ? ch.messages[ch.messages.length - 1].t : prev.last,
    };
    dirty.add(channelId);
    indexDirty = true;
    scheduleFlush();
}

/** Adds messages (or merges into existing ones). meta = { name, guildId, guildName } */
export async function addMessages(channelId, msgs, meta) {
    const ch = await getChannel(channelId);
    let changed = false;
    for (const msg of msgs) {
        const existing = ch.ids.get(msg.id);
        if (existing) {
            // History reloads can carry newer content than what we logged; keep the old version.
            if (msg.c !== existing.c && msg.c != null && !existing.d) {
                (existing.e ??= []).push({ t: msg.et ?? Date.now(), c: existing.c });
                existing.c = msg.c;
                changed = true;
            }
            continue;
        }
        insert(ch, msg);
        changed = true;
    }
    if (!changed) return;
    trim(ch);
    touch(channelId, ch, meta);
}

export async function editMessage(channelId, id, content, editedAt) {
    const ch = await getChannel(channelId);
    const msg = ch.ids.get(id);
    if (!msg || content == null || content === msg.c) return;
    (msg.e ??= []).push({ t: editedAt ?? Date.now(), c: msg.c });
    msg.c = content;
    touch(channelId, ch);
}

export async function deleteMessages(channelId, ids) {
    if (!index[channelId] && !channels.has(channelId)) return;
    const ch = await getChannel(channelId);
    const now = Date.now();
    let changed = false;
    for (const id of ids) {
        const msg = ch.ids.get(id);
        if (msg && !msg.d) {
            msg.d = now;
            changed = true;
        }
    }
    if (changed) touch(channelId, ch);
}

export async function clearChannel(channelId) {
    channels.delete(channelId);
    dirty.delete(channelId);
    delete index[channelId];
    // removeFile can silently fail on Android, so blank the file first.
    await fs.write(`channels/${channelId}.json`, JSON.stringify({ messages: [] }));
    await fs.remove(`channels/${channelId}.json`);
    indexDirty = true;
    await flush();
    emit();
}

export async function clearAll() {
    for (const id of Object.keys(index)) await clearChannel(id);
}

function scheduleFlush() {
    if (flushTimer) return;
    flushTimer = setTimeout(() => {
        flushTimer = null;
        flush();
    }, FLUSH_DELAY);
}

export async function flush() {
    if (flushTimer) {
        clearTimeout(flushTimer);
        flushTimer = null;
    }
    const ids = [...dirty];
    dirty.clear();
    for (const id of ids) {
        const ch = channels.get(id);
        if (!ch) continue;
        try {
            await fs.write(`channels/${id}.json`, JSON.stringify({ id, messages: ch.messages }));
        } catch (e) {
            console.error("[MessageLogger] failed to save channel", id, e);
        }
    }
    if (indexDirty) {
        indexDirty = false;
        try {
            await fs.write(INDEX, JSON.stringify(index));
        } catch (e) {
            console.error("[MessageLogger] failed to save index", e);
        }
    }
    evict();
    if (ids.length) emit();
}

// Keep memory bounded when logging lots of channels: drop the least recently
// used, already-saved channels from the cache.
function evict() {
    if (channels.size <= MAX_CACHED_CHANNELS) return;
    const saved = [...channels.values()].filter(c => !dirty.has(c.id)).sort((a, b) => a.used - b.used);
    for (const c of saved.slice(0, channels.size - MAX_CACHED_CHANNELS)) channels.delete(c.id);
}

/** Drops channel caches from memory (they're reloaded from disk on demand). */
export function unloadAll() {
    channels.clear();
    loading.clear();
}
