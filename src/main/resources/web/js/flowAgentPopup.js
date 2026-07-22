// flowAgentPopup.js — Mini session viewer for flow step agents.
//
// Each flowStepId gets its own hidden ChatView instance that renders in
// real-time. When the user clicks a node, the pre-rendered DOM is moved
// into the popup modal — so all history is preserved.

import { ChatView, setActiveView, activeView } from './chatView.js';

// ── Per-step state ────────────────────────────────────────
// flowStepId → { view: ChatView, container: div, meta: {} }
const stepViews = new Map();
let currentStepId = null;
let popupOverlay = null;

export function getStepView(flowStepId) {
  return stepViews.get(flowStepId) || null;
}

// ── CSS ───────────────────────────────────────────────────
const POPUP_CSS = `<style id="flow-agent-popup-css">
.flow-agent-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: var(--overlay-bg, rgba(0,0,0,0.4));
  display: flex; align-items: center; justify-content: center;
  z-index: 320;
  animation: fa-fade-in 0.2s ease;
}
.flow-agent-overlay.in-canvas {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  z-index: 100;
}
@keyframes fa-fade-in { from { opacity: 0; } to { opacity: 1; } }

.flow-agent-modal {
  width: calc(100% - 48px); max-width: 480px; max-height: calc(100% - 48px);
  display: flex; flex-direction: column;
  background: var(--glass-bg, rgba(255,255,255,0.03));
  -webkit-backdrop-filter: blur(30px) saturate(1.15);
  backdrop-filter: blur(30px) saturate(1.15);
  border: 1px solid var(--glass-border, rgba(255,255,255,0.1));
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12);
}
@media (prefers-color-scheme: dark) {
  .flow-agent-modal {
    box-shadow: inset 0 1px 0 0 rgba(255,255,255,0.04), 0 8px 32px rgba(0,0,0,0.35);
  }
}

.flow-agent-header {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--glass-border, rgba(255,255,255,0.08));
  flex-shrink: 0;
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

/* Chat area — reuses chat.css bubble/tool-card styles */
.flow-agent-chat {
  flex: 1; overflow-y: auto;
  padding: 12px 14px;
  scrollbar-color: var(--color-frame-border) transparent;
}
.flow-agent-chat .row { margin-bottom: 8px; }
.flow-agent-chat .bubble.ai {
  font-size: 12px; line-height: 1.6;
  max-width: 100%;
}
.flow-agent-chat .tool-card {
  font-size: 11px;
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

// ── Open popup ────────────────────────────────────────────

export function openStepPopup(flowStepId, nodeLabel, agentName, flowName, nodeSessionId) {
  closeStepPopup();
  currentStepId = flowStepId;

  // Ensure view exists (in case no events arrived yet)
  const entry = ensureStepView(flowStepId);

  popupOverlay = document.createElement('div');
  popupOverlay.className = 'flow-agent-overlay';

  const canvasPanel = document.getElementById('canvas-panel');
  const mountEl = (canvasPanel && canvasPanel.classList.contains('visible')) ? canvasPanel : document.body;
  if (mountEl === canvasPanel) popupOverlay.classList.add('in-canvas');

  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-node">${esc(nodeLabel)}</span>
        <span class="flow-agent-divider">·</span>
        <span class="flow-agent-name">${esc(agentName || entry.meta.agentName || '')}</span>
        <span class="flow-agent-subtitle">${esc(flowName || '')}</span>
        <div class="flow-agent-close" id="flow-agent-close">✕</div>
      </div>
    </div>
  `;

  mountEl.appendChild(popupOverlay);

  // Move the pre-rendered container from hidden root into the modal
  const modal = popupOverlay.querySelector('.flow-agent-modal');
  modal.appendChild(entry.container);

  popupOverlay.addEventListener('click', (e) => {
    if (e.target === popupOverlay || e.target.id === 'flow-agent-close') closeStepPopup();
  });

  // Auto-scroll
  entry.container.addEventListener('scroll', () => {
    const atBottom = entry.container.scrollTop + entry.container.clientHeight >= entry.container.scrollHeight - 40;
    entry.view.stream.scrollSnapped = atBottom;
  });

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
    if (entry) getHiddenRoot().appendChild(entry.container);
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

async function loadSessionHistory(view, sessionId, container) {
  try {
    const resp = await fetch(`/api/sessions/${sessionId}/history`);
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

  // Capture meta from agentStart
  if (msg.type === 'agentStart') {
    entry.meta.agentName = msg.name || '';
    entry.meta.task = msg.taskDescription || '';
  }

  // Set activeView so chat.js rendering targets this view's container
  setActiveView(entry.view);

  // Auto-scroll if popup is showing this step
  if (currentStepId === msg.flowStepId) {
    requestAnimationFrame(() => {
      entry.container.scrollTop = entry.container.scrollHeight;
    });
  }
  return true;
}

// ── Utils ────────────────────────────────────────────────
function esc(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
}
