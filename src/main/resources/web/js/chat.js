// chat.js — Chat rendering module for Nebflow
// All DOM manipulation for messages, bubbles, tool cards, option boxes, and status.

import state, { AGENT_PALETTE } from './state.js';
import { activeView, setActiveView } from './chatView.js';
import { renderMarkdownWithMath, escapeHtml, buildToolDetail, buildDelegatePromptHtml, attachToolClick, smartScroll, playSpinner, stopSpinner, localizeToolLabel, localizeToolSummary, renderHighlightedContent, highlightCode, createMsgCopyButton } from './utils.js';
import { renderWithRegistry } from './cardRegistry.js';
import { t } from './i18n.js';
import { sendWs } from './ws.js';

// ---------- Time format preference (12h / 24h toggle) ----------
const TIME_FORMAT_KEY = 'nebflow:timeFormat';
let _timeFormat = localStorage.getItem(TIME_FORMAT_KEY) || '24h';

function refreshAllTimestamps() {
  document.querySelectorAll('[data-ts]').forEach(el => {
    const ts = parseInt(el.getAttribute('data-ts'), 10);
    if (ts) el.textContent = formatHm(ts);
  });
}

export function toggleTimeFormat() {
  _timeFormat = _timeFormat === '24h' ? '12h' : '24h';
  localStorage.setItem(TIME_FORMAT_KEY, _timeFormat);
  refreshAllTimestamps();
}

// ---------- Voice TTS player ----------
// Module-level singleton. Manages sequential playback of <voice> blocks:
// fetches WAV from /api/tts, plays them in order, supports click-to-replay
// and a mute toggle (persisted in localStorage).
const VoicePlayer = {
  queue: [],
  processing: false,
  muted: localStorage.getItem('voiceMuted') === 'true',
  _current: null, // { audio, url, element, resolve }
  _cache: new Map(), // text → Promise<blobUrl>（流式预取 + 缓存，有界 LRU）
  _cacheLimit: 20,   // evicted entries get URL.revokeObjectURL — blob URLs pin memory until revoked

  /** Insert into the bounded LRU cache. Evicts the oldest entry and revokes
   *  its blob URL once the (possibly in-flight) fetch resolves. */
  _cacheSet(text, promise) {
    if (this._cache.has(text)) this._cache.delete(text);
    this._cache.set(text, promise);
    while (this._cache.size > this._cacheLimit) {
      const [oldestKey, oldestPromise] = this._cache.entries().next().value;
      this._cache.delete(oldestKey);
      Promise.resolve(oldestPromise).then(url => { if (url) URL.revokeObjectURL(url); }).catch(() => {});
    }
  },

  /** 流式预取：检测到完整 voice 块时立即发起 TTS 请求（并行，不等结果）。 */
  prefetch(text) {
    if (!text || this._cache.has(text)) return;
    this._cacheSet(text, this._fetchTts(text));
  },

  /** 调用后端 TTS API，返回 blobUrl 的 Promise。 */
  async _fetchTts(text) {
    try {
      const resp = await fetch('/api/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text }),
      });
      if (!resp.ok) return null;
      const blob = await resp.blob();
      return URL.createObjectURL(blob);
    } catch (e) {
      return null;
    }
  },

  /** 获取音频 URL：有缓存用缓存（可能还在 in-flight），没有就发请求。 */
  async _getAudioUrl(text) {
    if (this._cache.has(text)) {
      // LRU touch — move to newest so frequently replayed clips survive.
      const p = this._cache.get(text);
      this._cache.delete(text);
      this._cache.set(text, p);
      return await p;
    }
    const p = this._fetchTts(text);
    this._cacheSet(text, p);
    return await p;
  },

  /** Add a voice block to the playback queue. */
  enqueue(text, element) {
    this.queue.push({ text, element });
    if (!this.processing) this._processQueue();
  },

  /** Process queue items sequentially. Playback is ordered, but fetches are parallel. */
  async _processQueue() {
    this.processing = true;
    let cancelled = false;
    while (this.queue.length > 0 && !cancelled) {
      const item = this.queue.shift();
      const result = await this._playItem(item);
      if (result === 'cancelled') cancelled = true;
    }
    this.processing = false;
    if (this.queue.length > 0) this._processQueue();
  },

  /** Play a single voice block. */
  async _playItem({ text, element }) {
    if (this.muted) return 'done';
    try {
      const url = await this._getAudioUrl(text);
      if (!url) return 'done';
      const result = await new Promise(resolve => {
        const audio = new Audio(url);
        this._current = { audio, url, element, resolve };
        if (element) element.classList.add('playing');
        audio.onended = () => resolve('done');
        audio.onerror = () => resolve('done');
        audio.play().catch(() => resolve('done'));
      });
      return result;
    } catch (e) {
      return 'done';
    } finally {
      if (element) element.classList.remove('playing');
      this._current = null;
    }
  },

  /** Cancel current playback and clear the queue. */
  _cancel() {
    this.queue = [];
    if (this._current) {
      this._current.audio.pause();
      // Revoke the in-flight blob URL and drop its cache entry so the revoked
      // URL can never be replayed from cache (a replay simply re-fetches).
      const cur = this._current;
      const text = cur.element ? cur.element.textContent : null;
      if (text) this._cache.delete(text);
      if (cur.url) URL.revokeObjectURL(cur.url);
      cur.resolve('cancelled');
    }
  },

  /** Click-to-replay. */
  replay(element) {
    this._cancel();
    this.enqueue(element.textContent, element);
  },

  /** Toggle mute. */
  toggleMute() {
    this.muted = !this.muted;
    localStorage.setItem('voiceMuted', String(this.muted));
    if (this.muted) this._cancel();
    sendWs({ type: 'setVoiceMuted', muted: this.muted });
    return this.muted;
  },
};

// SVG icons for the voice toggle button
const VOICE_SVG_ON = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/></svg>';
const VOICE_SVG_OFF = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>';

// Initialize voice toggle buttons (runs after DOM is ready — module scripts are deferred)
document.querySelectorAll('.voice-toggle').forEach(btn => {
  if (VoicePlayer.muted) {
    btn.classList.add('muted');
    btn.innerHTML = VOICE_SVG_OFF;
  }
  btn.addEventListener('click', () => {
    const muted = VoicePlayer.toggleMute();
    btn.classList.toggle('muted', muted);
    btn.innerHTML = muted ? VOICE_SVG_OFF : VOICE_SVG_ON;
  });
});

// Unlock audio on first user interaction (browsers block autoplay without gesture)
let _audioUnlocked = false;
function _unlockAudio() {
  if (_audioUnlocked) return;
  _audioUnlocked = true;
  const s = new Audio();
  s.play().then(() => s.pause()).catch(() => {});
}
document.addEventListener('click', _unlockAudio, { once: true });
document.addEventListener('keydown', _unlockAudio, { once: true });

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

/** Reflect the WebSocket connection state onto the send button. The send button
 *  doubles as the connection indicator: when disconnected it goes grey/red
 *  (.disconnected) to signal sends are unavailable. Called from ws.js on
 *  connect/disconnect and from setBusy/clearBusy. */
export function refreshSendButtonState() {
  const btn = activeView?.dom?.sendBtn || document.getElementById('send-btn');
  if (!btn) return;
  btn.classList.toggle('disconnected', !state.connected);
}

export function setBusy(sessionId) {
  if (sessionId) state.busySessionIds.add(sessionId);
  window.dispatchEvent(new CustomEvent('session-busy', { detail: { sessionId, busy: true } }));
  if (activeView && activeView.sessionId === sessionId) {
    const { sendBtn, stopBtn, statusWrap } = activeView.dom;
    if (sendBtn) sendBtn.style.display = 'none';
    if (stopBtn) stopBtn.style.display = 'flex';
    if (statusWrap) statusWrap.classList.add('on');
  }
}

export function clearBusy(sessionId) {
  state.busySessionIds.delete(sessionId);
  window.dispatchEvent(new CustomEvent('session-busy', { detail: { sessionId, busy: false } }));
  if (activeView && activeView.sessionId === sessionId) {
    const { input, sendBtn, stopBtn, statusWrap } = activeView.dom;
    if (sendBtn) sendBtn.style.display = 'flex';
    if (stopBtn) stopBtn.style.display = 'none';
    if (statusWrap) statusWrap.classList.remove('on');
    if (input) input.focus();
    refreshSendButtonState();
  }
}

// ---------- User bubble ----------
export function renderUserBubble(text, attachments, timestamp) {
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

  // Attachment bubbles (below text)
  (attachments || []).forEach(att => {
    const bubble = document.createElement('div');
    bubble.className = 'bubble user att-bubble';
    if (att.type === 'image' && att.preview && typeof att.preview === 'string' && att.preview.startsWith('data:')) {
      const img = document.createElement('img');
      img.className = 'att-img';
      img.src = att.preview;
      img.title = att.name || '';
      img.onerror = () => { img.style.display = 'none'; };
      bubble.appendChild(img);
    } else {
      const tag = document.createElement('span');
      tag.className = 'att-file-tag';
      tag.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;flex-shrink:0"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg><span style="margin-left:2px">' + escapeHtml(att.name || 'file') + '</span>';
      bubble.appendChild(tag);
    }
    row.appendChild(bubble);
  });

  // Timestamp + copy button (pill style, matching AI duration badge)
  const ts = timestamp || Date.now();
  const badge = document.createElement('div');
  badge.className = 'duration-badge';
  const timeSpan = document.createElement('span');
  timeSpan.className = 'duration-badge-time';
  timeSpan.setAttribute('data-ts', ts);
  timeSpan.textContent = formatHm(ts);
  timeSpan.title = '点击切换 12/24 小时制';
  timeSpan.addEventListener('click', toggleTimeFormat);
  badge.appendChild(timeSpan);
  if (text) {
    const div = document.createElement('span');
    div.className = 'duration-badge-divider';
    badge.appendChild(div);
    badge.appendChild(createMsgCopyButton(text));
  }
  row.appendChild(badge);

  chat.appendChild(row);
  chat.scrollTop = chat.scrollHeight;
  return { type: 'user', text, timestamp: ts, attachments: (attachments || []).map(a => ({ type: a.type, name: a.name, preview: a.preview })) };
}

// ---------- Injected bubble (task P+Q: tool-injected prompts & result notifications) ----------
// Mail/Delegate/SubTask/Skill/Flow/ExternalEvent injections arrive as WS
// {type:"user", injected:true, source} and are persisted as UiMessage.User
// with source. Rendered as a light-blue bubble on the user side (right),
// visually distinct from the user's own green bubble.

/** Map backend injection source → display label. Sources are tool/protocol
 *  names (proper nouns) — no i18n needed. Unknown sources are capitalized. */
const INJECTED_SOURCE_LABELS = {
  mail: 'Mail', delegate: 'Delegate', subtask: 'SubTask', skill: 'Skill',
  ask: 'Ask', flow: 'Flow', tool: 'Tool', api: 'API',
};
export function injectedSourceLabel(source) {
  if (!source) return '';
  return INJECTED_SOURCE_LABELS[source] || source.charAt(0).toUpperCase() + source.slice(1);
}

/** Build a row element for an injected message (pure builder — no DOM append,
 *  no scroll). Shared by live render (renderInjectedBubble) and history
 *  restore (persistence.js) so both paths render identically. */
export function buildInjectedRow(text, source, timestamp) {
  const row = document.createElement('div');
  row.className = 'row user';

  const bubble = document.createElement('div');
  bubble.className = 'bubble injected';
  const label = document.createElement('div');
  label.className = 'ask-label injected-source-label';
  label.textContent = injectedSourceLabel(source);
  const content = document.createElement('div');
  content.textContent = text;
  bubble.appendChild(label);
  bubble.appendChild(content);
  row.appendChild(bubble);

  // Timestamp + copy button (same pill badge as user bubble) — only when a
  // real timestamp is provided; history restore may lack one.
  if (timestamp) {
    const badge = document.createElement('div');
    badge.className = 'duration-badge';
    const timeSpan = document.createElement('span');
    timeSpan.className = 'duration-badge-time';
    timeSpan.setAttribute('data-ts', timestamp);
    timeSpan.textContent = formatHm(timestamp);
    timeSpan.title = '点击切换 12/24 小时制';
    timeSpan.addEventListener('click', toggleTimeFormat);
    badge.appendChild(timeSpan);
    if (text) {
      const div = document.createElement('span');
      div.className = 'duration-badge-divider';
      badge.appendChild(div);
      badge.appendChild(createMsgCopyButton(text));
    }
    row.appendChild(badge);
  }
  return row;
}

/** Live-render an injected message into the active view. */
export function renderInjectedBubble(text, source, timestamp) {
  const chat = activeView.dom.chat;
  const row = buildInjectedRow(text, source, timestamp || Date.now());
  chat.appendChild(row);
  chat.scrollTop = chat.scrollHeight;
}

/** Format epoch millis as HH:MM (24h) or h:MM AM/PM (12h), respecting user preference */
export function formatHm(ms) {
  const d = new Date(ms);
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (_timeFormat === '12h') {
    let h = d.getHours();
    const ampm = h >= 12 ? 'PM' : 'AM';
    h = h % 12 || 12;
    return h + ':' + mm + ' ' + ampm;
  }
  return String(d.getHours()).padStart(2, '0') + ':' + mm;
}

// ---------- rAF stream render scheduler ----------
// Coalesces high-frequency streaming deltas into one DOM render per frame.
// Slots are keyed per (view, key) so concurrent views (main window + an open
// popup) and concurrent streams (ai / agent:<id> / ask) coalesce independently.
// The target is refreshed on every delta, so the pending rAF always renders
// the latest accumulated text into the latest bubble. This generalizes the
// module-level rAF pattern of appendThinkingDelta.
function scheduleStreamRender(view, key, target, render) {
  if (!view._streamRafSlots) view._streamRafSlots = {};
  let slot = view._streamRafSlots[key];
  if (!slot) slot = view._streamRafSlots[key] = { raf: null, target: null, render: null };
  slot.target = target;
  slot.render = render;
  if (!slot.raf) {
    slot.raf = requestAnimationFrame(() => {
      slot.raf = null;
      const t = slot.target; slot.target = null;
      const r = slot.render; slot.render = null;
      if (t && r) r(t);
    });
  }
}

/** Cancel a pending stream render — finish*() calls this before its final render. */
function cancelStreamRender(view, key) {
  const slot = view && view._streamRafSlots ? view._streamRafSlots[key] : null;
  if (slot && slot.raf) {
    cancelAnimationFrame(slot.raf);
    slot.raf = null;
    slot.target = null;
    slot.render = null;
  }
}

/** rAF-time scroll — mirrors smartScroll()'s snapped || near-bottom logic but
 *  scrolls the chat element captured at schedule time (activeView may point
 *  elsewhere by fire time). Same approach as appendThinkingDelta's rAF. */
function rafScrollChat(target) {
  const threshold = 60;
  if (target.snapped || target.chat.scrollHeight - target.chat.scrollTop - target.chat.clientHeight < threshold) {
    target.chat.scrollTop = target.chat.scrollHeight;
  }
}

// ---------- AI text streaming ----------
export function appendAiText(text) {
  const view = activeView;
  const chat = view.dom.chat;
  view.stream.aiText += text;
  // 流式检测：发现完整的 <voice>...</voice> 块立即并行预取 TTS
  const voiceMatches = view.stream.aiText.match(/<voice>([\s\S]+?)<\/voice>/g);
  if (voiceMatches) {
    voiceMatches.forEach(m => {
      const content = m.replace(/<\/?voice>/g, '').trim();
      VoicePlayer.prefetch(content);
    });
  }
  if (view.stream.currentAiBubble && view.stream.currentAiBubble.classList.contains('thinking-placeholder')) {
    if (window.__stopThinkingTimer) window.__stopThinkingTimer();
    view.stream.currentAiBubble.classList.remove('thinking-placeholder');
    view.stream.currentAiBubble.innerHTML = '';
  }
  if (!view.stream.currentAiBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    view.stream.currentAiBubble = document.createElement('div');
    view.stream.currentAiBubble.className = 'bubble ai';
    row.appendChild(view.stream.currentAiBubble);
    chat.appendChild(row);
  }
  // Preserve any option box across re-renders: keep it OUT of the bubble while
  // streaming so innerHTML replacement can't destroy its event listeners.
  const askBox = view.stream.currentAiBubble.querySelector('.option-box');
  if (askBox) { askBox.remove(); view.stream.aiStreamAskBox = askBox; }
  // rAF-throttled render — accumulate on every delta, render at most once per
  // frame. The full accumulated text lives on the bubble node so the rAF never
  // depends on which view is active at fire time.
  const bubble = view.stream.currentAiBubble;
  bubble._nfText = view.stream.aiText || '';
  scheduleStreamRender(view, 'ai',
    { bubble, chat, snapped: view.stream.scrollSnapped },
    (target) => {
      if (!target.bubble.isConnected) return;
      target.bubble.innerHTML = renderMarkdownWithMath(target.bubble._nfText || '') + '<span class="cursor"></span>';
      const box = view.stream.aiStreamAskBox;
      if (box) target.bubble.appendChild(box);
      rafScrollChat(target);
    });
}

export function finishAi(durationMs, model) {
  // Cancel any pending throttled render — this final render supersedes it.
  cancelStreamRender(activeView, 'ai');
  if (activeView.stream.currentAiBubble) {
    if (!activeView.stream.aiText || !activeView.stream.aiText.trim()) {
      const row = activeView.stream.currentAiBubble.closest('.row');
      if (row) row.remove();
      activeView.stream.currentAiBubble = null;
      activeView.stream.aiText = '';
      activeView.stream.aiStreamAskBox = null;
      return null;
    }
    const askBox = activeView.stream.aiStreamAskBox || activeView.stream.currentAiBubble.querySelector('.option-box');
    if (askBox) askBox.remove();
    const bubble = activeView.stream.currentAiBubble;
    bubble.innerHTML = renderMarkdownWithMath(activeView.stream.aiText || '');
    if (askBox) bubble.appendChild(askBox);
    activeView.stream.aiStreamAskBox = null;
    const ts = Date.now();
    let hasBadge = false;
    if (durationMs != null && durationMs > 0) {
      const seed = activeView.dom.chat.querySelectorAll('.duration-badge').length;
      renderDurationBadge(bubble, durationMs, model, seed, ts, activeView.stream.aiText);
      hasBadge = true;
    }
    // Copy button for AI message — use duration-badge pill with timestamp,
    // matching user message style. No phrase/model when no duration.
    if (!hasBadge) {
      const aiRow = bubble.closest('.row');
      if (aiRow) {
        const badge = document.createElement('div');
        badge.className = 'duration-badge';
        const timeSpan = document.createElement('span');
        timeSpan.className = 'duration-badge-time';
        timeSpan.setAttribute('data-ts', ts);
        timeSpan.textContent = formatHm(ts);
        timeSpan.title = '点击切换 12/24 小时制';
        timeSpan.addEventListener('click', toggleTimeFormat);
        badge.appendChild(timeSpan);
        if (activeView.stream.aiText) {
          const div = document.createElement('span');
          div.className = 'duration-badge-divider';
          badge.appendChild(div);
          badge.appendChild(createMsgCopyButton(activeView.stream.aiText));
        }
        aiRow.appendChild(badge);
      }
    }
    // Trigger voice TTS: enqueue all <voice> blocks for sequential playback,
    // and attach click-to-replay handlers on the green text.
    bubble.querySelectorAll('.voice-block').forEach(el => {
      el.addEventListener('click', () => VoicePlayer.replay(el));
      VoicePlayer.enqueue(el.textContent, el);
    });
    const result = { type: 'ai', text: activeView.stream.aiText, durationMs, model, timestamp: ts };
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
 * Shows the full phrase with duration embedded, plus optional model tag and timestamp.
 * @param {number} durationMs
 * @param {string} [model]
 * @param {number} [seed]
 * @param {number} [timestamp] - epoch millis for display
 * @returns {HTMLElement}
 */
export function createDurationBadgeElement(durationMs, model, seed, timestamp, copyText) {
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

  if (timestamp) {
    const div = document.createElement('span');
    div.className = 'duration-badge-divider';
    badge.appendChild(div);
    const timeSpan = document.createElement('span');
    timeSpan.className = 'duration-badge-time';
    timeSpan.setAttribute('data-ts', timestamp);
    timeSpan.textContent = formatHm(timestamp);
    timeSpan.title = '点击切换 12/24 小时制';
    timeSpan.addEventListener('click', toggleTimeFormat);
    badge.appendChild(timeSpan);
  }

  if (copyText) {
    const div = document.createElement('span');
    div.className = 'duration-badge-divider';
    badge.appendChild(div);
    const copyBtn = createMsgCopyButton(copyText);
    badge.appendChild(copyBtn);
  }

  return badge;
}

/**
 * Render a subtle duration badge below an AI bubble.
 */
export function renderDurationBadge(bubble, durationMs, model, seed, timestamp, copyText) {
  if (!bubble) return;
  const row = bubble.closest('.row');
  if (!row) return;
  const badge = createDurationBadgeElement(durationMs, model, seed, timestamp, copyText);
  row.appendChild(badge);
}

// ---------- Multi-agent rendering ----------
export function appendAgentText(agentId, text) {
  const view = activeView;
  const chat = view.dom.chat;
  if (!view.stream.agentBubbles[agentId]) {
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
    view.stream.agentBubbles[agentId] = { bubble, text: '', row, badge };
  }
  const a = view.stream.agentBubbles[agentId];
  a.text += text;
  // rAF-throttled render (same scheduler as appendAiText)
  a.bubble._nfText = a.text;
  scheduleStreamRender(view, 'agent:' + agentId,
    { bubble: a.bubble, chat, snapped: view.stream.scrollSnapped },
    (target) => {
      if (!target.bubble.isConnected) return;
      target.bubble.innerHTML = renderMarkdownWithMath(target.bubble._nfText || '') + '<span class="cursor"></span>';
      rafScrollChat(target);
    });
}

export function finishAgent(agentId) {
  cancelStreamRender(activeView, 'agent:' + agentId);
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

/** Render a Pop tool card onto an existing card element.
 *  Shared between live renderTool and history restoreFromBackendHistory.
 *  Returns true if the card was handled (Pop tool), false otherwise. */
export function applyPopCard(card, label, summary, inputJson, isError) {
  const _toolName = label ? label.split('(')[0].split('\n')[0].trim() : '';
  if (_toolName !== 'Pop' || !inputJson) return false;

  const icon = isError
    ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
    : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  let popFilePath = '';
  let popTitle = '';
  try {
    const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
    popFilePath = inp.filePath || '';
    popTitle = inp.title || '';
  } catch {}
  const popFileName = popFilePath.split('/').pop() || popFilePath;
  const popLocalLabel = localizeToolLabel(label);
  const popLocalSummary = localizeToolSummary(summary, label);
  const popLabelParts = popLocalLabel.split('\n', 2);
  const rainbowName = '<span class="pop-rainbow-name">' + escapeHtml(popTitle || popFileName) + '</span>';
  const summaryHtml = escapeHtml(popLocalSummary).replace(escapeHtml(popFileName), rainbowName);
  const labelHtml = escapeHtml(popLabelParts[0]) + ' &mdash; ' + summaryHtml
    + (popLabelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(popLabelParts[1]) + '</span>' : '');
  card.classList.add('pop-tool-card');
  card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
    '<div class="content"><div class="label">' + labelHtml + '</div></div>';
  if (popFilePath) {
    card.addEventListener('click', () => {
      const fileName = popFilePath.split('/').pop() || popFilePath;
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: {
          id: 'file:' + popFilePath,
          title: popTitle || fileName,
          itemType: '',
          content: '',
          absPath: popFilePath,
          pinned: true
        }
      }));
    });
  }
  return true;
}

export function renderTool(label, summary, content, isError, inputJson, sessionId) {
  const sid = sessionId || activeView.sessionId;
  const chat = activeView.dom.chat;

  // Cancel any pending streaming rAF so it doesn't overwrite the final render
  cancelToolStreamRAF();

  // Reuse the pending card's DOM node for a smooth transition from streaming
  // state to final state — no visual jump from remove+recreate.
  // When multiple tools are called in one LLM response, sessionToolCards[sid]
  // (a single slot) may point to a different tool's card. Search by
  // data-tool-label to find the correct one.
  cancelToolStreamRAF();
  let pending = state.sessionToolCards[sid];
  if (pending) {
    const cardEl = pending.querySelector('.tool-card');
    const existingLabel = cardEl?.dataset.toolLabel;
    if (existingLabel && label && existingLabel !== label) {
      // Slot points to a different tool's card — search DOM for the right one
      pending = null;
      const cards = chat.querySelectorAll('.row.tool .tool-card--pending');
      for (const c of cards) {
        if (c.dataset.toolLabel === label) { pending = c.closest('.row'); break; }
      }
    }
  } else {
    // No card in slot — search DOM by label
    const cards = chat.querySelectorAll('.row.tool .tool-card--pending');
    for (const c of cards) {
      if (c.dataset.toolLabel === label || !c.dataset.toolLabel) { pending = c.closest('.row'); break; }
    }
  }
  // Only clear the slot if we're consuming the card it points to
  if (pending === state.sessionToolCards[sid]) delete state.sessionToolCards[sid];
  let row, card;
  if (pending && pending.isConnected) {
    row = pending;
    card = row.querySelector('.tool-card');
    card.classList.remove('tool-card--pending');
    card.innerHTML = '';
  } else {
    row = document.createElement('div');
    row.className = 'row tool';
    card = document.createElement('div');
    card.className = 'tool-card';
    row.appendChild(card);
    chat.appendChild(row);
  }
  // NebLink tool marker
  if (label && label.startsWith('[NebLink]')) row.classList.add('neblink-row');

  // Card tool: render standard tool card (icon + label) then card iframe below.
  // This unifies Card display with other tools — spinner → checkmark transition,
  // consistent tool card header — while the rendered HTML card appears separately.
  if (content && typeof content === 'string' && /^___\w+_HTML___/.test(content)) {
    const cIcon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                         : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
    const cLocalLabel = localizeToolLabel(label);
    const cLocalSummary = localizeToolSummary(summary, label);
    const cLabelParts = cLocalLabel.split('\n', 2);
    const cLabelHtml = escapeHtml(cLabelParts[0]) + ' &mdash; ' + escapeHtml(cLocalSummary)
      + (cLabelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(cLabelParts[1]) + '</span>' : '');
    card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + cIcon + '</span>' +
      '<div class="content"><div class="label">' + cLabelHtml + '</div></div>';
    // Card iframe in a separate row below the tool card
    const cardRow = document.createElement('div');
    cardRow.className = 'row card-content';
    const cardContainer = document.createElement('div');
    cardRow.appendChild(cardContainer);
    row.after(cardRow);
    renderWithRegistry(cardContainer, content, label);
    smartScroll();
    return { type: 'tool', label, summary, content, isError, input: inputJson };
  }

  // Tools where the input parameters are more useful than the result.
  // For these, render the tool_use input (recipient, message, action) instead
  // of the tool_result content ("Message sent...").
  const _toolName = label ? label.split('(')[0].split('\n')[0].trim() : '';

  // Pop tool: rainbow filename inline in the label + clickable to re-open.
  if (applyPopCard(card, label, summary, inputJson, isError)) {
    smartScroll();
    return { type: 'tool', label, summary, content: null, isError, input: inputJson };
  }

  if (_toolName === 'Mail' && inputJson) {
    const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                         : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
    const localLabel = localizeToolLabel(label);
    const localSummary = localizeToolSummary(summary, label);
    const labelParts = localLabel.split('\n', 2);
    const labelHtml = escapeHtml(labelParts[0]) + ' &mdash; ' + escapeHtml(localSummary)
      + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
    let mailBody = '';
    try {
      const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
      const msg = inp.message || '';
      mailBody = msg ? '<div class="tool-mail-msg">' + renderMarkdownWithMath(msg) + '</div>' : '';
    } catch {}
    card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
      '<div class="content"><div class="label">' + labelHtml + '</div>' +
      (mailBody ? '<div class="body">' + mailBody + '</div>' : '') + '</div>';
    smartScroll();
    if (mailBody) attachToolClick(card);
    return { type: 'tool', label, summary, content: null, isError, input: inputJson };
  }

  if (_toolName === 'RemoveUnnecessary' && inputJson) {
    const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                         : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
    const localLabel = localizeToolLabel(label);
    const labelParts = localLabel.split('\n', 2);
    const labelHtml = escapeHtml(labelParts[0])
      + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');

    // Extract the user's summary from input
    let summaryBody = '';
    try {
      const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
      const userSummary = inp.summary || '';
      if (userSummary) {
        summaryBody = '<div class="tool-removeunnecessary-summary">' + renderMarkdownWithMath(userSummary) + '</div>';
      }
    } catch {}

    // Result text (e.g. "Replaced 3 tool result(s)") — strip the "Summary: ..." suffix
    const resultText = content ? content.replace(/Summary:.*$/s, '').trim() : '';

    card.innerHTML = '<span class="icon ' + (isError ? 'err' : 'ok') + '">' + icon + '</span>' +
      '<div class="content"><div class="label">' + labelHtml + '</div>' +
      (resultText ? '<div class="tool-result-badge">' + escapeHtml(resultText) + '</div>' : '') +
      (summaryBody ? '<div class="body">' + summaryBody + '</div>' : '') + '</div>';
    smartScroll();
    if (summaryBody) attachToolClick(card);
    return { type: 'tool', label, summary, content: null, isError, input: inputJson };
  }

  // Default rendering for all other tools
  const icon = isError ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f44336" stroke-width="3"><path d="M18 6L6 18M6 6l12 12"/></svg>'
                       : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#4caf50" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
  const detailHtml = buildToolDetail(inputJson, label);
  const delegatePromptHtml = buildDelegatePromptHtml(inputJson);
  // Render full content in body with syntax highlighting (Read/Grep only).
  // Body is hidden by default, click to expand shows full content with scroll for long output.
  const highlightHtml = content ? renderHighlightedContent(content, label) : null;
  const bodyHtml = (detailHtml + delegatePromptHtml + (highlightHtml || (content ? '<pre class="tool-body-pre">' + escapeHtml(content) + '</pre>' : ''))) || '';
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

  // Helper: update the label text on a pending card row.
  function updateLabel(rowEl) {
    const labelEl = rowEl.querySelector('.label');
    if (labelEl) {
      const localLabel = localizeToolLabel(label);
      const labelParts = localLabel.split('\n', 2);
      labelEl.innerHTML = escapeHtml(labelParts[0])
        + (labelParts.length > 1 ? '<br><span class="tool-detail">' + escapeHtml(labelParts[1]) + '</span>' : '');
    }
  }

  // If a pending card already exists for this session, update it in-place
  // to avoid spinner flicker between toolCallDetected → toolStart events.
  // Defense: if the DOM node was removed (e.g. historyPage cleared innerHTML
  // without clearing sessionToolCards), treat it as non-existent so a fresh
  // card is created.
  const existing = state.sessionToolCards[sid];
  if (existing && existing.isConnected) {
    const cardEl = existing.querySelector('.tool-card');
    const existingLabel = cardEl?.dataset.toolLabel;
    // Reuse if no toolStart has claimed this card yet (toolCallDetected → toolStart
    // for the same tool), or if the label matches (toolStart from execution phase
    // for the same tool).
    if (!existingLabel || existingLabel === label) {
      updateLabel(existing);
      return;
    }
    // Label mismatch — the slot holds a different tool's card.
    // Search for a pending card with matching label in the DOM.
    const cards = chat.querySelectorAll('.row.tool .tool-card--pending');
    for (const c of cards) {
      if (c.dataset.toolLabel === label) {
        const matchedRow = c.closest('.row');
        state.sessionToolCards[sid] = matchedRow;
        updateLabel(matchedRow);
        return;
      }
    }
    // No match found — fall through to create a new card.
  } else {
    // No card in slot — try to find a pending card by label (orphaned card
    // from a previous tool whose slot was overwritten).
    const cards = chat.querySelectorAll('.row.tool .tool-card--pending');
    for (const c of cards) {
      if (c.dataset.toolLabel === label || !c.dataset.toolLabel) {
        const matchedRow = c.closest('.row');
        state.sessionToolCards[sid] = matchedRow;
        updateLabel(matchedRow);
        return;
      }
    }
  }

  const row = document.createElement('div');
  row.className = 'row tool';
  if (label && label.startsWith('[NebLink]')) row.classList.add('neblink-row');
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

// ---------- Tool argument streaming ----------
// While the LLM generates tool call arguments (e.g. Write content, Bash command),
// toolArgDelta events stream partial JSON fragments. We accumulate them and try
// to extract the primary content field for display, giving the user real-time
// feedback instead of just a spinner.

const TOOL_PRIMARY_FIELDS = {
  'Write': 'content',
  'Edit': 'new_string',
  'Bash': 'command',
  'Card': 'html',
  'Delegate': 'prompt',
  'Read': 'file_path',
  'Grep': 'pattern',
  'Glob': 'pattern',
  'Curl': 'body',
  'WebSearch': 'query',
  'WebFetch': 'url',
  'TaskCreate': 'description',
  'TaskUpdate': 'description',
  'RemoveUnnecessary': 'summary',
  'Mail': 'message',
  'TransferFile': 'sourcePath',
  'MailAgent': 'message',
};

/**
 * Best-effort extraction of a JSON string field value from partial JSON.
 * Returns { value, complete } or null if the field hasn't been started yet.
 * Handles JSON string escapes (\n, \t, \", \\, \uXXXX).
 */
function extractFieldValueFromPartialJson(partialJson, fieldName) {
  const marker = '"' + fieldName + '"';
  const markerIdx = partialJson.indexOf(marker);
  if (markerIdx === -1) return null;

  let idx = markerIdx + marker.length;
  // Skip whitespace and colon
  while (idx < partialJson.length && /[\s:]/.test(partialJson[idx])) idx++;
  if (idx >= partialJson.length || partialJson[idx] !== '"') return null;
  idx++; // skip opening quote

  let result = '';
  while (idx < partialJson.length) {
    const ch = partialJson[idx];
    if (ch === '\\' && idx + 1 < partialJson.length) {
      const next = partialJson[idx + 1];
      switch (next) {
        case 'n': result += '\n'; break;
        case 't': result += '\t'; break;
        case 'r': result += '\r'; break;
        case '"': result += '"'; break;
        case '\\': result += '\\'; break;
        case '/': result += '/'; break;
        case 'b': result += '\b'; break;
        case 'f': result += '\f'; break;
        case 'u':
          if (idx + 5 < partialJson.length) {
            const code = parseInt(partialJson.substr(idx + 2, 4), 16);
            if (!isNaN(code)) result += String.fromCodePoint(code);
            idx += 4;
          }
          break;
        default: result += next;
      }
      idx += 2;
    } else if (ch === '"') {
      // Closing quote — field is complete
      return { value: result, complete: true };
    } else {
      result += ch;
      idx++;
    }
  }
  // Stream still open — return what we have so far
  return { value: result, complete: false };
}

// rAF-throttled rendering (same pattern as appendThinkingDelta)
let _pendingToolStreamRAF = null;
let _toolStreamRafTarget = null;

export function appendToolStreamDelta(toolName, delta) {
  activeView.stream.toolStreamText += delta;
  activeView.stream.toolStreamToolName = toolName;

  const sid = activeView.sessionId;
  const pendingRow = state.sessionToolCards[sid];
  if (!pendingRow || !pendingRow.isConnected) return;

  _toolStreamRafTarget = {
    row: pendingRow,
    chat: activeView.dom.chat,
    snapped: activeView.stream.scrollSnapped,
    toolName: toolName,
    rawText: activeView.stream.toolStreamText,
  };

  if (!_pendingToolStreamRAF) {
    _pendingToolStreamRAF = requestAnimationFrame(() => {
      _pendingToolStreamRAF = null;
      const target = _toolStreamRafTarget;
      _toolStreamRafTarget = null;
      if (!target || !target.row || !target.row.isConnected) return;

      // Extract displayable content from partial JSON
      const fieldName = TOOL_PRIMARY_FIELDS[target.toolName];
      let displayContent = null;
      if (fieldName) {
        const extracted = extractFieldValueFromPartialJson(target.rawText, fieldName);
        if (extracted) displayContent = extracted.value;
      }
      if (displayContent === null) return; // primary field not started yet

      // Find or create streaming body in the tool card
      let bodyEl = target.row.querySelector('.tool-stream-body');
      if (!bodyEl) {
        bodyEl = document.createElement('div');
        bodyEl.className = 'tool-stream-body';
        const card = target.row.querySelector('.tool-card');
        if (card) {
          const contentDiv = card.querySelector('.content');
          if (contentDiv) contentDiv.appendChild(bodyEl);
          else card.appendChild(bodyEl);
        }
      }
      // Try syntax highlighting for code content (Write/Edit/Bash tools generate code).
      // Falls back to plain text if hljs unavailable or content too large.
      // Extract file_path from partial JSON for language detection (e.g. Write/Main.scala → Scala).
      const fpResult = extractFieldValueFromPartialJson(target.rawText, 'file_path');
      const highlightLabel = fpResult ? fpResult.value : target.toolName;
      const highlighted = (displayContent.length < 20000) ? highlightCode(displayContent, highlightLabel) : null;

      // Capture scroll state BEFORE content update — if content grows significantly,
      // the post-update threshold check would fail and miss the auto-scroll.
      const wasNearBottom = bodyEl.scrollHeight - bodyEl.scrollTop - bodyEl.clientHeight < 80;

      if (highlighted) {
        bodyEl.innerHTML = highlighted.replace(/<\/code><\/pre>$/, '<span class="cursor"></span></code></pre>');
      } else {
        bodyEl.innerHTML = '<pre class="tool-body-pre">' + escapeHtml(displayContent) + '<span class="cursor"></span></pre>';
      }

      // Auto-scroll tool body to keep latest content visible
      if (wasNearBottom) {
        bodyEl.scrollTop = bodyEl.scrollHeight;
      }

      // Auto-scroll chat — snapped captured at schedule time to match smartScroll()'s logic
      const threshold = 60;
      if (target.snapped || target.chat.scrollHeight - target.chat.scrollTop - target.chat.clientHeight < threshold) {
        target.chat.scrollTop = target.chat.scrollHeight;
      }
    });
  }
}

/** Cancel pending rAF and clear target — called when tool finalizes or on cleanup. */
export function cancelToolStreamRAF() {
  if (_pendingToolStreamRAF) {
    cancelAnimationFrame(_pendingToolStreamRAF);
    _pendingToolStreamRAF = null;
  }
  _toolStreamRafTarget = null;
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
  const saved = askSessionId ? loadAskDrafts(askSessionId) : {};
  const questionWrappers = [];

  // --- Conditional branching helpers ---
  function shouldShow(qi) {
    const item = questions[qi];
    if (!item.dependsOn) return true;
    const dep = item.dependsOn;
    const refIdx = questions.findIndex(q => q.id === dep.ref);
    if (refIdx === -1) return true;
    return answers[refIdx] === dep.equals;
  }

  function updateVisibility() {
    questionWrappers.forEach((wrapper, qi) => {
      const visible = shouldShow(qi);
      wrapper.style.display = visible ? '' : 'none';
      if (!visible && answers[qi] !== null) {
        answers[qi] = null;
        wrapper.querySelectorAll('.option-btn').forEach(el => el.classList.remove('picked'));
        const input = wrapper.querySelector('.option-custom-input');
        if (input) input.value = '';
      }
    });
  }

  // --- Build question DOM ---
  questions.forEach((item, qi) => {
    const wrapper = document.createElement('div');
    wrapper.className = 'option-q-wrapper';
    questionWrappers.push(wrapper);

    const q = document.createElement('div');
    q.className = 'option-q';
    q.innerHTML = item.question;
    wrapper.appendChild(q);

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
          if (customInput) {
            customInput.style.display = 'none';
            customInput.value = '';
          }
          if (askSessionId) saveAskDraft(askSessionId, qi, '');
          updateVisibility();
          checkAllAnswered();
        };
        optsDiv.appendChild(btn);
      });
    }

    const allowOther = item.allowOther !== false;
    const customInput = document.createElement('textarea');
    customInput.className = 'option-custom-input';
    customInput.placeholder = t('chat.typeAnswer');
    customInput.rows = 2;

    const savedVal = saved[qi];
    if (savedVal) {
      customInput.value = savedVal;
      answers[qi] = savedVal;
    }

    if (!hasOptions) customInput.style.display = '';

    let otherBtn = null;
    if (hasOptions && allowOther) {
      otherBtn = document.createElement('button');
      otherBtn.className = 'option-btn';
      otherBtn.textContent = t('chat.other');
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
        updateVisibility();
        checkAllAnswered();
      };
      optsDiv.appendChild(otherBtn);
    } else if (hasOptions) {
      customInput.style.display = 'none';
    } else {
      answers[qi] = null;
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
      updateVisibility();
      checkAllAnswered();
    };
    optsDiv.appendChild(customInput);
    wrapper.appendChild(optsDiv);
    box.appendChild(wrapper);
  });

  // Apply initial visibility after all questions are in DOM
  updateVisibility();

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
  confirmBtn.disabled = !questions.every((_, qi) => !shouldShow(qi) || answers[qi] !== null);
  confirmBtn.onclick = () => {
    box.querySelectorAll('.option-btn').forEach(el => { el.disabled = true; });
    cancelBtn.disabled = true;
    confirmBtn.disabled = true;
    confirmBtn.style.display = 'none';
    cancelBtn.style.display = 'none';

    const ansDiv = document.createElement('div');
    ansDiv.className = 'option-answer';
    ansDiv.textContent = '-> ' + answers.filter(a => a).join(', ');
    box.appendChild(ansDiv);

    if (askSessionId) clearAskDrafts(askSessionId);
    const finalAnswers = answers.map(a => a !== null ? a : '');
    if (onConfirm) onConfirm(finalAnswers);
  };
  btnRow.appendChild(cancelBtn);
  btnRow.appendChild(confirmBtn);
  box.appendChild(btnRow);
  container.appendChild(box);
  if (typeof lucide !== 'undefined') lucide.createIcons();
  smartScroll();

  function checkAllAnswered() {
    confirmBtn.disabled = !questions.every((_, qi) => !shouldShow(qi) || answers[qi] !== null);
  }
}

// ---------- AskUser ----------
export function renderAskUser(items, askSessionId, agentName) {
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
  // Show source agent badge if not Nebula
  if (agentName && agentName !== 'Nebula') {
    const badge = document.createElement('div');
    badge.className = 'ask-user-source';
    badge.textContent = agentName;
    badge.style.cssText = 'font: 600 11px -apple-system, sans-serif; color: var(--color-text-muted, #888); margin-bottom: 8px; padding: 2px 8px; background: var(--color-surface, rgba(255,255,255,0.06)); border-radius: 6px; display: inline-block;';
    bubble.appendChild(badge);
  }
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
export function renderPermissionPrompt(toolName, summary, inputJson, permSessionId, dangerLevel, sourceAgent, sourceSession) {
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

  // Source badge: show which sub-agent this permission request came from
  if (sourceAgent) {
    const sourceBadge = document.createElement('div');
    sourceBadge.className = 'perm-source-badge';
    const shortSession = sourceSession ? sourceSession.substring(0, 12) : '';
    sourceBadge.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 17l9.2-9.2M17 17V7H7"/></svg>' +
      '<span>' + escapeHtml(t('chat.permSource', { agent: sourceAgent, session: shortSession })) + '</span>';
    bubble.appendChild(sourceBadge);
  }

  row.appendChild(bubble);
  chat.appendChild(row);

  const targetSid = permSessionId || activeView.sessionId;

  // If bypass is enabled for this session, auto-approve immediately
  if (state.bypassSessions.has(targetSid)) {
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
      wrap.style.position = 'relative';
      wrap.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:100px">' + escapeHtml(att.name) + '</span>';
      const rm = document.createElement('div');
      rm.className = 'att-remove';
      rm.textContent = 'x';
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
  const view = activeView;
  const chat = view.dom.chat;
  view.stream.askAnswerText += delta;
  if (!view.stream.currentAskBubble) {
    const row = document.createElement('div');
    row.className = 'row ai';
    view.stream.currentAskBubble = document.createElement('div');
    view.stream.currentAskBubble.className = 'bubble ai';
    const label = document.createElement('div');
    label.className = 'ask-label';
    label.textContent = t('chat.askLabel');
    const content = document.createElement('div');
    view.stream.currentAskBubble.appendChild(label);
    view.stream.currentAskBubble.appendChild(content);
    row.appendChild(view.stream.currentAskBubble);
    chat.appendChild(row);
  }
  // rAF-throttled render (same scheduler as appendAiText)
  const bubble = view.stream.currentAskBubble;
  bubble._nfText = view.stream.askAnswerText || '';
  scheduleStreamRender(view, 'ask',
    { bubble, chat, snapped: view.stream.scrollSnapped },
    (target) => {
      if (!target.bubble.isConnected) return;
      const contentEl = target.bubble.querySelector('div:not(.ask-label)');
      if (contentEl) {
        contentEl.innerHTML = renderMarkdownWithMath(target.bubble._nfText || '') + '<span class="cursor"></span>';
      }
      rafScrollChat(target);
    });
}

export function finishAskAnswer(durationMs, model) {
  cancelStreamRender(activeView, 'ask');
  if (activeView.stream.currentAskBubble) {
    const contentEl = activeView.stream.currentAskBubble.querySelector('div:not(.ask-label)');
    if (contentEl) {
      contentEl.innerHTML = renderMarkdownWithMath(activeView.stream.askAnswerText || '');
    }
    if (durationMs != null && durationMs > 0) {
      const seed = activeView.dom.chat.querySelectorAll('.duration-badge').length;
      renderDurationBadge(activeView.stream.currentAskBubble, durationMs, model, seed, Date.now());
    }
    activeView.stream.currentAskBubble = null;
    activeView.stream.askAnswerText = '';
  }
}

export function renderAskError(msg) {
  cancelStreamRender(activeView, 'ask');
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
  _thinkingRafTarget = { bubble: activeView.stream.currentThinkingBubble, chat: activeView.dom.chat, snapped: activeView.stream.scrollSnapped };
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
      // at rAF time which may be the wrong window. Capture snapped at schedule
      // time to match smartScroll()'s snapped || near-bottom logic.
      const threshold = 60;
      if (target.snapped || target.chat.scrollHeight - target.chat.scrollTop - target.chat.clientHeight < threshold) {
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
      contentEl.innerHTML = renderMarkdownWithMath(activeView.stream.thinkingText || '', false);
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

