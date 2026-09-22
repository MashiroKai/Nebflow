// daemonPanel.js — daemon config-panel overlay (daemonpanel Phase A).
//
// The host renders the form from the daemon's DECLARATION; the daemon ships no
// code in the schema case. Two shapes:
//
//   kind:"schema" (default)  the host builds the DOM from `fields` — zero
//                            foreign code execution by design.
//   kind:"web" (escape hatch) an iframe carries a daemon-supplied panel. It is
//                            DEFAULT-CLOSED: the backend only serves a web
//                            declaration when nebflow.json carries an explicit
//                            `daemonPanel.allowWeb` switch.
//
// Isolation (F-6 / E1 / E3): the panel iframe carries ONLY `allow-scripts`
// (`allow-forms` added when the declaration asks for forms) — never
// `allow-same-origin`, `allow-popups` or `allow-top-navigation`. `src`/`srcdoc`
// bytes never contain `token=` / `ticket=` — the panel is credential-free and
// its reachable set is the daemon's own origin plus the config-panel endpoints
// the host relays.
//
// Placement: the overlay is appended to <body>, NOT into #daemon-panel — that
// container is `overflow:hidden` (+ backdrop-filter), which would clip a
// descendant overlay to nothing (the #392 precedent: DOM assertions all green
// while the panel is invisible on screen).
//
// The overlay's open state is INDEPENDENT of the daemons `panelOpen` flag: the
// 5s poll re-renders the list, and sharing one flag would reset the form.

import { t } from './i18n.js';
import { brand } from './brand.js';
import { key } from './branding.js';
import { createIconsIn } from './utils.js';

const PANEL_CSS = `
<style id="daemon-panel-css">
#daemon-config-overlay {
  position: fixed; z-index: 420;
  min-width: 320px; max-width: 460px;
  max-height: min(620px, 78vh);
  display: none; flex-direction: column;
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
  overflow: hidden;
}
#daemon-config-overlay.open { display: flex; }
.daemon-config-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px; border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.daemon-config-title { font-size: 14px; font-weight: 600; color: var(--color-text); }
.daemon-config-close {
  background: none; border: 1px solid transparent; cursor: pointer;
  width: 24px; height: 24px; display: flex; align-items: center;
  justify-content: center; border-radius: 6px; padding: 0;
  color: var(--color-frame-text-muted);
}
.daemon-config-close:hover {
  background: var(--glass-control-bg-hover);
  border-color: var(--glass-control-border);
  color: var(--color-frame-text);
}
.daemon-config-close svg { width: 14px; height: 14px; }
.daemon-config-body { padding: 14px; overflow-y: auto; flex: 1; }
.daemon-config-field { display: flex; flex-direction: column; gap: 5px; margin-bottom: 12px; }
.daemon-config-label { font-size: 12px; font-weight: 500; color: var(--color-text-muted); }
.daemon-config-input, .daemon-config-select {
  font: inherit; font-size: 13px; padding: 7px 9px;
  border-radius: 8px; border: 1px solid var(--color-border);
  background: var(--color-surface); color: var(--color-text);
}
.daemon-config-input:focus, .daemon-config-select:focus {
  outline: none; border-color: rgba(91, 127, 191, 0.55);
}
.daemon-config-check { display: flex; align-items: center; gap: 7px; font-size: 13px; color: var(--color-text); }
.daemon-config-hint { font-size: 11px; color: var(--color-text-muted); }
.daemon-config-footer {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 12px 14px; border-top: 1px solid var(--color-border); flex-shrink: 0;
}
.daemon-config-btn {
  font: inherit; font-size: 13px; font-weight: 500; cursor: pointer;
  padding: 6px 14px; border-radius: 10px;
  border: 1px solid var(--color-border);
  background: var(--glass-control-bg); color: var(--color-text);
}
.daemon-config-btn.primary {
  background: rgba(91, 127, 191, 0.16);
  border-color: rgba(91, 127, 191, 0.45);
}
.daemon-config-btn:disabled { opacity: 0.45; cursor: default; }
.daemon-config-status { font-size: 12px; color: var(--color-text-muted); margin-right: auto; }
.daemon-config-status.error { color: #f44336; }
.daemon-config-iframe { width: 100%; height: 420px; border: 0; border-radius: 8px; background: var(--color-surface); }
</style>`;

let overlay = null;
let current = null; // { id, decl, values, trigger }
let keyHandler = null;

function getToken() { return localStorage.getItem(key('token')) || ''; }
function authHeaders() {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

/** Build (once) the body-level overlay container.
 *
 *  The container is DECLARED in index.html as a direct child of <body>; we
 *  adopt it when present so the placement is structural (assertable in the
 *  DOM), and only create it as a fallback for embedded/standalone harnesses.
 */
function ensureOverlay() {
  if (overlay && document.body.contains(overlay)) return overlay;
  if (!document.getElementById('daemon-panel-css')) {
    document.head.insertAdjacentHTML('beforeend', PANEL_CSS);
  }
  overlay = document.getElementById('daemon-config-overlay');
  if (!overlay) {
    overlay = document.createElement('div');
    overlay.id = 'daemon-config-overlay';
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-modal', 'false');
    // Appended to <body>: never inside #daemon-panel (overflow:hidden clips it).
    document.body.appendChild(overlay);
  }
  return overlay;
}

/** Render one declared field control (schema kind). Text goes in via
 *  textContent / value — never innerHTML — so a hostile declaration cannot
 *  inject markup through a label or option string. */
function buildField(field, value) {
  const wrap = document.createElement('div');
  wrap.className = 'daemon-config-field';

  const label = document.createElement('label');
  label.className = 'daemon-config-label';
  label.textContent = field.label || field.key;
  label.htmlFor = `dcf-${current.id}-${field.key}`;
  wrap.appendChild(label);

  let control;
  const type = field.type || 'string';
  if (type === 'boolean') {
    control = document.createElement('input');
    control.type = 'checkbox';
    control.className = 'daemon-config-input';
    control.checked = value === true;
    const row = document.createElement('label');
    row.className = 'daemon-config-check';
    row.appendChild(control);
    const on = document.createElement('span');
    on.textContent = t('daemons.configEnabled');
    row.appendChild(on);
    wrap.appendChild(row);
  } else if (type === 'enum') {
    control = document.createElement('select');
    control.className = 'daemon-config-select';
    for (const opt of field.options || []) {
      const o = document.createElement('option');
      o.value = opt;
      o.textContent = opt;
      control.appendChild(o);
    }
    if (typeof value === 'string') control.value = value;
    wrap.appendChild(control);
  } else if (type === 'text') {
    control = document.createElement('textarea');
    control.className = 'daemon-config-input';
    control.rows = 3;
    control.value = typeof value === 'string' ? value : '';
    wrap.appendChild(control);
  } else {
    control = document.createElement('input');
    control.className = 'daemon-config-input';
    control.type = type === 'secret' ? 'password' : (type === 'number' ? 'number' : 'text');
    if (type === 'number') {
      if (typeof field.min === 'number') control.min = String(field.min);
      if (typeof field.max === 'number') control.max = String(field.max);
    }
    control.value = value === null || value === undefined ? '' : String(value);
    control.autocomplete = 'off';
    wrap.appendChild(control);
  }

  control.id = `dcf-${current.id}-${field.key}`;
  control.dataset.fieldKey = field.key;
  control.dataset.fieldType = type;

  if (type === 'secret') {
    const hint = document.createElement('div');
    hint.className = 'daemon-config-hint';
    // Masked round-trip semantics, stated in the UI: *** keeps, empty skips.
    hint.textContent = t('daemons.configSecretHint');
    wrap.appendChild(hint);
  }
  return wrap;
}

/** Collect the current control values into the PUT payload. */
function collectValues(decl) {
  const out = {};
  for (const field of decl.fields) {
    const el = overlay.querySelector(`[data-field-key="${CSS.escape(field.key)}"]`);
    if (!el) continue;
    const type = field.type || 'string';
    if (type === 'boolean') out[field.key] = !!el.checked;
    else if (type === 'number') {
      const raw = el.value.trim();
      out[field.key] = raw === '' ? null : Number(raw);
    } else out[field.key] = el.value;
  }
  return out;
}

async function apiCall(method, path, body) {
  try {
    const opts = { method, headers: { ...authHeaders(), 'Content-Type': 'application/json' } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const resp = await fetch(path, opts);
    let payload = null;
    try { payload = await resp.json(); } catch { payload = null; }
    return { ok: resp.ok, status: resp.status, payload };
  } catch (e) {
    return { ok: false, status: 0, payload: null };
  }
}

function setStatus(text, isError) {
  const el = overlay?.querySelector('.daemon-config-status');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('error', !!isError);
}

/** Open the panel for one daemon row. `trigger` is the row's config button —
 *  focus returns there on close (C5). */
export async function openDaemonConfig(id, trigger) {
  ensureOverlay();
  const resp = await apiCall('GET', `/api/daemons/${encodeURIComponent(id)}/config-panel`);
  if (!resp.ok) {
    showTransientError(resp, trigger);
    return;
  }
  const decl = resp.payload || {};
  current = { id, decl, values: decl.values || {}, trigger };
  renderOverlay();
  positionOverlay(trigger);
  overlay.classList.add('open');
  const first = overlay.querySelector('input, select, textarea, button');
  if (first) first.focus();
  bindKeyHandler();
}

/** A failed open (409 no config panel / 404) must be visible, not silent —
 *  the row button only exists when the backend already published a panel, so
 *  this is a genuine race/regression signal.
 */
function showTransientError(resp, trigger) {
  const msg = resp.payload?.error || `HTTP ${resp.status}`;
  console.warn('[daemonPanel] config panel unavailable:', msg, resp.status);
  if (trigger) {
    const prev = trigger.title;
    trigger.title = msg;
    setTimeout(() => { trigger.title = prev; }, 2500);
  }
}

function renderOverlay() {
  const decl = current.decl;
  const title = decl.title || `${current.id} · ${t('daemons.config') || 'Config'}`;
  overlay.innerHTML = '';

  const header = document.createElement('div');
  header.className = 'daemon-config-header';
  const h = document.createElement('div');
  h.className = 'daemon-config-title';
  h.textContent = title;
  header.appendChild(h);
  const close = document.createElement('button');
  close.className = 'daemon-config-close';
  close.type = 'button';
  close.title = t('daemons.configClose') || 'Close';
  close.dataset.act = 'close-config';
  close.innerHTML = '<i data-lucide="x"></i>';
  close.addEventListener('click', () => closeDaemonConfig());
  header.appendChild(close);
  overlay.appendChild(header);

  const body = document.createElement('div');
  body.className = 'daemon-config-body';
  if (decl.kind === 'web') body.appendChild(buildWebFrame(decl));
  else for (const field of decl.fields || []) body.appendChild(buildField(field, current.values[field.key]));
  overlay.appendChild(body);

  const footer = document.createElement('div');
  footer.className = 'daemon-config-footer';
  const status = document.createElement('div');
  status.className = 'daemon-config-status';
  status.setAttribute('aria-live', 'polite');
  footer.appendChild(status);
  const save = document.createElement('button');
  save.className = 'daemon-config-btn primary';
  save.type = 'button';
  save.dataset.act = 'save-config';
  save.textContent = t('daemons.configSave') || 'Save';
  save.addEventListener('click', saveValues);
  footer.appendChild(save);
  overlay.appendChild(footer);

  if (typeof lucide !== 'undefined') createIconsIn(overlay);
}

/** The web escape hatch. Sandbox tokens come from the SERVER's validated
 *  declaration and are re-checked here: `allow-same-origin` must never appear.
 */
function buildWebFrame(decl) {
  const frame = document.createElement('iframe');
  frame.className = 'daemon-config-iframe';
  const tokens = (decl.sandbox || ['allow-scripts']).filter(tk =>
    tk === 'allow-scripts' || tk === 'allow-forms');
  frame.setAttribute('sandbox', tokens.join(' '));
  frame.setAttribute('referrerpolicy', 'no-referrer');
  // Belt: a credential parameter must never ride on a panel URL.
  const url = String(decl.panelUrl || '').replace(/([?&])(token|ticket)=[^&]*/g, '$1').replace(/[?&]$/, '');
  frame.src = url;
  return frame;
}

function positionOverlay(trigger) {
  const gap = 8;
  let rect = trigger?.getBoundingClientRect();
  const width = overlay.offsetWidth || 380;
  const height = overlay.offsetHeight || 420;
  if (!rect) {
    overlay.style.left = `${Math.max(gap, window.innerWidth - width - 16)}px`;
    overlay.style.top = '64px';
    return;
  }
  // Anchor to the row button, then clamp into the viewport (the overlay must
  // never land off-screen — the fixed-position trap from the #392 family).
  let left = rect.left - width + rect.width;
  let top = rect.bottom + gap;
  left = Math.min(Math.max(gap, left), Math.max(gap, window.innerWidth - width - gap));
  if (top + height > window.innerHeight - gap) {
    const above = rect.top - height - gap;
    top = above >= gap ? above : Math.max(gap, window.innerHeight - height - gap);
  }
  overlay.style.left = `${left}px`;
  overlay.style.top = `${top}px`;
}

async function saveValues() {
  const save = overlay.querySelector('[data-act="save-config"]');
  if (save) save.disabled = true;
  setStatus('');
  const payload = { values: collectValues(current.decl) };
  const resp = await apiCall('PUT', `/api/daemons/${encodeURIComponent(current.id)}/config-panel`, payload);
  if (save) save.disabled = false;
  if (!resp.ok) {
    const detail = resp.payload?.detail || resp.payload?.error || `HTTP ${resp.status}`;
    setStatus(detail, true);
    return;
  }
  if (resp.payload?.values) current.values = resp.payload.values;
  setStatus(t('daemons.configSaved') || 'Saved');
}

function bindKeyHandler() {
  if (keyHandler) return;
  keyHandler = (e) => {
    if (e.key !== 'Escape' || !overlay?.classList.contains('open')) return;
    e.stopPropagation();
    closeDaemonConfig();
  };
  document.addEventListener('keydown', keyHandler, true);
}

/** Close and return focus to the config button that opened the panel (C5). */
export function closeDaemonConfig() {
  if (!overlay) return;
  overlay.classList.remove('open');
  overlay.innerHTML = '';
  const trigger = current?.trigger;
  current = null;
  if (keyHandler) {
    document.removeEventListener('keydown', keyHandler, true);
    keyHandler = null;
  }
  if (trigger && document.body.contains(trigger)) trigger.focus();
}

export function isDaemonConfigOpen() {
  return !!overlay?.classList.contains('open');
}
