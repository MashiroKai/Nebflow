import state from './state.js';
import { findViewBySessionId } from './chatView.js';

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
  // Exponential backoff: 2s, 4s, 8s, 16s, 30s, 30s, ...
  const delay = Math.min(BASE_RECONNECT_DELAY * Math.pow(2, reconnectAttempts), MAX_RECONNECT_DELAY);
  // Add jitter (±20%) to avoid thundering herd
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
  // Store token in localStorage (port-scoped, fixes multi-instance cookie conflict)
  if (token) {
    localStorage.setItem('nebflow_token', token);
  }
  // Connect with token in URL (server reads from query param)
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
    reconnectAttempts = 0; // Reset backoff on successful connection
    state.dom.connEl.classList.remove('off');
    if (state.thinkingMode?.enabled) {
      sendWs({type: 'setThinking', thinking: state.thinkingMode});
    }
    // Request skill list
    sendWs({type: 'getSkills'});
    state.heartbeat = setInterval(() => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        sendWs({type: 'ping'});
      }
    }, 30000);
    // Note: previously we auto-interrupted stale busy sessions here, but that caused
    // false interrupts when the WS briefly disconnected during normal processing.
    // The frontend's safety timeout (sessionBusyTimeouts) will clear stale busy state
    // automatically. Users can also manually interrupt via the stop button.
  };

  state.ws.onclose = () => {
    state.dom.connEl.classList.add('off');
    if (state.heartbeat) { clearInterval(state.heartbeat); state.heartbeat = null; }
    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
      const delay = reconnectDelay();
      reconnectAttempts++;
      setTimeout(connect, delay);
    }
  };

  state.ws.onerror = () => {
    // Silence expected errors during reconnection — onclose handles the retry logic.
    // Only log on the first few attempts to avoid console spam.
    if (reconnectAttempts < 3) {
      console.warn('[ws] connection failed, reconnecting...');
    }
    state.dom.connEl.classList.add('off');
  };

  state.ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);
      // Filter out messages belonging to other sessions (defense against broadcast leakage)
      const GLOBAL_MSG_TYPES = [
        'sessionList', 'serverConfig', 'agentList', 'agentSessionList',
        'agentConfig', 'agentCreated', 'agentUpdated',
        'mcpServersUpdate', 'configData', 'configUpdated', 'modelOptions',
        'memoryData', 'memorySaved', 'memoryStatus',
        'cardDesignData', 'cardDesignSaved',
        'rulesData', 'rulesSaved', 'rulesDeleted', 'rulesStatus',
        'browseResult'
      ];
      // Terminal state events update per-session busy/attention status — must always be processed
      // so the sidebar accurately reflects session state even when the user is viewing another session.
      const TERMINAL_MSG_TYPES = [
        'done', 'error', 'interrupted', 'maxTokens', 'sessionBusy',
        'compactStart', 'compactComplete', 'compactFailed',
        'backgroundTaskUpdate', 'taskListUpdate',
        'askUser', 'askPermission'
      ];
      // NOTE: streaming events must reach handlers for ALL sessions so that buffer accumulation
      // (sessionThinkingBuffers / sessionTexts) stays complete. DOM updates are gated by isActive().
      const STREAM_MSG_TYPES = [
        'thinkingDelta', 'textDelta', 'textDone',
        'toolCallDetected', 'toolCallStart', 'toolCallChunk', 'toolStart', 'toolEnd',
        'roundComplete'
      ];
      if (state.activeSessionId && msg.sessionId && msg.sessionId !== state.activeSessionId &&
          msg.sessionId !== state.secondarySessionId &&
          !GLOBAL_MSG_TYPES.includes(msg.type) && !TERMINAL_MSG_TYPES.includes(msg.type) &&
          !STREAM_MSG_TYPES.includes(msg.type)) {
        return;
      }
      // ── ChatView routing ─────────────────────────────────────────────
      // For the secondary view: temporarily push its stream state into the
      // global state + point state.dom at its DOM, so legacy handlers render
      // into the right window. After handlers finish, pull changes back and
      // restore the primary view's globals.
      //
      // The primary view needs NO push/pull — its stream state IS the global
      // state (they share the same object references). This avoids 11 field
      // copies on every primary-window message (including high-frequency
      // textDelta/thinkingDelta).
      const view = findViewBySessionId(msg.sessionId);
      let _savedDom = null;
      if (view && view.id !== 'primary') {
        _savedDom = { ...state.dom };
        view.pushToGlobal();
        Object.assign(state.dom, view.dom);
        state._secondaryActive = true;
      }

      const list = handlers[msg.type];
      if (list) for (const h of list) h(msg);

      if (view && view.id !== 'primary') {
        view.pullFromGlobal();
        Object.assign(state.dom, _savedDom);
        state._secondaryActive = false;
      }
    } catch (err) {
      console.error('[ws] message parse error:', err);
    }
  };
}
