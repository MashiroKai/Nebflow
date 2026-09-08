// turnGroup.js — #346 turn-level collapse, v2 "decompression model"
// (2026-09-05 23:44 author ruling: 思考直播回归 + 收起/展开语义重做;
// 2026-09-06 08:19 author feedback: 动画太卡顿 → 动画层全摘, 直落/直剥;
// 收起态总结行允许换行完整显示; v1 设计标语 ✻ 字句恢复进题头).
//
// DATA MODEL — the turn keeps its ordered row list in place:
//   [text | thinking | tool | card | text …]
// Collapse is a RENDER-LAYER transform only. Rows are NEVER moved into a
// group container (the v1 `.turn-group > .turn-steps` gather is gone); the
// DOM order is the data order, so expanding restores every middle block to
// its original position by simply un-tucking it. What collapse does:
//
//   1. inserts ONE `.turn-header` summary bar at the TOP of the turn scope
//      (right after the user row): `✻ 字句 · <model> · 思考 <N>s · 工具 <M>
//      次 · 读写 <K> 文件` + a chevron affordance. The bar is PERSISTENT — it
//      stays in both states; clicking it toggles. The leading ✻ phrase is the
//      v1-designed cosmology copy (think.0–18, duration embedded), restored
//      2026-09-06 after 批② had dropped it; the bar WRAPS when long (no
//      ellipsis truncation — the full summary is always readable).
//   2. tucks ONLY thinking rows + tool-call rows (`.nf-tucked`: max-height 0,
//      hidden). Assistant text rows, card deliverables
//      (`.row.card-content`), and injected bubbles stay visible — the
//      deliverable is shown standalone, never stripped (2026-09-05 ruling:
//      the ONLY stripped pieces are thinking + tool calls).
//
// The phrase metadata on duration badges (data-nf-phrase) feeds the header
// again (live path via main.js meta.phrase; history via the done badge).
// EVERY turn's header stays visible (2026-09-08 author ruling: 每轮 turn 都有
// 自己的 ✻ 行、点击可展开/收起该轮过程). The 2026-09-04 banner-dedupe
// (`.turn-banner-superseded`, only the latest header visible) was REMOVED —
// hiding the older headers made them unclickable, which was the root cause
// of the 「旧轮点不开」 regression.
//
// Numbers are computed FROM THE TURN'S ROW LIST (2026-09-05口径):
//   工具 <M> 次   = count of `.row.tool` in scope
//   读写 <K> 文件 = deduped file paths across tool payloads (input JSON
//                   file_path / path / notebook_path; rows carry their input
//                   on dataset.nfInput — stamped by chat.js renderTool and
//                   persistence.js history rendering)
//   思考 <N>s     = accumulated thinking duration (thinking bubbles stamp
//                   dataset.nfStart / dataset.nfEnd live; history rebuilds
//                   have no timing data → the segment is simply omitted)
//
// BOUNDARY DEFAULTS (self-decided per task, 2026-09-05): a turn with NO
// thinking and NO tool rows renders no header and never collapses. A turn
// whose ONLY rows are tuckable (e.g. thinking-only — the E6 lone-thinking
// case) keeps everything visible too: collapsing it would leave nothing but
// the header. Failed turns (`failTurn`) render no header at all (spec A5
// spirit) — rows stay flat for troubleshooting; a same-turn re-terminal
// (done then error, nothing appended between) dissolves the done-chrome via
// the legacy heal path.
//
// NO ANIMATION (2026-09-06 08:19 author feedback 「动画太卡顿」): the 批②
// WAAPI transition layer (320ms max-height grow + opacity + ~5px settle +
// 50ms stagger, and the reverse compression) is REMOVED wholesale. Expand =
// the tucked rows reappear in place instantly; collapse = they are stripped
// instantly. The static state is owned entirely by the `.nf-tucked` CSS
// class; header numbers never re-render on toggle (常驻不闪).
//
// Streaming is untouched: rows append live and stay visible while the turn
// runs; only the terminal event tucks. Thinking streams EXPANDED again
// (2026-09-05 ruling — the #345 streaming-collapse regression is reverted in
// chat.js); the terminal tuck folds it with the rest of the process.
//
// Zero protocol/backend changes (spec D1). History reload re-derives headers
// heuristically (E4 P0: duration badge on the final Ai row = success).

import { t } from './i18n.js';
import { formatDuration, chevronSvg } from './chat.js';

/* ---------- row classification ---------- */

/** User-participation nodes / non-process artifacts — never tucked (E7/E8):
 *  option boxes, permission prompts, ask answers, agent rows. */
function isExcludedRow(row) {
  if (row.querySelector('.option-box') || row.querySelector('.permission-prompt')) return true;
  if (row.querySelector('.ask-label')) return true;            // ask answer / injected label
  if (row.querySelector('.bubble.injected')) return true;
  if (row.classList.contains('agent-row')) return true;
  return false;
}

/** An injected (external Mail / Team / event) message row. Rendered as
 *  `.row.user` with a `.bubble.injected` — never splits a turn (2026-08-25
 *  ruling). v2: injected bubbles are KEPT VISIBLE on collapse (the only
 *  stripped pieces are thinking + tool calls). */
function isInjectedRow(row) {
  return row.classList.contains('row') && row.querySelector('.bubble.injected');
}

function isNonInjectedUserRow(row) {
  return row.classList && row.classList.contains('row') &&
    row.classList.contains('user') && !isInjectedRow(row);
}

/** v2 tuck set: thinking rows + tool-call rows ONLY. Card deliverables
 *  (`.row.card-content`) and text rows stay visible. */
function isTuckableRow(row) {
  if (!row.classList.contains('row') || isExcludedRow(row)) return false;
  return row.classList.contains('tool') || row.classList.contains('thinking-row');
}

/* ---------- stats (数字口径从 turn 子列表统计) ---------- */

const FILE_PATH_KEYS = ['file_path', 'path', 'notebook_path'];

/** Compute the header stats from the turn's own row list:
 *  tools = `.row.tool` count; files = deduped file paths across tool input
 *  payloads; thinkingMs = accumulated (nfEnd − nfStart) of thinking bubbles
 *  (live rows only — history rows carry no timing and contribute 0). */
function computeTurnStats(scope) {
  let tools = 0;
  let thinkingMs = 0;
  const files = new Set();
  for (const row of scope) {
    if (!row.classList || !row.classList.contains('row')) continue;
    if (row.classList.contains('tool')) {
      tools++;
      const raw = row.dataset && row.dataset.nfInput;
      if (raw) {
        try {
          const inp = typeof raw === 'string' ? JSON.parse(raw) : raw;
          for (const k of FILE_PATH_KEYS) {
            const v = inp ? inp[k] : null;
            if (typeof v === 'string' && v.trim()) files.add(v.trim());
          }
        } catch { /* malformed input — skip files for this card */ }
      }
    } else if (row.classList.contains('thinking-row')) {
      const b = row.querySelector('.thinking-bubble');
      if (b && b.dataset) {
        const s = Number(b.dataset.nfStart);
        const e = Number(b.dataset.nfEnd);
        // Same-millisecond windows (fast tests, tiny segments) still count as
        // thinking happened: floor at 1ms so the 思考 segment renders '< 1s'.
        if (Number.isFinite(s) && Number.isFinite(e) && e >= s) thinkingMs += Math.max(e - s, 1);
      }
    }
  }
  return { tools, files: files.size, thinkingMs };
}

/** Fill the persistent header text: `✻ 字句 · <model> · 思考 <N>s · 工具
 *  <M> 次 · 读写 <K> 文件`. The leading ✻ phrase is the v1-designed
 *  cosmology copy (2026-09-06 restoration — 批② had dropped it); segments
 *  with no data are omitted (history turns have no thinking timing; a
 *  text+thinking turn has no tool segment; a turn without a done badge has
 *  no phrase). Written ONCE per terminal — toggling never rewrites it
 *  (数字常驻不闪). */
function fillHeaderText(textEl, meta, stats) {
  const parts = [];
  if (meta.phrase) parts.push(meta.phrase);
  if (meta.model) parts.push(meta.model);
  if (stats.thinkingMs > 0) parts.push(t('chat.turnHeaderThinking', { d: formatDuration(stats.thinkingMs) }));
  if (stats.tools > 0) parts.push(t(stats.tools === 1 ? 'chat.turnSummaryToolsOne' : 'chat.turnSummaryTools', { n: stats.tools }));
  if (stats.files > 0) parts.push(t('chat.turnHeaderFiles', { n: stats.files }));
  textEl.textContent = parts.join(' · ');
}

/* ---------- tuck / untuck (instant — the static .nf-tucked class owns the
   state; no transition layer, 2026-09-06 author feedback 「动画太卡顿」) --- */

/** Collapse: strip the rows behind the header. Direct class add — the
 *  middle blocks disappear in one frame. */
function tuckRows(rows) {
  for (const row of rows) {
    row.classList.add('nf-tucked');
  }
}

/** Expand: restore every tucked middle block to its original position
 *  (rows never moved — decompression model). Direct class remove. */
function untuckRows(rows) {
  for (const row of rows) {
    row.classList.remove('nf-tucked');
  }
}

/* ---------- header build / toggle ---------- */

/** Build the persistent summary header at the TOP of the turn scope (before
 *  `scope[0]` — i.e. immediately after the turn's user row). */
function buildHeader(chat, scope, meta, stats) {
  const header = document.createElement('div');
  header.className = 'turn-header';
  header.appendChild(chevronSvg());
  const text = document.createElement('span');
  text.className = 'turn-header-text';
  header.appendChild(text);
  fillHeaderText(text, meta, stats);
  header.title = meta.title || '';
  const anchor = scope.find(el => el.classList && el.classList.contains('row')) || null;
  if (anchor && anchor.parentNode === chat) chat.insertBefore(header, anchor);
  else chat.appendChild(header);
  return header;
}

/** The turn's rows following `header`, up to the next non-injected user row
 *  or the next header. Independent of the closure cursor — safe from a
 *  toggle handler at any time. */
function scopeFromHeader(header) {
  const out = [];
  let n = header.nextElementSibling;
  while (n) {
    if (isNonInjectedUserRow(n) || (n.classList && n.classList.contains('turn-header'))) break;
    out.push(n);
    n = n.nextElementSibling;
  }
  return out;
}

/** Persistent two-state toggle: click the header to expand / re-collapse.
 *  State rides on the header's dataset; the header text is never rewritten. */
function bindHeaderToggle(header) {
  header.setAttribute('role', 'button');
  header.setAttribute('tabindex', '0');
  const toggle = () => {
    if (header.dataset.turnState !== 'done' && header.dataset.turnState !== 'done-expanded') return;
    const tuckable = scopeFromHeader(header).filter(isTuckableRow);
    if (header.dataset.turnState === 'done') {
      header.dataset.turnState = 'done-expanded';
      header.setAttribute('aria-expanded', 'true');
      untuckRows(tuckable);
    } else {
      header.dataset.turnState = 'done';
      header.setAttribute('aria-expanded', 'false');
      tuckRows(tuckable);
    }
  };
  header.onclick = toggle;
  header.onkeydown = (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
  };
}

/* ---------- turn scope + closure cursor (#403, unchanged semantics) ---------- */

/**
 * Turn scope for a terminal event: the turn-header (if any) + rows that
 * belong to the turn now ending. Boundary = the last NON-injected user row.
 *
 * #403 closure ruling (2026-08-26): the turn CLOSES when the LLM turn ends;
 * anything appended after the closure cursor opens a NEW turn and is
 * forbidden from merging into the closed one. Same-turn re-terminal (done
 * then a late error, nothing appended between) keeps the legacy heal: the
 * just-built chrome (header + tuck marks) is dissolved so the turn is
 * re-terminalized exactly once under the new verdict. In v2 the heal is
 * cheap — rows never moved, so only the header is removed and tuck classes
 * cleared.
 */
const closedCursors = new WeakMap();

function markClosed(chat) {
  closedCursors.set(chat, { len: chat.children.length, anchor: chat.lastElementChild });
}

function markClosedAt(chat, idx) {
  closedCursors.set(chat, { len: idx, anchor: chat.children[idx - 1] || null });
}

function turnScope(chat) {
  const cur = closedCursors.get(chat);
  const valid = !!cur && (cur.len === 0 ||
    (cur.anchor && cur.anchor.parentNode === chat && chat.children[cur.len - 1] === cur.anchor));
  const fresh = valid && chat.children.length > cur.len;
  if (!fresh) dissolveTurnChrome(chat); // same-turn re-terminal heal / no valid cursor
  const kids = Array.from(chat.children);
  let boundary = -1;
  kids.forEach((el, i) => {
    if (isNonInjectedUserRow(el)) boundary = i;
  });
  if (fresh) boundary = Math.max(boundary, cur.len - 1);
  return kids.slice(boundary + 1).filter(el => el.classList &&
    (el.classList.contains('row') || el.classList.contains('turn-header')));
}

/** Dissolve the open turn's chrome: remove its header, clear tuck marks.
 *  Rows were never moved (decompression model) so nothing is hoisted. */
function dissolveTurnChrome(chat) {
  const kids = Array.from(chat.children);
  let lastUserIdx = -1;
  kids.forEach((el, i) => { if (isNonInjectedUserRow(el)) lastUserIdx = i; });
  for (let i = lastUserIdx + 1; i < kids.length; i++) {
    const el = kids[i];
    if (!el.classList) continue;
    if (el.classList.contains('turn-header')) { el.remove(); continue; }
    if (el.classList.contains('nf-tucked')) {
      el.classList.remove('nf-tucked');
    }
  }
}

/* ---------- terminal paths ---------- */

/**
 * Done path (v2 decompression model): insert the turn's stats header at the
 * turn top and tuck the thinking + tool rows (instant strip — no animation,
 * 2026-09-06). Text rows, card deliverables and injected bubbles stay
 * visible in place.
 *
 * Boundary defaults: no thinking AND no tools → no header, no tuck (E5
 * text-only / lone injection). Nothing visible left after tucking (E6
 * thinking-only) → keep everything flat instead. Failed-chrome heal is
 * handled by turnScope's dissolve path.
 */
export function collapseTurn(view, meta = {}) {
  const chat = view.dom.chat;
  const scope = turnScope(chat);
  const rows = scope.filter(el => el.classList && el.classList.contains('row'));
  const tuckable = rows.filter(isTuckableRow);
  const hasKept = rows.some(r => !isTuckableRow(r)); // something remains visible
  let header = null;
  if (tuckable.length > 0 && hasKept) {
    const stats = computeTurnStats(rows);
    header = buildHeader(chat, scope, meta, stats);
    header.dataset.turnState = 'done';
    header.setAttribute('aria-expanded', 'false');
    bindHeaderToggle(header);
    tuckRows(tuckable); // instant strip (no animation, 2026-09-06)
    // keep the viewport pinned to the bottom when it was pinned (spec §4.2)
    if (chat.scrollHeight - chat.scrollTop - chat.clientHeight < 80) {
      chat.scrollTop = chat.scrollHeight;
    }
  }
  markClosed(chat); // #403: LLM ended — the turn is closed even when there
                    // was nothing to tuck; later arrivals are a new turn.
  return header;
}

/**
 * Failed path: NO header (spec A5 — the summary element does not exist),
 * everything stays flat and visible for troubleshooting. A same-turn
 * re-terminal (done → error, nothing appended) lands here: turnScope's
 * dissolve heal already removed the done-chrome before this runs.
 */
export function failTurn(view) {
  const chat = view.dom.chat;
  turnScope(chat); // runs the dissolve heal when the cursor is stale
  markClosed(chat); // #403: terminal reached — later arrivals are a new turn
  return null;
}

/** E10: message-search hit inside a collapsed turn — expand it first so the
 *  row is visible/focusable. Instant: the caller scrolls immediately.
 *  Returns true when a turn was expanded. */
export function expandGroupContaining(el) {
  let header = null;
  let n = el.previousElementSibling;
  while (n) {
    if (n.classList && n.classList.contains('turn-header')) { header = n; break; }
    if (isNonInjectedUserRow(n)) return false; // open turn / no header — nothing to expand
    n = n.previousElementSibling;
  }
  if (!header || header.dataset.turnState !== 'done') return false;
  const tuckable = scopeFromHeader(header).filter(isTuckableRow);
  if (!tuckable.some(r => r.classList.contains('nf-tucked'))) return false;
  header.dataset.turnState = 'done-expanded';
  header.setAttribute('aria-expanded', 'true');
  untuckRows(tuckable);
  return true;
}

/* ---------- history rebuild (E4 P0 heuristic) ---------- */

/**
 * History reload: re-derive turn headers from the flat row sequence (the
 * rows themselves were never moved — v2 keeps history flat too). Turn
 * boundaries = `.row.user` (injected included — they trigger turns too).
 * Per segment: a duration badge on some Ai row = success → header + tuck;
 * otherwise failed/unfinished → everything stays flat and visible.
 * Uncertain → visible (observability over tidiness).
 *
 * opts.busyTail (2026-08-24 boundary fix): when the session is still
 * mid-turn at rebuild time, the trailing badge-less segment is OPEN — skip
 * it; the live terminal will build its header. The closure cursor then sits
 * at the tail's first row so the terminal doesn't dissolve legitimate
 * history chrome.
 */
export function buildTurnSummariesForHistory(chat, opts = {}) {
  const { busyTail = false } = opts;
  const rows = Array.from(chat.children).filter(el => el.classList && el.classList.contains('row'));
  let segStart = 0;
  const flush = (end) => {
    if (end > segStart) {
      summarizeSegment(chat, rows.slice(segStart, end));
    }
  };
  rows.forEach((r, i) => {
    if (isNonInjectedUserRow(r)) { flush(i); segStart = i + 1; return; }
    // #403 history side: an injected row arriving AFTER the segment already
    // carries a done badge opens its own turn — split here.
    if (isInjectedRow(r) && findDoneBadge(rows.slice(segStart, i))) { flush(i); segStart = i; }
  });
  const tail = rows.slice(segStart);
  if (!busyTail || findDoneBadge(tail)) {
    flush(rows.length);
    markClosed(chat);
  } else if (tail.length > 0) {
    const idx = Array.from(chat.children).indexOf(tail[0]);
    if (idx > 0) markClosedAt(chat, idx);
  } else {
    markClosed(chat);
  }
}

function summarizeSegment(chat, seg) {
  const tuckable = seg.filter(isTuckableRow);
  if (tuckable.length === 0) return []; // E5: nothing to tuck — no header
  if (!seg.some(r => !isTuckableRow(r))) return []; // E6: nothing would remain visible
  // E4 P0: success = some Ai row in the segment carries a done-badge
  // (data-nf-phrase rides the badge and is restored into the header as the
  // leading ✻ 字句 — the v1-designed copy, 2026-09-06).
  const badge = findDoneBadge(seg);
  if (!badge) return []; // failed/unfinished segment: flat, no header
  const meta = {
    model: badge.dataset.nfModel || '',
    phrase: badge.dataset.nfPhrase || '',
    title: historyBadgeTitle(seg, badge),
  };
  const stats = computeTurnStats(seg);
  const header = buildHeader(chat, seg, meta, stats);
  header.dataset.turnState = 'done';
  header.setAttribute('aria-expanded', 'false');
  bindHeaderToggle(header);
  tuckRows(tuckable); // history rebuild: static tuck
  return [header];
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
  // The tooltip carries the timestamp only (v1.2 footer ruling).
  const row = badge?.closest('.row') || seg.find(r => r.contains(badge));
  return row?.querySelector('.duration-badge-time')?.textContent || '';
}
