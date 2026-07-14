// planMode.js — Plan mode canvas panel for Nebflow
// Displays plan agent's final output as a markdown card with approve/feedback/cancel.
// Does NOT show streaming process — only renders when the plan agent's turn ends.

import state from './state.js';
import { sendWs } from './ws.js';
import { renderMarkdownWithMath, escapeHtml } from './utils.js';

// ---------- State ----------
let canvasEl = null;
let loadingEl = null;
let contentEl = null;
let footerEl = null;
let feedbackTextarea = null;
let planText = '';

// ---------- Init ----------
export function init() {
  canvasEl = document.createElement('div');
  canvasEl.id = 'plan-canvas';
  canvasEl.className = 'plan-canvas';
  canvasEl.innerHTML = `
    <div class="plan-canvas-header">
      <div class="plan-canvas-title">
        <i data-lucide="clipboard-list"></i>
        <span>Plan</span>
      </div>
      <button class="plan-canvas-close" id="plan-close-btn" title="Cancel plan">
        <i data-lucide="x"></i>
      </button>
    </div>
    <div class="plan-canvas-body">
      <div class="plan-canvas-loading" id="plan-loading">
        <div class="plan-canvas-spinner"></div>
        <span>Plan agent is analyzing...</span>
      </div>
      <div class="plan-canvas-content markdown-body" id="plan-content" style="display:none"></div>
    </div>
    <div class="plan-canvas-footer" id="plan-footer">
      <div class="plan-canvas-controls" style="display:none">
        <textarea class="plan-feedback-input" id="plan-feedback-input" rows="2" placeholder="Send feedback to adjust the plan..."></textarea>
        <div class="plan-canvas-buttons">
          <button class="plan-btn plan-btn-cancel" id="plan-cancel-btn">Cancel</button>
          <button class="plan-btn plan-btn-feedback" id="plan-feedback-btn">Send Feedback</button>
          <button class="plan-btn plan-btn-approve" id="plan-approve-btn">Approve</button>
        </div>
      </div>
    </div>
  `;
  document.body.appendChild(canvasEl);

  loadingEl = canvasEl.querySelector('#plan-loading');
  contentEl = canvasEl.querySelector('#plan-content');
  footerEl = canvasEl.querySelector('#plan-footer');
  feedbackTextarea = canvasEl.querySelector('#plan-feedback-input');

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
  showLoading();
  openCanvas();
}

/** Handle a plan agent streaming event — accumulate text silently. */
export function onPlanAgentEvent(msg) {
  // Silently accumulate text; don't render until planReady
  if (msg.type === 'agentTextDelta') {
    planText += msg.delta;
  }
}

/** Called when planReady event arrives — show final markdown + controls. */
export function onPlanReady(msg) {
  showPlanContent();
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
  // Back to loading while plan agent revises
  planText = '';
  showLoading();
}

// ---------- UI helpers ----------

function openCanvas() {
  if (canvasEl) canvasEl.classList.add('open');
}

function closeCanvas() {
  if (canvasEl) canvasEl.classList.remove('open');
}

function showLoading() {
  if (loadingEl) loadingEl.style.display = 'flex';
  if (contentEl) contentEl.style.display = 'none';
  const controls = footerEl?.querySelector('.plan-canvas-controls');
  if (controls) controls.style.display = 'none';
}

function showPlanContent() {
  // Render the accumulated plan text as markdown
  if (contentEl) {
    contentEl.innerHTML = renderMarkdownWithMath(planText);
    contentEl.style.display = 'block';
  }
  if (loadingEl) loadingEl.style.display = 'none';
  // Show approve/feedback/cancel controls
  const controls = footerEl?.querySelector('.plan-canvas-controls');
  if (controls) controls.style.display = '';
  if (feedbackTextarea) feedbackTextarea.focus();
}
