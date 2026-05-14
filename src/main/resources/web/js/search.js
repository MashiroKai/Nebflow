// search.js — History search across all sessions (WeChat-style)

import state from './state.js';
import { sendWs } from './ws.js';
import { switchSession } from './sidebar.js';
let searchTimeout = null;

export function initSearch() {
  const input = document.getElementById('search-input');
  const clearBtn = document.getElementById('search-clear');
  const results = document.getElementById('search-results');
  const sessionList = document.getElementById('session-list');

  input.addEventListener('input', () => {
    const q = input.value.trim();
    clearBtn.classList.toggle('visible', q.length > 0);
    if (searchTimeout) clearTimeout(searchTimeout);
    if (q.length === 0) {
      hideResults();
      return;
    }
    searchTimeout = setTimeout(() => {
      sendWs({ type: 'searchHistory', query: q });
    }, 300);
  });

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      input.value = '';
      clearBtn.classList.remove('visible');
      hideResults();
      input.blur();
    }
  });

  clearBtn.addEventListener('click', () => {
    input.value = '';
    clearBtn.classList.remove('visible');
    hideResults();
    input.focus();
  });

  function hideResults() {
    results.classList.remove('active');
    results.innerHTML = '';
    sessionList.style.display = '';
  }
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

/** Highlight all case-insensitive occurrences of query in text */
function highlight(text, query) {
  if (!query) return escapeHtml(text);
  const escaped = escapeHtml(text);
  const qEscaped = escapeHtml(query);
  const regex = new RegExp('(' + qEscaped.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'gi');
  return escaped.replace(regex, '<mark>$1</mark>');
}

export function renderSearchResults(query, hits) {
  const results = document.getElementById('search-results');
  const sessionList = document.getElementById('session-list');

  if (!query) {
    results.classList.remove('active');
    results.innerHTML = '';
    sessionList.style.display = '';
    return;
  }

  results.classList.add('active');
  sessionList.style.display = 'none';
  results.innerHTML = '';

  // Local folder name search
  const qLower = query.toLowerCase();
  const matchedFolders = (state.folders || []).filter(f => f.name.toLowerCase().includes(qLower));

  if (hits.length === 0 && matchedFolders.length === 0) {
    results.innerHTML = '<div class="search-result-empty">未找到相关记录</div>';
    return;
  }

  // Render matched folders first
  matchedFolders.forEach(folder => {
    const item = document.createElement('div');
    item.className = 'search-result-item';
    item.innerHTML =
      '<div class="search-result-meta">' +
        '<span class="search-result-type folder">文件夹</span>' +
        '<span class="search-result-session">' + escapeHtml(folder.name) + '</span>' +
      '</div>' +
      '<div class="search-result-snippet">' + highlight(folder.name, query) + '</div>';

    item.addEventListener('click', () => {
      const input = document.getElementById('search-input');
      input.value = '';
      document.getElementById('search-clear').classList.remove('visible');
      results.classList.remove('active');
      results.innerHTML = '';
      sessionList.style.display = '';
      // Expand folder and switch to first session inside
      import('./sidebar.js').then(mod => {
        if (!state.expandedFolders.has(folder.id)) {
          mod.toggleFolder(folder.id);
        }
        const firstSession = (state.sessions || []).find(s => s.folderId === folder.id);
        if (firstSession && firstSession.id !== state.activeSessionId) {
          mod.switchSession(firstSession.id);
        }
      });
    });

    results.appendChild(item);
  });

  hits.forEach(hit => {
    const item = document.createElement('div');
    item.className = 'search-result-item';

    let typeLabel = '';
    let typeClass = '';
    if (hit.hitType === 'session') {
      typeLabel = '会话';
      typeClass = 'session';
    } else if (hit.hitType === 'agent') {
      typeLabel = 'Agent';
      typeClass = 'agent-name';
    } else {
      typeLabel = { user: '用户', ai: 'AI', tool: '工具', agent: 'Agent', ask: '询问' }[hit.messageType] || hit.messageType;
      typeClass = hit.messageType;
    }

    item.innerHTML =
      '<div class="search-result-meta">' +
        '<span class="search-result-type ' + typeClass + '">' + typeLabel + '</span>' +
        '<span class="search-result-session">' + escapeHtml(hit.sessionName) + '</span>' +
      '</div>' +
      '<div class="search-result-snippet">' + highlight(hit.snippet, query) + '</div>';

    item.addEventListener('click', () => {
      // Clear search UI
      const input = document.getElementById('search-input');
      input.value = '';
      document.getElementById('search-clear').classList.remove('visible');
      results.classList.remove('active');
      results.innerHTML = '';
      sessionList.style.display = '';

      if (hit.hitType === 'message' && typeof hit.messageIndex === 'number') {
        navigateToMessage(hit.sessionId, hit.messageIndex);
      } else {
        // session/agent hit: just switch to session
        if (hit.sessionId !== state.activeSessionId) {
          switchSession(hit.sessionId);
        }
      }
    });

    results.appendChild(item);
  });
}

function navigateToMessage(sessionId, messageIndex) {
  if (sessionId === state.activeSessionId) {
    // Same session: check if message is in the currently loaded page
    const domIdx = messageIndex - state.historyOffset;
    if (domIdx >= 0 && domIdx < state.dom.chat.querySelectorAll('.row').length) {
      scrollToRow(domIdx);
    } else {
      // Message not in current page — reload with target centered
      state.searchNavigateTarget = { sessionId, messageIndex };
      state.dom.chat.innerHTML = '';
      state.historyOffset = 0;
      const limit = 100;
      const beforeIndex = messageIndex + Math.floor(limit / 2);
      sendWs({ type: 'getHistory', sessionId, limit, beforeIndex });
    }
  } else {
    // Different session: set target and switch (resetChatForActiveSession handles the rest)
    state.searchNavigateTarget = { sessionId, messageIndex };
    switchSession(sessionId);
  }
}

function scrollToRow(domIdx) {
  const rows = state.dom.chat.querySelectorAll('.row');
  if (domIdx >= 0 && domIdx < rows.length) {
    const targetRow = rows[domIdx];
    targetRow.scrollIntoView({ behavior: 'smooth', block: 'center' });
    targetRow.style.transition = 'background 0.3s';
    targetRow.style.background = 'rgba(7,193,96,0.15)';
    setTimeout(() => { targetRow.style.background = ''; }, 2000);
  }
}
