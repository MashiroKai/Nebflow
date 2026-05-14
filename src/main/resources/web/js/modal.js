// modal.js — Modal dialog management: new session, delete session, agent config

import state from './state.js';
import { sendWs } from './ws.js';
import { batchDeleteSelected } from './sidebar.js';

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
  const payload = {type: 'createSession', name, agentName: state.selectedAgent || 'Nebula'};
  if (state.activeFolderId) payload.folderId = state.activeFolderId;
  sendWs(payload);
}

// ---------- Inline New Session ----------
export function startInlineNewSession() {
  const sessionList = state.dom.sessionList;
  if (sessionList.querySelector('.new-session-input')) return;
  const wrapper = document.createElement('div');
  wrapper.className = 'session-item';
  const input = document.createElement('input');
  input.className = 'new-session-input';
  input.type = 'text';
  input.placeholder = 'Session name...';
  input.style.cssText = 'width:100%;background:var(--color-frame-input-bg);border:1px solid var(--color-frame-input-border);border-radius:6px;padding:6px 10px;color:var(--color-frame-input-text);font-size:13px;font-family:inherit;outline:none;';
  wrapper.appendChild(input);
  sessionList.prepend(wrapper);
  input.focus();
  const finish = () => {
    const name = input.value.trim();
    wrapper.remove();
    if (name) {
      const payload = { type: 'createSession', name, agentName: state.selectedAgent || 'Nebula' };
      if (state.activeFolderId) payload.folderId = state.activeFolderId;
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
  deleteTitle.textContent = 'Delete Session';
  deleteMsg.textContent = 'Delete session "' + sessionName + '"?';
  state.pendingDeleteId = sessionId;
  pendingBatchDelete = false;
  pendingFolderDelete = false;
  modalOverlay.classList.add('on');
}

export function showBatchDeleteModal() {
  const { modalBox, deleteBox, deleteTitle, deleteMsg, modalOverlay } = state.dom;
  modalBox.style.display = 'none';
  deleteBox.style.display = 'block';
  deleteTitle.textContent = 'Batch Delete Sessions';
  const count = state.selectedSessionIds.size;
  deleteMsg.textContent = 'Delete ' + count + ' selected session' + (count > 1 ? 's' : '') + '?';
  state.pendingDeleteId = null;
  pendingBatchDelete = true;
  pendingFolderDelete = false;
  modalOverlay.classList.add('on');
}

export function showDeleteFolderModal(folderId, folderName) {
  const { modalBox, deleteBox, deleteTitle, deleteMsg, modalOverlay } = state.dom;
  modalBox.style.display = 'none';
  deleteBox.style.display = 'block';
  deleteTitle.textContent = 'Delete Folder';
  deleteMsg.textContent = 'Delete folder "' + folderName + '"?\nSessions inside will be moved to root.';
  pendingFolderDelete = true;
  pendingFolderDeleteId = folderId;
  pendingBatchDelete = false;
  state.pendingDeleteId = null;
  modalOverlay.classList.add('on');
}

export function confirmDeleteSession() {
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
    if (fid) sendWs({ type: 'deleteFolder', folderId: fid });
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

let currentAgentFields = {};

function buildConfigJson(name, desc, tools, mcpServers, baseFields) {
  const obj = { ...baseFields };
  obj.name = name;
  obj.description = desc || '';
  obj.tools = tools;
  if (mcpServers && mcpServers.length > 0) obj.mcpServers = mcpServers;
  else delete obj.mcpServers;
  return JSON.stringify(obj, null, 2);
}

export function showAgentModal(name, configJson, systemMd) {
  document.getElementById('agent-modal').classList.add('show');
  document.getElementById('agent-overlay').classList.add('on');
  document.getElementById('agent-modal-title').textContent = name ? `Edit: ${name}` : 'New Agent';
  document.getElementById('agent-name-input').value = name || '';
  document.getElementById('agent-name-input').disabled = !!name;

  // Parse JSON config
  let fields = {};
  try { fields = JSON.parse(configJson || '{}'); } catch(e) {}
  currentAgentFields = fields;
  document.getElementById('agent-desc-input').value = fields.description || '';
  document.getElementById('agent-system-input').value = systemMd || '';

  const selectedTools = Array.isArray(fields.tools) ? fields.tools : [];
  const isWildcard = selectedTools.length === 1 && selectedTools[0] === '*';
  const selectedMcp = Array.isArray(fields.mcpServers) ? fields.mcpServers : [];

  // Build tool checkboxes from tool library
  const grid = document.getElementById('agent-tools-grid');
  grid.innerHTML = '';
  const allTools = state.agentAvailableTools || [];
  const autoTools = new Set(state.agentAutoTools || []);

  if (allTools.length === 0) {
    grid.innerHTML = '<div style="color:var(--color-frame-text-muted);font-size:12px;">Loading tools...</div>';
    return;
  }

  // Auto-injected tools (non-editable, always present)
  const autoToolsArr = [...autoTools].sort();
  if (autoToolsArr.length > 0) {
    const sectionLabel = document.createElement('div');
    sectionLabel.className = 'agent-built-in-label';
    sectionLabel.textContent = 'Auto-included';
    grid.appendChild(sectionLabel);
    autoToolsArr.forEach(tool => {
      const label = document.createElement('label');
      label.className = 'agent-tool-check checked builtin';
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.value = tool;
      cb.checked = true;
      cb.disabled = true;
      label.appendChild(cb);
      label.appendChild(document.createTextNode(tool));
      grid.appendChild(label);
    });
  }

  // Configurable tools
  const configurableTools = allTools.filter(t => !autoTools.has(t));
  if (configurableTools.length > 0) {
    const sectionLabel = document.createElement('div');
    sectionLabel.className = 'agent-built-in-label';
    sectionLabel.textContent = 'Configurable';
    grid.appendChild(sectionLabel);
    configurableTools.forEach(tool => {
      const label = document.createElement('label');
      const checked = isWildcard || selectedTools.includes(tool);
      label.className = 'agent-tool-check' + (checked ? ' checked' : '');
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.value = tool;
      cb.checked = checked;
      label.appendChild(cb);
      label.appendChild(document.createTextNode(tool));
      label.onclick = () => {
        cb.checked = !cb.checked;
        label.classList.toggle('checked', cb.checked);
      };
      grid.appendChild(label);
    });
  }

  // MCP servers section
  const mcpSection = document.getElementById('agent-mcp-grid');
  if (mcpSection) {
    mcpSection.innerHTML = '';
    const mcpServers = state.mcpServers || [];
    if (mcpServers.length === 0) {
      mcpSection.innerHTML = '<div style="color:var(--color-frame-text-muted);font-size:12px;">No MCP servers configured</div>';
    } else {
      mcpServers.forEach(server => {
        const id = server.id || server;
        const globallyEnabled = server.enabled !== false;
        const label = document.createElement('label');
        const checked = selectedMcp.includes(id);
        label.className = 'agent-tool-check' + (checked ? ' checked' : '');
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.value = id;
        cb.checked = checked;
        // Always allow toggling — per-agent MCP selection is independent of global enabled state
        if (!globallyEnabled) label.style.opacity = '0.5';
        label.appendChild(cb);
        label.appendChild(document.createTextNode(id));
        if (!globallyEnabled) {
          const hint = document.createElement('span');
          hint.textContent = ' (offline)';
          hint.style.cssText = 'font-size:10px;color:var(--color-frame-text-muted);margin-left:2px;';
          label.appendChild(hint);
        }
        label.onclick = () => {
          cb.checked = !cb.checked;
          label.classList.toggle('checked', cb.checked);
        };
        mcpSection.appendChild(label);
      });
    }
  }
}

export function hideAgentModal() {
  document.getElementById('agent-modal').classList.remove('show');
  document.getElementById('agent-overlay').classList.remove('on');
}

// ---------- Init all modal handlers ----------
export function initModals() {
  const {
    newSessionBtn, modalCancel, modalConfirm, modalInput,
    deleteCancelBtn, deleteConfirmBtn, modalOverlay
  } = state.dom;

  // New session — inline input instead of modal
  newSessionBtn.onclick = startInlineNewSession;
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

  // New agent button
  document.getElementById('new-agent-btn')?.addEventListener('click', () => showAgentModal(null, '', ''));

  // Agent modal cancel
  document.getElementById('agent-modal-cancel')?.addEventListener('click', hideAgentModal);

  // Agent overlay click-to-close
  document.getElementById('agent-overlay')?.addEventListener('click', (e) => {
    if (e.target.id === 'agent-overlay') hideAgentModal();
  });

  // Agent modal save
  document.getElementById('agent-modal-save')?.addEventListener('click', () => {
    const name = document.getElementById('agent-name-input').value.trim();
    const desc = document.getElementById('agent-desc-input').value.trim();
    const systemMd = document.getElementById('agent-system-input').value;
    if (!name) return;

    // Gather checked configurable tools (exclude auto-injected/disabled ones)
    const tools = [];
    const allConfigurable = document.querySelectorAll('#agent-tools-grid input[type=checkbox]:not(:disabled)');
    allConfigurable.forEach(cb => {
      if (cb.checked) tools.push(cb.value);
    });

    // Use wildcard if all configurable tools are checked
    const allChecked = tools.length === allConfigurable.length && allConfigurable.length > 0;
    const finalTools = allChecked ? ['*'] : tools;

    // Gather checked MCP servers
    const mcpServers = [];
    const mcpGrid = document.getElementById('agent-mcp-grid');
    if (mcpGrid) {
      mcpGrid.querySelectorAll('input[type=checkbox]:checked').forEach(cb => {
        mcpServers.push(cb.value);
      });
    }

    const isNew = !document.getElementById('agent-name-input').disabled;
    const configJson = buildConfigJson(name, desc, finalTools, mcpServers, isNew ? {} : currentAgentFields);
    sendWs({type: isNew ? 'createAgent' : 'updateAgent', name, configJson, systemMd});
    hideAgentModal();
  });

  // Expose session modal helpers for sidebar cross-module usage
  window.__showDeleteModal = showDeleteModal;
}
