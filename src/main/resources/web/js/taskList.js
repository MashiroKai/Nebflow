// taskList.js — Collapsible task panel (floats above the input area)
// 任务工具重做 (2026-08-30 作者规格): 进展展示语义——四态状态机
// pending → in_progress → completed / failed。needs_confirmation / dismissed /
// cancelled 退役：agent 直接标 completed，无用户确认/打回/取消环节。
//   - 待办区 (.task-section-todo): human pending — CIRCLE (click: completeTask
//     直接完成，无中间态；completed 即消失，TTL 自然清)
//   - 任务区 (.task-section-progress): agent pending/in_progress — read-only
//     glyphs (方块/spinner 圆环本体), status word + relative time
// 裁定②: team 域任务统一并入 Nebula 会话的本面板（Teams 标签页任务区已删）；
// 裁定④: 任务区三级分组 team → member → tasks（accessor 隔离挂接数据契约）。
// v2 semantics preserved: completing is optimistic (fill → collapse → remove).
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { sendWs, onMessage } from './ws.js';
import state from './state.js';
import { showToast } from './modal.js';

/** 任务工具重做: visible set = pending + in_progress ONLY（「列表只显
 *  pending+in_progress」——completed/failed 完成即消失，6h/2d TTL 管磁盘
 *  清理不管可见性）。legacy 状态由后端解码映射（needs_confirmation→
 *  completed 等），到不了这里。 */
function isVisible(tk) {
  return !!tk && (tk.status === 'pending' || tk.status === 'in_progress');
}

/** C1: missing taskKind (pre-upgrade server) defaults to 'agent'. */
function kindOf(tk) {
  return tk && tk.taskKind === 'human' ? 'human' : 'agent';
}

// ── Zone partitioning ────────────────────────────────────────────────────
// A task lives in exactly ONE zone at any time. 待办区 = needs user action
// (human pending only — agent needs_confirmation 已退役); 任务区 = read-only
// agent progress. Deterministic per (kind, status).
function inTodoZone(tk) {
  return kindOf(tk) === 'human' && tk.status === 'pending';
}

/** Sort key: updatedAt refreshed on every mutation and always present;
 *  defensive fallback to createdAt. */
function taskTs(tk) {
  const raw = tk.updatedAt || tk.createdAt;
  const ms = Date.parse(raw);
  return isNaN(ms) ? 0 : ms;
}

/** 待办区: human pinned by createdAt desc. */
function sortByCreatedDesc(a, b) {
  return (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0);
}
function sortByActiveDesc(a, b) {
  return taskTs(b) - taskTs(a);
}

/** 任务区: updatedAt desc (visible set is active-only — no failed sinking). */
function sortProgress(a, b) {
  return sortByActiveDesc(a, b);
}

// ── Team grouping (第七件 裁定④, 2026-08-30) ─────────────────────────────
// 任务区 groups team → member → tasks. ACCESSOR ISOLATION: the Backend data
// contract hooks up HERE and nowhere else. 契约字段（feat/task-redesign
// TaskModel）：team 归属 = `teamId`（scope=='team' 的 team 域任务携带；
// session 域无此键）。member 归属字段契约暂未交付——accessor 预留，Backend
// 补字段后即插即活；无归属任务平铺，零回归。
function taskTeam(tk) { return (tk && typeof tk.teamId === 'string' && tk.teamId) || null; }
function taskMember(tk) { return (tk && typeof tk.member === 'string' && tk.member) || null; }

/** 裁定②: team 域任务（teamTaskListUpdate, 无 sessionId）统一并入 Nebula
 *  会话的任务列表面板。Only the Nebula session shows the unified list —
 *  member/agent sessions keep showing only their own session tasks. */
export function sessionShowsTeamTasks(sid) {
  if (!sid) return false;
  const agent = state.sessionAgentMap ? state.sessionAgentMap[sid] : null;
  return !agent || agent === 'Nebula';
}

/** Merge team-domain tasks (state.teamTasks, keyed by team) into the panel
 *  list for Nebula sessions. Stamped copies carry teamId (grouping hook) and
 *  a namespaced display id — team tasks live in their own store directory
 *  with their own id space, so a bare id could collide with a session task
 *  (row dataset/zone-diff keyed by id). Team tasks are read-only here (no
 *  circle: completeTask is session-scoped), so the stamped id never goes
 *  back on the wire. */
function mergeTeamTasks(tasks, sessionId) {
  const base = Array.isArray(tasks) ? tasks : [];
  if (!sessionShowsTeamTasks(sessionId)) return base;
  const teamMap = state.teamTasks || {};
  const stamped = [];
  for (const [team, arr] of Object.entries(teamMap)) {
    if (!Array.isArray(arr)) continue;
    for (const tk of arr) {
      if (!tk) continue;
      stamped.push({ ...tk, id: `team:${team}:${tk.id}`, teamId: tk.teamId || team });
    }
  }
  return stamped.length ? base.concat(stamped) : base;
}

// ── §15.7 relative time (任务区) ─────────────────────────────────────────
function formatLastActive(updatedAt) {
  if (!updatedAt) return '';
  const ms = Date.parse(updatedAt);
  if (isNaN(ms)) return '';
  const diff = Date.now() - ms;
  if (diff < 60_000) return t('task.justNow');
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

/** §15.7: 60s interval, only while the panel has visible tasks; textContent
 *  direct update, no animation. */
let lastActiveTimer = null;
function refreshLastActive() {
  const container = document.getElementById('task-list');
  if (!container) return;
  const els = /** @type {NodeListOf<HTMLElement>} */ (container.querySelectorAll('.task-last-active'));
  els.forEach((el) => {
    const ts = el.dataset.ts;
    if (ts) el.textContent = formatLastActive(ts);
  });
}
function ensureLastActiveTimer(running) {
  if (running && !lastActiveTimer) {
    lastActiveTimer = setInterval(refreshLastActive, 60_000);
  } else if (!running && lastActiveTimer) {
    clearInterval(lastActiveTimer);
    lastActiveTimer = null;
  }
}

const REDUCED_MOTION = typeof matchMedia === 'function' &&
  matchMedia('(prefers-reduced-motion: reduce)').matches;

/** taskId → task snapshot, for taskError rollback (§7.2). */
const pendingComplete = new Map();

// ── Completion flow (§7.1/§7.2) ─────────────────────────────────────────

function requestComplete(row, task) {
  if (!state.connected) return;                    // §4: disabled while WS down
  if (pendingComplete.has(task.id)) return;        // debounce double-click
  pendingComplete.set(task.id, task);

  sendWs({ type: 'completeTask', sessionId: state.activeSessionId, taskId: task.id });

  // A4/C26: optimistic -1 on the 待办区 segment — do not wait for the server
  // push (taskListUpdate re-render is authoritative when it arrives; rollback
  // via taskError re-renders from restored state, self-healing).
  const sid0 = row.dataset.sessionId || state.activeSessionId;
  const arr0 = sid0 && state.sessionTasks ? state.sessionTasks[sid0] : null;
  const statsEl = document.querySelector('#task-list .task-stats');
  if (statsEl && Array.isArray(arr0)) {
    const todoCount = arr0.filter(tk => inTodoZone(tk)).length;
    const progressCount = arr0.filter(tk => isVisible(tk) && !inTodoZone(tk)).length;
    statsEl.textContent = t('task.statsBoth', {
      todo: Math.max(0, todoCount - 1),
      progress: progressCount,
    });
  }

  const finish = () => {
    // Server truth arrives via taskListUpdate; drop the optimistic entry from
    // local state so re-renders cannot resurrect it.
    const sid = row.dataset.sessionId || state.activeSessionId;
    const arr = sid && state.sessionTasks ? state.sessionTasks[sid] : null;
    const i = Array.isArray(arr) ? arr.indexOf(task) : -1;
    if (i >= 0) arr.splice(i, 1);
    row.remove();
    const container = document.getElementById('task-list');
    if (container && !container.querySelector('.task-item')) {
      container.classList.remove('has-tasks');
      container.innerHTML = '';
    }
  };

  if (REDUCED_MOTION) { finish(); return; }

  row.classList.add('task-completing');            // fill sapphire + white check
  setTimeout(() => {
    row.classList.add('task-collapsing');          // height collapse ~220ms
    setTimeout(finish, 230);
  }, 180);
}

// Server-side rejection of a complete → roll back: re-insert at the sorted
// position (render derives it), replay the entering animation, toast (§7.2).
// The backend taskError frame is {"type":"taskError","error","taskId"}.
// We identify completeTask failures by taskId membership in pendingComplete:
// a taskError for an id we never sent a complete for is not ours — pass
// through untouched (裁定 F-B1①, qa-frontend 打回修复).
// 任务工具重做: cancel/return 路径退役——taskError 的唯一来源是 completeTask。
onMessage('taskError', (msg) => {
  if (!msg.taskId) return;
  if (pendingComplete.has(msg.taskId)) {
    const task = pendingComplete.get(msg.taskId);
    pendingComplete.delete(msg.taskId);
    const sid = msg.sessionId || state.activeSessionId;
    const arr = sid && state.sessionTasks ? state.sessionTasks[sid] : null;
    if (Array.isArray(arr) && !arr.includes(task)) arr.push(task);
    const container = document.getElementById('task-list');
    // redraw() replays the entering animation for rows absent from the old
    // DOM (the rolled-back task was removed optimistically) — no manual class.
    renderTaskList(arr || [], container, sid);
    showToast(t('task.completeError'), 'error');
    return;
  }
});

// ── Row builders (§15.5/§15.6) ──────────────────────────────────────────

/**
 * Zone-leading icon per (kind, status):
 *  待办区 (user action): CIRCLE — clickable (role=checkbox, Space/Enter).
 *  任务区 (read-only):   SQUARE (.task-check-box) — 裁定 19:56: circles look
 *    clickable; squares are the observer-zone glyph (原始 checkbox 方块样式).
 *    in_progress = standalone spinner ring (裁定⑤); pending = hollow square.
 *    All aria-hidden (row aria-label carries status).
 */
function buildCheck(task, row) {
  const check = document.createElement('span');
  check.className = 'task-check';

  if (inTodoZone(task)) {
    // 待办区 — circle, clickable (human pending; click = completeTask 直接完成).
    check.classList.add('task-check-clickable');
    check.setAttribute('role', 'checkbox');
    check.setAttribute('aria-checked', 'false');
    check.setAttribute('tabindex', '0');
    check.setAttribute('aria-label', t('task.completeAria', { subject: task.subject || '' }));
    // Hidden check glyph: hover previews it, .task-completing fills the circle.
    check.innerHTML = '<i data-lucide="check" class="task-check-glyph"></i>';
    const complete = () => requestComplete(row, task);
    check.addEventListener('click', complete);
    check.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); complete(); }
    });
    return check;
  }

  // 任务区 — square, read-only (§15.6: no hover feedback, no tab stop).
  check.classList.add('task-check-box');
  if (task.status === 'in_progress') {
    // 第七件 裁定⑤ (2026-08-30): the spinner IS the glyph — a standalone
    // ring, no surrounding box ("spinner 在方框中转" combo is forbidden).
    check.classList.add('task-check-spin');
    check.setAttribute('aria-hidden', 'true');
    row.setAttribute('aria-label',
      `${task.subject || ''} — ${t('task.inProgressShort')}`.trim());
  } else {
    // agent pending — decorative hollow square
    check.setAttribute('aria-hidden', 'true');
    row.setAttribute('aria-label',
      `${task.subject || ''} — ${t('task.pendingShort')}`.trim());
  }
  return check;
}

function buildRow(task, sessionId) {
  const row = document.createElement('div');
  row.className = 'task-item' +
    (task.status === 'in_progress' ? ' task-active' : '');
  row.dataset.taskId = task.id;
  if (sessionId) row.dataset.sessionId = sessionId;

  row.appendChild(buildCheck(task, row));

  const text = document.createElement('div');
  text.className = 'task-item-text';
  const label = document.createElement('span');
  label.className = 'task-label';
  label.textContent = task.subject || '';
  text.appendChild(label);
  // #37: render the description under the subject so tasks with similar
  // subjects stay distinguishable. Truncated to 2 lines; full text in title.
  const descText = (task.description || '').trim();
  if (descText) {
    row.classList.add('task-has-desc');
    const desc = document.createElement('span');
    desc.className = 'task-desc';
    desc.textContent = descText;
    desc.title = descText;
    text.appendChild(desc);
  }
  row.appendChild(text);

  if (!inTodoZone(task)) {
    // 任务区 (§15.6): status word + relative time (D6). Both aria-hidden —
    // the row's aria-label carries the status for AT users (§15.9).
    // 待办区 (human pending) rows carry no status word (B9) — the circle
    // self-expresses the action.
    const word = document.createElement('span');
    word.className = 'task-status-word';
    word.setAttribute('aria-hidden', 'true');
    word.textContent = t(task.status === 'in_progress'
      ? 'task.inProgressShort' : 'task.pendingShort');

    const time = document.createElement('span');
    time.className = 'task-last-active';
    time.setAttribute('aria-hidden', 'true');
    const ts = task.updatedAt || task.createdAt || '';
    if (ts) {
      time.dataset.ts = ts;
      time.textContent = formatLastActive(ts);
    }

    row.appendChild(word);
    if (ts) row.appendChild(time);
  }
  return row;
}

function buildGroupHeader(label, section) {
  const h = document.createElement('div');
  // Dual class: .task-group-header is the implementation class (CSS hooks),
  // .task-section-title is the frozen spec assertion selector (§10 A6).
  // data-section carries the zone ('todo' | 'progress') — D1 assertion hook.
  h.className = 'task-group-header task-section-title';
  h.dataset.section = section;
  h.textContent = label;
  return h;
}

/** Grouping headers (裁定④): level-1 team / level-2 member. Visual grouping
 *  aids only — each row keeps carrying its own status via aria-label. */
function buildSubgroupHeader(label, level) {
  const h = document.createElement('div');
  h.className = 'task-subgroup-header task-subgroup-' + level;
  h.textContent = label;
  return h;
}

/** Empty-state row (§15.5/§15.6, C27): rendered only when a single zone is
 *  empty while the panel is visible; non-interactive, aria-hidden (§15.9). */
function buildEmpty(label) {
  const empty = document.createElement('div');
  empty.className = 'task-empty';
  empty.setAttribute('aria-hidden', 'true');
  empty.textContent = label;
  return empty;
}

// ── Main render (v3 dual-zone) ──────────────────────────────────────────

/**
 * @param {Array} tasks
 * @param {HTMLElement} [container]
 * @param {string} [sessionId] owning session — stamped onto rows so the
 *   complete/rollback path can update state.sessionTasks[sessionId]
 */
export function renderTaskList(tasks, container, sessionId) {
  if (!container) container = document.getElementById('task-list');
  if (!container) return;

  // 裁定②: Nebula 会话的任务列表 = 统一视图——session 域任务 + team 域任务
  // （state.teamTasks，teamTaskListUpdate 帧维护）。非 Nebula 会话不变。
  const visible = mergeTeamTasks(tasks, sessionId).filter(isVisible);
  container.classList.toggle('has-tasks', visible.length > 0);
  container.classList.toggle('task-ws-down', !state.connected);
  ensureLastActiveTimer(visible.length > 0);
  if (visible.length === 0) {
    container.innerHTML = '';
    return;
  }

  // 裁定 3 (跨区移动): a task whose zone changed since the last render fades
  // out of its old zone (.task-leaving), then redraw plays a zone-enter on
  // the new side. Ordinary updates redraw immediately (new rows get the
  // v1 task-entering — 裁定 2 状态过渡).
  const oldById = collectZoneById(container);
  let zoneMoved = false;
  for (const tk of visible) {
    const old = oldById.get(tk.id);
    if (old !== undefined && old !== (inTodoZone(tk) ? 'todo' : 'progress')) {
      zoneMoved = true;
      break;
    }
  }

  if (zoneMoved && !REDUCED_MOTION) {
    const byId = new Map(visible.map(tk => [tk.id, tk]));
    let leaving = 0;
    const items = /** @type {NodeListOf<HTMLElement>} */ (container.querySelectorAll('.task-item'));
    items.forEach((el) => {
      const tk = byId.get(el.dataset.taskId);
      const oldZone = el.closest('.task-section')?.classList.contains('task-section-todo') ? 'todo' : 'progress';
      if (!tk || oldZone !== (inTodoZone(tk) ? 'todo' : 'progress')) {
        el.classList.add('task-leaving');
        leaving++;
      }
    });
    if (leaving > 0) {
      // Let the 160ms fade-out finish, then redraw with enter animations.
      setTimeout(() => { redraw(visible, container, sessionId, oldById); }, 170);
      return;
    }
  }
  redraw(visible, container, sessionId, oldById);
}

/** Map taskId → zone ('todo'|'progress') from the current DOM. */
function collectZoneById(container) {
  const m = new Map();
  container.querySelectorAll('.task-item[data-task-id]').forEach((el) => {
    const zone = el.closest('.task-section')?.classList.contains('task-section-todo') ? 'todo' : 'progress';
    m.set(el.dataset.taskId, zone);
  });
  return m;
}

function redraw(visible, container, sessionId, oldById) {
  // 分区 + 排序（任务工具重做：四态进展展示——无 needs_confirmation 子块、
  // 无 failed 沉底，visible 集本就只含 pending+in_progress）:
  //  待办区 = human pending (createdAt desc); 任务区 = agent active
  //  (updatedAt desc), 裁定④ 三级分组 team → member → tasks 在排序之后。
  const todoHuman = visible.filter(tk => kindOf(tk) === 'human').sort(sortByCreatedDesc);
  const progress = visible.filter(tk => kindOf(tk) === 'agent').sort(sortProgress);

  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats (C26 dual-segment) ──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = t('task.statsBoth', {
    todo: todoHuman.length,
    progress: progress.length,
  });

  header.appendChild(toggle);
  header.appendChild(stats);

  // ── Body: two zones, headers constant while panel visible (C27) ──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  const todoSection = document.createElement('div');
  todoSection.className = 'task-section task-section-todo';
  todoSection.appendChild(buildGroupHeader(t('task.sectionTodo'), 'todo'));
  if (todoHuman.length === 0) {
    todoSection.appendChild(buildEmpty(t('task.todoEmpty')));
  } else {
    todoHuman.forEach(tk => todoSection.appendChild(buildRow(tk, sessionId)));
  }

  const progressSection = document.createElement('div');
  progressSection.className = 'task-section task-section-progress';
  progressSection.appendChild(buildGroupHeader(t('task.sectionProgress'), 'progress'));
  if (progress.length === 0) {
    progressSection.appendChild(buildEmpty(t('task.progressEmpty')));
  } else {
    // 裁定④ grouping: ungrouped (session-local) tasks flat first, then
    // team → member → tasks. First-appearance order preserves sortProgress.
    const ungrouped = [];
    const teamOrder = [];
    const byTeam = new Map(); // team → Map(member → tasks)
    for (const tk of progress) {
      const team = taskTeam(tk);
      if (!team) { ungrouped.push(tk); continue; }
      let g = byTeam.get(team);
      if (!g) { g = new Map(); byTeam.set(team, g); teamOrder.push(team); }
      const member = taskMember(tk) || '';
      if (!g.has(member)) g.set(member, []);
      g.get(member).push(tk);
    }
    ungrouped.forEach(tk => progressSection.appendChild(buildRow(tk, sessionId)));
    for (const team of teamOrder) {
      const teamEl = document.createElement('div');
      teamEl.className = 'task-subgroup';
      teamEl.appendChild(buildSubgroupHeader(team, 'team'));
      for (const [member, memberTasks] of byTeam.get(team)) {
        const memEl = document.createElement('div');
        memEl.className = 'task-member-group';
        if (member) memEl.appendChild(buildSubgroupHeader(member, 'member'));
        memberTasks.forEach(tk => memEl.appendChild(buildRow(tk, sessionId)));
        teamEl.appendChild(memEl);
      }
      progressSection.appendChild(teamEl);
    }
  }

  inner.appendChild(todoSection);
  inner.appendChild(progressSection);

  body.appendChild(inner);
  card.appendChild(header);
  card.appendChild(body);
  container.appendChild(card);

  createIconsIn(container);

  toggle.addEventListener('click', () => {
    const nowCollapsed = card.classList.toggle('collapsed');
    container.dataset.collapsed = nowCollapsed ? '1' : '';
    toggle.innerHTML = `<i data-lucide="${nowCollapsed ? 'chevron-down' : 'chevron-up'}"></i>`;
    toggle.title = nowCollapsed ? t('task.expand') : t('task.collapse');
    createIconsIn(toggle);
  });

  // 裁定 2/3: entry animations — brand-new rows get the v1 task-entering
  // (创建→进行→待确认 transitions); zone-moved rows get the stronger
  // task-zone-enter (跨区移动: appears at the new zone's top).
  if (!REDUCED_MOTION) {
    const items = /** @type {NodeListOf<HTMLElement>} */ (container.querySelectorAll('.task-item'));
    items.forEach((el) => {
      const oldZone = oldById.get(el.dataset.taskId);
      const zone = el.closest('.task-section')?.classList.contains('task-section-todo') ? 'todo' : 'progress';
      if (oldZone === undefined) {
        el.classList.add('task-entering');
        el.addEventListener('animationend', () => el.classList.remove('task-entering'), { once: true });
      } else if (oldZone !== zone) {
        el.classList.add('task-zone-enter');
        el.addEventListener('animationend', () => el.classList.remove('task-zone-enter'), { once: true });
      }
    });
  }
}
