// sidebar.js — Left panel management: nav tabs, agent list, settings, session sidebar

import state, { LS_SESSIONS_KEY, LS_DRAFTS_KEY } from './state.js';
import { brand } from './brand.js';
import { key } from './branding.js';
import { sendWs, onMessage } from './ws.js';
// Lazy wrapper - P2-4 cycle cut (sidebar <-> modal): modal.js statically
// imports sidebar.js, so this module must not statically import modal.js.
function showBatchDeleteModalLazy() {
  import('./modal.js').then(({ showBatchDeleteModal }) => showBatchDeleteModal());
}
import { renderMarkdownWithMath, smartScroll, stopSpinner, createIconsIn } from './utils.js';
import { finishAgent, setStatus, renderToolPending, cancelThinkingRAF } from './chat.js';
import { restoreFromStorage, loadMsgs } from './persistence.js';
import { renderTaskList } from './taskList.js';
import { clearMemoryCache } from './memory.js';
import { chatViews, setActiveView, activeView } from './chatView.js';
import { cleanupCardIframes, resetCardAccumulator } from './cardRegistry.js';
import { t, getLocale, setLocale, getAvailableLocales } from './i18n.js';
import { fetchNeblinkStatus, neblinkSettingsHTML, bindNeblinkEvents, avatarViewState, noteAvatarFailure } from './neblink.js';
import { notifyManualUpdateCheck } from './updateCheck.js';
import { toggleHTML, setToggleState } from './toggle.js';
import { preloadModelCapabilities, renderVisionBadge } from './modelCapabilities.js';
import * as presets from './presets.js';
import { renderAppearanceSection, bindAppearanceEvents } from './orbSettingsUI.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';

// 2026-09-03 作者裁定：光球（micOrb）按预设驱动，设置页隐藏光球配置区。
// 仅 UI 门控——orbSettingsUI/orbPresets/micOrb 代码与配置读取逻辑全部保留，
// 用户本地已存自定义配置照常生效；翻回 true 即恢复配置区。
const ORB_SETTINGS_VISIBLE = false;

// 2026-09-05 作者裁定（设置页清理批②）：新手运行指导入口隐藏封存。与
// SIDEBAR_LEGACY_ENTRIES 同款 flag 形态——false = 渲染与绑定整体门控；
// 按钮/绑定代码与 i18n 键 settings.rerunOnboarding 保留不删（隐藏 ≠ 删除），
// 翻回 true 即原样回归。onboarding 本体与 /onboarding slash 命令零触碰。
const SHOW_RERUN_ONBOARDING = false;

const eyeSvg = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
const eyeOffSvg = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

// ---------- Active Folder (VSCode-style) ----------
export function setActiveFolder(folderId) {
  state.activeFolderId = folderId;
  // Update UI highlight
  document.querySelectorAll('.folder-item').forEach(el => {
    el.classList.toggle('active', el.dataset.folderId === folderId);
  });
}

export function clearActiveFolder() {
  state.activeFolderId = null;
  document.querySelectorAll('.folder-item.active').forEach(el => el.classList.remove('active'));
}

// ---------- Panel Switching ----------
// Only one sidebar panel remains (Sessions/Explorer). Settings is a modal;
// Agents is a Canvas tab.
export function showPanel(tab) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  const panel = document.getElementById('panel-' + tab);
  if (panel) panel.classList.add('active');
}

/**
 * Open the Settings modal: show the centered overlay, fetch fresh config,
 * and render. Shared by the Activity Bar settings button.
 */
export function openSettingsPanel() {
  document.getElementById('settings-overlay')?.classList.add('on');
  sendWs({type: 'getConfig'});
  sendWs({type: 'getToolResultTtl'}); // #341: fresh TTL echo every open
  renderSettings();
}

/** Close the Settings modal and stop its periodic NebLink refresh. */
export function closeSettingsPanel() {
  document.getElementById('settings-overlay')?.classList.remove('on');
  scheduleDraft = null;   // #334: closing the panel discards unsaved edits
  if (window._neblinkRefreshTimer) {
    clearInterval(window._neblinkRefreshTimer);
    window._neblinkRefreshTimer = null;
  }
}

/** Return whether the settings modal is currently shown. */
export function isSettingsPanelActive() {
  const overlay = document.getElementById('settings-overlay');
  return !!overlay && overlay.classList.contains('on');
}

// ---------- Session Switching ----------

/** Switch the main panel to display a different session. */
export function switchToSession(sessionId) {
  if (!sessionId) return;

  // Same-session: just clear unread and scroll to bottom
  if (sessionId === state.activeSessionId) {
    state.unreadSessions.delete(sessionId);
    state.markedUnreadSessions.delete(sessionId);
    persistUnread();
    persistMarkedUnread();
    updateSessionStatus(sessionId);
    renderSessionSidebar(state.sessions, state.activeSessionId);
    if (chatViews.primary?.dom?.chat) {
      chatViews.primary.dom.chat.scrollTop = chatViews.primary.dom.chat.scrollHeight;
    }
    return;
  }

  // Different session: save draft, switch, reset
  const prevId = state.activeSessionId;
  state.activeSessionId = sessionId;
  window.dispatchEvent(new CustomEvent('nebflow-session-change', { detail: { sessionId } }));
  setActiveView(chatViews.primary);
  saveInputDraft(prevId);
  resetChatForActiveSession();
  restoreInputDraft(sessionId);
  clearMemoryCache();
  // Card iframes die with the previous session's chat DOM — their pending
  // interaction accumulations are unreachable orphans (D5, mem-diag 20260907).
  resetCardAccumulator();

  // Restore folder context: highlight the session's parent folder
  const session = (state.sessions || []).find(s => s.id === sessionId);
  if (session?.folderId) {
    setActiveFolder(session.folderId);
  } else {
    clearActiveFolder();
  }

  // Clear unread for the newly active session
  state.unreadSessions.delete(sessionId);
  state.markedUnreadSessions.delete(sessionId);
  persistUnread();
  persistMarkedUnread();
  updateSessionStatus(sessionId);

  renderSessionSidebar(state.sessions, sessionId);
  // Sync header indicators
  if (typeof state.updateHeaderModelInfo === 'function') state.updateHeaderModelInfo();
  if (typeof state.updateBgTasksUI === 'function') state.updateBgTasksUI();
  if (typeof state.updateBgAgentIndicator === 'function') state.updateBgAgentIndicator();
  if (typeof state.updateBypassToggle === 'function') state.updateBypassToggle(chatViews.primary);
  // Close plan canvas if bound to a different session
  if (typeof state.onPlanSessionChange === 'function') state.onPlanSessionChange(sessionId);
}

export function initNavTabs() {
  // Settings lives in a centered modal (opened from the Activity Bar via
  // openSettingsPanel). Wire its close affordances here.
  const overlay = document.getElementById('settings-overlay');
  document.getElementById('settings-modal-close')?.addEventListener('click', closeSettingsPanel);
  overlay?.addEventListener('click', (e) => {
    if (e.target === overlay) closeSettingsPanel();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && isSettingsPanelActive()) closeSettingsPanel();
  });
}

// ---------- Agent icons in Nav Bar ----------
export function renderAgentList() {
  const list = document.getElementById('nav-agent-list');
  if (!list) return;
  list.innerHTML = '';
  // ⑧ Nebula (orchestrator) pinned first with a separator before the rest
  // (entity-icons-visual-spec §3.3). Nebula always renders the orbit icon —
  // its identity marker must stay stable, so custom avatars are ignored.
  const ordered = [...state.agentsData].sort((a, b) =>
    (b.name === 'Nebula' ? 1 : 0) - (a.name === 'Nebula' ? 1 : 0));
  ordered.forEach((a, idx) => {
    const isNebula = a.name === 'Nebula';
    const el = document.createElement('div');
    const isActive = state.selectedAgent === a.name;
    el.className = 'nav-agent' + (isActive ? ' active' : '') + (isNebula ? ' nav-agent-orchestrator' : '');
    const avatar = a.avatar || '';
    const displayName = a.displayName || a.name;
    el.dataset.name = a.name;
    el.title = displayName;
    const iconHtml = isNebula
      ? `<span class="nav-agent-icon"><i data-lucide="orbit"></i></span>`
      : avatar
        ? `<span class="nav-agent-icon">${avatar}</span>`
        : `<span class="nav-agent-icon agent-letter-icon">${escapeHtml(displayName.charAt(0).toUpperCase())}</span>`;
    el.innerHTML = `${iconHtml}<span class="nav-agent-label">${escapeHtml(displayName.slice(0, 6))}</span>`;
    el.addEventListener('click', () => selectAgent(a.name));
    el.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      sendWs({type: 'getAgentSystemPrompt', name: a.name});
    });
    list.appendChild(el);
    // Separator right after the orchestrator (only when agents follow)
    if (isNebula && ordered.length > 1) {
      const sep = document.createElement('div');
      sep.className = 'nav-agent-separator';
      list.appendChild(sep);
    }
  });
  createIconsIn(list);
  computeAgentStates();
}

// ---------- Agent State Animations ----------

/** Apply a visual state class to an agent nav element. */
function applyAgentState(agentName, stateClass) {
  const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
  if (!el) return;
  el.classList.remove('state-working', 'state-waiting', 'state-compressing', 'state-complete');
  if (stateClass !== 'idle') {
    el.classList.add('state-' + stateClass);
  }
}

/**
 * Recompute aggregate state for each agent based on all their sessions.
 * Priority order: waiting (attention) > working (busy) > compressing > idle.
 * Detects busy→idle transitions and triggers a brief 'complete' animation.
 */
export function computeAgentStates() {
  // Use sessionAgentMap (contains ALL sessions) instead of state.sessions (filtered by selectedAgent)
  const agentSessionIds = {};
  Object.entries(state.sessionAgentMap).forEach(([sid, agent]) => {
    if (!agentSessionIds[agent]) agentSessionIds[agent] = [];
    agentSessionIds[agent].push(sid);
  });

  // Ensure agents with no sessions still get 'idle'
  state.agentsData.forEach(a => {
    if (!agentSessionIds[a.name]) agentSessionIds[a.name] = [];
  });

  const newStates = {};

  Object.keys(agentSessionIds).forEach(agent => {
    const sids = agentSessionIds[agent];
    let hasAttention = false;
    let hasBusy = false;
    let hasCompacting = false;

    sids.forEach(sid => {
      if (state.attentionSessions.has(sid)) hasAttention = true;
      if (state.busySessionIds.has(sid)) hasBusy = true;
      if (state.compactingSessionIds.has(sid)) hasCompacting = true;
    });

    // Priority: attention > busy > compacting > idle
    if (hasAttention) {
      newStates[agent] = 'waiting';
    } else if (hasBusy) {
      newStates[agent] = 'working';
    } else if (hasCompacting) {
      newStates[agent] = 'compressing';
    } else {
      newStates[agent] = 'idle';
    }

    // Detect complete transition: was working, now idle
    const prevState = state.agentStates[agent];
    if (prevState === 'working' && newStates[agent] === 'idle') {
      newStates[agent] = 'complete';
      // Clear any existing timer
      if (state.agentStateTimers[agent]) {
        clearTimeout(state.agentStateTimers[agent]);
      }
      // Schedule return to idle after animation completes
      state.agentStateTimers[agent] = setTimeout(() => {
        applyAgentState(agent, 'idle');
        state.agentStates[agent] = 'idle';
      }, 600);
    }
  });

  // Apply state changes
  Object.keys(newStates).forEach(agent => {
    if (state.agentStates[agent] !== newStates[agent] || newStates[agent] === 'complete') {
      state.agentStates[agent] = newStates[agent];
      applyAgentState(agent, newStates[agent]);
    }
  });
}

/** Select an agent and load its sessions. */
export function selectAgent(agentName) {
  const isSame = state.selectedAgent === agentName;
  if (isSame) return;
  // Agent tab switches only filter the sidebar list.
  state.selectedAgent = agentName;
  // Clear unread count for this agent
  const prevCount = state.agentUnreadCounts[agentName] || 0;
  if (prevCount > 0) {
    state.agentUnreadCounts[agentName] = 0;
    const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
    if (el) { const dot = el.querySelector('.agent-notif-dot'); if (dot) dot.remove(); }
  }
  // Update nav bar active state
  document.querySelectorAll('#nav-agent-list .nav-agent').forEach(el => {
    el.classList.toggle('active', el.dataset.name === agentName);
  });
  // Exit batch mode and clear active folder when switching agents
  if (state.selectedSessionIds.size > 0) exitBatchMode();
  clearActiveFolder();
  // Load sessions for this agent
  sendWs({type: 'listAgentSessions', name: agentName});
}

// ---------- Settings Panel ----------
/**
 * Freeze-time (work-schedule) settings block — the toggle row + the segment
 * editor. Renders `scheduleDraft ?? state.workSchedule` — the local edit draft
 * takes precedence over the server echo while the user is editing. F2: first
 * enable with no saved segments defaults to one 09:00-12:00 segment.
 *
 * SEMANTICS (user ruling 2026-08-19 23:16): the config is the FREEZE window
 * (non-work hours) — a BLACKLIST. Agents park INSIDE these intervals and work
 * outside them. Segments may cross midnight (start > end, e.g. 23:00-08:00 =
 * frozen overnight); start == end is rejected (zero-length freeze window).
 *
 * EXPLICIT SAVE (user ruling 2026-08-20 #334): while the user is editing, every
 * interaction (toggle / add / remove / time input) only mutates this local
 * draft — NOTHING is sent to the server until the explicit Save button. The
 * freeze engine reads state.workSchedule (server truth), so an editing
 * mid-state can never trigger a freeze. The draft survives renderSettings()
 * re-runs (serverConfig echoes for unrelated settings would otherwise wipe
 * in-progress edits); closing the panel discards it (same as the STT config:
 * unsaved typing does not persist). null = no edits in flight → render the
 * server state.
 */
let scheduleDraft = null;

/** Snapshot the current editor state into the draft (local only, no wire). */
function markScheduleDirty() {
  const enabled = document.getElementById('toggle-schedule')?.classList.contains('on') ?? false;
  scheduleDraft = { enabled, segments: collectSegments() };
  updateScheduleActionRow();
}

/** Show/hide the save/reset action row + "unsaved edits" hint. The action row
 *  stays visible whenever a draft exists — even when the schedule was toggled
 *  OFF while dirty, so the user can still commit (Save) or abandon (Reset) the
 *  disable. */
function updateScheduleActionRow() {
  const dirty = scheduleDraft !== null;
  const row = document.getElementById('schedule-actions');
  const hint = document.getElementById('schedule-dirty-hint');
  if (row) row.style.display = dirty ? 'flex' : 'none';
  if (hint) hint.style.display = dirty ? 'block' : 'none';
}

function renderWorkScheduleSection() {
  const serverWs = state.workSchedule && typeof state.workSchedule === 'object'
    ? state.workSchedule : { enabled: false, segments: [] };
  const ws = scheduleDraft ?? serverWs;
  const enabled = !!ws.enabled;
  const segs = Array.isArray(ws.segments) ? ws.segments : [];
  const segRows = segs.map((s, i) => buildSegmentRowHtml(s, i)).join('');
  return `
    <div class="settings-row">
      <span class="settings-label">${t('settings.workSchedule')}</span>
      ${toggleHTML({ on: enabled, id: 'toggle-schedule', label: t('settings.workSchedule') })}
    </div>
    <div id="schedule-editor" style="display:${enabled ? 'block' : 'none'};padding:4px 0 8px;">
      <div class="segment-list" id="segment-list">${segRows}</div>
      <button class="cfg-btn cfg-btn-add" id="btn-add-segment">${t('settings.addSegment')}</button>
      <div class="cfg-hint">${t('settings.workScheduleOnHint')}</div>
    </div>
    <!-- Dirty hint + action row live OUTSIDE #schedule-editor so they stay
         visible even when the editor is display:none — a toggled-OFF draft
         must remain committable (Save) / abandonable (Reset). The segments
         themselves stay in the DOM (renderScheduleEditorRows rebuilds from
         the draft on re-enable), so nothing is cleared on toggle-off. -->
    <div class="cfg-hint" id="schedule-dirty-hint" style="display:${scheduleDraft !== null ? 'block' : 'none'};margin-top:6px;color:var(--color-text-muted)">${t('settings.scheduleDirtyHint')}</div>
    <div style="display:${scheduleDraft !== null ? 'flex' : 'none'};gap:8px;margin-top:8px" id="schedule-actions">
      <button class="cfg-btn cfg-btn-primary" id="btn-save-schedule">${t('settings.scheduleSave')}</button>
      <button class="cfg-btn" id="btn-reset-schedule">${t('settings.scheduleReset')}</button>
    </div>
    <div class="cfg-hint" id="schedule-off-hint" style="display:${enabled ? 'none' : 'block'};margin-top:-2px">${t('settings.workScheduleOffHint')}</div>`;
}

/**
 * One segment row — start/end HH:mm inputs + remove. A small sapphire hint is
 * shown when the segment crosses midnight (start > end): the freeze window
 * spans into the next day (blacklist semantics).
 */
function buildSegmentRowHtml(s, i) {
  const crossMidnight = HHMM_RE.test(s.start) && HHMM_RE.test(s.end)
    && toMin(s.start) > toMin(s.end);
  return `
    <div class="segment-row" data-seg-index="${i}">
      <span class="seg-label">${t('settings.segmentLabel', { n: i + 1 })}</span>
      <input class="time-input" value="${escapeHtml(s.start || '')}" data-role="start" autocomplete="off" spellcheck="false">
      <span class="seg-label">${t('settings.segmentTo')}</span>
      <input class="time-input" value="${escapeHtml(s.end || '')}" data-role="end" autocomplete="off" spellcheck="false">
      ${crossMidnight ? `<span class="seg-cross-midnight">${t('settings.segmentCrossMidnight')}</span>` : ''}
      <button class="seg-remove" title="${t('settings.removeSegment')}" aria-label="${t('settings.removeSegment')}">×</button>
    </div>`;
}

/**
 * STT (speech-to-text) settings block — collapsed "advanced" panel (user
 * ruling 2026-08-20: the free browser path is the default, STT is advanced).
 * Echoes state.stt (serverConfig). The apiKey is NEVER echoed back (backend
 * contract: serverConfig.stt = {sttConfigured, endpoint?, model?} only), so
 * the password input starts empty on every render — a "已配置" badge +
 * masked placeholder carry the configured state instead. Saving sends exactly
 * what is typed (empty apiKey = omit the field, keep the stored key); the
 * clear button sends all-empty = clear config (back to the free browser path).
 */

/** Session-memory expand state: reopening settings within the same session
 *  keeps the panel open; a fresh session starts collapsed (advanced option —
 *  not persisted to storage on purpose). */
let sttAdvanceExpanded = false;

/** Tool result TTL panel — same session-memory collapse semantics as STT
 *  advance (#341): collapsed by default, expanded state survives re-renders
 *  but not fresh sessions. */
let ttlAdvanceExpanded = false;

/** Backend defaults — mirrors ToolResultTtlConfig.parseStrict bounds. Used
 *  both for rendering before the first echo and for local pre-validation. */
const TTL_DEFAULTS = { enabled: false, ttlMinutes: 60, keepRecent: 5 };

/** True while a setToolResultTtl is in flight — lets the shared
 *  configUpdateFailed handler attribute the error to THIS panel (the same
 *  frame type is also emitted by workSchedule/STT saves, so without a guard
 *  a non-TTL failure would toast the TTL message). */
let ttlSavePending = false;
const TTL_BOUNDS = {
  ttlMinutes: { min: 1, max: 43200 },
  keepRecent: { min: 0, max: 200 },
};

/** Parse an integer input exactly like the backend parseStrict does — rejects
 *  empty/non-integer/float strings (backend accepts Int JSON only). */
function parseTtlInt(value) {
  const s = String(value).trim();
  if (!/^-?\d+$/.test(s)) return null;
  const n = Number(s);
  return Number.isSafeInteger(n) ? n : null;
}

/** Typed accessor for the TTL number inputs — getElementById returns
 *  HTMLElement (no .value/.disabled), so cast once here instead of at every
 *  call site (checkJs-clean). */
/** @param {string} id @returns {HTMLInputElement|null} */
function ttlInput(id) {
  return /** @type {HTMLInputElement|null} */ (document.getElementById(id));
}

/**
 * Tool result TTL settings block (#341 frontend tail) — collapsed advanced
 * panel mirroring the STT advance pattern. Echoes state.toolResultTtl
 * (fetched via getToolResultTtl; refreshed by toolResultTtl/toolResultTtlSaved
 * frames). EXPLICIT SAVE (#334 ruling applied to all runtime-behavior config):
 * edits stay in the DOM until the Save button; closing the panel discards
 * them; nothing reaches the freeze engine mid-edit. enabled=false greys the
 * three number inputs.
 */
function renderTtlSection() {
  const cfg = state.toolResultTtl && typeof state.toolResultTtl === 'object'
    ? state.toolResultTtl : TTL_DEFAULTS;
  const enabled = !!cfg.enabled;
  const rowHtml = (labelKey, hintKey, id, value) => `
      <div class="cfg-form-group">
        <label class="cfg-label" for="${id}">${t(labelKey)}</label>
        <input class="cfg-input" id="${id}" type="number" inputmode="numeric" value="${Number(value)}" ${enabled ? '' : 'disabled'} autocomplete="off">
        <div class="cfg-hint">${t(hintKey)}</div>
      </div>`;
  return `
    <div class="settings-row stt-advance-toggle-row">
      <button type="button" class="settings-collapse-toggle" id="ttl-advance-toggle"
              aria-expanded="${ttlAdvanceExpanded}" aria-controls="ttl-advance-body">
        <span class="settings-label">${t('settings.ttlAdvanceTitle')}</span>
        <span class="settings-collapse-chevron" aria-hidden="true"></span>
      </button>
    </div>
    <div class="settings-collapse-body" id="ttl-advance-body" ${ttlAdvanceExpanded ? '' : 'hidden'}>
      <div class="settings-row">
        <span class="settings-label">${t('settings.ttlEnabled')}</span>
        ${toggleHTML({ on: enabled, id: 'toggle-ttl-enabled', label: t('settings.ttlEnabled') })}
      </div>
      <div class="cfg-hint">${t('settings.ttlEnabledHint')}</div>
      ${rowHtml('settings.ttlMinutesLabel', 'settings.ttlMinutesHint', 'ttl-minutes', cfg.ttlMinutes ?? TTL_DEFAULTS.ttlMinutes)}
      ${rowHtml('settings.keepRecentLabel', 'settings.keepRecentHint', 'ttl-keep-recent', cfg.keepRecent ?? TTL_DEFAULTS.keepRecent)}
      <div style="display:flex;gap:8px;margin-top:8px">
        <button class="cfg-btn cfg-btn-primary" id="btn-save-ttl">${t('settings.ttlSave')}</button>
      </div>
    </div>`;
}

/** Re-render just the TTL section in place after a WS echo (settings open).
 *  Elements are rebuilt, so listeners bound by bindTtlEvents are re-attached
 *  to fresh nodes (old ones GC) — no accumulation. */
function refreshTtlSectionDom() {
  const wrap = document.getElementById('ttl-section-wrap');
  if (!wrap) return; // settings panel not open
  wrap.innerHTML = renderTtlSection();
  bindTtlEvents();
}

function bindTtlEvents() {
  // Expand/collapse — session-memory state, same pattern as STT advance.
  document.getElementById('ttl-advance-toggle')?.addEventListener('click', () => {
    ttlAdvanceExpanded = !ttlAdvanceExpanded;
    const body = document.getElementById('ttl-advance-body');
    const toggle = document.getElementById('ttl-advance-toggle');
    if (body) body.hidden = !ttlAdvanceExpanded;
    if (toggle) toggle.setAttribute('aria-expanded', String(ttlAdvanceExpanded));
  });

  // Enabled switch — toggles the .on class and the two number inputs'
  // disabled state in place (#341: enabled=false → inputs greyed). Local
  // only: nothing reaches the backend until the explicit Save button (#334
  // ruling — editing mid-states must never affect runtime behavior).
  const sw = document.getElementById('toggle-ttl-enabled');
  if (sw) {
    // Shared nb-toggle component (js/toggle.js): a real <button>, so Space and
    // Enter produce native clicks — setToggleState keeps class + aria-checked
    // in sync (the old div needed manual keydown wiring; removed to avoid
    // double-toggle).
    const flip = () => {
      const on = !sw.classList.contains('on');
      setToggleState(sw, on);
      ['ttl-minutes', 'ttl-keep-recent'].forEach(id => {
        const inp = ttlInput(id);
        if (inp) inp.disabled = !on;
      });
    };
    sw.addEventListener('click', flip);
  }

  // Explicit save (#334 semantics) — local STRICT pre-validation mirrors the
  // backend parseStrict bounds exactly (ttlMinutes 1–43200, keepRecent 0–200,
  // integers only) so a typo is blocked client-side with an actionable toast
  // instead of a round-trip to configUpdateFailed. The payload is always the
  // FULL config (three fields mandatory — the backend replaces the whole node,
  // there is no merge). minChars was removed by author ruling 2026-09-01
  // (over-design); the backend ignores the field if an old config still
  // carries it.
  document.getElementById('btn-save-ttl')?.addEventListener('click', () => {
    const enabled = document.getElementById('toggle-ttl-enabled')?.classList.contains('on') ?? false;
    const values = {
      ttlMinutes: parseTtlInt(ttlInput('ttl-minutes')?.value),
      keepRecent: parseTtlInt(ttlInput('ttl-keep-recent')?.value),
    };
    const fieldKey = {
      ttlMinutes: 'settings.ttlMinutesLabel',
      keepRecent: 'settings.keepRecentLabel',
    };
    for (const [name, raw] of Object.entries(values)) {
      if (raw === null) {
        window.__showToast?.(t('settings.ttlInvalidInt', { field: t(fieldKey[name]) }), 'error');
        return;
      }
      const { min, max } = TTL_BOUNDS[name];
      if (raw < min || raw > max) {
        window.__showToast?.(t('settings.ttlOutOfRange', { field: t(fieldKey[name]), min, max }), 'error');
        return;
      }
    }
    sendWs({
      type: 'setToolResultTtl',
      config: { enabled, ttlMinutes: values.ttlMinutes, keepRecent: values.keepRecent },
    });
    ttlSavePending = true; // configUpdateFailed attribution window
  });
}

// #341 echo: getToolResultTtl response — authoritative config into state +
// in-place refresh (no full renderSettings: unrelated drafts must survive).
onMessage('toolResultTtl', (msg) => {
  if (msg.config && typeof msg.config === 'object') state.toolResultTtl = msg.config;
  refreshTtlSectionDom();
});

// #341: setToolResultTtl success — same authoritative refresh + a success
// toast (save is NOT optimistic: the UI only confirms after the backend ack).
onMessage('toolResultTtlSaved', (msg) => {
  ttlSavePending = false;
  if (msg.config && typeof msg.config === 'object') state.toolResultTtl = msg.config;
  refreshTtlSectionDom();
  window.__showToast?.(t('settings.ttlSaved'), 'success');
});

// configUpdateFailed has NO global consumer (main.js only handles the
// configUpdated success flag) — surface backend validation errors as an error
// toast here. The same frame is emitted by workSchedule/STT saves too, so
// attribute the message to the TTL panel only while a TTL save is in flight;
// otherwise fall back to the generic copy (chat.configUpdateFailed) so other
// save paths never fail silently in the console.
onMessage('configUpdateFailed', (msg) => {
  const detail = typeof msg.message === 'string' && msg.message ? `: ${msg.message}` : '';
  const prefix = ttlSavePending ? t('settings.ttlSaveFailed') : t('chat.configUpdateFailed');
  ttlSavePending = false;
  window.__showToast?.(prefix + detail, 'error');
});

function renderSttSection() {
  const stt = state.stt && typeof state.stt === 'object' ? state.stt : {};
  const configured = !!stt.sttConfigured;
  const endpoint = stt.endpoint || '';
  const model = stt.model || '';
  const status = configured
    ? t('settings.sttConfiguredStatus', { endpoint: escapeHtml(endpoint), model: escapeHtml(model) })
    : t('settings.sttUnconfiguredStatus');
  return `
    <div class="settings-row stt-advance-toggle-row">
      <button type="button" class="settings-collapse-toggle" id="stt-advance-toggle"
              aria-expanded="${sttAdvanceExpanded}" aria-controls="stt-advance-body">
        <span class="settings-label">${t('settings.sttAdvanceTitle')}</span>
        <span class="settings-collapse-chevron" aria-hidden="true"></span>
      </button>
      <span class="cfg-hint stt-status" id="stt-status-hint">${status}</span>
    </div>
    <div class="settings-collapse-body" id="stt-advance-body" ${sttAdvanceExpanded ? '' : 'hidden'}>
      <div class="cfg-form-group">
        <label class="cfg-label" for="stt-endpoint">${t('settings.sttEndpoint')}</label>
        <input class="cfg-input" id="stt-endpoint" type="text" value="${escapeHtml(endpoint)}" placeholder="https://api.example.com/v1/audio/transcriptions" autocomplete="off" spellcheck="false">
        <label class="cfg-label" for="stt-model">${t('settings.sttModel')}</label>
        <input class="cfg-input" id="stt-model" type="text" value="${escapeHtml(model)}" placeholder="${t('settings.sttModelPlaceholder')}" autocomplete="off" spellcheck="false">
        <label class="cfg-label" for="stt-apikey">${t('settings.sttApiKey')}
          ${configured ? `<span class="stt-key-configured">${t('settings.sttKeyConfigured')}</span>` : ''}
        </label>
        <input class="cfg-input" id="stt-apikey" type="password" value="" placeholder="${t(configured ? 'settings.sttApiKeyConfiguredPlaceholder' : 'settings.sttApiKeyPlaceholder')}" autocomplete="off">
        <div class="cfg-hint">${t('settings.sttHint')}</div>
        <div style="display:flex;gap:8px;margin-top:8px">
          <button class="cfg-btn cfg-btn-primary" id="btn-save-stt">${t('settings.sttSave')}</button>
          <button class="cfg-btn" id="btn-clear-stt">${t('settings.sttClear')}</button>
        </div>
      </div>
    </div>`;
}

/**
 * Collect segments currently in the editor (all rows, raw values).
 */
function collectSegments() {
  const rows = document.querySelectorAll('#segment-list .segment-row');
  return Array.from(rows).map(row => ({
    start: row.querySelector('[data-role="start"]')?.value.trim() || '',
    end: row.querySelector('[data-role="end"]')?.value.trim() || ''
  }));
}

/** Rebuild the segment rows (used for the F2 default on first enable). */
function renderScheduleEditorRows(segs) {
  const list = document.getElementById('segment-list');
  if (!list) return;
  list.innerHTML = segs.map((s, i) => buildSegmentRowHtml(s, i)).join('');
}

const HHMM_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
function toMin(hhmm) { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m; }

/**
 * Blacklist validation contract (user ruling 2026-08-19 23:16): HH:mm format;
 * start == end → rejected (zero-length freeze window is meaningless); start > end
 * → LEGAL (crosses midnight, freeze window spans into the next day). Overlap
 * allowed — backend takes the union.
 * Returns null when valid, else an i18n error key resolved to text.
 */
function validateSegments(segs) {
  for (const s of segs) {
    if (!HHMM_RE.test(s.start) || !HHMM_RE.test(s.end)) return t('settings.scheduleInvalidTime');
    const sm = toMin(s.start);
    const em = toMin(s.end);
    if (sm === em) return t('settings.scheduleInvalidRange');
  }
  return null;
}

/** In-place toggle of a row's cross-midnight hint (no rebuild → no focus loss
 *  while the user clicks from the start input into the end input). */
function updateSegmentRowHint(row) {
  if (!row) return;
  const start = row.querySelector('[data-role="start"]')?.value.trim() || '';
  const end = row.querySelector('[data-role="end"]')?.value.trim() || '';
  const cross = HHMM_RE.test(start) && HHMM_RE.test(end) && toMin(start) > toMin(end);
  let hint = row.querySelector('.seg-cross-midnight');
  if (cross && !hint) {
    hint = document.createElement('span');
    hint.className = 'seg-cross-midnight';
    hint.textContent = t('settings.segmentCrossMidnight');
    row.insertBefore(hint, row.querySelector('.seg-remove'));
  } else if (!cross && hint) {
    hint.remove();
  }
}

/**
 * Validate + persist. Local validation only guards the wire; the backend
 * re-validates (fail-safe → disabled, configUpdateFailed on bad payload).
 * Returns true when the frame was sent, false on validation failure — the
 * caller keeps the draft on failure so the user can fix and retry.
 */
function saveWorkSchedule(enabled, segs, opts) {
  if (enabled && (!Array.isArray(segs) || segs.length === 0)) {
    window.__showToast?.(t('settings.scheduleEmpty'), 'error');
    return false;
  }
  // Segment validation only matters when the freeze is ENABLED (a broken freeze
  // window would misbehave). When DISABLING, the segments are just preserved for
  // later re-enable (#337/#⑪G) and must never block a pure toggle-off — e.g. a
  // stale/in-progress segment edit must not stop the user turning the freeze off.
  if (enabled) {
    const err = validateSegments(segs);
    if (err) { window.__showToast?.(err, 'error'); return false; }
  }
  sendWs({ type: 'setWorkSchedule', workSchedule: { enabled, segments: segs } });
  if (opts && opts.toast) window.__showToast?.(t('settings.scheduleSaved'), 'success');
  return true;
}

/**
 * Nearest effort level for the persisted thinking config (#345) — used only
 * for dropdown echo. Boundaries mirror OpenAiAdapter.budgetToEffort so the
 * displayed level always equals what the adapter would send upstream.
 */
function effortFromConfig() {
  if (!state.thinkingMode?.enabled) return 'off';
  const b = state.thinkingMode.budgetTokens ?? 32000;
  if (b <= 2048) return 'low';
  if (b <= 8192) return 'medium';
  return 'high'; // covers ≤32768 and legacy >32768 (xhigh collapsed to high)
}

export function renderSettings() {
  const content = document.getElementById('settings-content');
  const cfg = state.parsedConfig || {};
  const llm = cfg.llm || {};
  const providers = llm.providers || {};
  const providerNames = Object.keys(providers);
  // Build language selector options
  const locales = getAvailableLocales();
  const localeLabels = { 'zh-CN': '中文', en: 'English' };
  const langOpts = locales.map(code =>
    `<option value="${code}" ${code === getLocale() ? 'selected' : ''}>${localeLabels[code] || code}</option>`
  ).join('');

  // 2026-09-06 作者裁定：账号区与设备互联统一为一个区块——设备属于账号，
  // 登录态头像下方直接放设备列表，未登录态只有 logo + 一句「不可用」说明，
  // 不再有独立的 neblink settings-section。
  content.innerHTML = `
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.account')}</div>
      <button class="settings-avatar-entry" id="settings-avatar-entry" type="button">
        <span class="settings-avatar-frame">
          <picture>
            <source media="(prefers-color-scheme: dark)" srcset="css/logo-dark-4.png">
            <img class="settings-avatar-logo" src="css/logo-bright-4.png" alt="">
          </picture>
          <img class="settings-avatar-photo" alt="" hidden>
        </span>
      </button>
      ${neblinkSettingsHTML()}
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.runtime')}</div>
      <div class="settings-row">
        <span class="settings-label">${t('settings.thinkingMode')}</span>
        <select class="cfg-select" id="cfg-thinking-effort" style="width:auto">
          ${['off','low','medium','high'].map(lvl => `<option value="${lvl}"${effortFromConfig() === lvl ? ' selected' : ''}>${t('settings.thinkingEffort.' + lvl)}</option>`).join('')}
        </select>
      </div>
      <div class="cfg-hint">${t('settings.thinkingEffortHint')}</div>
      <div class="settings-row">
        <span class="settings-label">${t('settings.llmLog')}</span>
        ${toggleHTML({ on: state.llmLogEnabled !== false, id: 'toggle-llm-log', label: t('settings.llmLog') })}
      </div>
      ${renderWorkScheduleSection()}
      ${renderSttSection()}
      <div id="ttl-section-wrap">${renderTtlSection()}</div>
      <div class="settings-row">
        <span class="settings-label">${t('settings.language')}</span>
        <select class="cfg-select" id="cfg-language" style="width:auto">${langOpts}</select>
      </div>
      <div class="settings-row">
        <span class="settings-label">${t('settings.autostart')}</span>
        ${toggleHTML({ on: !!(state.autostartStatus && state.autostartStatus.enabled), id: 'toggle-autostart', label: t('settings.autostart'), disabled: !!(state.autostartStatus && !state.autostartStatus.supported) })}
      </div>
      <div class="cfg-hint" id="autostart-hint" style="display:${state.autostartStatus && !state.autostartStatus.supported ? 'block' : 'none'};margin-top:-4px">${escapeHtml(state.autostartStatus?.reason || t('settings.autostartUnsupported'))}</div>
    </div>
    ${ORB_SETTINGS_VISIBLE ? `
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.appearance')}</div>
      ${renderAppearanceSection()}
    </div>` : ''}
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.providers')}</div>
      <div id="provider-list">
        ${providerNames.map(name => renderProviderCard(name, providers[name])).join('')}
      </div>
      <button class="cfg-btn cfg-btn-add" id="btn-add-provider">${t('settings.addProvider')}</button>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.presets')}</div>
      <div id="preset-migrate-banner" style="display:none"></div>
      <div id="preset-list"><div class="cfg-empty">Loading…</div></div>
      <button class="cfg-btn cfg-btn-add" id="btn-add-preset">${t('settings.addPreset')}</button>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.advanced')}</div>
      <button class="cfg-btn" id="btn-toggle-json">${t('settings.editRawJson')}</button>
      ${SHOW_RERUN_ONBOARDING ? `<button class="cfg-btn" id="btn-rerun-onboarding" style="margin-left:8px">${t('settings.rerunOnboarding')}</button>` : ''}
    </div>
    <div class="settings-section" id="json-editor-section" style="display:${state.settingsShowJson ? 'block' : 'none'}">
      <div class="config-editor-wrap">
        <textarea id="config-editor" spellcheck="false">${escapeHtml(state.configText)}</textarea>
        <div class="config-actions">
          <button class="btn-save" id="btn-save-config">${t('settings.save')}</button>
          <button id="btn-reload-config">${t('settings.reload')}</button>
        </div>
      </div>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.about')}</div>
      <div class="about-info">
        <div>${brand.productName} v${state.serverVersion || '...'}</div>
        <div style="margin-top:4px;font-size:12px;color:var(--color-text-secondary)">${t('settings.connection')}: <span style="color:${state.connected ? '#4caf50' : '#f44336'}">${state.connected ? t('settings.connected') : t('settings.disconnected')}</span></div>
        <div style="margin-top:10px">
          <button class="cfg-btn cfg-btn-sm" id="btn-check-update">${t('settings.checkUpdate')}</button>
          <span id="update-status" style="margin-left:8px;font-size:12px;color:var(--color-text-secondary)"></span>
        </div>
        <div id="update-action" style="display:${state.updateAvailable ? 'block' : 'none'};margin-top:8px">
          <button class="cfg-btn cfg-btn-primary" id="btn-do-update">${t('settings.updateNow')}</button>
          <button class="cfg-btn cfg-btn-sm" id="btn-dismiss-update" style="margin-left:6px">${t('settings.updateLater')}</button>
        </div>
      </div>
    </div>`;

  // Silent auto checks (updateCheck.js) record their outcome in state so a
  // panel (re)render shows the same "update available" detail the manual
  // path would — same chain, one source of truth.
  if (state.updateAvailable) {
    const statusEl = document.getElementById('update-status');
    if (statusEl) statusEl.textContent = t('settings.updateAvailable', { version: state.latestVersion });
  }

  renderSettingsAvatar();
  bindSettingsEvents(content, cfg);
  bindNeblinkEvents(() => renderSettings());

  // Async-load the preset management section (non-blocking)
  loadPresetsSection();

  // Pre-fetch model capabilities, then refresh badges on provider cards
  preloadModelCapabilities(() => {
    const providerList = document.getElementById('provider-list');
    if (providerList) {
      providerList.querySelectorAll('.cfg-model-vision').forEach(el => {
        const ref = el.closest('.cfg-model-row')?.dataset.modelRef;
        if (ref) el.innerHTML = renderVisionBadge(ref);
      });
    }
  });

  // Refresh neblink peers periodically while settings panel is open
  const refreshNeblink = () => {
    fetchNeblinkStatus().then(() => {
      // Match both states: logged in (.neblink-logged-in) and logged out
      // (.neblink-login-section) so login/logout swaps render correctly.
      const neblinkDiv = content.querySelector('.neblink-logged-in, .neblink-login-section');
      if (neblinkDiv && document.getElementById('settings-content').contains(neblinkDiv)) {
        const wrapper = document.createElement('div');
        wrapper.innerHTML = neblinkSettingsHTML();
        neblinkDiv.replaceWith(wrapper.firstElementChild);
        bindNeblinkEvents(() => renderSettings());
      }
      // Keep the avatar section in lockstep with login/avatar changes.
      renderSettingsAvatar();
    });
  };
  refreshNeblink();
  if (window._neblinkRefreshTimer) clearInterval(window._neblinkRefreshTimer);
  window._neblinkRefreshTimer = setInterval(refreshNeblink, 3000);
}

function renderProviderCard(name, p) {
  // Deleted providers are stored as null — render nothing instead of crashing
  // the settings re-render (remove flow calls renderSettings synchronously).
  if (!p) return '';
  const modelCount = (p.models || []).length;
  const modelsHtml = (p.models || []).map(m => {
    const ref = `${name}/${m.id}`;
    return `<div class="cfg-model-row" data-model-ref="${escapeHtml(ref)}">
      <span class="cfg-model-name">${escapeHtml(m.id)}</span>
      <div class="cfg-model-vision">${renderVisionBadge(ref)}</div>
    </div>`;
  }).join('');

  return `
    <div class="cfg-card" data-provider="${escapeHtml(name)}">
      <div class="cfg-card-header">
        <span class="cfg-card-title">${escapeHtml(name)}</span>
        <button class="cfg-card-remove" data-provider="${escapeHtml(name)}" title="${t('provider.remove')}">×</button>
      </div>
      <div class="cfg-card-meta">
        <span class="cfg-card-badge">${escapeHtml((p.protocol || '').toUpperCase())}</span>
        <span class="cfg-card-sub">${modelCount} model${modelCount !== 1 ? 's' : ''}</span>
      </div>
      ${modelsHtml ? `<div class="cfg-model-list">${modelsHtml}</div>` : ''}
    </div>`;
}

// ── Settings avatar section (09-05 五项裁定④; 2026-09-06 修整: 头像-only) ──
// Dual state driven by the SAME decision as the Activity Bar avatar
// (neblink.js avatarViewState): logged in with a usable account avatar →
// photo; otherwise the product logo. The entry renders the centered avatar
// only — all in-area text removed (author 2026-09-06). Click behavior is not
// reimplemented: the entry forwards to #activity-avatar's native click
// (activityBar.js bindAvatar — login modal when logged out, profile page when
// logged in), so both entries stay byte-identical by construction.
function renderSettingsAvatar() {
  const entry = document.getElementById('settings-avatar-entry');
  if (!entry) return;
  const { url: validAvatarUrl, showPhoto } = avatarViewState();
  const logoEl = entry.querySelector('.settings-avatar-logo');
  const photoEl = entry.querySelector('.settings-avatar-photo');
  if (photoEl) {
    photoEl.hidden = !showPhoto;
    photoEl.onerror = () => {
      noteAvatarFailure(validAvatarUrl); // shared failed-URL latch
      const view = avatarViewState();
      if (photoEl) photoEl.hidden = true;
      if (logoEl) logoEl.hidden = view.showPhoto;
    };
    if (showPhoto && photoEl.getAttribute('src') !== validAvatarUrl) photoEl.setAttribute('src', validAvatarUrl);
  }
  if (logoEl) logoEl.hidden = showPhoto;
}

// ---------- Preset management section (P3) ----------

/** Reverse-lookup: which agents reference a given preset name. */
function presetUsedBy(agentsMap, presetName) {
  return Object.entries(agentsMap || {})
    .filter(([, v]) => v === presetName)
    .map(([k]) => k)
    .sort();
}

function renderPresetCard(p, defaultPreset, agentsMap) {
  const isDefault = p.name === defaultPreset;
  const usedBy = presetUsedBy(agentsMap, p.name);
  const chain = [p.preferred, ...(p.fallbacks || [])].filter(Boolean);
  const usedByHtml = usedBy.length > 0
    ? `<span class="preset-card-agents" title="${escapeHtml(usedBy.join(', '))}">${t('preset.usedBy', { n: usedBy.length })}</span>`
    : `<span class="preset-card-agents">${t('preset.usedByNone')}</span>`;
  return `
    <div class="cfg-card preset-card" data-preset="${escapeHtml(p.name)}">
      <div class="cfg-card-header">
        <span class="cfg-card-title">${escapeHtml(p.name)}</span>
        ${isDefault ? `<span class="preset-default-badge">${t('preset.default')}</span>` : ''}
      </div>
      <div class="preset-card-body">
        ${p.description ? `<div class="preset-card-desc">${escapeHtml(p.description)}</div>` : ''}
        ${presets.presetChainHtml(chain, '')}
        <div class="preset-card-footer">
          ${usedByHtml}
          <span class="preset-card-actions">
            <button class="cfg-btn cfg-btn-sm" data-action="default" ${isDefault ? 'disabled' : ''}>${t('preset.setDefault')}</button>
            <button class="cfg-btn cfg-btn-sm" data-action="edit">${t('preset.edit')}</button>
            <button class="cfg-btn cfg-btn-sm" data-action="delete" ${isDefault ? 'disabled' : ''}>${t('preset.delete')}</button>
          </span>
        </div>
      </div>
    </div>`;
}

/** Load presets + agent mapping, render cards, bind section events. */
async function loadPresetsSection() {
  const listEl = document.getElementById('preset-list');
  if (!listEl) return; // settings panel not open

  const data = await presets.fetchPresets();
  if (!document.getElementById('preset-list')) return; // panel closed while fetching
  if (!data) {
    listEl.innerHTML = `<div class="cfg-empty">${t('preset.loadFailed')}</div>`;
    return;
  }

  const presetList = data.presets || [];
  const defaultPreset = data.defaultPreset || '';
  const agentsMap = data.agents || {};

  listEl.innerHTML = presetList.length > 0
    ? presetList.map(p => renderPresetCard(p, defaultPreset, agentsMap)).join('')
    : `<div class="cfg-empty">${t('preset.empty')}</div>`;

  // Card action buttons (delegation on the list container)
  listEl.onclick = async (e) => {
    const btn = e.target.closest('button[data-action]');
    if (!btn || btn.disabled) return;
    const card = btn.closest('.preset-card');
    const name = card?.dataset.preset;
    const preset = presetList.find(p => p.name === name);
    if (!preset) return;

    if (btn.dataset.action === 'default') {
      try {
        await presets.setDefaultPreset(name);
        loadPresetsSection();
      } catch (err) { window.__showToast?.(err.message, 'error'); }
    } else if (btn.dataset.action === 'edit') {
      showPresetModal(preset, () => loadPresetsSection());
    } else if (btn.dataset.action === 'delete') {
      const usedBy = presetUsedBy(agentsMap, name);
      const msg = usedBy.length > 0
        ? t('preset.deleteConfirm', { name, n: usedBy.length })
        : t('preset.deleteConfirmNone', { name });
      window.__showConfirm?.(t('preset.deleteTitle'), msg, async () => {
        try {
          await presets.deletePreset(name);
          loadPresetsSection();
        } catch (err) { window.__showToast?.(err.message, 'error'); }
      });
    }
  };

  // Add-preset button (recreated each renderSettings — bind here)
  const addBtn = document.getElementById('btn-add-preset');
  if (addBtn) addBtn.onclick = () => showPresetModal(null, () => loadPresetsSection());

  // Legacy migration banner → P5 migration dialog
  presets.detectLegacyAgents().then(legacy => {
    const banner = document.getElementById('preset-migrate-banner');
    if (!banner) return;
    if (legacy.length === 0) { banner.style.display = 'none'; return; }
    banner.style.display = '';
    banner.innerHTML = `
      <div class="preset-migrate-inner">
        <span class="preset-migrate-text">⚠ ${t('preset.migrateBanner', { n: legacy.length })}</span>
        <button class="cfg-btn cfg-btn-sm" id="btn-migrate-legacy">${t('preset.migrateAction')}</button>
      </div>`;
    banner.querySelector('#btn-migrate-legacy')?.addEventListener('click', () => {
      showMigrationDialog(legacy, () => loadPresetsSection());
    });
  });
}

/**
 * Legacy migration dialog (P5). Stage 1: local preview grouped by config
 * fingerprint. Stage 2: POST /api/presets/migrate-legacy, then refresh.
 */
async function showMigrationDialog(legacyAgents, onDone) {
  document.getElementById('cfg-modal')?.remove();

  const overlay = document.createElement('div');
  overlay.id = 'cfg-modal';
  overlay.className = 'cfg-modal-overlay';
  overlay.innerHTML = `
    <div class="cfg-modal">
      <div class="cfg-modal-title">${t('preset.migrateTitle')}</div>
      <div class="cfg-modal-body" id="preset-migrate-body">
        <div class="cfg-empty">Loading…</div>
      </div>
      <div class="cfg-modal-actions">
        <button class="cfg-btn cfg-btn-cancel" id="cfg-modal-cancel">${t('modal.cancel')}</button>
        <button class="cfg-btn cfg-btn-save" id="preset-migrate-run">${t('preset.migrateExecute')}</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  const close = () => overlay.remove();
  overlay.querySelector('#cfg-modal-cancel').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

  // Stage 1 — preview (local computation; existing names needed for mig-<n>)
  const body = overlay.querySelector('#preset-migrate-body');
  const presetData = await presets.fetchPresets();
  if (!overlay.isConnected) return;
  const groups = presets.previewMigration(legacyAgents, presetData);
  const newCount = groups.filter(g => !g.reused).length;

  body.innerHTML = `
    <div class="preset-migrate-summary">${t('preset.migrateSummary', { agents: legacyAgents.length, presets: newCount })}</div>
    ${groups.map(g => `
      <div class="preset-migrate-group">
        <div class="preset-migrate-group-head">
          <span class="preset-migrate-group-name">「${escapeHtml(g.presetName)}」</span>
          ${g.reused ? `<span class="preset-migrate-reuse">${t('preset.migrateReuse')}</span>` : ''}
          <span class="preset-migrate-group-arrow">←</span>
          <span class="preset-migrate-group-agents">${escapeHtml(g.agents.join(', '))}</span>
        </div>
        ${presets.presetChainHtml([g.preferred, ...g.fallbacks].filter(Boolean), '')}
      </div>`).join('')}
    <div class="preset-migrate-note">${t('preset.migrateNote')}</div>
    <div class="preset-migrate-error" id="preset-migrate-error" style="display:none"></div>`;

  // Stage 2 — execute
  const runBtn = overlay.querySelector('#preset-migrate-run');
  runBtn.addEventListener('click', async () => {
    runBtn.disabled = true;
    overlay.querySelector('#cfg-modal-cancel').disabled = true;
    runBtn.textContent = t('preset.migrateRunning');
    const errEl = overlay.querySelector('#preset-migrate-error');
    errEl.style.display = 'none';
    try {
      const result = await presets.migrateLegacy(legacyAgents.map(a => a.name));
      const migrated = result?.migratedAgents?.length ?? legacyAgents.length;
      close();
      window.__showToast?.(t('preset.migrateDone', { n: migrated }), 'success');
      onDone?.();
    } catch (err) {
      errEl.textContent = `${t('preset.migrateFailed')}: ${err.message}`;
      errEl.style.display = '';
      runBtn.disabled = false;
      overlay.querySelector('#cfg-modal-cancel').disabled = false;
      runBtn.textContent = t('preset.migrateExecute');
    }
  });
}

/**
 * Preset create/edit modal. Reuses the cfg-modal skeleton; the model chain
 * editor is the shared drag-to-reorder component from presets.js.
 */
function showPresetModal(existing, onSaved) {
  const isEdit = !!existing;
  document.getElementById('cfg-modal')?.remove();

  const overlay = document.createElement('div');
  overlay.id = 'cfg-modal';
  overlay.className = 'cfg-modal-overlay';
  overlay.innerHTML = `
    <div class="cfg-modal">
      <div class="cfg-modal-title">${isEdit ? t('preset.editTitle', { name: existing.name }) : t('preset.addTitle')}</div>
      <div class="cfg-modal-body">
        <div class="cfg-form-group">
          <label class="cfg-label">${t('preset.fieldName')}</label>
          <input class="cfg-input" data-field="name" type="text" value="${escapeHtml(existing?.name || '')}" placeholder="vision" ${isEdit ? 'disabled' : ''} autocomplete="off">
        </div>
        <div class="cfg-form-group">
          <label class="cfg-label">${t('preset.fieldDescription')}</label>
          <input class="cfg-input" data-field="description" type="text" value="${escapeHtml(existing?.description || '')}" autocomplete="off">
        </div>
        <div class="cfg-form-group">
          <label class="cfg-label">${t('preset.fieldChain')}</label>
          <div class="preset-drag-hint">${t('preset.dragHint')}</div>
          <div class="preset-chain-editor" id="preset-chain-editor"></div>
        </div>
      </div>
      <div class="cfg-modal-actions">
        <button class="cfg-btn cfg-btn-cancel" id="cfg-modal-cancel">${t('modal.cancel')}</button>
        <button class="cfg-btn cfg-btn-save" id="cfg-modal-save">${t('settings.save')}</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  const initialChain = existing ? [existing.preferred, ...(existing.fallbacks || [])].filter(Boolean) : [];
  const editor = presets.renderChainEditor(
    overlay.querySelector('#preset-chain-editor'),
    initialChain,
    presets.getAllModelRefs(),
  );

  const close = () => overlay.remove();
  overlay.querySelector('#cfg-modal-cancel').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });

  overlay.querySelector('#cfg-modal-save').addEventListener('click', async () => {
    const name = overlay.querySelector('[data-field="name"]').value.trim();
    const description = overlay.querySelector('[data-field="description"]').value.trim();
    if (!isEdit) {
      if (!name) { window.__showToast?.(t('preset.nameRequired'), 'error'); return; }
      if (/\s/.test(name)) { window.__showToast?.(t('preset.noSpaces'), 'error'); return; }
    }
    const chain = editor.getChain();
    const body = {
      name: isEdit ? existing.name : name,
      description,
      preferred: chain[0] || null,
      fallbacks: chain.slice(1),
    };
    try {
      if (isEdit) await presets.updatePreset(existing.name, body);
      else await presets.createPreset(body);
      close();
      onSaved?.();
    } catch (err) {
      window.__showToast?.(err.message, 'error');
    }
  });
}


// ---------- Autostart (开机自启动) ----------
// Target `enabled` while an autostartSet request is in flight; null when idle.
// The result handler compares the reported state against this to toast
// success/failure — the toggle itself is never flipped optimistically.
let autostartPendingSet = null;

onMessage('autostartStatusResult', (msg) => {
  state.autostartStatus = {
    enabled: !!msg.enabled,
    supported: msg.supported !== false,
    reason: msg.reason || '',
  };
  const toggle = document.getElementById('toggle-autostart');
  if (toggle) {
    setToggleState(toggle, state.autostartStatus.enabled);
    toggle.disabled = !state.autostartStatus.supported; // native button disabled — :disabled styling
  }
  const hint = document.getElementById('autostart-hint');
  if (hint) {
    const show = !state.autostartStatus.supported;
    hint.style.display = show ? 'block' : 'none';
    if (show) hint.textContent = state.autostartStatus.reason || t('settings.autostartUnsupported');
  }
  if (autostartPendingSet !== null) {
    if (state.autostartStatus.enabled === autostartPendingSet) {
      window.__showToast?.(t(autostartPendingSet ? 'settings.autostartOn' : 'settings.autostartOff'), 'success');
    } else {
      window.__showToast?.(state.autostartStatus.reason || t('settings.autostartFailed'), 'error');
    }
    autostartPendingSet = null;
  }
});


function bindSettingsEvents(content, cfg) {
  // Thinking effort selector (#345, user ruling 2026-08-20): OpenAI-style
  // off/low/medium/high dropdown replaces the Anthropic numeric budget input.
  // Levels map to budget_tokens at the same boundaries OpenAiAdapter.
  // budgetToEffort uses (≤2048 low / ≤8192 medium / ≤32768 high), so a saved
  // value round-trips through the adapter unchanged. Legacy numeric configs
  // are displayed at their nearest level (no migration needed — saving
  // rewrites the canonical number for that level).
  const EFFORT_BUDGETS = { low: 2048, medium: 8192, high: 32768 };
  document.getElementById('cfg-thinking-effort')?.addEventListener('change', function() {
    if (this.value === 'off') {
      state.thinkingMode = null;
    } else {
      state.thinkingMode = { enabled: true, budgetTokens: EFFORT_BUDGETS[this.value] };
    }
    sendWs({ type: 'setThinking', thinking: state.thinkingMode });
  });

  // LLM Log toggle — shared nb-toggle component; setToggleState keeps the
  // class and aria-checked in lockstep.
  document.getElementById('toggle-llm-log')?.addEventListener('click', function() {
    const enabled = !this.classList.contains('on');
    setToggleState(this, enabled);
    state.llmLogEnabled = enabled;
    sendWs({type: 'setLlmLog', enabled});
  });

  // ── Work schedule (freeze) — spec §3.2 sidebar.js ──────────────────────
  // 作者裁定（2026-08-30, toggle 即时保存）：开关类设置变更即保存——toggle 不再
  // 需要显示的保存按钮，更改就是保存（覆盖 #334 对 toggle 的适用；非 toggle 的
  // 文本/数字输入仍按 #334 显式保存）。toggle 变更立即调 saveWorkSchedule →
  // sendWs setWorkSchedule → 后端 broadcastServerConfig + FreezeScheduler.scan
  // 立即解冻/冻结（现象1 Save 链路 S1 已实证 13/13）。段落编辑器（add/remove/
  // 时间输入）仍是文本/数字输入 → 保留显式 Save 按钮（markScheduleDirty）。
  document.getElementById('toggle-schedule')?.addEventListener('click', function() {
    const enabled = !this.classList.contains('on');
    setToggleState(this, enabled);
    const editor = document.getElementById('schedule-editor');
    const offHint = document.getElementById('schedule-off-hint');
    if (editor) editor.style.display = enabled ? 'block' : 'none';
    if (offHint) offHint.style.display = enabled ? 'none' : 'block';
    let segs = collectSegments();
    if (enabled && segs.length === 0) {
      segs = [{ start: '09:00', end: '12:00' }];   // F2: default one segment
      renderScheduleEditorRows(segs);
    }
    // toggle 即时保存：变更即 sendWs setWorkSchedule（无需点保存按钮）。
    if (saveWorkSchedule(enabled, segs, { toast: true })) {
      scheduleDraft = null;      // toggle 已即时提交，无"待保存"草稿态
      updateScheduleActionRow();
    } else {
      // 保存失败（enabled=true 且段落非法）→ 回滚 toggle（setToggleState 同步
      // class + aria-checked），保持原状态。
      setToggleState(this, !enabled);
      if (editor) editor.style.display = !enabled ? 'block' : 'none';
      if (offHint) offHint.style.display = !enabled ? 'none' : 'block';
    }
  });

  document.getElementById('btn-add-segment')?.addEventListener('click', () => {
    const list = document.getElementById('segment-list');
    if (!list) return;
    const idx = list.children.length;
    list.insertAdjacentHTML('beforeend', buildSegmentRowHtml({ start: '', end: '' }, idx));
    list.querySelector(`[data-seg-index="${idx}"] [data-role="start"]`)?.focus();
    markScheduleDirty();
  });

  // Explicit commit / abandon — #334: nothing reaches the server until Save.
  document.getElementById('btn-save-schedule')?.addEventListener('click', () => {
    const enabled = document.getElementById('toggle-schedule')?.classList.contains('on') ?? false;
    const segs = collectSegments();
    if (saveWorkSchedule(enabled, segs, { toast: true })) {
      scheduleDraft = null;      // committed → back to server-state rendering
      updateScheduleActionRow(); // hide action row; the serverConfig echo re-renders
    }
  });
  document.getElementById('btn-reset-schedule')?.addEventListener('click', () => {
    scheduleDraft = null;        // abandon edits → render server truth again
    renderSettings();
  });

  // ── STT config (#295 + A1/A2) — save sends what's typed; all-empty = clear
  // (back to the free browser path). The apiKey input is never pre-filled
  // (server never echoes it) — the "已配置" badge + masked placeholder carry
  // the configured state; an EMPTY key omits the field from the payload so the
  // backend merge keeps the stored key (A2: an empty string would REPLACE
  // stt-config.json without the key and drop the service to unconfigured).
  function sendSttConfig(clear) {
    const endpoint = clear ? '' : (document.getElementById('stt-endpoint')?.value.trim() || '');
    const model = clear ? '' : (document.getElementById('stt-model')?.value.trim() || '');
    const apiKey = clear ? '' : (document.getElementById('stt-apikey')?.value.trim() || '');
    const allEmpty = !endpoint && !model && !apiKey;
    // Clear = explicit all-empty (three keys, backend deletes the config file);
    // normal save omits an empty apiKey so the backend merge keeps the stored
    // key — never send an empty string on a partial update.
    const sttConfig = clear
      ? { endpoint: '', apiKey: '', model: '' }
      : { endpoint, model, ...(apiKey ? { apiKey } : {}) };
    sendWs({ type: 'setSttConfig', sttConfig });
    window.__showToast?.(t(allEmpty ? 'settings.sttCleared' : 'settings.sttSaved'), 'success');
  }
  document.getElementById('btn-save-stt')?.addEventListener('click', () => sendSttConfig(false));
  document.getElementById('btn-clear-stt')?.addEventListener('click', () => sendSttConfig(true));
  // A1: collapsed advanced panel — expand/collapse (session-memory state).
  document.getElementById('stt-advance-toggle')?.addEventListener('click', () => {
    sttAdvanceExpanded = !sttAdvanceExpanded;
    const body = document.getElementById('stt-advance-body');
    const toggle = document.getElementById('stt-advance-toggle');
    if (body) body.hidden = !sttAdvanceExpanded;
    if (toggle) toggle.setAttribute('aria-expanded', String(sttAdvanceExpanded));
  });

  // Tool result TTL section (#341) — element-level bindings on fresh nodes.
  bindTtlEvents();

  // Appearance section (v8.3.0 micOrb palette presets) — element-level
  // bindings on fresh nodes; commits persist + dispatch CHANGE_EVENT so the
  // live orb re-resolves its board. Gated off by ORB_SETTINGS_VISIBLE.
  if (ORB_SETTINGS_VISIBLE) bindAppearanceEvents(content);

  // Language selector
  document.getElementById('cfg-language')?.addEventListener('change', function() {
    setLocale(this.value);
    renderSettings();
  });

  // Autostart toggle — server-authoritative: the toggle is only flipped by the
  // autostartStatusResult response (never optimistically), so a failed enable
  // (launchctl/schtasks error) leaves the UI showing the real state.
  document.getElementById('toggle-autostart')?.addEventListener('click', function() {
    const cur = state.autostartStatus;
    if (!cur || !cur.supported || autostartPendingSet !== null) return;
    autostartPendingSet = !cur.enabled;
    sendWs({ type: 'autostartSet', enabled: autostartPendingSet });
  });
  // Refresh status every time settings render (cheap; keeps toggle in sync
  // with CLI-side `nebflow autostart enable/disable`).
  sendWs({ type: 'autostartStatus' });

  // --- Provider add/edit/remove ---
  document.getElementById('btn-add-provider')?.addEventListener('click', () => {
    showProviderModal(null, null, (name, data) => {
      saveNewProvider(name, data);
    });
  });

  content.querySelectorAll('.cfg-card[data-provider]').forEach(card => {
    const name = card.dataset.provider;
    card.querySelector('.cfg-card-remove')?.addEventListener('click', (e) => {
      e.stopPropagation();
      window.__showConfirm?.('Remove Provider', t('provider.removeConfirm', { name }), () => {
        state.parsedConfig.llm.providers[name] = null;
        // Preset reference cleanup on provider removal is backend-owned (#339 —
        // the global default-model field is retired; backend auto-created
        // presets are keyed per provider).
        state.configDirty = true;
        flushConfigToServer();
        renderSettings();
      });
    });
    card.addEventListener('click', () => {
      const p = state.parsedConfig.llm.providers[name];
      showProviderModal(name, p, (newName, data) => {
        if (newName !== name) {
          // Use null to signal explicit deletion of old name
          state.parsedConfig.llm.providers[name] = null;
        }
        state.parsedConfig.llm.providers[newName] = data;
        state.configDirty = true;
        flushConfigToServer();
      });
    });
  });

  // --- Advanced JSON editor ---
  document.getElementById('btn-toggle-json')?.addEventListener('click', () => {
    state.settingsShowJson = !state.settingsShowJson;
    const sec = document.getElementById('json-editor-section');
    if (sec) sec.style.display = state.settingsShowJson ? 'block' : 'none';
  });

  // Re-run onboarding (sealed behind SHOW_RERUN_ONBOARDING — 09-05 裁定②):
  // reset the marker to pending and reload — the boot sequence picks it up
  // and shows the wizard (or returning-user prompt).
  if (SHOW_RERUN_ONBOARDING) {
    document.getElementById('btn-rerun-onboarding')?.addEventListener('click', () => {
      sendWs({ type: 'setOnboardingState', state: 'pending' });
      setTimeout(() => location.reload(), 300);
    });
  }

  document.getElementById('btn-save-config')?.addEventListener('click', () => {
    const cfg = document.getElementById('config-editor').value;
    try {
      JSON.parse(cfg);
      sendWs({type: 'updateConfig', config: cfg});
    } catch(e) {
      window.__showToast?.('Invalid JSON: ' + e.message, 'error');
    }
  });

  document.getElementById('btn-reload-config')?.addEventListener('click', () => {
    sendWs({type: 'getConfig'});
  });

  // --- Settings avatar section (dual state; click = Activity Bar avatar) ---
  document.getElementById('settings-avatar-entry')?.addEventListener('click', () => {
    // Forward to the canonical entry — bindAvatar's handler owns the
    // logged-out → login modal / logged-in → profile behavior.
    document.getElementById('activity-avatar')?.click();
  });

  // --- Check for updates ---
  document.getElementById('btn-check-update')?.addEventListener('click', () => {
    const statusEl = document.getElementById('update-status');
    const actionEl = document.getElementById('update-action');
    statusEl.textContent = t('settings.checking');
    statusEl.style.display = '';
    actionEl.style.display = 'none';
    // Flag BEFORE the WS roundtrip: updateCheckResult routes by this flag —
    // a manual check keeps its About-section echo even if a scheduled auto
    // check fires meanwhile (auto cycles are skipped while the flag is up).
    notifyManualUpdateCheck();
    sendWs({type: 'checkUpdate'});
  });

  document.getElementById('btn-do-update')?.addEventListener('click', () => {
    const btn = document.getElementById('btn-do-update');
    const statusEl = document.getElementById('update-status');
    btn.textContent = t('settings.updating');
    btn.disabled = true;
    statusEl.textContent = '';
    sendWs({type: 'doUpdate'});
  });

  document.getElementById('btn-dismiss-update')?.addEventListener('click', () => {
    document.getElementById('update-action').style.display = 'none';
  });
}

// Delegated segment-editor listeners — bound ONCE at module scope, NOT inside
// bindSettingsEvents. renderSettings() re-runs on every serverConfig echo while
// the panel is open; #settings-content is a persistent container, so listeners
// bound to it inside bindSettingsEvents would accumulate and fire N× per event
// (duplicate setWorkSchedule saves). Delegating from document keeps exactly one
// listener for the whole app lifetime.
document.addEventListener('click', (e) => {
  const settings = document.getElementById('settings-content');
  if (!settings || !(e.target instanceof Node) || !settings.contains(e.target)) return;
  const rm = e.target.closest('.seg-remove');
  if (!rm) return;
  const row = rm.closest('.segment-row');
  if (row) row.remove();
  settings.querySelectorAll('#segment-list .segment-row .seg-label').forEach((el, i) => {
    el.textContent = t('settings.segmentLabel', { n: i + 1 });
  });
  markScheduleDirty();   // #334: local draft only — Save commits
});

document.addEventListener('change', (e) => {
  const settings = document.getElementById('settings-content');
  if (!settings || !(e.target instanceof Node) || !settings.contains(e.target)) return;
  if (!e.target.classList || !e.target.classList.contains('time-input')) return;
  updateSegmentRowHint(e.target.closest('.segment-row'));
  markScheduleDirty();   // #334: local draft only — Save commits
});

function flushConfigToServer() {
  const json = JSON.stringify(state.parsedConfig, null, 2);
  state.configText = json;
  sendWs({type: 'updateConfig', config: json});
}

// Listen for config validation errors
onMessage('error', (data) => {
  if (data.message && typeof data.message === 'string' && data.message.includes('Provider')) {
    // Show validation errors from backend
    const toast = document.createElement('div');
    toast.className = 'cfg-toast cfg-toast-error';
    toast.textContent = data.message;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 5000);
  }
});

// --- Provider model-list auto-fetch (B1) ---
// Model choices fetched for the currently open provider modal. Set on a
// successful POST /api/provider/models; renderModelRowContent reads it so
// both existing and newly added rows offer a dropdown instead of free text.
// The proxy endpoint may not exist yet (404) — fetch failure degrades to
// manual input without blocking the flow.
// Elements are normalized to {id, contextLength} — the backend may send plain
// strings (old contract) or objects with an optional contextLength (e.g.
// OpenRouter exposes context_length; OpenAI/Anthropic do not).
let providerModelChoices = null;

function providerAuthHeaders() {
  const tok = localStorage.getItem(key('token')) || '';
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

/** Normalize one models[] element: 'id-string' | {id, contextLength?} →
 *  {id, contextLength|null}. Unknown shapes are dropped. */
function normalizeModelEntry(m) {
  if (typeof m === 'string' && m) return { id: m, contextLength: null };
  if (m && typeof m.id === 'string' && m.id) {
    return { id: m.id, contextLength: Number.isFinite(m.contextLength) ? m.contextLength : null };
  }
  return null;
}

async function fetchProviderModels(baseUrl, apiKey) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 10000);
  try {
    const resp = await fetch('/api/provider/models', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...providerAuthHeaders() },
      body: JSON.stringify({ baseUrl, apiKey }),
      signal: ctrl.signal,
    });
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) return { ok: false, error: data.error || `HTTP ${resp.status}` };
    const models = Array.isArray(data.models) ? data.models.map(normalizeModelEntry).filter(Boolean) : [];
    return { ok: true, models };
  } catch (e) {
    return { ok: false, error: e.name === 'AbortError' ? 'timeout' : e.message };
  } finally {
    clearTimeout(timer);
  }
}

function contextLengthFor(id) {
  if (!id || !providerModelChoices) return null;
  const found = providerModelChoices.find(m => m.id === id);
  return found && found.contextLength ? found.contextLength : null;
}

/** Fill the row's contextWindow input with the fetched contextLength — only
 *  when the input is empty (never overwrite a user-set value). maxTokens is
 *  intentionally not touched. */
function fillContextIfEmpty(row) {
  if (!row) return;
  const sel = row.querySelector('.cfg-model-id');
  const ctxInput = row.querySelector('.cfg-model-ctx');
  if (!sel || !ctxInput || ctxInput.value) return;
  const len = contextLengthFor(sel.value);
  if (len) ctxInput.value = len;
}

function renderModelIdSelect(currentId) {
  const opts = providerModelChoices.map(m => m.id);
  if (currentId && !opts.includes(currentId)) opts.unshift(currentId);
  return `<select class="cfg-select cfg-model-id">
    <option value="" disabled ${currentId ? '' : 'selected'}>${t('provider.modelSelectPlaceholder')}</option>
    ${opts.map(o => `<option value="${escapeHtml(o)}" ${o === currentId ? 'selected' : ''}>${escapeHtml(o)}</option>`).join('')}
  </select>`;
}

/** Convert manual id inputs in existing rows to dropdowns after a successful
 *  fetch. Current values are preserved (added as an extra option if absent
 *  from the fetched list); empty contextWindow inputs are auto-filled from
 *  the fetched contextLength. */
function upgradeModelRowsToSelects(container) {
  container.querySelectorAll('.cfg-model-row').forEach(row => {
    const input = row.querySelector('input.cfg-model-id');
    if (input) {
      const current = input.value.trim();
      const tmp = document.createElement('template');
      tmp.innerHTML = renderModelIdSelect(current).trim();
      input.replaceWith(tmp.content.firstChild);
    }
    fillContextIfEmpty(row);
  });
}

/** Wire base URL → auto-fetch model list inside the provider modal: a fetch
 *  button + status line above the models list, plus debounced auto-fetch on
 *  baseUrl/apiKey change. Degrades gracefully when the proxy endpoint is
 *  missing or the provider errors — manual input stays usable. */
function wireProviderModelFetch() {
  const overlay = document.getElementById('cfg-modal');
  if (!overlay) return;
  providerModelChoices = null;
  const baseInput = overlay.querySelector('[data-field="baseUrl"]');
  const keyInput = overlay.querySelector('[data-field="apiKey"]');
  const container = overlay.querySelector('[data-field="models"]');
  const group = container?.closest('.cfg-form-group');
  if (!baseInput || !container || !group) return;

  const statusRow = document.createElement('div');
  statusRow.className = 'cfg-fetch-status-row';
  statusRow.innerHTML = `<button type="button" class="cfg-fetch-models-btn">${t('provider.fetchModels')}</button><span class="cfg-fetch-status"></span>`;
  group.insertBefore(statusRow, container);
  const fetchBtn = statusRow.querySelector('.cfg-fetch-models-btn');
  const status = statusRow.querySelector('.cfg-fetch-status');

  let fetchGen = 0; // race guard: stale responses (older trigger) are dropped
  async function runFetch() {
    const baseUrl = baseInput.value.trim();
    if (!baseUrl || !/^https?:\/\//.test(baseUrl)) {
      status.textContent = '';
      status.className = 'cfg-fetch-status';
      return;
    }
    const gen = ++fetchGen;
    fetchBtn.disabled = true;
    status.textContent = t('provider.fetchingModels');
    status.className = 'cfg-fetch-status loading';
    const res = await fetchProviderModels(baseUrl, keyInput ? keyInput.value.trim() : '');
    if (gen !== fetchGen || !overlay.isConnected) return;
    fetchBtn.disabled = false;
    if (res.ok && res.models.length > 0) {
      providerModelChoices = res.models;
      upgradeModelRowsToSelects(container);
      // A successful list fetch with a non-empty key doubles as credential
      // validation — flag the key as valid. Anonymous providers (empty key)
      // never get this hint.
      const hasKey = !!(keyInput && keyInput.value.trim());
      status.textContent = t('provider.fetchModelsLoaded', { count: res.models.length })
        + (hasKey ? ` · ${t('provider.keyValid')}` : '');
      status.className = 'cfg-fetch-status ok';
    } else {
      providerModelChoices = null;
      status.textContent = t('provider.fetchModelsFailed');
      status.className = 'cfg-fetch-status fail';
    }
  }

  fetchBtn.addEventListener('click', runFetch);
  let debounceTimer = null;
  const autoFetch = () => { clearTimeout(debounceTimer); debounceTimer = setTimeout(runFetch, 400); };
  baseInput.addEventListener('change', autoFetch);
  keyInput?.addEventListener('change', autoFetch);
  // Delegated: picking a model from the dropdown auto-fills an empty
  // contextWindow from the fetched contextLength (never overwrites).
  container.addEventListener('change', (e) => {
    if (e.target.matches('select.cfg-model-id')) fillContextIfEmpty(e.target.closest('.cfg-model-row'));
  });
}

// --- Provider modal ---
/** Persist a newly added provider: mutate parsedConfig + flush. The backend
 *  auto-creates the first preset from a new provider — the legacy global
 *  default-model field is retired (global-default preset semantics, #339),
 *  so there is no frontend chain write here. Exported for the chat-native
 *  onboarding flow (onboarding-redesign-spec §5.1) — one write path. */
export function saveNewProvider(name, data) {
  if (!state.parsedConfig) state.parsedConfig = {llm: {providers: {}}};
  if (!state.parsedConfig.llm) state.parsedConfig.llm = {providers: {}};
  if (!state.parsedConfig.llm.providers) state.parsedConfig.llm.providers = {};
  state.parsedConfig.llm.providers[name] = data;
  state.configDirty = true;
  flushConfigToServer();
}

/**
 * Onboarding wizard entry: run the same add-provider modal outside the
 * settings panel. onSaved fires after the config has been flushed.
 */
export function openProviderWizard(onSaved) {
  showProviderModal(null, null, (name, data) => {
    saveNewProvider(name, data);
    if (typeof onSaved === 'function') onSaved(name);
  });
}

function showProviderModal(existingName, existingData, onSave) {
  const isEdit = !!existingName;
  const p = existingData || {baseUrl: '', apiKey: '', protocol: 'anthropic', models: []};
  const initialModels = p.models.length > 0 ? p.models.map(m => ({
    ...m,
  })) : [{id: '', maxTokens: 131072, contextWindow: 200000}];

  showModal({
    title: isEdit ? t('provider.edit', { name: existingName }) : t('provider.add'),
    fields: [
      {key: 'name', label: t('provider.id'), type: 'text', value: existingName || '', placeholder: t('provider.idPlaceholder'), disabled: isEdit},
      {key: 'baseUrl', label: t('provider.baseUrl'), type: 'text', value: p.baseUrl || '', placeholder: 'https://api.example.com/v1'},
      {key: 'apiKey', label: 'API Key', type: 'text', password: true, value: p.apiKey && p.apiKey !== '***' ? p.apiKey : '', placeholder: isEdit ? t('provider.keyPlaceholder') : t('provider.required')},
      {key: 'protocol', label: t('provider.protocol'), type: 'select', value: p.protocol || 'anthropic', options: ['anthropic', 'openai']},
      {key: 'models', label: t('provider.models'), type: 'models', value: initialModels},
    ],
    onConfirm(values) {
      const name = values.name.trim();
      if (!name) { window.__showToast?.(t('provider.idRequired'), 'error'); return; }
      if (/\s/.test(name)) { window.__showToast?.(t('provider.noSpaces'), 'error'); return; }
      let baseUrl = values.baseUrl.trim();
      if (!baseUrl) { window.__showToast?.(t('provider.baseUrlRequired'), 'error'); return; }
      // Auto-add trailing slash
      if (!baseUrl.endsWith('/')) baseUrl += '/';
      const apiKey = values.apiKey.trim() || '***';
      if (!isEdit && apiKey === '***') { window.__showToast?.(t('provider.keyRequired'), 'error'); return; }
      const validModels = values.models.filter(m => m.id && m.id.trim());
      if (validModels.length === 0) { window.__showToast?.(t('provider.modelRequired'), 'error'); return; }
      // Vision is never written from this form (B1 裁定 2026-08-25): the
      // edit-modal checkbox snapshot polluted nebflow.json ModelConfig.vision
      // (inline outranks models.json runtime annotations). An explicit inline
      // vision set by hand rides through untouched; new models omit the key.
      const modelsOut = validModels.map(m => {
        const prev = (p.models || []).find(x => x.id === m.id);
        return prev && prev.vision !== undefined ? { ...m, vision: prev.vision } : m;
      });
      onSave(name, {
        baseUrl,
        apiKey,
        protocol: values.protocol,
        models: modelsOut,
      });
    }
  });
  // baseUrl/apiKey change → auto-fetch model list (dropdown); degrades to
  // manual input when the proxy endpoint is unavailable.
  wireProviderModelFetch();
}

// --- Generic modal ---
function showModal({title, fields, onConfirm}) {
  // Remove existing modal
  document.getElementById('cfg-modal')?.remove();

  const renderField = (f) => `
          <div class="cfg-form-group">
            <label class="cfg-label">${escapeHtml(f.label)}</label>
            ${f.type === 'select' ? `<select class="cfg-input" data-field="${f.key}" ${f.disabled ? 'disabled' : ''}>
              ${f.options.map(o => `<option value="${o}" ${o === f.value ? 'selected' : ''}>${o}</option>`).join('')}
            </select>` : f.type === 'textarea' ? `<textarea class="cfg-input cfg-textarea" data-field="${f.key}" placeholder="${escapeHtml(f.placeholder || '')}">${escapeHtml(f.value || '')}</textarea>` :
            f.type === 'models' ? `<div class="cfg-models-container" data-field="${f.key}">
              ${f.value.map((m, i) => renderModelRow(m, i)).join('')}
              <button class="cfg-model-add" type="button">${t('model.add')}</button>
            </div>` :
            f.password ? `<div class="cfg-password-wrap"><input class="cfg-input" type="text" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" autocomplete="off" style="-webkit-text-security:disc" ${f.disabled ? 'disabled' : ''}><button class="cfg-eye-btn" type="button" tabindex="-1" aria-label="Toggle visibility">${eyeSvg}</button></div>` :
            f.type === 'number' ? `<input class="cfg-input" type="number" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" ${f.min != null ? `min="${f.min}"` : ''} ${f.disabled ? 'disabled' : ''}>` :
            `<input class="cfg-input" type="text" data-field="${f.key}" value="${escapeHtml(f.value || '')}" placeholder="${escapeHtml(f.placeholder || '')}" ${f.disabled ? 'disabled' : ''}>`}
          </div>`;

  const overlay = document.createElement('div');
  overlay.id = 'cfg-modal';
  overlay.className = 'cfg-modal-overlay';
  overlay.innerHTML = `
    <div class="cfg-modal">
      <div class="cfg-modal-title">${escapeHtml(title)}</div>
      <div class="cfg-modal-body">
        ${fields.map(renderField).join('')}
      </div>
      <div class="cfg-modal-actions">
        <button class="cfg-btn cfg-btn-cancel" id="cfg-modal-cancel">${t('modal.cancel')}</button>
        <button class="cfg-btn cfg-btn-save" id="cfg-modal-save">${t('settings.save')}</button>
      </div>
    </div>`;

  document.body.appendChild(overlay);

  // Wire up models add/remove
  overlay.querySelectorAll('.cfg-model-add').forEach(btn => {
    btn.addEventListener('click', () => {
      const container = btn.parentElement;
      const row = document.createElement('div');
      row.className = 'cfg-model-row';
      row.innerHTML = renderModelRowContent();
      container.insertBefore(row, btn);
      row.querySelector('.cfg-model-remove').addEventListener('click', () => row.remove());
      row.querySelector('.cfg-model-id').focus();
    });
  });
  overlay.querySelectorAll('.cfg-model-remove').forEach(btn => {
    btn.addEventListener('click', () => btn.closest('.cfg-model-row').remove());
  });

  // Wire up eye toggle
  overlay.querySelectorAll('.cfg-eye-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const input = btn.parentElement.querySelector('input');
      const isMasked = input.style.webkitTextSecurity !== 'none';
      input.style.webkitTextSecurity = isMasked ? 'none' : 'disc';
      btn.innerHTML = isMasked ? eyeOffSvg : eyeSvg;
    });
  });

  const close = () => overlay.remove();
  document.getElementById('cfg-modal-cancel').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  document.getElementById('cfg-modal-save').addEventListener('click', () => {
    const values = {};
    overlay.querySelectorAll('[data-field]:not([data-field="models"])').forEach(el => {
      values[el.dataset.field] = el.value;
    });
    // Collect models
    const modelsContainer = overlay.querySelector('[data-field="models"]');
    if (modelsContainer) {
      values.models = [];
      modelsContainer.querySelectorAll('.cfg-model-row').forEach(row => {
        const id = row.querySelector('.cfg-model-id').value.trim();
        if (!id) return;
        values.models.push({
          id,
          maxTokens: parseInt(row.querySelector('.cfg-model-max').value) || 131072,
          contextWindow: parseInt(row.querySelector('.cfg-model-ctx').value) || 200000,
        });
      });
    }
    onConfirm(values);
    close();
  });
}

function renderModelRowContent(m) {
  const id = m ? m.id : '';
  const max = m ? m.maxTokens : '';
  const ctx = m ? m.contextWindow : '';
  const idField = providerModelChoices && providerModelChoices.length > 0
    ? renderModelIdSelect(id)
    : `<input class="cfg-input cfg-model-id" type="text" value="${escapeHtml(id)}" placeholder="${t('model.idPlaceholder')}">`;
  // Vision is auto-detected at runtime (B3) and shown as a read-only badge on
  // provider cards — no per-model control in this form (B1 裁定 2026-08-25).
  return `${idField}
<input class="cfg-input cfg-model-max" type="number" value="${max}" placeholder="${t('model.maxTokensPlaceholder')}">
<input class="cfg-input cfg-model-ctx" type="number" value="${ctx}" placeholder="${t('model.contextPlaceholder')}">
<button class="cfg-model-remove" type="button" title="${t('provider.remove')}">&times;</button>`;
}

function renderModelRow(m, index) {
  return `<div class="cfg-model-row">${renderModelRowContent(m)}</div>`;
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// ---------- Session Sidebar ----------
export function formatSessionTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  const now = new Date();
  const isToday = d.toDateString() === now.toDateString();
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const isYesterday = d.toDateString() === yesterday.toDateString();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (isToday) return hh + ':' + mm;
  if (isYesterday) return t('time.yesterday') + ' ' + hh + ':' + mm;
  return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + hh + ':' + mm;
}

/** Render a single session item into the given container. */
function renderOneSessionItem(s, container, opts = {}) {
  const inFolder = opts.inFolder || false;
  const item = document.createElement('div');
  const isSelected = state.selectedSessionIds.has(s.id);
  item.className = 'session-item'
    + (inFolder ? ' in-folder' : '')
    + (s.id === state.activeSessionId ? ' active' : '')
    + (state.pinnedSessions.has(s.id) ? ' pinned' : '')
    + (isSelected ? ' selected' : '');
  item.dataset.id = s.id;
  item.draggable = true;
  item.addEventListener('dragstart', (e) => {
    const selected = state.selectedSessionIds;
    if (selected.size > 1 && selected.has(s.id)) {
      e.dataTransfer.setData('text/plain', 'batch:' + [...selected].join(','));
    } else if (selected.size === 1 && selected.has(s.id)) {
      e.dataTransfer.setData('text/plain', 'batch:' + s.id);
    } else {
      e.dataTransfer.setData('text/plain', s.id);
    }
    e.dataTransfer.effectAllowed = 'move';
    item.classList.add('dragging');
    showDeleteZone();
  });
  item.addEventListener('dragend', () => {
    item.classList.remove('dragging');
    document.querySelectorAll('.folder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
    hideDeleteZone();
  });
  const statusCls = getSessionStatusClass(s.id);
  const draft = state.sessionInputDrafts[s.id];
  const draftHtml = draft && draft.text
    ? '<div class="session-draft">' + escapeHtml(draft.text.replace(/\n/g, ' ').slice(0, 60)) + '</div>'
    : '';
  const deleteBtnHtml = '<button class="session-delete" title="' + t('session.delete') + '"><i data-lucide="x"></i></button>';
  item.innerHTML =
    '<div class="session-info">' +
    '<div class="session-name">' + escapeHtml(s.name) + '</div>' +
    (draftHtml || '<div class="session-time">' + formatSessionTime(s.updatedAt || s.createdAt) + '</div>') +
    '</div>' +
    '<div class="session-status ' + statusCls + '">' +
    '<div class="status-spinner"><i data-lucide="loader-2"></i></div>' +
    '<div class="status-compact-spinner"><i data-lucide="minimize-2"></i></div>' +
    '<div class="status-dot"></div>' +
    '</div>' +
    deleteBtnHtml;
  // Double-click to rename (only in normal mode)
  const nameEl = item.querySelector('.session-name');
  if (state.selectedSessionIds.size === 0) {
    nameEl.addEventListener('dblclick', (e) => {
      e.stopPropagation();
      nameEl.contentEditable = true;
      nameEl.focus();
      const range = document.createRange();
      range.selectNodeContents(nameEl);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    });
    const finishRename = () => {
      nameEl.contentEditable = false;
      const newName = nameEl.textContent.trim();
      if (newName && newName !== s.name) {
        sendWs({type: 'renameSession', sessionId: s.id, name: newName});
      } else {
        nameEl.textContent = s.name;
      }
    };
    nameEl.addEventListener('blur', finishRename);
    bindImeGuard(nameEl);
    nameEl.addEventListener('keydown', (e) => {
      if (isImeComposing(e, nameEl)) return; // ⑤ 组字期间 ↑↓/Enter/Esc 交还输入法
      if (e.key === 'Enter') {
        e.preventDefault(); nameEl.blur();
      }
      if (e.key === 'Escape') { nameEl.textContent = s.name; nameEl.blur(); }
    });
  }
  if (state.selectedSessionIds.size === 0) {
    item.querySelector('.session-delete').onclick = (e) => {
      e.stopPropagation();
      if (typeof window.__showDeleteModal === 'function') window.__showDeleteModal(s.id, s.name);
    };
  }
  item.onclick = (e) => {
    if (e.target.closest('.session-name[contenteditable="true"]')) return;
    if (e.target.closest('.session-delete')) return;
    const hasSelection = state.selectedSessionIds.size > 0;
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      toggleSessionSelection(s.id);
    } else if (e.shiftKey && state.lastSelectedSessionId) {
      e.preventDefault();
      if (!hasSelection) state.lastSelectedSessionId = state.activeSessionId;
      selectSessionRange(state.lastSelectedSessionId, s.id);
    } else if (hasSelection) {
      // In multi-select: click selected item does nothing; click unselected exits multi-select and switches
      if (state.selectedSessionIds.has(s.id)) {
        // do nothing, keep selection
      } else {
        exitBatchMode();
        // Unified: any session switches the main panel
        switchToSession(s.id);
      }
    } else {
      // Unified session switching — all agents display in the main panel.
      // switchToSession handles folder context restoration.
      switchToSession(s.id);
    }
  };
  item.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    if (state.selectedSessionIds.size > 0) {
      showBatchCtxMenu(e.clientX, e.clientY);
    } else {
      showSessionCtxMenu(e.clientX, e.clientY, s.id);
    }
  });
  container.appendChild(item);
}

/** Update the main header's session name + brand based on the active session.
 *  Extracted from renderSessionSidebar so the "skip rebuild" fast path can
 *  still refresh the header without a full DOM rebuild. */
function updateHeaderSessionName() {
  const sessionNameEl = chatViews.primary?.dom?.sessionNameEl;
  const active = state.sessions.find(s => s.id === state.activeSessionId);
  if (active) {
    const agentName = active.agentName || 'Nebula';
    // Show agent display name in center (session name is redundant when there's only one session).
    if (sessionNameEl) {
      const agent = state.agentsData.find(a => a.name === agentName);
      sessionNameEl.textContent = agent ? (agent.displayName || agent.name) : agentName;
      sessionNameEl.style.display = '';
    }
  } else {
    if (sessionNameEl) { sessionNameEl.textContent = ''; sessionNameEl.style.display = 'none'; }
  }
}

export function renderSessionSidebar(sessionData, activeId) {
  state.sessions = sessionData || [];
  const prevActiveId = state.activeSessionId;
  if (activeId) {
    state.activeSessionId = activeId;
    window.dispatchEvent(new CustomEvent('nebflow-session-change', { detail: { sessionId: activeId } }));
  }
  // If active session changed (new session, agent session, delete active), reset chat area
  if (activeId && activeId !== prevActiveId) {
    setActiveView(chatViews.primary);
    saveInputDraft(prevActiveId);
    resetChatForActiveSession();
    restoreInputDraft(activeId);
    clearMemoryCache();
  }

  // ── Performance: skip full DOM rebuild when nothing changed ──────────
  // sessionList and agentSessionList often carry identical data (especially during
  // initial load where both fire in sequence). Detect this and avoid the expensive
  // innerHTML='' + rebuild cycle. We compare a lightweight fingerprint: the set of
  // session ids + their updatedAt timestamps + expanded folders (folder
  // expand/collapse changes the DOM but not the session data).
  //
  // NOTE: activeId is deliberately NOT in the fingerprint. Switching the active
  // session only changes which item is highlighted — the in-place fast path below
  // toggles the `.active` class and refreshes the header without rebuilding 100+
  // DOM nodes. (Fix 4: session-switch sidebar performance.)
  const fingerprint =
    (sessionData || []).map(s => s.id + ':' + (s.updatedAt || 0) + ':' + (s.hasUnread ? 1 : 0)).sort().join(',') +
    '|folders:' + (state.folders || []).map(f => f.id + ':' + (f.parentId || '')).sort().join(';') +
    '|expanded:' + [...(state.expandedFolders || [])].sort().join(',') +
    '|pinned:' + [...(state.pinnedSessions || [])].sort().join(',') +
    '|selected:' + [...state.selectedSessionIds].sort().join(',') +
    '|rules:' + [...(state.foldersWithRules || [])].sort().join(',') +
    '|locale:' + getLocale();
  const sessionList = state.dom.sessionList;
  if (sessionList && sessionList._lastFingerprint === fingerprint) {
    // Data unchanged — just update active highlight in-place (much cheaper than rebuild).
    sessionList.querySelectorAll('.session-item').forEach(el => {
      el.classList.toggle('active', el.dataset.id === state.activeSessionId);
    });
    updateHeaderSessionName();
    return;
  }
  if (sessionList) sessionList._lastFingerprint = fingerprint;

  // Clean up localStorage for deleted sessions
  const currentIds = new Set((sessionData || []).map(s => s.id));
  try {
    const all = (() => { try { return JSON.parse(localStorage.getItem(LS_SESSIONS_KEY) || '{}'); } catch(e) { return {}; } })();
    let changed = false;
    for (const key of Object.keys(all)) {
      if (!currentIds.has(key)) { delete all[key]; changed = true; }
    }
    if (changed) { try { localStorage.setItem(LS_SESSIONS_KEY, JSON.stringify(all)); } catch(e) {} }
  } catch(e) {}
  // Session list DOM removed in single-session architecture — sidebar shows only scheduled tasks.
  // Sub-agent sessions are accessed via the flow canvas node popup.
  if (!sessionList) {
    updateHeaderSessionName();
    return;
  }
  sessionList.innerHTML = '';

  // Setup unified drop handler on sessionList (once)
  // VS Code-style: folder-wrapper is a "scope" — drop inside = move into folder, drop outside = move to root
  if (!sessionList._dropSetup) {
    sessionList._dropSetup = true;
    sessionList.addEventListener('dragover', (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
    });
    sessionList.addEventListener('drop', (e) => {
      e.preventDefault();
      // Clean up any drag-over highlights
      document.querySelectorAll('.folder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
      const data = e.dataTransfer.getData('text/plain');
      if (!data) return;
      // Determine target folder: closest folder-wrapper = move into, none = move to root
      const folderWrapper = e.target.closest('.folder-wrapper');
      const targetFolderId = folderWrapper ? folderWrapper.dataset.folderId : null;
      if (data.startsWith('folder:')) {
        const fid = data.slice(7);
        if (fid && fid !== targetFolderId) {
          sendWs({ type: 'moveFolder', folderId: fid, parentId: targetFolderId });
        }
      } else if (data.startsWith('batch:')) {
        const ids = data.slice(6).split(',').filter(Boolean);
        ids.forEach(sid => sendWs({ type: 'moveSessionToFolder', sessionId: sid, folderId: targetFolderId }));
      } else {
        sendWs({ type: 'moveSessionToFolder', sessionId: data, folderId: targetFolderId });
      }
    });
    // Click on empty area clears active folder (VSCode-style)
    sessionList.addEventListener('click', (e) => {
      if (e.target === sessionList) {
        clearActiveFolder();
      }
    });
  }

  // ── Single-session architecture: only render the active session ──
  // Sub-agent sessions are accessed via the flow canvas node popup.
  const activeSession = (sessionData || []).find(s => s.id === state.activeSessionId);
  const visibleSessions = activeSession ? [activeSession] : (sessionData || []).slice(0, 1);

  // Sort sessions helper
  const sortSessions = (list) => [...list].sort((a, b) => {
    const pa = state.pinnedSessions.has(a.id) ? 1 : 0;
    const pb = state.pinnedSessions.has(b.id) ? 1 : 0;
    if (pb !== pa) return pb - pa;
    return (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0);
  });

  // Group sessions and folders by agent
  const allFolders = state.folders || [];
  const agentGroups = {};
  visibleSessions.forEach(s => {
    const agent = s.agentName || 'Nebula';
    if (!agentGroups[agent]) agentGroups[agent] = { sessions: [], folders: [] };
    agentGroups[agent].sessions.push(s);
  });
  allFolders.forEach(f => {
    const agent = f.agentName || 'Nebula';
    if (!agentGroups[agent]) agentGroups[agent] = { sessions: [], folders: [] };
    agentGroups[agent].folders.push(f);
  });

  // Sort agent groups alphabetically
  const agentOrder = Object.keys(agentGroups).sort((a, b) => a.localeCompare(b));

  // Render each agent group
  agentOrder.forEach(agentName => {
    const group = agentGroups[agentName];

    // Agent group header (only when multiple agents exist)
    if (agentOrder.length > 1) {
      const header = document.createElement('div');
      header.className = 'agent-group-header';
      header.textContent = agentName;
      sessionList.appendChild(header);
    }

    // Separate sessions by folder within this agent group
    const groupFolderIds = new Set(group.folders.map(f => f.id));
    const rootSessions = [];
    const sessionsByFolder = {};
    group.sessions.forEach(s => {
      if (s.folderId && groupFolderIds.has(s.folderId)) {
        if (!sessionsByFolder[s.folderId]) sessionsByFolder[s.folderId] = [];
        sessionsByFolder[s.folderId].push(s);
      } else {
        rootSessions.push(s);
      }
    });

    const sortedRoots = sortSessions(rootSessions);
    Object.keys(sessionsByFolder).forEach(fid => {
      sessionsByFolder[fid] = sortSessions(sessionsByFolder[fid]);
    });

    const agentFolders = group.folders.filter(f => !f.parentId);
    agentFolders.sort((a, b) => a.name.localeCompare(b.name));

    // Build mixed list of root sessions and folders
    const mixedItems = [];
    sortedRoots.forEach(s => {
      mixedItems.push({ type: 'session', data: s, pinned: state.pinnedSessions.has(s.id), time: s.updatedAt || s.createdAt || 0 });
    });
    agentFolders.forEach(f => {
      mixedItems.push({ type: 'folder', data: f, pinned: state.pinnedFolders.has(f.id) });
    });
    mixedItems.sort((a, b) => {
      if (b.pinned !== a.pinned) return b.pinned ? 1 : -1;
      if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
      if (a.type === 'folder') return a.data.name.localeCompare(b.data.name);
      return b.time - a.time;
    });

    // Render items
    let hasPinned = false;
    let renderedDivider = false;
    mixedItems.forEach(item => {
      if (item.pinned) {
        hasPinned = true;
      } else if (hasPinned && !renderedDivider) {
        const divider = document.createElement('div');
        divider.className = 'session-divider';
        sessionList.appendChild(divider);
        renderedDivider = true;
      }
      if (item.type === 'session') {
        renderOneSessionItem(item.data, sessionList, { inFolder: false });
      } else {
        renderFolderItem(item.data, sessionsByFolder[item.data.id] || [], sessionList);
      }
    });
  });
  if (typeof lucide !== 'undefined') createIconsIn(sessionList);
  updateHeaderSessionName();
  computeAgentStates();
}

// Persist sessionInputDrafts to localStorage (strip transient skill/ask mode)
function persistDrafts() {
  const stripped = {};
  for (const [sid, draft] of Object.entries(state.sessionInputDrafts)) {
    stripped[sid] = { text: draft.text, attachments: draft.attachments };
  }
  try { localStorage.setItem(LS_DRAFTS_KEY, JSON.stringify(stripped)); } catch(e) {}
}

// Save current input box content as draft for the given session
export function saveInputDraft(sessionId, view) {
  view = view || activeView || chatViews.primary;
  if (!sessionId || !view?.dom?.input) return;
  const text = view.dom.input.value;
  const attachments = view.pendingAttachments;
  const skillMode = view.skillMode ? {
    name: view.skillModeName,
    desc: view.skillModeDesc,
    argHint: view.skillModeArgHint,
  } : null;
  const askMode = !!view.stream.askMode;
  if (text || attachments.length > 0 || skillMode || askMode) {
    state.sessionInputDrafts[sessionId] = {
      text,
      attachments: JSON.parse(JSON.stringify(attachments)),
      skillMode,
      askMode,
    };
  } else {
    delete state.sessionInputDrafts[sessionId];
  }
  persistDrafts();
  updateSessionDraftDisplay(sessionId);
}

/** Lightweight in-place update of a single session's draft/time display in the sidebar.
 *  Avoids a full renderSessionSidebar rebuild — just swaps the draft text or timestamp
 *  in the existing DOM node. */
function updateSessionDraftDisplay(sessionId) {
  if (!sessionId || !state.dom.sessionList) return;
  const item = state.dom.sessionList.querySelector('.session-item[data-id="' + sessionId + '"]');
  if (!item) return;
  const infoEl = item.querySelector('.session-info');
  if (!infoEl) return;
  const draft = state.sessionInputDrafts[sessionId];
  const existingDraft = infoEl.querySelector('.session-draft');
  const existingTime = infoEl.querySelector('.session-time');
  if (draft && draft.text) {
    const draftText = draft.text.replace(/\n/g, ' ').slice(0, 60);
    if (existingDraft) {
      existingDraft.textContent = draftText;
    } else {
      if (existingTime) existingTime.remove();
      const el = document.createElement('div');
      el.className = 'session-draft';
      el.textContent = draftText;
      infoEl.appendChild(el);
    }
  } else {
    if (existingDraft) {
      existingDraft.remove();
    }
    if (!existingTime) {
      const session = (state.sessions || []).find(s => s.id === sessionId);
      if (session) {
        const el = document.createElement('div');
        el.className = 'session-time';
        el.textContent = formatSessionTime(session.updatedAt || session.createdAt);
        infoEl.appendChild(el);
      }
    }
  }
}

// Restore input box content from draft for the given session
function restoreInputDraft(sessionId, view) {
  view = view || chatViews.primary;
  if (!view?.dom?.input) return;
  const input = view.dom.input;
  const draft = state.sessionInputDrafts[sessionId];
  if (draft) {
    input.value = draft.text;
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    view.pendingAttachments = draft.attachments || [];
    import('./chat.js').then(({ renderAttachmentPreview }) => {
      setActiveView(view);
      renderAttachmentPreview();
    });
  } else {
    input.value = '';
    input.style.height = 'auto';
    view.pendingAttachments = [];
    if (view.dom.attPreview) view.dom.attPreview.innerHTML = '';
  }
  import('./input.js').then(({ applyInputModes }) => {
    setActiveView(view);
    applyInputModes(draft?.skillMode, draft?.askMode);
  });
}

// Reset chat area for the current activeSessionId (used after session list updates)
export function resetChatForActiveSession() {
  const pv = chatViews.primary;
  if (!pv) return;
  // CRITICAL: sync the view's sessionId so findViewBySessionId can route
  // historyPage and streaming events to this view after session switch.
  pv.sessionId = state.activeSessionId;
  setActiveView(pv);
  pv.stream.aiText = '';
  pv.stream.currentAiBubble = null;
  pv.stream.currentThinkingBubble = null;
  pv.stream.thinkingText = '';
  pv.stream.agentBubbles = {};
  pv.stream.activeAgentId = null;
  Object.keys(state.sessionToolCards).forEach(sid => {
    if (state.sessionToolCards[sid]) state.sessionToolCards[sid].remove();
  });
  state.sessionToolCards = {};
  pv.pagination = { offset: 0, total: 0, hasMore: false, loading: false, pendingInitialLoad: true };
  cancelThinkingRAF();
  // Clear queue bar for the new session and re-render with this session's queue
  if (pv.dom.queueBar) {
    pv.dom.queueBar.innerHTML = '';
    pv.dom.queueBar.classList.remove('visible');
  }
  window.dispatchEvent(new CustomEvent('queuebar-refresh', { detail: { sessionId: state.activeSessionId } }));
  cleanupCardIframes(pv.dom.chat);
  pv.dom.chat.innerHTML = '';
  pv.dom.chat.querySelectorAll('.history-loader, .history-end').forEach(el => el.remove());

  const sid = state.activeSessionId;
  const isStreaming = state.busySessionIds.has(sid);

  if (sid) {
    sendWs({ type: 'getHistory', sessionId: sid, limit: 50 });
  }

  // Restore buffered thinking + text
  const pendingData = state.pendingRestore[sid];
  const thinkingBuf = state.sessionThinkingBuffers[sid] || pendingData?.thinking;
  const textBuf = state.sessionTexts[sid] || pendingData?.text;
  if (thinkingBuf || textBuf) {
    const chat = pv.dom.chat;

    if (thinkingBuf) {
      pv.stream.thinkingText = thinkingBuf;
      const hasText = !!textBuf;
      const done = hasText || !isStreaming;
      const row = document.createElement('div');
      row.className = 'row ai thinking-row';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai thinking-bubble' + (done ? ' thinking-done' : '');
      const label = document.createElement('div');
      label.className = 'thinking-label' + (done ? ' collapsible' : '');
      label.textContent = t('chat.thinkingLabel');
      const content = document.createElement('div');
      content.className = 'thinking-content';
      if (done) {
        content.style.display = 'none';
        label.classList.add('expanded');
        label.onclick = () => {
          const visible = content.style.display !== 'none';
          content.style.display = visible ? 'none' : '';
          label.classList.toggle('expanded', !visible);
        };
      }
      content.innerHTML = renderMarkdownWithMath(pv.stream.thinkingText, true, { cache: done }) + (!done ? '<span class="cursor"></span>' : '');
      bubble.appendChild(label);
      bubble.appendChild(content);
      row.appendChild(bubble);
      chat.appendChild(row);
      pv.stream.currentThinkingBubble = done ? null : bubble;
    }

    if (textBuf) {
      pv.stream.aiText = textBuf;
      const row = document.createElement('div');
      row.className = 'row ai';
      pv.stream.currentAiBubble = document.createElement('div');
      pv.stream.currentAiBubble.className = 'bubble ai';
      pv.stream.currentAiBubble.innerHTML = renderMarkdownWithMath(pv.stream.aiText, true, { cache: !isStreaming }) + (isStreaming ? '<span class="cursor"></span>' : '');
      row.appendChild(pv.stream.currentAiBubble);
      chat.appendChild(row);
      if (!isStreaming) {
        pv.stream.currentAiBubble = null;
        pv.stream.aiText = '';
      }
    }

    smartScroll();
  }

  if (state.sessionPendingTools[sid]) {
    renderToolPending(state.sessionPendingTools[sid].label, sid);
  }

  const isBusy = isStreaming;
  pv.dom.sendBtn.style.display = isBusy ? 'none' : 'flex';
  pv.dom.stopBtn.style.display = isBusy ? 'flex' : 'none';

  stopSpinner();

  if (!isBusy) pv.dom.input.focus();

  renderTaskList([], undefined, sid); // 首参占位——旧任务入参退役（2026-09-05 裁定纯节点视图）
  if (state.updateBgTasksUI) state.updateBgTasksUI();
  if (state.updateBgAgentIndicator) state.updateBgAgentIndicator();
  if (state.updateBypassToggle) state.updateBypassToggle(pv);
}

export function deleteSession(sessionId) {
  // Release any live card iframe observers/browsing contexts for this
  // session's messages before the DOM is dropped (P0-1/P1-5).
  if (activeView?.dom?.chat && state.activeSessionId === sessionId) {
    cleanupCardIframes(activeView.dom.chat);
  }
  delete state.sessionInputDrafts[sessionId];
  state.unreadSessions.delete(sessionId);
  state.markedUnreadSessions.delete(sessionId);
  state.pinnedSessions.delete(sessionId);
  // Session-keyed runtime state — purge so deleted sessions don't accumulate
  // stale buffers/DOM refs over the app's lifetime (P1-5).
  state.attentionSessions.delete(sessionId);
  state.busySessionIds.delete(sessionId);
  state.compactingSessionIds.delete(sessionId);
  state.selectedSessionIds.delete(sessionId);
  if (state.lastSelectedSessionId === sessionId) state.lastSelectedSessionId = null;
  delete state.sessionBusyTimeouts[sessionId];
  delete state.sessionTexts[sessionId];
  delete state.sessionAskBuffers[sessionId];
  delete state.sessionToolCards[sessionId];
  delete state.sessionPendingTools[sessionId];
  delete state.sessionPendingAiMessages[sessionId];
  // state.sessionTasks 已随旧任务区退役移除（2026-09-05 裁定）——无需清理
  delete state.sessionThinkingBuffers[sessionId];
  delete state.sessionBgTasks[sessionId];
  delete state.sessionBgAgents[sessionId];
  delete state.sessionModelInfo[sessionId];
  // Hidden bg-agent popup views hold full DOM containers keyed by sessionId.
  import('./bgAgentPopup.js').then(({ removeStepView }) => removeStepView(sessionId)).catch(() => {});
  // D2 (mem-diagnosis 20260907): purge the remaining session-keyed state —
  // messageQueue holds queued-message entries, pendingRestore/turn*/safetyModes
  // hold per-session buffers/flags, and agentStateTimers holds a live debounce
  // timer for the session's agent — all otherwise linger for the app lifetime.
  // (Capture the agent name BEFORE its map entry goes.)
  const agentName = state.sessionAgentMap[sessionId];
  delete state.sessionAgentMap[sessionId];
  if (agentName && state.agentStateTimers[agentName]) {
    clearTimeout(state.agentStateTimers[agentName]);
    delete state.agentStateTimers[agentName];
  }
  delete state.messageQueue[sessionId];
  delete state.pendingRestore[sessionId];
  delete state.turnStartTimes[sessionId];
  delete state.turnExpecting[sessionId];
  state.frozenSessions.delete(sessionId);
  state.answeredPermissions.delete(sessionId);
  delete state.safetyModes[sessionId];
  // Card accumulator entries for the deleted session's cards are orphans too.
  resetCardAccumulator();
  // Sync the persisted queue copy — otherwise the next page reload resurrects
  // the deleted session's queued messages from localStorage (restoreQueue).
  import('./input.js').then(({ persistQueue }) => persistQueue()).catch(() => {});
  persistUnread();
  persistMarkedUnread();
  persistPinned();
  persistDrafts();
  sendWs({type: 'deleteSession', sessionId});
}

export function deleteFolder(folderId) {
  // Remove from local state immediately so UI reflects deletion without waiting for server.
  // Cascading: collect all descendant folder IDs (children, grandchildren, etc.)
  const allFolders = state.folders || [];
  const descendantIds = (fid) => {
    const children = allFolders.filter(f => f.parentId === fid).map(f => f.id);
    return children.concat(children.flatMap(descendantIds));
  };
  const idsToRemove = new Set([folderId, ...descendantIds(folderId)]);
  state.folders = allFolders.filter(f => !idsToRemove.has(f.id));
  renderSessionSidebar(state.sessions, state.activeSessionId);
  sendWs({type: 'deleteFolder', folderId});
}

// ---------- Session status state machine ----------
// Priority: attention > compacting-bg > busy > marked unread > unread > idle
// Only one indicator visible at a time, controlled by a single CSS class.
// Attention takes priority over compacting so AskUser/permission prompts are visible
// even while the session is still technically compacting.
//
// Compact logic:
//   - Active session compacting: green compact info shown in chat area status bar;
//     sidebar shows 'busy' (falls through).
//   - Non-active session compacting: sidebar shows 'compacting' spinner.

function getSessionStatusClass(sessionId) {
  if (state.attentionSessions.has(sessionId)) return 'attention';
  // Only show 'compacting' spinner in sidebar for non-active sessions.
  // Active session shows compact info in the chat area status bar instead.
  if (state.compactingSessionIds.has(sessionId) && sessionId !== state.activeSessionId) return 'compacting';
  if (state.busySessionIds.has(sessionId)) return 'busy';
  if (state.markedUnreadSessions.has(sessionId) || state.unreadSessions.has(sessionId)) return 'unread';
  return '';
}

function persistMarkedUnread() {
  try {
    localStorage.setItem(key('marked_unread'), JSON.stringify([...state.markedUnreadSessions]));
  } catch(e) {}
}

function persistUnread() {
  try {
    localStorage.setItem(key('unread'), JSON.stringify([...state.unreadSessions]));
  } catch(e) {}
}

export { persistUnread };

function persistPinned() {
  try {
    localStorage.setItem(key('pinned'), JSON.stringify([...state.pinnedSessions]));
  } catch(e) {}
}

// ---------- Session context menu ----------

let activeCtxMenu = null;

function dismissCtxMenu() {
  if (activeCtxMenu) { activeCtxMenu.remove(); activeCtxMenu = null; }
}

function showSessionCtxMenu(x, y, sessionId) {
  dismissCtxMenu();
  const isPinned = state.pinnedSessions.has(sessionId);
  const session = state.sessions.find(s => s.id === sessionId);
  const currentFolderId = session?.folderId || null;

  // Build folder submenu HTML — unified list, no agent filtering
  const agentFolders = state.folders || [];
  let folderSubHtml = '';
  if (currentFolderId !== null) {
    folderSubHtml += '<div class="ctx-sub" data-folder-id="">' + t('ctx.removeFolder') + '</div>';
  }
  if (agentFolders.length > 0) {
    agentFolders.forEach(f => {
      if (f.id !== currentFolderId) {
        folderSubHtml += '<div class="ctx-sub" data-folder-id="' + f.id + '">' + escapeHtml(f.name) + '</div>';
      }
    });
  }
  if (!folderSubHtml) {
    folderSubHtml = '<div class="ctx-disabled">' + t('ctx.noFolders') + '</div>';
  }

  const menu = document.createElement('div');
  menu.className = 'session-ctx-menu';
  menu.innerHTML =
    '<div class="ctx-item" data-action="mark-unread">' + t('ctx.markUnread') + '</div>' +
    '<div class="ctx-item" data-action="toggle-pin">' + (isPinned ? t('ctx.unpin') : t('ctx.pin')) + '</div>' +
    '<div class="ctx-separator"></div>' +
    '<div class="ctx-item ctx-has-sub" data-action="move-to-folder">' + t('ctx.moveToFolder') + ' <span style="float:right;color:var(--color-frame-text-muted)">▸</span></div>' +
    '<div class="ctx-submenu" id="ctx-folder-submenu">' + folderSubHtml + '</div>';
  document.body.appendChild(menu);
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';

  menu.querySelector('[data-action="mark-unread"]').addEventListener('click', () => {
    state.markedUnreadSessions.add(sessionId);
    state.unreadSessions.delete(sessionId);
    persistUnread();
    persistMarkedUnread();
    updateSessionStatus(sessionId);
    dismissCtxMenu();
  });
  menu.querySelector('[data-action="toggle-pin"]').addEventListener('click', () => {
    if (isPinned) state.pinnedSessions.delete(sessionId);
    else state.pinnedSessions.add(sessionId);
    persistPinned();
    renderSessionSidebar(state.sessions, state.activeSessionId);
    dismissCtxMenu();
  });

  // Submenu interactions
  const subTrigger = menu.querySelector('[data-action="move-to-folder"]');
  const subMenu = menu.querySelector('#ctx-folder-submenu');
  let subTimer = null;

  subTrigger.addEventListener('mouseenter', () => {
    subTimer = setTimeout(() => { subMenu.style.display = 'block'; }, 100);
  });
  subTrigger.addEventListener('mouseleave', () => {
    if (subTimer) clearTimeout(subTimer);
  });
  subMenu.addEventListener('mouseenter', () => {
    if (subTimer) clearTimeout(subTimer);
  });
  subMenu.addEventListener('mouseleave', () => {
    subMenu.style.display = 'none';
  });

  subMenu.querySelectorAll('.ctx-sub').forEach(el => {
    el.addEventListener('click', () => {
      const fid = el.dataset.folderId;
      sendWs({ type: 'moveSessionToFolder', sessionId, folderId: fid || null });
      dismissCtxMenu();
    });
  });

  activeCtxMenu = menu;
}

// ---------- Batch Selection Context Menu ----------

function showBatchCtxMenu(x, y) {
  dismissCtxMenu();
  const count = state.selectedSessionIds.size;
  const allCount = state.sessions.length;
  const menu = document.createElement('div');
  menu.className = 'session-ctx-menu';
  menu.innerHTML =
    '<div class="ctx-item" data-action="batch-select-all">' + (count === allCount ? t('ctx.deselectAll') : t('ctx.selectAll', { count: allCount })) + '</div>' +
    '<div class="ctx-separator"></div>' +
    '<div class="ctx-item" data-action="batch-delete">' + t('ctx.batchDelete', { count }) + '</div>' +
    '<div class="ctx-item" data-action="batch-cancel">' + t('ctx.batchCancel') + '</div>';
  document.body.appendChild(menu);
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';

  menu.querySelector('[data-action="batch-select-all"]').addEventListener('click', () => {
    const allIds = state.sessions.map(s => s.id);
    const allSelected = allIds.every(id => state.selectedSessionIds.has(id));
    if (allSelected) {
      state.selectedSessionIds.clear();
    } else {
      allIds.forEach(id => state.selectedSessionIds.add(id));
    }
    state.lastSelectedSessionId = null;
    updateBatchToolbar();
    renderSessionSidebar(state.sessions, state.activeSessionId);
    dismissCtxMenu();
  });
  menu.querySelector('[data-action="batch-delete"]').addEventListener('click', () => {
    if (state.selectedSessionIds.size > 0) {
      showBatchDeleteModalLazy();
    }
    dismissCtxMenu();
  });
  menu.querySelector('[data-action="batch-cancel"]').addEventListener('click', () => {
    exitBatchMode();
    dismissCtxMenu();
  });
  activeCtxMenu = menu;
}

// ---------- Batch Selection Logic ----------

export function toggleSessionSelection(sessionId) {
  if (state.selectedSessionIds.has(sessionId)) {
    state.selectedSessionIds.delete(sessionId);
  } else {
    state.selectedSessionIds.add(sessionId);
  }
  state.lastSelectedSessionId = sessionId;
  updateBatchToolbar();
  // Full re-render to keep selection visuals consistent with Shift+select
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

function selectSessionRange(fromId, toId) {
  const fromSession = state.sessions.find(s => s.id === fromId);
  const toSession = state.sessions.find(s => s.id === toId);
  if (!fromSession || !toSession) return;

  const fromFolder = fromSession.folderId || null;
  const toFolder = toSession.folderId || null;

  if (fromFolder !== toFolder) {
    // Different levels — just select both, no range
    state.selectedSessionIds.add(fromId);
    state.selectedSessionIds.add(toId);
  } else {
    // Same level — select range within this level only
    const sameLevel = state.sessions.filter(s => (s.folderId || null) === fromFolder);
    sameLevel.sort((a, b) => {
      if (!fromFolder) {
        const pa = state.pinnedSessions.has(a.id) ? 1 : 0;
        const pb = state.pinnedSessions.has(b.id) ? 1 : 0;
        if (pb !== pa) return pb - pa;
      }
      return (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0);
    });
    const fromIdx = sameLevel.findIndex(s => s.id === fromId);
    const toIdx = sameLevel.findIndex(s => s.id === toId);
    if (fromIdx === -1 || toIdx === -1) return;
    const start = Math.min(fromIdx, toIdx);
    const end = Math.max(fromIdx, toIdx);
    for (let i = start; i <= end; i++) {
      state.selectedSessionIds.add(sameLevel[i].id);
    }
  }
  state.lastSelectedSessionId = toId;
  updateBatchToolbar();
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

export function exitBatchMode() {
  state.selectedSessionIds.clear();
  state.lastSelectedSessionId = null;
  updateBatchToolbar();
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

function updateBatchToolbar() {
  // Floating toolbar removed — batch actions are via drag-to-delete-zone or context menu
  const count = state.selectedSessionIds.size;
  let bar = document.getElementById('multi-select-bar');
  if (count === 0 && bar) bar.remove();
}

let _deleteZone = null;

function showDeleteZone() {
  const panel = document.getElementById('panel-sessions');
  if (!panel) return;
  if (_deleteZone) return;
  const zone = document.createElement('div');
  zone.className = 'delete-drop-zone';
  const count = state.selectedSessionIds.size;
  zone.innerHTML =
    '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
    '<span>' + (count > 1 ? t('session.dropDeleteCount', { count }) : t('session.dropDelete')) + '</span>';
  zone.addEventListener('dragover', (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    zone.classList.add('active');
  });
  zone.addEventListener('dragleave', () => {
    zone.classList.remove('active');
  });
  zone.addEventListener('drop', (e) => {
    e.preventDefault();
    zone.classList.remove('active');
    const data = e.dataTransfer.getData('text/plain');
    // Handle folder drop
    if (data.startsWith('folder:')) {
      const fid = data.slice(7);
      const f = state.folders.find(x => x.id === fid);
      if (f && typeof window.__showDeleteFolderModal === 'function') {
        window.__showDeleteFolderModal(fid, f.name);
      }
      return;
    }
    let ids = [];
    if (data.startsWith('batch:')) {
      ids = data.slice(6).split(',').filter(Boolean);
    } else if (data) {
      ids = [data];
    }
    if (ids.length > 0) {
      // If already in selection mode, show batch delete confirmation
      if (state.selectedSessionIds.size > 1) {
        showBatchDeleteModalLazy();
      } else {
        // Single drag — confirm delete
        const sid = ids[0];
        const s = state.sessions.find(x => x.id === sid);
        if (s && typeof window.__showDeleteModal === 'function') {
          window.__showDeleteModal(sid, s.name);
        }
      }
    }
  });
  panel.appendChild(zone);
  _deleteZone = zone;
}

function hideDeleteZone() {
  if (_deleteZone) {
    _deleteZone.remove();
    _deleteZone = null;
  }
}

export function batchDeleteSelected() {
  const ids = [...state.selectedSessionIds];
  if (ids.length === 0) return;
  sendWs({ type: 'batchDeleteSessions', sessionIds: ids });
  // Clean up local state
  ids.forEach(id => {
    delete state.sessionInputDrafts[id];
    state.unreadSessions.delete(id);
    state.markedUnreadSessions.delete(id);
    state.pinnedSessions.delete(id);
  });
  persistUnread();
  persistMarkedUnread();
  persistPinned();
  persistDrafts();
  exitBatchMode();
}

// Keyboard shortcuts for multi-select
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && state.selectedSessionIds.size > 0) {
    e.preventDefault();
    exitBatchMode();
  }
  if (e.key === 'Delete' && state.selectedSessionIds.size > 0) {
    e.preventDefault();
    showBatchDeleteModalLazy();
  }
});

// Dismiss on click outside
document.addEventListener('click', (e) => {
  if (activeCtxMenu && !activeCtxMenu.contains(e.target)) dismissCtxMenu();
});
document.addEventListener('contextmenu', (e) => {
  if (activeCtxMenu && !activeCtxMenu.contains(e.target)) dismissCtxMenu();
}, true);

function updateFolderStatus(folderId) {
  // Update this folder's DOM element if it exists (may be absent when parent is collapsed)
  const folderEl = state.dom.sessionList.querySelector(`.folder-item[data-folder-id="${folderId}"]`);
  if (folderEl) {
    const el = folderEl.querySelector('.folder-status');
    if (el) el.className = 'folder-status ' + getFolderStatusClass(folderId);
  }
  // Always cascade up to parent folder — even if this folder's DOM element is absent,
  // the status should still propagate to ancestors that ARE rendered.
  const folder = (state.folders || []).find(f => f.id === folderId);
  if (folder && folder.parentId) {
    updateFolderStatus(folder.parentId);
  }
}

export function updateSessionStatus(sessionId) {
  if (!sessionId || !state.dom.sessionList) return;
  const item = state.dom.sessionList.querySelector(`.session-item[data-id="${sessionId}"]`);
  if (item) {
    const el = item.querySelector('.session-status');
    if (el) el.className = 'session-status ' + getSessionStatusClass(sessionId);
  }
  // Cascade update parent folder status if session belongs to one
  const session = (state.sessions || []).find(s => s.id === sessionId);
  if (session && session.folderId) {
    updateFolderStatus(session.folderId);
  }
}

/** Show/hide the attention indicator for a session in the sidebar. */
export function setSessionAttention(sessionId, attention) {
  if (!sessionId) return;
  if (attention) state.attentionSessions.add(sessionId);
  else state.attentionSessions.delete(sessionId);
  updateSessionStatus(sessionId);
  // Also update agent notification dot for attention (yellow dot for askUser)
  const agentName = state.sessionAgentMap[sessionId];
  if (agentName && agentName !== state.selectedAgent) {
    if (attention) {
      state.agentUnreadCounts[agentName] = (state.agentUnreadCounts[agentName] || 0) + 1;
    } else {
      const cur = state.agentUnreadCounts[agentName] || 0;
      if (cur > 0) state.agentUnreadCounts[agentName] = cur - 1;
    }
    updateAgentNotificationDotExternal(agentName);
  }
}

// Update agent notification dot (callable from other modules)
function updateAgentNotificationDotExternal(agentName) {
  const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
  if (!el) return;
  const count = state.agentUnreadCounts[agentName] || 0;
  let dot = el.querySelector('.agent-notif-dot');
  if (count > 0) {
    if (!dot) {
      dot = document.createElement('div');
      dot.className = 'agent-notif-dot';
      el.appendChild(dot);
    }
  } else if (dot) {
    dot.remove();
  }
}

/** Update a session's activity timestamp without reordering it.
 *  Visual indicators (bold/unread dot) are handled by updateSessionStatus separately.
 *  Root sessions never move above folders — sorting is handled by renderSessionList.
 *  In-folder sessions update their folder's latest activity time for folder-level sorting. */
function markSessionActivity(sessionId) {
  // Don't update if session is busy (in progress)
  if (state.busySessionIds.has(sessionId)) return;
  if (!state.dom.sessionList) return;

  // Update updatedAt in state.sessions so future renders stay sorted
  const session = state.sessions.find(s => s.id === sessionId);
  if (!session) return;
  session.updatedAt = Date.now();

  const sessionList = state.dom.sessionList;
  const item = sessionList.querySelector(`.session-item[data-id="${sessionId}"]`);
  if (!item) return;

  // Update the time display to reflect the new activity
  const timeEl = item.querySelector('.session-time');
  if (timeEl) timeEl.textContent = formatSessionTime(session.updatedAt);

  // In-folder sessions: also update the folder's latestActivity so the folder
  // itself sorts correctly among root sessions on next render.
  if (item.classList.contains('in-folder')) {
    const folderEl = item.closest('.folder-section');
    if (folderEl) {
      const folderId = folderEl.dataset.id;
      if (folderId && state.folders[folderId]) {
        state.folders[folderId].latestActivity = session.updatedAt;
      }
    }
  }
}

// Listen for state changes from other modules (dispatched via CustomEvent)
window.addEventListener('session-busy', (e) => {
  updateSessionStatus(e.detail.sessionId);
  computeAgentStates();
});

window.addEventListener('session-attention', (e) => {
  setSessionAttention(e.detail.sessionId, e.detail.attention);
  computeAgentStates();
});

window.addEventListener('session-unread', (e) => {
  updateSessionStatus(e.detail.sessionId);
  markSessionActivity(e.detail.sessionId);
});

window.addEventListener('session-compacting', (e) => {
  updateSessionStatus(e.detail.sessionId);
  computeAgentStates();
});

/** Refresh header model-info bar for the active session. */
export function initHeaderModelInfo() {
  if (typeof state.updateHeaderModelInfo === 'function') {
    state.updateHeaderModelInfo();
  }
}


/** Determine which agent a new session/folder should belong to, based on context.
 *  Mirrors the folder-id resolution logic: active folder → active session → fallback. */
export function getTargetAgent() {
  // 1. Active folder context
  if (state.activeFolderId) {
    const folder = (state.folders || []).find(f => f.id === state.activeFolderId);
    if (folder?.agentName) return folder.agentName;
  }
  // 2. Active session
  const active = (state.sessions || []).find(s => s.id === state.activeSessionId);
  if (active?.agentName) return active.agentName;
  return 'Nebula';
}

/** Resolve the target folder ID for creating new sessions/folders, based on context.
 *  Priority: explicitly selected folder → active session's folder. */
export function getCurrentFolderId() {
  // 1. Active folder context (user clicked on a folder in the sidebar)
  if (state.activeFolderId) return state.activeFolderId;
  // 2. Active session
  const active = (state.sessions || []).find(s => s.id === state.activeSessionId);
  return active?.folderId || null;
}

/** Build a human-readable breadcrumb path for a folder ID, e.g. "项目A / 模块B". */
export function getTargetPath(folderId) {
  if (!folderId) return null;
  const allFolders = state.folders || [];
  const parts = [];
  let current = allFolders.find(f => f.id === folderId);
  while (current) {
    parts.unshift(current.name);
    current = current.parentId ? allFolders.find(f => f.id === current.parentId) : null;
  }
  return parts.length > 0 ? parts.join(' / ') : null;
}

/** Create a new folder inline (no prompt). Inserts an editable row into the sidebar. */
export function createNewFolder(parentFolderId) {
  let container;
  if (parentFolderId) {
    // Ensure parent folder is expanded so children container exists
    if (!state.expandedFolders.has(parentFolderId)) {
      state.expandedFolders.add(parentFolderId);
      persistExpandedFolders();
      renderSessionSidebar(state.sessions, state.activeSessionId);
    }
    const wrapper = state.dom.sessionList.querySelector('.folder-wrapper[data-folder-id="' + parentFolderId + '"]');
    container = wrapper ? wrapper.querySelector('.folder-children') : null;
  } else {
    container = document.getElementById('session-list');
  }
  if (!container) return;

  // Remove any existing inline-new-folder input
  container.querySelectorAll('.folder-new-row').forEach(el => el.remove());

  const targetPath = getTargetPath(parentFolderId) || t('path.root');

  const row = document.createElement('div');
  row.className = 'folder-new-row';
  row.innerHTML =
    '<div class="creation-path">' + escapeHtml(targetPath) + ' &gt;</div>' +
    '<div class="folder-new-row-inner">' +
    '<div class="folder-new-arrow"><i data-lucide="chevron-right"></i></div>' +
    '<div class="folder-new-icon"><i data-lucide="folder"></i></div>' +
    '<input class="folder-new-input" placeholder="' + t('folder.newPlaceholder') + '">' +
    '</div>';
  container.insertBefore(row, container.firstChild);

  const input = row.querySelector('.folder-new-input');
  if (typeof lucide !== 'undefined') createIconsIn(row);
  input.focus();

  const cancel = () => row.remove();
  const confirm = () => {
    const name = input.value.trim();
    row.remove();
    if (name) {
      const payload = { type: 'createFolder', name, agentName: getTargetAgent() };
      if (parentFolderId) payload.parentId = parentFolderId;
      import('./ws.js').then(({ sendWs }) => sendWs(payload));
    }
  };

  // ⑤ 组字期间 Enter/Esc 交还输入法（非组字态行为逐键不变）。
  bindImeGuard(input);
  input.addEventListener('keydown', (e) => {
    if (isImeComposing(e, input)) return;
    if (e.key === 'Enter') { e.preventDefault(); confirm(); }
    if (e.key === 'Escape') { e.preventDefault(); cancel(); }
  });
  input.addEventListener('blur', () => {
    // Small delay so Enter can fire first
    setTimeout(() => {
      if (document.activeElement !== input && row.parentNode) cancel();
    }, 150);
  });
}

// ===== Folder helpers =====

function persistExpandedFolders() {
  try {
    localStorage.setItem(key('expanded_folders'), JSON.stringify([...state.expandedFolders]));
  } catch(e) {}
}

function persistPinnedFolders() {
  try {
    localStorage.setItem(key('pinned_folders'), JSON.stringify([...state.pinnedFolders]));
  } catch(e) {}
}

// Collect all descendant folder IDs (including the folder itself) recursively
function getAllDescendantFolderIds(folderId) {
  const allFolders = state.folders || [];
  const result = new Set([folderId]);
  const children = allFolders.filter(f => f.parentId === folderId);
  for (const child of children) {
    const desc = getAllDescendantFolderIds(child.id);
    desc.forEach(id => result.add(id));
  }
  return result;
}

function getFolderStatusClass(folderId, sessions) {
  const allSessions = sessions || (state.sessions || []);
  const descendantIds = getAllDescendantFolderIds(folderId);
  const folderSessions = allSessions.filter(s => descendantIds.has(s.folderId));
  for (const s of folderSessions) {
    if (state.attentionSessions.has(s.id)) return 'attention';
  }
  for (const s of folderSessions) {
    if (state.compactingSessionIds.has(s.id) && s.id !== state.activeSessionId) return 'compacting';
  }
  for (const s of folderSessions) {
    if (state.busySessionIds.has(s.id)) return 'busy';
  }
  for (const s of folderSessions) {
    if (state.markedUnreadSessions.has(s.id) || state.unreadSessions.has(s.id)) return 'unread';
  }
  return '';
}

function renderFolderItem(folder, sessions, container) {
  const folderWrapper = document.createElement('div');
  folderWrapper.className = 'folder-wrapper';
  folderWrapper.dataset.folderId = folder.id;

  const isExpanded = state.expandedFolders.has(folder.id);
  const isPinned = state.pinnedFolders.has(folder.id);

  const folderEl = document.createElement('div');
  folderEl.className = 'folder-item'
    + (isPinned ? ' pinned' : '')
    + (state.activeFolderId === folder.id ? ' active' : '');
  folderEl.dataset.folderId = folder.id;
  folderEl.draggable = true;

  // Always use state.sessions (not the passed-in direct children) so subfolder
  // sessions are included in the status calculation.
  const statusCls = getFolderStatusClass(folder.id);

  folderEl.innerHTML =
    '<div class="folder-arrow">' +
      (isExpanded
        ? '<i data-lucide="chevron-down"></i>'
        : '<i data-lucide="chevron-right"></i>') +
    '</div>' +
    '<div class="folder-icon"><i data-lucide="folder"></i></div>' +
    '<div class="folder-name">' + escapeHtml(folder.name) + '</div>' +
    (folder.projectRoot ? '<span class="folder-badge" title="' + escapeHtml(folder.projectRoot) + '"><i data-lucide="hard-drive" style="width:12px;height:12px"></i></span>' : '') +
    '<div class="folder-status ' + statusCls + '">' +
      '<div class="status-spinner"><i data-lucide="loader-2"></i></div>' +
      '<div class="status-compact-spinner"><i data-lucide="minimize-2"></i></div>' +
      '<div class="folder-status-dot"></div>' +
    '</div>' +
    '<button class="folder-delete" title="' + t('sidebar.folderDelete') + '"><i data-lucide="x"></i></button>';

  // Drag: folder itself can be dragged (into another folder) or to delete zone
  folderEl.addEventListener('dragstart', (e) => {
    e.dataTransfer.setData('text/plain', 'folder:' + folder.id);
    e.dataTransfer.effectAllowed = 'move';
    folderEl.classList.add('dragging');
    showDeleteZone();
  });
  folderEl.addEventListener('dragend', () => {
    folderEl.classList.remove('dragging');
    document.querySelectorAll('.folder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
    hideDeleteZone();
  });

  // VSCode-style: click selects folder + expand/collapse
  folderEl.addEventListener('click', (e) => {
    if (e.target.closest('.folder-delete')) return;
    if (e.target.closest('.folder-name[contenteditable="true"]')) return;
    setActiveFolder(folder.id);
    toggleFolder(folder.id);
  });

  // Right-click context menu
  folderEl.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    showFolderCtxMenu(e.clientX, e.clientY, folder.id);
  });

  // Double-click to rename folder (inline, same as session)
  const folderNameEl = folderEl.querySelector('.folder-name');
  const startFolderRename = () => {
    folderNameEl.contentEditable = true;
    folderNameEl.focus();
    const range = document.createRange();
    range.selectNodeContents(folderNameEl);
    const sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  };
  folderNameEl.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    startFolderRename();
  });
  const finishFolderRename = () => {
    folderNameEl.contentEditable = false;
    const newName = folderNameEl.textContent.trim();
    if (newName && newName !== folder.name) {
      sendWs({ type: 'renameFolder', folderId: folder.id, name: newName });
    } else {
      folderNameEl.textContent = folder.name;
    }
  };
  folderNameEl.addEventListener('blur', finishFolderRename);
  bindImeGuard(folderNameEl);
  folderNameEl.addEventListener('keydown', (e) => {
    if (isImeComposing(e, folderNameEl)) return; // ⑤ 组字期间交还输入法
    if (e.key === 'Enter') {
      e.preventDefault(); folderNameEl.blur();
    }
    if (e.key === 'Escape') { folderNameEl.textContent = folder.name; folderNameEl.blur(); }
  });

  // Delete button
  folderEl.querySelector('.folder-delete').addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof window.__showDeleteFolderModal === 'function') {
      window.__showDeleteFolderModal(folder.id, folder.name);
    }
  });

  // Dragover highlight for folder scope
  folderEl.addEventListener('dragover', (e) => {
    folderEl.classList.add('drag-over');
  });
  folderEl.addEventListener('dragleave', () => {
    folderEl.classList.remove('drag-over');
  });

  folderWrapper.appendChild(folderEl);

  // Render children if expanded (sub-folders first, then sessions)
  if (isExpanded) {
    const childrenContainer = document.createElement('div');
    childrenContainer.className = 'folder-children';

    // Rules.md entry — only shown when rules exist for this folder
    if (state.foldersWithRules && state.foldersWithRules.has(folder.id)) {
      const rulesItem = document.createElement('div');
      rulesItem.className = 'session-item in-folder rules-item';
      rulesItem.innerHTML =
        '<div class="session-icon"><i data-lucide="file-text" style="width:14px;height:14px;color:var(--color-text-muted)"></i></div>' +
        '<div class="session-name">rules.md</div>';
      rulesItem.addEventListener('click', (e) => {
        e.stopPropagation();
        openRulesEditor(folder.id, folder.name);
      });
      childrenContainer.appendChild(rulesItem);
    }

    // Sub-folders — alphabetically sorted
    const subFolders = (state.folders || [])
      .filter(f => f.parentId === folder.id)
      .sort((a, b) => a.name.localeCompare(b.name));
    subFolders.forEach(sub => {
      const subSessions = (state.sessions || []).filter(s => s.folderId === sub.id)
        .sort((a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0));
      renderFolderItem(sub, subSessions, childrenContainer);
    });

    // Sessions inside this folder
    sessions.forEach(s => {
      renderOneSessionItem(s, childrenContainer, { inFolder: true });
    });
    // Dragover highlight for expanded children area
    childrenContainer.addEventListener('dragover', (e) => {
      folderEl.classList.add('drag-over');
    });
    childrenContainer.addEventListener('dragleave', (e) => {
      if (!childrenContainer.contains(e.relatedTarget)) {
        folderEl.classList.remove('drag-over');
      }
    });
    folderWrapper.appendChild(childrenContainer);
  }

  container.appendChild(folderWrapper);
}

export function toggleFolder(folderId) {
  if (state.expandedFolders.has(folderId)) {
    state.expandedFolders.delete(folderId);
  } else {
    state.expandedFolders.add(folderId);
  }
  persistExpandedFolders();
  renderSessionSidebar(state.sessions, state.activeSessionId);
}

function showFolderCtxMenu(x, y, folderId) {
  dismissCtxMenu();
  const isPinned = state.pinnedFolders.has(folderId);
  const folder = state.folders.find(f => f.id === folderId);
  const hasParent = folder && folder.parentId;
  const menu = document.createElement('div');
  menu.className = 'session-ctx-menu';
  let html =
    '<div class="ctx-item" data-action="toggle-pin">' + (isPinned ? t('ctx.unpin') : t('ctx.pin')) + '</div>' +
    '<div class="ctx-item" data-action="rename">' + t('ctx.rename') + '</div>' +
    '<div class="ctx-item" data-action="new-subfolder">' + t('ctx.newSubfolder') + '</div>';
  // Project root — available for all folders
  html += '<div class="ctx-separator"></div>' +
    '<div class="ctx-item" data-action="set-project-root">' + t('ctx.setProjectRoot') + '</div>';
  // Rules — dynamic: create if not exists, edit if exists
  const hasRules = state.foldersWithRules && state.foldersWithRules.has(folderId);
  html += '<div class="ctx-item" data-action="edit-rules">' + (hasRules ? t('ctx.editRules') : t('ctx.createRules')) + '</div>';
  if (hasParent) {
    html += '<div class="ctx-item" data-action="move-out">' + t('ctx.moveOut') + '</div>';
  }
  html +=
    '<div class="ctx-separator"></div>' +
    '<div class="ctx-item" data-action="delete" style="color:var(--color-error)">' + t('ctx.deleteFolder') + '</div>';
  menu.innerHTML = html;

  menu.querySelector('[data-action="toggle-pin"]').addEventListener('click', () => {
    if (isPinned) state.pinnedFolders.delete(folderId);
    else state.pinnedFolders.add(folderId);
    persistPinnedFolders();
    renderSessionSidebar(state.sessions, state.activeSessionId);
    dismissCtxMenu();
  });

  menu.querySelector('[data-action="rename"]').addEventListener('click', () => {
    dismissCtxMenu();
    if (!folder) return;
    // Trigger inline edit on the folder name element
    const folderEl = state.dom.sessionList.querySelector(`.folder-item[data-folder-id="${folderId}"]`);
    const nameEl = folderEl ? folderEl.querySelector('.folder-name') : null;
    if (nameEl) {
      nameEl.contentEditable = true;
      nameEl.focus();
      const range = document.createRange();
      range.selectNodeContents(nameEl);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
  });

  menu.querySelector('[data-action="new-subfolder"]').addEventListener('click', () => {
    dismissCtxMenu();
    createNewFolder(folderId);
  });

  // Set project root — opens directory picker
  menu.querySelector('[data-action="set-project-root"]').addEventListener('click', () => {
    if (!folder) return;
    openPathPicker(folderId, folder.projectRoot);
    dismissCtxMenu();
  });

  // Edit rules
  menu.querySelector('[data-action="edit-rules"]').addEventListener('click', () => {
    if (!folder) return;
    openRulesEditor(folderId, folder.name);
    dismissCtxMenu();
  });

  if (hasParent) {
    menu.querySelector('[data-action="move-out"]').addEventListener('click', () => {
      sendWs({ type: 'moveFolder', folderId, parentId: null });
      dismissCtxMenu();
    });
  }

  menu.querySelector('[data-action="delete"]').addEventListener('click', () => {
    if (folder && typeof window.__showDeleteFolderModal === 'function') {
      window.__showDeleteFolderModal(folder.id, folder.name);
    }
    dismissCtxMenu();
  });

  document.body.appendChild(menu);
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';
  activeCtxMenu = menu;
}

/** Open rules editor modal for a folder — Nebflow standard modal. */
let activeRulesFolderId = null;
let activeRulesExists = false;
function openRulesEditor(folderId, folderName) {
  activeRulesFolderId = folderId;
  activeRulesExists = state.foldersWithRules && state.foldersWithRules.has(folderId);
  const title = document.getElementById('rules-modal-title');
  const input = document.getElementById('rules-input');
  const deleteBtn = document.getElementById('rules-modal-delete');
  title.textContent = t('rules.title') + ' — ' + folderName;
  input.value = '';
  input.placeholder = t('rules.placeholder');
  deleteBtn.style.display = activeRulesExists ? 'inline-block' : 'none';
  deleteBtn.textContent = t('rules.delete');
  document.getElementById('rules-overlay').classList.add('on');
  document.getElementById('rules-modal').classList.add('show');
  // Fetch current content
  sendWs({ type: 'getRules', folderId });
  setTimeout(() => input.focus(), 50);
}

function closeRulesEditor() {
  activeRulesFolderId = null;
  document.getElementById('rules-overlay').classList.remove('on');
  document.getElementById('rules-modal').classList.remove('show');
}

/** Handle rulesData from server. */
export function handleRulesData(data) {
  if (activeRulesFolderId === data.folderId) {
    const input = document.getElementById('rules-input');
    if (input) input.value = data.content || '';
  }
}

/** Handle rulesSaved from server. */
export function handleRulesSaved(data) {
  if (data.folderId) {
    if (!state.foldersWithRules) state.foldersWithRules = new Set();
    state.foldersWithRules.add(data.folderId);
    // Re-render sidebar to show rules.md entry
    const activeId = state.activeSessionId;
    renderSessionSidebar(state.sessions, activeId);
    closeRulesEditor();
  }
}

/** Handle rulesDeleted from server. */
export function handleRulesDeleted(data) {
  if (data.folderId) {
    if (state.foldersWithRules) state.foldersWithRules.delete(data.folderId);
    const activeId = state.activeSessionId;
    renderSessionSidebar(state.sessions, activeId);
    closeRulesEditor();
  }
}

// ----- Rules Modal wiring (called from modal.js initModals) -----
export function initRulesModal() {
  document.getElementById('rules-modal-cancel').addEventListener('click', closeRulesEditor);
  document.getElementById('rules-overlay').addEventListener('click', (e) => {
    if (e.target.id === 'rules-overlay') closeRulesEditor();
  });
  document.getElementById('rules-modal-save').addEventListener('click', () => {
    const content = document.getElementById('rules-input').value;
    sendWs({ type: 'saveRules', folderId: activeRulesFolderId, content });
  });
  document.getElementById('rules-modal-delete').addEventListener('click', () => {
    if (!activeRulesFolderId) return;
    sendWs({ type: 'deleteRules', folderId: activeRulesFolderId });
  });
}

// ===== Path Picker Modal =====
let pathPickerFolderId = null;
let pathPickerCurrentPath = '';
let pathPickerCallback = null;

export function openPathPicker(folderId, currentRoot) {
  pathPickerFolderId = folderId;
  pathPickerCallback = null;
  const startPath = currentRoot || '~';
  document.getElementById('path-picker-title').textContent = t('pathPicker.title');
  document.getElementById('path-picker-cancel').textContent = t('modal.cancel');
  document.getElementById('path-picker-clear').textContent = t('pathPicker.clear');
  document.getElementById('path-picker-select').textContent = t('pathPicker.select');
  // Show/hide clear button based on current state
  document.getElementById('path-picker-clear').style.display = currentRoot ? 'inline-block' : 'none';
  document.getElementById('path-picker-overlay').classList.add('on');
  document.getElementById('path-picker-modal').classList.add('show');
  browseTo(startPath);
}

export function openPathPickerCallback(currentRoot, callback) {
  pathPickerFolderId = null;
  pathPickerCallback = callback;
  const startPath = currentRoot || '~';
  document.getElementById('path-picker-title').textContent = t('pathPicker.title');
  document.getElementById('path-picker-cancel').textContent = t('modal.cancel');
  document.getElementById('path-picker-select').textContent = t('pathPicker.select');
  document.getElementById('path-picker-clear').style.display = 'none';
  document.getElementById('path-picker-overlay').classList.add('on');
  document.getElementById('path-picker-modal').classList.add('show');
  browseTo(startPath);
}

function closePathPicker() {
  pathPickerFolderId = null;
  pathPickerCallback = null;
  pathPickerCurrentPath = '';
  document.getElementById('path-picker-overlay').classList.remove('on');
  document.getElementById('path-picker-modal').classList.remove('show');
}

function browseTo(path) {
  sendWs({ type: 'browsePath', path });
}

function buildBreadcrumb(path) {
  const bc = document.getElementById('path-picker-breadcrumb');
  const parts = path.split('/').filter(Boolean);
  let html = '';
  let accumulated = '';
  // root
  if (path.startsWith('/')) {
    html += '<span data-path="/">/</span>';
    accumulated = '/';
  }
  parts.forEach((part, i) => {
    accumulated += (accumulated.endsWith('/') ? '' : '/') + part;
    const p = accumulated;
    if (i < parts.length - 1) {
      html += ' / <span data-path="' + escapeHtml(p) + '">' + escapeHtml(part) + '</span>';
    } else {
      html += ' / ' + escapeHtml(part);
    }
  });
  bc.innerHTML = html;
  bc.querySelectorAll('span[data-path]').forEach(span => {
    span.addEventListener('click', () => browseTo(span.dataset.path));
  });
}

export function handleBrowseResult(data) {
  const list = document.getElementById('path-picker-list');
  if (!list) return;
  pathPickerCurrentPath = data.path || '';
  buildBreadcrumb(pathPickerCurrentPath);
  const entries = data.entries || [];
  list.innerHTML = '';
  list.removeAttribute('data-empty');
  if (data.error) {
    list.setAttribute('data-empty', data.error);
    return;
  }
  // Parent directory item — hidden at filesystem root
  if (pathPickerCurrentPath !== '/') {
    const parentItem = document.createElement('div');
    parentItem.className = 'pp-item';
    parentItem.innerHTML = '<span class="pp-icon">..</span><span class="pp-name">..</span>';
    parentItem.addEventListener('click', () => {
      const parts = pathPickerCurrentPath.replace(/\/$/, '').split('/');
      parts.pop();
      const parent = parts.join('/') || '/';
      browseTo(parent);
    });
    list.appendChild(parentItem);
  }

  if (entries.length === 0) {
    const emptyHint = document.createElement('div');
    emptyHint.style.cssText = 'text-align:center;padding:24px;color:var(--color-frame-text-muted);font-size:12px;';
    emptyHint.textContent = t('pathPicker.empty');
    list.appendChild(emptyHint);
    return;
  }

  entries.forEach(entry => {
    const item = document.createElement('div');
    item.className = 'pp-item';
    item.innerHTML = '<span class="pp-icon">&#128193;</span><span class="pp-name">' + escapeHtml(entry.name) + '</span>';
    item.addEventListener('click', () => browseTo(entry.path));
    list.appendChild(item);
  });
}

export function initPathPicker() {
  document.getElementById('path-picker-cancel').addEventListener('click', closePathPicker);
  document.getElementById('path-picker-overlay').addEventListener('click', (e) => {
    if (e.target.id === 'path-picker-overlay') closePathPicker();
  });
  document.getElementById('path-picker-select').addEventListener('click', () => {
    // Callback mode (Explorer folder picker)
    if (pathPickerCallback) {
      pathPickerCallback(pathPickerCurrentPath);
      closePathPicker();
      return;
    }
    // Folder mode
    if (!pathPickerFolderId) return;
    sendWs({ type: 'setFolderProjectRoot', folderId: pathPickerFolderId, projectRoot: pathPickerCurrentPath });
    closePathPicker();
    window.dispatchEvent(new CustomEvent('project-root-changed'));
  });
  document.getElementById('path-picker-clear').addEventListener('click', () => {
    if (pathPickerCallback) {
      pathPickerCallback(null);
      closePathPicker();
      return;
    }
    if (!pathPickerFolderId) return;
    sendWs({ type: 'setFolderProjectRoot', folderId: pathPickerFolderId, projectRoot: null });
    closePathPicker();
    window.dispatchEvent(new CustomEvent('project-root-changed'));
  });
}

export function moveSessionToFolder(sessionId, folderId) {
  sendWs({ type: 'moveSessionToFolder', sessionId, folderId });
}

