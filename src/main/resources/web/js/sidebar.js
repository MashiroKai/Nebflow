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
import { renderMarkdownWithMath, smartScroll, stopSpinner, createIconsIn, statusIcon } from './utils.js';
import { finishAgent, setStatus, renderToolPending, cancelThinkingRAF } from './chat.js';
import { restoreFromStorage, loadMsgs } from './persistence.js';
import { renderTaskList } from './taskList.js';
import { clearMemoryCache } from './memory.js';
// eyeSvg/eyeOffSvg（provider 密码域可见性切换图标）单源 = sidebar/providersUi.js（FE 拆分后
// 迁入；JEV 密钥引用行本地批 2026-10-09 的新调用点自模块引入，禁第二份字面量）。
import { eyeSvg, eyeOffSvg } from './sidebar/providersUi.js';
import { chatViews, setActiveView, activeView } from './chatView.js';
import { cleanupCardIframes, resetCardAccumulator } from './cardRegistry.js';
import { t, getLocale, setLocale, getAvailableLocales } from './i18n.js';
import { fetchNeblinkStatus, neblinkSettingsHTML, bindNeblinkEvents, avatarViewState, noteAvatarFailure, paintAvatarSlot } from './neblink.js';
// updateCheck.js（更新检查/更新执行链）已随 auth-trim 批（2026-10-20 作者令）整体删除：
// 设置页「更新」功能退役，未来由官网统一检测更新取代。重启面（restartStatus/
// restartResult 帧消费）留在本文件（见 bindSettingsEvents 后的模块级订阅）。
import { toggleHTML, setToggleState } from './toggle.js';
import { authHeaders } from './flowHelpers.js';
import { preloadModelCapabilities, renderVisionBadge } from './modelCapabilities.js';
import { renderAppearanceSection, bindAppearanceEvents } from './orbSettingsUI.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';
// Root agent's DISPLAY name — the single read point shared with onboarding /
// micOrb / chat / modelPanel (leaf module; no cycle risk).
import { rootDisplayName, isRootNamed } from './rootName.js';
// 文件缓存设置行（M2 · 设计 v1 §6.4/§7 表5）：占用读数/清空 seam 单源 = nfFileCache.js。
// 依赖纪律安全：nfFileCache 只 import state.js/branding.js（后者为叶子），本边不成环。
import { nfCacheStats, nfCacheReset } from './nfFileCache.js';
// uifix3 ② (2026-10-06 21:36 author order): the provider model-list probe is
// SHARED with the onboarding model question — implementation lives in
// providerModels.js (moved verbatim from here), one probe, no second copy.
// 合流落位：探针在本文件零消费点（唯一调用点 wireProviderModelFetch 随 Provider 界面族
// 迁 ./sidebar/providersUi.js，该件已自 ../providerModels.js 单源 import）⇒ 本文件
// 不再空挂该 import（A 侧单源语义由 providersUi.js 承接，零丢失）。

// sidebar.js 拆分(FE组件化批次二 2026-09-27):三簇实现迁至 js/sidebar/ 子模块 —
// Provider 界面族(providersUi.js)、Rules + PathPicker 模态(pathRules.js)。
// (预设管理 UI presetsAdmin.js 已随 A 线退役 presets.js 一并删除,合并 2026-10-02。)
// 本文件保持单向 import(子模块不得反向 import
// sidebar.js),公共导出面(批次前 41 符号)经下方 `export ... from` 显式转发,
// 外部 import 方零改动。
import { protocolFormLabel, escapeHtml, flushConfigToServer, showProviderModal, saveNewProvider } from './sidebar/providersUi.js';
export { saveNewProvider, openProviderWizard, validateContextWindow, CTX_WINDOW_HARD_CEILING } from './sidebar/providersUi.js';
import { openRulesEditor, openPathPicker, injectRenderSessionSidebar } from './sidebar/pathRules.js';
export { handleRulesData, handleRulesSaved, handleRulesDeleted, initRulesModal, openPathPicker, openPathPickerCallback, handleBrowseResult, initPathPicker } from './sidebar/pathRules.js';
// pathRules 的 handleRulesSaved/handleRulesDeleted 需重渲会话侧栏(该段留守本
// 文件):模块求值即注入,函数声明提升保证 renderSessionSidebar 此处已可引用。
injectRenderSessionSidebar(renderSessionSidebar);

// 2026-09-03 作者裁定：光球（micOrb）按预设驱动，设置页隐藏光球配置区。
// 仅 UI 门控——orbSettingsUI/orbPresets/micOrb 代码与配置读取逻辑全部保留，
// 用户本地已存自定义配置照常生效；翻回 true 即恢复配置区。
const ORB_SETTINGS_VISIBLE = false;

// 2026-09-05 作者裁定（设置页清理批②）：新手运行指导入口隐藏封存。与
// SIDEBAR_LEGACY_ENTRIES 同款 flag 形态——false = 渲染与绑定整体门控；
// 按钮/绑定代码与 i18n 键 settings.rerunOnboarding 保留不删（隐藏 ≠ 删除），
// 翻回 true 即原样回归。onboarding 本体与 /onboarding slash 命令零触碰。
const SHOW_RERUN_ONBOARDING = false;

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
/** DISPLAY name of an agent by mechanism name: `agentList` carries
 *  `displayName || name`, so the root agent renders the user's chosen name.
 *  Judgement keys (`a.name === 'Nebula'` below) stay untouched — display only. */
function agentDisplayLabel(agentName) {
  const a = (state.agentsData || []).find(x => x.name === agentName);
  return a ? (a.displayName || a.name || agentName) : agentName;
}

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

// 权限模式（全局档位）设置页控件已于 permshield F1（2026-09-13，作者重裁
// 「候选 B」①「删设置页，盾牌改成写全局」）**整体删除**——控件、事件绑定与
// 7 个 i18n 键一并移除；`PUT /api/safety/mode` 的前端调用点随之归零（REST 端点
// 由后端保留，供外部/脚本使用）。档位的**唯一 UI 入口 = 顶栏盾牌**
// （`web/js/main.js` `initSafetyToggle`，写的是同一条全局持久路径）。
// ── Jev 面板三改（用户 2026-10-09 23:11 截图反馈）─────────────────────────
// 钉死端点单点：Jev 版（provider=typesafe-jev）的端点是固定的——镜像后端
// DecisionProvider.DefaultTypesafeEndpoint（llm/decision/DecisionProvider.scala:270），
// 前端无后端常量导入面，此处是唯一拼写点（渲染钉死 / 提供方切换 / 恢复钉死共用）。
const JEV_PINNED_ENDPOINT = 'https://api.typesafe.ai/v1/systemone';

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
            <source media="(prefers-color-scheme: dark)" srcset="${brand.logoDarkUrl}">
            <img class="settings-avatar-logo" src="${brand.logoUrl}" alt="">
          </picture>
          <img class="settings-avatar-photo" alt="" hidden>
        </span>
      </button>
      ${neblinkSettingsHTML()}
      <div id="settings-devices-slot"></div>
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
      <div id="device-stamp-section"></div>
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
    <div class="cfg-hint" id="model-chain-hint">${t('settings.modelChainHint')}</div>
    <div class="settings-section">
      <!-- Jev card (executor-registry batch, 2026-10-03; ui-train-threefix B,
           2026-10-07 toggle-ized; secrets-ui-redesign 2026-10-08; 三改
           2026-10-09 用户反馈①②③). 收起态 = 标题「Jev（插件分配）」+ 开关
           （+chevron）一行：行体热区是真实 button (#jev-head, 含标题+chevron，
           aria-expanded/aria-controls)，点击原位展开/收起细节——开关是行内
           兄弟钮，不进热区（三改①，密钥面板行内化同语言：常态极简、点行
           展开、collapse ≠ clear）。开关只作用法闸（usage gate），与展开态
           解耦（旧「开=展开」耦合随三改①退役）。分配通道状态行随细节入
           折叠体（展开才见，三改①原话「点击展开才显示细节」）。端点行按
           提供方钉死：typesafe-jev → 预填 JEV_PINNED_ENDPOINT + disabled
           禁用态（用户只填密钥引用，三改②）；laya 保持可编辑。keyRef 是
           指针 NAME（.cfg-password-wrap + 眼睛，provider-modal 先例），保存
           仍是显式 PUT /api/config/jev 白名单键。健康徽标读 legs 分腿载荷
           （三改③，jev 不可用→已回退 llm 的如实显示见渲染块）。-->
      <div class="cfg-card" id="jev-card">
        <div class="cfg-card-head">
          <button type="button" class="cfg-card-head-btn" id="jev-head" aria-expanded="false" aria-controls="jev-detail-body" title="${t('settings.jevToggleDetails')}">
            <span class="cfg-card-title">${t('settings.jev')}</span>
            <span class="cfg-head-chev" aria-hidden="true">${statusIcon('chevron-right', 14)}</span>
          </button>
          ${toggleHTML({ on: !!(cfg.jev && cfg.jev.enabled), id: 'toggle-jev', label: t('settings.jevEnabled') })}
        </div>
        <div id="jev-detail-body" style="display:none;">
          <div class="cfg-card-divider"></div>
          <div class="cfg-row">
            <span class="cfg-row-label">${t('settings.jevProvider')}</span>
            <span class="cfg-row-main">
              <select class="cfg-select" id="cfg-jev-provider">
                ${['typesafe-jev', 'laya'].map(p => `<option value="${p}"${(cfg.jev && cfg.jev.provider) === p ? ' selected' : ''}>${p}</option>`).join('')}
              </select>
            </span>
          </div>
          <div class="cfg-row">
            <span class="cfg-row-label">${t('settings.jevEndpoint')}</span>
            <span class="cfg-row-main"><input class="cfg-input" id="cfg-jev-endpoint" placeholder="${(cfg.jev && cfg.jev.provider) === 'laya' ? 'http://127.0.0.1:8080/predict' : JEV_PINNED_ENDPOINT}" value="${escapeHtml((cfg.jev && cfg.jev.provider) === 'laya' ? ((cfg.jev && cfg.jev.endpoint) || '') : JEV_PINNED_ENDPOINT)}" ${(cfg.jev && cfg.jev.provider) === 'laya' ? '' : 'disabled'} title="${(cfg.jev && cfg.jev.provider) === 'laya' ? '' : t('settings.jevEndpointFixed')}" spellcheck="false"></span>
          </div>
          <div class="cfg-row">
            <span class="cfg-row-label">${t('settings.jevKeyRef')}</span>
            <span class="cfg-row-main">
              <span class="cfg-password-wrap">
                <input class="cfg-input" type="text" id="cfg-jev-keyref" placeholder="typesafe-api-key" value="${escapeHtml((cfg.jev && cfg.jev.keyRef) || '')}" autocomplete="off" style="-webkit-text-security:disc" spellcheck="false">
                <button type="button" class="cfg-eye-btn" id="jev-keyref-eye" tabindex="-1" aria-label="${t('settings.jevKeyRefShow')}" aria-pressed="false" title="${t('settings.jevKeyRefShow')}">${eyeSvg}</button>
              </span>
            </span>
          </div>
          <div class="cfg-hint">${t('settings.jevHint')}</div>
          <div class="card-actions">
            <span class="inline-state" id="jev-save-state" role="status" aria-live="polite"></span>
            <span class="spacer"></span>
            <button class="cfg-btn cfg-btn-primary" id="btn-save-jev">${t('settings.save')}</button>
          </div>
          <div class="cfg-card-divider"></div>
          <div class="cfg-row jev-health-row">
            <span class="cfg-row-label">${t('settings.jevHealth')}</span>
            <span class="cfg-row-main"><span class="st-badge" data-tone="mut" id="settings-jev-health">…</span></span>
          </div>
        </div>
      </div>
    </div>
    <div class="settings-section">
      <!-- Secrets entry (secrets-frontend batch, 2026-09-27, author ruling
           2026-09-27 19:20): one row only — the list itself lives in the
           standalone dedicated dialog (#secrets-overlay, owned by
           js/secretsPanel.js), not inside #settings-modal. Placement per
           brief §三: after MODEL PRESETS, before ADVANCED.
           secrets-ui-redesign 2026-10-08: the dashed full-width cfg-btn-add
           becomes a glass row-card entry (key icon + row name + "{n} secrets"
           subtitle + count pill + chevron). The count is the length of
           GET /api/secrets — metadata only (the contract has no read-value
           face); read via refreshSecretsEntryCount() below (module cache +
           the 'secrets-changed' nudge from secretsPanel.js). -->
      <div class="settings-section-title">${t('settings.secrets')}</div>
      <button type="button" class="secrets-entry" id="btn-open-secrets">
        ${statusIcon('key-round', 16)}
        <span class="entry-name">${t('settings.secretsManage')}</span>
        <span class="entry-sub" id="secrets-entry-sub">…</span>
        <span class="count-pill" id="secrets-entry-pill">…</span>
        ${statusIcon('chevron-right', 14)}
      </button>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.advanced')}</div>
      <button class="cfg-btn" id="btn-toggle-json">${t('settings.editRawJson')}</button>
      ${SHOW_RERUN_ONBOARDING ? `<button class="cfg-btn" id="btn-rerun-onboarding" style="margin-left:8px">${t('settings.rerunOnboarding')}</button>` : ''}
      <!-- 文件缓存行（M2 · 设计 v1 §6.4/§7 表5）：占用读数（nfCacheStats 同步 seam，
           分桶明细进 title）+ 清空键（nfCacheReset seam，两段式确认 = 按钮第二击
           即确认位，同 btn-restart-gateway 先例——共用 #delete-box 弹窗其确认键
           文案是「删除」，复用会把本键错标成删除）。 -->
      <div class="settings-row" id="nf-cache-row">
        <span class="settings-label">${t('settings.fileCache')}</span>
        <span style="display:flex;align-items:center;gap:8px">
          <span id="nf-cache-usage" role="status" style="font-size:12px;color:var(--color-text-secondary)">…</span>
          <button class="cfg-btn cfg-btn-sm" id="btn-clear-nf-cache" type="button">${t('settings.fileCacheClear')}</button>
        </span>
      </div>
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
        <div>${brand.productName} ${state.serverVersion || '...'}</div>
        <div style="margin-top:4px;font-size:12px;color:var(--color-text-secondary)">${t('settings.connection')}: ${state.connected
          ? statusIcon('circle-check', 14, 'st-ok') + ' <span style="color:var(--status-word)">' + t('settings.connected') + '</span>'
          : statusIcon('wifi-off', 14, 'st-err') + ' <span style="color:var(--status-word)">' + t('settings.disconnected') + '</span>'}</div>
        <div style="margin-top:10px;display:flex;align-items:center;gap:8px">
          <button class="cfg-btn cfg-btn-sm" id="btn-restart-gateway">${t('settings.restart')}</button>
          <span id="restart-status" role="status" style="font-size:12px;line-height:18px;min-height:18px;flex:1;min-width:0;color:var(--color-text-muted)"></span>
        </div>
        <!-- 更新功能已随 auth-trim 批（2026-10-20 作者令）退役：检查更新/立即更新/
             进度面/绿点整链移除，未来由官网统一检测更新取代。重启（restartStatus/
             restartResult 帧）状态行 = 上方 #restart-status，消费订阅见模块级。 -->
      </div>
    </div>`;

  renderSettingsAvatar();
  bindSettingsEvents(content, cfg);
  bindNeblinkEvents(() => renderSettings());
  // Secrets entry count pill (secrets-ui-redesign, 2026-10-08): read/cached
  // module-side, repainted on every render and on `secrets-changed`.
  refreshSecretsEntryCount();

  // friendseal batch (2026-09-25 ①-A): the settings-page device expansion
  // area (chat entry per device). Lazy import — the P2-4 cycle-cut precedent:
  // the builder lives in messages.js (the device chat's owner face, which
  // statically imports neblink.js), so a static import here would create a
  // new cycle. Idempotent mount; the refresh tick below re-mounts it.
  const devicesSlot = document.getElementById('settings-devices-slot');
  if (devicesSlot) {
    import('./messages.js').then((m) => m.mountSettingsDevices(devicesSlot)).catch(() => {});
  }

  // device-provenance 批（2026-10-23）：「消息设备标记」区（命名 + 绑定到设备
  // 列表）——懒挂载（叶子模块 deviceIdentity.js，roster 读数在其内异步拉）。
  const stampSlot = document.getElementById('device-stamp-section');
  if (stampSlot) {
    import('./deviceIdentity.js').then((m) => m.mountDeviceStampSettings(stampSlot)).catch(() => {});
  }

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
      // friendseal (2026-09-25 ①-A): keep the device expansion area in
      // lockstep with peer/presence/login changes on the same tick.
      const devicesSlot = document.getElementById('settings-devices-slot');
      if (devicesSlot) {
        import('./messages.js').then((m) => m.mountSettingsDevices(devicesSlot)).catch(() => {});
      }
    });
  };
  refreshNeblink();
  // O10 解绑（设备会话统一批 MVP-1）：修前把 3s 轮询**挂在 renderSettings 内**且从不清除
  // ⇒ 设置面板一开过，轮询就永久驻留（面板不在场也每 3s 打一次 /api/neblink/status）。
  // 现在 tick 内先判「设置内容是否仍在可见布局里」，不可见即停表并自注销。
  // 在线态刷新语义不变（面板在场时仍 3s 一拍）；联系人面板设备段的 online 面
  // **不依赖本表**，只依赖 WS `peerListChanged` 推送（neblink.js notifyStatusSubscribers）。
  if (window._neblinkRefreshTimer) clearInterval(window._neblinkRefreshTimer);
  window._neblinkRefreshTimer = setInterval(() => {
    const content = document.getElementById('settings-content');
    if (!content || content.offsetParent === null) {
      clearInterval(window._neblinkRefreshTimer);
      window._neblinkRefreshTimer = null;
      return;
    }
    refreshNeblink();
  }, 3000);
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
        <span class="cfg-card-badge">${escapeHtml(protocolFormLabel(p.protocol))}</span>
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
// reimplemented here either: activityBar.js's activateAccountEntry owns it for
// both entries (login when logged out, profile page when logged in → hence the
// landing URL, including the `from=client` hint, comes from the single source
// brand.js getProfileUrl()).
function renderSettingsAvatar() {
  const entry = document.getElementById('settings-avatar-entry');
  if (!entry) return;
  const { url: validAvatarUrl, showPhoto } = avatarViewState();
  const logoEl = entry.querySelector('.settings-avatar-logo');
  const photoEl = entry.querySelector('.settings-avatar-photo');
  if (photoEl) {
    // Same shared, readiness-gated slot paint as the Activity Bar avatar
    // (neblink.js paintAvatarSlot, 2026-09-15 flicker fix): the logo holds the
    // slot until the photo is paintable, so a fresh mount can never paint an
    // empty ring while the avatar bytes are still loading.
    paintAvatarSlot(photoEl, logoEl, validAvatarUrl, showPhoto, () => {
      noteAvatarFailure(validAvatarUrl); // shared failed-URL latch
      const view = avatarViewState();
      if (photoEl) photoEl.hidden = true;
      if (logoEl) logoEl.hidden = view.showPhoto;
    });
  } else if (logoEl) {
    logoEl.hidden = showPhoto;
  }
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


// ── Secrets entry count (secrets-ui-redesign, 2026-10-08) ───────────────────
// The entry row carries an "{n} secrets" subtitle + a count pill. The count is
// the LIST LENGTH of GET /api/secrets — metadata only (the contract has no
// read-value face; secretsPanel.js's discipline header applies verbatim).
// Cached for 60s so settings re-renders don't re-fetch; secretsPanel.js
// dispatches a `secrets-changed` event after every successful list read,
// which busts the cache and re-paints immediately (add/delete reflect at once).
/** @type {{n: number|null, at: number}} */
let secretsCountCache = { n: null, at: 0 };

/** Paint a known count onto the entry row (0 = the "no secrets yet" wording). */
function paintSecretsEntryCount(n) {
  const pill = document.getElementById('secrets-entry-pill');
  const sub = document.getElementById('secrets-entry-sub');
  if (!pill || !sub) return;
  pill.textContent = String(n);
  sub.textContent = n > 0 ? t('settings.secretsCount', { n }) : t('settings.secretsNone');
}

/** Read the count (cached ≤60s) and paint it; a failure keeps the "…"
 *  placeholder — the same silent-catch convention as the health probe below. */
function refreshSecretsEntryCount() {
  const pill = document.getElementById('secrets-entry-pill');
  const sub = document.getElementById('secrets-entry-sub');
  if (!pill || !sub) return;
  if (secretsCountCache.n !== null && Date.now() - secretsCountCache.at < 60000) {
    paintSecretsEntryCount(secretsCountCache.n);
    return;
  }
  fetch('/api/secrets', { headers: authHeaders() })
    .then(r => (r.ok ? r.json() : null))
    .then(data => {
      const rows = Array.isArray(data && data.secrets) ? data.secrets : [];
      secretsCountCache = { n: rows.length, at: Date.now() };
      if (!pill.isConnected || !sub.isConnected) return;
      paintSecretsEntryCount(rows.length);
    })
    .catch(() => {});
}
if (typeof window !== 'undefined') {
  window.addEventListener('secrets-changed', () => { secretsCountCache.at = 0; refreshSecretsEntryCount(); });
}

// ── 文件缓存设置行（M2 · 设计 v1 §6.4/§7 表5）：占用读数 + 清空入口 ─────────
// 读数/清空 seam 单源 = nfFileCache.js（nfCacheStats/nfCacheReset）；模块头注已声明
// 依赖纪律（nfFileCache 只 import state/branding 叶子，本边不成环）。
// MB 读数格式与 explorer.js formatMb 同式（本地小助手，禁为四行跨模块新增公共面；
// 一处刻意偏差：explorer 版对 0 字节下限取 1 KB，本行读数要如实出「0 KB」）。
function nfCacheFormatMb(n) {
  const v = Number(n) || 0;
  if (v >= 1024 * 1024 * 1024) return (v / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(1) + ' MB';
  return Math.max(0, Math.round(v / 1024)) + ' KB';
}

/** 占用读数入画：`N 条 · 大小`；title = 分桶明细（local/device/server，占/预算）。 */
function paintNfCacheUsage(el) {
  const s = nfCacheStats();
  el.textContent = `${s.entries} ${t('settings.fileCacheEntries')} · ${nfCacheFormatMb(s.bytes)}`;
  el.title = `local ${nfCacheFormatMb(s.buckets.local)}/${nfCacheFormatMb(s.bucketBudgets.local)}` +
    ` · device ${nfCacheFormatMb(s.buckets.device)}/${nfCacheFormatMb(s.bucketBudgets.device)}` +
    ` · server ${nfCacheFormatMb(s.buckets.server)}/${nfCacheFormatMb(s.bucketBudgets.server)}`;
}

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

  // 权限模式「全局」下拉的 change 绑定已随控件一并删除（permshield F1，2026-09-13
  // 作者重裁候选 B ①）：档位写入的唯一 UI 入口 = 顶栏盾牌（main.js
  // `initSafetyToggle` → WS `setSafetyMode` → 后端 `persistGlobalSafetyMode` 落盘
  // `nebflow.json` 的 `safety.defaultMode`），此处不再有第二个写入口。

  // LLM Log toggle — shared nb-toggle component; setToggleState keeps the
  // class and aria-checked in lockstep.
  document.getElementById('toggle-llm-log')?.addEventListener('click', function() {
    const enabled = !this.classList.contains('on');
    setToggleState(this, enabled);
    state.llmLogEnabled = enabled;
    sendWs({type: 'setLlmLog', enabled});
  });

  // ── Executors section retired (ui-train-threefix A, 2026-10-07): the
  // settings 默认执行器 row, the panel-entry button and the /agents command
  // left with their locale keys; ExecutorRegistry/detect (scala) untouched.

  // ── Jev section: explicit save (whitelist keys only; PUT /api/config/jev).
  // keyRef is a NAME under ~/.nebflow/secrets/ — a pointer, never a value.
  // 三改①（2026-10-09 用户反馈①）：开关只作用法闸——不再联动展开/收起细节
  // （旧「开=展开」耦合退役）。展开/收起唯一入口 = 卡头行体 #jev-head（真实
  // button：点击 + Enter/Space 原位开合；开关是行内兄弟钮，点击永不冒泡进
  // 热区）。折叠体常驻 DOM：collapse ≠ clear（输入值跨开合存活）。
  document.getElementById('toggle-jev')?.addEventListener('click', function() {
    setToggleState(this, !this.classList.contains('on'));
  });

  // 三改①：卡头行体点击 → 原位展开/收起（aria-expanded 与 chevron 旋转随态）。
  document.getElementById('jev-head')?.addEventListener('click', function() {
    const body = document.getElementById('jev-detail-body');
    if (!body) return;
    const open = body.style.display !== 'none';
    body.style.display = open ? 'none' : 'block';
    this.setAttribute('aria-expanded', String(!open));
  });

  // 三改②（2026-10-09 用户反馈②）：提供方切换联动端点钉死——typesafe-jev =
  // 预填固定值 + disabled 禁用态（用户只填密钥引用）；其他提供方恢复可编辑，
  // 钉死值/空值让位（后端 JevSettings.resolve 对空端点自有默认，不发错值）。
  document.getElementById('cfg-jev-provider')?.addEventListener('change', function() {
    const input = /** @type {HTMLInputElement | null} */ (document.getElementById('cfg-jev-endpoint'));
    if (!input) return;
    const provider = /** @type {HTMLSelectElement} */ (this).value;
    if (provider === 'typesafe-jev') {
      input.value = JEV_PINNED_ENDPOINT;
      input.disabled = true;
      input.title = t('settings.jevEndpointFixed');
      input.placeholder = JEV_PINNED_ENDPOINT;
    } else {
      input.disabled = false;
      input.title = '';
      input.placeholder = 'http://127.0.0.1:8080/predict';
      if (input.value === JEV_PINNED_ENDPOINT || !input.value.trim()) input.value = '';
    }
  });

  // keyRef masking (secrets-ui-redesign, 2026-10-08): the field starts masked
  // (-webkit-text-security:disc inline, provider-modal precedent at :1854) and
  // the eye reveals it. Same inline-style flip as the precedent at :1893-1900;
  // aria-label/aria-pressed track the state (secretsPanel applyMask reading:
  // the label names the ACTION the click performs).
  const jevEye = document.getElementById('jev-keyref-eye');
  if (jevEye) {
    jevEye.addEventListener('click', () => {
      const input = /** @type {HTMLInputElement} */ (document.getElementById('cfg-jev-keyref'));
      if (!input) return;
      // -webkit-text-security 是非标属性：lib.dom 的 CSSStyleDeclaration 不带它，
      // 经 any 视图读写（providers-modal 先例同型）。
      const style = /** @type {any} */ (input.style);
      const isMasked = style.webkitTextSecurity !== 'none';
      style.webkitTextSecurity = isMasked ? 'none' : 'disc';
      jevEye.innerHTML = isMasked ? eyeOffSvg : eyeSvg;
      const label = t(isMasked ? 'settings.jevKeyRefHide' : 'settings.jevKeyRefShow');
      jevEye.setAttribute('aria-label', label);
      jevEye.title = label;
      jevEye.setAttribute('aria-pressed', isMasked ? 'true' : 'false');
    });
  }

  document.getElementById('btn-save-jev')?.addEventListener('click', async function() {
    const enabled = document.getElementById('toggle-jev')?.classList.contains('on') || false;
    const provider = document.getElementById('cfg-jev-provider')?.value || 'typesafe-jev';
    const endpoint = document.getElementById('cfg-jev-endpoint')?.value.trim() || '';
    const keyRef = document.getElementById('cfg-jev-keyref')?.value.trim() || '';
    const body = { enabled, provider };
    if (endpoint) body.endpoint = endpoint;
    if (keyRef) body.keyRef = keyRef;
    let ok = false;
    try {
      const resp = await fetch('/api/config/jev', {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      ok = resp.ok;
    } catch (e) { ok = false; }
    // Inline feedback (secrets-ui-redesign, 2026-10-08): the button text no
    // longer swaps — a role=status line beside the right-aligned primary
    // button flashes the outcome for 1.5s (the old swap's duration
    // convention; the copy reuses settings.saved / settings.saveFailed).
    const stateEl = document.getElementById('jev-save-state');
    if (stateEl) {
      stateEl.innerHTML = ok
        ? statusIcon('circle-check', 11, 'st-ok') + escapeHtml(t('settings.saved'))
        : statusIcon('circle-alert', 11, 'st-err') + escapeHtml(t('settings.saveFailed'));
      setTimeout(() => { if (stateEl.isConnected) stateEl.innerHTML = ''; }, 1500);
    }
  });

  // ── Jev section: allocation-decision health (jev-plugins batch, 2026-10-07;
  // secrets-ui-redesign 2026-10-08: the bare word becomes a tri-state
  // .st-badge — activity(st-ok)=up · octagon-alert(st-err)=down + reason
  // (truncated inside the pill, full text on title) · circle-dashed(muted)=not
  // configured. Same reading as secrets-mode-badge: the ICON carries the
  // colour, the word stays neutral (--status-word). A fetch failure keeps the
  // muted "…" placeholder — the unchanged silent-catch convention.)
  // Read-only probe = GET /api/decision/health (DecisionHealthMonitor; the
  // write legs are the plugin-allocation calls themselves).
  // 三改③（2026-10-09 用户反馈③）：legs 分腿载荷——jev 腿 down 而通道 up
  // （llm 接管产出）→ warn 徽标「Jev 不可用 → 已回退 llm」+ jev 腿 reason
  // 尾巴（title 全文）；两腿全挂 → err「异常」+ title 双腿 reason。无 legs
  // 的旧载荷走既有三态，零回归。零新色值：warn 档用既有 --status-warn-icon
  // token（base.css 既有，暗亮两态随 token）。
  {
    const slot = document.getElementById('settings-jev-health');
    if (slot) {
      fetch('/api/decision/health', { headers: authHeaders() })
        .then(r => (r.ok ? r.json() : null))
        .then(data => {
          const d = data && data.decision;
          if (!d || !slot.isConnected) return;
          const up = d.status === 'up';
          const down = d.status === 'down';
          const legs = d.legs || null;
          const jevLeg = legs && legs.jev;
          // 回退态：通道有产出（up）但 jev 腿 down —— llm 兜底腿接管。
          const fellBack = !!(up && jevLeg && jevLeg.status === 'down');
          const icon = fellBack ? 'triangle-alert'
            : up ? 'activity' : down ? 'octagon-alert' : 'circle-dashed';
          const cls = fellBack ? 'st-warn' : up ? 'st-ok' : down ? 'st-err' : '';
          const tone = fellBack ? 'warn' : up ? 'ok' : down ? 'err' : 'mut';
          const word = fellBack ? t('settings.jevHealthFallback')
            : up ? t('settings.jevHealthUp')
            : down ? t('settings.jevHealthDown')
            : t('settings.jevHealthUnconfigured');
          const jevReason = jevLeg && jevLeg.status === 'down' && jevLeg.reason ? String(jevLeg.reason) : '';
          const reason = fellBack ? jevReason : (down && d.reason ? String(d.reason) : '');
          // title 全文：双腿如实并陈（回退后 llm 腿再挂 = jev + llm 两条都在）。
          const llmReason = down && d.reason ? String(d.reason) : '';
          const fullTitle = [jevReason, llmReason].filter(Boolean).join(' | ') || word;
          slot.dataset.tone = tone;
          slot.title = fullTitle;
          slot.innerHTML = statusIcon(icon, 10, cls) + escapeHtml(word)
            + (reason ? `<span class="badge-reason">— ${escapeHtml(reason)}</span>` : '');
        })
        .catch(() => {});
    }
  }

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
        // Provider removal only rewrites the providers map: model chains are
        // per-agent (agent.json `model` key, /model panel) and the engine
        // filters chain refs of removed providers at resolution time.
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
  // The click handler is NOT bound here: activityBar.js owns the account entry
  // (activateAccountEntry) and binds this element by document-level delegation,
  // which keeps the login popup reservation inside the user's own gesture.
  // The previous forwarding — synthesizing a click on #activity-avatar — carried
  // no transient user activation, so a real browser popup-blocked the login tab
  // and one click degraded into two (2026-09-15 session-handoff 案 3 ①).
  // Do not re-add a handler here: it would fire alongside the delegated one.
  // (The avatar's own rendering below stays in this module — it is view state,
  // not the entry.)

  // 「检查更新 / 立即更新 / 稍后」绑定已随 auth-trim 批（2026-10-20 作者令）删除：
  // 设置页更新功能退役（入口 + checkUpdate/doUpdate 发送 + updateCheck.js 消费链），
  // 未来由官网统一检测更新取代。

  // --- Restart the gateway (hotupdate 批 3 · G6 后半：界面重启触发补齐) ---
  // 🔴 沿用**既有** WS 命令 `restart`（`WebSocketRoutes.scala:1585-1619` 的 `case "restart"`）,
  //    禁新造重启命令、禁新造第二套重启面。
  // 🔴 载荷只带 `confirm: true`：**不传** `waitTimeoutMs`/`waitIdle` ⇒ 引擎侧沿用既有默认
  //    （等待上限 600 秒、等待空闲模式），本批对该默认值零改动。
  // 确认形态 = **两段式**（第二击即确认位）：强制确认位语义要求「用户的确认动作」，
  // 而共用的 `#delete-box` 确认弹窗其确认键文案是「删除」（`modal.deleteConfirm`），
  // 复用它会把中文/英文界面都写成错标签 ⇒ 按钮自身两段式，5 秒无第二击自动复位。
  const restartBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-restart-gateway'));
  if (restartBtn) {
    let armed = false;
    let armTimer = null;
    const disarm = () => {
      armed = false;
      if (armTimer) { clearTimeout(armTimer); armTimer = null; }
      restartBtn.textContent = t('settings.restart');
    };
    restartBtn.addEventListener('click', () => {
      if (!armed) {
        armed = true;
        restartBtn.textContent = t('settings.restartConfirm');
        armTimer = setTimeout(disarm, 5000);
        return;
      }
      disarm();
      sendWs({ type: 'restart', confirm: true });
    });
  }

  // --- 文件缓存：占用读数入画 + 清空入口（M2 · 设计 v1 §6.4/§7 表5） ---
  // 清空 = nfCacheReset()（索引 + Cache Storage 字节 + 会话内存 blob 全清；已铸的
  // blob URL 不受影响——URL 与字节源解耦，活动 viewer 继续渲染，下轮打开重新取数
  // 入账）。确认形态 = 两段式（按钮第二击即确认位，5 秒无第二击自动复位），同上方
  // btn-restart-gateway 先例：共用 #delete-box 弹窗其确认键文案是「删除」，复用会
  // 把「清空缓存」错标成删除。清空后读数即现「0 条 · 0 KB」= 清空反馈（诚实读数）。
  const cacheUsageEl = /** @type {HTMLElement|null} */ (document.getElementById('nf-cache-usage'));
  if (cacheUsageEl) paintNfCacheUsage(cacheUsageEl);
  const clearCacheBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-clear-nf-cache'));
  if (clearCacheBtn && cacheUsageEl) {
    let cacheArmed = false;
    let cacheArmTimer = null;
    const cacheDisarm = () => {
      cacheArmed = false;
      if (cacheArmTimer) { clearTimeout(cacheArmTimer); cacheArmTimer = null; }
      clearCacheBtn.textContent = t('settings.fileCacheClear');
    };
    clearCacheBtn.addEventListener('click', async () => {
      if (!cacheArmed) {
        cacheArmed = true;
        clearCacheBtn.textContent = t('settings.fileCacheClearConfirm');
        cacheArmTimer = setTimeout(cacheDisarm, 5000);
        return;
      }
      cacheDisarm();
      clearCacheBtn.disabled = true;
      try { await nfCacheReset(); } finally { clearCacheBtn.disabled = false; }
      paintNfCacheUsage(cacheUsageEl);
    });
  }
}

// ── 重启进度面（auth-trim 批迁址）────────────────────────────────────────────
// 原 updateCheck.js 的 restartStatus/restartResult 订阅（hotupdate 批 3 · G6）随该文件
// 整体退役；「重启服务」按钮（btn-restart-gateway，本文件 bindSettingsEvents 内）
// 仍需可见反馈 ⇒ 订阅迁到本模块**模块级**（import 时恰好一次，settings 面板重渲不重复订阅）。
// 消费元素 = About 区的 #restart-status；面板关闭时元素不存在 ⇒ 静默（既有口径）。
// 相位文案仍由 `restart.phase.*` locale 表解析（🔴 不写字面）。
onMessage('restartStatus', (msg) => {
  const el = document.getElementById('restart-status');
  if (!el) return;
  const key = msg && msg.phase ? 'restart.phase.' + msg.phase : '';
  el.textContent = key ? t(key, { detail: (msg && msg.detail) || '' }) : '';
});

onMessage('restartResult', (msg) => {
  const el = document.getElementById('restart-status');
  if (!el) return;
  el.textContent = (msg && msg.ok)
    ? t('settings.restartAccepted')
    : t('settings.restartFailed', { error: (msg && msg.error) || '' });
});

// Delegated segment-editor listeners — bound ONCE at module scope, NOT inside
// bindSettingsEvents. renderSettings() re-runs on every serverConfig echo while
// the panel is open; #settings-content is a persistent container, so listeners
// bound to it inside bindSettingsEvents would accumulate and fire N× per event
// (duplicate setWorkSchedule saves). Delegating from document keeps exactly one
// listener for the whole app lifetime.
document.addEventListener('click', (e) => {
  const settings = document.getElementById('settings-content');
  if (!settings || !(e.target instanceof Element) || !settings.contains(e.target)) return;
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
  if (!settings || !(e.target instanceof Element) || !settings.contains(e.target)) return;
  if (!e.target.classList.contains('time-input')) return;
  updateSegmentRowHint(e.target.closest('.segment-row'));
  markScheduleDirty();   // #334: local draft only — Save commits
});

// Listen for config validation errors
onMessage('error', (data) => {
  if (data.message && typeof data.message === 'string' && data.message.includes('Provider')) {
    // Show validation errors from backend.
    // ui-status-redesign（分叉④=A，S7 全站归一）：唯一违规件「实心红底白字」
    // 并入 nebflow-toast 玻璃规范——玻璃卡 + 行首 circle-x 红图标 + 中性正文。
    const toast = document.createElement('div');
    toast.className = 'cfg-toast cfg-toast-error';
    toast.innerHTML = statusIcon('circle-x', 15, 'st-err') + '<span class="cfg-toast-msg">' + escapeHtml(data.message) + '</span>';
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 5000);
  }
});

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
      // Root agent reads the SINGLE display-name read point (rootName.js): both
      // the in-session onboarding rename override (state.rootDisplayName) and the
      // persisted `displayName` live there. The old agentsData-only read was the
      // 「起名不影响 Header」 gap — it ignored the override and fell back to the
      // bare 'Nebula' literal whenever the agentsData row had not arrived.
      // N1 (unnamed-state batch 2026-10-06): UNNAMED root → the title area is an
      // EMPTY string — the mechanism name is a key, not a display fallback, and
      // the empty <span> keeps the header's center-slot layout intact (no jump).
      // Named root → display name as before; non-root agents keep their own name.
      const label = agentName === 'Nebula'
        ? (isRootNamed() ? rootDisplayName() : '')
        : (state.agentsData.find(a => a.name === agentName)?.displayName || agentName);
      sessionNameEl.textContent = label;
      sessionNameEl.style.display = '';
    }
  } else {
    if (sessionNameEl) { sessionNameEl.textContent = ''; sessionNameEl.style.display = 'none'; }
  }
}

// Onboarding rename → refresh the header center in place (author 2026-10-05:
// 「起名是会影响Header的标题的」). A rename does not change the session-list
// fingerprint, so no render path would otherwise fire; onboarding.js dispatches
// `nebflow-root-name-change` (event, not import — keeps the module graph acyclic).
window.addEventListener('nebflow-root-name-change', () => { updateHeaderSessionName(); });

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
      header.textContent = agentDisplayLabel(agentName);
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
// Priority: attention > busy > marked unread > unread > idle
// Only one indicator visible at a time, controlled by a single CSS class.
// Attention takes priority so AskUser/permission prompts are visible.
//
// 压缩中无独立可见态（作者令 2026-10-09 ①「『压缩中』可见指示列为拆除项」，
// compaction-pill-remove 批）：compactingSessionIds 仅剩功能职责（输入闸 /
// 排队闸），侧栏会话/文件夹行不再显示压缩指示（压缩 spinner 元素与样式已拆）。

function getSessionStatusClass(sessionId) {
  if (state.attentionSessions.has(sessionId)) return 'attention';
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

// 头部用量环刷新入口已随 2026-10-23 上下文用量显示退役批删除
//（作者令逐字「把桌面端的上下文用量显示也去掉」）。
// 防复活钉：tests/remove-context-usage.spec.mjs。


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
  // 压缩中指示已拆除（作者令 2026-10-09 ①）——文件夹行不再有 compacting 态。
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
    // ui-status-redesign（S10 / 盘点 #47）：常显危险文字项字转常规色，危险语义由
    // 行首 trash-2 红图标承载；hover 时刻字转红（ctx.css :hover 交互红保留）。
    '<div class="ctx-item" data-action="delete">' + statusIcon('trash-2', 12, 'st-err') + '<span>' + t('ctx.deleteFolder') + '</span></div>';
  menu.innerHTML = html;
  createIconsIn(menu);

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

  // Set project root — opens the unified directory picker (workspacePicker.js).
  // Clear commits projectRoot:null (button shown only when a root is set);
  // both commit pipes dispatch 'project-root-changed' exactly like the retired
  // in-module path picker did.
  menu.querySelector('[data-action="set-project-root"]').addEventListener('click', () => {
    if (!folder) return;
    import('./workspacePicker.js').then(({ openPicker }) => {
      openPicker({
        startPath: folder.projectRoot || '~',
        title: t('workspacePicker.projectTitle'),
        showClear: !!folder.projectRoot,
        onPick: (path) => {
          sendWs({ type: 'setFolderProjectRoot', folderId: folder.id, projectRoot: path });
          window.dispatchEvent(new CustomEvent('project-root-changed'));
        },
        onClear: () => {
          sendWs({ type: 'setFolderProjectRoot', folderId: folder.id, projectRoot: null });
          window.dispatchEvent(new CustomEvent('project-root-changed'));
        },
      });
    });
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

export function moveSessionToFolder(sessionId, folderId) {
  sendWs({ type: 'moveSessionToFolder', sessionId, folderId });
}

