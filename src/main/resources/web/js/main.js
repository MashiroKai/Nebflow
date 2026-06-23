import state from './state.js';
import { LS_SESSIONS_KEY, LS_MODEL_INFO_KEY } from './state.js';
import { initSpinner, initMarkdown, smartScroll, renderMarkdownWithMath } from './utils.js';
import { connect, onMessage, sendWs } from './ws.js';
import {
  setBusy, clearBusy, clearStatus,
  renderUserBubble, appendAiText, finishAi,
  appendAgentText, finishAgent, getAgentColor,
  renderTool, renderToolPending, renderError, renderTimeoutNotice,
  renderSystemBubble, renderRetryStatus, clearRetryStatus,
  showOptions, renderAskUser, renderPermissionPrompt,
  renderAttachmentPreview,
  appendAskAnswer, finishAskAnswer, renderAskError,
  appendThinkingDelta, finishThinking
} from './chat.js';
import {
  initNavTabs, renderSessionSidebar, renderAgentList, renderSettings,
  switchSession, deleteSession, formatSessionTime, setSessionAttention,
  initHeaderModelInfo,
  persistUnread, createNewFolder,
  resetChatForActiveSession
} from './sidebar.js';
import {
  showNewSessionModal, hideModals, confirmNewSession,
  showDeleteModal, confirmDeleteSession,
  showDeleteFolderModal,
  showAgentModal, hideAgentModal, initModals,
  startInlineNewSession
} from './modal.js';
import { send, handleSlash, addFileAttachment, initInput, injectUserMessage, enterAskMode, cancelAskMode, registerSkillCommands } from './input.js';
import { saveMsg, loadMsgs, restoreFromStorage, restoreFromBackendHistory, migrateLegacyIfNeeded } from './persistence.js';
import { renderTaskList } from './taskList.js';
import { renderWithRegistry } from './cardRegistry.js';
import { escapeHtml } from './utils.js';
import { showMemoryButton, handleMemoryData, initMemory, clearMemoryCache } from './memory.js';
import { handleRulesData, handleRulesSaved, handleRulesDeleted, handleBrowseResult, initRulesModal, initPathPicker } from './sidebar.js';
import { t, getLocale } from './i18n.js';
import { applyLocaleToHtml } from './i18n.js';
import { initScheduledTask, refreshScheduledTasks } from './scheduled-task.js';
import { initSecondaryChat } from './secondary-chat.js';
import { initMesh } from './mesh.js';
import { formatLiveDuration } from './chat.js';

// Randomized cosmic thinking bubble text
const THINKING_VARIANTS = 6; // chat.thinking.0 through .5
let _lastThinkingIdx = -1;
function randomThinkingText() {
  let idx;
  do { idx = Math.floor(Math.random() * THINKING_VARIANTS); } while (idx === _lastThinkingIdx && THINKING_VARIANTS > 1);
  _lastThinkingIdx = idx;
  return t('chat.thinking.' + idx);
}

// ---------- Live thinking timer ----------
let _thinkingTimerInterval = null;
let _thinkingTimerEl = null;

function startThinkingTimer() {
  stopThinkingTimer(); // clean any previous
  const sid = state.activeSessionId;
  const startTime = state.turnStartTimes[sid];
  if (!startTime) return;

  // Insert timer element into the existing thinking placeholder
  const placeholder = state.dom.chat.querySelector('.thinking-placeholder');
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
  if (!msg.sessionId || msg.sessionId === state.activeSessionId) return true;
  // When ws.js sets _secondaryActive, the swap is already done — treat as active
  if (state._secondaryActive) return true;
  return false;
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

onMessage('thinkingDelta', (msg) => {
  const sid = msg.sessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate thinking text for ALL sessions
  if (sid) state.sessionThinkingBuffers[sid] = (state.sessionThinkingBuffers[sid] || '') + msg.delta;
  resetStreamTimeout(sid);
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (isActive(msg)) {
    stopThinkingTimer();
    // Remove the generic thinking placeholder if it exists
    const existing = state.dom.chat.querySelector('.thinking-placeholder');
    if (existing) {
      const row = existing.closest('.row');
      if (row) row.remove();
      if (state.currentAiBubble === existing) state.currentAiBubble = null;
    }
    // Guard: only create thinking bubbles when a turn is expected (user sent
    // message or server explicitly started a new round). Prevents stray thinking
    // bubbles from late-arriving thinkingDelta after done has been processed.
    if (!state.turnExpecting[sid] && !state.currentThinkingBubble && !state.currentAiBubble) {
      return;
    }
    appendThinkingDelta(msg.delta);
  }
});

onMessage('textDelta', (msg) => {
  const sid = msg.sessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate text for ALL sessions
  if (sid) state.sessionTexts[sid] = (state.sessionTexts[sid] || '') + msg.delta;
  resetStreamTimeout(sid);
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (isActive(msg)) {
    stopThinkingTimer();
    clearRetryStatus();
    // Finish thinking bubble before first text delta
    if (state.currentThinkingBubble) finishThinking();
    appendAiText(msg.delta);
  }
});

onMessage('textDone', (msg) => {
  const sid = msg.sessionId;
  if (isActive(msg)) {
    stopThinkingTimer();
    if (state.currentThinkingBubble) finishThinking();
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

onMessage('thinking', (msg) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  resetStreamTimeout(sid);
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (isActive(msg)) {
    // Guard against duplicate thinking bubbles: check both state ref and DOM.
    const existing = state.dom.chat.querySelector('.thinking-placeholder');
    if (!state.currentAiBubble && !existing) {
      const { chat } = state.dom;
      const row = document.createElement('div');
      row.className = 'row ai';
      state.currentAiBubble = document.createElement('div');
      state.currentAiBubble.className = 'bubble ai thinking-placeholder';
      row.appendChild(state.currentAiBubble);
      chat.appendChild(row);
      smartScroll();
      // Start live timer after a brief moment so DOM is settled
      requestAnimationFrame(() => startThinkingTimer());
    }
  }
});

onMessage('toolCallDetected', (msg) => {
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
  if (isActive(msg)) {
    clearRetryStatus();
    if (state.currentThinkingBubble) finishThinking();
    // Flush thinking buffer for active sessions to prevent cross-turn concatenation.
    // finishAi() only returns text, so we read thinking separately from the buffer.
    const tThinking = state.sessionThinkingBuffers[sid] || '';
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
    renderToolPending(msg.name, msg.sessionId);
  }
});

onMessage('toolStart', (msg) => {
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

  if (isActive(msg)) {
    if (!state.busySessionIds.has(msg.sessionId || state.activeSessionId)) setBusy(msg.sessionId || state.activeSessionId);
    clearRetryStatus();
    // Finish the current AI bubble so that text after tool execution goes into a new bubble
    if (state.currentThinkingBubble) finishThinking();
    // Flush thinking buffer for active sessions (same as toolCallDetected)
    const tThinking = state.sessionThinkingBuffers[toolStartSid] || '';
    if (toolStartSid && state.sessionThinkingBuffers[toolStartSid]) delete state.sessionThinkingBuffers[toolStartSid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
    renderToolPending(msg.label, msg.sessionId);
  }
});

onMessage('toolEnd', (msg) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.label && msg.label.startsWith('AskUser')) return;
  const toolEndSid = msg.sessionId;
  if (toolEndSid) delete state.sessionPendingTools[toolEndSid];
  if (isActive(msg)) {
    const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input, msg.sessionId);
    if (data) saveMsg(data, msg.sessionId);
  } else {
    saveMsg({type: 'tool', label: msg.label, summary: msg.summary, content: msg.content, isError: msg.isError, input: msg.input}, msg.sessionId);
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

  const thresholdPct = Math.round((info.compactThreshold || state.COMPACT_THRESHOLD) * 100);
  const tooltip = info.inputTokens != null
    ? `${formatTokens(info.inputTokens)} / ${formatTokens(info.contextWindow)} tokens (${pct}%)`
    : `${formatTokens(info.contextWindow)} context window`;

  el.innerHTML = `
    <div class="ctx-bar-wrap" title="${tooltip}">
      <div class="ctx-bar-track">
        <div class="ctx-bar-fill" style="width:${pct}%;background:${barColor};"></div>
        <div class="ctx-bar-threshold" style="left:${thresholdPct}%;"></div>
        <div class="ctx-bar-threshold-label" style="left:${thresholdPct}%;">${thresholdPct}%</div>
      </div>
      <span class="ctx-bar-label">${formatTokens(info.inputTokens)}/${formatTokens(info.contextWindow)}</span>
    </div>
  `;
  el.style.display = 'inline-flex';

  // Attach drag handler to the outer wrap for a larger hit area
  const wrap = el.querySelector('.ctx-bar-wrap');
  if (!wrap) return;
  setupThresholdDrag(wrap, info, sid);
}
state.updateHeaderModelInfo = updateHeaderModelInfo;

/** Set up drag-to-adjust on the threshold line. */
function setupThresholdDrag(wrap, info, sid) {
  const track = wrap.querySelector('.ctx-bar-track');
  const thresholdEl = track?.querySelector('.ctx-bar-threshold');
  const labelEl = track?.querySelector('.ctx-bar-threshold-label');
  if (!track || !thresholdEl) return;

  const onStart = (e) => {
    e.preventDefault();
    const rect = track.getBoundingClientRect();
    track.classList.add('dragging');

    const movePct = (ev) => {
      const pct = Math.max(5, Math.min(95, ((ev.clientX - rect.left) / rect.width) * 100));
      thresholdEl.style.left = pct + '%';
      if (labelEl) {
        labelEl.style.left = pct + '%';
        labelEl.textContent = Math.round(pct) + '%';
        labelEl.classList.add('visible');
      }
    };

    // Set initial position from click
    const initPct = Math.max(5, Math.min(95, ((e.clientX - rect.left) / rect.width) * 100));
    thresholdEl.style.left = initPct + '%';
    if (labelEl) {
      labelEl.style.left = initPct + '%';
      labelEl.textContent = Math.round(initPct) + '%';
      labelEl.classList.add('visible');
    }

    const onMove = (ev) => movePct(ev);

    const onUp = (ev) => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      track.classList.remove('dragging');
      if (labelEl) labelEl.classList.remove('visible');

      const finalPct = Math.max(5, Math.min(95, ((ev.clientX - rect.left) / rect.width) * 100));

      // Compute new bufferRatio: bufferRatio = 1.0 - thresholdPosition
      const newBufferRatio = Math.round((1.0 - finalPct / 100) * 100) / 100;
      const clampedRatio = Math.max(0.01, Math.min(0.50, newBufferRatio));

      // Persist to nebflow.json
      // Update in-memory threshold for immediate display
      const newThreshold = 1.0 - clampedRatio;
      if (state.sessionModelInfo[sid]) {
        state.sessionModelInfo[sid].compactThreshold = newThreshold;
      }

      // Debounce save — fetch fresh config first to avoid overwriting server-side changes
      clearTimeout(state._thresholdSaveTimer);
      state._thresholdSaveTimer = setTimeout(() => {
        // Use a one-time listener to get the latest config from server before sending
        const freshConfig = state._freshConfigText || state.configText;
        try {
          const parsed = JSON.parse(freshConfig);
          if (!parsed.compact) parsed.compact = {};
          parsed.compact.bufferRatio = clampedRatio;
          const json = JSON.stringify(parsed, null, 2);
          state.parsedConfig = parsed;
          state.configText = json;
          sendWs({type: 'updateConfig', config: json});
        } catch {
          // Fallback: send with in-memory config (mergeConfig will preserve absent keys)
          if (!state.parsedConfig) state.parsedConfig = {};
          if (!state.parsedConfig.compact) state.parsedConfig.compact = {};
          state.parsedConfig.compact.bufferRatio = clampedRatio;
          const json = JSON.stringify(state.parsedConfig, null, 2);
          state.configText = json;
          sendWs({type: 'updateConfig', config: json});
        }
      }, 300);
      // Request fresh config from server (response will update state._freshConfigText)
      sendWs({type: 'getConfig'});
    };

    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  wrap.addEventListener('mousedown', onStart);
}

// Real-time usage update after each LLM round (multi-round tool calling)
onMessage('usageUpdate', (msg) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && msg.inputTokens != null && msg.contextWindow) {
    state.sessionModelInfo[sid] = {
      model: state.sessionModelInfo[sid]?.model,
      contextWindow: msg.contextWindow,
      inputTokens: msg.inputTokens,
      compactThreshold: msg.compactThreshold
    };
    if (sid === state.activeSessionId) updateHeaderModelInfo();
  }
});

onMessage('done', (msg) => {
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
    if (isActive(msg)) updateHeaderModelInfo();
  }
  // Flush any remaining buffered text/thinking for this session
  if (msg.sessionId) {
    if (!isActive(msg)) {
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
      // it's consumed by finishThinking() + the fallback read in the isActive(msg) block below.
    }
  }
  if (isActive(msg)) {
    stopThinkingTimer();
    // Clean up per-session pending tool card for this session
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    // Clean up pending segments accumulated at tool boundaries
    if (sid && state.sessionPendingAiMessages[sid]) delete state.sessionPendingAiMessages[sid];
    // Clean up pendingRestore (previous turn's data no longer needed)
    if (sid && state.pendingRestore[sid]) delete state.pendingRestore[sid];
    // Finish thinking bubble if still streaming
    const thinkingText = finishThinking() || (sid ? state.sessionThinkingBuffers[sid] || '' : '');
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    // Clear thinkingText unconditionally — when appendThinkingDelta skipped DOM
    // creation (second+ thinking block after text), finishThinking() had no bubble
    // to clear and state.thinkingText retains skipped content.
    state.thinkingText = '';
    const data = finishAi(durationMs, msg.model);
    if (data) {
      data.thinking = thinkingText || undefined;
      saveMsg(data, msg.sessionId);
    } else if (thinkingText) {
      // Thinking-only response (no text): keep thinking bubble expanded
      const thinkBubble = state.dom.chat.querySelector('.thinking-bubble');
      if (thinkBubble) {
        const content = thinkBubble.querySelector('.thinking-content');
        const label = thinkBubble.querySelector('.thinking-label');
        if (content) content.style.display = '';
        if (label) label.classList.add('expanded');
      }
      // Save without text field so restoreFromStorage doesn't render an empty bubble
      saveMsg({ type: 'ai', thinking: thinkingText, durationMs, model: msg.model }, msg.sessionId);
    }
    Object.keys(state.agentBubbles).forEach(id => finishAgent(id));
    state.agentBubbles = {};
    state.activeAgentId = null;
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

// roundComplete: backend signals the current round's text is finalized but a new
// LLM round is about to start (e.g. pendingEvents injection). Finalize the
// current AI bubble without ending the turn (no done/sessionBusy(false)).
onMessage('roundComplete', (msg) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (isActive(msg)) {
    if (state.currentThinkingBubble) finishThinking();
    const tThinking = state.sessionThinkingBuffers[sid] || '';
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
  }
});

onMessage('error', (msg) => {
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
  state.historyLoading = false;
  hideHistoryLoader();
  if (isActive(msg)) {
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

onMessage('interrupted', (msg) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  if (sid) delete state.pendingRestore[sid];
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) delete state.turnExpecting[sid];
  // Defensive: clear attention on interrupt
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) state.answeredPermissions.delete(sid);
  if (isActive(msg)) {
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishThinking();
    finishAi();
    clearStatus();
  }
});

onMessage('timeout', (msg) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) state.answeredPermissions.delete(sid);
  if (isActive(msg)) {
    finishThinking();
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
  if (sid) delete state.pendingRestore[sid];
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) delete state.turnExpecting[sid];
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) state.answeredPermissions.delete(sid);
  if (isActive(msg)) {
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
onMessage('askUser', (msg) => {
  const sid = msg.sessionId;
  if (sid) setSessionAttention(sid, true);
  if (isActive(msg)) {
    // Defensive: finalize any in-flight AI bubble before rendering the question.
    // Normally roundComplete (sent before askUser by the backend) handles this,
    // but guard against edge cases where the bubble is still pending.
    if (state.currentAiBubble) {
      const prevData = finishAi();
      if (prevData) saveMsg(prevData, sid);
    }
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
  // Clear stale "answered" tracking: a new permission request means any
  // previous answer in this session (same turn) is no longer relevant.
  // Without this, a second permission in the same turn would be stuck as
  // disabled because answeredPermissions still holds this sid.
  if (sid) state.answeredPermissions.delete(sid);
  if (isActive(msg)) {
    renderPermissionPrompt(msg.toolName, msg.summary, msg.input, msg.sessionId, msg.dangerLevel);
  } else if (sid) {
    // Non-active session: persist so it can be restored on session switch
    saveMsg({ type: 'askPermission', toolName: msg.toolName, summary: msg.summary, input: msg.input, dangerLevel: msg.dangerLevel }, sid);
  }
});

// --- Session list (global) ---
let restoredSessionId = null;
onMessage('sessionList', (msg) => {
  // Build sessionAgentMap from ALL sessions
  const allSessions = msg.sessions || [];
  const allFolders = msg.folders || [];
  allSessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || 'Nebula'; });

  // Unified list — show all sessions regardless of agent
  let sessionsToShow = allSessions;
  state.folders = allFolders;
  state.foldersWithRules = new Set(msg.foldersWithRules || []);

  // Use activeId as-is (unified list — no agent filtering)
  let activeId = msg.activeId;

  renderSessionSidebar(sessionsToShow, activeId);
  initHeaderModelInfo();
  // Mark the initial session as restored — getHistory is already sent by
  // resetChatForActiveSession (called inside renderSessionSidebar when activeId changes).
  if (!restoredSessionId && activeId) {
    restoredSessionId = activeId;
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
  loader.innerHTML = '<div class="history-spinner"></div><span>' + t('chat.loading') + '</span>';
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
  end.textContent = t('chat.noMoreMessages');
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
  if (sid !== state.activeSessionId && !state._secondaryActive) return;
  state.historyLoading = false;
  hideHistoryLoader();

  // Use explicit flag instead of historyOffset === 0 to prevent double-clear.
  // resetChatForActiveSession sets pendingInitialLoad=true; we clear it here on first response.
  // Subsequent historyPage responses (from duplicate getHistory) see pendingInitialLoad=false
  // and go to the prepend path instead of clearing the DOM again.
  const isInitialLoad = state.pendingInitialLoad;
  if (isInitialLoad) {
    state.pendingInitialLoad = false;
    // Initial load or full refresh — replace
    state.dom.chat.innerHTML = '';
    // Reset sessionToolCards — innerHTML clear above removes all tool pending
    // card DOM nodes, but renderToolPending uses sessionToolCards[sid] as an
    // existence check. A stale DOM reference causes it to skip card creation
    // (update-in-place on a detached node), leaving no spinner visible.
    Object.keys(state.sessionToolCards).forEach(sid => delete state.sessionToolCards[sid]);
    // Clear history indicators before rendering
    clearHistoryIndicators();
    state.historyOffset = msg.offset;
    state.historyTotal = msg.total;
    state.historyHasMore = msg.hasMore;
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
        state.thinkingText = thinkBuf;
        const hasText = !!txtBuf;
        const done = hasText || !isStillBusy;
        const chat = state.dom.chat;
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
        content.innerHTML = renderMarkdownWithMath(state.thinkingText) + (!done ? '<span class="cursor"></span>' : '');
        bubble.appendChild(label);
        bubble.appendChild(content);
        row.appendChild(bubble);
        chat.appendChild(row);
        state.currentThinkingBubble = done ? null : bubble;
      }

      // Restore text bubble
      if (txtBuf) {
        state.aiText = txtBuf;
        const chat = state.dom.chat;
        const row = document.createElement('div');
        row.className = 'row ai';
        state.currentAiBubble = document.createElement('div');
        state.currentAiBubble.className = 'bubble ai';
        state.currentAiBubble.innerHTML = renderMarkdownWithMath(state.aiText) + (isStillBusy ? '<span class="cursor"></span>' : '');
        row.appendChild(state.currentAiBubble);
        chat.appendChild(row);
        if (!isStillBusy) {
          state.currentAiBubble = null;
          state.aiText = '';
          delete state.pendingRestore[sid];
        }
      }

      const askBuf = state.sessionAskBuffers[sid];
      if (isStillBusy && askBuf && askBuf.answer) {
        state.askAnswerText = askBuf.answer;
        const chat = state.dom.chat;
        const row = document.createElement('div');
        state.currentAskBubble = document.createElement('div');
        state.currentAskBubble.className = 'bubble ai';
        const label = document.createElement('div');
        label.className = 'ask-label';
        label.textContent = t('chat.askLabel');
        const content = document.createElement('div');
        content.innerHTML = renderMarkdownWithMath(state.askAnswerText) + '<span class="cursor"></span>';
        state.currentAskBubble.appendChild(label);
        state.currentAskBubble.appendChild(content);
        row.appendChild(state.currentAskBubble);
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
      state.dom.chat.querySelectorAll('.row.ai').forEach(row => {
        if (row.querySelector('.option-box')) row.remove();
      });
      renderAskUser(lastHistMsg.items, sid);
    }

    // Re-create interactive AskPermission if the last history message is an unanswered askPermission.
    // Same logic as AskUser — if already answered, there would be subsequent tool messages.
    // Guard: skip if the user has already answered this permission in the current session
    // (tool is still executing, askPermission is still the last history entry).
    if (isAskPermissionPending && !state.answeredPermissions.has(sid)) {
      // Remove the disabled askPermission row (restored by restoreFromBackendHistory).
      state.dom.chat.querySelectorAll('.row.ai').forEach(row => {
        if (row.querySelector('.permission-pending-box')) row.remove();
      });
      renderPermissionPrompt(lastHistMsg.toolName, lastHistMsg.summary, lastHistMsg.input, sid, lastHistMsg.dangerLevel);
    }

    if (!state.historyHasMore && msg.messages.length > 0) showHistoryEnd();

    // Final scroll-to-bottom: after all rendering (history + streaming bubbles + pending tools)
    // is complete, ensure the viewport shows the latest content.
    // Uses rAF to avoid layout thrashing — fires after any pending style calculations.
    requestAnimationFrame(() => {
      const chat = state.dom.chat;
      if (state.scrollSnapped || chat.scrollHeight - chat.scrollTop - chat.clientHeight < 60) {
        chat.scrollTop = chat.scrollHeight;
        state.scrollSnapped = true;
      }
    });

  } else {
    // Scroll-up pagination — prepend older messages
    // Guard against duplicate historyPage responses (e.g. from double getHistory on initial load):
    // if the response offset is >= what we already have, skip it to avoid duplicates.
    if (msg.offset >= state.historyOffset && state.historyOffset > 0) {
      // Skipping duplicate response
      return;
    }
    const chat = state.dom.chat;
    const prevScrollHeight = chat.scrollHeight;
    const prevScrollTop = chat.scrollTop;
    // Insert before first child
    const fragment = document.createDocumentFragment();
    const tempDiv = document.createElement('div');
    const origChat = state.dom.chat;
    state.dom.chat = tempDiv;
    restoreFromBackendHistory(msg.messages, { scrollToBottom: false });
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
// Sub-agent activity shows a header indicator (like Bash background tasks).
// No tool cards or text rendered in the chat — the parent's Delegate tool
// card (spinner → result) is the only chat-level feedback.
// Click the indicator to see a dropdown with per-agent status.

function updateDelegateIndicator() {
  const el = document.getElementById('delegate-indicator');
  if (!el) return;
  const count = Object.keys(state.activeSubAgents || {}).length;
  if (count > 0) {
    el.classList.remove('hidden');
    el.querySelector('.delegate-count').textContent = count;
  } else {
    el.classList.add('hidden');
    // Also hide dropdown
    const dropdown = document.getElementById('delegate-dropdown');
    if (dropdown) dropdown.classList.add('hidden');
  }
  renderDelegateDropdown();
}

function renderDelegateDropdown() {
  const listEl = document.querySelector('#delegate-dropdown .bg-dropdown-list');
  if (!listEl) return;
  const agents = state.activeSubAgents || {};
  const entries = Object.entries(agents);
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
    return '<div class="bg-task-row">' +
      '<div class="bg-task-info">' +
        '<span class="bg-task-name">' + status + ' ' + label + '</span>' +
        toolPart +
      '</div>' +
    '</div>';
  }).join('');
}

// Toggle dropdown on indicator click
document.getElementById('delegate-indicator')?.addEventListener('click', (e) => {
  e.stopPropagation();
  const dropdown = document.getElementById('delegate-dropdown');
  if (!dropdown) return;
  if (dropdown.classList.contains('hidden')) {
    renderDelegateDropdown();
    dropdown.classList.remove('hidden');
  } else {
    dropdown.classList.add('hidden');
  }
});

// Close dropdown on outside click
document.addEventListener('click', (e) => {
  const dropdown = document.getElementById('delegate-dropdown');
  const indicator = document.getElementById('delegate-indicator');
  if (dropdown && !dropdown.contains(e.target) && !indicator?.contains(e.target)) {
    dropdown.classList.add('hidden');
  }
});

onMessage('agentStart', (msg) => {
  resetStreamTimeout(msg.sessionId);
  if (!isActive(msg)) return;
  const aid = msg.agentId || msg.name;
  state.activeAgentId = aid;
  if (!state.activeSubAgents) state.activeSubAgents = {};
  state.activeSubAgents[aid] = {
    name: msg.name || aid,
    task: msg.taskDescription || '',
    currentTool: null,
    done: false
  };
  updateDelegateIndicator();
});

onMessage('agentTextDelta', (msg) => { resetStreamTimeout(msg.sessionId); });
onMessage('agentToolCallDetected', (msg) => { resetStreamTimeout(msg.sessionId); });

onMessage('agentToolStart', (msg) => {
  resetStreamTimeout(msg.sessionId);
  const aid = msg.agentId || state.activeAgentId;
  if (aid && state.activeSubAgents && state.activeSubAgents[aid]) {
    state.activeSubAgents[aid].currentTool = msg.label;
    renderDelegateDropdown();
  }
});

onMessage('agentToolEnd', (msg) => { resetStreamTimeout(msg.sessionId); });
onMessage('agentEnd', (msg) => { resetStreamTimeout(msg.sessionId); });

onMessage('agentThinking', (msg) => { resetStreamTimeout(msg.sessionId); });
onMessage('agentRetryStatus', (msg) => { resetStreamTimeout(msg.sessionId); });

onMessage('agentDone', (msg) => {
  resetStreamTimeout(msg.sessionId);
  if (!isActive(msg)) return;
  const aid = msg.agentId || state.activeAgentId;
  if (aid && state.activeSubAgents) {
    // Mark as done briefly so user sees transition, then remove
    if (state.activeSubAgents[aid]) state.activeSubAgents[aid].done = true;
    renderDelegateDropdown();
    setTimeout(() => {
      if (state.activeSubAgents && state.activeSubAgents[aid]) {
        delete state.activeSubAgents[aid];
        updateDelegateIndicator();
      }
    }, 2000);
  }
  state.activeAgentId = null;
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
    renderSystemBubble(t('chat.compacting'));
  }
});

onMessage('compactComplete', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
  setCompacting(sid, false);
  if (sid === state.activeSessionId) {
    const detail = msg.reportPath ? ` (report: ${msg.reportPath.split('/').pop()})` : '';
    renderSystemBubble(t('chat.compacted', { before: msg.before, after: msg.after, detail }));
  }
});

onMessage('compactFailed', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
  setCompacting(sid, false);
  if (sid === state.activeSessionId) {
    renderSystemBubble(t('chat.compactFailed', { attempt: msg.attempt, maxAttempts: msg.maxAttempts }));
  }
  if (msg.attempt >= msg.maxAttempts) {
    if (isActive(msg)) {
      renderError(t('chat.compactCircuitBreaker', { attempt: msg.attempt }));
    }
    clearBusyFor(msg);
  }
});

// --- Agent panel events (global) ---
onMessage('agentList', (msg) => {
  state.agentsData = msg.agents || [];
  if (msg.availableTools) state.agentAvailableTools = msg.availableTools;
  renderAgentList();
  // Auto-select first agent if none selected
  if (!state.selectedAgent && state.agentsData.length > 0) {
    import('./sidebar.js').then(({ selectAgent }) => {
      // Default to Jarvis (main orchestrator)
      const jarvis = state.agentsData.find(a => a.name === 'Jarvis');
      selectAgent(jarvis ? 'Jarvis' : (state.agentsData[0]?.name || 'Nebula'));
    });
  }
});

onMessage('agentSessionList', (msg) => {
  // Unified list — accept all sessions regardless of which agent triggered the request
  const agentName = msg.agentName;
  const sessions = msg.sessions || [];
  const folders = msg.folders || [];
  state.folders = folders;
  state.foldersWithRules = new Set(msg.foldersWithRules || []);
  // Build sessionId -> agentName mapping
  sessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || agentName; });
  // Find active session for this agent
  const prevActiveId = state.activeSessionId;
  const newActiveId = sessions.find(s => s.id === prevActiveId) ? prevActiveId : (sessions[0]?.id || null);
  // Clear memory cache when session changes so modal fetches fresh content from server
  if (newActiveId && newActiveId !== prevActiveId) {
    clearMemoryCache();
  }
  renderSessionSidebar(sessions, newActiveId);
  // Sync backend active session when agent tab switch auto-selected a different session.
  // Without this, getMemory/memoryStatus read backend's getActiveMeta which still
  // points to the previous session → shows wrong memory content.
  if (newActiveId && newActiveId !== prevActiveId) {
    sendWs({type: 'switchSession', sessionId: newActiveId});
  }
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

onMessage('configUpdated', (msg) => {
  if (!msg.success) { renderError(t('chat.configUpdateFailed')); return; }
  // Re-fetch config from server so UI reflects what was actually saved
  sendWs({type: 'getConfig'});
});

// --- Model selection ---
onMessage('modelOptions', (msg) => {
  const models = msg.models || [];
  const current = msg.current || null;
  if (models.length === 0) {
    renderSystemBubble(t('chat.noModels'));
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
  const defaultLabel = t('chat.modelDefault');
  options.unshift({label: defaultLabel, desc: t('chat.modelDefaultDesc'), ref: null});
  import('./chat.js').then(({ showOptions }) => {
    showOptions(state.currentAiBubble, [
      {question: t('chat.selectModel'), options: options.map(o => ({label: o.label, desc: o.desc})), allowOther: false}
    ], (answers) => {
      const selected = options.find(o => o.label === answers[0]);
      const modelRef = selected ? selected.ref : null;
      sendWs({type: 'setSessionModel', sessionId: state.activeSessionId, modelRef: modelRef});
      renderSystemBubble(t('chat.modelSet', { model: answers[0] === defaultLabel ? 'default' : answers[0].replace(' ✓', '') }));
    }, t('chat.apply'));
  });
});

onMessage('sessionModelSet', (msg) => {
});

// --- Retry / fallback status ---
onMessage('retryStatus', (msg) => {
  resetStreamTimeout(msg.sessionId);
  if (isActive(msg)) renderRetryStatus(msg.message);
});

// --- Bridge user message (e.g. from external platform) ---
onMessage('bridgeUser', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  state.turnExpecting[sid] = true;
  saveMsg({type: 'user', text: msg.text}, sid);
  if (sid === state.activeSessionId) {
    renderUserBubble(msg.text, []);
    smartScroll();
  }
});

// --- Session busy state (backend authority) ---
onMessage('sessionBusy', (msg) => {
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
  // DOM-related cleanup only for active session
  if (isActive(msg) && !msg.busy && state.currentAiBubble) {
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
    Object.keys(state.agentBubbles).forEach(id => finishAgent(id));
    state.agentBubbles = {};
    state.activeAgentId = null;
    clearStatus();
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
      sendWs({ type: 'cancelBackgroundJob', sessionId: state.activeSessionId, jobId: task.taskId });
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
  const active = tasks.filter(task => task.status === 'running' || task.status === 'cancelling');
  const el = state.dom.bgIndicatorEl;
  const countEl = state.dom.bgCountEl;
  const dropdown = state.dom.bgDropdownEl;
  if (!el || !countEl) return;
  if (active.length > 0) {
    el.classList.remove('hidden');
    countEl.textContent = active.length;
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
    const taskId = msg.taskId;
    setTimeout(() => {
      const existing = state.sessionBgTasks[sid];
      if (existing) {
        state.sessionBgTasks[sid] = existing.filter(t => t.taskId !== taskId);
        updateBgTasksUI();
      }
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
  // Clean up thinking buffer so the subsequent 'done' event doesn't save
  // ask-mode thinking as a separate AI message (stray thinking fragment).
  if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
  if (state.currentThinkingBubble) finishThinking();
});
onMessage('askError', (msg) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid) delete state.sessionAskBuffers[sid];
  if (isActive(msg)) renderAskError(msg.message);
});

// --- Skills ---
onMessage('skillList', (msg) => {
  const skills = msg.skills || [];
  state.skills = skills;
  registerSkillCommands(skills);
});


// --- Memory ---
onMessage('memoryData', (msg) => handleMemoryData(msg));
onMessage('memorySaved', () => { /* saved confirmation, no action needed */ });
onMessage('memoryStatus', (msg) => showMemoryButton());

// --- Rules ---
onMessage('rulesData', (msg) => handleRulesData(msg));
onMessage('rulesSaved', (msg) => handleRulesSaved(msg));
onMessage('rulesDeleted', (msg) => handleRulesDeleted(msg));

// --- Browse Result (path picker) ---
onMessage('browseResult', (msg) => handleBrowseResult(msg));

// --- Card design prompt ---
onMessage('cardDesignData', (msg) => { state.cardDesignPrompt = msg.content || ''; });
onMessage('cardDesignSaved', () => { /* saved confirmation */ });

// --- Update check ---
onMessage('updateCheckResult', (msg) => {
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

onMessage('updateCompleted', (msg) => {
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

// ---------- 5. Initialize UI modules ----------
applyLocaleToHtml(); // Apply locale to static HTML elements
initNavTabs();
initModals();
initRulesModal();
initPathPicker();
initInput();
initMemory();
initScheduledTask();
initSecondaryChat();
initMesh();

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
  // Re-render current session content to update translated text
  if (state.activeSessionId) {
    renderSessionSidebar(state.sessions, state.activeSessionId);
    resetChatForActiveSession();
  }
});
// New Folder button
document.getElementById('new-folder-btn')?.addEventListener('click', () => createNewFolder(state.activeFolderId));


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
