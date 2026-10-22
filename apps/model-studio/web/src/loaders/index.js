// Opens whatever the user picked and returns a ready-to-show model.
import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { DRACOLoader } from 'three/examples/jsm/loaders/DRACOLoader.js';
import { MeshoptDecoder } from 'three/examples/jsm/libs/meshopt_decoder.module.js';
import { OBJLoader } from 'three/examples/jsm/loaders/OBJLoader.js';
import { MTLLoader } from 'three/examples/jsm/loaders/MTLLoader.js';
import { FBXLoader } from 'three/examples/jsm/loaders/FBXLoader.js';
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';
import { PLYLoader } from 'three/examples/jsm/loaders/PLYLoader.js';
import { ColladaLoader } from 'three/examples/jsm/loaders/ColladaLoader.js';
import { ThreeMFLoader } from 'three/examples/jsm/loaders/3MFLoader.js';
import { TDSLoader } from 'three/examples/jsm/loaders/TDSLoader.js';
import { VOXLoader, VOXMesh } from 'three/examples/jsm/loaders/VOXLoader.js';
import { ext, stem } from '../fileset.js';
import { isJavaModel, loadJavaModel } from './minecraft.js';
import { isBedrockGeo, loadBedrockGeo } from './bedrock.js';
import { isBBModel, loadBBModel } from './bbmodel.js';
import { isMsanim, fromMsanim } from '../anim/format.js';
import { isBedrockAnimation, bedrockToClips } from '../anim/convert.js';
import { emissiveToGlow } from '../materials.js';

export const MODEL_EXTS = ['glb', 'gltf', 'obj', 'fbx', 'stl', 'ply', 'dae', '3mf', '3ds', 'vox', 'json', 'geo.json', 'bbmodel'];
export const ANIM_EXTS = ['msanim', 'animation.json'];
export const IMAGE_EXTS = ['png', 'jpg', 'jpeg', 'webp', 'tga'];

// Decide what the picked files are: a model (plus helpers), an animation, or a texture.
export async function classify(files) {
	const exts = files.entries.map((e) => ext(e.name));
	const modelEntry =
		files.entries.find((e) => ['glb', 'gltf', 'obj', 'fbx', 'stl', 'ply', 'dae', '3mf', '3ds', 'vox', 'geo.json', 'bbmodel'].includes(ext(e.name))) ||
		(await firstJson(files, (j) => isJavaModel(j) || isBedrockGeo(j) || isBBModel(j)));
	const animEntries = [];
	for (const e of files.entries) {
		const x = ext(e.name);
		if (x === 'msanim' || x === 'animation.json') animEntries.push(e);
		else if (x === 'json' && e !== modelEntry) {
			const j = await readJson(e).catch(() => null);
			if (j && (isMsanim(j) || isBedrockAnimation(j))) animEntries.push(e);
		}
	}
	const images = files.entries.filter((e) => IMAGE_EXTS.includes(ext(e.name)));
	return { modelEntry, animEntries, images, exts };
}

async function firstJson(files, test) {
	// Prefer files that are not obviously parents: the shortest model chain is usually the leaf
	const candidates = [];
	for (const e of files.entries) {
		if (ext(e.name) !== 'json') continue;
		const j = await readJson(e).catch(() => null);
		if (j && test(j)) candidates.push({ e, j });
	}
	if (!candidates.length) return null;
	// A model that nobody else uses as a parent is the one the user meant
	const parents = new Set(candidates.map((c) => String(c.j.parent || '').split('/').pop()));
	return (candidates.find((c) => !parents.has(stem(c.e.name))) || candidates[0]).e;
}

export async function readJson(entry) {
	const text = await entry.blob.text();
	return JSON.parse(text.replace(/^﻿/, ''));
}

export async function loadAnimations(entries, warnings) {
	const clips = [];
	for (const e of entries) {
		const json = await readJson(e);
		if (isMsanim(json)) clips.push(...fromMsanim(json));
		else if (isBedrockAnimation(json)) clips.push(...bedrockToClips(json, warnings));
		else throw new Error(`${e.name} is not an animation file this app understands.`);
	}
	return clips;
}

export async function loadModel(entry, files) {
	const kind = ext(entry.name);
	const name = stem(entry.name);
	const manager = new THREE.LoadingManager();
	manager.setURLModifier(files.urlModifier());
	let root, threeClips = [];

	switch (kind) {
		case 'glb':
		case 'gltf': {
			const loader = new GLTFLoader(manager);
			const draco = new DRACOLoader();
			draco.setDecoderPath('draco/');
			loader.setDRACOLoader(draco);
			loader.setMeshoptDecoder(MeshoptDecoder);
			// Parse with a relative base so external .bin/texture references reach the URL modifier
			const data = kind === 'glb' ? await entry.blob.arrayBuffer() : await entry.blob.text();
			const gltf = await loader.parseAsync(data, './');
			root = gltf.scene || gltf.scenes[0];
			threeClips = gltf.animations || [];
			// glTF "extras" become userData; never let them pose as the app's own bookkeeping
			root.traverse((o) => {
				for (const k of APP_OBJECT_KEYS) delete o.userData[k];
			});
			for (const m of collectMaterials(root)) for (const k of APP_MATERIAL_KEYS) delete m.userData[k];
			draco.dispose();
			break;
		}
		case 'obj': {
			const loader = new OBJLoader(manager);
			const text = await entry.blob.text();
			const mtlName = /^mtllib\s+(.+)$/m.exec(text)?.[1]?.trim();
			const mtlEntry = (mtlName && files.find(mtlName)) || files.byExt('mtl')[0];
			if (mtlEntry) {
				const mtl = new MTLLoader(manager).parse(await mtlEntry.blob.text(), '');
				mtl.preload();
				loader.setMaterials(mtl);
			}
			root = loader.parse(text);
			break;
		}
		case 'fbx': {
			root = new FBXLoader(manager).parse(await entry.blob.arrayBuffer(), '');
			threeClips = root.animations || [];
			break;
		}
		case 'stl': {
			const geo = new STLLoader(manager).parse(await entry.blob.arrayBuffer());
			root = meshFromGeometry(geo, name);
			break;
		}
		case 'ply': {
			const geo = new PLYLoader(manager).parse(await entry.blob.arrayBuffer());
			root = meshFromGeometry(geo, name);
			break;
		}
		case 'dae': {
			const dae = new ColladaLoader(manager).parse(await entry.blob.text(), '');
			root = dae.scene;
			threeClips = dae.scene.animations || [];
			break;
		}
		case '3mf':
			root = new ThreeMFLoader(manager).parse(await entry.blob.arrayBuffer());
			break;
		case '3ds': {
			const loader = new TDSLoader(manager);
			loader.setResourcePath('');
			root = loader.parse(await entry.blob.arrayBuffer(), '');
			break;
		}
		case 'vox': {
			const chunks = new VOXLoader(manager).parse(await entry.blob.arrayBuffer());
			root = new THREE.Group();
			for (const chunk of chunks) root.add(new VOXMesh(chunk));
			root.scale.setScalar(1 / 16);
			break;
		}
		default: {
			const json = await readJson(entry);
			if (isBBModel(json)) root = await loadBBModel(json, entry.name, files);
			else if (isBedrockGeo(json)) root = await loadBedrockGeo(json, entry.name, files);
			else if (isJavaModel(json)) root = await loadJavaModel(json, entry.name, files);
			else throw new Error(`${entry.name} doesn't look like a 3D model. Supported JSON models: Minecraft Java block/item models, Bedrock .geo.json and Blockbench .bbmodel.`);
		}
	}

	if (!root) throw new Error('The file did not contain a model.');
	if (!root.name || /^(AuxScene|Scene|RootNode)$/.test(root.name)) root.name = name;
	prepareModel(root);
	root.userData.fileName = entry.name;
	root.userData.format = kind;
	root.userData.warnings ||= [];
	return { root, threeClips };
}

function meshFromGeometry(geo, name) {
	if (!geo.getAttribute('normal')) geo.computeVertexNormals();
	const mat = new THREE.MeshStandardMaterial({ color: 0xcfcfcf, roughness: 0.6, vertexColors: !!geo.getAttribute('color'), name: 'material' });
	const mesh = new THREE.Mesh(geo, mat);
	mesh.name = name;
	return mesh;
}

// Make every material editable the same way (MeshStandardMaterial) and give
// every object a unique name so animations can refer to it.
export function prepareModel(root) {
	const converted = new Map();
	const names = new Set();
	let n = 0;
	root.traverse((o) => {
		if (o !== root) {
			let base = o.name || (o.isMesh ? 'mesh' : 'node') + '_' + n++;
			let name = base, i = 2;
			while (names.has(name)) name = `${base}_${i++}`;
			o.name = name;
			names.add(name);
		}
		if (!o.isMesh && !o.isPoints && !o.isLine) return;
		const conv = (m) => {
			if (!m) return m;
			if (m.isMeshStandardMaterial) return m;
			if (converted.has(m)) return converted.get(m);
			const s = new THREE.MeshStandardMaterial({
				name: m.name,
				color: m.color ? m.color.clone() : 0xffffff,
				map: m.map || null,
				normalMap: m.normalMap || null,
				alphaMap: m.alphaMap || null,
				emissive: m.emissive ? m.emissive.clone() : 0x000000,
				emissiveMap: m.emissiveMap || null,
				transparent: m.transparent,
				opacity: m.opacity,
				alphaTest: m.alphaTest,
				side: m.side,
				vertexColors: m.vertexColors,
				roughness: m.shininess !== undefined ? Math.max(0.15, 1 - Math.sqrt(m.shininess / 100)) : 0.8,
				metalness: 0,
				flatShading: m.flatShading,
				wireframe: m.wireframe,
			});
			converted.set(m, s);
			return s;
		};
		if (o.isMesh) {
			o.material = Array.isArray(o.material) ? o.material.map(conv) : conv(o.material);
			o.castShadow = o.receiveShadow = true;
		}
	});
	let i = 1;
	for (const m of collectMaterials(root)) {
		if (!m.name) m.name = `Material ${i++}`;
		if (!m.userData.glow) emissiveToGlow(m);
	}
}

// userData keys the app uses for its own bookkeeping (kept out of glTF files in export/index.js)
export const APP_MATERIAL_KEYS = ['origMap', 'original', 'glow', 'wireframe', 'mcTexture'];
export const APP_OBJECT_KEYS = ['mcBone', 'geoBone', 'elementIndex', 'cube', 'orig'];

export function collectMaterials(root) {
	const set = new Set();
	root?.traverse((o) => {
		if (!o.material) return;
		for (const m of Array.isArray(o.material) ? o.material : [o.material]) set.add(m);
	});
	return [...set];
}
