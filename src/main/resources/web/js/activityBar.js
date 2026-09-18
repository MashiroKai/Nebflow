// activityBar.js — Vertical icon strip to the left of #sidebar.
//
// Layout (top → bottom):
//   • Avatar — the NebLink login entry. Tap when logged out → opens the
//     NebLink login modal (Logto OIDC, Authorization Code + PKCE; legacy
//     device flow as fallback). Tap when logged in →
//     opens the account profile page (brand.getProfileUrl) in a new tab.
//   • Side Bar panel switch buttons (Files; future panels register the same way).
//   • (spacer)
//   • Projects / Plugins (Canvas tabs) and Settings.
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
import { fetchNeblinkStatus, getNeblinkState, startDeviceFlow, pollDeviceFlow, cancelDeviceFlow, startPkceLogin, pollPkceState, cancelPkceFlow, avatarViewState, noteAvatarFailure, paintAvatarSlot, LOCAL_FILE_CODES, openEndSessionHandoff } from './neblink.js';
import { setUpdateDot } from './updateCheck.js';
import { createIconsIn, escapeHtml } from './utils.js';
import { getProfileUrl } from './brand.js';
import { t } from './i18n.js';
import { key } from './branding.js';

let initialized = false;
let statusPollTimer = null;

// ── 状态 beacon 订阅点（①opt-A3 载体）─────────────────────
// 既有 10s 状态轮询是页面唯一的「活着」节拍（只看可见性、不新增定时器）。
// 订阅者复用这一拍做降级动作（好友消息面：relay 不可用时的 REST 增量回补），
// 因此**不引入第二个定时器**、频率与可见性守卫与 beacon 完全同源。
// 返回注销函数（当前唯一订阅者 messages.js 整页生命周期只装一次，保留注销
// 能力是为了不把「只能加不能减」的隐含约束写进接口语义）。
/** @type {Set<() => void>} */
const statusTickListeners = new Set();

/**
 * 订阅既有 10s 状态 beacon 的每一拍（仅页面可见时触发）。
 * @param {() => void} cb
 * @returns {() => void} unsubscribe
 */
export function onStatusTick(cb) {
  if (typeof cb !== 'function') return () => {};
  statusTickListeners.add(cb);
  return () => { statusTickListeners.delete(cb); };
}

export function initActivityBar() {
  if (initialized) return;
  initialized = true;

  initSidePanels();
  bindSettingsButton();
  bindAvatar();
  bindSettingsAccountEntry();

  // Refresh NebLink state now and periodically (only while the page is visible)
  // so the avatar reflects logged-in / pairing state.
  refresh();
  statusPollTimer = setInterval(() => {
    if (document.hidden) return;
    refresh();
    // ①opt-A3：beacon 的同一拍上跑订阅者（好友消息面降级回补）。订阅者自身
    // 负责「健康路径零请求」的判据与节流 —— 这里不做任何策略判断。
    for (const cb of [...statusTickListeners]) {
      try { cb(); } catch { /* 订阅者异常不得打断状态轮询 */ }
    }
  }, 10000);

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

/**
 * Show a Side Bar panel without the icon-button toggle semantics.
 *
 * onPanelButtonClick collapses the bar when you re-click the ACTIVE panel
 * (VSCode behaviour) — correct for the icon, wrong for a programmatic
 * "reveal this panel" (Project card → open workspace in the file explorer):
 * revealing must never collapse the bar the user is about to read.
 * @param {string} id — registered side panel id (e.g. 'files')
 */
export function showSidePanel(id) {
  if (!sidePanels.has(id)) return;
  activePanelId = id;
  localStorage.setItem(LS_PANEL, id);
  setSideBarCollapsed(false);
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
    id: 'files',
    buttonId: 'files-btn',
    panelId: 'panel-sessions',
    i18nKey: 'activity.files',
  });
  // Friends gating — single decision point: js/featureFlags.js friendsEnabled()
  // (release marker vs. dev config chain). Detach the Messages/Contacts entries
  // now; main.js calls enableFriendPanels() once the first configData latches
  // the flag on. Default is ON / unsealed (author ruling 2026-09-14).
  detachFriendEntries();
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

// ── Friends feature release gating (author ruling 2026-09-08) ────────────
// Friend messaging + contacts ship disabled by default: a release user has no
// reachable friend backend, so any leftover entry would be a dead end. The
// flag lives in the existing server config channel — nebflow.json key
// "features": { "friends": true } reaches the frontend verbatim via WS
// configData → state.parsedConfig (see featureFlags.js). Gating = DOM
// removal, not CSS hiding: the Messages/Contacts buttons + panels are
// detached at boot; enableFriendPanels() re-attaches + registers them when
// the flag is on. Decision latches once per boot (main.js); a config edit
// takes effect on reload.
/** @type {{msgsBtn: HTMLElement, contactsBtn: HTMLElement, msgsPanel: HTMLElement, contactsPanel: HTMLElement} | null} */
let friendEntryNodes = null;

/** Detach the gated friend entries from the DOM (default-off boot posture). */
function detachFriendEntries() {
  const msgsBtn = document.getElementById('messages-btn');
  const contactsBtn = document.getElementById('contacts-btn');
  const msgsPanel = document.getElementById('panel-messages');
  const contactsPanel = document.getElementById('panel-contacts');
  if (!msgsBtn || !contactsBtn || !msgsPanel || !contactsPanel) return;
  friendEntryNodes = { msgsBtn, contactsBtn, msgsPanel, contactsPanel };
  for (const el of Object.values(friendEntryNodes)) el.remove();
}

/**
 * Re-attach + register the gated friend entries (flag confirmed on).
 * Idempotent — a no-op once the entries are back in the DOM.
 */
export function enableFriendPanels() {
  if (!friendEntryNodes) return;
  const { msgsBtn, contactsBtn, msgsPanel, contactsPanel } = friendEntryNodes;
  friendEntryNodes = null;
  document.querySelector('#activity-bar .activity-spacer')?.before(msgsBtn, contactsBtn);
  document.getElementById('sidebar-panel')?.append(msgsPanel, contactsPanel);
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
  // The buttons were detached before initActivityBar's createIconsIn pass —
  // convert their <i data-lucide> placeholders now.
  if (typeof lucide !== 'undefined') {
    createIconsIn(msgsBtn);
    createIconsIn(contactsBtn);
  }
  // Honor the persisted active panel now that the registry knows these ids.
  const stored = localStorage.getItem(LS_PANEL);
  if (stored && sidePanels.has(stored)) activePanelId = stored;
  syncPanelDom();
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
      // Opening settings is the green-dot clear point (updateCheck.js lights
      // it on a silent auto check; the user has now seen the About section).
      setUpdateDot(false);
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
// Agents is a Canvas tab (same pattern as Teams/Flows). Its button click and
// pressed state are owned by canvas.js registerCanvasPanelButton (registered
// from agentManager.js) — the 4-state toggle machine shared with Teams/Flows.

// ── Avatar / login ───────────────────────────────────────
/**
 * The account entry — ONE function, so every entry point (Activity Bar avatar,
 * settings avatar entry, friends/messages logged-out empty states) behaves
 * identically and can never drift.
 *
 * 🔴 MUST run synchronously inside a real user-gesture click handler: the
 * logged-out branch reserves the login popup inside the caller's gesture stack.
 * A synthesized `el.click()` carries no transient user activation, so a real
 * browser popup-blocks the tab it opens (that is why the settings entry no
 * longer forwards a synthetic click — see bindSettingsAccountEntry).
 *
 * Logged out → the single click starts the PKCE flow (no second confirmation
 * step); logged in → open the account profile page.
 */
export function activateAccountEntry() {
  const st = getNeblinkState();
  if (st.pairing) return; // pairing in progress — ignore
  if (st.loggedIn) {
    // Logged in → open the account profile page. The URL comes from the
    // brand contract (injected profileUrl, fallback in brand.js) — building
    // it from brand.domain shipped a dead https://neblink.example/profile
    // link, since domain is a display-only placeholder.
    // 'noopener': the profile page must not get a window.opener handle back
    // into the app window.
    window.open(getProfileUrl(), '_blank', 'noopener');
    return;
  }
  // Not logged in → start the NebLink login (PKCE, device-flow fallback).
  // showLoginModal reserves the popup synchronously in THIS gesture.
  showLoginModal();
}

function bindAvatar() {
  const avatar = document.getElementById('activity-avatar');
  if (!avatar) return;
  avatar.addEventListener('click', activateAccountEntry);
}

/**
 * The settings page's avatar entry (second entry point, same behaviour).
 *
 * WHY delegation instead of a direct binding: sidebar.js renders
 * #settings-avatar-entry lazily (renderSettings), so the element does not exist
 * when this module initialises; and sidebar.js may not statically import this
 * module — `scripts/check-circular.mjs` fails any static sidebar <-> activityBar
 * cycle. The sanctioned escape hatch (dynamic import()) is unusable here: it
 * would move the popup reservation out of the gesture stack.
 * A document-level delegated listener keeps the popup reservation synchronous
 * inside the user's own click and follows re-renders for free.
 */
function bindSettingsAccountEntry() {
  document.addEventListener('click', (e) => {
    const target = e.target instanceof Element ? e.target : null;
    if (target && target.closest('#settings-avatar-entry')) activateAccountEntry();
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
/* Panel hierarchy (2026-09-16 login-entry batch) — three levels, in order:
     ① title  .login-modal-header h3
     ② hint   .login-hint / .login-code-caption  (what this step needs)
     ③ keys   .login-actions (primary + secondary; geometry/material are the
              login-key family's single source in sapphire.css — NOT here)
   followed by the .login-waiting status line. This block therefore declares
   LAYOUT/TYPOGRAPHY ONLY: the key form itself is cross-surface (activity bar
   entry + panel CTAs + friends empty state) and lives in the family block, so
   it can never drift between surfaces. */
.login-modal-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 16px; border-bottom: 1px solid var(--glass-border, rgba(255,255,255,0.05));
}
.login-modal-header h3 { font-size: 15px; margin: 0; color: var(--color-text); font-weight: 600; letter-spacing: -0.01em; }
.login-modal-close {
  background: none; border: none; color: var(--color-text-muted);
  font-size: 18px; cursor: pointer; padding: 0 4px; line-height: 1;
  opacity: 0.5; transition: opacity 0.15s;
}
.login-modal-close:hover { opacity: 1; }
.login-modal-body { padding: 16px; text-align: center; }
.login-hint { font-size: 13px; color: var(--color-text-muted); margin-bottom: 14px; line-height: 1.55; }
.login-user-code {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 26px; font-weight: 600; letter-spacing: 2px;
  color: var(--color-primary); margin-bottom: 4px;
  font-variant-numeric: tabular-nums;
}
.login-code-caption { font-size: 12px; color: var(--color-text-muted); margin-bottom: 14px; }
/* Key stack. The two tiers are ONE family (sapphire.css 登录键族块):
   primary = green solid (cfg-btn-primary carries the white ink),
   secondary = same geometry, glass material. Geometry itself lives in the
   family block (single source) — this block only stacks the keys. */
.login-actions { display: flex; flex-direction: column; gap: 8px; }
.login-waiting { margin-top: 12px; font-size: 12px; color: var(--color-text-muted); }
.login-success { font-size: 14px; color: var(--color-primary); padding: 12px 0; }
.login-error-msg { font-size: 13px; color: #e57373; margin-bottom: 14px; line-height: 1.5; }
`;
  document.head.appendChild(style);
}

/**
 * Public entry for the NebLink login modal (used by the
 * friends/messages panels' logged-out empty states).
 *
 * `opts.auto === true` marks a caller that is NOT a user gesture — today
 * exactly the two `fm-auth-required` handlers (`contacts.js` / `messages.js`),
 * an event `friendsApi.js` dispatches on EVERY friendship 401/403. Such
 * callers are rate-limited by the repeat guard in `showLoginModal` instead of
 * being allowed to open an OAuth window per failed request. Plain calls stay
 * unguarded: they are either click handlers or the switch-account fallbacks,
 * i.e. real user intent.
 *
 * Two more opt-in flags (one-window switch batch, 2026-09-16), both used ONLY
 * by the switch-account fallback in `neblink.js`:
 *  - `opts.forceLogin === true` → the initial flow starts with
 *    `prompt="login consent"` (forced fresh login) instead of the plain
 *    `consent`. Without it the switch fallback would silently downgrade to a
 *    plain login and could re-enter the OLD account through a still-live SSO
 *    session — the exact defect the switch-account flow exists to prevent.
 *  - `opts.deferPopup === true` → do NOT reserve a popup at all. Used when the
 *    caller has no user gesture (a watchdog, not a click): a reservation there
 *    is either popup-blocked (real browsers) or, with the blocker off, opens a
 *    window the flow must not open. The panel's own 「重新打开登录页面」 button
 *    (a real gesture) then opens the window through the existing
 *    `.authorizeUrl` path. Neither flag touches the guard: `deferPopup` still
 *    arms `loginFlowGuardUntil` like every other open.
 */
export function openLoginModal(opts = {}) {
  showLoginModal(opts);
}

// ── Popup-blocker resilience ─────────────────────────────
// Browsers only allow window.open inside the synchronous user-gesture stack.
// The login flow must await the gateway (PKCE / device-flow start) before it
// knows the authorize URL, so a plain window.open after the await is blocked.
// Pattern: reserve an about:blank window synchronously in the click handler,
// then navigate it once the URL arrives. If the reservation itself returns
// null (blocker), fall back to a toast with a manual glass-control link.

/** @type {Window|null} */
let reservedPopup = null;

/** Repeat guard for the AUTO-started login flow (2026-09-15 OIDC fix).
 *
 * Defect it closes: `friendsApi.js` dispatches `fm-auth-required` on every
 * friendship 401/403 (two call sites) and both handlers call
 * `openLoginModal()`. Every one of those used to reserve a popup and start a
 * FULL PKCE flow, so an auth-failure burst opened one OAuth window after
 * another, each landing on the loopback callback page — the "switch account
 * keeps reopening http://127.0.0.1:<port>/auth/callback?code=…" symptom.
 * The modal's own success path closes itself 1.5s later, which re-armed the
 * DOM guard in `showLoginModal` and let the next event start yet another flow
 * (2026-09-15 author-machine reading: 5 complete logins in 19s, no click).
 *
 * Semantics: at most one AUTO flow per guard window; a repeat leaves the
 * panels' 登录失效卡 + 重登按钮 as the visible manual retry surface, so the
 * failure is still surfaced (never a silent no-op). User gestures are not
 * rate-limited — only `openLoginModal({auto:true})` callers consult this. */
const LOGIN_FLOW_GUARD_MS = 15000;
let loginFlowGuardUntil = 0;

/** Reserve a blank popup. Must be called synchronously in the gesture stack. */
function reservePopup() {
  try {
    reservedPopup = window.open('about:blank', '_blank');
  } catch (e) {
    reservedPopup = null;
  }
  return reservedPopup;
}

/** Navigate the reserved popup to url (or close it when url is null). */
function navigateReserved(url) {
  const w = reservedPopup;
  reservedPopup = null;
  if (!w) return false;
  try {
    if (url) { w.location.href = url; return true; }
    w.close();
  } catch (e) { /* cross-origin or already closed - ignore */ }
  return false;
}

/** Blocked fallback: toast with a manual glass-control link to url. */
function popupBlockedFallback(url) {
  const toast = document.createElement('div');
  toast.className = 'nebflow-toast nebflow-toast-info';
  const msg = document.createElement('span');
  msg.textContent = t('login.popupBlocked');
  const a = document.createElement('a');
  a.className = 'glass-control nebflow-toast-link';
  a.href = url;
  a.target = '_blank';
  a.rel = 'noopener';
  a.textContent = t('login.openPage');
  toast.append(msg, a);
  document.body.appendChild(toast);
  requestAnimationFrame(() => toast.classList.add('show'));
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 300);
  }, 8000);
}

/**
 * Centered glass modal driving the NebLink login.
 * States: starting → waiting (PKCE browser login) → success | error, with a
 * waiting-device state (user code + polling) when the gateway reports
 * logto-not-configured and the legacy device flow takes over.
 * Closing the modal cancels polling.
 */
function showLoginModal(opts = {}) {
  if (document.getElementById('nebflow-login-modal')) return;
  // Non-gesture repeat guard (2026-09-15 OIDC fix) — see LOGIN_FLOW_GUARD_MS.
  // Checked BEFORE the modal is built, so a suppressed repeat leaves no empty
  // panel behind; the status refresh keeps the visible surfaces current.
  if (opts.auto && Date.now() < loginFlowGuardUntil) {
    refresh();
    return;
  }
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
  // The recovery key's authorize-URL opener (2026-09-16 login-entry batch).
  // Behaviour is the pre-existing one, verbatim: open whatever the gateway
  // handed us.
  const openAuthPage = () => {
    if (flowInfo?.authorizeUrl) window.open(flowInfo.authorizeUrl, '_blank');
    else if (flowInfo?.verificationUri) window.open(flowInfo.verificationUri, '_blank');
  };
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
      body = `<div class="login-waiting" style="margin-top:0;padding:12px 0">${t('login.starting')}</div>`;
    } else if (state === 'waiting') {
      // PKCE primary path: nothing to copy - the browser tab does the whole
      // hosted login and redirects back to the local gateway.
      // One-click entry (2026-09-15 session-handoff 案 3 ①): when THIS click
      // already opened the hosted login page there is no second step left to
      // perform, so the modal stops asking for one — no "重新打开登录页面"
      // button, just the wait state. The button is rendered only when the popup
      // reservation was refused (data.popupOpened === false), i.e. when
      // clicking it IS the recovery; the blocked toast (popupBlockedFallback)
      // accompanies it.
      // "使用其他账号登录" (RP-logout fix, 2026-09-06): restarts the flow
      // with prompt="login consent" so the hosted page shows the account
      // form even when this browser still holds a Logto SSO session. Kept in
      // BOTH branches — it also resets the flow on a failed attempt.
      // (Absent data.popupOpened = not popup-blocked: no nag.)
      //
      // 2026-09-16 login-entry batch — the panel's keys all go through the ONE
      // login-key family (sapphire.css 登录键族块): the recovery key
      // (`login-open-auth`) is the primary tier (green solid) and 「使用其他账号
      // 登录」 is the secondary tier (same geometry, glass). No key is added or
      // removed here: the one-click entry (no second step to perform) is
      // unchanged.
      const popupOpened = data.popupOpened !== false;
      body = `
        <div class="login-hint">${t('login.hintBrowser')}</div>
        <div class="login-actions">
          ${popupOpened ? '' : `<button class="login-modal-btn glass-control cfg-btn-primary" id="login-open-auth">${t('login.reopenAuthPage')}</button>`}
          <button class="login-modal-btn glass-control login-modal-btn-secondary" id="login-switch-account">${t('login.switchAccount')}</button>
        </div>
        <div class="login-waiting">${popupOpened ? t('login.waitingPopupOpened') : t('login.waiting')}</div>`;
    } else if (state === 'waiting-device') {
      // Legacy device-flow fallback (gateway reports logto-not-configured).
      body = `
        <div class="login-hint">${t('login.hintAuthorize')}</div>
        <div class="login-user-code">${escapeHtml(data.userCode || '')}</div>
        <div class="login-code-caption">${t('login.deviceCodeCaption')}</div>
        <div class="login-actions">
          <button class="login-modal-btn glass-control cfg-btn-primary" id="login-open-auth">${t('login.openAuthPage')}</button>
          <button class="login-modal-btn glass-control login-modal-btn-secondary" id="login-switch-account">${t('login.switchAccount')}</button>
        </div>
        <div class="login-waiting">${t('login.waitingAuthorize')}</div>`;
    } else if (state === 'success') {
      body = `<div class="login-success">${t('login.success')}</div>`;
    } else if (state === 'error') {
      // Error state carries BOTH tiers on purpose: 重试 (primary, green) and
      // 使用其他账号登录 (secondary, glass) — the tier difference is what the
      // author's "主键 vs 次键档位要分明" reading can be checked against.
      //
      // 缺陷 A（上游 §8.2 第 7 项）：文案 = **三段式**（原因 + 下一步动作 + 诊断码），
      // 由 `neblink.js` 的 `loginFailureText` 单点组装（i18n 优先，后端分类串兜底）；
      // 本文件**不**再原样打印后端串。两键档位与主/次键视觉**逐字保留**（§13 禁造新轮子），
      // 仅在**本地凭据文件类**分类下加一枚「清理并重登」键（门控判据同设置面板）。
      const failureCode = data.code || '';
      const cleanupKey = LOCAL_FILE_CODES.includes(failureCode)
        ? `<button class="login-modal-btn glass-control login-modal-btn-secondary" id="login-cleanup">${t('neblink.cleanupRelogin')}</button>`
        : '';
      body = `
        <div class="login-error-msg" data-code="${escapeHtml(failureCode)}">${escapeHtml(data.message || t('login.failed'))}</div>
        <div class="login-actions">
          <button class="login-modal-btn glass-control cfg-btn-primary" id="login-retry">${t('login.retry')}</button>
          <button class="login-modal-btn glass-control login-modal-btn-secondary" id="login-switch-account">${t('login.switchAccount')}</button>
          ${cleanupKey}
        </div>`;
    }
    modal.innerHTML = `
      <div class="login-modal-header">
        <h3>${t('login.title')}</h3>
        <button class="login-modal-close">&times;</button>
      </div>
      <div class="login-modal-body">${body}</div>`;
    modal.querySelector('.login-modal-close').onclick = close;
    modal.querySelector('#login-open-auth')?.addEventListener('click', openAuthPage);
    // Switch account (RP-logout fix, 2026-09-06): reserve the popup inside
    // THIS click gesture (same contract as the retry button below), then
    // restart the flow — startPkceLogin(true) sends prompt="login consent"
    // so Logto shows the account form instead of silently re-entering the
    // SSO-session account.
    modal.querySelector('#login-switch-account')?.addEventListener('click', () => {
      reservePopup(); // synchronous gesture reservation
      startFlow(true);
    });
    modal.querySelector('#login-retry')?.addEventListener('click', () => {
      reservePopup(); // synchronous gesture reservation for the retry
      startFlow();
    });
    // 「清理并重登」（缺陷 A / 上游 §8.2 第 6+7 项）：只在**本地凭据文件类**分类下渲染，
    // 点击 = 复用既有 RP end-session 链（本地拆除 + 续登同一窗），零新增链路。跳转必须在
    // 手势栈里同步发生（popup-blocker），故直接调 `openEndSessionHandoff(true)`。
    modal.querySelector('#login-cleanup')?.addEventListener('click', () => {
      close();
      openEndSessionHandoff(true);
    });
  };

  const startFlow = async (forceLogin = false, deferPopup = false) => {
    setPairing(true);
    finished = false;
    render('starting');
    const onSuccess = () => {
      finished = true;
      setPairing(false);
      render('success');
      // Keep the auto-flow guard armed past the self-close below (2026-09-15
      // OIDC fix): an auth-failure event arriving right after a success must
      // not start another flow now that the DOM guard is about to disarm.
      loginFlowGuardUntil = Date.now() + LOGIN_FLOW_GUARD_MS;
      setTimeout(() => { close(); refresh(); }, 1500);
    };
    const onError = (errMsg, payload = null) => {
      finished = true;
      setPairing(false);
      // 缺陷 A：把分类码一路带到 error 渲染面（「清理并重登」键的门控判据 +
      // `data-code` 二值断言契约）。
      render('error', { message: errMsg, code: payload?.code || '' });
    };
    try {
      // Primary path: Authorization Code + PKCE via the hosted Logto page.
      // forceLogin → prompt="login consent" (switch-account entry).
      const pkce = await startPkceLogin(forceLogin);
      if (pkce) {
        flowInfo = pkce;
        st.flowState = 'waiting';
        // Navigate the popup reserved in the click gesture, and let the result
        // decide the modal body: an opened hosted login page = this single
        // click already did everything (one-click entry); a refused popup =
        // the modal keeps the manual re-open button as the recovery.
        // `deferPopup` (no gesture at all): nothing was reserved, so nothing is
        // navigated and no "popup blocked" nag is shown — the panel's manual
        // button stays the gesture that opens the window.
        const popupOpened = deferPopup ? false : navigateReserved(pkce.authorizeUrl);
        render('waiting', { popupOpened });
        if (!deferPopup && !popupOpened) popupBlockedFallback(pkce.authorizeUrl);
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
      if (flowInfo.verificationUri && !navigateReserved(flowInfo.verificationUri)) {
        popupBlockedFallback(flowInfo.verificationUri);
      }
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
      navigateReserved(null); // release the reserved popup on failure
      render('error', { message: e.message || t('login.startFailed') });
    }
  };

  // Reserve the popup synchronously inside the click gesture stack — the
  // async startFlow below cannot open one without being blocked.
  // Arm the auto-flow guard on every flow start (2026-09-15 OIDC fix).
  // `opts.deferPopup` (switch-account watchdog, no gesture): skip the
  // reservation entirely — see openLoginModal's doc.
  loginFlowGuardUntil = Date.now() + LOGIN_FLOW_GUARD_MS;
  if (!opts.deferPopup) reservePopup();
  startFlow(!!opts.forceLogin, opts.deferPopup === true);
}

// ── State refresh → avatar styling ───────────────────────
// Dual-state decision (photo vs logo) + the failed-URL latch live in
// neblink.js (avatarViewState / noteAvatarFailure) — shared with the settings
// page avatar section so both entries can never drift apart.

async function refresh() {
  await fetchNeblinkStatus();
  renderAvatar();
}

function renderAvatar() {
  const avatar = document.getElementById('activity-avatar');
  if (!avatar) return;
  const st = getNeblinkState();
  const { url: validAvatarUrl, showPhoto } = avatarViewState();
  const logoEl = avatar.querySelector('.activity-avatar-logo');
  const photoEl = avatar.querySelector('.activity-avatar-photo');
  const letterEl = avatar.querySelector('.activity-avatar-letter');

  if (photoEl) {
    // Slot paint is shared with the settings account area and gated on the
    // photo being paintable (neblink.js paintAvatarSlot, 2026-09-15 flicker fix).
    paintAvatarSlot(photoEl, logoEl, validAvatarUrl, showPhoto, () => {
      noteAvatarFailure(validAvatarUrl); // latch: stop re-showing the broken image
      photoEl.hidden = true;
      if (logoEl) logoEl.hidden = false;
    });
  } else if (logoEl) {
    logoEl.hidden = showPhoto;
  }
  if (letterEl) letterEl.hidden = true; // account avatar replaces the letter

  // ── Logged-out slot form = the pre-existing dimmed logo (2026-09-16 回退) ──
  // 作者 2026-09-16 11:55 方向纠正（逐字）：「这个大绿色按钮是什么意思，我要让优化
  // 的是那个登陆的按钮，这个绿色的位置回到以前的logo。」
  // ⇒ 本槽位不再套主键皮（`logged-out` / `cfg-btn-primary` 两处 class 与
  //   `ensureLoginGlyph` 字形同批摘除，`sapphire.css` 侧的 `#activity-avatar
  //   .logged-out` 块与 `.login-glyph` 规则一并删除）。
  // ⇒ 未登录态 = `nav.css` 的既有基线（36px 圆 + 24px 灰化 logo，零本文件声明）；
  //   登录键族的真正交付面 = `.fm-login-btn`（消息 / 联系人未登录空态 + 两处重新
  //   登录键），由 `sapphire.css` 登录键族块承接。
  // 🔴 本槽位零改面：结构（photo / logo / letter 三节点 + paintAvatarSlot 门控）
  //   与 `paired` / `pairing` / `title` 全部保持原样。

  // State styling: paired (logged in) / pairing.
  avatar.classList.toggle('paired', !!st.loggedIn);
  avatar.classList.toggle('pairing', !!st.pairing);

  // Tooltip via i18n (this used to write Chinese literals, clobbering the
  // `activity-avatar → activity.login` mapping in i18n.js on every 10s refresh).
  avatar.title = st.pairing
    ? t('activity.pairing')
    : (st.loggedIn ? t('activity.profile') : t('activity.login'));
}
