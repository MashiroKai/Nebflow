// modal.js — Modal dialog management: new session, delete session, agent config

import state from './state.js';
import { sendWs } from './ws.js';
import { batchDeleteSelected, deleteFolder, getTargetPath, getTargetAgent, getCurrentFolderId } from './sidebar.js';
import { t } from './i18n.js';

// ---------- Session Modals ----------
export function showNewSessionModal() {
  const { modalBox, deleteBox, modalInput, modalOverlay } = state.dom;
  deleteBox.style.display = 'none';
  modalBox.style.display = 'block';
  modalInput.value = '';
  modalOverlay.classList.add('on');
  setTimeout(() => modalInput.focus(), 50);
}

export function hideModals() {
  state.dom.modalOverlay.classList.remove('on');
}

export function confirmNewSession() {
  const { modalInput } = state.dom;
  const name = modalInput.value.trim();
  hideModals();
  if (!name) return;
  const payload = {type: 'createSession', name, agentName: getTargetAgent()};
  const folderId = getCurrentFolderId();
  if (folderId) payload.folderId = folderId;
  sendWs(payload);
}

// ---------- Inline New Session ----------
export function startInlineNewSession() {
  const sessionList = state.dom.sessionList;
  if (sessionList.querySelector('.new-session-input')) return;
  const folderId = getCurrentFolderId();
  const targetPath = getTargetPath(folderId) || t('path.root');
  const wrapper = document.createElement('div');
  wrapper.className = 'session-item';
  wrapper.style.flexDirection = 'column';
  wrapper.style.alignItems = 'stretch';
  wrapper.style.gap = '2px';
  const pathLabel = document.createElement('div');
  pathLabel.className = 'creation-path';
  pathLabel.style.width = '100%';
  pathLabel.textContent = targetPath + ' >';
  wrapper.appendChild(pathLabel);
  const input = document.createElement('input');
  input.className = 'new-session-input';
  input.type = 'text';
  input.placeholder = t('session.namePlaceholder');
  input.style.cssText = 'width:100%;background:var(--color-frame-input-bg);border:1px solid var(--color-frame-input-border);border-radius:6px;padding:6px 10px;color:var(--color-frame-input-text);font-size:13px;font-family:inherit;outline:none;';
  wrapper.appendChild(input);
  sessionList.prepend(wrapper);
  input.focus();
  const finish = () => {
    const name = input.value.trim();
    wrapper.remove();
    if (name) {
      const payload = { type: 'createSession', name, agentName: getTargetAgent() };
      if (folderId) payload.folderId = folderId;
      sendWs(payload);
    }
  };
  input.addEventListener('blur', finish);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      if (e.isComposing || e.keyCode === 229) return;
      e.preventDefault(); input.blur();
    }
    if (e.key === 'Escape') { input.value = ''; input.blur(); }
  });
}

// --- Delete modal ---
let pendingBatchDelete = false;
let pendingFolderDelete = false;
let pendingFolderDeleteId = null;

export function showDeleteModal(sessionId, sessionName) {
  const { modalBox, deleteBox, deleteTitle, deleteMsg, modalOverlay } = state.dom;
  modalBox.style.display = 'none';
  deleteBox.style.display = 'block';
  deleteTitle.textContent = t('delete.sessionTitle');
  deleteMsg.textContent = t('delete.sessionMsg', { name: sessionName });
  state.pendingDeleteId = sessionId;
  pendingBatchDelete = false;
  pendingFolderDelete = false;
  modalOverlay.classList.add('on');
}

export function showBatchDeleteModal() {
  const { modalBox, deleteBox, deleteTitle, deleteMsg, modalOverlay } = state.dom;
  modalBox.style.display = 'none';
  deleteBox.style.display = 'block';
  deleteTitle.textContent = t('delete.batchTitle');
  const count = state.selectedSessionIds.size;
  deleteMsg.textContent = t('delete.batchMsg', { count, s: count > 1 ? 's' : '' });
  state.pendingDeleteId = null;
  pendingBatchDelete = true;
  pendingFolderDelete = false;
  modalOverlay.classList.add('on');
}

export function showDeleteFolderModal(folderId, folderName) {
  const { modalBox, deleteBox, deleteTitle, deleteMsg, modalOverlay } = state.dom;
  modalBox.style.display = 'none';
  deleteBox.style.display = 'block';
  deleteTitle.textContent = t('delete.folderTitle');
  deleteMsg.textContent = t('delete.folderMsg', { name: folderName });
  pendingFolderDelete = true;
  pendingFolderDeleteId = folderId;
  pendingBatchDelete = false;
  state.pendingDeleteId = null;
  modalOverlay.classList.add('on');
}

// --- Generic confirm dialog ---
// Reuses #delete-box for any yes/no confirmation.
let pendingConfirmCallback = null;

/**
 * Show a Nebflow-styled confirmation dialog.
 * @param {string} title — dialog title
 * @param {string} message — dialog body text
 * @param {Function} onConfirm — called when user clicks Confirm
 */
export function showConfirm(title, message, onConfirm) {
  const { modalBox, deleteBox, deleteTitle, deleteMsg, modalOverlay } = state.dom;
  if (!deleteBox) { onConfirm?.(); return; }
  modalBox.style.display = 'none';
  deleteBox.style.display = 'block';
  deleteTitle.textContent = title;
  deleteMsg.textContent = message;
  pendingConfirmCallback = onConfirm;
  pendingBatchDelete = false;
  pendingFolderDelete = false;
  state.pendingDeleteId = null;
  modalOverlay.classList.add('on');
}

// --- Generic toast notification ---
// 2026-09-03 glass redesign (author ruling): type semantics moved from the
// color accent strip to a leading glyph icon (color-only distinction; glyph
// colors live in modal.css). Signature and lifecycle unchanged — callers
// keep passing (message, type).
const TOAST_ICONS = { error: '\u2715', info: '!', success: '\u2713' };
/**
 * Show a brief Nebflow-styled toast notification.
 * @param {string} message — text to display
 * @param {string} type — 'error' | 'info' | 'success'
 */
export function showToast(message, type = 'error') {
  const toast = document.createElement('div');
  toast.className = 'nebflow-toast nebflow-toast-' + type;
  const icon = document.createElement('span');
  icon.className = 'nebflow-toast-icon';
  icon.setAttribute('aria-hidden', 'true');
  icon.textContent = TOAST_ICONS[type] || TOAST_ICONS.info;
  const msg = document.createElement('span');
  msg.className = 'nebflow-toast-msg';
  msg.textContent = message;
  toast.append(icon, msg);
  document.body.appendChild(toast);
  requestAnimationFrame(() => toast.classList.add('show'));
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}

export function confirmDeleteSession() {
  // Generic confirm callback takes priority
  if (pendingConfirmCallback) {
    const cb = pendingConfirmCallback;
    pendingConfirmCallback = null;
    hideModals();
    cb();
    return;
  }
  if (pendingBatchDelete) {
    pendingBatchDelete = false;
    hideModals();
    batchDeleteSelected();
    return;
  }
  if (pendingFolderDelete) {
    pendingFolderDelete = false;
    const fid = pendingFolderDeleteId;
    pendingFolderDeleteId = null;
    hideModals();
    if (fid) deleteFolder(fid);
    return;
  }
  const id = state.pendingDeleteId;
  state.pendingDeleteId = null;
  hideModals();
  if (id) deleteSession(id);
}

function deleteSession(sessionId) {
  sendWs({type: 'deleteSession', sessionId});
}

// ---------- Agent Modal ----------

let currentAgentName = null;

/** Render the tools grid with toggleable chips.
 *  @param {string[]} agentTools — tools currently enabled for this agent */
function renderToolsGrid(agentTools) {
  const grid = document.getElementById('agent-tools-grid');
  if (!grid) return;
  const allTools = (state.availableTools || []).map(t => typeof t === 'string' ? t : t.name);
  const isAll = agentTools.includes('*');

  grid.innerHTML = allTools.map(name => {
    const checked = isAll || agentTools.includes(name);
    return `<span class="agent-tool-check${checked ? ' checked' : ''}" data-tool="${name}">${name}</span>`;
  }).join('');

  grid.querySelectorAll('.agent-tool-check').forEach(el => {
    el.addEventListener('click', () => el.classList.toggle('checked'));
  });
}

/** Collect checked tool names from the grid. */
function getCheckedTools() {
  const grid = document.getElementById('agent-tools-grid');
  if (!grid) return ['*'];
  const checked = [...grid.querySelectorAll('.agent-tool-check.checked')].map(el => el.dataset.tool);
  return checked;
}

export function showAgentModal(name, systemMd) {
  currentAgentName = name;
  document.getElementById('agent-modal').classList.add('show');
  document.getElementById('agent-overlay').classList.add('on');
  document.getElementById('agent-modal-title').textContent = t('agent.editTitle', { name });
  document.getElementById('agent-system-input').value = systemMd || '';

  // Populate tools from cached agentList data
  const agent = state.agentsData.find(a => a.name === name) || {};
  renderToolsGrid(agent.tools || ['*']);
}

export function hideAgentModal() {
  document.getElementById('agent-modal').classList.remove('show');
  document.getElementById('agent-overlay').classList.remove('on');
}

// ---------- Init all modal handlers ----------
export function initModals() {
  const {
    modalCancel, modalConfirm, modalInput,
    deleteCancelBtn, deleteConfirmBtn, modalOverlay
  } = state.dom;

  // New session — inline input instead of modal (button removed in single-session
  // architecture; guarded in case it reappears).
  const newSessionBtn = document.getElementById('new-session-btn');
  if (newSessionBtn) newSessionBtn.onclick = startInlineNewSession;
  modalCancel.onclick = hideModals;
  modalConfirm.onclick = confirmNewSession;
  modalInput.onkeydown = (e) => {
    if (e.key === 'Enter') {
      if (e.isComposing || e.keyCode === 229) return;
      e.preventDefault(); confirmNewSession();
    }
    if (e.key === 'Escape') hideModals();
  };

  // Delete session
  deleteCancelBtn.onclick = hideModals;
  deleteConfirmBtn.onclick = confirmDeleteSession;

  // Modal overlay click-to-close
  modalOverlay.onclick = (e) => {
    if (e.target === modalOverlay) hideModals();
  };

  // Agent modal cancel
  document.getElementById('agent-modal-cancel')?.addEventListener('click', hideAgentModal);

  // Agent overlay click-to-close
  document.getElementById('agent-overlay')?.addEventListener('click', (e) => {
    if (e.target.id === 'agent-overlay') hideAgentModal();
  });

  // Agent modal save — sends system prompt + tools
  document.getElementById('agent-modal-save')?.addEventListener('click', () => {
    const name = currentAgentName;
    const systemMd = document.getElementById('agent-system-input').value;
    const tools = getCheckedTools();
    if (!name) return;
    sendWs({type: 'updateAgentSystemPrompt', name, systemMd});
    sendWs({type: 'updateAgentTools', name, tools});
    hideAgentModal();
  });

  // Expose session modal helpers for sidebar cross-module usage
  window.__showDeleteModal = showDeleteModal;
  window.__showConfirm = showConfirm;
  window.__showToast = showToast;
}
