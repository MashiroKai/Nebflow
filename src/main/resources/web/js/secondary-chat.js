// secondary-chat.js — Secondary panel (画板) initialization only.
// All message rendering is handled by ws.js swap + existing main.js handlers.
import state from './state.js';
import { sendWs } from './ws.js';
import { smartScroll, escapeHtml } from './utils.js';
import { renderUserBubble } from './chat.js';
import { saveMsg } from './persistence.js';

// ── Slash command data (simplified) ───────────────────────────────────
const BUILT_IN_SLASH = [
  { cmd: '/clear',   desc: 'Clear conversation history' },
  { cmd: '/compact', desc: 'Compact conversation context' },
  { cmd: '/model',   desc: 'Switch AI model' },
  { cmd: '/bypass',  desc: 'Toggle bypass-all-permission mode' },
];

let secSlashMatches = [];
let secSlashSelected = 0;

// ── Public API ─────────────────────────────────────────────────────────

export function loadSecondary(sessionId) {
  // Reset secondary streaming state
  state._secStream = { aiText: '', currentAiBubble: null, thinkingText: '', currentThinkingBubble: null };
  const el = document.getElementById('secondary-chat');
  if (el) el.innerHTML = '<div style="padding:24px;color:var(--color-text-muted);text-align:center;font-size:13px">Loading...</div>';
  // Sync button state with current busy status
  showSecondaryBusy(state.busySessionIds.has(sessionId));
  // Request history — ws.js swap + main.js historyPage handler will render it
  sendWs({ type: 'getHistory', sessionId, limit: 50 });
}

export function sendSecondary() {
  const input = document.getElementById('secondary-input');
  if (!input) return;
  const text = input.value.trim();
  if (!text || !state.secondarySessionId) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  // Block if secondary session is already busy
  if (state.busySessionIds.has(state.secondarySessionId)) return;

  const secChat = document.getElementById('secondary-chat');

  // Render user bubble via renderUserBubble (swap state.dom.chat temporarily)
  const origChat = state.dom.chat;
  state.dom.chat = secChat;
  renderUserBubble(text, []);
  state.dom.chat = origChat;
  smartScroll();

  // Persist user message
  saveMsg({ type: 'user', text }, state.secondarySessionId);

  // Send to backend (same format as input.js)
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: text,
      attachments: [],
      clientMessageId,
      sessionId: state.secondarySessionId,
      chatWidth: secChat?.clientWidth || 0,
    });
  } catch (e) {
    console.error('[secondary] send failed:', e);
  }

  // Mark busy and update UI
  state.busySessionIds.add(state.secondarySessionId);
  state.turnExpecting[state.secondarySessionId] = true;
  state.turnStartTimes[state.secondarySessionId] = Date.now();
  showSecondaryBusy(true);

  // Clear input
  input.value = '';
  input.style.height = 'auto';
}

// ── Busy state UI ──────────────────────────────────────────────────────

function showSecondaryBusy(busy) {
  const sendBtn = document.getElementById('secondary-send-btn');
  const stopBtn = document.getElementById('secondary-stop-btn');
  const input = document.getElementById('secondary-input');
  if (sendBtn) sendBtn.style.display = busy ? 'none' : 'flex';
  if (stopBtn) stopBtn.style.display = busy ? 'flex' : 'none';
  if (input) input.disabled = busy;
}

// ── Slash dropdown (simplified autocomplete) ──────────────────────────

function getSecondarySlashCommands() {
  const cmds = [...BUILT_IN_SLASH];
  if (state.skills) {
    state.skills.forEach(skill => {
      cmds.push({
        cmd: '/' + skill.name,
        desc: skill.description || '',
        isSkill: true,
      });
    });
  }
  return cmds;
}

function updateSecondarySlash() {
  const input = document.getElementById('secondary-input');
  const dropdown = document.getElementById('secondary-slash-dropdown');
  if (!input || !dropdown) return;

  if (!input.value.startsWith('/')) {
    closeSecondarySlash(dropdown);
    return;
  }
  const query = input.value.slice(1).toLowerCase();
  secSlashMatches = getSecondarySlashCommands()
    .filter(c => c.cmd.slice(1).toLowerCase().startsWith(query));
  if (secSlashMatches.length === 0) {
    closeSecondarySlash(dropdown);
    return;
  }

  dropdown.innerHTML = '';
  secSlashMatches.forEach((item, i) => {
    const div = document.createElement('div');
    div.className = 'slash-item' + (i === 0 ? ' active' : '');
    const badge = item.isSkill ? '<span class="slash-badge skill">SKILL</span>' : '';
    div.innerHTML =
      '<div style="display:flex;align-items:center">' +
      '<span class="slash-cmd">' + escapeHtml(item.cmd) + '</span>' + badge +
      '</div><span class="slash-desc">' + escapeHtml(item.desc) + '</span>';
    div.onclick = () => pickSecondarySlash(i);
    div.onmouseenter = () => {
      secSlashSelected = i;
      updateSecondaryHighlight(dropdown);
    };
    dropdown.appendChild(div);
  });
  secSlashSelected = 0;
  dropdown.classList.add('on');
}

function closeSecondarySlash(dropdown) {
  if (!dropdown) return;
  dropdown.classList.remove('on');
  secSlashMatches = [];
  secSlashSelected = -1;
}

function updateSecondaryHighlight(dropdown) {
  const items = dropdown.querySelectorAll('.slash-item');
  items.forEach((el, i) => el.classList.toggle('active', i === secSlashSelected));
}

function pickSecondarySlash(index) {
  const input = document.getElementById('secondary-input');
  const dropdown = document.getElementById('secondary-slash-dropdown');
  if (index < 0 || index >= secSlashMatches.length) return;
  const cmd = secSlashMatches[index].cmd;
  if (input) {
    input.value = cmd + ' ';
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
  }
  closeSecondarySlash(dropdown);
}

// ── Initialization ─────────────────────────────────────────────────────

export function initSecondaryChat() {
  // Set initial button states (send visible, stop hidden)
  showSecondaryBusy(false);

  const sendBtn = document.getElementById('secondary-send-btn');
  if (sendBtn) sendBtn.addEventListener('click', (e) => { e.preventDefault(); sendSecondary(); });

  const stopBtn = document.getElementById('secondary-stop-btn');
  if (stopBtn) stopBtn.addEventListener('click', (e) => {
    e.preventDefault();
    if (state.secondarySessionId) sendWs({ type: 'interrupt', sessionId: state.secondarySessionId });
  });

  const input = document.getElementById('secondary-input');
  if (input) {
    input.addEventListener('keydown', (e) => {
      const dropdown = document.getElementById('secondary-slash-dropdown');
      // Slash dropdown navigation
      if (dropdown && dropdown.classList.contains('on')) {
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          secSlashSelected = (secSlashSelected + 1) % secSlashMatches.length;
          updateSecondaryHighlight(dropdown);
          return;
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault();
          secSlashSelected = (secSlashSelected - 1 + secSlashMatches.length) % secSlashMatches.length;
          updateSecondaryHighlight(dropdown);
          return;
        }
        if (e.key === 'Enter') {
          e.preventDefault();
          pickSecondarySlash(secSlashSelected);
          return;
        }
        if (e.key === 'Escape') {
          e.preventDefault();
          closeSecondarySlash(dropdown);
          return;
        }
      }
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendSecondary();
      }
    });
    input.addEventListener('input', () => {
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 200) + 'px';
      updateSecondarySlash();
    });
  }

  // Restore buttons when secondary turn completes
  // (clearBusy in chat.js dispatches this event)
  window.addEventListener('session-busy', (e) => {
    const { sessionId, busy } = e.detail;
    if (sessionId === state.secondarySessionId && !busy) {
      showSecondaryBusy(false);
    }
  });

  // Close slash dropdown when clicking outside
  document.addEventListener('click', (e) => {
    const dropdown = document.getElementById('secondary-slash-dropdown');
    const wrap = document.getElementById('secondary-input-wrap');
    if (dropdown && wrap && !wrap.contains(e.target)) {
      closeSecondarySlash(dropdown);
    }
  });
}
