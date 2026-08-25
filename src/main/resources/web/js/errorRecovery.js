// errorRecovery.js — Error-recovery "error family" UI (frozen-error-recovery
// plan §4/§5).
//
// The freeze mechanism is reused for error recovery: a transient LLM/network/
// provider failure parks the agent at a dispatch boundary (a "frozen" state)
// and auto-resumes when the condition clears. The UI must be DISTINCT from the
// time-table schedule freeze (sapphire, quiet wait) — this module renders the
// amber "error family": input-bar tint + reason strip + retry/abandon buttons +
// the escalation decision card (§5.3.2), plus the reason pass-through used by
// sub-agent tiles (UI-7).
//
// Schedule freeze (sapphire) stays in main.js. This module only handles the
// error-family distinction: reason ∈ {llm-transient, network, provider-down,
// restart-recovery}. reason==='schedule' (or absent → old backend default) is
// NOT ours.
//
// Consumed frames (the backend ⑪B lands these; we test with mock frames):
//   frozen        { sessionId, resumeAt, reason, detail?, retryCount?, escalation? }
//   agentFrozen   { sessionId, agentId, resumeAt, reason, ... }
//   errorEscalated{ sessionId, agentId?, reason, retryCount, level, escalateAt }
//   resumed       { sessionId }  (shared with schedule — main.js owns it)

import state from './state.js';
import { t } from './i18n.js';
import { sendWs } from './ws.js';
import { escapeHtml } from './utils.js';
import { findViewBySessionId } from './chatView.js';

// ── Reason metadata ──────────────────────────────────────────────────────
// wire names are the FreezeReason enum cases (kebab) emitted by protocol.scala.
const ERROR_REASONS = ['llm-transient', 'network', 'provider-down', 'restart-recovery'];

/** True when the frozen reason is an error-family reason (not 'schedule'). */
export function isErrorReason(reason) {
  return ERROR_REASONS.includes(reason);
}

/** Normalize an incoming reason: absent/unknown → 'schedule' (old-backend
 *  safe default), so non-error reasons never take the amber path. */
export function normalizeReason(reason) {
  return isErrorReason(reason) ? reason : 'schedule';
}

/** i18n key for the reason phrase (no prefix — caller prefixes "错误恢复中"). */
const REASON_KEYS = {
  'llm-transient': 'chat.errorRecovering.llmTransient',
  'network': 'chat.errorRecovering.network',
  'provider-down': 'chat.errorRecovering.providerDown',
  'restart-recovery': 'chat.errorRecovering.restartRecovery',
};

/** Human label for a reason (already translated, via t()). */
export function reasonText(reason) {
  const key = REASON_KEYS[normalizeReason(reason)];
  return key ? t(key) : '';
}

// ── Icon (dim 2: static warning glyph, no spinning ring) ─────────────────
const ICON_SHAPES = {
  // cloud + exclamation — "LLM service unavailable"
  'llm-transient':
    '<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/>' +
    '<line x1="12" y1="10" x2="12" y2="14"/>' +
    '<line x1="12" y1="16.5" x2="12.01" y2="16.5"/>',
  // circled slash — "network down"
  'network':
    '<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>',
  // server rack — "all providers down"
  'provider-down':
    '<rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/>' +
    '<line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/>',
  // rotate-cw resume arrow — "restart recovery"
  'restart-recovery':
    '<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>',
};

/** 18px warning SVG for a reason. Static shape (pulse via CSS, not rotation). */
export function errorIcon(reason, cls) {
  const key = isErrorReason(reason) ? reason : 'llm-transient';
  const shape = ICON_SHAPES[key] || ICON_SHAPES['llm-transient'];
  return `<svg class="${cls || 'error-icon'}" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${shape}</svg>`;
}

/** Build the strip text: "错误恢复中 · {reason} · 第 n 次重试" (or the no-retry
 *  form when retryCount is absent). Appends " · 等待上级决策" when escalated.
 *  Never contains "已冻结". */
export function buildStripText(info) {
  const reason = reasonText(info.reason);
  const n = Number(info.retryCount);
  const hasRetry = Number.isFinite(n) && n >= 1;
  let base;
  if (hasRetry) {
    const retry = t('chat.errorRecoveringRetryCount', { n });
    base = t('chat.errorRecoveringStrip', { reason, retry });
  } else {
    base = t('chat.errorRecoveringStripNoRetry', { reason });
  }
  if (info.escalation && info.escalation.level) {
    base += ` · ${t('chat.errorEscalatedEscalation')}`;
  }
  return base;
}

// ── Actions (dim 5: retry / abandon buttons) ─────────────────────────────
/** "立即重试" — R3 user-wake, forces the frozen agent to resume (re-dispatch
 *  from its lastDispatch checkpoint). */
export function sendRetry(sid) {
  if (!sid) return;
  sendWs({ type: 'immediateInput', sessionId: sid, content: '' });
}

/** "取消任务" — abandon recovery → terminal settle (reuses cancelAgent). */
export function sendAbandon(sid) {
  if (!sid) return;
  sendWs({ type: 'cancelAgent', sessionId: sid });
}

/** "重启并续跑" — parent/user restarts a frozen child (parentRestart WS
 *  action; backend routes by kind §5.3.3). */
export function sendParentRestart(sid) {
  if (!sid) return;
  sendWs({ type: 'parentRestart', sessionId: sid });
}

// ── Input-bar strip (dims 1-5 for the active/root view) ──────────────────
const STRIP_CLASS = 'error-recovery-strip';

/** Resolve the error strip's host element inside a view's input bar.
 *  Returns the input-wrap (which holds the textarea) or null. */
function stripHost(view) {
  if (!view || !view.dom || !view.dom.inputBar) return null;
  const bar = view.dom.inputBar;
  return bar.querySelector('#input-wrap') || bar;
}

function ensureStrip(view, info) {
  const host = stripHost(view);
  if (!host) return null;
  let strip = host.querySelector('.' + STRIP_CLASS);
  if (!strip) {
    strip = document.createElement('div');
    strip.className = STRIP_CLASS;
    strip.dataset.reason = normalizeReason(info.reason);
    // Insert before the textarea (#input for main, .fa-input for popups).
    const input = host.querySelector('#input, .fa-input');
    if (input && input.parentNode === host) host.insertBefore(strip, input);
    else host.appendChild(strip);
  } else {
    strip.dataset.reason = normalizeReason(info.reason);
  }
  strip.innerHTML =
    `<span class="error-recovery-icon">${errorIcon(info.reason)}</span>` +
    `<span class="error-recovery-text"></span>` +
    `<button class="error-retry-btn" type="button">${escapeHtml(t('chat.errorRetry'))}</button>` +
    `<button class="error-abandon-btn" type="button">${escapeHtml(t('chat.errorAbandon'))}</button>`;
  strip.querySelector('.error-recovery-text').textContent = buildStripText(info);
  const sid = info.sessionId;
  strip.querySelector('.error-retry-btn').addEventListener('click', () => sendRetry(sid));
  strip.querySelector('.error-abandon-btn').addEventListener('click', () => sendAbandon(sid));
  return strip;
}

/** Apply the amber error-family state to a view's input bar: tint + reason
 *  strip + wake placeholder. Called from main.js on frozen(reason≠schedule). */
export function applyErrorFrozen(view, info) {
  const v = view || (info.sessionId ? findViewBySessionIdSafe(info.sessionId) : null);
  if (!v || !v.dom || !v.dom.inputBar) return;
  const bar = v.dom.inputBar;
  // Amber family: add .frozen-error, NOT .frozen (UI-1 mutex).
  bar.classList.remove('frozen');
  bar.classList.add('frozen-error');
  bar.dataset.errorFrozen = 'true';
  ensureStrip(v, info);
  if (v.dom.input) {
    v.dom.input.placeholder = t('chat.errorRecoveringHint');
  }
}

/** Clear the amber error-family state from a view (on resumed / terminal).
 *  Removes the tint + strip, resets the placeholder. Does NOT restore the
 *  mode-appropriate placeholder itself — callers run applyInputModes(). */
export function clearErrorFrozen(sid) {
  const v = findViewBySessionIdSafe(sid);
  if (!v || !v.dom || !v.dom.inputBar) return;
  const bar = v.dom.inputBar;
  bar.classList.remove('frozen-error');
  delete bar.dataset.errorFrozen;
  const host = stripHost(v);
  const strip = host && host.querySelector('.' + STRIP_CLASS);
  if (strip) strip.remove();
  if (v.dom.input) v.dom.input.placeholder = '';
}

// ── Escalation decision card (dim: escalated state, §5.3.2) ──────────────
/** Render the persistent user-level decision card at the top of a session view.
 *  Called on errorEscalated. The card is not auto-dismissed (user is the final
 *  arbiter); "继续等待" closes it and defers (escalateAt is advanced server-side). */
export function renderEscalationCard(msg) {
  const sid = msg.sessionId;
  if (!sid) return;
  const v = findViewBySessionIdSafe(sid) || state.getActiveView && state.getActiveView();
  const chat = (v && v.dom && v.dom.chat) || null;
  if (!chat) return;

  // Remove any previous card for this session (re-render, not duplicate).
  clearEscalationCard(sid);

  const card = document.createElement('div');
  card.className = 'error-escalation-card';
  card.dataset.session = sid;

  const reason = reasonText(msg.reason);
  const n = Number(msg.retryCount);
  const retry = Number.isFinite(n) && n >= 1 ? t('chat.errorRecoveringRetryCount', { n }) : '';
  const levelTxt = t('chat.errorEscalatedLevel', { n: msg.level || 1 });
  const meta = t('chat.errorEscalatedMeta', { reason, retry, level: levelTxt });

  const header = document.createElement('div');
  header.className = 'error-escalation-header';
  header.innerHTML =
    `<span class="error-recovery-icon">${errorIcon(msg.reason, 'error-icon')}</span>` +
    `<span class="error-escalation-title-text"></span>`;
  header.querySelector('.error-escalation-title-text').textContent =
    t('chat.errorEscalatedTitle', { agent: msg.agentId || '' });

  const metaEl = document.createElement('div');
  metaEl.className = 'error-escalation-meta';
  metaEl.textContent = meta;

  // "等待上级决策 · 剩余 Xm" — countdown from escalateAt (ticked once a minute).
  const waitEl = document.createElement('div');
  waitEl.className = 'error-escalation-wait';
  const tickWait = () => {
    const at = Number(msg.escalateAt);
    if (Number.isFinite(at) && at > 0) {
      const rem = Math.max(0, Math.ceil((at - Date.now()) / 60000));
      waitEl.textContent = t('chat.errorWaitRemaining', { m: rem });
      waitEl.style.display = '';
    } else {
      waitEl.style.display = 'none';
      return;
    }
  };
  tickWait();
  const waitInt = setInterval(tickWait, 60000);
  escalationTickers.set(card, waitInt);

  const actions = document.createElement('div');
  actions.className = 'error-escalation-actions';
  const mk = (cls, text, handler) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = cls;
    b.textContent = text;
    b.addEventListener('click', handler);
    return b;
  };
  actions.appendChild(mk('error-restart-btn', t('chat.errorRestartContinue'), () => {
    sendParentRestart(sid);
    clearEscalationCard(sid);
  }));
  actions.appendChild(mk('error-abandon-btn', t('chat.errorAbandon'), () => {
    sendAbandon(sid);
    clearEscalationCard(sid);
  }));
  actions.appendChild(mk('error-wait-btn', t('chat.errorWait'), () => {
    // Keep waiting — close the card; the frozen state (and its strip) remains.
    clearEscalationCard(sid);
  }));

  card.appendChild(header);
  card.appendChild(metaEl);
  card.appendChild(waitEl);
  card.appendChild(actions);

  // Mark as a control so the card's chat-children don't count as a message row.
  chat.insertBefore(card, chat.firstChild);
}

// Ticker handle per escalation card (cleared in clearEscalationCard).
const escalationTickers = new Map();

/** Remove the escalation card for a session (if rendered). */
export function clearEscalationCard(sid) {
  if (!sid) return;
  const q = document.querySelectorAll(`.error-escalation-card[data-session="${CSS.escape(sid)}"]`);
  q.forEach(el => {
    const int = escalationTickers.get(el);
    if (int) { clearInterval(int); escalationTickers.delete(el); }
    el.remove();
  });
}

// ── Sub-agent tile text (UI-7) ───────────────────────────────────────────
/** Short tile label for a sub-agent footer (UI-7): schedule → '' (the caller
 *  renders the sapphire "已冻结 · HH:mm" text), error → 重试中 (actively
 *  retrying) / 等恢复 (waiting for external recovery), escalation → 等待上级决策. */
export function errorTileText(meta) {
  const reason = normalizeReason(meta.freezeReason);
  if (reason === 'schedule') return '';
  if (meta.escalation) return t('chat.errorEscalatedEscalation');
  if (reason === 'llm-transient') return t('chat.errorTileRetry');
  return t('chat.errorTileWaiting');
}

// ── Small helpers ────────────────────────────────────────────────────────
function findViewBySessionIdSafe(sid) {
  return findViewBySessionId(sid);
}
