// Minecraft exports: Java block/item model JSON (original or voxelised from any
// mesh) and Bedrock .geo.json for box-based models.
import * as THREE from 'three';
import { imageData } from '../loaders/cuboid.js';
import { imageToPng, safeName } from './util.js';
import { glowStrength } from '../materials.js';
import { collectMaterials } from '../loaders/index.js';

// Transform of obj relative to the model root (in pixels for Minecraft models).
function relTransform(root, obj) {
	root.updateMatrixWorld(true);
	const m = new THREE.Matrix4().copy(root.matrixWorld).invert().multiply(obj.matrixWorld);
	const p = new THREE.Vector3(), q = new THREE.Quaternion(), s = new THREE.Vector3();
	m.decompose(p, q, s);
	return { p, q, s };
}

function partGlow(mesh) {
	const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
	return Math.max(0, ...mats.map(glowStrength));
}

const r3 = (v) => Math.round(v * 1000) / 1000;

// Java JSON for a model that was opened from Java JSON: parents flattened,
// textures bundled, and any moved/turned/resized/hidden parts written back.
export async function exportJavaOriginal(root, name) {
	const flat = root.userData.mcFlat;
	const files = {};
	const textures = { ...flat.textures };
	const notes = [];
	const written = new Set();
	for (const m of collectMaterials(root)) {
		const ref = m.userData.mcTexture;
		const img = (m.userData.origMap ?? m.map)?.image;
		if (!ref || !img || written.has(ref) || (m.userData.origMap ?? m.map)?.userData?.missing) continue;
		written.add(ref);
		const [ns, path] = ref.includes(':') ? ref.split(':') : ['minecraft', ref];
		files[`assets/${ns}/textures/${path}.png`] = await imageToPng(img);
	}
	const elements = [];
	root.traverse((mesh) => {
		const i = mesh.userData.elementIndex;
		if (i === undefined || !mesh.isMesh) return;
		let visible = true;
		for (let o = mesh; o && o !== root; o = o.parent) visible &&= o.visible;
		if (!visible) return;
		const el = structuredClone(flat.elements[i]);
		const orig = mesh.userData.orig;
		const { p, q, s } = relTransform(root, mesh);
		const origin = [p.x + 8, p.y + 8, p.z + 8];
		const o0 = el.rotation?.origin || [8, 8, 8];
		const k = [s.x / (orig?.s.x || 1), s.y / (orig?.s.y || 1), s.z / (orig?.s.z || 1)];
		el.from = el.from.map((v, a) => r3(origin[a] + (v - o0[a]) * k[a]));
		el.to = el.to.map((v, a) => r3(origin[a] + (v - o0[a]) * k[a]));
		const e = new THREE.Euler().setFromQuaternion(q, 'XYZ');
		const angles = { x: e.x, y: e.y, z: e.z };
		const turned = Object.entries(angles).filter(([, v]) => Math.abs(v) > 1e-4);
		if (turned.length) {
			if (turned.length > 1) notes.push(`"${mesh.name}" is turned on more than one axis; Minecraft only allows one, so only the biggest turn was kept.`);
			const [axis, rad] = turned.sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))[0];
			el.rotation = { origin: origin.map(r3), axis, angle: r3(THREE.MathUtils.radToDeg(rad)), ...(el.rotation?.rescale ? { rescale: true } : {}) };
		} else delete el.rotation;
		const glow = partGlow(mesh);
		if (glow > 0) el.light_emission = Math.min(15, Math.round(glow * 3));
		elements.push(el);
	});
	const model = { credit: 'Made with Model Studio', textures };
	if (flat.display) model.display = flat.display;
	if (elements.length) model.elements = elements;
	else model.parent = 'item/generated';
	files[`assets/minecraft/models/${elements.length ? 'block' : 'item'}/${name}.json`] = JSON.stringify(model, null, '\t');
	return { files, notes };
}

// Turn any model into Minecraft Java cubes by voxelising its surface.
export async function exportJavaVoxel(root, name, resolution = 16, onProgress) {
	root.updateMatrixWorld(true);
	const box = new THREE.Box3().setFromObject(root);
	const size = box.getSize(new THREE.Vector3());
	const maxDim = Math.max(size.x, size.y, size.z) || 1;
	const N = resolution;
	const cell = maxDim / N;
	const dims = [0, 1, 2].map((i) => Math.max(1, Math.ceil(size.getComponent(i) / cell - 1e-6)));
	const grid = new Map();
	const key = (x, y, z) => (x * 1024 + y) * 1024 + z;

	const meshes = [];
	root.traverse((o) => o.isMesh && o.visible && meshes.push(o));
	const a = new THREE.Vector3(), b = new THREE.Vector3(), c = new THREE.Vector3(), p = new THREE.Vector3();
	const ua = new THREE.Vector2(), ub = new THREE.Vector2(), uc = new THREE.Vector2();
	const col = new THREE.Color(), tmp = new THREE.Color();
	let processed = 0;
	const total = meshes.reduce((n, m) => n + (m.geometry.index ? m.geometry.index.count : m.geometry.getAttribute('position').count) / 3, 0);

	for (const mesh of meshes) {
		const geo = mesh.geometry;
		const pos = geo.getAttribute('position');
		const uv = geo.getAttribute('uv');
		const vcol = geo.getAttribute('color');
		const tint = geo.userData.tint;
		const index = geo.getIndex();
		const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
		const groups = geo.groups.length ? geo.groups : [{ start: 0, count: index ? index.count : pos.count, materialIndex: 0 }];
		for (const g of groups) {
			const m = mats[g.materialIndex ?? 0];
			if (!m || m.visible === false) continue;
			const tex = m.userData.origMap ?? m.map;
			const img = tex?.image ? imageData(tex.image) : null;
			for (let i = g.start; i < g.start + g.count; i += 3) {
				const ids = [0, 1, 2].map((k) => (index ? index.getX(i + k) : i + k));
				a.fromBufferAttribute(pos, ids[0]).applyMatrix4(mesh.matrixWorld);
				b.fromBufferAttribute(pos, ids[1]).applyMatrix4(mesh.matrixWorld);
				c.fromBufferAttribute(pos, ids[2]).applyMatrix4(mesh.matrixWorld);
				if (uv) {
					ua.fromBufferAttribute(uv, ids[0]);
					ub.fromBufferAttribute(uv, ids[1]);
					uc.fromBufferAttribute(uv, ids[2]);
				}
				const longest = Math.max(a.distanceTo(b), b.distanceTo(c), c.distanceTo(a));
				const steps = Math.min(400, Math.max(1, Math.ceil(longest / (cell * 0.5))));
				for (let s = 0; s <= steps; s++)
					for (let t = 0; t <= steps - s; t++) {
						const w1 = s / steps, w2 = t / steps, w0 = 1 - w1 - w2;
						p.set(0, 0, 0).addScaledVector(a, w0).addScaledVector(b, w1).addScaledVector(c, w2);
						const x = Math.min(dims[0] - 1, Math.floor((p.x - box.min.x) / cell));
						const y = Math.min(dims[1] - 1, Math.floor((p.y - box.min.y) / cell));
						const z = Math.min(dims[2] - 1, Math.floor((p.z - box.min.z) / cell));
						col.copy(m.color || tmp.set(1, 1, 1));
						let alpha = m.opacity ?? 1;
						if (img && uv) {
							let u = ua.x * w0 + ub.x * w1 + uc.x * w2;
							let v = ua.y * w0 + ub.y * w1 + uc.y * w2;
							if (tex.userData?.frames > 1) v = v * tex.repeat.y + tex.offset.y;
							u = ((u % 1) + 1) % 1;
							v = ((v % 1) + 1) % 1;
							const px = Math.min(img.width - 1, Math.floor(u * img.width));
							const py = Math.min(img.height - 1, Math.floor((tex.flipY === false ? v : 1 - v) * img.height));
							const o = (py * img.width + px) * 4;
							tmp.setRGB(img.data[o] / 255, img.data[o + 1] / 255, img.data[o + 2] / 255, THREE.SRGBColorSpace);
							col.multiply(tmp);
							alpha *= img.data[o + 3] / 255;
						}
						if (tint) col.multiply(tmp.setRGB(tint[ids[0] * 3], tint[ids[0] * 3 + 1], tint[ids[0] * 3 + 2]));
						else if (vcol && m.vertexColors) col.multiply(tmp.fromBufferAttribute(vcol, ids[0]));
						if (alpha < 0.5) continue;
						const k = key(x, y, z);
						const cellData = grid.get(k) || { x, y, z, r: 0, g: 0, b: 0, n: 0, glow: 0 };
						cellData.r += col.r;
						cellData.g += col.g;
						cellData.b += col.b;
						cellData.n++;
						cellData.glow = Math.max(cellData.glow, glowStrength(m));
						grid.set(k, cellData);
					}
				processed++;
				if (onProgress && processed % 2000 === 0) {
					onProgress(processed / total);
					await new Promise((r) => setTimeout(r));
				}
			}
		}
	}
	if (!grid.size) throw new Error('Nothing to voxelise: the model has no visible surfaces.');

	// Reduce to a palette (max 256 colours) so neighbouring voxels can merge into bigger boxes.
	let bits = 5, palette, colorOf;
	for (; bits >= 2; bits--) {
		const q = (v) => Math.round(Math.min(1, v) * ((1 << bits) - 1));
		palette = new Map();
		colorOf = new Map();
		for (const [k, d] of grid) {
			const qc = [q(d.r / d.n), q(d.g / d.n), q(d.b / d.n)];
			const pk = qc.join(',') + (d.glow > 0 ? 'g' : '');
			if (!palette.has(pk)) palette.set(pk, { id: palette.size, rgb: qc.map((x) => x / ((1 << bits) - 1)), glow: d.glow });
			colorOf.set(k, palette.get(pk).id);
		}
		if (palette.size <= 256) break;
	}

	// Greedy box merging of same-coloured voxels
	const done = new Set();
	const boxes = [];
	const same = (x, y, z, id) => {
		const k = key(x, y, z);
		return colorOf.get(k) === id && !done.has(k);
	};
	const cells = [...grid.values()].sort((p, q) => p.y - q.y || p.z - q.z || p.x - q.x);
	for (const d of cells) {
		const k0 = key(d.x, d.y, d.z);
		if (done.has(k0)) continue;
		const id = colorOf.get(k0);
		let x1 = d.x;
		while (same(x1 + 1, d.y, d.z, id)) x1++;
		let z1 = d.z;
		grow: for (;;) {
			for (let x = d.x; x <= x1; x++) if (!same(x, d.y, z1 + 1, id)) break grow;
			z1++;
		}
		let y1 = d.y;
		growY: for (;;) {
			for (let x = d.x; x <= x1; x++) for (let z = d.z; z <= z1; z++) if (!same(x, y1 + 1, z, id)) break growY;
			y1++;
		}
		for (let x = d.x; x <= x1; x++) for (let y = d.y; y <= y1; y++) for (let z = d.z; z <= z1; z++) done.add(key(x, y, z));
		boxes.push({ from: [d.x, d.y, d.z], to: [x1 + 1, y1 + 1, z1 + 1], id });
	}

	// 16x16 palette texture, one pixel per colour
	const canvas = document.createElement('canvas');
	canvas.width = canvas.height = 16;
	const ctx = canvas.getContext('2d');
	const glowIds = new Map();
	for (const { id, rgb, glow } of palette.values()) {
		const c = new THREE.Color().setRGB(rgb[0], rgb[1], rgb[2]);
		ctx.fillStyle = '#' + c.getHexString(THREE.SRGBColorSpace);
		ctx.fillRect(id % 16, Math.floor(id / 16), 1, 1);
		if (glow > 0) glowIds.set(id, glow);
	}

	// Fit into Minecraft's 0..16 block space, centred horizontally, resting on the floor.
	const scale = 16 / N;
	const offset = [(16 - dims[0] * scale) / 2, 0, (16 - dims[2] * scale) / 2];
	const r = (v) => Math.round(v * 1000) / 1000;
	const tex = `minecraft:block/${name}`;
	const elements = boxes.map((bx) => {
		const u = bx.id % 16, v = Math.floor(bx.id / 16);
		const uv = [u + 0.25, v + 0.25, u + 0.75, v + 0.75];
		const faces = {};
		for (const dir of ['north', 'east', 'south', 'west', 'up', 'down']) faces[dir] = { uv, texture: '#0' };
		const el = {
			from: bx.from.map((x, i) => r(x * scale + offset[i])),
			to: bx.to.map((x, i) => r(x * scale + offset[i])),
			faces,
		};
		if (glowIds.has(bx.id)) el.light_emission = Math.min(15, Math.round(glowIds.get(bx.id) * 3));
		return el;
	});
	const model = { credit: 'Made with Model Studio', textures: { 0: tex, particle: tex }, elements };
	const png = new Uint8Array(await (await new Promise((res) => canvas.toBlob(res, 'image/png'))).arrayBuffer());
	return {
		files: {
			[`assets/minecraft/models/block/${name}.json`]: JSON.stringify(model, null, '\t'),
			[`assets/minecraft/textures/block/${name}.png`]: png,
		},
		elementCount: elements.length,
	};
}

// Bedrock geometry from a box-based model (Bedrock, Blockbench), including
// any parts that were moved, turned, resized or hidden.
export async function exportBedrockGeo(root, name) {
	const bones = root.userData.bones;
	if (!bones) throw new Error('Only box-based models (Bedrock, Blockbench) can be saved as Bedrock geometry.');
	const geoInfo = root.userData.geo || { tw: 64, th: 64 };
	const off = root.userData.bbOffset || [0, 0, 0];
	const flip = (v) => [-v[0], v[1], v[2]];
	const flipR = (v) => [-v[0], -v[1], v[2]];
	const r = r3;
	const R2D = THREE.MathUtils.radToDeg;
	const groups = new Map();
	root.traverse((o) => o.userData.geoBone && groups.set(o.userData.geoBone, o));
	// Bedrock pivots are in model space and ignore parent rotations, like the bones' offsets here.
	const pivotOf = (g) => {
		const parent = g.parent?.userData.geoBone ? g.parent : null;
		const base = parent ? pivotOf(parent) : [-off[0], -off[1], -off[2]];
		return [base[0] + g.position.x, base[1] + g.position.y, base[2] + g.position.z];
	};
	const outBones = bones.map((b) => {
		const g = groups.get(b);
		const pivot = g ? pivotOf(g) : b.pivot;
		const e = g ? new THREE.Euler().setFromQuaternion(g.quaternion, 'ZYX') : null;
		const rot = e ? [R2D(e.x), R2D(e.y), R2D(e.z)] : b.rotation || [0, 0, 0];
		const bone = { name: b.name, pivot: flip(pivot).map(r) };
		if (b.parent) bone.parent = b.parent;
		if (rot.some((x) => Math.abs(x) > 1e-4)) bone.rotation = flipR(rot).map(r);
		const cubes = [];
		g?.traverse((mesh) => {
			const c = mesh.userData.cube;
			if (!c || !mesh.isMesh) return;
			let visible = true;
			for (let o = mesh; o && o !== root; o = o.parent) visible &&= o.visible;
			if (!visible) return;
			// Moves/resizes of the cube itself, plus resizes of its bone, measured around the bone pivot
			const orig = mesh.userData.orig;
			const dp = orig ? mesh.position.clone().sub(orig.p) : new THREE.Vector3();
			const ws = new THREE.Vector3(1, 1, 1);
			for (let o = mesh; o && o !== g.parent; o = o.parent) {
				const os = o.userData.orig?.s;
				if (os) ws.multiply(new THREE.Vector3(o.scale.x / (os.x || 1), o.scale.y / (os.y || 1), o.scale.z / (os.z || 1)));
			}
			const k = ws.toArray();
			// Cubes are stored in model space, so they also follow their bone's pivot when it moves
			const d = dp.toArray().map((v, a) => v + pivot[a] - b.pivot[a]);
			const from = c.from.map((v, a) => b.pivot[a] + (v - b.pivot[a]) * k[a] + d[a]);
			const to = c.to.map((v, a) => b.pivot[a] + (v - b.pivot[a]) * k[a] + d[a]);
			const size = to.map((x, i) => x - from[i]);
			const cube = { origin: [-to[0], from[1], from[2]].map(r), size: size.map(r) };
			if (c.inflate) cube.inflate = c.inflate;
			if (c.rotation) {
				cube.pivot = flip(c.pivot).map(r);
				cube.rotation = flipR(c.rotation).map(r);
			}
			cube.uv = {};
			for (const [dir, f] of Object.entries(c.faces)) {
				const rect = Array.isArray(f) ? f : f.uv;
				const sx = Array.isArray(f) || !f.tw ? 1 : geoInfo.tw / f.tw;
				const sy = Array.isArray(f) || !f.th ? 1 : geoInfo.th / f.th;
				cube.uv[dir] = { uv: [r(rect[0] * sx), r(rect[1] * sy)], uv_size: [r((rect[2] - rect[0]) * sx), r((rect[3] - rect[1]) * sy)] };
			}
			cubes.push(cube);
		});
		if (cubes.length) bone.cubes = cubes;
		return bone;
	});
	const geo = {
		format_version: '1.16.0',
		'minecraft:geometry': [
			{
				description: { identifier: `geometry.${name}`, texture_width: geoInfo.tw, texture_height: geoInfo.th },
				bones: outBones,
			},
		],
	};
	const files = { [`${name}.geo.json`]: JSON.stringify(geo, null, '\t') };
	const mat = root.userData.materials?.[0];
	const tex = mat?.userData.origMap ?? mat?.map;
	if (tex?.image && !tex.userData?.missing) files[`${name}.png`] = await imageToPng(tex.image);
	return files;
}

export { safeName };
