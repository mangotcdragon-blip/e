// Shared geometry code for box-based models (Minecraft Java JSON, Bedrock
// geometry, Blockbench). Coordinates are in "pixels" (1/16 of a block); UV
// rectangles are normalised to 0..1 with v measured from the top of the image.
import * as THREE from 'three';

export const FACES = ['north', 'east', 'south', 'west', 'up', 'down'];

const NORMALS = {
	north: [0, 0, -1],
	south: [0, 0, 1],
	west: [-1, 0, 0],
	east: [1, 0, 0],
	up: [0, 1, 0],
	down: [0, -1, 0],
};

// Minecraft's built-in directional shading, which the game applies even with no lights.
export const MC_SHADE = { up: 1, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6 };

// Face corners as seen from outside the box: top-left, top-right, bottom-right, bottom-left.
export function faceCorners(dir, f, t) {
	switch (dir) {
		case 'north':
			return [[t[0], t[1], f[2]], [f[0], t[1], f[2]], [f[0], f[1], f[2]], [t[0], f[1], f[2]]];
		case 'south':
			return [[f[0], t[1], t[2]], [t[0], t[1], t[2]], [t[0], f[1], t[2]], [f[0], f[1], t[2]]];
		case 'west':
			return [[f[0], t[1], f[2]], [f[0], t[1], t[2]], [f[0], f[1], t[2]], [f[0], f[1], f[2]]];
		case 'east':
			return [[t[0], t[1], t[2]], [t[0], t[1], f[2]], [t[0], f[1], f[2]], [t[0], f[1], t[2]]];
		case 'up':
			return [[f[0], t[1], f[2]], [t[0], t[1], f[2]], [t[0], t[1], t[2]], [f[0], t[1], t[2]]];
		case 'down':
			return [[f[0], f[1], t[2]], [t[0], f[1], t[2]], [t[0], f[1], f[2]], [f[0], f[1], f[2]]];
	}
}

// Minecraft's automatic UVs (in 0..16 units) for a face that does not list its own.
export function autoUV(dir, f, t) {
	switch (dir) {
		case 'down':
			return [f[0], 16 - t[2], t[0], 16 - f[2]];
		case 'up':
			return [f[0], f[2], t[0], t[2]];
		case 'north':
			return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]];
		case 'south':
			return [f[0], 16 - t[1], t[0], 16 - f[1]];
		case 'west':
			return [f[2], 16 - t[1], t[2], 16 - f[1]];
		case 'east':
			return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]];
	}
}

// Blockbench/Bedrock "box UV" layout, in texture pixels.
export function boxUV(uv, size, mirror) {
	const [u, v] = uv;
	const [w, h, d] = size.map((s) => Math.floor(s + 1e-6));
	const faces = {
		east: [u, v + d, u + d, v + d + h],
		north: [u + d, v + d, u + d + w, v + d + h],
		west: [u + d + w, v + d, u + 2 * d + w, v + d + h],
		south: [u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h],
		up: [u + d + w, v + d, u + d, v],
		down: [u + d + 2 * w, v, u + d + w, v + d],
	};
	if (mirror) {
		for (const k in faces) {
			const r = faces[k];
			faces[k] = [r[2], r[1], r[0], r[3]];
		}
		[faces.east, faces.west] = [faces.west, faces.east];
	}
	return faces;
}

export class GeometryBuilder {
	constructor() {
		this.quads = [];
	}

	// corners: 4 points (TL, TR, BR, BL as seen from outside); uv: [u1,v1,u2,v2] in 0..1
	addQuad(corners, uv, { material = 0, rotation = 0, tint = null, shade = 1, normal = null } = {}) {
		const [u1, v1, u2, v2] = uv;
		const uvc = [[u1, v1], [u2, v1], [u2, v2], [u1, v2]];
		const k = (((rotation / 90) | 0) % 4 + 4) % 4;
		const uvs = [0, 1, 2, 3].map((i) => uvc[(i - k + 4) % 4]);
		this.quads.push({ corners, uvs, material, tint, shade, normal });
	}

	addBox(from, to, faces, { inflate = 0 } = {}) {
		const f = from.map((x, i) => Math.min(x, to[i]) - inflate);
		const t = to.map((x, i) => Math.max(x, from[i]) + inflate);
		for (const dir of FACES) {
			const face = faces[dir];
			if (!face) continue;
			this.addQuad(faceCorners(dir, f, t), face.uv, {
				material: face.material ?? 0,
				rotation: face.rotation || 0,
				tint: face.tint ?? null,
				shade: face.shade === false ? 1 : MC_SHADE[dir],
				normal: NORMALS[dir],
			});
		}
	}

	build() {
		const quads = this.quads.slice().sort((a, b) => a.material - b.material);
		const pos = [], nor = [], uv = [], shade = [], tint = [], index = [];
		const geo = new THREE.BufferGeometry();
		let groupStart = 0, groupMat = quads.length ? quads[0].material : 0;
		const a = new THREE.Vector3(), b = new THREE.Vector3(), c = new THREE.Vector3(), n = new THREE.Vector3();
		quads.forEach((q, qi) => {
			if (q.material !== groupMat) {
				geo.addGroup(groupStart, qi * 6 - groupStart, groupMat);
				groupStart = qi * 6;
				groupMat = q.material;
			}
			a.fromArray(q.corners[0]);
			b.fromArray(q.corners[1]).sub(a);
			c.fromArray(q.corners[3]).sub(a);
			n.crossVectors(c, b); // outward for TL,TR,BR,BL listed clockwise from outside
			let flip = false;
			if (q.normal && n.lengthSq() > 1e-12) flip = n.dot(new THREE.Vector3(...q.normal)) < 0;
			const normal = q.normal || (n.lengthSq() > 1e-12 ? n.normalize().toArray() : [0, 1, 0]);
			const base = pos.length / 3;
			for (let i = 0; i < 4; i++) {
				pos.push(...q.corners[i]);
				nor.push(...normal);
				uv.push(q.uvs[i][0], 1 - q.uvs[i][1]);
				shade.push(q.shade);
				const t = q.tint || [1, 1, 1];
				tint.push(t[0], t[1], t[2]);
			}
			if (flip) index.push(base, base + 1, base + 2, base, base + 2, base + 3);
			else index.push(base, base + 3, base + 2, base, base + 2, base + 1);
		});
		if (quads.length) geo.addGroup(groupStart, quads.length * 6 - groupStart, groupMat);
		geo.setIndex(index);
		geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
		geo.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
		geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
		geo.setAttribute('color', new THREE.Float32BufferAttribute(tint.slice(), 3));
		geo.userData.tint = tint;
		geo.userData.shade = shade;
		return geo;
	}
}

// Recompute vertex colours = tint x (Minecraft face shade, if enabled).
export function applyFaceShade(geo, enabled) {
	const { tint, shade } = geo.userData;
	if (!tint) return;
	const col = geo.getAttribute('color');
	for (let i = 0; i < shade.length; i++) {
		const s = enabled ? shade[i] : 1;
		col.setXYZ(i, tint[i * 3] * s, tint[i * 3 + 1] * s, tint[i * 3 + 2] * s);
	}
	col.needsUpdate = true;
}

// Pixel-art friendly texture from an <img>, with support for vertical
// animation strips (Minecraft .mcmeta style): UVs address a single frame.
export function pixelTexture(img, { frames = null, frametime = 1, name = '' } = {}) {
	const tex = new THREE.Texture(img);
	tex.name = name;
	tex.colorSpace = THREE.SRGBColorSpace;
	tex.magFilter = THREE.NearestFilter;
	tex.minFilter = THREE.NearestFilter;
	tex.generateMipmaps = false;
	tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
	tex.needsUpdate = true;
	const count = frames ?? (img.height > img.width && img.height % img.width === 0 ? img.height / img.width : 1);
	if (count > 1) {
		tex.repeat.set(1, 1 / count);
		tex.offset.set(0, 1 - 1 / count);
		tex.userData.frames = count;
		tex.userData.frametime = frametime;
	}
	return tex;
}

export function hasTranslucency(img) {
	const data = imageData(img);
	if (!data) return { cutout: false, translucent: false };
	let cutout = false, translucent = false;
	for (let i = 3; i < data.data.length; i += 4) {
		const a = data.data[i];
		if (a === 0) cutout = true;
		else if (a < 250) translucent = true;
	}
	return { cutout, translucent };
}

const dataCache = new WeakMap();
export function imageData(img) {
	if (!img || !img.width) return null;
	if (dataCache.has(img)) return dataCache.get(img);
	const canvas = document.createElement('canvas');
	canvas.width = img.width;
	canvas.height = img.height;
	const ctx = canvas.getContext('2d', { willReadFrequently: true });
	ctx.drawImage(img, 0, 0);
	const data = ctx.getImageData(0, 0, img.width, img.height);
	dataCache.set(img, data);
	return data;
}

export function blockMaterial(tex, name) {
	const mat = new THREE.MeshStandardMaterial({
		name,
		map: tex || null,
		color: tex ? 0xffffff : 0xd9d9d9,
		roughness: 1,
		metalness: 0,
		vertexColors: true,
		side: THREE.DoubleSide,
	});
	if (tex?.image) {
		const { cutout, translucent } = hasTranslucency(tex.image);
		if (cutout) mat.alphaTest = 0.1;
		if (translucent) {
			mat.transparent = true;
			mat.depthWrite = false;
		}
	}
	return mat;
}

// The black and magenta checkerboard Minecraft shows for missing textures.
let missingTex = null;
export function missingTexture() {
	if (missingTex) return missingTex;
	const c = document.createElement('canvas');
	c.width = c.height = 16;
	const ctx = c.getContext('2d');
	ctx.fillStyle = '#000';
	ctx.fillRect(0, 0, 16, 16);
	ctx.fillStyle = '#f800f8';
	ctx.fillRect(0, 0, 8, 8);
	ctx.fillRect(8, 8, 8, 8);
	missingTex = pixelTexture(c, { name: 'missing' });
	missingTex.userData.missing = true;
	return missingTex;
}
