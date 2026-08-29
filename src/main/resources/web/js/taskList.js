// taskList.js — Collapsible task panel (floats above the input area)
// todo-panel-spec.md v3 dual-zone (§15): the panel splits by ACTION OWNERSHIP
// (需要我操作 vs 只读观察), not by creator:
//   - 待办区 (.task-section-todo): human pending + agent needs_confirmation —
//     CIRCLE controls (clickable: complete / return), "需要你" signals
//   - 任务区 (.task-section-progress): agent pending/in_progress/failed —
//     SQUARE read-only icons (裁定 19:56: 任务区不用圆圈 — 圆圈暗示可点；
//     方块 = 观察区专属，圆圈 = 操作区专属), status word + relative time
// v2 semantics preserved: completing is optimistic (fill → collapse → remove),
// return drafts a taskRef into the input (C16), failed sinks same-day.
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { openTaskArchive } from './taskArchive.js';
import { sendWs, onMessage } from './ws.js';
import state from './state.js';
import { showToast } from './modal.js';
import { makeReference } from './reference.js';
import { activeView, chatViews } from './chatView.js';

/** Local-calendar check (device timezone). */
function isTodayLocal(dateStr) {
  if (!dateStr) return false;
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return false;
  const now = new Date();
  return d.getFullYear() === now.getFullYear() &&
         d.getMonth() === now.getMonth() &&
         d.getDate() === now.getDate();
}

/** C1: missing taskKind (pre-upgrade server) defaults to 'agent'. */
function kindOf(tk) {
  return tk && tk.taskKind === 'human' ? 'human' : 'agent';
}

/** §2.2/C6 (+v2): visible set = pending + in_progress + needs_confirmation +
 *  failed same-day. Completed and dismissed rows never render in the panel. */
function isVisible(tk) {
  if (!tk || !tk.status) return false;
  // cancelled is a terminal state (契约#2) — not in the active panel; it
  // surfaces in the archive. Excluding it here is what moves a cancelled
  // task out of the active list on the authoritative taskListUpdate push.
  if (tk.status === 'cancelled') return false;
  if (tk.status === 'pending' || tk.status === 'in_progress' || tk.status === 'needs_confirmation') return true;
  if (tk.status === 'failed') return isTodayLocal(tk.createdAt);
  return false;
}

// ── v3 dual-zone partitioning (C23) ──────────────────────────────────────
// A task lives in exactly ONE zone at any time. 待办区 = needs user action;
// 任务区 = read-only agent progress. Deterministic per (kind, status).
function inTodoZone(tk) {
  return kindOf(tk) === 'human'
    ? tk.status === 'pending'
    : tk.status === 'needs_confirmation';
}

/** Sort key — §15.3 data fact: updatedAt refreshed on every
 *  TaskCreate/TaskUpdate/complete/return and always Some (TaskStore.scala
 *  119/292/350/443); defensive fallback to createdAt. */
function taskTs(tk) {
  const raw = tk.updatedAt || tk.createdAt;
  const ms = Date.parse(raw);
  return isNaN(ms) ? 0 : ms;
}

/** C24 待办区: human sub-block pinned on top (key = createdAt, desc);
 *  agent needs_confirmation sub-block below (key = updatedAt, desc). */
function sortByCreatedDesc(a, b) {
  return (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0);
}
function sortByActiveDesc(a, b) {
  return taskTs(b) - taskTs(a);
}

/** C25 任务区: updatedAt desc; failed always sinks below active rows. */
function sortProgress(a, b) {
  const af = a.status === 'failed' ? 1 : 0;
  const bf = b.status === 'failed' ? 1 : 0;
  if (af !== bf) return af - bf;
  return sortByActiveDesc(a, b);
}

// ── Team grouping (第七件 裁定④, 2026-08-30) ─────────────────────────────
// 任务区 groups team → member → tasks. ACCESSOR ISOLATION: the Backend data
// contract (team/member attribution fields on the task-list API/WS payload)
// hooks up HERE and nowhere else. Tasks without team attribution render
// flat, exactly as before — zero regression for session-local tasks.
function taskTeam(tk) { return (tk && typeof tk.team === 'string' && tk.team) || null; }
function taskMember(tk) { return (tk && typeof tk.member === 'string' && tk.member) || null; }

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

/** taskId → task snapshot, for cancelTask rollback (契约#1/#3). Symmetric
 *  to pendingComplete: a taskError for an id we sent a cancel for is OURS —
 *  roll the row back (it was optimistically removed) and toast. */
const pendingCancel = new Map();

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

// ── Cancel flow (契约#1/#2/#4: user-cancels a task) ───────────────────────
// The TaskUpdate state machine has no cancel; the backend adds 'cancelled'
// (terminal). The user cancels from the panel; we send a cancelTask frame
// (#3: the backend notifies the owning agent — frontend only consumes the
// feedback). Optimistic removal mirrors the complete flow; a taskError for
// an id in pendingCancel rolls the row back. The cancel reason note is
// recorded server-side and shown in the archive (taskArchive.js).

/** Only agent tasks in a cancellable active state gain the cancel control.
 *  failed is terminal-not-cancellable (state machine: cancel sources are
 *  pending/in_progress/needs_confirmation); human to-do rows keep only the
 *  complete circle — cancelling a personal to-do isn't marked "cancelled". */
function isCancellable(task) {
  return kindOf(task) === 'agent' &&
    (task.status === 'pending' || task.status === 'in_progress' || task.status === 'needs_confirmation');
}

function requestCancel(row, task) {
  if (!state.connected) return;                    // WS down → disabled (§4)
  if (pendingCancel.has(task.id)) return;          // debounce double-click
  pendingCancel.set(task.id, task);

  sendWs({
    type: 'cancelTask',
    sessionId: state.activeSessionId,
    taskId: task.id,
    reason: t('task.cancelDefaultReason'),
  });

  const finish = () => {
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

  row.classList.add('task-cancelling');            // dim + lock the row
  setTimeout(() => {
    row.classList.add('task-collapsing');
    setTimeout(finish, 230);
  }, 180);
}

// Server-side rejection of a complete → roll back: re-insert at the sorted
// position (render derives it), replay the entering animation, toast (§7.2).
// The backend taskError frame is {"type":"taskError","error","taskId"} — no
// msgType field (WebSocketRoutes.scala:1527-1532, symmetric with dismissTask).
// We identify completeTask failures by taskId membership in pendingComplete:
// a taskError for an id we never sent a complete for is not ours — pass
// through untouched (裁定 F-B1①, qa-frontend 打回修复).
// v2: return failures (a taskRef chip in the input) remove the chip + toast
// t('task.returnError'); the row converges via the taskListUpdate push the
// backend always sends after processing taskRefs (§6.3).
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
  // 契约#1: cancel rejection — roll the optimistically-removed row back, toast.
  if (pendingCancel.has(msg.taskId)) {
    const task = pendingCancel.get(msg.taskId);
    pendingCancel.delete(msg.taskId);
    const sid = msg.sessionId || state.activeSessionId;
    const arr = sid && state.sessionTasks ? state.sessionTasks[sid] : null;
    if (Array.isArray(arr) && !arr.includes(task)) arr.push(task);
    const container = document.getElementById('task-list');
    renderTaskList(arr || [], container, sid);
    showToast(t('task.cancelError'), 'error');
    return;
  }
  // v2 §6.3: return rejection — drop the taskRef chip (local only), toast.
  if (removeTaskRefsByTaskId(msg.taskId)) {
    showToast(t('task.returnError'), 'error');
  }
});

// ── Row builders (§15.5/§15.6) ──────────────────────────────────────────

/**
 * Zone-leading icon per (kind, status):
 *  待办区 (user action): CIRCLE — clickable (role=checkbox, Space/Enter).
 *  任务区 (read-only):   SQUARE (.task-check-box) — 裁定 19:56: circles look
 *    clickable; squares are the observer-zone glyph (原始 checkbox 方块样式).
 *    in_progress = spinner inside the square slot; failed = red x; pending =
 *    hollow square. All aria-hidden (row aria-label carries status).
 */
function buildCheck(task, row) {
  const check = document.createElement('span');
  check.className = 'task-check';

  if (inTodoZone(task)) {
    // 待办区 — circle, clickable (§9 aria: the awaiting-ruling state is
    // carried by the row's aria-label; the status word is aria-hidden).
    if (task.status === 'needs_confirmation') {
      row.setAttribute('aria-label', `${task.subject || ''} — ${t('task.needsConfirmation')}`.trim());
    }
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
  } else if (task.status === 'failed') {
    check.setAttribute('aria-hidden', 'true');
    check.innerHTML = '<i data-lucide="x" class="task-failed-icon"></i>';
    row.setAttribute('aria-label',
      `${task.subject || ''} — ${t('task.failedShort')}`.trim());
  } else {
    // agent pending — decorative hollow square
    check.setAttribute('aria-hidden', 'true');
    row.setAttribute('aria-label',
      `${task.subject || ''} — ${t('task.pendingShort')}`.trim());
  }
  return check;
}

/** 契约#1: user-cancel control — visually distinct from the completed CIRCLE.
 *  A compact glass button (mirrors .task-return-btn) with a ban (circle-slash)
 *  icon + label, at the row's right end. The user-action-zone shape family is
 *  kept (interactive glass button, not a read-only square); the LEADING zone
 *  glyph stays square/read-only so the panel's observe-vs-act split holds.
 *  aria-hidden is NOT set (it is a real, focusable control). */
function buildCancelBtn(task, row) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'task-cancel-btn';
  const cancelLabel = t('task.cancelAria', { subject: task.subject || '' });
  btn.setAttribute('aria-label', cancelLabel);
  btn.title = cancelLabel;
  const icon = document.createElement('i');
  icon.dataset.lucide = 'ban';
  const span = document.createElement('span');
  span.textContent = t('task.cancel');
  btn.appendChild(icon);
  btn.appendChild(span);
  btn.addEventListener('click', (e) => { e.stopPropagation(); requestCancel(row, task); });
  return btn;
}

function buildRow(task, sessionId) {
  const row = document.createElement('div');
  row.className = 'task-item' +
    (task.status === 'in_progress' ? ' task-active' : '') +
    (task.status === 'failed' ? ' task-failed' : '') +
    (task.status === 'needs_confirmation' ? ' task-needs-confirmation' : '');
  row.dataset.taskId = task.id;
  if (sessionId) row.dataset.sessionId = sessionId;

  row.appendChild(buildCheck(task, row));

  const text = document.createElement('div');
  text.className = 'task-item-text';
  const label = document.createElement('span');
  label.className = 'task-label';
  label.textContent = task.subject || '';
  text.appendChild(label);
  // #37: render the description under the subject. The panel previously only
  // rendered subject, so tasks with similar subjects were indistinguishable
  // (the user cancelled 5 by mistake). Truncated to 2 lines; full text in title.
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

  if (inTodoZone(task)) {
    // 待办区: human rows carry no status word (B9); the needs_confirmation
    // row carries a sapphire-强调 status word (C29) + return button (v2 §3/§8).
    if (task.status === 'needs_confirmation') {
      const word = document.createElement('span');
      word.className = 'task-status-word';
      word.setAttribute('aria-hidden', 'true');
      word.textContent = t('task.needsConfirmation');

      const ret = document.createElement('button');
      ret.className = 'task-return-btn';
      ret.type = 'button';
      const retLabel = t('task.returnAria', { subject: task.subject || '' });
      ret.setAttribute('aria-label', retLabel);
      ret.title = retLabel;
      const icon = document.createElement('i');
      icon.dataset.lucide = 'corner-up-left';
      const span = document.createElement('span');
      span.textContent = t('task.returnLabel');
      ret.appendChild(icon);
      ret.appendChild(span);
      ret.addEventListener('click', () => requestReturn(task, row));

      row.appendChild(word);
      row.appendChild(ret);
    }
  } else {
    // 任务区 (§15.6): status word + relative time (D6). Both aria-hidden —
    // the row's aria-label carries the status for AT users (§15.9).
    const word = document.createElement('span');
    word.className = 'task-status-word';
    word.setAttribute('aria-hidden', 'true');
    word.textContent = t(task.status === 'in_progress'
      ? 'task.inProgressShort'
      : task.status === 'failed' ? 'task.failedShort' : 'task.pendingShort');

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
  // 契约#1: cancellable agent rows get an explicit cancel affordance at the
  // right end (distinct from the completed circle). Applies in BOTH zones
  // (needs_confirmation in 待办区, pending/in_progress in 任务区).
  if (isCancellable(task)) row.appendChild(buildCancelBtn(task, row));
  return row;
}

// ── Return flow (v2 §5/§6.3): draft a taskRef reference into the input ──────
// C16: clicking Return only DRAFTS — the reference chip goes into
// #attachment-preview (reusing the file-attachment mechanism, C18); sending
// the message is what actually returns the task. Snapshot taken at click
// time; the backend validates against authoritative state on send (§5.1).
// B (用户 2026-08-20 打回): 引用块精简——chip 只带任务号+标题（+实时意见预览），
// 载荷只带 taskId/sessionId/subject；描述/产出不进引用（agent 凭 taskId
// 定位，记忆里有任务上下文；意见 = 发送时输入框内容，§5.4 后端落 notes）。
function requestReturn(task, row) {
  if (!state.connected) return;                    // WS down → disabled (§4)
  const sessionId = row.dataset.sessionId || state.activeSessionId;
  if (!sessionId || !activeView || !Array.isArray(activeView.pendingAttachments)) return;
  // #303 B5 (v1.1 引用=打回): produce a unified type:'ref', refType:'task'
  // Reference (renders as an @#<id> <subject> mention block, §3.3/§3.5) instead
  // of the legacy taskRef chip. 引用即打回 — the backend routes refs[refType
  // == 'task'] to the return flow (spec §5.4); the legacy taskRef shape stays
  // supported for old history/refresh (persistence). Click drafts only; send
  // performs the return (C16).
  const ref = makeReference({
    refType: 'task',
    source: { kind: 'task', taskId: task.id, sessionId, title: task.subject || '' },
  });
  if (!ref) return;                                 // unknown shape → bail (no ref dropped)
  activeView.pendingAttachments.push(ref);
  import('./chat.js').then(({ renderAttachmentPreview }) => {
    renderAttachmentPreview(activeView);
    if (activeView.dom && activeView.dom.input) activeView.dom.input.focus();
  });
}

/** Remove task attachments with the given taskId from every view's pending
 *  attachments (taskError carries no sessionId — sweep is idempotent).
 *  #303 B5: matches both the legacy taskRef chip and the unified
 *  type:'ref', refType:'task' reference (source.taskId).
 *  Returns true if anything was removed. */
function removeTaskRefsByTaskId(taskId) {
  let removed = false;
  const seen = new Set();
  const isOurTaskRef = (a) =>
    (a.type === 'taskRef' && a.taskId === taskId) ||
    (a.type === 'ref' && a.refType === 'task' && a.source && a.source.taskId === taskId);
  const sweep = (v) => {
    if (!v || seen.has(v) || !Array.isArray(v.pendingAttachments)) return;
    seen.add(v);
    const before = v.pendingAttachments.length;
    v.pendingAttachments = v.pendingAttachments.filter(a => !isOurTaskRef(a));
    if (v.pendingAttachments.length !== before) {
      removed = true;
      import('./chat.js').then(({ renderAttachmentPreview }) => {
        if (v.dom && v.dom.attPreview) renderAttachmentPreview(v);
      });
    }
  };
  sweep(activeView);
  for (const v of Object.values(chatViews)) sweep(v);
  return removed;
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

  const visible = (tasks || []).filter(isVisible);
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
  // §15.3 C24/C25 partitioning + sorting:
  //  待办区 = human (createdAt desc) pinned above agent needs_confirmation
  //          (updatedAt desc); 任务区 = agent active (updatedAt desc, failed sinks).
  const todoHuman = visible.filter(tk => kindOf(tk) === 'human').sort(sortByCreatedDesc);
  const todoAgent = visible.filter(tk => kindOf(tk) === 'agent' && tk.status === 'needs_confirmation').sort(sortByActiveDesc);
  const progress = visible.filter(tk => kindOf(tk) === 'agent' && tk.status !== 'needs_confirmation').sort(sortProgress);

  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats (C26 dual-segment) + archive ──
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
    todo: todoHuman.length + todoAgent.length,
    progress: progress.length,
  });

  const archiveBtn = document.createElement('button');
  archiveBtn.className = 'task-archive-btn';
  archiveBtn.title = t('task.archiveTooltip');
  archiveBtn.setAttribute('aria-label', t('task.archiveTooltip'));
  archiveBtn.innerHTML = '<i data-lucide="archive"></i>';
  archiveBtn.addEventListener('click', (e) => { e.stopPropagation(); openTaskArchive(); });

  header.appendChild(toggle);
  header.appendChild(stats);
  header.appendChild(archiveBtn);

  // ── Body: two zones, headers constant while panel visible (C27) ──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  const todoSection = document.createElement('div');
  todoSection.className = 'task-section task-section-todo';
  todoSection.appendChild(buildGroupHeader(t('task.sectionTodo'), 'todo'));
  if (todoHuman.length + todoAgent.length === 0) {
    todoSection.appendChild(buildEmpty(t('task.todoEmpty')));
  } else {
    todoHuman.forEach(tk => todoSection.appendChild(buildRow(tk, sessionId)));
    todoAgent.forEach(tk => todoSection.appendChild(buildRow(tk, sessionId)));
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
