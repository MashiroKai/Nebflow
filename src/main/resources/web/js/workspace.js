// workspace.js — Sidebar workspace knowledge list
import state from './state.js';
import { sendWs, onMessage } from './ws.js';

// ── State ──────────────────────────────────────────────────────────────
let items = [];

// ── Helpers ────────────────────────────────────────────────────────────

function $(sel) { return document.querySelector(sel); }

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

const TYPE_ICONS = {
  markdown: 'file-text',
  code: 'code',
  html: 'layout'
};

const TYPE_LABELS = {
  markdown: 'MD',
  code: 'CODE',
  html: 'HTML'
};

function formatTime(epochMs) {
  const d = new Date(epochMs);
  const now = new Date();
  const diff = now - epochMs;
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago';
  if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago';
  return d.toLocaleDateString(navigator.language || 'en', { month: 'short', day: 'numeric' });
}

function buildPreview(content) {
  const text = content.replace(/<[^>]*>/g, '').trim();
  return text.length > 80 ? text.slice(0, 80) + '...' : text;
}

// ── Render ─────────────────────────────────────────────────────────────

function renderList() {
  const body = $('#workspace-list');
  if (!body) return;

  const countEl = $('.workspace-count');
  if (countEl) countEl.textContent = items.length > 0 ? String(items.length) : '';

  body.innerHTML = '';

  if (items.length === 0) {
    body.appendChild(buildEmptyState());
  } else {
    // Sort by createdAt desc (newest first)
    const sorted = [...items].sort((a, b) => b.createdAt - a.createdAt);
    for (const item of sorted) {
      body.appendChild(buildRow(item));
    }
  }

  if (typeof lucide !== 'undefined') lucide.createIcons();
}

function buildEmptyState() {
  const el = document.createElement('div');
  el.className = 'workspace-empty';
  el.textContent = 'No items yet';
  return el;
}

function buildRow(item) {
  const row = document.createElement('div');
  row.className = 'workspace-item';
  row.dataset.id = item.id;
  row.dataset.type = item.itemType;

  const icon = document.createElement('span');
  icon.className = 'workspace-item-icon';
  const iconName = TYPE_ICONS[item.itemType] || 'file';
  icon.innerHTML = `<i data-lucide="${iconName}"></i>`;

  const content = document.createElement('div');
  content.className = 'workspace-item-content';

  const title = document.createElement('span');
  title.className = 'workspace-item-title';
  title.textContent = item.title;

  const preview = document.createElement('span');
  preview.className = 'workspace-item-preview';
  preview.textContent = buildPreview(item.content);

  content.appendChild(title);
  content.appendChild(preview);

  const deleteBtn = document.createElement('button');
  deleteBtn.className = 'workspace-item-delete';
  deleteBtn.innerHTML = '<i data-lucide="x"></i>';
  deleteBtn.title = 'Delete';
  deleteBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    deleteItem(item.id);
  });

  row.appendChild(icon);
  row.appendChild(content);
  row.appendChild(deleteBtn);

  row.addEventListener('click', () => {
    openItem(item);
  });

  return row;
}

// ── Actions ────────────────────────────────────────────────────────────

function openItem(item) {
  window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: item }));
}

function deleteItem(id) {
  if (!state.activeSessionId) return;
  const row = $(`.workspace-item[data-id="${id}"]`);
  if (row) {
    row.classList.add('removing');
    setTimeout(() => {
      sendWs({ type: 'deleteWorkspaceItem', sessionId: state.activeSessionId, id });
      items = items.filter(i => i.id !== id);
      renderList();
    }, 200);
  } else {
    sendWs({ type: 'deleteWorkspaceItem', sessionId: state.activeSessionId, id });
    items = items.filter(i => i.id !== id);
    renderList();
  }
}

// ── WS Message Handlers ───────────────────────────────────────────────

onMessage('workspaceItemList', (msg) => {
  items = msg.items || [];
  renderList();
});

onMessage('workspaceItemSaved', (msg) => {
  if (msg.item) {
    // Remove existing with same id (replace)
    items = items.filter(i => i.id !== msg.item.id);
    items.push(msg.item);
    renderList();
  }
});

onMessage('workspaceItemDeleted', (msg) => {
  items = items.filter(i => i.id !== msg.id);
  renderList();
});

// ── Public Init ────────────────────────────────────────────────────────

export function initWorkspace() {
  if (state.activeSessionId) {
    sendWs({ type: 'listWorkspaceItems', sessionId: state.activeSessionId });
  }
}

export function refreshWorkspace(sessionId) {
  items = [];
  if (sessionId) {
    sendWs({ type: 'listWorkspaceItems', sessionId });
  } else {
    renderList();
  }
}
