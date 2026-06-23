// secondary-chat.js — Secondary panel (画板) initialization only.
// All message rendering is handled by ws.js swap + existing main.js handlers.
import state from './state.js';
import { sendWs } from './ws.js';
import { smartScroll } from './utils.js';
import { renderUserBubble } from './chat.js';

// ── Public API ─────────────────────────────────────────────────────────

export function loadSecondary(sessionId) {
  // Reset secondary streaming state
  state._secStream = { aiText: '', currentAiBubble: null, thinkingText: '', currentThinkingBubble: null };
  const el = document.getElementById('secondary-chat');
  if (el) el.innerHTML = '<div style="padding:24px;color:var(--color-text-muted);text-align:center;font-size:13px">Loading...</div>';
  // Request history — ws.js swap + main.js historyPage handler will render it
  sendWs({ type: 'getHistory', sessionId, limit: 50 });
}

export function sendSecondary() {
  const input = document.getElementById('secondary-input');
  if (!input) return;
  const text = input.value.trim();
  if (!text || !state.secondarySessionId) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  // Render user bubble in secondary chat via direct DOM (simple, no swap needed)
  const chatEl = document.getElementById('secondary-chat');
  if (chatEl) {
    const row = document.createElement('div');
    row.className = 'row user';
    const bubble = document.createElement('div');
    bubble.className = 'bubble user';
    bubble.textContent = text;
    row.appendChild(bubble);
    chatEl.appendChild(row);
    smartScroll();
  }
  // Send to backend (same format as input.js)
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: text, attachments: [], clientMessageId,
      sessionId: state.secondarySessionId,
      chatWidth: chatEl?.clientWidth || 0,
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
