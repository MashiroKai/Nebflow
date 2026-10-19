// mobile/layoutController.js — v3「右列液态下拉」（样张 20261020_214500 v3③ 同款）。
//
// v3 演进：r4 的右缘双态活动栏（#mobile-activity-bar + 走廊 --mobile-bar-gutter）
// 退役 —— 作者已批 v3③「液态下拉」：页面键收进顶栏右圆钮的液态下拉列（宽 40
// 恒定，子钮 spring 过冲析出，再点反向收回）。**键位映射对齐真实客户端**（任务
// 书口径）：右列 = r2 导航面七键**去聊天**——画板/项目/图谱/插件/用量/设置。
// 主会话不再是键：聊天是 root，页面各有「<」返回钮（栈契约不变）。
//
// 保留的 r4 职责（与呈现形态正交）：
//   · 按下态严格镜像 navController 的栈顶 + 卡片层状态（键上 .active）；
//   · 启动自动展开抑制（画板卡默认展开盖输入区形态不回归）；
//   · 手机层落位即收起桌面残留的侧栏抽屉态；
//   · 空闲预热共享模块（点击零延迟）。
//
// 共享组件复用声明：本文件不复制任何桌面逻辑；桌面代码路径零改动。

import { key } from '../branding.js';
import { closeCanvas, isCanvasOpen } from '../canvas.js';
import { setSideBarCollapsed, isSideBarCollapsed } from '../activityBar.js';
import { createIconsIn } from '../utils.js';
import {
  navState, navigateTo, toggleCards, warm,
  logEvent, touchLayer, isLayerTouched, syncCardLayer,
} from './navController.js';
import { rightList, collapseDDs } from './headerController.js';

/** 右列键（自上而下）= r2 导航面七键去聊天：画板 → 项目 → 图谱 → 插件 → 用量 → 设置。 */
const BAR_BUTTONS = [
  { face: 'canvas',   icon: 'layers',         zh: '画板',      en: 'Documents' },
  { face: 'projects', icon: 'folder-kanban',  zh: '项目',      en: 'Projects' },
  { face: 'graph',    icon: 'sparkles',       zh: '图谱',      en: 'Wiki Graph' },
  { face: 'plugins',  icon: 'puzzle',         zh: '插件',      en: 'Plugins' },
  { face: 'usage',    icon: 'bar-chart-3',    zh: 'token 用量', en: 'Usage' },
  { face: 'settings', icon: 'settings',       zh: '设置',      en: 'Settings' },
];

function tt(zh, en) {
  // 语言源 = i18n.js 同源键（key('locale')，branding.js 命名空间派生）。
  // 重启窗热修批 2026-10-18：裸 `'nebflow_locale'` 字面随品牌迁移失效，迁 `key()`。
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** @type {Array<() => void>} */
let unsubs = [];

// ── 壳构建：右列键（headerController 建列壳，本文件只填键）─────────────

function buildKeys() {
  const list = rightList();
  if (!list) return;
  for (const b of BAR_BUTTONS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'mh-key activity-btn';
    btn.dataset.face = b.face;
    btn.title = tt(b.zh, b.en);
    btn.setAttribute('aria-pressed', 'false');
    btn.innerHTML = `<i data-lucide="${b.icon}"></i>`;
    list.appendChild(btn);
  }
  if (typeof lucide !== 'undefined') createIconsIn(list);
}

// ── 右列键 → 导航（规格②③）─────────────────────────────────────────

function onKeyClick(face) {
  touchLayer();
  logEvent('bar-click', { face });
  switch (face) {
    case 'canvas':
      toggleCards();
      break;
    case 'projects':
    case 'graph':
    case 'plugins':
    case 'usage':
    case 'settings':
      navigateTo(face);
      break;
    default:
      return;
  }
  collapseDDs(); // v3：页面键按下即收列（目标面全屏接管，列不必常开）
}

// ── 按下态同步（严格镜像 navController 栈顶 + 卡片层）────────────────

function syncPressed() {
  const s = navState();
  const on = {
    canvas: s.cardsLive,
    projects: s.top === 'projects',
    graph: s.top === 'graph',
    plugins: s.top === 'plugins',
    usage: s.top === 'usage',
    settings: s.top === 'settings',
  };
  for (const b of BAR_BUTTONS) {
    const btn = /** @type {HTMLElement|null} */ (document.querySelector(`.mh-dd[data-dd="right"] [data-face="${b.face}"]`));
    if (!btn) continue;
    btn.classList.toggle('active', !!on[b.face]);
    btn.setAttribute('aria-pressed', on[b.face] ? 'true' : 'false');
  }
}

function watchPressed() {
  const onNav = () => syncPressed();
  document.addEventListener('nf:mobile-nav', onNav);
  document.addEventListener('canvas-tab-switched', onNav);
  document.addEventListener('canvas-tab-closed', onNav);
  const mo = new MutationObserver(() => syncPressed());
  mo.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  const onLocale = () => {
    for (const b of BAR_BUTTONS) {
      const btn = /** @type {HTMLElement|null} */ (document.querySelector(`.mh-dd[data-dd="right"] [data-face="${b.face}"]`));
      if (btn) btn.title = tt(b.zh, b.en);
    }
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => {
    document.removeEventListener('nf:mobile-nav', onNav);
    document.removeEventListener('canvas-tab-switched', onNav);
    document.removeEventListener('canvas-tab-closed', onNav);
    mo.disconnect();
    window.removeEventListener('locale-changed', onLocale);
  });
}

// ── 启动自动展开抑制（消灭「画板卡默认展开盖输入区」形态；r1 口径不变）──
// 两条腿：a) 同步腿（localStorage 恢复先于本层）；b) 异步腿（restoreTabs 晚至
// 数秒 —— 观察者盯 canvas-open，首次用户交互前出现的一律收起）。
function watchBootAutoOpen() {
  const maybeSuppress = () => {
    if (isLayerTouched()) return;
    if (isCanvasOpen()) {
      logEvent('suppress-boot-open');
      closeCanvas();
      syncCardLayer();
    }
  };
  maybeSuppress();
  const mo = new MutationObserver(maybeSuppress);
  mo.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  unsubs.push(() => mo.disconnect());
}

// ── 激活 / 拆除 ─────────────────────────────────────────────────

export function activateLayout() {
  buildKeys();
  const list = rightList();
  if (list) {
    list.addEventListener('click', (e) => {
      const tgt = /** @type {Element} */ (e.target);
      const btn = /** @type {HTMLElement|null} */ (tgt.closest('[data-face]'));
      if (btn && list.contains(btn)) onKeyClick(/** @type {string} */ (btn.dataset.face));
    });
  }
  watchPressed();
  watchBootAutoOpen();

  // 手机层落位即收起桌面残留的抽屉态（文件浏览器入口在手机层不存在，
  // 侧栏抽屉不应再被恢复出来）。
  if (!isSideBarCollapsed()) setSideBarCollapsed(true);

  // 按下态先算一遍（冷启动恢复页签时按钮态即刻正确）。
  syncPressed();

  // 预热共享模块（空闲时机，点击零延迟；失败静默 —— 页面路径有兜底）。
  if (typeof window.requestIdleCallback === 'function') window.requestIdleCallback(() => warm(), { timeout: 3000 });
  else setTimeout(() => warm(), 1200);

  return function teardownLayout() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    // 自建键随 headerController 的列壳拆除（列壳先拆时此处幂等空转）。
    const l = rightList();
    if (l) l.querySelectorAll('[data-face]').forEach((b) => b.remove());
  };
}
