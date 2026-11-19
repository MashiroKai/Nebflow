// mobile/searchFace.js — P2「搜索聊天记录」手机面（mobile-secondary-screens
// 实施批；样张 v3.5 P2 + v3.6 作者裁定②同款落地）。
//
// 裁定②口径（v3.6 报告 §1②）：搜索收窄主会话 ——
//   · 范围切换（当前会话/全部会话 select）删除：手机层恒 pin scope='current'，
//     select 本体 display:none（CSS），结果行会话名前缀同面隐藏（主会话内无二义）；
//   · 计数行 =「主会话 · N 条结果」（+ 既有截断尾注，chatSearch.statusSummary
//     手机层分支同源键 search.scopeMain）；
//   · 筛选两行：类型·日期（桌面 .search-tabs 同款沿用）+ 角色（本文件建的
//     kind select：全部来源/用户/助手/AI —— i18n search.sourceAll 同源键；
//     档值走 chatSearch.matchesKind 缝，window.__mhSearchKind，桌面恒空。
//     「工具」档随 2026-10-30 作者令①退役：关键词搜索只搜对话，tool 行不入
//     结果，常驻空档不再列出）；
//   · 角色行（保留）：类型·日期（桌面 .search-tabs 同款沿用）+ 角色（本文件建的
//     kind select：全部来源/用户/助手/AI —— i18n search.sourceAll 同源键；
//     档值走 chatSearch.matchesKind 缝，window.__mhSearchKind，桌面恒空。
//     「工具」档随 2026-10-30 作者令①退役：关键词搜索只搜对话，tool 行不入
//     结果，常驻空档不再列出）。
//
// 🔴 三点入口批（2026-11-05 作者令，本文件的破坏性变更——**只删不增**）：
//   ① 面板头图标 `.mh-search-head-ic` 退役：它住在 `.search-modal-header`，而该头
//      （重复标题「搜索聊天记录」）同批整体隐藏 ⇒ 顶部唯一标题 = 页面名胶囊
//      「搜索聊天记录」（`mobile/navController.js` PAGES.search）；
//   ② 底部说明脚注 `#mh-search-foot`（原第 14-16 行那条 mobileScopeNote）**整件
//      删除**（作者：「底下的说明文字不要」）；结果区因此多吃到它的高度，读数见
//      批报告；`search.mobileScopeNote` 文案键随同退役（零死键）。
//
// 桌面零改动边界：本文件只动自有节点（角色行）+ 写 window 缝 + pin 既有 select
// 值；搜索逻辑（流式/分页/跳转）零重造。

import { t } from '../i18n.js';
import { scheduleSearch } from '../chatSearch.js';
import { logEvent } from './navEvents.js';

/** 角色档位（值 = chatSearch data-kind 词表；label 键与桌面 type 徽标同源）。
 *  2026-10-30 作者令①：tool 档退役（搜索结果只搜对话，tool 行不入结果）。 */
const KIND_OPTIONS = [
  { value: '', labelKey: 'search.sourceAll' },
  { value: 'user', labelKey: 'search.typeUser' },
  { value: 'agent', labelKey: 'search.typeAgent' },
  { value: 'ai', labelKey: 'search.typeAi' },
];

/** @type {HTMLElement|null} 角色行（.search-tabs 之后的第二筛选行）。 */
let kindRow = null;
/** @type {HTMLSelectElement|null} */
let kindSel = null;
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

function ensureFaces() {
  const modal = document.getElementById('search-modal');
  if (!modal) return;

  // 🔴 三点入口批（2026-11-05，作者令「把搜索和搜索聊天记录结合……下面的文字不要，
  // 然后底下的说明文字不要」）：本文件原建的两件**同批退役**——
  //   ① 面板头图标 `.mh-search-head-ic`：它住在 `.search-modal-header` 里，而该头
  //      （重复标题「搜索聊天记录」）已整体隐藏（顶部唯一标题 = 页名胶囊）；
  //   ② 底部说明脚注 `#mh-search-foot`（`search.mobileScopeNote` 那条「仅检索当前
  //      主会话 · 类型/日期/角色筛选可叠加 · 空态：…」）：整行删除，结果区因此
  //      获得原本被它吃掉的高度。
  // 保留件 = 角色筛选行（`#mh-search-kind-row`，v3.6 筛选两行口径）与范围 pin。

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

  // 语言切换：角色档/aria 同帧刷新（locale-changed 与桌面渲染同源事件）。
  const onLocale = () => {
    syncKindRow();
    if (kindSel) kindSel.setAttribute('aria-label', t('search.sourceAll'));
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));

  return function teardownSearchFace() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    if (overlayMo) { overlayMo.disconnect(); overlayMo = null; }
    (/** @type {any} */ (window)).__mhSearchKind = '';
    if (kindRow) { kindRow.remove(); kindRow = null; kindSel = null; }
  };
}
