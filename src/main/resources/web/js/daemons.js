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
import { toggleHTML, setToggleState, setToggleBusy } from './toggle.js';
import { openDaemonConfig } from './daemonPanel.js';

// ── Inline CSS ─────────────────────────────────────────────
const DAEMON_CSS = `
<style id="daemon-css">
/* ── Header Trigger Button ── */
#daemon-btn {
  background: none; border: 1px solid transparent; cursor: pointer; padding: 0;
  width: 28px; height: 28px; display: flex; align-items: center;
  justify-content: center; border-radius: var(--ds-radius-chip);
  color: var(--color-frame-text-muted); position: relative;
  flex-shrink: 0; transition: background 0.15s, color 0.15s, border-color 0.15s, box-shadow 0.15s;
}
#daemon-btn:hover {
  background: var(--glass-control-bg-hover);
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
  border-radius: var(--ds-radius-card-lg); border: 1px solid var(--sapphire-edge);
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
  padding: var(--ds-space-3) 14px var(--ds-space-1h); flex-shrink: 0;
}
.daemon-panel-title {
  font-size: var(--ds-fs-body); font-weight: 600; color: var(--color-text);
  letter-spacing: -0.02em;
}
.daemon-panel-header-right { display: flex; align-items: center; gap: var(--ds-space-1); }

/* ── Close Button ── */
.daemon-close-btn {
  display: flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border: 1px solid transparent; border-radius: var(--ds-radius-chip);
  background: transparent; cursor: pointer; transition: all 0.15s; opacity: 0.7;
}
.daemon-close-btn { color: var(--color-text-muted); }
.daemon-close-btn:hover {
  opacity: 1;
  background: var(--glass-control-bg-hover);
  border-color: var(--glass-control-border);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge);
}
.daemon-close-btn svg { width: 14px; height: 14px; stroke-width: 2; }

/* ── Panel Body ── */
.daemon-panel-body {
  flex: 1; overflow-y: auto; padding: var(--ds-space-1) var(--ds-space-1h) var(--ds-space-2); min-height: 80px;
  overscroll-behavior: contain;
}
.daemon-panel-body::-webkit-scrollbar { width: 4px; }
.daemon-panel-body::-webkit-scrollbar-track { background: transparent; }
.daemon-panel-body::-webkit-scrollbar-thumb { background: rgba(128, 128, 128, 0.15); border-radius: var(--ds-radius-thin); }
@media (prefers-color-scheme: dark) {
  .daemon-panel-body::-webkit-scrollbar-thumb { background: rgba(255, 255, 255, 0.10); }
}

/* ── Panel Footer ── */
.daemon-panel-footer { flex-shrink: 0; }

/* ── Empty State ── */
.daemon-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 40px var(--ds-space-4) 36px; gap: var(--ds-space-1h); user-select: none;
  border-radius: var(--ds-radius-button); margin: 0 var(--ds-space-1);
}
.daemon-empty-text { font-size: var(--ds-fs-hint); color: var(--color-text); opacity: 0.3; letter-spacing: -0.01em; }
.daemon-empty-hint { font-size: var(--ds-fs-meta); color: var(--color-text); opacity: 0.18; letter-spacing: -0.01em; }

/* ── Daemon Row ── */
.daemon-row {
  display: flex; align-items: center; gap: var(--ds-space-inline-gap); padding: var(--ds-space-2) var(--ds-space-inline-x);
  border-radius: var(--ds-radius-button); margin: var(--ds-space-hair) 0; transition: background 0.15s;
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
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; margin-top: var(--ds-space-1);
}
.daemon-status.running { background: var(--color-primary, #07c160); animation: daemon-pulse 1.6s ease-out infinite; }
.daemon-status.stopped { background: var(--color-text-muted); opacity: 0.3; }
/* ui-status-redesign：图案圆点保留（铁律一载体面），色值散落字面量 → 状态 token。 */
.daemon-status.error { background: var(--status-error-icon); }
.daemon-status.crashed { background: var(--status-error-icon); }
.daemon-status.starting { background: var(--status-warn-icon); }
@keyframes daemon-pulse {
  0% { box-shadow: 0 0 0 0 rgba(7, 193, 96, 0.4); }
  100% { box-shadow: 0 0 0 7px rgba(7, 193, 96, 0); }
}

/* ── Row Info ── */
.daemon-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: var(--ds-space-hair); }
.daemon-name-row { display: flex; align-items: baseline; gap: var(--ds-space-1); }
.daemon-name {
  font-size: var(--ds-fs-body); font-weight: 500; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; letter-spacing: -0.01em;
}
.daemon-name.clickable { cursor: pointer; text-decoration: none; transition: color 0.15s; }
.daemon-name.clickable:hover { color: rgb(91, 127, 191); text-decoration: none; }
.daemon-port {
  font-size: var(--ds-fs-meta); color: var(--color-text-muted); opacity: 0.6;
  flex-shrink: 0; font-variant-numeric: tabular-nums;
}
.daemon-command {
  font-size: var(--ds-fs-meta); color: var(--color-text-muted); opacity: 0.4;
  font-family: ui-monospace, SFMono-Regular, monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* ── 自动启动开关 ──────────────────────────────────────────────────────────
   toggle-unify 批 2026-11-05：原 .daemon-autostart 一族（label + 隐藏
   input[checkbox] + .daemon-autostart-track + .daemon-autostart-thumb，
   26×15 轨道 / 11px 圆钮 / 圆钮带 0 1px 2px 投影 / 蓝 rgb(91,127,191) 自造色）
   是**全站第二套开关**，整族删除。开关本体改为共享组件（js/toggle.js 的
   .nb-toggle，css/sidebar.css，40×22 胶囊 + 16 圆钮），行为走 click + setToggleState，
   几何/配色/动效全部来自 --nf-toggle-* 令牌。本文件零开关样式。
   注：本段位于 DAEMON_CSS 模板串内 —— 注释里不许出现反引号（会截断模板串）。 */

/* ── Action Buttons ── */
.daemon-actions { display: flex; align-items: center; gap: var(--ds-space-1); flex-shrink: 0; }
.daemon-btn {
  font: 500 var(--ds-fs-meta) -apple-system, sans-serif; padding: var(--ds-space-1) var(--ds-space-inline-x);
  border-radius: var(--ds-radius-input); border: 1px solid transparent;
  background: var(--glass-control-bg);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge);
  cursor: pointer; transition: all 0.15s; white-space: nowrap;
}
.daemon-btn.start { color: var(--btn-primary-bg, #111827); border-color: color-mix(in srgb, var(--btn-primary-bg, #111827) 25%, transparent); }
.daemon-btn.start:hover { background: color-mix(in srgb, var(--btn-primary-bg, #111827) 8%, transparent); }
.daemon-btn.stop { color: rgb(91, 127, 191); border-color: rgba(91, 127, 191, 0.25); }
.daemon-btn.stop:hover { background: rgba(91, 127, 191, 0.08); }
.daemon-btn-icon {
  width: 26px; height: 26px; padding: 0; display: flex;
  align-items: center; justify-content: center; border-radius: var(--ds-radius-input);
  border: 1px solid transparent; background: transparent; cursor: pointer;
  color: var(--color-text-muted); transition: all 0.15s;
}
.daemon-btn-icon:hover {
  background: var(--glass-control-bg-hover);
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
    <div class="daemon-empty-hint">${t('daemons.emptyHint')}</div>
    <div class="daemon-empty-hint">${t('daemons.emptyHint2')}</div>
    <div class="daemon-empty-hint">${t('daemons.emptyHint3')}</div>`;
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
    ${toggleHTML({
      on: !!d.autoStart,
      label: `Auto-start with ${brand.productName}`,
      title: `Auto-start with ${brand.productName}`,
      attrs: `data-autostart-id="${esc(d.id)}"`,
    })}
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
      ${d.hasConfigPanel
        /* daemonpanel Phase A (F-3): appended AFTER delete, so the existing
           start|stop → restart → delete order is untouched. Native <button>,
           never a div[role=button] — it must be keyboard-reachable and sit
           last in the row's Tab sequence (C4). `hasConfigPanel` is true only
           when the backend published a VALIDATED panel (an invalid declaration
           is rejected whole ⇒ no button, never a partial render — C1/C2). */
        ? `<button class="daemon-btn-icon config" data-act="config" data-id="${esc(d.id)}" title="${t('daemons.config')}">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
      </button>`
        : ''}
    </div>`;

  // Daemon name is a native <a target="_blank"> when the port is open —
  // no JS click handler needed (and none that Safari could block).

  // Auto-start toggle — shared switch component（js/toggle.js）：乐观翻面 + PUT，
  // 失败回滚。toggle-unify 批 2026-11-05 前这里是 label+input[checkbox] 自画开关，
  // 自带一套 track/thumb 与蓝 rgb(91,127,191) 自造色；现在行为与本行其余控件同源。
  const autoSwitch = /** @type {HTMLButtonElement|null} */ (row.querySelector('.nb-toggle[data-autostart-id]'));
  if (autoSwitch) {
    autoSwitch.addEventListener('click', async (e) => {
      e.stopPropagation();
      const id = autoSwitch.getAttribute('data-autostart-id');
      const next = !autoSwitch.classList.contains('on');
      setToggleState(autoSwitch, next); // 乐观：组件自有翻转（与 bindToggle 同一套视觉态）
      const target = daemons.find(x => x.id === id);
      if (target) target.autoStart = next;
      // 在途用 aria-busy（不确定态）而不是 disabled：禁用一个刚被键盘操作的按钮会
      // 立刻丢焦点 —— 用户按 Space 之后紧接着按 Enter 就没反应了（本批实测踩到）。
      setToggleBusy(autoSwitch, true);
      const resp = await apiCall('PUT', `/api/daemons/${encodeURIComponent(id)}`, { autoStart: next });
      setToggleBusy(autoSwitch, false);
      if (!resp) {
        // Revert on failure — 界面绝不留在假态
        if (target) target.autoStart = !next;
        setToggleState(autoSwitch, !next);
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
          (/** @type {HTMLButtonElement} */ (btn)).disabled = true;
          const resp = await apiCall('DELETE', `/api/daemons/${encodeURIComponent(id)}`);
          if (resp) {
            // Optimistic removal — no full re-render
            daemons = daemons.filter(x => x.id !== id);
            const body = document.querySelector('#daemon-panel .daemon-panel-body');
            row.remove();
            if (body && daemons.length === 0) body.appendChild(buildEmptyState());
          } else {
            (/** @type {HTMLButtonElement} */ (btn)).disabled = false;
            fetchDaemons();
          }
        });
        return;
      }

      if (act === 'config') {
        // daemonpanel Phase A: open the config-panel overlay. Its open state is
        // deliberately independent of `panelOpen` — the 5s poll re-renders the
        // list and sharing the flag would reset the form (C6).
        openDaemonConfig(id, btn);
        return;
      }

      if (act === 'restart') {
        (/** @type {HTMLButtonElement} */ (btn)).disabled = true;
        // Optimistic: show starting state on this row
        const target = daemons.find(x => x.id === id);
        if (target) { target.status = 'starting'; updateRowState(row, target); }
        await apiCall('POST', `/api/daemons/${encodeURIComponent(id)}/restart`);
        (/** @type {HTMLButtonElement} */ (btn)).disabled = false;
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
    const hit = /** @type {Element|null} */ (e.target);
    if (panel && panelOpen && !panel.contains(hit) && !hit?.closest('#daemon-btn')) {
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
