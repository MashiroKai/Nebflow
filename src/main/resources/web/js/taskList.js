// taskList.js — Collapsible task panel (floats above the input area)
// 任务工具重做 (2026-08-30 作者规格) + #15 补齐 (2026-08-30): 纯进展可视化
// 四态状态机 pending → in_progress → completed / failed。needs_confirmation /
// dismissed / cancelled / 待办 全部退役：#15 作者三条强调「不再有待办——纯 team
// 任务进展可视化」——待办区 (.task-section-todo) 与 human pending CIRCLE
// (completeTask 用户确认通道) 移除，无用户确认/打回/取消环节。
//   - 任务区 (.task-section-progress): agent pending/in_progress — read-only
//     glyphs (pending=静态小方框 / in_progress=独立 spinner), status word +
//     relative time + team/member meta (谁在执行)
// 裁定②: team 域任务统一并入 Nebula 会话的本面板（Teams 标签页任务区已删）；
// 裁定④ + #15: 三级分组 team → member → tasks（member=assignee 契约字段，
// 每任务标注 Team+成员名）。
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import state from './state.js';

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
// #15 不再有待办——面板纯任务进展可视化，单一任务区（progress），todo/progress
// 双区块划分退役。

/** Sort key: updatedAt refreshed on every mutation and always present;
 *  defensive fallback to createdAt. */
function taskTs(tk) {
  const raw = tk.updatedAt || tk.createdAt;
  const ms = Date.parse(raw);
  return isNaN(ms) ? 0 : ms;
}

function sortByActiveDesc(a, b) {
  return taskTs(b) - taskTs(a);
}

/** 任务区: updatedAt desc (visible set is active-only — no failed sinking). */
function sortProgress(a, b) {
  return sortByActiveDesc(a, b);
}

// ── Team grouping (第七件 裁定④, 2026-08-30 + #15 补齐) ───────────────────
// 任务区 groups team → member → tasks. ACCESSOR ISOLATION: the Backend data
// contract hooks up HERE and nowhere else. 契约字段（TaskModel.scala）：
// team 归属 = `teamId`（scope=='team' 的 team 域任务携带；session 域无此键）；
// member 归属 = `assignee`（成员责任归属——#15 修正：此前 taskMember 误读
// `member` 字段（不存在）致三级分组的 member 层永不填充）。
function taskTeam(tk) { return (tk && typeof tk.teamId === 'string' && tk.teamId) || null; }
function taskMember(tk) { return (tk && typeof tk.assignee === 'string' && tk.assignee) || null; }

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
 *  (row dataset/zone-diff keyed by id). Team tasks are read-only here, so the
 *  stamped id never goes back on the wire. */
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

// ── Row builders (§15.5/§15.6) ──────────────────────────────────────────

/**
 * Zone-leading glyph per status (纯进展可视化 — single progress zone):
 *  pending  = 静态小方框 (.task-check-box, #15 更小 —— 观感「排队/等待执行」)
 *  in_progress = 独立 spinner 圆环本体 (裁定⑤; spinner 在方框中转禁).
 *  All read-only (aria-hidden); row aria-label carries the status.
 */
function buildCheck(task, row) {
  const check = document.createElement('span');
  check.className = 'task-check';

  // 任务区 — read-only glyph (§15.6: no hover feedback, no tab stop).
  // in_progress -> standalone spinner (base 20px); pending -> small static box.
  if (task.status === 'in_progress') {
    // 第七件 裁定⑤ (2026-08-30): the spinner IS the glyph — a standalone
    // ring, no surrounding box ("spinner 在方框中转" combo is forbidden).
    // No .task-check-box so it keeps the base 20px slot (not the small box).
    check.classList.add('task-check-spin');
    check.setAttribute('aria-hidden', 'true');
    row.setAttribute('aria-label',
      `${task.subject || ''} — ${t('task.inProgressShort')}`.trim());
  } else {
    // agent pending — decorative hollow small square (#15 更小)
    check.classList.add('task-check-box');
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
  // #15 三级分组: annotate every task with its Team · member (谁在执行). Quiet
  // meta line under the subject/desc; grouped tasks read clearly. 无归属的全局
  // 任务在 Nebula 统一面板标注「Nebula（全局）」来源（#16，Nebula 拍板 08-30）——
  // 成员会话面板的无归属任务不标注（那是成员自己的任务）。
  const team = taskTeam(task);
  const member = taskMember(task);
  let meta = [team, member].filter(Boolean).join(' · ');
  // #16 全局任务来源标注: 无 team 无 assignee 的全局任务在 Nebula 统一面板
  // （sessionShowsTeamTasks true）标注来源「Nebula（全局）」；成员会话面板不标。
  if (!meta && sessionShowsTeamTasks(sessionId)) meta = t('task.globalSource');
  if (meta) {
    row.classList.add('task-has-meta');
    const metaEl = document.createElement('span');
    metaEl.className = 'task-meta';
    metaEl.setAttribute('aria-hidden', 'true');
    metaEl.textContent = meta;
    text.appendChild(metaEl);
  }
  row.appendChild(text);

  // 任务区 (§15.6): status word + relative time (D6). Both aria-hidden —
  // the row's aria-label carries the status for AT users (§15.9).
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
  return row;
}

function buildGroupHeader(label, section) {
  const h = document.createElement('div');
  // Dual class: .task-group-header is the implementation class (CSS hooks),
  // .task-section-title is the frozen spec assertion selector (§10 A6).
  // data-section carries the zone ('progress') — D1 assertion hook.
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

/** Empty-state row (§15.5/§15.6, C27): rendered only when the panel is visible
 *  with no visible tasks; non-interactive, aria-hidden (§15.9). */
function buildEmpty(label) {
  const empty = document.createElement('div');
  empty.className = 'task-empty';
  empty.setAttribute('aria-hidden', 'true');
  empty.textContent = label;
  return empty;
}

// ── Main render (v3 single-zone: pure progress) ─────────────────────────

/**
 * @param {Array} tasks
 * @param {HTMLElement} [container]
 * @param {string} [sessionId] owning session — stamped onto rows
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

  // 裁定 3 (跨区移动) 已退役（#15 单一 progress 区，无跨区）——普通更新直接
  // 重绘，新行带 v1 task-entering（裁定 2 状态过渡）。
  const oldById = collectZoneById(container);
  redraw(visible, container, sessionId, oldById);
}

/** Map taskId → zone ('progress') from the current DOM (entry-animation
 *  comparison only — a #15 single-zone panel always resolves 'progress'). */
function collectZoneById(container) {
  const m = new Map();
  container.querySelectorAll('.task-item[data-task-id]').forEach((el) => {
    m.set(el.dataset.taskId, 'progress');
  });
  return m;
}

function redraw(visible, container, sessionId, oldById) {
  // #15: 单一 progress 区，四态进展展示（无 todo 子块、无 failed 沉底，visible
  // 集本就只含 pending+in_progress）: agent active (updatedAt desc), 裁定④
  // 三级分组 team → member → tasks 在排序之后。
  const progress = visible.filter(tk => kindOf(tk) === 'agent').sort(sortProgress);

  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats (C26 — #15 progress-only count) ──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = t('task.statsProgress', { progress: progress.length });

  header.appendChild(toggle);
  header.appendChild(stats);

  // ── Body: single progress zone ──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  const progressSection = document.createElement('div');
  progressSection.className = 'task-section task-section-progress';
  progressSection.appendChild(buildGroupHeader(t('task.sectionProgress'), 'progress'));
  if (progress.length === 0) {
    progressSection.appendChild(buildEmpty(t('task.progressEmpty')));
  } else {
    // 裁定④ + #15 grouping: ungrouped (session-local) tasks flat first, then
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

  // 裁定 2: entry animation for brand-new rows.
  if (!REDUCED_MOTION) {
    const items = /** @type {NodeListOf<HTMLElement>} */ (container.querySelectorAll('.task-item'));
    items.forEach((el) => {
      if (!oldById.has(el.dataset.taskId)) {
        el.classList.add('task-entering');
        el.addEventListener('animationend', () => el.classList.remove('task-entering'), { once: true });
      }
    });
  }
}
