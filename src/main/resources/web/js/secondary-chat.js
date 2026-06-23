// secondary-chat.js — Secondary panel (画板) full chat replication
// Reuses ALL existing rendering functions via state-swap pattern.
import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { smartScroll } from './utils.js';
import {
  appendAiText, finishAi, finishThinking, renderTool, renderToolPending,
  renderAskUser, renderPermissionPrompt, renderRetryStatus, renderUserBubble,
} from './chat.js';
import { restoreFromBackendHistory } from './persistence.js';

// ── Secondary streaming state ──────────────────────────────────────────
const secStream = { aiText: '', currentAiBubble: null, thinkingText: '', currentThinkingBubble: null };

function isSecondary(msg) {
  return state.secondarySessionId && msg.sessionId === state.secondarySessionId;
}

/** Temporarily swap rendering targets so existing functions render into #secondary-chat. */
function withSecondary(fn) {
  const saved = {
    chat: state.dom.chat,
    aiText: state.aiText,
    currentAiBubble: state.currentAiBubble,
    thinkingText: state.thinkingText,
    currentThinkingBubble: state.currentThinkingBubble,
  };
  state.dom.chat = document.getElementById('secondary-chat');
  state.aiText = secStream.aiText;
  state.currentAiBubble = secStream.currentAiBubble;
  state.thinkingText = secStream.thinkingText;
  state.currentThinkingBubble = secStream.currentThinkingBubble;
  try { fn(); }
  finally {
    secStream.aiText = state.aiText;
    secStream.currentAiBubble = state.currentAiBubble;
    secStream.thinkingText = state.thinkingText;
    secStream.currentThinkingBubble = state.currentThinkingBubble;
    state.dom.chat = saved.chat;
    state.aiText = saved.aiText;
    state.currentAiBubble = saved.currentAiBubble;
    state.thinkingText = saved.thinkingText;
    state.currentThinkingBubble = saved.currentThinkingBubble;
  }
}

// ── Status helpers ─────────────────────────────────────────────────────
function setSecondaryStatus(text) {
  const el = document.getElementById('secondary-status-text');
  if (el) el.textContent = text;
  const wrap = document.getElementById('secondary-status-wrap');
  if (wrap) wrap.style.display = text ? 'flex' : 'none';
}

function showSecondarySpinner(show) {
  const el = document.getElementById('secondary-spinner');
  if (el) el.style.display = show ? 'block' : 'none';
}

// ── Public API ─────────────────────────────────────────────────────────

export function loadSecondary(sessionId) {
  secStream.aiText = '';
  secStream.currentAiBubble = null;
  secStream.thinkingText = '';
  secStream.currentThinkingBubble = null;
  const el = document.getElementById('secondary-chat');
  if (el) el.innerHTML = '<div style="padding:24px;color:var(--color-text-muted);text-align:center;font-size:13px">Loading...</div>';
  setSecondaryStatus('');
  showSecondarySpinner(false);
  sendWs({ type: 'getHistory', sessionId, limit: 50 });
}

export function sendSecondary() {
  const input = document.getElementById('secondary-input');
  if (!input) return;
  const text = input.value.trim();
  if (!text || !state.secondarySessionId) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  // Render user bubble via swap (reuses renderUserBubble)
  withSecondary(() => {
    renderUserBubble(text, []);
    smartScroll();
  });
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: text, attachments: [], clientMessageId,
      sessionId: state.secondarySessionId,
      chatWidth: document.getElementById('secondary-chat')?.clientWidth || 0,
    });
  } catch (e) { console.error('[secondary] send failed:', e); }
  input.value = '';
  input.style.height = 'auto';
}

export function initSecondaryChat() {
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
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendSecondary(); }
    });
    input.addEventListener('input', () => {
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    });
  }
}

// ── WebSocket Handlers (coexist with main.js via multi-handler) ────────

onMessage('historyPage', (msg) => {
  if (!isSecondary(msg)) return;
  withSecondary(() => {
    state.dom.chat.innerHTML = '';
    state.pendingInitialLoad = false;
    if (state.sessionToolCards) delete state.sessionToolCards[msg.sessionId];
    restoreFromBackendHistory(msg.messages);
    smartScroll();
  });
});

onMessage('thinking', (msg) => {
  if (!isSecondary(msg)) return;
  showSecondarySpinner(true);
  setSecondaryStatus('Thinking...');
  withSecondary(() => {
    const existing = state.dom.chat.querySelector('.thinking-placeholder');
    if (!state.currentAiBubble && !existing) {
      const row = document.createElement('div');
      row.className = 'row ai';
      state.currentAiBubble = document.createElement('div');
      state.currentAiBubble.className = 'bubble ai thinking-placeholder';
      row.appendChild(state.currentAiBubble);
      state.dom.chat.appendChild(row);
      smartScroll();
    }
  });
});

onMessage('textDelta', (msg) => {
  if (!isSecondary(msg)) return;
  setSecondaryStatus('');
  withSecondary(() => {
    if (state.currentThinkingBubble) finishThinking();
    appendAiText(msg.delta);
  });
});

onMessage('toolCallDetected', (msg) => {
  if (!isSecondary(msg)) return;
  if (msg.name === 'AskUserQuestion') return;
  withSecondary(() => {
    if (state.currentThinkingBubble) finishThinking();
    finishAi();
    renderToolPending(msg.name, msg.sessionId);
  });
});

onMessage('toolStart', (msg) => {
  if (!isSecondary(msg)) return;
  if (msg.label && msg.label.startsWith('AskUser')) return;
  setSecondaryStatus('');
  withSecondary(() => {
    if (state.currentThinkingBubble) finishThinking();
    finishAi();
    renderToolPending(msg.label, msg.sessionId);
  });
});

onMessage('toolEnd', (msg) => {
  if (!isSecondary(msg)) return;
  if (msg.label && msg.label.startsWith('AskUser')) return;
  withSecondary(() => {
    renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input, msg.sessionId);
  });
});

onMessage('textDone', (msg) => {
  if (!isSecondary(msg)) return;
  withSecondary(() => {
    if (state.currentThinkingBubble) finishThinking();
    finishAi();
  });
});

onMessage('done', (msg) => {
  if (!isSecondary(msg)) return;
  secStream.aiText = '';
  secStream.currentAiBubble = null;
  secStream.thinkingText = '';
  secStream.currentThinkingBubble = null;
  showSecondarySpinner(false);
  setSecondaryStatus('');
});

onMessage('askUser', (msg) => {
  if (!isSecondary(msg)) return;
  withSecondary(() => {
    if (state.currentAiBubble) finishAi();
    renderAskUser(msg.items, msg.sessionId);
    smartScroll();
  });
});

onMessage('askPermission', (msg) => {
  if (!isSecondary(msg)) return;
  withSecondary(() => {
    renderPermissionPrompt(msg.toolName, msg.summary, msg.input, msg.sessionId, msg.dangerLevel);
    smartScroll();
  });
});

onMessage('retryStatus', (msg) => {
  if (!isSecondary(msg)) return;
  withSecondary(() => { renderRetryStatus(msg.message); });
});

onMessage('interrupted', (msg) => {
  if (!isSecondary(msg)) return;
  const sid = msg.sessionId;
  withSecondary(() => {
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishThinking();
    finishAi();
  });
  showSecondarySpinner(false);
  setSecondaryStatus('');
});

onMessage('maxTokens', (msg) => {
  if (!isSecondary(msg)) return;
  withSecondary(() => { finishThinking(); finishAi(); });
  showSecondarySpinner(false);
  setSecondaryStatus('Max tokens reached');
});

onMessage('compactStart', (msg) => {
  if (!isSecondary(msg)) return;
  showSecondarySpinner(true);
  setSecondaryStatus('Compacting...');
});

onMessage('compactComplete', (msg) => {
  if (!isSecondary(msg)) return;
  showSecondarySpinner(false);
  setSecondaryStatus('');
});

onMessage('compactFailed', (msg) => {
  if (!isSecondary(msg)) return;
  showSecondarySpinner(false);
  setSecondaryStatus('');
});

onMessage('bridgeUser', (msg) => {
  if (!isSecondary(msg)) return;
  withSecondary(() => {
    renderUserBubble(msg.text, []);
    smartScroll();
  });
});
