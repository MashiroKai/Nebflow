// canvas.js — Canvas panel (画板) controller.
//
// The canvas is a generic display container on the right side of the layout.
// It is NOT a ChatView — it doesn't manage sessions or streaming state.
// Future features (diff viewer, mind-map, plan preview, etc.) inject content
// via setCanvasContent() and control visibility via openCanvas()/closeCanvas().

/** Open the canvas panel with a title.
 *  @param {string} title — Display text in the canvas header. */
export function openCanvas(title = '') {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;
  document.body.classList.add('canvas-open');
  panel.classList.remove('hidden');
  panel.classList.add('visible');
  if (title) {
    const titleEl = document.getElementById('canvas-title');
    if (titleEl) titleEl.textContent = title;
  }
}

/** Close the canvas panel and clear its content. */
export function closeCanvas() {
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;
  document.body.classList.remove('canvas-open');
  panel.classList.remove('visible');
  panel.classList.add('hidden');
  const content = document.getElementById('canvas-content');
  if (content) content.innerHTML = '';
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
