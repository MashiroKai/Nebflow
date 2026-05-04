// Constants
export const LS_KEY = 'nebflow_v3';
export const LS_SESSIONS_KEY = 'nebflow_sessions';
export const LS_HISTORY_KEY = 'nebflow_input_history';
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
  unreadSessions: new Set(),
  legacyMigrated: false,

  // Chat streaming
  busySessionId: null,
  busyTimeoutId: null,
  currentAiBubble: null,
  aiText: '',

  // Multi-agent
  activeAgentId: null,
  agentBubbles: {},
  agentColors: {},
  agentColorIdx: 0,

  // Per-session streaming text buffer: sessionId -> accumulated text
  sessionTexts: {},

  // Per-session pending tool card: sessionId -> DOM row element
  sessionToolCards: {},

  // Task list cache per session
  sessionTasks: {},

  // Agent panel
  agentsData: [],
  configText: '',

  // Input
  pendingAttachments: [],
  thinkingMode: null,
  recognition: null,
  inputHistory: safeParse(localStorage.getItem('nebflow_input_history'), []),
  historyIndex: -1,
  historyDraft: '',
  pendingDeleteId: null,

  // Server config (sent on WS connect)
  streamTimeoutMs: 900000,
  serverVersion: '',
  currentPolicy: 'ask',
  serverThinking: null,

  // Send lock (prevents rapid double-send)
  isSending: false,

  // Scroll
  scrollSnapped: true,

  // IME
  composing: false,

  // Slash autocomplete
  slashSelectedIndex: 0,
  slashMatches: [],

  // DOM refs (populated in main.js)
  dom: {}
};
