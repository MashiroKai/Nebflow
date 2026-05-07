// Constants
export const LS_KEY = 'nebflow_v3';
export const LS_SESSIONS_KEY = 'nebflow_sessions';
export const LS_HISTORY_KEY = 'nebflow_input_history';
export const LS_DRAFTS_KEY = 'nebflow_input_drafts';
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
  sessions: [],
  unreadSessions: new Set(safeParse(localStorage.getItem('nebflow_unread'), [])),
  markedUnreadSessions: new Set(safeParse(localStorage.getItem('nebflow_marked_unread'), [])),
  pinnedSessions: new Set(safeParse(localStorage.getItem('nebflow_pinned'), [])),
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

  // Sessions whose last askUser has been answered — used to avoid re-rendering interactive askUser on session switch
  answeredAskUsers: new Set(),

  // Task list cache per session
  sessionTasks: {},

  // Agent panel
  agentsData: [],
  configText: '',
  // Available tools (loaded from backend ToolRegistry)
  availableTools: [],

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

  // DOM refs (populated in main.js)
  dom: {}
};
