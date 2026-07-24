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

// Track pending close timeout so openCanvas can cancel it (rapid toggle safety).
let closeTimeout = null;

// ── Tab state ──────────────────────────────────────────────
// Map of tabId -> { id, title, type, paneEl, tabEl, closable }
const tabs = new Map();
let activeTabId = null;

/** Read a previously persisted canvas width (px) from storage. */
function getPersistedCanvasWidth() {
  try {
    const data = JSON.parse(localStorage.getItem(LS_KEY) || '{}');
    return typeof data.canvas === 'number' ? data.canvas : null;
  } catch (_) { return null; }
}

/** Compute the target open width: a persisted width if any, else ~45% of
 *  the viewport, clamped to [min, max] AND to available space (leave room
 *  for sidebar + main panel). */
function computeOpenWidth() {
  // Measure ACTUAL available space instead of guessing fixed widths.
  const sidebar = document.getElementById('sidebar');
  const activityBar = document.getElementById('activity-bar');
  const sidebarW = sidebar ? sidebar.getBoundingClientRect().width : 0;
  const activityW = activityBar ? activityBar.getBoundingClientRect().width + 8 : 0; // +left margin
  const edgeBarW = 3;
  const minMainWidth = 350;
  const maxAvailable = window.innerWidth - sidebarW - activityW - edgeBarW - minMainWidth;
  const effectiveMax = Math.min(MAX_CANVAS_WIDTH, Math.max(MIN_CANVAS_WIDTH, maxAvailable));
  const persisted = getPersistedCanvasWidth();
  if (persisted) return Math.max(MIN_CANVAS_WIDTH, Math.min(effectiveMax, persisted));
  const target = Math.round(window.innerWidth * 0.42);
  return Math.max(MIN_CANVAS_WIDTH, Math.min(effectiveMax, target));
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

/** Close the canvas panel and clear all tabs.
 *
 *  Mirrors the sidebar pattern: remove body class, CSS animates back to 0.
 *  After the transition, all tabs are removed. */
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

  // Cleanup after the CSS transition completes.
  closeTimeout = setTimeout(() => {
    clearAllTabs();
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
  const { type = 'generic', closable = true } = opts;

  // Open the panel if it's not already visible.
  if (!isCanvasOpen()) openCanvas();

  // If tab already exists, just switch to it.
  if (tabs.has(id)) {
    setActiveTab(id);
    return tabs.get(id);
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
  tab.className = 'canvas-tab';
  tab.dataset.tabId = id;
  const closeHtml = closable
    ? `<button class="canvas-tab-close" title="Close"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M18 6L6 18M6 6l12 12"/></svg></button>`
    : '';
  tab.innerHTML = `<span class="canvas-tab-label">${title}</span>${closeHtml}`;

  // Click tab (not close button) → switch to it.
  tab.addEventListener('click', (e) => {
    if (e.target.closest('.canvas-tab-close')) return;
    setActiveTab(id);
  });

  // Click close button → close tab.
  const closeBtn = tab.querySelector('.canvas-tab-close');
  if (closeBtn) {
    closeBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      closeTab(id);
    });
  }

  tabBar.appendChild(tab);
  // Auto-scroll the tab bar to show the newly added tab.
  tabBar.scrollLeft = tabBar.scrollWidth;

  const entry = { id, title, type, paneEl: pane, tabEl: tab, closable };
  tabs.set(id, entry);
  setActiveTab(id);

  return entry;
}

/** Close a single tab and switch to an adjacent one.
 *  If the closed tab was the last one, closes the entire panel.
 *  Dispatches 'canvas-tab-closed' so external code (e.g. flowCanvas) can clean up.
 *  @param {string} id — Tab identifier. */
export function closeTab(id) {
  const entry = tabs.get(id);
  if (!entry) return;

  document.dispatchEvent(new CustomEvent('canvas-tab-closed', { detail: { id } }));

  // Find the next tab to activate.
  const ids = [...tabs.keys()];
  const idx = ids.indexOf(id);
  const nextId = ids[idx + 1] || ids[idx - 1] || null;

  entry.paneEl.remove();
  entry.tabEl.remove();
  tabs.delete(id);

  if (activeTabId === id) {
    activeTabId = null;
    if (nextId) {
      setActiveTab(nextId);
    } else {
      closeCanvas();
    }
  }
}

/** Switch the active tab — shows its pane, hides all others.
 *  @param {string} id — Tab identifier. */
export function setActiveTab(id) {
  if (!tabs.has(id)) return;
  activeTabId = id;
  tabs.forEach(t => {
    t.paneEl.classList.toggle('active', t.id === id);
    t.tabEl.classList.toggle('active', t.id === id);
  });
  document.dispatchEvent(new CustomEvent('canvas-tab-switched', { detail: { id } }));
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

/** Remove all tabs and reset state. Called during canvas close.
 *  Dispatches 'canvas-tab-closed' for each tab so external code can clean up. */
function clearAllTabs() {
  tabs.forEach(t => {
    document.dispatchEvent(new CustomEvent('canvas-tab-closed', { detail: { id: t.id } }));
    t.paneEl.remove();
    t.tabEl.remove();
  });
  tabs.clear();
  activeTabId = null;
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

/** Open a workspace item (file, card, etc.) in a new tab.
 *  Handles markdown, code, and HTML content types.
 *  If a tab with the same id is already open, just switches to it.
 *
 *  @param {object} item — { id, type, title, content }
 *  @param {string} item.id      — Unique identifier (used for tab dedup)
 *  @param {string} item.type    — 'markdown' | 'code' | 'html'
 *  @param {string} item.title   — Tab label
 *  @param {string} item.content — Raw content (markdown text, source code, or HTML) */
export async function openWorkspaceItem(item) {
  const { id, itemType, title, content } = item;
  const type = itemType;  // workspace items use 'itemType', not 'type'
  if (!id) return;

  // If tab already exists, just switch to it.
  if (tabs.has(id)) {
    setActiveTab(id);
    return;
  }

  const entry = openTab(id, title || id, { type });
  if (!entry) return;
  const pane = entry.paneEl;

  switch (type) {
    case 'markdown': {
      const { renderMarkdownWithMath } = await import('./utils.js');
      pane.innerHTML = `<div class="canvas-md-viewer">${renderMarkdownWithMath(content || '')}</div>`;
      pane.classList.add('scrollable');
      break;
    }
    case 'code': {
      const { highlightCode } = await import('./utils.js');
      const highlighted = highlightCode(content || '', title || '');
      pane.innerHTML = `<div class="canvas-code-viewer">${highlighted || `<pre class="tool-body-pre hljs"><code>${escapeHtml(content)}</code></pre>`}</div>`;
      pane.classList.add('scrollable');
      break;
    }
    case 'html': {
      pane.innerHTML = `<div class="canvas-html-viewer">${content || ''}</div>`;
      pane.classList.add('scrollable');
      break;
    }
    default: {
      pane.innerHTML = content || '';
    }
  }
}

// ── Initialization ─────────────────────────────────────────

/** Initialize canvas event listeners. Call on DOM ready. */
export function initCanvas() {
  const closeBtn = document.getElementById('canvas-close-btn');
  if (closeBtn) {
    closeBtn.addEventListener('click', closeCanvas);
  }

  // Listen for workspace-open-item events (dispatched on window by workspace.js).
  window.addEventListener('workspace-open-item', (e) => {
    if (e.detail) openWorkspaceItem(e.detail);
  });

  // Responsive: clamp canvas width when browser is resized.
  window.addEventListener('resize', () => {
    if (!isCanvasOpen()) return;
    const sidebar = document.getElementById('sidebar');
    const activityBar = document.getElementById('activity-bar');
    const sidebarW = sidebar ? sidebar.getBoundingClientRect().width : 0;
    const activityW = activityBar ? activityBar.getBoundingClientRect().width + 8 : 0;
    const maxAvailable = window.innerWidth - sidebarW - activityW - 3 - 350;
    const current = parseFloat(getComputedStyle(document.documentElement)
      .getPropertyValue('--canvas-width')) || 0;
    if (current > maxAvailable) {
      const clamped = Math.max(MIN_CANVAS_WIDTH, maxAvailable);
      document.documentElement.style.setProperty('--canvas-width', clamped + 'px');
    }
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
