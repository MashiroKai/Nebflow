import state from './state.js';
import { findViewBySessionId, setActiveView, activeView, chatViews } from './chatView.js';
import { isBgAgentId } from './utils.js';
import { addNotification } from './notificationBanner.js';
import { brand, getWebsiteLoginUrl } from './brand.js';
import { armSessionKeepalive } from './sessionGate.js';
import { key } from './branding.js';
import { t } from './i18n.js';

// Long-lived token cookie name follows the brand namespace. This is one of
// the legacy credential copies the B' hook retires (purgeLongLivedCopies
// below) — the live credential is the HttpOnly session cookie.
const TOKEN_COOKIE = key('token');

// ── Exported protocol typedefs (P2-3 core contracts) ──────────────────────

/**
 * Incoming WS event from the backend (protocol.scala). `type` discriminates;
 * events carry session routing keys plus event-specific payloads - the index
 * signature keeps the protocol permissive while the named fields pin the
 * routing contract every event shares.
 * @typedef {{ type: string, sessionId?: string, rootSessionId?: string, nodeSessionId?: string, agentId?: string, delta?: string } & Record<string, any>} WSIncomingMessage
 */

/**
 * Outgoing WS message sent via sendWs. `type` is the command discriminator -
 * but NOTE: plain user-input messages deliberately OMIT it (the backend
 * treats a type-less message with `content` as user input; see input.js).
 * @typedef {{ type?: string } & Record<string, any>} WSOutgoingMessage
 */

/**
 * Handler registered via onMessage for a specific incoming event type.
 * `view` is the ChatView owning msg.sessionId (null when hidden-gated).
 * @typedef {(msg: WSIncomingMessage, view: any) => void} WSMessageHandler
 */

// ── Flow-step interceptor (registered by flowAgentPopup.js) ─────────────
// ws.js must NOT import flowAgentPopup.js directly: that creates a circular
// dependency (flowAgentPopup.js imports onMessage/sendWs from ws.js) which
// throws a TDZ ReferenceError when flowAgentPopup.js calls onMessage() at
// module top level before ws.js has finished initializing `handlers`.
// flowAgentPopup.js registers its interceptor here instead.
let flowStepInterceptor = null;
export function setFlowStepInterceptor(fn) { flowStepInterceptor = fn; }

// ── Background-agent step interceptor (registered by bgAgentPopup.js) ────
// Same pattern as flowStepInterceptor. Checked FIRST so background sub-agent
// events (nodeSessionId starts with "delegate-"/"subtask-" - backend protocol) don't get
// swallowed by the flowStepInterceptor which claims all nodeSessionId events.
let bgAgentStepInterceptor = null;
export function setBgAgentStepInterceptor(fn) { bgAgentStepInterceptor = fn; }

// ── Team lifecycle meta applier (registered by flowAgentPopup.js) ─────────
// The team branch routes converted events itself; the popup footer still
// needs raw lifecycle meta. Injected to avoid a ws→popup circular import.
let teamMetaApplier = null;
export function setTeamMetaApplier(fn) { teamMetaApplier = fn; }

// ── Hidden-view render gating ─────────────────────────────────────────────
// Popup ChatViews (flow / bg-agent / team) have visible === false while their
// popup is closed. Streaming events for hidden views are dispatched with
// view=null so chat.js handlers skip DOM rendering entirely - the global
// per-session buffers in state.js (sessionTexts/sessionThinkingBuffers/...)
// still accumulate, so nothing is lost; the popup re-renders from backend
// history when opened (dirtyWhileHidden forces the refresh).
// Interactive events (askUser/askPermission) are exempt: they keep the real
// view and render into the hidden container immediately - low frequency,
// negligible cost, and the prompt must exist when the popup opens.
function streamDispatchView(view) {
  if (view && view.visible === false) {
    view.dirtyWhileHidden = true;
    return null;
  }
  return view;
}

/**
 * Convert agent* events (agentTextDelta, agentToolStart, etc.) to standard
 * chat events (textDelta, toolStart, etc.) so the same rendering pipeline
 * in chat.js handles both the main chat and flow agent popups.
 * The converted event has sessionId = nodeSessionId so it routes correctly.
 */
function convertAgentEvent(msg) {
  const sid = msg.nodeSessionId;
  if (!sid) return null;
  switch (msg.type) {
    case 'agentStart':
      return { type: 'agentStart', sessionId: sid, agentId: msg.agentId || msg.name, name: msg.name };
    case 'agentTextDelta':
      // B2 (RC-2): the block/round identity of the frame travels with the delta
      // so sub-agent popups get the same deterministic segment boundary the
      // primary window gets. `undefined` when the backend is older ⇒ unchanged.
      return { type: 'textDelta', sessionId: sid, delta: msg.delta || '', round: msg.round, block: msg.block };
    case 'agentThinking':
      return { type: 'thinkingDelta', sessionId: sid, delta: msg.delta || '', round: msg.round, block: msg.block };
    case 'agentToolCallDetected':
      return { type: 'toolCallDetected', sessionId: sid, name: msg.name || '' };
    case 'agentToolStart':
      return { type: 'toolStart', sessionId: sid, label: msg.label || '' };
    case 'agentToolEnd':
      return { type: 'toolEnd', sessionId: sid, label: msg.label || '', summary: msg.summary || '', content: msg.content || '', isError: msg.isError || false, input: msg.input || null };
    case 'agentToolHeartbeat':
      // 审计 20260903 子项①：工具执行期心跳——转换为标准事件喂活 busy timer。
      return { type: 'toolHeartbeat', sessionId: sid, label: msg.label || '' };
    case 'agentDone':
      return { type: 'done', sessionId: sid, model: msg.model, contextWindow: msg.contextWindow, inputTokens: msg.inputTokens, compactThreshold: msg.compactThreshold, outputTokens: msg.outputTokens };
    case 'usageUpdate':
      // #308 actual model: forward the round's actual model so popup badges
      // can live-refresh. model is None on older backends → falls back cleanly.
      return { type: 'usageUpdate', sessionId: sid, inputTokens: msg.inputTokens, contextWindow: msg.contextWindow, compactThreshold: msg.compactThreshold, outputTokens: msg.outputTokens, model: msg.model };
    case 'agentEnd':
      return null; // no standard equivalent
    default:
      return null;
  }
}

/** Reflect the connection state onto the send button (doubles as the connection
 *  indicator). Defined here (not imported from chat.js) to avoid a circular
 *  dependency: chat.js imports sendWs from ws.js. */
function syncSendButtonConnState() {
  const btn = activeView?.dom?.sendBtn || document.getElementById('send-btn');
  if (btn) btn.classList.toggle('disconnected', !state.connected);
  // §4 (todo-panel-spec): circle completion controls dim/disable while offline
  const taskList = document.getElementById('task-list');
  if (taskList) taskList.classList.toggle('task-ws-down', !state.connected);
}

// ---------- Handler registry (supports multiple handlers per type) ----------
const handlers = {};

// ── Message-type filter sets (module-level for O(1) lookup) ─────────────
// Hoisted out of onmessage so we don't allocate three ~20-entry arrays and run
// three O(n) Array.includes() calls on every single inbound WS frame. During
// agent streaming (text/tool deltas can fire dozens of times per second) this
// per-message allocation + linear scan was measurable main-thread overhead
// and contributed to CSS-spinner jank (the compositor can't paint frames while
// the main thread is busy).
const GLOBAL_MSG_TYPES = new Set([
  'sessionList', 'serverConfig', 'toolsList', 'agentList', 'agentSessionList',
  'agentSystemPrompt', 'agentSystemPromptSaved',
  'configData', 'configUpdated', 'configUpdateFailed',
  'toolResultTtl', 'toolResultTtlSaved', 'modelOptions',
  'memoryData', 'memorySaved', 'memoryStatus', 'memoryChanged',
  // memoryQueueAlert retired 2026-09-25 (govmemory batch): the memory-queue
  // mechanism and its consolidation track are gone; the engine no longer
  // emits this frame.
  'rulesData', 'rulesSaved', 'rulesDeleted', 'rulesStatus',
  'browseResult',
  // auth-trim 批（2026-10-20 作者令）：设置页更新功能退役（updateCheck.js 已删），
  // 但这些帧类型**保留在 GLOBAL**——后端仍会在 CLI/中继触发更新时广播
  // updateProgress/updateResult；帧不在 GLOBAL 会被 :485-494 当会话内事件
  // ⇒ 每次相位推进都把用户正在看的视图切走。留此 = 纯路由惰性（无订阅者即无操作）。
  'updateCheckResult', 'updateStarted', 'updateCompleted',
  // Unified update progress (hotupdate 批 1 契约 · 设计 §4/§7): the update
  // orchestrator broadcasts {type:'updateProgress', phase, messageKey, ...} on
  // the same channel (wsHub.broadcast → all connections) with NO sessionId.
  // 🔴 必须入 GLOBAL：无 sessionId 的非 GLOBAL 帧会被 :485-494 当会话内事件 ⇒
  // setActiveView(primary)，把用户正在看的视图切走（每次相位推进都切一次）。
  // 本批只钉**契约**（帧类型 + 文案键），前端**消费/渲染**属批 3（G6）——此处零消费。
  // 'updateResult' = 受理/已在途/更新中/拒绝的即时应答（同为全局、无 sessionId）。
  'updateProgress', 'updateResult',
  // 既有热重启进度帧 + 其即时应答（hotupdate 批 3 · G6 后半的前端触发面）：
  //   restartStatus {type,phase,detail} —— wsHub.broadcast 到**所有**连接、无 sessionId
  //     ⇒ 不入 GLOBAL 会把**每个**用户正在看的视图切走（不止触发者）。
  //   restartResult {type,ok,error?|message?} —— 'restart' 命令的直回帧（无 sessionId）。
  // 两者都只是「既有帧 + 补消费」，零新消息类型。
  'restartStatus', 'restartResult',
  'remoteUpdateResult', 'peerListChanged',
  'activeBgTasks', 'activeAgents',
  'dropbox-message', 'dropbox-file-response', 'dropbox-file-complete', 'dropbox-file-progress', 'dropbox-file-probe', 'dropbox-history', 'dropboxError',
  'friend_event',
  // Project 域节点广播（#28 0b 契约 §2）：{type,project,nodeId,node}，无 sessionId，
  // 是全应用级广播。入 GLOBAL 使 onmessage 路由不把他们当会话内事件（不 setActiveView
  // 为 primary），保持当前视图；多个 onMessage 订阅者（projectTab / flowMapTab）都收。
  'nodeCreated', 'nodeUpdated', 'nodeCompleted', 'nodeRemoved',
  // Project 域**项目级**事件（tabrealtime 批 2026-09-17，作者裁定 (b)/(e)）：
  //   projectCreated  {type,project,row{name,workspace,agentFile,description,createdAt},mounted}
  //   projectArchived {type,project,archivedAt}
  //   projectUnmounted {type,project,unmountedAt}   （N2 project-tool 批 2026-10-06：
  //     Project 工具 action="unmount" 成功腿；载荷镜像 archived 三键形状）
  // 同族：无 sessionId 的全应用广播（后端 emit 点 = Project 创建腿 / 归档 REST 腿 /
  // unmount 工具腿，经同一 wsHub.broadcast 面）。🔴 必须入 GLOBAL——否则 :485-494 会把无
  // sessionId 的非 GLOBAL 帧当会话内事件 ⇒ setActiveView(primary)，把用户正在看的
  // 会话/面板视图抢走。
  'projectCreated', 'projectArchived', 'projectUnmounted',
  // 应用内工作区浏览器（workspace-picker Route C 兜底）响应帧：无 sessionId 的
  // 弹窗私有请求-响应。必须 GLOBAL——无 sessionId 时 GLOBAL 保视图不换（:457 语义），
  // TERMINAL 会把 activeView 换成 primary。
  'wsBrowseList', 'wsBrowseMkdir',
  // 链控状态广播（chainview 批 2026-10-01 · 引擎面单点 ProjectActor.chainStateFrame）：
  //   {type:'chainState', project, chainId, status:'active'|'paused'|'cancelled',
  //    pausedAt|null, cancelledAt|null} —— 无 sessionId 的全应用广播（引擎面每条链控
  //   成功腿经既有 wsHub.broadcast 发一帧）。🔴 必须入 GLOBAL：否则 :647 会把无
  //   sessionId 的非 GLOBAL 帧当会话内事件 ⇒ setActiveView(null)，把用户正在看的
  //   会话/面板视图抢走（每次链控动作抢一次）——与上方 projectCreated/nodeCreated 同款。
  'chainState',
  // 链级取消回帧（chaincancel 批 2026-09-17，R2）：{type, ok, chainId, chainTitle?,
  // cancelled[]/preserved[]/skipped[]/injected/notified, error?}——无 sessionId 的
  // 弹窗私有请求-响应（与 cancelAgentResult 同族）；必须 GLOBAL：无 sessionId 时
  // GLOBAL 保视图不换（flow-map 面板正在看的项目不该被取消动作切走视图）。
  'chainCancelResult',
  // askucancel 批位2（2026-10-08）：askUserCancel 帧的裁决回执 {type, ok,
  // requestId, state, error?} —— 无 sessionId 的**本连接**直回帧（与
  // chainCancelResult 同「弹窗私有请求-响应」族）。🔴 必须入 GLOBAL：无 sessionId
  // 的非 GLOBAL 帧会被 :485-494 当会话内事件 ⇒ setActiveView(primary)，取消一张
  // 后台问题的卡也会把用户正在看的视图抢走。消费点 = main.js（仅 !ok → toast）。
  'askUserCancelResult',
  // M2 本地加速批（2026-10-20 · 设计 v1 §6.1/§4.4）：三条无 sessionId 的引擎广播/
  // 直回帧。🔴 必须入 GLOBAL：无 sessionId 的非 GLOBAL 帧会被 :485-494 当会话内
  // 事件 ⇒ setActiveView(primary)，把用户正在看的视图抢走。
  //   nf-cache-invalidate {type,path} —— 引擎写路径广播（本机文件被改/删/移），前端
  //     账本按 path 淘汰（main.js 订阅 → nfFileCache.nfCacheInvalidatePath）。
  //   fileProgress {type,path,device,bytesReceived,totalBytes,chunkMs} —— 设备取件
  //     分块拉取腿逐块进度（explorer.js 订阅 → 取件进度条）；进度**不得**切视图。
  //   fileTransferCancelResult {type,path,device,active} —— fileTransferCancel 的
  //     直回帧（无在途 = active:false，前端静默清理）。
  'nf-cache-invalidate', 'fileProgress', 'fileTransferCancelResult',
]);
const TERMINAL_MSG_TYPES = new Set([
  'done', 'error', 'interrupted', 'maxTokens', 'sessionBusy',
  'compactStart', 'compactComplete', 'compactFailed',
  // taskListUpdate 已移出（2026-09-05 10:54 裁定：旧任务区退役）——非活跃
  // 会话帧被入口过滤器丢弃，活跃会话帧无订阅者 no-op（后端照发，零触碰）。
  'backgroundTaskUpdate',
  'askUser', 'askPermission',
  // askUserAnswered 已摘（uiclean 批 2026-09-15）：该帧的唯一生产者（hub
  // handleChatInputAnswer）随输入框直通腿退役（e59ed251d），接收点亦已摘除 ⇒
  // 路由表项冗余且不再被引用（详见本批报告 C 项引用读数）。
  // D6 批 F2: source-death close frame (engine cascade = batch E2; frontend
  // handling already in place) — same view routing as askUser.
  'askUserClosed',
  // 工作区目录选择结果（workspace-picker 批次）：{sessionId, requestId, path|
  // cancelled|fallback}——askUser 交互家族同语义（带 sessionId，视图路由同 askUser）。
  'workspaceDirPicked',
  'frozen', 'resumed', 'agentFrozen', 'agentResumed',
  'errorEscalated', // error-recovery escalation → parent/user decision card
  'taskStuck', // sub-agent management panel (2026-08-22): stuck visibility for any session
  'cancelAgentResult', // management panel stop action result (2026-08-23 upgrade)
  'historyPage' // flow agent popups receive historyPage with their own sessionId
]);
const STREAM_MSG_TYPES = new Set([
  'thinkingDelta', 'textDelta', 'textDone',
  'toolCallDetected', 'toolCallStart', 'toolCallChunk', 'toolStart', 'toolEnd',
  'toolHeartbeat', // 审计 20260903 子项①：工具执行期心跳（喂活 busy timer）——
  'agentToolHeartbeat', // 非 active 会话也必须过过滤器才能到 resetStreamTimeout。
  'toolArgDelta',
  'roundComplete',
  'agentStart', 'agentTextDelta', 'agentToolCallDetected',
  'agentToolStart', 'agentToolEnd', 'agentEnd',
  'agentThinking', 'agentRetryStatus', 'agentDone',
  // 任务工具重做 (2026-08-30): team 域任务帧（{team, tasks}，无 sessionId）——
  // 裁定② 统一进 Nebula 会话任务列表面板（taskList.js 合并渲染）。
  // teamTaskListUpdate 已移出（2026-09-05 10:54 裁定：旧任务区退役）——
  // 无 sessionId 帧直达分发但无订阅者 = no-op（后端照发，零触碰）。
  // Legacy flow-canvas events (flowMail/flowStarted/flowNodesAdded/
  // flowProgress/flowCompleted/teamList/treeBranch*) removed 2026-09-05
  // 旧 UI 退役 — the backend may still broadcast them; unknown types with no
  // registered handler are dispatched to an empty handler list (no-op), and
  // off-session frames are dropped by the entry filter above.
  // #308 actual model: sub-agent usageUpdate (sessionId = nodeSessionId) must
  // survive the entry filter below to reach the popup live-refresh path.
  // Without this, the event is silently dropped for non-active sessions.
  'usageUpdate'
]);

/**
 * @param {string} type - incoming event name (TERMINAL_MSG_TYPES / STREAM_MSG_TYPES)
 * @param {WSMessageHandler} handler
 * @returns {() => void} unsubscribe - removes this handler (one-shot response
 *   listeners like fileSaved rely on it; push() alone returns a number, and
 *   calling that as a function throws inside the dispatch loop).
 */
export function onMessage(type, handler) {
  if (!handlers[type]) handlers[type] = [];
  handlers[type].push(handler);
  return () => {
    const list = handlers[type];
    const i = list.indexOf(handler);
    if (i >= 0) list.splice(i, 1);
  };
}

// ---------- Reconnect callback registry ----------
// Called after the connection is re-established (not on first connect).
// Used by main.js to re-fetch session history that may have been generated
// by the agent while the frontend was disconnected (e.g. during OS sleep).
const reconnectCallbacks = [];
let hasConnectedBefore = false;

export function onReconnect(callback) {
  reconnectCallbacks.push(callback);
}

// ---------- Disconnect callback registry ----------
// Mirror of onReconnect for UI that must react to connection loss (e.g. the
// friends chat modal's "reconnecting" bar + disabled input, spec §7).
const disconnectCallbacks = [];

export function onDisconnect(callback) {
  disconnectCallbacks.push(callback);
}

// ---------- Reconnection state ----------
// No hard limit on attempts - this is a desktop app; the connection should
// always recover from sleep / wake, network changes, or server restarts.
let reconnectAttempts = 0;
let reconnectTimer = null;
const BASE_RECONNECT_DELAY = 2000;
const MAX_RECONNECT_DELAY = 30000;

function reconnectDelay() {
  const delay = Math.min(BASE_RECONNECT_DELAY * Math.pow(2, reconnectAttempts), MAX_RECONNECT_DELAY);
  return delay * (0.8 + Math.random() * 0.4);
}

function scheduleReconnect() {
  if (reconnectTimer) return; // already scheduled
  const delay = reconnectDelay();
  reconnectAttempts++;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);
}

/** Force immediate reconnect, skipping exponential backoff. */
export function forceReconnect() {
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  reconnectAttempts = 0;
  // Close the old connection cleanly before opening a new one - prevents orphan sockets.
  if (state.ws) {
    try { state.ws.onclose = null; state.ws.close(); } catch (_) {}
    state.ws = null;
  }
  connect();
}

// ---------- Send ----------
// 观察项① 收口（hotupdate 批 3 · G6；批 1 判词裁「可接受 / 留后续，建议批 3 落地进度面
// 时一并收口」）：连接未开时 sendWs **静默丢弃**（无报错、无队列、无回调）——点击
// 「立即更新」/「重启」时该帧直接消失，界面停在「更新中…」。
//
// 本批收口形态 = **有界 pending 队列**（二选一中的队列案）：未连接 ⇒ 入队；连接开启时
// 按序补发。🔴 生效面 = 本函数这一个**单点**，因此对 ALL WS 命令一致（不是给「立即更新」
// 开的特例——没有任何命令/按键白名单）。
// 边界（逐条）：① 容量 100，溢出丢**最旧**（有界，防长时间离线导致内存无界增长）；
// ② 补发在 onopen 里**先于**既有引导命令（用户显式意图优先；引导命令自带幂等语义，
//    后发无副作用）；③ 补发中途发送异常 ⇒ 该项退回队首、停止本轮补发（保序），剩余项
//    留到下一次 open。读取面 `pendingSendCount()` 只作诊断读数。
const PENDING_SEND_CAP = 100;
let pendingSends = [];

/** 现读 pending 队列长度（诊断/验证读数；不改变发送语义）。 */
export function pendingSendCount() {
  return pendingSends.length;
}

/** 连接开启后按序补发 pending 队列（仅由 onopen 调用一次）。
 *  resend-dedup 批（修复批 #569）幂等契约：本函数**无条件按序补发**是「真未送达
 *  ⇒ 补送不丢」的承担腿（离线入队的帧从未被写过任何 socket ⇒ 必然未送达），
 *  行为保持不变；「已送达还重发」的抑制**不在这一层**——WS 无应用层 ack，本层
 *  无从判定送达，判定单点 = 服务端 clientMessageId 去重（AgentActor
 *  recentMessageIds 命中 ⇒ 丢弃 + `messageDuplicate` 回执）。因此：用户输入帧
 *  （content 帧 / 排队 drain / 立即发送）的幂等键在 input.js 生成并**跨重发稳定**
 *  （同一条逻辑消息一个 id），重发因此天然无害；ping 等控制帧重发无副作用。 */
function flushPendingSends() {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  while (pendingSends.length > 0) {
    const msg = pendingSends.shift();
    try {
      state.ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg));
    } catch (e) {
      console.error('[ws] pending send failed - keeping it at the head of the queue:', e);
      pendingSends.unshift(msg);
      return;
    }
  }
}

/** @param {WSOutgoingMessage | string} msg */
export function sendWs(msg) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg));
    return true;
  }
  // 未连接 ⇒ 入有界队列（不静默丢弃）。溢出丢最旧。
  pendingSends.push(msg);
  while (pendingSends.length > PENDING_SEND_CAP) pendingSends.shift();
  return false;
}

// ---------- Connect ----------
// The WS handshake authenticates with the HttpOnly session cookie — the live
// credential since phase 3b (stage D removed the legacy credential rails
// server-side). One cheap same-origin authcheck fetch fires before the first
// WebSocket attempt so the B' retirement hook runs the moment the session
// cookie is VERIFIED working:
//   204 + rail header `session` → purge the long-lived credential copies;
//   anything else → inconclusive or diagnostic; the handshake itself is the
//              authoritative probe, and a rejected one lands on the
//              paste-ticket entry (see onclose).
let authProbePromise = null;
let authProbeSettled = false;

// Phase 3b stage B' (plan §6.6): once the HttpOnly session cookie has VERIFIED
// (probe 204 + rail header `session`), the long-lived localStorage token and
// the non-HttpOnly legacy cookie are copies that can be retired — this flag
// records that the purge ran and suppresses every later re-write of them for
// the rest of the page lifecycle (the cookie rewrite below).
let longLivedPurged = false;
// Stage B: the paste-ticket entry is shown at most once per page (a rejected
// handshake then keeps reconnecting silently behind the modal).
let ticketEntryShown = false;

// Probe target: GET /api/nf-authcheck — validated by the unified GatewayAuth
// criterion (session cookie; Bearer for non-browser clients), so a bare
// same-origin fetch exercises exactly the cookie path the WS handshake relies
// on. Semantics are unambiguous: valid credential → 204, invalid → 403. The
// endpoint touches zero disk and has no other side effect.
//
// (Historical: this used to point at a bare GET /api/nf-file and read
// "400 = missing path ⇒ cookie works"; once the read endpoint went
// ticket-only, a credential-less request answered 401 — the same status a
// broken cookie produces — so the probe moved to the dedicated authcheck
// endpoint.)
function probeCookieAuth() {
  return fetch('/api/nf-authcheck', {
    credentials: 'same-origin',
    cache: 'no-store',
    signal: AbortSignal.timeout(3000),
  }).then(resp => {
    if (resp.status === 204 && !longLivedPurged) {
      // Stage B' trigger (plan §6.6): the server answers the read-only rail
      // header on every 204. ONLY `session` means the HttpOnly session cookie
      // is verified working — the one moment retiring the long-lived copies
      // cannot cut a live credential (same-origin fetch rides the session
      // from here on). The other rail value (bearer) is diagnostic and
      // deliberately not consumed.
      if (resp.headers.get(`x-${brand.lowerName}-credential`) === 'session') {
        purgeLongLivedCopies();
        // 会话保活腿（session TTL 批 2026-10-21）：首次证实已入座（204 +
        // rail=session）即武装 12h 一拍的 authcheck 保活——健康长命标签页在
        // 零 reload 期间也持续拉动服务端滑动窗与浏览器 cookie Max-Age 双续
        //（30d 绝对上限 / 7d 闲置窗，GatewaySessionStore）。幂等。
        armSessionKeepalive();
      }
    }
  }).catch(() => { /* inconclusive - proceed */ });
}

// Remove the long-lived credential copies (plan §6.6): the branded
// localStorage key, the branded non-HttpOnly cookie (both attribute
// variants — same-name-different-attrs are separate jar entries a plain
// write cannot replace), and — on rebrand day — the hardcoded legacy
// `nebflow_token` spelling, so the "no nebflow_token in the jar" assertion
// holds under every brand. Never touches the HttpOnly session cookie, so
// the WS handshake (session rail) has no empty-jar window.
function purgeLongLivedCopies() {
  const gone = '; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  const purgeCookie = (name) => {
    document.cookie = `${name}=; path=/${gone}`;
    document.cookie = `${name}=; path=/; domain=${location.hostname}${gone}`;
  };
  try { localStorage.removeItem(key('token')); } catch (e) { /* storage unavailable */ }
  purgeCookie(TOKEN_COOKIE);
  if (TOKEN_COOKIE !== 'nebflow_token') {
    purgeCookie('nebflow_token');
    try { localStorage.removeItem('nebflow_token'); } catch (e) { /* storage unavailable */ }
  }
  longLivedPurged = true;
}

// Stage B (plan §6.5): the paste-ticket entry replaces the old "reopen with
// the tokenized startup URL" fallback (that URL no longer exists — the
// startup link carries no token). Both degraded shapes (no credential /
// stale credential) land here: the user pastes a one-time console ticket
// (`nebflow token`) and the redemption face (?ticket=) exchanges it for an
// HttpOnly session cookie. Minimal card on purpose — the unified
// signed-out-card redesign belongs to period 4.
function showTicketEntry() {
  if (ticketEntryShown) return;
  ticketEntryShown = true;
  // 直接复用设置头像的统一账号登录（PKCE 直连本网关 Logto——与官网同一账号
  // 体系）。登录成功 = 网关会话建立 ⇒ WS 重连即通，onopen 收口登录门。
  // 动态 import 保持 ws.js ↔ activityBar.js 无模块环（同 micOrb 模式）。
  import('./activityBar.js').then((m) => m.activateAccountEntry()).catch(() => {});
}

// Silent host-session re-establishment (same-origin console). The relay's own
// session outlives the host's in-memory session store (every host restart
// wipes it), but the WS bridge's second leg forwards the browser's cookies to
// the host's local face — so a live relay session alone still fails the
// handshake. Before surfacing any login face, re-run the portal's own cycle
// same-origin: mint a fresh one-time ticket for the bound host (the relay
// issues it over the live tunnel) and navigate to its consoleUrl — the host
// redeems the ticket and the relayed Set-Cookie re-seats the host session;
// the reload then connects on its own.
//
// Two-plane discipline (2026-10-21 诊断修复批): a tunneled console answers to
// TWO independent session planes — the relay ingress (every /api/* and /ws
// admission is gated by the relay's own login; a dead relay session 403s at
// the ingress BEFORE the host is ever reached) and the host session store
// (wiped by every host restart). A refusal here can therefore mean either
// plane. When the portal-status leg itself is refused, probe the relay's
// PUBLIC /api/auth/login-config: on a hit, navigate to the relay's own login
// leg (/api/auth/login?return_to=<here>) — that is the ONLY login that can
// restore the ingress plane; the host-PKCE account entry cannot (it mints a
// host session the ingress still refuses), so falling through to it here
// produced the "logged in but everything still 403" trap. Direct-LAN consoles
// have no relay (login-config absent) and keep the existing fall-through to
// the login gate. Once-per-page-life guard (sessionStorage): a persistently
// failing host must not turn this into a reload loop.
async function remintHostSessionAndReload() {
  // #522 键盘点收尾：GUARD 原 `nebflow_ws_remint_at` 裸字面随品牌迁移失效——
  // 与 #500 同族迁 `key()`（branding.js 单一真源）。sessionStorage 30s 防
  // 重载闸，语义不变；旧拼写值弃置无害（最坏多一次 remint 尝试）。
  const GUARD = key('ws_remint_at');
  let last = 0;
  try { last = Number(sessionStorage.getItem(GUARD)) || 0; } catch (_) {}
  if (Date.now() - last < 30000) return false;
  try { sessionStorage.setItem(GUARD, String(Date.now())); } catch (_) {}
  try {
    const status = await fetch('/api/portal/status', { credentials: 'same-origin' });
    if (!status.ok) {
      // Ingress-plane refusal (relay session dead/expired): hand the page to
      // the relay's own login leg when this origin serves one. return_to is
      // same-origin validated server-side; an already-logged-in caller is
      // bounced straight back with no provider round trip.
      try {
        const cfg = await fetch('/api/auth/login-config', { credentials: 'same-origin' });
        if (cfg.ok) {
          const back = encodeURIComponent(location.pathname + location.search);
          window.location.href = `/api/auth/login?return_to=${back}`;
          return true;
        }
      } catch (_) { /* not a relay-served origin — keep the legacy fallback */ }
      return false;
    }
    const deviceId = (await status.json())?.boundHost?.deviceId;
    if (typeof deviceId !== 'string' || deviceId === '') return false;
    const mint = await fetch('/api/portal/console-ticket', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId }),
    });
    if (!mint.ok) return false;
    const consoleUrl = (await mint.json())?.consoleUrl;
    if (typeof consoleUrl !== 'string' || consoleUrl === '') return false;
    window.location.href = consoleUrl;
    return true;
  } catch (_) {
    return false;
  }
}


const HB_INTERVAL_MS = 30000;
const HB_PONG_GRACE_MIN_MS = 5000;
const HB_PONG_GRACE_MAX_MS = 30000;
const HB_MAX_MISSES = 3;
let hbMisses = 0;          // 连续 miss 计数（收到 pong / 新连接 / 进后台即归零）
let hbPingAt = 0;          // 最近一次 ping 的发出时刻（算 RTT）
let hbMaxRttMs = 0;        // 实测 RTT 高点（逐次成功后对折衰减：一次性抖动不会永久放宽）

/** 单次 pong 宽限：max(5s, 3×实测 RTT 高点)，上限 30s。 */
function hbGraceMs() {
  return Math.min(HB_PONG_GRACE_MAX_MS, Math.max(HB_PONG_GRACE_MIN_MS, hbMaxRttMs * 3));
}

/** 发一次 ping 并在宽限内等 pong；未等到记一次 miss —— 连续 HB_MAX_MISSES 次才判死。 */
function hbProbe() {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  state.pendingPong = true;
  hbPingAt = Date.now();
  sendWs({type: 'ping'});
  setTimeout(() => {
    if (!state.pendingPong) return;                                    // pong 已回 ⇒ 健康
    if (state.ws?.readyState !== WebSocket.OPEN) return;               // 已被 onclose 收走
    if (document.visibilityState === 'hidden') {                       // 期间进后台 ⇒ 本窗不作数
      state.pendingPong = false;
      hbMisses = 0;
      return;
    }
    hbMisses++;
    if (hbMisses >= HB_MAX_MISSES) {
      console.log(`[ws] heartbeat: no pong after ${HB_MAX_MISSES} consecutive misses, closing dead connection`);
      state.ws.close();
    }
  }, hbGraceMs());
}

/** 唤醒/回网探针（原 :706-713 平 2s 单次即 force reconnect 的容错化）：
 *  同一 ping/pong 口径，连续 maxMisses 次未回才判死。 */
function probeSocketAfterWake(maxMisses) {
  let misses = 0;
  const attempt = () => {
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;    // onclose 路径已接管
    if (document.visibilityState === 'hidden') return;                  // 后台期不判死
    state.pendingPong = true;
    hbPingAt = Date.now();
    sendWs({type: 'ping'});
    setTimeout(() => {
      if (!state.pendingPong) { hbMisses = 0; return; }                 // 活着
      if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
      if (document.visibilityState === 'hidden') { state.pendingPong = false; return; }
      misses++;
      if (misses >= maxMisses) {
        console.log('[ws] no pong after wake: consecutive misses, force reconnect');
        state.ws.close();                                              // onclose → scheduleReconnect
        return;
      }
      attempt();
    }, hbGraceMs());
  };
  attempt();
}

export function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  // Stage D: the URL credential ingestion is gone along with the server rail
  // it fed — a stale bookmarked URL carrying a credential now lands on the
  // paste-ticket entry via the normal rejected-handshake path. The only
  // remaining source for the legacy copies below is pre-existing storage,
  // which the B' purge clears once the session cookie verifies.
  const storedToken = localStorage.getItem(key('token')) || '';
  // Set auth cookie so the WebSocket connection authenticates via cookie
  // instead of exposing the token in the URL (history/logs/Referer leakage).
  // After the B' purge ran, this page lifecycle never re-writes the
  // long-lived copies (the session cookie is the live credential now).
  if (storedToken && !longLivedPurged) {
    // Skip the rewrite when the cookie already holds the right value: every
    // document.cookie op is a separate async message to the network service,
    // so a purge+set immediately followed by new WebSocket() can race - the
    // handshake may be evaluated in the empty-jar window between the purge
    // and the set → spurious 403 (observed in smoke tests). No rewrite, no
    // window.
    const desired = encodeURIComponent(storedToken);
    const current = document.cookie.match(new RegExp(`(?:^|;\\s*)${TOKEN_COOKIE}=([^;]*)`))?.[1];
    if (current !== desired) {
      // Purge stale variants first: a cookie written by an older build with
      // different attributes (Secure / domain=) is a SEPARATE jar entry that a
      // plain document.cookie write cannot replace - the server would keep
      // seeing the stale value win the cookie race.
      const gone = '; expires=Thu, 01 Jan 1970 00:00:00 GMT';
      document.cookie = `${TOKEN_COOKIE}=; path=/${gone}`;
      document.cookie = `${TOKEN_COOKIE}=; path=/; domain=${location.hostname}${gone}`;
      document.cookie = `${TOKEN_COOKIE}=${desired}; path=/; SameSite=Strict`;
    }
  }
  // First connect: run the authcheck probe once before opening the WebSocket
  // so the B' purge fires the moment the session cookie is verified — before
  // the legacy-copy rewrite below can recreate one. Deferred connects while
  // the probe is in flight simply wait for it to settle.
  if (!authProbeSettled) {
    if (!authProbePromise) {
      authProbePromise = probeCookieAuth().finally(() => {
        authProbeSettled = true;
        connect();
      });
    }
    return;
  }
  // Session-only connect: the handshake rides the HttpOnly session cookie
  // (gateway WsAuth). A rejected handshake lands on the paste-ticket entry.
  const wsUrl = `${proto}//${location.host}/ws`;
  try {
    state.ws = new WebSocket(wsUrl);
  } catch (e) {
    console.error('[ws] WebSocket constructor failed:', e);
    scheduleReconnect();
    return;
  }

  let opened = false;
  state.ws.onopen = () => {
    opened = true;
    reconnectAttempts = 0;
    state.connected = true;
    hbMisses = 0;             // freezetimeout B2: 新连接从零计 miss
    state.pendingPong = false;
    syncSendButtonConnState();
    // 离线期间入队的 WS 命令按序补发（观察项① 收口；先于以下引导命令）。
    flushPendingSends();
    if (state.thinkingMode?.enabled) {
      sendWs({type: 'setThinking', thinking: state.thinkingMode});
    }
    sendWs({type: 'setVoiceMuted', muted: localStorage.getItem('voiceMuted') === 'true'});
    sendWs({type: 'getSkills'});
    sendWs({type: 'getTeams'});
    sendWs({type: 'memoryStatus'});
    sendWs({type: 'getLlmLog'});
    sendWs({type: 'getConfig'});
    // perf-481 A4: the tool-description list (~93.5 KB) no longer rides every
    // serverConfig frame — it is fetched once per connection instead.
    sendWs({type: 'getToolsList'});
    sendWs({type: 'getModelOptions', sessionId: state.activeSessionId});
    // freezetimeout B2: 新连接从零计 miss；先清残留 interval 再武装 —— forceReconnect
    // 会置空 onclose 后 close()（不触发 onclose ⇒ 走不到下面的 clearInterval），若不在
    // 此处清掉，旧 interval 泄漏 ⇒ 双倍 ping / 双倍 miss 计数（实测 pingTs 间隔 70ms）。
    if (state.heartbeat) { clearInterval(state.heartbeat); state.heartbeat = null; }
    state.heartbeat = setInterval(() => {
      if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
      // 冻结/后台期**暂停计时**（freezetimeout B2 · 施工图 §4.1 B 面）：后台期间浏览器
      // 会掐/降频定时器，此刻的「没回 pong」不是 socket 死了，不能计入 miss。
      if (document.visibilityState === 'hidden') { hbMisses = 0; return; }
      hbProbe();
    }, HB_INTERVAL_MS);
    // On (re)connect, notify callbacks so they can fetch state that
    // requires an open WS (e.g. workspace items, scheduled tasks).
    reconnectCallbacks.forEach(cb => { try { cb(); } catch (e) { console.error('[ws] connect callback error:', e); } });
    hasConnectedBefore = true;
  };

  state.ws.onclose = () => {
    state.connected = false;
    syncSendButtonConnState();
    disconnectCallbacks.forEach(cb => { try { cb(); } catch (e) { console.error('[ws] disconnect callback error:', e); } });
    if (state.heartbeat) { clearInterval(state.heartbeat); state.heartbeat = null; }
    if (!opened) {
      // Handshake rejected before the socket ever opened. First try the
      // silent host-session re-establishment (fresh ticket → redemption →
      // reload): a live relay session with a stale host session is the
      // common shape after a host restart, and it must self-heal without
      // any login face. Only when the remint is refused (or this origin
      // doesn't serve the portal routes — direct-LAN consoles) does the
      // unified login entry appear; the reconnect loop continues behind it.
      console.warn('[ws] connect failed - attempting silent console-session re-establishment');
      // 卡窗白屏修复批（2026-10-22）：握手拒绝即广播（卡窗引导态面的快失败
      // 钩子 —— 无会话开窗在秒级落「登录过期」引导卡，不等 12s 通用超时）。
      try { document.dispatchEvent(new CustomEvent('nebflow-ws-rejected')); } catch (_) { /* 早于 document 就绪等 */ }
      remintHostSessionAndReload().then((navigating) => {
        if (navigating) return;
        addNotification('conn', t('ws.ticketEntry.notify'));
        showTicketEntry();
      });
    }
    scheduleReconnect();
  };

  state.ws.onerror = () => {
    state.connected = false;
    syncSendButtonConnState();
  };

  state.ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);

      // 通道活性水位（freezetimeout B2 · 施工图 §4.2③）：收到**任何**入站帧 = 这条
      // 通道还活着，是 busy 看门在阶梯到点时可用的唯一独立活性证据。必须在下面各
      // early-return（pong 等）之前推进。
      state.lastWsInboundAt = Date.now();

      // Pong reply - clear pending flag (used by heartbeat + wake detection)
      if (msg.type === 'pong') {
        state.pendingPong = false;
        hbMisses = 0;
        if (hbPingAt) {
          const rtt = Date.now() - hbPingAt;
          hbPingAt = 0;
          // 衰减保留：连续正常心跳会把一次性抖动收回 5s 底限。
          hbMaxRttMs = Math.max(rtt, Math.round(hbMaxRttMs * 0.5));
        }
        return;
      }

      // ── BUG 6 fix: save/restore activeView ────────────────────────────
      // activeView is a module-global (chatView.js). The bg-agent/flow
      // interceptors below call setActiveView() to render into popup ChatViews,
      // which would otherwise leak into subsequent rendering within the same
      // event-loop tick. Snapshot here and restore after each interceptor
      // branch so popup rendering never disturbs the user's current view.
      const savedView = activeView;

      // ── Message filtering ────────────────────────────────────────────
      // GLOBAL/TERMINAL/STREAM sets are module-level (see top of file) for O(1)
      // lookup and to avoid per-message allocation.
      // Sub-agent frames (nodeSessionId present) must NOT be gated on sessionId:
      // NodeRunner's routeSubagentWsSend leaves sessionId = the sub-agent's OWN id
      // (emitInjectedUserEvent already stamped it), while DelegateTool's routeWsSend
      // overwrites sessionId with the PARENT's. Every node-/dispatcher- live frame is
      // therefore dropped here, so a running sub-agent's popup shows history only
      // until the agent finishes and its ui.json lands on disk.
      // Exempt them; the owning window is decided downstream (bg-agent step
      // interceptor + the injected-bubble ownership gate in main.js).
      // Frames WITHOUT nodeSessionId evaluate this condition exactly as before —
      // same operands in the same order, one extra final operand.
      if (state.activeSessionId && msg.sessionId && msg.sessionId !== state.activeSessionId &&
          !GLOBAL_MSG_TYPES.has(msg.type) && !TERMINAL_MSG_TYPES.has(msg.type) &&
          !STREAM_MSG_TYPES.has(msg.type) &&
          !msg.nodeSessionId) {
        return;
      }

      // ── View routing ─────────────────────────────────────────────────
      // Find the ChatView that displays this session. Set it as activeView
      // so rendering functions target the correct window. No swap/restore —
      // the view object holds all its own state.
      let view = findViewBySessionId(msg.sessionId);
      // Messages without sessionId default to primary view (e.g. 'thinking'
      // events that use msg.sessionId || activeSessionId internally).
      if (!view && !msg.sessionId && !GLOBAL_MSG_TYPES.has(msg.type)) {
        view = chatViews.primary || null;
      }
      // Global messages (serverConfig, sessionList, configData…) are app-wide
      // and carry no sessionId — they must NOT null the current view. Before
      // this guard, every serverConfig broadcast called setActiveView(null),
      // tearing down the active window after any config save (STT/freeze
      // echo). Keep the pre-message view for them.
      if (msg.sessionId || !GLOBAL_MSG_TYPES.has(msg.type)) {
        setActiveView(view || null);
      } else {
        setActiveView(savedView);
      }

      // ── Background-agent popup: intercept events with nodeSessionId ─────
      // Delegate sub-agent events carry nodeSessionId (injected by DelegateTool's
      // routeWsSend - the "delegate-" prefix is backend protocol). Route them
      // to the bg-agent popup's ChatView.
      // Checked BEFORE flowStepInterceptor because both use nodeSessionId —
      // bg-agent IDs start with "delegate-"/"subtask-" so we can distinguish.
      if (msg.nodeSessionId && isBgAgentId(msg.nodeSessionId) && bgAgentStepInterceptor && bgAgentStepInterceptor(msg)) {
        const converted = convertAgentEvent(msg);
        if (converted) {
          const convList = handlers[converted.type];
          // Gating EXEMPT (regression fix): Delegate/SubTask sessions are
          // ephemeral - the backend only persists their ui.json at completion,
          // so getHistory returns empty while the sub-agent is still running.
          // Before hidden-view gating, live events rendered into the hidden
          // popup container and masked that gap; gating made the popup blank.
          // Render live (pre-gating behavior). Memory stays bounded via
          // cleanupBgAgentView's post-done TTL + the stepViews LRU cap.
          const convView = activeView;
          if (convList) for (const h of convList.slice()) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] bg-agent handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (for bg-agent indicator status, etc.)
        const origView = activeView;
        const list = handlers[msg.type];
        if (list) for (const h of list.slice()) {
          try { h(msg, origView); }
          catch (e) { console.error('[ws] bg-agent handler error for', msg.type, ':', e.message); }
        }
        setActiveView(savedView); // restore pre-message view (BUG 6)
        return;
      }

      // ── Team agent events: convert + route to the agent's popup ──
      // MailTool.activateAgent stamps Mail-activated team agent events with
      // nodeSessionId = "team-<sessionId>". Convert them to standard chat
      // events (agentTextDelta → textDelta, etc. - same as the flow/delegate
      // branches below) and route them to the popup ChatView. The popup is
      // registered under the BARE sessionId (openStepPopup keys team popups
      // with the unprefixed sid from /api/teams/mounted), so strip the
      // prefix before converting/looking up. Must NOT fall through to the
      // flow interceptor below - its ensureStepView would key on the
      // prefixed id and never match the popup.
      if (msg.nodeSessionId && msg.nodeSessionId.startsWith('team-')) {
        const bareSid = msg.nodeSessionId.replace(/^team-/, '');
        const teamView = findViewBySessionId(bareSid) || null;
        setActiveView(teamView);
        // Management panel (2026-08-22): the team branch routes converted
        // events itself, but the popup's status footer still needs the raw
        // lifecycle meta (running/thinking/tool/stuck-clear) — apply it here.
        if (teamMetaApplier) teamMetaApplier(msg);
        const converted = convertAgentEvent({ ...msg, nodeSessionId: bareSid });
        if (converted) {
          const convList = handlers[converted.type];
          const convView = streamDispatchView(teamView);
          if (convList) for (const h of convList.slice()) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] team handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (status tracking, delegate
        // indicator, stream timeouts) with the pre-routed view.
        const teamList = handlers[msg.type];
        if (teamList) for (const h of teamList.slice()) {
          try { h(msg, view); }
          catch (e) { console.error('[ws] team handler error for', msg.type, ':', e.message); }
        }
        setActiveView(savedView); // restore pre-message view (BUG 6)
        return;
      }

      // ── Flow agent popup: intercept events with nodeSessionId ────
      // Flow agent events carry nodeSessionId (injected by FlowAgentActivator).
      // Route them to the agent's hidden ChatView for the popup.
      // They must NOT render into the primary chat view.
      if (msg.nodeSessionId && flowStepInterceptor && flowStepInterceptor(msg)) {
        // interceptFlowStep already set activeView to the flow ChatView.
        // Convert agent* events to standard chat events so chat.js
        // rendering functions (textDelta, toolStart, toolEnd, done, etc.)
        // render into the popup's ChatView just like the main chat window.
        const converted = convertAgentEvent(msg);
        if (converted) {
          const convList = handlers[converted.type];
          // Hidden popup → view=null: accumulate state buffers only, no DOM.
          const convView = streamDispatchView(activeView);
          if (convList) for (const h of convList.slice()) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (for status tracking, etc.)
        // Interactive events keep the real view (render into hidden container).
        const origView = (msg.type === 'askUser' || msg.type === 'askPermission') ? activeView : streamDispatchView(activeView);
        const list = handlers[msg.type];
        if (list) for (const h of list.slice()) {
          try { h(msg, origView); }
          catch (e) { console.error('[ws] handler error for', msg.type, ':', e.message); }
        }
        setActiveView(savedView); // restore pre-message view (BUG 6)
        return;
      }

      // Dispatch to handlers
      const list = handlers[msg.type];
      if (list) for (const h of list.slice()) {
        try { h(msg, view); }
        catch (e) { console.error('[ws] handler error for', msg.type, ':', e.message); }
      }

    } catch (err) {
      console.error('[ws] message parse error:', err);
    }
  };
}

// ---------- LLM Log toggle sync ----------
onMessage('llmLogState', (msg) => {
  state.llmLogEnabled = msg.enabled;
  const toggle = document.getElementById('toggle-llm-log');
  if (toggle) toggle.classList.toggle('on', msg.enabled);
});

// ---------- Wake-up / network recovery ----------
// When the OS sleeps, the browser suspends timers and the WebSocket dies at the
// TCP level. On wake, detect this immediately and reconnect - don't wait for
// the exponential backoff timer (which may not fire for a while).
function checkConnection() {
  if (!state.ws || state.ws.readyState === WebSocket.CLOSED || state.ws.readyState === WebSocket.CLOSING) {
    // Connection is dead - reconnect immediately
    forceReconnect();
  } else if (state.ws.readyState === WebSocket.OPEN) {
    // Connection looks alive - probe with ping/pong. Mac lid-open can leave the TCP
    // connection in a half-open state where the browser hasn't fired onclose yet.
    // freezetimeout B2: 平 2s 单次 miss 即 force reconnect 改为连续 HB_MAX_MISSES
    // 次未回才判死（承压期单次 2s 太短，误伤健康 socket ⇒ 断连抖动 + 静默累积）。
    probeSocketAfterWake(HB_MAX_MISSES);
  }
}

/**
 * 数据面唤醒信号（clientconn item 2，2026-09-12 客户端长连接批）。
 *
 * 现状缺口（现场重取）：回前台 / 回网**只**走 `checkConnection()` —— 它探的是
 * 浏览器↔本机网关那条 WS 的死活，**完全不碰好友/消息数据**。后台期间丢掉的
 * `friend_event`（隧道断、WS 重连窗口内没人收帧）因此不会补回来，会话列表预览 /
 * 角标 / 开着的会话要等用户**手动关窗重开或切面板**才更新 —— 即「关窗才显示」
 * 的结构性残余。
 *
 * 收发分离：ws.js 是唤醒事件的天然属主（visibility / online 两个监听器都在本文件），
 * 它只负责**广播**；数据面动作（复用既有 REST 腿 + keyset `after=` 增量，禁尾窗全量）
 * 由 `messages.js` 消费。用 window CustomEvent 而非 import：messages.js 本来就 import
 * 本模块，反向 import 会成环（同 flowStepInterceptor 的注释口径）。
 */
function notifyWake(reason) {
  try { window.dispatchEvent(new CustomEvent('fm-wake', { detail: { reason } })); }
  catch (e) { console.error('[ws] fm-wake dispatch failed:', e.message); }
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') { checkConnection(); notifyWake('visibility'); }
});

window.addEventListener('online', () => {
  // Network came back - give it a moment then check
  setTimeout(() => { checkConnection(); notifyWake('online'); }, 500);
});
