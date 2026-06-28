import state from './state.js';
import { findViewBySessionId, setActiveView, chatViews } from './chatView.js';

// ---------- Handler registry (supports multiple handlers per type) ----------
const handlers = {};

export function onMessage(type, handler) {
  if (!handlers[type]) handlers[type] = [];
  handlers[type].push(handler);
}

// ---------- Reconnection state ----------
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;
const BASE_RECONNECT_DELAY = 2000;
const MAX_RECONNECT_DELAY = 30000;

function reconnectDelay() {
  const delay = Math.min(BASE_RECONNECT_DELAY * Math.pow(2, reconnectAttempts), MAX_RECONNECT_DELAY);
  return delay * (0.8 + Math.random() * 0.4);
}

// ---------- Send ----------
export function sendWs(msg) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg));
  }
}

// ---------- Connect ----------
export function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const urlParams = new URLSearchParams(location.search);
  const token = urlParams.get('token') || '';
  if (token) {
    localStorage.setItem('nebflow_token', token);
  }
  const storedToken = localStorage.getItem('nebflow_token') || token;
  const wsUrl = `${proto}//${location.host}/ws${storedToken ? '?token=' + encodeURIComponent(storedToken) : ''}`;
  try {
    state.ws = new WebSocket(wsUrl);
  } catch (e) {
    console.error('[ws] WebSocket constructor failed:', e);
    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
      const delay = reconnectDelay();
      reconnectAttempts++;
      setTimeout(connect, delay);
    }
    return;
  }

  state.ws.onopen = () => {
    reconnectAttempts = 0;
    state.dom.connEl.classList.remove('off');
    if (state.thinkingMode?.enabled) {
      sendWs({type: 'setThinking', thinking: state.thinkingMode});
    }
    sendWs({type: 'getSkills'});
    sendWs({type: 'memoryStatus'});
    state.heartbeat = setInterval(() => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        sendWs({type: 'ping'});
      }
    }, 30000);
  };

  state.ws.onclose = () => {
    state.dom.connEl?.classList.add('off');
    if (state.heartbeat) { clearInterval(state.heartbeat); state.heartbeat = null; }
    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
      const delay = reconnectDelay();
      reconnectAttempts++;
      setTimeout(connect, delay);
    }
  };

  state.ws.onerror = () => {
    if (reconnectAttempts < 3) {
      console.warn('[ws] connection failed, reconnecting...');
    }
    state.dom.connEl?.classList.add('off');
  };

  state.ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);

      // ── Message filtering ────────────────────────────────────────────
      const GLOBAL_MSG_TYPES = [
        'sessionList', 'serverConfig', 'agentList', 'agentSessionList',
        'agentConfig', 'agentCreated', 'agentUpdated',
        'mcpServersUpdate', 'configData', 'configUpdated', 'modelOptions',
        'memoryData', 'memorySaved', 'memoryStatus',
        'cardDesignData', 'cardDesignSaved',
        'rulesData', 'rulesSaved', 'rulesDeleted', 'rulesStatus',
        'browseResult'
      ];
      const TERMINAL_MSG_TYPES = [
        'done', 'error', 'interrupted', 'maxTokens', 'sessionBusy',
        'compactStart', 'compactComplete', 'compactFailed',
        'backgroundTaskUpdate', 'taskListUpdate',
        'askUser', 'askPermission'
      ];
      const STREAM_MSG_TYPES = [
        'thinkingDelta', 'textDelta', 'textDone',
        'toolCallDetected', 'toolCallStart', 'toolCallChunk', 'toolStart', 'toolEnd',
        'roundComplete',
        'agentStart', 'agentTextDelta', 'agentToolCallDetected',
        'agentToolStart', 'agentToolEnd', 'agentEnd',
        'agentThinking', 'agentRetryStatus', 'agentDone'
      ];
      if (state.activeSessionId && msg.sessionId && msg.sessionId !== state.activeSessionId &&
          msg.sessionId !== state.secondarySessionId &&
          !GLOBAL_MSG_TYPES.includes(msg.type) && !TERMINAL_MSG_TYPES.includes(msg.type) &&
          !STREAM_MSG_TYPES.includes(msg.type)) {
        return;
      }

      // ── View routing ─────────────────────────────────────────────────
      // Find the ChatView that displays this session. Set it as activeView
      // so rendering functions target the correct window. No swap/restore —
      // the view object holds all its own state.
      let view = findViewBySessionId(msg.sessionId);
      // Messages without sessionId default to primary view (e.g. 'thinking'
      // events that use msg.sessionId || activeSessionId internally).
      if (!view && !msg.sessionId && !GLOBAL_MSG_TYPES.includes(msg.type)) {
        view = chatViews.primary || null;
      }
      setActiveView(view || null);

      // Dispatch to handlers
      const list = handlers[msg.type];
      if (list) for (const h of list) h(msg, view);

    } catch (err) {
      console.error('[ws] message parse error:', err);
    }
  };
}
