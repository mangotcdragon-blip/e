import { ReactNative, clipboard } from "@vendetta/metro/common";
import { showToast } from "@vendetta/ui/toasts";
import { showConfirmationAlert } from "@vendetta/ui/alerts";
import { getAssetIDByName } from "@vendetta/ui/assets";
import * as store from "./store";
import * as fs from "./fs";

const pad = n => String(n).padStart(2, "0");
export function fmtTime(ms) {
    const d = new Date(ms);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function authorLabel(a) {
    const name = a.n && a.n !== a.u ? `${a.n} (@${a.u})` : `@${a.u}`;
    return `${name}${a.b ? " [BOT]" : ""} [${a.id}]`;
}

const indent = text => String(text).split("\n").join("\n    ");

export const RANGES = {
    all: { label: "Everything", ms: 0 },
    "1h": { label: "Last hour", ms: 3600e3 },
    "24h": { label: "Last 24 hours", ms: 86400e3 },
    "7d": { label: "Last 7 days", ms: 7 * 86400e3 },
    "30d": { label: "Last 30 days", ms: 30 * 86400e3 },
};

/**
 * options: { range: keyof RANGES, user?: string (id or part of a name), deletedOnly?: boolean }
 */
export async function buildText(channelId, options = {}) {
    const ch = await store.getChannel(channelId);
    const meta = store.getIndex()[channelId] ?? {};
    const since = RANGES[options.range]?.ms ? Date.now() - RANGES[options.range].ms : 0;
    const userQuery = options.user?.trim().toLowerCase().replace(/^@/, "");

    const byId = ch.ids;
    const lines = [];
    let count = 0;

    for (const m of ch.messages) {
        if (m.t < since) continue;
        if (options.deletedOnly && !m.d) continue;
        if (userQuery) {
            const a = m.a ?? {};
            const hay = [a.id, a.u, a.n].filter(Boolean).map(s => s.toLowerCase());
            if (!hay.some(s => s === userQuery || s.includes(userQuery))) continue;
        }
        count++;

        let line = `[${fmtTime(m.t)}] ${authorLabel(m.a ?? {})}`;
        if (m.d) line += ` [DELETED ${fmtTime(m.d)}]`;
        if (m.e?.length) line += ` [EDITED x${m.e.length}]`;
        lines.push(line);

        if (m.r) {
            const replied = byId.get(m.r);
            lines.push(replied
                ? `    > replying to @${replied.a?.u}: ${truncate(replied.c, 80)}`
                : `    > replying to message ${m.r}`);
        }
        if (m.e?.length) {
            for (const v of m.e) lines.push(`    (before edit at ${fmtTime(v.t)}): ${indent(v.c || "<empty>")}`);
            lines.push(`    (current):`);
        }
        if (m.c) lines.push(`    ${indent(m.c)}`);
        for (const e of m.em ?? []) lines.push(`    [embed] ${indent(e)}`);
        for (const s of m.st ?? []) lines.push(`    [sticker] ${s}`);
        for (const url of m.at ?? []) lines.push(`    [attachment] ${url}`);
        lines.push("");
    }

    const header = [
        `${meta.guildName ? `${meta.guildName} / ` : ""}${meta.name ?? channelId}`,
        `Channel ID: ${channelId}`,
        `Exported: ${fmtTime(Date.now())}`,
        `Range: ${RANGES[options.range]?.label ?? RANGES.all.label}`
            + (userQuery ? ` | User filter: ${options.user}` : "")
            + (options.deletedOnly ? " | Deleted messages only" : ""),
        `Messages: ${count}`,
        "=".repeat(60),
        "",
    ];

    return { text: header.concat(lines).join("\n"), count, meta };
}

function truncate(s, n) {
    s = (s ?? "").replace(/\s+/g, " ");
    return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}

function fileName(channelId, meta) {
    const clean = s => (s ?? "").replace(/[^\w\-]+/g, "_").replace(/_+/g, "_").replace(/^_|_$/g, "").slice(0, 40);
    const d = new Date();
    const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}`;
    return [clean(meta.guildName), clean(meta.name) || channelId, stamp].filter(Boolean).join("_") + ".txt";
}

// Android share intents fail above ~1MB, so long exports are shared in parts.
const SHARE_CHUNK = 400_000;

function chunk(text) {
    if (text.length <= SHARE_CHUNK) return [text];
    const parts = [];
    let i = 0;
    while (i < text.length) {
        let end = Math.min(i + SHARE_CHUNK, text.length);
        if (end < text.length) {
            const nl = text.lastIndexOf("\n\n", end);
            if (nl > i) end = nl + 2;
        }
        parts.push(text.slice(i, end));
        i = end;
    }
    return parts;
}

async function shareParts(title, parts, idx = 0) {
    const label = parts.length > 1 ? `${title} (part ${idx + 1}/${parts.length})` : title;
    const body = parts.length > 1 ? `--- ${label} ---\n${parts[idx]}` : parts[idx];
    await ReactNative.Share.share({ title: label, message: body }, { subject: label, dialogTitle: label });
    if (idx + 1 < parts.length) {
        showConfirmationAlert({
            title: "Next part",
            content: `Shared part ${idx + 1} of ${parts.length}. Share part ${idx + 2}?`,
            confirmText: "Share next",
            cancelText: "Stop",
            onConfirm: () => shareParts(title, parts, idx + 1),
        });
    }
}

/**
 * Saves the export as a .txt file, then does `mode`:
 *  - "share": opens the system share sheet (Save to Files / Drive / send to an app)
 *  - "copy":  copies to clipboard
 *  - "save":  only saves the file
 */
export async function exportChannel(channelId, options = {}, mode = "share") {
    try {
        await store.flush();
        const { text, count, meta } = await buildText(channelId, options);
        if (!count) {
            showToast("No messages match that filter", getAssetIDByName("ic_warning_24px"));
            return;
        }

        const name = fileName(channelId, meta);
        const path = await fs.write(`exports/${name}`, text);

        if (mode === "copy") {
            clipboard.setString(text);
            showToast(`Copied ${count} messages`, getAssetIDByName("toast_copy_link"));
        } else if (mode === "share") {
            if (ReactNative.Platform.OS === "ios") {
                // iOS can share the real file, which gives a proper .txt in "Save to Files".
                await ReactNative.Share.share({ url: `file://${path}`, title: name });
            } else {
                await shareParts(name, chunk(text));
            }
        } else {
            showToast(`Saved ${count} messages`, getAssetIDByName("Check"));
        }
        return path;
    } catch (e) {
        console.error("[MessageLogger] export failed", e);
        showToast(`Export failed: ${e?.message ?? e}`, getAssetIDByName("Small"));
    }
}
