// Blockbench project files (.bbmodel): cubes, groups (bones), embedded textures and animations.
import { dataUrlToBlob, loadImage, stem } from '../fileset.js';
import { boxUV, pixelTexture, blockMaterial, missingTexture, FACES } from './cuboid.js';
import { buildBoneModel } from './bedrock.js';
import { bbAnimationsToClips } from '../anim/convert.js';

export function isBBModel(json) {
	return !!json?.meta && Array.isArray(json.elements) && ('outliner' in json || 'resolution' in json);
}

export async function loadBBModel(json, fileName, files) {
	const warnings = [];
	const res = json.resolution || { width: 16, height: 16 };

	const materials = [];
	const texByKey = new Map();
	for (const [i, t] of (json.textures || []).entries()) {
		let img = null;
		try {
			if (t.source?.startsWith('data:')) img = await loadImage(dataUrlToBlob(t.source));
			else {
				const entry = files.find(t.relative_path || t.path || t.name);
				if (entry) img = await loadImage(entry.blob);
			}
		} catch {
			/* handled below */
		}
		if (!img) warnings.push(`Texture "${t.name}" is not embedded in the file and was not picked alongside it.`);
		const frames = t.frame_time && t.uv_height && img ? Math.max(1, Math.round(img.height / (img.width * (t.uv_height / (t.uv_width || res.width))))) : null;
		const tex = img ? pixelTexture(img, { name: t.name, frametime: t.frame_time || 1, frames }) : missingTexture();
		const mat = blockMaterial(tex, stem(t.name || `texture_${i}`));
		const entry = { index: materials.length, tw: t.uv_width || res.width, th: t.uv_height || res.height };
		texByKey.set(i, entry);
		if (t.uuid) texByKey.set(t.uuid, entry);
		materials.push(mat);
	}
	const fallback = () => {
		if (!texByKey.has('__none')) {
			texByKey.set('__none', { index: materials.length, tw: res.width, th: res.height });
			materials.push(blockMaterial(null, 'untextured'));
		}
		return texByKey.get('__none');
	};

	const elements = new Map();
	for (const el of json.elements) {
		const type = el.type || 'cube';
		if (type !== 'cube') {
			if (type === 'mesh') warnings.push('Blockbench mesh elements are not supported yet and were skipped.');
			continue;
		}
		if (el.visibility === false) continue;
		const size = el.to.map((v, i) => v - el.from[i]);
		const auto = el.box_uv ? boxUV(el.uv_offset || [0, 0], size, el.mirror_uv) : null;
		const faces = {};
		for (const dir of FACES) {
			const f = el.faces?.[dir];
			if (f && f.texture === null) continue;
			const tex = f?.texture !== undefined && f?.texture !== false ? texByKey.get(f.texture) : texByKey.get(0);
			const t = tex || fallback();
			const uv = f?.uv && !(el.box_uv && f.uv.every((x) => x === 0)) ? f.uv : auto?.[dir];
			if (!uv) continue;
			faces[dir] = { uv, material: t.index, rotation: f?.rotation || 0, tw: t.tw, th: t.th };
		}
		elements.set(el.uuid, {
			name: el.name,
			from: el.from,
			to: el.to,
			inflate: el.inflate || 0,
			faces,
			pivot: el.origin || [0, 0, 0],
			rotation: el.rotation && el.rotation.some((r) => r) ? el.rotation : null,
		});
	}

	// Groups live either inline in the outliner (older files) or in json.groups (newer files).
	const groupData = new Map((json.groups || []).map((g) => [g.uuid, g]));
	const bones = [];
	const rootCubes = [];
	const usedNames = new Set();
	const uniqueName = (n) => {
		let name = n || 'bone', i = 2;
		while (usedNames.has(name)) name = `${n}_${i++}`;
		usedNames.add(name);
		return name;
	};
	const walk = (items, parent) => {
		for (const item of items || []) {
			if (typeof item === 'string') {
				const el = elements.get(item);
				if (!el) continue;
				if (parent) parent.cubes.push(el);
				else rootCubes.push(el);
				continue;
			}
			const g = { ...(groupData.get(item.uuid) || {}), ...item };
			if (g.visibility === false) continue;
			const bone = {
				name: uniqueName(g.name),
				uuid: g.uuid,
				parent: parent ? parent.name : null,
				pivot: g.origin || [0, 0, 0],
				rotation: g.rotation || [0, 0, 0],
				cubes: [],
			};
			bones.push(bone);
			walk(item.children, bone);
		}
	};
	walk(json.outliner || [...elements.keys()], null);
	if (rootCubes.length) bones.unshift({ name: uniqueName('root_cubes'), parent: null, pivot: [0, 0, 0], rotation: [0, 0, 0], cubes: rootCubes });

	const root = buildBoneModel(bones, materials, res.width, res.height, json.name || stem(fileName));
	root.userData.source = 'bbmodel';
	root.userData.warnings = warnings;
	root.userData.geo = { id: 'geometry.' + (json.model_identifier || json.name || stem(fileName)), tw: res.width, th: res.height };
	// Blockbench "java_block" models are authored in 0..16 block space; centre them like Java models.
	if (json.meta.model_format === 'java_block' || json.meta.model_format === 'free') {
		for (const child of root.children) child.position.add({ x: -8, y: -8, z: -8 });
		root.userData.bbOffset = [-8, -8, -8];
	}
	const boneByUuid = new Map(bones.filter((b) => b.uuid).map((b) => [b.uuid, b.name]));
	root.userData.clips = bbAnimationsToClips(json.animations || [], boneByUuid, warnings);
	return root;
}
