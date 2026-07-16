// planMode.js — Plan mode canvas panel for Nebflow.
// Uses the shared #canvas-panel infrastructure (canvas.js).
//
// Design:
// - No loading state — canvas only opens when the plan result is ready.
// - Session-bound — closing the session closes the canvas; switching back
//   to a session with a pending plan auto-reopens it.
// - No header; plan content fills the canvas as a card.
// - Floating glassmorphism action bar at the bottom.

import state from './state.js';
import { sendWs } from './ws.js';
import { renderMarkdownWithMath } from './utils.js';
import { openCanvas, closeCanvas, setCanvasContent, showCanvasHeader } from './canvas.js';

// ---------- State ----------
let planText = '';
let planReady = false;

// ---------- Init ----------
export function init() {
  state.onPlanSessionChange = (newSessionId) => {
    // Close canvas when leaving any session.
    showCanvasHeader(true);
    closeCanvas();
    // Auto-open if the session being entered has a pending plan.
    if (planReady && state.planSessionId === newSessionId) {
      showCanvasHeader(false);
      openCanvas();
      showPlanContent();
    }
  };
}

// ---------- Event handlers ----------

export function onPlanStart(msg) {
  state.planAgentId = msg.agentId;
  state.planSessionId = msg.sessionId;
  planText = '';
  planReady = false;
  // Don't open canvas — wait for planReady.
}

export function onPlanAgentEvent(msg) {
  if (msg.type === 'agentTextDelta') {
    planText += msg.delta;
  }
}

export function onPlanReady(msg) {
  planReady = true;
  // Only show if the user is currently on the plan's session.
  if (state.activeSessionId === state.planSessionId) {
    showCanvasHeader(false);
    openCanvas();
    showPlanContent();
  }
}

export function onPlanEnd(msg) {
  showCanvasHeader(true);
  closeCanvas();
  planText = '';
  planReady = false;
  state.planAgentId = null;
  state.planSessionId = null;
}

// ---------- Actions ----------

function approvePlan() {
  if (!state.planSessionId) return;
  sendWs({ type: 'planApprove', sessionId: state.planSessionId });
  planReady = false;
  showCanvasHeader(true);
  closeCanvas();
  // planEnd from backend will clear planAgentId / planSessionId.
}

function cancelPlan() {
  if (!state.planSessionId) return;
  sendWs({ type: 'planCancel', sessionId: state.planSessionId });
  planReady = false;
  showCanvasHeader(true);
  closeCanvas();
}

function toggleFeedback() {
  const bar = document.getElementById('plan-action-bar');
  if (!bar) return;
  bar.innerHTML = feedbackBarHTML();
  document.getElementById('plan-feedback-send')?.addEventListener('click', sendFeedback);
  document.getElementById('plan-feedback-cancel')?.addEventListener('click', showPlanContent);
  const ta = document.getElementById('plan-feedback-input');
  if (ta) {
    ta.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendFeedback(); }
      if (e.key === 'Escape') { e.preventDefault(); showPlanContent(); }
    });
    ta.focus();
  }
}

function sendFeedback() {
  if (!state.planSessionId) return;
  const ta = document.getElementById('plan-feedback-input');
  if (!ta) return;
  const text = ta.value.trim();
  if (!text) return;
  sendWs({ type: 'planFeedback', sessionId: state.planSessionId, text });
  planText = '';
  planReady = false;
  showCanvasHeader(true);
  closeCanvas();
  // Canvas will reopen when next planReady arrives.
}

// ---------- UI: Plan content + action bar ----------

function showPlanContent() {
  setCanvasContent(`
    <div class="plan-card markdown-body">${renderMarkdownWithMath(planText)}</div>
    <div class="plan-action-bar" id="plan-action-bar">${defaultBarHTML()}</div>
  `);
  wireDefaultBar();
}

function defaultBarHTML() {
  return `
    <button class="plan-glass-btn plan-btn-cancel" id="plan-cancel-btn" title="Cancel plan">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      <span>Cancel</span>
    </button>
    <button class="plan-glass-btn plan-btn-feedback" id="plan-feedback-btn">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
      <span>Feedback</span>
    </button>
    <button class="plan-glass-btn plan-btn-approve" id="plan-approve-btn">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
      <span>Approve</span>
    </button>
  `;
}

function feedbackBarHTML() {
  return `
    <textarea class="plan-feedback-input" id="plan-feedback-input" rows="2"
      placeholder="Describe what to change... (Enter to send, Esc to cancel)"></textarea>
    <div class="plan-feedback-actions">
      <button class="plan-glass-btn plan-btn-cancel" id="plan-feedback-cancel">Back</button>
      <button class="plan-glass-btn plan-btn-approve" id="plan-feedback-send">Send</button>
    </div>
  `;
}

function wireDefaultBar() {
  document.getElementById('plan-approve-btn')?.addEventListener('click', approvePlan);
  document.getElementById('plan-cancel-btn')?.addEventListener('click', cancelPlan);
  document.getElementById('plan-feedback-btn')?.addEventListener('click', toggleFeedback);
}
