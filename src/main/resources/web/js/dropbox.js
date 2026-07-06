/**
 * Dropbox — cross-device messaging & file transfer.
 * Modal UI for sending text messages and files to neblink peers.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';
import { onMessage, sendWs } from './ws.js';

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
  return localStorage.getItem('nebflow_token') || '';
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
  // Request message history
  sendWs({ type: 'dropbox-get-history', deviceId: device.deviceId });
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

  overlay.innerHTML = `
    <div class="cfg-modal dropbox-modal">
      <div class="cfg-modal-title">
        <span>Dropbox — ${deviceName}</span>
        <span class="dropbox-close" id="dropbox-close-btn">×</span>
      </div>
      <div class="dropbox-device-info">
        <span class="dropbox-info-badge">${platform}</span>
        <span class="dropbox-info-badge dot">●</span>
        ${desc ? `<span class="dropbox-info-desc">${desc}</span>` : ''}
      </div>
      <div class="dropbox-messages" id="dropbox-messages"></div>
      <div class="dropbox-dropzone" id="dropbox-dropzone">
        <span>拖拽文件到此处，或点击选择</span>
        <input type="file" id="dropbox-file-input" style="display:none">
      </div>
      <div class="dropbox-input-bar">
        <input type="text" id="dropbox-text-input" class="cfg-input" placeholder="输入消息..." autocomplete="off">
        <button id="dropbox-send-btn" class="cfg-btn">发送</button>
      </div>
    </div>`;

  document.body.appendChild(overlay);

  // Bind events
  document.getElementById('dropbox-close-btn').onclick = closeDropbox;
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) closeDropbox();
  });

  bindChatEvents(device);
  renderMessages(device.deviceId);
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
  // Auto-scroll to bottom
  container.scrollTop = container.scrollHeight;

  // Bind accept/reject buttons for incoming file offers
  container.querySelectorAll('.dropbox-file-accept').forEach(btn => {
    btn.onclick = () => {
      const transferId = btn.dataset.transferId;
      const senderId = btn.dataset.deviceId;
      updateFileMessageStatus(senderId, transferId, 'accepted');
      sendWs({ type: 'dropbox-file-respond', deviceId: senderId, transferId, accepted: true });
    };
  });
  container.querySelectorAll('.dropbox-file-reject').forEach(btn => {
    btn.onclick = () => {
      const transferId = btn.dataset.transferId;
      const senderId = btn.dataset.deviceId;
      updateFileMessageStatus(senderId, transferId, 'rejected');
      sendWs({ type: 'dropbox-file-respond', deviceId: senderId, transferId, accepted: false });
    };
  });
}

function renderMessage(m) {
  const isOut = m.direction === 'out';
  const time = formatTime(m.ts);

  if (m.kind === 'text') {
    return `
      <div class="dropbox-msg ${isOut ? 'out' : 'in'}">
        <div class="dropbox-msg-bubble">${escapeHtml(m.text)}</div>
        <div class="dropbox-msg-time">${time}</div>
      </div>`;
  }

  if (m.kind === 'file') {
    const sizeStr = formatSize(m.fileSize);
    let statusHtml = '';
    let actionHtml = '';

    if (!isOut && m.status === 'pending') {
      // Incoming file offer — show accept/reject
      actionHtml = `
        <div class="dropbox-file-actions">
          <button class="dropbox-file-accept" data-transfer-id="${escapeHtml(m.transferId)}" data-device-id="${escapeHtml(openDeviceId || '')}">接受</button>
          <button class="dropbox-file-reject" data-transfer-id="${escapeHtml(m.transferId)}" data-device-id="${escapeHtml(openDeviceId || '')}">拒绝</button>
        </div>`;
    } else {
      const statusMap = {
        'pending':   { text: '等待确认', cls: 'waiting' },
        'accepted':  { text: '传输中...', cls: 'transferring' },
        'rejected':  { text: '已拒绝', cls: 'rejected' },
        'completed': { text: isOut ? '已送达' : `已保存到 ${escapeHtml(m.savedPath || 'Downloads')}`, cls: 'completed' },
        'failed':    { text: '传输失败', cls: 'failed' },
      };
      const st = statusMap[m.status] || { text: m.status, cls: '' };
      statusHtml = `<span class="dropbox-file-status ${st.cls}">${st.text}</span>`;
    }

    return `
      <div class="dropbox-msg ${isOut ? 'out' : 'in'}">
        <div class="dropbox-file-card">
          <div class="dropbox-file-info">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" style="opacity:0.6;flex-shrink:0"><path d="M4 1h6l4 4v10H4V1z" stroke="currentColor" stroke-width="1.2"/><path d="M10 1v4h4" stroke="currentColor" stroke-width="1.2"/></svg>
            <span class="dropbox-file-name">${escapeHtml(m.fileName)}</span>
            <span class="dropbox-file-size">${sizeStr}</span>
          </div>
          ${statusHtml}
          ${actionHtml}
        </div>
        <div class="dropbox-msg-time">${time}</div>
      </div>`;
  }

  return '';
}

// ===== Update a single message in the DOM =====

function updateMessageInDom(deviceId, msgId) {
  // Only re-render if this device's modal is open
  if (deviceId !== openDeviceId) return;
  const msgs = dropboxMessages[deviceId] || [];
  const msg = msgs.find(m => m.msgId === msgId);
  if (msg) {
    renderMessages(deviceId);
  }
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
