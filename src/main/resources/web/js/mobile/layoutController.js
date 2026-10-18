// mobile/layoutController.js — r4 规格③：右侧活动栏「双态」。
//
// 作者轮 4 规格（原文语义）：
//   · 展开态 = 紧贴右缘（right:0），**无任何浮动把手图标**（展开后旁边不得再
//     飘「<」类把手）；
//   · 收起态 = 更小更低调的 Apple 风图标（细描边、低透明度、半嵌入边缘）；
//   · 展开/收起交互保留；两态均不压可交互内容（--mobile-bar-gutter 走廊
//     随态适配：展开 58px＝栏宽 48＋间隙 10；收起 30px＝图标 22＋间隙 8）。
//
// r3 → r4 演进：r3 是「常驻恒展开」（r2 把手退场）；r4 在常驻基础上补回
// **收起态**——收/展控制键**集成在 bar 自身左缘内**（.mobile-bar-fold，非
// 外部浮把手，展开态旁边零漂浮元素）；收起态整条 bar 收缩为半嵌右缘的低调
// 图标（该图标即展开钮，命中区=整条收起态 bar）。状态持久化 localStorage
// （nebflow_mobile_bar_collapsed，私有模式 try/catch 静默），缺省展开。
//
// bar 键 = r2 导航面（规格②③）：主会话（pop 到 root + 收卡片层）、画板
// （文档卡片层开/收）、项目/图谱/插件/用量/设置（navigateTo —— 归 root 再
// push，栈深实际 ≤2）。按下态严格镜像 navController 的栈顶 + 卡片层状态。
// bar 不随导航自动收起（键按后 bar 原位，目标面在走廊内呈现）；收起态由
// 用户显式触发、跨导航保持。
//
// 与层内既有手势的协调：长按画板按钮 → 多卡视图已随 r2 废除（监听已删净）；
// 底缘手势带/横滑属画板卡片层（canvasGestures），与 bar 不同表面，零冲突。
//
// 共享组件复用声明：本文件不复制任何桌面逻辑；桌面代码路径零改动。

import { closeCanvas, isCanvasOpen } from '../canvas.js';
import { setSideBarCollapsed, isSideBarCollapsed } from '../activityBar.js';
import { createIconsIn } from '../utils.js';
import {
  navState, navigateTo, goHome, toggleCards, warm,
  logEvent, touchLayer, isLayerTouched, syncCardLayer,
} from './navController.js';

/** bar 键（自上而下）：主会话 → 画板（文档卡片层）→ 项目 → 图谱 → 插件 → 用量 → 设置。 */
const BAR_BUTTONS = [
  { face: 'home',     icon: 'message-circle', zh: '主会话',    en: 'Main Session' },
  { face: 'canvas',   icon: 'layers',         zh: '画板',      en: 'Documents' },
  { face: 'projects', icon: 'folder-kanban',  zh: '项目',      en: 'Projects' },
  { face: 'graph',    icon: 'sparkles',       zh: '图谱',      en: 'Wiki Graph' },
  { face: 'plugins',  icon: 'puzzle',         zh: '插件',      en: 'Plugins' },
  { face: 'usage',    icon: 'bar-chart-3',    zh: 'token 用量', en: 'Usage' },
  { face: 'settings', icon: 'settings',       zh: '设置',      en: 'Settings' },
];

function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || localStorage.getItem('locale') || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

// ── r4 双态：收起/展开（键集成 bar 内，无外部浮把手）─────────────────

const BAR_COLLAPSED_KEY = 'nebflow_mobile_bar_collapsed';

/** @type {boolean} */
let barCollapsed = false;

function readCollapsedPref() {
  try { return localStorage.getItem(BAR_COLLAPSED_KEY) === 'true'; } catch { return false; }
}

function writeCollapsedPref(v) {
  try { localStorage.setItem(BAR_COLLAPSED_KEY, v ? 'true' : 'false'); } catch { /* private mode */ }
}

/** 折叠键图标：展开态显示「收进去」（chevrons-right），收起态显示「展开出来」（chevrons-left）。 */
function renderFoldIcon(fold) {
  fold.innerHTML = `<i data-lucide="${barCollapsed ? 'chevrons-left' : 'chevrons-right'}"></i>`;
  if (typeof lucide !== 'undefined') createIconsIn(fold);
}

function applyBarState() {
  const bar = document.getElementById('mobile-activity-bar');
  if (!bar) return;
  document.body.classList.toggle('mb-bar-collapsed', barCollapsed);
  const fold = /** @type {HTMLElement|null} */ (bar.querySelector('.mobile-bar-fold'));
  if (fold) {
    renderFoldIcon(fold);
    fold.setAttribute('aria-expanded', barCollapsed ? 'false' : 'true');
    fold.title = tt(barCollapsed ? '展开活动栏' : '收起活动栏', barCollapsed ? 'Expand activity bar' : 'Collapse activity bar');
    fold.setAttribute('aria-label', fold.title);
  }
}

function toggleBar() {
  barCollapsed = !barCollapsed;
  writeCollapsedPref(barCollapsed);
  logEvent('bar-toggle', { collapsed: barCollapsed });
  applyBarState();
}

/** @type {Array<() => void>} */
let unsubs = [];

// ── 壳构建：双态 bar（单一 fixed 节点；展开=贴右缘全键列，收起=半嵌低调图标）──

function buildBar() {
  const bar = document.createElement('div');
  bar.id = 'mobile-activity-bar';
  bar.setAttribute('role', 'navigation');
  bar.setAttribute('aria-label', tt('手机活动栏', 'Mobile activity bar'));
  for (const b of BAR_BUTTONS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.id = `mobile-bar-${b.face}`;
    btn.className = 'activity-btn';
    btn.dataset.face = b.face;
    btn.title = tt(b.zh, b.en);
    btn.setAttribute('aria-pressed', 'false');
    btn.innerHTML = `<i data-lucide="${b.icon}"></i>`;
    bar.appendChild(btn);
  }
  // r4 收/展控制键：集成在 bar 左缘内（细描边低调，非外部浮把手 —— 展开
  // 态旁边零漂浮元素）；收起态它是整条 bar 唯一可见图标（即展开钮）。
  const fold = document.createElement('button');
  fold.type = 'button';
  fold.className = 'mobile-bar-fold';
  bar.appendChild(fold);
  document.body.appendChild(bar);
  if (typeof lucide !== 'undefined') createIconsIn(bar);
  return bar;
}

// ── bar 键 → 导航（规格②③；bar 常驻，导航后不收起）──────────────────

function onBarClick(face) {
  touchLayer();
  logEvent('bar-click', { face });
  switch (face) {
    case 'home':
      goHome();
      break;
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
}

// ── 按下态同步（严格镜像 navController 栈顶 + 卡片层）────────────────

function syncPressed() {
  const bar = document.getElementById('mobile-activity-bar');
  if (!bar) return;
  const s = navState();
  const on = {
    home: s.top === 'chat' && !s.cardsLive,
    canvas: s.cardsLive,
    projects: s.top === 'projects',
    graph: s.top === 'graph',
    plugins: s.top === 'plugins',
    usage: s.top === 'usage',
    settings: s.top === 'settings',
  };
  for (const b of BAR_BUTTONS) {
    const btn = bar.querySelector(`[data-face="${b.face}"]`);
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
    const bar = document.getElementById('mobile-activity-bar');
    if (!bar) return;
    for (const b of BAR_BUTTONS) {
      const btn = /** @type {HTMLElement|null} */ (bar.querySelector(`[data-face="${b.face}"]`));
      if (btn) btn.title = tt(b.zh, b.en);
    }
    applyBarState(); // 折叠键标题随语言刷新（图标不变）
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

// ── ③ 附加：启动自动展开抑制（消灭「画板卡默认展开盖输入区」形态）────
// 两条腿，覆盖两条到达路径（r1 口径不变，判据改读 navController 首触标记）：
//   a) 同步腿：localStorage 恢复在 main.js 模块求值内同步发生（先于本层模块
//      注册观察者）—— 激活时若用户未触碰且画板已开，立即收起一次；
//   b) 异步腿：服务端存档合并/冷启动重试（restoreTabs 兜底 openPlugins）可能
//      晚至数秒 —— 观察者持续盯 canvas-open，首次用户交互前出现的一律按
//      「启动自动展开」收起。卡片数据保留（页签数据模型不动），用户随后可经
//      bar「画板」键或再次 pop 文档重现。
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
  const bar = buildBar();
  bar.addEventListener('click', (e) => {
    const tgt = /** @type {Element} */ (e.target);
    // r4 双态：折叠键 / 收起态整条 bar（=展开钮）→ 切态；展开态其余点击走导航。
    if (tgt.closest('.mobile-bar-fold') || barCollapsed) {
      toggleBar();
      return;
    }
    const btn = /** @type {HTMLElement|null} */ (tgt.closest('[data-face]'));
    if (btn && bar.contains(btn)) onBarClick(btn.dataset.face);
  });
  // 恢复持久化态（缺省展开）+ 图标/aria 同步。
  barCollapsed = readCollapsedPref();
  applyBarState();
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
    bar.remove();
  };
}
