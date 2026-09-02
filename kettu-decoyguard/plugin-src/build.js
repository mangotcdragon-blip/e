// Rebuilds ../dist/index.js and ../dist/manifest.json from entry.js.
// Run: node build.js   (needs esbuild - `npx esbuild` or point ESBUILD_BIN at
// a local install, e.g. a Kettu clone's node_modules/.bin/esbuild)
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const here = __dirname;
const distDir = path.join(here, "..", "dist");
const rawPath = path.join(here, "raw.js");
const indexPath = path.join(distDir, "index.js");
const manifestPath = path.join(distDir, "manifest.json");

const esbuildBin = process.env.ESBUILD_BIN || "esbuild";

execFileSync(esbuildBin, [
    "entry.js",
    "--bundle",
    "--format=iife",
    "--global-name=KettuDecoyGuardPlugin",
    "--outfile=raw.js",
    "--target=es2020",
], { cwd: here, stdio: "inherit" });

let src = fs.readFileSync(rawPath, "utf8");
const prefix = "var KettuDecoyGuardPlugin = ";
if (!src.startsWith(prefix)) throw new Error("unexpected esbuild output shape - did the bundler version change?");
src = src.slice(prefix.length).trimEnd();
if (src.endsWith(";")) src = src.slice(0, -1);

fs.mkdirSync(distDir, { recursive: true });
fs.writeFileSync(indexPath, src);

const hash = crypto.createHash("sha256").update(src).digest("hex").slice(0, 16);
const manifest = {
    name: "Kettu Decoy Guard",
    description: "Hides your real DMs behind decoy conversations until you type a secret unlock phrase",
    authors: [{ name: "you" }],
    main: "index.js",
    hash,
};
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + "\n");

fs.unlinkSync(rawPath);
console.log("Built", indexPath, "hash", hash);
