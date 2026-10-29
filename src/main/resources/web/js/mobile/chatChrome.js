// mobile/chatChrome.js — 聊天根顶栏双键（dock 批；浮层下拉列退役后的聊天根
// 常驻入口）。
//
// v3 双液态下拉列退役（作者令 2026-10-29「各节目都是独立界面 不再像目前这样
// 跟其他界面重叠」）后，聊天根的会话级入口收敛为顶栏左右两枚玻璃圆键：
//   · 左键「任务」（list-todo）：push「任务」详情页（taskPanel 面收编；
//     徽标 = taskBadgeCount，触发 = delegate-chain-mirror-updated /
//     nebflow-session-change 与 taskPanel 同族事件，零新轮询）；
//   · 右键「搜索」（search）：push「搜索」详情页（chatSearch 面，openSearchModal
//     同一桌面导出 API）。
// 定时/心跳/后台/智能体/手机接入等其余入口 = 设置根页「工作台」分组行
// （navController HUB_ROWS）与联系人页「手机接入」行（socialFaces）。
// 桌面零改动边界：两键为本层自建节点（拆层摘除）；桌面 header 簇零触碰。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import state from '../state.js';
import { taskBadgeCount } from './taskPanel.js';
import { pushPage, logEvent, touchLayer } from './navController.js';

/** @type {HTMLButtonElement|null} */
let taskBtn = null;
/** @type {HTMLButtonElement|null} */
let searchBtn = null;
/** @type {Array<() => void>} */
let unsubs = [];

function syncTaskBadge() {
  if (!taskBtn) return;
  const n = taskBadgeCount();
  const b = /** @type {HTMLElement|null} */ (taskBtn.querySelector('.mh-chrome-badge'));
  if (!b) return;
  b.hidden = n === 0;
  b.textContent = n > 99 ? '99+' : String(n);
  taskBtn.title = t('taskpanel.title');
}

export function activateChatChrome() {
  const header = document.getElementById('header');
  if (!header) return () => {};

  taskBtn = document.createElement('button');
  taskBtn.type = 'button';
  taskBtn.className = 'mh-chrome-btn glass-control';
  taskBtn.dataset.chrome = 'tasks';
  taskBtn.title = t('taskpanel.title');
  taskBtn.setAttribute('aria-label', t('taskpanel.title'));
  taskBtn.innerHTML =
    '<i data-lucide="list-todo"></i><span class="mh-chrome-badge" hidden></span>';
  taskBtn.addEventListener('click', (e) => {
    // 桌面面 outside-close 监听 document 冒泡 —— 入口键停冒泡防误关（hub 行同款）。
    e.stopPropagation();
    touchLayer();
    logEvent('chrome-click', { face: 'tasks' });
    pushPage('tasks');
  });

  searchBtn = document.createElement('button');
  searchBtn.type = 'button';
  searchBtn.className = 'mh-chrome-btn glass-control';
  searchBtn.dataset.chrome = 'search';
  searchBtn.title = t('search.placeholder') || '搜索聊天记录';
  searchBtn.setAttribute('aria-label', searchBtn.title);
  searchBtn.innerHTML = '<i data-lucide="search"></i>';
  searchBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    touchLayer();
    logEvent('chrome-click', { face: 'search' });
    pushPage('search');
  });

  header.append(taskBtn, searchBtn);
  if (typeof lucide !== 'undefined') createIconsIn(header);

  syncTaskBadge();
  const onMirror = () => syncTaskBadge();
  window.addEventListener('delegate-chain-mirror-updated', onMirror);
  window.addEventListener('nebflow-session-change', onMirror);
  const onLocale = () => {
    syncTaskBadge();
    if (searchBtn) searchBtn.title = t('search.placeholder') || '搜索聊天记录';
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => {
    window.removeEventListener('delegate-chain-mirror-updated', onMirror);
    window.removeEventListener('nebflow-session-change', onMirror);
    window.removeEventListener('locale-changed', onLocale);
  });

  return function teardownChatChrome() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    taskBtn?.remove();
    searchBtn?.remove();
    taskBtn = null;
    searchBtn = null;
  };
}
