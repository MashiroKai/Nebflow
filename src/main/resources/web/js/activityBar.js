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
  avatar.addEventListener('click', (e) => {
    const st = getNeblinkState();
    if (st.pairing) return; // pairing in progress — ignore
    if (st.device?.deviceId) {
      // Logged in → show profile panel
      showProfilePanel(st, avatar);
    } else {
      // Not logged in → redirect to nebflow.space login
      const origin = window.location.origin;
      const token = localStorage.getItem('nebflow_token') || '';
      const redirectUrl = token
        ? `${origin}/?token=${encodeURIComponent(token)}`
        : origin;
      window.open(`https://nebflow.space/connect?redirect=${encodeURIComponent(redirectUrl)}`, '_blank');
    }
  });
}

/** Floating profile panel showing device info and peers, with logout button. */
function showProfilePanel(st, avatarEl) {
  document.getElementById('nebflow-profile-panel')?.remove();
  const esc = (s) => String(s ?? '').replace(/[<>&"]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;'}[c]));
  const panel = document.createElement('div');
  panel.id = 'nebflow-profile-panel';
  panel.className = 'nebflow-profile-panel';
  const peers = st.peers || [];
  panel.innerHTML = `
    <div class="profile-header">
      <h3>设备信息</h3>
      <button class="profile-close">&times;</button>
    </div>
    <div class="profile-body">
      <div class="profile-item"><span class="label">设备名</span><span class="value">${esc(st.device?.deviceName)}</span></div>
      <div class="profile-item"><span class="label">设备 ID</span><span class="value">${esc(st.device?.deviceId?.substring(0, 12))}…</span></div>
      <div class="profile-item"><span class="label">平台</span><span class="value">${esc(st.device?.platform)}</span></div>
      <div class="profile-peers">
        <div class="label">在线设备 (${peers.length})</div>
        ${peers.length > 0
          ? peers.map(p => `<div class="peer-item">${esc(p.deviceName)} (${esc(p.platform)})</div>`).join('')
          : '<div class="peer-empty">无其他设备</div>'}
      </div>
      <button class="profile-logout">断开连接</button>
    </div>`;
  document.body.appendChild(panel);

  panel.querySelector('.profile-close').onclick = () => panel.remove();
  panel.querySelector('.profile-logout').onclick = async () => {
    const token = localStorage.getItem('nebflow_token') || '';
    try {
      await fetch('/api/neblink/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ enabled: false }),
      });
    } catch (e) { /* non-critical */ }
    panel.remove();
    location.reload();
  };

  // Click outside to close
  setTimeout(() => {
    document.addEventListener('click', function close(e) {
      if (!panel.contains(e.target) && e.target !== avatarEl && !avatarEl?.contains(e.target)) {
        panel.remove();
        document.removeEventListener('click', close);
      }
    });
  }, 100);
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
  // Filter obviously fake/placeholder URLs
  const validAvatarUrl = avatarUrl && avatarUrl.startsWith('http') && !avatarUrl.includes('example.com') ? avatarUrl : '';
  const showPhoto = loggedIn && validAvatarUrl;
  if (photoEl) {
    photoEl.hidden = !showPhoto;
    photoEl.onerror = () => {
      photoEl.hidden = true;
      if (logoEl) logoEl.hidden = false;
    };
    if (showPhoto && photoEl.src !== validAvatarUrl) photoEl.src = validAvatarUrl;
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
