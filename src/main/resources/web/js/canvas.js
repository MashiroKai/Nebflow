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
// 行为逐字不变。
// 2026-10-22 作者令（胶囊交互重做）菜单契约扩容：多开（铺开叠卡）、关闭、
// 在独立窗口打开（联动 #717 卡窗链 popoutCardWindow/toggleCardWindow）——
// 引用项保留在挂（CC4 契约 + 移动层长按同通路）；文案随态（独立中=合并回主窗）。
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
    // 多开（作者令 2026-10-22）：铺开叠卡环（deck 关闭态）/ 收起（deck 开着，
    // 文案随态 —— 同一枚菜单项覆盖往返）
    if (actions.onMultiOpen) {
      const multiBtn = document.createElement('button');
      multiBtn.textContent = actions.multiOpenLabel || '多开';
      multiBtn.addEventListener('click', () => { hideCanvasRefMenu(); actions.onMultiOpen(); });
      menu.appendChild(multiBtn);
    }
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
    // 在独立窗口打开（作者令 2026-10-22）：文件卡才可独立（独立窗经 absPath
    // readFile 重读真容，cardCanPop 门）；独立中 = 合并回主窗（toggle 同位换形，
    // 与卡头 pop 钮同语义）。内容物收口批：非内容物页签 = 禁用态项 + 提示
    // （title + 点击 toast），边界可见不静默。
    if (actions.onPopout) {
      const popBtn = document.createElement('button');
      popBtn.textContent = actions.popoutLabel || '在独立窗口打开';
      if (actions.popoutDisabledHint) {
        popBtn.classList.add('disabled');
        popBtn.setAttribute('aria-disabled', 'true');
        popBtn.title = actions.popoutDisabledHint;
        popBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          window.__showToast?.(actions.popoutDisabledHint, 'info');
        });
      } else {
        popBtn.addEventListener('click', () => { hideCanvasRefMenu(); actions.onPopout(); });
      }
      menu.appendChild(popBtn);
    }
  }
  document.body.appendChild(menu);
  _canvasCtxMenu = menu;
  // Adjust if off-screen
  const rect = menu.getBoundingClientRect();
  if (rect.right > window.innerWidth) menu.style.left = (x - rect.width) + 'px';
  if (rect.bottom > window.innerHeight) menu.style.top = (y - rect.height) + 'px';
}

/** 胶囊右键 actions 组装（标题头与胶囊环共用一份菜单语义——作者令 2026-10-22：
 *  多开 / 关闭 / 在独立窗口打开，引用项保留）。@param {Object} entry */
function capsuleMenuActions(entry) {
  const deckOpen = deck.open && !deckBusy();
  // 内容物收口批（2026-10-22 作者令）：独立窗只承载内容物（文档/文件/图片）。
  // 非内容物页签（面板：任务列表/图谱/设置/会话列表等）不再静默省略菜单项，
  // 渲染「禁用态 + 提示」项 —— 边界可见；点击由弹出链内容物门终拒（同文案）。
  const canPop = cardCanPop(entry.id);
  return {
    allowNoRef: true,
    onMultiOpen: () => deckFabToggle(),
    multiOpenLabel: deck.open ? '收起叠卡' : '多开',
    onClose: () => (deckOpen ? deckRequestClose(entry.id) : closeTab(entry.id)),
    onCloseOthers: deckOpen ? () => deckCloseOthers(entry.id) : null,
    onPopout: () => toggleCardWindow(entry.id),
    popoutLabel: isCardIndependent(entry.id) ? '合并回主窗' : '在独立窗口打开',
    popoutDisabledHint: canPop ? null : t('canvas.cardwinContentOnly'),
  };
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
    const tid = /** @type {HTMLElement} */ (el).dataset.tabId;
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
  if (deck.open) deckShutdown();

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
        recordTombstone(oldPreviewId); // #910: 预览替换=显式关闭旧预览
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
  /* UI 批⑧：独立/合并 toggle 不放标签条 —— 桌面标签行已退役（canvas-stack v5，
     split.css 隐藏）、移动层也藏（mobileLayer.css），可见卡头 = 居中标题头
     （#canvas-pop-btn，syncCardHeaderPop）+ 环卡卡头（.deck-card-pop）+ 手机
     卡片头（.mobile-card-pop，navController）。 */
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
  // #910: 新建成功即视为显式重开落账（作者语义「显式重开才恢复」——点开
  // 这个文件/面板就是重开意图）。服务端对无墓碑的 id 忽略该账（不凭空造
  // 记录）；restoreTabs 批量恢复走这里也安全：能从服务端回来的 id 必无生效
  // 墓碑，重开账是空转。
  sessionReopens.set(id, { id, reopenedAt: Date.now() });
  if (sessionReopens.size > SESSION_LEDGER_CAP) {
    sessionReopens.delete(sessionReopens.keys().next().value); // 最旧
  }

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
  recordTombstone(id); // #910: 显式关闭意图落账（× 按钮 / deck 侧滑共用此处）
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

  // UI 批⑧：独立中的卡先静默收窗（merge 等价腿，无动画）再走关闭 —— 主窗卡
  // 关闭时独立窗绝不残留（cmd-W 卡窗自己会 bye，这里管主窗主动关的顺序）。
  if (independentWins.has(id)) {
    const rec = independentWins.get(id);
    independentWins.delete(id);
    const iv = cardWinWatch.get(id);
    if (iv) { clearInterval(iv); cardWinWatch.delete(id); }
    if (rec && rec.win && !rec.win.closed) cardBcPost({ kind: 'merge', tabId: id });
  }

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

/** Manual re-read for a file tab (canvas 文件刷新批, 作者令 2026-10-25): the
 *  bottom-right refresh button's action. Rides the SAME readFile round trip as
 *  scheduleFileRefresh (pop.readFile → fileContent → workspace-open-item →
 *  openWorkspaceItem) but bypasses the activation cooldown (the user asked for
 *  it), never clobbers unsaved edits (visible toast, same isTabDirty discipline
 *  as every other refresh path) and drops the nf-file blob-ledger entry first —
 *  a ledger hit renders cached bytes with zero fetch, which would make the
 *  button silently lie for image/pdf tabs. */
function refreshFileTab(entry) {
  if (!entry || !entry.absPath) return;
  const pane = entry.paneEl;
  if (!pane || !pane.isConnected) return;
  if (pane.dataset.sourceMode === '1') return;  // button hidden there (CSS); programmatic guard
  if (isTabDirty(entry)) {
    window.__showToast?.(t('canvas.refreshDirty'), 'info');
    return;
  }
  if (entry._refreshing) return;
  entry._refreshing = true;
  // 定位修复批：钮已是壳层单例（#canvas-content 直子），不再按 pane 作用域查
  const btn = (fileRefreshBtn && fileRefreshBtn.isConnected) ? fileRefreshBtn : null;
  if (btn) btn.classList.add('busy');
  // Safety: un-busy even if the response never arrives (mirrors the
  // _refreshing safety window in scheduleFileRefresh).
  setTimeout(() => { if (entry._refreshing) settleFileRefresh(entry); }, 10000);
  Promise.all([
    import('./ws.js'),
    import('./state.js'),
    import('./nfFileCache.js').then(({ nfCacheInvalidatePath }) => nfCacheInvalidatePath(entry.absPath)).catch(() => {}),
  ]).then(([{ sendWs }, { default: stateMod }]) => {
    if (!tabs.has(entry.id) || entry.paneEl !== pane) { settleFileRefresh(entry); return; }
    if (!sendWs) { settleFileRefresh(entry); return; }
    // refresh:true ⇒ the response must not steal activation; manual:true ⇒
    // openWorkspaceItem re-renders even without inline content (binary legs).
    window.dispatchEvent(new CustomEvent('explorer-preload-pinned', {
      detail: { path: entry.absPath, pinned: entry.pinned !== false, refresh: true, manual: true },
    }));
    sendWs({ type: 'pop.readFile', path: entry.absPath, sessionId: stateMod?.activeSessionId });
  }).catch(() => { settleFileRefresh(entry); });
}

/** Clear one file tab's refresh-in-flight state (busy icon + _refreshing). */
function settleFileRefresh(entry) {
  entry._refreshing = false;
  // 壳层单例：busy 类挂在共享按钮上，代表「任一在飞刷新」——清它即可
  if (fileRefreshBtn && fileRefreshBtn.isConnected) fileRefreshBtn.classList.remove('busy');
}

// ── 文件刷新钮 · 壳层挂载（定位修复，作者报障 2026-10-27「刷新钮悬浮在正文
// 中间」）────────────────────────────────────────────────────────────────
// 根因：刷新钮 absolute 挂在 pane 里，而 docx/yaml/xlsx/pptx 等查看器把
// .scrollable 加在 pane 自身（pane 即滚动容器）——absolute 子元素按整份滚动
// 内容盒定位，bottom:16 落到文档末尾而非可视区底缘，随滚动漂移（md/code 等
// pane 不自滚的页签不受累）。修法：挂载点上移到不滚动的面板壳 #canvas-content
// （split.css: position:relative + overflow:hidden），按钮 = 每文档单例，可见性
// 与座位随活动页签同步（syncFileRefreshButton）；源码态隐藏由 addSourceToggle
// 的 canvas-source-mode-changed 事件驱动（shared.js 无回流 import 边，同
// canvas-viewer-rerendered 先例）。主题（亮暗）只换 token 色值，几何不动。
let fileRefreshBtn = null;

/** The active tab's entry iff it is a live file tab (absPath + connected pane). */
function activeFileEntry() {
  const entry = activeTabId ? tabs.get(activeTabId) : null;
  const pane = entry && entry.paneEl;
  return (entry && entry.absPath && pane && pane.isConnected) ? entry : null;
}

/** Mount the bottom-right refresh button for rendered file tabs. Called after
 *  every renderFile (call sites unchanged from the 刷新批) — the shell button
 *  survives viewer innerHTML wipes, so remounts collapse to a no-op; the
 *  visibility/seat sync below is what does the per-tab work. */
function mountFileRefreshButton(pane, entry) {
  if (!entry || !entry.absPath) return;
  import('./viewers/shared.js').then(({ addRefreshButton }) => {
    // The tab can close (or the whole pane be replaced) while the import settles.
    if (!pane.isConnected || tabs.get(entry.id) !== entry) return;
    if (!fileRefreshBtn || !fileRefreshBtn.isConnected) {
      const host = document.getElementById('canvas-content');
      // Click resolves the ACTIVE file entry at click time (the singleton
      // outlives any one tab's closure).
      fileRefreshBtn = addRefreshButton(pane, {
        host,
        onRefresh: () => { const e = activeFileEntry(); if (e) refreshFileTab(e); },
      });
    }
    syncFileRefreshButton();
  }).catch(() => { /* shared.js unavailable — button absent, zero behavior change */ });
}

/** Shell button follows the active tab: visible iff the active entry is a live
 *  file tab outside source mode; seat recomputed against the active pane's
 *  corner cluster (source/ref/find/graph circles + zoom bar live in the pane
 *  and differ per viewer). */
function syncFileRefreshButton() {
  const btn = fileRefreshBtn;
  if (!btn || !btn.isConnected) return;
  const entry = activeFileEntry();
  const pane = entry && entry.paneEl;
  const visible = !!(entry && pane.dataset.sourceMode !== '1');
  btn.hidden = !visible;
  if (!entry) return;
  const entryId = entry.id;
  import('./viewers/shared.js').then(({ seatRefreshButton }) => {
    if (btn.isConnected && activeTabId === entryId) seatRefreshButton(btn, pane);
  }).catch(() => {});
}

// MD/HTML source↔rendered toggle swaps pane.dataset.sourceMode — the shell
// button must hide/show with it (event decouples shared.js from canvas.js; no
// import edge back). Only the ACTIVE pane owns the button.
document.addEventListener('canvas-source-mode-changed', (e) => {
  const pane = /** @type {any} */ (e.target);
  if (pane instanceof HTMLElement && pane.classList.contains('active')) syncFileRefreshButton();
});

// MD/HTML source↔rendered toggle round trips re-run the viewer WITHOUT a
// readFile (addSourceToggle → renderFn). The shell button survives the render
// wipe; this remains as a self-healing re-sync (and first-mount belt for flows
// that bypass openWorkspaceItem).
document.addEventListener('canvas-viewer-rerendered', (e) => {
  const pane = /** @type {any} */ (e.target);
  if (!(pane instanceof HTMLElement)) return;
  for (const entry of tabs.values()) {
    if (entry.paneEl === pane) { mountFileRefreshButton(pane, entry); return; }
  }
});

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
  // 壳层刷新钮（定位修复批）：随活动页签同步可见性 + 座位
  syncFileRefreshButton();

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
  const { itemType, title, content, absPath, size, pinned, anchor, warnings, stream, mtimeMs, readOnly, manual } = item;
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
      // 手动刷新收到错误应答（文件此刻不可读）：原内容照旧保留（F3 纪律），
      // 但点击不能无声蒸发——给可见失败反馈。后台刷新照旧静默。
      if (manual) window.__showToast?.(t('canvas.refreshFailed', { error: item.error }), 'error');
      if (!item.background) setActiveTab(id);
      settleFileRefresh(tabs.get(id));
      return;
    }
    const placeholderTitle = title || (absPath ? absPath.split('/').pop() : id);
    const entry = openTab(id, placeholderTitle, { type: 'code', pinned: !!pinned, absPath: absPath || id });
    if (!entry) return;
    renderFilePlaceholder(entry.paneEl, item.error, absPath || id);
    // 骨架标签也挂刷新钮：文件恢复可读后一键重读即可翻正（readFile →
    // fileContent → 下方既有页签重渲腿），不用整页刷新。
    mountFileRefreshButton(entry.paneEl, entry);
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
    settleFileRefresh(entry);  // response arrived (or user-initiated open) — un-busy the refresh button
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
    // 手动刷新（manual，canvas 文件刷新批）：二进制腿的 readFile 应答不带
    // content——没有 manual 旗标时该分支对图片/PDF 永不触发，刷新按钮就是
    // 无声空点。流式大文本除外：其变更面走上方 acceptMeta（保滚动位），
    // 整面重挂反而把读者扔回文件头。
    if ((content || manual) && !stream && !isTabDirty(entry) && entry.paneEl.dataset.sourceMode !== '1') {
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
        mountFileRefreshButton(entry.paneEl, entry);
        notifyCardUpdate(id);   // UI 批⑧：独立窗内容同步（卡窗重读文件）
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
      recordTombstone(oldPreviewId); // #910: 预览替换=显式关闭旧预览
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
  mountFileRefreshButton(pane, entry);  // 文件刷新批：右下刷新钮（查看器会清 pane.innerHTML，须渲后挂载）
  entry._lastRefreshAt = Date.now();  // just rendered — don't immediately re-fetch
  notifyCardUpdate(id);               // UI 批⑧：独立窗首渲/重渲同步（卡窗自读文件）
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
//     JS 逐帧同步面板盒（top 10 / right 12 / 40px；移位批 2026-10-22 曾迁右下
//     bottom16，回退批同日回访裁定迁回顶右原位），z-250 恒在卡组与面板之上；
//   · 换入（v4④）：点前景卡放大回满幅 → setActiveTab 落位 → 旧 canvas ghost 缩回；
//   · 划走（v2⑥/v4⑥/v5①）：作用于前景面（canvas 前景=拖面板，环卡前景=拖该卡），
//     6px 点击界分 / 20% 区宽或 0.55px/ms 速度阈 / 13° 侧旋甩出 / spring 弹回；
//   · 关闭接管同 closeTab 语义（teardownTab 共核）；非前景卡关 = 环流就地收敛保相位；
//   · 本批三精修：①趋近 10/7→12/9、翻落曲线收幅（30/40/6/30→26/32/5/24）、
//     morph 时长统一 0.28s 档；②投影 token 浅一档（split.css --card-shadow）；
//     ③× 退役 → 竖排浅色滑动提示（.card-swipe-hint / .deck-swipe-hint）。
//
// uifix 批（作者令 2026-10-20 四件，重写叠卡视角）——推翻/升级：
//   · ③a 层叠阴影重做：投影按环深**渐次减弱**（JS 每帧给卡打 data-dim 层级，
//     CSS 四档 token），露边间距**拉开**（deckPeekStep 三版档位，body.deck-look-v1
//     收敛 / v2 均衡(默认) / v3 拉开——作者挑版，setLook 即热切换）；
//   · ③b 卡片快照：开 deck 时对每张卡截取**真实内容克隆**（markdown=渲染面克隆/
//     image=img 复源/pdf=首页像素拷贝），Monaco/iframe 类不可克隆面回落类型 mock；
//     快照 Map 仅存到 deck 关闭（无常驻重渲染，擦洗仍是纯 transform）；
//   · ③c 滚轮重做（触控板/鼠标分流）：wheel 只在叠卡面内捕获（页面其余区域原生
//     滚动不被劫持——「双指不与页面滚动打架」）；触控板连续小 delta 直通 scrub，
//     事件流停 110ms 后速度 EMA 滑行衰减补动量；鼠标格（|dy|≥40）按档位增益
//     ≈一格近一卡；
//   · ③d 竖排「滑动关闭」提示文字整体退场（作者令）；
//   · ③e 卡右上角 × 钮回归（作者最新口径推翻精修③）：pointerdown 阻断防拖划误触，
//     click 走 deckRequestClose 同右键菜单一条腿（划走手势保留）。
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
// UI 批（作者令 2026-10-21 五件，堆叠视图）——推翻/新增：
//   · ①堆叠态顶部两行帮助条（操作提示行 + 前景计数行）整行退役：deck-foot 元素
//     整体移除，面板上一个字不留（交互自学，收进 ？ 帮由后续批定）；
//   · ②堆叠按钮连点稳定：三类失效成因修复——(a) 重入锁泄漏（closeDeck 收场不清
//     deck.drag/deck.cardDrag，残留 flag 让 fab 守卫永久吞点击）→ 收场统一清场；
//     (b) 指针捕获丢失（环卡在拖划中途被移除——外部换签重挂环/摘卡收敛——
//     pointerup 永不达卡）→ deckFrame 逐帧失扫 + 收场清场；(c) FX 窗口吞点击
//     （selFly/ghostFx/faceFx/exitCardFx/finalizing 期间 fab 点击被静默丢弃）→
//     改为登记排队，FX 收场即补发一次 toggle——快速连点每次都计数；
//   · ③每卡真实快照升级：md/图/pdf 克隆腿之外补 Monaco（.view-lines 渲染层克隆，
//     令牌色走全局注入样式）与 html 页签（srcdoc 同源 iframe 内容文档重挂）；
//     快照活性窗 |d|≤2.5——近卡挂活性同步（源滚动 → 缩略 translateY 镜像 +
//     源 DOM 变更防抖重截），深处卡降级静态快照（真实内容、不再骨架灰条）；
//   · ④动效层重做：deckPoseOf 加缩放坡道（近卡放大/远卡缩小）+ 视差 translateZ
//     （透视深度）+ 翻落支缩幅；d=0 两侧连续（前景 scale=1/z=0 基姿不变，交接
//     窗换手无跳变）；滚轮/竖拖换卡机制原样（动效是纯位姿层重写）；
//   · ⑤边缘阴影柔化：卡描边退役（1px 硬边 → 无边框，层次靠明暗差），投影 token
//     全族放大模糊半径、降低不透明度（羽化融边），card-shade 改纵向渐变
//     （近卡顶缘与背景的过渡带），split.css 同步。
//
// 与样张的落地差异（deviations，均为真实 app 约束所需）：
//   · deck-layer / fab 为 body 层 fixed 元素逐帧同步面板盒（真实 app 的
//     面板挂 body flex 行，样张的 #canvas-area 包裹层不存在；deck-foot 已于
//     UI 批 ① 退役）；
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

// ── uifix 批（作者令 2026-10-20 四件）新增 ─────────────────────────────
// ③a 层叠阴影三版参数（作者挑版，body.deck-look-* 切换；默认 v2 均衡）：
//   露边阶梯（间距拉开档位）由 deckPeekStep() 同源取值 —— CSS 只管阴影 token，
//   几何档位在这里（deckPoseOf 每帧读取，切版即重排）。
const DECK_LOOK_DEFAULT = 'v2';
const DECK_LOOKS = {
  v1: { peek1: 22, peek2: 6,  wheelNotch: 1.8 },   // 收敛：贴近 v5 原值，阴影最轻
  v2: { peek1: 27, peek2: 9,  wheelNotch: 2.2 },   // 均衡（默认）：间距+50%，阴影中档
  v3: { peek1: 34, peek2: 14, wheelNotch: 2.2 },   // 拉开：间距翻倍，层级感最强
};
/** 当前 look 档位参数（body.deck-look-* 单一来源，CSS/JS 同读一类名）。 */
function deckLook() {
  const body = document.body;
  for (const k of Object.keys(DECK_LOOKS)) if (body.classList.contains('deck-look-' + k)) return k;
  return DECK_LOOK_DEFAULT;
}
function deckPeekStep() { return DECK_LOOKS[deckLook()] || DECK_LOOKS[DECK_LOOK_DEFAULT]; }
// ③c 滚轮动量（触控板自然滚动）：末窗速度（px/ms）→ 事件流静默后滑行衰减。
//   触控板 macOS 原生动量事件本身持续喂 delta，滑行只补「动量被吞」的浏览器；
//   鼠标格（单事件 |dy|≥40px）按档位增益（DECK_LOOKS.wheelNotch 倍率）≈一格近一卡。
const DECK_WHEEL_GLIDE_DELAY = 110;   // 事件流静默 ≥110ms 判定结束
const DECK_WHEEL_GLIDE_DECAY = 4.6;   // 滑行指数衰减（/s）
const DECK_WHEEL_GLIDE_VMAX = 3.2;    // 滑行初速钳（px/ms，≤ ~1.1 卡冲程）
const DECK_NOTCH_PX = 40;             // 单事件 |dy|≥40 判为鼠标格（触控板指纹 1~20px）

// ── UI 批（作者令 2026-10-21 五件）新增 ─────────────────────────────
// ④ 动效层：近卡放大/远卡缩小 + 视差深度（d=0 两侧连续：前景 scale=1/z=0 基姿
//   不变，与 .stacked 整区缩放基姿严丝合缝，交接窗换手无跳变）。
const DECK_DEPTH_SCALE_NEAR = 0.055;  // d∈[0,1] 缩小档（前景 1 → 次卡 ≈0.945）
const DECK_DEPTH_SCALE_FAR = 0.018;   // d>1 续缩档（最深处 ≈0.86）
const DECK_DEPTH_Z = 26;              // 视差 translateZ（透视 1150，深处后撤）
const DECK_FALL_SHRINK = 0.10;        // 翻落支缩幅（d<0 向下翻落时缩小）
// ④② 透视 y 补偿（deckProjectYComp）：与 split.css #deck-layer 的 perspective /
//   perspective-origin: 50% 10% 同源——改 CSS 透视必须同步这里（A21-0 钉）。
const DECK_PERSPECTIVE = 1150;        // #deck-layer perspective（px）
const DECK_ORIGIN_YK = 0.10;          // perspective-origin 纵向系数（10%）
// ③ 快照活性窗：|d| ≤ 2.5 的卡挂活性同步（源滚动 → 缩略 translateY 镜像 +
//   源 DOM 变更防抖重截）；深处卡保留静态快照（真实内容，无活性开销）。
const DECK_LIVE_DEPTH = 2.5;
const DECK_LIVE_RECLONE_DEBOUNCE = 300;

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
  raf: 0, lastT: 0,
};

let deckLayer = null;
let deckFab = null;
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

// ── DOM 装配（body 层固定两件：deck-layer（面板之前）+ fab（面板之后））──
// UI 批 ①：堆叠态顶部两行帮助条（deck-foot：操作提示 + 前景计数）整体退役——
// 元素不再装配，面板上一个字不留。
function initDeck() {
  const panel = document.getElementById('canvas-panel');
  if (!panel || deckLayer) return;

  deckLayer = document.createElement('div');
  deckLayer.id = 'deck-layer';
  deckFab = document.createElement('button');
  deckFab.id = 'deck-fab';
  deckFab.className = 'deck-fab';
  deckFab.setAttribute('aria-pressed', 'false');
  deckFab.setAttribute('title', '叠卡切换（滚轮 / 上下拖动换卡 · 前景轮换 · 点击前景卡换入 · 横向划走关闭）');
  deckFab.setAttribute('aria-label', '叠卡切换');
  deckFab.innerHTML = DECK_ICONS.deck;

  // 层序（dock 态全 z-auto，按 DOM 序）：deck-layer < #canvas-panel < fab；
  // .over 抬 deck-layer z-60（overlay 形态 245）盖过面板（split.css）。
  document.body.insertBefore(deckLayer, panel);
  panel.after(deckFab);

  // 整区缩放基姿常量单一来源（样张 v4②）：CSS .stacked / 飞行落点 / ghost 起点 / 面板驱动位姿同源
  // 尺寸统一批（2026-10-23 六项⑤）：--stack-scale-inv = 1/0.85 反量随同注入 ——
  // .stacked 态胶囊（.canvas-header = 前卡名称条）按反量回乘，视觉尺寸与常态
  // 主胶囊同基准（134.9×36.0 档；面板整区 0.85 不动，只抵胶囊自身的缩）。
  panel.style.setProperty('--stack-scale', String(DECK_SCALE));
  panel.style.setProperty('--stack-shift', DECK_SHIFT + 'px');
  panel.style.setProperty('--stack-scale-inv', String(Number((1 / DECK_SCALE).toFixed(5))));

  // uifix ③a：默认层叠 look 档位（作者挑版后可经 __canvasDeck.setLook 热切换）
  if (![...document.body.classList].some((c) => c.indexOf('deck-look-') === 0)) {
    document.body.classList.add('deck-look-' + DECK_LOOK_DEFAULT);
  }

  // UI 批 ②：连点稳定入口（见 deckFabToggle 注释）
  deckFab.addEventListener('click', deckFabToggle);
  deckLayer.addEventListener('click', (e) => {
    if (e.target === deckLayer && !deck.finalizing) closeDeck();   // 点空白（区外露缘）收回
  });
  deckLayer.addEventListener('contextmenu', (e) => { if (e.target === deckLayer) e.preventDefault(); });

  bindDeckInput();
  syncDeckBox();
  renderCanvasHeader();
}

// ── UI 批 ②：堆叠按钮连点稳定 ──────────────────────────────────────
// 三类失效成因与修复：
//   (a) 重入锁泄漏：closeDeck 收场曾不清 deck.drag/deck.cardDrag（划走/竖拖进行中
//       按 ESC 等路径），残留 flag 让 fab 守卫永久吞点击 → 收场统一清场；
//   (b) 指针捕获丢失：环卡在拖划中途被移除（外部换签重挂环 / 摘卡收敛）→ pointerup
//       永不达卡 → deck.cardDrag 卡死 → deckFrame 逐帧失扫（el 断链即取消收场）；
//   (c) FX 窗口吞点击：selFly/ghostFx/faceFx/exitCardFx/finalizing 期间 fab 点击被
//       静默丢弃 → 改为登记排队（deckFabQueued），FX 收场即补发一次 toggle——
//       快速连点每次都计数，最终态确定不粘（spec A20 钉）。
let deckFabQueued = false;
function deckFabToggle() {
  if (deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx || deck.exitCardFx) {
    deckFabQueued = true;            // 动画窗口不吞点击：登记，收场即补
    return;
  }
  deckFabQueued = false;
  deck.open ? closeDeck() : openDeck();
}
/** FX/收场各落点回调：补发连点期间被排队的 toggle（一次）。 */
function deckFlushQueuedToggle() {
  if (!deckFabQueued) return;
  deckFabQueued = false;
  deckFabToggle();
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
  syncCardHeaderPop();
}

/* ── UI 批⑧：居中标题头独立/合并 toggle（桌面可见卡头；活动文件卡才显示）── */
const CARD_POP_SVG =
  '<svg class="pop-ic-pop" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/></svg>' +
  '<svg class="pop-ic-merge" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><path d="M14 10 21 3"/><path d="M3 21l7-7"/></svg>';
/** 标题头 toggle 建购 + 形态同步（活动页签视角：独立中=合并形高亮）。 */
function syncCardHeaderPop() {
  if (cardWindowMode) return;                     // 卡窗有自己的「合并」钮
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  if (!hdr) return;
  let btn = document.getElementById('canvas-pop-btn');
  if (!btn) {
    btn = document.createElement('button');
    btn.id = 'canvas-pop-btn';
    btn.className = 'canvas-pop-btn';
    btn.innerHTML = CARD_POP_SVG;
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      if (activeTabId) toggleCardWindow(activeTabId);
    });
    hdr.insertBefore(btn, document.getElementById('canvas-close-btn'));
  }
  const entry = activeTabId ? tabs.get(activeTabId) : null;
  const can = !!(entry && entry.absPath);
  btn.hidden = !can;
  if (!can) return;
  const indep = independentWins.has(activeTabId);
  btn.classList.toggle('is-independent', indep);
  const t = indep ? '合并回主窗' : '独立窗口打开';
  btn.title = t;
  btn.setAttribute('aria-label', t);
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
  document.body.classList.toggle('canvas-deck-narrow', r.width < 400);
}
/** body 层两件的盒同步（逐帧驱动；面板盒 = canvas 区）。
 *  滑删顺滑批（2026-10-23 六项⑥b）：盒未变零写 —— 逐帧直写 left/top/width/
 *  height 是布局属性，每帧写脏布局、下一帧 offset 读数强制重排（拖划期间
 *  读-写-读抖动 = 卡顿主源之一）。缓存上帧盒，四元全等即整段跳过（写不发生，
 *  读保持干净，帧内不再有强制重排）；盒真变（开合/拖拽分栏/缩放）才落笔。 */
let deckBoxLast = /** @type {{l: number, t: number, w: number, h: number}|null} */ (null);
function syncDeckBox() {
  const r = deckPanelRect();
  if (!r || !r.width) return;
  deck.areaH = r.height;               // ④②：透视 y 补偿的层高基准（deckProjectYComp）
  const same = deckBoxLast && deckBoxLast.l === r.left && deckBoxLast.t === r.top &&
    deckBoxLast.w === r.width && deckBoxLast.h === r.height;
  if (!same) {
    deckBoxLast = { l: r.left, t: r.top, w: r.width, h: r.height };
    if (deckLayer) {
      deckLayer.style.left = r.left + 'px';
      deckLayer.style.top = r.top + 'px';
      deckLayer.style.width = r.width + 'px';
      deckLayer.style.height = r.height + 'px';
    }
    if (deckFab) {
      // 回退批（作者令 2026-10-22 回访裁定）：fab 钉区回顶右原位（top = 面板顶
      // + 10）。移位批曾迁右下（top = 面板底 − 56 与圆钮簇同排），但作者只令
      // 图片查看器缩放控件迁右下，fab 属移位范围过宽；缩放条既已居底右、文件名
      // 胶囊居左，顶行叠印前提消失，回移安全。right 语义不变（12px 内缝）。
      // CSS 缺省（top:10）与本次式同源。
      deckFab.style.top = (r.top + 10) + 'px';
      deckFab.style.right = (window.innerWidth - r.right + 12) + 'px';
    }
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
    '<div class="card-thumb" data-snap="pending">' + deckThumbMock(family) + '</div>' +
    /* uifix ③e：卡右上角 × 关闭钮（作者最新口径，推翻精修③竖排提示方案） */
    '<button class="deck-card-x" type="button" title="关闭" aria-label="关闭 ' + escapeDeckText(entry.title || entry.id) + '">' +
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>' +
    '</button>' +
    '<div class="card-shade"></div>';
  /* UI 批⑧：环卡卡头独立/合并 toggle（文件卡；× 左侧同族小钮）。 */
  if (entry.absPath) {
    const pop = document.createElement('button');
    pop.type = 'button';
    pop.className = 'deck-card-pop';
    pop.title = '独立窗口打开';
    pop.setAttribute('aria-label', '独立窗口打开');
    pop.innerHTML = CARD_POP_SVG;
    pop.addEventListener('pointerdown', (e) => e.stopPropagation());
    pop.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleCardWindow(entry.id);
    });
    card.insertBefore(pop, card.querySelector('.deck-card-x'));
    const indep0 = independentWins.has(entry.id);
    if (indep0) {
      card.setAttribute('data-independent', '1');
      pop.classList.add('is-independent');
      pop.title = '合并回主窗';
      pop.setAttribute('aria-label', '合并回主窗');
    }
  }
  // uifix ③b + UI 批 ③：可视窗内的卡随建随挂快照（开卡组首帧即可见真容）；
  // 深处卡留 pending（mock 先顶住），由 deckAttachSnapshots 分帧补齐——
  // 防一次建满环克隆把开卡动画顶掉帧。
  const rNum = typeof r === 'number' ? r : NaN;
  if (cls === 'ghost' ||
      (Number.isFinite(rNum) && deck.M && Math.abs(deckWrapDelta(rNum - deck.pos, deck.M)) <= DECK_LIVE_DEPTH)) {
    deckMountSnapshot(card, entry.id);
  }
  /* 卡面 × 关闭（作者令）：pointerdown 阻断（不进拖划/点击语义），click 走
     deckRequestClose 同右键菜单一条腿——前景卡=面板收拢淡出，深处卡=就地收敛保相位 */
  const xBtn = card.querySelector('.deck-card-x');
  xBtn.addEventListener('pointerdown', (e) => { e.stopPropagation(); });
  xBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx || deck.exitCardFx) return;
    if (performance.now() < deck.suppressCardClick) return;
    deckRequestClose(entry.id);
  });
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
    wheelGlideV = 0;                                               // 拖划接管：截停滚轮滑行
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
    deckEndCardDrag(deck.cardDrag, cancelled);
  };
  card.addEventListener('pointerup', (e) => endCardDrag(e, false));
  card.addEventListener('pointercancel', (e) => endCardDrag(e, true));
  return card;
}

/** 拖划收尾（卡面公共腿）：正常 pointerup/cancel 与失扫兜底共用同一实现。
 *  UI 批 ②成因 (a)/(b)：此函数是 deck.cardDrag 的唯一清场口——收场路径
 *  （closeDeck/deckShutdown）直接置 null，跳过手势语义（收场优先）。 */
function deckEndCardDrag(d, cancelled) {
  if (deck.cardDrag !== d) return;
  deck.cardDrag = null;
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
}
function escapeDeckText(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ── ③b 卡片快照（作者令「堆叠视角要能看到卡片快照」+ UI 批 ③ 真实快照升级）──
// 方案 = **开 deck 时截取缓存 + 可视窗活性同步**：快照源按查看器类型分派 ——
//   markdown/docx/epub/xlsx/pptx = 渲染面 .canvas-md-viewer 克隆（去 id 防全局
//   重复）/ code/json = Monaco .view-lines 渲染层克隆（令牌色走全局注入样式，
//   连 host 字体/底色一起拷）/ html 页签 = srcdoc 同源 iframe 内容文档重挂
//   （真实渲染面；跨域 url 页签不可读 → 回落类型 mock）/ image = img 同源克隆
//   （零重解码，走缓存位图）/ pdf = 首页 canvas 像素 drawImage 拷贝。
// 活性窗（UI 批 ③「同屏只精渲染可视卡 ±2」）：|d| ≤ DECK_LIVE_DEPTH 的卡挂
//   活性同步——源滚动 → 缩略 translateY 镜像（滚动中同步更新）+ 源 DOM 变更
//   防抖重截（实时映射）；深处卡降级静态快照（真实内容、无活性开销）。
// 纪律：快照 Map 只活到 deck 收场（closeDeck/deckShutdown/deckFinishSwap 清），
// 每次开卡组重截 —— 无陈旧快照、无常驻重渲染；擦洗期间卡片仍是纯 transform。
const deckSnapshots = new Map();      // tabId → { node, srcW, kind, scroller }
const DECK_SNAP_BATCH = 2;            // 每帧最多建 2 张（防一帧长任务）
/** 快照源解析：可克隆面 → { el, kind, scroller? }；不可克隆 → null（mock 回落）。 */
function deckSnapshotSource(entry) {
  const pane = getTabPane(entry.id);
  if (!pane) return null;
  const family = deckTypeFamily(entry);
  const cloneFace = pane.querySelector('.canvas-md-viewer, .canvas-xlsx-viewer, .epub-reader');
  if (cloneFace && entry.paneEl.dataset.sourceMode !== '1') {
    return { el: cloneFace, kind: 'clone', scroller: cloneFace.closest('.canvas-md-scroll') || cloneFace.closest('.canvas-tab-pane') };
  }
  if (family === 'code' || entry.paneEl.dataset.sourceMode === '1') {
    // Monaco 渲染层（.view-lines = 纯内容行，令牌色类走全局注入样式，克隆保真）；
    // 编辑器未挂载（页签从未激活）→ 落 md/mock 腿。
    const editor = pane.querySelector('.monaco-editor');
    const lines = pane.querySelector('.monaco-editor .view-lines');
    if (editor && lines) return { el: lines, kind: 'monaco', host: editor };
  }
  if (family === 'web') {
    // html 页签 = srcdoc 同源 iframe（allow-same-origin）→ 内容文档序列化重挂
    // 真实渲染面；跨域（url 页签）contentDocument 不可读（抛错/null）→ mock。
    const frame = /** @type {HTMLIFrameElement|null} */ (pane.querySelector('iframe[data-nf-canvas-html]'));
    if (frame) {
      try {
        const doc = frame.contentDocument;
        const html = doc && doc.documentElement ? doc.documentElement.outerHTML : '';
        if (html && html.length > 32) return { el: frame, kind: 'iframe', html };
      } catch { /* 跨域 → mock */ }
    }
  }
  if (family === 'image') {
    const img = /** @type {HTMLImageElement|null} */ (pane.querySelector('.canvas-image-viewport img'));
    if (img && (img.complete || !img.src)) return { el: img, kind: 'img' };
  }
  if (family === 'doc') {
    const page = /** @type {HTMLCanvasElement|null} */ (pane.querySelector('.pdf-pages canvas'));
    if (page && page.width && page.height) return { el: page, kind: 'canvas' };
  }
  return null;
}
/** 建一张快照节点（已缓存直接返回；不可克隆回落 null）。 */
function deckBuildSnapshot(entry) {
  if (deckSnapshots.has(entry.id)) return deckSnapshots.get(entry.id);
  const src = deckSnapshotSource(entry);
  if (!src) return null;
  const area = deckPanelRect();
  const srcW = (area && area.width) || 900;   // 渲染面宽 = canvas 区宽（display:none 面读不到 offsetWidth）
  let node = null;
  try {
    if (src.kind === 'canvas') {
      // pdf 首页像素拷贝：按卡宽定高，一次 drawImage（零常驻成本）
      const srcCanvas = /** @type {HTMLCanvasElement} */ (src.el);
      const c = document.createElement('canvas');
      c.width = Math.round(Math.min(760, srcW));
      c.height = Math.round(c.width * (srcCanvas.height / srcCanvas.width));
      c.getContext('2d').drawImage(srcCanvas, 0, 0, c.width, c.height);
      node = c;
    } else if (src.kind === 'iframe') {
      // html 页签：新建只读 iframe 重挂同源 srcdoc（真实浏览器级渲染面）。
      const f = document.createElement('iframe');
      f.setAttribute('sandbox', 'allow-same-origin');    // 快照只读展示：不带回脚本
      f.setAttribute('scrolling', 'no');
      f.setAttribute('tabindex', '-1');
      f.setAttribute('aria-hidden', 'true');
      f.style.cssText = 'width:100%;height:100%;border:none;background:transparent;pointer-events:none;';
      f.srcdoc = src.html;
      node = f;
    } else if (src.kind === 'monaco') {
      // Monaco 渲染层克隆：剥掉滚动条/光标/悬浮件，宿主字体/底色/前景色一起拷，
      // 令牌色（.mtk* 类）由全局注入样式命中克隆子树。
      const host = /** @type {HTMLElement} */ (src.host);
      const hcs = getComputedStyle(host);
      const linesClone = /** @type {Element} */ (src.el.cloneNode(true));
      linesClone.querySelectorAll('.scrollbar, .cursors-layer, .scroll-decoration, .overflowingContentWidgets, .find-widget')
        .forEach((n) => n.remove());
      linesClone.querySelectorAll('[id]').forEach((n) => n.removeAttribute('id'));
      const shell = document.createElement('div');
      shell.style.cssText = 'background:' + hcs.backgroundColor + ';color:' + hcs.color +
        ';font-family:' + hcs.fontFamily + ';font-size:' + hcs.fontSize +
        ';font-weight:' + hcs.fontWeight + ';line-height:' + hcs.lineHeight +
        ';font-variant-ligatures:' + hcs.fontVariantLigatures + ';overflow:hidden;';
      shell.appendChild(linesClone);
      node = shell;
    } else {
      node = /** @type {Element} */ (src.el.cloneNode(true));
      node.querySelectorAll('[id]').forEach((n) => n.removeAttribute('id'));   // 克隆去 id：防全局 getElementById 撞车
      node.querySelectorAll('button, input, textarea, select').forEach((n) => n.setAttribute('tabindex', '-1'));
    }
  } catch { return null; }
  const snap = { node, srcW, kind: src.kind, scroller: src.scroller || null };
  deckSnapshots.set(entry.id, snap);
  return snap;
}
/** 把快照挂进卡的 thumb 槽（替换 mock；等比缩放 + 只读）。缩放比取布局常量
 *  （卡宽 = 面板盒 85%，thumb 内宽 = 卡宽 − 12×2 margin − 2 border）——建环瞬间
 *  卡未布局，clientWidth 读数不可靠。 */
function deckMountSnapshot(card, tabId) {
  const slot = card.querySelector('.card-thumb');
  if (!slot || slot.dataset.snap !== 'pending') return;
  const entry = tabs.get(tabId);
  if (!entry) return;
  const snap = deckBuildSnapshot(entry);
  if (!snap) { slot.dataset.snap = 'mock'; return; }
  slot.dataset.snap = 'live';
  slot.classList.add('has-snap');
  const area = deckPanelRect();
  const thumbW = area && area.width ? area.width * DECK_SCALE - 26 : 0;
  const k = thumbW && snap.srcW ? thumbW / snap.srcW : 0.85;
  const wrap = document.createElement('div');
  wrap.className = 'card-snap';
  const inner = document.createElement('div');
  inner.className = 'card-snap-inner';
  inner.style.width = snap.srcW + 'px';
  inner.dataset.k = k.toFixed(4);
  inner.style.transform = 'scale(' + k.toFixed(4) + ')';
  if (snap.kind === 'img') {
    const img = /** @type {HTMLElement} */ (snap.node);
    img.className = 'card-snap-img';
    inner.style.transform = '';
    inner.style.width = '100%';
    inner.appendChild(img);
  } else {
    inner.appendChild(snap.node);
  }
  wrap.appendChild(inner);
  slot.replaceChildren(wrap);
}
/** 开卡组时批量补快照：可视窗先挂，其余分帧建（防一帧长任务拖开卡动画）。 */
function deckAttachSnapshots() {
  if (!deck.open || !deckLayer) return;
  const cards = [...deckLayer.querySelectorAll('.deck-card[data-r]')]
    .sort((a, b) => {
      const da = Math.abs(deckWrapDelta((+a.dataset.r) - deck.pos, deck.M));
      const db = Math.abs(deckWrapDelta((+b.dataset.r) - deck.pos, deck.M));
      return da - db;                            // 近卡先建（前景先见真容）
    });
  const pending = cards.filter((c) => { const s = c.querySelector('.card-thumb'); return s && s.dataset.snap === 'pending'; });
  let i = 0;
  const step = () => {
    if (!deck.open) return;                       // 收场即弃（Map 已清）
    for (let n = 0; n < DECK_SNAP_BATCH && i < pending.length; n++, i++) {
      deckMountSnapshot(pending[i], pending[i].dataset.tab);
    }
    if (i < pending.length) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

// ── UI 批 ③：快照活性同步（可视卡 ±2 精渲染；深处静态快照）────────────
//   · 源滚动镜像：capture 段 scroll 监听 → 活性窗内卡把源 scroller 的 scrollTop
//     换算成缩略 translateY（滚动中同步更新，擦洗到哪跟到哪）；
//   · 源变更重截：MutationObserver 盯全部页签 pane（子树/文本），防抖后只重截
//     活性窗内的卡（深处卡不花这笔钱）。
const deckLive = { mo: null, onScroll: null, near: new Set(), timer: 0 };
function deckLiveSync() {
  if (!deck.open || !deckLayer) return;
  for (const id of [...deckLive.near]) {
    const el = deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(id) + '"]');
    if (!el) { deckLive.near.delete(id); continue; }
    const slot = el.querySelector('.card-thumb');
    if (!slot) continue;
    slot.dataset.snap = 'pending';                // 重建（保留 mock 兜底可见）
    slot.classList.remove('has-snap');
    slot.innerHTML = deckThumbMock(deckTypeFamily(tabs.get(id)));
    deckSnapshots.delete(id);
    deckMountSnapshot(el, id);
  }
}
function deckLiveSchedule() {
  if (deckLive.timer) clearTimeout(deckLive.timer);
  deckLive.timer = setTimeout(() => { deckLive.timer = 0; deckLiveSync(); }, DECK_LIVE_RECLONE_DEBOUNCE);
}
function deckLiveBind() {
  if (deckLive.mo || !deckLayer) return;
  deckLive.mo = new MutationObserver(() => { if (deckLive.near.size) deckLiveSchedule(); });
  deckLiveObserveAll();
  deckLive.onScroll = (e) => {
    if (!deckLive.near.size || !deckLayer) return;
    const scroller = e.target;
    if (!(scroller instanceof Element)) return;
    for (const id of deckLive.near) {
      const snap = deckSnapshots.get(id);
      if (!snap || !snap.scroller || snap.scroller !== scroller) continue;
      const inner = deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(id) + '"] .card-snap-inner');
      if (!inner) continue;
      const k = parseFloat(inner.dataset.k || '0.85');
      const top = scroller instanceof Element && 'scrollTop' in scroller ? /** @type {HTMLElement} */ (scroller).scrollTop : 0;
      inner.style.transform = 'translateY(' + (-k * top).toFixed(1) + 'px) scale(' + k.toFixed(4) + ')';
    }
  };
  document.addEventListener('scroll', deckLive.onScroll, true);
}
/** 把当前全部页签 pane 挂进活性监听（叠卡开着时外部新开的页签由重挂环路径补挂；
 *  对已监听目标重复 observe 同参数是幂等操作，不会重放回调）。 */
function deckLiveObserveAll() {
  if (!deckLive.mo) return;
  for (const [, entry] of tabs) {
    if (entry.paneEl) deckLive.mo.observe(entry.paneEl, { subtree: true, childList: true, characterData: true });
  }
}
function deckLiveUnbind() {
  if (deckLive.mo) { deckLive.mo.disconnect(); deckLive.mo = null; }
  if (deckLive.onScroll) { document.removeEventListener('scroll', deckLive.onScroll, true); deckLive.onScroll = null; }
  if (deckLive.timer) { clearTimeout(deckLive.timer); deckLive.timer = 0; }
  deckLive.near.clear();
}
/** 每帧维护活性窗集合（deckFrame 调）：进出窗沿 = 挂/摘（摘 = 留静态快照）。 */
function deckLiveTick() {
  if (!deck.open || !deckLayer || !deck.M) return;
  for (const el of deckLayer.querySelectorAll('.deck-card[data-r]:not(.ghost)')) {
    const id = el.dataset.tab;
    if (!id) continue;
    const d = Math.abs(deckWrapDelta((+el.dataset.r) - deck.pos, deck.M));
    const inWindow = d <= DECK_LIVE_DEPTH;
    const was = deckLive.near.has(id);
    if (inWindow && !was) {
      deckLive.near.add(id);
      const snap = deckSnapshots.get(id);
      if (!snap) deckMountSnapshot(el, id);       // 深处卡首次进窗：现补真容
    } else if (!inWindow && was) {
      deckLive.near.delete(id);                   // 出窗：静态快照保留，停止同步
    }
  }
}
/** 快照缓存清场（deck 收场三口：closeDeck / deckShutdown / deckFinishSwap）。
 *  只清 Map——卡 DOM 由收场自行移除，退场淡出期间快照留在卡上不闪帧。 */
function deckClearSnapshots() {
  deckSnapshots.clear();
  deckLiveUnbind();
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
  deck.cardDrag = null;                  // UI 批 ②：重挂环 = 全卡换血，在途拖划随旧卡失效
  deckMountRing();
  deckLiveObserveAll();                  // ③：外部新开的页签 pane 补挂活性监听
  deck.pos = deck.target = 0;
  deck.d0s = 0;
  deck.panelFront = true;
  if (deckLayer) deckLayer.classList.remove('over');
  deckStartLoop();
}

// ── 位姿（v4③ 露边阶梯 + v5① 翻落支 + UI 批 ④ 动效层：缩放坡道/视差深度）──
function deckPoseOf(d) {
  if (d < 0) {
    /* 翻落支（精修①收幅；y 从前景基姿 DECK_SHIFT 起落，保证 d=0 两侧连续）。
       UI 批 ④：翻落时缩小 + 后撤（视差），落地→渐隐收进卡组。 */
    const a = Math.min(-d, 2.2);
    const arc = -d < 1 ? Math.sin(Math.PI * -d) : 0;
    const y = DECK_SHIFT + (DECK_FALL_DROP * a + DECK_FALL_ARC * arc) * deck.sizeK;
    const tilt = (DECK_FALL_TILT * a + DECK_FALL_TILT_ARC * arc) * deck.sizeK;
    const op = Math.min(1, Math.max(0, 1 - (a - 0.12) * 0.42));
    return { y, z: -DECK_DEPTH_Z * a * deck.sizeK, tilt, scale: 1 - DECK_FALL_SHRINK * a,
             op, shade: Math.min(0.30, a * 0.12) };
  }
  /* uifix ③a：间距拉开档位（v1 收敛 / v2 均衡 / v3 拉开，deckPeekStep 同源）。
     UI 批 ④：近卡放大/远卡缩小（缩放坡道两段）+ 视差 translateZ（透视后撤）；
     d=0 → scale 1 / z 0（前景基姿与 .stacked 严丝合缝）。
     snapfix 批（作者令 2026-10-22 ③「堆叠快照不可见」）：透明度坡道重修——
     修前 `1 − (dd−1.2)×0.30` 无底：d=3 → 0.54、d=4 → 0.16、深处趋 0，暗色
     背景下深处卡直接沉底成「一片暗影」（作者截图：图谱面板后两层只余黑影）。
     现改带底坡道：d≤1 恒 1，此后每层 −0.12、0.62 封底 ⇒ 1 → 0.88(d2) →
     0.76(d3) → 0.64(d4+)，≥3 层逐层递暗有梯度、层层面容可辨（暗但不糊，
     9.19 无边语言：层次仍靠明暗差 + shade 渐变，不靠消失）。 */
  const step = deckPeekStep();
  const dd = Math.max(0, d);
  const a1 = Math.min(dd, 1);
  const a2 = Math.max(0, Math.min(dd, 4.5) - 1);
  const y = DECK_SHIFT - (step.peek1 * a1 + step.peek2 * a2) * deck.sizeK;
  const tilt = -1.6 * Math.min(dd, 4) * deck.sizeK;
  const op = dd <= 1 ? 1 : Math.max(0.62, 1 - (dd - 1) * 0.12);
  return { y, z: -DECK_DEPTH_Z * Math.min(dd, 4.5) * deck.sizeK,
           tilt, scale: 1 - DECK_DEPTH_SCALE_NEAR * a1 - DECK_DEPTH_SCALE_FAR * a2,
           op, shade: Math.min(0.30, 0.11 * dd) };
}
/** 面板 alpha（前景位交接窗，v5① 原窗口）：d₀≥0 环卡回归→面板渐入；d₀<0 面板翻落→环卡接管 */
function deckPanelAlphaOf(d0) {
  return d0 >= 0 ? deckSmooth((0.50 - d0) / 0.40) : deckSmooth(1 - (-d0 - 0.08) / 0.32);
}

/* ④② 透视 y 补偿（deckProjectYComp）─────────────────────────────────────
   #deck-layer 的 perspective(1150)/origin(50% 10%) 会把 translate3d 的 z≠0 卡
   拉向透视原点：设计露边阶梯（27/9px）投影后被压扁（d=1 实测 ~4px、d≥2 <1px，
   深处卡右键命中带消失 —— A9 首轮红）。反解投影方程：要「投影顶 = 设计顶」，
   translate y 应为
     y' = oy − H/2 + (topD − oy)/pf + (cardH/2)·s ，  pf = P/(P − z)
   其中 topD = H/2 + y − cardH/2（未投影设计顶）、oy = H·DECK_ORIGIN_YK。
   z=0 且 s=1（前景基姿）时代数恒等 → 无损；spread 开合期随 z 收敛连续。
   仅用于环卡绘制路径（deckPaintCard 消费方）；面板驱动路径盒几何不同（整区
   scale(0.85) 外置、无深度缩放），其投影顶本就 ≈ 设计值（实测偏差 <0.5px），
   勿复用本补偿。ghost/selFly 常量位姿 z=0 或渐隐中，不需要补偿。 */
function deckProjectYComp(y, z, s) {
  const H = deck.areaH || 0;
  if (!H || !z) return y;
  const pf = DECK_PERSPECTIVE / (DECK_PERSPECTIVE - z);
  const oy = H * DECK_ORIGIN_YK;
  const topD = H / 2 + y - deck.cardH / 2;
  return oy - H / 2 + (topD - oy) / pf + (deck.cardH / 2) * s;
}

/** @param {{y:number,z:number,tilt:number,scale:number,op:number,shade:number}} a
 *  @param {{y:number,z:number,tilt:number,scale:number,op:number,shade:number}} b */
function deckLerpPose(a, b, e) {
  return {
    y: a.y + (b.y - a.y) * e, z: a.z + (b.z - a.z) * e,
    tilt: a.tilt + (b.tilt - a.tilt) * e, scale: a.scale + (b.scale - a.scale) * e,
    op: a.op + (b.op - a.op) * e,
    shade: (a.shade || 0) + ((b.shade || 0) - (a.shade || 0)) * e,
  };
}
/* 落点/起点位姿（卡坐标系：卡基尺寸 = 面板盒 85% = 缩放态 canvas 尺寸） */
const DECK_FLY_LAND = { y: 0, z: 0, tilt: 0, scale: 1 / DECK_SCALE, op: 1, shade: 0 };
const DECK_GHOST_FROM = { y: DECK_SHIFT, z: 0, tilt: 0, scale: 1, op: 1, shade: 0 };
const DECK_GHOST_TO = { y: -34, z: -110, tilt: -8, scale: 0.94, op: 0, shade: 0 };

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

function deckFrame(t) {
  const panel = document.getElementById('canvas-panel');
  if (!panel || !deckLayer) { deckStopLoop(); return; }
  const dt = Math.min(0.05, (t - deck.lastT) / 1000 || 0.016);
  deck.lastT = t;
  syncDeckBox();

  /* UI 批 ② 成因 (b) 失扫：拖划中的环卡若已被移出文档（外部换签重挂环 / 摘卡
     收敛 / 收场清卡），浏览器不再向已拔除元素派发 pointerup —— deck.cardDrag
     会永久卡死、fab 永久失灵。逐帧断链检测即取消收场（跳过手势语义）。 */
  if (deck.cardDrag && !deck.cardDrag.el.isConnected) deckEndCardDrag(deck.cardDrag, true);

  // 移动层接管（视口切换/模式翻转）：叠卡静默退场
  if (document.body.classList.contains('mobile-layer') && deck.open && !deck.finalizing) {
    deckShutdown();
    return;
  }

  const k = deckRm.matches ? 1 : 1 - Math.exp(-dt * DECK_SCRUB_APPROACH);
  /* uifix ③c：滚轮滑行段 —— 事件流静默后的 EMA 初速指数衰减并进 target；
     新输入四路口（wheel/拖划/键盘/收场）已各自截停。 */
  if (wheelGlideV) {
    deck.target += wheelGlideV * dt * 1000 * DECK_WHEEL_GAIN;
    deck.target = Math.max(deck.pos - deck.M, Math.min(deck.pos + deck.M, deck.target));
    wheelGlideV *= Math.exp(-dt * DECK_WHEEL_GLIDE_DECAY);
    if (Math.abs(wheelGlideV) < 0.02) wheelGlideV = 0;
  }
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
  deckLiveTick();
  const cards = deckLayer.querySelectorAll('.deck-card[data-r]:not(.ghost)');
  for (const el of cards) {
    const r = +el.dataset.r;
    const d = deck.M ? deckWrapDelta(r - deck.pos, deck.M) : 1;
    /* uifix ③a：阴影渐次减弱 —— 环深档位打给 CSS（0=前景…3=深处） */
    const dimTier = Math.min(3, Math.floor(Math.abs(d)));
    if (el.dataset.dim !== String(dimTier)) el.dataset.dim = String(dimTier);
    const p0 = deckPoseOf(d);
    /* UI 批 ④：位姿含缩放坡道 + 视差 z（spread 开合期向基姿收敛，两侧连续）
       ④②：y 过透视补偿 —— 投影顶钉回设计露边阶梯（27/9px 命中带真实存在） */
    let p = { y: DECK_SHIFT + (p0.y - DECK_SHIFT) * deck.spread, z: p0.z * deck.spread, tilt: p0.tilt * deck.spread,
              scale: 1 + (p0.scale - 1) * deck.spread, op: p0.op * deck.spread, shade: p0.shade };
    p.y = deckProjectYComp(p.y, p.z, p.scale);
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
    /* 滑删顺滑批（2026-10-23 六项⑥b）：位姿签名去重 —— 拼一次完整串
     *  （z/opacity/transform 含拖划 extra/shade），与上帧全等即零写。横划
     *  跟手段 deck.pos 不动 ⇒ 非拖卡位姿恒定：修前每帧 4 写/卡（z-index 每
     *  帧重写还会触发叠层重算），修后只有拖卡自己写 —— 合成层内插值，
     *  主线程只剩一次字符串比较（滑删跟手不掉帧的主修）。 */
    const zi = String(100 - Math.round(Math.abs(d) * 10));
    const tf = 'translate(-50%,-50%)' +
      ' translate3d(0,' + p.y.toFixed(2) + 'px,' + p.z.toFixed(2) + 'px)' +
      ' rotateX(' + p.tilt.toFixed(2) + 'deg)' +
      (p.scale && p.scale !== 1 ? ' scale(' + p.scale.toFixed(4) + ')' : '') + extra;
    const opS = p.op.toFixed(3);
    const shadeEl = /** @type {HTMLElement|null} */ (el.querySelector('.card-shade'));
    const shadeS = (p.shade != null ? p.shade : 0).toFixed(3);
    const sig = zi + '|' + opS + '|' + tf + '|' + shadeS;
    const anyEl = /** @type {any} */ (el);
    if (anyEl._deckSig !== sig) {
      anyEl._deckSig = sig;
      el.style.zIndex = zi;
      el.style.opacity = opS;
      el.style.transform = tf;
      if (shadeEl) shadeEl.style.opacity = shadeS;
    }
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
  deck.open = true;
  deckFabQueued = false;
  deck.selFly = null; deck.ghostFx = null; deck.faceFx = null; deck.exitCardFx = null;
  deck.drag = null; deck.cardDrag = null; deck.suppressCardClick = 0;
  deckWheelReset();
  deckMountRing();
  deck.pos = deck.target = 0;          // 前景位 = 环首 = 当前 canvas
  deck.d0s = 0; deck.driveSuspend = false; deck.panelFront = true;
  deck.spread = 0; deck.spreadT = 1;
  panel.classList.add('stacked', 'is-front');
  panel.style.opacity = ''; panel.style.pointerEvents = '';
  deckLayer.classList.add('live');
  layoutDeck();
  syncDeckBox();
  deckFab.setAttribute('aria-pressed', 'true');
  deckLiveBind();                        // ③：活性窗监听（滚动镜像 + 变更重截）
  deckAttachSnapshots();                 // ③b：开卡组时批量截快照（近卡先挂，分帧补）
  deckStartLoop();
}
/** 收回（v4⑤ + v5① 轮换态衔接）：面板位姿瞬时归位 + 卡组 .collapse 淡出交叉。 */
function closeDeck() {
  const panel = document.getElementById('canvas-panel');
  if (!panel || !deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
  deck.finalizing = true;
  deck.spreadT = 0;
  /* UI 批 ② 成因 (a)：收场即清手势重入锁——残留 deck.drag/deck.cardDrag 曾让
     fab 守卫永久吞点击（划走/竖拖进行中 ESC/收场等路径的真 bug）；置 null 后
     迟到的 pointerup 走 no-op（endPanelDrag/endCardDrag 判空返回）。 */
  deck.drag = null;
  deck.cardDrag = null;
  panel.classList.add('no-trans');
  panel.style.transform = '';          // 擦洗中途位姿瞬时归位（前景位基姿）
  panel.style.pointerEvents = '';
  requestAnimationFrame(() => requestAnimationFrame(() => {
    panel.classList.remove('no-trans');        // 恢复过渡：解除缩放 + alpha 交叉
    panel.style.opacity = '1';
    deckLayer.classList.add('collapse');
    panel.classList.remove('stacked', 'is-front');
    deckFab.setAttribute('aria-pressed', 'false');
  }));
  setTimeout(() => {
    deckLayer.querySelectorAll('.deck-card').forEach((el) => el.remove());
    deck.ring = []; deck.M = 0; deck.selfCardEl = null; deck.ringActiveId = null;
    deckLayer.classList.remove('collapse');
    deckLayer.classList.remove('over');
    deckLayer.classList.remove('live');
    panel.style.opacity = '';
    deck.open = false; deck.finalizing = false;
    deck.exitCardFx = null;
    deckClearSnapshots();
    deckWheelReset();
    deckStopLoop();
    deckFlushQueuedToggle();             // ②：收场补发连点排队
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
  if (panel) {
    panel.classList.remove('stacked', 'is-front', 'receiving', 'no-trans', 'restore');
    panel.style.opacity = ''; panel.style.transform = ''; panel.style.pointerEvents = '';
  }
  deckFab.setAttribute('aria-pressed', 'false');
  deck.open = false; deck.finalizing = false; deck.driveSuspend = false;
  deckClearSnapshots();
  deckWheelReset();
  deckStopLoop();
  deckFlushQueuedToggle();
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
  deck.open = false; deck.finalizing = false; deck.driveSuspend = false;
  deckClearSnapshots();
  deckWheelReset();
  deckStopLoop();
  deckFlushQueuedToggle();             // UI 批 ②：收场补发连点排队
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
  deckStartLoop();
  deckFlushQueuedToggle();             // UI 批 ②：FX 收场补发连点排队
}
/** 环流就地收敛（v5①）：摘卡 → 环形旋转把 active 转回环首（pos 同步补偿，每张卡的
 *  d 不变 = 零重排跳动）→ 重挂 data-r。M 变化引起的环绕重分布只影响深处淡出区。 */
function deckSettleRingRemove(id) {
  if (deck.cardDrag && deck.cardDrag.tabId === id) deck.cardDrag = null;   // UI 批 ②：被摘卡即在途拖划卡
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

// ── 关闭请求（右键菜单 + uifix ③e 卡面 × 钮同一条腿；划走关闭经 exitCardFx/faceFx）──
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
    recordTombstone(id); // #910: 关闭其他=每张被关卡都是显式关闭意图
    for (const [k, v] of previewTabs) { if (v === id) previewTabs.delete(k); }
  }
  activeTabId = keepId;
  persistTabs();
  deck.cardDrag = null;                // UI 批 ②：重挂环 = 全卡换血
  deckMountRing();                     // 环重置：canvas 前景（v5①）
  deck.pos = deck.target = 0; deck.d0s = 0; deck.panelFront = true;
  syncCanvasPanelButtons();
  renderCanvasHeader();
  window.__showToast?.('已关闭其他页签', 'info');
  deckStartLoop();
}

// ── ③c 滚轮输入（触控板/鼠标分流 + 事件流滑行）─────────────────────────
// 触控板：macOS 连续小 delta（1~20px/事件，125Hz+）直通 scrub——**末窗速度**
// （最近 120ms 累计 dy / 窗时长，比全程 EMA 更贴近离手瞬间速度）记录动量；
// 原生动量事件持续喂 delta 自然延伸，事件流停 110ms 后由末窗速度起一段指数
// 衰减滑行（补动量被吞的浏览器）。鼠标格：单事件 |dy|≥40 走档位增益
// （≈一格近一卡），无动量语义。所有动量在「新 wheel / 拖划 / 键盘 / 收场」
// 四路口截停。
const DECK_WHEEL_V_WINDOW = 120;      // 末窗速度窗口（ms）
const DECK_WHEEL_INST_FLOOR = 0.3;    // 滑行武装的瞬时速度地板（px/ms——离手已停不起滑）
let wheelLastT = 0;        // 最近 wheel 事件时戳
let wheelV = 0;            // 末窗速度（px/ms）
let wheelInstV = 0;        // 最近事件瞬时速度（滑行武装第二判据）
let wheelTimer = 0;        // 事件流静默计时
let wheelGlideV = 0;       // 滑行速度（≠0 = 滑行中，deckFrame 消耗）
function deckWheelReset() {
  wheelLastT = 0; wheelV = 0; wheelInstV = 0; wheelGlideV = 0;
  if (wheelTimer) { clearTimeout(wheelTimer); wheelTimer = 0; }
}

// ── 输入绑定（滚轮 / 键盘 / 面板拖划〔主导轴：横=划走关闭 · 纵=scrub 换卡〕；
//    deck 层与卡片的监听在卡片工厂里，共用同一套拖划公共腿）──
function bindDeckInput() {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;

  // 滚轮 = 连续擦洗 + 前景轮换（v5①）+ uifix ③c 分流改造：
  //   · 捕获域收窄到叠卡面（deck 层 / 缩放态面板 / fab / foot）——页面其余区域
  //     （聊天列等）的双指滚动不被劫持（「不与页面滚动打架」）；
  //   · 触控板小 delta 直通 scrub + 事件流滑行补动量；鼠标格档位增益；
  //   · 可编辑焦点让路；Ctrl+滚轮（页面缩放语义）不拦截不擦洗。
  document.addEventListener('wheel', (e) => {
    if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
    if (e.ctrlKey) return;
    const tgt = e.target;
    if (!(tgt instanceof Element)) return;
    if (tgt.closest('input, textarea, [contenteditable="true"], .monaco-editor, .cm-editor')) return;
    const inDeck = (deckLayer && deckLayer.contains(tgt)) ||
      panel.contains(tgt) || !!tgt.closest('#deck-fab');
    if (!inDeck) return;               // 叠卡面之外：原生滚动（不 preventDefault）
    e.preventDefault();
    if (deck.M < 2) return;            // 单卡无环可转
    const dy = e.deltaY * (e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 100 : 1);
    wheelGlideV = 0;                   // 新输入立即截停旧滑行
    const notch = Math.abs(dy) >= DECK_NOTCH_PX;   // 鼠标格：无动量语义，只步进
    if (!notch) {
      // 末窗速度（W=120ms 滑窗逼近）：窗内旧速度按剩余窗权衰减、新 dy 全计；
      // 事件间隔超窗 = 整窗重来。快滚收敛到即时速度，慢滚/尾流自然归零。
      const dtms = Math.max(1, e.timeStamp - (wheelLastT || e.timeStamp - 16));
      wheelInstV = dy / dtms;
      if (dtms > DECK_WHEEL_V_WINDOW) wheelV = dy / dtms;
      else wheelV = (wheelV * (DECK_WHEEL_V_WINDOW - dtms) + dy) / DECK_WHEEL_V_WINDOW;
      wheelLastT = e.timeStamp;
    }
    const gain = DECK_WHEEL_GAIN * (notch ? deckPeekStep().wheelNotch : 1);
    deck.target += dy * gain;
    deck.target = Math.max(deck.pos - deck.M, Math.min(deck.pos + deck.M, deck.target));
    if (!notch) {
      if (wheelTimer) clearTimeout(wheelTimer);
      wheelTimer = setTimeout(() => {  // 事件流静默 → 末窗速度起滑行（瞬时速度过地板才武装）
        wheelTimer = 0;
        if (!deck.open || deck.M < 2) { wheelV = 0; return; }
        const armed = Math.abs(wheelV) >= 0.08 && Math.abs(wheelInstV) >= DECK_WHEEL_INST_FLOOR;
        wheelGlideV = armed
          ? Math.max(-DECK_WHEEL_GLIDE_VMAX, Math.min(DECK_WHEEL_GLIDE_VMAX, wheelV))
          : 0;
        wheelV = 0;
        if (Math.abs(wheelGlideV) >= 0.08) deckStartLoop();
      }, DECK_WHEEL_GLIDE_DELAY);
    } else if (wheelTimer) { clearTimeout(wheelTimer); wheelTimer = 0; wheelV = 0; }
    deckStartLoop();
  }, { passive: false });

  // 键盘：↑↓ 连续擦洗 ±1；Enter 选中前景卡（= 换入；canvas 前景时 = 收回）；Esc 收回。
  document.addEventListener('keydown', (e) => {
    if (!deck.open || deck.finalizing || deck.selFly || deck.ghostFx || deck.faceFx) return;
    const tgt = e.target;
    if (tgt instanceof Element && (tgt.closest('input, textarea, select, [contenteditable="true"], .monaco-editor, .cm-editor'))) return;
    if (e.key === 'Escape') { e.preventDefault(); closeDeck(); }
    else if (e.key === 'ArrowDown' || e.key === 'ArrowRight' || e.key === 'PageDown') { e.preventDefault(); if (deck.M > 1) { wheelGlideV = 0; deck.target += 1; deckStartLoop(); } }
    else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft' || e.key === 'PageUp') { e.preventDefault(); if (deck.M > 1) { wheelGlideV = 0; deck.target -= 1; deckStartLoop(); } }
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
    wheelGlideV = 0;                                               // 拖划接管：截停滚轮滑行
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

  // resize / 列宽拖拽 / overlay 形态翻转：重排 + 盒同步。UI 批 ②：deck 关着也要
  // 同步 fab 盒——否则窗口/列宽变化后浮钮钉区漂移（看起来「点了没反应」）。
  window.addEventListener('resize', () => {
    syncDeckBox();
    if (!deck.open) return;
    layoutDeck();
    deckStartLoop();
  });
  window.addEventListener('nebflow-col-resize', () => {
    syncDeckBox();
    if (!deck.open) return;
    layoutDeck();
    deckStartLoop();
  });
}

// ── 自动化/调试钩子（spec 断言通道，同 v5 样张 __demo.state() 口径）──
/** @type {any} */ (window).__canvasDeck = {
  open: () => openDeck(),
  close: () => closeDeck(),
  /* uifix 批：作者挑版热切换（body.deck-look-* 单一来源；返回生效档位） */
  setLook: (v) => {
    if (!DECK_LOOKS[v]) return deckLook();
    document.body.classList.remove(...Object.keys(DECK_LOOKS).map((k) => 'deck-look-' + k));
    document.body.classList.add('deck-look-' + v);
    if (deck.open) { deckStartLoop(); }        // 间距档位变化 → 重排
    return v;
  },
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
        /* UI 批 ④：动效层坡道常量单一来源 */
        depthScaleNear: DECK_DEPTH_SCALE_NEAR, depthScaleFar: DECK_DEPTH_SCALE_FAR,
        depthZ: DECK_DEPTH_Z, fallShrink: DECK_FALL_SHRINK,
        /* UI 批 ④②：透视补偿常量（与 split.css #deck-layer perspective 同源） */
        perspective: DECK_PERSPECTIVE, originYK: DECK_ORIGIN_YK,
      },
      cardShadow: getComputedStyle(document.documentElement).getPropertyValue('--card-shadow').trim(),
      /* uifix 批断言通道：look 档位 / 快照挂载 / × 钮 / 竖排提示退场 */
      look: deckLook(),
      lookPeek: deckPeekStep(),
      snapshots: {
        cached: deckSnapshots.size,
        live: deckLayer ? deckLayer.querySelectorAll('.card-thumb.has-snap').length : 0,
        pending: deckLayer ? deckLayer.querySelectorAll('.card-thumb[data-snap="pending"]').length : 0,
        mock: deckLayer ? deckLayer.querySelectorAll('.card-thumb[data-snap="mock"]').length : 0,
        /* UI 批 ③：活性窗（可视卡 ±2）同步中的卡数 */
        liveWindow: deckLive.near.size,
      },
      cardXButtons: deckLayer ? deckLayer.querySelectorAll('.deck-card .deck-card-x').length : 0,
      swipeHints: {
        panel: document.querySelectorAll('.deck-swipe-hint').length,
        card: deckLayer ? deckLayer.querySelectorAll('.card-swipe-hint').length : 0,
      },
      /* UI 批 ①：帮助条退役断言通道（deck-foot 元素零命中） */
      helpRetired: !document.getElementById('deck-foot'),
      /* UI 批⑧：单卡独立窗口断言通道（模式 + 独立中页签清单） */
      cardWindowMode,
      independent: [...independentWins.keys()],
      /* UI 批 ②：连点排队断言通道 */
      fabQueued: deckFabQueued,
      wheelGlide: wheelGlideV,
    };
  },
};



// ── Initialization ─────────────────────────────────────────

// ═════════════════════════════════════════════════════════════════════════
// UI 批⑨（作者令 2026-10-21）卡头毛玻璃胶囊 · 桌面手势集
//
// 形态（split.css §⑨）：.canvas-header 浮层胶囊（absolute top 10 居中 + 玻璃
// backdrop + .scroll-under-glass 裙边），#canvas-content 顶满全高 —— 文字从
// 胶囊底下穿过渐隐。胶囊手势面 touch-action:none，与内容区滚动天然分区
// （手势只绑在胶囊元素上；内容滚动发生在 pane 内部滚动器，互不劫持，CC7）。
//
// 四链路（B）：
//   按住 = 按压触感（.is-pressed → scale .97 + brightness，CSS）；
//   按住上滑/下滑 = 换卡 —— 与堆叠视图换卡同语义：同环（deckBuildRing，
//     ring[0]=active）、同向（下滑 dy>0 = 下一张，deck 竖拖/滚轮同规）、
//     同阈（轴锁 8px 同 DECK_AXIS_LOCK_PX 档；释放 |dy|>48 换一张）；
//   左右滑 = 关闭卡片 —— 与 deck 划走关闭同语义（closeTab 共核），释放
//     |dx|>64 关活动页签；滑删动画批（2026-10-23 六项⑥a）：跟手 1:1（净空内
//     不出面板）+ rotateZ 微倾（deck 同斜率 0.028），释放过阈 = 甩出 fling
//     （0.6×面板宽+240 与出板行程取小、13deg、快淡出）→ 过渡完 closeTab ——
//     仿堆叠视角左右滑动删卡的动效；不足阈松手照旧回弹；
//   右键 = 引用 —— 复用既有 canvas-tab-context-menu 形态（showCanvasRefMenu）；
//     deck 开着时与面板菜单同款 actions（关闭/关闭其他），stopPropagation 防
//     面板级菜单重复弹。
// 跟手反馈：--head-dx/--head-dy/--head-rot 变量（CSS transform 消费），拖动中
// is-dragging 关过渡 1:1 跟手，松手回弹 + 评估阈值；甩出走 .is-swipe-out
// （过渡重挂）。钮群（pop/close/merge/fab）closest 直通，永不吞点击。
// ═════════════════════════════════════════════════════════════════════════

/** 胶囊手势阈值（轴锁与 deck 同档；换卡/关卡阈给手势释放档）。 */
const CARDHEAD_AXIS_LOCK_PX = 8;
const CARDHEAD_SWITCH_PX = 48;
const CARDHEAD_CLOSE_PX = 64;
/** 划删跟手/甩出档（滑删动画批 2026-10-23 六项⑥a，仿 deck 环卡划走）：
 *  跟手 1:1 但不出面板（按可用净空钳制，防 panel overflow 硬裁切线）；
 *  甩出行程 = deck fling 档（0.6×面板宽 + 240）与「净空 + 半宽 + 80」取小
 *  —— 甩过面板缘即没，快淡出先于行程走完；rotateZ 斜率与 deck 同源
 *  （dx×0.028deg，甩出定值 13deg）。 */
const CARDHEAD_FOLLOW_CAP_PX = 140;
const CARDHEAD_SWIPE_ROT_K = 0.028;
const CARDHEAD_SWIPE_ROT_CAP = 6;
const CARDHEAD_FLING_MS = 240;

/** 换卡邻位：deck 同环同序（ring[0]=active）——下滑(+1)=下一张、上滑(-1)=上一张
 *  （环绕）。单页签（环长 1）不换。 */
function cardHeadRingNeighbor(delta) {
  const ring = deckBuildRing();
  if (ring.length < 2) return null;
  return delta > 0 ? ring[1] : ring[ring.length - 1];
}

/** 桌面胶囊手势装配（initCanvas 一次；卡窗态同挂 —— 卡头=卡窗「合并」钮行）。
 *  手机层（body.mobile-layer）整腿让位：卡头手势归 navController 卡头胶囊
 *  （直滑换卡/关卡 + 长按引用钮），双绑会互相覆盖激活页签（首轮实测）。 */
function initCardHeadGestures() {
  const hdr = /** @type {HTMLElement|null} */ (document.querySelector('#canvas-panel .canvas-header'));
  if (!hdr) return;
  const flags = /** @type {any} */ (hdr);
  if (flags._cardheadGestures) return;
  flags._cardheadGestures = true;
  const isMobileLayer = () => document.body.classList.contains('mobile-layer');
  /** @type {{id: number, x0: number, y0: number, dx: number, dy: number, axis: string|null, moved: boolean, roomL: number, roomR: number}|null} */
  let g = null;
  /** 甩出动画在途旗（滑删动画批）：在途封锁新手势，收场统一清。 */
  let swipeFx = false;
  const reset = () => {
    hdr.classList.remove('is-pressed', 'is-dragging');
    hdr.style.setProperty('--head-dx', '0px');
    hdr.style.setProperty('--head-dy', '0px');
    hdr.style.setProperty('--head-rot', '0deg');
  };
  /** 跟手净空（滑删动画批）：拖动起点时量一次左右可用行程（面板缘 − 胶囊缘
   *  − 8px 缝），跟手钳制不出面板 —— 不给 overflow:hidden 留硬裁切面。 */
  const measureRoom = () => {
    const panel = document.getElementById('canvas-panel');
    const hr = hdr.getBoundingClientRect();
    const pr = panel ? panel.getBoundingClientRect() : hr;
    g.roomL = Math.max(0, hr.left - pr.left - 8);
    g.roomR = Math.max(0, pr.right - hr.right - 8);
  };
  hdr.addEventListener('pointerdown', (e) => {
    if (isMobileLayer() || swipeFx) return;
    if (e.button !== 0) return;
    if (/** @type {Element} */ (e.target).closest('button')) return;   // 钮群直通
    g = { id: e.pointerId, x0: e.clientX, y0: e.clientY, dx: 0, dy: 0, axis: null, moved: false, roomL: 0, roomR: 0 };
    measureRoom();
    try { hdr.setPointerCapture(e.pointerId); } catch { /* 已释放 */ }
    hdr.classList.add('is-pressed');                                   // 按压触感（按住即有）
  });
  hdr.addEventListener('pointermove', (e) => {
    if (isMobileLayer() || !g || e.pointerId !== g.id) return;
    g.dx = e.clientX - g.x0;
    g.dy = e.clientY - g.y0;
    if (!g.axis && (Math.abs(g.dx) > CARDHEAD_AXIS_LOCK_PX || Math.abs(g.dy) > CARDHEAD_AXIS_LOCK_PX)) {
      // 主导轴锁（deck 同规：|dx|>|dy| 取横，平局取纵 —— 纵=非破坏换卡）
      g.axis = Math.abs(g.dx) > Math.abs(g.dy) ? 'x' : 'y';
      g.moved = true;
      hdr.classList.remove('is-pressed');   // 拖动态松开按压形（跟手位移接管）
      hdr.classList.add('is-dragging');
    }
    if (g.axis === 'x') {
      // 滑删动画批：跟手 1:1（净空内）+ 轻微 rotateZ 倾斜（deck 同斜率）
      const room = g.dx < 0 ? g.roomL : g.roomR;
      const dx = Math.max(-room, Math.min(room, g.dx));
      hdr.style.setProperty('--head-dx', dx.toFixed(1) + 'px');
      hdr.style.setProperty('--head-rot',
        (Math.max(-CARDHEAD_SWIPE_ROT_CAP, Math.min(CARDHEAD_SWIPE_ROT_CAP, g.dx * CARDHEAD_SWIPE_ROT_K))).toFixed(2) + 'deg');
    } else if (g.axis === 'y') hdr.style.setProperty('--head-dy', Math.max(-12, Math.min(12, g.dy)) + 'px');
  });
  /** 横滑甩出（滑删动画批六项⑥a，仿 deck 环卡划走 fling）：行程朝拖向甩过
   *  面板缘 + 13deg 旋转 + 快淡出（透明度先行，过渡完再关页签 —— 名称换装
   *  藏在不可见段）；0.24s 与 deck DECK_FLING_DUR 同档。reduce-motion 直关。 */
  const swipeOutFly = (dir, room) => {
    swipeFx = true;
    const panel = document.getElementById('canvas-panel');
    const pw = panel ? panel.getBoundingClientRect().width : 700;
    const dist = Math.min(pw * 0.6 + 240, room + hdr.offsetWidth / 2 + 80);
    hdr.classList.remove('is-dragging');
    hdr.classList.add('is-swipe-out');
    hdr.style.setProperty('--head-dx', (dir * dist).toFixed(1) + 'px');
    hdr.style.setProperty('--head-rot', (dir * 13).toFixed(2) + 'deg');
    hdr.style.opacity = '0';
    const entry = activeTabId ? tabs.get(activeTabId) : null;
    setTimeout(() => {
      reset();
      hdr.classList.remove('is-swipe-out');
      hdr.style.opacity = '';
      swipeFx = false;
      if (entry) closeTab(entry.id);
    }, deckRm.matches ? 0 : CARDHEAD_FLING_MS);
  };
  const finish = (e) => {
    if (isMobileLayer() || !g || e.pointerId !== g.id) return;
    const { dx, dy, axis, moved } = g;
    const room = g ? (dx < 0 ? g.roomL : g.roomR) : 0;
    g = null;
    reset();
    if (!moved) { ringClickCenter(); return;          // 点击界内：环游标胶囊即切换（游标=激活时零动作）
    }
    const entry = activeTabId ? tabs.get(activeTabId) : null;
    if (!entry) return;
    if (axis === 'y' && Math.abs(dy) > CARDHEAD_SWITCH_PX) {
      const nb = cardHeadRingNeighbor(dy > 0 ? 1 : -1);   // 下滑=下一张（deck 同语义）
      if (nb && nb.id !== entry.id) setActiveTab(nb.id);
    } else if (axis === 'x' && Math.abs(dx) > CARDHEAD_CLOSE_PX) {
      swipeOutFly(dx >= 0 ? 1 : -1, room);                // 划走关闭：先甩出动画再 closeTab（共核不变）
    }
  };
  hdr.addEventListener('pointerup', finish);
  hdr.addEventListener('pointercancel', finish);
  // 右键 = 引用 + 胶囊菜单契约（2026-10-22 作者令：多开/关闭/在独立窗口打开；
  // 引用保留。actions 与胶囊环共用 capsuleMenuActions 一份语义）
  hdr.addEventListener('contextmenu', (e) => {
    if (isMobileLayer()) return;   // 手机长按引用钮 = navController 腿（Android 长按会派发 contextmenu）
    const entry = activeTabId ? tabs.get(activeTabId) : null;
    if (!entry) return;
    e.preventDefault();
    e.stopPropagation();   // 面板级 contextmenu（deck 态）不再重复弹
    showCanvasRefMenu(e.clientX, e.clientY, entry, capsuleMenuActions(entry));
  });
}

// ── 胶囊环（2026-10-22 作者令追加：hover 态圆环排布 · 滚轮换选 · 点击切换）──
// 作者令：平时态 = 单胶囊（缩小）；hover 态 = 各页签胶囊沿**竖向**圆环排布、
// 最多同时可见 3 个；滚轮换选（hover 交互改向令 2026-10-22：「现在…hover 之后
// 左右滑动…改为上下的，这样才方便 Windows 这样的电脑用鼠标进行操作」——鼠标
// 滚轮 = 纵向 deltaY，横滑触控板用户无此输入）：**纵滚（deltaY）主导**，触控板
// 横滑（deltaX）保留为同环副手势，按主导轴取用；点击即切换；上下缘位沿曲率
// rotateX 透视 + 模糊扭曲（竖向 cover-flow），材质 = 全站统一 frosted token；
// 右键菜单 = 多开/关闭/在独立窗口打开（capsuleMenuActions 共用一份语义）。
// 修复批（作者令 2026-10-23「hover 要在上下显示…上下对称；胶囊大小固定；相对
// canvas 居中」）：胶囊常态回 canvas 顶带横轴居中（split.css 居中批，v2①
// 「居中标题头」原位 —— 不在画布中央挡内容命中：wiki 图谱/流程图中心区节点
// 点击实证）。顶部修正批（作者令 2026-10-23 逐字「canvas的hover要保持在顶部
// 居中，而不是整个canvas居中，顶部有些超出是正常且允许的。但是不要太多。」）：
// 对称批的「hover 沉降 canvas 垂直中点」（.ring-open + --ring-dy）撤销 —— 环锚
// 常驻顶带，横轴中线居中不变；缘位仍上下对称（上一签上展 −edgeY、下一签下垂
// +edgeY，|y| 严格相等；rotateX 反号等幅），上缘位允许**略微越出面板顶缘**
// （负向超出正常且允许 —— 真越出批六项②：环层挂 body，探出段真实渲染在
// canvas 顶缘外，不再被面板 overflow:hidden 裁切；越出量克制 = 胶囊自身高度的
// 一小部分（RING_TOP_OUT），绝不沉向 canvas 中部）。
// 胶囊本体任何状态尺寸恒定（零缩放零呼吸）。
// 收环判定 = **hover 区联合盒**（环开点 ∪ 胶囊本体 ∪ 上下缘位胶囊，各外扩
// 14px）：竖环几何下「中心→缘位」有 ~edgeY 间隙，pointerleave 单源会立即误收
// （甚至开→收振荡）—— 环开期间 document pointermove 跟踪指针，离区才调度
// 140ms 宽限收环。（对称批的「环开点→沉底胶囊顶走廊 ±90px」随沉降撤销一并
// 退役 —— 环锚回顶带，胶囊不再离位，走廊无事可桥。）
// 滚轮 = 环开期间 document 捕获（capture），**hover 区内消费为换选、区外零响
// 应**（触控板手感批 2026-10-25 收窄：旧「面板矩形内一律消费」会抢面板内容
// 滚动 —— 光标在胶囊/环胶囊/环开点走廊之外时页面滚动照旧，不 preventDefault
// 不换选）；面板外（侧栏/聊天）滚轮不消费零感知照旧。#716 捏合缩放分区：环开
// = 环优先（区内），画布区缩放/平移与 pane 滚动在环开期间 Escape/离区即还。
// 按住=按钮语义（作者令③）：胶囊本体 <button> + 全头 user-select:none（CSS）
// + dragstart preventDefault（拦从胶囊发起的原生拖拽；OS 文件拖入 drop 不受影响）。
// 实现形态：纯前端组件（真越出批六项②：#canvas-capsule-ring 挂 body 层，
// position:fixed 0×0 锚在标题头中心视口点 = 胶囊中心 —— 面板裁剪盒够不到，
// 上缘位真实越出顶缘渲染；环锚常驻顶带 —— 顶部修正批撤销沉降；胶囊 absolute
// 沿弧 transform），与左栏控制面零共享。手机层/卡窗态整腿让位（各有自己的
// 卡头形态）；deck 叠卡态不开环（叠卡有自己的环卡滚轮语义，双环互搏）。
// 触控板手感批（作者报障 2026-10-25 13:07「使用 mac 的触控板进行滑动的时候，
// 不够灵敏和丝滑」）：旧「单事件过恒定阈值即切」在两类输入下两难 —— 触控板
// 滑动是高频小增量 + 惯性末段，鼠标滚轮是离散大步进（|dy|≈±100）；阈高触控板
// 要用力甩（不灵敏）、阈低惯性一冲连跳三格（不丝滑）。改**设备分档 + 累积器
// + 冷却**三件：
//   · 设备分档（deck DECK_NOTCH_PX 同口径）：单事件 |d|（deltaMode 归一 px）
//     ≥ RING_MOUSE_DELTA = 鼠标离散格（一格意图）→ 低阈 RING_WHEEL_STEP_MOUSE
//     即切；否则触控板高频小增量 → 按方向累积过 RING_WHEEL_STEP_TRACKPAD 才切
//     （微漂不移游标）。
//   · 冷却：切换后 RING_WHEEL_COOLDOWN_MS 内事件只消费不累积 —— 轻扫一挥恰切
//     一格、惯性末段不连跳（冷却后重新累积）。
//   · 反向归零：累积中途反向 = 清账重积（反转跟手，不吃旧账死区）。
//   · 步进动画：steady 态游标步进 0.18s 快跟手档（split.css §ring）—— 0.36s
//     每步重定相位拖泥带水；收放 morph 档保持不变。
const RING_WHEEL_STEP_TRACKPAD = 48; // 触控板档阈值（X/Y 同档 ≈ 一挥轻扫的累积量）
const RING_WHEEL_STEP_MOUSE = 32;    // 鼠标离散格档阈值（一格意图明确，单事件即切）
const RING_MOUSE_DELTA = 40;         // 单事件 |d|≥40 判鼠标格（触控板指纹 1~20px）
const RING_WHEEL_COOLDOWN_MS = 200;  // 切换冷却（惯性末段防连跳；150-250ms 档取中）
const RING_CLOSE_GRACE_MS = 140; // 离环收起宽限（快速掠过不闪收）
const RING_ZONE_PAD_PX = 14;  // hover 区外扩（缘位胶囊 hit 盒外缘，穿越不闪收）
const RING_STEADY_MS = 370;   // 真越出批：展开过渡（0.36s）后缘位才接 pointer 的稳态门
/** 环开上缘位越出面板顶缘的量（顶部修正批 2026-10-23 作者令「顶部有些超出是
 *  正常且允许的。但是不要太多」）：**布局空间**系数 × 胶囊高（clamp 8–24px）。
 *  缘位胶囊带 rotateX(34°)+translateZ(-110)（perspective 640）投影把角点向
 *  锚收回 —— 视觉越出 ≈ 布局越出的 0.4 倍（实测 1440×900：布局 19.2px →
 *  视觉 ~8px ≈ 胶囊高 1/3，即「自身高度的一小部分量级」）。真越出批
 *  （2026-10-23 六项②）：环层挂 body 后探出段**真实渲染**在 canvas 顶缘外
 *  （修前面板 overflow:hidden 硬裁切线退役），越出量保持本克制档不变。
 *  下缘位 |y| 对称下垂（上下对称裁定保持）。 */
const RING_TOP_OUT = 0.8;

/** 环开态单例（{ ids: string[], cursor: number, lastDir: number }；null = 环闭）。
 *  lastDir = 最近一次滚轮换选方向（±1）——双卡环（n=2）的方向感知布局档消费：
 *  非游标胶囊驻「来向侧」，滑动永远读作「来一张/走一张」的滚动而不是弹回。
 *  hdr 重建时随 initCanvas 重挂，状态本体放模块级 —— 手势 finish() 的
 *  ringClickCenter 同读。 */
let ringState = /** @type {{ids: string[], cursor: number, lastDir: number}|null} */ (null);

/* 真越出批（作者令 2026-10-23 六项②）：环层挂 body（#canvas-capsule-ring
 *  position:fixed，z-246 —— overlay 面板 240 之上、fab 250 之下、模态 300+ 之下），
 *  不再是面板子树 —— 面板 overflow:hidden 裁不到它，上缘位探出段**真实渲染**在
 *  canvas 顶缘外（硬裁切线消灭；越出量仍走 RING_TOP_OUT 克制档不变）。锚定与
 *  几何随之改视口坐标：anchor() 直写视口点，layout() 的 edgeY 反解改
 *  「锚视口 y − 面板视口顶 − h/2 + out」（与旧 offsetTop 链同义，对 offsetParent
 *  链免疫）。中心位胶囊 pointer-events:none（split.css）—— 环层如今在标题头
 *  之上，放行事件才能照旧落到标题头手势链（按压/竖滑/横滑/右键契约不变）。 */

/** 中心位（环游标）胶囊的「点击即切换」——标题头手势 finish() 的 !moved 臂
 *  消费（中心位 pointer 事件被标题头 capture 重定向，click 不达胶囊）。
 *  游标 = 激活页签时零动作（CC1「点击界内零动作」契约保留）。@returns {boolean} */
function ringClickCenter() {
  const id = ringCursorId();
  if (!id) return true;                              // 环闭：界内零动作（legacy）
  if (id === activeTabId) return true;               // 界内零动作（态不变）
  setActiveTab(id);                                  // canvas-tab-switched 收环
  return true;
}
/** 环游标当前指的页签 id（环闭 = null）。@returns {string|null} */
function ringCursorId() { return ringState ? ringState.ids[ringState.cursor] || null : null; }

function initCapsuleRing() {
  const hdr = /** @type {HTMLElement|null} */ (document.querySelector('#canvas-panel .canvas-header'));
  if (!hdr) return;
  const flags = /** @type {any} */ (hdr);
  if (flags._capsuleRing) return;
  flags._capsuleRing = true;
  if (cardWindowMode) return;                      // 卡窗 = 单卡查看器，无环
  const isMobileLayer = () => document.body.classList.contains('mobile-layer');
  const panelEl = /** @type {HTMLElement} */ (hdr.closest('#canvas-panel'));

  let acc = 0;                                     // 横滑游标累加器（deltaX 刻度）
  let accY = 0;                                    // 纵滚游标累加器（deltaY 刻度，整数步进零浮点）
  let wheelLockUntil = 0;                          // 滚轮切换冷却截止（performance.now() ms，触控板手感批）
  let leaveTimer = 0;
  let steadyTimer = 0;                             // 真越出批：稳态门定时（展开过渡后缘位才可点）

  const root = document.createElement('div');
  root.className = 'capsule-ring';
  root.id = 'canvas-capsule-ring';
  root.hidden = true;
  // 真越出批：环层挂 body（fixed + z-246）—— 面板 overflow:hidden 裁不到，
  // 上缘位探出段真实渲染在 canvas 顶缘外（split.css §ring 注详）。
  document.body.appendChild(root);

  /** 环序 = 页签插序（tabs Map 序 —— 与标签条/deckBuildRing 源序同源）。 */
  const build = () => {
    root.textContent = '';
    ringState.ids = [...tabs.keys()];
    for (const id of ringState.ids) {
      const entry = tabs.get(id);
      if (!entry) continue;
      // div[role=button] 而非 <button>：标题头手势按 closest('button') 直通钮群
      // —— 环胶囊若是 button，环开着时按压/竖滑换卡/横滑关卡全被钮群守卫吞掉
      // （CC1/CC2/CC3 契约）。中心位事件照旧穿透标题头走手势链（按住=按钮语义），
      // 缘位 = 纯点击钮（stopPropagation）。切换语义：缘位 click / 中心位
      // ringClickCenter()（finish !moved 臂）。
      const b = document.createElement('div');
      b.className = 'cr-capsule' + (isTabDirty(entry) ? ' is-dirty' : '');
      b.setAttribute('role', 'button');
      b.dataset.tabId = id;
      b.innerHTML = '<span class="cr-icon">' + deckIconOf(deckTypeFamily(entry)) + '</span>' +
        '<span class="cr-dot"></span><span class="cr-name">' + escapeDeckText(entry.title || entry.id) + '</span>';
      // 中心位让 pointer 事件穿透标题头（按压触感/竖滑换卡/横滑关卡手势照旧，
      // 作者令③「按住=交互按钮」）；缘位胶囊 = 纯按钮（不进手势链）。
      b.addEventListener('pointerdown', (e) => { if (ringState && ringState.ids[ringState.cursor] !== id) e.stopPropagation(); });
      // 缘位点击即切换（中心位被标题头 pointer capture 重定向 —— 走
      // ringClickCenter()，见 initCardHeadGestures finish() 的 !moved 臂）。
      b.addEventListener('click', () => {
        if (!ringState || ringState.ids[ringState.cursor] === id) return;
        setActiveTab(id);                          // canvas-tab-switched 收环
      });
      b.addEventListener('contextmenu', (e) => {
        if (isMobileLayer()) return;
        const entry2 = tabs.get(id);
        if (!entry2) return;
        e.preventDefault();
        e.stopPropagation();                        // 不与标题头/面板级菜单双弹
        showCanvasRefMenu(e.clientX, e.clientY, entry2, capsuleMenuActions(entry2));
      });
      root.appendChild(b);
    }
  };

  /** 位姿（2026-10-23 作者令「hover 要在上下显示，这样才有滚动的感觉」——上下
   *  对称；顶部修正批：环锚常驻顶带，不沉降）：rel = 到游标的最短环距（wrap）；
   *  0 = 中心清晰（与常态胶囊同位同尺寸，尺寸恒定）；±1 = 缘位 cover-flow：
   *  上一签（rel<0）上展 −edgeY、下一签（rel>0）下垂 +edgeY，**|y| 严格相等**
   *  （上下对称断言的几何来源）；rotateX 反号等幅（上缘位 +34° 顶边后仰 /
   *  下缘位 −34° 底边后仰 —— 竖圆柱同侧曲率）；透视由 .capsule-ring 自身
   *  perspective 提供（原点 = 环锚 = 胶囊中心）；模糊扭曲（CSS is-edge）。
   *  |rel|≥2 = 弧外不可见（±2× 对称轨迹保留，收放有连续感）。
   *  间距 = 环锚面板几何反解（顶部修正批 + 真越出批六项②视口版）：edgeY 令
   *  上缘位顶越过面板顶缘 RING_TOP_OUT×h（胶囊高的一小部分 —— 作者令「顶部
   *  有些超出…不要太多」，探出段真实渲染在顶缘外 —— 环层挂 body 裁切盒失效），
   *  下缘位对称下垂；h + 2 下限兜底防退化叠置。本体尺寸恒定 ⇒ 间距恒定。 */
  /** #926 件1（作者令 2026-10-26「hover 的时候不要改变文字的布局」）：中心覆层
   *  的右内缝随 pop 钮实占宽同步（--cr-popw = 26 钮 + 6 gap，隐藏/缺席 = 0）——
   *  pop 可见时覆层文字盒收窄到与被替换的常态 .t-name 盒逐像素等宽（106px 档），
   *  隐藏时回宽档（138px）：hover 换层前后 ellipsis 截断点不变，文字行零换排。
   *  layout() 每次调（openRing / 滚轮换选 / 开环换签）都同步 —— CR4 开环换签时
   *  pop 可见性可能随新活动页签翻转，落在这条路径上刷新。 */
  const syncCenterInset = () => {
    const pop = hdr.querySelector('.canvas-pop-btn');
    const on = !!pop && getComputedStyle(pop).display !== 'none';
    root.style.setProperty('--cr-popw', on ? '32px' : '0px');
  };

  const layout = () => {
    if (!ringState) return;
    anchor();                                        // 真越出批：先回锚（环开中窗口尺寸变化随动）
    syncCenterInset();                               // #926 件1：中心覆层右内缝随 pop 钮实占同步
    const { ids, cursor } = ringState;
    const n = ids.length;
    const kids = /** @type {HTMLElement[]} */ ([...root.children]);
    const centerKid = cursor >= 0 && cursor < n ? kids[cursor] : null;
    const h = centerKid ? centerKid.offsetHeight : 28;
    const out = Math.max(8, Math.min(24, h * RING_TOP_OUT));
    // 环锚面板纵深（真越出批视口版）= 锚视口 y − 面板视口顶（getBoundingClientRect，
    // 对 offsetParent 链免疫，语义与旧 hdr.offsetTop + root.offsetTop 同一）；
    // edgeY 反解自「上缘位顶 = 锚 − edgeY − h/2 = 面板顶 − out」。
    const pr = panelEl.getBoundingClientRect();
    const ay = parseFloat(root.style.top) || 0;
    const edgeY = Math.max(h + 2, ay - pr.top - h / 2 + out);
    for (let i = 0; i < kids.length; i++) {
      let rel = (i - cursor + n) % n;
      if (rel > n / 2) rel -= n;
      /* 双卡方向感知档（作者令 2026-10-26「卡片数 <3 也要能正常滚动/切换」）：
       * n=2 时环距取模恒落 +1（下方）——两卡来回换选的位姿集合不变，视觉读作
       * 「弹回自己」。改：非游标胶囊驻 lastDir 侧（滚上来 = 它从上方来，滚下去
       * = 从下方来），单步动画 = 真「滚一格」；≥3 卡模运算本就覆盖上下两侧，不
       * 走本档（动画按实际卡数退化，不强求三卡）。 */
      if (n === 2 && rel !== 0) rel = ringState.lastDir === -1 ? -1 : 1;
      const ar = Math.abs(rel);
      const k = kids[i];
      k.classList.toggle('is-center', rel === 0);
      k.classList.toggle('is-edge', ar === 1);
      k.classList.toggle('is-far', ar > 1);
      const side = rel < 0 ? -1 : 1;
      const y = rel === 0 ? 0 : side * (ar === 1 ? edgeY : edgeY * 2);
      k.style.setProperty('--cr-y', y.toFixed(1) + 'px');
      k.style.setProperty('--cr-z', (ar === 0 ? 0 : ar === 1 ? -110 : -230) + 'px');
      /* rotateX(+θ) = 顶边远离视者（CSS y 轴向下 ⇒ 顶部 y<0 ⇒ z' = y·sinθ < 0）：
         上缘位（侧 −1）取 +34°（顶边后仰 = 竖圆柱外侧曲率），下缘位取 −34°
         （底边后仰）—— 反号等幅，双缘同读作「圆柱外侧」。 */
      k.style.setProperty('--cr-rx', String(rel === 0 ? 0 : -side * (ar === 1 ? 34 : 44)) + 'deg');
    }
  };

  /** 环锚 = 标题头胶囊盒（.canvas-header）中心（环收放与常态胶囊同位无缝 morph）。
   *  #926 件1（作者 2026-10-26 报障「hover 的时候不要改变文字的布局」）：旧锚取
   *  #canvas-title 中心 —— pop 钮（独立窗口入口 26px+gap6）可见时标题 fit-content
   *  盒整体偏左（title 中心 ≠ 胶囊中心，1440 面实测偏 ~13.5px），hover 中心覆层
   *  随之左移 = 文字行平移换位 + 前导图标顶穿胶囊左圆角（长名 ellipsis 态尤甚）。
   *  改锚胶囊盒本体中心：覆层与胶囊同轴，文字行零平移。
   *  真越出批：环层已挂 body（position:fixed），锚点直写**视口坐标**。 */
  const anchor = () => {
    const r = hdr.getBoundingClientRect();
    root.style.left = (r.left + r.width / 2) + 'px';
    root.style.top = (r.top + r.height / 2) + 'px';
  };

  /* ── hover 区（环开态收环判定）──：环开点（顶带胶囊处）∪ 胶囊本体 ∪ 非弧外
   *  环胶囊，各外扩 RING_ZONE_PAD_PX。竖环几何下中心→缘位有 ~edgeY 间隙，
   *  pointerleave 单源会立即误收（甚至开→收振荡）；环开期间 document
   *  pointermove 持续跟踪，离区才调度 140ms 宽限。（对称批「环开点→沉底胶囊
   *  顶走廊 ±90px」随沉降撤销退役 —— 环锚回顶带，胶囊不再离位，走廊无事可桥。） */
  let openPt = /** @type {{x: number, y: number}|null} */ (null);
  let lastPtr = /** @type {{x: number, y: number}|null} */ (null);
  const inHoverZone = (x, y) => {
    if (openPt) {
      if (Math.hypot(x - openPt.x, y - openPt.y) <= 56) return true;
    }
    const r = hdr.getBoundingClientRect();
    if (x >= r.left - RING_ZONE_PAD_PX && x <= r.right + RING_ZONE_PAD_PX
      && y >= r.top - RING_ZONE_PAD_PX && y <= r.bottom + RING_ZONE_PAD_PX) return true;
    for (const k of root.children) {
      if (k.classList.contains('is-far')) continue;
      const kr = /** @type {HTMLElement} */ (k).getBoundingClientRect();
      if (x >= kr.left - RING_ZONE_PAD_PX && x <= kr.right + RING_ZONE_PAD_PX
        && y >= kr.top - RING_ZONE_PAD_PX && y <= kr.bottom + RING_ZONE_PAD_PX) return true;
    }
    return false;
  };
  const evaluateLeave = () => {
    if (!ringState) return;
    if (!lastPtr || inHoverZone(lastPtr.x, lastPtr.y)) {
      if (leaveTimer) { clearTimeout(leaveTimer); leaveTimer = 0; }
    } else if (!leaveTimer) {
      leaveTimer = setTimeout(closeRing, RING_CLOSE_GRACE_MS);
    }
  };
  const onDocPointerMove = (e) => { lastPtr = { x: e.clientX, y: e.clientY }; evaluateLeave(); };
  /** 环开期间 document 捕获滚轮（capture + hover 区内消费）：触控板手感批 ——
   *  ④光标在 hover 区（openPt ∪ 标题头 ∪ 非弧外胶囊，与收环判定同一几何）外 =
   *  零响应（不 preventDefault 不换选，页面滚动照旧 —— 不再抢面板内容滚动）；
   *  ①区内设备分档累积（鼠标格低阈即切 / 触控板小增量累积过阈），切换后进
   *  RING_WHEEL_COOLDOWN_MS 冷却：期内事件只消费不累积 —— 轻扫一挥恰切一格、
   *  惯性末段不连跳，冷却后重新累积。 */
  const onDocWheel = (e) => {
    if (!ringState || isMobileLayer()) return;
    if (deck.open) { closeRing(); return; }   // deck 态不开环（叠卡有自己的滚轮语义；开环态下 deck 开启即让位）
    if (!inHoverZone(e.clientX, e.clientY)) return;   // ④区外零响应：不消费，页面滚动照旧
    e.preventDefault();
    e.stopPropagation();
    const now = performance.now();
    if (now < wheelLockUntil) return;         // 冷却期内：只消费不累积（惯性不叠加切换）
    /* deltaMode 归一 px（1=线 ×40 / 2=页 ×100 —— deck 滚轮重做同一口径）。 */
    const k = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 100 : 1;
    const dx = e.deltaX * k;
    const dy = e.deltaY * k;
    if (!dx && !dy) return;
    /* ②设备分档阈值：主导轴本事件 ≥ RING_MOUSE_DELTA = 鼠标离散格（一格意图）
     *  → 低阈即切；触控板小增量走累积档（48px ≈ 一挥轻扫）。 */
    const thr = Math.max(Math.abs(dx), Math.abs(dy)) >= RING_MOUSE_DELTA
      ? RING_WHEEL_STEP_MOUSE : RING_WHEEL_STEP_TRACKPAD;
    const n = ringState.ids.length;
    let dir = 0;
    if (Math.abs(dx) > Math.abs(dy)) {
      if (acc !== 0 && (dx > 0) !== (acc > 0)) acc = 0;   // 反向：清账重积
      acc += dx;
      if (acc >= thr) dir = 1; else if (acc <= -thr) dir = -1;
      if (dir) acc = 0;
    } else {
      if (accY !== 0 && (dy > 0) !== (accY > 0)) accY = 0;
      accY += dy;
      if (accY >= thr) dir = 1; else if (accY <= -thr) dir = -1;
      if (dir) accY = 0;
    }
    if (dir) {                                // 单次过阈恰切一格（余量清零 —— 事件不跨账）
      ringState.cursor = (ringState.cursor + dir + n) % n;
      ringState.lastDir = dir;                // 双卡方向感知档：非游标胶囊驻来向侧（layout 消费）
      wheelLockUntil = now + RING_WHEEL_COOLDOWN_MS;
      layout();
    }
  };
  const onDocPointerLeft = (e) => { if (ringState && !e.relatedTarget) closeRing(); }; // 离窗即收

  const openRing = (pt) => {
    if (ringState || isMobileLayer() || cardWindowMode) return;
    if (deck.open) return;                         // deck 态：叠卡有自己的环卡滚轮语义，不开环
    if (tabs.size < 2) return;                     // 单胶囊：平时态即全部，无环可展
    const all = [...tabs.keys()];
    ringState = { ids: all, cursor: Math.max(0, all.indexOf(activeTabId)), lastDir: 1 };
    acc = 0;
    accY = 0;
    wheelLockUntil = 0;                            // 新环新账：开环清冷却（上次环的余威不作废新环第一挥）
    openPt = pt ? { x: pt.x, y: pt.y } : null;
    lastPtr = null;
    build();
    hdr.classList.add('ring-open');
    // 真越出批：显隐随环层本位；可点性 = 稳态门（0.36s 展开过渡后缘位才接
    // pointer —— 展开中胶囊从指针位掠过会抢标题头按压/拖划，见 split.css 注）。
    root.classList.add('open');
    root.classList.remove('steady');
    if (steadyTimer) clearTimeout(steadyTimer);
    steadyTimer = setTimeout(() => { root.classList.add('steady'); steadyTimer = 0; }, RING_STEADY_MS);
    root.hidden = false;
    anchor();
    layout();
    document.addEventListener('pointermove', onDocPointerMove);
    document.addEventListener('wheel', onDocWheel, { capture: true, passive: false });
    document.addEventListener('pointerleave', onDocPointerLeft);
  };
  const closeRing = () => {
    if (!ringState) return;
    ringState = null;
    hdr.classList.remove('ring-open');
    root.classList.remove('open');
    root.classList.remove('steady');
    if (steadyTimer) { clearTimeout(steadyTimer); steadyTimer = 0; }
    root.hidden = true;
    root.textContent = '';
    acc = 0;
    accY = 0;
    wheelLockUntil = 0;
    openPt = null;
    lastPtr = null;
    document.removeEventListener('pointermove', onDocPointerMove);
    document.removeEventListener('wheel', onDocWheel, { capture: true });
    document.removeEventListener('pointerleave', onDocPointerLeft);
    if (leaveTimer) { clearTimeout(leaveTimer); leaveTimer = 0; }
  };

  hdr.addEventListener('pointerenter', (e) => {
    if (isMobileLayer() || e.pointerType === 'touch') return;
    if (leaveTimer) { clearTimeout(leaveTimer); leaveTimer = 0; }
    openRing({ x: e.clientX, y: e.clientY });
  });
  hdr.addEventListener('pointerleave', () => {
    if (!ringState) return;
    evaluateLeave();
  });
  // 作者令③：从胶囊发起的原生拖拽一律拦下（文字/链接拖拽；OS 文件拖入 =
  // drop 落区不受影响）。文字多选已被全头 user-select:none 封死（CSS）。
  hdr.addEventListener('dragstart', (e) => e.preventDefault());
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && ringState) closeRing(); });
  // 生命周期：换签不再无条件收环（作者报障 2026-10-25「hover 滚动连续性丧失」：
  // 「如果滚动，切换了其他卡片，那么就无法再次滚动，需要重新hover触发」）——
  // 旧腿 canvas-tab-switched → closeRing() 无条件收环，滚轮监听随环拆走，而
  // pointerenter 在指针未离头时不会再触发 ⇒ 换签后滚动链死断，必须移出再
  // hover。修法：换签时指针仍在 hover 区（openPt ∪ 标题头 ∪ 非弧外胶囊联合
  // 盒，与 evaluateLeave 同一判定）= 环保持开、游标跳到新激活页签（acc/accY
  // 清零、回锚重排），滚动零断链；指针已离区（他处换签）= 照旧收环。deck 态
  // / 单页签态不开环照旧（deck 叠卡有自己的滚轮语义，双环互搏防回归）。
  document.addEventListener('canvas-tab-switched', () => {
    if (!ringState) return;
    const pt = lastPtr || openPt;
    if (pt && !deck.open && tabs.size >= 2 && inHoverZone(pt.x, pt.y)) {
      ringState.ids = [...tabs.keys()];
      ringState.cursor = Math.max(0, ringState.ids.indexOf(activeTabId));
      acc = 0;
      accY = 0;
      wheelLockUntil = 0;      // 点击/手势换签 = 明确意图：清冷却，滚轮即时可再换选
      layout();                // layout() 内部先回锚（真越出批视口版）
      return;
    }
    closeRing();
  });
  document.addEventListener('canvas-tab-closed', () => {
    if (!ringState) return;
    /* teardownTab 在 tabs.delete 之前派发本事件（事件序先于删除）——同步重建
     * 会读到删除前状态：ids 含已关页签（死胶囊残留）、且 tabs.size 永远 ≥2
     * 「收环腿」永不触发（悬着的环卡死不收）。延一拍读删后状态。 */
    setTimeout(() => {
      if (!ringState) return;
      if (tabs.size < 2) { closeRing(); return; }
      ringState.cursor = Math.min(ringState.cursor, tabs.size - 1);
      build();
      anchor();
      layout();
      // 重建环 = 胶囊重新展开过渡：稳态门重走一遍（新胶囊从中心位掠出，同 race）
      root.classList.remove('steady');
      if (steadyTimer) clearTimeout(steadyTimer);
      steadyTimer = setTimeout(() => { root.classList.add('steady'); steadyTimer = 0; }, RING_STEADY_MS);
    }, 0);
  });
}

/** 手机长按引用通路（navController 卡头长按钮消费）：引用当前活动卡片进聊天，
 *  与桌面右键菜单「引用当前页」同一 appendRefToActiveView 通路（动态 import
 *  防 input.js 静态回边）。无文件可引（面板页签等）toast 与菜单同文案。 */
export function quoteActiveCardRef() {
  const entry = activeTabId ? tabs.get(activeTabId) : null;
  if (!entry) return false;
  const ref = refForTab(entry);
  if (!ref) { window.__showToast?.(t('canvas.noRef'), 'info'); return false; }
  import('./input.js').then(({ appendRefToActiveView }) => appendRefToActiveView(ref));
  return true;
}

/** Initialize canvas event listeners. Call on DOM ready. */
export function initCanvas() {
  // UI 批⑧：独立卡窗模式引导（?card-window=1）—— 必须先于 restoreTabs（main.js
  // boot 序）置位，否则卡窗会把主窗整组标签恢复进来。
  initCardWindowMode();

  const closeBtn = document.getElementById('canvas-close-btn');
  if (closeBtn) {
    closeBtn.addEventListener('click', closeCanvas);
  }

  // Event delegation on the tab bar (defensive fix for task L: per-tab listeners
  // could silently stop firing after long sessions — e.g. listener loss on tabs
  // rebuilt during restore/reorder). One delegated listener on the stable
  // #canvas-tab-bar container covers all tabs, present and future.
  const tabBar = document.getElementById('canvas-tab-bar');
  const tabBarEl = /** @type {HTMLElement|null} */ (tabBar);
  const tabBarFlags = /** @type {any} */ (tabBarEl);
  if (tabBarEl && !tabBarFlags._delegated) {
    tabBarFlags._delegated = true;

    // Click: close button → close tab; anywhere else on a tab → activate it.
    tabBarEl.addEventListener('click', (e) => {
      const tabEl = /** @type {Element} */ (e.target).closest('.canvas-tab');
      if (!tabEl || !tabBarEl.contains(tabEl)) return;
      const id = /** @type {HTMLElement} */ (tabEl).dataset.tabId;
      if (!id) return;
      if (/** @type {Element} */ (e.target).closest('.canvas-tab-close')) {
        e.stopPropagation();
        closeTab(id);
      } else {
        setActiveTab(id);
      }
    });

    // Double-click tab → promote to pinned (VS Code behavior).
    tabBarEl.addEventListener('dblclick', (e) => {
      const tabEl = /** @type {Element} */ (e.target).closest('.canvas-tab');
      if (!tabEl || !tabBarEl.contains(tabEl)) return;
      const id = /** @type {HTMLElement} */ (tabEl).dataset.tabId;
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
  const canvasHeaderEl = /** @type {HTMLElement|null} */ (document.querySelector('#canvas-panel .canvas-header'));
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
    }, canvasHeaderEl || tabBarEl);
  }

  // Listen for workspace-open-item events (dispatched on window by explorer.js).
  window.addEventListener('workspace-open-item', (e) => {
    const detail = /** @type {CustomEvent} */ (e).detail;
    if (detail) openWorkspaceItem(detail);
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
  // UI 批⑨：卡头胶囊手势（桌面腿：按压触感/竖滑换卡/横滑关卡/右键引用）。
  initCardHeadGestures();
  // 2026-10-22 作者令：胶囊环（hover 展开 · 横滑换选 · 点击切换 · 右键菜单扩容）。
  initCapsuleRing();

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
// ═════════════════════════════════════════════════════════════════════════
// UI 批⑧（作者令 2026-10-21）canvas 单卡独立窗口
//
// 主窗（权威）——卡片可以弹出为同源独立浏览器窗口（window.open
// `?card-window=1&path=…`），卡头 toggle 同位换形：未独立态「独立」⧉ / 已独立态
// 「合并」⇲。独立窗 = 最小查看器路由：同一 app 实例经既有 readFile →
// fileContent → workspace-open-item → renderFile 通路渲染真容（md/monaco/html/
// img/pdf 全家桶零新渲染代码）；卡窗只读镜像（编辑回主窗，避免双写者）。
// 同步 = BroadcastChannel('nebflow-canvas-cards')：update（主→卡，内容变了重读
// 文件）/ merge（主→卡，收窗）/ bye（卡→主，卡窗 ×/ESC/卸载 = 合并等价腿）/
// hello·welcome（主窗刷新后再收养）/ main-here 心跳（卡窗 8s 静默自守卫关闭）；
// 主窗另持 WindowProxy 轮询 win.closed 作崩溃兜底 —— 卡窗任何方式消失都收敛到
// 同一条 mergeCardWindow 路径（关窗等价 + 幂等：重入按 Map 判重直返）。
// 主窗独立中标记：页签 is-independent（toggle 同位换形）+ 卡面 data-independent
// 「独立中」轻态浮标（CSS ::after，占位不消失）；环卡同标记。
// 动画双向：独立 = 主窗卡面 pop 脉冲 + 卡窗根 nf-card-win-in 放大淡入；
// 合并 = 卡窗 nf-card-win-out 收缩淡出 + 主窗卡面 nf-card-restore 回弹展开；
// prefers-reduced-motion 直切（类不加、窗即关）。
// 手机端（mobile-layer / 窄视口）：window.open 不带 features = 新标签页姿态、
// 直切无动画（报告注记：同窗全屏路由会卸载主窗状态，新标签与桌面共用同一协议）。
// ═════════════════════════════════════════════════════════════════════════

/** 本实例 = 独立卡窗（URL ?card-window=1）；主窗恒 false。 */
let cardWindowMode = false;
/** tabId → { win: WindowProxy|null, winId }。win=null = 主窗刷新后由 hello 再收养
 *  （WindowProxy 已丢，关窗检测交给卡窗 bye/心跳看门狗）。 */
const independentWins = new Map();
/** popout 在途判重（window.open 同步返回，防双击双窗）。 */
const cardPopInFlight = new Set();
/** tabId → win.closed 轮询 interval。 */
const cardWinWatch = new Map();
/** 跨窗同步通道（主/卡共用；懒建）。 */
let cardBC = null;
/** 卡窗侧最近一次收到主窗心跳的时刻（8s 静默自守卫）。 */
let cardLastMainBeat = 0;

/** @param {any} m */
function cardBcPost(m) {
  try { if (cardBC) cardBC.postMessage(m); } catch { /* 通道已废：静默 */ }
}
function ensureCardChannel() {
  if (cardBC || typeof BroadcastChannel === 'undefined') return;
  cardBC = new BroadcastChannel('nebflow-canvas-cards');
  cardBC.onmessage = (e) => {
    const m = e.data || {};
      if (cardWindowMode) {
        /* 卡窗侧 */
        if (m.kind === 'main-here') cardLastMainBeat = Date.now();
        else if (m.kind === 'merge' && m.tabId) requestMergeSelf();   // 出窗动画照放（作者令 C 双向）
        else if (m.kind === 'update' && m.tabId) cardWindowReread();
        else if (m.kind === 'close-self' && m.tabId) window.close();
        return;
      }
    /* 主窗侧 */
    if (m.kind === 'bye' && m.tabId) mergeCardWindow(m.tabId, { fromCard: true });
    else if (m.kind === 'hello' && m.tabId) {
      if (tabs.has(m.tabId)) {
        if (!independentWins.has(m.tabId)) {
          // 主窗刷新后由卡窗 hello 再收养（WindowProxy 不可得，看门狗交卡窗侧）
          independentWins.set(m.tabId, { win: null, winId: String(m.winId || '') });
          markIndependentUI(m.tabId, true, { animate: false });
        }
        cardBcPost({ kind: 'welcome', tabId: m.tabId });
      } else {
        cardBcPost({ kind: 'close-self', tabId: m.tabId });   // 主窗已无此卡 → 卡窗自关
      }
    }
  };
  if (!cardWindowMode) {
    // 主窗心跳：卡窗 8s 静默自守卫的喂食腿（首弹卡时起搏，见 popoutCardWindow）。
    if (!cardLastMainBeat) cardLastMainBeat = Date.now();
    if (!/** @type {any} */ (window).__cardBeatTimer) {
      /** @type {any} */ (window).__cardBeatTimer = setInterval(() => cardBcPost({ kind: 'main-here' }), 3000);
    }
  }
}

/** 卡头 toggle 标记（页签 + 卡面 + 环卡三面同源投射）。
 *  @param {string} id @param {boolean} on @param {{animate?:boolean}} [o] */
function markIndependentUI(id, on, o = {}) {
  const entry = tabs.get(id);
  if (entry) {
    entry.tabEl.classList.toggle('is-independent', on);
    const pane = entry.paneEl;
    if (pane) {
      if (on) pane.setAttribute('data-independent', '1');
      else pane.removeAttribute('data-independent');
      if (o.animate && !cardWindowRm()) {
        const cls = on ? 'card-pop-anim' : 'card-merge-anim';
        pane.classList.remove('card-pop-anim', 'card-merge-anim');
        // 强制 reflow 重启动画
        void pane.offsetWidth;
        pane.classList.add(cls);
        pane.addEventListener('animationend', () => pane.classList.remove(cls), { once: true });
      }
    }
  }
  // 环卡（若在挂）：属性驱动 CSS ::after「独立中」chip，零 DOM 插拔
  const deckCard = deckLayer && deckLayer.querySelector('.deck-card[data-tab="' + cssEscapeV(id) + '"]');
  if (deckCard) {
    if (on) deckCard.setAttribute('data-independent', '1');
    else deckCard.removeAttribute('data-independent');
    const deckBtn = deckCard.querySelector('.deck-card-pop');
    if (deckBtn) {
      deckBtn.classList.toggle('is-independent', on);
      const t = on ? '合并回主窗' : '独立窗口打开';
      deckBtn.title = t;
      deckBtn.setAttribute('aria-label', t);
    }
  }
  syncCardHeaderPop();                 // 活动卡头钮形跟随
  // 手机卡片头钮形跟随（navController 消费；跨模块薄通知，零轮询）
  document.dispatchEvent(new CustomEvent('canvas-independent-changed', { detail: { id, on } }));
}
function cardWindowRm() {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** 弹出单卡独立窗（幂等：已在独立 / 在途 / 无 absPath / 弹窗被拦 ⇒ 零状态变化）。
 *  内容物收口批（2026-10-22 作者令）：独立窗只承载内容物 —— 文档（md/canvas
 *  文档）、文件、图片等工件视图；功能性面板（任务列表/图谱/设置/会话列表等
 *  全功能 UI）一律不可独立窗口化。absPath 是内容物的判定单点（面板/url/flow-run
 *  页签皆无 absPath），本函数是全部入口（卡头胶囊钮/环卡钮/右键菜单/手机卡头）
 *  的最终弹出腿：门在此收口，无视入口形态，拒绝腿带禁用态提示 toast。
 *  @param {string} id */
function popoutCardWindow(id) {
  const entry = tabs.get(id);
  if (!entry || !entry.absPath) {
    window.__showToast?.(t('canvas.cardwinContentOnly'), 'info');
    return false;
  }
  if (independentWins.has(id) || cardPopInFlight.has(id)) return false;
  cardPopInFlight.add(id);
  // 卡窗白屏修复批（2026-10-22）：入口必须是 `/` —— 网关静态路由只把 `/`
  // 派给 index（WebSocketRoutes `GET -> Root`），`/index.html` 落根文件白名单
  // （sw.js/favicon 族）之外 ⇒ 404 空 Content-Length 体，独立窗整页空白
  // （作者实测 link.mystand.space 白窗根因；旧批 spec 静态服 `/`→index 掩盖了
  // 网关路由差异）。同源开窗自带 cookie 会话，无凭据注入面；死会话形态由
  // WS 层自愈链收口（ws.js onclose → remint/登录引导）。
  const url = '/?card-window=1&path=' + encodeURIComponent(entry.absPath) +
    '&title=' + encodeURIComponent(entry.title || '');
  const winId = 'nf-card-' + id.replace(/[^a-zA-Z0-9_-]/g, '_');
  const mobile = isMobileViewport() || document.body.classList.contains('mobile-layer');
  const win = mobile
    ? window.open(url, winId)                                    // 手机：新标签页姿态
    : window.open(url, winId, 'popup=yes,width=780,height=680');
  cardPopInFlight.delete(id);
  if (!win) return false;                                        // 弹窗被拦：不动状态
  ensureCardChannel();
  independentWins.set(id, { win, winId });
  markIndependentUI(id, true, { animate: !mobile });
  // 崩溃兜底：卡窗以任何方式消失（× 之外的 Cmd-W / 崩溃）都收敛到合并路径
  const iv = setInterval(() => {
    const rec = independentWins.get(id);
    if (!rec) { clearInterval(iv); cardWinWatch.delete(id); return; }
    if (rec.win && rec.win.closed) {
      clearInterval(iv); cardWinWatch.delete(id);
      mergeCardWindow(id, { fromCard: true, instant: true });
    }
  }, 600);
  cardWinWatch.set(id, iv);
  return true;
}

/** 合并（收回主窗原位）。幂等门 = Map 判重：未独立/已合并重入直返 false。
 *  @param {string} id @param {{fromCard?:boolean, instant?:boolean}} [opts] */
function mergeCardWindow(id, opts = {}) {
  const rec = independentWins.get(id);
  if (!rec) return false;
  independentWins.delete(id);
  const iv = cardWinWatch.get(id);
  if (iv) { clearInterval(iv); cardWinWatch.delete(id); }
  if (!opts.fromCard && rec.win && !rec.win.closed) cardBcPost({ kind: 'merge', tabId: id });
  markIndependentUI(id, false, { animate: !opts.instant });
  return true;
}
/** 卡头 toggle 总入口：未独立 → 弹出；已独立 → 合并。 */
function toggleCardWindow(id) {
  if (independentWins.has(id)) mergeCardWindow(id);
  else popoutCardWindow(id);
}
/* UI 批⑧ 手机卡头腿（navController 消费）：新标签页姿态直切，协议同桌面。 */
/** @param {string} id */
export function mobileCardToggle(id) { toggleCardWindow(id); }
/** @param {string} id */
export function isCardIndependent(id) { return independentWins.has(id); }
/** 该卡可独立（文件卡才可 —— 独立窗经 absPath readFile 重读真容）。 */
export function cardCanPop(id) { const e = tabs.get(id); return !!(e && e.absPath); }
/** 主窗内容变更 → 通知卡窗重读（absPath 卡走自身 readFile，真容同源）。 */
function notifyCardUpdate(id) {
  if (independentWins.has(id)) cardBcPost({ kind: 'update', tabId: id });
}

/* ── 卡窗侧 ─────────────────────────────────────────────────────────── */
// ── 卡窗引导态面（卡窗白屏修复批 2026-10-22）──
// 开窗即见「正在打开…」占位（面板真容挂载前的不空白承诺）；首张页签 pane
// 挂出子节点（renderFile 真容 / F3 不可读骨架，二者皆非白）即撤。超时或
// WS 握手被拒（nebflow-ws-rejected 事件，ws.js onclose 同步派发）⇒ 错误态
// 卡片：WS 从未连上 ⇒ 提示登录过期 + [重试]（reload 重走 ws.js remint /
// 登录引导既有链）；其余 ⇒ 加载失败 + [重试]。未认证/加载失败态永不白屏。
const CARDWIN_BOOT_POLL_MS = 400;
const CARDWIN_BOOT_TIMEOUT_MS = 12000;
const CARDWIN_REJECT_GRACE_MS = 2000;
/** @type {number|null} 引导面轮询句柄（撤面/换态后清零）。 */
let cardWinBootPoll = null;

/** 撤除引导面（成功或关窗后；幂等）。 */
function cardWindowBootFaceDismiss() {
  if (cardWinBootPoll) { clearInterval(cardWinBootPoll); cardWinBootPoll = null; }
  document.getElementById('nf-cardwin-boot')?.remove();
}

/** 卡窗引导态面：loading 占位 → 真容即撤 / 失败态卡片（内联样式零依赖面，
 *  同 sessionGate 横幅纪律）。仅 cardWindowMode 调用（initCardWindowMode）。
 *  返回面句柄（内容物收口批）：无 path 深链的即刻失败腿用它直换失败态，
 *  不等 12s 轮询超时。
 *  @param {string} fileName */
function cardWindowBootFace(fileName) {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;
  const el = document.createElement('div');
  el.id = 'nf-cardwin-boot';
  el.setAttribute('data-state', 'loading');
  el.style.cssText =
    'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);z-index:5;' +
    'display:flex;flex-direction:column;align-items:center;gap:10px;padding:22px 30px;' +
    'border:1px solid var(--glass-border,#e0e1e4);border-radius:14px;' +
    'background:var(--glass-bg,#fff);color:var(--color-text,#1b1e26);' +
    "font:13px/1.6 -apple-system,'Segoe UI',sans-serif;text-align:center;max-width:78vw;";
  const dot = document.createElement('div');
  dot.className = 'nf-cw-dot';
  dot.style.cssText =
    'width:22px;height:22px;border-radius:50%;flex:none;' +
    'border:2.5px solid var(--color-frame-border,#e0e1e4);border-top-color:rgb(var(--sapphire,80,120,255));' +
    'animation:nf-cardwin-spin 0.9s linear infinite;';
  const title = document.createElement('div');
  title.className = 'nf-cw-title';
  title.style.cssText = 'font-weight:600;font-size:13px;max-width:52vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
  title.textContent = fileName || t('canvas.loadingFile');
  const hint = document.createElement('div');
  hint.className = 'nf-cw-hint';
  hint.style.cssText = 'font-size:12px;color:var(--color-text-muted,#8b8e96);display:none;';
  const retry = document.createElement('button');
  retry.className = 'nf-cw-retry';
  retry.textContent = t('canvas.retry');
  retry.style.cssText =
    'display:none;margin-top:2px;padding:5px 16px;border-radius:8px;cursor:pointer;' +
    'border:1px solid var(--glass-control-border,#d8d9dd);background:var(--glass-control-bg,transparent);' +
    'color:inherit;font-size:12px;';
  retry.addEventListener('click', () => { try { location.reload(); } catch { /* 无历环境 */ } });
  el.append(dot, title, hint, retry);
  const style = document.createElement('style');
  style.textContent = '@keyframes nf-cardwin-spin{to{transform:rotate(360deg)}}';
  el.appendChild(style);
  panel.appendChild(el);

  let wsRejectedAt = 0;
  const onRejected = () => { wsRejectedAt = wsRejectedAt || Date.now(); };
  document.addEventListener('nebflow-ws-rejected', onRejected, { once: true });
  window.addEventListener('pagehide', cardWindowBootFaceDismiss, { once: true });

  const started = Date.now();
  cardWinBootPoll = setInterval(() => {
    // 真容已挂（renderFile 内容或 F3 骨架任一子节点）⇒ 引导使命完成。
    const pane = panel.querySelector('#canvas-content .canvas-tab-pane');
    if (pane && pane.childElementCount > 0) { cardWindowBootFaceDismiss(); return; }
    const waited = Date.now() - started;
    // WS 握手被拒：宽限 2s（让 ws.js remint/登录导航先走 —— 若页面将被
    // 引去登录腿，这里不必闪错误卡），仍在 ⇒ 失败态（登录形态文案）。
    if (wsRejectedAt && Date.now() - wsRejectedAt >= CARDWIN_REJECT_GRACE_MS) {
      cardWindowBootFail(el, dot, hint, retry, true);
      return;
    }
    if (waited < CARDWIN_BOOT_TIMEOUT_MS) return;
    import('./state.js').then(({ default: state }) => {
      cardWindowBootFail(el, dot, hint, retry, !state.connected);
    }).catch(() => cardWindowBootFail(el, dot, hint, retry, false));
  }, CARDWIN_BOOT_POLL_MS);
  // 面句柄（内容物收口批）：无 path 深链的即刻失败腿消费。
  return { el, dot, hint, retry };
}

/** 引导面换失败态：撤动画、换文案（登录形态 / 一般失败）、亮 [重试]。 */
function cardWindowBootFail(el, dot, hint, retry, authLike) {
  if (el.getAttribute('data-state') === 'failed') return;
  el.setAttribute('data-state', 'failed');
  dot.style.animation = 'none';
  dot.style.borderTopColor = 'var(--status-error-icon,#d64545)';
  hint.style.display = 'block';
  hint.textContent = authLike ? t('canvas.cardwinAuthHint') : t('canvas.cardwinFailHint');
  retry.style.display = 'inline-flex';
}

/** 卡窗「合并」自请求：out 动画 → bye → close（主窗 closed 轮询/bye 双腿等价）。 */
function requestMergeSelf(o = {}) {
  if (!cardWindowMode) return;
  const rm = cardWindowRm();
  if (!o.silent && !rm) {
    const panel = document.getElementById('canvas-panel');
    if (panel) {
      panel.classList.add('card-win-merging');
      setTimeout(finish, 180);
      return;
    }
  }
  finish();
  function finish() {
    cardBcPost({ kind: 'bye', tabId: currentCardTabId() });
    try { window.close(); } catch { /* 弹窗灰被脚本关：留窗（主窗心跳看门狗会收） */ }
  }
}
/** 卡窗当前卡 tabId（= 本窗唯一页签；file:<path>）。 */
function currentCardTabId() {
  return activeTabId || ([...tabs.keys()][0] ?? '');
}
/** 卡窗收到 update：走专用刷新通道重读真容（readFile → fileContent →
 *  openWorkspaceItem 既有 tab 内重渲腿）。update = 主窗权威变更信号，清
 *  冷却门直通（卡窗只读镜像，无脏态；防抖 300ms 保留）。 */
function cardWindowReread() {
  const entry = tabs.get(currentCardTabId());
  if (!entry || !entry.absPath) return;
  entry._lastRefreshAt = 0;
  scheduleFileRefresh(entry);
}
/** 卡窗模式引导（initCanvas 最早步调用）：壳隐藏交给 CSS（body.card-window），
 *  阻断标签恢复/持久化（卡窗零存档副作用），追加「合并」钮，开单卡走 readFile。 */
function initCardWindowMode() {
  let qs;
  try { qs = new URLSearchParams(location.search); } catch { return false; }
  if (qs.get('card-window') !== '1') return false;
  cardWindowMode = true;
  document.body.classList.add('card-window');
  // 引导面句柄存全局（无 path 深链的即刻失败腿消费；内容物收口批）
  /** @type {any} */ (window).__nfCardwinBootFace =
    cardWindowBootFace(qs.get('title') || (qs.get('path') || '').split('/').pop() || '');
  ensureCardChannel();
  cardLastMainBeat = Date.now();
  // 主窗心跳看门狗：20s 静默（主窗已关/崩/挂起）→ 自闭，绝无僵尸镜像。
  // 阈值取心跳 3s 的 6 倍余 —— 后台窗计时器节流下也不误杀。
  setInterval(() => {
    if (Date.now() - cardLastMainBeat > 20000) requestMergeSelf({ silent: true });
  }, 2000);
  window.addEventListener('pagehide', () => cardBcPost({ kind: 'bye', tabId: currentCardTabId() }));
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') requestMergeSelf(); });
  // 卡头「合并」钮（.canvas-header 内，panel 关闭钮在卡窗态 CSS 隐藏）
  const hdr = document.querySelector('#canvas-panel .canvas-header');
  if (hdr && !hdr.querySelector('.canvas-merge-btn')) {
    const b = document.createElement('button');
    b.className = 'canvas-merge-btn';
    b.id = 'canvas-merge-btn';
    b.title = '合并回主窗';
    b.setAttribute('aria-label', '合并回主窗');
    b.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><path d="M14 10 21 3"/><path d="M3 21l7-7"/></svg>';
    b.addEventListener('click', () => requestMergeSelf());
    hdr.appendChild(b);
  }
  // 单卡开卡：空 content + absPath ⇒ 既有 readFile WS 取读腿（fileContent →
  // workspace-open-item 重派 → renderFile 真容）。freshest 内容，零载荷传递。
  // setTimeout 推后一拍：initCanvas 的 workspace-open-item 监听在本函数之后
  // 同步注册，同步派发会先于监听蒸发（首轮 CW1-3 红：卡窗 tabs=[] 实证）。
  // 内容物收口批：无 path 的卡窗深链 = 非内容物目标（功能性面板不可独立窗口
  // 化）——即刻失败卡收口（壳保持隐藏，绝不落全功能 UI），不等 12s 超时轮询。
  const path = qs.get('path') || '';
  if (path) {
    setTimeout(() => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + path, itemType: '', title: qs.get('title') || '', content: '', absPath: path, pinned: true },
      }));
    }, 0);
  } else {
    const face = /** @type {any} */ (/** @type {any} */ (window).__nfCardwinBootFace || null);
    if (face) {
      // 失败即停轮（终态卡在挂，后续轮询腿/超时腿全部失效）
      if (cardWinBootPoll) { clearInterval(cardWinBootPoll); cardWinBootPoll = null; }
      cardWindowBootFail(face.el, face.dot, face.hint, face.retry, false);
      face.hint.textContent = t('canvas.cardwinNoPath');
    }
  }
  return true;
}

// ── #910 墓碑语义（作者裁定 2026-10-26 飞书逐字「找少的那个认吧」）────────
// 关闭意图优先：任一窗口/设备显式关闭的标签，权威存档保持关闭；只有显式
// 重开才恢复。前端职责=把「本会话关闭/重开意图账」随每次 PUT 上交，合并
// 裁决在服务端（CanvasTabs.merge，时间戳幂等，账目可重放、不清不剪——超
// 上限只淘汰最旧，保 PUT 永不因账目超限 400）。
const sessionTombstones = new Map(); // id -> { id, closedAt, dev }
const sessionReopens = new Map();    // id -> { id, reopenedAt }
const SESSION_LEDGER_CAP = 2000;     // 与服务端 MaxTombstoneInput 一致

// 所有显式关闭路径的记账点（× 按钮 / deck 侧滑 / 关闭其他 / 预览替换——
// 各站点在 tabs.delete 后、persistTabs 前调用）。拖拽排序与文件改名不是
// 关闭（tabs 重建/换 id），不记账。
function recordTombstone(id) {
  if (!id) return;
  sessionTombstones.set(id, { id, closedAt: Date.now(), dev: navigator.platform || 'unknown' });
  if (sessionTombstones.size > SESSION_LEDGER_CAP) {
    sessionTombstones.delete(sessionTombstones.keys().next().value); // 最旧
  }
}

function persistTabs() {
  // UI 批⑧：独立卡窗零存档副作用 —— 卡窗自己的单卡标签绝不能写穿服务端权威
  // 存档/本地缓存（否则会把主窗整组标签覆盖成一张卡）。
  if (cardWindowMode) return;
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
    // #910: 附带本会话关闭/重开意图账（服务端墓碑合并；账幂等可重放）。
    if (sessionTombstones.size > 0) payload.tombstones = [...sessionTombstones.values()];
    if (sessionReopens.size > 0) payload.reopened = [...sessionReopens.values()];
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
    // UI 批⑧：独立卡窗不恢复主窗标签组（只承载 ?path= 单卡）；返回 true 让
    // main.js 跳过「恢复失败 → 开 Teams 面板」兜底。
    if (cardWindowMode) { resolve(true); return; }
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
