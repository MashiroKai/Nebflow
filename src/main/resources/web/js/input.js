// input.js — Input handling module for Nebflow
// All send logic, keyboard/input events, slash commands, attachments, drag/drop, voice.

import state, { LS_HISTORY_KEY } from './state.js';
import { activeView, setActiveView, chatViews, findViewBySessionId } from './chatView.js';
import { sendWs } from './ws.js';
import { renderUserBubble, renderSystemBubble, setBusy, renderAttachmentPreview, renderAskBubble, renderSkillBubble } from './chat.js';
import { renderMarkdownWithMath, escapeHtml, smartScroll } from './utils.js';
import { saveMsg } from './persistence.js';
import { saveInputDraft } from './sidebar.js';
import { renderTaskList } from './taskList.js';
import { t } from './i18n.js';
import { getLocale } from './i18n.js';

// ---------- Slash Commands ----------
const slashCommands = {
  '/clear': {
    desc: () => t('slash.clear'),
    run: () => {
      sendWs({type:'command', command:'clear', sessionId: activeView.sessionId});
      delete state.sessionTasks[activeView.sessionId];
      renderTaskList([]);
      renderSystemBubble(t('slash.clearDone'));
    }
  },
  '/compact': {
    desc: () => t('slash.compact'),
    run: () => {
      sendWs({type:'command', command:'compact', sessionId: activeView.sessionId});
      renderSystemBubble(t('slash.compactDone'));
    }
  },
  '/fork': {
    desc: () => t('slash.fork'),
    run: () => {
      sendWs({type:'command', command:'fork', sessionId: activeView.sessionId});
      renderSystemBubble(t('slash.forkPending'));
    }
  },
  '/ask': {
    desc: () => t('slash.ask'),
    run: () => {
      enterAskMode();
    }
  },
  '/model': {
    desc: () => t('slash.model'),
    run: () => {
      sendWs({type: 'getModelOptions', sessionId: activeView.sessionId});
    }
  },
  '/bypass': {
    desc: () => t('slash.bypass'),
    run: () => {
      // Show a selection card to toggle bypass-all mode
      if (!activeView.stream.currentAiBubble) {
        const row = document.createElement('div');
        row.className = 'row ai';
        activeView.stream.currentAiBubble = document.createElement('div');
        activeView.stream.currentAiBubble.className = 'bubble ai';
        row.appendChild(activeView.stream.currentAiBubble);
        activeView.dom.chat.appendChild(row);
      }
      const isEnabled = state.bypassAllPermission;
      import('./chat.js').then(({ showOptions }) => {
        showOptions(activeView.stream.currentAiBubble, [
          {
            question: isEnabled ? t('slash.bypassDisableQ') : t('slash.bypassEnableQ'),
            options: [
              { label: isEnabled ? t('slash.bypassDisable') : t('slash.bypassEnable'), desc: t('slash.bypassDesc') },
              { label: t('chat.cancel'), desc: '' }
            ],
            allowOther: false
          }
        ], (answers) => {
          const confirmed = answers[0] === (isEnabled ? t('slash.bypassDisable') : t('slash.bypassEnable'));
          if (confirmed) {
            state.bypassAllPermission = !isEnabled;
            updateBypassBadge(state.bypassAllPermission);
            renderSystemBubble(
              state.bypassAllPermission ? t('slash.bypassEnabled') : t('slash.bypassDisabled')
            );
          }
        }, t('chat.apply'));
      });
    }
  }
};

/**
 * Show or hide the bypass badge in the header.
 * Called when bypass mode is toggled.
 */
function updateBypassBadge(enabled) {
  // Bypass is a global toggle — keep both the primary and secondary header badges in sync.
  for (const badge of [
    document.getElementById('bypass-badge'),
    document.getElementById('secondary-bypass-badge'),
  ]) {
    if (!badge) continue;
    const textEl = badge.querySelector('.bypass-badge-text');
    if (enabled) {
      badge.classList.remove('hidden');
      if (textEl) textEl.textContent = t('chat.bypassBadge');
    } else {
      badge.classList.add('hidden');
    }
  }
}

/** Register skill commands from the server-provided skill list. */
export function registerSkillCommands(skills) {
  // Remove previously registered skill commands
  Object.keys(slashCommands).forEach(key => {
    if (key.startsWith('/') && slashCommands[key]._skill) {
      delete slashCommands[key];
    }
  });
  skills.forEach(skill => {
    const cmd = '/' + skill.name;
    // Don't override built-in commands
    if (!slashCommands[cmd] || slashCommands[cmd]._skill) {
      slashCommands[cmd] = {
        _skill: true,
        desc: () => skill.description || t('slash.skillDefault'),
        whenToUse: skill.whenToUse || '',
        argumentHint: skill.argumentHint || '',
        run: () => enterSkillMode(skill.name, skill.description, skill.argumentHint)
      };
    }
  });
}

// ---------- Slash Command Handler ----------
export function handleSlash(text) {
  const cmd = text.trim().split(/\s/)[0];
  if (slashCommands[cmd] && slashCommands[cmd].run) {
    slashCommands[cmd].run();
    return true;
  }
  return false;
}

// ---------- Slash Autocomplete ----------
function updateSlashDropdown() {
  const input = activeView.dom.input;
  const text = input.value;
  if (!text.startsWith('/')) {
    closeSlashDropdown();
    return;
  }
  const query = text.slice(1).toLowerCase();
  activeView.slashMatches = Object.entries(slashCommands)
    .filter(([cmd]) => cmd.slice(1).toLowerCase().startsWith(query))
    .map(([cmd, info]) => ({ cmd, desc: typeof info.desc === 'function' ? info.desc() : info.desc, whenToUse: info.whenToUse || '', isSkill: !!info._skill }));
  if (activeView.slashMatches.length === 0) {
    closeSlashDropdown();
    return;
  }
  const slashDropdown = activeView.dom.slashDropdown;
  slashDropdown.innerHTML = '';
  activeView.slashMatches.forEach((item, i) => {
    const div = document.createElement('div');
    div.className = 'slash-item' + (i === 0 ? ' active' : '');
    const badge = item.isSkill ? '<span class="slash-badge skill">' + escapeHtml(t('slash.skillBadge')) + '</span>' : '';
    const whenToUseHtml = item.whenToUse ? '<span class="slash-when">' + escapeHtml(item.whenToUse) + '</span>' : '';
      div.innerHTML = '<div style="display:flex;align-items:center"><span class="slash-cmd">' + escapeHtml(item.cmd) + '</span>' + badge + '</div><span class="slash-desc">' + escapeHtml(item.desc) + '</span>' + whenToUseHtml;
    div.onclick = () => { pickSlashCommand(i); };
    div.onmouseenter = () => { setSlashHighlight(i); };
    slashDropdown.appendChild(div);
  });
  activeView.slashSelectedIndex = 0;
  slashDropdown.classList.add('on');
}

function closeSlashDropdown() {
  activeView.dom.slashDropdown.classList.remove('on');
  activeView.slashSelectedIndex = -1;
  activeView.slashMatches = [];
}

function setSlashHighlight(index) {
  activeView.slashSelectedIndex = index;
  const items = activeView.dom.slashDropdown.querySelectorAll('.slash-item');
  items.forEach((el, i) => { el.classList.toggle('active', i === index); });

  const active = items[index];
  if (!active) return;

  const dd = activeView.dom.slashDropdown;
  // Calculate element's offset relative to the dropdown content area
  let relTop = 0;
  let el = active;
  while (el && el !== dd) {
    relTop += el.offsetTop;
    el = el.offsetParent;
  }
  const relBottom = relTop + active.offsetHeight;
  const scrollTop = dd.scrollTop;
  const visibleBottom = scrollTop + dd.clientHeight;

  if (relTop < scrollTop) {
    dd.scrollTop = relTop;
  } else if (relBottom > visibleBottom) {
    dd.scrollTop = relBottom - dd.clientHeight;
  }
}

function pickSlashCommand(index) {
  if (index < 0 || index >= activeView.slashMatches.length) return;
  const cmd = activeView.slashMatches[index].cmd;
  activeView.dom.input.value = '';
  closeSlashDropdown();
  activeView.dom.input.focus();
  if (slashCommands[cmd] && slashCommands[cmd].run) slashCommands[cmd].run();
}

// ---------- Ask Mode ----------
export function enterAskMode() {
  if (activeView.stream.askMode) return;
  activeView.stream.askMode = true;
  updateAskIndicator();
  activeView.dom.input.placeholder = t('input.askPlaceholder');
  activeView.dom.input.focus();
}

export function cancelAskMode() {
  if (!activeView.stream.askMode) return;
  activeView.stream.askMode = false;
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.placeholder');
}

function updateAskIndicator() {
  updateInputIndicator();
}

function updateInputIndicator() {
  const prefix = activeView.id === 'secondary' ? 'secondary-' : '';
  const askEl = document.getElementById(prefix + 'ask-indicator');
  const skillEl = document.getElementById(prefix + 'skill-indicator');
  const skillLabel = document.getElementById(prefix + 'skill-indicator-label');
  const input = activeView.dom.input;
  // Ask mode takes priority over skill mode
  if (activeView.stream.askMode) {
    if (askEl) askEl.classList.add('show');
    if (skillEl) skillEl.classList.remove('show');
    input.style.paddingLeft = '';
    if (askEl) {
      askEl.classList.add('show');
      // Measure ask indicator width after layout
      const w = askEl.offsetWidth + 12;
      input.style.paddingLeft = Math.max(w, 48) + 'px';
    }
  } else if (activeView.skillMode) {
    if (askEl) askEl.classList.remove('show');
    if (skillEl) {
      if (skillLabel) skillLabel.textContent = activeView.skillModeName || 'SKILL';
      skillEl.classList.add('show');
      // Measure skill indicator width after layout
      const w = skillEl.offsetWidth + 12;
      input.style.paddingLeft = Math.max(w, 56) + 'px';
    } else {
      input.style.paddingLeft = '';
    }
  } else {
    if (askEl) askEl.classList.remove('show');
    if (skillEl) skillEl.classList.remove('show');
    input.style.paddingLeft = '';
  }
}

// ---------- Skill Mode ----------
export function enterSkillMode(skillName, description, argumentHint) {
  if (activeView.skillMode) {
    // Already in skill mode — if it's a different skill, switch; otherwise do nothing
    if (activeView.skillModeName === skillName) return;
    cancelSkillMode();
  }
  // Cancel ask mode if active
  if (activeView.stream.askMode) cancelAskMode();
  activeView.skillMode = true;
  activeView.skillModeName = skillName;
  activeView.skillModeDesc = description || '';
  activeView.skillModeArgHint = argumentHint || '';
  updateInputIndicator();
  activeView.dom.input.placeholder = argumentHint || t('input.skillPlaceholder');
  activeView.dom.input.focus();
}

export function cancelSkillMode() {
  if (!activeView.skillMode) return;
  activeView.skillMode = false;
  activeView.skillModeName = '';
  activeView.skillModeDesc = '';
  activeView.skillModeArgHint = '';
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.placeholder');
}

// Restore skill/ask mode from per-session draft data.
// Unlike enterSkillMode/enterAskMode, this does NOT focus the input.
export function applyInputModes(skillData, askActive) {
  activeView.skillMode = false;
  activeView.skillModeName = '';
  activeView.skillModeDesc = '';
  activeView.skillModeArgHint = '';
  activeView.stream.askMode = false;

  if (skillData) {
    activeView.skillMode = true;
    activeView.skillModeName = skillData.name || '';
    activeView.skillModeDesc = skillData.desc || '';
    activeView.skillModeArgHint = skillData.argHint || '';
  } else if (askActive) {
    activeView.stream.askMode = true;
  }

  updateInputIndicator();

  if (activeView.skillMode) {
    activeView.dom.input.placeholder = activeView.skillModeArgHint || t('input.skillPlaceholder');
  } else if (activeView.stream.askMode) {
    activeView.dom.input.placeholder = t('input.askPlaceholder');
  } else {
    activeView.dom.input.placeholder = t('input.placeholder');
  }
}

// ---------- Image Compression ----------
function compressImage(file, opts = {}) {
  const maxDim = opts.maxDim || 1920;
  const quality = opts.quality || 0.8;
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(img.src);
      let w = img.width, h = img.height;
      if (w > maxDim || h > maxDim) {
        const scale = maxDim / Math.max(w, h);
        w = Math.round(w * scale);
        h = Math.round(h * scale);
      }
      const canvas = document.createElement('canvas');
      canvas.width = w; canvas.height = h;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, w, h);
      const dataUrl = canvas.toDataURL('image/jpeg', quality);
      resolve({ dataUrl, w, h });
    };
    img.onerror = () => { URL.revokeObjectURL(img.src); reject(new Error('Image load failed')); };
    img.src = URL.createObjectURL(file);
  });
}

// ---------- File Attachment ----------
export async function addFileAttachment(file, callback, target) {
  // target: optional { attPreviewEl, attachments } for non-primary windows.
  // Defaults to primary window's pendingAttachments.
  const attachments = (target && target.attachments) || activeView.pendingAttachments;
  if (file.type.startsWith('image/')) {
    if (file.size > 10 * 1024 * 1024) {
      alert('Image too large (max 10MB): ' + file.name);
      return;
    }
    try {
      const { dataUrl, w, h } = await compressImage(file);
      attachments.push({
        type: 'image', mimeType: 'image/jpeg',
        data: dataUrl.split(',')[1],
        name: file.name, preview: dataUrl
      });
    } catch (e) {
      console.warn('[input] image compression failed, using original:', e);
      // Fallback to original
      const reader = new FileReader();
      reader.onload = () => {
        attachments.push({
          type: 'image', mimeType: 'image/jpeg',
          data: reader.result.split(',')[1],
          name: file.name, preview: reader.result
        });
        renderAttachmentPreview(target);
        if (callback) callback();
      };
      reader.readAsDataURL(file);
      return;
    }
    renderAttachmentPreview(target);
    if (callback) callback();
  } else {
    // Non-image: compute SHA-256 hash for local file search, also keep data as fallback
    const MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    if (file.size > MAX_FILE_SIZE) {
      alert('File too large (max 50MB): ' + file.name);
      return;
    }
    const reader = new FileReader();
    reader.onload = async () => {
      const resultStr = reader.result;
      const commaIdx = resultStr.indexOf(',');
      const base64Data = commaIdx >= 0 ? resultStr.substring(commaIdx + 1) : resultStr;
      // Compute SHA-256 hash from the file ArrayBuffer
      let hash = '';
      try {
        const buffer = await file.arrayBuffer();
        const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        hash = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
      } catch (e) {
        console.warn('[input] SHA-256 computation failed:', e);
      }
      attachments.push({
        type: 'text', mimeType: file.type || 'application/octet-stream',
        data: base64Data, name: file.name, hash, size: file.size
      });
      renderAttachmentPreview(target);
      if (callback) callback();
    };
    reader.onerror = () => {
      console.warn('[input] file read failed:', reader.error);
    };
    reader.readAsDataURL(file);
  }
}

// ---------- Send ----------
export function send() {
  if (activeView.isSending) {
    console.warn('[send] blocked: already sending');
    return;
  }
  const input = activeView.dom.input;
  const text = input.value.trim();
  const isBusy = state.busySessionIds.has(activeView.sessionId);
  // If in skill mode, send as skill activation
  if (activeView.skillMode) {
    const skillName = activeView.skillModeName;
    cancelSkillMode();
    if (!text || isBusy || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
      return;
    }
    activeView.isSending = true;
    if (activeView.sessionId) state.turnExpecting[activeView.sessionId] = true;
    sendWs({ type: 'skill', skillName, input: text, sessionId: activeView.sessionId });
    renderSkillBubble(skillName, text);
    saveMsg({type:'user', text, attachments: (activeView.pendingAttachments||[]).map(a=>({type:a.type,name:a.name,preview:a.preview}))});
    input.value = '';
    saveInputDraft(activeView.sessionId);
    setTimeout(() => { activeView.isSending = false; }, 300);
    return;
  }
  // If in ask mode, send as ask question
  if (activeView.stream.askMode) {
    cancelAskMode();
    if (!text || isBusy || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
      activeView.isSending = false;
      return;
    }
    activeView.isSending = true;
    if (activeView.sessionId) state.turnExpecting[activeView.sessionId] = true;
    sendWs({ type: 'ask', question: text, sessionId: activeView.sessionId });
    state.sessionAskBuffers[activeView.sessionId] = { question: text, answer: '' };
    renderAskBubble(text);
    input.value = '';
    saveInputDraft(activeView.sessionId);
    setTimeout(() => { activeView.isSending = false; }, 300);
    return;
  }
  // Allow slash commands (except /ask <question> which sends to the agent)
  // even when the session is busy — they are UI/meta operations.
  if (text.startsWith('/') && !text.startsWith('/ask ')) {
    if (handleSlash(text)) {
      input.value = '';
      saveInputDraft(activeView.sessionId);
      setTimeout(() => { activeView.isSending = false; }, 300);
      return;
    }
  }
  if ((!text && activeView.pendingAttachments.length === 0) || isBusy) {
    console.warn('[send] blocked:', { text: text.slice(0,20), busy: state.busySessionIds.has(activeView.sessionId), wsState: state.ws?.readyState });
    return;
  }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    console.warn('[send] ws not open:', { ws: !!state.ws, readyState: state.ws?.readyState });
    return;
  }
  activeView.isSending = true;
  // Mark this session as expecting a turn (prevents stray thinking bubbles after done)
  if (activeView.sessionId) state.turnExpecting[activeView.sessionId] = true;
  // Intercept /ask <question> before normal slash handling
  if (text.startsWith('/ask ')) {
    const question = text.slice(5).trim();
    if (question) {
      sendWs({ type: 'ask', question, sessionId: activeView.sessionId });
      state.sessionAskBuffers[activeView.sessionId] = { question, answer: '' };
      renderAskBubble(question);
    }
    input.value = '';
    saveInputDraft(activeView.sessionId);
    setTimeout(() => { activeView.isSending = false; }, 300);
    return;
  }
  if (handleSlash(text)) {
    input.value = '';
    saveInputDraft(activeView.sessionId);
    // Debounce: keep lock briefly to prevent accidental double-trigger of slash commands
    setTimeout(() => { activeView.isSending = false; }, 300);
    return;
  }
  renderUserBubble(text, activeView.pendingAttachments);
  saveMsg({type:'user', text, attachments: (activeView.pendingAttachments||[]).map(a=>({type:a.type,name:a.name,preview:a.preview}))});
  // Save to input history
  if (text && text !== '/clear') {
    state.inputHistory.push(text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e) {
      console.warn('[input] history save failed:', e);
    }
  }
  activeView.historyIndex = -1;
  activeView.historyDraft = '';
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: text,
      attachments: activeView.pendingAttachments.map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
      })),
      clientMessageId,
      sessionId: activeView.sessionId,
      chatWidth: activeView.dom.chat?.clientWidth || 0
    });
  } catch (e) {
    console.error('WebSocket send failed:', e);
  }
  input.value = '';
  input.style.height = 'auto';
  activeView.pendingAttachments = [];
  activeView.dom.attPreview.innerHTML = '';
  // Immediately clear the draft for this session so it is not restored after refresh
  saveInputDraft(activeView.sessionId);
  setBusy(activeView.sessionId);
  // Start turn timer
  state.turnStartTimes[activeView.sessionId] = Date.now();
  // Release send lock after a short debounce to prevent double-click / rapid Enter
  setTimeout(() => { activeView.isSending = false; }, 300);
  // Clean up any orphaned thinking placeholders from previous incomplete streams
  if (window.__stopThinkingTimer) window.__stopThinkingTimer();
  activeView.dom.chat.querySelectorAll('.thinking-placeholder').forEach(el => {
    const row = el.closest('.row');
    if (row) row.remove();
  });
  activeView.stream.currentAiBubble = null;
  activeView.stream.aiText = '';
  activeView.stream.currentThinkingBubble = null;
  activeView.stream.thinkingText = '';
  // Safety timeout: backend sends 'timeout' event, but this is a last-resort fallback
  // in case the backend event never arrives. Uses streamTimeoutMs from server config (+ 30s buffer).
  const sid = activeView.sessionId;
  if (sid && state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  state.sessionBusyTimeouts[sid] = setTimeout(() => {
    if (state.busySessionIds.has(sid)) {
      // Send interrupt to backend so the agent cancels its fiber and returns to idle.
      // Without this, the frontend clears busy but the backend keeps processing — any
      // new user message gets stashed and the user sees no response until they manually
      // click Stop (which does send interrupt).
      sendWs({type: 'interrupt', sessionId: sid});
      import('./chat.js').then(({ renderTimeoutNotice, clearBusy, clearStatus }) => {
        const v = findViewBySessionId(sid);
        if (v) { setActiveView(v); renderTimeoutNotice(); }
        clearBusy(sid);
        clearStatus();
      });
    }
  }, state.streamTimeoutMs + 30000);
}

// ---------- Inject User Message (for plugin card interactions) ----------
export function injectUserMessage(text, options = {}) {
  /**
   * Inject a user message into the conversation as if the user typed it.
   * Used by agent frontend cards (e.g., Pulsar waveform confirm/modify buttons).
   *
   * @param {string} text - The message text to inject
   * @param {object} options - Optional: { sessionId, silent }
   *   - sessionId: target session (defaults to active)
   *   - silent: if true, don't render user bubble (for programmatic confirmations)
   */
  const sessionId = options.sessionId || activeView.sessionId;
  if (!text || !text.trim()) {
    console.warn('[injectUserMessage] empty text');
    return false;
  }
  if (!sessionId) {
    console.warn('[injectUserMessage] no active session');
    return false;
  }
  const isBusy = state.busySessionIds.has(sessionId);
  if (isBusy) {
    console.warn('[injectUserMessage] session is busy:', sessionId);
    return false;
  }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    console.warn('[injectUserMessage] ws not open');
    return false;
  }

  const trimmed = text.trim();

  // Render user bubble (unless silent)
  if (!options.silent) {
    renderUserBubble(trimmed, []);
  }

  // Save to persistence
  saveMsg({ type: 'user', text: trimmed, injected: true });

  // Send via WebSocket (same format as normal send)
  const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  sendWs({
    content: trimmed,
    attachments: [],
    clientMessageId,
    sessionId,
    injected: true,
    chatWidth: activeView.dom.chat?.clientWidth || 0
  });

  // Mark session as busy
  setBusy(sessionId);
  // Only set timer if not already running — don't reset during active turn
  if (!state.turnStartTimes[sessionId]) state.turnStartTimes[sessionId] = Date.now();

  // Safety timeout (same as normal send)
  if (state.sessionBusyTimeouts[sessionId]) {
    clearTimeout(state.sessionBusyTimeouts[sessionId]);
    delete state.sessionBusyTimeouts[sessionId];
  }
  state.sessionBusyTimeouts[sessionId] = setTimeout(() => {
    if (state.busySessionIds.has(sessionId)) {
      sendWs({type: 'interrupt', sessionId});
      import('./chat.js').then(({ renderTimeoutNotice, clearBusy, clearStatus }) => {
        const v = findViewBySessionId(sessionId);
        if (v) { setActiveView(v); renderTimeoutNotice(); }
        clearBusy(sessionId);
        clearStatus();
      });
    }
  }, state.streamTimeoutMs + 30000);

  return true;
}

// ---------- Initialize all input event listeners ----------
export function initInput(view) {
  const input = view.dom.input;
  const sendBtn = view.dom.sendBtn;
  const stopBtn = view.dom.stopBtn;
  const attachBtn = view.dom.attachBtn;
  const voiceBtn = view.dom.voiceBtn;
  const voiceOverlay = view.dom.voiceOverlay;
  const voiceText = view.dom.voiceText;

  const slashDropdown = view.dom.slashDropdown;

  // Auto-resize textarea
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
  });

  // Send button
  sendBtn.onclick = () => { setActiveView(view); send(); };

  // Stop button — send interrupt with sessionId, reset UI immediately
  stopBtn.onclick = () => {
    setActiveView(view);
    const sid = view.sessionId;
    sendWs({type: 'interrupt', sessionId: sid});
    if (sid && state.sessionBusyTimeouts[sid]) {
      clearTimeout(state.sessionBusyTimeouts[sid]);
      delete state.sessionBusyTimeouts[sid];
    }
    import('./chat.js').then(({ clearBusy }) => clearBusy(sid));
  };

  // Composition start/end (IME)
  input.addEventListener('compositionstart', () => { view.composing = true; });
  input.addEventListener('compositionend', () => { view.composing = false; });

  // Paste handler — image paste from clipboard
  input.addEventListener('paste', (e) => {
    setActiveView(view);
    const files = [];
    if (e.clipboardData.items) {
      for (const item of e.clipboardData.items) {
        if (item.kind === 'file') {
          const f = item.getAsFile();
          if (f) files.push(f);
        }
      }
    }
    if (files.length === 0) return; // normal text paste, let browser handle it
    e.preventDefault();
    files.forEach(file => addFileAttachment(file));
  });

  // Keydown handler — slash autocomplete navigation, input history navigation, Enter-to-send
  input.onkeydown = (e) => {
    setActiveView(view);
    // Escape cancels ask mode or skill mode
    if (e.key === 'Escape') {
      if (view.stream.askMode) {
        e.preventDefault();
        cancelAskMode();
        return;
      }
      if (view.skillMode) {
        e.preventDefault();
        cancelSkillMode();
        return;
      }
    }
    // Backspace/Delete on empty input cancels ask/skill mode (like removing a tag)
    if ((e.key === 'Backspace' || e.key === 'Delete') && input.value.trim() === '') {
      if (view.stream.askMode) {
        e.preventDefault();
        cancelAskMode();
        return;
      }
      if (view.skillMode) {
        e.preventDefault();
        cancelSkillMode();
        return;
      }
    }
    if (slashDropdown.classList.contains('on')) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSlashHighlight((view.slashSelectedIndex + 1) % view.slashMatches.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSlashHighlight((view.slashSelectedIndex - 1 + view.slashMatches.length) % view.slashMatches.length);
        return;
      }
      if (e.key === 'Enter') {
        e.preventDefault();
        pickSlashCommand(view.slashSelectedIndex);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        closeSlashDropdown();
        return;
      }
    }
    // Input history navigation (up/down arrows)
    if (!slashDropdown.classList.contains('on') && !view.composing && !e.isComposing && e.keyCode !== 229) {
      if (e.key === 'ArrowUp' && input.selectionStart === 0 && input.selectionEnd === 0) {
        e.preventDefault();
        if (state.inputHistory.length === 0) return;
        if (view.historyIndex === -1) {
          view.historyDraft = input.value;
          view.historyIndex = state.inputHistory.length - 1;
        } else if (view.historyIndex > 0) {
          view.historyIndex--;
        }
        input.value = state.inputHistory[view.historyIndex];
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
        input.setSelectionRange(input.value.length, input.value.length);
        return;
      }
      if (e.key === 'ArrowDown' && input.selectionStart === input.value.length && input.selectionEnd === input.selectionStart) {
        e.preventDefault();
        if (view.historyIndex === -1) return;
        if (view.historyIndex >= state.inputHistory.length - 1) {
          view.historyIndex = -1;
          input.value = view.historyDraft;
        } else {
          view.historyIndex++;
          input.value = state.inputHistory[view.historyIndex];
        }
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
        input.setSelectionRange(input.value.length, input.value.length);
        return;
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      if (view.composing || e.isComposing || e.keyCode === 229) {
        return; // Let browser handle composition confirmation
      }
      e.preventDefault();
      send();
    }
  };

  // Attach button — hidden file input trigger
  attachBtn.onclick = () => {
    setActiveView(view);
    const f = document.createElement('input');
    f.type = 'file';
    f.style.display = 'none';
    document.body.appendChild(f);
    f.onchange = (e) => {
      const file = e.target.files[0];
      if (file) addFileAttachment(file);
      f.remove();
    };
    f.click();
  };

  // Drag & drop on document.body — only register once (primary view)
  if (view.id === 'primary') {
    document.body.addEventListener('dragover', (e) => {
      e.preventDefault();
      e.stopPropagation();
    });
    document.body.addEventListener('drop', (e) => {
      e.preventDefault();
      e.stopPropagation();
      const files = [];
      if (e.dataTransfer.files) {
        for (const f of e.dataTransfer.files) files.push(f);
      }
      files.forEach(f => addFileAttachment(f));
    });
  }

  // Voice start/stop — Web Speech API (toggle mode)
  let voiceActive = false;      // is voice recognition currently active?
  let voiceFinal = '';           // accumulated finalized text this session
  let voiceBefore = '';          // text before cursor at voice start
  let voiceAfter = '';           // text after cursor at voice start

  function createRecognition() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    const rec = new SR();
    rec.lang = getLocale();
    rec.continuous = true;
    rec.interimResults = true;
    rec.maxAlternatives = 3;

    rec.onresult = (ev) => {
      let interimText = '';
      for (let i = ev.resultIndex; i < ev.results.length; i++) {
        // Pick the best alternative (highest confidence)
        const result = ev.results[i];
        let bestIdx = 0;
        let bestConf = result[0].confidence || 0;
        for (let j = 1; j < result.length; j++) {
          const c = result[j].confidence || 0;
          if (c > bestConf) { bestConf = c; bestIdx = j; }
        }
        const t = result[bestIdx].transcript;
        if (result.isFinal) voiceFinal += t;
        else interimText += t;
      }
      const current = voiceFinal + interimText;
      voiceText.textContent = current || t('voice.listening');

      // Dynamic cursor tracking: re-read cursor position each time
      const cursorNow = (input.selectionStart !== null && input.selectionStart !== undefined)
        ? input.selectionStart : input.value.length;
      // Calculate length of old voice text currently in input.value
      const oldVoiceLen = Math.max(0, input.value.length - (voiceBefore.length + voiceAfter.length));
      // Map cursor position from current value (which includes old voice text) to raw text position
      let rawCursor;
      if (cursorNow <= voiceBefore.length) {
        rawCursor = cursorNow; // cursor before voice region
      } else if (cursorNow >= voiceBefore.length + oldVoiceLen) {
        rawCursor = cursorNow - oldVoiceLen; // cursor after voice region
      } else {
        rawCursor = voiceBefore.length; // cursor inside voice region — keep at insertion point
      }
      // Extract raw text (without old voice text) and re-split at new cursor position
      const rawText = input.value.substring(0, voiceBefore.length)
        + input.value.substring(voiceBefore.length + oldVoiceLen);
      voiceBefore = rawText.substring(0, rawCursor);
      voiceAfter = rawText.substring(rawCursor);

      const sep = voiceBefore && !voiceBefore.endsWith(' ') ? ' ' : '';
      input.value = voiceBefore + sep + current + voiceAfter;
      // Place cursor at the end of the newly inserted voice text
      const cursorEnd = voiceBefore.length + (sep ? 1 : 0) + current.length;
      input.setSelectionRange(cursorEnd, cursorEnd);
    };

    rec.onerror = (ev) => {
      // no-speech and aborted are benign — just ignore
      if (ev.error === 'no-speech' || ev.error === 'aborted') {
        return;
      }
      voiceText.textContent = t('voice.error', { error: ev.error });
      setTimeout(() => { if (!voiceActive) return; stopVoice(); }, 1500);
    };

    rec.onend = () => {
      // Recognition ended. If voiceActive is still true, it stopped unexpectedly
      // (browser limit, network issue, etc.). Sync UI to stopped state.
      if (voiceActive) {
        stopVoice();
      } else {
        // User explicitly toggled off — clean up UI
        voiceOverlay.classList.remove('on');
        voiceBtn.classList.remove('recording');
      }
    };

    return rec;
  }

  function startVoice() {
    if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
      alert(t('input.voiceError'));
      return;
    }
    voiceActive = true;
    const pos = (input.selectionStart !== null && input.selectionStart !== undefined) ? input.selectionStart : input.value.length;
    voiceBefore = input.value.substring(0, pos);
    voiceAfter = input.value.substring(pos);
    voiceFinal = '';
    view.recognition = createRecognition();
    view.recognition.start();
    voiceOverlay.classList.add('on');
    voiceText.textContent = t('voice.listening');
    voiceBtn.classList.add('recording');
  }

  function stopVoice() {
    voiceActive = false;
    if (view.recognition) {
      try { view.recognition.stop(); } catch (_) {}
      view.recognition = null;
    }
    voiceOverlay.classList.remove('on');
    voiceBtn.classList.remove('recording');
    // Return focus to input so Enter can send immediately
    if (input.value.trim()) input.focus();
  }

  // Long-press voice: mousedown/touchstart to start, mouseup/mouseleave/touchend to stop
  function onVoiceStart(e) {
    if (e.type === 'touchstart') e.preventDefault();
    startVoice();
  }
  function onVoiceEnd(e) {
    if (voiceActive) stopVoice();
  }
  voiceBtn.addEventListener('mousedown', onVoiceStart);
  voiceBtn.addEventListener('touchstart', onVoiceStart, { passive: false });
  voiceBtn.addEventListener('mouseup', onVoiceEnd);
  voiceBtn.addEventListener('mouseleave', onVoiceEnd);
  voiceBtn.addEventListener('touchend', onVoiceEnd);
  voiceBtn.addEventListener('touchcancel', onVoiceEnd);

  // Escape key stops voice recording (only register once)
  if (view.id === 'primary') {
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && voiceActive) {
        e.preventDefault();
        stopVoice();
      }
    });
  }

  // Slash dropdown input listener and document click listener
  input.addEventListener('input', () => { setActiveView(view); updateSlashDropdown(); });
  document.addEventListener('click', (e) => {
    if (!input.contains(e.target) && !slashDropdown.contains(e.target)) {
      slashDropdown.classList.remove('on');
      view.slashMatches = [];
      view.slashSelectedIndex = -1;
    }
  });

  // Ask/skill indicator cancel buttons (view-specific element IDs)
  {
    const prefix = view.id === 'primary' ? '' : 'secondary-';
    const askCancel = document.getElementById(prefix + 'ask-indicator-cancel');
    if (askCancel) {
      askCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelAskMode();
        input.focus();
      });
    }
    const skillCancel = document.getElementById(prefix + 'skill-indicator-cancel');
    if (skillCancel) {
      skillCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelSkillMode();
        input.focus();
      });
    }
  }
}
