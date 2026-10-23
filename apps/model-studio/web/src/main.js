import * as THREE from 'three';
import { Viewer, DEFAULT_SETTINGS } from './viewer.js';
import { FileSet, loadImage } from './fileset.js';
import { classify, loadModel, loadAnimations, collectMaterials } from './loaders/index.js';
import { AnimPlayer } from './anim/player.js';
import { clipsFromThree } from './anim/convert.js';
import { newClip, setKey, removeKey, keyTimes, getTrack, defaultValue, LOOP_MODES } from './anim/format.js';
import { runExport, MODEL_FORMATS, ANIM_FORMATS, modelFormatAvailability } from './export/index.js';
import { saveFile, isAndroid, fetchIncoming, shareLastSaved } from './bridge.js';
import { sampleRobot, sampleClips } from './sample.js';
import { snapshot, resetMaterial, glowStrength, glowColor, setGlow, setOpacity, textureFromImage, GLOW_PRESETS, COLOR_SWATCHES } from './materials.js';
import { h, section, hint, button, slider, toggle, chips, colorRow, select, numberRow, round, prompt, confirm, ICONS } from './ui/dom.js';

// ---------------------------------------------------------------- state

const SETTINGS_KEY = 'model-studio-settings';
const storedSettings = (() => {
	try {
		return JSON.parse(localStorage.getItem(SETTINGS_KEY)) || {};
	} catch {
		return {};
	}
})();

const state = {
	files: null,
	clips: [],
	clipIndex: 0,
	time: 0,
	playing: false,
	speed: 1,
	material: null,
	bone: null,
	panel: null,
	convertMode: false,
	interpolation: 'smooth',
	gizmoMode: 'translate',
	voxelRes: 16,
	javaVoxel: false,
	lastSaved: null,
};

const $ = (sel) => document.querySelector(sel);
const viewer = new Viewer($('#viewport'), storedSettings);
const player = new AnimPlayer();
viewer.on('settings', (s) => {
	try {
		const { studioLight, azimuth, elevation, lightIntensity, lightColor, fill, shadows, reflections, background, grid, autoRotate, faceShade, bloomStrength, bloomRadius } = s;
		localStorage.setItem(SETTINGS_KEY, JSON.stringify({ studioLight, azimuth, elevation, lightIntensity, lightColor, fill, shadows, reflections, background, grid, autoRotate, faceShade, bloomStrength, bloomRadius }));
	} catch {
		/* storage unavailable */
	}
});

const clip = () => state.clips[state.clipIndex] || null;

// ---------------------------------------------------------------- feedback

let toastTimer;
function toast(message, kind = '') {
	const el = $('#toast');
	el.textContent = message;
	el.className = 'show ' + kind;
	clearTimeout(toastTimer);
	toastTimer = setTimeout(() => (el.className = ''), kind === 'error' ? 6000 : 3200);
}

function busy(message) {
	const el = $('#busy');
	el.querySelector('span').textContent = message || '';
	el.classList.toggle('show', !!message);
}

// ---------------------------------------------------------------- opening files

const fileInput = $('#file-input');
let pickPurpose = 'open';
function pickFiles(purpose = 'open') {
	pickPurpose = purpose;
	// On Android the app's own picker passes real file names (texture matching depends on them)
	if (window.AndroidBridge?.pickFiles) return window.AndroidBridge.pickFiles(purpose);
	fileInput.value = '';
	fileInput.click();
}
fileInput.addEventListener('change', () => {
	const files = [...fileInput.files];
	if (files.length) openFiles(files, pickPurpose);
});

async function openFiles(fileList, purpose = 'open') {
	busy('Opening…');
	try {
		const files = await FileSet.fromFiles(fileList);
		const { modelEntry, animEntries, images } = await classify(files);
		const warnings = [];

		if (purpose === 'texture' || purpose === 'replace' || (!modelEntry && !animEntries.length && images.length)) {
			if (!viewer.model) throw new Error('Open a model first, then add a texture to one of its materials.');
			if (!images.length) throw new Error('Pick a picture file (PNG, JPG or WebP) to use as the texture.');
			const applied = await applyTextures(images, purpose === 'replace' ? state.material : null);
			toast(applied.join(' · '));
			showPanel('material');
			return;
		}

		if (modelEntry) {
			const { root, threeClips } = await loadModel(modelEntry, files);
			state.files?.dispose();
			state.files = files;
			setModel(root);
			warnings.push(...root.userData.warnings);
			const clips = [...(root.userData.clips || [])];
			if (threeClips.length) clips.push(...clipsFromThree(threeClips, root, player, warnings));
			clips.push(...(await loadAnimations(animEntries, warnings)));
			setClips(clips);
		} else if (animEntries.length) {
			if (!viewer.model) throw new Error('Open a model first, then add its animation file.');
			const clips = await loadAnimations(animEntries, warnings);
			state.clips.push(...clips);
			state.clipIndex = state.clips.length - clips.length;
			const missing = new Set(clips.flatMap((c) => c.tracks.map((t) => t.bone)).filter((b) => !player.find(b)));
			if (missing.size) warnings.push(`These bones are not in the current model: ${[...missing].slice(0, 8).join(', ')}${missing.size > 8 ? '…' : ''}`);
			toast(`Added ${clips.length} animation${clips.length === 1 ? '' : 's'}`);
			setTime(0);
			showPanel('animate');
		} else {
			throw new Error(`Couldn't find a model or animation in ${fileList.length === 1 ? fileList[0].name : 'those files'}.`);
		}
		state.warnings = warnings;
		if (warnings.length) toast(warnings[0], 'warn');
		if (state.convertMode && modelEntry) {
			state.convertMode = false;
			showPanel('save', { banner: 'Now pick the format to convert to.' });
		} else if (modelEntry) {
			if (state.panel) showPanel(state.panel);
			if (!warnings.length) toast(`Opened ${modelEntry.name}`);
		}
	} catch (err) {
		console.error(err);
		toast(err.message || String(err), 'error');
	} finally {
		busy(null);
	}
}

// Put picked pictures onto the model: by matching name to a texture the model
// is missing (or a material's name), otherwise onto the selected material.
async function applyTextures(images, only = null) {
	const mats = viewer.materials();
	const key = (n) => String(n || '').toLowerCase().split(/[/:]/).pop().replace(/\.(png|jpe?g|webp|tga)$/, '');
	const missing = mats.filter((m) => !(m.userData.origMap ?? m.map) || (m.userData.origMap ?? m.map)?.userData?.missing);
	const done = [];
	for (const entry of images) {
		const want = key(entry.name);
		let targets = only ? [only] : mats.filter((m) => key(m.userData.mcTexture) === want || key((m.userData.origMap ?? m.map)?.name) === want || key(m.name) === want);
		if (!targets.length && images.length === 1) targets = state.material ? [state.material] : missing.length ? missing : mats.slice(0, 1);
		if (!targets.length && missing.length) targets = [missing.shift()];
		if (!targets.length) continue;
		const img = await loadImage(entry.blob);
		for (const mat of targets) {
			const old = mat.userData.origMap ?? mat.map;
			const tex = textureFromImage(img, entry.name, Math.max(img.width, img.height) <= 256);
			if (old && !old.userData?.missing) {
				tex.flipY = old.flipY;
				tex.repeat.copy(old.repeat);
				tex.offset.copy(old.offset);
				tex.userData = { ...old.userData };
			}
			snapshot(mat);
			viewer.setMaterialMap(mat, tex);
			mat.color.set(0xffffff);
		}
		done.push(`${entry.name} \u2192 ${targets.map((m) => m.name).join(', ')}`);
	}
	if (!done.length) throw new Error('Could not tell which part those pictures belong to. Select a material in the Look tab first.');
	viewer.syncGlowAll();
	return done;
}

function setModel(root) {
	state.playing = false;
	viewer.setModel(root);
	player.setModel(root);
	state.material = null;
	state.bone = null;
	state.part = null;
	root?.traverse((o) => (o.userData.orig = { p: o.position.clone(), q: o.quaternion.clone(), s: o.scale.clone() }));
	$('#empty').classList.toggle('hidden', !!root);
	$('#title').textContent = root ? root.userData.fileName || root.name : 'Model Studio';
	for (const m of collectMaterials(root)) snapshot(m);
}

function setClips(clips) {
	state.clips = clips;
	state.clipIndex = 0;
	state.time = 0;
	state.playing = false;
	player.pose(clip(), 0);
	updateHud();
}

function loadSample() {
	const root = sampleRobot();
	root.userData.fileName = 'Sample robot';
	setModel(root);
	setClips(sampleClips());
	state.playing = true;
	state.warnings = [];
	updateHud();
	toast('Sample robot loaded. Try the Animate and Look tabs!');
	if (state.panel) showPanel(state.panel);
}

// ---------------------------------------------------------------- animation playback

function setTime(t) {
	const c = clip();
	state.time = Math.max(0, Math.min(t, c ? c.length : 0));
	player.pose(c, state.time);
	viewer.invalidate();
	syncAnimUI();
}

viewer.addFrameHook((dt) => {
	const c = clip();
	if (!state.playing || !c) return false;
	let t = state.time + dt * state.speed;
	if (t > c.length) {
		if (c.loop === 'loop') t = c.length > 0 ? t % c.length : 0;
		else {
			t = c.length;
			state.playing = false;
			updateHud();
		}
	}
	state.time = t;
	player.pose(c, t);
	syncAnimUI();
	return true;
});

function togglePlay() {
	const c = clip();
	if (!c) return;
	if (!state.playing && state.time >= c.length) state.time = 0;
	state.playing = !state.playing;
	updateHud();
	if (state.panel === 'animate') renderAnimTransport();
}

// The floating mini player over the 3D view
function updateHud() {
	const hud = $('#hud');
	const c = clip();
	hud.classList.toggle('hidden', !c || state.panel === 'animate');
	if (!c) return;
	hud.replaceChildren(
		h('button', { class: 'icon-btn', onClick: togglePlay, 'aria-label': state.playing ? 'Pause' : 'Play', html: state.playing ? ICONS.pause : ICONS.play }),
		h('button', { class: 'hud-name', onClick: () => showPanel('animate') }, c.name),
	);
}

// ---------------------------------------------------------------- panels

const PANELS = {
	open: { title: 'Open', icon: ICONS.open, render: renderOpenPanel },
	parts: { title: 'Parts', icon: ICONS.parts, render: renderPartsPanel },
	material: { title: 'Look', icon: ICONS.material, render: renderMaterialPanel },
	light: { title: 'Light', icon: ICONS.light, render: renderLightPanel },
	animate: { title: 'Animate', icon: ICONS.animate, render: renderAnimatePanel },
	save: { title: 'Save', icon: ICONS.save, render: renderSavePanel },
	view: { title: 'View', icon: ICONS.view, render: renderViewPanel },
};

function buildTabs() {
	const bar = $('#tabs');
	for (const [id, p] of Object.entries(PANELS)) {
		bar.appendChild(h('button', { class: 'tab', 'data-id': id, onClick: () => (state.panel === id ? hidePanel() : showPanel(id)) }, h('span', { class: 'icon', html: p.icon }), h('span', {}, p.title)));
	}
}

let panelOptions = {};
function showPanel(id, options = {}) {
	if (id !== state.panel && (id === 'parts' || id === 'animate')) stopForEditing();
	if (id !== 'parts' && id !== 'animate') {
		viewer.select(null);
		viewer.detachGizmo();
	}
	state.panel = id;
	panelOptions = options;
	const sheet = $('#sheet');
	const opening = !sheet.classList.contains('open');
	sheet.classList.add('open');
	document.body.classList.add('sheet-open');
	$('#sheet-title').textContent = PANELS[id].title;
	document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.id === id));
	const body = $('#sheet-body');
	const scroll = body.scrollTop;
	body.replaceChildren(...[PANELS[id].render(options)].flat().filter(Boolean));
	if (options.keepScroll) body.scrollTop = scroll;
	viewer.showSunMarker = id === 'light';
	viewer.applySettings();
	updateHud();
	if (opening) requestAnimationFrame(() => {
		viewer.resize();
		// Keep the model filling the (now smaller or bigger) view unless you've moved the camera
		if (!viewer.cameraMoved) viewer.frame();
	});
}

function hidePanel() {
	state.panel = null;
	viewer.select(null);
	viewer.detachGizmo();
	$('#sheet').classList.remove('open');
	document.body.classList.remove('sheet-open');
	document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
	viewer.showSunMarker = false;
	viewer.applySettings();
	updateHud();
	requestAnimationFrame(() => {
		viewer.resize();
		// Keep the model filling the (now smaller or bigger) view unless you've moved the camera
		if (!viewer.cameraMoved) viewer.frame();
	});
}

const refresh = () => state.panel && showPanel(state.panel, { ...panelOptions, keepScroll: true });

// ---- Open

function renderOpenPanel() {
	const root = viewer.model;
	const out = [
		h('div', { class: 'big-buttons' },
			button('Open 3D file', () => pickFiles('open'), { kind: 'primary big', icon: ICONS.open }),
			button('Convert a file', () => {
				state.convertMode = true;
				pickFiles('open');
			}, { kind: 'big', icon: ICONS.save }),
			button('Add animation', () => pickFiles('anim'), { kind: 'big', icon: ICONS.animate, disabled: !root }),
			button('Add textures', () => pickFiles('texture'), { kind: 'big', icon: ICONS.material, disabled: !root }),
			button('Try sample', loadSample, { kind: 'big', icon: ICONS.cube }),
		),
		hint('Tip: select the model together with its textures (or a whole resource-pack .zip) so everything is found.'),
	];
	if (root) {
		let meshes = 0, tris = 0, verts = 0;
		root.traverse((o) => {
			if (!o.isMesh) return;
			meshes++;
			const g = o.geometry;
			verts += g.getAttribute('position')?.count || 0;
			tris += (g.index ? g.index.count : g.getAttribute('position')?.count || 0) / 3;
		});
		out.push(
			section('This model',
				h('dl', { class: 'facts' },
					fact('File', root.userData.fileName || root.name),
					fact('Type', describeFormat(root)),
					fact('Parts', meshes),
					fact('Triangles', Math.round(tris).toLocaleString()),
					fact('Points', verts.toLocaleString()),
					fact('Materials', viewer.materials().length),
					fact('Animations', state.clips.length),
				),
			),
		);
		if (state.warnings?.length) out.push(section('Notes', h('ul', { class: 'notes' }, state.warnings.map((w) => h('li', {}, w)))));
	}
	out.push(
		section('What can I open?',
			h('dl', { class: 'facts formats' },
				fact('3D models', 'GLB, glTF, OBJ (+MTL), FBX, STL, PLY, DAE, 3MF, 3DS, VOX'),
				fact('Minecraft', 'Java block & item .json, Bedrock .geo.json, Blockbench .bbmodel, resource-pack .zip'),
				fact('Animations', '.msanim, Minecraft .animation.json, and animations inside GLB/FBX/.bbmodel'),
				fact('Textures', 'PNG, JPG, WebP'),
			),
		),
	);
	return out;
}

function fact(k, v) {
	return [h('dt', {}, k), h('dd', {}, String(v))];
}

function describeFormat(root) {
	return { java: 'Minecraft Java model', bedrock: 'Minecraft Bedrock model', bbmodel: 'Blockbench project', sample: 'Sample' }[root.userData.source] || (root.userData.format || '').toUpperCase();
}

function needModel() {
	return h('div', { class: 'empty-panel' }, hint('Open a model first.'), button('Open 3D file', () => pickFiles('open'), { kind: 'primary', icon: ICONS.open }), button('Try sample', loadSample, { icon: ICONS.cube }));
}

// ---- Move / Rotate / Scale handles

const GIZMO_CHANNEL = { translate: 'position', rotate: 'rotation', scale: 'scale' };

function gizmoChips(obj) {
	viewer.attachGizmo(obj, state.gizmoMode);
	return h('div', { class: 'gizmo-bar' },
		chips({
			options: [
				{ value: 'translate', label: 'Move' },
				{ value: 'rotate', label: 'Rotate' },
				{ value: 'scale', label: 'Scale' },
			],
			value: state.gizmoMode,
			onChange: (v) => {
				state.gizmoMode = v;
				viewer.attachGizmo(obj, v);
			},
		}),
		state.gizmoMode !== 'scale' ? toggle({ label: 'Use the part\u2019s own axes', value: viewer.gizmoSpace === 'local', onChange: (v) => ((viewer.gizmoSpace = v ? 'local' : 'world'), viewer.attachGizmo(obj, state.gizmoMode)) }) : null,
		hint('Drag the coloured handles on the model. Arrows and squares move, rings rotate, cubes scale (the middle one scales evenly).'),
	);
}

viewer.on('gizmoChange', (obj, mode) => {
	if (state.panel === 'parts') {
		player.setRest(obj);
	} else if (state.panel === 'animate') {
		const c = clip();
		if (!c || !state.bone) return;
		const t = snapToFrame(state.time);
		state.time = t;
		setKey(c, state.bone, GIZMO_CHANNEL[mode], t, player.delta(obj, GIZMO_CHANNEL[mode]), state.interpolation);
	}
});

viewer.on('gizmoEnd', () => {
	if (state.panel === 'parts') refresh();
	else if (state.panel === 'animate') {
		player.pose(clip(), state.time);
		renderAnimatePanelBody();
	}
});

// ---- Parts

// Editing a part's pose while an animation has moved it would bake the animation in.
function stopForEditing() {
	if (state.panel === 'parts' || !viewer.model) return;
	state.playing = false;
	player.resetPose();
	viewer.invalidate();
	updateHud();
}

// Bone models select whole bones; everything else selects the tapped piece.
function defaultPartFor(obj) {
	for (let o = obj; o && o !== viewer.model; o = o.parent) if (o.userData.mcBone) return o;
	return obj;
}

function partList() {
	const out = [];
	const walk = (o, depth) => {
		for (const c of o.children) {
			if (!c.isObject3D || c.isLight || c.isCamera || (c.isBone && !c.name)) continue;
			if (!c.name.endsWith('_pivot')) out.push({ obj: c, depth });
			walk(c, c.name.endsWith('_pivot') ? depth : depth + 1);
		}
	};
	walk(viewer.model, 0);
	return out;
}

function partMeshes(obj) {
	const meshes = [];
	obj.traverse((o) => o.isMesh && meshes.push(o));
	return meshes;
}

function usedMaterials(mesh) {
	const list = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
	if (!Array.isArray(mesh.material)) return list;
	const idx = new Set(mesh.geometry.groups.length ? mesh.geometry.groups.map((g) => g.materialIndex ?? 0) : [0]);
	return [...idx].map((i) => list[i]).filter(Boolean);
}

function partMaterials(obj) {
	return [...new Set(partMeshes(obj).flatMap(usedMaterials))];
}

// Give a part its own copies of any materials it shares with the rest of the
// model, so its glow/colour can change without touching other parts.
function ownMaterials(obj) {
	const inside = new Set(partMeshes(obj));
	const users = new Map();
	viewer.model.traverse((o) => {
		if (!o.isMesh) return;
		for (const m of usedMaterials(o)) (users.get(m) || users.set(m, new Set()).get(m)).add(o);
	});
	const copies = new Map();
	for (const mesh of inside) {
		const isArray = Array.isArray(mesh.material);
		const list = isArray ? mesh.material.slice() : [mesh.material];
		let changed = false;
		for (const m of usedMaterials(mesh)) {
			if (![...users.get(m)].some((u) => !inside.has(u))) continue;
			if (!copies.has(m)) {
				const c = m.clone();
				c.userData = { ...m.userData, glow: m.userData.glow ? { ...m.userData.glow } : null };
				c.name = `${m.name} (${obj.name})`;
				copies.set(m, c);
			}
			list[list.indexOf(m)] = copies.get(m);
			changed = true;
		}
		if (changed) mesh.material = isArray ? list : list[0];
	}
	if (copies.size) viewer.applySettings();
	return partMaterials(obj);
}

const D2R = THREE.MathUtils.degToRad, R2D = THREE.MathUtils.radToDeg;

function renderPartsPanel() {
	if (!viewer.model) return needModel();
	const parts = partList();
	if (!parts.length) return [hint('This model is a single piece, so there are no separate parts to pick.')];
	if (state.part && !parts.some((p) => p.obj === state.part)) state.part = null;
	const obj = state.part;
	viewer.select(obj);
	const out = [
		hint(obj ? '' : 'Tap a part of the model to select it, or pick one from the list.'),
		select({
			options: [{ value: '', label: '— choose a part —' }, ...parts.map((p, i) => ({ value: String(i), label: '  '.repeat(p.depth) + p.obj.name }))],
			value: obj ? String(parts.findIndex((p) => p.obj === obj)) : '',
			onChange: (v) => ((state.part = v === '' ? null : parts[+v].obj), refresh()),
		}),
	];
	if (!obj) {
		viewer.detachGizmo();
		return out;
	}
	out.push(gizmoChips(obj));

	// Bigger / smaller part shortcuts
	const chain = [];
	for (let o = obj.parent; o && o !== viewer.model; o = o.parent) if (!o.name.endsWith('_pivot')) chain.push(o);
	const kids = obj.children.flatMap((c) => (c.name.endsWith('_pivot') ? c.children : [c])).filter((c) => c.name);
	if (chain.length || kids.length)
		out.push(h('div', { class: 'sub' }, h('span', { class: 'label' }, 'Also select'),
			h('div', { class: 'chips scroll' },
				chain.slice(0, 3).map((o) => h('button', { type: 'button', class: 'chip', onClick: () => ((state.part = o), refresh()) }, `↑ ${o.name}`)),
				kids.slice(0, 12).map((o) => h('button', { type: 'button', class: 'chip', onClick: () => ((state.part = o), refresh()) }, `↓ ${o.name}`)),
			)));

	const orig = obj.userData.orig || { p: obj.position.clone(), q: obj.quaternion.clone(), s: obj.scale.clone() };
	const changed = () => {
		player.setRest(obj);
		viewer.invalidate();
	};
	const parentScale = obj.parent.getWorldScale(new THREE.Vector3()).x || 1;
	const range = Math.max(1, Math.ceil((viewer.radius / parentScale) * 1.5));
	const euler = new THREE.Euler().setFromQuaternion(obj.quaternion, 'ZYX');
	const move = ['x', 'y', 'z'].map((a) => slider({ label: `Move ${a.toUpperCase()}`, min: -range, max: range, step: range / 400, value: obj.position[a] - orig.p[a], format: (v) => round(v, 0.01), onInput: (v) => ((obj.position[a] = orig.p[a] + v), changed()) }));
	const turn = ['x', 'y', 'z'].map((a) => slider({ label: `Turn ${a.toUpperCase()}`, min: -180, max: 180, step: 1, value: Math.round(R2D(euler[a])), format: (v) => `${Math.round(v)}°`, onInput: (v) => {
		const e = new THREE.Euler().setFromQuaternion(obj.quaternion, 'ZYX');
		e[a] = D2R(v);
		obj.quaternion.setFromEuler(e);
		changed();
	} }));
	const rel = (a) => obj.scale[a] / (orig.s[a] || 1);
	const sizeAxes = ['x', 'y', 'z'].map((a) => slider({ label: `Size ${a.toUpperCase()}`, min: 0.05, max: 4, step: 0.01, value: rel(a), format: (v) => `${Math.round(v * 100)}%`, onInput: (v) => ((obj.scale[a] = orig.s[a] * v), changed()) }));
	const size = slider({ label: 'Size (all)', min: 0.05, max: 4, step: 0.01, value: (rel('x') + rel('y') + rel('z')) / 3, format: (v) => `${Math.round(v * 100)}%`, onInput: (v) => {
		for (const [i, a] of ['x', 'y', 'z'].entries()) {
			obj.scale[a] = orig.s[a] * v;
			sizeAxes[i].set(v);
		}
		changed();
	} });
	out.push(
		h('details', { class: 'exact', open: state.exactOpen, onToggle: (e) => (state.exactOpen = e.target.open) },
			h('summary', {}, 'Exact values'),
			section('Move', ...move),
			section('Turn', ...turn),
			section('Size', size, ...sizeAxes),
		),
	);

	// Look of this part only
	const mats = partMaterials(obj);
	const first = mats[0];
	const glowNow = first ? glowStrength(first) : 0;
	const setPartGlow = (opts) => {
		for (const m of ownMaterials(obj)) setGlow(m, opts);
		viewer.syncGlowAll();
	};
	out.push(
		section(obj.children.length && partMeshes(obj).length > 1 ? 'Glow (this part and what\u2019s attached to it)' : 'Glow (this part only)',
			chips({ options: GLOW_PRESETS.map((p) => ({ value: p.strength, label: p.label })), value: GLOW_PRESETS.find((p) => Math.abs(p.strength - glowNow) < 0.01)?.strength, onChange: (v) => (setPartGlow({ strength: v }), refresh()) }),
			slider({ label: 'Glow strength', min: 0, max: 10, step: 0.1, value: glowNow, onInput: (v) => setPartGlow({ strength: v }) }),
			colorRow({ label: 'Glow colour', value: first ? glowColor(first) : '#ffffff', swatches: COLOR_SWATCHES, onChange: (c) => setPartGlow({ color: c, strength: glowNow || 2 }) }),
			mats.some((m) => m.userData.origMap ?? m.map) ? toggle({ label: 'Glow in the texture’s colours', value: !!first?.userData.glow?.useTexture, onChange: (v) => (setPartGlow({ useTexture: v, strength: glowStrength(first) || 2 }), refresh()) }) : null,
		),
		section('Colour (this part only)',
			colorRow({ label: 'Tint', value: first ? '#' + first.color.getHexString() : '#ffffff', swatches: COLOR_SWATCHES, onChange: (c) => {
				for (const m of ownMaterials(obj)) m.color.set(c);
				viewer.invalidate();
			} }),
			toggle({ label: 'Show this part', value: obj.visible, onChange: (v) => ((obj.visible = v), viewer.invalidate()) }),
		),
		h('div', { class: 'row-buttons' },
			button('Reset this part', () => {
				obj.position.copy(orig.p);
				obj.quaternion.copy(orig.q);
				obj.scale.copy(orig.s);
				obj.visible = true;
				changed();
				refresh();
			}, { kind: 'ghost', icon: ICONS.reset }),
			button('Reset all parts', async () => {
				if (!(await confirm('Put every part back where it was when the file was opened?', 'Reset'))) return;
				viewer.model.traverse((o) => {
					const r = o.userData.orig;
					if (!r || o === viewer.model) return;
					o.position.copy(r.p);
					o.quaternion.copy(r.q);
					o.scale.copy(r.s);
					o.visible = true;
					player.setRest(o);
				});
				viewer.invalidate();
				refresh();
			}, { kind: 'ghost' }),
		),
	);
	return out;
}

// ---- Look (materials)

viewer.on('pick', (hit) => {
	if (state.panel === 'animate') {
		if (!hit) return;
		let o = hit.object;
		const names = new Set(player.boneNames());
		while (o && !names.has(o.name)) o = o.parent;
		if (o) {
			state.bone = o.name;
			viewer.select(o);
			renderAnimatePanelBody();
		}
		return;
	}
	if (state.panel === 'material') {
		if (!hit) return;
		state.material = hit.material;
		flashSelection(hit.object);
		refresh();
		return;
	}
	// Anywhere else a tap selects the part under your finger
	if (!hit) {
		if (state.panel === 'parts') (state.part = null), viewer.select(null), refresh();
		return;
	}
	state.part = defaultPartFor(hit.object);
	viewer.select(state.part);
	showPanel('parts');
});

function flashSelection(obj) {
	viewer.select(obj);
	clearTimeout(flashSelection.t);
	flashSelection.t = setTimeout(() => state.panel !== 'animate' && viewer.select(null), 900);
}

function renderMaterialPanel() {
	if (!viewer.model) return needModel();
	const mats = viewer.materials();
	if (!state.material || !mats.includes(state.material)) state.material = mats[0];
	const m = state.material;
	const s = viewer.settings;
	const out = [
		toggle({ label: 'Show textures', hint: 'Turn off to see the plain shapes', value: s.textures, onChange: (v) => viewer.set({ textures: v }) }),
		section('Material', hint(mats.length > 1 ? 'Tap the model to pick the material you want to change.' : ''),
			chips({ options: mats.map((x) => ({ value: x, label: x.name || 'Material', swatch: '#' + x.color.getHexString() })), value: m, scroll: true, onChange: (v) => ((state.material = v), refresh()) }),
		),
	];
	if (!m) return out;
	const set = (fn) => {
		fn();
		m.needsUpdate = true;
		viewer.invalidate();
	};
	const tex = m.userData.origMap ?? m.map;
	out.push(
		section('Colour',
			colorRow({ label: tex ? 'Tint' : 'Colour', value: '#' + m.color.getHexString(), swatches: COLOR_SWATCHES, onChange: (c) => set(() => m.color.set(c)) }),
		),
		section('Texture',
			h('div', { class: 'tex-row' },
				tex?.image ? h('img', { class: 'tex-thumb', src: thumb(tex.image) }) : h('div', { class: 'tex-thumb none' }, 'none'),
				h('div', { class: 'stack' },
					button(tex ? 'Replace texture' : 'Add texture', () => pickFiles('replace'), { kind: 'small' }),
					tex ? button('Remove texture', () => (viewer.setMaterialMap(m, null), refresh()), { kind: 'small ghost' }) : null,
				),
			),
		),
		section('Glow (everywhere this material is used)',
			hint('To make just one part glow, tap it and use the Parts tab.'),
			chips({ options: GLOW_PRESETS.map((p) => ({ value: p.strength, label: p.label })), value: GLOW_PRESETS.find((p) => Math.abs(p.strength - glowStrength(m)) < 0.01)?.strength, onChange: (v) => (setGlow(m, { strength: v }), viewer.syncGlowAll(), refresh()) }),
			slider({ label: 'Glow strength', min: 0, max: 10, step: 0.1, value: glowStrength(m), onInput: (v) => (setGlow(m, { strength: v }), viewer.syncGlowAll()) }),
			colorRow({ label: 'Glow colour', value: glowColor(m), swatches: COLOR_SWATCHES, onChange: (c) => (setGlow(m, { color: c, strength: glowStrength(m) || 2 }), viewer.syncGlowAll()) }),
			tex ? toggle({ label: 'Glow in the texture\u2019s colours', hint: 'Like Minecraft emissive textures', value: !!m.userData.glow?.useTexture, onChange: (v) => (setGlow(m, { useTexture: v, strength: glowStrength(m) || 2 }), viewer.syncGlowAll(), refresh()) }) : null,
		),
		section('Surface',
			slider({ label: 'Metal', min: 0, max: 1, step: 0.01, value: m.metalness, format: pct, onInput: (v) => set(() => (m.metalness = v)) }),
			slider({ label: 'Shine', min: 0, max: 1, step: 0.01, value: 1 - m.roughness, format: pct, onInput: (v) => set(() => (m.roughness = 1 - v)) }),
			m.metalness > 0.3 && !viewer.settings.reflections ? hint('Metal looks best with Reflections on (Light tab).') : null,
			slider({ label: 'See-through', min: 0, max: 1, step: 0.01, value: 1 - m.opacity, format: pct, onInput: (v) => (setOpacity(m, 1 - v), viewer.invalidate()) }),
			toggle({ label: 'Two-sided', value: m.side === THREE.DoubleSide, onChange: (v) => set(() => (m.side = v ? THREE.DoubleSide : THREE.FrontSide)) }),
			toggle({ label: 'Faceted (flat shading)', value: m.flatShading, onChange: (v) => set(() => (m.flatShading = v)) }),
			toggle({ label: 'Wireframe', value: !!m.userData.wireframe, onChange: (v) => ((m.userData.wireframe = v), viewer.applySettings()) }),
		),
		h('div', { class: 'row-buttons' },
			button('Rename', async () => {
				const n = await prompt('Material name', m.name);
				if (n) (m.name = n), refresh();
			}, { kind: 'ghost' }),
			button('Reset material', () => (resetMaterial(m, viewer), viewer.syncGlowAll(), refresh()), { kind: 'ghost', icon: ICONS.reset }),
		),
	);
	return out;
}

const pct = (v) => Math.round(v * 100) + '%';

const thumbs = new WeakMap();
function thumb(img) {
	if (thumbs.has(img)) return thumbs.get(img);
	const c = document.createElement('canvas');
	c.width = img.width;
	c.height = img.height;
	c.getContext('2d').drawImage(img, 0, 0);
	const url = c.toDataURL();
	thumbs.set(img, url);
	return url;
}

// ---- Light

function renderLightPanel() {
	const s = viewer.settings;
	const out = [
		toggle({ label: 'Studio light', hint: s.studioLight ? 'A movable light with shadows' : 'Off: the model is evenly lit so you see its true colours', value: s.studioLight, onChange: (v) => (viewer.set({ studioLight: v }), refresh()) }),
	];
	if (s.studioLight) {
		out.push(
			section('Light angle',
				slider({ label: 'Around', min: 0, max: 360, step: 1, value: s.azimuth, format: (v) => `${Math.round(v)}°`, onInput: (v) => viewer.set({ azimuth: v }) }),
				slider({ label: 'Height', min: -10, max: 90, step: 1, value: s.elevation, format: (v) => `${Math.round(v)}°`, onInput: (v) => viewer.set({ elevation: v }) }),
				chips({
					options: [
						{ value: 'front', label: 'Front' },
						{ value: 'side', label: 'Side' },
						{ value: 'top', label: 'Top' },
						{ value: 'back', label: 'Rim' },
					],
					onChange: (v) => {
						const p = { front: [20, 35], side: [90, 25], top: [0, 88], back: [200, 30] }[v];
						viewer.set({ azimuth: p[0], elevation: p[1] });
						refresh();
					},
				}),
			),
			section('Light',
				slider({ label: 'Brightness', min: 0, max: 8, step: 0.05, value: s.lightIntensity, onInput: (v) => viewer.set({ lightIntensity: v }) }),
				colorRow({ label: 'Colour', value: s.lightColor, swatches: ['#ffffff', '#fff1d6', '#ffd9a0', '#d6e8ff', '#a0c4ff', '#ffb0d0'], onChange: (c) => viewer.set({ lightColor: c }) }),
				slider({ label: 'Fill light', min: 0, max: 1, step: 0.01, value: s.fill, format: pct, onInput: (v) => viewer.set({ fill: v }) }),
				toggle({ label: 'Shadows', value: s.shadows, onChange: (v) => viewer.set({ shadows: v }) }),
			),
		);
	}
	out.push(
		section('Extras',
			toggle({ label: 'Reflections', hint: 'Makes metal and shiny materials reflect a studio', value: s.reflections, onChange: (v) => viewer.set({ reflections: v }) }),
			slider({ label: 'Glow spread', min: 0, max: 1, step: 0.01, value: s.bloomRadius, format: pct, onInput: (v) => viewer.set({ bloomRadius: v }) }),
			slider({ label: 'Glow intensity', min: 0, max: 3, step: 0.05, value: s.bloomStrength, onInput: (v) => viewer.set({ bloomStrength: v }) }),
		),
		button('Reset lighting', () => {
			const d = DEFAULT_SETTINGS;
			viewer.set({ studioLight: d.studioLight, azimuth: d.azimuth, elevation: d.elevation, lightIntensity: d.lightIntensity, lightColor: d.lightColor, fill: d.fill, shadows: d.shadows, reflections: d.reflections, bloomStrength: d.bloomStrength, bloomRadius: d.bloomRadius });
			refresh();
		}, { kind: 'ghost', icon: ICONS.reset }),
	);
	return out;
}

// ---- Animate

let animBody = null;
function renderAnimatePanel() {
	animBody = h('div', { class: 'anim' });
	renderAnimatePanelBody();
	return animBody;
}

function renderAnimatePanelBody() {
	if (!animBody) return;
	if (!viewer.model) return animBody.replaceChildren(needModel());
	const c = clip();
	const parts = [];
	parts.push(
		section('Animations',
			state.clips.length
				? chips({ options: state.clips.map((x, i) => ({ value: i, label: x.name })), value: state.clipIndex, scroll: true, onChange: (i) => ((state.clipIndex = i), (state.playing = false), setTime(0), renderAnimatePanelBody()) })
				: hint('No animations yet. Make a new one, or open a .msanim / Minecraft .animation.json file.'),
			h('div', { class: 'row-buttons' },
				button('New', async () => {
					const name = await prompt('Name for the new animation', `animation_${state.clips.length + 1}`);
					if (!name) return;
					state.clips.push(newClip(name, 2));
					state.clipIndex = state.clips.length - 1;
					state.playing = false;
					setTime(0);
					renderAnimatePanelBody();
				}, { kind: 'small', icon: ICONS.plus }),
				button('Open file', () => pickFiles('anim'), { kind: 'small', icon: ICONS.open }),
				c ? button('Rename', async () => {
					const name = await prompt('Rename animation', c.name);
					if (name) (c.name = name), renderAnimatePanelBody(), updateHud();
				}, { kind: 'small ghost' }) : null,
				c ? button('Copy', () => {
					state.clips.push({ ...structuredClone(c), name: c.name + '_copy' });
					state.clipIndex = state.clips.length - 1;
					renderAnimatePanelBody();
				}, { kind: 'small ghost' }) : null,
				c ? button('Delete', async () => {
					if (!(await confirm(`Delete the animation "${c.name}"?`, 'Delete'))) return;
					state.clips.splice(state.clipIndex, 1);
					state.clipIndex = Math.max(0, state.clipIndex - 1);
					state.playing = false;
					setTime(0);
					renderAnimatePanelBody();
					updateHud();
				}, { kind: 'small ghost danger', icon: ICONS.trash }) : null,
			),
		),
	);
	if (c) {
		parts.push(h('div', { id: 'transport' }));
		parts.push(
			section('Settings',
				h('div', { class: 'grid2' },
					numberRow({ label: 'Length', value: round(c.length, 0.01), min: 0.05, step: 0.05, suffix: 's', onChange: (v) => ((c.length = Math.max(0.05, v)), setTime(Math.min(state.time, c.length)), renderAnimatePanelBody()) }),
					numberRow({ label: 'Frames/s', value: c.fps, min: 1, max: 120, step: 1, onChange: (v) => ((c.fps = Math.round(v)), renderAnimatePanelBody()) }),
				),
				chips({ options: LOOP_MODES.map((l) => ({ value: l, label: { loop: 'Loop', once: 'Play once', hold: 'Hold last frame' }[l] })), value: c.loop, onChange: (v) => (c.loop = v) }),
			),
		);
		parts.push(renderBoneEditor(c));
	} else {
		parts.push(
			h('div', { class: 'empty-panel' },
				button('Start a new animation', () => {
					state.clips.push(newClip('animation_1', 2));
					state.clipIndex = state.clips.length - 1;
					setTime(0);
					renderAnimatePanelBody();
					updateHud();
				}, { kind: 'primary big', icon: ICONS.plus }),
				hint('Then tap a part and move the sliders; each change is saved as a keyframe. To just pose or resize parts without animating, use the Parts tab.'),
			),
		);
	}
	animBody.replaceChildren(...parts);
	renderAnimTransport();
}

function renderAnimTransport() {
	const el = animBody?.querySelector('#transport');
	const c = clip();
	if (!el || !c) return;
	const times = keyTimes(c, state.bone);
	const timeline = h('div', { class: 'timeline' });
	const input = h('input', { type: 'range', min: 0, max: c.length, step: 0.001, value: state.time });
	input.addEventListener('input', () => {
		state.playing = false;
		setTime(snapToFrame(parseFloat(input.value)));
		syncPlayButton();
	});
	const marks = h('div', { class: 'marks' }, times.map((t) => h('span', { class: 'mark', style: { left: `${(t / (c.length || 1)) * 100}%` } })));
	timeline.append(marks, input);
	const frame = 1 / (c.fps || 24);
	const prevKey = () => setTime([...times].reverse().find((t) => t < state.time - 1e-4) ?? 0);
	const nextKey = () => setTime(times.find((t) => t > state.time + 1e-4) ?? c.length);
	el.replaceChildren(
		h('div', { class: 'transport' },
			h('button', { class: 'icon-btn', onClick: prevKey, 'aria-label': 'Previous keyframe', html: ICONS.prev }),
			h('button', { class: 'icon-btn play', id: 'play-btn', onClick: togglePlay, 'aria-label': 'Play', html: state.playing ? ICONS.pause : ICONS.play }),
			h('button', { class: 'icon-btn', onClick: nextKey, 'aria-label': 'Next keyframe', html: ICONS.next }),
			h('span', { class: 'time', id: 'time-label' }, `${state.time.toFixed(2)}s / ${c.length.toFixed(2)}s`),
			h('div', { class: 'step-buttons' },
				button('-1f', () => ((state.playing = false), setTime(Math.round((state.time - frame) / frame) * frame), syncPlayButton()), { kind: 'small ghost' }),
				button('+1f', () => ((state.playing = false), setTime(Math.round((state.time + frame) / frame) * frame), syncPlayButton()), { kind: 'small ghost' }),
			),
		),
		timeline,
		chips({ options: [0.25, 0.5, 1, 2].map((s) => ({ value: s, label: `${s}x` })), value: state.speed, onChange: (v) => (state.speed = v) }),
	);
}

// Keyframes land on whole frames, so scrubbing snaps to the clip's frame rate.
function snapToFrame(t) {
	const fps = clip()?.fps || 24;
	return Math.round(t * fps) / fps;
}

function syncPlayButton() {
	const b = document.getElementById('play-btn');
	if (b) b.innerHTML = state.playing ? ICONS.pause : ICONS.play;
}

// Keeps the time slider, label and the bone sliders in step while playing/scrubbing.
let lastSync = 0;
function syncAnimUI() {
	if (state.panel !== 'animate') return;
	const now = performance.now();
	if (state.playing && now - lastSync < 50) return;
	lastSync = now;
	const c = clip();
	const range = document.querySelector('#transport input[type=range]');
	if (range && c) range.value = state.time;
	const label = document.getElementById('time-label');
	if (label && c) label.textContent = `${state.time.toFixed(2)}s / ${c.length.toFixed(2)}s`;
	syncPlayButton();
	if (boneSliders) {
		const obj = player.find(state.bone);
		if (obj) for (const ch of ['rotation', 'position', 'scale']) {
			const v = player.delta(obj, ch);
			boneSliders[ch]?.forEach((s, i) => s.set(round(v[i], 0.01)));
		}
		const keyHere = keyTimes(c, state.bone).some((t) => Math.abs(t - state.time) < 1e-3);
		document.getElementById('key-here')?.classList.toggle('on', keyHere);
	}
}

let boneSliders = null;
function renderBoneEditor(c) {
	const bones = player.boneNames();
	boneSliders = null;
	if (!bones.length) return section('Parts', hint('This model has no named parts to animate.'));
	if (!state.bone || !bones.includes(state.bone)) state.bone = bones[0];
	const obj = player.find(state.bone);
	viewer.select(obj);
	const handles = gizmoChips(obj);
	const animated = new Set(c.tracks.map((t) => t.bone));
	const out = section('Part to move',
		hint('Tap the model or pick a part, then drag its handles (or the sliders below). Each change is saved as a keyframe at the current time.'),
		select({ options: bones.map((b) => ({ value: b, label: animated.has(b) ? `${b}  •` : b })), value: state.bone, onChange: (v) => ((state.bone = v), renderAnimatePanelBody()) }),
		handles,
	);
	const r = viewer.radius / Math.abs(viewer.model.getWorldScale(new THREE.Vector3()).x || 1);
	const posRange = Math.max(1, Math.ceil(r * 1.5));
	const make = (channel, axis, opts) =>
		slider({
			...opts,
			value: player.delta(obj, channel)[axis],
			onInput: (v) => {
				if (state.playing || Math.abs(snapToFrame(state.time) - state.time) > 1e-6) {
					state.playing = false;
					setTime(snapToFrame(state.time));
				}
				const cur = player.delta(obj, channel);
				cur[axis] = v;
				setKey(c, state.bone, channel, state.time, cur, state.interpolation);
				player.pose(c, state.time);
				viewer.invalidate();
				document.getElementById('key-here')?.classList.add('on');
			},
			onChange: () => renderAnimTransport(),
		});
	boneSliders = {
		rotation: ['X', 'Y', 'Z'].map((a, i) => make('rotation', i, { label: `Turn ${a}`, min: -180, max: 180, step: 1, format: (v) => `${Math.round(v)}°` })),
		position: ['X', 'Y', 'Z'].map((a, i) => make('position', i, { label: `Move ${a}`, min: -posRange, max: posRange, step: posRange / 200 })),
		scale: ['X', 'Y', 'Z'].map((a, i) => make('scale', i, { label: `Size ${a}`, min: 0, max: 3, step: 0.01 })),
	};
	const keyHere = keyTimes(c, state.bone).some((t) => Math.abs(t - state.time) < 1e-3);
	const keys = keyTimes(c, state.bone);
	out.append(
		h('div', { class: 'key-status' },
			h('span', { id: 'key-here', class: 'key-dot' + (keyHere ? ' on' : ''), html: ICONS.key }),
			h('span', {}, 'Keyframes:'),
			keys.length ? h('div', { class: 'chips scroll' }, keys.map((t) => h('button', { type: 'button', class: 'chip', onClick: () => ((state.playing = false), setTime(t)) }, `${t.toFixed(2)}s`))) : h('span', { class: 'muted' }, 'none yet'),
		),
		h('div', { class: 'row-buttons' },
			button('Save keyframe', () => {
				state.playing = false;
				setTime(snapToFrame(state.time));
				for (const ch of ['rotation', 'position', 'scale']) {
					const v = player.delta(obj, ch);
					const isDefault = v.every((x, i) => Math.abs(x - defaultValue(ch)[i]) < 1e-4);
					if (!isDefault || getTrack(c, state.bone, ch)) setKey(c, state.bone, ch, state.time, v, state.interpolation);
				}
				if (!getTrack(c, state.bone, 'rotation')) setKey(c, state.bone, 'rotation', state.time, [0, 0, 0], state.interpolation);
				toast(`Keyframe saved at ${state.time.toFixed(2)}s`);
				renderAnimatePanelBody();
			}, { kind: 'small primary', icon: ICONS.key }),
			button('Delete keyframe', () => {
				removeKey(c, state.bone, state.time);
				setTime(state.time);
				renderAnimatePanelBody();
			}, { kind: 'small ghost', disabled: !keyHere }),
			button('Clear part', async () => {
				if (!(await confirm(`Remove all keyframes of "${state.bone}" in this animation?`, 'Clear'))) return;
				c.tracks = c.tracks.filter((t) => t.bone !== state.bone);
				setTime(state.time);
				renderAnimatePanelBody();
			}, { kind: 'small ghost danger' }),
		),
		h('div', { class: 'sub' }, h('span', { class: 'label' }, 'New keyframes'),
			chips({ options: [{ value: 'smooth', label: 'Smooth' }, { value: 'linear', label: 'Linear' }, { value: 'step', label: 'Snap' }], value: state.interpolation, onChange: (v) => (state.interpolation = v) })),
		h('h4', {}, 'Turn'),
		...boneSliders.rotation,
		h('h4', {}, 'Move'),
		...boneSliders.position,
		h('h4', {}, 'Size'),
		...boneSliders.scale,
	);
	return out;
}

// ---- Save

function renderSavePanel(options = {}) {
	if (!viewer.model) return needModel();
	const avail = modelFormatAvailability(viewer.model);
	const out = [];
	if (options.banner) out.push(h('div', { class: 'banner' }, options.banner));
	if (state.lastSaved) out.push(h('div', { class: 'saved' }, h('span', {}, state.lastSaved), isAndroid() ? button('Share', shareLastSaved, { kind: 'small', icon: ICONS.share }) : null));
	const card = (f) => {
		const reason = f.id === 'mc-bedrock' ? avail['mc-bedrock'] : null;
		const desc = reason || (f.id === 'mc-java' ? `${f.desc} ${avail['mc-java']}` : f.desc);
		return h('button', { type: 'button', class: 'format-card', disabled: !!reason, onClick: () => doExport(f.id) }, h('strong', {}, f.label), h('span', {}, desc));
	};
	out.push(section('Save the model as', h('div', { class: 'format-list' }, MODEL_FORMATS.map((f) => card(f)))));
	const voxel = viewer.model.userData.source !== 'java' || state.javaVoxel;
	out.push(
		section('Minecraft Java options',
			viewer.model.userData.source === 'java' ? toggle({ label: 'Rebuild as voxels', hint: 'Off keeps the original cubes', value: state.javaVoxel, onChange: (v) => ((state.javaVoxel = v), refresh()) }) : null,
			voxel ? h('div', { class: 'sub' }, h('span', { class: 'label' }, 'Detail'), chips({ options: [{ value: 8, label: 'Low' }, { value: 16, label: 'Normal' }, { value: 32, label: 'High' }], value: state.voxelRes, onChange: (v) => (state.voxelRes = v) })) : null,
		),
	);
	out.push(
		section('Save the animations as',
			state.clips.length ? null : hint('No animations yet — make one in the Animate tab.'),
			h('div', { class: 'format-list' }, ANIM_FORMATS.map((f) => h('button', { type: 'button', class: 'format-card', disabled: !state.clips.length, onClick: () => doExport(f.id) }, h('strong', {}, f.label), h('span', {}, f.desc)))),
		),
	);
	return out;
}

async function doExport(id) {
	busy('Saving…');
	const wasPlaying = state.playing;
	state.playing = false;
	try {
		const { name, blob, note } = await runExport(id, { viewer, player, clips: state.clips }, {
			resolution: state.voxelRes,
			voxel: state.javaVoxel,
			onProgress: (p) => busy(`Converting… ${Math.round(p * 100)}%`),
		});
		const where = await saveFile(name, blob);
		state.lastSaved = `${where}${note ? ' — ' + note : ''}`;
		toast(state.lastSaved);
		if (state.panel === 'save') refresh();
	} catch (err) {
		console.error(err);
		toast(err.message || String(err), 'error');
	} finally {
		busy(null);
		state.playing = wasPlaying;
		player.pose(clip(), state.time);
	}
}

// ---- View

function renderViewPanel() {
	const s = viewer.settings;
	const cam = (dir) => () => {
		const t = viewer.controls.target;
		const d = viewer.camera.position.distanceTo(t);
		viewer.camera.position.copy(t).addScaledVector(new THREE.Vector3(...dir).normalize(), d);
		viewer.controls.update();
		viewer.invalidate();
	};
	return [
		section('Camera',
			h('div', { class: 'chips' },
				[['Front', [0, 0, 1]], ['Back', [0, 0, -1]], ['Left', [-1, 0, 0]], ['Right', [1, 0, 0]], ['Top', [0, 1, 0.0001]], ['3/4', [0.9, 0.6, 1.2]]].map(([l, d]) => h('button', { type: 'button', class: 'chip', onClick: cam(d) }, l)),
			),
			h('div', { class: 'row-buttons' },
				button('Fit model', () => viewer.frame(), { kind: 'small', icon: ICONS.reset }),
				button('Screenshot', () => doExport('png'), { kind: 'small', icon: ICONS.camera }),
			),
			toggle({ label: 'Spin slowly', value: s.autoRotate, onChange: (v) => viewer.set({ autoRotate: v }) }),
		),
		section('Display',
			toggle({ label: 'Floor grid', value: s.grid, onChange: (v) => viewer.set({ grid: v }) }),
			toggle({ label: 'Wireframe', hint: 'Show the triangles of every part', value: s.wireframe, onChange: (v) => viewer.set({ wireframe: v }) }),
			toggle({ label: 'Minecraft face shading', hint: 'Darkens sides like the game does (Minecraft models only)', value: s.faceShade, onChange: (v) => viewer.set({ faceShade: v }) }),
			colorRow({ label: 'Background', value: s.background, swatches: ['#20232a', '#000000', '#3a3f4b', '#808890', '#d0d4da', '#ffffff', '#1d3557', '#2a9d8f', '#79c0ff'], onChange: (c) => viewer.set({ background: c }) }),
		),
		section('About', hint('Model Studio — view, restyle, animate and convert 3D models and Minecraft models. Saved files go to your Downloads folder.')),
	];
}

// ---------------------------------------------------------------- startup

buildTabs();
$('#sheet-close').addEventListener('click', hidePanel);
$('#btn-open').addEventListener('click', () => pickFiles('open'));
$('#btn-fit').addEventListener('click', () => viewer.frame());
$('#empty-open').addEventListener('click', () => pickFiles('open'));
$('#empty-sample').addEventListener('click', loadSample);
$('#empty-convert').addEventListener('click', () => {
	state.convertMode = true;
	pickFiles('open');
});

// Drag-and-drop for desktop browsers
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('drop', (e) => {
	e.preventDefault();
	if (e.dataTransfer?.files?.length) openFiles([...e.dataTransfer.files]);
});

// Hooks for the Android app
window.App = {
	onBack() {
		if (document.querySelector('.dialog-overlay')) {
			document.querySelector('.dialog-overlay').remove();
			return true;
		}
		if (state.panel) {
			hidePanel();
			return true;
		}
		return false;
	},
	async receivePicked(purpose, list) {
		try {
			openFiles(await fetchIncoming(list), purpose);
		} catch (err) {
			toast(err.message, 'error');
		}
	},
	async openIncoming(list) {
		try {
			openFiles(await fetchIncoming(list));
		} catch (err) {
			toast(err.message, 'error');
		}
	},
	// For tests and debugging
	state,
	viewer,
	player,
	openFiles,
	loadSample,
	doExport,
	showPanel,
};
if (isAndroid()) document.body.classList.add('android');
window.AndroidBridge?.ready?.();
