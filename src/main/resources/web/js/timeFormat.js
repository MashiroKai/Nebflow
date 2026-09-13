// timeFormat.js — the single source of truth for the 12h/24h clock preference.
//
// One shared preference (`nebflow_time_format`, legacy spelling
// 'nebflow:timeFormat' is normalized by branding.js) drives EVERY clock face:
// the main chat footer, the device dialog and the friends dialog. Toggling on
// any one face re-formats all of them (one preference, one storage key).
//
// ── The [data-ts-text] contract ─────────────────────────────────────────────
// `data-ts-text` marks a node whose ENTIRE text is `formatHm(<epoch millis>)`.
// It is the ONLY thing toggleTimeFormat re-formats, because the refresh is an
// in-place `textContent` write: a node carrying child elements (a card row, a
// search-result row, …) would be flattened. Never put it on a mixed-content
// node — declare the clock text as its own element instead.
//
// `data-ts` is a DIFFERENT contract: it is the jump anchor consumed by
// chatSearchFloat.js (`querySelector('[data-ts="…"]')`), and it legitimately
// sits on whole containers (flowMapArchive `.fm-entry`, chatSearch
// `.search-result`). It is deliberately NOT a refresh target — that over-reach
// was the P1 defect this module fixes.
//
// Only clock text participates: date/relative spellings ("昨天" / "M/D" /
// "2 min ago") are never produced here, so a toggle can never rewrite them.
//
// Import discipline: branding.js + i18n.js only (no back edge → the static
// import graph stays acyclic, scripts/check-circular.mjs).

import { key } from './branding.js';
import { t } from './i18n.js';

/** localStorage key holding the shared clock preference ('12h' | '24h'). */
export const TIME_FORMAT_KEY = key('time_format');

/** Window event fired after a toggle (consumers re-render derived lists). */
export const TIME_FORMAT_CHANGED = 'nf:time-format-changed';

const DEFAULT_FORMAT = '24h';

// Module-level snapshot of the preference — the ONE copy (a second snapshot
// elsewhere would drift). Chat / device / friends faces all read through it.
let _timeFormat = localStorage.getItem(TIME_FORMAT_KEY) || DEFAULT_FORMAT;

/** Current clock preference: '12h' | '24h'. */
export function getTimeFormat() {
  return _timeFormat;
}

/** Format epoch millis as HH:MM (24h) or h:MM AM/PM (12h), per preference. */
export function formatHm(ms) {
  const d = new Date(ms);
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (_timeFormat === '12h') {
    let h = d.getHours();
    const ampm = h >= 12 ? 'PM' : 'AM';
    h = h % 12 || 12;
    return h + ':' + mm + ' ' + ampm;
  }
  return String(d.getHours()).padStart(2, '0') + ':' + mm;
}

/**
 * Re-format every declared clock text node under `root` in place.
 * @param {ParentNode} [root] defaults to the whole document
 */
export function refreshAllTimestamps(root = document) {
  root.querySelectorAll('[data-ts-text]').forEach(el => {
    const ts = parseInt(el.getAttribute('data-ts-text'), 10);
    if (ts) el.textContent = formatHm(ts);
  });
}

/** Flip the shared preference, persist it, re-format every clock text node. */
export function toggleTimeFormat() {
  _timeFormat = _timeFormat === '24h' ? '12h' : '24h';
  localStorage.setItem(TIME_FORMAT_KEY, _timeFormat);
  refreshAllTimestamps();
  window.dispatchEvent(new CustomEvent(TIME_FORMAT_CHANGED, { detail: { format: _timeFormat } }));
}

/**
 * Make a clock text node a toggle hot zone: pointer affordance (.time-toggle),
 * i18n tooltip, role=button + tabindex + aria-label, and mouse AND keyboard
 * activation (Enter/Space). Idempotent — a re-bound node keeps one handler.
 *
 * The node must be a pure clock text node ([data-ts-text]); binding and the
 * refresh contract are declared separately on purpose.
 * @param {HTMLElement|null|undefined} el
 * @returns {HTMLElement|null|undefined} the same element
 */
export function bindTimeToggle(el) {
  if (!el) return el;
  if (el.dataset.timeToggleBound === '1') return el;
  el.dataset.timeToggleBound = '1';
  el.classList.add('time-toggle');
  el.setAttribute('role', 'button');
  el.setAttribute('tabindex', '0');
  applyToggleLabel(el);
  el.addEventListener('click', () => { toggleTimeFormat(); });
  el.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
      e.preventDefault(); // Space must not scroll the panel
      toggleTimeFormat();
    }
  });
  return el;
}

/** Tooltip/aria-label in the active locale (a locale switch relabels in place). */
function applyToggleLabel(el) {
  const label = t('time.toggleFormat');
  el.title = label;
  el.setAttribute('aria-label', label);
}

window.addEventListener('locale-changed', () => {
  document.querySelectorAll('.time-toggle').forEach(applyToggleLabel);
});
