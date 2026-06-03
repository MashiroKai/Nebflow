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
  legacyMigrated: false,

  // Bypass all permission requests (auto-approve mode)
  bypassAllPermission: false,

  // Chat streaming
  busySessionIds: new Set(),
  sessionBusyTimeouts: {},
  compactingSessionIds: new Set(),
  currentAiBubble: null,
  aiText: '',

  // Multi-agent
  activeAgentId: null,
  agentBubbles: {},
  agentColors: {},
  agentColorIdx: 0,

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

  // Input
  pendingAttachments: [],
  thinkingMode: null,
  recognition: null,
  inputHistory: safeParse(localStorage.getItem('nebflow_input_history'), []),
  historyIndex: -1,
  historyDraft: '',
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

  // Scroll
  scrollSnapped: true,

  // History pagination
  historyOffset: 0,
  historyTotal: 0,
  historyHasMore: false,
  historyLoading: false,
  // Set true by resetChatForActiveSession, consumed by historyPage handler to distinguish
  // initial load from scroll-up pagination. Prevents double chat.innerHTML='' from
  // duplicate getHistory responses when offset happens to be 0.
  pendingInitialLoad: false,

  // IME
  composing: false,

  // Skill list (from server)
  skills: [],

  // /ask command
  currentAskBubble: null,
  askAnswerText: '',
  askMode: false,

  // Skill mode
  skillMode: false,
  skillModeName: '',
  skillModeDesc: '',

  // Thinking bubble
  currentThinkingBubble: null,
  thinkingText: '',
  // Per-session thinking buffer: sessionId -> accumulated thinking text
  sessionThinkingBuffers: {},
  // Per-session last completed turn data (kept alive until historyPage confirms it).
  // Prevents lost messages on switch-back when backend hasn't persisted yet.
  pendingRestore: {},
  // Per-session flag: true when a turn is in progress (user sent message or server
  // explicitly set busy). Prevents stray thinkingDelta after done from creating bubbles.
  turnExpecting: {},

  // Slash autocomplete
  slashSelectedIndex: 0,
  slashMatches: [],

  // Search navigation target: { sessionId, messageIndex } or null


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

  // Feishu global config cache (survives DOM recreation)
  feishuGlobalConfig: null,

  // Persistent notifications (survive session switches)
  notifications: [],
};
