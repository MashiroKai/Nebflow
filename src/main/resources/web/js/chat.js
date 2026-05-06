// chat.js — Chat rendering module for Nebflow
// All DOM manipulation for messages, bubbles, tool cards, option boxes, and status.

import state, { AGENT_PALETTE } from './state.js';
import { renderMarkdownWithMath, escapeHtml, formatDiff, buildToolDetail, attachToolClick, smartScroll, playSpinner, stopSpinner } from './utils.js';
import { renderWithRegistry } from './cardRegistry.js';

// ---------- Agent color assignment ----------
export function getAgentColor(agentId) {
  if (!state.agentColors[agentId]) {
    state.agentColors[agentId] = AGENT_PALETTE[state.agentColorIdx % AGENT_PALETTE.length];
    state.agentColorIdx++;
  }
  return state.agentColors[agentId];
}

// ---------- Status bar ----------
export function setStatus(text) {
  const { statusText, statusWrap } = state.dom;
  statusText.textContent = text || '';
  statusWrap.classList.add('on');
  playSpinner();
}

export function clearStatus() {
  const { statusWrap } = state.dom;
  statusWrap.classList.remove('on', 'compacting', 'compact-done', 'compact-failed');
  stopSpinner();
}

export function renderRetryStatus(msg) {
  const { chat } = state.dom;
  let el = document.getElementById('retry-status');
  if (!el) {
    el = document.createElement('div');
    el.id = 'retry-status';
    el.className = 'retry-status';
    chat.appendChild(el);
  }
  el.textContent = msg;
  el.style.display = 'block';
  smartScroll();
}

export function clearRetryStatus() {
  const el = document.getElementById('retry-status');
  if (el) el.style.display = 'none';
}

// ---------- Busy toggle (per-session) ----------
export function setBusy(sessionId) {
  if (sessionId) state.busySessionIds.add(sessionId);
  window.dispatchEvent(new CustomEvent('session-busy', { detail: { sessionId, busy: true } }));
  if (sessionId === state.activeSessionId) {
    const { input, sendBtn, stopBtn } = state.dom;
    input.disabled = true;
    sendBtn.style.display = 'none';
    stopBtn.style.display = 'flex';
  }
}

export function clearBusy(sessionId) {
  state.busySessionIds.delete(sessionId);
  window.dispatchEvent(new CustomEvent('session-busy', { detail: { sessionId, busy: false } }));
  if (sessionId === state.activeSessionId) {
    const { input, sendBtn, stopBtn } = state.dom;
    input.disabled = false;
    sendBtn.style.display = 'flex';
    stopBtn.style.display = 'none';
    input.focus();
  }
}

// ---------- User bubble ----------
export function renderUserBubble(text, attachments) {
  const chat = state.dom.chat;
  const row = document.createElement('div');
  row.className = 'row user';

  // Text bubble (separate)
  if (text) {
    const bubble = document.createElement('div');
    bubble.className = 'bubble user';
    const t = document.createElement('div');
    t.textContent = text;
    bubble.appendChild(t);
    row.appendChild(bubble);
  }

  // Attachment bubbles (small gray, below text)
  (attachments || []).forEach(att => {
    const bubble = document.createElement('div');
    bubble.className = 'bubble user att-bubble';
    const tag = document.createElement('span');
    tag.className = 'att-file-tag';
    if (att.type === 'image') {
      tag.textContent = '[image' + (att.name ? ': ' + att.name : '') + ']';
    } else {
      tag.textContent = '[file' + (att.name ? ': ' + att.name : '') + ']';
    }
    bubble.appendChild(tag);
    row.appendChild(bubble);
  });

  chat.appendChild(row);
  chat.scrollTop = chat.scrollHeight;
  return { type: 'user', text, attachments: (attachments || []).map(a => ({ type: a.type, name: a.name, preview: a.preview })) };
}

// ---------- AI text streaming ----------
export function appendAiText(text) {
  const chat = state.dom.chat;
  state.aiText += text;
  if (state.currentAiBubble && state.currentAiBubble.classList.contains('thinking-placeholder')) {
    state.currentAiBubble.classList.remove('thinking-placeholder');
    state.currentAiBubble.innerHTML = '';
  }
  if (!state.currentAiBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    state.currentAiBubble = document.createElement('div');
    state.currentAiBubble.className = 'bubble ai';
    row.appendChild(state.currentAiBubble);
    chat.appendChild(row);
  }
  const askBox = state.currentAiBubble.querySelector('.option-box');
  if (askBox) askBox.remove();
  const cursor = '<span class="cursor"></span>';
  state.currentAiBubble.innerHTML = renderMarkdownWithMath(state.aiText || '') + cursor;
  if (askBox) state.currentAiBubble.appendChild(askBox);
  smartScroll();
}

export function finishAi(durationMs, model) {
  if (state.currentAiBubble) {
    if (!state.aiText) {
      const row = state.currentAiBubble.closest('.row');
      if (row) row.remove();
      state.currentAiBubble = null;
      return null;
    }
    const askBox = state.currentAiBubble.querySelector('.option-box');
    if (askBox) askBox.remove();
    state.currentAiBubble.innerHTML = renderMarkdownWithMath(state.aiText || '');
    if (askBox) state.currentAiBubble.appendChild(askBox);
    if (durationMs != null && durationMs > 0) {
      renderDurationBadge(state.currentAiBubble, durationMs, model);
    }
    const result = { type: 'ai', text: state.aiText, durationMs, model };
    state.currentAiBubble = null;
    state.aiText = '';
    return result;
  }
  return null;
}

/**
 * Format milliseconds into a human-readable duration string.
 * e.g. 5000 -> "5s", 93000 -> "1m 33s", 547000 -> "9m 7s"
 */
export function formatDuration(ms) {
  const totalSeconds = ms / 1000;
  if (totalSeconds < 1) return '< 1s';
  const rounded = Math.round(totalSeconds);
  if (rounded < 60) return rounded + 's';
  const minutes = Math.floor(rounded / 60);
  const seconds = rounded % 60;
  return minutes + 'm ' + seconds + 's';
}


/**
 * Cosmology-themed thinking phrases. {d} is replaced with the formatted duration.
 */
const THINKING_PHRASES = [
  'Thought for {d}',
  'Observed for {d}',
  'Traversed the cosmos for {d}',
  'Charted the stars for {d}',
  'Navigated for {d}',
  'Explored for {d}',
  'Scanned the deep field for {d}',
  'Computed for {d}',
  'Surveyed the void for {d}',
  'Drifted through space for {d}',
  'Pondered for {d}',
  'Illuminated for {d}',
  'Wandered the cosmos for {d}',
  'Gazed into the deep for {d}',
  'Mapped the nebula for {d}',
  'Orbited the problem for {d}',
  'Aligned the constellations for {d}',
  'Reached for the light for {d}',
  'Probed the darkness for {d}',
  'Sailed the stellar winds for {d}',
];

/**
 * Pick a cosmology-themed thinking phrase.
 * @param {number} durationMs - Duration in milliseconds.
 * @param {number} [seed] - Optional seed for deterministic selection.
 * @returns {string} The full badge text including the ✻ prefix.
 */
export function pickThinkingPhrase(durationMs, seed) {
  const idx = seed != null
    ? ((seed % THINKING_PHRASES.length) + THINKING_PHRASES.length) % THINKING_PHRASES.length
    : Math.floor(Math.random() * THINKING_PHRASES.length);
  return '✻ ' + THINKING_PHRASES[idx].replace('{d}', formatDuration(durationMs));
}

/**
 * Render a subtle duration badge below an AI bubble.
 */
export function renderDurationBadge(bubble, durationMs, model) {
  if (!bubble) return;
  const row = bubble.closest('.row');
  if (!row) return;
  const badge = document.createElement('div');
  badge.className = 'duration-badge';
  let text = pickThinkingPhrase(durationMs);
  if (model) text += ' · ' + model;
  badge.textContent = text;
  row.appendChild(badge);
}

// ---------- Multi-agent rendering ----------
export function appendAgentText(agentId, text) {
  const chat = state.dom.chat;
  if (!state.agentBubbles[agentId]) {
    const row = document.createElement('div');
    row.className = 'row ai agent-row';
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai';
    let badge = null;
    if (agentId && agentId !== 'default') {
      badge = document.createElement('div');
      badge.className = 'agent-badge';
      const color = getAgentColor(agentId);
      badge.style.borderColor = color;
      badge.style.color = color;
      badge.textContent = agentId;
      row.appendChild(badge);
    }
    row.appendChild(bubble);
    chat.appendChild(row);
    state.agentBubbles[agentId] = { bubble, text: '', row, badge };
  }
  const a = state.agentBubbles[agentId];
  a.text += text;
  const cursor = '<span class="cursor"></span>';
  a.bubble.innerHTML = renderMarkdownWithMath(a.text) + cursor;
  smartScroll();
}

export function finishAgent(agentId) {
  const a = state.agentBubbles[agentId];
  if (a) {
    if (!a.text || a.text.trim() === '') {
      if (a.row) a.row.remove();
    } else {
      a.bubble.innerHTML = renderMarkdownWithMath(a.text);
    }
  }
  if (state.activeAgentId === agentId) state.activeAgentId = null;
}

// ---------- Tool rendering ----------
export function renderTool(label, summary, content, isError, inputJson, sessionId, truncated) {
  const sid = sessionId || state.activeSessionId;
  // Guard: do not render into a different session's chat
  if (sid && sid !== state.activeSessionId) return null;
  const chat = state.dom.chat;
  const pending = state.sessionToolCards[sid];
  if (pending) {
    pending.remove();
    delete state.sessionToolCards[sid];
  }
  const row = document.createElement('div');
  row.className = 'row tool';
  const card = document.createElement('div');
  card.className = 'tool-card';

  // Try plugin renderer first
  const data = { label, summary, content, isError, input: inputJson, sessionId: sid };
  if (renderWithRegistry(card, data)) {
    row.appendChild(card);
    chat.appendChild(row);
    smartScroll();
    return { type: 'tool', label, summary, content, isError, input: inputJson };
  }

  // Truncation warning badge
  const truncBadge = truncated
    ? '<span class="truncated-badge" title="Output was too large and has been truncated to prevent context overflow">Truncated</span>'
    : '';

  // Fallback to default rendering
  const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                       : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  const diffHtml = formatDiff(content);
  const detailHtml = buildToolDetail(inputJson, label);
  const bodyText = diffHtml ? '' : (content ? escapeHtml(content.length > 120 ? content.slice(0,120) + '...' : content) : '');
  const bodyHtml = (detailHtml + (diffHtml || (bodyText ? '<pre>' + bodyText + '</pre>' : ''))) || '';
  const hasBody = !!bodyHtml;
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + escapeHtml(label) + ' &mdash; ' + escapeHtml(summary) + truncBadge + '</div>' +
    (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();

  if (hasBody) attachToolClick(card);
  return { type: 'tool', label, summary, content, isError, input: inputJson, truncated: !!truncated };
}

export function renderToolPending(label, sessionId) {
  const sid = sessionId || state.activeSessionId;
  // Guard: only render into the active session
  if (sid && sid !== state.activeSessionId) return;
  const chat = state.dom.chat;
  if (state.currentAiBubble && state.currentAiBubble.classList.contains('thinking-placeholder')) {
    const row = state.currentAiBubble.closest('.row');
    if (row) row.remove();
    state.currentAiBubble = null;
    state.aiText = '';
  }
  const pending = state.sessionToolCards[sid];
  if (pending) pending.remove();
  const row = document.createElement('div');
  row.className = 'row tool';
  const card = document.createElement('div');
  card.className = 'tool-card';
  card.style.background = '#e8e8e8';
  card.innerHTML = '<span class="icon"><span class="spinner"></span></span>' +
    '<div class="content"><div class="label">' + escapeHtml(label) + '</div></div>';
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();
  state.sessionToolCards[sid] = row;
}

// ---------- Error ----------
export function renderError(msg) {
  const chat = state.dom.chat;
  const row = document.createElement('div');
  row.className = 'row error';
  const card = document.createElement('div');
  card.className = 'error-card';
  card.textContent = msg;
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();
}

// ---------- Timeout notice with retry ----------
export function renderTimeoutNotice() {
  const chat = state.dom.chat;
  const row = document.createElement('div');
  row.className = 'row error';
  const card = document.createElement('div');
  card.className = 'error-card';
  card.style.display = 'flex';
  card.style.alignItems = 'center';
  card.style.gap = '12px';
  const text = document.createElement('span');
  text.textContent = 'Response timed out';
  card.appendChild(text);
  const btn = document.createElement('button');
  btn.textContent = 'Retry';
  btn.style.cssText = 'padding:4px 12px;border-radius:6px;border:1px solid #888;background:#2a2a2a;color:#eee;cursor:pointer;font-size:13px;font-family:inherit;';
  btn.onmouseenter = () => { btn.style.background = '#3a3a3a'; };
  btn.onmouseleave = () => { btn.style.background = '#2a2a2a'; };
  btn.onclick = () => {
    row.remove();
    // Find last user message from input history and resend
    const history = state.inputHistory;
    const lastMsg = history.length > 0 ? history[history.length - 1] : '';
    if (lastMsg) {
      state.dom.input.value = lastMsg;
      // Import send from input.js dynamically to avoid circular dependency
      import('./input.js').then(({ send }) => send());
    }
  };
  card.appendChild(btn);
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();
}

// ---------- System bubble ----------
export function renderSystemBubble(text) {
  const chat = state.dom.chat;
  const row = document.createElement('div');
  row.className = 'row notice';
  const card = document.createElement('div');
  card.className = 'notice-card notice-info';
  card.textContent = text;
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();
  return { type: 'system', text };
}


// ---------- Universal Option Box ----------
// Renders an inline option picker. Used by AskUser tool, /thinking, permission prompts.
export function showOptions(container, questions, onConfirm, doneLabel, onCancel) {
  const box = document.createElement('div');
  box.className = 'option-box';
  const answers = new Array(questions.length).fill(null);
  const confirmLabel = doneLabel || 'Confirm';

  questions.forEach((item, qi) => {
    const q = document.createElement('div');
    q.className = 'option-q';
    q.textContent = item.question;
    box.appendChild(q);

    const optsDiv = document.createElement('div');
    optsDiv.className = 'option-opts';
    const hasOptions = item.options && item.options.length > 0;

    if (hasOptions) {
      item.options.forEach((opt, oi) => {
        const btn = document.createElement('button');
        btn.className = 'option-btn';
        const isStr = typeof opt === 'string';
        const label = isStr ? opt : opt.label;
        const desc = isStr ? '' : (opt.desc || opt.description || '');
        btn.innerHTML = escapeHtml(label) + (desc ? '<div style="font-size:11px;color:#888;margin-top:2px;font-weight:normal;">' + escapeHtml(desc) + '</div>' : '');
        btn.onclick = () => {
          answers[qi] = label;
          optsDiv.querySelectorAll('.option-btn').forEach((el, i) => {
            el.classList.toggle('picked', i === oi);
          });
          customInput && (customInput.style.display = 'none');
          checkAllAnswered();
        };
        optsDiv.appendChild(btn);
      });
    }

    // "Other" option for custom input (shown when allowOther is true)
    const allowOther = item.allowOther !== false; // default true
    const customInput = document.createElement('textarea');
    customInput.className = 'option-custom-input';
    customInput.placeholder = 'Type your answer...';
    customInput.rows = 2;
    if (!hasOptions) customInput.style.display = ''; // visible by default for open-ended

    let otherBtn = null;
    if (hasOptions && allowOther) {
      otherBtn = document.createElement('button');
      otherBtn.className = 'option-btn';
      otherBtn.textContent = 'Other...';
      customInput.style.display = 'none';
      otherBtn.onclick = () => {
        optsDiv.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
        otherBtn.classList.add('picked');
        customInput.style.display = '';
        customInput.focus();
        if (customInput.value.trim()) {
          answers[qi] = customInput.value.trim();
        }
        checkAllAnswered();
      };
      optsDiv.appendChild(otherBtn);
    } else if (hasOptions) {
      // No "Other" — hide custom input
      customInput.style.display = 'none';
    } else {
      // Open-ended: auto-focus and show input immediately
      answers[qi] = null; // needs to be filled
    }

    customInput.oninput = () => {
      if (customInput.value.trim()) {
        answers[qi] = customInput.value.trim();
        if (hasOptions) {
          optsDiv.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
          otherBtn && otherBtn.classList.add('picked');
        }
        checkAllAnswered();
      }
    };
    optsDiv.appendChild(customInput);
    box.appendChild(optsDiv);
  });

  const btnRow = document.createElement('div');
  btnRow.className = 'option-btn-row';

  const cancelBtn = document.createElement('button');
  cancelBtn.className = 'option-cancel';
  cancelBtn.textContent = 'Cancel';
  cancelBtn.onclick = () => {
    box.querySelectorAll('.option-btn, .option-confirm').forEach(el => { el.disabled = true; });
    cancelBtn.disabled = true;
    confirmBtn.disabled = true;
    if (onCancel) onCancel();
  };

  const confirmBtn = document.createElement('button');
  confirmBtn.className = 'option-confirm';
  confirmBtn.innerHTML = '<i data-lucide="check"></i><span>' + escapeHtml(confirmLabel) + '</span>';
  confirmBtn.disabled = true;
  confirmBtn.onclick = () => {
    box.querySelectorAll('.option-btn').forEach(el => { el.disabled = true; });
    cancelBtn.disabled = true;
    confirmBtn.disabled = true;
    confirmBtn.style.display = 'none';
    cancelBtn.style.display = 'none';

    const ansDiv = document.createElement('div');
    ansDiv.className = 'option-answer';
    ansDiv.textContent = '-> ' + answers.join(', ');
    box.appendChild(ansDiv);

    if (onConfirm) onConfirm(answers);
  };
  btnRow.appendChild(cancelBtn);
  btnRow.appendChild(confirmBtn);
  box.appendChild(btnRow);
  container.appendChild(box);
  if (typeof lucide !== 'undefined') lucide.createIcons();
  smartScroll();

  function checkAllAnswered() {
    confirmBtn.disabled = !answers.every(a => a !== null);
  }
}

// ---------- AskUser ----------
export function renderAskUser(items, askSessionId) {
  console.log('[askUser] rendering', items?.length || 0, 'questions');
  if (!Array.isArray(items) || items.length === 0) {
    renderError('Waiting for question...');
    return { type: 'askUser', items: [] };
  }
  const chat = state.dom.chat;
  const sid = state.activeSessionId;
  if (sid && state.sessionToolCards[sid]) { state.sessionToolCards[sid].remove(); delete state.sessionToolCards[sid]; }
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai';
  row.appendChild(bubble);
  chat.appendChild(row);
  // Use the sessionId from the askUser message, not the currently active session
  const targetSid = askSessionId || state.activeSessionId;
  try {
    showOptions(bubble, items, (answers) => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify({ type: 'askUserAnswer', sessionId: targetSid, answers }));
      }
      window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
    }, 'Confirm', () => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify({ type: 'askUserAnswer', sessionId: targetSid, answers: ['__cancelled__'] }));
      }
      window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
    });
  } catch (e) {
    console.error('[askUser] render failed:', e);
    bubble.textContent = 'Failed to render options. Please try again.';
  }
  return { type: 'askUser', items };
}

// ---------- Permission prompt ----------
export function renderPermissionPrompt(toolName, summary, inputJson, permSessionId) {
  const chat = state.dom.chat;
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai';
  row.appendChild(bubble);
  chat.appendChild(row);

  // Show tool details
  let detail = '';
  try {
    const input = JSON.parse(inputJson || '{}');
    if (input.command) detail = 'Command: ' + input.command;
    else if (input.file_path) detail = 'File: ' + input.file_path;
    else if (input.url) detail = 'URL: ' + input.url;
  } catch (e) {}

  const targetSid = permSessionId || state.activeSessionId;
  const items = [{
    question: 'Allow ' + toolName + '?',
    options: [
      { label: 'Allow', desc: summary + (detail ? ' — ' + detail : '') },
      { label: 'Deny', desc: 'Skip this tool call' }
    ]
  }];
  showOptions(bubble, items, (answers) => {
    const approved = answers[0] === 'Allow';
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: targetSid, approved }));
    }
    window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
  }, 'Confirm', () => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: targetSid, approved: false }));
    }
    window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
  });
  smartScroll();
}

// ---------- Attachment preview ----------
export function renderAttachmentPreview() {
  const attPreview = state.dom.attPreview;
  attPreview.innerHTML = '';
  state.pendingAttachments.forEach((att, idx) => {
    if (att.type === 'image' && att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:')) {
      const wrap = document.createElement('div');
      wrap.style.position = 'relative';
      const img = document.createElement('img');
      img.src = att.preview;
      img.className = 'att-thumb';
      img.onerror = () => { img.style.display = 'none'; };
      wrap.appendChild(img);
      const rm = document.createElement('div');
      rm.className = 'att-remove';
      rm.textContent = 'x';
      rm.onclick = () => {
        state.pendingAttachments.splice(idx, 1);
        renderAttachmentPreview();
      };
      wrap.appendChild(rm);
      attPreview.appendChild(wrap);
    } else {
      const wrap = document.createElement('div');
      wrap.className = 'att-file';
      wrap.innerHTML = '<span>[f]</span><span>' + escapeHtml(att.name) + '</span>';
      const rm = document.createElement('span');
      rm.textContent = ' x';
      rm.style.cursor = 'pointer';
      rm.style.color = '#f44336';
      rm.onclick = () => {
        state.pendingAttachments.splice(idx, 1);
        renderAttachmentPreview();
      };
      wrap.appendChild(rm);
      attPreview.appendChild(wrap);
    }
  });
}

// ---------- /ask bubble rendering ----------
export function renderAskBubble(question) {
  const chat = state.dom.chat;
  const row = document.createElement('div');
  row.className = 'row user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble user';
  const label = document.createElement('div');
  label.className = 'ask-label';
  label.textContent = 'Ask';
  const q = document.createElement('div');
  q.textContent = question;
  bubble.appendChild(label);
  bubble.appendChild(q);
  row.appendChild(bubble);
  chat.appendChild(row);
  smartScroll();
}

export function appendAskAnswer(delta) {
  const chat = state.dom.chat;
  state.askAnswerText += delta;
  if (!state.currentAskBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    state.currentAskBubble = document.createElement('div');
    state.currentAskBubble.className = 'bubble ai';
    const label = document.createElement('div');
    label.className = 'ask-label';
    label.textContent = 'Ask';
    const content = document.createElement('div');
    state.currentAskBubble.appendChild(label);
    state.currentAskBubble.appendChild(content);
    row.appendChild(state.currentAskBubble);
    chat.appendChild(row);
  }
  const contentEl = state.currentAskBubble.querySelector('div:not(.ask-label)');
  if (contentEl) {
    const cursor = '<span class="cursor"></span>';
    contentEl.innerHTML = renderMarkdownWithMath(state.askAnswerText || '') + cursor;
  }
  smartScroll();
}

export function finishAskAnswer() {
  if (state.currentAskBubble) {
    const contentEl = state.currentAskBubble.querySelector('div:not(.ask-label)');
    if (contentEl) {
      contentEl.innerHTML = renderMarkdownWithMath(state.askAnswerText || '');
    }
    state.currentAskBubble = null;
    state.askAnswerText = '';
  }
}

export function renderAskError(msg) {
  // Clean up any in-progress ask bubble
  if (state.currentAskBubble) {
    const row = state.currentAskBubble.closest('.row');
    if (row) row.remove();
    state.currentAskBubble = null;
    state.askAnswerText = '';
  }
  renderError(msg || 'Ask failed');
}

