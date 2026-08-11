import state from './state.js';
import { LS_SESSIONS_KEY, LS_MODEL_INFO_KEY } from './state.js';
import { initSpinner, initMarkdown, smartScroll, renderMarkdownWithMath } from './utils.js';
import { connect, onMessage, sendWs, onReconnect } from './ws.js';
import {
  setBusy, clearBusy, clearStatus,
  renderUserBubble, appendAiText, finishAi,
  appendAgentText, finishAgent, getAgentColor,
  renderTool, renderToolPending, renderError, renderTimeoutNotice,
  renderSystemBubble, renderRetryStatus, clearRetryStatus,
  showOptions, renderAskUser, renderPermissionPrompt,
  renderAttachmentPreview,
  appendAskAnswer, finishAskAnswer, renderAskError,
  appendThinkingDelta, finishThinking,
  appendToolStreamDelta, cancelToolStreamRAF
} from './chat.js';
import {
  initNavTabs, renderSessionSidebar, renderAgentList, renderSettings,
  deleteSession, formatSessionTime, setSessionAttention,
  persistUnread, createNewFolder, getCurrentFolderId,
  resetChatForActiveSession
} from './sidebar.js';
import {
  showNewSessionModal, hideModals, confirmNewSession,
  showDeleteModal, confirmDeleteSession,
  showDeleteFolderModal,
  showAgentModal, hideAgentModal, initModals
} from './modal.js';
import { send, handleSlash, addFileAttachment, initInput, injectUserMessage, enterAskMode, cancelAskMode, registerSkillCommands, drainMessageQueue, restoreQueue } from './input.js';import { saveMsg, loadMsgs, restoreFromStorage, restoreFromBackendHistory, migrateLegacyIfNeeded, emergencyCacheCleanup } from './persistence.js';
import { renderTaskList } from './taskList.js';
import { renderWithRegistry } from './cardRegistry.js';
import { escapeHtml } from './utils.js';
import { showMemoryButton, handleMemoryData, handleMemoryChanged, initMemory, clearMemoryCache } from './memory.js';
import { handleRulesData, handleRulesSaved, handleRulesDeleted, handleBrowseResult, initRulesModal, initPathPicker } from './sidebar.js';
import { t, getLocale } from './i18n.js';
import { applyLocaleToHtml } from './i18n.js';
import { initScheduledTask, refreshScheduledTasks } from './scheduled-task.js';
import { initDaemons } from './daemons.js';
import { initExplorer, refreshExplorer } from './explorer.js';
import { initChatView, chatViews, findViewBySessionId, activeView, setActiveView } from './chatView.js';
import { handleFlowAgentHistory } from './flowAgentPopup.js';
import { handleDelegateHistory, openStepPopup as openDelegatePopup, cleanupDelegateView } from './delegatePopup.js';
import { initNeblink, checkPairingRedirect } from './neblink.js';
import { initDropbox } from './dropbox.js';
import { formatLiveDuration } from './chat.js';
import * as planMode from './planMode.js';
import { initCanvas, restoreTabs, closeCanvas, openCanvas } from './canvas.js';
import * as flowCanvas from './flowCanvas.js';
import { initColResizers } from './colResizer.js';
import { initActivityBar } from './activityBar.js';

// ---------- Live thinking timer ----------
let _thinkingTimerInterval = null;
let _thinkingTimerEl = null;

function startThinkingTimer() {
  stopThinkingTimer(); // clean any previous
  const sid = state.activeSessionId;
  const startTime = state.turnStartTimes[sid];
  if (!startTime) return;

  // Insert timer element into the existing thinking placeholder
  const placeholder = activeView.dom.chat.querySelector('.thinking-placeholder');
  if (!placeholder) return;

  // Remove old thinking text, replace with indicator structure
  placeholder.innerHTML = '';
  const indicator = document.createElement('div');
  indicator.className = 'thinking-indicator';

  // Animated dots
  for (let i = 0; i < 3; i++) {
    const dot = document.createElement('span');
    dot.className = 'thinking-dot';
    if (i === 1) dot.style.animationDelay = '0.15s';
    if (i === 2) dot.style.animationDelay = '0.3s';
    indicator.appendChild(dot);
  }

  // Label
  const label = document.createElement('span');
  label.className = 'thinking-indicator-label';
  label.textContent = t('chat.thinking.now') || '思考中';
  indicator.appendChild(label);

  // Live timer
  const timer = document.createElement('span');
  timer.className = 'thinking-timer';
  timer.textContent = formatLiveDuration(Date.now() - startTime);
  indicator.appendChild(timer);

  placeholder.appendChild(indicator);
  _thinkingTimerEl = timer;

  // Tick every second
  _thinkingTimerInterval = setInterval(() => {
    if (_thinkingTimerEl) {
      _thinkingTimerEl.textContent = formatLiveDuration(Date.now() - startTime);
    }
  }, 1000);
}

function stopThinkingTimer() {
  if (_thinkingTimerInterval) {
    clearInterval(_thinkingTimerInterval);
    _thinkingTimerInterval = null;
  }
  _thinkingTimerEl = null;
}
// Expose for cross-module cleanup (input.js)
window.__stopThinkingTimer = stopThinkingTimer;

// ---------- 1. Populate DOM refs ----------
state.dom = {
  chat: document.getElementById('chat'),
  input: document.getElementById('input'),
  sendBtn: document.getElementById('send-btn'),
  stopBtn: document.getElementById('stop-btn'),
  voiceBtn: document.getElementById('voice-btn'),
  attachBtn: document.getElementById('attach-btn'),
  attPreview: document.getElementById('attachment-preview'),
  voiceOverlay: document.getElementById('voice-overlay'),
  voiceText: document.getElementById('voice-text'),
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
  agentSystemInput: document.getElementById('agent-system-input'),
  agentModalCancel: document.getElementById('agent-modal-cancel'),
  agentModalSave: document.getElementById('agent-modal-save'),
  bgIndicatorEl: document.getElementById('bg-indicator'),
  bgCountEl: document.getElementById('bg-indicator')?.querySelector('.bg-count'),
  bgDropdownEl: document.getElementById('bg-dropdown'),
  bgDropdownListEl: document.getElementById('bg-dropdown')?.querySelector('.bg-dropdown-list'),
  // Header status indicators — surfaced on state.dom for ws.js indicator updates.
  bypassToggleEl: document.getElementById('bypass-toggle'),
  delegateIndicatorEl: document.getElementById('delegate-indicator'),
  delegateDropdownEl: document.getElementById('delegate-dropdown'),
  delegateDropdownListEl: document.getElementById('delegate-dropdown')?.querySelector('.bg-dropdown-list'),
  memoryBtnEl: document.getElementById('memory-btn'),
};

// ── Initialize ChatView ───────────────────────────────────────────────
// Single view instance for the main panel. The chatViews registry supports
// future multi-view expansion — additional views can register via
// chatViews.<id> = new ChatView(...).
initChatView(
  // Primary window DOM refs — field names MUST match state.dom keys exactly,
  // so Object.assign(state.dom, view.dom) correctly overrides each field.
  {
    chat: document.getElementById('chat'),
    input: document.getElementById('input'),
    sendBtn: document.getElementById('send-btn'),
    stopBtn: document.getElementById('stop-btn'),
    attachBtn: document.getElementById('attach-btn'),
    attPreview: document.getElementById('attachment-preview'),
    slashDropdown: document.getElementById('slash-dropdown'),
    queueBar: document.getElementById('queue-bar'),
    voiceBtn: document.getElementById('voice-btn'),
    voiceOverlay: document.getElementById('voice-overlay'),
    voiceText: document.getElementById('voice-text'),
    bgIndicatorEl: document.getElementById('bg-indicator'),
    bgCountEl: document.getElementById('bg-indicator')?.querySelector('.bg-count'),
    bgDropdownEl: document.getElementById('bg-dropdown'),
    bgDropdownListEl: document.getElementById('bg-dropdown')?.querySelector('.bg-dropdown-list'),
    delegateIndicatorEl: document.getElementById('delegate-indicator'),
    delegateDropdownEl: document.getElementById('delegate-dropdown'),
    delegateDropdownListEl: document.getElementById('delegate-dropdown')?.querySelector('.bg-dropdown-list'),
    sessionNameEl: document.getElementById('session-name'),
  }
);

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

// Helper: compute and clear turn duration for a session
function consumeTurnDuration(sid) {
  const startTime = state.turnStartTimes[sid];
  if (!startTime) return undefined;
  delete state.turnStartTimes[sid];
  return Date.now() - startTime;
}

// Helper: clear busy for a specific session, then drain any queued messages.
// Called by ALL terminal events (done, error, interrupted, timeout, maxTokens,
// compactFailed) — not just 'done' — so the queue drains regardless of how the
// turn ended.
function clearBusyFor(msg) {
  const sid = msg.sessionId || state.activeSessionId;
  if (state.busySessionIds.has(sid)) {
    clearBusy(sid);
  }
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  // Drain queued messages after a short delay to let the UI finalize first
  if (sid) {
    setTimeout(() => drainMessageQueue(sid), 50);
  }
  // Release the send lock for the view displaying this session
  const view = findViewBySessionId(sid);
  if (view) view.isSending = false;
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
        const v = findViewBySessionId(sid);
        if (v) { setActiveView(v); renderTimeoutNotice(); clearStatus(); }
        clearBusy(sid);
      });
    }
  }, state.streamTimeoutMs + 30000);
}

// --- Chat streaming ---
// ALL sessions: save to localStorage. Active session only: render DOM.

onMessage('thinkingDelta', (msg, view) => {
  const sid = msg.sessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate thinking text for ALL sessions
  if (sid) state.sessionThinkingBuffers[sid] = (state.sessionThinkingBuffers[sid] || '') + msg.delta;
  resetStreamTimeout(sid);
  state.lastStreamActivity = Date.now();
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (view) {
    stopThinkingTimer();
    // Remove the generic thinking placeholder if it exists
    const existing = activeView.dom.chat.querySelector('.thinking-placeholder');
    if (existing) {
      const row = existing.closest('.row');
      if (row) row.remove();
      if (activeView.stream.currentAiBubble === existing) activeView.stream.currentAiBubble = null;
    }
    // Guard: only create thinking bubbles when a turn is expected (user sent
    // message or server explicitly started a new round). Prevents stray thinking
    // bubbles from late-arriving thinkingDelta after done has been processed.
    if (!state.turnExpecting[sid] && !activeView.stream.currentThinkingBubble && !activeView.stream.currentAiBubble) {
      return;
    }
    appendThinkingDelta(msg.delta);
  }
});

onMessage('textDelta', (msg, view) => {
  const sid = msg.sessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate text for ALL sessions
  if (sid) state.sessionTexts[sid] = (state.sessionTexts[sid] || '') + msg.delta;
  resetStreamTimeout(sid);
  state.lastStreamActivity = Date.now();
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (view) {
    stopThinkingTimer();
    clearRetryStatus();
    // Finish thinking bubble before first text delta
    if (activeView.stream.currentThinkingBubble) finishThinking();
    appendAiText(msg.delta);
  }
});

onMessage('textDone', (msg, view) => {
  const sid = msg.sessionId;
  if (view) {
    stopThinkingTimer();
    if (activeView.stream.currentThinkingBubble) finishThinking();
    const tThinking = state.sessionThinkingBuffers[sid] || '';
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    const durationMs = consumeTurnDuration(sid);
    const data = finishAi(durationMs);
    if (data) {
      data.thinking = tThinking || undefined;
      saveMsg(data, sid);
    }
  } else if (sid && state.sessionTexts[sid]) {
    // Non-active session: save to localStorage + stash in pendingRestore
    // for switch-back restoration, then reset sessionTexts/sessionThinkingBuffers
    // (must reset between turns to avoid cross-turn concatenation).
    const thinkingText = state.sessionThinkingBuffers[sid] || '';
    saveMsg({type: 'ai', text: state.sessionTexts[sid], thinking: thinkingText || undefined}, sid);
    state.pendingRestore[sid] = { text: state.sessionTexts[sid], thinking: thinkingText || undefined };
    delete state.sessionTexts[sid];
    if (state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
  }
});

onMessage('thinking', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  resetStreamTimeout(sid);
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (view) {
    // Guard against duplicate thinking bubbles: check both state ref and DOM.
    const existing = activeView.dom.chat.querySelector('.thinking-placeholder');
    if (!activeView.stream.currentAiBubble && !existing) {
      const { chat } = activeView.dom;
      const row = document.createElement('div');
      row.className = 'row ai';
      activeView.stream.currentAiBubble = document.createElement('div');
      activeView.stream.currentAiBubble.className = 'bubble ai thinking-placeholder';
      row.appendChild(activeView.stream.currentAiBubble);
      chat.appendChild(row);
      smartScroll();
      // Start live timer after a brief moment so DOM is settled
      requestAnimationFrame(() => startThinkingTimer());
    }
  }
});

onMessage('toolCallDetected', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.name === 'AskUserQuestion') return;
  const sid = msg.sessionId;
  if (sid && !state.sessionPendingTools[sid]) state.sessionPendingTools[sid] = { label: msg.name };

  // Flush accumulated text buffer for ALL sessions at tool call boundary.
  // This prevents cross-round concatenation in sessionTexts[sid] for non-active
  // sessions (active sessions are handled by finishAi() below).
  if (sid && state.sessionTexts[sid]) {
    const thinkingText = state.sessionThinkingBuffers[sid] || '';
    if (!state.sessionPendingAiMessages[sid]) state.sessionPendingAiMessages[sid] = [];
    state.sessionPendingAiMessages[sid].push({
      type: 'ai',
      text: state.sessionTexts[sid],
      thinking: thinkingText || undefined
    });
    delete state.sessionTexts[sid];
    if (state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
  }

  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (view) {
    clearRetryStatus();
    if (activeView.stream.currentThinkingBubble) finishThinking();
    // Flush thinking buffer for active sessions to prevent cross-turn concatenation.
    // finishAi() only returns text, so we read thinking separately from the buffer.
    const tThinking = state.sessionThinkingBuffers[sid] || '';
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
    // In ask mode, finalize the current ask bubble so the tool card renders below it
    if (activeView.stream.currentAskBubble) finishAskAnswer();
    // If the existing pending card was claimed by a previous tool (toolStart fired),
    // close its streaming display and clear the slot so a new card is created.
    const existingCard = state.sessionToolCards[sid];
    if (existingCard && existingCard.isConnected) {
      const cardEl = existingCard.querySelector('.tool-card');
      if (cardEl && cardEl.dataset.toolLabel) {
        cancelToolStreamRAF();
        existingCard.querySelectorAll('.cursor').forEach(el => el.remove());
        delete state.sessionToolCards[sid];
      }
    }
    renderToolPending(msg.name, msg.sessionId);
    // Reset tool argument streaming for the new tool call
    activeView.stream.toolStreamText = '';
    activeView.stream.toolStreamToolName = msg.name;
  }
});

onMessage('toolStart', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  // Skip pending card for AskUser — the askUser event handles rendering directly
  if (msg.label && msg.label.startsWith('AskUser')) return;
  const toolStartSid = msg.sessionId;
  if (toolStartSid) state.sessionPendingTools[toolStartSid] = { label: msg.label };

  // Flush accumulated text buffer for ALL sessions at tool boundary.
  // Same as toolCallDetected — ensures sessionTexts doesn't accumulate
  // across rounds for non-active sessions.
  if (toolStartSid && state.sessionTexts[toolStartSid]) {
    const thinkingText = state.sessionThinkingBuffers[toolStartSid] || '';
    if (!state.sessionPendingAiMessages[toolStartSid]) state.sessionPendingAiMessages[toolStartSid] = [];
    state.sessionPendingAiMessages[toolStartSid].push({
      type: 'ai',
      text: state.sessionTexts[toolStartSid],
      thinking: thinkingText || undefined
    });
    delete state.sessionTexts[toolStartSid];
    if (state.sessionThinkingBuffers[toolStartSid]) delete state.sessionThinkingBuffers[toolStartSid];
  }

  if (view) {
    if (!state.busySessionIds.has(msg.sessionId || state.activeSessionId)) setBusy(msg.sessionId || state.activeSessionId);
    clearRetryStatus();
    // Finish the current AI bubble so that text after tool execution goes into a new bubble
    if (activeView.stream.currentThinkingBubble) finishThinking();
    // Flush thinking buffer for active sessions (same as toolCallDetected)
    const tThinking = state.sessionThinkingBuffers[toolStartSid] || '';
    if (toolStartSid && state.sessionThinkingBuffers[toolStartSid]) delete state.sessionThinkingBuffers[toolStartSid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
    // In ask mode, finalize the current ask bubble so the tool card renders below it
    if (activeView.stream.currentAskBubble) finishAskAnswer();
    renderToolPending(msg.label, msg.sessionId);
    // Tag the card with the full tool label so toolEnd can find the right card
    // when multiple tools share the session (single sessionToolCards slot).
    const startedRow = state.sessionToolCards[msg.sessionId];
    if (startedRow) {
      const cardEl = startedRow.querySelector('.tool-card');
      if (cardEl) cardEl.dataset.toolLabel = msg.label;
    }
  }
});

onMessage('toolEnd', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.label && msg.label.startsWith('AskUser')) return;
  const toolEndSid = msg.sessionId;
  if (toolEndSid) delete state.sessionPendingTools[toolEndSid];
  if (view) {
    const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input, msg.sessionId);
    if (data) saveMsg(data, msg.sessionId);
  } else {
    saveMsg({type: 'tool', label: msg.label, summary: msg.summary, content: msg.content, isError: msg.isError, input: msg.input}, msg.sessionId);
  }
});

onMessage('toolArgDelta', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (view) {
    appendToolStreamDelta(msg.toolName, msg.delta);
  }
});

// --- Terminal events ---
// Always clear busySessionId. DOM + status only for active session. Always mark unread for non-active.

// Header model info display
if (!state.sessionModelInfo) state.sessionModelInfo = {};


// Real-time usage update after each LLM round (multi-round tool calling)
onMessage('usageUpdate', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && msg.inputTokens != null && msg.contextWindow) {
    state.sessionModelInfo[sid] = {
      model: state.sessionModelInfo[sid]?.model,
      contextWindow: msg.contextWindow,
      inputTokens: msg.inputTokens,
      compactThreshold: msg.compactThreshold
    };
    try { localStorage.setItem(LS_MODEL_INFO_KEY, JSON.stringify(state.sessionModelInfo)); } catch(e) {}
  }
});

onMessage('done', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  // Defensive: clear attention when turn ends (in case answer callback didn't fire)
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  const durationMs = consumeTurnDuration(sid);
  delete state.sessionPendingTools[sid];
  // Turn is complete — clear turnExpecting so stray thinkingDelta won't create bubbles
  if (sid) delete state.turnExpecting[sid];
  // Clean up answered permission tracking — turn is done
  if (sid) state.answeredPermissions.delete(sid);
  // Store model info for this session
  if (sid && (msg.model || msg.contextWindow || msg.inputTokens != null)) {
    state.sessionModelInfo[sid] = {
      model: msg.model || state.sessionModelInfo[sid]?.model,
      contextWindow: msg.contextWindow || state.sessionModelInfo[sid]?.contextWindow,
      inputTokens: msg.inputTokens != null ? msg.inputTokens : state.sessionModelInfo[sid]?.inputTokens,
      compactThreshold: msg.compactThreshold != null ? msg.compactThreshold : state.sessionModelInfo[sid]?.compactThreshold
    };
    try { localStorage.setItem(LS_MODEL_INFO_KEY, JSON.stringify(state.sessionModelInfo)); } catch(e) {}
  }
  // Flush any remaining buffered text/thinking for this session
  if (msg.sessionId) {
    if (!view) {
      const thinkingText = state.sessionThinkingBuffers[msg.sessionId] || '';
      // Save ALL pending segments accumulated at tool boundaries, then the final round.
      const pendingSegments = state.sessionPendingAiMessages[msg.sessionId] || [];
      pendingSegments.forEach(seg => saveMsg(seg, msg.sessionId));
      delete state.sessionPendingAiMessages[msg.sessionId];

      if (state.sessionTexts[msg.sessionId] || thinkingText) {
        const text = state.sessionTexts[msg.sessionId] || '';
        // Save final round's text/thinking as the main message
        saveMsg({type: 'ai', text, thinking: thinkingText || undefined, durationMs, model: msg.model}, msg.sessionId);
        // pendingRestore only contains the LAST round's data (not concatenated across rounds),
        // so dedup against backend history (last Ai message) works correctly.
        state.pendingRestore[msg.sessionId] = { text, thinking: thinkingText || undefined, durationMs, model: msg.model };
      }
      delete state.sessionTexts[msg.sessionId];
      delete state.sessionThinkingBuffers[msg.sessionId];
    } else {
      delete state.sessionTexts[msg.sessionId];
      // Note: sessionThinkingBuffers is NOT deleted here for active sessions —
      // it's consumed by finishThinking() + the fallback read in the view block below.
    }
  }
  if (view) {
    stopThinkingTimer();
    // Clean up per-session pending tool card for this session
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    // Reset tool argument streaming state
    activeView.stream.toolStreamText = '';
    activeView.stream.toolStreamToolName = '';
    // Clean up pending segments accumulated at tool boundaries
    if (sid && state.sessionPendingAiMessages[sid]) delete state.sessionPendingAiMessages[sid];
    // Clean up pendingRestore (previous turn's data no longer needed)
    if (sid && state.pendingRestore[sid]) delete state.pendingRestore[sid];
    // Finish thinking bubble if still streaming
    const thinkingText = finishThinking() || (sid ? state.sessionThinkingBuffers[sid] || '' : '');
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    // Clear thinkingText unconditionally — when appendThinkingDelta skipped DOM
    // creation (second+ thinking block after text), finishThinking() had no bubble
    // to clear and activeView.stream.thinkingText retains skipped content.
    activeView.stream.thinkingText = '';
    const data = finishAi(durationMs, msg.model);
    if (data) {
      data.thinking = thinkingText || undefined;
      saveMsg(data, msg.sessionId);
    } else if (thinkingText) {
      // Thinking-only response (no text): keep thinking bubble expanded
      const thinkBubble = activeView.dom.chat.querySelector('.thinking-bubble');
      if (thinkBubble) {
        const content = thinkBubble.querySelector('.thinking-content');
        const label = thinkBubble.querySelector('.thinking-label');
        if (content) content.style.display = '';
        if (label) label.classList.add('expanded');
      }
      // Save without text field so restoreFromStorage doesn't render an empty bubble
      saveMsg({ type: 'ai', thinking: thinkingText, durationMs, model: msg.model }, msg.sessionId);
    }
    Object.keys(activeView.stream.agentBubbles).forEach(id => finishAgent(id));
    activeView.stream.agentBubbles = {};
    activeView.stream.activeAgentId = null;
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
  // Queue drainage handled by clearBusyFor above — no duplicate call here.
});

// roundComplete: backend signals the current round's text is finalized but a new
// LLM round is about to start (e.g. pendingEvents injection). Finalize the
// current AI bubble without ending the turn (no done/sessionBusy(false)).
onMessage('roundComplete', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (view) {
    if (activeView.stream.currentThinkingBubble) finishThinking();
    const tThinking = state.sessionThinkingBuffers[sid] || '';
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
    // Show thinking placeholder for the upcoming round — backend sent sessionBusy(true)
    // after roundComplete, so the agent is still working.
    if (sid && state.busySessionIds.has(sid)) {
      if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
      const { chat } = activeView.dom;
      const existing = chat.querySelector('.thinking-placeholder');
      if (!activeView.stream.currentAiBubble && !existing) {
        const row = document.createElement('div');
        row.className = 'row ai';
        activeView.stream.currentAiBubble = document.createElement('div');
        activeView.stream.currentAiBubble.className = 'bubble ai thinking-placeholder';
        row.appendChild(activeView.stream.currentAiBubble);
        chat.appendChild(row);
        smartScroll();
        requestAnimationFrame(() => startThinkingTimer());
      }
    }
  }
});

onMessage('error', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  if (sid) delete state.pendingRestore[sid];
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) delete state.turnExpecting[sid];
  // Defensive: clear attention on error
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) state.answeredPermissions.delete(sid);
  // Reset history loading state — backend may fail mid-pagination
  const errView = findViewBySessionId(sid);
  if (errView) errView.pagination.loading = false;
  if (view) view.pagination.loading = false;
  hideHistoryLoader();
  if (view) {
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishThinking();
    finishAi();
    renderError(msg.message);
    clearStatus();
  } else {
    saveMsg({type: 'error', text: msg.message}, msg.sessionId);
    markSessionUnread(msg.sessionId);
  }
});

onMessage('interrupted', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  if (sid) delete state.pendingRestore[sid];
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) delete state.turnExpecting[sid];
  // Defensive: clear attention on interrupt
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) state.answeredPermissions.delete(sid);
  if (view) {
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishThinking();
    finishAi();
    clearStatus();
  }
});

onMessage('timeout', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) state.answeredPermissions.delete(sid);
  if (view) {
    finishThinking();
    finishAi();
    renderTimeoutNotice();
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

onMessage('maxTokens', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  if (sid) delete state.pendingRestore[sid];
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) delete state.turnExpecting[sid];
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) state.answeredPermissions.delete(sid);
  if (view) {
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishThinking();
    finishAi();
    renderError('Max tokens reached — response truncated');
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

// --- AskUser / Permission ---
onMessage('askUser', (msg, view) => {
  const sid = msg.sessionId;
  if (sid) setSessionAttention(sid, true);
  // AskUser waits for human response — suppress stream timeout indefinitely
  if (sid && state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  if (view) {
    // Defensive: finalize any in-flight AI bubble before rendering the question.
    // Normally roundComplete (sent before askUser by the backend) handles this,
    // but guard against edge cases where the bubble is still pending.
    if (activeView.stream.currentAiBubble) {
      const prevData = finishAi();
      if (prevData) saveMsg(prevData, sid);
    }
    const data = renderAskUser(msg.items, msg.sessionId, msg.agentName);
    if (data) saveMsg(data, msg.sessionId);
  } else if (sid) {
    // Non-active session: persist so it can be restored on session switch
    saveMsg({ type: 'askUser', items: msg.items, agentName: msg.agentName }, sid);
  }
});

onMessage('askPermission', (msg, view) => {
  const sid = msg.sessionId;
  // Bypass mode: auto-approve immediately without showing attention indicator.
  // This must run for BOTH active and non-active sessions — previously only
  // active sessions got bypass treatment (inside renderPermissionPrompt),
  // leaving non-active sessions stuck with a yellow indicator that never clears.
  if (sid && state.bypassSessions.has(sid)) {
    if (view) {
      // Active session: renderPermissionPrompt detects bypass, sends approval,
      // and shows the "auto-approved" badge. Let it handle everything.
      renderPermissionPrompt(msg.toolName, msg.summary, msg.input, msg.sessionId, msg.dangerLevel, msg.sourceAgent, msg.sourceSession);
    } else {
      // Non-active session: auto-approve directly (renderPermissionPrompt is never called).
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: sid, approved: true }));
      }
      saveMsg({ type: 'askPermission', toolName: msg.toolName, summary: msg.summary, input: msg.input, dangerLevel: msg.dangerLevel, autoApproved: true, sourceAgent: msg.sourceAgent, sourceSession: msg.sourceSession }, sid);
    }
    return;
  }
  if (sid) setSessionAttention(sid, true);
  // Permission prompt waits for human response — suppress stream timeout indefinitely
  if (sid && state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  // Clear stale "answered" tracking: a new permission request means any
  // previous answer in this session (same turn) is no longer relevant.
  // Without this, a second permission in the same turn would be stuck as
  // disabled because answeredPermissions still holds this sid.
  if (sid) state.answeredPermissions.delete(sid);
  if (view) {
    renderPermissionPrompt(msg.toolName, msg.summary, msg.input, msg.sessionId, msg.dangerLevel, msg.sourceAgent, msg.sourceSession);
  } else if (sid) {
    // Non-active session: persist so it can be restored on session switch
    saveMsg({ type: 'askPermission', toolName: msg.toolName, summary: msg.summary, input: msg.input, dangerLevel: msg.dangerLevel, sourceAgent: msg.sourceAgent, sourceSession: msg.sourceSession }, sid);
  }
});

onMessage('permissionExpired', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  // Mark as answered so the prompt isn't re-created on session switch
  state.answeredPermissions.add(sid);
  // Remove the permission prompt row from DOM
  if (view) {
    view.dom.chat.querySelectorAll('.row.ai').forEach(row => {
      if (row.querySelector('.permission-pending-box')) row.remove();
    });
  }
  // Clear attention indicator
  setSessionAttention(sid, false);
});

// --- Session list (global) ---
let restoredSessionId = null;
onMessage('sessionList', (msg, view) => {
  // Build sessionAgentMap from ALL sessions
  const allSessions = msg.sessions || [];
  const allFolders = msg.folders || [];
  allSessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || 'Nebula'; });

  // Restore safety mode from persisted session metadata
  state.safetyModes = {};
  allSessions.forEach(s => { if (s.safetyMode) state.safetyModes[s.id] = s.safetyMode; });
  // Derive bypassSessions (auto-all) for chat.js auto-approve compatibility
  state.bypassSessions = new Set(allSessions.filter(s => s.safetyMode === 'auto-all').map(s => s.id));

  state.folders = allFolders;
  state.foldersWithRules = new Set(msg.foldersWithRules || []);

  const activeId = msg.activeId;

  renderSessionSidebar(allSessions, activeId);
  // Mark the initial session as restored — getHistory is already sent by
  // resetChatForActiveSession (called inside renderSessionSidebar when activeId changes).
  if (!restoredSessionId && activeId) {
    restoredSessionId = activeId;
    // Restore flow canvas now that we have a valid session ID
    flowCanvas.autoRestore();
  }
  migrateLegacyIfNeeded();
  // Request agent list on first connect (no tab to trigger it now)
  if (!state.selectedAgent) sendWs({ type: 'listAgents' });
});

// --- History pagination indicators ---
function showHistoryLoader() {
  let loader = activeView.dom.chat.querySelector('.history-loader');
  if (loader) return;
  loader = document.createElement('div');
  loader.className = 'history-loader';
  loader.innerHTML = '<div class="history-spinner"></div><span>' + t('chat.loading') + '</span>';
  activeView.dom.chat.prepend(loader);
}

function hideHistoryLoader() {
  document.querySelectorAll('.history-loader').forEach(el => el.remove());
}

function showHistoryEnd() {
  if (activeView.dom.chat.querySelector('.history-end')) return;
  const end = document.createElement('div');
  end.className = 'history-end';
  end.textContent = t('chat.noMoreMessages');
  activeView.dom.chat.prepend(end);
}

function clearHistoryIndicators() {
  activeView.dom.chat.querySelectorAll('.history-loader, .history-end').forEach(el => el.remove());
}

// --- Backend history page ---
// For initial load: replaces chat content.
// For scroll-up pagination: prepends older messages before existing content.
onMessage('historyPage', (msg, view) => {
  // Delegate sub-agent sessions are handled by the delegate popup viewer.
  if (handleDelegateHistory(msg)) return;
  // Flow agent sessions are handled by the popup viewer, not the primary chat.
  if (handleFlowAgentHistory(msg)) return;

  const sid = msg.sessionId;
  hideHistoryLoader();
  if (!view) return;
  view.pagination.loading = false;

  // Use explicit flag instead of historyOffset === 0 to prevent double-clear.
  const isInitialLoad = view.pagination.pendingInitialLoad;
  if (isInitialLoad) {
    view.pagination.pendingInitialLoad = false;
    // Initial load or full refresh — replace
    activeView.dom.chat.innerHTML = '';
    // Reset sessionToolCards — innerHTML clear above removes all tool pending
    // card DOM nodes, but renderToolPending uses sessionToolCards[sid] as an
    // existence check. A stale DOM reference causes it to skip card creation
    // (update-in-place on a detached node), leaving no spinner visible.
    Object.keys(state.sessionToolCards).forEach(sid => delete state.sessionToolCards[sid]);
    // Clear history indicators before rendering
    clearHistoryIndicators();
    view.pagination.offset = msg.offset;
    view.pagination.total = msg.total;
    view.pagination.hasMore = msg.hasMore;
    restoreFromBackendHistory(msg.messages);

    // Detect if the agent is waiting for AskUser — in that case it's NOT actively streaming.
    const histMsgs = msg.messages;
    const lastHistMsg = histMsgs && histMsgs[histMsgs.length - 1];
    const isAskUserPending = lastHistMsg && lastHistMsg.type === 'askUser'
      && Array.isArray(lastHistMsg.items) && lastHistMsg.items.length > 0;
    const isAskPermissionPending = lastHistMsg && lastHistMsg.type === 'askPermission'
      && lastHistMsg.toolName;

    if (isAskUserPending || isAskPermissionPending) {
      // Agent is blocked on AskUser/AskPermission — clear stale sessionTexts so we don't create
      // a phantom streaming bubble for text that's already in history.
      delete state.sessionTexts[sid];
    }

    // Re-create streaming/completed state from sessionTexts/sessionThinkingBuffers/pendingRestore.
    const isStillBusy = state.busySessionIds.has(sid);
    if (!isAskUserPending && !isAskPermissionPending) {
      // If the backend history already includes the completed message, clean up pendingRestore
      // to avoid duplication. Check last AI message text+thinking match.
      const pendingData = state.pendingRestore[sid];
      if (pendingData && !isStillBusy) {
        const histMsgs = msg.messages;
        const lastAiMsg = histMsgs && [...histMsgs].reverse().find(m => m.type === 'ai' && (m.text || m.thinking));
        const textMatch = lastAiMsg && lastAiMsg.text === pendingData.text;
        const thinkingMatch = lastAiMsg && lastAiMsg.thinking === pendingData.thinking;
        // Exact match: both text and thinking (or both absent) match.
        // Loose match: text matches and thinking is absent on both sides, or
        //             thinking matches and text is absent on both sides.
        const looseMatch = lastAiMsg && (
          (textMatch && (!lastAiMsg.thinking || !pendingData.thinking || thinkingMatch)) ||
          (!lastAiMsg.text && !pendingData.text && thinkingMatch)
        );
        if (textMatch || looseMatch) {
          delete state.pendingRestore[sid];
        }
        // If texts don't match, keep pendingRestore — it may be from a turn
        // the backend hasn't persisted yet. The sessionPendingAiMessages fix
        // ensures text is not concatenated across rounds, so even if rendered
        // as extra bubbles, the content is correct (not duplicated/concatenated).
      }

      const pd = state.pendingRestore[sid];
      // After historyPage restores the authoritative state, clean up any remaining
      // per-session buffer state that wasn't consumed by the active streaming path.
      // This prevents stale data from leaking through on subsequent restores.
      if (!isStillBusy && !pd && !state.sessionTexts[sid]) {
        delete state.sessionThinkingBuffers[sid];
        delete state.sessionPendingAiMessages[sid];
      }
      const thinkBuf = state.sessionThinkingBuffers[sid] || pd?.thinking;
      const txtBuf = state.sessionTexts[sid] || pd?.text;

      // Restore thinking bubble
      if (thinkBuf) {
        activeView.stream.thinkingText = thinkBuf;
        const hasText = !!txtBuf;
        const done = hasText || !isStillBusy;
        const chat = activeView.dom.chat;
        const row = document.createElement('div');
        row.className = 'row ai thinking-row';
        const bubble = document.createElement('div');
        bubble.className = 'bubble ai thinking-bubble' + (done ? ' thinking-done' : '');
        const label = document.createElement('div');
        label.className = 'thinking-label' + (done ? ' collapsible' : '');
        label.textContent = t('chat.thinkingLabel');
        const content = document.createElement('div');
        content.className = 'thinking-content';
        if (done) {
          content.style.display = 'none';
          label.classList.add('expanded');
          label.onclick = () => {
            const visible = content.style.display !== 'none';
            content.style.display = visible ? 'none' : '';
            label.classList.toggle('expanded', !visible);
          };
        }
        content.innerHTML = renderMarkdownWithMath(activeView.stream.thinkingText) + (!done ? '<span class="cursor"></span>' : '');
        bubble.appendChild(label);
        bubble.appendChild(content);
        row.appendChild(bubble);
        chat.appendChild(row);
        activeView.stream.currentThinkingBubble = done ? null : bubble;
      }

      // Restore text bubble
      if (txtBuf) {
        activeView.stream.aiText = txtBuf;
        const chat = activeView.dom.chat;
        const row = document.createElement('div');
        row.className = 'row ai';
        activeView.stream.currentAiBubble = document.createElement('div');
        activeView.stream.currentAiBubble.className = 'bubble ai';
        activeView.stream.currentAiBubble.innerHTML = renderMarkdownWithMath(activeView.stream.aiText) + (isStillBusy ? '<span class="cursor"></span>' : '');
        row.appendChild(activeView.stream.currentAiBubble);
        chat.appendChild(row);
        if (!isStillBusy) {
          activeView.stream.currentAiBubble = null;
          activeView.stream.aiText = '';
          delete state.pendingRestore[sid];
        }
      }

      const askBuf = state.sessionAskBuffers[sid];
      if (isStillBusy && askBuf && askBuf.answer) {
        activeView.stream.askAnswerText = askBuf.answer;
        const chat = activeView.dom.chat;
        const row = document.createElement('div');
        activeView.stream.currentAskBubble = document.createElement('div');
        activeView.stream.currentAskBubble.className = 'bubble ai';
        const label = document.createElement('div');
        label.className = 'ask-label';
        label.textContent = t('chat.askLabel');
        const content = document.createElement('div');
        content.innerHTML = renderMarkdownWithMath(activeView.stream.askAnswerText) + '<span class="cursor"></span>';
        activeView.stream.currentAskBubble.appendChild(label);
        activeView.stream.currentAskBubble.appendChild(content);
        row.appendChild(activeView.stream.currentAskBubble);
        chat.appendChild(row);
      }
      // Re-create pending tool card if this session has an in-progress tool.
      // Also restored in sidebar.js resetChatForActiveSession() for immediate
      // feedback before history arrives. This re-creation ensures the spinner
      // persists after the async getHistory roundtrip clears the DOM.
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
      activeView.dom.chat.querySelectorAll('.row.ai').forEach(row => {
        if (row.querySelector('.option-box')) row.remove();
      });
      renderAskUser(lastHistMsg.items, sid, lastHistMsg.agentName);
    }

    // Re-create interactive AskPermission if the last history message is an unanswered askPermission.
    // Same logic as AskUser — if already answered, there would be subsequent tool messages.
    // Guard: skip if the user has already answered this permission in the current session
    // (tool is still executing, askPermission is still the last history entry).
    if (isAskPermissionPending && !state.answeredPermissions.has(sid)) {
      // Remove the disabled askPermission row (restored by restoreFromBackendHistory).
      activeView.dom.chat.querySelectorAll('.row.ai').forEach(row => {
        if (row.querySelector('.permission-pending-box')) row.remove();
      });
      renderPermissionPrompt(lastHistMsg.toolName, lastHistMsg.summary, lastHistMsg.input, sid, lastHistMsg.dangerLevel, lastHistMsg.sourceAgent, lastHistMsg.sourceSession);
    }

    // Final scroll-to-bottom: after all rendering (history + streaming bubbles + pending tools)
    // is complete, ensure the viewport shows the latest content.
    // Uses rAF to avoid layout thrashing — fires after any pending style calculations.
    requestAnimationFrame(() => {
      const chat = view.dom.chat;
      if (view.stream.scrollSnapped || chat.scrollHeight - chat.scrollTop - chat.clientHeight < 60) {
        chat.scrollTop = chat.scrollHeight;
        view.stream.scrollSnapped = true;
      }
      // Second pass after deferred markdown rendering settles
      setTimeout(() => { chat.scrollTop = chat.scrollHeight; }, 150);
    });

  } else {
    // Scroll-up pagination — prepend older messages
    // Guard against duplicate historyPage responses (e.g. from double getHistory on initial load):
    // Skip if the response offset is >= what we already have, OR if offset is 0
    // (offset 0 means "no older messages" — there is nothing valid to prepend).
    if (msg.offset >= view.pagination.offset || msg.offset === 0) {
      // Skipping duplicate response
      return;
    }
    const chat = activeView.dom.chat;
    const prevScrollHeight = chat.scrollHeight;
    const prevScrollTop = chat.scrollTop;
    // Insert before first child
    const fragment = document.createDocumentFragment();
    const tempDiv = document.createElement('div');
    const origChat = activeView.dom.chat;
    activeView.dom.chat = tempDiv;
    restoreFromBackendHistory(msg.messages, { scrollToBottom: false });
    activeView.dom.chat = origChat;
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
    view.pagination.offset = msg.offset;
    view.pagination.hasMore = msg.hasMore;
    if (!view.pagination.hasMore) showHistoryEnd();
  }
});

// --- Multi-agent events ---
// Sub-agent activity shows a header indicator (like Bash background tasks).
// No tool cards or text rendered in the chat — the parent's Delegate tool
// card (spinner → result) is the only chat-level feedback.
// Click the indicator to see a dropdown with per-agent status.

function updateDelegateIndicator() {
  if (!activeView) return;
  const el = activeView.dom.delegateIndicatorEl;
  if (!el) return;
  const sid = activeView?.sessionId;
  const delegates = (sid && state.sessionDelegates[sid]) || {};
  const count = Object.keys(delegates).length;
  if (count > 0) {
    el.classList.remove('hidden');
    el.querySelector('.delegate-count').textContent = count;
  } else {
    el.classList.add('hidden');
    const dropdown = activeView.dom.delegateDropdownEl;
    if (dropdown) dropdown.classList.add('hidden');
  }
  renderDelegateDropdown();
}
state.updateDelegateIndicator = updateDelegateIndicator;

function renderDelegateDropdown() {
  if (!activeView) return;
  const listEl = activeView.dom.delegateDropdownListEl;
  if (!listEl) return;
  const sid = activeView?.sessionId;
  const delegates = (sid && state.sessionDelegates[sid]) || {};
  const entries = Object.entries(delegates);
  if (entries.length === 0) {
    listEl.innerHTML = '';
    return;
  }
  listEl.innerHTML = entries.map(([id, info]) => {
    const toolLabel = info.currentTool || '';
    const toolPart = toolLabel ? '<span class="delegate-tool">' + escapeHtml(toolLabel) + '</span>' : '';
    const status = info.done ? '<span class="delegate-done">done</span>' : '<span class="delegate-running">running</span>';
    const displayName = info.name || id;
    const label = info.task ? displayName + ' · ' + escapeHtml(info.task) : displayName;
    // nodeSessionId for delegate sub-agents starts with "delegate-"
    const nodeSessionId = id.startsWith('delegate-') ? id : null;
    const clickAttr = nodeSessionId ? `data-node-session-id="${escapeHtml(nodeSessionId)}" style="cursor:pointer"` : '';
    return '<div class="bg-task-row" ' + clickAttr + '>' +
      '<div class="bg-task-info">' +
        '<span class="bg-task-name">' + status + ' ' + label + '</span>' +
        toolPart +
      '</div>' +
    '</div>';
  }).join('');

  // Wire click handlers for delegate rows
  listEl.querySelectorAll('[data-node-session-id]').forEach(row => {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      const nodeSessionId = row.getAttribute('data-node-session-id');
      const info = delegates[nodeSessionId];
      if (info) {
        openDelegatePopup(nodeSessionId, info.name, info.task);
        // Close the dropdown
        const dropdown = activeView.dom.delegateDropdownEl;
        if (dropdown) dropdown.classList.add('hidden');
      }
    });
  });
}

// Toggle dropdown on indicator click — register for ALL views
Object.values(chatViews).forEach(v => {
  const indicator = v.dom.delegateIndicatorEl;
  const dropdown = v.dom.delegateDropdownEl;
  if (!indicator || !dropdown) return;
  indicator.addEventListener('click', (e) => {
    e.stopPropagation();
    setActiveView(v);
    if (dropdown.classList.contains('hidden')) {
      renderDelegateDropdown();
      dropdown.classList.remove('hidden');
    } else {
      dropdown.classList.add('hidden');
    }
  });
});

// Close dropdown on outside click
document.addEventListener('click', (e) => {
  Object.values(chatViews).forEach(v => {
    const dropdown = v.dom.delegateDropdownEl;
    const indicator = v.dom.delegateIndicatorEl;
    if (dropdown && !dropdown.contains(e.target) && indicator && !indicator.contains(e.target)) {
      dropdown.classList.add('hidden');
    }
  });
});

onMessage('agentStart', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  // rootSessionId points at the top-level main session even for nested
  // delegates (child → grandchild), so sessionDelegates stays keyed by the
  // session the user is actually viewing.
  const sid = msg.rootSessionId || msg.sessionId || state.activeSessionId;
  if (!sid) return;
  const aid = msg.agentId || msg.name;
  if (view) view.stream.activeAgentId = aid;
  if (!state.sessionDelegates[sid]) state.sessionDelegates[sid] = {};
  state.sessionDelegates[sid][aid] = {
    name: msg.name || aid,
    task: msg.taskDescription || '',
    currentTool: null,
    done: false,
  };
  if (view) updateDelegateIndicator();
});

onMessage('agentTextDelta', (msg, view) => { resetStreamTimeout(msg.sessionId); });
onMessage('agentToolCallDetected', (msg, view) => { resetStreamTimeout(msg.sessionId); });

onMessage('agentToolStart', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  const sid = msg.rootSessionId || msg.sessionId || state.activeSessionId;
  if (!sid) return;
  const aid = msg.agentId || (view && view.stream.activeAgentId);
  if (aid && state.sessionDelegates[sid] && state.sessionDelegates[sid][aid]) {
    state.sessionDelegates[sid][aid].currentTool = msg.label;
    if (view) renderDelegateDropdown();
  }
});

onMessage('agentToolEnd', (msg, view) => { resetStreamTimeout(msg.sessionId); });
onMessage('agentEnd', (msg, view) => { resetStreamTimeout(msg.sessionId); });

onMessage('agentThinking', (msg, view) => { resetStreamTimeout(msg.sessionId); });
onMessage('agentRetryStatus', (msg, view) => { resetStreamTimeout(msg.sessionId); });

onMessage('agentDone', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  const sid = msg.rootSessionId || msg.sessionId || state.activeSessionId;
  if (!sid) return;
  const aid = msg.agentId || (view && view.stream.activeAgentId);
  if (aid && state.sessionDelegates[sid]) {
    if (state.sessionDelegates[sid][aid]) state.sessionDelegates[sid][aid].done = true;
    if (view) renderDelegateDropdown();
    // Clean up delegate popup view after a delay
    if (aid.startsWith('delegate-')) cleanupDelegateView(aid);
    // Remove after 2s — always runs, even if the parent session isn't displayed
    setTimeout(() => {
      if (state.sessionDelegates[sid] && state.sessionDelegates[sid][aid]) {
        delete state.sessionDelegates[sid][aid];
        // Clean up empty session entries
        if (Object.keys(state.sessionDelegates[sid]).length === 0) {
          delete state.sessionDelegates[sid];
        }
        // Update indicator if the affected view is currently displayed
        const targetView = findViewBySessionId(sid);
        if (targetView) {
          const saved = activeView;
          setActiveView(targetView);
          updateDelegateIndicator();
          setActiveView(saved);
        }
      }
    }, 2000);
  }
  if (view) view.stream.activeAgentId = null;
});

// --- Flow events → canvas DAG visualization ---
window.addEventListener('nebflow-session-change', (e) => {
  flowCanvas.onSessionChange(e.detail.sessionId);
  refreshExplorer(e.detail.sessionId);
  refreshScheduledTasks(e.detail.sessionId);
  if (e.detail.sessionId) sendWs({ type: 'getTaskList', sessionId: e.detail.sessionId });
});

onMessage('treeBranchMounted', () => {
  flowCanvas.refresh();
});
onMessage('treeBranchUnmounted', () => {
  flowCanvas.refresh();
});
onMessage('treeBranchUpdated', () => {
  flowCanvas.refresh();
});

onMessage('flowMail', (msg) => {
  flowCanvas.onFlowMail(msg);
});

// ── Flow agent status tracking ────────────────────────────
// agentStart/agentDone carry nodeSessionId (set by FlowAgentActivator's wsSend wrapper).
// ws.js intercepts them into the popup ChatView, but we also need to update
// the flow canvas status pills.
// Team agent events carry nodeSessionId = "team-<sessionId>"; strip the prefix
// so agentStatus keys match the bare sessionId from /api/teams/mounted
// (flowTeams.statusOf / flowCanvas.autoRestore look up by bare sid).
onMessage('agentStart', (msg) => {
  const sid = msg.nodeSessionId ? msg.nodeSessionId.replace(/^team-/, '') : null;
  if (sid) flowCanvas.onAgentStart(sid);
});
onMessage('agentDone', (msg) => {
  const sid = msg.nodeSessionId ? msg.nodeSessionId.replace(/^team-/, '') : null;
  if (sid) flowCanvas.onAgentDone(sid);
});

onMessage('flowStarted', (msg) => {
  flowCanvas.onFlowStarted(msg);
});

onMessage('flowProgress', (msg) => {
  flowCanvas.onFlowProgress(msg);
});

onMessage('flowStarted', (msg) => {
  flowCanvas.onFlowStarted(msg);
});

onMessage('flowCompleted', (msg) => {
  flowCanvas.onFlowCompleted(msg);
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

onMessage('compactStart', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
  setCompacting(sid, true);
  if (view) {
    renderSystemBubble(t('chat.compacting'));
  }
});

onMessage('compactComplete', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
  setCompacting(sid, false);
  if (view) {
    const detail = msg.reportPath ? ` (report: ${msg.reportPath.split('/').pop()})` : '';
    renderSystemBubble(t('chat.compacted', { before: msg.before, after: msg.after, detail }));
  }
  // Drain queued messages (compact = busy state, messages were queued)
  if (sid) {
    import('./input.js').then(({ drainMessageQueue }) => setTimeout(() => drainMessageQueue(sid), 50));
  }
});

onMessage('compactFailed', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
  setCompacting(sid, false);
  if (view) {
    renderSystemBubble(t('chat.compactFailed', { attempt: msg.attempt, maxAttempts: msg.maxAttempts }));
  }
  if (msg.attempt >= msg.maxAttempts) {
    if (view) {
      renderError(t('chat.compactCircuitBreaker', { attempt: msg.attempt }));
    }
    clearBusyFor(msg);
  } else {
    // Retry path — also drain queue in case user sent messages during compaction
    if (sid) {
      import('./input.js').then(({ drainMessageQueue }) => setTimeout(() => drainMessageQueue(sid), 50));
    }
  }
});

// --- Agent panel events (global) ---
onMessage('agentList', (msg, view) => {
  state.agentsData = msg.agents || [];
  renderAgentList();
  // Auto-select first agent if none selected
  if (!state.selectedAgent && state.agentsData.length > 0) {
    import('./sidebar.js').then(({ selectAgent }) => {
      selectAgent(state.agentsData[0]?.name || 'Nebula');
    });
  }
});

onMessage('agentSessionList', (msg, view) => {
  // Unified list — accept all sessions regardless of which agent triggered the request
  const agentName = msg.agentName;
  let sessions = msg.sessions || [];
  const folders = msg.folders || [];
  state.folders = folders;
  state.foldersWithRules = new Set(msg.foldersWithRules || []);

  // Build sessionId -> agentName mapping
  sessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || agentName; });

  // Restore bypass state from persisted session metadata
  state.bypassSessions = new Set(sessions.filter(s => s.safetyMode === 'auto-all').map(s => s.id));
  state.safetyModes = {};
  sessions.forEach(s => { if (s.safetyMode) state.safetyModes[s.id] = s.safetyMode; });

  // Render sidebar — active highlight shows the current active session
  renderSessionSidebar(sessions, state.activeSessionId);
});

onMessage('agentSystemPrompt', (msg, view) => showAgentModal(msg.name, msg.systemMd || ''));
onMessage('agentSystemPromptSaved', () => sendWs({ type: 'listAgents' }));

// --- Server config ---
onMessage('serverConfig', (msg, view) => {
  if (msg.streamTimeoutMs) state.streamTimeoutMs = msg.streamTimeoutMs;
  if (msg.version) state.serverVersion = msg.version;
  if (msg.thinking !== undefined) {
    state.serverThinking = msg.thinking;
    state.thinkingMode = msg.thinking;
  }
  if (msg.tools) {
    state.availableTools = msg.tools;
  }
  if (msg.mcpServers) {
    state.mcpServers = msg.mcpServers;
  }
});

// MCP server list updated (after background init completes)
onMessage('mcpServersUpdate', (msg, view) => {
  if (msg.mcpServers) state.mcpServers = msg.mcpServers;
});

onMessage('configData', (msg, view) => {
  state.configText = msg.config || '';
  state._freshConfigText = state.configText; // Cache for slider's fetch-before-save
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

onMessage('configUpdated', (msg, view) => {
  if (!msg.success) { renderError(t('chat.configUpdateFailed')); return; }
  // Re-fetch config from server so UI reflects what was actually saved
  sendWs({type: 'getConfig'});
});

// --- Model selection (input-bar picker) ---
// modelOptions now just feeds the input-bar picker's "add model" list; the
// /model slash command and the bubble chooser have been removed.
onMessage('modelOptions', (msg, view) => {
  const models = msg.models || [];
  state.allModelRefs = models.map(m => m.ref).filter(Boolean);
});

onMessage('sessionModelSet', (msg, view) => {
});

// --- Runtime model change (fallback kicked in) ---
onMessage('modelChanged', (msg, view) => {
  if (msg.newModel) {
    state.currentModel = msg.newModel;
  }
});

// --- Retry / fallback status ---
onMessage('retryStatus', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (view) renderRetryStatus(msg.message);
});

// --- Bridge user message (e.g. from external platform) ---
onMessage('bridgeUser', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  state.turnExpecting[sid] = true;
  saveMsg({type: 'user', text: msg.text}, sid);
  if (sid === state.activeSessionId) {
    renderUserBubble(msg.text, []);
    smartScroll();
  }
});

// --- Message recalled (backend confirms deletion) ---
// The optimistic DOM removal already happened in recallUserMessage() in utils.js.
// If the backend says it failed (e.g. AI already replied), we just reload the
// history to restore the message. If success, nothing more to do.
onMessage('messageRecalled', (msg) => {
  if (!msg.success) {
    // Recall failed — reload history to restore the removed row
    const sid = msg.sessionId;
    if (sid && sid === state.activeSessionId) {
      import('./persistence.js').then(({ restoreFromStorage }) => {
        if (activeView?.dom?.chat) activeView.dom.chat.innerHTML = '';
        restoreFromStorage();
      });
    }
  }
});

// --- Session busy state (backend authority) ---
onMessage('sessionBusy', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (msg.busy) {
    setBusy(msg.sessionId);
    // Server explicitly set busy — mark as expecting a turn (e.g. pendingEvents round)
    if (sid) state.turnExpecting[sid] = true;
  } else {
    clearBusy(msg.sessionId);
    // Defensive: if the 'done' event was lost but backend sent busy=false,
    // finish any active streaming bubble so the cursor disappears and the
    // duration badge is rendered.
    delete state.sessionPendingTools[sid];
    delete state.sessionPendingAiMessages[sid];
    delete state.pendingRestore[sid];
    if (state.sessionTexts[sid]) delete state.sessionTexts[sid];
  }
  // DOM-related cleanup only for active session.
  // Guard: if we received a textDelta/thinkingDelta very recently, the stream
  // is still active — a stale sessionBusy(false) (e.g. from idle-entry delay,
  // DeathWatcher broadcast, or crash-recovery race) must NOT finalize the bubble.
  // Only do the defensive cleanup if the stream has been silent for 15s+,
  // indicating the 'done' event was truly lost.
  if (view && !msg.busy && activeView.stream.currentAiBubble) {
    const sinceActivity = Date.now() - (state.lastStreamActivity || 0);
    if (sinceActivity < 15000) {
      console.warn('[sessionBusy(false)] Ignoring stale sessionBusy(false) during active streaming'
        + ` (${sinceActivity}ms since last delta)`);
    } else {
      const durationMs = consumeTurnDuration(sid);
      // IMPORTANT: do NOT use sessionModelInfo[sid]?.model here.
      // If the user switched models (e.g. deepseek → glm), sessionModelInfo still
      // holds the PREVIOUS turn's model. When sessionBusy(false) wins the race
      // against 'done' (backend acknowledges this can happen), using the cache
      // renders the wrong model name on the duration badge. Passing null means
      // the badge shows the phrase without a model — the correct model comes from
      // history when the user switches sessions.
      const data = finishAi(durationMs, null);
      if (data) saveMsg(data, sid);
      Object.keys(activeView.stream.agentBubbles).forEach(id => finishAgent(id));
      activeView.stream.agentBubbles = {};
      activeView.stream.activeAgentId = null;
      clearStatus();
    }
  }
});

// --- Task list ---
onMessage('taskListUpdate', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.sessionId) state.sessionTasks[msg.sessionId] = msg.tasks;
  if (view) {
    renderTaskList(msg.tasks, undefined, msg.sessionId);
  }
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
  const tasks = state.sessionBgTasks[activeView?.sessionId] || [];
  const listEl = activeView.dom.bgDropdownListEl;
  const dropdown = activeView.dom.bgDropdownEl;
  if (!listEl || !dropdown) return;
  listEl.innerHTML = '';
  // Show running AND cancelling tasks (cancelling tasks stay visible until backend confirms)
  const visible = tasks.filter(t => t.status === 'running' || t.status === 'cancelling');
  if (visible.length === 0) {
    dropdown.classList.add('hidden');
    stopBgTimer();
    return;
  }
  const now = Date.now();
  visible.forEach(task => {
    const row = document.createElement('div');
    row.className = 'bg-task-row';
    if (task.status === 'cancelling') row.classList.add('bg-task-cancelling');
    const info = document.createElement('div');
    info.className = 'bg-task-info';
    const desc = document.createElement('span');
    desc.className = 'bg-task-desc';
    desc.textContent = task.description || task.taskId;
    const meta = document.createElement('div');
    meta.className = 'bg-task-meta';
    const idSpan = document.createElement('span');
    idSpan.className = 'bg-task-id';
    idSpan.textContent = task.taskId;
    const durationSpan = document.createElement('span');
    durationSpan.className = 'bg-task-duration';
    durationSpan.dataset.taskId = task.taskId;
    if (task.startedAt) durationSpan.textContent = formatDuration(now - task.startedAt);

    // Heartbeat status indicator (skip for cancelling tasks — it'll be gone soon)
    const hb = task.heartbeat;
    let statusDot = null;
    let linesSpan = null;
    if (hb && task.status !== 'cancelling') {
      const idleClass = hb.idleMs > 600000 ? 'bg-status-stuck' : (hb.idleMs > 120000 ? 'bg-status-idle' : 'bg-status-active');
      statusDot = document.createElement('span');
      statusDot.className = `bg-task-status ${idleClass}`;
      statusDot.title = hb.alive ? (hb.idleMs > 600000 ? t('bg.stuck') : t('bg.running')) : t('bg.ended');
      linesSpan = document.createElement('span');
      linesSpan.className = 'bg-task-lines';
      linesSpan.textContent = t('bg.lines', { count: hb.outputLines });
    }

    meta.appendChild(idSpan);
    if (statusDot) meta.appendChild(statusDot);
    meta.appendChild(durationSpan);
    if (linesSpan) meta.appendChild(linesSpan);
    info.appendChild(desc);
    info.appendChild(meta);
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'bg-task-cancel';
    cancelBtn.textContent = t('bg.cancel');
    cancelBtn.onclick = (e) => {
      e.stopPropagation();
      // Immediate visual feedback — optimistically show cancelling state
      cancelBtn.disabled = true;
      cancelBtn.classList.add('cancelling');
      cancelBtn.textContent = task.status === 'cancelling' ? t('bg.cancelling') : '...';
      task.status = 'cancelling';
      sendWs({ type: 'cancelBackgroundJob', sessionId: activeView?.sessionId, jobId: task.taskId });
    };
    // If already cancelling, show the cancelling state
    if (task.status === 'cancelling') {
      cancelBtn.disabled = true;
      cancelBtn.classList.add('cancelling');
      cancelBtn.textContent = t('bg.cancelling');
    }
    row.appendChild(info);
    row.appendChild(cancelBtn);
    listEl.appendChild(row);
  });

  // Flow entries
  const flows = flowCanvas.getRunningFlows();
  flows.forEach(flow => {
    const row = document.createElement('div');
    row.className = 'bg-task-row';
    const info = document.createElement('div');
    info.className = 'bg-task-info';
    const desc = document.createElement('span');
    desc.className = 'bg-task-desc';
    desc.textContent = flow.flowName || flow.name;
    const meta = document.createElement('div');
    meta.className = 'bg-task-meta';
    const progress = document.createElement('span');
    progress.className = 'bg-task-id';
    progress.textContent = `${flow.done}/${flow.total} steps`;
    if (flow.running > 0) {
      const runningTag = document.createElement('span');
      runningTag.className = 'bg-task-status bg-status-active';
      runningTag.textContent = `${flow.running} running`;
      meta.appendChild(runningTag);
    }
    meta.appendChild(progress);
    info.appendChild(desc);
    info.appendChild(meta);
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'bg-task-cancel';
    cancelBtn.textContent = t('bg.cancel');
    cancelBtn.onclick = (e) => {
      e.stopPropagation();
      cancelBtn.disabled = true;
      cancelBtn.textContent = '...';
      sendWs({ type: 'cancelFlow', name: flow.name, sessionId: state.activeSessionId });
    };
    row.appendChild(info);
    row.appendChild(cancelBtn);
    listEl.appendChild(row);
  });
}

function startBgTimer() {
  if (_bgTimer) return;
  const v = activeView; // capture at start time — interval fires later when activeView may have drifted
  if (!v) return;
  _bgTimer = setInterval(() => {
    const dropdown = v.dom.bgDropdownEl;
    if (!dropdown || dropdown.classList.contains('hidden')) { stopBgTimer(); return; }
    const tasks = state.sessionBgTasks[v.sessionId] || [];
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
  const tasks = state.sessionBgTasks[activeView?.sessionId] || [];
  const now = Date.now();
  const active = tasks.filter(task =>
    task.status === 'running' || task.status === 'cancelling' ||
    (task.finishedAt && (now - task.finishedAt < 3000))
  );
  const flows = flowCanvas.getRunningFlows();
  const totalCount = active.length + flows.length;
  const el = activeView.dom.bgIndicatorEl;
  const countEl = activeView.dom.bgCountEl;
  const dropdown = activeView.dom.bgDropdownEl;
  if (!el || !countEl) return;
  if (totalCount > 0) {
    el.classList.remove('hidden');
    countEl.textContent = totalCount;
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

// Toggle dropdown on indicator click — register for ALL views
Object.values(chatViews).forEach(v => {
  const indicator = v.dom.bgIndicatorEl;
  const dropdown = v.dom.bgDropdownEl;
  if (!indicator || !dropdown) return;
  indicator.addEventListener('click', (e) => {
    e.stopPropagation();
    setActiveView(v);
    if (dropdown.classList.contains('hidden')) {
      renderBgDropdown();
      dropdown.classList.remove('hidden');
      startBgTimer();
    } else {
      dropdown.classList.add('hidden');
      stopBgTimer();
    }
  });
});

// Close dropdown when clicking outside — check all views' dropdowns
document.addEventListener('click', (e) => {
  Object.values(chatViews).forEach(v => {
    const dropdown = v.dom.bgDropdownEl;
    const indicator = v.dom.bgIndicatorEl;
    if (dropdown && !dropdown.contains(e.target) && indicator && !indicator.contains(e.target)) {
      dropdown.classList.add('hidden');
    }
  });
  stopBgTimer();
});

onMessage('backgroundTaskUpdate', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  if (!state.sessionBgTasks[sid]) state.sessionBgTasks[sid] = [];
  const tasks = state.sessionBgTasks[sid];
  const idx = tasks.findIndex(t => t.taskId === msg.taskId);
  if (idx >= 0) {
    tasks[idx].status = msg.status;
    if (msg.description && !tasks[idx].description) tasks[idx].description = msg.description;
    if (msg.status === 'completed' || msg.status === 'failed') {
      tasks[idx].finishedAt = Date.now();
    }
    if (msg.heartbeat) tasks[idx].heartbeat = msg.heartbeat;
  } else {
    tasks.push({
      taskId: msg.taskId,
      description: msg.description,
      status: msg.status,
      startedAt: msg.startedAt || Date.now(),
      heartbeat: msg.heartbeat || null,
      finishedAt: (msg.status === 'completed' || msg.status === 'failed') ? Date.now() : undefined
    });
  }
  // Remove completed/failed tasks after a brief delay so user sees the count update
  if (msg.status === 'completed' || msg.status === 'failed') {
    const taskId = msg.taskId;
    const v = activeView; // capture before setTimeout
    setTimeout(() => {
      const existing = state.sessionBgTasks[sid];
      if (existing) {
        state.sessionBgTasks[sid] = existing.filter(t => t.taskId !== taskId);
        if (v && v.mounted) {
          const saved = activeView;
          setActiveView(v);
          updateBgTasksUI();
          setActiveView(saved);
        }
      }
    }, 3000);
  }
  if (view) updateBgTasksUI();
});

// --- /ask command ---
onMessage('askTextDelta', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate ask text for ALL sessions
  if (sid) {
    if (!state.sessionAskBuffers[sid]) state.sessionAskBuffers[sid] = { question: '', answer: '' };
    state.sessionAskBuffers[sid].answer = (state.sessionAskBuffers[sid].answer || '') + msg.delta;
  }
  if (view) appendAskAnswer(msg.delta);
});
onMessage('askDone', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  const durationMs = consumeTurnDuration(sid) || msg.durationMs;
  const buf = sid ? state.sessionAskBuffers[sid] : null;
  if (view) {
    // Prefer buf.answer (complete accumulated text across tool boundaries)
    // over askAnswerText (only the last bubble segment)
    const answer = (buf ? buf.answer : '') || activeView.stream.askAnswerText || '';
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
  // Clean up thinking buffer so the subsequent 'done' event doesn't save
  // ask-mode thinking as a separate AI message (stray thinking fragment).
  if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
  if (activeView?.stream?.currentThinkingBubble) finishThinking();
});
onMessage('askError', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid) delete state.sessionAskBuffers[sid];
  if (view) renderAskError(msg.message);
});

// --- Skills ---
onMessage('skillList', (msg, view) => {
  state.skills = msg.skills || [];
  registerSkillCommands(state.skills);
});

onMessage('teamList', (msg) => {
  state.teams = msg.teams || [];
  state.flows = msg.flows || [];
  flowCanvas.refresh();
});

onMessage('skillError', (msg, view) => {
  if (view) renderSystemBubble(msg.message || 'Skill error');
});

onMessage('skillDeleted', (msg, view) => {
  if (view) {
    if (msg.success) {
      renderSystemBubble(t('slash.skillDeleted').replace('{skill}', msg.name));
    } else {
      renderSystemBubble(t('slash.skillDeleteFailed').replace('{skill}', msg.name));
    }
  }
});


// --- Memory ---
onMessage('memoryData', (msg, view) => handleMemoryData(msg));
onMessage('memoryChanged', (msg, view) => handleMemoryChanged(msg));
onMessage('memorySaved', () => { /* saved confirmation, no action needed */ });
onMessage('memoryStatus', (msg, view) => showMemoryButton());

// --- Rules ---
onMessage('rulesData', (msg, view) => handleRulesData(msg));
onMessage('rulesSaved', (msg, view) => handleRulesSaved(msg));
onMessage('rulesDeleted', (msg, view) => handleRulesDeleted(msg));

// --- Browse Result (path picker) ---
onMessage('browseResult', (msg, view) => handleBrowseResult(msg));

// --- Card design prompt ---
onMessage('cardDesignData', (msg, view) => { state.cardDesignPrompt = msg.content || ''; });
onMessage('cardDesignSaved', () => { /* saved confirmation */ });

// --- Update check ---
onMessage('updateCheckResult', (msg, view) => {
  const statusEl = document.getElementById('update-status');
  const actionEl = document.getElementById('update-action');
  if (!statusEl) return;
  if (msg.error) {
    statusEl.textContent = t('settings.updateError');
    return;
  }
  if (msg.hasUpdate) {
    statusEl.textContent = t('settings.updateAvailable', { version: msg.latestVersion });
    actionEl.style.display = 'block';
  } else {
    statusEl.textContent = t('settings.upToDate');
    actionEl.style.display = 'none';
  }
});

onMessage('updateStarted', () => {
  const statusEl = document.getElementById('update-status');
  if (statusEl) statusEl.textContent = t('settings.updating');
});

onMessage('updateCompleted', (msg, view) => {
  const btn = document.getElementById('btn-do-update');
  const statusEl = document.getElementById('update-status');
  if (btn) { btn.textContent = t('settings.checkUpdate'); btn.disabled = false; }
  if (statusEl) {
    if (msg.success) {
      statusEl.textContent = '✓ ' + t('settings.upToDate');
      document.getElementById('update-action').style.display = 'none';
    } else {
      statusEl.textContent = '✗ ' + (msg.error || t('settings.updateError'));
    }
  }
});


// ---------- 4. Cross-module wiring ----------
window.__showDeleteModal = showDeleteModal;
window.__showDeleteFolderModal = showDeleteFolderModal;

// Fork complete — auto-switch to the forked session
onMessage('forkComplete', (msg, view) => {
  renderSessionSidebar(state.sessions, msg.sessionId);
});

// ── Global ESC handler: close any visible modal/overlay/dropdown ──────
(function initGlobalEscHandler() {
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;

    // Full-screen overlay modals — click the overlay to trigger its close handler
    const overlays = [
      '#memory-overlay', '#card-design-overlay', '#rules-overlay',
      '#path-picker-overlay', '#modal-overlay', '#agent-overlay',
    ];
    for (const sel of overlays) {
      const el = document.querySelector(sel);
      if (el && getComputedStyle(el).display !== 'none') {
        el.click();
        e.preventDefault();
        e.stopPropagation();
        return;
      }
    }

    // Dropdown menus
    const bypassMenu = document.getElementById('bypass-menu');
    if (bypassMenu?.classList.contains('show')) {
      bypassMenu.classList.remove('show');
      e.preventDefault();
      return;
    }

    const delegate = document.getElementById('delegate-dropdown');
    if (delegate && !delegate.classList.contains('hidden')) {
      delegate.classList.add('hidden');
      e.preventDefault();
      return;
    }

    const bg = document.getElementById('bg-dropdown');
    if (bg && !bg.classList.contains('hidden')) {
      bg.classList.add('hidden');
      e.preventDefault();
      return;
    }

    const reminder = document.getElementById('reminder-panel');
    if (reminder?.classList.contains('open')) {
      reminder.classList.remove('open');
      e.preventDefault();
      return;
    }
  }, true); // capture phase — intercept before other handlers
})();

(function initHeaderResizeObserver() {
  const header = document.getElementById('header');
  if (!header || !window.ResizeObserver) return;
  let compactMode = false;
  let rafId = null;
  let suppressUntil = 0;
  const check = () => {
    if (rafId) cancelAnimationFrame(rafId);
    rafId = requestAnimationFrame(() => {
      rafId = null;
      if (performance.now() < suppressUntil) return;
      const center = header.querySelector('.header-center');
      if (!center) return;
      const cw = center.clientWidth;
      if (!compactMode && cw < 140) {
        compactMode = true;
        header.classList.add('header-compact');
        // Suppress callbacks for 300ms — the class toggle changes center
        // width, which would re-trigger ResizeObserver and bounce back.
        suppressUntil = performance.now() + 300;
      } else if (compactMode && cw > 300) {
        compactMode = false;
        header.classList.remove('header-compact');
        suppressUntil = performance.now() + 300;
      }
    });
  };
  const center = header.querySelector('.header-center');
  if (center) {
    const ro = new ResizeObserver(check);
    ro.observe(center);
  }
  check();
})();

// ---------- 5. Initialize UI modules ----------
applyLocaleToHtml(); // Apply locale to static HTML elements
initNavTabs();
initModals();
initRulesModal();
initPathPicker();
initInput(chatViews.primary);

// Keep the last chat message visible above the floating #input-area.
// #input-area (position:absolute; bottom:0) overlays #chat and has variable
// height (multi-line input, message queue, voice panel). All scroll code uses
// scrollTop = scrollHeight, so we grow #chat's padding-bottom to match the
// input area's height — then scrolling to the bottom lands the last message
// above the input bar instead of behind it.
(() => {
  const inputArea = document.getElementById('input-area');
  const chat = document.getElementById('chat');
  if (!inputArea || !chat || !window.ResizeObserver) return;
  const updateChatPadding = () => {
    // offsetHeight covers all in-flow children (queue-bar + input-bar + the
    // area's own 10px bottom padding). Absolutely-positioned children
    // (voice-overlay, slash-dropdown) are excluded — they overlay the chat
    // transiently above the bar, so they must not inflate the padding.
    const inputHeight = inputArea.offsetHeight;
    // +2px: minimal breathing room so the last message sits flush above the
    // input bar without touching the glass edge.
    chat.style.setProperty('padding-bottom', `${inputHeight + 2}px`, 'important');
    // Only re-scroll if the user is already near the bottom — don't yank
    // them away from history they're reading.
    const nearBottom = chat.scrollHeight - chat.scrollTop - chat.clientHeight < 100;
    if (nearBottom) chat.scrollTop = chat.scrollHeight;
  };
  const ro = new ResizeObserver(updateChatPadding);
  ro.observe(inputArea);
  updateChatPadding();
})();
initMemory();
initExplorer();
initCanvas();
initColResizers();

// Remove UI initialization lock — all layout setup is done.
// Double-rAF ensures the browser has painted at least one frame with
// the final layout before re-enabling transitions.
requestAnimationFrame(() => {
  requestAnimationFrame(() => {
    document.documentElement.classList.remove('ui-init');
  });
});

initActivityBar();
document.getElementById('canvas-toggle-btn')?.addEventListener('click', () => {
  // Toggle Canvas open/close — closing does NOT clear tabs.
  if (document.body.classList.contains('canvas-open')) {
    closeCanvas();
  } else {
    openCanvas();
  }
});
document.getElementById('teams-btn')?.addEventListener('click', () => flowCanvas.openTeams());
document.getElementById('flows-btn')?.addEventListener('click', () => flowCanvas.openFlows());
// Restore queued messages from localStorage (survives browser refresh)
restoreQueue();
// Restore Canvas tabs from localStorage (survives browser refresh).
// If no saved tabs (e.g. cache cleared), auto-open Teams panel.
if (!restoreTabs()) {
  flowCanvas.openTeams();
}
// Auto-restore is triggered from sessionList handler (needs activeSessionId)
initScheduledTask();
initDaemons();
initNeblink();
checkPairingRedirect();
initDropbox();
planMode.init();

// Preload Monaco Editor during idle time so first file open is instant.
// Monaco (~2MB from CDN) is the main cause of first-open lag.
const _idleCb = window.requestIdleCallback || ((fn) => setTimeout(fn, 2000));
_idleCb(() => import('./monacoEditor.js').then(({ preloadMonaco }) => preloadMonaco().catch(() => {})));

// ---------- Click agent name in header → open agent config modal ----------
document.getElementById('session-name')?.addEventListener('click', () => {
  const active = state.sessions.find(s => s.id === state.activeSessionId);
  const agentName = active?.agentName || 'Nebula';
  // Show modal immediately with cached data, then fetch fresh system prompt
  showAgentModal(agentName, '');
  sendWs({ type: 'getAgentSystemPrompt', name: agentName });
});

// ---------- Plan mode event handlers ----------
onMessage('planStart', (msg, view) => planMode.onPlanStart(msg, view));
onMessage('planReady', (msg, view) => planMode.onPlanReady(msg, view));
onMessage('planEnd', (msg, view) => planMode.onPlanEnd(msg, view));
onMessage('_planAgent', (msg) => planMode.onPlanAgentEvent(msg));

// ---------- Safety mode dropdown ----------
(function initSafetyToggle() {
  const TITLES = {
    'confirm-edits': '安全模式：确认编辑 (Write/Edit/Bash 需确认)',
    'auto-edits': '安全模式：编辑放行 (仅 Bash 需确认)',
    'auto-all': '安全模式：全部放行 (无需确认)',
  };

  state.updateSafetyToggle = function(view) {
    const v = view || activeView;
    if (!v || !v.sessionId) return;
    const mode = state.safetyModes[v.sessionId] || 'confirm-edits';
    const btn = document.getElementById('bypass-toggle');
    if (btn) {
      btn.setAttribute('data-mode', mode);
      btn.title = TITLES[mode] || TITLES['confirm-edits'];
    }
    // Highlight active option in dropdown
    document.querySelectorAll('#bypass-menu button').forEach(b => {
      b.classList.toggle('active', b.dataset.mode === mode);
    });
  };

  state.updateBypassToggle = state.updateSafetyToggle;

  const btn = document.getElementById('bypass-toggle');
  const menu = document.getElementById('bypass-menu');
  if (btn && menu) {
    const v = chatViews.primary;
    // Toggle dropdown on icon click
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      setActiveView(v);
      menu.classList.toggle('show');
    });
    // Select mode from dropdown
    menu.querySelectorAll('button').forEach(item => {
      item.addEventListener('click', (e) => {
        e.stopPropagation();
        const mode = item.dataset.mode;
        if (!v.sessionId) return;
        state.safetyModes[v.sessionId] = mode;
        if (mode === 'auto-all') {
          state.bypassSessions.add(v.sessionId);
        } else {
          state.bypassSessions.delete(v.sessionId);
        }
        state.updateSafetyToggle(v);
        sendWs({ type: 'setSafetyMode', sessionId: v.sessionId, safetyMode: mode });
        menu.classList.remove('show');
      });
    });
    // Close dropdown on outside click
    document.addEventListener('mousedown', (e) => {
      if (!menu.contains(e.target) && e.target !== btn) {
        menu.classList.remove('show');
      }
    }, true);
  }
})();

// Sidebar collapse toggle
(function initSidebarToggle() {
  const LS_KEY = 'nebflow_sidebar_collapsed';
  const btn = document.getElementById('sidebar-toggle');
  if (!btn) return;
  const collapsed = localStorage.getItem(LS_KEY) === 'true';
  if (collapsed) document.body.classList.add('sidebar-collapsed');
  btn.addEventListener('click', () => {
    document.body.classList.toggle('sidebar-collapsed');
    localStorage.setItem(LS_KEY, document.body.classList.contains('sidebar-collapsed'));
  });
  // Keyboard shortcut: Cmd/Ctrl+B
  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key === 'b') {
      const tag = document.activeElement?.tagName;
      if (tag === 'TEXTAREA' || tag === 'INPUT') return;
      e.preventDefault();
      document.body.classList.toggle('sidebar-collapsed');
      localStorage.setItem(LS_KEY, document.body.classList.contains('sidebar-collapsed'));
    }
  });
})();

// ---------- Scrollbar: fixed slim width ----------
// Previously had an auto-slim mechanism that toggled scrollbar width between
// 6px and 10px on scroll/idle. This caused bubbles to re-flow (jump) because
// the content area width changed by 4px each time. Fixed width eliminates this.

// Re-apply locale when language changes
window.addEventListener('locale-changed', () => {
  applyLocaleToHtml();
  // Force session sidebar rebuild by invalidating fingerprint cache
  const sl = state.dom.sessionList;
  if (sl) sl._lastFingerprint = null;
  if (state.sessions.length > 0) {
    renderSessionSidebar(state.sessions, state.activeSessionId);
  }
  // Re-render agent list to update localized labels
  if (state.agentsData.length > 0) {
    renderAgentList();
  }
  // Update input placeholders (may have been overwritten by skill/ask mode)
  if (chatViews.primary?.dom?.input) {
    const v = chatViews.primary;
    setActiveView(v);
    if (!v.skillMode && !v.stream.askMode) {
      v.dom.input.placeholder = t('input.placeholder');
    }
  }
});
// New Folder button
document.getElementById('new-folder-btn')?.addEventListener('click', () => createNewFolder(getCurrentFolderId()));


// ---------- Reconnect: refresh active session history ----------
// After OS sleep/wake or network drop, the agent may have produced output
// while the frontend was disconnected. On reconnect, re-fetch the active
// session's history so the user sees the latest state.
onReconnect(() => {
  const sid = state.activeSessionId;
  if (sid) {
    const view = findViewBySessionId(sid);
    if (view) {
      view.pagination.pendingInitialLoad = true;
      sendWs({ type: 'getHistory', sessionId: sid, limit: 50 });
    }
    // Re-fetch explorer tree — the initial load may have been dropped
    // if WS wasn't open when nebflow-session-change fired.
    refreshExplorer(sid);
    // Re-fetch task list for the same reason
    sendWs({ type: 'getTaskList', sessionId: sid });
  }
  // Sync background task state — completion events may have been missed
  sendWs({ type: 'getActiveBgTasks' });
});

// ---------- Reconnect: sync background tasks ----------
// Backend responds with active tasks grouped by sessionId.
// We remove any locally-tracked tasks that are no longer active on the backend
// (they completed during the disconnect), and keep tasks the backend confirms.
onMessage('activeBgTasks', (msg) => {
  const backendTasks = msg.tasks || {};
  // Remove locally-tracked tasks that the backend no longer knows about
  for (const sid of Object.keys(state.sessionBgTasks)) {
    const backendSessionTasks = backendTasks[sid] || [];
    const backendIds = new Set(backendSessionTasks.map(t => t.taskId));
    const before = state.sessionBgTasks[sid].length;
    state.sessionBgTasks[sid] = state.sessionBgTasks[sid].filter(t => backendIds.has(t.taskId));
    // If we removed tasks, also clean up finishedAt entries
    if (state.sessionBgTasks[sid].length < before) {
      const removed = before - state.sessionBgTasks[sid].length;
      // Silently clean — no UI update needed since these were already "running"
      // indicators that will disappear on next render
    }
    if (state.sessionBgTasks[sid].length === 0) {
      delete state.sessionBgTasks[sid];
    }
  }
  // Also clean stale delegate indicators — any session that has delegates
  // tracked locally but no longer has an active agent on the backend
  // can't be reliably detected here (delegates use actor system, not BgTaskRegistry).
  // The agentDone fix (global sessionDelegates) already handles missed events.
  // Refresh the UI for the active view
  if (activeView) updateBgTasksUI();
});

// Scroll listener (primary window)
const _primChat = chatViews.primary.dom.chat;
_primChat.addEventListener('scroll', () => {
  const pv = chatViews.primary;
  pv.stream.scrollSnapped = _primChat.scrollTop + _primChat.clientHeight >= _primChat.scrollHeight - 40;
  // Scroll-to-top: load older messages
  if (_primChat.scrollTop < 100 && pv?.pagination?.hasMore && !pv?.pagination?.loading && pv?.pagination?.offset > 0) {
    pv.pagination.loading = true;
    setActiveView(pv);
    showHistoryLoader();
    sendWs({ type: 'getHistory', sessionId: state.activeSessionId, limit: 50, beforeIndex: pv.pagination.offset });
  }
}, { passive: true });

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
emergencyCacheCleanup(); // Purge bloated localStorage cache before any writes
connect();
chatViews.primary.dom.input.focus();

// Cloud STT — no model preloading needed.
