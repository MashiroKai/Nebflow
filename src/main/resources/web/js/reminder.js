// reminder.js — Session Scheduled Tasks UI (Apple Reminders style)
import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { renderSystemBubble } from './chat.js';
import { t } from './i18n.js';
import { escapeHtml } from './utils.js';

// ── State ──────────────────────────────────────────────────────────────
let reminders = [];
let isCreating = false;
let panelOpen = false;

// ── Helpers ────────────────────────────────────────────────────────────

function $(sel) { return document.querySelector(sel); }
function $$(sel) { return document.querySelectorAll(sel); }

function formatTriggerTime(epochMs) {
  const d = new Date(epochMs);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const target = new Date(d.getFullYear(), d.getMonth(), now.getDate());
  const diffDays = Math.round((target - today) / 86400000);

  const time = d.toLocaleTimeString(getLocale() === 'zh-CN' ? 'zh-CN' : 'en', { hour: '2-digit', minute: '2-digit', hour12: false });

  if (diffDays === 0) return t('reminder.today') + ' ' + time;
  if (diffDays === 1) return t('reminder.tomorrow') + ' ' + time;
  if (diffDays === -1) return t('reminder.yesterday') + ' ' + time;
  const dateStr = d.toLocaleDateString(getLocale() === 'zh-CN' ? 'zh-CN' : 'en', { month: 'short', day: 'numeric' });
  return dateStr + ' ' + time;
}

function isOverdue(r) {
  return !r.triggered && r.triggerAt < Date.now();
}

function pendingCount() {
  return reminders.filter(r => !r.triggered).length;
}

function defaultTriggerAt() {
  const d = new Date(Date.now() + 3600000);
  d.setMinutes(Math.ceil(d.getMinutes() / 5) * 5, 0, 0);
  return d;
}

function toLocalDatetimeString(d) {
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
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

  const pending = reminders.filter(r => !r.triggered);
  const triggered = reminders.filter(r => r.triggered);

  // Update header count
  const countEl = $('#reminder-panel .reminder-panel-count');
  if (countEl) countEl.textContent = pending.length > 0 ? t('reminder.pendingCount', { count: pending.length }) : '';

  // Build list HTML
  let html = '';

  if (isCreating) html += renderInlineCreate();

  if (reminders.length === 0 && !isCreating) {
    html += renderEmptyState();
  } else {
    for (const r of pending) html += renderRow(r, false);
    for (const r of triggered.slice(0, 5)) html += renderRow(r, true);
  }

  body.innerHTML = html;
  if (typeof lucide !== 'undefined') lucide.createIcons();

  // Bind events inside body
  bindBodyEvents();

  // Focus inline input if creating
  if (isCreating) {
    const input = $('#reminder-inline-input');
    if (input) setTimeout(() => input.focus(), 50);
  }
}

function renderEmptyState() {
  return `
    <div class="reminder-empty" id="reminder-empty-area">
      <div class="reminder-empty-text">${t('reminder.empty')}</div>
      <div class="reminder-empty-hint">${t('reminder.emptyHint')}</div>
    </div>`;
}

function renderRow(r, isTriggered) {
  const cls = isTriggered ? 'reminder-row triggered' : 'reminder-row';
  const timeCls = !isTriggered && isOverdue(r) ? 'reminder-row-time overdue' : 'reminder-row-time';
  const checkSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>';

  const refHtml = r.referencePath
    ? `<div class="reminder-row-ref"><i data-lucide="file-text"></i>${escapeHtml(r.referencePath)}</div>`
    : '';

  if (isTriggered) {
    return `<div class="${cls}" data-id="${r.id}">
      <button class="reminder-complete-btn completing">${checkSvg}</button>
      <div class="reminder-row-content">
        <div class="${timeCls}">${t('reminder.triggered')} ${formatTriggerTime(r.triggerAt)}</div>
        <div class="reminder-row-text">${escapeHtml(r.content)}</div>
        ${refHtml}
      </div>
    </div>`;
  }

  return `<div class="${cls}" data-id="${r.id}">
    <button class="reminder-complete-btn" data-action="delete" data-id="${r.id}">${checkSvg}</button>
    <div class="reminder-row-content">
      <div class="${timeCls}"><i data-lucide="clock"></i> ${formatTriggerTime(r.triggerAt)}</div>
      <div class="reminder-row-text">${escapeHtml(r.content)}</div>
      ${refHtml}
    </div>
  </div>`;
}

function renderInlineCreate() {
  const dtStr = toLocalDatetimeString(defaultTriggerAt());
  const minStr = toLocalDatetimeString(new Date());
  return `<div class="reminder-inline-create" id="reminder-inline-create">
    <div class="reminder-inline-row">
      <span class="reminder-complete-btn" style="border-style:dashed;opacity:0.4;pointer-events:none"></span>
      <input type="text" class="reminder-inline-input" id="reminder-inline-input"
        placeholder="${t('reminder.inputPlaceholder')}" autocomplete="off">
      <button class="reminder-inline-cancel" id="reminder-inline-cancel" title="${t('reminder.cancel')}">
        <i data-lucide="x"></i>
      </button>
    </div>
    <div class="reminder-inline-detail">
      <div class="reminder-time-row">
        <label><i data-lucide="clock"></i> ${t('reminder.timeLabel')}</label>
        <input type="datetime-local" id="reminder-time-input" value="${dtStr}" min="${minStr}">
      </div>
      <div class="reminder-ref-row">
        <label><i data-lucide="file-text"></i> ${t('reminder.refLabel')}</label>
        <input type="text" id="reminder-ref-input" placeholder="${t('reminder.refPlaceholder')}" autocomplete="off">
      </div>
    </div>
  </div>`;
}

// ── Actions ────────────────────────────────────────────────────────────

function openPanel() {
  const panel = $('#reminder-panel');
  if (!panel) return;
  panelOpen = true;
  isCreating = false;
  panel.classList.add('open');

  // Apply i18n to panel elements
  const title = panel.querySelector('.reminder-panel-title');
  if (title) title.textContent = t('reminder.panelTitle');
  const createSpan = panel.querySelector('#reminder-create-btn span');
  if (createSpan) createSpan.textContent = t('reminder.create');
  const createIcon = panel.querySelector('#reminder-create-btn i');
  if (createIcon && typeof lucide !== 'undefined') lucide.createIcons();

  if (state.activeSessionId) {
    sendWs({ type: 'listReminders', sessionId: state.activeSessionId });
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

function saveInlineReminder() {
  const input = $('#reminder-inline-input');
  const timeInput = $('#reminder-time-input');
  const refInput = $('#reminder-ref-input');
  if (!input || !timeInput) return;

  const content = input.value.trim();
  if (!content) {
    cancelInlineCreate();
    return;
  }

  const triggerAt = new Date(timeInput.value).getTime();
  if (!triggerAt || isNaN(triggerAt) || triggerAt <= Date.now()) {
    timeInput.focus();
    return;
  }

  const refPath = refInput ? refInput.value.trim() : '';

  sendWs({
    type: 'createReminder',
    sessionId: state.activeSessionId,
    content: content,
    triggerAt: triggerAt,
    referencePath: refPath || undefined
  });

  isCreating = false;
  // Will re-render when reminderCreated message comes back
}

function deleteReminder(id) {
  if (!state.activeSessionId) return;

  const row = $(`.reminder-row[data-id="${id}"]`);
  if (row) {
    const btn = row.querySelector('.reminder-complete-btn');
    if (btn) btn.classList.add('completing');
    row.classList.add('removing');
    setTimeout(() => {
      sendWs({ type: 'deleteReminder', sessionId: state.activeSessionId, id: id });
      reminders = reminders.filter(r => r.id !== id);
      renderList();
      updateBadge();
    }, 350);
  } else {
    sendWs({ type: 'deleteReminder', sessionId: state.activeSessionId, id: id });
  }
}

// ── Event Binding ──────────────────────────────────────────────────────

function bindBodyEvents() {
  // Empty area click → start create
  const empty = $('#reminder-empty-area');
  if (empty) empty.addEventListener('click', (e) => {
    e.stopPropagation();
    startInlineCreate();
  });

  // Delete buttons on rows
  $$('.reminder-complete-btn[data-action="delete"]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const id = btn.getAttribute('data-id');
      if (id) deleteReminder(id);
    });
  });

  // Inline create events
  const input = $('#reminder-inline-input');
  const cancel = $('#reminder-inline-cancel');

  if (input) {
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        saveInlineReminder();
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        cancelInlineCreate();
      }
    });
  }

  if (cancel) {
    cancel.addEventListener('click', (e) => {
      e.stopPropagation();
      cancelInlineCreate();
    });
  }
}

// ── WS Message Handlers ───────────────────────────────────────────────

onMessage('reminderList', (msg) => {
  reminders = msg.reminders || [];
  renderList();
  updateBadge();
});

onMessage('reminderCreated', (msg) => {
  if (state.activeSessionId) {
    sendWs({ type: 'listReminders', sessionId: state.activeSessionId });
  }
  updateBadge();
});

onMessage('reminderDeleted', (msg) => {
  reminders = reminders.filter(r => r.id !== msg.id);
  renderList();
  updateBadge();
});

onMessage('reminderTriggered', (msg) => {
  if (msg.sessionId === state.activeSessionId) {
    reminders = reminders.map(r =>
      r.id === (msg.reminder && msg.reminder.id)
        ? { ...r, triggered: true, triggeredAt: Date.now() }
        : r
    );
    renderList();
    updateBadge();

    if (msg.reminder) {
      const ref = msg.reminder.referencePath
        ? `<div class="reminder-row-ref" style="margin-top:4px"><i data-lucide="file-text" style="width:12px;height:12px"></i>${escapeHtml(msg.reminder.referencePath)}</div>`
        : '';
      const bubble = document.createElement('div');
      bubble.className = 'reminder-trigger-bubble';
      bubble.innerHTML = `
        <div class="reminder-trigger-header"><i data-lucide="bell-ring"></i> ${t('reminder.triggerTitle')}</div>
        <div class="reminder-trigger-content">${escapeHtml(msg.reminder.content)}</div>
        <div class="reminder-trigger-time">${t('reminder.scheduledAt', { time: msg.reminder.formattedTime || formatTriggerTime(msg.reminder.triggerAt) })}</div>
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
  }
});

// ── Public Init ────────────────────────────────────────────────────────

export function initReminder() {
  const btn = $('#reminder-btn');
  if (btn) {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      togglePanel();
    });
  }

  // Footer create button — use event delegation since it's stable HTML
  const footer = $('#reminder-panel .reminder-panel-footer');
  if (footer) {
    footer.addEventListener('click', (e) => {
      e.stopPropagation();
      if (isCreating) saveInlineReminder();
      else startInlineCreate();
    });
  }

  // Body click for Apple Reminders style: click empty area to create/save
  const body = $('#reminder-panel .reminder-panel-body');
  if (body) {
    body.addEventListener('click', (e) => {
      if (e.target.closest('button, input, a, .reminder-row')) return;
      if (isCreating) saveInlineReminder();
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

/** Called when session switches — refresh reminder list */
export function refreshReminders(sessionId) {
  reminders = [];
  isCreating = false;
  if (panelOpen && sessionId) {
    sendWs({ type: 'listReminders', sessionId: sessionId });
  } else {
    renderList();
    updateBadge();
  }
}
