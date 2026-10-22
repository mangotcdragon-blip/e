// Minecraft Java Edition block/item model JSON.
import * as THREE from 'three';
import { stem, loadImage } from '../fileset.js';
import {
	GeometryBuilder,
	autoUV,
	pixelTexture,
	blockMaterial,
	missingTexture,
	imageData,
	FACES,
} from './cuboid.js';

const GRASS_TINT = [0x91 / 255, 0xbd / 255, 0x59 / 255];

const face = (tex, extra = {}) => ({ texture: tex, ...extra });
const cubeFaces = (t) =>
	Object.fromEntries(FACES.map((d) => [d, face(t[d], { cullface: d })]));
const fullCube = (t, extra = {}) => ({ from: [0, 0, 0], to: [16, 16, 16], faces: cubeFaces(t), ...extra });

// Stand-ins for the vanilla parent models, so common files open without the game's assets.
const BUILTIN = {
	'block/block': {},
	'block/thin_block': {},
	'block/cube': {
		elements: [fullCube({ down: '#down', up: '#up', north: '#north', south: '#south', west: '#west', east: '#east' })],
	},
	'block/cube_all': { parent: 'block/cube', textures: { particle: '#all', down: '#all', up: '#all', north: '#all', south: '#all', west: '#all', east: '#all' } },
	'block/cube_mirrored_all': { parent: 'block/cube_all' },
	'block/leaves': {
		textures: { particle: '#all' },
		elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: Object.fromEntries(FACES.map((d) => [d, face('#all', { tintindex: 0 })])) }],
	},
	'block/cube_column': { parent: 'block/cube', textures: { particle: '#side', down: '#end', up: '#end', north: '#side', south: '#side', west: '#side', east: '#side' } },
	'block/cube_column_horizontal': { parent: 'block/cube_column' },
	'block/cube_bottom_top': { parent: 'block/cube', textures: { particle: '#side', down: '#bottom', up: '#top', north: '#side', south: '#side', west: '#side', east: '#side' } },
	'block/cube_top': { parent: 'block/cube', textures: { particle: '#side', down: '#side', up: '#top', north: '#side', south: '#side', west: '#side', east: '#side' } },
	'block/orientable': { parent: 'block/cube', textures: { particle: '#front', down: '#top', up: '#top', north: '#front', south: '#side', west: '#side', east: '#side' } },
	'block/orientable_with_bottom': { parent: 'block/cube', textures: { particle: '#front', down: '#bottom', up: '#top', north: '#front', south: '#side', west: '#side', east: '#side' } },
	'block/slab': {
		elements: [{ from: [0, 0, 0], to: [16, 8, 16], faces: { down: face('#bottom'), up: face('#top'), north: face('#side'), south: face('#side'), west: face('#side'), east: face('#side') } }],
	},
	'block/slab_top': {
		elements: [{ from: [0, 8, 0], to: [16, 16, 16], faces: { down: face('#bottom'), up: face('#top'), north: face('#side'), south: face('#side'), west: face('#side'), east: face('#side') } }],
	},
	'block/stairs': {
		elements: [
			{ from: [0, 0, 0], to: [16, 8, 16], faces: { down: face('#bottom'), up: face('#top'), north: face('#side'), south: face('#side'), west: face('#side'), east: face('#side') } },
			{ from: [8, 8, 0], to: [16, 16, 16], faces: { up: face('#top'), north: face('#side'), south: face('#side'), west: face('#side'), east: face('#side') } },
		],
	},
	'block/carpet': {
		elements: [{ from: [0, 0, 0], to: [16, 1, 16], faces: Object.fromEntries(FACES.map((d) => [d, face('#wool')])) }],
	},
	'block/cross': {
		elements: [
			{ from: [0.8, 0, 8], to: [15.2, 16, 8], rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true }, shade: false, faces: { north: face('#cross', { uv: [0, 0, 16, 16] }), south: face('#cross', { uv: [0, 0, 16, 16] }) } },
			{ from: [8, 0, 0.8], to: [8, 16, 15.2], rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true }, shade: false, faces: { west: face('#cross', { uv: [0, 0, 16, 16] }), east: face('#cross', { uv: [0, 0, 16, 16] }) } },
		],
	},
	'item/generated': { __generated: true },
	'builtin/generated': { __generated: true },
	'item/handheld': { parent: 'item/generated' },
	'item/handheld_rod': { parent: 'item/generated' },
	'builtin/entity': { __generated: true },
};
BUILTIN['block/tinted_cross'] = {
	elements: BUILTIN['block/cross'].elements.map((el) => ({
		...el,
		faces: Object.fromEntries(Object.entries(el.faces).map(([k, f]) => [k, { ...f, tintindex: 0 }])),
	})),
};

export function isJavaModel(json) {
	return json && typeof json === 'object' && !Array.isArray(json) && ('elements' in json || 'parent' in json || ('textures' in json && !('format_version' in json)));
}

function normRef(ref) {
	return String(ref).replace(/^minecraft:/, '');
}

async function resolveParent(ref, files) {
	const key = normRef(ref);
	const entry = files.find(`models/${key}.json`) || files.find(`${key}.json`);
	if (entry) {
		try {
			return { model: JSON.parse(await entry.blob.text()), from: entry.name };
		} catch {
			/* fall through to builtin */
		}
	}
	const builtin = BUILTIN[key] || BUILTIN['block/' + key] || BUILTIN['item/' + key];
	if (builtin) return { model: builtin, from: 'builtin' };
	return null;
}

async function findTexture(ref, files) {
	const key = normRef(ref);
	const [ns, path] = String(ref).includes(':') ? String(ref).split(':') : ['minecraft', ref];
	const entry =
		files.find(`assets/${ns}/textures/${path}.png`) ||
		files.find(`textures/${key}.png`) ||
		files.find(`${key}.png`);
	if (!entry) return null;
	const img = await loadImage(entry.blob);
	let frametime = 1;
	const meta = files.find(entry.path + '.mcmeta');
	if (meta) {
		try {
			frametime = JSON.parse(await meta.blob.text())?.animation?.frametime || 1;
		} catch {
			/* ignore broken mcmeta */
		}
	}
	return { img, entry, frametime };
}

export async function loadJavaModel(json, fileName, files) {
	const warnings = [];
	const chain = [json];
	let cur = json;
	for (let depth = 0; cur.parent && depth < 24; depth++) {
		const p = await resolveParent(cur.parent, files);
		if (!p) {
			warnings.push(`Parent model "${cur.parent}" was not found. Pick it together with this file (or open a resource pack .zip) to see the full model.`);
			break;
		}
		cur = p.model;
		chain.push(cur);
	}

	const textures = {};
	for (let i = chain.length - 1; i >= 0; i--) Object.assign(textures, chain[i].textures || {});
	const owner = chain.find((m) => m.elements || m.__generated);
	const resolveTex = (ref) => {
		let r = ref && typeof ref === 'object' ? ref.sprite : ref;
		for (let i = 0; i < 16 && typeof r === 'string' && r.startsWith('#'); i++) {
			const next = textures[r.slice(1)];
			r = next && typeof next === 'object' ? next.sprite : next;
		}
		return typeof r === 'string' && !r.startsWith('#') ? r : null;
	};

	// One material per distinct texture
	const matIndex = new Map();
	const materials = [];
	const texInfo = [];
	const getMaterial = async (ref) => {
		const path = resolveTex(ref);
		const key = path || '__none__';
		if (matIndex.has(key)) return matIndex.get(key);
		let tex = null, info = null;
		if (path) {
			info = await findTexture(path, files);
			tex = info ? pixelTexture(info.img, { name: path, frametime: info.frametime }) : missingTexture();
			if (!info) warnings.push(`Texture "${path}" was not found. Pick the .png together with the model to see it.`);
		}
		const mat = blockMaterial(tex, path ? stem(path) : 'untextured');
		mat.userData.mcTexture = path;
		matIndex.set(key, materials.length);
		materials.push(mat);
		texInfo.push(info);
		return materials.length - 1;
	};

	const root = new THREE.Group();
	root.name = stem(fileName);
	root.scale.setScalar(1 / 16);
	const model = new THREE.Group();
	model.name = 'model';
	model.position.set(-8, -8, -8);
	root.add(model);

	let elements = [];
	if (owner?.__generated) {
		await buildGenerated(model, textures, resolveTex, getMaterial, materials, texInfo);
	} else if (owner) {
		elements = owner.elements;
		for (let i = 0; i < elements.length; i++) {
			const el = elements[i];
			const mesh = await buildElement(el, getMaterial, materials);
			mesh.name = el.name || `element_${i}`;
			mesh.userData.elementIndex = i;
			model.add(mesh);
		}
	}

	root.userData = {
		source: 'java',
		cuboid: true,
		materials,
		warnings,
		mcFlat: { textures: Object.fromEntries(Object.keys(textures).map((k) => [k, resolveTex('#' + k) || textures[k]])), elements, display: chain.find((m) => m.display)?.display },
		mcTextures: texInfo,
	};
	return root;
}

async function buildElement(el, getMaterial, materials) {
	const from = el.from, to = el.to;
	const origin = el.rotation?.origin || [8, 8, 8];
	const builder = new GeometryBuilder();
	const faces = {};
	for (const dir of FACES) {
		const f = el.faces?.[dir];
		if (!f) continue;
		const uv = (f.uv || autoUV(dir, from, to)).map((x) => x / 16);
		faces[dir] = {
			uv,
			rotation: f.rotation || 0,
			material: await getMaterial(f.texture),
			tint: f.tintindex !== undefined && f.tintindex >= 0 ? GRASS_TINT : null,
			shade: el.shade === false ? false : true,
		};
	}
	builder.addBox(from.map((x, i) => x - origin[i]), to.map((x, i) => x - origin[i]), faces);
	const geo = builder.build();
	const mesh = new THREE.Mesh(geo, materials);
	mesh.position.fromArray(origin);
	const r = el.rotation;
	if (r && r.angle) {
		const rad = THREE.MathUtils.degToRad(r.angle);
		mesh.rotation[r.axis] = rad;
		if (r.rescale) {
			const s = 1 / Math.cos(rad);
			for (const a of ['x', 'y', 'z']) if (a !== r.axis) mesh.scale[a] = s;
		}
	}
	return mesh;
}

// item/generated: extrude each layer's opaque pixels into a 1px thick sprite.
async function buildGenerated(model, textures, resolveTex, getMaterial, materials, texInfo) {
	for (let layer = 0; layer < 5; layer++) {
		if (!textures['layer' + layer]) continue;
		const mi = await getMaterial('#layer' + layer);
		const info = texInfo[mi];
		const builder = new GeometryBuilder();
		const z0 = 7.5 - layer * 0.01, z1 = 8.5 + layer * 0.01;
		builder.addBox([0, 0, z0], [16, 16, z1], {
			south: { uv: [0, 0, 1, 1], material: mi },
			north: { uv: [1, 0, 0, 1], material: mi },
		});
		const data = info && imageData(info.img);
		if (data) {
			const w = data.width, h = Math.min(data.height, data.width);
			const s = 16 / w;
			const opaque = (x, y) => x >= 0 && y >= 0 && x < w && y < h && data.data[(y * w + x) * 4 + 3] > 0;
			for (let y = 0; y < h; y++)
				for (let x = 0; x < w; x++) {
					if (!opaque(x, y)) continue;
					const cu = (x + 0.5) / w, cv = (y + 0.5) / h;
					const px = { uv: [cu, cv, cu, cv], material: mi };
					const faces = {};
					if (!opaque(x, y - 1)) faces.up = px;
					if (!opaque(x, y + 1)) faces.down = px;
					if (!opaque(x - 1, y)) faces.west = px;
					if (!opaque(x + 1, y)) faces.east = px;
					if (Object.keys(faces).length)
						builder.addBox([x * s, 16 - (y + 1) * s, z0], [(x + 1) * s, 16 - y * s, z1], faces);
				}
		}
		const mesh = new THREE.Mesh(builder.build(), materials);
		mesh.name = 'layer' + layer;
		model.add(mesh);
	}
}
