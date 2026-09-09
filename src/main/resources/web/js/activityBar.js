// activityBar.js — Vertical icon strip to the left of #sidebar.
//
// Layout (top → bottom):
//   • Avatar — the NebLink login entry. Tap when logged out → opens the
//     self-drawn login wizard (loginWizard.js; the gateway BFF holds the
//     Logto interaction, so the browser renders zero auth.nebflow.space
//     pages). Tap when logged in →
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
import { fetchNeblinkStatus, getNeblinkState, avatarViewState, noteAvatarFailure } from './neblink.js';
import { openLoginWizard } from './loginWizard.js';
import { setUpdateDot } from './updateCheck.js';
import { createIconsIn } from './utils.js';
import { getProfileUrl } from './brand.js';
import { t } from './i18n.js';
import { key } from './branding.js';

let initialized = false;
let statusPollTimer = null;

export function initActivityBar() {
  if (initialized) return;
  initialized = true;

  initSidePanels();
  bindSettingsButton();
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
  // Friends release gating (2026-09-08, see featureFlags.js): default-off
  // posture — detach the Messages/Contacts entries now; main.js calls
  // enableFriendPanels() once the first configData proves the flag on.
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
function bindAvatar() {
  const avatar = document.getElementById('activity-avatar');
  if (!avatar) return;
  avatar.addEventListener('click', () => {
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
    } else {
      // Not logged in → open the self-drawn login wizard (E1, design §1.1).
      openLoginWizard(loginWizardCallbacks());
    }
  });
}

// ── Login wizard (self-drawn, plan A) ────────────────────
// 2026-09-09 design `20260909_login-selfdrawn-design.md` §2.2: the hosted
// Logto pages are GONE from the login path — the browser renders only this
// app's glass wizard (loginWizard.js) talking to the gateway BFF. The old
// hosted-page entries are absorbed:
//   E1 (this avatar entry)        → the wizard identifier step
//   E2 (重新打开登录页面)          → dissolved: there is no hosted page anymore
//   E3 (使用其他账号登录)          → dissolved: the wizard IS the account form
//   E4 (consent 同意屏)           → resolved server-side inside submit
// E5-E8 (register / forgot / MFA / social) are wizard steps in loginWizard.js.
// The legacy device flow (logto-not-configured) fallback lives there too.

/** Callbacks the wizard needs from this module: the avatar pairing spinner
 *  and the post-login refresh (fetchNeblinkStatus + renderAvatar). */
function loginWizardCallbacks() {
  return {
    onPairingChange: (v) => { getNeblinkState().pairing = v; renderAvatar(); },
    onLoginDone: () => { refresh(); },
  };
}

/**
 * Public entry for the NebLink login (used by the friends/messages panels'
 * logged-out empty states) — opens the self-drawn wizard.
 */
export function openLoginModal() {
  openLoginWizard(loginWizardCallbacks());
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
    photoEl.hidden = !showPhoto;
    photoEl.onerror = () => {
      noteAvatarFailure(validAvatarUrl); // latch: stop re-showing the broken image
      photoEl.hidden = true;
      if (logoEl) logoEl.hidden = false;
    };
    if (showPhoto && photoEl.src !== validAvatarUrl) photoEl.src = validAvatarUrl;
  }
  if (logoEl) logoEl.hidden = showPhoto;
  if (letterEl) letterEl.hidden = true; // account avatar replaces the letter

  // State styling: paired (logged in) / pairing / logged out.
  avatar.classList.toggle('paired', !!st.loggedIn);
  avatar.classList.toggle('pairing', !!st.pairing);
  avatar.title = st.pairing
    ? 'Pairing…'
    : (st.loggedIn ? '个人主页' : '登录');
}
