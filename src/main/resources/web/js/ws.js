import state from './state.js';
import { findViewBySessionId, setActiveView, activeView, chatViews } from './chatView.js';
import { isBgAgentId } from './utils.js';
import { addNotification } from './notificationBanner.js';
import { brand } from './brand.js';
import { key } from './branding.js';

// Auth token cookie name follows the brand namespace (branding.js copies the
// legacy cookie across on rename day; the backend accepts both names during
// the transition).
const TOKEN_COOKIE = key('token');

// ── Exported protocol typedefs (P2-3 core contracts) ──────────────────────

/**
 * Incoming WS event from the backend (protocol.scala). `type` discriminates;
 * events carry session routing keys plus event-specific payloads - the index
 * signature keeps the protocol permissive while the named fields pin the
 * routing contract every event shares.
 * @typedef {{ type: string, sessionId?: string, rootSessionId?: string, nodeSessionId?: string, agentId?: string, delta?: string } & Record<string, any>} WSIncomingMessage
 */

/**
 * Outgoing WS message sent via sendWs. `type` is the command discriminator -
 * but NOTE: plain user-input messages deliberately OMIT it (the backend
 * treats a type-less message with `content` as user input; see input.js).
 * @typedef {{ type?: string } & Record<string, any>} WSOutgoingMessage
 */

/**
 * Handler registered via onMessage for a specific incoming event type.
 * `view` is the ChatView owning msg.sessionId (null when hidden-gated).
 * @typedef {(msg: WSIncomingMessage, view: any) => void} WSMessageHandler
 */

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
// events (nodeSessionId starts with "delegate-"/"subtask-" - backend protocol) don't get
// swallowed by the flowStepInterceptor which claims all nodeSessionId events.
let bgAgentStepInterceptor = null;
export function setBgAgentStepInterceptor(fn) { bgAgentStepInterceptor = fn; }

// ── Team lifecycle meta applier (registered by flowAgentPopup.js) ─────────
// The team branch routes converted events itself; the popup footer still
// needs raw lifecycle meta. Injected to avoid a ws→popup circular import.
let teamMetaApplier = null;
export function setTeamMetaApplier(fn) { teamMetaApplier = fn; }

// ── Hidden-view render gating ─────────────────────────────────────────────
// Popup ChatViews (flow / bg-agent / team) have visible === false while their
// popup is closed. Streaming events for hidden views are dispatched with
// view=null so chat.js handlers skip DOM rendering entirely - the global
// per-session buffers in state.js (sessionTexts/sessionThinkingBuffers/...)
// still accumulate, so nothing is lost; the popup re-renders from backend
// history when opened (dirtyWhileHidden forces the refresh).
// Interactive events (askUser/askPermission) are exempt: they keep the real
// view and render into the hidden container immediately - low frequency,
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
      return { type: 'thinkingDelta', sessionId: sid, delta: msg.delta || '' };
    case 'agentToolCallDetected':
      return { type: 'toolCallDetected', sessionId: sid, name: msg.name || '' };
    case 'agentToolStart':
      return { type: 'toolStart', sessionId: sid, label: msg.label || '' };
    case 'agentToolEnd':
      return { type: 'toolEnd', sessionId: sid, label: msg.label || '', summary: msg.summary || '', content: msg.content || '', isError: msg.isError || false, input: msg.input || null };
    case 'agentDone':
      return { type: 'done', sessionId: sid, model: msg.model, contextWindow: msg.contextWindow, inputTokens: msg.inputTokens, compactThreshold: msg.compactThreshold, outputTokens: msg.outputTokens };
    case 'usageUpdate':
      // #308 actual model: forward the round's actual model so popup badges
      // can live-refresh. model is None on older backends → falls back cleanly.
      return { type: 'usageUpdate', sessionId: sid, inputTokens: msg.inputTokens, contextWindow: msg.contextWindow, compactThreshold: msg.compactThreshold, outputTokens: msg.outputTokens, model: msg.model };
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
  // §4 (todo-panel-spec): circle completion controls dim/disable while offline
  const taskList = document.getElementById('task-list');
  if (taskList) taskList.classList.toggle('task-ws-down', !state.connected);
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
  'mcpServersUpdate', 'configData', 'configUpdated', 'configUpdateFailed',
  'toolResultTtl', 'toolResultTtlSaved', 'modelOptions',
  'memoryData', 'memorySaved', 'memoryStatus', 'memoryChanged',
  'rulesData', 'rulesSaved', 'rulesDeleted', 'rulesStatus',
  'browseResult',
  'updateCheckResult', 'updateStarted', 'updateCompleted',
  'remoteUpdateResult', 'peerListChanged',
  'activeBgTasks', 'activeAgents',
  'mailQueued', 'mailDequeued',
  'dropbox-message', 'dropbox-file-response', 'dropbox-file-complete', 'dropbox-history', 'dropboxError',
  'friend_event'
]);
const TERMINAL_MSG_TYPES = new Set([
  'done', 'error', 'interrupted', 'maxTokens', 'sessionBusy',
  'compactStart', 'compactComplete', 'compactFailed',
  'backgroundTaskUpdate', 'taskListUpdate',
  'askUser', 'askPermission',
  'frozen', 'resumed', 'agentFrozen', 'agentResumed',
  'errorEscalated', // error-recovery escalation → parent/user decision card
  'taskStuck', // sub-agent management panel (2026-08-22): stuck visibility for any session
  'cancelAgentResult', // management panel stop action result (2026-08-23 upgrade)
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
  'flowMail', 'flowStarted', 'flowProgress', 'flowCompleted', 'teamList',
  // #308 actual model: sub-agent usageUpdate (sessionId = nodeSessionId) must
  // survive the entry filter below to reach the popup live-refresh path.
  // Without this, the event is silently dropped for non-active sessions.
  'usageUpdate'
]);

/**
 * @param {string} type - incoming event name (TERMINAL_MSG_TYPES / STREAM_MSG_TYPES)
 * @param {WSMessageHandler} handler
 * @returns {() => void} unsubscribe - removes this handler (one-shot response
 *   listeners like fileSaved rely on it; push() alone returns a number, and
 *   calling that as a function throws inside the dispatch loop).
 */
export function onMessage(type, handler) {
  if (!handlers[type]) handlers[type] = [];
  handlers[type].push(handler);
  return () => {
    const list = handlers[type];
    const i = list.indexOf(handler);
    if (i >= 0) list.splice(i, 1);
  };
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

// ---------- Disconnect callback registry ----------
// Mirror of onReconnect for UI that must react to connection loss (e.g. the
// friends chat modal's "reconnecting" bar + disabled input, spec §7).
const disconnectCallbacks = [];

export function onDisconnect(callback) {
  disconnectCallbacks.push(callback);
}

// ---------- Reconnection state ----------
// No hard limit on attempts - this is a desktop app; the connection should
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
  // Close the old connection cleanly before opening a new one - prevents orphan sockets.
  if (state.ws) {
    try { state.ws.onclose = null; state.ws.close(); } catch (_) {}
    state.ws = null;
  }
  connect();
}

// ---------- Send ----------
/** @param {WSOutgoingMessage | string} msg */
export function sendWs(msg) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg));
  }
}

// ---------- Connect ----------
// Cookie auth is preferred (token stays out of URLs). But some setups block
// cookies on localhost (Safari "block all cookies", restrictive private
// modes) - the WS handshake then 403s forever even though the token is in
// localStorage, and every rejected handshake logs an unsuppressible native
// browser error line. So instead of blindly trying cookie-only first, probe
// cookie reachability once with a cheap same-origin fetch before the first
// WebSocket attempt:
//   200      → cookie rides along → connect cookie-first (token out of URL)
//   401/403  → cookie blocked → connect with ?token= on the FIRST attempt,
//              so the doomed cookie-only handshake never happens
//   fetch error (server restarting, network down) → inconclusive → fall
//              through to cookie-first; the onclose fallback chain below
//              stays as the last line of defense.
let authParamFallback = false;
let authProbePromise = null;
let authProbeSettled = false;

// Probe target: GET /api/nf-tasks - lightweight (reads the task index),
// authenticated via extractToken (param → Authorization header → cookie), so
// a bare same-origin fetch exercises exactly the cookie path the WS handshake
// relies on. Verified: 403 without credentials, 200 with a valid cookie.
function probeCookieAuth() {
  return fetch('/api/nf-tasks?limit=1', {
    credentials: 'same-origin',
    cache: 'no-store',
    signal: AbortSignal.timeout(3000),
  }).then(resp => {
    if (resp.status === 401 || resp.status === 403) {
      authParamFallback = true;
    }
  }).catch(() => { /* inconclusive - proceed cookie-first */ });
}

export function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const urlParams = new URLSearchParams(location.search);
  const token = urlParams.get('token') || '';
  if (token) {
    localStorage.setItem(key('token'), token);
  }
  const storedToken = localStorage.getItem(key('token')) || token;
  // Set auth cookie so the WebSocket connection authenticates via cookie
  // instead of exposing the token in the URL (history/logs/Referer leakage).
  if (storedToken) {
    // Skip the rewrite when the cookie already holds the right value: every
    // document.cookie op is a separate async message to the network service,
    // so a purge+set immediately followed by new WebSocket() can race - the
    // handshake may be evaluated in the empty-jar window between the purge
    // and the set → spurious 403 (observed in smoke tests). No rewrite, no
    // window.
    const desired = encodeURIComponent(storedToken);
    const current = document.cookie.match(new RegExp(`(?:^|;\\s*)${TOKEN_COOKIE}=([^;]*)`))?.[1];
    if (current !== desired) {
      // Purge stale variants first: a cookie written by an older build with
      // different attributes (Secure / domain=) is a SEPARATE jar entry that a
      // plain document.cookie write cannot replace - the server would keep
      // seeing the stale value win the cookie race.
      const gone = '; expires=Thu, 01 Jan 1970 00:00:00 GMT';
      document.cookie = `${TOKEN_COOKIE}=; path=/${gone}`;
      document.cookie = `${TOKEN_COOKIE}=; path=/; domain=${location.hostname}${gone}`;
      document.cookie = `${TOKEN_COOKIE}=${desired}; path=/; SameSite=Strict`;
    }
  }
  // First connect: probe cookie reachability before opening the WebSocket so
  // a cookie-blocked browser goes straight to ?token= (zero rejected
  // handshakes, zero native browser error lines). Deferred connects while the
  // probe is in flight simply wait for it to settle.
  if (!authProbeSettled) {
    if (!authProbePromise) {
      authProbePromise = probeCookieAuth().finally(() => {
        authProbeSettled = true;
        connect();
      });
    }
    return;
  }
  // Connect without token in URL - relies on cookie auth. After a rejected
  // handshake (likely cookie blocked), retry with the ?token= fallback.
  const qs = (authParamFallback && storedToken) ? `?token=${encodeURIComponent(storedToken)}` : '';
  const wsUrl = `${proto}//${location.host}/ws${qs}`;
  try {
    state.ws = new WebSocket(wsUrl);
  } catch (e) {
    console.error('[ws] WebSocket constructor failed:', e);
    scheduleReconnect();
    return;
  }

  let opened = false;
  state.ws.onopen = () => {
    opened = true;
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
    disconnectCallbacks.forEach(cb => { try { cb(); } catch (e) { console.error('[ws] disconnect callback error:', e); } });
    if (state.heartbeat) { clearInterval(state.heartbeat); state.heartbeat = null; }
    if (!opened) {
      // Handshake rejected (e.g. 403) before the socket ever opened.
      if (!authParamFallback && storedToken) {
        authParamFallback = true;
        console.info('[ws] handshake rejected - retrying with ?token= fallback (cookie may be blocked)');
      } else {
        const hint = storedToken
          ? 'token 无效或服务端已更换 token，请用启动日志中的带 token 地址重新打开'
          : `页面缺少 token，请通过启动时打印的带 token 地址打开（token 在 ~/${brand.homeDirName ?? '.nebflow'}/auth.json）`;
        console.warn('[ws] connect failed:', hint);
        addNotification('conn', `无法连接 ${brand.lowerName}：${hint}`);
      }
    }
    scheduleReconnect();
  };

  state.ws.onerror = () => {
    state.connected = false;
    syncSendButtonConnState();
  };

  state.ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data);

      // Pong reply - clear pending flag (used by heartbeat + wake detection)
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
      // Global messages (serverConfig, sessionList, configData…) are app-wide
      // and carry no sessionId — they must NOT null the current view. Before
      // this guard, every serverConfig broadcast called setActiveView(null),
      // tearing down the active window after any config save (STT/freeze
      // echo). Keep the pre-message view for them.
      if (msg.sessionId || !GLOBAL_MSG_TYPES.has(msg.type)) {
        setActiveView(view || null);
      } else {
        setActiveView(savedView);
      }

      // ── Background-agent popup: intercept events with nodeSessionId ─────
      // Delegate sub-agent events carry nodeSessionId (injected by DelegateTool's
      // routeWsSend - the "delegate-" prefix is backend protocol). Route them
      // to the bg-agent popup's ChatView.
      // Checked BEFORE flowStepInterceptor because both use nodeSessionId —
      // bg-agent IDs start with "delegate-"/"subtask-" so we can distinguish.
      if (msg.nodeSessionId && isBgAgentId(msg.nodeSessionId) && bgAgentStepInterceptor && bgAgentStepInterceptor(msg)) {
        const converted = convertAgentEvent(msg);
        if (converted) {
          const convList = handlers[converted.type];
          // Gating EXEMPT (regression fix): Delegate/SubTask sessions are
          // ephemeral - the backend only persists their ui.json at completion,
          // so getHistory returns empty while the sub-agent is still running.
          // Before hidden-view gating, live events rendered into the hidden
          // popup container and masked that gap; gating made the popup blank.
          // Render live (pre-gating behavior). Memory stays bounded via
          // cleanupBgAgentView's post-done TTL + the stepViews LRU cap.
          const convView = activeView;
          if (convList) for (const h of convList.slice()) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] bg-agent handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (for bg-agent indicator status, etc.)
        const origView = activeView;
        const list = handlers[msg.type];
        if (list) for (const h of list.slice()) {
          try { h(msg, origView); }
          catch (e) { console.error('[ws] bg-agent handler error for', msg.type, ':', e.message); }
        }
        setActiveView(savedView); // restore pre-message view (BUG 6)
        return;
      }

      // ── Team agent events: convert + route to the agent's popup ──
      // MailTool.activateAgent stamps Mail-activated team agent events with
      // nodeSessionId = "team-<sessionId>". Convert them to standard chat
      // events (agentTextDelta → textDelta, etc. - same as the flow/delegate
      // branches below) and route them to the popup ChatView. The popup is
      // registered under the BARE sessionId (openStepPopup keys team popups
      // with the unprefixed sid from /api/teams/mounted), so strip the
      // prefix before converting/looking up. Must NOT fall through to the
      // flow interceptor below - its ensureStepView would key on the
      // prefixed id and never match the popup.
      if (msg.nodeSessionId && msg.nodeSessionId.startsWith('team-')) {
        const bareSid = msg.nodeSessionId.replace(/^team-/, '');
        const teamView = findViewBySessionId(bareSid) || null;
        setActiveView(teamView);
        // Management panel (2026-08-22): the team branch routes converted
        // events itself, but the popup's status footer still needs the raw
        // lifecycle meta (running/thinking/tool/stuck-clear) — apply it here.
        if (teamMetaApplier) teamMetaApplier(msg);
        const converted = convertAgentEvent({ ...msg, nodeSessionId: bareSid });
        if (converted) {
          const convList = handlers[converted.type];
          const convView = streamDispatchView(teamView);
          if (convList) for (const h of convList.slice()) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] team handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (status tracking, delegate
        // indicator, stream timeouts) with the pre-routed view.
        const teamList = handlers[msg.type];
        if (teamList) for (const h of teamList.slice()) {
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
          if (convList) for (const h of convList.slice()) {
            try { h(converted, convView); }
            catch (e) { console.error('[ws] handler error for', converted.type, ':', e.message); }
          }
        }
        // Also dispatch the original event (for status tracking, etc.)
        // Interactive events keep the real view (render into hidden container).
        const origView = (msg.type === 'askUser' || msg.type === 'askPermission') ? activeView : streamDispatchView(activeView);
        const list = handlers[msg.type];
        if (list) for (const h of list.slice()) {
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
        if (planList) for (const h of planList.slice()) {
          try { h(msg); }
          catch (e) { console.error('[ws] plan handler error:', e.message); }
        }
        return;
      }

      // Dispatch to handlers
      const list = handlers[msg.type];
      if (list) for (const h of list.slice()) {
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
// TCP level. On wake, detect this immediately and reconnect - don't wait for
// the exponential backoff timer (which may not fire for a while).
function checkConnection() {
  if (!state.ws || state.ws.readyState === WebSocket.CLOSED || state.ws.readyState === WebSocket.CLOSING) {
    // Connection is dead - reconnect immediately
    forceReconnect();
  } else if (state.ws.readyState === WebSocket.OPEN) {
    // Connection looks alive - send a ping and verify with pong within 2s.
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
  // Network came back - give it a moment then check
  setTimeout(checkConnection, 500);
});
