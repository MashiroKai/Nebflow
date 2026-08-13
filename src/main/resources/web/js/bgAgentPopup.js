// bgAgentPopup.js — Live message viewer for background sub-agents.
//
// When an agent uses the Delegate/SubTask tool, sub-agent events arrive with
// nodeSessionId = "delegate-<agentName>-<uuid>" / "subtask-<uuid>" (backend protocol). This
// module intercepts those events in ws.js (via setBgAgentStepInterceptor)
// and renders them into a popup ChatView — the same pattern as flowAgentPopup.js.
//
// The user opens the popup by clicking the bg-agent dropdown entry.

import { ChatView, setActiveView, activeView, chatViews } from './chatView.js';
import { sendWs, onMessage, setBgAgentStepInterceptor } from './ws.js';
import { restoreFromBackendHistory } from './persistence.js';
import { isBgAgentId } from './utils.js';
import state from './state.js';

// ── Per-sub-agent state ────────────────────────────────────
// nodeSessionId → { view: ChatView, container: div, meta: {}, historyLoaded: bool }
const stepViews = new Map();
let currentStepId = null;
let popupOverlay = null;
let popupResizeObs = null;

// ── CSS (shared with flowAgentPopup — same modal style) ───
// The CSS is injected by flowAgentPopup.js at module load time.
// Both modules are always loaded together since they're imported by other
// modules, so the shared class names are always available.

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

  const container = document.createElement('div');
  container.className = 'flow-agent-chat';
  getHiddenRoot().appendChild(container);

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
  const view = new ChatView('bgagent-' + sessionId, fakeDom);
  view.mounted = true;
  view.sessionId = sessionId;
  // Hidden until the popup opens — ws.js gates DOM rendering while false.
  view.visible = false;

  const entry = { view, container, meta: { agentName: '', task: '', status: '' }, historyLoaded: false };
  stepViews.set(sessionId, entry);
  enforceStepViewCap();
  return entry;
}

function resetCardWidths(container) {
  const wraps = container.querySelectorAll('.html-card-wrap');
  wraps.forEach(w => { w.style.width = ''; });
}

// ── Open popup ────────────────────────────────────────────

export function openStepPopup(nodeSessionId, agentName, taskDescription) {
  closeStepPopup();
  currentStepId = nodeSessionId;

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

  // Mount on document.body for bg-agent popups (not inside a flow card)
  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-name">${esc(agentName || entry.meta.agentName || 'Sub-agent')}</span>
        <span class="flow-agent-subtitle">${esc(taskDescription || entry.meta.task || '')}</span>
        <span class="flow-agent-model" id="bgagent-model"></span>
        <span class="flow-agent-ctx" id="bgagent-ctx"></span>
        <div class="flow-agent-close" id="bgagent-close">✕</div>
      </div>
      <div class="flow-agent-footer" id="bgagent-footer">
        <span class="fa-status-dot"></span>
        <span class="fa-task">${esc(entry.meta.task || 'Session')}</span>
      </div>
    </div>
  `;

  // Async-fetch agent model info for header badge
  if (agentName) {
    fetchAgentModelBadge(agentName);
  }

  document.body.appendChild(popupOverlay);

  const modal = popupOverlay.querySelector('.flow-agent-modal');

  const footer = popupOverlay.querySelector('#bgagent-footer');
  modal.insertBefore(entry.container, footer);
  entry.footerEl = footer;

  updateFooterStatus(entry);

  // ── Drag: mousedown on header moves the modal ──
  const header = popupOverlay.querySelector('.flow-agent-header');
  if (header) {
    header.addEventListener('mousedown', (e) => {
      if (e.target.id === 'bgagent-close') return;
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
    if (e.target === popupOverlay || e.target.id === 'bgagent-close') closeStepPopup();
  });

  entry.container.addEventListener('scroll', () => {
    const atBottom = entry.container.scrollTop + entry.container.clientHeight >= entry.container.scrollHeight - 40;
    entry.view.stream.scrollSnapped = atBottom;
  });

  requestAnimationFrame(() => {
    entry.container.scrollTop = entry.container.scrollHeight;
  });
  entry.view.stream.scrollSnapped = true;

  popupResizeObs = new ResizeObserver(() => {
    resetCardWidths(entry.container);
  });
  popupResizeObs.observe(modal);

  // Load session history from backend
  if (nodeSessionId && !entry.historyLoaded && entry.container.children.length === 0) {
    entry.historyLoaded = true;
    setActiveView(entry.view);
    entry.view.pagination.pendingInitialLoad = true;
    sendWs({ type: 'getHistory', sessionId: nodeSessionId, limit: 100 });
  }
}

export function closeStepPopup() {
  if (!popupOverlay) return;
  if (currentStepId) {
    const entry = stepViews.get(currentStepId);
    if (entry) {
      entry.view.visible = false; // hidden — ws.js gates DOM rendering again
      getHiddenRoot().appendChild(entry.container);
      entry.footerEl = null;
      if (entry.meta.status === 'done') cleanupBgAgentView(currentStepId);
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

/** Simple LRU — evict oldest views (Map insertion order) beyond the cap.
 *  Never evicts the currently open view. */
const STEP_VIEW_LRU_CAP = 20;
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

// ── Context usage ring ───────────────────────────────────

function fmtTokens(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return Math.round(n / 1000) + 'k';
  return String(n);
}

function updatePopupCtxRing() {
  if (!popupOverlay || !currentStepId) return;
  const el = popupOverlay.querySelector('#bgagent-ctx');
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

onMessage('usageUpdate', () => { if (popupOverlay) updatePopupCtxRing(); });
onMessage('done', () => { if (popupOverlay) updatePopupCtxRing(); });

// ── WS event interception ────────────────────────────────

export function interceptBgAgentStep(msg) {
  // "delegate-"/"subtask-" prefix is backend protocol (DelegateTool/SubTaskTool session naming).
  if (!msg.nodeSessionId || !isBgAgentId(msg.nodeSessionId)) return false;
  const entry = ensureStepView(msg.nodeSessionId);

  if (msg.type === 'agentStart') {
    entry.meta.agentName = msg.name || '';
    entry.meta.task = msg.taskDescription || '';
    entry.meta.status = 'running';
  } else if (msg.type === 'agentDone' || msg.type === 'agentEnd') {
    entry.meta.status = 'done';
  }

  setActiveView(entry.view);

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

setBgAgentStepInterceptor(interceptBgAgentStep);

// ── historyPage handler ───────────────────────────────────

export function handleBgAgentHistory(msg) {
  const entry = stepViews.get(msg.sessionId);
  if (!entry) return false;

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

function updateFooterStatus(entry) {
  if (!entry.footerEl) return;
  const status = entry.meta.status || '';
  entry.footerEl.classList.remove('running', 'done', 'failed');
  if (status) entry.footerEl.classList.add(status);
  const taskEl = entry.footerEl.querySelector('.fa-task');
  if (taskEl) {
    const label = entry.meta.task
      ? entry.meta.task
      : status === 'running' ? 'Running...'
      : status === 'done' ? 'Done'
      : status === 'failed' ? 'Failed'
      : 'Session';
    taskEl.textContent = label;
  }
}

// ── Cleanup when a sub-agent session ends ─────────────────
// Called from main.js agentDone handler when a background sub-agent finishes.
export function cleanupBgAgentView(nodeSessionId) {
  // Keep the view for a few seconds so the user can read the output,
  // then remove it.
  setTimeout(() => {
    // Never destroy the view while the user is looking at it.
    if (currentStepId === nodeSessionId && popupOverlay) return;
    removeStepView(nodeSessionId);
  }, 5000);
}

// ── Utils ────────────────────────────────────────────────
function esc(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&').replace(/</g, '<').replace(/>/g, '>')
    .replace(/"/g, '"').replace(/'/g, '&#039;');
}

/** Fetch agent model config and render a badge in the popup header. */
async function fetchAgentModelBadge(agentName) {
  try {
    const token = localStorage.getItem('nebflow_token') || '';
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const resp = await fetch(`/api/agents/${encodeURIComponent(agentName)}/model`, { headers });
    if (!resp.ok) return;
    const cfg = await resp.json();
    const el = popupOverlay?.querySelector('#bgagent-model');
    if (!el) return;
    const current = cfg.preferred || cfg.current || cfg.default || '';
    if (!current) { el.innerHTML = ''; return; }
    const isFallback = cfg.preferred && current !== cfg.preferred;
    el.innerHTML = isFallback
      ? `<span class="flow-agent-model-badge">${esc(current)}</span>`
      : `<span class="flow-agent-subtitle">${esc(current)}</span>`;
  } catch (e) { /* non-critical */ }
}
