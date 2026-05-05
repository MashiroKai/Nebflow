// input.js — Input handling module for Nebflow
// All send logic, keyboard/input events, slash commands, attachments, drag/drop, voice.

import state, { LS_HISTORY_KEY } from './state.js';
import { sendWs } from './ws.js';
import { renderUserBubble, renderSystemBubble, setBusy, renderAttachmentPreview } from './chat.js';
import { renderMarkdownWithMath, escapeHtml, smartScroll } from './utils.js';
import { saveMsg } from './persistence.js';

// ---------- Slash Commands ----------
const slashCommands = {
  '/clear': {
    desc: 'Clear LLM context (keeps chat history)',
    run: () => {
      sendWs({type:'command', command:'clear'});
      renderSystemBubble('Context cleared. LLM memory reset.');
    }
  },
  '/thinking': {
    desc: 'Toggle extended thinking (Deep mode)',
    run: () => {
      if (!state.currentAiBubble) {
        const row = document.createElement('div');
        row.className = 'row ai';
        state.currentAiBubble = document.createElement('div');
        state.currentAiBubble.className = 'bubble ai';
        row.appendChild(state.currentAiBubble);
        state.dom.chat.appendChild(row);
      }
      import('./chat.js').then(({ showOptions }) => {
        showOptions(state.currentAiBubble, [
          {question: 'Extended thinking', options: [
            {label:'Enable', desc:'Deep analysis with thinking tokens'},
            {label:'Disable', desc:'Normal response mode'}
          ]}
        ], (answers) => {
          const mode = answers[0];
          state.thinkingMode = mode === 'Enable' ? {type: 'enabled', budget_tokens: 16000} : null;
          sendWs({type: 'setThinking', thinking: state.thinkingMode});
          renderSystemBubble('Thinking: ' + mode.toLowerCase());
        }, 'Confirm');
      });
    }
  },
  '/trust': {
    desc: 'Manage tool approval policy',
    run: () => {
      if (!state.currentAiBubble) {
        const row = document.createElement('div');
        row.className = 'row ai';
        state.currentAiBubble = document.createElement('div');
        state.currentAiBubble.className = 'bubble ai';
        row.appendChild(state.currentAiBubble);
        state.dom.chat.appendChild(row);
      }
      import('./chat.js').then(({ showOptions }) => {
        showOptions(state.currentAiBubble, [
          {question: 'Tool approval policy', options: [
            {label: 'Auto-approve all', desc: 'All tools execute without asking'},
            {label: 'Ask every time', desc: 'Prompt for Bash/Write/Edit/Curl'},
            {label: 'Block dangerous', desc: 'Block Bash/Write/Edit/Curl entirely'}
          ]}
        ], (answers) => {
          const policy = answers[0] === 'Auto-approve all' ? 'auto'
                       : answers[0] === 'Block dangerous' ? 'block'
                       : 'ask';
          sendWs({type: 'setPolicy', policy});
          renderSystemBubble('Policy: ' + answers[0]);
        }, 'Apply');
      });
    }
  },
  '/new': {
    desc: 'Create a new session',
    // Will be wired up from session module in main.js
    run: null
  }
};

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
    .map(([cmd, info]) => ({ cmd, desc: info.desc }));
  if (state.slashMatches.length === 0) {
    closeSlashDropdown();
    return;
  }
  const slashDropdown = state.dom.slashDropdown;
  slashDropdown.innerHTML = '';
  state.slashMatches.forEach((item, i) => {
    const div = document.createElement('div');
    div.className = 'slash-item' + (i === 0 ? ' active' : '');
    div.innerHTML = '<span class="slash-cmd">' + escapeHtml(item.cmd) + '</span><span class="slash-desc">' + escapeHtml(item.desc) + '</span>';
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
}

function pickSlashCommand(index) {
  if (index < 0 || index >= state.slashMatches.length) return;
  const cmd = state.slashMatches[index].cmd;
  state.dom.input.value = '';
  closeSlashDropdown();
  state.dom.input.focus();
  if (slashCommands[cmd] && slashCommands[cmd].run) slashCommands[cmd].run();
}

// ---------- File Attachment ----------
export function addFileAttachment(file, callback) {
  if (file.type.startsWith('image/')) {
    const reader = new FileReader();
    reader.onload = () => {
      if (file.size > 5 * 1024 * 1024) {
        alert('Image too large (max 5MB): ' + file.name);
        return;
      }
      state.pendingAttachments.push({
        type: 'image', mimeType: file.type,
        data: reader.result.split(',')[1],
        name: file.name, preview: reader.result
      });
      renderAttachmentPreview();
      if (callback) callback();
    };
    reader.readAsDataURL(file);
  } else {
    // Non-image: check size
    const MAX_FILE_SIZE = 500 * 1024;
    if (file.size > MAX_FILE_SIZE) {
      alert('File too large (max 500KB for text files): ' + file.name + ' (' + (file.size / 1024).toFixed(0) + 'KB)');
      return;
    }
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
  if ((!text && state.pendingAttachments.length === 0) || isBusy) {
    console.warn('[send] blocked:', { text: text.slice(0,20), busy: state.busySessionIds.has(state.activeSessionId), wsState: state.ws?.readyState });
    return;
  }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    console.warn('[send] ws not open:', { ws: !!state.ws, readyState: state.ws?.readyState });
    return;
  }
  state.isSending = true;
  if (handleSlash(text)) {
    input.value = '';
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
      clientMessageId
    });
  } catch (e) {
    console.error('WebSocket send failed:', e);
  }
  input.value = '';
  input.style.height = 'auto';
  state.pendingAttachments = [];
  state.dom.attPreview.innerHTML = '';
  setBusy(state.activeSessionId);
  // Release send lock after a short debounce to prevent double-click / rapid Enter
  setTimeout(() => { state.isSending = false; }, 300);
  // Clean up any orphaned thinking placeholders from previous incomplete streams
  state.dom.chat.querySelectorAll('.thinking-placeholder').forEach(el => {
    const row = el.closest('.row');
    if (row) row.remove();
  });
  state.currentAiBubble = null;
  state.aiText = '';
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

// ---------- Wire up /new to session module (called from main.js) ----------
export function setNewSessionHandler(fn) {
  slashCommands['/new'].run = fn;
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
    sendWs({content: '__interrupt__', sessionId: sid});
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
    f.accept = 'image/*,.txt,.md,.json,.scala,.js,.ts,.py,.java,.go,.rs,.csv,.xml,.yaml,.yml,.html,.css,.log,.sh,.bash,.toml,.conf,.cfg,.ini,.env,.sql,.proto,.graphql,.tf,.dart,.rb,.php,.c,.cpp,.h,.hpp,.r,.swift,.kt,.kts';
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

  // Voice start/stop — Web Speech API
  function startVoice(e) {
    e.preventDefault();
    if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
      alert('Voice input not supported in this browser. Try Chrome.');
      return;
    }
    state.recognition = new (window.SpeechRecognition || window.webkitSpeechRecognition)();
    state.recognition.lang = 'zh-CN';
    state.recognition.continuous = true;
    state.recognition.interimResults = true;
    voiceOverlay.classList.add('on');
    voiceText.textContent = 'Listening...';
    const voiceBase = input.value; // text before this voice session

    let voiceFinal = ''; // accumulated finalized text this session
    state.recognition.onresult = (ev) => {
      let interimText = '';
      for (let i = ev.resultIndex; i < ev.results.length; i++) {
        const t = ev.results[i][0].transcript;
        if (ev.results[i].isFinal) voiceFinal += t;
        else interimText += t;
      }
      const current = voiceFinal + interimText;
      voiceText.textContent = current || 'Listening...';
      input.value = voiceBase + (voiceBase && current ? ' ' : '') + current;
    };
    state.recognition.onerror = (ev) => {
      voiceText.textContent = 'Error: ' + ev.error;
      setTimeout(() => stopVoice(), 1000);
    };
    state.recognition.onend = () => {
      voiceOverlay.classList.remove('on');
      voiceBtn.classList.remove('recording');
    };
    state.recognition.start();
    voiceBtn.classList.add('recording');
  }

  function stopVoice() {
    if (state.recognition) { state.recognition.stop(); state.recognition = null; }
    voiceOverlay.classList.remove('on');
    voiceBtn.classList.remove('recording');
  }

  voiceBtn.addEventListener('mousedown', startVoice);
  voiceBtn.addEventListener('mouseup', stopVoice);
  voiceBtn.addEventListener('mouseleave', stopVoice);
  voiceBtn.addEventListener('touchstart', startVoice, {passive:false});
  voiceBtn.addEventListener('touchend', stopVoice);

  // Slash dropdown input listener and document click listener
  input.addEventListener('input', updateSlashDropdown);
  document.addEventListener('click', (e) => {
    if (!input.contains(e.target) && !slashDropdown.contains(e.target)) {
      closeSlashDropdown();
    }
  });
}
