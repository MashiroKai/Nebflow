// askPending.js — D6 批 F2 (G10): global pending-AskUser overflow aggregation.
//
// Problem (spec §3.4): every AskUser card renders into the Nebula root window
// chat stream; concurrent asks from several project nodes overflow the scroll
// port and the only existing reminder is the sidebar per-session dot — no
// global cross-session visibility.
//
// Design: a frontend mirror of the hub pending map, keyed by requestId,
// driven by three events — askUser (+1, upsert; replayed frames included),
// askUserAnswered (-1), askUserClosed (-1, source death; engine cascade lands
// in batch E2). WS reconnect resets the mirror: the hub re-sends the
// still-pending snapshot (ListPendingAsks) on (re)subscribe, so the set is
// rebuilt from the single authority and can never strand a stale entry.
//
// Surfaces: a persistent bar (one row per pending ask: source · summary, click
// = scroll to card + highlight) and a header badge with the global count.
// askCardRegistry (per-session, newest card only) keeps its Canvas answer
// channel duty — untouched.

import { t } from './i18n.js';
import { findViewBySessionId, activeView } from './chatView.js';

/** requestId → { requestId, sessionId, agentName, project, nodeName, summary, ts } */
const pending = new Map();

/** First-question summary, 40-char truncation — same rule as
 *  AskUserQuestionTool.summarize (:125-132). */
function summarizeItems(items) {
  if (!Array.isArray(items) || items.length === 0) return '';
  const q0 = (items[0] && typeof items[0].question === 'string') ? items[0].question : '';
  const short = q0.length > 40 ? q0.slice(0, 37) + '...' : q0;
  return items.length > 1 ? `${short} (+${items.length - 1})` : short;
}

/** Badge/row source label: project context → "project · nodeName" (dispatcher
 *  asks carry nodeName="dispatcher" from the engine); otherwise the bare agent
 *  name (Nebula self-ask / REPL — pre-F1 behavior). */
export function askSourceLabel(source) {
  if (source && source.project) return `${source.project} · ${source.nodeName || source.agentName || ''}`;
  return (source && source.agentName) || '';
}

/** Upsert from an askUser frame (live first-send or replayed snapshot frame —
 *  both carry the same payload; replay is idempotent by requestId). */
export function notePendingAsk(msg) {
  if (!msg || !msg.requestId) return;
  pending.set(msg.requestId, {
    requestId: msg.requestId,
    sessionId: msg.sessionId || '',
    agentName: msg.agentName || '',
    project: msg.project || '',
    nodeName: msg.nodeName || '',
    summary: summarizeItems(msg.items),
    ts: Date.now()
  });
  renderBar();
}

/** Remove on terminal resolution: answered (card / chat-input) or closed
 *  (source death, askUserClosed). Unknown requestId = no-op (an answer that
 *  raced the reconnect rebuild simply finds nothing). */
export function removePendingAsk(requestId) {
  if (!requestId) return;
  if (pending.delete(requestId)) renderBar();
}

/** Reconnect reset — the hub ListPendingAsks replay re-populates; clearing
 *  first guarantees the mirror equals the still-pending snapshot (an ask
 *  answered while this client was gone is simply never replayed). */
export function resetPendingAsks() {
  if (pending.size === 0) return;
  pending.clear();
  renderBar();
}

export function pendingAskCount() { return pending.size; }

// ---------- Rendering ----------

function barEl() { return document.getElementById('ask-pending-bar'); }
function badgeEl() { return document.getElementById('pending-asks-indicator'); }

function renderBar() {
  const bar = barEl();
  const badge = badgeEl();
  const entries = [...pending.values()].sort((a, b) => a.ts - b.ts);
  if (badge) {
    badge.classList.toggle('hidden', entries.length === 0);
    const count = badge.querySelector('.pa-count');
    if (count) count.textContent = String(entries.length);
  }
  if (!bar) return;
  bar.classList.toggle('hidden', entries.length === 0);
  bar.textContent = '';
  for (const entry of entries) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'ask-pending-row';
    row.dataset.requestId = entry.requestId;
    const src = document.createElement('span');
    src.className = 'ask-pending-source';
    src.textContent = askSourceLabel(entry) || t('askUser.pendingTitle');
    const sum = document.createElement('span');
    sum.className = 'ask-pending-summary';
    sum.textContent = entry.summary;
    row.appendChild(src);
    row.appendChild(sum);
    row.addEventListener('click', () => jumpToAsk(entry));
    bar.appendChild(row);
  }
}

/** Click a bar row → scroll the matching card into view + highlight flash.
 *  The card may live in a non-active session's view (asks route to the Nebula
 *  root session) — switch sessions first when needed, then locate by
 *  data-request-id. Card gone (answered between render and click) = no-op,
 *  the next askUserAnswered frame removes the row. */
function jumpToAsk(entry) {
  const locate = () => {
    const esc = window.CSS && CSS.escape ? CSS.escape(entry.requestId) : entry.requestId;
    const box = document.querySelector('.option-box[data-request-id="' + esc + '"]');
    if (!box) return false;
    const row = box.closest('.row.ai') || box;
    row.scrollIntoView({ behavior: 'smooth', block: 'center' });
    row.classList.remove('ask-card-flash');
    if (row instanceof HTMLElement) void row.offsetWidth; // restart the flash animation on repeat clicks
    row.classList.add('ask-card-flash');
    return true;
  };
  const view = entry.sessionId ? findViewBySessionId(entry.sessionId) : null;
  const isActive = view && activeView && view === activeView;
  if (!isActive && entry.sessionId) {
    // Dynamic import: sidebar.js ↔ main.js import graph stays acyclic.
    import('./sidebar.js').then(({ switchToSession }) => {
      switchToSession(entry.sessionId);
      // The session switch restores history + replays pending frames
      // asynchronously — retry the locate once the view settles.
      setTimeout(locate, 600);
    }).catch(() => locate());
  } else {
    locate();
  }
}

// ---------- Wiring ----------

/** Header badge click = jump to the oldest pending ask (same target as the
 *  first bar row). Called once from main.js at startup. */
export function initPendingAsks() {
  const badge = badgeEl();
  if (badge) {
    badge.title = t('askUser.pendingTitle');
    badge.addEventListener('click', () => {
      const first = [...pending.values()].sort((a, b) => a.ts - b.ts)[0];
      if (first) jumpToAsk(first);
    });
    badge.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        badge.click();
      }
    });
  }
}
