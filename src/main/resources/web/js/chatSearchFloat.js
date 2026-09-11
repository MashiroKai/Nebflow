// chatSearchFloat.js — Detached message window opened from a chat-search hit.
//
// 2026-09-11 msgsearch v2 (author ruling): clicking a non-Pop search result no
// longer locates the message by scrolling the MAIN session — it opens an
// independent floating window whose content STARTS at the clicked message.
// Window contract:
//   - draggable (pointer drag on the header), independently closable, with its
//     own scroll container, several windows can coexist (cascade offset).
//   - the main session window (position / scroll / input focus / live
//     streaming) is NEVER touched: no switchToSession, no WS pagination, no
//     write into the main chat DOM, no focus steal. History comes from the
//     same REST endpoint the search list already uses, and rows are drawn by
//     the SAME history renderer (restoreFromBackendHistory → identical rows,
//     identical cards) through a throwaway ChatView that is never registered
//     in chatViews and is only the activeView for the synchronous render call.
//   - no backdrop / no dimming (visual 铁律 1): a floating window must not
//     shade the UI underneath it.
//
// Failure visibility: a failed history fetch / a hit that can no longer be
// located / a renderer throw each surface an inline notice inside the window,
// and (via opts.onError) an inline warning in the originating search row —
// never a silent empty window, never console-only.

import { ChatView, setActiveView, activeView } from './chatView.js';
import { restoreFromBackendHistory } from './persistence.js';
import { cleanupCardIframes } from './cardRegistry.js';
import { expandGroupContaining } from './turnGroup.js';
import { t } from './i18n.js';
import { key } from './branding.js';

/** Messages rendered per window (from the clicked message onward). */
const WINDOW_MESSAGES = 120;
/** Offset between cascaded windows (multiple windows open at once). */
const CASCADE_STEP = 26;
/** How much of a window must stay on screen when dragged. */
const MIN_VISIBLE = 48;
/** Header controls that must not start a drag. */
const DRAG_IGNORE = 'button, input, select, textarea, a, iframe, [role="button"]';

/** @type {Set<HTMLElement>} */
const openFloats = new Set();
let cascadeSeq = 0;

function authHeaders() {
  const tok = localStorage.getItem(key('token')) || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

const stripForMatch = (s) => (s || '').replace(/[^\p{L}\p{N}]/gu, '').toLowerCase();

/** normalizeMessage's kind ← UiMessage.type mapping, inverted. */
function typeMatches(rawType, kind) {
  if (kind === 'user') return rawType === 'user';
  if (kind === 'tool') return rawType === 'tool';
  return rawType === 'ai' || rawType === 'agent' || rawType === 'ask';
}

/**
 * Index of the clicked message inside the raw history array. `res.ord` IS that
 * index (normalizeMessage tags every message with its array position), so the
 * fast path is a plain lookup; the snippet scan is the defensive fallback for
 * a history that changed between the search fetch and this one.
 * @returns {number} -1 when the message can no longer be found.
 */
function findHitIndex(msgs, res) {
  const at = typeof res.ord === 'number' ? msgs[res.ord] : null;
  if (at && typeMatches(at.type, res.kind)) return res.ord;
  const needle = stripForMatch(res.content || res.summary || res.text).slice(0, 40);
  if (needle.length < 4) return -1;
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i];
    if (!typeMatches(m.type, res.kind)) continue;
    const hay = stripForMatch([m.text, m.summary, m.input, m.content].filter(Boolean).join('\n'));
    if (hay.includes(needle)) return i;
  }
  return -1;
}

/**
 * The rendered row of the clicked message (same locale-independent candidates
 * the old in-session jump used: card content → raw summary → joined text, then
 * a timestamp fallback), falling back to the first row of the window.
 * @returns {HTMLElement|null}
 */
function findTargetRow(chatEl, res) {
  const candidates = res.kind === 'tool' ? [res.content, res.summary, res.text] : [res.text];
  const needles = [];
  for (const cand of candidates) {
    const s = stripForMatch(cand).slice(0, 40);
    if (s.length >= 4 && !needles.includes(s)) needles.push(s);
  }
  const rows = chatEl.querySelectorAll('.row, .tool-card');
  for (const needle of needles) {
    for (const row of rows) {
      if (stripForMatch(row.textContent).includes(needle)) return /** @type {HTMLElement} */ (row);
    }
  }
  if (res.ts) {
    const badge = chatEl.querySelector(`[data-ts="${res.ts}"]`);
    const row = badge?.closest('.row, .tool-card');
    if (row) return /** @type {HTMLElement} */ (row);
  }
  return /** @type {HTMLElement|null} */ (chatEl.querySelector('.row'));
}

/** Visible inline notice inside a window (failure paths never stay silent). */
function showNotice(host, msg) {
  const el = document.createElement('div');
  el.className = 'nf-msgfloat-notice';
  el.setAttribute('role', 'status');
  el.textContent = msg;
  host.appendChild(el);
  return el;
}

// ── Shell ──────────────────────────────────────────────────
/** @param {{sessionName?:string, sessionId:string}} res */
function buildShell(res) {
  const el = document.createElement('div');
  // data-nf-float-layer: cards rendered inside a floating window must never
  // drive the active view's scroll (see the cardRegistry height handler).
  el.setAttribute('data-nf-float-layer', '1');
  el.className = 'nf-msgfloat';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', t('search.floatTitle'));

  const head = document.createElement('div');
  head.className = 'nf-msgfloat-head';

  const title = document.createElement('div');
  title.className = 'nf-msgfloat-title';
  title.textContent = res.sessionName || res.sessionId;
  head.appendChild(title);

  const sub = document.createElement('div');
  sub.className = 'nf-msgfloat-sub';
  sub.textContent = t('search.floatFromHere');
  head.appendChild(sub);

  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'nf-msgfloat-close';
  close.setAttribute('aria-label', t('search.floatClose'));
  close.title = t('search.floatClose');
  close.textContent = '×';
  head.appendChild(close);

  const chat = document.createElement('div');
  chat.className = 'nf-msgfloat-chat';

  el.appendChild(head);
  el.appendChild(chat);

  close.addEventListener('click', () => closeFloat(el));
  el.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    e.stopPropagation();
    closeFloat(el);
  });
  bindDrag(el, head);
  return el;
}

function closeFloat(el) {
  openFloats.delete(el);
  const chat = el.querySelector('.nf-msgfloat-chat');
  // Release card iframe browsing contexts before the DOM goes away (same
  // discipline as the main chat's history reload).
  if (chat instanceof HTMLElement) cleanupCardIframes(chat);
  el.remove();
}

/** Cascade placement: top-right, each new window offset from the previous. */
function placeFloat(el) {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const step = (cascadeSeq++ % 8) * CASCADE_STEP;
  const w = el.offsetWidth;
  const h = el.offsetHeight;
  const left = Math.max(12, Math.min(vw - w - 32 - step, vw - MIN_VISIBLE));
  const top = Math.min(Math.max(72 + step, 12), Math.max(12, vh - h - 12));
  el.style.left = `${left}px`;
  el.style.top = `${top}px`;
}

function bindDrag(el, handle) {
  let pointerId = null;
  let dx = 0;
  let dy = 0;
  handle.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    if (e.target instanceof Element && e.target.closest(DRAG_IGNORE)) return;
    const r = el.getBoundingClientRect();
    pointerId = e.pointerId;
    dx = e.clientX - r.left;
    dy = e.clientY - r.top;
    try { handle.setPointerCapture(pointerId); } catch (err) { /* capture is best-effort */ }
    el.classList.add('dragging');
    e.preventDefault();
  });
  handle.addEventListener('pointermove', (e) => {
    if (pointerId === null || e.pointerId !== pointerId) return;
    const w = el.offsetWidth;
    const left = Math.min(Math.max(e.clientX - dx, MIN_VISIBLE - w), window.innerWidth - MIN_VISIBLE);
    const top = Math.min(Math.max(e.clientY - dy, 0), window.innerHeight - MIN_VISIBLE);
    el.style.left = `${left}px`;
    el.style.top = `${top}px`;
  });
  const end = (e) => {
    if (pointerId === null || e.pointerId !== pointerId) return;
    try { handle.releasePointerCapture(pointerId); } catch (err) { /* already released */ }
    pointerId = null;
    el.classList.remove('dragging');
  };
  handle.addEventListener('pointerup', end);
  handle.addEventListener('pointercancel', end);
}

/**
 * Render the window content: from the clicked message onward, through the
 * app's own history renderer.
 * @param {HTMLElement} chatEl
 * @param {any[]} slice
 * @param {any} res
 * @param {number} remaining  messages beyond the window cap
 * @param {(msg:string)=>void} [onError]
 */
function renderWindow(chatEl, slice, res, remaining, onError) {
  const prevView = activeView;
  // Throwaway view: the history renderer targets activeView.dom.chat. It is
  // deliberately NOT registered in chatViews (a registered view with the same
  // sessionId would hijack live WS routing) and the active view is restored in
  // finally — the assignment below only spans this synchronous call.
  const view = new ChatView('search-float', { chat: chatEl });
  try {
    setActiveView(view);
    restoreFromBackendHistory(slice, { scrollToBottom: false, busyTail: false });
  } catch (e) {
    console.debug('[chatSearchFloat] history render failed', e);
    showNotice(chatEl, t('search.floatRenderFailed'));
    onError?.(t('search.floatRenderFailed'));
    return;
  } finally {
    setActiveView(prevView);
  }

  // Reveal the clicked message: history turn chrome tucks tool rows, and the
  // hit is usually a tool row — expand the owning turn, then bring the row to
  // the top of THIS window (scrollIntoView stays inside the window's own
  // scroll container; the main chat is not an ancestor).
  const target = findTargetRow(chatEl, res);
  if (target) {
    expandGroupContaining(target);
    target.classList.add('search-hit-flash');
    target.scrollIntoView({ block: 'start' });
    setTimeout(() => target.classList.remove('search-hit-flash'), 1800);
  } else {
    showNotice(chatEl, t('search.floatHitNotFound'));
    onError?.(t('search.floatHitNotFound'));
  }
  if (remaining > 0) showNotice(chatEl, t('search.floatTruncated', { n: remaining }));
}

/**
 * Open a detached window whose first row is the clicked message.
 * @param {{sessionId:string, sessionName?:string, kind?:string, tool?:string,
 *          input?:any, text?:string, summary?:string, content?:string,
 *          ts?:number, ord?:number, key?:string}} res  a search result item
 * @param {{onError?:(msg:string)=>void}} [opts]
 * @returns {HTMLElement} the window element
 */
export function openSearchMessageFloat(res, opts = {}) {
  const el = buildShell(res);
  el.setAttribute('data-key', res.key || '');
  document.body.appendChild(el);
  openFloats.add(el);
  placeFloat(el);
  const chatEl = /** @type {HTMLElement} */ (el.querySelector('.nf-msgfloat-chat'));

  (async () => {
    let msgs;
    try {
      const resp = await fetch(
        `/api/sessions/${encodeURIComponent(res.sessionId)}/history`,
        { headers: authHeaders() });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const data = await resp.json();
      msgs = Array.isArray(data.messages) ? data.messages : [];
    } catch (e) {
      console.debug('[chatSearchFloat] history fetch failed', e);
      showNotice(chatEl, t('search.floatLoadFailed'));
      opts.onError?.(t('search.floatLoadFailed'));
      return;
    }
    if (!el.isConnected) return;   // window closed while the fetch was in flight
    const start = findHitIndex(msgs, res);
    if (start < 0) {
      showNotice(chatEl, t('search.floatHitNotFound'));
      opts.onError?.(t('search.floatHitNotFound'));
      return;
    }
    const slice = msgs.slice(start, start + WINDOW_MESSAGES);
    renderWindow(chatEl, slice, res, msgs.length - start - slice.length, opts.onError);
  })();

  return el;
}

// Re-render open windows when the locale changes while they are on screen
// (they must not stay in the language they were opened in).
window.addEventListener('locale-changed', () => {
  for (const el of openFloats) {
    const sub = el.querySelector('.nf-msgfloat-sub');
    if (sub) sub.textContent = t('search.floatFromHere');
    const close = el.querySelector('.nf-msgfloat-close');
    if (close) {
      close.setAttribute('aria-label', t('search.floatClose'));
      close.setAttribute('title', t('search.floatClose'));
    }
  }
});
