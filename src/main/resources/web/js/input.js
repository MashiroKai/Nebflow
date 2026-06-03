// input.js — Input handling module for Nebflow
// All send logic, keyboard/input events, slash commands, attachments, drag/drop, voice.

import state, { LS_HISTORY_KEY } from './state.js';
import { sendWs } from './ws.js';
import { renderUserBubble, renderSystemBubble, setBusy, renderAttachmentPreview, renderAskBubble } from './chat.js';
import { renderMarkdownWithMath, escapeHtml, smartScroll } from './utils.js';
import { saveMsg } from './persistence.js';
import { saveInputDraft } from './sidebar.js';
import { renderTaskList } from './taskList.js';
import { t } from './i18n.js';
import { getLocale } from './i18n.js';
import { addNotification } from './notificationBanner.js';

// ---------- Slash Commands ----------
const slashCommands = {
  '/clear': {
    desc: () => t('slash.clear'),
    run: () => {
      sendWs({type:'command', command:'clear', sessionId: state.activeSessionId});
      delete state.sessionTasks[state.activeSessionId];
      renderTaskList([]);
      renderSystemBubble(t('slash.clearDone'));
    }
  },
  '/compact': {
    desc: () => t('slash.compact'),
    run: () => {
      sendWs({type:'command', command:'compact', sessionId: state.activeSessionId});
      renderSystemBubble(t('slash.compactDone'));
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
      sendWs({type: 'getModelOptions', sessionId: state.activeSessionId});
    }
  },
  '/bypass': {
    desc: () => t('slash.bypass'),
    run: () => {
      // Show a selection card to toggle bypass-all mode
      if (!state.currentAiBubble) {
        const row = document.createElement('div');
        row.className = 'row ai';
        state.currentAiBubble = document.createElement('div');
        state.currentAiBubble.className = 'bubble ai';
        row.appendChild(state.currentAiBubble);
        state.dom.chat.appendChild(row);
      }
      const isEnabled = state.bypassAllPermission;
      import('./chat.js').then(({ showOptions }) => {
        showOptions(state.currentAiBubble, [
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
  const badge = document.getElementById('bypass-badge');
  if (!badge) return;
  const textEl = badge.querySelector('.bypass-badge-text');
  if (enabled) {
    badge.classList.remove('hidden');
    if (textEl) textEl.textContent = t('chat.bypassBadge');
    if (typeof lucide !== 'undefined') lucide.createIcons();
  } else {
    badge.classList.add('hidden');
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
        run: () => enterSkillMode(skill.name, skill.description)
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
  const input = state.dom.input;
  const text = input.value;
  if (!text.startsWith('/')) {
    closeSlashDropdown();
    return;
  }
  const query = text.slice(1).toLowerCase();
  state.slashMatches = Object.entries(slashCommands)
    .filter(([cmd]) => cmd.slice(1).toLowerCase().startsWith(query))
    .map(([cmd, info]) => ({ cmd, desc: typeof info.desc === 'function' ? info.desc() : info.desc, isSkill: !!info._skill }));
  if (state.slashMatches.length === 0) {
    closeSlashDropdown();
    return;
  }
  const slashDropdown = state.dom.slashDropdown;
  slashDropdown.innerHTML = '';
  state.slashMatches.forEach((item, i) => {
    const div = document.createElement('div');
    div.className = 'slash-item' + (i === 0 ? ' active' : '');
    const badge = item.isSkill ? '<span class="slash-badge skill">' + escapeHtml(t('slash.skillBadge')) + '</span>' : '';
    div.innerHTML = '<div style="display:flex;align-items:center"><span class="slash-cmd">' + escapeHtml(item.cmd) + '</span>' + badge + '</div><span class="slash-desc">' + escapeHtml(item.desc) + '</span>';
    div.onclick = () => { pickSlashCommand(i); };
    div.onmouseenter = () => { setSlashHighlight(i); };
    slashDropdown.appendChild(div);
  });
  state.slashSelectedIndex = 0;
  slashDropdown.classList.add('on');
}

function closeSlashDropdown() {
  state.dom.slashDropdown.classList.remove('on');
  state.slashSelectedIndex = -1;
  state.slashMatches = [];
}

function setSlashHighlight(index) {
  state.slashSelectedIndex = index;
  const items = state.dom.slashDropdown.querySelectorAll('.slash-item');
  items.forEach((el, i) => { el.classList.toggle('active', i === index); });

  const active = items[index];
  if (!active) return;

  const dd = state.dom.slashDropdown;
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
  if (index < 0 || index >= state.slashMatches.length) return;
  const cmd = state.slashMatches[index].cmd;
  state.dom.input.value = '';
  closeSlashDropdown();
  state.dom.input.focus();
  if (slashCommands[cmd] && slashCommands[cmd].run) slashCommands[cmd].run();
}

// ---------- Ask Mode ----------
export function enterAskMode() {
  if (state.askMode) return;
  state.askMode = true;
  updateAskIndicator();
  state.dom.input.placeholder = t('input.askPlaceholder');
  state.dom.input.focus();
}

export function cancelAskMode() {
  if (!state.askMode) return;
  state.askMode = false;
  updateInputIndicator();
  state.dom.input.placeholder = t('input.placeholder');
}

function updateAskIndicator() {
  updateInputIndicator();
}

function updateInputIndicator() {
  const askEl = document.getElementById('ask-indicator');
  const skillEl = document.getElementById('skill-indicator');
  const skillLabel = document.getElementById('skill-indicator-label');
  const input = state.dom.input;
  // Ask mode takes priority over skill mode
  if (state.askMode) {
    if (askEl) askEl.classList.add('show');
    if (skillEl) skillEl.classList.remove('show');
    input.style.paddingLeft = '';
    if (askEl) {
      askEl.classList.add('show');
      // Measure ask indicator width after layout
      const w = askEl.offsetWidth + 12;
      input.style.paddingLeft = Math.max(w, 48) + 'px';
    }
  } else if (state.skillMode) {
    if (askEl) askEl.classList.remove('show');
    if (skillEl) {
      if (skillLabel) skillLabel.textContent = state.skillModeName || 'SKILL';
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
export function enterSkillMode(skillName, description) {
  if (state.skillMode) {
    // Already in skill mode — if it's a different skill, switch; otherwise do nothing
    if (state.skillModeName === skillName) return;
    cancelSkillMode();
  }
  // Cancel ask mode if active
  if (state.askMode) cancelAskMode();
  state.skillMode = true;
  state.skillModeName = skillName;
  state.skillModeDesc = description || '';
  updateInputIndicator();
  state.dom.input.placeholder = t('input.skillPlaceholder');
  state.dom.input.focus();
}

export function cancelSkillMode() {
  if (!state.skillMode) return;
  state.skillMode = false;
  state.skillModeName = '';
  state.skillModeDesc = '';
  updateInputIndicator();
  state.dom.input.placeholder = t('input.placeholder');
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
export async function addFileAttachment(file, callback) {
  if (file.type.startsWith('image/')) {
    if (file.size > 10 * 1024 * 1024) {
      alert('Image too large (max 10MB): ' + file.name);
      return;
    }
    try {
      const { dataUrl, w, h } = await compressImage(file);
      state.pendingAttachments.push({
        type: 'image', mimeType: 'image/jpeg',
        data: dataUrl.split(',')[1],
        name: file.name, preview: dataUrl
      });
    } catch (e) {
      console.warn('[input] image compression failed, using original:', e);
      // Fallback to original
      const reader = new FileReader();
      reader.onload = () => {
        state.pendingAttachments.push({
          type: 'image', mimeType: 'image/jpeg',
          data: reader.result.split(',')[1],
          name: file.name, preview: reader.result
        });
        renderAttachmentPreview();
        if (callback) callback();
      };
      reader.readAsDataURL(file);
      return;
    }
    renderAttachmentPreview();
    if (callback) callback();
  } else {
    // Non-image: save to disk on backend, no size limit needed
    const reader = new FileReader();
    reader.onload = () => {
      state.pendingAttachments.push({
        type: 'text', mimeType: file.type || 'text/plain',
        data: reader.result, name: file.name
      });
      renderAttachmentPreview();
      if (callback) callback();
    };
    reader.readAsText(file);
  }
}

// ---------- Send ----------
export function send() {
  if (state.isSending) {
    console.warn('[send] blocked: already sending');
    return;
  }
  const input = state.dom.input;
  const text = input.value.trim();
  const isBusy = state.busySessionIds.has(state.activeSessionId);
  // If in skill mode, send as skill activation
  if (state.skillMode) {
    const skillName = state.skillModeName;
    cancelSkillMode();
    if (!text || isBusy || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
      return;
    }
    state.isSending = true;
    if (state.activeSessionId) state.turnExpecting[state.activeSessionId] = true;
    sendWs({ type: 'skill', skillName, input: text, sessionId: state.activeSessionId });
    renderSystemBubble(t('slash.skillActivated', { skill: skillName }));
    renderUserBubble(text);
    // Show persistent notification so skill name stays visible after session switch
    addNotification('skill', t('slash.skillActivated', { skill: skillName }), { dismissAfter: 20000 });
    saveMsg({type:'user', text, attachments: (state.pendingAttachments||[]).map(a=>({type:a.type,name:a.name,preview:a.preview}))});
    input.value = '';
    saveInputDraft(state.activeSessionId);
    setTimeout(() => { state.isSending = false; }, 300);
    return;
  }
  // If in ask mode, send as ask question
  if (state.askMode) {
    cancelAskMode();
    if (!text || isBusy || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
      state.isSending = false;
      return;
    }
    state.isSending = true;
    if (state.activeSessionId) state.turnExpecting[state.activeSessionId] = true;
    sendWs({ type: 'ask', question: text, sessionId: state.activeSessionId });
    state.sessionAskBuffers[state.activeSessionId] = { question: text, answer: '' };
    renderAskBubble(text);
    input.value = '';
    saveInputDraft(state.activeSessionId);
    setTimeout(() => { state.isSending = false; }, 300);
    return;
  }
  if ((!text && state.pendingAttachments.length === 0) || isBusy) {
    console.warn('[send] blocked:', { text: text.slice(0,20), busy: state.busySessionIds.has(state.activeSessionId), wsState: state.ws?.readyState });
    return;
  }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    console.warn('[send] ws not open:', { ws: !!state.ws, readyState: state.ws?.readyState });
    return;
  }
  state.isSending = true;
  // Mark this session as expecting a turn (prevents stray thinking bubbles after done)
  if (state.activeSessionId) state.turnExpecting[state.activeSessionId] = true;
  // Intercept /ask <question> before normal slash handling
  if (text.startsWith('/ask ')) {
    const question = text.slice(5).trim();
    if (question) {
      sendWs({ type: 'ask', question, sessionId: state.activeSessionId });
      state.sessionAskBuffers[state.activeSessionId] = { question, answer: '' };
      renderAskBubble(question);
    }
    input.value = '';
    saveInputDraft(state.activeSessionId);
    setTimeout(() => { state.isSending = false; }, 300);
    return;
  }
  if (handleSlash(text)) {
    input.value = '';
    saveInputDraft(state.activeSessionId);
    // Debounce: keep lock briefly to prevent accidental double-trigger of slash commands
    setTimeout(() => { state.isSending = false; }, 300);
    return;
  }
  renderUserBubble(text, state.pendingAttachments);
  saveMsg({type:'user', text, attachments: (state.pendingAttachments||[]).map(a=>({type:a.type,name:a.name,preview:a.preview}))});
  // Save to input history
  if (text && text !== '/clear') {
    state.inputHistory.push(text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e) {
      console.warn('[input] history save failed:', e);
    }
  }
  state.historyIndex = -1;
  state.historyDraft = '';
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: text,
      attachments: state.pendingAttachments.map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name
      })),
      clientMessageId,
      sessionId: state.activeSessionId
    });
  } catch (e) {
    console.error('WebSocket send failed:', e);
  }
  input.value = '';
  input.style.height = 'auto';
  state.pendingAttachments = [];
  state.dom.attPreview.innerHTML = '';
  // Immediately clear the draft for this session so it is not restored after refresh
  saveInputDraft(state.activeSessionId);
  setBusy(state.activeSessionId);
  // Start turn timer
  state.turnStartTimes[state.activeSessionId] = Date.now();
  // Release send lock after a short debounce to prevent double-click / rapid Enter
  setTimeout(() => { state.isSending = false; }, 300);
  // Clean up any orphaned thinking placeholders from previous incomplete streams
  if (window.__stopThinkingTimer) window.__stopThinkingTimer();
  state.dom.chat.querySelectorAll('.thinking-placeholder').forEach(el => {
    const row = el.closest('.row');
    if (row) row.remove();
  });
  state.currentAiBubble = null;
  state.aiText = '';
  state.currentThinkingBubble = null;
  state.thinkingText = '';
  // Safety timeout: backend sends 'timeout' event, but this is a last-resort fallback
  // in case the backend event never arrives. Uses streamTimeoutMs from server config (+ 30s buffer).
  const sid = state.activeSessionId;
  if (sid && state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  state.sessionBusyTimeouts[sid] = setTimeout(() => {
    if (state.busySessionIds.has(sid)) {
      import('./chat.js').then(({ renderTimeoutNotice, clearBusy, clearStatus }) => {
        if (sid === state.activeSessionId) renderTimeoutNotice();
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
  const sessionId = options.sessionId || state.activeSessionId;
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
    injected: true
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
      import('./chat.js').then(({ renderTimeoutNotice, clearBusy, clearStatus }) => {
        if (sessionId === state.activeSessionId) renderTimeoutNotice();
        clearBusy(sessionId);
        clearStatus();
      });
    }
  }, state.streamTimeoutMs + 30000);

  return true;
}

// ---------- Initialize all input event listeners ----------
export function initInput() {
  const input = state.dom.input;
  const sendBtn = state.dom.sendBtn;
  const stopBtn = state.dom.stopBtn;
  const attachBtn = state.dom.attachBtn;
  const voiceBtn = state.dom.voiceBtn;
  const voiceOverlay = state.dom.voiceOverlay;
  const voiceText = state.dom.voiceText;

  const slashDropdown = state.dom.slashDropdown;

  // Auto-resize textarea
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
  });

  // Send button
  sendBtn.onclick = send;

  // Stop button — send interrupt with sessionId, reset UI immediately
  stopBtn.onclick = () => {
    const sid = state.activeSessionId;
    sendWs({type: 'interrupt', sessionId: sid});
    if (sid && state.sessionBusyTimeouts[sid]) {
      clearTimeout(state.sessionBusyTimeouts[sid]);
      delete state.sessionBusyTimeouts[sid];
    }
    import('./chat.js').then(({ clearBusy }) => clearBusy(sid));
  };

  // Composition start/end (IME)
  input.addEventListener('compositionstart', () => { state.composing = true; });
  input.addEventListener('compositionend', () => { state.composing = false; });

  // Paste handler — image paste from clipboard
  input.addEventListener('paste', (e) => {
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
    // Escape cancels ask mode or skill mode
    if (e.key === 'Escape') {
      if (state.askMode) {
        e.preventDefault();
        cancelAskMode();
        return;
      }
      if (state.skillMode) {
        e.preventDefault();
        cancelSkillMode();
        return;
      }
    }
    // Backspace/Delete on empty input cancels ask/skill mode (like removing a tag)
    if ((e.key === 'Backspace' || e.key === 'Delete') && state.dom.input.value.trim() === '') {
      if (state.askMode) {
        e.preventDefault();
        cancelAskMode();
        return;
      }
      if (state.skillMode) {
        e.preventDefault();
        cancelSkillMode();
        return;
      }
    }
    if (slashDropdown.classList.contains('on')) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSlashHighlight((state.slashSelectedIndex + 1) % state.slashMatches.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSlashHighlight((state.slashSelectedIndex - 1 + state.slashMatches.length) % state.slashMatches.length);
        return;
      }
      if (e.key === 'Enter') {
        e.preventDefault();
        pickSlashCommand(state.slashSelectedIndex);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        closeSlashDropdown();
        return;
      }
    }
    // Input history navigation (up/down arrows)
    if (!slashDropdown.classList.contains('on') && !state.composing && !e.isComposing && e.keyCode !== 229) {
      if (e.key === 'ArrowUp' && input.selectionStart === 0 && input.selectionEnd === 0) {
        e.preventDefault();
        if (state.inputHistory.length === 0) return;
        if (state.historyIndex === -1) {
          state.historyDraft = input.value;
          state.historyIndex = state.inputHistory.length - 1;
        } else if (state.historyIndex > 0) {
          state.historyIndex--;
        }
        input.value = state.inputHistory[state.historyIndex];
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
        input.setSelectionRange(input.value.length, input.value.length);
        return;
      }
      if (e.key === 'ArrowDown' && input.selectionStart === input.value.length && input.selectionEnd === input.selectionStart) {
        e.preventDefault();
        if (state.historyIndex === -1) return;
        if (state.historyIndex >= state.inputHistory.length - 1) {
          state.historyIndex = -1;
          input.value = state.historyDraft;
        } else {
          state.historyIndex++;
          input.value = state.inputHistory[state.historyIndex];
        }
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
        input.setSelectionRange(input.value.length, input.value.length);
        return;
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      if (state.composing || e.isComposing || e.keyCode === 229) {
        return; // Let browser handle composition confirmation
      }
      e.preventDefault();
      send();
    }
  };

  // Attach button — hidden file input trigger
  attachBtn.onclick = () => {
    const f = document.createElement('input');
    f.type = 'file';
    f.style.display = 'none';
    // No accept filter — backend saves to disk, LLM reads via path
    document.body.appendChild(f);
    f.onchange = (e) => {
      const file = e.target.files[0];
      if (file) addFileAttachment(file);
      f.remove();
    };
    f.click();
  };

  // Drag & drop on document.body
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
    state.recognition = createRecognition();
    state.recognition.start();
    voiceOverlay.classList.add('on');
    voiceText.textContent = t('voice.listening');
    voiceBtn.classList.add('recording');
  }

  function stopVoice() {
    voiceActive = false;
    if (state.recognition) {
      try { state.recognition.stop(); } catch (_) {}
      state.recognition = null;
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

  // Escape key stops voice recording (fallback)
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && voiceActive) {
      e.preventDefault();
      stopVoice();
    }
  });

  // Slash dropdown input listener and document click listener
  input.addEventListener('input', updateSlashDropdown);
  document.addEventListener('click', (e) => {
    if (!input.contains(e.target) && !slashDropdown.contains(e.target)) {
      closeSlashDropdown();
    }
  });

  // Ask indicator cancel button
  const askCancel = document.getElementById('ask-indicator-cancel');
  if (askCancel) {
    askCancel.addEventListener('click', (e) => {
      e.stopPropagation();
      cancelAskMode();
      state.dom.input.focus();
    });
  }
  // Skill indicator cancel button
  const skillCancel = document.getElementById('skill-indicator-cancel');
  if (skillCancel) {
    skillCancel.addEventListener('click', (e) => {
      e.stopPropagation();
      cancelSkillMode();
      state.dom.input.focus();
    });
  }
}
