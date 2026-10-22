// Bundles the app into dist/ (which the Android build copies into the APK).
import * as esbuild from 'esbuild';
import { cpSync, mkdirSync, rmSync } from 'node:fs';

const watch = process.argv.includes('--watch');
rmSync('dist', { recursive: true, force: true });
mkdirSync('dist/draco', { recursive: true });
cpSync('index.html', 'dist/index.html');
cpSync('styles.css', 'dist/styles.css');
for (const f of ['draco_decoder.js', 'draco_decoder.wasm', 'draco_wasm_wrapper.js'])
	cpSync(`node_modules/three/examples/jsm/libs/draco/gltf/${f}`, `dist/draco/${f}`);

const options = {
	entryPoints: ['src/main.js'],
	bundle: true,
	minify: !watch,
	sourcemap: watch ? 'inline' : false,
	format: 'iife',
	target: ['chrome80'],
	outfile: 'dist/app.js',
	logLevel: 'info',
};
if (watch) {
	const ctx = await esbuild.context(options);
	await ctx.watch();
	await ctx.serve({ servedir: 'dist', port: 5173 });
	console.log('Serving on http://localhost:5173');
} else {
	await esbuild.build(options);
}
