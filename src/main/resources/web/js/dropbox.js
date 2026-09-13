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
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';
// 时制（12h/24h）：与主对话框/好友对话框共享同一偏好与同一实现。
import { formatHm, bindTimeToggle } from './timeFormat.js';

// Per-device message cache: deviceId -> DropboxMessage[]
let dropboxMessages = {};

// Currently open modal device
let openDeviceId = null;

// Pending file selections, keyed by deviceId (FIFO). 附件腿批（2026-09-12）：
// 单条消息可带 ≤9 件，后端按 index 顺序串行 offer ⇒ 前端按 FIFO 逐件对号入座。
// deviceId -> File[]
let pendingFileQueues = {};

// Files ready to upload, keyed by transferId
// transferId -> File
let pendingUploads = {};

// ===== 附件闸位常量（必须与后端 AttachContract 逐字对齐）=====
//
// 🔴 量纲写死：100 MB **十进制** = 100,000,000 B（不是 100 MiB = 104,857,600 B）。
// 两处口径一旦漂移，前端会放行一个后端必拒的文件（或反之），且都是静默的。
const ATTACH_MAX_FILE_BYTES = 100000000;
const ATTACH_MAX_PER_MESSAGE = 9;
const ATTACH_MAX_FILE_LABEL = '100 MB (100,000,000 bytes)';

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
  // 时制（12h/24h）与主对话框共享同一偏好（timeFormat.js）。dropbox 的 ts 是
  // **epoch 毫秒**（DropboxModels.now = System.currentTimeMillis()），formatHm 同吃毫秒。
  return formatHm(ts);
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
      </div>
      <div class="dropbox-input-bar">
        <input type="text" id="dropbox-text-input" class="cfg-input" placeholder="${t('dropbox.inputPlaceholder')}" autocomplete="off">
        <button id="dropbox-attach-btn" class="icon-btn dropbox-attach-btn" title="${t('dropbox.attachFile')}" aria-label="${t('dropbox.attachFile')}"><i data-lucide="paperclip"></i></button>
        <button id="dropbox-send-btn" class="cfg-btn">${t('dropbox.send')}</button>
      </div>
      <input type="file" id="dropbox-file-input" style="display:none">
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
  // ⑤ 组字期间 Enter 交还输入法（非组字态行为逐键不变）。
  bindImeGuard(input);
  input.addEventListener('keydown', (e) => {
    if (isImeComposing(e, input)) return;
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
  const attachBtn = document.getElementById('dropbox-attach-btn');
  const fileInput = document.getElementById('dropbox-file-input');
  // HTMLElement（非 Element）标注：drag/drop 事件类型只能从 HTMLElementEventMap
  // 解析出来，否则回调参数退化成 Event（dataTransfer/relatedTarget 不可见）。
  const modal = /** @type {HTMLElement | null} */ (document.querySelector('.dropbox-modal'));

  // Send text
  const sendText = () => {
    const text = textInput.value.trim();
    if (!text) return;
    sendWs({ type: 'dropbox-send-text', deviceId: device.deviceId, text });
    textInput.value = '';
  };

  sendBtn.onclick = sendText;
  // ⑤ 组字期间 Enter 交还输入法（原判定只有 `!e.isComposing` 单臂，收归为统一谓词）。
  bindImeGuard(textInput);
  textInput.addEventListener('keydown', (e) => {
    if (isImeComposing(e, textInput)) return;
    if (e.key === 'Enter') {
      e.preventDefault();
      sendText();
    }
  });

  // 入口 ①：纸夹键 → 同一个 input[type=file]（③A6 第一格）
  if (attachBtn) attachBtn.addEventListener('click', () => fileInput.click());
  fileInput.onchange = () => {
    if (fileInput.files.length > 0) {
      // 附件腿批：多件选择（≤9）。闸位在 handleFilesSelected 内统一执行。
      handleFilesSelected(device.deviceId, fileInput.files);
      fileInput.value = '';
    }
  };

  // 入口 ②：拖拽目标 = 整个对话框（③-F 双入口：dropzone 条降为纯提示文案）。
  // 监听挂 body 捕获相位，理由与本文件既有的 blockBody{ Drop,DragOver} 一致：
  // 未拦截时浏览器会对落点文件执行默认打开动作。落点必须落在对话框内才生效。
  if (modal) {
    modal.addEventListener('dragover', (e) => {
      e.preventDefault();
      modal.classList.add('drag-over');
    });
    modal.addEventListener('dragleave', (e) => {
      const to = e.relatedTarget;
      if (!(to instanceof Node) || !modal.contains(to)) modal.classList.remove('drag-over');
    });
    modal.addEventListener('drop', (e) => {
      e.preventDefault();
      modal.classList.remove('drag-over');
      if (e.dataTransfer && e.dataTransfer.files.length > 0) {
        handleFilesSelected(device.deviceId, e.dataTransfer.files);
      }
    });
  }

  // Prevent body-level drop from navigating away while the modal is open
  document.body.addEventListener('drop', blockBodyDrop, true);
  document.body.addEventListener('dragover', blockBodyDragOver, true);
}

// body 捕获相位的兜底：对话框外的落点不触发设备动作，但必须 preventDefault，
// 否则浏览器会用该文件替换整个页面。对话框内的落点交给 modal 自身的监听（它在
// 捕获相位之后、冒泡相位之前不会被执行——所以这里对内部落点直接放行）。
function insideDropboxModal(e) {
  const modal = document.querySelector('.dropbox-modal');
  return !!(modal && e.target instanceof Node && modal.contains(e.target));
}

function blockBodyDrop(e) {
  if (!document.getElementById('dropbox-overlay')) return;
  if (insideDropboxModal(e)) return; // 由 modal 自身监听处理
  e.preventDefault();
  e.stopPropagation();
}

function blockBodyDragOver(e) {
  if (!document.getElementById('dropbox-overlay')) return;
  if (insideDropboxModal(e)) return;
  e.preventDefault();
  e.stopPropagation();
}

/**
 * 丢弃某设备的待发队列（R6）。
 *
 * 队列里的文件只能被「对应的出向 file 消息」消费。offer **失败**时那份消息永远不会来
 * （后端闸位拒绝 / 对端不可达 / dropbox 未启用 ⇒ 只回 `dropboxError`），队列若留着，
 * 下一次**成功** offer 的 transferId 就会对号入座到上一次的文件 —— 静默传错件。
 * 宁可让用户重选（可见），也不许传错文件（不可见）。
 */
function clearPendingFileQueue(deviceId) {
  if (deviceId && pendingFileQueues[deviceId]) delete pendingFileQueues[deviceId];
}

/**
 * 用户选/拖了文件 —— **闸位在本地先判一次**（件数 ≤9、单件 ≤100,000,000 B），
 * 超限**可见拒绝并回显实际值**（禁静默丢弃、禁只 console.error）。
 * 后端闸位仍在（本地闸只是提前反馈，不是唯一防线）。
 */
function handleFilesSelected(deviceId, fileList) {
  const files = Array.from(fileList || []);
  if (files.length === 0) return;

  if (files.length > ATTACH_MAX_PER_MESSAGE) {
    showDropboxNotice(deviceId, t('dropbox.tooManyFiles')
      .replace('{actual}', String(files.length))
      .replace('{limit}', String(ATTACH_MAX_PER_MESSAGE)));
    return;
  }
  const tooBig = files.find(f => f.size > ATTACH_MAX_FILE_BYTES);
  if (tooBig) {
    showDropboxNotice(deviceId, t('dropbox.fileTooLarge')
      .replace('{name}', tooBig.name)
      .replace('{actual}', formatSize(tooBig.size))
      .replace('{actualBytes}', String(tooBig.size))
      .replace('{limit}', ATTACH_MAX_FILE_LABEL));
    return;
  }

  // offer 失败路径 ②（R6）：`sendWs` 在 socket 非 OPEN 时**静默丢弃**（ws.js:275-279）——
  // 那样 offer 从未发出、也就永远不会有 `dropboxError` 回来，队列会**永久残留**。
  // 故先探活再入队；未连接 ⇒ 不入队 + 可见提示。
  if (!(state.ws && state.ws.readyState === 1 /* WebSocket.OPEN */)) {
    clearPendingFileQueue(deviceId);
    showDropboxNotice(deviceId, t('dropbox.notConnected'));
    return;
  }

  pendingFileQueues[deviceId] = (pendingFileQueues[deviceId] || []).concat(files);
  sendWs({
    type: 'dropbox-file-offer',
    deviceId,
    files: files.map(f => ({
      fileName: f.name,
      fileSize: f.size,
      mimeType: f.type || 'application/octet-stream'
    }))
  });
}

/** 单件入口（保留既有调用面）。 */
function handleFileSelected(deviceId, file) {
  handleFilesSelected(deviceId, [file]);
}

/** 可见拒绝条（超限回显实际值）。 */
function showDropboxNotice(deviceId, text) {
  if (!dropboxMessages[deviceId]) dropboxMessages[deviceId] = [];
  dropboxMessages[deviceId].push({
    msgId: 'notice-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8),
    direction: 'out',
    kind: 'notice',
    ts: Date.now(),
    text
  });
  if (deviceId === openDeviceId) renderMessages(deviceId);
}

// ===== Message rendering =====

function renderMessages(deviceId) {
  const container = document.getElementById('dropbox-messages');
  if (!container) return;

  const msgs = dropboxMessages[deviceId] || [];
  container.innerHTML = msgs.map(m => renderMessage(m)).join('');

  // 时制热区（每次 innerHTML 重建后重绑）：消息时间成为可点击/可键盘触发的
  // 切换入口，与主对话框/好友对话框共享同一偏好（timeFormat.js）。
  container.querySelectorAll('.dropbox-msg-time').forEach(bindTimeToggle);

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
          <span class="dropbox-msg-time" data-ts-text="${m.ts}">${time}</span>
          <button class="dropbox-msg-copy" data-text="${escapeHtml(m.text).replace(/"/g,'&quot;')}" title="${t('chat.copy')}">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
        </div>
      </div>`;
  }

  // 闸位拒绝 / 本地超限提示条（可见，回显实际值）
  if (m.kind === 'notice') {
    return `
      <div class="dropbox-msg ${isOut ? 'out' : 'in'}">
        <div class="dropbox-msg-bubble dropbox-notice">${escapeHtml(m.text)}</div>
        <div class="dropbox-msg-meta"><span class="dropbox-msg-time" data-ts-text="${m.ts}">${time}</span></div>
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

    // 块级进度（附件腿批）：进度独立字段，**不新增对 UI 可见的状态字符串**（沿用 5 态）。
    const pct = (m.bytesReceived && m.totalBytes)
      ? Math.min(100, Math.floor((m.downloadedBytes || m.bytesReceived) / m.totalBytes * 100))
      : null;

    return `
      <div class="dropbox-msg ${isOut ? 'out' : 'in'}">
        <div class="dropbox-file-card msg-file-card${isOut ? ' out' : ''}">
          <div class="dropbox-file-row">
            <svg width="20" height="20" viewBox="0 0 16 16" fill="none" style="opacity:0.5;flex-shrink:0"><path d="M4 1h6l4 4v10H4V1z" stroke="currentColor" stroke-width="1.2"/><path d="M10 1v4h4" stroke="currentColor" stroke-width="1.2"/></svg>
            <div class="dropbox-file-meta">
              <span class="dropbox-file-name">${escapeHtml(m.fileName)}</span>
              <span class="dropbox-file-info-line">${sizeStr}${m.savedPath ? ` \u00b7 ${escapeHtml(m.savedPath)}` : ''}${pct !== null ? ` \u00b7 ${pct}%` : ''}</span>
            </div>
            <span class="dropbox-file-badge ${st.cls}">${st.icon}</span>
          </div>
          ${pct !== null ? `<div class="dropbox-file-progress"><div class="dropbox-file-progress-bar" style="width:${pct}%"></div></div>` : ''}
          <div class="dropbox-file-status-text ${st.cls}">${statusText}</div>
        </div>
        <div class="dropbox-msg-time" data-ts-text="${m.ts}">${time}</div>
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

    // If this is an outgoing file message and we have pending file selections, map it.
    // 后端按 attachmentIndex 顺序串行 offer ⇒ 前端按 FIFO 逐件对号入座（≤9 件共用一条消息）。
    if (m.kind === 'file' && m.direction === 'out') {
      const queue = pendingFileQueues[deviceId] || [];
      if (queue.length > 0) {
        pendingUploads[m.transferId] = queue.shift();
        if (queue.length === 0) delete pendingFileQueues[deviceId];
      }
    }

    if (deviceId === openDeviceId) renderMessages(deviceId);
  });

  // 闸位拒绝 / 传输错误（后端结构化错误体：code + actual + limit）
  onMessage('dropboxError', (msg) => {
    const detail = msg.errorDetail || {};
    const deviceId = msg.deviceId || openDeviceId;
    // offer 失败路径 ①（R6）：offer 被后端拒（闸位 / 对端不可达 / dropbox 未启用）⇒
    // 那些文件不会再收到出向 file 消息去消费队列 —— 不清空就会把下一次成功 offer 的
    // transferId 错配到上一次的文件。清空必须与提示**同一轮**发生。
    clearPendingFileQueue(deviceId);
    let text = msg.error || t('dropbox.failed');
    if (detail.code === 'ATTACH_TOO_MANY') {
      text = t('dropbox.tooManyFiles')
        .replace('{actual}', String(detail.actual))
        .replace('{limit}', String(detail.limit));
    } else if (detail.code === 'ATTACH_TOO_LARGE') {
      text = t('dropbox.fileTooLarge')
        .replace('{name}', '')
        .replace('{actual}', formatSize(detail.actual))
        .replace('{actualBytes}', String(detail.actual))
        .replace('{limit}', ATTACH_MAX_FILE_LABEL);
    }
    if (deviceId) showDropboxNotice(deviceId, text);
    else console.error('[dropbox]', text);
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

  // 块级进度（附件腿批）：进度是独立字段，**不动** 5 态状态字符串。
  onMessage('dropbox-file-progress', (msg) => {
    const inner = msg.msg || {};
    const { transferId, bytesReceived, totalBytes } = inner;
    const msgs = dropboxMessages[msg.deviceId] || [];
    const m = msgs.find(x => x.transferId === transferId);
    if (m) {
      m.bytesReceived = bytesReceived;
      m.totalBytes = totalBytes;
      m.downloadedBytes = bytesReceived;
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
