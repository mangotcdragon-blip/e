/**
 * Rule-based Modern English -> Early Modern English (Shakespeare / King James
 * era) rewriter. No network, no dictionary download: a fixed set of ordered
 * rewrite rules applied to the plain-text parts of a message.
 */

export interface TranslateOptions {
    /** Conjugate ordinary verbs: "thou knowest", "he knoweth". */
    verbs: boolean;
    /** Rewrite chat slang and abbreviations (lol, idk, u, ...). */
    slang: boolean;
}

export const DEFAULT_OPTIONS: TranslateOptions = { verbs: true, slang: true };

type Ctx = { before: string; after: string; atStart: boolean };
type Replacement = string | ((core: string, ctx: Ctx) => string | null);
type Rule = { re: RegExp; to: Replacement };

const WORD_CHAR = "A-Za-z0-9_'’";

/**
 * Builds a rule. In `pattern`, a space matches any run of whitespace, `'`
 * matches a straight or curly apostrophe and `'?` makes it optional (so
 * "dont" is caught as well as "don't"). The match must stand as whole words.
 */
function rule(pattern: string, to: Replacement): Rule {
    const body = pattern
        .replace(/'\?/g, "\u0000")
        .replace(/'/g, "['’]")
        .replace(/\u0000/g, "['’]?")
        .replace(/ /g, "\\s+");
    return { re: new RegExp(`(^|[^${WORD_CHAR}])(${body})(?![${WORD_CHAR}])`, "gi"), to };
}

function rules(map: Record<string, Replacement>): Rule[] {
    return Object.keys(map).map((k) => rule(k, map[k]));
}

function isAllCaps(s: string): boolean {
    const letters = s.replace(/[^A-Za-z]/g, "");
    return letters.length > 1 && letters === letters.toUpperCase();
}

/** Copies the capitalisation of `src` onto `out`. */
function matchCase(src: string, out: string, atStart: boolean): string {
    if (!out) return out;
    if (isAllCaps(src)) return out.toUpperCase();
    // "I" is always capitalised, so it says nothing about the sentence start.
    const startsWithI = /^I(?![A-Za-z])/.test(src);
    const cap = startsWithI ? atStart : src[0] !== src[0].toLowerCase();
    const i = out.search(/[A-Za-z]/);
    if (i < 0) return out;
    return cap
        ? out.slice(0, i) + out[i].toUpperCase() + out.slice(i + 1)
        : /^I(?![A-Za-z])/.test(out.slice(i))
          ? out
          : out.slice(0, i) + out[i].toLowerCase() + out.slice(i + 1);
}

function applyRules(text: string, list: Rule[]): string {
    for (const { re, to } of list) {
        text = text.replace(re, (whole: string, lead: string, core: string, offset: number, str: string) => {
            const start = offset + lead.length;
            const before = str.slice(0, start);
            const after = str.slice(start + core.length);
            const atStart = /(^|[.!?\n]\s*)$/.test(before);
            const out = typeof to === "function" ? to(core, { before, after, atStart }) : to;
            if (out === null || out === undefined) return whole;
            return lead + (typeof to === "function" ? out : matchCase(core, out, atStart));
        });
    }
    return text;
}

// --- word tables -----------------------------------------------------------

/** base form -> [second person "thou" form, third person "-eth" form] */
const VERBS: Record<string, [string, string]> = {
    know: ["knowest", "knoweth"],
    think: ["thinkest", "thinketh"],
    want: ["wantest", "wanteth"],
    like: ["likest", "liketh"],
    love: ["lovest", "loveth"],
    need: ["needest", "needeth"],
    see: ["seest", "seeth"],
    say: ["sayest", "saith"],
    go: ["goest", "goeth"],
    mean: ["meanest", "meaneth"],
    feel: ["feelest", "feeleth"],
    come: ["comest", "cometh"],
    make: ["makest", "maketh"],
    take: ["takest", "taketh"],
    speak: ["speakest", "speaketh"],
    hear: ["hearest", "heareth"],
    look: ["lookest", "looketh"],
    seem: ["seemest", "seemeth"],
    get: ["gettest", "getteth"],
    play: ["playest", "playeth"],
    believe: ["believest", "believeth"],
    understand: ["understandest", "understandeth"],
    remember: ["rememberest", "remembereth"],
    forget: ["forgettest", "forgetteth"],
    hate: ["hatest", "hateth"],
    wish: ["wishest", "wisheth"],
    hope: ["hopest", "hopeth"],
    give: ["givest", "giveth"],
    tell: ["tellest", "telleth"],
    find: ["findest", "findeth"],
    keep: ["keepest", "keepeth"],
    ask: ["askest", "asketh"],
    try: ["triest", "trieth"],
    work: ["workest", "worketh"],
    live: ["livest", "liveth"],
    care: ["carest", "careth"],
    mind: ["mindest", "mindeth"],
    fear: ["fearest", "feareth"],
    doubt: ["doubtest", "doubteth"],
    sound: ["soundest", "soundeth"],
    eat: ["eatest", "eateth"],
    sleep: ["sleepest", "sleepeth"],
};

/** Auxiliaries after "thou". Always applied, they carry most of the flavour. */
const THOU_AUX: Record<string, string> = {
    are: "art",
    were: "wert",
    was: "wast",
    have: "hast",
    had: "hadst",
    do: "dost",
    did: "didst",
    can: "canst",
    could: "couldst",
    will: "wilt",
    would: "wouldst",
    shall: "shalt",
    should: "shouldst",
    may: "mayst",
    might: "mightst",
    am: "art",
    is: "art",
};

/** Words after "you" that show it is the subject: "you did", "you went". */
const SUBJECT_NEXT = new Set(
    "are were was did can could will would shall should may might had must said got went made saw knew thought told gave took came left ran ate forgot lost won bought sent".split(
        " ",
    ),
);

/** "dost thou", "canst thou": the verb after "thou" stays in its plain form. */
const THOU_AUX_FORMS = new Set(Object.keys(THOU_AUX).map((k) => THOU_AUX[k]));

const CONJUNCTIONS = new Set(
    "and but or nor if when whenever because so that though although while whilst as until unless since where what how why who which then yet once lest".split(
        " ",
    ),
);

/** Words ending in "s" after he/she/it that are not verbs. */
const NOT_VERBS = new Set(
    "is was has his its us as yes this thus plus less unless always sometimes perhaps besides towards afterwards nevertheless whereas across various previous serious famous obvious anxious nervous jealous curious dangerous gorgeous ridiculous delicious enormous".split(
        " ",
    ),
);

function thirdPerson(word: string): string | null {
    const lower = word.toLowerCase();
    if (lower.length < 3 || !lower.endsWith("s") || lower.endsWith("ss") || lower.endsWith("us")) return null;
    if (NOT_VERBS.has(lower) || lower.endsWith("ous") || lower.endsWith("ics")) return null;
    const base = lower.slice(0, -1);
    if (VERBS[base]) return VERBS[base][1];
    let stem: string;
    if (lower.endsWith("ies")) stem = lower.slice(0, -3) + "i";
    else if (/(ch|sh|x|z|o)es$/.test(lower)) stem = lower.slice(0, -2);
    else stem = base.endsWith("e") ? base.slice(0, -1) : base;
    return stem + "eth";
}

// --- rule sets ---------------------------------------------------------------

const SLANG = rules({
    lol: "'tis most droll",
    lmao: "I laugh most heartily",
    lmfao: "I laugh most heartily",
    rofl: "I roll upon the floor with mirth",
    omg: "good heavens",
    "oh my god": "good heavens",
    wtf: "what devilry is this",
    brb: "I shall return anon",
    idk: "I know not",
    idc: "I care not",
    tbh: "in faith",
    btw: "by the by",
    ngl: "in truth",
    imo: "methinks",
    imho: "methinks",
    gg: "well fought",
    rn: "anon",
    pls: "prithee",
    plz: "prithee",
    ty: "gramercy",
    thx: "gramercy",
    tysm: "a thousand thanks",
    np: "'tis naught",
    nvm: "think naught of it",
    smh: "I shake my head",
    ikr: "verily",
    fr: "in sooth",
    wyd: "what dost thou",
    hbu: "and thou?",
    gn: "good night",
    gm: "good morrow",
    irl: "in this mortal realm",
    afk: "away awhile",
    u: "you",
    ur: "your",
    bro: "good sir",
    bruh: "good sir",
    dude: "good sir",
    guys: "good people",
    awesome: "wondrous",
    amazing: "wondrous",
    cool: "most excellent",
    stupid: "witless",
    dumb: "witless",
    idiot: "fool",
    crazy: "mad",
    money: "coin",
    mom: "mother",
    mum: "mother",
    dad: "father",
    kinda: "somewhat",
    gonna: "going to",
    wanna: "want to",
    gotta: "must",
});

const CONTRACTIONS = rules({
    // Unambiguous even without the apostrophe.
    "i'?m": "I am",
    "you'?re": "you are",
    "you'?ve": "you have",
    "you'?ll": "you will",
    "you'?d": "you would",
    "that'?s": "that is",
    "what'?s": "what is",
    "there'?s": "there is",
    "who'?s": "who is",
    "where'?s": "where is",
    "how'?s": "how is",
    "i'?ve": "I have",
    "they'?re": "they are",
    "they'?ve": "they have",
    "they'?ll": "they will",
    "we'?ve": "we have",
    "isn'?t": "is not",
    "aren'?t": "are not",
    "wasn'?t": "was not",
    "weren'?t": "were not",
    "don'?t": "do not",
    "doesn'?t": "does not",
    "didn'?t": "did not",
    "can'?t": "cannot",
    "couldn'?t": "could not",
    "wouldn'?t": "would not",
    "shouldn'?t": "should not",
    "won'?t": "will not",
    "haven'?t": "have not",
    "hasn'?t": "has not",
    "hadn'?t": "had not",
    "mustn'?t": "must not",
    "ain'?t": "is not",
    // These spell real words without the apostrophe (its, ill, well, ...).
    "it's": "it is",
    "it'll": "it will",
    "i'll": "I shall",
    "i'd": "I would",
    "we'll": "we shall",
    "we're": "we are",
    "let's": "let us",
    "he's": "he is",
    "she's": "she is",
    "here's": "here is",
    "y'all": "ye",
});

const IDIOMS = rules({
    "see you later": "see thee anon",
    "see ya": "see thee anon",
    goodbye: "fare thee well",
    "good bye": "fare thee well",
    bye: "fare thee well",
    "good morning": "good morrow",
    "come here": "come hither",
    "go away": "begone",
    "you guys": "ye",
    "you all": "ye",
    "i think": "methinks",
    "it is": "'tis",
    "it was": "'twas",
    "it will": "'twill",
    "it would": "'twould",
    "is it": "is't",
    "a lot": "a great deal",
    "how are you": "how dost thou fare",
    "how is it going": "how goes it",
});

/** "I do not know" -> "I know not", "he does not care" -> "he careth not". */
const DO_NOT: Rule[] = [
    rule("do not \\w+", (core) => {
        const verb = core.split(/\s+/)[2];
        if (!VERBS[verb.toLowerCase()]) return null;
        return `${keepCase(core, verb)} not`;
    }),
    rule("does not \\w+", (core) => {
        const verb = core.split(/\s+/)[2];
        const forms = VERBS[verb.toLowerCase()];
        if (!forms) return null;
        return `${keepCase(core, forms[1])} not`;
    }),
];

/** Uses the case of the first word of `core` for `word`. */
function keepCase(core: string, word: string): string {
    if (isAllCaps(core)) return word.toUpperCase();
    return core[0] !== core[0].toLowerCase() ? word[0].toUpperCase() + word.slice(1) : word.toLowerCase();
}

/** "are you" -> "art thou", "do you" -> "dost thou", ... */
const INVERSIONS: Rule[] = Object.keys(THOU_AUX)
    .filter((aux) => aux !== "am" && aux !== "is")
    .map((aux) => rule(`${aux} you`, `${THOU_AUX[aux]} thou`));

const POSSESSIVES = rules({
    yourself: "thyself",
    yourselves: "yourselves",
    yours: "thine",
    your: (core, { after, atStart }) =>
        matchCase(core, /^\s+[aeiouAEIOU]/.test(after) ? "thine" : "thy", atStart),
});

const YOU: Rule[] = [
    rule("you", (core, { before, after, atStart }) => {
        const prev = /([A-Za-z]+)[\s,]*$/.exec(before)?.[1]?.toLowerCase();
        const next = /^\s+([A-Za-z]+)/.exec(after)?.[1]?.toLowerCase();
        const subject =
            atStart ||
            /[,;:(]\s*$/.test(before) ||
            (prev !== undefined && CONJUNCTIONS.has(prev)) ||
            (next !== undefined && (SUBJECT_NEXT.has(next) || /[a-z]ed$/.test(next)));
        return matchCase(core, subject && (next !== undefined || atStart) ? "thou" : "thee", atStart);
    }),
];

function thouVerbRules(verbs: boolean): Rule[] {
    return [
        rule("thou [A-Za-z]+", (core, { before }) => {
            const [thou, verb] = core.split(/\s+/);
            const lower = verb.toLowerCase();
            const prev = /([A-Za-z]+)\s*$/.exec(before)?.[1]?.toLowerCase();
            if (prev && THOU_AUX_FORMS.has(prev)) return null;
            const form = THOU_AUX[lower] ?? (verbs ? VERBS[lower]?.[0] : undefined);
            if (!form) return null;
            const space = core.slice(thou.length, core.length - verb.length);
            return thou + space + keepCase(verb, form);
        }),
    ];
}

const THIRD_ALWAYS = rules({
    has: "hath",
    does: "doth",
    says: "saith",
});

const THIRD_ETH: Rule[] = [
    rule("(?:he|she|it|who|nobody|everyone|everybody|someone|somebody|anyone|anybody|no one) [A-Za-z]+", (core) => {
        const verb = /[A-Za-z]+$/.exec(core)![0];
        const form = thirdPerson(verb);
        if (!form) return null;
        return core.slice(0, core.length - verb.length) + keepCase(verb, form);
    }),
];

const MY: Rule[] = [
    rule("my", (core, { after, atStart }) =>
        /^\s+[aeioAEIO]/.test(after) ? matchCase(core, "mine", atStart) : null,
    ),
];

const VOCAB = rules({
    hello: "good morrow",
    hi: "hail",
    hey: "hark",
    yes: "aye",
    yeah: "aye",
    yep: "aye",
    yup: "aye",
    nope: "nay",
    no: (core, { after, atStart }) => (/^\s*([,.!?]|$)/.test(after) ? matchCase(core, "nay", atStart) : null),
    okay: "very well",
    ok: "very well",
    thanks: "gramercy",
    please: "prithee",
    before: "ere",
    often: "oft",
    maybe: "perchance",
    perhaps: "perchance",
    why: "wherefore",
    really: "verily",
    nothing: "naught",
    anything: "aught",
    between: "betwixt",
    while: "whilst",
    among: "amongst",
    soon: "anon",
    girl: "lass",
    girls: "lasses",
    boy: "lad",
    boys: "lads",
    tired: "weary",
    sorry: "pray pardon me",
});

// --- protected spans -----------------------------------------------------------

/**
 * Parts of a message that must reach Discord untouched: code, links, mentions,
 * custom emoji, timestamps and :emoji: shortcodes.
 */
const PROTECTED = /(```[\s\S]*?```|`[^`\n]*`|<[@#:a-z][^>\s]*>|https?:\/\/\S+|:[A-Za-z0-9_~-]+:)/g;

function translatePlain(text: string, opts: TranslateOptions): string {
    if (!/[A-Za-z]/.test(text)) return text;
    if (opts.slang) text = applyRules(text, SLANG);
    text = applyRules(text, CONTRACTIONS);
    text = applyRules(text, IDIOMS);
    text = applyRules(text, DO_NOT);
    text = applyRules(text, INVERSIONS);
    text = applyRules(text, POSSESSIVES);
    text = applyRules(text, YOU);
    text = applyRules(text, thouVerbRules(opts.verbs));
    text = applyRules(text, THIRD_ALWAYS);
    if (opts.verbs) text = applyRules(text, THIRD_ETH);
    text = applyRules(text, MY);
    text = applyRules(text, VOCAB);
    return text;
}

export function translate(text: string, options: Partial<TranslateOptions> = {}): string {
    const opts = { ...DEFAULT_OPTIONS, ...options };
    return text
        .split(PROTECTED)
        .map((part, i) => (i % 2 === 1 ? part : translatePlain(part, opts)))
        .join("");
}
