// input/composer.js — 统一文字输入行为包（2026-11-02 作者令「文字输入框的逻辑
// 做成一个包，参考主 agent 会话窗口的输入模式」）。
//
// Why this module exists: before this batch the two composer surfaces each held
// their own copy of the input behavior — the primary chat input in `input.js`
// (IME guard + Enter/Shift+Enter cascade inline) and the canvas ask dock in
// `askDock.js` (a bare `keydown → if Enter submit()`, which sent the message
// on the Enter that CONFIRMED a Chinese IME candidate and had no interrupt
// state at all). From now on the behavior lives HERE and both surfaces
// consume it; neither surface re-implements a leg (两处不许各写一遍逻辑).
// The primary chat surface is the behavioral baseline: this extraction is
// move-only for it (同一语句、同一顺序，见 input.js 落地腿批注).
//
// Three legs (三条腿):
//   ① 键盘 / IME 组合态 —— composition bookkeeping + hoisted short-circuit.
//      The composition SEMANTICS stay single-sourced in `imeGuard.js`
//      (批次 ⑤ 收归的唯一定义点; the static sentinel
//      `scripts/check-ime-guard.mjs` keeps it that way). This module only
//      ASSEMBLES them into the keydown contract — it never reads raw
//      composition flags itself.
//   ② 发送判定 —— bare Enter (outside composition) commits via
//      `commitEnter`; Shift+Enter falls through to the browser default
//      (newline on a textarea); every composed key belongs to the IME and is
//      returned untouched.
//   ③ 中断状态机 —— the busy↔idle button chrome swap (send hidden / stop
//      shown, mirroring the primary window's #send-btn/#stop-btn pair) plus
//      the single interrupt-frame emission point.
//
// Consumers: primary chat input (`input.js` keyboard leg + `chat.js`
// setBusy/clearBusy chrome leg) and canvas ask dock (`askDock.js`, both legs).
//
// Gate constraints honored: zero static cycle (imports only imeGuard.js — a
// zero-import leaf — and ws.js, neither of which reaches back here);
// `scripts/check-ime-guard.mjs` clean (no raw composition flags, no direct
// composition binding outside imeGuard.js); build-web orphan guard satisfied
// (imported by input.js / chat.js / askDock.js, all entry-reachable).

import { bindImeGuard, isImeComposing, commitEnter } from '../imeGuard.js';
import { sendWs } from '../ws.js';

// Re-export the composition predicates so consumers take the whole input
// contract from ONE module face (语义权威仍在 imeGuard.js——本包只转发，不复制).
export { bindImeGuard, isImeComposing, commitEnter };

/**
 * Install the shared keyboard contract on a composer element.
 *
 * The keydown decision order is FROZEN by the primary window's behavior
 * (批次 ⑤ 收归后的形态) and is identical for every consumer:
 *   1. `preamble(e)` — surface bookkeeping that must run on EVERY keydown
 *      before any decision (primary: `setActiveView(view)`).
 *   2. hoisted composition short-circuit — while the IME is composing, EVERY
 *      key belongs to the IME (Enter confirms the candidate, ↑↓ navigate
 *      candidates, Esc cancels the composition, Backspace edits the reading);
 *      none of them is preventDefaulted or acted on. This arm sits FIRST, in
 *      front of every `e.key` branch — both so a candidate-confirming Enter
 *      can never reach the send decision, and so surface cascades (slash
 *      dropdown, the mention popup) can never consume a composed key either.
 *   3. `cascade(e)` — surface-specific pre-emption, returns `true` when the
 *      key was consumed (primary: the mention popup, mode cancels, slash
 *      navigation, input-history recall; ask: none).
 *   4. send decision — `commitEnter` (bare Enter outside composition):
 *      preventDefault + `onSend(e)`. Shift+Enter and every other key fall
 *      through to the browser default (newline on a textarea).
 *
 * Binding uses the `onkeydown` slot (single consumer per element, idempotent
 * under re-init — the primary view re-runs its init per popup with fresh DOM;
 * a re-init on the same element would overwrite, never stack).
 *
 * @param {HTMLElement} input the editable element (textarea/input)
 * @param {{ preamble?: (e: Event) => void, cascade?: (e: KeyboardEvent) => boolean|void,
 *            onSend?: (e: KeyboardEvent) => void }} [opts]
 */
export function bindComposerKeys(input, opts = {}) {
  if (!input) return;
  // Element-level composition bookkeeping — the keyboard decision below reads
  // the flag through `isImeComposing`, written only by bindImeGuard (⑤ 收归：
  // 组字状态唯一写入点仍是 imeGuard.js，本包只装配).
  bindImeGuard(input);
  input.onkeydown = (e) => {
    if (opts.preamble) opts.preamble(e);
    // 组字前置短路（⑤ 收归语义，搬位不变）：组字期间每一个键都属于输入法，
    // 一律不 preventDefault、不动作，交还浏览器。
    if (isImeComposing(e, input)) return;
    if (opts.cascade && opts.cascade(/** @type {KeyboardEvent} */ (e)) === true) return;
    if (commitEnter(e, input) && opts.onSend) opts.onSend(/** @type {KeyboardEvent} */ (e));
  };
}

/**
 * Apply the busy↔idle button chrome for one composer's turn state — the
 * PRIMARY window's exact statements (chat.js setBusy/clearBusy legs, moved
 * verbatim): busy = send hidden + stop shown + status pill on; idle = the
 * reverse. Missing elements are skipped individually (popup views without a
 * status pill, the ask dock without one).
 *
 * @param {{ sendBtn?: HTMLElement|null, stopBtn?: HTMLElement|null, statusWrap?: HTMLElement|null }} chrome
 * @param {boolean} busy
 */
export function applyTurnChrome(chrome, busy) {
  if (!chrome) return;
  const { sendBtn, stopBtn, statusWrap } = chrome;
  if (sendBtn) sendBtn.style.display = busy ? 'none' : 'flex';
  if (stopBtn) stopBtn.style.display = busy ? 'flex' : 'none';
  if (statusWrap) statusWrap.classList.toggle('on', busy);
}

/**
 * Turn-toggle state machine for one composer: `busy()` arms the interrupt
 * state (stop button surfaces), `idle()` settles it. Idempotent — the state
 * is held here so repeat terminal frames / repeat clicks cannot flip the
 * chrome twice.
 *
 * @param {{ sendBtn?: HTMLElement|null, stopBtn?: HTMLElement|null, statusWrap?: HTMLElement|null }} chrome
 * @returns {{ busy: () => void, idle: () => void, isBusy: () => boolean }}
 */
export function createTurnToggle(chrome) {
  let armed = false;
  return {
    busy() { if (armed) return; armed = true; applyTurnChrome(chrome, true); },
    idle() { if (!armed) return; armed = false; applyTurnChrome(chrome, false); },
    isBusy() { return armed; },
  };
}

/**
 * The single interrupt-frame emission point. Both stop buttons (primary
 * `#stop-btn`, ask `.ask-stop`) stop a turn through HERE — same frame shape,
 * one home (主会话中断回归不破：帧形态逐字节不变).
 *
 * @param {string} sessionId
 */
export function requestTurnInterrupt(sessionId) {
  sendWs({ type: 'interrupt', sessionId });
}
