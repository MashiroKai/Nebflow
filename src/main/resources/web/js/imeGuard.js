// imeGuard.js — 中文输入法（IME）按键唯一判定点（批次 ⑤ 收归，作者裁定
// 2026-09-12；方案 §4.1 步骤级规格）。
//
// Why this file exists: before the takeover, every input surface re-implemented
// its own composition test (`e.isComposing`, `e.keyCode === 229`, some bound
// `compositionstart`/`compositionend` by hand) — 15 fork points, three of them
// (messages / contacts search / contacts verify note) had NO test at all, so a
// Chinese user pressing Enter to confirm a candidate would send the message.
// From now on the predicate lives HERE and nowhere else;
// `scripts/check-ime-guard.mjs` (CI step "IME guard") fails the build if any
// file under web/js reads the raw flags or binds composition events directly.
//
// ZERO-IMPORT LEAF on purpose: it must satisfy two build gates at once —
// `scripts/build-web.mjs` orphan guard (every web/js/**/*.js must be reachable
// from the entry graph) and `scripts/check-circular.mjs` (no static import
// cycles). A leaf with no imports can never contribute to a cycle, and it is
// pulled in by the first consumer that imports it.
//
// Semantics (frozen by the batch spec):
//   isImeComposing(e, el) = e.isComposing === true || e.keyCode === 229
//                           || el?.dataset?.imeComposing === '1'
// The three arms OR together, so the only failure direction is "pass the key
// through to the browser" — never a swallowed normal keystroke.
// `el.dataset.imeComposing` is written by `bindImeGuard` ALONE.

/** Marker property name used for the idempotence guard (no global state). */
const BOUND_FLAG = '__imeGuardBound';

/**
 * Structural element shape (deliberately NOT `HTMLElement`): call sites hand us
 * whatever their query returned — `Element` from `querySelector`, `HTMLElement`
 * from `createElement`, an event target — and all we need is `addEventListener`
 * plus the optional `dataset` bag. Keeping `addEventListener` required also
 * keeps this a non-weak type, so plain `Element` stays assignable.
 * @typedef {object} ImeTarget
 * @property {Function} addEventListener
 * @property {DOMStringMap} [dataset]
 * @property {boolean} [__imeGuardBound]
 */

/**
 * Bind the element-level composition bookkeeping. Idempotent: calling it twice
 * on the same element is a no-op (the marker is set on the element itself, so
 * re-created DOM nodes correctly get a fresh binding).
 *
 * `compositionstart` sets `el.dataset.imeComposing = '1'`; `compositionend`
 * clears it LAZILY, never synchronously (see the comment inside).
 *
 * @param {ImeTarget | null | undefined} el element that receives IME input
 */
export function bindImeGuard(el) {
  if (!el || el[BOUND_FLAG]) return;
  el[BOUND_FLAG] = true;
  el.addEventListener('compositionstart', () => {
    el.dataset.imeComposing = '1';
  });
  el.addEventListener('compositionend', () => {
    // ⑤-C: deferred clear — a synchronous clear here would let the trailing
    // events of the SAME physical key press (the Enter that confirmed the
    // candidate; some browsers dispatch a keydown after compositionend) reach
    // a commit handler with the flag already gone, i.e. confirm → send.
    setTimeout(() => { delete el.dataset.imeComposing; }, 0);
    // ⑤A2 ("组字结束后同 tick 的第二次 Enter 必须发送"): a 0ms timer is only
    // *usually* enough — Chromium's input task queue can preempt the timer
    // queue, which would swallow a genuinely new Enter. A microtask is drained
    // at the end of the current task, i.e. strictly before any later key
    // event, so it cannot swallow anything; and it still runs AFTER the
    // same-task trailing keydown above, so that case stays guarded.
    // Whichever of the two runs first clears the flag.
    queueMicrotask(() => { delete el.dataset.imeComposing; });
  });
}

/**
 * The single composition predicate. True ⇒ the key belongs to the IME: the
 * caller must NOT preventDefault and must NOT act on it.
 *
 * `el` is structural (anything with a `dataset` bag); `e` is typed as the
 * generic `Event` because several call sites register through an
 * `Element`-typed handler, where lib.dom widens the event to `Event`.
 *
 * @param {Event | null | undefined} e
 * @param {ImeTarget | null | undefined} el
 * @returns {boolean}
 */
export function isImeComposing(e, el) {
  const ev = /** @type {KeyboardEvent | null | undefined} */ (/** @type {any} */ (e));
  return ev?.isComposing === true || ev?.keyCode === 229 || el?.dataset?.imeComposing === '1';
}

/**
 * Single-point commit gate for "bare Enter commits/fires the action"
 * surfaces (send message, submit search, commit inline rename/remark …).
 *
 * Semantics: returns `true` exactly when this is an Enter WITHOUT Shift that
 * arrived outside IME composition — and in that case the default action has
 * already been prevented for the caller. Returns `false` in every other case
 * (other keys, Shift+Enter, composing) WITHOUT touching the event, so the
 * browser/IME keeps its native behavior.
 *
 * Callers therefore never restate the composition test:
 *   el.addEventListener('keydown', (e) => {
 *     if (isImeComposing(e, el)) return;          // IME owns every key
 *     if (commitEnter(e, el)) doSend();           // bare Enter, default killed
 *     else if (e.key === 'Escape') { … }
 *   });
 *
 * @param {Event | null | undefined} e
 * @param {ImeTarget | null | undefined} el
 * @returns {boolean} true ⇒ caller commits (default already prevented)
 */
export function commitEnter(e, el) {
  const ev = /** @type {KeyboardEvent | null | undefined} */ (/** @type {any} */ (e));
  if (!ev || ev.key !== 'Enter' || ev.shiftKey) return false;
  if (isImeComposing(ev, el)) return false;
  ev.preventDefault();
  return true;
}
