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
// Only pure-clock text participates by default: date/relative spellings
// ("昨天" / "M/D" / "2 min ago") are never produced by formatHm, so a toggle
// can never rewrite them. The ONE exception is the day-layered chat footer
// label below — nodes carrying `data-ts-text-format="day"` re-format through
// formatFooterTime instead (their whole text is still derived from the same
// epoch value, so the in-place write stays safe).
//
// Import discipline: branding.js + i18n.js only (no back edge → the static
// import graph stays acyclic, scripts/check-circular.mjs).

import { key } from './branding.js';
import { t, getLocale } from './i18n.js';

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

// ── Day-layered footer label (footer-time batch 2026-10-06) ─────────────────
// The chat message footer stops being a bare clock for older messages: today
// stays a pure clock (formatHm — the 12h/24h preference still governs it) and
// everything older gains a date layer. Buckets, decided on LOCAL calendar days
// (local timezone, local Y/M/D — never UTC, never "24h ago"):
//   today             → HH:MM                        (unchanged)
//   yesterday         → 昨天 HH:MM       / Yesterday HH:MM
//   this local week   → 星期X HH:MM      / Weekday HH:MM   (yesterday excluded)
//   earlier this year → M月D日 HH:MM     / MMM D HH:MM
//   cross-year        → YYYY年M月D日 HH:MM / MMM D, YYYY HH:MM
// "This week" = local calendar week starting Monday 00:00 — the repo's
// week-bucketing precedent (popMediaIndex.js `weekStart`, the media grid's
// 本周 bucket) and the task's default 口径. The bucket ladder is sequential:
// this-week wins over cross-year, so a message from the Dec 28–31 tail seen
// on Jan 1–4 still reads 星期X (same local week), not a dated label.

/** Same LOCAL calendar day (Y/M/D equality — timezone-safe by construction). */
function isSameLocalDay(a, b) {
  return a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate();
}

/** Monday 00:00 LOCAL of the week `d` belongs to (popMediaIndex weekStart rule). */
function localWeekStart(d) {
  const day = (d.getDay() + 6) % 7;   // 0 = Monday … 6 = Sunday
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - day);
}

// getDay(): 0=Sun … 6=Sat → the 'time.weekday.*' locale keys (zh 一二三四五六日,
// en full names — no Intl fallback, the strings live in the locales).
const WEEKDAY_KEY = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];

/**
 * Layered chat footer time label — pure `timestamp → string` (no DOM), shared
 * by every footer render site: the live chat.js badge builders AND history
 * replay, which funnels through the same builders, so long-session scroll-back
 * loading needs no special branch. Today's bucket is exactly formatHm, so a
 * today-message footer is byte-identical to the pre-batch clock.
 * @param {number} ms epoch millis (always rendered in the local timezone)
 * @param {Date} [now] injectable clock — evidence harness / tests only
 * @returns {string}
 */
export function formatFooterTime(ms, now = new Date()) {
  const d = new Date(ms);
  if (isSameLocalDay(d, now)) return formatHm(ms);
  const time = formatHm(ms);
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (isSameLocalDay(d, yesterday)) return t('time.footerYesterday', { time });
  if (d >= localWeekStart(now)) {
    return t('time.footerWeekday', { weekday: t('time.weekday.' + WEEKDAY_KEY[d.getDay()]), time });
  }
  // Older: M月D日 ( MMM D ) — cross-year prepends the year (YYYY年M月D日 / MMM D, YYYY).
  // The date payload rides the runtime's locale data (the chatSearch calendar
  // precedent: calendar names/particles via Intl, no per-locale month tables);
  // the composition key keeps "date ␠ time" one localizable template.
  const opts = /** @type {Intl.DateTimeFormatOptions} */ ({ month: 'short', day: 'numeric' });
  if (d.getFullYear() !== now.getFullYear()) opts.year = 'numeric';
  return t('time.footerDate', { date: d.toLocaleDateString(getLocale(), opts), time });
}

/**
 * Re-format every declared clock text node under `root` in place.
 *
 * Nodes flagged `data-ts-text-format="day"` are day-layered footer labels and
 * re-format through formatFooterTime; every other [data-ts-text] node keeps
 * the original bare-clock contract (formatHm) — the device / friends /
 * dropbox faces are untouched by the footer batch.
 * @param {ParentNode} [root] defaults to the whole document
 */
export function refreshAllTimestamps(root = document) {
  root.querySelectorAll('[data-ts-text]').forEach(el => {
    const ts = parseInt(el.getAttribute('data-ts-text'), 10);
    if (!ts) return;
    el.textContent = el.getAttribute('data-ts-text-format') === 'day'
      ? formatFooterTime(ts)
      : formatHm(ts);
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
  // Day-layered footer labels now carry locale words (昨天/Yesterday, 星期X /
  // Weekday, 月/日 particles) — re-format every clock node in place so a
  // mid-session language switch doesn't leave stale-language footers behind.
  // Pure-clock nodes re-format to the identical string: a no-op for them.
  refreshAllTimestamps();
});
