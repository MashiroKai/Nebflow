import state from './state.js';
import { LS_SESSIONS_KEY } from './state.js';
import { initSpinner, initMarkdown, smartScroll } from './utils.js';
import { connect, onMessage, sendWs } from './ws.js';
import {
  setBusy, setStatus, clearStatus,
  renderUserBubble, appendAiText, finishAi,
  appendAgentText, finishAgent, getAgentColor,
  renderTool, renderToolPending, renderError,
  renderSystemBubble,
  showOptions, renderAskUser, renderPermissionPrompt,
  renderAttachmentPreview
} from './chat.js';
import {
  initNavTabs, renderSessionSidebar, renderAgentList, renderSettings,
  switchSession, deleteSession, formatSessionTime
} from './sidebar.js';
import {
  showNewSessionModal, hideModals, confirmNewSession,
  showDeleteModal, confirmDeleteSession,
  showAgentModal, hideAgentModal, initModals,
  startInlineNewSession
} from './modal.js';
import { send, handleSlash, addFileAttachment, initInput, setNewSessionHandler } from './input.js';
import { saveMsg, loadMsgs, restoreFromStorage, migrateLegacyIfNeeded } from './persistence.js';

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
  const item = state.dom.sessionList.querySelector(`.session-item[data-id="${sessionId}"]`);
  if (item) {
    const dot = item.querySelector('.session-dot');
    if (dot) dot.classList.add('show');
  }
}

// Helper: is this event for the currently active session?
function isActive(msg) {
  return !msg.sessionId || msg.sessionId === state.activeSessionId;
}

// Helper: clear busy for a specific session
function clearBusyFor(msg) {
  const sid = msg.sessionId || state.activeSessionId;
  if (state.busySessionId === sid) {
    state.busySessionId = null;
    if (sid === state.activeSessionId) {
      const { input, sendBtn, stopBtn } = state.dom;
      input.disabled = false;
      sendBtn.style.display = 'flex';
      stopBtn.style.display = 'none';
      input.focus();
    }
  }
  clearTimeout(state.busyTimeoutId);
}

// --- Chat streaming ---
// ALL sessions: save to localStorage. Active session only: render DOM.

onMessage('textDelta', (msg) => {
  const sid = msg.sessionId;
  // Accumulate text for ALL sessions
  if (sid) state.sessionTexts[sid] = (state.sessionTexts[sid] || '') + msg.delta;
  if (isActive(msg)) {
    if (!state.busySessionId) setBusy(msg.sessionId || state.activeSessionId);
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
  if (isActive(msg)) {
    if (!state.busySessionId) setBusy(msg.sessionId || state.activeSessionId);
    if (!state.currentAiBubble) {
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
    if (!state.busySessionId) setBusy(msg.sessionId || state.activeSessionId);
    renderToolPending(msg.label);
  }
});

onMessage('toolEnd', (msg) => {
  if (msg.label && msg.label.startsWith('AskUser')) return;
  if (isActive(msg)) {
    const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input);
    if (data) saveMsg(data, msg.sessionId);
  } else {
    saveMsg({type: 'tool', label: msg.label, summary: msg.summary, content: msg.content, isError: msg.isError, input: msg.input}, msg.sessionId);
  }
});

// --- Terminal events ---
// Always clear busySessionId. DOM + status only for active session. Always mark unread for non-active.

onMessage('done', (msg) => {
  clearBusyFor(msg);
  // Flush any remaining buffered text for this session
  if (msg.sessionId && state.sessionTexts[msg.sessionId]) {
    if (!isActive(msg)) {
      saveMsg({type: 'ai', text: state.sessionTexts[msg.sessionId]}, msg.sessionId);
    }
    delete state.sessionTexts[msg.sessionId];
  }
  if (isActive(msg)) {
    if (state.currentToolCard) { state.currentToolCard.remove(); state.currentToolCard = null; }
    const data = finishAi();
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
    if (state.currentToolCard) { state.currentToolCard.remove(); state.currentToolCard = null; }
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
    if (state.currentToolCard) { state.currentToolCard.remove(); state.currentToolCard = null; }
    finishAi();
    clearStatus();
  }
});

onMessage('timeout', (msg) => {
  clearBusyFor(msg);
  if (isActive(msg)) {
    if (state.currentToolCard) { state.currentToolCard.remove(); state.currentToolCard = null; }
    finishAi();
    renderError('Response timed out');
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

onMessage('maxTokens', (msg) => {
  clearBusyFor(msg);
  if (isActive(msg)) {
    if (state.currentToolCard) { state.currentToolCard.remove(); state.currentToolCard = null; }
    finishAi();
    renderError('Max tokens reached — response truncated');
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

// --- AskUser / Permission ---
onMessage('askUser', (msg) => {
  if (isActive(msg)) {
    const data = renderAskUser(msg.items);
    if (data) saveMsg(data, msg.sessionId);
  }
});

onMessage('askPermission', (msg) => {
  if (isActive(msg)) renderPermissionPrompt(msg.toolName, msg.summary, msg.input);
});

// --- Session list (global) ---
let restoredSessionId = null;
onMessage('sessionList', (msg) => {
  renderSessionSidebar(msg.sessions, msg.activeId);
  // Only restore messages on first load (before any session was active)
  if (!restoredSessionId && msg.activeId) {
    restoredSessionId = msg.activeId;
    restoreFromStorage();
  }
  migrateLegacyIfNeeded();
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
    const badge = document.createElement('div');
    badge.className = 'agent-badge';
    badge.style.borderColor = color;
    badge.style.color = color;
    badge.textContent = state.activeAgentId;
    row.appendChild(badge);
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai';
    bubble.innerHTML = '<span style="color:#999;font-size:13px;">Thinking...</span>';
    row.appendChild(bubble);
    chat.appendChild(row);
    state.agentBubbles[state.activeAgentId] = { bubble, text: '', row, badge };
  }
});

onMessage('agentTextDelta', (msg) => { if (isActive(msg)) appendAgentText(msg.agentId || state.activeAgentId, msg.delta); });
onMessage('agentToolStart', (msg) => { if (isActive(msg)) renderToolPending(msg.label); });
onMessage('agentToolEnd', (msg) => { if (isActive(msg)) renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input); });
onMessage('agentEnd', (msg) => { if (isActive(msg)) finishAgent(msg.name || msg.agentId); });

onMessage('agentThinking', (msg) => {
  if (!isActive(msg)) return;
  if (state.activeAgentId && state.agentBubbles[state.activeAgentId]) {
    state.agentBubbles[state.activeAgentId].bubble.innerHTML = '<span style="color:#999;font-size:13px;">Thinking...</span>';
  }
});

onMessage('agentDone', (msg) => {
  if (!isActive(msg)) return;
  Object.keys(state.agentBubbles).forEach(id => finishAgent(id));
  state.agentBubbles = {};
  state.activeAgentId = null;
});

// --- Agent panel events (global) ---
onMessage('agentList', (msg) => {
  state.agentsData = msg.agents || [];
  renderAgentList();
});

onMessage('agentConfig', (msg) => showAgentModal(msg.name, msg.yaml || '', msg.systemMd || ''));
onMessage('agentCreated', () => sendWs({ type: 'listAgents' }));
onMessage('agentUpdated', () => sendWs({ type: 'listAgents' }));

onMessage('configData', (msg) => {
  state.configText = msg.config || '';
  const editor = document.getElementById('config-editor');
  if (editor) editor.value = state.configText;
});

onMessage('configUpdated', (msg) => {
  if (!msg.success) renderError('Config update failed');
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
});

// ---------- 6. Start ----------
connect();
state.dom.input.focus();
