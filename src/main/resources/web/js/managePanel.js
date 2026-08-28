// managePanel.js — Sub-agent management controls (2026-08-22 user ruling).
//
// User ruling 2026-08-22: sub-agent windows are MANAGEMENT surfaces, not
// conversation surfaces — the message input bar is removed and replaced by
// state display + control buttons mapped onto the backend's AgentControl
// capabilities (WS layer landed 2026-08-22 @179a009e):
//   - stop   → WS {type:'cancelAgent', sessionId} (terminal cancel; result
//              frame cancelAgentResult {ok, sessionId, message?|error?})
//              with graceful fallback to {type:'interrupt'} when the result
//              reports the handler missing (pre-restart instances)
//   - retry  → WS {type:'restartAgent', sessionId, level:'soft'} (soft only —
//              rollback/prune/full mutate session history, never bare-exposed)
//   - stuck  ← WS taskStuck {sessionId, kind, idleSecs, action}
//   - retries← WS taskStuck action=restart count + snapshot retryCount
//   - uptime ← snapshot startedAt (epoch ms, 0=unknown) / agentStart arrival
//   - failed ← snapshot status startsWith('Error') (no WS failed event yet)
//
// Permission matrix (AgentControl spec §4): Delegate/SubTask/Ephemeral are
// operable; Team/Flow/Root are read-only (buttons greyed + tooltip reason).
// Buttons are state-linked — no permanent dead buttons: stop only while the
// agent is processing, retry only on failed/stuck.

import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';
import { showToast } from './modal.js';

/** Kinds the user may stop/restart (AgentControl §4 operable bucket). */
export const OPERABLE_KINDS = new Set(['Delegate', 'SubTask', 'Ephemeral']);

/** Read-only kinds get greyed buttons with a reason tooltip. */
export function managePolicy(kind) {
  if (kind === 'Team') return { operable: false, reason: t('manage.readonlyTeam') };
  if (kind === 'Flow') return { operable: false, reason: t('manage.readonlyFlow') };
  if (kind === 'Root') return { operable: false, reason: t('manage.readonlyTeam') };
  return { operable: true, reason: '' };
}

/** True while the agent is in a processing phase (stop button visible). */
export function isProcessingStatus(status) {
  return status === 'running' || status === 'thinking' || status === 'tool' || status === 'responding';
}

/** True when a restart makes sense (retry button visible).
 *  'failed' comes from the activeAgents snapshot status ("Error(msg)" form —
 *  the backend has no WS failed event for sub-agents yet). */
export function isRetryableStatus(status) {
  return status === 'failed' || status === 'stuck' || status === 'stopped';
}

/** Snapshot status parse: AgentStatus.toString yields "Error(msg)" for the
 *  failed state (msg may contain spaces/parens — prefix check only). */
export function isFailedSnapshotStatus(status) {
  return typeof status === 'string' && status.startsWith('Error');
}

// ── cancelAgent result-driven stop with graceful fallback ─────────────
// The WS cancelAgent handler landed 2026-08-22 (@179a009e). Pre-restart
// instances silently drop unknown frames (handleMessage case _ => IO.unit) —
// no cancelAgentResult ever arrives. So: send cancelAgent, arm a short timer;
// a result frame settles it, timer expiry falls back to interrupt (the
// pre-upgrade semantics) so the stop button never goes dead on old backends.
const CANCEL_FALLBACK_MS = 1500;
const pendingCancelTimers = new Map(); // sessionId → timeout handle
const cancelListeners = new Set();

/** Popup modules register a listener for settled cancel outcomes
 *  (bg and flow popups each watch their own open session). */
export function onCancelResult(fn) { cancelListeners.add(fn); }

/** Feed a cancelAgentResult frame: clear the fallback timer, notify listeners. */
export function noteCancelResult(msg) {
  const timer = pendingCancelTimers.get(msg.sessionId);
  if (timer) { clearTimeout(timer); pendingCancelTimers.delete(msg.sessionId); }
  for (const fn of cancelListeners) fn(msg);
}

// WS result frame for the panel stop action (whitelisted in ws.js TERMINAL).
// ok=false → error toast (stale id / read-only kind); ok=true is silent — the
// footer transition rides the subsequent interrupted/agentEnd event.
onMessage('cancelAgentResult', (msg) => {
  noteCancelResult(msg);
  if (msg && msg.ok === false && msg.error) {
    showToast(t('manage.stopFailed') + ' — ' + msg.error, 'error');
  }
});

/** Relative uptime formatter — shared with the Sub-Agents dropdown rows
 *  (main.js renderBgAgentDropdown, subagents-panel spec 2026-08-25). */
export function fmtUptime(ms) {
  if (ms == null || ms < 0) return '';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + (s % 60) + 's';
  const h = Math.floor(m / 60);
  return h + 'h ' + (m % 60) + 'm';
}

/** Build the right-side management cluster for a popup footer.
 *  Returns { root, stopBtn, retryBtn, uptimeEl, retriesEl }. */
export function buildManageBar() {
  const root = document.createElement('div');
  root.className = 'fa-manage';
  const retriesEl = document.createElement('span');
  retriesEl.className = 'fa-retries';
  retriesEl.style.display = 'none';
  const uptimeEl = document.createElement('span');
  uptimeEl.className = 'fa-uptime';
  uptimeEl.style.display = 'none';
  const stopBtn = document.createElement('button');
  stopBtn.className = 'fa-mgmt-btn fa-mgmt-stop glass-control';
  stopBtn.title = t('manage.stop');
  stopBtn.setAttribute('aria-label', t('manage.stop'));
  stopBtn.innerHTML = '<i data-lucide="square"></i>';
  const retryBtn = document.createElement('button');
  retryBtn.className = 'fa-mgmt-btn fa-mgmt-retry glass-control';
  retryBtn.title = t('manage.retry');
  retryBtn.setAttribute('aria-label', t('manage.retry'));
  retryBtn.innerHTML = '<i data-lucide="rotate-cw"></i>';
  root.append(retriesEl, uptimeEl, stopBtn, retryBtn);
  return { root, stopBtn, retryBtn, uptimeEl, retriesEl };
}

/** Wire stop/retry actions for a session.
 *  stop = cancelAgent (terminal) with interrupt fallback for pre-upgrade
 *  backends (see CANCEL_FALLBACK_MS); retry = restartAgent soft only. */
export function bindManageActions(bar, getSessionId) {
  bar.stopBtn.addEventListener('click', () => {
    const sid = getSessionId();
    if (!sid) return;
    sendWs({ type: 'cancelAgent', sessionId: sid });
    showToast(t('manage.stopSent'), 'info');
    // Fallback: pre-restart instances drop unknown frames silently — if no
    // cancelAgentResult settles this session within the window, send the
    // legacy interrupt so the button never goes dead.
    if (pendingCancelTimers.has(sid)) clearTimeout(pendingCancelTimers.get(sid));
    pendingCancelTimers.set(sid, setTimeout(() => {
      pendingCancelTimers.delete(sid);
      sendWs({ type: 'interrupt', sessionId: sid });
    }, CANCEL_FALLBACK_MS));
  });
  bar.retryBtn.addEventListener('click', () => {
    const sid = getSessionId();
    if (!sid) return;
    sendWs({ type: 'restartAgent', sessionId: sid, level: 'soft' });
    showToast(t('manage.retrySent'), 'info');
  });
}

/** State-linked visibility + permission matrix.
 *  entry.meta: { status, kind, stuck: {idleSecs, action}|null, retries, startedAt } */
export function syncManageControls(bar, meta) {
  if (!bar) return;
  const policy = managePolicy(meta.kind || '');
  const status = meta.status || '';
  const processing = isProcessingStatus(status);
  const retryable = isRetryableStatus(status);

  if (!policy.operable) {
    // Read-only: greyed, disabled, tooltip explains why.
    for (const btn of [bar.stopBtn, bar.retryBtn]) {
      btn.disabled = true;
      btn.classList.add('readonly');
      btn.title = policy.reason;
      btn.style.display = 'flex';
    }
    return;
  }
  // Operable: show only when the action makes sense (no dead buttons).
  bar.stopBtn.disabled = false;
  bar.retryBtn.disabled = false;
  bar.stopBtn.classList.remove('readonly');
  bar.retryBtn.classList.remove('readonly');
  bar.stopBtn.title = t('manage.stop');
  bar.retryBtn.title = t('manage.retry');
  bar.stopBtn.style.display = processing ? 'flex' : 'none';
  bar.retryBtn.style.display = retryable ? 'flex' : 'none';

  // Retries chip (agentRetryStatus count) — visible once > 0.
  if (bar.retriesEl) {
    const n = meta.retries || 0;
    bar.retriesEl.style.display = n > 0 ? '' : 'none';
    bar.retriesEl.textContent = '×' + n;
    bar.retriesEl.title = t('manage.retry') + ' ×' + n;
  }
  // Uptime — from agentStart arrival (frontend) or snapshot startedAt.
  if (bar.uptimeEl) {
    const started = meta.startedAt || null;
    if (started && status !== 'done') {
      bar.uptimeEl.style.display = '';
      bar.uptimeEl.textContent = t('manage.uptime', { time: fmtUptime(Date.now() - started) });
    } else {
      bar.uptimeEl.style.display = 'none';
    }
  }
}
