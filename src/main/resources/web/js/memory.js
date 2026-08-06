// memory.js — Memory modal with Agent / User tabs

import state from './state.js';
import { sendWs } from './ws.js';

/** Currently active tab scope. */
let activeScope = 'agent';

/** Cache per-scope content so tab switches don't re-fetch within same modal open. */
const cache = { user: null, agent: null };

/** The session whose memory is being viewed. Set when the modal opens. */
let memorySessionId = null;

/** Show the Memory button in the activity bar. */
export function showMemoryButton() {
  const btn = document.getElementById('memory-btn');
  if (btn) {
    btn.hidden = false;
  }
}

/**
 * Clear all cached memory. Called on session switch so the new session
 * fetches fresh content from the server instead of showing stale data.
 */
export function clearMemoryCache() {
  cache.user = null;
  cache.agent = null;
}

/** Open the memory modal, fetch active tab content. */
export function openMemoryEditor(event) {
  memorySessionId = state.activeSessionId;
  clearMemoryCache();
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
    sendWs({ type: 'getMemory', scope, sessionId: memorySessionId });
    // Retry once if server doesn't respond within 800ms
    setTimeout(() => {
      if (cache[scope] === null && activeScope === scope) {
        sendWs({ type: 'getMemory', scope, sessionId: memorySessionId });
      }
    }, 800);
  }
}

/** Handle memoryData from server — update textarea + cache. */
export function handleMemoryData(data) {
  cache[data.scope] = data.content || '';
  if (data.scope === activeScope) {
    document.getElementById('memory-content-input').value = data.content || '';
  }
}

/**
 * Handle memoryChanged push notification — invalidate cache and refresh
 * the active tab if the modal is open (so the user sees agent edits in real-time).
 */
export function handleMemoryChanged(data) {
  const path = data.path || '';
  let changedScope = null;
  if (path.endsWith('User.md')) changedScope = 'user';
  else if (path.endsWith('memory.md')) changedScope = 'agent';

  if (changedScope) {
    cache[changedScope] = null;
    const modal = document.getElementById('memory-modal');
    if (modal && modal.classList.contains('show') && changedScope === activeScope) {
      loadTab(activeScope);
    }
  }
}

/** Save the current tab's content. */
export function saveMemory() {
  const content = document.getElementById('memory-content-input').value;
  cache[activeScope] = content;
  sendWs({ type: 'saveMemory', scope: activeScope, content, sessionId: memorySessionId });
}

/** Initialize memory UI — bind button, tabs, modal buttons, overlay dismiss. */
export function initMemory() {
  document.getElementById('memory-btn')?.addEventListener('click', openMemoryEditor);
  document.querySelectorAll('.memory-tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.dataset.scope));
  });
  document.getElementById('memory-modal-cancel')?.addEventListener('click', closeMemoryEditor);
  document.getElementById('memory-modal-save')?.addEventListener('click', saveMemory);
  document.getElementById('memory-overlay')?.addEventListener('click', e => {
    if (e.target.id === 'memory-overlay') closeMemoryEditor();
  });
}
