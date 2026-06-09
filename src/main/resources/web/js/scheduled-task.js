// scheduled-task.js — Session Scheduled Tasks UI (iPhone Reminders style)
import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';
import { addNotification } from './notificationBanner.js';

// Inline locale getter to avoid caching issues with module imports
function getLocale() {
  return document.documentElement.lang || navigator.language || 'en';
}

// ── State ──────────────────────────────────────────────────────────────
let tasks = [];
let isCreating = false;
let panelOpen = false;

// ── Helpers ────────────────────────────────────────────────────────────

function $(sel) { return document.querySelector(sel); }

function formatTriggerTime(epochMs) {
  const d = new Date(epochMs);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const target = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const diffDays = Math.round((target - today) / 86400000);

  const time = d.toLocaleTimeString(getLocale() === 'zh-CN' ? 'zh-CN' : 'en', {
    hour: '2-digit', minute: '2-digit', hour12: false
  });

  if (diffDays === 0) return t('task.today') + ' ' + time;
  if (diffDays === 1) return t('task.tomorrow') + ' ' + time;
  if (diffDays === -1) return t('task.yesterday') + ' ' + time;
  const dateStr = d.toLocaleDateString(getLocale() === 'zh-CN' ? 'zh-CN' : 'en', {
    month: 'short', day: 'numeric'
  });
  return dateStr + ' ' + time;
}

function isOverdue(t) {
  return !t.triggered && t.triggerAt < Date.now();
}

function pendingCount() {
  return tasks.filter(t => !t.triggered).length;
}

function defaultTriggerAt() {
  // Always recalculate from NOW to avoid stale past times
  const d = new Date(Date.now() + 3600000);
  d.setMinutes(Math.ceil(d.getMinutes() / 5) * 5, 0, 0);
  return d;
}

function toLocalDatetimeString(d) {
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

// ── Badge ──────────────────────────────────────────────────────────────

function updateBadge() {
  const btn = $('#reminder-btn');
  if (!btn) return;
  let badge = btn.querySelector('.reminder-badge');
  const count = pendingCount();
  if (count > 0) {
    if (!badge) {
      badge = document.createElement('span');
      badge.className = 'reminder-badge';
      btn.appendChild(badge);
    }
    badge.textContent = count > 9 ? '9+' : count;
  } else if (badge) {
    badge.remove();
  }
}

// ── Render ─────────────────────────────────────────────────────────────

function renderList() {
  const body = $('#reminder-panel .reminder-panel-body');
  if (!body) return;

  const pending = tasks.filter(t => !t.triggered);
  const triggered = tasks.filter(t => t.triggered);

  // Update header count
  const countEl = $('#reminder-panel .reminder-panel-count');
  if (countEl) countEl.textContent = pending.length > 0 ? t('task.pendingCount', { count: pending.length }) : '';

  // Clear and rebuild
  body.innerHTML = '';

  if (tasks.length === 0 && !isCreating) {
    body.appendChild(buildEmptyState());
  } else {
    for (const r of pending) body.appendChild(buildRow(r, false));
    for (const r of triggered.slice(0, 3)) body.appendChild(buildRow(r, true));
  }

  // Create form (always at bottom)
  if (isCreating) {
    body.appendChild(buildInlineCreate());
  }

  if (typeof lucide !== 'undefined') lucide.createIcons();

  // Focus inline input if creating
  if (isCreating) {
    const input = body.querySelector('#reminder-inline-input');
    if (input) requestAnimationFrame(() => input.focus());
  }
}

// ── DOM builders (returns elements, not HTML strings, for reliable event binding) ──

function buildEmptyState() {
  const el = document.createElement('div');
  el.className = 'reminder-empty';
  el.innerHTML = `
    <div class="reminder-empty-text">${t('task.empty')}</div>
    <div class="reminder-empty-hint">${t('task.emptyHint')}</div>`;
  el.addEventListener('click', (e) => {
    e.stopPropagation();
    startInlineCreate();
  });
  return el;
}

function buildRow(r, isTriggered) {
  const row = document.createElement('div');
  row.className = 'reminder-row' + (isTriggered ? ' triggered' : '');
  if (!isTriggered && isOverdue(r)) row.classList.add('overdue');
  row.dataset.id = r.id;

  // Circle button
  const circle = document.createElement('button');
  circle.className = 'reminder-circle';
  if (isTriggered) {
    circle.classList.add('completed');
    circle.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>';
    circle.disabled = true;
  } else {
    circle.dataset.action = 'delete';
    circle.dataset.id = r.id;
    circle.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteTask(r.id);
    });
  }

  // Content area — single line
  const content = document.createElement('div');
  content.className = 'reminder-row-content';

  const textSpan = document.createElement('span');
  textSpan.className = 'reminder-row-text';
  textSpan.textContent = r.content;

  const timeSpan = document.createElement('span');
  timeSpan.className = 'reminder-row-time';
  if (isTriggered) {
    timeSpan.textContent = t('task.triggered') + ' ' + formatTriggerTime(r.triggerAt);
  } else {
    timeSpan.textContent = formatTriggerTime(r.triggerAt);
    if (isOverdue(r)) {
      const tag = document.createElement('span');
      tag.className = 'reminder-overdue-tag';
      tag.textContent = t('task.overdue');
      timeSpan.appendChild(tag);
    }
  }

  content.appendChild(textSpan);
  content.appendChild(timeSpan);

  row.appendChild(circle);
  row.appendChild(content);
  return row;
}

function buildInlineCreate() {
  const wrap = document.createElement('div');
  wrap.className = 'reminder-inline-create';
  wrap.id = 'reminder-inline-create';

  const dtStr = toLocalDatetimeString(defaultTriggerAt());
  const minStr = toLocalDatetimeString(new Date());

  wrap.innerHTML = `
    <div class="reminder-inline-main">
      <span class="reminder-circle ghost"></span>
      <input type="text" class="reminder-inline-input" id="reminder-inline-input"
        placeholder="${t('task.inputPlaceholder')}" autocomplete="off">
    </div>
    <div class="reminder-inline-detail">
      <div class="reminder-time-row">
        <i data-lucide="clock"></i>
        <input type="datetime-local" id="reminder-time-input" value="${dtStr}" min="${minStr}">
      </div>
    </div>`;

  // Bind events
  const input = wrap.querySelector('#reminder-inline-input');
  const timeInput = wrap.querySelector('#reminder-time-input');

  if (input) {
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        saveInlineTask();
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        cancelInlineCreate();
      }
    });
  }

  // Update min when time input is focused (prevents stale past-time validation)
  if (timeInput) {
    timeInput.addEventListener('focus', () => {
      timeInput.min = toLocalDatetimeString(new Date());
    });
  }

  return wrap;
}

// ── Actions ────────────────────────────────────────────────────────────

function openPanel() {
  const panel = $('#reminder-panel');
  if (!panel) return;
  panelOpen = true;
  isCreating = false;
  panel.classList.add('open');

  const title = panel.querySelector('.reminder-panel-title');
  if (title) title.textContent = t('task.panelTitle');
  if (typeof lucide !== 'undefined') lucide.createIcons();

  if (state.activeSessionId) {
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId });
  }
}

function closePanel() {
  const panel = $('#reminder-panel');
  if (!panel) return;
  panelOpen = false;
  isCreating = false;
  panel.classList.remove('open');
}

function togglePanel() {
  if (panelOpen) closePanel();
  else openPanel();
}

function startInlineCreate() {
  if (isCreating) return;
  isCreating = true;
  renderList();
}

function cancelInlineCreate() {
  isCreating = false;
  renderList();
}

function saveInlineTask() {
  const input = document.querySelector('#reminder-inline-input');
  const timeInput = document.querySelector('#reminder-time-input');
  if (!input || !timeInput) return;

  const content = input.value.trim();
  if (!content) {
    cancelInlineCreate();
    return;
  }

  const triggerAt = new Date(timeInput.value).getTime();
  if (!triggerAt || isNaN(triggerAt)) {
    timeInput.focus();
    return;
  }

  // If time is in the past, auto-bump to 5 min from now
  const now = Date.now();
  const effectiveTriggerAt = triggerAt <= now
    ? (() => { const d = new Date(now + 300000); d.setMinutes(Math.ceil(d.getMinutes() / 5) * 5, 0, 0); return d.getTime(); })()
    : triggerAt;

  sendWs({
    type: 'createScheduledTask',
    sessionId: state.activeSessionId,
    content: content,
    triggerAt: effectiveTriggerAt,
    referencePath: undefined
  });

  // Optimistic add — append to end
  tasks.push({
    id: 'temp-' + Date.now(),
    content: content,
    triggerAt: effectiveTriggerAt,
    createdAt: Date.now(),
    triggered: false,
    triggeredAt: null,
    referencePath: null
  });
  isCreating = false;
  renderList();
  updateBadge();
}

function deleteTask(id) {
  if (!state.activeSessionId) return;

  const row = document.querySelector(`.reminder-row[data-id="${id}"]`);
  if (row) {
    const circle = row.querySelector('.reminder-circle');
    if (circle) circle.classList.add('completing');
    row.classList.add('removing');
    setTimeout(() => {
      sendWs({ type: 'deleteScheduledTask', sessionId: state.activeSessionId, id: id });
      tasks = tasks.filter(t => t.id !== id);
      renderList();
      updateBadge();
    }, 350);
  } else {
    sendWs({ type: 'deleteScheduledTask', sessionId: state.activeSessionId, id: id });
    tasks = tasks.filter(t => t.id !== id);
    renderList();
    updateBadge();
  }
}

// ── WS Message Handlers ───────────────────────────────────────────────

onMessage('scheduledTaskList', (msg) => {
  tasks = msg.tasks || [];
  renderList();
  updateBadge();
});

onMessage('scheduledTaskCreated', (msg) => {
  if (state.activeSessionId) {
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId });
  }
  updateBadge();
});

onMessage('scheduledTaskDeleted', (msg) => {
  tasks = tasks.filter(t => t.id !== msg.id);
  renderList();
  updateBadge();
});

onMessage('scheduledTaskTriggered', (msg) => {
  // Show persistent notification regardless of active session, so it survives session switches
  if (msg.task && msg.task.content) {
    addNotification('task', msg.task.content, { dismissAfter: 60000 });
  }

  if (msg.sessionId === state.activeSessionId) {
    tasks = tasks.map(t =>
      t.id === (msg.task && msg.task.id)
        ? { ...t, triggered: true, triggeredAt: Date.now() }
        : t
    );
    renderList();
    updateBadge();

    if (msg.task) {
      const ref = msg.task.referencePath
        ? `<div class="reminder-row-ref" style="margin-top:4px"><i data-lucide="file-text" style="width:12px;height:12px"></i>${escapeHtml(msg.task.referencePath)}</div>`
        : '';
      const bubble = document.createElement('div');
      bubble.className = 'reminder-trigger-bubble';
      bubble.innerHTML = `
        <div class="reminder-trigger-header"><i data-lucide="bell-ring"></i> ${t('task.triggerTitle')}</div>
        <div class="reminder-trigger-content">${escapeHtml(msg.task.content)}</div>
        <div class="reminder-trigger-time">${t('task.scheduledAt', { time: msg.task.formattedTime || formatTriggerTime(msg.task.triggerAt) })}</div>
        ${ref}`;
      const chat = document.getElementById('chat');
      if (chat) {
        const row = document.createElement('div');
        row.className = 'row system';
        row.appendChild(bubble);
        chat.appendChild(row);
        if (typeof lucide !== 'undefined') lucide.createIcons();
        chat.scrollTop = chat.scrollHeight;
      }
    }

    if (!panelOpen) openPanel();
  } else {
    // Non-active session: still update local task state so badge is correct
    tasks = tasks.map(t =>
      t.id === (msg.task && msg.task.id)
        ? { ...t, triggered: true, triggeredAt: Date.now() }
        : t
    );
    updateBadge();
  }
});

// ── Public Init ────────────────────────────────────────────────────────

export function initScheduledTask() {
  const btn = $('#reminder-btn');
  if (btn) {
    btn.title = t('task.panelTitle');
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      togglePanel();
    });
  }

  // "+" button in panel header — use direct event listener (not delegation)
  const createBtn = $('#reminder-create-btn');
  if (createBtn) {
    createBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      if (isCreating) {
        saveInlineTask();
      } else {
        startInlineCreate();
      }
    });
  }

  // Body click: click empty area to create/save
  const body = $('#reminder-panel .reminder-panel-body');
  if (body) {
    body.addEventListener('click', (e) => {
      if (e.target.closest('button, input, a, .reminder-row')) return;
      if (isCreating) saveInlineTask();
      else startInlineCreate();
    });
  }

  // Close panel on outside click
  document.addEventListener('click', (e) => {
    if (!panelOpen) return;
    const panel = $('#reminder-panel');
    if (panel && !panel.contains(e.target) && !e.target.closest('#reminder-btn')) {
      closePanel();
    }
  });
}

/** Called when session switches — refresh task list */
export function refreshScheduledTasks(sessionId) {
  tasks = [];
  isCreating = false;
  if (panelOpen && sessionId) {
    sendWs({ type: 'listScheduledTasks', sessionId: sessionId });
  } else {
    renderList();
    updateBadge();
  }
}
