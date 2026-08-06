// flowHelpers.js — Shared utilities for flow visualization modules.

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

export function authHeaders() {
  const token = localStorage.getItem('nebflow_token') || '';
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function fmtTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  if (isNaN(d)) return '';
  return d.toLocaleString();
}

/** Stable overlay container inside an active flow-related tab — survives renderAll.
 *  Checks both 'teams' and 'flows' tab panes since either could be active. */
export function overlayRoot() {
  for (const tabId of ['teams', 'flows']) {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${tabId}"]`);
    if (pane) {
      let root = pane.querySelector('#flow-overlay-root');
      if (!root) {
        root = document.createElement('div');
        root.id = 'flow-overlay-root';
        root.style.position = 'absolute';
        root.style.inset = '0';
        root.style.pointerEvents = 'none';
        pane.appendChild(root);
      }
      return root;
    }
  }
  return document.body;
}
