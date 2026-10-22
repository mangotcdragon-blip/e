// Applies .msanim clips to a loaded model. Values are offsets from each bone's
// rest pose: rotation in degrees (ZYX order), position in model units, scale as a multiplier.
import * as THREE from 'three';
import { sampleTrack } from './format.js';

const D2R = THREE.MathUtils.degToRad, R2D = THREE.MathUtils.radToDeg;
const tmpE = new THREE.Euler(), tmpQ = new THREE.Quaternion();

export class AnimPlayer {
	constructor() {
		this.root = null;
		this.rest = new Map();
		this.touched = new Set();
	}

	setModel(root) {
		this.root = root;
		this.rest.clear();
		this.touched.clear();
		this.byName = new Map();
		root?.traverse((o) => {
			const e = new THREE.Euler().setFromQuaternion(o.quaternion, 'ZYX');
			this.rest.set(o, {
				p: o.position.clone(),
				q: o.quaternion.clone(),
				s: o.scale.clone(),
				e: [R2D(e.x), R2D(e.y), R2D(e.z)],
			});
			if (o !== root && o.name && !this.byName.has(o.name)) this.byName.set(o.name, o);
		});
	}

	find(name) {
		return this.byName?.get(name) || null;
	}

	// Objects that make sense to animate: named groups/bones first, then meshes.
	boneNames() {
		if (!this.root) return [];
		const bones = [], meshes = [];
		const mc = [...this.byName.values()].some((o) => o.userData.mcBone);
		for (const [name, o] of this.byName) {
			if (mc && !o.userData.mcBone) continue;
			if (o.isMesh && !o.isSkinnedMesh) meshes.push(name);
			else if (!o.isLight && !o.isCamera) bones.push(name);
		}
		return bones.length ? [...bones, ...meshes] : meshes;
	}

	resetPose() {
		for (const o of this.touched) {
			const r = this.rest.get(o);
			if (!r) continue;
			o.position.copy(r.p);
			o.quaternion.copy(r.q);
			o.scale.copy(r.s);
		}
		this.touched.clear();
	}

	pose(clip, time, overrides = null) {
		this.resetPose();
		if (!clip) return;
		for (const track of clip.tracks) {
			const obj = this.find(track.bone);
			if (!obj || !track.keys.length) continue;
			this.apply(obj, track.channel, sampleTrack(track, time));
		}
		if (overrides) for (const { bone, channel, value } of overrides) {
			const obj = this.find(bone);
			if (obj) this.apply(obj, channel, value);
		}
	}

	apply(obj, channel, v) {
		const r = this.rest.get(obj);
		if (!r) return;
		this.touched.add(obj);
		if (channel === 'position') obj.position.set(r.p.x + v[0], r.p.y + v[1], r.p.z + v[2]);
		else if (channel === 'scale') obj.scale.set(r.s.x * v[0], r.s.y * v[1], r.s.z * v[2]);
		else if (obj.userData.mcBone) {
			obj.rotation.set(D2R(r.e[0] + v[0]), D2R(r.e[1] + v[1]), D2R(r.e[2] + v[2]), 'ZYX');
		} else {
			tmpQ.setFromEuler(tmpE.set(D2R(v[0]), D2R(v[1]), D2R(v[2]), 'ZYX'));
			obj.quaternion.copy(r.q).multiply(tmpQ);
		}
	}

	// Inverse of apply(): the offset an object's current transform represents.
	delta(obj, channel) {
		const r = this.rest.get(obj);
		if (!r) return channel === 'scale' ? [1, 1, 1] : [0, 0, 0];
		if (channel === 'position') return [obj.position.x - r.p.x, obj.position.y - r.p.y, obj.position.z - r.p.z];
		if (channel === 'scale') return [obj.scale.x / (r.s.x || 1), obj.scale.y / (r.s.y || 1), obj.scale.z / (r.s.z || 1)];
		return rotationDelta(r, obj.quaternion, obj.userData.mcBone);
	}
}

export function rotationDelta(rest, quat, mcBone) {
	if (mcBone) {
		tmpE.setFromQuaternion(quat, 'ZYX');
		return [R2D(tmpE.x) - rest.e[0], R2D(tmpE.y) - rest.e[1], R2D(tmpE.z) - rest.e[2]];
	}
	tmpQ.copy(rest.q).invert().multiply(quat);
	tmpE.setFromQuaternion(tmpQ, 'ZYX');
	return [R2D(tmpE.x), R2D(tmpE.y), R2D(tmpE.z)];
}
