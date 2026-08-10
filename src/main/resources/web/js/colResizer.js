// colResizer.js — Draggable column resizers for the 3-column layout.
//
// Layout: Sidebar | resizer | Main | resizer | Canvas (fixed order — panels
// are not reorderable; only widths can be adjusted by dragging a resizer).
//
// Each .col-resizer has data-left / data-right attributes naming the columns
// it separates ("sidebar", "main", "canvas"). Dragging pins FIXED pixel
// widths directly on the column elements (flex: 0 0 <px>), so the rendered
// width matches exactly what was dragged — no flex-grow sharing surprises.
// Widths are persisted to localStorage and restored on load / canvas open.
//
// Resizer visibility is also managed here: a resizer shows only when BOTH
// adjacent panels are visible (sidebar expanded / canvas open), updated via
// a body-class MutationObserver.
//
// Behaviour:
//  • Sidebar resizer — always active when the sidebar is expanded.
//  • Main/canvas resizer — only relevant when the canvas is open. When the
//    canvas closes, Main's pinned width is cleared so it fills the space.

const LS_KEY = 'nebflow_col_widths';

// Fixed layout order — panels are NOT reorderable; the order below matches
// the DOM (Sidebar | resizer | Main | resizer | Canvas). Resizer i separates
// ORDER[i] and ORDER[i+1].
const ORDER = ['sidebar', 'main', 'canvas'];

// Minimum widths — prevent columns from collapsing too far.
const MIN_WIDTHS = {
  sidebar: 180,
  main: 320,
  canvas: 320,
};

// Default sidebar width (no persisted value).
const DEFAULT_SIDEBAR = 236;

// Map data-* column names → element selectors.
const COL_EL = {
  sidebar: () => document.getElementById('sidebar'),
  main: () => document.getElementById('main'),
  canvas: () => document.getElementById('canvas-panel'),
};

let initialized = false;

/** Initialize resizer handles. Idempotent — safe to call multiple times. */
export function initColResizers() {
  if (initialized) return;
  initialized = true;

  restoreWidths();
  bindAll();

  // Assign each resizer its neighbors (data-left / data-right) from the fixed
  // panel order and set initial visibility. A resizer is visible only when
  // BOTH adjacent panels are visible.
  document.querySelectorAll('.col-resizer').forEach((r, i) => {
    const leftId = ORDER[i];
    const rightId = ORDER[i + 1];
    if (!leftId || !rightId) return;
    r.dataset.left = leftId;
    r.dataset.right = rightId;
    updateResizerVisibility(r, leftId, rightId);
  });

  // Single body-class MutationObserver handles both sidebar and canvas pin
  // management AND resizer visibility — mirrors how the sidebar works (body
  // class drives everything: sidebar-collapsed / canvas-open).
  const bodyEl = document.body;
  const moBody = new MutationObserver(() => {
    // Sidebar: clear pin on collapse, restore on expand.
    if (bodyEl.classList.contains('sidebar-collapsed')) {
      clearPin('sidebar');
    } else {
      restoreWidths();
    }
    // Canvas: clear main pin when canvas closes so Main fills remaining space.
    if (!bodyEl.classList.contains('canvas-open')) {
      clearPin('main');
    }
    updateAllResizerVisibility();
  });
  moBody.observe(bodyEl, { attributes: true, attributeFilter: ['class'] });
}

function isPanelVisible(id) {
  if (id === 'sidebar') return !document.body.classList.contains('sidebar-collapsed');
  if (id === 'canvas') return document.body.classList.contains('canvas-open');
  return true; // main is always visible
}

/** Show/hide a resizer based on whether both adjacent panels are visible. */
function updateResizerVisibility(resizer, leftId, rightId) {
  const show = isPanelVisible(leftId) && isPanelVisible(rightId);
  resizer.style.display = show ? 'block' : 'none';
}

/** Re-evaluate visibility for all resizers (fixed order). */
function updateAllResizerVisibility() {
  document.querySelectorAll('.col-resizer').forEach((r, i) => {
    const leftId = ORDER[i];
    const rightId = ORDER[i + 1];
    if (leftId && rightId) updateResizerVisibility(r, leftId, rightId);
  });
}

function bindAll() {
  document.querySelectorAll('.col-resizer').forEach(bindResizer);
}

/** Pin a fixed pixel width on a column.
 *  For canvas: uses --canvas-width CSS variable so it doesn't fight
 *  the CSS transition on open/close. For others: inline flex. */
function pinWidth(name, px) {
  if (name === 'canvas') {
    document.documentElement.style.setProperty('--canvas-width', px + 'px');
    // Also clear any inline flex on canvas panel so CSS var takes effect
    const el = COL_EL[name]?.();
    if (el) el.style.flex = '';
    return;
  }
  const el = COL_EL[name]?.();
  if (el) el.style.flex = `0 0 ${px}px`;
}

/** Remove a pinned width so the column uses its CSS default (flex:1 / fill). */
function clearPin(name) {
  if (name === 'canvas') {
    // Don't clear --canvas-width on close — closeCanvas handles it
    return;
  }
  const el = COL_EL[name]?.();
  if (el) el.style.flex = '';
}

/** Bind drag-to-resize on a single .col-resizer handle.
 *  Reads data-left / data-right (set by initColResizers from the fixed panel
 *  order) to know which columns this handle separates, then pins fixed pixel
 *  widths during drag so mouse movement maps 1:1 to width changes.
 *
 *  Pinning strategy (works regardless of panel order):
 *    sidebar — always pinned (flex:0 0 px); drag maps 1:1.
 *    canvas  — pinned via --canvas-width CSS var; drag maps 1:1.
 *    main    — NEVER pinned; always flex:1 to absorb the difference.
 *
 *  This ensures exactly one flexible column at all times, so dragging ANY
 *  resizer correctly adjusts the pinned panel while main grows/shrinks to
 *  fill the remaining space. */
function bindResizer(handle) {
  handle.addEventListener('mousedown', (e) => {
    e.preventDefault();
    const leftName = handle.dataset.left;
    const rightName = handle.dataset.right;
    const leftEl = COL_EL[leftName]?.();
    const rightEl = COL_EL[rightName]?.();
    if (!leftEl || !rightEl) return;

    // Main must be flex:1 (unpinned) so it absorbs width changes.
    clearPin('main');

    const startX = e.clientX;
    const leftW0 = leftEl.getBoundingClientRect().width;
    const rightW0 = rightEl.getBoundingClientRect().width;
    // Pin sidebar and canvas at their current rendered widths.
    if (leftName === 'sidebar') {
      pinWidth(leftName, leftW0);
    }
    if (leftName === 'canvas') {
      pinWidth(leftName, leftW0);
    }
    if (rightName === 'sidebar') {
      pinWidth(rightName, rightW0);
    }
    if (rightName === 'canvas') {
      pinWidth(rightName, rightW0);
    }

    handle.classList.add('dragging');
    document.body.classList.add('col-resizing');

    const onMove = (ev) => {
      const dx = ev.clientX - startX;
      const minL = MIN_WIDTHS[leftName] || 0;
      const minR = MIN_WIDTHS[rightName] || 0;
      // Clamp so neither column shrinks below its minimum.
      const clamped = Math.max(-leftW0 + minL, Math.min(rightW0 - minR, dx));
      // Adjust whichever side is sidebar or canvas (the "fixed" panels).
      if (leftName === 'sidebar' || leftName === 'canvas') {
        pinWidth(leftName, Math.max(MIN_WIDTHS[leftName] || 0, leftW0 + clamped));
      }
      if (rightName === 'sidebar' || rightName === 'canvas') {
        pinWidth(rightName, Math.max(MIN_WIDTHS[rightName] || 0, rightW0 - clamped));
      }
      notifyResize();
    };

    const onUp = () => {
      handle.classList.remove('dragging');
      document.body.classList.remove('col-resizing');
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      saveWidths();
      notifyResize();
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  });

  // Double-click a resizer to reset that pair to defaults.
  handle.addEventListener('dblclick', () => {
    const leftName = handle.dataset.left;
    const rightName = handle.dataset.right;
    clearPin(leftName);
    clearPin(rightName);
    // Sidebar falls back to its default width (it can't be content-driven in
    // 2-column mode the way Main/Canvas can).
    if (leftName === 'sidebar' || rightName === 'sidebar') pinWidth('sidebar', DEFAULT_SIDEBAR);
    clearWidths();
    notifyResize();
  });
}

/** Read a column's pinned width (px) from its inline flex shorthand, or null
 *  if not pinned. Parses the flex-basis token (e.g. "0 0 316px" → 316) rather
 *  than the rendered rect, so resizer negative-margin overlap can't skew it. */
function pinnedWidth(name) {
  const el = COL_EL[name]?.();
  if (!el || !el.style.flex) return null;
  const m = el.style.flex.match(/([\d.]+)px\s*$/);
  return m ? parseFloat(m[1]) : null;
}

/** Persist current pinned widths. Main is never pinned (always flex:1),
 *  so only sidebar and canvas widths are saved. */
function saveWidths() {
  const canvasOpen = document.body.classList.contains('canvas-open');
  const data = {};
  const sb = pinnedWidth('sidebar');
  if (sb) data.sidebar = sb;
  if (canvasOpen) {
    const c = pinnedWidth('canvas');
    if (c) data.canvas = c;
  }
  try { localStorage.setItem(LS_KEY, JSON.stringify(data)); } catch (_) {}
}

/** Restore persisted widths on load. Sidebar always; canvas only when
 *  the canvas is open. Main is NEVER pinned — it always flex:1 to fill.
 *  Transitions are suppressed by the html.ui-init class in <head>. */
function restoreWidths() {
  let data = {};
  try { data = JSON.parse(localStorage.getItem(LS_KEY) || '{}'); } catch (_) {}
  if (data.sidebar) {
    pinWidth('sidebar', Math.max(MIN_WIDTHS.sidebar, data.sidebar));
  }
  const canvasOpen = document.body.classList.contains('canvas-open');
  if (canvasOpen && data.canvas) {
    pinWidth('canvas', Math.max(MIN_WIDTHS.canvas, data.canvas));
  }
}

function clearWidths() {
  try { localStorage.removeItem(LS_KEY); } catch (_) {}
}

/** Notify other modules (flowCanvas) that column sizes changed so they can
 *  re-fit their content. Dispatched on window for easy listening. */
function notifyResize() {
  window.dispatchEvent(new CustomEvent('nebflow-col-resize'));
}
