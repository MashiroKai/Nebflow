const MAX_VISIBLE = 10;

const iconMap = {
  pending: 'square',
  in_progress: 'loader-2'
};

const activeStatuses = new Set(['pending', 'in_progress']);

export function renderTaskList(tasks) {
  const container = document.getElementById('task-list');
  if (!container) return;

  if (!tasks || tasks.length === 0) {
    container.classList.remove('has-tasks');
    container.innerHTML = '';
    return;
  }

  // Only show active tasks; completed/filtered are reflected in stats only
  const active = tasks.filter(t => activeStatuses.has(t.status));
  const terminalCount = tasks.length - active.length;

  if (active.length === 0) {
    // All done — show brief summary then collapse
    container.classList.remove('has-tasks');
    container.innerHTML = '';
    return;
  }

  container.classList.add('has-tasks');

  // Sort: in_progress first, then pending by ID
  const sorted = [...active].sort((a, b) => {
    if (a.status !== b.status) {
      const order = { in_progress: 0, pending: 1 };
      return (order[a.status] ?? 1) - (order[b.status] ?? 1);
    }
    return (parseInt(a.id) || 0) - (parseInt(b.id) || 0);
  });

  // Stats
  const counts = { pending: 0, in_progress: 0 };
  active.forEach(t => { if (counts[t.status] !== undefined) counts[t.status]++; });

  let html = '<div class="task-card">';
  html += '<div class="task-header">';
  html += `<span class="task-count">${active.length} task${active.length !== 1 ? 's' : ''}</span>`;
  const parts = [];
  if (terminalCount > 0) parts.push(`${terminalCount} done`);
  if (counts.in_progress > 0) parts.push(`${counts.in_progress} in progress`);
  if (counts.pending > 0) parts.push(`${counts.pending} open`);
  if (parts.length > 0) html += `<span class="task-stats">${parts.join(', ')}</span>`;
  html += '</div>';

  const visible = sorted.slice(0, MAX_VISIBLE);
  visible.forEach(task => {
    const isActive = task.status === 'in_progress';

    let cls = 'task-item';
    cls += isActive ? ' task-active' : ' task-pending';

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

  if (typeof lucide !== 'undefined') lucide.createIcons();
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
