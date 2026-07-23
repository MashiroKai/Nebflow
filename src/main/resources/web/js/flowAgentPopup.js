// flowAgentPopup.js — Mini session viewer for flow step agents.
//
// Each flowStepId gets its own hidden ChatView instance that renders in
// real-time. When the user clicks a node, the pre-rendered DOM is moved
// into the popup modal — so all history is preserved.
//
// The popup is mounted INSIDE the flow card (not on document.body) so it
// is positioned and sized relative to the card, not the viewport.

import { ChatView, setActiveView, activeView } from './chatView.js';

// ── Per-step state ────────────────────────────────────────
// flowStepId → { view: ChatView, container: div, meta: {} }
const stepViews = new Map();
let currentStepId = null;
let popupOverlay = null;
let popupResizeObs = null;

export function getStepView(flowStepId) {
  return stepViews.get(flowStepId) || null;
}

// ── CSS ───────────────────────────────────────────────────
const POPUP_CSS = `<style id="flow-agent-popup-css">
/* Overlay fills the flow card (position:absolute inside .flow-card).
   No background dim — the modal's own glass effect is enough. */
.flow-agent-overlay {
  position: absolute; top: 0; left: 0; right: 0; bottom: 0;
  display: flex; align-items: center; justify-content: center;
  z-index: 50;
  animation: fa-fade-in 0.2s ease;
}
@keyframes fa-fade-in { from { opacity: 0; } to { opacity: 1; } }

/* Modal — absolutely positioned so it can be dragged within the flow card.
   Initial position is centered; drag bar (header) moves it. */
.flow-agent-modal {
  position: absolute;
  left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  width: calc(100% - 24px);
  height: calc(100% - 24px);
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
  display: flex; align-items: center; gap: 8px;
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
.flow-agent-node {
  font: 600 13px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text);
}
.flow-agent-divider { color: var(--color-text-muted); opacity: 0.5; }
.flow-agent-name {
  font: 500 12px -apple-system, sans-serif;
  color: var(--color-primary, #6366f1);
}
.flow-agent-subtitle {
  font: 400 11px -apple-system, sans-serif;
  color: var(--color-text-muted);
  margin-right: auto; margin-left: 4px;
}
.flow-agent-close {
  cursor: pointer; font-size: 16px; line-height: 1;
  opacity: 0.5; transition: opacity 0.15s;
  padding: 0 4px; color: var(--color-text-muted);
}
.flow-agent-close:hover { opacity: 1; }

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

// ── Ensure a ChatView exists for a flowStepId ─────────────

function ensureStepView(flowStepId) {
  if (stepViews.has(flowStepId)) return stepViews.get(flowStepId);

  // Create hidden DOM container
  const container = document.createElement('div');
  container.className = 'flow-agent-chat';
  getHiddenRoot().appendChild(container);

  // Create ChatView pointing to this container
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
  const view = new ChatView('flow-' + flowStepId, fakeDom);
  view.mounted = true;
  view.sessionId = '';

  const entry = { view, container, meta: { agentName: '', task: '' } };
  stepViews.set(flowStepId, entry);
  return entry;
}

// ── Reset card widths so they re-flow to match the new container width ──
// .html-card-wrap uses grow-only inline width (set by cardRegistry.js).
// When the popup resizes (narrower), we must clear the inline width so
// it falls back to CSS width:100%, allowing cards to shrink and re-wrap.
function resetCardWidths(container) {
  const wraps = container.querySelectorAll('.html-card-wrap');
  wraps.forEach(w => { w.style.width = ''; });
}

// ── Open popup ────────────────────────────────────────────

export function openStepPopup(flowStepId, nodeLabel, agentName, flowName, nodeSessionId) {
  closeStepPopup();
  currentStepId = flowStepId;

  // Ensure view exists (in case no events arrived yet)
  const entry = ensureStepView(flowStepId);

  popupOverlay = document.createElement('div');
  popupOverlay.className = 'flow-agent-overlay';

  // Mount INSIDE the flow card so the popup is positioned and sized
  // relative to the card, not the viewport. The flow card has
  // overflow:hidden + border-radius, so the popup is clipped to its bounds.
  const flowCard = document.querySelector('.flow-card');
  const mountEl = flowCard || document.body;

  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-node">${esc(nodeLabel)}</span>
        <span class="flow-agent-divider">·</span>
        <span class="flow-agent-name">${esc(agentName || entry.meta.agentName || '')}</span>
        <span class="flow-agent-subtitle">${esc(flowName || '')}</span>
        <div class="flow-agent-close" id="flow-agent-close">✕</div>
      </div>
      <div class="flow-agent-footer" id="flow-agent-footer">
        <span class="fa-status-dot"></span>
        <span class="fa-task">${esc(entry.meta.task || 'Session')}</span>
      </div>
    </div>
  `;

  mountEl.appendChild(popupOverlay);

  // Move the pre-rendered container from hidden root into the modal,
  // inserting it BEFORE the footer so the layout is header / chat / footer.
  const modal = popupOverlay.querySelector('.flow-agent-modal');
  const footer = popupOverlay.querySelector('#flow-agent-footer');
  modal.insertBefore(entry.container, footer);
  entry.footerEl = footer;

  // Sync footer with any meta captured before opening
  updateFooterStatus(entry);

  // ── Drag: mousedown on header moves the modal within the flow card ──
  const header = popupOverlay.querySelector('.flow-agent-header');
  if (header) {
    header.addEventListener('mousedown', (e) => {
      // Ignore drag when clicking the close button
      if (e.target.id === 'flow-agent-close') return;
      e.preventDefault();
      const overlayRect = popupOverlay.getBoundingClientRect();
      const modalRect = modal.getBoundingClientRect();
      // Compute initial offset of mouse from modal's top-left
      const startX = e.clientX - modalRect.left;
      const startY = e.clientY - modalRect.top;

      const onMove = (ev) => {
        // New position relative to overlay
        let newLeft = ev.clientX - overlayRect.left - startX;
        let newTop = ev.clientY - overlayRect.top - startY;
        // Clamp within overlay bounds
        newLeft = Math.max(0, Math.min(newLeft, overlayRect.width - modalRect.width));
        newTop = Math.max(0, Math.min(newTop, overlayRect.height - modalRect.height));
        // Switch from centered transform to explicit position
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

  // Observe modal size changes — when the flow card is resized (column
  // drag, window resize), reset card widths so iframes re-flow.
  popupResizeObs = new ResizeObserver(() => {
    resetCardWidths(entry.container);
  });
  popupResizeObs.observe(modal);

  // Load session history from backend if we have a nodeSessionId
  // and the container is empty (no real-time events were captured)
  if (nodeSessionId && entry.container.children.length === 0) {
    loadSessionHistory(entry.view, nodeSessionId, entry.container);
  }
}

export function closeStepPopup() {
  if (!popupOverlay) return;
  // Move container back to hidden root
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

export function removeStepView(flowStepId) {
  const entry = stepViews.get(flowStepId);
  if (entry) {
    entry.container.remove();
    stepViews.delete(flowStepId);
  }
}

// ── Load session history from backend ─────────────────────
// Fetches UI messages via REST API and renders them into the ChatView.

function getAuthToken() {
  return localStorage.getItem('nebflow_token') || '';
}

async function loadSessionHistory(view, sessionId, container) {
  try {
    // The REST API requires a Bearer token (or ?token=). The WS layer stores
    // it in localStorage.nebflow_token; send it as a header so the popup can
    // load history even when opened directly (not via a ?token= URL).
    const headers = {};
    const tok = getAuthToken();
    if (tok) headers['Authorization'] = `Bearer ${tok}`;
    const resp = await fetch(`/api/sessions/${sessionId}/history`, { headers });
    if (!resp.ok) return;
    const data = await resp.json();
    const messages = data.messages || [];
    if (messages.length === 0) return;

    // Import rendering functions dynamically to avoid circular deps
    const { renderUserBubble, renderTool } = await import('./chat.js');
    const { setActiveView: setAV } = await import('./chatView.js');
    const { renderMarkdownWithMath } = await import('./utils.js');

    setAV(view);

    for (const msg of messages) {
      if (msg.type === 'user') {
        renderUserBubble(msg.text || '', null, msg.timestamp);
      } else if (msg.type === 'ai') {
        // Render AI message as a bubble
        const row = document.createElement('div');
        row.className = 'row';
        const bubble = document.createElement('div');
        bubble.className = 'bubble ai';
        bubble.innerHTML = renderMarkdownWithMath(msg.text || '');
        row.appendChild(bubble);
        container.appendChild(row);
      } else if (msg.type === 'tool') {
        // Render tool result
        if (msg.label && msg.content) {
          renderTool(msg.label, msg.summary || '', msg.content, msg.isError || false, msg.input, sessionId);
        }
      }
    }
    container.scrollTop = container.scrollHeight;
  } catch (e) {
    console.warn('[flowAgentPopup] Failed to load session history:', e);
  }
}

// ── WS event interception ────────────────────────────────
// Called from ws.js BEFORE handler dispatch.
// Ensures a hidden ChatView exists, then sets activeView to it.

export function interceptFlowStep(msg) {
  if (!msg.flowStepId) return false;
  const entry = ensureStepView(msg.flowStepId);

  // Capture meta + lifecycle status from key events. These events are patched
  // with flowStepId by the backend's routeWsSend, so they reach us directly.
  if (msg.type === 'agentStart') {
    entry.meta.agentName = msg.name || '';
    entry.meta.task = msg.taskDescription || '';
    entry.meta.status = 'running';
  } else if (msg.type === 'agentDone' || msg.type === 'agentEnd') {
    entry.meta.status = 'done';
  }

  // Set activeView so chat.js rendering targets this view's container
  setActiveView(entry.view);

  // Auto-scroll + footer sync if popup is showing this step
  if (currentStepId === msg.flowStepId) {
    requestAnimationFrame(() => {
      entry.container.scrollTop = entry.container.scrollHeight;
    });
    updateFooterStatus(entry);
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
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
}
