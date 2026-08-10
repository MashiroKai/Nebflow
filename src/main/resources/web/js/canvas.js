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

const MIN_CANVAS_WIDTH = 320;
const MAX_CANVAS_WIDTH = 1200;
const LS_KEY = 'nebflow_col_widths';
const LS_TABS_KEY = 'nebflow_canvas_tabs';

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

// ── Drag-to-reorder ────────────────────────────────────────
let draggedTabId = null;

function attachDragHandlers(tabEl, id) {
  tabEl.addEventListener('dragstart', (e) => {
    draggedTabId = id;
    tabEl.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', id);
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

/** Read a previously persisted canvas width (px) from storage. */
function getPersistedCanvasWidth() {
  try {
    const data = JSON.parse(localStorage.getItem(LS_KEY) || '{}');
    return typeof data.canvas === 'number' ? data.canvas : null;
  } catch (_) { return null; }
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

// ── Tab management ─────────────────────────────────────────

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
        if (oldEntry.paneEl._editorHandle) {
          oldEntry.paneEl._editorHandle.dispose();
          oldEntry.paneEl._editorHandle = null;
        }
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

  // Click tab (not close button) → switch to it.
  tab.addEventListener('click', (e) => {
    if (e.target.closest('.canvas-tab-close')) return;
    setActiveTab(id);
  });

  // Double-click tab → promote to pinned (VS Code behavior)
  tab.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    pinTab(id);
  });

  // Click close button → close tab.
  const closeBtn = tab.querySelector('.canvas-tab-close');
  if (closeBtn) {
    closeBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      closeTab(id);
    });
  }

  // Drag-to-reorder
  attachDragHandlers(tab, id);

  tabBar.appendChild(tab);
  // Auto-scroll the tab bar to show the newly added tab.
  tabBar.scrollLeft = tabBar.scrollWidth;

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
  if (entry.paneEl._editorHandle) {
    entry.paneEl._editorHandle.dispose();
    entry.paneEl._editorHandle = null;
  }

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
    if (entry.paneEl._editorHandle) {
      entry.paneEl._editorHandle.dispose();
      entry.paneEl._editorHandle = null;
    }
    entry.paneEl.remove();
    entry.tabEl.remove();
    tabs.delete(id);
    for (const [k, v] of previewTabs) { if (v === id) previewTabs.delete(k); }
    if (tabs.size === 0) {
      // User closed the last tab — clear saved data so it doesn't restore
      localStorage.removeItem(LS_TABS_KEY);
      console.log('[closeTab] last tab closed — cleared localStorage');
    }
    persistTabs();

    if (activeTabId === id) {
      activeTabId = null;
      if (nextId) {
        setActiveTab(nextId);
      } else {
        closeCanvas();
      }
    }
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
        detail: { path: entry.absPath, pinned: entry.pinned !== false }
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
  document.dispatchEvent(new CustomEvent('canvas-tab-switched', { detail: { id } }));
  persistTabs();

  // Live-refresh file tabs on activation (debounced, dirty-safe).
  const entry = tabs.get(id);
  if (entry) scheduleFileRefresh(entry);
}

/** Get the content pane element for a tab.
 *  External code (e.g. flowCanvas) uses this to render into their tab.
 *  @param {string} id — Tab identifier.
 *  @returns {HTMLElement|null} */
export function getTabPane(id) {
  return tabs.get(id)?.paneEl || null;
}

/** Get the currently active tab's id.
 *  @returns {string|null} */
export function getActiveTabId() {
  return activeTabId;
}

/** Check if a tab with the given id exists.
 *  @param {string} id — Tab identifier.
 *  @returns {boolean} */
export function hasTab(id) {
  return tabs.has(id);
}

// ── Content injection ──────────────────────────────────────

/** Set content for a specific tab (or the active tab if no id given).
 *  Replaces the pane's innerHTML entirely.
 *  @param {string} html   — HTML string.
 *  @param {string} tabId  — Target tab id (defaults to active tab). */
export function setCanvasContent(html, tabId = null) {
  const targetId = tabId || activeTabId;
  const pane = targetId ? getTabPane(targetId) : document.getElementById('canvas-content');
  if (pane) pane.innerHTML = html;
}

// ── File viewers ───────────────────────────────────────────

/** Escape HTML special characters for safe text display. */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str ?? '';
  return div.innerHTML;
}

/** Open a file or workspace item in a Canvas tab.
 *  Uses the file viewer registry for rendering all file types.
 *  Text files open in Monaco editor (editable). Binary files are read-only.
 *  @param {object} item — { id, itemType, title, content, absPath, size }
 */
export async function openWorkspaceItem(item) {
  const { id, itemType, title, content, absPath, size, pinned } = item;
  if (!id) return;

  // If tab already exists: switch to it — and when this dispatch carries new
  // content (e.g. a refresh readFile response), re-render the existing pane.
  // Never re-render over unsaved edits or a mounted source-mode editor.
  if (tabs.has(id)) {
    if (pinned) pinTab(id);
    const entry = tabs.get(id);
    entry._refreshing = false;  // response arrived (or user-initiated open)
    if (content && !isTabDirty(entry) && entry.paneEl.dataset.sourceMode !== '1') {
      if (entry.paneEl._editorHandle) {
        entry.paneEl._editorHandle.dispose();
        entry.paneEl._editorHandle = null;
      }
      const { renderFile } = await import('./fileViewers.js');
      await renderFile(entry.paneEl, { itemType, content, absPath, fileName: title, size, path: item.path, rootPath: item.rootPath });
      entry._lastRefreshAt = Date.now();
    }
    setActiveTab(id);
    return;
  }

  // Re-open from chat Pop card click: content and itemType are empty but
  // absPath is provided. Fetch real content via readFile WS instead of
  // showing a blank tab. The fileContent handler will dispatch a new
  // workspace-open-item with full content and correct itemType.
  if (content === '' && itemType === '' && absPath) {
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
      if (oldEntry.paneEl._editorHandle) {
        oldEntry.paneEl._editorHandle.dispose();
        oldEntry.paneEl._editorHandle = null;
      }
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
  await renderFile(pane, { itemType, content, absPath, fileName: title, size, path: item.path, rootPath: item.rootPath });
  entry._lastRefreshAt = Date.now();  // just rendered — don't immediately re-fetch
}

// ── Initialization ─────────────────────────────────────────

/** Initialize canvas event listeners. Call on DOM ready. */
export function initCanvas() {
  const closeBtn = document.getElementById('canvas-close-btn');
  if (closeBtn) {
    closeBtn.addEventListener('click', closeCanvas);
  }

  // Listen for workspace-open-item events (dispatched on window by explorer.js).
  window.addEventListener('workspace-open-item', (e) => {
    if (e.detail) openWorkspaceItem(e.detail);
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

/** Show or hide the canvas header bar.
 *  In the tab model, the header (containing the tab bar) is always visible.
 *  This function is kept for backward compatibility but is effectively a no-op.
 *  @param {boolean} visible */
export function showCanvasHeader(visible) {
  // No-op: tab bar must remain visible for tab switching.
  // Individual tabs manage their own content area.
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
      serializable.push({
        id: t.id,
        title: t.title,
        type: t.type,
        absPath: t.absPath || null,   // null for panel tabs (Teams/Flows)
        pinned: t.pinned,
        closable: t.closable !== false,  // default true
      });
    }
    localStorage.setItem(LS_TABS_KEY, JSON.stringify({
      v: 2,  // schema version — v1 (pre-unified-persistence) lacked closable
             // and skipped no-absPath tabs; restoreTabs discards non-v2 data
      tabs: serializable,
      activeTabId: activeTabId,
    }));
    console.log('[persistTabs] saved', serializable.length, 'tabs', serializable.map(t => t.id));
  } catch (e) { /* storage full — non-critical */ }
}

/** Restore tabs from localStorage on page load.
 *  Two-phase restore:
 *  1. Panel tabs without absPath (Teams/Flows) are re-opened synchronously —
 *     they re-render from live state via the 'canvas-tab-restore' event.
 *  2. File tabs are re-opened asynchronously by sending a readFile WS message
 *     for each absPath. The existing fileContent → workspace-open-item
 *     pipeline handles rendering.
 *  Finally the last active tab is re-activated. */
export function restoreTabs() {
  try {
    const raw = localStorage.getItem(LS_TABS_KEY);
    console.log('[restoreTabs] localStorage:', raw);
    if (!raw) {
      console.log('[restoreTabs] no saved tabs — nothing to restore');
      return false;
    }
    const data = JSON.parse(raw);
    // Schema guard: discard pre-v2 leftovers (saved by the old version that
    // skipped no-absPath tabs, often an empty/incomplete list). The next
    // persistTabs call re-saves in the current format.
    if (data.v !== 2) {
      console.log('[restoreTabs] legacy/incomplete schema (v=' + data.v + ') — discarding');
      localStorage.removeItem(LS_TABS_KEY);
      return false;
    }
    if (!data.tabs || data.tabs.length === 0) {
      console.log('[restoreTabs] saved tab list is empty — nothing to restore');
      return false;
    }

    // flow-run tabs are runtime instances — never restore them (defensive;
    // persistTabs already excludes them, but old saved data may not).
    const panelTabs = data.tabs.filter(t => !t.absPath && t.type !== 'flow-run');
    const fileTabs = data.tabs.filter(t => t.absPath);
    console.log('[restoreTabs] restoring', panelTabs.length, 'panel tabs,', fileTabs.length, 'file tabs');

    // Phase 1 — panel tabs, synchronous.
    // openTab replaces the current preview tab when opening a new unpinned
    // tab, so open everything as pinned first, then revert the unpinned
    // ones to preview state afterwards.
    const unpinnedRestored = [];
    for (const tab of panelTabs) {
      const entry = openTab(tab.id, tab.title, {
        type: tab.type || 'generic',
        closable: tab.closable !== false,
        pinned: true,
      });
      if (!entry) continue;
      if (tab.pinned === false) unpinnedRestored.push(entry);
      // Let flowCanvas render the pane content for this panel tab.
      window.dispatchEvent(new CustomEvent('canvas-tab-restore', {
        detail: { id: tab.id, type: tab.type }
      }));
    }
    for (const entry of unpinnedRestored) {
      entry.pinned = false;
      entry.tabEl.classList.add('preview');
      // last unpinned tab in each group becomes that group's preview tab
      previewTabs.set(previewKeyOf(entry.type, entry.absPath), entry.id);
    }
    if (unpinnedRestored.length > 0) persistTabs();

    // Phase 2 — file tabs, async via readFile WS.
    // Defer WS import to avoid circular dependency.
    if (fileTabs.length > 0) {
      Promise.all([import('./ws.js'), import('./state.js')]).then(([{ sendWs, onReconnect }, stateMod]) => {
        const state = stateMod.default;
        const sendFileRequests = () => {
          fileTabs.forEach((tab) => {
            // Send readFile to get content — the response triggers the
            // explorer's fileContent handler which opens the tab.
            // For binary files (images, PDFs) the viewer fetches via /api/nf-file.
            // Mark this path as pinned so explorer's fileContent handler picks it up
            window.dispatchEvent(new CustomEvent('explorer-preload-pinned', {
              detail: { path: tab.absPath, pinned: tab.pinned !== false }
            }));
            sendWs({ type: 'pop.readFile', path: tab.absPath, sessionId: undefined });
          });
          console.log('[restoreTabs] sent readFile for', fileTabs.length, 'file tabs');
          // Re-activate the last active tab if it already exists (panel tab).
          // File tabs activate themselves as their readFile responses arrive.
          if (data.activeTabId) setActiveTab(data.activeTabId);
        };
        // Page-load race: restoreTabs runs before the first WS connect, and
        // sendWs silently drops messages when the socket isn't OPEN. If not
        // connected yet, defer to the first onopen via onReconnect.
        if (state.ws && state.ws.readyState === WebSocket.OPEN) {
          sendFileRequests();
        } else {
          console.log('[restoreTabs] WS not open — deferring file tab restore to onopen');
          let sent = false;
          onReconnect(() => {
            if (sent) return;
            sent = true;
            sendFileRequests();
          });
        }
      });
    } else if (data.activeTabId) {
      setActiveTab(data.activeTabId);
    }
    return true;
  } catch (e) { /* corrupt data — ignore */ }
  return false;
}
