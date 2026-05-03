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
    if (state.thinkingMode) {
      sendWs({type: 'setThinking', thinking: state.thinkingMode});
    }
    state.heartbeat = setInterval(() => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        sendWs({type: 'ping'});
      }
    }, 30000);
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
      const handler = handlers[msg.type];
      if (handler) handler(msg);
    } catch (err) {
      console.error('[ws] message parse error:', err);
    }
  };
}
