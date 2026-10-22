// The 3D view: renderer, camera, touch controls, lighting, glow and helpers.
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/examples/jsm/postprocessing/OutputPass.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import { applyFaceShade } from './loaders/cuboid.js';
import { collectMaterials } from './loaders/index.js';

// Ambient intensity at which an unlit white material renders as pure white.
const FLAT = Math.PI;

export const DEFAULT_SETTINGS = {
	studioLight: false,
	azimuth: 45,
	elevation: 50,
	lightIntensity: 2.5,
	lightColor: '#ffffff',
	fill: 0.35,
	shadows: true,
	reflections: false,
	background: '#20232a',
	grid: true,
	autoRotate: false,
	textures: true,
	faceShade: true,
	bloomStrength: 0.6,
	bloomRadius: 0.3,
	wireframe: false,
};

export class Viewer {
	constructor(container, settings) {
		this.container = container;
		this.settings = { ...DEFAULT_SETTINGS, ...settings };
		this.model = null;
		this.listeners = {};

		const renderer = (this.renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: false }));
		renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
		renderer.outputColorSpace = THREE.SRGBColorSpace;
		renderer.toneMapping = THREE.NoToneMapping;
		renderer.shadowMap.enabled = true;
		renderer.shadowMap.type = THREE.PCFSoftShadowMap;
		container.appendChild(renderer.domElement);

		this.scene = new THREE.Scene();
		this.camera = new THREE.PerspectiveCamera(45, 1, 0.01, 1000);
		this.camera.position.set(2.5, 2, 3);

		const controls = (this.controls = new OrbitControls(this.camera, renderer.domElement));
		controls.enableDamping = true;
		controls.dampingFactor = 0.12;
		controls.screenSpacePanning = true;
		controls.addEventListener('change', () => this.invalidate());

		this.ambient = new THREE.AmbientLight(0xffffff, FLAT);
		this.sun = new THREE.DirectionalLight(0xffffff, 2.5);
		this.sun.castShadow = true;
		this.sun.shadow.mapSize.set(2048, 2048);
		this.sun.shadow.bias = -0.0005;
		this.sun.shadow.normalBias = 0.02;
		this.scene.add(this.ambient, this.sun, this.sun.target);

		this.grid = new THREE.GridHelper(4, 16, 0x888888, 0x555555);
		this.grid.material.transparent = true;
		this.grid.material.opacity = 0.35;
		this.scene.add(this.grid);

		this.ground = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.ShadowMaterial({ opacity: 0.25 }));
		this.ground.rotation.x = -Math.PI / 2;
		this.ground.receiveShadow = true;
		this.scene.add(this.ground);

		this.sunMarker = new THREE.Mesh(new THREE.SphereGeometry(1, 16, 12), new THREE.MeshBasicMaterial({ color: 0xffd34d }));
		this.sunLine = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]), new THREE.LineDashedMaterial({ color: 0xffd34d, dashSize: 0.05, gapSize: 0.04 }));
		this.scene.add(this.sunMarker, this.sunLine);
		this.showSunMarker = false;

		this.selectionBox = new THREE.Box3Helper(new THREE.Box3(), 0x4dabff);
		this.selectionBox.visible = false;
		this.scene.add(this.selectionBox);
		this.selected = null;

		const pmrem = new THREE.PMREMGenerator(renderer);
		this.envMap = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

		this.composer = new EffectComposer(renderer);
		this.composer.addPass(new RenderPass(this.scene, this.camera));
		this.bloom = new UnrealBloomPass(new THREE.Vector2(256, 256), 0.8, 0.4, 1.0);
		this.composer.addPass(this.bloom);
		this.composer.addPass(new OutputPass());

		this.radius = 1;
		this.clock = new THREE.Clock();
		this.texTime = 0;
		this.needsRender = true;
		this.frameHooks = [];

		this.setupPicking();
		new ResizeObserver(() => this.resize()).observe(container);
		this.resize();
		this.applySettings();
		renderer.setAnimationLoop(() => this.tick());
	}

	on(event, fn) {
		(this.listeners[event] ||= []).push(fn);
	}

	emit(event, ...args) {
		for (const fn of this.listeners[event] || []) fn(...args);
	}

	invalidate() {
		this.needsRender = true;
	}

	resize() {
		const w = this.container.clientWidth || 1, h = this.container.clientHeight || 1;
		this.renderer.setSize(w, h, false);
		this.composer.setSize(w, h);
		this.camera.aspect = w / h;
		this.camera.updateProjectionMatrix();
		this.invalidate();
	}

	setModel(root) {
		if (this.model) {
			this.scene.remove(this.model);
			disposeTree(this.model);
		}
		this.model = root;
		this.select(null);
		if (root) {
			this.scene.add(root);
			this.animatedTextures = [];
			for (const m of collectMaterials(root)) {
				m.userData.origMap ??= m.map;
				if (m.map?.userData.frames > 1 && !this.animatedTextures.includes(m.map)) this.animatedTextures.push(m.map);
			}
		}
		this.applySettings();
		this.frame();
	}

	materials() {
		return collectMaterials(this.model);
	}

	frame() {
		const box = new THREE.Box3();
		if (this.model) {
			this.model.updateMatrixWorld(true);
			box.setFromObject(this.model);
		}
		if (box.isEmpty()) box.set(new THREE.Vector3(-0.5, -0.5, -0.5), new THREE.Vector3(0.5, 0.5, 0.5));
		const center = box.getCenter(new THREE.Vector3());
		const size = box.getSize(new THREE.Vector3());
		const radius = (this.radius = Math.max(size.length() / 2, 1e-3));
		const vHalf = THREE.MathUtils.degToRad(this.camera.fov / 2);
		const hHalf = Math.atan(Math.tan(vHalf) * this.camera.aspect);
		const dist = (radius / Math.sin(Math.min(vHalf, hHalf))) * 1.05;
		const dir = new THREE.Vector3(0.9, 0.6, 1.2).normalize();
		this.controls.target.copy(center);
		this.camera.position.copy(center).addScaledVector(dir, dist);
		this.camera.near = radius / 100;
		this.camera.far = radius * 100 + dist;
		this.camera.updateProjectionMatrix();
		this.controls.minDistance = radius / 20;
		this.controls.maxDistance = radius * 30;
		this.controls.update();

		const step = niceStep(radius);
		const extent = Math.ceil((radius * 3) / step) * step;
		this.grid.geometry.dispose();
		this.scene.remove(this.grid);
		this.grid = new THREE.GridHelper(extent * 2, Math.round((extent * 2) / step), 0x888888, 0x555555);
		this.grid.material.transparent = true;
		this.grid.material.opacity = 0.35;
		this.grid.position.set(center.x, box.min.y, center.z);
		this.scene.add(this.grid);
		this.ground.scale.setScalar(extent * 4);
		this.ground.position.set(center.x, box.min.y - radius * 0.001, center.z);
		this.modelBox = box;
		this.placeSun();
		this.applySettings();
	}

	placeSun() {
		const s = this.settings;
		const center = this.modelBox ? this.modelBox.getCenter(new THREE.Vector3()) : new THREE.Vector3();
		const az = THREE.MathUtils.degToRad(s.azimuth), el = THREE.MathUtils.degToRad(s.elevation);
		const dir = new THREE.Vector3(Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az));
		const d = this.radius * 3;
		this.sun.position.copy(center).addScaledVector(dir, d);
		this.sun.target.position.copy(center);
		const cam = this.sun.shadow.camera;
		cam.left = cam.bottom = -this.radius * 1.5;
		cam.right = cam.top = this.radius * 1.5;
		cam.near = d - this.radius * 2;
		cam.far = d + this.radius * 3;
		cam.updateProjectionMatrix();
		this.sunMarker.position.copy(center).addScaledVector(dir, this.radius * 1.6);
		this.sunMarker.scale.setScalar(this.radius * 0.06);
		this.sunLine.geometry.setFromPoints([center, this.sunMarker.position.clone()]);
		this.sunLine.computeLineDistances();
		this.sunLine.material.dashSize = this.radius * 0.06;
		this.sunLine.material.gapSize = this.radius * 0.04;
	}

	set(partial) {
		Object.assign(this.settings, partial);
		this.applySettings();
		this.emit('settings', this.settings);
	}

	applySettings() {
		const s = this.settings;
		this.scene.background = new THREE.Color(s.background);
		this.ambient.intensity = s.studioLight ? FLAT * s.fill : FLAT;
		this.sun.visible = s.studioLight;
		this.sun.intensity = s.lightIntensity;
		this.sun.color.set(s.lightColor);
		this.sun.castShadow = s.studioLight && s.shadows;
		this.ground.visible = s.studioLight && s.shadows;
		this.sunMarker.visible = this.sunLine.visible = s.studioLight && this.showSunMarker;
		this.scene.environment = s.reflections ? this.envMap : null;
		this.grid.visible = s.grid;
		this.controls.autoRotate = s.autoRotate;
		this.bloom.strength = s.bloomStrength;
		this.bloom.radius = s.bloomRadius;
		this.placeSun();
		for (const m of this.materials()) {
			const want = s.textures ? m.userData.origMap ?? m.map : null;
			if (m.map !== want) {
				m.map = want;
				m.needsUpdate = true;
			}
			if (m.userData.glowUsesTexture) m.emissiveMap = m.map;
			if (m.wireframe !== (s.wireframe || !!m.userData.wireframe)) m.wireframe = s.wireframe || !!m.userData.wireframe;
		}
		this.model?.traverse((o) => {
			if (o.geometry?.userData.shade) applyFaceShade(o.geometry, s.faceShade);
		});
		this.invalidate();
	}

	// Called after a material's map changes so "textures off" can restore it later.
	setMaterialMap(m, tex) {
		m.userData.origMap = tex;
		m.map = this.settings.textures ? tex : null;
		if (m.userData.glowUsesTexture) m.emissiveMap = m.map;
		m.needsUpdate = true;
		this.invalidate();
	}

	hasGlow() {
		return this.materials().some((m) => m.emissiveIntensity > 0 && (m.emissive.r + m.emissive.g + m.emissive.b) > 0);
	}

	select(obj) {
		this.selected = obj;
		this.selectionBox.visible = !!obj;
		this.updateSelectionBox();
		this.invalidate();
	}

	updateSelectionBox() {
		if (!this.selected) return;
		this.selected.updateWorldMatrix(true, true);
		this.selectionBox.box.setFromObject(this.selected);
	}

	setupPicking() {
		const el = this.renderer.domElement;
		let down = null;
		el.addEventListener('pointerdown', (e) => (down = { x: e.clientX, y: e.clientY, t: performance.now() }));
		el.addEventListener('pointerup', (e) => {
			if (!down || !this.model) return;
			const moved = Math.hypot(e.clientX - down.x, e.clientY - down.y);
			if (moved > 8 || performance.now() - down.t > 500) return;
			const rect = el.getBoundingClientRect();
			const ndc = new THREE.Vector2(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
			const ray = new THREE.Raycaster();
			ray.setFromCamera(ndc, this.camera);
			const hit = ray.intersectObject(this.model, true).find((h) => h.object.visible && h.object.isMesh);
			if (!hit) return this.emit('pick', null);
			const mats = Array.isArray(hit.object.material) ? hit.object.material : [hit.object.material];
			const material = mats[hit.face?.materialIndex ?? 0] || mats[0];
			this.emit('pick', { object: hit.object, material });
		});
	}

	addFrameHook(fn) {
		this.frameHooks.push(fn);
	}

	tick() {
		const dt = Math.min(this.clock.getDelta(), 0.1);
		let busy = false;
		for (const fn of this.frameHooks) busy = fn(dt) || busy;
		if (this.controls.update()) busy = true;
		if (this.settings.autoRotate) busy = true;
		if (this.animatedTextures?.length && this.settings.textures) {
			this.texTime += dt;
			for (const tex of this.animatedTextures) {
				const frames = tex.userData.frames;
				const frame = Math.floor(this.texTime * 20 / (tex.userData.frametime || 1)) % frames;
				tex.offset.y = 1 - (frame + 1) / frames;
			}
			busy = true;
		}
		if (!busy && !this.needsRender) return;
		this.needsRender = false;
		if (this.selected) this.updateSelectionBox();
		this.render();
	}

	render() {
		if (this.hasGlow()) this.composer.render();
		else this.renderer.render(this.scene, this.camera);
	}

	screenshot() {
		const helpers = [this.grid, this.selectionBox, this.sunMarker, this.sunLine];
		const vis = helpers.map((h) => h.visible);
		helpers.forEach((h) => (h.visible = false));
		this.render();
		const url = this.renderer.domElement.toDataURL('image/png');
		helpers.forEach((h, i) => (h.visible = vis[i]));
		this.invalidate();
		return url;
	}
}

function niceStep(radius) {
	const raw = radius / 4;
	const p = Math.pow(10, Math.floor(Math.log10(raw)));
	return [1, 2, 5, 10].map((m) => m * p).find((s) => s >= raw) || p * 10;
}

function disposeTree(root) {
	const textures = new Set();
	root.traverse((o) => {
		if (o.geometry?.isBufferGeometry) o.geometry.dispose();
	});
	for (const m of collectMaterials(root)) {
		for (const key of ['map', 'emissiveMap', 'normalMap', 'roughnessMap', 'metalnessMap', 'alphaMap', 'aoMap']) if (m[key]?.isTexture) textures.add(m[key]);
		if (m.userData.origMap?.isTexture) textures.add(m.userData.origMap);
		m.dispose?.();
	}
	for (const t of textures) if (!t.userData?.missing) t.dispose();
}
