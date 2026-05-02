// modal.js — Modal dialog management: new session, delete session, agent config

import state from './state.js';
import { sendWs } from './ws.js';

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
  sendWs({type: 'createSession', name});
}

// ---------- Inline New Session ----------
export function startInlineNewSession() {
  const sessionList = state.dom.sessionList;
  // Don't create duplicate input
  if (sessionList.querySelector('.new-session-input')) return;
  const wrapper = document.createElement('div');
  wrapper.className = 'session-item';
  const input = document.createElement('input');
  input.className = 'new-session-input';
  input.type = 'text';
  input.placeholder = 'Session name...';
  input.style.cssText = 'width:100%;background:#1a1a1a;border:1px solid #444;border-radius:6px;padding:6px 10px;color:#ccc;font-size:13px;font-family:inherit;outline:none;';
  wrapper.appendChild(input);
  sessionList.prepend(wrapper);
  input.focus();
  const finish = () => {
    const name = input.value.trim();
    wrapper.remove();
    if (name) sendWs({type: 'createSession', name});
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
export function showDeleteModal(sessionId, sessionName) {
  const { modalBox, deleteBox, deleteMsg, modalOverlay } = state.dom;
  modalBox.style.display = 'none';
  deleteBox.style.display = 'block';
  deleteMsg.textContent = 'Delete session "' + sessionName + '"?';
  state.pendingDeleteId = sessionId;
  modalOverlay.classList.add('on');
}

export function confirmDeleteSession() {
  const id = state.pendingDeleteId;
  state.pendingDeleteId = null;
  hideModals();
  if (id) deleteSession(id);
}

function deleteSession(sessionId) {
  sendWs({type: 'deleteSession', sessionId});
}

// ---------- Agent Modal ----------
const ALL_TOOLS = ['Read','Write','Edit','Bash','Glob','Grep','WebSearch','WebFetch','Curl','AskUserQuestion','ContextManage'];

function parseYamlFields(yaml) {
  const fields = {};
  yaml.split('\n').forEach(line => {
    const trimmed = line.trim();
    const idx = trimmed.indexOf(':');
    if (idx >= 0) {
      const key = trimmed.substring(0, idx).trim();
      let value = trimmed.substring(idx + 1).trim();
      value = value.replace(/^["']|["']$/g, '');
      if (key) fields[key] = value;
    }
  });
  return fields;
}

function buildYaml(desc, modelRoute, tools) {
  let yaml = `description: "${(desc || '').replace(/"/g, '\\"')}"\n`;
  if (modelRoute && modelRoute !== 'default') yaml += `modelRoute: ${modelRoute}\n`;
  if (tools.length > 0) yaml += `tools: ${tools.join(', ')}\n`;
  return yaml;
}

export function showAgentModal(name, yaml, systemMd) {
  document.getElementById('agent-modal').classList.add('show');
  document.getElementById('agent-overlay').classList.add('on');
  document.getElementById('agent-modal-title').textContent = name ? `Edit: ${name}` : 'New Agent';
  document.getElementById('agent-name-input').value = name || '';
  document.getElementById('agent-name-input').disabled = !!name;

  // Parse YAML into fields
  const fields = yaml ? parseYamlFields(yaml) : {};
  document.getElementById('agent-desc-input').value = fields.description || '';
  document.getElementById('agent-system-input').value = systemMd || '';

  // Build model select options from config
  const modelSelect = document.getElementById('agent-model-input');
  const currentModel = fields.modelRoute || 'default';
  const existingValues = new Set();
  // Collect existing option values
  Array.from(modelSelect.options).forEach(opt => existingValues.add(opt.value));
  // Parse config to discover available models
  try {
    const cfg = JSON.parse(state.configText || '{}');
    const providers = cfg.llm && cfg.llm.providers ? cfg.llm.providers : {};
    for (const [pid, prov] of Object.entries(providers)) {
      if (prov.models && Array.isArray(prov.models)) {
        for (const m of prov.models) {
          const ref = `${pid}/${m.id}`;
          if (!existingValues.has(ref)) {
            const opt = document.createElement('option');
            opt.value = ref;
            opt.textContent = ref;
            modelSelect.appendChild(opt);
            existingValues.add(ref);
          }
        }
      }
    }
  } catch (_) {}
  modelSelect.value = existingValues.has(currentModel) ? currentModel : 'default';

  // Build tool checkboxes
  const selectedTools = (fields.tools || '').split(',').map(t => t.trim().replace(/^- /, '')).filter(Boolean);
  const grid = document.getElementById('agent-tools-grid');
  grid.innerHTML = '';
  ALL_TOOLS.forEach(tool => {
    const label = document.createElement('label');
    label.className = 'agent-tool-check' + (selectedTools.includes(tool) ? ' checked' : '');
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.value = tool;
    cb.checked = selectedTools.includes(tool);
    label.appendChild(cb);
    label.appendChild(document.createTextNode(tool));
    label.onclick = () => {
      cb.checked = !cb.checked;
      label.classList.toggle('checked', cb.checked);
    };
    grid.appendChild(label);
  });
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
    const modelRoute = document.getElementById('agent-model-input').value.trim();
    const systemMd = document.getElementById('agent-system-input').value;
    if (!name) return;
    // Gather checked tools
    const tools = [];
    document.querySelectorAll('#agent-tools-grid input[type=checkbox]:checked').forEach(cb => tools.push(cb.value));
    const yaml = buildYaml(desc, modelRoute, tools);
    const isNew = !document.getElementById('agent-name-input').disabled;
    sendWs({type: isNew ? 'createAgent' : 'updateAgent', name, yaml, systemMd});
    hideAgentModal();
  });

  // Expose session modal helpers for sidebar cross-module usage
  window.__showDeleteModal = showDeleteModal;
}
