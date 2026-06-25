// modal.js — Modal dialog management: new session, delete session, agent config

import state from './state.js';
import { sendWs } from './ws.js';
import { batchDeleteSelected, deleteFolder, getTargetPath, getTargetAgent } from './sidebar.js';
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
  const folderId = state.activeFolderId || getCurrentSessionFolderId();
  if (folderId) payload.folderId = folderId;
  sendWs(payload);
}

/** Get the folderId of the currently displayed session, or null if at root. */
function getCurrentSessionFolderId() {
  const active = (state.sessions || []).find(s => s.id === state.activeSessionId);
  return active ? (active.folderId || null) : null;
}

// ---------- Inline New Session ----------
export function startInlineNewSession() {
  const sessionList = state.dom.sessionList;
  if (sessionList.querySelector('.new-session-input')) return;
  const folderId = state.activeFolderId || getCurrentSessionFolderId();
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
  document.getElementById('agent-modal-title').textContent = name ? t('agent.editTitle', { name }) : t('agent.newTitle');
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

  if (allTools.length === 0) {
    grid.innerHTML = '<div style="color:var(--color-frame-text-muted);font-size:12px;">' + t('agent.loadingTools') + '</div>';
    return;
  }

  // All tools are configurable
  if (allTools.length > 0) {
    allTools.sort().forEach(tool => {
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
      mcpSection.innerHTML = '<div style="color:var(--color-frame-text-muted);font-size:12px;">' + t('agent.noMcp') + '</div>';
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
          hint.textContent = t('agent.offline');
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

  // --- Card Design Modal ---
  document.getElementById('card-design-modal-cancel')?.addEventListener('click', hideCardDesignModal);
  document.getElementById('card-design-overlay')?.addEventListener('click', (e) => {
    if (e.target.id === 'card-design-overlay') hideCardDesignModal();
  });
  document.getElementById('card-design-modal-save')?.addEventListener('click', () => {
    const content = document.getElementById('card-design-input').value;
    state.cardDesignPrompt = content;
    sendWs({type: 'saveCardDesign', content});
    const btn = document.getElementById('card-design-modal-save');
    btn.textContent = t('settings.saved');
    setTimeout(() => { btn.textContent = t('settings.save'); }, 1500);
  });
  document.getElementById('card-design-modal-reset')?.addEventListener('click', () => {
    if (!confirm(t('settings.cardDesignResetConfirm'))) return;
    const defaultPrompt = getDefaultCardDesignPrompt();
    state.cardDesignPrompt = defaultPrompt;
    document.getElementById('card-design-input').value = defaultPrompt;
    sendWs({type: 'saveCardDesign', content: defaultPrompt});
  });
}

// ---------- Card Design Modal ----------
function getDefaultCardDesignPrompt() {
  return `## Card Visual Design Guidelines

Follow these strictly. They override any conflicting defaults.

### Purpose

Visual-first, text-minimal. Cards are for diagrams, animations, transitions, spatial layouts — not paragraphs. If the content works as Markdown, don't use Card.

### Color: always use CSS variables

**Never hardcode hex colors.** Always use var(--color-text) for body text, var(--color-bg)/var(--color-surface) for backgrounds. These guarantee maximum contrast in both light and dark mode. Use var(--color-primary/success/error/warning) only for status indicators. var(--color-text-muted) is for captions only — too low contrast for body text.

### Accuracy

Accuracy and correctness come first. If a visualization involves data, numbers, or precise relationships, use a professional tool (matplotlib, gnuplot, ROOT, etc.) to generate it — then embed the result as an image. Hand-drawn SVG is for layout and simple diagrams where precision is not critical.

### Visual defaults

- **No emoji.** Use typography and spacing for visual interest.
- **Rounded corners:** cards 16px, buttons 10px, inputs 8px, badges 9999px.
- **Font:** -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Helvetica Neue", sans-serif
- Body 13-14px / 400, headings 16-20px / 600, tight letter-spacing on headings.
- Animations only if they aid understanding (200-400ms, ease-out for entrance). Respect prefers-reduced-motion.

### Embedding external content

HTML must be self-contained (all styles/tags inline, no external CSS/JS). Local file paths in src/href are automatically served by the backend.`;
}

export function showCardDesignModal() {
  document.getElementById('card-design-input').value = state.cardDesignPrompt || '';
  document.getElementById('card-design-input').placeholder = t('settings.cardDesignPlaceholder');
  document.getElementById('card-design-modal-title').textContent = t('settings.cardDesign');
  document.getElementById('card-design-modal-reset').textContent = t('settings.cardDesignReset');
  document.getElementById('card-design-modal-cancel').textContent = t('modal.cancel');
  document.getElementById('card-design-modal-save').textContent = t('memory.save');
  document.getElementById('card-design-modal').classList.add('show');
  document.getElementById('card-design-overlay').classList.add('on');
}

export function hideCardDesignModal() {
  document.getElementById('card-design-modal').classList.remove('show');
  document.getElementById('card-design-overlay').classList.remove('on');
}
