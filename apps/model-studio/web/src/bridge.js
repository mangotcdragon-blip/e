// Talks to the Android app (MainActivity's "AndroidBridge"); falls back to a
// normal browser download when running in a desktop browser.

export const isAndroid = () => typeof window.AndroidBridge !== 'undefined';

export async function saveFile(name, blob) {
	if (isAndroid()) {
		const bridge = window.AndroidBridge;
		const id = bridge.beginSave(name, blob.type || 'application/octet-stream');
		const CHUNK = 768 * 1024; // multiple of 3 so base64 chunks concatenate cleanly
		for (let off = 0; off < blob.size; off += CHUNK) {
			const bytes = new Uint8Array(await blob.slice(off, off + CHUNK).arrayBuffer());
			bridge.appendSave(id, toBase64(bytes));
		}
		const result = bridge.finishSave(id);
		if (result.startsWith('error:')) throw new Error(result.slice(6));
		return result;
	}
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = name;
	document.body.appendChild(a);
	a.click();
	a.remove();
	setTimeout(() => URL.revokeObjectURL(url), 10000);
	return `Downloaded ${name}`;
}

// Opens Android's share sheet for the file saved last.
export function shareLastSaved() {
	return isAndroid() && window.AndroidBridge.shareLast();
}

function toBase64(bytes) {
	let s = '';
	for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
	return btoa(s);
}

// Files sent to the app from other apps ("Open with Model Studio") are served by
// the Android side under /incoming/ and announced via window.App.openIncoming.
export async function fetchIncoming(list) {
	const files = [];
	for (const { url, name } of list) {
		const res = await fetch(url);
		if (!res.ok) throw new Error(`Could not read ${name}`);
		files.push(new File([await res.blob()], name));
	}
	return files;
}
