import state from './state.js';

// ---------- Handler registry ----------
const handlers = {};

export function onMessage(type, handler) {
  handlers[type] = handler;
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
  // Store token in cookie (HttpOnly not possible from JS, but Secure/SameSite are)
  if (token) {
    document.cookie = `nebflow_token=${encodeURIComponent(token)}; path=/; SameSite=Strict; max-age=86400`;
  }
  // Connect without token in URL — server reads from cookie
  const wsUrl = `${proto}//${location.host}/ws`;
  console.log('[ws] connecting to', wsUrl);
  try {
    state.ws = new WebSocket(wsUrl);
  } catch (e) {
    console.error('[ws] WebSocket constructor failed:', e);
    setTimeout(connect, 3000);
    return;
  }

  state.ws.onopen = () => {
    console.log('[ws] connected');
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
    console.log('[ws] disconnected');
    state.dom.connEl.classList.add('off');
    if (state.heartbeat) { clearInterval(state.heartbeat); state.heartbeat = null; }
    setTimeout(connect, 2000);
  };

  state.ws.onerror = (e) => {
    console.error('[ws] error:', e);
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
          !GLOBAL_MSG_TYPES.includes(msg.type) && !TERMINAL_MSG_TYPES.includes(msg.type) &&
          !STREAM_MSG_TYPES.includes(msg.type)) {
        return;
      }
      const handler = handlers[msg.type];
      if (msg.type === 'done' || msg.type === 'error') {
        console.log(`[ws] ${msg.type} received`, { sessionId: msg.sessionId, activeSessionId: state.activeSessionId, hasHandler: !!handler });
      }
      if (handler) handler(msg);
    } catch (err) {
      console.error('[ws] message parse error:', err);
    }
  };
}
