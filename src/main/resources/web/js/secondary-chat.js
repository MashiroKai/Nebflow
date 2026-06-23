// secondary-chat.js — Secondary panel (画板) chat rendering
// Reuses ALL existing rendering functions from chat.js / persistence.js
// via a state-swap pattern: temporarily redirect state.dom.chat to #secondary-chat.
import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { smartScroll } from './utils.js';
import {
  appendAiText, finishAi, finishThinking, renderTool, renderToolPending,
} from './chat.js';
import { restoreFromBackendHistory } from './persistence.js';

// ── Secondary streaming state (persists across delta messages) ─────────
const secStream = { aiText: '', currentAiBubble: null, thinkingText: '', currentThinkingBubble: null };

function isSecondary(msg) {
  return state.secondarySessionId && msg.sessionId === state.secondarySessionId;
}

/**
 * Temporarily swap rendering targets so existing functions render into #secondary-chat.
 * Saves/restores all rendering-critical state variables.
 */
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
  try {
    fn();
  } finally {
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

// ── Public API ─────────────────────────────────────────────────────────

export function loadSecondary(sessionId) {
  // Reset streaming state
  secStream.aiText = '';
  secStream.currentAiBubble = null;
  secStream.thinkingText = '';
  secStream.currentThinkingBubble = null;
  // Show loading indicator
  const el = document.getElementById('secondary-chat');
  if (el) el.innerHTML = '<div style="padding:24px;color:var(--color-text-muted);text-align:center;font-size:13px">Loading...</div>';
  // Request history — historyPage handler below will render it
  sendWs({ type: 'getHistory', sessionId, limit: 50 });
}

export function sendSecondary() {
  const input = document.getElementById('secondary-input');
  if (!input) return;
  const text = input.value.trim();
  if (!text || !state.secondarySessionId) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  // Render user bubble using existing chat.js rendering (via swap)
  withSecondary(() => {
    const { chat } = state.dom;
    const row = document.createElement('div');
    row.className = 'row user';
    const bubble = document.createElement('div');
    bubble.className = 'bubble user';
    bubble.textContent = text;
    row.appendChild(bubble);
    chat.appendChild(row);
    smartScroll();
  });
  // Send to backend (same format as input.js)
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: text,
      attachments: [],
      clientMessageId,
      sessionId: state.secondarySessionId,
      chatWidth: document.getElementById('secondary-chat')?.clientWidth || 0,
    });
  } catch (e) {
    console.error('[secondary] send failed:', e);
  }
  input.value = '';
  input.style.height = 'auto';
}

export function initSecondaryChat() {
  const sendBtn = document.getElementById('secondary-send-btn');
  if (sendBtn) {
    sendBtn.addEventListener('click', (e) => { e.preventDefault(); sendSecondary(); });
  }
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

// ── WebSocket Handlers ─────────────────────────────────────────────────
// Main handlers skip rendering for non-active sessions (isActive() returns false).
// These handlers pick up secondary-session messages and render via swap.

onMessage('historyPage', (msg) => {
  if (!isSecondary(msg)) return;
  withSecondary(() => {
    state.dom.chat.innerHTML = '';
    state.pendingInitialLoad = false;
    restoreFromBackendHistory(msg);
    smartScroll();
  });
});

onMessage('thinking', (msg) => {
  if (!isSecondary(msg)) return;
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
  // Reset streaming state for next turn
  secStream.aiText = '';
  secStream.currentAiBubble = null;
  secStream.thinkingText = '';
  secStream.currentThinkingBubble = null;
});
