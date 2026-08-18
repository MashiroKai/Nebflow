// persistence.js — localStorage cache module for Nebflow
// Backend is the source of truth. localStorage is a best-effort write-behind cache
// for optimistic display during streaming, and a fallback when backend is unreachable.

import state, { LS_KEY, LS_SESSIONS_KEY, LS_HISTORY_KEY, AGENT_PALETTE } from './state.js';
import { key } from './branding.js';
import { activeView } from './chatView.js';
import { t } from './i18n.js';
import { renderMarkdownWithMath, escapeHtml, smartScroll, buildToolDetail, buildDelegatePromptHtml, attachToolClick, esc, localizeToolLabel, localizeToolSummary, renderHighlightedContent, createMsgCopyButton } from './utils.js';
import { renderWithRegistry, cleanupCardIframes } from './cardRegistry.js';
import { createDurationBadgeElement, formatHm, toggleTimeFormat, applyPopCard, buildInjectedRow } from './chat.js';

// ---------- AI message badge (no duration) ----------
// Builds a duration-badge pill with timestamp + copy button, matching
// the style used by finishAi() in chat.js for live messages.
function createAiCopyBadge(timestamp, text) {
  const badge = document.createElement('div');
  badge.className = 'duration-badge';
  if (timestamp && timestamp > 0) {
    const timeSpan = document.createElement('span');
    timeSpan.className = 'duration-badge-time';
    timeSpan.setAttribute('data-ts', timestamp);
    timeSpan.textContent = formatHm(timestamp);
    timeSpan.title = '点击切换 12/24 小时制';
    timeSpan.addEventListener('click', toggleTimeFormat);
    badge.appendChild(timeSpan);
    const div = document.createElement('span');
    div.className = 'duration-badge-divider';
    badge.appendChild(div);
  }
  if (text) badge.appendChild(createMsgCopyButton(text));
  return badge;
}

// ---------- Safe localStorage write with quota handling ----------
function safeSetItem(key, value) {
  try {
    localStorage.setItem(key, value);
    return true;
  } catch (e) {
    // Quota exceeded — just drop the cache silently; backend is the source of truth
    console.debug('[persistence] localStorage quota exceeded, dropping cache');
    return false;
  }
}

// ---------- Prune old sessions from the cache and retry writing once ----------
// Removes roughly half of the non-active sessions to free space. Returns true on success.
function pruneAndRetrySetSessions(all, keepSid) {
  const otherSids = Object.keys(all).filter(k => k !== keepSid);
  if (otherSids.length === 0) return false; // nothing to prune
  const removeCount = Math.ceil(otherSids.length / 2);
  for (let i = 0; i < removeCount; i++) delete all[otherSids[i]];
  return safeSetItem(LS_SESSIONS_KEY, JSON.stringify(all));
}

// ---------- Emergency cleanup: purge bloated localStorage on startup ----------
// If localStorage is near quota, old unsanitized data (from before size limits)
// prevents any new write. This re-reads all sessions, re-sanitizes every entry,
// and writes back a compact version. Called once on startup.
export function emergencyCacheCleanup() {
  try {
    const raw = localStorage.getItem(LS_SESSIONS_KEY);
    if (!raw) return;
    // If already under 2MB, no need to clean
    if (raw.length < 2_000_000) return;
    console.debug('[persistence] cache size ' + Math.round(raw.length / 1024) + 'KB — running emergency cleanup');
    const all = JSON.parse(raw);
    // Re-sanitize every entry in every session and enforce message cap
    for (const sid of Object.keys(all)) {
      const arr = all[sid];
      if (!Array.isArray(arr)) { delete all[sid]; continue; }
      all[sid] = arr.slice(-MAX_MSGS_PER_SESSION).map(e => {
        try { return sanitizeForCache(e); } catch { return null; }
      }).filter(Boolean);
      if (all[sid].length === 0) delete all[sid];
    }
    // Write back compact version; if still too large, keep only the active session
    let compacted = JSON.stringify(all);
    if (compacted.length > 2_000_000 && state.activeSessionId) {
      const trimmed = { [state.activeSessionId]: all[state.activeSessionId] || [] };
      compacted = JSON.stringify(trimmed);
    }
    if (compacted.length > 3_000_000) {
      // Still too large — drop everything
      localStorage.removeItem(LS_SESSIONS_KEY);
      console.debug('[persistence] cache dropped entirely after cleanup attempt');
    } else {
      localStorage.setItem(LS_SESSIONS_KEY, compacted);
      console.debug('[persistence] cache cleaned: ' + Math.round(compacted.length / 1024) + 'KB');
    }
  } catch (e) {
    // If anything goes wrong, drop the cache
    try { localStorage.removeItem(LS_SESSIONS_KEY); } catch {}
  }
}

// ---------- Safe JSON parse from localStorage ----------
function safeGetJSON(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key) || JSON.stringify(fallback)); } catch(e) { return fallback; }
}

// ---------- Sanitize entry for localStorage (prevent quota bloat) ----------
// Tool content and attachment previews can be enormous (entire files, base64 images).
// Since backend is the source of truth, we truncate aggressively for the local cache.
const MAX_CONTENT_LEN = 500;   // tool result content
const MAX_INPUT_LEN = 500;     // tool input args
const MAX_THINKING_LEN = 2000; // AI thinking text
const MAX_TEXT_LEN = 5000;     // AI response text
const MAX_MSGS_PER_SESSION = 80;

function sanitizeForCache(entry) {
  if (!entry || typeof entry !== 'object') return entry;
  const e = { ...entry };
  if (typeof e.content === 'string' && e.content.length > MAX_CONTENT_LEN)
    e.content = e.content.slice(0, MAX_CONTENT_LEN) + '…';
  if (typeof e.input === 'string' && e.input.length > MAX_INPUT_LEN)
    e.input = e.input.slice(0, MAX_INPUT_LEN) + '…';
  if (typeof e.thinking === 'string' && e.thinking.length > MAX_THINKING_LEN)
    e.thinking = e.thinking.slice(0, MAX_THINKING_LEN) + '…';
  if (typeof e.text === 'string' && e.text.length > MAX_TEXT_LEN)
    e.text = e.text.slice(0, MAX_TEXT_LEN) + '…';
  if (typeof e.answer === 'string' && e.answer.length > MAX_TEXT_LEN)
    e.answer = e.answer.slice(0, MAX_TEXT_LEN) + '…';
  // Strip base64 attachment previews — they can be multi-MB. Keep `path`:
  // it's a small string and lets the restore path re-render the image from
  // the uploads route (G1) even after the preview is gone.
  if (e.attachments) {
    e.attachments = e.attachments.map(a => ({ type: a.type, name: a.name, path: a.path }));
  }
  return e;
}

// ---------- Attachment rendering (shared by both restore paths) ----------

/** Convert an absolute uploads path (ui.json records attachments as
 *  {name, type, path}) into a served URL for the backend's
 *  GET /uploads/<sid>/<file> route. Auth: same-origin cookie usually
 *  suffices; the ?token= query is the stale-cookie fallback (token from the
 *  frontend's existing store — localStorage key('token'), same one
 *  the WS connect uses). Returns null when the path isn't under an
 *  uploads dir or is otherwise unusable. */
export function attachmentImageUrl(path) {
  if (!path || typeof path !== 'string') return null;
  const norm = path.replace(/\\/g, '/');
  const idx = norm.indexOf('/uploads/');
  if (idx < 0) return null;
  const rel = norm.slice(idx + '/uploads/'.length); // <sid>/<file>
  if (!rel || rel.includes('..')) return null;
  const encoded = rel.split('/').map(encodeURIComponent).join('/');
  const tok = localStorage.getItem(key('token')) || '';
  return `/uploads/${encoded}` + (tok ? `?token=${encodeURIComponent(tok)}` : '');
}

/** Append one attachment bubble to a message row. Images render as <img>
 *  from the live preview (dataURL) or, after a refresh, from the uploads
 *  route via att.path (G1). Non-image attachments keep the plain text tag. */
function appendAttachmentBubble(row, att) {
  const bubble = document.createElement('div');
  bubble.className = 'bubble user att-bubble';
  const imgSrc =
    att.type === 'image'
      ? (att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:'))
        ? att.preview
        : attachmentImageUrl(att.path)
      : null;
  if (imgSrc) {
    const img = document.createElement('img');
    img.src = imgSrc;
    img.className = 'att-img';
    img.title = att.name || '';
    img.draggable = false;
    // Missing file (uploads dir cleaned, session deleted) — hide the broken
    // img, the tag below still labels the attachment.
    img.onerror = () => { img.style.display = 'none'; };
    bubble.appendChild(img);
  }
  const tag = document.createElement('span');
  tag.className = 'att-file-tag';
  tag.textContent = (att.type === 'image' ? '[image' : '[file') + (att.name ? ': ' + att.name : '') + ']';
  bubble.appendChild(tag);
  row.appendChild(bubble);
}

// ---------- Save a message entry to localStorage (best-effort cache) ----------
export function saveMsg(entry, sessionId) {
  const sid = sessionId || state.activeSessionId;
  if (!sid) return;
  try {
    const all = safeGetJSON(LS_SESSIONS_KEY, {});
    const arr = all[sid] || [];
    arr.push(sanitizeForCache(entry));
    // Cap messages per session to prevent unbounded growth
    if (arr.length > MAX_MSGS_PER_SESSION) arr.splice(0, arr.length - MAX_MSGS_PER_SESSION);
    all[sid] = arr;
    if (!safeSetItem(LS_SESSIONS_KEY, JSON.stringify(all))) {
      // Quota exceeded — prune old sessions and retry once
      pruneAndRetrySetSessions(all, sid);
    }
  } catch (e) {
    // Silently ignore — backend is the source of truth
  }
}

// ---------- Injected-bubble direction filter ----------
// The blue injected bubble is for content RECEIVED by this session's agent
// (Mail from others, Delegate/SubTask completion notifications, ExternalEvent
// results). The backend currently records EVERY injected user event flowing
// through the root WS connection into the ROOT session's ui.json
// (makeRecordingWsSend's "user" case ignores the event's own session id), so a
// restored history can contain injections that were actually delivered
// elsewhere — including this agent's own OUTGOING sends. Those are not
// received content: never render them as injected bubbles.
//   - sender === own agent name  → a Mail this agent SENT (the bubble belongs
//     to the recipient's session)
//   - source delegate/subtask without eventType → a task prompt this agent
//     DISPATCHED (completion notifications always carry an eventType)
function ownAgentName() {
  const sid = state.activeSessionId;
  if (!sid) return null;
  const s = (state.sessions || []).find(x => x && x.id === sid);
  return s ? (s.agentName || s.name || null) : null;
}
function isOutgoingInjection(m) {
  const own = ownAgentName();
  if (m.sender && own && m.sender === own) return true;
  if ((m.source === 'delegate' || m.source === 'subtask') && !m.eventType) return true;
  return false;
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
  const chat = activeView?.dom?.chat;
  if (!chat) return;
  const msgs = loadMsgs();
  msgs.forEach((m, i) => {
    if (m.type === 'user') {
      // Injected messages (task P+Q): render as light-blue bubble via the
      // shared builder — same visual as the live WS path. Outgoing sends
      // (misrecorded into this session by the backend) are not received
      // content — skip them.
      if (m.injected && m.source) {
        if (isOutgoingInjection(m)) return;
        chat.appendChild(buildInjectedRow(m.text || '', m.source, m.timestamp, m.eventType, m.sender, m.senderTeam, undefined, m.delivery));
        return;
      }
      // Look ahead: if next message is a skill-activated system message,
      // render as a skill bubble (with label) instead of a plain user bubble.
      const next = msgs[i + 1];
      const isSkillActivation = next && next.type === 'system' && next.i18nKey === 'slash.skillActivated';
      const skillName = isSkillActivation ? (next.params && next.params.skillName) : null;
      const row = document.createElement('div');
      row.className = 'row user';
      if (m.text) {
        const bubble = document.createElement('div');
        bubble.className = 'bubble user';
        if (isSkillActivation && skillName) {
          const label = document.createElement('div');
          label.className = 'ask-label';
          label.textContent = t('chat.skillLabel', { skill: skillName });
          const content = document.createElement('div');
          content.textContent = m.text;
          bubble.appendChild(label);
          bubble.appendChild(content);
        } else {
          const t = document.createElement('div');
          t.textContent = m.text;
          bubble.appendChild(t);
        }
        row.appendChild(bubble);
      }
      (m.attachments || []).forEach(att => appendAttachmentBubble(row, att));
      // Timestamp + copy button (pill style, matching AI duration badge)
      if (m.timestamp && m.timestamp > 0) {
        const badge = document.createElement('div');
        badge.className = 'duration-badge';
        const timeSpan = document.createElement('span');
        timeSpan.className = 'duration-badge-time';
        timeSpan.setAttribute('data-ts', m.timestamp);
        timeSpan.textContent = formatHm(m.timestamp);
        timeSpan.title = '点击切换 12/24 小时制';
        timeSpan.addEventListener('click', toggleTimeFormat);
        badge.appendChild(timeSpan);
        if (m.text) {
          const div = document.createElement('span');
          div.className = 'duration-badge-divider';
          badge.appendChild(div);
          badge.appendChild(createMsgCopyButton(m.text));
        }
        row.appendChild(badge);
      } else if (m.text) {
        row.appendChild(createAiCopyBadge(m.timestamp, m.text));
      }
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
          const badge = createDurationBadgeElement(m.durationMs, i, m.timestamp, m.text);
          row.appendChild(badge);
        } else {
          row.appendChild(createAiCopyBadge(m.timestamp, m.text));
        }
        chat.appendChild(row);
      }
    } else if (m.type === 'tool') {
      // Inline render to avoid triggering saveMsg again
      const row = document.createElement('div');
      row.className = 'row tool';
      const card = document.createElement('div');
      card.className = 'tool-card';
      // Card tool: render standard tool card + separate card iframe below
      if (m.content && typeof m.content === 'string' && /^___\w+_HTML___/.test(m.content)) {
        const isError = m.isError;
        const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                             : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        const localLabel = localizeToolLabel(m.label);
        const localSummary = localizeToolSummary(m.summary, m.label);
        const lParts = localLabel.split('\n', 2);
        const lHtml = esc(lParts[0]) + ' &mdash; ' + esc(localSummary)
          + (lParts.length > 1 ? '<br><span class="tool-detail">' + esc(lParts[1]) + '</span>' : '');
        card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
          '<div class="content"><div class="label">' + lHtml + '</div></div>';
        row.appendChild(card);
        chat.appendChild(row);
        const cardRow = document.createElement('div');
        cardRow.className = 'row card-content';
        const cardContainer = document.createElement('div');
        cardRow.appendChild(cardContainer);
        chat.appendChild(cardRow);
        renderWithRegistry(cardContainer, m.content);
      } else {
        const isError = m.isError;
        const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                             : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        const detailHtml = buildToolDetail(m.input, m.label);
        const _delegatePrompt = buildDelegatePromptHtml(m.input);
        const highlightHtml = renderHighlightedContent(m.content, m.label);
        const bodyHtml = (detailHtml + _delegatePrompt + (highlightHtml || (m.content ? '<pre class="tool-body-pre">' + esc(m.content) + '</pre>' : ''))) || '';
        const hasBody = !!bodyHtml;
        const localLabel = localizeToolLabel(m.label);
        const localSummary = localizeToolSummary(m.summary, m.label);
        const lParts = localLabel.split('\n', 2);
        let _deviceTag = '';
        if (m.input) { try { const _inp = typeof m.input === 'string' ? JSON.parse(m.input) : m.input; if (_inp.device) _deviceTag = '<span class="tool-device-tag">' + esc(String(_inp.device)) + '</span>'; } catch {} }
        const lHtml = esc(lParts[0]) + ' &mdash; ' + esc(localSummary) + _deviceTag
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
      // Add danger level styling if applicable
      const level = m.dangerLevel || 0;
      const dangerI18nKeys = {
        1: { cls: 'perm-warning', key: 'chat.permLevel.warning' },
        2: { cls: 'perm-dangerous', key: 'chat.permLevel.dangerous' },
        3: { cls: 'perm-critical', key: 'chat.permLevel.critical' }
      };
      const dInfo = dangerI18nKeys[level];
      if (dInfo) {
        bubble.classList.add(dInfo.cls);
        const banner = document.createElement('div');
        banner.className = 'perm-danger-banner';
        banner.innerHTML = '<span>' + escapeHtml(t(dInfo.key, { detail: '' })) + '</span>';
        bubble.appendChild(banner);
      }
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
          const badge = createDurationBadgeElement(m.durationMs, i, m.timestamp);
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
      // Skill-activated system messages are rendered as skill bubbles by the user handler above
      if (m.i18nKey === 'slash.skillActivated') return;
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
  if (activeView) activeView.stream.scrollSnapped = true;
  // Schedule deferred scrolls to catch async iframe height changes from card rendering.
  requestAnimationFrame(() => { chat.scrollTop = chat.scrollHeight; if (activeView) activeView.stream.scrollSnapped = true; });
  setTimeout(() => { chat.scrollTop = chat.scrollHeight; if (activeView) activeView.stream.scrollSnapped = true; }, 100);
  setTimeout(() => { chat.scrollTop = chat.scrollHeight; if (activeView) activeView.stream.scrollSnapped = true; }, 500);
}

// ---------- Replay backend history messages into the DOM ----------
// Same logic as restoreFromStorage but takes messages array directly (from backend).
// Uses DocumentFragment to batch DOM insertions and avoids redundant scroll operations.
export function restoreFromBackendHistory(msgs, opts = {}) {
  const { scrollToBottom = true } = opts;
  const chat = activeView.dom.chat;
  const fragment = document.createDocumentFragment();
  let skipMsg = false;
  // Defer expensive markdown+KaTeX rendering into post-append batches.
  // DOM elements are created synchronously with plain text; innerHTML is upgraded via rAF.
  const pendingRenders = [];
  const deferMd = (el, text, parseVoice = true) => {
    el.textContent = text;
    pendingRenders.push({ el, text, parseVoice });
  };
  msgs.forEach((m, i) => {
    if (skipMsg) { skipMsg = false; return; }
    if (m.type === 'user') {
      // Injected messages (task P+Q): light-blue bubble via shared builder.
      // Skip outgoing sends misrecorded into this session (see
      // isOutgoingInjection) — the bubble is for received content only.
      if (m.injected && m.source) {
        if (isOutgoingInjection(m)) return;
        // deferMd with parseVoice=false (same as the live path): injected
        // messages join the rAF markdown batch — no synchronous render storm
        // on hard-refresh (P0-2).
        fragment.appendChild(buildInjectedRow(m.text || '', m.source, m.timestamp, m.eventType, m.sender, m.senderTeam,
          (el, text) => deferMd(el, text, false), m.delivery));
        return;
      }
      // Look ahead: if next message is a skill-activated system message,
      // render as a skill bubble (with label) instead of a plain user bubble.
      const next = msgs[i + 1];
      const isSkillActivation = next && next.type === 'system' && next.i18nKey === 'slash.skillActivated';
      const skillName = isSkillActivation ? (next.params && next.params.skillName) : null;
      const row = document.createElement('div');
      row.className = 'row user';
      if (m.text) {
        const bubble = document.createElement('div');
        bubble.className = 'bubble user';
        if (isSkillActivation && skillName) {
          // Render as skill bubble with label
          const label = document.createElement('div');
          label.className = 'ask-label';
          label.textContent = t('chat.skillLabel', { skill: skillName });
          const content = document.createElement('div');
          content.textContent = m.text;
          bubble.appendChild(label);
          bubble.appendChild(content);
          skipMsg = true; // skip the system message on next iteration
        } else {
          const t = document.createElement('div');
          t.textContent = m.text;
          bubble.appendChild(t);
        }
        row.appendChild(bubble);
      }
      (m.attachments || []).forEach(att => appendAttachmentBubble(row, att));
      // Timestamp + copy button (pill style, matching AI duration badge)
      if (m.timestamp && m.timestamp > 0) {
        const badge = document.createElement('div');
        badge.className = 'duration-badge';
        const timeSpan = document.createElement('span');
        timeSpan.className = 'duration-badge-time';
        timeSpan.setAttribute('data-ts', m.timestamp);
        timeSpan.textContent = formatHm(m.timestamp);
        timeSpan.title = '点击切换 12/24 小时制';
        timeSpan.addEventListener('click', toggleTimeFormat);
        badge.appendChild(timeSpan);
        if (m.text) {
          const div = document.createElement('span');
          div.className = 'duration-badge-divider';
          badge.appendChild(div);
          badge.appendChild(createMsgCopyButton(m.text));
        }
        row.appendChild(badge);
      }
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
        deferMd(tContent, m.thinking);
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
        deferMd(bubble, m.text || '');
        row.appendChild(bubble);
        if (m.durationMs != null && m.durationMs > 0) {
          const badge = createDurationBadgeElement(m.durationMs, i, m.timestamp, m.text);
          row.appendChild(badge);
        } else {
          row.appendChild(createAiCopyBadge(m.timestamp, m.text));
        }
        fragment.appendChild(row);
      }
    } else if (m.type === 'tool') {
      const row = document.createElement('div');
      row.className = 'row tool';
      const card = document.createElement('div');
      card.className = 'tool-card';
      // Card tool: render standard tool card + separate card iframe below
      if (m.content && typeof m.content === 'string' && /^___\w+_HTML___/.test(m.content)) {
        const isError = m.isError;
        const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                             : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        const localLabel = localizeToolLabel(m.label);
        const localSummary = localizeToolSummary(m.summary, m.label);
        const lParts = localLabel.split('\n', 2);
        const lHtml = esc(lParts[0]) + ' &mdash; ' + esc(localSummary)
          + (lParts.length > 1 ? '<br><span class="tool-detail">' + esc(lParts[1]) + '</span>' : '');
        card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
          '<div class="content"><div class="label">' + lHtml + '</div></div>';
        row.appendChild(card);
        fragment.appendChild(row);
        const cardRow = document.createElement('div');
        cardRow.className = 'row card-content';
        const cardContainer = document.createElement('div');
        cardRow.appendChild(cardContainer);
        fragment.appendChild(cardRow);
        renderWithRegistry(cardContainer, m.content);
      } else {
        const isError = m.isError;
        // Pop tool: rainbow filename + clickable card (shared with live renderTool)
        if (applyPopCard(card, m.label, m.summary, m.input, isError)) {
          row.appendChild(card);
          fragment.appendChild(row);
        } else {
        const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                             : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
        const detailHtml = buildToolDetail(m.input, m.label);
        const _delegatePrompt2 = buildDelegatePromptHtml(m.input);
        const highlightHtml = renderHighlightedContent(m.content, m.label);
        const bodyHtml = (detailHtml + _delegatePrompt2 + (highlightHtml || (m.content ? '<pre class="tool-body-pre">' + esc(m.content) + '</pre>' : ''))) || '';
        const hasBody = !!bodyHtml;
        const localLabel = localizeToolLabel(m.label);
        const localSummary = localizeToolSummary(m.summary, m.label);
        const lParts = localLabel.split('\n', 2);
        let _deviceTag2 = '';
        if (m.input) { try { const _inp2 = typeof m.input === 'string' ? JSON.parse(m.input) : m.input; if (_inp2.device) _deviceTag2 = '<span class="tool-device-tag">' + esc(String(_inp2.device)) + '</span>'; } catch {} }
        const lHtml = esc(lParts[0]) + ' &mdash; ' + esc(localSummary) + _deviceTag2
          + (lParts.length > 1 ? '<br><span class="tool-detail">' + esc(lParts[1]) + '</span>' : '');
        card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
          '<div class="content"><div class="label">' + lHtml + '</div>' +
          (bodyHtml ? '<div class="body">' + bodyHtml + '</div>' : '') + '</div>';
        row.appendChild(card);
        fragment.appendChild(row);
        if (hasBody) attachToolClick(card);
        }
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
      }
    } else if (m.type === 'askPermission') {
      // Render as disabled permission prompt (will be replaced by interactive version if still pending)
      const row = document.createElement('div');
      row.className = 'row ai';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      // Add danger level styling if applicable
      const level = m.dangerLevel || 0;
      const dangerI18nKeys = {
        1: { cls: 'perm-warning', key: 'chat.permLevel.warning' },
        2: { cls: 'perm-dangerous', key: 'chat.permLevel.dangerous' },
        3: { cls: 'perm-critical', key: 'chat.permLevel.critical' }
      };
      const dInfo = dangerI18nKeys[level];
      if (dInfo) {
        bubble.classList.add(dInfo.cls);
        const banner = document.createElement('div');
        banner.className = 'perm-danger-banner';
        banner.innerHTML = '<span>' + escapeHtml(t(dInfo.key, { detail: '' })) + '</span>';
        bubble.appendChild(banner);
      }
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
        deferMd(aContent, m.answer);
        aBubble.appendChild(aLabel);
        aBubble.appendChild(aContent);
        aRow.appendChild(aBubble);
        if (m.durationMs != null && m.durationMs > 0) {
          const badge = createDurationBadgeElement(m.durationMs, i, m.timestamp);
          aRow.appendChild(badge);
        }
        fragment.appendChild(aRow);
      }
    } else if (m.type === 'agent') {
      const row = document.createElement('div');
      row.className = 'row ai agent-row';
      const bubble = document.createElement('div');
      bubble.className = 'bubble ai';
      deferMd(bubble, m.text || '');
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
      // Skill-activated system messages are rendered as skill bubbles by the user handler above;
      // skip them here as a safety net (e.g. if the preceding user message was missing).
      if (m.i18nKey === 'slash.skillActivated') return;
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
    if (activeView) activeView.stream.scrollSnapped = true;
  }
  // Batch-upgrade deferred markdown rendering (marked.parse + KaTeX) via rAF.
  // Processes a few elements per frame to avoid blocking the main thread.
  const BATCH_SIZE = 6;
  let ri = 0;
  function upgradeBatch() {
    const end = Math.min(ri + BATCH_SIZE, pendingRenders.length);
    for (; ri < end; ri++) {
      const { el, text, parseVoice } = pendingRenders[ri];
      el.innerHTML = renderMarkdownWithMath(text, parseVoice);
    }
    if (ri < pendingRenders.length) {
      requestAnimationFrame(upgradeBatch);
    } else if (scrollToBottom) {
      chat.scrollTop = chat.scrollHeight;
      if (activeView) activeView.stream.scrollSnapped = true;
    }
  }
  if (pendingRenders.length > 0) {
    requestAnimationFrame(upgradeBatch);
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
      if (!safeSetItem(LS_SESSIONS_KEY, JSON.stringify(all))) {
        pruneAndRetrySetSessions(all, state.activeSessionId);
      }
      // Re-render chat with the migrated data
      if (activeView?.dom?.chat) {
        cleanupCardIframes(activeView.dom.chat);
        activeView.dom.chat.innerHTML = '';
      }
      restoreFromStorage();
    }
  } catch(e) {}
}
