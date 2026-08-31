// flowHelpers.js — Shared utilities for flow visualization modules.

import { key } from './branding.js';

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

export function authHeaders() {
  const token = localStorage.getItem(key('token')) || '';
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function fmtTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  if (isNaN(d)) return '';
  return d.toLocaleString();
}

/** Relative time for pending mail rows: "just now" / "2 min ago" / "3 hr ago" / "1d ago". */
export function fmtRelTime(ts) {
  if (!ts) return '';
  const diff = Date.now() - ts;
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return 'just now';
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min} min ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} hr ago`;
  return `${Math.floor(hr / 24)}d ago`;
}

// ── Pending mail-queue counts (per agent session) ──────────
// Updated by mailQueued/mailDequeued WS events (flowCanvas.js) and by the
// mailbox viewer's Cancel action (flowViewers.js). Read by flowTeams.js to
// render the pending badge on team cards.
const mailPendingCounts = new Map(); // agentSessionId → pending count

export function setMailPending(sessionId, count) {
  if (!sessionId) return;
  if (count > 0) mailPendingCounts.set(sessionId, count);
  else mailPendingCounts.delete(sessionId);
}

export function teamPendingCount(agents) {
  let n = 0;
  for (const a of agents || []) {
    if (a.sessionId) n += mailPendingCounts.get(a.sessionId) || 0;
  }
  return n;
}

/** Stable overlay container inside an active flow-related tab — survives renderAll.
 *  Prefers the ACTIVE canvas pane (covers flow-run / flow-map / projects tabs too),
 *  then the teams/flows panes, then body. Anchoring to the visible pane is required:
 *  a viewer opened from any flow-related tab must land on-screen, not tucked into a
 *  hidden 0×0 teams pane. */
export function overlayRoot() {
  const active = document.querySelector('.canvas-tab-pane.active');
  const candidate = active
    || document.querySelector('.canvas-tab-pane[data-tab-id="teams"]')
    || document.querySelector('.canvas-tab-pane[data-tab-id="flows"]');
  if (candidate) {
    let root = candidate.querySelector('#flow-overlay-root');
    if (!root) {
      root = document.createElement('div');
      root.id = 'flow-overlay-root';
      root.style.position = 'absolute';
      root.style.inset = '0';
      root.style.pointerEvents = 'none';
      candidate.appendChild(root);
    }
    return root;
  }
  return document.body;
}
