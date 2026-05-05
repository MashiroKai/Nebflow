import state from './state.js';
import { LS_SESSIONS_KEY } from './state.js';
import { initSpinner, initMarkdown, smartScroll } from './utils.js';
import { connect, onMessage, sendWs } from './ws.js';
import {
  setBusy, clearBusy, setStatus, clearStatus,
  renderUserBubble, appendAiText, finishAi,
  appendAgentText, finishAgent, getAgentColor,
  renderTool, renderToolPending, renderError, renderTimeoutNotice,
  renderSystemBubble, renderStageBubble, renderRetryStatus, clearRetryStatus,
  showOptions, renderAskUser, renderPermissionPrompt,
  renderAttachmentPreview
} from './chat.js';
import {
  initNavTabs, renderSessionSidebar, renderAgentList, renderSettings,
  switchSession, deleteSession, formatSessionTime, setSessionAttention
} from './sidebar.js';
import {
  showNewSessionModal, hideModals, confirmNewSession,
  showDeleteModal, confirmDeleteSession,
  showAgentModal, hideAgentModal, initModals,
  startInlineNewSession
} from './modal.js';
import { send, handleSlash, addFileAttachment, initInput, setNewSessionHandler } from './input.js';
import { saveMsg, loadMsgs, restoreFromStorage, restoreFromBackendHistory, migrateLegacyIfNeeded } from './persistence.js';
import { renderTaskList } from './taskList.js';
import { registerCardRenderer, renderWithRegistry } from './cardRegistry.js';
import { escapeHtml } from './utils.js';

// ---------- 1. Populate DOM refs ----------
state.dom = {
  chat: document.getElementById('chat'),
  input: document.getElementById('input'),
  sendBtn: document.getElementById('send-btn'),
  stopBtn: document.getElementById('stop-btn'),
  voiceBtn: document.getElementById('voice-btn'),
  attachBtn: document.getElementById('attach-btn'),
  attPreview: document.getElementById('attachment-preview'),
  statusWrap: document.getElementById('status-wrap'),
  statusText: document.getElementById('status-text'),
  connEl: document.getElementById('conn'),
  voiceOverlay: document.getElementById('voice-overlay'),
  voiceText: document.getElementById('voice-text'),
  lottieSpinnerEl: document.getElementById('lottie-spinner'),
  sessionList: document.getElementById('session-list'),
  sessionNameEl: document.getElementById('session-name'),
  slashDropdown: document.getElementById('slash-dropdown'),
  modalOverlay: document.getElementById('modal-overlay'),
  modalBox: document.getElementById('modal-box'),
  modalInput: document.getElementById('modal-input'),
  modalCancel: document.getElementById('modal-cancel'),
  modalConfirm: document.getElementById('modal-confirm'),
  deleteBox: document.getElementById('delete-box'),
  deleteTitle: document.getElementById('delete-title'),
  deleteMsg: document.getElementById('delete-msg'),
  deleteCancelBtn: document.getElementById('delete-cancel'),
  deleteConfirmBtn: document.getElementById('delete-confirm'),
  agentOverlay: document.getElementById('agent-overlay'),
  agentModal: document.getElementById('agent-modal'),
  newSessionBtn: document.getElementById('new-session-btn'),
  newAgentBtn: document.getElementById('new-agent-btn'),
  agentNameInput: document.getElementById('agent-name-input'),
  agentYamlInput: document.getElementById('agent-yaml-input'),
  agentSystemInput: document.getElementById('agent-system-input'),
  agentModalCancel: document.getElementById('agent-modal-cancel'),
  agentModalSave: document.getElementById('agent-modal-save'),
};

// ---------- 2. Init libraries ----------
// Guard: Safari may execute module scripts before CDN scripts finish loading.
function waitForGlobals() {
  return new Promise((resolve) => {
    if (typeof marked !== 'undefined' && typeof lottie !== 'undefined' && typeof lucide !== 'undefined') {
      resolve();
    } else {
      const check = setInterval(() => {
        if (typeof marked !== 'undefined' && typeof lottie !== 'undefined' && typeof lucide !== 'undefined') {
          clearInterval(check);
          resolve();
        }
      }, 50);
      // Safety timeout: proceed after 5s even if some libs missing
      setTimeout(() => { clearInterval(check); resolve(); }, 5000);
    }
  });
}

await waitForGlobals();
if (typeof marked !== 'undefined') initMarkdown();
if (typeof lottie !== 'undefined') initSpinner();
if (typeof lucide !== 'undefined') lucide.createIcons();

// ---------- 3. Register WS message handlers ----------

// Mark a session as having unread activity
function markSessionUnread(sessionId) {
  if (state.unreadSessions.has(sessionId)) return;
  state.unreadSessions.add(sessionId);
  window.dispatchEvent(new CustomEvent('session-unread', { detail: { sessionId } }));
}

// Helper: is this event for the currently active session?
function isActive(msg) {
  return !msg.sessionId || msg.sessionId === state.activeSessionId;
}

// Helper: compute and clear turn duration for a session
function consumeTurnDuration(sid) {
  const startTime = state.turnStartTimes[sid];
  if (!startTime) return undefined;
  delete state.turnStartTimes[sid];
  return Date.now() - startTime;
}

// Helper: clear busy for a specific session
function clearBusyFor(msg) {
  const sid = msg.sessionId || state.activeSessionId;
  if (state.busySessionIds.has(sid)) {
    clearBusy(sid);
  }
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  // Only release the send lock for the currently active session
  if (sid === state.activeSessionId) {
    state.isSending = false;
  }
}

// --- Chat streaming ---
// ALL sessions: save to localStorage. Active session only: render DOM.

onMessage('textDelta', (msg) => {
  const sid = msg.sessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate text for ALL sessions
  if (sid) state.sessionTexts[sid] = (state.sessionTexts[sid] || '') + msg.delta;
  if (isActive(msg)) {
    if (!state.busySessionIds.has(msg.sessionId || state.activeSessionId)) setBusy(msg.sessionId || state.activeSessionId);
    clearRetryStatus();
    appendAiText(msg.delta);
  }
});

onMessage('textDone', (msg) => {
  const sid = msg.sessionId;
  if (isActive(msg)) {
    const data = finishAi();
    if (data) saveMsg(data, sid);
  } else if (sid && state.sessionTexts[sid]) {
    // Non-active session: save buffered text to localStorage
    saveMsg({type: 'ai', text: state.sessionTexts[sid]}, sid);
    delete state.sessionTexts[sid];
  }
});

onMessage('thinking', (msg) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  if (isActive(msg)) {
    if (!state.busySessionIds.has(msg.sessionId || state.activeSessionId)) setBusy(msg.sessionId || state.activeSessionId);
    // Guard against duplicate thinking bubbles: check both state ref and DOM.
    const existing = state.dom.chat.querySelector('.thinking-placeholder');
    if (!state.currentAiBubble && !existing) {
      const { chat } = state.dom;
      const row = document.createElement('div');
      row.className = 'row ai';
      state.currentAiBubble = document.createElement('div');
      state.currentAiBubble.className = 'bubble ai thinking-placeholder';
      state.currentAiBubble.innerHTML = '<span style="color:#999;font-size:13px;">Thinking...</span>';
      row.appendChild(state.currentAiBubble);
      chat.appendChild(row);
      smartScroll();
    }
  }
});

onMessage('toolStart', (msg) => {
  if (isActive(msg)) {
    if (!state.busySessionIds.has(msg.sessionId || state.activeSessionId)) setBusy(msg.sessionId || state.activeSessionId);
    clearRetryStatus();
    // Finish the current AI bubble so that text after tool execution goes into a new bubble
    const prevData = finishAi();
    if (prevData) saveMsg(prevData, msg.sessionId);
    renderToolPending(msg.label, msg.sessionId);
  }
});

onMessage('toolEnd', (msg) => {
  if (msg.label && msg.label.startsWith('AskUser')) return;
  if (isActive(msg)) {
    const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input, msg.sessionId);
    if (data) saveMsg(data, msg.sessionId);
  } else {
    saveMsg({type: 'tool', label: msg.label, summary: msg.summary, content: msg.content, isError: msg.isError, input: msg.input}, msg.sessionId);
  }
});

// --- Terminal events ---
// Always clear busySessionId. DOM + status only for active session. Always mark unread for non-active.

onMessage('done', (msg) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  const durationMs = consumeTurnDuration(sid);
  // Flush any remaining buffered text for this session
  if (msg.sessionId && state.sessionTexts[msg.sessionId]) {
    if (!isActive(msg)) {
      saveMsg({type: 'ai', text: state.sessionTexts[msg.sessionId]}, msg.sessionId);
    }
    delete state.sessionTexts[msg.sessionId];
  }
  if (isActive(msg)) {
    // Clean up per-session pending tool card for this session
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    const data = finishAi(durationMs);
    if (data) saveMsg(data, msg.sessionId);
    Object.keys(state.agentBubbles).forEach(id => finishAgent(id));
    state.agentBubbles = {};
    state.activeAgentId = null;
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

onMessage('error', (msg) => {
  clearBusyFor(msg);
  if (isActive(msg)) {
    const sid = msg.sessionId || state.activeSessionId;
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishAi();
    renderError(msg.message);
    clearStatus();
  } else {
    saveMsg({type: 'error', text: msg.message}, msg.sessionId);
    markSessionUnread(msg.sessionId);
  }
});

onMessage('interrupted', (msg) => {
  clearBusyFor(msg);
  if (isActive(msg)) {
    const sid = msg.sessionId || state.activeSessionId;
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishAi();
    clearStatus();
  }
});

onMessage('timeout', (msg) => {
  clearBusyFor(msg);
  if (isActive(msg)) {
    finishAi();
    renderTimeoutNotice();
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

onMessage('maxTokens', (msg) => {
  clearBusyFor(msg);
  if (isActive(msg)) {
    const sid = msg.sessionId || state.activeSessionId;
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishAi();
    renderError('Max tokens reached — response truncated');
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

// --- AskUser / Permission ---
onMessage('askUser', (msg) => {
  const sid = msg.sessionId;
  if (sid) setSessionAttention(sid, true);
  if (isActive(msg)) {
    const data = renderAskUser(msg.items, msg.sessionId);
    if (data) saveMsg(data, msg.sessionId);
  } else if (sid) {
    // Non-active session: persist so it can be restored on session switch
    saveMsg({ type: 'askUser', items: msg.items }, sid);
  }
});

onMessage('askPermission', (msg) => {
  const sid = msg.sessionId;
  if (sid) setSessionAttention(sid, true);
  if (isActive(msg)) renderPermissionPrompt(msg.toolName, msg.summary, msg.input, msg.sessionId);
});

// --- Session list (global) ---
let restoredSessionId = null;
onMessage('sessionList', (msg) => {
  renderSessionSidebar(msg.sessions, msg.activeId);
  // Only restore messages on first load (before any session was active)
  if (!restoredSessionId && msg.activeId) {
    restoredSessionId = msg.activeId;
    // Try backend history first; fall back to localStorage
    sendWs({ type: 'getHistory', sessionId: msg.activeId, limit: 100 });
  }
  migrateLegacyIfNeeded();
});

// --- Backend history page ---
// For initial load: replaces chat content.
// For scroll-up pagination: prepends older messages before existing content.
onMessage('historyPage', (msg) => {
  const sid = msg.sessionId;
  if (sid !== state.activeSessionId) return;
  state.historyLoading = false;

  if (msg.offset === 0 || state.historyOffset === 0 && msg.messages.length > 0) {
    // Initial load or full refresh — replace
    state.historyOffset = msg.offset;
    state.historyTotal = msg.total;
    state.historyHasMore = msg.hasMore;
    state.dom.chat.innerHTML = '';
    restoreFromBackendHistory(msg.messages);
    smartScroll();

    // Re-render interactive AskUser if session is still busy with a pending one
    if (state.busySessionIds.has(sid)) {
      const lastAskUser = [...msg.messages].reverse().find(m => m.type === 'askUser');
      if (lastAskUser && lastAskUser.items) {
        renderAskUser(lastAskUser.items, sid);
      }
    }
  } else {
    // Scroll-up pagination — prepend older messages
    const chat = state.dom.chat;
    const prevScrollHeight = chat.scrollHeight;
    const prevScrollTop = chat.scrollTop;
    // Insert before first child
    const fragment = document.createDocumentFragment();
    const tempDiv = document.createElement('div');
    // Render messages into a temp container
    const prevActiveId = state.activeSessionId;
    // Use a temporary chat container for rendering
    const origChat = state.dom.chat;
    state.dom.chat = tempDiv;
    restoreFromBackendHistory(msg.messages);
    state.dom.chat = origChat;
    // Move rendered children to fragment
    while (tempDiv.firstChild) {
      fragment.appendChild(tempDiv.firstChild);
    }
    chat.prepend(fragment);
    // Restore scroll position so user stays at the same message
    const newScrollHeight = chat.scrollHeight;
    chat.scrollTop = prevScrollTop + (newScrollHeight - prevScrollHeight);
    state.historyOffset = msg.offset;
    state.historyHasMore = msg.hasMore;
  }
});

// --- Multi-agent events ---
onMessage('agentStart', (msg) => {
  if (!isActive(msg)) return;
  state.activeAgentId = msg.name || msg.agentId;
  const color = getAgentColor(state.activeAgentId);
  if (!state.agentBubbles[state.activeAgentId]) {
    const { chat } = state.dom;
    const row = document.createElement('div');
    row.className = 'row ai agent-row';
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai';
    bubble.innerHTML = '<span style="color:#999;font-size:13px;">Thinking...</span>';
    // Only show badge for non-default agents
    if (state.activeAgentId && state.activeAgentId !== 'default') {
      const badge = document.createElement('div');
      badge.className = 'agent-badge';
      badge.style.borderColor = color;
      badge.style.color = color;
      badge.textContent = state.activeAgentId;
      row.appendChild(badge);
    }
    row.appendChild(bubble);
    chat.appendChild(row);
    state.agentBubbles[state.activeAgentId] = { bubble, text: '', row, badge: row.querySelector('.agent-badge') };
  }
});

onMessage('agentTextDelta', (msg) => { if (isActive(msg)) appendAgentText(msg.agentId || state.activeAgentId, msg.delta); });
onMessage('agentToolStart', (msg) => { if (isActive(msg)) renderToolPending(msg.label, msg.sessionId); });
onMessage('agentToolEnd', (msg) => {
  if (!isActive(msg)) return;
  const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input, msg.sessionId);
  if (data) saveMsg(data, msg.sessionId);
});
onMessage('agentEnd', (msg) => {
  if (!isActive(msg)) return;
  clearBusyFor(msg);
  const id = msg.name || msg.agentId;
  const a = state.agentBubbles[id];
  if (a && a.text) {
    saveMsg({ type: 'agent', agentId: id, text: a.text }, msg.sessionId || state.activeSessionId);
  }
  finishAgent(id);
});

onMessage('agentThinking', (msg) => {
  if (!isActive(msg)) return;
  if (state.activeAgentId && state.agentBubbles[state.activeAgentId]) {
    state.agentBubbles[state.activeAgentId].bubble.innerHTML = '<span style="color:#999;font-size:13px;">Thinking...</span>';
  }
});

onMessage('agentRetryStatus', (msg) => {
  if (isActive(msg)) renderRetryStatus(msg.message);
});

onMessage('agentDone', (msg) => {
  if (!isActive(msg)) return;
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  consumeTurnDuration(sid);
  Object.keys(state.agentBubbles).forEach(id => {
    const a = state.agentBubbles[id];
    if (a && a.text) {
      saveMsg({ type: 'agent', agentId: id, text: a.text }, sid);
    }
    finishAgent(id);
  });
  state.agentBubbles = {};
  state.activeAgentId = null;
  clearStatus();
});

// --- Compaction events (per-session) ---
// These events include sessionId from the backend for root agents.
// We track compacting sessions globally so the sidebar shows an indicator
// even when the user is viewing a different session.
function setCompacting(sessionId, active) {
  if (!sessionId) return;
  if (active) {
    state.compactingSessionIds.add(sessionId);
  } else {
    state.compactingSessionIds.delete(sessionId);
  }
  window.dispatchEvent(new CustomEvent('session-compacting', { detail: { sessionId } }));
}

onMessage('compactStart', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  setCompacting(sid, true);
  if (sid === state.activeSessionId) {
    state.dom.statusWrap.classList.add('compacting');
    setStatus('Compacting context...');
  }
});

onMessage('compactComplete', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  setCompacting(sid, false);
  if (sid === state.activeSessionId) {
    const wrap = state.dom.statusWrap;
    wrap.classList.remove('compacting');
    wrap.classList.add('compact-done');
    const detail = msg.snapshotPath ? ` (snapshot: ${msg.snapshotPath.split('/').pop()})` : '';
    setStatus(`Context compacted: ${msg.before} → ${msg.after} messages${detail}`);
    setTimeout(clearStatus, 3000);
  }
});

onMessage('compactFailed', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  setCompacting(sid, false);
  if (sid === state.activeSessionId) {
    const wrap = state.dom.statusWrap;
    wrap.classList.remove('compacting');
    wrap.classList.add('compact-failed');
    setStatus(`Context compaction failed (attempt ${msg.attempt}/${msg.maxAttempts})`);
  }
  if (msg.attempt >= msg.maxAttempts) {
    renderError(`Context compaction circuit breaker open after ${msg.attempt} attempts`);
    clearBusyFor(msg);
  }
});

// --- Agent panel events (global) ---
onMessage('agentList', (msg) => {
  state.agentsData = msg.agents || [];
  renderAgentList();
});

onMessage('agentConfig', (msg) => showAgentModal(msg.name, msg.configJson || '', msg.systemMd || ''));
onMessage('agentCreated', () => sendWs({ type: 'listAgents' }));
onMessage('agentUpdated', () => sendWs({ type: 'listAgents' }));

// --- Server config ---
onMessage('serverConfig', (msg) => {
  if (msg.streamTimeoutMs) state.streamTimeoutMs = msg.streamTimeoutMs;
  if (msg.version) state.serverVersion = msg.version;
  if (msg.policy) state.currentPolicy = msg.policy;
  if (msg.thinking !== undefined) {
    state.serverThinking = msg.thinking;
    state.thinkingMode = msg.thinking;
  }
  if (msg.tools) {
    state.availableTools = msg.tools;
  }
});

onMessage('configData', (msg) => {
  state.configText = msg.config || '';
  const editor = document.getElementById('config-editor');
  if (editor) editor.value = state.configText;
});

onMessage('configUpdated', (msg) => {
  if (!msg.success) renderError('Config update failed');
});

// --- Retry / fallback status ---
onMessage('retryStatus', (msg) => {
  if (isActive(msg)) renderRetryStatus(msg.message);
});

// --- Session busy state (backend authority) ---
onMessage('sessionBusy', (msg) => {
  if (isActive(msg)) {
    if (msg.busy) setBusy(msg.sessionId);
    else clearBusy(msg.sessionId);
  }
});

// --- Task list ---
onMessage('taskListUpdate', (msg) => {
  if (msg.sessionId) state.sessionTasks[msg.sessionId] = msg.tasks;
  if (isActive(msg)) renderTaskList(msg.tasks);
});

onMessage('progressUpdate', (msg) => {
  const sid = msg.sessionId;
  const prev = state.lastStage[sid] || 'Normal';
  state.lastStage[sid] = msg.stage;
  // Only render + persist when stage actually changes
  if (msg.stage !== prev && msg.stage !== 'Normal') {
    if (isActive(msg)) {
      const data = renderStageBubble(msg.stage, msg.stagnationCount, msg.turnIdx);
      if (data) saveMsg(data, sid);
    } else {
      saveMsg({ type: 'stage', stage: msg.stage, stagnationCount: msg.stagnationCount, turnIdx: msg.turnIdx }, sid);
    }
  }
});

onMessage('paused', (msg) => {
  const sid = msg.sessionId;
  state.lastStage[sid] = 'Paused';
  if (isActive(msg)) {
    const data = renderStageBubble('Paused', 9, '?');
    if (data) saveMsg(data, sid);
  } else {
    saveMsg({ type: 'stage', stage: 'Paused', stagnationCount: 9, turnIdx: '?' }, sid);
    markSessionUnread(sid);
  }
});

// ---------- 4. Cross-module wiring ----------
setNewSessionHandler(() => startInlineNewSession());
window.__showDeleteModal = showDeleteModal;

// ---------- 5. Initialize UI modules ----------
console.log('[main] initializing modules...');
initNavTabs();
initModals();
initInput();
console.log('[main] modules initialized, connecting ws...');

// Scroll listener
state.dom.chat.addEventListener('scroll', () => {
  state.scrollSnapped = state.dom.chat.scrollTop + state.dom.chat.clientHeight >= state.dom.chat.scrollHeight - 40;
  // Scroll-to-top: load older messages
  if (state.dom.chat.scrollTop < 50 && state.historyHasMore && !state.historyLoading && state.historyOffset > 0) {
    state.historyLoading = true;
    sendWs({ type: 'getHistory', sessionId: state.activeSessionId, limit: 50, beforeIndex: state.historyOffset });
  }
});

// ---------- 6. Expose global Nebflow API for plugins ----------
window.Nebflow = {
  registerCardRenderer,
  escapeHtml,
  get state() { return state; },
};

// ---------- 7. Load plugins ----------
async function loadPlugins() {
  try {
    const res = await fetch('/plugins/manifest.json');
    if (!res.ok) return;
    const data = await res.json();
    const plugins = data.plugins || [];
    for (const plugin of plugins) {
      // Load CSS first
      for (const cssPath of (plugin.frontend?.styles || [])) {
        const link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = `/plugins/${plugin.name}/${cssPath}`;
        document.head.appendChild(link);
      }
      // Load JS in order
      for (const jsPath of (plugin.frontend?.scripts || [])) {
        await new Promise((resolve, reject) => {
          const script = document.createElement('script');
          script.src = `/plugins/${plugin.name}/${jsPath}`;
          script.onload = resolve;
          script.onerror = () => {
            console.warn(`[plugin] Failed to load ${plugin.name}/${jsPath}`);
            resolve(); // continue loading other plugins
          };
          document.head.appendChild(script);
        });
      }
    }
    console.log(`[plugin] Loaded ${plugins.length} plugin(s)`);
  } catch (e) {
    console.warn('[plugin] Failed to load plugins:', e);
  }
}
loadPlugins();

// ---------- 8. Start ----------
connect();
state.dom.input.focus();
