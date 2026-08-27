// input.js — Input handling module for Nebflow
// All send logic, keyboard/input events, slash commands, attachments, drag/drop, voice.

import state, { LS_HISTORY_KEY } from './state.js';
import { key } from './branding.js';
import { activeView, setActiveView, chatViews, findViewBySessionId } from './chatView.js';
import { sendWs } from './ws.js';
import { renderUserBubble, renderSystemBubble, setBusy, renderAttachmentPreview, renderAskBubble, renderSkillBubble, cancelToolStreamRAF, refreshSendButtonState } from './chat.js';
import { renderMarkdownWithMath, escapeHtml, smartScroll } from './utils.js';
import { saveMsg } from './persistence.js';
import { saveInputDraft } from './sidebar.js';
import { renderTaskList } from './taskList.js';
import { t } from './i18n.js';
import { getLocale } from './i18n.js';
import { renderQueueBar } from './chatQueue.js';
import { makeReference } from './reference.js';
import { startDictation, stopDictation, isModelReady } from './voiceEngine.js';
import { notifyVoiceState } from './micOrb.js';
import { showToast } from './modal.js';

// ---------- Large text auto-attachment (paste detection) ----------
const LARGE_TEXT_THRESHOLD = 1000;
// Safety cap: a paste larger than this falls back to inserting the text inline
// (plain user-message content) instead of converting to an attachment. Above
// ~2MB the base64 payload (×1.35) + JSON.stringify of a single multi-MB string
// risks blowing the WS send path; the backend has no size guard either, so the
// cap lives on the producer side where the user can still edit/retry.
const LARGE_TEXT_MAX_CHARS = 2 * 1024 * 1024;

/** Show a transient banner at the top of the viewport. */
function showAttachmentBanner(message) {
  const banner = document.createElement('div');
  banner.className = 'attachment-banner';
  banner.textContent = message;
  banner.style.cssText = 'position:fixed;top:50px;left:50%;transform:translateX(-50%);background:var(--color-surface,rgba(20,25,35,0.9));color:var(--color-text);padding:8px 16px;border-radius:8px;z-index:1000;font-size:13px;box-shadow:0 2px 12px rgba(0,0,0,0.15);border:1px solid var(--glass-border);transition:opacity 0.3s;';
  document.body.appendChild(banner);
  setTimeout(() => { banner.style.opacity = '0'; }, 2700);
  setTimeout(() => banner.remove(), 3000);
}

// ---------- Slash Commands ----------
const slashCommands = {
  '/ask': {
    desc: () => t('slash.ask'),
    run: () => {
      enterAskMode();
    }
  },
  '/onboarding': {
    desc: () => t('slash.onboarding'),
    run: () => {
      // Replay the fixed chat-native onboarding greeting (same as first run).
      import('./onboarding.js').then(m => m.replayOnboarding()).catch(() => {});
    }
  }
};

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
        _source: skill.source,
        _skillName: skill.name,
        desc: () => skill.description || t('slash.skillDefault'),
        whenToUse: skill.whenToUse || '',
        argumentHint: skill.argumentHint || '',
        run: () => enterSkillMode(skill.name, skill.description, skill.argumentHint, skill.source)
      };
    }
  });
}

// ---------- Slash Command Handler ----------
export function handleSlash(text) {
  const cmd = text.trim().split(/\s/)[0];
  if (slashCommands[cmd] && slashCommands[cmd].run) {
    slashCommands[cmd].run(text);
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
    .map(([cmd, info]) => ({ cmd, desc: typeof info.desc === 'function' ? info.desc() : info.desc, whenToUse: info.whenToUse || '', isSkill: !!info._skill, source: info._source || '', skillName: info._skillName || '' }));
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
    const deleteHtml = (item.isSkill && item.source === 'user')
      ? '<span class="slash-delete" title="' + escapeHtml(t('slash.deleteSkill')) + '" data-skill="' + escapeHtml(item.skillName) + '"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg></span>'
      : '';
    div.innerHTML = '<div style="display:flex;align-items:center"><span class="slash-cmd">' + escapeHtml(item.cmd) + '</span>' + badge + '</div><span class="slash-desc">' + escapeHtml(item.desc) + '</span>' + whenToUseHtml + deleteHtml;
    div.onclick = () => { pickSlashCommand(i); };
    div.onmouseenter = () => { setSlashHighlight(i); };
    if (deleteHtml) {
      const delBtn = div.querySelector('.slash-delete');
      if (delBtn) {
        delBtn.onclick = (e) => {
          e.stopPropagation();
          handleDeleteSkill(item.skillName);
        };
      }
    }
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
  activeView.dom.input.style.height = 'auto';
  closeSlashDropdown();
  activeView.dom.input.focus();
  if (slashCommands[cmd] && slashCommands[cmd].run) slashCommands[cmd].run();
}

/** Delete a user-level skill after confirmation. */
function handleDeleteSkill(skillName) {
  const msg = t('slash.confirmDelete').replace('{skill}', skillName);
  window.__showConfirm?.('Delete Skill', msg, () => {
    sendWs({ type: 'deleteSkill', name: skillName });
    closeSlashDropdown();
  });
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

// ---------- Plan Mode ----------
export function enterPlanMode() {
  if (activeView.stream.planMode) return;
  activeView.stream.planMode = true;
  updateInputIndicator();
  activeView.dom.input.placeholder = 'Describe the task to plan...';
  activeView.dom.input.focus();
}

export function cancelPlanMode() {
  if (!activeView.stream.planMode) return;
  activeView.stream.planMode = false;
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.placeholder');
}

function updateAskIndicator() {
  updateInputIndicator();
}

function updateInputIndicator() {
  const askEl = document.getElementById('ask-indicator');
  const skillEl = document.getElementById('skill-indicator');
  const skillLabel = document.getElementById('skill-indicator-label');
  const compactEl = document.getElementById('compact-indicator');
  const planEl = document.getElementById('plan-indicator');
  const input = activeView.dom.input;
  // Plan/Ask/Skill/Compact mode — all mutually exclusive
  if (activeView.stream.planMode) {
    if (planEl) planEl.classList.add('show');
    if (askEl) askEl.classList.remove('show');
    if (skillEl) skillEl.classList.remove('show');
    if (compactEl) compactEl.classList.remove('show');
    input.style.paddingLeft = '';
    if (planEl) {
      const w = planEl.offsetWidth + 12;
      input.style.paddingLeft = Math.max(w, 48) + 'px';
    }
  } else if (activeView.stream.askMode) {
    if (planEl) planEl.classList.remove('show');
    if (askEl) askEl.classList.add('show');
    if (skillEl) skillEl.classList.remove('show');
    if (compactEl) compactEl.classList.remove('show');
    input.style.paddingLeft = '';
    if (askEl) {
      const w = askEl.offsetWidth + 12;
      input.style.paddingLeft = Math.max(w, 48) + 'px';
    }
  } else if (activeView.skillMode) {
    if (planEl) planEl.classList.remove('show');
    if (askEl) askEl.classList.remove('show');
    if (skillEl) {
      if (skillLabel) skillLabel.textContent = activeView.skillModeSource === 'flow' ? 'FLOW' : (activeView.skillModeName || 'SKILL');
      skillEl.classList.add('show');
      const w = skillEl.offsetWidth + 12;
      input.style.paddingLeft = Math.max(w, 56) + 'px';
    } else {
      input.style.paddingLeft = '';
    }
    if (compactEl) compactEl.classList.remove('show');
  } else if (activeView.compactMode) {
    if (planEl) planEl.classList.remove('show');
    if (askEl) askEl.classList.remove('show');
    if (skillEl) skillEl.classList.remove('show');
    if (compactEl) compactEl.classList.add('show');
    input.style.paddingLeft = '';
    const w = compactEl.offsetWidth + 12;
    input.style.paddingLeft = Math.max(w, 48) + 'px';
  } else {
    if (planEl) planEl.classList.remove('show');
    if (askEl) askEl.classList.remove('show');
    if (skillEl) skillEl.classList.remove('show');
    if (compactEl) compactEl.classList.remove('show');
    input.style.paddingLeft = '';
  }
}

// ---------- Skill Mode ----------
export function enterSkillMode(skillName, description, argumentHint, source) {
  if (activeView.skillMode) {
    // Already in skill mode — if it's a different skill, switch; otherwise do nothing
    if (activeView.skillModeName === skillName) return;
    cancelSkillMode();
  }
  // Cancel ask mode if active
  if (activeView.stream.askMode) cancelAskMode();
  activeView.skillMode = true;
  activeView.skillModeName = skillName;
  activeView.skillModeSource = source || '';
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
  activeView.skillModeSource = '';
  activeView.skillModeDesc = '';
  activeView.skillModeArgHint = '';
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.placeholder');
}

// ---------- Compact Mode ----------
export function enterCompactMode() {
  if (activeView.compactMode) return;
  // Cancel other modes if active
  if (activeView.stream.askMode) cancelAskMode();
  if (activeView.skillMode) cancelSkillMode();
  if (activeView.stream.planMode) cancelPlanMode();
  activeView.compactMode = true;
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.compactPlaceholder');
  activeView.dom.input.focus();
}

export function cancelCompactMode() {
  if (!activeView.compactMode) return;
  activeView.compactMode = false;
  updateInputIndicator();
  activeView.dom.input.placeholder = t('input.placeholder');
}

// Restore skill/ask mode from per-session draft data.
// Unlike enterSkillMode/enterAskMode, this does NOT focus the input.
export function applyInputModes(skillData, askActive) {
  activeView.skillMode = false;
  activeView.skillModeName = '';
  activeView.skillModeSource = '';
  activeView.skillModeDesc = '';
  activeView.skillModeArgHint = '';
  activeView.stream.askMode = false;

  if (skillData) {
    activeView.skillMode = true;
    activeView.skillModeName = skillData.name || '';
    activeView.skillModeSource = skillData.source || '';
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

/** Show an inline error in the attachment preview area (no alert popup). */
function showAttError(msg, target) {
  const attPreview = (target && target.attPreviewEl) || (activeView && activeView.dom && activeView.dom.attPreview);
  if (!attPreview) { console.warn(msg); return; }
  const err = document.createElement('div');
  err.className = 'att-error';
  err.textContent = msg;
  attPreview.appendChild(err);
  setTimeout(() => { err.classList.add('att-error-fade'); setTimeout(() => err.remove(), 300); }, 2500);
}

/** Convert ArrayBuffer to base64 in chunks (avoids reading file twice). */
function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  const chunkSize = 0x8000;
  let binary = '';
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

// Track pending attachment operations to prevent send() race condition
const pendingAttCount = { value: 0 };

export async function addFileAttachment(file, callback, target) {
  // Capture the view at entry — activeView is a live module binding that ws.js
  // changes on every incoming message. Without capturing, the await points below
  // would read a stale/changed activeView, causing renderAttachmentPreview to
  // target the wrong DOM element (or crash on null).
  if (!target && activeView) {
    target = { attPreviewEl: activeView.dom.attPreview, attachments: activeView.pendingAttachments };
  }
  const attachments = (target && target.attachments) || (activeView && activeView.pendingAttachments);
  if (!attachments) { console.error('[input] addFileAttachment: no attachments array'); return; }

  // Increment once at entry — every exit path below decrements.
  pendingAttCount.value++;

  if (file.type.startsWith('image/')) {
    if (file.size > 10 * 1024 * 1024) {
      pendingAttCount.value--;
      showAttError('Image too large (max 10MB): ' + file.name, target);
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
      // Fallback: read once as ArrayBuffer, derive both base64 and preview
      try {
        const buffer = await file.arrayBuffer();
        const base64Data = arrayBufferToBase64(buffer);
        const mimeType = file.type || 'image/jpeg';
        const preview = 'data:' + mimeType + ';base64,' + base64Data;
        attachments.push({
          type: 'image', mimeType,
          data: base64Data, name: file.name, preview
        });
      } catch (e2) {
        console.warn('[input] image fallback read failed:', e2);
        showAttError('Failed to read image: ' + file.name, target);
        pendingAttCount.value--;
        return;
      }
    }
    renderAttachmentPreview(target);
    if (callback) callback();
    pendingAttCount.value--;
  } else {
    // Non-image: send metadata (name + size + hash) for filesystem resolution,
    // plus base64 data for small files (< 5MB) as a fallback when the file
    // can't be found on disk (e.g. inside ~/Library/Containers).
    try {
      const buffer = await file.arrayBuffer();
      let hash = '';
      try {
        const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        hash = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
      } catch (e) {
        console.warn('[input] SHA-256 computation failed:', e);
      }
      const isSmall = file.size < 5 * 1024 * 1024;
      const base64Data = isSmall ? arrayBufferToBase64(buffer) : '';
      attachments.push({
        type: 'text', mimeType: file.type || 'application/octet-stream',
        data: base64Data, name: file.name, hash, size: file.size
      });
      renderAttachmentPreview(target);
      if (callback) callback();
    } catch (e) {
      console.warn('[input] file read failed:', e);
      showAttError('Failed to read file: ' + file.name, target);
    }
    pendingAttCount.value--;
  }
}

// ---------- Send ----------
export function send() {
  // Capture the view at entry — activeView is a live module binding that ws.js
  // changes on every incoming message. Without capturing, setTimeout closures
  // (e.g. the 300ms isSending debounce) would clear the flag on the wrong view
  // when another session's streaming messages arrive during the window.
  const v = activeView;
  if (v.isSending) {
    console.warn('[send] blocked: already sending');
    return;
  }
  const input = v.dom.input;
  const text = input.value.trim();
  const isBusy = state.busySessionIds.has(v.sessionId) || state.compactingSessionIds.has(v.sessionId);
  // If in skill mode, send as skill activation
  if (v.skillMode) {
    const skillName = v.skillModeName;
    cancelSkillMode();
    if (!text) return;
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
    if (isBusy) {
      queueMessage(v, text, [], skillName);
      input.value = '';
      input.style.height = 'auto';
      saveInputDraft(v.sessionId);
      setTimeout(() => { v.isSending = false; }, 300);
      return;
    }
    v.isSending = true;
    if (v.sessionId) state.turnExpecting[v.sessionId] = true;
    sendWs({ type: 'skill', skillName, input: text, sessionId: v.sessionId });
    renderSkillBubble(skillName, text);
    saveMsg({type:'user', text, attachments: (v.pendingAttachments||[]).map(a=>({type:a.type,name:a.name,preview:a.preview}))});
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  // If in plan mode, send as plan command
  if (v.stream.planMode) {
    cancelPlanMode();
    if (!text || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
      v.isSending = false;
      return;
    }
    v.isSending = true;
    sendWs({type:'command', command:'plan', sessionId: v.sessionId, task: text});
    renderSystemBubble('Plan mode started — analyzing...');
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  // If in ask mode, send as ask question
  if (v.stream.askMode) {
    cancelAskMode();
    if (!text || isBusy || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
      v.isSending = false;
      return;
    }
    v.isSending = true;
    if (v.sessionId) state.turnExpecting[v.sessionId] = true;
    sendWs({ type: 'ask', question: text, sessionId: v.sessionId });
    state.sessionAskBuffers[v.sessionId] = { question: text, answer: '' };
    renderAskBubble(text);
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  // If in compact mode, send as compact command (empty input is OK — triggers default compact)
  if (v.compactMode) {
    cancelCompactMode();
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
      v.isSending = false;
      return;
    }
    if (isBusy) {
      queueMessage(v, text, [], null, 'compact');
      input.value = '';
      input.style.height = 'auto';
      saveInputDraft(v.sessionId);
      setTimeout(() => { v.isSending = false; }, 300);
      return;
    }
    v.isSending = true;
    sendWs({type:'command', command:'compact', sessionId: v.sessionId, instruction: text || undefined});
    renderSystemBubble(text
      ? t('slash.compactDone') + ' — ' + text
      : t('slash.compactDone'));
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  // Allow slash commands (except /ask <question> which sends to the agent)
  // even when the session is busy — they are UI/meta operations.
  if (text.startsWith('/') && !text.startsWith('/ask ')) {
    if (handleSlash(text)) {
      input.value = '';
      input.style.height = 'auto';
      saveInputDraft(v.sessionId);
      setTimeout(() => { v.isSending = false; }, 300);
      return;
    }
  }
  // Empty input — just ignore
  if (!text && v.pendingAttachments.length === 0) {
    return;
  }
  // Wait for pending attachment processing (image compression, file reading)
  // to prevent race condition where image is lost because send() runs before
  // addFileAttachment finishes pushing to pendingAttachments.
  if (pendingAttCount.value > 0) {
    setTimeout(() => send(), 200);
    return;
  }
  // LLM is busy — queue the message instead of blocking. Frozen sessions
  // EXEMPT: a frozen agent never runs a turn, so queueing would leave the
  // message parked forever — the whole point of freeze is that a user message
  // WAKES the agent (spec §3.2 input.js, F7). Fall through to the normal send
  // path below (direct WS frame, no queueMessage).
  if (isBusy && !state.frozenSessions.has(v.sessionId)) {
    queueMessage(v, text, v.pendingAttachments);
    input.value = '';
    input.style.height = 'auto';
    v.pendingAttachments = [];
    v.dom.attPreview.innerHTML = '';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    console.warn('[send] ws not open:', { ws: !!state.ws, readyState: state.ws?.readyState });
    return;
  }
  v.isSending = true;
  // Mark this session as expecting a turn (prevents stray thinking bubbles after done)
  if (v.sessionId) state.turnExpecting[v.sessionId] = true;
  // Intercept /ask <question> before normal slash handling
  if (text.startsWith('/ask ')) {
    const question = text.slice(5).trim();
    if (question) {
      sendWs({ type: 'ask', question, sessionId: v.sessionId });
      state.sessionAskBuffers[v.sessionId] = { question, answer: '' };
      renderAskBubble(question);
    }
    input.value = '';
    saveInputDraft(v.sessionId);
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  if (handleSlash(text)) {
    input.value = '';
    input.style.height = 'auto';
    saveInputDraft(v.sessionId);
    // Debounce: keep lock briefly to prevent accidental double-trigger of slash commands
    setTimeout(() => { v.isSending = false; }, 300);
    return;
  }
  renderUserBubble(text, v.pendingAttachments);
  saveMsg({type:'user', text, attachments: (v.pendingAttachments||[]).map(a => a.type === 'taskRef'
    ? { type: a.type, name: a.subject, taskId: a.taskId, sessionId: a.sessionId, subject: a.subject }
    : a.type === 'ref'
      ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display }
      : { type: a.type, name: a.name, preview: a.preview })});
  // Save to input history
  if (text && text !== '/clear') {
    state.inputHistory.push(text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e) {
      // Quota exceeded — trim history to 100 entries and retry once
      if (state.inputHistory.length > 100) {
        state.inputHistory = state.inputHistory.slice(-100);
        try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e2) {}
      }
      console.debug('[input] history save failed:', e);
    }
  }
  v.historyIndex = -1;
  v.historyDraft = '';
  try {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    // v2 §5.2/§6.1 + B (2026-08-20): taskRefs carry taskId/sessionId/subject —
    // 描述/产出不重复进载荷（agent 凭 taskId 定位任务，记忆里有上下文）；
    // 用户意见 = 本帧 content（§5.4，后端落 notes）。
    const taskRefs = (v.pendingAttachments || [])
      .filter(a => a.type === 'taskRef')
      .map(a => ({ taskId: a.taskId, sessionId: a.sessionId || v.sessionId, ...(a.subject ? { subject: a.subject } : {}) }));
    // Global Reference (2026-08-25 #303): carry unified refs on the wire. Backend's
    // processTaskReturns reads taskRefs via circe cursor downField(...).getOrElse(Nil)
    // — unknown fields (refs) are tolerated, so this is forward-compatible.
    const refs = (v.pendingAttachments || [])
      .filter(a => a.type === 'ref')
      .map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display }));
    sendWs({
      content: text,
      ...(taskRefs.length > 0 ? { taskRefs } : {}),
      ...(refs.length > 0 ? { refs } : {}),
      attachments: (v.pendingAttachments || [])
        .filter(a => a.type !== 'taskRef' && a.type !== 'ref')
        .map(a => ({
          mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
        })),
      clientMessageId,
      sessionId: v.sessionId,
      chatWidth: v.dom.chat?.clientWidth || 0
    });
  } catch (e) {
    console.error('WebSocket send failed:', e);
  }
  input.value = '';
  input.style.height = 'auto';
  v.pendingAttachments = [];
  v.dom.attPreview.innerHTML = '';
  // Immediately clear the draft for this session so it is not restored after refresh
  saveInputDraft(v.sessionId);
  setBusy(v.sessionId);
  // Start turn timer
  state.turnStartTimes[v.sessionId] = Date.now();
  // Release send lock after a short debounce to prevent double-click / rapid Enter
  setTimeout(() => { v.isSending = false; }, 300);
  // Clean up any orphaned thinking placeholders from previous incomplete streams
  if (window.__stopThinkingTimer) window.__stopThinkingTimer();
  v.dom.chat.querySelectorAll('.thinking-placeholder').forEach(el => {
    const row = el.closest('.row');
    if (row) row.remove();
  });
  v.stream.currentAiBubble = null;
  v.stream.aiText = '';
  v.stream.currentThinkingBubble = null;
  v.stream.thinkingText = '';
  // Safety timeout: backend sends 'timeout' event, but this is a last-resort fallback
  // in case the backend event never arrives. Uses streamTimeoutMs from server config (+ 30s buffer).
  const sid = v.sessionId;
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
        const timeoutView = findViewBySessionId(sid);
        if (timeoutView) { setActiveView(timeoutView); renderTimeoutNotice(); }
        clearBusy(sid);
        clearStatus();
      });
    }
  }, state.streamTimeoutMs + 30000);
}

// ---------- Input Queue (messages typed while LLM is busy) ----------

let queueCounter = 0;
const LS_QUEUE_KEY = key('message_queue');

/** Persist message queue to localStorage so it survives browser refresh. */
function persistQueue() {
  try {
    // Strip non-serializable fields (preview images are large; keep metadata only)
    const serializable = {};
    for (const [sid, items] of Object.entries(state.messageQueue)) {
      if (!items || items.length === 0) continue;
      serializable[sid] = items.map(it => ({
        id: it.id,
        text: it.text,
        skillName: it.skillName || null,
        mode: it.mode || null,
        // v2 B15: taskRef chips must survive refresh — keep taskId/sessionId
        // (+ subject as the display name); #303: ref blocks keep their full
        // Reference shape; file/image keep type+name only — EXCEPT pasted-text
        // attachments, whose base64 data IS the only copy of the content (the
        // file never existed on disk). Keep small text payloads (<=400KB
        // base64) so drain-after-refresh still delivers the content; images
        // stay stripped (too large for localStorage).
        attachments: (it.attachments || []).map(a => a.type === 'taskRef'
          ? { type: 'taskRef', taskId: a.taskId, sessionId: a.sessionId, subject: a.subject, name: a.subject }
          : a.type === 'ref'
            ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display }
            : a.type === 'text' && typeof a.data === 'string' && a.data.length > 0 && a.data.length <= 400000
              ? { type: a.type, mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0 }
              : { type: a.type, name: a.name })
      }));
    }
    localStorage.setItem(LS_QUEUE_KEY, JSON.stringify(serializable));
  } catch (e) { /* storage full or unavailable — non-critical */ }
}

/** Restore message queue from localStorage on page load. */
export function restoreQueue() {
  try {
    const raw = localStorage.getItem(LS_QUEUE_KEY);
    if (!raw) return;
    const data = JSON.parse(raw);
    for (const [sid, items] of Object.entries(data)) {
      if (Array.isArray(items) && items.length > 0) {
        state.messageQueue[sid] = items;
        // Restore queueCounter to avoid ID collisions
        for (const it of items) {
          if (it.id > queueCounter) queueCounter = it.id;
        }
        // Re-render queue bar for the restored session
        refreshQueue(sid);
      }
    }
  } catch (e) { /* corrupt data — ignore */ }
}

/** Helper: re-render the queue bar for a session with standard handlers. */
function refreshQueue(sessionId) {
  renderQueueBar(sessionId, {
    onImmediate: (item) => sendImmediate(sessionId, item),
    onRecall: (item) => recallQueuedItem(sessionId, item),
    onRemove: (item) => removeQueuedItem(sessionId, item)
  });
}

// Re-render queue bar when user switches to a different session
window.addEventListener('queuebar-refresh', (e) => {
  refreshQueue(e.detail.sessionId);
});

function queueMessage(view, text, attachments, skillName, mode) {
  const sid = view.sessionId;
  const item = {
    id: ++queueCounter,
    text,
    attachments: attachments.map(a => ({ ...a })),
    skillName,
    mode
  };
  if (!state.messageQueue[sid]) state.messageQueue[sid] = [];
  state.messageQueue[sid].push(item);
  refreshQueue(sid);
  persistQueue();
}

function sendImmediate(sessionId, item) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  const taskRefs = (item.attachments || []).filter(a => a.type === 'taskRef');
  const refs = (item.attachments || []).filter(a => a.type === 'ref');
  // File/image attachments (base64 data payload) ride the same default
  // user-message frame. Without this branch, an attachment-only queued item
  // fell into the immediateInput branch, whose backend handler reads only
  // `content` — with content === '' the frame hit handleUserText's empty
  // guard and the agent never saw it (silent non-response).
  const fileAtts = (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref');
  if (item.mode === 'compact') {
    sendWs({ type: 'command', command: 'compact', sessionId, instruction: item.text || undefined });
  } else if (item.skillName) {
    sendWs({ type: 'skill', skillName: item.skillName, input: item.text, sessionId });
  } else if (taskRefs.length > 0 || refs.length > 0 || fileAtts.length > 0) {
    // v2 + #303: a return/reference message must ride the default user-message
    // branch — the backend parses taskRefs/refs only there (WebSocketRoutes
    // §6.2); immediateInput goes through handleUserText and would drop them.
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    sendWs({
      content: item.text,
      ...(taskRefs.length > 0 ? { taskRefs: taskRefs.map(a => ({ taskId: a.taskId, sessionId: a.sessionId || sessionId })) } : {}),
      ...(refs.length > 0 ? { refs: refs.map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display })) } : {}),
      attachments: (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref').map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
      })),
      clientMessageId,
      sessionId,
      chatWidth: activeView?.dom?.chat?.clientWidth || 0
    });
  } else {
    sendWs({ type: 'immediateInput', content: item.text, sessionId });
  }
  // Render in chat
  if (activeView && activeView.sessionId === sessionId) {
    if (item.mode === 'compact') {
      renderSystemBubble(item.text ? t('slash.compactDone') + ' — ' + item.text : t('slash.compactDone'));
    } else if (item.skillName) {
      renderSkillBubble(item.skillName, item.text);
    } else {
      renderUserBubble(item.text, item.attachments);
    }
  }
  saveMsg({ type: 'user', text: item.text, attachments: (item.attachments || []).map(a => a.type === 'taskRef'
    ? { type: a.type, name: a.subject, taskId: a.taskId, sessionId: a.sessionId, subject: a.subject }
    : a.type === 'ref'
      ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display }
      : { type: a.type, name: a.name, preview: a.preview }) }, sessionId);
  // Save to input history (same as normal send and drainMessageQueue)
  if (item.text) {
    state.inputHistory.push(item.text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e) {}
  }
  // Remove from queue and refresh bar
  const q = state.messageQueue[sessionId];
  if (q) {
    const idx = q.indexOf(item);
    if (idx >= 0) q.splice(idx, 1);
  }
  refreshQueue(sessionId);
  persistQueue();
}

function removeQueuedItem(sessionId, item) {
  const q = state.messageQueue[sessionId];
  if (!q) return;
  const idx = q.indexOf(item);
  if (idx >= 0) q.splice(idx, 1);
  refreshQueue(sessionId);
  persistQueue();
}

/** Recall a queued item back to the input box for editing, removing it from the queue. */
function recallQueuedItem(sessionId, item) {
  const q = state.messageQueue[sessionId];
  if (!q) return;
  const idx = q.indexOf(item);
  if (idx >= 0) q.splice(idx, 1);
  refreshQueue(sessionId);
  persistQueue();

  // Put text back into the input box
  const view = findViewBySessionId(sessionId);
  if (view && view.dom.input) {
    const text = item.mode === 'compact'
      ? `/compact ${item.text}`.trim()
      : item.skillName ? `/${item.skillName} ${item.text}` : item.text;
    view.dom.input.value = text;
    view.dom.input.style.height = 'auto';
    view.dom.input.focus();
    // Place cursor at end
    const len = view.dom.input.value.length;
    view.dom.input.setSelectionRange(len, len);
    saveInputDraft(sessionId);
  }
}

/** Called on 'done' event — send first queued message as normal UserInput.
 *  Works even when the session isn't currently displayed (view is null):
 *  DOM operations are skipped, but the WS message is still sent. */
export function drainMessageQueue(sessionId) {
  const q = state.messageQueue[sessionId];
  if (!q || q.length === 0) return false;
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return false;
  const item = q[0];
  const view = findViewBySessionId(sessionId);

  // Remove from queue
  q.shift();
  refreshQueue(sessionId);
  persistQueue();

  // Render in chat (only if this session is the active view)
  if (view && activeView && activeView.sessionId === sessionId) {
    if (item.mode === 'compact') {
      renderSystemBubble(item.text ? t('slash.compactDone') + ' — ' + item.text : t('slash.compactDone'));
    } else if (item.skillName) {
      renderSkillBubble(item.skillName, item.text);
    } else {
      renderUserBubble(item.text, item.attachments);
    }
  }

  // Save to history
  if (item.text) {
    state.inputHistory.push(item.text);
    if (state.inputHistory.length > 200) state.inputHistory = state.inputHistory.slice(-200);
    try { localStorage.setItem(LS_HISTORY_KEY, JSON.stringify(state.inputHistory)); } catch(e) {}
  }
  saveMsg({ type: 'user', text: item.text, attachments: (item.attachments || []).map(a => a.type === 'taskRef'
    ? { type: a.type, name: a.subject, taskId: a.taskId, sessionId: a.sessionId, subject: a.subject }
    : a.type === 'ref'
      ? { type: 'ref', refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display }
      : { type: a.type, name: a.name, preview: a.preview }) }, sessionId);

  // Send as normal UserInput
  if (sessionId) state.turnExpecting[sessionId] = true;
  if (view) {
    view.isSending = true;
    view.historyIndex = -1;
    view.historyDraft = '';
  }

  if (item.mode === 'compact') {
    sendWs({ type: 'command', command: 'compact', sessionId, instruction: item.text || undefined });
  } else if (item.skillName) {
    sendWs({ type: 'skill', skillName: item.skillName, input: item.text, sessionId });
  } else {
    const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    // v2 B15: queued return messages keep their taskRefs when drained.
    // #303: unified refs ride the same default user-message frame (backend
    // parses refs via circe cursor — tolerated for forward-compat).
    const taskRefs = (item.attachments || []).filter(a => a.type === 'taskRef');
    const refs = (item.attachments || []).filter(a => a.type === 'ref');
    sendWs({
      content: item.text,
      ...(taskRefs.length > 0 ? { taskRefs: taskRefs.map(a => ({ taskId: a.taskId, sessionId: a.sessionId || sessionId })) } : {}),
      ...(refs.length > 0 ? { refs: refs.map(a => ({ refType: a.refType, id: a.id, source: a.source, anchor: a.anchor, meta: a.meta, display: a.display })) } : {}),
      attachments: (item.attachments || []).filter(a => a.type !== 'taskRef' && a.type !== 'ref').map(a => ({
        mimeType: a.mimeType, data: a.data, name: a.name, hash: a.hash || '', size: a.size || 0
      })),
      clientMessageId,
      sessionId,
      chatWidth: view?.dom?.chat?.clientWidth || 0
    });
  }

  setBusy(sessionId);
  state.turnStartTimes[sessionId] = Date.now();

  // Safety timeout
  if (state.sessionBusyTimeouts[sessionId]) {
    clearTimeout(state.sessionBusyTimeouts[sessionId]);
    delete state.sessionBusyTimeouts[sessionId];
  }
  state.sessionBusyTimeouts[sessionId] = setTimeout(() => {
    if (state.busySessionIds.has(sessionId)) {
      sendWs({ type: 'interrupt', sessionId });
      import('./chat.js').then(({ renderTimeoutNotice, clearBusy, clearStatus }) => {
        const timeoutView = findViewBySessionId(sessionId);
        if (timeoutView) { setActiveView(timeoutView); renderTimeoutNotice(); }
        clearBusy(sessionId);
        clearStatus();
      });
    }
  }, state.streamTimeoutMs + 30000);

  // Clean up thinking placeholders (only for displayed sessions)
  if (view) {
    if (window.__stopThinkingTimer) window.__stopThinkingTimer();
    view.dom.chat.querySelectorAll('.thinking-placeholder').forEach(el => {
      const row = el.closest('.row');
      if (row) row.remove();
    });
    view.stream.currentAiBubble = null;
    view.stream.aiText = '';
    view.stream.currentThinkingBubble = null;
    view.stream.thinkingText = '';
    setTimeout(() => { view.isSending = false; }, 300);
  }
  return true;
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

  // Save to persistence (plain user message — plugin-card interaction, not a
  // tool injection, so no `injected` marker)
  saveMsg({ type: 'user', text: trimmed });

  // Send via WebSocket (same format as normal send)
  const clientMessageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  sendWs({
    content: trimmed,
    attachments: [],
    clientMessageId,
    sessionId,
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

// Close the active view's slash dropdown on outside clicks. Bound ONCE at
// module scope: popup ChatViews rebuild their input DOM on every open
// (openStepPopup re-runs initInput each time), so a per-initInput document
// binding would both accumulate listeners and reference stale detached
// elements. Resolution goes through activeView — only one dropdown can be
// open at a time (updateSlashDropdown renders into activeView.dom only).
document.addEventListener('click', (e) => {
  const v = activeView;
  if (!v || !v.dom || !v.dom.input || !v.dom.slashDropdown) return;
  if (!v.dom.input.contains(e.target) && !v.dom.slashDropdown.contains(e.target)) {
    v.dom.slashDropdown.classList.remove('on');
    v.slashMatches = [];
    v.slashSelectedIndex = -1;
  }
});

export function initInput(view) {
  const input = view.dom.input;
  const sendBtn = view.dom.sendBtn;
  const stopBtn = view.dom.stopBtn;
  const attachBtn = view.dom.attachBtn;
  const voiceBtn = view.dom.voiceBtn;
  const voiceOverlay = view.dom.voiceOverlay;
  const voiceText = view.dom.voiceText;

  const slashDropdown = view.dom.slashDropdown;

  // Sync the send button's connection state on init (grey until connected).
  refreshSendButtonState();

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

  // Paste handler — image paste + large text detection
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
    if (files.length === 0) {
      // Large text paste → auto-convert to file attachment via existing mechanism
      const pastedText = e.clipboardData.getData('text/plain') || '';
      if (pastedText.length > LARGE_TEXT_THRESHOLD) {
        if (pastedText.length > LARGE_TEXT_MAX_CHARS) {
          // Above the cap: keep inline (browser default paste) + toast, never
          // silently drop the text.
          showToast(t('input.pasteTooLarge'));
          return;
        }
        e.preventDefault();
        e.stopPropagation();
        const blob = new Blob([pastedText], { type: 'text/plain' });
        const file = new File([blob], `pasted-text-${Date.now()}.txt`, { type: 'text/plain' });
        addFileAttachment(file);
        showAttachmentBanner(`大段文本（${pastedText.length} 字符）已转为文件附件`);
        return;
      }
      return; // normal text paste, let browser handle it
    }
    e.preventDefault();
    e.stopPropagation(); // prevent document-level paste from double-processing
    files.forEach(file => addFileAttachment(file));
  });

  // Keydown handler — slash autocomplete navigation, input history navigation, Enter-to-send
  input.onkeydown = (e) => {
    setActiveView(view);
    // Escape cancels ask/skill/compact mode
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
      if (view.compactMode) {
        e.preventDefault();
        cancelCompactMode();
        return;
      }
    }
    // Backspace/Delete on empty input cancels ask/skill/compact mode (like removing a tag)
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
      if (view.compactMode) {
        e.preventDefault();
        cancelCompactMode();
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

  // Attach button — hidden file input trigger (supports multiple files)
  attachBtn.onclick = () => {
    setActiveView(view);
    const f = document.createElement('input');
    f.type = 'file';
    f.multiple = true;
    f.style.display = 'none';
    document.body.appendChild(f);
    f.onchange = (e) => {
      const files = Array.from(e.target.files);
      files.forEach(file => addFileAttachment(file));
      f.remove();
    };
    f.click();
  };

  // Paste image support (Cmd/Ctrl+V) — primary only, unchanged.
  // Drag & drop moved to initGlobalFileDrop() (document-level delegation
  // covering primary + popup input bars — see #303).
  if (view.id === 'primary') {
    document.addEventListener('paste', (e) => {
      const items = e.clipboardData && e.clipboardData.items;
      if (!items) return;
      for (const item of items) {
        if (item.type && item.type.startsWith('image/')) {
          const f = item.getAsFile();
          if (f) {
            e.preventDefault();
            addFileAttachment(f);
          }
        }
      }
    });
  }

  // Voice dictation — uses browser Web Speech API (free, no API key needed)
  // Push-and-hold the mic button to start; release to stop.
  // Interim text streams directly into the input box — no overlay.
  let voiceActive = false;
  let voiceAnchor = 0;       // position where current voice segment starts
  let voiceInterimLen = 0;   // length of interim text currently displayed

  // Shared callbacks for dictation mode.
  function makeVoiceCallbacks() {
    return {
      onInterim: (text) => {
        // Replace [voiceAnchor, voiceAnchor + voiceInterimLen) with new interim text
        const before = input.value.substring(0, voiceAnchor);
        const after = input.value.substring(voiceAnchor + voiceInterimLen);
        input.value = before + text + after;
        voiceInterimLen = text.length;
        input.focus();
        input.setSelectionRange(voiceAnchor + text.length, voiceAnchor + text.length);
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
      },
      onText: (text) => {
        // Replace interim with final text + trailing space
        const before = input.value.substring(0, voiceAnchor);
        const after = input.value.substring(voiceAnchor + voiceInterimLen);
        const insert = text + ' ';
        input.value = before + insert + after;
        voiceInterimLen = 0;
        voiceAnchor = before.length + insert.length;
        input.setSelectionRange(voiceAnchor, voiceAnchor);
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
      },
      onState: (state, data) => {
        updateVoiceUI(state, data);
      },
    };
  }

  // Update voice UI — the orb reflects the state via notifyVoiceState; the
  // legacy .recording class is kept for any external consumers (no visual
  // styling on the orb button itself).
  function updateVoiceUI(state, data) {
    notifyVoiceState(state);
    switch (state) {
      case 'listening':
      case 'speaking':
        voiceBtn.classList.add('recording');
        break;
      case 'error':
        voiceBtn.classList.remove('recording');
        // Voice errors must be user-visible, not console-only (#stt-hotfix:
        // a denied/busy mic previously produced zero on-screen feedback).
        // data is an already-classified, i18n'd message from voiceEngine.
        showToast(data, 'error');
        console.warn('[voice] Error:', data);
        break;
      case 'idle':
        voiceBtn.classList.remove('recording');
        break;
    }
  }

  async function startVoice() {
    voiceActive = true;
    voiceAnchor = input.selectionStart ?? input.value.length;
    voiceInterimLen = 0;
    // Add separator space if needed
    if (voiceAnchor > 0) {
      const charBefore = input.value[voiceAnchor - 1];
      if (charBefore && charBefore !== ' ' && charBefore !== '\n') {
        input.value = input.value.substring(0, voiceAnchor) + ' ' + input.value.substring(voiceAnchor);
        voiceAnchor++;
      }
    }
    voiceBtn.classList.add('recording');
    input.classList.add('voice-dictating');
    input.focus();
    try { localStorage.setItem(key('voice_used'), '1'); } catch {}
    await startDictation(makeVoiceCallbacks());
  }

  function stopVoice() {
    voiceActive = false;
    // Remove any remaining interim cursor
    if (voiceInterimLen > 0) {
      const before = input.value.substring(0, voiceAnchor);
      const after = input.value.substring(voiceAnchor + voiceInterimLen);
      input.value = before + after;
      voiceInterimLen = 0;
    }
    // Show the "processing" orb state while the captured audio transcribes
    // (spec §9 pure-front-end addition — voiceEngine emits no processing state
    // after stop; the follow-up onState('idle') from the engine clears it).
    notifyVoiceState('processing');
    stopDictation();
    voiceBtn.classList.remove('recording');
    input.classList.remove('voice-dictating');
    input.focus();
  }

  // Click-toggle voice (user ruling 2026-08-25 21:34: tap once to start, tap
  // again to stop — replaces the old push-and-hold). Also spec §3.3.
  function onVoiceToggle(e) {
    if (e) e.preventDefault();
    setActiveView(view);
    if (voiceActive) stopVoice();
    else startVoice();
  }
  voiceBtn.addEventListener('click', onVoiceToggle);

  // Escape key stops voice recording (only register once)
  if (view.id === 'primary') {
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && voiceActive) {
        e.preventDefault();
        stopVoice();
      }
    });
  }

  // Slash dropdown input listener
  input.addEventListener('input', () => { setActiveView(view); updateSlashDropdown(); });

  // Ask/skill indicator cancel buttons
  {
    const askCancel = document.getElementById('ask-indicator-cancel');
    if (askCancel) {
      askCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelAskMode();
        input.focus();
      });
    }
    const skillCancel = document.getElementById('skill-indicator-cancel');
    if (skillCancel) {
      skillCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelSkillMode();
        input.focus();
      });
    }
    const compactCancel = document.getElementById('compact-indicator-cancel');
    if (compactCancel) {
      compactCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelCompactMode();
        input.focus();
      });
    }
    const planCancel = document.getElementById('plan-indicator-cancel');
    if (planCancel) {
      planCancel.addEventListener('click', (e) => {
        e.stopPropagation();
        setActiveView(view);
        cancelPlanMode();
        input.focus();
      });
    }
  }
}

// ---------- Global drag & drop onto input bars (#303) ----------
// Event delegation on document: popup views recreate their input bar DOM on
// every open (per-element binding would go stale — see _inputBound), so all
// drag listeners live here and resolve the owning ChatView at event time.
// A file dropped onto ANY input bar (main or popup) attaches to THAT bar's
// session; drops elsewhere are ignored (no page navigation).
const INPUT_BAR_SELECTOR = '#input-bar, .fa-input-bar';
// #303 internal drag: explorer file rows carry this custom MIME in addition to
// (or instead of) native 'Files' — both count as attach payloads.
const INTERNAL_DRAG_MIME = 'application/x-nebflow-file';
// #303 global-reference: canvas tab rows carry this MIME to produce a Reference
// (document/file/html-element) on drop into an input bar.
const CANVAS_DRAG_MIME = 'application/x-nebflow-ref';

/**
 * #303/global-reference: append a unified Reference to a view's
 * pendingAttachments and re-render the attachment preview strip. Used by the
 * explorer/canvas entry points (right-click & drag). Returns true on success.
 * @param {Object} ref  Reference produced by makeReference()
 * @param {{attPreviewEl?:HTMLElement, attachments?:Array, focus?:boolean}} [target]
 */
export function appendRefToActiveView(ref, target) {
  const view = activeView || state.getActiveView?.();
  if (!view || !ref) return false;
  if (!Array.isArray(view.pendingAttachments)) view.pendingAttachments = [];
  view.pendingAttachments.push(ref);
  if (target && target.attPreviewEl) {
    renderAttachmentPreview({ attPreviewEl: target.attPreviewEl, attachments: view.pendingAttachments });
  } else if (view.dom && view.dom.attPreview) {
    renderAttachmentPreview({ attPreviewEl: view.dom.attPreview, attachments: view.pendingAttachments });
  }
  if (target?.focus !== false) view.dom?.input?.focus?.();
  return true;
}

export function initGlobalFileDrop() {
  let dragDepth = 0;    // child-element nesting depth inside the bar
  let activeBar = null; // currently highlighted input bar

  const hasAttach = (e) => {
    if (!e.dataTransfer) return false;
    const types = Array.from(e.dataTransfer.types || []);
    return types.includes('Files') || types.includes(INTERNAL_DRAG_MIME) || types.includes(CANVAS_DRAG_MIME);
  };
  const barOf = (e) =>
    e.target instanceof Element ? e.target.closest(INPUT_BAR_SELECTOR) : null;
  const clearHighlight = () => {
    if (activeBar) activeBar.classList.remove('drag-over');
    activeBar = null; dragDepth = 0;
  };

  document.addEventListener('dragenter', (e) => {
    if (!hasAttach(e)) return;
    const bar = barOf(e);
    if (!bar) return;
    if (bar !== activeBar) clearHighlight();
    activeBar = bar;
    dragDepth++;
    bar.classList.add('drag-over');
  });

  document.addEventListener('dragover', (e) => {
    if (!hasAttach(e)) return;     // native text drags pass through untouched
    e.preventDefault();            // block browser default "open dropped file"
    e.dataTransfer.dropEffect = 'copy';
  });

  // No hasFiles() check here: dataTransfer.types is unreadable during
  // dragleave in some engines (this was the old stuck-highlight bug).
  document.addEventListener('dragleave', () => {
    if (!activeBar) return;
    dragDepth--;
    if (dragDepth <= 0) clearHighlight();
  });

  document.addEventListener('drop', (e) => {
    if (!hasAttach(e)) return;
    e.preventDefault();            // never navigate to the dropped file
    const bar = barOf(e);
    clearHighlight();
    if (!bar) return;              // dropped outside any input bar — ignore
    const view = Object.values(chatViews)
      .find(v => v.mounted && v.dom && v.dom.inputBar === bar);
    if (!view || view.dom.input?.readOnly) return; // disabled popup guard
    setActiveView(view);
    const target = { attPreviewEl: view.dom.attPreview,
                     attachments: view.pendingAttachments };
    // #303 global-reference drag routing (v1.1):
    //   internal explorer file row (application/x-nebflow-file with path+rootPath)
    //     → Reference @path (not an attachment upload)
    //   canvas tab row (application/x-nebflow-ref) → Reference w/ current anchor
    //   anything else (OS/Finder files) → attachment upload (#303 regression)
    const internal = e.dataTransfer.getData(INTERNAL_DRAG_MIME);
    const canvasRef = e.dataTransfer.getData(CANVAS_DRAG_MIME);
    if (internal) {
      try {
        const { path, rootPath } = JSON.parse(internal);
        if (path) {
          const absPath = joinAbsPath(rootPath, path);
          const name = path.split('/').pop() || path;
          appendRefToActiveView(makeReference({
            refType: 'file',
            source: { kind: 'workspace', path: absPath, fileName: name, title: name },
          }), { focus: false });
        } else console.warn('[drop] internal drag payload missing path');
      } catch (err) {
        console.warn('[drop] bad internal drag payload:', err);
      }
    } else if (canvasRef) {
      try {
        const input = JSON.parse(canvasRef);
        if (input && input.refType) appendRefToActiveView(makeReference(input), { focus: false });
        else console.warn('[drop] canvas ref payload missing refType');
      } catch (err) {
        console.warn('[drop] bad canvas ref payload:', err);
      }
    } else {
      // External drag (desktop files) — unchanged.
      Array.from(e.dataTransfer.files || []).forEach(f => addFileAttachment(f, null, target));
    }
    view.dom.input?.focus();
  });
}

// ---------- #303 internal drag: explorer file → attachment pipeline ----------
// Explorer rows drop with {path, rootPath} (explorer-relative path + absolute
// root, the same coordinates readFile/listDir use). We re-read the file through
// the SAME channels the tree uses — /api/nf-file for media (binary never rides
// the WS), readFile WS for text — then feed a real File object into
// addFileAttachment, so preview chips / upload / send are byte-identical to the
// attach-button path.
function joinAbsPath(rootPath, path) {
  if (!rootPath) return path;
  return rootPath.endsWith('/') ? rootPath + path : rootPath + '/' + path;
}

async function addPathAttachment({ path, rootPath }, view, target) {
  const name = path.split('/').pop() || path;
  const absPath = joinAbsPath(rootPath, path);
  const token = localStorage.getItem(key('token')) || '';
  // Typed window alias — the one-shot readFile guard flag shared with explorer.js.
  const win = /** @type {Window & { __internalDragReadPath: string | null }} */ (/** @type {any} */ (window));

  // Phase 1: whitelisted media (images, pdf, mp4…) come back from nf-file.
  // Non-whitelisted extensions → 400 "File type not allowed" → phase 2.
  try {
    const resp = await fetch(`/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(token)}`);
    if (resp.ok) {
      const blob = await resp.blob();
      const file = new File([blob], name, { type: blob.type || 'application/octet-stream' });
      addFileAttachment(file, null, target);
      return;
    }
  } catch { /* offline/network — fall through to readFile, it surfaces the error */ }

  // Phase 2: text files via readFile WS. The answer is a fileContent frame that
  // explorer.js routes to us (window flag + 'internal-file-read' event) instead
  // of opening a Canvas tab. One-shot guard with a timeout so a lost response
  // never leaks the flag into a later normal file open.
  const readDone = new Promise((resolve) => {
    let settled = false;
    const finish = (detail) => { if (!settled) { settled = true; resolve(detail); } };
    const onRead = (e) => {
      clearTimeout(timer);
      window.removeEventListener('internal-file-read', onRead);
      finish(e.detail);
    };
    const timer = setTimeout(() => {
      window.removeEventListener('internal-file-read', onRead);
      if (win.__internalDragReadPath === path) win.__internalDragReadPath = null;
      finish({ error: 'timeout' });
    }, 8000);
    window.addEventListener('internal-file-read', onRead);
  });
  win.__internalDragReadPath = path;
  // sessionId follows explorer.js's own readFile semantics (state.activeSessionId,
  // not the drop target view's session — the file tree is session-agnostic).
  sendWs({ type: 'readFile', sessionId: state.activeSessionId, path, rootPath: rootPath || undefined });
  const detail = await readDone;
  if (detail.error) {
    showAttError(`Failed to attach ${name}: ${detail.error}`, target);
    return;
  }
  if (!detail.content) {
    // Binary non-media (zip, bin…) — readFile never sends content for these and
    // nf-file is whitelist-only, so there is nothing to embed.
    showAttError(`Cannot attach binary file ${name} — reference its path in the message instead`, target);
    return;
  }
  const file = new File([detail.content], name, { type: 'text/plain' });
  addFileAttachment(file, null, target);
}
