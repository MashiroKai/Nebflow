// planMode.js — Plan mode canvas panel for Nebflow
// Displays plan agent output on a right-side panel with approve/feedback/cancel controls.

import state from './state.js';
import { sendWs } from './ws.js';
import { renderMarkdownWithMath, escapeHtml } from './utils.js';

// ---------- State ----------
let canvasEl = null;
let contentEl = null;
let toolsEl = null;
let footerEl = null;
let feedbackTextarea = null;
let planText = '';
let toolBlocks = [];

// ---------- Init ----------
export function init() {
  // Create canvas element dynamically (avoids modifying index.html)
  canvasEl = document.createElement('div');
  canvasEl.id = 'plan-canvas';
  canvasEl.className = 'plan-canvas';
  canvasEl.innerHTML = `
    <div class="plan-canvas-header">
      <div class="plan-canvas-title">
        <i data-lucide="clipboard-list"></i>
        <span>Plan Mode</span>
      </div>
      <button class="plan-canvas-close" id="plan-close-btn" title="Cancel plan">
        <i data-lucide="x"></i>
      </button>
    </div>
    <div class="plan-canvas-body">
      <div class="plan-canvas-tools" id="plan-tools"></div>
      <div class="plan-canvas-content" id="plan-content"></div>
    </div>
    <div class="plan-canvas-footer" id="plan-footer">
      <div class="plan-canvas-waiting">Plan agent is analyzing...</div>
      <div class="plan-canvas-controls" style="display:none">
        <textarea class="plan-feedback-input" id="plan-feedback-input" rows="2" placeholder="Send feedback to adjust the plan..."></textarea>
        <div class="plan-canvas-buttons">
          <button class="plan-btn plan-btn-feedback" id="plan-feedback-btn">Send Feedback</button>
          <button class="plan-btn plan-btn-cancel" id="plan-cancel-btn">Cancel</button>
          <button class="plan-btn plan-btn-approve" id="plan-approve-btn">Approve</button>
        </div>
      </div>
    </div>
  `;
  document.body.appendChild(canvasEl);

  contentEl = canvasEl.querySelector('#plan-content');
  toolsEl = canvasEl.querySelector('#plan-tools');
  footerEl = canvasEl.querySelector('#plan-footer');
  feedbackTextarea = canvasEl.querySelector('#plan-feedback-input');

  // Wire up controls
  canvasEl.querySelector('#plan-approve-btn').addEventListener('click', approvePlan);
  canvasEl.querySelector('#plan-cancel-btn').addEventListener('click', cancelPlan);
  canvasEl.querySelector('#plan-close-btn').addEventListener('click', cancelPlan);
  canvasEl.querySelector('#plan-feedback-btn').addEventListener('click', sendFeedback);
  feedbackTextarea.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendFeedback();
    }
  });

  if (typeof lucide !== 'undefined') lucide.createIcons();
}

// ---------- Event handlers ----------

/** Called when planStart event arrives — open the canvas. */
export function onPlanStart(msg) {
  state.planAgentId = msg.agentId;
  state.planSessionId = msg.sessionId;
  planText = '';
  toolBlocks = [];
  if (contentEl) contentEl.innerHTML = '';
  if (toolsEl) toolsEl.innerHTML = '';
  showWaiting();
  openCanvas();
}

/** Handle a plan agent streaming event (routed from ws.js). */
export function onPlanAgentEvent(msg) {
  switch (msg.type) {
    case 'agentTextDelta':
      planText += msg.delta;
      renderPlanContent();
      break;
    case 'agentToolStart':
      addToolBlock(msg.label);
      break;
    case 'agentToolEnd':
      updateToolBlock(msg.label, msg.summary);
      break;
    case 'agentThinking':
      // Could show a thinking indicator
      break;
    case 'agentDone':
      // Turn ended — planReady should arrive separately
      break;
  }
}

/** Called when planReady event arrives — show approve/feedback/cancel. */
export function onPlanReady(msg) {
  showControls();
}

/** Called when planEnd event arrives — close the canvas. */
export function onPlanEnd(msg) {
  closeCanvas();
  state.planAgentId = null;
  state.planSessionId = null;
}

// ---------- Actions ----------

function approvePlan() {
  if (!state.planSessionId) return;
  sendWs({ type: 'planApprove', sessionId: state.planSessionId });
  closeCanvas();
}

function cancelPlan() {
  if (!state.planSessionId) return;
  sendWs({ type: 'planCancel', sessionId: state.planSessionId });
  closeCanvas();
}

function sendFeedback() {
  if (!state.planSessionId || !feedbackTextarea) return;
  const text = feedbackTextarea.value.trim();
  if (!text) return;
  sendWs({ type: 'planFeedback', sessionId: state.planSessionId, text });
  feedbackTextarea.value = '';
  showWaiting();
}

// ---------- UI helpers ----------

function openCanvas() {
  if (canvasEl) {
    canvasEl.classList.add('open');
  }
}

function closeCanvas() {
  if (canvasEl) {
    canvasEl.classList.remove('open');
  }
}

function showWaiting() {
  if (!footerEl) return;
  footerEl.querySelector('.plan-canvas-waiting').style.display = '';
  footerEl.querySelector('.plan-canvas-controls').style.display = 'none';
}

function showControls() {
  if (!footerEl) return;
  footerEl.querySelector('.plan-canvas-waiting').style.display = 'none';
  footerEl.querySelector('.plan-canvas-controls').style.display = '';
  if (feedbackTextarea) feedbackTextarea.focus();
}

function renderPlanContent() {
  if (!contentEl) return;
  // Render as markdown
  contentEl.innerHTML = renderMarkdownWithMath(planText);
  // Auto-scroll to bottom
  contentEl.scrollTop = contentEl.scrollHeight;
}

function addToolBlock(label) {
  if (!toolsEl) return;
  const block = document.createElement('div');
  block.className = 'plan-tool-item active';
  block.innerHTML = `<span class="plan-tool-icon"><i data-lucide="loader-2"></i></span><span class="plan-tool-label">${escapeHtml(label)}</span>`;
  toolsEl.appendChild(block);
  toolBlocks.push({ label, el: block });
  if (typeof lucide !== 'undefined') lucide.createIcons();
  // Auto-scroll
  const body = canvasEl.querySelector('.plan-canvas-body');
  if (body) body.scrollTop = body.scrollHeight;
}

function updateToolBlock(label, summary) {
  const block = toolBlocks.find(b => b.label === label && b.el.classList.contains('active'));
  if (block) {
    block.el.classList.remove('active');
    block.el.classList.add('done');
    block.el.querySelector('.plan-tool-icon').innerHTML = '<i data-lucide="check"></i>';
    if (summary) {
      const sumEl = document.createElement('span');
      sumEl.className = 'plan-tool-summary';
      sumEl.textContent = summary;
      block.el.appendChild(sumEl);
    }
    if (typeof lucide !== 'undefined') lucide.createIcons();
  }
}
