(() => {
  "use strict";

  const STORAGE_KEY = "customAutocorrectRules";
  const IDLE_DELAY_MS = 3000;

  const editor = document.getElementById("editor");
  const statusEl = document.getElementById("status");
  const ruleCountEl = document.getElementById("ruleCount");
  const ruleForm = document.getElementById("ruleForm");
  const fromInput = document.getElementById("fromInput");
  const toInput = document.getElementById("toInput");
  const ruleList = document.getElementById("ruleList");
  const exportBtn = document.getElementById("exportBtn");
  const importBtn = document.getElementById("importBtn");
  const importFile = document.getElementById("importFile");
  const clearBtn = document.getElementById("clearBtn");

  /** @type {{from: string, to: string}[]} */
  let rules = [];
  /** lowercase(from) -> to */
  const ruleMap = new Map();
  let idleTimer = null;
  let statusTimer = null;

  function isValidRule(r) {
    return (
      r &&
      typeof r.from === "string" &&
      typeof r.to === "string" &&
      r.from.trim() !== ""
    );
  }

  function rebuildMap() {
    ruleMap.clear();
    for (const r of rules) ruleMap.set(r.from.toLowerCase(), r.to);
  }

  function loadRules() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) rules = parsed.filter(isValidRule);
      }
    } catch {
      rules = [];
    }
    rebuildMap();
  }

  function saveRules() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(rules));
  }

  function renderRules() {
    ruleList.innerHTML = "";
    if (rules.length === 0) {
      const empty = document.createElement("li");
      empty.className = "empty-hint";
      empty.textContent = "No custom words yet. Add one above, or import a JSON file.";
      ruleList.appendChild(empty);
    } else {
      for (const rule of rules) {
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
        ruleList.appendChild(li);
      }
    }
    ruleCountEl.textContent = `${rules.length} word${rules.length === 1 ? "" : "s"}`;
  }

  function upsertRule(from, to) {
    const idx = rules.findIndex((r) => r.from.toLowerCase() === from.toLowerCase());
    if (idx >= 0) rules[idx] = { from, to };
    else rules.push({ from, to });
    rebuildMap();
    saveRules();
    renderRules();
  }

  function removeRule(from) {
    rules = rules.filter((r) => r.from.toLowerCase() !== from.toLowerCase());
    rebuildMap();
    saveRules();
    renderRules();
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

    const replacement = ruleMap.get(found.word.toLowerCase());
    if (replacement === undefined || replacement === found.word) return false;

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

  exportBtn.addEventListener("click", () => {
    const blob = new Blob([JSON.stringify(rules, null, 2)], {
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
  });

  importBtn.addEventListener("click", () => importFile.click());

  importFile.addEventListener("change", async () => {
    const file = importFile.files && importFile.files[0];
    importFile.value = "";
    if (!file) return;

    try {
      const text = await file.text();
      const parsed = JSON.parse(text);
      if (!Array.isArray(parsed)) throw new Error("Expected a JSON array of rules");
      const valid = parsed.filter(isValidRule);
      if (valid.length === 0) throw new Error("No valid rules found in file");
      for (const r of valid) upsertRule(r.from, r.to);
      flashStatus(`Imported ${valid.length} word${valid.length === 1 ? "" : "s"}`);
    } catch (err) {
      alert(`Could not import file: ${err.message}`);
    }
  });

  clearBtn.addEventListener("click", () => {
    if (rules.length === 0) return;
    if (!confirm("Remove all custom words? This cannot be undone.")) return;
    rules = [];
    rebuildMap();
    saveRules();
    renderRules();
  });

  // ---- Init ----

  loadRules();
  renderRules();
})();
