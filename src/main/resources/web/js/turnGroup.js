// turnGroup.js — #346 WeChat-style turn-level collapse of intermediate process.
//
// On `done` the turn's process rows (thinking rows, tool cards, intermediate
// text segments) are gathered into a `.turn-group` and collapsed behind a
// one-line summary bar (frozen work-status phrase + tool count; model and
// timestamp live in the title tooltip). Failed turns (`error`/`interrupted`/
// `timeout`/`maxTokens`) are grouped but NEVER collapsed — no summary bar,
// rows stay visible for troubleshooting. Streaming path is untouched: rows
// append directly to the chat as today; gathering happens once, at terminal.
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
 *  bubbles, agent rows. */
function isExcludedRow(row) {
  if (row.querySelector('.option-box') || row.querySelector('.permission-prompt')) return true;
  if (row.querySelector('.ask-label')) return true;            // ask answer / injected label
  if (row.querySelector('.bubble.injected')) return true;
  if (row.classList.contains('agent-row')) return true;
  return false;
}

/** True for rows that belong to the agent's intermediate process. */
function isProcessRow(row) {
  if (!row.classList.contains('row') || isExcludedRow(row)) return false;
  if (row.classList.contains('tool') || row.classList.contains('card-content')) return true;
  if (row.classList.contains('thinking-row')) return true;
  // intermediate AI text segments (roundComplete finalize products)
  return row.classList.contains('ai') && !!row.querySelector('.bubble.ai');
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

/** Build the group DOM around the given process rows (DOM moves, no
 *  rebuild — listeners and scroll memory survive). Returns null when there
 *  is nothing to group (E5 zero-process; E6 thinking-only = final product). */
function buildGroup(chat, processRows, finalRow, meta) {
  const steps = processRows.filter(r => r !== finalRow);
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

  const summary = document.createElement('div');
  summary.className = 'turn-summary';
  summary.appendChild(chevronSvg());
  const text = document.createElement('span');
  text.className = 'turn-summary-text';
  summary.appendChild(text);
  group.appendChild(summary);
  group.appendChild(stepsEl);

  chat.insertBefore(group, processRows[0]);
  steps.forEach(r => stepsEl.appendChild(r));
  return { group, steps: stepsEl, summary, text };
}

/** Fill the summary bar: frozen status-line phrase (✻ …) + tool count;
 *  model/timestamp in the title tooltip (v1.1 user ruling). */
function fillSummary(built, meta) {
  const toolCount = built.steps.querySelectorAll('.tool-card').length;
  const phrase = meta.phrase || (meta.durationMs != null ? pickThinkingPhrase(meta.durationMs, meta.seed) : '');
  const parts = [];
  if (phrase) parts.push(phrase);
  parts.push(t(toolCount === 1 ? 'chat.turnSummaryToolsOne' : 'chat.turnSummaryTools', { n: toolCount }));
  built.text.textContent = parts.join(' · ');
  built.summary.title = meta.title || '';
  built.summary.setAttribute('aria-controls', built.steps.id);
  bindCollapsibleToggle(built.summary, () => built.steps, (expanded) => {
    built.group.dataset.turnState = expanded ? 'done-expanded' : 'done';
  });
}

/**
 * Done path: gather this turn's process rows and collapse immediately
 * (synchronous, no linger — v1.1 user ruling). Returns the group or null.
 */
export function collapseTurn(view, meta = {}) {
  const chat = view.dom.chat;
  const rows = Array.from(chat.children).filter(el => el.classList && el.classList.contains('row'));
  let lastUserIdx = -1;
  rows.forEach((r, i) => { if (r.classList.contains('user')) lastUserIdx = i; });
  const scope = rows.slice(lastUserIdx + 1);
  const processRows = scope.filter(isProcessRow);
  if (processRows.length === 0) return null; // E5
  const built = buildGroup(chat, processRows, findFinalRow(scope), meta);
  if (!built) return null;
  fillSummary(built, meta);
  built.group.dataset.turnState = 'done';
  built.steps.style.display = 'none';
  built.summary.setAttribute('aria-expanded', 'false');
  // keep the viewport pinned to the bottom when it was pinned (spec §4.2)
  if (chat.scrollHeight - chat.scrollTop - chat.clientHeight < 80) {
    chat.scrollTop = chat.scrollHeight;
  }
  return built.group;
}

/**
 * Failed path: group for structural consistency but keep everything visible;
 * the summary bar is permanently hidden (no collapse entry — spec D2).
 */
export function failTurn(view) {
  const chat = view.dom.chat;
  const rows = Array.from(chat.children).filter(el => el.classList && el.classList.contains('row'));
  let lastUserIdx = -1;
  rows.forEach((r, i) => { if (r.classList.contains('user')) lastUserIdx = i; });
  const scope = rows.slice(lastUserIdx + 1);
  const processRows = scope.filter(isProcessRow);
  if (processRows.length === 0) return null;
  const built = buildGroup(chat, processRows, findFinalRow(scope), {});
  if (!built) return null;
  built.group.dataset.turnState = 'failed';
  built.group.classList.add('turn-failed');
  built.summary.style.display = 'none';
  return built.group;
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

/**
 * History reload (E4 P0 heuristic): rebuild turn groups from the flat row
 * sequence. Turn boundaries = `.row.user` (injected included — they trigger
 * turns too). Per segment: a duration badge on the final Ai row = success →
 * collapsed; process rows without it = failed/unfinished → expanded.
 * Uncertain → expanded (observability over tidiness).
 */
export function buildTurnGroupsForHistory(chat) {
  const rows = Array.from(chat.children).filter(el => el.classList && el.classList.contains('row'));
  let segStart = 0;
  const flush = (end) => {
    if (end > segStart) groupSegment(chat, rows.slice(segStart, end));
  };
  rows.forEach((r, i) => {
    if (r.classList.contains('user')) { flush(i); segStart = i + 1; }
  });
  flush(rows.length);
}

function groupSegment(chat, seg) {
  const processRows = seg.filter(isProcessRow);
  if (processRows.length === 0) return;
  const finalRow = findFinalRow(seg);
  // E4 P0: success = some Ai row in the segment carries a duration badge
  // (SessionRecorder backfills durationMs on done — the implicit marker).
  const badge = seg.reduce((acc, r) => r.querySelector('.duration-badge-text') || acc, null);
  const success = !!badge;
  const meta = success ? {
    phrase: badge ? badge.textContent : '',
    title: historyBadgeTitle(seg),
    sessionId: chat.dataset?.sessionId || '',
  } : {};
  const built = buildGroup(chat, processRows, finalRow, meta);
  if (!built) return;
  if (success) {
    fillSummary(built, meta);
    built.group.dataset.turnState = 'done';
    built.steps.style.display = 'none';
    built.summary.setAttribute('aria-expanded', 'false');
  } else {
    built.group.dataset.turnState = 'failed';
    built.group.classList.add('turn-failed');
    built.summary.style.display = 'none';
  }
}

function historyBadgeTitle(seg) {
  const badgeRow = seg.find(r => r.querySelector('.duration-badge-text'));
  if (!badgeRow) return '';
  const model = badgeRow.querySelector('.duration-badge-model')?.textContent || '';
  const time = badgeRow.querySelector('.duration-badge-time')?.textContent || '';
  return [model, time].filter(Boolean).join(' · ');
}
