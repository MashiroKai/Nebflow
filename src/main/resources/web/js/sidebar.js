// sidebar.js — Left panel management: nav tabs, agent list, settings, session sidebar

import state, { LS_SESSIONS_KEY, LS_DRAFTS_KEY } from './state.js';
import { sendWs, onMessage } from './ws.js';
import { showAgentModal } from './modal.js';
import { renderMarkdownWithMath, smartScroll, stopSpinner } from './utils.js';
import { finishAgent, setStatus } from './chat.js';
import { restoreFromStorage, loadMsgs } from './persistence.js';
import { renderTaskList } from './taskList.js';

// ---------- Nav Bar Tab Switching ----------
export function initNavTabs() {
  document.querySelectorAll('.nav-item[data-tab]').forEach(item => {
    item.addEventListener('click', () => {
      const tab = item.dataset.tab;
      document.querySelectorAll('.nav-item[data-tab]').forEach(n => n.classList.remove('active'));
      item.classList.add('active');
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
      document.getElementById('panel-' + tab).classList.add('active');
      if (tab === 'settings') {
        sendWs({type: 'getConfig'});
        renderSettings();
      } else {
        // Returning to sessions from settings — switch back
        document.querySelectorAll('.nav-item[data-tab]').forEach(n => n.classList.remove('active'));
      }
    });
  });
}

// ---------- Agent icons in Nav Bar ----------
export function renderAgentList() {
  const list = document.getElementById('nav-agent-list');
  if (!list) return;
  list.innerHTML = '';
  state.agentsData.forEach(a => {
    const el = document.createElement('div');
    const isActive = state.selectedAgent === a.name;
    el.className = 'nav-agent' + (isActive ? ' active' : '');
    const avatar = a.avatar || '';
    const displayName = a.displayName || a.name;
    el.dataset.name = a.name;
    el.title = displayName;
    const iconHtml = avatar
      ? `<span class="nav-agent-icon">${escapeHtml(avatar)}</span>`
      : '<i data-lucide="bot" class="nav-agent-icon" style="color:#888"></i>';
    el.innerHTML = `${iconHtml}<span class="nav-agent-label">${escapeHtml(displayName.slice(0, 5))}</span>`;
    el.addEventListener('click', () => selectAgent(a.name));
    el.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      sendWs({type: 'getAgentConfig', name: a.name});
    });
    list.appendChild(el);
  });
  lucide.createIcons();
}

/** Select an agent and load its sessions. */
export function selectAgent(agentName) {
  if (state.selectedAgent === agentName) return;
  // Save current input draft before switching agent tabs
  saveInputDraft(state.activeSessionId);
  state.selectedAgent = agentName;
  // Clear unread count for this agent
  const prevCount = state.agentUnreadCounts[agentName] || 0;
  if (prevCount > 0) {
    state.agentUnreadCounts[agentName] = 0;
    const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
    if (el) { const dot = el.querySelector('.agent-notif-dot'); if (dot) dot.remove(); }
  }
  // Switch back to sessions panel if on settings
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.getElementById('panel-sessions').classList.add('active');
  document.querySelectorAll('.nav-item[data-tab]').forEach(n => n.classList.remove('active'));
  // Update nav bar active state
  document.querySelectorAll('#nav-agent-list .nav-agent').forEach(el => {
    el.classList.toggle('active', el.dataset.name === agentName);
  });
  // Load sessions for this agent
  sendWs({type: 'listAgentSessions', name: agentName});
}

/** Update header brand to show agent display name. */
export function updateHeaderBrand(agentName) {
  const brandEl = document.querySelector('.header-brand');
  if (!brandEl) return;
  const agent = state.agentsData.find(a => a.name === agentName);
  if (agent) {
    brandEl.textContent = agent.displayName || agent.name;
  } else {
    brandEl.textContent = 'nebflow';
  }
}

// ---------- Settings Panel ----------
export function renderSettings() {
  const content = document.getElementById('settings-content');
  content.innerHTML = `
    <div class="settings-section">
      <div class="settings-section-title">Runtime</div>
      <div class="settings-row">
        <span class="settings-label">Thinking Mode</span>
        <div class="toggle ${state.thinkingMode ? 'on' : ''}" id="toggle-thinking"></div>
      </div>
      <div class="settings-row">
        <span class="settings-label">Permission Policy</span>
        <div class="policy-group">
          <label class="policy-option"><input type="radio" name="policy" value="ask"> Ask</label>
          <label class="policy-option"><input type="radio" name="policy" value="auto"> Trust All</label>
          <label class="policy-option"><input type="radio" name="policy" value="block"> Block Dangerous</label>
        </div>
      </div>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">Configuration</div>
      <div class="config-editor-wrap">
        <textarea id="config-editor" spellcheck="false">${escapeHtml(state.configText)}</textarea>
        <div class="config-actions">
          <button class="btn-save" id="btn-save-config">Save</button>
          <button id="btn-reload-config">Reload</button>
        </div>
      </div>
    </div>
    <div class="settings-section">
      <div class="settings-section-title">About</div>
      <div class="about-info">
        Nebflow v${state.serverVersion || '...'}<br>
        Connection: <span style="color:${state.dom.connEl.classList.contains('off') ? '#f44336' : '#4caf50'}">${state.dom.connEl.classList.contains('off') ? 'Disconnected' : 'Connected'}</span>
      </div>
    </div>`;
  // Thinking toggle — unified with /thinking slash command (includes budget_tokens)
  document.getElementById('toggle-thinking').addEventListener('click', function() {
    this.classList.toggle('on');
    const enabled = this.classList.contains('on');
    state.thinkingMode = enabled ? {type: 'enabled', budget_tokens: 16000} : null;
    sendWs({type: 'setThinking', thinking: state.thinkingMode});
  });
  // Policy radio — restore current selection from state
  const currentPolicy = state.currentPolicy || 'ask';
  content.querySelectorAll('input[name="policy"]').forEach(r => {
    r.checked = (r.value === currentPolicy);
    r.addEventListener('change', () => {
      state.currentPolicy = r.value;
      sendWs({type: 'setPolicy', policy: r.value});
    });
  });

  // Config save — validate JSON before sending
  document.getElementById('btn-save-config')?.addEventListener('click', () => {
    const cfg = document.getElementById('config-editor').value;
    try {
      JSON.parse(cfg);
      sendWs({type: 'updateConfig', config: cfg});
    } catch(e) {
      alert('Invalid JSON: ' + e.message);
    }
  });
  document.getElementById('btn-reload-config')?.addEventListener('click', () => {
    sendWs({type: 'getConfig'});
  });
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// ---------- Session Sidebar ----------
export function formatSessionTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  const now = new Date();
  const isToday = d.toDateString() === now.toDateString();
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const isYesterday = d.toDateString() === yesterday.toDateString();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (isToday) return hh + ':' + mm;
  if (isYesterday) return 'Yesterday ' + hh + ':' + mm;
  return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + hh + ':' + mm;
}

export function renderSessionSidebar(sessionData, activeId) {
  state.sessions = sessionData || [];
  const prevActiveId = state.activeSessionId;
  if (activeId) state.activeSessionId = activeId;
  // If active session changed (new session, agent session, delete active), reset chat area
  if (activeId && activeId !== prevActiveId) {
    saveInputDraft(prevActiveId);
    resetChatForActiveSession();
    restoreInputDraft(activeId);
  }

  // Clean up localStorage for deleted sessions
  const currentIds = new Set((sessionData || []).map(s => s.id));
  try {
    const all = (() => { try { return JSON.parse(localStorage.getItem(LS_SESSIONS_KEY) || '{}'); } catch(e) { return {}; } })();
    let changed = false;
    for (const key of Object.keys(all)) {
      if (!currentIds.has(key)) { delete all[key]; changed = true; }
    }
    if (changed) { try { localStorage.setItem(LS_SESSIONS_KEY, JSON.stringify(all)); } catch(e) {} }
  } catch(e) {}
  // Clean up drafts for deleted sessions
  let draftsChanged = false;
  for (const sid of Object.keys(state.sessionInputDrafts)) {
    if (!currentIds.has(sid)) { delete state.sessionInputDrafts[sid]; draftsChanged = true; }
  }
  if (draftsChanged) persistDrafts();

  const sessionList = state.dom.sessionList;
  sessionList.innerHTML = '';

  // Sort sessions: pinned first, then by most recently active
  const sorted = [...state.sessions].sort((a, b) => {
    const pa = state.pinnedSessions.has(a.id) ? 1 : 0;
    const pb = state.pinnedSessions.has(b.id) ? 1 : 0;
    if (pb !== pa) return pb - pa;
    const ta = a.updatedAt || a.createdAt || 0;
    const tb = b.updatedAt || b.createdAt || 0;
    return tb - ta;
  });

  sorted.forEach((s, idx) => {
    // Insert divider between pinned and non-pinned groups
    if (idx > 0 && !state.pinnedSessions.has(s.id) && state.pinnedSessions.has(sorted[idx - 1].id)) {
      const divider = document.createElement('div');
      divider.className = 'session-divider';
      sessionList.appendChild(divider);
    }
    const item = document.createElement('div');
    item.className = 'session-item'
      + (s.id === state.activeSessionId ? ' active' : '')
      + (state.pinnedSessions.has(s.id) ? ' pinned' : '');
    item.dataset.id = s.id;
    const statusCls = getSessionStatusClass(s.id);
    const draft = state.sessionInputDrafts[s.id];
    const draftHtml = draft && draft.text
      ? '<div class="session-draft">' + escapeHtml(draft.text.replace(/\n/g, ' ').slice(0, 60)) + '</div>'
      : '';
    item.innerHTML =
      '<div class="session-info">' +
      '<div class="session-name">' + escapeHtml(s.name) + '</div>' +
      (draftHtml || '<div class="session-time">' + formatSessionTime(s.updatedAt || s.createdAt) + '</div>') +
      '</div>' +
      '<div class="session-status ' + statusCls + '">' +
      '<div class="status-spinner"><i data-lucide="loader-2"></i></div>' +
      '<div class="status-compact-spinner"><i data-lucide="minimize-2"></i></div>' +
      '<div class="status-dot"></div>' +
      '</div>' +
      '<button class="session-delete" title="Delete"><i data-lucide="x"></i></button>';
    // Double-click to rename
    const nameEl = item.querySelector('.session-name');
    nameEl.addEventListener('dblclick', (e) => {
      e.stopPropagation();
      nameEl.contentEditable = true;
      nameEl.focus();
      // Select all text
      const range = document.createRange();
      range.selectNodeContents(nameEl);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    });
    const finishRename = () => {
      nameEl.contentEditable = false;
      const newName = nameEl.textContent.trim();
      if (newName && newName !== s.name) {
        sendWs({type: 'renameSession', sessionId: s.id, name: newName});
      } else {
        nameEl.textContent = s.name;
      }
    };
    nameEl.addEventListener('blur', finishRename);
    nameEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        if (e.isComposing || e.keyCode === 229) return;
        e.preventDefault(); nameEl.blur();
      }
      if (e.key === 'Escape') { nameEl.textContent = s.name; nameEl.blur(); }
    });
    item.querySelector('.session-delete').onclick = (e) => {
      e.stopPropagation();
      if (typeof window.__showDeleteModal === 'function') window.__showDeleteModal(s.id, s.name);
    };
    item.onclick = (e) => {
      if (e.target.closest('.session-delete') || e.target.closest('.session-name[contenteditable="true"]')) return;
      if (s.id !== state.activeSessionId) switchSession(s.id);
    };
    item.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      showSessionCtxMenu(e.clientX, e.clientY, s.id);
    });
    sessionList.appendChild(item);
  });
  if (typeof lucide !== 'undefined') lucide.createIcons();
  // Update header session name + brand
  const sessionNameEl = state.dom.sessionNameEl;
  const active = state.sessions.find(s => s.id === state.activeSessionId);
  if (active) {
    sessionNameEl.textContent = active.name;
    sessionNameEl.style.display = '';
    // Show feishu indicator if session has feishu config
    const feishuActive = active.bridges?.feishu?.enabled;
    sessionNameEl.classList.toggle('feishu-linked', !!feishuActive);
    // Update header brand based on session's agent
    const agentName = active.agentName || 'Nebula';
    updateHeaderBrand(agentName);
  } else {
    sessionNameEl.textContent = '';
    sessionNameEl.style.display = 'none';
  }
}

// Persist sessionInputDrafts to localStorage
function persistDrafts() {
  try { localStorage.setItem(LS_DRAFTS_KEY, JSON.stringify(state.sessionInputDrafts)); } catch(e) {}
}

// Save current input box content as draft for the given session
export function saveInputDraft(sessionId) {
  if (!sessionId || !state.dom.input) return;
  const text = state.dom.input.value;
  const attachments = state.pendingAttachments;
  if (text || attachments.length > 0) {
    state.sessionInputDrafts[sessionId] = { text, attachments: JSON.parse(JSON.stringify(attachments)) };
  } else {
    delete state.sessionInputDrafts[sessionId];
  }
  persistDrafts();
}

// Restore input box content from draft for the given session
function restoreInputDraft(sessionId) {
  const input = state.dom.input;
  if (!input) return;
  const draft = state.sessionInputDrafts[sessionId];
  if (draft) {
    input.value = draft.text;
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    state.pendingAttachments = draft.attachments || [];
    // Restore attachment preview if import available
    import('./chat.js').then(({ renderAttachmentPreview }) => {
      renderAttachmentPreview();
    });
  } else {
    input.value = '';
    input.style.height = 'auto';
    state.pendingAttachments = [];
    if (state.dom.attPreview) state.dom.attPreview.innerHTML = '';
  }
}

// Reset chat area for the current activeSessionId (used after session list updates)
function resetChatForActiveSession() {
  state.aiText = '';
  state.currentAiBubble = null;
  state.agentBubbles = {};
  state.activeAgentId = null;
  Object.keys(state.sessionToolCards).forEach(sid => {
    if (state.sessionToolCards[sid]) state.sessionToolCards[sid].remove();
  });
  state.sessionToolCards = {};
  state.historyOffset = 0;
  state.historyHasMore = false;
  state.historyLoading = false;
  state.dom.chat.innerHTML = '';
  // Clear any history loader/end indicators (they live in chat, but belt-and-suspenders)
  state.dom.chat.querySelectorAll('.history-loader, .history-end').forEach(el => el.remove());
  smartScroll();

  const sid = state.activeSessionId;
  const isStreaming = state.busySessionIds.has(sid);

  // Request history from backend (historyPage handler in main.js will render it)
  if (sid) {
    if (state.searchNavigateTarget && state.searchNavigateTarget.sessionId === sid) {
      // Search navigation: request a page that includes the target message
      const targetIdx = state.searchNavigateTarget.messageIndex;
      const limit = 100;
      // Center the target message in the loaded page
      const beforeIndex = targetIdx + Math.floor(limit / 2);
      sendWs({ type: 'getHistory', sessionId: sid, limit, beforeIndex });
    } else {
      sendWs({ type: 'getHistory', sessionId: sid, limit: 100 });
    }
  }

  // If this session is streaming, restore buffered text
  if (isStreaming && state.sessionTexts[sid]) {
    state.aiText = state.sessionTexts[sid];
    const chat = state.dom.chat;
    const row = document.createElement('div');
    row.className = 'row ai';
    state.currentAiBubble = document.createElement('div');
    state.currentAiBubble.className = 'bubble ai';
    state.currentAiBubble.innerHTML = renderMarkdownWithMath(state.aiText) + '<span class="cursor"></span>';
    row.appendChild(state.currentAiBubble);
    chat.appendChild(row);
    smartScroll();
  }

  // Interactive AskUser is re-rendered in the historyPage handler (main.js) after history loads

  // Update busy UI
  const isBusy = isStreaming;
  const { input, sendBtn, stopBtn } = state.dom;
  input.disabled = isBusy;
  sendBtn.style.display = isBusy ? 'none' : 'flex';
  stopBtn.style.display = isBusy ? 'flex' : 'none';

  // Restore compacting status if this session is compacting
  const statusWrap = state.dom.statusWrap;
  statusWrap.classList.remove('compacting', 'compact-done', 'compact-failed');
  if (state.compactingSessionIds.has(sid)) {
    statusWrap.classList.add('compacting');
    setStatus('Compacting context...');
  } else {
    // Clear residual status from previous session
    statusWrap.classList.remove('on');
    stopSpinner();
  }

  if (!isBusy) input.focus();

  // Restore cached task list for this session (or clear if none)
  renderTaskList(state.sessionTasks[sid] || []);
  // Restore background task indicator for this session
  if (state.updateBgTasksUI) state.updateBgTasksUI();
}

export function switchSession(sessionId) {
  // Clear unread + marked unread for this session
  state.unreadSessions.delete(sessionId);
  state.markedUnreadSessions.delete(sessionId);
  persistUnread();
  persistMarkedUnread();
  // Save draft for the session we're leaving
  saveInputDraft(state.activeSessionId);
  const prevActiveId = state.activeSessionId;
  // Switch active session
  state.activeSessionId = sessionId;
  // Update sidebar status for both sessions (activeSessionId has changed,
  // so getSessionStatusClass may return different values, e.g. compacting → busy or vice versa)
  updateSessionStatus(sessionId);
  if (prevActiveId && prevActiveId !== sessionId) updateSessionStatus(prevActiveId);
  resetChatForActiveSession();
  // Restore input draft for the new session
  restoreInputDraft(sessionId);
  // Task list and bg task indicator are restored inside resetChatForActiveSession()
  sendWs({type: 'switchSession', sessionId});
}

export function deleteSession(sessionId) {
  delete state.sessionInputDrafts[sessionId];
  state.unreadSessions.delete(sessionId);
  state.markedUnreadSessions.delete(sessionId);
  state.pinnedSessions.delete(sessionId);
  persistUnread();
  persistMarkedUnread();
  persistPinned();
  persistDrafts();
  sendWs({type: 'deleteSession', sessionId});
}

// ---------- Session status state machine ----------
// Priority: attention > compacting-bg > busy > marked unread > unread > idle
// Only one indicator visible at a time, controlled by a single CSS class.
// Attention takes priority over compacting so AskUser/permission prompts are visible
// even while the session is still technically compacting.
//
// Compact logic:
//   - Active session compacting: green compact info shown in chat area status bar;
//     sidebar shows 'busy' (falls through).
//   - Non-active session compacting: sidebar shows 'compacting' spinner.

function getSessionStatusClass(sessionId) {
  if (state.attentionSessions.has(sessionId)) return 'attention';
  // Only show 'compacting' spinner in sidebar for non-active sessions.
  // Active session shows compact info in the chat area status bar instead.
  if (state.compactingSessionIds.has(sessionId) && sessionId !== state.activeSessionId) return 'compacting';
  if (state.busySessionIds.has(sessionId)) return 'busy';
  if (state.markedUnreadSessions.has(sessionId) || state.unreadSessions.has(sessionId)) return 'unread';
  return '';
}

function persistMarkedUnread() {
  try {
    localStorage.setItem('nebflow_marked_unread', JSON.stringify([...state.markedUnreadSessions]));
  } catch(e) {}
}

function persistUnread() {
  try {
    localStorage.setItem('nebflow_unread', JSON.stringify([...state.unreadSessions]));
  } catch(e) {}
}

export { persistUnread };

function persistPinned() {
  try {
    localStorage.setItem('nebflow_pinned', JSON.stringify([...state.pinnedSessions]));
  } catch(e) {}
}

// ---------- Session context menu ----------

let activeCtxMenu = null;

function dismissCtxMenu() {
  if (activeCtxMenu) { activeCtxMenu.remove(); activeCtxMenu = null; }
}

function showSessionCtxMenu(x, y, sessionId) {
  dismissCtxMenu();
  const isPinned = state.pinnedSessions.has(sessionId);
  const menu = document.createElement('div');
  menu.className = 'session-ctx-menu';
  menu.innerHTML =
    '<div class="ctx-item" data-action="mark-unread">标记为未读</div>' +
    '<div class="ctx-item" data-action="toggle-pin">' + (isPinned ? '取消置顶' : '置顶') + '</div>';
  document.body.appendChild(menu);
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';

  menu.querySelector('[data-action="mark-unread"]').addEventListener('click', () => {
    state.markedUnreadSessions.add(sessionId);
    state.unreadSessions.delete(sessionId);
    persistUnread();
    persistMarkedUnread();
    updateSessionStatus(sessionId);
    dismissCtxMenu();
  });
  menu.querySelector('[data-action="toggle-pin"]').addEventListener('click', () => {
    if (isPinned) state.pinnedSessions.delete(sessionId);
    else state.pinnedSessions.add(sessionId);
    persistPinned();
    // Re-render to update sort order and pinned class
    renderSessionSidebar(state.sessions, state.activeSessionId);
    dismissCtxMenu();
  });
  activeCtxMenu = menu;
}

// Dismiss on click outside or scroll
document.addEventListener('click', (e) => {
  if (activeCtxMenu && !activeCtxMenu.contains(e.target)) dismissCtxMenu();
});
document.addEventListener('scroll', () => dismissCtxMenu(), true);
document.addEventListener('contextmenu', (e) => {
  if (activeCtxMenu && !activeCtxMenu.contains(e.target)) dismissCtxMenu();
}, true);

export function updateSessionStatus(sessionId) {
  if (!sessionId) return;
  const item = state.dom.sessionList.querySelector(`.session-item[data-id="${sessionId}"]`);
  if (!item) return;
  const el = item.querySelector('.session-status');
  if (!el) return;
  el.className = 'session-status ' + getSessionStatusClass(sessionId);
}

/** Show/hide the attention indicator for a session in the sidebar. */
export function setSessionAttention(sessionId, attention) {
  if (!sessionId) return;
  if (attention) state.attentionSessions.add(sessionId);
  else state.attentionSessions.delete(sessionId);
  updateSessionStatus(sessionId);
  // Also update agent notification dot for attention (yellow dot for askUser)
  const agentName = state.sessionAgentMap[sessionId];
  if (agentName && agentName !== state.selectedAgent) {
    if (attention) {
      state.agentUnreadCounts[agentName] = (state.agentUnreadCounts[agentName] || 0) + 1;
    }
    updateAgentNotificationDotExternal(agentName);
  }
}

// Update agent notification dot (callable from other modules)
function updateAgentNotificationDotExternal(agentName) {
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

/** Move a session item to the top of the non-pinned group in the sidebar (like WeChat). */
function reorderSessionToTop(sessionId) {
  // Don't reorder if session is busy (in progress)
  if (state.busySessionIds.has(sessionId)) return;

  // Update updatedAt in state.sessions so future renders stay sorted
  const session = state.sessions.find(s => s.id === sessionId);
  if (!session) return;
  session.updatedAt = Date.now();

  const sessionList = state.dom.sessionList;
  const item = sessionList.querySelector(`.session-item[data-id="${sessionId}"]`);
  if (!item) return;

  const isPinned = state.pinnedSessions.has(sessionId);

  if (isPinned) {
    // Pinned items stay in their group; just move to top of pinned group
    const firstItem = sessionList.querySelector('.session-item.pinned');
    if (firstItem && firstItem !== item) {
      sessionList.insertBefore(item, firstItem);
    }
  } else {
    // Find insertion point: after divider (start of non-pinned group) or top of list
    const divider = sessionList.querySelector('.session-divider');
    if (divider) {
      const nextSibling = divider.nextSibling;
      if (nextSibling !== item) {
        sessionList.insertBefore(item, nextSibling);
      }
    } else {
      // No pinned sessions — insert at the very top
      if (sessionList.firstChild !== item) {
        sessionList.insertBefore(item, sessionList.firstChild);
      }
    }
  }

  // Update the time display to reflect the new activity
  const timeEl = item.querySelector('.session-time');
  if (timeEl) timeEl.textContent = formatSessionTime(session.updatedAt);
}

// Listen for state changes from other modules (dispatched via CustomEvent)
window.addEventListener('session-busy', (e) => {
  updateSessionStatus(e.detail.sessionId);
});

window.addEventListener('session-attention', (e) => {
  setSessionAttention(e.detail.sessionId, e.detail.attention);
});

window.addEventListener('session-unread', (e) => {
  updateSessionStatus(e.detail.sessionId);
  reorderSessionToTop(e.detail.sessionId);
});

window.addEventListener('session-compacting', (e) => {
  updateSessionStatus(e.detail.sessionId);
});

// ===== Feishu Config Panel =====
// Click header session-name to open feishu config

let feishuPanelEl = null;

function openFeishuPanel() {
  if (feishuPanelEl) return; // already open
  const active = state.sessions.find(s => s.id === state.activeSessionId);
  if (!active) return;

  const panel = document.createElement('div');
  panel.id = 'feishu-panel';
  panel.innerHTML = buildFeishuPanelHtml(active);
  document.body.appendChild(panel);
  feishuPanelEl = panel;

  // Close on overlay click
  panel.querySelector('.feishu-overlay').addEventListener('click', closeFeishuPanel);

  // Bind form events
  const form = panel.querySelector('.feishu-form');
  const chatIdInput = form.querySelector('[name="chatId"]');
  const enabledCheck = form.querySelector('[name="enabled"]');

  // Save on submit or chatId blur
  form.addEventListener('submit', (e) => { e.preventDefault(); saveFeishuConfig(active.id); });
  form.querySelector('.feishu-save').addEventListener('click', () => saveFeishuConfig(active.id));
  form.querySelector('.feishu-cancel').addEventListener('click', closeFeishuPanel);
  form.querySelector('.feishu-clear').addEventListener('click', () => {
    sendWs({type: 'updateSessionFeishu', sessionId: active.id, chatId: ''});
    closeFeishuPanel();
  });

  // Request global config status
  sendWs({type: 'getFeishuGlobalConfig'});
}

function buildFeishuPanelHtml(session) {
  const feishu = (session.bridges && session.bridges.feishu) || {};
  const chatId = feishu.chatId || '';
  const isLinked = !!chatId;

  return `
    <div class="feishu-overlay"></div>
    <div class="feishu-panel-content">
      <div class="feishu-header">
        <span class="feishu-title">绑定飞书群聊</span>
        <span class="feishu-session-name">${escapeHtml(session.name)}</span>
      </div>
      <div class="feishu-status" id="feishu-global-status">检查全局配置...</div>
      <form class="feishu-form">
        <label>
          <span class="feishu-label">群聊 Chat ID</span>
          <input type="text" name="chatId" value="${escapeHtml(chatId)}" placeholder="oc_xxx" />
          <span class="feishu-hint">在飞书群设置里找到群的 chat_id，以 oc_ 开头</span>
        </label>
        <div class="feishu-actions">
          <button type="button" class="feishu-clear" ${!isLinked ? 'style="display:none"' : ''}>解除绑定</button>
          <button type="button" class="feishu-cancel">取消</button>
          <button type="button" class="feishu-save">绑定</button>
        </div>
      </form>
    </div>`;
}

function saveFeishuConfig(sessionId) {
  if (!feishuPanelEl) return;
  const form = feishuPanelEl.querySelector('.feishu-form');
  const chatId = form.querySelector('[name="chatId"]').value.trim();
  sendWs({
    type: 'updateSessionFeishu',
    sessionId,
    chatId,
    chatType: 'group',
    enabled: true,
    syncMessages: true,
    notifyEvents: ['aiResponse', 'askUser', 'permissionRequest']
  });
  closeFeishuPanel();
}

function closeFeishuPanel() {
  if (feishuPanelEl) {
    feishuPanelEl.remove();
    feishuPanelEl = null;
  }
}

// Handle global feishu config response
onMessage('feishuGlobalConfig', (data) => {
  if (!feishuPanelEl) return;
  const statusEl = feishuPanelEl.querySelector('#feishu-global-status');
  if (data.configured) {
    statusEl.className = 'feishu-status ok';
    statusEl.textContent = '全局配置 ✓ (appId: ' + data.appId + '...)';
  } else {
    statusEl.className = 'feishu-status warn';
    statusEl.innerHTML = '全局配置未设置 — 请创建 <code>~/.nebflow/feishu.json</code>（含 appId/appSecret）';
  }
});

// Bind click on header session name (called from main.js after DOM refs are set)
export function initFeishuPanel() {
  if (!state.dom.sessionNameEl) return;
  state.dom.sessionNameEl.style.cursor = 'pointer';
  state.dom.sessionNameEl.addEventListener('click', (e) => {
    e.stopPropagation();
    openFeishuPanel();
  });
}

