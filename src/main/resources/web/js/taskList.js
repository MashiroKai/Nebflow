import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { openTaskArchive } from './taskArchive.js';

const MAX_VISIBLE = 20;
const COLLAPSED_KEY = 'nebflow-task-collapsed';

const iconMap = {
  pending: 'square',
  in_progress: 'loader-2',
  completed: 'check',
  failed: 'x'
};

const activeStatuses = new Set(['pending', 'in_progress']);

// ── 今日完成 (completedToday) ─────────────────────────────
// 口径(Manager 裁定 2026-08-16): N = status==='completed' 且 completedAt 存在
// 且日期为今天(用户本地时区)。failed/dismissed 均不计入——failed 在档案视图
// 照常展示带失败标注,但绝不能混进「完成 N」误导用户。

function isTodayLocal(iso) {
  if (!iso) return false;
  const d = new Date(iso);
  if (isNaN(d.getTime())) return false;
  const now = new Date();
  return d.getFullYear() === now.getFullYear() &&
         d.getMonth() === now.getMonth() &&
         d.getDate() === now.getDate();
}

function todayCompleted(tasks) {
  return (tasks || [])
    .filter(task => task.status === 'completed' && isTodayLocal(task.completedAt))
    .sort((a, b) => new Date(b.completedAt) - new Date(a.completedAt));
}

function fmtTimeHM(iso) {
  const d = new Date(iso);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

/** Render the「今日完成 N」folded bar below the task card (or standalone when
 *  no active tasks). Re-render safe: replaces any existing bar, preserves the
 *  expanded flag on the container across re-renders. */
function renderTodayBar(container, today, sessionId) {
  container.querySelector('.task-today-wrap')?.remove();
  // #task-list 无 .has-tasks 时 max-height:0 折叠——独立展示折叠条必须给容器
  // 单独放行(.has-today),否则 bar 会被 overflow:hidden 裁掉。
  container.classList.toggle('has-today', !!(today && today.length));
  if (!today || today.length === 0) { container._todayTasks = null; return; }
  container._todayTasks = today;
  container._todaySessionId = sessionId;

  const expanded = container._todayExpanded === true;
  const wrap = document.createElement('div');
  wrap.className = 'task-today-wrap';

  let html = `<div class="task-today-bar" role="button" tabindex="0">` +
    `<i data-lucide="${expanded ? 'chevron-down' : 'chevron-right'}"></i>` +
    `<span class="task-today-count">${escapeHtml(t('task.completedToday', { count: today.length }))}</span>` +
    `</div>`;

  if (expanded) {
    html += '<div class="task-today-list">';
    today.slice(0, 10).forEach(task => {
      const note = (task.notes && task.notes[0] && task.notes[0].content) || '';
      html += `<div class="task-today-item">` +
        `<div class="task-today-item-main">` +
          `<span class="task-today-subject">${escapeHtml(task.subject || '')}</span>` +
          `<span class="task-today-time">${escapeHtml(fmtTimeHM(task.completedAt))}</span>` +
        `</div>` +
        (note ? `<div class="task-today-note">${escapeHtml(note)}</div>` : '') +
        `</div>`;
    });
    html += `<div class="task-today-all" role="button" tabindex="0">${escapeHtml(t('task.viewAll'))}</div>`;
    html += '</div>';
  }

  wrap.innerHTML = html;
  container.appendChild(wrap);
  if (typeof lucide !== 'undefined') createIconsIn(wrap);

  const bar = wrap.querySelector('.task-today-bar');
  const toggle = () => {
    container._todayExpanded = !container._todayExpanded;
    renderTodayBar(container, container._todayTasks, container._todaySessionId);
  };
  bar.addEventListener('click', toggle);
  bar.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } });

  const allBtn = wrap.querySelector('.task-today-all');
  if (allBtn) {
    allBtn.addEventListener('click', (e) => { e.stopPropagation(); openTaskArchive(); });
    allBtn.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openTaskArchive(); } });
  }
}

function isCollapsed() {
  try { return localStorage.getItem(COLLAPSED_KEY) === '1'; } catch { return false; }
}

function setCollapsed(v) {
  try { localStorage.setItem(COLLAPSED_KEY, v ? '1' : '0'); } catch {}
}

export function renderTaskList(tasks, container, sessionId) {
  container = container || document.getElementById('task-list');
  if (!container) return;

  // Build previous snapshot: taskId → status
  const prevSnapshot = container._taskSnapshot || {};

  const isEmpty = !tasks || tasks.length === 0;
  const active = isEmpty ? [] : tasks.filter(t => activeStatuses.has(t.status));

  if (active.length === 0) {
    // 「今日完成」折叠条在没有活跃任务时仍独立展示——这正是用户想看
    // 「今天干成了什么」的时刻。
    const today = todayCompleted(tasks);
    const finishClear = () => {
      container.innerHTML = '';
      container.classList.remove('has-tasks');
      renderTodayBar(container, today, sessionId);
    };
    // Fade out existing card before clearing
    const card = container.querySelector('.task-card');
    if (card && Object.keys(prevSnapshot).length > 0) {
      card.classList.add('task-card-leaving');
      setTimeout(finishClear, 300);
    } else {
      finishClear();
    }
    container._taskSnapshot = {};
    return;
  }

  // Build new snapshot
  const newSnapshot = {};
  active.forEach(t => { newSnapshot[t.id] = t.status; });

  // Detect items to animate out (existed before, now completed/gone)
  const leavingIds = Object.keys(prevSnapshot).filter(id => !newSnapshot[id]);

  // If there are leaving items, animate them out first, then re-render after 350ms
  if (leavingIds.length > 0) {
    let pendingLeave = leavingIds.length;
    leavingIds.forEach(id => {
      const el = container.querySelector(`[data-task-id="${id}"]`);
      if (el) {
        el.classList.add('task-leaving');
      }
      pendingLeave--;
    });

    // Delay the re-render to let leave animation play
    setTimeout(() => doRender(container, tasks, active, sessionId, prevSnapshot, newSnapshot), 350);
  } else {
    doRender(container, tasks, active, sessionId, prevSnapshot, newSnapshot);
  }

  container._taskSnapshot = newSnapshot;
}

function doRender(container, allTasks, active, sessionId, prevSnapshot, newSnapshot) {
  container.classList.add('has-tasks');
  const collapsed = isCollapsed();

  // Build parent → children map
  const byParent = new Map();
  active.forEach(t => {
    const pid = t.parentId || null;
    if (!byParent.has(pid)) byParent.set(pid, []);
    byParent.get(pid).push(t);
  });
  byParent.forEach(arr => arr.sort((a, b) => (parseInt(a.id) || 0) - (parseInt(b.id) || 0)));

  const activeIds = new Set(active.map(t => t.id));
  const roots = active
    .filter(t => !t.parentId || !activeIds.has(t.parentId))
    .sort((a, b) => {
      if (a.status !== b.status) {
        const order = { in_progress: 0, pending: 1, completed: 2 };
        return (order[a.status] ?? 3) - (order[b.status] ?? 3);
      }
      return (parseInt(a.id) || 0) - (parseInt(b.id) || 0);
    });

  const counts = { pending: 0, in_progress: 0 };
  active.forEach(t => { counts[t.status]++; });

  let html = `<div class="task-card${collapsed ? ' collapsed' : ''}">`;
  html += '<div class="task-header">';
  html += `<button class="task-toggle" title="${collapsed ? t('task.expand') : t('task.collapse')}"><i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i></button>`;
  const parts = [];
  if (counts.in_progress > 0) parts.push(t('task.inProgress', { count: counts.in_progress }));
  if (counts.pending > 0) parts.push(t('task.open', { count: counts.pending }));
  if (parts.length > 0) html += `<span class="task-stats">${parts.join(', ')}</span>`;
  html += '</div>';
  html += `<div class="task-body"><div class="task-body-inner">`;

  let visibleCount = 0;
  function renderTaskItem(task, depth) {
    if (visibleCount >= MAX_VISIBLE) return;
    visibleCount++;

    const isActive = task.status === 'in_progress';
    let cls = 'task-item';
    cls += isActive ? ' task-active' : ' task-pending';
    if (depth > 0) cls += ' task-child';

    // Transition class: flip if status changed, entering if new
    const prevStatus = prevSnapshot[task.id];
    if (prevStatus && prevStatus !== task.status) {
      cls += ' task-flipping';
    } else if (!prevStatus) {
      cls += ' task-entering';
    }

    const indent = depth * 16;
    const label = (isActive && task.activeForm) ? task.activeForm : task.subject;

    html += `<div class="${cls}" data-task-id="${task.id}" style="margin-left:${indent}px">`;
    const iconName = iconMap[task.status] || 'square';
    html += `<span class="task-icon"><i data-lucide="${iconName}"></i></span>`;
    html += `<span class="task-label">${escapeHtml(label)}</span>`;
    html += '</div>';

    const children = byParent.get(task.id) || [];
    children.forEach(c => renderTaskItem(c, depth + 1));
  }

  roots.forEach(task => renderTaskItem(task, 0));

  const totalShown = active.length;
  if (totalShown > MAX_VISIBLE) {
    html += `<div class="task-more">${t('task.more', { count: totalShown - MAX_VISIBLE })}</div>`;
  }

  html += '</div></div>';
  html += '</div>';
  container.innerHTML = html;

  if (typeof lucide !== 'undefined') createIconsIn(container);

  // 「今日完成 N」折叠条——卡片底部追加(独立于卡片折叠态)
  renderTodayBar(container, todayCompleted(allTasks), sessionId);

  // Toggle handler
  const toggleBtn = container.querySelector('.task-toggle');
  if (toggleBtn) {
    toggleBtn.addEventListener('click', () => {
      const card = container.querySelector('.task-card');
      if (!card) return;
      const nowCollapsed = !card.classList.contains('collapsed');
      card.classList.toggle('collapsed', nowCollapsed);
      setCollapsed(nowCollapsed);
      toggleBtn.title = nowCollapsed ? t('task.expand') : t('task.collapse');
      toggleBtn.innerHTML = `<i data-lucide="${nowCollapsed ? 'chevron-down' : 'chevron-up'}"></i>`;
      if (typeof lucide !== 'undefined') createIconsIn(toggleBtn);
    });
  }
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
