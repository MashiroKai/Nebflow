// mobile/navController.js — r2 规格②＋③：以聊天为 root 的页面栈导航＋面板体系 app 化。
//
// ── 导航模型（规格②）────────────────────────────────────────────────
//   · root = 主聊天（#main + header + 输入区 + 悬浮工具条）；
//   · 页面 = 项目 / 图谱 / 插件 / token 用量 / 设置 五个独立 view，互斥显示
//     （真切换，非上下层覆盖：同一时刻至多一个 section 带 .live）；
//   · 栈模型 = `stack = ['chat', ...pages]`：进页 push、返回 pop 一层；
//   · 返回钮契约：每页左上角圆形毛玻璃「<」＝ pop 一层（从聊天推入的页
//     ⇒ 返回聊天）；
//   · 栈状态 = 会话内存（模块变量）；🔴 刷新后归位 root（交付裁定：刷新 ≙ App
//     冷启动，落主会话 —— 与层内「启动自动展开抑制」同口径）。
//
// ── 面板体系 app 化（规格③）────────────────────────────────────────
//   · 项目/图谱/插件：废除标签页形态 —— 桌面画板页签 pane 被 **re-parent 进各自
//     页面体**（appendChild = move，监听随节点走；桌面模块零改动，渲染/取数
//     全走桌面导出 API，零复制）。页签系统本身仍是桌面共享的**数据模型**
//     （restore/persist/4 态机不动）；移动层废除的是它的**视觉形态**
//     （页签条永不显示，见 mobileLayer.css）。
//   · 用量/设置：内容面（#settings-modal / #usage-dashboard）**收编进各自页面体**
//     （r3 p4fix：r2 的「overlay 原地升格」垫在页面容器 156 之下被不透明页面
//     遮挡 ⇒ 页面体恒空 —— 改为与三页 pane-adopt 同款的 re-parent 真页面；
//     锚点记录/原位归还照 paneAnchors 惯例）。overlay 节点原地保留，仅作状态
//     载体（.on / [hidden] 与桌面关闭对账观察者不变）；开/关走桌面导出 API
//     （openUsageDashboard / openSettingsPanel），头部注入同款「<」返回钮
//     （桌面关闭钮手机层隐藏）。
//   · 画板 = 文档卡片层：画板层只承载「pop 出来的文档」——面板 pane 一律被
//     移出画板；文档以卡片呈现（圆角浮层卡片 + 卡片头：标题 / 计数 / 关闭）。
//     卡片 X = closeTab（关单卡，关后落相邻文档卡）；上滑收整叠（closeCanvas，
//     文档保留可再开，见 canvasGestures.js）；横滑 = 卡片间切换。
//     卡片层可见性 = canvas-open ∧ 激活页签是文档（面板页签激活 ⇒ 层隐藏）。
//
// ── App 打包留缝点（任务书要求，本轮只留缝不实现）──────────────────
//   · 路由名稳定：'chat' | 'projects' | 'graph' | 'plugins' | 'usage' | 'settings'；
//   · 通用导航 API：pushPage / popPage / popToRoot / popToPage / navigateTo
//     （可任意深度 —— 深链、WebView 壳的历史栈映射可直接复用；UI 内只有
//     navigateTo 一条入口，栈深实际 ≤2）；
//   · 状态广播：document 事件 `nf:mobile-nav`（detail = { stack, top, action }）；
//   · 只读探针：window.__nfMobileNav = { stack, top, version }。
//
// 共享组件复用声明：本文件不复制任何桌面逻辑 —— 页面内容（项目列表/图谱/插件/
// 用量/设置）全部走桌面导出 API；桌面侧零改动。

import {
  openCanvas, closeCanvas, isCanvasOpen, getTabPane, setActiveTab, closeTab,
} from '../canvas.js';
import { key } from '../branding.js';
import { openSettingsPanel, closeSettingsPanel, isSettingsPanelActive } from '../sidebar.js';
import { dockProfileBlock, undockProfileBlock } from '../agentProfile.js';
import { createIconsIn } from '../utils.js';

/** 页面注册表（root 之外）。route id 稳定 = App 深链/壳映射留缝点。 */
export const PAGES = [
  { id: 'projects', tabId: 'projects',   icon: 'folder-kanban', zh: '项目',       en: 'Projects' },
  { id: 'graph',    tabId: 'wiki-graph', icon: 'sparkles',      zh: '图谱',       en: 'Wiki Graph' },
  { id: 'plugins',  tabId: 'plugins',    icon: 'puzzle',        zh: '插件',       en: 'Plugins' },
  { id: 'usage',    tabId: null,         icon: 'bar-chart-3',   zh: 'token 用量', en: 'Usage' },
  { id: 'settings', tabId: null,         icon: 'settings',      zh: '设置',       en: 'Settings' },
];
const PAGE_BY_ID = new Map(PAGES.map((p) => [p.id, p]));

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

/** 空闲预热（首次点击零延迟；失败静默 —— 页面路径有兜底）。 */
export function warm() { modProjects(); modGraph(); modPlugins(); modUsage(); }

function tt(zh, en) {
  // 语言源 = i18n.js 同源键（key('locale')，branding.js 命名空间派生）。
  // 重启窗热修批 2026-10-18：裸 `'nebflow_locale'` 字面随品牌迁移失效，迁 `key()`。
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

// ── 首触标记（启动自动展开抑制的判据，layoutController 消费）────────
let layerTouched = false;
export function touchLayer() {
  if (!layerTouched) { layerTouched = true; logEvent('layer-first-touch'); }
}
export function isLayerTouched() { return layerTouched; }

// ── 模块状态 ─────────────────────────────────────────────────────────
/** @type {string[]} 页面栈；stack[0] 恒为 'chat'（root）。 */
let stack = ['chat'];
/** @type {HTMLElement|null} */
let container = null;
/** @type {Map<string, HTMLElement>} route id → section */
const sections = new Map();
/** @type {{pane: Element, parent: Node, next: Node|null}[]} pane 归还锚点（拆层逆序归还）。 */
const paneAnchors = [];
/** @type {HTMLElement|null} 卡片头（插入 .canvas-header，拆层移除）。 */
let cardHead = null;
/** @type {MutationObserver|null} */
let contentObserver = null;
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

  const head = document.createElement('div');
  head.className = 'mobile-page-header';
  const back = document.createElement('button');
  back.type = 'button';
  back.className = 'mobile-back-btn';
  back.title = backLabel();
  back.setAttribute('aria-label', backLabel());
  back.innerHTML = '<i data-lucide="chevron-left"></i>';
  back.addEventListener('click', () => popPage());
  const title = document.createElement('span');
  title.className = 'mobile-page-title';
  title.tabIndex = -1; // push 后聚焦（读屏从头播报页名）
  title.textContent = tt(def.zh, def.en);
  const spacer = document.createElement('span');
  spacer.className = 'mobile-page-head-spacer';
  spacer.setAttribute('aria-hidden', 'true');
  head.append(back, title, spacer);
  if (typeof lucide !== 'undefined') createIconsIn(head);

  const body = document.createElement('div');
  body.className = 'mobile-page-body';
  section.append(head, body);
  return section;
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
    syncCardChrome();
  };
  window.addEventListener('locale-changed', onLocale);
  unsubs.push(() => window.removeEventListener('locale-changed', onLocale));
  return container;
}

function bodyOf(pageId) {
  return sections.get(pageId)?.querySelector('.mobile-page-body') || null;
}

// ── 面板 pane re-parent（③：标签页形态 → 真页面）────────────────────

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

// ── overlay 内容面收编（r3 p4fix：usage/settings 与三页同款真页面）─────
// overlay 节点原地保留，只作状态载体（.on / [hidden] 与 watchOverlayClose 的
// 关闭对账照旧）；可见内容面 re-parent 进页面体。桌面重渲/销毁兼容：renderSettings
// 写 #settings-content、usage reloadAll 写面板子树 —— 全是收编节点内部的面写入，
// 与面的落点无关；两节点本体桌面代码从不替换（settings modal 静态、usage
// buildShell 每页只建一次）。桌面层（layerActive=false）不走本文件任何路径 ⇒ 零收编。
/** @type {Map<string, {face: Element, parent: Node, next: Node|null}>} page id → 面锚点 */
const overlayAnchors = new Map();
/** 页面 → overlay 内容面（收编对象）。 */
const overlayFaceByPage = {
  settings: '#settings-modal',
  usage: '#usage-dashboard',
};

/** P12 设置页 profile 置顶块（agentProfile 面板停靠形态）：栈顶=设置时收编为
 *  页体首块（常显、弹窗语义让位 —— dock/undock 缝在 agentProfile.js），出栈
 *  拆铺归位。锚点惯例同 overlayAnchors（面板本体挂 document.body）。 */
const profileAnchor = { face: null, parent: null, next: null };

function syncProfileBlock() {
  const body = bodyOf('settings');
  if (!body) return;
  const live = stack.includes('settings');
  if (live) {
    const face = dockProfileBlock(); // 幂等：建面 + 停靠态 + 常显（弹窗语义让位）
    if (!profileAnchor.face) {
      profileAnchor.face = face;
      profileAnchor.parent = face.parentElement;
      profileAnchor.next = face.nextSibling;
      logEvent('pane-adopt', { page: 'settings', face: '#agent-profile-panel' });
    }
    if (face.parentElement !== body) body.insertBefore(face, body.firstChild);
  } else if (profileAnchor.face) {
    const { face, parent, next } = profileAnchor;
    profileAnchor.face = null; profileAnchor.parent = null; profileAnchor.next = null;
    if (parent && parent.isConnected) {
      try { parent.insertBefore(face, next); } catch { document.body.appendChild(face); }
    } else {
      document.body.appendChild(face);
    }
    undockProfileBlock();
    logEvent('pane-restore', { page: 'settings', face: '#agent-profile-panel' });
  }
}

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

/** 栈 ↔ overlay 面对账（汇合点 = renderPages：在栈即收编，出栈即归还 ——
 *  外部关闭对账（watchOverlayClose）改栈后也走 renderPages，一处兜全）。
 *  usage 首开时面板由 openUsageDashboard 异步建出 —— pushPage 的 .then 里
 *  显式再跑一次；本函数幂等。 */
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

async function ensurePanelContent(pageId) {
  const fn = openFnByPage[pageId];
  if (!fn) return;
  try {
    await fn();
  } catch (e) {
    console.error('[mobile-layer] page open failed:', pageId, e);
  }
  adoptPane(pageId);
}

/** 画板内容区监视：启动恢复/服务端 reconcile 新建的面板 pane 出现即收编进页面
 *  （restoreTabs 晚于本层激活的到达路径；文档 pane 不收编）。 */
function watchCanvasContent() {
  const content = document.getElementById('canvas-content');
  if (!content) return;
  const tabIdToPage = new Map(PAGES.filter((p) => p.tabId).map((p) => [p.tabId, p.id]));
  const sweep = () => {
    for (const pane of content.querySelectorAll(':scope > .canvas-tab-pane')) {
      const pageId = tabIdToPage.get((/** @type {HTMLElement} */ (pane)).dataset.tabId);
      if (pageId) adoptPane(pageId);
    }
    syncCardLayer();
  };
  contentObserver = new MutationObserver(sweep);
  contentObserver.observe(content, { childList: true });
  sweep();
  unsubs.push(() => { contentObserver?.disconnect(); contentObserver = null; });
}

// ── 文档卡片（③：画板层只承载 pop 出来的文档，以卡片呈现）───────────

function activeTabId() {
  const el = /** @type {HTMLElement|null} */ (document.querySelector('#canvas-tab-bar .canvas-tab.active'));
  return el ? el.dataset.tabId : null;
}

/** 文档页签清单 = pane 仍在画板内容区里的页签（面板 pane 已收编进页面）。
 *  @returns {{id: string, label: string}[]} */
export function docTabs() {
  const content = document.getElementById('canvas-content');
  if (!content) return [];
  const out = [];
  for (const el of document.querySelectorAll('#canvas-tab-bar .canvas-tab')) {
    const id = (/** @type {HTMLElement} */ (el)).dataset.tabId;
    const pane = id ? getTabPane(id) : null;
    if (pane && pane.parentElement === content) {
      out.push({ id, label: el.querySelector('.canvas-tab-label')?.textContent || id });
    }
  }
  return out;
}

export function hasDocTabs() { return docTabs().length > 0; }

/** 当前激活的文档页签 id（激活页签是面板页签时返回 null）。 */
export function activeDocId() {
  const id = activeTabId();
  if (!id) return null;
  const pane = getTabPane(id);
  const content = document.getElementById('canvas-content');
  return pane && content && pane.parentElement === content ? id : null;
}

function ensureCardHead() {
  if (cardHead) return cardHead;
  const header = document.querySelector('#canvas-panel .canvas-header');
  if (!header) return null;
  cardHead = document.createElement('div');
  cardHead.className = 'mobile-card-head';
  const title = document.createElement('span');
  title.className = 'mobile-card-title';
  const count = document.createElement('span');
  count.className = 'mobile-card-count';
  count.hidden = true;
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'mobile-card-close';
  close.title = tt('关闭文档', 'Close document');
  close.setAttribute('aria-label', tt('关闭文档', 'Close document'));
  close.innerHTML = '<i data-lucide="x"></i>';
  close.addEventListener('click', () => closeCard());
  cardHead.append(title, count, close);
  header.insertBefore(cardHead, header.firstChild);
  if (typeof lucide !== 'undefined') createIconsIn(cardHead);
  return cardHead;
}

function syncCardChrome() {
  if (!cardHead) return;
  const docs = docTabs();
  const activeId = activeDocId();
  const idx = docs.findIndex((d) => d.id === activeId);
  const title = cardHead.querySelector('.mobile-card-title');
  if (title) title.textContent = idx >= 0 ? docs[idx].label : '';
  const count = /** @type {HTMLElement|null} */ (cardHead.querySelector('.mobile-card-count'));
  if (count) {
    const show = docs.length > 1 && idx >= 0;
    count.hidden = !show;
    if (show) count.textContent = `${idx + 1}/${docs.length}`;
  }
}

/** 卡片层可见性 = 画板开 ∧ 激活页签是文档（面板页签激活 ⇒ 层隐藏，页面接管）。 */
export function syncCardLayer() {
  const live = isCanvasOpen() && !!activeDocId();
  document.body.classList.toggle('mf-cards-live', live);
  ensureCardHead();
  syncCardChrome();
}

/** 关闭当前文档卡片（卡片头 X）：关单卡；关后落相邻文档卡，无文档卡则收层。
 *  注：closeTab 的 DOM/映射清算是异步收尾（transitionend/200ms），本卡在此期间
 *  仍会出现在 docTabs() 里 —— 相邻卡选择必须先滤掉它；抢先 setActiveTab 相邻卡
 *  也会让 closeTab 的 finalize 走「激活已变」分支，不再自选（可能选中面板页签）。 */
export function closeCard() {
  const id = activeDocId();
  if (!id) return;
  const docs = docTabs();
  const idx = docs.findIndex((d) => d.id === id);
  logEvent('card-close', { tab: id });
  closeTab(id);
  const rest = docTabs().filter((d) => d.id !== id);
  if (rest.length) {
    // 桌面 closeTab 的相邻页签选择含面板页签；手机层改落相邻**文档**卡。
    const next = rest[Math.min(Math.max(idx, 0), rest.length - 1)];
    if (next && next.id !== activeTabId()) setActiveTab(next.id);
  }
  syncCardLayer();
}

/** bar「画板」键：开/收文档卡片层（无文档卡时轻提示，不伪造空层）。 */
export function toggleCards() {
  if (document.body.classList.contains('mf-cards-live')) {
    closeCanvas();
    logEvent('cards-collapse', { via: 'bar' });
  } else if (hasDocTabs()) {
    // 激活页签可能是面板页签：先落一张文档卡再开层，避免开出一个不可见层。
    const docs = docTabs();
    if (!activeDocId() && docs.length) setActiveTab(docs[docs.length - 1].id);
    openCanvas();
    logEvent('cards-expand', { via: 'bar' });
  } else {
    window.__showToast?.(tt('画板暂无文档卡片', 'No document cards yet'), 'info');
    logEvent('cards-empty');
  }
  syncCardLayer();
}

/** 主会话键：pop 到 root 并收起卡片层（文档保留在栈里，可再开）。 */
export function goHome() {
  popToRoot();
  if (isCanvasOpen()) { closeCanvas(); logEvent('cards-collapse', { via: 'home' }); }
  syncCardLayer();
}

// ── 页面栈（②：root chat + push/pop）────────────────────────────────

export function navState() {
  return {
    stack: [...stack],
    top: stack[stack.length - 1],
    cardsLive: document.body.classList.contains('mf-cards-live'),
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
  document.body.classList.toggle('mf-page-open', PAGE_BY_ID.has(top));
  syncOverlayFaces(); // usage/settings 面随栈收编/归还（外部关闭对账路径同此汇合）
  syncProfileBlock(); // P12：设置页 profile 置顶块随栈停靠/拆铺
}

/** 页面面关闭（overlay 升格的 usage/settings 与栈同步；面板页由 renderPages 收）。
 *  r3 p4fix：面关闭后其内容面的原位归还统一走 renderPages → syncOverlayFaces
 *  （本函数只负责桌面开/关态同步，不碰 DOM 落点）。 */
function closePageFace(pageId) {
  if (pageId === 'settings') {
    try { if (isSettingsPanelActive()) closeSettingsPanel(); } catch { /* 模块态异常不阻断 */ }
  }
  if (pageId === 'usage') {
    modUsage().then((m) => m.closeUsageDashboard()).catch(() => { /* 未载即未开 */ });
  }
}

/**
 * 通用 push（App 壳/深链留缝点）：目标已在栈中则先摘除再压顶（摘除位移的
 * overlay 面同步关闭）。UI 内统一走 navigateTo。
 * @param {string} pageId
 */
export function pushPage(pageId) {
  if (!PAGE_BY_ID.has(pageId)) return;
  ensureContainer();
  const displaced = [];
  stack = stack.filter((p) => (p === pageId ? (displaced.push(p), false) : true));
  for (const d of displaced) closePageFace(d);
  stack.push(pageId);
  logEvent('nav-push', { page: pageId, depth: stack.length });
  const def = PAGE_BY_ID.get(pageId);
  if (def.tabId) ensurePanelContent(pageId); // 异步：pane 建好即收编，页面壳即刻呈现
  if (pageId === 'settings') {
    try {
      openSettingsPanel();
      ensureInjectedBack('.settings-modal-header', 'settings');
    } catch (e) { console.error('[mobile-layer] settings', e); }
  }
  if (pageId === 'usage') {
    modUsage()
      .then((m) => {
        m.openUsageDashboard();
        ensureInjectedBack('.ud-header', 'usage');
        ensureUsageReconcile(); // overlay 首开即建 —— 此刻才存在，挂对账观察者
        syncOverlayFaces();     // 首开的面板此刻才建出 —— 建好即收编进页面体
      })
      .catch((e) => console.error('[mobile-layer] usage', e));
  }
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

/** pop 到 root（主会话键）。 */
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
 * UI 内统一入口（bar 页面键）：归 root 再 push —— 栈深实际 ≤2，overlay 面
 * 不可能出现「栈中位移但面残留」。通用 pushPage 保留给壳/深链。
 * @param {string} pageId
 */
export function navigateTo(pageId) {
  if (!PAGE_BY_ID.has(pageId)) return;
  if (stack[stack.length - 1] === pageId) { emitNav('push-noop'); return; }
  popToRoot();
  pushPage(pageId);
}

// ── overlay 面（usage/settings）的注入式返回钮 + 外部关闭对账 ────────

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
  // r3 p4fix：自身页在栈顶时就是普通「返回一层」（popToPage 对栈顶页是空转）；
  // 不在栈顶（下方叠了别的页）才弹回本页。
  back.addEventListener('click', () => {
    if (stack[stack.length - 1] === pageId) popPage();
    else popToPage(pageId);
  });
  host.insertBefore(back, host.firstChild);
  injectedBacks.push(back);
  if (typeof lucide !== 'undefined') createIconsIn(back);
}

/** 桌面关闭路径（Escape / 桌面关闭钮 / overlay 点外）关掉面 ⇒ 栈同步弹层。
 *  设置 overlay 静态存在于 index.html（激活即可挂观察者）；用量 overlay 由
 *  usageDashboard.buildShell 首开时才建 —— 它的对账观察者延迟到首次 push
 *  （ensureUsageReconcile），避免在激活期对不存在的节点挂空观察。 */
function watchOverlayClose() {
  const settingsOv = document.getElementById('settings-overlay');
  if (settingsOv) {
    const reconcile = () => {
      const top = stack[stack.length - 1];
      if (top === 'settings' && !settingsOv.classList.contains('on')) {
        stack.pop();
        renderPages();
        emitNav('pop-external');
      }
    };
    const mo = new MutationObserver(reconcile);
    mo.observe(settingsOv, { attributes: true, attributeFilter: ['class'] });
    overlayObservers.push(mo);
  }
  unsubs.push(() => {
    for (const mo of overlayObservers.splice(0)) mo.disconnect();
  });
}

/** 用量 overlay 首开后挂对账观察者（幂等；节点不存在则下次 push 再试）。 */
let usageReconcileBound = false;
function ensureUsageReconcile() {
  if (usageReconcileBound) return;
  const usageOv = document.getElementById('usage-overlay');
  if (!usageOv) return;
  usageReconcileBound = true;
  const reconcileUsage = () => {
    const top = stack[stack.length - 1];
    if (top === 'usage' && usageOv.hidden) {
      stack.pop();
      renderPages();
      emitNav('pop-external');
    }
  };
  const mo = new MutationObserver(reconcileUsage);
  mo.observe(usageOv, { attributes: true, attributeFilter: ['hidden'] });
  overlayObservers.push(mo);
}

// ── 画板事件 → 卡片层可见性 ─────────────────────────────────────────

function watchCanvasEvents() {
  const onSwitch = () => syncCardLayer();
  document.addEventListener('canvas-tab-switched', onSwitch);
  document.addEventListener('canvas-tab-closed', onSwitch);
  const mo = new MutationObserver(() => syncCardLayer());
  mo.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  unsubs.push(() => {
    document.removeEventListener('canvas-tab-switched', onSwitch);
    document.removeEventListener('canvas-tab-closed', onSwitch);
    mo.disconnect();
  });
}

// ── 激活 / 拆除 ─────────────────────────────────────────────────────

export function activateNav() {
  (/** @type {any} */ (window)).__nfMobileLayerEvents = __nfEvents;
  ensureContainer();
  watchCanvasContent();
  watchOverlayClose();
  watchCanvasEvents();
  // 激活即收编启动恢复已建成的面板 pane（同步路径；异步路径走 watchCanvasContent）。
  for (const def of PAGES) if (def.tabId) adoptPane(def.id);
  syncCardLayer();
  renderPages();
  // 只读探针（App 壳/深链留缝点）。
  (/** @type {any} */ (window)).__nfMobileNav = {
    get stack() { return navState().stack; },
    get top() { return navState().top; },
    version: 'mobile-ui-refactor r3 nav (p4fix settings/usage face-adopt)',
  };
  return function teardownNav() {
    for (const fn of unsubs.splice(0)) { try { fn(); } catch { /* ignore */ } }
    // pane 原样归还画板内容区（逆序归还：锚点的 next 在归还时必然已就位）。
    for (const a of paneAnchors.reverse()) {
      try {
        if (a.pane && a.parent && a.parent.isConnected) a.parent.insertBefore(a.pane, a.next);
      } catch { /* 树已变：放弃归还，避免二次破坏 */ }
    }
    paneAnchors.length = 0;
    // overlay 内容面原位归还（锚点语义同 paneAnchors；开/关态原样保留给桌面 ——
    // 视口跨越回桌面时正在看的设置/用量页直接以桌面形态续显）。
    for (const id of [...overlayAnchors.keys()].reverse()) restoreOverlayFace(id);
    overlayAnchors.clear();
    // P12 profile 置顶块拆铺（面板本体归位 body + 弹窗语义还原）。
    if (profileAnchor.face) {
      const { face } = profileAnchor;
      profileAnchor.face = null; profileAnchor.parent = null; profileAnchor.next = null;
      if (!face.parentElement || face.parentElement !== document.body) document.body.appendChild(face);
      undockProfileBlock();
    }
    if (cardHead) { cardHead.remove(); cardHead = null; }
    for (const b of injectedBacks.splice(0)) b.remove();
    document.body.classList.remove('mf-cards-live', 'mf-page-open');
    if (container) { container.remove(); container = null; }
    sections.clear();
    stack = ['chat'];
    layerTouched = false;
    usageReconcileBound = false;
    delete (/** @type {any} */ (window)).__nfMobileNav;
  };
}
