// memory.js — Memory modal with Folder / Agent / User tabs

import state from './state.js';
import { sendWs } from './ws.js';
import { t } from './i18n.js';

/** Currently active tab scope. */
let activeScope = 'folder';

/** Cache per-scope content so tab switches don't re-fetch within same session. */
const cache = { user: null, agent: null, folder: null };

/** Show the Memory button in header (both windows). */
export function showMemoryButton() {
  for (const id of ['memory-btn', 'secondary-memory-btn']) {
    const btn = document.getElementById(id);
    if (btn) {
      btn.style.display = '';
      btn.textContent = t('header.memory');
    }
  }
}

/**
 * Clear all cached memory. Called on session switch so the new session
 * fetches fresh content from the server instead of showing stale data.
 */
export function clearMemoryCache() {
  cache.user = null;
  cache.agent = null;
  cache.folder = null;
}

/** Open the memory modal, fetch active tab content. */
export function openMemoryEditor() {
  document.getElementById('memory-modal').classList.add('show');
  document.getElementById('memory-overlay').classList.add('on');
  loadTab(activeScope);
}

/** Close the memory modal. */
export function closeMemoryEditor() {
  document.getElementById('memory-modal').classList.remove('show');
  document.getElementById('memory-overlay').classList.remove('on');
}

/** Switch tab — cache current content first. */
function switchTab(scope) {
  cache[activeScope] = document.getElementById('memory-content-input').value;
  activeScope = scope;
  document.querySelectorAll('.memory-tab').forEach(t => {
    t.classList.toggle('active', t.dataset.scope === scope);
  });
  loadTab(scope);
}

/** Load content for a scope — use cache if hit, otherwise fetch from server. */
function loadTab(scope) {
  const input = document.getElementById('memory-content-input');
  if (cache[scope] !== null) {
    input.value = cache[scope];
  } else {
    input.value = '';
    sendWs({ type: 'getMemory', scope });
  }
}

/** Handle memoryData from server — update textarea + cache. */
export function handleMemoryData(data) {
  cache[data.scope] = data.content || '';
  if (data.scope === activeScope) {
    document.getElementById('memory-content-input').value = data.content || '';
  }
}

/** Save the current tab's content. */
export function saveMemory() {
  const content = document.getElementById('memory-content-input').value;
  cache[activeScope] = content;
  sendWs({ type: 'saveMemory', scope: activeScope, content });
}

/** Initialize memory UI — bind button, tabs, modal buttons, overlay dismiss. */
export function initMemory() {
  for (const id of ['memory-btn', 'secondary-memory-btn']) {
    document.getElementById(id)?.addEventListener('click', openMemoryEditor);
  }
  document.querySelectorAll('.memory-tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.scope));
  });
  document.getElementById('memory-modal-cancel')?.addEventListener('click', closeMemoryEditor);
  document.getElementById('memory-modal-save')?.addEventListener('click', saveMemory);
  document.getElementById('memory-overlay')?.addEventListener('click', e => {
    if (e.target.id === 'memory-overlay') closeMemoryEditor();
  });
}
