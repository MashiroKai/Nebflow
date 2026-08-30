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

const MIN_CANVAS_WIDTH = 320;
const MAX_CANVAS_WIDTH = 1200;
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
let _canvasCtxMenu = null;
let _ctxMenuBound = false;
function hideCanvasRefMenu() {
  if (_canvasCtxMenu) { _canvasCtxMenu.remove(); _canvasCtxMenu = null; }
}
function showCanvasRefMenu(x, y, entry) {
  hideCanvasRefMenu();
  const ref = refForTab(entry);
  if (!ref) {
    window.__showToast?.(t('canvas.noRef'), 'info');
    return;
  }
  const menu = document.createElement('div');
  menu.className = 'canvas-tab-context-menu';
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';
  const btn = document.createElement('button');
  btn.textContent = t('canvas.referenceCurrent');
  btn.addEventListener('click', () => {
    hideCanvasRefMenu();
    // Dynamic import to avoid a static edge back into input.js (which would
    // close the modal→taskList→canvas→input cycle — historically via
    // taskArchive.js, removed in the 2026-08-30 task redesign).
    import('./input.js').then(({ appendRefToActiveView }) => appendRefToActiveView(ref));
  });
  menu.appendChild(btn);
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
    e.dataTransfer.effectAllowed = 'move';
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

/** Compute the target open width so Canvas matches Chat panel width.
 *  Both panels share the remaining space 50/50 after fixed elements
 *  (sidebar, activity bar) and margins are accounted for. */
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
  syncCanvasPanelButtons();
}

/** Close the canvas panel (hide visually — tabs are preserved).
 *
 *  Mirrors the sidebar pattern: remove body class, CSS animates back to 0.
 *  Tabs remain in memory and localStorage so they can be restored on reopen. */
export function closeCanvas() {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;

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

  const entry = { id, title, type, paneEl: pane, tabEl: tab, closable, pinned, absPath: opts.absPath || null };
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

/** Close a single tab and switch to an adjacent one.
 *  If the closed tab was the last one, closes the entire panel.
 *  Dispatches 'canvas-tab-closed' so external code (e.g. flowCanvas) can clean up.
 *  @param {string} id — Tab identifier. */
export function closeTab(id) {
  const entry = tabs.get(id);
  if (!entry) return;

  // Dispose Monaco editor if present
  disposeTabEditor(entry);

  document.dispatchEvent(new CustomEvent('canvas-tab-closed', { detail: { id } }));

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
    disposeTabEditor(entry);
    entry.paneEl.remove();
    entry.tabEl.remove();
    tabs.delete(id);
    for (const [k, v] of previewTabs) { if (v === id) previewTabs.delete(k); }
    // F2 (2026-08-30 作者裁定 + 方案 §8.4 F2): 关闭最后一个标签不再清空共享存档。
    // localStorage 是全浏览器共享的，多窗口下本窗口关最后一个标签即清空全局存档，
    // 其他仍开着的窗口重载后全部标签丢失（多窗口竞态）。语义改为「本窗口会话级清空」——
    // 共享存档保留，重启后从服务端/本地存档恢复。persistTabs 在 tabs.size===0 时早退，
    // 也不会向服务端写清空。
    persistTabs();

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
  hint.textContent = `${t('canvas.unreadableHint')}${errorMsg ? ' ' + errorMsg : ''}`;
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
  const { itemType, title, content, absPath, size, pinned, anchor } = item;
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
    const entry = tabs.get(id);
    entry._refreshing = false;  // response arrived (or user-initiated open)
    // A response arrived = the file was freshly checked. Update unconditionally
    // — binary viewers (image/pdf/...) receive no content and never re-render,
    // so without this their _lastRefreshAt stays stale, the cooldown never
    // engages, and refresh→setActiveTab loops forever (each cycle stealing
    // activation back to the binary tab — "can't switch away from an image").
    entry._lastRefreshAt = Date.now();
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
        await renderFile(entry.paneEl, { itemType, content, absPath, fileName: title, size, path: item.path, rootPath: item.rootPath });
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
    // and is consumed at render time below.
    const pgStash = anchor && anchor.pageStart;
    if (Number.isFinite(pgStash)) pendingRefAnchors.set(absPath, { pageStart: pgStash });
    Promise.all([
      import('./ws.js'),
      import('./state.js')
    ]).then(([{ sendWs }, { default: state }]) => {
      const sid = state?.activeSessionId;
      if (sendWs && sid) {
        window.dispatchEvent(new CustomEvent('explorer-preload-pinned', {
          detail: { path: absPath, pinned: !!pinned }
        }));
        sendWs({ type: 'pop.readFile', path: absPath, sessionId: sid });
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

  const entry = openTab(id, title || id, { type: itemType, pinned: !!pinned, absPath: absPath });
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
  });

  const { renderFile } = await import('./fileViewers.js');
  // #303 C3: consume a stashed reference-jump anchor (empty-content open) or
  // one carried directly on the item, and hand it to the viewer.
  const stashAnchor = absPath ? pendingRefAnchors.get(absPath) : undefined;
  if (absPath) pendingRefAnchors.delete(absPath);
  const renderAnchor = anchor || stashAnchor;
  await renderFile(pane, { itemType, content, absPath, fileName: title, size, path: item.path, rootPath: item.rootPath, anchor: renderAnchor });
  entry._lastRefreshAt = Date.now();  // just rendered — don't immediately re-fetch
}

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

  // Responsive: maintain equal panel widths on resize.
  window.addEventListener('resize', () => {
    if (!isCanvasOpen()) return;
    // Skip if user has manually pinned a specific width via col-resizer
    const panel = document.getElementById('canvas-panel');
    if (panel && panel.style.flex) return; // inline flex = user resized
    const target = computeOpenWidth();
    document.documentElement.style.setProperty('--canvas-width', target + 'px');
  });
}

// ── Tab persistence ─────────────────────────────────────────

/** Save open tabs (metadata only) to localStorage so they survive refresh.
 *  Stores: id, title, type, absPath (nullable), pinned, closable.
 *  Tabs without absPath (Teams/Flows panels) are restored synchronously
 *  and re-render from live state; file tabs re-fetch content on restore. */
function persistTabs() {
  // Don't overwrite saved tabs with an empty list — transient states can
  // empty the in-memory Map, but saved data should survive for restoration.
  if (tabs.size === 0) {
    console.log('[persistTabs] tabs empty — skipping save to preserve existing data');
    return;
  }
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
function scheduleServerPersist(payload) {
  if (serverPersistTimer) clearTimeout(serverPersistTimer);
  serverPersistTimer = setTimeout(() => {
    serverPersistTimer = null;
    fetch(TABS_API, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
      .then((r) => { if (!r.ok) console.warn('[canvas-tabs] server persist failed:', r.status); })
      .catch((e) => console.warn('[canvas-tabs] server persist error:', e));
  }, 500);
}

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
  const panelTabs = data.tabs.filter(t => !t.absPath && t.type !== 'flow-run');
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
    fetch(TABS_API)
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
