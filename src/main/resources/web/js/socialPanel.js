// socialPanel.js — Social interface card panel: render + state machine + focus.
//
// Upstream design (read-only, do not re-derive): `socremote-design`
// (n-5c95367d), implemented face A/B/D/E only —
//   · A  entry: `#social-btn` directly below `#contacts-btn` (smartphone icon),
//        gated the same way the contacts anchor is (button hidden WITH it).
//   · B  modal + one card per SOCIAL_CHANNELS entry + the remote-access section
//        (`#social-section-remote`, always present IN THE DOM — it is now
//        withdrawn from the VISIBLE face by the `hidden` attribute it carries
//        in index.html, see the hide ruling below; the mapping for its four
//        caption ids is kept so no locale key becomes an orphan).
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
//    guessed address FOR THE REMOTE-ACCESS SECTION. `[data-remote-url]` /
//    `[data-copy-link]` do not exist — that is the intended, frozen state of
//    that section, not an oversight. (feiscanbind, 2026-09-27: the FEISHU
//    scan-bind QR sub-dialog below is a different face — it is the approved
//    plan-card §5 main path and renders the platform verification URL only.)
//
// 🔴 HIDE RULING (author, 2026-09-23, Part A): the whole remote-access section
//    is HIDDEN FROM THE VISIBLE FACE, NOT DELETED — `#social-section-remote`
//    carries `hidden` in index.html plus the companion rule in css/social.css.
//    Nothing in this file changes: its DOM subtree stays built, and the four
//    `social-remote-*` entries below keep translating it, so the `social.remote.*`
//    locale keys stay consumed (no orphan keys, no key churn). The section's own
//    CSS body is kept as well. Restoring the section = dropping one attribute.
//    O1–O5 are NOT settled by the hide ruling — they stay suspended exactly as
//    described above. Hidden ≠ deleted is the whole point of this shape.
//
// 🔴 CHANNEL SEAL (socialhide batch, 2026-09-26): the wechat + telegram
//    entries are `hidden: true` at the definition layer (PHASE 3 there). The
//    single render point in [[renderAll]] maps the definition layer's visible
//    set, so a sealed card is never built, never bound and never refreshed:
//    the refresh path ([[refreshCard]]) early-returns on its missing card
//    element, and the state loops only fill data maps (no DOM, no listeners).
//    Same sealed family as the remote-access section above: hide ≠ delete.
//
// Style: css/social.css (new file; sapphire.css is off-limits for this batch).
// Switch component: shared js/toggle.js (`nb-toggle`), never a private copy.

import { t } from './i18n.js';
import { escapeHtml, createIconsIn } from './utils.js';
import { toggleHTML, bindToggle, setToggleState } from './toggle.js';
import { getAuthToken } from './neblink.js';
import {
  SOCIAL_CHANNELS, visibleChannels, channelById, storedKey, channelStatus, channelProblem, fieldValues,
} from './socialChannels.js';

// ── State ────────────────────────────────────────────────────────────────
/** Last config truth read from the backend, keyed by channel id.
 *  @type {Record<string, {enabled?: boolean, fields?: Record<string, string>}>} */
let configByChannel = {};
/** Last probe triples (exists / modeOk / readable), keyed by channel id.
 *  @type {Record<string, Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>>} */
let probeByChannel = {};
/** Live adapter registration truth per channel id, read off the probe face's
 *  `adapterRegistered` (feishubridge). A failed probe fetch leaves the last
 *  reading in place — a transient backend hiccup must not fake-unlink a card.
 *  @type {Record<string, boolean>} */
let registeredByChannel = {};
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

/** GET /api/social/probe?channel=<id> — mechanical triple, no content. Also
 *  records the live `adapterRegistered` flag (phase 2) next to the triples. */
async function fetchProbe(id) {
  const resp = await api(`/api/social/probe?channel=${encodeURIComponent(id)}`);
  if (!resp.ok) throw new Error(`GET /api/social/probe ${resp.status}`);
  const data = await resp.json();
  probeByChannel[id] = data && data.secrets ? data.secrets : {};
  registeredByChannel[id] = !!(data && data.adapterRegistered === true);
  return probeByChannel[id];
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
  const status = channelStatus(ch, cfg, probeByChannel[ch.id], registeredByChannel[ch.id]);
  const hint = cardHint(ch, status);
  const fields = ch.fields.map((f) => fieldHTML(ch, f)).join('');
  const on = !!(cfg && cfg.enabled);
  // feiscanbind: cards flagged `scanBind` in the definition layer carry the
  // "scan to create" main path (feishu only today). First in the actions row —
  // it is the primary path, the form below it is the fallback.
  const scanBtn = ch.scanBind
    ? `<button type="button" class="glass-control cfg-btn cfg-btn-sm" data-scanbind="${escapeHtml(ch.id)}">${escapeHtml(t(`social.${ch.id}.scan.action`))}</button>`
    : '';
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
        ${scanBtn}<button type="button" class="glass-control cfg-btn cfg-btn-sm social-btn-primary" data-save="${escapeHtml(ch.id)}">${escapeHtml(t('social.action.save'))}</button>
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
    // feishubridge: hidden regions are data, not choices — the sealed lark
    // entry stays in the definition layer but never renders (HIDE, NOT DELETE;
    // restoring = dropping its `hidden` flag).
    const opts = (ch.regions || [])
      .filter((r) => !r.hidden)
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
  // socialhide (PHASE 3): sealed channels (`hidden: true`) are data, not
  // cards. The visible set comes from the definition layer's ONE filter, so
  // the rendered face and `socialChannelCount()` cannot disagree; the
  // filtered order is the visible card order (§E.3 X3). Restoring a card =
  // dropping its flag in socialChannels.js — nothing changes here.
  list.innerHTML = visibleChannels().map(cardHTML).join('');
  createIconsIn(list);
  bindToggle(list, onToggleChange);
  applyStaticText();
}

/** In-place convergence for one card (no full re-render ⇒ typing is not lost). */
function refreshCard(channelId) {
  const ch = channelById(channelId);
  const card = cardEl(channelId);
  if (!ch || !card) return;
  const status = channelStatus(ch, configByChannel[ch.id], probeByChannel[ch.id], registeredByChannel[ch.id]);
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

// ── Scan-bind overlay (feiscanbind batch, 2026-09-27) ────────────────────
// The "scan to create" main path for the feishu card: a glass sub-dialog
// (same family as the panel shell — no dimming backdrop) with the QR code,
// the phone confirmation code, one status line and the manual-fill fallback.
// Exactly ONE poll timer exists ([[scanPollTimer]]); it is cleared on every
// terminal state and on close, so the overlay can never leave a loop behind.
let scanOverlayEl = /** @type {HTMLElement|null} */ (null);
let scanPollTimer = /** @type {number|null} */ (null);
let scanActiveScanId = '';
const SCAN_POLL_MS = 2000;
const SCAN_MAX_CONSECUTIVE_ERRORS = 5;

/** @returns {HTMLElement|null} */
function scanOverlay() { return document.getElementById('social-scan'); }

/**
 * Build the overlay DOM once (a child of #social-modal, so the panel's focus
 * trap keeps covering it; renderAll only rewrites the card list, never this).
 * @returns {HTMLElement}
 */
function buildScanOverlay() {
  const host = modal();
  if (!host) throw new Error('social modal missing');
  const el = document.createElement('div');
  el.id = 'social-scan';
  el.className = 'social-scan';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-labelledby', 'social-scan-title');
  el.hidden = true;
  el.innerHTML = `
      <div class="social-scan-head">
        <span id="social-scan-title" class="social-scan-title">${escapeHtml(t('social.feishu.scan.title'))}</span>
        <button type="button" id="social-scan-close" class="panel-btn" data-scan-close title="${escapeHtml(t('social.action.close'))}"><i data-lucide="x"></i></button>
      </div>
      <div class="social-scan-body">
        <div class="social-scan-qr" data-scan-qr></div>
        <div class="social-scan-code" data-scan-code hidden></div>
        <div class="social-scan-status" data-scan-status role="status" aria-live="polite"></div>
        <button type="button" class="social-scan-manual" data-scan-manual>${escapeHtml(t('social.feishu.scan.manual'))}</button>
      </div>`;
  host.appendChild(el);
  createIconsIn(el);
  scanOverlayEl = el;
  return el;
}

/**
 * Vendor QR render (web/vendor/qrcode.js, MIT): canvas, white ground + the
 * light-theme text token value as ink. The literals are deliberate — a QR
 * must scan identically in both themes, so it does not follow the palette.
 * @param {HTMLElement} container
 * @param {string} url
 */
function renderQr(container, url) {
  if (typeof qrcode !== 'function') { renderQrFallback(container, url); return; }
  const qr = qrcode(0, 'M');
  qr.addData(url);
  qr.make();
  const count = qr.getModuleCount();
  const cell = 5;
  const quiet = 4 * cell; // 4-module quiet zone
  const canvas = document.createElement('canvas');
  canvas.className = 'social-scan-canvas';
  canvas.width = count * cell + quiet * 2;
  canvas.height = count * cell + quiet * 2;
  const ctx = canvas.getContext('2d');
  if (!ctx) { renderQrFallback(container, url); return; }
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = '#1b1e26';
  for (let r = 0; r < count; r++) {
    for (let c = 0; c < count; c++) {
      if (qr.isDark(r, c)) ctx.fillRect(quiet + c * cell, quiet + r * cell, cell, cell);
    }
  }
  container.replaceChildren(canvas);
}

/** Vendor missing / canvas refused: the URL itself stays the usable path. */
function renderQrFallback(container, url) {
  const note = document.createElement('div');
  note.className = 'social-scan-fallback';
  note.textContent = t('social.feishu.scan.qrFallback');
  const link = document.createElement('a');
  link.href = url;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  link.textContent = url;
  container.replaceChildren(note, link);
}

/** @param {string} text */
function setScanStatus(text) {
  const el = scanOverlay()?.querySelector('[data-scan-status]');
  if (el) el.textContent = text;
}

/** @param {string} code empty hides the line */
function setScanUserCode(code) {
  const el = scanOverlay()?.querySelector('[data-scan-code]');
  if (!el) return;
  el.textContent = t('social.feishu.scan.userCode', { code });
  if (code) el.removeAttribute('hidden'); else el.setAttribute('hidden', '');
}

/** Open the overlay, fire `begin` and start polling. */
async function openScanOverlay() {
  const el = scanOverlay() || buildScanOverlay();
  el.hidden = false;
  setScanStatus(t('social.feishu.scan.starting'));
  setScanUserCode('');
  const qrBox = el.querySelector('[data-scan-qr]');
  if (qrBox) qrBox.replaceChildren();
  const first = el.querySelector('button');
  if (first instanceof HTMLElement) first.focus();
  let scanId = '';
  try {
    const resp = await api('/api/social/channels/feishu/scan-bind/begin', { method: 'POST' });
    if (!resp.ok) throw new Error(`begin ${resp.status}`);
    const data = await resp.json();
    scanId = typeof data.scanId === 'string' ? data.scanId : '';
  } catch {
    setScanStatus(t('social.feishu.scan.failed', { reason: 'io' }));
    return;
  }
  if (!scanId || el.hidden) return; // closed while the request was in flight
  scanActiveScanId = scanId;
  let lastQrUrl = '';
  let consecutiveErrors = 0;
  /** One poll tick: reads the status face and drives the whole sub-dialog. */
  const tick = async () => {
    if (scanActiveScanId !== scanId || el.hidden) { stopScanPoll(); return; }
    /** @type {Record<string, any>|null} */
    let s = null;
    try {
      const resp = await api(`/api/social/channels/feishu/scan-bind/status?scanId=${encodeURIComponent(scanId)}`);
      if (resp.ok) { s = await resp.json(); consecutiveErrors = 0; }
    } catch { /* transient — counted below */ }
    if (scanActiveScanId !== scanId || el.hidden) { stopScanPoll(); return; }
    if (!s) {
      consecutiveErrors++;
      if (consecutiveErrors >= SCAN_MAX_CONSECUTIVE_ERRORS) {
        stopScanPoll();
        setScanStatus(t('social.feishu.scan.failed', { reason: 'io' }));
      }
      return;
    }
    const state = typeof s.state === 'string' ? s.state : '';
    if (state === 'qr_ready' || state === 'polling') {
      const url = typeof s.qrUrl === 'string' ? s.qrUrl : '';
      if (url && url !== lastQrUrl && qrBox) { lastQrUrl = url; renderQr(/** @type {HTMLElement} */ (qrBox), url); }
      setScanUserCode(typeof s.userCode === 'string' ? s.userCode : '');
      setScanStatus(t(state === 'polling' ? 'social.feishu.scan.confirmed' : 'social.feishu.scan.waiting'));
    } else if (state === 'done') {
      stopScanPoll();
      setScanStatus(t('social.feishu.scan.done'));
      // The card behind flips by backend truth: re-read config + probe so the
      // pill/probe line/adapterRegistered come back current (plan card §5.7).
      setBusy(true);
      try { await loadAll(); } finally { setBusy(false); }
    } else if (state === 'failed') {
      stopScanPoll();
      setScanStatus(t('social.feishu.scan.failed', { reason: typeof s.error === 'string' ? s.error : 'io' }));
    }
  };
  stopScanPoll();
  scanPollTimer = /** @type {any} */ (setInterval(() => { void tick(); }, SCAN_POLL_MS));
  void tick();
}

/** Clear the ONE poll handle (idempotent). */
function stopScanPoll() {
  if (scanPollTimer !== null) { clearInterval(scanPollTimer); scanPollTimer = null; }
}

/** Hide the overlay and tear the poll down (D-2②: the closer owns the exit). */
function closeScanOverlay() {
  scanActiveScanId = '';
  stopScanPoll();
  const el = scanOverlay();
  if (el) el.hidden = true;
  // Focus back to the entry button (the card may have been re-rendered by a
  // `done` refresh — query fresh instead of holding a stale node).
  const btn = /** @type {HTMLElement|null} */ (
    document.querySelector('.social-card[data-channel="feishu"] [data-scanbind]'));
  if (btn) btn.focus();
}

/** Click path for the card button. @param {string} channelId */
function onScanBind(channelId) {
  if (channelId !== 'feishu') return; // single-channel face today (definition-layer flag gates the button)
  openScanOverlay();
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
  closeScanOverlay(); // the sub-dialog never outlives the panel (poll torn down)
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
    // Two stages (same contract as the message search dialog): with the scan
    // overlay open, Esc closes ONLY the overlay; the panel closes from the
    // panel level.
    const scan = scanOverlay();
    if (scan && !scan.hidden) { closeScanOverlay(); return; }
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
  const scan = target.closest('[data-scanbind]');
  if (scan instanceof HTMLElement && scan.dataset.scanbind) { onScanBind(scan.dataset.scanbind); return; }
  if (target.closest('[data-scan-close]')) { closeScanOverlay(); return; }
  // "改为手填": the fallback IS the card form behind the overlay — closing the
  // overlay is the whole handover. Any in-flight scan session is torn down.
  if (target.closest('[data-scan-manual]')) { closeScanOverlay(); return; }
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
