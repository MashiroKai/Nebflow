// persistence.js — localStorage cache module for Nebflow
// Backend is the source of truth. localStorage is a best-effort write-behind cache
// for optimistic display during streaming, and a fallback when backend is unreachable.

import state, { LS_KEY, LS_SESSIONS_KEY, LS_HISTORY_KEY, AGENT_PALETTE } from './state.js';
import { renderMarkdownWithMath, escapeHtml, smartScroll, formatDiff, buildToolDetail, attachToolClick, esc } from './utils.js';
import { renderWithRegistry } from './cardRegistry.js';

const MAX_MSGS_PER_SESSION = 200;

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
  msgs.forEach(m => {
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
        bubble.className = 'bubble user';
        if (att.type === 'image' && att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:')) {
          const img = document.createElement('img');
          img.src = att.preview;
          img.style.maxWidth = '180px';
          img.style.maxHeight = '180px';
          img.style.borderRadius = '8px';
          bubble.appendChild(img);
        } else {
          bubble.style.fontSize = '13px';
          const tag = document.createElement('span');
          tag.className = 'file-tag';
          tag.textContent = (att.name || 'file');
          bubble.appendChild(tag);
        }
        row.appendChild(bubble);
      });
      chat.appendChild(row);
    } else if (m.type === 'ai') {
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      bubble.innerHTML = renderMarkdownWithMath(m.text || '');
      row.appendChild(bubble);
      chat.appendChild(row);
    } else if (m.type === 'tool') {
      // Inline render to avoid triggering saveMsg again
      const row = document.createElement('div');
      row.className = 'row tool';
      const card = document.createElement('div');
      card.className = 'tool-card';
      // Try plugin renderer first
      const data = { label: m.label, summary: m.summary, content: m.content, isError: m.isError, input: m.input, sessionId: state.activeSessionId };
      if (renderWithRegistry(card, data)) {
        row.appendChild(card);
        chat.appendChild(row);
      } else {
        const isError = m.isError;
        const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                             : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        const diffHtml = formatDiff(m.content);
        const detailHtml = buildToolDetail(m.input, m.label);
        const bodyText = diffHtml ? '' : (m.content ? esc(m.content.length > 120 ? m.content.slice(0,120) + '...' : m.content) : '');
        const bodyHtml = (detailHtml + (diffHtml || (bodyText ? '<pre>' + bodyText + '</pre>' : ''))) || '';
        const hasBody = !!bodyHtml;
        card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
          '<div class="content"><div class="label">' + esc(m.label) + ' &mdash; ' + esc(m.summary) + '</div>' +
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
      card.style.background = '#e3f2fd';
      card.style.color = '#1565c0';
      card.textContent = m.content;
      row.appendChild(card);
      chat.appendChild(row);
    } else if (m.type === 'stage') {
      const STAGE_STYLES = {
        Cautious:     { bg: 'rgba(217,165,65,0.15)',  border: '#D6B656', color: '#b8941e', icon: '⚠' },
        Conservative: { bg: 'rgba(199,84,80,0.15)',    border: '#c75450', color: '#c75450', icon: '⛔' },
        Paused:       { bg: 'rgba(108,142,191,0.18)',  border: '#6C8EBF', color: '#6C8EBF', icon: '⏸' },
      };
      const s = STAGE_STYLES[m.stage];
      if (s) {
        const row = document.createElement('div');
        row.className = 'row error';
        const card = document.createElement('div');
        card.className = 'error-card';
        card.style.background = s.bg;
        card.style.borderLeft = `3px solid ${s.border}`;
        card.style.color = s.color;
        card.style.paddingLeft = '14px';
        card.textContent = `${s.icon} [${m.stage}] Turn ${m.turnIdx} · stagnant ${m.stagnationCount}`;
        row.appendChild(card);
        chat.appendChild(row);
      }
    }
  });
  chat.scrollTop = chat.scrollHeight;
}

// ---------- Replay backend history messages into the DOM ----------
// Same logic as restoreFromStorage but takes messages array directly (from backend).
export function restoreFromBackendHistory(msgs) {
  const chat = state.dom.chat;
  msgs.forEach(m => {
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
        bubble.className = 'bubble user';
        if (att.type === 'image' && att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:')) {
          const img = document.createElement('img');
          img.src = att.preview;
          img.style.maxWidth = '180px';
          img.style.maxHeight = '180px';
          img.style.borderRadius = '8px';
          bubble.appendChild(img);
        } else {
          bubble.style.fontSize = '13px';
          const tag = document.createElement('span');
          tag.className = 'file-tag';
          tag.textContent = (att.name || 'file');
          bubble.appendChild(tag);
        }
        row.appendChild(bubble);
      });
      chat.appendChild(row);
    } else if (m.type === 'ai') {
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      bubble.innerHTML = renderMarkdownWithMath(m.text || '');
      row.appendChild(bubble);
      chat.appendChild(row);
    } else if (m.type === 'tool') {
      const row = document.createElement('div');
      row.className = 'row tool';
      const card = document.createElement('div');
      card.className = 'tool-card';
      const data = { label: m.label, summary: m.summary, content: m.content, isError: m.isError, input: m.input, sessionId: state.activeSessionId };
      if (renderWithRegistry(card, data)) {
        row.appendChild(card);
        chat.appendChild(row);
      } else {
        const isError = m.isError;
        const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                             : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        const diffHtml = formatDiff(m.content);
        let inputObj = null;
        try { inputObj = typeof m.input === 'string' ? JSON.parse(m.input) : m.input; } catch(e) {}
        const detailHtml = buildToolDetail(inputObj, m.label);
        const bodyText = diffHtml ? '' : (m.content ? esc(m.content.length > 120 ? m.content.slice(0,120) + '...' : m.content) : '');
        const bodyHtml = (detailHtml + (diffHtml || (bodyText ? '<pre>' + bodyText + '</pre>' : ''))) || '';
        const hasBody = !!bodyHtml;
        card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
          '<div class="content"><div class="label">' + esc(m.label) + ' &mdash; ' + esc(m.summary) + '</div>' +
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
    } else if (m.type === 'system') {
      const row = document.createElement('div');
      row.className = 'row error';
      const card = document.createElement('div');
      card.className = 'error-card';
      card.style.background = '#e3f2fd';
      card.style.color = '#1565c0';
      card.textContent = m.content;
      row.appendChild(card);
      chat.appendChild(row);
    } else if (m.type === 'stage') {
      const STAGE_STYLES = {
        Cautious:     { bg: 'rgba(217,165,65,0.15)',  border: '#D6B656', color: '#b8941e', icon: '⚠' },
        Conservative: { bg: 'rgba(199,84,80,0.15)',    border: '#c75450', color: '#c75450', icon: '⛔' },
        Paused:       { bg: 'rgba(108,142,191,0.18)',  border: '#6C8EBF', color: '#6C8EBF', icon: '⏸' },
      };
      const s = STAGE_STYLES[m.stage];
      if (s) {
        const row = document.createElement('div');
        row.className = 'row error';
        const card = document.createElement('div');
        card.className = 'error-card';
        card.style.background = s.bg;
        card.style.borderLeft = `3px solid ${s.border}`;
        card.style.color = s.color;
        card.style.paddingLeft = '14px';
        card.textContent = `${s.icon} [${m.stage}] Turn ${m.turnIdx} · stagnant ${m.stagnationCount}`;
        row.appendChild(card);
        chat.appendChild(row);
      }
    }
  });
  chat.scrollTop = chat.scrollHeight;
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
