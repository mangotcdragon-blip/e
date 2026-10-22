// A bag of user-supplied files (picked together, or unpacked from a zip), so a
// model can find the textures, .mtl, .bin and parent models it refers to.
import { unzipSync } from 'fflate';

export class FileSet {
	constructor() {
		this.entries = [];
		this.urls = new Map();
	}

	static async fromFiles(files) {
		const set = new FileSet();
		for (const file of files) await set.add(file.name, file);
		return set;
	}

	async add(path, blob) {
		path = path.replace(/\\/g, '/');
		if (/\.zip$/i.test(path)) {
			const zip = unzipSync(new Uint8Array(await blob.arrayBuffer()));
			for (const [inner, bytes] of Object.entries(zip)) {
				if (inner.endsWith('/') || inner.startsWith('__MACOSX/')) continue;
				await this.add(inner, new Blob([bytes], { type: mimeFor(inner) }));
			}
			return;
		}
		this.entries.push({ path, lower: path.toLowerCase(), name: basename(path), blob });
	}

	get size() {
		return this.entries.length;
	}

	// Exact path, then a path suffix ("textures/block/stone.png"), then a bare file name.
	find(ref) {
		if (!ref) return null;
		const want = decodeURIComponent(ref).replace(/\\/g, '/').replace(/^\.?\//, '').toLowerCase();
		const base = want.split('/').pop();
		return (
			this.entries.find((e) => e.lower === want) ||
			this.entries.find((e) => e.lower.endsWith('/' + want)) ||
			this.entries.find((e) => e.name.toLowerCase() === base) ||
			null
		);
	}

	byExt(...exts) {
		return this.entries.filter((e) => exts.includes(ext(e.name)));
	}

	url(entry) {
		if (!this.urls.has(entry)) this.urls.set(entry, URL.createObjectURL(entry.blob));
		return this.urls.get(entry);
	}

	// For three.js loaders: map any URL they ask for back onto a picked file.
	urlModifier() {
		return (url) => {
			if (/^(blob:|data:)/.test(url)) return url;
			const entry = this.find(url.split(/[?#]/)[0]);
			return entry ? this.url(entry) : url;
		};
	}

	dispose() {
		for (const url of this.urls.values()) URL.revokeObjectURL(url);
		this.urls.clear();
	}
}

export function basename(path) {
	return path.split('/').pop();
}

export function ext(name) {
	const m = /\.([^./]+)$/.exec(name.toLowerCase());
	if (!m) return '';
	// Two-part extensions that matter for Minecraft files
	if (/\.geo\.json$/i.test(name)) return 'geo.json';
	if (/\.animation\.json$/i.test(name)) return 'animation.json';
	return m[1];
}

export function stem(name) {
	return basename(name).replace(/\.(geo|animation)\.json$/i, '').replace(/\.[^.]+$/, '');
}

export function mimeFor(name) {
	const e = ext(name);
	return (
		{
			png: 'image/png',
			jpg: 'image/jpeg',
			jpeg: 'image/jpeg',
			webp: 'image/webp',
			json: 'application/json',
			gltf: 'model/gltf+json',
			glb: 'model/gltf-binary',
		}[e] || 'application/octet-stream'
	);
}

export function loadImage(blob) {
	return new Promise((resolve, reject) => {
		const url = URL.createObjectURL(blob);
		const img = new Image();
		img.onload = () => resolve(img);
		img.onerror = () => {
			URL.revokeObjectURL(url);
			reject(new Error('Could not read image'));
		};
		img.src = url;
	});
}

export function dataUrlToBlob(dataUrl) {
	const [head, body] = dataUrl.split(',');
	const mime = /data:([^;]+)/.exec(head)?.[1] || 'application/octet-stream';
	const bin = atob(body);
	const bytes = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
	return new Blob([bytes], { type: mime });
}
