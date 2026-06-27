import { t } from './i18n.js';

const MAX_VISIBLE = 10;
const COLLAPSED_KEY = 'nebflow-task-collapsed';

const iconMap = {
  pending: 'square',
  in_progress: 'loader-2'
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

  const collapsed = isCollapsed();

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

  let html = `<div class="task-card${collapsed ? ' collapsed' : ''}">`;
  html += '<div class="task-header">';
  html += `<button class="task-toggle" title="${collapsed ? t('task.expand') : t('task.collapse')}"><i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i></button>`;
  html += `<span class="task-count">${t('task.count', { count: active.length })}</span>`;
  const parts = [];
  if (terminalCount > 0) parts.push(t('task.done', { count: terminalCount }));
  if (counts.in_progress > 0) parts.push(t('task.inProgress', { count: counts.in_progress }));
  if (counts.pending > 0) parts.push(t('task.open', { count: counts.pending }));
  if (parts.length > 0) html += `<span class="task-stats">${parts.join(', ')}</span>`;
  html += '</div>';

  html += `<div class="task-body"><div class="task-body-inner">`;

  const visible = sorted.slice(0, MAX_VISIBLE);
  visible.forEach(task => {
    const isActive = task.status === 'in_progress';

    let cls = 'task-item';
    cls += isActive ? ' task-active' : ' task-pending';

    const iconName = iconMap[task.status] || 'square';
    const label = (isActive && task.activeForm) ? task.activeForm : task.subject;
    const blocked = task.blockedBy && task.blockedBy.length > 0
      ? ` <span class="task-blocked">${t('task.blockedBy', { ids: task.blockedBy.join(', #') })}</span>`
      : '';

    html += `<div class="${cls}" data-task-id="${task.id}">`;
    html += `<span class="task-icon"><i data-lucide="${iconName}"></i></span>`;
    html += `<span class="task-label">${escapeHtml(label)}</span>`;
    html += `<span class="task-id">#${task.id}</span>`;
    html += blocked;
    html += '</div>';
  });

  if (sorted.length > MAX_VISIBLE) {
    html += `<div class="task-more">${t('task.more', { count: sorted.length - MAX_VISIBLE })}</div>`;
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
      const icon = toggleBtn.querySelector('i');
      if (icon) icon.setAttribute('data-lucide', nowCollapsed ? 'chevron-down' : 'chevron-up');
      if (typeof lucide !== 'undefined') lucide.createIcons();
    });
  }
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
