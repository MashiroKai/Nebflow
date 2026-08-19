// flowAgentPopup.js — Mini session viewer for flow agents.
//
// Each flow agent session gets a ChatView instance that renders exactly
// like the main chat window. When the user clicks an agent pill, the
// pre-rendered DOM is moved into the popup modal — so all history and
// streaming output is preserved.
//
// The popup uses the .fullscreen variant: mounted on document.body and
// centered in the viewport (same style as the Delegate popup). It is NOT
// draggable — position is always centered.

import { ChatView, setActiveView, activeView, chatViews } from './chatView.js';
import { sendWs, onMessage, setFlowStepInterceptor } from './ws.js';
import { restoreFromBackendHistory } from './persistence.js';
import state from './state.js';
import { key } from './branding.js';

// ── Per-agent state ───────────────────────────────────────
// nodeSessionId → { view: ChatView, container: div, meta: {}, historyLoaded: bool }
const stepViews = new Map();
let currentStepId = null;
let popupOverlay = null;
let popupResizeObs = null;

// ── CSS ───────────────────────────────────────────────────
const POPUP_CSS = `<style id="flow-agent-popup-css">
/* Overlay fills the flow card (position:absolute inside .team-card).
   No background dim — the modal's own glass effect is enough. */
.flow-agent-overlay {
  position: absolute; top: 0; left: 0; right: 0; bottom: 0;
  display: flex; align-items: center; justify-content: center;
  z-index: 50;
  pointer-events: auto;
  animation: fa-fade-in 0.2s ease;
}
@keyframes fa-fade-in { from { opacity: 0; } to { opacity: 1; } }

/* Modal — fixed-position, always centered in the viewport. Not draggable. */
.flow-agent-modal {
  position: absolute;
  left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  width: calc(100% - 48px);
  height: calc(100% - 48px);
  max-width: 100%; max-height: 100%;
  display: flex; flex-direction: column;
  background: var(--glass-bg);
  /* Panel keeps its own glass blur; only the overlay is forbidden from dimming. */
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  border: 1px solid var(--glass-border);
  border-radius: 20px;
  overflow: hidden;
  box-shadow:
    inset 0 1px 0 0 rgba(255,255,255,0.25),
    0px 2px 8px rgba(0,0,0,0.04),
    0px 8px 32px rgba(0,0,0,0.10);
}
@media (prefers-color-scheme: dark) {
  .flow-agent-modal {
    box-shadow:
      inset 0 1px 0 0 rgba(255,255,255,0.04),
      0px 2px 8px rgba(0,0,0,0.20),
      0px 8px 32px rgba(0,0,0,0.35);
  }
}

/* Header — matches #header glassmorphism + sapphire refraction line. */
.flow-agent-header {
  display: flex; align-items: center; gap: 6px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--glass-border, rgba(255,255,255,0.08));
  flex-shrink: 0;
  position: relative;
  user-select: none;
}
.flow-agent-header::before {
  content: '';
  position: absolute;
  top: 0; left: 10%; right: 10%;
  height: 1px;
  background: linear-gradient(90deg,
    transparent 10%,
    var(--sapphire-refraction, rgba(99,179,237,0.25)) 50%,
    transparent 90%);
  pointer-events: none;
  z-index: 1;
}
.flow-agent-name {
  font: 600 13px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text);
}
.flow-agent-subtitle {
  font: 400 11px -apple-system, sans-serif;
  color: var(--color-text-muted);
  margin-right: auto; margin-left: 8px;
}
.flow-agent-ctx {
  display: inline-flex; align-items: center; gap: 4px;
  flex-shrink: 0;
  margin-right: 8px;
}
.flow-agent-close {
  cursor: pointer; font-size: 16px; line-height: 1;
  opacity: 0.7; transition: opacity 0.15s;
  padding: 2px 4px; color: var(--color-text);
  z-index: 10;
  border-radius: 4px;
}
.flow-agent-close:hover { opacity: 1; background: rgba(255,255,255,0.06); }
/* Code copy buttons inside popup: shift left to avoid overlapping close button */
.flow-agent-modal .code-copy-btn { right: 40px; }

/* Chat area — no custom overrides; inherits chat.css bubble/tool-card styles.
   Matches #chat layout: flex column + overscroll-behavior. */
.flow-agent-chat {
  flex: 1; overflow-y: auto;
  padding: 12px 16px 8px;
  display: flex; flex-direction: column;
  overscroll-behavior: contain;
  scrollbar-color: var(--color-frame-border) transparent;
}

/* Footer status bar — matches #input-bar glassmorphism + sapphire refraction */
.flow-agent-footer {
  flex-shrink: 0;
  display: flex; align-items: center; gap: 8px;
  padding: 8px 16px;
  border-top: 1px solid var(--glass-border);
  position: relative;
}
.flow-agent-footer::before {
  content: '';
  position: absolute;
  top: 0; left: 10%; right: 10%;
  height: 1px;
  background: linear-gradient(90deg,
    transparent 10%,
    var(--sapphire-refraction, rgba(99,179,237,0.25)) 50%,
    transparent 90%);
  pointer-events: none;
  z-index: 1;
}
.flow-agent-footer .fa-status-dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--color-text-muted); opacity: 0.5; flex-shrink: 0;
}
.flow-agent-footer.running .fa-status-dot {
  background: var(--color-primary, #07c160); opacity: 1;
  animation: fa-pulse 1.4s ease-in-out infinite;
}
.flow-agent-footer.done .fa-status-dot {
  background: var(--color-success, #4caf50); opacity: 1;
}
.flow-agent-footer.failed .fa-status-dot {
  background: var(--color-error, #f44336); opacity: 1;
}
/* Frozen (work-schedule park, freeze-spec §3.2): sapphire dot + tint ring,
   sapphire task text — distinct from running (pulse green) / done (green) */
.flow-agent-footer.frozen .fa-status-dot {
  background: rgb(var(--sapphire)); opacity: 1;
  box-shadow: 0 0 0 3px rgb(var(--sapphire) / 0.15);
  animation: none;
}
.flow-agent-footer.frozen .fa-task {
  color: rgb(var(--sapphire));
}
@keyframes fa-pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }
.flow-agent-footer .fa-task {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font: 500 11px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text-muted);
}

/* Hidden containers for background rendering */
.flow-agent-hidden {
  position: absolute;
  width: 0; height: 0; overflow: hidden;
  opacity: 0; pointer-events: none;
  left: -9999px;
}

/* Fullscreen variant — mounted on document.body, centered in the viewport.
   No dimming backdrop and no blur: the modal floats directly above the UI. */
.flow-agent-overlay.fullscreen {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  z-index: 1000;
  animation: fa-fade-in 0.2s ease;
}
.flow-agent-overlay.fullscreen .flow-agent-modal {
  position: relative;
  left: auto; top: auto;
  transform: none;
  width: 90%; max-width: 720px;
  height: 80vh; max-height: 85vh;
  margin: 6vh auto;
}
</style>`;

if (!document.getElementById('flow-agent-popup-css')) {
  document.head.insertAdjacentHTML('beforeend', POPUP_CSS);
}

// ── Hidden container for background rendering ─────────────
let hiddenRoot = null;
function getHiddenRoot() {
  if (!hiddenRoot) {
    hiddenRoot = document.createElement('div');
    hiddenRoot.className = 'flow-agent-hidden';
    document.body.appendChild(hiddenRoot);
  }
  return hiddenRoot;
}

// ── Ensure a ChatView exists for a nodeSessionId ──────────

function ensureStepView(sessionId) {
  if (stepViews.has(sessionId)) return stepViews.get(sessionId);

  // Create hidden DOM container — this is the "chat" area for this agent
  const container = document.createElement('div');
  container.className = 'flow-agent-chat';
  getHiddenRoot().appendChild(container);

  // Create ChatView with a fakeDom pointing to this container.
  // ChatView is the same class used by the main chat window.
  const fakeDom = {
    chat: container,
    input: null, sendBtn: null, stopBtn: null, attachBtn: null,
    attPreview: null, slashDropdown: null, queueBar: null,
    voiceBtn: null, voiceOverlay: null, voiceText: null,
    headerModelInfoEl: null, bgIndicatorEl: null, bgCountEl: null,
    bgDropdownEl: null, bgDropdownListEl: null,
    bgagentIndicatorEl: null, bgagentDropdownEl: null, bgagentDropdownListEl: null,
    sessionNameEl: null,
  };
  const view = new ChatView('flow-' + sessionId, fakeDom);
  view.mounted = true;
  view.sessionId = sessionId;
  // Hidden until the popup opens — ws.js gates DOM rendering while false.
  view.visible = false;
  // Register in the global view registry so findViewBySessionId() can route
  // live events here even while hidden (streamDispatchView then marks
  // dirtyWhileHidden → reopen forces a history refresh). This is what makes
  // the team branch (ws.js "team-" prefix) reach an open team popup at all.
  chatViews[view.id] = view;

  const entry = { view, container, meta: { agentName: '', task: '', status: '' }, historyLoaded: false };
  stepViews.set(sessionId, entry);
  enforceStepViewCap();
  return entry;
}

// ── Reset card widths so they re-flow to match the new container width ──
function resetCardWidths(container) {
  const wraps = container.querySelectorAll('.html-card-wrap');
  wraps.forEach(w => { w.style.width = ''; });
}

// ── Open popup ────────────────────────────────────────────

export function openStepPopup(stepId, nodeLabel, agentName, flowName, nodeSessionId) {
  closeStepPopup();
  currentStepId = nodeSessionId || stepId;

  // Ensure view exists (in case no events arrived yet)
  const entry = ensureStepView(currentStepId);
  entry.view.visible = true;

  // Events were skipped while hidden → DOM is stale or empty. Force a full
  // refresh from backend history (same pipeline as first open) and seed
  // in-flight stream text so the current turn's tail renders live.
  if (entry.view.dirtyWhileHidden) {
    entry.view.dirtyWhileHidden = false;
    entry.view.resetStream();
    entry.container.innerHTML = '';
    entry.historyLoaded = false;
    const sid = entry.view.sessionId;
    if (state.sessionTexts[sid]) entry.view.stream.aiText = state.sessionTexts[sid];
    if (state.sessionThinkingBuffers[sid]) entry.view.stream.thinkingText = state.sessionThinkingBuffers[sid];
  }

  popupOverlay = document.createElement('div');
  popupOverlay.className = 'flow-agent-overlay fullscreen';

  // Mount on document.body — the popup is always viewport-centered (same as
  // the Delegate popup). Mounting inside the flow pane would let renderAll's
  // scroll rebuild destroy an open popup; mounting on .team-card would drag
  // it with the scroll.
  const mountEl = document.body;

  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-name">${esc(agentName || entry.meta.agentName || '')}</span>
        <span class="flow-agent-subtitle">${esc(flowName || '')}</span>
        <span class="flow-agent-model" id="flow-agent-model"></span>
        <span class="flow-agent-ctx" id="flow-agent-ctx"></span>
        <div class="flow-agent-close" id="flow-agent-close">✕</div>
      </div>
      <div class="flow-agent-input-area" id="flow-input-area">
        <div id="flow-slash-dropdown" class="slash-dropdown"></div>
        <div id="flow-queue-bar"></div>
        <div class="fa-input-bar" id="flow-input-bar">
          <button class="glass-control fa-icon-btn" id="flow-attach-btn" title="Attach file">
            <i data-lucide="paperclip"></i>
          </button>
          <div class="fa-input-wrap">
            <div id="flow-attachment-preview" class="attachment-preview"></div>
            <textarea id="flow-input" rows="1" placeholder="Type a message..." autocomplete="off"></textarea>
          </div>
          <button class="glass-control" id="flow-send-btn" title="Send"><i data-lucide="send"></i></button>
          <button class="glass-control" id="flow-stop-btn" title="Stop" style="display:none"><i data-lucide="square"></i></button>
        </div>
      </div>
      <div class="flow-agent-footer" id="flow-agent-footer">
        <span class="fa-status-dot"></span>
        <span class="fa-task">${esc(entry.meta.task || 'Session')}</span>
      </div>
    </div>
  `;

  // Async-fetch agent model info for header badge
  if (agentName) {
    fetchAgentModelBadge(agentName);
  }

  mountEl.appendChild(popupOverlay);

  // Move the pre-rendered container from hidden root into the modal,
  // inserting it BEFORE the input area so the layout is header / chat / input / footer.
  const modal = popupOverlay.querySelector('.flow-agent-modal');
  const footer = popupOverlay.querySelector('#flow-agent-footer');
  const inputArea = popupOverlay.querySelector('#flow-input-area');
  modal.insertBefore(entry.container, inputArea);
  entry.footerEl = footer;

  // Wire view.dom to real input elements so initInput() can bind events
  const v = entry.view;
  v.dom.input = popupOverlay.querySelector('#flow-input');
  v.dom.sendBtn = popupOverlay.querySelector('#flow-send-btn');
  v.dom.stopBtn = popupOverlay.querySelector('#flow-stop-btn');
  v.dom.attachBtn = popupOverlay.querySelector('#flow-attach-btn');
  v.dom.attPreview = popupOverlay.querySelector('#flow-attachment-preview');
  v.dom.slashDropdown = popupOverlay.querySelector('#flow-slash-dropdown');
  v.dom.queueBar = popupOverlay.querySelector('#flow-queue-bar');
  // Voice elements — create dummy elements so initInput doesn't crash on null
  v.dom.voiceBtn = document.createElement('button');
  v.dom.voiceOverlay = document.createElement('div');
  v.dom.voiceText = document.createElement('div');
  v.dom.voiceBtn.style.display = 'none';

  // Disable input if no sessionId (can't route messages)
  if (!nodeSessionId) {
    v.dom.input.readOnly = true;
    v.dom.input.placeholder = 'Agent not running — cannot send messages';
    v.dom.sendBtn.disabled = true;
    v.dom.attachBtn.disabled = true;
    v.dom.sendBtn.style.opacity = '0.4';
    v.dom.attachBtn.style.opacity = '0.4';
  } else if (!v._inputBound) {
    // Bind input events (idempotent — only once per view)
    v._inputBound = true;
    import('./input.js').then(({ initInput }) => {
      import('./chat.js').then(({ refreshSendButtonState }) => {
        setActiveView(v);
        initInput(v);
        refreshSendButtonState();
      });
    });
  }

  // Render lucide icons for the new input-area buttons
  import('./utils.js').then(({ createIconsIn }) => {
    createIconsIn(popupOverlay.querySelector('#flow-input-area'));
  });

  syncInputButtons(entry);

  // Sync footer with any meta captured before opening
  updateFooterStatus(entry);

  // Render context usage ring (if model info exists for this session)
  updatePopupCtxRing();

  popupOverlay.addEventListener('click', (e) => {
    if (e.target === popupOverlay || e.target.id === 'flow-agent-close') closeStepPopup();
  });

  // Auto-scroll
  entry.container.addEventListener('scroll', () => {
    const atBottom = entry.container.scrollTop + entry.container.clientHeight >= entry.container.scrollHeight - 40;
    entry.view.stream.scrollSnapped = atBottom;
  });

  // Scroll to bottom on open + set initial snapped state
  requestAnimationFrame(() => {
    entry.container.scrollTop = entry.container.scrollHeight;
  });
  entry.view.stream.scrollSnapped = true;

  // Observe modal size changes
  popupResizeObs = new ResizeObserver(() => {
    resetCardWidths(entry.container);
  });
  popupResizeObs.observe(modal);

  // Load session history from backend via WS getHistory (same as main chat).
  // Flow/team sessions are real sessions persisted live on disk, so always
  // re-pull on open — cheap (disk read, 100-msg cap) and guarantees fresh
  // content even if events streamed in while the popup was closed or the
  // same session was shown in the main window meanwhile.
  if (nodeSessionId) {
    entry.historyLoaded = true;
    setActiveView(entry.view);
    entry.view.pagination.pendingInitialLoad = true;
    sendWs({ type: 'getHistory', sessionId: nodeSessionId, limit: 100 });
  }
}

// ── Context usage ring ───────────────────────────────────

function fmtTokens(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return Math.round(n / 1000) + 'k';
  return String(n);
}

/** Fetch agent model config and render a badge in the popup header. */
async function fetchAgentModelBadge(agentName) {
  try {
    const token = localStorage.getItem(key('token')) || '';
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const resp = await fetch(`/api/agents/${encodeURIComponent(agentName)}/model`, { headers });
    if (!resp.ok) return;
    const cfg = await resp.json();
    const el = popupOverlay?.querySelector('#flow-agent-model');
    if (!el) return;
    // preferred is the configured model — trust it over `current`
    // (current is only a reference from the backend resolution).
    const current = cfg.preferred || cfg.current || cfg.default || '';
    if (!current) { el.innerHTML = ''; return; }
    const isFallback = cfg.preferred && current !== cfg.preferred;
    el.innerHTML = isFallback
      ? `<span class="flow-agent-model-badge">${esc(current)}</span>`
      : `<span class="flow-agent-subtitle">${esc(current)}</span>`;
  } catch (e) { /* non-critical */ }
}

/** Render the context usage ring into the popup header.
 *  Matches the main window's ctx-ring style (same SVG structure, threshold line,
 *  colors, and transitions) for visual consistency. */
function updatePopupCtxRing() {
  if (!popupOverlay || !currentStepId) return;
  const el = popupOverlay.querySelector('#flow-agent-ctx');
  if (!el) return;
  const info = state.sessionModelInfo[currentStepId];
  if (!info || !info.contextWindow) { el.innerHTML = ''; el.style.display = 'none'; return; }
  const ratio = info.inputTokens != null ? info.inputTokens / info.contextWindow : 0;
  const pct = Math.min(Math.round(ratio * 100), 100);
  let color = '#4caf50';
  if (ratio > 0.5) color = '#d4a030';
  if (ratio > 0.75) color = '#e53935';
  const R = 15;
  const CIRC = 2 * Math.PI * R;
  const dashLen = CIRC * pct / 100;
  const thresholdPct = Math.round((info.compactThreshold || 0.8) * 100);
  const thresholdAngle = thresholdPct * 3.6;
  const tooltip = info.inputTokens != null
    ? `${fmtTokens(info.inputTokens)} / ${fmtTokens(info.contextWindow)} tokens (${pct}%) · threshold ${thresholdPct}%`
    : `${fmtTokens(info.contextWindow)} context window`;
  el.title = tooltip;
  el.style.display = 'inline-flex';
  el.innerHTML = `<div class="ctx-ring-wrap ctx-compact" title="${tooltip}">
    <svg width="28" height="28" viewBox="0 0 36 36" class="ctx-ring-svg">
      <circle cx="18" cy="18" r="${R}" fill="none" stroke="rgba(128,128,128,0.15)" stroke-width="3.5"/>
      <circle cx="18" cy="18" r="${R}" fill="none" stroke="${color}" stroke-width="3.5"
              stroke-dasharray="${dashLen.toFixed(1)} ${CIRC.toFixed(1)}"
              stroke-linecap="round"
              transform="rotate(-90 18 18)"
              style="transition:stroke-dasharray 0.4s ease, stroke 0.4s ease;"/>
      <line x1="18" y1="1.5" x2="18" y2="5" stroke="rgba(200,80,80,0.7)" stroke-width="1.5"
            class="ctx-ring-threshold"
            transform="rotate(${thresholdAngle.toFixed(1)} 18 18)"/>
    </svg>
    <span class="ctx-ring-pct">${pct}</span>
  </div>`;
}

// Update popup ring when model info arrives for any session
onMessage('usageUpdate', () => { if (popupOverlay) updatePopupCtxRing(); });
onMessage('done', () => { if (popupOverlay) updatePopupCtxRing(); });

export function closeStepPopup() {
  if (!popupOverlay) return;
  if (currentStepId) {
    const entry = stepViews.get(currentStepId);
    if (entry) {
      entry.view.visible = false; // hidden — ws.js gates DOM rendering again
      getHiddenRoot().appendChild(entry.container);
      entry.footerEl = null;
      // Finished agents don't need their DOM kept around — schedule cleanup.
      if (entry.meta.status === 'done') scheduleStepViewRemoval(currentStepId);
    }
  }
  if (popupResizeObs) {
    popupResizeObs.disconnect();
    popupResizeObs = null;
  }
  popupOverlay.remove();
  popupOverlay = null;
  currentStepId = null;
}

// ── View cleanup (memory) ────────────────────────────────
// Without this, every flow agent's hidden container lived forever — 10
// finished agents ≈ 50-500MB of detached DOM. Views are destroyed a few
// seconds after the agent finishes (user can still reopen from history),
// and a simple LRU cap prevents long-session accumulation.
const STEP_VIEW_TTL_MS = 8000;
const STEP_VIEW_LRU_CAP = 20;
const removalTimers = new Map();

export function removeStepView(sessionId) {
  const entry = stepViews.get(sessionId);
  if (entry) {
    if (removalTimers.has(sessionId)) {
      clearTimeout(removalTimers.get(sessionId));
      removalTimers.delete(sessionId);
    }
    delete chatViews[entry.view.id];
    entry.container.remove();
    stepViews.delete(sessionId);
  }
}

function scheduleStepViewRemoval(sessionId) {
  if (removalTimers.has(sessionId)) return;
  removalTimers.set(sessionId, setTimeout(() => {
    removalTimers.delete(sessionId);
    // Never destroy the view while the user is looking at it — the LRU cap
    // reclaims it after the popup is closed.
    if (currentStepId === sessionId && popupOverlay) return;
    removeStepView(sessionId);
  }, STEP_VIEW_TTL_MS));
}

/** Evict oldest views (Map insertion order) beyond the cap. Never evicts
 *  the currently open view. */
function enforceStepViewCap() {
  while (stepViews.size > STEP_VIEW_LRU_CAP) {
    let evicted = false;
    for (const key of stepViews.keys()) {
      if (key === currentStepId && popupOverlay) continue;
      removeStepView(key);
      evicted = true;
      break;
    }
    if (!evicted) break;
  }
}

// ── WS event interception ────────────────────────────────
// Called from ws.js BEFORE handler dispatch.
// Routes agent* events to the correct ChatView by setting it as activeView,
// so chat.js rendering functions target the right container.
// Returns true if the event was handled (should NOT render into primary chat).

export function interceptFlowStep(msg) {
  if (!msg.nodeSessionId) return false;
  const entry = ensureStepView(msg.nodeSessionId);

  // Capture meta + lifecycle status from key events.
  if (msg.type === 'agentStart') {
    entry.meta.agentName = msg.name || '';
    entry.meta.task = msg.taskDescription || '';
    entry.meta.status = 'running';
  } else if (msg.type === 'agentDone' || msg.type === 'agentEnd') {
    entry.meta.status = 'done';
    scheduleStepViewRemoval(msg.nodeSessionId);
  }

  // Set activeView so chat.js rendering functions target this view's container.
  // This is the same mechanism used for the primary chat window.
  setActiveView(entry.view);

  // Auto-scroll if popup is showing this agent
  if (currentStepId === msg.nodeSessionId) {
    requestAnimationFrame(() => {
      const snapped = entry.view.stream.scrollSnapped;
      const threshold = 60;
      if (snapped || entry.container.scrollHeight - entry.container.scrollTop - entry.container.clientHeight < threshold) {
        entry.container.scrollTop = entry.container.scrollHeight;
      }
    });
    updateFooterStatus(entry);
  }
  return true;
}

// Register the interceptor with ws.js. This breaks what would otherwise be a
// circular import (ws.js → flowAgentPopup.js → ws.js). ws.js holds the
// interceptor in a variable and calls it at runtime during onmessage.
setFlowStepInterceptor(interceptFlowStep);

// ── historyPage handler ───────────────────────────────────
// When the backend responds to getHistory for a flow agent session,
// we need to render it into the popup's ChatView, not the primary one.
// This is called from main.js's historyPage handler when the sessionId
// matches a flow agent session.

export function handleFlowAgentHistory(msg) {
  const entry = stepViews.get(msg.sessionId);
  if (!entry) return false; // not a flow agent session

  const view = entry.view;
  view.pagination.loading = false;

  const isInitialLoad = view.pagination.pendingInitialLoad;
  if (isInitialLoad) {
    view.pagination.pendingInitialLoad = false;
    entry.container.innerHTML = '';
    view.pagination.offset = msg.offset;
    view.pagination.total = msg.total;
    view.pagination.hasMore = msg.hasMore;

    setActiveView(view);
    restoreFromBackendHistory(msg.messages);

    requestAnimationFrame(() => {
      entry.container.scrollTop = entry.container.scrollHeight;
    });
  }
  return true;
}

// Reflect entry.meta (status/task) into the footer element, if mounted.
function updateFooterStatus(entry) {
  if (!entry.footerEl) return;
  const status = entry.meta.status || '';
  entry.footerEl.classList.remove('running', 'done', 'failed');
  if (status) entry.footerEl.classList.add(status);
  const taskEl = entry.footerEl.querySelector('.fa-task');
  if (taskEl) {
    const label = entry.meta.task
      ? entry.meta.task
      : status === 'running' ? 'Running…'
      : status === 'done' ? 'Done'
      : status === 'failed' ? 'Failed'
      : 'Session';
    taskEl.textContent = label;
  }
  syncInputButtons(entry);
}

/** Toggle send/stop button visibility based on agent busy state. */
function syncInputButtons(entry) {
  if (!entry.view.dom.sendBtn || !entry.view.dom.stopBtn) return;
  const busy = entry.meta.status === 'running';
  entry.view.dom.sendBtn.style.display = busy ? 'none' : 'flex';
  entry.view.dom.stopBtn.style.display = busy ? 'flex' : 'none';
}

// ── Utils ────────────────────────────────────────────────
function esc(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&').replace(/</g, '<').replace(/>/g, '>')
    .replace(/"/g, '"').replace(/'/g, '&#039;');
}
