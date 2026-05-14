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
  pinnedFolders: new Set(safeParse(localStorage.getItem('nebflow_pinned_folders'), [])),
  attentionSessions: new Set(),
  legacyMigrated: false,

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

  // Task list cache per session
  sessionTasks: {},

  // Agent panel
  agentsData: [],
  selectedAgent: null,
  configText: '',
  // Available tools (loaded from backend ToolRegistry via serverConfig)
  availableTools: [],
  // Agent-configurable tools + auto-tools (from agentList)
  agentAvailableTools: [],
  agentAutoTools: [],
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
  currentPolicy: 'ask',
  serverThinking: null,
  language: null,
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

  // IME
  composing: false,

  // /ask command
  currentAskBubble: null,
  askAnswerText: '',

  // Slash autocomplete
  slashSelectedIndex: 0,
  slashMatches: [],

  // Search navigation target: { sessionId, messageIndex } or null
  searchNavigateTarget: null,

  // Per-session background tasks: sessionId -> [{ taskId, description, status }]
  sessionBgTasks: {},

  // DOM refs (populated in main.js)
  dom: {},

  // Background tasks update helper
  updateBgTasksUI: null,

  // Per-session model info: sessionId -> { model, contextWindow, inputTokens }
  sessionModelInfo: safeParse(localStorage.getItem('nebflow_model_info'), {}),
  updateHeaderModelInfo: null,
  COMPACT_THRESHOLD: 0.75,
};
