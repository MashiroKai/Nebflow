// chatSearch.js — Chat history search modal.
//
// Header button (or Cmd/Ctrl+F) opens a centered modal (no dimming
// backdrop, no blur). Progressive search: typing or changing a filter
// re-searches after a 300ms debounce — no submit required. Stale
// responses are dropped via an increasing requestId and an
// AbortController.
//
// Searches message history via the REST API (backend is the source of
// truth — localStorage is only a truncated cache):
//   GET /api/sessions?includeUnindexed=1 → session list incl. unindexed
//                                          delegate/subtask/dag sessions
//   GET /api/sessions/{id}/history → full UiMessage list per session
//
// Filters: keyword (case-insensitive substring), agent (session-level, from
// the agentName of each session), tool name (clean names extracted from the
// rich labels present in the searched scope), date range (applies to
// messages that carry a timestamp; tool messages have none and are excluded
// when a date range is set).
// scope=all groups results per session under .search-group-header rows.
// Clicking (or Enter on the .active result) switches to that session and
// scrolls to the message.

import state from './state.js';
import { chatViews } from './chatView.js';
import { switchToSession } from './sidebar.js';
import { t } from './i18n.js';
import { escapeHtml } from './utils.js';

const MAX_RESULTS = 200;
const FETCH_CONCURRENCY = 4;
const DEBOUNCE_MS = 300;
const PAGE_JUMP = 5;

let initialized = false;
let lastResults = [];
let lastTotal = 0;
let activeIndex = -1;
let searchSeq = 0;        // race guard — responses from older runs are dropped
let searchAbort = null;   // AbortController for the in-flight search
let debounceTimer = 0;

/**
 * Typed element lookups (getElementById returns bare HTMLElement).
 * @param {string} id
 * @returns {HTMLInputElement|null}
 */
const inputById = (id) => /** @type {HTMLInputElement|null} */ (document.getElementById(id));
/**
 * @param {string} id
 * @returns {HTMLSelectElement|null}
 */
const selectById = (id) => /** @type {HTMLSelectElement|null} */ (document.getElementById(id));

// ── Init ───────────────────────────────────────────────────
export function initChatSearch() {
  if (initialized) return;
  initialized = true;

  document.getElementById('search-btn')?.addEventListener('click', openSearchModal);
  document.getElementById('search-modal-close')?.addEventListener('click', closeSearchModal);
  const overlay = document.getElementById('search-overlay');
  overlay?.addEventListener('click', (e) => {
    if (e.target === overlay) closeSearchModal();
  });
  // Demoted to a secondary submit (progressive search is primary).
  document.getElementById('search-go')?.addEventListener('click', triggerSearch);

  // Progressive search: typing and filter changes re-search after a 300ms
  // debounce.
  document.getElementById('search-keyword')?.addEventListener('input', scheduleSearch);
  for (const id of ['search-scope', 'search-agent', 'search-tool', 'search-date-from', 'search-date-to']) {
    document.getElementById(id)?.addEventListener('change', scheduleSearch);
  }

  // Keyboard contract, single capture-phase handler active only while the
  // modal is open — also silences background chat shortcuts (arrows, Enter,
  // two-stage Esc, PageUp/Down, focus-trap Tab).
  document.addEventListener('keydown', onModalKeydown, true);

  // Cmd/Ctrl+F opens the modal (Slack mode). Yield to Monaco's own find
  // when a canvas editor has focus.
  document.addEventListener('keydown', (e) => {
    if (!(e.metaKey || e.ctrlKey) || (e.key !== 'f' && e.key !== 'F')) return;
    if (e.target instanceof Element && e.target.closest('.monaco-editor')) return;
    e.preventDefault();
    openSearchModal();
  });

  // Result click — event delegation on the results container
  const resultsEl = document.getElementById('search-results');
  resultsEl?.addEventListener('click', (e) => {
    if (!(e.target instanceof Element)) return;
    const el = /** @type {HTMLElement|null} */ (e.target.closest('.search-result'));
    if (!el) return;
    const res = lastResults[parseInt(el.dataset.idx || '', 10)];
    if (res) jumpToResult(res);
  });
  // Hovering a result clears the keyboard selection.
  resultsEl?.addEventListener('mouseover', (e) => {
    if (e.target instanceof Element && e.target.closest('.search-result')) setActive(-1);
  });
}

// ── Modal open/close ───────────────────────────────────────
export function openSearchModal() {
  const overlay = document.getElementById('search-overlay');
  if (!overlay) return;
  resetSearchState();   // Spotlight mode: every open is a fresh session
  renderStaticLabels();
  overlay.classList.add('on');
  const kw = inputById('search-keyword');
  if (kw) { kw.value = ''; kw.focus(); }
}

export function closeSearchModal() {
  // Abort any in-flight search and drop pending work.
  searchSeq++;
  searchAbort?.abort();
  searchAbort = null;
  clearTimeout(debounceTimer);
  document.getElementById('search-overlay')?.classList.remove('on');
  document.getElementById('search-btn')?.focus();   // return focus to the opener
}

/** Cancel pending/in-flight work and return to the idle (empty) state. */
function resetSearchState() {
  searchSeq++;
  searchAbort?.abort();
  searchAbort = null;
  clearTimeout(debounceTimer);
  lastResults = [];
  lastTotal = 0;
  activeIndex = -1;
  const resultsEl = document.getElementById('search-results');
  resultsEl?.removeAttribute('data-loading');
  document.getElementById('search-keyword')?.removeAttribute('aria-activedescendant');
}

/** Back to the empty state: hint text, no status. */
function showIdle() {
  resetSearchState();
  const statusEl = document.getElementById('search-status');
  if (statusEl) statusEl.textContent = '';
  const resultsEl = document.getElementById('search-results');
  if (resultsEl) resultsEl.innerHTML = `<div class="search-hint">${escapeHtml(t('search.hint'))}</div>`;
}

/** Fill labels/placeholders that depend on the current locale. */
function renderStaticLabels() {
  const scope = document.getElementById('search-scope');
  if (scope) {
    scope.innerHTML =
      `<option value="current">${escapeHtml(t('search.scopeCurrent'))}</option>` +
      `<option value="all">${escapeHtml(t('search.scopeAll'))}</option>`;
  }
  const tool = document.getElementById('search-tool');
  if (tool) {
    tool.innerHTML = `<option value="">${escapeHtml(t('search.allTypes'))}</option>`;
  }
  const agent = document.getElementById('search-agent');
  if (agent) {
    agent.innerHTML = `<option value="">${escapeHtml(t('search.allAgents'))}</option>`;
  }
  const go = document.getElementById('search-go');
  if (go) go.textContent = t('search.go');
  const status = document.getElementById('search-status');
  if (status) status.textContent = '';
  const results = document.getElementById('search-results');
  if (results) {
    results.setAttribute('aria-label', t('search.title'));
    results.innerHTML = `<div class="search-hint">${escapeHtml(t('search.hint'))}</div>`;
  }
}

// ── Keyboard contract ──────────────────────────────────────
function onModalKeydown(e) {
  const overlay = document.getElementById('search-overlay');
  if (!overlay?.classList.contains('on')) return;
  if (e.isComposing) return;   // never intercept IME composition keys

  const modal = document.getElementById('search-modal');
  const ae = document.activeElement;
  // Native keyboard behavior wins inside selects and date inputs.
  const inNativeControl = !!(ae && modal?.contains(ae) &&
    (ae.tagName === 'SELECT' || (ae instanceof HTMLInputElement && ae.type === 'date')));

  switch (e.key) {
    case 'Escape': {
      e.preventDefault();
      e.stopImmediatePropagation();
      const kw = inputById('search-keyword');
      if (kw && kw.value) {
        // Two-stage Esc: first clear the input back to the idle state…
        kw.value = '';
        showIdle();
      } else {
        // …then close the modal.
        closeSearchModal();
      }
      return;
    }
    case 'Enter': {
      // Buttons and selects keep their native Enter behavior.
      if (inNativeControl || ae?.tagName === 'BUTTON') return;
      e.preventDefault();
      e.stopImmediatePropagation();
      if (activeIndex >= 0 && lastResults[activeIndex]) jumpToResult(lastResults[activeIndex]);
      return;
    }
    case 'ArrowDown':
    case 'ArrowUp':
    case 'PageDown':
    case 'PageUp': {
      if (inNativeControl) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      const isPage = e.key === 'PageDown' || e.key === 'PageUp';
      const delta = e.key === 'ArrowDown' ? 1 : e.key === 'ArrowUp' ? -1
        : e.key === 'PageDown' ? PAGE_JUMP : -PAGE_JUMP;
      moveActive(delta, isPage);
      return;
    }
    case 'Tab':
      e.stopImmediatePropagation();
      trapFocus(e);
      return;
    default:
      // Silence background chat shortcuts while the modal is open.
      e.stopImmediatePropagation();
  }
}

/** Move the keyboard selection. Arrows wrap around; Page keys clamp. */
function moveActive(delta, clamp) {
  const n = lastResults.length;
  if (!n) return;
  let i = activeIndex;
  if (i < 0) i = delta > 0 ? 0 : n - 1;
  else if (clamp) i = Math.max(0, Math.min(n - 1, i + delta));
  else i = (i + delta + n) % n;
  setActive(i, true);
}

/** Apply (i >= 0) or clear (i < 0) the keyboard-selected result. */
function setActive(i, scroll = false) {
  const resultsEl = document.getElementById('search-results');
  if (!resultsEl) return;
  const prev = /** @type {HTMLElement|null} */ (resultsEl.querySelector('.search-result.active'));
  if (prev) {
    prev.classList.remove('active');
    prev.setAttribute('aria-selected', 'false');
    prev.tabIndex = -1;
  }
  activeIndex = i;
  const kw = inputById('search-keyword');
  if (i < 0) { kw?.removeAttribute('aria-activedescendant'); return; }
  const el = /** @type {HTMLElement|null} */ (resultsEl.querySelector(`.search-result[data-idx="${i}"]`));
  if (!el) return;
  el.classList.add('active');
  el.setAttribute('aria-selected', 'true');
  el.tabIndex = 0;
  kw?.setAttribute('aria-activedescendant', el.id);
  if (scroll) el.scrollIntoView({ block: 'nearest' });
}

/** Focus trap: Tab/Shift+Tab cycle inside the modal, never escaping to the
 *  background chat area. Order follows DOM order: close button → keyword
 *  input → filters → (retry button) → active result → wrap. */
function trapFocus(e) {
  const modal = document.getElementById('search-modal');
  if (!modal) return;
  const focusables = /** @type {HTMLElement[]} */ ([...modal.querySelectorAll('button, input, select, [tabindex="0"]')])
    .filter(el => !(/** @type {HTMLButtonElement} */ (el).disabled) && el.offsetParent !== null);
  if (!focusables.length) return;
  const first = focusables[0];
  const last = focusables[focusables.length - 1];
  const ae = document.activeElement;
  if (e.shiftKey) {
    if (ae === first || !modal.contains(ae)) { e.preventDefault(); last.focus(); }
  } else if (ae === last || !modal.contains(ae)) {
    e.preventDefault();
    first.focus();
  }
}

// ── Progressive search scheduling ──────────────────────────
function scheduleSearch() {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(triggerSearch, DEBOUNCE_MS);
}

/** Debounced entry point. Empty keyword without any filter → idle state;
 *  empty keyword WITH filters still searches (matches everything). */
function triggerSearch() {
  const kw = (inputById('search-keyword')?.value || '').trim();
  const hasFilters = !!(
    selectById('search-tool')?.value ||
    selectById('search-agent')?.value ||
    inputById('search-date-from')?.value ||
    inputById('search-date-to')?.value
  );
  if (!kw && !hasFilters) { showIdle(); return; }
  runSearch();
}

// ── Data fetching ──────────────────────────────────────────
function authHeaders() {
  const tok = localStorage.getItem('nebflow_token') || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

async function fetchSessionList(signal) {
  // includeUnindexed=1: also returns delegate-*/subtask-*/dag-* sessions whose
  // ui.json exists on disk but which never entered the session index.
  const resp = await fetch('/api/sessions?includeUnindexed=1', { headers: authHeaders(), signal });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const data = await resp.json();
  return data.sessions || [];
}

async function fetchHistory(sessionId, signal) {
  const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/history`, { headers: authHeaders(), signal });
  if (!resp.ok) return [];
  const data = await resp.json();
  return data.messages || [];
}

/** Run async tasks with a concurrency limit. */
async function mapLimit(items, limit, fn, onProgress) {
  const out = [];
  let idx = 0;
  let done = 0;
  async function worker() {
    while (idx < items.length) {
      const item = items[idx++];
      out.push(await fn(item));
      done++;
      onProgress?.(done, items.length);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

// ── Message normalization ──────────────────────────────────
// Extract searchable plain text per UiMessage type (see shared/protocol.scala
// UiMessage encoder for the JSON shape).
function normalizeMessage(m) {
  switch (m.type) {
    case 'user':
      return { kind: 'user', text: m.text || '', ts: m.timestamp || 0 };
    case 'ai':
      return { kind: 'ai', text: (m.text || '') + (m.thinking ? '\n' + m.thinking : ''), ts: m.timestamp || 0 };
    case 'tool':
      return {
        kind: 'tool',
        tool: m.label || '',
        text: [m.summary, m.input, m.content].filter(Boolean).join('\n'),
        ts: 0,
      };
    case 'agent':
      return { kind: 'ai', text: m.text || '', ts: 0 };
    case 'ask':
      return { kind: 'ai', text: [m.question, m.answer].filter(Boolean).join('\n'), ts: 0 };
    default:
      return null; // system/askUser/askPermission etc. are not searchable
  }
}

// ── Search ─────────────────────────────────────────────────
async function runSearch() {
  const mySeq = ++searchSeq;
  searchAbort?.abort();
  const ac = new AbortController();
  searchAbort = ac;

  const kw = (inputById('search-keyword')?.value || '').trim();
  const scope = selectById('search-scope')?.value || 'current';
  const toolSel = selectById('search-tool')?.value || '';
  const agentSel = selectById('search-agent')?.value || '';
  const fromVal = inputById('search-date-from')?.value || '';
  const toVal = inputById('search-date-to')?.value || '';
  const fromTs = fromVal ? new Date(fromVal + 'T00:00:00').getTime() : 0;
  const toTs = toVal ? new Date(toVal + 'T23:59:59.999').getTime() : 0;
  const statusEl = document.getElementById('search-status');
  const resultsEl = document.getElementById('search-results');
  if (!statusEl || !resultsEl) return;

  // Loading state: keep the previous results, dimmed, to avoid flicker.
  resultsEl.dataset.loading = 'true';
  statusEl.textContent = t('search.searching');

  try {
    // Resolve the sessions to search
    let sessions;
    if (scope === 'current') {
      const sid = state.activeSessionId;
      if (!sid) {
        if (mySeq !== searchSeq) return;
        resultsEl.removeAttribute('data-loading');
        resultsEl.innerHTML = `<div class="search-hint">${escapeHtml(t('search.hint'))}</div>`;
        statusEl.textContent = t('search.noSession');
        lastResults = [];
        lastTotal = 0;
        return;
      }
      const local = (state.sessions || []).find(s => s.id === sid);
      sessions = [{ id: sid, name: local?.name || sid }];
    } else {
      sessions = await fetchSessionList(ac.signal);
      // Agent filter options come from the FULL list (before filtering)
      if (mySeq === searchSeq) populateAgentFilter(sessions, agentSel);
      // Filter at the session level — also skips history fetches for
      // sessions that cannot match.
      if (agentSel) sessions = sessions.filter(s => agentNameOf(s) === agentSel);
    }

    // Fetch histories (bounded concurrency), with progress for the all-scope
    const showProgress = sessions.length > 1;
    const perSession = await mapLimit(sessions, FETCH_CONCURRENCY, async (s) => {
      const msgs = await fetchHistory(s.id, ac.signal);
      return { session: s, msgs };
    }, showProgress ? (done, total) => {
      if (mySeq === searchSeq) statusEl.textContent = t('search.progress', { done, total });
    } : null);

    if (mySeq !== searchSeq) return;   // superseded by a newer search

    // Populate the tool filter dropdown from labels actually present
    populateToolFilter(perSession, toolSel);

    // Match
    const kwLower = kw.toLowerCase();
    const results = [];
    for (const { session, msgs } of perSession) {
      for (const raw of msgs) {
        const m = normalizeMessage(raw);
        if (!m) continue;
        if (toolSel === '__any__') {
          if (m.kind !== 'tool') continue;
        } else if (toolSel) {
          if (m.kind !== 'tool' || cleanToolName(m.tool) !== toolSel) continue;
        }
        if ((fromTs || toTs) && m.ts) {
          if (fromTs && m.ts < fromTs) continue;
          if (toTs && m.ts > toTs) continue;
        } else if ((fromTs || toTs) && !m.ts) {
          continue; // no timestamp → cannot satisfy a date range
        }
        if (kwLower && !m.text.toLowerCase().includes(kwLower)) continue;
        results.push({
          sessionId: session.id,
          sessionName: session.name || session.id,
          kind: m.kind,
          tool: m.tool || '',
          text: m.text,
          ts: m.ts,
        });
      }
    }

    // Newest first (timestamped), untimestamped last
    results.sort((a, b) => (b.ts || 0) - (a.ts || 0));
    lastResults = results.slice(0, MAX_RESULTS);
    lastTotal = results.length;

    resultsEl.removeAttribute('data-loading');
    statusEl.textContent = statusSummary();
    renderResults(resultsEl, kw);
  } catch (e) {
    if (e.name === 'AbortError' || mySeq !== searchSeq) return;   // stale/aborted
    resultsEl.removeAttribute('data-loading');
    showError(statusEl, e);
  }
}

/** Status line summary: result count + truncation notice. */
function statusSummary() {
  return t('search.results', { n: lastTotal }) +
    (lastTotal > MAX_RESULTS ? ` (${t('search.truncated', { n: MAX_RESULTS })})` : '');
}

/** Error state: message + an inline retry button (glass control). */
function showError(statusEl, e) {
  statusEl.textContent = `${t('search.failed')}: ${e.message}`;
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'glass-control cfg-btn cfg-btn-sm';
  btn.textContent = t('search.retry');
  btn.addEventListener('click', triggerSearch);
  statusEl.appendChild(btn);
}

// ── Tool name normalization ────────────────────────────────
// ui.json tool labels are RICH display strings, not tool names:
//   "Edit(jarvis.html)\n  (\"/path\")", "Bash\n  (cd \"...\")",
//   "Mail(→Manager) [RESULT]", "TaskUpdate(#1, completed)"
// Deduping/filtering on the full label floods the dropdown with one variant
// per call. Extract the clean tool name — the leading identifier before
// '(' / whitespace / newline.
function cleanToolName(label) {
  const m = /^([A-Za-z_][A-Za-z0-9_-]*)/.exec(label || '');
  return m ? m[1] : (label || '');
}

/** Display name of the agent that owns a session (backend derives it from
 *  the id prefix for unindexed sessions, e.g. "Explorer (delegate)"). */
function agentNameOf(s) {
  return s.agentName || '';
}

/** Rebuild the agent filter options from the fetched session list. */
function populateAgentFilter(sessions, keepValue) {
  const sel = selectById('search-agent');
  if (!sel) return;
  const counts = new Map();
  for (const s of sessions) {
    const name = agentNameOf(s);
    if (name) counts.set(name, (counts.get(name) || 0) + 1);
  }
  const sorted = [...counts.keys()].sort((a, b) => a.localeCompare(b));
  sel.innerHTML =
    `<option value="">${escapeHtml(t('search.allAgents'))}</option>` +
    sorted.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)} (${counts.get(n)})</option>`).join('');
  if ([...sel.options].some(o => o.value === keepValue)) sel.value = keepValue;
}

/** Rebuild the tool filter options from the tool labels in the fetched set.
 *  Options are CLEAN tool names (with call counts), not raw labels. */
function populateToolFilter(perSession, keepValue) {
  const sel = selectById('search-tool');
  if (!sel) return;
  const counts = new Map();
  for (const { msgs } of perSession) {
    for (const raw of msgs) {
      if (raw.type === 'tool' && raw.label) {
        const name = cleanToolName(raw.label);
        if (name) counts.set(name, (counts.get(name) || 0) + 1);
      }
    }
  }
  const sorted = [...counts.keys()].sort();
  sel.innerHTML =
    `<option value="">${escapeHtml(t('search.allTypes'))}</option>` +
    `<option value="__any__">${escapeHtml(t('search.anyTool'))}</option>` +
    sorted.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)} (${counts.get(n)})</option>`).join('');
  // Restore previous selection if still available
  if ([...sel.options].some(o => o.value === keepValue)) sel.value = keepValue;
}

// ── Result rendering ───────────────────────────────────────
function renderResults(container, kw) {
  if (lastResults.length === 0) {
    activeIndex = -1;
    container.innerHTML =
      `<div class="search-hint">${escapeHtml(t('search.noResults'))}<br>` +
      `${escapeHtml(t('search.noResultsSuggestion'))}</div>`;
    return;
  }
  // scope=all groups hits per session (time-desc groups, time-desc within).
  const grouped = (selectById('search-scope')?.value || 'current') === 'all';
  const counts = new Map();
  if (grouped) {
    for (const r of lastResults) counts.set(r.sessionId, (counts.get(r.sessionId) || 0) + 1);
  }
  let html = '';
  let lastSid = null;
  lastResults.forEach((r, i) => {
    if (grouped && r.sessionId !== lastSid) {
      lastSid = r.sessionId;
      html += `<div class="search-group-header">` +
        `<span class="search-result-session">${escapeHtml(r.sessionName)}</span>` +
        ` (${counts.get(r.sessionId)})</div>`;
    }
    const typeBadge = r.kind === 'tool'
      ? `${escapeHtml(t('search.typeTool'))} · ${escapeHtml(cleanToolName(r.tool))}`
      : r.kind === 'user'
        ? escapeHtml(t('search.typeUser'))
        : escapeHtml(t('search.typeAi'));
    const time = r.ts ? formatTime(r.ts) : '';
    html += `<div class="search-result" id="search-result-${i}" data-idx="${i}" role="option" aria-selected="false" tabindex="-1">
      <div class="search-result-head">
        <span class="search-result-session">${escapeHtml(r.sessionName)}</span>
        <span class="search-result-type type-${r.kind}">${typeBadge}</span>
        <span class="search-result-time">${escapeHtml(time)}</span>
      </div>
      <div class="search-result-preview">${highlightPreview(r.text, kw)}</div>
    </div>`;
  });
  container.innerHTML = html;
  setActive(0);   // first result is pre-selected (Spotlight Top Hit)
}

/** Preview snippet centered on the first keyword match, with <mark> highlights. */
function highlightPreview(text, kw) {
  const MAX = 180;
  const flat = (text || '').replace(/\s+/g, ' ').trim();
  let start = 0;
  if (kw) {
    const idx = flat.toLowerCase().indexOf(kw.toLowerCase());
    if (idx > 60) start = idx - 60;
  }
  const slice = flat.slice(start, start + MAX);
  const prefix = start > 0 ? '…' : '';
  const suffix = start + MAX < flat.length ? '…' : '';
  if (!kw) return prefix + escapeHtml(slice) + suffix;

  // Index-based marking on the raw slice (escape each segment separately)
  const kwLower = kw.toLowerCase();
  let html = '';
  let pos = 0;
  const lower = slice.toLowerCase();
  while (pos < slice.length) {
    const idx = lower.indexOf(kwLower, pos);
    if (idx === -1) { html += escapeHtml(slice.slice(pos)); break; }
    html += escapeHtml(slice.slice(pos, idx));
    html += '<mark>' + escapeHtml(slice.slice(idx, idx + kw.length)) + '</mark>';
    pos = idx + kw.length;
  }
  return prefix + html + suffix;
}

function formatTime(ts) {
  const d = new Date(ts);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (sameDay) return hh + ':' + mm;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hh}:${mm}`;
}

// ── Jump to message ────────────────────────────────────────
// DOM matching normalizes away whitespace and non-alphanumeric characters so
// markdown formatting (**bold**, `code`, list bullets) doesn't break the match.
const stripForMatch = (s) => (s || '').replace(/[^\p{L}\p{N}]/gu, '').toLowerCase();

function jumpToResult(res) {
  closeSearchModal();
  switchToSession(res.sessionId);
  scrollToMessage(res, 0);
}

function scrollToMessage(res, attempt) {
  const chat = chatViews.primary?.dom?.chat;
  if (!chat) return;
  const snippet = stripForMatch(res.text).slice(0, 40);
  if (snippet.length >= 4) {
    const rows = chat.querySelectorAll('.row, .tool-card');
    for (const el of rows) {
      if (stripForMatch(el.textContent).includes(snippet)) {
        el.scrollIntoView({ block: 'center', behavior: 'smooth' });
        el.classList.add('search-hit-flash');
        setTimeout(() => el.classList.remove('search-hit-flash'), 1800);
        return;
      }
    }
  }
  // History loads asynchronously after the session switch — retry briefly.
  if (attempt < 12) {
    setTimeout(() => scrollToMessage(res, attempt + 1), 250);
  } else {
    (/** @type {any} */ (window)).__showToast?.(t('search.jumpFailed'), 'error');  }
}

// Re-apply labels when the locale changes while the modal is open.
// Rendered results are re-drawn too so badges/status stay localized.
window.addEventListener('locale-changed', () => {
  if (!document.getElementById('search-overlay')?.classList.contains('on')) return;
  const kw = inputById('search-keyword')?.value || '';
  renderStaticLabels();
  if (lastResults.length) {
    const resultsEl = document.getElementById('search-results');
    const statusEl = document.getElementById('search-status');
    if (resultsEl) renderResults(resultsEl, kw.trim());
    if (statusEl) statusEl.textContent = statusSummary();
  }
});
