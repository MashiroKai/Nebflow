// canvas.js — Canvas panel (画板) controller.
//
// The canvas is a generic display container on the right side of the layout.
// It is NOT a ChatView — it doesn't manage sessions or streaming state.
// Future features (diff viewer, mind-map, plan preview, etc.) inject content
// via setCanvasContent() and control visibility via openCanvas()/closeCanvas().
//
// Open/close is CSS-driven, mirroring the sidebar collapse pattern:
// JS sets a CSS custom property (--canvas-width) and toggles body.canvas-open;
// CSS handles all transitions via flex-basis. #main stays flex:1 throughout —
// the browser redistributes space naturally, just like sidebar collapse.
//
// Double rAF on open ensures the browser paints one full frame at the initial
// state (display:flex, flex-basis:0) before the transition target is applied.
// The sidebar doesn't need this because it's always visible — only its width
// changes. The canvas starts at display:none, so it needs one committed frame
// at flex-basis:0 before triggering the transition.

const MIN_CANVAS_WIDTH = 320;
const MAX_CANVAS_WIDTH = 1200;
const LS_KEY = 'nebflow_col_widths';

// Track pending close timeout so openCanvas can cancel it (rapid toggle safety).
let closeTimeout = null;

/** Read a previously persisted canvas width (px) from storage. */
function getPersistedCanvasWidth() {
  try {
    const data = JSON.parse(localStorage.getItem(LS_KEY) || '{}');
    return typeof data.canvas === 'number' ? data.canvas : null;
  } catch (_) { return null; }
}

/** Compute the target open width: a persisted width if any, else ~45% of
 *  the viewport, clamped to [min, max]. */
function computeOpenWidth() {
  const persisted = getPersistedCanvasWidth();
  if (persisted) return Math.max(MIN_CANVAS_WIDTH, Math.min(MAX_CANVAS_WIDTH, persisted));
  const target = Math.round(window.innerWidth * 0.45);
  return Math.max(MIN_CANVAS_WIDTH, Math.min(MAX_CANVAS_WIDTH, target));
}

/** Open the canvas panel with a title.
 *  @param {string} title — Display text in the canvas header.
 *
 *  CSS-driven: sets --canvas-width and toggles body.canvas-open. The panel's
 *  flex-basis animates from 0 to the target via CSS transition, and #main
 *  (flex:1) absorbs the change naturally — no JS width pinning needed. */
export function openCanvas(title = '') {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;

  // Cancel any pending close cleanup from a rapid toggle.
  if (closeTimeout) { clearTimeout(closeTimeout); closeTimeout = null; }

  panel.classList.remove('hidden');
  panel.classList.add('visible');
  if (title) {
    const titleEl = document.getElementById('canvas-title');
    if (titleEl) titleEl.textContent = title;
  }

  // Set the target width via CSS custom property; CSS handles the transition.
  const canvasTarget = computeOpenWidth();
  document.documentElement.style.setProperty('--canvas-width', canvasTarget + 'px');

  // Double rAF: the first rAF lets the browser complete one full style recalc
  // + layout + paint cycle, committing the initial state (display:flex,
  // flex-basis:0) to the render tree. The second rAF runs in the next frame,
  // so adding canvas-open correctly triggers a transition from the painted
  // initial state to the target. This replaces the old forced reflow
  // (void offsetWidth) — same frame-commit guarantee, but non-blocking.
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      document.body.classList.add('canvas-open');
    });
  });
}

/** Close the canvas panel and clear its content.
 *
 *  CSS-driven: removes body.canvas-open so the panel's flex-basis animates back
 *  to 0. After the transition duration, content is cleared. */
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

  const slide = document.getElementById('canvas-slide');

  // Remove body class — CSS animates flex-basis back to 0.
  document.body.classList.remove('canvas-open');

  // Cleanup after the transition completes.
  const DURATION = 400; // --panel-duration (0.32s) + 80ms buffer
  closeTimeout = setTimeout(() => {
    panel.classList.remove('visible');
    panel.classList.add('hidden');
    const content = document.getElementById('canvas-content');
    if (content) content.innerHTML = '';
    closeTimeout = null;
  }, DURATION);
}

/** Check whether the canvas is currently visible.
 *  @returns {boolean} */
export function isCanvasOpen() {
  return document.body.classList.contains('canvas-open');
}

/** Set the canvas content.
 *  @param {string} html — HTML string to inject into #canvas-content. */
export function setCanvasContent(html) {
  const content = document.getElementById('canvas-content');
  if (content) content.innerHTML = html;
}

/** Initialize canvas event listeners (close button). Call on DOM ready. */
export function initCanvas() {
  const closeBtn = document.getElementById('canvas-close-btn');
  if (closeBtn) {
    closeBtn.addEventListener('click', closeCanvas);
  }
}

/** Show or hide the canvas header bar.
 *  @param {boolean} visible */
export function showCanvasHeader(visible) {
  const header = document.querySelector('.canvas-header');
  const content = document.getElementById('canvas-content');
  if (header) header.style.display = visible ? '' : 'none';
  if (content) content.style.paddingTop = visible ? '' : '0px';
}
