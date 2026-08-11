// planMode.js — Plan mode inline card for Nebflow.
//
// Design:
// - Plan result is displayed as a centered glassmorphism card inline in the
//   main chat area (not a separate canvas panel).
// - The plan agent shows as a normal sub-agent: its agentStart, agentToolStart,
//   agentDone events flow through normal dispatch so the bg-agent indicator
//   and tool activity are visible. Only agentTextDelta is intercepted to
//   accumulate the plan text for the card.
// - Session-bound: switching sessions removes the card; switching back to a
//   session with a pending plan re-injects it.

import state from './state.js';
import { sendWs } from './ws.js';
import { renderMarkdownWithMath } from './utils.js';
import { findViewBySessionId } from './chatView.js';

// ---------- State ----------
let planText = '';
let planReady = false;

// ---------- Init ----------
export function init() {
  state.onPlanSessionChange = (newSessionId) => {
    // Remove any plan card from DOM (session switch clears chat anyway).
    removeInlineCard();
    // Re-inject if the session being entered has a pending plan.
    if (planReady && state.planSessionId === newSessionId) {
      const view = findViewBySessionId(newSessionId);
      if (view) showPlanContent(view);
    }
  };
}

// ---------- Event handlers ----------

export function onPlanStart(msg) {
  state.planAgentId = msg.agentId;
  state.planSessionId = msg.sessionId;
  planText = '';
  planReady = false;
}

export function onPlanAgentEvent(msg) {
  if (msg.type === 'agentTextDelta') {
    planText += msg.delta;
  }
}

export function onPlanReady(msg, view) {
  planReady = true;
  // Only show if the user is currently on the plan's session.
  if (view && state.activeSessionId === state.planSessionId) {
    showPlanContent(view);
  }
}

export function onPlanEnd(msg) {
  removeInlineCard();
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
  removeInlineCard();
  // planEnd from backend will clear planAgentId / planSessionId.
}

function cancelPlan() {
  if (!state.planSessionId) return;
  sendWs({ type: 'planCancel', sessionId: state.planSessionId });
  planReady = false;
  removeInlineCard();
}

function toggleFeedback() {
  const bar = document.getElementById('plan-action-bar');
  if (!bar) return;
  bar.innerHTML = feedbackBarHTML();
  document.getElementById('plan-feedback-send')?.addEventListener('click', sendFeedback);
  document.getElementById('plan-feedback-cancel')?.addEventListener('click', restoreDefaultBar);
  const ta = document.getElementById('plan-feedback-input');
  if (ta) {
    ta.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendFeedback(); }
      if (e.key === 'Escape') { e.preventDefault(); restoreDefaultBar(); }
    });
    ta.focus();
  }
}

function restoreDefaultBar() {
  const view = findViewBySessionId(state.planSessionId);
  if (view) showPlanContent(view);
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
  removeInlineCard();
  // Card will reappear when next planReady arrives.
}

// ---------- UI: inline plan card + action bar ----------

function showPlanContent(view) {
  if (!view || !view.dom.chat) return;
  removeInlineCard(); // remove any stale card first

  const container = document.createElement('div');
  container.id = 'plan-inline-container';
  container.className = 'plan-inline-container';
  container.innerHTML = `
    <div class="plan-card markdown-body">${renderMarkdownWithMath(planText)}</div>
    <div class="plan-action-bar" id="plan-action-bar">${defaultBarHTML()}</div>
  `;
  view.dom.chat.appendChild(container);
  wireDefaultBar();

  // Scroll so the plan card is visible.
  view.dom.chat.scrollTop = view.dom.chat.scrollHeight;
}

function removeInlineCard() {
  document.getElementById('plan-inline-container')?.remove();
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
