// taskList.js — Collapsible task panel (floats above the input area)
// Apple Reminders-style layout per todo-panel-spec.md v1.1:
//   - two sections (My Tasks / Agent Tasks), explicit group headers only when
//     both kinds are present
//   - circle completion control: human tasks clickable, agent tasks read-only,
//     in_progress rows show a spinner in the same slot
//   - completing is optimistic-disappear (fill → collapse → remove); the
//     server confirms via taskListUpdate or rejects via taskError (rollback)
//   - failed tasks stay visible same-day, sink to the bottom of their group
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { openTaskArchive } from './taskArchive.js';
import { sendWs, onMessage } from './ws.js';
import state from './state.js';
import { showToast } from './modal.js';
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
  if (tk.status === 'pending' || tk.status === 'in_progress' || tk.status === 'needs_confirmation') return true;
  if (tk.status === 'failed') return isTodayLocal(tk.createdAt);
  return false;
}

/** §3.3 + C19 (v2): needs_confirmation first (user must act), then
 *  in_progress, then pending by createdAt asc, failed sinks. */
function sortGroup(a, b) {
  const rank = tk => tk.status === 'needs_confirmation' ? 0
    : tk.status === 'in_progress' ? 1
    : tk.status === 'pending' ? 2 : 3;
  const r = rank(a) - rank(b);
  if (r !== 0) return r;
  return (Date.parse(a.createdAt) || 0) - (Date.parse(b.createdAt) || 0);
}

/** Non-terminal count for the header stats (failed excluded — A9; v2:
 *  needs_confirmation is not terminal — awaiting user ruling, counted). */
function activeCount(tasks) {
  return tasks.filter(tk => tk && (tk.status === 'pending' || tk.status === 'in_progress' || tk.status === 'needs_confirmation')).length;
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

  // A4: optimistic -1 on the header non-terminal count — do not wait for the
  // server push (taskListUpdate re-render is authoritative when it arrives;
  // rollback via taskError re-renders from restored state, self-healing).
  const sid0 = row.dataset.sessionId || state.activeSessionId;
  const arr0 = sid0 && state.sessionTasks ? state.sessionTasks[sid0] : null;
  const statsEl = document.querySelector('#task-list .task-stats');
  if (statsEl && Array.isArray(arr0)) {
    statsEl.textContent = `${Math.max(0, activeCount(arr0) - 1)}${t('task.statsSuffix')}`;
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
    renderTaskList(arr || [], container, sid);
    const row = container && container.querySelector(`.task-item[data-task-id="${CSS.escape(task.id)}"]`);
    if (row && !REDUCED_MOTION) {
      row.classList.add('task-entering');
      row.addEventListener('animationend', () => row.classList.remove('task-entering'), { once: true });
    }
    showToast(t('task.completeError'), 'error');
    return;
  }
  // v2 §6.3: return rejection — drop the taskRef chip (local only), toast.
  if (removeTaskRefsByTaskId(msg.taskId)) {
    showToast(t('task.returnError'), 'error');
  }
});

// ── Row builders (§3.2) ──────────────────────────────────────────────────

/**
 * Circle control per kind/status:
 *  - human pending     → clickable (role=checkbox, Space/Enter)
 *  - agent needs_confirmation → clickable — user confirm = complete (§2.3,
 *    same interaction as human check-off, [U4])
 *  - agent pending     → decorative, aria-hidden (no checkbox semantics — A14)
 *  - in_progress       → spinner, aria-hidden (row aria-label carries status)
 *  - failed            → red x, aria-hidden
 */
function buildCheck(task, row) {
  const check = document.createElement('span');
  check.className = 'task-check';

  if (task.status === 'in_progress') {
    check.setAttribute('aria-hidden', 'true');
    check.innerHTML = '<span class="task-check-spinner"></span>';
    row.setAttribute('aria-label',
      `${task.subject || ''} — ${t('task.inProgressShort')}`.trim());
    return check;
  }
  if (task.status === 'failed') {
    check.setAttribute('aria-hidden', 'true');
    check.innerHTML = '<i data-lucide="x" class="task-failed-icon"></i>';
    return check;
  }
  if (task.status === 'needs_confirmation') {
    // §9: the awaiting-ruling state is carried by the row's aria-label —
    // the status word is aria-hidden to avoid double readout.
    row.setAttribute('aria-label', `${task.subject || ''} — ${t('task.needsConfirmation')}`.trim());
  }
  if (task.status === 'needs_confirmation' || kindOf(task) === 'human') {
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
  // agent pending — purely decorative
  check.setAttribute('aria-hidden', 'true');
  return check;
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
  row.appendChild(text);

  // v2 §3: awaiting-ruling row carries a muted status word + return button
  // (right end). Status word is aria-hidden — the row's state is carried by
  // the clickable checkbox aria-label + return button.
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
  return row;
}

// ── Return flow (v2 §5/§6.3): draft a taskRef reference into the input ──────
// C16: clicking Return only DRAFTS — the reference chip goes into
// #attachment-preview (reusing the file-attachment mechanism, C18); sending
// the message is what actually returns the task. Snapshot taken at click
// time; the backend validates against authoritative state on send (§5.1).
function requestReturn(task, row) {
  if (!state.connected) return;                    // WS down → disabled (§4)
  const sessionId = row.dataset.sessionId || state.activeSessionId;
  if (!sessionId || !activeView || !Array.isArray(activeView.pendingAttachments)) return;
  const notes = Array.isArray(task.notes) ? task.notes : [];
  const lastNote = notes.length > 0 ? notes[notes.length - 1] : null;
  activeView.pendingAttachments.push({
    type: 'taskRef',
    taskId: task.id,
    sessionId,
    subject: task.subject || '',
    description: task.description || '',
    output: (lastNote && lastNote.content) || ''   // agent's outcome note
  });
  import('./chat.js').then(({ renderAttachmentPreview }) => {
    renderAttachmentPreview(activeView);
    if (activeView.dom && activeView.dom.input) activeView.dom.input.focus();
  });
}

/** Remove taskRef chips with the given taskId from every view's pending
 *  attachments (taskError carries no sessionId — sweep is idempotent).
 *  Returns true if anything was removed. */
function removeTaskRefsByTaskId(taskId) {
  let removed = false;
  const seen = new Set();
  const sweep = (v) => {
    if (!v || seen.has(v) || !Array.isArray(v.pendingAttachments)) return;
    seen.add(v);
    const before = v.pendingAttachments.length;
    v.pendingAttachments = v.pendingAttachments.filter(a => !(a.type === 'taskRef' && a.taskId === taskId));
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

function buildGroupHeader(label) {
  const h = document.createElement('div');
  // Dual class: .task-group-header is the implementation class (CSS hooks),
  // .task-section-title is the frozen spec assertion selector (§10 A6).
  h.className = 'task-group-header task-section-title';
  h.textContent = label;
  return h;
}

// ── Main render ──────────────────────────────────────────────────────────

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
  container.innerHTML = '';
  if (visible.length === 0) return;

  const human = visible.filter(tk => kindOf(tk) === 'human').sort(sortGroup);
  const agent = visible.filter(tk => kindOf(tk) === 'agent').sort(sortGroup);
  const both = human.length > 0 && agent.length > 0;

  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats + archive entry (§3.1/§3.4) ──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = `${activeCount(visible)}${t('task.statsSuffix')}`;

  const archiveBtn = document.createElement('button');
  archiveBtn.className = 'task-archive-btn';
  archiveBtn.title = t('task.archiveTooltip');
  archiveBtn.setAttribute('aria-label', t('task.archiveTooltip'));
  archiveBtn.innerHTML = '<i data-lucide="archive"></i>';
  archiveBtn.addEventListener('click', (e) => { e.stopPropagation(); openTaskArchive(); });

  header.appendChild(toggle);
  header.appendChild(stats);
  header.appendChild(archiveBtn);

  // ── Body: two sections (§3) ──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  const appendGroup = (label, list) => {
    if (list.length === 0) return;
    if (both) inner.appendChild(buildGroupHeader(label));
    for (const task of list) inner.appendChild(buildRow(task, sessionId));
  };
  // Human section pinned on top when both kinds exist (§3).
  appendGroup(t('task.sectionHuman'), human);
  appendGroup(t('task.sectionAgent'), agent);

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
}
