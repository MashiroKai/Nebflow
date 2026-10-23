// ── Exported protocol typedefs (P2-3 core contracts) ──────────────────────
// These are the cross-module data shapes other files should reference via
// import('./state.js').<Name> in JSDoc, instead of re-declaring them ad hoc.

// All storage keys are brand-namespaced via branding.js (rename-day
// migration lives there too, as a module-init side effect ordered first in
// main.js so it runs before the reads below).
import { key } from './branding.js';

/**
 * A chat session as returned by /api/sessions and the sessionList WS event.
 * @typedef {Object} Session
 * @property {string} id
 * @property {string} [title]
 * @property {string} [name]
 * @property {string} [agentName]
 * @property {string|null} [folderId]
 * @property {number} [createdAt] - epoch ms
 * @property {number} [updatedAt] - epoch ms
 * @property {boolean} [enabled]
 * @property {boolean} [hasUnread]
 */

/**
 * Background sub-agent row tracked in sessionBgAgents.
 * @typedef {Object} BgAgent
 * @property {string} name
 * @property {string} [task]
 * @property {string} [currentTool]
 * @property {boolean} [done]
 */

/**
 * Per-session model info (sessionModelInfo map values).
 * @typedef {Object} SessionModelInfo
 * @property {string} model
 * @property {number} [contextWindow]
 * @property {number} [inputTokens]
 * @property {number} [outputTokens]
 * @property {number} [compactThreshold]
 */

// Constants
export const LS_KEY = key('v3');
export const LS_SESSIONS_KEY = key('sessions');
export const LS_HISTORY_KEY = key('input_history');
export const LS_DRAFTS_KEY = key('input_drafts');
export const LS_MODEL_INFO_KEY = key('model_info');
export const AGENT_PALETTE = ['#6C8EBF', '#D4A574', '#82B366', '#B5739D', '#9678B6', '#D6B656'];

function safeParse(json, fallback) {
  try { const v = JSON.parse(json); return (v !== null && v !== undefined) ? v : fallback; } catch { return fallback; }
}

// Shared mutable state
export default {
  // WebSocket
  ws: null,
  heartbeat: null,
  connected: false,
  pendingPong: false,

  // Session
  activeSessionId: null,
  activeFolderId: null,
  // Autostart status from backend (autostartStatusResult): {enabled, supported, reason}
  autostartStatus: null,
  sessions: [],
  folders: [],
  expandedFolders: new Set(safeParse(localStorage.getItem(key('expanded_folders')), [])),
  unreadSessions: new Set(safeParse(localStorage.getItem(key('unread')), [])),
  markedUnreadSessions: new Set(safeParse(localStorage.getItem(key('marked_unread')), [])),
  pinnedSessions: new Set(safeParse(localStorage.getItem(key('pinned')), [])),
  foldersWithRules: new Set(),
  pinnedFolders: new Set(safeParse(localStorage.getItem(key('pinned_folders')), [])),
  attentionSessions: new Set(),
  /** Tracks sessions whose askPermission has been answered by the user.
   *  Prevents re-creating interactive permission prompts on session switch-back
   *  when the tool is still executing (the askPermission UiMessage is still the
   *  last history entry until toolEnd is recorded). */
  answeredPermissions: new Set(),
  legacyMigrated: false,

  // Permission mode: "confirm-edits" | "auto-edits" | "auto-all".
  // Application-level since permshield S1/F1 (2026-09-13) — the session
  // dimension no longer exists, so every entry holds the same global value.
  // Kept as a per-session map purely because the header shield resolves the
  // mode by the active session id (see main.js `applyGlobalSafetyMode`).
  safetyModes: {},  // sessionId → mode string (all entries = the global mode)

  // Chat streaming (per-session status sets - view-level state lives on ChatView)
  busySessionIds: new Set(),
  sessionBusyTimeouts: {},
  // freezetimeout B2 (2026-09-20, chain-n-36a3f13d 施工图 §4.2②③): 入站通道活性水位。
  // ws.js 每收到一帧 WS 消息就推进它 —— 这是「判定模型缺独立活性信号」缺口下**前端
  // 唯一可得的活性证据**（后端独立通道 = B1，作者裁决项，本批禁碰）。busy 看门在阶梯
  // 到点时用它区分「慢」与「死」：本档内有过入站帧 ⇒ 只呈现/放宽，绝不进错误态。
  lastWsInboundAt: 0,
  compactingSessionIds: new Set(),
  // Freeze schedule: sessionId set whose agent is parked at a dispatch boundary
  // (work hours ended after a tool round). Sending a message to a frozen session
  // bypasses client-side queueing and wakes the agent (freeze-schedule spec §3.2).
  frozenSessions: new Set(),
  // serverConfig echo of the workSchedule node: { enabled, segments:[{start,end}] }
  workSchedule: null,
  // serverConfig echo of the global freeze state node (现象2 契约 2026-08-30):
  // { enabled, frozen, skipped, nextChangeAt, segments } — authoritative for the
  // input-bar freeze state machine. skipped=true means the current freeze window
  // was voided by skipFreeze and must stay un-frozen even after a reload (the
  // in-memory skipFrozenUntil mirror is lost on refresh — this is the durable source).
  freezeState: null,
  // Error recovery (frozen-error-recovery plan §4): per-session state for the
  // amber "error family". sessionId -> { reason, retryCount, detail, resumeAt,
  // escalation? }. Distinct from frozenSessions (which is the park set for both
  // schedule and error — error entries ALSO set frozenSessions so the exempt
  // F5/watcher semantics hold). reason is one of the FreezeReason wire names.
  errorRecovery: {},
  // serverConfig echo of the stt node: { sttConfigured, endpoint?, model? } — the
  // apiKey is NEVER echoed (server-side only). null/absent = free browser path.
  stt: null,
  // Tool result TTL echo (#341): { enabled, ttlMinutes, keepRecent } — fetched
  // via getToolResultTtl, refreshed by toolResultTtl/toolResultTtlSaved
  // frames. null = not fetched yet (panel renders backend defaults). An old
  // backend echo may still carry minChars (removed by author ruling
  // 2026-09-01); the panel simply ignores unknown fields.
  toolResultTtl: null,

  // Timestamp of the last textDelta/thinkingDelta received (ms). GLOBAL — any
  // session's stream refreshes it, so it cannot answer "is THIS session's
  // stream alive". The per-session twin below is the one busy-lifecycle
  // decisions read.
  lastStreamActivity: 0,

  // busy-stuck-fix (2026-10-20): per-session timestamp of the last stream-frame
  // activity (textDelta/thinkingDelta/thinking/textDone/toolStart/toolHeartbeat).
  // Twin of the GLOBAL lastStreamActivity above at per-session granularity —
  // the messageDuplicate receipt guard and the turn-start confirmation net both
  // need "has THIS session streamed since T", which a global stamp cannot
  // answer (any other busy session's deltas would keep masking a phantom).
  sessionStreamActivity: {},

  // busy-stuck-fix (2026-10-20): per-session pending "turn-start confirmation"
  // timer ids (setTimeout handle). Armed by every optimistic busy arm
  // (send / drainMessageQueue / dispatchOfflineItem); cancelled by the engine's
  // authoritative new-turn signals (sessionBusy{busy:true}, any terminal frame,
  // or this session's own stream activity). Fires only when NO turn ever
  // started — clearing the phantom busy the optimistic arm created. Owners
  // (arm/cancel/fire bodies) live in main.js; these state slots are the
  // cross-module seam (input.js/chat.js call state.armTurnConfirm?.(sid) —
  // same pattern as updateSafetyToggle below).
  turnConfirmTimers: {},
  armTurnConfirm: null,
  cancelTurnConfirm: null,
  // queue-idle-flush (2026-10-23): cross-module seam for the queued-message
  // drain trigger (owner body scheduleQueueDrain lives in main.js). Stop
  // button (input.js) and the watchdog true-death close (chat.js) are both
  // REAL busy→idle transitions — after clearing busy they must trigger the
  // queue drain, or queued items strand with the UI showing idle.
  scheduleQueueDrain: null,
  // Confirmation window (ms). A real root-session turn is confirmed by the
  // engine's sessionBusy{busy:true} within RTT of acceptance (AgentIdle emits
  // it before the LLM call). Overridable for spec timing.
  turnConfirmMs: 20000,

  // ①-2 (2026-09-11, queue-direct-pass diagnosis §1.2 G3): per-session timestamp
  // of the last TERMINAL frame (done/error/interrupted/timeout/maxTokens/
  // compactFailed — everything clearBusyFor handles). While an entry is live, a
  // late streaming frame must not re-arm busy: the re-armed flag sends every
  // later Enter into the local queue with no further terminal frame to drain it
  // ⇒ the message never leaves the browser (症状①). Superseded only by facts
  // that prove a NEW turn began (backend sessionBusy{busy:true} or a locally
  // dispatched user message) — see markTerminal/canArmBusy/clearTerminal below.
  lastTerminalAt: {},

  // Multi-agent (global color assignment - per-view bubbles live on ChatView)
  agentColors: {},
  agentColorIdx: 0,
  activeDelegates: 0,

  // Per-session streaming text buffer: sessionId -> accumulated text
  sessionTexts: {},

  // Per-session ask streaming buffer: sessionId -> { question, answer, model }
  sessionAskBuffers: {},

  // Per-session turn start time: sessionId -> timestamp (ms)
  turnStartTimes: {},

  // resend-dedup 批（修复批 #569）：每会话最近一次**真人派发**的 clientMessageId。
  // 写点 = input.js 的真人派发点（send() 普通/ask/skill 三支 + drainMessageQueue）。
  // 读点 = chat.js resendLastInput（重试/重发腿）——同一条逻辑消息的任何重发都
  // 复用**同一个** id：服务端按 id 去重（recentMessageIds 命中 ⇒ 丢弃 + 回执），
  // 已送达的重发因此天然无害；真未送达时服务端没见过该 id ⇒ 正常受理。断线重连
  // 场景（WS 断开任意时长后重连）不依赖「断开了多久」，只依赖 id。
  lastUserSendId: {},

  // Per-session pending tool card: sessionId -> DOM row element
  sessionToolCards: {},

  // Per-session pending tool metadata: sessionId -> { label } (persists across session switches)
  sessionPendingTools: {},
  // Per-session pending AI message segments: sessionId -> [{ type:'ai', text, thinking }]
  // Accumulated between tool call boundaries, flushed by toolStart/toolCallDetected
  // and consumed by the done handler to reconstruct correct per-bubble messages.
  sessionPendingAiMessages: {},

  // sessionTasks / teamTasks 已随旧任务区退役移除（2026-09-05 10:54 裁定：
  // 任务面板 = 纯 Flow Map 节点视图，节点缓存在 taskList.js nodeCache 自包含）。

  // Agent panel
  agentsData: [],
  // Root agent DISPLAY name, set the moment onboarding writes one (optimistic
  // paint before agentList re-pushes). `null` = read it off agentList instead.
  // 🔴 Display only: the mechanism key stays "Nebula" (task book §2 C).
  rootDisplayName: null,
  // Root agent NAMED-verdict from the agent.json probe (rootName.js
  // initRootNameProbe, askuser-bubblename batch): true = a non-blank
  // `displayName` key exists in agents/Nebula/agent.json; false = unnamed or
  // probe failed/absent; null = probe not settled yet. Judgement face only —
  // the agentsData `displayName` row is fallback-poisoned (gateway folds the
  // unset case to the mechanism name) and must never feed the named-judgement.
  rootAgentFileNamed: null,
  // The probe's authoritative display name (same file, same read the server's
  // displayNameForRoot does). Display-chain leg for the boot race where the
  // header renders before agentList arrives; null = unnamed / probe unsettled.
  rootAgentFileName: null,
  selectedAgent: null,
  configText: '',
  parsedConfig: null,        // structured config parsed from JSON
  configDirty: false,        // true if local edits differ from server
  settingsShowJson: false,   // toggle advanced JSON editor
  currentModel: null,        // runtime model (WS modelChanged; null = use default)
  // Available tools (loaded from backend ToolRegistry via serverConfig)
  availableTools: [],
  // Agent-configurable tools (from agentList)
  agentAvailableTools: [],
  // Per-agent unread count: { agentName: count }
  agentUnreadCounts: {},
  // sessionId -> agentName mapping (across all agents)
  sessionAgentMap: {},

  // Per-session input drafts: sessionId -> { text, attachments }
  sessionInputDrafts: safeParse(localStorage.getItem(key('input_drafts')), {}),

  // Input (view-level state lives on ChatView; only global input state here)
  thinkingMode: null,
  inputHistory: safeParse(localStorage.getItem(key('input_history')), []),
  pendingDeleteId: null,


  // Server config (sent on WS connect)
  streamTimeoutMs: 600000,
  serverVersion: '',

  serverThinking: null,

  // App update state（updateAvailable/latestVersion，原 updateCheck.js 消费）已随
  // auth-trim 批（2026-10-20 作者令）删除：设置页更新功能退役。

  // Batch selection (like VS Code Explorer)
  batchMode: false,
  selectedSessionIds: new Set(),
  lastSelectedSessionId: null,

  // Skill list (from server)
  skills: [],

  // Per-session thinking buffer: sessionId -> accumulated thinking text
  sessionThinkingBuffers: {},
  // Per-session last completed turn data (kept alive until historyPage confirms it).
  pendingRestore: {},
  // Per-session flag: true when a turn is in progress
  turnExpecting: {},

  // Per-session message queue: sessionId -> [{ id, text, attachments, row }]
  // Messages typed while LLM is busy; sent on 'done' (normal) or via 立即 (immediate)
  messageQueue: {},

  // Per-session background tasks: sessionId -> [{ taskId, description, status }]
  sessionBgTasks: {},

  // Per-session background sub-agents: sessionId -> { [agentId]: { name, task, currentTool, done } }
  // Global (not per-view) so agentDone events are processed even when the
  // parent session isn't currently displayed, preventing stale indicators.
  sessionBgAgents: {},

  // Per-agent aggregate state: agentName -> 'working' | 'waiting' | 'compressing' | 'complete' | 'idle'
  agentStates: {},
  // Debounce timer for complete state
  agentStateTimers: {},

  // DOM refs (populated in main.js)
  dom: {},

  // Active ChatView accessor - set once by chatView.js at module init. Lets
  // utils.js / cardRegistry.js read the active view WITHOUT a static import
  // of chatView.js (P2-4 cycle cut: chatView <-> cardRegistry <-> utils).
  /** @type {null | (() => any)} */
  getActiveView: null,

  // Background tasks update helper
  updateBgTasksUI: null,

  // Per-session model info: sessionId -> { model, contextWindow, inputTokens }
  // (pipeline kept after the 2026-10-23 usage-ring display removal — popup
  // model badges still consume `.model`.)
  sessionModelInfo: safeParse(localStorage.getItem(key('model_info')), {}),
  updateBypassToggle: null,
  updateSafetyToggle: null,

  // Persistent notifications (survive session switches)
  notifications: [],
};
