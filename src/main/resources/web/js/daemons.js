// daemons.js — Dev server daemon management panel.
// Self-contained module: CSS injection, DOM rendering, REST API calls.
// Follows the reminder panel pattern (sapphire glass popover from header button).
//
// Rendering strategy (no-jump):
//   - Rows animate in only on first panel open (class .anim), never on updates.
//   - Button actions update only the affected row (optimistic + silent refresh),
//     never the whole list.
//   - Polling re-renders only when displayed fields actually changed.

import { onMessage } from './ws.js';
import { t } from './i18n.js';
import { brand } from './brand.js';
import { key } from './branding.js';
import { createIconsIn } from './utils.js';

// ── Inline CSS ─────────────────────────────────────────────
const DAEMON_CSS = `
<style id="daemon-css">
/* ── Header Trigger Button ── */
#daemon-btn {
  background: none; border: 1px solid transparent; cursor: pointer; padding: 0;
  width: 28px; height: 28px; display: flex; align-items: center;
  justify-content: center; border-radius: 6px;
  color: var(--color-frame-text-muted); position: relative;
  flex-shrink: 0; transition: background 0.15s, color 0.15s, border-color 0.15s, box-shadow 0.15s;
}
#daemon-btn:hover {
  background: var(--glass-control-bg-hover);
  -webkit-backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  border-color: var(--glass-control-border);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge);
  color: var(--color-frame-text);
}
#daemon-btn svg { width: 16px; height: 16px; stroke-width: 2; opacity: 0.7; }
#daemon-btn.active svg { opacity: 1; color: rgb(91, 127, 191); }
@media (prefers-color-scheme: dark) { #daemon-btn svg { opacity: 0.5; } }

/* ── Panel Container (sapphire glass) ── */
#daemon-panel {
  position: absolute; top: 56px; right: 16px; width: 400px;
  max-height: min(540px, 70vh);
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  border-radius: 16px; border: 1px solid var(--sapphire-edge);
  box-shadow:
    inset 0 -1px 0 0 rgba(91, 127, 191, 0.025),
    inset 1px 0 0 0 rgba(91, 127, 191, 0.02),
    inset -1px 0 0 0 rgba(91, 127, 191, 0.02),
    0px 0px 0px 1px rgba(0, 0, 0, 0.03),
    0px 4px 16px rgba(0, 0, 0, 0.05),
    0px 8px 36px rgba(0, 0, 0, 0.06);
  z-index: 300; visibility: hidden; opacity: 0; pointer-events: none;
  flex-direction: column; overflow: hidden;
  transition: opacity 0.15s, visibility 0.15s;
}
#daemon-panel::before {
  content: ''; position: absolute; top: 0; left: 10%; right: 10%; height: 1px;
  background: linear-gradient(90deg, transparent 10%, var(--sapphire-refraction) 50%, transparent 90%);
  pointer-events: none; z-index: 1;
}
#daemon-panel.open {
  visibility: visible; opacity: 1; pointer-events: auto;
  animation: daemonPanelIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes daemonPanelIn {
  from { opacity: 0; transform: translateY(-8px) scale(0.96); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}
@media (prefers-color-scheme: dark) {
  #daemon-panel {
    box-shadow:
      inset 0 -1px 0 0 rgba(91, 127, 191, 0.035),
      inset 1px 0 0 0 rgba(91, 127, 191, 0.025),
      inset -1px 0 0 0 rgba(91, 127, 191, 0.025),
      0px 0px 0px 1px rgba(255, 255, 255, 0.035),
      0px 4px 16px rgba(0, 0, 0, 0.22),
      0px 8px 36px rgba(0, 0, 0, 0.35);
  }
}

/* ── Panel Header ── */
.daemon-panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px 6px; flex-shrink: 0;
}
.daemon-panel-title {
  font-size: 13px; font-weight: 600; color: var(--color-text);
  letter-spacing: -0.02em;
}
.daemon-panel-header-right { display: flex; align-items: center; gap: 4px; }

/* ── Add / Close Buttons ── */
.daemon-add-btn, .daemon-close-btn {
  display: flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border: 1px solid transparent; border-radius: 6px;
  background: transparent; cursor: pointer; transition: all 0.15s; opacity: 0.7;
}
.daemon-add-btn { color: rgb(91, 127, 191); }
.daemon-close-btn { color: var(--color-text-muted); }
.daemon-add-btn:hover, .daemon-close-btn:hover {
  opacity: 1;
  background: var(--glass-control-bg-hover);
  -webkit-backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  border-color: var(--glass-control-border);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge);
}
.daemon-add-btn svg, .daemon-close-btn svg { width: 14px; height: 14px; stroke-width: 2; }

/* ── Panel Body ── */
.daemon-panel-body {
  flex: 1; overflow-y: auto; padding: 4px 6px 8px; min-height: 80px;
  overscroll-behavior: contain;
}
.daemon-panel-body::-webkit-scrollbar { width: 4px; }
.daemon-panel-body::-webkit-scrollbar-track { background: transparent; }
.daemon-panel-body::-webkit-scrollbar-thumb { background: rgba(128, 128, 128, 0.15); border-radius: 2px; }
@media (prefers-color-scheme: dark) {
  .daemon-panel-body::-webkit-scrollbar-thumb { background: rgba(255, 255, 255, 0.10); }
}

/* ── Panel Footer ── */
.daemon-panel-footer { flex-shrink: 0; }

/* ── Empty State ── */
.daemon-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 40px 16px 36px; gap: 6px; cursor: pointer; user-select: none;
  border-radius: 10px; margin: 0 4px; transition: background 0.15s;
}
.daemon-empty:hover { background: rgba(91, 127, 191, 0.04); }
.daemon-empty-text { font-size: 12px; color: var(--color-text); opacity: 0.3; letter-spacing: -0.01em; }
.daemon-empty-hint { font-size: 11px; color: var(--color-text); opacity: 0.18; letter-spacing: -0.01em; }
@media (prefers-color-scheme: dark) {
  .daemon-empty:hover { background: rgba(91, 127, 191, 0.05); }
}

/* ── Daemon Row ── */
.daemon-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 10px;
  border-radius: 10px; margin: 1px 0; transition: background 0.15s;
}
.daemon-row.anim { animation: daemonRowIn 0.3s cubic-bezier(0.16, 1, 0.3, 1) backwards; }
.daemon-row.anim:nth-child(1) { animation-delay: 0ms; }
.daemon-row.anim:nth-child(2) { animation-delay: 30ms; }
.daemon-row.anim:nth-child(3) { animation-delay: 60ms; }
.daemon-row.anim:nth-child(4) { animation-delay: 90ms; }
.daemon-row.anim:nth-child(5) { animation-delay: 120ms; }
.daemon-row.anim:nth-child(6) { animation-delay: 150ms; }
.daemon-row:hover { background: rgba(91, 127, 191, 0.04); }
@keyframes daemonRowIn { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: translateY(0); } }
@media (prefers-color-scheme: dark) { .daemon-row:hover { background: rgba(91, 127, 191, 0.05); } }

/* ── Status Dot ── */
.daemon-status {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; margin-top: 3px;
}
.daemon-status.running { background: var(--color-primary, #07c160); animation: daemon-pulse 1.6s ease-out infinite; }
.daemon-status.stopped { background: var(--color-text-muted); opacity: 0.3; }
.daemon-status.error { background: #f44336; }
.daemon-status.crashed { background: #f44336; }
.daemon-status.starting { background: #ff9800; }
@keyframes daemon-pulse {
  0% { box-shadow: 0 0 0 0 rgba(7, 193, 96, 0.4); }
  100% { box-shadow: 0 0 0 7px rgba(7, 193, 96, 0); }
}

/* ── Row Info ── */
.daemon-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.daemon-name-row { display: flex; align-items: baseline; gap: 5px; }
.daemon-name {
  font-size: 13px; font-weight: 500; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; letter-spacing: -0.01em;
}
.daemon-name.clickable { cursor: pointer; text-decoration: none; transition: color 0.15s; }
.daemon-name.clickable:hover { color: rgb(91, 127, 191); text-decoration: none; }
.daemon-port {
  font-size: 11px; color: var(--color-text-muted); opacity: 0.6;
  flex-shrink: 0; font-variant-numeric: tabular-nums;
}
.daemon-command {
  font-size: 11px; color: var(--color-text-muted); opacity: 0.4;
  font-family: ui-monospace, SFMono-Regular, monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* ── Auto-start Toggle (small switch) ── */
.daemon-autostart {
  display: inline-flex; align-items: center; gap: 4px; flex-shrink: 0;
  cursor: pointer; user-select: none; opacity: 0.75;
  transition: opacity 0.15s;
}
.daemon-autostart:hover { opacity: 1; }
.daemon-autostart-track {
  position: relative; width: 26px; height: 15px; border-radius: 8px;
  background: rgba(128, 128, 128, 0.28);
  transition: background 0.2s; flex-shrink: 0;
}
.daemon-autostart-thumb {
  position: absolute; top: 2px; left: 2px; width: 11px; height: 11px;
  border-radius: 50%; background: #fff;
  box-shadow: 0 1px 2px rgba(0,0,0,0.25);
  transition: transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}
.daemon-autostart-input {
  position: absolute; opacity: 0; width: 0; height: 0; pointer-events: none;
}
.daemon-autostart-input:checked + .daemon-autostart-track {
  background: rgb(91, 127, 191);
}
.daemon-autostart-input:checked + .daemon-autostart-track .daemon-autostart-thumb {
  transform: translateX(11px);
}

/* ── Action Buttons ── */
.daemon-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.daemon-btn {
  font: 500 11px -apple-system, sans-serif; padding: 4px 10px;
  border-radius: 7px; border: 1px solid transparent;
  background: var(--glass-control-bg);
  -webkit-backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge);
  cursor: pointer; transition: all 0.15s; white-space: nowrap;
}
.daemon-btn.start { color: var(--color-primary, #07c160); border-color: rgba(7, 193, 96, 0.25); }
.daemon-btn.start:hover { background: rgba(7, 193, 96, 0.08); }
.daemon-btn.stop { color: rgb(91, 127, 191); border-color: rgba(91, 127, 191, 0.25); }
.daemon-btn.stop:hover { background: rgba(91, 127, 191, 0.08); }
.daemon-btn-icon {
  width: 26px; height: 26px; padding: 0; display: flex;
  align-items: center; justify-content: center; border-radius: 7px;
  border: 1px solid transparent; background: transparent; cursor: pointer;
  color: var(--color-text-muted); transition: all 0.15s;
}
.daemon-btn-icon:hover {
  background: var(--glass-control-bg-hover);
  -webkit-backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  border-color: var(--glass-control-border);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge);
  color: var(--color-text);
}
.daemon-btn-icon.restart:hover { color: #ff9800; }
.daemon-btn-icon.delete:hover { color: #f44336; }
.daemon-btn-icon svg { width: 13px; height: 13px; stroke-width: 2; }
.daemon-btn:disabled, .daemon-btn-icon:disabled { opacity: 0.4; cursor: default; }

/* ── Add Form ── */
.daemon-add-form {
  padding: 10px 12px; border-radius: 10px;
  background: rgba(91, 127, 191, 0.03);
  border: 1px solid rgba(91, 127, 191, 0.10);
  margin: 2px 4px 6px;
  animation: daemonFormIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes daemonFormIn { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
.daemon-add-field { margin-bottom: 6px; }
.daemon-add-field:last-of-type { margin-bottom: 8px; }
.daemon-add-label {
  display: block; font: 600 10px -apple-system, sans-serif;
  color: var(--color-text-muted); text-transform: uppercase;
  letter-spacing: 0.05em; margin-bottom: 3px;
}
.daemon-add-input {
  width: 100%; border-radius: 6px; padding: 5px 8px; font-size: 12px;
  font-family: inherit; color: var(--color-text);
  background: var(--glass-etched-bg, rgba(0,0,0,0.025));
  outline: none; border: 1px solid var(--glass-etched-border, var(--glass-border));
  transition: border-color 0.2s, box-shadow 0.2s;
  box-sizing: border-box;
}
.daemon-add-input:focus {
  border-color: var(--glass-etched-border-focus, rgba(91,127,191,0.35));
  box-shadow: 0 0 0 2px rgba(91, 127, 191, 0.08);
}
.daemon-add-input.mono { font-family: ui-monospace, SFMono-Regular, monospace; }
.daemon-add-actions { display: flex; gap: 6px; justify-content: flex-end; }
.daemon-add-save {
  font: 600 12px -apple-system, sans-serif; color: #fff;
  background: rgb(91, 127, 191); border: none; border-radius: 7px;
  padding: 5px 14px; cursor: pointer; transition: opacity 0.15s;
}
.daemon-add-save:hover { opacity: 0.88; }
.daemon-add-save:disabled { opacity: 0.5; cursor: default; }
.daemon-add-cancel {
  font: 500 12px -apple-system, sans-serif; color: var(--color-text-muted);
  background: var(--glass-control-bg);
  -webkit-backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  border: 1px solid var(--glass-control-border);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge);
  border-radius: 7px; padding: 4px 12px; cursor: pointer; transition: all 0.15s;
}
.daemon-add-cancel:hover { background: var(--glass-control-bg-hover); color: var(--color-text); }
@media (prefers-color-scheme: dark) {
  .daemon-add-form { background: rgba(91, 127, 191, 0.04); border-color: rgba(91, 127, 191, 0.10); }
  .daemon-add-input { background: rgba(255, 255, 255, 0.04); border-color: rgba(91, 127, 191, 0.08); color: #e0e0e0; }
}
</style>
`;

// ── State ──────────────────────────────────────────────────
let daemons = [];
let panelOpen = false;
let pollTimer = null;

// ── Helpers ────────────────────────────────────────────────
function getToken() { return localStorage.getItem(key('token')) || ''; }
function authHeaders() {
  const t = getToken();
  return t ? { Authorization: `Bearer ${t}` } : {};
}
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

/** Signature of the fields we display — used to skip pointless re-renders. */
function rowSignature(d) {
  return `${d.id}|${normalizeStatus(d.status)}|${d.port ?? ''}|${d.autoStart ? 1 : 0}|${d.name || ''}|${d.portOpen ? 1 : 0}`;
}

/** Defensive status normalize — backend DaemonStatus may serialize as an
 *  object ({"Running": {}}) instead of a plain string ("running"). */
function normalizeStatus(raw) {
  const s = raw || 'stopped';
  return typeof s === 'object'
    ? (Object.keys(s)[0] || '').toLowerCase() || 'stopped'
    : String(s).toLowerCase();
}

// ── API ────────────────────────────────────────────────────
/** Generic JSON API call. Returns parsed body (or null). Never throws. */
async function apiCall(method, path, body) {
  try {
    const opts = { method, headers: { ...authHeaders(), 'Content-Type': 'application/json' } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const resp = await fetch(path, opts);
    if (!resp.ok) return null;
    try { return await resp.json(); } catch { return null; }
  } catch (e) { return null; }
}

/**
 * Refresh the daemons list. When `rerender` is true, only re-renders if the
 * displayed fields actually changed (prevents the panel from jumping on every
 * 5s poll / after every button action).
 */
async function fetchDaemons({ rerender = true } = {}) {
  try {
    const resp = await fetch('/api/daemons', { headers: authHeaders() });
    if (!resp.ok) { if (panelOpen && rerender) renderList(false); return; }
    const data = await resp.json();
    const next = Array.isArray(data) ? data : (data.daemons || []);
    if (!panelOpen) { daemons = next; return; }
    const changed =
      daemons.length !== next.length ||
      daemons.some((d, i) => rowSignature(d) !== rowSignature(next[i]));
    daemons = next;
    if (rerender && changed) renderList(false);
  } catch (e) { if (panelOpen && rerender) renderList(false); }
}

// ── Render ─────────────────────────────────────────────────
function renderList(animate = false) {
  const body = document.querySelector('#daemon-panel .daemon-panel-body');
  if (!body) return;
  body.innerHTML = '';

  if (daemons.length === 0) {
    body.appendChild(buildEmptyState());
  } else {
    for (const d of daemons) body.appendChild(buildRow(d, animate));
  }

  if (typeof lucide !== 'undefined') createIconsIn(body);
}

/** Update only the status-dependent parts of an existing row (no rebuild). */
function updateRowState(row, d) {
  const status = normalizeStatus(d.status);
  const dot = row.querySelector('.daemon-status');
  if (dot) dot.className = `daemon-status ${status}`;
  const startStop = row.querySelector('[data-act="start"], [data-act="stop"]');
  if (startStop) {
    if (status === 'running' || status === 'starting') {
      startStop.dataset.act = 'stop';
      startStop.className = 'daemon-btn stop';
      startStop.textContent = t('daemons.stop');
    } else {
      startStop.dataset.act = 'start';
      startStop.className = 'daemon-btn start';
      startStop.textContent = t('daemons.start');
    }
  }
  // Restart only makes sense while running/starting — disable it otherwise
  const restart = row.querySelector('[data-act="restart"]');
  if (restart) {
    const can = status === 'running' || status === 'starting';
    restart.disabled = !can;
    restart.title = can ? t('daemons.restart') : t('daemons.restartFirst');
  }
}

function buildEmptyState() {
  const el = document.createElement('div');
  el.className = 'daemon-empty';
  el.innerHTML = `
    <div class="daemon-empty-text">${t('daemons.empty')}</div>
    <div class="daemon-empty-hint">${t('daemons.emptyHint')}</div>`;
  return el;
}

function buildRow(d, animate = false) {
  const row = document.createElement('div');
  row.className = 'daemon-row' + (animate ? ' anim' : '');
  row.dataset.id = d.id;
  const status = normalizeStatus(d.status);
  // Clickable based on TCP port probe (portOpen), not process state — an
  // externally-started dev server has a live port but Stopped process state.
  const canOpen = !!d.portOpen && !!d.port;
  const canRestart = status === 'running' || status === 'starting';

  row.innerHTML = `
    <div class="daemon-status ${status}"></div>
    <div class="daemon-info">
      <div class="daemon-name-row">
        ${canOpen
          /* Native <a> link — never blocked by Safari's popup blocker,
             unlike window.open() called from a JS click handler. */
          ? `<a class="daemon-name clickable" href="http://localhost:${esc(d.port)}" target="_blank" rel="noopener">${esc(d.name || d.id)}</a>`
          : `<span class="daemon-name">${esc(d.name || d.id)}</span>`}
        ${d.port ? `<span class="daemon-port">:${esc(d.port)}</span>` : ''}
      </div>
      <div class="daemon-command">${esc(d.command || '')}</div>
    </div>
    <label class="daemon-autostart" title="Auto-start with ${brand.productName}">
      <input type="checkbox" class="daemon-autostart-input" data-id="${esc(d.id)}" ${d.autoStart ? 'checked' : ''}>
      <span class="daemon-autostart-track"><span class="daemon-autostart-thumb"></span></span>
    </label>
    <div class="daemon-actions">
      ${status === 'running' || status === 'starting'
        ? `<button class="daemon-btn stop" data-act="stop" data-id="${esc(d.id)}">${t('daemons.stop')}</button>`
        : `<button class="daemon-btn start" data-act="start" data-id="${esc(d.id)}">${t('daemons.start')}</button>`
      }
      <button class="daemon-btn-icon restart" data-act="restart" data-id="${esc(d.id)}" title="${canRestart ? t('daemons.restart') : t('daemons.restartFirst')}" ${canRestart ? '' : 'disabled'}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M3 21v-5h5"/></svg>
      </button>
      <button class="daemon-btn-icon delete" data-act="delete" data-id="${esc(d.id)}" title="${t('daemons.delete')}">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>
      </button>
    </div>`;

  // Daemon name is a native <a target="_blank"> when the port is open —
  // no JS click handler needed (and none that Safari could block).

  // Auto-start toggle — optimistic flip + PUT, revert on failure.
  const autoInput = row.querySelector('.daemon-autostart-input');
  if (autoInput) {
    autoInput.addEventListener('change', async (e) => {
      e.stopPropagation();
      const id = autoInput.getAttribute('data-id');
      const next = autoInput.checked;
      const target = daemons.find(x => x.id === id);
      if (target) target.autoStart = next;
      const resp = await apiCall('PUT', `/api/daemons/${encodeURIComponent(id)}`, { autoStart: next });
      if (!resp) {
        // Revert on failure
        if (target) target.autoStart = !next;
        autoInput.checked = !next;
      }
    });
  }

  // Action buttons (start/stop/restart/delete) — targeted row updates only.
  row.querySelectorAll('[data-act]').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const act = btn.getAttribute('data-act');
      const id = btn.getAttribute('data-id');

      if (act === 'delete') {
        const name = daemons.find(x => x.id === id)?.name || id;
        // dialog-unify 2026-09-05: unified glass confirm (was bare native
        // window.confirm) — same #delete-box family as every other flow.
        window.__showConfirm?.(t('daemons.deleteTitle'), t('daemons.deleteConfirm', { name }), async () => {
          btn.disabled = true;
          const resp = await apiCall('DELETE', `/api/daemons/${encodeURIComponent(id)}`);
          if (resp) {
            // Optimistic removal — no full re-render
            daemons = daemons.filter(x => x.id !== id);
            const body = document.querySelector('#daemon-panel .daemon-panel-body');
            row.remove();
            if (body && daemons.length === 0) body.appendChild(buildEmptyState());
          } else {
            btn.disabled = false;
            fetchDaemons();
          }
        });
        return;
      }

      if (act === 'restart') {
        btn.disabled = true;
        // Optimistic: show starting state on this row
        const target = daemons.find(x => x.id === id);
        if (target) { target.status = 'starting'; updateRowState(row, target); }
        await apiCall('POST', `/api/daemons/${encodeURIComponent(id)}/restart`);
        btn.disabled = false;
        await fetchDaemons(); // silent refresh — re-renders only if state differs
        return;
      }

      // start / stop
      const target = daemons.find(x => x.id === id);
      if (target) {
        target.status = act === 'start' ? 'starting' : 'stopped';
        updateRowState(row, target);
      }
      await apiCall('POST', `/api/daemons/${encodeURIComponent(id)}/${act}`);
      // Refresh; if the optimistic status matches the server, nothing jumps.
      await fetchDaemons();
    });
  });

  return row;
}

// ── Panel Control ──────────────────────────────────────────
function openPanel() {
  const panel = document.getElementById('daemon-panel');
  if (!panel) return;
  panelOpen = true;
  panel.classList.add('open');
  document.getElementById('daemon-btn')?.classList.add('active');
  if (typeof lucide !== 'undefined') createIconsIn(panel);
  fetchDaemons({ rerender: false }).then(() => renderList(true));
  // Poll every 5 seconds while open — only re-renders when something changed
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(() => { if (panelOpen) fetchDaemons(); }, 5000);
}

function closePanel() {
  const panel = document.getElementById('daemon-panel');
  if (!panel) return;
  panelOpen = false;
  panel.classList.remove('open');
  document.getElementById('daemon-btn')?.classList.remove('active');
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

function togglePanel() {
  if (panelOpen) closePanel();
  else openPanel();
}

// ── Public Init ────────────────────────────────────────────
export function initDaemons() {
  // Inject CSS once
  if (!document.getElementById('daemon-css')) {
    document.head.insertAdjacentHTML('beforeend', DAEMON_CSS);
  }

  const btn = document.getElementById('daemon-btn');
  if (btn) {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      togglePanel();
    });
  }

  // Close button in panel header
  document.getElementById('daemon-close-btn')?.addEventListener('click', (e) => {
    e.stopPropagation(); closePanel();
  });

  // Outside click: close panel
  document.addEventListener('click', (e) => {
    const panel = document.getElementById('daemon-panel');
    if (panel && panelOpen && !panel.contains(e.target) && !e.target.closest('#daemon-btn')) {
      closePanel();
    }
  });

  // WS real-time push — update status without polling
  onMessage('daemonStatus', (msg) => {
    if (Array.isArray(msg.daemons)) {
      daemons = msg.daemons;
      if (panelOpen) renderList(false);
    }
  });
}
