// messages.js — Messages panel (conversation list) + friend chat modal.
// friends-messaging-spec §3.2/§3.3/§4: WeChat-style conversation rows,
// cfg-modal chat window (560px glass), forward-to-agent (one-way), agent-sent
// chips, pure-badge notifications (no sound/banner/title — [U3]).
import { t } from './i18n.js';
import { createIconsIn, markCopyFailed } from './utils.js';
import state from './state.js';
import { onMessage, onReconnect, onDisconnect } from './ws.js';
import { getNeblinkState, onNeblinkStatus, presenceBadgeHTML, platformDisplay } from './neblink.js';
import { setActivityBadge, openLoginModal, onStatusTick } from './activityBar.js';
import { key } from './branding.js';
// ⑨ 缓存与增量（方案 §2.3 B+E）：L2 持久层 = fmMessageCache.js（唯一属主，
// 键/上限/账号分区都在那边）；本模块只消费 + 负责 L1（内存会话列表）新鲜度。
import {
  TTL_MS as CACHE_TTL_MS, SYNC_PAGE, MAX_SYNC_PAGES,
  loadConversation, saveConversation, getCacheAccount,
} from './fmMessageCache.js';
import * as api from './friendsApi.js';
// 群组一期（friendgroups 客户端腿）：群域唯一属主 = friendGroups.js（可用性/
// 取数归一/建群/群设置）。本模块只做会话列表合并 + 聊天窗渲染面的群分支。
// 🔴 转发链锚点（forwardBubble/forwardToAgent/makeReference/appendRefToActiveView/
// notifyFriendRefsSent/onRefsSent/stampForwarded/sendWs）零触碰 —— 群消息复用
// 同一转发入口（主卡 D2：同一契约，ref.id/refType/type='ref' 逐字不变）。
import {
  refreshGroups, groupsAvailable, groupTitleOf, buildGroupSettings, groupErrToast,
  groupAvatarGrid,
} from './friendGroups.js';
import { makeReference } from './reference.js';
import { appendRefToActiveView } from './input.js';
import { showPopupMenu } from './contextMenu.js';
// ⑥ 信任好友封存（作者裁定 2026-09-12）：静态常量，非配置读取、不过 latch。
import { TRUST_SEALED } from './featureFlags.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
// 本模块的好友会话输入框（作者点名的面）此前**零组字判定** —— 组字 Enter 会
// 直接 doSend()。
import { bindImeGuard, isImeComposing } from './imeGuard.js';
// 时制（12h/24h）：与主对话框/设备对话框共享同一偏好与同一实现。
import { formatHm, bindTimeToggle, TIME_FORMAT_CHANGED } from './timeFormat.js';
// ⑩ 合并期滚动位保持（与 Dropbox 面**共用一份实现**，无第二个公式）。
import { preserveScrollAnchor } from './msgScrollAnchor.js';
// 设备会话统一批 MVP-1（2026-09-15）：设备腿的**数据面**访问器（唯一属主 = dropbox.js，
// 含 WS 帧 `dropbox-history`/`dropbox-message` + L2 缓存 + 附件队列/闸位）。本模块只
// **消费**它们，不复制任何取数/合并/落盘逻辑（卡 §6.2：否则造第三实现）。
import {
  deviceMessagesOf, hydrateDeviceCache, requestDeviceHistory, deviceHistoryPending,
  sendDeviceText, sendDeviceFiles, onDeviceMessageChange,
  deviceHasLocalTraffic, // ⑥：通信证据（本地半程）判据单点
  saveDeviceDescription, // ⑤c：设备描述写路径单点（旧设备窗与本窗共用同一函数）
} from './dropbox.js';
// 附件预览（作者令 2026-09-15「点击附件要能直接在 canvas 里预览」）：附件卡的**唯一**
// 预览入口 = attachmentPreview.js（判据 + Canvas 渲染腿都在那边）；本模块只做接线 +
// 降级文案（禁在此再写第二套类型判据 / 第二套取字节路）。
import { previewBlob, previewLocalPath, canPreviewLocalPath, isPreviewOpen } from './attachmentPreview.js';
// 附件**上传**（attachcl 批，作者 2026-09-16 07:36）：好友窗与群窗的发送面**唯一**
// 实现（上传链 + 闸位 + 上传卡渲染都在那边 ⇒ 两面不各写一套）。设备面**不**经此
// （设备腿仍走 dropbox.js 单点，零行为变化）。
import { sendFiles, attachAvailable, renderUploadCards, detachUploadCards, clearSettledUploads } from './attachUpload.js';

let conversations = [];
let friendsCache = [];          // accepted friends — source of truth for §3.3 gate
let friendsFetchedAt = 0;       // ⑨ L1 新鲜度锚：好友列表最后一次成功拉取时刻
let openConvId = null;          // conversation shown in the chat modal
let modalEls = null;            // {overlay, flow, input, sendBtn, toast}
let triggeringRow = null;       // for focus return (A18)
let triggeringConvId = null;    // row may be re-rendered after open (unread clear) — refind by id
const forwardedIds = new Set(); // session-persistent 「已转发」 chips (§3.3)
let msgSeq = 0;

// ── 群组一期（friendgroups 客户端腿）：群会话状态 ─────────────────────
// 群行与单聊行共用 conversations[]（合并后同键排序，主卡 C-3：排序键不变），
// 群行形状 = { conversationId, kind:'group', title, lastMessage, unreadCount,
// memberCount, myRole, selfUserId? }（friendGroups.refreshGroups 归一出口；
// selfUserId 为契约加性 viewer 字段，**在场才有键**，缺席不造值）。

// 「本机是否发送者」判据源（主卡 F-2 #3 点名的群新增面）。契约字段面 =
// **加性小批的 `selfUserId`**（真源 = neblink-server
// `.nebflow/reports/20260915_130900_group-selfuserid-impl.md` §1 契约终版：
// 值 = 本次请求的鉴权身份，服务端权威、客户端不可影响）。本模块的消费点
// = `learnSelfUserId` 单点，四条来源腿：
//  · 群行**行内** `selfUserId`（GET /api/groups，§1.1 #2；refreshConversations）；
//  · 邀请**信封** `selfUserId`（GET /api/groups/invites，§1.1 #3；随 refreshGroups 出口）；
//  · 成员**信封** `selfUserId`（GET /api/groups/{id}/members，§1.1 #7；开群窗名册腿）；
//  · 群发**回执** `selfUserId`（POST …/messages 201，§1.1 #8；发送腿）。
// 四条**都缺席**（老服务端 / 零群退化）⇒ 不改判据、不造值，继续按「发送关联」自证：
//  · sentMessageIds = 本机发送成功的服务端 messageId（POST 响应腿登记）；
//  · 任何取数/帧腿见到 id ∈ sentMessageIds 的行 ⇒ 该行 senderId 即 viewer
//    身份（权威：服务端 sender_id 恒 = 鉴权解出身份，补充卡 §5.4 矩阵）；
//  · 结果按账号分区（deviceId|email，fmMessageCache 同源）持久 localStorage。
// 残余边界（不掩盖，impl 报告登记）：全新浏览器会话、viewer 从未发送成功过
// 且无历史学习值时，群行方向判据无证据 ⇒ 按「非本机」渲染（左）。推荐服务端
// 批补一个 viewer 相对字段（如 GET /api/groups 行内 selfUserId）——客户端
// 一旦有该键即优先生效（learnSelfUserId 单点）。
const LS_SELF_ID = key('fm_self_id');
let selfUserId = '';
let selfIdAcct = '';
const sentMessageIds = new Set();

/** viewer 自身 userId（群方向/未读判据用；单聊路径不受影响——direct 分支
 *  仍走既有 conv.friend.userId 判据）。 */
function groupSelfUserId() {
  const acct = getCacheAccount();
  if (selfIdAcct !== acct) {
    selfIdAcct = acct;
    selfUserId = '';
    try {
      const raw = JSON.parse(localStorage.getItem(LS_SELF_ID) || 'null');
      if (raw && raw.acct === acct && raw.userId) selfUserId = String(raw.userId);
    } catch { /* non-critical */ }
  }
  return selfUserId;
}

/** 契约加性字段腿（消费点，**只有这一个**）：群行带 `selfUserId` ⇒ 直接学习为
 *  viewer 身份；缺席 ⇒ 静默跳过（禁造值）。与自证腿的等价性：两腿最终都落到
 *  `groupSelfUserId()` 的同一返回值上，而 oursBySenderId 只做 `String(sid) ===
 *  mine` 比较 ⇒ 对同一批消息两腿给出**相同**方向判定（等价性读数见 impl 报告）。 */
function learnSelfFromGroupRows(list) {
  for (const c of list || []) {
    if (c && c.kind === 'group' && c.selfUserId) learnSelfUserId(c.selfUserId);
  }
}

/** 自证写入点（唯一）：certified senderId ⇒ viewer 身份。换账号由 acct 分区
 *  隔离（groupSelfUserId 的分区核对），同账号重复学习幂等。 */
function learnSelfUserId(sid) {
  const v = String(sid || '');
  if (!v || v === 'me') return;
  const acct = getCacheAccount();
  if (selfIdAcct !== acct) { selfIdAcct = acct; selfUserId = ''; }
  if (selfUserId === v) return;
  selfUserId = v;
  try { localStorage.setItem(LS_SELF_ID, JSON.stringify({ acct, userId: v })); } catch { /* non-critical */ }
}

/** 发送成功 ⇒ 登记服务端真 id（self 识别关联源；U-b 锚定后调用）。 */
function noteSentRealId(realId) {
  if (realId !== undefined && realId !== null && realId !== '') sentMessageIds.add(String(realId));
}

/** 行级 self 识别关联：id ∈ sentMessageIds ⇒ 该行 senderId 即 viewer 身份。
 *  幂等、零额外请求（关联源 = 本机发送登记表）。 */
function noteRowForSelfLearning(m) {
  if (!m || selfUserId) return;
  if (m.senderId && sentMessageIds.has(String(m.id))) learnSelfUserId(m.senderId);
}

// ── U-b 乐观项锚定（作者报障 2026-09-14「发送的消息本地重复显示」）────────
// 「发送中的乐观项」登记表：一条待锚定的本地消息 = `{ tempId, convId, body, node,
// entry, anchoredTo }`。生命期 = 一个聊天窗（`renderChatModal` 重置、`closeChat`
// 清空，与 `chatMsgs` 同拍），条目在 POST 响应回来即出表 ⇒ 长度 ≈ 同时在飞条数。
let pendingSends = [];

/**
 * 乐观项 → 真 id 的**唯一锚定点**（数据层收敛，U-b）。
 *
 * 后置条件（POST 响应腿 与 回显认领腿 **完全一致**）：
 *  · `chatMsgs` 里该 id 恰有一条 —— 乐观项**原地换键**；服务端副本若已先到
 *    （「回显先到 · 响应后到」形态），把它移出窗口（同一条消息不得两存）；
 *  · DOM 里该 id 恰有一个节点 —— **复用乐观节点**，删掉同 id 的其它节点
 *    （「认领/替换乐观项」：不新增气泡，节点数不变）。
 *
 * 为什么必须存在（病灶）：乐观项在 POST 响应回来前挂在临时 id（`fm-tmp-N`）上，
 * 而服务端同一条消息可以经 **WS 自播帧 `message_new_self`** 或 **REST keyset 增量
 * `after=<水位>`** 两条腿先一步进窗；两条腿都按**真 messageId** 判「是不是已经在
 * 窗口里」，临时 id 让判据失配 ⇒ 真 id 那条新上一屏；随后响应再把乐观节点改键成
 * **同一个真 id** ⇒ 一个 messageId 两个节点 + 两条 chatMsgs 条目 = 视觉重复
 * （作者截图：同文同刻、两个独立气泡）。收敛必须落在**数据层**：`keyedDiff` 的
 * `existing` 是 Map（同 id 只认一个节点），幽灵节点不会被后续任何一次渲染回收
 * ⇒ 「重开窗还在」（且缓存里同 id 两条，重挂载再放大一次）。
 *
 * @param {{node: HTMLElement, entry: any, anchoredTo: string|null}} p 乐观项登记
 * @param {string|number} realId 服务端真 id
 * @returns {boolean} true = 已锚定（调用方不得再新增节点/条目）
 */
function anchorSendToRealId(p, realId) {
  const key = String(realId ?? '');
  // 窗口已关/重开（乐观节点已脱离文档）⇒ 锚定无意义，交调用方走最小改键回落。
  if (!p || !modalEls || !p.node || !p.node.isConnected) return false;
  if (!key) return false;
  p.anchoredTo = key;
  const rekeyed = { ...p.entry, id: realId };
  const i = chatMsgs.indexOf(p.entry);
  if (i >= 0) chatMsgs[i] = rekeyed;
  p.entry = rekeyed;
  p.node.dataset.messageId = key;
  for (const n of [...modalEls.flow.querySelectorAll('.fm-msg')]) {
    if (n !== p.node && n.dataset.messageId === key) n.remove();
  }
  // 数据面同键去重：**保留乐观项那一条**（= 这个节点对应的一条），服务端副本条目
  // 出窗。保留顺序不动（窗口恒升序，keyset 水位只认最大数值 id）。
  for (let j = chatMsgs.length - 1; j >= 0; j--) {
    if (chatMsgs[j] !== rekeyed && String(chatMsgs[j].id) === key) chatMsgs.splice(j, 1);
  }
  return true;
}

/**
 * **回显认领**：把一条**本机所发**的服务端消息认领到本会话未决的乐观项上
 * （「回显先到 · 响应后到」的解 —— 不新增气泡、不新增条目）。
 *
 * 只在**唯一可判**时认领（三条同时成立）：① 调用方已确证这条是本机所发
 * （WS 面判据 = 事件类型 `message_new_self`；REST 面判据 = 服务端记录里的
 * `senderId` **权威且非好友**）② 该 id 尚未归属任何已载入条目 ③ 本会话存在
 * 未锚定、正文逐字相同的乐观项。多条同正文未决（罕见）取**最老**一条 ——
 * 服务端 id 升序 = 发送序。
 *
 * 已知边界（**登记为残余风险，不掩盖**）：同一账号**另一台设备**在同一会话、
 * 同一在飞窗口内发出**逐字相同**正文的消息时，该帧会与本机乐观项同判据 ⇒ 归错。
 * 代价有界（窗口重开即自愈）；判据无法更紧：wire 上没有任何客户端令牌可回带。
 * @param {any} m 服务端消息（含真 id）
 * @param {string} convId 该消息所属会话
 * @param {boolean} ours 调用方确证「这是本机所发」
 * @returns {boolean} true = 已认领（调用方禁止再 append）
 */
function claimPendingSend(m, convId, ours) {
  if (!ours || !modalEls || !m) return false;
  const key = m.id;
  if (key === undefined || key === null || key === '') return false;
  if (!convId || String(convId) !== String(openConvId)) return false;
  if (chatMsgs.some(x => String(x.id) === String(key))) return false; // 已归属 ⇒ 无未决项
  const body = m.body || '';
  const p = pendingSends.find(x => !x.anchoredTo && x.node && x.node.isConnected
    && (!x.convId || String(x.convId) === String(convId)) && x.body === body);
  return p ? anchorSendToRealId(p, key) : false;
}

/** 出表（锚定完成 / 发送失败 / 重试换号）：登记表不随发送条数增长。 */
function forgetPendingSend(p) {
  const i = pendingSends.indexOf(p);
  if (i >= 0) pendingSends.splice(i, 1);
}

// ⑨ 增量同步：单飞 + 回补节流（①opt-A3 挂靠点见 backfillTick）。
let syncingConvId = null;       // 同一会话同时只跑一条增量链
let lastBackfillAt = 0;

// ── 好友信任模式 v1（作者令：信任的好友，新消息自动走既有「转发给 agent」
// 通道）── 纯客户端本地标记，localStorage 持久化（与 fm_seen_requests /
// fm_blocked 同一家族）。存 userId 数组（与黑名单缓存同键——userId 比
// neblinkId/Username 稳定，friend_event 的 senderId 匹配也用它）。零后端改动；
// 跨设备同步 = v2 候选。本模块是 store 唯一属主，contacts.js 经导出入口读写。
// ⑨-6（作者预授权令）：裸键 `fm_trusted` 迁入 `key()` 品牌命名空间
// （`nebflow_fm_trusted` today）。存量值经 branding.js 的 LEGACY_IRREGULAR
// 启动即迁移，数据不丢；裸键字面只保留在 branding.js 的兼容层里。
const LS_TRUSTED = key('fm_trusted');
function loadTrusted() {
  try { return new Set(JSON.parse(localStorage.getItem(LS_TRUSTED) || '[]')); } catch { return new Set(); }
}
export function isFriendTrusted(userId) {
  return !!userId && loadTrusted().has(userId);
}
export function setFriendTrusted(userId, trusted) {
  if (!userId) return;
  const s = loadTrusted();
  if (trusted) s.add(userId); else s.delete(userId);
  try { localStorage.setItem(LS_TRUSTED, JSON.stringify([...s])); } catch { /* non-critical */ }
  // Let an open chat modal refresh its header trust indicator.
  window.dispatchEvent(new CustomEvent('fm-trust-changed', { detail: { userId, trusted } }));
}

// ── 历史分页（0904 批次：加载更早消息）────────────────
// Server keyset is forward-only (store.rs list_messages: id > after ASC LIMIT
// limit, clamp 1..200 — no before/desc). 「Load older」 therefore walks BACKWARD
// in id-windows: after = oldestLoaded - 1 - WINDOW. Message ids are table-wide
// AUTOINCREMENT (gaps possible when other conversations interleave) — an empty
// window auto-steps further back (bounded). Window edge = id 1 → history start.
const HISTORY_WINDOW = 200;
// 存在性探针的单页条数（②-7 方案 A）。**1 即够**：服务端 keyset 是
// `id > after ORDER BY id ASC` —— 窗口里若有本会话的消息，首条必然是窗口内
// **最小 id**；`some(id < fromId)` 的真假只取决于这条。原实现取整窗
// （200 条 ≈ 20–25 KB）只为问一个是非题，与 ⑨ M4①「重复打开 200 条会话
// ≤1 KB」直接冲突 —— 判据等价、载荷 1/200。这不是新数值：它是探针判定的
// 最小可判定页（不是可调参数，不对外暴露）。
const PROBE_LIMIT = 1;
let chatMsgs = [];              // ascending messages currently loaded in the modal
let oldestLoadedId = 0;         // keyset anchor for load-more
let hasMoreHistory = false;
let loadingHistory = false;

// ── ⑦ 好友备注显示（作者裁定 2026-09-12，方案 §4.3(c)）────────────
// 显示优先级「备注 > 显示名」；`username`（neblinkId，NL 号）显示面不变。
// 备注缺失（null/undefined）⇒ 回落显示名，绝不渲染 null 字面。
export function personLabel(person) {
  if (!person) return '';
  return person.remark || person.name || person.neblinkId || '';
}

function loggedIn() { return !!getNeblinkState().loggedIn; }

/** drag 事件是否携带文件（③A8 判定用；无 dataTransfer 的合成事件一律视为无文件）。 */
function hasFiles(e) {
  return !!(e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files'));
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function avatarEl(person, size) {
  const a = el('span', `fm-avatar fm-avatar-${size}`);
  if (person && person.avatarUrl) {
    const img = document.createElement('img');
    img.src = person.avatarUrl;
    img.alt = '';
    a.appendChild(img);
  } else {
    a.textContent = ((person && (person.name || person.neblinkId)) || '?').trim().charAt(0).toUpperCase();
  }
  a.setAttribute('aria-hidden', 'true');
  return a;
}

/** 设备行头像（①，作者 2026-09-15：「设备在消息列表里的头像应该要和在联系人面板里
 *  一致」）。**同款判据 = 同一字形源**：联系人面板设备行（`contacts.js:401`
 *  `platformDisplay(d.platform).icon`）与本处**共用同一函数、同一返回值**
 *  （`neblink.js:401-414` 单点，禁第二份平台→图标映射），故同一设备在两面板里渲染出
 *  逐字节同形的 `<svg>`（同 viewBox / 同 path `d` / 同 fill|stroke 语义）。
 *  🔴 落槽 = `.fm-avatar` 家族（几何随既有 `-40` 档，不新开尺寸座），字形尺寸由
 *  `.fm-avatar-device svg` 单条规则决定（`friends.css`）。
 *  🔴 只换**设备**这一支：好友头像照旧走档案 `avatarUrl` / 首字母，群行照旧首字母
 *  ⇒ 三类头像互不影响（逐类读数见本批报告 §①）。
 *  ⚠ 平台映射的兜底档（未知平台）返回**显示器/笔记本形**glyph（`neblink.js:406`
 *  generic）⇒ 无名/未知平台的「空白态」设备同样有设备语义图标，不回落字母。 */
function deviceAvatarEl(device, size) {
  const a = el('span', `fm-avatar fm-avatar-${size} fm-avatar-device`);
  a.innerHTML = platformDisplay(device && device.platform).icon;
  a.setAttribute('aria-hidden', 'true');
  return a;
}

// ── Time format: today HH:mm / yesterday / M-D (§3.2) ───
// Backend timestamps are epoch SECONDS (numbers, FriendApiRoutesSpec:
// "createdAt":1234567890) or ISO strings — normalize before new Date().
function toEpochMs(ts) {
  if (ts == null || ts === '') return 0;
  if (typeof ts === 'number') return ts < 1e12 ? ts * 1000 : ts; // s vs ms
  const ms = Date.parse(ts);
  return isNaN(ms) ? 0 : ms;
}

export function fmtTime(ts) {
  const ms = toEpochMs(ts);
  if (!ms) return '';
  // 同日 = 纯时钟文本 ⇒ 走共享的 12h/24h 偏好（formatHm）。
  if (isSameDayMs(ms)) return formatHm(ms);
  const d = new Date(ms);
  const now = new Date();
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.getFullYear() === y.getFullYear() && d.getMonth() === y.getMonth() && d.getDate() === y.getDate()) {
    return t('messages.yesterday');
  }
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

/** Same calendar day as now (local) — the only branch that is a pure clock. */
function isSameDayMs(ms) {
  const d = new Date(ms);
  const now = new Date();
  return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
}

// #290: origin column (R2=A, contract-first with the Rust batch) - a message
// is agent-sent when the server-side origin says so, or via the legacy
// session-level markers. origin defaults to 'user' when absent.
function isAgentSent(m) { return !!(m && (m.origin === 'agent' || m.agentSent === true || m.kind === 'agent')); }

/** Agent 徽章文案的**唯一键**（作者 2026-09-16 决策卡 F1：全站统一为通用
 *  「Agent 代发」徽章形态，**不显示具体子 agent 名**）。
 *  🔴 单源：主对话面与好友/群面、以及会话列表摘要前缀**都**取本键 ⇒ 同一语义
 *  只有一处文案（原 zh 四值 / en 四值：`agentBadge` / `agentGroupBadge` / 摘要
 *  旧硬编码前缀 已收敛；`messages.agentGroupBadge` 键同批删除，禁死键）。 */
const AGENT_BADGE_TEXT_KEY = 'messages.agentBadge';

/** 会话行摘要（⑧，作者 2026-09-15：「就很奇怪，对一个设备说 KAI / Device /
 *  You are now friends」；⑨，作者 2026-09-16 07:55：「入群提示还是：你们已成为
 *  好友，这不对」）。
 *
 *  🔴 按**对端类型**分支 —— **三个语境三个键**（禁一个键服务两个面）：
 *   · 设备会话 ⇒ 中性空态（既有 `messages.noMessages`），**零好友关系文案**；
 *     🔴 不为设备**编造**事件文案（禁拿好友文案凑数、禁空壳占位冒充配对成功）。
 *   · **群会话 ⇒ 群语境**（`latestEvent` 事件文案 / `messages.groupNoMessages*`，
 *     见 `groupSummaryEmpty`）。
 *   · 好友 ⇒ `messages.systemNowFriends` —— 该键语义 = **好友接受流程**，其唯一
 *     合法消费点见 `onFriendEvent` 的 `friend_accepted` 分支注释
 *     「New friendship → empty conversation appears (summary: systemNowFriends)」。
 *
 *  🔴 改动史（本批根因）：本函数此前 **kind-blind**
 *  （`conv.kind === 'device' ? … : systemNowFriends`）⇒ 非 device 的空会话
 *  （好友**与群**）一律套好友接受流程文案，群行摘要渲染成「你们已成为好友」。
 *  处置 = **拆键**：群面走新键；`systemNowFriends` 的**键值与消费点对好友面
 *  **逐字不变**（🔴 禁只改共用键的值 —— 那会反噬好友场景）。
 *  有 `lastMessage.body` 时三面**同走正文**（不改：正文是服务端载荷，不是语境文案）。 */
function summaryOf(conv) {
  const m = conv.lastMessage;
  if (!m || !m.body) {
    if (conv && conv.kind === 'device') return t('messages.noMessages');
    if (conv && conv.kind === 'group') return groupSummaryEmpty(conv);
    return t('messages.systemNowFriends');
  }
  return (isAgentSent(m) ? `[${t(AGENT_BADGE_TEXT_KEY)}] ` : '') + m.body;
}

/** 群面空会话摘要（`summaryOf` 的群分支；2026-09-16 拆键产物；本批接 `latestEvent`）。
 *
 *  🔴 取名源**两腿，按优先级**（两腿都是**契约字段**，无第三条路；零新增请求）：
 *   · ① **事件腿**（本批新增）= 群行加性键 `latestEvent`（正典 §2.4；归一出口 =
 *     `friendsApi.normalizeGroupRow` ⇒ `conv.latestEvent`，🔴 渲染面**禁直读 wire 键**）
 *     ⇒ 六 kind 事件文案，成员名取 `subject.name`（= wire `display_name`；**唯一**显示名
 *     来源 = 服务端 `COALESCE(name, username, user_id)` ⇒ **禁猜名 / 禁自造第二显示名源**）。
 *     事件缺席（老服务端 / 上线前无事件行的群 / 未知 kind）⇒ 落到腿② —— 那是**正常态**：
 *     不渲染空系统行、不报错、不回退编造文案。
 *   · ② **群名腿**（本批前既有形态，**逐字节不变**）= `GroupSummary.title` ⇒ `conv.title`；
 *     两态（禁造值）：群名非空 ⇒ `messages.groupNoMessagesNamed`（含群名占位）；
 *     群名空白 ⇒ `messages.groupNoMessages`（禁与 `groupTitleOf` 的「群聊」占位拼成重复）。
 *
 *  🔴 方向**由 `kind` 区分**，**禁**靠 `actor` 反推（正典 §2.2：`member_joined` 的
 *  `actor` = **邀请人**、`member_left` 的 `actor` = 退群者本人、`member_removed` 的
 *  `actor` = 移除者）。
 *  🔴 **禁借 `memberAvatars`** 做事件身份：其元素只有 `userId`/`avatar`、**无名字**，
 *  且顺序语义被契约**显式禁止**做业务判定 —— `friendsApi.js` 符号 `normalizeAvatarPreview`
 *  （建位快照 773-774 行）「顺序 = 服务端加入序」＋「禁把索引 0 当群主」；该契约
 *  **不因本批变更**（本批对其归一出口与顺序语义**零改动**）。
 *  🔴 `at` 是秒级读数、客户端只见**一条**事件 ⇒ **禁**用于排序 / 比序。 */
function groupSummaryEmpty(conv) {
  const ev = groupEventText(conv && conv.latestEvent);
  if (ev) return ev;
  const title = conv && typeof conv.title === 'string' ? conv.title.trim() : '';
  return title
    ? t('messages.groupNoMessagesNamed', { name: title })
    : t('messages.groupNoMessages');
}

/** 事件 → 群行摘要文案（**六 kind 全映射**，正典 §3.2 文案族；唯一消费点 = groupSummaryEmpty）。
 *
 *  用谁的名字（逐条，正典 §3.2「谁出现在文案里」；文案键两侧同批落地）：
 *   · `group_created` ⇒ `subject.name`（= 创建者；该 kind 的 actor == subject）；
 *   · `member_joined` ⇒ `subject.name`（= 入群者 = 作者方向令原始诉求）；
 *     **若 `actor.userId !== subject.userId` ⇒ 邀约分支**：「`actor.name` 邀请了 `subject.name`」
 *     （`actor` = **邀请人**，正典 §2.2）—— 判式按 **userId** 比（显示名可重名 ⇒ 禁拿名字当身份）；
 *   · `member_left` ⇒ `subject.name`（= 退群者；`actor` 与他同一人）；
 *   · `member_removed` ⇒ `subject.name`（= 被移出者）；`actor` = 移除者 —— 文案取正典 §3.2 的
 *     **基形**，执行者进文案是该条的**可选**形态，本批不落（不造零消费点的死键）；
 *   · `member_role_granted` / `member_role_revoked` ⇒ `subject.name`（= 被设 / 被撤者）；
 *     方向由 `kind` 区分（**禁**靠 `actor` 反推）。
 *
 *  🔴 未命中 ⇒ 返回 `null` = **忽略**（未知 `kind`，或卡片缺名 —— 归一层已把这两种折成
 *  「键缺席」，本函数是**第二道闸**）：零异常、零占位文案、零键名回落字符串
 *  （`i18n.js` 缺键回落 = 返回键名本身 ⇒ 未映射的 kind **不得**进 `t()`）。 */
function groupEventText(ev) {
  if (!ev || typeof ev !== 'object') return null;
  const subj = ev.subject && typeof ev.subject.name === 'string' ? ev.subject.name.trim() : '';
  if (!subj) return null;
  const actorEv = ev.actor;
  const actorName = actorEv && typeof actorEv.name === 'string' ? actorEv.name.trim() : '';
  const actorId = actorEv && typeof actorEv.userId === 'string' ? actorEv.userId : '';
  const subjId = typeof ev.subject.userId === 'string' ? ev.subject.userId : '';
  switch (ev.kind) {
    case 'group_created': return t('messages.groupEventCreated', { name: subj });
    case 'member_joined':
      return (actorName && actorId && subjId && actorId !== subjId)
        ? t('messages.groupEventInvited', { actor: actorName, name: subj })
        : t('messages.groupEventJoined', { name: subj });
    case 'member_left': return t('messages.groupEventLeft', { name: subj });
    case 'member_removed': return t('messages.groupEventRemoved', { name: subj });
    case 'member_role_granted': return t('messages.groupEventRoleGranted', { name: subj });
    case 'member_role_revoked': return t('messages.groupEventRoleRevoked', { name: subj });
    default: return null;
  }
}

function totalUnread() {
  return conversations.reduce((s, c) => s + (c.unreadCount || 0), 0);
}

function updateBadge() {
  const n = loggedIn() ? totalUnread() : 0;
  setActivityBadge('messages-btn', n, t('messages.ariaUnread', { n }));
}

// ── Conversation list (§3.2) ─────────────────────────────
/** 会话列表刷新。
 *  `friends` 语义（⑨ M1②「再次开面板 = 1」）：
 *   · `'reuse'`（默认，面板重复切换）——好友列表命中 L1 且未过 TTL ⇒ 只取会话
 *     列表（1 次往返）；好友列表为空或已过期 ⇒ 自动升级为 `'force'`
 *     （正确性优先于省一次往返 —— §3.3 not-friend 门禁吃的是这份缓存）。
 *   · `'force'`（首次装载 / 好友域变更 / reconnect / 未知会话）——会话 + 好友
 *     并行取（2 次往返，与今天一致）。 */
async function refreshConversations({ friends = 'reuse' } = {}) {
  if (!loggedIn()) { conversations = []; friendsCache = []; friendsFetchedAt = 0; renderList(); return; }
  const wantFriends = friends === 'force'
    || friendsCache.length === 0
    || (Date.now() - friendsFetchedAt) > CACHE_TTL_MS;
  try {
    const [convs, fr, grp] = await Promise.all([
      api.getConversations(),
      wantFriends ? api.getFriends() : Promise.resolve(null),
      // 群取数独立兜底（禁拖垮单聊刷新）：失败/不可用按 refreshGroups 三态
      // 语义落（null = keep-last-known；404 = 空集 + fail-closed 翻面）。
      refreshGroups(),
    ]);
    const direct = convs || [];
    // 设备会话行（设备会话统一批 MVP-1 · 卡 A3 合并点）：**本账号自有设备** ⇒
    // 与好友行/群行同列同排序（排序键 = lastMessage.createdAt 不变）。
    // MVP-2：服务端 `kind:'device'` 行优先（未读/预览权威），peers 派生行只补空缺
    // （归并见 `deviceConvs`）。🔴 服务端设备行**本来就会**出现在 `direct` 里
    // （网关透传 `kind`/`deviceId`）⇒ 必须先从 `direct` 剔除再并入，否则同一设备
    // 出两行（一行无设备档案、一行派生）。
    const serverDeviceRows = direct.filter(c => c && c.kind === 'device');
    const directNonDevice = direct.filter(c => !(c && c.kind === 'device'));
    const devices = deviceConvs(serverDeviceRows);
    if (grp) {
      // pendingInvites 的消费方是 contacts 面的群邀请区（它自己调 refreshGroups，
      // 与面板独立刷新同构）；messages 面只消费 groups。
      conversations = directNonDevice.concat(grp.groups || []).concat(devices);
    } else {
      // keep-last-known：群面取数失败（auth/网络/5xx）⇒ 既有群行原样保留，
      // 只刷新单聊行（与好友域「失败≠空」同口径，禁闪空列表）。
      const prevGroups = conversations.filter(c => c && c.kind === 'group');
      conversations = directNonDevice.concat(prevGroups).concat(devices);
    }
    conversations = conversations.sort((a, b) =>
      (toEpochMs(b.lastMessage?.createdAt) || 0) - (toEpochMs(a.lastMessage?.createdAt) || 0));
    // 契约加性 viewer 字段腿：群行行内 selfUserId（§1.1 #2）+ 邀请信封面
    // （refreshGroups 出口，§1.1 #3）—— 在场则直接学习；缺席 ⇒ 无操作，
    // groupSelfUserId 继续走发送关联自证。
    learnSelfFromGroupRows(conversations);
    if (grp && grp.selfUserId) learnSelfUserId(grp.selfUserId);
    if (fr) { friendsCache = fr.friends || []; friendsFetchedAt = Date.now(); }
  } catch { /* keep last known */ }
  renderList();
  updateBadge();
}

function renderList() {
  const box = document.getElementById('fm-conversations');
  if (!box) return;
  box.innerHTML = '';
  updateBadge();

  if (!loggedIn()) {
    const empty = el('div', 'fm-login-empty');
    empty.appendChild(el('div', 'fm-login-text', t('messages.loginRequired')));
    const btn = el('button', 'glass-control fm-login-btn', t('messages.login'));
    btn.addEventListener('click', () => openLoginModal());
    empty.appendChild(btn);
    box.appendChild(empty);
    return;
  }
  if (conversations.length === 0) {
    box.appendChild(el('div', 'fm-empty', t('messages.empty')));
    return;
  }
  for (const conv of conversations) box.appendChild(convRow(conv));
  createIconsIn(box);
}

function convRow(conv) {
  const isGroup = conv.kind === 'group';
  // 设备行（MVP-1）：形态范本 = 群行（`data-group='1'` + chip），差异 = tag 键与
  // 数据源（设备无好友档案 ⇒ 名字取设备自报，见 deviceLabel）。
  const isDevice = conv.kind === 'device';
  const row = el('div', 'fm-row fm-conv-row');
  row.setAttribute('role', 'option');
  row.setAttribute('tabindex', '0');
  row.setAttribute('aria-selected', String(conv.conversationId === openConvId));
  row.dataset.conversationId = conv.conversationId;
  if (isGroup) row.dataset.group = '1'; // QA 断言面：群行可机械定位
  if (isDevice) row.dataset.device = '1'; // QA 断言面：设备行可机械定位（同族口径）

  // O④（**现行有效**）：群**自身**头像仍无字段；群行头像 = **成员头像九宫格**
  // （唯一实现 = friendGroups.groupAvatarGrid），名册/字段不可得时回退标题首字母
  // 那一枚（现状形态 = 降级态，非被删态）。数据面 = conv.memberAvatars（归一读点
  // 只在 friendsApi.normalizeGroupRow；渲染面禁读 wire 键）。
  // ④①（作者 2026-09-15）：**设备行头像**改为与联系人面板设备段**同款**的
  // 平台图标（`deviceAvatarEl`，字形源 = `platformDisplay` 单点）——原形态是
  // `avatarEl` 的**首字母占位**（与好友/群同款），与联系人面板里那台设备的
  // 图标不一致（红读数见本批报告 §①）。单聊行照旧朋友档案。
  const avatarPerson = isGroup ? { name: groupTitleOf(conv) } : conv.friend;
  if (isDevice) {
    row.appendChild(deviceAvatarEl(conv.device, 40));
  } else if (isGroup) {
    row.appendChild(groupAvatarGrid(conv.memberAvatars, 40, conv.memberCount)
      || avatarEl(avatarPerson, 40));
  } else {
    row.appendChild(avatarEl(avatarPerson, 40));
  }
  const meta = el('div', 'fm-row-meta');
  const top = el('div', 'fm-conv-top');
  const rowName = isDevice ? deviceLabel(conv.device) : (isGroup ? groupTitleOf(conv) : personLabel(conv.friend));
  top.appendChild(el('span', 'fm-row-name', rowName));
  if (isGroup) top.appendChild(el('span', 'fm-group-tag', t('messages.groupTag')));
  if (isDevice) top.appendChild(el('span', 'fm-group-tag fm-device-tag', t('messages.deviceTag')));
  top.appendChild(el('span', 'fm-conv-time', fmtTime(conv.lastMessage?.createdAt)));
  meta.appendChild(top);
  const bottom = el('div', 'fm-conv-bottom');
  bottom.appendChild(el('span', 'fm-conv-summary', summaryOf(conv)));
  if (conv.unreadCount > 0) {
    const b = el('span', 'fm-row-badge', conv.unreadCount > 99 ? '99+' : String(conv.unreadCount));
    b.setAttribute('aria-label', t('messages.ariaUnread', { n: conv.unreadCount }));
    bottom.appendChild(b);
  }
  meta.appendChild(bottom);
  row.appendChild(meta);

  const open = () => openConversation(conv.conversationId, row);
  row.addEventListener('click', open);
  row.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); open(); }
    // R5 listbox nav
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const rows = [...box_rows()];
      const i = rows.indexOf(row);
      const next = rows[i + (e.key === 'ArrowDown' ? 1 : -1)];
      if (next) /** @type {HTMLElement} */ (next).focus();
    }
  });
  return row;
}

function box_rows() {
  return document.querySelectorAll('#fm-conversations .fm-conv-row');
}

// ── Chat modal (§2.3/§3.3) ───────────────────────────────
function closeChat() {
  // Every close path (ESC, backdrop, ×, post-send) funnels through here —
  // detach the document listener centrally so open/close cycles stay
  // symmetric (D4, mem-diag 20260907). removeEventListener is idempotent.
  document.removeEventListener('keydown', escClose);
  if (modalEls) {
    modalEls.overlay.remove();
    modalEls = null;
  }
  // U-b：窗口一关，在飞乐观项的节点即脱离文档 ⇒ 登记随之作废（在飞 POST 的
  // 续接腿会因 `node.isConnected === false` 自动走最小回落）。
  pendingSends = [];
  // 附件上传卡：解除挂载（进度帧不再画到已关闭的窗上），并清掉已终态卡片
  // （在飞件保留 —— 上传不因关窗而静默中止，其终态由卡片/后续开窗承接）。
  if (openConvId) clearSettledUploads(openConvId);
  detachUploadCards();
  openConvId = null;
  renderList(); // refresh aria-selected + any unread changes
  // A18: focus return — the triggering row may have been detached by the
  // post-open renderList (unread clear), so refind by conversation id.
  const back = (triggeringRow && triggeringRow.isConnected) ? triggeringRow
    : (triggeringConvId ? document.querySelector(`#fm-conversations .fm-conv-row[data-conversation-id="${CSS.escape(triggeringConvId)}"]`) : null);
  if (back) back.focus();
  triggeringRow = null;
  triggeringConvId = null;
}

function currentConv() {
  if (modalEls && modalEls.conv) return modalEls.conv;
  return conversations.find(c => c.conversationId === openConvId) || null;
}

/** 群组合头像落槽（**唯一**落槽实现：窗头 `.fm-modal-avatar` + 抽屉信息头
 *  `.fm-gs-head-avatar` 共用）。
 *  数据源优先级（**两级，禁第三级**）：
 *   ① `cells`（本窗已拉到的成员名册，来源 = `getGroupMembers`，含显示名）；
 *   ② `conv.memberAvatars`（会话列表行字段，归一出口产物；渲染面禁读 wire 键）。
 *  两级都空（名册未到 / 字段缺席 / 畸形 / 群 0 人）⇒ 回退标题首字母那一枚
 *  （现状形态 = 降级态）。逐格无 `avatarUrl` 由 groupAvatarGrid 内逐格首字母兜底。 */
function paintGroupAvatarInto(host, conv, cells) {
  if (!host || !conv) return;
  const list = (Array.isArray(cells) && cells.length) ? cells : conv.memberAvatars;
  const total = Number(conv.memberCount) || (Array.isArray(list) ? list.length : 0);
  host.innerHTML = '';
  host.appendChild(groupAvatarGrid(list, 40, total) || avatarEl({ name: convTitleLabel(conv) }, 40));
}

/** 开着的群窗：组合头像就地重打（`fm-groups-changed` 到达 ⇒ 成员集可能已变）。
 *  触发面 = 方案 §3.2.2 的**唯一现成广播面**；数据零新增请求（复用群列表行字段
 *  + 抽屉自己的成员面拉取）。会话行可能已被 refreshConversations 换成新对象 ⇒
 *  先把最新读数同步回本窗捕获的 conv（窗头与抽屉共用同一份，禁两套数据）。 */
function refreshOpenGroupHeaderAvatar() {
  if (!modalEls) return;
  const slot = modalEls.overlay.querySelector('.fm-modal-avatar');
  const drawerAvatar = modalEls.overlay.querySelector('.fm-gs-head-avatar');
  if (!slot && !drawerAvatar) return;
  const conv = currentConv();
  if (!conv || conv.kind !== 'group') return;
  const fresh = conversations.find(c => c.conversationId === conv.conversationId);
  if (fresh && fresh !== conv) {
    conv.memberAvatars = fresh.memberAvatars;
    if (fresh.memberCount !== undefined) conv.memberCount = fresh.memberCount;
  }
  const cells = groupMemberAvatars.get(String(conv.conversationId));
  paintGroupAvatarInto(slot, conv, cells);
  paintGroupAvatarInto(drawerAvatar, conv, cells);
}

function isStillFriend(conv) {
  return !!(conv && conv.friend && friendsCache.some(f => f.userId === conv.friend.userId));
}

/** 已读上报 + 角标/列表同步（开窗路径与增量补齐路径共用，零第二实现）。 */
function markConvRead(conv) {
  const last = chatMsgs[chatMsgs.length - 1];
  if (!conv || !last) return;
  conv.unreadCount = 0;
  api.markConversationRead(conv.conversationId, last.id).catch(() => {});
  updateBadge();
  renderList();
}

/** ⑨ 把当前已加载窗口写进 L2 缓存（水位 = 已见到过的最大数值 id）。 */
function persistConversation(conv) {
  if (!conv || !conv.conversationId || chatMsgs.length === 0) return;
  let watermark = 0;
  for (const m of chatMsgs) { const n = Number(m.id); if (Number.isFinite(n) && n > watermark) watermark = n; }
  saveConversation(conv.conversationId, chatMsgs, {
    watermark,
    lastMessageAt: toEpochMs(conv.lastMessage?.createdAt),
  });
}

/** ⑨ keyset 水位 = 已加载窗口里的最大数值 id（`after=` 游标的唯一取值来源）。 */
function syncWatermark() {
  let max = 0;
  for (const m of chatMsgs) { const n = Number(m.id); if (Number.isFinite(n) && n > max) max = n; }
  return max;
}

/**
 * ⑨ 增量同步：把打开的会话窗口补到服务端水位。
 *
 * 契约：**只走 `after=<水位>` 的 keyset 前进拉取**，永不重取尾窗
 * （M1③ 红线：出现尾窗全量 = 不合）。单页 `SYNC_PAGE` 条；返回满页才继续翻，
 * 最多 `MAX_SYNC_PAGES` 页（4 × 50 = 200 = 既有尾窗带宽上限 ⇒ 增量补齐的
 * 单次带宽不超过今天一次尾窗）。同一会话单飞（`syncingConvId`）。
 *
 * @param {string} conversationId
 * @param {{pages?: number, trigger?: string}} [opts] pages = 最大翻页数（默认 1：单次探测）；
 *   trigger = 补拉触发点（W14 观测口径，除日志外零行为影响）
 *   —— 本行 2026-09-14 交付批补齐：实现早已解构 `trigger`，而 JSDoc 未同步 ⇒
 *   3 处调用点恒报 TS2339/TS2353。本文件被本批触碰，按 `check-js-types.mjs`
 *   「new/touched files must be checkJs-clean」须自清（纯类型注释订正、零行为改动）。
 * @returns {Promise<number>} 补进来的条数
 */
async function syncConversation(conversationId, { pages = 1, trigger = 'open' } = {}) {
  // W14（§3.3）：修前零日志 `return 0`。单飞命中 = 「本拍这条补拉**没发生**」——
  // 若把它吞掉，「用户看到没变化」与「链正在跑、稍后自愈」就不可分。
  if (!conversationId || syncingConvId === conversationId) {
    // 字段口径（判据②）：与后端 `droppedWarn` **同形**——`conversationId=` + `messageId=`
    // （本分支无消息面 ⇒ 显式写 `<none>`，不允许「键缺席」这一形态）+ `reason=`。
    console.warn(`[fm] syncConversation 跳过 conversationId=${conversationId} messageId=<none> reason=`
      + `${!conversationId ? 'no_conversation_id' : 'single_flight_in_progress'} trigger=${trigger}`
      + ` syncingConvId=${syncingConvId}`);
    return 0;
  }
  const maxPages = Math.max(1, Math.min(Number(pages) || 1, MAX_SYNC_PAGES));
  syncingConvId = conversationId;
  let fetched = 0;
  try {
    for (let i = 0; i < maxPages; i++) {
      const after = syncWatermark();
      // W15（§3.3）：无可信水位（缓存缺席/temp id 占位）⇒ 不猜、不改窗口；但必须留痕，
      // 否则「补拉链空转」与「已到水位」同形。
      if (after <= 0) {
        console.warn(`[fm] syncConversation 无可信水位（不猜、不改窗口）conversationId=${conversationId} `
          + `messageId=<none> reason=no_trusted_watermark after=${after} page=${i} trigger=${trigger}`);
        break;
      }
      let batch = [];
      try { batch = await api.getMessages(conversationId, { after, limit: SYNC_PAGE }); }
      catch (e) {
        // W16（§3.3）：修前零日志 break。网络/鉴权失败**保留已渲染内容不冒泡**（不变），
        // 但必须留痕带 err.message —— 否则「服务端挂了」看起来像「没有新消息」。
        console.warn(`[fm] syncConversation 取数失败（保留已渲染内容）conversationId=${conversationId} `
          + `messageId=<none> reason=fetch_failed after=${after} page=${i} trigger=${trigger} err=${e && e.message}`);
        break;
      }
      if (openConvId !== conversationId || !modalEls) {
        // W17（§3.3）：正常态（会话已换/窗已关）⇒ 低噪 `debug` 档，不报 warn。
        console.debug(`[fm] syncConversation 停止（会话已换/窗已关）conversationId=${conversationId} `
          + `messageId=<none> reason=conversation_switched after=${after} page=${i} openConvId=${openConvId} trigger=${trigger}`);
        break;
      }
      const fresh = (batch || []).filter(m => !chatMsgs.some(x => String(x.id) === String(m.id)));
      if (fresh.length) {
        appendMessages(fresh);
        fetched += fresh.length;
        const conv = conversations.find(c => c.conversationId === conversationId);
        if (conv) {
          conv.lastMessage = fresh[fresh.length - 1];
          markConvRead(conv);       // 窗开着 ⇒ 到即已读（与 WS 帧路径同语义）
          persistConversation(conv);
        }
      }
      if ((batch || []).length < SYNC_PAGE) break; // 不满页 ⇒ 已到服务端水位
    }
  } finally {
    syncingConvId = null;
  }
  return fetched;
}

async function openConversation(conversationId, rowEl) {
  const conv = conversations.find(c => c.conversationId === conversationId);
  if (!conv) return;
  // 设备会话（MVP-1）：同窗骨架、另一条数据面（网关本机 dropbox 腿）⇒ 走设备分支。
  if (conv.kind === 'device') { openDeviceConversation(conv, rowEl); return; }
  triggeringRow = rowEl || null;
  triggeringConvId = conversationId;
  openConvId = conversationId;
  renderChatModal(conv);
  // 群窗：惰性装载成员名册（发送者名回填；失败 = 降级无名字，消息不受影响）。
  if (conv.kind === 'group') hydrateGroupSenderNames(conv);

  // ⑨ 热路径（有缓存）：同步读缓存首屏（零往返），随后一次极小增量核对
  // （`after=<水位>&limit=SYNC_PAGE`）—— 无新消息 = 空响应，**不是**尾窗重取。
  const cached = loadConversation(conversationId);
  if (cached) {
    chatMsgs = cached.msgs.slice();
    oldestLoadedId = chatMsgs.length ? (Number(chatMsgs[0].id) || 0) : 0;
    hasMoreHistory = false;
    renderMessages(chatMsgs);
    markConvRead(conv);
    if (modalEls) modalEls.input.focus();
    if (chatMsgs.length && oldestLoadedId > 1) probeOlderHistory(conversationId, oldestLoadedId);
    // TTL 过期 ⇒ 条目「可疑」⇒ 允许多补几页（仍全部是增量页，绝无尾窗）。
    await syncConversation(conversationId, { pages: cached.fresh ? 1 : MAX_SYNC_PAGES, trigger: 'open_cached' });
    persistConversation(conv);
    return;
  }

  // ⑩ 冷路径（无本地缓存）：**消息区不得空白**（作者 2026-09-14 17:21 令）。
  // 立即插入可见加载态；随后仍是一次尾窗拉取（M6「冷缓存不倒退」基线不变：
  // 本行只改「空窗期显示什么」，**不改门槛顺序、不缩短任何等待**）。
  // 🔴 加载态**只**服务这一档 —— 有缓存时首帧来源是缓存本身，绝不进加载态，
  // 否则就成了「用转圈掩盖慢」。
  showFlowLoading();

  // ⑨ 冷路径（无缓存）：= 今天的行为，一次尾窗拉取（M6 冷缓存不倒退基线）。
  // Initial window: anchor on the conversation list's cached newest message id
  // and take one window backwards (after = anchor - WINDOW). Missing anchor
  // (fresh/empty conversation) → from 0, which is then the entire history.
  const anchor = Number(conv.lastMessage && conv.lastMessage.id);
  const after = Number.isFinite(anchor) && anchor > HISTORY_WINDOW ? anchor - HISTORY_WINDOW : 0;
  let msgs = [];
  try { msgs = await api.getMessages(conversationId, { after, limit: HISTORY_WINDOW }); } catch { /* empty */ }
  if (openConvId !== conversationId) return; // replaced meanwhile
  chatMsgs = msgs.slice();
  oldestLoadedId = chatMsgs.length ? (Number(chatMsgs[0].id) || 0) : 0;
  // ②-7 方案 A「不自证不显示」：`oldestLoadedId > 1` 只是「表级自增号不等于 1」，
  // **不构成**「本会话还有更早」的证明（他人会话占号 ⇒ 判据恒真 = 按钮常亮的
  // 病灶）。这里先一律不渲染按钮，交给后台探针探到更早再插入。
  hasMoreHistory = false;
  renderMessages(chatMsgs);
  markConvRead(conv);
  if (modalEls) modalEls.input.focus();
  // 确定态 ①：本会话第一条 id = 表首 id(1) ⇒ 确定没有更早，探针无需发。
  // 否则后台探针（与首屏解耦：气泡已渲染，按钮命中后再插入）。
  if (chatMsgs.length && oldestLoadedId > 1) {
    probeOlderHistory(conversationId, oldestLoadedId);
  }
  persistConversation(conv);
}

/** 窗头/弹窗标题单点（群 = 群名；单聊 = 既有 personLabel 链，备注 > 显示名）。 */
function convTitleLabel(conv) {
  // 设备会话（MVP-1 + U3）：窗头名 = 设备显示名（`deviceLabel` 单点：描述 > 设备名 > 占位）。
  // 🔴 U3（root 2026-09-15 #600）：**无名称无描述**的设备 ⇒ 窗头显示**占位文案**
  // （`neblink.unknownDevice`）而**非 device id**（与作者 ⑤「不显示设备码」同族精神）
  // ⇒ 走 `deviceLabel` 的窗头专用形态（唯一实现内的一支，不新开第二份名字链）。
  if (conv && conv.kind === 'device') return deviceLabel(conv.device, { forWindowTitle: true });
  return (conv && conv.kind === 'group') ? groupTitleOf(conv) : personLabel(conv && conv.friend);
}

// ── 群气泡发送者名（腿B §5.2 #2 的群新增面）───────────────────────────
// 名册来源 = GET /api/groups/{id}/members（主卡:249 接口清单；显示名而非好友
// 备注，主卡 H 节口径）。开群窗时惰性取一次；首帧早于名册时先挂空槽（带
// data-sender-id），名册到达后就地回填 —— 消息本体渲染不受名册成败影响。
const groupMemberNames = new Map(); // conversationId -> Map(senderId -> displayName)
// 窗头组合头像的**名册腿**（conversationId -> [{userId,name,avatarUrl}]）：来源 =
// 与发送者名**同一次** `getGroupMembers`（零新增请求、零 N+1；方案 §3.2.5「群会话
// 窗头名册可得」）。首帧早于名册时用会话行字段 `memberAvatars` 先画（列表面同源），
// 名册到达后就地升级。
const groupMemberAvatars = new Map();

/** 群发送者显示名（未命中 ⇒ ''，渲染层留空槽等待回填）。 */
function groupSenderNameOf(conv, senderId) {
  const map = groupMemberNames.get(String(conv && conv.conversationId));
  return (map && map.get(String(senderId))) || '';
}

/** 开群窗时的成员名册惰性装载（每窗一次；失败降级为无发送者名）。 */
async function hydrateGroupSenderNames(conv) {
  try {
    const members = await api.getGroupMembers(conv.conversationId);
    // 成员面信封 `selfUserId`（契约终版 §1.1 #7）= viewer 身份的另一条权威腿：
    // 学到即收敛整窗方向判据（含已渲染气泡的下一次渲染）。
    if (members && members.selfUserId) learnSelfUserId(members.selfUserId);
    const map = new Map();
    const cells = [];
    for (const mem of members || []) {
      if (mem && mem.userId) map.set(String(mem.userId), mem.name || String(mem.userId));
      // 窗头组合头像格子（身份键 userId 与列表行字段同空间；顺序纯透传 ——
      // 🔴 禁把索引 0 当群主，正典 §A.2 owner 位置不确定）。
      if (mem && mem.userId) cells.push({ userId: mem.userId, name: mem.name, avatarUrl: mem.avatarUrl });
    }
    groupMemberNames.set(String(conv.conversationId), map);
    groupMemberAvatars.set(String(conv.conversationId), cells);
    // 名册到达 ⇒ 窗头组合头像就地重打（同一次拉取的产物，零新增请求）。
    refreshOpenGroupHeaderAvatar();
    // 就地回填：名册晚于首帧到达时，补齐已渲染气泡的发送者名（幂等）。
    if (modalEls && openConvId === conv.conversationId) {
      for (const s of modalEls.flow.querySelectorAll('.fm-msg-sender[data-sender-id]')) {
        const nm = map.get(s.dataset.senderId);
        if (nm && !s.textContent) s.textContent = nm;
      }
    }
  } catch { /* 名册失败 = 降级为无发送者名（禁因名册失败丢消息） */ }
}

function renderChatModal(conv) {
  document.getElementById('fm-chat-overlay')?.remove();

  const overlay = el('div', 'cfg-modal-overlay');
  overlay.id = 'fm-chat-overlay';

  const modal = el('div', 'cfg-modal fm-modal');
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-label', convTitleLabel(conv));

  // header: name · neblinkId | trust slot | ×
  // 窗头转发按钮已移除（作者 2026-09-12 裁定，方案 §3.1 S5）：转发入口只保留
  // 按消息的两条 —— 气泡内按钮 + 气泡右键，共用 forwardBubble（无第二实现）。
  const header = el('div', 'fm-modal-header');
  // 群窗头 = 组合头像（40px 档）+ 群名 + 成员数（方案 §3.1 P1；**窗头此前无头像**，
  // 现取 avatarEls=0 ⇒ 本批补齐）。头像元素由下方 groupAvatarSlot 持有，随名册/
  // 群列表变更就地重打（禁整窗重建）。
  const groupAvatarSlot = conv.kind === 'group' ? el('span', 'fm-modal-avatar') : null;
  if (groupAvatarSlot) {
    paintGroupAvatarInto(groupAvatarSlot, conv);
    header.appendChild(groupAvatarSlot);
  }
  const title = el('div', 'fm-modal-title');
  title.appendChild(el('span', 'fm-modal-name', convTitleLabel(conv)));
  // 描述入口小键的**面板挂载点**（devrow）：面板仍在窗头下方占一行，键本身进窗头行。
  let deviceDescRow = null;
  // 群窗副行 = 成员数（有读数才挂）；单聊副行不变（neblinkId）。
  if (conv.kind === 'group') {
    if (conv.memberCount > 0) title.appendChild(el('span', 'fm-modal-id', t('messages.memberCount', { n: conv.memberCount })));
  } else if (conv.kind === 'device') {
    // 设备窗副行：**平台标签**（⑤a，作者 2026-09-15：「一是不要显示设备码」——
    // 原副行 = 平台 + `deviceId`，deviceId 已从 `deviceSubLabel` 摘除）+ 在线态徽章。
    // 在线态 = `presenceBadgeHTML` **唯一实现**（与联系人设备段同源；O10 禁第二份判据与文案）。
    title.appendChild(el('span', 'fm-modal-id', deviceSubLabel(conv.device)));
    // 🔴 devrow（作者 2026-09-16 07:53 截图令）：描述入口小键与**名块同一 flex 行**
    // （名右侧内联、垂直居中）。旧形态（uifix3 ②）把键挂在 `.fm-device-desc` 行里 ⇒
    // 键**独占一行**、名块与被点面垂直相隔一整行（红锚读数：名 bottom 164.5 vs 键 top 178）。
    // 现在键入 `title` —— 与 `.fm-modal-name` **同一个 flex 行**（同行判据即
    // `btn.parentElement === name.parentElement`），行本体退化为面板挂载点。
    // 徽章仍在其后挂 ⇒ `margin-left:auto` 照旧把在线态贴右（窗头其余几何零改动）。
    deviceDescRow = buildDeviceDescRow(conv, title);
    const badge = presenceBadgeHTML({ ...(conv.device || {}), isLocal: false });
    if (badge) {
      const pslot = el('span', 'fm-device-presence fm-modal-presence');
      pslot.innerHTML = badge;
      title.appendChild(pslot);
    }
  } else {
    title.appendChild(el('span', 'fm-modal-id', conv.friend?.neblinkId || ''));
  }
  header.appendChild(title);
  // 群设置入口（仅群窗）：成员/邀请/改名/退群/解散抽屉（friendGroups.js 唯一属主）。
  // H（决策卡 C/D/G/H）：入口**在会话窗头群名处** —— 窗头群名/标题区整块可点
  // （微信原样），原 `.fm-gs-open` 图标钮**保留**为同族第二入口（既有断言面
  // `.fm-gs-open` 不破；两入口共用同一个 mountDrawer，禁第二套抽屉实现）。
  let groupSettingsMounted = false;
  if (conv.kind === 'group') {
    const settingsBtn = el('button', 'fm-gs-open');
    settingsBtn.type = 'button';
    settingsBtn.innerHTML = '<i data-lucide="users"></i>';
    settingsBtn.title = t('messages.groupSettings');
    settingsBtn.setAttribute('aria-label', t('messages.groupSettings'));
    const mountDrawer = () => {
      if (!modalEls) return;
      modalEls.overlay.querySelector('.fm-group-settings')?.remove();
      const drawer = buildGroupSettings(conv, {
        toast: (s) => modalToast(s),
        close: () => closeChat(),
        onChanged: () => {
          // 成员/标题就地变化：列表/窗头重打 + 抽屉重建（成员数/踢人态刷新）。
          renderList();
          updateModalTitle(conv);
          refreshGroupHeaderAvatar();
          if (modalEls && groupSettingsMounted) mountDrawer();
        },
      });
      header.insertAdjacentElement('afterend', drawer);
      groupSettingsMounted = true;
    };
    /** 窗头组合头像就地重打（成员/群列表变更 ⇒ 九宫格失效重算；禁整窗重建）。 */
    const refreshGroupHeaderAvatar = () => refreshOpenGroupHeaderAvatar();
    settingsBtn.addEventListener('click', () => {
      if (groupSettingsMounted) {
        modalEls?.overlay.querySelector('.fm-group-settings')?.remove();
        groupSettingsMounted = false;
        return;
      }
      mountDrawer();
    });
    header.appendChild(settingsBtn);
    // 窗头群名处入口（H）：title 区可点/可键盘触发 = 打开（收起）群信息抽屉。
    const toggleFromTitle = () => { if (groupSettingsMounted) { modalEls?.overlay.querySelector('.fm-group-settings')?.remove(); groupSettingsMounted = false; } else { mountDrawer(); } };
    title.classList.add('fm-modal-title-btn');
    title.setAttribute('role', 'button');
    title.setAttribute('tabindex', '0');
    title.title = t('messages.groupSettings');
    title.addEventListener('click', toggleFromTitle);
    title.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleFromTitle(); }
    });
  }
  // 信任模式 v1: 窗头信任状态指示（开启态一眼可辨；开关在好友行右键菜单）。
  // SEALED (author ruling 2026-09-12): 封存期不挂槽；updateTrustBadge 保留
  // （无槽即天然不产出）。回退 = featureFlags.js 常量改回 false。
  if (!TRUST_SEALED) header.appendChild(el('span', 'fm-trust-slot'));
  const closeBtn = el('span', 'fm-modal-close');
  closeBtn.textContent = '×';
  closeBtn.setAttribute('role', 'button');
  closeBtn.setAttribute('tabindex', '0');
  closeBtn.addEventListener('click', closeChat);
  closeBtn.addEventListener('keydown', (e) => { if (e.key === 'Enter') closeChat(); });
  header.appendChild(closeBtn);
  modal.appendChild(header);

  const toast = el('div', 'fm-modal-toast');
  toast.hidden = true;
  modal.appendChild(toast);

  const offline = el('div', 'fm-offline-bar', t('messages.reconnecting'));
  offline.hidden = state.connected;
  modal.appendChild(offline);

  // ⑤b（作者 2026-09-15）：「二是不要显示 Cloud keeps messages for 7 days; this
  // device keeps them permanently. 这样的信息」⇒ 原 P10 留存明示条**整块删除**
  // （含 `messages.deviceRetention` 双语键与 `.fm-device-note` 规则，避免死键/死规则）。
  // 🔴 该条被删 = **被取代**（P10 终裁④与契约 §9 的「UI 明示」要求随本令作废）。
  //
  // ⑤c（同令）：「三是缺少了给设备添加描述的地方，总体和好友对话框统一，只是多了
  // 设备描述」⇒ 设备窗相对好友窗的**唯一**新增项 = 描述编辑行（同一渲染器
  // `renderChatModal` 的设备分支，禁第二套对话框实现）。
  if (conv.kind === 'device') modal.appendChild(deviceDescRow);

  const flow = el('div', 'fm-flow');
  modal.appendChild(flow);

  // input bar
  const bar = el('div', 'fm-input-bar');
  const input = document.createElement('input');
  input.className = 'cfg-input fm-input';
  input.type = 'text';
  input.placeholder = t('messages.inputPlaceholder');
  input.maxLength = 2000;
  input.autocomplete = 'off';
  // ③ 发送键族统一批（作者 2026-09-15「正常绿 / 掉线灰」）：本键与全站发送键共用
  //   同一套状态色（`sapphire.css` 的发送族块）。`cfg-btn-primary` 只为**承接既有
  //   墨色**（`sidebar.css:1162-1170` 的主操作白墨声明，既有类名 ⇒ 零新增字面量色值）；
  //   材质/几何/状态一律由发送族块覆盖，不取 `.cfg-btn` 的灰档。
  const sendBtn = el('button', 'cfg-btn cfg-btn-primary fm-send-btn', t('messages.send'));
  bar.appendChild(input);
  // 附件发送入口。两种形态**共用同一枚纸夹**（`fm-attach-btn` + `paperclip` 图标），
  // 靠会话面而非两套控件区分（attachcl 批，作者 2026-09-16 07:36）：
  //   · 设备面（既有，**零行为变化**）：闸位/队列/传输全走 dropbox.js 单点；
  //   · 好友 / 群面（本批**放开**）：整件一次请求 → 网关 → 复用桌面分块驱动
  //     （唯一实现 = attachUpload.js；闸位常量同源 = dropbox.js 导出的同一组）。
  // 🔴 两面各写一套入口/渲染器是本批明令禁止的形态 ⇒ 下面按 `attachAvailable(conv)`
  //    一个判据分流，二者互斥，设备面走不到新腿。
  let deviceFileInput = null;
  let friendFileInput = null;
  if (conv.kind === 'device') {
    deviceFileInput = document.createElement('input');
    deviceFileInput.type = 'file';
    deviceFileInput.multiple = true;
    deviceFileInput.style.display = 'none';
    deviceFileInput.addEventListener('change', () => {
      if (deviceFileInput.files && deviceFileInput.files.length > 0) {
        sendDeviceFiles(conv.device.deviceId, deviceFileInput.files);
      }
      deviceFileInput.value = '';
    });
    const attachBtn = el('button', 'icon-btn dropbox-attach-btn fm-attach-btn');
    attachBtn.type = 'button';
    attachBtn.title = t('dropbox.attachFile');
    attachBtn.setAttribute('aria-label', t('dropbox.attachFile'));
    attachBtn.innerHTML = '<i data-lucide="paperclip"></i>';
    attachBtn.addEventListener('click', () => deviceFileInput.click());
    bar.appendChild(attachBtn);
    bar.appendChild(deviceFileInput);
  } else if (attachAvailable(conv)) {
    friendFileInput = document.createElement('input');
    friendFileInput.type = 'file';
    friendFileInput.multiple = true;
    friendFileInput.style.display = 'none';
    friendFileInput.addEventListener('change', () => {
      if (friendFileInput.files && friendFileInput.files.length > 0) {
        void sendAttachCurrent(conv, friendFileInput.files);
      }
      friendFileInput.value = '';
    });
    const attachBtn = el('button', 'icon-btn dropbox-attach-btn fm-attach-btn');
    attachBtn.type = 'button';
    attachBtn.title = t('dropbox.attachFile');
    attachBtn.setAttribute('aria-label', t('dropbox.attachFile'));
    attachBtn.innerHTML = '<i data-lucide="paperclip"></i>';
    attachBtn.addEventListener('click', () => friendFileInput.click());
    bar.appendChild(attachBtn);
    bar.appendChild(friendFileInput);
  }
  bar.appendChild(sendBtn);
  modal.appendChild(bar);

  overlay.appendChild(modal);
  document.body.appendChild(overlay);
  modalEls = { overlay, flow, input, sendBtn, toast, offline, conv };
  // Fresh modal → reset history-window state (a stale older conversation's
  // tail must never leak into this one).
  chatMsgs = [];
  // U-b：旧窗口的乐观项登记随之作废（其节点已脱离文档）——登记表与 chatMsgs
  // 同拍，绝不跨窗残留。
  pendingSends = [];
  oldestLoadedId = 0;
  hasMoreHistory = false;
  loadingHistory = false;

  overlay.addEventListener('click', (e) => { if (e.target === overlay) closeChat(); });
  document.addEventListener('keydown', escClose);

  // ③A8 / ③-B：好友面零附件入口（不挂入口、也不做灰置假入口），但**静默吞文件
  // 不可接受**（项目纪律：失败必须可见）——好友窗内落文件 ⇒ 一次显式提示，文件
  // 不被任何通道接收、无副作用。
  // 4b 腿 A 更新：附件**接收/呈现/下载**面本批已通（附件卡片 + 鉴权下载路由），
  // 但**本窗仍无发送入口**（发送面走 SendMessage 工具 / agent 腿）⇒ 该提示保留，
  // 文案已改为不误导的说法（原文「好友消息暂不支持附件」已不成立）。
  overlay.addEventListener('dragover', (e) => {
    if (hasFiles(e)) e.preventDefault();
  });
  overlay.addEventListener('drop', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    // 设备窗（卡 D3）：拖拽 = **发送入口**（设备面独有，纸夹 + 拖拽两条并存；
    // 闸位/队列/offer 走 dropbox.js 单点，与新窗纸夹键同一实现）。
    if (conv.kind === 'device') {
      sendDeviceFiles(conv.device.deviceId, e.dataTransfer.files);
      return;
    }
    // 好友 / 群（attachcl 批**放开**）：拖放与纸夹**同一实现**（同一 `sendAttachCurrent`，
    // 禁两条腿各写一套闸/上传/渲染）。放开前这里回的是
    // 「暂无附件发送入口」提示（`messages.attachUnsupported[Group]`）—— 该提示随本批
    // **被取代**（键保留仅为兼容旧读数，见 i18n 注记）。
    if (attachAvailable(conv)) {
      void sendAttachCurrent(conv, e.dataTransfer.files);
      return;
    }
    modalToast(t('messages.attachUnsupported'));
  });

  const doSend = () => (conv.kind === 'device' ? sendDeviceCurrent(conv) : sendCurrent(conv));
  sendBtn.addEventListener('click', doSend);
  // ③ 输入非空 ↔ 发送键可用态即时同步（含发送后清空 ⇒ 回禁用态；禁两态分叉）
  input.addEventListener('input', syncComposerSend);
  // ⑤ 中文输入（作者点名的面）：组字期间 Enter 属于输入法（确认候选），不得
  // 触发 doSend()；组字结束后的 Enter 照旧发送（⑤A3）。
  bindImeGuard(input);
  input.addEventListener('keydown', (e) => {
    if (isImeComposing(e, input)) return;
    if (e.key === 'Enter') { e.preventDefault(); doSend(); }
  });

  applyBlockState(conv);
  updateTrustBadge(conv);
  createIconsIn(overlay);
}

// ⑦ 窗头标题面（显示优先级第三处）：备注/群名改动后就地重打，不整窗重建。
// ── ⑤c 设备描述编辑（设备窗相对好友窗的**唯一**新增项）────────────────────
// 作者 2026-09-15：「三是缺少了给设备添加描述的地方，总体和好友对话框统一，
// 只是多了设备描述」；同夜 ② 返工令：「给设备写描述的面板还可以优化一下，比如
// 可以是一个小按钮，点了之后展开一个面板让我们写。现在设计的很难看」。
//
// 形态（② 现令 + devrow 改位）= **默认收起 + 小键 + 点击展开编辑面板**：
//   · 收起态 = **窗头行内一枚图标小键**（`.fm-modal-title > .fm-device-desc-btn`，
//     Glass Control standard 族 `sapphire.css:159-262`）：pencil 图标 +
//     `title`/`aria-label` 同用**既有键** `neblink.deviceDescHint`。
//     🔴 devrow（作者 2026-09-16 07:53 截图令）：键位于**名块右侧、同一 flex 行**、
//     垂直居中；旧形态（键独占 `.fm-device-desc` 一行）判为红锚。热区地板与截断
//     优先序见 `friends.css` 的 `.fm-modal-title > .fm-device-desc-btn` 规则注释。
//     🔴 R2（⑤ 更正令，locales 零 diff）：本键**不引入任何新文案** —— 上一轮的
//     二态文案键 `messages.deviceDescAdd` / `messages.deviceDescEdit`（zh/en 各两条）
//     随之删除，「无描述 / 已有描述」的区分由**展开后的 textarea 正文**呈现（描述值
//     本身仍是窗头名/列表行名的最高优先位）⇒ 零信息损失。
//   · 展开态 = 就地展开编辑面板（textarea + 保存/取消，复用既有键
//     `neblink.save` / `neblink.cancel`），**写面板 ≠ 关窗**：
//     保存失败时面板原样留着，正文零丢失（项目纪律：失败可见、禁静默丢字）。
//   · 🔴 旧形态（常驻只读文本条 + 点击换 `input` 的行内编辑）**整体替换**——那正是
//     作者点名的「很难看」；其只读文本条同时被「窗头名/列表行名」取代：描述值仍是
//     `deviceLabel` 的**最高优先位**（`messages.js:706-711` 窗头 / `:2622-2641` 列表行）
//     ⇒ 收起后描述**照旧一眼可读**，零信息损失（也就零死 CSS：`.fm-device-desc-text`
//     / `.fm-device-desc-empty` 两条规则随旧渲染点同批删除）。
// 写路径 = `dropbox.js` 的 `saveDeviceDescription` **单点**（旧设备窗的编辑器与
// 本处共用同一函数 ⇒ 禁两套并存，见 dropbox.js 该函数注释）。
// 长度上限 200：与设置侧旧编辑器同档口径（该编辑器走同一 PUT 端点）。
// 键盘语义：Enter/Esc 之外的键交还输入法（IME 组字守卫 `imeGuard.js` 唯一判据）；
// Esc = 收起面板（**stopPropagation**：否则会冒泡到 document 的 `escClose` ⇒ 关整窗）；
// Ctrl/Cmd+Enter = 提交（面板内是 textarea，裸 Enter 必须是换行）。
const DEVICE_DESC_MAX = 200;

/** 面板挂载行（②）：**默认收起 = 无子节点**（键已上窗头行，见 devrow）；点键就地展开面板。
 *
 *  🔴 devrow（作者 2026-09-16 07:53）：本行**不再承载收起态小键** —— 键改挂
 *  `mountEl`（= `.fm-modal-title`，与 `.fm-modal-name` 同一个 flex 行）⇒ 达成
 *  「名块与键同行、名右侧内联、垂直居中」。本行只剩**面板挂载点**职责：
 *  `openDeviceDescPanel` 仍把 `.fm-device-desc-panel` append 到这里 ⇒ 展开面板
 *  照旧占窗头下方一行（收起态 `:empty` ⇒ 无占位，见 friends.css 该规则注释）。
 *  ⚠ 本行仍是 `[data-device-desc="1"]` 的**唯一**持有者（既有断言面不搬）。 */
function buildDeviceDescRow(conv, mountEl) {
  const row = el('div', 'fm-device-desc');
  row.dataset.deviceDesc = '1'; // QA 断言面：描述入口可机械定位（②）
  mountEl.appendChild(buildDeviceDescToggle(conv, row));
  return row;
}

/** 收起态小键（②）：面板开合的**唯一**开关（`aria-expanded` 同步，禁第二份开合态）。 */
function buildDeviceDescToggle(conv, row) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'glass-control fm-device-desc-btn';
  // ⑤ R2：**图标键**形态（零新文案）——图标 + 既有键标题；无障碍名与 title 同源，
  // 禁「只剩图形、无字可读」的裸图标键。
  btn.innerHTML = '<i data-lucide="pencil-line"></i>';
  btn.title = t('neblink.deviceDescHint');
  btn.setAttribute('aria-label', t('neblink.deviceDescHint'));
  btn.setAttribute('aria-expanded', 'false');
  btn.addEventListener('click', () => {
    if (row.querySelector('.fm-device-desc-panel')) closeDeviceDescPanel(row, btn);
    else openDeviceDescPanel(conv, row, btn);
  });
  return btn;
}

/** 收起：面板整体出 DOM（`aria-expanded` 同拍回落；无隐藏态残留）。 */
function closeDeviceDescPanel(row, btn) {
  row.querySelector('.fm-device-desc-panel')?.remove();
  btn.setAttribute('aria-expanded', 'false');
}

/** 展开编辑面板（②）：textarea + 保存/取消；写路径仍在展开时按下（非每次敲键）。 */
function openDeviceDescPanel(conv, row, btn) {
  if (!conv.device || row.querySelector('.fm-device-desc-panel')) return;
  const panel = el('div', 'glass-control fm-device-desc-panel');
  const ta = document.createElement('textarea');
  ta.className = 'cfg-input fm-device-desc-input';
  ta.rows = 2;
  ta.maxLength = DEVICE_DESC_MAX;
  ta.value = conv.device.userDescription || '';
  ta.placeholder = t('neblink.deviceDescHint');
  ta.setAttribute('aria-label', t('neblink.deviceDescHint'));
  ta.autocomplete = 'off';
  // ⑤ 中文输入收归：组字期间所有键交还输入法（既有唯一判据源 imeGuard.js）。
  bindImeGuard(ta);
  const actions = el('div', 'fm-device-desc-actions');
  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'glass-control fm-device-desc-save';
  save.textContent = t('neblink.save');
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'glass-control fm-device-desc-cancel';
  cancel.textContent = t('neblink.cancel');
  actions.appendChild(save);
  actions.appendChild(cancel);
  panel.appendChild(ta);
  panel.appendChild(actions);
  row.appendChild(panel);
  createIconsIn(panel);
  btn.setAttribute('aria-expanded', 'true');

  let busy = false;
  const close = () => closeDeviceDescPanel(row, btn);
  const commit = async () => {
    if (busy) return;
    // maxlength 只管键盘输入 ⇒ 提交边界再夹一次（与好友备注同纪律）。
    const next = ta.value.trim().slice(0, DEVICE_DESC_MAX);
    const prev = conv.device.userDescription || '';
    if (next === prev) { close(); return; } // 无变化：零请求
    busy = true;
    save.disabled = true; cancel.disabled = true;
    try {
      await saveDeviceDescription(conv.device, next); // 🔴 单点写路径（dropbox.js）
      conv.device.userDescription = next;
      close();
      // ⑤ R2：收起态小键已是**图标键**（无文案）⇒ 此处不再重打键文案；
      // 描述值的可见面收敛为「窗头名 + 列表行名」两处同源就地重打。
      updateModalTitle(conv); // 窗头名 = `deviceLabel`（描述 > 设备名 > 占位）
      renderList();           // 列表行名同源同改
    } catch {
      // 🔴 失败**不收起**：面板与正文原样留着（零丢失），错误显式可见。
      save.disabled = false; cancel.disabled = false;
      modalToast(t('messages.deviceDescSaveFailed'));
    } finally {
      busy = false;
    }
  };
  save.addEventListener('click', () => { commit(); });
  cancel.addEventListener('click', close);
  ta.addEventListener('keydown', (e) => {
    if (isImeComposing(e, ta)) return;
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); return; }
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); e.stopPropagation(); commit(); }
  });

  ta.focus();
  ta.setSelectionRange(ta.value.length, ta.value.length);
}

function updateModalTitle(conv) {
  if (!modalEls) return;
  const nameEl = modalEls.overlay.querySelector('.fm-modal-name');
  if (nameEl) nameEl.textContent = convTitleLabel(conv);
  const dlg = modalEls.overlay.querySelector('.fm-modal');
  if (dlg) dlg.setAttribute('aria-label', convTitleLabel(conv));
}

// 信任状态指示：trusted → sapphire chip（shield-check + 「已信任」），未信任
// → 空槽。fm-trust-changed（contacts 右键菜单开关）到达时对开着的窗重打。
function updateTrustBadge(conv) {
  const slot = modalEls && modalEls.overlay.querySelector('.fm-trust-slot');
  if (!slot) return;
  slot.innerHTML = '';
  if (!conv || !conv.friend || !isFriendTrusted(conv.friend.userId)) return;
  const badge = el('span', 'fm-trust-badge');
  badge.innerHTML = '<i data-lucide="shield-check"></i>';
  badge.appendChild(el('span', '', t('messages.trusted')));
  badge.title = t('messages.trustedHint');
  slot.appendChild(badge);
  createIconsIn(slot);
}

function escClose(e) {
  if (e.key === 'Escape' && modalEls) {
    e.stopPropagation();
    closeChat(); // also detaches this listener (D4)
  }
}

// §3.3/R8: not-friend gate — system bar + disabled input (history read-only)
function applyBlockState(conv) {
  if (!modalEls) return;
  modalEls.overlay.querySelector('.fm-blocked-bar')?.remove();
  // 群分支（O⑨ 裁定「拉黑只断单聊、同群照常」）：群窗不走好友闸 —— 拉黑/删
  // 好友不产生群内只读栏，也不禁用群发送输入框（群发送权在服务端成员闸）。
  const blocked = conv.kind !== 'group' && conv.kind !== 'device' && !isStillFriend(conv);
  // F1 翻案要件②（2026-09-15）：设备会话的服务端腿**必须有本机 device id**
  // ——发送路径段是**发送设备**且服务端硬闸只许自报本机（见 `sendDeviceCurrent`）。
  // 身份缺席（未登录 / 字段缺席）⇒ **可见禁用**（只读栏 + 输入框禁用 + 占位提示），
  // 禁静默打对端、禁静默失败、禁把消息留在「看着能发」的假可用态。
  const noSelf = deviceSendBlocked(conv);
  if (blocked || noSelf) {
    const bar = el('div', 'fm-blocked-bar', t(noSelf ? 'messages.deviceSendUnavailable' : 'messages.notFriendBlocked'));
    modalEls.flow.parentNode.insertBefore(bar, modalEls.flow);
  }
  modalEls.input.disabled = blocked || noSelf || !state.connected;
  modalEls.input.placeholder = t(noSelf ? 'messages.deviceSendUnavailable' : 'messages.inputPlaceholder');
  syncComposerSend(); // 发送键 = 输入框可用 ∧ 输入非空（判据单源，见下）
}

/** ③ 同病同修（2026-09-14 交付批 · 好友面板「发送按钮组」同族）：
 *  发送键可用态 = 输入框可用 ∧ 输入非空（trim）。旧形态只跟「拉黑/断连」同步，
 *  空输入/纯空格时按键看着可用、点了**静默 no-op**（同族反极性缺陷）。
 *  判据唯一来源：applyBlockState / onDisconnect / input 事件 / sendCurrent 共用。 */
function syncComposerSend() {
  if (!modalEls) return;
  modalEls.sendBtn.disabled = modalEls.input.disabled || !modalEls.input.value.trim();
  // ③（作者 2026-09-15「掉线了会变成灰色」）：链路态与主对话框**同族判据** ——
  // 断连 ⇒ 给本键挂 `.disconnected`（主对话框那条挂点 = `ws.js:119` 的
  // `syncSendButtonConnState`，判据同为 `!state.connected`，禁第二份判据）。
  // 本函数是发送键状态的**唯一**收敛点（applyBlockState / onDisconnect / input
  // 事件 / 重连回打四处共用）⇒ 挂在这里即全部路径同步，无需另挂监听。
  modalEls.sendBtn.classList.toggle('disconnected', !state.connected);
}

// ── 附件卡片（4b 腿 A-2；线面契约 §B.2 M6 / §B.4 / §B.7）─────────────────────
//
// 🔴 本节的**唯一职责**：把服务端下发的附件元数据渲染成**可判读**的卡片。
//    判据优先级逐字照 §B.7 ③：**先看元数据 `state`，再看下载时的 HTTP 码**。
//    三条禁令（同节）：
//      ① 禁静默丢弃 —— 任何 state（含未知值）都要出卡片，绝不从消息里消失；
//      ② 禁把「已过期」与「下载失败」折叠成一个态：前者终态不可重试，后者可重试；
//      ③ 禁渲染成「可点但点了报错」的按钮 —— 不可下载的件不挂下载按钮。

/** 附件呈现态（客户端**唯一**映射点）。`ready` = 可下载；其余一律不可下载。 */
function attStateOf(att) {
  if (!att || typeof att !== 'object') return 'unreadable';
  const s = typeof att.state === 'string' ? att.state : '';
  // 设备面文件（MVP-1）：呈现 = 传输态（无下载面），与好友面四种态互斥取值。
  if (s === 'device') return 'device';
  if (s === 'ready') return att.id ? 'ready' : 'unreadable'; // 有 ready 无 id = 不可下载（降级而非假按钮）
  if (s === 'expired') return 'expired';
  if (s === 'uploading') return 'uploading';
  return 'unreadable'; // 越界值 / 键缺失：可判读的降级态（不是「无附件」）
}

/** 设备面传输态文案（**唯一映射点**，卡 §7.1 功能等价清单「文件进度/成败/已保存」）。
 *  复用既有 `dropbox.*` 双语键（旧窗同源，禁新造第二套文案）。 */
function deviceTransferText(att) {
  const st = (att && att.deviceStatus) || '';
  const ours = !!(att && att.deviceOut);
  if (st === 'completed') {
    return ours ? t('dropbox.delivered')
      : ((att && att.deviceSavedPath) ? t('dropbox.saved') : t('dropbox.completed'));
  }
  if (st === 'failed' || st === 'rejected') return t('dropbox.failed');
  return t('dropbox.transferring'); // pending / accepted / transferring（含未知值）
}

const ATT_NOTE_KEY = {
  expired: 'messages.attachExpired',
  uploading: 'messages.attachUploading',
  unreadable: 'messages.attachUnreadable',
};

/** 人类可读体积（十进制，与作者给定数的「1024 MB = 1 GiB = 1,073,741,824 B」同量纲）。 */
function fmtBytes(n) {
  const v = Number(n);
  if (!isFinite(v) || v < 0) return '';
  if (v < 1000) return `${v} B`;
  const units = ['KB', 'MB', 'GB'];
  let x = v / 1000, i = 0;
  while (x >= 1000 && i < units.length - 1) { x /= 1000; i++; }
  return `${x < 10 ? x.toFixed(1) : Math.round(x)} ${units[i]}`;
}

/** §B.4 占位正文的**逐字**复算（用于识别服务端生成的占位文本，见 `fillBubble`）。 */
function attPlaceholderName(name) {
  const cp = Array.from(String(name == null ? '' : name));
  return cp.length <= 24 ? cp.join('') : cp.slice(0, 23).join('') + '…';
}
function attPlaceholderBody(atts) {
  const names = atts.map(a => attPlaceholderName(a && a.name));
  if (names.length === 0) return '';
  if (names.length === 1) return `[附件] ${names[0]}`;
  const joined = names.length <= 3 ? names.join(', ') : names.slice(0, 3).join(', ') + ' …';
  const s = `[附件] ${names.length} 个文件：${joined}`;
  const cp = Array.from(s);
  return cp.length <= 200 ? s : cp.slice(0, 199).join('') + '…';
}

/** 附件签名的**唯一**形态（keyedDiff 的两路字段集收敛判据，见 §5.3-J）。 */
function attSig(m) {
  const a = m && Array.isArray(m.attachments) ? m.attachments : [];
  // 设备面（MVP-1）：设备文件卡的**传输态**也是外观量（transferring→completed 必须
  // 就地重填，否则状态位永远停在旧态）⇒ 设备卡签名单列一支；好友面签名逐字不变。
  return a.map(x => (x && x.state === 'device')
    ? `dev:${(x.deviceStatus || '')}:${(x.deviceSavedPath || '')}:${(typeof x.devicePct === 'number' ? x.devicePct : '')}`
    : `${(x && x.id) || ''}:${(x && x.state) || ''}`).join(',');
}

function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'attachment';
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

/** 整件 sha256（64 位小写 hex）。拿不到字节 / 无 WebCrypto（非安全上下文）⇒ `null`。
 *
 *  🔴 `null` 是**正确结果**而非降级：引擎侧见 `null` 即零 E4（fail-closed）——
 *  「验证不了就不删服务端 blob」。「下载成功即视为落盘成功」的宽口径**不接受**。 */
async function sha256HexOfBlob(blob) {
  try {
    const subtle = globalThis.crypto && globalThis.crypto.subtle;
    if (!subtle || !blob || typeof blob.arrayBuffer !== 'function') return null;
    const digest = await subtle.digest('SHA-256', await blob.arrayBuffer());
    return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('');
  } catch { return null; }
}

/** 4b1 · E4 取证 + 上报（UI 侧**唯一**回执调用点）。
 *
 *  §F.1b①「落盘成功」= 整件字节 ∧ 本地整件 sha256 == 服务端声明 digest。本函数**只取证**：
 *   - 本地 digest = WebCrypto 自算（拿不到 ⇒ 上报 `null` ⇒ 引擎侧跳过 ⇒ 零 E4）；
 *   - `landedFinal: true` 的**前提** = 本函数只在 `saveBlob` 正常返回后被调用（saveBlob 抛
 *     ⇒ 走外层的 catch ⇒ 根本不进这里）⇒ 恒为「已交给保存」。
 *  ⚠ 已登记的 provisional 偏差：本落点 = 浏览器/OS 下载管理器，页面**拿不到**保存成功回执
 *  ⇒「最终位置 + fsync」不可证（补件批 4b1 证据件 `01-byte-landing-inventory.md`；root #524 log ④）。
 *
 *  全程 try/catch 吞异常 + 调用侧**不 await** ⇒ 用户面零影响（§F.1b 规则 4）。 */
async function ackAttachmentLanded(att, blob) {
  try {
    const localSha = await sha256HexOfBlob(blob);
    await api.ackAttachmentReceived(att && att.id, {
      wholeSha256: localSha,
      declaredSha256: (att && att.sha256) || '',
      receivedBytes: blob && typeof blob.size === 'number' ? blob.size : null,
      expectedBytes: att && typeof att.size === 'number' ? att.size : null,
      landedFinal: true,
    });
  } catch { /* 静默：回执面永不影响用户 */ }
}

/** 单件卡片。`kind` 由元数据判定；下载失败**就地**改文案（可重试），
  * 410（服务端权威）**就地**升级为已过期（终态）。 */
function attachmentCard(att) {
  const kind = attStateOf(att);
  const card = el('div', `fm-att fm-att-${kind}`);
  card.dataset.attState = kind;
  card.dataset.attId = (att && att.id) || '';

  const name = el('span', 'fm-att-name', (att && att.name) || t('messages.attachUnnamed'));
  card.appendChild(name);
  const sizeText = fmtBytes(att && att.size);
  if (sizeText) card.appendChild(el('span', 'fm-att-size', sizeText));

  // ── 设备面文件卡（MVP-1 · 卡 D4「适配 attachmentCard + 气泡状态位保留传输态」）──
  // 与好友面附件卡的**唯一**差异：设备面的落盘由接收端传输链负责（`savedPath` 回显），
  // 没有应用内鉴权下载路由 ⇒ **不挂下载键**（禁「可点但点了报错」的假按钮，§B.7 ③）。
  // 状态判据 = 设备腿 5 态（pending/accepted/transferring/completed/failed）。
  if (kind === 'device') {
    card.appendChild(el('span', 'fm-att-device-status', deviceTransferText(att)));
    if (att && att.deviceSavedPath) card.appendChild(el('span', 'fm-att-device-path', String(att.deviceSavedPath)));
    const pct = att && att.devicePct;
    if (typeof pct === 'number') {
      const track = el('div', 'fm-att-device-progress');
      const bar2 = el('div', 'fm-att-device-progress-bar');
      bar2.style.width = `${Math.max(0, Math.min(100, pct))}%`;
      track.appendChild(bar2);
      card.appendChild(track);
    }
    // 预览腿（作者令 2026-09-15）：**只在有本机落盘件且其类型可渲染时**才挂可点面。
    // 无 `savedPath`（发出腿 / 未完成 / 失败）⇒ 本地没有可读件 ⇒ 保持不可点
    // （禁「可点但点了报错」，§B.7 ③；状态位文案即用户可见的说明）。
    const localPath = (att && att.deviceSavedPath) ? String(att.deviceSavedPath) : '';
    if (localPath && canPreviewLocalPath(localPath)) {
      makeCardPreviewable(card, att, () => previewLocalPath({ path: localPath, title: (att && att.name) || '' }) !== 'ok');
    }
    return card;
  }

  /** @type {HTMLElement} */
  const note = el('span', 'fm-att-note',
    kind === 'ready' ? t('messages.attachDownload') : t(ATT_NOTE_KEY[kind] || 'messages.attachUnreadable'));
  if (kind === 'ready') {
    const btn = el('button', 'fm-att-dl', t('messages.attachDownload'));
    btn.type = 'button';
    btn.setAttribute('aria-label', `${t('messages.attachDownload')}: ${(att && att.name) || ''}`);
    btn.addEventListener('click', () => downloadAttachment(att, card, btn, note));
    card.appendChild(btn);
    card.appendChild(note);
    note.classList.add('visually-hidden-note'); // 常态下只显示按钮；状态文案在失败/成功后就地显示
    // 预览腿（作者令 2026-09-15）：**整卡可点 = 在 Canvas 里预览**；下载键是卡内
    // 嵌套键，其点击不得冒泡成预览（见 `makeCardPreviewable` 的事件路由）。
    // 只有 `ready` 态挂可点面：其余态无字节可取（§B.7 ③ 不造假按钮）。
    makeCardPreviewable(card, att, () => previewFriendAttachment(att, card));
  } else {
    card.appendChild(note);
    card.setAttribute('aria-disabled', 'true');
  }
  return card;
}

/** 把整张附件卡变成「点一下 = 预览」的可点面（好友 ready 态 / 设备有本地件态共用）。
 *
 *  · `role=button` + `tabindex=0` + Enter/Space ⇒ 键盘可达；
 *  · 卡内既有交互件（`.fm-att-dl` 下载键）的点击**不**触发预览；
 *  · `showToastOnUnavailable()` 返回 true ⇒ 走**可见**降级（禁静默无反应）。
 *
 *  @param {HTMLElement} card
 *  @param {any} att
 *  @param {() => (boolean|Promise<boolean>)} showToastOnUnavailable */
function makeCardPreviewable(card, att, showToastOnUnavailable) {
  card.classList.add('fm-att-previewable');
  card.setAttribute('role', 'button');
  card.setAttribute('tabindex', '0');
  card.setAttribute('aria-label', `${t('messages.attachPreview')}: ${(att && att.name) || ''}`);
  /** 卡内嵌套键（下载键）自己处理点击 ⇒ 不冒泡成预览。 */
  const onNestedControl = (e) => {
    const tgt = /** @type {HTMLElement|null} */ (e.target);
    return !!(tgt && typeof tgt.closest === 'function' && tgt.closest('.fm-att-dl'));
  };
  const fire = () => {
    Promise.resolve(showToastOnUnavailable())
      .then((need) => { if (need) modalToast(t('messages.attachPreviewFailed')); })
      .catch(() => modalToast(t('messages.attachPreviewFailed')));
  };
  card.addEventListener('click', (e) => {
    if (onNestedControl(e)) return;
    fire();
  });
  card.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    if (onNestedControl(e)) return;
    e.preventDefault();
    fire();
  });
}

/** 好友面预览：取字节**只走应用内鉴权路由**（`friendsApi.downloadAttachment`，同下载腿
 *  的唯一取字节口，禁第二条取字节路）⇒ 交给 attachmentPreview 判类型 + 渲染。
 *  @returns {Promise<boolean>} true = 需要**可见降级**文案 */
async function previewFriendAttachment(att, card) {
  if (!att || !att.id) return true;
  if (card.dataset.attPreviewBusy === '1') return false;
  // 已在面板里预览这一件 ⇒ 只激活，**不取第二份字节**（同一次点击 = 同一次取数）。
  if (isPreviewOpen(`attach:${att.id}`)) return false;
  card.dataset.attPreviewBusy = '1';
  try {
    const { blob, filename } = await api.downloadAttachment(att.id);
    const r = await previewBlob({
      id: `attach:${att.id}`,
      title: (att && att.name) || filename || t('messages.attachUnnamed'),
      fileName: (att && att.name) || filename || '',
      blob,
    });
    if (r === 'unsupported') {
      modalToast(t('messages.attachPreviewUnsupported', { name: (att && att.name) || '' }));
      return false; // 文案已就位，不再叠一条通用失败提示
    }
    return r !== 'ok';
  } catch (err) {
    // 会话过期 / 非好友：全局链已给引导（与下载腿同一处置），不再叠文案。
    if (err && (err.status === 401 || err.status === 403)) return false;
    // 410 = 服务端权威「附件已过期」（终态）：给**可见**读数（下载腿的就地卡片升级
    // 是那条路的处置；此处不复制第二套状态迁移，只保证用户看得见、非静默）。
    if (err && err.status === 410) { modalToast(t('messages.attachExpired')); return false; }
    return true;
  } finally {
    card.dataset.attPreviewBusy = '0';
  }
}

/** 取字节：**只走应用内鉴权路由**（`/api/friends/attachments/{id}`，裁定②）。
  * 🔴 前端不拼任何静态/公开 URL（服务端附件目录不挂 Caddy）。 */
async function downloadAttachment(att, card, btn, note) {
  if (card.dataset.busy === '1') return;
  card.dataset.busy = '1';
  btn.disabled = true;
  note.classList.remove('visually-hidden-note');
  note.textContent = t('messages.attachDownloading');
  try {
    const { blob, filename } = await api.downloadAttachment(att.id);
    saveBlob(blob, filename || (att && att.name));
    note.textContent = t('messages.attachDownloaded');
    // 4b1 · E4（§F.1b）：**落盘尝试完成之后**才取证回执（saveBlob 抛 ⇒ 走下面的 catch ⇒ 零 E4）。
    // 🔴 `void` = 故意 **不 await**：回执面绝不阻塞/影响下载与 UI（失败静默容忍）。
    void ackAttachmentLanded(att, blob);
  } catch (err) {
    if (err && err.status === 410) {
      // 服务端权威：元数据说 ready 但字节已按瞬态口径删除（§B.7 ③ 表）——
      // **就地升级为终态**，并撤掉重试可能（不是「下载失败」）。
      card.dataset.attState = 'expired';
      card.classList.remove('fm-att-ready');
      card.classList.add('fm-att-expired');
      btn.remove();
      note.textContent = t('messages.attachExpired');
      card.setAttribute('aria-disabled', 'true');
      return;
    }
    // 传输/本地失败：**可重试**（§B.7 ③ 表第二行），绝不标成「已过期」。
    card.dataset.attState = 'failed';
    note.textContent = t('messages.attachDownloadFailed');
    window.dispatchEvent(new CustomEvent('fm-attachment-failed'));
  } finally {
    card.dataset.busy = '0';
    btn.disabled = false;
  }
}

/** 气泡内容填充（**单点**：新建与就地升级共用，禁两套渲染）。 */
function fillBubble(bubble, m) {
  bubble.innerHTML = '';
  const atts = m && Array.isArray(m.attachments) ? m.attachments : [];
  const body = (m && m.body) || '';
  // §B.4：附件消息的 `body` 是服务端生成的占位正文 —— 只有当它与「本消息附件的
  // 占位文本」**逐字相等**时才隐藏（否则照旧显示用户原文，不误吞任何真实文本）。
  const hidePlaceholder = atts.length > 0 && body !== '' && body === attPlaceholderBody(atts);
  if (body && !hidePlaceholder) bubble.appendChild(el('div', 'fm-msg-text', body));
  if (atts.length > 0) {
    const list = el('div', 'fm-att-list');
    atts.forEach(att => list.appendChild(attachmentCard(att)));
    bubble.appendChild(list);
  }
}

// ── 气泡方向判据（P5 修复 · 单点）─────────────────────────
/** 逐条「本机所发」判据（**正向证据**）。
 *
 * 修前判据是**负向推断**：`m.senderId !== conv.friend?.userId` —— 「不是对方发的就是我
 * 发的」。它对**字段缺席**与**会话缺档案**都恒真，于是有两种误判：
 *  · 帧/REST 条不带 `senderId` ⇒ 对方的消息被渲染成**本机所发**（右侧气泡）；
 *  · `conv.friend` 尚未 hydrate ⇒ **所有**消息都成右侧。
 * 本仓同族「不猜」口径的既有先例 = 回显认领（`appendMessages` 内注释「REST 面该字段
 * 权威且必带；缺席 ⇒ 不认领」）。本判据与它对齐：**没有证据就不下结论**。
 *
 * @returns {boolean|null} true/false = 已由证据确证；null = 证据缺席（调用方回退）
 */
function oursBySenderId(m, conv) {
  const sid = m && m.senderId;
  if (sid === undefined || sid === null || sid === '') return null;
  // 群分支（friendgroups 客户端腿）：群无单一对端档案 ⇒ 判据源 = viewer 自身
  // 身份（groupSelfUserId，发送关联自证）+ 客户端本地哨兵 'me'（本机乐观项
  // 及其 L2 缓存副本的 senderId）。有 senderId 而非本机 ⇒ 正向判「他人」
  // （返回 false = 左侧），不落「单侧在场 ⇒ out」的 direct 兜底（群行兜底
  // 会把他人消息画到右侧，比 P5 的 direct 残余更常见）。
  if (conv && conv.kind === 'group') {
    if (sid === 'me') return true;
    const mine = groupSelfUserId();
    if (mine && String(sid) === mine) return true;
    return false;
  }
  const fid = conv && conv.friend ? conv.friend.userId : undefined;
  if (fid === undefined || fid === null || fid === '') return null;
  return String(sid) !== String(fid); // 确证不是对方所发 ⇒ 本机所发
}

/** 打**不可枚举**的方向标记。刻意不进 JSON / L2 缓存序列化：该标记是渲染期派生态，
 *  缓存契约只存服务端字段（加键会污染既有 wire 形态与缓存比对判据）。 */
function markOurs(m, val) {
  if (!m || typeof m !== 'object') return;
  try {
    Object.defineProperty(m, 'ours', { value: val, enumerable: false, configurable: true, writable: true });
  } catch { /* 冻结对象：退回不标记 —— resolveOut 走 senderId 正向比对档 */ }
}

/** 方向判据②的**证据源存在性**：`senderId` 与 `conv.friend.userId` **任一在场**。
 *  只服务 ③ 兜底档的区分（单侧在场 = 既有形态；两侧皆缺席 = P5 修点）。 */
function hasDirectionEvidence(m, conv) {
  const sid = m && m.senderId;
  const fid = conv && conv.friend ? conv.friend.userId : undefined;
  const present = (v) => v !== undefined && v !== null && v !== '';
  return present(sid) || present(fid);
}

/** 气泡方向（`out` = 右侧 = 本机所发）。优先级：
 *  ① `m.ours` 显式标记 —— **最强证据**（补拉帧按逐条 `senderId` 判定后写入，见
 *     `frameOursHint`；事件帧按事件类型写入，见 `onFriendEvent`）；
 *  ② `senderId` 与好友档案的正向比对（REST 条目 / 带 senderId 的帧）；
 *  ③ 兜底档（**P5 修复 · root 裁定 2026-09-14**）：**两源皆缺席 ⇒ `in`**
 *     —— 禁静默翻成 `out`（详见函数体内注释）。 */
function resolveOut(m, conv) {
  if (m && typeof m.ours === 'boolean') return m.ours;
  const byId = oursBySenderId(m, conv);
  if (byId !== null) return byId;
  // ③ 兜底档：**两源皆缺席** ⇒ `in`。
  //   修前（r2）本档恒返回 `true` —— 与 r2 **之前**的口径相反（旧判据
  //   `m.senderId !== conv.friend?.userId` 在两侧皆 `undefined` 时得 `false` = `in`），
  //   即 r2 引入了一次**方向翻转**：一条既无 `senderId` 又无好友档案的消息会被画到
  //   右侧（「本机所发」）。而该形态**同样可能只是对方的消息**（帧 / REST 该字段缺席
  //   ⇒ 见 `appendMessages` 的「REST 面该字段权威且必带；缺席 ⇒ 不认领」同族口径）。
  //   ⇒ 无证据不下结论（方向错判会连带把「接收侧消息」误标成自己发的，并让验收矩阵
  //   里「写死一条接收侧消息」被误判）。
  //   🔴 本档**只**覆盖「两源皆缺席」：**单侧在场**仍走既有 `out` 回落（与 r2 前逐字
  //   一致）—— 该残余不在本批裁定范围，已在报告内登记。
  //   🔴 本机自播帧（agent 代发；服务端该帧**不带** `senderId`）**不落本档**：事件腿
  //   `message_new_self` 已按事件类型正向确证并 `markOurs(m, true)`（见
  //   `onFriendEvent`）⇒ 走 ① ⇒ 方向不受本改影响（禁把自送消息画到左侧）。
  return hasDirectionEvidence(m, conv) ? true : false;
}

// ── Bubbles ──────────────────────────────────────────────
function bubbleEl(m, conv) {
  const out = resolveOut(m, conv);
  const wrap = el('div', `fm-msg ${out ? 'out' : 'in'}`);
  wrap.dataset.messageId = m.id;
  wrap.dataset.body = m.body || '';
  wrap.dataset.createdAt = String(toEpochMs(m.createdAt) || '');
  // QA 断言面（附件）：条目数 + 逐条呈现态 + 签名（keyedDiff 收敛判据同源）。
  wrap.dataset.attachments = attSig(m);
  wrap.dataset.attCount = String(Array.isArray(m.attachments) ? m.attachments.length : 0);
  // 设备会话统一批 MVP-1：来源面落 dataset（QA 断言面 + 徽章判据同源）。缺省 `user`。
  // 🔴 收端只为**提示级**渲染（设计卡 §9 P3）：wire `origin` 未经服务端强制，
  // 不构成「agent 发」的定论；服务端强制属 MVP-2。
  wrap.dataset.origin = (m && m.origin === 'agent') ? 'agent' : 'user';

  const bubble = el('div', 'fm-msg-bubble');
  // 群气泡发送者名：仅群窗、仅入站（他人）消息挂名（本机消息右侧不挂，微信式）。
  // 成员名册未到时留空槽（data-sender-id），hydrateGroupSenderNames 到达后回填。
  if (conv.kind === 'group' && !out && m.senderId) {
    const sender = el('div', 'fm-msg-sender', groupSenderNameOf(conv, m.senderId));
    sender.dataset.senderId = String(m.senderId);
    wrap.appendChild(sender);
  }
  fillBubble(bubble, m);
  wrap.appendChild(bubble);

  // #290 addendum §3.3: bubble right-click = primary desktop entry for
  // 转发给 agent (same action as the hover/header buttons - one handler).
  wrap.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    showPopupMenu(e.clientX, e.clientY, [
      { label: t('messages.forwardToAgent'), onClick: () => forwardBubble(wrap, conv) },
    ]);
  });

  const meta = el('div', 'fm-msg-meta');
  // 批 D（作者裁定 2026-09-14 20:5x「双方可见」）：徽标**两端渲染**、无抑制逻辑
  // —— 修前判据 `out && isAgentSent(m)` 让接收侧（`out === false`）**永不进入**
  // 本分支（即便 `origin` 已修复到位，对方也看不到）⇒ 与作者诉求相左。
  // 放开后 `out` 只决定气泡左右 / 对齐（`resolveOut`），**不再是**徽标的可见性条件。
  // 服务端零改动（`origin` 生产版同样具备）；`model.rs` 注文「Local rendering
  // only」的语义摩擦已由作者裁定解除（本批附局限声明）。
  // 群版文案 = **同一枚**通用徽章（作者 2026-09-16 决策卡 F1）：不再按会话 kind
  // 选键（`messages.agentGroupBadge` 已删，禁死键/禁两套表述），全站一个键
  // `AGENT_BADGE_TEXT_KEY`；`isAgentSent` 判据零改动，附件帧与徽章同帧共存
  // （fillBubble 附件卡渲染与本分支正交 ⇒ 结构性支持 A③ 翻案）。
  if (isAgentSent(m)) {
    meta.appendChild(el('span', 'fm-msg-agent-badge', t(AGENT_BADGE_TEXT_KEY)));
  }
  if (hasForwarded(fwdKeyOf(conv, m.id))) meta.appendChild(el('span', 'fm-msg-forwarded-badge', t('messages.forwarded')));
  const timeMs = toEpochMs(m.createdAt);
  const timeSpan = el('span', 'fm-msg-time', fmtTime(m.createdAt));
  // 仅同日分支（纯时钟文本）参与 12/24 切换：挂 data-ts-text 即声明「本节点文本
  // 整体 = formatHm(ts)」。「昨天」/「M/D」带日期语义，挂上会被刷新覆写成裸时钟。
  if (timeMs && isSameDayMs(timeMs)) {
    timeSpan.setAttribute('data-ts-text', String(timeMs));
    bindTimeToggle(timeSpan);
  }
  meta.appendChild(timeSpan);

  const actions = el('span', 'fm-msg-actions');
  const copyBtn = el('button', 'fm-msg-act');
  copyBtn.innerHTML = '<i data-lucide="copy"></i>';
  copyBtn.title = t('messages.copy');
  copyBtn.setAttribute('aria-label', t('messages.copy')); // title 不保证被 AT 播报
  // 三窗口 footer 统一（2026-09-14 作者七答）：S14/R3 补复制成功反馈——好友面原
  // 「零反馈」（实测 domChanged=false / anyToast=false / anyCopiedClass=false），
  // 现收敛到主对话语义（换对勾图 + `.copied` 绿亮 + 1500ms 复位）；
  // S15/R4 补 `await` + catch——原写法无 await / 无 catch ⇒ 未处理 Promise 拒绝
  // （实测 pageerror: Write permission denied），失败现走就地提示。转发键（第 2 颗）
  // 只随共用类 `.fm-msg-act` 的**样式档**变化，存在性与行为不动（E3）。
  let copyResetTimer = null;
  copyBtn.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(m.body || '');
      copyBtn.innerHTML = '<i data-lucide="check"></i>';
      copyBtn.classList.add('copied');
      createIconsIn(copyBtn);
      clearTimeout(copyResetTimer);
      copyResetTimer = setTimeout(() => {
        copyBtn.innerHTML = '<i data-lucide="copy"></i>';
        copyBtn.classList.remove('copied');
        createIconsIn(copyBtn);
      }, 1500);
    } catch (err) {
      console.error('[messages] Copy failed:', err);
      markCopyFailed(copyBtn);
    }
  });
  const fwd = el('button', 'fm-msg-act');
  fwd.innerHTML = '<i data-lucide="forward"></i>';
  fwd.title = t('messages.forwardToAgent');
  fwd.setAttribute('aria-label', t('messages.forwardToAgent'));
  fwd.addEventListener('click', () => forwardBubble(wrap, conv));
  actions.appendChild(copyBtn);
  actions.appendChild(fwd);
  meta.appendChild(actions);

  wrap.appendChild(meta);
  return wrap;
}

/**
 * ⑨-E keyed diff 渲染（方案 §2.3 案 E）：按 `data-message-id` 复用既有气泡节点，
 * 只增删差集、只移动错位节点 —— 不再 `innerHTML=''` 全量重建，因此刷新/增量补齐
 * 不会重建全部 DOM（无闪、无滚动跳动、命中动画不重放）。
 *
 * 不变量：`flow` 下 `.fm-load-more-row`（若有）恒为首个子节点，消息节点按
 * `msgs` 顺序排在其后，且每个 message id **至多一个**节点（M5 一致性门：
 * 渲染 id 序列无重复、严格升序）。
 */
function keyedDiff(flow, msgs, conv) {
  const loadMore = flow.querySelector('.fm-load-more-row');
  /** @type {Map<string, HTMLElement>} */
  const existing = new Map();
  for (const node of [...flow.children]) {
    if (node === loadMore) continue;
    if (node.classList && node.classList.contains('fm-flow-status')) continue; // ⑩ 状态行不参与消息身份
    const id = node.dataset && node.dataset.messageId;
    if (id !== undefined && id !== null && id !== '') existing.set(String(id), /** @type {HTMLElement} */ (node));
  }
  const wanted = new Set(msgs.map(m => String(m.id)));
  for (const [k, node] of [...existing]) {
    if (!wanted.has(k)) { node.remove(); existing.delete(k); }
  }
  // 光标起点：按钮行优先，其次 ⑩ 状态行（两者都不参与排序，消息一律排在它们之后）。
  let cursor = loadMore || flow.querySelector('.fm-flow-status') || null;
  for (const m of msgs) {
    const k = String(m.id);
    const reused = existing.get(k);
    if (reused) existing.delete(k);
    const node = reused || bubbleEl(m, conv);
    if (reused) {
      // 🔴 两路字段集收敛（信息包 §5.3-J：WS 帧 raw 透传、REST 重编码）——
      // 同一 messageId 先到的那一路可能**不带**附件元数据（或带的是旧态：
      // 例如先到 ready、后到 expired）。幂等上屏「命中即 skip」会把先到者钉死
      // ⇒ 刷新/重挂载后附件消失（或过期态不更新）。此处按**附件签名**就地重填
      // 气泡内容（不换节点 ⇒ 乐观项锚定与滚动锚点都不受影响）。
      if (node.dataset.attachments !== attSig(m)) {
        node.dataset.attachments = attSig(m);
        node.dataset.attCount = String(Array.isArray(m.attachments) ? m.attachments.length : 0);
        const b = node.querySelector('.fm-msg-bubble');
        if (b) fillBubble(b, m);
        if (m.body !== undefined) node.dataset.body = m.body || '';
      }
    }
    const after = cursor ? cursor.nextSibling : flow.firstChild;
    if (node !== after) flow.insertBefore(node, after);
    cursor = node;
  }
}

/**
 * ⑩ 消息流状态行 —— **消息区永不为空**（作者令「打开应该能够直接显示，而不是空白」）。
 * 这是**唯一**的加载态入口，只有真正没有本地缓存的会话才会走到：
 *  - `loading=true`  ⇒ 冷路径正在等尾窗回包（可见、带 `aria-busy`）；
 *  - `loading=false` ⇒ 回包为空 ⇒ 空态（不是「永远转圈」）。
 * 有缓存的会话首帧就是缓存消息本身 —— 状态行在 `renderMessages` 里被立即撤除。
 */
function renderFlowStatus(hasMsgs, loading) {
  if (!modalEls) return;
  const flow = modalEls.flow;
  flow.querySelector('.fm-flow-status')?.remove();
  if (hasMsgs) return;
  const row = el('div', 'fm-flow-status');
  if (loading) {
    row.classList.add('loading');
    row.setAttribute('aria-busy', 'true');
    row.textContent = t('messages.loading');
  } else {
    row.classList.add('empty');
    row.textContent = t('messages.noMessages');
  }
  flow.appendChild(row);
}

/** 冷路径入口：在等回包的那段窗口里给消息区一个非空、可见、可读屏的加载态。 */
function showFlowLoading() {
  renderFlowStatus(false, true);
}

function renderMessages(msgs, { stickBottom = true } = {}) {
  if (!modalEls) return;
  const conv = currentConv();
  if (!conv) return;
  const flow = modalEls.flow;
  const apply = () => {
    updateLoadMoreRow();
    renderFlowStatus(msgs.length > 0, false); // 内容到位 ⇒ 撤加载态；确为空 ⇒ 换空态
    keyedDiff(flow, msgs, conv);
    // 附件上传卡（attachcl 批）：**好友窗与群窗的唯一挂载点**（本函数服务两种会话面）
    // ⇒ 两面共用同一渲染器（`attachUpload.renderUploadCards`），设备窗不经此
    // （设备面走自己的 dropbox 传输链与状态渲染，零行为变化）。
    if (attachAvailable(conv)) renderUploadCards(flow, conv.conversationId);
    createIconsIn(flow);
  };
  if (stickBottom) {
    // 开窗首帧 / 本机发送：钉到底部。
    apply();
    flow.scrollTop = flow.scrollHeight;
    return;
  }
  // ⑩ 合并/增量路径（`appendMessages` 等）：以**可视锚点**为准补偿。
  // 🔴 不再走 `scrollTop += 新高−旧高`：keyset 增量加在**下方**，该公式会把正在阅读
  //    的用户整体下移一个增量高度（= 「同步到达就跳」）。两面共用一份实现。
  preserveScrollAnchor(flow, apply, 'messageId');
}

/** ⑨ 增量补齐落到 DOM（保持窗口有序 + 阅读位置不跳）。
 *  不重排：keyset 页本身 ASC、且 `after=` 恒取窗口最大 id ⇒ 追加序即升序
 *  （不引入 `Number(id)` 排序 —— mock 面的字符串 id 会被 NaN 打乱既有顺序）。 */
function appendMessages(msgs) {
  const conv = currentConv();
  for (const m of msgs) {
    // U-b 回显认领：REST keyset 增量先于 POST 响应到达时，就地锚定乐观项
    // （不新增气泡/条目）。「本机所发」判据 = 服务端记录里的 `senderId` ——
    // REST 面该字段权威且必带；**缺席 ⇒ 不认领**（照旧走原路径，与 U-a
    // 「不猜」同向）。
    const ours = !!conv && oursBySenderId(m, conv) === true;
    // 群 viewer 身份学习：① 发送关联（id ∈ 本机发送登记表）；② direct 行的
    // 双员封闭（server 端 UNIQUE(user_a,user_b) ⇒ 行内非对方即本机）—— 两者
    // 都是权威 senderId 的合法证书；幂等、零额外请求。
    noteRowForSelfLearning(m);
    if (conv && conv.kind !== 'group' && ours && m.senderId) learnSelfUserId(m.senderId);
    // P5：把**已确证**的极性固化成显式标记（判据缺席 ⇒ 不标记 ⇒ 渲染回退既有形态）。
    const hint = conv ? oursBySenderId(m, conv) : null;
    if (hint !== null) markOurs(m, hint);
    if (claimPendingSend(m, conv && conv.conversationId, ours)) continue;
    if (!chatMsgs.some(x => String(x.id) === String(m.id))) chatMsgs.push(m);
  }
  renderMessages(chatMsgs, { stickBottom: false });
}

// ── 加载更早消息（keyset id-window backward walk）────────
/** 后台探针（②-7 方案 A）：从 oldestLoadedId-1 起按 HISTORY_WINDOW 步回退，找
 *  任何 id < oldestLoadedId 的消息。命中 ⇒ 确定有更早 ⇒ 插入按钮行；一路空窗
 *  退到 after == 0 ⇒ 确定没有更早 ⇒ 不渲染。steps 上限沿既有 20 步：号段稀疏
 *  时可能在退到 0 之前用尽上限（= 未定态）——未定态**一律不渲染**（「不自证
 *  不显示」的安全方向）。纯客户端，零后端改动；与首屏解耦（气泡先出）。 */
async function probeOlderHistory(convId, fromId) {
  let after = Math.max(0, fromId - 1 - HISTORY_WINDOW);
  let steps = 0;
  while (steps < 20) {
    let fetched = [];
    try { fetched = await api.getMessages(convId, { after, limit: PROBE_LIMIT }); }
    catch (e) {
      // W18（§3.3）：探针失败**保持「不显示」**（不自证不显示，行为不变）——但低噪留痕
      // （`debug` 档：探针本身是后台行为，失败是常态，不该刷 warn）。
      console.debug(`[fm] probeOlderHistory 探针取数失败（保持不显示）conversationId=${convId} `
        + `messageId=<none> reason=probe_fetch_failed after=${after} step=${steps} err=${e && e.message}`);
      return;
    }
    if (openConvId !== convId || !modalEls) return; // 会话已换/窗已关
    if ((fetched || []).some(m => Number(m.id) < fromId)) {
      hasMoreHistory = true;
      insertLoadMoreRowAnchored();
      return;
    }
    if (after <= 0) { hasMoreHistory = false; return; } // 确定态：退到 0 仍空
    after = Math.max(0, after - HISTORY_WINDOW);
    steps++;
  }
}

/** 插入按钮行并补偿滚动高度 —— 探针命中可能在用户阅读中途到达，按钮行会改变
 *  flow 上方高度；复用 prependMessages 的 scrollHeight 补偿思路，保证视口不跳。 */
function insertLoadMoreRowAnchored() {
  if (!modalEls) return;
  const flow = modalEls.flow;
  if (flow.querySelector('.fm-load-more-row')) return; // 幂等
  const prevHeight = flow.scrollHeight;
  const prevTop = flow.scrollTop;
  updateLoadMoreRow();
  flow.scrollTop = flow.scrollHeight - prevHeight + prevTop;
}

function updateLoadMoreRow() {
  if (!modalEls) return;
  modalEls.flow.querySelector('.fm-load-more-row')?.remove();
  if (!hasMoreHistory) return;
  const row = el('div', 'fm-load-more-row');
  const btn = el('button', 'glass-control fm-load-more', t('messages.loadingOlder'));
  btn.addEventListener('click', loadOlderMessages);
  row.appendChild(btn);
  modalEls.flow.insertBefore(row, modalEls.flow.firstChild);
}

function setLoadMoreState(state) {
  const btn = modalEls && modalEls.flow.querySelector('.fm-load-more');
  if (!btn) return;
  const loading = state === 'loading';
  btn.disabled = loading;
  if (loading) btn.setAttribute('aria-busy', 'true');
  else btn.removeAttribute('aria-busy');
  btn.textContent = loading ? t('messages.loading') : t('messages.loadingOlder');
}

async function loadOlderMessages() {
  if (!modalEls || !hasMoreHistory || loadingHistory) return;
  const convId = openConvId;
  if (!convId) return;
  loadingHistory = true;
  setLoadMoreState('loading');
  try {
    let fetched = [];
    let fresh = [];
    let after = Math.max(0, oldestLoadedId - 1 - HISTORY_WINDOW);
    let steps = 0;
    const existing = new Set(chatMsgs.map(x => String(x.id)));
    // Ids are table-wide AUTOINCREMENT — a window may contain zero messages of
    // THIS conversation (ids owned by others). Auto-step further back, bounded.
    // 共同口径修正（② 方案 §3.3）：空窗判据从 `fetched.length` 改为 **fresh.length**
    // ——窗口里全是已加载消息时 fetched 非空但 fresh 为空，旧的判据会提前 break
    // 并把 hasMoreHistory 置回 `after > 0`（= true）⇒ 按钮留着重点的死路。
    while (steps < 20) {
      fetched = await api.getMessages(convId, { after, limit: HISTORY_WINDOW });
      if (openConvId !== convId) return; // modal replaced mid-flight
      fresh = (fetched || []).filter(m => !existing.has(String(m.id)));
      if (fresh.length > 0 || after <= 0) break;
      after = Math.max(0, after - HISTORY_WINDOW);
      steps++;
    }
    if (openConvId !== convId || !modalEls) return;
    if (fresh.length) {
      chatMsgs = fresh.concat(chatMsgs);
      oldestLoadedId = Number(chatMsgs[0].id) || oldestLoadedId;
      prependMessages(fresh);
      if (oldestLoadedId > 1) {
        // id 1 是表首（确定无更早）；否则继续探针判定，绝不按「id ≠ 1」自证。
        hasMoreHistory = true;
        await probeOlderHistory(convId, oldestLoadedId);
      } else {
        hasMoreHistory = false;
      }
    } else {
      // 退到 after == 0 仍全是已加载消息（或窗口耗尽）→ 确定态：没有更早了。
      hasMoreHistory = false;
    }
  } catch {
    // ②-8：加载失败不得静默 —— 回 idle 文案 + 一次性可见反馈（可重试）。
    setLoadMoreState('idle');
    modalToast(t('messages.loadOlderFailed'));
  }
  loadingHistory = false;
  if (modalEls) {
    if (hasMoreHistory) setLoadMoreState('idle');
    else updateLoadMoreRow(); // removes the row (history start reached)
  }
}

/** Prepend older bubbles keeping the viewport anchored on the messages the
 *  user is looking at (classic scrollHeight-delta compensation). */
function prependMessages(older) {
  if (!modalEls) return;
  const conv = currentConv();
  if (!conv) return;
  const flow = modalEls.flow;
  const prevHeight = flow.scrollHeight;
  const prevTop = flow.scrollTop;
  const frag = document.createDocumentFragment();
  for (const m of older) frag.appendChild(bubbleEl(m, conv));
  const anchor = flow.querySelector('.fm-load-more-row');
  flow.insertBefore(frag, anchor ? anchor.nextSibling : flow.firstChild);
  createIconsIn(flow);
  flow.scrollTop = flow.scrollHeight - prevHeight + prevTop;
}

/**
 * @param {any} m 服务端消息
 * @param {boolean} [ours] 调用方确证「这是本机所发」（WS 面 = 事件类型
 *   `message_new_self`）—— 只有本机所发的帧才可能是乐观项的回显。
 */
function appendMessage(m, ours = false) {
  const conv = currentConv();
  if (!modalEls || !conv) return;
  // U-b 回显认领：本条就是本地未决乐观项的回显 ⇒ 就地锚定真 id 并**复用原节点**
  // （气泡数不变、条目数不变）。锚定后与「响应腿」同后置条件 ⇒ 幂等。
  if (claimPendingSend(m, conv.conversationId, ours)) {
    modalEls.flow.scrollTop = modalEls.flow.scrollHeight;
    persistConversation(conv);
    return;
  }
  if (!chatMsgs.some(x => String(x.id) === String(m.id))) chatMsgs.push(m);
  // ⑨-E 同 id 幂等：WS 帧与乐观回显（sendCurrent 已把 temp id 换成真 id）撞车时
  // 只留一个节点 —— 重复气泡会让 M5「渲染 id 序列」直接不等。
  const dup = modalEls.flow.querySelector(`.fm-msg[data-message-id="${CSS.escape(String(m.id))}"]`);
  if (dup) dup.remove();
  modalEls.flow.appendChild(bubbleEl(m, conv));
  createIconsIn(modalEls.flow);
  modalEls.flow.scrollTop = modalEls.flow.scrollHeight;
  persistConversation(conv); // ⑨ 落盘：WS 帧自带 body，无需再问服务端
}

// ── Forward to agent (§3.3 R7, one-way) ──────────────────
// #290 addendum §3 (R3=②): forward = a friend-message Reference drafted into
// the ACTIVE chat input (pendingAttachments) - never auto-sent. The old
// text-prefix `[来自 X] body` direct injection is removed; all three entries
// (bubble right-click / hover button / header button) share this handler.
// The 「已转发给 agent」 chip is stamped by the 'fm-refs-sent' event from
// input.js when the ref actually leaves on the wire (session-level, R5).
// Data-driven core shared by the manual entry (bubble DOM) and the trust-mode
// auto-forward (raw message object, no DOM needed). Returns true when the ref
// landed in the ACTIVE chat input; false = no active session view (never
// silent for the manual path — the caller toasts; auto-forward just skips).
function forwardToAgent({ body, messageId, direction, createdAtMs }, conv) {
  if (!body) return false;
  // 设备面（卡 §1.3-8/9）：入参映射 `body←text` / `messageId←msgId` /
  // `direction←direction` / `createdAtMs←ts`（由调用侧适配层给出，本函数只接线）。
  // 🔴 `refType` 仍为 `'friend-message'`（禁改链零触碰 ⇒ 一期接受语义不纯，卡 O6）。
  const isDevice = !!(conv && conv.kind === 'device');
  const ref = makeReference({
    refType: 'friend-message',
    source: {
      conversationId: conv.conversationId || '',
      messageId: messageId || '',
      // 设备面无名册档案 ⇒ 名字取设备自报（`deviceLabel`），neblinkId 恒空。
      friendName: isDevice ? deviceLabel(conv.device) : (conv.friend?.name || ''),
      friendNeblinkId: conv.friend?.neblinkId || '',
      direction: direction === 'out' ? 'out' : 'in',
      date: refDate(createdAtMs),
    },
    content: { fullText: body.slice(0, 4000) },
  });
  if (!ref) return false;
  return appendRefToActiveView(ref);
}

function forwardBubble(wrap, conv) {
  const ok = forwardToAgent({
    body: wrap.dataset.body,
    messageId: wrap.dataset.messageId,
    direction: wrap.classList.contains('out') ? 'out' : 'in',
    createdAtMs: wrap.dataset.createdAt,
  }, conv);
  // 设备面（卡 P4）：`msgId` 是 uuid 形状，与好友数字 id 在 `forwardedIds` 同集合里
  // 会撞域 ⇒ 设备侧统一加 `dev:` 前缀（写/读两侧同一函数 `fwdKeyOf`）。
  if (ok && conv && conv.kind === 'device') stampForwarded(fwdKeyOf(conv, wrap.dataset.messageId), wrap.dataset.messageId);
  // No ACTIVE chat view (nothing open in the main window) → appendRef returns
  // false. Never silent: guide the user to open a session first (0904 audit
  // break-point fix — previously a silent no-op).
  modalToast(ok ? t('messages.forwardToast') : t('messages.forwardNoSession'));
}

// forwardedIds members arrive as strings (fm-refs-sent detail) while live
// message ids may be numbers — normalize at the boundary.
function hasForwarded(id) { return forwardedIds.has(id) || forwardedIds.has(String(id)); }

/** 转发「已转发」标记的 id 命名空间（设备会话统一批 MVP-1 · 卡 P4）：
 *  `forwardedIds` 是**字符串集合**，好友/群用服务端数字 id、设备用 `msgId`（uuid 形状）
 *  —— 两个 id 域混存会互相碰撞 ⇒ 设备侧统一加会话前缀（写侧 `stampForwarded` 与
 *  读侧 `bubbleEl` 都过本函数，禁两处各写一次前缀）。 */
function fwdKeyOf(conv, id) {
  const raw = String(id == null ? '' : id);
  return (conv && conv.kind === 'device') ? DEVICE_CONV_PREFIX + raw : raw;
}

/** Stamp the 「已转发给 agent」 chip (set + open bubble, if rendered). Idempotent.
 *  @param {string} id 集合键（设备面 = `dev:<msgId>`）
 *  @param {string} [domId] DOM 查键（设备面 = 裸 `msgId`，即 `dataset.messageId`） */
function stampForwarded(id, domId) {
  forwardedIds.add(id);
  forwardedIds.add(String(id));
  if (!modalEls) return;
  const wrap = modalEls.flow.querySelector(`.fm-msg[data-message-id="${CSS.escape(String(domId === undefined ? id : domId))}"]`);
  const meta = wrap && wrap.querySelector('.fm-msg-meta');
  if (meta && !meta.querySelector('.fm-msg-forwarded-badge')) {
    meta.prepend(el('span', 'fm-msg-forwarded-badge', t('messages.forwarded')));
  }
}

// ── 信任模式 v1 自动转发 ── 新到 INCOMING 消息 + 好友 trusted → 复用既有
// Reference 起草通道（永不自动发送，与手动转发同形态）。门禁顺序：
// ① 仅好友发来的消息 ② 仍是好友且未拉黑（黑名单优先于信任——blocked 行
// 不在 friendsCache 里，isStillFriend 一并覆盖删除态） ③ trusted ④ 防重
// （同消息只转一次，与 forwardedIds 对齐）。起草成功立即打「已转发」角标。
// 通知形态不变：仅好友消息既有角标三级，无横幅无提示音（08-18 裁定）。
function maybeAutoForward(m, conv) {
  // SEALED (author ruling 2026-09-12): 封存期行为 early-return——门禁链与函数体
  // 完整保留（onFriendEvent 内调用点、initMessages 内 fm-trust-changed 监听
  // 均不动）。回退 = featureFlags.js 常量改回 false。
  if (TRUST_SEALED) return;
  if (!m || !conv || !conv.friend) return;
  if (m.senderId !== conv.friend.userId) return; // incoming only
  if (!isStillFriend(conv)) return;              // blocked/deleted beats trust
  if (!isFriendTrusted(conv.friend.userId)) return;
  if (hasForwarded(m.id)) return;                // same message forwards once
  if (forwardToAgent({
    body: m.body || '',
    messageId: String(m.id ?? ''),
    direction: 'in',
    createdAtMs: toEpochMs(m.createdAt),
  }, conv)) {
    stampForwarded(m.id);
  }
}

/** Date label for the ref meta / injection text layer: YYYY-MM-DD HH:mm. */
function refDate(createdAtMs) {
  const ms = Number(createdAtMs) || 0;
  if (!ms) return '';
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// Chip stamping on actual send (input.js dispatches after sendWs).
function onRefsSent(e) {
  const ids = (e.detail && e.detail.messageIds) || [];
  if (!ids.length) return;
  for (const id of ids) stampForwarded(id);
}

let toastTimer = null;
function modalToast(text) {
  if (!modalEls) return;
  modalEls.toast.textContent = text;
  modalEls.toast.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { if (modalEls) modalEls.toast.hidden = true; }, 2000);
}

// ── Send (§6.2 sending/delivered/failed) ─────────────────
async function sendCurrent(conv) {
  if (!modalEls) return;
  const body = modalEls.input.value.trim();
  if (!body || body.length > 2000) return;
  modalEls.input.value = '';
  syncComposerSend(); // 已清空 ⇒ 发送键回禁用态（判据单源）

  const tempId = 'fm-tmp-' + (++msgSeq);
  const optimistic = { id: tempId, senderId: 'me', kind: 'text', body, createdAt: new Date().toISOString() };
  // ⑨-E：乐观回显登记进窗口 —— keyed diff 才知道这个节点「该在」，否则任何一次
  // 增量补齐的重排都会把它当差集删掉（用户会看到自己刚发的消息凭空消失）。
  chatMsgs.push(optimistic);
  const wrap = bubbleEl(optimistic, conv);
  wrap.classList.add('fm-sending');
  modalEls.flow.appendChild(wrap);
  createIconsIn(modalEls.flow);
  modalEls.flow.scrollTop = modalEls.flow.scrollHeight;
  // U-b：登记为「发送中的乐观项」——回显（WS 自播帧 / REST keyset 增量）若先于
  // 响应到达，由 claimPendingSend 认领回这一条（不新增气泡）。
  const pending = { tempId, convId: conv.conversationId || '', body, node: wrap, entry: optimistic, anchoredTo: null };
  pendingSends.push(pending);

  try {
    // 群发分支（补充卡 §5.1 逐字契约：POST /api/groups/{id}/messages {body} →
    // SendMessageResponse 同形 {messageId, conversationId, createdAt, ...}）。
    // 🔴 UI 直发无 origin 字段（补充卡 §5.4 写权矩阵第一行）⇒ 服务端落 'user'。
    const resp = conv.kind === 'group'
      ? await api.sendGroupMessage(conv.conversationId, body)
      : await api.sendFriendMessage(conv.friend.userId, body);
    const realId = resp.messageId || tempId;
    noteSentRealId(realId); // self 识别关联源（群方向判据的学习输入，见 §状态段）
    // 群发回执面 `selfUserId`（契约终版 §1.1 #8）= 发送者鉴权身份 ⇒ 最强证据，
    // 即时收敛 viewer 身份（不等自播帧/后续拉取）。🔴 单聊路径**刻意忽略**该键
    // （同形共享信封的外溢字段，已裁：单聊面不消费 —— 单聊方向判据走既有
    // conv.friend.userId 双员封闭，无需 viewer 身份）。
    if (conv.kind === 'group' && resp && resp.selfUserId) learnSelfUserId(resp.selfUserId);
    wrap.classList.remove('fm-sending');
    // U-b 唯一锚定点：回显已先到时此处**幂等**（同一后置条件，节点/条目数不变）；
    // 回显未到时即既有的「temp id → 服务端 id」换键。
    if (!anchorSendToRealId(pending, realId)) {
      // 窗口已关/重开（乐观节点脱离文档）⇒ 退回最小改键（不触碰新窗口的状态）。
      wrap.dataset.messageId = realId;
      const idx = chatMsgs.findIndex(x => x.id === tempId);
      if (idx >= 0) chatMsgs[idx] = { ...optimistic, id: realId }; // temp id → 服务端 id
    }
    forgetPendingSend(pending);
    if (!conv.conversationId && resp.conversationId) {
      // First send created the conversation (friend-addressed send)
      conv.conversationId = resp.conversationId;
      openConvId = resp.conversationId;
    }
    // delivered: silent (§6.2 克制)
    conv.lastMessage = { ...optimistic, id: realId };
    conv.lastMessage.agentSent = false;
    resortAndRender();
    persistConversation(conv); // ⑨ 落盘（temp id 由缓存层过滤，不会存成幻影）
  } catch (err) {
    forgetPendingSend(pending);
    wrap.classList.remove('fm-sending');
    if (conv.kind === 'group') {
      // 群发终态错误（补充卡 §5.3：404 group_not_found / 403 group_disbanded /
      // 403 not_member）⇒ 就地移除该群行 + 可见反馈（退群后历史不可见的呈现）；
      // 其余（网络/5xx/429）照既有失败旗标重试面（正文不丢）。
      const code = err && err.data && (err.data.error || err.data.code);
      const terminal = (err && err.status === 404 && code === 'group_not_found')
        || (err && err.status === 403 && (code === 'group_disbanded' || code === 'not_member'));
      if (terminal) {
        groupErrToast(err);
        wrap.remove();
        const i = chatMsgs.findIndex(x => x.id === tempId);
        if (i >= 0) chatMsgs.splice(i, 1);
        conversations = conversations.filter(c => c.conversationId !== conv.conversationId);
        renderList();
        updateBadge();
        closeChat();
        return;
      }
    }
    wrap.classList.add('fm-failed');
    const flag = el('button', 'fm-retry', '!');
    flag.title = t('messages.send');
    flag.addEventListener('click', () => {
      wrap.remove();
      // 重试会生成新的 temp id ⇒ 旧条目必须出窗口，否则 keyed diff 把刚删掉的
      // 失败气泡又插回来（一屏两个失败气泡）。
      const i = chatMsgs.findIndex(x => x.id === tempId);
      if (i >= 0) chatMsgs.splice(i, 1);
      modalEls.input.value = body;
      syncComposerSend(); // 回填非空文本 ⇒ 发送键回可用态（重试路径不得留假禁用）
      sendCurrent(conv);
    });
    wrap.appendChild(flag);
  }
}

function resortAndRender() {
  conversations.sort((a, b) =>
    (toEpochMs(b.lastMessage?.createdAt) || 0) - (toEpochMs(a.lastMessage?.createdAt) || 0));
  renderList();
}

// ── 附件发送（attachcl 批）：好友窗与群窗的**同一**入口 ────────────────
/**
 * 纸夹键与拖放面**共用**的发送链（两面同一实现；见 `attachUpload.sendFiles`）。
 *
 * 语义要点（与设备面同族、与任务书硬钉对齐）：
 *  · **闸在最前**：`sendFiles` 内部先过本地闸（件数 ≤9 / 单件 ≤1 GiB / 非空件），
 *    超限**可见拒绝**并回显实际值 —— 与网关的早拒是两道闸，且都**不晚于传输前**；
 *  · **进度可信**：卡片进度只随网关「服务端已确认一块」的 WS 帧推进（禁假进度）；
 *  · **失败可见**：上传未成功 ⇒ **消息不发**、卡片就地显示可判读文案；正文**不丢**
 *    （仍在输入框里）；
 *  · **取消**：卡片上的取消键 ⇒ 停后续分块 + 终态「已取消」（禁报成完成）。
 * @returns {Promise<void>}
 */
async function sendAttachCurrent(conv, fileList) {
  if (!modalEls || !conv) return;
  const text = modalEls.input.value.trim();
  const res = await sendFiles(conv, fileList, text);
  if (!res.ok) {
    modalToast(res.reason || t('messages.attachFailed'));
    return; // 正文留在输入框（与 sendCurrent 的失败面同语义）
  }
  // 发送成功 ⇒ 清输入框 + 走既有重取链（服务端行是唯一事实源，禁本地乐观气泡）。
  modalEls.input.value = '';
  syncComposerSend();
  await refreshAfterAttachSend(conv);
}

/** 附件消息发送后的可见刷新（复用既有增量补拉链 = 唯一取数实现，禁另写尾窗重取）。
 *  服务端会为纯附件消息生成占位正文 ⇒ 补拉到的服务端行即权威呈现（含附件卡）。 */
async function refreshAfterAttachSend(conv) {
  try {
    if (conv.kind === 'group') await refreshGroups();
    await syncConversation(conv.conversationId, { pages: MAX_SYNC_PAGES, trigger: 'attach_send' });
    await refreshConversations();
  } catch { /* 重取失败不改终态：卡片已显示「已发送」，服务端行由后续帧/刷新补齐 */ }
}

// ── friend_event (arch §6.2) ─────────────────────────────
/** 事件名判据**单点**（K-2 段 A 2026-09-12）：与 Scala 侧
 *  `FriendService.MessageNew / MessageNewSelf` **逐字同名** —— 两侧不得各写一套
 *  字面量（事件名漂移 = 分支静默失配，正是本批 K-2 的病灶形态：`message_new_self`
 *  两侧都无分支、且字面量各写一套时无人能发现）。
 *  `message_new` = 对方所发；`message_new_self` = **本账号在他处所发**（agent 代发 /
 *  同账号另一台设备）——后者**不计未读**。 */
const EV_MESSAGE_NEW = 'message_new';
const EV_MESSAGE_NEW_SELF = 'message_new_self';

/** 帧 → 本地消息对象（**唯一实现**）：网关 `FriendEvent.frontendFrame` 已把
 *  `payload` 展平到帧顶层，两个 `message_*` 分支共用本函数 ⇒ 字段名只此一处
 *  （不得各写一套读法）。
 *  @param {any} p 网关 friend_event 帧（扁平） */
function frameMessage(p) {
  return {
    id: p.messageId,
    senderId: p.senderId || p.sender?.userId,
    kind: p.kind,
    body: p.body,
    createdAt: p.createdAt,
    // 4b 腿 A：推送帧与 REST 面**同形**（服务端 §B.2 推送 builder 单点）——
    // 键缺席 = 无附件（老服务端/纯文本消息，逐字节现状）。
    attachments: p.attachments,
    // 批 D（agent 代发 footer 标识）：`origin` 是 #290 spec v1.1 §2.4 的**语义承载
    // 键**（徽标 / 审计 / 限速区分），不是纯展示字段。本函数是**白名单式**字段
    // 枚举 ⇒ 服务端 payload → 隧道 → 网关 `frontendFrame` 展平一路都在的
    // `origin`，**在这一跳被抹掉** ⇒ `isAgentSent` 恒 false ⇒ 徽标永不渲染
    // （丢字段，不丢消息）。与 r2 的 `attachments` 同款加性扩面：键缺席 =
    // `undefined`（老服务端 / 无该字段）⇒ 前端按「缺键 ≠ agent」读。
    origin: p.origin,
  };
}

/** U-a 幂等前置（本批）：**帧级 messageId 去重**（有界 FIFO）。
 *
 *  为什么需要：网关侧 `FriendMessagingGuard.dedupe` 只按 **eventId** 去重，而同一个
 *  messageId 可以带**不同 eventId** 二次到达（live 推送 + 服务端重放 / 本机代发自播 +
 *  另一台设备推送 / REST 回补与推送撞车；自播帧的 eventId 是 fresh uuid，
 *  与服务端 message-<id> 恒不同）。修前该形态在**未开会话**时把
 *  `conv.unreadCount` 重复 +1（角标虚高），并让 `maybeAutoForward` 把同一条消息
 *  重复草拟进 agent 输入 —— `appendMessage` 自身的同 id 幂等只覆盖**开着窗**的
 *  DOM 节点，这两条路都不在它的覆盖面内（实测读数见下）。
 *  幂等方向 = 「不猜」：缺席 messageId（帧里读不到）**不拦**，照旧走原路径。
 *  有界：FIFO ≤512（前端只做近窗；网关侧同族上限 `maxSeenEvents=2048`）。 */
const seenFrameMessageIds = new Set();
const seenFrameMessageIdOrder = [];
const SEEN_FRAME_MESSAGE_MAX = 512;
/** @returns {boolean} true = 首次见到（继续原路径）；false = 重复（调用方直接 return）。 */
function markFrameMessageSeen(id) {
  if (id === undefined || id === null || id === '') return true;
  const k = String(id);
  if (seenFrameMessageIds.has(k)) return false;
  seenFrameMessageIds.add(k);
  seenFrameMessageIdOrder.push(k);
  if (seenFrameMessageIdOrder.length > SEEN_FRAME_MESSAGE_MAX) {
    const oldest = seenFrameMessageIdOrder.shift();
    if (oldest !== undefined) seenFrameMessageIds.delete(oldest);
  }
  return true;
}

/** 批 C：**未读计数的幂等集**（与渲染去重 `seenFrameMessageIds` **分离**，理由见
 *  `onFriendEvent` 的 message_new 分支注释）。
 *
 *  为什么不能与渲染去重共用一个集合：两件事的**权威顺序不同** —— 渲染的权威是
 *  「这条消息的正文在不在窗口里」（任一种帧都算），未读的权威是「这次到达是不是
 *  **新的一条对方消息**」（只有真事件帧算）。共用一个集合就让「哪种帧先到」变成
 *  计数的隐藏输入（非确定、且跨实例不同）。分开后判定与到达顺序无关。
 *  有界：FIFO ≤512（与 `seenFrameMessageIds` 同上限，同族先例）。 */
const countedUnreadIds = new Set();
const countedUnreadIdOrder = [];
const COUNTED_UNREAD_MAX = 512;
/** @returns {boolean} true = 本条尚未计过未读（调用方 +1）；false = 已计过（跳过）。 */
function markUnreadCounted(id) {
  if (id === undefined || id === null || id === '') return true;
  const k = String(id);
  if (countedUnreadIds.has(k)) return false;
  countedUnreadIds.add(k);
  countedUnreadIdOrder.push(k);
  if (countedUnreadIdOrder.length > COUNTED_UNREAD_MAX) {
    const oldest = countedUnreadIdOrder.shift();
    if (oldest !== undefined) countedUnreadIds.delete(oldest);
  }
  return true;
}

// ── W13 待补队列（会话缓存缺失时不静默丢帧）────────────────
// 帧可能**先于**会话列表到达（重连窗口 / 全新会话首条 / 面板未挂载）。修前那条分支
// 只 `refreshConversations()` 后 `return` —— 帧**零日志地消失**；刷新之后没人再把它
// 送进来，于是「列表对了、打开的窗还是空的」。此处暂存该帧，刷新后**补投一次**。
const pendingFriendFrames = [];
const PENDING_FRIEND_FRAME_MAX = 32;

/** 补投暂存帧（每帧至多补投一次：补投调用带 `retried=true`，不再入队、不再触发第二轮）。 */
async function retryPendingFriendFrames() {
  if (!pendingFriendFrames.length) return;
  const batch = pendingFriendFrames.splice(0, pendingFriendFrames.length);
  for (const f of batch) await onFriendEvent(f, true);
}

/** 补拉帧的**逐条**极性（`backfill` 帧恒带 `senderId`，故此处优先按逐条证据判）。
 *  返回 null = 证据缺席 ⇒ 沿用调用方给的事件级极性。 */
function frameOursHint(m, conv) {
  const byId = oursBySenderId(m, conv);
  return byId === null ? null : byId;
}

async function onFriendEvent(msg, retried = false) {
  // 帧静默门控的唯一打点（**任何** friend 帧都算：判据是「通道还在不在送帧」）。
  lastFriendFrameAt = Date.now();
  if (msg.event === EV_MESSAGE_NEW) {
    const p = msg;
    const conv = conversations.find(c => c.conversationId === p.conversationId);
    if (!conv) {
      // W13（§3.3）：修前**零日志**地丢帧。必须留痕 + **入待补队列**（刷新后补投）。
      // 🔴 批 C 去歧义（W13 一名两处）：补 `branch=` 键，取值**唯一**（W13a=入站面）。
      // 机械计数一律读 `branch=`（**精确值**）而不是 `reason=` —— 后者两侧存在前缀
      // 关系（`conversation_not_cached` ⊂ `conversation_not_cached_self`），按
      // `contains` 计数会让「应报数 == 出现数」对 W13 不可判（与 W1 同族形态）。
      // `reason=` 取值**保持批 A 原值**（跨批契约，禁改）。
      console.warn(`[fm] friend_event 帧到达但会话缓存缺失（REST 为权威）event=${msg.event} `
        + `branch=W13a conversationId=${p.conversationId} messageId=${p.messageId} `
        + `reason=conversation_not_cached retried=${retried}`);
      if (!retried) {
        if (pendingFriendFrames.length >= PENDING_FRIEND_FRAME_MAX) pendingFriendFrames.shift();
        pendingFriendFrames.push(msg);
      }
      await refreshConversations({ friends: 'force' }); // REST is truth
      if (!retried) await retryPendingFriendFrames();
      return;
    }
    const m = frameMessage(p);
    noteRowForSelfLearning(m); // 群 viewer 身份学习（发送关联；幂等零开销）
    const isOpen = openConvId === p.conversationId;
    // 🔴 批 C（批 A §11.3 划归本批的**回放帧前端幂等去重**实现面）：
    // 「**已渲染**去重」与「**已计未读**去重」必须**分开**，不能共用一套集合。
    //
    // 为什么：批 A 的「拉取即派发」使**同一个 messageId** 可能以两种帧先后到达
    // （回放帧 `backfill:true` + 真事件帧）。共用 `markFrameMessageSeen` 时，**先到的
    // 那一帧决定后一帧的生死**：
    //   · 回放帧先到 ⇒ id 被标成「见过」⇒ 随后真事件帧在下面 `markFrameMessageSeen`
    //     处**早退** ⇒ 未读**不涨**（角标少 1，且全程静默 —— 正是本仓缺陷族形态）；
    //   · 事件帧先到 ⇒ 无害（回放帧本就不计数、不回放不涨未读）。
    // ⇒ 未读计数走**自己的**幂等集（`countedUnreadIds`）：与渲染去重解耦，两集各有界。
    // 计数口径**零变化**（判据与修前逐字一致）：仅在**未开会话** + **非回放帧** +
    // **发送方确为对方**（正向证据，`sender != me`）时 +1 ⇒ 「self 不计未读」不变。
    if (!isOpen && p.backfill !== true) {
      // 群分支（C-2：别人的消息计未读、自己的不计）= 单一判据点 oursBySenderId
      // 的群分支复用（有 senderId 且非本机 ⇒ false ⇒ 计；'me'/viewer 身份 ⇒ 不计；
      // 证据缺席 ⇒ 不计，保守向）。单聊判据逐字不变。
      const fromPeer = conv.kind === 'group'
        ? oursBySenderId(m, conv) === false
        : !!(m.senderId && conv.friend && m.senderId === conv.friend.userId);
      if (fromPeer && markUnreadCounted(m.id)) conv.unreadCount = (conv.unreadCount || 0) + 1;
    }
    // U-a 幂等：同 messageId 的重复到达不得二次上屏 / 二次自动转发（**渲染面**）。
    if (!markFrameMessageSeen(m.id)) return;
    conv.lastMessage = m;
    // §3.2① 补拉回放帧（`backfill: true`）：只做**渲染/预览**，不计数、不转发、不认领。
    // 未读的权威来源是事件增量 + 服务端 `unreadCount` 基线；补拉是渲染修复，不是计数
    // 依据 —— 否则冷启动后的一页历史回补会把角标刷成虚高（冲垮「self 不计未读」口径）。
    // 极性取**逐条**证据（同一窗里可能既有对方的消息也有本机自送的消息），
    // 证据缺席才回落到事件级极性（`message_new` = 对方所发）。
    if (p.backfill === true) {
      markOurs(m, frameOursHint(m, conv) === true);
      if (isOpen) appendMessage(m, false); // 回放帧不进乐观认领（旧条没有在飞的乐观项）
      resortAndRender();
      updateBadge();
      return;
    }
    markOurs(m, false); // 事件类型 = 正向证据（`message_new` 恒为对方所发）
    if (isOpen) {
      appendMessage(m); // 内部已落 ⑨ 缓存
      api.markConversationRead(p.conversationId, m.id).catch(() => {});
      conv.unreadCount = 0;
    }
    // 未开会话的未读 +1 已在上方（渲染去重之前）按「已计未读」判据落账 —— 修前它在
    // 这里，落在 `markFrameMessageSeen` **之后** ⇒ 被回放帧抢先时整条不涨（批 C 修复面）。
    // 口径本身零变化：inbound only counts / our own / agent-sent never unread（§4）。
    // 信任模式 v1：trusted 好友的新到消息自动起草进 agent 输入（单向）。
    // Note: 全新会话的首条消息走上方 refreshConversations 早退分支，不在此
    // 自动转发（会话缓存缺失时的已知边界，后续消息正常覆盖）。
    maybeAutoForward(m, conv);
    resortAndRender();
    updateBadge();
    return;
  }
  if (msg.event === EV_MESSAGE_NEW_SELF) {
    // K-2（段 A）：**本账号在他处所发**（agent/工具代发，或同账号的另一台设备）。
    // 修前无此分支 ⇒ 帧被整条忽略：会话预览/角标/开着窗全都不动，只有「关窗再开」
    // （重挂载取数）才可见。语义 = 上屏 + 列表预览/角标刷新，**不涨未读**
    // （自送消息不计未读，§4 口径 sender != me）——`unreadCount` 两条支路一律不动，
    // 与上方 message_new 的 inbound 支路形成显式对照。也不触发 maybeAutoForward
    // （信任模式自动转发只面向对方来件，自送件回灌进 agent 输入是反语义）。
    const conv = conversations.find(c => c.conversationId === msg.conversationId);
    if (!conv) {
      // W13（self 面同族）：同样必须留痕 + 入待补队列。
      // 🔴 批 C 去歧义（W13 一名两处）：同族**self 面**子分支 ⇒ `branch=W13b`。
      console.warn(`[fm] friend_event 帧到达但会话缓存缺失（REST 为权威）event=${msg.event} `
        + `branch=W13b conversationId=${msg.conversationId} messageId=${msg.messageId} `
        + `reason=conversation_not_cached_self retried=${retried}`);
      if (!retried) {
        if (pendingFriendFrames.length >= PENDING_FRIEND_FRAME_MAX) pendingFriendFrames.shift();
        pendingFriendFrames.push(msg);
      }
      await refreshConversations({ friends: 'force' }); // REST is truth
      if (!retried) await retryPendingFriendFrames();
      return;
    }
    const m = frameMessage(msg);
    noteRowForSelfLearning(m); // 群 viewer 身份学习（message_new_self 常带 senderId）
    // U-a 幂等：与 message_new 同判据、同实现（同 id 只做一次上屏/落盘）。
    if (!markFrameMessageSeen(m.id)) return;
    conv.lastMessage = m;
    // U-b：`message_new_self` 事件的语义 = 本机所发⇒ 是本机在飞乐观项的回显形态，
    // 允许认领（`message_new`= 对方所发，**不认领**：缺席 senderId 的入站帧与
    // 本机乐观项同判据会归错，判据宁缺勿滥）。
    //
    // P5：方向由**事件类型**正向确证（self 事件恒为本机所发）。补拉回放帧另按逐条
    // `senderId` 判（`backfill` 帧恒带该字段），证据缺席才回落事件级极性。
    if (msg.backfill === true) {
      markOurs(m, frameOursHint(m, conv) === true);
      if (openConvId === msg.conversationId) appendMessage(m, false);
      resortAndRender();
      updateBadge();
      return;
    }
    markOurs(m, true);
    if (openConvId === msg.conversationId) appendMessage(m, true); // 复用既有 append 腿
    // 未开会话：仅下方刷新（列表预览 + 角标），不 append、不计未读。
    resortAndRender();
    updateBadge();
    return;
  }
  if (msg.event === 'friend_accepted') {
    // New friendship → empty conversation appears (summary: systemNowFriends)
    await refreshConversations({ friends: 'force' });
    window.dispatchEvent(new CustomEvent('fm-friends-changed'));
    return;
  }
  if (msg.event === 'friend_request') {
    // §3.1①（批 B）：修前本分支**只**派发 `fm-friends-changed`（= 好友列表/申请面），
    // 申请状态与「新的朋友」列表的可见性因此完全依赖**下游监听者当时是否在册**
    // （取证稿 §3.1 现状读数：「friend_request：只派发 fm-friends-changed，不刷新
    // 申请状态/列表」）。补一次**同拍强刷**（`friends:'force'` ⇒ 会话 + `GET /api/friends`
    // 并行取，与 `friend_accepted` 分支**逐字同形**）——判据①要求的是「帧到达后
    // ≤1 个 beacon 拍内 GET /api/friends 状态翻转」，不能让这条保证挂在监听器挂载
    // 时序上。🔴 禁新端点：`getFriends()` 已返回双向 pending（`friendsApi.js:249`）。
    // `await` 不改变事件语义（帧处理本身已是异步，且 `onFriendEvent` 的调用方不
    // 依赖本分支的返回值）。
    await refreshConversations({ friends: 'force' });
    window.dispatchEvent(new CustomEvent('fm-friends-changed'));
  }
}

// ── ①opt-A3 + 帧静默门控（方案 §2.1，判据 P4 / 本批 §12.1）────────────
// 载体 = activityBar.js **既有** 10s 状态 beacon（`onStatusTick`）——不新增定时器、
// 不新增端点、不改协议。
//
// 🔴 红线口径变更（作者 2026-09-13 裁定放行）：原文是「健康的 WS 路径零额外请求
// （⑨ M1②/M3 红线：不得出现多余 REST）」——那条红线判的是**通道还活着**，而不是
// **本机真的收到了帧**：`relayUsable()` 探的是「网关 ↔ 服务端隧道」（真源
// `RestApiRoutes relayAvailable = relayTunnelOpt.exists(_.isAlive)`），隧道报活
// 而帧不来（上游丢帧 / 隧道半死假活 / 网关广播时浏览器正处 WS 重连窗口）时，
// 客户端**恒零请求、陈旧无上界**，只能等用户动作（切面板 / 关窗再开）——
// 这正是「不自己出来，要切页面才出现」的机制（取证正本 §7.2 RC-C1 / §10.2-4）。
// 新口径 = **「已确证送达的健康路径零额外请求」**：门控判据从「隧道是否活」换成
// **「距上次收到任何 friend_event 帧是否超过 N 秒（帧静默）」**；帧静默 ⇒ 执行
// 一次增量核对。代价 = 健康态每 beacon 拍最多 1 次极小请求（列表态 1 次
// `GET /api/conversations`；开窗态另加 1 次 `after=<水位>&limit=50` 的空响应
// keyset，约 6 次/分钟），换来**陈旧上界 ≤ N + 1 次往返 ≈ 10.2 s**（原为无界）。
// 唤醒面（回前台/回网 `wakeResync`）与「隧道判死」腿语义不变。
/** 通道是否可用。`relay` 未知（状态未取到 / 老网关无该字段）按**不可用**处理 ——
 *  降级兜底的方向是「多花一次极小请求」，不是「静默不补」。 */
function relayUsable() {
  const r = getNeblinkState().relay;
  return !!(r && r.available === true);
}

/** 节流 > beacon 周期 ⇒ 至多一拍一次；`document.hidden` 守卫与 beacon 同语义。 */
const BACKFILL_THROTTLE_MS = 9000;

/** 帧静默阈值（毫秒）。配置键 `messages.frameSilenceMs`（`<home>/nebflow.json`
 *  顶层 `messages` 节，走既有 WS `configData` → `state.parsedConfig` 通道，
 *  与 `features.friends` 同源同语义：改配置后刷新页面生效，无 live toggle）。
 *  缺省 **10000**（= 既有 beacon 周期 10 s ⇒ 静默一拍的语义即「整整一拍没帧」）。
 *  读取点 = 本函数（唯一），非法值（非数 / ≤0）一律回落缺省。 */
const FRAME_SILENCE_MS_DEFAULT = 10000;
function frameSilenceMs() {
  const v = Number(state.parsedConfig?.messages?.frameSilenceMs);
  return Number.isFinite(v) && v > 0 ? v : FRAME_SILENCE_MS_DEFAULT;
}

/** 最近一次收到 friend_event 帧的时刻（毫秒）。写入点唯一 = `onFriendEvent` 入口
 *  （**任何** friend 帧都算：message_new / message_new_self / friend_request /
 *  friend_accepted —— 判据是「通道是否还在给我们送帧」，不是某一个事件类型）。 */
let lastFriendFrameAt = 0;

/** 距上次收帧是否已超过静默阈值（含义 = 该做一次增量核对了）。 */
function frameSilent() {
  return Date.now() - lastFriendFrameAt > frameSilenceMs();
}

/**
 * **增量复同步单点**（K-4 降级回补 与 本批「回前台/回网即增量拉」**共用同一实现**，
 * 禁造第二份 —— 两处若各写一套分派，红线（禁尾窗全量）会在其中一处悄悄失守）。
 *
 * 分派判据（与列表/开窗两态一一对应）：
 *  · 列表态（未开任何会话 / `__pending__` 占位）⇒ `refreshConversations()`
 *    —— 列表预览 + 角标，既有腿，默认 `'reuse'` = 1 次往返；
 *  · 开窗态 ⇒ `syncConversation(convId, { pages: MAX_SYNC_PAGES })`
 *    —— 只走 keyset `after=<水位>` 前进拉取，**永不重取尾窗**（M1③ 红线）。
 *
 * @param {{withList?: boolean}} [opts] withList = 开窗态是否**顺带**刷列表。
 *   唤醒面需要（后台期间别的会话的预览/角标也停了）；K-4 的 10s beacon 只在通道
 *   失效时动作、必须保持最省，故默认 false（= 修前 backfillTick 的逐字节语义）。
 */
async function incrementalResync({ withList = false } = {}) {
  const convId = openConvId;
  const open = !!convId && !String(convId).startsWith('__pending__');
  if (!open || withList) await refreshConversations();
  if (open) await syncConversation(convId, { pages: MAX_SYNC_PAGES, trigger: 'beacon_backfill' });
}

async function backfillTick() {
  if (!loggedIn() || document.hidden) return;
  // 帧静默门控（作者 2026-09-13 裁定放行，口径见上方红线段）：
  //   · 最近 N 秒内收到过 friend 帧 且 隧道报活 且 WS 在连 ⇒ 已确证送达，零请求
  //     （= 修前「健康路径零请求」的**保真子集**：帧真的在流）；
  //   · 任一不成立 ⇒ 做一次增量核对（隧道判死腿沿用修前语义：不因「刚有过帧」
  //     而跳过；帧静默腿为本批新增）。
  if (!frameSilent() && relayUsable() && state.connected) return; // 已确证送达：零请求
  const now = Date.now();
  if (now - lastBackfillAt < BACKFILL_THROTTLE_MS) return;
  lastBackfillAt = now;
  // K-4（段 A 2026-09-12）：**列表/角标兜底**。修前此处是 `if (!convId) return`
  // ——用户停在列表态（没开任何会话）时，通道失效期间零补偿：列表预览与角标停在
  // 旧值，只有手动重挂载（切面板 / 关窗再开）才更新（正本 §2(c) 的结构性残余）。
  // 现在退化为一次 `refreshConversations()`（列表 + 角标，与 M1② 同一条既有腿，
  // 默认 `'reuse'` = 1 次往返）。
  // 红线不动：本支路**不发任何消息窗口请求**（`limit=200` 尾窗零命中），有
  // `openConvId` 时继续走 keyset 增量 `syncConversation`（`after=水位`，M1③ 红线）。
  // 分派收口到 incrementalResync（clientconn item 2）：与唤醒面同一实现。
  await incrementalResync();
}

// ── 回前台 / 回网即增量拉（clientconn item 2 的正解落点）────────────────
// 唤醒源 = ws.js 的 `fm-wake`（visibilitychange→visible / online 两个监听器广播）。
// 与 K-4 的分工：K-4 是「通道失效时的 10s beacon 兜底」（后台时 beacon 也被挂起，
// 覆盖不到唤醒窗口）；本支路是「用户回到前台/网络刚恢复」这一刻的**确定性**补拉。
// 两态都被覆盖（停在列表态 / 开着会话），且都落在 incrementalResync 同一实现上。
/** 唤醒面节流：连点标签页不该一次翻转打一次请求；3s 既远低于人眼可辨的陈旧阈值，
 *  又高于正常翻转节奏。数据面正确性不依赖它（拉取本身幂等：`after=` 水位过滤 +
 *  `fresh` 去重 ⇒ 重复唤醒不会重复上屏）。 */
const WAKE_THROTTLE_MS = 3000;
let lastWakeAt = 0;

async function wakeResync() {
  if (!loggedIn() || document.hidden) return;
  const now = Date.now();
  if (now - lastWakeAt < WAKE_THROTTLE_MS) return;
  lastWakeAt = now;
  try {
    await incrementalResync({ withList: true });
  } catch (e) {
    // 数据面失败不得冒泡成用户可见错误（与 syncConversation 的 catch 同口径）：
    // 窗口保留已渲染内容，下一次唤醒/beacon 再试。
    console.error('[messages] wake resync failed:', e.message);
  }
}

// ── Wiring ───────────────────────────────────────────────
let initialized = false;

export function initMessages() {
  if (initialized) return;
  initialized = true;

  onMessage('friend_event', onFriendEvent);
  window.addEventListener('fm-refs-sent', onRefsSent);
  // ①opt-A3：挂上既有 10s beacon（返回的注销函数本模块生命周期内不需要 ——
  // initMessages 本身是一次性 latch，整页生命周期只装一次）。
  // 帧静默门控的计时基准在此起点：boot 后 **整整 N 秒**没有任何帧 ⇒ 首拍即核对
  // （不是「boot 立刻额外多一次」——首次 tick 前已过一拍）。
  lastFriendFrameAt = Date.now();
  onStatusTick(() => { void backfillTick(); });
  // clientconn item 2：回前台 / 回网即增量拉（唤醒源 = ws.js 的 `fm-wake`）。
  // 只在登录态 + 前台动作（wakeResync 内部守卫）。
  window.addEventListener('fm-wake', () => { void wakeResync(); });
  // #290: deleting/blocking a friend (contacts panel) flips the open chat
  // into the read-only gate - resync the friend cache and re-apply.
  window.addEventListener('fm-friends-changed', async () => {
    await refreshConversations({ friends: 'force' });
    if (modalEls) applyBlockState(currentConv());
  });
  // 信任开关在 contacts 右键菜单——开着的聊天窗头指示随之刷新。
  window.addEventListener('fm-trust-changed', () => {
    if (modalEls) updateTrustBadge(currentConv());
  });
  // ⑦ 备注改动（contacts 行内编辑提交 / 清除）——会话列表行与开着的聊天窗头
  // 标题面同步（三处显示优先级「备注 > 显示名」；server 端 remark 落到
  // conversations 的 friend 档案是下一次 refresh 的事，本地先就地更新）。
  window.addEventListener('fm-remark-changed', (e) => {
    const d = /** @type {CustomEvent<{userId?: string, remark?: string|null}>} */ (e).detail || {};
    if (!d.userId) return;
    const remark = d.remark || null;
    for (const c of conversations) if (c.friend && c.friend.userId === d.userId) c.friend.remark = remark;
    for (const f of friendsCache) if (f.userId === d.userId) f.remark = remark;
    renderList();
    updateModalTitle(modalEls ? currentConv() : null);
  });
  // 时制偏好变更 → 会话列表行时间（`.fm-conv-time`）就地重渲染随之刷新。列表行
  // 本身已是 role=option 按钮，**不挂**热区（嵌套可交互元素 = 点击语义冲突）；
  // 打开中的聊天窗消息时间由 refreshAllTimestamps 的 [data-ts-text] 就地刷新覆盖。
  window.addEventListener(TIME_FORMAT_CHANGED, () => { renderList(); });
  // P3 error surface — friendsApi dispatches on auth failure / network error.
  // `{auto:true}`: not a user gesture — one event per failed request, so it
  // must not open an OAuth window per event (repeat guard, 2026-09-15 OIDC
  // fix; the 登录失效卡 stays the visible manual retry surface).
  window.addEventListener('fm-auth-required', () => { openLoginModal({ auto: true }); });
  window.addEventListener('fm-network-error', () => { window.__showToast?.(t('messages.networkError'), 'error'); });
  // 群域变更（建群/退群/解散/踢人/邀请响应/可用性翻面）：群列表重取；建群成功
  // 带 openConversationId ⇒ 列表就绪后直接开群窗（新群必在服务端返回里）。
  window.addEventListener('fm-groups-changed', async (e) => {
    await refreshConversations();
    // 群成员头像九宫格的重算触发点（方案 §3.2.2 唯一现成广播面）：群列表行已随
    // refreshConversations 重打；**开着的群窗**需就地重打窗头头像（成员可能已变）。
    refreshOpenGroupHeaderAvatar();
    const detail = /** @type {CustomEvent<{openConversationId?: string}>} */ (e).detail || {};
    if (detail.openConversationId
      && conversations.some(c => c.conversationId === detail.openConversationId)) {
      openConversation(detail.openConversationId, null);
    }
  });
  onReconnect(() => {
    if (modalEls) {
      modalEls.offline.hidden = true;
      applyBlockState(currentConv());
    }
    refreshConversations({ friends: 'force' }); // resync after reconnect (REST is truth)
  });
  onDisconnect(() => {
    if (modalEls) {
      modalEls.offline.hidden = false;
      modalEls.input.disabled = true;
      syncComposerSend(); // 输入框已禁用 ⇒ 发送键同闸禁用（判据单源）
    }
  });

  const panel = document.getElementById('panel-messages');
  if (panel) {
    new MutationObserver(() => {
      // ⑨ M1②：重复开面板 = 1 次往返（好友列表命中 L1 且在 TTL 内即复用；
      // 过期/为空由 refreshConversations 自动升级为 force）。
      if (panel.classList.contains('active')) refreshConversations();
    }).observe(panel, { attributes: true, attributeFilter: ['class'] });
  }

  // ── 设备面（MVP-1）：数据面订阅 ────────────────────────────────────────
  // ① 设备腿消息变更（`dropbox-message` / `dropbox-history` / 传输态 / 闸位提示）——
  //    通知源 = dropbox.js 的 `afterDeviceMessageChange` 单点（不在本模块重挂 WS 帧
  //    监听：否则新旧两窗各消费一次 ⇒ 两套时序判断）。
  onDeviceMessageChange((deviceId) => { onDeviceMessageChanged(deviceId); });
  // ② 在线态推送（O10）：唯一推送源 = `/api/neblink/status` 落地拍（WS `peerListChanged`
  //    已在其上游汇流）⇒ 开着的设备窗副行徽章就地刷新。联系人面板设备段由 contacts.js
  //    自行订阅同一源（禁第二份轮询）。
  onNeblinkStatus(() => { refreshOpenDevicePresence(); });

  renderList();
  if (loggedIn()) refreshConversations();
}

/** 联系人面板好友行入口 (§2.3 ②): open (or create) the chat with a friend. */
export async function openChatWithFriend(friend) {
  let conv = conversations.find(c => c.friend && c.friend.userId === friend.userId);
  if (!conv) {
    conv = { conversationId: null, friend, lastMessage: null, unreadCount: 0 };
    conversations.unshift(conv);
    renderList();
  }
  if (!conv.conversationId) {
    // No conversation yet (never messaged): open an empty modal; the first
    // send creates the conversation server-side (friend-addressed send).
    triggeringRow = null;
    triggeringConvId = null;
    openConvId = '__pending__' + friend.userId;
    renderChatModal(conv);
    if (modalEls) modalEls.input.focus();
    return;
  }
  openConversation(conv.conversationId, null);
}

// ══════════════════════════════════════════════════════════════════════════
// 设备会话统一批 MVP-1（2026-09-15）：设备会话面（客户端统一）
//
// 设计卡 §6.2/§6.3 的落点 —— 设备会话窗**复用本模块的窗骨架与渲染管线单点**
// （`renderChatModal` → `renderMessages` → `keyedDiff` → `bubbleEl` → `fillBubble`
// → `attachmentCard` / `renderFlowStatus` / `preserveScrollAnchor` / `timeFormat`），
// 差异只剩**一层数据源适配**（§6.3 D1/D10）与两处设备专有项（附件发送入口 D3、
// 文件卡传输态 D4）。
// 🔴 禁复制 `dropbox.js:543/487/464/564` 的平行管线（否则 = 卡点名的「第三实现」）；
//    设备腿**数据面**仍由 dropbox.js 唯一属主，本模块只消费其访问器。
// 🔴 本段不触碰转发链禁改面（`:21-23` 的 `forwardBubble/forwardToAgent/makeReference/
//    appendRefToActiveView/notifyFriendRefsSent/onRefsSent/stampForwarded/sendWs`）
//    —— 设备面**调用**既有入口 + 入参映射，零新增实现。
// ══════════════════════════════════════════════════════════════════════════

/** 设备会话 id 域：`dev:<deviceId>`（确定性 ⇒ 幂等；与好友/群 id 不同域）。 */
export const DEVICE_CONV_PREFIX = 'dev:';

/** 设备工作集（本模块持有的适配后消息数组；与 `chatMsgs` 同构，但按设备分开）。 */
let deviceMsgs = [];

/** 设备行去重（卡 O12/P9）：`peers` 里同一 `deviceId` 可能出现多行（上游缺口
 *  board #9/#10）⇒ **渲染层去重**，保首条（先到者 = 状态面板同源的那条）。 */
function dedupeDevices(peers) {
  const out = [];
  const seen = new Set();
  for (const d of peers || []) {
    const id = d && d.deviceId ? String(d.deviceId) : '';
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(d);
  }
  return out;
}

/** 设备显示名（**唯一实现**，与设置面板同口径）：用户描述 > 设备名 > 占位文案。
 *   · 面板行（默认形态）：占位文案（🔴 不回落 `deviceId`）；
 *   · **设备窗窗头**（`opts.forWindowTitle`）：占位文案（🔴 不回落 `deviceId`）。
 *  🔴 U3（root 2026-09-15 #600）：无名称**且**无描述的设备显示占位文案
 *  （「未命名设备」）而非 id —— 与作者 ⑤「不显示设备码」同族精神。占位文案走既有
 *  UI i18n 通道（`t()`；键 `neblink.unknownDevice` 的 en/zh **配对已在库**，零新键、
 *  零第二语言真源）。
 *  🔴 devpanelname 扩展（作者 2026-09-15 22:27 卡答「那兜底就用未命名设备呗」）：
 *  原默认形态的 `deviceId` 兜底已**摘除** ⇒ 三处消费者（消息面板设备行 / 联系人面板
 *  设备行 / 引用载荷 `friendName`）与窗头**同键同文**；界面上任何位置不再裸显设备码。
 *  ⇒ 窗头支（`forWindowTitle`）与默认形态**同值**：该支**保留**（U3 已落行为禁回改，
 *  且两支同值 ⇒ 窗头读数逐字不变）；`opts` 形参保留以维持既有调用面。 */
export function deviceLabel(d, opts) {
  if (!d) return '';
  const named = d.userDescription || d.deviceName;
  if (named) return named;
  if (opts && opts.forWindowTitle) return t('neblink.unknownDevice');
  return t('neblink.unknownDevice');
}

/** 设备窗副行 = **平台标签**（⑤a，作者 2026-09-15：「一是不要显示设备码」）。
 *  🔴 原实现 = `[平台, deviceId].join(' · ')`（设备码可见）⇒ **deviceId 已摘除**；
 *  平台标签仍走 `platformDisplay` 单点（禁第二份映射）。
 *  ⚠ 面板行的 `deviceId` **不在本令指涉面内**（作者只提对话框）⇒ 未改，列开放项。 */
function deviceSubLabel(d) {
  if (!d) return '';
  return platformDisplay(d.platform).text || '';
}

/** 本账号**自有设备**（去重后）。数据源 = `getNeblinkState().peers`（不是好友关系域：
 *  设备无好友语义、无服务端 presence 面，卡 §2 A1）。未登录 ⇒ 空集。 */
export function devicePeers() {
  const rel = getNeblinkState();
  if (!rel || !rel.loggedIn) return [];
  return dedupeDevices(rel.peers);
}

/** 本机设备 id（MVP-2 方向重算 P2 的**比对基准**）。
 *  数据源 = `getNeblinkState().device.deviceId`（与 `devicePeers` 同一 state 单点，
 *  禁另开第二份身份面）。未登录 / 字段缺席 ⇒ `''`（此时**没有**比对基准，见
 *  `adaptDeviceMessage` 的降级档）。 */
function selfDeviceId() {
  const st = getNeblinkState();
  const id = st && st.device ? st.device.deviceId : null;
  return (id === undefined || id === null) ? '' : String(id);
}

/** 服务端附件行 → 附件卡形态（MVP-2 附件第三分支 · 契约 §8.8）。
 *  wire = `AttachmentDto`（跨仓 `src/model.rs:678`：id/name/size/mime?/sha256/state），
 *  与好友附件**同一张卡**（`attachmentCard` 单点渲染，禁第二套卡）；`state` 直通
 *  ——三态 `uploading|ready|expired` 的语义真源在服务端（§B.7），本层不改写。
 *  设备会话附件的取字节路径 = 既有 `api.downloadAttachment`（E3 成员闸，§8.8：
  * peer 可放 `dev:<deviceId>`），故 id 原样透传即可，**零新增下载面**。 */
function serverAttachmentAsCard(a) {
  if (!a || typeof a !== 'object') return null;
  const id = a.id === undefined || a.id === null ? '' : String(a.id);
  return {
    id,
    name: a.name || '',
    size: Number(a.size) || 0,
    mime: a.mime || undefined,
    sha256: a.sha256 || '',
    // 无 id = 不可判读条目（服务端网关侧已把坏条目折成 `state:''` 的安全缺省）
    state: id ? (a.state || '') : 'expired',
  };
}

/** 设备消息 → 本模块气泡形态（§6.3 D1/D10 **唯一**映射点；MVP-2 扩为**双源**）。
 *
 *  🔴 判源 = `senderDeviceId` 键（契约 §8.3 逐字：「仅 device 非 NULL」「直聊/群聊
 *  行无该键」）——只有服务端行携带它，legacy 网关 `DropboxMessage` 永不带。
 *  🔴 方向重算（P2，契约 §8.3 逐字：「方向（out/in）由 `sender_device_id == 本机 id`
 *  重算，不落库」）：服务端行 `ours = senderDeviceId === 本机`；legacy 行回落
 *  `direction === 'out'`（网关本已按本机视角判定）。
 *  映射：`msgId→id`（legacy）/ `text→body`（legacy）/ `ts→createdAtMs`
 *  （`toEpochMs` 直通，卡 1.3-9）/ `direction==='out'→ours`（legacy）。
 *  节点身份键统一走 `messageId`（`bubbleEl` 已用 `dataset.messageId` ⇒ 与好友/群同键）。
 *  @param {any} m 服务端 MessageDto（`senderDeviceId` 在场）或 legacy DropboxMessage
 *  @returns {{id: string, body: string, createdAt: number, createdAtMs: number, origin: string, ours: boolean, attachments: any[]}} */
export function adaptDeviceMessage(m) {
  if (!m || typeof m !== 'object') {
    return { id: '', body: '', createdAt: 0, createdAtMs: 0, origin: 'user', ours: false, attachments: [] };
  }
  const isServer = m.senderDeviceId !== undefined && m.senderDeviceId !== null;
  // 方向（P2）：有发送设备 id ⇒ 用它比对本机；没有 ⇒ 无该证据，回落 legacy 字段。
  // 🔴 `selfDeviceId()` 为空串（未登录/字段缺席）时**不**把一切判成「本机所发」：
  // 那会把对端消息全部画到右侧。此时无证据 ⇒ 保守落「in」（与好友面
  // `resolveOut` 的「两源皆缺席 ⇒ in」同一条纪律，P5 同源）。
  const mine = selfDeviceId();
  const ours = isServer
    ? (mine !== '' && String(m.senderDeviceId) === mine)
    : m.direction === 'out';
  const rawId = (m.id !== undefined && m.id !== null) ? m.id : m.msgId;
  const id = (rawId === undefined || rawId === null) ? '' : String(rawId);
  // 时间：`createdAtMs`（服务端，毫秒）直通优先；`createdAt`（秒/ISO）与 `ts`
  // （legacy，毫秒）走 `toEpochMs` 归一 —— 三源一档，禁各写一套猜法。
  const createdRaw = (m.createdAtMs !== undefined && m.createdAtMs !== null) ? m.createdAtMs : m.createdAt;
  const createdAtMs = toEpochMs(createdRaw) || toEpochMs(m.ts) || 0;
  const isFile = m.kind === 'file';
  const serverAtts = Array.isArray(m.attachments) ? m.attachments : [];
  const atts = isServer
    ? serverAtts.map(serverAttachmentAsCard).filter(Boolean)
    : (isFile ? [deviceFileAsAttachment(m, ours)] : []);
  return {
    id,
    // 文件消息不占气泡正文（文件卡承载）；notice 走 text 正文（既有闸位提示形态）。
    body: !isServer && isFile ? '' : (m.body !== undefined && m.body !== null ? String(m.body) : (m.text || '')),
    // 内部形态键 `createdAt` 供渲染管线（`toEpochMs`/`fmtTime` 单点）；
    // `createdAtMs` 为同值的毫秒别名（§6.3 D1 契约名 + 转发链入参同源）。
    createdAt: createdAtMs,
    createdAtMs,
    // 来源面（卡 P3）：收端**提示级**渲染（wire `origin` 未经服务端强制，不构成
    // 「agent 发」的判定依据 —— 徽标判据 `isAgentSent` 语义不变，仅提示）。
    origin: m.origin === 'agent' ? 'agent' : 'user',
    ours,
    attachments: atts,
  };
}

/** 设备文件消息 → 附件卡形态（卡 D4）：适配 `attachmentCard` 的 `device` 态，
 *  传输态（status/savedPath/进度）挂在气泡状态位。 */
function deviceFileAsAttachment(m, ours) {
  const total = Number(m.totalBytes) || Number(m.fileSize) || 0;
  const got = Number(m.bytesReceived) || Number(m.downloadedBytes) || 0;
  const pct = (total > 0 && got > 0 && m.status !== 'completed') ? Math.floor((got / total) * 100) : null;
  return {
    id: '',
    name: m.fileName || '',
    size: Number(m.fileSize) || 0,
    state: 'device',
    deviceStatus: m.status || '',
    deviceSavedPath: m.savedPath || '',
    deviceOut: ours,
    devicePct: pct,
  };
}

/** 两条消息里更新的那条（**窗内预览**取最新；判据 = 服务端行 id 单调，回落
 *  `createdAtMs`）。纯函数，禁散落的比较实现（与 `compareDeviceMsg` 同一档判据）。 */
function newerDeviceMsg(a, b) {
  if (!a) return b || null;
  if (!b) return a;
  const na = Number(a.id); const nb = Number(b.id);
  if (Number.isFinite(na) && Number.isFinite(nb)) return nb >= na ? b : a;
  return (Number(b.createdAtMs) || 0) >= (Number(a.createdAtMs) || 0) ? b : a;
}

/** 归并窗内的消息排序（keyset 两路合流；判据同 `newerDeviceMsg`）。 */
function compareDeviceMsg(a, b) {
  const na = Number(a.id); const nb = Number(b.id);
  if (Number.isFinite(na) && Number.isFinite(nb)) return na - nb;
  return (Number(a.createdAtMs) || 0) - (Number(b.createdAtMs) || 0);
}

/** 设备会话行（合并进 `conversations`；范本 = 群行 `convRow`）。
 *
 *  MVP-2（2026-09-15）：**服务端行优先 + 按 peer 归并两行**（契约 §9.3 + §8.1）。
 *  服务端设备会话以**发送设备**为键（`dev:<senderDeviceId>`；`sender_device_id`
 *  由会话 id 派生 ⇒ **一条会话内方向恒定**，服务端自测 `devicesession_test.rs:377-446`），
 *  故一次双向对聊落**两行**：`dev:<本机>` = 我发出的、`dev:<对端>` = 对端发来的。
 *  🔴 本函数把**同一个 peer 的两行读成一条窗**：
 *    · 本机自己那一行**不单独成窗**（否则一次对聊裂成两窗，且本机窗名退化成裸 id）；
 *    · 窗 id = 对端那一行（`dev:<对端>`；contacts 面板点设备即开此窗）；
 *    · `serverRows` = 参与本窗的服务端行（对端行 + 本机行）——取数/已读/回执都
 *      按它**逐行**打（禁把两行当一行打）；
 *    · 方向**逐条按 `senderDeviceId` 重算**（`adaptDeviceMessage` 单点）⇒ 窗内天然并呈；
 *    · 未读 = 各行 `unreadCount` **之和**（本机行恒 0，§8.1「发送设备自己 0」）。
 *  peers 派生行只补「该设备与它自己那一行都没有服务端行」的空缺（旧网关 / 新设备
 *  未通信 ⇒ legacy dropbox 腿，判红④的降级展示）。
 *  🔴 本机 device id 缺席（未登录 / 字段缺席）⇒ **无法判定哪一行是本机行**：不归并
 *  （每行各自成窗、方向保守落 in），且该窗的发送面**可见禁用**（见 `applyBlockState`）。
 *  🔴 已知后果（登记项，非缺陷）：账号只剩本机一台设备（无 peers）时，本机那一行
 *  **不成窗** ⇒ 它承载的「我发出的」消息在 UI 上无窗可入（对齐 §9.3：本机行只作为
 *  对端窗的一半存在）。
 *  §9.7：设备显示名**不**在服务端行里（`title` 恒 NULL）⇒ 名字一律由 `peers` 单点提供。
 *  @param {any[]} serverRows `api.getConversations()` 原始行（已含设备行）
 *  @returns {any[]} */
function deviceConvs(serverRows) {
  const selfId = selfDeviceId();
  // ① 服务端设备行：按**发送设备**分桶（`kind` 键判别，不猜前缀 §8.1）
  const rows = [];
  for (const row of serverRows || []) {
    if (!row || row.kind !== 'device') continue;
    const did = row.deviceId || String(row.conversationId || '').slice(DEVICE_CONV_PREFIX.length);
    if (!did) continue;
    rows.push({
      conversationId: row.conversationId || (DEVICE_CONV_PREFIX + did),
      deviceId: did,
      unreadCount: Number(row.unreadCount) || 0,
      lastMessage: row.lastMessage ? adaptDeviceMessage(row.lastMessage) : null,
    });
  }
  // 本机那一行 = 归并用的公共半边；无本机身份 ⇒ 不识别（不归并）
  const selfRow = selfId ? (rows.find(r => r.deviceId === selfId) || null) : null;
  const peerRows = selfId ? rows.filter(r => r.deviceId !== selfId) : rows;
  const peers = devicePeers();
  const byId = new Map();
  const windowOf = (did) => {
    let w = byId.get(did);
    if (!w) {
      w = {
        conversationId: DEVICE_CONV_PREFIX + did,
        kind: 'device',
        device: null, // peers 段回填（名字/平台/在线态单点在 peers）
        serverRows: [], // 参与本窗的服务端行（对端行 + 本机行）
        sourceServer: false, // 数据面选路判据（有服务端行才走服务端取数腿）
        unreadCount: 0,
        lastMessage: null,
      };
      byId.set(did, w);
    }
    return w;
  };
  // ② 窗集合 = 服务端「非本机」行 ∪ **有通信证据**的 peers
  //    （🔴 本机那一行**不是窗**，见函数注释）
  // ⑥（作者 2026-09-15）：「而且要跟设备通信过再出现在消息面板里呀，不要直接出现」
  //   ⇒ peers 派生窗加**通信证据闸**：仅当该设备有通信证据才建窗。
  //   证据判据（两档，与 forensic §2-⑥ 给出的候选一致）：
  //     · 服务端会话行在场（= 有服务端行 ⇒ 该设备收/发过；对方先发、我方从未回也算）；
  //     · 本地消息缓存非空（legacy 腿：`deviceHasLocalTraffic` —— 内存工作集 ∨ L2
  //       缓存，判据唯一实现在 dropbox.js 的设备数据面）。
  //   🔴 无证据 ⇒ **不建窗**（peers 档案仍保留给联系人面板的设备段使用 —— 该面板
  //   直读 `devicePeers()`，不经本函数）。
  for (const r of peerRows) windowOf(r.deviceId);
  for (const d of peers) {
    const did = d && d.deviceId ? String(d.deviceId) : '';
    if (!did) continue;
    if (selfId && did === selfId) continue; // 防御：peers 已剔本机
    if (!byId.has(did) && !deviceHasLocalTraffic(did)) continue; // ⑥ 通信证据闸
    const w = windowOf(did);
    if (!w.device) w.device = d; // 名字/平台/在线态：peers 单点（§9.7 禁服务端名字快照）
  }
  // ③ 服务端行归属：对端行 + 本机行（本机行并入**每一条**窗）
  for (const [did, w] of byId) {
    w.serverRows = rows.filter(r => r.deviceId === did);
    if (selfRow) w.serverRows.push(selfRow);
    if (w.serverRows.length) {
      w.sourceServer = true;
      let sum = 0;
      let latest = null;
      for (const r of w.serverRows) {
        sum += r.unreadCount;
        latest = newerDeviceMsg(latest, r.lastMessage);
      }
      w.unreadCount = sum;
      w.lastMessage = latest;
    } else {
      // D5：无服务端行 ⇒ 无服务端未读可读 ⇒ 恒 0（不是假装算过）；预览取本地缓存
      // ⑥：本地缓存的**唯一入口**是 `hydrateDeviceCache`（L2 → 内存工作集）——先灌
      // 再读，否则「上次会话聊过、本次未开窗」的设备行会显示空预览（看着像没聊过）。
      hydrateDeviceCache(did);
      const raw = deviceMessagesOf(did);
      const last = raw.length ? raw[raw.length - 1] : null;
      w.lastMessage = last ? adaptDeviceMessage(last) : null;
    }
  }
  // ④ 孤儿服务端行 / 不在 peers 的设备：显示名降级为 id，不静默消失。
  for (const w of byId.values()) {
    if (!w.device) w.device = { deviceId: String(w.conversationId).slice(DEVICE_CONV_PREFIX.length) };
  }
  return [...byId.values()];
}

/** 设备工作集 → 本模块消息数组（**顺序即帧/缓存顺序**：网关按 ts 升序给出全量）。 */
function syncDeviceMsgs(deviceId) {
  const raw = deviceMessagesOf(deviceId);
  const next = [];
  for (const m of raw) {
    if (!m || m.msgId === undefined || m.msgId === null) continue;
    next.push(adaptDeviceMessage(m));
  }
  deviceMsgs = next;
}

// ── MVP-2 设备会话切服务端数据源（2026-09-15）─────────────────────────
// 数据面选路判据 = 会话行的 `sourceServer`（`deviceConvs` 单点给出：**真服务端行**
// 才走服务端腿；peers 派生行 = 无服务端行 ⇒ 走 legacy dropbox 腿，判红④的降级展示）。
// 🔴 取数/已读/回执/发送**四条**都在同一实现面上落地（任务书⑤：与②同一实现面，
// 禁各自演化）——都只消费 `sourceServer` 一个判据。

/** 设备回执（会话 id → {lastReadMessageId, lastSentMessageId}）。渲染期派生态，
 *  不进缓存（与好友面 `markOurs` 同一条「派生态不落盘」纪律）。
 *  🔴 键 = **本机所发那一行** `dev:<本机>`：服务端 `device_conversation_receipts`
 *  只回 `sender_device_id = 请求设备` 的行（`store.rs:7121-7146`）⇒ 我的回执只在
 *  我自己的会话行上，对端那一行恒空（禁按窗 id 乱打）。 */
const deviceReceipts = new Map();

/** 本机所属的会话行 id（= 我发出的消息所在的那一行；无本机身份 ⇒ 空串）。 */
function selfConversationId() {
  const s = selfDeviceId();
  return s ? DEVICE_CONV_PREFIX + s : '';
}

/** 本机身份缺席时设备会话的**可见禁用**判据（`applyBlockState` / 发送面共用，
 *  判据单源）。服务端腿的发送身份必须是本机 device id（§8.6 路径段 = 发送设备 +
 *  硬闸 `credential_device == device_id`，`friends.rs:1305-1320`）⇒ 身份缺席时
 *  发送**必然**失败，此时按「可见禁用」处理，禁静默打对端、禁静默失败。 */
function deviceSendBlocked(conv) {
  return !!(conv && conv.kind === 'device' && conv.sourceServer === true && !selfDeviceId());
}

/** 气泡 id 的回执态（`'read' | 'sent' | null`）。判据 = 契约 §8.7 的**高水位**
 *  语义：`id <= lastReadMessageId` ⇒ read（终态）；否则 `id <= lastSentMessageId`
 *  ⇒ sent；无回执数据 ⇒ null（不画假状态）。 */
function deviceReceiptStateOf(id) {
  const st = deviceReceipts.get(selfConversationId());
  if (!st) return null;
  const n = Number(id);
  if (!Number.isFinite(n) || n <= 0) return null;
  if (st.lastReadMessageId && n <= st.lastReadMessageId) return 'read';
  if (st.lastSentMessageId && n <= st.lastSentMessageId) return 'sent';
  return null;
}

/** 回执面刷新（写 → 读往返的**读**半程）：拉服务端 `GET …/receipts`（契约 §8.7，
 *  设备会话读自 `device_message_receipts`）⇒ 就地补画/撤画气泡状态位。
 *  🔴 读的是**本机所发那一行**（`selfConversationId()`）：回执行的身份键是
 *  「发送设备 = 请求设备」，对端行上恒无可读回执。
 *  🔴 失败静默容忍（回执是**增强**信息，不是消息本体的承重面）；不重试（避免
 *  「持续无输出」式循环）。 */
async function refreshDeviceReceipts(conv) {
  if (!conv || !conv.sourceServer) return;
  const selfConvId = selfConversationId();
  if (!selfConvId) return; // 无本机身份 ⇒ 无「我发出的」面可读，不画假状态
  try {
    const r = await api.getConversationReceipts(selfConvId);
    deviceReceipts.set(selfConvId, {
      lastReadMessageId: Number(r && r.lastReadMessageId) || 0,
      lastSentMessageId: Number(r && r.lastSentMessageId) || 0,
    });
  } catch { /* keep last known */ }
  applyDeviceReceipts(conv);
}

/** 把回执态就地补画到已渲染的**本机所发**气泡上（只碰设备窗内节点）。
 *  不走重渲染（`renderMessages` 会重建全窗）——回执到达不移动任何气泡。 */
function applyDeviceReceipts(conv) {
  if (!modalEls || !modalEls.conv || modalEls.conv.kind !== 'device') return;
  if (openConvId !== (conv && conv.conversationId)) return;
  for (const wrap of modalEls.flow.querySelectorAll('.fm-msg.out')) {
    let chip = wrap.querySelector('.fm-msg-device-receipt');
    const state = deviceReceiptStateOf(wrap.dataset.messageId);
    if (!state) { if (chip) chip.remove(); continue; }
    if (!chip) {
      chip = el('span', 'fm-msg-device-receipt');
      const meta = wrap.querySelector('.fm-msg-meta');
      if (!meta) continue;
      meta.appendChild(chip);
    }
    const text = state === 'read' ? t('messages.deviceRead') : t('messages.deviceSent');
    if (chip.textContent !== text) chip.textContent = text;
    chip.dataset.receiptState = state;
  }
}

/** 设备会话**已读上报**（MVP-2：`POST /api/conversations/{id}/read`，契约 §8.7
 *  设备分支写 `device_read_cursors` + 由它派生 `device_message_receipts` 的
 *  `read` 回执）。与好友面 `markConvRead` 同一形态（窗口开着 ⇒ 即已读）。
 *  🔴 **按窗内每条服务端行逐行上报**（§9.3 归并窗的窗语义）：本机那一行恒 0
 *  未读（§8.1「发送设备自己 0」）⇒ 只上报**对端行**，各自带**该行自己的**
 *  末条消息 id（两行的 id 空间不同，禁混用）。
 *  🔴 **不改本地 cursor 台账**——设备维度未读是服务端权威，本地 `read_cursors`
 *  是 friend 域水位（服务端逐字告警：设备消息 id 会推进 friend 域水位、抑制
 *  S1 好友唤醒 —— 设计卡 §5.5）。 */
function markDeviceConvRead(conv) {
  if (!conv || !conv.sourceServer) return; // 无服务端行 ⇒ 无可上报的游标面
  const selfId = selfDeviceId();
  for (const row of conv.serverRows || []) {
    if (selfId && row.deviceId === selfId) continue; // 本机行恒 0 未读，无上报面
    const last = Number(row.lastMessage && row.lastMessage.id) || 0;
    if (!last) continue;
    api.markConversationRead(row.conversationId, last).catch(() => {});
  }
  conv.unreadCount = 0;
  // 乐观翻面：本窗内「别人发来的」未读不再显示（服务端读数回来时以它为准）。
  updateBadge();
  renderList();
  void refreshDeviceReceipts(conv);
}

/** 设备会话服务端取数（keyset 尾窗，D9：MVP-2 由「网关全量」转 keyset）。
 *  🔴 **归并窗 = 两条会话各自取数后按 id 归并**（§9.3）：对端行给出「对端发来的」、
 *  本机行给出「我发出的」，两路都在同一实现面取（禁各自演化）。
 *  任一路取数失败 ⇒ 整窗降级 legacy dropbox 腿（可见提示），不半窗呈现。 */
async function fetchDeviceMsgsServer(conv) {
  const rows = (conv && conv.serverRows) || [];
  const parts = [];
  try {
    for (const row of rows) {
      const anchor = Number(row.lastMessage && row.lastMessage.id);
      const after = Number.isFinite(anchor) && anchor > HISTORY_WINDOW ? anchor - HISTORY_WINDOW : 0;
      const msgs = await api.getMessages(row.conversationId, { after, limit: HISTORY_WINDOW });
      for (const m of msgs || []) parts.push(m);
    }
  } catch (err) {
    // 判红④：服务端面不可达（404 neblinkOff / 未登录 / 网络）⇒ **可见降级**，
    // 落 legacy dropbox 腿，绝不留白窗、绝不抛错崩窗。
    syncDeviceMsgs(conv.device.deviceId);
    deviceMsgs = deviceMsgs.map(m => ({ ...m }));
    if (err && (err.status === 404 || err.status === 403 || err.status === 401)) {
      modalToast(t('messages.deviceServerUnavailable'));
    }
    return;
  }
  if (openConvId !== conv.conversationId) return; // 窗已被替换
  const next = [];
  for (const m of parts) {
    const a = adaptDeviceMessage(m);
    if (!a.id) continue; // 无 id 的行不可定位（keyedDiff 需要 id），跳过并留待重取
    next.push(a);
  }
  next.sort(compareDeviceMsg);
  deviceMsgs = next;
}

/** 设备面发送（人发）。与好友面**同一形态**（trim 闸 → 清空 → 发送键回禁用态）。
 *  MVP-2：服务端行走 `POST /api/devices/{id}/messages`（契约 §8.6，幂等键
 *  `clientMsgId`）；legacy 腿仍走 `dropbox-send-text`（D3：设备文件发送入口保留）。
 *  🔴 **路径段 = 本机 device id（发送设备）**，不是对端：服务端该路由的 `{device_id}`
 *  语义逐字为「The path carries the SENDING device」（`friends.rs:1288-1291`）且硬闸
 *  `credential_device == device_id` 否则 `403 not_my_device`（`:1305-1320`）；网关凭据
 *  设备恒为本机（`NeblinkEnrollment.scala:116` → `NeblinkClient.scala:347` 登录体）。
 *  会话 id 也由它决定（`conversationId = dev:<senderDeviceId>`）⇒ 我发出的消息落
 *  `dev:<本机>`，与 §9.3 的「两行归并成一窗」自洽。
 *  🔴 本机 device id 缺席 ⇒ **可见禁用**（`applyBlockState` 已禁用输入框 + 只读栏；
 *  本函数再守一道）——绝不把消息打到对端 id（那必然 403）、绝不静默失败。
 *  🔴 回显不做本地乐观气泡——服务端行以 keyset 重取为唯一事实源（同 `clientMsgId`
 *  重复发送是幂等回放，§8.6「201 either way」，不产生第二行）。 */
async function sendDeviceCurrent(conv) {
  if (!modalEls || !conv || !conv.device) return;
  const body = modalEls.input.value.trim();
  if (!body || body.length > 2000) return; // D8：与好友窗同闸（2000，服务端无 enforcement）
  const selfId = selfDeviceId();
  if (conv.sourceServer && !selfId) {
    modalToast(t('messages.deviceSendUnavailable'));
    return; // 内容留在输入框（输入框此刻是禁用态，正常路径到不了这里）
  }
  modalEls.input.value = '';
  syncComposerSend();
  if (!conv.sourceServer) { sendDeviceText(conv.device.deviceId, body); return; }
  try {
    await api.sendDeviceMessage(selfId, body);
    await fetchDeviceMsgsServer(conv);
    if (openConvId !== conv.conversationId) return;
    renderMessages(deviceMsgs, { stickBottom: true });
    conv.lastMessage = deviceMsgs[deviceMsgs.length - 1] || conv.lastMessage;
    renderList();
    void refreshDeviceReceipts(conv);
  } catch {
    // 可见失败（正文不丢）：回填输入框 + 就地提示，与好友面失败面同语义。
    modalEls.input.value = body;
    syncComposerSend();
    modalToast(t('messages.deviceSendFailed'));
  }
}

/** 在线态推送到达 ⇒ 开着的设备窗副行徽章就地刷新（不整窗重建）。 */
function refreshOpenDevicePresence() {
  if (!modalEls || !modalEls.conv || modalEls.conv.kind !== 'device') return;
  const conv = modalEls.conv;
  const fresh = devicePeers().find(d => d.deviceId === conv.device.deviceId);
  if (fresh) conv.device = fresh;
  const sub = modalEls.overlay.querySelector('.fm-modal-id');
  if (sub) sub.textContent = deviceSubLabel(conv.device);
  const slot = modalEls.overlay.querySelector('.fm-modal-presence');
  if (slot) {
    const badge = presenceBadgeHTML({ ...(conv.device || {}), isLocal: false });
    slot.innerHTML = badge;
  }
}

/** 设备腿消息变更（dropbox.js 单点通知）：会话列表设备行 + 开着的设备窗。
 *  🔴 只作用于 **legacy 腿**（`sourceServer` 假 = 数据面在 dropbox.js）：服务端腿的
 *  新消息到达由 `refreshConversations` 的会话行刷新承载（未读/预览权威在服务端，
 *  契约 §8.1），本通知不重复拉服务端（禁双源同时推同一窗口）。 */
function onDeviceMessageChanged(deviceId) {
  // ⑥ 反向半程（「通信过**之后**才出现」）：设备首次与本机通信后并入消息面板。
  // 判据面复用**唯一建窗实现** `deviceConvs`（不在此另造窗构造逻辑）——该设备此刻
  // 本地缓存已非空 ⇒ 通信证据成立 ⇒ 下一次 `refreshConversations` 即把它读进列表。
  // 仅在「当前窗集合里还没有它」时补一次重取（有行者 = 已在列表 ⇒ 零额外请求）。
  const convId = DEVICE_CONV_PREFIX + deviceId;
  if (!conversations.some(c => c && c.conversationId === convId)) {
    void refreshConversations();
  }
  renderList(); // 设备行预览/时间（legacy 数据全在内存 ⇒ 零额外请求）
  const conv = currentConv();
  if (conv && conv.sourceServer) return; // 服务端腿：不在 dropbox 通知面上刷新
  if (!modalEls || openConvId !== DEVICE_CONV_PREFIX + deviceId) return;
  if (!conv || conv.kind !== 'device') return;
  syncDeviceMsgs(deviceId);
  // 阅读位（卡 §6.2 #5）：用户在读旧内容时不被推走；已在底部 ⇒ 跟随新消息。
  const flow = modalEls.flow;
  const atBottom = flow.scrollTop + flow.clientHeight >= flow.scrollHeight - 4;
  renderMessages(deviceMsgs, { stickBottom: atBottom });
}

/** 设备会话开窗（与 `openConversation` 同构；MVP-2 起按 `sourceServer` 选数据面）。
 *  · `sourceServer`（服务端行）：keyset 尾窗 + 已读上报 + 回执读面（§8.5/§8.7）。
 *  · 否则（peers 派生行 / 旧网关）：legacy dropbox 腿原样保留 —— 网关本机
 *    `~/.nebflow/dropbox/messages.json` 的全量帧（判红④的降级展示面）。 */
async function openDeviceConversation(conv, rowEl) {
  triggeringRow = rowEl || null;
  triggeringConvId = conv.conversationId;
  openConvId = conv.conversationId;
  renderChatModal(conv);
  if (conv.sourceServer) {
    deviceMsgs = [];
    showFlowLoading(); // 冷启动可见加载态（与好友面冷路径同一档，不用转圈掩盖慢）
    await fetchDeviceMsgsServer(conv);
    if (openConvId !== conv.conversationId) return;
    renderMessages(deviceMsgs);
    markDeviceConvRead(conv); // 窗开着 ⇒ 已读（同时触发回执读面刷新）
    if (modalEls) modalEls.input.focus();
    return;
  }
  const deviceId = conv.device.deviceId;
  // 顺序即契约（与旧窗 `openDropbox` 同）：本地缓存渲染先于出帧（stale-while-revalidate）。
  const hydrated = hydrateDeviceCache(deviceId);
  syncDeviceMsgs(deviceId);
  renderMessages(deviceMsgs);
  if (!hydrated && deviceHistoryPending(deviceId)) showFlowLoading(); // 无缓存 ⇒ 可见加载态
  requestDeviceHistory(deviceId);
  if (modalEls) modalEls.input.focus();
}

/** 设备行/联系人设备段的共用开窗入口（联系人面板与消息面板都调它）。 */
export function openDeviceChat(device) {
  if (!device || !device.deviceId) return;
  const convId = DEVICE_CONV_PREFIX + device.deviceId;
  let conv = conversations.find(c => c.conversationId === convId);
  if (conv) {
    conv.device = device; // presence/描述就地更新（对象引用复用）
  } else {
    // ⑥（作者 2026-09-15）：**可开窗，但不"直接出现"在消息面板** —— 原
    // `conversations.unshift(conv)` 已删（那正是「在联系人面板点一下设备 ⇒ 消息面板
    // 立刻多一行零消息行」的路径）。无通信证据的设备此时只开窗；首次通信后由
    // `deviceConvs`（唯一建窗实现，见 `onDeviceMessageChanged` 的补登）把它读进列表。
    // 🔴 与 ⑤c 联动：描述编辑只在对话框内 ⇒ 未通信设备**仍须**能从联系人面板开窗
    // （本函数保留开窗腿；被删的只有「成行」）。
    conv = { conversationId: convId, kind: 'device', device, unreadCount: 0, lastMessage: null };
  }
  renderList();
  openDeviceConversation(conv, null);
}
