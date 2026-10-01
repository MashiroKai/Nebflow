// workline.js — the running turn's single-line work indicator (stream-ux §二.1.1)
//
// ONE badge slot that scrolls through the turn's live process: the tool that is
// currently running (spinner + tool name) / the thinking stream (single-line
// width reveal) → a drawn check on completion → a roll-up / roll-down switch to
// the next item, and, once the turn's process is over and the reply streams,
// the completed badge (`已完成 N 工具 · 思考 N 轮 · 点击展开`).
//
// Why this module is a LEAF (no chat.js / turnGroup.js import):
//  · The work-line is TRANSIENT chrome. turnGroup.js removes it the moment the
//    turn reaches a terminal (collapseTurn / failTurn), so the only persistent
//    top-level turn node stays `.turn-header` — the contract that
//    tests/turn-collapse-keep-text.spec.mjs `readSeq` enumerates in full (an
//    extra top-level node would read as `other:nf-workline` and break it).
//  · Its node carries NO `.row` class and never `.row.ai`: the background-agent
//    popup family pins `.row.ai` counts
//    (tests/bgagent-injected-bubbles.spec.mjs `aiRows === 1`).
//  · Pure CSS animations only — the turn-* specs pin script-driven (WAAPI)
//    animations out of the process rows.
//  · Imports i18n only ⇒ no import cycle (chat.js → workline.js → i18n.js).
//
// The engine event stream is consumed unchanged: the callers are the existing
// render paths in chat.js (appendThinkingDelta / finishThinking /
// renderToolPending / renderTool / appendAiText), i.e. the same events that
// already drove the process rows. No WS frame, no backend, no main.js
// event-entry change.

import { t } from './i18n.js';

/** Out-phase duration before the leaving item is dropped from the DOM —
 *  slightly longer than the CSS roll-out (0.35s) so the animation completes. */
const ROLL_MS = 380;
/** Width-reveal budget for the single-line thinking text (px). */
const THINK_MAX_PX = 520;

const CHECK_SVG =
  '<svg viewBox="0 0 16 16" aria-hidden="true">' +
  '<circle cx="8" cy="8" r="7"></circle>' +
  '<path d="M4.5 8.2 L7 10.7 L11.5 5.5"></path>' +
  '</svg>';

/** Error mark for a failed tool (stroke-only, same ink box as CHECK_SVG so the
 *  single-line geometry does not shift). */
const CROSS_SVG =
  '<svg viewBox="0 0 16 16" aria-hidden="true">' +
  '<path d="M4.5 4.5 L11.5 11.5 M11.5 4.5 L4.5 11.5"></path>' +
  '</svg>';

function state(view) {
  if (!view) return null;
  if (!view._nfWorkline) view._nfWorkline = { row: null, slot: null, item: null, key: '', kind: '' };
  return view._nfWorkline;
}

function ensureRow(view) {
  const wl = state(view);
  if (wl.row && wl.row.isConnected) return wl;
  const chat = view.dom && view.dom.chat;
  if (!chat) return null;
  const row = document.createElement('div');
  row.className = 'nf-workline';
  row.setAttribute('role', 'status');
  row.setAttribute('aria-live', 'polite');
  const slot = document.createElement('div');
  slot.className = 'nf-wl-slot';
  row.appendChild(slot);
  chat.appendChild(row);
  wl.row = row;
  wl.slot = slot;
  wl.item = null;
  wl.key = '';
  wl.kind = '';
  return wl;
}

/** Build one item: icon cell + label. `icon` = 'spin' | 'chk' | ''. */
function buildItem(labelText, icon, extraClass) {
  const item = document.createElement('div');
  item.className = 'nf-wl-item' + (extraClass ? ' ' + extraClass : '');
  if (icon) {
    const iconEl = document.createElement('span');
    iconEl.className = 'nf-wl-icon' + (icon === 'chk' ? ' nf-wl-chk' : '');
    if (icon === 'chk') iconEl.innerHTML = CHECK_SVG;
    else iconEl.innerHTML = '<span class="nf-wl-spin"></span>';
    item.appendChild(iconEl);
  } else if (extraClass && extraClass.indexOf('is-error') !== -1) {
    // Error state: the check glyph is replaced by a plain cross so the failed
    // tool reads at a glance (the row itself stays a single line).
    const iconEl = document.createElement('span');
    iconEl.className = 'nf-wl-icon nf-wl-x';
    iconEl.innerHTML = CROSS_SVG;
    item.appendChild(iconEl);
  }
  const label = document.createElement('span');
  label.className = 'nf-wl-label';
  label.textContent = labelText;
  item.appendChild(label);
  return item;
}

/** Swap the item in. `roll` = the leaving item gets the roll-up animation
 *  (a genuine item switch); false = in-place state change of the SAME item
 *  (tool running → tool done), which must not roll.
 *
 *  On a genuine switch the ENTERING item is marked `is-enter-late`: its roll-in
 *  is held back until the leaving item's roll-out is over. Without that hold the
 *  two items animate in the same window and BOTH labels are legible at once — a
 *  double exposure the approved demo does not have (there the outgoing item
 *  reaches opacity 0 before the incoming one leaves its from-state, i.e. a hard
 *  switch with a short empty gap; spec §二.1.1「旧上滚出、新下滚入」read as a
 *  SEQUENCE, not a cross-fade). The first item of a turn has no predecessor, so
 *  it is never delayed. */
function swap(wl, item, { key, kind, roll }) {
  const old = wl.item;
  let entersBehind = false;
  if (old && old !== item) {
    if (roll) {
      old.classList.add('is-out');
      const doomed = old;
      setTimeout(() => doomed.remove(), ROLL_MS);
      entersBehind = true;
    } else {
      old.remove();
    }
  }
  item.classList.toggle('is-enter-late', entersBehind);
  wl.item = item;
  wl.key = key;
  wl.kind = kind;
  wl.slot.appendChild(item);
}

/** Tool (or other single-line process) STARTED: spinner + label. Re-issuing the
 *  same key just refreshes the label (toolCallDetected → toolStart for the same
 *  tool must not flicker a roll). */
export function startWorklineItem(view, label, kind = 'tool') {
  const wl = ensureRow(view);
  if (!wl) return;
  const key = label;
  if (wl.item && wl.key === key && wl.kind === kind) {
    const labelEl = wl.item.querySelector('.nf-wl-label');
    if (labelEl) labelEl.textContent = label;
    return;
  }
  swap(wl, buildItem(label, 'spin'), { key, kind, roll: true });
}

/** Same item COMPLETED: drawn check + bounce, in place (no roll). */
export function doneWorklineItem(view, key) {
  const wl = state(view);
  if (!wl || !wl.item) return;
  if (wl.key !== key) return; // a different item owns the slot — leave it alone
  const labelEl = wl.item.querySelector('.nf-wl-label');
  const text = labelEl ? labelEl.textContent : '';
  swap(wl, buildItem(text, 'chk', 'is-done'), { key: wl.key, kind: wl.kind, roll: false });
}

/** Same item FAILED (toolEnd with isError): cross mark, in place (no roll). */
export function failWorklineItem(view, key) {
  const wl = state(view);
  if (!wl || !wl.item) return;
  if (wl.key !== key) return;
  const labelEl = wl.item.querySelector('.nf-wl-label');
  const text = labelEl ? labelEl.textContent : '';
  swap(wl, buildItem(text, '', 'is-error'), { key: wl.key, kind: wl.kind, roll: false });
}

/** Thinking: single-line width reveal. The first call opens the item; later
 *  calls only grow the revealed width, so the text never re-renders per frame
 *  (that is the A3/A7 discipline — no per-token DOM rebuild). */
export function thinkingWorklineItem(view, text) {
  const wl = ensureRow(view);
  if (!wl) return;
  if (!wl.item || wl.kind !== 'thinking') {
    const item = buildItem('', '', '');
    const label = item.querySelector('.nf-wl-label');
    label.textContent = '';
    const think = document.createElement('span');
    think.className = 'nf-wl-think';
    item.replaceChild(think, label);
    swap(wl, item, { key: 'thinking', kind: 'thinking', roll: true });
  }
  const think = wl.item.querySelector('.nf-wl-think');
  if (!think) return;
  think.textContent = text || '';
  const px = Math.min(THINK_MAX_PX, Math.round((text || '').length * 8 + 14));
  think.style.maxWidth = px + 'px';
}

/** Thinking finished: swap the reveal for the completed label + check. */
export function thinkingWorklineDone(view, text) {
  const wl = state(view);
  if (!wl || !wl.item || wl.kind !== 'thinking') return;
  const shown = text || '';
  const item = buildItem(shown, 'chk', 'is-done');
  const label = item.querySelector('.nf-wl-label');
  if (label) label.textContent = shown;
  swap(wl, item, { key: 'thinking', kind: 'thinking', roll: false });
}

/** Turn stats for the completed badge: counted from the DOM rows of the CURRENT
 *  turn (rows after the last non-injected user row) — same口径 as the turn
 *  header, computed locally so this module stays a leaf. */
function turnStats(chat) {
  const kids = Array.from(chat.children);
  let start = 0;
  kids.forEach((el, i) => {
    if (el.classList && el.classList.contains('row') && el.classList.contains('user') &&
        !el.querySelector('.bubble.injected')) start = i + 1;
  });
  let tools = 0;
  let thinking = 0;
  for (let i = start; i < kids.length; i++) {
    const el = kids[i];
    if (!el.classList || !el.classList.contains('row')) continue;
    if (el.classList.contains('tool')) tools++;
    else if (el.classList.contains('thinking-row')) thinking++;
  }
  return { tools, thinking };
}

/** Completed badge text: `已完成 N 工具 · 思考 N 轮 · 点击展开`. Used by the
 *  terminal hand-over (turnGroup.js) when the turn leaves NO `.turn-header`
 *  (boundary default: nothing tuckable, or nothing left visible after tucking)
 *  — in every other case the existing header IS the completed badge, per spec
 *  §二.1.4 「终态与现状一致」. Returns the badge label + hint as plain strings
 *  so the caller owns the DOM. */
export function worklineDoneText(view) {
  const chat = view && view.dom && view.dom.chat;
  const stats = chat ? turnStats(chat) : { tools: 0, thinking: 0 };
  return {
    label: t('chat.workline.tools', { n: stats.tools }) + ' · ' +
      t('chat.workline.thinking', { n: stats.thinking }),
    hint: t('chat.workline.expandHint'),
  };
}

/** Swap the slot to the completed badge (drawn check + counts + hint). Rolls
 *  the previous item out. Idempotent per turn: a second call is a no-op once
 *  the slot already holds the done badge. */
export function finishWorkline(view) {
  const wl = state(view);
  if (!wl || !wl.row || !wl.row.isConnected) return;
  if (wl.kind === 'done') return;
  const { label: labelText, hint: hintText } = worklineDoneText(view);
  const item = buildItem(labelText, 'chk', 'is-done');
  const hint = document.createElement('span');
  hint.className = 'nf-wl-hint';
  hint.textContent = hintText;
  item.appendChild(hint);
  swap(wl, item, { key: 'done', kind: 'done', roll: true });
}

/** Remove the work-line (turn terminal). Synchronous and idempotent: the turn's
 *  terminal DOM contract (turn-collapse-keep-text.spec.mjs `readSeq` enumerates
 *  #chat's children in full) admits no leftover top-level node, so the row is
 *  detached in the same tick the terminal is rendered — the existing
 *  `.turn-header` is what remains. */
export function removeWorkline(view) {
  const wl = state(view);
  if (!wl) return;
  if (wl.row && wl.row.isConnected) wl.row.remove();
  wl.row = null;
  wl.slot = null;
  wl.item = null;
  wl.key = '';
  wl.kind = '';
  // The next turn of this view starts a fresh badge (appendAiText latches once
  // per turn).
  if (view && view.stream) view.stream._nfWorklineDone = false;
}
