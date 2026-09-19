// socialPanel.js — Social interface card panel: render + state machine + focus.
//
// Upstream design (read-only, do not re-derive): `socremote-design`
// (n-5c95367d), implemented face A/B/D/E only —
//   · A  entry: `#social-btn` directly below `#contacts-btn` (smartphone icon),
//        gated the same way the contacts anchor is (button hidden WITH it).
//   · B  modal + one card per SOCIAL_CHANNELS entry + the remote-access section
//        (`#social-section-remote`, always present, never a channel card).
//   · D  "fill and save directly": a pasted credential goes into the POST body
//        ONCE; the backend writes the secret file and the config keeps only a
//        path. The panel never echoes a stored credential back (it renders
//        "written · not echoed" instead).
//   · E  phase-1 terminal state = "configured · not linked" for every card;
//        `connected` is reachable only by flipping the definition layer flag.
//
// 🔴 FACES DELIBERATELY NOT IMPLEMENTED (dispatcher ruling 2026-09-19 21:04:34
//    = face C suspended; the author-face items O1–O5 in the design's §9.3 are
//    NOT decided yet): the remote-access switch semantics, the QR code, the
//    read-only address-export route, the clipboard fallback and the mobile
//    adaptation range. This file therefore renders NO link, NO QR image and NO
//    guessed address. `[data-remote-url]` / `[data-copy-link]` do not exist —
//    that is the intended, frozen state of this node, not an oversight.
//
// Style: css/social.css (new file; sapphire.css is off-limits for this batch).
// Switch component: shared js/toggle.js (`nb-toggle`), never a private copy.

import { t } from './i18n.js';
import { escapeHtml, createIconsIn } from './utils.js';
import { toggleHTML, bindToggle, setToggleState } from './toggle.js';
import { getAuthToken } from './neblink.js';
import {
  SOCIAL_CHANNELS, channelById, storedKey, channelStatus, channelProblem, fieldValues,
} from './socialChannels.js';

// ── State ────────────────────────────────────────────────────────────────
/** Last config truth read from the backend, keyed by channel id.
 *  @type {Record<string, {enabled?: boolean, fields?: Record<string, string>}>} */
let configByChannel = {};
/** Last probe triples (exists / modeOk / readable), keyed by channel id.
 *  @type {Record<string, Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>>} */
let probeByChannel = {};
/** Focusable-element trap state. */
let lastFocusedBeforeOpen = /** @type {HTMLElement|null} */ (null);
let initialized = false;
/** Fields the user has typed since the last save, keyed by `<channelId>.<fieldKey>`.
 *  Secret values live ONLY here and ONLY until the POST body is built — they are
 *  never written into the DOM as a value and never read back from the backend.
 *  @type {Record<string, string>} */
let draftValues = {};

// ── Backend adapter (same token pattern as plugins.js / agentManager.js) ──
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

/** GET /api/social/channels — never returns secret CONTENT, only paths. */
async function fetchConfig() {
  const resp = await api('/api/social/channels');
  if (!resp.ok) throw new Error(`GET /api/social/channels ${resp.status}`);
  const data = await resp.json();
  return data && data.channels ? data.channels : {};
}

/** GET /api/social/probe?channel=<id> — mechanical triple, no content. */
async function fetchProbe(id) {
  const resp = await api(`/api/social/probe?channel=${encodeURIComponent(id)}`);
  if (!resp.ok) throw new Error(`GET /api/social/probe ${resp.status}`);
  const data = await resp.json();
  return data && data.secrets ? data.secrets : {};
}

// ── Selection helpers ────────────────────────────────────────────────────
/** @returns {HTMLElement|null} */
function overlay() { return document.getElementById('social-overlay'); }
/** @returns {HTMLElement|null} */
function modal() { return document.getElementById('social-modal'); }
/** @returns {HTMLElement|null} */
function cardList() { return document.querySelector('#social-section-channels .social-card-list'); }

/**
 * @param {string} channelId
 * @returns {HTMLElement|null}
 */
function cardEl(channelId) {
  return /** @type {HTMLElement|null} */ (
    document.querySelector(`.social-card[data-channel="${channelId}"]`)
  );
}

/** Yes/no reading of one probe bit, translated (avoids a bare `true` in the UI). */
function bit(v) { return v === true ? t('social.value.yes') : t('social.value.no'); }

/**
 * The panel's readiness state, exposed the standard way: `aria-busy` on the
 * channels section while a backend read/write is in flight. It is a real UI
 * state (assistive tech announces it), not a test hook — and it is what makes
 * "the panel has converged on backend truth" observable from outside, so no
 * caller has to guess with a timer.
 * @param {boolean} on
 */
function setBusy(on) {
  const el = document.getElementById('social-section-channels');
  if (el) el.setAttribute('aria-busy', on ? 'true' : 'false');
}

// ── Rendering ────────────────────────────────────────────────────────────
/**
 * The mechanical probe line: exists / modeOk / readable (arch §5, unchanged by
 * the "fill and save directly" reshape — only its trigger moved to "after save").
 * @param {Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>|undefined} probe
 * @returns {string} translated summary, or '' when nothing was probed yet
 */
function probeSummary(probe) {
  if (!probe) return '';
  const keys = Object.keys(probe);
  if (!keys.length) return '';
  const p = probe[keys[0]];
  if (!p) return '';
  // 🔴 literal field names are kept for machine readability of the reading itself
  return t('social.probe.summary', {
    exists: bit(p.exists), modeOk: bit(p.modeOk), readable: bit(p.readable),
  });
}

/**
 * Per-card hint: the credential path when a probe contradicts the config
 * (W9 requires the path text), the offending pattern otherwise. Reads the SAME
 * contradiction the pill was derived from (`channelProblem`) — a hint can never
 * describe a different problem than the state it sits next to.
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @param {string} status
 * @returns {string}
 */
function cardHint(ch, status) {
  if (status === 'configInvalid') {
    const p = channelProblem(ch, configByChannel[ch.id], probeByChannel[ch.id]);
    if (p && p.kind === 'ref') {
      const probe = (probeByChannel[ch.id] || {})[p.field.key] || {};
      return probe.exists === false
        ? t('social.hint.missingRef', { path: p.path })
        : t('social.hint.modeBad', { path: p.path });
    }
    const values = fieldValues(ch, configByChannel[ch.id]);
    const bad = ch.fields.filter((f) => {
      const v = String(values[storedKey(f)] ?? '').trim();
      return !!v && !!f.pattern && !new RegExp(f.pattern).test(v);
    });
    return t('social.hint.pattern', { field: bad.map((f) => t(f.i18n)).join(', ') });
  }
  return probeSummary(probeByChannel[ch.id]);
}

/**
 * One card. Head carries icon + name + `[data-status]` + the shared switch
 * (W4 requires all four inside `.social-card-head`).
 * @param {any} ch channel definition (shape: SOCIAL_CHANNELS entries)
 * @returns {string}
 */
function cardHTML(ch) {
  const cfg = configByChannel[ch.id];
  const status = channelStatus(ch, cfg, probeByChannel[ch.id]);
  const hint = cardHint(ch, status);
  const fields = ch.fields.map((f) => fieldHTML(ch, f)).join('');
  const on = !!(cfg && cfg.enabled);
  return `<div class="social-card" data-channel="${escapeHtml(ch.id)}">
      <div class="social-card-head">
        <i data-lucide="${escapeHtml(ch.icon)}" class="social-card-icon"></i>
        <span class="social-card-name">${escapeHtml(t(ch.nameKey))}</span>
        <span class="social-status-pill plugins-state-pill" data-status="${status}">${escapeHtml(t(`social.status.${status}`))}</span>
        ${toggleHTML({
          on,
          label: t(ch.nameKey),
          attrs: `data-toggle-channel="${escapeHtml(ch.id)}"`,
        })}
      </div>
      <div class="social-card-desc">${escapeHtml(t(ch.descKey))}</div>
      <div class="social-card-fields">${fields}</div>
      <div class="social-card-hint"${hint ? '' : ' hidden'}>${escapeHtml(hint)}</div>
      <div class="social-card-actions">
        <button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-primary" data-save="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.save'))}</button>
        <button type="button" class="glass-control cfg-btn cfg-btn-sm" data-recheck="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.recheck'))}</button>
        <span class="social-save-state" data-save-state="${escapeHtml(ch.id)}" role="status" aria-live="polite"></span>
      </div>
    </div>`;
}

/**
 * One field row. Secret fields are write-only: `value` is never rendered from
 * the stored config — the stored value is a PATH and is shown as a state badge.
 * @param {any} ch channel definition
 * @param {any} f field definition
 * @returns {string}
 */
function fieldHTML(ch, f) {
  const stored = fieldValues(ch, configByChannel[ch.id])[storedKey(f)];
  const label = escapeHtml(t(f.i18n));
  const id = `social-${ch.id}-${f.key}`;
  if (f.kind === 'secret') {
    const state = stored ? t('social.secret.stored') : t('social.secret.empty');
    return `<label class="social-field" for="${id}">
        <span class="social-field-label">${label}</span>
        <input id="${id}" class="social-input" type="password" autocomplete="off"
               data-field="${escapeHtml(f.key)}" data-secret="1"
               placeholder="${escapeHtml(t('social.secret.placeholder'))}" value="">
        <span class="social-secret-state">${escapeHtml(state)}</span>
      </label>`;
  }
  if (f.kind === 'select') {
    const opts = (ch.regions || [])
      .map((r) => {
        const sel = (stored || f.default) === r.key ? ' selected' : '';
        return `<option value="${escapeHtml(r.key)}"${sel}>${escapeHtml(t(`social.${ch.id}.region.${r.key}`))}</option>`;
      })
      .join('');
    return `<label class="social-field" for="${id}">
        <span class="social-field-label">${label}</span>
        <select id="${id}" class="social-input social-select" data-field="${escapeHtml(f.key)}">${opts}</select>
      </label>`;
  }
  return `<label class="social-field" for="${id}">
      <span class="social-field-label">${label}</span>
      <input id="${id}" class="social-input" type="text" autocomplete="off"
             data-field="${escapeHtml(f.key)}"
             placeholder="${escapeHtml(f.placeholder || '')}"
             value="${escapeHtml(stored || '')}">
    </label>`;
}

/** Full modal render (channels + the remote-access section captions). */
function renderAll() {
  const list = cardList();
  if (!list) return;
  list.innerHTML = SOCIAL_CHANNELS.map(cardHTML).join('');
  createIconsIn(list);
  bindToggle(list, onToggleChange);
  applyStaticText();
}

/** In-place convergence for one card (no full re-render ⇒ typing is not lost). */
function refreshCard(channelId) {
  const ch = channelById(channelId);
  const card = cardEl(channelId);
  if (!ch || !card) return;
  const status = channelStatus(ch, configByChannel[ch.id], probeByChannel[ch.id]);
  const pill = card.querySelector('.social-status-pill');
  if (pill) {
    pill.setAttribute('data-status', status);
    pill.textContent = t(`social.status.${status}`);
  }
  const hintEl = card.querySelector('.social-card-hint');
  const hint = cardHint(ch, status);
  if (hintEl) {
    hintEl.textContent = hint;
    if (hint) hintEl.removeAttribute('hidden'); else hintEl.setAttribute('hidden', '');
  }
  const toggle = card.querySelector('.nb-toggle');
  setToggleState(toggle, !!(configByChannel[ch.id] && configByChannel[ch.id].enabled));
  card.querySelectorAll('.social-field').forEach((label) => {
    const input = label.querySelector('[data-field]');
    if (!input) return;
    const key = input.getAttribute('data-field') || '';
    const field = ch.fields.find((f) => f.key === key);
    if (!field || field.kind !== 'secret') return;
    const badge = label.querySelector('.social-secret-state');
    if (!badge) return;
    const stored = fieldValues(ch, configByChannel[ch.id])[storedKey(field)];
    badge.textContent = stored ? t('social.secret.stored') : t('social.secret.empty');
  });
}

/** Captions living in index.html (the shell is text-free; i18n.js is not ours
 *  to edit, so the panel translates its own subtree). */
function applyStaticText() {
  const map = [
    ['social-modal-title', 'social.title'],
    ['social-modal-close', 'social.action.close', 'title'],
    ['social-remote-title', 'social.remote.title'],
    ['social-remote-suspended', 'social.remote.suspended'],
    ['social-remote-safety-key', 'social.remote.safetyKey'],
    ['social-remote-safety-plain', 'social.remote.safetyPlain'],
    ['social-channels-title', 'social.channels.title'],
    ['social-btn', 'social.btn.title', 'title'],
  ];
  for (const [id, key, attr] of map) {
    const el = document.getElementById(id);
    if (!el) continue;
    if (attr) el.setAttribute(attr, t(key)); else el.textContent = t(key);
  }
}

// ── Load / save ──────────────────────────────────────────────────────────
/** Read config + probe for every channel; failures degrade to "not configured"
 *  readings instead of blanking the panel. */
async function loadAll() {
  try {
    configByChannel = await fetchConfig();
  } catch {
    configByChannel = {};
  }
  for (const ch of SOCIAL_CHANNELS) {
    try {
      probeByChannel[ch.id] = await fetchProbe(ch.id);
    } catch {
      probeByChannel[ch.id] = {};
    }
  }
  renderAll();
  setBusy(false);
}

/**
 * @param {HTMLButtonElement} el
 * @param {boolean} on
 */
async function onToggleChange(el, on) {
  const id = el.dataset.toggleChannel || '';
  const ch = channelById(id);
  if (!ch) return;
  const ok = await saveChannel(id, on);
  if (!ok) setToggleState(el, !on); // rollback to backend truth
}

/**
 * The single write path (§D.3): field values + any typed secret go into the
 * request body ONCE; the reply's probe triple drives the pill/hint.
 * @param {string} channelId
 * @param {boolean} [enabledOverride]
 * @returns {Promise<boolean>}
 */
async function saveChannel(channelId, enabledOverride) {
  const ch = channelById(channelId);
  const card = cardEl(channelId);
  if (!ch || !card) return false;
  const toggle = card.querySelector('.nb-toggle');
  const enabled = enabledOverride !== undefined
    ? enabledOverride
    : !!(toggle && toggle.classList.contains('on'));
  /** @type {Record<string, string>} */
  const fields = {};
  for (const f of ch.fields) {
    if (f.kind === 'secret') {
      const typed = draftValues[`${channelId}.${f.key}`];
      if (typed) fields[f.key] = typed; // plaintext: this request body, once
    } else {
      const input = card.querySelector(`[data-field="${f.key}"]`);
      const v = input && 'value' in input ? String(input.value) : '';
      fields[f.key] = v || f.default || '';
    }
  }
  setSaveState(channelId, t('social.action.saving'));
  setBusy(true);
  try {
    const resp = await api(`/api/social/channels/${encodeURIComponent(channelId)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled, fields }),
    });
    if (!resp.ok) {
      setSaveState(channelId, t('social.action.saveFailed', { code: resp.status }));
      return false;
    }
    const data = await resp.json();
    if (data && data.probe) probeByChannel[channelId] = data.probe;
    // The credential plaintext is gone from memory at this point; the stored
    // value the backend kept is a PATH, so a plain re-read cannot echo it back.
    for (const f of ch.fields) delete draftValues[`${channelId}.${f.key}`];
    configByChannel = await fetchConfig();            // step 2 → step 3: probe re-read
    try { probeByChannel[channelId] = await fetchProbe(channelId); } catch { /* keep POST probe */ }
    renderAll();
    setSaveState(channelId, t('social.action.saved'));
    return true;
  } catch {
    setSaveState(channelId, t('social.action.saveFailed', { code: 'io' }));
    return false;
  } finally {
    setBusy(false);
  }
}

/**
 * @param {string} channelId
 * @param {string} text
 */
function setSaveState(channelId, text) {
  const el = document.querySelector(`[data-save-state="${channelId}"]`);
  if (el) el.textContent = text;
}

/** Re-read the mechanical probe for one channel (manual "check again"). */
async function recheck(channelId) {
  try {
    probeByChannel[channelId] = await fetchProbe(channelId);
  } catch {
    probeByChannel[channelId] = {};
  }
  refreshCard(channelId);
}

// ── Open / close / focus ─────────────────────────────────────────────────
export function openSocialPanel() {
  const ov = overlay();
  if (!ov) return;
  lastFocusedBeforeOpen = /** @type {HTMLElement|null} */ (document.activeElement);
  ov.classList.add('on');
  renderAll();
  setBusy(true); // set BEFORE the load so an observer can never read the previous round's value
  const first = modal()?.querySelector('button, input, select');
  if (first && 'focus' in first) /** @type {HTMLElement} */ (first).focus();
  loadAll();
}

export function closeSocialPanel() {
  const ov = overlay();
  if (!ov) return;
  ov.classList.remove('on');
  // Esc/close always returns focus to the entry button (W13).
  const btn = document.getElementById('social-btn');
  if (btn) btn.focus();
  else lastFocusedBeforeOpen?.focus?.();
}

/** The modal has no dimming backdrop (design §B/W3) — clicking the empty
 *  overlay area is the click-outside affordance. */
function onOverlayClick(ev) {
  if (ev.target === overlay()) closeSocialPanel();
}

/** Tab must not escape the open dialog (W13). */
function onKeydown(ev) {
  const ov = overlay();
  if (!ov || !ov.classList.contains('on')) return;
  if (ev.key === 'Escape') {
    ev.preventDefault();
    closeSocialPanel();
    return;
  }
  if (ev.key !== 'Tab') return;
  const m = modal();
  if (!m) return;
  const focusables = /** @type {HTMLElement[]} */ ([
    ...m.querySelectorAll('button, input, select, [href], [tabindex]:not([tabindex="-1"])'),
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
  if (target.closest('#social-btn')) { openSocialPanel(); return; }
  if (target.closest('#social-modal-close')) { closeSocialPanel(); return; }
  const save = target.closest('[data-save]');
  if (save instanceof HTMLElement && save.dataset.save) { saveChannel(save.dataset.save); return; }
  const re = target.closest('[data-recheck]');
  if (re instanceof HTMLElement && re.dataset.recheck) { recheck(re.dataset.recheck); }
}

function onInput(ev) {
  const el = /** @type {HTMLInputElement|null} */ (ev.target instanceof HTMLInputElement ? ev.target : null);
  if (!el || !el.dataset.field) return;
  const card = el.closest('.social-card');
  if (!(card instanceof HTMLElement)) return;
  const id = card.dataset.channel || '';
  if (el.dataset.secret === '1') draftValues[`${id}.${el.dataset.field}`] = el.value;
}

/** Boot wiring. Idempotent; document-level so the entry button survives the
 *  friends gate detaching/re-attaching it (activityBar.js). */
export function initSocialPanel() {
  if (initialized) return;
  initialized = true;
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onKeydown);
  document.addEventListener('input', onInput);
  overlay()?.addEventListener('click', onOverlayClick);
  window.addEventListener('locale-changed', () => { applyStaticText(); renderAll(); });
  applyStaticText();
}
