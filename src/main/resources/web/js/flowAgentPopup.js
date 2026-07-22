// flowAgentPopup.js — Mini session viewer for flow step agents.
//
// Creates a real ChatView instance pointing to a DOM container inside the
// popup modal. ws.js routes flowStepId-tagged events to this view, so all
// existing rendering (streaming text, tool cards, markdown) works as-is.

import { ChatView, setActiveView, activeView } from './chatView.js';

// ── Registry: flowStepId → popup ChatView ─────────────────
const popupViews = new Map(); // flowStepId → ChatView
let currentStepId = null;
let popupOverlay = null;

export function getPopupView(flowStepId) {
  return popupViews.get(flowStepId) || null;
}

export function isPopupOpen(flowStepId) {
  return currentStepId === flowStepId && popupOverlay !== null;
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
/* When inside canvas panel, scope overlay to that panel only */
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
</style>`;

// Inject CSS once
if (!document.getElementById('flow-agent-popup-css')) {
  document.head.insertAdjacentHTML('beforeend', POPUP_CSS);
}

// ── Open popup ────────────────────────────────────────────

export function openStepPopup(flowStepId, nodeLabel, agentName, flowName) {
  // Remove existing popup
  closeStepPopup();
  currentStepId = flowStepId;

  // Create overlay
  popupOverlay = document.createElement('div');
  popupOverlay.className = 'flow-agent-overlay';

  // Mount inside canvas-panel if visible, otherwise body
  const canvasPanel = document.getElementById('canvas-panel');
  const mountEl = (canvasPanel && canvasPanel.classList.contains('visible')) ? canvasPanel : document.body;
  if (mountEl === canvasPanel) popupOverlay.classList.add('in-canvas');

  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-node">${esc(nodeLabel)}</span>
        <span class="flow-agent-divider">·</span>
        <span class="flow-agent-name">${esc(agentName || '')}</span>
        <span class="flow-agent-subtitle">${esc(flowName || '')}</span>
        <div class="flow-agent-close" id="flow-agent-close">✕</div>
      </div>
      <div class="flow-agent-chat" id="flow-agent-chat-${esc(flowStepId)}"></div>
    </div>
  `;

  mountEl.appendChild(popupOverlay);

  // Close handlers
  popupOverlay.addEventListener('click', (e) => {
    if (e.target === popupOverlay || e.target.id === 'flow-agent-close') closeStepPopup();
  });

  // Create a ChatView targeting the popup's chat container.
  // Minimal DOM refs — only `chat` is needed for rendering. Others are
  // stubbed to prevent errors when rendering code touches them.
  const chatEl = popupOverlay.querySelector('.flow-agent-chat');
  const fakeDom = {
    chat: chatEl,
    input: null, sendBtn: null, stopBtn: null, attachBtn: null,
    statusWrap: null, statusText: null, lottieSpinnerEl: null,
    attPreview: null, slashDropdown: null, queueBar: null,
    voiceBtn: null, voiceOverlay: null, voiceText: null,
    headerModelInfoEl: null, bgIndicatorEl: null, bgCountEl: null,
    bgDropdownEl: null, bgDropdownListEl: null,
    delegateIndicatorEl: null, delegateDropdownEl: null, delegateDropdownListEl: null,
    sessionNameEl: null,
  };
  const view = new ChatView('flow-popup-' + flowStepId, fakeDom);
  view.mounted = true;
  // Use the parent session's ID so ws.js filter passes these events through.
  // The view won't conflict with the primary view because ws.js routes by
  // flowStepId match first (see intercept below).
  view.sessionId = window.Nebflow?.activeSessionId || '';
  popupViews.set(flowStepId, view);

  // Auto-scroll
  chatEl.addEventListener('scroll', () => {
    const atBottom = chatEl.scrollTop + chatEl.clientHeight >= chatEl.scrollHeight - 40;
    view.stream.scrollSnapped = atBottom;
  });
}

export function closeStepPopup() {
  if (popupOverlay) {
    popupOverlay.remove();
    popupOverlay = null;
  }
  currentStepId = null;
  // Keep popupViews entries so reopening shows history.
  // They're cleaned up when the flow completes.
}

export function removePopupView(flowStepId) {
  popupViews.delete(flowStepId);
}

// ── WS event interception ────────────────────────────────
// Called from ws.js BEFORE handler dispatch.
// If the message has flowStepId and a popup is open for it,
// set activeView to the popup's ChatView so rendering targets the popup.

export function interceptFlowStep(msg) {
  if (!msg.flowStepId) return false;
  const view = popupViews.get(msg.flowStepId);
  if (!view || !popupOverlay) return false;
  // Redirect rendering to popup view
  setActiveView(view);
  // Auto-scroll
  const chatEl = view.dom.chat;
  if (chatEl && view.stream.scrollSnapped) {
    requestAnimationFrame(() => { chatEl.scrollTop = chatEl.scrollHeight; });
  }
  return true;
}

// ── Utils ────────────────────────────────────────────────
function esc(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
}
