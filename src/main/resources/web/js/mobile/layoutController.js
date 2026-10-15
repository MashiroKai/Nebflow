// mobile/layoutController.js — r3 规格⑤：右侧活动栏「常驻」（r1 形态回归，把手退场）。
//
// 作者规格（原文语义）：
//   · 右侧活动栏默认展开**常驻**（毛玻璃 blur(24px) saturate(1.15)＋
//     rgba(255,255,255,.55) 保留 —— 即 --glass-blur/--glass-bg token，不换值）；
//   · 废除 r2 的「默认隐藏＋把手」模式：#mobile-bar-handle 不再创建（退场），
//     boot 即展开，展开态恒在（无收起态 ⇒ 无持久化问题）；
//   · 内容面让位：r1 的右缘走廊 --mobile-bar-gutter 随常驻 bar 一并回归
//     （#main margin-right；页面/卡片层/浮层几何同口径，见 mobileLayer.css）。
//
// bar 键 = r2 导航面（规格②③）：主会话（pop 到 root + 收卡片层）、画板
// （文档卡片层开/收）、项目/图谱/插件/用量/设置（navigateTo —— 归 root 再
// push，栈深实际 ≤2）。按下态严格镜像 navController 的栈顶 + 卡片层状态。
// 常驻 bar 不随导航收起（键按后 bar 原位常在，目标面在走廊内呈现）。
//
// 与层内既有手势的协调：长按画板按钮 → 多卡视图已随 r2 废除（监听已删净）；
// 底缘手势带/横滑属画板卡片层（canvasGestures），与常驻 bar 不同表面，零冲突。
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

// ── 壳构建：常驻 bar（单一 fixed 节点，boot 即展开，恒在）────────────

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
      const btn = bar.querySelector(`[data-face="${b.face}"]`);
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
    const btn = e.target.closest('[data-face]');
    if (btn && bar.contains(btn)) onBarClick(btn.dataset.face);
  });
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
