// flowAgentPopup.js — Read-only mini session viewer for flow step agents.
//
// Buffers agent WS events (text deltas, tool calls) per flowStepId.
// When the user clicks a flow node, opens a dropbox-style modal showing
// the agent's conversation in real-time.

// ── Event buffer: flowStepId → conversation items ────────
const stepBuffers = new Map(); // flowStepId → { items: [], meta: {} }

function getBuffer(flowStepId) {
  if (!stepBuffers.has(flowStepId)) {
    stepBuffers.set(flowStepId, { items: [], meta: {} });
  }
  return stepBuffers.get(flowStepId);
}

// ── Buffer WS events ─────────────────────────────────────

export function onAgentEvent(msg) {
  const fsid = msg.flowStepId;
  if (!fsid) return;
  const buf = getBuffer(fsid);

  switch (msg.type) {
    case 'agentStart': {
      buf.meta.agentName = msg.name || '';
      buf.meta.task = msg.taskDescription || '';
      buf.meta.startTime = Date.now();
      break;
    }
    case 'agentTextDelta': {
      // Append to last text item or create new one
      const last = buf.items[buf.items.length - 1];
      if (last && last.type === 'text') {
        last.text += msg.delta || '';
      } else {
        buf.items.push({ type: 'text', text: msg.delta || '' });
      }
      // Live-update if popup is showing this step
      if (currentStepId === fsid) appendTextDelta(msg.delta || '');
      break;
    }
    case 'agentToolStart': {
      buf.items.push({ type: 'tool', label: msg.label || '', status: 'running', summary: '', content: '', isError: false });
      if (currentStepId === fsid) appendToolPending(msg.label || '');
      break;
    }
    case 'agentToolEnd': {
      // Update the last running tool
      const runningTool = [...buf.items].reverse().find(it => it.type === 'tool' && it.status === 'running');
      if (runningTool) {
        runningTool.status = 'done';
        runningTool.summary = msg.summary || '';
        runningTool.content = msg.content || msg.frontendContent || '';
        runningTool.isError = !!msg.isError;
        runningTool.input = msg.input;
      }
      if (currentStepId === fsid) updateToolResult(msg);
      break;
    }
    case 'agentDone': {
      buf.meta.done = true;
      buf.meta.endTime = Date.now();
      if (currentStepId === fsid) markDone();
      break;
    }
  }
}

// ── Popup rendering ──────────────────────────────────────

let currentStepId = null;
let popupOverlay = null;

export function openStepPopup(flowStepId, nodeLabel, agentName, flowName) {
  currentStepId = flowStepId;
  const buf = getBuffer(flowStepId);

  // Remove existing popup
  closeStepPopup();

  // Create overlay — relative to canvas panel, not viewport
  popupOverlay = document.createElement('div');
  popupOverlay.className = 'flow-agent-overlay';

  // Append to canvas panel if open, otherwise body
  const canvasPanel = document.getElementById('canvas-panel');
  const mountEl = (canvasPanel && canvasPanel.classList.contains('visible')) ? canvasPanel : document.body;
  if (mountEl === canvasPanel) popupOverlay.classList.add('in-canvas');

  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <div class="flow-agent-title">
          <span class="flow-agent-node">${escapeHtml(nodeLabel)}</span>
          <span class="flow-agent-divider">·</span>
          <span class="flow-agent-name">${escapeHtml(agentName || buf.meta.agentName || '')}</span>
        </div>
        <div class="flow-agent-subtitle">${escapeHtml(flowName || '')}</div>
        <div class="flow-agent-close" id="flow-agent-close">✕</div>
      </div>
      <div class="flow-agent-body" id="flow-agent-body">
        ${buf.meta.task ? `<div class="flow-agent-task">${escapeHtml(buf.meta.task)}</div>` : ''}
      </div>
    </div>
  `;

  mountEl.appendChild(popupOverlay);

  // Close on overlay click or close button
  popupOverlay.addEventListener('click', (e) => {
    if (e.target === popupOverlay || e.target.id === 'flow-agent-close') closeStepPopup();
  });

  // Render existing items
  const body = popupOverlay.querySelector('#flow-agent-body');
  buf.items.forEach(item => {
    if (item.type === 'text') {
      appendTextDelta(item.text);
    } else if (item.type === 'tool') {
      appendToolPending(item.label);
      if (item.status === 'done') {
        updateToolResult({ label: item.label, summary: item.summary, content: item.content, isError: item.isError });
      }
    }
  });

  if (buf.meta.done) markDone();
}

export function closeStepPopup() {
  if (popupOverlay) {
    popupOverlay.remove();
    popupOverlay = null;
  }
  currentStepId = null;
}

// ── Incremental render helpers ───────────────────────────

function appendTextDelta(delta) {
  if (!popupOverlay || !delta) return;
  const body = popupOverlay.querySelector('#flow-agent-body');
  if (!body) return;

  let textEl = body.querySelector('.flow-agent-text:last-child');
  if (!textEl) {
    textEl = document.createElement('div');
    textEl.className = 'flow-agent-text';
    body.appendChild(textEl);
  }
  // For simplicity, append raw text. Full markdown rendering would require
  // re-rendering on each delta — deferred for now.
  textEl.textContent += delta;
  body.scrollTop = body.scrollHeight;
}

function appendToolPending(label) {
  if (!popupOverlay) return;
  const body = popupOverlay.querySelector('#flow-agent-body');
  if (!body) return;

  const el = document.createElement('div');
  el.className = 'flow-agent-tool pending';
  el.dataset.toolLabel = label;
  el.innerHTML = `
    <div class="flow-agent-tool-icon pending"></div>
    <span class="flow-agent-tool-label">${escapeHtml(label)}</span>
  `;
  body.appendChild(el);
  body.scrollTop = body.scrollHeight;
}

function updateToolResult(msg) {
  if (!popupOverlay) return;
  const body = popupOverlay.querySelector('#flow-agent-body');
  if (!body) return;

  // Find the pending tool matching this label (last one)
  const tools = body.querySelectorAll('.flow-agent-tool.pending');
  let el = null;
  for (const t of tools) {
    if (t.dataset.toolLabel === msg.label) { el = t; break; }
  }
  if (!el && tools.length > 0) el = tools[tools.length - 1];
  if (!el) return;

  const isError = !!msg.isError;
  el.classList.remove('pending');
  el.classList.add(isError ? 'error' : 'done');

  const icon = el.querySelector('.flow-agent-tool-icon');
  icon.className = `flow-agent-tool-icon ${isError ? 'error' : 'done'}`;

  // Expandable content
  const summary = msg.summary || '';
  const content = msg.content || '';
  const label = el.querySelector('.flow-agent-tool-label');
  label.textContent = summary || msg.label;

  if (content && content.trim()) {
    el.style.cursor = 'pointer';
    el.dataset.expanded = '0';
    el.addEventListener('click', () => {
      const isExpanded = el.dataset.expanded === '1';
      if (isExpanded) {
        el.querySelector('.flow-agent-tool-content')?.remove();
        el.dataset.expanded = '0';
      } else {
        const contentEl = document.createElement('div');
        contentEl.className = 'flow-agent-tool-content';
        contentEl.textContent = content.slice(0, 2000);
        el.appendChild(contentEl);
        el.dataset.expanded = '1';
      }
    });
  }
}

function markDone() {
  if (!popupOverlay) return;
  const modal = popupOverlay.querySelector('.flow-agent-modal');
  if (modal) modal.classList.add('agent-done');
}

// ── Utils ────────────────────────────────────────────────

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
}

// ── CSS (injected once) ──────────────────────────────────

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
  z-index: 100;
}
@keyframes fa-fade-in { from { opacity: 0; } to { opacity: 1; } }

.flow-agent-modal {
  width: 560px; max-width: 90vw; max-height: 72vh;
  display: flex; flex-direction: column;
  background: var(--glass-bg, rgba(255,255,255,0.03));
  -webkit-backdrop-filter: blur(30px) saturate(1.15);
  backdrop-filter: blur(30px) saturate(1.15);
  border: 1px solid var(--glass-border, rgba(255,255,255,0.1));
  border-radius: 14px;
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
  padding: 12px 16px;
  border-bottom: 1px solid var(--glass-border, rgba(255,255,255,0.08));
}
.flow-agent-title {
  display: flex; align-items: center; gap: 6px;
}
.flow-agent-node {
  font: 600 13px -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text);
}
.flow-agent-divider {
  color: var(--color-text-muted); opacity: 0.5;
}
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

.flow-agent-body {
  flex: 1; overflow-y: auto;
  padding: 14px 16px;
  display: flex; flex-direction: column; gap: 8px;
}

.flow-agent-task {
  font: 400 11px -apple-system, sans-serif;
  color: var(--color-text-muted);
  background: var(--glass-bg, rgba(255,255,255,0.02));
  border-radius: 8px; padding: 8px 10px;
  border-left: 2px solid var(--color-primary, #6366f1);
}

.flow-agent-text {
  font: 400 12px/1.6 -apple-system, BlinkMacSystemFont, sans-serif;
  color: var(--color-text);
  white-space: pre-wrap; word-break: break-word;
}

.flow-agent-tool {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--glass-bg, rgba(255,255,255,0.02));
  border: 1px solid var(--glass-border, rgba(255,255,255,0.06));
  font: 500 11px -apple-system, sans-serif;
  transition: background 0.15s;
}
.flow-agent-tool:hover { background: var(--glass-bg, rgba(255,255,255,0.04)); }

.flow-agent-tool-icon {
  width: 14px; height: 14px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
}
.flow-agent-tool-icon.pending {
  border: 1.5px solid var(--color-text-muted);
  border-top-color: transparent;
  border-radius: 50%;
  animation: fa-spin 0.8s linear infinite;
}
.flow-agent-tool-icon.done {
  background: none;
}
.flow-agent-tool-icon.done::before {
  content: '✓'; font-size: 12px; font-weight: 700;
  color: var(--color-success, #3dd68c);
}
.flow-agent-tool-icon.error::before {
  content: '✕'; font-size: 12px; font-weight: 700;
  color: var(--color-error, #e5484d);
}
@keyframes fa-spin { to { transform: rotate(360deg); } }

.flow-agent-tool-label {
  color: var(--color-text-muted);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.flow-agent-tool.done .flow-agent-tool-label { color: var(--color-text); }

.flow-agent-tool-content {
  font: 400 10px/1.5 ui-monospace, SFMono-Regular, monospace;
  color: var(--color-text-muted);
  margin-top: 6px; padding: 6px 8px;
  background: var(--color-bg, rgba(0,0,0,0.03));
  border-radius: 6px;
  white-space: pre-wrap; word-break: break-word;
  max-height: 200px; overflow-y: auto;
}

.flow-agent-modal.agent-done .flow-agent-header {
  position: relative;
}
.flow-agent-modal.agent-done .flow-agent-header::after {
  content: ''; position: absolute; bottom: -1px; left: 16px; right: 16px;
  height: 1px; background: var(--color-success, #3dd68c); opacity: 0.3;
}
</style>`;

// Inject CSS once
if (!document.getElementById('flow-agent-popup-css')) {
  document.head.insertAdjacentHTML('beforeend', POPUP_CSS);
}
