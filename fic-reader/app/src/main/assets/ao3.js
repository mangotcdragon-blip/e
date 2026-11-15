/*
 * AO3 helpers: URL building, HTML parsing and sanitising.
 * Everything here works on the HTML that AO3 serves to a logged-out (or logged-in) browser.
 * Selectors are written defensively: if AO3 moves something, the field comes back empty
 * instead of the whole page failing.
 */
(function () {
  'use strict';

  const HOME = 'https://archiveofourown.org';

  const RATINGS = [
    { id: '10', name: 'General Audiences', short: 'G', cls: 'g' },
    { id: '11', name: 'Teen And Up Audiences', short: 'T', cls: 't' },
    { id: '12', name: 'Mature', short: 'M', cls: 'm' },
    { id: '13', name: 'Explicit', short: 'E', cls: 'e' },
    { id: '9', name: 'Not Rated', short: '?', cls: 'nr' },
  ];

  const SORTS = [
    { v: '_score', label: 'Best match' },
    { v: 'revised_at', label: 'Recently updated' },
    { v: 'created_at', label: 'Recently posted' },
    { v: 'kudos_count', label: 'Most kudos' },
    { v: 'hits', label: 'Most hits' },
    { v: 'bookmarks_count', label: 'Most bookmarks' },
    { v: 'comments_count', label: 'Most comments' },
    { v: 'word_count', label: 'Longest' },
    { v: 'title_to_sort_on', label: 'Title' },
  ];

  const LANGUAGES = [
    ['', 'Any language'], ['en', 'English'], ['es', 'Español'], ['fr', 'Français'],
    ['de', 'Deutsch'], ['it', 'Italiano'], ['pt-BR', 'Português (BR)'], ['pt-PT', 'Português (PT)'],
    ['ru', 'Русский'], ['pl', 'Polski'], ['zh', '中文'], ['ja', '日本語'], ['ko', '한국어'],
    ['vi', 'Tiếng Việt'], ['id', 'Bahasa Indonesia'], ['tr', 'Türkçe'], ['uk', 'Українська'],
    ['nl', 'Nederlands'], ['sv', 'Svenska'], ['fi', 'Suomi'], ['ar', 'العربية'],
  ];

  // ---------------------------------------------------------------- URLs

  function abs(href) {
    if (!href) return '';
    try { return new URL(href, HOME + '/').toString(); } catch (e) { return ''; }
  }

  function qs(params) {
    const out = [];
    for (const [k, v] of params) {
      if (v === undefined || v === null || v === '') continue;
      out.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
    }
    return out.join('&');
  }

  function wordRange(min, max) {
    min = String(min || '').replace(/\D/g, '');
    max = String(max || '').replace(/\D/g, '');
    if (min && max) return min + '-' + max;
    if (min) return '>' + (Number(min) - 1 < 0 ? 0 : Number(min) - 1);
    if (max) return '<' + (Number(max) + 1);
    return '';
  }

  /** f: the filter object kept by the search screen. */
  function searchUrl(f, page) {
    const p = [];
    const ws = (k, v) => p.push(['work_search[' + k + ']', v]);
    ws('query', (f.query || '').trim());
    ws('title', f.title);
    ws('creators', f.creators);
    ws('fandom_names', f.fandoms);
    ws('relationship_names', f.relationships);
    ws('character_names', f.characters);
    ws('freeform_names', f.freeforms);

    // Ratings: one picked -> filter by it. Several picked -> exclude the rest.
    const picked = f.ratings || [];
    const excluded = (f.excluded || '').split(',').map(s => s.trim()).filter(Boolean);
    if (picked.length === 1) {
      ws('rating_ids', picked[0]);
    } else if (picked.length > 1 && picked.length < RATINGS.length) {
      for (const r of RATINGS) if (!picked.includes(r.id)) excluded.push(r.name);
    }
    ws('excluded_tag_names', excluded.join(','));

    ws('complete', f.complete);         // '' | 'T' | 'F'
    ws('crossover', f.crossover);       // '' | 'F' | 'T'
    ws('language_id', f.language);
    ws('word_count', wordRange(f.minWords, f.maxWords));
    if (f.minKudos) ws('kudos_count', '>' + (Number(f.minKudos) - 1));
    ws('sort_column', f.sort || '_score');
    ws('sort_direction', f.sort === 'title_to_sort_on' ? 'asc' : 'desc');
    p.push(['commit', 'Search']);
    if (page > 1) p.push(['page', page]);
    return HOME + '/works/search?' + qs(p);
  }

  /** Adds or replaces ?page=N on a listing URL. */
  function withPage(url, page) {
    const u = new URL(url);
    if (page > 1) u.searchParams.set('page', page); else u.searchParams.delete('page');
    return u.toString();
  }

  /** AO3 escapes some characters in tag URLs. */
  function tagNameFromHref(href) {
    const m = /\/tags\/([^/?#]+)/.exec(href || '');
    if (!m) return '';
    let s;
    try { s = decodeURIComponent(m[1].replace(/\+/g, ' ')); } catch (e) { s = m[1]; }
    return s.replace(/\*s\*/g, '/').replace(/\*a\*/g, '&').replace(/\*d\*/g, '.')
      .replace(/\*q\*/g, '?').replace(/\*h\*/g, '#');
  }

  function tagWorksUrl(tagName, sort, complete) {
    const p = [['commit', 'Sort and Filter'], ['tag_id', tagName]];
    if (sort) p.push(['work_search[sort_column]', sort]);
    if (complete) p.push(['work_search[complete]', complete]);
    return HOME + '/works?' + qs(p);
  }

  function workUrl(id) { return HOME + '/works/' + id + '?view_adult=true'; }
  function chapterUrl(id, cid) { return HOME + '/works/' + id + '/chapters/' + cid + '?view_adult=true'; }

  /**
   * Works out what an AO3 link points at.
   * Returns {type:'work', id, chapter} | {type:'tag', name} | {type:'user', url, name}
   *       | {type:'series', url} | {type:'search', url} | {type:'external', url} | null
   */
  function route(input) {
    if (!input) return null;
    let s = String(input).trim();
    if (/^\d{3,}$/.test(s)) return { type: 'work', id: s, chapter: null };
    const found = /https?:\/\/[^\s<>"']+/.exec(s);
    if (found) s = found[0];
    let u;
    try { u = new URL(s, HOME + '/'); } catch (e) { return null; }
    if (!/(^|\.)archiveofourown\.org$/.test(u.hostname) && !/^ao3\.org$/.test(u.hostname)) {
      return /^https?:$/.test(u.protocol) ? { type: 'external', url: u.toString() } : null;
    }
    const path = u.pathname;
    let m;
    if ((m = /^\/(?:collections\/[^/]+\/)?works\/(\d+)(?:\/chapters\/(\d+))?/.exec(path))) {
      return { type: 'work', id: m[1], chapter: m[2] || null };
    }
    if ((m = /^\/chapters\/(\d+)/.exec(path))) return { type: 'chapter-only', chapter: m[1] };
    if (/^\/tags\/[^/]+/.test(path)) return { type: 'tag', name: tagNameFromHref(path) };
    if ((m = /^\/users\/([^/]+)(?:\/pseuds\/([^/]+))?/.exec(path))) {
      const base = HOME + '/users/' + m[1] + (m[2] ? '/pseuds/' + m[2] : '') + '/works';
      return { type: 'user', url: base, name: decodeURIComponent(m[2] || m[1]) };
    }
    if ((m = /^\/series\/(\d+)/.exec(path))) return { type: 'series', url: HOME + '/series/' + m[1] };
    if (/^\/works(\/search)?$/.test(path) && u.search) return { type: 'search', url: u.toString() };
    return { type: 'external', url: u.toString() };
  }

  // ---------------------------------------------------------------- sanitising

  const ALLOWED = new Set(('p br em i strong b u s strike del ins sup sub small big blockquote hr ' +
    'h1 h2 h3 h4 h5 h6 ul ol li dl dt dd a img span div center table thead tbody tfoot tr td th ' +
    'caption pre code abbr acronym cite q details summary figure figcaption address kbd var tt ruby rt rp ' +
    'col colgroup mark wbr').split(' '));
  const DROP = new Set(('script style iframe object embed form input button select textarea audio video ' +
    'source track noscript template link meta base frame frameset applet svg math canvas').split(' '));
  const ATTRS = {
    '*': ['title', 'dir', 'lang', 'align'],
    a: ['href'],
    img: ['src', 'alt', 'width', 'height'],
    td: ['colspan', 'rowspan'], th: ['colspan', 'rowspan'],
    ol: ['start', 'type'], li: ['value'],
    abbr: ['title'],
  };

  function safeUrl(v, forImg) {
    const a = abs(v);
    if (!/^https?:/i.test(a)) return '';
    if (forImg && /^http:/i.test(a)) return a.replace(/^http:/i, 'https:');
    return a;
  }

  function cleanNode(src, doc) {
    if (src.nodeType === 3) return doc.createTextNode(src.nodeValue);
    if (src.nodeType !== 1) return null;
    const tag = src.tagName.toLowerCase();
    if (DROP.has(tag)) return null;
    if (!ALLOWED.has(tag)) {
      const frag = doc.createDocumentFragment();
      for (const c of src.childNodes) { const k = cleanNode(c, doc); if (k) frag.appendChild(k); }
      return frag;
    }
    const el = doc.createElement(tag);
    const allow = (ATTRS['*']).concat(ATTRS[tag] || []);
    for (const a of src.attributes) {
      const n = a.name.toLowerCase();
      if (!allow.includes(n)) continue;
      let v = a.value;
      if (n === 'href') {
        // In-page anchors (footnotes) stay in-page; ids are prefixed so they cannot clash with the app.
        if (/^#[\w.:-]+$/.test(v)) v = '#u-' + v.slice(1);
        else { v = safeUrl(v, false); if (!v) continue; }
      }
      if (n === 'src') { v = safeUrl(v, true); if (!v) continue; }
      if (n === 'align' && !/^(left|right|center|justify)$/i.test(v)) continue;
      el.setAttribute(n, v);
    }
    const anchor = src.getAttribute('id') || (tag === 'a' && src.getAttribute('name'));
    if (anchor && /^[\w.:-]+$/.test(anchor)) el.setAttribute('id', 'u-' + anchor);
    if (tag === 'img') { el.setAttribute('loading', 'lazy'); el.setAttribute('referrerpolicy', 'no-referrer'); }
    // AO3 sometimes centres with inline style; keep just that one property.
    const st = src.getAttribute('style') || '';
    if (/text-align\s*:\s*(center|right|justify)/i.test(st)) {
      el.setAttribute('align', /center/i.test(st) ? 'center' : /right/i.test(st) ? 'right' : 'justify');
    }
    for (const c of src.childNodes) { const k = cleanNode(c, doc); if (k) el.appendChild(k); }
    return el;
  }

  /** Returns safe HTML for a piece of AO3 user content (summary, notes, chapter text). */
  function clean(node) {
    if (!node) return '';
    const doc = document.implementation.createHTMLDocument('');
    const box = doc.createElement('div');
    for (const c of node.childNodes) { const k = cleanNode(c, doc); if (k) box.appendChild(k); }
    return box.innerHTML.trim();
  }

  // ---------------------------------------------------------------- parsing

  function parse(html) { return new DOMParser().parseFromString(html, 'text/html'); }
  const txt = el => (el ? el.textContent.replace(/\s+/g, ' ').trim() : '');
  const num = s => { const n = parseInt(String(s || '').replace(/[^\d]/g, ''), 10); return isNaN(n) ? 0 : n; };
  const links = (root, sel) => Array.from(root ? root.querySelectorAll(sel) : [])
    .map(a => ({ name: txt(a), href: abs(a.getAttribute('href')) })).filter(t => t.name);

  function ratingFrom(name) {
    name = (name || '').toLowerCase();
    return RATINGS.find(r => r.name.toLowerCase() === name) ||
      RATINGS.find(r => name.includes(r.name.toLowerCase().split(' ')[0])) || RATINGS[4];
  }

  function chaptersInfo(s) {
    const m = /(\d[\d,]*)\s*\/\s*(\d[\d,]*|\?)/.exec(s || '');
    if (!m) return { posted: num(s) || 1, total: null };
    return { posted: num(m[1]), total: m[2] === '?' ? null : num(m[2]) };
  }

  function statsFrom(dl) {
    const g = c => txt(dl && dl.querySelector('dd.' + c));
    const ch = chaptersInfo(g('chapters'));
    return {
      language: g('language'), words: num(g('words')),
      chaptersPosted: ch.posted, chaptersTotal: ch.total,
      comments: num(g('comments')), kudos: num(g('kudos')),
      bookmarks: num(g('bookmarks')), hits: num(g('hits')),
      published: g('published'), status: g('status'),
      statusLabel: txt(dl && dl.querySelector('dt.status')).replace(/:$/, ''),
    };
  }

  /** Parses the work blurbs on a search / tag / user / series page. */
  function parseList(html) {
    const doc = parse(html);
    const items = [];
    const seen = new Set();
    for (const li of doc.querySelectorAll('li.blurb')) {
      const head = li.querySelector('.header .heading') || li.querySelector('h4');
      const titleA = head && Array.from(head.querySelectorAll('a')).find(a => /\/works\/\d+/.test(a.getAttribute('href') || ''));
      if (!titleA) continue; // bookmark of a series/external work, or a deleted work
      const id = /\/works\/(\d+)/.exec(titleA.getAttribute('href'))[1];
      if (seen.has(id)) continue;
      seen.add(id);

      let authors = links(head, 'a[rel="author"]');
      if (!authors.length) {
        const m = /by\s+(.+)$/i.exec(txt(head).replace(txt(titleA), ''));
        authors = [{ name: m ? m[1].replace(/\s*for\s+.*$/i, '') : 'Anonymous', href: '' }];
      }
      const req = li.querySelector('.required-tags');
      const reqTitle = sel => { const e = req && req.querySelector(sel); return e ? (e.getAttribute('title') || txt(e)) : ''; };
      const ratingName = reqTitle('.rating');
      const wip = reqTitle('.iswip');

      const tags = {
        warnings: links(li, 'ul.tags li.warnings a'),
        relationships: links(li, 'ul.tags li.relationships a'),
        characters: links(li, 'ul.tags li.characters a'),
        freeforms: links(li, 'ul.tags li.freeforms a'),
      };
      const stats = statsFrom(li.querySelector('dl.stats'));
      items.push({
        id,
        title: txt(titleA),
        authors,
        fandoms: links(li, '.header .fandoms a.tag'),
        rating: ratingFrom(ratingName).id,
        warningSummary: reqTitle('.warnings'),
        category: reqTitle('.category'),
        complete: /complete/i.test(wip) && !/in progress/i.test(wip)
          ? true : (stats.chaptersTotal && stats.chaptersTotal === stats.chaptersPosted) || false,
        updated: txt(li.querySelector('.header .datetime, p.datetime')),
        tags,
        summary: clean(li.querySelector('blockquote.summary')),
        series: Array.from(li.querySelectorAll('ul.series li')).map(s => {
          const a = s.querySelector('a[href*="/series/"]');
          return { text: txt(s), href: a ? abs(a.getAttribute('href')) : '' };
        }),
        ...stats,
      });
    }

    let total = null;
    for (const h of doc.querySelectorAll('h2.heading, h3.heading, h2, h3')) {
      const t = txt(h);
      let m = /of\s+([\d,]+)\s+Works?/i.exec(t) || /([\d,]+)\s+Works?\s+(found|in|by)/i.exec(t);
      if (m) { total = num(m[1]); break; }
    }
    const next = !!doc.querySelector('ol.pagination li.next a, ol.pagination a[rel="next"]');
    const heading = txt(doc.querySelector('#main h2.heading'));
    const seriesTitle = txt(doc.querySelector('#main .series h2.heading, #main > h2.heading'));
    return { items, total, next, heading, seriesTitle, doc };
  }

  /** Text and notes of the chapter(s) shown on a work or chapter page. */
  function parseChapters(doc) {
    const box = doc.querySelector('#chapters');
    if (!box) return [];
    const blocks = Array.from(box.children).filter(e => e.classList.contains('chapter') && !e.classList.contains('preface'));
    const textOf = scope => {
      const el = Array.from(scope.querySelectorAll('.userstuff'))
        .find(u => u.tagName === 'DIV' && !u.closest('.preface') && u.closest('#chapters') === box);
      if (!el) return '';
      const c = el.cloneNode(true);
      c.querySelectorAll('h3.landmark, .landmark').forEach(n => n.remove());
      return clean(c);
    };
    if (!blocks.length) {
      return [{ title: '', summary: '', notes: '', endNotes: '', html: textOf(box) }];
    }
    return blocks.map(ch => {
      const pre = Array.from(ch.querySelectorAll('.chapter.preface'));
      const start = pre.find(p => !p.querySelector('.end.notes')) || null;
      const end = pre.find(p => p.querySelector('.end.notes')) || null;
      const startNotes = start && (start.querySelector('#notes blockquote.userstuff') ||
        start.querySelector('.notes:not(.end) blockquote.userstuff'));
      return {
        title: txt(ch.querySelector('h3.title')),
        summary: clean(start && start.querySelector('#summary blockquote.userstuff, .summary blockquote.userstuff')),
        notes: clean(startNotes),
        endNotes: clean(end && end.querySelector('.end.notes blockquote.userstuff')),
        html: textOf(ch),
      };
    });
  }

  function parseWork(html, id) {
    const doc = parse(html);
    const pre = Array.from(doc.querySelectorAll('div.preface.group')).find(p => p.querySelector('h2.title')) ||
      doc.querySelector('div.preface.group');
    const title = txt(doc.querySelector('h2.title.heading') || (pre && pre.querySelector('h2')));
    if (!title) return { error: describeProblem(doc) };

    const byline = doc.querySelector('h3.byline');
    let authors = links(byline, 'a[rel="author"]');
    if (!authors.length) authors = [{ name: txt(byline) || 'Anonymous', href: '' }];

    const meta = doc.querySelector('dl.work.meta') || doc.querySelector('dl.meta');
    const mt = c => links(meta, 'dd.' + c + ' a.tag');
    const stats = statsFrom(meta && (meta.querySelector('dl.stats') || meta));
    const ratingTags = mt('rating');

    let chapters = Array.from(doc.querySelectorAll('#selected_id option')).map((o, i) => ({
      id: o.getAttribute('value'), title: txt(o).replace(/^\d+\.\s*/, '') || 'Chapter ' + (i + 1),
    }));
    let selected = 0;
    const sel = doc.querySelector('#selected_id option[selected]');
    if (sel) selected = Math.max(0, chapters.findIndex(c => c.id === sel.getAttribute('value')));
    if (!chapters.length) {
      // One-shot, or "entire work" view: build the list from the chapter blocks / links.
      const blocks = Array.from(doc.querySelectorAll('#chapters > div.chapter[id^="chapter-"]'));
      chapters = blocks.map((b, i) => {
        const a = b.querySelector('h3.title a');
        const m = a && /chapters\/(\d+)/.exec(a.getAttribute('href') || '');
        return { id: m ? m[1] : null, title: txt(b.querySelector('h3.title')).replace(/^Chapter\s+\d+\s*:?\s*/i, '') || 'Chapter ' + (i + 1) };
      });
      if (!chapters.length) chapters = [{ id: null, title }];
    }

    const seriesEls = meta ? Array.from(meta.querySelectorAll('dd.series span.series')) : [];
    if (!seriesEls.length && meta) seriesEls.push(...meta.querySelectorAll('dd.series .position'));
    const series = seriesEls.map(s => {
      const a = Array.from(s.querySelectorAll('a')).find(x => /\/series\/\d+/.test(x.getAttribute('href') || ''));
      return { text: txt(s.querySelector('.position') || s), href: a ? abs(a.getAttribute('href')) : '' };
    }).filter(s => s.text);

    const content = parseChapters(doc);
    const notesEl = pre && pre.querySelector('.notes blockquote.userstuff');

    return {
      id: String(id),
      title,
      authors,
      fandoms: mt('fandom'),
      rating: ratingFrom(ratingTags[0] && ratingTags[0].name).id,
      tags: {
        warnings: mt('warning'),
        categories: mt('category'),
        relationships: mt('relationship'),
        characters: mt('character'),
        freeforms: mt('freeform'),
      },
      collections: links(meta, 'dd.collections a'),
      series,
      summary: clean(pre && pre.querySelector('.summary blockquote.userstuff')),
      notes: clean(notesEl),
      endNotes: clean(doc.querySelector('#work_endnotes blockquote.userstuff')),
      chapters,
      selected,
      content,            // chapter blocks shown on this page
      complete: stats.chaptersTotal != null && stats.chaptersTotal === stats.chaptersPosted,
      ...stats,
      updated: stats.status || stats.published,
    };
  }

  function parseChapterPage(html) {
    const doc = parse(html);
    const content = parseChapters(doc);
    if (!content.length || !content[0].html) return { error: describeProblem(doc) };
    let selectedId = null;
    const sel = doc.querySelector('#selected_id option[selected]');
    if (sel) selectedId = sel.getAttribute('value');
    return { content, selectedId };
  }

  /** Tries to say why a page did not contain a work. */
  function describeProblem(doc) {
    const t = txt(doc.body).slice(0, 3000);
    if (/only available to registered users|log in to view|restricted/i.test(t) || doc.querySelector('#loginform, form#new_user'))
      return { kind: 'login', message: 'This work is only visible to logged-in AO3 users.' };
    if (/adult content|proceed/i.test(t) && doc.querySelector('p.caution'))
      return { kind: 'adult', message: 'AO3 asked to confirm adult content.' };
    if (/retry later|too many requests/i.test(t)) return { kind: 'rate', message: 'AO3 says to slow down. Wait a minute and try again.' };
    if (/bad gateway|gateway time-?out|web server is down|host error|origin is unreachable|connection timed out|down for maintenance|archive is down/i.test(t))
      return { kind: 'down', message: 'AO3\'s servers are not responding right now. This is on AO3\'s side, not your phone or the app. Try again in a few minutes.' };
    if (/temporarily denied|just a moment|checking your browser|verify you are human|cf-chl|challenge-platform/i.test(t + ' ' + (doc.documentElement ? doc.documentElement.innerHTML.slice(0, 5000) : '')))
      return { kind: 'check', message: 'AO3 wants to check this connection first.' };
    if (/404|can't find|could not be found|has been deleted/i.test(t))
      return { kind: 'missing', message: 'This work could not be found. It may have been deleted.' };
    const flash = txt(doc.querySelector('.flash.error, .flash.notice, .error'));
    return { kind: 'unknown', message: flash || 'AO3 sent a page this app could not read.' };
  }

  window.AO3 = {
    HOME, RATINGS, SORTS, LANGUAGES,
    abs, searchUrl, withPage, tagNameFromHref, tagWorksUrl, workUrl, chapterUrl, route,
    clean, parse, parseList, parseWork, parseChapterPage, describeProblem, ratingFrom,
  };
})();
