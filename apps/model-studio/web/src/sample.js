// The built-in "Try a sample" robot: box bones, a pixel texture, a glowing lamp and two animations.
import * as THREE from 'three';
import { buildBoneModel } from './loaders/bedrock.js';
import { pixelTexture, blockMaterial, FACES } from './loaders/cuboid.js';

function texture() {
	const c = document.createElement('canvas');
	c.width = c.height = 32;
	const g = c.getContext('2d');
	const cell = (x, y, color) => {
		g.fillStyle = color;
		g.fillRect(x, y, 8, 8);
	};
	cell(0, 0, '#9aa5b1');
	cell(8, 0, '#4a5560');
	cell(16, 0, '#3d7eff');
	cell(24, 0, '#f2c14e');
	// face
	cell(0, 8, '#b7c2cc');
	g.fillStyle = '#20e3ff';
	g.fillRect(1, 10, 2, 2);
	g.fillRect(5, 10, 2, 2);
	g.fillStyle = '#4a5560';
	g.fillRect(2, 13, 4, 1);
	// panel
	cell(8, 8, '#9aa5b1');
	g.fillStyle = '#3d7eff';
	g.fillRect(10, 10, 4, 3);
	g.fillStyle = '#f2c14e';
	g.fillRect(10, 14, 1, 1);
	g.fillRect(12, 14, 1, 1);
	return pixelTexture(c, { name: 'robot', frames: 1 });
}

const R = { grey: [0, 0, 8, 8], dark: [8, 0, 16, 8], blue: [16, 0, 24, 8], yellow: [24, 0, 32, 8], face: [0, 8, 8, 16], panel: [8, 8, 16, 16] };
const all = (rect, material = 0, extra = {}) => Object.fromEntries(FACES.map((d) => [d, { uv: extra[d] || rect, material }]));

export function sampleRobot() {
	const tex = texture();
	const body = blockMaterial(tex, 'Robot');
	body.vertexColors = true;
	const lamp = new THREE.MeshStandardMaterial({ name: 'Lamp', color: 0xff3b3b, roughness: 0.4, vertexColors: true });
	lamp.userData.glow = { strength: 3, color: '#ff2020', useTexture: false, map: null, colorChosen: true };
	const bones = [
		{ name: 'body', parent: null, pivot: [0, 12, 0], rotation: [0, 0, 0], cubes: [{ from: [-4, 12, -2], to: [4, 24, 2], faces: all(R.grey, 0, { south: R.panel }) }] },
		{ name: 'head', parent: 'body', pivot: [0, 24, 0], rotation: [0, 0, 0], cubes: [{ from: [-4, 24, -4], to: [4, 32, 4], faces: all(R.grey, 0, { south: R.face }) }] },
		{ name: 'antenna', parent: 'head', pivot: [0, 32, 0], rotation: [0, 0, 0], cubes: [{ from: [-0.5, 32, -0.5], to: [0.5, 36, 0.5], faces: all(R.dark) }] },
		{ name: 'lamp', parent: 'antenna', pivot: [0, 37, 0], rotation: [0, 0, 0], cubes: [{ from: [-1, 36, -1], to: [1, 38, 1], faces: all([0, 0, 1, 1], 1) }] },
		{ name: 'arm_left', parent: 'body', pivot: [5.5, 23, 0], rotation: [0, 0, 0], cubes: [{ from: [4, 13, -1.5], to: [7, 24, 1.5], faces: all(R.blue) }] },
		{ name: 'arm_right', parent: 'body', pivot: [-5.5, 23, 0], rotation: [0, 0, 0], cubes: [{ from: [-7, 13, -1.5], to: [-4, 24, 1.5], faces: all(R.blue) }] },
		{ name: 'leg_left', parent: null, pivot: [2, 12, 0], rotation: [0, 0, 0], cubes: [{ from: [0.5, 0, -1.5], to: [3.5, 12, 1.5], faces: all(R.dark) }] },
		{ name: 'leg_right', parent: null, pivot: [-2, 12, 0], rotation: [0, 0, 0], cubes: [{ from: [-3.5, 0, -1.5], to: [-0.5, 12, 1.5], faces: all(R.dark) }] },
	];
	const root = buildBoneModel(bones, [body, lamp], 32, 32, 'robot');
	root.userData.source = 'sample';
	root.userData.geo = { id: 'geometry.robot', tw: 32, th: 32 };
	root.userData.warnings = [];
	return root;
}

export function sampleClips() {
	const swing = (bone, amount) => ({
		bone,
		channel: 'rotation',
		keys: [
			{ t: 0, v: [amount, 0, 0], i: 'smooth' },
			{ t: 0.5, v: [-amount, 0, 0], i: 'smooth' },
			{ t: 1, v: [amount, 0, 0], i: 'smooth' },
		],
	});
	return [
		{
			name: 'walk',
			length: 1,
			fps: 24,
			loop: 'loop',
			tracks: [
				swing('leg_left', 30),
				swing('leg_right', -30),
				swing('arm_left', -25),
				swing('arm_right', 25),
				{ bone: 'body', channel: 'position', keys: [{ t: 0, v: [0, 0, 0], i: 'smooth' }, { t: 0.25, v: [0, 0.6, 0], i: 'smooth' }, { t: 0.5, v: [0, 0, 0], i: 'smooth' }, { t: 0.75, v: [0, 0.6, 0], i: 'smooth' }, { t: 1, v: [0, 0, 0], i: 'smooth' }] },
			],
		},
		{
			name: 'wave',
			length: 1.5,
			fps: 24,
			loop: 'loop',
			tracks: [
				{ bone: 'arm_right', channel: 'rotation', keys: [{ t: 0, v: [0, 0, -150], i: 'smooth' }, { t: 0.375, v: [0, 0, -120], i: 'smooth' }, { t: 0.75, v: [0, 0, -150], i: 'smooth' }, { t: 1.125, v: [0, 0, -120], i: 'smooth' }, { t: 1.5, v: [0, 0, -150], i: 'smooth' }] },
				{ bone: 'head', channel: 'rotation', keys: [{ t: 0, v: [0, -10, 5], i: 'smooth' }, { t: 0.75, v: [0, 10, -5], i: 'smooth' }, { t: 1.5, v: [0, -10, 5], i: 'smooth' }] },
				{ bone: 'lamp', channel: 'scale', keys: [{ t: 0, v: [1, 1, 1], i: 'smooth' }, { t: 0.75, v: [1.4, 1.4, 1.4], i: 'smooth' }, { t: 1.5, v: [1, 1, 1], i: 'smooth' }] },
			],
		},
	];
}
