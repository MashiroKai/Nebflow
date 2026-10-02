// main.js 抽取(FE组件化批次七 2026-09-28):后台代理下拉族 模块(行为保持)。
import state from '../state.js';
import { sendWs } from '../ws.js';
import { t } from '../i18n.js';
import { escapeHtml, isBgAgentId } from '../utils.js';
import { findViewBySessionId, activeView } from '../chatView.js';
import { fmtUptime, isFailedSnapshotStatus } from '../managePanel.js';
import { openStepPopup as openFlowStepPopup } from '../flowAgentPopup.js';
import { openStepPopup as openBgAgentPopup } from '../bgAgentPopup.js';

// --- Multi-agent events ---
// Background sub-agent activity shows a header indicator (like Bash background tasks).
// No tool cards or text rendered in the chat — the parent's Delegate tool
// card (spinner → result) is the only chat-level feedback.
// Click the indicator to see a dropdown with per-agent status.

export function updateBgAgentIndicator(targetSid, opts) {
  // Update the indicator of the view that DISPLAYS the session owning these
  // sub-agents — not blindly activeView. Sub-agent events are routed through
  // the popup's ChatView (activeView temporarily points there; popup views
  // have no indicator element), which previously left the primary badge stale
  // while the dropdown re-rendered from live data on click — showing e.g.
  // count=2 on the badge but 3 rows inside (a stuck sub-agent that emits no
  // further events never triggered another badge refresh).
  const view = targetSid ? findViewBySessionId(targetSid) : activeView;
  if (!view) return;
  const el = view.dom.bgagentIndicatorEl;
  if (!el) return;
  const sid = view.sessionId;
  const bgAgents = (sid && state.sessionBgAgents[sid]) || {};
  // Author ruling 2026-09-14 16:30: the badge counts RUNNING rows only (an idle
  // dispatcher inside its keep-alive window counts zero). Same predicate as the
  // list (visibleBgAgentEntries) — the two must never disagree (see the view
  // resolution note above: count>0 with an empty dropdown was a real defect).
  const count = visibleBgAgentEntries(bgAgents).length;
  if (count > 0) {
    el.classList.remove('hidden');
    el.querySelector('.bgagent-count').textContent = count;
  } else {
    el.classList.add('hidden');
    el.setAttribute('aria-expanded', 'false');
    const dropdown = view.dom.bgagentDropdownEl;
    // Author ruling 2026-09-19 (批 dropcol-impl), 令①: only the SNAPSHOT-BACKFILL
    // call site (onMessage('activeAgents') below) may not close the panel. The
    // user just clicked the indicator — "open" outranks a backfill that arrives
    // with an empty registry (isolated/test instance or a session whose
    // sub-agents just went terminal), which used to snap the panel shut one
    // frame after it opened. Every OTHER close source is deliberately untouched:
    // the terminal paths (agentDone → 2s cleanup, session-level done for
    // node-*/dispatcher-*) still collapse the panel through this same line, and
    // so do the outside-click (:2319) and toggle (:2301) paths. Hence the guard
    // is per CALL SITE, not global — a blanket "never close on count 0" would
    // break 令②(子任务全部结束面板自动收起).
    if (dropdown && !(opts && opts.keepDropdownOpen)) dropdown.classList.add('hidden');
  }
  if (view === activeView) renderBgAgentDropdown();
}

// ── Sub-agents panel (2026-08-25 spec, frozen @142535e9) ─────────────
// Row = [status dot + label][kind chip][name · task][uptime][retries ×N]
//       + secondary lines: stuck alert label / current tool subtitle.
// User rulings locked in the spec: barrier hidden, retries only when >0,
// NO kind grouping (chip instead), NO inline stuck buttons (operations
// live in the agent popup — A13).

/** Derive the AgentKind from the session-id prefix when the snapshot kind is
 *  absent (live agentStart carries no kind field). */
export function bgAgentKindFromSession(sessionId) {
  const raw = sessionId || '';
  if (raw.startsWith('team-')) return 'Team';
  if (raw.startsWith('delegate-')) return 'Delegate';
  if (raw.startsWith('subtask-')) return 'SubTask';
  if (raw.startsWith('dag-')) return 'Flow';
  if (raw.startsWith('ephemeral-')) return 'Ephemeral';
  // #28 可观测接线: Project 节点/分发器后端注册为 AgentKind.Flow → 面板 kind
  // 徽章显示 Flow（与快照 kind 一致）。
  if (raw.startsWith('node-')) return 'Flow';
  if (raw.startsWith('dispatcher-')) return 'Flow';
  return '';
}

/** Background-task bucket keying (2026-09-05 fix): every backend
 *  backgroundTaskUpdate envelope now carries rootSessionId (ToolContext.rootSessionId,
 *  falling back to the executor's own sessionId), and the BgTaskRegistry snapshot
 *  (activeBgTasks) is grouped by the same key — the frontend keys directly on it.
 *  The old heuristic reverse-lookup (bgTaskRootFor, added 2026-08-25) walked
 *  state.sessionBgAgents to map delegate-/subtask- sessions back to their root,
 *  but silently mis-keyed whenever that mapping was absent (host restart, page
 *  refresh, agent already finished) — tasks landed in orphan buckets and the
 *  owning window's badge went stale (count>0 with an empty dropdown). Deleted:
 *  the authoritative key makes the heuristic both unnecessary and harmful.
 *  Defensive fallback for envelopes lacking rootSessionId (old backend /
 *  REST-invoked tools): key on msg.sessionId as-is. */

/** Row state machine (spec §3): done > stuck > frozen > error > idle > active. */
function bgRowState(info) {
  if (info.done) return 'done';
  if (info.stuck) return 'stuck';
  if (info.frozen) return 'frozen';
  if (isFailedSnapshotStatus(info.status)) return 'error';
  const st = info.status || '';
  if (st === 'Idle' || st === 'WaitingForUser' || st === 'idle') return 'idle';
  return 'active';
}

/** Session-id prefix predicate (single point): `dispatcher-` = Project task
 *  dispatcher session. `node-` = Project Flow Map node session (its panel
 *  visibility口径 is deliberately UNCHANGED by this batch), `delegate-` /
 *  `subtask-` = Delegate / SubTask sub-agents. */
function isDispatcherSession(id) {
  return typeof id === 'string' && id.startsWith('dispatcher-');
}

/** Panel admission口径 (author ruling 2026-09-14 16:30, asknb-6d626dd4307a4d31):
 *  「只显示运行中的，计数也只计算运行中的，空闲不显示。」
 *
 *  The dispatcher session survives its turn inside the keep-alive window
 *  (Defaults.DispatcherIdleWindowMs, default 30min — ProjectActor.scala:702-712
 *  keeps the registry entry, :888-947 sweeps it later), so the registry
 *  snapshot legitimately reports it with status Idle. This is a display-side
 *  (呈现出/计数) rule only — NOT a liveness change: inside the window the
 *  dispatcher is still alive and still holds the singleton slot, so an empty
 *  panel does not mean no dispatcher exists.
 *
 *  「运行中」 is nailed down as `bgRowState(info) === 'active'` — the bottom
 *  rung of the done > stuck > frozen > error > idle > active ladder, i.e. not
 *  Idle / WaitingForUser and not done / stuck / frozen / error. Admitting only
 *  'active' covers the 2s `agentDone` window too (the row keeps done=true
 *  before its delayed delete — main.js agentDone handler).
 *
 *  `dispatcher-` rows are admitted ONLY while active; every other prefix keeps
 *  its existing口径 (hidden = not rendered AND not counted — same bucket, same
 *  口径 as the list, so the badge can never disagree with the dropdown). */
function isBgRowVisible(id, info) {
  const dispatcher = isDispatcherSession(id) || isDispatcherSession(info && info.sessionId);
  if (!dispatcher) return true;
  return bgRowState(info) === 'active';
}

/** The visible rows of a bucket — the ONE read used by the badge count, the
 *  panel-header count and the list rendering. The bucket itself keeps the raw
 *  set (presentation never rewrites the data面). */
function visibleBgAgentEntries(bgAgents) {
  return Object.entries(bgAgents || {}).filter(([id, info]) => isBgRowVisible(id, info));
}

// Uptime tick: while the dropdown is open, refresh .bg-task-uptime text in
// place every 15s (no full re-render — keeps keyboard focus). Self-terminates
// once the dropdown hides, regardless of which close path ran.
let bgUptimeTick = null;
export function armBgUptimeTick(view) {
  if (bgUptimeTick) clearTimeout(bgUptimeTick);
  bgUptimeTick = setTimeout(() => {
    bgUptimeTick = null;
    const dd = view && view.dom.bgagentDropdownEl;
    if (dd && !dd.classList.contains('hidden')) {
      dd.querySelectorAll('.bg-task-uptime[data-started-at]').forEach(el => {
        const started = Number(el.getAttribute('data-started-at'));
        if (started > 0) el.textContent = fmtUptime(Date.now() - started);
      });
      armBgUptimeTick(view);
    }
  }, 15000);
}

export function renderBgAgentDropdown() {
  if (!activeView) return;
  const dropdownEl = activeView.dom.bgagentDropdownEl;
  const listEl = activeView.dom.bgagentDropdownListEl;
  if (!listEl) return;
  const sid = activeView?.sessionId;
  const bgAgents = (sid && state.sessionBgAgents[sid]) || {};
  // Author ruling 2026-09-14 16:30: an idle dispatcher row is neither listed nor
  // counted (it survives in the keep-alive window and the snapshot reports it —
  // see isBgRowVisible). Header count, empty state and rows all read this one
  // filtered set — 0 visible ⇒ count 0 + empty text, never a self-contradiction.
  const entries = visibleBgAgentEntries(bgAgents);
  // Panel header carries the live count (aria-live polite, spec §5).
  const headerEl = dropdownEl ? dropdownEl.querySelector('.bg-dropdown-header') : null;
  if (headerEl) headerEl.textContent = t('subagents.header', { count: entries.length });
  if (entries.length === 0) {
    // Empty state (spec §6): restrained i18n line, never fake rows.
    listEl.innerHTML = '<div class="bg-dropdown-empty">' + escapeHtml(t('subagents.empty')) + '</div>';
    return;
  }
  listEl.innerHTML = entries.map(([id, info]) => {
    const rowState = bgRowState(info);
    const terminal = rowState === 'done' || rowState === 'error';
    const statusLabel = t('subagents.status.' + (rowState === 'active' ? 'running' : rowState));
    const dotClass = {
      active: 'bg-status-active', idle: 'bg-status-idle', stuck: 'bg-status-stuck',
      error: 'bg-status-error', frozen: 'bg-status-frozen', done: 'bg-status-done',
    }[rowState];
    const kind = info.kind || bgAgentKindFromSession(info.sessionId || id);
    const kindPart = kind ? '<span class="bg-task-kind">' + escapeHtml(kind) + '</span>' : '';
    // Project attribution (2026-09-06 author ruling): Flow rows carry the
    // owning project's name next to the kind chip — muted plain text,
    // visually subordinate to the chip, never competing with the node name.
    // Absent (Root/direct delegates) → no badge at all.
    const projectPart = info.project
      ? '<span class="bg-task-project" title="' + escapeHtml(info.project) + '">' + escapeHtml(info.project) + '</span>'
      : '';
    // Task attribution badge (2026-09-27 taskbadge batch): `#<id> · <title>` chip
    // per row — the per-row task marker under the multi-task dispatcher
    // architecture (rows from different tasks sit side by side in one bucket;
    // the badge is what makes them distinguishable). No attribution (kernel /
    // system / legacy / slot cleared) → no badge at all — an explicit empty
    // badge would be noise on every non-task row. Long titles truncate at the
    // RENDER layer only (CSS ellipsis + full text in the title tooltip).
    const taskBadgeText = info.taskId
      ? (info.taskTitle ? '#' + info.taskId + ' · ' + info.taskTitle : '#' + info.taskId)
      : '';
    const taskPart = taskBadgeText
      ? '<span class="bg-task-badge" title="' + escapeHtml(t('subagents.taskBadge', { text: taskBadgeText })) + '">'
        + escapeHtml(taskBadgeText) + '</span>'
      : '';
    const displayName = info.name || id;
    const taskText = info.task ? displayName + ' · ' + info.task : displayName;
    const namePart = '<span class="bg-task-name" title="' + escapeHtml(taskText) + '">' + escapeHtml(taskText) + '</span>';
    // Uptime (spec §2.2): relative duration while non-terminal; hidden once done.
    const uptimePart = (!terminal && info.startedAt)
      ? '<span class="bg-task-uptime" data-started-at="' + info.startedAt + '">' + escapeHtml(fmtUptime(Date.now() - info.startedAt)) + '</span>'
      : '';
    // Retries chip: only when >0 (0 is noise — user ruling ②).
    const retries = info.retryCount || 0;
    const retriesPart = retries > 0
      ? '<span class="bg-task-retries" title="' + escapeHtml(t('manage.retry')) + ' ×' + retries + '">×' + retries + '</span>'
      : '';
    // Stuck: restrained red label with idle seconds (manage-panel semantics).
    // NO inline cancel/restart buttons — operations live in the agent popup (A13).
    // Hard-recovery P7 (2026-09-07): the label mirrors the REAL action the
    // backend performed (halt / hard-abort / restart / failed) — "auto-
    // restarting" only ever shows when an actual restart was triggered.
    const stuckPart = (rowState === 'stuck' && info.stuck)
      ? '<span class="bg-task-stuck" role="alert">' + escapeHtml(
          info.stuck.action === 'restart'
            ? t('manage.stuckAutoRestart')
            : info.stuck.action === 'hard-abort'
              ? t('manage.stuckHardAbort')
              : info.stuck.action === 'failed'
                ? t('manage.stuckFailed')
                : t('manage.stuck', { secs: info.stuck.idleSecs ?? '' })
        ) + '</span>'
      : '';
    const toolLabel = info.currentTool || '';
    const toolPart = (toolLabel && !terminal)
      ? '<span class="bgagent-tool" title="' + escapeHtml(toolLabel) + '">↳ ' + escapeHtml(toolLabel) + '</span>'
      : '';
    // The sub-agent's OWN session id — agentStart carries it as
    // msg.nodeSessionId (delegate-*/subtask-*/team-<sid>/dag-*). Every row
    // is clickable; the click handler strips the team- prefix and routes
    // dag-*/bare sids to the flow popup (same view the Flow panel uses).
    const sessionId = info.sessionId || (isBgAgentId(id) ? id : '');
    const clickAttr = sessionId
      ? `data-bg-key="${escapeHtml(id)}" data-node-session-id="${escapeHtml(sessionId)}"`
      : '';
    return '<div class="bg-task-row' + (rowState === 'done' ? ' done' : '') + '" role="listitem" tabindex="0" ' + clickAttr + '>' +
      '<span class="bg-task-status ' + dotClass + '" aria-hidden="true"></span>' +
      '<div class="bg-task-info">' +
        // Single-line row (2026-09-27 badge-inline batch, author order: task
        // badge joins the uptime line): state / kind / project / name / badge
        // / retries / uptime share ONE line — uptime stays pinned right via
        // .bg-task-uptime margin-left:auto; project / name / badge truncate
        // (CSS ellipsis + the existing title tooltips, subagents.taskBadge
        // family included); fixed chips never shrink. Pure layout: data
        // sources, tooltip keys, dot / kind / state semantics and counting
        // are untouched. Supersedes the old meta+name two-line split for THIS
        // panel only (#bgagent-dropdown); the background-tasks panel
        // (#bg-dropdown) keeps .bg-task-meta / .bg-task-name-line untouched.
        '<div class="bg-task-line bg-task-main">' +
          '<span class="bg-task-state bg-state-' + rowState + '">' + escapeHtml(statusLabel) + '</span>' +
          kindPart + projectPart + namePart + taskPart + retriesPart + uptimePart +
        '</div>' +
        stuckPart + toolPart +
      '</div>' +
    '</div>';
  }).join('');

  // Wire click handlers for sub-agent rows
  listEl.querySelectorAll('[data-node-session-id]').forEach(row => {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      const rawSessionId = row.getAttribute('data-node-session-id');
      const info = bgAgents[row.getAttribute('data-bg-key')];
      if (info && rawSessionId) {
        // Capture the dropdown BEFORE opening the popup — openStepPopup
        // switches activeView to the popup view whose fakeDom has no dropdown.
        const dropdown = activeView.dom.bgagentDropdownEl;
        // team-<sid> wraps the agent's bare session id (its ui.json key) —
        // strip it, matching how the retired Team panel used to open these
        // popups; live team-agent events keep flowing into the same view.
        const sessionId = rawSessionId.replace(/^team-/, '');
        if (isBgAgentId(rawSessionId)) {
          // delegate-*/subtask-* → bg-agent popup (ephemeral sessions,
          // live-rendered; history only persists at completion).
          openBgAgentPopup(rawSessionId, info.name, info.task);
        } else {
          // dag-*/bare sid → flow popup — the SAME entry the Flow/Team panel
          // uses, so live events flow into one shared view instead of
          // building a duplicate hidden view per popup module.
          openFlowStepPopup(row.getAttribute('data-bg-key'), info.name, info.name, '', sessionId);
        }
        // Close the dropdown
        if (dropdown) dropdown.classList.add('hidden');
      }
    });
  });

  // Keyboard (spec §7 / APG listbox): Enter/Space opens the row's popup,
  // ArrowUp/ArrowDown move between rows. Property assignment — idempotent
  // across re-renders (no listener accumulation).
  listEl.onkeydown = (e) => {
    const row = e.target && e.target.closest ? e.target.closest('.bg-task-row') : null;
    if (!row) return;
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      row.click();
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const rows = [...listEl.querySelectorAll('.bg-task-row')];
      const i = rows.indexOf(row);
      const next = rows[e.key === 'ArrowDown' ? i + 1 : i - 1];
      if (next) next.focus();
    }
  };
}

// Snapshot-on-open (2026-09-13 memory-track visibility forensics, fix B).
// The registry snapshot used to be fetched ONCE per WS (re)connect (the
// getActiveAgents send inside onReconnect below) — enough for every sub-agent
// whose live frames reach the client, but NOT for the (now retired, govmemory
// batch) memory-consolidation track: the backend silenced that track's frames
// on purpose (track events must not pollute the parent view), so a run was
// invisible unless the browser happened to refresh inside its window.
// Fetch on open instead: user-driven, reuses the existing getActiveAgents
// request and the existing activeAgents consumer (since 批 snapmerge-impl that
// consumer MERGES the snapshot into the rendered row set instead of rebuilding
// it, so a backfill arriving empty no longer wipes the open panel's rows)
// (zero backend change, no new protocol). The 1s cooldown only debounces rapid
// open/close taps — a normal open sends exactly one frame, closing sends none.
let lastActiveAgentsFetchAt = 0;
export function fetchActiveAgentsOnOpen() {
  const now = Date.now();
  if (now - lastActiveAgentsFetchAt < 1000) return;
  lastActiveAgentsFetchAt = now;
  sendWs({ type: 'getActiveAgents' });
}

// Sub-agents panel (2026-08-25): any activity on a stuck entry clears the
// stuck flag — the row falls back to its live state on the next render.
export function clearBgStuck(msg) {
  const sid = msg.rootSessionId || msg.sessionId;
  const aid = msg.agentId;
  if (!sid || !aid) return;
  const entry = state.sessionBgAgents[sid] && state.sessionBgAgents[sid][aid];
  if (entry && entry.stuck) {
    entry.stuck = null;
    updateBgAgentIndicator(sid);
  }
}
