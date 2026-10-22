// Conversions between .msanim clips and other animation formats.
import * as THREE from 'three';
import { round, defaultValue } from './format.js';
import { molangValue } from './molang.js';
import { rotationDelta } from './player.js';

const BAKE_FPS = 20;

// ---------- Minecraft Bedrock / GeckoLib (.animation.json) ----------

export function isBedrockAnimation(json) {
	return !!json && typeof json.animations === 'object' && !Array.isArray(json.animations) && !json.meta;
}

// Blockbench space <-> Bedrock space
const toBedrock = { rotation: (v) => [-v[0], -v[1], v[2]], position: (v) => [-v[0], v[1], v[2]], scale: (v) => v };
const fromBedrock = toBedrock; // each mapping is its own inverse

export function bedrockToClips(json, warnings = []) {
	const clips = [];
	let usedMolang = false, failed = false;
	for (const [id, a] of Object.entries(json.animations || {})) {
		const clip = {
			name: id.replace(/^animation\./, '').replace(/^[^.]+\.(?=.)/, ''),
			length: Number(a.animation_length) || 0,
			fps: 24,
			loop: a.loop === true ? 'loop' : a.loop === 'hold_on_last_frame' ? 'hold' : 'once',
			tracks: [],
		};
		for (const [bone, chans] of Object.entries(a.bones || {})) {
			for (const channel of ['rotation', 'position', 'scale']) {
				if (chans[channel] === undefined) continue;
				const res = parseBedrockChannel(chans[channel], channel, clip);
				usedMolang ||= res.molang;
				failed ||= res.failed;
				if (res.keys.length) clip.tracks.push({ bone, channel, keys: res.keys });
			}
		}
		if (!clip.length) clip.length = Math.max(1, ...clip.tracks.flatMap((t) => t.keys.map((k) => k.t)));
		clips.push(clip);
	}
	if (usedMolang) warnings.push('Some Molang expressions were baked into keyframes so they can be previewed and converted.');
	if (failed) warnings.push('Some Molang expressions could not be understood and were treated as 0.');
	return clips;
}

function parseVec(v, channel) {
	if (v === undefined || v === null) return null;
	if (!Array.isArray(v)) v = channel === 'scale' ? [v, v, v] : [v, v, v];
	return v.map((x) => molangValue(x));
}

function evalVec(parts, t, channel) {
	const env = { 'query.anim_time': t, 'query.life_time': t, 'query.time_stamp': t * 20 };
	return fromBedrock[channel](parts.map((p) => (p.constant ? p.value : p.fn(env))));
}

function parseBedrockChannel(value, channel, clip) {
	const keys = [];
	let molang = false, failed = false;
	const note = (parts) => {
		for (const p of parts) {
			if (!p.constant) molang = true;
			if (p.failed) failed = true;
		}
		return parts;
	};
	const isTimeline = value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every((k) => !isNaN(parseFloat(k)));
	if (!isTimeline) {
		const parts = note(parseVec(value, channel) || []);
		if (parts.some((p) => !p.constant)) {
			const len = clip.length || 1;
			for (let f = 0; f <= Math.ceil(len * BAKE_FPS); f++) {
				const t = Math.min(len, f / BAKE_FPS);
				keys.push({ t: round(t), v: evalVec(parts, t, channel).map(round), i: 'linear' });
			}
		} else if (parts.length) keys.push({ t: 0, v: evalVec(parts, 0, channel).map(round), i: 'linear' });
		return { keys, molang, failed };
	}
	const entries = Object.entries(value).map(([t, v]) => [parseFloat(t), v]).sort((a, b) => a[0] - b[0]);
	for (const [t, v] of entries) {
		const obj = v && typeof v === 'object' && !Array.isArray(v);
		const post = note(parseVec(obj ? v.post ?? v.pre : v, channel) || []);
		const pre = obj && v.pre !== undefined ? note(parseVec(v.pre, channel)) : null;
		const interp = obj && v.lerp_mode === 'catmullrom' ? 'smooth' : 'linear';
		if (pre && post.length && keys.length) {
			const preV = evalVec(pre, t, channel).map(round);
			const postV = evalVec(post, t, channel).map(round);
			if (preV.some((x, i) => x !== postV[i])) keys.push({ t: round(Math.max(0, t - 0.0001)), v: preV, i: 'linear' });
		}
		if (post.length) keys.push({ t: round(t), v: evalVec(post, t, channel).map(round), i: interp });
	}
	return { keys, molang, failed };
}

const fmtTime = (t) => {
	const s = String(round(t));
	return s.includes('.') ? s : s + '.0';
};

export function clipsToBedrock(clips, modelName = 'model') {
	const model = slug(modelName);
	const animations = {};
	for (const clip of clips) {
		const bones = {};
		for (const track of clip.tracks) {
			if (!track.keys.length) continue;
			const conv = toBedrock[track.channel];
			const out = {};
			track.keys.forEach((k, i) => {
				const v = conv(k.v).map(round);
				const prev = track.keys[i - 1];
				if (prev && (prev.i || 'linear') === 'step') out[fmtTime(k.t)] = { pre: conv(prev.v).map(round), post: v };
				else if ((k.i || 'linear') === 'smooth') out[fmtTime(k.t)] = { post: v, lerp_mode: 'catmullrom' };
				else out[fmtTime(k.t)] = v;
			});
			(bones[track.bone] ||= {})[track.channel] = out;
		}
		const a = { animation_length: round(clip.length), bones };
		if (clip.loop === 'loop') a.loop = true;
		else if (clip.loop === 'hold') a.loop = 'hold_on_last_frame';
		animations[`animation.${model}.${slug(clip.name)}`] = a;
	}
	return { format_version: '1.8.0', animations };
}

export function slug(s) {
	return String(s || 'model').toLowerCase().replace(/^animation\.[^.]+\./, '').replace(/[^a-z0-9_.]+/g, '_').replace(/^_+|_+$/g, '') || 'model';
}

// ---------- Blockbench (.bbmodel) ----------

export function bbAnimationsToClips(anims, boneByUuid, warnings = []) {
	let molang = false;
	const clips = anims.map((a) => {
		const clip = {
			name: String(a.name || 'animation').replace(/^animation\.[^.]+\./, ''),
			length: Number(a.length) || 0,
			fps: Number(a.snapping) || 24,
			loop: a.loop === 'loop' ? 'loop' : a.loop === 'hold' ? 'hold' : 'once',
			tracks: [],
		};
		for (const [uuid, an] of Object.entries(a.animators || {})) {
			if (an.type && an.type !== 'bone') continue;
			const bone = boneByUuid.get(uuid) || an.name;
			const byChannel = {};
			for (const kf of an.keyframes || []) {
				if (!['rotation', 'position', 'scale'].includes(kf.channel)) continue;
				const pts = (kf.data_points || []).map((d) => ['x', 'y', 'z'].map((ax) => molangValue(d[ax] ?? defaultValue(kf.channel)[0])));
				if (!pts.length) continue;
				const env = { 'query.anim_time': kf.time, 'query.life_time': kf.time };
				const val = (p) => p.map((x) => {
					if (!x.constant) molang = true;
					return round(x.constant ? x.value : x.fn(env));
				});
				const i = kf.interpolation === 'step' ? 'step' : kf.interpolation === 'catmullrom' || kf.interpolation === 'bezier' ? 'smooth' : 'linear';
				const keys = (byChannel[kf.channel] ||= []);
				if (pts.length > 1) keys.push({ t: round(Math.max(0, kf.time - 0.0001)), v: val(pts[0]), i: 'linear', pre: true });
				keys.push({ t: round(kf.time), v: val(pts[pts.length - 1]), i });
			}
			for (const [channel, keys] of Object.entries(byChannel)) {
				keys.sort((x, y) => x.t - y.t);
				clip.tracks.push({ bone, channel, keys: keys.map(({ t, v, i }) => ({ t, v, i })) });
			}
		}
		if (!clip.length) clip.length = Math.max(1, ...clip.tracks.flatMap((t) => t.keys.map((k) => k.t)));
		return clip;
	});
	if (molang) warnings.push('Some Molang expressions in the animations were evaluated once per keyframe.');
	return clips;
}

// ---------- three.js AnimationClip (glTF, FBX, ...) ----------

export function clipsFromThree(threeClips, root, player, warnings = []) {
	const out = [];
	let skipped = 0;
	for (const clip of threeClips) {
		const c = { name: clip.name || `animation_${out.length + 1}`, length: round(clip.duration) || 1, fps: 24, loop: 'loop', tracks: [] };
		for (const track of clip.tracks) {
			const parsed = THREE.PropertyBinding.parseTrackName(track.name);
			const node = THREE.PropertyBinding.findNode(root, parsed.nodeName);
			const prop = parsed.propertyName;
			if (!node || !['quaternion', 'position', 'scale'].includes(prop) || !node.name) {
				skipped++;
				continue;
			}
			const rest = player.rest.get(node);
			if (!rest) continue;
			const size = prop === 'quaternion' ? 4 : 3;
			const n = track.times.length;
			const cubic = track.values.length === n * size * 3;
			const interp = track.getInterpolation?.() === THREE.InterpolateDiscrete ? 'step' : cubic || track.getInterpolation?.() === THREE.InterpolateSmooth ? 'smooth' : 'linear';
			const keys = [];
			const q = new THREE.Quaternion();
			let prev = null;
			for (let k = 0; k < n; k++) {
				const off = (cubic ? k * 3 + 1 : k) * size;
				const vals = Array.from(track.values.slice(off, off + size));
				let v;
				if (prop === 'quaternion') {
					v = rotationDelta(rest, q.fromArray(vals).normalize(), false);
					if (prev) v = v.map((x, i) => unwrap(x, prev[i]));
				} else if (prop === 'position') v = [vals[0] - rest.p.x, vals[1] - rest.p.y, vals[2] - rest.p.z];
				else v = [vals[0] / (rest.s.x || 1), vals[1] / (rest.s.y || 1), vals[2] / (rest.s.z || 1)];
				prev = v;
				keys.push({ t: round(track.times[k]), v: v.map(round), i: interp });
			}
			c.tracks.push({ bone: node.name, channel: prop === 'quaternion' ? 'rotation' : prop, keys });
		}
		out.push(c);
	}
	if (skipped) warnings.push(`${skipped} animation track(s) (such as shape keys) are not supported and were skipped.`);
	return out;
}

function unwrap(a, ref) {
	while (a - ref > 180) a -= 360;
	while (a - ref < -180) a += 360;
	return a;
}

// Bake clips into absolute three.js tracks (for glTF export).
export function clipsToThree(clips, player) {
	return clips.map((clip) => {
		const fps = Math.max(1, Math.min(60, clip.fps || 24));
		const frames = Math.max(1, Math.round(clip.length * fps));
		const objs = [...new Set(clip.tracks.map((t) => player.find(t.bone)).filter(Boolean))];
		const times = [], data = new Map(objs.map((o) => [o, { p: [], q: [], s: [] }]));
		for (let f = 0; f <= frames; f++) {
			const t = Math.min(clip.length, f / fps);
			times.push(t);
			player.pose(clip, t);
			for (const o of objs) {
				const d = data.get(o);
				d.p.push(...o.position.toArray());
				d.q.push(...o.quaternion.toArray());
				d.s.push(...o.scale.toArray());
			}
		}
		player.resetPose();
		const tracks = [];
		for (const o of objs) {
			const d = data.get(o);
			const channels = new Set(clip.tracks.filter((t) => player.find(t.bone) === o).map((t) => t.channel));
			if (channels.has('position')) tracks.push(new THREE.VectorKeyframeTrack(`${o.uuid}.position`, times, d.p));
			if (channels.has('rotation')) tracks.push(new THREE.QuaternionKeyframeTrack(`${o.uuid}.quaternion`, times, d.q));
			if (channels.has('scale')) tracks.push(new THREE.VectorKeyframeTrack(`${o.uuid}.scale`, times, d.s));
		}
		return new THREE.AnimationClip(clip.name, clip.length, tracks);
	});
}
