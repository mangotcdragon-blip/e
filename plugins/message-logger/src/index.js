import { storage } from "@vendetta/plugin";
import { registerCommand } from "@vendetta/commands";
import * as store from "./store";
import * as logger from "./logger";
import { exportChannel, RANGES } from "./export";
import Settings from "./Settings";

const defaults = {
    enabled: true,
    logDMs: false,
    logBots: true,
    logHistory: true,
    guildMode: "all",
    selectedGuilds: {},
    ignoredGuilds: {},
    ignoredChannels: {},
    maxPerChannel: 10000,
};
for (const [k, v] of Object.entries(defaults)) storage[k] ??= v;

let unregister = [];
let ready = false;

export async function onLoad() {
    await store.init();
    ready = true;
    logger.start();

    unregister.push(registerCommand({
        name: "exportlog",
        displayName: "exportlog",
        description: "Export this channel's MessageLogger log as a .txt file",
        displayDescription: "Export this channel's MessageLogger log as a .txt file",
        applicationId: "-1",
        inputType: 1,
        type: 1,
        options: [
            {
                name: "range",
                displayName: "range",
                description: "How far back to export (default: everything)",
                displayDescription: "How far back to export (default: everything)",
                type: 3,
                required: false,
                choices: Object.entries(RANGES).map(([value, r]) => ({
                    name: r.label, displayName: r.label, value,
                })),
            },
            {
                name: "user",
                displayName: "user",
                description: "Only messages from this user ID / username",
                displayDescription: "Only messages from this user ID / username",
                type: 3,
                required: false,
            },
            {
                name: "deleted",
                displayName: "deleted",
                description: "Only deleted messages",
                displayDescription: "Only deleted messages",
                type: 5,
                required: false,
            },
        ],
        execute(args, ctx) {
            const get = n => args.find(a => a.name === n)?.value;
            exportChannel(ctx.channel.id, {
                range: get("range") ?? "all",
                user: get("user"),
                deletedOnly: !!get("deleted"),
            }, "share");
        },
    }));
}

export function onUnload() {
    logger.stop();
    for (const u of unregister) u?.();
    unregister = [];
    if (ready) store.flush().finally(store.unloadAll);
    ready = false;
}

export const settings = Settings;
