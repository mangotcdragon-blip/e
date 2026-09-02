import { createStorage } from "@lib/api/storage";

export interface DecoyMessage {
    /** true = shown as sent by you, false = shown as sent by the other person */
    fromMe: boolean;
    text: string;
}

export interface DecoyChannelConfig {
    /** just a label so you can tell channels apart while editing this file/JSON - purely cosmetic */
    label: string;
    messages: DecoyMessage[];
}

export interface DecoyGuardStorage {
    /**
     * Type this into ANY DM's message box and hit send while locked to unlock.
     * It's intercepted before it ever reaches Discord's servers - never actually sent.
     * Typing it again while unlocked re-locks immediately (panic button).
     * CHANGE THIS from the default before relying on it.
     */
    unlockPhrase: string;
    /**
     * channelId -> decoy content. Only channels listed here stay visible in your DM
     * list while locked; every other DM/group is hidden entirely. The channels listed
     * here show THIS fake content instead of their real messages while locked.
     */
    channels: Record<string, DecoyChannelConfig>;
}

export const decoyGuardStorage = createStorage<DecoyGuardStorage>("kettu/decoyguard.json", {
    dflt: {
        unlockPhrase: "CHANGE-ME-IN-SETTINGS",
        channels: {},
    },
});
