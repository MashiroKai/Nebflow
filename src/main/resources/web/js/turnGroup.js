// turnGroup.js — #346 WeChat-style turn-level collapse of intermediate process.
//
// On `done` the turn's process rows (thinking rows, tool cards, injected
// events) are gathered into `.turn-group` container(s) and collapsed behind a
// one-line summary bar: `✻ phrase, model · tool count` (v1.2: model name is
// visible summary text; timestamp stays in the title tooltip). Failed turns
// (`error`/`interrupted`/`timeout`/`maxTokens`) are grouped but NEVER
// collapsed — no summary bar (spec A5: the element does not exist), rows
// stay visible for troubleshooting. Streaming path is untouched: rows
// append directly to the chat as today; gathering happens once, at terminal.
//
// 2026-09-03 ruling — collapse keeps LLM text visible: ONLY tool blocks +
// tool results + injected events collapse. EVERY assistant text row (ai text,
// whatever its position in the turn) stays flat in its original position and
// order — text interleaved with tools splits the collapsible rows into
// contiguous RUNS, each run becoming its own group:
//   文字A → [✻ group1: 工具1] → 文字B → [✻ group2: 工具2] → 最终回复
// Text is never moved into (or out of) a group; expanding a run reveals its
// tools exactly where they happened.
//
// Zero protocol/backend changes (spec D1): success vs failure is executed
// here from the terminal events main.js already receives; history reload
// infers the initial state heuristically (E4 P0: a turn whose final Ai row
// carries a duration badge = success).

import { t } from './i18n.js';
import { pickThinkingPhrase, bindCollapsibleToggle, chevronSvg } from './chat.js';

let _turnGroupSeq = 0;

/** Rows that are user-participation nodes or non-process artifacts — never
 *  gathered (E7/E8): option boxes, permission prompts, ask answers, injected
 *  bubbles, agent rows. Injected bubbles are handled separately (isInjectedRow)
 *  — they ARE gathered as part of the turn's process (2026-08-25 ruling) but
 *  are excluded from the final-reply / done-badge detection. */
function isExcludedRow(row) {
  if (row.querySelector('.option-box') || row.querySelector('.permission-prompt')) return true;
  if (row.querySelector('.ask-label')) return true;            // ask answer / injected label
  if (row.querySelector('.bubble.injected')) return true;
  if (row.classList.contains('agent-row')) return true;
  return false;
}

/** An injected (external Mail / Team / event) message row. Rendered as
 *  `.row.user` with a `.bubble.injected` (chat.js buildInjectedRow) — so it
 *  would be mistaken for a user turn-boundary by lastUserIdx. 2026-08-25
 *  ruling: injected messages are part of the turn's intermediate process and
 *  are carried INTO the collapse; they never split a turn. */
function isInjectedRow(row) {
  return row.classList.contains('row') && row.querySelector('.bubble.injected');
}

/** True for agent-produced process rows (tool card, thinking row, AI text
 *  segment). The E7/E8 excluded artifacts (injected/option/ask/agent-row)
 *  never count as agent work. */
function isAgentRow(row) {
  if (!row.classList.contains('row') || isExcludedRow(row)) return false;
  if (row.classList.contains('tool') || row.classList.contains('card-content')) return true;
  if (row.classList.contains('thinking-row')) return true;
  // intermediate AI text segments (roundComplete finalize products)
  return row.classList.contains('ai') && !!row.querySelector('.bubble.ai');
}

/** True for rows that belong to the collapsible process — agent work MINUS
 *  assistant text (2026-09-03 ruling: tool blocks + tool results + injected
 *  events only; LLM text replies always stay visible in place). */
function isCollapsibleRow(row) {
  if (!row.classList.contains('row')) return false;
  if (isInjectedRow(row)) return true;
  if (row.classList.contains('tool') || row.classList.contains('card-content')) return true;
  if (row.classList.contains('thinking-row')) return true;
  return false; // ai text rows (and everything else) never collapse
}

/** Split scope rows into contiguous runs of collapsible rows. Assistant text
 *  rows act as run separators and stay flat — each run becomes one group, so
 *  文字→工具→文字→工具→最终 gathers as two groups with the texts untouched
 *  between them (original position and order preserved, 2026-09-03 ruling). */
function collapsibleRuns(rows) {
  const runs = [];
  let cur = null;
  for (const row of rows) {
    if (isCollapsibleRow(row)) {
      if (!cur) cur = [];
      cur.push(row);
    } else if (cur) {
      runs.push(cur);
      cur = null;
    }
  }
  if (cur) runs.push(cur);
  return runs;
}

/** True when the scope contains at least one agent-produced row (tool /
 *  thinking / AI text). A lone injected message (no agent work) is not a turn
 *  worth collapsing — hide nothing (preserves the E7 intent for isolated
 *  injections: an unworked injected bubble stays visible). */
function hasAgentWork(scope) {
  return scope.some(isAgentRow);
}

/** The turn's final reply row: last `.row.ai` in scope with non-empty text
 *  (spec §4.3). Returns null for zero-text turns. */
function findFinalRow(scope) {
  for (let i = scope.length - 1; i >= 0; i--) {
    const r = scope[i];
    if (!r.classList.contains('ai') || r.classList.contains('thinking-row') || isExcludedRow(r)) continue;
    const bubble = r.querySelector('.bubble.ai');
    if (bubble && (bubble.textContent || '').trim()) return r;
  }
  return null;
}

/** Build the group DOM around the given collapsible run (DOM moves, no
 *  rebuild — listeners and scroll memory survive). `finalRow` (the turn's
 *  final text reply, never part of a run since the 2026-09-03 ruling) is only
 *  consulted for the E6 thinking-only exemption. Returns null when there is
 *  nothing to group (E5 zero-process; E6 thinking-only = final product).
 *  `withSummary` controls whether a summary bar element is created — failed
 *  groups pass false: spec A5 asserts `.turn-summary` does NOT exist there. */
function buildGroup(chat, run, finalRow, meta, withSummary = true) {
  const steps = run;
  if (steps.length === 0) return null;
  // E6: a lone thinking row with no text reply IS the user's only readable
  // content — never hide it.
  if (!finalRow && steps.length === 1 && steps[0].classList.contains('thinking-row')) return null;

  const idx = _turnGroupSeq++;
  const group = document.createElement('div');
  group.className = 'turn-group';
  const stepsEl = document.createElement('div');
  stepsEl.className = 'turn-steps';
  stepsEl.id = 'turn-steps-' + (meta.sessionId || 'view') + '-' + idx;

  let summary = null, text = null;
  if (withSummary) {
    summary = document.createElement('div');
    summary.className = 'turn-summary';
    summary.appendChild(chevronSvg());
    text = document.createElement('span');
    text.className = 'turn-summary-text';
    summary.appendChild(text);
    group.appendChild(summary);
  }
  group.appendChild(stepsEl);

  chat.insertBefore(group, run[0]);
  steps.forEach(r => stepsEl.appendChild(r));
  return { group, steps: stepsEl, summary, text };
}

/** Fill the summary bar: frozen status-line phrase (✻ …, duration embedded)
 *  + model name + tool count — all visible text (v1.2 user ruling 2026-08-21
 *  12:08: model moves from tooltip to summary text; footer keeps only
 *  time + copy). Timestamp stays in the title tooltip. */
function fillSummary(built, meta) {
  const toolCount = built.steps.querySelectorAll('.tool-card').length;
  const phrase = meta.phrase || (meta.durationMs != null ? pickThinkingPhrase(meta.durationMs, meta.seed) : '');
  const parts = [];
  if (phrase) parts.push(phrase);
  if (meta.model) parts.push(meta.model);
  parts.push(t(toolCount === 1 ? 'chat.turnSummaryToolsOne' : 'chat.turnSummaryTools', { n: toolCount }));
  built.text.textContent = parts.join(' · ');
  built.summary.title = meta.title || '';
  built.summary.setAttribute('aria-controls', built.steps.id);
  bindCollapsibleToggle(built.summary, () => built.steps, (expanded) => {
    built.group.dataset.turnState = expanded ? 'done-expanded' : 'done';
  });
}

/**
 * Turn scope for a terminal event (#346 + #403): the rows that belong to the
 * turn now ending.
 *
 * Boundary = the last NON-injected user row — injected messages mid-turn are
 * process (2026-08-25 ruling) and never split a turn.
 *
 * #403 closure ruling (2026-08-26 20:24/20:26): the group CLOSES when the LLM
 * turn ends. Anything appended AFTER closure — a delegate completion, Team
 * Mail, Schedule firing, flow notification (each triggers its own new turn) —
 * must open a NEW group and is forbidden from merging into the closed one.
 * collapseTurn/failTurn therefore stamp a closure cursor (child count + anchor
 * element); when fresh content exists past the cursor, the whole closed
 * prefix (earlier groups AND their trailing flat final reply) is the
 * boundary. The pre-#403 bug: ungroupTail dissolved the previous turn's
 * closed group and the new terminal re-gathered it ("fusion").
 *
 * The cursor self-invalidates: it is honored only while its anchor element
 * still sits at the recorded position — an innerHTML rebuild (session switch
 * / reconnect) disconnects the anchor and the legacy path applies until
 * buildTurnGroupsForHistory stamps a fresh cursor.
 *
 * Same-turn re-terminal (done then a late error — no rows appended between)
 * keeps the legacy heal: ungroupTail dissolves the just-built group so the
 * turn is re-gathered exactly once under the new verdict.
 */
const closedCursors = new WeakMap();

/** Stamp the closure cursor: every child now in the chat belongs to a closed
 *  turn (or earlier). Rows appended after this point start a new turn. */
function markClosed(chat) {
  closedCursors.set(chat, { len: chat.children.length, anchor: chat.lastElementChild });
}

/** Stamp the cursor at a specific child index (busyTail rebuild: the flat
 *  tail rows after idx belong to the OPEN turn). */
function markClosedAt(chat, idx) {
  closedCursors.set(chat, { len: idx, anchor: chat.children[idx - 1] || null });
}

function turnScope(chat) {
  const cur = closedCursors.get(chat);
  const valid = !!cur && (cur.len === 0 ||
    (cur.anchor && cur.anchor.parentNode === chat && chat.children[cur.len - 1] === cur.anchor));
  const fresh = valid && chat.children.length > cur.len;
  if (!fresh) ungroupTail(chat); // same-turn re-terminal heal / no valid cursor
  const kids = Array.from(chat.children);
  let boundary = -1;
  kids.forEach((el, i) => {
    if (el.classList && el.classList.contains('row') && el.classList.contains('user') && !isInjectedRow(el)) boundary = i;
  });
  if (fresh) boundary = Math.max(boundary, cur.len - 1);
  return kids.slice(boundary + 1).filter(el => el.classList && el.classList.contains('row'));
}

/**
 * Done path: gather this turn's collapsible runs and collapse immediately
 * (synchronous, no linger — v1.1 user ruling). Text replies stay flat between
 * the groups (2026-09-03 ruling). Returns the last group built, or null.
 */
export function collapseTurn(view, meta = {}) {
  const chat = view.dom.chat;
  const scope = turnScope(chat);
  const finalRow = findFinalRow(scope);
  const builtGroups = [];
  if (hasAgentWork(scope)) { // lone injection / no agent work → nothing to collapse
    for (const run of collapsibleRuns(scope)) { // E5/E6 runs → buildGroup null
      const built = buildGroup(chat, run, finalRow, meta);
      if (built) builtGroups.push(built);
    }
  }
  for (const built of builtGroups) {
    fillSummary(built, meta);
    built.group.dataset.turnState = 'done';
    built.steps.style.display = 'none';
    built.summary.setAttribute('aria-expanded', 'false');
  }
  if (builtGroups.length) {
    // keep the viewport pinned to the bottom when it was pinned (spec §4.2)
    if (chat.scrollHeight - chat.scrollTop - chat.clientHeight < 80) {
      chat.scrollTop = chat.scrollHeight;
    }
  }
  markClosed(chat); // #403: LLM ended — the turn is closed even when there
                    // was nothing to group (E5/E6); later arrivals are a new turn.
  return builtGroups.length ? builtGroups[builtGroups.length - 1].group : null;
}

/**
 * Failed path: group for structural consistency but keep everything visible;
 * the summary bar is permanently hidden (no collapse entry — spec D2).
 */
export function failTurn(view) {
  const chat = view.dom.chat;
  const scope = turnScope(chat);
  const finalRow = findFinalRow(scope);
  const builtGroups = [];
  if (hasAgentWork(scope)) {
    for (const run of collapsibleRuns(scope)) {
      // A5: failed groups carry NO summary element (not merely hidden).
      const built = buildGroup(chat, run, finalRow, {}, false);
      if (built) builtGroups.push(built);
    }
  }
  for (const built of builtGroups) {
    built.group.dataset.turnState = 'failed';
    built.group.classList.add('turn-failed');
  }
  markClosed(chat); // #403: terminal reached — later arrivals are a new turn
  return builtGroups.length ? builtGroups[builtGroups.length - 1].group : null;
}

/** E10: message-search hit inside a collapsed group — expand it first so the
 *  row is visible/focusable. Returns true when a group was expanded. */
export function expandGroupContaining(el) {
  const group = el.closest('.turn-group');
  if (!group || group.dataset.turnState !== 'done') return false;
  const steps = group.querySelector('.turn-steps');
  if (!steps || steps.style.display !== 'none') return false;
  steps.style.display = '';
  group.dataset.turnState = 'done-expanded';
  const bar = group.querySelector('.turn-summary');
  if (bar) { bar.classList.add('expanded'); bar.setAttribute('aria-expanded', 'true'); }
  return true;
}

/** Boundary heal (2026-08-24): a mid-turn tail can get grouped by a history
 *  rebuild (refresh / WS reconnect full-reload / popup open) before the turn's
 *  terminal event arrives — stamped 'failed' and stranded expanded forever,
 *  while the live terminal then gathers only the post-rebuild rows into a
 *  second group ("half-expanded dead state"). A same-turn re-terminal (done
 *  then a late error, no rows appended between) must heal the DOM: hoist
 *  every turn-group after the last user row back to flat rows (order
 *  preserved) so the whole turn is gathered exactly once.
 *  #403 (2026-08-26): only invoked when NO content was appended since the
 *  last close — once fresh rows exist past the closure cursor, closed groups
 *  are immutable boundaries (a new turn is running; dissolving the previous
 *  group fused the two turns — the bug this fixes). */
function ungroupTail(chat) {
  const kids = Array.from(chat.children);
  let lastUserIdx = -1;
  kids.forEach((r, i) => { if (r.classList && r.classList.contains('row') && r.classList.contains('user') && !isInjectedRow(r)) lastUserIdx = i; });
  for (let i = lastUserIdx + 1; i < kids.length; i++) {
    const g = kids[i];
    if (!g.classList || !g.classList.contains('turn-group')) continue;
    const steps = g.querySelector('.turn-steps');
    if (steps) while (steps.firstChild) chat.insertBefore(steps.firstChild, g);
    g.remove();
  }
}

/**
 * History reload (E4 P0 heuristic): rebuild turn groups from the flat row
 * sequence. Turn boundaries = `.row.user` (injected included — they trigger
 * turns too). Per segment: a duration badge on the final Ai row = success →
 * collapsed; process rows without it = failed/unfinished → expanded.
 * Uncertain → expanded (observability over tidiness).
 *
 * opts.busyTail (2026-08-24 boundary fix): when the session is still mid-turn
 * at rebuild time (refresh/reconnect/popup open during streaming), the
 * trailing segment has no done badge YET but is not failed. Grouping it would
 * stamp it 'failed' and strand a zombie group. Skip it when it carries no
 * done badge — the live stream keeps appending flat rows and the terminal
 * event gathers them. A badge-carrying tail is complete → grouped normally.
 */
export function buildTurnGroupsForHistory(chat, opts = {}) {
  const { busyTail = false } = opts;
  const rows = Array.from(chat.children).filter(el => el.classList && el.classList.contains('row'));
  let segStart = 0;
  const flush = (end) => {
    if (end > segStart) groupSegment(chat, rows.slice(segStart, end));
  };
  rows.forEach((r, i) => {
    // Turn boundary = non-injected user row (injected messages are process).
    if (r.classList.contains('user') && !isInjectedRow(r)) { flush(i); segStart = i + 1; return; }
    // #403 history side of the closure ruling: an injected row that arrives
    // AFTER the current segment already carries a done badge (LLM ended,
    // group closed) opened its own new turn — split here. Mid-turn injected
    // rows (no badge yet in this segment) stay inside the turn's process.
    if (isInjectedRow(r) && findDoneBadge(rows.slice(segStart, i))) { flush(i); segStart = i; }
  });
  const tail = rows.slice(segStart);
  if (!busyTail || findDoneBadge(tail)) {
    flush(rows.length);
    markClosed(chat); // #403: everything rebuilt is a closed turn
  } else if (tail.length > 0) {
    // busyTail skipped: the flat tail rows belong to the OPEN turn — the
    // closure cursor sits at the tail's first row, not at the chat end, or
    // the live terminal would find "no fresh content" and dissolve the
    // legitimately closed history groups.
    const idx = Array.from(chat.children).indexOf(tail[0]);
    if (idx > 0) markClosedAt(chat, idx);
  } else {
    markClosed(chat);
  }
}

function groupSegment(chat, seg) {
  if (!hasAgentWork(seg)) return; // lone injection / no agent work — leave flat
  const finalRow = findFinalRow(seg);
  const runs = collapsibleRuns(seg);
  if (runs.length === 0) return; // E5: nothing collapsible (text-only turn)
  // E4 P0: success = some Ai row in the segment carries a done-badge
  // (SessionRecorder backfills durationMs on done — the implicit marker).
  // v1.2: footers are time + copy only, so phrase/model ride on the badge's
  // data-nf-phrase / data-nf-model attributes instead of visible spans.
  const badge = findDoneBadge(seg);
  const success = !!badge;
  const meta = success ? {
    phrase: badge.dataset.nfPhrase || '',
    model: badge.dataset.nfModel || '',
    title: historyBadgeTitle(seg, badge),
    sessionId: chat.dataset?.sessionId || '',
  } : {};
  // A5: failed segments build the group WITHOUT a summary element.
  // 2026-09-03: one group per collapsible run — assistant text rows between
  // runs stay flat at their original positions (live/rebuild parity).
  for (const run of runs) {
    const built = buildGroup(chat, run, finalRow, meta, success);
    if (!built) continue;
    if (success) {
      fillSummary(built, meta);
      built.group.dataset.turnState = 'done';
      built.steps.style.display = 'none';
      built.summary.setAttribute('aria-expanded', 'false');
    } else {
      built.group.dataset.turnState = 'failed';
      built.group.classList.add('turn-failed');
    }
  }
}

/** The turn's implicit done-marker: an AI footer badge carrying a duration
 *  phrase (data-nf-phrase). Copy-only footers (no duration) don't count. */
function findDoneBadge(seg) {
  for (let i = seg.length - 1; i >= 0; i--) {
    const badges = seg[i].querySelectorAll('.duration-badge');
    for (let j = badges.length - 1; j >= 0; j--) {
      if (badges[j].dataset.nfPhrase) return badges[j];
    }
  }
  return null;
}

function historyBadgeTitle(seg, badge) {
  // v1.2: model is visible summary text; the tooltip carries the timestamp only.
  const row = badge?.closest('.row') || seg.find(r => r.contains(badge));
  return row?.querySelector('.duration-badge-time')?.textContent || '';
}
