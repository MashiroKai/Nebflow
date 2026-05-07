import state from './state.js';
import { LS_SESSIONS_KEY } from './state.js';
import { initSpinner, initMarkdown, smartScroll } from './utils.js';
import { connect, onMessage, sendWs } from './ws.js';
import {
  setBusy, clearBusy, setStatus, clearStatus,
  renderUserBubble, appendAiText, finishAi,
  appendAgentText, finishAgent, getAgentColor,
  renderTool, renderToolPending, renderError, renderTimeoutNotice,
  renderSystemBubble, renderRetryStatus, clearRetryStatus,
  showOptions, renderAskUser, renderPermissionPrompt,
  renderAttachmentPreview,
  appendAskAnswer, finishAskAnswer, renderAskError
} from './chat.js';
import {
  initNavTabs, renderSessionSidebar, renderAgentList, renderSettings,
  switchSession, deleteSession, formatSessionTime, setSessionAttention,
  persistUnread
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
import { initSearch, renderSearchResults } from './search.js';

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
  bgTasksEl: document.getElementById('bg-tasks'),
  bgTasksCount: document.getElementById('bg-tasks')?.querySelector('.bg-task-count'),
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
  persistUnread();
  // Update agent-level unread count
  const agentName = state.sessionAgentMap[sessionId];
  if (agentName && agentName !== state.selectedAgent) {
    state.agentUnreadCounts[agentName] = (state.agentUnreadCounts[agentName] || 0) + 1;
    updateAgentNotificationDot(agentName);
  }
  window.dispatchEvent(new CustomEvent('session-unread', { detail: { sessionId } }));
}

// Show/hide notification dot on an agent avatar
function updateAgentNotificationDot(agentName) {
  const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
  if (!el) return;
  const count = state.agentUnreadCounts[agentName] || 0;
  let dot = el.querySelector('.agent-notif-dot');
  if (count > 0) {
    if (!dot) {
      dot = document.createElement('div');
      dot.className = 'agent-notif-dot';
      el.appendChild(dot);
    }
  } else if (dot) {
    dot.remove();
  }
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
// Helper: reset activity-based stream timeout for a busy session
function resetStreamTimeout(sid) {
  if (!sid || !state.busySessionIds.has(sid)) return;
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
  }
  state.sessionBusyTimeouts[sid] = setTimeout(() => {
    if (state.busySessionIds.has(sid)) {
      import('./chat.js').then(({ renderTimeoutNotice, clearBusy, clearStatus }) => {
        if (sid === state.activeSessionId) renderTimeoutNotice();
        clearBusy(sid);
        clearStatus();
      });
    }
  }, state.streamTimeoutMs + 30000);
}

// --- Chat streaming ---
// ALL sessions: save to localStorage. Active session only: render DOM.

onMessage('textDelta', (msg) => {
  const sid = msg.sessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate text for ALL sessions
  if (sid) state.sessionTexts[sid] = (state.sessionTexts[sid] || '') + msg.delta;
  resetStreamTimeout(sid);
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
  resetStreamTimeout(sid);
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

onMessage('toolCallDetected', (msg) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.name === 'AskUserQuestion') return;
  const sid = msg.sessionId;
  if (sid && !state.sessionPendingTools[sid]) state.sessionPendingTools[sid] = { label: msg.name };
  if (isActive(msg)) {
    if (!state.busySessionIds.has(msg.sessionId || state.activeSessionId)) setBusy(msg.sessionId || state.activeSessionId);
    clearRetryStatus();
    const prevData = finishAi();
    if (prevData) saveMsg(prevData, msg.sessionId);
    renderToolPending(msg.name, msg.sessionId);
  }
});

onMessage('toolStart', (msg) => {
  resetStreamTimeout(msg.sessionId);
  // Skip pending card for AskUser — the askUser event handles rendering directly
  if (msg.label && msg.label.startsWith('AskUser')) return;
  const toolStartSid = msg.sessionId;
  if (toolStartSid) state.sessionPendingTools[toolStartSid] = { label: msg.label };
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
  resetStreamTimeout(msg.sessionId);
  if (msg.label && msg.label.startsWith('AskUser')) return;
  const toolEndSid = msg.sessionId;
  if (toolEndSid) delete state.sessionPendingTools[toolEndSid];
  if (isActive(msg)) {
    const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input, msg.sessionId, msg.truncated);
    if (data) saveMsg(data, msg.sessionId);
  } else {
    saveMsg({type: 'tool', label: msg.label, summary: msg.summary, content: msg.content, isError: msg.isError, input: msg.input, truncated: msg.truncated}, msg.sessionId);
  }
});

// --- Terminal events ---
// Always clear busySessionId. DOM + status only for active session. Always mark unread for non-active.

onMessage('done', (msg) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  const durationMs = consumeTurnDuration(sid);
  delete state.sessionPendingTools[sid];
  console.log('[done] handler', { sid, activeSessionId: state.activeSessionId, isActive: isActive(msg), hasBubble: !!state.currentAiBubble, aiTextLen: (state.aiText || '').length });
  // Flush any remaining buffered text for this session
  if (msg.sessionId && state.sessionTexts[msg.sessionId]) {
    if (!isActive(msg)) {
      saveMsg({type: 'ai', text: state.sessionTexts[msg.sessionId], durationMs, model: msg.model}, msg.sessionId);
    }
    delete state.sessionTexts[msg.sessionId];
  }
  if (isActive(msg)) {
    // Clean up per-session pending tool card for this session
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    const data = finishAi(durationMs, msg.model);
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
  delete state.sessionPendingTools[msg.sessionId || state.activeSessionId];
  // Reset history loading state — backend may fail mid-pagination
  state.historyLoading = false;
  hideHistoryLoader();
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
  delete state.sessionPendingTools[msg.sessionId || state.activeSessionId];
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
  delete state.sessionPendingTools[msg.sessionId || state.activeSessionId];
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
  // Request agent list on first connect (no tab to trigger it now)
  if (!state.selectedAgent) sendWs({ type: 'listAgents' });
});

// --- History pagination indicators ---
function showHistoryLoader() {
  let loader = state.dom.chat.querySelector('.history-loader');
  if (loader) return;
  loader = document.createElement('div');
  loader.className = 'history-loader';
  loader.innerHTML = '<div class="history-spinner"></div><span>Loading...</span>';
  state.dom.chat.prepend(loader);
}

function hideHistoryLoader() {
  const loader = state.dom.chat.querySelector('.history-loader');
  if (loader) loader.remove();
}

function showHistoryEnd() {
  if (state.dom.chat.querySelector('.history-end')) return;
  const end = document.createElement('div');
  end.className = 'history-end';
  end.textContent = '— No more messages —';
  state.dom.chat.prepend(end);
}

function clearHistoryIndicators() {
  state.dom.chat.querySelectorAll('.history-loader, .history-end').forEach(el => el.remove());
}

// --- Backend history page ---
// For initial load: replaces chat content.
// For scroll-up pagination: prepends older messages before existing content.
onMessage('historyPage', (msg) => {
  const sid = msg.sessionId;
  if (sid !== state.activeSessionId) return;
  state.historyLoading = false;
  hideHistoryLoader();

  // state.historyOffset is 0 only right after resetChatForActiveSession() — that's how
  // we distinguish initial load from scroll-up pagination that happens to reach offset 0.
  const isInitialLoad = state.historyOffset === 0;
  if (isInitialLoad) {
    // Initial load or full refresh — replace
    state.historyOffset = msg.offset;
    state.historyTotal = msg.total;
    state.historyHasMore = msg.hasMore;
    state.dom.chat.innerHTML = '';
    restoreFromBackendHistory(msg.messages);

    // Re-create streaming state if this session is still busy.
    if (state.busySessionIds.has(sid)) {
      if (state.sessionTexts[sid]) {
        state.aiText = state.sessionTexts[sid];
        const chat = state.dom.chat;
        const row = document.createElement('div');
        row.className = 'row ai';
        state.currentAiBubble = document.createElement('div');
        state.currentAiBubble.className = 'bubble ai';
        state.currentAiBubble.innerHTML = renderMarkdownWithMath(state.aiText) + '<span class="cursor"></span>';
        row.appendChild(state.currentAiBubble);
        chat.appendChild(row);
      }
      const askBuf = state.sessionAskBuffers[sid];
      if (askBuf && askBuf.answer) {
        state.askAnswerText = askBuf.answer;
        const chat = state.dom.chat;
        const row = document.createElement('div');
        state.currentAskBubble = document.createElement('div');
        state.currentAskBubble.className = 'bubble ai';
        const label = document.createElement('div');
        label.className = 'ask-label';
        label.textContent = 'Ask';
        const content = document.createElement('div');
        content.innerHTML = renderMarkdownWithMath(state.askAnswerText) + '<span class="cursor"></span>';
        state.currentAskBubble.appendChild(label);
        state.currentAskBubble.appendChild(content);
        row.appendChild(state.currentAskBubble);
        chat.appendChild(row);
      }
      // Re-create pending tool card if this session has an in-progress tool
      if (state.sessionPendingTools[sid]) {
        renderToolPending(state.sessionPendingTools[sid].label, sid);
      }
    }

    // Re-create interactive AskUser if the last history message is an unanswered askUser.
    // Must be OUTSIDE the busySessionIds check — a non-active session that received
    // AskUser was never added to busySessionIds (setBusy only runs for the active session).
    // Use attentionSessions (set for ALL sessions on askUser) as the pending indicator.
    if (state.attentionSessions.has(sid)) {
      const histMsgs = msg.messages;
      const lastMsg = histMsgs && histMsgs[histMsgs.length - 1];
      if (lastMsg && lastMsg.type === 'askUser' && Array.isArray(lastMsg.items) && lastMsg.items.length > 0) {
        const rows = state.dom.chat.querySelectorAll('.row.ai');
        const lastAiRow = rows[rows.length - 1];
        if (lastAiRow && lastAiRow.querySelector('.option-box')) {
          lastAiRow.remove();
        }
        renderAskUser(lastMsg.items, sid);
      }
    }

    if (!state.historyHasMore && msg.messages.length > 0) showHistoryEnd();
    smartScroll();

    // Handle search navigation: scroll to a specific message after history loads
    if (state.searchNavigateTarget && state.searchNavigateTarget.sessionId === sid) {
      const target = state.searchNavigateTarget;
      state.searchNavigateTarget = null;
      // messageIndex is in the full array; adjust by the loaded page offset
      const domIdx = target.messageIndex - msg.offset;
      const rows = state.dom.chat.querySelectorAll('.row');
      if (domIdx >= 0 && domIdx < rows.length) {
        const targetRow = rows[domIdx];
        targetRow.scrollIntoView({ behavior: 'smooth', block: 'center' });
        targetRow.style.transition = 'background 0.3s';
        targetRow.style.background = 'rgba(7,193,96,0.15)';
        setTimeout(() => { targetRow.style.background = ''; }, 2000);
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
    // Move rendered children to fragment, skip animation on prepended rows
    while (tempDiv.firstChild) {
      const child = tempDiv.firstChild;
      if (child.classList && child.classList.contains('row')) {
        child.classList.add('prepend-skip-anim');
      }
      fragment.appendChild(child);
    }
    chat.prepend(fragment);
    // Restore scroll position so user stays at the same message
    const newScrollHeight = chat.scrollHeight;
    chat.scrollTop = prevScrollTop + (newScrollHeight - prevScrollHeight);
    state.historyOffset = msg.offset;
    state.historyHasMore = msg.hasMore;
    if (!state.historyHasMore) showHistoryEnd();
  }
});

// --- Multi-agent events ---
onMessage('agentStart', (msg) => {
  resetStreamTimeout(msg.sessionId);
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

onMessage('agentTextDelta', (msg) => { resetStreamTimeout(msg.sessionId); if (isActive(msg)) appendAgentText(msg.agentId || state.activeAgentId, msg.delta); });
onMessage('agentToolCallDetected', (msg) => { 
  resetStreamTimeout(msg.sessionId); 
  const aSid = msg.sessionId;
  if (aSid && !state.sessionPendingTools[aSid]) state.sessionPendingTools[aSid] = { label: msg.name };
  if (isActive(msg)) renderToolPending(msg.name, msg.sessionId); 
});
onMessage('agentToolStart', (msg) => { 
  resetStreamTimeout(msg.sessionId); 
  const aSid2 = msg.sessionId;
  if (aSid2) state.sessionPendingTools[aSid2] = { label: msg.label };
  if (isActive(msg)) renderToolPending(msg.label, msg.sessionId); 
});
onMessage('agentToolEnd', (msg) => {
  resetStreamTimeout(msg.sessionId);
  const aSid3 = msg.sessionId;
  if (aSid3) delete state.sessionPendingTools[aSid3];
  if (!isActive(msg)) return;
  const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input, msg.sessionId, msg.truncated);
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
  resetStreamTimeout(msg.sessionId);
  if (!isActive(msg)) return;
  if (state.activeAgentId && state.agentBubbles[state.activeAgentId]) {
    state.agentBubbles[state.activeAgentId].bubble.innerHTML = '<span style="color:#999;font-size:13px;">Thinking...</span>';
  }
});

onMessage('agentRetryStatus', (msg) => {
  resetStreamTimeout(msg.sessionId);
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
  resetStreamTimeout(sid);
  setCompacting(sid, true);
  if (sid === state.activeSessionId) {
    state.dom.statusWrap.classList.add('compacting');
    setStatus('Compacting context...');
  }
});

onMessage('compactComplete', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
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
  resetStreamTimeout(sid);
  setCompacting(sid, false);
  if (sid === state.activeSessionId) {
    const wrap = state.dom.statusWrap;
    wrap.classList.remove('compacting');
    wrap.classList.add('compact-failed');
    setStatus(`Context compaction failed (attempt ${msg.attempt}/${msg.maxAttempts})`);
  }
  if (msg.attempt >= msg.maxAttempts) {
    if (isActive(msg)) {
      renderError(`Context compaction circuit breaker open after ${msg.attempt} attempts`);
    }
    clearBusyFor(msg);
  }
});

// --- Agent panel events (global) ---
onMessage('agentList', (msg) => {
  state.agentsData = msg.agents || [];
  if (msg.availableTools) state.agentAvailableTools = msg.availableTools;
  if (msg.autoTools) state.agentAutoTools = msg.autoTools;
  renderAgentList();
  // Auto-select first agent if none selected
  if (!state.selectedAgent && state.agentsData.length > 0) {
    import('./sidebar.js').then(({ selectAgent }) => {
      // Default to Nebula if present, otherwise first agent
      const nebula = state.agentsData.find(a => a.name === 'Nebula');
      selectAgent(nebula ? 'Nebula' : state.agentsData[0].name);
    });
  }
});

onMessage('agentSessionList', (msg) => {
  // Render sessions for the selected agent
  const agentName = msg.agentName;
  const sessions = msg.sessions || [];
  // Build sessionId -> agentName mapping
  sessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || agentName; });
  // Find active session for this agent
  const activeId = state.activeSessionId;
  renderSessionSidebar(sessions, sessions.find(s => s.id === activeId) ? activeId : (sessions[0]?.id || null));
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
  if (msg.language !== undefined) {
    state.language = msg.language || null;
  }
  if (msg.mcpServers) {
    state.mcpServers = msg.mcpServers;
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

// --- Model selection ---
onMessage('modelOptions', (msg) => {
  const models = msg.models || [];
  const current = msg.current || null;
  if (models.length === 0) {
    renderSystemBubble('No models available');
    return;
  }
  if (!state.currentAiBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    state.currentAiBubble = document.createElement('div');
    state.currentAiBubble.className = 'bubble ai';
    row.appendChild(state.currentAiBubble);
    state.dom.chat.appendChild(row);
  }
  const options = models.map(m => {
    const isCurrent = current && m.ref === current;
    return {label: m.label + (isCurrent ? ' ✓' : ''), desc: m.ref, ref: m.ref};
  });
  // Add "Default" option to reset
  options.unshift({label: 'Default', desc: 'Use config default model chain', ref: null});
  import('./chat.js').then(({ showOptions }) => {
    showOptions(state.currentAiBubble, [
      {question: 'Select model for this session', options: options.map(o => ({label: o.label, desc: o.desc}))}
    ], (answers) => {
      const selected = options.find(o => o.label === answers[0]);
      const modelRef = selected ? selected.ref : null;
      sendWs({type: 'setSessionModel', sessionId: state.activeSessionId, modelRef: modelRef});
      renderSystemBubble('Model: ' + (answers[0] === 'Default' ? 'default' : answers[0].replace(' ✓', '')));
    }, 'Apply');
  });
});

onMessage('sessionModelSet', (msg) => {
});

// --- Retry / fallback status ---
onMessage('retryStatus', (msg) => {
  resetStreamTimeout(msg.sessionId);
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
  resetStreamTimeout(msg.sessionId);
  if (msg.sessionId) state.sessionTasks[msg.sessionId] = msg.tasks;
  if (isActive(msg)) renderTaskList(msg.tasks);
});

// --- Background task indicator in header ---
function updateBgTasksUI() {
  const tasks = state.sessionBgTasks[state.activeSessionId] || [];
  const running = tasks.filter(t => t.status === 'running');
  const el = state.dom.bgTasksEl;
  const countEl = state.dom.bgTasksCount;
  if (!el || !countEl) return;
  if (running.length > 0) {
    el.classList.remove('hidden');
    countEl.textContent = running.length;
  } else {
    el.classList.add('hidden');
  }
}
state.updateBgTasksUI = updateBgTasksUI;

onMessage('backgroundTaskUpdate', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  if (!state.sessionBgTasks[sid]) state.sessionBgTasks[sid] = [];
  const tasks = state.sessionBgTasks[sid];
  const idx = tasks.findIndex(t => t.taskId === msg.taskId);
  if (idx >= 0) {
    tasks[idx].status = msg.status;
  } else {
    tasks.push({ taskId: msg.taskId, description: msg.description, status: msg.status });
  }
  // Remove completed/failed tasks after a brief delay so user sees the count update
  if (msg.status === 'completed' || msg.status === 'failed') {
    setTimeout(() => {
      state.sessionBgTasks[sid] = state.sessionBgTasks[sid].filter(t => t.status === 'running');
      updateBgTasksUI();
    }, 3000);
  }
  updateBgTasksUI();
});

// --- /ask command ---
onMessage('askTextDelta', (msg) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate ask text for ALL sessions
  if (sid) {
    if (!state.sessionAskBuffers[sid]) state.sessionAskBuffers[sid] = { question: '', answer: '' };
    state.sessionAskBuffers[sid].answer = (state.sessionAskBuffers[sid].answer || '') + msg.delta;
  }
  if (isActive(msg)) appendAskAnswer(msg.delta);
});
onMessage('askDone', (msg) => {
  const sid = msg.sessionId || state.activeSessionId;
  const durationMs = consumeTurnDuration(sid) || msg.durationMs;
  const buf = sid ? state.sessionAskBuffers[sid] : null;
  if (isActive(msg)) {
    const answer = state.askAnswerText || (buf ? buf.answer : '') || '';
    const question = buf ? buf.question : '';
    finishAskAnswer(durationMs, msg.model);
    if (question || answer) {
      saveMsg({ type: 'ask', question, answer, durationMs, model: msg.model }, msg.sessionId);
    }
  } else if (buf && (buf.question || buf.answer)) {
    // Non-active session: save buffered ask to localStorage
    saveMsg({ type: 'ask', question: buf.question, answer: buf.answer, durationMs, model: msg.model }, sid);
  }
  if (sid) delete state.sessionAskBuffers[sid];
});
onMessage('askError', (msg) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid) delete state.sessionAskBuffers[sid];
  if (isActive(msg)) renderAskError(msg.message);
});


// --- Search results ---
onMessage('searchResults', (msg) => {
  renderSearchResults(msg.query, msg.results || []);
});


// ---------- 4. Cross-module wiring ----------
setNewSessionHandler(() => startInlineNewSession());
window.__showDeleteModal = showDeleteModal;

// ---------- 5. Initialize UI modules ----------
console.log('[main] initializing modules...');
initNavTabs();
initModals();
initInput();
initSearch();
console.log('[main] modules initialized, connecting ws...');

// Scroll listener
state.dom.chat.addEventListener('scroll', () => {
  state.scrollSnapped = state.dom.chat.scrollTop + state.dom.chat.clientHeight >= state.dom.chat.scrollHeight - 40;
  // Scroll-to-top: load older messages
  if (state.dom.chat.scrollTop < 100 && state.historyHasMore && !state.historyLoading && state.historyOffset > 0) {
    state.historyLoading = true;
    showHistoryLoader();
    sendWs({ type: 'getHistory', sessionId: state.activeSessionId, limit: 50, beforeIndex: state.historyOffset });
  }
});

// ---------- 6. Expose global Nebflow API for plugins ----------
window.Nebflow = {
  registerCardRenderer,
  escapeHtml,
  get state() { return state; },
};

// ---------- 7. Load agent frontend assets ----------
async function loadAgentFrontends() {
  try {
    const res = await fetch('/agents/manifest.json');
    if (!res.ok) return;
    const data = await res.json();
    const agents = data.agents || [];
    let loaded = 0;
    for (const agent of agents) {
      if (!agent.frontend) continue;
      // Load CSS first
      for (const cssPath of (agent.frontend.styles || [])) {
        const link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = `/agents/${agent.name}/${cssPath}`;
        document.head.appendChild(link);
      }
      // Load JS in order
      for (const jsPath of (agent.frontend.scripts || [])) {
        await new Promise((resolve) => {
          const script = document.createElement('script');
          script.src = `/agents/${agent.name}/${jsPath}`;
          script.onload = resolve;
          script.onerror = () => {
            console.warn(`[agent-frontend] Failed to load ${agent.name}/${jsPath}`);
            resolve(); // continue loading other agents
          };
          document.head.appendChild(script);
        });
      }
      loaded++;
    }
    if (loaded > 0) console.log(`[agent-frontend] Loaded frontend assets for ${loaded} agent(s)`);
  } catch (e) {
    console.warn('[agent-frontend] Failed to load agent frontends:', e);
  }
}
loadAgentFrontends();

// ---------- 8. Start ----------
connect();
state.dom.input.focus();
