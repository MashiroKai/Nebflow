import state from './state.js';

const RECENT_COMPLETED_TTL_MS = 30000;
const MAX_VISIBLE = 10;

const iconMap = {
  pending: 'square',
  in_progress: 'loader-2',
  completed: 'check-square'
};

export function renderTaskList(tasks) {
  const container = document.getElementById('task-list');
  if (!container) return;

  // Issue #8: Bind timers to container element instead of module global
  // Clear old fade timers for this container
  if (container._fadeTimers) {
    container._fadeTimers.forEach(t => clearTimeout(t));
  }
  container._fadeTimers = [];

  if (!tasks || tasks.length === 0) {
    container.classList.remove('has-tasks');
    container.innerHTML = '';
    return;
  }

  container.classList.add('has-tasks');

  // Sort: in_progress first, then pending, then completed
  const order = { in_progress: 0, pending: 1, completed: 2 };
  const sorted = [...tasks].sort((a, b) => {
    const oa = order[a.status] ?? 1;
    const ob = order[b.status] ?? 1;
    if (oa !== ob) return oa - ob;
    return (parseInt(a.id) || 0) - (parseInt(b.id) || 0);
  });

  // Stats
  const counts = { pending: 0, in_progress: 0, completed: 0 };
  tasks.forEach(t => { if (counts[t.status] !== undefined) counts[t.status]++; });
  const total = tasks.length;

  let html = '<div class="task-card">';
  html += '<div class="task-header">';
  html += `<span class="task-count">${total} task${total !== 1 ? 's' : ''}</span>`;
  const parts = [];
  if (counts.completed > 0) parts.push(`${counts.completed} done`);
  if (counts.in_progress > 0) parts.push(`${counts.in_progress} in progress`);
  if (counts.pending > 0) parts.push(`${counts.pending} open`);
  if (parts.length > 0) html += `<span class="task-stats">${parts.join(', ')}</span>`;
  html += '</div>';

  const visible = sorted.slice(0, MAX_VISIBLE);
  visible.forEach(task => {
    const isActive = task.status === 'in_progress';
    const isDone = task.status === 'completed';
    const isPending = task.status === 'pending';

    let cls = 'task-item';
    if (isActive) cls += ' task-active';
    else if (isDone) cls += ' task-done';
    else cls += ' task-pending';

    if (isDone) cls += ' task-fade';

    const iconName = iconMap[task.status] || 'square';

    const label = (isActive && task.activeForm) ? task.activeForm : task.subject;
    const blocked = task.blockedBy && task.blockedBy.length > 0
      ? ` <span class="task-blocked">blocked by #${task.blockedBy.join(', #')}</span>`
      : '';

    html += `<div class="${cls}" data-task-id="${task.id}">`;
    html += `<span class="task-icon"><i data-lucide="${iconName}"></i></span>`;
    html += `<span class="task-label">${escapeHtml(label)}</span>`;
    html += `<span class="task-id">#${task.id}</span>`;
    html += blocked;
    html += '</div>';
  });

  if (sorted.length > MAX_VISIBLE) {
    html += `<div class="task-more">+${sorted.length - MAX_VISIBLE} more</div>`;
  }

  html += '</div>';
  container.innerHTML = html;

  // Re-render lucide icons
  if (typeof lucide !== 'undefined') lucide.createIcons();

  // Fade completed tasks after TTL
  container.querySelectorAll('.task-done.task-fade').forEach(el => {
    const timer = setTimeout(() => {
      el.classList.add('task-fading');
      setTimeout(() => {
        if (el.parentNode) {
          el.style.maxHeight = '0';
          el.style.padding = '0';
          el.style.margin = '0';
          el.style.opacity = '0';
          el.style.overflow = 'hidden';
        }
      }, 300);
    }, RECENT_COMPLETED_TTL_MS);
    container._fadeTimers.push(timer);
  });
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
