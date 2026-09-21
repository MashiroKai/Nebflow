/**
 * Dropbox — cross-device messaging & file transfer.
 * Also serves as the device info / description editor modal for ALL devices (including local).
 */
import state from './state.js';
import { key } from './branding.js';
import { escapeHtml, createIconsIn } from './utils.js';
import { t } from './i18n.js';
import { onMessage, onDisconnect, onReconnect, sendWs } from './ws.js';
import { refreshNeblink } from './neblink.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
import { bindImeGuard, isImeComposing } from './imeGuard.js';
// 时制（12h/24h）：与主对话框/好友对话框共享同一偏好与同一实现。
import { formatHm, bindTimeToggle } from './timeFormat.js';
// ⑩ L2 本地持久层（作者 2026-09-14 17:21 令「缓存优先直出」）：本面此前**零本地层**
// ⇒ 首帧恒等挂在 `dropbox-history` 回包上（考古位实测 Δ=0.0–0.1 ms，13/13 全等；
// 回包静默 ⇒ 永不显示）。本模块补的正是这一层。
import { loadDeviceMessages, saveDeviceMessages } from './fmDropboxCache.js';
// ⑩ 合并期滚动位保持（与好友消息面**共用一份实现**，无第二个公式）。
import { preserveScrollAnchor } from './msgScrollAnchor.js';

// Per-device message cache: deviceId -> DropboxMessage[]
// ⑩ 语义变更：本对象从「服务端回包的镜像」升级为「**先缓存、后增量合并**的工作集」
// —— 开窗时先由 `hydrateFromCache` 从 L2 灌入，回包再就地收敛（见 `mergeDeviceMessages`）。
let dropboxMessages = {};

// Currently open modal device
let openDeviceId = null;

// ③ 发送键族统一批（2026-09-15）：链路态本地镜像（掉线 ⇒ `#dropbox-send-btn` 转灰）。
// 🔴 判据源唯一 = `ws.js` 的连接态回调（本模块只镜像一个布尔，不去读别模块内部 state）；
// 初值 true 与 `state.connected` 的初始语义同（未连接前不发任何请求，键由空输入闸禁用）。
let connUp = true;

// ⑩ 已发出 `dropbox-get-history` 且尚未回包的设备集合：决定「无缓存设备」显示
// **加载态**还是**空态**。这是「仅无本地缓存才走加载态」的唯一判据来源
// （有缓存的设备首帧就是消息本身，不进加载态 —— 加载态不参与掩盖慢）。
const historyPending = new Set();

// Pending file selections, keyed by deviceId (FIFO). 附件腿批（2026-09-12）：
// 单条消息可带 ≤9 件，后端按 index 顺序串行 offer ⇒ 前端按 FIFO 逐件对号入座。
// deviceId -> File[]
let pendingFileQueues = {};

// Files ready to upload, keyed by transferId
// transferId -> File
let pendingUploads = {};

// ===== 设备腿**传输生命周期**台账 + emission（uxconsist Phase B · §3.3）=====
//
// 病灶（R3-device / R6-device / R7-device / R8-device 四格的共同成因）：设备附件腿的
// 传输态**只活在本模块内部**（FIFO 无 UI、失败只 `console.error`、无取消），而**新窗**
// （messages.js 面）的附件反馈面此前只由「服务端腿的 dropbox 通知」驱动 ⇒ 动作面与
// 反馈面**结构性脱节**（§7 清扫 #7）。本段把「传输发生了什么」升为**单点 emission**：
//   · 唯一属主仍 = 本模块（传输链一行不动：FIFO / offer / 上行 / 台账全在此）；
//   · 消费方（messages.js）只据此**登记显示态**（`attachUpload.registerExternalTransfer`
//     / `updateExternalTransfer`）——只共享显示，不碰传输（`attachTargetOf(device)`
//     恒 `null` 的结构性隔离不变）。
// 🔴 emission 的 `id` = **本地件号**（`dt-…`）：件在 offer 被受理前后都要有稳定键
//    （wire 的 `transferId` 由「出向 file 消息逐件对号」给出，见 `dropbox-message` 腿），
//    故 emission 一律用本地件号，wire id 只作**加性字段**回带（取消/断言用）。

/** 本地件号 → 传输台账条目 `{id, deviceId, file, transferId, state, controller}`。 */
const deviceTransfers = new Map();
/** wire `transferId` → 本地件号（帧回包按 wire id 找卡）。 */
const transferIdIndex = new Map();
let transferSeq = 0;

/** 本地件号（唯一生成点）。 */
function nextTransferLocalId() {
  transferSeq += 1;
  return `dt-${Date.now().toString(36)}-${transferSeq}`;
}

/** 入队建台账（S1 排队行的**显示态**源）。 */
function trackTransfer(deviceId, file) {
  const item = { id: nextTransferLocalId(), deviceId, file, transferId: '', state: 'queued', controller: null };
  deviceTransfers.set(item.id, item);
  return item;
}

/** 出向 file 消息逐件对号时**同拍**锚上 wire id（与 `pendingUploads` 的 FIFO 同一次
 *  消费 ⇒ 两表不可能各锚一件；禁另写第二套对号）。 */
function bindTransferWireId(deviceId, transferId, file) {
  for (const item of deviceTransfers.values()) {
    if (item.deviceId === deviceId && item.file === file && !item.transferId) {
      item.transferId = transferId;
      transferIdIndex.set(transferId, item.id);
      return item;
    }
  }
  return null;
}

const transferSubscribers = /** @type {Set<(evt: any) => void>} */ (new Set());

/** 传输生命周期订阅（返回注销函数；形态同既有 `onDeviceMessageChange`）。**只做通知**。 */
export function onDeviceTransfer(cb) {
  transferSubscribers.add(cb);
  return () => transferSubscribers.delete(cb);
}

/** emission 单点（订阅方异常**绝不**回灌传输链）。 */
function emitTransfer(evt) {
  for (const cb of [...transferSubscribers]) {
    try { cb(evt); } catch (e) { console.error('[dropbox] transfer subscriber failed:', e); }
  }
}

/** 批量置终态（offer 被拒 / 闸位错误回包）：所有**未对号**的排队件一并翻面 ——
 *  否则它们会永远停在「排队中」（那些文件不会再收到出向 file 消息去消费）。
 *  🔴 `file` **必须在场**（§5 R3/R4-device）：卡面的重试键由消费方按 `evt.file` 装配
 *     （`messages.js::onDeviceTransferEvent`）—— 缺它则失败卡只有文案、没有重试入口。 */
function failQueuedTransfers(deviceId, message, code) {
  for (const item of deviceTransfers.values()) {
    if (item.deviceId !== deviceId || item.transferId || item.state !== 'queued') continue;
    item.state = 'failed';
    emitTransfer({ phase: 'failed', deviceId, id: item.id, name: item.file.name, size: item.file.size, file: item.file, message, code });
  }
}

/** 取消一件设备附件传输（§5 R7-device · S8；新窗卡上的取消键唯一落点）。
 *
 *  两档（同一函数的两个分支，语义如实登记）：
 *   · **未 offer**（仍在 `pendingFileQueues`）：移出 FIFO —— 否则下一次成功 offer 会把
 *     `transferId` 对号到一件用户已取消的文件（静默传错件，R6 病灶形态）；
 *   · **在传**：`AbortController` 断上行。
 *  🔴 「对端是否停」需取消帧 / 后端确认 abort 语义 ⇒ **本批不承诺**（设计件 §9-3 候令项，
 *     不属本批；此处如实声明：客户端只停**上行**）。 */
export function cancelDeviceTransfer(deviceId, id) {
  const item = deviceTransfers.get(id);
  if (!item || item.state === 'cancelled' || item.state === 'sent') return;
  const dev = deviceId || item.deviceId;
  item.state = 'cancelled';
  const queue = pendingFileQueues[dev] || [];
  const qi = queue.indexOf(item.file);
  if (qi >= 0) queue.splice(qi, 1);
  if (queue.length === 0) delete pendingFileQueues[dev];
  try { item.controller?.abort(); } catch { /* 已结束 */ }
  emitTransfer({
    phase: 'cancelled', deviceId: dev, id: item.id, transferId: item.transferId,
    name: item.file.name, size: item.file.size, message: t('messages.attachCancelled'),
  });
}

// ===== 发送件真句柄表（selfattach 批 · 片 2「A 腿」，作者 D-1 = A + B′ 混合裁定）=====
//
// **发**侧本机字节句柄（浏览器 user 腿唯一可得的字节源）：`File` 是**盘上惰性句柄**，
// 不是字节副本 ⇒ 零内存代价、零留存。用途 = 该次会话内让设备 legacy **出向行**的附件卡
// 与好友/群面同款（直显 / 点击预览 / 保存键）——判据与消费全在 `messages.js`，本表只登记+只读。
//
// 🔴 与 `pendingUploads` **不同轴，禁合并**：后者是「待上传队列」的**一次性**交接（上传已发起 /
//    完成即删，见 `:776`），本表是「发送侧本机现实」的**会话级**句柄（跨 `file-complete` 存活）。
// 🔴 有界 LRU（上限 `OUT_HANDLE_MAX`）：超限淘汰最旧者，并广播
//    `dropbox-out-handle-evicted`（消费侧据此撤销由该句柄派生的 objectURL，禁悬空 URL）。
// 🔴 刷新/关窗即整体消失（页面级内存）⇒ 回落 = 台账帧的「名称 + 状态」形态（作者 D-2：
//    该回落**可接受**，已登记，不是 bug）。
const OUT_HANDLE_MAX = 32;
/** transferId -> File（LRU 序：最近登记/读取者在末位）。 */
const outFileHandles = new Map();

/** 登记一枚发送侧句柄（^LRU 末尾；超限淘汰最旧者并广播退场事件）。
 *  @param {string} transferId @param {File|null|undefined} file */
function rememberOutFileHandle(transferId, file) {
  if (!transferId || !file) return;
  if (outFileHandles.has(transferId)) outFileHandles.delete(transferId);
  outFileHandles.set(transferId, file);
  while (outFileHandles.size > OUT_HANDLE_MAX) {
    const oldest = outFileHandles.keys().next();
    if (oldest.done) break;
    outFileHandles.delete(oldest.value);
    try {
      document.dispatchEvent(new CustomEvent('dropbox-out-handle-evicted', { detail: { transferId: oldest.value } }));
    } catch { /* 事件面失败不影响句柄表本身 */ }
  }
}

/** 发送侧句柄只读访问器（消费面唯一入口；命中即 LRU touch）。
 *  @param {string} transferId @returns {File|null} */
export function outFileHandleOf(transferId) {
  if (!transferId) return null;
  const f = outFileHandles.get(transferId);
  if (!f) return null;
  outFileHandles.delete(transferId);
  outFileHandles.set(transferId, f); // LRU touch（在看的行不被淘汰）
  return f;
}

// ===== 附件闸位常量（必须与后端 AttachContract 逐字对齐）=====
//
// 🔴 量纲写死：**1024 MB = 1 GiB = 1,073,741,824 B**（作者 2026-09-14 09:14 原话
// 「把文件传输的上限增加到一个g。1024MB」⇒ 按 1 GiB 取数，取代 2026-09-12 的
// 「100 MB 十进制 = 100,000,000 B」口径；单条消息 ≤9 件不变）。
// 两处口径一旦漂移，前端会放行一个后端必拒的文件（或反之），且都是静默的。
// 权威面 = 后端 `AttachContract.MaxFileBytes` / `MaxFileBytesLabel`（本处为镜像）。
const ATTACH_MAX_FILE_BYTES = 1073741824;
const ATTACH_MAX_PER_MESSAGE = 9;
const ATTACH_MAX_FILE_LABEL = '1024 MB = 1 GiB (1,073,741,824 bytes)';
// attachcl 批（2026-09-16）：三个常量**导出**给好友/群窗的上传链
// （`attachUpload.js` 的本地早拒闸）。值/用法零改动 —— 设备面仍读本模块内的同名
// 常量，两个面因此共用**同一个**上限来源（禁两处各写一个数）。
export { ATTACH_MAX_FILE_BYTES, ATTACH_MAX_PER_MESSAGE, ATTACH_MAX_FILE_LABEL };

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

/** ⑩ 本地缓存 → 内存工作集。**只在内存为空时灌**：已有活体数据（本次会话收到的
 *  消息）绝不被缓存回退覆盖 —— 缓存是首帧来源，不是真相来源。
 *  @returns {boolean} 是否真的灌入了缓存 */
function hydrateFromCache(deviceId) {
  if ((dropboxMessages[deviceId] || []).length > 0) return false;
  const cached = loadDeviceMessages(deviceId);
  if (!cached || cached.length === 0) return false;
  dropboxMessages[deviceId] = cached;
  return true;
}

export function openDropbox(device) {
  openDeviceId = device.deviceId;
  // ⑩ stale-while-revalidate 的**顺序即契约**：本地缓存渲染必须发生在
  // `dropbox-get-history` 出帧**之前**。顺序一旦反过来，首帧就又挂回网络腿。
  if (!device.isLocal) {
    hydrateFromCache(device.deviceId);
    historyPending.add(device.deviceId);
  }
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
  // 作者 2026-09-14 09:14 令：删掉引导句（dropbox 拖拽提示那一句，中英双语）。
  // —— **只删文案，功能零变化**：拖拽目标仍是整个对话框（`.dropbox-modal` 的
  // dragover/dragleave/drop 三监听，见 bindChatEvents），纸夹键与粘贴两条路径不动。
  // 该句唯一的宿主 = 原 dropzone 提示条元素，随本次一并整体删除 ⇒ 无死 key
  // （i18n 双语 entry 同批删）+ 无死 CSS（原提示条规则同批删）。
  const chatHtml = hasChat ? `
    <div class="dropbox-tab-content active" id="dropbox-tab-chat">
      <div class="dropbox-messages" id="dropbox-messages"></div>
      <div class="dropbox-input-bar">
        <input type="text" id="dropbox-text-input" class="cfg-input" placeholder="${t('dropbox.inputPlaceholder')}" autocomplete="off">
        <button id="dropbox-attach-btn" class="icon-btn dropbox-attach-btn" title="${t('dropbox.attachFile')}" aria-label="${t('dropbox.attachFile')}"><i data-lucide="paperclip"></i></button>
        <button id="dropbox-send-btn" class="cfg-btn cfg-btn-primary">${t('dropbox.send')}</button>
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

  // 🔴 件 1 真因面（2026-09-14 作者报「附件的按钮不可见」）：图标渲染必须显式触发。
  // 本模态是**运行时**创建的 DOM，而全局的 `lucide.createIcons()`（main.js:269）只在
  // 启动时扫一次静态 DOM —— 模态里的 `<i data-lucide="paperclip">` 从未被替换成 `<svg>`。
  // 后果不是「图标缺失」而是**整键不可见**：`.icon-btn` 是 `background:none` +
  // `border:1px solid transparent` + `color:var(--color-frame-text-muted)`，唯一的可见
  // 像素来自 svg 的 `currentColor` 描边 ⇒ 空 `<i>` 下留一个 32×32 全透明热区。
  // 同族先例：messages.js 的好友窗（renderChatModal 末尾 `createIconsIn(overlay)`）、
  // daemons/contacts/scheduled-task 等所有运行时面板都显式调它。
  createIconsIn(overlay);

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

/** 设备描述**写路径单点**（⑤c，作者 2026-09-15）。
 *
 *  🔴 本函数是描述落库的**唯一实现**，两个调用点共用（禁两套并存）：
 *   · 本文件的旧设备窗编辑器（`bindDescEditor`，随 ① 已不可达，保留为同函数第二调用点）；
 *   · 设备会话窗的行内编辑器（`messages.js` `startDeviceDescEdit`）——本令要求
 *     「给设备添加描述的地方」搬进对话框，写路径不搬第二份。
 *  端点：本机 ⇒ `PUT /api/neblink/device-info`；远端 ⇒ `PUT /api/neblink/peer-description`
 *  （两者皆为既有端点，**零新增服务面**）。非 2xx ⇒ 抛错（调用方给可见失败反馈；
 *  旧实现静默吞掉状态码，是「看着保存了、其实没保存」的温床）。
 *  @param {{deviceId?: string, isLocal?: boolean}} device
 *  @param {string} userDescription
 */
export async function saveDeviceDescription(device, userDescription) {
  const desc = String(userDescription == null ? '' : userDescription);
  const token = getAuthToken();
  const isLocal = !!(device && device.isLocal);
  const url = isLocal ? '/api/neblink/device-info' : '/api/neblink/peer-description';
  const body = isLocal
    ? { userDescription: desc }
    : { deviceId: device && device.deviceId, userDescription: desc };
  const res = await fetch(url, {
    method: 'PUT',
    headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (!res.ok) throw new Error(`device description PUT ${res.status}`);
  // Refresh neblink state so the description propagates to every name surface
  // (设备窗窗头 / 消息面板设备行 / 联系人面板设备行 —— 全部经 `deviceLabel` 单点)。
  refreshNeblink();
}

function bindDescEditor(device) {
  const saveBtn = document.getElementById('dropbox-desc-save');
  const input = document.getElementById('dropbox-desc-input');

  const doSave = async () => {
    const desc = input.value.trim();
    const originalText = saveBtn.textContent;
    try {
      await saveDeviceDescription(device, desc); // 🔴 单点写路径（与 messages.js 共用）
      saveBtn.textContent = '✓';
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
  const sendBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('dropbox-send-btn'));
  const textInput = /** @type {HTMLInputElement | null} */ (document.getElementById('dropbox-text-input'));
  const attachBtn = document.getElementById('dropbox-attach-btn');
  const fileInput = /** @type {HTMLInputElement | null} */ (document.getElementById('dropbox-file-input'));
  // HTMLElement（非 Element）标注：drag/drop 事件类型只能从 HTMLElementEventMap
  // 解析出来，否则回调参数退化成 Event（dataTransfer/relatedTarget 不可见）。
  const modal = /** @type {HTMLElement | null} */ (document.querySelector('.dropbox-modal'));

  // ③ 同病同修（2026-09-14 交付批）：本键的「可否提交」判据 = 输入非空。
  // 旧形态 = 按钮恒呈可用态、而空/纯空格输入点了**静默 no-op**（同族反极性
  // 缺陷：看着能发、实际发不出去且零反馈）。现在按钮态与 Enter 路径同闸。
  // ③ 发送键族统一批（2026-09-15）追加：挂 `.disconnected`（掉线灰）——判据与主
  // 对话框同族（`!state.connected`，唯一真源 = `ws.js` 的连接态回调），本键与
  // 会话窗键同档（`sapphire.css` 发送族块）。
  const syncSendState = () => {
    if (!sendBtn || !textInput) return;
    sendBtn.disabled = !textInput.value.trim();
    sendBtn.classList.toggle('disconnected', !connUp);
  };

  // Send text
  const sendText = () => {
    if (!textInput) return;
    const text = textInput.value.trim();
    if (!text) return;
    sendWs({ type: 'dropbox-send-text', deviceId: device.deviceId, text });
    textInput.value = '';
    syncSendState();
  };

  if (sendBtn) sendBtn.onclick = sendText;
  if (textInput) {
    textInput.addEventListener('input', syncSendState); // 空 ↔ 非空即时同步（禁两态分叉）
  }
  syncSendState(); // 初态：空输入 ⇒ 禁用（不再「看着可点」）
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
 * 用户选/拖了文件 —— **闸位在本地先判一次**（件数 ≤9、单件 ≤1 GiB、非空件），
 * 超限**可见拒绝并回显实际值**（禁静默丢弃、禁只 console.error）。
 * 后端闸位仍在（本地闸只是提前反馈，不是唯一防线）。
 *
 * 📌 uxconsist Phase B（§7 清扫 #1/#2 + §5 R3-device 行）：闸位判据**三面同源** ——
 * 本腿改调 `attachUpload.js` 的 `validateFiles`（**同一**实现，常量本就同源；补上
 * 「空件」判据），拒绝面在**新窗**就地可见（卡行）+ 旧 dropbox 窗保留既有台账 notice。
 * 动态 `import()` = 本仓既有的**破环**手法（本模块 ↔ attachUpload.js 的静态边会成 SCC，
 * 见 `scripts/check-circular.mjs`；attachUpload.js 侧对 messages.js 用的是同一手法）。
 * @returns {Promise<void>}
 */
async function handleFilesSelected(deviceId, fileList) {
  const files = Array.from(fileList || []);
  if (files.length === 0) return;

  const { validateFiles } = await import('./attachUpload.js');
  const gate = validateFiles(files);
  if (!gate.ok) {
    const names = files.map(f => (f && f.name) || '').filter(Boolean);
    emitTransfer({
      phase: 'rejected', deviceId, id: nextTransferLocalId(),
      // 名字只取**实际件名**（不新造「N 件」这类文案：文案白名单外零新增，§8）。
      name: names[0] || '', size: (files[0] && files[0].size) || 0,
      files: files.length === 1 ? [files[0]] : [], // 单件才给「重选同件」的重试面（件数超限重试必然再拒）
      message: gate.message, code: 'gate',
    });
    showDropboxNotice(deviceId, gate.message); // 旧窗台账 notice（§7 #2 保留档）
    return;
  }

  // offer 失败路径 ②（R6）：`sendWs` 在 socket 非 OPEN 时**静默丢弃**（ws.js:275-279）——
  // 那样 offer 从未发出、也就永远不会有 `dropboxError` 回来，队列会**永久残留**。
  // 故先探活再入队；未连接 ⇒ 不入队 + 可见提示。
  if (!(state.ws && state.ws.readyState === 1 /* WebSocket.OPEN */)) {
    clearPendingFileQueue(deviceId);
    const text = t('dropbox.notConnected');
    emitTransfer({
      phase: 'rejected', deviceId, id: nextTransferLocalId(),
      name: (files[0] && files[0].name) || '', size: (files[0] && files[0].size) || 0,
      files: files.length === 1 ? [files[0]] : [], message: text, code: 'not_connected',
    });
    showDropboxNotice(deviceId, text);
    return;
  }

  // 逐件建台账（S1 排队行 = 本腿的**显示态**源）+ 既有 FIFO 语义逐字不变。
  const items = files.map(f => trackTransfer(deviceId, f));
  pendingFileQueues[deviceId] = (pendingFileQueues[deviceId] || []).concat(files);
  for (const item of items) {
    emitTransfer({
      phase: 'queued', deviceId, id: item.id,
      name: item.file.name, size: item.file.size, file: item.file,
    });
  }
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
  // ⑩ 新到消息：走合并路径（非钉底）——阅读中的用户不被推走；已在底部则自然跟随。
  afterDeviceMessageChange(deviceId);
}

// ===== Message rendering =====

/** 渲染签名：**只有会改变已渲染外观的字段**参与。
 *  签名相同 ⇒ 复用同一节点对象（身份保持 / 无闪 / 滚动不跳 / 热区不重绑）；
 *  签名不同（文件传输状态推进、进度推进）⇒ 只重建**那一条**，不是全量重建。 */
function msgSignature(m) {
  return [
    m.kind, m.direction, m.ts, m.text, m.status, m.fileName, m.fileSize,
    m.savedPath, m.bytesReceived, m.totalBytes, m.downloadedBytes,
  ].map(v => (v === undefined || v === null ? '' : String(v))).join('\u0001');
}

/** 复制键热区：**建节点时绑一次**（不再每次渲染后全容器重扫重绑）。 */
function bindCopyButton(btn) {
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
}

/** 单条消息 → 元素节点（模板仍是 `renderMessage` 的 HTML **单源**，无第二份）。 */
function messageNode(m) {
  const tpl = document.createElement('div');
  tpl.innerHTML = renderMessage(m);
  const node = /** @type {HTMLElement | null} */ (tpl.firstElementChild);
  if (!node) return null;
  node.dataset.msgId = String(m.msgId);
  node.dataset.renderSig = msgSignature(m);
  // 时制热区：与主对话框/好友对话框共享同一偏好与同一实现（timeFormat.js）；
  // `bindTimeToggle` 以 `data-time-toggle-bound` 幂等。
  node.querySelectorAll('.dropbox-msg-time').forEach(bindTimeToggle);
  const copy = /** @type {HTMLElement | null} */ (node.querySelector('.dropbox-msg-copy'));
  if (copy) bindCopyButton(copy);
  return node;
}

/**
 * ⑩-E keyed diff 渲染：按 `msgId` 复用既有气泡节点，只增删差集、只移动错位节点
 * —— 不再 `innerHTML=''` 全量重建。因此 `dropbox-history` 回包与增量消息到达时
 * **已渲染节点身份保持**（节点引用不变、无全量重建、滚动位不重置）。
 *
 * 不变量：消息节点按 `msgs` 顺序排列，状态行（若有）恒为**首个子节点**，
 * 且每个 `msgId` **至多一个**节点。
 */
function keyedDiffMessages(container, msgs) {
  /** @type {Map<string, HTMLElement>} */
  const existing = new Map();
  for (const node of [...container.children]) {
    const e = /** @type {HTMLElement} */ (node);
    if (e.classList && e.classList.contains('dropbox-flow-status')) continue;
    const id = e.dataset && e.dataset.msgId;
    if (id !== undefined && id !== null && id !== '') existing.set(String(id), e);
  }
  const wanted = new Set(msgs.map(m => String(m.msgId)));
  for (const [k, node] of [...existing]) {
    if (!wanted.has(k)) { node.remove(); existing.delete(k); }
  }
  const status = /** @type {HTMLElement | null} */ (container.querySelector('.dropbox-flow-status'));
  let cursor = status || null;
  for (const m of msgs) {
    const k = String(m.msgId);
    const sig = msgSignature(m);
    let node = existing.get(k) || null;
    if (node && node.dataset.renderSig !== sig) {
      const fresh = messageNode(m);
      if (!fresh) { existing.delete(k); continue; }
      node.replaceWith(fresh);
      node = fresh;
    } else if (!node) {
      node = messageNode(m);
      if (!node) continue;
    }
    existing.delete(k);
    const after = cursor ? cursor.nextSibling : container.firstChild;
    if (node !== after) container.insertBefore(node, after);
    cursor = node;
  }
}

/**
 * 消息区状态行 —— **消息区永不为空**（作者令「打开应该能够直接显示，而不是空白」）。
 * 只有**真正没有本地缓存**的设备才会看到它：有缓存时首帧就是消息本身。
 * 加载态**只**用于「无缓存且请求在飞」这一种情形，绝不用来掩盖有缓存却慢的路径。
 */
function renderStatusRow(container, deviceId, hasMsgs) {
  container.querySelector('.dropbox-flow-status')?.remove();
  if (hasMsgs) return;
  const row = document.createElement('div');
  row.className = 'dropbox-flow-status';
  if (historyPending.has(deviceId)) {
    row.classList.add('loading');
    row.setAttribute('aria-busy', 'true');
    row.textContent = t('dropbox.loadingMessages');
  } else {
    row.classList.add('empty');
    row.textContent = t('dropbox.noMessages');
  }
  container.appendChild(row);
}

function renderMessages(deviceId, { stickBottom = true } = {}) {
  const container = document.getElementById('dropbox-messages');
  if (!container) return;

  const msgs = dropboxMessages[deviceId] || [];
  const apply = () => {
    keyedDiffMessages(container, msgs);
    renderStatusRow(container, deviceId, msgs.length > 0);
  };
  if (stickBottom) {
    // 开窗首帧（缓存/空态）：直接钉到底部。
    apply();
    container.scrollTop = container.scrollHeight;
    return;
  }
  // 合并/增量路径：以**可视锚点**为准补偿，阅读位置不被推走（作者令「不跳动」）。
  // 🔴 不能再走「scrollTop += 新高−旧高」：Dropbox 的增量加在**下方**，该公式会把
  //    正在阅读的用户整体下移一个增量高度（实测 scrollTop 0 → 433、锚点 dm-0 → dm-4）。
  preserveScrollAnchor(container, apply, 'msgId');
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
          <span class="dropbox-msg-divider"></span>
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

/** ⑩ 回包 → 就地收敛（stale-while-revalidate 的 revalidate 腿）。
 *
 *  帧是**全量历史**（网关 `DropboxService.getHistory` 内存直读该设备全量），
 *  故帧定义**集合与顺序**；缓存里帧未携带的条目 = 服务端已不存在的消息，
 *  按「服务端是真相来源」丢弃。
 *
 *  但**已渲染节点的身份必须保持**：命中同一 `msgId` 的既有对象**就地更新**
 *  （`Object.assign`，不换引用）并保留其在数组中的原序 —— 这才让 `keyedDiffMessages`
 *  的签名比较落到「没变 ⇒ 复用同一 DOM 节点」上，而不是「全量重建」。
 *  @returns {any[]} 收敛后的工作集
 */
function mergeDeviceMessages(deviceId, incoming) {
  const prev = dropboxMessages[deviceId] || [];
  /** @type {Map<string, any>} */
  const byId = new Map();
  for (const m of prev) {
    const k = (m && m.msgId !== undefined && m.msgId !== null) ? String(m.msgId) : '';
    if (k && !byId.has(k)) byId.set(k, m);
  }
  /** @type {any[]} */
  const next = [];
  const seen = new Set();
  for (const m of incoming) {
    if (!m || m.msgId === undefined || m.msgId === null) continue;
    const k = String(m.msgId);
    if (seen.has(k)) continue; // 帧内去重（保留首条，保序）
    seen.add(k);
    const old = byId.get(k);
    if (old && old !== m) { Object.assign(old, m); next.push(old); }
    else next.push(m);
  }
  dropboxMessages[deviceId] = next;
  return next;
}

export function initDropbox() {
  // ③ 发送键族统一批：掉线 ⇒ 已开着的 dropbox 窗发送键转灰（与主对话框/会话窗同档）；
  // 重连 ⇒ 回绿。两处只改本键的链路态类，功能闸（空正文）仍由 syncSendState 单源管。
  onDisconnect(() => {
    connUp = false;
    const b = /** @type {HTMLButtonElement | null} */ (document.getElementById('dropbox-send-btn'));
    if (b) b.classList.add('disconnected');
  });
  onReconnect(() => {
    connUp = true;
    const b = /** @type {HTMLButtonElement | null} */ (document.getElementById('dropbox-send-btn'));
    if (b) b.classList.remove('disconnected');
  });
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
        const nextFile = queue.shift();
        pendingUploads[m.transferId] = nextFile;
        // selfattach 批 · 片 2（A 腿）：**同处**登记发送侧真句柄 —— 位置**先于**下方
        // `afterDeviceMessageChange` ⇒ 本行**首帧渲染即带句柄**（作者令要求「首帧即带」）。
        rememberOutFileHandle(m.transferId, pendingUploads[m.transferId]);
        // uxconsist Phase B：**同一次 FIFO 消费**里锚上台账（两表不可能各锚一件）。
        bindTransferWireId(deviceId, m.transferId, nextFile);
        if (queue.length === 0) delete pendingFileQueues[deviceId];
      }
    }

    afterDeviceMessageChange(deviceId);
    // ⑩ 增量落盘：下次开窗的首帧来源。写入按每设备上限截尾（fmDropboxCache 单一落点）。
    saveDeviceMessages(deviceId, dropboxMessages[deviceId] || []);
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
    // 卡面（新窗 = 本批可见面）文案 = **§8 白名单键**：闸位码取同族键，其余（含服务端
    // 自由文本 `msg.error`）一律落 `messages.attachFailed` —— 🔴 自由文本只留在旧窗台账
    // notice 上，禁进卡面（白名单外零新增 / 零透传）。
    let cardText = t('messages.attachFailed');
    if (detail.code === 'ATTACH_TOO_MANY') {
      text = t('dropbox.tooManyFiles')
        .replace('{actual}', String(detail.actual))
        .replace('{limit}', String(detail.limit));
      cardText = text;
    } else if (detail.code === 'ATTACH_TOO_LARGE') {
      text = t('dropbox.fileTooLarge')
        .replace('{name}', '')
        .replace('{actual}', formatSize(detail.actual))
        .replace('{actualBytes}', String(detail.actual))
        .replace('{limit}', ATTACH_MAX_FILE_LABEL);
      cardText = text;
    }
    if (deviceId) showDropboxNotice(deviceId, text);
    else console.error('[dropbox]', text);
    // 未对号的排队件一并翻「失败」（否则永远停在「排队中」）：可见面 = 新窗卡行（卡面文案
    // 用三面同款 `messages.attachFailed` / 闸位同族键，`text` 仍留在旧窗台账 notice 上）。
    if (deviceId) failQueuedTransfers(deviceId, cardText, detail.code || 'offer_failed');
  });

  // File offer accepted/rejected (sender side)
  onMessage('dropbox-file-response', (msg) => {
    const inner = msg.msg || {};
    const { transferId, accepted } = inner;
    const localId = transferIdIndex.get(transferId) || '';
    if (accepted && pendingUploads[transferId]) {
      uploadFile(transferId, pendingUploads[transferId]);
    } else if (!accepted) {
      delete pendingUploads[transferId];
      // 卡面文案 = §8 白名单键（门禁 `check-msgstyle-single-source` 逐键对表）。
      failQueuedTransfers(msg.deviceId, t('messages.attachFailed'), 'rejected');
    }
    if (localId) {
      const item = deviceTransfers.get(localId);
      if (item && item.state === 'queued') item.state = accepted ? 'transferring' : 'failed';
      emitTransfer({
        phase: accepted ? 'transferring' : 'failed',
        deviceId: msg.deviceId, id: localId, transferId,
        name: (item && item.file && item.file.name) || '',
        size: (item && item.file && item.file.size) || 0,
        // `file` 在场 ⇒ 失败卡的重试键（§5 R3/R4-device：重试 = 新传输，如实声明）。
        file: (item && item.file) || null,
        message: accepted ? '' : t('messages.attachFailed'), code: accepted ? '' : 'rejected',
      });
    }
    updateFileMessageStatus(msg.deviceId, transferId, accepted ? 'accepted' : 'rejected');
  });

  // File transfer completed/failed (both sides)
  onMessage('dropbox-file-complete', (msg) => {
    const inner = msg.msg || {};
    const { transferId, success, savedPath } = inner;
    // 🔴 只删「待上传队列」那半（上传交接已完成）——发送侧**句柄表**（`outFileHandles`）
    //    不在此删除：完成态的卡正是要用它取字节（selfattach 批 · 片 2）。
    delete pendingUploads[transferId];
    const localId = transferIdIndex.get(transferId) || '';
    if (localId) {
      const item = deviceTransfers.get(localId);
      if (item && item.state !== 'cancelled') item.state = success ? 'sent' : 'failed';
      emitTransfer({
        phase: success ? 'sent' : 'failed', deviceId: msg.deviceId, id: localId, transferId,
        name: (item && item.file && item.file.name) || '',
        size: (item && item.file && item.file.size) || 0,
        file: (item && item.file) || null, // 失败卡重试键（§5 R3/R4-device）
        message: success ? '' : t('messages.attachFailed'), code: success ? '' : 'transfer_failed',
      });
    }
    const msgs = dropboxMessages[msg.deviceId] || [];
    const m = msgs.find(x => x.transferId === transferId);
    if (m) {
      m.status = success ? 'completed' : 'failed';
      if (savedPath) m.savedPath = savedPath;
      afterDeviceMessageChange(msg.deviceId);
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
      afterDeviceMessageChange(msg.deviceId);
    }
    // S3 进度 emission（§3.3）：**唯一**进度来源仍是网关「服务端已确认一块」的帧
    // （禁本地假进度）⇒ 新窗卡上的数值与本帧同拍。
    const localId = transferIdIndex.get(transferId) || '';
    if (localId) {
      const item = deviceTransfers.get(localId);
      if (item && item.state === 'transferring') {
        emitTransfer({
          phase: 'progress', deviceId: msg.deviceId, id: localId, transferId,
          bytesSent: Number(bytesReceived) || 0, totalBytes: Number(totalBytes) || 0,
        });
      }
    }
  });

  // Message history response —— ⑩ **增量合并**，不再整体替换（整体替换 ⇒ 全量重绘）。
  // 已渲染的节点身份、滚动位、复制/时间热区全部保持；回包只把差集补进当前视图。
  onMessage('dropbox-history', (msg) => {
    const deviceId = msg.deviceId;
    historyPending.delete(deviceId);
    mergeDeviceMessages(deviceId, msg.messages || []);
    afterDeviceMessageChange(deviceId);
    saveDeviceMessages(deviceId, dropboxMessages[deviceId] || []);
  });
}

// ===== Helpers =====

function updateFileMessageStatus(deviceId, transferId, status) {
  const msgs = dropboxMessages[deviceId] || [];
  const m = msgs.find(x => x.transferId === transferId);
  if (m) {
    m.status = status;
    afterDeviceMessageChange(deviceId);
  }
}

/** 上行（不改任何 wire 形态）。失败面自 uxconsist Phase B 起**可见化**（§3.3 +
 *  §8.2 改写③）：改前失败**只**进控制台（`console.error` 单一面 ⇒ 用户在屏上零反馈），
 *  现在同时 emit `failed` ⇒ 新窗卡行显示 `messages.attachFailed`（三面同款文案）。
 *  `console.error` 保留 = 诊断面（不再承担用户面）。 */
async function uploadFile(transferId, file) {
  const localId = transferIdIndex.get(transferId) || '';
  const item = localId ? deviceTransfers.get(localId) : null;
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  if (item) item.controller = controller;
  const failFace = (code, detail) => {
    console.error('[dropbox] Upload failed:', detail);
    if (item && item.state !== 'cancelled') {
      item.state = 'failed';
      emitTransfer({
        phase: 'failed', deviceId: item.deviceId, id: item.id, transferId,
        name: item.file.name, size: item.file.size,
        file: item.file, // 失败卡重试键（§5 R3/R4-device：重试 = 重走入队的新传输）
        message: t('messages.attachFailed'), code,
      });
    }
  };
  try {
    const token = getAuthToken();
    const resp = await fetch(`/api/neblink/dropbox/upload/${transferId}`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` },
      body: file,
      ...(controller ? { signal: controller.signal } : {}),
    });
    const data = await resp.json();
    if (!data.ok) failFace(data.code || 'upload_failed', data.error);
  } catch (e) {
    if (item && item.state === 'cancelled') return; // 用户主动取消 ⇒ 终态已定，不覆盖
    failFace('network', e);
  }
}

// ===== 设备会话统一批 MVP-1（2026-09-15）：新窗（messages.js 面）的数据面 =====
// 设计卡 §6.2 的「复用单点清单」：新窗复用 messages.js 的窗骨架与渲染管线
// （`renderMessages → keyedDiff → bubbleEl → fillBubble`）；**设备腿的数据面**
// （WS 帧 `dropbox-history`/`dropbox-message` + L2 缓存 `fmDropboxCache` + 附件队列/闸位）
// **仍由本模块唯一属主**。新窗因此只经下面这组访问器取数/送件，不复制任何取数、
// 合并（`mergeDeviceMessages`）、落盘（`saveDeviceMessages`）或闸位逻辑 —— 否则就是
// 设计卡点名的「第三实现」。旧窗（本模块的 dropbox 模态，含**设备描述编辑器**）一并
// 保留到 MVP-3（卡 §7.1：描述编辑器不得随聊天窗删除）。

const deviceMessageSubscribers = /** @type {Set<(deviceId: string) => void>} */ (new Set());

/** 设备消息集/传输态变更后：旧窗重渲（既有语义不动）+ 通知新窗订阅者。 */
function afterDeviceMessageChange(deviceId, stickBottom = false) {
  if (deviceId === openDeviceId) renderMessages(deviceId, { stickBottom });
  for (const cb of [...deviceMessageSubscribers]) {
    try { cb(deviceId); } catch (e) { console.error('[dropbox] device message subscriber failed:', e); }
  }
}

/** 新窗订阅（返回注销函数）：**只做通知**；取数一律走 `deviceMessagesOf`。 */
export function onDeviceMessageChange(cb) {
  deviceMessageSubscribers.add(cb);
  return () => deviceMessageSubscribers.delete(cb);
}

/** 某设备的设备消息工作集（**本模块唯一属主**；调用方禁就地改结构）。 */
export function deviceMessagesOf(deviceId) {
  return dropboxMessages[deviceId] || [];
}

/** 「与它通信过」的**本地半程**判据（⑥，作者 2026-09-15：「要跟设备通信过再出现在
 *  消息面板里」）。
 *
 *  🔴 **唯一实现**（调用方禁自行读 L2 / 禁自行判两层）：证据 = 内存工作集非空
 *  **∨** L2 缓存非空。后者是跨会话的那一半 —— 只读内存会把「上次会话聊过、本次
 *  尚未开窗」的设备误判成「没通信过」（重登/刷新后设备行凭空消失）。
 *  服务端会话行在场 = 另一半证据，由 `messages.js` 的 `deviceConvs` 自判（两半取或）。
 *  @param {string} deviceId
 *  @returns {boolean} */
export function deviceHasLocalTraffic(deviceId) {
  if (!deviceId) return false;
  if ((dropboxMessages[deviceId] || []).length > 0) return true;
  const cached = loadDeviceMessages(deviceId);
  return !!(cached && cached.length > 0);
}

/** 开窗顺序契约（与旧窗 `openDropbox` 同：本地缓存渲染先于出帧）。返回是否灌入缓存。 */
export function hydrateDeviceCache(deviceId) {
  return hydrateFromCache(deviceId);
}

/** 请求全量历史（`dropbox-get-history`）；帧到达经 `onDeviceMessageChange` 通知。 */
export function requestDeviceHistory(deviceId) {
  if (!deviceId) return;
  historyPending.add(deviceId);
  sendWs({ type: 'dropbox-get-history', deviceId });
}

/** 无缓存且请求在飞 ⇒ 新窗显示加载态（判据与旧窗同源 = `historyPending`）。 */
export function deviceHistoryPending(deviceId) {
  return historyPending.has(deviceId);
}

/** 发送文本（人发 ⇒ 网关侧 origin = 'user'；agent 腿不经此路径）。 */
export function sendDeviceText(deviceId, text) {
  sendWs({ type: 'dropbox-send-text', deviceId, text });
}

/** 附件发送入口（卡 D3：设备面**保留**纸夹 + 拖拽）——闸位/队列/offer 全在本模块单点。
 *  （Phase B 起闸位判据经动态 import 走三面同源实现，故本入口是 async 的 fire-and-forget。） */
export function sendDeviceFiles(deviceId, fileList) {
  void handleFilesSelected(deviceId, fileList);
}
