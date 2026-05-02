// chat.js — Chat rendering module for Nebflow
// All DOM manipulation for messages, bubbles, tool cards, option boxes, and status.

import state, { AGENT_PALETTE } from './state.js';
import { renderMarkdownWithMath, escapeHtml, formatDiff, buildToolDetail, attachToolClick, smartScroll, playSpinner, stopSpinner } from './utils.js';

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
  statusWrap.classList.remove('on');
  stopSpinner();
}

// ---------- Busy toggle (per-session) ----------
export function setBusy(sessionId) {
  const { input, sendBtn, stopBtn } = state.dom;
  state.busySessionId = sessionId;
  const isBusy = sessionId !== null;
  const isCurrentSession = isBusy && sessionId === state.activeSessionId;
  input.disabled = isCurrentSession;
  if (isCurrentSession) {
    sendBtn.style.display = 'none';
    stopBtn.style.display = 'flex';
  } else {
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

export function finishAi() {
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
    const result = { type: 'ai', text: state.aiText };
    state.currentAiBubble = null;
    state.aiText = '';
    return result;
  }
  return null;
}

// ---------- Multi-agent rendering ----------
export function appendAgentText(agentId, text) {
  const chat = state.dom.chat;
  if (!state.agentBubbles[agentId]) {
    const row = document.createElement('div');
    row.className = 'row ai agent-row';
    const badge = document.createElement('div');
    badge.className = 'agent-badge';
    const color = getAgentColor(agentId);
    badge.style.borderColor = color;
    badge.style.color = color;
    badge.textContent = agentId;
    row.appendChild(badge);
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai';
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
    a.bubble.innerHTML = renderMarkdownWithMath(a.text || '');
  }
  if (state.activeAgentId === agentId) state.activeAgentId = null;
}

// ---------- Tool rendering ----------
export function renderTool(label, summary, content, isError, inputJson) {
  const chat = state.dom.chat;
  if (state.currentToolCard) {
    state.currentToolCard.remove();
    state.currentToolCard = null;
  }
  const row = document.createElement('div');
  row.className = 'row tool';
  const card = document.createElement('div');
  card.className = 'tool-card';
  const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                       : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  const diffHtml = formatDiff(content);
  const detailHtml = buildToolDetail(inputJson, label);
  const bodyText = diffHtml ? '' : (content ? escapeHtml(content.length > 120 ? content.slice(0,120) + '...' : content) : '');
  const bodyHtml = (detailHtml + (diffHtml || (bodyText ? '<pre>' + bodyText + '</pre>' : ''))) || '';
  const hasBody = !!bodyHtml;
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + escapeHtml(label) + ' &mdash; ' + escapeHtml(summary) + '</div>' +
    (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();

  if (hasBody) attachToolClick(card);
  return { type: 'tool', label, summary, content, isError, input: inputJson };
}

export function renderToolPending(label) {
  const chat = state.dom.chat;
  if (state.currentAiBubble && state.currentAiBubble.classList.contains('thinking-placeholder')) {
    const row = state.currentAiBubble.closest('.row');
    if (row) row.remove();
    state.currentAiBubble = null;
    state.aiText = '';
  }
  if (state.currentToolCard) state.currentToolCard.remove();
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
  state.currentToolCard = row;
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

// ---------- System bubble ----------
export function renderSystemBubble(text) {
  const chat = state.dom.chat;
  const row = document.createElement('div');
  row.className = 'row error';
  const card = document.createElement('div');
  card.className = 'error-card';
  card.style.background = '#e3f2fd';
  card.style.color = '#1565c0';
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
export function renderAskUser(items) {
  const chat = state.dom.chat;
  if (state.currentToolCard) { state.currentToolCard.remove(); state.currentToolCard = null; }
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai';
  row.appendChild(bubble);
  chat.appendChild(row);
  showOptions(bubble, items, (answers) => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'askUserAnswer', answers }));
    }
  }, 'Confirm', () => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'askUserAnswer', answers: ['__cancelled__'] }));
    }
  });
}

// ---------- Permission prompt ----------
export function renderPermissionPrompt(toolName, summary, inputJson) {
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
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', approved }));
    }
  }, 'Confirm', () => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', approved: false }));
    }
  });
  smartScroll();
}

// ---------- Attachment preview ----------
export function renderAttachmentPreview() {
  const attPreview = state.dom.attPreview;
  attPreview.innerHTML = '';
  state.pendingAttachments.forEach((att, idx) => {
    if (att.type === 'image' && att.preview) {
      const wrap = document.createElement('div');
      wrap.style.position = 'relative';
      const img = document.createElement('img');
      img.src = att.preview;
      img.className = 'att-thumb';
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
