// Material editing helpers used by the Materials panel.
import * as THREE from 'three';

export const GLOW_PRESETS = [
	{ label: 'None', strength: 0 },
	{ label: 'Soft', strength: 1 },
	{ label: 'Bright', strength: 2 },
	{ label: 'Neon', strength: 4 },
];

export const COLOR_SWATCHES = ['#ffffff', '#ff4d4d', '#ff9f1a', '#ffe14d', '#5ce65c', '#33d6ff', '#4d7dff', '#b84dff', '#ff66cc', '#222222'];

const SNAPSHOT_KEYS = ['color', 'emissive', 'emissiveIntensity', 'metalness', 'roughness', 'opacity', 'transparent', 'alphaTest', 'side', 'flatShading', 'depthWrite'];

export function snapshot(m) {
	if (m.userData.original) return;
	const s = {};
	for (const k of SNAPSHOT_KEYS) s[k] = m[k]?.isColor ? m[k].getHex() : m[k];
	s.map = m.userData.origMap ?? m.map;
	s.emissiveMap = m.emissiveMap;
	m.userData.original = s;
}

export function resetMaterial(m, viewer) {
	const s = m.userData.original;
	if (!s) return;
	for (const k of SNAPSHOT_KEYS) {
		if (m[k]?.isColor) m[k].setHex(s[k]);
		else m[k] = s[k];
	}
	m.userData.glowUsesTexture = false;
	m.userData.wireframe = false;
	m.emissiveMap = s.emissiveMap;
	viewer.setMaterialMap(m, s.map);
	m.needsUpdate = true;
}

export function glowStrength(m) {
	const e = m.emissive;
	return e.r + e.g + e.b > 0 ? m.emissiveIntensity : 0;
}

export function glowColor(m) {
	const e = m.emissive;
	return e.r + e.g + e.b > 0 ? '#' + e.getHexString() : '#' + (m.color?.getHexString() || 'ffffff');
}

// Glow = emissive colour x strength. Values above 1 bloom (the view only blooms what is brighter than white).
export function setGlow(m, { strength, color, useTexture }) {
	if (color !== undefined) m.emissive.set(color);
	if (strength !== undefined) {
		if (strength > 0 && m.emissive.r + m.emissive.g + m.emissive.b === 0) {
			// Textured materials glow in their own texture colours unless told otherwise
			if (m.map && useTexture === undefined && color === undefined) useTexture = true;
			m.emissive.set(m.userData.glowUsesTexture || useTexture ? 0xffffff : m.color);
		}
		m.emissiveIntensity = strength;
		if (strength === 0) m.emissive.set(0x000000);
	}
	if (useTexture !== undefined) {
		m.userData.glowUsesTexture = useTexture;
		m.emissiveMap = useTexture ? m.map : m.userData.original?.emissiveMap ?? null;
		if (useTexture && m.emissiveIntensity > 0) m.emissive.set(0xffffff);
	}
	m.needsUpdate = true;
}

export function setOpacity(m, opacity) {
	m.opacity = opacity;
	const wasTransparent = m.userData.original?.transparent;
	m.transparent = opacity < 1 || !!wasTransparent;
	m.depthWrite = opacity >= 1 ? m.userData.original?.depthWrite ?? true : false;
	m.needsUpdate = true;
}

export function textureFromImage(img, name, pixelated) {
	const tex = new THREE.Texture(img);
	tex.name = name;
	tex.colorSpace = THREE.SRGBColorSpace;
	tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
	if (pixelated) {
		tex.magFilter = THREE.NearestFilter;
		tex.minFilter = THREE.NearestFilter;
		tex.generateMipmaps = false;
	}
	tex.needsUpdate = true;
	return tex;
}
