// colResizer.js — Draggable column resizers for the 3-column layout.
//
// Layout: Sidebar | resizer | Main | resizer | Canvas
//
// Each .col-resizer has data-left / data-right attributes naming the columns
// it separates ("sidebar", "main", "canvas"). Dragging pins FIXED pixel
// widths directly on the column elements (flex: 0 0 <px>), so the rendered
// width matches exactly what was dragged — no flex-grow sharing surprises.
// Widths are persisted to localStorage and restored on load / canvas open.
//
// Behaviour:
//  • Sidebar resizer — always active when the sidebar is expanded.
//  • Main/canvas resizer — only relevant when the canvas is open. When the
//    canvas closes, Main's pinned width is cleared so it fills the space.

const LS_KEY = 'nebflow_col_widths';

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

  // Clear pinned main/canvas widths when the canvas closes so Main can fill.
  // Restore them when it reopens.
  const canvasPanel = document.getElementById('canvas-panel');
  if (canvasPanel) {
    const mo = new MutationObserver(() => {
      if (canvasPanel.classList.contains('visible')) {
        restoreWidths();
      } else {
        clearPin('main');
        clearPin('canvas');
      }
    });
    mo.observe(canvasPanel, { attributes: true, attributeFilter: ['class'] });
  }

  // When the sidebar collapses, clear its drag-pinned inline flex so the
  // collapse CSS (body.sidebar-collapsed #sidebar { flex: 0 0 0 }) can take
  // effect — otherwise the higher-specificity inline style keeps it expanded.
  // On expand, restore the persisted width so it returns to the user's size.
  const bodyEl = document.body;
  const moSidebar = new MutationObserver(() => {
    if (bodyEl.classList.contains('sidebar-collapsed')) {
      clearPin('sidebar');
    } else {
      restoreWidths();
    }
  });
  moSidebar.observe(bodyEl, { attributes: true, attributeFilter: ['class'] });
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

function bindResizer(handle) {
  handle.addEventListener('mousedown', (e) => {
    e.preventDefault();
    const leftName = handle.dataset.left;
    const rightName = handle.dataset.right;
    const leftEl = COL_EL[leftName]?.();
    const rightEl = COL_EL[rightName]?.();
    if (!leftEl || !rightEl) return;

    // Snapshot the rendered widths at drag start, then pin both columns to
    // FIXED widths so subsequent mouse moves map 1:1 to pixel changes.
    const startX = e.clientX;
    const leftW0 = leftEl.getBoundingClientRect().width;
    const rightW0 = rightEl.getBoundingClientRect().width;
    // Only pin the sidebar (left column of sidebar|main pair).
    // For main|canvas: only control canvas via --canvas-width, let main flex:1.
    if (leftName === 'sidebar') {
      pinWidth(leftName, leftW0);
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
      if (leftName === 'sidebar') {
        pinWidth(leftName, leftW0 + clamped);
      }
      if (rightName === 'canvas') {
        pinWidth(rightName, Math.max(MIN_WIDTHS.canvas, rightW0 - clamped));
      }
      // For sidebar|main resizer: also pin main so drag works
      if (rightName === 'main') {
        pinWidth(rightName, rightW0 - clamped);
      }
      notifyResize();
    };

    const onUp = () => {
      handle.classList.remove('dragging');
      document.body.classList.remove('col-resizing');
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      // When the canvas isn't open, don't leave a pinned width on Main —
      // let it auto-fill. Only the sidebar width is meaningful in 2-column mode.
      if (!document.body.classList.contains('canvas-open') && rightName === 'main') {
        clearPin('main');
      }
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
    if (leftName === 'sidebar') pinWidth('sidebar', DEFAULT_SIDEBAR);
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

/** Persist current pinned widths. Only persists main/canvas when the canvas
 *  is open, so a 2-column sidebar drag doesn't pin an irrelevant main width. */
function saveWidths() {
  const canvasOpen = document.body.classList.contains('canvas-open');
  const data = {};
  const sb = pinnedWidth('sidebar');
  if (sb) data.sidebar = sb;
  if (canvasOpen) {
    const m = pinnedWidth('main');
    const c = pinnedWidth('canvas');
    if (m) data.main = m;
    if (c) data.canvas = c;
  }
  try { localStorage.setItem(LS_KEY, JSON.stringify(data)); } catch (_) {}
}

/** Restore persisted widths on load. Sidebar always; canvas only when
 *  the canvas is open. Main is NEVER pinned — it always flex:1 to fill. */
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
