// attachUpload.js — 附件上传（网页腿）**唯一**实现 + 上传卡**唯一**渲染器。
//
// 作者 2026-09-16 07:36：「附件等功能怎么没看到」——服务端腿已就绪，客户端腿是缺失
// 的另一半。本模块 = 客户端腿的单点：好友窗与群窗**共用同一个入口、同一个上传器、
// 同一个渲染器**（任务书 §1①「🔴 禁好友群各写一套」）。设备面（dropbox.js 的
// `sendDeviceFiles`）走它自己的既有传输链，本模块**零触碰**（设备行为零变化）。
//
// 上传形态（摘要批 A1 = ②）：**网页整件一次请求 → 网关 → 复用桌面分块驱动**。
// 浏览器把整件放进请求体（`fetch(..., {body: file})`，Chromium 自带流式发送），
// 网关把它流式落临时件后按 4 MiB 分块驱动推给 neblink-server（E1+E2×n，与桌面腿
// **同一份** `AttachUpload`）。因此：
//   · **进度**只来自网关在**服务端确认一块之后**广播的 WS 帧
//     `attach-upload-progress` —— 不存在「到点即 100%」的假进度（硬钉②）；
//   · **终态**只来自本次请求的响应：非 2xx ⇒ `{ok:false, code, error}` ⇒ 卡片进
//     `failed`/`cancelled` 态并**显示可判读文案**（禁静默、禁报成成功）；
//   · **1 GiB 早拒**在本地先判一次（`validateFiles`，与设备面同一常量源），
//     网关再判一次 —— 两面都**不晚于传输前**。
//
// 🔴 失败 fail-closed 可见：上传失败**绝不**发送消息（上传链任一环失败 ⇒ 一个字节
// 都没送进会话；已 staged 的服务端件由 7 天 TTL 兜底回收）。文案与群域
// `not_group_admin` 同族口径：**就地说清「没发出去」+ 原因**，不塞进任何 2xx。

import { t } from './i18n.js';
import { onMessage } from './ws.js';
import { getAuthToken } from './neblink.js';
import * as api from './friendsApi.js';
// 闸位常量**唯一来源**（设备面 dropbox.js 的既有常量，本批只把它们导出，
// 不改值、不改用法）⇒ 两个面不可能漂移出两个上限。
import { ATTACH_MAX_FILE_BYTES, ATTACH_MAX_PER_MESSAGE, ATTACH_MAX_FILE_LABEL } from './dropbox.js';

/** 上传条状态（渲染判据单点；字符串进 `data-upload-state` 供 QA 断言）。 */
export const UPLOAD_STATE = {
  UPLOADING: 'uploading',
  SENT: 'sent',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  SKIPPED: 'skipped',
};

/** convId → 上传条数组（本次会话的上传卡工作集）。 */
const uploadsByConv = new Map();
let seq = 0;
/** 当前挂载的上传卡容器（`renderUploadCards` 单点挂载；进度帧就地重画它）。 */
let mounted = null; // { container, convId }

const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = String(text);
  return node;
};

/** 人类可读字节数（与设备面同口径的十进制显示；仅展示用）。 */
function fmtBytes(n) {
  if (typeof n !== 'number' || !isFinite(n)) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

/** 会话的**附件寻址面**（好友 = 好友 userId；群 = 群会话 id）。
 *  设备面 / 未识别会话 ⇒ `null`（调用方不得调用本模块 ⇒ 结构性隔离）。
 *  🔴 单一实现：两个面都经此取址，禁各自拼。
 *  🔴 判据用**正向证据**（本仓 P5 口径「没有证据就不下结论」）：好友面**不**按
 *  `kind === 'friend'` 判 —— 会话行的 `kind` 在本仓**从不**取字面量 `'friend'`
 *  （单聊行靠 `conv.friend` 在场证明身份，见 `refreshConversations` 的 direct 行），
 *  按字面量判会得到一个**恒假**的闸（本批实测踩到：好友窗无纸夹、群窗有）。 */
export function attachTargetOf(conv) {
  if (!conv) return null;
  if (conv.kind === 'device') return null; // 设备腿走自己的传输链，绝不经本模块
  if (conv.kind === 'group') return conv.conversationId ? String(conv.conversationId) : null;
  const uid = conv.friend && conv.friend.userId;
  return uid ? String(uid) : null;
}

/** 纸夹是否可用（**两面同一判据**）。 */
export function attachAvailable(conv) {
  return attachTargetOf(conv) !== null;
}

/**
 * 本地闸位（**同一实现面**：设备面 dropbox.js 的 `handleFilesSelected` 用同一份
 * 常量；本函数供好友/群窗使用）。超限**可见拒绝并回显实际值** —— 禁静默丢弃。
 * 🔴 返回**单一形态**（`{ok, files, message}`，成功时 `message:''`）：调用方无需
 * 类型收窄 ⇒ 两面取用点逐字一致。
 * @returns {{ok: boolean, files: File[], message: string}}
 */
export function validateFiles(fileList) {
  const files = Array.from(fileList || []).filter(f => f && typeof f.size === 'number');
  if (files.length === 0) return { ok: false, files: [], message: t('messages.attachNoFiles') };
  if (files.length > ATTACH_MAX_PER_MESSAGE) {
    return {
      ok: false,
      files: [],
      message: t('dropbox.tooManyFiles')
        .replace('{actual}', String(files.length))
        .replace('{limit}', String(ATTACH_MAX_PER_MESSAGE)),
    };
  }
  const tooBig = files.find(f => f.size > ATTACH_MAX_FILE_BYTES);
  if (tooBig) {
    return {
      ok: false,
      files: [],
      message: t('dropbox.fileTooLarge')
        .replace('{name}', tooBig.name)
        .replace('{actual}', fmtBytes(tooBig.size))
        .replace('{actualBytes}', String(tooBig.size))
        .replace('{limit}', ATTACH_MAX_FILE_LABEL),
    };
  }
  const empty = files.find(f => f.size <= 0);
  if (empty) {
    return { ok: false, files: [], message: t('messages.attachEmptyFile').replace('{name}', empty.name) };
  }
  return { ok: true, files, message: '' };
}

function convUploads(convId) {
  if (!uploadsByConv.has(convId)) uploadsByConv.set(convId, []);
  return uploadsByConv.get(convId);
}

/** 本会话的上传条快照（渲染/断言用；调用方禁就地改结构）。 */
export function uploadsOf(convId) {
  return convUploads(convId).slice();
}

function paint() {
  if (!mounted) return;
  const { container, convId } = mounted;
  container.innerHTML = '';
  const items = convUploads(convId);
  container.hidden = items.length === 0;
  for (const item of items) container.appendChild(uploadCardEl(item));
}

function uploadCardEl(item) {
  const card = el('div', `fm-upload fm-upload-${item.state}`);
  card.dataset.uploadState = item.state;
  card.dataset.uploadId = item.uploadId;
  card.dataset.uploadName = item.name;
  card.dataset.uploadBytesSent = String(item.bytesSent || 0);
  card.dataset.uploadTotalBytes = String(item.totalBytes || item.size || 0);
  if (item.code) card.dataset.uploadCode = item.code;

  card.appendChild(el('span', 'fm-upload-name', item.name));
  card.appendChild(el('span', 'fm-upload-size', fmtBytes(item.size)));

  // 进度条**只在真实推进中出现**（宽度 = 服务端已确认的字节 / 总字节）。
  const total = item.totalBytes || item.size || 0;
  const pct = total > 0 ? Math.max(0, Math.min(100, Math.round(((item.bytesSent || 0) / total) * 100))) : 0;
  if (item.state === UPLOAD_STATE.UPLOADING) {
    const track = el('div', 'fm-upload-progress');
    const bar = el('div', 'fm-upload-progress-bar');
    bar.style.width = `${pct}%`;
    track.appendChild(bar);
    card.appendChild(track);
    card.appendChild(el('span', 'fm-upload-note', t('messages.attachProgress').replace('{pct}', String(pct))));
    const cancel = el('button', 'fm-upload-cancel', t('messages.attachCancel'));
    cancel.type = 'button';
    cancel.addEventListener('click', () => { void cancelUpload(item.uploadId, item.convId); });
    card.appendChild(cancel);
  } else if (item.state === UPLOAD_STATE.SENT) {
    card.appendChild(el('span', 'fm-upload-note', t('messages.attachSent')));
  } else {
    // 失败 / 取消 / 未发（fail-closed 可见：**终态文案就在卡上**，不靠 toast 兜底）。
    card.appendChild(el('span', 'fm-upload-note', item.error || t('messages.attachFailed')));
  }
  return card;
}

/** **唯一**渲染入口（messages.js 只在 `renderMessages` 里调它一次 ⇒ 好友窗与群窗
 *  共用同一份实现/同一挂载点；设备窗不调）。幂等：重进只重挂容器。 */
export function renderUploadCards(flow, convId) {
  if (!flow) return;
  const container = el('div', 'fm-upload-list');
  container.dataset.convId = String(convId || '');
  flow.appendChild(container);
  mounted = { container, convId };
  paint();
}

/** 会话切换/关窗时解除挂载（避免进度帧画到已关闭的窗上）。 */
export function detachUploadCards() {
  mounted = null;
}

// ── 上传链 ────────────────────────────────────────────────

function beginItem(convId, uploadId, file) {
  const item = {
    uploadId,
    convId,
    name: file.name,
    size: file.size,
    bytesSent: 0,
    totalBytes: file.size,
    state: UPLOAD_STATE.UPLOADING,
    code: '',
    error: '',
    attachmentId: '',
    controller: typeof AbortController !== 'undefined' ? new AbortController() : null,
  };
  convUploads(convId).push(item);
  paint();
  return item;
}

function findItem(uploadId) {
  for (const list of uploadsByConv.values()) {
    const hit = list.find(i => i.uploadId === uploadId);
    if (hit) return hit;
  }
  return null;
}

/** 单件上传（整件一次请求）。返回 `{ok:true, attachmentId}` 或 `{ok:false, code, message}`。 */
async function uploadOne(item, file, targetId) {
  const token = getAuthToken();
  const qs = new URLSearchParams({
    conversationId: targetId,
    name: file.name || 'attachment',
    uploadId: item.uploadId,
  });
  let resp;
  try {
    resp = await fetch(`/api/attachments?${qs.toString()}`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Attach-Size': String(file.size),
        'Content-Type': 'application/octet-stream',
      },
      body: file,
      ...(item.controller ? { signal: item.controller.signal } : {}),
    });
  } catch (e) {
    // 请求本身失败（网络/被 abort）—— 终止态**如实**：取消 ⇒ cancelled，否则 failed。
    if (item.state === UPLOAD_STATE.CANCELLED) return { ok: false, code: 'cancelled', message: t('messages.attachCancelled') };
    return { ok: false, code: 'network', message: t('messages.attachFailed') };
  }
  let data = null;
  try { data = await resp.json(); } catch { data = null; }
  if (!resp.ok || !data || data.ok !== true) {
    const code = (data && data.code) || `http_${resp.status}`;
    const message = (data && data.error) || t('messages.attachFailed');
    return { ok: false, code, message };
  }
  return { ok: true, attachmentId: String(data.attachmentId || '') };
}

/** 取消一件在飞上传：先翻网关的取消位（停止后续分块），再断本请求
  *  （停止还在上行中的字节）。两路合起来 ⇒ **不再有任何后续分块**。 */
export async function cancelUpload(uploadId, convId) {
  const item = findItem(uploadId);
  if (!item || item.state !== UPLOAD_STATE.UPLOADING) return;
  item.state = UPLOAD_STATE.CANCELLED;
  item.code = 'cancelled';
  item.error = t('messages.attachCancelled');
  paint();
  try {
    const token = getAuthToken();
    await fetch(`/api/attachments/${encodeURIComponent(uploadId)}/cancel`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` },
    });
  } catch { /* 网关不可达 ⇒ 仍有 abort 兜底；终态仍是用户可见的「已取消」 */ }
  try { item.controller?.abort(); } catch { /* 已结束 */ }
  void convId;
}

/**
 * **好友窗与群窗的唯一发送入口**：闸 → 逐件上传 → 全部成功才发消息。
 *
 * fa-fail-closed：任一件失败 ⇒ **不发消息**（未发送的件标 `skipped`），且失败件的
 * 卡片留在原地显示原因。取消 ⇒ 立即停止（剩余件标 `skipped`），同样不发消息。
 *
 * @returns {Promise<{ok: boolean, reason?: string, ids?: string[]}>}
 */
export async function sendFiles(conv, fileList, text) {
  const convId = conv && conv.conversationId;
  const targetId = attachTargetOf(conv);
  if (!convId || !targetId) return { ok: false, reason: t('messages.attachUnsupported') };
  const gate = validateFiles(fileList);
  if (!gate.ok) return { ok: false, reason: gate.message };

  const files = gate.files;
  const ids = [];
  for (let i = 0; i < files.length; i += 1) {
    const file = files[i];
    const uploadId = `att-${Date.now()}-${(++seq)}-${Math.random().toString(36).slice(2, 8)}`;
    const item = beginItem(convId, uploadId, file);
    const res = await uploadOne(item, file, targetId);
    if (res.ok) {
      item.attachmentId = res.attachmentId;
      item.bytesSent = file.size;
      item.totalBytes = file.size;
      ids.push(res.attachmentId);
    } else {
      item.state = res.code === 'cancelled' ? UPLOAD_STATE.CANCELLED : UPLOAD_STATE.FAILED;
      item.code = res.code || 'upload_failed';
      item.error = res.message || t('messages.attachFailed');
      // 取消 ⇒ 用户主动停 ⇒ 剩余件不再上传（禁「偷偷继续传」）。
      const reasonKey = item.state === UPLOAD_STATE.CANCELLED ? 'messages.attachCancelledRest' : 'messages.attachNotSentRest';
      for (let j = i + 1; j < files.length; j += 1) {
        const rest = beginItem(convId, `att-${Date.now()}-${(++seq)}-skip`, files[j]);
        rest.state = UPLOAD_STATE.SKIPPED;
        rest.code = item.code;
        rest.error = t(reasonKey);
      }
      paint();
      return { ok: false, reason: item.error };
    }
  }

  // 全部上传成功 ⇒ 发消息（正文可空：服务端 §B.4 生成占位正文）。
  try {
    if (conv.kind === 'group') await api.sendGroupMessage(conv.conversationId, text, ids);
    else await api.sendFriendMessage(conv.friend.userId, text, ids);
  } catch (e) {
    const message = t('messages.attachSendFailed');
    for (const item of convUploads(convId)) {
      if (item.state === UPLOAD_STATE.UPLOADING) {
        item.state = UPLOAD_STATE.FAILED;
        item.code = 'message_send_failed';
        item.error = message;
      }
    }
    paint();
    return { ok: false, reason: message };
  }
  for (const item of convUploads(convId)) {
    if (item.state === UPLOAD_STATE.UPLOADING) {
      item.state = UPLOAD_STATE.SENT;
      item.bytesSent = item.totalBytes;
    }
  }
  paint();
  return { ok: true, ids };
}

/** 清掉某会话的已终态上传卡（窗关闭时由 messages.js 调用；在飞件保留）。 */
export function clearSettledUploads(convId) {
  const list = convUploads(convId);
  const keep = list.filter(i => i.state === UPLOAD_STATE.UPLOADING);
  uploadsByConv.set(convId, keep);
  paint();
}

// ── 进度帧（**唯一**进度来源 = 网关在服务端确认一块之后的广播）────────────

onMessage('attach-upload-progress', (msg) => {
  const inner = (msg && msg.msg) || {};
  const uploadId = inner.uploadId;
  if (!uploadId) return;
  const item = findItem(uploadId);
  if (!item) return;
  if (item.state !== UPLOAD_STATE.UPLOADING) return;
  const sent = typeof inner.bytesSent === 'number' ? inner.bytesSent : item.bytesSent;
  const total = typeof inner.totalBytes === 'number' ? inner.totalBytes : item.totalBytes;
  item.bytesSent = sent;
  item.totalBytes = total;
  paint();
});
