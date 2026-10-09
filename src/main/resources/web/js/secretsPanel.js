// secretsPanel.js — Unified secrets management dialog (standalone modal).
//
// secrets-frontend batch, 2026-09-27. Author ruling 2026-09-27 19:20:
// NOT an embedded section inside #settings-modal — the list lives in this
// DEDICATED dialog of its own (capacity not bounded by the settings modal);
// the settings page carries only a one-row entry button (sidebar.js
// renderSettings → #btn-open-secrets, i18n key `settings.secrets`).
//
// 行内化 (author ruling 2026-10-09 07:42: 「我想直接就是把密钥的值放在条目
// 这一页啊，有个小眼睛去点。平时就是点点点，直接就可以查看修改，不需要弹窗」):
// the editor sub-dialog (#secrets-form) AND the floating「+」(#secrets-fab)
// are RETIRED. Every operation happens inline in the list rows.
//
// 行内化二轮 · 原位展开编辑 (author feedback 2026-10-09 08:32: 「可以对Ui布局
// 再进行优化一下 不用都放同一行，保持极简干净的布局，另外修改的时候也不要
// 影响布局，就在原位进行修改。…就是原位进行修改」— iPhone 提醒事项式)：
// the row is now a two-face unit:
//   · Rest face = ONE minimal line: name (left) + permission badge (only
//     when permissive — the security signal stays on the list face). NO
//     buttons, NO field, NO metadata — the eye, the editor and delete do not
//     exist in the resting DOM.
//   · Click the row → it expands IN PLACE downward into its edit detail
//     (value field + eye + meta + 保存/删除); rows below shift naturally —
//     no popup, no global reflow. One row open at a time.
//
// 行内化三轮 · 点点微调 (author feedback 2026-10-09 23:06: 「密钥面板这个
// 点点的长度跟这个密钥的长度不一致。然后没有点击展开的时候，可以不显示点点」):
//   · Rest face drops the dots span entirely (收起不显示点点) — the only
//     value face left is the expanded detail's field placeholder.
//   · That placeholder is LENGTH-FAITHFUL: one dot per stored byte, from the
//     list's `size` metadata (maskPlaceholder below) — no value is read to
//     paint it; byte-exact for ASCII secrets, and over-wide dots clip inside
//     the one-row field (css .secrets-value::placeholder white-space: pre).
//   · 保存 / Enter commits through the existing PUT; click-outside commits
//     a CHANGED field (Reminders dismiss-to-save) and collapses; Esc
//     collapses WITHOUT committing; failure keeps the detail open with the
//     text for another try; success flashes 「已保存 ✓」on the resting face.
//   · Eye (masked+empty) → ONE authenticated GET /api/secrets/{name}/value
//     → plaintext fills the field, mask off. Eye again → mask ON and the
//     FETCHED plaintext burns back to the placeholder face (清空回占位，
//     下次揭示再拉). Collapsing the row removes the whole detail (a stronger
//     burn). A locally-typed draft is never burned by the eye — masking
//     local text is a presentation-only toggle.
//   · Create: the dashed「＋ 新建密钥」tail row (always present, list end)
//     expands in place into a name+value draft; Enter / 保存 submits through
//     the existing POST; Esc discards.
//   · Delete: unchanged (in-detail control, hover danger tint, glass confirm,
//     DELETE ?confirm=<name>).
//
// 🔴 SECURITY DISCIPLINE (plan card §二, readback ruling 2026-10-09 07:03,
//   unchanged in spirit by 行内化):
//   · The list carries METADATA ONLY (name/size/mtime/mode/refs) and the
//     module strips any stray `value` key off the wire rows. The ONE
//     value-bearing call this module makes is GET /api/secrets/{name}/value
//     (authenticated, single-name) — issued ONLY by an eye click on a
//     masked-and-empty field, never while the list renders. One fetch per
//     reveal: a click while the fetch is in flight CANCELS it (token bump),
//     and the per-row token (revealSeq) drops stale replies so a slow
//     response can never land in a re-masked field, a re-rendered row or a
//     closed panel.
//   · A fetched plaintext exists in exactly two places — the row field
//     (revealed) and the baselines Map (change-detection baseline, entry
//     deleted the moment the field re-masks / rests / the panel closes).
//     Re-masking clears the field; committing clears it too. Never
//     localStorage, never an attribute, never rendered into the list.
//   · Names go through escapeHtml when interpolated into templates; every
//     dynamic text is set via textContent. innerHTML carries literals and
//     escaped names only — never a value. Logs/errors carry names and
//     STATUS codes only.
//
// Precedents: socialPanel.js (module shape, glass dialog family, api()),
// daemons.js:454-456 (window.__showConfirm), sidebar.js renderSettings
// (entry row), sidebar.js jev save-state (inline「已保存 ✓」feedback,
// statusIcon('circle-check', 11, 'st-ok')), css/secrets.css (this batch;
// sapphire.css untouched). All user-visible copy goes through t()
// (i18n.js); en + zh-CN locale files ship the same key set in the same
// batch.

import { t } from './i18n.js';
import { escapeHtml, createIconsIn, statusIcon } from './utils.js';
import { getAuthToken } from './neblink.js';

// ── Eye toggle glyphs ────────────────────────────────────────────────────
// Inline SVG (stroke: currentColor, so the button's colour token drives the
// ink in both themes). Path data is the vendored lucide set the rest of the
// app uses (vendor/lucide.min.js, v0.454.0) — `data-lucide` is not usable
// here because createIconsIn REPLACES the element, which would defeat the
// two-state swap; sidebar.js draws its eye pair the same way (:69-70).
const EYE_SVG = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/></svg>';
const EYE_OFF_SVG = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49"/><path d="M14.084 14.158a3 3 0 0 1-4.242-4.242"/><path d="M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143"/><path d="m2 2 20 20"/></svg>';

// ── State ────────────────────────────────────────────────────────────────
/** Last list truth read from the backend (metadata only — no value field
 *  exists on the wire shape; anything unexpected is dropped below).
 *  @type {{name: string, size: number, mtime: number|null, mode: string, refs: string[]}[]} */
let secrets = [];
/** Monotonic reveal token source. Each eye-driven fetch takes the next
 *  token and stamps it on the row; only the STILL-CURRENT token for that
 *  row may apply the reply (a slow response must never land in a re-masked
 *  field, a re-rendered row or a closed panel — seq guard, 防串行). */
let revealSeq = 0;
/** Change-detection baselines: name → the stored value CURRENTLY DISPLAYED
 *  in that row's field (set by the fetch reply, deleted on re-mask / rest /
 *  commit / panel close). An undefined baseline simply means "unknown" —
 *  any non-empty local text then counts as a change (overwrite intent).
 *  The map holds plaintext ONLY while that plaintext is on screen.
 *  @type {Map<string, string>} */
const baselines = new Map();
/** Whether the tail row is expanded into the create draft. */
let draftOpen = false;
/** One draft POST at a time. */
let draftBusy = false;
/** The row currently expanded into its edit detail ('' = none). One at a
 *  time — expanding one row commits-and-collapses the previously open one. */
let openName = '';
/** Row to re-expand after the next renderList: a commit-triggered re-read
 *  must not eat the row the user just clicked. */
let pendingOpen = '';
/** Row to flash「已保存 ✓」on after the next renderList — the flash lives on
 *  the row's RESTING face (the detail is gone by then). */
let savedFlashName = '';
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
 * GET /api/secrets/{name}/value — the ONE value-bearing call this module makes
 * (readback ruling 2026-10-09, carried into 行内化): issued only by an eye
 * click on a masked-and-empty row field; the list face stays metadata-only.
 * The authenticated response carries exactly {name, value}. Errors reject
 * with a `.status` so the caller can reuse the shared error-copy mapping.
 * @param {string} name
 * @returns {Promise<string>}
 */
async function fetchSecretValue(name) {
  const resp = await api(`/api/secrets/${encodeURIComponent(name)}/value`);
  if (!resp.ok) {
    // `.status` rides the error so the caller can reuse the shared error-copy
    // mapping (Object.assign keeps the checkJs face of Error clean).
    throw Object.assign(new Error(`GET /api/secrets/${name}/value ${resp.status}`), { status: resp.status });
  }
  const data = await resp.json().catch(() => null);
  return data && typeof data.value === 'string' ? data.value : '';
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
function addRowEl() { return listEl()?.querySelector('.secrets-add-row') || null; }

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

/**
 * The expanded edit field's mask placeholder: ONE dot per stored BYTE, taken
 * from the list row's `size` metadata (三轮 2026-10-09 23:06:「点点的长度跟
 * 这个密钥的长度不一致」— the fixed 6-dot face could not describe the stored
 * length; this one does). A placeholder NEVER reads the value: byte-size is
 * the length proxy the list already carries (SecretsStore writes the value
 * byte-for-byte, so bytes == characters for ASCII secrets; multi-byte values
 * may paint slightly more dots). The glyph reuses the i18n dotsPlaceholder
 * face so the dot is the same character the panel has always shown; a
 * degenerate/unknown size falls back to that same fixed face.
 * @param {number} size byte length from the list metadata
 * @returns {string}
 */
function maskPlaceholder(size) {
  const face = t('secrets.dotsPlaceholder') || '••••••';
  const glyph = face.charAt(0) || '•';
  return glyph.repeat(Number.isFinite(size) && size > 0 ? size : face.length);
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

// ── Row helpers ──────────────────────────────────────────────────────────
/** @param {HTMLElement} row @returns {HTMLTextAreaElement|null} */
function rowField(row) {
  const f = row.querySelector('[data-edit]');
  return f instanceof HTMLTextAreaElement ? f : null;
}
/** @param {HTMLElement} row @returns {HTMLElement|null} */
function rowEye(row) {
  const e = row.querySelector('[data-eye]');
  return e instanceof HTMLElement ? e : null;
}

/**
 * Paint the mask state onto one eye button. This is the ONLY place an eye's
 * face is written, so the glyph, the label and aria-pressed can never
 * disagree. The label names the ACTION the click performs (reveal while
 * masked, mask while revealed) — the standard disclosure-toggle reading.
 * @param {HTMLElement|null} eye
 * @param {boolean} masked
 */
function eyeFace(eye, masked) {
  if (!eye) return;
  eye.innerHTML = masked ? EYE_SVG : EYE_OFF_SVG;
  const key = masked ? 'secrets.showValue' : 'secrets.hideValue';
  eye.setAttribute('aria-label', t(key));
  eye.title = t(key);
  eye.setAttribute('aria-pressed', masked ? 'false' : 'true');
}

/** The row element currently expanded into its edit detail, or null. */
function openRowEl() {
  if (!openName) return null;
  const row = listEl()?.querySelector(`.secrets-row[data-name="${CSS.escape(openName)}"]`);
  return row instanceof HTMLElement ? row : null;
}

/**
 * Burn one row's detail back to its resting face (dots). Everything transient
 * dies here: the displayed plaintext lives only inside the detail subtree, so
 * removing it is the strongest possible 回遮清空 — no field, no value, no
 * placeholder-but-dots anywhere.
 * @param {HTMLElement} row
 * @param {string} name
 */
function closeRowFace(row, name) {
  baselines.delete(name);
  row.querySelector('.secrets-row-detail')?.remove();
  row.removeAttribute('data-open');
  row.querySelector('[data-row-head]')?.setAttribute('aria-expanded', 'false');
}

/**
 * Expand a row in place: build its edit detail beneath the resting head (the
 * rows below shift naturally — no popup, no global reflow). Exactly ONE row
 * is open at a time: expanding over an open row commits-and-collapses that
 * one first and re-opens this one after the re-read settles (pendingOpen).
 * @param {HTMLElement} row
 */
function expandRow(row) {
  const name = row.dataset.name || '';
  if (!name || openName === name || row.querySelector('.secrets-row-detail')) return;
  const s = secrets.find((x) => x.name === name);
  if (!s) return;
  if (openName) {
    const prev = openRowEl();
    if (prev) {
      pendingOpen = name;
      void collapseRow(prev, { commit: true });
      return; // renderList re-expands us after the re-read
    }
  }
  if (draftOpen) discardDraft(false);
  const head = row.querySelector('[data-row-head]');
  row.setAttribute('data-open', '');
  head?.setAttribute('aria-expanded', 'true');
  head?.insertAdjacentHTML('afterend', detailHTML(s));
  openName = name;
  rowField(row)?.focus(); // click row → ready to type
}

/**
 * Collapse a row back to its resting face.
 *  · commit:false — Esc semantics: discard the typed draft, collapse now.
 *  · commit otherwise — the completion path (保存 / Enter / click-outside):
 *    an empty or unchanged field just collapses (no request); a changed one
 *    PUTs through the existing save path — success re-reads the list (the
 *    row lands closed,「已保存 ✓」flashes on its resting face), failure
 *    toasts and KEEPS the detail open with the text for another try.
 * Either way an in-flight reveal for this row is killed first: the token
 * bump turns a late reply into a stale drop (迟到丢弃), and the revealing
 * flag is cleared so a future re-expansion starts honest.
 * @param {HTMLElement} row
 * @param {{commit?: boolean}} [opts]
 * @returns {Promise<void>}
 */
async function collapseRow(row, opts = {}) {
  const name = row.dataset.name || '';
  if (openName === name) openName = '';
  row.dataset.revealToken = String(++revealSeq);
  delete row.dataset.revealing;
  if (opts.commit === false) { closeRowFace(row, name); return; }
  const field = rowField(row);
  const val = field ? field.value : '';
  const baseline = baselines.get(name);
  if (!field || !val || (baseline !== undefined && val === baseline)) {
    closeRowFace(row, name);
    return;
  }
  if (row.dataset.committing) return; // one PUT in flight per row
  row.dataset.committing = '1';
  setBusy(true);
  try {
    const resp = await api(`/api/secrets/${encodeURIComponent(name)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value: val }), // the plaintext's single appearance
    });
    if (!resp.ok) {
      window.__showToast?.(errorText(resp.status), 'error');
      return; // keep the detail + typed text for another try
    }
    baselines.delete(name);
    savedFlashName = name;
    await loadAll(); // fresh metadata; the row lands closed, flash paints
  } catch {
    window.__showToast?.(t('secrets.error.io', { code: 'io' }), 'error');
  } finally {
    delete row.dataset.committing;
    setBusy(false);
  }
}

// ── Rendering ────────────────────────────────────────────────────────────
/**
 * One row's RESTING face (行内化二轮 2026-10-09; 三轮 2026-10-09 23:06 drops
 * the dots). ONE minimal line: name (left) + permission badge (only when
 * permissive — the security signal stays visible without expanding) + the
 * transient saved-flash slot. NO field, NO eye, NO delete, NO metadata, and
 * since 三轮 NO value dots — the resting row carries no value face at all.
 * The head is a real button: the whole row is the tap target (Reminders row
 * affordance). Those controls are built lazily by detailHTML on expansion,
 * so the resting DOM carries neither an edit control nor a button. METADATA
 * ONLY ever crosses the wire; no value face exists in either face of this
 * template.
 * @param {{name: string, size: number, mtime: number|null, mode: string, refs: string[]}} s
 * @returns {string}
 */
function rowHTML(s) {
  const permissive = s.mode !== '0600';
  const modeBadge = permissive
    // ui-status-redesign（S13 / 盘点 #36）：pill 字转中性，词前 octagon-alert 红
    // 图标（权限档形状语义）；红底红边图案由 secrets.css 保留。
    ? `<span class="secrets-mode-badge" data-mode="permissive">${statusIcon('octagon-alert', 10, 'st-err')}${escapeHtml(t('secrets.state.permissive'))}</span>`
    : `<span class="secrets-mode-badge" data-mode="ok">${escapeHtml(t('secrets.state.ok'))}</span>`;
  const name = escapeHtml(s.name);
  return `<div class="secrets-row" data-name="${name}">
      <button type="button" class="secrets-row-head" data-row-head="${name}" aria-expanded="false" aria-controls="secrets-detail-${name}" title="${escapeHtml(t('secrets.rowHint'))}">
        <span class="secrets-row-name">${name}</span>
        ${modeBadge}
        <span class="secrets-row-status" role="status" aria-live="polite"></span>
      </button>
    </div>`;
}

/**
 * One row's EXPANDED detail (原位展开编辑): the value field (empty at rest,
 * placeholder dots, masked) + eye + the iconized metadata line + the
 * 保存/删除 actions, rendered INSIDE the row — rows below shift naturally.
 * The eye on a masked-and-empty field is the ONE value fetch; the meta
 * items (size/mtime/refs) moved here from the old resting face (二轮极简).
 * @param {{name: string, size: number, mtime: number|null, mode: string, refs: string[]}} s
 * @returns {string}
 */
function detailHTML(s) {
  const metaItems = [
    `<span class="m">${statusIcon('box', 10)}${escapeHtml(formatSize(s.size))}</span>`,
    `<span class="m">${statusIcon('clock', 10)}${escapeHtml(formatMtime(s.mtime))}</span>`,
    s.refs.length
      ? `<span class="m">${statusIcon('link', 10)}${escapeHtml(t('secrets.referencedBy', { refs: s.refs.join(', ') }))}</span>`
      : '',
  ];
  const name = escapeHtml(s.name);
  // The inline edit field: EMPTY when the detail opens. The placeholder is
  // the LENGTH-FAITHFUL mask (maskPlaceholder: one dot per stored byte from
  // the size metadata — expansion never fetches). title carries the
  // overwrite warning (a11y tooltip; the eye's aria labels the reveal
  // action). data-edit/data-eye carry the NAME for event routing — never a
  // value.
  const field = `<textarea class="secrets-input secrets-value masked" rows="1" data-edit="${name}" placeholder="${escapeHtml(maskPlaceholder(s.size))}" title="${escapeHtml(t('secrets.overwriteHint'))}" aria-label="${escapeHtml(t('secrets.value'))}: ${name}" autocomplete="off" spellcheck="false"></textarea>`;
  const eye = `<button type="button" class="secrets-value-eye" data-eye="${name}" aria-label="${escapeHtml(t('secrets.showValue'))}" aria-pressed="false" title="${escapeHtml(t('secrets.showValue'))}">${EYE_SVG}</button>`;
  return `<div class="secrets-row-detail" id="secrets-detail-${name}" data-detail="${name}">
      <div class="secrets-row-value">${field}${eye}</div>
      <div class="secrets-row-meta">${metaItems.join('')}</div>
      <div class="secrets-row-actions">
        <button type="button" class="glass-control cfg-btn cfg-btn-sm" data-delete="${name}">${escapeHtml(t('secrets.delete'))}</button>
        <button type="button" class="cfg-btn cfg-btn-primary cfg-btn-sm" data-save="${name}">${escapeHtml(t('secrets.save'))}</button>
      </div>
    </div>`;
}

/** Collapsed tail-row face: the dashed「＋ 新建密钥」trigger (cfg-btn-add
 *  dashed language; the FAB + empty-state button retired with the modal).
 *  The ＋ glyph is part of the i18n value (both locales), not the template. */
function addTriggerHTML() {
  return `<button type="button" class="secrets-add-trigger" data-add>${escapeHtml(t('secrets.tailAdd'))}</button>`;
}

/** Expanded tail-row face: the in-place create draft (name + value fields,
 *  inline error line, 保存 confirm; Enter submits, Esc discards). The draft
 *  eye is a presentation-only mask toggle on the TYPED value — nothing is
 *  stored yet, so it never fetches. */
function draftHTML() {
  return `<div class="secrets-add-form">
      <input type="text" class="secrets-input" data-draft-name placeholder="${escapeHtml(t('secrets.nameHint'))}" aria-label="${escapeHtml(t('secrets.name'))}" autocomplete="off" spellcheck="false">
      <div class="secrets-row-value">
        <textarea class="secrets-input secrets-value masked" rows="1" data-draft-value placeholder="${escapeHtml(t('secrets.valuePlaceholder'))}" aria-label="${escapeHtml(t('secrets.value'))}" autocomplete="off" spellcheck="false"></textarea>
        <button type="button" class="secrets-value-eye" data-draft-eye aria-label="${escapeHtml(t('secrets.showValue'))}" aria-pressed="false" title="${escapeHtml(t('secrets.showValue'))}">${EYE_SVG}</button>
      </div>
      <div class="secrets-add-actions">
        <span class="secrets-draft-error" role="alert" hidden></span>
        <button type="button" class="cfg-btn cfg-btn-primary" data-draft-save>${escapeHtml(t('secrets.save'))}</button>
      </div>
    </div>`;
}

/** Full list render from backend truth (metadata only). The tail add row is
 *  ALWAYS present (empty list → guidance copy + the same row, plan card
 *  问1). A render is a truth reset: rows come back at the resting face
 *  (openName cleared), then the two transient wishes replay — pendingOpen
 *  (the row the user clicked while a commit's re-read was in flight) and
 *  savedFlashName (the「已保存 ✓」flash on the resting face). */
function renderList() {
  const list = listEl();
  if (!list) return;
  const empty = secrets.length
    ? ''
    : `<div class="cfg-empty">${escapeHtml(t('secrets.empty'))}</div>`;
  list.innerHTML = empty + secrets.map(rowHTML).join('') + `<div class="secrets-add-row"${draftOpen ? ' data-open' : ''}>${draftOpen ? draftHTML() : addTriggerHTML()}</div>`;
  openName = ''; // fresh DOM — nothing is expanded yet
  if (pendingOpen) {
    const row = list.querySelector(`.secrets-row[data-name="${CSS.escape(pendingOpen)}"]`);
    pendingOpen = '';
    if (row instanceof HTMLElement) expandRow(row);
  }
  if (savedFlashName) {
    paintRowSaved(savedFlashName);
    savedFlashName = '';
  }
}

/** Captions living in index.html (the shell is text-free; the panel
 *  translates its own subtree — socialPanel.js applyStaticText precedent).
 *  行内化: only the shell header remains static; every row/draft caption is
 *  rendered fresh by rowHTML/draftHTML on each renderList. */
function applyStaticText() {
  const map = [
    ['secrets-modal-title', 'secrets.title'],
    ['secrets-modal-close', 'secrets.close', 'title'],
  ];
  for (const [id, key, attr] of map) {
    const el = document.getElementById(id);
    if (!el) continue;
    if (attr) el.setAttribute(attr, t(key)); else el.textContent = t(key);
  }
}

// ── Load ─────────────────────────────────────────────────────────────────
/** Read the list; failures degrade to the empty state with an error line —
 *  the panel never fakes data it did not read. */
async function loadAll() {
  setBusy(true);
  try {
    secrets = await fetchSecrets();
    renderList();
    // secrets-ui-redesign (2026-10-08): nudge the settings-page entry row's
    // count pill — sidebar.js listens for `secrets-changed`, busts its 60s
    // cache and re-reads this same metadata-only list.
    window.dispatchEvent(new CustomEvent('secrets-changed'));
  } catch {
    secrets = [];
    openName = '';
    pendingOpen = '';
    savedFlashName = '';
    const list = listEl();
    if (list) {
      list.innerHTML = `<div class="cfg-empty">${escapeHtml(t('secrets.error.io', { code: 'io' }))}</div><div class="secrets-add-row">${addTriggerHTML()}</div>`;
    }
  } finally {
    setBusy(false);
  }
}

// ── Reveal (the eye) ─────────────────────────────────────────────────────
/**
 * The row eye. Three readings by field state:
 *  · revealed → mask ON; FETCHED plaintext burns back to the placeholder
 *    face (清空回占位，下次揭示再拉); a locally-typed draft is KEPT (masking
 *    it is presentation-only — it never left the user's hands).
 *  · masked + local text → unmask in place (no fetch, no overwrite).
 *  · masked + empty → THE value fetch: exactly one authenticated GET, reply
 *    applied only if this row's token is still current, the field is still
 *    attached, the panel is still open and the field is still masked+empty.
 * @param {HTMLElement} row
 */
function revealRow(row) {
  const field = rowField(row);
  const eye = rowEye(row);
  if (!field || !eye) return;
  const name = row.dataset.name || '';
  if (!field.classList.contains('masked')) {
    field.classList.add('masked');
    if (field.dataset.held === 'fetched') { field.value = ''; delete field.dataset.held; }
    baselines.delete(name);
    eyeFace(eye, true);
    return;
  }
  if (field.value) { // local typed draft → presentation-layer unmask only
    field.classList.remove('masked');
    eyeFace(eye, false);
    return;
  }
  if (row.dataset.revealing) {
    // A fetch is in flight and the field is still masked+empty: this click
    // CANCELS the pending reveal — the token bump makes the late reply a
    // stale drop (fast-toggle seq guard, 防串行).
    row.dataset.revealToken = String(++revealSeq);
    delete row.dataset.revealing;
    return;
  }
  const token = String(++revealSeq);
  row.dataset.revealing = '1';
  row.dataset.revealToken = token;
  fetchSecretValue(name).then((value) => {
    delete row.dataset.revealing;
    if (row.dataset.revealToken !== token) return;   // a newer toggle owns the row
    if (!field.isConnected) return;                  // row re-rendered away
    const ov = overlay();
    if (!ov || !ov.classList.contains('on')) return; // panel closed meanwhile
    if (!field.classList.contains('masked') || field.value) return; // typed meanwhile
    baselines.set(name, value);
    field.dataset.held = 'fetched';
    field.value = value;
    field.classList.remove('masked');
    eyeFace(eye, false);
  }).catch((e) => {
    delete row.dataset.revealing;
    if (row.dataset.revealToken !== token || !field.isConnected) return;
    const status = e && typeof e.status === 'number' ? e.status : 0;
    window.__showToast?.(status ? errorText(status) : t('secrets.error.io', { code: 'io' }), 'error');
  });
}

// ── Inline edit (commit / revert) ────────────────────────────────────────
/** Client-side name shape check (plan card 问5: ^[a-z0-9][a-z0-9._-]{0,63}$;
 *  hard reds: no leading dot, no .tmp suffix). The server re-validates —
 *  this is face guidance, not the authority.
 * @param {string} name
 * @returns {boolean}
 */
function validName(name) {
  return /^[a-z0-9][a-z0-9._-]{0,63}$/.test(name) && !name.endsWith('.tmp');
}

/** Flash「已保存 ✓」into a row's status slot (jev card save-state precedent:
 *  role=status live region, ~1.6s decay, cleared only if still connected).
 *  The slot lives on the row's RESTING head — the flash lands after the
 *  commit's re-read has already collapsed the detail. */
function paintRowSaved(name) {
  const row = listEl()?.querySelector(`.secrets-row[data-name="${CSS.escape(name)}"]`);
  const st = row?.querySelector('.secrets-row-status');
  if (!(st instanceof HTMLElement)) return;
  st.innerHTML = statusIcon('circle-check', 11, 'st-ok') + escapeHtml(t('secrets.saved'));
  setTimeout(() => { if (st.isConnected) st.innerHTML = ''; }, 1600);
}

// ── Create draft (tail row) ──────────────────────────────────────────────
/** Expand the tail row into the draft (in place —焦点自动落名称字段). The
 *  draft and an open row are mutually exclusive: expanding the draft
 *  commits-and-collapses any open row first (one expansion at a time). */
function openDraftInPlace(focus = true) {
  const row = addRowEl();
  if (!row) return;
  const open = openRowEl();
  if (open) void collapseRow(open, { commit: true });
  draftOpen = true;
  row.setAttribute('data-open', '');
  row.innerHTML = draftHTML();
  const nameInput = row.querySelector('[data-draft-name]');
  if (focus && nameInput instanceof HTMLElement) nameInput.focus();
}

/** Collapse the draft back to the dashed trigger; the draft burns here. */
function discardDraft(refocus = true) {
  draftOpen = false;
  const row = addRowEl();
  if (!row) return;
  row.removeAttribute('data-open');
  row.innerHTML = addTriggerHTML();
  if (refocus) {
    const trigger = row.querySelector('[data-add]');
    if (trigger instanceof HTMLElement) trigger.focus();
  }
}

function showDraftError(msg) {
  const err = addRowEl()?.querySelector('.secrets-draft-error');
  if (err instanceof HTMLElement) {
    err.innerHTML = statusIcon('circle-alert', 11, 'st-err') + escapeHtml(msg);
    err.hidden = false;
  }
}

function clearDraftError() {
  const err = addRowEl()?.querySelector('.secrets-draft-error');
  if (err instanceof HTMLElement) { err.hidden = true; err.textContent = ''; }
}

/**
 * Submit the draft through the existing POST path (create mode of the old
 * editor: client-side name shape check, 409 → conflict copy, value into the
 * body once). Success collapses the draft and re-reads the list — the new
 * row lands from backend truth.
 * @returns {Promise<void>}
 */
async function submitDraft() {
  if (draftBusy) return;
  const row = addRowEl();
  if (!row) return;
  const nameEl = row.querySelector('[data-draft-name]');
  const valueEl = row.querySelector('[data-draft-value]');
  const name = nameEl instanceof HTMLInputElement ? nameEl.value.trim() : '';
  const value = valueEl instanceof HTMLTextAreaElement ? valueEl.value : '';
  clearDraftError();
  if (!name || !validName(name)) {
    showDraftError(t('secrets.invalidName'));
    if (nameEl instanceof HTMLElement) nameEl.focus();
    return;
  }
  if (!value) {
    showDraftError(t('secrets.error.emptyValue'));
    if (valueEl instanceof HTMLElement) valueEl.focus();
    return;
  }
  draftBusy = true;
  setBusy(true);
  try {
    const resp = await api(`/api/secrets/${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }), // the plaintext's single appearance
    });
    if (!resp.ok) { showDraftError(errorText(resp.status)); return; }
    draftOpen = false;
    await loadAll(); // the new row lands in place
  } catch {
    showDraftError(t('secrets.error.io', { code: 'io' }));
  } finally {
    draftBusy = false;
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
      baselines.delete(name);
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
  // Everything transient burns here: displayed plaintext (the baselines are
  // deleted entry-by-entry as details close, but a hard clear cannot hurt —
  // no value ever outlives the panel), the draft and the expansion state
  // (the DOM rebuilds from backend truth on the next open).
  baselines.clear();
  draftOpen = false;
  openName = '';
  pendingOpen = '';
  savedFlashName = '';
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

/**
 * Escape shield — CAPTURE phase (family precedent: main.js's global Esc
 * handler shields #modal-overlay the same way). Three-stage contract, now
 * expansion-aware (行内化二轮): draft open → discard the draft; a row is
 * expanded → collapse it WITHOUT committing (Esc = 放弃并收起, the typed
 * draft burns with the detail); otherwise close the dialog. Capture on
 * document runs before every bubble-phase listener, so handling + stopping
 * the event here gives this contract exclusive ownership of Esc while this
 * dialog is open. Scoped strictly to "this overlay is open". When the
 * unified confirm dialog (#modal-overlay, opened by the delete flow) is up,
 * the global capture handler owns that Esc — this shield stands down.
 */
function onKeydownCapture(ev) {
  if (ev.key !== 'Escape') return;
  const ov = overlay();
  if (!ov || !ov.classList.contains('on')) return;
  const confirmOverlay = document.getElementById('modal-overlay');
  if (confirmOverlay && confirmOverlay.classList.contains('on')) return;
  ev.preventDefault();
  ev.stopPropagation();
  if (draftOpen) { discardDraft(); return; }
  const row = openRowEl();
  if (row) { void collapseRow(row, { commit: false }); return; }
  closeSecretsPanel();
}

/**
 * Bubble-phase keys inside the dialog: Tab = focus trap; Enter (no shift)
 * completes — a row's edit field commits-and-collapses its row (same path
 * as the 保存 button), a draft field submits the draft (both preventDefault
 * so the textarea never swallows the newline). Enter on a resting head
 * button is the button's native click → expandRow.
 */
function onKeydown(ev) {
  const ov = overlay();
  if (!ov || !ov.classList.contains('on')) return;
  if (ev.key === 'Enter' && !ev.shiftKey) {
    const target = ev.target instanceof Element ? ev.target : null;
    if (!target) return;
    if (target.matches('[data-edit]')) {
      ev.preventDefault();
      const row = target.closest('.secrets-row');
      if (row instanceof HTMLElement) void collapseRow(row, { commit: true });
      return;
    }
    if (target.matches('[data-draft-name],[data-draft-value]')) {
      ev.preventDefault();
      void submitDraft();
      return;
    }
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
  // Resting head (行内化二轮): the whole row is the tap target. Click a
  // resting row → expand it in place; click the OPEN row's head again →
  // commit-and-collapse (the dismiss-to-save reading, symmetric toggle).
  const head = target.closest('[data-row-head]');
  if (head instanceof HTMLElement) {
    const row = head.closest('.secrets-row');
    if (row instanceof HTMLElement) {
      if (openName === row.dataset.name) void collapseRow(row, { commit: true });
      else expandRow(row);
    }
    return;
  }
  // Create entry (行内化 2026-10-09 07:42): the dashed tail row — the ONLY
  // create face (FAB + empty-state button retired with the editor modal).
  if (target.closest('[data-add]')) { openDraftInPlace(); return; }
  // Row eye (the value fetch / mask toggle) — lives in the expanded detail;
  // the draft's own eye ([data-draft-eye]) is presentation-only below.
  const eye = target.closest('[data-eye]');
  if (eye instanceof HTMLElement && eye.dataset.eye) {
    const row = eye.closest('.secrets-row');
    if (row instanceof HTMLElement) revealRow(row);
    return;
  }
  if (target.closest('[data-draft-eye]')) {
    const wrap = target.closest('.secrets-add-row');
    const field = wrap?.querySelector('[data-draft-value]');
    const draftEye = wrap?.querySelector('[data-draft-eye]');
    if (field instanceof HTMLTextAreaElement) {
      const masked = !field.classList.contains('masked');
      field.classList.toggle('masked', masked);
      eyeFace(draftEye instanceof HTMLElement ? draftEye : null, masked);
    }
    return;
  }
  if (target.closest('[data-draft-save]')) { void submitDraft(); return; }
  // Detail actions: 保存 = the explicit commit-and-collapse; 删除 = the
  // unchanged glass-confirm flow. Both sit INSIDE the open row, so neither
  // trips the outside-collapse below.
  const save = target.closest('[data-save]');
  if (save instanceof HTMLElement) {
    const row = save.closest('.secrets-row');
    if (row instanceof HTMLElement) void collapseRow(row, { commit: true });
    return;
  }
  const del = target.closest('[data-delete]');
  if (del instanceof HTMLElement && del.dataset.delete) requestDelete(del.dataset.delete);
  // Click-outside completion (点行外): a click anywhere outside the open row
  // finishes it — a changed field PUTs (Reminders dismiss-to-save), then the
  // row collapses. Standing down for clicks inside the glass confirm overlay
  // (#modal-overlay — its 取消/删除 buttons are outside the row; the delete
  // flow owns those clicks, and a cancel must NOT collapse the row mid-flow).
  const open = openRowEl();
  if (open && target.closest('.secrets-row') !== open && !target.closest('#modal-overlay')) {
    void collapseRow(open, { commit: true });
  }
}

/** Row fields feed only their own value (value discipline); any local input
 *  marks the field's content as a LOCAL draft — the eye then knows a
 *  re-mask must keep it, and a fetched value can never be confused with
 *  user text. Fetch replies set the value programmatically → no input
 *  event → the flag stays honest. (Blur is deliberately NOT a commit in
 *  the 二轮 model — completion is owned by 保存 / Enter / the click-outside
 *  collapse, so a stray focusout can never double-fire a PUT.) */
function onInput(ev) {
  const el = ev.target instanceof HTMLTextAreaElement ? ev.target : null;
  if (!el || !el.matches('[data-edit]')) return;
  el.dataset.held = 'local';
}

/** Boot wiring. Idempotent; document-level (survives settings re-renders —
 *  the entry button is rebuilt by every renderSettings call). */
export function initSecretsPanel() {
  if (initialized) return;
  initialized = true;
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onKeydownCapture, true);
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
