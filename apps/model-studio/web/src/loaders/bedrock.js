// Minecraft Bedrock Edition entity geometry (.geo.json), also used by GeckoLib.
// Converted to Blockbench's axis convention (X mirrored), which is what the
// animation code and the Bedrock exporters expect.
import * as THREE from 'three';
import { loadImage, stem } from '../fileset.js';
import { GeometryBuilder, boxUV, pixelTexture, blockMaterial, FACES } from './cuboid.js';

export function isBedrockGeo(json) {
	return !!json && typeof json === 'object' && (Array.isArray(json['minecraft:geometry']) || Object.keys(json).some((k) => k.startsWith('geometry.')));
}

export function bedrockGeometries(json) {
	if (Array.isArray(json['minecraft:geometry'])) {
		return json['minecraft:geometry'].map((g) => ({
			id: g.description?.identifier || 'geometry.unknown',
			tw: g.description?.texture_width || 64,
			th: g.description?.texture_height || 64,
			bones: g.bones || [],
		}));
	}
	return Object.entries(json)
		.filter(([k]) => k.startsWith('geometry.'))
		.map(([k, g]) => ({ id: k.split(':')[0], tw: g.texturewidth || 64, th: g.textureheight || 64, bones: g.bones || [] }));
}

const flipX = (v = [0, 0, 0]) => [-v[0], v[1], v[2]];
const flipRot = (v = [0, 0, 0]) => [-v[0], -v[1], v[2]];

export async function loadBedrockGeo(json, fileName, files) {
	const warnings = [];
	const geos = bedrockGeometries(json);
	if (!geos.length) throw new Error('No geometry found in this file.');
	if (geos.length > 1) warnings.push(`This file has ${geos.length} models; showing "${geos[0].id}".`);
	const g = geos[0];

	// Texture: prefer one named like the model, otherwise any picked image.
	const images = files.byExt('png', 'jpg', 'jpeg', 'tga');
	const name = g.id.replace(/^geometry\./, '');
	const texEntry = images.find((e) => stem(e.name).toLowerCase() === name.toLowerCase()) || images.find((e) => stem(e.name).toLowerCase() === stem(fileName).toLowerCase()) || images[0];
	let tex = null;
	if (texEntry) tex = pixelTexture(await loadImage(texEntry.blob), { name: texEntry.name, frames: 1 });
	else warnings.push('No texture was picked with this model. Select the .png together with the .geo.json to see it textured.');
	const material = blockMaterial(tex, tex ? stem(texEntry.name) : name);

	const bones = g.bones.map((b) => ({
		name: b.name,
		parent: b.parent || null,
		pivot: flipX(b.pivot),
		rotation: flipRot(b.rotation),
		mirror: !!b.mirror,
		cubes: (b.cubes || []).map((c) => {
			const size = c.size || [0, 0, 0];
			const o = c.origin || [0, 0, 0];
			const from = [-(o[0] + size[0]), o[1], o[2]];
			const to = [from[0] + size[0], from[1] + size[1], from[2] + size[2]];
			let faces;
			if (Array.isArray(c.uv) || c.uv === undefined) {
				faces = boxUV(c.uv || [0, 0], size, c.mirror ?? b.mirror);
			} else {
				faces = {};
				for (const dir of FACES) {
					const f = c.uv[dir];
					if (!f) continue;
					const [u, v] = f.uv || [0, 0];
					const [w, h] = f.uv_size || [0, 0];
					faces[dir] = [u, v, u + w, v + h];
				}
			}
			return {
				from,
				to,
				inflate: c.inflate || 0,
				faces,
				pivot: c.pivot ? flipX(c.pivot) : null,
				rotation: c.rotation ? flipRot(c.rotation) : null,
			};
		}),
	}));

	const root = buildBoneModel(bones, [material], g.tw, g.th, stem(fileName));
	root.userData.source = 'bedrock';
	root.userData.warnings = warnings;
	root.userData.geo = { id: g.id, tw: g.tw, th: g.th, texture: texEntry || null };
	return root;
}

// bones: [{name, parent, pivot, rotation, cubes:[{from,to,inflate,faces:{dir:[u1,v1,u2,v2] px | {uv, material, rotation}}, pivot, rotation}]}]
export function buildBoneModel(bones, materials, tw, th, name) {
	const root = new THREE.Group();
	root.name = name;
	root.scale.setScalar(1 / 16);
	const groups = new Map();
	for (const b of bones) {
		const grp = new THREE.Group();
		grp.name = b.name;
		grp.userData.mcBone = true;
		grp.userData.geoBone = b;
		groups.set(b.name, grp);
	}
	for (const b of bones) {
		const grp = groups.get(b.name);
		const parent = b.parent && groups.get(b.parent);
		const parentPivot = parent ? parent.userData.geoBone.pivot : [0, 0, 0];
		grp.position.set(b.pivot[0] - parentPivot[0], b.pivot[1] - parentPivot[1], b.pivot[2] - parentPivot[2]);
		setEulerDeg(grp, b.rotation);
		(parent || root).add(grp);

		b.cubes.forEach((c, ci) => {
			const pivot = c.rotation ? c.pivot || b.pivot : b.pivot;
			const builder = new GeometryBuilder();
			const faces = {};
			for (const dir of FACES) {
				const f = c.faces[dir];
				if (!f) continue;
				const rect = Array.isArray(f) ? f : f.uv;
				const fw = (Array.isArray(f) ? null : f.tw) || tw;
				const fh = (Array.isArray(f) ? null : f.th) || th;
				faces[dir] = {
					uv: [rect[0] / fw, rect[1] / fh, rect[2] / fw, rect[3] / fh],
					material: Array.isArray(f) ? 0 : f.material ?? 0,
					rotation: Array.isArray(f) ? 0 : f.rotation || 0,
				};
			}
			builder.addBox(c.from.map((x, i) => x - pivot[i]), c.to.map((x, i) => x - pivot[i]), faces, { inflate: c.inflate });
			const mesh = new THREE.Mesh(builder.build(), materials);
			mesh.name = c.name || `${b.name}_cube${ci}`;
			if (c.rotation) {
				const holder = new THREE.Group();
				holder.name = mesh.name + '_pivot';
				holder.position.set(pivot[0] - b.pivot[0], pivot[1] - b.pivot[1], pivot[2] - b.pivot[2]);
				setEulerDeg(holder, c.rotation);
				holder.add(mesh);
				grp.add(holder);
			} else {
				grp.add(mesh);
			}
		});
	}
	root.userData = { cuboid: true, materials, bones };
	return root;
}

export function setEulerDeg(obj, deg = [0, 0, 0]) {
	const d = THREE.MathUtils.degToRad;
	obj.rotation.set(d(deg[0] || 0), d(deg[1] || 0), d(deg[2] || 0), 'ZYX');
}
