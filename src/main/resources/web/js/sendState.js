// sendState.js — 三面发送状态**统一落码**（好友 / 群 / 设备共用一份词汇表与三个单点）。
//
// 设计依据（照批生效，非新设计）：`uxconsist-design` 件 §2.2 状态机 + §3.1 本模块规格 +
// §5 十五格逐格补齐设计（格 R1/R2/R3/R4/R6/R7/R8）——落点与判据逐条照抄，不重设计。
//
// 本模块 = **叶子模块**（零新环，`check-circular.mjs` 的 SCC 集合不因本件增长）：
// 只 `import { t } from './i18n.js'`；被 `messages.js` / `attachUpload.js` 消费，
// **不反向依赖任何业务件**。
//
// 判据纪律（§2.2 末尾逐字）：「感受面只读 `PHASE` 一个词汇表；`conv.kind` 只在
// **传输适配器选择**处出现一次」。⇒ 本模块不判 kind 分流传输，只给**能力位**与
// **呈现单点**；三面的传输适配器选择留在 `messages.js` 的单一入口（`dispatchSend`
// / `dispatchFiles`）。

import { t } from './i18n.js';

/**
 * 八态词汇表（S1–S8，§2.2 逐行）。三面共用一份；`data-send-phase` / `data-upload-state`
 * 两个 dataset 契约由本表驱动（供 QA 机械断言）。
 */
export const PHASE = {
  /** S1 排队：附件腿多件动作，件集全量登记、尚未轮到。 */
  QUEUED: 'queued',
  /** S2 发送中：文本腿请求在飞 / 附件腿本件已开始（气泡左侧 14px 环）。 */
  SENDING: 'sending',
  /** S3 进度：附件腿收到**服务端确认分块**后的进度帧（卡内进度条 + 数值说明行）。 */
  PROGRESS: 'progress',
  /** S4 错误：请求失败（网络 / 5xx / 非终态 401-403 / 闸位拒绝 / 件失败）。 */
  ERROR: 'error',
  /** S5 重试：用户点重试键（载面回 S2 / S3；文本腿同键，设备附件腿 = 新传输）。 */
  RETRY: 'retry',
  /** S6 送达：2xx / 全部件成功且消息落地（**无痕**：摘载面，不留「已发送」残条）。 */
  SENT: 'sent',
  /** S7 失败（语义终态）：气泡撤除 + 分态提示（群：解散 / 非成员 / 群不存在）。 */
  FAILED: 'failed',
  /** S8 中断 / 取消：用户点取消（S1 未开始 / S3 在传）；文本腿不适用。 */
  CANCELLED: 'cancelled',
};

/** 三面**腿**的枚举（能力位的第二维：同一条腿的文本面与附件面能力不同，§5 R7 行）。 */
export const SEND_LEG = {
  /** 文本腿（三面同款；无取消面 —— 单次 POST，明示登记）。 */
  TEXT: 'text',
  /** 附件腿（无乐观气泡的形态：卡面承担反馈，**给数值**）。 */
  ATTACH: 'attach',
  /** 附件腿 · 乐观气泡形态（好友 / 群图片腿，R1-group 放开后两面同款）：卡**不给百分比**。 */
  ATTACH_OPTIMISTIC: 'attach-optimistic',
};

/**
 * R5 两格（好友 / 群**回执位**）的**能力位** —— 源契约已回投，本批（uxb-seg2）接线。
 *
 * 沿革：作者 2026-09-20 18:45 裁定②把两格由「不出现」改为「候后端源」；源契约
 * `friend-group-receipt-source` **v1.2**（判词 PASS 6/6，sha256 `640de17b2d52…`）
 * 经 root 回投 ⇒ 本常量翻 `true`，好友 / 群两面**打开回执位**。
 * ⇒ 取数腿 = `messages.js::refreshConvReceipts`（E1 `GET …/receipts`，窗口按**消息 id**
 * 口径）；呈现单点仍 = `applyReceipt`（本件，槽位形态与设备面同源）。
 *
 * 🔴 **能力位 ≠ 源可用性**：服务端不支持 / 非成员 / 鉴权失效 / 网络 = **运行时降级**面
 * —— 无回执数据 ⇒ 槽位**不画**（无空槽、无报错噪声），处置落在 `refreshConvReceipts`
 * 的错误面（契约 §1.4 逐码）。
 * 🔴 设备面（`kind='device'`）走 E2EE 原则，**本批零触碰**：其回执位判据保持原式
 * （`sourceServer === true`）。
 */
const FRIEND_RECEIPT_SOURCE = true;

/**
 * 能力位**唯一判据点**（§3.1）。感受面不各自判 `kind` / `sourceServer`，一律读本表。
 * @param {any} conv 会话（好友 / 群 / 设备）
 * @param {string} [leg] `SEND_LEG.*`；缺省 = 附件腿（无乐观面形态）
 * @returns {{receipt: boolean, queue: boolean, cancel: boolean, numericProgress: boolean}}
 */
export function capabilitiesOf(conv, leg) {
  const kind = conv && conv.kind;
  const l = leg || SEND_LEG.ATTACH;
  const device = kind === 'device';
  return {
    // 回执位（S6）：设备**服务端腿**（`device_message_receipts`）或好友 / 群
    // （账号回执源 `message_receipts`，契约 v1.2 已回投 ⇒ 上方位为真）。
    receipt: (device && conv.sourceServer === true) || (FRIEND_RECEIPT_SOURCE && !device),
    // 排队态（S1）：附件腿=有（件集全量登记）；文本腿=无（单次 POST，无件集）。
    queue: l !== SEND_LEG.TEXT,
    // 取消（S8）：附件腿=有；文本腿=不适用（明示登记，§5 R7 行）。
    cancel: l !== SEND_LEG.TEXT,
    // 数值进度（S3 说明行）：**有乐观气泡的腿 = 卡不出百分比**（反馈由气泡承担，
    // §5 R6 行 —— 现状即此，本批由「个案」升为**规则**）；其余腿（群/混批/设备）
    // 卡给进度条 + 数值。
    numericProgress: l !== SEND_LEG.ATTACH_OPTIMISTIC,
  };
}

/** 在飞档（环 / 进度 / 重试 = 同一枚「发送中」载面，§2.2 S2/S3/S5）。 */
const IN_FLIGHT = new Set([PHASE.QUEUED, PHASE.SENDING, PHASE.PROGRESS, PHASE.RETRY]);

/**
 * 气泡左侧**单一状态槽**的挂 / 摘（§3.1；类的取用 = 既有 `.fm-msg.fm-sending` /
 * `.fm-msg.fm-failed` 规则，`css/friends.css:1283-1360`，**零新 CSS 规则**）。
 *
 * dataset 契约（QA 读数面）：`data-send-phase ∈ {local-pending, confirmed}` ——
 * 在飞（S1/S2/S3/S5）= `local-pending`，S6 落地 = `confirmed`。S4/S7/S8 是**终态**，
 * 其信号 = `fm-failed` 类（槽位形态），dataset 不再改写（禁出现第三值）。
 * @param {HTMLElement} node 气泡节点（`.fm-msg`）
 * @param {string} phase `PHASE.*`
 */
export function applyPhase(node, phase) {
  if (!node || !node.classList) return;
  node.classList.toggle('fm-sending', IN_FLIGHT.has(phase));
  node.classList.toggle('fm-failed', phase === PHASE.ERROR || phase === PHASE.FAILED);
  node.dataset.sendPhase = phase === PHASE.SENT ? 'confirmed' : 'local-pending';
}

/**
 * 红圈重试键的**唯一装配点**（§3.1；类 = 既有 `.fm-retry`，落位由
 * `css/friends.css:1327-1360` 的 `.fm-msg.fm-failed .fm-retry` 承担）。
 * `title` / `aria-label` = `contacts.retry`（§8.2 改写①：**改后可判读**，与按钮语义一致）。
 * @param {HTMLElement} node 气泡节点（挂 `.fm-failed` 的那个）
 * @param {() => void} onRetry 重试动作（调用方给：好友 / 群 = 同键 `sendCurrent`；
 *   设备文本腿 = 同键记忆复用 `deviceRetry` ← §5 R4-device）
 * @returns {HTMLButtonElement|null}
 */
export function bindRetry(node, onRetry) {
  if (!node) return null;
  const flag = document.createElement('button');
  flag.className = 'fm-retry';
  flag.type = 'button';
  flag.textContent = '!';
  flag.title = t('contacts.retry');
  flag.setAttribute('aria-label', t('contacts.retry'));
  flag.dataset.retry = '1';
  flag.addEventListener('click', () => {
    try { onRetry(); } catch { /* 重试动作自吞错：用户面已有载面，不额外抛 */ }
  });
  node.appendChild(flag);
  return flag;
}

/**
 * 回执位的**文案单点**（含群聊计数形态）。
 *
 * 口径（契约 v1.2 §1.1 派生式 + §1.2 状态机）：直聊 = 1:1 无歧义 ⇒ 复用
 * `messages.deviceSent` / `messages.deviceRead`（**逐字沿用既有回执面用语**，零新增文案）；
 * 群聊 = `已读 {read}/{memberCount}`（契约逐字给出的气泡文案，全会话口径的
 * `memberCount` 为分母）与同族 `已送达 {sent}/{memberCount}`（同一派生式
 * `deliveredCount(mid)` 的呈现）⇒ 两枚新键，见 §16 文案申报。
 * `counts` 缺席（设备面调用形态）/ 分母非正 ⇒ 恒走既有非计数形态（设备面**逐字不变**）。
 * @param {'sent'|'read'} st 回执态
 * @param {{read?: number, delivered?: number, total?: number}} [counts]
 * @returns {string}
 */
function receiptText(st, counts) {
  const total = Number(counts && counts.total);
  if (!(Number.isFinite(total) && total > 0)) {
    return st === 'read' ? t('messages.deviceRead') : t('messages.deviceSent');
  }
  if (st === 'read') {
    return t('messages.receiptReadCount', { read: Number((counts && counts.read) || 0), total });
  }
  return t('messages.receiptSentCount', { sent: Number((counts && counts.delivered) || 0), total });
}

/**
 * 回执位（S6 能力位）的**唯一呈现点** —— 设备面（无计数）与好友 / 群面（群聊带计数）
 * 共用一份实现；「有 slot 才画、无 slot **不画空槽**」是唯一形态纪律。
 *
 * 🔴 本函数**不含任何回执取数逻辑**（取数在 `messages.js::refreshConvReceipts`，
 * 按 v1.2 契约接线）⇒ 调用方喂 state/counts 即可。
 * @param {HTMLElement} node 气泡节点（`.fm-msg`）
 * @param {'sent'|'read'|null} state 回执态（`null`/假值 ⇒ 撤画）
 * @param {{read?: number, delivered?: number, total?: number}|null} [counts]
 *   群聊计数面（`total` = `memberCount`）；缺席 ⇒ 非计数形态（设备 / 直聊）
 * @returns {'sent'|'read'|null} 实际呈现态
 */
export function applyReceipt(node, state, counts) {
  if (!node || !node.querySelector) return null;
  const wrap = node;
  const chip = wrap.querySelector('.fm-msg-device-receipt');
  const st = (state === 'sent' || state === 'read') ? state : null;
  if (!st) {
    if (chip) chip.remove();
    return null;
  }
  const meta = chip ? chip.parentElement : wrap.querySelector('.fm-msg-meta');
  if (!meta) return null;
  const target = /** @type {HTMLElement} */ (chip || document.createElement('span'));
  if (!chip) {
    target.className = 'fm-msg-device-receipt';
    meta.appendChild(target);
  }
  const text = receiptText(st, counts);
  if (target.textContent !== text) target.textContent = text;
  target.dataset.receiptState = st;
  // 计数面 dataset（QA 机械读数面；非计数形态 ⇒ 摘键，禁留陈旧值）。
  const total = Number(counts && counts.total);
  if (Number.isFinite(total) && total > 0) {
    target.dataset.receiptTotal = String(total);
    target.dataset.receiptCount = String(Number((st === 'read' ? counts.read : counts.delivered) || 0));
  } else {
    target.removeAttribute('data-receipt-total');
    target.removeAttribute('data-receipt-count');
  }
  return st;
}

// ── 未决乐观项登记表（U-b 语义**参数化**：好友 / 群 = `chatMsgs`，设备 = `deviceMsgs`）──
// 本表是**唯一属主**（调用方经 `trackPending` / `forgetPending` / `resetPending` 读写），
// 生命期与调用方的窗口数据同拍（开窗 / 关窗各自归零，见 messages.js 两个 reset 点）。
/** @type {any[]} */
let pending = [];

/** 登记一条未决乐观项（`{tempId, convId, body, node, entry, anchoredTo, attachIds?}`）。 */
export function trackPending(p) {
  if (p) pending.push(p);
  return p;
}

/** 出表（锚定完成 / 发送失败）：登记表不随发送条数增长。 */
export function forgetPending(p) {
  const i = pending.indexOf(p);
  if (i >= 0) pending.splice(i, 1);
}

/** 整表归零（开窗 / 关窗同拍；与调用方窗口数据同一条纪律）。 */
export function resetPending() {
  pending.length = 0;
}

/** 在飞未决条数（断言 / 读数用）。 */
export function pendingCount() {
  return pending.length;
}

/**
 * **回显认领**（§3.1）：把一条**本机所发**的服务端消息认领到本会话未决的乐观项上
 * （「回显先到 · 响应后到」的解 —— 不新增气泡、不新增条目）。
 *
 * 语义逐字继承既有 `claimPendingSend` / `anchorSendToRealId`（U-b 批），只是把消息数组
 * **参数化**到 `list`：好友 / 群 = `chatMsgs`，设备 = `deviceMsgs`。
 * 只在**唯一可判**时认领：① 调用方已确证这条是本机所发（`ours`）② 该 id 尚未归属任何
 * 已载入条目 ③ 本会话存在未锚定、**身份可判**的乐观项（身份两条腿，**强键优先**：
 * 附件 id 腿 ⇒ 正文腿「逐字相同的最老者」）。
 *
 * @param {any[]} list 本会话消息数组（认领在**该数组内**换键 + 同 id 去重）
 * @param {any} m 服务端消息（含真 id）
 * @param {{convId?: string, ours?: boolean, presentOk?: boolean}} [opts]
 *   `convId` = 该消息所属会话；`ours` = 调用方确证「这是本机所发」；
 *   `presentOk` = **设备腿专用**：设备取数是**整窗替换**（`fetchDeviceMsgsServer` /
 *   `syncDeviceMsgs` 直接换数组）⇒ 权威行**必然已在窗内**，好友腿那条「同 id 条目已在
 *   窗内即不认领」的闸在此**结构性不适用**（不放开会让设备腿永远认领不了）。
 *   好友 / 群腿**不得**传该选项（逐字保持既有保护）。
 * @returns {boolean} true = 已认领（调用方禁止再 append）
 */
export function claim(list, m, opts) {
  const o = opts || {};
  if (!o.ours || !m || !Array.isArray(list)) return false;
  const key = m.id;
  if (key === undefined || key === null || key === '') return false;
  if (!o.convId) return false;
  if (!o.presentOk && list.some(x => String(x.id) === String(key))) return false;
  const live = (x) => !x.anchoredTo && x.node && x.node.isConnected
    && (!x.convId || String(x.convId) === String(o.convId));
  // ① 判据 A（imgmsg 批 · 强键优先）：入帧带的附件 id ∩ 未决项的**本机上传回执 id**。
  const attIds = (Array.isArray(m.attachments) ? m.attachments : [])
    .map(a => (a && a.id !== undefined && a.id !== null) ? String(a.id) : '')
    .filter(Boolean);
  if (attIds.length > 0) {
    const byAtt = pending.find(x => live(x) && Array.isArray(x.attachIds)
      && x.attachIds.some(id => attIds.includes(String(id))));
    if (byAtt) return anchorSendToRealId(byAtt, key, list);
  }
  // ② 判据 B（既有 · 逐字不变）：正文逐字相同的最老者（服务端 id 升序 = 发送序）。
  const body = m.body || '';
  const p = pending.find(x => live(x) && x.body === body);
  return p ? anchorSendToRealId(p, key, list) : false;
}

/**
 * 乐观项 → 真 id 的**唯一锚定点**（数据层收敛，U-b）——`claim` 的实现面，同时供
 * 「POST 响应腿」（temp id → 真 id）直接调用（**一个锚定点，禁第二套换键**）。
 *
 * 后置条件（两条腿**完全一致**）：`list` 里该 id 恰有一条（乐观项**原地换键**；服务端
 * 副本若已先到 ⇒ 出窗，同一条消息不得两存）；DOM 里该 id 恰有一个节点（**复用乐观节点**，
 * 删掉同 id 的其它节点 —— 不新增气泡、节点数不变）。
 * @param {any} p 乐观项登记（`{entry, node, anchoredTo}`）
 * @param {string|number} realId 服务端真 id
 * @param {any[]} list 本会话消息数组（好友 / 群 = `chatMsgs`，设备 = `deviceMsgs`）
 * @returns {boolean} true = 已锚定（调用方不得再新增节点/条目）
 */
export function anchorSendToRealId(p, realId, list) {
  // 窗口已关 / 已重开（乐观节点已脱离文档）⇒ 锚定无意义（交调用方走最小改键回落）。
  if (!p || !p.entry || !p.node || !p.node.isConnected) return false;
  if (!Array.isArray(list)) return false;
  if (realId === undefined || realId === null || realId === '') return false;
  const key = String(realId);
  p.anchoredTo = key;
  const rekeyed = { ...p.entry, id: realId };
  const i = list.indexOf(p.entry);
  if (i >= 0) list[i] = rekeyed;
  // 条目不在窗内（设备腿：取数腿已整窗替换）⇒ 就地补入 —— 该行正是本窗最新一条。
  else list.push(rekeyed);
  p.entry = rekeyed;
  p.node.dataset.messageId = key;
  // 同 id 的**其它节点**退场（认领/替换乐观项：不新增气泡、节点数不变）。
  const host = p.node.parentElement;
  if (host) {
    for (const n of [...host.querySelectorAll('.fm-msg')]) {
      if (n !== p.node && n.dataset.messageId === key) n.remove();
    }
  }
  // 数据面同键去重：**保留认领的那一条**（= 该节点对应的一条），副本条目出窗。
  for (let j = list.length - 1; j >= 0; j--) {
    if (list[j] !== rekeyed && String(list[j].id) === key) list.splice(j, 1);
  }
  return true;
}
