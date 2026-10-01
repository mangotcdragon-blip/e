import { ReactNative } from "@vendetta/metro/common";

// Same lookup Kettu itself uses to reach Discord's native file module.
function getNativeModule(...names) {
    const nmp = window.nativeModuleProxy;
    for (const name of names) {
        if (globalThis.__turboModuleProxy) {
            const mod = globalThis.__turboModuleProxy(name);
            if (mod) return mod;
        }
        if (nmp?.[name]) return nmp[name];
    }
    return undefined;
}

const FileModule = getNativeModule("NativeFileModule", "RTNFileManager", "DCDFileManager");

const ROOT = "MessageLogger";

// iOS needs a "Documents/" prefix on older file managers (mirrors Kettu's filePathFixer).
function fixPath(path) {
    if (ReactNative.Platform.OS === "ios" && !FileModule.saveFileToGallery) return `Documents/${path}`;
    return path;
}

function docsDir() {
    const c = FileModule.getConstants?.() ?? FileModule;
    return c.DocumentsDirPath;
}

export function fullPath(path) {
    return `${docsDir()}/${ROOT}/${path}`;
}

export async function exists(path) {
    return FileModule.fileExists(fullPath(path));
}

export async function read(path) {
    return FileModule.readFile(fullPath(path), "utf8");
}

export async function readJSON(path, fallback) {
    try {
        if (!(await exists(path))) return fallback;
        return JSON.parse(await read(path));
    } catch {
        return fallback;
    }
}

/** Writes a file and resolves with its absolute path. */
export async function write(path, data) {
    await FileModule.writeFile("documents", fixPath(`${ROOT}/${path}`), data, "utf8");
    return fullPath(path);
}

export async function remove(path) {
    try {
        await FileModule.removeFile?.("documents", fixPath(`${ROOT}/${path}`));
    } catch {}
}
