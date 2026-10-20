// canvas.js — Canvas panel (画板) controller with multi-tab support.
//
// The canvas is a generic display container on the right side of the layout.
// It supports multiple tabs — each tab has its own content pane. The flow
// visualization is one tab type; file viewers (markdown, code, html) are others.
//
// Tab lifecycle:
//   openTab(id, title, opts)  — create tab (if new), switch to it, open panel
//   closeTab(id)              — remove tab; if last tab, close panel
//   setActiveTab(id)          — switch visible pane + highlight tab button
//   getTabPane(id)            — get the content div for a tab (for external render)
//
// Panel open/close is CSS-driven, mirroring the sidebar collapse pattern:
// JS sets a CSS custom property (--canvas-width) and toggles body.canvas-open;
// CSS handles all transitions via flex-basis.

import { key } from './branding.js';
import { t } from './i18n.js';
import { makeReference } from './reference.js';
import { initCanvasDrop } from './canvasDrop.js';
import { authHeaders } from './flowHelpers.js'; // Bearer token for REST withAuth (/api/canvas-tabs)
import { setSideBarCollapsed, isMobileViewport } from './activityBar.js';

const MIN_CANVAS_WIDTH = 320;
const MAX_CANVAS_WIDTH = 1200;
// Minimum usable width for the CHAT column while the panel is open. Same
// number as colResizer.js MIN_WIDTHS.main (the column minimum enforced while
// dragging a resizer): both answer the question "when is a side-by-side split
// still possible?", so the threshold below and the drag clamp agree.
const MIN_CHAT_WIDTH = 320;
const LS_KEY = key('col_widths');
const LS_TABS_KEY = key('canvas_tabs');
// F1 (2026-08-30 作者裁定 + 方案 §8.4 F1): 服务端作为权威标签存档，localStorage 仅作降级缓存。
// 根治 Safari 无痕/多窗口清空 localStorage 导致的标签重启丢失。
const TABS_API = '/api/canvas-tabs';
// F1: 服务端 PUT 防抖计时器——openTab/closeTab/setActiveTab/pinTab 都调 persistTabs，
// 防抖合并连续操作成一次 PUT。
let serverPersistTimer = null;
// F1: 本次页面加载已派发过 readFile 的文件路径集合。restoreFromData（本地）与
// reconcileFromServer（服务端）会先后处理同一批 file 标签，用此集合去重，
// 避免同一文件被发两次 readFile（重复请求/重复打开）。
const requestedFilePaths = new Set();

// Track pending close timeout so openCanvas can cancel it (rapid toggle safety).
let closeTimeout = null;

// ── Tab state ──────────────────────────────────────────────
// Map of tabId -> { id, title, type, paneEl, tabEl, closable }
const tabs = new Map();
let activeTabId = null;
// Preview (temporary) tabs, one per preview group — VS Code behavior, but
// scoped: tabs only replace a preview tab within their own group. File tabs
// (with absPath) share the 'file' group so opening a .md still replaces a
// previewed .js; panel tabs ('teams', 'flows', 'flow-run', ...) each get
// their own group so different panel types never replace each other.
const previewTabs = new Map();  // previewKey -> tabId
const previewKeyOf = (type, absPath) => absPath ? 'file' : (type || 'generic');

// #303 C3: page anchors from document-reference jumps, keyed by absPath. A
// ref click opens with empty content (readFile round trip) and the response
// item carries no anchor — stash it here and consume at render time.
const pendingRefAnchors = new Map();

// ── #303 global-reference: build a Reference from a tab's current state ──
// Best-effort anchor extraction per viewer type (PDF page range, Monaco text
// selection). Tabs without absPath (panel tabs) produce no ref. The reference
// is a pointer — source.path + optional anchor; the agent reads on demand.

/** @param {string} s */
function _trunc(s, n) {
  const str = String(s || '');
  return str.length > n ? str.slice(0, n) + '…' : str;
}

/** Best-effort anchor for a tab pane: PDF visible page range / Monaco selection. */
function tabAnchor(pane) {
  if (!pane) return { kind: 'none' };
  // XLSX — active sheet + its full range (no cell selection in the viewer, so
  // the whole active sheet is the anchor; C5-A4).
  const xr = pane._xlsxRef;
  if (xr && xr.active) {
    return { kind: 'cell', sheet: xr.active, cellRange: (xr.dims && xr.dims[xr.active]) || '' };
  }
  // PDF — canvases stacked in .pdf-pages; compute the visible range from scroll.
  const pdfPages = pane.querySelector('.pdf-pages');
  if (pdfPages) {
    const pages = Array.from(pdfPages.querySelectorAll('.pdf-page')).map((c, i) => ({
      n: i + 1, top: c.offsetTop, h: c.offsetHeight,
    })).filter(p => p.h > 0);
    if (pages.length) {
      const top = pdfPages.scrollTop, bottom = top + (pdfPages.clientHeight || 0);
      const vis = pages.filter(p => p.top < bottom && (p.top + p.h) > top);
      if (vis.length) {
        const first = Math.min(...vis.map(p => p.n), pages[0].n);
        const last = Math.max(...vis.map(p => p.n), first);
        return { kind: 'page', pageStart: first, pageEnd: last };
      }
    }
  }
  // Monaco text — selection line range (fall back to cursor line).
  const h = pane._editorHandle;
  if (h && h.editor && typeof h.editor.getSelection === 'function') {
    const sel = h.editor.getSelection();
    if (sel) {
      const start = sel.getStartPosition()?.lineNumber;
      const end = sel.getEndPosition()?.lineNumber;
      if (start != null) {
        const lineStart = Math.min(start, end ?? start);
        const lineEnd = Math.max(start, end ?? start);
        const text = (h.editor.getModel?.()?.getValueInRange?.(sel)) || '';
        return { kind: 'range', lineStart, lineEnd, text: _trunc(text, 160) };
      }
    }
  }
  return { kind: 'none' };
}

/** Build a makeReference input from a tab (returns null for non-file tabs). */
function refInputForTab(tab) {
  // #303 C5-A2: cross-origin URL tabs can't select an element (crossorigin
  // mode) — degrade to a whole-page reference (anchor.kind='none').
  if (tab && tab.type === 'url' && tab.id && tab.id.startsWith('url:')) {
    const url = tab.id.slice(4);
    return {
      refType: 'html-element',
      source: { kind: 'web', url, title: tab.title || url },
      anchor: { kind: 'none' },
    };
  }
  if (!tab || !tab.absPath || !tab.type) return null;
  const name = (tab.title || tab.absPath.split('/').pop() || '');
  // document vs file: keep the tab's itemType as refType hint, but normalize to
  // the two pointer types we render (document for MIME docs, file otherwise).
  const isDoc = /pdf|epub|xlsx|xl|docx|pptx/.test(tab.type) || /\.(pdf|epub|xlsx|xls|docx|pptx)$/i.test(tab.absPath);
  const anchor = tabAnchor(tab.paneEl);
  return {
    refType: isDoc ? 'document' : 'file',
    source: { kind: 'canvas', path: tab.absPath, fileName: name, title: name },
    anchor,
  };
}

/** Build a fully-formed Reference from a tab (makeReference over refInputForTab). */
function refForTab(tab) {
  const input = refInputForTab(tab);
  return input ? makeReference(input) : null;
}

// ── Canvas tab context menu (「引用当前页/选区」) ──
// canvas-stack：叠卡右键菜单在同一张玻璃菜单里追加「关闭 / 关闭其他」（v5 菜单语义，
// 样张 ctx-menu 三项），经 actions 回调接线到叠卡引擎；标签条旧入口不传 actions，
// 行为逐字不变。 */
let _canvasCtxMenu = null;
let _ctxMenuBound = false;
function hideCanvasRefMenu() {
  if (_canvasCtxMenu) { _canvasCtxMenu.remove(); _canvasCtxMenu = null; }
}
function showCanvasRefMenu(x, y, entry, actions = null) {
  hideCanvasRefMenu();
  const ref = refForTab(entry);
  if (!ref && !actions) {
    window.__showToast?.(t('canvas.noRef'), 'info');
    return;
  }
  const menu = document.createElement('div');
  menu.className = 'canvas-tab-context-menu';
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';
  // 样张菜单语义：引用项恒在（面板页签点了 toast noRef）；叠卡模式追加关闭两项
  const refBtn = document.createElement('button');
  refBtn.textContent = t('canvas.referenceCurrent');
  refBtn.addEventListener('click', () => {
    hideCanvasRefMenu();
    if (!ref) { window.__showToast?.(t('canvas.noRef'), 'info'); return; }
    // Dynamic import to avoid a static edge back into input.js (which would
    // close the modal→taskList→canvas→input cycle — historically via
    // taskArchive.js, removed in the 2026-08-30 task redesign).
    import('./input.js').then(({ appendRefToActiveView }) => appendRefToActiveView(ref));
  });
  menu.appendChild(refBtn);
  if (actions) {
    const hr = document.createElement('hr');
    menu.appendChild(hr);
    if (actions.onClose) {
      const closeBtn = document.createElement('button');
      closeBtn.textContent = '关闭';
      closeBtn.addEventListener('click', () => { hideCanvasRefMenu(); actions.onClose(); });
      menu.appendChild(closeBtn);
    }
    if (actions.onCloseOthers) {
      const closeOthersBtn = document.createElement('button');
      closeOthersBtn.textContent = '关闭其他';
      closeOthersBtn.className = 'danger';
      closeOthersBtn.addEventListener('click', () => { hideCanvasRefMenu(); actions.onCloseOthers(); });
      menu.appendChild(closeOthersBtn);
    }
  }
  document.body.appendChild(menu);
  _canvasCtxMenu = menu;
  // Adjust if off-screen
  const rect = menu.getBoundingClientRect();
  if (rect.right > window.innerWidth) menu.style.left = (x - rect.width) + 'px';
  if (rect.bottom > window.innerHeight) menu.style.top = (y - rect.height) + 'px';
}

// ── Drag-to-reorder ────────────────────────────────────────
let draggedTabId = null;

function attachDragHandlers(tabEl, id) {
  tabEl.addEventListener('dragstart', (e) => {
    draggedTabId = id;
    tabEl.classList.add('dragging');
    // canvasdnd: must be 'copyMove', NOT 'move'. initGlobalFileDrop (input.js)
    // rewrites dropEffect to 'copy' on every document-level dragover of an
    // application/x-nebflow-ref drag, and per the HTML spec's "current drag
    // operation" table 'move' × 'copy' resolves to "none" ⇒ the drag operation
    // fails and Chromium (Edge/Chrome) never delivers a single `drop`, killing
    // both the reference drop and tab reorder. Same declaration as the explorer
    // file rows (explorer.js: "copy → input bar, move → tree").
    e.dataTransfer.effectAllowed = 'copyMove';
    e.dataTransfer.setData('text/plain', id);
    // #303 B6b: a canvas tab dragged to an input bar carries a Reference
    // payload (current page/selection anchor). Dragging onto another tab for
    // reorder ignores this MIME (drop reorders via draggedTabId).
    const tab = tabs.get(id);
    const ref = refForTab(tab);
    if (ref) {
      e.dataTransfer.setData('application/x-nebflow-ref',
        JSON.stringify({ refType: ref.refType, source: ref.source, anchor: ref.anchor }));
    }
  });

  tabEl.addEventListener('dragend', () => {
    tabEl.classList.remove('dragging');
    // Clear all drop indicators
    document.querySelectorAll('.canvas-tab').forEach(t => {
      t.classList.remove('drop-before', 'drop-after');
    });
    draggedTabId = null;
  });

  tabEl.addEventListener('dragover', (e) => {
    e.preventDefault();
    if (!draggedTabId || draggedTabId === id) return;
    e.dataTransfer.dropEffect = 'move';

    // Determine before/after based on mouse X position relative to tab center.
    const rect = tabEl.getBoundingClientRect();
    const after = e.clientX > rect.left + rect.width / 2;

    tabEl.classList.toggle('drop-after', after);
    tabEl.classList.toggle('drop-before', !after);
  });

  tabEl.addEventListener('dragleave', () => {
    tabEl.classList.remove('drop-before', 'drop-after');
  });

  tabEl.addEventListener('drop', (e) => {
    e.preventDefault();
    if (!draggedTabId || draggedTabId === id) return;

    const rect = tabEl.getBoundingClientRect();
    const after = e.clientX > rect.left + rect.width / 2;
    moveTab(draggedTabId, id, after);

    tabEl.classList.remove('drop-before', 'drop-after');
  });
}

/** Move a tab to a new position in both DOM and the tabs Map.
 *  @param {string} dragId    — The tab being moved.
 *  @param {string} targetId  — The tab it's dropped on.
 *  @param {boolean} after    — true = place after target, false = before. */
function moveTab(dragId, targetId, after) {
  const dragEntry = tabs.get(dragId);
  const targetEntry = tabs.get(targetId);
  if (!dragEntry || !targetEntry) return;

  const tabBar = document.getElementById('canvas-tab-bar');
  if (!tabBar) return;

  // Reorder DOM
  if (after) {
    targetEntry.tabEl.insertAdjacentElement('afterend', dragEntry.tabEl);
  } else {
    targetEntry.tabEl.insertAdjacentElement('beforebegin', dragEntry.tabEl);
  }

  // Sync Map order to match DOM
  const newOrder = [];
  tabBar.querySelectorAll('.canvas-tab').forEach(el => {
    const tid = el.dataset.tabId;
    if (tabs.has(tid)) newOrder.push([tid, tabs.get(tid)]);
  });
  tabs.clear();
  newOrder.forEach(([k, v]) => tabs.set(k, v));
}

/** Is the window too narrow for a side-by-side chat + canvas split?
 *
 *  The split needs, in the flex row: activity bar + sidebar + #main + panel +
 *  margins. Below that budget no side-by-side arrangement exists — the panel
 *  switches to its overlay form (body.canvas-overlay, see split.css) and
 *  #main keeps its full width.
 *  @returns {boolean} */
function isNarrowSplit() {
  const sidebar = document.getElementById('sidebar');
  const activityBar = document.getElementById('activity-bar');
  const sidebarW = sidebar ? sidebar.getBoundingClientRect().width : 0;
  const activityW = activityBar ? activityBar.getBoundingClientRect().width + 8 : 0;
  const edgeBarW = 3;
  // Both panels have margin:0 10px = 20px each = 40px total margins
  const totalMargins = 40;
  const available = window.innerWidth - sidebarW - activityW - edgeBarW - totalMargins;
  return available < MIN_CANVAS_WIDTH + MIN_CHAT_WIDTH;
}

/** Compute the target open width so Canvas matches Chat panel width.
 *  Both panels share the remaining space 50/50 after fixed elements
 *  (sidebar, activity bar) and margins are accounted for.
 *
 *  In the narrow range (see isNarrowSplit) the split cannot hold both columns,
 *  so the side-by-side width is no longer meaningful — return the smallest
 *  legal side-by-side width and let CSS drive the overlay form instead of
 *  letting this value claim space the row does not have. */
function computeOpenWidth() {
  const sidebar = document.getElementById('sidebar');
  const activityBar = document.getElementById('activity-bar');
  const sidebarW = sidebar ? sidebar.getBoundingClientRect().width : 0;
  const activityW = activityBar ? activityBar.getBoundingClientRect().width + 8 : 0;
  const edgeBarW = 3;
  // Both panels have margin:0 10px = 20px each = 40px total margins
  const totalMargins = 40;
  const available = window.innerWidth - sidebarW - activityW - edgeBarW - totalMargins;
  const half = Math.floor(available / 2);
  return Math.max(MIN_CANVAS_WIDTH, Math.min(MAX_CANVAS_WIDTH, half));
}

/** Apply the current window's layout mode to the body class list.
 *
 *  Layout mode is a single decision point: 「panel open」 has two forms —
 *  side-by-side (wide window) and overlay (window too narrow to hold both
 *  columns at their minimum widths). Both are the SAME panel state: tabs,
 *  persistence and the close path are untouched — only the box model differs.
 *
 *  The overlay form floats over #main, and #main's top band (the glass header
 *  plus #top-overlays) carries the panel's OWN header toggle — covering it
 *  would turn the primary toggle into a dead control exactly where the user
 *  needs it. So the drawer's top edge is taken from the measured band bottom
 *  and published as a CSS variable; split.css consumes it and never guesses.
 *  @returns {boolean} true when the overlay form is active. */
function applyCanvasLayoutMode() {
  const overlay = isNarrowSplit();
  document.body.classList.toggle('canvas-overlay', overlay);
  if (overlay) {
    const header = document.getElementById('header');
    const overlays = document.getElementById('top-overlays');
    const bottom = Math.max(
      header ? header.getBoundingClientRect().bottom : 0,
      overlays ? overlays.getBoundingClientRect().bottom : 0,
    );
    if (bottom > 0) document.documentElement.style.setProperty('--canvas-overlay-top', Math.ceil(bottom) + 'px');
  }
  return overlay;
}

/** Re-evaluate the layout mode for the current window width.
 *  Called from the panel's own resize / resize-adjacent signals: the mode
 *  depends on the Side Bar's rendered width, which changes on window resize,
 *  on the sidebar collapse toggle (a body-class change) and on a resizer drag
 *  (an inline flex change that dispatches `nebflow-col-resize`) — all three
 *  must be able to flip the panel back to side-by-side on the same beat.
 *  No-op while the panel is closed: the open path evaluates the mode again. */
function refreshCanvasLayoutMode() {
  if (!isCanvasOpen()) return;
  applyCanvasLayoutMode();
}

/** Open the canvas panel.
 *  @param {string} title — Unused in tab model (titles are per-tab). Kept for API compat.
 *
 *  Mirrors the sidebar pattern: set CSS variable, toggle body class, done.
 *  The panel is always display:flex (never display:none), so no double-rAF
 *  or forced reflow is needed. CSS handles the entire transition. */
export function openCanvas(title = '') {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;

  if (closeTimeout) { clearTimeout(closeTimeout); closeTimeout = null; }

  // Set target width via CSS custom property, then toggle body class.
  // The panel transitions from flex-basis:0 to --canvas-width via CSS.
  const canvasTarget = computeOpenWidth();
  document.documentElement.style.setProperty('--canvas-width', canvasTarget + 'px');
  document.body.classList.add('canvas-open');
  applyCanvasLayoutMode();
  syncCanvasPanelButtons();
  // Mobile drawer exclusivity (≤768px, css/mobile.css): both drawers are full
  // overlays, so an opening canvas drawer collapses the Side Bar drawer —
  // whoever expands second wins (the Side Bar announces its own expansion via
  // the nf:mobile-drawer-expanded event, handled below).
  if (isMobileViewport()) setSideBarCollapsed(true);
}

// The other half of the exclusivity pair: on a phone viewport the Side Bar
// drawer expanding closes the canvas drawer, so the file explorer (and every
// other Side Bar face) is never hidden under the canvas drawer.
document.addEventListener('nf:mobile-drawer-expanded', () => {
  if (document.body.classList.contains('canvas-open')) closeCanvas();
});

/** Close the canvas panel (hide visually — tabs are preserved).
 *
 *  Mirrors the sidebar pattern: remove body class, CSS animates back to 0.
 *  Tabs remain in memory and localStorage so they can be restored on reopen. */
export function closeCanvas() {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;

  // canvas-stack：面板收起前先静默收掉叠卡层（层是 body 级固定元素，不随面板消失）。
  if (deck.open) deckShutdown(true);

  // Persist the current width so a later reopen returns to the same size.
  const w = document.documentElement.style.getPropertyValue('--canvas-width');
  if (w) {
    try {
      const data = JSON.parse(localStorage.getItem(LS_KEY) || '{}');
      data.canvas = parseFloat(w);
      localStorage.setItem(LS_KEY, JSON.stringify(data));
    } catch (_) {}
  }

  // Clear any inline flex pin so CSS transition takes effect.
  panel.style.flex = '';

  // Remove body class — CSS animates flex-basis + opacity back to 0.
  document.body.classList.remove('canvas-open');
  // The overlay form is a property of the OPEN panel only; drop it with the
  // panel so a closed panel carries no layout mode into the next open.
  document.body.classList.remove('canvas-overlay');
  syncCanvasPanelButtons();

  // Tabs are preserved — closing Canvas just hides the panel visually.
  closeTimeout = setTimeout(() => {
    closeTimeout = null;
  }, 350);
}

/** Check whether the canvas is currently visible.
 *  @returns {boolean} */
export function isCanvasOpen() {
  return document.body.classList.contains('canvas-open');
}

// ── Activity Bar panel buttons (Teams / Flows / Agents) ────
// Author's 2026-08-30 spec: each button drives its Canvas tab through a
// 4-state machine:
//   1. tab missing              -> openFn() (creates + activates the tab)
//   2. tab open + Canvas open + currently displayed -> closeTab (toggle off;
//      closing the last tab keeps the existing auto-close-Canvas semantics)
//   3. tab open + Canvas open + another tab shown   -> setActiveTab
//      (+ panelTabRefresh: 切回即补一次该页签自己的刷新入口; 见下)
//   4. tab open + Canvas closed -> openCanvas + setActiveTab
// Pressed state (.active / aria-pressed) strictly mirrors "this tab IS the
// visible Canvas content": Canvas closed or another tab displayed -> never
// pressed, even when the tab still exists.
/** @type {Map<string, {buttonId: string}>} */
const panelButtons = new Map();
/** @type {Set<string>} buttonIds whose click handler is already bound. */
const boundPanelButtons = new Set();

/**
 * Register an Activity Bar button as a Canvas panel toggle.
 * @param {string} tabId    — the Canvas tab id this button controls
 * @param {string} buttonId — the Activity Bar button element id
 * @param {() => void} openFn — opens the panel (state 1 entry point)
 */
export function registerCanvasPanelButton(tabId, buttonId, openFn) {
  if (!tabId || !buttonId || typeof openFn !== 'function') return;
  panelButtons.set(tabId, { buttonId });
  const btn = document.getElementById(buttonId);
  if (btn && !boundPanelButtons.has(buttonId)) {
    boundPanelButtons.add(buttonId);
    btn.addEventListener('click', () => onCanvasPanelButtonClick(tabId, openFn));
  }
  syncCanvasPanelButtons();
}

/** @param {string} tabId @param {() => void} openFn */
function onCanvasPanelButtonClick(tabId, openFn) {
  if (!tabs.has(tabId)) { openFn(); return; }                       // state 1
  if (!isCanvasOpen()) { openCanvas(); setActiveTab(tabId); return; } // state 4
  if (activeTabId === tabId) { closeTab(tabId); return; }           // state 2 (toggle off)
  setActiveTab(tabId);                                              // state 3
  refreshPanelTabOnActivate(tabId);                                 // state 3 + 补拉（D 项）
}

// ── state 3 补拉注册表（D 项 fast-follow）────────────────────────────────────
// 背景（取证报告 §C-4「第二成因」+ 表行 D）：4 态机的 state 3 原先只做
// `setActiveTab`——从别的页签切回 `projects` 时面板**不取数**，用户看到的是上次渲染的
// 陈旧列表（唯一的手动刷新 = 关掉页签再开；见 `projectTab.js` 头注 (c) 段）。
// 契约（三件，均由本文件内的注册表集中表达）：
//   ① 值 = 该页签**既有**刷新入口的取用（🔴 本文件不新造取数实现：不出现任何
//      fetch / REST 路径 / 第二套渲染）；
//   ② 只在 state 3 触发——state 1 的 openFn 自带首取、state 2 = 收起、state 4（Canvas
//      折叠态重开）不在本批范围（报告「未做」项有据）；
//   ③ 未注册的页签零行为变化（`projects` 之外的面不受影响）。
// 为什么动态 import 而非静态 import：`projectTab.js:27` 静态 import 本文件 ⇒ 反向的静态边
// 会构成 2 节点 SCC（`scripts/check-circular.mjs` 判 FAIL）；动态边是该门禁明文许可的破环路径
// （同 `:167` `:405` 既有用法）。
// 为什么不是直接调 openFn：`openProjectsTab()` 会把 pane 从「就地 Flow Map 视图」重置回列表
// 并重建全部卡片（重放入场动画 + 「新」标记）——超出「补一次取数」的范围；量化对照见批报告
// §偏离申报（harness `alt` 变体）。
/** @type {Map<string, () => unknown>} tabId -> 该页签既有刷新入口 */
const panelTabRefresh = new Map([
  ['projects', () => import('./projectTab.js').then((m) => m.rerenderProjectsTab())],
  ['wiki-graph', () => import('./wikiGraph.js').then((m) => m.rerenderWikiGraph())],
]);

/** state 3 补拉（D 项）：切回已在册页签时补一次该页签自己的刷新入口。
 *  幂等 / 不叠加：入口自身为防抖合并（`projectTab.js` `rerenderProjectsTab` 200ms 窗）⇒
 *  高频重复切回只落一次取数；未注册页签静默返回（不打断页签切换本身），入口抛错只留一条
 *  `console.warn`（不冒泡成页面错误）。 */
function refreshPanelTabOnActivate(tabId) {
  const refresh = panelTabRefresh.get(tabId);
  if (typeof refresh !== 'function') return;
  Promise.resolve()
    .then(refresh)
    .catch((e) => console.warn('[canvas] panel tab refresh failed:', e));
}

/** Mirror "tab is the visible Canvas content" onto the registered buttons. */
function syncCanvasPanelButtons() {
  if (panelButtons.size === 0) return;
  const open = isCanvasOpen();
  for (const [tabId, def] of panelButtons) {
    const btn = document.getElementById(def.buttonId);
    if (!btn) continue;
    const on = open && tabs.has(tabId) && activeTabId === tabId;
    btn.classList.toggle('active', on);
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
  }
}

// ── Tab management ─────────────────────────────────────────

/** Dispose a closing tab's Monaco editor, freeing the shared model once no
 *  other live editor references it (models are never auto-freed — without
 *  this every file ever opened stays in memory). Tabs in rendered mode
 *  (markdown source toggle) have no mounted handle; their cached model is
 *  released by path instead. Safe for panes that never had an editor. */
function disposeTabEditor(entry) {
  // Text-stream tabs hold a live client (windows / index / server-side scan):
  // closing the tab releases the server side too (卡 §三.5 textCancel).
  const streamView = entry.paneEl?._streamClient;
  if (streamView) {
    if (typeof streamView.destroy === 'function') streamView.destroy();
    entry.paneEl._streamClient = null;
  }
  const handle = entry.paneEl?._editorHandle;
  if (handle) {
    handle.dispose({ disposeModel: true });
    entry.paneEl._editorHandle = null;
  } else if (entry.absPath) {
    import('./monacoEditor.js').then((m) => m.releaseModelIfUnused(entry.absPath));
  }
}

/** Open a tab — create it if it doesn't exist, then switch to it.
 *  Opens the canvas panel if it's not already open.
 *
 *  @param {string} id    — Unique tab identifier (e.g. 'flow', 'file:readme.md')
 *  @param {string} title — Display label in the tab button
 *  @param {object} opts  — { type: 'flow'|'markdown'|'code'|'html'|'generic', closable: bool }
 *  @returns {object|null} — The tab entry, or null on failure. */
export function openTab(id, title, opts = {}) {
  const { type = 'generic', closable = true, pinned = false } = opts;

  // Open the panel if it's not already visible.
  if (!isCanvasOpen()) openCanvas();

  // If tab already exists, just switch to it.
  if (tabs.has(id)) {
    // If opening as pinned, promote existing preview tab
    if (pinned) pinTab(id);
    setActiveTab(id);
    return tabs.get(id);
  }

  // VS Code preview behavior: opening a new preview tab replaces the
  // existing preview tab in the same group (if any). Pinned tabs are not
  // affected. Groups: all file tabs share 'file'; panel tabs are per-type.
  const previewKey = previewKeyOf(type, opts.absPath);
  if (!pinned) {
    const oldPreviewId = previewTabs.get(previewKey);
    if (oldPreviewId && oldPreviewId !== id) {
      const oldEntry = tabs.get(oldPreviewId);
      if (oldEntry) {
        disposeTabEditor(oldEntry);
        document.dispatchEvent(new CustomEvent('canvas-tab-closed', { detail: { id: oldPreviewId } }));
        oldEntry.paneEl.remove();
        oldEntry.tabEl.remove();
        tabs.delete(oldPreviewId);
        previewTabs.delete(previewKey);
        persistTabs();
      }
    }
  }

  const content = document.getElementById('canvas-content');
  const tabBar = document.getElementById('canvas-tab-bar');
  if (!content || !tabBar) return null;

  // Create the content pane.
  const pane = document.createElement('div');
  pane.className = 'canvas-tab-pane';
  pane.dataset.tabId = id;
  pane.dataset.type = type;
  content.appendChild(pane);

  // Create the tab button.
  const tab = document.createElement('div');
  tab.className = 'canvas-tab' + (pinned ? '' : ' preview');
  tab.dataset.tabId = id;
  tab.draggable = true;
  const closeHtml = closable
    ? `<button class="canvas-tab-close" title="Close"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M18 6L6 18M6 6l12 12"/></svg></button>`
    : '';
  tab.innerHTML = `<span class="canvas-tab-label">${title}</span>${closeHtml}`;

  // Drag-to-reorder (per-tab; click/dblclick/close are delegated to #canvas-tab-bar
  // in initCanvas — immune to listener loss on rebuilt/restored tabs).
  attachDragHandlers(tab, id);

  tabBar.appendChild(tab);
  // Auto-scroll the tab bar to show the newly added tab.
  tabBar.scrollTo({ left: tabBar.scrollWidth, behavior: 'smooth' });

  const entry = { id, title, type, paneEl: pane, tabEl: tab, closable, pinned,
                  absPath: opts.absPath || null,
                  // canvasdrop: the write root + request path the pane was
                  // opened with. The drop handler reuses them verbatim so its
                  // writeFile lands under the same containment root as the
                  // editor's own save (no root broadening).
                  rootPath: opts.rootPath || null,
                  filePath: opts.filePath || null };
  tabs.set(id, entry);

  // Track preview tab — will be replaced when a new tab in the same
  // preview group is opened
  if (!pinned) {
    previewTabs.set(previewKey, id);
  }

  setActiveTab(id);
  persistTabs();

  return entry;
}

/** Shared teardown core for BOTH close paths (hidden tab-strip × and the deck
 *  stack's swipe/context close, canvas-stack batch): dispose the tab's editor,
 *  fire canvas-tab-closed, delete the entry + preview registration, persist.
 *  Returns the removed entry (or null when the id was already gone). */
function teardownTab(id) {
  const entry = tabs.get(id);
  if (!entry) return null;
  disposeTabEditor(entry);
  document.dispatchEvent(new CustomEvent('canvas-tab-closed', { detail: { id } }));
  entry.paneEl.remove();
  entry.tabEl.remove();
  tabs.delete(id);
  for (const [k, v] of previewTabs) { if (v === id) previewTabs.delete(k); }
  persistTabs();
  deckNotifyRemoved(id);
  return entry;
}

/** Close a single tab and switch to an adjacent one.
 *  If the closed tab was the last one, closes the entire panel.
 *  Dispatches 'canvas-tab-closed' so external code (e.g. flowCanvas) can clean up.
 *  @param {string} id — Tab identifier. */
export function closeTab(id) {
  const entry = tabs.get(id);
  if (!entry) return;

  // Find the next tab to activate.
  const ids = [...tabs.keys()];
  const idx = ids.indexOf(id);
  const nextId = ids[idx + 1] || ids[idx - 1] || null;

  // Exit animation: fade + shrink via transform (GPU-accelerated, no reflow),
  // then remove from DOM. Remaining tabs reflow via their existing flex transition.
  const tabEl = entry.tabEl;
  tabEl.style.transition = 'opacity 0.15s ease, transform 0.15s cubic-bezier(0.4,0,0.2,1)';
  tabEl.style.opacity = '0';
  tabEl.style.transform = 'scale(0.7)';
  // Collapse the pane immediately
  if (entry.paneEl && entry.paneEl.parentNode) {
    entry.paneEl.style.opacity = '0';
    entry.paneEl.style.transition = 'opacity 0.12s ease';
  }

  const finalize = () => {
    teardownTab(id);
    // F2 (2026-08-30 作者裁定 + 方案 §8.4 F2): 关闭最后一个标签不再清空共享存档。
    // localStorage 是全浏览器共享的，多窗口下本窗口关最后一个标签即清空全局存档，
    // 其他仍开着的窗口重载后全部标签丢失（多窗口竞态）。语义改为「本窗口会话级清空」——
    // 共享存档保留，重启后从服务端/本地存档恢复。persistTabs 在 tabs.size===0 时早退，
    // 也不会向服务端写清空。

    if (activeTabId === id) {
      activeTabId = null;
      if (nextId) {
        setActiveTab(nextId);
      } else {
        closeCanvas();
      }
    }
    syncCanvasPanelButtons();
  };

  // Use transitionend if supported, fallback to timeout
  let done = false;
  const onEnd = () => { if (done) return; done = true; finalize(); };
  tabEl.addEventListener('transitionend', onEnd, { once: true });
  setTimeout(onEnd, 200);
}

/** Promote a preview (temporary) tab to pinned (permanent).
 *  VS Code behavior: preview tabs are replaced when opening another file;
 *  pinning makes the tab persistent. No-op if already pinned.
 *  @param {string} id — Tab identifier. */
export function pinTab(id) {
  const entry = tabs.get(id);
  if (!entry || entry.pinned) return;
  entry.pinned = true;
  entry.tabEl.classList.remove('preview');
  for (const [k, v] of previewTabs) { if (v === id) previewTabs.delete(k); }
}

/** Check whether a tab has unsaved edits — refresh must never clobber them.
 *  Three sources (OR): the Monaco handle's getDirty() (viewMonaco and MD/HTML
 *  source mode), the forwarded pane._dirty flag, and the 'dirty' class on the
 *  tab label (canonical UI indicator from the editor-dirty-change listener). */
function isTabDirty(entry) {
  if (!entry) return false;
  if (entry.paneEl?._editorHandle?.getDirty?.()) return true;
  if (entry.paneEl?._dirty) return true;
  return !!entry.tabEl?.querySelector('.canvas-tab-label')?.classList.contains('dirty');
}

/** Schedule a debounced readFile refresh for a file tab — another agent may
 *  have modified the file since the tab was rendered. The response flows back
 *  through fileContent → workspace-open-item → openWorkspaceItem, which
 *  re-renders the existing pane.
 *  Guards: panel tabs (no absPath), unsaved edits, MD/HTML source mode (an
 *  editor is mounted — re-render would destroy it), a refresh already in
 *  flight, and a cooldown window (also breaks the setActiveTab → readFile →
 *  re-render → setActiveTab loop). */
const FILE_REFRESH_DEBOUNCE = 300;
const FILE_REFRESH_COOLDOWN = 2000;
function scheduleFileRefresh(entry) {
  if (!entry.absPath) return;
  if (isTabDirty(entry)) return;
  if (entry.paneEl?.dataset.sourceMode === '1') return;
  if (entry._refreshing) return;
  if (Date.now() - (entry._lastRefreshAt || 0) < FILE_REFRESH_COOLDOWN) return;
  clearTimeout(entry._refreshTimer);
  entry._refreshTimer = setTimeout(() => {
    entry._refreshTimer = null;
    if (!tabs.has(entry.id)) return;  // tab closed while debouncing — don't reopen it
    if (isTabDirty(entry) || entry._refreshing) return;
    entry._refreshing = true;
    // Safety: never wedge the tab if the response never arrives
    setTimeout(() => { entry._refreshing = false; }, 10000);
    import('./ws.js').then(({ sendWs }) => {
      if (!sendWs) { entry._refreshing = false; return; }
      window.dispatchEvent(new CustomEvent('explorer-preload-pinned', {
        // refresh: true marks the readFile response as a background refresh —
        // openWorkspaceItem must not steal activation for it (and must not
        // reopen the tab if it was closed while the request was in flight).
        detail: { path: entry.absPath, pinned: entry.pinned !== false, refresh: true }
      }));
      sendWs({ type: 'pop.readFile', path: entry.absPath, sessionId: undefined });
    });
  }, FILE_REFRESH_DEBOUNCE);
}

/** Switch the active tab — shows its pane, hides all others.
 *  Also notifies Monaco editors when they become active (for Ctrl+S focus).
 *  @param {string} id — Tab identifier. */
export function setActiveTab(id) {
  if (!tabs.has(id)) return;
  activeTabId = id;
  tabs.forEach(t => {
    t.paneEl.classList.toggle('active', t.id === id);
    t.tabEl.classList.toggle('active', t.id === id);
    // Notify editor in the activated pane
    if (t.id === id && t.paneEl._editorHandle) {
      t.paneEl.dispatchEvent(new CustomEvent('canvas-tab-activated'));
    }
  });
  // Scroll the activated tab into view — with many tabs the active one can
  // be clipped out of the horizontal tab bar.
  const activated = tabs.get(id);
  if (activated?.tabEl) {
    activated.tabEl.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
  }
  document.dispatchEvent(new CustomEvent('canvas-tab-switched', { detail: { id } }));
  persistTabs();
  syncCanvasPanelButtons();

  // Live-refresh file tabs on activation (debounced, dirty-safe).
  const entry = tabs.get(id);
  if (entry) scheduleFileRefresh(entry);

  // canvas-stack：居中标题头随激活易主；叠卡开着时环重置到新 active 的前景位
  // （外部换签——agent 开文件 / 换入编排——都收敛到「新 canvas 在前景位」）。
  renderCanvasHeader();
  if (deck.open && !deck.finalizing && deck.ringActiveId !== id) deckRemountForActive();
}

/** Retarget open file tabs after an explorer drag-to-move: update absPaths,
 *  rewrite `file:<rel>` tab ids, and sync the mounted editor's save path so a
 *  later Cmd+S writes to the NEW location (save() captured opts.path).
 *  @param {string} oldRel — old explorer-relative path prefix
 *  @param {string} newRel — new explorer-relative path prefix
 *  @param {string} rootPath — explorer root (absolute, '' = default root) */
export function retargetFileTabs(oldRel, newRel, rootPath) {
  const root = (rootPath || '').replace(/\/+$/, '');
  const absOld = root ? root + '/' + oldRel : null;
  const absNew = root ? root + '/' + newRel : null;
  const relId = `file:${oldRel}`;
  let changed = false;
  for (const [id, entry] of [...tabs]) {
    const idMatch = id === relId || id.startsWith(relId + '/');
    const absMatch = !!(absOld && entry.absPath &&
      (entry.absPath === absOld || entry.absPath.startsWith(absOld + '/')));
    if (!idMatch && !absMatch) continue;
    // Retarget the stored absolute path. Prefix swap when the project root is
    // known; suffix swap when it is not — with the default project root the
    // explorer passes rootPath=null (server-resolved), so absOld cannot be
    // constructed. Explorer-opened tabs are keyed file:<rel> and their
    // absPath ends with that same relative path.
    if (entry.absPath) {
      if (absMatch) {
        entry.absPath = absNew + entry.absPath.slice(absOld.length);
      } else if (idMatch) {
        const tail = oldRel + id.slice(relId.length);
        const newTail = newRel + id.slice(relId.length);
        if (entry.absPath === tail || entry.absPath.endsWith('/' + tail)) {
          entry.absPath = entry.absPath.slice(0, entry.absPath.length - tail.length) + newTail;
        }
      }
    }
    if (idMatch) {
      const newId = `file:${newRel}` + id.slice(relId.length);
      tabs.delete(id);
      tabs.set(newId, entry);
      entry.id = newId;
      entry.tabEl.dataset.tabId = newId;
      entry.paneEl.dataset.tabId = newId;
      if (activeTabId === id) activeTabId = newId;
      for (const [k, v] of previewTabs) { if (v === id) previewTabs.set(k, newId); }
    }
    // Save path is form-sensitive: explorer-opened editors store the relative
    // path, source-toggle editors the absolute one — setPath picks correctly.
    entry.paneEl?._editorHandle?.setPath?.(newRel + (idMatch ? id.slice(relId.length) : ''), entry.absPath);
    changed = true;
  }
  if (changed) persistTabs();
}

/** Get the content pane element for a tab.
 *  External code (e.g. flowCanvas) uses this to render into their tab.
 *  @param {string} id — Tab identifier.
 *  @returns {HTMLElement|null} */
export function getTabPane(id) {
  return tabs.get(id)?.paneEl || null;
}

/** Check if a tab with the given id exists.
 *  @param {string} id — Tab identifier.
 *  @returns {boolean} */
export function hasTab(id) {
  return tabs.has(id);
}

/** The active tab's content pane — the Ctrl/Cmd+F file-search routing face
 *  (fileSearch.js): whether a file tab is displayed decides whether the
 *  keystroke searches file content or falls back to the chat history search.
 *  @returns {HTMLElement|null} */
export function activeCanvasPane() {
  const entry = activeTabId ? tabs.get(activeTabId) : null;
  return entry ? entry.paneEl : null;
}

// ── File viewers ───────────────────────────────────────────

/** Render a web URL into a tab pane: slim toolbar (hostname + "open in new
 *  window"), the sandboxed iframe (flex:1), and a permanent low-key hint
 *  strip below. X-Frame-Options blocking cannot be detected reliably — the
 *  iframe's load event fires even when the page refuses to embed — so the
 *  hint is always visible rather than conditional. All text goes through
 *  textContent (never innerHTML) since url/title are agent-controlled.
 *  @param {HTMLElement} pane — The tab content pane.
 *  @param {string} url      — The http(s) URL to embed. */
function renderUrlPane(pane, url) {
  // Only http(s) pages belong in an iframe — reject anything else
  // (javascript:, data:, file:) so the sandbox stays meaningful.
  if (!/^https?:\/\//i.test(url)) {
    const err = document.createElement('div');
    err.style.cssText = 'padding:24px;font-size:13px;color:var(--color-text-muted)';
    err.textContent = '无法显示该链接：仅支持 http/https 网页。';
    pane.appendChild(err);
    return;
  }

  // ── Top toolbar: hostname + "open in new window" fallback ──
  const bar = document.createElement('div');
  bar.style.cssText = 'flex:none;display:flex;align-items:center;gap:12px;' +
    'height:32px;padding:0 12px;min-width:0;' +
    'border-bottom:1px solid var(--color-border);' +
    'font-size:12px;color:var(--color-text-muted);user-select:none';

  const host = document.createElement('span');
  let hostText = url;
  try { hostText = new URL(url).hostname || url; } catch (_) { /* keep raw url */ }
  host.textContent = hostText;
  host.style.cssText = 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap';

  const openLink = document.createElement('a');
  openLink.href = url;
  openLink.target = '_blank';
  openLink.rel = 'noopener noreferrer';
  openLink.textContent = '在新窗口打开';
  openLink.style.cssText = 'flex:none;color:var(--color-text-muted);text-decoration:none;cursor:pointer';
  openLink.addEventListener('mouseenter', () => { openLink.style.color = 'var(--color-text)'; });
  openLink.addEventListener('mouseleave', () => { openLink.style.color = 'var(--color-text-muted)'; });

  bar.append(host, openLink);

  // ── The embedded page ──
  const iframe = document.createElement('iframe');
  iframe.src = url;
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-popups allow-forms');
  iframe.setAttribute('referrerpolicy', 'no-referrer-when-downgrade');
  iframe.setAttribute('allow', 'clipboard-read; clipboard-write');
  iframe.style.cssText = 'flex:1;min-height:0;width:100%;border:none;background:var(--color-surface)';

  // ── Bottom hint strip — always visible (embedding refusal is undetectable) ──
  const hint = document.createElement('div');
  hint.style.cssText = 'flex:none;display:flex;align-items:center;justify-content:center;gap:6px;' +
    'padding:4px 12px;font-size:11px;color:var(--color-text-muted);opacity:.7;user-select:none';
  const hintText = document.createElement('span');
  hintText.textContent = '如果页面没有显示，可能是该网站不允许嵌入';
  const hintLink = document.createElement('a');
  hintLink.href = url;
  hintLink.target = '_blank';
  hintLink.rel = 'noopener noreferrer';
  hintLink.textContent = '点击在新窗口打开';
  hintLink.style.cssText = 'flex:none;color:inherit;text-decoration:underline;cursor:pointer';
  hint.append(hintText, hintLink);

  // Best-effort blocked detection: a refused embed still fires load, and a
  // cross-origin frame legitimately has null contentDocument — so neither
  // can diagnose XFO. The only certain signal is no contentWindow at all;
  // on that (or onerror, which some embed failures do trigger) the hint is
  // emphasized. Everything else stays ambiguous and relies on the hint.
  const emphasizeHint = () => {
    hint.style.opacity = '1';
    hintText.textContent = '该网站似乎不允许嵌入显示';
  };
  iframe.addEventListener('error', emphasizeHint);
  iframe.addEventListener('load', () => {
    setTimeout(() => {
      try {
        if (!iframe.contentWindow) emphasizeHint();
      } catch (_) { /* cross-origin access may throw — expected, ignore */ }
    }, 3000);
  });

  pane.append(bar, iframe, hint);
}

// F3 (2026-08-30 作者裁定 + 方案 §8.4 F3): 「文件不可读」骨架标签的内容渲染。
// 用设计系统 CSS 变量，避免硬编码颜色；纯提示文案不进 Monaco 编辑器。
// 区分两类错误：路径非法/格式错误（path must be absolute / not a regular
// file / exceeds）vs 文件不存在或被清理（file not found）——避免「文件明明
// 在却说被清理」的矛盾提示。
function readErrorHint(errorMsg) {
  const m = (errorMsg || '').toLowerCase();
  if (m.includes('must be absolute') || m.includes('not a regular file') ||
      m.includes('exceeds') || m.includes('path is')) {
    return t('canvas.unreadablePathHint');
  }
  return t('canvas.unreadableHint');
}
function renderFilePlaceholder(paneEl, errorMsg, absPath) {
  paneEl.innerHTML = '';
  const wrap = document.createElement('div');
  wrap.className = 'file-unreadable';
  wrap.style.cssText = [
    'display:flex',
    'flex-direction:column',
    'gap:8px',
    'align-items:flex-start',
    'justify-content:center',
    'height:100%',
    'padding:24px',
    'color:var(--color-text-muted)',
    'font-size:14px',
  ].join(';');
  const iconRow = document.createElement('div');
  iconRow.textContent = t('canvas.unreadableTitle');
  iconRow.style.cssText = 'font-weight:600;color:var(--color-text);font-size:15px;';
  const path = document.createElement('div');
  path.textContent = absPath || '';
  path.style.cssText = 'font-family:var(--font-mono,monospace);font-size:12px;word-break:break-all;';
  const hint = document.createElement('div');
  hint.textContent = `${readErrorHint(errorMsg)}${errorMsg ? ' ' + errorMsg : ''}`;
  wrap.append(iconRow, path, hint);
  paneEl.appendChild(wrap);
}

/** Open a file or workspace item in a Canvas tab.
 *  Uses the file viewer registry for rendering all file types.
 *  Text files open in Monaco editor (editable). Binary files are read-only.
 *  @param {object} item — { id, itemType, title, content, absPath, size }
 */
export async function openWorkspaceItem(item) {
  // id is let (not const): the absPath dedupe below may rewrite it to the
  // existing tab's id for the same file.
  let { id } = item;
  const { itemType, title, content, absPath, size, pinned, anchor, warnings, stream, mtimeMs, readOnly } = item;
  if (!id) return;

  // URL type — render the page in a sandboxed iframe. Handled before all
  // file logic: URL tabs have no absPath (scheduleFileRefresh skips them)
  // and are excluded from persistTabs (a restored URL tab would be a dead
  // pane — nothing re-renders it).
  if (itemType === 'url') {
    if (!item.url) return;
    if (tabs.has(id)) {
      if (pinned) pinTab(id);
      tabs.get(id)._lastRefreshAt = Date.now();
      if (!item.background) setActiveTab(id);
      return;
    }
    const urlEntry = openTab(id, title || item.url, { type: 'url', pinned: !!pinned });
    if (!urlEntry) return;
    renderUrlPane(urlEntry.paneEl, item.url);
    urlEntry._lastRefreshAt = Date.now();
    return;
  }

  // F3 (2026-08-30 作者裁定 + 方案 §8.4 F3): readFile 失败的「文件不可读」骨架标签。
  if (item.error) {
    // 已有该路径标签（后台刷新失败）→ 保留原内容，不覆盖成错误提示。
    if (tabs.has(id)) {
      if (!item.background) setActiveTab(id);
      return;
    }
    const placeholderTitle = title || (absPath ? absPath.split('/').pop() : id);
    const entry = openTab(id, placeholderTitle, { type: 'code', pinned: !!pinned, absPath: absPath || id });
    if (!entry) return;
    renderFilePlaceholder(entry.paneEl, item.error, absPath || id);
    entry._lastRefreshAt = Date.now();
    if (!item.background) setActiveTab(id);
    return;
  }

  // Dedupe by absPath: a refresh/readFile response always arrives with the
  // canonical id `file:<path>`, but the same file may already be open under a
  // different id (Pop cards, agent-provided ids). Without this check every
  // activation-refresh of such a tab spawns a duplicate tab that steals
  // activation — the user perceives tab switching as broken. Rewrite the id
  // to the existing tab's so the existing-tab refresh path below handles it.
  if (!tabs.has(id) && absPath) {
    for (const [existingId, existing] of tabs) {
      if (existing.absPath === absPath) { id = existingId; break; }
    }
  }

  // Stale background refresh for a tab that was closed while the request was
  // in flight — drop it instead of reopening the tab.
  if (item.background && !tabs.has(id)) return;

  // If tab already exists: switch to it — and when this dispatch carries new
  // content (e.g. a refresh readFile response), re-render the existing pane.
  // Never re-render over unsaved edits or a mounted source-mode editor.
  if (tabs.has(id)) {
    if (pinned) pinTab(id);
    // D 项（2026-10-08 主人报「Pop 出的 SVG 媒体卡点击无反应」）：落在**既有
    // 页签**上的打开必须可见。本分支早退（不走 openTab ⇒ 无 openCanvas），原先
    // 只 setActiveTab —— 面板折叠时等于把激活写进隐藏面板，用户看到的是
    // 「点了没反应」；刷新后 restore 经 openTab 重开面板，才显得「刷新就好了」。
    // 补齐 state 4 语义（同文件 onCanvasPanelButtonClick :455 既有先例：
    // tab open + Canvas closed ⇒ openCanvas + setActiveTab）。background 刷新
    // 仍不抢激活（同下方 :1067 口径），面板开着的路径零变化。
    if (!item.background && !isCanvasOpen()) openCanvas();
    const entry = tabs.get(id);
    entry._refreshing = false;  // response arrived (or user-initiated open)
    // A response arrived = the file was freshly checked. Update unconditionally
    // — binary viewers (image/pdf/...) receive no content and never re-render,
    // so without this their _lastRefreshAt stays stale, the cooldown never
    // engages, and refresh→setActiveTab loops forever (each cycle stealing
    // activation back to the binary tab — "can't switch away from an image").
    entry._lastRefreshAt = Date.now();
    // Streamed large-text tab (卡 §三.3 后台刷新行): the response carries NO content,
    // so the re-render branch below never fires. Compare mtimeMs/size instead —
    // changed ⇒ the view voids its loaded windows + index and re-fetches (never a
    // silent mix of two revisions). Unchanged ⇒ nothing to do.
    if (stream) {
      const view = /** @type {any} */ (entry.paneEl)._streamClient;
      if (view && typeof view.acceptMeta === 'function') view.acceptMeta({ mtimeMs, size });
    }
    // wiki-graph batch: consume a stashed jump anchor here — a reference
    // click into an ALREADY-OPEN document rides the same readFile round trip
    // as a first open, and the anchor must survive into the re-render
    // (markdown.js consumes ctx.anchor). The stash is deleted unconditionally:
    // a jump into a source-mode tab does not fire later as a stale jump.
    const stash = absPath ? pendingRefAnchors.get(absPath) : undefined;
    if (absPath) pendingRefAnchors.delete(absPath);
    // Jump into a tab that is ALREADY open: this branch switched to it without
    // any re-render, so the anchor must be applied to the LIVE viewer here —
    // the markdown viewer keeps its analysis on the pane (nf-jump-anchor).
    const jumpIntent = anchor || stash;
    if (jumpIntent && !item.background) {
      if (entry.type === 'markdown' && entry.paneEl.dataset.sourceMode !== '1') {
        entry.paneEl.dispatchEvent(new CustomEvent('nf-jump-anchor', { detail: jumpIntent }));
      }
      const pgExisting = jumpIntent.pageStart;
      if (Number.isFinite(pgExisting)) /** @type {any} */ (entry.paneEl)._scrollToPage?.(pgExisting);
    }
    if (content && !isTabDirty(entry) && entry.paneEl.dataset.sourceMode !== '1') {
      // Skip the re-render when the live editor already shows this content —
      // remounting Monaco on every refresh would steal the cursor/scroll for
      // zero visible change (matters for focus-triggered refreshes).
      const live = entry.paneEl._editorHandle?.model?.getValue?.();
      if (live === undefined || live !== content) {
        if (entry.paneEl._editorHandle) {
          entry.paneEl._editorHandle.dispose();
          entry.paneEl._editorHandle = null;
        }
        const { renderFile } = await import('./fileViewers.js');
        await renderFile(entry.paneEl, { itemType, content, absPath, fileName: title, size, path: item.path, rootPath: item.rootPath, anchor: anchor || stash, warnings });
      }
    }
    // Background refresh responses must not steal activation — the user may
    // have clicked another tab while the request was in flight.
    if (!item.background) setActiveTab(id);
    // #303 C3: reference jump to an already-open tab — scroll the live viewer
    // (pdf.js exposes pane._scrollToPage) instead of re-rendering.
    const pgExisting = anchor && anchor.pageStart;
    if (Number.isFinite(pgExisting)) /** @type {any} */ (entry.paneEl)._scrollToPage?.(pgExisting);
    return;
  }

  // Re-open from chat Pop card click: content and itemType are empty but
  // absPath is provided. Fetch real content via readFile WS instead of
  // showing a blank tab. The fileContent handler will dispatch a new
  // workspace-open-item with full content and correct itemType.
  if (content === '' && itemType === '' && absPath) {
    // #303 C3: a document-reference jump carries a page anchor — stash it so
    // it survives the readFile round trip (the response item has no anchor)
    // and is consumed at render time below. The wiki-graph batch widened the
    // stash from PDF pageStart to the full anchor payload (block/heading/line).
    const pgStash = anchor && anchor.pageStart;
    if (anchor && (Number.isFinite(pgStash) || anchor.kind)) {
      pendingRefAnchors.set(absPath, anchor);
    }
    Promise.all([
      import('./ws.js'),
      import('./state.js')
    ]).then(([{ sendWs }, { default: state }]) => {
      // D 项（2026-10-08）：🔴 摘掉旧 `if (sendWs && sid)` 的 `&& sid` 静默吞——
      // pop.readFile 的后端派发（WsFileOpsHandlers.scala:44 → handlePopReadFile）
      // 只读 path、不读 sessionId，旧闸在 activeSessionId 为空时把整次打开静默
      // 蒸发（点击零反馈零日志）。对齐同文件另两个发送点（scheduleFileRefresh /
      // sendFileRestoreRequests 均不发会话闸）：sessionId 仅透传（无会话 ⇒
      // undefined）；WS 未连时 sendWs 自带有界排队、重连补发（ws.js sendWs）。
      if (sendWs) {
        window.dispatchEvent(new CustomEvent('explorer-preload-pinned', {
          detail: { path: absPath, pinned: !!pinned }
        }));
        sendWs({ type: 'pop.readFile', path: absPath, sessionId: state?.activeSessionId });
      }
    });
    return;
  }

  // VS Code preview behavior: if opening a preview tab and the current
  // preview tab in the same group is a different tab, close it first
  // (replace, not accumulate).
  // Use instant removal (no exit animation) so the new tab appears in the right
  // position immediately — animating the old tab's removal would leave the new
  // tab in the wrong position until the animation finishes.
  const itemPreviewKey = previewKeyOf(itemType, absPath);
  const oldPreviewId = previewTabs.get(itemPreviewKey);
  if (!pinned && oldPreviewId && oldPreviewId !== id) {
    const oldEntry = tabs.get(oldPreviewId);
    if (oldEntry) {
      disposeTabEditor(oldEntry);
      document.dispatchEvent(new CustomEvent('canvas-tab-closed', { detail: { id: oldPreviewId } }));
      oldEntry.paneEl.remove();
      oldEntry.tabEl.remove();
      tabs.delete(oldPreviewId);
      previewTabs.delete(itemPreviewKey);
      persistTabs();
    }
  }

  const entry = openTab(id, title || id, {
    type: itemType, pinned: !!pinned, absPath: absPath,
    rootPath: item.rootPath || null, filePath: item.path || null,
  });
  if (!entry) return;
  const pane = entry.paneEl;

  // Listen for dirty state changes from Monaco editor
  pane.addEventListener('editor-dirty-change', (e) => {
    const tabEl = entry.tabEl;
    const labelEl = tabEl.querySelector('.canvas-tab-label');
    if (labelEl) {
      if (e.detail.dirty) {
        labelEl.classList.add('dirty');
        // Editing a preview tab → promote to pinned (VS Code behavior)
        pinTab(id);
      } else {
        labelEl.classList.remove('dirty');
      }
    }
    // canvas-stack：脏标记同源投射——居中标题头绿点 + 该页签的环卡名称条绿点。
    renderCanvasHeader();
    deckRefreshDirty(id);
  });

  const { renderFile } = await import('./fileViewers.js');
  // #303 C3: consume a stashed reference-jump anchor (empty-content open) or
  // one carried directly on the item, and hand it to the viewer.
  const stashAnchor = absPath ? pendingRefAnchors.get(absPath) : undefined;
  if (absPath) pendingRefAnchors.delete(absPath);
  const renderAnchor = anchor || stashAnchor;
  // `objectUrl` is the blob-URL byte leg imgfix added to the render context
  // (consumed by viewers/{image,pdf,docx,xlsx,pptx,epub}.js as `objectUrl ||
  // await ticketUrl(absPath)`). It is a real runtime field but is not declared on
  // the shared ViewerContext typedef (utils.js), so the context is widened here
  // locally — typed only, the object passed to renderFile is unchanged.
  // `stream`/`mtimeMs` are the text-stream descriptor fields (卡 §三.4): also
  // widened here for the same reason — the viewer reads them, nothing else changes.
  /** @type {import('./utils.js').ViewerContext & {objectUrl?: string, stream?: any, mtimeMs?: number, readOnly?: boolean}} */
  const renderCtx = { itemType, content, absPath, fileName: title, size, path: item.path, rootPath: item.rootPath, anchor: renderAnchor, warnings, objectUrl: item.objectUrl, stream: item.stream || null, mtimeMs: item.mtimeMs || 0, readOnly: readOnly === true };
  await renderFile(pane, renderCtx);
  entry._lastRefreshAt = Date.now();  // just rendered — don't immediately re-fetch
}

// ═════════════════════════════════════════════════════════════════════════
// canvas-stack（v5 定稿落地）——标签行 → 叠卡引擎
//
// 样张 = reports/20261020_081631_canvas-card-stack-mockup-v5（作者批「就是这个效果」），
// 模型裁定照 v5 设计报告 §二全量移植：
//   · 整区缩放（v4①②）：.stacked 落 #canvas-panel，translateY(8px) scale(0.85)；
//   · 前景轮换（v5①）：环含全部页签（ring[0]=active=前景位），滚轮连续擦洗
//     （1/240 增益 + rAF 指数趋近），卡片循环穿过前景位，canvas 本尊经交接窗
//     与面板交叉淡换（d₀ 驱动位姿/alpha，no-trans 逐帧直写）；
//   · 浮钮固定（v5②）：fab 是 body 级固定元素（panel.contains(fab)===false），
//     JS 逐帧同步面板盒（top 10 / right 12 / 40px），z-250 恒在卡组与面板之上；
//   · 换入（v4④）：点前景卡放大回满幅 → setActiveTab 落位 → 旧 canvas ghost 缩回；
//   · 划走（v2⑥/v4⑥/v5①）：作用于前景面（canvas 前景=拖面板，环卡前景=拖该卡），
//     6px 点击界分 / 20% 区宽或 0.55px/ms 速度阈 / 13° 侧旋甩出 / spring 弹回；
//   · 关闭接管同 closeTab 语义（teardownTab 共核）；非前景卡关 = 环流就地收敛保相位；
//   · 本批三精修：①趋近 10/7→12/9、翻落曲线收幅（30/40/6/30→26/32/5/24）、
//     morph 时长统一 0.28s 档；②投影 token 浅一档（split.css --card-shadow）；
//     ③× 退役 → 竖排浅色滑动提示（.card-swipe-hint / .deck-swipe-hint）。
//
// 作者补充令（竖拖换卡）：上下拖动手势同可换卡（不只鼠标滚轮）——
//   · Pointer Events 纵向拖动驱动（pointerdown+move 的 dy 增量喂给同一环形 scrub
//     引擎：target += Δdy × 增益，跟手连续）；松手带少量惯性（EMA 速度 × 冲程、
//     钳 ±半卡）后 Math.round 贴靠最近卡（拖动手势不停半卡交叉态；滚轮保持连续
//     不贴靠）；触屏/触控板/鼠标拖动统一走 Pointer Events 一条通路；
//   · 与「滑动关闭」按主导轴分工：|dx|>|dy| 横向主导 = 划走关闭（原逻辑逐字保持），
//     否则纵向主导 = scrub 换卡（对角等幅平局从轻取纵——scrub 非破坏性）；
//   · 8px 死区内不锁轴（此前 <6px 视为点击的语义升格为「未锁轴=点击」）；单卡
//     （M<2）纵锁降级死轴静默（不做破坏性降级去触发关闭）；
//   · 既有让路规则原样保持：拖划起点落在可交互子树（Monaco/按钮/画布等）不启动，
//     与滚轮/键盘同一套让路面。
//
// 与样张的落地差异（deviations，均为真实 app 约束所需）：
//   · deck-layer / deck-foot / fab 为 body 层 fixed 元素逐帧同步面板盒（真实 app 的
//     面板挂 body flex 行，样张的 #canvas-area 包裹层不存在）；
//   · 卡面缩略为类型族 CSS mock（样张同款），面板页签副标「面板」= 无 absPath 页签；
//   · 面板拖划起点落在可交互子树（按钮/编辑器/画布等）时不启动（保护真实查看器交互）；
//   · 键盘/滚轮在可编辑焦点（输入框/Monaco）让路；Ctrl+滚轮（页面缩放语义）不拦截；
//   · 触摸可达性（竖拖补充令）：.deck-card 补 touch-action:none（卡面纯 chrome 无
//     滚动内容）；面板面触点常落在 pane 滚动容器内部 —— Chromium 取 touch-action
//     以滚动容器为界，.stacked 的 touch-action:none 够不着触点链，故 deck 态面板
//     touchstart 在非交互子树 preventDefault（原生滚动不启动、pointercancel 不打断
//     竖拖；交互子树照旧让路）—— 触屏/触控板/鼠标拖动统一走 Pointer Events 一条通路。
// ═════════════════════════════════════════════════════════════════════════

const DECK_SCALE = 0.85;
const DECK_SHIFT = 8;
// 精修①：擦洗/展开 rAF 指数趋近系数（样张 10 / 7 → 12 / 9，「跟手不拖沓」）
const DECK_SCRUB_APPROACH = 12;
const DECK_SPREAD_APPROACH = 9;
// 精修①：翻落支曲线收幅（样张 30/40 + 6/30 → 26/32 + 5/24，d=0 两侧连续不变）
const DECK_FALL_DROP = 26;
const DECK_FALL_ARC = 32;
const DECK_FALL_TILT = 5;
const DECK_FALL_TILT_ARC = 24;
// 精修①：FX/morph 时长统一 0.28s 档（样张 0.32/0.3/0.24/0.26 → 0.30/0.28/0.22/0.24）
const DECK_FLY_DUR = 0.30;
const DECK_GHOST_DUR = 0.28;
const DECK_SPRING_DUR = 0.22;
const DECK_FLING_DUR = 0.24;
const DECK_SHRINK_DUR = 0.26;
const DECK_WHEEL_GAIN = 1 / 240;
// 竖拖补充令：纵向拖动增益（1px 指纹 = 1/160 卡，比滚轮灵敏一档——滚轮有档位
// 棘感、指纹行程更长）；松手惯性 = EMA 速度(px/ms) × 90px 冲程，钳 ±80px（≤半卡，
// 快甩不越格不可控）；主导轴判定阈值（累计位移超 8px 才锁轴，锁后不换轴）。
const DECK_DRAG_GAIN = 1 / 160;
const DECK_DRAG_INERTIA = 90;
const DECK_DRAG_INERTIA_MAX = 80;
const DECK_AXIS_LOCK_PX = 8;

const deckRm = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : { matches: false };

const DECK_ICONS = {
  markdown: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="5" width="19" height="14" rx="3"/><path d="M6 15v-6l3 3.4L12 9v6"/><path d="M18 9v6m0 0-2.2-2.3M18 15l2.2-2.3"/></svg>',
  code: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m8.5 8-4 4 4 4"/><path d="m15.5 8 4 4-4 4"/></svg>',
  sheet: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="4" width="17" height="16" rx="2.5"/><path d="M3.5 9.5h17M3.5 15h17M9.5 4v16M15.5 4v16"/></svg>',
  image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="9" cy="9.5" r="1.6"/><path d="m4 18 5.2-5.2a1.5 1.5 0 0 1 2.1 0L20 20"/></svg>',
  doc: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 16.5h4"/></svg>',
  flow: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2.8" width="6" height="5" rx="1.4"/><rect x="3" y="16" width="6" height="5" rx="1.4"/><rect x="15" y="16" width="6" height="5" rx="1.4"/><path d="M12 7.8v3.4M6 16v-2.2a1.6 1.6 0 0 1 1.6-1.6h8.8A1.6 1.6 0 0 1 18 13.8V16"/></svg>',
  tasks: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="4" width="17" height="16" rx="2.5"/><path d="m7 9.5 1.6 1.6L11.5 8"/><path d="M14 10h3.5M7 15.5h3M13.5 15.5h4"/></svg>',
  web: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17"/><path d="M12 3.5c2.6 2.3 4 5.2 4 8.5s-1.4 6.2-4 8.5c-2.6-2.3-4-5.2-4-8.5s1.4-6.2 4-8.5Z"/></svg>',
  generic: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"/><path d="M14 3v5h5"/></svg>',
  deck: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m12 2.5 8.5 4.8L12 12 3.5 7.3 12 2.5Z"/><path d="m3.5 12.2 8.5 4.8 8.5-4.8"/><path d="m3.5 17.1 8.5 4.8 8.5-4.8"/></svg>',
};

/** 卡面类型族（图标 + 缩略 mock 共用）。真实 itemType 值域宽（查看器注册表），
 *  先按 type 精确映射、再按文件名扩展兜底，其余落 generic。 */
function deckTypeFamily(entry) {
  const type = (entry?.type || '').toLowerCase();
  const name = entry?.title || '';
  if (type === 'markdown') return 'markdown';
  if (type === 'code' || type === 'json') return 'code';
  if (type === 'html' || type === 'url') return 'web';
  if (type === 'image') return 'image';
  if (type === 'pdf' || type === 'epub' || type === 'docx' || type === 'pptx') return 'doc';
  if (type === 'sheet' || type === 'xlsx' || type === 'xls' || type === 'csv') return 'sheet';
  if (type === 'flow' || type === 'flow-run' || type === 'wiki-graph') return 'flow';
  if (type === 'projects' || type === 'tasks' || type === 'teams' || type === 'agents') return 'tasks';
  if (/\.(md|markdown)$/i.test(name)) return 'markdown';
  if (/\.(png|jpe?g|gif|webp|svg|avif)$/i.test(name)) return 'image';
  if (/\.pdf$/i.test(name)) return 'doc';
  if (/\.(xlsx|xls|csv)$/i.test(name)) return 'sheet';
  if (/\.(js|mjs|cjs|ts|tsx|scala|java|py|go|rs|rb|c|cpp|h|css|scss|json|ya?ml|sh|toml|sql)$/i.test(name)) return 'code';
  if (/^https?:\/\//i.test(name)) return 'web';
  return 'generic';
}
function deckIconOf(family) { return DECK_ICONS[family] || DECK_ICONS.generic; }

/** 卡面缩略 mock（样张 thumb 族原样；文本/代码=行条，sheet=网格，image=渐变山形，
 *  doc=页，flow=拓扑，web/默认=浏览器块）。 */
function deckThumbMock(family) {
  switch (family) {
    case 'markdown':
      return '<div class="thumb-pad"><div class="ln strong w40"></div><div class="ln w100"></div><div class="ln w85"></div><div class="ln w55"></div><div class="ln w70"></div><div class="ln w30"></div><div class="ln w100"></div></div>';
    case 'code':
      return '<div class="thumb-pad"><div class="ln w40"></div><div class="ln w70"></div><div class="ln w55"></div><div class="ln w85"></div><div class="ln w30"></div><div class="ln w70"></div><div class="ln w55"></div></div>';
    case 'sheet':
      return '<div class="thumb-grid"><div class="ghead"></div></div>';
    case 'image':
      return '<div class="thumb-img"><svg viewBox="0 0 120 60" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M8 52 40 22l22 20 18-14 32 24"/></svg></div>';
    case 'doc':
      return '<div class="thumb-page"><div class="ln strong w40"></div><div class="ln w100"></div><div class="ln w85"></div><div class="ln w100"></div><div class="ln w55"></div></div>';
    case 'flow':
      return '<div class="thumb-flow"><svg viewBox="0 0 120 70" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><rect x="45" y="4" width="30" height="14" rx="4"/><rect x="8" y="50" width="30" height="14" rx="4"/><rect x="82" y="50" width="30" height="14" rx="4"/><path d="M60 18v14M60 32H23v18M60 32h34v18"/></svg></div>';
    case 'tasks':
      return '<div class="thumb-pad"><div class="ln w55"></div><div class="ln w70"></div><div class="ln w40"></div><div class="ln w85"></div></div>';
    default:
      return '<div class="thumb-browser"><div class="bar"><i></i><i></i><i></i></div><div class="blk"></div><div class="blk b2"></div><div class="blk"></div></div>';
  }
}

// ── 引擎状态 ──
const deck = {
  open: false,
  finalizing: false,
  ring: [],            // 环流（含 active，ring[0]=active=前景位初始 occupant）
  M: 0,                // 环流卡数
  ringActiveId: null,  // 建环时的 active id（外部换签检测 → 环重置）
  pos: 0, target: 0,   // 环形浮点位置：d = wrapDelta(r − pos, M)，d=0 = 前景位
  d0s: 0,              // 面板驱动平滑 d₀（=wrapDelta(−pos, M)），防环绕翻折跳变
  spread: 0, spreadT: 0,
  selFly: null,        // { el, entry, k } 换入飞行
  ghostFx: null,       // { el, k } 旧 canvas 缩回卡组
  faceFx: null,        // { mode, k, dx0, dir, endX } 面板 FX（划走/收拢）
  cardDrag: null,      // 前景环卡拖划
  drag: null,          // 缩放态面板拖划
  exitCardFx: null,    // 环卡 FX（甩出/弹回/收拢）
  suppressCardClick: 0,
  selfCardEl: null,    // active 页签的环卡（交接窗与面板交叉淡换）
  driveSuspend: false,
  panelFront: true,    // 面板是否处于前景位（|d₀|<0.5，驱动 is-front 提示类）
  sizeK: 1, cardW: 0, cardH: 0,
  raf: 0, lastT: 0, interacted: false,
};

let deckLayer = null;
let deckFab = null;
let deckFoot = null;
let deckHintEl = null;
let deckCountEl = null;
let deckTitleEl = null;
let deckTitleIconEl = null;
let deckTitleNameEl = null;

const deckMod = (x, n) => ((x % n) + n) % n;
function deckWrapDelta(x, n) { x = deckMod(x, n); return x > n / 2 ? x - n : x; }
const deckEaseOut = (k) => 1 - Math.pow(1 - Math.min(1, Math.max(0, k)), 3);
const deckClamp01 = (x) => Math.min(1, Math.max(0, x));
const deckSmooth = (t) => { t = deckClamp01(t); return t * t * (3 - 2 * t); };
function deckBusy() { return deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx; }
function cssEscapeV(s) { return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&'); }

/** 拖划/键盘在可交互子树让路（真实查看器的按钮/编辑器/画布交互优先于叠卡手势）。 */
function deckInteractiveTarget(t) {
  return !!(t instanceof Element && t.closest(
    'button, a, input, textarea, select, [contenteditable="true"], .monaco-editor, .cm-editor, ' +
    '.canvas-zoom-bar, .canvas-find-toggle, .canvas-ref-select, .canvas-source-toggle, ' +
    'canvas, video, iframe, .pdf-pages',
  ));
}

// ── 拖划手势公共腿（竖拖补充令）：面板面（deck.drag）与环卡面（deck.cardDrag）
//    共用同一套状态机 —— 死区→主导轴锁→分轴驱动，速度 EMA 横纵对称。 ──
/** 主导轴判定：|dx|>|dy| → 横（划走关闭，原逻辑）；否则纵（scrub 换卡）。对角等幅
 *  平局取纵 —— scrub 非破坏性，划走关闭销毁页签，疑则从轻。 */
function deckDominantAxis(dx, dy) { return Math.abs(dx) > Math.abs(dy) ? 'x' : 'y'; }
/** 竖拖 scrub 喂给（跟手连续）：与滚轮同引擎同钳位（pos±M），单卡（M<2）无环不转。 */
function deckScrubFeed(dy) {
  if (deck.M < 2) return;
  deck.target += dy * DECK_DRAG_GAIN;
  deck.target = Math.max(deck.pos - deck.M, Math.min(deck.pos + deck.M, deck.target));
  if (!deck.interacted) { deck.interacted = true; deckHintEl.classList.add('faded'); }
  deckStartLoop();
}
/** 竖拖收尾：少量惯性（钳 ±80px 冲程 = ±半卡）+ Math.round 贴靠最近卡。只由拖动
 *  松手调用；滚轮不贴靠（连续擦洗语义不变），两条输入各自收尾。注意单位：vy 为
 *  px/ms、冲程为 px，target 为「卡」——并入前必须乘 DECK_DRAG_GAIN 换算。 */
function deckScrubRelease(vy) {
  if (deck.M < 2) return;
  const flingPx = Math.max(-DECK_DRAG_INERTIA_MAX, Math.min(DECK_DRAG_INERTIA_MAX, vy * DECK_DRAG_INERTIA));
  deck.target += flingPx * DECK_DRAG_GAIN;
  deck.target = Math.round(deck.target);
  deck.target = Math.max(deck.pos - deck.M, Math.min(deck.pos + deck.M, deck.target));
  deckStartLoop();
}
/** 拖划状态工厂：dx/dy 之外补 dy 侧对称的 EMA 速度 + 主导轴锁（null=未锁=点击界内），
 *  feedDy = 纵轴已喂给引擎的累计位移（增量喂给防回滚段倒灌）。 */
function deckDragState(e) {
  return {
    id: e.pointerId, x0: e.clientX, y0: e.clientY, dx: 0, dy: 0, feedDy: 0,
    vx: 0, vy: 0, lastX: e.clientX, lastY: e.clientY, lastT: e.timeStamp,
    axis: null, wasDragged: false,
  };
}
/** move 公共腿：死区（≤8px 不锁轴）→ 主导轴一次锁定（M<2 时纵降级死轴 'n'：
 *  单卡无环可转，静默结束而非破坏性降级触发关闭）→ 横纵速度 EMA。返回当前轴。 */
function deckDragTrack(d, e) {
  const dx = e.clientX - d.x0, dy = e.clientY - d.y0;
  if (!d.axis && Math.max(Math.abs(dx), Math.abs(dy)) > DECK_AXIS_LOCK_PX) {
    d.axis = deckDominantAxis(dx, dy);
    d.wasDragged = true;
    if (d.axis === 'y' && deck.M < 2) d.axis = 'n';
  }
  const dtms = Math.max(1, e.timeStamp - d.lastT);
  d.vx = 0.8 * d.vx + 0.2 * ((e.clientX - d.lastX) / dtms);
  d.vy = 0.8 * d.vy + 0.2 * ((e.clientY - d.lastY) / dtms);
  d.lastX = e.clientX; d.lastY = e.clientY; d.lastT = e.timeStamp;
  return d.axis;
}
/** 纵轴增量喂给：本次事件累计 dy − 已喂部分 = 增量（锁轴那一刻的死区段一并补喂）。 */
function deckDragFeedVertical(d, e) {
  const total = e.clientY - d.y0;
  const step = total - d.feedDy;
  d.feedDy = total; d.dy = total;
  deckScrubFeed(step);
}

// ── DOM 装配（body 层固定三件：deck-layer（面板之前）+ foot/fab（面板之后））──
function initDeck() {
  const panel = document.getElementById('canvas-panel');
  if (!panel || deckLayer) return;

  deckLayer = document.createElement('div');
  deckLayer.id = 'deck-layer';
  deckFoot = document.createElement('div');
  deckFoot.id = 'deck-foot';
  deckFoot.innerHTML = '<div class="deck-hint">滚轮 / 上下拖动 换卡（连续擦洗 · 可回滚） · 点击前景卡放大回满幅换入 · 点深处卡翻到该卡 · 横向划走关闭 · ESC 收回</div><div class="deck-count"></div>';
  deckFab = document.createElement('button');
  deckFab.id = 'deck-fab';
  deckFab.className = 'deck-fab';
  deckFab.setAttribute('aria-pressed', 'false');
  deckFab.setAttribute('title', '叠卡切换（滚轮 / 上下拖动换卡 · 前景轮换 · 点击前景卡换入 · 横向划走关闭）');
  deckFab.setAttribute('aria-label', '叠卡切换');
  deckFab.innerHTML = DECK_ICONS.deck;
  deckHintEl = deckFoot.querySelector('.deck-hint');
  deckCountEl = deckFoot.querySelector('.deck-count');

  // 层序（dock 态全 z-auto，按 DOM 序）：deck-layer < #canvas-panel < foot/fab；
  // .over 抬 deck-layer z-60（overlay 形态 245）盖过面板（split.css）。
  document.body.insertBefore(deckLayer, panel);
  panel.after(deckFoot);
  deckFoot.after(deckFab);

  // 整区缩放基姿常量单一来源（样张 v4②）：CSS .stacked / 飞行落点 / ghost 起点 / 面板驱动位姿同源
  panel.style.setProperty('--stack-scale', String(DECK_SCALE));
  panel.style.setProperty('--stack-shift', DECK_SHIFT + 'px');

  // 精修③：面板面上的竖排滑动提示（× 退役）
  const slide = panel.querySelector('#canvas-slide') || panel;
  const panelHint = document.createElement('div');
  panelHint.className = 'deck-swipe-hint';
  panelHint.textContent = '滑动关闭';
  slide.appendChild(panelHint);

  deckFab.addEventListener('click', () => {
    if (deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx || deck.exitCardFx || deck.cardDrag) return;
    deck.open ? closeDeck() : openDeck();
  });
  deckLayer.addEventListener('click', (e) => {
    if (e.target === deckLayer && !deck.finalizing) closeDeck();   // 点空白（区外露缘）收回
  });
  deckLayer.addEventListener('contextmenu', (e) => { if (e.target === deckLayer) e.preventDefault(); });

  bindDeckInput();
  syncDeckBox();
  renderCanvasHeader();
}

// ── 居中标题头（v2①：常态头只留居中标题；deck 态兼作前卡卡顶名称条）──
function renderCanvasHeader() {
  if (!deckTitleEl) {
    deckTitleEl = document.getElementById('canvas-title');
    deckTitleIconEl = document.getElementById('canvas-title-icon');
    deckTitleNameEl = document.getElementById('canvas-title-name');
  }
  if (!deckTitleEl) return;
  const entry = activeTabId ? tabs.get(activeTabId) : null;
  const family = deckTypeFamily(entry);
  if (deckTitleIconEl) deckTitleIconEl.innerHTML = entry ? deckIconOf(family) : '';
  if (deckTitleNameEl) deckTitleNameEl.textContent = entry ? (entry.title || entry.id) : '画板';
  deckTitleEl.classList.toggle('is-dirty', !!(entry && isTabDirty(entry)));
  if (deckFab) deckFab.disabled = tabs.size === 0;
}

/** 脏标记同源投射到环卡名称条（editor-dirty-change 的叠卡腿）。 */
function deckRefreshDirty(id) {
  if (!deck.open || !deckLayer) return;
  const el = deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(id) + '"]');
  if (!el) return;
  const entry = tabs.get(id);
  el.classList.toggle('is-dirty', !!(entry && isTabDirty(entry)));
}

// ── 几何 ──
/** canvas 区基准盒 = 面板的布局盒（offset* 与 transform 无关）。叠卡态面板被
 *  scale(0.85)，getBoundingClientRect 会把缩放后的投影盒读回来——层/卡组几何
 *  若以它为基准就形成反馈回路（盒缩水 → 卡组随缩 → 露边带被裁）。布局盒恒定，
 *  且 app 根无滚动（100vh overflow hidden），视口坐标与布局坐标一致。 */
function deckPanelRect() {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return null;
  return {
    left: panel.offsetLeft,
    top: panel.offsetTop,
    width: panel.offsetWidth,
    height: panel.offsetHeight,
    right: panel.offsetLeft + panel.offsetWidth,
    bottom: panel.offsetTop + panel.offsetHeight,
  };
}
function layoutDeck() {
  const r = deckPanelRect();
  if (!r || !r.width || !r.height || !deckLayer) return;
  deck.cardW = r.width * DECK_SCALE;
  deck.cardH = r.height * DECK_SCALE;
  deck.sizeK = Math.min(1, deck.cardH / 300);
  deckLayer.style.setProperty('--card-w', deck.cardW.toFixed(0) + 'px');
  deckLayer.style.setProperty('--card-h', deck.cardH.toFixed(0) + 'px');
  const narrow = r.width < 400;
  document.body.classList.toggle('canvas-deck-narrow', narrow);
  deckFoot.classList.toggle('narrow', narrow);
}
/** body 层三件的盒同步（逐帧驱动；面板盒 = canvas 区）。 */
function syncDeckBox() {
  const r = deckPanelRect();
  if (!r || !r.width) return;
  const place = (el) => {
    el.style.left = r.left + 'px';
    el.style.top = r.top + 'px';
    el.style.width = r.width + 'px';
    el.style.height = r.height + 'px';
  };
  if (deckLayer) place(deckLayer);
  if (deckFoot) place(deckFoot);
  if (deckFab) {
    deckFab.style.top = (r.top + 10) + 'px';
    deckFab.style.right = (window.innerWidth - r.right + 12) + 'px';
  }
}

// ── 环流（v5①：含全部页签，ring[0]=active）──
function deckBuildRing() {
  const keys = [...tabs.keys()];
  const ai = Math.max(0, keys.indexOf(activeTabId));
  const r = [];
  for (let k = 0; k < keys.length; k++) {
    const entry = tabs.get(keys[(ai + k) % keys.length]);
    if (entry) r.push(entry);
  }
  return r;
}
function deckMakeCardEl(entry, r, cls) {
  const family = deckTypeFamily(entry);
  const card = document.createElement('div');
  card.className = 'deck-card' + (isTabDirty(entry) ? ' is-dirty' : '') + (cls ? ' ' + cls : '');
  card.dataset.r = String(r);
  card.dataset.tab = entry.id;
  card.innerHTML =
    '<div class="card-top">' +
      '<span class="n-icon">' + deckIconOf(family) + '</span>' +
      '<span class="n-dot"></span>' +
      '<span class="n-text">' + escapeDeckText(entry.title || entry.id) + '</span>' +
      '<span class="n-sub">' + (entry.absPath ? '' : '面板') + '</span>' +
    '</div>' +
    '<div class="card-thumb">' + deckThumbMock(family) + '</div>' +
    /* 精修③：× 退役 → 卡缘竖排浅色滑动提示（示意左右划走关闭） */
    '<div class="card-swipe-hint">滑动关闭</div>' +
    '<div class="card-shade"></div>';
  /* 点击：前景卡（|d|<0.5）= 放大回满幅换入；深处卡 = 翻到该卡（不换签） */
  card.addEventListener('click', () => {
    if (!deck.open || deckBusy() || deck.exitCardFx) return;
    if (performance.now() < deck.suppressCardClick) return;
    const rr = +card.dataset.r;
    const d = deck.M ? deckWrapDelta(rr - deck.pos, deck.M) : 1;
    if (Math.abs(d) < 0.5) deckSelectCard(rr);
    else { deck.target = deck.pos + d; deckStartLoop(); }
  });
  card.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    if (deck.finalizing || deck.selFly || deck.ghostFx) return;
    const live = tabs.get(entry.id);
    if (live) {
      showCanvasRefMenu(e.clientX, e.clientY, live, {
        allowNoRef: true,
        onClose: () => deckRequestClose(live.id),
        onCloseOthers: () => deckCloseOthers(live.id),
      });
    }
  });
  /* 前景环卡拖划（v2⑥ 回归 + 竖拖补充令）：主导轴分工 —— 横向主导 = 跟手划走 →
     越阈甩出关闭该文件 / 不足弹回；纵向主导 = scrub 换卡（dy 增量喂引擎，松手惯性+贴靠） */
  card.addEventListener('pointerdown', (e) => {
    if (!deck.open || deckBusy() || deck.exitCardFx || deck.cardDrag || deck.drag) return;
    if (e.button !== 0) return;
    if (card === deck.selfCardEl) return;                          // canvas 本尊的环卡不参与拖划
    const rr = +card.dataset.r;
    if (deck.M && Math.abs(deckWrapDelta(rr - deck.pos, deck.M)) >= 0.5) return;  // 只拖前景卡
    deck.cardDrag = { ...deckDragState(e), el: card, tabId: entry.id };
    card.setPointerCapture(e.pointerId);
  });
  card.addEventListener('pointermove', (e) => {
    if (!deck.cardDrag || e.pointerId !== deck.cardDrag.id || deck.cardDrag.el !== card) return;
    const d = deck.cardDrag;
    const axis = deckDragTrack(d, e);
    if (axis === 'x') d.dx = e.clientX - d.x0;                     // 跟手位移由 frame() 直写
    else if (axis === 'y') deckDragFeedVertical(d, e);             // 纵向主导：换卡（不关闭）
  });
  const endCardDrag = (e, cancelled) => {
    if (!deck.cardDrag || e.pointerId !== deck.cardDrag.id || deck.cardDrag.el !== card) return;
    const d = deck.cardDrag; deck.cardDrag = null;
    if (cancelled || !d.wasDragged) return;                        // 未锁轴（≤8px 死区）= 点击，交给 click
    deck.suppressCardClick = performance.now() + 350;
    if (d.axis === 'y') { deckScrubRelease(d.vy); return; }        // 纵向：惯性+贴靠换卡，永不触发关闭
    if (d.axis !== 'x') return;                                    // 死轴（单卡纵拖）：静默结束
    const rect = deckPanelRect();
    const far = Math.abs(d.dx) > rect.width * 0.20 || Math.abs(d.vx) > 0.55;
    deck.exitCardFx = far
      ? { el: d.el, tabId: d.tabId, k: 0, mode: 'fling', dx0: d.dx, dir: d.dx >= 0 ? 1 : -1, endX: 0 }
      : { el: d.el, tabId: d.tabId, k: 0, mode: 'spring', dx0: d.dx, dir: 0, endX: 0 };
    deckStartLoop();
  };
  card.addEventListener('pointerup', (e) => endCardDrag(e, false));
  card.addEventListener('pointercancel', (e) => endCardDrag(e, true));
  return card;
}
function escapeDeckText(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function deckMountRing() {
  if (!deckLayer) return;
  deckLayer.querySelectorAll('.deck-card').forEach((el) => el.remove());
  deck.ring = deckBuildRing();
  deck.M = deck.ring.length;
  deck.ring.forEach((entry, r) => deckLayer.appendChild(deckMakeCardEl(entry, r)));
  deck.ringActiveId = activeTabId;
  deck.selfCardEl = deck.M
    ? deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(activeTabId || '') + '"]')
    : null;
}
/** 外部换签收敛：环重置到新 active 的前景位（pos=0）。 */
function deckRemountForActive() {
  deckMountRing();
  deck.pos = deck.target = 0;
  deck.d0s = 0;
  deck.panelFront = true;
  if (deckLayer) deckLayer.classList.remove('over');
  deckUpdateCount();
  deckStartLoop();
}

// ── 位姿（v4③ 露边阶梯 + v5① 翻落支）──
function deckPoseOf(d) {
  if (d < 0) {
    /* 翻落支（精修①收幅；y 从前景基姿 DECK_SHIFT 起落，保证 d=0 两侧连续） */
    const a = Math.min(-d, 2.2);
    const arc = -d < 1 ? Math.sin(Math.PI * -d) : 0;
    const y = DECK_SHIFT + (DECK_FALL_DROP * a + DECK_FALL_ARC * arc) * deck.sizeK;
    const tilt = (DECK_FALL_TILT * a + DECK_FALL_TILT_ARC * arc) * deck.sizeK;
    const op = Math.max(0, 1 - (a - 0.12) * 0.42);
    return { y, z: 0, tilt, scale: 1, op, shade: Math.min(0.30, a * 0.12) };
  }
  const dd = Math.max(0, d);
  const a1 = Math.min(dd, 1);
  const a2 = Math.max(0, Math.min(dd, 4.5) - 1);
  const y = DECK_SHIFT - (22 * a1 + 6 * a2) * deck.sizeK;
  const tilt = -1.6 * Math.min(dd, 4) * deck.sizeK;
  const op = dd <= 1.2 ? 1 : Math.max(0, 1 - (dd - 1.2) * 0.30);
  return { y, z: 0, tilt, scale: 1, op, shade: Math.min(0.30, 0.11 * dd) };
}
/** 面板 alpha（前景位交接窗，v5① 原窗口）：d₀≥0 环卡回归→面板渐入；d₀<0 面板翻落→环卡接管 */
function deckPanelAlphaOf(d0) {
  return d0 >= 0 ? deckSmooth((0.50 - d0) / 0.40) : deckSmooth(1 - (-d0 - 0.08) / 0.32);
}
function deckLerpPose(a, b, e) {
  return {
    y: a.y + (b.y - a.y) * e, z: a.z + (b.z - a.z) * e,
    tilt: a.tilt + (b.tilt - a.tilt) * e, scale: a.scale + (b.scale - a.scale) * e,
    op: a.op + (b.op - a.op) * e,
  };
}
/* 落点/起点位姿（卡坐标系：卡基尺寸 = 面板盒 85% = 缩放态 canvas 尺寸） */
const DECK_FLY_LAND = { y: 0, z: 0, tilt: 0, scale: 1 / DECK_SCALE, op: 1 };
const DECK_GHOST_FROM = { y: DECK_SHIFT, z: 0, tilt: 0, scale: 1, op: 1 };
const DECK_GHOST_TO = { y: -34, z: -110, tilt: -8, scale: 0.94, op: 0 };

function deckPaintCard(el, p, zi) {
  el.style.zIndex = zi;
  el.style.opacity = p.op.toFixed(3);
  el.style.transform = 'translate(-50%,-50%)' +
    ' translate3d(0,' + p.y.toFixed(2) + 'px,' + p.z.toFixed(2) + 'px)' +
    ' rotateX(' + p.tilt.toFixed(2) + 'deg)' +
    (p.scale && p.scale !== 1 ? ' scale(' + p.scale.toFixed(4) + ')' : '');
  const shade = el.querySelector('.card-shade');
  if (shade) shade.style.opacity = (p.shade != null ? p.shade : 0).toFixed(3);
}
function deckSetRootFxStyle(panel, x, rot, op, scaleK) {
  panel.style.opacity = op.toFixed(3);
  panel.style.transform = 'translateY(' + DECK_SHIFT + 'px) scale(' + DECK_SCALE + ')' +
    ' translate3d(' + x.toFixed(1) + 'px,0,0) rotateZ(' + rot.toFixed(2) + 'deg)' +
    (scaleK && scaleK !== 1 ? ' scale(' + scaleK.toFixed(4) + ')' : '');
}

let deckLastCount = '';
function deckUpdateCount() {
  if (!deckCountEl) return;
  let s;
  const n = tabs.size;
  if (!n) s = '';
  else if (n < 2) s = '仅此一张 · 无卡组';
  else if (!deck.M) s = '';
  else {
    /* 前景位随擦洗轮换：计数行实时易主 = 轮换生效的可断言证据 */
    const f = deckMod(Math.round(deck.pos), deck.M);
    const ftab = deck.ring[f];
    const ntab = deck.ring[deckMod(f + 1, deck.M)];
    const idx = [...tabs.values()].indexOf(ftab);
    s = '前景 ' + (idx + 1) + '/' + n + ' · ' + ftab.title + ' · 下一张 ' + (ntab ? ntab.title : '—');
  }
  if (s !== deckLastCount) { deckLastCount = s; deckCountEl.textContent = s; }
}

function deckFrame(t) {
  const panel = document.getElementById('canvas-panel');
  if (!panel || !deckLayer) { deckStopLoop(); return; }
  const dt = Math.min(0.05, (t - deck.lastT) / 1000 || 0.016);
  deck.lastT = t;
  syncDeckBox();

  // 移动层接管（视口切换/模式翻转）：叠卡静默退场
  if (document.body.classList.contains('mobile-layer') && deck.open && !deck.finalizing) {
    deckShutdown(false);
    return;
  }

  const k = deckRm.matches ? 1 : 1 - Math.exp(-dt * DECK_SCRUB_APPROACH);
  deck.pos += (deck.target - deck.pos) * k;
  if (Math.abs(deck.target - deck.pos) < 0.0008) deck.pos = deck.target;
  const ks = deckRm.matches ? 1 : 1 - Math.exp(-dt * DECK_SPREAD_APPROACH);
  deck.spread += (deck.spreadT - deck.spread) * ks;
  if (Math.abs(deck.spreadT - deck.spread) < 0.004) deck.spread = deck.spreadT;

  /* 面板驱动（v5①）：位姿/透明度随 d₀ 走，与前景环卡在交接窗交叉淡换。
     驱动期间 no-trans（逐帧直写不吃 CSS 过渡）；回前景位清 inline 交还 .stacked。
     alpha→0 时 pointer-events 关断（隐没的面板不得拦截卡组点击）；
     deck-layer.over 随 alpha 自动抬/落。 */
  let panelA = 1;
  if (deck.open && !deck.finalizing && !deck.driveSuspend && deck.M) {
    const d0raw = deckWrapDelta(-deck.pos, deck.M);
    if (Math.abs(d0raw - deck.d0s) > deck.M / 2) deck.d0s = d0raw;
    else deck.d0s += (d0raw - deck.d0s) * k;
    if (Math.abs(d0raw - deck.d0s) < 0.001) deck.d0s = d0raw;
    if (Math.abs(deck.d0s) > 0.0005) {
      panelA = deckPanelAlphaOf(deck.d0s);
      const p0 = deckPoseOf(deck.d0s);
      panel.classList.add('no-trans');
      panel.style.transform = 'translateY(' + DECK_SHIFT + 'px) scale(' + DECK_SCALE + ')' +
        ' translate3d(0,' + (p0.y - DECK_SHIFT).toFixed(2) + 'px,' + p0.z.toFixed(2) + 'px)' +
        ' rotateX(' + p0.tilt.toFixed(2) + 'deg)';
      panel.style.opacity = panelA.toFixed(3);
    } else {
      deck.d0s = 0; panelA = 1;
      panel.classList.remove('no-trans');
      if (panel.style.transform) panel.style.transform = '';
      if (panel.style.opacity && panel.style.opacity !== '1') panel.style.opacity = '';
    }
    panel.style.pointerEvents = panelA < 0.02 ? 'none' : '';
    deckLayer.classList.toggle('over', panelA < 0.995);
  }
  const frontNow = Math.abs(deck.d0s) < 0.5;
  if (frontNow !== deck.panelFront) {
    deck.panelFront = frontNow;
    panel.classList.toggle('is-front', frontNow && deck.open);
  }

  /* 环流卡（前景位轮换） */
  const cards = deckLayer.querySelectorAll('.deck-card[data-r]:not(.ghost)');
  for (const el of cards) {
    const r = +el.dataset.r;
    const d = deck.M ? deckWrapDelta(r - deck.pos, deck.M) : 1;
    const p0 = deckPoseOf(d);
    let p = { y: DECK_SHIFT + (p0.y - DECK_SHIFT) * deck.spread, z: p0.z * deck.spread, tilt: p0.tilt * deck.spread,
              scale: 1, op: p0.op * deck.spread, shade: p0.shade };
    /* canvas 本尊的环卡：交接窗内与面板互补淡换（两表面位姿恒等，只有 face 交叉） */
    if (el === deck.selfCardEl) p.op *= (1 - panelA);
    if (deck.selFly && el === deck.selFly.el) {
      deck.selFly.k = Math.min(1, deck.selFly.k + dt / (deckRm.matches ? 0.05 : DECK_FLY_DUR));
      p = deckLerpPose(p, DECK_FLY_LAND, deckEaseOut(deck.selFly.k));
      deckPaintCard(el, p, '600');
      if (deck.selFly.k >= 1) { const done = deck.selFly; deck.selFly = null; deckDoSwap(done.entry); }
      continue;
    }
    let extra = '';
    if (deck.cardDrag && deck.cardDrag.el === el && deck.cardDrag.wasDragged && deck.cardDrag.axis === 'x') {
      extra = ' translate3d(' + deck.cardDrag.dx.toFixed(1) + 'px,0,0) rotateZ(' + (deck.cardDrag.dx * 0.028).toFixed(2) + 'deg)';
    }
    if (deck.exitCardFx && deck.exitCardFx.el === el) {
      const dur = deck.exitCardFx.mode === 'spring' ? DECK_SPRING_DUR : DECK_FLING_DUR;
      deck.exitCardFx.k = Math.min(1, deck.exitCardFx.k + dt / (deckRm.matches ? 0.05 : dur));
      const e = deckEaseOut(deck.exitCardFx.k);
      if (deck.exitCardFx.mode === 'fling') {
        const rect = deckPanelRect();
        const dist = rect.width * 0.6 + 240;
        extra += ' translate3d(' + (deck.exitCardFx.dx0 + deck.exitCardFx.dir * dist * e).toFixed(1) + 'px,0,0) rotateZ(' + (deck.exitCardFx.dir * 13 * e).toFixed(2) + 'deg)';
        p = { ...p, op: Math.max(0, p.op * (1 - e * 1.25)) };
        if (deck.exitCardFx.dir) deck.exitCardFx.endX = deck.exitCardFx.dir * dist;
      } else if (deck.exitCardFx.mode === 'spring') {
        const x = deck.exitCardFx.dx0 * (1 - e);
        extra += ' translate3d(' + x.toFixed(1) + 'px,0,0) rotateZ(' + (x * 0.028).toFixed(2) + 'deg)';
      } else { /* shrink（右键收拢） */
        p = { ...p, op: Math.max(0, p.op * (1 - e * 1.2)) };
        extra += ' scale(' + (1 - 0.12 * e).toFixed(4) + ')';
      }
      if (deck.exitCardFx.k >= 1) {
        const done = deck.exitCardFx; deck.exitCardFx = null;
        if (done.mode !== 'spring') deckSettleClose(done.tabId, done.mode === 'fling' ? done : null);
      }
    }
    deckPaintCard(el, p, String(100 - Math.round(Math.abs(d) * 10)));
    if (extra) el.style.transform += extra;
    el.classList.toggle('front', Math.abs(d) < 0.5);
    el.style.pointerEvents = (p.op > 0.05 && !deck.selFly && !deck.finalizing) ? 'auto' : 'none';
  }

  /* ghost 旧内容卡：从满幅位缩回堆叠深处（v4④） */
  if (deck.ghostFx) {
    deck.ghostFx.k = Math.min(1, deck.ghostFx.k + dt / (deckRm.matches ? 0.05 : DECK_GHOST_DUR));
    const e = deckEaseOut(deck.ghostFx.k);
    const p = deckLerpPose(DECK_GHOST_FROM, DECK_GHOST_TO, e);
    deckPaintCard(deck.ghostFx.el, { ...p, shade: 0.1 * (1 - e) }, '600');
    if (deck.ghostFx.k >= 1) { const g = deck.ghostFx; deck.ghostFx = null; g.el.remove(); deckFinishSwap(); }
  }

  /* 面板 FX：甩出 / 弹回 / 收拢（v4⑥；精修①时长 0.22/0.24/0.26） */
  if (deck.faceFx) {
    const dur = deck.faceFx.mode === 'spring' ? DECK_SPRING_DUR
      : deck.faceFx.mode === 'fling' ? DECK_FLING_DUR : DECK_SHRINK_DUR;
    deck.faceFx.k = Math.min(1, deck.faceFx.k + dt / (deckRm.matches ? 0.05 : dur));
    const e = deckEaseOut(deck.faceFx.k);
    if (deck.faceFx.mode === 'fling') {
      const rect = deckPanelRect();
      const dist = rect.width * 0.6 + 240;
      deckSetRootFxStyle(panel, deck.faceFx.dx0 + deck.faceFx.dir * dist * e, deck.faceFx.dir * 13 * e, Math.max(0, 1 - e * 1.25));
      if (deck.faceFx.dir) deck.faceFx.endX = deck.faceFx.dir * dist;
    } else if (deck.faceFx.mode === 'spring') {
      deckSetRootFxStyle(panel, deck.faceFx.dx0 * (1 - e), deck.faceFx.dx0 * (1 - e) * 0.028, 1);
    } else { /* shrink */
      deckSetRootFxStyle(panel, 0, 0, Math.max(0, 1 - e * 1.2), 1 - 0.14 * e);
    }
    if (deck.faceFx.k >= 1) {
      const done = deck.faceFx; deck.faceFx = null;
      panel.classList.add('no-trans');
      if (done.mode !== 'spring') deckSettleClose(activeTabId, done);
      else { panel.style.opacity = ''; panel.style.transform = ''; panel.classList.remove('no-trans'); }
    }
  }

  deckUpdateCount();
  const settled = deck.pos === deck.target && deck.spread === deck.spreadT &&
    !deck.drag && !deck.cardDrag && !deck.faceFx && !deck.selFly && !deck.ghostFx && !deck.exitCardFx;
  if (settled && (!deck.open || deck.finalizing)) { deckStopLoop(); return; }
  deck.raf = requestAnimationFrame(deckFrame);
}
function deckStartLoop() {
  if (!deck.raf) { deck.lastT = performance.now(); deck.raf = requestAnimationFrame(deckFrame); }
}
function deckStopLoop() {
  if (deck.raf) { cancelAnimationFrame(deck.raf); deck.raf = 0; }
}

// ── 进入 / 收回 ──
function openDeck() {
  const panel = document.getElementById('canvas-panel');
  if (!panel || deck.open || deck.finalizing || tabs.size === 0) return;
  if (!isCanvasOpen()) return;
  if (document.body.classList.contains('mobile-layer') || isMobileViewport()) return;
  deck.open = true; deck.interacted = false;
  deck.selFly = null; deck.ghostFx = null; deck.faceFx = null; deck.exitCardFx = null;
  deck.drag = null; deck.cardDrag = null; deck.suppressCardClick = 0;
  deckMountRing();
  deck.pos = deck.target = 0;          // 前景位 = 环首 = 当前 canvas
  deck.d0s = 0; deck.driveSuspend = false; deck.panelFront = true;
  deck.spread = 0; deck.spreadT = 1;
  panel.classList.add('stacked', 'is-front');
  panel.style.opacity = ''; panel.style.pointerEvents = '';
  deckLayer.classList.add('live');
  deckFoot.classList.add('live');
  layoutDeck();
  syncDeckBox();
  deckHintEl.classList.remove('faded');
  deckFoot.classList.add('on');
  deckFab.setAttribute('aria-pressed', 'true');
  deckUpdateCount();
  deckStartLoop();
}
/** 收回（v4⑤ + v5① 轮换态衔接）：面板位姿瞬时归位 + 卡组 .collapse 淡出交叉。 */
function closeDeck() {
  const panel = document.getElementById('canvas-panel');
  if (!panel || !deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
  deck.finalizing = true;
  deck.spreadT = 0;
  panel.classList.add('no-trans');
  panel.style.transform = '';          // 擦洗中途位姿瞬时归位（前景位基姿）
  panel.style.pointerEvents = '';
  requestAnimationFrame(() => requestAnimationFrame(() => {
    panel.classList.remove('no-trans');        // 恢复过渡：解除缩放 + alpha 交叉
    panel.style.opacity = '1';
    deckLayer.classList.add('collapse');
    deckFoot.classList.remove('on');
    panel.classList.remove('stacked', 'is-front');
    deckFab.setAttribute('aria-pressed', 'false');
  }));
  setTimeout(() => {
    deckLayer.querySelectorAll('.deck-card').forEach((el) => el.remove());
    deck.ring = []; deck.M = 0; deck.selfCardEl = null; deck.ringActiveId = null;
    deckLayer.classList.remove('collapse');
    deckLayer.classList.remove('over');
    deckLayer.classList.remove('live');
    deckFoot.classList.remove('live');
    panel.style.opacity = '';
    deck.open = false; deck.finalizing = false;
    deckStopLoop();
  }, deckRm.matches ? 30 : 300);
}
/** 静默折层（面板收起 / 移动层接管）：跳过动画直接清场。 */
function deckShutdown() {
  if (!deck.open) return;
  const panel = document.getElementById('canvas-panel');
  deckLayer.querySelectorAll('.deck-card').forEach((el) => el.remove());
  deck.ring = []; deck.M = 0; deck.selfCardEl = null; deck.ringActiveId = null;
  deck.selFly = null; deck.ghostFx = null; deck.faceFx = null; deck.exitCardFx = null;
  deck.drag = null; deck.cardDrag = null;
  deckLayer.classList.remove('collapse', 'over', 'live');
  deckFoot.classList.remove('live', 'on');
  if (panel) {
    panel.classList.remove('stacked', 'is-front', 'receiving', 'no-trans', 'restore');
    panel.style.opacity = ''; panel.style.transform = ''; panel.style.pointerEvents = '';
  }
  deckFab.setAttribute('aria-pressed', 'false');
  deck.open = false; deck.finalizing = false; deck.driveSuspend = false;
  deckStopLoop();
}

// ── 换入（v4④：点前景卡 = 放大回满幅；深处卡走滚轮翻到该卡，不换入）──
function deckSelectCard(r) {
  if (deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
  const entry = deck.ring[r];
  if (!entry) return;
  if (entry.id === activeTabId) {
    if (!deck.M || Math.abs(deckWrapDelta(0 - deck.pos, deck.M)) < 0.5) closeDeck();
    return;
  }
  const el = deckLayer.querySelector('.deck-card[data-r="' + r + '"]');
  if (!el) return;
  deck.finalizing = true;
  el.classList.add('fly');
  deck.selFly = { el, entry, k: 0 };
  deckLayer.classList.add('over');     // 飞行卡瞬时盖过面板根
  document.getElementById('canvas-panel').classList.add('receiving');
  deck.spreadT = 0;                    // 其余卡同步收回面板背后
  deckStartLoop();
}
function deckDoSwap(entry) {
  const panel = document.getElementById('canvas-panel');
  const oldId = activeTabId;
  if (deck.selFly && deck.selFly.el) deck.selFly.el.remove();
  deck.selFly = null;
  /* 旧 canvas 的环卡让位给 ghost（同一页签不双渲染） */
  if (deck.selfCardEl && deck.selfCardEl.dataset.tab === oldId) { deck.selfCardEl.remove(); deck.selfCardEl = null; }
  /* 标题交叉淡入 + 面板瞬时回满幅（被落位飞行卡严丝合缝盖住），内容随之切换 */
  if (deckTitleEl) deckTitleEl.classList.add('swap');
  setActiveTab(entry.id);
  panel.classList.add('no-trans');
  panel.classList.remove('stacked', 'receiving', 'is-front');
  panel.style.transform = ''; panel.style.opacity = ''; panel.style.pointerEvents = '';
  deckFab.setAttribute('aria-pressed', 'false');
  setTimeout(() => { if (deckTitleEl) deckTitleEl.classList.remove('swap'); }, deckRm.matches ? 0 : 140);
  void panel.offsetWidth;              // 强制应用 no-trans 后的满幅态
  panel.classList.remove('no-trans');
  /* 原 canvas（旧当前卡）缩回堆叠（ghost） */
  const oldEntry = tabs.get(oldId);
  if (oldEntry) {
    const g = deckMakeCardEl(oldEntry, '', 'ghost');
    deckLayer.appendChild(g);
    deck.ghostFx = { el: g, k: 0 };
  }
}
function deckFinishSwap() {
  deckLayer.classList.remove('over');
  deckLayer.querySelectorAll('.deck-card').forEach((el) => el.remove());
  deck.ring = []; deck.M = 0; deck.selfCardEl = null; deck.ringActiveId = null;
  deck.pos = deck.target = 0; deck.d0s = 0; deck.spread = 0; deck.spreadT = 0;
  deckLayer.classList.remove('live');
  deckFoot.classList.remove('live');
  deck.open = false; deck.finalizing = false; deck.driveSuspend = false;
  deckStopLoop();
}

// ── 关卡收尾：teardownTab 共核 + 邻位接管（右邻优先，同 closeTab）。
// canvas（前卡）被关 = 整环重置：新 canvas 顶上（pos=0）；非前景卡 = 就地收敛保相位。 */
function deckSettleClose(id, panelFx) {
  const entry = tabs.get(id);
  if (!entry) { deck.finalizing = false; return; }
  const ids = [...tabs.keys()];
  const idx = ids.indexOf(id);
  const nextId = ids[idx + 1] || ids[idx - 1] || null;
  const wasActive = activeTabId === id;
  const fx = panelFx || deck.faceFx;
  deck.faceFx = null;
  deck.finalizing = false;             // teardownTab→notify 与 setActiveTab→hook 都要在活态跑
  teardownTab(id);
  if (!tabs.size) {
    deckShutdown();
    closeCanvas();                     // 全关收面板（重开走既有入口）
    return;
  }
  if (wasActive) {
    activeTabId = null;
    if (nextId) setActiveTab(nextId);  // hook: deckRemountForActive（环重置 pos=0）
    else { closeCanvas(); return; }
    const panel = document.getElementById('canvas-panel');
    if (panel && fx && fx.mode !== 'spring') deckRestorePanel(panel, fx);
  } else {
    syncCanvasPanelButtons();
    renderCanvasHeader();
  }
  deckUpdateCount();
  deckStartLoop();
}
/** 环流就地收敛（v5①）：摘卡 → 环形旋转把 active 转回环首（pos 同步补偿，每张卡的
 *  d 不变 = 零重排跳动）→ 重挂 data-r。M 变化引起的环绕重分布只影响深处淡出区。 */
function deckSettleRingRemove(id) {
  const r = deck.ring.findIndex((t) => t.id === id);
  if (r < 0) { deckRemountForActive(); return; }
  const el = deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(id) + '"]');
  if (el) el.remove();
  deck.ring.splice(r, 1);
  deck.M = deck.ring.length;
  const s = deck.ring.findIndex((t) => t.id === activeTabId);
  if (s > 0) {
    deck.ring = deck.ring.slice(s).concat(deck.ring.slice(0, s));
    deck.pos -= s; deck.target -= s;
  }
  deck.ring.forEach((t, i) => {
    const e = deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(t.id) + '"]');
    if (e) e.dataset.r = String(i);
  });
  deck.selfCardEl = deck.M
    ? deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(activeTabId || '') + '"]')
    : null;
  deck.d0s = deck.M ? deckWrapDelta(-deck.pos, deck.M) : 0;
  deckUpdateCount();
  deckStartLoop();
}
/** teardownTab 挂钩：叠卡开着时的非 active 摘卡就地收敛（active 摘卡由
 *  setActiveTab 钩子整环重置）。 */
function deckNotifyRemoved(id) {
  if (!deck || !deck.open || deck.finalizing) return;
  if (id === activeTabId) return;
  deckSettleRingRemove(id);
}
/** 面板回弹复位（v4⑥）：从甩出位姿弹性回到整区缩放基姿（期间驱动挂起）。 */
function deckRestorePanel(panel, fx) {
  deck.driveSuspend = true;
  panel.classList.add('no-trans');
  panel.style.opacity = '0';
  const rect = deckPanelRect();
  panel.style.transform = 'translateY(' + DECK_SHIFT + 'px) scale(' + DECK_SCALE + ')' +
    ' translate3d(' + ((fx.endX || (fx.dir || 1) * (rect.width * 0.6 + 240))).toFixed(1) + 'px,0,0) rotateZ(' + ((fx.dir || 1) * 13).toFixed(2) + 'deg)';
  requestAnimationFrame(() => requestAnimationFrame(() => {
    panel.classList.remove('no-trans');
    panel.classList.add('restore');
    panel.style.opacity = '';
    panel.style.transform = '';
    setTimeout(() => { panel.classList.remove('restore'); deck.driveSuspend = false; }, deckRm.matches ? 20 : 340);
  }));
}

// ── 关闭请求（右键菜单；× 已随精修③退役）──
function deckRequestClose(id) {
  if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
  const panel = document.getElementById('canvas-panel');
  if (id === activeTabId) {
    // 关当前页签 = 面板收拢淡出（v4⑥ shrink 通路）
    deck.finalizing = true;
    panel.classList.add('no-trans');
    deck.faceFx = { mode: 'shrink', k: 0, dx0: 0, dir: 0, endX: 0 };
  } else {
    const el = deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(id) + '"]');
    if (!el) return;
    deck.exitCardFx = { el, tabId: id, k: 0, mode: 'shrink', dx0: 0, dir: 0, endX: 0 };
  }
  deckStartLoop();
}
function deckCloseOthers(keepId) {
  if (!deck.open || deck.finalizing) return;
  for (const [id, entry] of [...tabs]) {
    if (id === keepId) continue;
    disposeTabEditor(entry);
    document.dispatchEvent(new CustomEvent('canvas-tab-closed', { detail: { id } }));
    entry.paneEl.remove();
    entry.tabEl.remove();
    tabs.delete(id);
    for (const [k, v] of previewTabs) { if (v === id) previewTabs.delete(k); }
  }
  activeTabId = keepId;
  persistTabs();
  deckMountRing();                     // 环重置：canvas 前景（v5①）
  deck.pos = deck.target = 0; deck.d0s = 0; deck.panelFront = true;
  syncCanvasPanelButtons();
  renderCanvasHeader();
  deckUpdateCount();
  window.__showToast?.('已关闭其他页签', 'info');
  deckStartLoop();
}

// ── 输入绑定（滚轮 / 键盘 / 面板拖划〔主导轴：横=划走关闭 · 纵=scrub 换卡〕；
//    deck 层与卡片的监听在卡片工厂里，共用同一套拖划公共腿）──
function bindDeckInput() {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;

  // 滚轮 = 连续擦洗 + 前景轮换（v5①）：无阈值步进，滚多少动多少，可回滚。
  // 面板与 deck 层是兄弟层，wheel 在 document 收口；可编辑焦点让路；Ctrl+滚轮
  // （页面缩放语义）不拦截不擦洗。
  document.addEventListener('wheel', (e) => {
    if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
    if (e.ctrlKey) return;
    const tgt = e.target;
    if (tgt instanceof Element && (tgt.closest('input, textarea, [contenteditable="true"], .monaco-editor, .cm-editor'))) return;
    e.preventDefault();
    if (deck.M < 2) return;            // 单卡无环可转
    const dy = e.deltaY * (e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 100 : 1);
    deck.target += dy * DECK_WHEEL_GAIN;
    deck.target = Math.max(deck.pos - deck.M, Math.min(deck.pos + deck.M, deck.target));
    if (!deck.interacted) { deck.interacted = true; deckHintEl.classList.add('faded'); }
    deckStartLoop();
  }, { passive: false });

  // 键盘：↑↓ 连续擦洗 ±1；Enter 选中前景卡（= 换入；canvas 前景时 = 收回）；Esc 收回。
  document.addEventListener('keydown', (e) => {
    if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
    const tgt = e.target;
    if (tgt instanceof Element && (tgt.closest('input, textarea, select, [contenteditable="true"], .monaco-editor, .cm-editor'))) return;
    if (e.key === 'Escape') { e.preventDefault(); closeDeck(); }
    else if (e.key === 'ArrowDown' || e.key === 'ArrowRight' || e.key === 'PageDown') { e.preventDefault(); if (deck.M > 1) { deck.target += 1; deckStartLoop(); } }
    else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft' || e.key === 'PageUp') { e.preventDefault(); if (deck.M > 1) { deck.target -= 1; deckStartLoop(); } }
    else if (e.key === 'Enter') { e.preventDefault(); if (deck.M) deckSelectCard(deckMod(Math.round(deck.pos), deck.M)); }
  });

  // 缩放态面板拖划（v4⑥ + 竖拖补充令）：主导轴分工 —— 横向主导 = 跟手划走，越阈
  // 甩出关闭 / 不足弹回；纵向主导 = dy 增量喂 scrub 引擎换卡（跟手连续，松手惯性+
  // 贴靠最近卡）；位移 ≤8px 未锁轴视为点击（= 收回）。该面只在 canvas 前景位可触
  // （环卡前景时面板 pointer-events 关断、被前景卡覆盖）；起点落在可交互子树
  // （查看器按钮/编辑器/画布）时不启动——真实内容交互优先（让路规则竖拖同享）。
  panel.addEventListener('pointerdown', (e) => {
    if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx || deck.drag || deck.cardDrag || deck.exitCardFx) return;
    if (e.button !== 0) return;
    if (deckInteractiveTarget(e.target)) return;
    deck.drag = deckDragState(e);
    panel.setPointerCapture(e.pointerId);
    panel.classList.add('no-trans');
  });
  panel.addEventListener('pointermove', (e) => {
    if (!deck.drag || e.pointerId !== deck.drag.id) return;
    const d = deck.drag;
    const axis = deckDragTrack(d, e);
    if (axis === 'x') {                                            // 横向主导 = 划走关闭（原 v4⑥ 逻辑）
      d.dx = e.clientX - d.x0;
      deckSetRootFxStyle(panel, d.dx, d.dx * 0.028, 1);
      deckStartLoop();
    } else if (axis === 'y') {                                     // 纵向主导 = scrub 换卡（跟手连续）
      deckDragFeedVertical(d, e);
    }
  });
  const endPanelDrag = (e, cancelled) => {
    if (!deck.drag || (e && e.pointerId !== deck.drag.id)) return;
    const d = deck.drag; deck.drag = null;
    const rect = deckPanelRect();
    if (cancelled || !d.wasDragged || d.axis === 'n') {
      panel.classList.remove('no-trans');
      panel.style.transform = ''; panel.style.opacity = '';
      if (!cancelled && !d.wasDragged) closeDeck();   // 未锁轴 = 点击 canvas = 收回卡组（原界不变）
      return;                                          // 死轴（单卡纵拖）：复位静默，不关闭不擦洗
    }
    if (d.axis === 'y') {                              // 纵向主导：惯性 + 贴靠换卡，永不触发关闭
      deckScrubRelease(d.vy);
      return;
    }
    const far = Math.abs(d.dx) > rect.width * 0.20 || Math.abs(d.vx) > 0.55;
    if (far) deck.faceFx = { mode: 'fling', k: 0, dx0: d.dx, dir: d.dx >= 0 ? 1 : -1, endX: 0 };
    else deck.faceFx = { mode: 'spring', k: 0, dx0: d.dx, dir: 0, endX: 0 };
    deckStartLoop();
  };
  panel.addEventListener('pointerup', (e) => endPanelDrag(e, false));
  panel.addEventListener('pointercancel', (e) => endPanelDrag(e, true));

  // 触屏通路（竖拖补充令）：deck 态面板面 touchstart 在非交互子树内 preventDefault
  // —— 否掉原生滚动手势的启动，pointercancel 不再中途打断竖拖 scrub。只拦面板面
  // （触点常落在 pane 滚动容器内部，Chromium 取 touch-action 以滚动容器为界，
  // .stacked 的 touch-action:none 够不着触点链）；可交互子树（Monaco/查看器/按钮）
  // 照旧让路，原生滚动保留（让路规则 ③ 的触屏层）。touchstart preventDefault 不影响
  // Pointer Events 流（只拦截兼容鼠标事件合成与原生手势），tap 收回仍走 pointerup 通路。
  panel.addEventListener('touchstart', (e) => {
    if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
    if (deckInteractiveTarget(e.target)) return;
    e.preventDefault();
  }, { passive: false });

  // 右键菜单（deck 态面板 = 当前页签的菜单；含「关闭/关闭其他」，精修③后无 × 依赖）
  panel.addEventListener('contextmenu', (e) => {
    if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx) return;
    const entry = activeTabId ? tabs.get(activeTabId) : null;
    if (!entry) return;
    e.preventDefault();
    showCanvasRefMenu(e.clientX, e.clientY, entry, {
      allowNoRef: true,
      onClose: () => deckRequestClose(entry.id),
      onCloseOthers: () => deckCloseOthers(entry.id),
    });
  });

  // resize / 列宽拖拽 / overlay 形态翻转：重排 + 盒同步
  window.addEventListener('resize', () => {
    if (!deck.open) return;
    layoutDeck();
    deckStartLoop();
  });
  window.addEventListener('nebflow-col-resize', () => {
    if (!deck.open) return;
    layoutDeck();
    deckStartLoop();
  });
}

// ── 自动化/调试钩子（spec 断言通道，同 v5 样张 __demo.state() 口径）──
window.__canvasDeck = {
  open: () => openDeck(),
  close: () => closeDeck(),
  state: () => {
    const panel = document.getElementById('canvas-panel');
    const rect = deckPanelRect() || { width: 0, height: 0, top: 0, right: 0 };
    const n = tabs.size;
    const frontIdx = deck.M ? deckMod(Math.round(deck.pos), deck.M) : -1;
    const frontEntry = deck.M ? deck.ring[frontIdx] : null;
    return {
      open: deck.open, pos: deck.pos, target: deck.target, spread: deck.spread, spreadT: deck.spreadT,
      M: deck.M, activeId: activeTabId, cardW: deck.cardW, cardH: deck.cardH, sizeK: deck.sizeK,
      areaW: rect.width, areaH: rect.height,
      stackScale: DECK_SCALE, stackShift: DECK_SHIFT,
      faceFx: deck.faceFx ? deck.faceFx.mode : null, selFly: !!deck.selFly, ghost: !!deck.ghostFx,
      deckIsPanelChild: panel ? panel.contains(deckLayer) : null,
      fabIsPanelChild: panel ? panel.contains(deckFab) : null,
      frontIdx,
      frontName: frontEntry ? frontEntry.title : null,
      d0: deck.d0s,
      panelAlpha: !panel || panel.style.opacity === '' ? 1 : parseFloat(panel.style.opacity),
      deckOver: deckLayer ? deckLayer.classList.contains('over') : false,
      selfCardHidden: !deck.selfCardEl || parseFloat(deck.selfCardEl.style.opacity || '1') < 0.02,
      deckLayerClass: deckLayer ? deckLayer.className : '',
      panelClass: panel ? panel.className : '',
      rootTransform: panel ? getComputedStyle(panel).transform : 'none',
      ring: deck.ring.map((t) => t.title),
      tabs: [...tabs.values()].map((t) => t.title),
      tabCount: n,
      /* 竖拖补充令断言通道：活动拖划手势（面板面/环卡面 + 主导轴） */
      gesture: deck.drag
        ? { face: 'panel', axis: deck.drag.axis, dx: deck.drag.dx, dy: deck.drag.dy }
        : deck.cardDrag
          ? { face: 'card', axis: deck.cardDrag.axis, dx: deck.cardDrag.dx, dy: deck.cardDrag.dy }
          : null,
      /* 精修①/② + 竖拖补充令断言通道：常量单一来源 + token 现值 */
      consts: {
        scrubApproach: DECK_SCRUB_APPROACH, spreadApproach: DECK_SPREAD_APPROACH,
        fallDrop: DECK_FALL_DROP, fallArc: DECK_FALL_ARC, fallTilt: DECK_FALL_TILT, fallTiltArc: DECK_FALL_TILT_ARC,
        flyDur: DECK_FLY_DUR, ghostDur: DECK_GHOST_DUR, springDur: DECK_SPRING_DUR, flingDur: DECK_FLING_DUR,
        wheelGain: DECK_WHEEL_GAIN,
        dragGain: DECK_DRAG_GAIN, dragInertia: DECK_DRAG_INERTIA, dragInertiaMax: DECK_DRAG_INERTIA_MAX,
        axisLockPx: DECK_AXIS_LOCK_PX,
      },
      cardShadow: getComputedStyle(document.documentElement).getPropertyValue('--card-shadow').trim(),
    };
  },
};



// ── Initialization ─────────────────────────────────────────

/** Initialize canvas event listeners. Call on DOM ready. */
export function initCanvas() {
  const closeBtn = document.getElementById('canvas-close-btn');
  if (closeBtn) {
    closeBtn.addEventListener('click', closeCanvas);
  }

  // Event delegation on the tab bar (defensive fix for task L: per-tab listeners
  // could silently stop firing after long sessions — e.g. listener loss on tabs
  // rebuilt during restore/reorder). One delegated listener on the stable
  // #canvas-tab-bar container covers all tabs, present and future.
  const tabBar = document.getElementById('canvas-tab-bar');
  if (tabBar && !tabBar._delegated) {
    tabBar._delegated = true;

    // Click: close button → close tab; anywhere else on a tab → activate it.
    tabBar.addEventListener('click', (e) => {
      const tabEl = e.target.closest('.canvas-tab');
      if (!tabEl || !tabBar.contains(tabEl)) return;
      const id = tabEl.dataset.tabId;
      if (!id) return;
      if (e.target.closest('.canvas-tab-close')) {
        e.stopPropagation();
        closeTab(id);
      } else {
        setActiveTab(id);
      }
    });

    // Double-click tab → promote to pinned (VS Code behavior).
    tabBar.addEventListener('dblclick', (e) => {
      const tabEl = e.target.closest('.canvas-tab');
      if (!tabEl || !tabBar.contains(tabEl)) return;
      const id = tabEl.dataset.tabId;
      if (!id) return;
      e.stopPropagation();
      pinTab(id);
    });

    // #303 B2-B4: right-click a file/document tab → 「引用当前页/选区」.
    tabBar.addEventListener('contextmenu', (e) => {
      const tgt = /** @type {Element} */ (e.target);
      const tabEl = tgt.closest('.canvas-tab');
      if (!tabEl || !tabBar.contains(tabEl)) return;
      const id = /** @type {HTMLElement} */ (tabEl).dataset.tabId;
      const entry = id ? tabs.get(id) : null;
      if (!entry) return;
      e.preventDefault();
      e.stopPropagation();
      showCanvasRefMenu(e.clientX, e.clientY, entry);
    });
  }

  // canvasdrop: OS file dropped onto a pane → copy into `<fileDir>/assets/`
  // and insert a relative reference at the cursor; clipboard paste (⌘V /
  // screenshot) rides the same pipeline; an OS file dropped on the tab strip
  // (R6 ④) opens as a new tab. The resolver hands the drop handler the tab's own
  // absPath / write root / itemType; panel tabs (no file behind them) resolve to
  // null and get a visible "not editable here" notice.
  // canvas-stack：标签条在桌面退役后，替代落区 = 头部整条（居中标题条，
  // v1 备忘「标签条退役后需把 header 整条注册为替代落区」）。
  const canvasContent = document.getElementById('canvas-content');
  const canvasHeaderEl = document.querySelector('#canvas-panel .canvas-header');
  if (canvasContent) {
    initCanvasDrop(canvasContent, (pane) => {
      const tabId = pane.dataset.tabId;
      const entry = tabId ? tabs.get(tabId) : null;
      if (!entry) return null;
      return {
        absPath: entry.absPath || undefined,
        rootPath: entry.rootPath || null,
        itemType: entry.type,
      };
    }, canvasHeaderEl || tabBar);
  }

  // Listen for workspace-open-item events (dispatched on window by explorer.js).
  window.addEventListener('workspace-open-item', (e) => {
    if (e.detail) openWorkspaceItem(e.detail);
  });

  // Close the canvas tab context menu on any outside interaction (capture).
  if (!_ctxMenuBound) {
    _ctxMenuBound = true;
    document.addEventListener('mousedown', (e) => {
      if (_canvasCtxMenu && !_canvasCtxMenu.contains(e.target)) hideCanvasRefMenu();
    }, true);
    document.addEventListener('contextmenu', () => hideCanvasRefMenu(), true);
  }

  // canvas-stack（v5 定稿落地）：居中标题头首渲 + 叠卡引擎引导（层/浮钮/ foot 均
  // body 级固定元素，见 initDeck）。移动层有自己的卡片形态，引擎在其下不激活。
  renderCanvasHeader();
  initDeck();

  // Window-focus refresh (dispatched by explorer.js after reloading the tree):
  // re-check the active file tab for external edits. Dirty-guarded, debounced,
  // and unchanged content never re-renders (see openWorkspaceItem).
  window.addEventListener('explorer-focus-refresh', () => {
    const entry = activeTabId ? tabs.get(activeTabId) : null;
    if (entry) scheduleFileRefresh(entry);
  });

  // Listen for Pop tool WS messages — agent opens a file in Canvas.
  import('./ws.js').then(({ onMessage }) => {
    onMessage('popFile', (msg) => {
      if (msg.item) openWorkspaceItem(msg.item);
    });
  });

  // Responsive: maintain equal panel widths on resize, and re-decide the
  // layout form (side-by-side vs. overlay) — a resize can cross the threshold
  // in either direction, so the mode is evaluated BEFORE the width early-return
  // (a user-pinned width must not freeze the mode).
  window.addEventListener('resize', () => {
    if (!isCanvasOpen()) return;
    refreshCanvasLayoutMode();
    // Skip if user has manually pinned a specific width via col-resizer
    const panel = document.getElementById('canvas-panel');
    if (panel && panel.style.flex) return; // inline flex = user resized
    const target = computeOpenWidth();
    document.documentElement.style.setProperty('--canvas-width', target + 'px');
  });

  // The layout form also depends on the Side Bar's own width: collapsing it
  // frees exactly the room the side-by-side split needs, so the panel must be
  // able to flip back without a window resize. body class changes are the one
  // signal the sidebar toggle and the ⌘B shortcut funnel through, and a
  // resizer drag on the sidebar|main handle announces itself with
  // `nebflow-col-resize` (colResizer.js).
  // classList.toggle(name, force) only mutates on an actual state change, so
  // this observer cannot loop on its own write.
  const moLayout = new MutationObserver(() => {
    if (!isCanvasOpen()) return;
    if (document.body.classList.contains('canvas-overlay') !== isNarrowSplit()) {
      refreshCanvasLayoutMode();
    }
  });
  moLayout.observe(document.body, { attributes: true, attributeFilter: ['class'] });
  window.addEventListener('nebflow-col-resize', refreshCanvasLayoutMode);

  // The overlay form's top edge is derived from the top band's measured height,
  // and that band grows on its own (a task list with cards, a notification
  // banner, the pending-ask bar). Re-derive when the band resizes so the
  // published variable never goes stale against it.
  if (window.ResizeObserver) {
    const header = document.getElementById('header');
    const overlays = document.getElementById('top-overlays');
    const roBand = new ResizeObserver(() => {
      if (document.body.classList.contains('canvas-overlay')) applyCanvasLayoutMode();
    });
    if (header) roBand.observe(header);
    if (overlays) roBand.observe(overlays);
  }
}

// ── Tab persistence ─────────────────────────────────────────

/** Save open tabs (metadata only) to localStorage so they survive refresh.
 *  Stores: id, title, type, absPath (nullable), pinned, closable.
 *  Tabs without absPath (Teams/Flows panels) are restored synchronously
 *  and re-render from live state; file tabs re-fetch content on restore. */
function persistTabs() {
  // P1 (2026-09-10, 定案 §7): 不再对空表早退。关闭（含关闭最后一个）必须把当前
  // 集合写入双源——可以是空集 { v:2, tabs: [] }。旧 F2 早退让「关掉最后一个标签」
  // 一份都不写，服务端与 localStorage 都留旧记录，重启后被并集恢复整批重开
  // （定案 A6 缺口 A）。空存档语义 = 不恢复：restoreFromData / restoreTabs
  // 均有 tabs.length > 0 守卫，故写空集不会造成「关不掉的标签」。
  try {
    const serializable = [];
    for (const [, t] of tabs) {
      // Skip runtime flow-run tabs — their instanceId is invalid after a
      // restart, so restoring them would leave dead tabs.
      if (t.type === 'flow-run') continue;
      // Skip URL tabs — they have no absPath and restoreTabs' panel-tab
      // branch would re-open them as empty dead panes (canvas-tab-restore
      // has no renderer for type 'url'). Losing URL tabs across a reload is
      // acceptable per spec.
      if (t.type === 'url') continue;
      // Skip retired panel tabs (2026-09-05 旧 UI 退役): the legacy
      // Teams/Flows panels no longer exist client-side — persisting them
      // would re-open dead panes on every reload.
      if (t.type === 'teams' || t.type === 'flows') continue;
      // Skip delegate-diff tabs (2026-10-10 裁定): their payload lives in the
      // chat card (click re-opens the view); a restored pane would be empty —
      // canvas-tab-restore has no renderer that can refetch a batch payload.
      if (t.type === 'filechanges') continue;
      serializable.push({
        id: t.id,
        title: t.title,
        type: t.type,
        absPath: t.absPath || null,   // null for panel tabs (Teams/Flows)
        pinned: t.pinned,
        closable: t.closable !== false,  // default true
      });
    }
    const payload = { v: 2, tabs: serializable };
    // localStorage 仅作降级缓存（Safari 无痕/清空后仍可从服务端恢复）。
    localStorage.setItem(LS_TABS_KEY, JSON.stringify({
      v: 2,  // schema version — v1 (pre-unified-persistence) lacked closable
             // and skipped no-absPath tabs; restoreTabs discards non-v2 data
      tabs: serializable,
      activeTabId: activeTabId,
    }));
    console.log('[persistTabs] saved', serializable.length, 'tabs', serializable.map(t => t.id));
    // F1 (2026-08-30 作者裁定 + 方案 §8.4 F1): 服务端权威存档，防抖 PUT。
    scheduleServerPersist(payload);
  } catch (e) { /* storage full — non-critical */ }
}

// F1: 防抖合并连续操作成一次服务端 PUT。500ms 窗口内多次 persistTabs 只发一次。
// P2 (2026-09-10, 定案 §7): 防抖窗不再丢单——待发载荷留一份在 pendingPersistPayload，
// 页面离开/切后台时立即 flush（闭合 500ms 窗口）；PUT 失败记 persistDirty，连接
// 恢复时重放「当前集合」（闭合「服务端重启期间关闭」）。定案 A6 缺口 B。
let pendingPersistPayload = null;
let persistDirty = false;

function scheduleServerPersist(payload) {
  pendingPersistPayload = payload;
  if (serverPersistTimer) clearTimeout(serverPersistTimer);
  serverPersistTimer = setTimeout(() => {
    serverPersistTimer = null;
    const p = pendingPersistPayload;
    pendingPersistPayload = null;
    sendServerPersist(p);
  }, 500);
}

// P2: 发送一份存档载荷（防抖到期 / pagehide·visibilitychange flush 共用）。
// keepalive 供页面卸载路径——请求可在文档销毁后完成。不用 sendBeacon：它无法带
// Bearer 头，而 /api/canvas-tabs 走 withAuth（无 token 会 403）。
function sendServerPersist(payload, keepalive) {
  if (!payload) return;
  fetch(TABS_API, {
    method: 'PUT',
    keepalive: !!keepalive,
    // withAuth (RestApiRoutes) only accepts Bearer/query token — NOT cookie.
    // Without the header the PUT 403s and the service archive never persists.
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(payload),
  })
    .then((r) => {
      if (r.ok) { persistDirty = false; return; }
      persistDirty = true;
      console.warn('[canvas-tabs] server persist failed:', r.status);
    })
    .catch((e) => {
      persistDirty = true;
      console.warn('[canvas-tabs] server persist error:', e);
    });
}

// P2: 把防抖窗内待发的 PUT 立即发掉（页面卸载 / 切到后台）。
function flushPendingPersist() {
  if (serverPersistTimer) {
    clearTimeout(serverPersistTimer);
    serverPersistTimer = null;
  }
  const payload = pendingPersistPayload;
  pendingPersistPayload = null;
  sendServerPersist(payload, true);
}

// P2: 监听器与重放钩子只注册一次——模块顶层语句，ES module 单次求值即幂等保证。
// ws.js 必须动态 import：静态 import 会与 canvas.js 成环（仓内既定出口，
// 见 scripts/check-circular.mjs 注释），同 sendFileRestoreRequests 的做法。
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') flushPendingPersist();
});
window.addEventListener('pagehide', flushPendingPersist);

let persistReconnectBound = false;
function bindPersistReconnect() {
  if (persistReconnectBound) return;
  persistReconnectBound = true;
  import('./ws.js')
    .then(({ onReconnect }) => {
      onReconnect(() => {
        if (!persistDirty) return;
        // 重放「当前集合」而非旧载荷：persistTabs 重建载荷后走防抖 PUT。
        // 只在连接恢复时触发，不逐帧重试，无自旋/日志风暴。
        persistTabs();
      });
    })
    .catch(() => { /* ws.js 不可用 → 无重放通道，保持 dirty */ });
}
bindPersistReconnect();

// F1: 解析并校验 v2 标签存档（兼容 localStorage JSON 字符串与服务端对象）。
// 不合法 → null（交由服务端/回退路径处理，不清空本地缓存）。
function parseTabsData(raw) {
  try {
    const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!data || data.v !== 2 || !Array.isArray(data.tabs)) return null;
    return data;
  } catch (e) {
    return null;
  }
}

// 打开一个面板标签（无 absPath），并派发 canvas-tab-restore 让对应模块渲染内容。
// 先全部钉住再回退未钉的，避免批量恢复时 openTab 的预览替换行为互相顶掉。
function openPanelTab(tab) {
  const entry = openTab(tab.id, tab.title, {
    type: tab.type || 'generic',
    closable: true,
    pinned: true,
  });
  if (!entry) return null;
  if (tab.pinned === false) {
    entry.pinned = false;
    entry.tabEl.classList.add('preview');
    previewTabs.set(previewKeyOf(entry.type, entry.absPath), entry.id);
  }
  window.dispatchEvent(new CustomEvent('canvas-tab-restore', {
    detail: { id: tab.id, type: tab.type }
  }));
  return entry;
}

// 批量发送文件标签的 readFile 恢复请求（WS 未就绪时延迟到 onopen）。
// 这是 restoreFromData / reconcileFromServer 共用的文件恢复通道。
// 用 requestedFilePaths 去重：本地恢复已派发的路径，服务端 reconcile 不再重复发。
function sendFileRestoreRequests(fileTabs) {
  if (fileTabs.length === 0) return;
  // 只发送「未派发过」且「标签尚未打开」的文件，避免本地/服务端双通道重复请求。
  const fresh = fileTabs.filter((t) => (
    !requestedFilePaths.has(t.absPath) && !tabs.has(`file:${t.absPath}`)
  ));
  if (fresh.length === 0) return;
  fresh.forEach((t) => requestedFilePaths.add(t.absPath));
  // Defer WS import to avoid circular dependency.
  Promise.all([import('./ws.js'), import('./state.js')]).then(([{ sendWs, onReconnect }, stateMod]) => {
    const state = stateMod.default;
    const doSend = () => {
      fresh.forEach((tab) => {
        // Mark this path as pinned so explorer's fileContent handler picks it up.
        window.dispatchEvent(new CustomEvent('explorer-preload-pinned', {
          detail: { path: tab.absPath, pinned: tab.pinned !== false }
        }));
        sendWs({ type: 'pop.readFile', path: tab.absPath, sessionId: undefined });
      });
      console.log('[restoreTabs] sent readFile for', fresh.length, 'file tabs');
    };
    // Page-load race: restore runs before the first WS connect, and sendWs
    // silently drops messages when the socket isn't OPEN. If not connected
    // yet, defer to the first onopen via onReconnect.
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      doSend();
    } else {
      console.log('[restoreTabs] WS not open — deferring file tab restore to onopen');
      let sent = false;
      onReconnect(() => {
        if (sent) return;
        sent = true;
        doSend();
      });
    }
  });
}

// 从一份合法的 v2 tabs 数据执行两阶段恢复（面板同步 + 文件异步）。
// 返回「是否恢复了任何内容」（有面板/文件标签即 true）。
function restoreFromData(data) {
  if (!data || !data.tabs || data.tabs.length === 0) return false;
  // Retired panel types (legacy Teams/Flows, 2026-09-05) never re-open —
  // old server/localStorage archives may still carry them; drop silently.
  const panelTabs = data.tabs.filter(t => !t.absPath && t.type !== 'flow-run' &&
    t.type !== 'teams' && t.type !== 'flows');
  const fileTabs = data.tabs.filter(t => t.absPath);
  console.log('[restoreTabs] restoring', panelTabs.length, 'panel tabs,', fileTabs.length, 'file tabs');

  // Phase 1 — panel tabs, synchronous.
  const unpinnedRestored = [];
  for (const tab of panelTabs) {
    const entry = openPanelTab(tab);
    if (!entry) continue;
    if (tab.pinned === false) unpinnedRestored.push(entry);
  }
  for (const entry of unpinnedRestored) {
    entry.pinned = false;
    entry.tabEl.classList.add('preview');
    previewTabs.set(previewKeyOf(entry.type, entry.absPath), entry.id);
  }
  if (unpinnedRestored.length > 0) persistTabs();

  // Phase 2 — file tabs, async via readFile WS.
  if (fileTabs.length > 0) sendFileRestoreRequests(fileTabs);
  if (data.activeTabId) setActiveTab(data.activeTabId);
  return true;
}

// F1: 服务端数据到达后，与服务端对齐（服务端权威）。只补开本地缺的标签，不强制关闭
// 本地已开的（避免破坏用户当前打开的标签）；刷新本地缓存。
function reconcileFromServer(serverData) {
  if (!serverData || !Array.isArray(serverData.tabs) || serverData.tabs.length === 0) return false;
  const present = new Set([...tabs.keys()]);
  const panelToOpen = [];
  const fileToOpen = [];
  for (const tab of serverData.tabs) {
    if (tab.type === 'flow-run') continue;
    // Retired legacy panel tabs (Teams/Flows) — server archive may still
    // carry them; never re-open (2026-09-05 旧 UI 退役).
    if (tab.type === 'teams' || tab.type === 'flows') continue;
    if (present.has(tab.id)) continue;  // 已开（本地恢复成功）→ 跳过
    if (tab.absPath) fileToOpen.push(tab);
    else panelToOpen.push(tab);
  }
  let openedAny = false;
  for (const tab of panelToOpen) {
    if (!openPanelTab(tab)) continue;
    openedAny = true;
  }
  if (fileToOpen.length > 0) {
    sendFileRestoreRequests(fileToOpen);
    openedAny = true;
  }
  // 刷新本地缓存（服务端权威）。
  try {
    localStorage.setItem(LS_TABS_KEY, JSON.stringify({
      v: 2, tabs: serverData.tabs, activeTabId,
    }));
  } catch (e) { /* storage full — non-critical */ }
  return openedAny;
}

/** Restore tabs on page load — server persisted (authoritative), falls back to localStorage.
 *  F1 (2026-08-30 作者裁定 + 方案 §8.4 F1): 服务端存档为权威，localStorage 仅降级缓存。
 *  Two-phase restore:
 *  1. Panel tabs without absPath (Teams/Flows) are re-opened synchronously —
 *     they re-render from live state via the 'canvas-tab-restore' event.
 *  2. File tabs are re-opened asynchronously by sending a readFile WS message
 *     for each absPath. The existing fileContent → workspace-open-item
 *     pipeline handles rendering.
 *  Finally the last active tab is re-activated.
 *  Returns a Promise<boolean> — true if any tab was restored; false means main.js
 *  should fall back to opening the Teams panel. */
export function restoreTabs() {
  return new Promise((resolve) => {
    let didLocal = false;
    try {
      const raw = localStorage.getItem(LS_TABS_KEY);
      console.log('[restoreTabs] localStorage:', raw);
      const localData = parseTabsData(raw);
      didLocal = restoreFromData(localData);
    } catch (e) { /* corrupt data — ignore */ }

    // F1 (2026-08-30 作者裁定 + 方案 §8.4 F1): 服务端为权威存档。无论本地是否恢复成功，
    // 都拉取服务端并合并/覆盖——这是根治 Safari 无痕/多窗口清空 localStorage 导致标签
    // 重启丢失的关键：存档介质移出浏览器、落到服务端磁盘。服务端 404/网络失败时回退本地。
    fetch(TABS_API, { headers: { ...authHeaders() } })
      .then((r) => (r.ok ? r.json() : null))
      .then((serverData) => {
        const parsed = parseTabsData(serverData);
        if (parsed && parsed.tabs.length > 0) {
          const didServer = reconcileFromServer(parsed);
          resolve(didLocal || didServer);
        } else {
          // 无服务端存档（404/空）或网络失败 → 回退本地结果。
          resolve(didLocal);
        }
      })
      .catch(() => resolve(didLocal));
  });
}
