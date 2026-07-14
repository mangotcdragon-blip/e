(() => {
  "use strict";

  const DB_NAME = "customAutocorrectDB";
  const STORE_NAME = "rules";
  const DB_VERSION = 1;
  const IDLE_DELAY_MS = 3000;
  const PAGE_SIZE = 200;

  const editor = document.getElementById("editor");
  const statusEl = document.getElementById("status");
  const ruleCountEl = document.getElementById("ruleCount");
  const ruleForm = document.getElementById("ruleForm");
  const fromInput = document.getElementById("fromInput");
  const toInput = document.getElementById("toInput");
  const searchInput = document.getElementById("searchInput");
  const countText = document.getElementById("countText");
  const ruleList = document.getElementById("ruleList");
  const loadMoreBtn = document.getElementById("loadMoreBtn");
  const ioStatus = document.getElementById("ioStatus");
  const exportBtn = document.getElementById("exportBtn");
  const importBtn = document.getElementById("importBtn");
  const importFile = document.getElementById("importFile");
  const clearBtn = document.getElementById("clearBtn");

  /** Source of truth in memory: lowercase(from) -> {from, to}. Kept in sync with IndexedDB. */
  const ruleMap = new Map();
  let db = null;
  let idleTimer = null;
  let statusTimer = null;
  let searchDebounce = null;
  let currentSearch = "";
  let renderedCount = PAGE_SIZE;

  // ---- IndexedDB (avoids localStorage's ~5-10MB quota; handles hundreds of thousands of rows) ----

  function openDB() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const database = req.result;
        if (!database.objectStoreNames.contains(STORE_NAME)) {
          database.createObjectStore(STORE_NAME, { keyPath: "key" });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  function dbLoadAll() {
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, "readonly");
      const req = tx.objectStore(STORE_NAME).openCursor();
      const records = [];
      req.onsuccess = (e) => {
        const cursor = e.target.result;
        if (cursor) {
          records.push(cursor.value);
          cursor.continue();
        } else {
          resolve(records);
        }
      };
      req.onerror = () => reject(req.error);
    });
  }

  // "relaxed" durability skips the strict per-commit fsync guarantee (data could in theory
  // be lost on an OS crash in the split second after commit, but the store can't corrupt).
  // For a bulk import from a file that still exists on disk as the source of truth, that
  // tradeoff is fine, and it's dramatically faster for large writes in Chromium.
  function writeTx() {
    try {
      return db.transaction(STORE_NAME, "readwrite", { durability: "relaxed" });
    } catch {
      return db.transaction(STORE_NAME, "readwrite");
    }
  }

  function dbBulkPut(records) {
    return new Promise((resolve, reject) => {
      const tx = writeTx();
      const store = tx.objectStore(STORE_NAME);
      for (const r of records) store.put(r);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  const IMPORT_CHUNK_SIZE = 20000;

  /**
   * Writes records in chunked transactions rather than one giant transaction.
   * A single transaction holding hundreds of thousands of queued puts is slow in
   * practice (IndexedDB implementations buffer the whole thing before it can start
   * flushing); committing in batches is faster and lets the UI stay responsive and
   * show progress between chunks.
   */
  async function dbBulkPutChunked(records, onProgress) {
    for (let i = 0; i < records.length; i += IMPORT_CHUNK_SIZE) {
      const chunk = records.slice(i, i + IMPORT_CHUNK_SIZE);
      await dbBulkPut(chunk);
      const done = Math.min(i + IMPORT_CHUNK_SIZE, records.length);
      if (onProgress) onProgress(done, records.length);
      await nextFrame();
    }
  }

  function dbDelete(key) {
    return new Promise((resolve, reject) => {
      const tx = writeTx();
      tx.objectStore(STORE_NAME).delete(key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  function dbClear() {
    return new Promise((resolve, reject) => {
      const tx = writeTx();
      tx.objectStore(STORE_NAME).clear();
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  function toRecord(rule) {
    return { key: rule.from.toLowerCase(), from: rule.from, to: rule.to };
  }

  function isValidRule(r) {
    return (
      r &&
      typeof r.from === "string" &&
      typeof r.to === "string" &&
      r.from.trim() !== ""
    );
  }

  // ---- Rendering: bounded + searchable, so the DOM never has to hold huge lists ----

  function renderRules() {
    const term = currentSearch.trim().toLowerCase();
    const matches = [];
    let totalMatches = 0;
    for (const rule of ruleMap.values()) {
      if (!term || rule.from.toLowerCase().includes(term)) {
        totalMatches++;
        if (matches.length < renderedCount) matches.push(rule);
      }
    }

    const frag = document.createDocumentFragment();
    if (matches.length === 0) {
      const empty = document.createElement("li");
      empty.className = "empty-hint";
      empty.textContent =
        ruleMap.size === 0
          ? "No custom words yet. Add one above, or import a JSON file."
          : "No matches.";
      frag.appendChild(empty);
    } else {
      for (const rule of matches) {
        const li = document.createElement("li");

        const from = document.createElement("span");
        from.className = "from";
        from.textContent = rule.from;

        const arrow = document.createElement("span");
        arrow.className = "arrow";
        arrow.textContent = "→";

        const to = document.createElement("span");
        to.className = "to";
        to.textContent = rule.to;

        const removeBtn = document.createElement("button");
        removeBtn.type = "button";
        removeBtn.className = "remove";
        removeBtn.textContent = "✕";
        removeBtn.setAttribute("aria-label", `Remove ${rule.from}`);
        removeBtn.addEventListener("click", () => removeRule(rule.from));

        li.append(from, arrow, to, removeBtn);
        frag.appendChild(li);
      }
    }
    ruleList.innerHTML = "";
    ruleList.appendChild(frag);

    ruleCountEl.textContent = `${ruleMap.size} word${ruleMap.size === 1 ? "" : "s"}`;
    countText.textContent = term
      ? `${totalMatches} match${totalMatches === 1 ? "" : "es"} for "${currentSearch.trim()}"`
      : `${totalMatches} word${totalMatches === 1 ? "" : "s"} total`;
    loadMoreBtn.hidden = matches.length >= totalMatches;
  }

  searchInput.addEventListener("input", () => {
    clearTimeout(searchDebounce);
    searchDebounce = setTimeout(() => {
      currentSearch = searchInput.value;
      renderedCount = PAGE_SIZE;
      renderRules();
    }, 250);
  });

  loadMoreBtn.addEventListener("click", () => {
    renderedCount += PAGE_SIZE;
    renderRules();
  });

  // ---- Mutations: update the in-memory map instantly, persist to IndexedDB, re-render ----

  async function upsertRule(from, to) {
    const rule = { from, to };
    ruleMap.set(from.toLowerCase(), rule);
    renderRules();
    await dbBulkPut([toRecord(rule)]);
  }

  async function removeRule(from) {
    ruleMap.delete(from.toLowerCase());
    renderRules();
    await dbDelete(from.toLowerCase());
  }

  function flashStatus(message) {
    statusEl.textContent = message;
    statusEl.classList.add("flash");
    clearTimeout(statusTimer);
    statusTimer = setTimeout(() => {
      statusEl.classList.remove("flash");
      statusEl.textContent = "";
    }, 2000);
  }

  function setBusy(busy, message) {
    ioStatus.hidden = !busy;
    ioStatus.textContent = message;
    exportBtn.disabled = busy;
    importBtn.disabled = busy;
    clearBtn.disabled = busy;
  }

  function nextFrame() {
    return new Promise((resolve) => setTimeout(resolve, 0));
  }

  // ---- Autocorrect engine ----

  function isWordChar(ch) {
    return !!ch && /[\p{L}\p{N}']/u.test(ch);
  }

  function findWordBefore(text, idx) {
    let start = idx;
    while (start > 0 && isWordChar(text[start - 1])) start--;
    if (start === idx) return null;
    return { start, end: idx, word: text.slice(start, idx) };
  }

  /**
   * Look up the word ending at `wordEndIdx` and, if it matches a custom rule,
   * replace it in place. `cursorAnchor` is the caret position (>= wordEndIdx)
   * to restore afterwards, shifted by the length delta of the replacement.
   */
  function correctWord(wordEndIdx, cursorAnchor) {
    const text = editor.value;
    const found = findWordBefore(text, wordEndIdx);
    if (!found) return false;

    const rule = ruleMap.get(found.word.toLowerCase());
    if (!rule || rule.to === found.word) return false;
    const replacement = rule.to;

    const before = text.slice(0, found.start);
    const after = text.slice(found.end);
    editor.value = before + replacement + after;

    const delta = replacement.length - found.word.length;
    const newCursor = cursorAnchor + delta;
    editor.setSelectionRange(newCursor, newCursor);

    flashStatus(`Corrected "${found.word}" → "${replacement}"`);
    return true;
  }

  function scheduleIdleCorrection() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      const cursor = editor.selectionStart;
      correctWord(cursor, cursor);
    }, IDLE_DELAY_MS);
  }

  editor.addEventListener("input", () => {
    clearTimeout(idleTimer);

    const cursor = editor.selectionStart;
    const text = editor.value;
    const prevChar = text[cursor - 1];

    if (prevChar && /\s/.test(prevChar)) {
      correctWord(cursor - 1, cursor);
    }

    scheduleIdleCorrection();
  });

  editor.addEventListener("blur", () => {
    clearTimeout(idleTimer);
    const cursor = editor.selectionStart;
    correctWord(cursor, cursor);
  });

  // ---- Rule form ----

  ruleForm.addEventListener("submit", (e) => {
    e.preventDefault();
    const from = fromInput.value.trim();
    const to = toInput.value.trim();
    if (!from || !to) return;
    upsertRule(from, to);
    ruleForm.reset();
    fromInput.focus();
  });

  // ---- Import / export ----

  exportBtn.addEventListener("click", async () => {
    setBusy(true, "Exporting…");
    await nextFrame();
    try {
      const all = [...ruleMap.values()].map((r) => ({ from: r.from, to: r.to }));
      const blob = new Blob([JSON.stringify(all, null, 2)], {
        type: "application/json",
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "autocorrect-dictionary.json";
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } finally {
      setBusy(false, "");
    }
  });

  importBtn.addEventListener("click", () => importFile.click());

  importFile.addEventListener("change", async () => {
    const file = importFile.files && importFile.files[0];
    importFile.value = "";
    if (!file) return;

    setBusy(true, "Importing…");
    await nextFrame();
    try {
      const text = await file.text();
      const parsed = JSON.parse(text);
      const entries = parseImportedEntries(parsed);
      if (entries.length === 0) throw new Error("No valid rules found in file");

      // Single O(n) merge into the in-memory map, then a chunked bulk write to IndexedDB.
      for (const r of entries) ruleMap.set(r.from.toLowerCase(), r);
      await dbBulkPutChunked(entries.map(toRecord), (done, total) => {
        ioStatus.textContent = `Importing… ${done.toLocaleString()} / ${total.toLocaleString()}`;
      });

      currentSearch = "";
      searchInput.value = "";
      renderedCount = PAGE_SIZE;
      renderRules();
      flashStatus(`Imported ${entries.length} word${entries.length === 1 ? "" : "s"}`);
    } catch (err) {
      alert(`Could not import file: ${err.message}`);
    } finally {
      setBusy(false, "");
    }
  });

  /**
   * Accepts either the export format (an array of {from, to} objects) or a
   * plain dictionary object, e.g. {"hello": "myooooms"}.
   */
  function parseImportedEntries(parsed) {
    if (Array.isArray(parsed)) {
      return parsed.filter(isValidRule);
    }
    if (parsed && typeof parsed === "object") {
      return Object.entries(parsed)
        .filter(([from, to]) => typeof from === "string" && from.trim() !== "" && typeof to === "string")
        .map(([from, to]) => ({ from, to }));
    }
    throw new Error("Expected a JSON array of rules or a {word: replacement} object");
  }

  clearBtn.addEventListener("click", async () => {
    if (ruleMap.size === 0) return;
    if (!confirm("Remove all custom words? This cannot be undone.")) return;
    ruleMap.clear();
    currentSearch = "";
    searchInput.value = "";
    renderedCount = PAGE_SIZE;
    renderRules();
    await dbClear();
  });

  // ---- Init ----

  (async () => {
    db = await openDB();
    const records = await dbLoadAll();
    for (const r of records) ruleMap.set(r.key, { from: r.from, to: r.to });
    renderRules();
  })();
})();
