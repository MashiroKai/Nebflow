// planMode.js — Plan mode canvas panel for Nebflow.
// Uses the shared #canvas-panel infrastructure (canvas.js) instead of a
// separate overlay. Plan content is injected into #canvas-content.
// Does NOT show streaming process — only renders when planReady arrives.

import state from './state.js';
import { sendWs } from './ws.js';
import { renderMarkdownWithMath } from './utils.js';
import { openCanvas, closeCanvas, setCanvasContent } from './canvas.js';

// ---------- State ----------
let planText = '';

// ---------- Init ----------
export function init() {
  // No DOM creation — #canvas-panel already exists in index.html.
  // Close button override is set up in onPlanStart, restored in onPlanEnd.
}

// ---------- Event handlers ----------

/** Called when planStart event arrives — open the canvas with loading state. */
export function onPlanStart(msg) {
  state.planAgentId = msg.agentId;
  state.planSessionId = msg.sessionId;
  planText = '';
  openCanvas('Plan');
  overrideCloseButton();
  showLoading();
}

/** Handle a plan agent streaming event — accumulate text silently. */
export function onPlanAgentEvent(msg) {
  if (msg.type === 'agentTextDelta') {
    planText += msg.delta;
  }
}

/** Called when planReady event arrives — show plan markdown + controls. */
export function onPlanReady(msg) {
  showPlanContent();
}

/** Called when planEnd event arrives — close the canvas. */
export function onPlanEnd(msg) {
  restoreCloseButton();
  closeCanvas();
  state.planAgentId = null;
  state.planSessionId = null;
}

// ---------- Actions ----------

function approvePlan() {
  if (!state.planSessionId) return;
  sendWs({ type: 'planApprove', sessionId: state.planSessionId });
  restoreCloseButton();
  closeCanvas();
}

function cancelPlan() {
  if (!state.planSessionId) return;
  sendWs({ type: 'planCancel', sessionId: state.planSessionId });
  restoreCloseButton();
  closeCanvas();
}

function sendFeedback() {
  if (!state.planSessionId) return;
  const textarea = document.getElementById('plan-feedback-input');
  if (!textarea) return;
  const text = textarea.value.trim();
  if (!text) return;
  sendWs({ type: 'planFeedback', sessionId: state.planSessionId, text });
  textarea.value = '';
  planText = '';
  showLoading();
}

// ---------- Close button override ----------

function overrideCloseButton() {
  const closeBtn = document.getElementById('canvas-close-btn');
  if (closeBtn) closeBtn.addEventListener('click', cancelPlan);
}

function restoreCloseButton() {
  const closeBtn = document.getElementById('canvas-close-btn');
  if (closeBtn) closeBtn.removeEventListener('click', cancelPlan);
}

// ---------- UI helpers ----------

function showLoading() {
  setCanvasContent(`
    <div class="plan-loading">
      <div class="plan-spinner"></div>
      <span>Plan agent is analyzing...</span>
    </div>
  `);
}

function showPlanContent() {
  // Render the accumulated plan text as markdown, plus footer controls.
  const html = `
    <div class="plan-content markdown-body">${renderMarkdownWithMath(planText)}</div>
    <div class="plan-footer">
      <textarea class="plan-feedback-input" id="plan-feedback-input" rows="2"
        placeholder="Send feedback to adjust the plan..."></textarea>
      <div class="plan-buttons">
        <button class="plan-btn plan-btn-cancel" id="plan-cancel-btn">Cancel</button>
        <button class="plan-btn plan-btn-feedback" id="plan-feedback-btn">Send Feedback</button>
        <button class="plan-btn plan-btn-approve" id="plan-approve-btn">Approve</button>
      </div>
    </div>
  `;
  setCanvasContent(html);

  // Wire up buttons
  document.getElementById('plan-approve-btn')?.addEventListener('click', approvePlan);
  document.getElementById('plan-cancel-btn')?.addEventListener('click', cancelPlan);
  document.getElementById('plan-feedback-btn')?.addEventListener('click', sendFeedback);

  const textarea = document.getElementById('plan-feedback-input');
  if (textarea) {
    textarea.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendFeedback();
      }
    });
    textarea.focus();
  }
}
