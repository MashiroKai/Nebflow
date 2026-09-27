// secretsPanel.js — Unified secrets management dialog (standalone modal).
//
// secrets-frontend batch, 2026-09-27. Author ruling 2026-09-27 19:20:
// NOT an embedded section inside #settings-modal — the list lives in this
// DEDICATED dialog of its own (capacity not bounded by the settings modal);
// the settings page carries only a one-row entry button (sidebar.js
// renderSettings → #btn-open-secrets, i18n key `settings.secrets`).
//
// Scope of this batch (dispatcher): the CONSUMPTION mechanism (env-like
// consumption + agent invisibility) is NOT implemented here — seed files are
// untouched. This module is the panel face only, developed against the
// plan-card contract (问2) while the parallel backend leg
// (secrets-backend-impl) lands its four routes:
//   GET    /api/secrets                → {"secrets":[{name,size,mtime,mode,refs}]}
//   POST   /api/secrets/{name}         body {"value"} — 409 already_exists
//   PUT    /api/secrets/{name}         body {"value"} — 404 unknown_secret
//   DELETE /api/secrets/{name}?confirm=<name> — 400 confirm_mismatch,
//          409 referenced (refs list), 403 managed_namespace
//
// 🔴 SECURITY DISCIPLINE (plan card §二, binding on this face):
//   · The value lives ONLY in a draft variable, ONLY until the request body
//     is assembled; on success the textarea AND the draft are cleared
//     immediately. Never localStorage, never rendered back, never echoed.
//   · The editor textarea ALWAYS starts empty — in replace mode the stored
//     value is never fetched and never displayed (write-only semantics).
//   · Names go through escapeHtml when interpolated into templates; every
//     dynamic text is set via textContent. innerHTML carries literals and
//     escaped names only — never a value.
//   · The list carries METADATA ONLY (name/size/mtime/mode/refs); there is
//     no read-value endpoint on the contract and none is called.
//
// Precedents: socialPanel.js (module shape, glass dialog family, api()),
// daemons.js:454-456 (window.__showConfirm), sidebar.js renderSettings
// (entry row, .cfg-btn-add), css/secrets.css (this batch; sapphire.css
// untouched). All user-visible copy goes through t() (i18n.js); en + zh-CN
// locale files ship the same key set in the same batch.

import { t } from './i18n.js';
import { escapeHtml, createIconsIn } from './utils.js';
import { getAuthToken } from './neblink.js';

// ── State ────────────────────────────────────────────────────────────────
/** Last list truth read from the backend (metadata only — no value field
 *  exists on the wire shape; anything unexpected is dropped below).
 *  @type {{name: string, size: number, mtime: number|null, mode: string, refs: string[]}[]} */
let secrets = [];
/** Which secret the editor is replacing ('' = create mode). */
let editorTarget = '';
/** The ONE place a typed value exists (create or replace) — cleared the
 *  moment the request body is built and again on any close.
 *  @type {string} */
let draftValue = '';
/** Focusable-element trap state (which element opened the dialog). */
let lastFocusedBeforeOpen = /** @type {HTMLElement|null} */ (null);
let initialized = false;

// ── Backend adapter (same token pattern as socialPanel.js) ───────────────
/**
 * Narrow option shape on purpose: this module only ever issues JSON requests,
 * and a `RequestInit`-wide type is what makes the header merge un-inferable
 * (`checkJs` gate: new files must be clean).
 * @typedef {object} ApiOpts
 * @property {string} [method]
 * @property {Record<string, string>} [headers]
 * @property {string} [body]
 */
/**
 * @param {string} path
 * @param {ApiOpts} [opts]
 * @returns {Promise<Response>}
 */
async function api(path, opts = {}) {
  /** @type {Record<string, string>} */
  const headers = { ...(opts.headers || {}) };
  const tok = getAuthToken();
  if (tok) headers['Authorization'] = `Bearer ${tok}`;
  return fetch(path, { ...opts, headers });
}

/** GET /api/secrets — metadata only; the contract has no read-value face. */
async function fetchSecrets() {
  const resp = await api('/api/secrets');
  if (!resp.ok) throw new Error(`GET /api/secrets ${resp.status}`);
  const data = await resp.json();
  const rows = Array.isArray(data && data.secrets) ? data.secrets : [];
  // Defensive shaping: keep the known metadata fields only. A `value` key (or
  // anything else) on the wire is dropped here and can never reach a render.
  return rows.map((s) => ({
    name: typeof s.name === 'string' ? s.name : '',
    size: typeof s.size === 'number' ? s.size : 0,
    mtime: typeof s.mtime === 'number' ? s.mtime : null,
    mode: typeof s.mode === 'string' ? s.mode : '',
    refs: Array.isArray(s.refs) ? s.refs.filter((r) => typeof r === 'string') : [],
  })).filter((s) => s.name);
}

/**
 * Map an error status to its translated copy (plan card 问2 error family).
 * Unknown statuses fall back to the generic io line. Error bodies carry a
 * name + code, never a value — this face only reads the STATUS.
 * @param {number} status
 * @returns {string}
 */
function errorText(status) {
  switch (status) {
    case 400: return t('secrets.invalidName');
    case 403: return t('secrets.reservedName');
    case 404: return t('secrets.error.notFound');
    case 409: return t('secrets.error.conflict');
    default: return t('secrets.error.io', { code: String(status) });
  }
}

// ── Selection helpers ────────────────────────────────────────────────────
/** @returns {HTMLElement|null} */
function overlay() { return document.getElementById('secrets-overlay'); }
/** @returns {HTMLElement|null} */
function modal() { return document.getElementById('secrets-modal'); }
/** @returns {HTMLElement|null} */
function listEl() { return document.querySelector('#secrets-list'); }
/** @returns {HTMLElement|null} */
function formEl() { return document.querySelector('#secrets-form'); }

/**
 * The panel's readiness state, exposed the standard way: `aria-busy` on the
 * list section while a backend read/write is in flight (same convention as
 * socialPanel.js setBusy) — an outside observer never guesses with a timer.
 * @param {boolean} on
 */
function setBusy(on) {
  const el = document.getElementById('secrets-section');
  if (el) el.setAttribute('aria-busy', on ? 'true' : 'false');
}

/** Byte size with a thousands separator (tabular-nums face, plan card §三). */
function formatSize(bytes) {
  return `${bytes.toLocaleString('en-US')} B`;
}

/** Epoch millis → M-D HH:mm (locale-neutral numeric face; the UI never
 *  localizes secret metadata through toLocaleString date formats). */
function formatMtime(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mi = String(d.getMinutes()).padStart(2, '0');
  return `${mm}-${dd} ${hh}:${mi}`;
}

// ── Rendering ────────────────────────────────────────────────────────────
/**
 * One row. Name (escaped) · size (tabular-nums) · mtime · mode badge
 * (two states) · referenced-by line · inline [replace] [delete]. NO value
 * face exists anywhere in this template — metadata only.
 * @param {{name: string, size: number, mtime: number|null, mode: string, refs: string[]}} s
 * @returns {string}
 */
function rowHTML(s) {
  const permissive = s.mode !== '0600';
  const modeBadge = `<span class="secrets-mode-badge" data-mode="${permissive ? 'permissive' : 'ok'}">${escapeHtml(t(permissive ? 'secrets.state.permissive' : 'secrets.state.ok'))}</span>`;
  const refsLine = s.refs.length
    ? `<div class="secrets-row-refs">${escapeHtml(t('secrets.referencedBy', { refs: s.refs.join(', ') }))}</div>`
    : '';
  const name = escapeHtml(s.name);
  return `<div class="secrets-row" data-name="${name}">
      <div class="secrets-row-main">
        <span class="secrets-row-name">${name}</span>
        ${modeBadge}
        <div class="secrets-row-meta">
          <span class="secrets-row-size">${formatSize(s.size)}</span>
          <span class="secrets-row-mtime">${formatMtime(s.mtime)}</span>
        </div>
      </div>
      ${refsLine}
      <div class="secrets-row-actions">
        <button type="button" class="glass-control cfg-btn cfg-btn-sm" data-replace="${name}">${escapeHtml(t('secrets.replace'))}</button>
        <button type="button" class="glass-control cfg-btn cfg-btn-sm" data-delete="${name}">${escapeHtml(t('secrets.delete'))}</button>
      </div>
    </div>`;
}

/** Full list render from backend truth (createIconsIn for the close icons). */
function renderList() {
  const list = listEl();
  if (!list) return;
  if (!secrets.length) {
    // Empty state: guidance copy + the add button (plan card 问1).
    list.innerHTML = `<div class="cfg-empty">${escapeHtml(t('secrets.empty'))}</div>
        <button type="button" class="glass-control cfg-btn cfg-btn-add" data-add>${escapeHtml(t('secrets.add'))}</button>`;
    return;
  }
  list.innerHTML = secrets.map(rowHTML).join('');
}

/** Captions living in index.html (the shell is text-free; the panel
 *  translates its own subtree — socialPanel.js applyStaticText precedent). */
function applyStaticText() {
  const map = [
    ['secrets-modal-title', 'secrets.title'],
    ['secrets-modal-close', 'secrets.close', 'title'],
    ['secrets-form-title', editorTarget ? 'secrets.replaceTitle' : 'secrets.addTitle'],
    ['secrets-form-close', 'secrets.close', 'title'],
  ];
  for (const [id, key, attr] of map) {
    const el = document.getElementById(id);
    if (!el) continue;
    if (attr) el.setAttribute(attr, t(key)); else el.textContent = t(key);
  }
  const addBtn = document.getElementById('secrets-modal-add');
  if (addBtn) addBtn.textContent = t('secrets.add');
  const nameLabel = document.getElementById('secrets-name-label');
  if (nameLabel) nameLabel.textContent = t('secrets.name');
  const nameInput = /** @type {HTMLInputElement|null} */ (document.getElementById('secrets-name-input'));
  if (nameInput) nameInput.placeholder = t('secrets.nameHint');
  const nameHint = document.getElementById('secrets-name-hint');
  if (nameHint) nameHint.textContent = t('secrets.nameHint');
  const valueLabel = document.getElementById('secrets-value-label');
  if (valueLabel) valueLabel.textContent = t('secrets.value');
  const valueArea = /** @type {HTMLTextAreaElement|null} */ (document.getElementById('secrets-value-input'));
  if (valueArea) valueArea.placeholder = t(editorTarget ? 'secrets.replacePlaceholder' : 'secrets.valuePlaceholder');
  const saveBtn = document.getElementById('secrets-form-save');
  if (saveBtn) saveBtn.textContent = t('secrets.save');
  const cancelBtn = document.getElementById('secrets-form-cancel');
  if (cancelBtn) cancelBtn.textContent = t('secrets.cancel');
}

// ── Load ─────────────────────────────────────────────────────────────────
/** Read the list; failures degrade to the empty state with an error line —
 *  the panel never fakes data it did not read. */
async function loadAll() {
  setBusy(true);
  try {
    secrets = await fetchSecrets();
    renderList();
  } catch {
    secrets = [];
    const list = listEl();
    if (list) {
      list.innerHTML = `<div class="cfg-empty">${escapeHtml(t('secrets.error.io', { code: 'io' }))}</div>
          <button type="button" class="glass-control cfg-btn cfg-btn-add" data-add>${escapeHtml(t('secrets.add'))}</button>`;
    }
  } finally {
    setBusy(false);
  }
}

// ── Editor (create / replace sub-dialog) ─────────────────────────────────
/**
 * Open the editor. Create mode: editable name + naming hint. Replace mode:
 * the name is readonly and the textarea starts EMPTY — the stored value is
 * never fetched, never displayed (write-only replace semantics).
 * @param {string} name '' for create, the target name for replace
 */
function openEditor(name) {
  editorTarget = name;
  draftValue = '';
  const form = formEl();
  if (!form) return;
  const title = document.getElementById('secrets-form-title');
  if (title) title.textContent = t(name ? 'secrets.replaceTitle' : 'secrets.addTitle');
  const nameInput = /** @type {HTMLInputElement|null} */ (document.getElementById('secrets-name-input'));
  if (nameInput) {
    nameInput.value = name;
    nameInput.readOnly = !!name;
    nameInput.placeholder = t('secrets.nameHint');
  }
  // 🔴 The textarea ALWAYS starts empty — never seeded with a stored value.
  const valueArea = /** @type {HTMLTextAreaElement|null} */ (document.getElementById('secrets-value-input'));
  if (valueArea) {
    valueArea.value = '';
    valueArea.placeholder = t(name ? 'secrets.replacePlaceholder' : 'secrets.valuePlaceholder');
  }
  const nameHint = document.getElementById('secrets-name-hint');
  if (nameHint) nameHint.textContent = t('secrets.nameHint');
  const err = document.getElementById('secrets-form-error');
  if (err) { err.hidden = true; err.textContent = ''; }
  const state = document.getElementById('secrets-form-state');
  if (state) state.textContent = '';
  form.hidden = false;
  if (nameInput instanceof HTMLElement) nameInput.focus();
}

/** Close the editor and burn the draft — the value never outlives the form. */
function closeEditor() {
  draftValue = '';
  editorTarget = '';
  const form = formEl();
  if (form) form.hidden = true;
  const valueArea = /** @type {HTMLTextAreaElement|null} */ (document.getElementById('secrets-value-input'));
  if (valueArea) valueArea.value = '';
  const nameInput = /** @type {HTMLInputElement|null} */ (document.getElementById('secrets-name-input'));
  if (nameInput) nameInput.value = '';
  const err = document.getElementById('secrets-form-error');
  if (err) { err.hidden = true; err.textContent = ''; }
}

/** Client-side name shape check (plan card 问5: ^[a-z0-9][a-z0-9._-]{0,63}$;
 *  hard reds: no leading dot, no .tmp suffix). The server re-validates —
 *  this is face guidance, not the authority.
 * @param {string} name
 * @returns {boolean}
 */
function validName(name) {
  return /^[a-z0-9][a-z0-9._-]{0,63}$/.test(name) && !name.endsWith('.tmp');
}

/**
 * Build + send the create/replace request. The draft value goes into the
 * request body ONCE and is cleared immediately after — success or failure —
 * so no plaintext survives the call in memory of this module.
 * @returns {Promise<boolean>}
 */
async function saveEditor() {
  const nameInput = /** @type {HTMLInputElement|null} */ (document.getElementById('secrets-name-input'));
  const valueArea = /** @type {HTMLTextAreaElement|null} */ (document.getElementById('secrets-value-input'));
  const err = document.getElementById('secrets-form-error');
  const stateEl = document.getElementById('secrets-form-state');
  const showErr = (/** @type {string} */ msg) => {
    if (err) { err.textContent = msg; err.hidden = false; }
  };
  const name = (editorTarget || (nameInput ? nameInput.value.trim() : ''));
  if (!name) { showErr(t('secrets.invalidName')); return false; }
  if (!editorTarget && !validName(name)) { showErr(t('secrets.invalidName')); return false; }
  const value = draftValue;
  if (!value) { showErr(t('secrets.error.emptyValue')); return false; }
  const replacing = !!editorTarget;
  setBusy(true);
  try {
    const resp = await api(`/api/secrets/${encodeURIComponent(name)}`, {
      method: replacing ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }), // the plaintext's single appearance
    });
    if (!resp.ok) { showErr(errorText(resp.status)); return false; }
    // Success: the draft is gone, the textarea is empty again, the list
    // re-reads backend truth (metadata only).
    draftValue = '';
    if (valueArea) valueArea.value = '';
    closeEditor();
    if (stateEl) stateEl.textContent = '';
    await loadAll();
    return true;
  } catch {
    showErr(t('secrets.error.io', { code: 'io' }));
    return false;
  } finally {
    setBusy(false);
  }
}

// ── Delete (unified glass confirm, daemons.js:454-456 precedent) ─────────
/**
 * Two-step delete: the glass confirm carries the name (and the referenced-by
 * warning when refs exist); the server enforces its own `confirm=<name>`
 * parameter on top (plan card 问7 double insurance).
 * @param {string} name
 */
function requestDelete(name) {
  const row = secrets.find((s) => s.name === name);
  const refs = row ? row.refs : [];
  const msg = refs.length
    ? t('secrets.deleteConfirmRefs', { name, refs: refs.join(', ') })
    : t('secrets.deleteConfirm', { name });
  if (typeof window.__showConfirm !== 'function') return; // never delete without a confirm
  window.__showConfirm(t('secrets.deleteTitle'), msg, async () => {
    setBusy(true);
    try {
      const resp = await api(`/api/secrets/${encodeURIComponent(name)}?confirm=${encodeURIComponent(name)}`, {
        method: 'DELETE',
      });
      if (!resp.ok) {
        window.__showToast?.(errorText(resp.status), 'error');
        await loadAll(); // 409 referenced re-reads the fresh refs list
        return;
      }
      await loadAll();
    } catch {
      window.__showToast?.(t('secrets.error.io', { code: 'io' }), 'error');
    } finally {
      setBusy(false);
    }
  });
}

// ── Open / close / focus ─────────────────────────────────────────────────
export function openSecretsPanel() {
  const ov = overlay();
  if (!ov) return;
  lastFocusedBeforeOpen = /** @type {HTMLElement|null} */ (document.activeElement);
  ov.classList.add('on');
  applyStaticText();
  const first = modal()?.querySelector('button, input, select');
  if (first && 'focus' in first) /** @type {HTMLElement} */ (first).focus();
  loadAll();
}

export function closeSecretsPanel() {
  const ov = overlay();
  if (!ov) return;
  closeEditor(); // the form never outlives the panel; the draft burns here
  ov.classList.remove('on');
  // Focus back to the settings entry row button (or wherever we came from).
  const btn = document.getElementById('btn-open-secrets');
  if (btn) btn.focus();
  else lastFocusedBeforeOpen?.focus?.();
}

/** The dialog has no dimming backdrop (iron rule 1) — clicking the empty
 *  overlay area is the click-outside affordance. */
function onOverlayClick(ev) {
  if (ev.target === overlay()) closeSecretsPanel();
}

/** Esc closes the editor first, then the dialog; Tab must not escape the
 *  open dialog (two-stage contract, same shape as socialPanel.js). */
function onKeydown(ev) {
  const ov = overlay();
  if (!ov || !ov.classList.contains('on')) return;
  if (ev.key === 'Escape') {
    ev.preventDefault();
    const form = formEl();
    if (form && !form.hidden) { closeEditor(); return; }
    closeSecretsPanel();
    return;
  }
  if (ev.key !== 'Tab') return;
  const m = modal();
  if (!m) return;
  const focusables = /** @type {HTMLElement[]} */ ([
    ...m.querySelectorAll('button, input, select, textarea, [href], [tabindex]:not([tabindex="-1"])'),
  ]).filter((el) => !el.hasAttribute('disabled') && el.offsetParent !== null);
  if (!focusables.length) return;
  const first = focusables[0];
  const last = focusables[focusables.length - 1];
  const active = /** @type {HTMLElement|null} */ (document.activeElement);
  if (ev.shiftKey && (active === first || !m.contains(active))) {
    ev.preventDefault();
    last.focus();
  } else if (!ev.shiftKey && active === last) {
    ev.preventDefault();
    first.focus();
  }
}

function onDocumentClick(ev) {
  const target = /** @type {HTMLElement|null} */ (ev.target instanceof Element ? ev.target : null);
  if (!target) return;
  if (target.closest('#btn-open-secrets')) { openSecretsPanel(); return; }
  if (target.closest('#secrets-modal-close')) { closeSecretsPanel(); return; }
  if (target.closest('#secrets-modal-add')) { openEditor(''); return; }
  if (target.closest('[data-add]')) { openEditor(''); return; }
  if (target.closest('#secrets-form-close')) { closeEditor(); return; }
  if (target.closest('#secrets-form-cancel')) { closeEditor(); return; }
  if (target.closest('#secrets-form-save')) { void saveEditor(); return; }
  const replace = target.closest('[data-replace]');
  if (replace instanceof HTMLElement && replace.dataset.replace) {
    openEditor(replace.dataset.replace);
    return;
  }
  const del = target.closest('[data-delete]');
  if (del instanceof HTMLElement && del.dataset.delete) requestDelete(del.dataset.delete);
}

/** The textarea feeds the draft only (value discipline); the name field is
 *  never stored anywhere but the form until save assembles the request. */
function onInput(ev) {
  const el = ev.target instanceof HTMLTextAreaElement ? ev.target : null;
  if (!el || el.id !== 'secrets-value-input') return;
  draftValue = el.value;
}

/** Boot wiring. Idempotent; document-level (survives settings re-renders —
 *  the entry button is rebuilt by every renderSettings call). */
export function initSecretsPanel() {
  if (initialized) return;
  initialized = true;
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onKeydown);
  document.addEventListener('input', onInput);
  overlay()?.addEventListener('click', onOverlayClick);
  window.addEventListener('locale-changed', () => {
    applyStaticText();
    renderList();
  });
  applyStaticText();
}

// The dialog shell is built by index.html (text-free, like #social-modal);
// boot only creates the icons inside it.
if (typeof document !== 'undefined') {
  const boot = () => createIconsIn(document.getElementById('secrets-modal') || document.body);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
}
