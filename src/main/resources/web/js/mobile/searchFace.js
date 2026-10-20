// mobile/searchFace.js — P2「搜索聊天记录」手机面（mobile-secondary-screens
// 实施批；样张 v3.5 P2 + v3.6 作者裁定②同款落地）。
//
// 裁定②口径（v3.6 报告 §1②）：搜索收窄主会话 ——
//   · 范围切换（当前会话/全部会话 select）删除：手机层恒 pin scope='current'，
//     select 本体 display:none（CSS），结果行会话名前缀同面隐藏（主会话内无二义）；
//   · 计数行 =「主会话 · N 条结果」（+ 既有截断尾注，chatSearch.statusSummary
//     手机层分支同源键 search.scopeMain）；
//   · 筛选两行：类型·日期（桌面 .search-tabs 同款沿用）+ 角色（本文件建的
//     kind select：全部来源/用户/助手/AI/工具 —— i18n search.sourceAll 同源键；
//     档值走 chatSearch.matchesKind 缝，window.__mhSearchKind，桌面恒空）；
//   · 脚注（说明/空态）：「仅检索当前主会话 · 类型/日期/角色筛选可叠加 ·
//     空态：无匹配结果——试试缩短关键词或清除日期/类型筛选」（empty/hint 两腿
//     = 既有 search.noResults / search.noResultsSuggestion 现网同款）。
//
// 桌面零改动边界：本文件只追加自有节点（头图标/角色行/脚注）+ 写 window 缝 +
// pin 既有 select 值；拆层全部原样摘除。搜索逻辑（流式/分页/跳转）零重造。

import { t } from '../i18n.js';
import { createIconsIn } from '../utils.js';
import { scheduleSearch } from '../chatSearch.js';
import { logEvent } from './navController.js';

/** 角色档位（值 = chatSearch data-kind 词表；label 键与桌面 type 徽标同源）。 */
const KIND_OPTIONS = [
  { value: '', labelKey: 'search.sourceAll' },
  { value: 'user', labelKey: 'search.typeUser' },
  { value: 'agent', labelKey: 'search.typeAgent' },
  { value: 'ai', labelKey: 'search.typeAi' },
  { value: 'tool', labelKey: 'search.typeTool' },
];

/** @type {HTMLElement|null} 角色行（.search-tabs 之后的第二筛选行）。 */
let kindRow = null;
/** @type {HTMLSelectElement|null} */
let kindSel = null;
/** @type {HTMLElement|null} 说明/空态脚注。 */
let foot = null;
/** @type {HTMLElement|null} 面板头图标（来源键 i-search 同款）。 */
let headIcon = null;
/** @type {MutationObserver|null} overlay .on 观察（开面板即 pin 主会话范围）。 */
let overlayMo = null;
/** @type {Array<() => void>} */
let unsubs = [];

function pinScopeToCurrent() {
  const scope = /** @type {HTMLSelectElement|null} */ (document.getElementById('search-scope'));
  if (scope && scope.value !== 'current') scope.value = 'current';
}

function syncKindRow() {
  if (!kindSel) return;
  const prev = kindSel.value;
  kindSel.innerHTML = KIND_OPTIONS.map((o) =>
    `<option value="${o.value}">${t(o.labelKey)}</option>`).join('');
  if (KIND_OPTIONS.some((o) => o.value === prev)) kindSel.value = prev;
}

function syncFoot() {
  if (!foot) return;
  foot.textContent = t('search.mobileScopeNote', {
    empty: t('search.noResults'),
    hint: t('search.noResultsSuggestion'),
  });
}

function ensureFaces() {
  const modal = document.getElementById('search-modal');
  if (!modal) return;

  // 面板头图标（样张 P2 头 = 来源键图标；桌面头只有标题 + 关闭钮）。
  const head = modal.querySelector('.search-modal-header');
  if (head && !head.querySelector('.mh-search-head-ic')) {
    headIcon = document.createElement('span');
    headIcon.className = 'mh-search-head-ic';
    headIcon.setAttribute('aria-hidden', 'true');
    headIcon.innerHTML = '<i data-lucide="search"></i>';
    head.insertBefore(headIcon, head.firstChild);
    if (typeof lucide !== 'undefined') createIconsIn(headIcon);
  }

  // 角色行：类型·日期 tabs 之后的第二筛选行（v3.6 筛选两行口径）。
  const controls = modal.querySelector('.search-controls');
  const tabs = modal.querySelector('.search-tabs');
  if (controls && tabs && !document.getElementById('mh-search-kind-row')) {
    kindRow = document.createElement('div');
    kindRow.id = 'mh-search-kind-row';
    kindRow.className = 'mh-search-kindrow';
    kindSel = document.createElement('select');
    kindSel.id = 'mh-search-kind';
    kindSel.className = 'cfg-select mh-search-kind';
    kindSel.setAttribute('aria-label', t('search.sourceAll'));
    syncKindRow();
    kindSel.addEventListener('change', () => {
      (/** @type {any} */ (window)).__mhSearchKind = kindSel?.value || '';
      logEvent('p2-kind-filter', { kind: kindSel?.value || '' });
      scheduleSearch(); // 导出缝：与 scope/tool select 同一条去抖调度路径
    });
    kindRow.appendChild(kindSel);
    tabs.insertAdjacentElement('afterend', kindRow);
  }

  // 说明/空态脚注（面板底部；CSS 管样式，本文件只管文案与生命周期）。
  if (!document.getElementById('mh-search-foot')) {
    foot = document.createElement('div');
    foot.id = 'mh-search-foot';
    foot.className = 'mh-search-foot';
    syncFoot();
    modal.appendChild(foot);
  }
}

export function activateSearchFace() {
  ensureFaces();
  pinScopeToCurrent();

  // 开面板即 pin 主会话（openSearchModal 置 .on；去抖 300ms 前完成 pin）。
  const overlay = document.getElementById('search-overlay');
  if (overlay) {
    overlayMo = new MutationObserver(() => {
      if (overlay.classList.contains('on')) pinScopeToCurrent();
    });
    overlayMo.observe(overlay, { attributes: true, attributeFilter: ['class'] });
  }

  // 语言切换：角色档/脚注/aria 同帧刷新（locale-changed 与桌面渲染同源事件）。
  const onLocale = () => {
    syncKindRow();
    if (kindSel) kindSel.setAttribute('aria-label', t('search.sourceAll'));
    syncFoot();
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));

  return function teardownSearchFace() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    if (overlayMo) { overlayMo.disconnect(); overlayMo = null; }
    (/** @type {any} */ (window)).__mhSearchKind = '';
    if (kindRow) { kindRow.remove(); kindRow = null; kindSel = null; }
    if (foot) { foot.remove(); foot = null; }
    if (headIcon) { headIcon.remove(); headIcon = null; }
  };
}
