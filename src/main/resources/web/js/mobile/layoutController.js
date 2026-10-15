// mobile/layoutController.js — r2 规格①：右侧活动栏「默认隐藏＋把手」。
//
// 作者规格（原文语义）：
//   · 默认态：整条活动栏隐藏，仅留一个可交互把手图标 —— 小、贴右缘、不遮挡
//     内容、不占布局（fixed 悬浮，不参与文档流）；
//   · 展开：按住把手向左滑拖出整条 bar（跟手位移，越半程释放即停）；bar 毛玻璃
//     底，悬浮于内容之上（overlay，不占布局列）；
//   · 收回：滑回（把手向右拖），或点击 bar 外任意处；默认隐藏为持久态
//     （🔴 刷新后仍隐藏 —— 激活恒以收起态起步，展开态不落任何存储）。
//   · 与层内既有手势的协调（冲突以本轮三点为准）：r1 ④ 的「长按画板按钮 →
//     多卡视图」随页签形态废除（规格③），长按监听已删净；底缘手势带/横滑
//     属画板卡片层（canvasGestures），与把手拖拽不同表面，零冲突。
//
// bar 键 = r2 导航面（规格②③）：主会话（pop 到 root + 收卡片层）、画板
// （文档卡片层开/收）、项目/图谱/插件/用量/设置（navigateTo —— 归 root 再
// push，栈深实际 ≤2）。按下态严格镜像 navController 的栈顶 + 卡片层状态。
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

/** @type {Array<() => void>} */
let unsubs = [];

// ── 壳构建：bar ＋ 把手（两个独立 fixed 节点，把手恒贴右缘）──────────

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
  document.body.appendChild(bar);
  if (typeof lucide !== 'undefined') createIconsIn(bar);
  return bar;
}

function buildHandle() {
  const handle = document.createElement('button');
  handle.type = 'button';
  handle.id = 'mobile-bar-handle';
  handle.setAttribute('aria-controls', 'mobile-activity-bar');
  handle.setAttribute('aria-expanded', 'false');
  handle.setAttribute('aria-label', tt('展开活动栏', 'Open activity bar'));
  handle.title = tt('活动栏', 'Activity bar');
  handle.innerHTML = '<i data-lucide="chevron-left"></i>';
  document.body.appendChild(handle);
  if (typeof lucide !== 'undefined') createIconsIn(handle);
  return handle;
}

// ── 展开 / 收回（默认收起；展开态不持久 —— 刷新后仍隐藏）────────────

function applyBarState(bar, handle, open) {
  const changed = bar.classList.contains('open') !== open;
  bar.classList.toggle('open', open);
  // 收起态：按钮不可达不可点（inert；位移出屏由 CSS transform 承担）。
  try { /** @type {any} */ (bar).inert = !open; } catch { /* 旧内核无 inert */ }
  bar.setAttribute('aria-hidden', open ? 'false' : 'true');
  handle.setAttribute('aria-expanded', open ? 'true' : 'false');
  handle.setAttribute('aria-label', open ? tt('收起活动栏', 'Close activity bar') : tt('展开活动栏', 'Open activity bar'));
  if (changed) logEvent(open ? 'bar-expand' : 'bar-collapse', { via: 'state' });
}

function bindHandleDrag(bar, handle) {
  /** @type {{id:number, x0:number, base:number, moved:boolean, off:number}|null} */
  let drag = null;
  const isOpen = () => bar.classList.contains('open');
  // 收起位 = 右距 28 + 阴影余量 28（与 mobileLayer.css calc(100% + 56px) 同值）。
  const offDistance = () => bar.getBoundingClientRect().width + 56;

  handle.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    touchLayer();
    drag = {
      id: e.pointerId,
      x0: e.clientX,
      base: isOpen() ? 0 : offDistance(),
      moved: false,
      off: offDistance(),
    };
    bar.classList.add('dragging');
    try { handle.setPointerCapture(e.pointerId); } catch { /* ignore */ }
  });

  handle.addEventListener('pointermove', (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const dx = e.clientX - drag.x0;
    if (Math.abs(dx) > 6) drag.moved = true;
    // 跟手：x ∈ [0, off]；0 = 全出，off = 全收。垂直居中位移（-50%）保持。
    const x = Math.min(drag.off, Math.max(0, drag.base + dx));
    bar.style.transform = `translate(${x}px, -50%)`;
  });

  const finish = (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const dx = e.clientX - drag.x0;
    const d = drag;
    drag = null;
    bar.classList.remove('dragging');
    bar.style.transform = '';
    // 拖拽过半程（或快速轻扫方向）⇒ 判开/收；未拖动（原地点按）⇒ 开关切换。
    const open = d.moved ? (d.base + dx) < d.off / 2 : !isOpen();
    applyBarState(bar, handle, open);
  };
  handle.addEventListener('pointerup', finish);
  handle.addEventListener('pointercancel', finish);

  // 展开态：点击 bar 外任意处收回（不吞事件 —— 外层点击照常生效）。
  const onDocDown = (e) => {
    if (!isOpen()) return;
    if (bar.contains(/** @type {Node} */ (e.target)) || e.target === handle) return;
    touchLayer();
    applyBarState(bar, handle, false);
  };
  document.addEventListener('pointerdown', onDocDown, true);

  return () => document.removeEventListener('pointerdown', onDocDown, true);
}

// ── bar 键 → 导航（规格②③）─────────────────────────────────────────

function onBarClick(face, bar, handle) {
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
  // 导航动作完成后收回 bar（目标已呈现，bar 不遮内容）。
  applyBarState(bar, handle, false);
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
      const btn = bar.querySelector(`[data-face="${b.face}"]`);
      if (btn) btn.title = tt(b.zh, b.en);
    }
    const handle = document.getElementById('mobile-bar-handle');
    if (handle) handle.title = tt('活动栏', 'Activity bar');
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
  const handle = buildHandle();
  bar.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-face]');
    if (btn && bar.contains(btn)) onBarClick(btn.dataset.face, bar, handle);
  });
  const unbindDoc = bindHandleDrag(bar, handle);
  // 🔴 默认隐藏为持久态：激活恒以收起态起步（展开态不落任何存储，刷新后仍隐藏）。
  applyBarState(bar, handle, false);
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
    unbindDoc();
    bar.remove();
    handle.remove();
  };
}
