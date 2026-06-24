// secondary-chat.js — Secondary panel (画板) initialization only.
// All message rendering is handled by ws.js swap + existing main.js handlers.
import state from './state.js';
import { sendWs } from './ws.js';
import { smartScroll, escapeHtml } from './utils.js';
import { renderUserBubble, renderAttachmentPreview, renderSystemBubble } from './chat.js';
import { saveMsg } from './persistence.js';
import { addFileAttachment } from './input.js';
import { t, getLocale } from './i18n.js';

// ── Slash command data (simplified) ───────────────────────────────────
const BUILT_IN_SLASH = [
  { cmd: '/clear',   desc: 'Clear conversation history' },
  { cmd: '/compact', desc: 'Compact conversation context' },
  { cmd: '/model',   desc: 'Switch AI model' },
  { cmd: '/bypass',  desc: 'Toggle bypass-all-permission mode' },
];

let secSlashMatches = [];
let secSlashSelected = 0;
let secComposing = false;

// ── Secondary slash execution ──────────────────────────────────────────
// The primary window's slashCommands run() are hard-bound to state.activeSessionId,
// so the secondary window needs its own session-scoped executor. These mirror the
// primary commands but route to state.secondarySessionId and render into #secondary-chat.
function renderSecondarySystemBubble(text) {
  const secChat = document.getElementById('secondary-chat');
  const origChat = state.dom.chat;
  state.dom.chat = secChat;
  renderSystemBubble(text);
  state.dom.chat = origChat;
}

// Returns true if the text was a slash command that was handled (and should NOT be sent as a message).
function handleSecondarySlash(text) {
  const cmd = text.trim().split(/\s/)[0];
  const sid = state.secondarySessionId;
  if (!sid) return false;
  switch (cmd) {
    case '/clear':
      sendWs({ type: 'command', command: 'clear', sessionId: sid });
      delete state.sessionTasks[sid];
      renderSecondarySystemBubble(t('slash.clearDone'));
      return true;
    case '/compact':
      sendWs({ type: 'command', command: 'compact', sessionId: sid });
      renderSecondarySystemBubble(t('slash.compactDone'));
      return true;
    case '/model':
      sendWs({ type: 'getModelOptions', sessionId: sid });
      return true;
    case '/bypass':
      // Bypass is a global toggle. Show a confirm-style system bubble rather than the
      // full option card (which is bound to the primary window's currentAiBubble).
      state.bypassAllPermission = !state.bypassAllPermission;
      renderSecondarySystemBubble(
        state.bypassAllPermission ? t('slash.bypassEnabled') : t('slash.bypassDisabled')
      );
      return true;
    default:
      return false;
  }
}

// Handle "/ask <question>" — sent directly as an ask turn (parity with the primary
// window's pre-slash interception). "/ask" with no argument is not supported here
// because the interactive ask-mode (typed `?`) is bound to the primary input state.
function handleSecondaryAsk(text) {
  if (!text.startsWith('/ask ')) return false;
  const sid = state.secondarySessionId;
  if (!sid) return false;
  const question = text.slice(5).trim();
  if (!question) return false;
  sendWs({ type: 'ask', question, sessionId: sid });
  state.sessionAskBuffers[sid] = { question, answer: '' };
  return true;
}


// ── Public API ─────────────────────────────────────────────────────────

// Save the secondary input as a draft for the given (soon-to-be-left) session.
export function saveSecondaryDraft(sessionId) {
  if (!sessionId) return;
  const input = document.getElementById('secondary-input');
  if (!input) return;
  const text = input.value;
  const attachments = state._secPendingAttachments || [];
  if (text || attachments.length > 0) {
    state._secInputDrafts[sessionId] = {
      text,
      attachments: JSON.parse(JSON.stringify(attachments)),
    };
  } else {
    delete state._secInputDrafts[sessionId];
  }
}

// Restore the secondary input from a draft for the given session.
function restoreSecondaryDraft(sessionId) {
  const input = document.getElementById('secondary-input');
  if (!input) return;
  const draft = state._secInputDrafts[sessionId];
  if (draft) {
    input.value = draft.text || '';
    state._secPendingAttachments = draft.attachments || [];
  } else {
    input.value = '';
    state._secPendingAttachments = [];
  }
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 200) + 'px';
  renderAttachmentPreview({
    attPreviewEl: document.getElementById('secondary-attachment-preview'),
    attachments: state._secPendingAttachments,
  });
  updateSecondarySendBtn();
}

export function loadSecondary(sessionId) {
  // Reset secondary streaming state (must mirror the swap fields in ws.js so a
  // fresh secondary session never inherits stale bubbles/buffers from the previous one).
  state._secStream = {
    aiText: '', currentAiBubble: null,
    thinkingText: '', currentThinkingBubble: null,
    currentAskBubble: null, askAnswerText: '', askMode: false,
    agentBubbles: {}, activeAgentId: null, activeSubAgents: {},
    scrollSnapped: true,
  };
  // Reset secondary pagination state (independent from primary)
  state._secHistoryOffset = 0;
  state._secHistoryHasMore = false;
  state._secHistoryLoading = false;
  state._secPendingInitialLoad = true;
  state._secScrollSnapped = true;
  const el = document.getElementById('secondary-chat');
  if (el) el.innerHTML = '<div style="padding:24px;color:var(--color-text-muted);text-align:center;font-size:13px">Loading...</div>';
  // Sync button state with current busy status
  showSecondaryBusy(state.busySessionIds.has(sessionId));
  // Restore any saved draft for this session (text + attachments).
  restoreSecondaryDraft(sessionId);
  // Request history — ws.js swap + main.js historyPage handler will render it
  sendWs({ type: 'getHistory', sessionId, limit: 50 });
}

export function sendSecondary() {
  const input = document.getElementById('secondary-input');
  if (!input) return;
  const text = input.value.trim();
  const attachments = state._secPendingAttachments || [];
  if ((!text && attachments.length === 0) || !state.secondarySessionId) return;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  // Block if secondary session is already busy
  if (state.busySessionIds.has(state.secondarySessionId)) return;

  const secChat = document.getElementById('secondary-chat');

  // Render user bubble via renderUserBubble (swap state.dom.chat temporarily)
  const origChat = state.dom.chat;
  state.dom.chat = secChat;
  renderUserBubble(text, attachments);
  state.dom.chat = origChat;
  // Scroll-to-bottom on send. We do this directly rather than via smartScroll
  // because sending happens outside the ws.js swap window (no message being processed),
  // so state.dom.chat here still points at the primary chat.
  if (secChat) {
    requestAnimationFrame(() => { secChat.scrollTop = secChat.scrollHeight; });
  }

  // Persist user message
  saveMsg({
    type: 'user', text,
    attachments: attachments.map(a => ({ type: a.type, name: a.name, preview: a.preview })),
  }, state.secondarySessionId);

  // Send to backend (same format as input.js)
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: text,
      attachments: attachments.map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
      })),
      clientMessageId,
      sessionId: state.secondarySessionId,
      chatWidth: secChat?.clientWidth || 0,
    });
  } catch (e) {
    console.error('[secondary] send failed:', e);
  }

  // Mark busy and update UI
  state.busySessionIds.add(state.secondarySessionId);
  state.turnExpecting[state.secondarySessionId] = true;
  state.turnStartTimes[state.secondarySessionId] = Date.now();
  showSecondaryBusy(true);

  // Clear input + attachments
  input.value = '';
  input.style.height = 'auto';
  state._secPendingAttachments = [];
  const attPreview = document.getElementById('secondary-attachment-preview');
  if (attPreview) attPreview.innerHTML = '';
  // Clear the draft for this session so it isn't restored after a refresh.
  delete state._secInputDrafts[state.secondarySessionId];
  updateSecondarySendBtn();
}

// ── Busy state UI ──────────────────────────────────────────────────────

function showSecondaryBusy(busy) {
  const sendBtn = document.getElementById('secondary-send-btn');
  const stopBtn = document.getElementById('secondary-stop-btn');
  const input = document.getElementById('secondary-input');
  if (sendBtn) sendBtn.style.display = busy ? 'none' : 'flex';
  if (stopBtn) stopBtn.style.display = busy ? 'flex' : 'none';
  if (input) input.disabled = busy;
}

function updateSecondarySendBtn() {
  const input = document.getElementById('secondary-input');
  const sendBtn = document.getElementById('secondary-send-btn');
  if (!input || !sendBtn) return;
  const hasText = input.value.trim();
  const hasAttachments = (state._secPendingAttachments || []).length > 0;
  sendBtn.disabled = !(hasText || hasAttachments);
}

// ── Slash dropdown (simplified autocomplete) ──────────────────────────

function getSecondarySlashCommands() {
  const cmds = [...BUILT_IN_SLASH];
  if (state.skills) {
    state.skills.forEach(skill => {
      cmds.push({
        cmd: '/' + skill.name,
        desc: skill.description || '',
        isSkill: true,
      });
    });
  }
  return cmds;
}

function updateSecondarySlash() {
  const input = document.getElementById('secondary-input');
  const dropdown = document.getElementById('secondary-slash-dropdown');
  if (!input || !dropdown) return;

  if (!input.value.startsWith('/')) {
    closeSecondarySlash(dropdown);
    return;
  }
  const query = input.value.slice(1).toLowerCase();
  secSlashMatches = getSecondarySlashCommands()
    .filter(c => c.cmd.slice(1).toLowerCase().startsWith(query));
  if (secSlashMatches.length === 0) {
    closeSecondarySlash(dropdown);
    return;
  }

  dropdown.innerHTML = '';
  secSlashMatches.forEach((item, i) => {
    const div = document.createElement('div');
    div.className = 'slash-item' + (i === 0 ? ' active' : '');
    const badge = item.isSkill ? '<span class="slash-badge skill">SKILL</span>' : '';
    div.innerHTML =
      '<div style="display:flex;align-items:center">' +
      '<span class="slash-cmd">' + escapeHtml(item.cmd) + '</span>' + badge +
      '</div><span class="slash-desc">' + escapeHtml(item.desc) + '</span>';
    div.onclick = () => pickSecondarySlash(i);
    div.onmouseenter = () => {
      secSlashSelected = i;
      updateSecondaryHighlight(dropdown);
    };
    dropdown.appendChild(div);
  });
  secSlashSelected = 0;
  dropdown.classList.add('on');
}

function closeSecondarySlash(dropdown) {
  if (!dropdown) return;
  dropdown.classList.remove('on');
  secSlashMatches = [];
  secSlashSelected = -1;
}

function updateSecondaryHighlight(dropdown) {
  const items = dropdown.querySelectorAll('.slash-item');
  items.forEach((el, i) => el.classList.toggle('active', i === secSlashSelected));
}

function pickSecondarySlash(index) {
  const input = document.getElementById('secondary-input');
  const dropdown = document.getElementById('secondary-slash-dropdown');
  if (index < 0 || index >= secSlashMatches.length) return;
  const cmd = secSlashMatches[index].cmd;
  if (input) {
    input.value = cmd + ' ';
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 200) + 'px';
  }
  closeSecondarySlash(dropdown);
}

// ── Initialization ─────────────────────────────────────────────────────

export function initSecondaryChat() {
  // Set initial button states (send visible, stop hidden)
  showSecondaryBusy(false);
  updateSecondarySendBtn();

  const sendBtn = document.getElementById('secondary-send-btn');
  if (sendBtn) sendBtn.addEventListener('click', (e) => { e.preventDefault(); sendSecondary(); });

  const stopBtn = document.getElementById('secondary-stop-btn');
  if (stopBtn) stopBtn.addEventListener('click', (e) => {
    e.preventDefault();
    if (state.secondarySessionId) sendWs({ type: 'interrupt', sessionId: state.secondarySessionId });
  });

  const input = document.getElementById('secondary-input');
  if (input) {
    // IME composition tracking — prevents sending on Enter while composing CJK
    input.addEventListener('compositionstart', () => { secComposing = true; });
    input.addEventListener('compositionend', () => { secComposing = false; });

    input.addEventListener('keydown', (e) => {
      const dropdown = document.getElementById('secondary-slash-dropdown');
      // Slash dropdown navigation
      if (dropdown && dropdown.classList.contains('on')) {
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          secSlashSelected = (secSlashSelected + 1) % secSlashMatches.length;
          updateSecondaryHighlight(dropdown);
          return;
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault();
          secSlashSelected = (secSlashSelected - 1 + secSlashMatches.length) % secSlashMatches.length;
          updateSecondaryHighlight(dropdown);
          return;
        }
        if (e.key === 'Enter') {
          e.preventDefault();
          pickSecondarySlash(secSlashSelected);
          return;
        }
        if (e.key === 'Escape') {
          e.preventDefault();
          closeSecondarySlash(dropdown);
          return;
        }
      }
      if (e.key === 'Enter' && !e.shiftKey) {
        if (secComposing || e.isComposing || e.keyCode === 229) return;
        e.preventDefault();
        const val = input.value.trim();
        // "/ask <question>" is intercepted before other slash handling (parity with primary).
        if (handleSecondaryAsk(val)) {
          input.value = '';
          input.style.height = 'auto';
          updateSecondarySendBtn();
          return;
        }
        // Try other slash commands (clear/compact/model/bypass).
        if (val.startsWith('/') && handleSecondarySlash(val)) {
          input.value = '';
          input.style.height = 'auto';
          updateSecondarySendBtn();
          return;
        }
        sendSecondary();
      }
    });
    input.addEventListener('input', () => {
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 200) + 'px';
      updateSecondarySlash();
      updateSecondarySendBtn();
    });
  }

  // Scroll-up pagination for secondary chat
  const secChat = document.getElementById('secondary-chat');
  if (secChat) {
    secChat.addEventListener('scroll', () => {
      const sid = state.secondarySessionId;
      if (!sid) return;
      state._secScrollSnapped = secChat.scrollTop + secChat.clientHeight >= secChat.scrollHeight - 40;
      if (secChat.scrollTop < 100 && state._secHistoryHasMore && !state._secHistoryLoading && state._secHistoryOffset > 0) {
        state._secHistoryLoading = true;
        sendWs({ type: 'getHistory', sessionId: sid, limit: 50, beforeIndex: state._secHistoryOffset });
      }
    });
  }

  // Restore buttons when secondary turn completes
  // (clearBusy in chat.js dispatches this event)
  window.addEventListener('session-busy', (e) => {
    const { sessionId, busy } = e.detail;
    if (sessionId === state.secondarySessionId && !busy) {
      showSecondaryBusy(false);
    }
  });

  // Close slash dropdown when clicking outside
  document.addEventListener('click', (e) => {
    const dropdown = document.getElementById('secondary-slash-dropdown');
    const wrap = document.getElementById('secondary-input-wrap');
    if (dropdown && wrap && !wrap.contains(e.target)) {
      closeSecondarySlash(dropdown);
    }
  });

  // Attachment button — real file attachment, routed to the secondary window's own queue
  const secAttachBtn = document.getElementById('secondary-attach-btn');
  if (secAttachBtn) {
    secAttachBtn.addEventListener('click', () => {
      const fileInput = document.createElement('input');
      fileInput.type = 'file';
      fileInput.multiple = true;
      fileInput.style.display = 'none';
      document.body.appendChild(fileInput);
      fileInput.onchange = (e) => {
        const files = Array.from(e.target.files);
        const target = {
          attPreviewEl: document.getElementById('secondary-attachment-preview'),
          attachments: state._secPendingAttachments,
        };
        files.forEach(f => addFileAttachment(f, updateSecondarySendBtn, target));
        fileInput.remove();
      };
      fileInput.click();
    });
  }

  // Drag & drop / paste attachments into the secondary input — parity with the primary window
  const secInput = document.getElementById('secondary-input');
  const secInputWrap = document.getElementById('secondary-input-wrap');
  const secTarget = () => ({
    attPreviewEl: document.getElementById('secondary-attachment-preview'),
    attachments: state._secPendingAttachments,
  });
  if (secInputWrap) {
    secInputWrap.addEventListener('dragover', (e) => { e.preventDefault(); });
    secInputWrap.addEventListener('drop', (e) => {
      e.preventDefault();
      const files = Array.from(e.dataTransfer?.files || []);
      files.forEach(f => addFileAttachment(f, updateSecondarySendBtn, secTarget()));
    });
  }
  if (secInput) {
    secInput.addEventListener('paste', (e) => {
      const items = e.clipboardData?.items || [];
      for (const it of items) {
        if (it.kind === 'file') {
          const f = it.getAsFile();
          if (f) addFileAttachment(f, updateSecondarySendBtn, secTarget());
        }
      }
    });
  }

  // Voice button — push-to-talk with overlay + interim text, mirroring the primary window.
  // The primary window's voice logic lives in an input.js closure we can't reach, so this
  // is an equivalent standalone implementation scoped to the secondary DOM nodes.
  const secVoiceBtn = document.getElementById('secondary-voice-btn');
  const secVoiceOverlay = document.getElementById('secondary-voice-overlay');
  const secVoiceText = document.getElementById('secondary-voice-text');
  if (secVoiceBtn) {
    let voiceActive = false;
    let voiceFinal = '';
    let voiceBefore = '';
    let voiceAfter = '';

    function createSecRecognition() {
      const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
      const rec = new SR();
      rec.lang = getLocale();
      rec.continuous = true;
      rec.interimResults = true;
      rec.maxAlternatives = 3;

      rec.onresult = (ev) => {
        let interimText = '';
        for (let i = ev.resultIndex; i < ev.results.length; i++) {
          const result = ev.results[i];
          let bestIdx = 0;
          let bestConf = result[0].confidence || 0;
          for (let j = 1; j < result.length; j++) {
            const c = result[j].confidence || 0;
            if (c > bestConf) { bestConf = c; bestIdx = j; }
          }
          const tr = result[bestIdx].transcript;
          if (result.isFinal) voiceFinal += tr;
          else interimText += tr;
        }
        const current = voiceFinal + interimText;
        if (secVoiceText) secVoiceText.textContent = current || t('voice.listening');

        const inp = document.getElementById('secondary-input');
        const cursorNow = (inp.selectionStart != null) ? inp.selectionStart : inp.value.length;
        const oldVoiceLen = Math.max(0, inp.value.length - (voiceBefore.length + voiceAfter.length));
        let rawCursor;
        if (cursorNow <= voiceBefore.length) rawCursor = cursorNow;
        else if (cursorNow >= voiceBefore.length + oldVoiceLen) rawCursor = cursorNow - oldVoiceLen;
        else rawCursor = voiceBefore.length;
        const rawText = inp.value.substring(0, voiceBefore.length)
          + inp.value.substring(voiceBefore.length + oldVoiceLen);
        voiceBefore = rawText.substring(0, rawCursor);
        voiceAfter = rawText.substring(rawCursor);
        const sep = voiceBefore && !voiceBefore.endsWith(' ') ? ' ' : '';
        inp.value = voiceBefore + sep + current + voiceAfter;
        const cursorEnd = voiceBefore.length + (sep ? 1 : 0) + current.length;
        inp.setSelectionRange(cursorEnd, cursorEnd);
      };

      rec.onerror = (ev) => {
        if (ev.error === 'no-speech' || ev.error === 'aborted') return;
        if (secVoiceText) secVoiceText.textContent = t('voice.error', { error: ev.error });
        setTimeout(() => { if (voiceActive) stopSecVoice(); }, 1500);
      };

      rec.onend = () => {
        if (voiceActive) stopSecVoice();
        else {
          if (secVoiceOverlay) secVoiceOverlay.classList.remove('on');
          secVoiceBtn.classList.remove('recording');
        }
      };
      return rec;
    }

    function startSecVoice() {
      if (!('webkitSpeechRecognition' in window) && !('SpeechRecognition' in window)) {
        alert(t('input.voiceError'));
        return;
      }
      voiceActive = true;
      const inp = document.getElementById('secondary-input');
      const pos = (inp.selectionStart != null) ? inp.selectionStart : inp.value.length;
      voiceBefore = inp.value.substring(0, pos);
      voiceAfter = inp.value.substring(pos);
      voiceFinal = '';
      state._secRecognition = createSecRecognition();
      try { state._secRecognition.start(); } catch (_) {}
      if (secVoiceOverlay) secVoiceOverlay.classList.add('on');
      if (secVoiceText) secVoiceText.textContent = t('voice.listening');
      secVoiceBtn.classList.add('recording');
    }

    function stopSecVoice() {
      voiceActive = false;
      if (state._secRecognition) {
        try { state._secRecognition.stop(); } catch (_) {}
        state._secRecognition = null;
      }
      if (secVoiceOverlay) secVoiceOverlay.classList.remove('on');
      secVoiceBtn.classList.remove('recording');
      const inp = document.getElementById('secondary-input');
      if (inp && inp.value.trim()) inp.focus();
    }

    // Push-to-talk: press-and-hold to record, release to stop.
    const onVoiceStart = (e) => { if (e.type === 'touchstart') e.preventDefault(); startSecVoice(); };
    const onVoiceEnd = () => { if (voiceActive) stopSecVoice(); };
    secVoiceBtn.addEventListener('mousedown', onVoiceStart);
    secVoiceBtn.addEventListener('touchstart', onVoiceStart, { passive: false });
    secVoiceBtn.addEventListener('mouseup', onVoiceEnd);
    secVoiceBtn.addEventListener('mouseleave', onVoiceEnd);
    secVoiceBtn.addEventListener('touchend', onVoiceEnd);
    secVoiceBtn.addEventListener('touchcancel', onVoiceEnd);
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && voiceActive) { e.preventDefault(); stopSecVoice(); }
    });
  }
}
