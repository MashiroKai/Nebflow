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

  // Per-session safety mode: "confirm-edits" | "auto-edits" | "auto-all"
  safetyModes: {},  // sessionId → mode string

  // Chat streaming (per-session status sets - view-level state lives on ChatView)
  busySessionIds: new Set(),
  sessionBusyTimeouts: {},
  compactingSessionIds: new Set(),
  // Freeze schedule: sessionId set whose agent is parked at a dispatch boundary
  // (work hours ended after a tool round). Sending a message to a frozen session
  // bypasses client-side queueing and wakes the agent (freeze-schedule spec §3.2).
  frozenSessions: new Set(),
  // serverConfig echo of the workSchedule node: { enabled, segments:[{start,end}] }
  workSchedule: null,
  // serverConfig echo of the stt node: { sttConfigured, endpoint?, model? } — the
  // apiKey is NEVER echoed (server-side only). null/absent = free browser path.
  stt: null,

  // Timestamp of the last textDelta/thinkingDelta received (ms).
  lastStreamActivity: 0,

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

  // Per-session pending tool card: sessionId -> DOM row element
  sessionToolCards: {},

  // Per-session pending tool metadata: sessionId -> { label } (persists across session switches)
  sessionPendingTools: {},
  // Per-session pending AI message segments: sessionId -> [{ type:'ai', text, thinking }]
  // Accumulated between tool call boundaries, flushed by toolStart/toolCallDetected
  // and consumed by the done handler to reconstruct correct per-bubble messages.
  sessionPendingAiMessages: {},

  // Task list cache per session
  sessionTasks: {},

  // Agent panel
  agentsData: [],
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
  mcpServers: [],

  // Batch selection (like VS Code Explorer)
  batchMode: false,
  selectedSessionIds: new Set(),
  lastSelectedSessionId: null,

  // Skill list (from server)
  skills: [],
  flows: [],

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
  sessionModelInfo: safeParse(localStorage.getItem(key('model_info')), {}),
  updateBypassToggle: null,
  updateSafetyToggle: null,
  COMPACT_THRESHOLD: 0.90,
  updateHeaderModelInfo: null,

  // Plan mode: agentId of the active plan agent (null when not in plan mode)
  planAgentId: null,
  // Plan mode: sessionId that triggered plan mode
  planSessionId: null,

  // Persistent notifications (survive session switches)
  notifications: [],
};
