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
// ── 幂等键（attachkey 批）：**唯一生成点复用**，禁第二套 ────────────────────
// 作者 2026-09-17 裁定 A：附件腿**不**自建生成器、**不**做独立判重 —— 直接取 P2-b 在
// `messages.js` 铸键的**同一单点**（`newClientMsgId`，本批按裁定 A 给它加 `export`，
// 该文件字面 diff 恰 1 处 `-`/`+`）。
// 🔴 cired 批 2026-09-17（破环）：该单点**不再用静态 import 取**，改为 `sendFiles`
// 调用点的动态 `import()`（`check-circular.mjs` 的 SCC [2] messages.js↔attachUpload.js
// ⇒ 本边是**最薄**的一条：导出的绑定只有 1 个、且只在运行时读；反向边 messages.js
// 从本文件取 5 个绑定且在渲染路径同步消费 ⇒ 薄边选本边，改面最小）。行为零变：
// 生成点仍是 `messages.js` 的同一个 `newClientMsgId`；`sendFiles` 是 async 动作边界，
// 调用前 messages.js 必已完成实例化与求值（它正是本模块的导入方），故动态 import
// 只是读已求值的同一命名空间 —— 不新起模块实例、不重跑求值、无新请求。
// 历史：先前的静态边安全依据（运行时读、非实例化期读）见
// `.nebflow/evidence/20260917_attachkey-impl/esm-cycle.txt`（该依据随本改动作废，
// 本条改由「静态边消失」承担同一目标）。

/** 上传条状态（渲染判据单点；字符串进 `data-upload-state` 供 QA 断言）。
 *  📌 uxconsist Phase B（S1 排队）：`QUEUED` 进**同一**状态常量表 —— 件集在动作入口
 *     全量登记为排队行，轮到本件才转 `UPLOADING`（§5 R8 三格；队列 = 既有**串行**语义的
 *     可视化，不改任何传输行为）。设备腿的排队行经同一渲染器出卡（`registerExternalTransfer`）。 */
export const UPLOAD_STATE = {
  QUEUED: 'queued',
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

/** convId → **可重试提示**记录数组（(c) 臂的工作集；一个失败**动作**一条）。 */
const retryHintsByConv = new Map();
function convRetryHints(convId) {
  if (!retryHintsByConv.has(convId)) retryHintsByConv.set(convId, []);
  return retryHintsByConv.get(convId);
}
/** 可重试提示快照（断言用，与 [[uploadsOf]] 同款只读出口）。 */
export function retryHintsOf(convId) {
  return convRetryHints(convId).slice();
}

function paint() {
  if (!mounted) return;
  const { container, convId } = mounted;
  container.innerHTML = '';
  const items = convUploads(convId);
  const hints = convRetryHints(convId);
  // 空判据 = **实际挂上的卡**与提示都没有（有提示时容器必须可见，否则重试入口不可达）。
  // 🔴 按「挂上的卡数」而非 `items.length`：quiet 腿的发送中/成功态**不出卡**
  //    （见 [[uploadCardEl]]）⇒ 若按条目数判空，容器会以**可见的空壳**留在消息流里
  //    （`.fm-upload-list` 有 `margin: 0 16px 6px`、`.fm-flow` 有 `gap: 10px` ⇒ 凭空 16px）。
  container.hidden = true;
  let cards = 0;
  for (const item of items) {
    const card = uploadCardEl(item);
    if (!card) continue;
    container.appendChild(card);
    cards += 1;
  }
  for (const hint of hints) container.appendChild(uploadRetryEl(hint));
  container.hidden = cards === 0 && hints.length === 0;
}

function uploadCardEl(item) {
  // ── 「正在发送」条幅**整条退场**（sendstate 批 · 作者设计令 2026-09-18）──────────
  // 作者设计令（逐字）：「不要出现正在发送的条幅，改为在气泡左侧，用spinner来显示
  // 状态，发送失败就是❌，类似于微信的做法。」
  // 本函数是那条条幅（quiet 上传卡）的**唯一装配点** ⇒ 退场在这里落地：
  //   · `UPLOADING` ⇒ **不出卡**：状态改由**气泡左侧环**承载（`.fm-msg.fm-sending::before`，
  //     随乐观气泡同一时点上屏）；旧卡上的「正在发送」文案与取消键**随之退场**；
  //   · `SENT` ⇒ **同样不出卡**：成功态语义 = **无痕**（确认面接管后不留「已发送」残条，
  //     与 `messages.js::settleOptimisticAttach` 摘 `fm-sending` 同属一件事）；
  //   · **终态（FAILED / CANCELLED / SKIPPED）照旧出卡**（下方既有分支）—— fail-closed 的
  //     「未发送 / 真原因」文案**一字不动**：🔴 图片上传失败时屏上的**流内失败卡就是它**，
  //     本批对该面零触碰（干跑帧 09/10 逐字节相同即此意）。
  // C 修正令（逐字）：「取消发送，使用右键取消。」⇒ 旧卡上的取消键随卡退场；🔴 本批
  // **不新增任何取消 UI**（气泡 hover / 右键菜单 / composer 一律不加），撤销能力由
  // **右键菜单批**承接（临时状态已在批次报告与人读件显式登记）。🔴 不得在此补位。
  // `quiet` 的唯一来源 = 好友**图片乐观腿**（`messages.js::sendAttachCurrent` 的
  // `quiet: true`）；其余腿（群窗 / 混批 / 非图片 / 设备面）逐字走下方既有分支 ⇒ 零行为变化。
  if (item.quiet && (item.state === UPLOAD_STATE.UPLOADING || item.state === UPLOAD_STATE.SENT)) return null;
  const card = el('div', `fm-upload fm-upload-${item.state}`);
  card.dataset.uploadState = item.state;
  card.dataset.uploadId = item.uploadId;
  card.dataset.uploadName = item.name;
  card.dataset.uploadBytesSent = String(item.bytesSent || 0);
  card.dataset.uploadTotalBytes = String(item.totalBytes || item.size || 0);
  if (item.code) card.dataset.uploadCode = item.code;

  card.appendChild(el('span', 'fm-upload-name', item.name));
  card.appendChild(el('span', 'fm-upload-size', fmtBytes(item.size)));

  // 🔴 此处原为 quiet 腿（图片乐观腿）的**发送中条幅**装配分支（「正在发送」文案 +
  //    取消键）——已按作者设计令**整条删除**（含其 i18n 键 `messages.attachSending`）。
  //    发送中/成功态在函数头 `return null`（不出卡），终态走下方既有分支。

  // 进度条**只在真实推进中出现**（宽度 = 服务端已确认的字节 / 总字节）。
  const total = item.totalBytes || item.size || 0;
  const pct = total > 0 ? Math.max(0, Math.min(100, Math.round(((item.bytesSent || 0) / total) * 100))) : 0;
  if (item.state === UPLOAD_STATE.QUEUED) {
    // ── S1 排队（uxconsist Phase B · §5 R8 三格）────────────────────────────
    // 说明行 = 复用既有 `input.queued`；**零新 CSS 规则**（同一 `.fm-upload` /
    // `.fm-upload-note` 族，队列态只多 `data-upload-state="queued"` 一个钩子）。
    // 取消键 = 仅**外部腿**（设备，`onCancel` 在场）挂 —— 其语义 = 「未 offer 件移出
    // FIFO」（§5 R7-device）；网页腿（好友 / 群）的件集在第一个 await 前**已开始**串行
    // 上传，其取消面仍是既有 `UPLOADING` 档那一枚键（逐字不变，禁在此新增第二套）。
    card.appendChild(el('span', 'fm-upload-note', t('input.queued')));
    if (typeof item.onCancel === 'function') appendCardCancel(card, item);
  } else if (item.state === UPLOAD_STATE.UPLOADING) {
    const track = el('div', 'fm-upload-progress');
    const bar = el('div', 'fm-upload-progress-bar');
    bar.style.width = `${pct}%`;
    track.appendChild(bar);
    card.appendChild(track);
    card.appendChild(el('span', 'fm-upload-note', t('messages.attachProgress').replace('{pct}', String(pct))));
    appendCardCancel(card, item);
  } else if (item.state === UPLOAD_STATE.SENT) {
    card.appendChild(el('span', 'fm-upload-note', t('messages.attachSent')));
  } else {
    // 失败 / 取消 / 未发（fail-closed 可见：**终态文案就在卡上**，不靠 toast 兜底）。
    card.appendChild(el('span', 'fm-upload-note', item.error || t('messages.attachFailed')));
    // ── S4/S8 后的重试键（§5 R3-device + R4-device）──────────────────────
    // 仅在**外部腿**（设备，`onRetry` 在场）挂：设备附件腿无幂等键面 ⇒ 重试 = **新传输**
    // （重走入队，如实声明，不新造键面）。网页腿的失败重试面 = 既有 (c) 臂可重试提示
    // （`uploadRetryEl`，同键重放），逐字不变 ⇒ 不在此叠第二套。
    if (typeof item.onRetry === 'function') {
      const retry = el('button', 'fm-upload-cancel', t('contacts.retry'));
      retry.type = 'button';
      retry.dataset.uploadRetry = '1';
      retry.addEventListener('click', () => { item.onRetry(); });
      card.appendChild(retry);
    }
  }
  return card;
}

/** 卡上取消键（**一枚实现**，两档状态共用）：外部腿走它的 `onCancel`（设备：未 offer 件
 *  移出 FIFO / 在传件 abort），网页腿走既有 `cancelUpload`（网关取消位 + 断上行）。 */
function appendCardCancel(card, item) {
  const cancel = el('button', 'fm-upload-cancel', t('messages.attachCancel'));
  cancel.type = 'button';
  cancel.dataset.uploadCancel = '1';
  cancel.addEventListener('click', () => {
    if (typeof item.onCancel === 'function') item.onCancel();
    else void cancelUpload(item.uploadId, item.convId);
  });
  card.appendChild(cancel);
}

/**
 * **(c) 臂 · 可重试提示**的唯一渲染器（`attachfix` 边角口径，作者 2026-09-16 17:10 ＋
 * 2026-09-17 范围原则）。
 *
 * 形态：失败**动作**在「清理点」被转成**一条**提示 —— 失败线索（原因文案 + 件名）留在
 * 屏上（**不静默丢失**），并带一个**真**重试入口。🔴 禁做成死文案：按钮点击走
 * [[retryAction]] → **同一** `sendFiles`（原上传/发送腿，非第二条链），且复用**同一**
 * 幂等键 ⇒ 服务端按同键回放原行（§8.6），同动作不会因重试多落一条。
 *
 * `data-*` 契约（供机械读数）：`data-upload-hint`/`data-upload-state`/`data-hint-key`/
 * `data-hint-count`/`data-hint-retry`。
 *
 * 🔴 类名纪律（R-1/R-2 整改，先复用后新建）：卡与按钮**一律复用既有类**，本文件**零新造
 * 类名** ——
 *   · 卡 = `fm-upload`＋`fm-upload-failed`（`css/friends.css:850-876`）：提示是**失败动作**
 *     的延续，故直接承接**既有失败态类** ⇒ `border-color`/文案 = `var(--color-error)`，
 *     与失败卡**逐字同一 token 解析值**（零新增 token）；
 *   · 按钮 = `fm-upload-cancel`（`css/friends.css:889-899`）：与**同一张卡里**的上传件
 *     「取消」按钮**同一规则族**（border 1px `var(--glass-control-border)` / radius 5px /
 *     font-size 11px / padding 1px 8px / cursor pointer ＋ `:hover` ＋ `:focus-visible`）。
 *     改前该按钮用的是**新造且无任何 CSS 规则**的类名（`retry` 后缀族）⇒ 真渲染成
 *     **浏览器原生控件**（两次 FAIL 里的 verifier R-1 阻断项）；本批允许面**不含 `css/**`**
 *     ⇒ 正解只能是复用既有类，🔴 禁新造。（本文件与 `web/css/**` 现对旧新造类名**零命中**，
 *     机械读数见报告 §4/§9。）
 * 识别钩子（两个 harness 都按它取件）＝ `[data-hint-retry]`（按钮）/ `.fm-upload[data-upload-hint]`（卡）。
 */
function uploadRetryEl(hint) {
  const card = el('div', 'fm-upload fm-upload-failed');
  card.dataset.uploadState = 'retry';
  card.dataset.uploadHint = '1';
  card.dataset.hintKey = hint.clientMsgId;
  card.dataset.hintCount = String(hint.fileNames.length);
  card.dataset.hintName = hint.fileNames[0] || '';
  card.appendChild(el('span', 'fm-upload-name', hint.fileNames[0] || ''));
  card.appendChild(el('span', 'fm-upload-note', hint.message));
  // 文案取**既有** key（本批允许面不含 locales 文件 ⇒ 零新增 i18n key；`contacts.retry`
  // 与另两处 `*.retry` 逐字同形 = 既有的通用「重试 / Retry」词条）。
  const retry = el('button', 'fm-upload-cancel', t('contacts.retry'));
  retry.type = 'button';
  retry.dataset.hintRetry = '1';
  retry.addEventListener('click', () => { void retryAction(hint.convId, hint.clientMsgId); });
  card.appendChild(retry);
  return card;
}

/** (c) 臂的重试入口：**真**重走原上传/发送腿，且**复用同一键**（重试 ≠ 新动作）。
 *
 *  成功后的可见收口 = **复用同一个**唯一清理点（与 `messages.js` 发送成功后的收口
 *  同款语义：屏上只留服务端权威行一个附件面）；失败面照 `sendAttachCurrent` 的
 *  fail-closed 语义**不**清理（提示与卡都留在原地，供再试）。
 *  📌 imgmsg 批（2026-09-18）范围注记：好友**图片**路径自本批起「屏上的主面 = 气泡」
 *  （上传开始即上屏的乐观面，确认时原地接管）⇒ 本条注释里的「只剩服务端权威行一个面」
 *  在新语义下读作「只剩一个**持有图片字节**的面（= 气泡）」；本文件其余路径
 *  （群窗 / 混批 / 设备面 / 重试提示）**逐字不变**。 */
async function retryAction(convId, clientMsgId) {
  const hint = convRetryHints(convId).find(h => h.clientMsgId === clientMsgId);
  if (!hint) return;
  const res = await sendFiles(hint.conv, hint.files, hint.text, { clientMsgId });
  if (res && res.ok) clearSettledUploads(convId);
}

/** 该键的动作已成功落地 ⇒ 收尾（同一键的**整个生命周期**到此结束）：
 *   ① 该动作**早前尝试**留下的失败/取消卡退场 —— 同键动作已落行，它们是**同一动作**的
 *      过期线索；留着不会「多看一眼」，反而会在**下一个清理点被转回可重试提示**
 *      （一个**已成功**的动作反复要求重试 = 必然返工形态）。🔴 只清**本动作键**的终态
 *      失败卡，其余动作的失败卡一字不动；
 *   ② 该键的待重试提示退场（不留悬空重试入口）。 */
function settleActionOutcome(convId, clientMsgId) {
  const list = convUploads(convId);
  const keep = list.filter((i) => {
    const terminalFailed = i.state === UPLOAD_STATE.FAILED || i.state === UPLOAD_STATE.CANCELLED;
    const sameAction = !!(i.action && i.action.clientMsgId === clientMsgId);
    return !(terminalFailed && sameAction);
  });
  if (keep.length !== list.length) uploadsByConv.set(convId, keep);
  const hints = convRetryHints(convId);
  const i = hints.findIndex(h => h.clientMsgId === clientMsgId);
  if (i >= 0) hints.splice(i, 1);
}

/** **唯一**渲染入口（`messages.js` 只在 `renderMessages` 里调它一次 ⇒ 好友窗 / 群窗 /
 *  **设备窗**共用同一份实现与同一挂载点）。幂等：重进只重挂容器。
 *
 *  🔴 幂等性 = 本函数的**职责**（不是调用方的）：`renderMessages` 每次渲染都调它，
 *  而修前只 append、不清理旧容器 ⇒ 同一个会话在 DOM 里**堆叠多个 `.fm-upload-list`**
 *  （每只都留着上一次 `paint()` 画的那批卡；`paint()` 只重画 `mounted` 指的那只）
 *  ⇒ 屏幕上同一件附件出**多张**上传卡（作者报障「重复且没有意义」的客户端侧成因之一）。
 *  `.fm-upload-list` 的唯一创建者就是本函数 ⇒ 清理面在这里天然闭合。
 *
 *  📌 uxconsist Phase B（§3.2 / §4.1-#4）：挂载面**扩到设备窗** —— 会话面参数
 *  （`convKind`）只落 dataset（QA 读数面），**显示面三面同款**；设备腿的**传输**仍由
 *  `dropbox.js` 单点执行，本模块只经 `registerExternalTransfer` 收显示态。
 *  @param {HTMLElement} flow 消息流容器
 *  @param {string} convId 会话 id
 *  @param {string} [convKind] 会话 kind（'friend' | 'group' | 'device'；缺省 ''） */
export function renderUploadCards(flow, convId, convKind) {
  if (!flow) return;
  for (const stale of flow.querySelectorAll('.fm-upload-list')) stale.remove();
  const container = el('div', 'fm-upload-list');
  container.dataset.convId = String(convId || '');
  container.dataset.convKind = String(convKind || '');
  flow.appendChild(container);
  mounted = { container, convId };
  paint();
}

/** 设备腿**登记口**（§3.2）：外部传输（传输链属 `dropbox.js` 单点）把**显示态**登记进来。
 *
 *  🔴 边界（硬）：`attachTargetOf(device)` 恒 `null` ⇒ 设备腿的**传输**永不进本模块，
 *  此处只共享**显示**（同一渲染器 / 同一容器 / 同一 dataset 契约）。
 *  🔴 键 = `transferId`（设备腿的**本地件号**；offer 受理后同一条 emission 会带上
 *  wire 的 `transferId` 供取消/断言用）——同键重复登记 = **就地覆盖**（幂等，不叠卡）。
 *  @param {string} convId 会话 id（设备面 = `dev:<deviceId>`）
 *  @param {{transferId: string, name?: string, size?: number, state?: string, bytesSent?: number,
 *    totalBytes?: number, code?: string, error?: string, onRetry?: () => void,
 *    onCancel?: () => void}} item 显示态（缺省 = S1 排队行）
 *  @returns {any} 登记后的条目（调用方禁就地改结构） */
export function registerExternalTransfer(convId, item) {
  if (!convId || !item || !item.transferId) return null;
  const list = convUploads(convId);
  const entry = {
    uploadId: `dev-${item.transferId}`,
    transferId: String(item.transferId),
    convId,
    name: item.name || '',
    size: Number(item.size) || 0,
    bytesSent: Number(item.bytesSent) || 0,
    totalBytes: Number(item.totalBytes) || Number(item.size) || 0,
    state: item.state || UPLOAD_STATE.QUEUED,
    code: item.code || '',
    error: item.error || '',
    attachmentId: '',
    quiet: false, // 设备腿只有卡面（无乐观气泡）⇒ 卡**给数值**（R6-device）
    external: true,
    onRetry: typeof item.onRetry === 'function' ? item.onRetry : null,
    onCancel: typeof item.onCancel === 'function' ? item.onCancel : null,
    action: null, // 设备附件腿无幂等键面 ⇒ 不进 (c) 臂的可重试提示转换
    controller: null,
  };
  const i = list.findIndex(x => x.transferId === entry.transferId);
  if (i >= 0) list[i] = { ...list[i], ...entry };
  else list.push(entry);
  paint();
  return i >= 0 ? list[i] : list[list.length - 1];
}

/** 设备腿显示态推进（同一登记口的第二半）：**只推进已登记项**（未知键 ⇒ `null`，不新建
 *  —— 防幽灵卡；「先登记后推进」的顺序由 `dropbox.js` 的 emission 顺序保证）。 */
export function updateExternalTransfer(convId, transferId, patch) {
  const list = convUploads(convId);
  const item = list.find(x => x.transferId === String(transferId));
  if (!item) return null;
  Object.assign(item, patch || {});
  paint();
  return item;
}

/** 会话切换/关窗时解除挂载（避免进度帧画到已关闭的窗上）。 */
export function detachUploadCards() {
  mounted = null;
}

// ── 上传链 ────────────────────────────────────────────────

function beginItem(convId, uploadId, file, action, quiet, state) {
  const item = {
    uploadId,
    convId,
    name: file.name,
    size: file.size,
    bytesSent: 0,
    totalBytes: file.size,
    state: state || UPLOAD_STATE.UPLOADING,
    code: '',
    error: '',
    attachmentId: '',
    // ④ imgmsg 批：`quiet` = 本动作的反馈面由**乐观气泡**承担 ⇒ 本卡不给百分比
    //（只影响 uploading 态的**呈现**，不影响任何传输/终态语义）。
    quiet: !!quiet,
    // 动作身份（attachkey 批）：**失败卡存键** —— 动作对象（含 `clientMsgId` /
    // 原文件集 / 原正文）随件卡存活 ⇒ 「清理点」能把失败卡转成**可重试**提示，
    // 重试即以**同一键**重走原链（机制见 [[clearSettledUploads]] 的 (c) 段）。
    action: action || null,
    controller: typeof AbortController !== 'undefined' ? new AbortController() : null,
  };
  convUploads(convId).push(item);
  paint();
  return item;
}

/** 上传件号（**唯一**生成点：`att-<时间戳>-<单调序>-<随机尾>`）。 */
function nextUploadId() {
  seq += 1;
  return `att-${Date.now()}-${seq}-${Math.random().toString(36).slice(2, 8)}`;
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
 * 幂等键（attachkey 批）：键在**本动作入口**铸、**一次动作一枚**，并作为第 4 实参直达
 * 既有接收面（`friendsApi.sendGroupMessage/sendFriendMessage`，P2-b 落地签名）。缺省
 * （无 `opts.clientMsgId`）= **新动作** ⇒ 新键；`opts.clientMsgId` = 重试面（(c) 提示的
 * 重试入口）⇒ **复用同一键**。🔴 生成点全仓唯一（`messages.js:newClientMsgId`），本文件
 * 只**调用**、不复制。
 *
 * @param {object} conv 会话（好友 / 群；设备面不经本模块）
 * @param {FileList|File[]} fileList 本动作的件集
 * @param {string} text 本动作的正文（重试时必须**同一**正文：同键 ⇒ 服务端回放原行）
 * @param {{clientMsgId?: string, quiet?: boolean, onUploaded?: (attachmentId: string, file: File) => void}} [opts]
 *   仅重试面传 `clientMsgId`（同动作复用键）；`quiet` / `onUploaded` = imgmsg 批加性面
 *   （④ 无百分比呈现 / ① 上传回执回调）——缺席 ⇒ 逐字现状。
 * @returns {Promise<{ok: boolean, reason?: string, ids?: string[], messageId?: string}>}
 */
export async function sendFiles(conv, fileList, text, opts) {
  const convId = conv && conv.conversationId;
  const targetId = attachTargetOf(conv);
  if (!convId || !targetId) return { ok: false, reason: t('messages.attachUnsupported') };
  const gate = validateFiles(fileList);
  if (!gate.ok) return { ok: false, reason: gate.message };

  const files = gate.files;
  // 🔴 动作边界铸键（与 P2-b 的 `messages.js:2348` 同一原则：生成点在**动作**判据处，
  //   不在 API 层 —— API 层分不清「重试」与「用户又想发一批一样的」）。
  // 生成点 = `messages.js` 的 `newClientMsgId`（唯一），此处以**动态 import 读同一
  //   命名空间**取用（破环；见文件头注释）。短路次序逐字不变：`opts.clientMsgId` 在
  //   时**不触碰生成点** ⇒ 动态 import 也不发生（重试面零新增动作）。
  const clientMsgId = (opts && opts.clientMsgId)
    || (await import('./messages.js')).newClientMsgId();
  const action = { conv, files, text, clientMsgId };
  const quiet = !!(opts && opts.quiet);
  /** ① imgmsg 批：逐件上传回执的**加性回调**（缺席 ⇒ 逐字现状）。调用方据此把
   *  「本机件 → 附件 id」的强键与句柄登记进未决乐观项（回显认领的身份判据）。 */
  const onUploaded = opts && typeof opts.onUploaded === 'function' ? opts.onUploaded : null;
  const ids = [];
  // ── S1 排队（uxconsist Phase B · §3.2 排队行前置 / §5 R8-friend + R8-group）────
  // 件集在**动作入口**（第一个 await 之前）**全量登记为 `queued` 行**，轮到本件才转
  // `uploading`。传输行为**零变化** —— 仍是同一条串行 for 循环，队列只是把既有
  // 「一次一件、按序进行」的语义**可见化**（禁借排队改并发/改顺序）。
  const batch = files.map(file => beginItem(convId, nextUploadId(), file, action, quiet, UPLOAD_STATE.QUEUED));
  for (let i = 0; i < files.length; i += 1) {
    const file = files[i];
    const item = batch[i];
    item.state = UPLOAD_STATE.UPLOADING; // 轮到本件（S1 → S2/S3）
    paint();
    const res = await uploadOne(item, file, targetId);
    if (res.ok) {
      item.attachmentId = res.attachmentId;
      item.bytesSent = file.size;
      item.totalBytes = file.size;
      ids.push(res.attachmentId);
      if (onUploaded) {
        // 🔴 回调失败**绝不**影响发送链（与「回执面永不影响用户」同一条纪律）。
        try { onUploaded(res.attachmentId, file); } catch { /* non-critical */ }
      }
    } else {
      item.state = res.code === 'cancelled' ? UPLOAD_STATE.CANCELLED : UPLOAD_STATE.FAILED;
      item.code = res.code || 'upload_failed';
      item.error = res.message || t('messages.attachFailed');
      // 取消 ⇒ 用户主动停 ⇒ 剩余件不再上传（禁「偷偷继续传」）。
      // 剩余件 = **本批已登记的排队行**（S1 → 未发），就地翻终态（不再另建条目：
      // 同一次动作在屏上恒一组卡，禁两套行）。
      const reasonKey = item.state === UPLOAD_STATE.CANCELLED ? 'messages.attachCancelledRest' : 'messages.attachNotSentRest';
      for (let j = i + 1; j < files.length; j += 1) {
        const rest = batch[j];
        rest.state = UPLOAD_STATE.SKIPPED;
        rest.code = item.code;
        rest.error = t(reasonKey);
      }
      paint();
      return { ok: false, reason: item.error };
    }
  }

  // 全部上传成功 ⇒ 发消息（正文可空：服务端 §B.4 生成占位正文）。
  // 幂等键 = 第 4 实参（P2-b 落地签名：`(…, attachments, clientMsgId)`，现读非猜形）。
  // ① imgmsg 批：响应里的 `messageId` **加性回带**给调用方（乐观面锚定的唯一锚点
  // 需要它；改前本层把它丢掉 ⇒ 调用方只能等回显腿，正是重复面的温床）。
  let sentMessageId = '';
  try {
    const resp = conv.kind === 'group'
      ? await api.sendGroupMessage(conv.conversationId, text, ids, clientMsgId)
      : await api.sendFriendMessage(conv.friend.userId, text, ids, clientMsgId);
    if (resp && resp.messageId !== undefined && resp.messageId !== null) sentMessageId = String(resp.messageId);
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
  // 动作已成功落地 ⇒ 该键生命周期收尾（过期失败卡 + 待重试提示一并退场）。
  settleActionOutcome(convId, clientMsgId);
  paint();
  // `messageId` = 加性回带键（① 乐观面锚定用；缺席/老服务端 ⇒ 空串 ⇒ 调用方走既有
  // 「等回显」形态，不新造回落分支）。
  return { ok: true, ids, messageId: sentMessageId };
}

/**
 * 清掉某会话的已终态上传卡（**唯一**清理点；两个调用点 = `messages.js:2484` 发送成功后
 * 与 `messages.js:643` 关窗时）。
 *
 * **(c) 臂（作者 2026-09-16 17:10 口径 ＋ 2026-09-17 范围原则）**：
 *   · 清理点之后**卡片 UI 仍可达** ⇒ 失败/取消态卡在清除前**转为一条可重试的提示**
 *     （重试入口可达、失败线索不静默丢失），其余状态（`SENT` / `SKIPPED`）直接清；
 *   · 卡片**随窗销毁**、重试入口不复存在 ⇒ **维持全清**（现状逐字不变）。
 *   可达判据 = 挂载容器**仍在文档里**（`isConnected`）。关窗路径（`messages.js:643`）的
 *   上游 `closeChat()` 已经 `modalEls.overlay.remove()`（`messages.js:635`）⇒ 容器必然
 *   `isConnected === false` ⇒ 该点归「窗销毁」档 ⇒ **本批禁改、维持全清**（对号申报见报告）。
 *   在飞件（`UPLOADING`）两档都保留（上传不因清理而静默中止 —— 现状）。
 */
export function clearSettledUploads(convId) {
  const list = convUploads(convId);
  const reachable = !!(mounted && mounted.convId === convId && mounted.container.isConnected === true);
  const keep = [];
  const convertible = [];
  for (const item of list) {
    if (item.state === UPLOAD_STATE.UPLOADING) { keep.push(item); continue; }
    const failedOrCancelled = item.state === UPLOAD_STATE.FAILED || item.state === UPLOAD_STATE.CANCELLED;
    if (reachable && failedOrCancelled) convertible.push(item);
    // 其余（SENT / SKIPPED / 不可达面）⇒ 直接清（现状）。
  }
  uploadsByConv.set(convId, keep);
  if (convertible.length) convertToRetryHints(convId, convertible);
  else if (!reachable) retryHintsByConv.set(convId, []); // 窗销毁档 = 全清（连提示一起）
  paint();
}

/** 失败/取消态卡 → **一条可重试提示**（按动作键聚合：同键件同属一动作 ⇒ 一条）。 */
function convertToRetryHints(convId, items) {
  const hints = convRetryHints(convId);
  const byKey = new Map();
  for (const item of items) {
    const action = item.action;
    if (!action || !action.clientMsgId) continue;
    let hint = byKey.get(action.clientMsgId);
    if (!hint) {
      hint = {
        clientMsgId: action.clientMsgId,
        convId,
        conv: action.conv,
        files: action.files,
        text: action.text,
        fileNames: action.files.map(f => (f && f.name) || ''),
        message: '',
      };
      byKey.set(action.clientMsgId, hint);
    }
    // 失败线索不静默丢失：取**真实失败件**的可判读文案（不带兜底、不折叠）。
    if (!hint.message) hint.message = item.error || t('messages.attachFailed');
  }
  for (const hint of byKey.values()) {
    const i = hints.findIndex(h => h.clientMsgId === hint.clientMsgId);
    if (i >= 0) hints[i] = hint;
    else hints.push(hint);
  }
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
