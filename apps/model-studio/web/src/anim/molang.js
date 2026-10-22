// A small Molang evaluator, enough to bake the expressions commonly found in
// Bedrock / GeckoLib animations (math.sin(query.anim_time * 90) * 10 etc.)
// into keyframes. Unknown variables evaluate to 0.

const FUNCS = {
	sin: (x) => Math.sin((x * Math.PI) / 180),
	cos: (x) => Math.cos((x * Math.PI) / 180),
	asin: (x) => (Math.asin(x) * 180) / Math.PI,
	acos: (x) => (Math.acos(x) * 180) / Math.PI,
	atan: (x) => (Math.atan(x) * 180) / Math.PI,
	atan2: (y, x) => (Math.atan2(y, x) * 180) / Math.PI,
	abs: Math.abs,
	sqrt: Math.sqrt,
	floor: Math.floor,
	ceil: Math.ceil,
	round: Math.round,
	trunc: Math.trunc,
	exp: Math.exp,
	ln: Math.log,
	pow: Math.pow,
	min: Math.min,
	max: Math.max,
	mod: (a, b) => a % b,
	clamp: (v, lo, hi) => Math.min(Math.max(v, lo), hi),
	lerp: (a, b, t) => a + (b - a) * t,
	lerprotate: (a, b, t) => a + ((((b - a) % 360) + 540) % 360 - 180) * t,
	hermite_blend: (t) => 3 * t * t - 2 * t * t * t,
	random: (a, b) => a + Math.random() * (b - a),
	sign: Math.sign,
};

export function compileMolang(src) {
	const tokens = tokenize(String(src).toLowerCase());
	let pos = 0;
	const peek = () => tokens[pos];
	const next = () => tokens[pos++];
	const expect = (v) => {
		if (next()?.v !== v) throw new Error('Molang: expected ' + v);
	};

	const prec = { '||': 1, '&&': 2, '==': 3, '!=': 3, '<': 4, '>': 4, '<=': 4, '>=': 4, '+': 5, '-': 5, '*': 6, '/': 6 };
	function parseExpr(min = 0) {
		let left = parseUnary();
		for (;;) {
			const t = peek();
			if (t?.v === '?' && min <= 0) {
				next();
				const a = parseExpr(0);
				if (peek()?.v === ':') {
					next();
					const b = parseExpr(0);
					const c = left;
					left = (env) => (c(env) ? a(env) : b(env));
				} else {
					const c = left;
					left = (env) => (c(env) ? a(env) : 0);
				}
				continue;
			}
			if (t?.v === '??') {
				next();
				const b = parseExpr(1);
				const a = left;
				left = (env) => a(env) ?? b(env);
				continue;
			}
			const p = t && t.type === 'op' ? prec[t.v] : undefined;
			if (p === undefined || p < min) return left;
			next();
			const right = parseExpr(p + 1);
			const l = left, op = t.v;
			left = (env) => binary(op, l(env), right(env));
		}
	}
	function parseUnary() {
		const t = peek();
		if (t?.v === '-') {
			next();
			const e = parseUnary();
			return (env) => -e(env);
		}
		if (t?.v === '!') {
			next();
			const e = parseUnary();
			return (env) => (e(env) ? 0 : 1);
		}
		return parsePrimary();
	}
	function parsePrimary() {
		const t = next();
		if (!t) throw new Error('Molang: unexpected end');
		if (t.type === 'num') return () => t.v;
		if (t.v === '(') {
			const e = parseExpr(0);
			expect(')');
			return e;
		}
		if (t.type === 'id') {
			const name = t.v;
			if (peek()?.v === '(') {
				next();
				const args = [];
				while (peek() && peek().v !== ')') {
					args.push(parseExpr(0));
					if (peek()?.v === ',') next();
				}
				expect(')');
				const fn = FUNCS[name.replace(/^math\./, '')];
				return (env) => (fn ? fn(...args.map((a) => a(env))) : 0);
			}
			if (name === 'math.pi') return () => Math.PI;
			if (name === 'true') return () => 1;
			if (name === 'false') return () => 0;
			const key = name.replace(/^q\./, 'query.').replace(/^v\./, 'variable.');
			return (env) => env[key] ?? 0;
		}
		throw new Error('Molang: unexpected ' + t.v);
	}
	const fn = parseExpr(0);
	if (pos < tokens.length && tokens[pos].v !== ';') throw new Error('Molang: unexpected ' + tokens[pos].v);
	return fn;
}

function binary(op, a, b) {
	switch (op) {
		case '+': return a + b;
		case '-': return a - b;
		case '*': return a * b;
		case '/': return b === 0 ? 0 : a / b;
		case '<': return +(a < b);
		case '>': return +(a > b);
		case '<=': return +(a <= b);
		case '>=': return +(a >= b);
		case '==': return +(a === b);
		case '!=': return +(a !== b);
		case '&&': return +(a && b);
		case '||': return +(a || b);
	}
	return 0;
}

function tokenize(s) {
	const out = [];
	let i = 0;
	while (i < s.length) {
		const c = s[i];
		if (/\s/.test(c)) { i++; continue; }
		const num = /^(\d+\.?\d*|\.\d+)f?/.exec(s.slice(i));
		if (num) { out.push({ type: 'num', v: parseFloat(num[1]) }); i += num[0].length; continue; }
		const id = /^[a-z_][a-z0-9_.]*/.exec(s.slice(i));
		if (id) { out.push({ type: 'id', v: id[0] }); i += id[0].length; continue; }
		const two = s.slice(i, i + 2);
		if (['<=', '>=', '==', '!=', '&&', '||', '??'].includes(two)) { out.push({ type: 'op', v: two }); i += 2; continue; }
		out.push({ type: '+-*/<>'.includes(c) ? 'op' : 'punct', v: c });
		i++;
	}
	return out;
}

export function molangValue(v) {
	if (typeof v === 'number') return { constant: true, value: v };
	const s = String(v).trim();
	if (s === '') return { constant: true, value: 0 };
	if (/^-?(\d+\.?\d*|\.\d+)$/.test(s)) return { constant: true, value: parseFloat(s) };
	try {
		const fn = compileMolang(s);
		return { constant: false, fn };
	} catch {
		return { constant: true, value: 0, failed: true };
	}
}
