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

import state, { LS_SESSIONS_KEY } from './state.js';
import { t } from './i18n.js';
import { sendWs } from './ws.js';
import { escapeHtml } from './utils.js';
import { findViewBySessionId } from './chatView.js';

// ── Reason metadata ──────────────────────────────────────────────────────
// wire names are the FreezeReason enum cases (kebab) emitted by protocol.scala.
// 'loop' (R3, 2026-09-10): LoopGuard L2 park. The backend enters it through the
// SAME error-family entry point (AgentActor enterErrorFrozen(..., FreezeReason
// .Loop, LoopFreezeResumeMs=365d)), so it belongs to this family. It used to be
// absent here ⇒ normalizeReason() folded it into 'schedule' ⇒ the loop freeze
// rendered as the sapphire TIME-TABLE freeze ("已冻结 · <+1y clock>", disabled
// composer, "跳过本次" button — itself a backend no-op for loop) and never got
// the retry/abandon affordances, i.e. the "why did it stop" was unanswerable
// and the family's only exits were invisible.
const ERROR_REASONS = ['llm-transient', 'network', 'provider-down', 'restart-recovery', 'loop'];

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
  'loop': 'chat.errorRecovering.loop',
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
  // rotate-ccw repeat cycle — "the same action keeps repeating"
  'loop':
    '<path d="m17 2 4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/>' +
    '<path d="m7 22-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/>',
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
  if (normalizeReason(info.reason) === 'loop') {
    // R3: the loop park never auto-recovers (resumeAt = +365d) — the generic
    // "错误恢复中" prefix would be a lie. Dedicated line: what happened + the
    // two real exits (the buttons sit right next to it).
    base = t('chat.loopFrozenStrip');
  } else if (hasRetry) {
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
/** Fresh clientMessageId — byte-identical in shape to the input.js send paths.
 *  A NEW id per click is mandatory, not cosmetic: the frozen agent's wake
 *  branch dedups on it (AgentActor.checkDuplicate) and a repeated id is
 *  silently dropped — the 2nd click of a reused id would be a no-op. */
function newClientMessageId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

/** Last user-authored message text of a session — the payload the retry
 *  re-sends. DOM first (it renders the live conversation AND the backend
 *  history restore), then the per-session localStorage cache written by
 *  persistence.saveMsg (covers sessions whose view is not mounted).
 *  Injected (blue) bubbles are `row user` + `bubble injected`, so the
 *  `.bubble.user` selector skips them by construction; attachment-only rows
 *  carry `att-bubble` and are skipped too (their text is a `[file: …]` tag).
 *  Within a bubble the payload is the LAST child (plain sends = the text div;
 *  /ask + skill bubbles = a label div followed by the question/argument div) —
 *  reading the bubble itself would prepend the label. */
function lastUserMessageText(sid) {
  const v = findViewBySessionIdSafe(sid);
  const chat = v && v.dom && v.dom.chat;
  if (chat) {
    const bubbles = chat.querySelectorAll('.row.user .bubble.user:not(.att-bubble)');
    for (let i = bubbles.length - 1; i >= 0; i--) {
      const txt = ((bubbles[i].lastElementChild || bubbles[i]).textContent || '').trim();
      if (txt) return txt;
    }
  }
  try {
    const all = JSON.parse(localStorage.getItem(LS_SESSIONS_KEY) || '{}');
    const arr = all[sid] || [];
    for (let i = arr.length - 1; i >= 0; i--) {
      const m = arr[i];
      if (m && m.type === 'user' && !m.injected && typeof m.text === 'string' && m.text.trim()) {
        return m.text.trim();
      }
    }
  } catch (e) { /* cache unreadable — fall through to the empty return */ }
  return '';
}

/** "立即重试" — user wake: re-send the last user message as a normal browser
 *  send frame (typeless), which is what actually resumes the frozen agent.
 *
 *  R2 (2026-09-10). The previous form
 *      sendWs({ type: 'immediateInput', sessionId: sid, content: '' })
 *  was a structural no-op, twice over:
 *   (a) empty content — the immediateInput route funnels into handleUserText,
 *       whose `if sessionId.nonEmpty && content.nonEmpty` guard drops it with
 *       a "dropped EMPTY content" warn and dispatches nothing;
 *   (b) even with text it could never WAKE a frozen agent: the immediateInput
 *       route reads only sessionId + content and never forwards
 *       clientMessageId, and the frozen agent's only text exit is
 *       `case UserInput(...) if clientMessageId.isDefined` ⇒ wake; without an
 *       id the same message takes the queue-without-waking branch and the
 *       freeze is untouched.
 *  AgentCommand.Retry is not an alternative either: it has a handler
 *  (AgentActor ~:3788) but NO WS route, and it re-dispatches as Gated — a loop
 *  freeze would simply re-freeze. The typeless browser frame (content +
 *  clientMessageId + sessionId) is the only client shape that reaches
 *  AgentCommand.UserInput(content, None, clientMessageId, …) and thus the wake
 *  branch. Chat width rides along (as in input.js) so the resumed turn keeps
 *  the same wrap width.
 *
 *  Trade-off (inherent to re-sending): the agent's context gains a second copy
 *  of the last user message. That is the point — the wake re-states the user's
 *  request so the parked turn can be retried. */
export function sendRetry(sid) {
  if (!sid) return;
  const text = lastUserMessageText(sid);
  if (!text) {
    // No user text anywhere (no rendered row, no cache entry) — there is
    // nothing to re-send, and an empty frame is exactly the no-op being fixed.
    // Say so instead of failing silently.
    // N-1 (2026-09-10): this is now defense-in-depth only. ensureStrip probes
    // the same predicate at render time and renders the button `disabled` with
    // a visible reason, so the user-facing path never reaches this branch; it
    // survives for the strip-less callers (direct calls, a future entry point).
    console.warn('[errorRecovery] retry: no user message text available for session', sid);
    return;
  }
  const v = findViewBySessionIdSafe(sid);
  const chatWidth = (v && v.dom && v.dom.chat && v.dom.chat.clientWidth) || 0;
  sendWs({ content: text, clientMessageId: newClientMessageId(), sessionId: sid, chatWidth });
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
  // N-1 (2026-09-10): the retry button is only actionable when there IS a
  // user-authored text to re-send — sendRetry's payload source is
  // lastUserMessageText(), so an injected-only conversation (scheduled task /
  // Mail / delegate driven) has nothing to replay. Until now the button stayed
  // visible and clickable in that case while the click did nothing but a
  // console.warn — the same "visible, clickable, no feedback" defect class this
  // batch exists to remove (and the strip text promised 重试). Probe the SAME
  // function the click path uses, so the state can never disagree with it, and
  // turn the no-op into a disabled control that says why.
  //
  // The reason rides ON the button (label swap) rather than in a sibling hint
  // span on purpose: the strip row is a fixed-width flex row (icon + reason +
  // 2 buttons), and a third text item costs the reason line its width — the
  // measured first cut dropped it from 13 to ~3 visible chars, and giving the
  // hint its own wrapped row grew the strip from 1 to 3 rows. Label swap is the
  // only shape that explains the disabled state at zero layout cost.
  const retryBtn = strip.querySelector('.error-retry-btn');
  if (!lastUserMessageText(sid)) {
    retryBtn.disabled = true;
    retryBtn.setAttribute('aria-disabled', 'true');
    retryBtn.dataset.unavailable = 'no-user-message';   // DOM assertion contract
    retryBtn.textContent = t('chat.errorRetryNoText');
    retryBtn.title = t('chat.errorRetryNoTextTitle');
  }
  retryBtn.addEventListener('click', () => sendRetry(sid));
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
  // Loop park is manual-only (no auto-recovery) — "等恢复" would promise a
  // resume that never comes; label the cause instead.
  if (reason === 'loop') return t('chat.errorRecovering.loop');
  return t('chat.errorTileWaiting');
}

// ── Small helpers ────────────────────────────────────────────────────────
function findViewBySessionIdSafe(sid) {
  return findViewBySessionId(sid);
}
