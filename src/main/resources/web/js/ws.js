import state from './state.js';
import { findViewBySessionId, setActiveView, activeView, chatViews } from './chatView.js';
import { isBgAgentId } from './utils.js';

// ── Flow-step interceptor (registered by flowAgentPopup.js) ─────────────
// ws.js must NOT import flowAgentPopup.js directly: that creates a circular
// dependency (flowAgentPopup.js imports onMessage/sendWs from ws.js) which
// throws a TDZ ReferenceError when flowAgentPopup.js calls onMessage() at
// module top level before ws.js has finished initializing `handlers`.
// flowAgentPopup.js registers its interceptor here instead.
let flowStepInterceptor = null;
export function setFlowStepInterceptor(fn) { flowStepInterceptor = fn; }

// ── Background-agent step interceptor (registered by bgAgentPopup.js) ────
// Same pattern as flowStepInterceptor. Checked FIRST so background sub-agent
// events (nodeSessionId starts with "delegate-"/"subtask-" — backend protocol) don't get
// swallowed by the flowStepInterceptor which claims all nodeSessionId events.
let bgAgentStepInterceptor = null;
export function setBgAgentStepInterceptor(fn) { bgAgentStepInterceptor = fn; }

// ── Hidden-view render gating ─────────────────────────────────────────────
// Popup ChatViews (flow / bg-agent / team) have visible === false while their
// popup is closed. Streaming events for hidden views are dispatched with
// view=null so chat.js handlers skip DOM rendering entirely — the global
// per-session buffers in state.js (sessionTexts/sessionThinkingBuffers/...)
// still accumulate, so nothing is lost; the popup re-renders from backend
// history when opened (dirtyWhileHidden forces the refresh).
// Interactive events (askUser/askPermission) are exempt: they keep the real
// view and render into the hidden container immediately — low frequency,
// negligible cost, and the prompt must exist when the popup opens.
function streamDispatchView(view) {
  if (view && view.visible === false) {
    view.dirtyWhileHidden = true;
    return null;
  }
  return view;
}

/**
 * Convert agent* events (agentTextDelta, agentToolStart, etc.) to standard
 * chat events (textDelta, toolStart, etc.) so the same rendering pipeline
 * in chat.js handles both the main chat and flow agent popups.
 * The converted event has sessionId = nodeSessionId so it routes correctly.
 */
function convertAgentEvent(msg) {
  const sid = msg.nodeSessionId;
  if (!sid) return null;
  switch (msg.type) {
    case 'agentStart':
      return { type: 'agentStart', sessionId: sid, agentId: msg.agentId || msg.name, name: msg.name };
    case 'agentTextDelta':
      return { type: 'textDelta', sessionId: sid, delta: msg.delta || '' };
    case 'agentThinking':
      return { type: 'thinkingDelta', sessionId: sid, delta: '' };
    case 'agentToolCallDetected':
      return { type: 'toolCallDetected', sessionId: sid, name: msg.name || '' };
    case 'agentToolStart':
      return { type: 'toolStart', sessionId: sid, label: msg.label || '' };
    case 'agentToolEnd':
      return { type: 'toolEnd', sessionId: sid, label: msg.label || '', summary: msg.summary || '', content: msg.content || '', isError: msg.isError || false, input: msg.input || null };
    case 'agentDone':
      return { type: 'done', sessionId: sid, model: msg.model, contextWindow: msg.contextWindow, inputTokens: msg.inputTokens, compactThreshold: msg.compactThreshold };
    case 'usageUpdate':
      return { type: 'usageUpdate', sessionId: sid, inputTokens: msg.inputTokens, contextWindow: msg.contextWindow, compactThreshold: msg.compactThreshold };
    case 'agentEnd':
      return null; // no standard equivalent
    default:
      return null;
  }
}

/** Reflect the connection state onto the send button (doubles as the connection
 *  indicator). Defined here (not imported from chat.js) to avoid a circular
 *  dependency: chat.js imports sendWs from ws.js. */
function syncSendButtonConnState() {
  const btn = activeView?.dom?.sendBtn || document.getElementById('send-btn');
  if (btn) btn.classList.toggle('disconnected', !state.connected);
}

// ---------- Handler registry (supports multiple handlers per type) ----------
const handlers = {};

// ── Message-type filter sets (module-level for O(1) lookup) ─────────────
// Hoisted out of onmessage so we don't allocate three ~20-entry arrays and run
// three O(n) Array.includes() calls on every single inbound WS frame. During
// agent streaming (text/tool deltas can fire dozens of times per second) this
// per-message allocation + linear scan was measurable main-thread overhead
// and contributed to CSS-spinner jank (the compositor can't paint frames while
// the main thread is busy).
const GLOBAL_MSG_TYPES = new Set([
  'sessionList', 'serverConfig', 'agentList', 'agentSessionList',
  'agentSystemPrompt', 'agentSystemPromptSaved',
  'mcpServersUpdate', 'configData', 'configUpdated', 'modelOptions',
  'memoryData', 'memorySaved', 'memoryStatus', 'memoryChanged',
  'rulesData', 'rulesSaved', 'rulesDeleted', 'rulesStatus',
  'browseResult',
  'updateCheckResult', 'updateStarted', 'updateCompleted',
  'remoteUpdateResult', 'peerListChanged',
  'activeBgTasks', 'activeAgents',
  'dropbox-message', 'dropbox-file-response', 'dropbox-file-complete', 'dropbox-history', 'dropboxError'
]);
const TERMINAL_MSG_TYPES = new Set([
  'done', 'error', 'interrupted', 'maxTokens', 'sessionBusy',
  'compactStart', 'compactComplete', 'compactFailed',
  'backgroundTaskUpdate', 'taskListUpdate',
  'askUser', 'askPermission',
  'historyPage' // flow agent popups receive historyPage with their own sessionId
]);
const STREAM_MSG_TYPES = new Set([
  'thinkingDelta', 'textDelta', 'textDone',
  'toolCallDetected', 'toolCallStart', 'toolCallChunk', 'toolStart', 'toolEnd',
  'toolArgDelta',
  'roundComplete',
  'agentStart', 'agentTextDelta', 'agentToolCallDetected',
  'agentToolStart', 'agentToolEnd', 'agentEnd',
  'agentThinking', 'agentRetryStatus', 'agentDone',
  'treeBranchMounted', 'treeBranchUnmounted', 'treeBranchUpdated',
  'flowMail', 'flowStarted', 'flowProgress', 'flowCompleted', 'teamList'
]);

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

/** Force immediate reconnect, skipping exponential backoff. */
export function forceReconnect() {
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  reconnectAttempts = 0;
  // Close the old connection cleanly before opening a new one — prevents orphan sockets.
  if (state.ws) {
    try { state.ws.onclose = null; state.ws.close(); } catch (_) {}
    state.ws = null;
  }
  connect();
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
  // Set auth cookie so the WebSocket connection authenticates via cookie
  // instead of exposing the token in the URL (history/logs/Referer leakage).
  if (storedToken) {
    document.cookie = `nebflow_token=${encodeURIComponent(storedToken)}; path=/; SameSite=Strict`;
  }
  // Connect without token in URL — relies on cookie auth.
  const wsUrl = `${proto}//${location.host}/ws`;
  try {
    state.ws = new WebSocket(wsUrl);
  } catch (e) {
    console.error('[ws] WebSocket constructor failed:', e);
    scheduleReconnect();
    return;
  }

  state.ws.onopen = () => {
    reconnectAttempts = 0;
    state.connected = true;
    syncSendButtonConnState();
    if (state.thinkingMode?.enabled) {
      sendWs({type: 'setThinking', thinking: state.thinkingMode});
    }
    sendWs({type: 'setVoiceMuted', muted: localStorage.getItem('voiceMuted') === 'true'});
    sendWs({type: 'getSkills'});
    sendWs({type: 'getTeams'});
    sendWs({type: 'memoryStatus'});
    sendWs({type: 'getLlmLog'});
    sendWs({type: 'getConfig'});
    sendWs({type: 'getModelOptions', sessionId: state.activeSessionId});
    state.heartbeat = setInterval(() => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.pendingPong = true;
        sendWs({type: 'ping'});
        setTimeout(() => {
          if (state.pendingPong && state.ws?.readyState === WebSocket.OPEN) {
            console.log('[ws] heartbeat: no pong after 5s, closing dead connection');
            state.ws.close();
          }
        }, 5000);
      }
    }, 30000);
    // On (re)connect, notify callbacks so they can fetch state that
    // requires an open WS (e.g. workspace items, scheduled tasks).
    reconnectCallbacks.forEach(cb => { try { cb(); } catch (e) { console.error('[ws] connect callback error:', e); } });
    hasConnectedBefore = true;
  };

  state.ws.onclose = () => {
    state.connected = false;
    syncSendButtonConnState();
    if (state.heartbeat) { clearInterval(state.heartbeat); state.heartbeat = null; }
    scheduleReconnect();
  };

  state.ws.onerror = () => {
    state.connected = false;
    syncSendButtonConnState();
  };

  state.ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);

      // Pong reply — clear pending flag (used by heartbeat + wake detection)
      if (msg.type === 'pong') { state.pendingPong = false; return; }

      // ── BUG 6 fix: save/restore activeView ────────────────────────────
      // activeView is a module-global (chatView.js). The bg-agent/flow
      // interceptors below call setActiveView() to render into popup ChatViews,
      // which would otherwise leak into subsequent rendering within the same
      // event-loop tick. Snapshot here and restore after each interceptor
      // branch so popup rendering never disturbs the user's current view.
      const savedView = activeView;

      // ── Message filtering ────────────────────────────────────────────
      // GLOBAL/TERMINAL/STREAM sets are module-level (see top of file) for O(1)
      // lookup and to avoid per-message allocation.
      if (state.activeSessionId && msg.sessionId && msg.sessionId !== state.activeSessionId &&
          !GLOBAL_MSG_TYPES.has(msg.type) && !TERMINAL_MSG_TYPES.has(msg.type) &&
          !STREAM_MSG_TYPES.has(msg.type)) {
        return;
      }

      // ── View routing ─────────────────────────────────────────────────
      // Find the ChatView that displays this session. Set it as activeView
      // so rendering functions target the correct window. No swap/restore —
      // the view object holds all its own state.
      let view = findViewBySessionId(msg.sessionId);
      // Messages without sessionId default to primary view (e.g. 'thinking'
      // events that use msg.sessionId || activeSessionId internally).
      if (!view && !msg.sessionId && !GLOBAL_MSG_TYPES.has(msg.type)) {
        view = chatViews.primary || null;
      }
      setActiveView(view || null);

      // ── Background-agent popup: intercept events with nodeSessionId ─────
      // Delegate sub-agent events carry nodeSessionId (injected by DelegateTool's
      // routeWsSend — the "delegate-" prefix is backend protocol). Route them
      // to the bg-agent popup's ChatView.
      // Checked BEFORE flowStepInterceptor because both use nodeSessionId —
      // bg-agent IDs start with "delegate-"/"subtask-" so we can distinguish.
      if (msg.nodeSessionId && isBgAgentId(msg.nodeSessionId) && bgAgentStepInterceptor && bgAgentStepInterceptor(msg)) {
        const converted = convertAgentEvent(msg);
        if (converted) {
          const convList = handlers[converted.type];
          // Hidden popup → view=null: accumulate state buffers only, no DOM.
          const convView = streamDispatchView(activeView);
          if (convList) for (const h of convList) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] bg-agent handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (for bg-agent indicator status, etc.)
        // Interactive events keep the real view (render into hidden container).
        const origView = (msg.type === 'askUser' || msg.type === 'askPermission') ? activeView : streamDispatchView(activeView);
        const list = handlers[msg.type];
        if (list) for (const h of list) {
          try { h(msg, origView); }
          catch (e) { console.error('[ws] bg-agent handler error for', msg.type, ':', e.message); }
        }
        setActiveView(savedView); // restore pre-message view (BUG 6)
        return;
      }

      // ── Team agent events: convert + route to the agent's popup ──
      // MailTool.activateAgent stamps Mail-activated team agent events with
      // nodeSessionId = "team-<sessionId>". Convert them to standard chat
      // events (agentTextDelta → textDelta, etc. — same as the flow/delegate
      // branches below) and route them to the popup ChatView. The popup is
      // registered under the BARE sessionId (openStepPopup keys team popups
      // with the unprefixed sid from /api/teams/mounted), so strip the
      // prefix before converting/looking up. Must NOT fall through to the
      // flow interceptor below — its ensureStepView would key on the
      // prefixed id and never match the popup.
      if (msg.nodeSessionId && msg.nodeSessionId.startsWith('team-')) {
        const bareSid = msg.nodeSessionId.replace(/^team-/, '');
        const teamView = findViewBySessionId(bareSid) || null;
        setActiveView(teamView);
        const converted = convertAgentEvent({ ...msg, nodeSessionId: bareSid });
        if (converted) {
          const convList = handlers[converted.type];
          const convView = streamDispatchView(teamView);
          if (convList) for (const h of convList) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] team handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (status tracking, delegate
        // indicator, stream timeouts) with the pre-routed view.
        const teamList = handlers[msg.type];
        if (teamList) for (const h of teamList) {
          try { h(msg, view); }
          catch (e) { console.error('[ws] team handler error for', msg.type, ':', e.message); }
        }
        setActiveView(savedView); // restore pre-message view (BUG 6)
        return;
      }

      // ── Flow agent popup: intercept events with nodeSessionId ────
      // Flow agent events carry nodeSessionId (injected by FlowAgentActivator).
      // Route them to the agent's hidden ChatView for the popup.
      // They must NOT render into the primary chat view.
      if (msg.nodeSessionId && flowStepInterceptor && flowStepInterceptor(msg)) {
        // interceptFlowStep already set activeView to the flow ChatView.
        // Convert agent* events to standard chat events so chat.js
        // rendering functions (textDelta, toolStart, toolEnd, done, etc.)
        // render into the popup's ChatView just like the main chat window.
        const converted = convertAgentEvent(msg);
        if (converted) {
          const convList = handlers[converted.type];
          // Hidden popup → view=null: accumulate state buffers only, no DOM.
          const convView = streamDispatchView(activeView);
          if (convList) for (const h of convList) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (for status tracking, etc.)
        // Interactive events keep the real view (render into hidden container).
        const origView = (msg.type === 'askUser' || msg.type === 'askPermission') ? activeView : streamDispatchView(activeView);
        const list = handlers[msg.type];
        if (list) for (const h of list) {
          try { h(msg, origView); }
          catch (e) { console.error('[ws] handler error for', msg.type, ':', e.message); }
        }
        setActiveView(savedView); // restore pre-message view (BUG 6)
        return;
      }

      // ── Plan mode: intercept only agentTextDelta ───────────────────
      // We accumulate plan text for the card, but let all other plan agent
      // events (agentStart, agentToolStart, agentDone, etc.) flow through
      // normal dispatch so the plan agent shows as a sub-agent with its
      // bg-agent indicator and tool activity.
      if (msg.agentId && state.planAgentId === msg.agentId && msg.type === 'agentTextDelta') {
        const planList = handlers['_planAgent'];
        if (planList) for (const h of planList) {
          try { h(msg); }
          catch (e) { console.error('[ws] plan handler error:', e.message); }
        }
        return;
      }

      // Dispatch to handlers
      const list = handlers[msg.type];
      if (list) for (const h of list) {
        try { h(msg, view); }
        catch (e) { console.error('[ws] handler error for', msg.type, ':', e.message); }
      }

    } catch (err) {
      console.error('[ws] message parse error:', err);
    }
  };
}

// ---------- LLM Log toggle sync ----------
onMessage('llmLogState', (msg) => {
  state.llmLogEnabled = msg.enabled;
  const toggle = document.getElementById('toggle-llm-log');
  if (toggle) toggle.classList.toggle('on', msg.enabled);
});

// ---------- Wake-up / network recovery ----------
// When the OS sleeps, the browser suspends timers and the WebSocket dies at the
// TCP level. On wake, detect this immediately and reconnect — don't wait for
// the exponential backoff timer (which may not fire for a while).
function checkConnection() {
  if (!state.ws || state.ws.readyState === WebSocket.CLOSED || state.ws.readyState === WebSocket.CLOSING) {
    // Connection is dead — reconnect immediately
    forceReconnect();
  } else if (state.ws.readyState === WebSocket.OPEN) {
    // Connection looks alive — send a ping and verify with pong within 2s.
    // Mac lid-open can leave the TCP connection in a half-open state where
    // the browser hasn't fired onclose yet.
    state.pendingPong = true;
    sendWs({type: 'ping'});
    setTimeout(() => {
      if (state.pendingPong && state.ws?.readyState === WebSocket.OPEN) {
        console.log('[ws] no pong after wake, force reconnect');
        state.ws.close(); // triggers onclose → scheduleReconnect
      }
    }, 2000);
  }
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') checkConnection();
});

window.addEventListener('online', () => {
  // Network came back — give it a moment then check
  setTimeout(checkConnection, 500);
});
