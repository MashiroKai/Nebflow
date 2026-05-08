// memory.js — Memory card UI for Nebflow

import state from './state.js';
import { sendWs } from './ws.js';

/**
 * Render memory tag pills in the header.
 * Tags reflect the current session's memory status.
 */
export function renderMemoryTags(status) {
  const c = document.getElementById('memory-tags');
  if (!c) return;
  c.querySelectorAll('.memory-tag').forEach(tag => {
    const scope = tag.dataset.scope;
    const info = status?.[scope] || {};
    tag.classList.toggle('has-content', !!info.exists);
    tag.title = info.preview || '(empty — click to create)';
  });
  c.style.display = status ? 'flex' : 'none';
}

/**
 * Open the memory editor modal for the given scope.
 */
export function openMemoryEditor(scope) {
  sendWs({ type: 'getMemory', scope });
  const modal = document.getElementById('memory-modal');
  const title = document.getElementById('memory-modal-title');
  const labels = { user: 'User', agent: state.selectedAgent || 'Agent', session: 'Session' };
  title.textContent = 'Memory \u00b7 ' + (labels[scope] || scope);
  modal.dataset.scope = scope;
  modal.classList.add('show');
  document.getElementById('memory-overlay').classList.add('on');
}

/**
 * Handle memoryData response from server.
 */
export function handleMemoryData(data) {
  document.getElementById('memory-content-input').value = data.content || '';
}

/**
 * Save memory content and close the modal.
 */
export function saveMemory() {
  const scope = document.getElementById('memory-modal').dataset.scope;
  const content = document.getElementById('memory-content-input').value;
  sendWs({ type: 'saveMemory', scope, content });
  closeMemoryEditor();
}

/**
 * Close the memory editor modal.
 */
export function closeMemoryEditor() {
  document.getElementById('memory-modal').classList.remove('show');
  document.getElementById('memory-overlay').classList.remove('on');
}

/**
 * Initialize memory UI — bind tag clicks, modal buttons, overlay dismiss.
 */
export function initMemory() {
  document.querySelectorAll('#memory-tags .memory-tag').forEach(tag => {
    tag.onclick = () => openMemoryEditor(tag.dataset.scope);
  });
  document.getElementById('memory-modal-cancel')?.addEventListener('click', closeMemoryEditor);
  document.getElementById('memory-modal-save')?.addEventListener('click', saveMemory);
  document.getElementById('memory-overlay')?.addEventListener('click', e => {
    if (e.target.id === 'memory-overlay') closeMemoryEditor();
  });
}
