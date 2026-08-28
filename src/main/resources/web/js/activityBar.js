// activityBar.js — Vertical icon strip to the left of #sidebar.
//
// Layout (top → bottom):
//   • Avatar — the NebLink login entry. Tap when logged out → opens the
//     NebLink login modal (Logto OIDC, Authorization Code + PKCE; legacy
//     device flow as fallback). Tap when logged in →
//     opens the profile page on nebflow.space in a new tab.
//   • Side Bar panel switch buttons (Files; future panels register the same way).
//   • (spacer)
//   • Teams / Flows / Agents (Canvas tabs) and Settings.
//
// This module also owns the Side Bar panel registry: the single source of
// truth for Side Bar visibility + the active panel. Panel buttons, the header
// #sidebar-toggle and ⌘B all drive the same API (setSideBarCollapsed /
// toggleSideBar) — nothing else toggles the sidebar-collapsed class.
//
// The bar is an independent glass card (see nav.css #activity-bar). It is NOT
// part of the 3-column layout — order:-1 keeps it leftmost, and it
// stays visible when the sidebar is collapsed.

import { openSettingsPanel, closeSettingsPanel, isSettingsPanelActive } from './sidebar.js';
import { fetchNeblinkStatus, getNeblinkState, startDeviceFlow, pollDeviceFlow, cancelDeviceFlow, startPkceLogin, pollPkceState, cancelPkceFlow } from './neblink.js';
import { openAgents } from './agentManager.js';
import { createIconsIn, escapeHtml } from './utils.js';
import { brand } from './brand.js';
import { t } from './i18n.js';
import { key } from './branding.js';

let initialized = false;
let statusPollTimer = null;

export function initActivityBar() {
  if (initialized) return;
  initialized = true;

  initSidePanels();
  bindSettingsButton();
  bindAgentsButton();
  bindAvatar();

  // Refresh NebLink state now and periodically (only while the page is visible)
  // so the avatar reflects logged-in / pairing state.
  refresh();
  statusPollTimer = setInterval(() => { if (!document.hidden) refresh(); }, 10000);

  observeSettingsModal();

  if (typeof lucide !== 'undefined') createIconsIn(document.getElementById('activity-bar'));
}

// ── Side Bar panel registry ──────────────────────────────
// State:
//   body.sidebar-collapsed — visibility, persisted as key('sidebar_collapsed')
//   activePanelId          — last active panel id, persisted as
//                            key('sidebar_active_panel'); kept while collapsed
//                            so the next expand restores it
// Collapsed ⇔ no panel active. The inline pre-paint script in index.html
// restores the classes before first paint; this module re-syncs on init.
const LS_COLLAPSED = key('sidebar_collapsed');
const LS_PANEL = key('sidebar_active_panel');

/** @type {Map<string, {id: string, buttonId: string, panelId: string, i18nKey: string|undefined}>} */
const sidePanels = new Map();
/** Last active panel id (survives collapse so expand restores it). */
let activePanelId = 'files';

/**
 * Register a Side Bar panel + its Activity Bar switch button.
 * Adding a future panel is a registration — no layout code changes.
 * @param {{id: string, buttonId: string, panelId: string, i18nKey?: string}} def
 */
export function registerSidePanel(def) {
  const { id, buttonId, panelId, i18nKey } = def;
  if (!id || sidePanels.has(id)) return;
  sidePanels.set(id, { id, buttonId, panelId, i18nKey });
  const btn = document.getElementById(buttonId);
  if (!btn) return;
  if (i18nKey) btn.title = t(i18nKey);
  btn.addEventListener('click', () => onPanelButtonClick(id));
}

/** true when the Side Bar is collapsed (the Activity Bar stays visible). */
export function isSideBarCollapsed() {
  return document.body.classList.contains('sidebar-collapsed');
}

/** Collapse/expand the Side Bar. Expanding restores the last active panel. */
export function setSideBarCollapsed(collapsed) {
  document.body.classList.toggle('sidebar-collapsed', collapsed);
  localStorage.setItem(LS_COLLAPSED, String(collapsed));
  if (!collapsed) {
    if (!sidePanels.has(activePanelId)) activePanelId = 'files';
    localStorage.setItem(LS_PANEL, activePanelId);
  }
  syncPanelDom();
}

/** ⌘B / header #sidebar-toggle entry point. */
export function toggleSideBar() {
  setSideBarCollapsed(!isSideBarCollapsed());
}

function onPanelButtonClick(id) {
  if (!sidePanels.has(id)) return;
  if (!isSideBarCollapsed() && activePanelId === id) {
    // Re-click the active icon → collapse the Side Bar (VSCode semantics).
    setSideBarCollapsed(true);
    return;
  }
  // Switch panel (instant — no width animation) and/or expand.
  activePanelId = id;
  localStorage.setItem(LS_PANEL, id);
  setSideBarCollapsed(false);
}

/** Mirror state onto panel/button classes + aria-pressed. */
function syncPanelDom() {
  const collapsed = isSideBarCollapsed();
  for (const p of sidePanels.values()) {
    const on = !collapsed && p.id === activePanelId;
    const panel = document.getElementById(p.panelId);
    const btn = document.getElementById(p.buttonId);
    if (panel) panel.classList.toggle('active', on);
    if (btn) {
      btn.classList.toggle('active', on);
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }
}

function initSidePanels() {
  registerSidePanel({
    id: 'messages',
    buttonId: 'messages-btn',
    panelId: 'panel-messages',
    i18nKey: 'activity.messages',
  });
  registerSidePanel({
    id: 'contacts',
    buttonId: 'contacts-btn',
    panelId: 'panel-contacts',
    i18nKey: 'activity.contacts',
  });
  registerSidePanel({
    id: 'files',
    buttonId: 'files-btn',
    panelId: 'panel-sessions',
    i18nKey: 'activity.files',
  });
  registerSidePanel({
    id: 'messages',
    buttonId: 'messages-btn',
    panelId: 'panel-messages',
    i18nKey: 'activity.messages',
  });
  registerSidePanel({
    id: 'contacts',
    buttonId: 'contacts-btn',
    panelId: 'panel-contacts',
    i18nKey: 'activity.contacts',
  });
  // Restore the persisted panel; unregistered ids fall back to files.
  const stored = localStorage.getItem(LS_PANEL);
  activePanelId = stored && sidePanels.has(stored) ? stored : 'files';
  // Classes were already restored pre-paint by the inline script; this
  // re-sync is authoritative for the runtime (aria-pressed included).
  syncPanelDom();
  // Button titles follow the language.
  window.addEventListener('locale-changed', () => {
    for (const p of sidePanels.values()) {
      if (!p.i18nKey) continue;
      const btn = document.getElementById(p.buttonId);
      if (btn) btn.title = t(p.i18nKey);
    }
  });
  bridgeExplorerTitle();
}

// explorer.js (zero-change file) owns the explorer header title: it shows the
// root folder name when a folder is open, and the hardcoded literal
// 'Explorer' otherwise. Bridge the no-root case to i18n: whenever the title
// carries no root path (its title tooltip attr is empty), show the localized
// panel name. Self-heals after every explorer.js rewrite via observer.
function bridgeExplorerTitle() {
  const el = document.getElementById('panel-title-explorer');
  if (!el) return;
  const apply = () => {
    if (el.title) return; // a root folder name is shown — leave it
    const localized = t('panel.explorer');
    if (el.textContent !== localized) el.textContent = localized;
  };
  apply();
  new MutationObserver(apply).observe(el, { childList: true, characterData: true, subtree: true });
  window.addEventListener('locale-changed', apply);
}

/**
 * Set the count badge on an Activity Bar button (friends-messaging §4 L1/L1b).
 * 0 hides the badge; >99 shows "99+". The badge slot lives inside the button
 * markup (`<span class="activity-badge" hidden>`).
 * @param {string} buttonId
 * @param {number} count
 * @param {string} [ariaLabel] - full accessible label (already interpolated)
 */
export function setActivityBadge(buttonId, count, ariaLabel) {
  const btn = document.getElementById(buttonId);
  const badge = /** @type {HTMLElement|null} */ (btn ? btn.querySelector('.activity-badge') : null);
  if (!badge) return;
  const n = Math.max(0, Math.floor(Number(count) || 0));
  badge.hidden = n === 0;
  badge.textContent = n > 99 ? '99+' : String(n);
  if (ariaLabel !== undefined) badge.setAttribute('aria-label', n > 0 ? ariaLabel : '');
}

// ── Settings ─────────────────────────────────────────────
function bindSettingsButton() {
  const btn = document.getElementById('settings-btn');
  if (!btn) return;
  btn.addEventListener('click', () => {
    // Settings is a centered modal — click toggles it open/closed.
    if (isSettingsPanelActive()) {
      closeSettingsPanel();
    } else {
      openSettingsPanel();
    }
  });
}

/** Mirror the settings modal's open state onto the button highlight. */
function observeSettingsModal() {
  const btn = document.getElementById('settings-btn');
  const overlay = document.getElementById('settings-overlay');
  if (!btn || !overlay) return;
  const sync = () => btn.classList.toggle('active', overlay.classList.contains('on'));
  sync();
  new MutationObserver(sync).observe(overlay, { attributes: true, attributeFilter: ['class'] });
}

// ── Agents ───────────────────────────────────────────────
// Agents is a Canvas tab (same pattern as Teams/Flows). The button's active
// state is synced by agentManager.js via canvas-tab-closed / restore events.
function bindAgentsButton() {
  const btn = document.getElementById('agents-btn');
  if (!btn) return;
  btn.addEventListener('click', () => openAgents());
}

// ── Avatar / login ───────────────────────────────────────
function bindAvatar() {
  const avatar = document.getElementById('activity-avatar');
  if (!avatar) return;
  avatar.addEventListener('click', () => {
    const st = getNeblinkState();
    if (st.pairing) return; // pairing in progress — ignore
    if (st.loggedIn) {
      // Logged in → open the profile page on the product domain
      window.open(`https://${brand.domain}/profile`, '_blank');
    } else {
      // Not logged in → open the NebLink login modal (PKCE, device-flow fallback)
      showLoginModal();
    }
  });
}

// ── Login modal (PKCE primary, device-flow fallback) ─────
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
 * Public entry for the NebLink login modal (used by the
 * friends/messages panels' logged-out empty states).
 */
export function openLoginModal() {
  showLoginModal();
}

/**
 * Centered glass modal driving the NebLink login.
 * States: starting → waiting (PKCE browser login) → success | error, with a
 * waiting-device state (user code + polling) when the gateway reports
 * logto-not-configured and the legacy device flow takes over.
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
      cancelPkceFlow();
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
      body = `<div class="login-waiting" style="margin-top:0;padding:12px 0">正在启动登录…</div>`;
    } else if (state === 'waiting') {
      // PKCE primary path: nothing to copy - the browser tab does the whole
      // hosted login and redirects back to the local gateway.
      body = `
        <div class="login-hint">在浏览器中登录 nebflow 账号以连接此设备</div>
        <button class="login-modal-btn glass-control" id="login-open-auth">重新打开登录页面</button>
        <div class="login-waiting">等待登录完成…</div>`;
    } else if (state === 'waiting-device') {
      // Legacy device-flow fallback (gateway reports logto-not-configured).
      body = `
        <div class="login-hint">在浏览器中完成授权以连接此设备</div>
        <div class="login-user-code">${escapeHtml(data.userCode || '')}</div>
        <div class="login-code-caption">授权码</div>
        <button class="login-modal-btn glass-control" id="login-open-auth">打开授权页面</button>
        <div class="login-waiting">等待授权完成…</div>`;
    } else if (state === 'success') {
      body = `<div class="login-success">✓ 连接成功，设备已加入网络</div>`;
    } else if (state === 'error') {
      body = `
        <div class="login-error-msg">${escapeHtml(data.message || '登录失败')}</div>
        <button class="login-modal-btn glass-control" id="login-retry">重试</button>`;
    }
    modal.innerHTML = `
      <div class="login-modal-header">
        <h3>登录 nebflow 账号</h3>
        <button class="login-modal-close">&times;</button>
      </div>
      <div class="login-modal-body">${body}</div>`;
    modal.querySelector('.login-modal-close').onclick = close;
    modal.querySelector('#login-open-auth')?.addEventListener('click', () => {
      if (flowInfo?.authorizeUrl) window.open(flowInfo.authorizeUrl, '_blank');
      else if (flowInfo?.verificationUri) window.open(flowInfo.verificationUri, '_blank');
    });
    modal.querySelector('#login-retry')?.addEventListener('click', () => startFlow());
  };

  const startFlow = async () => {
    setPairing(true);
    finished = false;
    render('starting');
    const onSuccess = () => {
      finished = true;
      setPairing(false);
      render('success');
      setTimeout(() => { close(); refresh(); }, 1500);
    };
    const onError = (errMsg) => {
      finished = true;
      setPairing(false);
      render('error', { message: errMsg });
    };
    try {
      // Primary path: Authorization Code + PKCE via the hosted Logto page.
      const pkce = await startPkceLogin();
      if (pkce) {
        flowInfo = pkce;
        st.flowState = 'waiting';
        render('waiting');
        // Auto-open the login page; the in-modal button is the fallback
        // in case the popup was blocked.
        window.open(pkce.authorizeUrl, '_blank');
        pollPkceState(onSuccess, onError);
        return;
      }
      // Fallback: gateway answered logto-not-configured - legacy device flow.
      flowInfo = await startDeviceFlow();
      // Mirror the codes into neblinkState (same as the Settings flow) so the
      // Settings panel shows the waiting UI if it's open.
      st.deviceCode = flowInfo.deviceCode;
      st.userCode = flowInfo.userCode;
      st.flowState = 'waiting';
      render('waiting-device', { userCode: flowInfo.userCode });
      if (flowInfo.verificationUri) window.open(flowInfo.verificationUri, '_blank');
      pollDeviceFlow(
        flowInfo.deviceCode,
        flowInfo.interval || 3,
        flowInfo.expiresIn || 900,
        onSuccess,
        onError
      );
    } catch (e) {
      finished = true;
      setPairing(false);
      render('error', { message: e.message || '启动登录失败' });
    }
  };

  startFlow();
}

// ── State refresh → avatar styling ───────────────────────
// Error latch: remember avatar URLs that failed to load (e.g. account avatars
// unreachable without a proxy). Without this, the 10s refresh poll re-shows
// the broken <img> every cycle (src unchanged → no retry → broken-image icon)
// and the onerror fallback to the logo never sticks.
let avatarFailedUrl = '';

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
  const showPhoto = loggedIn && validAvatarUrl && avatarFailedUrl !== validAvatarUrl;
  if (photoEl) {
    photoEl.hidden = !showPhoto;
    photoEl.onerror = () => {
      avatarFailedUrl = validAvatarUrl; // latch: stop re-showing the broken image
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
