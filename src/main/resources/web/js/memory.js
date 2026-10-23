// memory.js — Memory modal with Agent / User tabs
//
// 面板合并批（2026-10-23 作者令①②）：本 modal 升级为「智能体 + 记忆」合并面板
// 的唯一载体 —— 顶层两枚页签（智能体 / 记忆，文案复用 profile.title /
// memory.title 既有键）切换两个分区：
//   · 智能体分区 = agentProfile.js buildProfilePane() 注入的 .ap-pane
//     （头像点击即换头像 + 名称就地编辑 + 说明行）；
//   · 记忆分区 = 既有 Agent/User scope tabs + 内容面 + Save/Cancel（零变化）。
// 旧独立 #agent-profile-panel（body 级弹窗）退役：桌面会话头头像与 #memory-btn
// 都指向本面板（openMergedPanel('agent'|'memory')）；分区分显由
// `#memory-modal[data-pane]` 属性 + CSS 驱动（桌面/手机同一套）。

import state from './state.js';
import { sendWs } from './ws.js';
import { t } from './i18n.js';
import { buildProfilePane } from './agentProfile.js';

/** Currently active tab scope. */
let activeScope = 'agent';

/** Currently active top-level pane: 'agent'（智能体）| 'memory'（记忆）. */
let activePane = 'agent';

/** Cache per-scope content so tab switches don't re-fetch within same modal open. */
const cache = { user: null, agent: null };

/** The session whose memory is being viewed. Set when the modal opens. */
let memorySessionId = null;

/** Show the Memory button in the activity bar. */
export function showMemoryButton() {
  const btn = document.getElementById('memory-btn');
  if (btn) {
    btn.hidden = false;
  }
}

/**
 * Clear all cached memory. Called on session switch so the new session
 * fetches fresh content from the server instead of showing stale data.
 */
export function clearMemoryCache() {
  cache.user = null;
  cache.agent = null;
}

/** Switch top-level pane（智能体 ⇄ 记忆）：data-pane 属性驱动 CSS 分区显隐。 */
function switchPane(pane) {
  activePane = pane;
  const modal = /** @type {HTMLElement|null} */ (document.getElementById('memory-modal'));
  if (modal) modal.dataset.pane = pane;
  document.querySelectorAll('.merged-tab').forEach((tab) => {
    const on = /** @type {HTMLElement} */ (tab).dataset.pane === pane;
    tab.classList.toggle('active', on);
    tab.setAttribute('aria-selected', on ? 'true' : 'false');
  });
  const title = document.getElementById('memory-modal-title');
  if (title) title.textContent = t(pane === 'agent' ? 'profile.title' : 'memory.title');
  if (pane === 'memory') loadTab(activeScope);
}

/** Open the merged panel on a pane ('agent' | 'memory'), fetch active content. */
export function openMergedPanel(pane = 'memory') {
  memorySessionId = state.activeSessionId;
  clearMemoryCache();
  document.getElementById('memory-modal').classList.add('show');
  document.getElementById('memory-overlay').classList.add('on');
  switchPane(pane);
}

/** Close the memory modal. */
export function closeMemoryModal() {
  document.getElementById('memory-modal').classList.remove('show');
  document.getElementById('memory-overlay').classList.remove('on');
}

/** Switch tab — cache current content first. */
function switchTab(scope) {
  cache[activeScope] = document.getElementById('memory-content-input').value;
  activeScope = scope;
  document.querySelectorAll('.memory-tab').forEach(t => {
    t.classList.toggle('active', t.dataset.scope === scope);
  });
  loadTab(scope);
}

/** 手机层 P5 读态卡同步缝（mobile-secondary-screens 实施批）：内容面值由 WS
 *  异步程序赋值（不触发 input 事件），memoryFace.js 挂 window.__mhMemorySync
 *  钩子重切读态卡；桌面恒 undefined ⇒ 零行为改动。 */
function syncMobileFace() {
  if (document.body.classList.contains('mobile-layer')) {
    (/** @type {any} */ (window)).__mhMemorySync?.();
  }
}

/** Load content for a scope — use cache if hit, otherwise fetch from server. */
function loadTab(scope) {
  const input = document.getElementById('memory-content-input');
  if (cache[scope] !== null) {
    input.value = cache[scope];
  } else {
    input.value = '';
    sendWs({ type: 'getMemory', scope, sessionId: memorySessionId });
    // Retry once if server doesn't respond within 800ms
    setTimeout(() => {
      if (cache[scope] === null && activeScope === scope) {
        sendWs({ type: 'getMemory', scope, sessionId: memorySessionId });
      }
    }, 800);
  }
  syncMobileFace();
}

/** Handle memoryData from server — update textarea + cache. */
export function handleMemoryData(data) {
  cache[data.scope] = data.content || '';
  if (data.scope === activeScope) {
    document.getElementById('memory-content-input').value = data.content || '';
  }
  syncMobileFace();
}

/**
 * Handle memoryChanged push notification — invalidate cache and refresh
 * the active tab if the modal is open (so the user sees agent edits in real-time).
 */
export function handleMemoryChanged(data) {
  const path = data.path || '';
  let changedScope = null;
  if (path.endsWith('User.md')) changedScope = 'user';
  else if (path.endsWith('memory.md')) changedScope = 'agent';

  if (changedScope) {
    cache[changedScope] = null;
    const modal = document.getElementById('memory-modal');
    if (modal && modal.classList.contains('show') && changedScope === activeScope) {
      loadTab(activeScope);
    }
  }
}

/** Save the current tab's content. */
export function saveMemory() {
  const content = document.getElementById('memory-content-input').value;
  cache[activeScope] = content;
  sendWs({ type: 'saveMemory', scope: activeScope, content, sessionId: memorySessionId });
}

/** 顶层页签行（智能体 / 记忆）：幂等一件；文案走既有键，locale 切换跟随。 */
function ensureMergedChrome() {
  const modal = document.getElementById('memory-modal');
  if (!modal || modal.querySelector('.merged-tabs')) return;
  const title = document.getElementById('memory-modal-title');

  const tabsRow = document.createElement('div');
  tabsRow.className = 'merged-tabs';
  tabsRow.setAttribute('role', 'tablist');
  tabsRow.setAttribute('aria-label', t('memory.title'));
  for (const pane of ['agent', 'memory']) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'merged-tab';
    b.dataset.pane = pane;
    b.setAttribute('role', 'tab');
    b.setAttribute('aria-selected', 'false');
    b.textContent = t(pane === 'agent' ? 'profile.title' : 'memory.title');
    b.addEventListener('click', () => switchPane(pane));
    tabsRow.appendChild(b);
  }
  if (title) modal.insertBefore(tabsRow, title);
  else modal.insertBefore(tabsRow, modal.firstChild);

  // 智能体分区（agentProfile.js 的一件式 .ap-pane）：插在记忆 scope tabs 之前。
  const memoryTabs = modal.querySelector('.memory-tabs');
  const paneEl = buildProfilePane();
  if (memoryTabs) modal.insertBefore(paneEl, memoryTabs);
  else modal.appendChild(paneEl);

  window.addEventListener('locale-changed', () => {
    tabsRow.querySelectorAll('.merged-tab').forEach((tab) => {
      const pane = /** @type {HTMLElement} */ (tab).dataset.pane;
      tab.textContent = t(pane === 'agent' ? 'profile.title' : 'memory.title');
    });
    const titleNow = document.getElementById('memory-modal-title');
    if (titleNow) titleNow.textContent = t(activePane === 'agent' ? 'profile.title' : 'memory.title');
  });
}

/** Initialize memory UI — bind button, tabs, modal buttons, overlay dismiss. */
export function initMemory() {
  ensureMergedChrome();
  document.getElementById('memory-btn')?.addEventListener('click', () => openMergedPanel('memory'));
  document.querySelectorAll('.memory-tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.scope));
  });
  document.getElementById('memory-modal-cancel')?.addEventListener('click', closeMemoryModal);
  document.getElementById('memory-modal-save')?.addEventListener('click', saveMemory);
  document.getElementById('memory-overlay')?.addEventListener('click', e => {
    if (e.target.id === 'memory-overlay') closeMemoryModal();
  });
}
