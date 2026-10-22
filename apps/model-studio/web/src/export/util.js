import { zipSync, strToU8 } from 'fflate';

export async function imageToPng(image) {
	if (!image) return null;
	const canvas = document.createElement('canvas');
	canvas.width = image.width;
	canvas.height = image.height;
	canvas.getContext('2d').drawImage(image, 0, 0);
	const blob = await new Promise((r) => canvas.toBlob(r, 'image/png'));
	return new Uint8Array(await blob.arrayBuffer());
}

export function zip(files) {
	const out = {};
	for (const [name, data] of Object.entries(files)) out[name] = typeof data === 'string' ? strToU8(data) : data;
	return new Blob([zipSync(out, { level: 6 })], { type: 'application/zip' });
}

export function json(obj) {
	return JSON.stringify(obj, null, '\t');
}

export function safeName(s) {
	return String(s || 'model').replace(/[^a-zA-Z0-9_\-. ]+/g, '_').trim() || 'model';
}

// Copy of the model where every multi-material mesh is split into one mesh per
// material (some exporters, like USDZ, only accept a single material per mesh).
export function splitMultiMaterial(root, THREE) {
	const copy = root.clone(true);
	const targets = [];
	copy.traverse((o) => o.isMesh && Array.isArray(o.material) && targets.push(o));
	for (const mesh of targets) {
		const geo = mesh.geometry;
		const holder = new THREE.Group();
		holder.name = mesh.name;
		holder.position.copy(mesh.position);
		holder.quaternion.copy(mesh.quaternion);
		holder.scale.copy(mesh.scale);
		const groups = geo.groups.length ? geo.groups : [{ start: 0, count: geo.index ? geo.index.count : geo.getAttribute('position').count, materialIndex: 0 }];
		groups.forEach((g, i) => {
			const part = new THREE.BufferGeometry();
			for (const [k, attr] of Object.entries(geo.attributes)) part.setAttribute(k, attr);
			if (geo.index) part.setIndex(new THREE.BufferAttribute(geo.index.array.slice(g.start, g.start + g.count), 1));
			else part.setDrawRange(g.start, g.count);
			const sub = new THREE.Mesh(part, mesh.material[g.materialIndex ?? 0]);
			sub.name = `${mesh.name}_${i}`;
			holder.add(sub);
		});
		for (const child of [...mesh.children]) holder.add(child);
		mesh.parent.add(holder);
		mesh.parent.remove(mesh);
	}
	return copy;
}
