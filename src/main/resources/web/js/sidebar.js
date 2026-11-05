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
import { finishAgent, setStatus, renderToolPending, cancelThinkingRAF, bindCollapsibleToggle } from './chat.js';
import { restoreFromStorage, loadMsgs } from './persistence.js';
import { renderTodoTaskList } from './todoFace.js';
import { clearMemoryCache } from './memory.js';
// eyeSvg/eyeOffSvg（provider 密码域可见性切换图标）单源 = sidebar/providersUi.js（FE 拆分后
// 迁入；jev-imagegen-align-llm 批 2026-11-02 起 Jev 面唯一掩码输入框 #cfg-jev-key 的
// eye 也从本模块取，禁第二份字面量）。
import { eyeSvg, eyeOffSvg } from './sidebar/providersUi.js';
// JeV「测试可用性」钮（settings-panel-jev-imagegen 批 2026-10-29 作者令「然后
// Jev/生图，也要像我们LLM这样，有个按钮来测试可用性」）：钮 + 结果块 + 绑定面
// 全在 sidebar/jevTestButton.js（与 LLM 测试钮同组件同口径）。
import { jevTestButtonHTML, jevTestResultsHTML, bindJevTestButton } from './sidebar/jevTestButton.js';
import { chatViews, setActiveView, activeView } from './chatView.js';
import { cleanupCardIframes, resetCardAccumulator } from './cardRegistry.js';
import { t, getLocale, setLocale, getAvailableLocales } from './i18n.js';
import { fetchNeblinkStatus, neblinkSettingsHTML, bindNeblinkEvents } from './neblink.js';
// 件四 · 设置页身份卡本地化（作者令 2026-11-02）：头像在上 + 用户名/邮箱单行胶囊，
// 点胶囊 = App 内个人资料面（不再跳官网）。卡片结构/绘制/资料面全在该叶子模块，
// 本文件只调两个入口（settingsIdentityCardHTML 出结构，paintSettingsIdentity 出读数）。
import { settingsIdentityCardHTML, paintSettingsIdentity } from './settingsIdentity.js';
// updateCheck.js（更新检查/更新执行链）已随 auth-trim 批（2026-10-20 作者令）整体删除：
// 设置页「更新」功能退役，未来由官网统一检测更新取代。重启面（restartStatus/
// restartResult 帧消费）留在本文件（见 bindSettingsEvents 后的模块级订阅）。
import { toggleHTML, setToggleState } from './toggle.js';
// 时间滚轮选择器（time-wheel-picker 实施批 2026-11-03，作者令 22:16/22:20）：
// 冻结时段 = 原子段字段（整段一颗触发钮），交互单源 = js/timeWheel.js。
import { HHMM_RE, toMin, rangeTriggerHTML, paintRangeTrigger, attachTimeTriggers } from './timeWheel.js';
import { authHeaders } from './flowHelpers.js';
import { preloadModelCapabilities, renderVisionBadge } from './modelCapabilities.js';
import { renderAppearanceSection, bindAppearanceEvents } from './orbSettingsUI.js';
// 骨架卡（audit ②27，2026-10-25）：初次进会话（历史未加载完）的 loading 占位泡。
import { showHistoryLoader } from './main/historyIndicators.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';
import { withFocusKey, pushLayer, popLayer } from './focusTrap.js';
// Root agent's DISPLAY name — the single read point shared with onboarding /
// micOrb / chat / modelPanel (leaf module; no cycle risk).
import { rootDisplayName, isRootNamed, agentDisplayName, ROOT_AGENT_KEY } from './rootName.js';
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
import { protocolFormLabel, escapeHtml, flushConfigToServer, showProviderModal, saveNewProvider, revealProviderApiKeys } from './sidebar/providersUi.js';
// 配置提供方卡片单源（jev-imagegen-align-llm 批 · card 件，契约 §A.1/§A.4）：
// 卡片 DOM 与两个交互绑定抽到 js/cfgProviderCard.js，LLM 面 = 第一个消费方
// （下方 renderProviderCard 退化为薄壳，调用点与产出逐字不变）；Jev / 生图面
// 各自节点只读该件。本文件对它的依赖是单向的（该件不回指 sidebar.js）。
import { providerCardHTML, addProviderButtonHTML, bindCardRemove, bindCardSelect } from './cfgProviderCard.js';
// 生图设置节（settings-panel 批 ②③件）与每模型连接性测试钮（B 件）——渲染/
// 绑定单点在各自子模块，本文件只挂壳 + 转发调用（子模块不回指本文件）。
import { bindImagegenSection } from './sidebar/imagegenSection.js';
// 配置面风格统一批（2026-10-31 作者令）：JeV/生图层级下钻的桌面浮层半
// （手机层级子页半在 mobile/settingsSub.js——同一内容节点、body class 互斥）。
import { registerConfigDrill, syncConfigDrills } from './configDrill.js';
// 同批②：设置页「模型回退链」节——/model 面板的回退链编辑器原样再放一份，
// 内容面/状态/保存通道与面板同源（modelPanel.js 单点实现）。
import { mountModelChainSection } from './modelPanel.js';
// 连接性测试钮已随 settings-panel-fourfix 批 ②件（2026-10-29 作者令「连接性
// 测试按钮应放到 LLM provider 配置页面里」）迁入 provider 编辑弹窗——设置页
// provider 卡模型行不再携带测试钮；模块 = sidebar/modelTestButton.js（弹窗面
// 自行 import）。
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

// 2026-09-05 作者裁定（设置页清理批②）曾将此入口隐藏封存；2026-10-30
// 「Onboarding 跳过批」作者令（更新、更具体）要求设置里保留重新进入
// Onboarding 的入口 —— 跳过用户唯一的回归面，故翻回 true 原样回归。
// 与 SIDEBAR_LEGACY_ENTRIES 同款 flag 形态；按钮/绑定与 i18n 键
// settings.rerunOnboarding 从未删除（隐藏 ≠ 删除）。onboarding 本体与
// /onboarding slash 命令零触碰。
const SHOW_RERUN_ONBOARDING = true;

// ---------- Active Folder (VSCode-style) ----------
export function setActiveFolder(folderId) {
  state.activeFolderId = folderId;
  // Update UI highlight
  document.querySelectorAll('.folder-item').forEach(el => {
    el.classList.toggle('active', (/** @type {HTMLElement} */ (el)).dataset.folderId === folderId);
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
  const ov = document.getElementById('settings-overlay');
  ov?.classList.add('on');
  // B4：设置面板入层栈（本壳有 6 个打开入口：活动栏 / 命令面板 / 会话头 / 移动层，
  // 全部汇到本函数 ⇒ 单点接线）。Esc 仍由本模块既有 document keydown 收口。
  if (ov) pushLayer(ov, { name: 'settings' });
  sendWs({type: 'getConfig'});
  sendWs({type: 'getToolResultTtl'}); // #341: fresh TTL echo every open
  renderSettings();
}

/** Close the Settings modal and stop its periodic NebLink refresh. */
export function closeSettingsPanel() {
  const ov = document.getElementById('settings-overlay');
  if (ov) popLayer(ov); // B4：出栈 + 回焦
  ov?.classList.remove('on');
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
/** DISPLAY name of an agent by mechanism name — delegates to the SINGLE display
 *  read point (rootName.js#agentDisplayName), so the root agent renders the
 *  user's chosen name (generic appellation while unnamed — never the mechanism
 *  key) and background agents render their own name. Judgement keys
 *  (`a.name === ROOT_AGENT_KEY` below) stay untouched — display only. */
function agentDisplayLabel(agentName) {
  return agentDisplayName(agentName);
}

export function renderAgentList() {
  const list = document.getElementById('nav-agent-list');
  if (!list) return;
  list.innerHTML = '';
  // ⑧ root agent (orchestrator) pinned first with a separator before the rest
  // (entity-icons-visual-spec §3.3). It always renders the orbit icon —
  // its identity marker must stay stable, so custom avatars are ignored.
  const ordered = [...state.agentsData].sort((a, b) =>
    (b.name === ROOT_AGENT_KEY ? 1 : 0) - (a.name === ROOT_AGENT_KEY ? 1 : 0));
  ordered.forEach((a, idx) => {
    const isNebula = a.name === ROOT_AGENT_KEY;
    const el = document.createElement('div');
    const isActive = state.selectedAgent === a.name;
    el.className = 'nav-agent' + (isActive ? ' active' : '') + (isNebula ? ' nav-agent-orchestrator' : '');
    const avatar = a.avatar || '';
    // Display name = the single read point (named root → its name; unnamed root →
    // generic appellation; every other agent → its own name).
    const displayName = agentDisplayLabel(a.name);
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
    el.classList.toggle('active', (/** @type {HTMLElement} */ (el)).dataset.name === agentName);
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
    <div id="schedule-editor" style="display:${enabled ? 'block' : 'none'};padding:var(--ds-space-1) 0 var(--ds-space-2);">
      <div class="segment-list" id="segment-list">${segRows}</div>
      <button class="cfg-btn cfg-btn-add" id="btn-add-segment">${t('settings.addSegment')}</button>
      <div class="cfg-hint">${t('settings.workScheduleOnHint')}</div>
    </div>
    <!-- Dirty hint + action row live OUTSIDE #schedule-editor so they stay
         visible even when the editor is display:none — a toggled-OFF draft
         must remain committable (Save) / abandonable (Reset). The segments
         themselves stay in the DOM (renderScheduleEditorRows rebuilds from
         the draft on re-enable), so nothing is cleared on toggle-off. -->
    <div class="cfg-hint" id="schedule-dirty-hint" style="display:${scheduleDraft !== null ? 'block' : 'none'};margin-top:var(--ds-space-1h);color:var(--color-text-muted)">${t('settings.scheduleDirtyHint')}</div>
    <div style="display:${scheduleDraft !== null ? 'flex' : 'none'};gap:var(--ds-space-2);margin-top:var(--ds-space-2)" id="schedule-actions">
      <button class="cfg-btn cfg-btn-primary" id="btn-save-schedule">${t('settings.scheduleSave')}</button>
      <button class="cfg-btn" id="btn-reset-schedule">${t('settings.scheduleReset')}</button>
    </div>
    <div class="cfg-hint" id="schedule-off-hint" style="display:${enabled ? 'none' : 'block'};margin-top:-2px">${t('settings.workScheduleOffHint')}</div>`;
}

/**
 * One segment row — 原子段字段（整段「09:00-12:00」一颗触发钮，作者令 2026-11-01 16:46
 * 「8:00-12:00 应该是一个整体」）+ remove。跨零点的行内 sapphire 徽标保留
 * （start > end = 冻结窗跨入次日，黑名单语义）。交互组件单源 = js/timeWheel.js。
 */
function buildSegmentRowHtml(s, i) {
  const start = HHMM_RE.test(s.start) ? s.start : '09:00';
  const end = HHMM_RE.test(s.end) ? s.end : '12:00';
  const crossMidnight = toMin(start) > toMin(end);
  return `
    <div class="segment-row" data-seg-index="${i}">
      <span class="seg-label">${t('settings.segmentLabel', { n: i + 1 })}</span>
      ${rangeTriggerHTML({ start, end, label: t('settings.segmentEdit'), presets: 'freeze' })}
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

/** 模型回退链节上一任卸载函数（配置面风格统一批②：renderSettings 重渲前
 *  先卸上一任 mountModelChainSection 的监听，不叠订阅）。 */
let modelChainUnmount = /** @type {(() => void)|null} */ (null);

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
      <div style="display:flex;gap:var(--ds-space-2);margin-top:var(--ds-space-2)">
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
  // D 件（2026-10-28）：总开关——缺省开（缺席 = 现有行为逐字不变，存量用户
  // 零感知）；关 = STT 整体停用（入口与快捷键全收 + 引擎腿拒绝，见
  // voiceEngine.sttEnabled 门与 stt-disabled body 类）。
  const enabled = stt.sttEnabled !== false;
  const endpoint = stt.endpoint || '';
  const model = stt.model || '';
  const status = !enabled
    ? t('settings.sttDisabledStatus')
    : configured
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
      <div class="settings-row">
        <span class="settings-label">${t('settings.sttEnabled')}</span>
        ${toggleHTML({ on: enabled, id: 'toggle-stt-enabled', label: t('settings.sttEnabled') })}
      </div>
      <div class="cfg-hint">${t('settings.sttEnabledHint')}</div>
      <div class="cfg-form-group">
        <label class="cfg-label" for="stt-endpoint">${t('settings.sttEndpoint')}</label>
        <input class="cfg-input" id="stt-endpoint" type="text" value="${escapeHtml(endpoint)}" placeholder="https://api.example.com/v1/audio/transcriptions" autocomplete="off" spellcheck="false" ${enabled ? '' : 'disabled'}>
        <label class="cfg-label" for="stt-model">${t('settings.sttModel')}</label>
        <input class="cfg-input" id="stt-model" type="text" value="${escapeHtml(model)}" placeholder="${t('settings.sttModelPlaceholder')}" autocomplete="off" spellcheck="false" ${enabled ? '' : 'disabled'}>
        <label class="cfg-label" for="stt-apikey">${t('settings.sttApiKey')}
          ${configured ? `<span class="stt-key-configured">${t('settings.sttKeyConfigured')}</span>` : ''}
        </label>
        <input class="cfg-input" id="stt-apikey" type="password" value="" placeholder="${t(configured ? 'settings.sttApiKeyConfiguredPlaceholder' : 'settings.sttApiKeyPlaceholder')}" autocomplete="off" ${enabled ? '' : 'disabled'}>
        <div class="cfg-hint">${t('settings.sttHint')}</div>
        <div style="display:flex;gap:var(--ds-space-2);margin-top:var(--ds-space-2)">
          <button class="cfg-btn cfg-btn-primary" id="btn-save-stt">${t('settings.sttSave')}</button>
          <button class="cfg-btn" id="btn-clear-stt">${t('settings.sttClear')}</button>
        </div>
      </div>
    </div>`;
}

/**
 * Collect segments currently in the editor (all rows). 值域单源 = 原子段触发钮的
 * dataset（data-start/data-end）——整段一个字段，无半边输入框可取。
 */
function collectSegments() {
  const rows = document.querySelectorAll('#segment-list .segment-row');
  return Array.from(rows).map((row) => {
    const btn = /** @type {HTMLElement|null} */ (row.querySelector('[data-tw-range]'));
    return {
      start: btn ? (btn.dataset.start || '') : '',
      end: btn ? (btn.dataset.end || '') : ''
    };
  });
}

/** Rebuild the segment rows (used for the F2 default on first enable). */
function renderScheduleEditorRows(segs) {
  const list = document.getElementById('segment-list');
  if (!list) return;
  list.innerHTML = segs.map((s, i) => buildSegmentRowHtml(s, i)).join('');
}

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

/** In-place toggle of a row's cross-midnight hint（值域单源 = 原子段触发钮 dataset）。 */
function updateSegmentRowHint(row) {
  if (!row) return;
  const btn = /** @type {HTMLElement|null} */ (row.querySelector('[data-tw-range]'));
  const start = btn ? (btn.dataset.start || '') : '';
  const end = btn ? (btn.dataset.end || '') : '';
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
// ── Jev 面板全面对齐 LLM 配置面（jev-imagegen-align-llm 批 · jevpanel 件，2026-11-02
// 作者令逐字：「设置页 Jev 配置面按 LLM 配置面全面对齐——空态起步无预填、无任何
//『密钥名/内部指针』类字眼、已配置以卡片列表呈现可并存可单删、密钥直接录入走
// secrets」）。契约：reports/20261102_jev-imagegen-align-llm/contract.md §B.1（
// jev.providers 形状）/ §C（密钥面）/ §E.2（DOM 钩子表）。
//
// 本批整体退役的三处旧面（旧锚一删即不再渲染；id 清单见契约 §E.2「退役 id」行）：
//   · 写死的两选项提供方下拉 → 卡片列表 + `#cfg-jev-name` 文本名；
//   · 凭据名输入框 + 明文回读只读行（及其 `GET /api/secrets/<名>/value` 拉取
//     路径、eye 换标）→ 唯一掩码输入框 `#cfg-jev-key`；
//   · 「typesafe-jev 端点钉死 disabled」预填（钉死端点常量与提供方切换联动）→
//     `#cfg-jev-endpoint` 永不禁用、空态空、不再预填。
//
// 数据源 = GET /api/config/jev（ConfigService.jevStatus 单点投影：配置热读 + key
// 在位性；永不回传 key 值）。凭据面无感化（作者令「前端永远不出现引用类字眼与
// 解释文案」）：面板只认「API Key 值」——内部 secret 名按提供方名派生
// （`jev-<小写名>-key`，SecretsStore.NamePattern 只认小写），对用户不可见、不可
// 编辑；值只进输入框与请求体（secrets 面 0600 落盘），保存后清空 ⇒ 不进 value
// 属性 / data-* / localStorage / console / DOM 文本。
// 空态铁律：无配置 ⇒ 0 张卡片 + 表单五字段全空 + 协议下拉无预选 + 端点不预填。

/** 协议适配枚举（= 后端 DecisionProvider.AllIds；前端无后端常量导入面，此处唯一拼写点）。 */
const JEV_PROVIDER_TYPES = ['typesafe-jev', 'laya'];

/** 提供方名合法性（允许大写输入，派生 secret 名时转小写——§C.2）。 */
const JEV_NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$/;

/** GET /api/config/jev 最近一次载荷（null = 尚未拉到 ⇒ 空态面）。 */
/** @type {any} */
let lastJevStatus = null;

/** 正在编辑的提供方名（'' = 空态/新增面：表单全空）。 */
let activeJevProvider = '';

/** 两段式删除已武装的提供方名（'' = 未武装）。 */
let jevDeleteArmed = '';

/**
 * 拉取 Jev 状态投影（GET /api/config/jev）。任何失败 → null：面板保持空态，
 * 绝不造假数据（生图法面同款）。
 * @returns {Promise<any|null>}
 */
async function fetchJevStatus() {
  try {
    const resp = await fetch('/api/config/jev', { headers: authHeaders() });
    if (!resp.ok) return null;
    return await resp.json();
  } catch {
    return null;
  }
}

/**
 * 内部 secret 名派生（契约 §C.2 单点）：`jev-<小写提供方名>-key`，截到 64 字符
 * （SecretsStore.MaxLen；NamePattern 只认小写，故必须小写化）。
 * 🔴 该名**永不出渲染文本 / 属性 / 日志**——只作请求路径与配置里的 secret 名字段。
 * @param {string} name 提供方名
 * @returns {string}
 */
function jevSecretName(name) {
  return `jev-${String(name || '').toLowerCase()}-key`.slice(0, 64);
}

/**
 * 投影里的某个提供方条目（缺席 = null）。
 * @param {string} name
 * @returns {any}
 */
function jevProviderEntry(name) {
  if (!name) return null;
  const providers = /** @type {Record<string, any>} */ ((lastJevStatus && lastJevStatus.providers) || {});
  return providers[name] || null;
}

/**
 * 投影里的提供方名清单（顺序 = 配置顺序）。
 * @returns {string[]}
 */
function jevProviderNames() {
  return Object.keys(/** @type {Record<string, any>} */ ((lastJevStatus && lastJevStatus.providers) || {}));
}

/**
 * 协议适配徽标文案（卡片 .cfg-card-badge）；未知类型不出徽标（§A.2）。
 * @param {string} type
 * @returns {string}
 */
function jevTypeLabel(type) {
  if (type === 'typesafe-jev') return t('settings.jev.typeTypesafe');
  if (type === 'laya') return t('settings.jev.typeLaya');
  return '';
}

/**
 * 卡片右侧 key 状态徽标（已保存 / 未配置）——只报在位性，永不回显值。
 * @param {boolean} configured
 * @returns {string}
 */
function jevKeyBadge(configured) {
  return configured
    ? `<span class="st-badge" data-tone="ok">${escapeHtml(t('settings.cfgCard.keyConfigured'))}</span>`
    : `<span class="st-badge" data-tone="warn">${escapeHtml(t('settings.cfgCard.keyMissing'))}</span>`;
}

/**
 * 一张 Jev 提供方卡片（共用卡片组件 · 契约 §A.1/§A.2/§E.2：face='jev'，一卡 =
 * 一个提供方 + 它的主模型，DOM 与 LLM 卡片逐字同构）。
 * @param {string} name
 * @param {any} p 投影条目
 * @param {boolean} active 当前正在编辑
 * @returns {string}
 */
function jevCardHTML(name, p, active) {
  const model = String((p && p.model) || '').trim();
  return providerCardHTML({
    name,
    face: 'jev',
    protocolBadge: jevTypeLabel(String((p && p.type) || '')),
    models: model ? [model] : [],
    metaExtra: jevKeyBadge(!!(p && p.keyConfigured)),
    active,
  });
}

/**
 * 卡片列表 + 空态引导（空态 = 0 张 .cfg-card，只剩一句「尚未配置」提示）。
 * @returns {string}
 */
function jevProviderListHTML() {
  const providers = /** @type {Record<string, any>} */ ((lastJevStatus && lastJevStatus.providers) || {});
  const names = Object.keys(providers);
  const cards = names.map((n) => jevCardHTML(n, providers[n], n === activeJevProvider)).join('');
  const empty = names.length ? '' : `<div class="cfg-hint">${escapeHtml(t('settings.cfgCard.emptyHint'))}</div>`;
  return `<div id="jev-provider-list">${cards}${empty}</div>`;
}

/**
 * 编辑表单（契约 §E.2 的 id 表逐字）。空态 = 五字段全空 + 协议占位不预选 +
 * 端点不预填（空态铁律）；选中卡片 = 该提供方现值，key 框**永不带值**（只按
 * key 在位性换 placeholder）。
 * @returns {string}
 */
function jevProviderFormHTML() {
  const names = jevProviderNames();
  const active = names.includes(activeJevProvider) ? activeJevProvider : '';
  const p = active ? jevProviderEntry(active) : null;
  const type = String((p && p.type) || '');
  const keyConfigured = !!(p && p.keyConfigured);
  const keyPlaceholder = keyConfigured ? t('settings.jev.keyReentryPlaceholder') : t('settings.jev.keyPlaceholder');
  // 协议下拉：空态 = 占位项选中（不预选任何真实适配器）；既有值不在枚举内时补一项。
  const typeList = type && !JEV_PROVIDER_TYPES.includes(type) ? [...JEV_PROVIDER_TYPES, type] : JEV_PROVIDER_TYPES;
  const typeOpts = [
    `<option value="" disabled${type ? '' : ' selected'}>${escapeHtml(t('settings.imagegenTypePlaceholder'))}</option>`,
    ...typeList.map((tp) => `<option value="${escapeHtml(tp)}"${tp === type ? ' selected' : ''}>${escapeHtml(jevTypeLabel(tp) || tp)}</option>`),
  ].join('');
  const armed = !!active && jevDeleteArmed === active;
  return `
    <div class="cfg-form-group" id="jev-provider-form">
      <label class="cfg-label" for="cfg-jev-name">${t('settings.jev.name')}</label>
      <input class="cfg-input" id="cfg-jev-name" type="text" value="${escapeHtml(active)}" placeholder="${escapeHtml(t('settings.jev.namePlaceholder'))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label" for="cfg-jev-type">${t('settings.jev.type')}</label>
      <select class="cfg-input" id="cfg-jev-type">${typeOpts}</select>
      <label class="cfg-label" for="cfg-jev-endpoint">${t('settings.jevEndpoint')}</label>
      <input class="cfg-input" id="cfg-jev-endpoint" type="text" value="${escapeHtml(String((p && p.endpoint) || ''))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label" for="cfg-jev-model">${t('settings.jev.model')}</label>
      <input class="cfg-input" id="cfg-jev-model" type="text" value="${escapeHtml(String((p && p.model) || ''))}" placeholder="${escapeHtml(t('settings.jev.modelPlaceholder'))}" autocomplete="off" spellcheck="false">
      <label class="cfg-label" for="cfg-jev-key">${t('settings.jev.keyEntry')}</label>
      <div class="cfg-password-wrap">
        <input class="cfg-input" id="cfg-jev-key" type="text" value="" placeholder="${escapeHtml(keyPlaceholder)}" autocomplete="off" spellcheck="false" style="-webkit-text-security:disc">
        <button type="button" class="cfg-eye-btn" id="jev-key-eye" tabindex="-1" aria-label="${escapeHtml(t('settings.jev.keyShow'))}" aria-pressed="false" title="${escapeHtml(t('settings.jev.keyShow'))}">${eyeSvg}</button>
      </div>
      <div class="cfg-hint">${escapeHtml(t('settings.jev.detailHint'))}</div>
      <div class="cfg-card-action-row">
        <button class="cfg-btn cfg-btn-primary" id="btn-save-jev">${t('settings.save')}</button>
        ${jevTestButtonHTML()}
        <button class="cfg-btn" id="btn-delete-jev" type="button" data-armed="${armed ? '1' : '0'}"${active ? '' : ' disabled'}>${escapeHtml(armed ? t('settings.cfgCard.deleteConfirm') : t('settings.cfgCard.delete'))}</button>
        <span class="inline-state" id="jev-save-state" role="status" aria-live="polite"></span>
      </div>
    </div>
    ${jevTestResultsHTML()}`;
}

/**
 * 整节内容（`#jev-section-wrap` 的唯一绘制点）：列表 + 新增钮 + 表单 + 测试结果块。
 * @returns {string}
 */
function jevSectionHTML() {
  return `${jevProviderListHTML()}${addProviderButtonHTML({ id: 'btn-add-jev-provider', face: 'jev' })}${jevProviderFormHTML()}`;
}

/** 原地重画（选中/新增/保存/删除后都走这里，零第二份状态副本）。 */
function paintJevSection() {
  const wrap = document.getElementById('jev-section-wrap');
  if (!wrap) return;
  wrap.innerHTML = jevSectionHTML();
}

/**
 * 保存结果就地反馈（既有 inline-state 惯例：1.5s 自清，role=status 播报）。
 * @param {boolean} ok
 */
function setJevSaveState(ok) {
  const stateEl = document.getElementById('jev-save-state');
  if (!stateEl) return;
  stateEl.innerHTML = ok
    ? statusIcon('circle-check', 11, 'st-ok') + escapeHtml(t('settings.saved'))
    : statusIcon('circle-alert', 11, 'st-err') + escapeHtml(t('settings.saveFailed'));
  setTimeout(() => { if (stateEl.isConnected) stateEl.innerHTML = ''; }, 1500);
}

/**
 * 表单字段现值（trim）。@param {string} id @returns {string}
 */
function jevFieldValue(id) {
  const el = /** @type {HTMLInputElement|HTMLSelectElement|null} */ (document.getElementById(id));
  return el && typeof el.value === 'string' ? el.value.trim() : '';
}

/**
 * 写入一枚 secret（契约 §C.3，与生图 writeSecret 同口径）：POST 先建；
 * 409（同名已存在）⇒ PUT 覆写；其余非 2xx ⇒ toast 带 code。值只出现在请求体里。
 * @param {string} name 派生 secret 名（对用户不可见）
 * @param {string} value 输入的 key 值
 * @returns {Promise<boolean>}
 */
async function writeJevSecret(name, value) {
  try {
    let resp = await fetch(`/api/secrets/${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }),
    });
    if (resp.status === 409) {
      resp = await fetch(`/api/secrets/${encodeURIComponent(name)}`, {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ value }),
      });
    }
    if (!resp.ok) window.__showToast?.(t('settings.jev.keySaveFailed', { code: String(resp.status) }), 'error');
    return resp.ok;
  } catch {
    window.__showToast?.(t('settings.jev.keySaveFailed', { code: 'io' }), 'error');
    return false;
  }
}

/**
 * 保存流（契约 §C.4 / §B.1）：读五字段 → key 非空先落 secret → 整块 PUT
 * `/api/config/jev`。providers 从 GET 投影**现读合并**（绝不丢别的提供方）；
 * key 留空 = 只写配置不改凭据（keyRef 原样带走）。成功后输入框清空、面板停在
 * 刚保存的提供方。
 * @returns {Promise<void>}
 */
async function saveJevProvider() {
  const name = jevFieldValue('cfg-jev-name');
  const type = jevFieldValue('cfg-jev-type');
  const endpoint = jevFieldValue('cfg-jev-endpoint');
  const model = jevFieldValue('cfg-jev-model');
  const keyInput = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-jev-key'));
  const key = keyInput ? keyInput.value.trim() : '';
  if (!name) { window.__showToast?.(t('settings.jev.nameRequired'), 'error'); return; }
  if (!JEV_NAME_PATTERN.test(name)) { window.__showToast?.(t('settings.jev.nameInvalid'), 'error'); return; }
  if (!type) { window.__showToast?.(t('settings.jev.typeRequired'), 'error'); return; }
  if (!model) { window.__showToast?.(t('settings.jev.modelRequired'), 'error'); return; }

  // ① 凭据腿：有输入的 key 才写（留空 = 不动已存凭据）。
  if (key) {
    const written = await writeJevSecret(jevSecretName(name), key);
    if (!written) return; // 凭据没落盘 ⇒ 不动配置（避免半更新）
  }

  // ② 配置腿：其余提供方逐条原样带走（改名即旧名让位），本条落 {type,$endpoint,$model,keyRef}。
  const existing = /** @type {Record<string, any>} */ ((lastJevStatus && lastJevStatus.providers) || {});
  const prev = existing[name] || (activeJevProvider ? existing[activeJevProvider] : null) || null;
  /** @type {Record<string, Record<string, string>>} */
  const providers = {};
  for (const n of Object.keys(existing)) {
    if (n === activeJevProvider && n !== name) continue; // 改名：旧条目让位
    const e = existing[n] || {};
    /** @type {Record<string, string>} */
    const row = { type: String(e.type || '') };
    if (e.endpoint) row.endpoint = String(e.endpoint);
    if (e.model) row.model = String(e.model);
    if (e.keyRef) row.keyRef = String(e.keyRef);
    providers[n] = row;
  }
  /** @type {Record<string, string>} */
  const row = { type };
  if (endpoint) row.endpoint = endpoint;
  row.model = model;
  const keyRef = key ? jevSecretName(name) : String((prev && prev.keyRef) || '');
  if (keyRef) row.keyRef = keyRef;
  providers[name] = row;

  const enabled = document.getElementById('toggle-jev')?.classList.contains('on') || false;
  let ok = false;
  try {
    const resp = await fetch('/api/config/jev', {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled, provider: name, providers }),
    });
    ok = resp.ok;
    if (!resp.ok) {
      const data = await resp.json().catch(() => null);
      const err = data && /** @type {any} */ (data).error;
      if (typeof err === 'string' && err) window.__showToast?.(err, 'error');
    }
  } catch { ok = false; }
  if (!ok) { setJevSaveState(false); return; }

  // ③ 落定：清空 key 输入框（明文零驻留）→ 现读回投影（key 徽标即刻到位）→ 停在刚保存的提供方。
  if (keyInput) keyInput.value = '';
  activeJevProvider = name;
  jevDeleteArmed = '';
  const fresh = await fetchJevStatus();
  if (fresh) lastJevStatus = fresh;
  paintJevSection();
  bindJevSectionEvents();
  setJevSaveState(true);
}

/**
 * 两段式删除（契约 §E.2 `#btn-delete-jev` 的 `data-armed` 中间态）：首击武装
 * （标签换「再点一次确认删除」），二击才真删（整块 PUT，其余提供方原样保留；
 * 凭据文件不动——删配置 ≠ 删凭据）。
 * @param {string} name
 * @returns {Promise<void>}
 */
async function armOrDeleteJevProvider(name) {
  if (!name || !jevProviderEntry(name)) return;
  if (jevDeleteArmed !== name) {
    jevDeleteArmed = name;
    activeJevProvider = name;
    paintJevSection();
    bindJevSectionEvents();
    return;
  }
  const existing = /** @type {Record<string, any>} */ ((lastJevStatus && lastJevStatus.providers) || {});
  /** @type {Record<string, Record<string, string>>} */
  const providers = {};
  for (const n of Object.keys(existing)) {
    if (n === name) continue;
    const e = existing[n] || {};
    /** @type {Record<string, string>} */
    const row = { type: String(e.type || '') };
    if (e.endpoint) row.endpoint = String(e.endpoint);
    if (e.model) row.model = String(e.model);
    if (e.keyRef) row.keyRef = String(e.keyRef);
    providers[n] = row;
  }
  const remaining = Object.keys(providers);
  const enabled = document.getElementById('toggle-jev')?.classList.contains('on') || false;
  let ok = false;
  try {
    const resp = await fetch('/api/config/jev', {
      method: 'PUT',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled, provider: remaining[0] || '', providers }),
    });
    ok = resp.ok;
  } catch { ok = false; }
  if (!ok) { window.__showToast?.(t('settings.saveFailed'), 'error'); return; }
  jevDeleteArmed = '';
  activeJevProvider = remaining[0] || '';
  const fresh = await fetchJevStatus();
  if (fresh) lastJevStatus = fresh;
  paintJevSection();
  bindJevSectionEvents();
  window.__showToast?.(t('settings.cfgCard.deleted'), 'success');
}

/**
 * 「测试可用性」钮的表单读取（点击时现读——未保存的改动照样测）。provider =
 * 表单里的提供方名（= DecisionProvider id）；keyRef = 配置里已存的凭据名优先，
 * 否则按名派生（保存后即真名；未落盘 ⇒ 引擎按「待录入」失败，不造假值）。
 * @returns {{provider: string, endpoint: string, keyRef: string}}
 */
function jevTestForm() {
  const provider = jevFieldValue('cfg-jev-name') || jevFieldValue('cfg-jev-type');
  const entry = jevProviderEntry(provider);
  return {
    provider,
    endpoint: jevFieldValue('cfg-jev-endpoint'),
    keyRef: entry && entry.keyRef ? String(entry.keyRef) : (provider ? jevSecretName(provider) : ''),
  };
}

/**
 * 绑定整节交互（每次重画后重绑：元素随 innerHTML 重建，监听器随元素蒸发；
 * 卡片两条走容器级委托 + 幂等哨兵）。
 */
function bindJevSectionEvents() {
  const wrap = document.getElementById('jev-section-wrap');
  if (!wrap) return;
  const list = document.getElementById('jev-provider-list');
  if (list) {
    bindCardSelect(list, (name) => {
      if (!name || name === activeJevProvider) return;
      activeJevProvider = name;
      jevDeleteArmed = '';
      paintJevSection();
      bindJevSectionEvents();
    });
    bindCardRemove(list, (name) => { void armOrDeleteJevProvider(name); });
  }
  document.getElementById('btn-add-jev-provider')?.addEventListener('click', () => {
    activeJevProvider = '';
    jevDeleteArmed = '';
    paintJevSection();
    bindJevSectionEvents();
    (/** @type {HTMLInputElement|null} */ (document.getElementById('cfg-jev-name')))?.focus();
  });
  // key 输入框掩码切换（判掩码只认内联 -webkit-text-security:disc；生图面同款读法）。
  // 值只进本输入框，切换不读写任何存储。
  document.getElementById('jev-key-eye')?.addEventListener('click', () => {
    const input = /** @type {HTMLInputElement|null} */ (document.getElementById('cfg-jev-key'));
    const eye = document.getElementById('jev-key-eye');
    if (!input || !eye) return;
    const style = /** @type {any} */ (input.style);
    const isMasked = style.webkitTextSecurity === 'disc';
    style.webkitTextSecurity = isMasked ? 'none' : 'disc';
    eye.replaceChildren();
    eye.insertAdjacentHTML('afterbegin', isMasked ? eyeOffSvg : eyeSvg);
    const label = t(isMasked ? 'settings.jev.keyHide' : 'settings.jev.keyShow');
    eye.setAttribute('aria-label', label);
    eye.title = label;
    eye.setAttribute('aria-pressed', isMasked ? 'true' : 'false');
  });
  document.getElementById('btn-save-jev')?.addEventListener('click', () => { void saveJevProvider(); });
  document.getElementById('btn-delete-jev')?.addEventListener('click', () => { void armOrDeleteJevProvider(activeJevProvider); });
  bindJevTestButton(wrap, { getForm: jevTestForm });
}

/**
 * 整节装配（renderSettings 尾调用）：先按手上的状态画（首次 = 空态：0 卡 +
 * 五字段全空），再拉 GET /api/config/jev 现读重画——拉取失败保持空态，不造假数据。
 * @returns {Promise<void>}
 */
async function bindJevSection() {
  if (!document.getElementById('jev-section-wrap')) return;
  const firstLoad = lastJevStatus === null;
  paintJevSection();
  bindJevSectionEvents();
  const fresh = await fetchJevStatus();
  if (!document.getElementById('jev-section-wrap')) return; // 面板已关 / 重渲换了节点
  if (!fresh) return;
  lastJevStatus = fresh;
  const sw = document.getElementById('toggle-jev');
  if (sw) setToggleState(sw, !!fresh.enabled);
  // 首载且当前选中项指向已配置的提供方 ⇒ 面板停在该提供方（空态则保持全空，不预填）。
  if (firstLoad) {
    const names = jevProviderNames();
    const pointer = String(fresh.provider || '');
    activeJevProvider = names.includes(pointer) ? pointer : '';
  }
  paintJevSection();
  bindJevSectionEvents();
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
  // 件四 · 设置页身份卡本地化（作者令 2026-11-02「头像在上，下面是用户名和邮箱…
  // 点击胶囊直接进自己的 profile 设置页」）：头像在上 + 用户名/邮箱单行胶囊，两档
  // 形态同一 DOM（手机层由 mobileLayer.css 同族规则适配）。**手机层也渲染** ——
  // 「设置页头像入口手机层不渲染」是手机顶栏批（2026-10-31）的旧口径，本批按作者
  // 新令就地推翻：身份卡回到设置页两档并在场（设备区仍在联系人页，不搬回）。
  // 点胶囊 = App 内个人资料面；点头像 = 换头像流程 / 未登录登录卡（activityBar
  // 既有委托统一承担，本文件不绑任何点击）。
  const mobileLayer = typeof document !== 'undefined' && document.body.classList.contains('mobile-layer');
  content.innerHTML = `
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.account')}</div>
      ${settingsIdentityCardHTML()}
      ${neblinkSettingsHTML()}
      ${mobileLayer ? '' : '<div id="settings-devices-slot"></div>'}
    </div>
    <div class="settings-section">
      <div class="settings-section-title">${t('settings.runtime')}</div>
      <div class="settings-row">
        <span class="settings-label">${t('settings.thinkingMode')}</span>
        <select class="cfg-select" id="cfg-thinking-effort" style="width:auto">
          ${['off','low','medium','high'].map(lvl => `<option value="${lvl}"${effortFromConfig() === lvl ? ' selected' : ''}>${t('settings.thinkingEffort.' + lvl)}</option>`).join('')}
        </select>
      </div>
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
    <div class="cfg-hint" id="model-chain-hint">${t('settings.modelChainHint')}</div>
    <div class="settings-section" id="model-chain-section">
      <!-- 模型回退链节（配置面风格统一批②，2026-10-31 作者令：/model 里的回退
           顺序调整页在设置页原样再放一份，不用打 /model；两份共享同一份状态/
           保存通道，改哪边都即时同步）。内容面 = modelPanel.js 的
           buildNebulaChainFace 同款（seed 态 / 拖拽排序链编辑器，逐动作即存）；
           /model 原入口保留不动（上方 model-chain-hint 照旧指路）。 -->
      <div class="settings-section-title">${t('settings.modelChainTitle')}</div>
      <div class="cfg-hint">${t('settings.modelChainSectionHint')}</div>
      <div id="model-chain-section-body"></div>
    </div>
    <div class="settings-section">
      <!-- Jev allocation section — 风格统一重排（settings-panel 批 C 件，
           2026-10-28 作者令：「JeV 插件分配那部分的风格，与『STT 高级设置』区的
           风格保持一致——以 STT 高级设置的风格为基准（布局/间距/控件形态/分组
           样式逐项对齐）」）。组件族与 STT 高级设置逐项同源：
           · 头行   .settings-row.stt-advance-toggle-row + .settings-collapse-toggle
                    （标题+chevron）+ 右侧状态位（分配通道健康，同 stt-status 位）；
           · 折叠体 .settings-collapse-body（层级下钻的收编内容面，见批注下）；
           · 开关行 .settings-row + .settings-label + toggleHTML（启用 JeV——
                    旧卡头行内开关随重排迁入折叠体首行，STT enabled 行同款）；
           · 字段组 .cfg-form-group 纵排 label+input（旧 cfg-row 横排退役）；
           · 保存行 显式保存 + inline-state（#334 语义不变）。
           功能零删减：分配通道健康 legs 分腿徽标（三改③）、PUT /api/config/jev
           保存全保留。
           🔴 jev-imagegen-align-llm 批（2026-11-02 作者令）推翻了本节三处旧面：
           提供方下拉→卡片列表、keyRef 行 + 明文回读行→唯一掩码 key 输入框、
           typesafe-jev 端点钉死预填→端点永不禁用且空态空。详见模块区
           「Jev 面板全面对齐 LLM 配置面」段（jevSecretName / saveJevProvider /
           bindJevSection）。-->
      <!-- 配置面风格统一批（2026-10-31 作者令）：本节改层级下钻——头行 =
           下钻入口（chevron 右转），点按桌面开浮层玻璃面板（js/configDrill.js）
           /手机 push 层级子页（mobile/settingsSub.js）；同一 #jev-advance-body
           节点两端收编，内容面/绑定零副本。「展开式配置」退役后端内联不渲染。-->
      <div class="settings-row stt-advance-toggle-row">
        <button type="button" class="settings-collapse-toggle settings-drill-toggle" id="jev-advance-toggle"
                aria-haspopup="dialog" aria-controls="jev-advance-body">
          <span class="settings-label">${t('settings.jev')}</span>
          <span class="settings-collapse-chevron" aria-hidden="true"></span>
        </button>
        <span class="cfg-hint stt-status" id="jev-status-hint"><span class="st-badge" data-tone="mut" id="settings-jev-health">…</span></span>
      </div>
      <div class="settings-collapse-body" id="jev-advance-body" hidden>
        <div class="settings-row">
          <span class="settings-label">${t('settings.jevEnabled')}</span>
          ${toggleHTML({ on: !!(cfg.jev && cfg.jev.enabled), id: 'toggle-jev', label: t('settings.jevEnabled') })}
        </div>
        <!-- 内容面（jev-imagegen-align-llm 批 · jevpanel 件，2026-11-02 作者令）：
             已配置以卡片列表呈现（可并存/可单删）、空态起步零预填、密钥直接录入走
             secrets。唯一绘制点 = jevSectionHTML()，数据源 = GET /api/config/jev
             （bindJevSection 拉取后原位重画——首帧即空态，零造假数据）。 -->
        <div id="jev-section-wrap">${jevSectionHTML()}</div>
      </div>
    </div>
    <div class="settings-section">
      <!-- 生图（imagegen）设置节（settings-panel 批 ②③件，2026-10-28 作者令；
           功能开放批 2026-10-30：正式开放、调试中徽标退役）。
           两形态由 sidebar/imagegenSection.js 单点渲染：seal=OFF（显式剔除构建）
           → 「未编入（release 构建零痕迹）」提示位；seal=ON（缺省）→ 正常配置面
           （enabled 开关 / provider/model 编辑 / key 状态与录入）。数据与绑定全
           在该模块（renderSettings 尾部 bindImagegenSection 拉取装配）。
           配置面风格统一批（2026-10-31 作者令）：本节与 JeV 节同款层级下钻——
           头行 = 下钻入口（#imagegen-advance-toggle，chevron 右转），点按桌面开
           浮层玻璃面板（js/configDrill.js）/手机 push 层级子页（mobile/
           settingsSub.js settings-imagegen 路由）；同一 #imagegen-advance-body
           节点两端收编，模块渲染面 #imagegen-section-wrap 住在其中随节点走。-->
      <div class="settings-row stt-advance-toggle-row">
        <button type="button" class="settings-collapse-toggle settings-drill-toggle" id="imagegen-advance-toggle"
                aria-haspopup="dialog" aria-controls="imagegen-advance-body">
          <span class="settings-label">${t('settings.imagegenTitle')}</span>
          <span class="settings-collapse-chevron" aria-hidden="true"></span>
        </button>
      </div>
      <div class="settings-collapse-body" id="imagegen-advance-body" hidden>
        <div id="imagegen-section-wrap"><div class="cfg-hint">${t('settings.imagegenLoading')}</div></div>
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
      ${SHOW_RERUN_ONBOARDING ? `<button class="cfg-btn" id="btn-rerun-onboarding" style="margin-left:var(--ds-space-2)">${t('settings.rerunOnboarding')}</button>` : ''}
      <!-- 文件缓存行（M2 · 设计 v1 §6.4/§7 表5）：占用读数（nfCacheStats 同步 seam，
           分桶明细进 title）+ 清空键（nfCacheReset seam，两段式确认 = 按钮第二击
           即确认位，同 btn-restart-gateway 先例——共用 #delete-box 弹窗其确认键
           文案是「删除」，复用会把本键错标成删除）。 -->
      <div class="settings-row" id="nf-cache-row">
        <span class="settings-label">${t('settings.fileCache')}</span>
        <span style="display:flex;align-items:center;gap:var(--ds-space-2)">
          <span id="nf-cache-usage" role="status" style="font-size:var(--mfs-min, 12px);color:var(--color-text-secondary)">…</span>
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
        <div style="margin-top:var(--ds-space-1);font-size:var(--mfs-min, 12px);color:var(--color-text-secondary)">${t('settings.connection')}: ${state.connected
          ? statusIcon('circle-check', 14, 'st-ok') + ' <span style="color:var(--status-word)">' + t('settings.connected') + '</span>'
          : statusIcon('wifi-off', 14, 'st-err') + ' <span style="color:var(--status-word)">' + t('settings.disconnected') + '</span>'}</div>
        <div style="margin-top:var(--ds-space-stack-gap);display:flex;align-items:center;gap:var(--ds-space-2)">
          <button class="cfg-btn cfg-btn-sm" id="btn-restart-gateway">${t('settings.restart')}</button>
          <span id="restart-status" role="status" style="font-size:var(--mfs-min, 12px);line-height:18px;min-height:18px;flex:1;min-width:0;color:var(--color-text-muted)"></span>
        </div>
        <!-- 更新功能已随 auth-trim 批（2026-10-20 作者令）退役：检查更新/立即更新/
             进度面/绿点整链移除，未来由官网统一检测更新取代。重启（restartStatus/
             restartResult 帧）状态行 = 上方 #restart-status，消费订阅见模块级。 -->
      </div>
    </div>`;

  paintSettingsIdentity();
  bindSettingsEvents(content, cfg);
  bindNeblinkEvents(() => renderSettings());
  // Secrets entry count pill (secrets-ui-redesign, 2026-10-08): read/cached
  // module-side, repainted on every render and on `secrets-changed`.
  refreshSecretsEntryCount();
  // 生图设置节（settings-panel 批 ②③件）：异步拉 GET /api/config/imagegen 后
  // 原位绘制两形态之一（seal OFF 提示位 / ON 配置面）。失败 = 错误提示行，
  // 不造假数据。
  void bindImagegenSection();
  // 配置面风格统一批（2026-10-31 作者令）：JeV/生图层级下钻注册（幂等；
  // 桌面浮层委托在 configDrill.js，手机层级子页委托在 settingsSub.js）+
  // 重渲后的浮层收编同步（开着的浮层内容节点被重渲换掉 ⇒ 按 id 重收编）。
  registerConfigDrill({ toggleSel: '#jev-advance-toggle', bodyId: 'jev-advance-body', titleKey: 'settings.jev' });
  registerConfigDrill({ toggleSel: '#imagegen-advance-toggle', bodyId: 'imagegen-advance-body', titleKey: 'settings.imagegenTitle' });
  syncConfigDrills();
  // 模型回退链节：挂载/重挂（与 /model 面板共享状态/保存通道，modelPanel.js
  // 单点；先卸上一任监听再挂，重渲不叠订阅）。
  modelChainUnmount?.();
  modelChainUnmount = mountModelChainSection(
    document.getElementById('model-chain-section-body'));
  // Jev 配置面装配（jev-imagegen-align-llm 批 · jevpanel 件）：先画空态，再拉
  // GET /api/config/jev 现读重画（卡片列表 + 表单 + key 在位性徽标；拉取失败保持
  // 空态）。旧的 key 值明文回读（GET /api/secrets/<keyRef>/value）整链已退役。
  void bindJevSection();
  // 设置页 apikey 明文回读预取（作者令 2026-10-27「设置里配置的 apikey 直接
  // 显示」）：进页即拉已存 provider key 真值（owner 鉴权单点
  // GET /api/config/provider-keys；providersUi 模块内 60s 缓存 + 单飞），编辑
  // 弹窗打开时已在手。失败静默——弹窗退回既有空态（留空保存 = "***" = 引擎
  // 保留旧值），保存路径零改动。
  revealProviderApiKeys().catch(() => {});

  // friendseal batch (2026-09-25 ①-A): the settings-page device expansion
  // area (chat entry per device). Lazy import — the P2-4 cycle-cut precedent:
  // the builder lives in messages.js (the device chat's owner face, which
  // statically imports neblink.js), so a static import here would create a
  // new cycle. Idempotent mount; the refresh tick below re-mounts it.
  const devicesSlot = document.getElementById('settings-devices-slot');
  if (devicesSlot) {
    import('./messages.js').then((m) => m.mountSettingsDevices(devicesSlot)).catch(() => {});
  }

  // device-provenance 批（2026-10-23）的「消息设备标记」设置区已随标注全自动批
  // （2026-10-26 作者令「消息来源识别应该是自动的」）整区退役：来源识别由
  // deviceIdentity.js 按花名册自动判定（登录会话→账号设备名；配对设备→登记名；
  // 匿名→默认标注），设置页不再有手动命名/绑定面。

  // Pre-fetch model capabilities, then refresh badges on provider cards
  preloadModelCapabilities(() => {
    const providerList = document.getElementById('provider-list');
    if (providerList) {
      providerList.querySelectorAll('.cfg-model-vision').forEach(el => {
        const ref = (/** @type {HTMLElement|null} */ (el.closest('.cfg-model-row')))?.dataset.modelRef;
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
      paintSettingsIdentity();
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

// 配置提供方卡片 = 共用组件薄壳（jev-imagegen-align-llm 批 · card 件，契约 §A.3）。
// 结构与层级已抽到 js/cfgProviderCard.js —— 本函数只剩「LLM 面实参装配」：
// 调用点（:934 providerNames.map）、class 族、DOM 顺序、文案逐字不变
// （metaExtra 仍传既有 "N model(s)" 串；模型行 ref/vision 徽标由组件同款生成）。
function renderProviderCard(name, p) {
  // Deleted providers are stored as null — render nothing instead of crashing
  // the settings re-render (remove flow calls renderSettings synchronously).
  if (!p) return '';
  const modelCount = (p.models || []).length;
  return providerCardHTML({
    name,
    face: 'llm',
    protocolBadge: protocolFormLabel(p.protocol),
    models: (p.models || []).map(m => m.id),
    metaExtra: `${modelCount} model${modelCount !== 1 ? 's' : ''}`,
  });
}

// ── Settings avatar section — 已迁入 settingsIdentity.js（件四，作者令 2026-11-02）──
// 修前本处是 `renderSettingsAvatar()`（只画头像、点击委托 activityBar 的
// activateAccountEntry）：2026-09-06 起「头像 only、区内零文案」；2026-11-01 起
// 已登录态点头像 = 两选一菜单（其中「个人主页」= window.open 官网）。
// 件四按作者令整体改写：头像在上 + 用户名/邮箱单行胶囊 + 点胶囊进 **App 内**
// 个人资料面（零外链），绘制/交互/资料面全在 `settingsIdentity.js`
// （`settingsIdentityCardHTML()` 出结构，`paintSettingsIdentity()` 出读数）。
// 本文件不再持有任何头像绘制代码 —— 禁在此重建第二份（双真源必然分叉）。

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
  const toggle = /** @type {HTMLButtonElement|null} */ (document.getElementById('toggle-autostart'));
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
  // budgetToEffort uses (≤2048 low / ≤8192 medium / ≤32768 high), so a saved
  // value round-trips through the adapter unchanged. Legacy numeric configs
  // are displayed at their nearest level (no migration needed — saving
  // rewrites the canonical number for that level).
  const EFFORT_BUDGETS = { low: 2048, medium: 8192, high: 32768 };
  document.getElementById('cfg-thinking-effort')?.addEventListener('change', /** @this {HTMLSelectElement} */ function() {
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

  // ── Jev section: 开关只作用法闸（三改①，2026-10-09）——不联动展开/收起。
  // 配置面风格统一批（2026-10-31）：开合入口退役——#jev-advance-toggle 改
  // 层级下钻入口（桌面浮层/手机子页收编），点按绑定在 js/configDrill.js +
  // mobile/settingsSub.js 的 document 委托。
  // 🔴 jev-imagegen-align-llm 批（2026-11-02 作者令）后，本节的字段/保存/删除/
  // 测试/掩码切换绑定**全部**迁到模块区的 bindJevSectionEvents（每次重画后重绑，
  // 元素随 #jev-section-wrap 的 innerHTML 重建）——此处只留这枚不随重画重建的开关。
  // 旧的提供方下拉切换联动端点钉死（三改②）随端点解禁整体退役。
  document.getElementById('toggle-jev')?.addEventListener('click', function() {
    setToggleState(this, !this.classList.contains('on'));
  });

  // ── Jev section: allocation-decision health (jev-plugins batch, 2026-10-07;
  // secrets-ui-redesign 2026-10-08: the bare word becomes a tri-state
  // .st-badge — activity(st-ok)=up · octagon-alert(st-err)=down + reason
  // (truncated inside the pill, full text on title) · circle-dashed(muted)=not
  // configured. Same reading as the (retired 2026-11-01) secrets-mode-badge: the
  // ICON carries the colour, the word stays neutral (--status-word). A fetch failure keeps the
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
    // 新段 = 合法默认段（09:00-12:00）：原子段字段没有「空值」这一状态
    // （空串不是 HH:mm，且面板只能产出合法的 HH:mm 二元组）。
    list.insertAdjacentHTML('beforeend', buildSegmentRowHtml({ start: '09:00', end: '12:00' }, idx));
    (/** @type {HTMLElement|null} */ (list.querySelector(`[data-seg-index="${idx}"] [data-tw-range]`)))?.focus();
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
    const endpoint = clear ? '' : ((/** @type {HTMLInputElement|null} */ (document.getElementById('stt-endpoint')))?.value.trim() || '');
    const model = clear ? '' : ((/** @type {HTMLInputElement|null} */ (document.getElementById('stt-model')))?.value.trim() || '');
    const apiKey = clear ? '' : ((/** @type {HTMLInputElement|null} */ (document.getElementById('stt-apikey')))?.value.trim() || '');
    // D 件（2026-10-28）：总开关随保存一起提交（显式布尔；后端三态合并——
    // 省略路径仅旧前端负载会出现）。关 = STT 整体停用：清空三串 + enabled=false
    // 时后端保留 {enabled:false} 文件（不再整体删档），停用态跨重启保持。
    const enabled = document.getElementById('toggle-stt-enabled')?.classList.contains('on') !== false;
    const allEmpty = !endpoint && !model && !apiKey;
    // Clear = explicit all-empty (three keys, backend deletes the config file);
    // normal save omits an empty apiKey so the backend merge keeps the stored
    // key — never send an empty string on a partial update.
    const sttConfig = clear
      ? { endpoint: '', apiKey: '', model: '', enabled }
      : { endpoint, model, enabled, ...(apiKey ? { apiKey } : {}) };
    sendWs({ type: 'setSttConfig', sttConfig });
    window.__showToast?.(t(allEmpty && enabled ? 'settings.sttCleared' : 'settings.sttSaved'), 'success');
  }
  // D 件：启用开关只翻本地态（显式保存随 #334 既有 STT 语义——非 toggle 即存
  // 族例外不新增），关=灰化三个输入框（TTL enabled 同款交互）。
  document.getElementById('toggle-stt-enabled')?.addEventListener('click', function () {
    const on = !this.classList.contains('on');
    setToggleState(this, on);
    ['stt-endpoint', 'stt-model', 'stt-apikey'].forEach((id) => {
      const inp = /** @type {HTMLInputElement|null} */ (document.getElementById(id));
      if (inp) inp.disabled = !on;
    });
    const hint = document.getElementById('stt-status-hint');
    if (hint) hint.textContent = t(on ? 'settings.sttUnconfiguredStatus' : 'settings.sttDisabledStatus');
  });
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
  document.getElementById('cfg-language')?.addEventListener('change', /** @this {HTMLSelectElement} */ function() {
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
      // 明文直显（作者令 2026-10-27）：开弹窗前等一次回读落地——进页已预取，
      // 这里只兜「进页即点」的竞态；回读失败也照常开弹窗（退回空态语义，
      // 见 providersUi.js revealProviderApiKeys）。
      Promise.resolve(revealProviderApiKeys()).catch(() => {}).then(() => {
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
    const cfg = (/** @type {HTMLTextAreaElement} */ (document.getElementById('config-editor'))).value;
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

// 时段选择器（原子段字段 = 整段一颗触发钮，js/timeWheel.js 单源）：
// 委托单点绑定——面板确认回写 dataset 后刷新行内跨零点徽标 + 落本地草稿。
// 精确输入行（.tw-in）在面板内、不走 settings 容器，故不再需要 change 委托。
attachTimeTriggers(document, {
  onRangeChange(btn) {
    const row = btn.closest('.segment-row');
    if (!row) return;
    updateSegmentRowHint(row);
    markScheduleDirty();   // #334: local draft only — Save commits
  },
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
  // 侧栏族（ui-unification-20261103-sidebar）：.ds-side-row = 形态唯一家
  // （design-system.css），.session-item 保留为 DOM 寻址钩子（JS 查询 + spec）。
  item.className = 'session-item ds-side-row'
    + (inFolder ? ' in-folder' : '')
    + (s.id === state.activeSessionId ? ' ds-side-row--active active' : '')
    + (state.pinnedSessions.has(s.id) ? ' ds-side-row--pinned pinned' : '')
    + (isSelected ? ' ds-side-row--selected selected' : '');
  item.dataset.id = s.id;
  // B3-2 生产者：会话行的跨重渲稳定 key（重渲后按 key 回焦，见 renderSessionSidebar）。
  item.dataset.focusKey = `session:${s.id}`;
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
    item.classList.add('dragging', 'ds-side-row--dragging');
    showDeleteZone();
  });
  item.addEventListener('dragend', () => {
    item.classList.remove('dragging', 'ds-side-row--dragging');
    document.querySelectorAll('.folder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
    hideDeleteZone();
  });
  const statusCls = getSessionStatusClass(s.id);
  const draft = state.sessionInputDrafts[s.id];
  // 行内文字形态走族内槽（.ds-side-title 主文案 / .ds-side-meta 时间与草稿），
  // 旧 class 全保留为钩子（重命名 contenteditable 与查询都按旧名）。
  const draftHtml = draft && draft.text
    ? '<div class="session-draft ds-side-meta ds-side-meta--dim">' + escapeHtml(draft.text.replace(/\n/g, ' ').slice(0, 60)) + '</div>'
    : '';
  const deleteBtnHtml = '<button class="session-delete ds-side-danger" title="' + t('session.delete') + '"><i data-lucide="x"></i></button>';
  item.innerHTML =
    '<div class="session-info">' +
    '<div class="session-name ds-side-title">' + escapeHtml(s.name) + '</div>' +
    (draftHtml || '<div class="session-time ds-side-meta">' + formatSessionTime(s.updatedAt || s.createdAt) + '</div>') +
    '</div>' +
    '<div class="session-status ' + statusCls + '">' +
    '<div class="status-spinner"><i data-lucide="loader-2"></i></div>' +
    '<div class="status-dot"></div>' +
    '</div>' +
    deleteBtnHtml;
  // Double-click to rename (only in normal mode)
  const nameEl = /** @type {HTMLElement} */ (item.querySelector('.session-name'));
  if (state.selectedSessionIds.size === 0) {
    nameEl.addEventListener('dblclick', (e) => {
      e.stopPropagation();
      nameEl.contentEditable = 'true';
      nameEl.focus();
      const range = document.createRange();
      range.selectNodeContents(nameEl);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    });
    const finishRename = () => {
      nameEl.contentEditable = 'false';
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
    (/** @type {HTMLElement} */ (item.querySelector('.session-delete'))).onclick = (e) => {
      e.stopPropagation();
      if (typeof window.__showDeleteModal === 'function') window.__showDeleteModal(s.id, s.name);
    };
  }
  item.onclick = (e) => {
    if ((/** @type {Element} */ (e.target)).closest('.session-name[contenteditable="true"]')) return;
    if ((/** @type {Element} */ (e.target)).closest('.session-delete')) return;
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
    const agentName = active.agentName || ROOT_AGENT_KEY;
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
      const label = agentName === ROOT_AGENT_KEY
        ? (isRootNamed() ? rootDisplayName() : '')
        : agentDisplayName(agentName);
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
  // B3-2：侧栏整表重建（`sessionList.innerHTML = ''` + 全量重排）会换掉当前聚焦的
  // `.session-item` / `.folder-item`。重渲前后按 key 回焦，Shift+] 的落脚点不再丢。
  const list = state.dom.sessionList;
  return withFocusKey(list instanceof HTMLElement ? list : null, () => renderSessionSidebarInner(sessionData, activeId));
}

/** 侧栏重建本体（重渲保焦包装见 renderSessionSidebar）。 */
function renderSessionSidebarInner(sessionData, activeId) {
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
    const agent = s.agentName || ROOT_AGENT_KEY;
    if (!agentGroups[agent]) agentGroups[agent] = { sessions: [], folders: [] };
    agentGroups[agent].sessions.push(s);
  });
  allFolders.forEach(f => {
    const agent = f.agentName || ROOT_AGENT_KEY;
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
      header.className = 'agent-group-header ds-side-label ds-side-label--meta';
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
      } else {
        // 重连折叠持久批（2026-10-20）口径对齐（残片清理批 2026-10-29）：busy
        // 重建面恒折叠 —— 会话切换重放腿与 historyReplay 同一契约，不得把缓冲
        // 的思考全文摊开；用户可点 label 手动展开。
        content.style.display = 'none';
        bindCollapsibleToggle(label, () => content);
      }
      // 残片清理批：重放补发的 thinking 段按历史渲染，不带流式游标（与
      // historyReplay 同批语义；真仍在飞的轮次由 live 增量帧的 rAF 自带游标）。
      content.innerHTML = renderMarkdownWithMath(pv.stream.thinkingText, true, { cache: done });
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
      pv.stream.currentAiBubble.innerHTML = renderMarkdownWithMath(pv.stream.aiText, true, { cache: !isStreaming, stream: isStreaming }) + (isStreaming ? '<span class="cursor"></span>' : '');
      row.appendChild(pv.stream.currentAiBubble);
      chat.appendChild(row);
      if (!isStreaming) {
        pv.stream.currentAiBubble = null;
        pv.stream.aiText = '';
      }
    }

    smartScroll();
  } else if (sid) {
    // 骨架占位卡整件退役（作者令 2026-11-05 12:28「我们目前的逻辑是通过一个卡片来
    // 显示加载过程 进来就应该是加载好的 这个占位逻辑多余了」；实拍形态 = 聊天流顶部
    // 4 条大圆角占位泡，含右侧用户位**绿泡**）。
    // 🔴 旧形态的真 bug：本支每次重渲都挂，摘除却只发生在 historyPage 帧 ⇒ 已渲染
    // 真消息的会话重渲后「占位与真内容同框」，且没有新 historyPage 到达时占位永不
    // 退场。现在：线程**真的空**（一条消息都没渲染）才出一条细加载行 —— 既有
    // `.history-loader`（spinner + 「加载中…」，与翻页加载同一个件，非卡片/非气泡
    // 形态），historyPage 到场由 main.js 处理器同帧摘除。三档判据因此仍分开：
    // 加载中 = 这一行；有内容 = 零占位；失败/空态各有自己的面（不吃本条）。
    if (!pv.dom.chat.children.length) showHistoryLoader();
  }

  if (state.sessionPendingTools[sid]) {
    renderToolPending(state.sessionPendingTools[sid].label, sid);
  }

  const isBusy = isStreaming;
  pv.dom.sendBtn.style.display = isBusy ? 'none' : 'flex';
  pv.dom.stopBtn.style.display = isBusy ? 'flex' : 'none';

  stopSpinner();

  if (!isBusy) pv.dom.input.focus();

  // 任务面换血（ReminderIsland 复刻批 2026-11-02）：本槽位渲染 = 待办列表
  //（renderTodoTaskList；旧 delegate 链块渲染 taskList.renderTaskList 代码零删除、
  // 本面零触发——模块仍被 todoFace/mobile/taskPanel 引用，数据面活在别面）。
  renderTodoTaskList(sid);
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
  const subMenu = /** @type {HTMLElement} */ (menu.querySelector('#ctx-folder-submenu'));
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
      const fid = (/** @type {HTMLElement} */ (el)).dataset.folderId;
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
  updateSessionStatus(/** @type {CustomEvent<{sessionId: string}>} */ (e).detail.sessionId);
  computeAgentStates();
});

window.addEventListener('session-attention', (e) => {
  const detail = /** @type {CustomEvent<{sessionId: string, attention: string}>} */ (e).detail;
  setSessionAttention(detail.sessionId, detail.attention);
  computeAgentStates();
});

window.addEventListener('session-unread', (e) => {
  updateSessionStatus(/** @type {CustomEvent<{sessionId: string}>} */ (e).detail.sessionId);
  markSessionActivity(/** @type {CustomEvent<{sessionId: string}>} */ (e).detail.sessionId);
});

window.addEventListener('session-compacting', (e) => {
  updateSessionStatus(/** @type {CustomEvent<{sessionId: string}>} */ (e).detail.sessionId);
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
  return ROOT_AGENT_KEY;
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

  const input = /** @type {HTMLInputElement} */ (row.querySelector('.folder-new-input'));
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

  // 侧栏族：树行 = .ds-side-row .ds-side-row--tree（32px 热区 + 紧内距）；
  // .folder-item 保留为 DOM 寻址钩子（拖放态 .drag-over 仍写在旧 class 上）。
  const folderEl = document.createElement('div');
  folderEl.className = 'folder-item ds-side-row ds-side-row--tree'
    + (isPinned ? ' ds-side-row--pinned pinned' : '')
    + (state.activeFolderId === folder.id ? ' ds-side-row--active active' : '');
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
    '<div class="folder-name ds-side-title">' + escapeHtml(folder.name) + '</div>' +
    (folder.projectRoot ? '<span class="folder-badge ds-side-meta" title="' + escapeHtml(folder.projectRoot) + '"><i data-lucide="hard-drive" style="width:12px;height:12px"></i></span>' : '') +
    '<div class="folder-status ' + statusCls + '">' +
      '<div class="status-spinner"><i data-lucide="loader-2"></i></div>' +
      '<div class="folder-status-dot"></div>' +
    '</div>' +
    '<button class="folder-delete ds-side-danger" title="' + t('sidebar.folderDelete') + '"><i data-lucide="x"></i></button>';

  // Drag: folder itself can be dragged (into another folder) or to delete zone
  folderEl.addEventListener('dragstart', (e) => {
    e.dataTransfer.setData('text/plain', 'folder:' + folder.id);
    e.dataTransfer.effectAllowed = 'move';
    folderEl.classList.add('dragging', 'ds-side-row--dragging');
    showDeleteZone();
  });
  folderEl.addEventListener('dragend', () => {
    folderEl.classList.remove('dragging', 'ds-side-row--dragging');
    document.querySelectorAll('.folder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
    hideDeleteZone();
  });

  // VSCode-style: click selects folder + expand/collapse
  folderEl.addEventListener('click', (e) => {
    if ((/** @type {Element} */ (e.target)).closest('.folder-delete')) return;
    if ((/** @type {Element} */ (e.target)).closest('.folder-name[contenteditable="true"]')) return;
    setActiveFolder(folder.id);
    toggleFolder(folder.id);
  });

  // Right-click context menu
  folderEl.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    showFolderCtxMenu(e.clientX, e.clientY, folder.id);
  });

  // Double-click to rename folder (inline, same as session)
  const folderNameEl = /** @type {HTMLElement} */ (folderEl.querySelector('.folder-name'));
  const startFolderRename = () => {
    folderNameEl.contentEditable = 'true';
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
    folderNameEl.contentEditable = 'false';
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
      rulesItem.className = 'session-item in-folder rules-item ds-side-row ds-side-row--tree';
      rulesItem.innerHTML =
        '<div class="session-icon"><i data-lucide="file-text" style="width:14px;height:14px;color:var(--color-text-muted)"></i></div>' +
        '<div class="session-name ds-side-title">rules.md</div>';
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
      if (!childrenContainer.contains(/** @type {Node|null} */ (e.relatedTarget))) {
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
      nameEl.contentEditable = 'true';
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
        // picker-device-switch 批：项目根绑定本机会话工作区（回写面无设备定语——
        // setFolderProjectRoot 只收路径串），选择器内禁用设备切换，杜绝远端路径
        // 静默落本机的错觉（G.12）。新建/迁移项目与文件浏览器的入口不受影响。
        deviceSwitch: false,
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

