/**
 * Dropbox — cross-device messaging & file transfer.
 * Also serves as the device info / description editor modal for ALL devices (including local).
 */
import state from './state.js';
import { key } from './branding.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';
import { onMessage, sendWs } from './ws.js';
import { refreshNeblink } from './neblink.js';

// Per-device message cache: deviceId -> DropboxMessage[]
let dropboxMessages = {};

// Currently open modal device
let openDeviceId = null;

// Pending file selected by user, waiting for offer→transferId mapping
// { deviceId, file }
let pendingFileSelection = null;

// Files ready to upload, keyed by transferId
// transferId -> File
let pendingUploads = {};

function getAuthToken() {
  return localStorage.getItem(key('token')) || '';
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
  return (bytes / 1073741824).toFixed(2) + ' GB';
}

function formatTime(ts) {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

// ===== Open / close modal =====

export function openDropbox(device) {
  openDeviceId = device.deviceId;
  renderModal(device);
  // Request message history (only for remote devices)
  if (!device.isLocal) {
    sendWs({ type: 'dropbox-get-history', deviceId: device.deviceId });
  }
}

export function closeDropbox() {
  document.getElementById('dropbox-overlay')?.remove();
  openDeviceId = null;
}

function renderModal(device) {
  // Remove existing
  document.getElementById('dropbox-overlay')?.remove();

  const overlay = document.createElement('div');
  overlay.id = 'dropbox-overlay';
  overlay.className = 'cfg-modal-overlay';

  const deviceName = escapeHtml(device.deviceName || 'Unknown');
  const platform = escapeHtml(device.platform || '');
  const desc = escapeHtml(device.userDescription || '');
  const isLocal = device.isLocal;
  const hasChat = !isLocal;

  // Tab bar (only when both tabs exist)
  const tabsHtml = hasChat ? `
    <div class="dropbox-tabs">
      <button class="dropbox-tab active" data-tab="chat">${t('dropbox.tabChat')}</button>
      <button class="dropbox-tab" data-tab="desc">${t('dropbox.tabDesc')}</button>
    </div>` : '';

  // Chat tab content
  const chatHtml = hasChat ? `
    <div class="dropbox-tab-content active" id="dropbox-tab-chat">
      <div class="dropbox-messages" id="dropbox-messages"></div>
      <div class="dropbox-dropzone" id="dropbox-dropzone">
        <span>${t('dropbox.dropHint')}</span>
        <input type="file" id="dropbox-file-input" style="display:none">
      </div>
      <div class="dropbox-input-bar">
        <input type="text" id="dropbox-text-input" class="cfg-input" placeholder="${t('dropbox.inputPlaceholder')}" autocomplete="off">
        <button id="dropbox-send-btn" class="cfg-btn">${t('dropbox.send')}</button>
      </div>
    </div>` : '';

  // Description tab content (always present)
  const descHtml = `
    <div class="dropbox-tab-content${hasChat ? '' : ' active'}" id="dropbox-tab-desc">
      <div class="dropbox-desc-editor-large">
        <textarea id="dropbox-desc-input" class="dropbox-desc-textarea" placeholder="${t('neblink.deviceDescHint')}">${desc}</textarea>
        <div class="dropbox-desc-actions">
          <button id="dropbox-desc-save" class="cfg-btn">${t('neblink.save')}</button>
        </div>
      </div>
    </div>`;

  overlay.innerHTML = `
    <div class="cfg-modal dropbox-modal">
      <div class="cfg-modal-title">
        <span>${deviceName}</span>
        <span class="dropbox-close" id="dropbox-close-btn">×</span>
      </div>
      <div class="dropbox-device-info">
        <span class="dropbox-info-badge">${platform}</span>
        <span class="dropbox-info-badge dot">●</span>
        ${isLocal ? `<span class="dropbox-info-desc">${t('neblink.thisDevice')}</span>` : ''}
      </div>
      ${tabsHtml}
      ${chatHtml}
      ${descHtml}
    </div>`;

  document.body.appendChild(overlay);

  // Bind events
  document.getElementById('dropbox-close-btn').onclick = closeDropbox;
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) closeDropbox();
  });

  // Bind tab switching
  if (hasChat) bindTabs();

  // Bind description editor (all devices)
  bindDescEditor(device);

  // Bind messaging events (remote only)
  if (hasChat) {
    bindChatEvents(device);
    renderMessages(device.deviceId);
  }
}

function bindTabs() {
  document.querySelectorAll('.dropbox-tab').forEach(tab => {
    tab.onclick = () => {
      const target = tab.dataset.tab;
      document.querySelectorAll('.dropbox-tab').forEach(t => t.classList.toggle('active', t === tab));
      document.querySelectorAll('.dropbox-tab-content').forEach(c => {
        c.classList.toggle('active', c.id === `dropbox-tab-${target}`);
      });
    };
  });
}

// ===== Description editor =====

function bindDescEditor(device) {
  const saveBtn = document.getElementById('dropbox-desc-save');
  const input = document.getElementById('dropbox-desc-input');

  const doSave = async () => {
    const desc = input.value.trim();
    const token = getAuthToken();
    const isLocal = device.isLocal;
    const url = isLocal ? '/api/neblink/device-info' : '/api/neblink/peer-description';
    const body = isLocal
      ? { userDescription: desc }
      : { deviceId: device.deviceId, userDescription: desc };

    const originalText = saveBtn.textContent;
    try {
      await fetch(url, {
        method: 'PUT',
        headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      saveBtn.textContent = '✓';
      // Refresh neblink state so settings panel updates
      refreshNeblink();
    } catch (e) {
      saveBtn.textContent = '!';
    }
    setTimeout(() => { saveBtn.textContent = originalText; }, 1200);
  };

  saveBtn.onclick = doSave;
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      doSave();
    }
  });
}

// ===== Chat events =====

function bindChatEvents(device) {
  const sendBtn = document.getElementById('dropbox-send-btn');
  const textInput = document.getElementById('dropbox-text-input');
  const dropzone = document.getElementById('dropbox-dropzone');
  const fileInput = document.getElementById('dropbox-file-input');

  // Send text
  const sendText = () => {
    const text = textInput.value.trim();
    if (!text) return;
    sendWs({ type: 'dropbox-send-text', deviceId: device.deviceId, text });
    textInput.value = '';
  };

  sendBtn.onclick = sendText;
  textInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.isComposing) {
      e.preventDefault();
      sendText();
    }
  });

  // File selection via click
  dropzone.onclick = () => fileInput.click();
  fileInput.onchange = () => {
    if (fileInput.files.length > 0) {
      handleFileSelected(device.deviceId, fileInput.files[0]);
      fileInput.value = '';
    }
  };

  // File drag-drop
  dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.classList.add('drag-over');
  });
  dropzone.addEventListener('dragleave', () => dropzone.classList.remove('drag-over'));
  dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.classList.remove('drag-over');
    if (e.dataTransfer.files.length > 0) {
      handleFileSelected(device.deviceId, e.dataTransfer.files[0]);
    }
  });

  // Prevent body-level drop from intercepting when modal is open
  document.body.addEventListener('drop', blockBodyDrop, true);
  document.body.addEventListener('dragover', blockBodyDragOver, true);
}

function blockBodyDrop(e) {
  if (document.getElementById('dropbox-overlay')) {
    e.preventDefault();
    e.stopPropagation();
  }
}

function blockBodyDragOver(e) {
  if (document.getElementById('dropbox-overlay')) {
    e.preventDefault();
    e.stopPropagation();
  }
}

function handleFileSelected(deviceId, file) {
  pendingFileSelection = { deviceId, file };
  sendWs({
    type: 'dropbox-file-offer',
    deviceId,
    fileName: file.name,
    fileSize: file.size,
    mimeType: file.type || 'application/octet-stream'
  });
}

// ===== Message rendering =====

function renderMessages(deviceId) {
  const container = document.getElementById('dropbox-messages');
  if (!container) return;

  const msgs = dropboxMessages[deviceId] || [];
  container.innerHTML = msgs.map(m => renderMessage(m)).join('');

  // Copy button delegation (re-bound each render since innerHTML replaces children)
  container.querySelectorAll('.dropbox-msg-copy').forEach(btn => {
    btn.onclick = async (e) => {
      e.stopPropagation();
      try {
        await navigator.clipboard.writeText(btn.dataset.text);
        const origSvg = btn.innerHTML;
        btn.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
        btn.classList.add('copied');
        setTimeout(() => { btn.innerHTML = origSvg; btn.classList.remove('copied'); }, 1500);
      } catch (err) {
        console.error('[dropbox] Copy failed:', err);
      }
    };
  });

  // Auto-scroll to bottom
  container.scrollTop = container.scrollHeight;
}

function renderMessage(m) {
  const isOut = m.direction === 'out';
  const time = formatTime(m.ts);

  if (m.kind === 'text') {
    return `
      <div class="dropbox-msg ${isOut ? 'out' : 'in'}">
        <div class="dropbox-msg-bubble">${escapeHtml(m.text)}</div>
        <div class="dropbox-msg-meta">
          <span class="dropbox-msg-time">${time}</span>
          <button class="dropbox-msg-copy" data-text="${escapeHtml(m.text)}" title="${t('chat.copy')}">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
        </div>
      </div>`;
  }

  if (m.kind === 'file') {
    const sizeStr = formatSize(m.fileSize);
    const statusMap = {
      'pending':   { icon: '...', cls: 'transferring' },
      'accepted':  { icon: '...', cls: 'transferring' },
      'completed': { icon: isOut ? '\u2713' : '\u2713', cls: 'completed' },
      'failed':    { icon: '\u2717', cls: 'failed' },
    };
    const st = statusMap[m.status] || { icon: '', cls: '' };
    const statusText = m.status === 'completed'
      ? (isOut ? t('dropbox.delivered') : (m.savedPath ? t('dropbox.saved') : t('dropbox.completed')))
      : (m.status === 'failed' ? t('dropbox.failed') : t('dropbox.transferring'));

    return `
      <div class="dropbox-msg ${isOut ? 'out' : 'in'}">
        <div class="dropbox-file-card">
          <div class="dropbox-file-row">
            <svg width="20" height="20" viewBox="0 0 16 16" fill="none" style="opacity:0.5;flex-shrink:0"><path d="M4 1h6l4 4v10H4V1z" stroke="currentColor" stroke-width="1.2"/><path d="M10 1v4h4" stroke="currentColor" stroke-width="1.2"/></svg>
            <div class="dropbox-file-meta">
              <span class="dropbox-file-name">${escapeHtml(m.fileName)}</span>
              <span class="dropbox-file-info-line">${sizeStr}${m.savedPath ? ` \u00b7 ${escapeHtml(m.savedPath)}` : ''}</span>
            </div>
            <span class="dropbox-file-badge ${st.cls}">${st.icon}</span>
          </div>
          <div class="dropbox-file-status-text ${st.cls}">${statusText}</div>
        </div>
        <div class="dropbox-msg-time">${time}</div>
      </div>`;
  }

  return '';
}

// ===== WS message handlers =====

export function initDropbox() {
  // Incoming/outgoing message (text or file record)
  onMessage('dropbox-message', (msg) => {
    const deviceId = msg.deviceId;
    const m = msg.msg;
    if (!dropboxMessages[deviceId]) dropboxMessages[deviceId] = [];
    // Replace if same msgId exists (status update), else append
    const idx = dropboxMessages[deviceId].findIndex(x => x.msgId === m.msgId);
    if (idx >= 0) dropboxMessages[deviceId][idx] = m;
    else dropboxMessages[deviceId].push(m);

    // If this is an outgoing file message and we have a pending file selection, map it
    if (m.kind === 'file' && m.direction === 'out' && pendingFileSelection && pendingFileSelection.deviceId === deviceId) {
      pendingUploads[m.transferId] = pendingFileSelection.file;
      pendingFileSelection = null;
    }

    if (deviceId === openDeviceId) renderMessages(deviceId);
  });

  // File offer accepted/rejected (sender side)
  onMessage('dropbox-file-response', (msg) => {
    const inner = msg.msg || {};
    const { transferId, accepted } = inner;
    if (accepted && pendingUploads[transferId]) {
      uploadFile(transferId, pendingUploads[transferId]);
    } else if (!accepted) {
      delete pendingUploads[transferId];
    }
    updateFileMessageStatus(msg.deviceId, transferId, accepted ? 'accepted' : 'rejected');
  });

  // File transfer completed/failed (both sides)
  onMessage('dropbox-file-complete', (msg) => {
    const inner = msg.msg || {};
    const { transferId, success, savedPath } = inner;
    delete pendingUploads[transferId];
    const msgs = dropboxMessages[msg.deviceId] || [];
    const m = msgs.find(x => x.transferId === transferId);
    if (m) {
      m.status = success ? 'completed' : 'failed';
      if (savedPath) m.savedPath = savedPath;
      if (msg.deviceId === openDeviceId) renderMessages(msg.deviceId);
    }
  });

  // Message history response
  onMessage('dropbox-history', (msg) => {
    dropboxMessages[msg.deviceId] = msg.messages || [];
    if (msg.deviceId === openDeviceId) renderMessages(msg.deviceId);
  });
}

// ===== Helpers =====

function updateFileMessageStatus(deviceId, transferId, status) {
  const msgs = dropboxMessages[deviceId] || [];
  const m = msgs.find(x => x.transferId === transferId);
  if (m) {
    m.status = status;
    if (deviceId === openDeviceId) renderMessages(deviceId);
  }
}

async function uploadFile(transferId, file) {
  try {
    const token = getAuthToken();
    const resp = await fetch(`/api/neblink/dropbox/upload/${transferId}`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` },
      body: file
    });
    const data = await resp.json();
    if (!data.ok) {
      console.error('[dropbox] Upload failed:', data.error);
    }
  } catch (e) {
    console.error('[dropbox] Upload error:', e);
  }
}
