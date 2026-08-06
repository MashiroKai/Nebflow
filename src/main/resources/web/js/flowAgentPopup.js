// flowAgentPopup.js — Mini session viewer for flow agents.
//
// Each flow agent session gets a ChatView instance that renders exactly
// like the main chat window. When the user clicks an agent pill, the
// pre-rendered DOM is moved into the popup modal — so all history and
// streaming output is preserved.
//
// The popup is mounted INSIDE the flow card (not on document.body) so it
// is positioned and sized relative to the card, not the viewport.

import { ChatView, setActiveView, activeView, chatViews } from './chatView.js';
import { sendWs, onMessage, setFlowStepInterceptor } from './ws.js';
import { restoreFromBackendHistory } from './persistence.js';
import state from './state.js';

// ── Per-agent state ───────────────────────────────────────
// nodeSessionId → { view: ChatView, container: div, meta: {}, historyLoaded: bool }
const stepViews = new Map();
let currentStepId = null;
let popupOverlay = null;
let popupResizeObs = null;

export function getStepView(sessionId) {
  return stepViews.get(sessionId) || null;
}

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

/* Modal — absolutely positioned so it can be dragged within the flow card.
   Initial position is centered; drag bar (header) moves it. */
.flow-agent-modal {
  position: absolute;
  left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  width: calc(100% - 48px);
  height: calc(100% - 48px);
  max-width: 100%; max-height: 100%;
  display: flex; flex-direction: column;
  background: var(--glass-bg);
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

/* Header — matches #header glassmorphism + sapphire refraction line.
   Doubles as the drag bar: cursor:grab, mousedown initiates drag. */
.flow-agent-header {
  display: flex; align-items: center; gap: 6px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--glass-border, rgba(255,255,255,0.08));
  flex-shrink: 0;
  position: relative;
  cursor: grab;
  user-select: none;
}
.flow-agent-header:active { cursor: grabbing; }
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
    statusWrap: null, statusText: null, lottieSpinnerEl: null,
    attPreview: null, slashDropdown: null, queueBar: null,
    voiceBtn: null, voiceOverlay: null, voiceText: null,
    headerModelInfoEl: null, bgIndicatorEl: null, bgCountEl: null,
    bgDropdownEl: null, bgDropdownListEl: null,
    delegateIndicatorEl: null, delegateDropdownEl: null, delegateDropdownListEl: null,
    sessionNameEl: null,
  };
  const view = new ChatView('flow-' + sessionId, fakeDom);
  view.mounted = true;
  view.sessionId = sessionId;

  const entry = { view, container, meta: { agentName: '', task: '', status: '' }, historyLoaded: false };
  stepViews.set(sessionId, entry);
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

  popupOverlay = document.createElement('div');
  popupOverlay.className = 'flow-agent-overlay';

  // Mount on the flow pane's stable overlay root (#flow-overlay-root), a
  // sibling of #team-scroll that survives renderAll re-renders. Mounting
  // directly on the pane would let renderAll's scroll rebuild destroy an open
  // popup; mounting on .team-card would drag it with the scroll.
  // Tabs are now split into 'teams' and 'flows' — check both.
  let flowPane = document.querySelector('.canvas-tab-pane[data-tab-id="teams"]')
    || document.querySelector('.canvas-tab-pane[data-tab-id="flows"]');
  const overlayRootEl = flowPane?.querySelector('#flow-overlay-root');
  const flowCard = document.querySelector('.team-card');
  const mountEl = overlayRootEl || flowPane || flowCard || document.body;

  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-name">${esc(agentName || entry.meta.agentName || '')}</span>
        <span class="flow-agent-subtitle">${esc(flowName || '')}</span>
        <span class="flow-agent-model" id="flow-agent-model"></span>
        <span class="flow-agent-ctx" id="flow-agent-ctx"></span>
        <div class="flow-agent-close" id="flow-agent-close">✕</div>
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
  // inserting it BEFORE the footer so the layout is header / chat / footer.
  const modal = popupOverlay.querySelector('.flow-agent-modal');
  const footer = popupOverlay.querySelector('#flow-agent-footer');
  modal.insertBefore(entry.container, footer);
  entry.footerEl = footer;

  // Sync footer with any meta captured before opening
  updateFooterStatus(entry);

  // Render context usage ring (if model info exists for this session)
  updatePopupCtxRing();

  // ── Drag: mousedown on header moves the modal within the flow card ──
  const header = popupOverlay.querySelector('.flow-agent-header');
  if (header) {
    header.addEventListener('mousedown', (e) => {
      if (e.target.id === 'flow-agent-close') return;
      e.preventDefault();
      const overlayRect = popupOverlay.getBoundingClientRect();
      const modalRect = modal.getBoundingClientRect();
      const startX = e.clientX - modalRect.left;
      const startY = e.clientY - modalRect.top;

      const onMove = (ev) => {
        let newLeft = ev.clientX - overlayRect.left - startX;
        let newTop = ev.clientY - overlayRect.top - startY;
        newLeft = Math.max(0, Math.min(newLeft, overlayRect.width - modalRect.width));
        newTop = Math.max(0, Math.min(newTop, overlayRect.height - modalRect.height));
        modal.style.transform = 'none';
        modal.style.left = newLeft + 'px';
        modal.style.top = newTop + 'px';
      };
      const onUp = () => {
        document.removeEventListener('mousemove', onMove);
        document.removeEventListener('mouseup', onUp);
      };
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', onUp);
    });
  }

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
  // This uses the exact same pipeline as session switching — the backend
  // responds with historyPage, which restoreFromBackendHistory renders.
  if (nodeSessionId && !entry.historyLoaded && entry.container.children.length === 0) {
    entry.historyLoaded = true;
    setActiveView(entry.view);
    entry.view.pagination.pendingInitialLoad = true;
    sendWs({ type: 'getHistory', sessionId: nodeSessionId, limit: 100 });
  }
}

export function isPopupOpen() {
  return popupOverlay !== null;
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
    const token = localStorage.getItem('nebflow_token') || '';
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const resp = await fetch(`/api/agents/${encodeURIComponent(agentName)}/model`, { headers });
    if (!resp.ok) return;
    const cfg = await resp.json();
    const el = popupOverlay?.querySelector('#flow-agent-model');
    if (!el) return;
    const current = cfg.current || cfg.preferred || cfg.default || '';
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
      getHiddenRoot().appendChild(entry.container);
      entry.footerEl = null;
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

export function removeStepView(sessionId) {
  const entry = stepViews.get(sessionId);
  if (entry) {
    entry.container.remove();
    stepViews.delete(sessionId);
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
}

// ── Utils ────────────────────────────────────────────────
function esc(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&').replace(/</g, '<').replace(/>/g, '>')
    .replace(/"/g, '"').replace(/'/g, '&#039;');
}
