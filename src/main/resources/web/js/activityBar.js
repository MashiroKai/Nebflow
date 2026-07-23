// activityBar.js — Vertical icon strip to the left of #sidebar.
//
// Layout (top → bottom):
//   • Avatar — the NebLink login entry. Tap when logged out → opens
//     nebflow.space/connect to pair. Tap when logged in → opens the Settings
//     panel's NebLink section (where devices are managed, same as before).
//   • (spacer)
//   • Settings.
//
// Device management (list, rename, cross-device messaging) lives in the Settings
// panel's NebLink section — this bar is intentionally minimal: avatar + login
// entry only.
//
// The bar is an independent glass card (see nav.css #activity-bar). It is NOT
// part of the panelDragger 3-column layout — order:-1 keeps it leftmost, and it
// stays visible when the sidebar is collapsed.

import { openSettingsPanel, isSettingsPanelActive } from './sidebar.js';
import { fetchNeblinkStatus, getNeblinkState } from './neblink.js';

let initialized = false;
let statusPollTimer = null;

export function initActivityBar() {
  if (initialized) return;
  initialized = true;

  bindSettingsButton();
  bindAvatar();

  // Refresh NebLink state now and periodically (only while the page is visible)
  // so the avatar reflects logged-in / pairing state.
  refresh();
  statusPollTimer = setInterval(() => { if (!document.hidden) refresh(); }, 10000);

  observeSettingsPanel();

  if (typeof lucide !== 'undefined') lucide.createIcons();
}

// ── Settings ─────────────────────────────────────────────
function bindSettingsButton() {
  const btn = document.getElementById('settings-btn');
  if (!btn) return;
  btn.addEventListener('click', () => {
    // Toggle back to sessions when already on settings.
    if (isSettingsPanelActive()) {
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
      document.getElementById('panel-sessions')?.classList.add('active');
    } else {
      openSettingsPanel();
    }
  });
}

/** Mirror the settings panel's active state onto the button highlight. */
function observeSettingsPanel() {
  const btn = document.getElementById('settings-btn');
  const panel = document.getElementById('panel-settings');
  if (!btn || !panel) return;
  const sync = () => btn.classList.toggle('active', panel.classList.contains('active'));
  sync();
  new MutationObserver(sync).observe(panel, { attributes: true, attributeFilter: ['class'] });
}

// ── Avatar / login ───────────────────────────────────────
function bindAvatar() {
  const avatar = document.getElementById('activity-avatar');
  if (!avatar) return;
  avatar.addEventListener('click', () => {
    const st = getNeblinkState();
    if (st.pairing) return; // pairing in progress — ignore
    // The avatar is the NebLink login entry: always go to the web login page.
    // Device management (after login) lives in the Settings panel.
    const origin = window.location.origin;
    window.open(`https://nebflow.space/connect?redirect=${encodeURIComponent(origin)}`, '_blank');
  });
}

// ── State refresh → avatar styling ───────────────────────
async function refresh() {
  await fetchNeblinkStatus();
  renderAvatar();
}

function renderAvatar() {
  const avatar = document.getElementById('activity-avatar');
  if (!avatar) return;
  const st = getNeblinkState();
  const loggedIn = !!(st.device?.deviceId);
  const avatarUrl = st.device?.avatarUrl || '';
  const logoEl = avatar.querySelector('.activity-avatar-logo');
  const photoEl = avatar.querySelector('.activity-avatar-photo');
  const letterEl = avatar.querySelector('.activity-avatar-letter');

  // Logged in WITH an account avatar → show the photo.
  // Logged in WITHOUT an avatar (no account data yet) → fall back to the logo.
  // Logged out → show the logo.
  const showPhoto = loggedIn && avatarUrl;
  if (photoEl) {
    photoEl.hidden = !showPhoto;
    if (showPhoto && photoEl.src !== avatarUrl) photoEl.src = avatarUrl;
  }
  if (logoEl) logoEl.hidden = showPhoto;
  if (letterEl) letterEl.hidden = true; // account avatar replaces the letter

  // State styling: paired (logged in) / pairing / logged out.
  avatar.classList.toggle('paired', loggedIn);
  avatar.classList.toggle('pairing', !!st.pairing);
  avatar.title = st.pairing
    ? 'Pairing…'
    : (loggedIn ? 'NebLink connected' : 'Click to log in to NebLink');
}
