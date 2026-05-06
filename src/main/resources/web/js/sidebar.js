// sidebar.js — Left panel management: nav tabs, agent list, settings, session sidebar

import state, { LS_SESSIONS_KEY } from './state.js';
import { sendWs } from './ws.js';
import { showAgentModal } from './modal.js';
import { renderMarkdownWithMath, smartScroll } from './utils.js';
import { finishAgent, renderAskUser, setStatus } from './chat.js';
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
      if (tab === 'agents') {
        sendWs({type: 'listAgents'});
      }
      if (tab === 'settings') {
        sendWs({type: 'getConfig'});
        renderSettings();
      }
    });
  });
}

// ---------- Agent Panel ----------
export function renderAgentList() {
  const list = document.getElementById('agent-list');
  list.innerHTML = '';
  state.agentsData.forEach(a => {
    const card = document.createElement('div');
    card.className = 'agent-card';
    const escName = escapeHtml(a.name);
    const escDesc = escapeHtml(a.description || '');
    const builtInSet = new Set(a.builtInTools || []);
    const tools = (a.tools || []).filter(t => t !== '*');
    const toolsHtml = tools.slice(0, 5).map(t => {
      const isBuiltIn = builtInSet.has(t);
      return `<span class="agent-tool-tag${isBuiltIn ? ' builtin' : ''}">${escapeHtml(t)}</span>`;
    }).join('');
    const moreTools = tools.length > 5 ? `<span class="agent-tool-tag">+${tools.length - 5}</span>` : '';
    const subsHtml = (a.subagents && a.subagents.length > 0)
      ? `<div class="agent-card-subs">↳ ${a.subagents.map(s => escapeHtml(s)).join(', ')}</div>` : '';
    card.innerHTML = `
      <div class="agent-card-name">${escName}</div>
      <div class="agent-card-desc">${escDesc}</div>
      <div class="agent-card-tools">${toolsHtml}${moreTools}</div>
      ${subsHtml}
      <div class="agent-card-actions">
        <button class="btn-activate" data-name="${escName}">Chat</button>
        <button class="btn-edit" data-name="${escName}">Edit</button>
      </div>`;
    list.appendChild(card);
  });
  // Wire buttons
  list.querySelectorAll('.btn-activate').forEach(btn => {
    btn.addEventListener('click', () => {
      sendWs({type: 'createAgentSession', name: btn.dataset.name});
    });
  });
  list.querySelectorAll('.btn-edit').forEach(btn => {
    btn.addEventListener('click', () => {
      sendWs({type: 'getAgentConfig', name: btn.dataset.name});
    });
  });
  lucide.createIcons();
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
      <div class="settings-row">
        <span class="settings-label">Language</span>
        <input type="text" id="language-input" class="settings-text-input" placeholder="Auto-detect" value="${state.language || ''}" />
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
  // Language input — send on change
  document.getElementById('language-input')?.addEventListener('change', function() {
    const val = this.value.trim() || null;
    state.language = val;
    sendWs({type: 'setLanguage', language: val});
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
  try {
    const all = (() => { try { return JSON.parse(localStorage.getItem(LS_SESSIONS_KEY) || '{}'); } catch(e) { return {}; } })();
    const currentIds = new Set((sessionData || []).map(s => s.id));
    let changed = false;
    for (const key of Object.keys(all)) {
      if (!currentIds.has(key)) { delete all[key]; changed = true; }
    }
    if (changed) { try { localStorage.setItem(LS_SESSIONS_KEY, JSON.stringify(all)); } catch(e) {} }
  } catch(e) {}

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
    item.innerHTML =
      '<div class="session-info">' +
      '<div class="session-name">' + escapeHtml(s.name) + '</div>' +
      '<div class="session-time">' + formatSessionTime(s.updatedAt || s.createdAt) + '</div>' +
      '</div>' +
      '<div class="session-status ' + statusCls + '">' +
      '<div class="status-spinner"><i data-lucide="loader-2"></i></div>' +
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
  // Update header session name
  const sessionNameEl = state.dom.sessionNameEl;
  const active = state.sessions.find(s => s.id === state.activeSessionId);
  if (active) {
    sessionNameEl.textContent = active.name;
    sessionNameEl.style.display = '';
  } else {
    sessionNameEl.textContent = '';
    sessionNameEl.style.display = 'none';
  }
}

// Save current input box content as draft for the given session
function saveInputDraft(sessionId) {
  if (!sessionId || !state.dom.input) return;
  const text = state.dom.input.value;
  const attachments = state.pendingAttachments;
  if (text || attachments.length > 0) {
    state.sessionInputDrafts[sessionId] = { text, attachments: JSON.parse(JSON.stringify(attachments)) };
  } else {
    delete state.sessionInputDrafts[sessionId];
  }
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
    sendWs({ type: 'getHistory', sessionId: sid, limit: 100 });
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

  // If the session is busy and has a pending askUser, re-render it interactively
  if (isStreaming) {
    const msgs = loadMsgs();
    const lastAskUser = [...msgs].reverse().find(m => m.type === 'askUser');
    if (lastAskUser && lastAskUser.items) {
      renderAskUser(lastAskUser.items, sid);
    }
  }

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
  }

  if (!isBusy) input.focus();
}

export function switchSession(sessionId) {
  // Clear unread + marked unread for this session
  state.unreadSessions.delete(sessionId);
  state.markedUnreadSessions.delete(sessionId);
  persistMarkedUnread();
  updateSessionStatus(sessionId);
  // Save draft for the session we're leaving
  saveInputDraft(state.activeSessionId);
  // Switch active session
  state.activeSessionId = sessionId;
  resetChatForActiveSession();
  // Restore input draft for the new session
  restoreInputDraft(sessionId);
  // Restore cached task list for this session (or clear if none)
  renderTaskList(state.sessionTasks[sessionId] || []);
  sendWs({type: 'switchSession', sessionId});
}

export function deleteSession(sessionId) {
  delete state.sessionInputDrafts[sessionId];
  state.markedUnreadSessions.delete(sessionId);
  state.pinnedSessions.delete(sessionId);
  persistMarkedUnread();
  persistPinned();
  sendWs({type: 'deleteSession', sessionId});
}

// ---------- Session status state machine ----------
// Priority: attention > compacting > busy > marked unread > unread > idle
// Only one indicator visible at a time, controlled by a single CSS class.
// Attention takes priority over compacting so AskUser/permission prompts are visible
// even while the session is still technically compacting.

function getSessionStatusClass(sessionId) {
  if (state.attentionSessions.has(sessionId)) return 'attention';
  if (state.compactingSessionIds.has(sessionId)) return 'compacting';
  if (state.busySessionIds.has(sessionId)) return 'busy';
  if (state.markedUnreadSessions.has(sessionId) || state.unreadSessions.has(sessionId)) return 'unread';
  return '';
}

function persistMarkedUnread() {
  try {
    localStorage.setItem('nebflow_marked_unread', JSON.stringify([...state.markedUnreadSessions]));
  } catch(e) {}
}

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
});

window.addEventListener('session-compacting', (e) => {
  updateSessionStatus(e.detail.sessionId);
});

