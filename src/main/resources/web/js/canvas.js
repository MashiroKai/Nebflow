// canvas.js — Canvas panel (画板) controller.
//
// The canvas is a generic display container on the right side of the layout.
// It is NOT a ChatView — it doesn't manage sessions or streaming state.
// Future features (diff viewer, mind-map, plan preview, etc.) inject content
// via setCanvasContent() and control visibility via openCanvas()/closeCanvas().
//
// Open/close animation mirrors the sidebar collapse: the panel always uses
// flex:0 0 (a concrete flex-basis, never flex-grow — CSS can't transition
// flex-grow). openCanvas sets flex-basis 0 → target px; closeCanvas animates it
// back to 0. colResizer uses the same `flex: 0 0 <px>` model when dragging, so
// the two never conflict. This is what makes the flow toggle feel as smooth as
// the sidebar toggle.

const MIN_CANVAS_WIDTH = 320;
const MAX_CANVAS_WIDTH = 1200;
const MIN_MAIN_WIDTH = 320;
const LS_KEY = 'nebflow_col_widths';

/** Read a previously persisted canvas width (px) from colResizer's storage. */
function getPersistedCanvasWidth() {
  try {
    const data = JSON.parse(localStorage.getItem(LS_KEY) || '{}');
    return typeof data.canvas === 'number' ? data.canvas : null;
  } catch (_) { return null; }
}

/** Compute the target open width: a persisted drag width if any, else ~45% of
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
 *  Expand mirrors the collapse: the canvas column grows (flex-basis 0 → target)
 *  while Main contracts by the same amount, in lockstep. The inner #canvas-slide
 *  starts off-screen to the right (translateX(100%)) and slides in to 0 as the
 *  column widens — so the content reveals right-to-left exactly as the space
 *  opens up. This is the symmetric inverse of closeCanvas. */
export function openCanvas(title = '') {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;
  const main = document.getElementById('main');
  const slide = document.getElementById('canvas-slide');

  panel.classList.remove('hidden');
  panel.classList.add('visible');
  if (title) {
    const titleEl = document.getElementById('canvas-title');
    if (titleEl) titleEl.textContent = title;
  }
  document.body.classList.add('canvas-open');

  const canvasTarget = computeOpenWidth();

  // Starting points: canvas collapsed (0), Main pinned to its current width so
  // its flex-basis can animate (instead of flex-grow snapping).
  panel.style.flex = '0 0 0px';
  let mainTarget = null;
  if (main) {
    const mainW = main.getBoundingClientRect().width;
    mainTarget = Math.max(MIN_MAIN_WIDTH, Math.round(mainW - canvasTarget));
    main.style.flex = `0 0 ${mainW}px`;
  }
  // Slide starts off-screen to the right.
  if (slide) slide.removeAttribute('data-state');

  // Commit the starting frame, then set both targets + slide-in in the same
  // frame so the width grow, Main's shrink, and the slide-in run together.
  void panel.offsetWidth;
  panel.style.flex = `0 0 ${canvasTarget}px`;
  if (main && mainTarget !== null) main.style.flex = `0 0 ${mainTarget}px`;
  if (slide) slide.setAttribute('data-state', 'open');
}

/** Close the canvas panel and clear its content.
 *
 *  Close is tuned separately from open (its own easing/duration). The canvas
 *  column shrinks (flex-basis → 0) while Main expands to fill, and the inner
 *  slide layer translates back off to the right. Once the slide-out ends, the
 *  panel is hidden and Main released to flex:1. */
export function closeCanvas() {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;
  const main = document.getElementById('main');
  const slide = document.getElementById('canvas-slide');

  // Persist the current flex-basis so a later reopen returns to the same size.
  const m = (panel.style.flex || '').match(/([\d.]+)px/);
  const currentPx = m ? parseFloat(m[1]) : 0;
  if (currentPx > 0) {
    try {
      const data = JSON.parse(localStorage.getItem(LS_KEY) || '{}');
      data.canvas = currentPx;
      localStorage.setItem(LS_KEY, JSON.stringify(data));
    } catch (_) {}
  }

  // Main expands to reclaim the canvas's width. Pin it first, reflow, then set
  // the grown target so its flex-basis transition runs.
  let mainTarget = null;
  if (main) {
    const mainW = main.getBoundingClientRect().width;
    mainTarget = Math.round(mainW + currentPx);
    main.style.flex = `0 0 ${mainW}px`;
    void main.offsetWidth;
  }

  // Slide the content off to the right (data-state="closed" → translateX(100%)).
  if (slide) slide.setAttribute('data-state', 'closed');
  // Shrink the canvas column to 0; Main grows by the same amount in lockstep.
  document.body.classList.remove('canvas-open');
  panel.style.flex = '0 0 0px';
  if (main && mainTarget !== null) main.style.flex = `0 0 ${mainTarget}px`;

  let finished = false;
  const finishHide = () => {
    if (finished) return;
    finished = true;
    panel.classList.remove('visible');
    panel.classList.add('hidden');
    if (slide) slide.removeAttribute('data-state');
    const content = document.getElementById('canvas-content');
    if (content) content.innerHTML = '';
    panel.removeEventListener('transitionend', onEnd);
    // Release Main back to flex:1 (already at full width — no visible jump).
    if (main) main.style.flex = '';
  };
  const onEnd = (e) => {
    // React when the slide-out (transform) or the column shrink (flex-basis) ends.
    if ((e.target === slide && e.propertyName === 'transform') ||
        (e.target === panel && (e.propertyName === 'flex-basis' || e.propertyName === 'flex'))) {
      finishHide();
    }
  };
  panel.addEventListener('transitionend', onEnd);
  if (slide) slide.addEventListener('transitionend', onEnd);
  // Safety net: if no transition fires (reduced motion / already collapsed),
  // still hide so the panel doesn't linger invisible-but-present.
  setTimeout(finishHide, 450);
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
