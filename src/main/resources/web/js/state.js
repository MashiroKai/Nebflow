// Constants
export const LS_KEY = 'nebflow_v3';
export const LS_SESSIONS_KEY = 'nebflow_sessions';
export const LS_HISTORY_KEY = 'nebflow_input_history';
export const LS_DRAFTS_KEY = 'nebflow_input_drafts';
export const LS_MODEL_INFO_KEY = 'nebflow_model_info';
export const MAX_FILE_SIZE = 500 * 1024;
export const AGENT_PALETTE = ['#6C8EBF', '#D4A574', '#82B366', '#B5739D', '#9678B6', '#D6B656'];

function safeParse(json, fallback) {
  try { const v = JSON.parse(json); return (v !== null && v !== undefined) ? v : fallback; } catch { return fallback; }
}

// Shared mutable state
export default {
  // WebSocket
  ws: null,
  heartbeat: null,

  // Session
  activeSessionId: null,
  secondarySessionId: null,
  activeFolderId: null,
  sessions: [],
  folders: [],
  expandedFolders: new Set(safeParse(localStorage.getItem('nebflow_expanded_folders'), [])),
  unreadSessions: new Set(safeParse(localStorage.getItem('nebflow_unread'), [])),
  markedUnreadSessions: new Set(safeParse(localStorage.getItem('nebflow_marked_unread'), [])),
  pinnedSessions: new Set(safeParse(localStorage.getItem('nebflow_pinned'), [])),
  foldersWithRules: new Set(),
  pinnedFolders: new Set(safeParse(localStorage.getItem('nebflow_pinned_folders'), [])),
  attentionSessions: new Set(),
  /** Tracks sessions whose askPermission has been answered by the user.
   *  Prevents re-creating interactive permission prompts on session switch-back
   *  when the tool is still executing (the askPermission UiMessage is still the
   *  last history entry until toolEnd is recorded). */
  answeredPermissions: new Set(),
  legacyMigrated: false,

  // Bypass all permission requests (auto-approve mode)
  bypassAllPermission: false,

  // Chat streaming (per-session status sets — view-level state lives on ChatView)
  busySessionIds: new Set(),
  sessionBusyTimeouts: {},
  compactingSessionIds: new Set(),

  // Timestamp of the last textDelta/thinkingDelta received (ms).
  lastStreamActivity: 0,

  // Multi-agent (global color assignment — per-view bubbles live on ChatView)
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
  // Available tools (loaded from backend ToolRegistry via serverConfig)
  availableTools: [],
  // Agent-configurable tools (from agentList)
  agentAvailableTools: [],
  // Per-agent unread count: { agentName: count }
  agentUnreadCounts: {},
  // sessionId -> agentName mapping (across all agents)
  sessionAgentMap: {},

  // Per-session input drafts: sessionId -> { text, attachments }
  sessionInputDrafts: safeParse(localStorage.getItem('nebflow_input_drafts'), {}),

  // Input (view-level state lives on ChatView; only global input state here)
  thinkingMode: null,
  inputHistory: safeParse(localStorage.getItem('nebflow_input_history'), []),
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

  // Send lock (prevents rapid double-send)
  isSending: false,

  // Skill list (from server)
  skills: [],

  // Per-session thinking buffer: sessionId -> accumulated thinking text
  sessionThinkingBuffers: {},
  // Per-session last completed turn data (kept alive until historyPage confirms it).
  pendingRestore: {},
  // Per-session flag: true when a turn is in progress
  turnExpecting: {},

  // Per-session background tasks: sessionId -> [{ taskId, description, status }]
  sessionBgTasks: {},

  // Per-agent aggregate state: agentName -> 'working' | 'waiting' | 'compressing' | 'complete' | 'idle'
  agentStates: {},
  // Debounce timer for complete state
  agentStateTimers: {},

  // DOM refs (populated in main.js)
  dom: {},

  // Background tasks update helper
  updateBgTasksUI: null,

  // Per-session model info: sessionId -> { model, contextWindow, inputTokens }
  sessionModelInfo: safeParse(localStorage.getItem('nebflow_model_info'), {}),
  updateHeaderModelInfo: null,
  COMPACT_THRESHOLD: 0.90,

  // Card design prompt
  cardDesignPrompt: '',

  // Persistent notifications (survive session switches)
  notifications: [],
};
