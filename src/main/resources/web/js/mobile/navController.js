// mobile/navController.js — dock 批（作者令 2026-10-29 13:31/13:36/13:38）：
// 小红书式五栏底 dock ＋「每个节目都是独立界面」的全屏页导航。
//
// ── 导航模型（dock 批重写；中键改版批 2026-10-29 23:36 修订）─────────
//   · 底 dock = 根页切换器：五栏 `消息 / 联系人 / 对话 / canvas / 工作台`
//     （工作台批 2026-10-30 作者令：设置从顶层导航降级为工作台成员——
//     第五栏 设置 → 工作台；DOCK_ORDER 钉死）。dock 常驻（聊天根上也显示，
//     激活中键）；点 tab = 切换根页（互斥替换，非堆叠）。
//   · 中键改版批（作者 23:36 逐字「中间那个对话 就是进入我跟你的这个输入框
//     …最左侧的消息就是 跟联系人的对话消息」）：第三栏对话语义 = **chat
//     home 键**（点 = 详情全清 + 回聊天根输入框；不是页面路由，无 section），
//     形态 = 小红书中心键（sapphire 主色大圆、高出基线、图标 = agent 头像，
//     无头像回落气泡字形）；消息键 = 跟联系人的对话消息（好友会话列表自原
//     「对话」面迁入，气泡图标随迁）。
//   · 栈模型 = `stack = [home, ...details]`：stack[0] ∈ 'chat' ∪ 五栏根；
//     详情页（任务/搜索/定时/心跳/后台/智能体/手机接入/项目/图谱/插件/用量/
//     设置/会话窗）push 在当前根之上；返回 pop 一层；根页「<」= 回聊天根。
//   · 总原则（作者 13:38 逐字「各节目都是独立界面 不再像目前这样跟其他界面
//     重叠」）：页面 = 全屏不透明 section（#mobile-pages 容器互斥 .live）；
//     浮层族全部退役 —— v3 双液态下拉列（headerController/layoutController/
//     floatController）、任务浮层 morph（mh-tpanel 呈现位）、画板 bottom
//     sheet（#canvas-panel 悬浮卡）、二级液态 morph 面板全部改为**收编进
//     独立页面**（overlay/pane 锚点原位归还惯例不变）。
//   · canvas = 独立整页（作者 13:36 判）：dock tab 进全屏 canvas 页，
//     非弹窗非悬浮；页内卡片式切换多张 canvas 卡（卡片列表 chip 条 +
//     ‹ › 步进 + 内容区横滑切卡，环式）；桌面 canvas 零变化（pane re-parent
//     进页面体，#canvas-panel 手机层永不显示）。
//   · 栈状态 = 会话内存（模块变量）；刷新后归位聊天根（口径同前批：刷新 ≙
//     App 冷启动）。
//
// ── 面收编（沿用 paneAnchors / overlayAnchors 两套锚点惯例）─────────
//   · 面板页（项目/图谱/插件）：桌面画板 pane re-parent 进页面体（不变）。
//   · overlay 面（设置/用量/搜索/定时/心跳/后台/智能体/手机接入/任务）：
//     面节点 re-parent 进页面体；overlay 载体原地保留作状态源（外部关闭
//     对账 = watchOverlayClose 扩展到全页面注册表）。
//   · 会话窗（#fm-chat-overlay）：窗体节点 re-parent 进「会话」详情页；
//     桌面 closeChat 摘除 overlay ⇒ 对账弃节点 + 弹页。
//
// ── App 打包留缝点 ──────────────────────────────────────────────────
//   · 路由名稳定：'chat' | 五栏根 | 详情页 id（PAGES 注册表）；
//   · 通用导航 API：pushPage / popPage / goHome / dockTo；
//   · 状态广播：document 事件 `nf:mobile-nav`（detail = { stack, top, home, action }）；
//   · 只读探针：window.__nfMobileNav = { stack, top, home, version }。
//
// 共享组件复用声明：本文件不复制任何桌面逻辑 —— 页面内容全部走桌面导出
// API / 桌面已渲染节点收编；桌面侧零改动。

import {
  getTabPane, setActiveTab, closeTab,
} from '../canvas.js';
import { key } from '../branding.js';
import { openSettingsPanel, closeSettingsPanel, isSettingsPanelActive } from '../sidebar.js';
import { createIconsIn } from '../utils.js';
import { ensureSocialPage, syncSocialFaces } from './socialFaces.js';
import { ensureTaskPanel, closeTaskPanel } from './taskPanel.js';
import { wikiGraphEnabled } from '../featureFlags.js';
import { agentAvatarDataUrl, onAgentAvatarChange } from '../agentProfile.js';
import { avatarImgNode } from '../avatarRender.js';

/**
 * 底 dock 五栏（作者 2026-10-29 令逐字顺序；顺序钉死 = spec 断言面）。
 * 中键改版批（同日 23:36）：第三栏 = 'chat' —— 对话键是 **chat home 键**
 * （点 = 回主对话输入框），不是页面路由：不在 PAGES、ensureContainer 不为
 * 它建 section。 */
export const DOCK_ORDER = ['messages', 'contacts', 'chat', 'canvas', 'workbench'];

/** chat home 键定义。icon = 回落字形（作者令：无 agent 头像时用「目前中间
 *  对话按钮那个图标」= message-circle 气泡；有头像 = 圆头像图）。 */
const DOCK_CHAT_DEF = { id: 'chat', icon: 'message-circle', zh: '对话', en: 'Chats' };

/** dock 键定义解析：chat home 键走 CHAT_DEF，其余查 PAGES。 */
function dockDef(id) {
  return id === 'chat' ? DOCK_CHAT_DEF : PAGE_BY_ID.get(id);
}

/** 工作台分组行（工作台根页体；dock 放不下的功能面的统一入口）。
 *  工作台批（作者令 2026-10-30「设置并入工作台一览」）：末行 = 设置——
 *  设置自顶层导航降级为工作台成员，点行进设置页（push 详情）。 */
const HUB_ROWS = [
  { page: 'projects',   icon: 'folder-kanban',   zh: '项目',      en: 'Projects' },
  { page: 'plugins',    icon: 'puzzle',          zh: '插件',      en: 'Plugins' },
  { page: 'usage',      icon: 'bar-chart-3',     zh: 'token 用量', en: 'Usage' },
  { page: 'graph',      icon: 'sparkles',        zh: '图谱',      en: 'Wiki Graph', flagGated: true },
  { page: 'reminder',   icon: 'alarm-clock',     zh: '定时任务',  en: 'Scheduled' },
  { page: 'daemon',     icon: 'heart-pulse',     zh: '心跳',      en: 'Daemons' },
  { page: 'background', icon: 'square-terminal', zh: '后台任务',  en: 'Background' },
  { page: 'settings',   icon: 'settings',        zh: '设置',      en: 'Settings' },
];

/** 页面注册表。dock: true = 底栏根页；其余 = push 详情页。route id 稳定。
 *  （chat home 键不在此表 —— 见 DOCK_ORDER/CHAT_DEF。）中键改版批：消息键
 *  图标 = message-circle 气泡（作者令 23:36「图标换位」——气泡自原中键迁入；
 *  消息页内容 = 跟联系人的对话消息，面迁移见 socialFaces.js）。 */
export const PAGES = [
  { id: 'messages',      dock: true, icon: 'message-circle', zh: '消息',   en: 'Messages' },
  { id: 'contacts',      dock: true, icon: 'contact-round',  zh: '联系人', en: 'Contacts' },
  { id: 'canvas',        dock: true, icon: 'layers',         zh: 'canvas', en: 'canvas' },
  { id: 'workbench',     dock: true, icon: 'layout-grid',    zh: '工作台', en: 'Workspace' },
  { id: 'settings',      tabId: null,      icon: 'settings',  zh: '设置',   en: 'Settings' },
  { id: 'projects', tabId: 'projects', icon: 'folder-kanban',   zh: '项目',     en: 'Projects' },
  { id: 'graph',    tabId: 'wiki-graph', icon: 'sparkles',      zh: '图谱',     en: 'Wiki Graph' },
  { id: 'plugins',  tabId: 'plugins',  icon: 'puzzle',          zh: '插件',     en: 'Plugins' },
  { id: 'usage',    tabId: null,       icon: 'bar-chart-3',     zh: 'token 用量', en: 'Usage' },
  { id: 'tasks',    tabId: null,       icon: 'list-todo',       zh: '任务',     en: 'Tasks' },
  { id: 'search',   tabId: null,       icon: 'search',          zh: '搜索',     en: 'Search' },
  { id: 'reminder', tabId: null,       icon: 'alarm-clock',     zh: '定时任务', en: 'Scheduled' },
  { id: 'daemon',   tabId: null,       icon: 'heart-pulse',     zh: '心跳',     en: 'Daemons' },
  { id: 'background', tabId: null,     icon: 'square-terminal', zh: '后台任务', en: 'Background' },
  { id: 'profile',  tabId: null,       icon: 'bot',             zh: '智能体',   en: 'Agent' },
  { id: 'social',   tabId: null,       icon: 'smartphone',      zh: '手机接入', en: 'Phone Access' },
  { id: 'conversation', tabId: null,   icon: 'message-circle',  zh: '会话',     en: 'Conversation' },
];
const PAGE_BY_ID = new Map(PAGES.map((p) => [p.id, p]));
/** 面板页 id 集（项目/图谱/插件——承载桌面画板 pane 的页）。栈顶 = 面板页时
 *  canvas-open 上升沿/切卡事件是面板开页签的副产物，自动弹 canvas 页会把刚
 *  push 的面板页顶掉（工作台误路由批 2026-10-30 作者令实证：点工作台行进
 *  项目 ⇒ 弹出 canvas）。 */
const PANEL_PAGE_IDS = new Set(PAGES.filter((p) => p.tabId).map((p) => p.id));

// ── 共享模块惰性单载（首次进入页面后同步可用；与 r1 同款机制）────────
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
const modAgentProfile = () => lazy('agentProfile', () => import('../agentProfile.js'));

/** 空闲预热（首次点击零延迟；失败静默 —— 页面路径有兜底）。 */
export function warm() { modProjects(); modGraph(); modPlugins(); modUsage(); }

function tt(zh, en) {
  // 语言源 = i18n.js 同源键（key('locale')，branding.js 命名空间派生）。
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

// ── 层事件流水（QA 探针 window.__nfMobileLayerEvents，只读）──────────
const __nfEvents = [];
export function logEvent(kind, extra = {}) {
  __nfEvents.push({ t: Date.now(), kind, ...extra });
}

// ── 首触标记（启动自动展开抑制的判据）────────────────────────────────
let layerTouched = false;
export function touchLayer() {
  if (!layerTouched) { layerTouched = true; logEvent('layer-first-touch'); }
}
export function isLayerTouched() { return layerTouched; }

// ── 模块状态 ─────────────────────────────────────────────────────────
/** @type {string[]} 页面栈；stack[0] = home（'chat' 或 dock 根），其余 = 详情。 */
let stack = ['chat'];
/** @type {HTMLElement|null} */
let container = null;
/** @type {Map<string, HTMLElement>} route id → section */
const sections = new Map();
/** @type {Map<string, HTMLElement>} 页面 hub 容器 */
const hubBodies = new Map();
/** @type {{pane: Element, parent: Node, next: Node|null}[]} pane 归还锚点。 */
const paneAnchors = [];
/** @type {HTMLElement|null} 底 dock（dock 批：常驻根页切换器）。 */
let dockEl = null;
/** @type {MutationObserver|null} */
let contentObserver = null;
/** @type {MutationObserver|null} 会话窗收编观察者。 */
let chatModalMo = null;
/** @type {{modal: Element}|null} 会话窗收编槽。 */
let chatAdopted = null;
/** @type {MutationObserver[]} */
let overlayObservers = [];
/** @type {Array<() => void>} */
let unsubs = [];

const backLabel = () => tt('返回', 'Back');

// ── 页面壳构建 ───────────────────────────────────────────────────────

function buildSection(def) {
  const section = document.createElement('section');
  section.className = 'mobile-page';
  section.dataset.page = def.id;
  section.setAttribute('aria-label', tt(def.zh, def.en));

  const body = document.createElement('div');
  body.className = 'mobile-page-body';
  // 手机端顶栏批（2026-10-31 作者令①）：canvas 页顶部「canvas」胶囊退役——
  // 页头整体不建（安全区垫高随 .mf-strip 走，mobileLayer.css），文档标签条
  // 上移就位顶带。其余页页头照旧。
  if (def.id === 'canvas') {
    section.append(body);
  } else {
    const head = document.createElement('div');
    head.className = 'mobile-page-header';
    const title = document.createElement('span');
    title.className = 'mobile-page-title';
    title.tabIndex = -1; // push 后聚焦（读屏从头播报页名）
    title.textContent = tt(def.zh, def.en);
    const spacer = document.createElement('span');
    spacer.className = 'mobile-page-head-spacer';
    spacer.setAttribute('aria-hidden', 'true');
    // Canvas 改造批②（2026-10-31 作者令）：canvas/工作台/联系人/消息 = dock 一级
    // 面板，禁左上角返回钮——返回语义由底 dock 承担（dock 根无「<」；详情页
    // 保留「<」= pop 一层）。spacer 保留占位，标题胶囊居中不变。
    if (!def.dock) {
      const back = document.createElement('button');
      back.type = 'button';
      back.className = 'mobile-back-btn';
      back.title = backLabel();
      back.setAttribute('aria-label', backLabel());
      back.innerHTML = '<i data-lucide="chevron-left"></i>';
      back.addEventListener('click', () => backOut());
      head.append(back, title, spacer);
    } else {
      head.append(title, spacer);
    }
    if (typeof lucide !== 'undefined') createIconsIn(head);
    section.append(head, body);
  }
  if (def.id === 'workbench') buildHub(body);
  if (def.id === 'canvas') buildCanvasPage(body);
  return section;
}

/** 工作台根页的「工作台」分组行（dock 五栏放不下的功能面统一入口；push 详情；
 *  末行 = 设置——设置自顶层导航降级为工作台成员，工作台批 2026-10-30）。 */
function buildHubRow(r) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'mf-hub-row';
  row.dataset.hubPage = r.page;
  row.innerHTML = `<i data-lucide="${r.icon}"></i><span class="mf-hub-row-label"></span><i data-lucide="chevron-right" class="mf-hub-chev"></i>`;
  /** @type {HTMLElement|null} */ (row.querySelector('.mf-hub-row-label')).textContent = tt(r.zh, r.en);
  row.addEventListener('click', (e) => {
    // 打开的面板（桌面 outside-close 监听 document 冒泡）会被本记点击的
    // 继续冒泡误关 —— 入口键一律停冒泡（dock 批 2026-10-29 实测修复）。
    e.stopPropagation();
    touchLayer();
    logEvent('hub-open', { page: r.page });
    pushPage(r.page);
  });
  return row;
}

function buildHub(body) {
  const hub = document.createElement('div');
  hub.className = 'mf-hub';
  const label = document.createElement('div');
  label.className = 'mf-hub-label';
  label.textContent = tt('工作台', 'Workspace');
  hub.appendChild(label);
  const rows = document.createElement('div');
  rows.className = 'mf-hub-rows';
  hub.appendChild(rows);
  body.appendChild(hub);
  hubBodies.set('workbench', hub);
  syncHubGate();
}

/** flag 门控行（graph）随配置到达重对账：ensureContainer 在 boot 早期建全部
 *  页面，首帧 configData 可能尚未写入 parsedConfig —— 若只按建行当时的 flag
 *  判定，graph 行会缺席到下次刷新（p9 单跑/全量均实测同点 flaky）。
 *  renderPages 每次翻页对账一次：行集与 flag 期望不一致才重建（一致零成本）。 */
function syncHubGate() {
  const hub = hubBodies.get('workbench');
  const rows = hub?.querySelector('.mf-hub-rows');
  if (!rows) return;
  const want = HUB_ROWS.filter((r) => !r.flagGated || wikiGraphEnabled()).map((r) => r.page);
  const have = [...rows.querySelectorAll('.mf-hub-row')].map((el) => (/** @type {HTMLElement} */ (el)).dataset.hubPage);
  if (want.length === have.length && want.every((p, i) => p === have[i])) return;
  rows.replaceChildren();
  for (const r of HUB_ROWS) {
    if (r.flagGated && !wikiGraphEnabled()) continue; // graphseal 同 flag（featureFlags 惯例）
    rows.appendChild(buildHubRow(r));
  }
  if (typeof lucide !== 'undefined') createIconsIn(rows);
}

function ensureContainer() {
  if (container) return container;
  container = document.createElement('div');
  container.id = 'mobile-pages';
  for (const def of PAGES) {
    const section = buildSection(def);
    sections.set(def.id, section);
    container.appendChild(section);
  }
  document.body.appendChild(container);
  const onLocale = () => {
    for (const def of PAGES) {
      const s = sections.get(def.id);
      if (!s) continue;
      const t = s.querySelector('.mobile-page-title');
      if (t) t.textContent = tt(def.zh, def.en);
      const b = /** @type {HTMLElement|null} */ (s.querySelector('.mobile-back-btn'));
      if (b) { b.title = backLabel(); b.setAttribute('aria-label', backLabel()); }
    }
    const hub = hubBodies.get('workbench');
    const hubLabel = hub?.querySelector('.mf-hub-label');
    if (hubLabel) hubLabel.textContent = tt('工作台', 'Workspace');
    const rows = hub?.querySelectorAll('.mf-hub-row');
    if (rows) {
      for (const row of rows) {
        const r = HUB_ROWS.find((x) => x.page === (/** @type {HTMLElement} */ (row)).dataset.hubPage);
        if (r) (/** @type {HTMLElement|null} */ (row.querySelector('.mf-hub-row-label'))).textContent = tt(r.zh, r.en);
      }
    }
    syncCanvasPage();
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));
  return container;
}

function bodyOf(pageId) {
  return sections.get(pageId)?.querySelector('.mobile-page-body') || null;
}

// ── 面板 pane re-parent（桌面画板 pane → 项目/图谱/插件页）──────────

/** 把面板页签 pane 移入页面体（幂等）。移动 = appendChild（监听随节点走）。 */
function adoptPane(pageId) {
  const def = PAGE_BY_ID.get(pageId);
  const pane = def?.tabId ? getTabPane(def.tabId) : null;
  const body = bodyOf(pageId);
  if (!pane || !body || pane.parentElement === body) return;
  paneAnchors.push({ pane, parent: pane.parentElement, next: pane.nextSibling });
  logEvent('pane-adopt', { page: pageId, tab: def.tabId });
  body.appendChild(pane);
}

// ── overlay 内容面收编（设置/用量惯例扩展到全部页面面）───────────────
// overlay 载体节点原地保留，只作状态载体（.on/.open/.hidden/[hidden] 与外部
// 关闭对账照旧）；可见内容面 re-parent 进页面体。桌面重渲/销毁兼容：面写入
// 都是收编节点内部的子树写入，与面的落点无关。
/** @type {Map<string, {face: Element, parent: Node, next: Node|null}>} page id → 面锚点 */
const overlayAnchors = new Map();
/** 页面 → overlay 内容面（收编对象；conversation 页走动态会话窗槽，不在此表）。 */
const overlayFaceByPage = {
  settings: '#settings-modal',
  usage: '#usage-dashboard',
  search: '#search-modal',
  reminder: '#reminder-panel',
  daemon: '#daemon-panel',
  background: '#bg-dropdown',
  profile: '#memory-modal',
  social: '#social-modal',
  tasks: '#mobile-task-panel',
};

/** 把 overlay 内容面移入页面体（幂等；锚点照 paneAnchors 惯例记录）。 */
function adoptOverlayFace(pageId) {
  const sel = overlayFaceByPage[pageId];
  const face = sel ? document.querySelector(sel) : null;
  const body = bodyOf(pageId);
  if (!face || !body || face.parentElement === body) return;
  overlayAnchors.set(pageId, { face, parent: face.parentElement, next: face.nextSibling });
  logEvent('pane-adopt', { page: pageId, face: sel });
  body.appendChild(face);
}

/** 页面体 → overlay 原位归还（出栈/拆层；面不在页面体时空操作）。 */
function restoreOverlayFace(pageId) {
  const a = overlayAnchors.get(pageId);
  if (!a) return;
  overlayAnchors.delete(pageId);
  const body = bodyOf(pageId);
  if (!a.face || !body || a.face.parentElement !== body || !a.parent || !a.parent.isConnected) return;
  try {
    a.parent.insertBefore(a.face, a.next);
    logEvent('pane-restore', { page: pageId, face: overlayFaceByPage[pageId] });
  } catch { /* 树已变：放弃归还，避免二次破坏 */ }
}

/** 栈 ↔ overlay 面对账（汇合点 = renderPages：在栈即收编，出栈即归还）。 */
function syncOverlayFaces() {
  for (const id of Object.keys(overlayFaceByPage)) {
    if (stack.includes(id)) adoptOverlayFace(id);
    else restoreOverlayFace(id);
  }
}

/** 页面 → 桌面模块入口（pane 的创建/渲染全部走桌面导出 API，零复制）。 */
const openFnByPage = {
  projects: () => modProjects().then((m) => m.openProjectsTab()),
  graph: () => modGraph().then((m) => m.openWikiGraphTab()),
  plugins: () => modPlugins().then((m) => m.openPlugins()),
};

/** 桌面按钮同一入口的合面腿（桌面键在手机层不可见，click 语义 = 同一处理函数）。 */
function clickDesktopBtn(id) { (/** @type {HTMLElement|null} */ (document.getElementById(id)))?.click(); }

async function ensurePanelContent(pageId) {
  const fn = openFnByPage[pageId];
  if (fn) {
    try {
      await fn();
    } catch (e) {
      console.error('[mobile-layer] page open failed:', pageId, e);
    }
    adoptPane(pageId);
    return;
  }
  // 页面内容臂（按页面 id 分派；全部幂等）。
  switch (pageId) {
    case 'messages':
    case 'contacts':
      ensureSocialPage(pageId, bodyOf(pageId));
      break;
    case 'settings':
      try { openSettingsPanel(); } catch (e) { console.error('[mobile-layer] settings', e); }
      break;
    case 'usage':
      modUsage()
        .then((m) => {
          m.openUsageDashboard();
          ensureOverlayReconcile('usage', '#usage-overlay', (el) => !!el.hidden, ['hidden']);
          syncOverlayFaces(); // overlay 首开此刻才建出 —— 建好即收编
        })
        .catch((e) => console.error('[mobile-layer] usage', e));
      break;
    case 'search':
      try {
        const { openSearchModal } = await import('../chatSearch.js');
        openSearchModal();
      } catch (e) { console.error('[mobile-layer] search', e); }
      break;
    case 'reminder':
      if (!document.querySelector('#reminder-panel.open')) clickDesktopBtn('reminder-btn');
      break;
    case 'daemon':
      if (!document.querySelector('#daemon-panel.open')) clickDesktopBtn('daemon-btn');
      break;
    case 'background':
      if (document.querySelector('#bg-dropdown.hidden')) clickDesktopBtn('bg-indicator');
      // dock 批：手机层 bgagent 键属退场桌面簇 —— 载体开时补跑子智能体列表
      // 渲染（桌面键开面分支同款 fetch + render；幂等，收编列表随节点走）。
      import('../main/bgAgents.js').then((m) => {
        m.fetchActiveAgentsOnOpen();
        m.renderBgAgentDropdown();
      }).catch(() => { /* 未载即无行 */ });
      break;
    case 'profile':
      try {
        const ap = await modAgentProfile();
        if (ap.openProfilePanel) ap.openProfilePanel();
      } catch (e) { console.error('[mobile-layer] profile', e); }
      break;
    case 'social':
      if (!document.querySelector('#social-overlay.on')) clickDesktopBtn('social-btn');
      break;
    case 'tasks':
      ensureTaskPanel();
      // 面板惰性建出 —— 建好即挂关闭对账 + 收编（X 关闭 → hidden → 弹栈）。
      ensureOverlayReconcile('tasks', '#mobile-task-panel', (el) => !!el.hidden, ['hidden']);
      syncOverlayFaces();
      break;
    default:
      break;
  }
}

/** 画板内容区监视：面板 pane 出现即收编进页面（文档 pane 由 canvas 页管）。 */
function watchCanvasContent() {
  const content = document.getElementById('canvas-content');
  if (!content) return;
  const tabIdToPage = new Map(PAGES.filter((p) => p.tabId).map((p) => [p.tabId, p.id]));
  const sweep = () => {
    for (const pane of content.querySelectorAll(':scope > .canvas-tab-pane')) {
      const pageId = tabIdToPage.get((/** @type {HTMLElement} */ (pane)).dataset.tabId);
      if (pageId) adoptPane(pageId);
    }
    syncCanvasPage();
  };
  contentObserver = new MutationObserver(sweep);
  contentObserver.observe(content, { childList: true });
  sweep();
  unsubs.push(() => { contentObserver?.disconnect(); contentObserver = null; });
}

// ══════════════════════════════════════════════════════════════════════
// canvas 独立页（作者令 2026-10-29 13:36：手机端 canvas 不做悬浮窗，独立
// 整页；页内卡片式呈现/切换多张 canvas 卡 —— 卡片列表 chip 条 + ‹ › 步进 +
// 内容区横滑切卡。桌面 canvas 行为零变化：pane re-parent，#canvas-panel
// 手机层永不显示（bottom sheet 退役）。
// ══════════════════════════════════════════════════════════════════════

/** 面板页签 id 集（文档页签 = 页签栏全集 − 本集）。 */
const PANEL_TAB_IDS = new Set(PAGES.filter((p) => p.tabId).map((p) => p.tabId));

const SWIPE_AXIS_PX = 8;
const SWIPE_SWITCH_PX = 48;

/** @type {{strip: HTMLElement, toolbar: HTMLElement, host: HTMLElement, empty: HTMLElement}|null} */
let canvasUI = null;
/** @type {{pane: Element, parent: Node, next: Node|null}|null} 活动文档 pane 收编槽（单槽）。 */
let canvasDocAnchor = null;

/** 文档页签全集（不按 pane 落点过滤 —— 活动 pane 可能已收编进页面体）。
 *  @returns {{id: string, label: string}[]} */
export function docTabs() {
  const bar = document.getElementById('canvas-tab-bar');
  if (!bar) return [];
  const out = [];
  for (const el of bar.querySelectorAll('.canvas-tab')) {
    const id = (/** @type {HTMLElement} */ (el)).dataset.tabId;
    if (!id || PANEL_TAB_IDS.has(id)) continue;
    out.push({ id, label: el.querySelector('.canvas-tab-label')?.textContent || id });
  }
  return out;
}

export function hasDocTabs() { return docTabs().length > 0; }

export function activeDocId() {
  const el = /** @type {HTMLElement|null} */ (document.querySelector('#canvas-tab-bar .canvas-tab.active'));
  const id = el ? el.dataset.tabId : null;
  return id && !PANEL_TAB_IDS.has(id) ? id : null;
}

/** 文档卡邻位切换（环绕；单卡不换）。@param {number} delta +1 下一张 / -1 上一张 */
export function switchDocCard(delta) {
  const docs = docTabs();
  const id = activeDocId();
  const idx = docs.findIndex((d) => d.id === id);
  if (docs.length < 2 || idx < 0) return;
  const nb = docs[(idx + delta + docs.length) % docs.length];
  if (nb && nb.id !== id) setActiveTab(nb.id);
}

/** 活动文档 pane 收编进 canvas 页内容区（单槽锚点；换卡即归旧纳新）。 */
function adoptActiveDoc() {
  const id = activeDocId();
  const pane = id ? getTabPane(id) : null;
  const host = canvasUI?.host;
  if (!host) return;
  if (canvasDocAnchor && (!pane || canvasDocAnchor.pane !== pane)) {
    // 换卡/关卡：旧 pane 原位归还画板内容区。
    const a = canvasDocAnchor;
    canvasDocAnchor = null;
    if (a.pane.isConnected && a.parent && a.parent.isConnected) {
      try { a.parent.insertBefore(a.pane, a.next); } catch { /* 树已变：放弃 */ }
    }
  }
  if (pane && pane.parentElement !== host) {
    canvasDocAnchor = { pane, parent: pane.parentElement, next: pane.nextSibling };
    logEvent('canvas-page-adopt', { tab: id });
    host.appendChild(pane);
  }
}

/** canvas 页同步：chip 条重建（签名闸）+ 工具条态 + pane 收编 + 空态。 */
function syncCanvasPage() {
  if (!canvasUI) return;
  const docs = docTabs();
  const activeId = activeDocId();
  const sig = docs.map((d) => d.id).join('|') + '#' + (activeId || '');
  if (canvasUI.strip.dataset.sig !== sig) {
    canvasUI.strip.dataset.sig = sig;
    canvasUI.strip.innerHTML = '';
    for (const d of docs) {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'mf-strip-chip' + (d.id === activeId ? ' on' : '');
      chip.dataset.tabId = d.id;
      chip.textContent = d.label;
      chip.title = d.label;
      chip.addEventListener('click', () => {
        logEvent('card-switch', { via: 'strip', tab: d.id });
        setActiveTab(d.id);
      });
      canvasUI.strip.appendChild(chip);
    }
    // 活动 chip 滚入视野（切卡后一键可见）。
    canvasUI.strip.querySelector('.mf-strip-chip.on')?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' });
  }
  syncStripFade();
  const idx = docs.findIndex((d) => d.id === activeId);
  const cnt = /** @type {HTMLElement|null} */ (canvasUI.toolbar.querySelector('.mf-cv-count'));
  if (cnt) cnt.textContent = docs.length > 1 && idx >= 0 ? `${idx + 1}/${docs.length}` : '';
  // Canvas 改造批②⑤（2026-10-31 作者令）：工具条 = 左叉号 + 计数 + 右堆叠视角
  // 钮；独立窗/步进钮退役（独立窗出口桌面保留；切卡走 chip/横滑/堆叠视角）。
  const stackBtn = /** @type {HTMLButtonElement|null} */ (canvasUI.toolbar.querySelector('.mf-cv-stack'));
  if (stackBtn) stackBtn.disabled = docs.length === 0;
  adoptActiveDoc();
  canvasUI.empty.hidden = docs.length > 0;
  canvasUI.strip.hidden = docs.length === 0;
  canvasUI.toolbar.hidden = docs.length === 0;
  canvasUI.host.hidden = docs.length === 0;
  if (stackOpen) syncStackView();
}

/** 标签条横向边缘渐隐门控（顶栏批② 2026-10-31）：内容横向溢出才挂 .fade
 *  （左右两端 28px mask 渐隐，chat.css .nf-wl-clip 同配方双侧版）；不溢出
 *  摘 mask 全清晰。chip 重建/横滚/视口变化三路都对账（签名闸零成本直过）。 */
function syncStripFade() {
  const strip = canvasUI?.strip;
  if (!strip) return;
  strip.classList.toggle('fade', strip.scrollWidth > strip.clientWidth + 1);
}

/** 堆叠视角开关态（批②⑤：手机端「堆叠态」= 全卡堆叠列表面，顶部显示标签数）。 */
let stackOpen = false;
/** @type {{view: HTMLElement, list: HTMLElement, count: HTMLElement}|null} */
let stackUI = null;

/** 堆叠视角面构建（一次）：顶部「N 张文档」计数行 + 全卡堆叠列表（活动卡高亮；
 *  点卡换入并收面；✕/下滑/Esc 收面）。毛玻璃浮层面，锚 canvas 页内。 */
function buildStackView(wrap) {
  const view = document.createElement('div');
  view.className = 'mf-stack-view';
  view.hidden = true;
  const head = document.createElement('div');
  head.className = 'mf-stack-head';
  const count = document.createElement('span');
  count.className = 'mf-stack-count';
  const x = document.createElement('button');
  x.type = 'button';
  x.className = 'mf-stack-x g2-fab';
  x.title = tt('关闭堆叠视角', 'Close stacked view');
  x.setAttribute('aria-label', x.title);
  x.innerHTML = '<i data-lucide="x"></i>';
  x.addEventListener('click', () => toggleStackView(false));
  head.append(count, x);
  const list = document.createElement('div');
  list.className = 'mf-stack-list';
  view.append(head, list);
  wrap.appendChild(view);
  // 下滑收面（浮面自身纵滑手势；横滑无义不接管）。
  let dy0 = null;
  view.addEventListener('pointerdown', (e) => { dy0 = e.clientY; });
  view.addEventListener('pointerup', (e) => {
    if (dy0 === null) return;
    const dy = e.clientY - dy0;
    dy0 = null;
    if (dy > 56) toggleStackView(false);
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && stackOpen) toggleStackView(false);
  });
  stackUI = { view, list, count };
  return view;
}

/** 堆叠视角开合 + 列表同步（open 省略 = 翻转）。
 *  顶栏批④（2026-10-31 作者令「展开动画与电脑端类似」）：开合不再瞬时
 *  display 切换——开 = 摘 hidden → 回流 → .open（浮面淡入 + 卡列自堆叠位
 *  逐张弹簧升起，CSS .open/.mf-stack-card stagger 承载）；收 = 摘 .open 卡列
 *  即时回落（.closing 清 stagger 递延，不拖尾）→ 淡出后 hidden。 */
function toggleStackView(open) {
  if (!stackUI) return;
  const next = open === undefined ? !stackOpen : !!open;
  if (next === stackOpen) return;
  stackOpen = next;
  logEvent('canvas-stack-' + (next ? 'open' : 'close'));
  const view = stackUI.view;
  if (next) {
    view.hidden = false;
    view.classList.remove('closing');
    syncStackView();                   // 先建列（卡生于收合落位态，:not(.open) 初始样式）
    void view.offsetWidth;             // 回流：.open 自初始态（opacity 0/卡落位）起播
    view.classList.add('open');        // 后开闸：stagger 从落位态逐张归位（建列在闸前 = 中段帧可取证）
  } else {
    view.classList.remove('open');
    view.classList.add('closing');     // 收面零递延：卡列即时回落不逐张拖尾
    setTimeout(() => {
      if (!stackOpen) { view.hidden = true; view.classList.remove('closing'); }
    }, 200);
  }
}

/** 堆叠列表重建（签名闸同 chip 条纪律）：活动卡高亮，点卡 = 换入 + 收面。 */
function syncStackView() {
  if (!stackUI || !stackOpen) return;
  const docs = docTabs();
  const activeId = activeDocId();
  stackUI.count.textContent = docs.length ? `${docs.length} 张文档` : '';
  const sig = docs.map((d) => d.id).join('|') + '#' + (activeId || '');
  if (stackUI.list.dataset.sig === sig) {
    stackUI.list.querySelectorAll('.mf-stack-card').forEach((el) => {
      el.classList.toggle('on', (/** @type {HTMLElement} */ (el)).dataset.tabId === activeId);
    });
    return;
  }
  stackUI.list.dataset.sig = sig;
  stackUI.list.innerHTML = '';
  docs.forEach((d, i) => {
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'mf-stack-card' + (d.id === activeId ? ' on' : '');
    card.dataset.tabId = d.id;
    const num = document.createElement('span');
    num.className = 'mf-stack-num';
    num.textContent = String(i + 1);
    const label = document.createElement('span');
    label.className = 'mf-stack-label';
    label.textContent = d.label;
    card.append(num, label);
    card.addEventListener('click', () => {
      logEvent('card-switch', { via: 'stack-view', tab: d.id });
      if (d.id !== activeDocId()) setActiveTab(d.id);
      toggleStackView(false);
    });
    stackUI.list.appendChild(card);
  });
}

/** canvas 页内容臂构建（一次；chip 条 + 工具条 + 文档区 + 空态 + 堆叠视角）。
 *  Canvas 改造批②⑤（2026-10-31 作者令）：工具条换代 = 左叉号（液态玻璃悬浮
 *  钮）+ 中部计数 + 右堆叠视角钮（.g2-fab 复用液体玻璃 v2 材质，零新造）。
 *  退役：‹ › 步进（chip/横滑/堆叠视角三条换卡腿已足）、独立窗钮（批⑤）。 */
function buildCanvasPage(body) {
  const wrap = document.createElement('div');
  wrap.className = 'mf-canvas-page';
  const strip = document.createElement('div');
  strip.className = 'mf-strip';
  strip.setAttribute('role', 'tablist');
  strip.setAttribute('aria-label', tt('文档卡片', 'Document cards'));

  const toolbar = document.createElement('div');
  toolbar.className = 'mf-cv-toolbar';
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'mf-cv-close g2-fab';
  close.title = tt('关闭文档', 'Close document');
  close.setAttribute('aria-label', tt('关闭文档', 'Close document'));
  close.innerHTML = '<i data-lucide="x"></i>';
  close.addEventListener('click', () => closeCard());
  const count = document.createElement('span');
  count.className = 'mf-cv-count';
  const stack = document.createElement('button');
  stack.type = 'button';
  stack.className = 'mf-cv-stack g2-fab';
  stack.title = tt('堆叠视角', 'Stacked view');
  stack.setAttribute('aria-label', tt('堆叠视角', 'Stacked view'));
  stack.innerHTML = '<i data-lucide="layers"></i>';
  stack.addEventListener('click', () => { logEvent('canvas-stack-toggle'); toggleStackView(); });
  toolbar.append(close, count, stack);

  const host = document.createElement('div');
  host.className = 'mf-cv-host';
  bindDocSwipe(host);
  const empty = document.createElement('div');
  empty.className = 'mf-cv-empty';
  const eIcon = document.createElement('i');
  eIcon.setAttribute('data-lucide', 'layers');
  const eTitle = document.createElement('div');
  eTitle.className = 'mf-cv-empty-title';
  eTitle.textContent = tt('画板暂无文档卡片', 'No document cards yet');
  const eSub = document.createElement('div');
  eSub.className = 'mf-cv-empty-sub';
  eSub.textContent = tt('在聊天里打开文件或文档后会出现在这里', 'Documents opened from chat appear here');
  empty.append(eIcon, eTitle, eSub);
  const stackView = buildStackView(wrap);
  wrap.append(strip, toolbar, host, empty);
  body.appendChild(wrap);
  canvasUI = { strip, toolbar, host, empty };
  // 顶栏批②：标签条渐隐门控的横滚/视口两路对账（chip 重建路在 syncCanvasPage）。
  strip.addEventListener('scroll', syncStripFade, { passive: true });
  window.addEventListener('resize', syncStripFade);
  if (typeof lucide !== 'undefined') createIconsIn(wrap);
  void stackView;
}

/** 文档区横滑切卡（轴锁 8px / 阈 48px；环式；纵滚零劫持 —— 卡头手势同参数）。
 *  Canvas 改造批③（2026-10-31 作者令「标签页手机端左右滑动，两边露圆环相邻
 *  页 = 桌面上下滚动的横屏变体」）：横拖跟手位移 + 拖向侧露圆环（邻卡环露
 *  指示，进深随拖距 0→1）+ 松手判定（过阈换卡滑出 / 不过阈弹簧回位）。
 *  圆环 = 玻璃描边半露圆（CSS .mf-swipe-ring），只在横轴锁后出现，松手即退。 */
function bindDocSwipe(host) {
  /** @type {{id: number, x0: number, y0: number, dx: number, dy: number, axis: string|null}|null} */
  let g = null;
  /** @type {HTMLElement|null} 当前收编 pane（跟手位移对象；换卡由 adoptActiveDoc 归位）。 */
  let paneEl = null;
  /** @type {HTMLElement|null} */
  let ringL = null;
  /** @type {HTMLElement|null} */
  let ringR = null;
  const ensureChrome = () => {
    if (!ringL) {
      ringL = document.createElement('div');
      ringL.className = 'mf-swipe-ring left';
      ringL.setAttribute('aria-hidden', 'true');
      ringR = document.createElement('div');
      ringR.className = 'mf-swipe-ring right';
      ringR.setAttribute('aria-hidden', 'true');
      host.append(ringL, ringR);
    }
  };
  const setRing = (side, k) => {
    const el = side < 0 ? ringL : ringR;
    if (!el) return;
    const v = Math.max(0, Math.min(1, k));
    el.style.opacity = v <= 0 ? '' : String(0.25 + 0.75 * v);
    el.style.transform = `translateY(-50%) scale(${0.72 + 0.28 * v})`;
  };
  host.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    if (/** @type {Element} */ (e.target).closest('button, input, textarea, select, a')) return;
    g = { id: e.pointerId, x0: e.clientX, y0: e.clientY, dx: 0, dy: 0, axis: null };
    paneEl = /** @type {HTMLElement|null} */ (host.querySelector(':scope > .canvas-tab-pane'));
    try { host.setPointerCapture(e.pointerId); } catch (_) {}
  });
  host.addEventListener('pointermove', (e) => {
    if (!g || e.pointerId !== g.id) return;
    g.dx = e.clientX - g.x0;
    g.dy = e.clientY - g.y0;
    if (!g.axis && (Math.abs(g.dx) > SWIPE_AXIS_PX || Math.abs(g.dy) > SWIPE_AXIS_PX)) {
      g.axis = Math.abs(g.dx) > Math.abs(g.dy) ? 'x' : 'y';
      if (g.axis === 'x') ensureChrome();
    }
    if (g.axis === 'x') {
      const docs = docTabs();
      if (paneEl && docs.length > 1) {
        paneEl.style.transition = 'none';
        paneEl.style.transform = `translate3d(${g.dx}px,0,0)`;
      }
      setRing(-1, docs.length > 1 && g.dx > 0 ? g.dx / SWIPE_SWITCH_PX : 0);
      setRing(1, docs.length > 1 && g.dx < 0 ? -g.dx / SWIPE_SWITCH_PX : 0);
    }
  });
  const clearRings = () => {
    if (ringL) { ringL.style.opacity = ''; ringL.style.transform = ''; }
    if (ringR) { ringR.style.opacity = ''; ringR.style.transform = ''; }
  };
  const finish = (e) => {
    if (!g || e.pointerId !== g.id) return;
    const { dx, axis } = g;
    g = null;
    if (axis !== 'x') { paneEl = null; return; }
    const docs = docTabs();
    const hit = Math.abs(dx) > SWIPE_SWITCH_PX && docs.length > 1 && !!activeDocId();
    if (hit) {
      // 过阈：即刻换卡（adoptActiveDoc 归位旧 pane、纳新 pane——松手即切是
      // 既有契约，spec T3 同步断言；跟手位移已发生在拖动段）。
      logEvent('card-switch', { via: 'page-swipe', dir: dx < 0 ? 1 : -1 });
      switchDocCard(dx < 0 ? 1 : -1);
      if (paneEl) { paneEl.style.transition = ''; paneEl.style.transform = ''; paneEl.style.opacity = ''; paneEl = null; }
      clearRings();
    } else {
      // 不过阈：弹簧回位（220ms 后清内联，避免遗留过渡影响后续纵滚）。
      if (paneEl) {
        paneEl.style.transition = 'transform 220ms cubic-bezier(0.16, 1, 0.3, 1)';
        paneEl.style.transform = 'translate3d(0,0,0)';
        const el = paneEl;
        paneEl = null;
        setTimeout(() => { el.style.transition = ''; el.style.transform = ''; }, 240);
      } else paneEl = null;
      clearRings();
    }
  };
  host.addEventListener('pointerup', finish);
  host.addEventListener('pointercancel', finish);
}

/** 关闭当前文档（工具条 X）：关单卡；关后落相邻文档卡，无文档卡则保持空态。 */
export function closeCard() {
  const id = activeDocId();
  if (!id) return;
  const docs = docTabs();
  const idx = docs.findIndex((d) => d.id === id);
  logEvent('card-close', { tab: id, via: 'canvas-page' });
  closeTab(id);
  const rest = docTabs().filter((d) => d.id !== id);
  if (rest.length) {
    const next = rest[Math.min(Math.max(idx, 0), rest.length - 1)];
    if (next && next.id !== activeDocId()) setActiveTab(next.id);
  }
  syncCanvasPage();
}

/** 主会话键：回聊天根（dock 根的「<」）；canvas 页离开 = 面归还、文档保留。 */
export function goHome() {
  while (stack.length > 1) closePageFace(stack.pop());
  if (stack[0] !== 'chat') {
    closePageFace(stack[0]);
    stack[0] = 'chat';
  }
  renderPages();
  emitNav('home');
}

// ── 页面栈（home + details）─────────────────────────────────────────

export function navState() {
  return {
    stack: [...stack],
    top: stack[stack.length - 1],
    home: stack[0],
    cardsLive: false, // dock 批：bottom sheet 退役；字段保留兼容旧探针读者
  };
}

function emitNav(action) {
  document.dispatchEvent(new CustomEvent('nf:mobile-nav', { detail: { ...navState(), action } }));
}

function renderPages() {
  const top = stack[stack.length - 1];
  for (const [id, section] of sections) {
    const live = top === id;
    section.classList.toggle('live', live);
    if (live) (/** @type {HTMLElement|null} */ (section.querySelector('.mobile-page-title')))?.focus?.();
  }
  document.body.classList.toggle('mf-page-open', top !== 'chat');
  syncDockActive();
  // 徽标可能晚于 buildDock 建出（封存态剖面好友面不初始化）——翻页时重查补绑。
  syncDockBadges();
  // hub 门控行（graph 随 wikiGraph flag）与 configData 到达时序重对账（p9 flaky 实证）。
  syncHubGate();
  syncOverlayFaces();
  syncSocialFaces((id) => stack.includes(id));
  if (top === 'canvas') syncCanvasPage();
}

/** dock 根页「<」/详情页「<」统一出栈腿：详情 pop 一层；根页回聊天。 */
function backOut() {
  if (stack.length > 1) popPage();
  else if (stack[0] !== 'chat') goHome();
}

/** 页面面关闭（出栈/换根时归位桌面开/关态）。 */
function closePageFace(pageId) {
  switch (pageId) {
    case 'settings':
      try { if (isSettingsPanelActive()) closeSettingsPanel(); } catch { /* 模块态异常不阻断 */ }
      break;
    case 'usage':
      modUsage().then((m) => m.closeUsageDashboard()).catch(() => { /* 未载即未开 */ });
      break;
    case 'search':
      document.getElementById('search-overlay')?.classList.remove('on');
      break;
    case 'reminder':
      document.querySelector('#reminder-panel')?.classList.remove('open');
      break;
    case 'daemon':
      document.querySelector('#daemon-panel')?.classList.remove('open');
      break;
    case 'background':
      document.querySelector('#bg-dropdown')?.classList.add('hidden');
      break;
    case 'profile':
      import('../memory.js').then((m) => m.closeMemoryModal?.()).catch(() => { /* 未载即未开 */ });
      break;
    case 'social':
      document.getElementById('social-overlay')?.classList.remove('on');
      break;
    case 'tasks':
      closeTaskPanel(true);
      break;
    default:
      break; // messages/contacts/canvas/panels/conversation：面态无需翻转
  }
}

/**
 * 通用 push（详情页/壳/深链留缝点）：dock 根 id 走 dockTo；'chat' = 中键
 * home；目标已在栈中先摘除再压顶（摘除位移的 overlay 面同步关闭）。
 * @param {string} pageId
 */
export function pushPage(pageId) {
  if (pageId === 'chat') return dockHome();
  if (!PAGE_BY_ID.has(pageId)) return;
  if (PAGE_BY_ID.get(pageId).dock) return dockTo(pageId);
  ensureContainer();
  const displaced = [];
  stack = stack.filter((p) => (p === pageId ? (displaced.push(p), false) : true));
  for (const d of displaced) closePageFace(d);
  stack.push(pageId);
  logEvent('nav-push', { page: pageId, depth: stack.length });
  void ensurePanelContent(pageId);
  renderPages();
  emitNav('push');
}

/** pop 一层（返回钮契约）。 */
export function popPage() {
  if (stack.length <= 1) return;
  const pageId = stack.pop();
  logEvent('nav-pop', { page: pageId });
  closePageFace(pageId);
  renderPages();
  emitNav('pop');
}

/** pop 到 home（聊天根或当前 dock 根；详情全清）。 */
export function popToRoot() {
  if (stack.length <= 1) return;
  while (stack.length > 1) closePageFace(stack.pop());
  renderPages();
  emitNav('pop-to-root');
}

/** pop 到指定页（注入式返回钮用：被弹页可能不在栈顶）。 */
export function popToPage(pageId) {
  const i = stack.lastIndexOf(pageId);
  if (i === -1) return;
  while (stack.length - 1 > i) closePageFace(stack.pop());
  renderPages();
  emitNav('pop');
}

/**
 * 中键 = 对话 home 键（作者令 2026-10-29 23:36「中间那个对话 就是进入我跟你的
 * 这个输入框」）：详情全清 + 回聊天根（输入框）；已在聊天根 = noop。
 */
function dockHome() {
  ensureContainer();
  if (stack[0] === 'chat' && stack.length === 1) { emitNav('dock-noop'); return; }
  logEvent('dock-switch', { page: 'chat' });
  goHome();
}

/**
 * 底 dock 切换根页：详情全清 + 根替换（互斥）。同根再点 = 清详情回根。
 * chat home 键（中键）改走 dockHome。
 * @param {string} pageId dock 根 id
 */
export function dockTo(pageId) {
  if (pageId === 'chat') return dockHome();
  if (!PAGE_BY_ID.get(pageId)?.dock) return;
  ensureContainer();
  if (stack[0] === pageId && stack.length === 1) { emitNav('dock-noop'); return; }
  while (stack.length > 1) closePageFace(stack.pop());
  if (stack[0] !== pageId) {
    if (stack[0] !== 'chat') closePageFace(stack[0]);
    stack[0] = pageId;
    logEvent('dock-switch', { page: pageId });
  }
  void ensurePanelContent(pageId);
  renderPages();
  emitNav('dock');
}

/** UI 内统一入口：dock 根走 dockTo，详情走 pushPage；'chat' = 中键 home。 */
export function navigateTo(pageId) {
  if (pageId === 'chat') return dockHome();
  if (!PAGE_BY_ID.has(pageId)) return;
  if (PAGE_BY_ID.get(pageId).dock) return dockTo(pageId);
  if (stack[stack.length - 1] === pageId) { emitNav('push-noop'); return; }
  pushPage(pageId);
}

// ── overlay 面外部关闭对账（watchOverlayClose 扩展到全页面注册表）────

/** @type {HTMLElement[]} 注入的返回钮（拆层移除，桌面零残留）。 */
const injectedBacks = [];

function ensureInjectedBack(hostSel, pageId) {
  const host = document.querySelector(hostSel);
  if (!host || host.querySelector('.mobile-back-injected')) return;
  const back = document.createElement('button');
  back.type = 'button';
  back.className = 'mobile-back-btn mobile-back-injected';
  back.dataset.page = pageId;
  back.title = backLabel();
  back.setAttribute('aria-label', backLabel());
  back.innerHTML = '<i data-lucide="chevron-left"></i>';
  back.addEventListener('click', () => {
    if (stack[stack.length - 1] === pageId) popPage();
    else popToPage(pageId);
  });
  host.insertBefore(back, host.firstChild);
  injectedBacks.push(back);
  if (typeof lucide !== 'undefined') createIconsIn(back);
}

/** 外部关闭对账注册表：页面 ←→ overlay 状态载体（桌面关闭路径 ⇄ 栈同步弹层）。 */
const CLOSE_WATCH = [
  { page: 'settings', sel: '#settings-overlay', off: (el) => !el.classList.contains('on'), attr: ['class'] },
  { page: 'search', sel: '#search-overlay', off: (el) => !el.classList.contains('on'), attr: ['class'] },
  { page: 'reminder', sel: '#reminder-panel', off: (el) => !el.classList.contains('open'), attr: ['class'] },
  { page: 'daemon', sel: '#daemon-panel', off: (el) => !el.classList.contains('open'), attr: ['class'] },
  { page: 'background', sel: '#bg-dropdown', off: (el) => el.classList.contains('hidden'), attr: ['class'] },
  { page: 'profile', sel: '#memory-overlay', off: (el) => !el.classList.contains('on'), attr: ['class'] },
  { page: 'social', sel: '#social-overlay', off: (el) => !el.classList.contains('on'), attr: ['class'] },
];

/** overlay 载体建出后才可挂观察者的页面（usage 面板首开建；taskPanel 惰性建）。 */
const overlayReconcileBound = new Map();
function ensureOverlayReconcile(pageId, sel, off, attr) {
  if (overlayReconcileBound.get(pageId)) return;
  const ov = document.querySelector(sel);
  if (!ov) return;
  overlayReconcileBound.set(pageId, true);
  bindReconcile(pageId, ov, off, attr);
}

function bindReconcile(pageId, ov, off, attr) {
  const reconcile = () => {
    const top = stack[stack.length - 1];
    if (top === pageId && off(ov)) {
      stack.pop();
      renderPages();
      emitNav('pop-external');
    }
  };
  const mo = new MutationObserver(reconcile);
  mo.observe(ov, { attributes: true, attributeFilter: attr });
  overlayObservers.push(mo);
}

function watchOverlayClose() {
  for (const w of CLOSE_WATCH) {
    const ov = document.querySelector(w.sel);
    if (ov) bindReconcile(w.page, ov, w.off, w.attr);
  }
  unsubs.push(() => {
    for (const mo of overlayObservers.splice(0)) mo.disconnect();
  });
  // 头像腿（桌面 handler 直开合并面板）：memory-overlay 被外部置 .on 且本层
  // 不在 profile 页 ⇒ pushPage 收编进页面（浮层形态不落地）。与 closeWatch
  // 同源观察（close 腿弹栈 / open 腿进页，一对镜像）。
  const memOv = document.getElementById('memory-overlay');
  if (memOv) {
    let onPrev = memOv.classList.contains('on');
    const mo = new MutationObserver(() => {
      const on = memOv.classList.contains('on');
      const rose = on && !onPrev;
      onPrev = on;
      if (rose && stack[stack.length - 1] !== 'profile' && !stack.includes('profile')) {
        logEvent('profile-open-adopt', {});
        pushPage('profile');
      }
    });
    mo.observe(memOv, { attributes: true, attributeFilter: ['class'] });
    overlayObservers.push(mo);
  }
}

// ── 会话窗收编（对话列表行 → 「会话」详情页）────────────────────────
// 桌面 renderChatModal 每次整建 #fm-chat-overlay（remove + create）；窗体
// .fm-modal re-parent 进 conversation 页体；closeChat remove overlay ⇒ 对账
// 弃收编节点（桌面整建语义下复用无意义）+ 弹页。

function watchChatModal() {
  chatModalMo = new MutationObserver(() => {
    const ov = document.getElementById('fm-chat-overlay');
    const modal = ov?.querySelector('.fm-modal') || null;
    if (modal && chatAdopted?.modal !== modal) {
      // 新窗建出：弃旧槽 + 收编新窗。
      // 🔴 串窗根因修复（设备平级批 2026-10-31，截图②实锤）：旧收编窗被
      // re-parent **出** overlay 进了页体 ⇒ 桌面 renderChatModal 整建时摘除的
      // 只是空 overlay 壳，**旧窗体留在页体内**；此处若只弃引用不摘节点，
      // 新窗 append 后页体里就是上下两个对话窗（各带各的输入条）。故弃槽
      // 必须连同旧窗节点一起摘（节点已不在 DOM 时 remove() 为空操作）。
      chatAdopted?.modal?.remove();
      chatAdopted = null;
      const body = bodyOf('conversation');
      if (!body) return;
      logEvent('chat-adopt', {});
      chatAdopted = { modal };
      body.appendChild(modal);
      if (stack[stack.length - 1] !== 'conversation') pushPage('conversation');
    } else if (!ov && chatAdopted) {
      // 桌面关窗：弃收编节点（其 overlay 已整体摘除）+ 弹页。
      chatAdopted.modal.remove();
      chatAdopted = null;
      if (stack[stack.length - 1] === 'conversation') popPage();
    }
  });
  chatModalMo.observe(document.body, { childList: true, subtree: true });
  unsubs.push(() => { chatModalMo?.disconnect(); chatModalMo = null; chatAdopted = null; });
}

// ── 底 dock（五栏根页切换器；作者令 2026-10-29 小红书样式基准）───────
// 白底固定底栏、图标在上 + 文字标签在下、五等分、active 态可辨（克制形态：
// 强调色 icon/label）、未读红点徽标镜像桌面既有徽标读数。
// 中键改版批（同日 23:36）：中键 = 小红书中心键形态 —— sapphire 主色实心大圆、
// 高出两侧常规 tab 基线，图标 = 给 agent 设置的头像（圆裁 + 主色细环；无头像
// 回落气泡字形），点 = 回主对话输入框（chat home）。

/** @type {Map<string, HTMLElement>} dock id → 键 */
const dockKeys = new Map();
/** @type {MutationObserver|null} 徽标镜像观察者。 */
let badgeMo = null;

function buildDock() {
  if (dockEl) return dockEl;
  dockEl = document.createElement('nav');
  dockEl.id = 'mobile-dock';
  dockEl.setAttribute('role', 'tablist');
  dockEl.setAttribute('aria-label', tt('主导航', 'Main navigation'));
  const DEFS = DOCK_ORDER.map(dockDef);
  for (const def of DEFS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'dock-key';
    btn.dataset.dock = def.id;
    btn.setAttribute('role', 'tab');
    btn.setAttribute('aria-selected', 'false');
    const badge = document.createElement('span');
    badge.className = 'dock-badge';
    badge.hidden = true;
    const label = document.createElement('span');
    label.className = 'dock-label';
    label.textContent = tt(def.zh, def.en);
    if (def.id === 'chat') {
      // 中键（作者令 23:36）：中心键容器 —— 图标位由 paintDockCenterAvatar
      // 按「agent 头像 / 气泡回落」两态填装（形态见 mobileLayer.css 中键块）。
      btn.classList.add('dock-key-center');
      const center = document.createElement('span');
      center.className = 'dock-center';
      btn.append(center, label, badge);
    } else {
      const icon = document.createElement('span');
      icon.className = 'dock-ic';
      icon.innerHTML = `<i data-lucide="${def.icon}"></i>`;
      btn.append(icon, label, badge);
    }
    btn.addEventListener('click', (e) => {
      // 同 hub 行：桌面面 outside-close 监听 document 冒泡，入口键停冒泡防误关。
      e.stopPropagation();
      touchLayer();
      logEvent('dock-click', { page: def.id });
      if (def.id === 'chat') dockHome();
      else dockTo(def.id);
    });
    dockEl.appendChild(btn);
    dockKeys.set(def.id, btn);
  }
  document.body.appendChild(dockEl);
  paintDockCenterAvatar(); // 先装字形/头像再统一 createIcons（<i data-lucide> 替换腿）
  if (typeof lucide !== 'undefined') createIconsIn(dockEl);
  // 头像落点汇合订阅（本机上传/跨端收敛/会话切换都经 agentProfile.paintSlot）。
  unsubs.push(onAgentAvatarChange(paintDockCenterAvatar));
  const onLocale = () => {
    for (const def of DEFS) {
      const k = dockKeys.get(def.id);
      if (k) (/** @type {HTMLElement|null} */ (k.querySelector('.dock-label'))).textContent = tt(def.zh, def.en);
    }
    if (dockEl) dockEl.setAttribute('aria-label', tt('主导航', 'Main navigation'));
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));
  watchDockBadges();
  return dockEl;
}

/** 中键图标两态（作者令 23:36「图标应该用给agent设置的头像」）：读链 = 会话头
 *  同一本地条目（agentProfile.agentAvatarDataUrl，跨端 sha 收敛同键），节点走
 *  avatarRender 解码复用池；无头像回落气泡字形（= 原中键图标，作者逐字「就是
 *  目前中间对话按钮那个图标」）。 */
function paintDockCenterAvatar() {
  const holder = dockKeys.get('chat')?.querySelector('.dock-center');
  if (!holder) return;
  const dataUrl = agentAvatarDataUrl();
  const img = dataUrl ? avatarImgNode(dataUrl) : null;
  if (img) {
    img.className = 'dock-center-img';
    img.draggable = false;
    img.alt = '';
    holder.replaceChildren(img);
    holder.classList.add('has-avatar');
    return;
  }
  holder.classList.remove('has-avatar');
  if (holder.querySelector('svg')) return; // 字形已就位（lucide 已替换，幂等零重建）
  holder.innerHTML = '<i data-lucide="message-circle"></i>';
  if (typeof lucide !== 'undefined') createIconsIn(holder);
}

/** 徽标镜像：桌面徽标读数（#messages-badge/#contacts-badge，手机层按钮不可见
 *  但 WS 计数流照写）→ dock 徽标。零新轮询、零第二判据。
 *
 *  数字角标（作者令 2026-10-30 08:29 逐字「就像微信一样就行了，有新消息在消息
 *  按钮的右上角加数字角标就行」）：消息键 = **数字角标**，读数与文案逐字取桌面
 *  `#messages-badge`（messages.js `updateBadge` → `setActivityBadge` 已归一
 *  「>99 ⇒ 99+」+ 空串/0 ⇒ hidden），手机层零换算零第二实现。联系人键口径不变
 *  （布尔红点 —— 该键语义 = 好友待处理请求，非「新消息」，作者令只针对消息键）。
 *
 *  🔴 写回必须**幂等**（同值不写）：`watchDockBadges` 挂的是 body 子树
 *  childList 观察器，而 `textContent =` 无论值是否相同都会重建子节点 ——
 *  无守卫的赋值 = 自触发无限循环（本轮实测：D3 3 分钟超时、页面假死）。 */
function applyDockBadges() {
  const msgs = document.getElementById('messages-badge');
  const cts = document.getElementById('contacts-badge');
  const setBadge = (dockId, on, text = '') => {
    const b = /** @type {HTMLElement|null} */ (dockKeys.get(dockId)?.querySelector('.dock-badge'));
    if (!b) return;
    const want = on ? text : '';
    if (b.hidden !== !on) b.hidden = !on;
    if (b.textContent !== want) b.textContent = want;
    b.classList.toggle('is-count', on && text !== ''); // 数字角标形态 vs 纯红点
  };
  const msgOn = !!msgs && !msgs.hidden && msgs.textContent !== '';
  setBadge('messages', msgOn, msgOn ? String(msgs.textContent) : '');
  setBadge('contacts', !!cts && !cts.hidden && cts.textContent !== '');
}

/** 已观察的徽标节点（徽标可能晚于 buildDock 才建出 —— 封存态剖面里好友面
 *  不初始化，D3 判据实测：build 时查无节点 ⇒ 观察从未绑定，晚写的徽标读数
 *  不触发镜像）。观察者挂 body 子树 childList 兜底捕捉晚建节点 + 已绑节点的
 *  读数变化，回调统一走 bind+apply。 */
const badgeWatched = new Set();
function syncDockBadges() {
  if (!badgeMo) return;
  for (const sel of ['messages-badge', 'contacts-badge']) {
    const el = document.getElementById(sel);
    if (el && !badgeWatched.has(el)) {
      badgeMo.observe(el, { attributes: true, attributeFilter: ['hidden'], childList: true, characterData: true, subtree: true });
      badgeWatched.add(el);
    }
  }
  applyDockBadges();
}

function watchDockBadges() {
  badgeMo = new MutationObserver(() => syncDockBadges());
  badgeWatched.clear();
  badgeMo.observe(document.body, { childList: true, subtree: true });
  syncDockBadges();
  unsubs.push(() => { badgeMo?.disconnect(); badgeMo = null; badgeWatched.clear(); });
}

function syncDockActive() {
  const home = stack[0];
  for (const [id, btn] of dockKeys) {
    const on = home === id;
    btn.classList.toggle('active', on);
    btn.setAttribute('aria-selected', on ? 'true' : 'false');
  }
}

// ── 画板事件 → canvas 页同步（canvas-open 拦截：桌面 openCanvas ⇒ 进页）─

function watchCanvasEvents() {
  // 桌面开画板/切卡（聊天文件卡等路径）⇒ 手机层进 canvas 独立页（bottom sheet
  // 退役）。三条闸：①canvas-open 在场；②启动抑制窗 3s（boot 恢复/兜底路径
  // restoreTabs→openPlugins 先于/紧随本层把 canvas-open 置真 —— 那是恢复不是
  // 用户开卡，刷新归聊天根契约不被拽页）；③已不在 canvas 页（页内切换不抢）。
  // 触发源 = 画板 open 类的 **false→true 迁移沿** + canvas-tab-switched
  // （已开画板时点文件卡 = openCanvas 幂等无类变化，靠切卡事件补触发）。
  let bootedAt = Date.now();
  const clearPending = () => {
    try { (/** @type {any} */ (window)).__nfPendingCanvasOpen = 0; } catch { /* ignore */ }
  };
  // 显式收养（无启动抑制闸）：nf:canvas-open-intent = 用户点击 Pop/文件卡的即时
  // 意图（canvas.js Pop 重开腿派发）——点击不可能是 boot 恢复，故不受 3s 窗约束；
  // 「canvas-open 已真」的老会话（restoreTabs 恢复后腿）也只有这条路能自动弹页。
  const adoptNow = (why) => {
    if (!document.body.classList.contains('canvas-open')) return;
    if (stack[stack.length - 1] === 'canvas') return;
    // 工作台误路由修复（作者令 2026-10-30「点工作台弹出的是 canvas」）：项目/
    // 图谱/插件面板页在栈顶时，canvas-open 上升沿是面板页开页签的副产物——
    // 面板页自己承载 pane，自动弹 canvas 页会把刚 push 的面板详情顶掉（canvas
    // 页只呈现文档卡，不呈现面板 pane）。文档卡路径（Pop/文件卡）栈顶恒非面板页。
    if (PANEL_PAGE_IDS.has(stack[stack.length - 1])) return;
    clearPending();
    logEvent('canvas-open-adopt', { why });
    dockTo('canvas');
  };
  const maybeAdopt = (why) => {
    if (bootedAt && Date.now() - bootedAt <= 3000) return;
    adoptNow(why);
  };
  const onIntent = () => adoptNow('open-intent');
  document.addEventListener('nf:canvas-open-intent', onIntent);
  // 点击早于本层挂载的竞态补放（canvas.js Pop 重开腿记旗）：慢机冷启时用户在
  // navController 挂载前点完 Pop 图片——open 沿与切卡事件都已错过，无回放则
  // 「点了不弹、手动开才弹」。旗 = 显式用户开卡意图（boot 恢复不记旗），不受
  // 上方 3s 启动抑制闸约束； freshness 30s + canvas-open 在场双判据，陈旧旗/
  // 用户已手动关面板都不拽页。
  try {
    const pending = (/** @type {any} */ (window)).__nfPendingCanvasOpen;
    if (pending && Date.now() - pending < 30000 && document.body.classList.contains('canvas-open')) {
      clearPending();
      if (stack[stack.length - 1] !== 'canvas') {
        logEvent('canvas-open-adopt', { why: 'pending-replay' });
        dockTo('canvas');
      }
    }
  } catch { /* ignore */ }
  const onSwitch = () => { syncCanvasPage(); maybeAdopt('tab-switched'); };
  document.addEventListener('canvas-tab-switched', onSwitch);
  document.addEventListener('canvas-tab-closed', () => { syncCanvasPage(); maybeAdopt('tab-closed'); });
  document.addEventListener('canvas-independent-changed', onSwitch);
  // 联系人页「手机接入」行 / 消息页聚合行（socialFaces 事件环外发腿）。
  document.addEventListener('nf:mobile-social-entry', () => pushPage('social'));
  document.addEventListener('nf:mobile-goto', (e) => {
    const page = (/** @type {CustomEvent<{page?: string}>} */ (e)).detail?.page;
    if (page && PAGE_BY_ID.get(page)?.dock) dockTo(page);
  });
  let canvasOpenPrev = document.body.classList.contains('canvas-open');
  const mo = new MutationObserver(() => {
    const open = document.body.classList.contains('canvas-open');
    const rose = open && !canvasOpenPrev;
    canvasOpenPrev = open;
    if (rose) maybeAdopt('open-edge');
  });
  mo.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  unsubs.push(() => {
    document.removeEventListener('canvas-tab-switched', onSwitch);
    document.removeEventListener('canvas-tab-closed', onSwitch);
    document.removeEventListener('canvas-independent-changed', onSwitch);
    document.removeEventListener('nf:canvas-open-intent', onIntent);
    mo.disconnect();
  });
}

// ── 激活 / 拆除 ─────────────────────────────────────────────────────

export function activateNav() {
  (/** @type {any} */ (window)).__nfMobileLayerEvents = __nfEvents;
  ensureContainer();
  buildDock();
  watchCanvasContent();
  watchOverlayClose();
  watchCanvasEvents();
  watchChatModal();
  // 激活即收编启动恢复已建成的面板 pane（同步路径；异步路径走 watchCanvasContent）。
  for (const def of PAGES) if (def.tabId) adoptPane(def.id);
  renderPages();
  // 只读探针（App 壳/深链留缝点）。
  (/** @type {any} */ (window)).__nfMobileNav = {
    get stack() { return navState().stack; },
    get top() { return navState().top; },
    get home() { return navState().home; },
    get dock() { return [...DOCK_ORDER]; },
    version: 'mobile dock batch (five-tab dock + workbench root + settings-as-hub-row + chat-home center key + fullscreen pages + canvas page)',
  };
  return function teardownNav() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    // pane 原样归还画板内容区（逆序归还）。
    for (const a of paneAnchors.reverse()) {
      try {
        if (a.pane && a.parent && a.parent.isConnected) a.parent.insertBefore(a.pane, a.next);
      } catch { /* 树已变：放弃归还 */ }
    }
    paneAnchors.length = 0;
    // canvas 页活动文档 pane 归还。
    if (canvasDocAnchor) {
      try {
        if (canvasDocAnchor.pane.isConnected && canvasDocAnchor.parent?.isConnected) {
          canvasDocAnchor.parent.insertBefore(canvasDocAnchor.pane, canvasDocAnchor.next);
        }
      } catch { /* 放弃 */ }
      canvasDocAnchor = null;
    }
    // overlay 内容面原位归还。
    for (const id of [...overlayAnchors.keys()].reverse()) restoreOverlayFace(id);
    overlayAnchors.clear();
    if (chatAdopted) { try { chatAdopted.modal.remove(); } catch { /* ignore */ } chatAdopted = null; }
    if (dockEl) { dockEl.remove(); dockEl = null; }
    dockKeys.clear();
    canvasUI = null;
    hubBodies.clear();
    for (const b of injectedBacks.splice(0)) b.remove();
    document.body.classList.remove('mf-page-open');
    if (container) { container.remove(); container = null; }
    sections.clear();
    stack = ['chat'];
    layerTouched = false;
    overlayReconcileBound.clear();
    delete (/** @type {any} */ (window)).__nfMobileNav;
  };
}
