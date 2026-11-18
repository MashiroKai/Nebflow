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
//
// ── 溢出转待办批（2026-11-18 作者令）────────────────────────────────────────
// 作者令逐字：「超出对话分页的 AskUser 问题自动转入待办，不再弹窗和计数气泡显示，
// 但是用户往上翻消息的话还是可以通过原卡片作答，然后在待办和原卡片作答效果应该是
// 一样的，共同管理卡片的生命周期，不能出现待办已答但原卡还可以作答的不对称现象」。
//
// 本模块是**判据与状态的单点**（本批新增，别处禁自算）：
//   · `askPlacement(rid)`   ⇒ 'inline' | 'todo' | null（null = 不挂起）
//   · `transferredAsks()`   ⇒ 转入待办的那批（含题目载荷，待办面渲染用）
//   · `inlineAsks()`        ⇒ 仍在对话面（弹窗条 + 计数气泡）的那批
//   · `askAnswerFrame(...)` ⇒ 答复帧**唯一构造器**（chat.js 卡内确认腿同用）
//   · `submitAskAnswer(...)`⇒ 待办面作答入口（与卡内作答同一收口单点）
// 两处渲染（待办条/徽标、待办面）一律从这里的读口取，禁各自判一遍。
//
// 转出判据（依据 + 边界，逐条对应作者要求的面）：
//   ① **对话上下文在场**（页面有 chat 宿主元素）才判定；无上下文（纯镜像夹具 /
//      会话尚未建面）⇒ 一律 'inline'，即本批之前的镜像行为**逐字不变**。
//   ② 有上下文时：该 requestId 的卡片不在**已加载的消息页窗口**内（DOM 无
//      `.option-box[data-request-id]`）⇒ 'todo'。聊天气泡流不虚拟化、不逐条
//      淘汰（chat.js 全量挂 DOM，只有会话切换 / 重开才整体清空）⇒ DOM 在场
//      ⟺ 「在对话当前分页内」——这正是作者指定的「按分页而非视口」口径。
//   边界：
//     · 滚动翻页：视口内与否**不算**；只有「那一页是否已加载」算 ⇒ 用户往上
//       翻、旧页被加载后卡片重新在场 ⇒ 自动回到 'inline'（非单向闩锁）。
//     · 会话切换：切走的会话其卡片随视图清空 ⇒ 转出；切回时 hub 订阅重放帧
//       重挂卡 ⇒ 自动回 'inline'。
//     · 刷新后重开：判定是纯投影、无持久状态 ⇒ 按重开后实际加载的页窗口重算。
//     · 多设备：判定读的是**本设备**的已加载页窗口 ⇒ 各设备各算一次；而挂起/
//       已答的**生命周期状态**是全局共享的（hub 单点 + askUserClosed 广播），
//       所以 A 设备作答 ⇒ B 设备卡片与待办行同步收口。
//   重估时机：只要还有挂起问题，就挂一个 rAF 合并的 MutationObserver（无挂起即
//   摘除 ⇒ 零常态开销），DOM 一变就重估一次——会话清空 / 历史页加载 / 卡片挂入
//   挂出全部自动跟上；两处渲染只在**归属签名变化**时才重建（防抖环）。

import { t } from './i18n.js';
import { findViewBySessionId, activeView } from './chatView.js';
import { isRootNamed, rootDisplayName, agentDisplayName, ROOT_AGENT_KEY } from './rootName.js';
import { EVENTS } from './events.js';

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
 *  asks carry nodeName="dispatcher" from the engine); otherwise the ASKER's own
 *  display name (root self-ask / REPL — pre-F1 behavior).
 *  Nebula 退役批（作者令 2026-11-01）: the label is resolved through the single
 *  display-name read point — the root asker renders the user's name for it
 *  (never the mechanism key), background askers render their own identity.
 *  N5 (unnamed-state batch 2026-10-06) kept: an UNNAMED root self-ask returns ''
 *  (same single-point judgement as N1/N2/N4) so renderBar's existing
 *  `|| t('askUser.pendingTitle')` fallback naturally lands on 「待回答问题」 —
 *  the mechanism name is a key, never a display fallback. Named → the name. */
export function askSourceLabel(source) {
  const parts = askSourceParts(source);
  return parts ? parts.full : '';
}

/** 归属标注**两段化**（归属命名批 2026-11-05 作者令，逐字：「现在的后台agent的
 *  问题卡片还是只会显示stand，delegate现在不都是有name吗，node也有name，那么可以
 *  显示 delegate name，node就是 delegate name · node name」）。
 *
 *  返回 `{ parent, node, full }` 或 `null`（无名根自问 = 不挂标注，N5 同口径）。
 *  段① `parent` = **发起实体自身名**（不是项目名），判据顺序：
 *    · `ownerName`（引擎侧 `DelegateRegistry.ownerNameOf` 单点解析的 delegate 批次
 *      短名，随 askUser 载荷下发）⇒ delegate 会话提问/委托流程内节点提问都取它；
 *      **前端不按 sessionId 猜名**（名字只认后端下发的值）。
 *    · 项目上下文（项目节点 / 项目分发器）无 delegate 归属 ⇒ 回落项目名（现状不变）。
 *    · 其余 = 问者显示名（Nebula 自问 = 用户名；内核 ask = `subagent · 摘要`）。
 *  段② `node` = 节点名（项目节点 = 节点名、项目分发器 = "dispatcher"）；delegate
 *  自问无第二段 ⇒ 只渲染段①。回落纪律：owner 与 project 皆缺且问者无名 ⇒ `null`
 *  （调用点各自的既有空态文案接管），绝不渲染空白/id/undefined。 */
export function askSourceParts(source) {
  const asker = (source && source.agentName) || '';
  const owner = String((source && source.ownerName) || '').trim();
  const node = String((source && source.nodeName) || '').trim();
  const project = String((source && source.project) || '').trim();
  // ① delegate 归属：段① = 批次短名；段② = 节点名（delegate 自问无节点 ⇒ 单段）
  if (owner) return { parent: owner, node, full: node ? `${owner} · ${node}` : owner };
  // ② 项目上下文（项目节点 / 项目分发器）：段① = 项目名（现口径不变）
  if (project) {
    const second = node || agentDisplayName(asker);
    return second ? { parent: project, node: second, full: `${project} · ${second}` } : null;
  }
  // ③ 非项目会话：问者自身显示名（无名根自问 ⇒ null，N5 口径）
  const selfName = (!asker || asker === ROOT_AGENT_KEY)
    ? (isRootNamed() ? rootDisplayName() : '')
    : agentDisplayName(asker);
  return selfName ? { parent: selfName, node: '', full: selfName } : null;
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
    // 归属命名批（2026-11-05）：delegate 批次短名——待办条与卡片共用同一
    // `askSourceParts` 口径（同族两面必须同名，不许条上一套卡上另一套）。
    ownerName: msg.ownerName || '',
    // 溢出转待办批（2026-11-18）：本次提问的题目载荷（items）随镜像登记。
    // 单一来源：待办面要渲染可作答的选项，就必须有题目本体；旧口径 toEntry 只留
    // `summary`（40 字摘要），待办面无从渲染 ⇒ 只能再要一份状态（正是本批禁止的
    // 「两处各存一份」）。帧载荷（live 首发 / 快照重放）本来都带 items，登记零成本。
    // （attachments 仍然不进镜像——待办面不消费，见 main.js askUser 腿注。）
    items: Array.isArray(msg.items) ? msg.items : [],
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
  ensureAskObserver();
  renderBar();
  schedulePlacementRerender();
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
  if (pending.size === 0) dropAskObserver();
  renderBar();
  schedulePlacementRerender();
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
  if (pending.size === 0) dropAskObserver(); else ensureAskObserver();
  renderBar();
  schedulePlacementRerender();
}

/** #250 ③: snapshot query failed (gateway sent `failed:true`). Keep the local
 *  mirror (a failed sync says nothing about what is still pending) and make the
 *  failure visible — degrading to "shows 0 pending" would be a silent lie. */
export function pendingSnapshotFailed() {
  window.__showToast?.(t('askUser.pendingSyncFailed'), 'error');
}

export function pendingAskCount() { return pending.size; }

// ---------- 溢出转待办：判据单点（本批） ----------
//
// 全部判据只在这里定义一次：`askPlacement` 是唯一「这张卡在对话面还是已转入待办」
// 的判定函数；`inlineAsks` / `transferredAsks` 是它的两个投影读口；
// `renderBar`（对话面）与待办面渲染器（askTodoList.js）都从这里取，禁各自再判。
// 判据文字与边界见文件头「溢出转待办批」。

/** 文档句柄（测试缝：__setDocument 可换；产品路径恒 = document）。 */
let docRef = null;
function doc() {
  if (docRef) return docRef;
  try { docRef = document; } catch { /* 无 DOM 环境 */ docRef = null; }
  return docRef;
}
/** 测试缝（夹具/单元测试用）：换文档与卡片定位器；产品路径不调用。 */
export function __setDocument(d) { docRef = d || null; }

/** 卡片定位器（默认 = 文档内 `.option-box[data-request-id]` 查询）。 */
let cardLocator = null;
export function __setCardLocator(fn) { cardLocator = fn || null; }

/**
 * 站点是否有「已加载的对话上下文」——判据的前置条件（见文件头边界①）。
 * 无上下文（纯镜像夹具 / 会话尚未建面）⇒ 不判定转出，保持本批之前的镜像行为。
 * @returns {boolean}
 */
function conversationLoaded() {
  const d = doc();
  if (!d) return false;
  if (d.getElementById && d.getElementById('chat')) return true;
  return !!(d.querySelector && d.querySelector('.chat'));
}

/** 该 requestId 的卡片是否在已加载的消息页窗口内（DOM 在场）。 */
function cardInWindow(requestId) {
  if (!requestId) return false;
  if (typeof cardLocator === 'function') return !!cardLocator(requestId);
  const d = doc();
  if (!d || typeof d.querySelector !== 'function') return false;
  let esc = String(requestId);
  const CSSRef = d.defaultView && d.defaultView.CSS;
  if (CSSRef && typeof CSSRef.escape === 'function') esc = CSSRef.escape(esc);
  else esc = esc.replace(/["\\]/g, '\\$&');
  return !!d.querySelector('.option-box[data-request-id="' + esc + '"]');
}

/**
 * 判据单点：待答问题的归属。
 * @param {string} requestId
 * @returns {'inline'|'todo'|null} null = 该问题不挂起（已答/已关，无归属）
 */
export function askPlacement(requestId) {
  if (!requestId || !pending.has(requestId)) return null;
  if (!conversationLoaded()) return 'inline'; // 边界①：无对话上下文 ⇒ 既有行为
  return cardInWindow(requestId) ? 'inline' : 'todo';
}

/** 仍在对话面（待办条 + 计数气泡）的那批，hub 序（旧在前）。 */
export function inlineAsks() {
  return orderedEntries().filter(e => askPlacement(e.requestId) === 'inline');
}

/** 已转入待办面（弹窗/计数气泡都不得再出现）的那批，hub 序（旧在前）。 */
export function transferredAsks() {
  return orderedEntries().filter(e => askPlacement(e.requestId) === 'todo');
}

/** 只读投影（供待办面渲染；条目形状 = toEntry 产物，含 items 题目载荷）。 */
export function pendingAskEntry(requestId) { return pending.get(requestId) || null; }

/** 归属签名（只含归属，不含时间戳）——重估防抖环的判据：签名不变即不重建。 */
function placementSignature() {
  const t = transferredAsks().map(e => e.requestId);
  const i = inlineAsks().map(e => e.requestId);
  return 'T:' + t.join(',') + '|I:' + i.join(',');
}
let lastSignature = null;

// ── 重估（DOM 观察 + rAF 合并；只有存在挂起问题时才挂观察者）────────────

let askObserver = null;
let rafId = 0;
const placementListeners = new Set();

/**(待办面) 订阅归属变化；返回退订函数。 */
export function onAskPlacementChange(fn) {
  if (typeof fn !== 'function') return () => {};
  placementListeners.add(fn);
  return () => placementListeners.delete(fn);
}

function firePlacementChange() {
  for (const fn of [...placementListeners]) {
    try { fn(); } catch { /* 监听者断链不传染 */ }
  }
}

/** rAF 合并的一次重估：签名变化才重建待办条 + 通知订阅方。 */
function schedulePlacementRerender() {
  const d = doc();
  if (!d) return;
  if (rafId) return;
  const raf = (d.defaultView && d.defaultView.requestAnimationFrame)
    ? d.defaultView.requestAnimationFrame.bind(d.defaultView)
    : (fn) => setTimeout(fn, 16);
  rafId = raf(() => {
    rafId = 0;
    const sig = placementSignature();
    if (sig === lastSignature) return; // 防抖环：自身重建引起的 DOM 变化到此止步
    lastSignature = sig;
    renderBar();
    firePlacementChange();
  });
}

/** 显式重估（历史页加载 / 会话切换等外部时机可调用；本身也幂等）。 */
export function notifyAskPlacement() { schedulePlacementRerender(); }

function ensureAskObserver() {
  const d = doc();
  if (askObserver || !d || !d.body || typeof MutationObserver === 'undefined') return;
  askObserver = new MutationObserver(() => schedulePlacementRerender());
  askObserver.observe(d.body, { childList: true, subtree: true });
}
function dropAskObserver() {
  if (askObserver) { askObserver.disconnect(); askObserver = null; }
}

// ── 答复：帧构造 + 提交（待办面作答入口）────────────────────────────────

/**
 * 答复帧**唯一构造器**（chat.js 卡内确认腿同用；键集与既有帧逐字节同形）。
 * @param {{sessionId:string, requestId?:string, answers:Array<string>,
 *   secretSlots?:Array<any>, answerAttachments?:Array<any>}} o
 * @returns {Record<string, any>}
 */
export function askAnswerFrame({ sessionId, requestId, answers, secretSlots, answerAttachments }) {
  return {
    type: 'askUserAnswer',
    sessionId,
    answers,
    ...(Array.isArray(secretSlots) && secretSlots.length > 0 ? { secretSlots } : {}),
    ...(Array.isArray(answerAttachments) && answerAttachments.length > 0 ? { attachments: answerAttachments } : {}),
    ...(requestId && { requestId })
  };
}

/** 单请求单飞（幂等 + 竞态先到者胜的前端半面）：在飞 ⇒ 后来者直接 no-op。 */
const inFlightAnswers = new Set();

/** 卡片锁定单一收口的动态入口（chat.js closeAskUserCard；测试缝可换）。 */
let cardCloser = null;
export function __setCardCloser(fn) { cardCloser = fn || null; }
async function closeCard(sessionId, requestId, note) {
  if (typeof cardCloser === 'function') { cardCloser(sessionId, requestId, note); return; }
  try {
    const chat = await import('./chat.js');
    if (typeof chat.closeAskUserCard === 'function') chat.closeAskUserCard(sessionId, requestId, note);
  } catch { /* 无卡宿主（夹具）⇒ 本地无可关之卡，零副作用 */ }
}

/**
 * 待办面作答入口（与卡内作答**同一收口**：同一帧构造器 + 同一个卡片锁定单点）。
 *
 * 幂等 / 竞态（前端半面，实测见 tests/ask-todo-lifecycle.spec.mjs）：
 *   · 同一 requestId 已不挂起（已答/已关/他人先答）⇒ `not-pending` no-op，**不再产生
 *     第二条回答**；
 *   · 同一 requestId 在飞 ⇒ `in-flight` no-op（先到者胜）；
 *   · 提交后本地即收口（pending 摘除 + 卡片锁定）⇒ 第二处立刻读到已答态，
 *     反向不对称（待办已答而原卡仍可作答）在本地被结构性排除；引擎侧 hub 是权威
 *     仲裁（重复答复 `ask-already-answered`），两半合起来 = 先到者胜。
 * @param {{requestId:string, sessionId?:string, answers:Array<string>, answerText?:string}} o
 * @returns {{ok:boolean, reason?:string}}
 */
export function submitAskAnswer({ requestId, sessionId, answers, answerText }) {
  const entry = pending.get(requestId);
  if (!entry) return { ok: false, reason: 'not-pending' };
  if (inFlightAnswers.has(requestId)) return { ok: false, reason: 'in-flight' };
  if (typeof wsSend !== 'function') return { ok: false, reason: 'no-transport' };
  inFlightAnswers.add(requestId);
  try {
    wsSend(askAnswerFrame({
      sessionId: entry.sessionId || sessionId,
      requestId,
      answers,
    }));
  } catch (err) {
    inFlightAnswers.delete(requestId);
    console.error('[askPending] submitAskAnswer send failed', err);
    return { ok: false, reason: 'send-failed' };
  }
  // 本地收口（先到者胜的可见面）：摘镜像 + 锁卡——与 hub 广播腿
  // （main.js askUserClosed → closeAskUserCard + removePendingAsk）同一个单点。
  const note = answerText || t('askUser.answeredElsewhere');
  removePendingAsk(requestId);
  closeCard(entry.sessionId || sessionId, requestId, note);
  window.dispatchEvent(new CustomEvent(EVENTS.SESSION_ATTENTION, {
    detail: { sessionId: entry.sessionId || sessionId, attention: false }
  }));
  // 在飞标记随镜像摘除即失去意义（下一次提交因 not-pending 早退），下一拍清位。
  setTimeout(() => inFlightAnswers.delete(requestId), 0);
  return { ok: true };
}

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
 *  oldest (keeps the badge's documented "jump to the oldest" behaviour).
 *  溢出转待办批（2026-11-18）：只看**仍在对话面**的那批——已转入待办的问题
 *  不再弹窗、不进计数气泡，跳转链自然也不得把它当「下一张待办」。 */
function nextEntry() {
  const list = inlineAsks();
  if (list.length === 0) return null;
  const idx = list.findIndex(e => e.requestId === lastFocusedRequestId);
  return idx < 0 ? list[0] : list[(idx + 1) % list.length];
}

function renderBar() {
  const bar = barEl();
  const badge = badgeEl();
  sweepAged(); // 超龄自愈（重启孤儿对账批）：任何渲染/对账点先摘僵尸条目再出数
  // 溢出转待办批：对话面只承载**仍在分页内**的那批（判据单点 askPlacement）。
  // 已转入待办的既不出行也不计数——它们改由待办面承载（同一对象、同一份状态）。
  const entries = inlineAsks();
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
 *  todo signal" this batch removes; #250 ⑥ same rule).
 *
 *  待办分家批（2026-10-30）：导出复用——待办面板的 AskUser 镜像行（name=ask-*）
 *  点击同样跳回提问卡作答（闭环 Ask→待办→答→销账）。 */
export function jumpToAsk(entry) {
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
