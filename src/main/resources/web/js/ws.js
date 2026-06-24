import state from './state.js';

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
      // ── Secondary session swap ──────────────────────────────────────
      // When a message is for the secondary session, temporarily swap
      // state.dom.chat + streaming variables so ALL existing handlers
      // render into #secondary-chat without any code duplication.
      const isSecMsg = state.secondarySessionId && msg.sessionId === state.secondarySessionId;
      let _saved = null;
      if (isSecMsg) {
        _saved = {
          chat: state.dom.chat,
          statusWrap: state.dom.statusWrap,
          statusText: state.dom.statusText,
          sendBtn: state.dom.sendBtn,
          stopBtn: state.dom.stopBtn,
          input: state.dom.input,
          lottieSpinnerEl: state.dom.lottieSpinnerEl,
          aiText: state.aiText,
          currentAiBubble: state.currentAiBubble,
          thinkingText: state.thinkingText,
          currentThinkingBubble: state.currentThinkingBubble,
          // Fields that were previously NOT swapped — leaking these caused
          // cross-window state corruption when both windows ran /ask, multi-agent
          // (delegate) turns, or relied on scroll-snap anchoring.
          currentAskBubble: state.currentAskBubble,
          askAnswerText: state.askAnswerText,
          askMode: state.askMode,
          agentBubbles: state.agentBubbles,
          activeAgentId: state.activeAgentId,
          activeSubAgents: state.activeSubAgents,
          scrollSnapped: state.scrollSnapped,
          // Header status indicators (session-scoped) — swap so updateHeaderModelInfo,
          // updateBgTasksUI, updateDelegateIndicator render into the secondary header.
          headerModelInfoEl: state.dom.headerModelInfoEl,
          bgIndicatorEl: state.dom.bgIndicatorEl,
          bgCountEl: state.dom.bgCountEl,
          bgDropdownEl: state.dom.bgDropdownEl,
          bgDropdownListEl: state.dom.bgDropdownListEl,
          delegateIndicatorEl: state.dom.delegateIndicatorEl,
          delegateDropdownEl: state.dom.delegateDropdownEl,
          delegateDropdownListEl: state.dom.delegateDropdownListEl,
        };
        state.dom.chat = document.getElementById('secondary-chat');
        state.dom.statusWrap = document.getElementById('secondary-status-wrap');
        state.dom.statusText = document.getElementById('secondary-status-text');
        state.dom.sendBtn = document.getElementById('secondary-send-btn');
        state.dom.stopBtn = document.getElementById('secondary-stop-btn');
        state.dom.input = document.getElementById('secondary-input');
        state.dom.lottieSpinnerEl = document.getElementById('secondary-spinner');
        state.dom.headerModelInfoEl = document.getElementById('secondary-header-model-info');
        state.dom.bgIndicatorEl = document.getElementById('secondary-bg-indicator');
        state.dom.bgCountEl = document.getElementById('secondary-bg-indicator')?.querySelector('.bg-count');
        state.dom.bgDropdownEl = document.getElementById('secondary-bg-dropdown');
        state.dom.bgDropdownListEl = document.getElementById('secondary-bg-dropdown')?.querySelector('.bg-dropdown-list');
        state.dom.delegateIndicatorEl = document.getElementById('secondary-delegate-indicator');
        state.dom.delegateDropdownEl = document.getElementById('secondary-delegate-dropdown');
        state.dom.delegateDropdownListEl = document.getElementById('secondary-delegate-dropdown')?.querySelector('.bg-dropdown-list');
        const ss = state._secStream || (state._secStream = {});
        state.aiText = ss.aiText || '';
        state.currentAiBubble = ss.currentAiBubble || null;
        state.thinkingText = ss.thinkingText || '';
        state.currentThinkingBubble = ss.currentThinkingBubble || null;
        state.currentAskBubble = ss.currentAskBubble || null;
        state.askAnswerText = ss.askAnswerText || '';
        state.askMode = ss.askMode || false;
        state.agentBubbles = ss.agentBubbles || {};
        state.activeAgentId = ss.activeAgentId || null;
        state.activeSubAgents = ss.activeSubAgents || {};
        // scrollSnapped: prefer the dedicated secondary flag, fall back to stream store.
        state.scrollSnapped = state._secScrollSnapped != null ? state._secScrollSnapped : (ss.scrollSnapped != null ? ss.scrollSnapped : true);
        state._secondaryActive = true;
      }

      const list = handlers[msg.type];
      if (list) for (const h of list) h(msg);

      // Restore primary state after handlers complete
      if (isSecMsg && _saved) {
        const ss = state._secStream || (state._secStream = {});
        ss.aiText = state.aiText;
        ss.currentAiBubble = state.currentAiBubble;
        ss.thinkingText = state.thinkingText;
        ss.currentThinkingBubble = state.currentThinkingBubble;
        ss.currentAskBubble = state.currentAskBubble;
        ss.askAnswerText = state.askAnswerText;
        ss.askMode = state.askMode;
        ss.agentBubbles = state.agentBubbles;
        ss.activeAgentId = state.activeAgentId;
        ss.activeSubAgents = state.activeSubAgents;
        // Persist the per-window scroll-snap back to the dedicated secondary flag
        // (the scroll handler in secondary-chat.js reads/writes _secScrollSnapped).
        state._secScrollSnapped = state.scrollSnapped;
        state.dom.chat = _saved.chat;
        state.dom.statusWrap = _saved.statusWrap;
        state.dom.statusText = _saved.statusText;
        state.dom.sendBtn = _saved.sendBtn;
        state.dom.stopBtn = _saved.stopBtn;
        state.dom.input = _saved.input;
        state.dom.lottieSpinnerEl = _saved.lottieSpinnerEl;
        state.aiText = _saved.aiText;
        state.currentAiBubble = _saved.currentAiBubble;
        state.thinkingText = _saved.thinkingText;
        state.currentThinkingBubble = _saved.currentThinkingBubble;
        state.currentAskBubble = _saved.currentAskBubble;
        state.askAnswerText = _saved.askAnswerText;
        state.askMode = _saved.askMode;
        state.agentBubbles = _saved.agentBubbles;
        state.activeAgentId = _saved.activeAgentId;
        state.activeSubAgents = _saved.activeSubAgents;
        state.scrollSnapped = _saved.scrollSnapped;
        state.dom.headerModelInfoEl = _saved.headerModelInfoEl;
        state.dom.bgIndicatorEl = _saved.bgIndicatorEl;
        state.dom.bgCountEl = _saved.bgCountEl;
        state.dom.bgDropdownEl = _saved.bgDropdownEl;
        state.dom.bgDropdownListEl = _saved.bgDropdownListEl;
        state.dom.delegateIndicatorEl = _saved.delegateIndicatorEl;
        state.dom.delegateDropdownEl = _saved.delegateDropdownEl;
        state.dom.delegateDropdownListEl = _saved.delegateDropdownListEl;
        state._secondaryActive = false;
      }
    } catch (err) {
      console.error('[ws] message parse error:', err);
    }
  };
}
