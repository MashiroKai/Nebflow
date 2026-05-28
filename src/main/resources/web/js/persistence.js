// persistence.js — localStorage cache module for Nebflow
// Backend is the source of truth. localStorage is a best-effort write-behind cache
// for optimistic display during streaming, and a fallback when backend is unreachable.

import state, { LS_KEY, LS_SESSIONS_KEY, LS_HISTORY_KEY, AGENT_PALETTE } from './state.js';
import { t } from './i18n.js';
import { renderMarkdownWithMath, escapeHtml, smartScroll, buildToolDetail, attachToolClick, esc, localizeToolLabel, localizeToolSummary, renderHighlightedContent } from './utils.js';
import { renderWithRegistry } from './cardRegistry.js';
import { createDurationBadgeElement } from './chat.js';

const MAX_MSGS_PER_SESSION = 200;

// ---------- Duration formatting (mirrors chat.js formatDuration) ----------
function formatDurationPersisted(ms) {
  const totalSeconds = ms / 1000;
  if (totalSeconds < 1) return '< 1s';
  const rounded = Math.round(totalSeconds);
  if (rounded < 60) return rounded + 's';
  const minutes = Math.floor(rounded / 60);
  const seconds = rounded % 60;
  return minutes + 'm ' + seconds + 's';
}


// ---------- Safe localStorage write with quota handling ----------
function safeSetItem(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    // Quota exceeded — just drop the cache silently; backend is the source of truth
    console.warn('[persistence] localStorage quota exceeded, dropping cache');
  }
}

// ---------- Safe JSON parse from localStorage ----------
function safeGetJSON(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key) || JSON.stringify(fallback)); } catch(e) { return fallback; }
}

// ---------- Save a message entry to localStorage (best-effort cache) ----------
export function saveMsg(entry, sessionId) {
  const sid = sessionId || state.activeSessionId;
  if (!sid) return;
  try {
    const all = safeGetJSON(LS_SESSIONS_KEY, {});
    const arr = all[sid] || [];
    arr.push(entry);
    if (arr.length > MAX_MSGS_PER_SESSION) {
      all[sid] = arr.slice(-MAX_MSGS_PER_SESSION);
    } else {
      all[sid] = arr;
    }
    safeSetItem(LS_SESSIONS_KEY, JSON.stringify(all));
  } catch (e) {
    // Silently ignore — backend is the source of truth
  }
}

// ---------- Load messages for the active session from localStorage ----------
export function loadMsgs() {
  if (state.activeSessionId) {
    const all = safeGetJSON(LS_SESSIONS_KEY, {});
    return all[state.activeSessionId] || [];
  }
  // No session ID yet (first load before WebSocket) — read from legacy key
  return safeGetJSON(LS_KEY, []);
}

// ---------- Replay all stored messages into the DOM (localStorage fallback) ----------
// Builds DOM directly (doesn't call render functions from chat.js) to avoid
// circular deps and to avoid re-saving.
export function restoreFromStorage() {
  const chat = state.dom.chat;
  const msgs = loadMsgs();
  msgs.forEach((m, i) => {
    if (m.type === 'user') {
      const row = document.createElement('div');
      row.className = 'row user';
      if (m.text) {
        const bubble = document.createElement('div');
        bubble.className = 'bubble user';
        const t = document.createElement('div');
        t.textContent = m.text;
        bubble.appendChild(t);
        row.appendChild(bubble);
      }
      (m.attachments || []).forEach(att => {
        const bubble = document.createElement('div');
        bubble.className = 'bubble user att-bubble';
        if (att.type === 'image' && att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:')) {
          const img = document.createElement('img');
          img.src = att.preview;
          img.className = 'att-img';
          bubble.appendChild(img);
          const tag = document.createElement('span');
          tag.className = 'att-file-tag';
          tag.textContent = '[image' + (att.name ? ': ' + att.name : '') + ']';
          bubble.appendChild(tag);
        } else {
          const tag = document.createElement('span');
          tag.className = 'att-file-tag';
          tag.textContent = '[file' + (att.name ? ': ' + att.name : '') + ']';
          bubble.appendChild(tag);
        }
        row.appendChild(bubble);
      });
      chat.appendChild(row);
    } else if (m.type === 'ai') {
      // Thinking bubble (if present)
      if (m.thinking) {
        const tRow = document.createElement('div');
        tRow.className = 'row ai thinking-row';
        const tBubble = document.createElement('div');
        tBubble.className = 'bubble ai thinking-bubble thinking-done';
        const tLabel = document.createElement('div');
        tLabel.className = 'thinking-label collapsible';
        tLabel.textContent = t('chat.thinkingLabel');
        const tContent = document.createElement('div');
        tContent.className = 'thinking-content';
        tContent.innerHTML = renderMarkdownWithMath(m.thinking);
        // If there's no text, keep thinking expanded so user can see what the model thought
        if (!m.text) {
          tLabel.classList.add('expanded');
        } else {
          tContent.style.display = 'none';
        }
        tBubble.appendChild(tLabel);
        tBubble.appendChild(tContent);
        tRow.appendChild(tBubble);
        chat.appendChild(tRow);
        tLabel.onclick = () => {
          const visible = tContent.style.display !== 'none';
          tContent.style.display = visible ? 'none' : '';
          tLabel.classList.toggle('expanded', !visible);
        };
      }
      // Only render AI bubble if there's actual text content
      if (m.text) {
        const row = document.createElement('div');
        row.className = 'row ai';
        const bubble = document.createElement('div');
        bubble.className = 'bubble ai';
        bubble.innerHTML = renderMarkdownWithMath(m.text || '');
        row.appendChild(bubble);
        if (m.durationMs != null && m.durationMs > 0) {
          const badge = createDurationBadgeElement(m.durationMs, m.model, i);
          row.appendChild(badge);
        }
        chat.appendChild(row);
      }
    } else if (m.type === 'tool') {
      // Inline render to avoid triggering saveMsg again
      const row = document.createElement('div');
      row.className = 'row tool';
      const card = document.createElement('div');
      card.className = 'tool-card';
      // Card tool: pass input directly ({html, title}) — renderWithRegistry handles it natively
      const isCard = m.label && m.label.startsWith('Card') && m.input && m.input.html;
      const cardData = isCard ? m.input : { label: m.label, summary: m.summary, content: m.content || '', isError: m.isError, input: m.input, sessionId: state.activeSessionId };
      if (renderWithRegistry(card, cardData)) {
        card.classList.add('tool-card--html');
        row.appendChild(card);
        chat.appendChild(row);
      } else {
        const isError = m.isError;
        const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                             : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        const detailHtml = buildToolDetail(m.input, m.label);
        const highlightHtml = renderHighlightedContent(m.content, m.label);
        const bodyHtml = (detailHtml + (highlightHtml || (m.content ? '<pre class="tool-body-pre">' + esc(m.content) + '</pre>' : ''))) || '';
        const hasBody = !!bodyHtml;
        const localLabel = localizeToolLabel(m.label);
        const localSummary = localizeToolSummary(m.summary, m.label);
        const lParts = localLabel.split('\n', 2);
        const lHtml = esc(lParts[0]) + ' &mdash; ' + esc(localSummary)
          + (lParts.length > 1 ? '<br><span class="tool-detail">' + esc(lParts[1]) + '</span>' : '');
        card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
          '<div class="content"><div class="label">' + lHtml + '</div>' +
          (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
        row.appendChild(card);
        chat.appendChild(row);
        if (hasBody) attachToolClick(card);
      }
    } else if (m.type === 'askUser') {
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      row.appendChild(bubble);
      chat.appendChild(row);
      // Inline option-box render (no showOptions import — avoids chat.js dep)
      const box = document.createElement('div');
      box.className = 'option-box';
      m.items.forEach(item => {
        const q = document.createElement('div');
        q.className = 'option-q';
        q.textContent = item.question;
        box.appendChild(q);
        const optsDiv = document.createElement('div');
        optsDiv.className = 'option-opts';
        (item.options || []).forEach(opt => {
          const btn = document.createElement('button');
          btn.className = 'option-btn';
          const label = typeof opt === 'string' ? opt : opt.label;
          btn.textContent = label;
          btn.disabled = true;
          btn.style.opacity = '0.5';
          optsDiv.appendChild(btn);
        });
        box.appendChild(optsDiv);
      });
      bubble.appendChild(box);
    } else if (m.type === 'askPermission') {
      // Render as disabled permission prompt (will be replaced by interactive version if still pending)
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      row.appendChild(bubble);
      chat.appendChild(row);
      const box = document.createElement('div');
      box.className = 'permission-pending-box option-box';
      const q = document.createElement('div');
      q.className = 'option-q';
      q.textContent = t('chat.allowTool', { tool: m.toolName || '?' });
      box.appendChild(q);
      const optsDiv = document.createElement('div');
      optsDiv.className = 'option-opts';
      [{ label: t('chat.allow') }, { label: t('chat.deny') }].forEach(opt => {
        const btn = document.createElement('button');
        btn.className = 'option-btn';
        btn.textContent = opt.label;
        btn.disabled = true;
        btn.style.opacity = '0.5';
        optsDiv.appendChild(btn);
      });
      box.appendChild(optsDiv);
      bubble.appendChild(box);
    } else if (m.type === 'ask') {
      // Ask question (user side)
      const qRow = document.createElement('div');
      qRow.className = 'row user';
      const qBubble = document.createElement('div');
      qBubble.className = 'bubble user';
      const qLabel = document.createElement('div');
      qLabel.className = 'ask-label';
      qLabel.textContent = t('chat.askLabel');
      const qText = document.createElement('div');
      qText.textContent = m.question || '';
      qBubble.appendChild(qLabel);
      qBubble.appendChild(qText);
      qRow.appendChild(qBubble);
      chat.appendChild(qRow);
      // Ask answer (AI side)
      if (m.answer) {
        const aRow = document.createElement('div');
        aRow.className = 'row ai';
        const aBubble = document.createElement('div');
        aBubble.className = 'bubble ai';
        const aLabel = document.createElement('div');
        aLabel.className = 'ask-label';
        aLabel.textContent = t('chat.askLabel');
        const aContent = document.createElement('div');
        aContent.innerHTML = renderMarkdownWithMath(m.answer);
        aBubble.appendChild(aLabel);
        aBubble.appendChild(aContent);
        aRow.appendChild(aBubble);
        if (m.durationMs != null && m.durationMs > 0) {
          const badge = createDurationBadgeElement(m.durationMs, m.model, i);
          aRow.appendChild(badge);
        }
        chat.appendChild(aRow);
      }
    } else if (m.type === 'agent') {
      const row = document.createElement('div');
      row.className = 'row ai agent-row';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      bubble.innerHTML = renderMarkdownWithMath(m.text || '');
      if (m.agentId && m.agentId !== 'default') {
        const badge = document.createElement('div');
        badge.className = 'agent-badge';
        const colorIdx = m.agentId.length % AGENT_PALETTE.length;
        const color = AGENT_PALETTE[colorIdx];
        badge.style.borderColor = color;
        badge.style.color = color;
        badge.textContent = m.agentId;
        badge.style.maxWidth = '160px';
        badge.style.whiteSpace = 'nowrap';
        badge.style.overflow = 'hidden';
        badge.style.textOverflow = 'ellipsis';
        row.appendChild(badge);
      }
      row.appendChild(bubble);
      chat.appendChild(row);
    } else if (m.type === 'error') {
      // Skip error messages on restore — they're transient
    } else if (m.type === 'system') {
      const row = document.createElement('div');
      row.className = 'row error';
      const card = document.createElement('div');
      card.className = 'error-card';
      card.style.background = 'rgba(91,141,217,0.1)';
      card.style.color = '#5b8dd9';
      card.textContent = m.i18nKey ? t(m.i18nKey, m.params || {}) : m.content;
      row.appendChild(card);
      chat.appendChild(row);
    }
  });
  chat.scrollTop = chat.scrollHeight;
  state.scrollSnapped = true;
  // Schedule deferred scrolls to catch async iframe height changes from card rendering.
  requestAnimationFrame(() => { chat.scrollTop = chat.scrollHeight; state.scrollSnapped = true; });
  setTimeout(() => { chat.scrollTop = chat.scrollHeight; state.scrollSnapped = true; }, 100);
  setTimeout(() => { chat.scrollTop = chat.scrollHeight; state.scrollSnapped = true; }, 500);
}

// ---------- Replay backend history messages into the DOM ----------
// Same logic as restoreFromStorage but takes messages array directly (from backend).
// Uses DocumentFragment to batch DOM insertions and avoids redundant scroll operations.
export function restoreFromBackendHistory(msgs, opts = {}) {
  const { scrollToBottom = true } = opts;
  const chat = state.dom.chat;
  const fragment = document.createDocumentFragment();
  let skipUserMsg = false;
  msgs.forEach((m, i) => {
    if (skipUserMsg) { skipUserMsg = false; return; }
    if (m.type === 'user') {
      const row = document.createElement('div');
      row.className = 'row user';
      if (m.text) {
        const bubble = document.createElement('div');
        bubble.className = 'bubble user';
        const t = document.createElement('div');
        t.textContent = m.text;
        bubble.appendChild(t);
        row.appendChild(bubble);
      }
      (m.attachments || []).forEach(att => {
        const bubble = document.createElement('div');
        bubble.className = 'bubble user att-bubble';
        if (att.type === 'image' && att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:')) {
          const img = document.createElement('img');
          img.src = att.preview;
          img.className = 'att-img';
          bubble.appendChild(img);
          const tag = document.createElement('span');
          tag.className = 'att-file-tag';
          tag.textContent = '[image' + (att.name ? ': ' + att.name : '') + ']';
          bubble.appendChild(tag);
        } else {
          const tag = document.createElement('span');
          tag.className = 'att-file-tag';
          tag.textContent = '[file' + (att.name ? ': ' + att.name : '') + ']';
          bubble.appendChild(tag);
        }
        row.appendChild(bubble);
      });
      fragment.appendChild(row);
    } else if (m.type === 'ai') {
      // Thinking bubble (if present)
      if (m.thinking) {
        const tRow = document.createElement('div');
        tRow.className = 'row ai thinking-row';
        const tBubble = document.createElement('div');
        tBubble.className = 'bubble ai thinking-bubble thinking-done';
        const tLabel = document.createElement('div');
        tLabel.className = 'thinking-label collapsible';
        tLabel.textContent = t('chat.thinkingLabel');
        const tContent = document.createElement('div');
        tContent.className = 'thinking-content';
        tContent.innerHTML = renderMarkdownWithMath(m.thinking);
        // If there's no text, keep thinking expanded so user can see what the model thought
        if (!m.text) {
          tLabel.classList.add('expanded');
        } else {
          tContent.style.display = 'none';
        }
        tBubble.appendChild(tLabel);
        tBubble.appendChild(tContent);
        tRow.appendChild(tBubble);
        fragment.appendChild(tRow);
        tLabel.onclick = () => {
          const visible = tContent.style.display !== 'none';
          tContent.style.display = visible ? 'none' : '';
          tLabel.classList.toggle('expanded', !visible);
        };
      }
      // Only render AI bubble if there's actual text content
      if (m.text) {
        const row = document.createElement('div');
        row.className = 'row ai';
        const bubble = document.createElement('div');
        bubble.className = 'bubble ai';
        bubble.innerHTML = renderMarkdownWithMath(m.text || '');
        row.appendChild(bubble);
        if (m.durationMs != null && m.durationMs > 0) {
          const badge = createDurationBadgeElement(m.durationMs, m.model, i);
          row.appendChild(badge);
        }
        fragment.appendChild(row);
      }
    } else if (m.type === 'tool') {
      const row = document.createElement('div');
      row.className = 'row tool';
      const card = document.createElement('div');
      card.className = 'tool-card';
      // Card tool: pass input directly ({html, title}) — renderWithRegistry handles it natively
      let parsedInput = m.input;
      try { parsedInput = typeof m.input === 'string' ? JSON.parse(m.input) : m.input; } catch(e) {}
      const isCard2 = m.label && m.label.startsWith('Card') && parsedInput && parsedInput.html;
      const cardData2 = isCard2 ? parsedInput : { label: m.label, summary: m.summary, content: m.content || '', isError: m.isError, input: parsedInput, sessionId: state.activeSessionId };
      if (renderWithRegistry(card, cardData2)) {
        card.classList.add('tool-card--html');
        row.appendChild(card);
        fragment.appendChild(row);
      } else {
        const isError = m.isError;
        const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                             : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        const detailHtml = buildToolDetail(parsedInput, m.label);
        const highlightHtml = renderHighlightedContent(m.content, m.label);
        const bodyHtml = (detailHtml + (highlightHtml || (m.content ? '<pre class="tool-body-pre">' + esc(m.content) + '</pre>' : ''))) || '';
        const hasBody = !!bodyHtml;
        const localLabel = localizeToolLabel(m.label);
        const localSummary = localizeToolSummary(m.summary, m.label);
        const lParts = localLabel.split('\n', 2);
        const lHtml = esc(lParts[0]) + ' &mdash; ' + esc(localSummary)
          + (lParts.length > 1 ? '<br><span class="tool-detail">' + esc(lParts[1]) + '</span>' : '');
        card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
          '<div class="content"><div class="label">' + lHtml + '</div>' +
          (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
        row.appendChild(card);
        fragment.appendChild(row);
        if (hasBody) attachToolClick(card);
      }
    } else if (m.type === 'askUser') {
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      row.appendChild(bubble);
      fragment.appendChild(row);
      const box = document.createElement('div');
      box.className = 'option-box';
      m.items.forEach(item => {
        const q = document.createElement('div');
        q.className = 'option-q';
        q.textContent = item.question;
        box.appendChild(q);
        const optsDiv = document.createElement('div');
        optsDiv.className = 'option-opts';
        (item.options || []).forEach(opt => {
          const btn = document.createElement('button');
          btn.className = 'option-btn';
          const label = typeof opt === 'string' ? opt : opt.label;
          btn.textContent = label;
          btn.disabled = true;
          btn.style.opacity = '0.5';
          optsDiv.appendChild(btn);
        });
        box.appendChild(optsDiv);
      });
      bubble.appendChild(box);
      // If the next message is a User answer (from AskUser), show it on the card
      const nextMsg = msgs[i + 1];
      if (nextMsg && nextMsg.type === 'user' && nextMsg.text) {
        const ansDiv = document.createElement('div');
        ansDiv.className = 'option-answer';
        ansDiv.textContent = '-> ' + nextMsg.text;
        box.appendChild(ansDiv);
        skipUserMsg = true;
      }
    } else if (m.type === 'askPermission') {
      // Render as disabled permission prompt (will be replaced by interactive version if still pending)
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      row.appendChild(bubble);
      fragment.appendChild(row);
      const box = document.createElement('div');
      box.className = 'permission-pending-box option-box';
      const q = document.createElement('div');
      q.className = 'option-q';
      q.textContent = t('chat.allowTool', { tool: m.toolName || '?' });
      box.appendChild(q);
      const optsDiv = document.createElement('div');
      optsDiv.className = 'option-opts';
      [{ label: t('chat.allow') }, { label: t('chat.deny') }].forEach(opt => {
        const btn = document.createElement('button');
        btn.className = 'option-btn';
        btn.textContent = opt.label;
        btn.disabled = true;
        btn.style.opacity = '0.5';
        optsDiv.appendChild(btn);
      });
      box.appendChild(optsDiv);
      bubble.appendChild(box);
    } else if (m.type === 'ask') {
      // Ask question (user side)
      const qRow = document.createElement('div');
      qRow.className = 'row user';
      const qBubble = document.createElement('div');
      qBubble.className = 'bubble user';
      const qLabel = document.createElement('div');
      qLabel.className = 'ask-label';
      qLabel.textContent = t('chat.askLabel');
      const qText = document.createElement('div');
      qText.textContent = m.question || '';
      qBubble.appendChild(qLabel);
      qBubble.appendChild(qText);
      qRow.appendChild(qBubble);
      fragment.appendChild(qRow);
      // Ask answer (AI side)
      if (m.answer) {
        const aRow = document.createElement('div');
        aRow.className = 'row ai';
        const aBubble = document.createElement('div');
        aBubble.className = 'bubble ai';
        const aLabel = document.createElement('div');
        aLabel.className = 'ask-label';
        aLabel.textContent = t('chat.askLabel');
        const aContent = document.createElement('div');
        aContent.innerHTML = renderMarkdownWithMath(m.answer);
        aBubble.appendChild(aLabel);
        aBubble.appendChild(aContent);
        aRow.appendChild(aBubble);
        if (m.durationMs != null && m.durationMs > 0) {
          const badge = createDurationBadgeElement(m.durationMs, m.model, i);
          aRow.appendChild(badge);
        }
        fragment.appendChild(aRow);
      }
    } else if (m.type === 'agent') {
      const row = document.createElement('div');
      row.className = 'row ai agent-row';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      bubble.innerHTML = renderMarkdownWithMath(m.text || '');
      if (m.agentId && m.agentId !== 'default') {
        const badge = document.createElement('div');
        badge.className = 'agent-badge';
        const colorIdx = m.agentId.length % AGENT_PALETTE.length;
        const color = AGENT_PALETTE[colorIdx];
        badge.style.borderColor = color;
        badge.style.color = color;
        badge.textContent = m.agentId;
        badge.style.maxWidth = '160px';
        badge.style.whiteSpace = 'nowrap';
        badge.style.overflow = 'hidden';
        badge.style.textOverflow = 'ellipsis';
        row.appendChild(badge);
      }
      row.appendChild(bubble);
      fragment.appendChild(row);
    } else if (m.type === 'system') {
      const row = document.createElement('div');
      row.className = 'row error';
      const card = document.createElement('div');
      card.className = 'error-card';
      card.style.background = 'rgba(91,141,217,0.1)';
      card.style.color = '#5b8dd9';
      card.textContent = m.i18nKey ? t(m.i18nKey, m.params || {}) : m.content;
      row.appendChild(card);
      fragment.appendChild(row);
    }
  });
  chat.appendChild(fragment);
  // Scroll to bottom: immediate sync (for stable initial position before any async iframe load)
  // followed by deferred rAF (catches late layout changes from streaming state restoration, etc.).
  // Caller can set scrollToBottom=false (e.g. scroll-up pagination preserves position).
  if (scrollToBottom) {
    chat.scrollTop = chat.scrollHeight;
    state.scrollSnapped = true;
    requestAnimationFrame(() => { chat.scrollTop = chat.scrollHeight; state.scrollSnapped = true; });
  }
}

// ---------- One-time migration from old localStorage key ----------
export function migrateLegacyIfNeeded() {
  if (!state.activeSessionId || state.legacyMigrated) return;
  state.legacyMigrated = true;
  const all = safeGetJSON(LS_SESSIONS_KEY, {});
  if (all[state.activeSessionId]) return; // already migrated
  // Try to read from legacy key
  try {
    const oldMsgs = safeGetJSON(LS_KEY, []);
    if (Array.isArray(oldMsgs) && oldMsgs.length > 0) {
      all[state.activeSessionId] = oldMsgs;
      safeSetItem(LS_SESSIONS_KEY, JSON.stringify(all));
      // Re-render chat with the migrated data
      state.dom.chat.innerHTML = '';
      restoreFromStorage();
    }
  } catch(e) {}
}
