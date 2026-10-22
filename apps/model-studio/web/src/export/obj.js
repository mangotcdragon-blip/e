// OBJ + MTL export that keeps materials and textures (three's OBJExporter writes geometry only).
import * as THREE from 'three';
import { imageToPng, safeName } from './util.js';

export async function exportOBJ(root, name) {
	const lines = [`# Exported by Model Studio`, `mtllib ${name}.mtl`];
	const mtl = [`# Exported by Model Studio`];
	const files = {};
	const matNames = new Map();
	const texNames = new Map();
	let vOff = 1, tOff = 1, nOff = 1;
	const v = new THREE.Vector3(), nrm = new THREE.Vector3(), nm = new THREE.Matrix3();

	const materialName = async (m) => {
		if (matNames.has(m)) return matNames.get(m);
		let n = safeName(m.name || 'material').replace(/\s+/g, '_');
		while ([...matNames.values()].includes(n)) n += '_';
		matNames.set(m, n);
		const c = m.color || new THREE.Color(1, 1, 1);
		mtl.push('', `newmtl ${n}`, `Kd ${c.r.toFixed(4)} ${c.g.toFixed(4)} ${c.b.toFixed(4)}`, `Ka 0 0 0`, `d ${m.opacity ?? 1}`);
		if (m.emissive && m.emissiveIntensity > 0) {
			const e = m.emissive.clone().multiplyScalar(Math.min(m.emissiveIntensity, 1));
			mtl.push(`Ke ${e.r.toFixed(4)} ${e.g.toFixed(4)} ${e.b.toFixed(4)}`);
		}
		const tex = m.userData.origMap ?? m.map;
		if (tex?.image) {
			if (!texNames.has(tex.image)) {
				const tn = `${safeName(tex.name ? tex.name.split(/[/:]/).pop().replace(/\.png$/i, '') : n)}.png`;
				texNames.set(tex.image, tn);
				files[tn] = await imageToPng(tex.image);
			}
			mtl.push(`map_Kd ${texNames.get(tex.image)}`);
		}
		return n;
	};

	root.updateMatrixWorld(true);
	const meshes = [];
	root.traverse((o) => o.isMesh && o.visible && meshes.push(o));
	for (const mesh of meshes) {
		const geo = mesh.geometry;
		const pos = geo.getAttribute('position');
		const uv = geo.getAttribute('uv');
		const normal = geo.getAttribute('normal');
		if (!pos) continue;
		const tex = (Array.isArray(mesh.material) ? mesh.material[0] : mesh.material)?.map;
		lines.push(`o ${safeName(mesh.name).replace(/\s+/g, '_')}`);
		nm.getNormalMatrix(mesh.matrixWorld);
		for (let i = 0; i < pos.count; i++) {
			v.fromBufferAttribute(pos, i).applyMatrix4(mesh.matrixWorld);
			lines.push(`v ${v.x.toFixed(6)} ${v.y.toFixed(6)} ${v.z.toFixed(6)}`);
		}
		if (uv)
			for (let i = 0; i < uv.count; i++) {
				let x = uv.getX(i), y = uv.getY(i);
				if (tex && tex.flipY === false) y = 1 - y;
				if (tex?.repeat && tex.userData?.frames > 1) y = y * tex.repeat.y + (1 - tex.repeat.y);
				lines.push(`vt ${x.toFixed(6)} ${y.toFixed(6)}`);
			}
		if (normal)
			for (let i = 0; i < normal.count; i++) {
				nrm.fromBufferAttribute(normal, i).applyMatrix3(nm).normalize();
				lines.push(`vn ${nrm.x.toFixed(4)} ${nrm.y.toFixed(4)} ${nrm.z.toFixed(4)}`);
			}
		const index = geo.getIndex();
		const groups = geo.groups.length ? geo.groups : [{ start: 0, count: index ? index.count : pos.count, materialIndex: 0 }];
		const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
		for (const g of groups) {
			const m = mats[g.materialIndex ?? 0];
			if (m) lines.push(`usemtl ${await materialName(m)}`);
			for (let i = g.start; i < g.start + g.count; i += 3) {
				const f = [0, 1, 2].map((k) => {
					const idx = index ? index.getX(i + k) : i + k;
					const a = idx + vOff, b = uv ? idx + tOff : '', c = normal ? idx + nOff : '';
					return normal ? `${a}/${b}/${c}` : uv ? `${a}/${b}` : `${a}`;
				});
				lines.push(`f ${f.join(' ')}`);
			}
		}
		vOff += pos.count;
		if (uv) tOff += uv.count;
		if (normal) nOff += normal.count;
	}
	files[`${name}.obj`] = lines.join('\n') + '\n';
	files[`${name}.mtl`] = mtl.join('\n') + '\n';
	return files;
}
