// panelDragger.js — Drag panel headers to reorder the 3-column layout.
//
// Layout: Sidebar | Main | Canvas (default order, user-rearrangeable)
//
// Each panel has a header bar that serves as a drag handle. Drag a panel
// header and drop it at a new position to reorder. The visual position is
// controlled by CSS `order` — no DOM restructuring needed.
//
// Resizer data-left/data-right attributes are updated to match the new
// neighbors. Resizer visibility is managed here (both adjacent panels must
// be visible for the resizer to show), replacing the old CSS body-class rules.

const ORDER_KEY = 'nebflow_panel_order';

const PANEL_IDS = ['sidebar', 'main', 'canvas'];

const PANEL_EL = {
  sidebar: () => document.getElementById('sidebar'),
  main: () => document.getElementById('main'),
  canvas: () => document.getElementById('canvas-panel'),
};

// Which element inside each panel acts as the drag handle.
// Canvas panel is intentionally excluded — its header overlays the flow
// canvas area, and dragging conflicts with the flow's internal pan/drag.
// The canvas panel can still be repositioned by dragging other panels.
const HANDLE_SEL = {
  sidebar: '.sidebar-topbar',
  main: '#header',
};

// Human-readable labels for the drag ghost.
const PANEL_LABEL = {
  sidebar: 'Sessions',
  main: 'Chat',
  canvas: 'Canvas',
};

// px of movement required before drag activates (prevents accidental drags).
const DRAG_THRESHOLD = 5;

let order = ['sidebar', 'main', 'canvas'];
let dragState = null;
let moBody = null;
let initialized = false;

export function initPanelDragger() {
  if (initialized) return;
  initialized = true;

  restoreOrder();
  applyOrder(false);
  bindAllHandles();
  initHeaderBumpZone();
  observeBodyClass();
}

// ── Header bump hover zone ─────────────────────────────────────
// Creates an invisible hit area over the bump (reverse notch) at the top
// of #header. Hover only triggers when the mouse is on the bump itself,
// not the entire header area.
function initHeaderBumpZone() {
  const header = document.getElementById('header');
  if (!header || header.querySelector('.header-bump-zone')) return;
  const zone = document.createElement('div');
  zone.className = 'header-bump-zone';
  header.appendChild(zone);
  zone.addEventListener('mouseenter', () => header.classList.add('bump-hover'));
  zone.addEventListener('mouseleave', () => header.classList.remove('bump-hover'));
}

// ── Order persistence ──────────────────────────────────────────

function restoreOrder() {
  try {
    const saved = JSON.parse(localStorage.getItem(ORDER_KEY) || '[]');
    if (Array.isArray(saved)
        && saved.length === 3
        && PANEL_IDS.every(id => saved.includes(id))) {
      order = saved;
    }
  } catch (_) { /* ignore */ }
}

function saveOrder() {
  try { localStorage.setItem(ORDER_KEY, JSON.stringify(order)); } catch (_) {}
}

// ── Apply order to DOM via CSS `order` ─────────────────────────

function applyOrder(persist = true) {
  // Panels: order 0, 2, 4
  order.forEach((id, i) => {
    const el = PANEL_EL[id]?.();
    if (el) el.style.order = i * 2;
  });

  // Resizers: order 1, 3 — positioned between adjacent panels
  const resizers = document.querySelectorAll('.col-resizer');
  resizers.forEach((r, i) => {
    if (i < order.length - 1) {
      const leftId = order[i];
      const rightId = order[i + 1];
      r.style.order = i * 2 + 1;
      r.dataset.left = leftId;
      r.dataset.right = rightId;
      updateResizerVisibility(r, leftId, rightId);
    } else {
      r.style.display = 'none';
    }
  });

  if (persist) saveOrder();
}

function isPanelVisible(id) {
  if (id === 'sidebar') return !document.body.classList.contains('sidebar-collapsed');
  if (id === 'canvas') return document.body.classList.contains('canvas-open');
  return true; // main is always visible
}

function updateResizerVisibility(resizer, leftId, rightId) {
  const show = isPanelVisible(leftId) && isPanelVisible(rightId);
  resizer.style.display = show ? 'block' : 'none';
}

// Watch body class changes (sidebar collapse / canvas open) to update
// resizer visibility without changing the logical order.
function observeBodyClass() {
  if (moBody) moBody.disconnect();
  moBody = new MutationObserver(() => {
    const resizers = document.querySelectorAll('.col-resizer');
    resizers.forEach((r, i) => {
      if (i < order.length - 1) {
        updateResizerVisibility(r, order[i], order[i + 1]);
      }
    });
  });
  moBody.observe(document.body, { attributes: true, attributeFilter: ['class'] });
}

// ── Drag handle binding ────────────────────────────────────────

function bindAllHandles() {
  PANEL_IDS.forEach(id => {
    const panelEl = PANEL_EL[id]?.();
    if (!panelEl) return;
    const handle = panelEl.querySelector(HANDLE_SEL[id]);
    if (!handle) return;
    handle.addEventListener('mousedown', (e) => onHandleDown(e, id));
    handle.addEventListener('dragstart', (e) => e.preventDefault());
  });
}

function onHandleDown(e, panelId) {
  if (e.button !== 0) return;
  // Don't start drag from interactive elements (buttons, context bar, indicators)
  if (e.target.closest(
    'button, input, textarea, select, a, [contenteditable], ' +
    '.ctx-bar-wrap, #bg-indicator, #delegate-indicator'
  )) return;
  // Panel must be visible to drag
  if (!isPanelVisible(panelId)) return;

  e.preventDefault();

  dragState = {
    panelId,
    startX: e.clientX,
    startY: e.clientY,
    started: false,
    ghost: null,
    insertAt: null,
  };

  document.addEventListener('mousemove', onDragMove);
  document.addEventListener('mouseup', onDragUp);
}

function startDrag() {
  dragState.started = true;
  document.body.classList.add('panel-dragging');

  const panelEl = PANEL_EL[dragState.panelId]?.();
  if (panelEl) panelEl.classList.add('panel-drag-source');

  // Ghost — floating label that follows the cursor
  const ghost = document.createElement('div');
  ghost.className = 'panel-drag-ghost';
  ghost.textContent = PANEL_LABEL[dragState.panelId] || dragState.panelId;
  document.body.appendChild(ghost);
  dragState.ghost = ghost;
}

function onDragMove(e) {
  if (!dragState) return;

  // Check drag threshold
  if (!dragState.started) {
    const dx = e.clientX - dragState.startX;
    const dy = e.clientY - dragState.startY;
    if (Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) return;
    startDrag();
  }

  // Position ghost at cursor
  if (dragState.ghost) {
    dragState.ghost.style.left = e.clientX + 'px';
    dragState.ghost.style.top = e.clientY + 'px';
  }

  // Determine insertion position among the OTHER panels
  const others = order.filter(id => id !== dragState.panelId);
  let insertAt = others.length; // default: append at end

  for (let i = 0; i < others.length; i++) {
    const el = PANEL_EL[others[i]]?.();
    if (!el || !isPanelVisible(others[i])) continue;
    const rect = el.getBoundingClientRect();
    if (e.clientX < rect.left + rect.width / 2) {
      insertAt = i;
      break;
    }
  }
  dragState.insertAt = insertAt;

  positionIndicator(others, insertAt);
}

/** Highlight the resizer handle at the insertion edge instead of drawing a
 *  separate drop line. Reuses the same "active pill" look as the resize handle's
 *  hover/drag state (via the .drop-target class) so the two feel unified. */
let highlightedResizer = null;
function positionIndicator(others, insertAt) {
  // Clear any previously highlighted resizer.
  if (highlightedResizer) {
    highlightedResizer.classList.remove('drop-target');
    highlightedResizer = null;
  }

  let edgeX = null;
  const visibleOthers = others.filter(id => isPanelVisible(id));
  if (visibleOthers.length === 0) return; // nothing to show against

  // Map insertAt to a visible panel edge (left edge of the target panel, or the
  // right edge of the last panel when appending at the end).
  if (insertAt < others.length) {
    const targetId = others[insertAt];
    const el = PANEL_EL[targetId]?.();
    if (el && isPanelVisible(targetId)) {
      edgeX = el.getBoundingClientRect().left;
    }
  }
  if (edgeX === null) {
    for (let i = others.length - 1; i >= 0; i--) {
      if (isPanelVisible(others[i])) {
        const el = PANEL_EL[others[i]]?.();
        if (el) edgeX = el.getBoundingClientRect().right;
        break;
      }
    }
  }

  if (edgeX === null) return;

  // Find the resizer whose center is closest to the insertion edge, and light it
  // up. This is the handle between the two panels the dragged item will sit
  // between — reusing the resize handle's own hover/drag animation.
  let best = null;
  let bestDist = Infinity;
  document.querySelectorAll('.col-resizer').forEach(r => {
    if (r.style.display === 'none') return;
    const rect = r.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const dist = Math.abs(cx - edgeX);
    if (dist < bestDist) { bestDist = dist; best = r; }
  });
  if (best && bestDist < 60) { // only highlight if there's a handle near the edge
    best.classList.add('drop-target');
    highlightedResizer = best;
  }
}

function onDragUp() {
  document.removeEventListener('mousemove', onDragMove);
  document.removeEventListener('mouseup', onDragUp);

  if (!dragState) return;

  if (!dragState.started) {
    dragState = null;
    return;
  }

  // Execute reorder
  if (dragState.insertAt !== null) {
    const others = order.filter(id => id !== dragState.panelId);
    const newOrder = [...others];
    newOrder.splice(dragState.insertAt, 0, dragState.panelId);

    if (newOrder.join(',') !== order.join(',')) {
      order = newOrder;
      applyOrder(true);
      // Notify other modules (flowCanvas, colResizer) to re-fit content
      window.dispatchEvent(new CustomEvent('nebflow-col-resize'));
    }
  }

  // Cleanup
  const panelEl = PANEL_EL[dragState.panelId]?.();
  if (panelEl) panelEl.classList.remove('panel-drag-source');
  if (dragState.ghost) dragState.ghost.remove();
  if (highlightedResizer) {
    highlightedResizer.classList.remove('drop-target');
    highlightedResizer = null;
  }
  document.body.classList.remove('panel-dragging');
  dragState = null;
}
