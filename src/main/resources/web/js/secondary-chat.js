// secondary-chat.js — Renders chat content in the secondary panel (画板)
import state from './state.js';
import { sendWs, onMessage } from './ws.js';
import { renderMarkdownWithMath, smartScroll } from './utils.js';

// ── State ──────────────────────────────────────────────────────────────
let streamingText = '';
let currentBubble = null;

// ── Helpers ────────────────────────────────────────────────────────────
function chatEl() { return document.getElementById('secondary-chat'); }

function isSecondary(msg) {
  return state.secondarySessionId && msg.sessionId === state.secondarySessionId;
}

// ── Rendering ──────────────────────────────────────────────────────────

function clearChat() {
  const el = chatEl();
  if (el) el.innerHTML = '';
  streamingText = '';
  currentBubble = null;
}

function appendUserMessage(text) {
  const el = chatEl();
  if (!el) return;
  const row = document.createElement('div');
  row.className = 'row user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble user';
  bubble.textContent = text;
  row.appendChild(bubble);
  el.appendChild(row);
  smartScroll(el);
}

function appendAiMessage(html) {
  const el = chatEl();
  if (!el) return;
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai';
  bubble.innerHTML = html;
  row.appendChild(bubble);
  el.appendChild(row);
  smartScroll(el);
  return bubble;
}

function appendToolResult(label, summary, isError) {
  const el = chatEl();
  if (!el) return;
  const row = document.createElement('div');
  row.className = 'row tool';
  const card = document.createElement('div');
  card.className = 'tool-card';
  const head = document.createElement('div');
  head.className = 'head' + (isError ? ' error' : '');
  head.innerHTML = `<span class="tool-label">${label}</span>${summary ? `<span class="tool-summary">${summary}</span>` : ''}`;
  card.appendChild(head);
  row.appendChild(card);
  el.appendChild(row);
  smartScroll(el);
}

// ── History Rendering ──────────────────────────────────────────────────

function renderHistory(messages) {
  clearChat();
  const el = chatEl();
  if (!el) return;

  for (const m of messages) {
    if (m.type === 'user') {
      const row = document.createElement('div');
      row.className = 'row user';
      const bubble = document.createElement('div');
      bubble.className = 'bubble user';
      bubble.textContent = m.text || '';
      row.appendChild(bubble);
      el.appendChild(row);
    } else if (m.type === 'ai') {
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      if (m.text) {
        bubble.innerHTML = renderMarkdownWithMath(m.text);
      }
      row.appendChild(bubble);
      el.appendChild(row);
    } else if (m.type === 'tool') {
      const row = document.createElement('div');
      row.className = 'row tool';
      const card = document.createElement('div');
      card.className = 'tool-card';
      const head = document.createElement('div');
      head.className = 'head' + (m.isError ? ' error' : '');
      const labelSpan = document.createElement('span');
      labelSpan.className = 'tool-label';
      labelSpan.textContent = m.label || 'tool';
      head.appendChild(labelSpan);
      if (m.summary) {
        const sumSpan = document.createElement('span');
        sumSpan.className = 'tool-summary';
        sumSpan.textContent = m.summary;
        head.appendChild(sumSpan);
      }
      card.appendChild(head);
      if (m.content) {
        const body = document.createElement('div');
        body.className = 'body';
        const pre = document.createElement('pre');
        pre.textContent = m.content.slice(0, 2000);
        body.appendChild(pre);
        card.appendChild(body);
      }
      row.appendChild(card);
      el.appendChild(row);
    }
  }
  smartScroll(el);
}

// ── Public API ─────────────────────────────────────────────────────────

export function loadSecondary(sessionId, sessionName) {
  clearChat();
  // Show loading indicator
  const el = chatEl();
  if (el) {
    el.innerHTML = '<div style="padding:24px;color:var(--color-text-muted);text-align:center;font-size:13px">Loading...</div>';
  }
  // Request history from backend
  sendWs({ type: 'getHistory', sessionId, limit: 50 });
}

// ── Send message ───────────────────────────────────────────────────────

export function sendSecondary() {
  const input = document.getElementById('secondary-input');
  if (!input) return;
  const text = input.value.trim();
  if (!text || !state.secondarySessionId) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;

  // Render user message
  appendUserMessage(text);

  // Send to backend (same format as main input.js)
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: text,
      attachments: [],
      clientMessageId,
      sessionId: state.secondarySessionId,
      chatWidth: chatEl()?.clientWidth || 0
    });
  } catch (e) {
    console.error('[secondary] send failed:', e);
  }

  input.value = '';
  input.style.height = 'auto';
}

// ── WebSocket Handlers ─────────────────────────────────────────────────
// These fire alongside main.js handlers. Main handlers skip messages
// where sessionId !== state.activeSessionId, so secondary messages
// are naturally ignored by main and picked up here.

onMessage('historyPage', (msg) => {
  if (!isSecondary(msg)) return;
  renderHistory(msg.messages || []);
});

onMessage('textDelta', (msg) => {
  if (!isSecondary(msg)) return;
  if (!currentBubble) {
    // Start new AI bubble
    streamingText = '';
    const el = chatEl();
    if (!el) return;
    // Remove loading indicator
    const loader = el.querySelector('[style*="Loading"]');
    if (loader) loader.remove();

    const row = document.createElement('div');
    row.className = 'row ai';
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai';
    row.appendChild(bubble);
    el.appendChild(row);
    currentBubble = bubble;
  }
  streamingText += msg.delta;
  currentBubble.innerHTML = renderMarkdownWithMath(streamingText);
  smartScroll(chatEl());
});

onMessage('textDone', (msg) => {
  if (!isSecondary(msg)) return;
  currentBubble = null;
  streamingText = '';
});

onMessage('done', (msg) => {
  if (!isSecondary(msg)) return;
  currentBubble = null;
  streamingText = '';
});

onMessage('toolStart', (msg) => {
  if (!isSecondary(msg)) return;
  // Simplified: show tool started indicator
});

onMessage('toolEnd', (msg) => {
  if (!isSecondary(msg)) return;
  appendToolResult(msg.label, msg.summary, msg.isError);
});

// ── Init ───────────────────────────────────────────────────────────────

export function initSecondaryChat() {
  const sendBtn = document.getElementById('secondary-send-btn');
  if (sendBtn) {
    sendBtn.addEventListener('click', (e) => {
      e.preventDefault();
      sendSecondary();
    });
  }
  const input = document.getElementById('secondary-input');
  if (input) {
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendSecondary();
      }
    });
    // Auto-resize textarea
    input.addEventListener('input', () => {
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    });
  }
}
