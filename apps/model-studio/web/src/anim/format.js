// The .msanim animation format: a small, readable JSON format for bone
// animations that converts cleanly to Minecraft (Bedrock / GeckoLib),
// Blockbench and glTF. See apps/model-studio/docs/msanim.md for the spec.

export const MSANIM_VERSION = 1;
export const CHANNELS = ['rotation', 'position', 'scale'];
export const INTERPOLATIONS = ['linear', 'smooth', 'step'];
export const LOOP_MODES = ['loop', 'once', 'hold'];

export function newClip(name = 'animation', length = 2) {
	return { name, length, fps: 24, loop: 'loop', tracks: [] };
}

export function defaultValue(channel) {
	return channel === 'scale' ? [1, 1, 1] : [0, 0, 0];
}

export function getTrack(clip, bone, channel, create = false) {
	let t = clip.tracks.find((t) => t.bone === bone && t.channel === channel);
	if (!t && create) {
		t = { bone, channel, keys: [] };
		clip.tracks.push(t);
	}
	return t || null;
}

export function setKey(clip, bone, channel, time, value, interpolation = 'linear') {
	const track = getTrack(clip, bone, channel, true);
	time = round(time);
	const existing = track.keys.find((k) => Math.abs(k.t - time) < 1e-4);
	if (existing) {
		existing.v = value.map(round);
		existing.i = interpolation;
	} else {
		track.keys.push({ t: time, v: value.map(round), i: interpolation });
		track.keys.sort((a, b) => a.t - b.t);
	}
	if (time > clip.length) clip.length = time;
	return track;
}

export function removeKey(clip, bone, time) {
	for (const track of clip.tracks) if (track.bone === bone) track.keys = track.keys.filter((k) => Math.abs(k.t - time) > 1e-4);
	clip.tracks = clip.tracks.filter((t) => t.keys.length);
}

export function keyTimes(clip, bone) {
	const times = new Set();
	for (const t of clip.tracks) if (!bone || t.bone === bone) for (const k of t.keys) times.add(round(k.t));
	return [...times].sort((a, b) => a - b);
}

export function sampleTrack(track, time) {
	const keys = track.keys;
	if (!keys.length) return defaultValue(track.channel);
	if (time <= keys[0].t) return keys[0].v.slice();
	const last = keys[keys.length - 1];
	if (time >= last.t) return last.v.slice();
	let i = 0;
	while (i < keys.length - 2 && time >= keys[i + 1].t) i++;
	const a = keys[i], b = keys[i + 1];
	const span = b.t - a.t || 1;
	const f = (time - a.t) / span;
	const mode = a.i || track.interpolation || 'linear';
	if (mode === 'step') return a.v.slice();
	if (mode === 'smooth') {
		const p0 = keys[i - 1]?.v || a.v;
		const p3 = keys[i + 2]?.v || b.v;
		return a.v.map((_, c) => catmullRom(p0[c], a.v[c], b.v[c], p3[c], f));
	}
	return a.v.map((v, c) => v + (b.v[c] - v) * f);
}

function catmullRom(p0, p1, p2, p3, t) {
	const t2 = t * t, t3 = t2 * t;
	return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
}

export function round(x) {
	return Math.round(x * 10000) / 10000;
}

export function toMsanim(clips, modelName) {
	return {
		format: 'msanim',
		formatVersion: MSANIM_VERSION,
		generator: 'Model Studio',
		model: modelName || undefined,
		rotationOrder: 'ZYX',
		units: { time: 'seconds', rotation: 'degrees', position: 'model units', scale: 'multiplier' },
		clips: clips.map((c) => ({
			name: c.name,
			length: round(c.length),
			fps: c.fps || 24,
			loop: c.loop || 'loop',
			tracks: c.tracks
				.filter((t) => t.keys.length)
				.map((t) => ({ bone: t.bone, channel: t.channel, keys: t.keys.map((k) => ({ t: round(k.t), v: k.v.map(round), ...(k.i && k.i !== 'linear' ? { i: k.i } : {}) })) })),
		})),
	};
}

export function isMsanim(json) {
	return json?.format === 'msanim';
}

export function fromMsanim(json) {
	if (!isMsanim(json)) throw new Error('Not an .msanim file');
	if ((json.formatVersion || 1) > MSANIM_VERSION) throw new Error('This .msanim file was made by a newer version of the app.');
	return (json.clips || []).map((c) => ({
		name: String(c.name || 'animation'),
		length: Number(c.length) || 1,
		fps: Number(c.fps) || 24,
		loop: LOOP_MODES.includes(c.loop) ? c.loop : 'loop',
		tracks: (c.tracks || [])
			.filter((t) => t.bone && CHANNELS.includes(t.channel))
			.map((t) => ({
				bone: String(t.bone),
				channel: t.channel,
				keys: (t.keys || [])
					.map((k) => ({ t: Number(k.t) || 0, v: vec3(k.v, t.channel), i: INTERPOLATIONS.includes(k.i) ? k.i : 'linear' }))
					.sort((a, b) => a.t - b.t),
			})),
	}));
}

function vec3(v, channel) {
	if (typeof v === 'number') return [v, v, v];
	const d = defaultValue(channel);
	return [0, 1, 2].map((i) => (Number.isFinite(Number(v?.[i])) ? Number(v[i]) : d[i]));
}
