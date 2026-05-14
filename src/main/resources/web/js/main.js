import state from './state.js';
import { LS_SESSIONS_KEY, LS_MODEL_INFO_KEY } from './state.js';
import { initSpinner, initMarkdown, smartScroll, renderMarkdownWithMath } from './utils.js';
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
  initHeaderModelInfo,
  persistUnread, initFeishuPanel, createNewFolder
} from './sidebar.js';
import {
  showNewSessionModal, hideModals, confirmNewSession,
  showDeleteModal, confirmDeleteSession,
  showDeleteFolderModal,
  showAgentModal, hideAgentModal, initModals,
  startInlineNewSession
} from './modal.js';
import { send, handleSlash, addFileAttachment, initInput, setNewSessionHandler, injectUserMessage } from './input.js';
import { saveMsg, loadMsgs, restoreFromStorage, restoreFromBackendHistory, migrateLegacyIfNeeded } from './persistence.js';
import { renderTaskList } from './taskList.js';
import { renderWithRegistry } from './cardRegistry.js';
import { escapeHtml } from './utils.js';
import { initSearch, renderSearchResults } from './search.js';
import { showMemoryButton, handleMemoryData, initMemory, clearMemoryCache } from './memory.js';

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
  bgIndicatorEl: document.getElementById('bg-indicator'),
  bgCountEl: document.getElementById('bg-indicator')?.querySelector('.bg-count'),
  bgDropdownEl: document.getElementById('bg-dropdown'),
  bgDropdownListEl: document.getElementById('bg-dropdown')?.querySelector('.bg-dropdown-list'),
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

// Header model info display
if (!state.sessionModelInfo) state.sessionModelInfo = {};

function formatTokens(n) {
  if (n == null) return '';
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return Math.round(n / 1000) + 'k';
  return String(n);
}

function updateHeaderModelInfo() {
  const el = document.getElementById('header-model-info');
  if (!el) return;
  const sid = state.activeSessionId;
  const info = sid ? state.sessionModelInfo[sid] : null;
  if (!info || !info.contextWindow) {
    el.textContent = '';
    el.style.display = 'none';
    return;
  }
  const ratio = info.inputTokens != null ? info.inputTokens / info.contextWindow : 0;
  const pct = Math.min(Math.round(ratio * 100), 100);
  let barColor = '#4caf50'; // green
  if (ratio > 0.5) barColor = '#d4a030'; // amber
  if (ratio > 0.75) barColor = '#e53935'; // red

  const thresholdPct = Math.round(state.COMPACT_THRESHOLD * 100);
  const tooltip = info.inputTokens != null
    ? `${formatTokens(info.inputTokens)} / ${formatTokens(info.contextWindow)} tokens (${pct}%)`
    : `${formatTokens(info.contextWindow)} context window`;

  el.innerHTML = `
    <div class="ctx-bar-wrap" title="${tooltip}">
      <div class="ctx-bar-track">
        <div class="ctx-bar-fill" style="width:${pct}%;background:${barColor};"></div>
        <div class="ctx-bar-threshold" style="left:${thresholdPct}%;"></div>
      </div>
      <span class="ctx-bar-label">${formatTokens(info.inputTokens)}/${formatTokens(info.contextWindow)}</span>
    </div>
  `;
  el.style.display = 'inline-flex';
}
state.updateHeaderModelInfo = updateHeaderModelInfo;

onMessage('done', (msg) => {
  console.log('[done] raw msg:', JSON.stringify(msg));
  console.log('[done] contextWindow:', msg.contextWindow, 'inputTokens:', msg.inputTokens, 'model:', msg.model);
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  // Defensive: clear attention when turn ends (in case answer callback didn't fire)
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  const durationMs = consumeTurnDuration(sid);
  delete state.sessionPendingTools[sid];
  console.log('[done] handler', { sid, activeSessionId: state.activeSessionId, isActive: isActive(msg), hasBubble: !!state.currentAiBubble, aiTextLen: (state.aiText || '').length });
  // Store model info for this session
  if (sid && (msg.model || msg.contextWindow || msg.inputTokens != null)) {
    state.sessionModelInfo[sid] = {
      model: msg.model || state.sessionModelInfo[sid]?.model,
      contextWindow: msg.contextWindow || state.sessionModelInfo[sid]?.contextWindow,
      inputTokens: msg.inputTokens != null ? msg.inputTokens : state.sessionModelInfo[sid]?.inputTokens
    };
    try { localStorage.setItem(LS_MODEL_INFO_KEY, JSON.stringify(state.sessionModelInfo)); } catch(e) {}
    if (isActive(msg)) updateHeaderModelInfo();
  }
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
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  // Defensive: clear attention on error
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  // Reset history loading state — backend may fail mid-pagination
  state.historyLoading = false;
  hideHistoryLoader();
  if (isActive(msg)) {
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
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  // Defensive: clear attention on interrupt
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (isActive(msg)) {
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
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
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
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (isActive(msg)) {
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
  // Build sessionAgentMap from ALL sessions
  const allSessions = msg.sessions || [];
  const allFolders = msg.folders || [];
  allSessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || 'Nebula'; });

  // Filter by selected agent if one is active (safety net — backend should send agentSessionList)
  let sessionsToShow = allSessions;
  if (state.selectedAgent) {
    sessionsToShow = allSessions.filter(s => (s.agentName || 'Nebula') === state.selectedAgent);
  }
  state.folders = allFolders;

  // Determine activeId: only use it if it belongs to the filtered (shown) sessions
  let activeId = msg.activeId;
  if (state.selectedAgent && activeId) {
    const activeAgent = state.sessionAgentMap[activeId];
    if (activeAgent !== state.selectedAgent) {
      // activeId belongs to a different agent — pick the first session of the current agent instead
      activeId = sessionsToShow[0]?.id || null;
    }
  }

  renderSessionSidebar(sessionsToShow, activeId);
  initHeaderModelInfo();
  // Only restore messages on first load (before any session was active)
  if (!restoredSessionId && activeId) {
    restoredSessionId = activeId;
    // Try backend history first; fall back to localStorage
    sendWs({ type: 'getHistory', sessionId: activeId, limit: 100 });
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

    // Detect if the agent is waiting for AskUser — in that case it's NOT actively streaming.
    const histMsgs = msg.messages;
    const lastHistMsg = histMsgs && histMsgs[histMsgs.length - 1];
    const isAskUserPending = lastHistMsg && lastHistMsg.type === 'askUser'
      && Array.isArray(lastHistMsg.items) && lastHistMsg.items.length > 0;

    if (isAskUserPending) {
      // Agent is blocked on AskUser — clear stale sessionTexts so we don't create
      // a phantom streaming bubble for text that's already in history.
      delete state.sessionTexts[sid];
    }

    // Re-create streaming state if this session is still busy.
    if (state.busySessionIds.has(sid) && !isAskUserPending) {
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
    // Simply check if the last history message is askUser with items — if already answered,
    // there would be subsequent Ai/Tool messages after it, so it wouldn't be the last message.
    if (isAskUserPending) {
      // Remove ALL disabled askUser rows (restored by restoreFromBackendHistory).
      state.dom.chat.querySelectorAll('.row.ai').forEach(row => {
        if (row.querySelector('.option-box')) row.remove();
      });
      renderAskUser(lastHistMsg.items, sid);
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
    const detail = msg.reportPath ? ` (report: ${msg.reportPath.split('/').pop()})` : '';
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
  const folders = msg.folders || [];
  state.folders = folders;
  // Build sessionId -> agentName mapping
  sessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || agentName; });
  // Find active session for this agent
  const activeId = state.activeSessionId;
  renderSessionSidebar(sessions, sessions.find(s => s.id === activeId) ? activeId : (sessions[0]?.id || null));
  initHeaderModelInfo();
});

onMessage('agentConfig', (msg) => showAgentModal(msg.name, msg.configJson || '', msg.systemMd || ''));
onMessage('agentCreated', () => sendWs({ type: 'listAgents' }));
onMessage('agentUpdated', () => sendWs({ type: 'listAgents' }));

// --- Server config ---
onMessage('serverConfig', (msg) => {
  if (msg.streamTimeoutMs) state.streamTimeoutMs = msg.streamTimeoutMs;
  if (msg.version) state.serverVersion = msg.version;
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

// MCP server list updated (after background init completes)
onMessage('mcpServersUpdate', (msg) => {
  if (msg.mcpServers) state.mcpServers = msg.mcpServers;
});

onMessage('configData', (msg) => {
  state.configText = msg.config || '';
  try { state.parsedConfig = JSON.parse(state.configText); } catch { state.parsedConfig = null; }
  state.configDirty = false;
  const editor = document.getElementById('config-editor');
  if (editor) editor.value = state.configText;
  // Re-render settings if panel is visible
  const settingsPanel = document.getElementById('panel-settings');
  if (settingsPanel && settingsPanel.classList.contains('active')) {
    renderSettings();
  }
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
      {question: 'Select model for this session', options: options.map(o => ({label: o.label, desc: o.desc})), allowOther: false}
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
    if (msg.busy) {
      setBusy(msg.sessionId);
    } else {
      clearBusy(msg.sessionId);
      // Defensive: if the 'done' event was lost but backend sent busy=false,
      // finish any active streaming bubble so the cursor disappears and the
      // duration badge is rendered.
      const sid = msg.sessionId || state.activeSessionId;
      delete state.sessionPendingTools[sid];
      if (state.sessionTexts[sid]) delete state.sessionTexts[sid];
      if (state.currentAiBubble) {
        const durationMs = consumeTurnDuration(sid);
        const data = finishAi(durationMs, null);
        if (data) saveMsg(data, sid);
        Object.keys(state.agentBubbles).forEach(id => finishAgent(id));
        state.agentBubbles = {};
        state.activeAgentId = null;
        clearStatus();
      }
    }
  }
});

// --- Task list ---
onMessage('taskListUpdate', (msg) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.sessionId) state.sessionTasks[msg.sessionId] = msg.tasks;
  if (isActive(msg)) renderTaskList(msg.tasks);
});

// --- Background task indicator in header ---
let _bgTimer = null;

function formatDuration(ms) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

function renderBgDropdown() {
  const tasks = state.sessionBgTasks[state.activeSessionId] || [];
  const listEl = state.dom.bgDropdownListEl;
  const dropdown = state.dom.bgDropdownEl;
  if (!listEl || !dropdown) return;
  listEl.innerHTML = '';
  const running = tasks.filter(t => t.status === 'running');
  if (running.length === 0) {
    dropdown.classList.add('hidden');
    stopBgTimer();
    return;
  }
  const now = Date.now();
  running.forEach(t => {
    const row = document.createElement('div');
    row.className = 'bg-task-row';
    const info = document.createElement('div');
    info.className = 'bg-task-info';
    const desc = document.createElement('span');
    desc.className = 'bg-task-desc';
    desc.textContent = t.description || t.taskId;
    const meta = document.createElement('div');
    meta.className = 'bg-task-meta';
    const idSpan = document.createElement('span');
    idSpan.className = 'bg-task-id';
    idSpan.textContent = t.taskId;
    const durationSpan = document.createElement('span');
    durationSpan.className = 'bg-task-duration';
    durationSpan.dataset.taskId = t.taskId;
    if (t.startedAt) durationSpan.textContent = formatDuration(now - t.startedAt);

    // Heartbeat status indicator
    const hb = t.heartbeat;
    let statusDot = null;
    let linesSpan = null;
    if (hb) {
      const idleClass = hb.idleMs > 600000 ? 'bg-status-stuck' : (hb.idleMs > 120000 ? 'bg-status-idle' : 'bg-status-active');
      statusDot = document.createElement('span');
      statusDot.className = `bg-task-status ${idleClass}`;
      statusDot.title = hb.alive ? (hb.idleMs > 600000 ? 'No output for 10+ min' : 'Running') : 'Process ended';
      linesSpan = document.createElement('span');
      linesSpan.className = 'bg-task-lines';
      linesSpan.textContent = `${hb.outputLines} lines`;
    }

    meta.appendChild(idSpan);
    if (statusDot) meta.appendChild(statusDot);
    meta.appendChild(durationSpan);
    if (linesSpan) meta.appendChild(linesSpan);
    info.appendChild(desc);
    info.appendChild(meta);
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'bg-task-cancel';
    cancelBtn.textContent = 'Cancel';
    cancelBtn.onclick = (e) => {
      e.stopPropagation();
      sendWs({ type: 'cancelBackgroundJob', sessionId: state.activeSessionId, jobId: t.taskId });
      cancelBtn.disabled = true;
      cancelBtn.textContent = '...';
    };
    row.appendChild(info);
    row.appendChild(cancelBtn);
    listEl.appendChild(row);
  });
}

function startBgTimer() {
  if (_bgTimer) return;
  _bgTimer = setInterval(() => {
    const dropdown = state.dom.bgDropdownEl;
    if (!dropdown || dropdown.classList.contains('hidden')) { stopBgTimer(); return; }
    const tasks = state.sessionBgTasks[state.activeSessionId] || [];
    const now = Date.now();
    dropdown.querySelectorAll('.bg-task-duration').forEach(el => {
      const t = tasks.find(t => t.taskId === el.dataset.taskId);
      if (t && t.startedAt) el.textContent = formatDuration(now - t.startedAt);
    });
  }, 1000);
}

function stopBgTimer() {
  if (_bgTimer) { clearInterval(_bgTimer); _bgTimer = null; }
}

function updateBgTasksUI() {
  const tasks = state.sessionBgTasks[state.activeSessionId] || [];
  const running = tasks.filter(t => t.status === 'running');
  const el = state.dom.bgIndicatorEl;
  const countEl = state.dom.bgCountEl;
  const dropdown = state.dom.bgDropdownEl;
  if (!el || !countEl) return;
  if (running.length > 0) {
    el.classList.remove('hidden');
    countEl.textContent = running.length;
  } else {
    el.classList.add('hidden');
    if (dropdown) dropdown.classList.add('hidden');
    stopBgTimer();
  }
  if (dropdown && !dropdown.classList.contains('hidden')) {
    renderBgDropdown();
    startBgTimer();
  }
}
state.updateBgTasksUI = updateBgTasksUI;

// Toggle dropdown on indicator click
document.getElementById('bg-indicator')?.addEventListener('click', (e) => {
  e.stopPropagation();
  const dropdown = state.dom.bgDropdownEl;
  if (!dropdown) return;
  if (dropdown.classList.contains('hidden')) {
    renderBgDropdown();
    dropdown.classList.remove('hidden');
    startBgTimer();
  } else {
    dropdown.classList.add('hidden');
    stopBgTimer();
  }
});

// Close dropdown when clicking outside
document.addEventListener('click', (e) => {
  const dropdown = state.dom.bgDropdownEl;
  const indicator = state.dom.bgIndicatorEl;
  if (dropdown && !dropdown.contains(e.target) && !indicator?.contains(e.target)) {
    dropdown.classList.add('hidden');
    stopBgTimer();
  }
});

onMessage('backgroundTaskUpdate', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  if (!state.sessionBgTasks[sid]) state.sessionBgTasks[sid] = [];
  const tasks = state.sessionBgTasks[sid];
  const idx = tasks.findIndex(t => t.taskId === msg.taskId);
  if (idx >= 0) {
    tasks[idx].status = msg.status;
    if (msg.heartbeat) tasks[idx].heartbeat = msg.heartbeat;
  } else {
    tasks.push({
      taskId: msg.taskId,
      description: msg.description,
      status: msg.status,
      startedAt: msg.startedAt || Date.now(),
      heartbeat: msg.heartbeat || null
    });
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


// --- Memory ---
onMessage('memoryData', (msg) => handleMemoryData(msg));
onMessage('memorySaved', () => { /* saved confirmation, no action needed */ });
onMessage('memoryStatus', (msg) => showMemoryButton());

// --- Search results ---
onMessage('searchResults', (msg) => {
  renderSearchResults(msg.query, msg.results || []);
});


// ---------- 4. Cross-module wiring ----------
setNewSessionHandler(() => startInlineNewSession());
window.__showDeleteModal = showDeleteModal;
window.__showDeleteFolderModal = showDeleteFolderModal;

// ---------- 5. Initialize UI modules ----------
console.log('[main] initializing modules...');
initNavTabs();
initModals();
initInput();
initSearch();
initFeishuPanel();
initMemory();
// New Folder button
document.getElementById('new-folder-btn')?.addEventListener('click', () => createNewFolder());

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
// Theme tokens extracted from CSS custom properties — agents can read these for consistency.
const _themeCache = {};
function getThemeTokens() {
  if (Object.keys(_themeCache).length) return _themeCache;
  const s = getComputedStyle(document.documentElement);
  const pick = (prop) => s.getPropertyValue(prop).trim();
  _themeCache.primary = pick('--color-primary') || '#07c160';
  _themeCache.primaryHover = pick('--color-primary-hover') || '#06ad56';
  _themeCache.error = pick('--color-error') || '#f44336';
  _themeCache.success = pick('--color-success') || '#4caf50';
  _themeCache.bubbleAi = pick('--color-bubble-ai') || '#fff';
  _themeCache.text = pick('--color-text') || '#000';
  _themeCache.textMuted = pick('--color-text-muted') || '#888';
  _themeCache.border = pick('--color-border') || '#ddd';
  return _themeCache;
}

window.Nebflow = {
  // --- Card rendering ---
  escapeHtml,
  getThemeTokens,
  /** Send a message as if the user typed it. */
  injectUserMessage,
  /** Get read-only state snapshot. */
  get state() { return state; },
  /** Currently active session ID. */
  get activeSessionId() { return state.activeSessionId; },
  /** Currently selected agent name. */
  get selectedAgent() { return state.selectedAgent; },
  /** Send a raw WebSocket message. */
  sendWs,
  /** Smart-scroll the chat to the bottom. */
  smartScroll,
};

// ---------- 8. Start ----------
connect();
sendWs({ type: 'memoryStatus' });
state.dom.input.focus();
