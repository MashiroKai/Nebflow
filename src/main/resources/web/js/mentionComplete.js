// mentionComplete.js — @-mention project autocomplete for the chat composer
// (mention-routing batch, 2026-09-27).
//
// Behavior contract:
//   - Typing '@' at WORD START (preceded by whitespace or line start) opens a
//     floating list of mounted projects: name + workspace-path subtitle.
//     'foo@' (mid-word, e.g. an email address) never triggers.
//   - Continued typing filters the list (case-insensitive substring on the
//     project name). The mention token lives from '@' up to the next
//     whitespace — a manually typed space inside the token closes the popup.
//   - ArrowUp/ArrowDown navigate; Tab / Enter / click insert '@<full name> '
//     (one trailing space, cursor lands after it). Names with spaces and
//     Chinese names are inserted verbatim — no quoting, no escaping (the
//     prompt layer resolves the full-name mention).
//   - Escape / blur / outside click close the popup. Zero matches shows a
//     "no matching projects" line (informational only: Enter still sends the
//     message as typed, Tab keeps its default focus behavior).
//
// Data source: fetchProjects() from nodeData.js (GET /api/projects) — the
// SAME single source the projects panel and the task list use; no second
// project list is maintained here. Fetched lazily on first trigger and
// reused via a short-lived cache (60s TTL, one in-flight request, 10s retry
// backoff on failure) so typing never refetches per keystroke.
//
// House rules respected by this module:
//   - IME: no composition events are bound and no raw composition flags are
//     read (scripts/check-ime-guard.mjs). All key handling runs behind the
//     hoisted imeGuard short-circuit in input.js's keydown handler, so keys
//     belonging to an IME session can neither navigate nor commit a pick.
//   - Rendering: dynamic text goes through textContent only (project names
//     and workspace paths are data, never markup).
//   - Styling: the popup mirrors the #slash-dropdown glass recipe in
//     css/input.css (same container, same hover/active family). No new
//     design tokens.

import { t, getLocale } from './i18n.js';
import { fetchProjects } from './nodeData.js';

// ── Project list cache (lazy, ≤60s TTL, single in-flight request) ────────
const MENTION_CACHE_TTL_MS = 60_000;
const MENTION_RETRY_BACKOFF_MS = 10_000;
let cacheProjects = null; // last successful fetch result (array), or null
let cacheAt = 0;          // epoch ms of the last successful fetch (0 = none)
let failAt = 0;           // epoch ms of the last failed fetch (retry backoff)
let inflight = null;      // in-flight fetch promise (request dedup)

/** Resolve the mounted-project list, honoring the TTL cache. Never rejects:
 *  on fetch failure it resolves to the cached list, or [] when nothing is
 *  cached yet (cacheAt stays 0, so the renderer can tell "no projects" from
 *  "list unavailable" and stay quiet in the latter case). */
function getProjectsCached() {
  const now = Date.now();
  if (cacheProjects && now - cacheAt < MENTION_CACHE_TTL_MS) {
    return Promise.resolve(cacheProjects);
  }
  // Failed recently — serve whatever we have instead of hammering the gateway
  // on every keystroke until the backoff window passes.
  if (now - failAt < MENTION_RETRY_BACKOFF_MS) {
    return Promise.resolve(cacheProjects || []);
  }
  if (!inflight) {
    inflight = fetchProjects()
      .then((projects) => {
        cacheProjects = Array.isArray(projects) ? projects : [];
        cacheAt = Date.now();
        failAt = 0;
        return cacheProjects;
      })
      .catch(() => {
        failAt = Date.now();
        return cacheProjects || [];
      })
      .finally(() => { inflight = null; });
  }
  return inflight;
}

// ── Popup state ──────────────────────────────────────────────────────────
// The composer is a singleton (chatViews.primary owns #input; initInput runs
// only for that view), so module-level state is sufficient — the same trade
// the single static #slash-dropdown element already makes. `ctx.view` still
// guards every key path so a foreign view can never drive this popup.
let ddEl = null;      // lazily created dropdown element (#mention-dropdown)
let ctx = null;       // { view, input } while the popup is open
let items = [];       // currently rendered matches
let selected = 0;     // highlighted row index
let fetchSeq = 0;     // supersedes stale async renders after newer input/close
let lastRenderKey = ''; // dedup: identical re-parses must not rebuild/reset rows

const WHITESPACE_RE = /\s/;

/** Active mention token at the caret, or null.
 *  Token = the '@' at word start (preceded by whitespace or line start) up
 *  to the caret, with no whitespace between '@' and the caret. */
function parseMentionToken(input) {
  const caret = input.selectionStart ?? input.value.length;
  const before = input.value.slice(0, caret);
  const at = before.lastIndexOf('@');
  if (at < 0) return null;
  // Word-start gate: 'foo@' (email addresses and the like) never triggers.
  if (at > 0 && !WHITESPACE_RE.test(before[at - 1])) return null;
  // The token ends at the first whitespace — a manually typed space closes.
  if (WHITESPACE_RE.test(before.slice(at + 1))) return null;
  return { start: at, query: before.slice(at + 1) };
}

/** Case-insensitive substring filter on the project name, then a natural
 *  sort in the current locale (Chinese names sort natively, no pinyin). */
function filterProjects(projects, query) {
  const q = query.toLowerCase();
  const locale = getLocale();
  return projects
    .filter((p) => p && typeof p.name === 'string' && p.name.toLowerCase().includes(q))
    .sort((a, b) => String(a.name).localeCompare(String(b.name), locale, { numeric: true }));
}

function ensureDropdownEl() {
  if (ddEl) return ddEl;
  ddEl = document.createElement('div');
  ddEl.id = 'mention-dropdown';
  ddEl.className = 'mention-dropdown';
  ddEl.setAttribute('role', 'listbox');
  ddEl.setAttribute('aria-label', t('mention.title'));
  // Overlay target: same layer as #slash-dropdown (a child of #input-area,
  // which is position:absolute; the dropdown itself is pointer-events:auto).
  const area = document.getElementById('input-area') || document.body;
  area.appendChild(ddEl);
  return ddEl;
}

function syncActiveRow() {
  if (!ddEl) return;
  const rows = ddEl.querySelectorAll('.mention-item');
  rows.forEach((el, i) => {
    const active = i === selected;
    el.classList.toggle('active', active);
    el.setAttribute('aria-selected', String(active));
  });
  if (ctx && items.length > 0) {
    ctx.input.setAttribute('aria-activedescendant', 'mention-opt-' + selected);
  }
  // Keep the highlighted row in view — same visible-range math as the slash
  // dropdown's setSlashHighlight (deliberately NOT scrollIntoView: it can
  // write programmatic scroll into overflow:hidden ancestors).
  const active = rows[selected];
  if (!active) return;
  const top = active.offsetTop;
  const bottom = top + active.offsetHeight;
  if (top < ddEl.scrollTop) {
    ddEl.scrollTop = top;
  } else if (bottom > ddEl.scrollTop + ddEl.clientHeight) {
    ddEl.scrollTop = bottom - ddEl.clientHeight;
  }
}

function buildItem(project, index) {
  const el = document.createElement('div');
  el.className = 'mention-item';
  el.id = 'mention-opt-' + index;
  el.setAttribute('role', 'option');
  el.setAttribute('aria-selected', 'false');
  const name = document.createElement('span');
  name.className = 'mention-name';
  name.textContent = project.name;
  el.appendChild(name);
  if (typeof project.workspace === 'string' && project.workspace) {
    const path = document.createElement('span');
    path.className = 'mention-path';
    path.textContent = project.workspace;
    el.appendChild(path);
  }
  // Pick on mousedown (not click): preventDefault keeps the composer focused
  // — no blur round-trip, and the caret stays ours until pickMention moves it.
  el.addEventListener('mousedown', (e) => {
    e.preventDefault();
    pickMention(index);
  });
  el.addEventListener('mouseenter', () => {
    if (selected !== index) {
      selected = index;
      syncActiveRow();
    }
  });
  return el;
}

/** Force-close the popup and cancel any pending async render. */
export function closeMentionDropdown() {
  fetchSeq++;
  lastRenderKey = '';
  if (ctx && ctx.input) {
    ctx.input.removeAttribute('aria-expanded');
    ctx.input.removeAttribute('aria-controls');
    ctx.input.removeAttribute('aria-activedescendant');
  }
  ctx = null;
  items = [];
  selected = 0;
  if (ddEl) {
    ddEl.classList.remove('on');
    ddEl.textContent = '';
  }
}

function renderDropdown(view, input, token, projects) {
  items = filterProjects(projects, token.query);
  // Dedup guard: selectionchange re-parses after every caret move (including
  // the ones each keystroke causes), so identical renders must be no-ops —
  // rebuilding rows would reset the user's highlight state. A CHANGED query
  // (new key) still re-renders and resets the highlight to row 0.
  const renderKey = token.start + '\u0000' + token.query + '\u0000' +
    (items.length === 0 ? '<empty>' : items.map((p) => p.name).join('\u0001'));
  if (ctx && ddEl && ddEl.classList.contains('on') && renderKey === lastRenderKey) return;
  lastRenderKey = renderKey;
  if (items.length === 0 && cacheAt === 0) {
    // Fetch failed and nothing was ever cached — stay quiet rather than
    // claiming "no matching projects" about an unknown list.
    closeMentionDropdown();
    return;
  }
  const dd = ensureDropdownEl();
  ctx = { view, input };
  selected = 0;
  dd.textContent = '';
  if (items.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'mention-empty';
    empty.setAttribute('role', 'option');
    empty.setAttribute('aria-disabled', 'true');
    empty.textContent = t('mention.noMatch');
    dd.appendChild(empty);
  } else {
    items.forEach((project, i) => { dd.appendChild(buildItem(project, i)); });
  }
  dd.classList.add('on');
  input.setAttribute('aria-expanded', 'true');
  input.setAttribute('aria-controls', 'mention-dropdown');
  syncActiveRow();
}

/** Input-event entry point: re-parse the token at the caret and refresh the
 *  popup. Safe against await races via fetchSeq (only the newest event
 *  renders, and only if the token is still live when data arrives). */
function updateMentionDropdown(view) {
  const input = view.dom.input;
  if (!input) return;
  const token = parseMentionToken(input);
  if (!token) {
    closeMentionDropdown();
    return;
  }
  const seq = ++fetchSeq;
  getProjectsCached().then((projects) => {
    if (seq !== fetchSeq) return; // superseded by a newer input / close
    const current = parseMentionToken(input); // text may have changed mid-await
    if (!current) {
      closeMentionDropdown();
      return;
    }
    renderDropdown(view, input, current, projects);
  });
}

/** Insert the highlighted project as '@<full name> ' at the token range and
 *  leave the cursor right after the trailing space. */
function pickMention(index) {
  const project = items[index];
  if (!project || !ctx) return;
  const { input } = ctx;
  const token = parseMentionToken(input);
  closeMentionDropdown();
  if (!token) {
    input.focus();
    return;
  }
  const caret = input.selectionStart ?? input.value.length;
  // FULL project name, verbatim — names with spaces / Chinese names ride as-is
  // (zero quoting, zero escaping; the prompt layer matches the full name).
  const insert = '@' + project.name + ' ';
  input.value = input.value.slice(0, token.start) + insert + input.value.slice(caret);
  const pos = token.start + insert.length;
  input.focus();
  input.setSelectionRange(pos, pos);
  // Mirror the composer's own auto-resize, then re-dispatch 'input' so
  // input.js's existing listeners re-sync everything else (ref-only gate,
  // slash dropdown, scroll height). Precedent: projectTab.js:813.
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 200) + 'px';
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/**
 * Keydown hook for the popup. Call it from the composer's keydown handler
 * AFTER the imeGuard short-circuit (IME keys never reach this) and BEFORE
 * the slash-dropdown / history / Enter-to-send branches.
 *
 * Returns true when the key was consumed (caller must stop). Only consumes
 * keys while the popup is open: ArrowUp/Down navigate, Tab and bare Enter
 * insert the highlighted project, Escape closes. With zero matches the
 * popup is informational — Enter (with or without Shift) and Tab fall
 * through untouched.
 *
 * @param {KeyboardEvent} e
 * @param {object} view - the ChatView whose composer fired the event
 * @returns {boolean}
 */
export function handleMentionKeydown(e, view) {
  if (!ctx || ctx.view !== view || !ddEl || !ddEl.classList.contains('on')) return false;
  if (e.key === 'ArrowDown' && items.length > 0) {
    e.preventDefault();
    selected = (selected + 1) % items.length;
    syncActiveRow();
    return true;
  }
  if (e.key === 'ArrowUp' && items.length > 0) {
    e.preventDefault();
    selected = (selected - 1 + items.length) % items.length;
    syncActiveRow();
    return true;
  }
  if ((e.key === 'Enter' || e.key === 'Tab') && !e.shiftKey && items.length > 0) {
    e.preventDefault();
    pickMention(selected);
    return true;
  }
  if (e.key === 'Escape') {
    e.preventDefault();
    closeMentionDropdown();
    return true;
  }
  return false;
}

/**
 * Bind the mention autocomplete to a composer view (input + blur listeners).
 * Call once from initInput. Idempotent per element (re-created DOM nodes are
 * correctly re-bound, mirroring bindImeGuard's marker pattern).
 *
 * @param {object} view - ChatView with dom.input
 */
export function bindMention(view) {
  const input = view.dom.input;
  if (!input || input.__mentionBound) return;
  input.__mentionBound = true;
  input.addEventListener('input', () => { updateMentionDropdown(view); });
  input.addEventListener('blur', () => { closeMentionDropdown(); });
}

// Outside click closes the popup. Bound ONCE at module scope (the slash
// dropdown's document handler does the same for its element): one composer,
// one popup, no per-initInput listener accumulation.
document.addEventListener('click', (e) => {
  if (!ctx || !ddEl) return;
  const target = e.target;
  if (target instanceof Element && (ctx.input.contains(target) || ddEl.contains(target))) return;
  closeMentionDropdown();
});

// Caret moves that fire NO input event (mouse click, ArrowLeft/Right, Home/
// End) must re-live the token: the popup represents the token AT THE CARET,
// so a caret that leaves the token closes the popup — the same liveness rule
// as typing a space into the token. selectionchange is document-level, so
// guard to the view that owns the open popup. Bound once at module scope.
document.addEventListener('selectionchange', () => {
  if (!ctx) return;
  if (document.activeElement !== ctx.input) return;
  updateMentionDropdown(ctx.view);
});
