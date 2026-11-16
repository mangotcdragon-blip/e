/* Fic Reader UI. Talks to MainActivity through window.Native (see MainActivity.Bridge). */
(function () {
  'use strict';

  // ---------------------------------------------------------------- small helpers

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  function h(html) { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; }
  const fmt = n => {
    n = Number(n) || 0;
    if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1e4) return Math.round(n / 1e3) + 'k';
    if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  };
  const full = n => (Number(n) || 0).toLocaleString('en-US');
  const readTime = words => {
    const m = Math.round((Number(words) || 0) / 250);
    if (m < 60) return Math.max(1, m) + ' min';
    const hh = Math.floor(m / 60), mm = m % 60;
    return hh + 'h' + (mm ? ' ' + mm + 'm' : '');
  };
  const N = window.Native || null;
  const native = (fn, ...a) => { try { if (N && N[fn]) N[fn](...a); } catch (e) { /* ignore */ } };

  const ICON = {
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    library: '<path d="M4 5a2 2 0 0 1 2-2h14v15H6a2 2 0 0 0-2 2V5Z"/><path d="M4 20a2 2 0 0 0 2 2h14v-4"/>',
    settings: '<path d="M4 6h9M19 6h1M4 12h3M11 12h9M4 18h11M19 18h1"/><circle cx="16" cy="6" r="2.2"/><circle cx="9" cy="12" r="2.2"/><circle cx="17" cy="18" r="2.2"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    next: '<path d="m9 5 7 7-7 7"/>',
    close: '<path d="M6 6l12 12M18 6 6 18"/>',
    bookmark: '<path d="M6 3h12v18l-6-4.2L6 21z"/>',
    share: '<circle cx="18" cy="5" r="2.5"/><circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="19" r="2.5"/><path d="m8.2 10.8 7.6-4.4M8.2 13.2l7.6 4.4"/>',
    external: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
    filter: '<path d="M4 5h16l-6 8v5l-4 2v-7z"/>',
    sort: '<path d="M7 4v16M3 16l4 4 4-4M17 20V4M13 8l4-4 4 4"/>',
    heart: '<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/>',
    eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    words: '<path d="M4 6h16M4 12h16M4 18h10"/>',
    list: '<path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4.5" cy="6" r=".8"/><circle cx="4.5" cy="12" r=".8"/><circle cx="4.5" cy="18" r=".8"/>',
    type: '<path d="M3 19 8.5 5 14 19M5.2 14h6.6"/><path d="M16.5 11.2a2.6 2.6 0 0 1 4.5 1.8V19m0-3.2c-.7-.6-1.7-.8-2.6-.8-1.5 0-2.6.8-2.6 2s1 2 2.4 2c1.2 0 2.3-.6 2.8-1.6"/>',
    refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>',
    go: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    check: '<path d="m5 12 5 5L20 7"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    logout: '<path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 17l5-5-5-5M15 12H3"/>',
    shield: '<path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z"/>',
    trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
    down: '<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>',
    up: '<path d="M12 15V4M7 9l5-5 5 5M5 20h14"/>',
    clock: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>',
    comment: '<path d="M4 5h16v11H9l-5 4z"/>',
    bookmarks: '<path d="M7 3h10v17l-5-3.5L7 20z"/>',
  };
  const icon = (name, extra = '') => `<svg class="i" viewBox="0 0 24 24" aria-hidden="true" ${extra}>${ICON[name] || ''}</svg>`;

  // ---------------------------------------------------------------- storage

  const Store = {
    get(k, d) { try { const v = localStorage.getItem('fr.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('fr.' + k, JSON.stringify(v)); } catch (e) { /* full or blocked */ } },
  };

  const DEFAULT_SETTINGS = {
    theme: 'dark', size: 18, lh: 1.7, font: 'serif', margin: 'm', para: 'spaced', justify: false,
    keepOn: true, immersive: false, tapZones: false, rememberFilters: true, compact: false,
  };
  const settings = Object.assign({}, DEFAULT_SETTINGS, Store.get('settings', {}));
  const saved = Store.get('saved', {});      // id -> snapshot + savedAt/knownChapters/newCount
  const hist = Store.get('history', {});     // id -> snapshot + chapter/chapterId/frac/at
  let recent = Store.get('recent', []);

  const blankFilters = () => ({
    query: '', sort: '_score', ratings: [], complete: '', crossover: '', language: '',
    minWords: '', maxWords: '', minKudos: '', fandoms: '', characters: '', relationships: '',
    freeforms: '', excluded: '', creators: '', title: '',
  });
  let filters = Object.assign(blankFilters(), settings.rememberFilters ? Store.get('filters', {}) : {});
  filters.query = '';

  let saveTimer = null;
  function persistSoon() { clearTimeout(saveTimer); saveTimer = setTimeout(persist, 600); }
  function persist() {
    clearTimeout(saveTimer);
    Store.set('settings', settings);
    Store.set('saved', saved);
    Store.set('history', hist);
    Store.set('recent', recent);
    const f = Object.assign({}, filters); f.query = '';
    Store.set('filters', settings.rememberFilters ? f : {});
  }

  function snapshot(w) {
    const cut = a => (a || []).slice(0, 12);
    return {
      id: w.id, title: w.title, authors: w.authors || [], fandoms: cut(w.fandoms), rating: w.rating,
      complete: !!w.complete, words: w.words, chaptersPosted: w.chaptersPosted, chaptersTotal: w.chaptersTotal,
      kudos: w.kudos, hits: w.hits, bookmarks: w.bookmarks, updated: w.updated, summary: w.summary,
      tags: {
        warnings: cut(w.tags && w.tags.warnings), relationships: cut(w.tags && w.tags.relationships),
        characters: cut(w.tags && w.tags.characters), freeforms: cut(w.tags && w.tags.freeforms),
      },
    };
  }

  // ---------------------------------------------------------------- network

  function classify(r) {
    r.ok = false;
    if (!r.status) { r.problem = { kind: 'network', message: 'Could not reach AO3. Check your connection and try again.' }; return r; }
    if (/\/users\/login/.test(r.url || '')) { r.problem = { kind: 'login', message: 'AO3 only shows this to logged-in users.' }; return r; }
    if (r.status === 429) { r.problem = { kind: 'rate', message: 'AO3 is asking for a short break. Wait a minute, then try again.' }; return r; }
    if (r.status === 404) { r.problem = { kind: 'missing', message: 'Not found. It may have been deleted or hidden.' }; return r; }
    if (r.status >= 400) {
      const p = AO3.describeProblem(AO3.parse(r.body || ''));
      const outage = r.status === 500 || r.status === 502 || r.status === 504 || (r.status >= 520 && r.status <= 530);
      if (outage && p.kind !== 'check') {
        p.kind = 'down';
        p.message = 'AO3\'s servers are not responding right now (error ' + r.status + '). This is on AO3\'s side, not your phone or the app. Try again in a few minutes.';
      } else if (p.kind === 'unknown') {
        p.kind = (r.status === 418 || r.status === 403) ? 'check' : r.status === 503 ? 'down' : 'server';
        p.message = p.kind === 'check' ? 'AO3 wants to check this connection first.'
          : p.kind === 'down' ? 'AO3 is down or busy right now (error 503). Try again in a few minutes.'
          : 'AO3 returned an error (' + r.status + '). Try again in a little while.';
      }
      r.problem = p;
      return r;
    }
    r.ok = true;
    return r;
  }

  const net = {
    seq: 0, pending: new Map(), cache: new Map(),
    get(url, opt) {
      const fresh = opt && opt.fresh;
      if (!fresh && this.cache.has(url)) return Promise.resolve(this.cache.get(url));
      return new Promise(resolve => {
        const id = 'r' + (++this.seq);
        this.pending.set(id, resolve);
        if (N) N.fetch(id, url);
        else fetch(url, { credentials: 'include' })
          .then(r => r.text().then(body => this.resolve(id, { status: r.status, url: r.url, body })))
          .catch(e => this.resolve(id, { status: 0, url, body: '', error: String(e) }));
      }).then(res => {
        classify(res);
        if (res.ok) {
          this.cache.set(url, res);
          while (this.cache.size > 40) this.cache.delete(this.cache.keys().next().value);
        }
        return res;
      });
    },
    resolve(id, res) { const f = this.pending.get(id); if (f) { this.pending.delete(id); f(res); } },
  };

  // ---------------------------------------------------------------- navigation

  const tabs = {};
  let currentTab = 'search';
  const stack = [];      // pushed screens: {el, onPop, onShow}
  let sheet = null;      // {close}
  let lastRetry = null;

  function topEl() { return stack.length ? stack[stack.length - 1].el : tabs[currentTab]; }

  function push(el, hooks) {
    topEl().classList.add('covered');
    el.classList.add('screen', 'stacked');
    document.body.appendChild(el);
    stack.push(Object.assign({ el }, hooks || {}));
    return el;
  }

  function pop() {
    const s = stack.pop();
    if (!s) return;
    if (s.onPop) s.onPop();
    s.el.remove();
    const t = topEl();
    t.classList.remove('covered');
    const below = stack[stack.length - 1];
    if (below && below.onShow) below.onShow();
    if (!below && tabs[currentTab].onShow) tabs[currentTab].onShow();
  }

  function switchTab(name) {
    while (stack.length) pop();
    currentTab = name;
    for (const [k, el] of Object.entries(tabs)) el.classList.toggle('covered', k !== name);
    $$('#bottom-nav button').forEach(b => b.classList.toggle('on', b.dataset.tab === name));
    if (tabs[name].onShow) tabs[name].onShow();
  }

  function back() {
    if (sheet) { sheet.close(); return true; }
    if (stack.length) { pop(); return true; }
    if (currentTab !== 'search') { switchTab('search'); return true; }
    return false;
  }

  function openSheet(content, opts) {
    if (sheet) sheet.close();
    const scrim = h('<div class="scrim"></div>');
    const el = h('<div class="sheet" role="dialog"><div class="grab"></div></div>');
    el.appendChild(content);
    document.body.append(scrim, el);
    const me = {
      close() {
        if (sheet !== me) return;
        sheet = null; scrim.remove(); el.remove();
        if (opts && opts.onClose) opts.onClose();
      },
    };
    scrim.addEventListener('click', () => me.close());
    sheet = me;
    return me;
  }

  let toastTimer;
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2200);
  }

  // ---------------------------------------------------------------- shared bits

  function loginUrl() { return AO3.HOME + '/users/login'; }

  function errorBox(problem, retry, pageUrl) {
    problem = problem || { kind: 'unknown', message: 'Something went wrong.' };
    lastRetry = retry || null;
    const titles = {
      network: 'No connection', login: 'Log in needed', rate: 'Slow down', missing: 'Not found',
      check: 'AO3 is checking this connection', server: 'AO3 is having trouble', down: 'AO3 is down right now', adult: 'Adult content check', unknown: 'Could not load this',
    };
    const el = h(`<div class="error-box"><b>${esc(titles[problem.kind] || titles.unknown)}</b><p>${esc(problem.message)}</p><div class="btn-row"></div></div>`);
    const row = $('.btn-row', el);
    const add = (label, cls, fn) => { const b = h(`<button class="btn small ${cls}">${esc(label)}</button>`); b.onclick = fn; row.appendChild(b); };
    if (problem.kind === 'login') add('Log in to AO3', 'primary', () => native('openBrowser', loginUrl()));
    if (problem.kind === 'check') add('Open AO3 to verify', 'primary', () => native('openBrowser', pageUrl || AO3.HOME));
    if (retry && problem.kind !== 'missing') add('Try again', problem.kind === 'login' || problem.kind === 'check' ? '' : 'primary', retry);
    if (pageUrl && problem.kind !== 'check') add('Open in browser', '', () => native('openExternal', pageUrl));
    return el;
  }

  function skeletons(n) {
    const box = h('<div class="cards"></div>');
    for (let i = 0; i < n; i++) box.appendChild(h('<div class="skeleton"><i style="width:30%"></i><i style="width:80%;height:16px"></i><i style="width:55%"></i><i></i><i style="width:90%"></i></div>'));
    return box;
  }
  const loader = () => h('<div class="loader"><div class="spin"></div></div>');

  function ratingBadge(id, withName) {
    const r = AO3.RATINGS.find(x => x.id === String(id)) || AO3.RATINGS[4];
    return `<span class="rating ${r.cls}" title="${esc(r.name)}">${r.short}</span>${withName ? `<span class="status">${esc(r.name)}</span>` : ''}`;
  }

  function statusPill(w) {
    if (w.complete) return '<span class="status done">Complete</span>';
    return '<span class="status">In progress</span>';
  }

  const SKIP_WARN = /^(No Archive Warnings Apply)$/i;

  function tagChips(tags, limit) {
    const list = [];
    (tags.warnings || []).forEach(t => { if (!SKIP_WARN.test(t.name)) list.push([t, 'warn']); });
    (tags.relationships || []).forEach(t => list.push([t, 'rel']));
    (tags.characters || []).forEach(t => list.push([t, '']));
    (tags.freeforms || []).forEach(t => list.push([t, '']));
    const box = h('<div class="tags"></div>');
    const draw = all => {
      box.innerHTML = '';
      const shown = all ? list : list.slice(0, limit);
      shown.forEach(([t, cls]) => {
        const b = h(`<button class="tag ${cls}">${esc(t.name)}</button>`);
        b.onclick = e => { e.stopPropagation(); openTag(t.name, t.href); };
        box.appendChild(b);
      });
      if (!all && list.length > limit) {
        const more = h(`<button class="tag more">+${list.length - limit} more</button>`);
        more.onclick = e => { e.stopPropagation(); draw(true); };
        box.appendChild(more);
      }
    };
    draw(!limit);
    return list.length ? box : null;
  }

  function card(w, opt) {
    opt = opt || {};
    const chapters = (w.chaptersPosted || 1) + '/' + (w.chaptersTotal || '?');
    const el = h(`<div class="card" role="button" tabindex="0">
      <div class="card-top">${ratingBadge(w.rating)} ${statusPill(w)} ${opt.badge ? `<span class="badge-new">${esc(opt.badge)}</span>` : ''}<span class="date">${esc(w.updated || '')}</span></div>
      <h3>${esc(w.title)}</h3>
      <div class="by">by ${esc((w.authors || []).map(a => a.name).join(', ') || 'Anonymous')}</div>
      ${w.fandoms && w.fandoms.length ? `<div class="fandoms">${esc(w.fandoms.map(f => f.name).join(' · '))}</div>` : ''}
      ${w.summary && !opt.noSummary ? `<div class="summary">${w.summary}</div>` : ''}
    </div>`);
    if (opt.remove) {
      el.querySelector('.card-top .date').style.marginRight = '36px';
      const x = h(`<button class="icon-btn remove" aria-label="Remove">${icon('close')}</button>`);
      x.onclick = e => { e.stopPropagation(); opt.remove(el); };
      el.appendChild(x);
    }
    if (!settings.compact && !opt.noTags && w.tags) {
      const t = tagChips(w.tags, 7);
      if (t) el.appendChild(t);
    }
    el.appendChild(h(`<div class="stats">
      <span>${icon('words')}${fmt(w.words)}</span>
      <span>${icon('list')}${chapters}</span>
      <span>${icon('heart')}${fmt(w.kudos)}</span>
      <span>${icon('eye')}${fmt(w.hits)}</span>
      ${opt.progressText ? `<span style="margin-left:auto;color:var(--text-2)">${esc(opt.progressText)}</span>` : ''}
    </div>`));
    if (opt.progress != null) el.appendChild(h(`<div class="progress"><i style="width:${Math.round(opt.progress * 100)}%"></i></div>`));
    el.addEventListener('click', () => openWork(w.id, w));
    return el;
  }

  function progressOf(entry) {
    if (!entry) return null;
    const total = Math.max(entry.chaptersPosted || 1, (entry.chapter || 0) + 1);
    const p = ((entry.chapter || 0) + (entry.frac || 0)) / total;
    const text = total > 1 ? `Ch ${(entry.chapter || 0) + 1}/${total} · ${Math.round((entry.frac || 0) * 100)}%`
      : `${Math.round((entry.frac || 0) * 100)}%`;
    return { p: Math.min(1, p), text };
  }

  /** An endless list of work cards that loads AO3 pages one after another. */
  function createFeed(scroller, body, urlFor, opt) {
    opt = opt || {};
    let page = 0, hasNext = true, loading = false, dead = false;
    const seen = new Set();
    const meta = h('<div class="result-meta"></div>');
    const list = h('<div class="cards"></div>');
    const foot = h('<div></div>');
    body.replaceChildren(meta, list, foot);

    const nearEnd = () => foot.getBoundingClientRect().top < scroller.clientHeight + 700;
    const io = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) more(); }, { root: scroller, rootMargin: '700px' });
    io.observe(foot);

    async function more() {
      if (loading || !hasNext || dead) return;
      loading = true;
      foot.replaceChildren(page === 0 ? skeletons(3) : loader());
      const url = urlFor(page + 1);
      const res = await net.get(url);
      if (dead) return;
      loading = false;
      if (!res.ok) { foot.replaceChildren(errorBox(res.problem, () => more(), url)); return; }
      const r = AO3.parseList(res.body);
      page++;
      hasNext = r.next && r.items.length > 0;
      if (page === 1) {
        meta.textContent = r.total != null ? full(r.total) + (r.total === 1 ? ' work' : ' works') : '';
        if (opt.onFirst) opt.onFirst(r);
      }
      for (const w of r.items) {
        if (seen.has(w.id)) continue;
        seen.add(w.id);
        list.appendChild(card(w));
      }
      if (page === 1 && !r.items.length) {
        foot.replaceChildren(h(`<div class="empty"><b>No works found</b><p>${esc(opt.emptyText || 'Try different words or fewer filters.')}</p></div>`));
        return;
      }
      foot.replaceChildren(hasNext ? h('<div style="height:40px"></div>') : h('<div class="empty" style="padding:24px">That is everything.</div>'));
      if (hasNext) requestAnimationFrame(() => { if (nearEnd()) more(); });
    }
    more();
    return { destroy() { dead = true; io.disconnect(); } };
  }

  // ---------------------------------------------------------------- search tab

  function activeFilterCount() {
    const b = blankFilters();
    let n = 0;
    for (const k of Object.keys(b)) {
      if (k === 'query' || k === 'sort') continue;
      const v = filters[k];
      if (Array.isArray(v) ? v.length : v !== b[k]) n++;
    }
    return n;
  }

  function buildSearch() {
    const el = tabs.search;
    el.innerHTML = `
      <div class="search-head">
        <div class="brand">Fic <span>Reader</span></div>
        <form class="searchbox" autocomplete="off">
          ${icon('search')}
          <input type="search" name="q" placeholder="Search AO3, or paste a link" enterkeyhint="search" autocapitalize="off" spellcheck="false">
          <button type="button" class="icon-btn clear hidden" aria-label="Clear">${icon('close')}</button>
        </form>
        <div class="filter-row"></div>
      </div>
      <div class="wrap" id="search-body"></div>`;
    const form = $('form', el), input = $('input', el), clear = $('.clear', el);
    const body = $('#search-body', el);
    let feed = null;

    input.addEventListener('input', () => clear.classList.toggle('hidden', !input.value));
    clear.onclick = () => { input.value = ''; clear.classList.add('hidden'); input.focus(); };
    form.addEventListener('submit', e => { e.preventDefault(); input.blur(); run(input.value); });

    function chips() {
      const row = $('.filter-row', el);
      const n = activeFilterCount();
      const sortLabel = (AO3.SORTS.find(s => s.v === filters.sort) || AO3.SORTS[0]).label;
      row.innerHTML = '';
      const add = (html, on, fn) => { const b = h(`<button class="chip ${on ? 'on' : ''}">${html}</button>`); b.onclick = fn; row.appendChild(b); };
      add(`${icon('filter')}Filters${n ? ` <span class="count">${n}</span>` : ''}`, n > 0, () => filterSheet(() => { chips(); rerun(); }));
      add(`${icon('sort')}${esc(sortLabel)}`, filters.sort !== '_score', () => sortSheet(filters.sort, v => { filters.sort = v; chips(); rerun(); }));
      add('Complete only', filters.complete === 'T', () => { filters.complete = filters.complete === 'T' ? '' : 'T'; chips(); rerun(); });
      add('English', filters.language === 'en', () => { filters.language = filters.language === 'en' ? '' : 'en'; chips(); rerun(); });
      for (const r of AO3.RATINGS.slice(0, 4)) {
        const on = filters.ratings.includes(r.id);
        add(`<span class="rating ${r.cls}" style="min-width:18px;height:18px;font-size:11px">${r.short}</span>${esc(r.name.split(' ')[0])}`, on, () => {
          filters.ratings = on ? filters.ratings.filter(x => x !== r.id) : filters.ratings.concat(r.id);
          chips(); rerun();
        });
      }
    }

    let searched = false;
    function rerun() { persistSoon(); if (searched) run(input.value, true); }

    function run(q, keepScroll) {
      q = (q || '').trim();
      const r = AO3.route(q);
      if (r && r.type !== 'external' && (/archiveofourown\.org|ao3\.org/i.test(q) || /^\d{3,}$/.test(q))) { openRoute(r); return; }
      filters.query = q;
      if (q) { recent = [q].concat(recent.filter(x => x.toLowerCase() !== q.toLowerCase())).slice(0, 12); }
      persistSoon();
      searched = true;
      if (feed) feed.destroy();
      if (!keepScroll) el.scrollTop = 0;
      const snap = JSON.parse(JSON.stringify(filters));
      feed = createFeed(el, body, page => AO3.searchUrl(snap, page));
    }

    function home() {
      searched = false;
      if (feed) { feed.destroy(); feed = null; }
      body.innerHTML = '';
      const entries = Object.values(hist).sort((a, b) => b.at - a.at).slice(0, 3);
      if (entries.length) {
        body.appendChild(h('<div class="section-title">Continue reading</div>'));
        const box = h('<div class="cards"></div>');
        for (const e of entries) { const p = progressOf(e); box.appendChild(card(e, { progress: p.p, progressText: p.text, noTags: true, noSummary: true })); }
        body.appendChild(box);
      }
      if (recent.length) {
        const t = h('<div class="section-title">Recent searches <button>Clear</button></div>');
        $('button', t).onclick = () => { recent = []; persist(); home(); };
        body.appendChild(t);
        const box = h('<div class="recent"></div>');
        for (const q of recent) {
          const b = h(`<button class="chip">${icon('clock')}${esc(q)}</button>`);
          b.onclick = () => { input.value = q; clear.classList.remove('hidden'); run(q); };
          box.appendChild(b);
        }
        body.appendChild(box);
      }
      if (!entries.length && !recent.length) {
        body.appendChild(h(`<div class="empty"><b>Find something to read</b><p>Search by title, fandom, ship, character or any words. Use Filters to narrow it down, or paste an AO3 link to open it.</p></div>`));
      }
      const quick = h('<div class="section-title">Browse</div>');
      body.appendChild(quick);
      const box = h('<div class="recent"></div>');
      [['Most kudos', 'kudos_count'], ['Recently updated', 'revised_at'], ['Most bookmarked', 'bookmarks_count'], ['Newest', 'created_at']].forEach(([label, sort]) => {
        const b = h(`<button class="chip">${esc(label)}</button>`);
        b.onclick = () => { filters.sort = sort; chips(); run(input.value); };
        box.appendChild(b);
      });
      body.appendChild(box);
    }

    // Clearing the box and submitting goes back to the start page.
    input.addEventListener('search', () => { if (!input.value) home(); });
    clear.addEventListener('click', () => home());

    chips();
    home();
    el.onShow = () => { if (!searched) home(); };
  }

  function sortSheet(current, done, list) {
    const box = h('<div><h3>Sort by</h3><div class="toc"></div></div>');
    const toc = $('.toc', box);
    (list || AO3.SORTS).forEach(s => {
      const b = h(`<button><span class="${s.v === current ? 'cur' : ''}" style="flex:1">${esc(s.label)}</span>${s.v === current ? icon('check') : ''}</button>`);
      b.onclick = () => { sh.close(); done(s.v); };
      toc.appendChild(b);
    });
    const sh = openSheet(box);
  }

  function seg(options, value, onPick) {
    const el = h('<div class="seg"></div>');
    options.forEach(([v, label]) => {
      const b = h(`<button type="button" class="${v === value ? 'on' : ''}">${esc(label)}</button>`);
      b.onclick = () => { $$('button', el).forEach(x => x.classList.remove('on')); b.classList.add('on'); onPick(v); };
      el.appendChild(b);
    });
    return el;
  }

  function filterSheet(done) {
    const f = JSON.parse(JSON.stringify(filters));
    const box = h('<div><h3>Filters <button type="button" class="reset">Reset</button></h3></div>');
    const field = (label, child) => { const d = h(`<div class="field"><div class="lbl">${esc(label)}</div></div>`); d.appendChild(child); box.appendChild(d); return d; };
    const text = (key, ph) => { const i = h(`<input type="text" placeholder="${esc(ph || '')}" autocapitalize="off">`); i.value = f[key] || ''; i.oninput = () => { f[key] = i.value; }; return i; };
    const number = (key, ph) => { const i = h(`<input type="number" inputmode="numeric" min="0" placeholder="${esc(ph)}">`); i.value = f[key] || ''; i.oninput = () => { f[key] = i.value; }; return i; };

    const sortSel = h('<select></select>');
    AO3.SORTS.forEach(s => sortSel.appendChild(h(`<option value="${s.v}">${esc(s.label)}</option>`)));
    sortSel.value = f.sort; sortSel.onchange = () => { f.sort = sortSel.value; };
    field('Sort by', sortSel);

    const rchips = h('<div class="chips"></div>');
    AO3.RATINGS.forEach(r => {
      const b = h(`<button type="button" class="chip ${f.ratings.includes(r.id) ? 'on' : ''}"><span class="rating ${r.cls}" style="min-width:18px;height:18px;font-size:11px">${r.short}</span>${esc(r.name)}</button>`);
      b.onclick = () => {
        f.ratings = f.ratings.includes(r.id) ? f.ratings.filter(x => x !== r.id) : f.ratings.concat(r.id);
        b.classList.toggle('on');
      };
      rchips.appendChild(b);
    });
    field('Rating (none picked = all)', rchips);
    field('Status', seg([['', 'Any'], ['T', 'Complete'], ['F', 'In progress']], f.complete, v => { f.complete = v; }));
    field('Crossovers', seg([['', 'Include'], ['F', 'Exclude'], ['T', 'Only']], f.crossover, v => { f.crossover = v; }));

    const lang = h('<select></select>');
    AO3.LANGUAGES.forEach(([v, l]) => lang.appendChild(h(`<option value="${esc(v)}">${esc(l)}</option>`)));
    lang.value = f.language; lang.onchange = () => { f.language = lang.value; };
    field('Language', lang);

    const words = h('<div class="two"></div>');
    words.append(number('minWords', 'Min'), number('maxWords', 'Max'));
    field('Word count', words);
    field('Kudos at least', number('minKudos', 'e.g. 100'));

    field('Fandoms', text('fandoms', 'e.g. Harry Potter - J. K. Rowling'));
    field('Relationships', text('relationships', 'e.g. Draco Malfoy/Harry Potter'));
    field('Characters', text('characters', 'Comma separated'));
    field('Additional tags', text('freeforms', 'e.g. Fluff, Slow Burn'));
    field('Exclude tags', text('excluded', 'e.g. Major Character Death'));
    field('Author', text('creators', 'Username'));
    field('Title', text('title', 'Words in the title'));

    const actions = h('<div class="sheet-actions"><button type="button" class="btn">Cancel</button><button type="button" class="btn primary">Show results</button></div>');
    box.appendChild(actions);
    const sh = openSheet(box);
    $('.reset', box).onclick = () => {
      const q = filters.query;
      filters = blankFilters(); filters.query = q;
      sh.close(); done();
    };
    actions.children[0].onclick = () => sh.close();
    actions.children[1].onclick = () => {
      const q = filters.query;
      filters = f; filters.query = q;
      sh.close(); done();
    };
  }

  // ---------------------------------------------------------------- list screens (tag / author / series)

  function openTag(name, href) {
    const tagName = name || AO3.tagNameFromHref(href);
    openList({ kind: 'tag', title: tagName, label: 'Tag', tagName });
  }

  function openList(o) {
    const el = h(`<section>
      <div class="topbar line">
        <button class="icon-btn" data-act="back" aria-label="Back">${icon('back')}</button>
        <div class="sub"><b>${esc(o.title)}</b><small>${esc(o.label)}</small></div>
        <button class="icon-btn" data-act="web" aria-label="Open on AO3">${icon('external')}</button>
      </div>
      <div class="wrap"><div class="filter-row"></div><div class="body"></div></div>
    </section>`);
    let sort = o.kind === 'tag' ? 'revised_at' : '';
    let complete = '';
    let feed = null;
    const urlFor = page => {
      if (o.kind === 'tag') return AO3.withPage(AO3.tagWorksUrl(o.tagName, sort, complete), page);
      return AO3.withPage(o.url, page);
    };
    $('[data-act=back]', el).onclick = () => back();
    $('[data-act=web]', el).onclick = () => native('openExternal', urlFor(1));
    const row = $('.filter-row', el);
    function chips() {
      row.innerHTML = '';
      if (o.kind !== 'tag') { row.classList.add('hidden'); return; }
      const sortLabel = (AO3.SORTS.find(s => s.v === sort) || { label: 'Recently updated' }).label;
      const a = h(`<button class="chip">${icon('sort')}${esc(sortLabel)}</button>`);
      a.onclick = () => sortSheet(sort, v => { sort = v; chips(); load(); }, AO3.SORTS.filter(s => s.v !== '_score'));
      const b = h(`<button class="chip ${complete ? 'on' : ''}">Complete only</button>`);
      b.onclick = () => { complete = complete ? '' : 'T'; chips(); load(); };
      const c = h(`<button class="chip">${icon('search')}Search in this tag</button>`);
      c.onclick = () => {
        filters = blankFilters();
        filters.freeforms = o.tagName;
        switchTab('search');
        toast('Tag added to filters. Type words to search inside it.');
        buildSearch();
        $('#tab-search input').focus();
      };
      row.append(a, b, c);
    }
    function load() {
      if (feed) feed.destroy();
      el.scrollTop = 0;
      feed = createFeed(el, $('.body', el), urlFor, {
        onFirst(r) {
          const t = o.kind === 'series' ? r.seriesTitle : '';
          if (t) $('.sub b', el).textContent = t;
        },
      });
    }
    push(el, { onPop() { if (feed) feed.destroy(); } });
    chips();
    load();
  }

  function openRoute(r) {
    if (!r) return false;
    if (r.type === 'work') { openWork(r.id, null, { chapter: r.chapter }); return true; }
    if (r.type === 'tag') { openTag(r.name); return true; }
    if (r.type === 'user') { openList({ kind: 'user', title: r.name, label: 'Author', url: r.url }); return true; }
    if (r.type === 'series') { openList({ kind: 'series', title: 'Series', label: 'Series', url: r.url }); return true; }
    if (r.type === 'search') { openList({ kind: 'search', title: 'AO3 results', label: 'Search', url: r.url }); return true; }
    if (r.type === 'external' || r.type === 'chapter-only') { native('openExternal', r.url || (AO3.HOME + '/chapters/' + r.chapter)); return true; }
    return false;
  }

  // ---------------------------------------------------------------- work screen

  const works = new Map();        // id -> parsed work (this session)
  const chapterCache = new Map(); // id:index -> chapter content
  const ckey = (id, i) => id + ':' + i;

  function cacheContent(w) {
    if (!w.content || !w.content.length) return;
    if (w.content.length > 1 && w.content.length === w.chapters.length) {
      w.content.forEach((c, i) => chapterCache.set(ckey(w.id, i), c));
    } else if (w.content[0].html) {
      chapterCache.set(ckey(w.id, w.selected || 0), w.content[0]);
    }
  }

  function resumeIndex(w) {
    const e = hist[w.id];
    if (!e) return null;
    let i = e.chapterId ? w.chapters.findIndex(c => c.id === e.chapterId) : -1;
    if (i < 0) i = Math.min(e.chapter || 0, w.chapters.length - 1);
    return { index: i, frac: e.frac || 0 };
  }

  function linkList(items, sep) {
    return items.map(a => a.href ? `<a href="${esc(a.href)}">${esc(a.name)}</a>` : esc(a.name)).join(sep || ', ');
  }

  function openWork(id, snap, opt) {
    opt = opt || {};
    id = String(id);
    const el = h(`<section>
      <div class="topbar">
        <button class="icon-btn" data-act="back" aria-label="Back">${icon('back')}</button>
        <h1></h1>
        <button class="icon-btn" data-act="save" aria-label="Save">${icon('bookmark')}</button>
        <button class="icon-btn" data-act="share" aria-label="Share">${icon('share')}</button>
        <button class="icon-btn" data-act="web" aria-label="Open on AO3">${icon('external')}</button>
      </div>
      <div class="wrap"></div>
    </section>`);
    const wrap = $('.wrap', el);
    const saveBtn = $('[data-act=save]', el);
    let work = works.get(id) || null;

    function paintSave() {
      const on = !!saved[id];
      saveBtn.classList.toggle('on', on);
      saveBtn.innerHTML = icon('bookmark', on ? 'style="fill:currentColor"' : '');
    }
    paintSave();
    $('[data-act=back]', el).onclick = () => back();
    $('[data-act=web]', el).onclick = () => native('openExternal', AO3.HOME + '/works/' + id);
    $('[data-act=share]', el).onclick = () => native('share', (work || snap || {}).title || 'AO3 work', AO3.HOME + '/works/' + id);
    saveBtn.onclick = () => {
      const src = work || snap;
      if (!src) return;
      if (saved[id]) { delete saved[id]; toast('Removed from library'); }
      else { saved[id] = Object.assign(snapshot(src), { savedAt: Date.now(), knownChapters: src.chaptersPosted || 1, newCount: 0 }); toast('Saved to library'); }
      paintSave(); persistSoon();
    };

    // Show the title in the top bar once the big heading scrolls away.
    el.addEventListener('scroll', () => {
      const big = $('.work-head h2', el);
      $('.topbar h1', el).textContent = big && big.getBoundingClientRect().bottom < 60 ? big.textContent : '';
    }, { passive: true });

    function head(w) {
      return `<div class="work-head">
        <div class="pills">${ratingBadge(w.rating, true)} ${statusPill(w)}</div>
        <h2>${esc(w.title)}</h2>
        <div class="by">by ${linkList(w.authors || [])}</div>
        ${w.fandoms && w.fandoms.length ? `<div class="fandoms">${linkList(w.fandoms, ' · ')}</div>` : ''}
      </div>`;
    }

    function render() {
      const w = work;
      const r = resumeIndex(w);
      const multi = w.chapters.length > 1;
      let cta = 'Start reading';
      if (r) cta = multi ? `Continue · Ch ${r.index + 1} of ${w.chapters.length}` : `Continue · ${Math.round(r.frac * 100)}%`;
      wrap.innerHTML = head(w) + `
        <div class="stat-grid">
          <div><b>${fmt(w.words)}</b><small>words</small></div>
          <div><b>${w.chaptersPosted}/${w.chaptersTotal || '?'}</b><small>chapters</small></div>
          <div><b>${readTime(w.words)}</b><small>to read</small></div>
          <div><b>${fmt(w.kudos)}</b><small>kudos</small></div>
          <div><b>${fmt(w.hits)}</b><small>hits</small></div>
          <div><b>${fmt(w.bookmarks)}</b><small>bookmarks</small></div>
        </div>
        <div class="result-meta" style="margin-top:12px">${esc([w.published && 'Published ' + w.published, w.status && (w.statusLabel || 'Updated') + ' ' + w.status, w.language].filter(Boolean).join(' · '))}</div>
        <div class="actions">
          <button class="btn primary" data-act="read">${esc(cta)}</button>
          ${r && multi ? '<button class="btn" data-act="start">From start</button>' : ''}
        </div>
        ${w.summary ? `<div class="block"><h4>Summary</h4><div class="userstuff-sm">${w.summary}</div></div>` : ''}
        <div class="block tag-block"><h4>Tags</h4></div>
        ${w.series.length ? `<div class="block"><h4>Series</h4>${w.series.map(s => `<a class="series-link" href="${esc(s.href)}">${esc(s.text)}</a>`).join('')}</div>` : ''}
        ${w.notes ? `<div class="block"><details class="notes" style="background:var(--surface);border-color:var(--line)"><summary>Author's notes</summary><div class="nbody userstuff-sm">${w.notes}</div></details></div>` : ''}
        ${multi ? `<div class="block"><h4>Chapters</h4><div class="chapter-list"></div></div>` : ''}
      `;
      const tb = $('.tag-block', wrap);
      const groups = [['Warnings', w.tags.warnings, 'warn'], ['Relationships', w.tags.relationships, 'rel'],
        ['Characters', w.tags.characters, ''], ['Additional tags', w.tags.freeforms, ''], ['Categories', w.tags.categories, '']];
      let any = false;
      for (const [label, list, cls] of groups) {
        if (!list || !list.length) continue;
        any = true;
        const g = h(`<div class="tag-group"><small>${esc(label)}</small><div class="tags"></div></div>`);
        const t = $('.tags', g);
        list.forEach(tag => {
          const b = h(`<button class="tag ${cls}">${esc(tag.name)}</button>`);
          b.onclick = () => openTag(tag.name, tag.href);
          t.appendChild(b);
        });
        tb.appendChild(g);
      }
      if (!any) tb.remove();

      $('[data-act=read]', wrap).onclick = () => { const rr = resumeIndex(w); openReader(w, rr ? rr.index : 0, rr ? rr.frac : 0); };
      const st = $('[data-act=start]', wrap);
      if (st) st.onclick = () => openReader(w, 0, 0);

      const cl = $('.chapter-list', wrap);
      if (cl) {
        w.chapters.forEach((c, i) => {
          const isCur = r && r.index === i, isRead = r && i < r.index;
          const b = h(`<button class="${isCur ? 'cur' : ''}"><span class="n">${i + 1}</span><span class="t">${esc(c.title)}</span>${isRead ? `<span class="read">${icon('check')}</span>` : ''}</button>`);
          b.onclick = () => openReader(w, i, isCur ? r.frac : 0);
          cl.appendChild(b);
        });
      }
    }

    async function load() {
      if (!work) {
        wrap.innerHTML = (snap ? head(snap) : '') ;
        wrap.appendChild(skeletons(1));
      }
      const url = AO3.workUrl(id);
      const res = await net.get(url);
      if (!el.isConnected) return;
      if (!res.ok) { wrap.replaceChildren(errorBox(res.problem, load, AO3.HOME + '/works/' + id)); if (snap) wrap.insertAdjacentHTML('afterbegin', head(snap)); return; }
      const w = AO3.parseWork(res.body, id);
      if (w.error) { net.cache.delete(url); wrap.replaceChildren(errorBox(w.error, load, AO3.HOME + '/works/' + id)); return; }
      work = w;
      works.set(id, w);
      cacheContent(w);
      if (saved[id]) {
        Object.assign(saved[id], snapshot(w), { knownChapters: w.chaptersPosted, newCount: 0 });
        persistSoon();
      }
      if (hist[id]) { Object.assign(hist[id], snapshot(w)); persistSoon(); }
      render();
      if (opt.chapter) {
        const i = w.chapters.findIndex(c => c.id === opt.chapter);
        opt.chapter = null;
        openReader(w, i >= 0 ? i : 0, 0);
      }
    }

    push(el, { onShow() { if (work) render(); } });
    if (work) render();
    load();
  }

  // ---------------------------------------------------------------- reader

  const THEME_BG = { dark: '#111216', dim: '#22252b', black: '#000000', sepia: '#f4ecd8', light: '#fbfbfa' };
  const MARGINS = { s: '12px', m: '22px', l: '36px' };

  function applyReaderStyle(el) {
    el.dataset.theme = settings.theme;
    el.style.setProperty('--r-size', settings.size + 'px');
    el.style.setProperty('--r-lh', settings.lh);
    el.style.setProperty('--r-font', settings.font === 'sans' ? 'var(--ui)' : 'var(--serif)');
    const page = $('.page', el);
    if (page) { page.style.paddingLeft = page.style.paddingRight = MARGINS[settings.margin] || MARGINS.m; }
    const prose = $('.prose', el);
    if (prose) { prose.classList.toggle('justify', settings.justify); prose.classList.toggle('indent', settings.para === 'indent'); }
  }

  function readingControls(onChange) {
    const box = h('<div></div>');
    const field = (label, child) => { const d = h(`<div class="field"><div class="lbl">${esc(label)}</div></div>`); d.appendChild(child); box.appendChild(d); };
    const set = (k, v) => { settings[k] = v; persistSoon(); onChange && onChange(k); };

    const sw = h('<div class="swatches"></div>');
    [['dark', 'Dark', '#111216', '#dcdce2'], ['dim', 'Dim', '#22252b', '#d0d2d8'], ['black', 'Black', '#000', '#cfcfd4'],
      ['sepia', 'Sepia', '#f4ecd8', '#3b3026'], ['light', 'Light', '#fbfbfa', '#26272b']].forEach(([v, l, bg, fg]) => {
      const b = h(`<button type="button" class="swatch ${settings.theme === v ? 'on' : ''}" style="background:${bg};color:${fg}">${l}</button>`);
      b.onclick = () => { $$('.swatch', sw).forEach(x => x.classList.remove('on')); b.classList.add('on'); set('theme', v); };
      sw.appendChild(b);
    });
    field('Theme', sw);

    const range = (key, min, max, step, show) => {
      const d = h(`<div class="range"><input type="range" min="${min}" max="${max}" step="${step}"><output></output></div>`);
      const i = $('input', d), o = $('output', d);
      i.value = settings[key]; o.textContent = show(settings[key]);
      i.oninput = () => { const v = Number(i.value); o.textContent = show(v); set(key, v); };
      return d;
    };
    field('Text size', range('size', 13, 30, 1, v => v + 'px'));
    field('Line spacing', range('lh', 1.3, 2.3, 0.05, v => Number(v).toFixed(2)));
    field('Font', seg([['serif', 'Serif'], ['sans', 'Sans']], settings.font, v => set('font', v)));
    field('Margins', seg([['s', 'Small'], ['m', 'Medium'], ['l', 'Large']], settings.margin, v => set('margin', v)));
    field('Paragraphs', seg([['spaced', 'Spaced'], ['indent', 'Indented']], settings.para, v => set('para', v)));

    const toggles = h('<div class="panel" style="padding:0 14px"></div>');
    const toggle = (key, label, sub) => {
      const r = h(`<label class="toggle-row"><span>${esc(label)}${sub ? `<small>${esc(sub)}</small>` : ''}</span><span class="switch"><input type="checkbox"><i></i></span></label>`);
      const i = $('input', r); i.checked = !!settings[key];
      i.onchange = () => set(key, i.checked);
      toggles.appendChild(r);
    };
    toggle('justify', 'Justify text');
    toggle('tapZones', 'Tap to turn pages', 'Tap the top or bottom of the screen to scroll a page');
    toggle('immersive', 'Full screen while reading', 'Hides the status and navigation bars');
    toggle('keepOn', 'Keep screen on while reading');
    box.appendChild(toggles);
    return box;
  }

  function wordsIn(html) {
    const d = document.createElement('div'); d.innerHTML = html;
    const t = d.textContent.trim();
    return t ? t.split(/\s+/).length : 0;
  }

  function openReader(work, startIndex, startFrac) {
    const el = h(`<section class="reader">
      <div class="rbar top">
        <button class="icon-btn" data-act="back" aria-label="Back">${icon('back')}</button>
        <div class="sub"><b></b><small>${esc(work.title)}</small></div>
        <button class="icon-btn" data-act="toc" aria-label="Chapters">${icon('list')}</button>
        <button class="icon-btn" data-act="style" aria-label="Reading settings">${icon('type')}</button>
      </div>
      <div class="page"></div>
      <div class="rbar bottom">
        <button class="nav-btn" data-act="prev">${icon('back')}Prev</button>
        <div class="rprog"><span class="lbl"></span><div class="progress"><i style="width:0"></i></div></div>
        <button class="nav-btn" data-act="next">Next${icon('next')}</button>
      </div>
    </section>`);
    const page = $('.page', el);
    const lbl = $('.rprog .lbl', el), bar = $('.rprog .progress i', el);
    let cur = startIndex, frac = 0, tok = 0, ready = false;
    const total = work.chapters.length;

    function chromeColors() { native('setBarColor', THEME_BG[settings.theme] || THEME_BG.dark); }
    function enterChrome() {
      chromeColors();
      native('setImmersive', !!settings.immersive);
      native('keepScreenOn', !!settings.keepOn);
    }
    function leaveChrome() {
      native('setBarColor', '#111216');
      native('setImmersive', false);
      native('keepScreenOn', false);
    }

    function updateProgress() {
      const max = el.scrollHeight - el.clientHeight;
      frac = max > 0 ? Math.min(1, Math.max(0, el.scrollTop / max)) : 1;
      const pct = Math.round(frac * 100);
      lbl.textContent = total > 1 ? `Chapter ${cur + 1} of ${total} · ${pct}%` : `${pct}%`;
      bar.style.width = (total > 1 ? ((cur + frac) / total) * 100 : pct) + '%';
    }

    function saveProgress() {
      if (!ready) return;
      const ch = work.chapters[cur] || {};
      hist[work.id] = Object.assign(snapshot(work), {
        chapter: cur, chapterId: ch.id || null, chapterTitle: ch.title || '', frac, at: Date.now(),
      });
      persistSoon();
    }

    function headerTitle(i) {
      const t = (work.chapters[i] || {}).title || '';
      return total > 1 ? (t && !/^chapter\s+\d+$/i.test(t) ? t : 'Chapter ' + (i + 1)) : work.title;
    }

    function notesBlock(label, parts) {
      const body = parts.filter(p => p[1]).map(([h5, html]) => (h5 ? `<h5>${esc(h5)}</h5>` : '') + html).join('');
      return body ? `<details class="notes"><summary>${esc(label)}</summary><div class="nbody">${body}</div></details>` : '';
    }

    function render(c, i) {
      const words = wordsIn(c.html);
      const first = i === 0, last = i === total - 1;
      const startNotes = notesBlock(first ? 'Summary & notes' : 'Chapter notes', [
        [first && total > 1 ? 'Work summary' : (first ? 'Summary' : ''), first ? work.summary : ''],
        [first ? 'Work notes' : '', first ? work.notes : ''],
        [total > 1 ? 'Chapter summary' : '', c.summary],
        [total > 1 && first ? 'Chapter notes' : '', c.notes],
      ]);
      const endNotes = notesBlock('End notes', [['', c.endNotes], [last && c.endNotes ? 'Work end notes' : '', last ? work.endNotes : '']]);
      const nextTitle = last ? '' : headerTitle(i + 1);
      page.innerHTML = `
        <header class="ch-head">
          <small>${total > 1 ? 'Chapter ' + (i + 1) + ' of ' + total : esc((work.authors || []).map(a => a.name).join(', '))}</small>
          <h2>${esc(headerTitle(i))}</h2>
          <div class="wt">${fmt(words)} words · about ${readTime(words)}</div>
        </header>
        ${startNotes}
        <div class="prose">${c.html || '<p><em>This chapter has no text.</em></p>'}</div>
        ${endNotes}
        <div class="end-nav">
          ${last ? `<div class="fin">${work.complete ? 'The end.' : 'You are caught up. This work is still in progress.'}</div>` : ''}
          ${last ? '' : `<button class="btn primary block" data-act="next">Next: ${esc(nextTitle)}</button>`}
          <button class="btn block" data-act="kudos">${icon('heart')}Kudos or comment on AO3</button>
          ${last ? '<button class="btn block" data-act="done">Back to the work</button>' : ''}
        </div>`;
      applyReaderStyle(el);
      const nb = $('.end-nav [data-act=next]', page); if (nb) nb.onclick = () => go(i + 1, 0);
      const kb = $('[data-act=kudos]', page);
      kb.onclick = () => native('openExternal', work.chapters[i].id ? AO3.HOME + '/works/' + work.id + '/chapters/' + work.chapters[i].id + '#comments' : AO3.HOME + '/works/' + work.id + '#comments');
      const db = $('[data-act=done]', page); if (db) db.onclick = () => back();
    }

    async function fetchChapter(i) {
      const k = ckey(work.id, i);
      if (chapterCache.has(k)) return { content: chapterCache.get(k) };
      const ch = work.chapters[i];
      if (!ch || !ch.id) return { problem: { kind: 'unknown', message: 'This chapter could not be found on the work page.' }, url: AO3.HOME + '/works/' + work.id };
      const url = AO3.chapterUrl(work.id, ch.id);
      const res = await net.get(url);
      if (!res.ok) return { problem: res.problem, url };
      const p = AO3.parseChapterPage(res.body);
      if (p.error) { net.cache.delete(url); return { problem: p.error, url }; }
      const content = p.content.length === total ? p.content[i] : p.content[0];
      chapterCache.set(k, content);
      return { content };
    }

    async function go(i, f) {
      if (i < 0 || i >= total) return;
      if (ready) saveProgress();
      const mine = ++tok;
      cur = i; ready = false;
      $('.rbar.top .sub b', el).textContent = headerTitle(i);
      $('.rbar.bottom [data-act=prev]', el).disabled = i === 0;
      $('.rbar.bottom [data-act=next]', el).disabled = i === total - 1;
      page.replaceChildren(loader());
      el.scrollTop = 0;
      const r = await fetchChapter(i);
      if (mine !== tok || !el.isConnected) return;
      if (r.problem) { page.replaceChildren(errorBox(r.problem, () => go(i, f), r.url)); return; }
      render(r.content, i);
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (mine !== tok) return;
        const max = el.scrollHeight - el.clientHeight;
        el.scrollTop = Math.round((f || 0) * max);
        ready = true;
        updateProgress();
        saveProgress();
      }));
      // Fetch the next chapter quietly so it opens instantly.
      if (i + 1 < total) setTimeout(() => { if (el.isConnected && mine === tok) fetchChapter(i + 1); }, 1500);
    }

    // Bars: tap the text to show/hide; they slide away as you scroll down.
    let lastY = 0;
    el.addEventListener('scroll', () => {
      const y = el.scrollTop;
      if (y > lastY + 12 && y > 120) el.classList.add('bars-off');
      if (el.scrollHeight - el.clientHeight - y < 4) el.classList.remove('bars-off');
      lastY = y;
      if (ready) { updateProgress(); clearTimeout(el._t); el._t = setTimeout(saveProgress, 500); }
    }, { passive: true });
    page.addEventListener('click', e => {
      if (e.target.closest('a, button, summary, details, input, select')) return;
      if (settings.tapZones) {
        const y = e.clientY / window.innerHeight;
        if (y < 0.3) { el.scrollBy({ top: -el.clientHeight * 0.85, behavior: 'smooth' }); return; }
        if (y > 0.7) { el.scrollBy({ top: el.clientHeight * 0.85, behavior: 'smooth' }); return; }
      }
      el.classList.toggle('bars-off');
    });

    $('[data-act=back]', el).onclick = () => back();
    $('.rbar.bottom [data-act=prev]', el).onclick = () => go(cur - 1, 0);
    $('.rbar.bottom [data-act=next]', el).onclick = () => go(cur + 1, 0);
    $('[data-act=toc]', el).onclick = () => {
      const box = h('<div><h3>Chapters</h3><div class="toc"></div></div>');
      const toc = $('.toc', box);
      work.chapters.forEach((c, i) => {
        const b = h(`<button><span class="n">${i + 1}</span><span class="${i === cur ? 'cur' : ''}" style="flex:1">${esc(c.title)}</span></button>`);
        b.onclick = () => { sh.close(); if (i !== cur) go(i, 0); };
        toc.appendChild(b);
      });
      const sh = openSheet(box);
      const curBtn = toc.children[cur];
      if (curBtn) setTimeout(() => curBtn.scrollIntoView({ block: 'center' }), 50);
    };
    $('[data-act=style]', el).onclick = () => {
      const keep = frac;
      const box = h('<div><h3>Reading</h3></div>');
      box.appendChild(readingControls(k => {
        applyReaderStyle(el);
        if (k === 'theme') chromeColors();
        if (k === 'immersive') native('setImmersive', !!settings.immersive);
        if (k === 'keepOn') native('keepScreenOn', !!settings.keepOn);
        // Keep the same place in the text when the layout changes.
        const max = el.scrollHeight - el.clientHeight;
        el.scrollTop = Math.round(keep * max);
      }));
      openSheet(box);
    };

    push(el, {
      onPop() { saveProgress(); leaveChrome(); persist(); },
    });
    applyReaderStyle(el);
    enterChrome();
    updateProgress();
    go(cur, startFrac || 0);
  }

  // ---------------------------------------------------------------- library tab

  function buildLibrary() {
    const el = tabs.library;
    let mode = 'saved';
    let checking = false;
    el.innerHTML = `
      <div class="topbar"><h1>Library</h1><button class="icon-btn" data-act="refresh" aria-label="Check for new chapters">${icon('refresh')}</button></div>
      <div class="wrap"><div class="segwrap"></div><div class="body"></div></div>`;
    const body = $('.body', el);

    function draw() {
      const sw = $('.segwrap', el);
      sw.replaceChildren(seg([['saved', `Saved (${Object.keys(saved).length})`], ['history', `History (${Object.keys(hist).length})`]], mode, v => { mode = v; draw(); }));
      body.innerHTML = '';
      if (mode === 'saved') {
        const list = Object.values(saved).sort((a, b) => (b.newCount || 0) - (a.newCount || 0) || b.savedAt - a.savedAt);
        if (!list.length) { body.appendChild(h(`<div class="empty"><b>Nothing saved yet</b><p>Tap the bookmark on any work to keep it here. The refresh button checks saved works for new chapters.</p></div>`)); return; }
        const box = h('<div class="cards" style="margin-top:14px"></div>');
        list.forEach(w => {
          const p = progressOf(hist[w.id]);
          box.appendChild(card(w, {
            badge: w.newCount ? w.newCount + ' new' : '', progress: p ? p.p : null, progressText: p ? p.text : '', noSummary: !!p,
            remove: () => { delete saved[w.id]; persistSoon(); draw(); toast('Removed from library'); },
          }));
        });
        body.appendChild(box);
      } else {
        const list = Object.values(hist).sort((a, b) => b.at - a.at);
        if (!list.length) { body.appendChild(h(`<div class="empty"><b>No history yet</b><p>Works you read show up here with your place saved.</p></div>`)); return; }
        const box = h('<div class="cards" style="margin-top:14px"></div>');
        list.forEach(w => {
          const p = progressOf(w);
          box.appendChild(card(w, {
            progress: p.p, progressText: p.text, noTags: true, noSummary: true,
            remove: () => { delete hist[w.id]; persistSoon(); draw(); },
          }));
        });
        body.appendChild(box);
      }
    }

    $('[data-act=refresh]', el).onclick = async () => {
      if (checking) return;
      const ids = Object.keys(saved);
      if (!ids.length) { toast('Save some works first'); return; }
      checking = true;
      const btn = $('[data-act=refresh]', el);
      btn.style.animation = 'spin 1s linear infinite';
      let found = 0, failed = 0;
      for (let n = 0; n < ids.length; n++) {
        const id = ids[n];
        toast(`Checking ${n + 1} of ${ids.length}…`);
        const res = await net.get(AO3.workUrl(id), { fresh: true });
        if (res.ok) {
          const w = AO3.parseWork(res.body, id);
          if (!w.error && saved[id]) {
            const known = saved[id].knownChapters || w.chaptersPosted;
            const extra = Math.max(0, w.chaptersPosted - known);
            Object.assign(saved[id], snapshot(w), { newCount: extra, knownChapters: known });
            works.set(id, w); cacheContent(w);
            if (extra) found++;
          }
        } else {
          failed++;
          if (res.problem && res.problem.kind === 'rate') break;
        }
        // Be gentle with AO3.
        await new Promise(r => setTimeout(r, 1200));
      }
      checking = false;
      btn.style.animation = '';
      persistSoon();
      draw();
      toast(found ? `${found} work${found > 1 ? 's have' : ' has'} new chapters` : failed ? `Could not check ${failed} work${failed > 1 ? 's' : ''}` : 'No new chapters');
    };

    el.onShow = draw;
    draw();
  }

  // ---------------------------------------------------------------- settings tab

  function buildSettings() {
    const el = tabs.settings;
    el.innerHTML = `<div class="topbar"><h1>Settings</h1></div><div class="wrap"></div>`;
    const wrap = $('.wrap', el);
    const section = t => wrap.appendChild(h(`<div class="section-title">${esc(t)}</div>`));
    const panel = () => wrap.appendChild(h('<div class="panel"></div>'));
    const row = (p, ic, label, sub, fn, cls) => {
      const b = h(`<button class="row-btn ${cls || ''}">${icon(ic)}<span>${esc(label)}${sub ? `<small>${esc(sub)}</small>` : ''}</span></button>`);
      b.onclick = fn; p.appendChild(b);
    };

    section('AO3 account');
    let p = panel();
    row(p, 'user', 'Log in to AO3', 'Only needed for works limited to registered users', () => native('openBrowser', loginUrl()));
    row(p, 'shield', 'Open AO3 in the app browser', 'Use this if AO3 asks to check your connection', () => native('openBrowser', AO3.HOME));
    row(p, 'logout', 'Log out', 'Clears AO3 cookies from this app', () => { native('clearCookies'); net.cache.clear(); toast('Logged out'); });

    section('Reading');
    const rc = h('<div class="panel" style="padding:16px"></div>');
    rc.appendChild(readingControls());
    wrap.appendChild(rc);

    section('Search');
    const sp = h('<div class="panel" style="padding:0 14px"></div>');
    const toggle = (key, label, sub, after) => {
      const r = h(`<label class="toggle-row"><span>${esc(label)}${sub ? `<small>${esc(sub)}</small>` : ''}</span><span class="switch"><input type="checkbox"><i></i></span></label>`);
      const i = $('input', r); i.checked = !!settings[key];
      i.onchange = () => { settings[key] = i.checked; persistSoon(); if (after) after(); };
      sp.appendChild(r);
    };
    toggle('rememberFilters', 'Remember filters', 'Keep your filters for next time');
    toggle('compact', 'Compact results', 'Hide tags on result cards');
    wrap.appendChild(sp);

    section('Your data');
    p = panel();
    row(p, 'up', 'Export library', 'Share your saved works and history as text', () => {
      const data = JSON.stringify({ app: 'fic-reader', v: 1, saved, history: hist });
      native('share', 'Fic Reader library', data);
      if (!N) console.log(data);
    });
    row(p, 'down', 'Import library', 'Paste text from an export', () => importSheet());
    row(p, 'clock', 'Clear search history', '', () => { recent = []; persist(); toast('Search history cleared'); });
    row(p, 'trash', 'Clear reading history', '', () => {
      if (!confirm('Forget every work you have read and your place in it?')) return;
      for (const k of Object.keys(hist)) delete hist[k];
      persist(); toast('Reading history cleared');
    }, 'danger');

    el.onShow = () => { const y = el.scrollTop; buildSettings(); el.scrollTop = y; };
    wrap.appendChild(h(`<p class="result-meta" style="text-align:center;margin-top:26px">Fic Reader 1.2 · Reads works from archiveofourown.org.<br>Not made by or affiliated with AO3 or the OTW.</p>`));
  }

  function importSheet() {
    const box = h(`<div><h3>Import library</h3>
      <div class="field"><textarea rows="7" style="width:100%;border-radius:12px;background:var(--surface-2);border:1px solid var(--line);padding:12px;outline:0" placeholder="Paste the exported text here"></textarea></div>
      <div class="sheet-actions"><button class="btn">Cancel</button><button class="btn primary">Import</button></div></div>`);
    const sh = openSheet(box);
    const [cancel, ok] = $$('.sheet-actions button', box);
    cancel.onclick = () => sh.close();
    ok.onclick = () => {
      try {
        const raw = $('textarea', box).value;
        const d = JSON.parse(raw.slice(raw.indexOf('{')));
        let n = 0;
        for (const [k, v] of Object.entries(d.saved || {})) { if (v && v.id) { saved[k] = Object.assign(saved[k] || {}, v); n++; } }
        for (const [k, v] of Object.entries(d.history || {})) { if (v && v.id && (!hist[k] || hist[k].at < v.at)) { hist[k] = v; n++; } }
        persist(); sh.close(); toast(`Imported ${n} item${n === 1 ? '' : 's'}`);
      } catch (e) { toast('That does not look like a Fic Reader export'); }
    };
  }

  // ---------------------------------------------------------------- global link handling

  document.addEventListener('click', e => {
    const a = e.target.closest('a[href]');
    if (!a) return;
    e.preventDefault();
    const href = a.getAttribute('href');
    if (href.startsWith('#u-')) {
      const t = document.getElementById(href.slice(1));
      if (t) t.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    App.openLink(href);
  });

  // ---------------------------------------------------------------- boot

  function boot() {
    tabs.search = $('#tab-search');
    tabs.library = $('#tab-library');
    tabs.settings = $('#tab-settings');
    const nav = $('#bottom-nav');
    nav.innerHTML = [['search', 'Search'], ['library', 'Library'], ['settings', 'Settings']]
      .map(([k, l]) => `<button data-tab="${k}"><span class="pill">${icon(k)}</span>${l}</button>`).join('');
    $$('button', nav).forEach(b => b.onclick = () => switchTab(b.dataset.tab));
    buildSearch();
    buildLibrary();
    buildSettings();
    switchTab('search');
  }

  window.App = {
    net,
    back,
    persist,
    openLink(url) {
      const r = AO3.route(url);
      if (!openRoute(r)) toast('That is not an AO3 link');
    },
    browserClosed() {
      net.cache.clear();
      if (lastRetry) { const f = lastRetry; lastRetry = null; f(); }
    },
  };

  document.addEventListener('DOMContentLoaded', boot);
})();
