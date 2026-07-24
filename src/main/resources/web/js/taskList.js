import { t } from './i18n.js';

const MAX_VISIBLE = 15;
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

export function renderTaskList(tasks, container) {
  container = container || document.getElementById('task-list');
  if (!container) return;

  if (!tasks || tasks.length === 0) {
    container.classList.remove('has-tasks');
    container.innerHTML = '';
    return;
  }

  // Only show active tasks; completed/failed are archived
  const active = tasks.filter(t => activeStatuses.has(t.status));

  if (active.length === 0) {
    container.classList.remove('has-tasks');
    container.innerHTML = '';
    return;
  }

  container.classList.add('has-tasks');

  const collapsed = isCollapsed();

  // Build parent → children map
  const byParent = new Map();
  active.forEach(t => {
    const pid = t.parentId || null;
    if (!byParent.has(pid)) byParent.set(pid, []);
    byParent.get(pid).push(t);
  });

  // Sort within each parent by ID
  byParent.forEach(arr => arr.sort((a, b) => (parseInt(a.id) || 0) - (parseInt(b.id) || 0)));

  // Roots are tasks with no parent or whose parent is not active
  const activeIds = new Set(active.map(t => t.id));
  const roots = active
    .filter(t => !t.parentId || !activeIds.has(t.parentId))
    .sort((a, b) => {
      // in_progress first, then by ID
      if (a.status !== b.status) {
        const order = { in_progress: 0, pending: 1 };
        return (order[a.status] ?? 1) - (order[b.status] ?? 1);
      }
      return (parseInt(a.id) || 0) - (parseInt(b.id) || 0);
    });

  // Stats — only show active counts, no completed
  const counts = { pending: 0, in_progress: 0 };
  active.forEach(t => { if (counts[t.status] !== undefined) counts[t.status]++; });

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

    const indent = depth * 16;
    const iconName = iconMap[task.status] || 'square';
    const label = (isActive && task.activeForm) ? task.activeForm : task.subject;
    const blocked = task.blockedBy && task.blockedBy.length > 0
      ? ` <span class="task-blocked">${t('task.blockedBy', { ids: task.blockedBy.join(', #') })}</span>`
      : '';

    html += `<div class="${cls}" data-task-id="${task.id}" style="margin-left:${indent}px">`;
    html += `<span class="task-icon"><i data-lucide="${iconName}"></i></span>`;
    html += `<span class="task-label">${escapeHtml(label)}</span>`;
    html += `<span class="task-id">#${task.id}</span>`;
    html += blocked;
    html += '</div>';

    // Render children
    const children = byParent.get(task.id) || [];
    children.forEach(c => renderTaskItem(c, depth + 1));
  }

  roots.forEach(task => renderTaskItem(task, 0));

  const totalShown = active.length;
  if (totalShown > MAX_VISIBLE) {
    html += `<div class="task-more">${t('task.more', { count: totalShown - MAX_VISIBLE })}</div>`;
  }

  html += '</div></div>'; // .task-body-inner / .task-body
  html += '</div>'; // .task-card
  container.innerHTML = html;

  if (typeof lucide !== 'undefined') lucide.createIcons();

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
      if (typeof lucide !== 'undefined') lucide.createIcons();
    });
  }
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
