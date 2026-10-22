// Tiny DOM helpers and the reusable, touch-friendly controls used by every panel.

export function h(tag, props = {}, ...children) {
	const el = document.createElement(tag);
	for (const [k, v] of Object.entries(props || {})) {
		if (v === undefined || v === null || v === false) continue;
		if (k === 'class') el.className = v;
		else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
		else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
		else if (k === 'html') el.innerHTML = v;
		else if (v === true) el.setAttribute(k, '');
		else el.setAttribute(k, v);
	}
	append(el, children);
	return el;
}

function append(el, children) {
	for (const c of children.flat(Infinity)) {
		if (c === null || c === undefined || c === false) continue;
		el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
	}
}

export function section(title, ...children) {
	return h('section', { class: 'section' }, title ? h('h3', {}, title) : null, ...children);
}

export function hint(text) {
	return h('p', { class: 'hint' }, text);
}

export function button(label, onClick, { kind = '', icon = null, disabled = false, title } = {}) {
	return h('button', { class: `btn ${kind}`, onClick, disabled, title, type: 'button' }, icon ? h('span', { class: 'icon', html: icon }) : null, label ? h('span', {}, label) : null);
}

export function slider({ label, min, max, step = 0.01, value, format = (v) => String(round(v, step)), onInput, onChange }) {
	const out = h('span', { class: 'value' }, format(value));
	const input = h('input', { type: 'range', min, max, step, value });
	input.addEventListener('input', () => {
		const v = parseFloat(input.value);
		out.textContent = format(v);
		onInput?.(v);
	});
	input.addEventListener('change', () => onChange?.(parseFloat(input.value)));
	const row = h('label', { class: 'row slider' }, h('span', { class: 'label' }, label), out, input);
	row.set = (v) => {
		input.value = v;
		out.textContent = format(v);
	};
	return row;
}

export function toggle({ label, value, onChange, hint: sub }) {
	const input = h('input', { type: 'checkbox', checked: !!value });
	input.addEventListener('change', () => onChange(input.checked));
	return h('label', { class: 'row toggle' }, h('span', { class: 'label' }, label, sub ? h('small', {}, sub) : null), input, h('span', { class: 'switch' }));
}

export function chips({ options, value, onChange, scroll = false }) {
	const wrap = h('div', { class: 'chips' + (scroll ? ' scroll' : '') });
	for (const opt of options) {
		const o = typeof opt === 'object' ? opt : { value: opt, label: String(opt) };
		const el = h('button', { type: 'button', class: 'chip' + (o.value === value ? ' active' : ''), title: o.title }, o.swatch ? h('span', { class: 'dot', style: { background: o.swatch } }) : null, o.label);
		el.addEventListener('click', () => {
			wrap.querySelectorAll('.chip').forEach((c) => c.classList.remove('active'));
			el.classList.add('active');
			onChange(o.value);
		});
		wrap.appendChild(el);
	}
	return wrap;
}

export function colorRow({ label, value, swatches, onChange }) {
	const picker = h('input', { type: 'color', value: toHex(value) });
	picker.addEventListener('input', () => onChange(picker.value));
	const sw = h('div', { class: 'swatches' });
	for (const c of swatches) {
		const b = h('button', { type: 'button', class: 'swatch', style: { background: c }, title: c });
		b.addEventListener('click', () => {
			picker.value = c;
			onChange(c);
		});
		sw.appendChild(b);
	}
	return h('div', { class: 'row color' }, h('span', { class: 'label' }, label), h('div', { class: 'color-controls' }, sw, h('label', { class: 'picker', title: 'Pick any colour' }, picker)));
}

export function select({ label, options, value, onChange }) {
	const s = h('select', {});
	for (const o of options) {
		const opt = h('option', { value: o.value }, o.label);
		if (o.value === value) opt.selected = true;
		s.appendChild(opt);
	}
	s.addEventListener('change', () => onChange(s.value));
	return h('label', { class: 'row select' }, label ? h('span', { class: 'label' }, label) : null, s);
}

export function numberRow({ label, value, min, max, step = 0.1, onChange, suffix = '' }) {
	const input = h('input', { type: 'number', value, min, max, step, inputmode: 'decimal' });
	input.addEventListener('change', () => {
		const v = parseFloat(input.value);
		if (Number.isFinite(v)) onChange(v);
	});
	return h('label', { class: 'row number' }, h('span', { class: 'label' }, label), h('span', { class: 'num-wrap' }, input, suffix ? h('span', { class: 'suffix' }, suffix) : null));
}

export function round(v, step = 0.01) {
	const d = Math.max(0, Math.min(4, Math.ceil(-Math.log10(step))));
	return Number(v).toFixed(d).replace(/\.0+$|(\.\d*?)0+$/, '$1');
}

function toHex(c) {
	return /^#[0-9a-f]{6}$/i.test(c) ? c : '#ffffff';
}

export function prompt(message, value = '') {
	return new Promise((resolve) => {
		const input = h('input', { type: 'text', value });
		const close = (v) => {
			overlay.remove();
			resolve(v);
		};
		const overlay = h(
			'div',
			{ class: 'dialog-overlay' },
			h('form', { class: 'dialog', onSubmit: (e) => (e.preventDefault(), close(input.value.trim() || null)) },
				h('p', {}, message),
				input,
				h('div', { class: 'dialog-buttons' }, button('Cancel', () => close(null)), h('button', { class: 'btn primary', type: 'submit' }, 'OK')),
			),
		);
		document.body.appendChild(overlay);
		setTimeout(() => input.focus(), 50);
	});
}

export function confirm(message, okLabel = 'OK') {
	return new Promise((resolve) => {
		const close = (v) => {
			overlay.remove();
			resolve(v);
		};
		const overlay = h('div', { class: 'dialog-overlay' }, h('div', { class: 'dialog' }, h('p', {}, message), h('div', { class: 'dialog-buttons' }, button('Cancel', () => close(false)), button(okLabel, () => close(true), { kind: 'primary' }))));
		document.body.appendChild(overlay);
	});
}

export const ICONS = {
	open: '<svg viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>',
	material: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><circle cx="8.5" cy="10" r="1.4" class="fill"/><circle cx="12" cy="7.5" r="1.4" class="fill"/><circle cx="15.5" cy="10" r="1.4" class="fill"/><path d="M12 21a2.5 2.5 0 0 1 0-5h2a3 3 0 0 0 3-3"/></svg>',
	light: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>',
	animate: '<svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M10 9l5 3-5 3z" class="fill"/></svg>',
	save: '<svg viewBox="0 0 24 24"><path d="M12 3v12M7 10l5 5 5-5M4 19h16"/></svg>',
	view: '<svg viewBox="0 0 24 24"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>',
	play: '<svg viewBox="0 0 24 24"><path d="M7 4l13 8-13 8z" class="fill"/></svg>',
	pause: '<svg viewBox="0 0 24 24"><rect x="6" y="4" width="4" height="16" class="fill"/><rect x="14" y="4" width="4" height="16" class="fill"/></svg>',
	reset: '<svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 1 0 2.3-5.7M4 4v4h4"/></svg>',
	close: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
	plus: '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
	key: '<svg viewBox="0 0 24 24"><path d="M12 3l7 9-7 9-7-9z" class="fill"/></svg>',
	trash: '<svg viewBox="0 0 24 24"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/></svg>',
	camera: '<svg viewBox="0 0 24 24"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>',
	share: '<svg viewBox="0 0 24 24"><circle cx="18" cy="5" r="2.5"/><circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="19" r="2.5"/><path d="M8.2 10.8l7.6-4.6M8.2 13.2l7.6 4.6"/></svg>',
	cube: '<svg viewBox="0 0 24 24"><path d="M12 2l9 5v10l-9 5-9-5V7z"/><path d="M3 7l9 5 9-5M12 12v10"/></svg>',
	prev: '<svg viewBox="0 0 24 24"><path d="M18 5L8 12l10 7zM6 5v14" class="fill"/></svg>',
	next: '<svg viewBox="0 0 24 24"><path d="M6 5l10 7-10 7zM18 5v14" class="fill"/></svg>',
};
