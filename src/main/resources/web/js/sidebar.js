// sidebar.js — Left panel management: nav tabs, agent list, settings, session sidebar

import state, { LS_SESSIONS_KEY } from './state.js';
import { sendWs } from './ws.js';
import { showAgentModal } from './modal.js';
import { renderMarkdownWithMath, smartScroll } from './utils.js';
import { finishAgent } from './chat.js';
import { restoreFromStorage } from './persistence.js';
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
    const toolsHtml = (a.tools || []).slice(0, 5).map(t => `<span class="agent-tool-tag">${escapeHtml(t)}</span>`).join('');
    const moreTools = (a.tools || []).length > 5 ? `<span class="agent-tool-tag">+${a.tools.length - 5}</span>` : '';
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
    resetChatForActiveSession();
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

  // Sort sessions by most recently active (like WeChat)
  const sorted = [...state.sessions].sort((a, b) => {
    const ta = a.updatedAt || a.createdAt || 0;
    const tb = b.updatedAt || b.createdAt || 0;
    return tb - ta;
  });

  sorted.forEach(s => {
    const item = document.createElement('div');
    item.className = 'session-item' + (s.id === state.activeSessionId ? ' active' : '');
    item.dataset.id = s.id;
    const hasUnread = state.unreadSessions.has(s.id);
    const dotCls = hasUnread ? ' session-dot show' : ' session-dot';
    item.innerHTML =
      '<div class="session-info">' +
      '<div class="session-name">' + escapeHtml(s.name) + '</div>' +
      '<div class="session-time">' + formatSessionTime(s.updatedAt || s.createdAt) + '</div>' +
      '</div>' +
      '<div class="' + dotCls + '"></div>' +
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

// Reset chat area for the current activeSessionId (used after session list updates)
function resetChatForActiveSession() {
  state.aiText = '';
  state.currentAiBubble = null;
  Object.keys(state.sessionToolCards).forEach(sid => {
    if (state.sessionToolCards[sid]) state.sessionToolCards[sid].remove();
  });
  state.sessionToolCards = {};
  state.dom.chat.innerHTML = '';
  restoreFromStorage();
  smartScroll();

  // If this session is streaming, restore buffered text
  const sid = state.activeSessionId;
  const isStreaming = state.busySessionId === sid;
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

  // Update busy UI
  const isBusy = isStreaming;
  const { input, sendBtn, stopBtn } = state.dom;
  input.disabled = isBusy;
  sendBtn.style.display = isBusy ? 'none' : 'flex';
  stopBtn.style.display = isBusy ? 'flex' : 'none';
  if (!isBusy) input.focus();
}

export function switchSession(sessionId) {
  // Clear unread for this session
  state.unreadSessions.delete(sessionId);
  // Switch active session
  state.activeSessionId = sessionId;
  resetChatForActiveSession();
  // Restore cached task list for this session (or clear if none)
  renderTaskList(state.sessionTasks[sessionId] || []);
  sendWs({type: 'switchSession', sessionId});
}

export function deleteSession(sessionId) {
  sendWs({type: 'deleteSession', sessionId});
}
