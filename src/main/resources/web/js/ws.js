import state from './state.js';
import { findViewBySessionId, setActiveView, chatViews } from './chatView.js';

// ---------- Handler registry (supports multiple handlers per type) ----------
const handlers = {};

export function onMessage(type, handler) {
  if (!handlers[type]) handlers[type] = [];
  handlers[type].push(handler);
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

// ---------- Reconnection state ----------
// No hard limit on attempts — this is a desktop app; the connection should
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
    scheduleReconnect();
    return;
  }

  state.ws.onopen = () => {
    reconnectAttempts = 0;
    state.dom.connEl.classList.remove('off');
    state.dom.connEl.classList.remove('reconnecting');
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
    // On reconnect (not first connect), notify callbacks so they can
    // re-fetch state that may have changed during the disconnect.
    if (hasConnectedBefore) {
      reconnectCallbacks.forEach(cb => { try { cb(); } catch (e) { console.error('[ws] reconnect callback error:', e); } });
    }
    hasConnectedBefore = true;
  };

  state.ws.onclose = () => {
    state.dom.connEl?.classList.add('off');
    state.dom.connEl?.classList.add('reconnecting');
    if (state.heartbeat) { clearInterval(state.heartbeat); state.heartbeat = null; }
    scheduleReconnect();
  };

  state.ws.onerror = () => {
    state.dom.connEl?.classList.add('off');
    state.dom.connEl?.classList.add('reconnecting');
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

// ---------- Wake-up / network recovery ----------
// When the OS sleeps, the browser suspends timers and the WebSocket dies at the
// TCP level. On wake, detect this immediately and reconnect — don't wait for
// the exponential backoff timer (which may not fire for a while).
function checkConnection() {
  if (!state.ws || state.ws.readyState === WebSocket.CLOSED || state.ws.readyState === WebSocket.CLOSING) {
    // Connection is dead — cancel any pending slow reconnect and reconnect now
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    reconnectAttempts = 0; // reset so we use the minimum delay
    connect();
  } else if (state.ws.readyState === WebSocket.OPEN) {
    // Connection looks alive — send a ping to verify it actually works
    sendWs({type: 'ping'});
  }
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') checkConnection();
});

window.addEventListener('online', () => {
  // Network came back — give it a moment then check
  setTimeout(checkConnection, 500);
});
