// Material editing helpers used by the Materials panel.
import * as THREE from 'three';

export const GLOW_PRESETS = [
	{ label: 'None', strength: 0 },
	{ label: 'Soft', strength: 1 },
	{ label: 'Bright', strength: 2 },
	{ label: 'Neon', strength: 4 },
];

export const COLOR_SWATCHES = ['#ffffff', '#ff4d4d', '#ff9f1a', '#ffe14d', '#5ce65c', '#33d6ff', '#4d7dff', '#b84dff', '#ff66cc', '#222222'];

const SNAPSHOT_KEYS = ['color', 'metalness', 'roughness', 'opacity', 'transparent', 'alphaTest', 'side', 'flatShading', 'depthWrite'];

export function snapshot(m) {
	if (m.userData.original) return;
	const s = {};
	for (const k of SNAPSHOT_KEYS) s[k] = m[k]?.isColor ? m[k].getHex() : m[k];
	s.map = m.userData.origMap ?? m.map;
	s.glow = m.userData.glow ? { ...m.userData.glow } : null;
	m.userData.original = s;
}

export function resetMaterial(m, viewer) {
	const s = m.userData.original;
	if (!s) return;
	for (const k of SNAPSHOT_KEYS) {
		if (m[k]?.isColor) m[k].setHex(s[k]);
		else m[k] = s[k];
	}
	m.userData.glow = s.glow ? { ...s.glow } : null;
	m.userData.wireframe = false;
	viewer.setMaterialMap(m, s.map);
	m.needsUpdate = true;
}

// Glow lives in material.userData.glow = { strength, color, useTexture, map }.
// The viewer draws it as a halo (selective bloom) so the surface keeps its real
// colours; exporters turn it into emissive values (see exportGlow).
export function getGlow(m) {
	const g = m?.userData.glow;
	return g && g.strength > 0 ? g : null;
}

export function glowStrength(m) {
	return getGlow(m)?.strength || 0;
}

export function glowColor(m) {
	return m.userData.glow?.color || '#ffffff';
}

export function setGlow(m, { strength, color, useTexture }) {
	const g = (m.userData.glow ||= { strength: 0, color: '#ffffff', useTexture: false, map: null });
	if (strength !== undefined) {
		// Textured materials glow in their own texture colours unless a colour is chosen
		if (strength > 0 && !g.strength && color === undefined && useTexture === undefined && !g.colorChosen) g.useTexture = !!(m.userData.origMap ?? m.map);
		g.strength = strength;
	}
	if (color !== undefined) {
		g.color = color;
		g.colorChosen = true;
		g.useTexture = false;
	}
	if (useTexture !== undefined) g.useTexture = useTexture;
}

// The texture the glow takes its colours from, if any.
export function glowMap(m) {
	const g = getGlow(m);
	if (!g) return null;
	if (g.useTexture) return m.userData.origMap ?? m.map;
	return g.map || null;
}

// Puts glow into the standard emissive slots for exporters; returns an undo function.
export function exportGlow(materials) {
	const saved = materials.map((m) => [m, m.emissive.clone(), m.emissiveIntensity, m.emissiveMap]);
	for (const m of materials) {
		const g = getGlow(m);
		if (!g) continue;
		const map = glowMap(m);
		m.emissive.set(g.useTexture ? 0xffffff : g.color);
		m.emissiveMap = map;
		m.emissiveIntensity = g.strength;
		m.needsUpdate = true;
	}
	return () => {
		for (const [m, e, i, map] of saved) {
			m.emissive.copy(e);
			m.emissiveIntensity = i;
			m.emissiveMap = map;
			m.needsUpdate = true;
		}
	};
}

// Turn emissive settings that came in with a file into glow.
export function emissiveToGlow(m) {
	const e = m.emissive;
	if (!e || (!m.emissiveMap && e.r + e.g + e.b === 0) || m.emissiveIntensity <= 0) return;
	const useTexture = !!m.emissiveMap && m.emissiveMap === m.map;
	m.userData.glow = {
		strength: m.emissiveIntensity,
		color: '#' + (e.r + e.g + e.b > 0 ? e.getHexString() : 'ffffff'),
		useTexture,
		map: useTexture ? null : m.emissiveMap || null,
		colorChosen: true,
	};
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
