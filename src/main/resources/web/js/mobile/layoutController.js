// mobile/layoutController.js — 规格①＋③：右侧竖排活动栏＋左侧内容面板。
//
// 规格①（作者原文语义）：活动栏移右侧竖排，自上而下：
//   主会话 → 项目 → 图谱 → 插件 → token 用量 → 设置；
//   按钮不满屏、垂直居中、两侧留白（iOS 风）。
// 规格④追加：画板（Canvas）成活动栏独立按钮 —— 插在「主会话」之后（两块
//   主-face 结伴置顶；规格①未给画板位次，此为交付注记的显式取舍，作者可后调）。
//
// 规格③：布局＝右侧活动栏＋左侧内容面板；所有内容不再弹窗、直接显示在左侧
//   面板。实现取「单一内容面」模型：
//   · 主会话态   → #main（聊天）即内容面板；
//   · 项目/图谱/插件 → 复用桌面 canvas 页签（openProjectsTab / openWikiGraphTab /
//     openPlugins —— openTab 内部自动 openCanvas），画板以整幅内容面板形态呈现
//     （mobileLayer.css 覆盖抽屉/overlay 几何）；
//   · 用量/设置  → 复用桌面 overlay 节点，CSS 改为填满内容面板（不再是居中弹窗）。
//   弹窗族遗留（删除确认/冲突三选等瞬时对话框）保持桌面节点，仅做手机尺寸适配。
//
// 共享组件复用声明：本文件不复制任何桌面逻辑 —— 页签机、设置面、用量面全部
// 走桌面导出 API（canvas.js / sidebar.js / usageDashboard.js / projectTab.js /
// wikiGraph.js / plugins.js）。桌面侧唯一新增 = index.html 一行注册钩子。
//
// 审计附加发现的消灭（③）：375 宽启动时 restoreTabs 兜底 openPlugins() 会把
// 插件画板卡默认展开盖住输入区（send 不可达）。手机层激活后的启动窗内出现
// canvas-open 且用户尚未触碰交互层 ⇒ 视为「启动自动展开」并立即收起，启动落
// 在主会话。桌面路径不受影响（判定挂在 body.mobile-layer 之下）。

import { openCanvas, closeCanvas, isCanvasOpen } from '../canvas.js';
import { openSettingsPanel, closeSettingsPanel, isSettingsPanelActive } from '../sidebar.js';
import { setSideBarCollapsed, isSideBarCollapsed } from '../activityBar.js';
import { createIconsIn } from '../utils.js';

/** 双语轻取值：手机层自带（不写桌面 locales 文件，桌面文件零改动）。 */
function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem('nebflow_locale') || localStorage.getItem('locale') || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

/** @type {boolean} 记录用户是否已触碰过手机层（启动自动展开抑制只管开机窗）。 */
let userTouched = false;
/** @type {MutationObserver|null} */
let bodyObserver = null;
/** @type {Array<() => void>} */
let unsubs = [];

// ── 活动栏构建（①：右侧竖排、垂直居中、两侧留白）────────────────────

const BAR_BUTTONS = [
  { face: 'home',     icon: 'message-circle', zh: '主会话',    en: 'Main Session' },
  { face: 'canvas',   icon: 'layers',         zh: '画板',      en: 'Canvas' },
  { face: 'projects', icon: 'folder-kanban',  zh: '项目',      en: 'Projects' },
  { face: 'graph',    icon: 'sparkles',       zh: '图谱',      en: 'Wiki Graph' },
  { face: 'plugins',  icon: 'puzzle',         zh: '插件',      en: 'Plugins' },
  { face: 'usage',    icon: 'bar-chart-3',    zh: 'token 用量', en: 'Usage' },
  { face: 'settings', icon: 'settings',       zh: '设置',      en: 'Settings' },
];

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

// ── 共享模块惰性单载（首次点击后预热，之后同步可用）──────────────────

/** @type {Record<string, Promise<any>>} */
const lazyMods = {};
function lazy(key, loader) {
  if (!lazyMods[key]) lazyMods[key] = loader().catch((err) => { delete lazyMods[key]; throw err; });
  return lazyMods[key];
}
const modProjects = () => lazy('projects', () => import('../projectTab.js'));
const modGraph = () => lazy('graph', () => import('../wikiGraph.js'));
const modPlugins = () => lazy('plugins', () => import('../plugins.js'));
const modUsage = () => lazy('usage', () => import('../usageDashboard.js'));

// ── 面板面路由（③：单一内容面）──────────────────────────────────

/** 回到主会话：收掉画板/设置/用量三块内容面（聊天成为内容面板）。 */
async function goHome() {
  if (isCanvasOpen()) closeCanvas();
  if (isSettingsPanelActive()) closeSettingsPanel();
  try { (await modUsage()).closeUsageDashboard(); } catch { /* 模块未载即未开 */ }
}

function onBarClick(face) {
  userTouched = true;
  switch (face) {
    case 'home':
      goHome();
      break;
    case 'canvas':
      // 独立画板按钮：开/关画板（页签原样保留，桌面同语义）。
      if (isCanvasOpen()) closeCanvas();
      else openCanvas();
      break;
    case 'projects':
      modProjects().then((m) => m.openProjectsTab()).catch((e) => console.error('[mobile-layer] projects', e));
      break;
    case 'graph':
      modGraph().then((m) => m.openWikiGraphTab()).catch((e) => console.error('[mobile-layer] graph', e));
      break;
    case 'plugins':
      modPlugins().then((m) => m.openPlugins()).catch((e) => console.error('[mobile-layer] plugins', e));
      break;
    case 'usage':
      modUsage().then((m) => m.openUsageDashboard()).catch((e) => console.error('[mobile-layer] usage', e));
      break;
    case 'settings':
      if (isSettingsPanelActive()) closeSettingsPanel();
      else openSettingsPanel();
      break;
    default:
      break;
  }
}

// ── 按下态同步（按钮 .active 严格镜像「该面即当前内容面板」）──────────

function activeTabId() {
  const el = document.querySelector('#canvas-tab-bar .canvas-tab.active');
  return el ? el.dataset.tabId : null;
}
function usageOpen() {
  const ov = document.getElementById('usage-overlay');
  return !!ov && !ov.hidden;
}
function canvasOpen() { return document.body.classList.contains('canvas-open'); }

function syncActive() {
  const bar = document.getElementById('mobile-activity-bar');
  if (!bar) return;
  const canvasOn = canvasOpen();
  const tab = activeTabId();
  const settingsOn = isSettingsPanelActive();
  const usageOn = usageOpen();
  const on = {
    home: !canvasOn && !settingsOn && !usageOn,
    canvas: canvasOn,
    projects: canvasOn && tab === 'projects',
    graph: canvasOn && tab === 'wiki-graph',
    plugins: canvasOn && tab === 'plugins',
    usage: usageOn,
    settings: settingsOn,
  };
  for (const b of BAR_BUTTONS) {
    const btn = bar.querySelector(`[data-face="${b.face}"]`);
    if (!btn) continue;
    btn.classList.toggle('active', !!on[b.face]);
    btn.setAttribute('aria-pressed', on[b.face] ? 'true' : 'false');
  }
}

function watchActive() {
  // body class（canvas-open）与两个 overlay 的开合属性变化都驱动一次重算。
  bodyObserver = new MutationObserver(() => syncActive());
  bodyObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  const settingsOv = document.getElementById('settings-overlay');
  if (settingsOv) bodyObserver.observe(settingsOv, { attributes: true, attributeFilter: ['class'] });
  const usageOv = document.getElementById('usage-overlay');
  if (usageOv) bodyObserver.observe(usageOv, { attributes: true, attributeFilter: ['hidden'] });
  const onTabSwitched = () => syncActive();
  document.addEventListener('canvas-tab-switched', onTabSwitched);
  document.addEventListener('canvas-tab-closed', onTabSwitched);
  window.addEventListener('locale-changed', () => {
    const bar = document.getElementById('mobile-activity-bar');
    if (!bar) return;
    for (const b of BAR_BUTTONS) {
      const btn = bar.querySelector(`[data-face="${b.face}"]`);
      if (btn) btn.title = tt(b.zh, b.en);
    }
  });
  unsubs.push(() => {
    document.removeEventListener('canvas-tab-switched', onTabSwitched);
    document.removeEventListener('canvas-tab-closed', onTabSwitched);
    if (bodyObserver) { bodyObserver.disconnect(); bodyObserver = null; }
  });
}

// ── ③ 附加：启动自动展开抑制（消灭「画板卡默认展开盖输入区」形态）────
// 无截止时刻、以首次用户交互为界：restoreTabs 服务端合并/冷启动缓存都可能让
// openPlugins/openCanvas 晚至数秒后到达 —— 只要用户尚未触碰手机层，开机后出现
// 的 canvas-open 都按「启动自动展开」收起；用户一旦交互，抑制立即永久失效。
function watchBootAutoOpen() {
  const maybeSuppress = () => {
    if (userTouched) return;
    if (canvasOpen()) {
      closeCanvas(); // 启动兜底 openPlugins/restoreTabs 的自动展开 —— 手机层落主会话
    }
  };
  const mo = new MutationObserver(maybeSuppress);
  mo.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  unsubs.push(() => mo.disconnect());
}

// ── 长按画板按钮 → ④卡片视图（事件解耦：手势控制器监听本事件）────────

function bindLongPress(bar) {
  const canvasBtn = bar.querySelector('[data-face="canvas"]');
  if (!canvasBtn) return;
  let timer = null;
  let fired = false;
  const start = (e) => {
    if (e.button && e.button !== 0) return;
    fired = false;
    timer = setTimeout(() => {
      fired = true;
      userTouched = true;
      canvasBtn.dispatchEvent(new CustomEvent('nf:mobile-canvas-longpress', { bubbles: true }));
    }, 480);
  };
  const cancel = () => { if (timer) { clearTimeout(timer); timer = null; } };
  canvasBtn.addEventListener('pointerdown', start);
  canvasBtn.addEventListener('pointerup', (e) => {
    cancel();
    if (fired) {
      // 长按已消费本次按压 —— 吞掉随后的 click（防开/关画板与卡片视图叠加）。
      e.preventDefault();
      e.stopPropagation();
      const swallow = (ev) => { ev.preventDefault(); ev.stopPropagation(); };
      canvasBtn.addEventListener('click', swallow, { capture: true, once: true });
    }
  }, true);
  canvasBtn.addEventListener('pointerleave', cancel);
  canvasBtn.addEventListener('pointercancel', cancel);
}

// ── 激活 / 拆除 ─────────────────────────────────────────────────

export function activateLayout() {
  const bar = buildBar();
  bar.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-face]');
    if (btn && bar.contains(btn)) onBarClick(btn.dataset.face);
  });
  bindLongPress(bar);
  watchActive();
  watchBootAutoOpen();

  // 手机层落位即收起桌面残留的抽屉态（文件浏览器入口②在手机层不存在，
  // 侧栏抽屉不应再被恢复出来）。
  if (!isSideBarCollapsed()) setSideBarCollapsed(true);

  // 按下态先算一遍（冷启动恢复页签时按钮态即刻正确）。
  syncActive();

  // 预热共享模块（空闲时机，点击零延迟；失败静默 —— 点击路径有兜底）。
  const warm = () => { modProjects(); modGraph(); modPlugins(); modUsage(); };
  if (typeof window.requestIdleCallback === 'function') window.requestIdleCallback(warm, { timeout: 3000 });
  else setTimeout(warm, 1200);

  return function teardownLayout() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    bar.remove();
    userTouched = false;
  };
}
