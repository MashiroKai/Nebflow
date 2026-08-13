import { t } from './i18n.js';
import { createIconsIn } from './utils.js';

const MAX_VISIBLE = 20;
const COLLAPSED_KEY = 'nebflow-task-collapsed';

const iconMap = {
  pending: 'square',
  in_progress: 'loader-2',
  completed: 'check',
  failed: 'x'
};

const activeStatuses = new Set(['pending', 'in_progress']);

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
    // Fade out existing card before clearing
    const card = container.querySelector('.task-card');
    if (card && Object.keys(prevSnapshot).length > 0) {
      card.classList.add('task-card-leaving');
      setTimeout(() => {
        container.innerHTML = '';
        container.classList.remove('has-tasks');
      }, 300);
    } else {
      container.innerHTML = '';
      container.classList.remove('has-tasks');
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
