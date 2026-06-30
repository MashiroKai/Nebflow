// chat.js — Chat rendering module for Nebflow
// All DOM manipulation for messages, bubbles, tool cards, option boxes, and status.

import state, { AGENT_PALETTE } from './state.js';
import { activeView, setActiveView } from './chatView.js';
import { renderMarkdownWithMath, escapeHtml, buildToolDetail, attachToolClick, smartScroll, playSpinner, stopSpinner, localizeToolLabel, localizeToolSummary, renderHighlightedContent } from './utils.js';
import { renderWithRegistry } from './cardRegistry.js';
import { t } from './i18n.js';

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
  const { statusText, statusWrap } = activeView.dom;
  if (statusText) statusText.textContent = text || '';
  if (statusWrap) statusWrap.classList.add('on');
  playSpinner();
}

export function clearStatus() {
  const { statusWrap } = activeView.dom;
  if (statusWrap) statusWrap.classList.remove('on');
  stopSpinner();
}

export function renderRetryStatus(msg) {
  const { chat } = activeView.dom;
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
  if (activeView && activeView.sessionId === sessionId) {
    const { sendBtn, stopBtn } = activeView.dom;
    sendBtn.style.display = 'none';
    stopBtn.style.display = 'flex';
  }
}

export function clearBusy(sessionId) {
  state.busySessionIds.delete(sessionId);
  window.dispatchEvent(new CustomEvent('session-busy', { detail: { sessionId, busy: false } }));
  if (activeView && activeView.sessionId === sessionId) {
    const { input, sendBtn, stopBtn } = activeView.dom;
    sendBtn.style.display = 'flex';
    stopBtn.style.display = 'none';
    input.focus();
  }
}

// ---------- User bubble ----------
export function renderUserBubble(text, attachments) {
  const chat = activeView.dom.chat;
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
  const chat = activeView.dom.chat;
  activeView.stream.aiText += text;
  if (activeView.stream.currentAiBubble && activeView.stream.currentAiBubble.classList.contains('thinking-placeholder')) {
    if (window.__stopThinkingTimer) window.__stopThinkingTimer();
    activeView.stream.currentAiBubble.classList.remove('thinking-placeholder');
    activeView.stream.currentAiBubble.innerHTML = '';
  }
  if (!activeView.stream.currentAiBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    activeView.stream.currentAiBubble = document.createElement('div');
    activeView.stream.currentAiBubble.className = 'bubble ai';
    row.appendChild(activeView.stream.currentAiBubble);
    chat.appendChild(row);
  }
  const askBox = activeView.stream.currentAiBubble.querySelector('.option-box');
  if (askBox) askBox.remove();
  const cursor = '<span class="cursor"></span>';
  activeView.stream.currentAiBubble.innerHTML = renderMarkdownWithMath(activeView.stream.aiText || '') + cursor;
  if (askBox) activeView.stream.currentAiBubble.appendChild(askBox);
  smartScroll();
}

export function finishAi(durationMs, model) {
  if (activeView.stream.currentAiBubble) {
    // Diagnostic: warn if finishAi is called while streaming is active.
    // This helps catch any code path that prematurely resets the bubble.
    const sinceActivity = Date.now() - (state.lastStreamActivity || 0);
    if (sinceActivity < 15000 && activeView.stream.aiText) {
      console.warn('[finishAi] Called during active streaming'
        + ` (${sinceActivity}ms since last delta, textLen=${activeView.stream.aiText.length})`);
    }
    if (!activeView.stream.aiText || !activeView.stream.aiText.trim()) {
      const row = activeView.stream.currentAiBubble.closest('.row');
      if (row) row.remove();
      activeView.stream.currentAiBubble = null;
      activeView.stream.aiText = '';
      return null;
    }
    const askBox = activeView.stream.currentAiBubble.querySelector('.option-box');
    if (askBox) askBox.remove();
    activeView.stream.currentAiBubble.innerHTML = renderMarkdownWithMath(activeView.stream.aiText || '');
    if (askBox) activeView.stream.currentAiBubble.appendChild(askBox);
    if (durationMs != null && durationMs > 0) {
      const seed = activeView.dom.chat.querySelectorAll('.duration-badge').length;
      renderDurationBadge(activeView.stream.currentAiBubble, durationMs, model, seed);
    }
    const result = { type: 'ai', text: activeView.stream.aiText, durationMs, model };
    activeView.stream.currentAiBubble = null;
    activeView.stream.aiText = '';
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
 * Format duration for live timer display (always ticking, no rounding).
 * e.g. 3500 -> "3s", 65000 -> "1m 5s", 3700000 -> "1h 1m 40s"
 */
export function formatLiveDuration(ms) {
  const totalSeconds = Math.floor(ms / 1000);
  if (totalSeconds < 60) return totalSeconds + 's';
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return minutes + 'm ' + seconds + 's';
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return hours + 'h ' + mins + 'm ' + seconds + 's';
}

/**
 * Cosmology-themed thinking phrases — now powered by i18n.
 * Keys: think.0 through think.18. {d} is replaced with the formatted duration.
 */
const THINKING_COUNT = 19;

/**
 * Pick a cosmology-themed thinking phrase with duration embedded.
 * e.g. "Counted some stars for 12s"
 * @param {number} durationMs
 * @param {number} [seed]
 * @returns {string} The full phrase text with duration.
 */
export function pickThinkingPhrase(durationMs, seed) {
  const idx = seed != null
    ? ((seed % THINKING_COUNT) + THINKING_COUNT) % THINKING_COUNT
    : Math.floor(Math.random() * THINKING_COUNT);
  return '✻ ' + t('think.' + idx, { d: formatDuration(durationMs) });
}

/**
 * Create a duration badge DOM element (pill style).
 * Shows the full phrase with duration embedded, plus optional model tag.
 * @param {number} durationMs
 * @param {string} [model]
 * @param {number} [seed]
 * @returns {HTMLElement}
 */
export function createDurationBadgeElement(durationMs, model, seed) {
  const badge = document.createElement('div');
  badge.className = 'duration-badge';

  const phraseSpan = document.createElement('span');
  phraseSpan.className = 'duration-badge-text';
  phraseSpan.textContent = pickThinkingPhrase(durationMs, seed);
  badge.appendChild(phraseSpan);

  if (model) {
    const div = document.createElement('span');
    div.className = 'duration-badge-divider';
    badge.appendChild(div);
    const modelSpan = document.createElement('span');
    modelSpan.className = 'duration-badge-model';
    modelSpan.textContent = model;
    badge.appendChild(modelSpan);
  }

  return badge;
}

/**
 * Render a subtle duration badge below an AI bubble.
 */
export function renderDurationBadge(bubble, durationMs, model, seed) {
  if (!bubble) return;
  const row = bubble.closest('.row');
  if (!row) return;
  const badge = createDurationBadgeElement(durationMs, model, seed);
  row.appendChild(badge);
}

// ---------- Multi-agent rendering ----------
export function appendAgentText(agentId, text) {
  const chat = activeView.dom.chat;
  if (!activeView.stream.agentBubbles[agentId]) {
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
    activeView.stream.agentBubbles[agentId] = { bubble, text: '', row, badge };
  }
  const a = activeView.stream.agentBubbles[agentId];
  a.text += text;
  const cursor = '<span class="cursor"></span>';
  a.bubble.innerHTML = renderMarkdownWithMath(a.text) + cursor;
  smartScroll();
}

export function finishAgent(agentId) {
  const a = activeView.stream.agentBubbles[agentId];
  if (a) {
    if (!a.text || a.text.trim() === '') {
      if (a.row) a.row.remove();
    } else {
      a.bubble.innerHTML = renderMarkdownWithMath(a.text);
    }
  }
  if (activeView.stream.activeAgentId === agentId) activeView.stream.activeAgentId = null;
}

// ---------- Tool rendering ----------
export function renderTool(label, summary, content, isError, inputJson, sessionId) {
  const sid = sessionId || activeView.sessionId;
  const chat = activeView.dom.chat;
  const pending = state.sessionToolCards[sid];
  if (pending) {
    pending.remove();
    delete state.sessionToolCards[sid];
  }
  const row = document.createElement('div');
  row.className = 'row tool';
  // Mesh tool marker
  if (label && label.startsWith('[Mesh]')) row.classList.add('mesh-row');
  const card = document.createElement('div');
  card.className = 'tool-card';

  // Try HTML card renderer first
  // Always prefer `content` (server-processed, includes ___CARD_HTML___ marker with
  // embedLocalFiles-processed /api/nf-file URLs). Previously inputJson.html was used
  // for Card tools, but that's the raw LLM input before server-side file embedding.
  const cardData = content || '';
  if (renderWithRegistry(card, cardData, label)) {
    card.classList.add('tool-card--html');
    row.appendChild(card);
    chat.appendChild(row);
    smartScroll();
    return { type: 'tool', label, summary, content, isError, input: inputJson };
  }

  // Fallback to default rendering
  const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                       : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  const detailHtml = buildToolDetail(inputJson, label);
  // Render full content in body with syntax highlighting (Read/Grep only).
  // Body is hidden by default, click to expand shows full content with scroll for long output.
  const highlightHtml = content ? renderHighlightedContent(content, label) : null;
  const bodyHtml = (detailHtml + (highlightHtml || (content ? '<pre class="tool-body-pre">' + escapeHtml(content) + '</pre>' : ''))) || '';
  const hasBody = !!bodyHtml;
  const localLabel = localizeToolLabel(label);
  const localSummary = localizeToolSummary(summary, label);
  const labelParts = localLabel.split('\n', 2);
  const truncBadge = ''; // placeholder for future truncated content indicator
  // Device tag — subtle indicator when tool runs on a remote device
  let deviceTag = '';
  if (inputJson) {
    try {
      const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
      if (inp.device) deviceTag = '<span class="tool-device-tag">' + escapeHtml(String(inp.device)) + '</span>';
    } catch {}
  }
  const labelHtml = escapeHtml(labelParts[0]) + ' &mdash; ' + escapeHtml(localSummary) + truncBadge + deviceTag
    + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + labelHtml + '</div>' +
    (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();

  if (hasBody) attachToolClick(card);
  return { type: 'tool', label, summary, content, isError, input: inputJson };
}

export function renderToolPending(label, sessionId) {
  const sid = sessionId || activeView.sessionId;
  const chat = activeView.dom.chat;
  if (activeView.stream.currentAiBubble && activeView.stream.currentAiBubble.classList.contains('thinking-placeholder')) {
    if (window.__stopThinkingTimer) window.__stopThinkingTimer();
    const row = activeView.stream.currentAiBubble.closest('.row');
    if (row) row.remove();
    activeView.stream.currentAiBubble = null;
    activeView.stream.aiText = '';
  }

  // If a pending card already exists for this session, update it in-place
  // to avoid spinner flicker between toolCallDetected → toolStart events.
  // Defense: if the DOM node was removed (e.g. historyPage cleared innerHTML
  // without clearing sessionToolCards), treat it as non-existent so a fresh
  // card is created.
  const existing = state.sessionToolCards[sid];
  if (existing && existing.isConnected) {
    const labelEl = existing.querySelector('.label');
    if (labelEl) {
      const localLabel = localizeToolLabel(label);
      const labelParts = localLabel.split('\n', 2);
      labelEl.innerHTML = escapeHtml(labelParts[0])
        + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
    }
    return;
  }

  const row = document.createElement('div');
  row.className = 'row tool';
  if (label && label.startsWith('[Mesh]')) row.classList.add('mesh-row');
  const card = document.createElement('div');
  card.className = 'tool-card tool-card--pending';
  const localLabel = localizeToolLabel(label);
  const labelParts = localLabel.split('\n', 2);
  const labelHtml = escapeHtml(labelParts[0])
    + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
  card.innerHTML = '<span class="icon"><span class="spinner"></span></span>' +
    '<div class="content"><div class="label">' + labelHtml + '</div></div>';
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();
  state.sessionToolCards[sid] = row;
}

// ---------- Error ----------
export function renderError(msg) {
  const chat = activeView.dom.chat;
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
  const v = activeView; // capture before callback
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row error';
  const card = document.createElement('div');
  card.className = 'error-card';
  card.style.display = 'flex';
  card.style.alignItems = 'center';
  card.style.gap = '12px';
  const text = document.createElement('span');
  text.textContent = t('chat.timeout');
  card.appendChild(text);
  const btn = document.createElement('button');
  btn.textContent = t('chat.retry');
  btn.style.cssText = 'padding:4px 12px;border-radius:6px;border:1px solid var(--color-frame-border);background:var(--color-frame-hover);color:var(--color-frame-text);cursor:pointer;font-size:13px;font-family:inherit;';
  btn.onmouseenter = () => { btn.style.background = 'var(--color-frame-active)'; };
  btn.onmouseleave = () => { btn.style.background = 'var(--color-frame-hover)'; };
  btn.onclick = () => {
    row.remove();
    // Find last user message from input history and resend
    const history = state.inputHistory;
    const lastMsg = history.length > 0 ? history[history.length - 1] : '';
    if (lastMsg) {
      v.dom.input.value = lastMsg;
      import('./input.js').then(({ send }) => { setActiveView(v); send(); });
    }
  };
  card.appendChild(btn);
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();
}

// ---------- System bubble ----------
export function renderSystemBubble(text) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row notice';
  const card = document.createElement('div');
  card.className = 'notice-card notice-info';
  card.textContent = text;
  row.appendChild(card);
  chat.appendChild(row);
  smartScroll();
  // Persist to localStorage and backend (via recording ws send)
  import('./persistence.js').then(({ saveMsg }) => saveMsg({type: 'system', content: text}));
  return { type: 'system', text };
}


// ---------- Universal Option Box ----------
// Renders an inline option picker. Used by AskUser tool, /thinking, permission prompts.
const ASKUSER_DRAFTS_KEY = 'nebflow_askuser_drafts';

function loadAskDrafts(sid) {
  try { return JSON.parse(localStorage.getItem(ASKUSER_DRAFTS_KEY))?.[sid] || {}; } catch { return {}; }
}

function saveAskDraft(sid, qi, value) {
  try {
    const all = JSON.parse(localStorage.getItem(ASKUSER_DRAFTS_KEY)) || {};
    if (!all[sid]) all[sid] = {};
    if (value) all[sid][qi] = value;
    else delete all[sid][qi];
    localStorage.setItem(ASKUSER_DRAFTS_KEY, JSON.stringify(all));
  } catch { /* ignore */ }
}

function clearAskDrafts(sid) {
  try {
    const all = JSON.parse(localStorage.getItem(ASKUSER_DRAFTS_KEY)) || {};
    delete all[sid];
    localStorage.setItem(ASKUSER_DRAFTS_KEY, JSON.stringify(all));
  } catch { /* ignore */ }
}

export function showOptions(container, questions, onConfirm, doneLabel, onCancel, askSessionId) {
  const box = document.createElement('div');
  box.className = 'option-box';
  const answers = new Array(questions.length).fill(null);
  const confirmLabel = doneLabel || t('chat.confirm');

  // Restore saved drafts for this session
  const saved = askSessionId ? loadAskDrafts(askSessionId) : {};

  questions.forEach((item, qi) => {
    const q = document.createElement('div');
    q.className = 'option-q';
    q.innerHTML = item.question;
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
        btn.innerHTML = escapeHtml(label) + (desc ? '<div class="option-desc">' + escapeHtml(desc) + '</div>' : '');
        btn.onclick = () => {
          answers[qi] = label;
          optsDiv.querySelectorAll('.option-btn').forEach((el, i) => {
            el.classList.toggle('picked', i === oi);
          });
          customInput && (customInput.style.display = 'none');
          customInput.value = '';
          if (askSessionId) saveAskDraft(askSessionId, qi, '');
          checkAllAnswered();
        };
        optsDiv.appendChild(btn);
      });
    }

    // "Other" option for custom input (shown when allowOther is true)
    const allowOther = item.allowOther !== false; // default true
    const customInput = document.createElement('textarea');
    customInput.className = 'option-custom-input';
    customInput.placeholder = t('chat.typeAnswer');
    customInput.rows = 2;

    // Restore saved draft text for this question
    const savedVal = saved[qi];
    if (savedVal) {
      customInput.value = savedVal;
      answers[qi] = savedVal;
    }

    if (!hasOptions) customInput.style.display = ''; // visible by default for open-ended

    let otherBtn = null;
    if (hasOptions && allowOther) {
      otherBtn = document.createElement('button');
      otherBtn.className = 'option-btn';
      otherBtn.textContent = t('chat.other');
      // If a custom draft exists, auto-select "Other" for this option set
      if (savedVal && !item.options.some(o => (typeof o === 'string' ? o : o.label) === savedVal)) {
        otherBtn.classList.add('picked');
        customInput.style.display = '';
      } else {
        customInput.style.display = 'none';
      }
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
      const val = customInput.value.trim();
      if (val) {
        answers[qi] = val;
        if (hasOptions) {
          optsDiv.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
          otherBtn && otherBtn.classList.add('picked');
        }
      } else if (hasOptions && !otherBtn?.classList.contains('picked')) {
        // Don't clear answer if a preset option is selected
      } else {
        answers[qi] = null;
      }
      if (askSessionId) saveAskDraft(askSessionId, qi, val);
      checkAllAnswered();
    };
    optsDiv.appendChild(customInput);
    box.appendChild(optsDiv);
  });

  const btnRow = document.createElement('div');
  btnRow.className = 'option-btn-row';

  const cancelBtn = document.createElement('button');
  cancelBtn.className = 'option-cancel';
  cancelBtn.textContent = t('chat.cancel');
  cancelBtn.onclick = () => {
    box.querySelectorAll('.option-btn, .option-confirm').forEach(el => { el.disabled = true; });
    cancelBtn.disabled = true;
    confirmBtn.disabled = true;
    if (askSessionId) clearAskDrafts(askSessionId);
    if (onCancel) onCancel();
  };

  const confirmBtn = document.createElement('button');
  confirmBtn.className = 'option-confirm';
  confirmBtn.innerHTML = '<i data-lucide="check"></i><span>' + escapeHtml(confirmLabel) + '</span>';
  confirmBtn.disabled = !answers.every(a => a !== null);
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

    if (askSessionId) clearAskDrafts(askSessionId);
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
  if (!Array.isArray(items) || items.length === 0) {
    renderError(t('chat.waitingQuestion'));
    return { type: 'askUser', items: [] };
  }
  const chat = activeView.dom.chat;
  const sid = askSessionId || activeView.sessionId;
  if (sid && state.sessionToolCards[sid]) { state.sessionToolCards[sid].remove(); delete state.sessionToolCards[sid]; }
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai';
  row.appendChild(bubble);
  chat.appendChild(row);
  // Use the sessionId from the askUser message, not the currently active session
  const targetSid = askSessionId || activeView.sessionId;
  try {
    showOptions(bubble, items, (answers) => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify({ type: 'askUserAnswer', sessionId: targetSid, answers }));
      }
      window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
    }, t('chat.confirm'), () => {
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify({ type: 'askUserAnswer', sessionId: targetSid, answers: ['__cancelled__'] }));
      }
      window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
    }, targetSid);
  } catch (e) {
    console.error('[askUser] render failed:', e);
    bubble.textContent = t('chat.failedRender');
  }
  return { type: 'askUser', items };
}

// ---------- Permission prompt ----------
export function renderPermissionPrompt(toolName, summary, inputJson, permSessionId, dangerLevel) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row ai';
  const bubble = document.createElement('div');
  bubble.className = 'bubble ai';

  // Danger level decorations
  const level = dangerLevel || 0;

  // Parse input to extract detail and check danger
  let detail = '';
  let isDangerous = level >= 2;
  try {
    const input = JSON.parse(inputJson || '{}');
    if (input.command) {
      detail = input.command;
    } else if (input.file_path) detail = input.file_path;
    else if (input.url) detail = input.url;
  } catch (e) {}

  // Danger icons (shared SVG shapes, no text — text comes from i18n)
  const warningIcon = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--color-warning)" stroke-width="2.5"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>';
  const dangerIcon = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--color-error)" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>';
  const criticalIcon = dangerIcon; // same icon, text differentiates

  const dangerConfigs = {
    1: { cls: 'perm-warning', icon: warningIcon, i18nKey: 'chat.permLevel.warning' },
    2: { cls: 'perm-dangerous', icon: dangerIcon, i18nKey: 'chat.permLevel.dangerous' },
    3: { cls: 'perm-critical', icon: criticalIcon, i18nKey: 'chat.permLevel.critical' }
  };
  const dangerConf = dangerConfigs[level];
  if (dangerConf) {
    bubble.classList.add(dangerConf.cls);
    const banner = document.createElement('div');
    banner.className = 'perm-danger-banner';
    // i18n label with {detail} placeholder for dangerous/critical levels
    const bannerText = t(dangerConf.i18nKey, { detail: detail || '' });
    banner.innerHTML = dangerConf.icon + '<span>' + escapeHtml(bannerText) + '</span>';
    bubble.appendChild(banner);
  }

  row.appendChild(bubble);
  chat.appendChild(row);

  const targetSid = permSessionId || activeView.sessionId;

  // If bypassAll is enabled, auto-approve immediately
  if (state.bypassAllPermission) {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: targetSid, approved: true }));
    }
    const autoBadge = document.createElement('div');
    autoBadge.className = 'perm-auto-approved';
    autoBadge.textContent = t('chat.autoApproved');
    bubble.appendChild(autoBadge);
    window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
    smartScroll();
    return;
  }

  // Build the question text
  let questionText = t('chat.allowTool', { tool: toolName });
  if (detail) {
    questionText = t('chat.allowTool', { tool: toolName }) + '  <code class="perm-detail-code">' + escapeHtml(detail) + '</code>';
  }

  const allowLabel = t('chat.allow');
  const allowDesc = isDangerous ? t('chat.permExecCmd') : (summary || '');
  const items = [{
    question: questionText,
    options: [
      { label: allowLabel, desc: allowDesc },
      { label: t('chat.deny'), desc: t('chat.skipTool') }
    ]
  }];
  showOptions(bubble, items, (answers) => {
    const approved = answers[0] === allowLabel;
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: targetSid, approved }));
    }
    // Track answered permission: prevents re-creating interactive prompt on
    // session switch-back while tool is still executing (askPermission is still
    // the last history entry until toolEnd is recorded).
    state.answeredPermissions.add(targetSid);
    // Remove the permission prompt row from DOM immediately after answering
    row.remove();
    window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
  }, t('chat.confirm'), () => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: targetSid, approved: false }));
    }
    // Track denied permission (same reason as above)
    state.answeredPermissions.add(targetSid);
    // Remove the permission prompt row from DOM immediately after denying
    row.remove();
    window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
  });
  smartScroll();
}

// ---------- Attachment preview ----------
export function renderAttachmentPreview(target) {
  // target: optional { attPreviewEl, attachments } for non-primary windows.
  // Defaults to the primary window's attPreview + pendingAttachments so existing
  // call sites are unaffected.
  const attPreview = (target && target.attPreviewEl) || activeView.dom.attPreview;
  const attachments = (target && target.attachments) || activeView.pendingAttachments;
  if (!attPreview) return;
  attPreview.innerHTML = '';
  attachments.forEach((att, idx) => {
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
        attachments.splice(idx, 1);
        renderAttachmentPreview(target);
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
        attachments.splice(idx, 1);
        renderAttachmentPreview(target);
      };
      wrap.appendChild(rm);
      attPreview.appendChild(wrap);
    }
  });
}

// ---------- /ask bubble rendering ----------
export function renderAskBubble(question) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble user';
  const label = document.createElement('div');
  label.className = 'ask-label';
  label.textContent = t('chat.askLabel');
  const q = document.createElement('div');
  q.textContent = question;
  bubble.appendChild(label);
  bubble.appendChild(q);
  row.appendChild(bubble);
  chat.appendChild(row);
  smartScroll();
}

export function renderSkillBubble(skillName, text) {
  const chat = activeView.dom.chat;
  const row = document.createElement('div');
  row.className = 'row user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble user';
  const label = document.createElement('div');
  label.className = 'ask-label';
  label.textContent = t('chat.skillLabel', { skill: skillName });
  const content = document.createElement('div');
  content.textContent = text;
  bubble.appendChild(label);
  bubble.appendChild(content);
  row.appendChild(bubble);
  chat.appendChild(row);
  smartScroll();
}

export function appendAskAnswer(delta) {
  const chat = activeView.dom.chat;
  activeView.stream.askAnswerText += delta;
  if (!activeView.stream.currentAskBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    activeView.stream.currentAskBubble = document.createElement('div');
    activeView.stream.currentAskBubble.className = 'bubble ai';
    const label = document.createElement('div');
    label.className = 'ask-label';
    label.textContent = t('chat.askLabel');
    const content = document.createElement('div');
    activeView.stream.currentAskBubble.appendChild(label);
    activeView.stream.currentAskBubble.appendChild(content);
    row.appendChild(activeView.stream.currentAskBubble);
    chat.appendChild(row);
  }
  const contentEl = activeView.stream.currentAskBubble.querySelector('div:not(.ask-label)');
  if (contentEl) {
    const cursor = '<span class="cursor"></span>';
    contentEl.innerHTML = renderMarkdownWithMath(activeView.stream.askAnswerText || '') + cursor;
  }
  smartScroll();
}

export function finishAskAnswer(durationMs, model) {
  if (activeView.stream.currentAskBubble) {
    const contentEl = activeView.stream.currentAskBubble.querySelector('div:not(.ask-label)');
    if (contentEl) {
      contentEl.innerHTML = renderMarkdownWithMath(activeView.stream.askAnswerText || '');
    }
    if (durationMs != null && durationMs > 0) {
      const seed = activeView.dom.chat.querySelectorAll('.duration-badge').length;
      renderDurationBadge(activeView.stream.currentAskBubble, durationMs, model, seed);
    }
    activeView.stream.currentAskBubble = null;
    activeView.stream.askAnswerText = '';
  }
}

export function renderAskError(msg) {
  // Clean up any in-progress ask bubble
  if (activeView.stream.currentAskBubble) {
    const row = activeView.stream.currentAskBubble.closest('.row');
    if (row) row.remove();
    activeView.stream.currentAskBubble = null;
    activeView.stream.askAnswerText = '';
  }
  renderError(msg || t('chat.askFailed'));
}

// ---------- Thinking bubble rendering ----------
// Throttle thinking rendering to ~60fps using rAF. Without this, fast thinking
// streams re-render the entire accumulated text on every delta, which gets
// O(n^2) slow as text grows and keeps the main thread busy — causing visible
// lag when user tries to interact (e.g. switching agents).
let _pendingThinkingRAF = null;
// Capture the bubble + chat at schedule time so the rAF renders into the correct
// window. For the secondary view, ws.js push/pull restores global state to primary
// before the rAF fires — reading state.* at fire time would target the wrong window.
let _thinkingRafTarget = null;
export function appendThinkingDelta(delta) {
  // NOTE: always accumulate thinking text for saveMsg even if we skip DOM creation
  activeView.stream.thinkingText += delta;
  // If text bubble already exists (e.g. second+ thinking block after text has started),
  // do NOT create a new thinking bubble — it would appear after the text (misplaced).
  // The thinking content is still accumulated in activeView.stream.thinkingText + sessionThinkingBuffers
  // and will be captured correctly by finishThinking() + done handler's fallback.
  if (!activeView.stream.currentThinkingBubble) {
    if (activeView.stream.currentAiBubble) {
      // Text already showing — skip DOM bubble creation for this thinking block.
      // Content is in activeView.stream.thinkingText for persistence; no bubble needed.
      return;
    }
    const chat = activeView.dom.chat;
    const row = document.createElement('div');
    row.className = 'row ai thinking-row';
    const bubble = document.createElement('div');
    bubble.className = 'bubble ai thinking-bubble';
    const label = document.createElement('div');
    label.className = 'thinking-label';
    label.textContent = t('chat.thinkingLabel');
    const content = document.createElement('div');
    content.className = 'thinking-content';
    bubble.appendChild(label);
    bubble.appendChild(content);
    row.appendChild(bubble);
    chat.appendChild(row);
    activeView.stream.currentThinkingBubble = bubble;
  }
  // Capture the render target synchronously (correct during ws.js push/pull window).
  // Store accumulated text on the bubble node so the rAF reads it regardless of
  // which view global state points to at fire time.
  _thinkingRafTarget = { bubble: activeView.stream.currentThinkingBubble, chat: activeView.dom.chat };
  _thinkingRafTarget.bubble._nfText = activeView.stream.thinkingText;
  // Schedule a rAF render if one isn't already pending — caps re-render rate
  // and coalesces multiple deltas into a single DOM update.
  if (!_pendingThinkingRAF) {
    _pendingThinkingRAF = requestAnimationFrame(() => {
      _pendingThinkingRAF = null;
      const target = _thinkingRafTarget;
      _thinkingRafTarget = null;
      if (!target || !target.bubble) return;
      const contentEl = target.bubble.querySelector('.thinking-content');
      if (contentEl) {
        contentEl.innerHTML = renderMarkdownWithMath(target.bubble._nfText || '') + '<span class="cursor"></span>';
      }
      // Scroll the correct chat element directly — smartScroll() reads state.dom
      // at rAF time which may be the wrong window.
      const threshold = 60;
      if (target.chat.scrollHeight - target.chat.scrollTop - target.chat.clientHeight < threshold) {
        target.chat.scrollTop = target.chat.scrollHeight;
      }
    });
  }
}

// Cancel pending rAF — called by persist on cleanup when no active session.
export function cancelThinkingRAF() {
  if (_pendingThinkingRAF) {
    cancelAnimationFrame(_pendingThinkingRAF);
    _pendingThinkingRAF = null;
  }
}

export function finishThinking() {
  cancelThinkingRAF();
  if (activeView.stream.currentThinkingBubble) {
    const contentEl = activeView.stream.currentThinkingBubble.querySelector('.thinking-content');
    if (contentEl) {
      contentEl.innerHTML = renderMarkdownWithMath(activeView.stream.thinkingText || '');
    }
    // Collapse: hide content, make label clickable
    activeView.stream.currentThinkingBubble.classList.add('thinking-done');
    const label = activeView.stream.currentThinkingBubble.querySelector('.thinking-label');
    const content = activeView.stream.currentThinkingBubble.querySelector('.thinking-content');
    if (content) content.style.display = 'none';
    if (label) {
      label.classList.add('collapsible');
      label.onclick = () => {
        const visible = content.style.display !== 'none';
        content.style.display = visible ? 'none' : '';
        label.classList.toggle('expanded', !visible);
      };
    }
    const text = activeView.stream.thinkingText;
    activeView.stream.currentThinkingBubble = null;
    activeView.stream.thinkingText = '';
    return text;
  }
  return '';
}

