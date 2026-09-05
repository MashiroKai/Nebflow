/**
 * updateCheck.js — app update check chain owner (09-05 五项裁定⑤).
 *
 * One module owns everything update-related on the frontend:
 *   • WS handlers: updateCheckResult / updateStarted / updateCompleted
 *     (moved here from main.js so the auto path and the manual path share
 *     one routing table — no second listener can double-echo).
 *   • Silent auto checks: one shot 60s after boot, then every 6h.
 *   • The settings-btn green dot (activityBar.setUpdateDot) lit when a check
 *     finds a newer version; cleared when the user opens settings (see
 *     activityBar.bindSettingsButton) or an update completes successfully.
 *
 * Design decisions (documented per task requirement ⑤):
 *   • 60s boot delay — the first minute is the startup thundering herd (WS
 *     handshake, serverConfig echo, plugin catalog, session history). An
 *     update check is in no hurry; deferring it avoids competing with the
 *     boot window and guarantees `state.connected` has settled.
 *   • 6h interval — release cadence is far slower than hourly; 6h finds a new
 *     build the same day at negligible cost.
 *   • Page hidden → keep running. Browsers already throttle background
 *     setInterval to ≥1/min, which is irrelevant at a 6h cadence; a
 *     visibilitychange pause/resume state machine would add complexity for
 *     zero user-visible difference (the check is silent and cheap).
 *   • Failure/offline → silent. No toast, no modal, no dot; a failed cycle
 *     simply yields and the next one retries (requirement f, 零打扰).
 *   • Manual/auto disambiguation: the About button flags its check via
 *     notifyManualUpdateCheck() BEFORE the WS roundtrip. A flagged response
 *     is echoed into the About section and clears the flag; an unflagged
 *     (auto) response updates state + dot and only writes the About section
 *     if it is currently in the DOM — it can never stomp an in-flight manual
 *     echo, because auto cycles are skipped while the flag is up.
 */
import state from './state.js';
import { t } from './i18n.js';
import { onMessage, sendWs } from './ws.js';

const FIRST_CHECK_DELAY_MS = 60_000;
const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;
// Safety valve: if a manual check's response never arrives (offline, gateway
// restart), the flag must not block auto checks forever.
const MANUAL_FLAG_TTL_MS = 30_000;

let manualCheckPending = false;
let initialized = false;

/**
 * The update-available dot on #settings-btn. Lives here (not activityBar.js)
 * because the dot is a feature of the update-check chain: this module lights
 * it on a found update and clears it on completion; activityBar.js imports
 * this same function for its clear-on-open behavior. Keeping it here also
 * keeps the static import graph acyclic (activityBar -> sidebar already).
 */
export function setUpdateDot(show) {
  const btn = document.getElementById('settings-btn');
  if (!btn) return;
  let dot = btn.querySelector('.settings-update-dot');
  if (show && !dot) {
    dot = document.createElement('span');
    dot.className = 'settings-update-dot';
    btn.appendChild(dot);
  } else if (!show && dot) {
    dot.remove();
  }
}

export function initUpdateCheck() {
  if (initialized) return;
  initialized = true;

  onMessage('updateCheckResult', (msg) => {
    const wasManual = manualCheckPending;
    manualCheckPending = false;
    const statusEl = document.getElementById('update-status');
    const actionEl = document.getElementById('update-action');

    if (msg.error) {
      // Manual check → surface the error in About; auto check → silent.
      if (wasManual && statusEl) statusEl.textContent = t('settings.updateError');
      return;
    }

    // Record the outcome in state (single source of truth for the dot and
    // for the About echo after any settings re-render).
    state.updateAvailable = !!msg.hasUpdate;
    state.latestVersion = msg.latestVersion || '';
    setUpdateDot(state.updateAvailable);

    // Both paths reuse the same About display; with the settings panel
    // closed the elements simply don't exist and the result stays silent.
    if (!statusEl) return;
    if (msg.hasUpdate) {
      statusEl.textContent = t('settings.updateAvailable', { version: msg.latestVersion });
      if (actionEl) actionEl.style.display = 'block';
    } else {
      statusEl.textContent = t('settings.upToDate');
      if (actionEl) actionEl.style.display = 'none';
    }
  });

  onMessage('updateStarted', () => {
    const statusEl = document.getElementById('update-status');
    if (statusEl) statusEl.textContent = t('settings.updating');
  });

  onMessage('updateCompleted', (msg) => {
    const btn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-do-update'));
    const statusEl = document.getElementById('update-status');
    if (btn) { btn.textContent = t('settings.checkUpdate'); btn.disabled = false; }
    if (msg.success) {
      state.updateAvailable = false;
      state.latestVersion = '';
      setUpdateDot(false);
      if (statusEl) statusEl.textContent = '✓ ' + t('settings.upToDate');
      const actionEl = document.getElementById('update-action');
      if (actionEl) actionEl.style.display = 'none';
    } else if (statusEl) {
      statusEl.textContent = '✗ ' + (msg.error || t('settings.updateError'));
    }
  });

  const autoCheck = () => {
    // Skip while disconnected (response could never come) or while a manual
    // check is in flight (never interleave with its About echo).
    if (!state.connected || manualCheckPending) return;
    sendWs({ type: 'checkUpdate' });
  };

  setTimeout(autoCheck, FIRST_CHECK_DELAY_MS);
  setInterval(autoCheck, CHECK_INTERVAL_MS);
}

/**
 * Flag a user-initiated check (sidebar About button). Must be called BEFORE
 * sendWs({type:'checkUpdate'}) so the response is routed as manual.
 */
export function notifyManualUpdateCheck() {
  manualCheckPending = true;
  setTimeout(() => { manualCheckPending = false; }, MANUAL_FLAG_TTL_MS);
}
