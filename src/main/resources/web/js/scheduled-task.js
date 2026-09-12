// scheduled-task.js — Session Scheduled Tasks UI (iPhone Reminders style)
import state from './state.js';
import { key } from './branding.js';
import { sendWs, onMessage, onReconnect } from './ws.js';
import { t } from './i18n.js';
import { addNotification } from './notificationBanner.js';
import { createIconsIn, shouldFollowBottom } from './utils.js';
import { chatViews } from './chatView.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';

// Inline locale getter to avoid caching issues with module imports
function getLocale() {
  return document.documentElement.lang || navigator.language || 'en';
}

// ── State ──────────────────────────────────────────────────────────────
let tasks = [];
let isCreating = false;
let panelOpen = false;
// Recurrence selected in the inline create form ("once" | "hourly" | "daily"
// | "weekly"). Reset whenever the form opens. Wire values match ScheduleTool
// schema — "once" maps to omitting repeat (one-shot).
let currentRepeat = 'once';

// Creates attempted while the WS was down (sendWs silently drops non-OPEN
// sends). Queued instead of optimistically added — the UI must never show a
// task the server never received. Flushed on reconnect / session restore.
let pendingCreates = [];

// ── Persistence ────────────────────────────────────────────────────────
// Cache tasks per-session in localStorage so the panel shows instantly
// on page reload or session switch, before the WS response arrives.
const TASKS_CACHE_PREFIX = key('tasks_');
const PANEL_OPEN_KEY = key('reminder_panel_open');

function loadCachedTasks(sessionId) {
  if (!sessionId) return [];
  try {
    const raw = localStorage.getItem(TASKS_CACHE_PREFIX + sessionId);
    return raw ? JSON.parse(raw) : [];
  } catch (_) { return []; }
}

function saveCachedTasks(sessionId, taskList) {
  if (!sessionId) return;
  try {
    localStorage.setItem(TASKS_CACHE_PREFIX + sessionId, JSON.stringify(taskList));
  } catch (_) {}
}

function loadPanelOpen() {
  try { return localStorage.getItem(PANEL_OPEN_KEY) === 'true'; }
  catch (_) { return false; }
}

function savePanelOpen(open) {
  try { localStorage.setItem(PANEL_OPEN_KEY, open ? 'true' : 'false'); }
  catch (_) {}
}

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
  // 1-hour from now, rounded UP to the next minute.
  const d = new Date(Date.now() + 3600000);
  d.setSeconds(0, 0);
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

/** Localized label for a repeat value ("hourly" | "daily" | "weekly"), or ''
 *  for one-shot — the badge is only rendered for recurring tasks. */
function repeatLabel(r) {
  if (r === 'hourly') return t('task.repeatHourly');
  if (r === 'daily') return t('task.repeatDaily');
  if (r === 'weekly') return t('task.repeatWeekly');
  return '';
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

  // Update header count
  const countEl = $('#reminder-panel .reminder-panel-count');
  if (countEl) countEl.textContent = pending.length > 0 ? pending.length : '';

  // Clear and rebuild
  body.innerHTML = '';

  if (tasks.length === 0 && !isCreating) {
    body.appendChild(buildEmptyState());
  } else {
    for (const r of pending) body.appendChild(buildRow(r, false));
  }

  // Create form (always at bottom)
  if (isCreating) {
    body.appendChild(buildInlineCreate());
  }

  if (typeof lucide !== 'undefined') createIconsIn(body);

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
  if (r.enabled === false) row.classList.add('disabled');
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

  // Content area
  const content = document.createElement('div');
  content.className = 'reminder-row-content';

  // Title line: task text (ellipsis) + repeat badge for recurring tasks.
  // One-shot tasks get no badge (restraint; the repeat field is the signal).
  const titleRow = document.createElement('div');
  titleRow.className = 'reminder-row-title';
  const textSpan = document.createElement('span');
  textSpan.className = 'reminder-row-text';
  textSpan.textContent = r.content;
  titleRow.appendChild(textSpan);
  const badgeLabel = repeatLabel(r.repeat);
  if (badgeLabel) {
    const badge = document.createElement('span');
    badge.className = 'reminder-repeat-badge';
    badge.dataset.repeat = r.repeat;
    badge.textContent = badgeLabel;
    titleRow.appendChild(badge);
  }
  content.appendChild(titleRow);

  // Time info: show next trigger + last triggered when available
  const timeSpan = document.createElement('span');
  timeSpan.className = 'reminder-row-time';
  if (isTriggered) {
    timeSpan.textContent = t('task.triggered') + ' ' + formatTriggerTime(r.triggerAt);
  } else {
    const nextTime = r.nextTrigger || r.triggerAt;
    timeSpan.textContent = formatTriggerTime(nextTime);
    if (isOverdue({ triggered: false, triggerAt: nextTime })) {
      const tag = document.createElement('span');
      tag.className = 'reminder-overdue-tag';
      tag.textContent = t('task.overdue');
      timeSpan.appendChild(tag);
    }
  }

  content.appendChild(timeSpan);

  // Last triggered sub-text (if available)
  if (r.lastTriggered) {
    const lastEl = document.createElement('div');
    lastEl.className = 'reminder-row-last';
    lastEl.textContent = t('task.lastTriggered') + ' ' + formatTriggerTime(r.lastTriggered);
    content.appendChild(lastEl);
  }

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
      <div class="reminder-repeat-row">
        <i data-lucide="repeat"></i>
        <div class="reminder-repeat-seg" id="reminder-repeat-seg" role="radiogroup" aria-label="${t('task.repeat')}">
          <button type="button" data-repeat="once" class="active" role="radio" aria-checked="true">${t('task.repeatOnce')}</button>
          <button type="button" data-repeat="hourly" role="radio" aria-checked="false">${t('task.repeatHourly')}</button>
          <button type="button" data-repeat="daily" role="radio" aria-checked="false">${t('task.repeatDaily')}</button>
          <button type="button" data-repeat="weekly" role="radio" aria-checked="false">${t('task.repeatWeekly')}</button>
        </div>
      </div>
    </div>`;

  // Bind events
  const input = wrap.querySelector('#reminder-inline-input');
  const timeInput = wrap.querySelector('#reminder-time-input');
  const repeatSeg = wrap.querySelector('#reminder-repeat-seg');

  // Repeat segmented control — the active pill is currentRepeat; values match
  // ScheduleTool schema ("hourly"|"daily"|"weekly"; "once" omits repeat).
  if (repeatSeg) {
    repeatSeg.addEventListener('click', (e) => {
      if (!(e.target instanceof Element)) return;
      const btn = /** @type {HTMLElement | null} */ (e.target.closest('button[data-repeat]'));
      if (!btn) return;
      repeatSeg.querySelectorAll('button').forEach(b => {
        const active = b === btn;
        b.classList.toggle('active', active);
        b.setAttribute('aria-checked', active ? 'true' : 'false');
      });
      currentRepeat = btn.dataset.repeat;
    });
  }

  if (input) {
    // ⑤ 组字期间 Enter/Esc 交还输入法（非组字态行为逐键不变）。
    bindImeGuard(input);
    input.addEventListener('keydown', (e) => {
      if (isImeComposing(e, input)) return;
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
  savePanelOpen(true);

  if (typeof lucide !== 'undefined') createIconsIn(panel);

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
  savePanelOpen(false);
}

function togglePanel() {
  if (panelOpen) closePanel();
  else openPanel();
}

function startInlineCreate() {
  if (isCreating) return;
  isCreating = true;
  currentRepeat = 'once';   // fresh form → one-shot by default
  renderList();
}

function cancelInlineCreate() {
  isCreating = false;
  renderList();
}

// Send a create frame and add the optimistic temp row. Only call this when
// the WS is OPEN and a session is active — otherwise the row would be a lie
// (sendWs silently drops non-OPEN sends).
function submitCreate(content, triggerAt, repeat) {
  const payload = {
    type: 'createScheduledTask',
    sessionId: state.activeSessionId,
    content: content,
    triggerAt: triggerAt,
    referencePath: undefined
  };
  // "once" = one-shot = omit repeat (ScheduleTool schema: optional field);
  // hourly/daily/weekly are sent verbatim.
  if (repeat && repeat !== 'once') payload.repeat = repeat;
  sendWs(payload);

  // Optimistic add — append to end
  tasks.push({
    id: 'temp-' + Date.now(),
    content: content,
    triggerAt: triggerAt,
    createdAt: Date.now(),
    triggered: false,
    triggeredAt: null,
    referencePath: null,
    repeat: repeat && repeat !== 'once' ? repeat : undefined
  });
  if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
}

function wsReady() {
  return !!(state.ws && state.ws.readyState === WebSocket.OPEN && state.activeSessionId);
}

function flushPendingCreates() {
  if (pendingCreates.length === 0 || !wsReady()) return;
  const queued = pendingCreates;
  pendingCreates = [];
  for (const q of queued) {
    // Same auto-bump semantics as saveInlineTask: a trigger time that expired
    // while queued becomes 2 min from now.
    const triggerAt = q.triggerAt <= Date.now() ? Date.now() + 120000 : q.triggerAt;
    submitCreate(q.content, triggerAt, q.repeat);
  }
  renderList();
  updateBadge();
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

  // If time is within 1 minute of now, auto-bump to 2 min from now.
  // This prevents race conditions where a near-future time expires by the
  // time the WS message round-trips to the server.
  const now = Date.now();
  const effectiveTriggerAt = triggerAt <= now + 60000
    ? now + 120000
    : triggerAt;

  if (!wsReady()) {
    // WS down (reconnect backoff, half-open socket, server restart) or no
    // active session yet — queue instead of lying with an optimistic row.
    pendingCreates.push({ content: content, triggerAt: effectiveTriggerAt, repeat: currentRepeat });
    addNotification('task', t('task.queuedOffline'), { dismissAfter: 15000 });
    isCreating = false;
    renderList();
    return;
  }

  submitCreate(content, effectiveTriggerAt, currentRepeat);
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
      saveCachedTasks(state.activeSessionId, tasks);
      renderList();
      updateBadge();
    }, 350);
  } else {
    sendWs({ type: 'deleteScheduledTask', sessionId: state.activeSessionId, id: id });
    tasks = tasks.filter(t => t.id !== id);
    saveCachedTasks(state.activeSessionId, tasks);
    renderList();
    updateBadge();
  }
}

// ── WS Message Handlers ───────────────────────────────────────────────

onMessage('scheduledTaskList', (msg) => {
  tasks = msg.tasks || [];
  if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
  renderList();
  updateBadge();
});

onMessage('scheduledTaskCreated', (msg) => {
  if (state.activeSessionId) {
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId });
  }
  updateBadge();
});

// Server-side rejection of a create (invalid fields, etc.). The backend tags
// these with msgType: 'createScheduledTask'. Roll back the optimistic temp
// row so the list never shows a task the server refused to persist.
onMessage('error', (msg) => {
  if (msg.msgType !== 'createScheduledTask') return;
  // Remove the most recent optimistic temp row (the one this error answers).
  for (let i = tasks.length - 1; i >= 0; i--) {
    if (typeof tasks[i].id === 'string' && tasks[i].id.startsWith('temp-')) {
      tasks.splice(i, 1);
      break;
    }
  }
  if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
  renderList();
  updateBadge();
  addNotification('task', msg.message || t('task.createFailed'), { dismissAfter: 15000 });
});

onMessage('scheduledTaskDeleted', (msg) => {
  tasks = tasks.filter(t => t.id !== msg.id);
  renderList();
  updateBadge();
});

onMessage('scheduledTaskToggled', (msg) => {
  // Server confirmed toggle — update local state
  tasks = tasks.map(t => t.id === msg.id ? { ...t, enabled: msg.enabled } : t);
  if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
  renderList();
  updateBadge();
});

onMessage('scheduledTaskTriggered', (msg) => {
  // Show persistent notification regardless of active session, so it survives session switches
  if (msg.task && msg.task.content) {
    addNotification('task', msg.task.content, { dismissAfter: 60000 });
  }

  if (msg.sessionId === state.activeSessionId) {
    // Remove the triggered task from the list immediately
    tasks = tasks.filter(t => t.id !== (msg.task && msg.task.id));
    if (state.activeSessionId) saveCachedTasks(state.activeSessionId, tasks);
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
        if (typeof lucide !== 'undefined') createIconsIn(bubble);
        // A-branch (2026-09-11): conditional — a fired schedule used to yank the
        // viewport to the bottom even when the user was reading history. Now it
        // follows only when the user is already at/near the bottom; otherwise the
        // row lands and the ↓ N pill counts it.
        if (shouldFollowBottom(chatViews.primary, chat)) chat.scrollTop = chat.scrollHeight;
      }
    }

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
  // Header clock button — toggle panel
  const reminderBtn = $('#reminder-btn');
  if (reminderBtn) {
    reminderBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      togglePanel();
    });
  }

  // "+" button in panel header
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

  // Panel body click: click empty area to create/save
  const body = $('#reminder-panel .reminder-panel-body');
  if (body) {
    body.addEventListener('click', (e) => {
      if (e.target.closest('button, input, a, .reminder-row')) return;
      if (isCreating) saveInlineTask();
      else startInlineCreate();
    });
  }

  // Outside click: close panel
  document.addEventListener('click', (e) => {
    const panel = $('#reminder-panel');
    if (panel && panelOpen && !panel.contains(e.target) && !e.target.closest('#reminder-btn')) {
      closePanel();
    }
  });

  // Restore panel open state from last session
  if (loadPanelOpen()) {
    const panel = $('#reminder-panel');
    if (panel) {
      panelOpen = true;
      panel.classList.add('open');
    }
  }

  // Flush any creates queued while the WS was down once the connection is
  // (re)established. Runs on every open; the queue is normally empty.
  onReconnect(flushPendingCreates);

  // Auto-load tasks for active session
  if (state.activeSessionId) {
    // Show cached tasks instantly, then refresh from server
    tasks = loadCachedTasks(state.activeSessionId);
    renderList();
    updateBadge();
    sendWs({ type: 'listScheduledTasks', sessionId: state.activeSessionId });
  } else {
    // No active session yet — still render cached tasks for when session arrives
    tasks = loadCachedTasks(state.activeSessionId) || [];
    renderList();
    updateBadge();
  }
}

/** Called when session switches — refresh task list */
export function refreshScheduledTasks(sessionId) {
  isCreating = false;
  // Session just became available — retry any creates queued while there was
  // no active session (flush no-ops when the queue is empty or WS is down).
  flushPendingCreates();
  if (sessionId) {
    // Show cached tasks instantly, then refresh from server
    tasks = loadCachedTasks(sessionId);
    renderList();
    updateBadge();
    sendWs({ type: 'listScheduledTasks', sessionId: sessionId });
  } else {
    tasks = [];
    renderList();
    updateBadge();
  }
}
