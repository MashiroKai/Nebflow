// activityBar.js — Vertical icon strip to the left of #sidebar.
//
// Layout (top → bottom):
//   • Avatar — the NebLink login entry. Tap when logged out → opens the
//     NebLink device-flow login modal (GitHub OAuth). Tap when logged in →
//     opens the profile page on nebflow.space in a new tab.
//   • (spacer)
//   • Settings.
//
// Device management (list, rename, cross-device messaging) lives in the Settings
// panel's NebLink section — this bar is intentionally minimal: avatar + login
// entry only.
//
// The bar is an independent glass card (see nav.css #activity-bar). It is NOT
// part of the 3-column layout — order:-1 keeps it leftmost, and it
// stays visible when the sidebar is collapsed.

import { openSettingsPanel, isSettingsPanelActive, showPanel } from './sidebar.js';
import { fetchNeblinkStatus, getNeblinkState, startDeviceFlow, pollDeviceFlow, cancelDeviceFlow } from './neblink.js';
import { renderAgentManager, isAgentsPanelActive } from './agentManager.js';
import { createIconsIn, escapeHtml } from './utils.js';

let initialized = false;
let statusPollTimer = null;

export function initActivityBar() {
  if (initialized) return;
  initialized = true;

  bindSettingsButton();
  bindAgentsButton();
  bindAvatar();

  // Refresh NebLink state now and periodically (only while the page is visible)
  // so the avatar reflects logged-in / pairing state.
  refresh();
  statusPollTimer = setInterval(() => { if (!document.hidden) refresh(); }, 10000);

  observeSettingsPanel();
  observeAgentsPanel();

  if (typeof lucide !== 'undefined') createIconsIn(document.getElementById('activity-bar'));
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

// ── Agents ───────────────────────────────────────────────
function bindAgentsButton() {
  const btn = document.getElementById('agents-btn');
  if (!btn) return;
  btn.addEventListener('click', () => {
    if (isAgentsPanelActive()) {
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
      document.getElementById('panel-sessions')?.classList.add('active');
    } else {
      showPanel('agents');
      renderAgentManager();
    }
  });
}

function observeAgentsPanel() {
  const btn = document.getElementById('agents-btn');
  const panel = document.getElementById('panel-agents');
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
    if (st.loggedIn) {
      // Logged in → open the profile page on nebflow.space
      window.open('https://nebflow.space/profile', '_blank');
    } else {
      // Not logged in → open the NebLink device-flow login modal (GitHub OAuth)
      showLoginModal();
    }
  });
}

// ── Login modal (device flow) ────────────────────────────
// Styles are injected once here (rather than a CSS file) so the modal stays
// self-contained in this module; all values come from the Sapphire Glass
// design variables so it themes with the rest of the UI.
let loginModalStylesInjected = false;
function injectLoginModalStyles() {
  if (loginModalStylesInjected) return;
  loginModalStylesInjected = true;
  const style = document.createElement('style');
  style.textContent = `
.nebflow-login-modal {
  position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%);
  width: 320px; z-index: 1000; overflow: hidden;
  background: var(--glass-bg, rgba(24, 28, 38, 0.68));
  -webkit-backdrop-filter: blur(var(--glass-blur, 24px)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur, 24px)) saturate(1.15);
  border: 1px solid var(--glass-border, rgba(255,255,255,0.1));
  border-radius: 16px;
  box-shadow:
    inset 0 1px 0 0 rgba(255, 255, 255, 0.25),
    0px 2px 8px rgba(0, 0, 0, 0.04),
    0px 8px 24px rgba(0, 0, 0, 0.06);
  animation: nebflowLoginIn 0.2s ease;
}
@media (prefers-color-scheme: dark) {
  .nebflow-login-modal {
    box-shadow:
      inset 0 1px 0 0 rgba(255, 255, 255, 0.04),
      0px 2px 8px rgba(0, 0, 0, 0.20),
      0px 8px 24px rgba(0, 0, 0, 0.35);
  }
}
.nebflow-login-modal::before {
  content: '';
  position: absolute; top: 0; left: 10%; right: 10%; height: 1px;
  background: linear-gradient(90deg, transparent 10%, var(--sapphire-refraction, rgba(255,255,255,0.45)) 50%, transparent 90%);
  pointer-events: none; z-index: 1;
}
@keyframes nebflowLoginIn {
  from { opacity: 0; transform: translate(-50%, -50%) scale(0.96) translateY(8px); }
  to   { opacity: 1; transform: translate(-50%, -50%) scale(1) translateY(0); }
}
.login-modal-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 16px; border-bottom: 1px solid var(--glass-border, rgba(255,255,255,0.05));
}
.login-modal-header h3 { font-size: 14px; margin: 0; color: var(--color-text); font-weight: 600; letter-spacing: -0.01em; }
.login-modal-close {
  background: none; border: none; color: var(--color-text-muted);
  font-size: 18px; cursor: pointer; padding: 0 4px; line-height: 1;
  opacity: 0.5; transition: opacity 0.15s;
}
.login-modal-close:hover { opacity: 1; }
.login-modal-body { padding: 16px; text-align: center; }
.login-hint { font-size: 13px; color: var(--color-text-muted); margin-bottom: 12px; line-height: 1.5; }
.login-user-code {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 26px; font-weight: 600; letter-spacing: 2px;
  color: var(--color-primary); margin-bottom: 4px;
  font-variant-numeric: tabular-nums;
}
.login-code-caption { font-size: 12px; color: var(--color-text-muted); margin-bottom: 14px; }
.login-modal-btn {
  display: inline-block; padding: 8px 20px; border-radius: 10px;
  font-size: 13px; font-weight: 500; color: var(--color-text);
  cursor: pointer; transition: filter 0.15s;
}
.login-modal-btn:hover { filter: brightness(1.06); }
.login-modal-btn:active { filter: brightness(0.96); }
.login-waiting { margin-top: 12px; font-size: 12px; color: var(--color-text-muted); }
.login-success { font-size: 14px; color: var(--color-primary); padding: 12px 0; }
.login-error-msg { font-size: 13px; color: #e57373; margin-bottom: 14px; line-height: 1.5; }
`;
  document.head.appendChild(style);
}

/**
 * Centered glass modal driving the NebLink device-flow login.
 * States: starting → waiting (user code + polling) → success | error.
 * Closing the modal cancels polling.
 */
function showLoginModal() {
  if (document.getElementById('nebflow-login-modal')) return;
  injectLoginModalStyles();

  const modal = document.createElement('div');
  modal.id = 'nebflow-login-modal';
  modal.className = 'nebflow-login-modal';
  document.body.appendChild(modal);

  const st = getNeblinkState();
  let flowInfo = null;
  let finished = false; // true once success/error reached — close() must not cancel then

  const setPairing = (v) => { st.pairing = v; renderAvatar(); };

  const close = () => {
    if (!finished) {
      cancelDeviceFlow();
      setPairing(false);
    }
    document.removeEventListener('keydown', onKey);
    modal.remove();
  };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);
  // Click outside to close (deferred so the opening click doesn't close it).
  setTimeout(() => {
    document.addEventListener('click', function outside(e) {
      if (!document.body.contains(modal)) {
        document.removeEventListener('click', outside);
      } else if (!modal.contains(e.target)) {
        close();
        document.removeEventListener('click', outside);
      }
    });
  }, 100);

  const render = (state, data = {}) => {
    let body = '';
    if (state === 'starting') {
      body = `<div class="login-waiting" style="margin-top:0;padding:12px 0">正在启动设备授权…</div>`;
    } else if (state === 'waiting') {
      body = `
        <div class="login-hint">在浏览器中完成 GitHub 授权以连接此设备</div>
        <div class="login-user-code">${escapeHtml(data.userCode || '')}</div>
        <div class="login-code-caption">授权码</div>
        <button class="login-modal-btn glass-control" id="login-open-auth">打开授权页面</button>
        <div class="login-waiting">等待授权完成…</div>`;
    } else if (state === 'success') {
      body = `<div class="login-success">✓ 连接成功，设备已加入网络</div>`;
    } else if (state === 'error') {
      body = `
        <div class="login-error-msg">${escapeHtml(data.message || '授权失败')}</div>
        <button class="login-modal-btn glass-control" id="login-retry">重试</button>`;
    }
    modal.innerHTML = `
      <div class="login-modal-header">
        <h3>登录到 NebLink</h3>
        <button class="login-modal-close">&times;</button>
      </div>
      <div class="login-modal-body">${body}</div>`;
    modal.querySelector('.login-modal-close').onclick = close;
    modal.querySelector('#login-open-auth')?.addEventListener('click', () => {
      if (flowInfo?.verificationUri) window.open(flowInfo.verificationUri, '_blank');
    });
    modal.querySelector('#login-retry')?.addEventListener('click', () => startFlow());
  };

  const startFlow = async () => {
    setPairing(true);
    finished = false;
    render('starting');
    try {
      flowInfo = await startDeviceFlow();
      // Mirror the codes into neblinkState (same as the Settings flow) so the
      // Settings panel shows the waiting UI if it's open.
      st.deviceCode = flowInfo.deviceCode;
      st.userCode = flowInfo.userCode;
      st.flowState = 'waiting';
      render('waiting', { userCode: flowInfo.userCode });
      // Auto-open the authorization page; the in-modal button is the fallback
      // in case the popup was blocked.
      if (flowInfo.verificationUri) window.open(flowInfo.verificationUri, '_blank');
      pollDeviceFlow(
        flowInfo.deviceCode,
        flowInfo.interval || 3,
        flowInfo.expiresIn || 900,
        () => { // success
          finished = true;
          setPairing(false);
          render('success');
          setTimeout(() => { close(); refresh(); }, 1500);
        },
        (errMsg) => { // error / timeout
          finished = true;
          setPairing(false);
          render('error', { message: errMsg });
        }
      );
    } catch (e) {
      finished = true;
      setPairing(false);
      render('error', { message: e.message || '启动设备流程失败' });
    }
  };

  startFlow();
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
  const loggedIn = !!st.loggedIn;
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
    : (loggedIn ? '个人主页' : '登录');
}
