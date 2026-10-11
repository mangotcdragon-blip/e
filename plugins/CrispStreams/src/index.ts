import { logger } from "@vendetta";
import { findByStoreName } from "@vendetta/metro";
import { FluxDispatcher } from "@vendetta/metro/common";
import { before, instead } from "@vendetta/patcher";
import { storage } from "@vendetta/plugin";

import Settings from "./Settings";
import { getSize, initStorage } from "./config";

/*
 * Why streams look bad on mobile
 * ------------------------------
 * Each Go Live (stream) connection has a GoLiveQualityManager. When it asks the
 * voice server for the stream it sends "media sink wants":
 *
 *     { [ssrc]: 100, pixelCounts: { [ssrc]: width * height * zoom * zoom } }
 *
 * width/height come from VIDEO_SIZE_UPDATE, i.e. the size of the video tile on
 * screen. On a phone that size is in layout points (about 400x225 for a
 * portrait tile), so Discord asks for roughly 90 000 pixels, a bit over 360p.
 * The server then serves a downscaled stream (or the low simulcast layer)
 * and it looks blurry and blocky, even after you go full screen.
 *
 * This plugin makes the manager report a large viewport instead, and with
 * simulcast it subscribes to the highest quality layer.
 */

let patches: Array<() => void> = [];
let managerProto: any = null;
let fluxHandlers: Array<[string, (...args: any[]) => void]> = [];

function debug(...args: any[]) {
    if (storage.debug) logger.log("[CrispStreams]", ...args);
}

function getConnections(): any[] {
    try {
        const store = findByStoreName("StreamRTCConnectionStore");
        const conns = store?.getRTCConnections?.();
        return conns ? Object.values(conns) : [];
    } catch {
        return [];
    }
}

/** Re-send the wants for every open stream so a settings change applies now. */
export function refresh() {
    for (const conn of getConnections()) {
        try {
            conn?._goLiveQualityManager?.update?.();
        } catch (e) {
            logger.error("[CrispStreams] refresh failed", e);
        }
    }
}

function hookManager(manager: any) {
    if (managerProto || !manager) return false;
    const proto = Object.getPrototypeOf(manager);
    if (typeof proto?.request !== "function") return false;
    managerProto = proto;

    // Report a big viewport while the request is built, then put the real
    // values back so nothing else sees the fake ones.
    patches.push(
        instead("request", proto, function (this: any, args, orig) {
            if (!storage.enabled) return orig(...args);

            const w = this.resolutionWidth;
            const h = this.resolutionHeight;
            const z = this.zoom;
            const size = getSize();
            const real = (w || 0) * (h || 0) * (z || 1) * (z || 1);
            if (real >= size.width * size.height) return orig(...args);

            this.resolutionWidth = size.width;
            this.resolutionHeight = size.height;
            this.zoom = 1;
            try {
                return orig(...args);
            } finally {
                this.resolutionWidth = w;
                this.resolutionHeight = h;
                this.zoom = z;
                debug(`request: ${w}x${h} zoom ${z} -> ${size.width}x${size.height}`, args[0]);
            }
        })
    );

    // The manager subscribes to the first active stream in the list. With
    // simulcast that can be the low layer, so put the best layer first.
    if (typeof proto.updateAudioAndVideoStreamInfo === "function") {
        patches.push(
            before("updateAudioAndVideoStreamInfo", proto, (args) => {
                if (!storage.enabled || !storage.preferBestLayer) return;
                const streams = args[1];
                if (!Array.isArray(streams) || streams.length < 2) return;
                args[1] = [...streams].sort((a, b) => {
                    const active = Number(!!b?.active) - Number(!!a?.active);
                    return active !== 0 ? active : (b?.quality ?? 0) - (a?.quality ?? 0);
                });
                debug("stream layers", args[1]);
            })
        );
    }

    debug("hooked GoLiveQualityManager");
    refresh();
    return true;
}

function scan() {
    if (managerProto) return;
    for (const conn of getConnections()) {
        if (hookManager(conn?._goLiveQualityManager)) return;
    }
}

/**
 * Before the manager has been found (no stream opened yet, or a Discord build
 * where the class moved), grow the tile size in VIDEO_SIZE_UPDATE itself. The
 * RTC stores are the only listeners for that action.
 */
function patchDispatch() {
    patches.push(
        before("dispatch", FluxDispatcher, (args) => {
            const action = args[0];
            if (!action || action.type !== "VIDEO_SIZE_UPDATE") return;
            if (!storage.enabled || managerProto) return;
            const size = getSize();
            const d = action.dimensions;
            if (d && d.width * d.height >= size.width * size.height) return;
            args[0] = { ...action, dimensions: { width: size.width, height: size.height }, zoom: 1 };
            debug("VIDEO_SIZE_UPDATE", d, "->", args[0].dimensions);
        })
    );
}

function subscribe(type: string) {
    // Stores handle the action first; look for the new connection afterwards.
    const handler = () => setTimeout(scan, 0);
    try {
        FluxDispatcher.subscribe(type, handler);
        fluxHandlers.push([type, handler]);
    } catch (e) {
        logger.error(`[CrispStreams] could not subscribe to ${type}`, e);
    }
}

export default {
    onLoad: () => {
        initStorage();
        try {
            patchDispatch();
        } catch (e) {
            logger.error("[CrispStreams] failed to patch dispatch", e);
        }
        for (const type of ["STREAM_CREATE", "STREAM_UPDATE", "RTC_CONNECTION_STATE", "VIDEO_SIZE_UPDATE"]) {
            subscribe(type);
        }
        scan();
    },

    onUnload: () => {
        for (const [type, handler] of fluxHandlers) {
            try {
                FluxDispatcher.unsubscribe(type, handler);
            } catch {}
        }
        fluxHandlers = [];
        for (const unpatch of patches) {
            try {
                unpatch();
            } catch (e) {
                logger.error("[CrispStreams] failed to unpatch", e);
            }
        }
        patches = [];
        managerProto = null;
        // Go back to Discord's own request for any stream still open.
        refresh();
    },

    settings: Settings,
};
