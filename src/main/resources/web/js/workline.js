// workline.js — the turn line: ONE persistent line that IS both the running
// process and the terminal badge (stream-ux redesign, 2026-10-03 author demo).
//
// The line lives at the turn top (right after the user row — the exact slot the
// `.turn-header` occupies at the terminal). While the turn runs it is the ONLY
// live process surface: dots before the first token, the thinking stream as a
// single-line width reveal, each tool as spinner → drawn check, every switch a
// roll-up / roll-down. The thinking bubble and tool cards are still written to
// the DOM by chat.js, but PRE-TUCKED (`.nf-tucked`) — they are the expand face,
// never a visible feed. At the terminal turnGroup.js settles THIS line into the
// `.turn-header` (same element, same position): the last item rolls out, the
// stats badge rolls in, and the row gains the header's role/toggle. There is no
// remove-and-rebuild hand-over any more — 「过程全部展示在这一行，终态与该行风格
// 统一」.
//
// Why this module stays a LEAF (imports i18n + utils only, never chat.js /
// turnGroup.js):
//  · The morph keeps the terminal contract: the only top-level turn node is the
//    `.turn-header` — tests/turn-collapse-keep-text.spec.mjs `readSeq` and
//    tests/stream-ux.spec.mjs A5 enumerate #chat's children in full.
//  · Its node carries NO `.row` class and never `.row.ai`: the background-agent
//    popup family pins `.row.ai` counts
//    (tests/bgagent-injected-bubbles.spec.mjs `aiRows === 1`).
//  · Pure CSS animations only — the turn-* specs pin script-driven (WAAPI)
//    animations out of the process rows.
//  · The chevron SVG is an inline copy of chat.js chevronSvg() on purpose:
//    importing chat.js from here would close the chat.js → workline.js cycle.

import { t } from './i18n.js';
import { isNearBottom } from './utils.js';

/** Out-phase duration before the leaving item is dropped from the DOM —
 *  slightly longer than the CSS fade-out (0.3s) so the animation completes. */
const ROLL_MS = 380;
/** How long the exit window stays OPEN: an item that enters while the window is
 *  open is held until it closes (see `swap`). 0.4s = the CSS fade-out (0.3s)
 *  plus the short empty gap the approved demo shows between the outgoing item
 *  reaching opacity 0 and the incoming one leaving its from-state. */
const EXIT_HOLD_MS = 400;

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

/** Inline copy of chat.js chevronSvg() (leaf module — see header). */
function chevronSvg() {
  const span = document.createElement('span');
  span.className = 'nf-chevron';
  span.setAttribute('aria-hidden', 'true');
  span.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>';
  return span;
}

/** Live-seconds formatter for the dots item (leaf-local copy of the
 *  chat.js formatLiveDuration shape: `42s` / `1m 03s`). */
function formatLive(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return s + 's';
  return Math.floor(s / 60) + 'm ' + String(s % 60).padStart(2, '0') + 's';
}

function state(view) {
  if (!view) return null;
  if (!view._nfWorkline) {
    view._nfWorkline = { view, row: null, slot: null, item: null, key: '', kind: '', exitUntil: 0, dotTimer: 0, round: 0 };
  }
  return view._nfWorkline;
}

/** Window-role motion switch (UI-G, author 2026-10-03 19:59 ruling): the main
 *  Nebula window animates, subagent / flow popup windows stay static to save
 *  resources. The decision itself lives on the view (chatView.js
 *  `isMainWindow` → `view.motionEnabled`) — this module only consumes it, so
 *  workline.js stays a leaf (no chatView.js import ⇒ no cycle). */
function motion(view) {
  return typeof view?.motionEnabled === 'function' ? view.motionEnabled() : true;
}

/* ── badge terminal-state oracle (AskUser badge 分组条件化, author 2026-10-07
   18:36 三令之②·新口径) ────────────────────────────────────────────────────
   THE single terminal criterion, shared by turnGroup.js's five boundary
   readers (isNonInjectedUserRow → turnScope / dissolveTurnChrome /
   scopeFromHeader / expandGroupContaining / buildTurnSummariesForHistory) and
   this module's turnAnchor. The old pair of static knives — 398dd72ac「注入腿
   统一重开」(any user row re-opens) and 0584bf0bb「作答行豁免」(a stamped
   answer row never opens) — is upgraded to ONE conditional rule:

     上一轮 badge 未到终态 → 并入同组 (merge into the open group)
     上一轮 badge 已终态   → 下方新开 badge 单行流式 (own line + badge below,
                             与用户输入同形)

   Terminal criterion = the badge STATUS FIELD, read at ONE point here: a
   settled `.turn-header` whose `data-turn-state` is `done` / `done-expanded`
   (the field every settle/build path stamps — turnGroup.js collapseTurn
   settle path, buildHeader fallback, history summarizeSegment). The walk
   stops at the first decisive marker above `row`:

   · settled turn-header                     ⇒ TRUE  (previous group terminal)
   · the ask card this row's stamp answers   ⇒ FALSE (the exchange the answer
     (`.option-box[data-request-id]` ===       continues is open RIGHT HERE —
     `data-nf-ask-answer`; stamped rows)       its ack-round badge, when one
                                               exists, is mid-exchange, never
                                               the group's terminal — B2/B3
                                               一枚 header 面不动)
   · a plain user row (real input, always    ⇒ stamped row: TRUE — the answer's
     an opener)                                own card is NOT in the segment
                                               above ⇒ the exchange it replies
                                               into already closed ⇒ the answer
                                               opens a NEW group below;
                                             injected row: FALSE — the group
                                               above is open ⇒ merge (tail
                                               injection 并入同组)
   · exhausted ⇒ FALSE (nothing above ⇒ no badge ⇒ merge)

   Lives HERE because workline.js is the leaf (turnGroup.js already imports
   this module; the reverse import would close a cycle). */
export function priorTurnBadgeSettled(chat, row) {
  if (!chat || !row || !row.classList) return false;
  const rid = row.getAttribute('data-nf-ask-answer') || '';
  for (let el = row.previousElementSibling; el; el = el.previousElementSibling) {
    const cls = el.classList;
    if (!cls) continue;
    if (cls.contains('turn-header')) {
      const st = el.dataset ? el.dataset.turnState : '';
      if (st === 'done' || st === 'done-expanded') return true;
      continue;
    }
    if (!cls.contains('row')) continue;
    if (rid) {
      const boxes = el.querySelectorAll('.option-box[data-request-id]');
      for (const b of boxes) {
        if (b.getAttribute('data-request-id') === rid) return false;
      }
    }
    if (cls.contains('user') && !el.hasAttribute('data-nf-ask-answer') &&
        !(el.querySelector && el.querySelector('.bubble.injected'))) {
      return !!rid; // plain user row: stamped ⇒ new group; injected ⇒ open tail
    }
  }
  return false;
}

/** The turn-top anchor: the last user-class row of #chat — CONDITIONAL since
 *  the AskUser badge 分组条件化批 (author 2026-10-07 18:36 三令之②): a user-
 *  class row anchors a NEW line only when the group above it has settled its
 *  badge (priorTurnBadgeSettled — the single oracle above). The pre-conditional
 *  rulings remain as its branches:
 *
 *  · Real user input: unconditional opener (unchanged — the reference shape).
 *  · Injected rows (toolline-detail 批 2026-10-06, dispatcher 16:54 令「Badge
 *    重建逻辑 Bug」): turn 边界 = 任何面向用户的开轮事件 — an injection whose
 *    previous badge is TERMINAL anchors after its bubble (idle injection /
 *    queued continuation — badge-injected-turn.spec faces); an injection into
 *    an OPEN tail (busyTail replay, rows after the bubble) merges — no
 *    re-anchor, the line stays at the turn top. A tail injection with NOTHING
 *    after it also anchors: the previous turn may be headerless-closed (E5
 *    text-only — no badge exists to read) and the line must not land inside
 *    it. Injection bubble display shape zero-changed.
 *  · Ask ANSWER rows (AskUser 强制展开修令, 2026-10-06 19:29 令, now
 *    conditional): a replayed card answer (`data-nf-ask-answer`) anchors its
 *    own line ONLY when the exchange it replied into already settled (下方新开
 *    — 与用户输入同形); inside its open exchange it stays mid-turn chrome
 *    (the exemption, conditional).
 *
 *  Fallback: when the chosen anchor's group already settled (a turn-header
 *  rides between it and the tail), the arriving turn is NEWER than every row-
 *  anchored group — the line opens at the chat BOTTOM (anchor = null), never
 *  above a settled badge (the parallel-badge face the 09:25 令 fixed for the
 *  pending-ask path).
 *
 *  Deliberately NOT mirrored into turnGroup's turnScope / dissolveTurnChrome /
 *  scopeFromHeader: the live terminal scope is governed by the #403 closure
 *  cursor (marked at each terminal), which already scopes injection turns
 *  correctly, and a mid-turn continuation bubble must NOT split the turn it
 *  arrived into (the bubble stays inside that turn's group; the QUEUED
 *  continuation opens its own turn with its own line+badge — the anchor here
 *  is consulted only when a NEW line is built, i.e. at genuine turn starts).
 *  The downward toggle walk (scopeFromHeader) needs no change either: the
 *  settled badge of the injection turn is inserted right after the bubble, so
 *  it terminates the walk at exactly the right row. History rebuild already
 *  splits an injected row that follows a closed (badged) segment
 *  (buildTurnSummariesForHistory #403 note) — live and history agree. */
function turnAnchor(chat) {
  let anchor = null;
  for (const el of chat.children) {
    if (!el.classList || !el.classList.contains('row') || !el.classList.contains('user')) continue;
    if (el.hasAttribute('data-nf-ask-answer')) {
      if (priorTurnBadgeSettled(chat, el)) anchor = el;
      continue;
    }
    if (el.querySelector && el.querySelector('.bubble.injected')) {
      if (priorTurnBadgeSettled(chat, el) || !el.nextElementSibling) anchor = el;
      continue;
    }
    anchor = el; // real user input: unconditional opener
  }
  for (let n = anchor ? anchor.nextElementSibling : null; n; n = n.nextElementSibling) {
    if (n.classList && n.classList.contains('turn-header')) return null;
  }
  return anchor;
}

function ensureRow(view) {
  const wl = state(view);
  wl.view = view; // the motion-role owner; refreshed each call so a released line re-binds
  if (wl.row && wl.row.isConnected) return wl;
  const chat = view.dom && view.dom.chat;
  if (!chat) return null;
  const row = document.createElement('div');
  row.className = 'nf-workline';
  // UI-G: a static window (subagent / flow popup) runs the line with every
  // animation off — one class, one CSS rule, no per-element handling.
  if (!motion(view)) row.classList.add('nf-static');
  row.setAttribute('role', 'status');
  row.setAttribute('aria-live', 'polite');
  row.appendChild(chevronSvg());
  const slot = document.createElement('div');
  slot.className = 'nf-wl-slot';
  row.appendChild(slot);
  const anchor = turnAnchor(chat);
  if (anchor && anchor.parentNode === chat) anchor.after(row);
  else chat.appendChild(row);
  // The line is one row tall; keep the viewport pinned when it was pinned (the
  // insertion above would otherwise push the pinned content out of view).
  if (isNearBottom(chat)) chat.scrollTop = chat.scrollHeight;
  wl.row = row;
  wl.slot = slot;
  wl.item = null;
  wl.key = '';
  wl.kind = '';
  wl.exitUntil = 0;
  // A brand-new line is the start of a brand-new turn ⇒ round attribution
  // restarts at 0 (UI-B). Rounds only advance within a turn (beginRound).
  wl.round = 0;
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

/** True when the item is currently legible in the slot. An item that is being
 *  held back by an open exit window sits at its from-state (opacity 0) for the
 *  whole hold, so it has no exit of its own left to animate. */
function isLegible(item) {
  if (!item || !item.isConnected) return false;
  return +getComputedStyle(item).opacity > 0.05;
}

/** The switch hold is a motion device; under reduced motion the CSS turns the
 *  animations off, so JS must not re-introduce a wait as an inline delay. */
function prefersReducedMotion() {
  try {
    return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  } catch {
    return false;
  }
}

/** Stop the dots item's live timer (owned by the slot — any item change or the
 *  terminal settles it). */
function stopDotTimer(wl) {
  if (wl.dotTimer) {
    clearInterval(wl.dotTimer);
    wl.dotTimer = 0;
  }
}

/** Swap the item in. `roll` = the leaving item gets the roll-up animation
 *  (a genuine item switch); false = in-place state change of the SAME item
 *  (tool running → tool done), which must not roll.
 *
 *  The slot obeys ONE invariant, and it is deliberately independent of WHEN the
 *  next event lands: **at most one item is legible at a time**. A switch opens
 *  an exit window (`wl.exitUntil`) covering the outgoing item's roll-out plus
 *  the short empty beat the approved demo leaves between "old out" and "new
 *  in"; every item that enters while that window is open is held at its
 *  from-state until the window closes. That is what makes the switch a hard
 *  switch (「旧上滚出、新下滚入」 read as a SEQUENCE) rather than a cross-fade,
 *  and — the point of tracking the window instead of relying on a fixed CSS
 *  delay — it still holds when the incoming item's own COMPLETION arrives
 *  inside the window (that case rebuilt the item as a fresh element, dropping
 *  the hold, exactly while the outgoing item was still legible). The first item
 *  of a turn has no predecessor, so it is never held. */
function swap(wl, item, { key, kind, roll }) {
  stopDotTimer(wl);
  const animated = motion(wl.view);
  const reduce = prefersReducedMotion() || !animated;
  const old = wl.item;
  if (old && old !== item) {
    // Roll the outgoing item out only when there is something legible to
    // animate AND motion is on. An item already held invisible by an open
    // window has no exit of its own left — animating it would restart its
    // opacity at 1 and double-expose it against the item still rolling away.
    if (roll && !reduce && isLegible(old)) {
      old.classList.add('is-out');
      const doomed = old;
      // No exit timer in a static window (UI-G): the leaving item is dropped
      // in the same frame, so the subagent path never arms a timeout.
      setTimeout(() => doomed.remove(), ROLL_MS);
      wl.exitUntil = performance.now() + EXIT_HOLD_MS;
    } else {
      old.remove();
    }
  }
  // Precise remaining hold. The `.is-enter-late` class alone carries a fixed
  // 0.4s measured from THIS swap, which is wrong for an item that enters
  // mid-window: it would restart the clock instead of waiting out the window.
  // Two delay values mirror the two-animation `.is-done` pair (roll-in + the
  // 0.1s-delayed bounce); a single-animation item uses the first value.
  // Static windows hold nothing: with the CSS animations off the item is
  // legible immediately, so an inline delay would only hide it.
  const hold = reduce ? 0 : Math.max(0, Math.round(wl.exitUntil - performance.now()));
  item.classList.toggle('is-enter-late', hold > 0);
  if (hold > 0) item.style.animationDelay = hold + 'ms, ' + (hold + 100) + 'ms';
  wl.item = item;
  wl.key = key;
  wl.kind = kind;
  wl.slot.appendChild(item);
}

/** Turn started, nothing streaming yet: dots + 「思考中」 + a live seconds
 *  timer, on the turn line (the old `.thinking-placeholder` bubble's face,
 *  moved onto the line by the 2026-10-03 redesign). The timer stops itself the
 *  moment any real item replaces the dots (swap) or the turn ends
 *  (removeWorkline / settleWorklineAsHeader).
 *  UI-G: a static window (subagent / flow popup) gets NO live-seconds timer —
 *  the hint is written once and never re-armed, so the window adds zero
 *  periodic work. */
export function startWorklineDots(view, startedAt) {
  const wl = ensureRow(view);
  if (!wl) return;
  if (wl.kind === 'dots') return; // already showing — timer keeps running
  const item = document.createElement('div');
  item.className = 'nf-wl-item';
  const dots = document.createElement('span');
  dots.className = 'nf-wl-dots';
  for (let i = 0; i < 3; i++) {
    const dot = document.createElement('span');
    dot.className = 'thinking-dot';
    if (i === 1) dot.style.animationDelay = '0.15s';
    if (i === 2) dot.style.animationDelay = '0.3s';
    dots.appendChild(dot);
  }
  item.appendChild(dots);
  const label = document.createElement('span');
  label.className = 'nf-wl-label';
  label.textContent = t('chat.thinking.now');
  item.appendChild(label);
  const hint = document.createElement('span');
  hint.className = 'nf-wl-hint';
  const start = startedAt || Date.now();
  hint.textContent = formatLive(Date.now() - start);
  item.appendChild(hint);
  swap(wl, item, { key: 'dots', kind: 'dots', roll: true });
  stopDotTimer(wl);
  if (!motion(view)) return; // UI-G: static window — no setInterval, no ticking
  wl.dotTimer = setInterval(() => {
    if (!item.isConnected) { stopDotTimer(wl); return; }
    hint.textContent = formatLive(Date.now() - start);
  }, 1000);
}

/** Tail anchor + overflow-gated head fade (ui-polish 2026-10-08). The `1e7`
 *  clamp write stays THE anchor (newest text at the right edge). Classifying
 *  the box costs the B4 frame budget's ONE geometry read (reading `scrollLeft`
 *  back — clamped > 0 ⇔ the box overflows): an overflowing box gets
 *  `.nf-wl-clip`, whose fade lives on the LEFT edge — where old text exits —
 *  so the newest text and the caret at the right edge keep full ink (the old
 *  right-edge mask dissolved exactly them). A fitting box gets no mask, so a
 *  short label keeps its plain clipped edge. */
function pinTail(el) {
  el.scrollLeft = 1e7;
  el.classList.toggle('nf-wl-clip', el.scrollLeft > 0);
}

/** Tool (or other single-line process) STARTED: spinner + label. Re-issuing the
 *  same key just refreshes the label (toolCallDetected → toolStart for the same
 *  tool must not flicker a roll).
 *  zcode-484: the label is ONE clipped, tail-faded row like the thinking text,
 *  so the refresh path re-anchors it to its right edge (`1e7` clamp — pinTail,
 *  no other layout read — see thinkingWorklineItem's doc for the discipline). */
export function startWorklineItem(view, label, kind = 'tool') {
  const wl = ensureRow(view);
  if (!wl) return;
  const key = label;
  if (wl.item && wl.key === key && wl.kind === kind) {
    const labelEl = wl.item.querySelector('.nf-wl-label');
    if (labelEl) { labelEl.textContent = label; pinTail(labelEl); }
    return;
  }
  swap(wl, buildItem(label, 'spin'), { key, kind, roll: true });
  const fresh = wl.item && wl.item.querySelector('.nf-wl-label');
  if (fresh) pinTail(fresh);
}

/** Same item COMPLETED: drawn check + bounce, in place (no roll).
 *  ③ 完成翻转（toolline-detail 批 2026-10-06）：`resultText` = 单行结果摘要
 *  （chat.js toolLineResultText 组装自 toolEnd 帧已有字段）；空/缺席时回退
 *  运行中 label 文本（旧引擎帧逐字保持原行为）。翻转 = 项级替换（buildItem
 *  新建项），运行细节 span 随旧项自然卸下——展开面（tucked 卡）零触碰。 */
export function doneWorklineItem(view, key, resultText) {
  const wl = state(view);
  if (!wl || !wl.item) return;
  if (wl.key !== key) return; // a different item owns the slot — leave it alone
  const labelEl = wl.item.querySelector('.nf-wl-label');
  const fallback = labelEl ? labelEl.textContent : '';
  const text = (typeof resultText === 'string' && resultText) ? resultText : fallback;
  swap(wl, buildItem(text, 'chk', 'is-done'), { key: wl.key, kind: wl.kind, roll: false });
}

/** Same item FAILED (toolEnd with isError): cross mark, in place (no roll).
 *  resultText 语义同 doneWorklineItem（③ 完成翻转）。 */
export function failWorklineItem(view, key, resultText) {
  const wl = state(view);
  if (!wl || !wl.item) return;
  if (wl.key !== key) return;
  const labelEl = wl.item.querySelector('.nf-wl-label');
  const fallback = labelEl ? labelEl.textContent : '';
  const text = (typeof resultText === 'string' && resultText) ? resultText : fallback;
  swap(wl, buildItem(text, '', 'is-error'), { key: wl.key, kind: wl.kind, roll: false });
}

/** ② 运行中类型化细节行（toolline-detail 批 2026-10-06）：muted 小字细节骑在
 *  当前运行项上（spinner + label 之后）。调用方 = 参数流 rAF 帧（增量）与
 *  toolStart 终值重放，二者共用；本函数是唯一写入点——
 *  · kind/态守卫：只服务**运行中**工具项（dots/thinking 项不接细节；已完成
 *    项（is-done/is-error）不接——真实链路由 renderTool 的 cancelToolStreamRAF
 *    挡迟刷帧，本守卫兜直喂路径的残余增量）；
 *  · 跨帧复用：span 首次创建后只做 textContent 替换（流式帧零节点重建，
 *    与本行 spinner 的元素复用同纪律）；文本未变时零写；
 *  · 单行纪律：nowrap + 裁剪 + 尾部 fade（CSS 同 .nf-wl-label 档，.nf-wl-clip
 *    左缘 fade），右侧尾锚 `scrollLeft = 1e7`（pinTail——同 thinkingWorklineItem
 *    的 clamp 惯例）。 */
export function worklineToolDetail(view, text) {
  const wl = state(view);
  if (!wl || !wl.item || wl.kind !== 'tool') return;
  if (wl.item.classList.contains('is-done') || wl.item.classList.contains('is-error')) return;
  const next = String(text == null ? '' : text);
  if (!next) return;
  let el = wl.item.querySelector('.nf-wl-detail');
  if (!el) {
    el = document.createElement('span');
    el.className = 'nf-wl-detail';
    wl.item.appendChild(el);
  }
  if (el.textContent !== next) el.textContent = next;
  pinTail(el);
}

/** Thinking: the badge shows the thinking text as TEXT (UI-C text cadence). The
 *  line is ONE row (zcode-484 batch, author ruling 2026-10-04 09:29
 *  「在一行进行流式，像zcode一样」) — the 2026-10-03 UI-A wrapping rule is
 *  reversed, so the box is a fixed-height `nowrap` clipper and the streaming
 *  text scrolls inside it.
 *
 *  THE TAIL ANCHOR: after every append the box is pinned to its right edge
 *  (`pinTail` — the `1e7` clamp idiom chat.js uses for the chat scroll
 *  container). New content therefore flows in at the right and the older
 *  content slides out to the left, under the CSS head fade — the zcode shape.
 *  The anchor is written AFTER the append, never before. The ui-polish
 *  2026-10-08 classification read (scrollLeft back from pinTail) is the ONE
 *  geometry read the B4 one-geometry-read-per-frame discipline budgets for
 *  this frame — cargo-culting `scrollWidth` here would be a second read per
 *  delta.
 *
 *  Cadence stays the CSS caret, never a per-token DOM rebuild (A3/A7). Later
 *  calls only replace/extend the string — the prefix test below appends just
 *  the new tail, and the write is unconditional because a freshly built
 *  `.nf-wl-think` is never already at the right edge. */
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
  const next = text || '';
  const prev = think._nfText || '';
  // Append only the new tail. Re-writing the whole text node every delta makes
  // the browser re-shape and re-layout the entire (wrapping) line from scratch
  // — measured as the single largest layout cost of the thinking stream. The
  // prefix test also covers the rebuild path: a freshly built .nf-wl-think has
  // no `_nfText`, so the first call falls through to the whole-text write.
  if (prev && next.length > prev.length && next.startsWith(prev)) {
    think.appendChild(document.createTextNode(next.slice(prev.length)));
  } else if (next !== prev) {
    think.textContent = next;
  }
  think._nfText = next;
  // Tail anchor (see the doc above): pin the scroller to its right edge so the
  // newest text sits at the row's end and the old text slides out left.
  pinTail(think);
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

/** Terminal morph (stream-ux redesign 2026-10-03): the turn line IS the
 *  summary. The last running item rolls out, the stats badge text rolls in,
 *  and the row itself becomes the `.turn-header` — same element, same turn-top
 *  position, chevron already in place. turnGroup.js binds the toggle on the
 *  returned element (the scope walk lives there; this module stays a leaf).
 *  Returns the morphed header element, or null when there is no live line
 *  (caller falls back to buildHeader). Idempotent: a second call is a no-op.
 *
 *  UI-B (author 2026-10-03 「Badge 按轮次切分没有做的很好」): the settle RELEASES
 *  the line. The morph consumes `wl.row` (it is now the header, owned by
 *  turnGroup's toggle), so leaving `wl.row` / `wl.slot` pointing at it made the
 *  NEXT turn's `ensureRow` take the `isConnected` shortcut and append its live
 *  items into the PREVIOUS turn's settled badge — the badge carried the wrong
 *  round's process. Only `kind='done'` is kept, so a re-entrant settle inside
 *  the same terminal is still a no-op. */
export function settleWorklineAsHeader(view, headerText, title) {
  const wl = state(view);
  if (!wl || !wl.row || !wl.row.isConnected) return null;
  if (wl.kind === 'done') return null;
  const item = document.createElement('div');
  item.className = 'nf-wl-item is-final';
  const label = document.createElement('span');
  // `turn-header-text` is the header contract class — every reader (specs,
  // expandGroupContaining tooltips, future consumers) reads the settled badge
  // through the SAME selector as a built header's text span.
  label.className = 'nf-wl-label turn-header-text';
  label.textContent = headerText;
  item.appendChild(label);
  swap(wl, item, { key: 'done', kind: 'done', roll: true });
  const row = wl.row;
  row.classList.remove('nf-workline');
  row.classList.add('turn-header');
  row.removeAttribute('role'); // was status — a header is a button face
  row.removeAttribute('aria-live');
  if (title) row.title = title;
  // Release the line: the header now belongs to turnGroup, and the next turn
  // must build its OWN line (see the UI-B note above).
  stopDotTimer(wl);
  wl.row = null;
  wl.slot = null;
  wl.item = null;
  wl.key = '';
  wl.exitUntil = 0;
  return row;
}

/** Remove the turn line (failed / interrupted turns and header-less text-only
 *  terminals — the only shapes with nothing to settle into). Synchronous and
 *  idempotent: the terminal DOM contract (turn-collapse-keep-text.spec.mjs
 *  `readSeq` enumerates #chat's children in full) admits no leftover
 *  top-level node. */
export function removeWorkline(view) {
  const wl = state(view);
  if (!wl) return;
  stopDotTimer(wl);
  if (wl.row && wl.row.isConnected) wl.row.remove();
  wl.row = null;
  wl.slot = null;
  wl.item = null;
  wl.key = '';
  wl.kind = '';
  wl.exitUntil = 0;
  wl.round = 0;
}

/* ── round attribution (UI-B, author 2026-10-03 「Badge 按轮次切分没有做的
   很好」) ─────────────────────────────────────────────────────────────────
   The badge IS the turn line settled into `.turn-header` at the terminal, and
   the badge's numbers are computed from the turn's own row list
   (turnGroup.computeTurnStats). "切分" is therefore a QUESTION OF TURN SCOPE,
   not of splitting one turn into several headers: within a turn, several LLM
   rounds of tool calls (MemoryNote×5 → text → Task×4) all belong to ONE badge
   — the decisive original user quote is 「Badge按轮次切分没有做很好」 and the
   pinned regression (tests/turn-single-badge.spec.mjs) forbids two headers for
   a multi-round turn.

   The defect this fixes is the ROW-LEVEL attribution of a round boundary: at
   `roundComplete` (main.js) the work line is re-armed with the dots item for
   the upcoming round. That re-arm currently lands on whatever row happens to
   be last, and — the case the author scored — the PROCESS ROW of a tool whose
   completion arrives in the NEXT round is stamped with the round in which it
   STARTED, so a tool that is still running when the round flips is attributed
   to the wrong round.

   The attribution point is explicit and testable: `beginRound(view)` is called
   at each round boundary, and `roundOf(view)` reports the current round index
   for any row stamped by the live render path. Rows carry their round on
   `dataset.nfRound` so a reader (and a spec) can verify the split without
   reading the render code. Round 0 = the turn's first LLM round. */
export function beginRound(view, roundIndex) {
  const wl = state(view);
  if (!wl) return 0;
  wl.round = Number.isInteger(roundIndex) ? roundIndex : ((wl.round || 0) + 1);
  return wl.round;
}

/** The current round index for the view's live turn (0 while none started). */
export function roundOf(view) {
  const wl = state(view);
  return wl && Number.isInteger(wl.round) ? wl.round : 0;
}

/** Stamp a row with the round it belongs to. Called by chat.js for every
 *  process row it renders live, so the round split is a readable DOM fact. */
export function stampRound(view, row) {
  if (!row || !row.dataset) return;
  row.dataset.nfRound = String(roundOf(view));
}
