import { logger } from "@vendetta";
import { findByProps } from "@vendetta/metro";
import { before } from "@vendetta/patcher";
import { storage } from "@vendetta/plugin";

import Settings from "./Settings";
import { translate } from "./translate";

/** Start a message with this to send it unchanged (the prefix is removed). */
export const BYPASS_PREFIX = "\\";

const DEFAULTS = {
    enabled: true,
    edits: true,
    verbs: true,
    slang: true,
};

let patches: Array<() => void> = [];

export function rewrite(content: unknown): unknown {
    if (typeof content !== "string" || !content.trim()) return content;
    if (content.startsWith(BYPASS_PREFIX)) return content.slice(BYPASS_PREFIX.length);
    try {
        return translate(content, { verbs: !!storage.verbs, slang: !!storage.slang });
    } catch (e) {
        logger.error("[EarlyModernEnglish] translate failed", e);
        return content;
    }
}

export default {
    onLoad: () => {
        for (const key of Object.keys(DEFAULTS) as Array<keyof typeof DEFAULTS>) {
            storage[key] ??= DEFAULTS[key];
        }

        const MessageActions = findByProps("sendMessage", "receiveMessage");
        if (!MessageActions) {
            logger.error("[EarlyModernEnglish] MessageActions not found; messages will not be rewritten");
            return;
        }

        // sendMessage(channelId, { content, ... }, ...)
        patches.push(
            before("sendMessage", MessageActions, (args) => {
                const message = args[1];
                if (storage.enabled && message) message.content = rewrite(message.content);
            }),
        );

        // editMessage(channelId, messageId, { content, ... })
        if (typeof MessageActions.editMessage === "function") {
            patches.push(
                before("editMessage", MessageActions, (args) => {
                    const message = args[2];
                    if (storage.enabled && storage.edits && message) message.content = rewrite(message.content);
                }),
            );
        }
    },

    onUnload: () => {
        for (const unpatch of patches) unpatch();
        patches = [];
    },

    settings: Settings,
};
