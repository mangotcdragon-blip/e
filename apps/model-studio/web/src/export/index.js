// Every "Save as" option: 3D model formats, Minecraft formats and animation formats.
import { GLTFExporter } from 'three/examples/jsm/exporters/GLTFExporter.js';
import { STLExporter } from 'three/examples/jsm/exporters/STLExporter.js';
import { PLYExporter } from 'three/examples/jsm/exporters/PLYExporter.js';
import { USDZExporter } from 'three/examples/jsm/exporters/USDZExporter.js';
import { exportOBJ } from './obj.js';
import { exportJavaOriginal, exportJavaVoxel, exportBedrockGeo } from './minecraft.js';
import * as THREE from 'three';
import { zip, json, safeName, splitMultiMaterial } from './util.js';
import { toMsanim } from '../anim/format.js';
import { clipsToBedrock, clipsToThree, slug } from '../anim/convert.js';
import { dataUrlToBlob } from '../fileset.js';
import { collectMaterials } from '../loaders/index.js';

export const MODEL_FORMATS = [
	{ id: 'glb', label: 'GLB', desc: 'Best all-round format. Keeps textures, glow and animations in one file.' },
	{ id: 'gltf', label: 'glTF', desc: 'Text version of GLB, everything embedded.' },
	{ id: 'obj', label: 'OBJ', desc: 'Works almost everywhere. Saved as a .zip with the .mtl and textures.' },
	{ id: 'stl', label: 'STL', desc: 'For 3D printing. Shape only, no colours.' },
	{ id: 'ply', label: 'PLY', desc: 'Shape with vertex colours.' },
	{ id: 'usdz', label: 'USDZ', desc: 'For Apple AR Quick Look.' },
	{ id: 'mc-java', label: 'Minecraft Java', desc: 'Block/item model .json with textures, as a resource pack .zip.' },
	{ id: 'mc-bedrock', label: 'Minecraft Bedrock', desc: 'Entity .geo.json with texture (for Bedrock add-ons and GeckoLib).' },
	{ id: 'png', label: 'Picture', desc: 'A screenshot of the current view as .png.' },
];

export const ANIM_FORMATS = [
	{ id: 'msanim', label: 'Model Studio animation', desc: '.msanim — this app\'s own animation file. Opens back here with nothing lost.' },
	{ id: 'mc-anim', label: 'Minecraft animation', desc: '.animation.json for Bedrock add-ons, GeckoLib (Java mods) and Blockbench.' },
	{ id: 'glb-anim', label: 'GLB with animation', desc: 'The model and all its animations in one .glb (Blender, Unity, web).' },
];

export function modelFormatAvailability(root) {
	const ud = root?.userData || {};
	return {
		'mc-java': ud.source === 'java' ? 'Saves the model with all parents flattened into one file.' : 'Converts the model to Minecraft cubes (voxels).',
		'mc-bedrock': ud.bones ? null : 'Only for box-based models (Bedrock .geo.json or Blockbench). Use Minecraft Java to convert other models.',
	};
}

// Returns { name, blob, note }
export async function runExport(formatId, ctx, options = {}) {
	const { viewer, player, clips } = ctx;
	const root = viewer.model;
	const name = safeName(root?.name || 'model').replace(/\s+/g, '_');
	if (formatId === 'png') return { name: `${name}.png`, blob: dataUrlToBlob(viewer.screenshot()) };
	if (!root) throw new Error('Open a model first.');

	player.resetPose();
	const prevShade = viewer.settings.faceShade, prevTex = viewer.settings.textures, prevWire = viewer.settings.wireframe;
	viewer.settings.faceShade = false;
	viewer.settings.textures = true;
	viewer.settings.wireframe = false;
	viewer.applySettings();
	try {
		switch (formatId) {
			case 'glb':
			case 'gltf':
			case 'glb-anim': {
				const binary = formatId !== 'gltf';
				const animations = clips.length ? clipsToThree(clips, player) : [];
				const restore = stripAppData(root);
				let out;
				try {
					out = await new GLTFExporter().parseAsync(root, { binary, animations, onlyVisible: true });
				} finally {
					restore();
				}
				const ext = binary ? 'glb' : 'gltf';
				return { name: `${name}.${ext}`, blob: new Blob([binary ? out : JSON.stringify(out)], { type: binary ? 'model/gltf-binary' : 'model/gltf+json' }) };
			}
			case 'obj': {
				const files = await exportOBJ(root, name);
				return { name: `${name}_obj.zip`, blob: zip(files) };
			}
			case 'stl': {
				const out = new STLExporter().parse(root, { binary: true });
				return { name: `${name}.stl`, blob: new Blob([out], { type: 'model/stl' }) };
			}
			case 'ply': {
				const out = await new Promise((res) => new PLYExporter().parse(root, res, { binary: true }));
				return { name: `${name}.ply`, blob: new Blob([out], { type: 'application/octet-stream' }) };
			}
			case 'usdz': {
				const out = await new USDZExporter().parseAsync(splitMultiMaterial(root, THREE));
				return { name: `${name}.usdz`, blob: new Blob([out], { type: 'model/vnd.usdz+zip' }) };
			}
			case 'mc-java': {
				const id = slug(name);
				if (root.userData.source === 'java' && !options.voxel) {
					const files = await exportJavaOriginal(root, id);
					return { name: `${id}_java.zip`, blob: zip(files), note: 'Saved as a resource-pack style .zip.' };
				}
				const { files, elementCount } = await exportJavaVoxel(root, id, options.resolution || 16, options.onProgress);
				return {
					name: `${id}_java.zip`,
					blob: zip(files),
					note: `Converted to ${elementCount} cubes.${elementCount > 800 ? ' That is a lot for Minecraft; try a lower detail level.' : ''}`,
				};
			}
			case 'mc-bedrock': {
				const files = await exportBedrockGeo(root, slug(name));
				return { name: `${slug(name)}_bedrock.zip`, blob: zip(files) };
			}
			case 'msanim':
				requireClips(clips);
				return { name: `${name}.msanim`, blob: new Blob([json(toMsanim(clips, root.name))], { type: 'application/json' }) };
			case 'mc-anim':
				requireClips(clips);
				return { name: `${slug(name)}.animation.json`, blob: new Blob([json(clipsToBedrock(clips, name))], { type: 'application/json' }) };
		}
		throw new Error('Unknown format');
	} finally {
		viewer.settings.faceShade = prevShade;
		viewer.settings.textures = prevTex;
		viewer.settings.wireframe = prevWire;
		viewer.applySettings();
	}
}

function requireClips(clips) {
	if (!clips.length) throw new Error('There are no animations yet. Make one in the Animate tab or open an animation file.');
}

// glTF writes userData out as "extras"; keep the app's internal bookkeeping out of saved files.
function stripAppData(root) {
	const saved = [];
	root.traverse((o) => {
		saved.push([o, o.userData]);
		o.userData = {};
	});
	for (const m of collectMaterials(root)) {
		saved.push([m, m.userData]);
		m.userData = {};
	}
	return () => {
		for (const [o, ud] of saved) o.userData = ud;
	};
}
