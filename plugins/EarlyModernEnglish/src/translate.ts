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

/**
 * Replacement used only when the word ends a clause: "damn!" but not
 * "damn good". With `exclaim`, it must also start one: "Shit!" but not "this shit".
 */
function alone(out: string, exclaim = false): Replacement {
    return (core, { before, after, atStart }) =>
        /^\s*([,.!?;:)]|$)/.test(after) && (!exclaim || atStart || /([,;:(]|\b(?:oh|ah|aw))\s*$/i.test(before))
            ? matchCase(core, out, atStart)
            : null;
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
    help: ["helpest", "helpeth"],
    call: ["callest", "calleth"],
    run: ["runnest", "runneth"],
    walk: ["walkest", "walketh"],
    talk: ["talkest", "talketh"],
    read: ["readest", "readeth"],
    write: ["writest", "writeth"],
    sing: ["singest", "singeth"],
    pay: ["payest", "payeth"],
    buy: ["buyest", "buyeth"],
    sell: ["sellest", "selleth"],
    send: ["sendest", "sendeth"],
    bring: ["bringest", "bringeth"],
    hold: ["holdest", "holdeth"],
    stand: ["standest", "standeth"],
    sit: ["sittest", "sitteth"],
    lose: ["losest", "loseth"],
    win: ["winnest", "winneth"],
    fight: ["fightest", "fighteth"],
    wait: ["waitest", "waiteth"],
    watch: ["watchest", "watcheth"],
    stay: ["stayest", "stayeth"],
    leave: ["leavest", "leaveth"],
    begin: ["beginnest", "beginneth"],
    change: ["changest", "changeth"],
    owe: ["owest", "oweth"],
    swear: ["swearest", "sweareth"],
    die: ["diest", "dieth"],
    cry: ["criest", "crieth"],
    laugh: ["laughest", "laugheth"],
    smile: ["smilest", "smileth"],
    kill: ["killest", "killeth"],
    steal: ["stealest", "stealeth"],
    drink: ["drinkest", "drinketh"],
    dream: ["dreamest", "dreameth"],
    learn: ["learnest", "learneth"],
    teach: ["teachest", "teacheth"],
    show: ["showest", "showeth"],
    open: ["openest", "openeth"],
    close: ["closest", "closeth"],
    move: ["movest", "moveth"],
    turn: ["turnest", "turneth"],
    start: ["startest", "starteth"],
    stop: ["stoppest", "stoppeth"],
    happen: ["happenest", "happeneth"],
    matter: ["matterest", "mattereth"],
    deserve: ["deservest", "deserveth"],
    promise: ["promisest", "promiseth"],
    agree: ["agreest", "agreeth"],
    prefer: ["preferrest", "preferreth"],
    suppose: ["supposest", "supposeth"],
    guess: ["guessest", "guesseth"],
    wonder: ["wonderest", "wondereth"],
    worry: ["worriest", "worrieth"],
    enjoy: ["enjoyest", "enjoyeth"],
    miss: ["missest", "misseth"],
    kiss: ["kissest", "kisseth"],
    touch: ["touchest", "toucheth"],
    follow: ["followest", "followeth"],
    answer: ["answerest", "answereth"],
    meet: ["meetest", "meeteth"],
    wear: ["wearest", "weareth"],
    carry: ["carriest", "carrieth"],
    break: ["breakest", "breaketh"],
    fall: ["fallest", "falleth"],
    hurt: ["hurtest", "hurteth"],
    use: ["usest", "useth"],
    trust: ["trustest", "trusteth"],
    pray: ["prayest", "prayeth"],
    forgive: ["forgivest", "forgiveth"],
    serve: ["servest", "serveth"],
    seek: ["seekest", "seeketh"],
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
    // bawdy talk, in the words Shakespeare's audience used
    "have sex": "make the beast with two backs",
    "had sex": "made the beast with two backs",
    "having sex": "making the beast with two backs",
    "hook up": "tryst",
    hookup: "tryst",
    "jerk off": "pleasure oneself",
    "jack off": "pleasure oneself",
    wank: "pleasure oneself",
    masturbate: "pleasure oneself",
    sex: "swiving",
    sexy: "comely",
    horny: "lusty",
    aroused: "enflamed",
    porn: "bawdry",
    nudes: "naked likenesses",
    penis: "yard",
    dick: "prick",
    cock: "prick",
    boner: "stiff yard",
    balls: "stones",
    testicles: "stones",
    vagina: "quaint",
    pussy: "quaint",
    cunt: "quaint",
    boobs: "bosoms",
    tits: "paps",
    butt: "arse",
    slut: "strumpet",
    whore: "strumpet",
    hoe: "strumpet",
    condom: "sheath",
    cum: "seed",
    orgasm: "little death",
    fucked: "undone",
    // oaths and insults, the period way
    "what the fuck": "what in God's name",
    "what the hell": "what in God's name",
    "fuck you": "a pox upon thee",
    "fuck off": "get thee hence",
    "piss off": "begone",
    "go to hell": "get thee to perdition",
    "holy shit": "God's wounds",
    "damn it": "a pox upon it",
    dammit: "a pox upon it",
    "no cap": "upon mine honour",
    stfu: "hold thy tongue",
    gtfo: "get thee hence",
    fuck: alone("zounds", true),
    fck: alone("zounds", true),
    shit: alone("zounds", true),
    damn: alone("a pox upon it", true),
    fucking: "bloody",
    freaking: "bloody",
    frickin: "bloody",
    effing: "bloody",
    bullshit: "folly",
    asshole: "base knave",
    ass: "arse",
    bastard: "whoreson",
    bitch: "harpy",
    jerk: "churl",
    loser: "wretch",
    moron: "dullard",
    noob: "novice",
    sucks: "is most foul",
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
    lmk: "pray tell me",
    ttyl: "we shall speak anon",
    omw: "I am on my way",
    gtg: "I must away",
    g2g: "I must away",
    cya: "fare thee well",
    jk: "I jest",
    sus: "most suspect",
    cringe: "most unseemly",
    yolo: "we live but once",
    bff: "bosom friend",
    hmu: "send word",
    wbu: "and thou?",
    wym: "what meanest thou",
    ily: "I love thee",
    ofc: "certes",
    asap: "with all haste",
    fyi: "know thou that",
    tho: "though",
    cuz: "because",
    ya: "you",
    yall: "ye",
    thru: "through",
    srsly: "in sooth",
    prob: "belike",
    probs: "belike",
    obv: "plainly",
    obvi: "plainly",
    sup: "what news",
    wassup: "what news",
    yo: "ho there",
    dope: "most excellent",
    bae: "my love",
    ong: "upon mine honour",
    deadass: "in sooth",
    lowkey: "privily",
    highkey: "openly",
    ez: "'twas no labour",
    gl: "fortune be with thee",
    k: alone("very well"),
    gimme: "give me",
    lemme: "let me",
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
    "what time is it": "what hour is it",
    "how old are you": "how many summers hast thou seen",
    "where are you going": "whither goest thou",
    "where are you from": "whence comest thou",
    "nice to meet you": "well met",
    "excuse me": "by your leave",
    "you are welcome": "'tis naught",
    "no problem": "'tis naught",
    "never mind": "think naught of it",
    "good evening": "good even",
    "good afternoon": "good den",
    "what is up": "what news",
    "shut up": "hold thy tongue",
    "be quiet": "peace",
    "hurry up": "make haste",
    "come on": "come now",
    "let me know": "pray tell me",
    "of course": "certes",
    "i swear": "by my troth",
    "oh no": "alack",
    "oh well": "alas",
    "good luck": "fortune be with thee",
    "good job": "bravely done",
    "nice job": "bravely done",
    "great job": "bravely done",
    "happy birthday": "joy to thee on thy natal day",
    "pretty good": "passing good",
    "was killed": "was slain",
    "got killed": "was slain",
    "talk to you later": "we shall speak anon",
    "be right back": "return anon",
    "thank god": "thank heaven",
    "go there": "go thither",
    "get out": "get thee hence",
    "have fun": "make merry",
    "having fun": "making merry",
    "had fun": "made merry",
    "for fun": "for sport",
    "hang out": "keep company",
    "hanging out": "keeping company",
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
    // interjections
    wow: "marry",
    oops: "alack",
    whoops: "alack",
    ugh: "fie",
    yay: "huzzah",
    hooray: "huzzah",
    alright: alone("very well"),
    whatever: alone("as thou wilt"),
    exactly: alone("just so"),
    listen: "hark",
    // adverbs and linking words
    never: "ne'er",
    over: "o'er",
    tomorrow: "on the morrow",
    today: "this day",
    tonight: "this night",
    morning: "morn",
    almost: "well nigh",
    enough: "enow",
    quickly: "apace",
    probably: "belike",
    anyway: "howsoever",
    anyways: "howsoever",
    actually: "in truth",
    seriously: "in sooth",
    honestly: "in faith",
    definitely: "assuredly",
    certainly: "assuredly",
    obviously: "plainly",
    although: "albeit",
    nobody: "no man",
    "no one": "none",
    // verbs
    wait: "tarry",
    hurry: "make haste",
    kill: "slay",
    killed: "slew",
    // adjectives
    beautiful: "fair",
    pretty: "fair",
    ugly: "foul",
    terrible: "most foul",
    awful: "most foul",
    horrible: "most foul",
    happy: "merry",
    sad: "woeful",
    angry: "wroth",
    afraid: "afeard",
    scared: "afeard",
    funny: "droll",
    weird: "strange",
    boring: "tedious",
    rude: "churlish",
    annoying: "vexing",
    annoyed: "vexed",
    upset: "vexed",
    fake: "false",
    fun: "merry",
    cute: "comely",
    attractive: "comely",
    flirt: "dally",
    flirting: "dallying",
    relax: "rest easy",
    chill: "at ease",
    huge: "vast",
    smart: "wise",
    // nouns
    car: "carriage",
    cars: "carriages",
    game: "sport",
    games: "sports",
    job: "trade",
    doctor: "physician",
    police: "constables",
    cops: "constables",
    jail: "gaol",
    prison: "gaol",
    stomach: "belly",
    bathroom: "privy",
    toilet: "privy",
    clothes: "garments",
    message: "missive",
    messages: "missives",
    story: "tale",
    stories: "tales",
    party: "revel",
    parties: "revels",
    food: "victuals",
    beer: "ale",
    buddy: "good fellow",
    pal: "good fellow",
    guy: "fellow",
    kids: "younglings",
    boyfriend: "suitor",
    girlfriend: "sweetheart",
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
