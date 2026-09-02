import { defineCorePlugin } from "..";
import { startDecoyGuard, stopDecoyGuard } from "./guard";
import Settings from "./Settings";

// Disabled by default on purpose: go into this plugin's Settings, set a real
// unlock phrase and at least one decoy channel FIRST, then enable it here in
// Settings > Plugins. Enabling it before configuring does nothing (see the
// isConfigured() guard in guard.ts) rather than hiding everything with no
// way back in - but don't rely on that, configure first anyway.
export const preenabled = false;

export default defineCorePlugin({
    manifest: {
        id: "kettu.decoyguard",
        version: "0.1.0",
        type: "plugin",
        spec: 3,
        main: "",
        display: {
            name: "Decoy Guard",
            description: "Hides your real DMs behind a chosen set of decoy conversations until you type a secret phrase",
            authors: [{ name: "you" }],
        },
    },

    start() {
        startDecoyGuard();
    },

    stop() {
        stopDecoyGuard();
    },

    SettingsComponent: Settings,
});
