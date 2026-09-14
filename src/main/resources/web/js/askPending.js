// askPending.js — D6 批 F2 (G10): global pending-AskUser overflow aggregation.
//
// Problem (spec §3.4): every AskUser card renders into the Nebula root window
// chat stream; concurrent asks from several project nodes overflow the scroll
// port and the only existing reminder is the sidebar per-session dot — no
// global cross-session visibility.
//
// Design: a frontend mirror of the hub pending map, keyed by requestId,
// driven by three events — askUser (+1, upsert; replayed frames included),
// askUserAnswered (-1), askUserClosed (-1, source death; engine cascade).
//
// 多 AskUser 并发批（#250，2026-09-13 作者裁定「6 项全补」）两处口径变更：
//  ① 「下一条」推进 / 自动聚焦：答完一张后自动聚焦下一张待办（仅当被答的卡就在
//     当前活动会话里——后台会话的卡被关闭时不得把用户拽走），另有常驻条尾部的
//     显式「下一张待办」控件；行点击定位失败不再是静默 no-op（摘行 + 可见提示）。
//  ③ 镜像重建改为**全局快照**驱动：`pendingAsksSnapshot`（hub `ListAllPendingAsks`，
//     跨 root 单帧）一次原子地「清空 + 重建」，与「哪个会话被（重新）订阅」解耦。
//     旧口径的不一致 —— 前端全局清空（resetPendingAsks）+ 后端按会话订阅重放 ——
//     会在「重连时活动会话 ≠ 承载卡片的 root 会话」时把待办信号静默清零。
//
// Surfaces: a persistent bar (one row per pending ask: source · summary, click
// = scroll to card + highlight) and a header badge with the global count.
// askCardRegistry (per-session, newest card only) keeps its Canvas answer
// channel duty — untouched.

import { t } from './i18n.js';
import { findViewBySessionId, activeView } from './chatView.js';

/** requestId → { requestId, sessionId, agentName, project, nodeName, summary, ts } */
const pending = new Map();

/** requestId of the last card the user was sent to (bar row / badge / auto
 *  advance) — the anchor for "next". null = nothing focused yet ⇒ "next"
 *  means the oldest pending ask (the badge's historical behaviour). */
let lastFocusedRequestId = null;

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

/** askUser frame → mirror entry. `ts` is the local arrival stamp (the frame
 *  carries no creation time by contract — replay frames are byte-identical to
 *  the first send plus `replayed:true`, see InteractionHubReplaySpec). The
 *  snapshot is delivered hub-ordered (createdAt ascending) and Array.sort is
 *  stable, so equal `ts` keeps the hub's order. */
function toEntry(msg) {
  return {
    requestId: msg.requestId,
    sessionId: msg.sessionId || '',
    agentName: msg.agentName || '',
    project: msg.project || '',
    nodeName: msg.nodeName || '',
    summary: summarizeItems(msg.items),
    ts: Date.now()
  };
}

/** Upsert from an askUser frame (live first-send or replayed snapshot frame —
 *  both carry the same payload; replay is idempotent by requestId). */
export function notePendingAsk(msg) {
  if (!msg || !msg.requestId) return;
  pending.set(msg.requestId, toEntry(msg));
  renderBar();
}

/** Remove on terminal resolution: answered (card / chat-input) or closed
 *  (source death, askUserClosed). Unknown requestId = no-op (an answer that
 *  raced the reconnect rebuild simply finds nothing).
 *
 *  #250 ①: when the resolved card was the one the user was looking at (its
 *  session is the active view), advance focus to the next pending card. A
 *  background close (e.g. a node cancelled elsewhere) never yanks the user
 *  into another session — that would be its own kind of surprise. */
export function removePendingAsk(requestId) {
  if (!requestId) return;
  const entry = pending.get(requestId);
  if (!entry) return;
  pending.delete(requestId);
  if (lastFocusedRequestId === requestId) lastFocusedRequestId = null;
  renderBar();
  if (pending.size === 0) return;
  const view = entry.sessionId ? findViewBySessionId(entry.sessionId) : null;
  if (!view || !activeView || view !== activeView) return;
  const next = nextEntry();
  if (next) jumpToAsk(next);
}

/** #250 ③: global authority rebuild — the hub's `pendingAsksSnapshot`
 *  (ListAllPendingAsks, every root, hub-ordered). Clear + refill happen in ONE
 *  reconciliation pass so the mirror can never be left emptied by a clear whose
 *  rebuild never comes (the old reconnect mismatch: global `resetPendingAsks`
 *  vs per-session replay). Answer/close frames that raced the snapshot are
 *  simply not in it.
 *
 *  `since` = the local time the snapshot was requested. Entries that arrived
 *  AFTER the request but are absent from the snapshot are kept: the hub may not
 *  have known them yet when it built the answer (a live askUser frame racing
 *  the round trip) — dropping them would be exactly the silent loss this item
 *  removes. Every other entry absent from the snapshot is retired. */
export function applyPendingAskSnapshot(asks, since) {
  const incoming = new Map();
  for (const msg of Array.isArray(asks) ? asks : []) {
    if (!msg || !msg.requestId) continue;
    incoming.set(msg.requestId, toEntry(msg));
  }
  const raced = typeof since === 'number' ? since : 0;
  for (const [rid, entry] of [...pending]) {
    if (incoming.has(rid)) continue;
    if (entry.ts > raced) continue; // newer than the query — keep (authority had not seen it yet)
    pending.delete(rid);
    if (lastFocusedRequestId === rid) lastFocusedRequestId = null;
  }
  for (const [rid, entry] of incoming) pending.set(rid, entry);
  renderBar();
}

/** #250 ③: snapshot query failed (gateway sent `failed:true`). Keep the local
 *  mirror (a failed sync says nothing about what is still pending) and make the
 *  failure visible — degrading to "shows 0 pending" would be a silent lie. */
export function pendingSnapshotFailed() {
  window.__showToast?.(t('askUser.pendingSyncFailed'), 'error');
}

export function pendingAskCount() { return pending.size; }

// ---------- Rendering ----------

function barEl() { return document.getElementById('ask-pending-bar'); }
function badgeEl() { return document.getElementById('pending-asks-indicator'); }

/** Hub-ordered mirror rows (oldest first). */
function orderedEntries() {
  return [...pending.values()].sort((a, b) => a.ts - b.ts);
}

/** #250 ①: the card after the focused one (cyclic); nothing focused yet ⇒ the
 *  oldest (keeps the badge's documented "jump to the oldest" behaviour). */
function nextEntry() {
  const list = orderedEntries();
  if (list.length === 0) return null;
  const idx = list.findIndex(e => e.requestId === lastFocusedRequestId);
  return idx < 0 ? list[0] : list[(idx + 1) % list.length];
}

function renderBar() {
  const bar = barEl();
  const badge = badgeEl();
  const entries = orderedEntries();
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
  // #250 ①: explicit "next pending" control — only worth showing with ≥2 cards
  // (a single pending ask is already one click away). It shares the row
  // material at the CSS-rule level (chat.css declares both classes in ONE
  // selector list) BUT carries its own class so every `.ask-pending-row` count
  // (bar rows = pending asks) keeps its exact meaning — existing e2e/QA
  // assertions stay valid.
  if (entries.length > 1) {
    const next = document.createElement('button');
    next.type = 'button';
    next.className = 'ask-pending-next';
    next.textContent = t('askUser.nextPending', { n: String(entries.length) });
    next.addEventListener('click', () => {
      const target = nextEntry();
      if (target) jumpToAsk(target);
    });
    bar.appendChild(next);
  }
}

/** Click a bar row → scroll the matching card into view + highlight flash.
 *  The card may live in a non-active session's view (asks route to the Nebula
 *  root session) — switch sessions first when needed, then locate by
 *  data-request-id.
 *
 *  #250 ①: a row whose card can no longer be located is NOT a silent no-op
 *  anymore — the entry is dropped from the mirror and the user gets a visible
 *  notice (a dead row that does nothing on click is exactly the "silently lost
 *  todo signal" this batch removes; #250 ⑥ same rule). */
function jumpToAsk(entry) {
  lastFocusedRequestId = entry.requestId;
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
  const settle = (attempt = 0) => {
    if (locate()) return;
    // One retry: the target session's history + replayed frames land
    // asynchronously, so a first miss is not yet proof the card is gone —
    // retiring on it would drop a live todo entry.
    if (attempt === 0) { setTimeout(() => settle(1), 500); return; }
    if (pendingAskCount() === 0) return; // mirror already empty (frame landed late) — nothing to retire
    pending.delete(entry.requestId);
    if (lastFocusedRequestId === entry.requestId) lastFocusedRequestId = null;
    renderBar();
    window.__showToast?.(t('askUser.cardGone'), 'info');
  };
  const view = entry.sessionId ? findViewBySessionId(entry.sessionId) : null;
  const isActive = view && activeView && view === activeView;
  if (!isActive && entry.sessionId) {
    // Dynamic import: sidebar.js ↔ main.js import graph stays acyclic.
    import('./sidebar.js').then(({ switchToSession }) => {
      switchToSession(entry.sessionId);
      // The session switch restores history + replays pending frames
      // asynchronously — retry the locate once the view settles.
      setTimeout(settle, 600);
    }).catch(() => settle());
  } else {
    settle();
  }
}

// ---------- Wiring ----------

/** Header badge click = next pending ask (the oldest when nothing is focused —
 *  the badge's original behaviour). Called once from main.js at startup. */
export function initPendingAsks() {
  const badge = badgeEl();
  if (badge) {
    badge.title = t('askUser.pendingTitle');
    badge.addEventListener('click', () => {
      const target = nextEntry();
      if (target) jumpToAsk(target);
    });
    badge.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        badge.click();
      }
    });
  }
}
