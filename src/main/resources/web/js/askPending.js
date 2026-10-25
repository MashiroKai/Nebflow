// askPending.js — D6 批 F2 (G10): global pending-AskUser overflow aggregation.
//
// Problem (spec §3.4): every AskUser card renders into the Nebula root window
// chat stream; concurrent asks from several project nodes overflow the scroll
// port and the only existing reminder is the sidebar per-session dot — no
// global cross-session visibility.
//
// Design: a frontend mirror of the hub pending map, keyed by requestId,
// driven by two events — askUser (+1, upsert; replayed frames included),
// askUserClosed (-1, source death / turn interrupted; engine broadcast).
// (A third driver, askUserAnswered (-1), retired with the chat-input
// passthrough — e59ed251d / uiclean 批 2026-09-15: no producer left.)
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
import { isRootNamed } from './rootName.js';

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
 *  name (root self-ask / REPL — pre-F1 behavior).
 *  N5 (unnamed-state batch 2026-10-06): an UNNAMED root self-ask returns ''
 *  (same single-point judgement as N1/N2/N4) so renderBar's existing
 *  `|| t('askUser.pendingTitle')` fallback naturally lands on 「待回答问题」 —
 *  the mechanism name is a key, never a display fallback. Named → unchanged. */
export function askSourceLabel(source) {
  if (source && source.project) return `${source.project} · ${source.nodeName || source.agentName || ''}`;
  if (!isRootNamed()) return '';
  return (source && source.agentName) || '';
}

/** askUser frame → mirror entry. `ts` is the local arrival stamp (the frame
 *  carries no creation time by contract — replay frames are byte-identical to
 *  the first send plus `replayed:true`, see InteractionHubReplaySpec). The
 *  snapshot is delivered hub-ordered (createdAt ascending) and Array.sort is
 *  stable, so equal `ts` keeps the hub's order.
 *
 *  `live` = hub-unaware arrival (修复批 2026-10-25「待答徽章常显」)：live 首发帧
 *  = true；快照/订阅重放帧（`replayed`/`replaces` 任一）= false——重放帧**由
 *  hub 状态产生**，快照对账时不得凭「到达晚于查询」豁免摘除（旧口径把重放刷新
 *  的 ts 当活帧豁免 ⇒ 关卡帧丢失时僵尸条目永生，徽标对着已收口的槽位常显）。 */
function toEntry(msg) {
  return {
    requestId: msg.requestId,
    sessionId: msg.sessionId || '',
    agentName: msg.agentName || '',
    project: msg.project || '',
    nodeName: msg.nodeName || '',
    summary: summarizeItems(msg.items),
    ts: Date.now(),
    live: !msg.replayed && !msg.replaces
  };
}

/** Upsert from an askUser frame (live first-send or replayed snapshot frame —
 *  both carry the same payload; replay is idempotent by requestId). */
export function notePendingAsk(msg) {
  if (!msg || !msg.requestId) return;
  pending.set(msg.requestId, toEntry(msg));
  renderBar();
}

/** Remove on terminal resolution: answered (the card's own confirm/cancel
 *  callback — chat.js) or closed (source death / turn interrupted,
 *  askUserClosed). Unknown requestId = no-op (an answer that
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
 *  AFTER the request but are absent from the snapshot are kept **only when
 *  they arrived on a live frame**: the hub may not have known them yet when it
 *  built the answer (a live askUser frame racing the round trip) — dropping
 *  those would be exactly the silent loss this item removes. Entries last
 *  refreshed by a REPLAYED frame (`live:false`) get no such exemption: a
 *  replay exists because the hub knew the slot, so absence here means the slot
 *  closed after the replay was built (its close frame lost/raced) — the
 *  authority has spoken, retire it (修复批 2026-10-25「待答徽章常显」). */
export function applyPendingAskSnapshot(asks, since) {
  const incoming = new Map();
  for (const msg of Array.isArray(asks) ? asks : []) {
    if (!msg || !msg.requestId) continue;
    incoming.set(msg.requestId, toEntry(msg));
  }
  const raced = typeof since === 'number' ? since : lastSnapshotSince;
  for (const [rid, entry] of [...pending]) {
    if (incoming.has(rid)) continue;
    if (entry.ts > raced && entry.live) continue; // live frame newer than the query — keep (authority had not seen it yet)
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

// ---------- 权威对账同步（修复批 2026-10-25「待答徽章常显」） ----------
//
// 缺口（与 hub 侧 PendingAskLedger.bootReconcile 互补的前端半面）：镜像对账
// （getPendingAsks → pendingAsksSnapshot）此前**只挂在 WS 重连**上（main.js
// onReconnect）。答链又是「卡上作答本地清」——hub complete() 在 2026-10-25 修复前
// 不广播任何关卡帧 ⇒ 任何**没亲手作答**的标签页/设备（多端常开）镜像永久滞留，
// 而长连标签页可以几天不重连：徽标对着早已收口的槽位常显（作者报障 13:0x 即此
// 形态——hub 全天 0 槽位、台账空，纯前端僵尸）。三层收口：
//   ① hub complete() 补广播 askUserClosed{reason:"answered"}（引擎侧，本批）——
//      亲手作答之外的标签页即时清零；
//   ② 重放帧不再豁免对账摘除（toEntry.live，本文件）——丢失的关卡帧由下一次
//      对账兜住；
//   ③ 本面：对账不再只等重连——可见期低频轮询 + 页面重新可见即补一次对账，
//      `since` 戳由这里单点维护（main.js onReconnect 改调 requestPendingAskSync）。

/** 最近一次快照查询的本地时间戳（对账 race 闸的 `since`）。 */
let lastSnapshotSince = 0;

/** send 函数（main.js init 时注入 sendWs；askPending 不 import ws.js——依赖图
 *  保持无环）。null = WS 未就绪，请求静默跳过（下次节拍再试）。 */
let wsSend = null;

/** 对账节拍（可见期）。5min = 待办信号的自愈上界：丢失的关卡帧最迟一个节拍
 *  被权威快照收口。后台标签页不请求（visibility 闸，零无效流量）。 */
const SYNC_INTERVAL_MS = 5 * 60 * 1000;
/** 页面重新可见的补账节流：60s 内已对账过就不再补（切 tab 抖动不放大流量）。 */
const SYNC_VISIBLE_THROTTLE_MS = 60 * 1000;

let syncTimer = null;
let lastSyncAt = 0;
let visHooked = false;

/** 单点：盖 `since` 戳并请求全局快照。返回是否真的发出了请求。 */
export function requestPendingAskSync() {
  if (!wsSend) return false;
  lastSnapshotSince = Date.now();
  lastSyncAt = lastSnapshotSince;
  wsSend({ type: 'getPendingAsks' });
  return true;
}

function syncIfVisible() {
  if (document.visibilityState !== 'visible') return false;
  return requestPendingAskSync();
}

/** main.js 启动时调用一次：注入 send 通道 + 装节拍与可见性补账。幂等（重复
 *  调用先拆旧节拍）。 */
export function startPendingAskSync(send) {
  if (typeof send !== 'function') return;
  wsSend = send;
  if (syncTimer) clearInterval(syncTimer);
  syncTimer = setInterval(() => syncIfVisible(), SYNC_INTERVAL_MS);
  if (!visHooked) {
    visHooked = true;
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState !== 'visible') return;
      if (Date.now() - lastSyncAt < SYNC_VISIBLE_THROTTLE_MS) return;
      syncIfVisible();
    });
  }
}

// ---------- 超龄自愈（重启孤儿对账批 2026-10-22） ----------

/** Mirror 条目寿限（作者令「超龄（如 >24h）自动过期」）：24h。镜像只是**待办
 *  信号**，不是卡片的权威——权威在 hub（运行中槽位的收口走既有 askUserClosed
 *  级联；跨重启残留走引擎 boot 对账 PendingAskLedger.bootReconcile 广播）。
 *  本上限只兜「两个权威都够不着」的最后一种形态：**久未重连的标签页**——WS 半开
 *  （宿主睡眠/断网）时既收不到关卡帧也跑不了快照对账，镜像条目会无限期存续，
 *  徽标对着一个早已死亡的槽位说谎（实测 asknb-6554022b93bb4aa7 计数存活 46h+
 *  跨三次重启）。条目超龄即在任一渲染/对账点被摘除（renderBar 单点），计数与
 *  待办条同步收口；对应卡片若仍在（hub 槽位真活着的病态长挂）不受本摘除影响
 *  ——卡片自己的收口是引擎侧机制的事，镜像退场不制造「有卡无数」以外的第二张
 *  谎言面（该形态归 askerAlive 标注与 boot 对账管）。 */
const MAX_ENTRY_AGE_MS = 24 * 60 * 60 * 1000;

/** 摘除超龄条目。renderBar 的头部单点调用 ⇒ 任何渲染/对账路径都自愈。 */
function sweepAged() {
  const cutoff = Date.now() - MAX_ENTRY_AGE_MS;
  for (const [rid, entry] of [...pending]) {
    if (entry.ts <= cutoff) {
      pending.delete(rid);
      if (lastFocusedRequestId === rid) lastFocusedRequestId = null;
    }
  }
}

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
  sweepAged(); // 超龄自愈（重启孤儿对账批）：任何渲染/对账点先摘僵尸条目再出数
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
