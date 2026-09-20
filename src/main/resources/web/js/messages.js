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
import { makeReference, renderRefBlock } from './reference.js';
import { appendRefToActiveView } from './input.js';
import { showPopupMenu, isPopupMenuOpen } from './contextMenu.js';
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
  onDeviceTransfer, // uxconsist Phase B：设备腿**传输生命周期**通知（显示态登记用）
  cancelDeviceTransfer, // 同上：新窗卡上取消键的唯一执行入口（传输链在 dropbox.js 单点）
  deviceHasLocalTraffic, // ⑥：通信证据（本地半程）判据单点
  saveDeviceDescription, // ⑤c：设备描述写路径单点（旧设备窗与本窗共用同一函数）
  outFileHandleOf, // selfattach 片 2（A 腿）：发送侧真句柄只读访问器（属主 = dropbox.js）
} from './dropbox.js';
// 附件预览（作者令 2026-09-15「点击附件要能直接在 canvas 里预览」；作者令 2026-09-17 12:31
// 追加「设备面对齐好友/群」）：附件卡的**唯一**预览入口 = attachmentPreview.js（判据 +
// Canvas 渲染腿都在那边）；本模块只做接线 + 降级文案 + 取字节（禁在此再写第二套类型判据 /
// 第二套取字节路）。`previewLocalPath` 在设备面已降为**回落腿**（票据路由拒绝的类型），
// 与 `previewBlob` 同属该模块的既有出口。
import { previewBlob, previewLocalPath, canPreviewLocalPath, canPreviewName, canFetchLocalBytes, isPreviewOpen } from './attachmentPreview.js';
// 附件**上传**（attachcl 批，作者 2026-09-16 07:36）：好友窗与群窗的发送面**唯一**
// 实现（上传链 + 闸位 + 上传卡渲染都在那边 ⇒ 两面不各写一套）。设备面**不**经此
// （设备腿仍走 dropbox.js 单点，零行为变化）。
import {
  sendFiles, attachAvailable, renderUploadCards, detachUploadCards, clearSettledUploads,
  registerExternalTransfer, updateExternalTransfer, // 设备腿显示态登记口（只共享显示，传输不碰）
} from './attachUpload.js';
// 三面发送状态**统一落码**（uxconsist Phase B，作者 2026-09-20 18:45 三裁批准）：
// 8 态词汇表 + 能力位 + 气泡左侧单一状态槽 + 重试键 + 回显认领（数组参数化）。
// 🔴 本模块是三面的**唯一** UI 分流点（`dispatchSend` / `dispatchFiles`）；`conv.kind`
// 只在适配器选择处出现一次（设计件 §2.2 判据），其余感受面一律读 `PHASE` / 能力位。
import {
  PHASE, SEND_LEG, capabilitiesOf, applyPhase, bindRetry, applyReceipt,
  claim as claimSend, anchorSendToRealId as anchorPend,
  trackPending, forgetPending, resetPending,
} from './sendState.js';
// 头像复用池 + 九宫格内容签名（uifix 批 2026-09-17，「群头像没有被缓存」修复）：
// 唯一入口 = avatarRender.js（判据与池都在那边，本模块只消费）。
import { avatarImgNode, avatarCellsSignature, avatarNodeFor } from './avatarRender.js';
// 本地优先层（sessperf Phase B · 2026-09-20；卡
// `.nebflow/20260920_184900_sessperf-local-first-card__chain-sessperf.md` §4①②③）：
// 唯一属主 = `localStore.js`（IndexedDB）。本模块**只消费**其冻结接口面：
// 首帧走同步读（`readMessages` / `readRoster`，零 await、零网络），写走异步
// （`writeMessages` / `writeRoster`），后台对账挂既有 10s beacon（`reconcile`）。
// 🔴 本地层不可用（L1）⇒ `isLocalStoreEnabled()` 为假 ⇒ 回落 legacy 三层
// （`fmMessageCache` / `fmDropboxCache`）—— 与改前逐字同行为。
import {
  readMessages, readRoster, writeMessages, writeRoster, isLocalStoreEnabled,
  signatureOf, reconcile as reconcileLocalStore,
} from './localStore.js';
// 内联图片附件的取字节/票据面：好友·群面 = 既有鉴权路由（friendsApi），设备面 =
// 既有本机落盘路径的 nf-ticket 链（`ticketUrl`）。两套都是**既有**取数面，
// 本批禁新增第三条取字节路（并禁与另一套附件渲染面混淆）。
import { ticketUrl } from './nfTicket.js';
import { MAX_INLINE_IMAGE_BYTES, isImageAttachmentName } from './attachmentPreview.js';

let conversations = [];
let friendsCache = [];          // accepted friends — source of truth for §3.3 gate
let friendsFetchedAt = 0;       // ⑨ L1 新鲜度锚：好友列表最后一次成功拉取时刻
let openConvId = null;          // conversation shown in the chat modal
let modalEls = null;            // {overlay, flow, input, sendBtn, toast}
let triggeringRow = null;       // for focus return (A18)
let triggeringConvId = null;    // row may be re-rendered after open (unread clear) — refind by id
const forwardedIds = new Set(); // session-persistent 「已转发」 chips (§3.3)
let msgSeq = 0;
// ── msgmenu 一期（作者 2026-09-18 19:2x 四答 = 唯一规格）· 客户端两个新态 ──────
//  `pendingQuoteRef`：引用态（输入框面）当前待发送的引用对象（null = 无引用态）。
//   `selectionMode` + `selectedIds`：多选态 + 已选 messageId（**字符串**归一，
//   与 `keyedDiff` 的节点身份键同口径）。两个态都在 `closeChat()` 归零
//   （「关窗重开选中归零」= 验收判据之一）。🔴 零删除/零撤回语义（本批硬禁）。
let pendingQuoteRef = null;
let selectionMode = false;
const selectedIds = new Set();

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
//
// 📌 uxconsist Phase B：登记表**属主迁到 `sendState.js`**（三面共用一份 —— 好友 / 群 =
// `chatMsgs`，设备 = `deviceMsgs`），读写口 = `trackPending` / `forgetPending` /
// `resetPending` / `claim`。语义逐字不变：本模块只保留「哪个数组是窗口」这一层接线。

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
  // 📌 uxconsist Phase B：换键实现**收敛到 `sendState.js` 的唯一锚定点**（数组参数化：
  // 好友 / 群 = `chatMsgs`，设备 = `deviceMsgs`）——同一份后置条件，禁第二套换键。
  if (!modalEls) return false;
  return anchorPend(p, realId, chatMsgs);
}

/**
 * **回显认领**：把一条**本机所发**的服务端消息认领到本会话未决的乐观项上
 * （「回显先到 · 响应后到」的解 —— 不新增气泡、不新增条目）。
 *
 * 只在**唯一可判**时认领（三条同时成立）：① 调用方已确证这条是本机所发
 * （WS 面判据 = 事件类型 `message_new_self`；REST 面判据 = 服务端记录里的
 * `senderId` **权威且非好友**）② 该 id 尚未归属任何已载入条目 ③ 本会话存在
 * 未锚定、**身份可判**的乐观项 —— 身份判据两条腿，**强键优先**：
 *   · **附件 id 腿（imgmsg 批）**：入帧的 `attachments[].id` ∩ 未决项的本机上传
 *     回执 id。图片乐观面的 `body` 常态为空串（服务端占位正文 ≠ 空串）⇒ 正文判据
 *     在图片路径上结构性失配，必须由这一路兜住（否则回显腿另上一屏 = 重复面）。
 *   · **正文腿（既有 · 逐字不变）**：正文逐字相同的最老者 —— 服务端 id 升序 = 发送序。
 *     多条同正文未决（罕见）取**最老**一条。
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
  // uxconsist Phase B：语义与判据**逐字**同旧实现，只是把消息数组参数化到
  // `chatMsgs`（好友 / 群窗的窗口）并交由 sendState 的单一实现承担。
  return claimSend(chatMsgs, m, { convId, ours });
}

/** 出表（锚定完成 / 发送失败 / 重试换号）：登记表不随发送条数增长。 */
function forgetPendingSend(p) {
  forgetPending(p);
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
  // sessperf Phase B（2026-09-20）：src 解析收口到 `avatarRender.avatarNodeFor`
  // —— 本地层（`localStore.readAvatar`，键 = userId）命中 ⇒ 直接拿 objectURL，
  // **零网络、零重取**；未命中 ⇒ 回落既有解码复用池 + 远端直拉（零回归），且由
  // 本地层低频补字节供下一次开窗命中。判据面（有无 avatarUrl）与改前逐字相同。
  const node = person ? avatarNodeFor(person) : null;
  if (node) {
    a.appendChild(node);
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

/** 会话行摘要（⑧，作者 2026-09-15：「就很奇怪，对一个设备说〈设备名〉/ Device /
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
  // 引用信封（正文首行）不进会话列表摘要 —— 列表预览只显示**回复正文**
  // （无信封 ⇒ 逐字现状）。解析走唯一解析点 `parseQuoteBody`。
  const q = parseQuoteBody(m.body);
  return (isAgentSent(m) ? `[${t(AGENT_BADGE_TEXT_KEY)}] ` : '') + (q ? q.reply : m.body);
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
  // sessperf Phase B（2026-09-20）：面板首帧来源 = 本地层名册快照（**同步、零
  // await、零网络**）；下面的网络腿降为**增量核对**（卡 §4②「签名相同 ⇒ 零网络、
  // 零 DOM 操作」的落点见函数尾）。本地层不可用 / 无快照 ⇒ 与改前逐字同行为。
  seedConversationsFromStore();
  const wantFriends = friends === 'force'
    || friendsCache.length === 0
    || (Date.now() - friendsFetchedAt) > CACHE_TTL_MS;
  let friendsRefreshed = false; // ③ 本拍是否真的换上了新快照（补判的判据，见函数尾）
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
    if (fr) { friendsCache = fr.friends || []; friendsFetchedAt = Date.now(); friendsRefreshed = true; }
    // 写路径（本地层唯一写入口）：合并后的**最终快照**落盘 ⇒ 下一次面板首帧零网络。
    if (isLocalStoreEnabled()) {
      writeRoster('conversations', conversations);
      if (fr) writeRoster('friends', friendsCache);
      if (grp && Array.isArray(grp.groups)) writeRoster('groups', grp.groups);
      if (devices.length) writeRoster('devices', devices);
    }
  } catch { /* keep last known */ }
  // 签名相同 ⇒ **零 DOM 操作**（卡 §4②）：内容没变时不做 innerHTML 全量重建
  // （重建会把每行头像 `<img>` 全部销毁重造，正是「每次进面板都闪一下」的机制）。
  renderListIfChanged();
  updateBadge();
  // ③ 名单装载后**补判一次**（msgfix 批 · 作者 2026-09-19 睡前令）：快照补齐/刷新后
  //   「不是好友」的判定可能已翻转（无证据 ⇒ 已装载且命中）。旧形态只在开窗 /
  //   `fm-friends-changed` / 重连三处重判 ⇒ 空快照下开窗的条**粘滞**到关窗重开为止
  //   （实测 C2b）。此处与 `fm-friends-changed` 分支同款、同函数（禁第二实现）。
  if (friendsRefreshed && modalEls) applyBlockState(currentConv());
}

// ── 会话列表：本地层首帧 + 内容签名闸（sessperf Phase B · 2026-09-20）──────
// 卡 §4②「群名册 / 会话列表 / 好友列表：服务端无版本号 ⇒ **内容签名**（有序拼接
// 「行 id + lastMessage.id + unreadCount + memberCount」）」+「签名相同 ⇒ 零网络、
// 零 DOM 操作」。本区是这两条的机械落点（唯一实现）。

/** 上一次真正重建过列表 DOM 时的内容签名（`null` = 本会话尚未渲染过）。 */
let renderedListSig = null;

/** 会话列表内容签名（判定字段 = 卡 §4② 口径；`openConvId` 决定 aria-selected，
 *  属渲染输入 ⇒ 一并入签名，避免「跳过的重建其实该改选中态」）。 */
function rosterSignature(list) {
  const parts = [];
  for (const c of list || []) {
    if (!c) continue;
    parts.push(
      c.conversationId,
      (c.lastMessage && c.lastMessage.id) || '',
      Number(c.unreadCount) || 0,
      Number(c.memberCount) || 0
    );
  }
  parts.push(`open:${openConvId || ''}`);
  return signatureOf(parts);
}

/** 签名变了才重建（相同 ⇒ 零 DOM 操作，卡 §4②）。 */
function renderListIfChanged() {
  if (rosterSignature(conversations) === renderedListSig) return;
  renderList();
}

/** 首帧：本地层名册快照 → 会话列表（同步、零 await、零网络）。
 *  本地层不可用 / 无快照 / 已有内存行 ⇒ 无操作（回落既有网络腿，零回归）。 */
function seedConversationsFromStore() {
  if (!isLocalStoreEnabled() || conversations.length) return false;
  const cached = readRoster('conversations');
  const rows = cached && Array.isArray(cached.payload) ? cached.payload : null;
  if (!rows || !rows.length) return false;
  conversations = rows.slice();
  learnSelfFromGroupRows(conversations); // 加性 viewer 字段腿：缓存快照同样可学
  renderListIfChanged();
  updateBadge();
  return true;
}

function renderList() {
  const box = document.getElementById('fm-conversations');
  if (!box) return;
  box.innerHTML = '';
  updateBadge();
  // 本拍 DOM 与内容指纹对齐（下一次可比对 ⇒ 相同则零重建）。三条出口（登录空态 /
  // 列表空态 / 全量重建）都在此落位 —— `#fm-conversations` 是 index.html 的静态
  // 节点 ⇒ 上面 `!box` 一支只在极早期（HTML 未挂载）可达，不参与指纹。
  const sig = rosterSignature(conversations);

  if (!loggedIn()) {
    const empty = el('div', 'fm-login-empty');
    empty.appendChild(el('div', 'fm-login-text', t('messages.loginRequired')));
    const btn = el('button', 'glass-control fm-login-btn', t('messages.login'));
    btn.addEventListener('click', () => openLoginModal());
    empty.appendChild(btn);
    box.appendChild(empty);
    renderedListSig = sig;
    return;
  }
  if (conversations.length === 0) {
    box.appendChild(el('div', 'fm-empty', t('messages.empty')));
    renderedListSig = sig;
    return;
  }
  for (const conv of conversations) box.appendChild(convRow(conv));
  createIconsIn(box);
  renderedListSig = sig;
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
  // ①兜底（作者 2026-09-17；与服务端部署轨**解耦**）：列表侧优先取**已拉到的成员
  // 名册**（`groupMemberAvatars`，开窗时装载）——与窗头 `paintGroupAvatarInto` 的
  // **同一两级优先序**逐字对齐（名册 → 会话行字段 → 首字母）。① 的服务端部署到位后
  // 第一级即命中 `memberAvatars`，本行**无害且不再被走到**；部署前它把「列表 = 首字母
  // vs 对话框 = 组合头像」的错位收敛为同源：零新增请求、零 N+1（复用既有模块级 Map）。
  // ④①（作者 2026-09-15）：**设备行头像**改为与联系人面板设备段**同款**的
  // 平台图标（`deviceAvatarEl`，字形源 = `platformDisplay` 单点）——原形态是
  // `avatarEl` 的**首字母占位**（与好友/群同款），与联系人面板里那台设备的
  // 图标不一致（红读数见本批报告 §①）。单聊行照旧朋友档案。
  const avatarPerson = isGroup ? { name: groupTitleOf(conv) } : conv.friend;
  if (isDevice) {
    row.appendChild(deviceAvatarEl(conv.device, 40));
  } else if (isGroup) {
    const roster = groupMemberAvatars.get(String(conv.conversationId));
    row.appendChild(groupAvatarGrid((roster && roster.length) ? roster : conv.memberAvatars, 40, conv.memberCount)
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

/** 会话列表**单行**就地重打（r2：名册到达时与窗头重打**并列**的那一条腿）。
 *  🔴 为什么必须单独一条腿：`convRow()` 的名册优先序**只在渲染时求值**，而列表渲染
 *  由刷新事件驱动 ⇒ 名册晚于首帧到达时，「窗头已是组合头像 / 列表仍是首字母」会一直
 *  错位到下一次刷新事件（两腿不同源）。本函数把名册到达**接进列表腿的因果链**。
 *  🔴 只换该行 DOM（不整表重建）：其他行的滚动位置、键盘焦点、`aria-selected` 面零扰动；
 *  焦点原在该行 ⇒ 迁移到新行（键盘可达性不回归，与 `closeChat` 的 A18 回找同语义）。
 *  行不在场（面板未开 / 行已被换掉）或会话不在册 ⇒ 无操作（幂等，零新增请求）。 */
function rerenderConvRow(conversationId) {
  const cid = String(conversationId);
  const box = document.getElementById('fm-conversations');
  const old = box && box.querySelector(`.fm-conv-row[data-conversation-id="${CSS.escape(cid)}"]`);
  if (!old) return;
  const conv = conversations.find(c => String(c.conversationId) === cid);
  if (!conv) return;
  const focused = document.activeElement === old;
  const next = convRow(conv);
  old.replaceWith(next);
  if (focused) next.focus();
}

// ── Chat modal (§2.3/§3.3) ───────────────────────────────
function closeChat() {
  // Every close path (ESC, backdrop, ×, post-send) funnels through here —
  // detach the document listener centrally so open/close cycles stay
  // symmetric (D4, mem-diag 20260907). removeEventListener is idempotent.
  document.removeEventListener('keydown', escClose);
  // msgmenu 一期：两态随窗归零（「关窗重开选中归零」= 验收判据；引用态同理
  // —— 关窗即弃，禁跨窗残留）。注意：exitSelection 在 modalEls 被清前调用。
  exitSelection();
  clearQuote();
  if (modalEls) {
    modalEls.overlay.remove();
    modalEls = null;
  }
  // U-b：窗口一关，在飞乐观项的节点即脱离文档 ⇒ 登记随之作废（在飞 POST 的
  // 续接腿会因 `node.isConnected === false` 自动走最小回落）。
  resetPending();
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

/** 群组合头像落槽（**唯一**落槽实现；落点 = **窗头** `.fm-modal-avatar` 一处
 *  —— 抽屉信息头那处已按作者 2026-09-17 令摘除）。
 *  数据源优先级（**两级，禁第三级**）：
 *   ① `cells`（本窗已拉到的成员名册，来源 = `getGroupMembers`，含显示名）；
 *   ② `conv.memberAvatars`（会话列表行字段，归一出口产物；渲染面禁读 wire 键）。
 *  🔴 同一两级序也是**会话列表群行** `convRow` 群分支的优先序（`groupMemberAvatars`
 *  缓存 → `conv.memberAvatars` → 首字母）⇒ 两处**同源同序**（本批 ①的客户端兜底）。
 *  两级都空（名册未到 / 字段缺席 / 畸形 / 群 0 人）⇒ 回退标题首字母那一枚
 *  （现状形态 = 降级态）。逐格无 `avatarUrl` 由 groupAvatarGrid 内逐格首字母兜底。
 *
 *  🔴 2026-09-17 uifix 批（作者令「群头像没有被缓存，我每次点进群……都会闪一下」）：
 *  **重绘前先比内容签名**，签名相同 ⇒ **零 DOM 操作**直接返回。
 *  · 改前恒 `host.innerHTML = ''` + 重建 ⇒ 每次进群一次全量替换（实测
 *    `rebuild=1`/`imgCreate=6`/节点复用 `0/6`）；名册腿到达后的那次重绘与首帧
 *    内容**逐字相同**却照样重造全部 `<img>` ⇒ 新节点重新解码 ⇒ 可见闪。
 *  · 签名 = `avatarCellsSignature`（`avatarRender.js`，纳入 userId/avatarUrl/
 *    顺序/总数/降级文案 = 决定渲染结果的全部输入）⇒ 「变了才重绘」是**完备**的：
 *    任一输入变（成员增删/头像改 URL/成员数变）签名必变，仍照旧重绘。
 *  · 逐枚 `<img>` 由 `avatarImgNode` 取（已解码节点复用池）⇒ 真需要重绘时也
 *    不重新拉取、不重新解码（同 URL 的游离节点直接重新挂载）。 */
function paintGroupAvatarInto(host, conv, cells) {
  if (!host || !conv) return;
  const list = (Array.isArray(cells) && cells.length) ? cells : conv.memberAvatars;
  const total = Number(conv.memberCount) || (Array.isArray(list) ? list.length : 0);
  const label = convTitleLabel(conv);
  const sig = avatarCellsSignature(list, total, label);
  if (host.dataset.avatarSig === sig) return; // 内容未变 ⇒ 不碰 DOM（禁「先清空再赋值」）
  host.dataset.avatarSig = sig;
  host.innerHTML = '';
  host.appendChild(groupAvatarGrid(list, 40, total) || avatarEl({ name: label }, 40));
}

/** 开着的群窗：组合头像就地重打（`fm-groups-changed` 到达 ⇒ 成员集可能已变）。
 *  触发面 = 方案 §3.2.2 的**唯一现成广播面**；数据零新增请求（复用群列表行字段
 *  + 窗头自己的名册腿拉取）。会话行可能已被 refreshConversations 换成新对象 ⇒
 *  先把最新读数同步回本窗捕获的 conv（窗头与列表行共用同一份，禁两套数据）。
 *  🔴 单一落槽：抽屉信息头（`.fm-gs-head-avatar`）已按作者 2026-09-17 令**摘除**
 *  （展开面板不再重复「头像 + 群名 + 成员数」）⇒ 本函数只重打窗头那**一处**。 */
function refreshOpenGroupHeaderAvatar() {
  if (!modalEls) return;
  const slot = modalEls.overlay.querySelector('.fm-modal-avatar');
  if (!slot) return;
  const conv = currentConv();
  if (!conv || conv.kind !== 'group') return;
  const fresh = conversations.find(c => c.conversationId === conv.conversationId);
  if (fresh && fresh !== conv) {
    conv.memberAvatars = fresh.memberAvatars;
    if (fresh.memberCount !== undefined) conv.memberCount = fresh.memberCount;
  }
  const cells = groupMemberAvatars.get(String(conv.conversationId));
  paintGroupAvatarInto(slot, conv, cells);
}

function isStillFriend(conv) {
  // ③ 证据门槛（msgfix 批 · 作者 2026-09-19 睡前令）：`friendsCache` 的「空」有**两种**
  //   含义 ——「已装载且确实不在名单」（= 真不是好友）与「无证据（快照未装载 / 取数
  //   失败 / 空快照）」。旧写法把二者折叠成同一个 `false` ⇒ 空快照下开窗必出「对方已
  //   不是你的好友」且输入框被误禁用（实测 C2a/C3，粘滞见 C2b）。
  //   证据 = **本模块的这份名单已装载且非空**（`friendsFetchedAt` 与 `friendsCache`
  //   同生同灭：`refreshConversations` 同帧赋值 `:574` / 未登录同帧清零 `:534`）。
  //   空名单**不构成**「他不是好友」的证据 —— 200 空体与「取数失败」在界面上无从区分，
  //   而本条文案是**指控**（作者令：宁可不判定，不得误指）。
  //   与 `friendsApi.js:470-478 friendStateLoaded()` **同一口径**（「无证据 ≠ 已收敛」）；
  //   差别在严格度：`friendStateLoaded()` 只要求「成功取过一次数」（空快照也算已装载），
  //   本判据额外要求名单非空 —— 因为这里被断言的正是这份名单，且空名单的两种来源无法
  //   区分（实测判据 C2a：空快照必须**不**出条）。
  //   🔴 fail-closed **不可退**：名单已装载（非空）且该 userId 真不在其中（删除/拉黑）
  //   ⇒ 仍判假（出条 + 禁用输入框），见实测判据 C4。
  if (friendsFetchedAt <= 0 || friendsCache.length === 0) return true; // 无证据 ⇒ 未判定
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

/** ⑨ 把当前已加载窗口写进本地层（水位 = 已见到过的最大数值 id）。
 *  sessperf Phase B：写路径收口到 `localStore`（IndexedDB）；本地层不可用（L1）
 *  ⇒ 回落 legacy L2（`fmMessageCache.saveConversation`）—— 两者**不双写**
 *  （卡 §5.4 风险表：双写期双读数分歧）。 */
function persistConversation(conv) {
  if (!conv || !conv.conversationId || chatMsgs.length === 0) return;
  let watermark = 0;
  for (const m of chatMsgs) { const n = Number(m.id); if (Number.isFinite(n) && n > watermark) watermark = n; }
  const lastMessageAt = toEpochMs(conv.lastMessage?.createdAt);
  if (isLocalStoreEnabled()) {
    void writeMessages(conv.conversationId, chatMsgs, { watermark, lastMessageAt });
    return;
  }
  saveConversation(conv.conversationId, chatMsgs, { watermark, lastMessageAt });
}

/** 首帧读取的单点：本地层（同步内存镜像）优先；本地层不可用 ⇒ legacy L2。
 *  🔴 本地层可用时**不读** legacy（禁双读数：导入是一次性的，此后 IDB 才权威）。 */
function cachedConversation(conversationId) {
  const fromLocal = readMessages(conversationId);
  if (fromLocal) return fromLocal;
  return isLocalStoreEnabled() ? null : loadConversation(conversationId);
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

  // ⑨ 热路径（有缓存）：同步读本地层首屏（零往返、零 await），随后一次极小增量核对
  // （`after=<水位>&limit=SYNC_PAGE`）—— 无新消息 = 空响应，**不是**尾窗重取。
  // sessperf Phase B：读取面 = `localStore.readMessages`（IndexedDB 内存镜像，
  // 键 = convId）；本地层不可用时回落 legacy L2（L1，行为与改前逐字相同）。
  const cached = cachedConversation(conversationId);
  if (cached) {
    chatMsgs = cached.msgs.slice();
    oldestLoadedId = chatMsgs.length ? (Number(chatMsgs[0].id) || 0) : 0;
    hasMoreHistory = false;
    renderMessages(chatMsgs);
    void refreshConvReceipts(conv); // R5 两格：回执面（E1）随开窗取一次（真相源）
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
  void refreshConvReceipts(conv); // R5 两格：回执面（E1）随开窗取一次（真相源）
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

/** 群成员名册的应用（**唯一**就地渲染实现：窗头组合头像 + 列表行 + 已渲染气泡
 *  的发送者名回填）。名册来源两腿（本地层快照 / 网络腿）共用它 ⇒ 两腿渲染结果
 *  逐字同形（禁第二份渲染链）。 */
function applyGroupRoster(conv, cells) {
  const convId = String(conv.conversationId);
  const map = new Map();
  for (const mem of cells || []) {
    if (mem && mem.userId) map.set(String(mem.userId), mem.name || String(mem.userId));
  }
  groupMemberNames.set(convId, map);
  groupMemberAvatars.set(convId, cells || []);
  // 名册到达 ⇒ 窗头组合头像就地重打（同一次拉取的产物，零新增请求）。
  refreshOpenGroupHeaderAvatar();
  // r2（判词 V1 的根因）：**列表腿同一时刻重打**。窗头与列表行**共用同一两级优先序**
  // （名册 → 会话行字段 → 首字母）⇒ 两条腿必须挂在**同一因果链**上；只重打窗头会让
  // 列表停在首字母直到下一次刷新事件（两侧逐格不等）。同一次拉取的产物 ⇒ 零新增请求、
  // 零新 CSS，不动数据面/接口。
  rerenderConvRow(conv.conversationId);
  // 就地回填：名册晚于首帧到达时，补齐已渲染气泡的发送者名（幂等）。
  if (modalEls && openConvId === conv.conversationId) {
    for (const s of modalEls.flow.querySelectorAll('.fm-msg-sender[data-sender-id]')) {
      const nm = map.get(s.dataset.senderId);
      if (nm && !s.textContent) s.textContent = nm;
    }
  }
}

/** 群名册内容签名（卡 §4② 兜底判据的群面口径：成员 id 集合的有序拼接）。
 *  `userId` 顺序 = 服务端加入序（纯透传 ⇒ 顺序是内容的一部分，禁排序归一）。 */
function groupRosterSignature(cells) {
  return signatureOf((cells || []).map((c) => (c && c.userId) || ''));
}

/** 开群窗时的成员名册装载（每窗一次）。
 *
 *  sessperf Phase B（卡 §4② / §5.4）：① 首帧先读本地层快照（**同步、零网络**）
 *  并就地渲染 —— 集团头像/发送者名不再等一次往返；② 快照新鲜（10 min TTL）且与
 *  会话行 `memberCount` 一致 ⇒ **零网络**（「签名相同 ⇒ 零网络」的群面落点，
 *  每窗省 1 次 `GET /api/groups/{id}/members`）；③ 否则取一次，**签名相同 ⇒
 * 零 DOM 操作**（禁「字母 → 组合头像」的换脸重打）。失败一律降级为无发送者名
 *  （消息本体不受影响），与改前同一条纪律。 */
async function hydrateGroupSenderNames(conv) {
  const convId = String(conv.conversationId);
  const cachedEntry = readRoster('groupMembers:' + convId);
  const cachedCells = cachedEntry && Array.isArray(cachedEntry.payload) ? cachedEntry.payload : null;
  let prevSig = null;
  if (cachedCells && cachedCells.length) {
    prevSig = groupRosterSignature(cachedCells);
    applyGroupRoster(conv, cachedCells);
    const mc = Number(conv && conv.memberCount);
    const countMatches = !(Number.isFinite(mc) && mc > 0) || mc === cachedCells.length;
    if (cachedEntry.fresh && countMatches) return; // ② 零网络
  }
  try {
    const members = await api.getGroupMembers(conv.conversationId);
    // 成员面信封 `selfUserId`（契约终版 §1.1 #7）= viewer 身份的另一条权威腿：
    // 学到即收敛整窗方向判据（含已渲染气泡的下一次渲染）。
    if (members && members.selfUserId) learnSelfUserId(members.selfUserId);
    const cells = [];
    for (const mem of members || []) {
      // 窗头组合头像格子（身份键 userId 与列表行字段同空间；顺序纯透传 ——
      // 🔴 禁把索引 0 当群主，正典 §A.2 owner 位置不确定）。
      if (mem && mem.userId) cells.push({ userId: mem.userId, name: mem.name, avatarUrl: mem.avatarUrl });
    }
    const nextSig = groupRosterSignature(cells);
    void writeRoster('groupMembers:' + convId, cells, { version: 0 });
    if (prevSig !== null && prevSig === nextSig) return; // ③ 签名相同 ⇒ 零 DOM 操作
    applyGroupRoster(conv, cells);
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
  // 窗头头像槽（两档，同槽类 `.fm-modal-avatar`、同 40px 档 ⇒ **零新 CSS**）：
  //   · 群窗 = 组合头像（方案 §3.1 P1），随名册/群列表变更就地重打（禁整窗重建）；
  //   · **好友（单聊）窗 = 好友档案头像**（作者 2026-09-17 令「让好友的对话框能显示
  //     好友的头像」）—— 复用既有 `avatarEl` ＋ 既有 `conv.friend`（**与列表行
  //     `convRow` 的单聊分支**同一调用、同一数据对象，禁第二份取数/渲染链）；
  //   · 设备窗**不挂**（其窗头形态由作者 2026-09-15 档位固定，本批不扩张）。
  // 几何申报：40px 头像行必然把窗头抬到 64px 档（群窗先例 `729c56f3d`，已判**非回归**；
  // 单聊窗同款增量，本批逐条读数见报告 §P2）。
  const isGroupHead = conv.kind === 'group';
  const headAvatarSlot = (isGroupHead || (conv.kind !== 'device' && !!conv.friend))
    ? el('span', 'fm-modal-avatar') : null;
  if (headAvatarSlot) {
    // 🔴 uifix 批（2026-09-17）：首帧**也**先查本窗名册缓存 `groupMemberAvatars`
    // —— 与 `refreshOpenGroupHeaderAvatar`（下方 :745 一带）**同一个查找式、同一份
    // 数据**（禁第二套）。改前首帧只看 `conv.memberAvatars`：该字段缺席时首帧落
    // 群名首字母，名册腿到达后再整体换成六宫格 ⇒ 每次进群一次「字母 → 头像」闪。
    // 带上缓存后，第二次及以后进同一群首帧即命中名册（与随后的名册腿签名相同 ⇒
    // `paintGroupAvatarInto` 的签名闸直接短路，全程零 DOM 操作）。
    if (isGroupHead) paintGroupAvatarInto(headAvatarSlot, conv, groupMemberAvatars.get(String(conv.conversationId)));
    else headAvatarSlot.appendChild(avatarEl(conv.friend, 40));
    header.appendChild(headAvatarSlot);
  }
  const title = el('div', 'fm-modal-title');
  const nameEl = el('span', 'fm-modal-name', convTitleLabel(conv));
  title.appendChild(nameEl);
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
    // 窗头群名处入口（H）：**点击/键盘热区 = 群名本身**（作者 2026-09-17 令：
    // 「让 header 可点击展开的范围只是群名和按钮」）—— 类/role/tabindex/tooltip/
    // handler 一律挂在 `.fm-modal-name` 上；成员数副行（上方 `if (conv.kind === 'group')`
    // 支）与群名右侧的空白**移出**热区（原形态 = `title` 为 `.fm-modal-header` 的
    // 唯一 `flex:1` 项 ⇒ 整条中间带可点，`friends.css:632`）。
    // 🔴 可达性零回归：`role=button` / `tabindex=0` / Enter|Space 键盘契约**随迁**
    // （逐条断言见报告 §P3）；Tab 序不变（`.fm-modal-name` 仍在 `.fm-gs-open` 之前）。
    // 🔴 与 `.fm-gs-open`（同族第二入口）仍共用同一个 `mountDrawer`，禁第二套抽屉实现。
    const toggleFromTitle = () => { if (groupSettingsMounted) { modalEls?.overlay.querySelector('.fm-group-settings')?.remove(); groupSettingsMounted = false; } else { mountDrawer(); } };
    nameEl.classList.add('fm-modal-title-btn');
    nameEl.setAttribute('role', 'button');
    nameEl.setAttribute('tabindex', '0');
    nameEl.title = t('messages.groupSettings');
    nameEl.addEventListener('click', toggleFromTitle);
    nameEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleFromTitle(); }
    });
  }
  // 信任模式 v1: 窗头信任状态指示（开启态一眼可辨；开关在好友行右键菜单）。
  // SEALED (author ruling 2026-09-12): 封存期不挂槽；updateTrustBadge 保留
  // （无槽即天然不产出）。回退 = featureFlags.js 常量改回 false。
  if (!TRUST_SEALED) header.appendChild(el('span', 'fm-trust-slot'));
  // 多选计数宿主（**D5**：计数由底部条迁到**头部**，作者 2026-09-19 04:22 照案）。
  // 落位 = 窗头右端（标题 `flex:1` 之后、✕ 之前）——参考图为「头部居中显示计数」，
  // 本窗头左侧已有头像+名称 ⇒ 取右端（有据适配；几何读数见本批报告 token 表）。
  // 🔴 计数文案沿用既有 i18n key（不改文案）；非多选态隐藏。
  const selectCount = el('span', 'fm-modal-select-count', '');
  selectCount.hidden = true;
  header.appendChild(selectCount);
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

  // ── msgmenu 一期 + visup-b（作者 2026-09-19 04:22 照案）：两个新 UI 面 ─────────
  // ① 引用态条（**D2：挂输入条下方**）—— 组件本体仍是既有输入框引用块渲染器
  //    （`renderRefBlock(mode:'input', closeStyle:'disc')` ⇒ ❌ = 主窗口同类件，修正①）；
  // ② 多选工具条（**D5：等分动作**；已选 N 条已迁到窗头）—— 转发键开**转发窗口**（D7），
  //    逐条转发走既有目标选择器，合并转发 = 二期（disabled + 标注）。
  // 🔴 引用态条的 DOM 序必须**晚于** `.fm-input-bar`（D2 的落位 = 输入框下方）：
  //    旧形态挂在输入条**上方**（DOM 序 header→flow→strip→input-bar）。
  const selectBar = el('div', 'fm-select-bar');
  selectBar.hidden = true;
  const selectForward = el('button', 'fm-select-act fm-select-forward', t('messages.forward'));
  selectForward.type = 'button';
  selectForward.disabled = true;
  const selectForwardEach = el('button', 'fm-select-act fm-select-forward-each', t('messages.forwardEach'));
  selectForwardEach.type = 'button';
  selectForwardEach.disabled = true;
  // 合并转发 = **二期**（无服务端协议 ⇒ 形态在册、落 disabled + 标注；禁「点了没反应」）。
  const selectForwardMerge = el('button', 'fm-select-act fm-select-forward-merge', t('messages.forwardMerge'));
  selectForwardMerge.type = 'button';
  selectForwardMerge.disabled = true;
  selectForwardMerge.title = t('messages.forwardMergePhase2');
  selectForwardMerge.dataset.phase = '2';
  const selectExit = el('button', 'fm-select-exit', t('messages.selectExit'));
  selectExit.type = 'button';
  selectBar.append(selectForward, selectForwardEach, selectForwardMerge, selectExit);
  modal.appendChild(selectBar);
  selectForward.addEventListener('click', () => openForwardWindow(conv, selectForward));
  selectForwardEach.addEventListener('click', () => openTargetPicker(conv, selectForwardEach));
  selectExit.addEventListener('click', () => exitSelection());
  // 「点外退出」：窗内空白处（不在气泡/工具条/**转发窗口**上的）点击 ⇒ 退多选。
  // 覆盖层点击仍是既有「关窗」语义（`overlay.addEventListener` 的 `e.target === overlay` 分支）。
  // 🔴 visup-b：转发窗口（D7）挂在覆层上、**不在本 `modal` 子树内**，但真实点击可能
  // 落回本窗（面板关时）⇒ 判据里显式排除 `.fm-fwd-modal` 子树，否则面板内的任意点击
  // 都会被当成「点外」而退多选（＝把刚打开的面板连根收掉）。
  modal.addEventListener('click', (e) => {
    if (!selectionMode) return;
    const t0 = e.target;
    if (t0 instanceof Element && (t0.closest('.fm-msg') || t0.closest('.fm-select-bar') || t0.closest('.fm-fwd-modal'))) return;
    // D7：转发面板在场时，窗内空白的一击**先收面板**（多选态保留 ⇒ 可原地重开，
    // 不用重新勾选）；面板不在场时仍是既有「点外退出多选」。覆层自身的点击仍是
    // 既有「点外关窗」（`overlay` 分支 ⇒ `closeChat()` ⇒ `exitSelection()` ⇒
    // `closeForwardWindow()`）⇒ 面板在任何关窗路径下都会被同拍收掉，零残留。
    if (fwdState) { closeForwardWindow(); return; }
    exitSelection();
  });

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
  // uxconsist Phase B（§4.1-#2 三面入口收敛）：纸夹 = **一枚键 + 一个 input + 一个分派口**。
  // 改前按 `conv.kind` **二分建两套控件**（两个 input、两枚键、两个 handler）——同一动作
  // 三面各有实现，闸位/上限/受理面的差异正是从这类分叉里长出来的。现在入口只有一个，
  // 面间差异全部收在 `dispatchFiles`（闸位/上限/受理面**三面同源**）。
  const attachInput = document.createElement('input');
  attachInput.type = 'file';
  attachInput.multiple = true;
  attachInput.style.display = 'none';
  attachInput.addEventListener('change', () => {
    if (attachInput.files && attachInput.files.length > 0) dispatchFiles(conv, attachInput.files);
    attachInput.value = '';
  });
  const attachBtn = el('button', 'icon-btn dropbox-attach-btn fm-attach-btn');
  attachBtn.type = 'button';
  attachBtn.title = t('dropbox.attachFile');
  attachBtn.setAttribute('aria-label', t('dropbox.attachFile'));
  attachBtn.innerHTML = '<i data-lucide="paperclip"></i>';
  attachBtn.addEventListener('click', () => attachInput.click());
  // 入口可用面 = **三面同源**：设备窗恒有（`sendDeviceFiles` 单点）；好友 / 群窗由
  // `attachAvailable(conv)` 判（寻址面在 `attachTargetOf` 单点）。不可用面**不挂假入口**。
  if (conv.kind === 'device' || attachAvailable(conv)) {
    bar.appendChild(attachBtn);
    bar.appendChild(attachInput);
  }
  bar.appendChild(sendBtn);
  modal.appendChild(bar);

  // 引用态条宿主（**D2**：挂载点 = 输入条**之后** ⇒ DOM 序 header→flow→input-bar→strip，
  // 与「引用条在输入框下方」的参考图实测层序一致）。
  const quoteStrip = el('div', 'fm-quote-strip');
  quoteStrip.hidden = true;
  modal.appendChild(quoteStrip);

  overlay.appendChild(modal);
  document.body.appendChild(overlay);
  modalEls = { overlay, flow, input, sendBtn, toast, offline, conv, quoteStrip, selectBar, selectCount, selectForward, selectForwardEach, selectForwardMerge };
  // Fresh modal → reset history-window state (a stale older conversation's
  // tail must never leak into this one).
  chatMsgs = [];
  // msgmenu 一期：两个新态同拍归零（关窗重开 ⇒ 选中归零、无跨窗引用态）。
  selectionMode = false;
  selectedIds.clear();
  pendingQuoteRef = null;
  // U-b：旧窗口的乐观项登记随之作废（其节点已脱离文档）——登记表与 chatMsgs
  // 同拍，绝不跨窗残留。
  resetPending();
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
    // 拖放与纸夹 = **同一实现**（同一 `dispatchFiles`）：闸位 / 上限 / 受理面三面同源，
    // 禁两条腿各写一套（§4.1-#3）。不可用面（既非设备窗、又无附件寻址面）⇒ 可见提示，
    // 静默吞文件不可接受（`messages.attachUnsupported` 键保留仅为兼容旧读数）。
    dispatchFiles(conv, e.dataTransfer.files);
  });

  // ── 发送入口**单一分派**（§4.1-#1）：`conv.kind` 的适配器选择只在本函数内出现一次 ──
  const doSend = () => dispatchSend(conv);
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
    // msgmenu 一期：① 浮层菜单开着 ⇒ 这一下 Esc 归菜单（`contextMenu.js` 的捕获期
    // 监听已关它）⇒ 本处理器让位，禁同一击既关菜单又关窗/退多选；
    // ② 多选态 ⇒ 先退多选（作者口径「Esc 退出选择态」），窗不关；
    // ③ 其余 = 既有语义（关窗）。
    if (isPopupMenuOpen()) return;
    if (selectionMode) { e.stopPropagation(); exitSelection(); return; }
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
  // 📌 selfattach 批：**字节源**同样进签名（`deviceOutPath` 值 / 句柄在场与否）——
  //    路径或句柄到达/退场即须重填卡片（否则「台账帧补上路径」永远画不出来）。
  return a.map(x => (x && x.state === 'device')
    ? `dev:${(x.deviceStatus || '')}:${(x.deviceSavedPath || '')}:${(x.deviceOutPath || '')}:${x.deviceOutHandle ? 'h' : '-'}:${(typeof x.devicePct === 'number' ? x.devicePct : '')}`
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
  // 状态判据 = 设备腿 5 态（pending/accepted/transferring/completed/failed）。
  // 📌 devattach 批 · 作者令 2026-09-17 12:31（逐字）：「设备对话窗口的附件行为和群聊/
  //    好友的行为不一致。首先 ui/动画的行为上要一致，操作逻辑也要一致，群聊和好友发送的
  //    附件，是可以直接点击在 canvas 打开的，但是设备对话框的就不行。」
  //    ⇒ 本分支的**判据 = 与好友/群分支对齐**：
  //      · 点击 ⇒ `previewBlob`（与好友面**同一条** Canvas 渲染腿）；
  //      · 下载键 ⇒ 既有 `.fm-att-dl` 键族（同款呈现/动效）。
  //    🔴 **旧登记已被本令推翻**：此处原注「设备面的落盘由接收端传输链负责…没有应用内
  //    鉴权下载路由 ⇒ **不挂下载键**（禁「可点但点了报错」的假按钮，§B.7 ③）」——
  //    本机落盘件**有**既有字节路由（`nfTicket.ticketUrl` ⇒ `/api/nf-file?path=…&ticket=…`，
  //    即设备内联图片腿同一个字节源），故「无下载面」的前提不成立；键现按
  //    `canFetchLocalBytes` 判据挂（§B.7 ③ 本身仍成立：票据路由不服务的类型不挂键）。
  if (kind === 'device') {
    card.appendChild(el('span', 'fm-att-device-status', deviceTransferText(att)));
    // 预览/下载两腿的**共同前提** = 本机有可读件（发出腿 user / 未完成 / 失败 ⇒ 无句柄无路径
    // ⇒ 本地没有这件 ⇒ 不挂可点面、不挂键；状态位文案即用户可见的说明，§B.7 ③）。
    // selfattach 批：判据收敛到**单点** `deviceByteSourceOf`（blob = 发送侧句柄 / path = 本机路径）。
    const src = deviceByteSourceOf(att);
    const srcPath = src.kind === 'path' ? src.path : '';
    // ③ 下载键（作者令 2026-09-17 12:31「操作逻辑也要一致」）：位次紧跟状态位（同一行），
    //    路径位/进度条仍各占整行 ⇒ 几何与改前一致，只多一枚键（与好友卡同名同类同款）。
    //    挂键判据 = **能整件取回字节**：
    //      · blob 腿（发送侧 `File` 在手）⇒ 恒可（`saveBlob` 只吃这枚 blob，无路由可拒）；
    //      · path 腿 ⇒ 既有 `canFetchLocalBytes`（票据路由白名单；文本腿不挂键）逐字不变。
    //    §B.7 ③「禁可点但点了报错」两条腿都成立。
    if (src.kind === 'blob' || (src.kind === 'path' && canFetchLocalBytes(srcPath))) {
      const note = el('span', 'fm-att-note', t('messages.attachDownload'));
      note.classList.add('visually-hidden-note'); // 常态只显示键；失败/成功后就地显示文案
      const btn = el('button', 'fm-att-dl', t('messages.attachDownload'));
      btn.type = 'button';
      btn.setAttribute('aria-label', `${t('messages.attachDownload')}: ${(att && att.name) || ''}`);
      btn.addEventListener('click', () => downloadDeviceSavedAttachment(att, card, btn, note, src));
      card.appendChild(btn);
      card.appendChild(note);
    }
    if (att && att.deviceSavedPath) card.appendChild(el('span', 'fm-att-device-path', String(att.deviceSavedPath)));
    // B′ 腿（agent 发）：把发送端本机真实路径显示在同一路径位（同一既有类名，零新样式）。
    else if (att && att.deviceOutPath) card.appendChild(el('span', 'fm-att-device-path', String(att.deviceOutPath)));
    const pct = att && att.devicePct;
    if (typeof pct === 'number') {
      const track = el('div', 'fm-att-device-progress');
      const bar2 = el('div', 'fm-att-device-progress-bar');
      bar2.style.width = `${Math.max(0, Math.min(100, pct))}%`;
      track.appendChild(bar2);
      card.appendChild(track);
    }
    // ④ 图片直显（设备端）：按字节源分派（blob = 本机句柄 / path = 既有票据链）。
    attachInlineImage(card, att, kind, src);
    // ① 点击 ⇒ Canvas 预览（与好友/群同路）。
    //    可点判据按字节源：path 腿 = 既有 `canPreviewLocalPath`（逐字）；blob 腿 = `canPreviewName`
    //    （同一判据源，收**真实名**）⇒ `.zip` 之类不挂假可点面（§B.7 ③）。
    const previewable = src.kind === 'blob'
      ? canPreviewName(String((att && att.name) || ''))
      : (src.kind === 'path' && canPreviewLocalPath(srcPath));
    if (previewable) {
      makeCardPreviewable(card, att, () => previewDeviceSavedAttachment(att, card, src));
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
    // ④ 图片直显（好友端 / 群聊端 —— 两面共用本卡片单点渲染）。
    attachInlineImage(card, att, kind);
    makeCardPreviewable(card, att, () => previewFriendAttachment(att, card));
  } else {
    card.appendChild(note);
    card.setAttribute('aria-disabled', 'true');
    // ① 发送侧乐观面（imgmsg 批）：本机件在上传在飞时的**唯一出帧腿** ——
    //    `att.localFace` 在场才挂（发送侧）；接收侧的 uploading/unreadable/expired 卡
    //    照旧**零直显槽**（`attachInlineImage` 首行即返回 ⇒ 逐字现状）。
    attachInlineImage(card, att, kind);
  }
  return card;
}

// ── ③ 气泡预览盒几何（**唯一算术落点**）· visup-ratio 批 · 作者 2026-09-19 01:29 令 ──
// 「气泡预览图尺寸按图片**真实宽高比**（禁写死固定比）；微信做法 = 按原图尺寸决定预览框
//  宽高比；实现侧自定 min/max 夹取防极端长宽比破版；参数按视觉定并在卡面申报。」
// ⇒ 本段四个常数即**申报的参数**（卡片同款：`css/friends.css` 的 `.fm-att-inline img` 段），
//    CSS 侧只消费 `--fm-att-w` / `--fm-att-ar`，**禁**第二处比例算术（禁两套并行实现）。
/** 上限盒宽（px）。沿用既有档（`.fm-att` 卡内容宽同量级）⇒ **零新增几何档**。 */
const ATT_INLINE_BOX_W = 260;
/** 上限盒高（px）。同上：既有档，盒永不大于 `260 × 200`。 */
const ATT_INLINE_BOX_H = 200;
/** 比夹取下界（1:2）：更竖的图按 1:2 出盒并**居中裁切**（防超长条把版撑破）。 */
const ATT_INLINE_AR_MIN = 1 / 2;
/** 比夹取上界（2:1）：更扁的图按 2:1 出盒并**居中裁切**（防超扁条把版撑破）。 */
const ATT_INLINE_AR_MAX = 2;

/** 由**原图内禀尺寸**算预览盒（**单点**：`W/H` 夹取 + 上限盒 contain + 禁放大）。
 *
 *  规则（与 CSS 段逐条对齐）：
 *   ① 比夹取：`r = clamp(nw / nh, AR_MIN, AR_MAX)`——越界图按界内比出盒（`cover` 居中裁切）；
 *   ② 上限盒：把 `r` 按 contain 装进 `260 × 200` ⇒ `r ≥ 260/200` 时定宽 260、否则定高 200；
 *   ③ 禁放大：盒不超过原图自身尺寸（小图按原尺寸出，不被拉大糊掉）。
 *  `ar` 回传**比率 token**：界内图直接用**所测字节源**的 `nw / nh`，越界图用夹取界
 *  （`2 / 1`、`1 / 2`）。⚠ 语义 = 「token 的**数值** == 目标比」而**非**「token 逐字恒定」：
 *  Chromium 对 `aspect-ratio` 的计算值**不约分**（`1600 / 900` 原样回读），且确认面按
 *  **小图**字节复量（`640 / 480` = 同比、不同 token）⇒ 断言一律走**数值比**（见 spec）。
 *  @param {number} nw 原图宽（px，>0） @param {number} nh 原图高（px，>0）
 *  @returns {{w:number, h:number, apx:number, ar:string, clamped:boolean}|null} 尺寸无效 ⇒ null */
function inlineBoxFor(nw, nh) {
  const W = Number(nw) || 0;
  const H = Number(nh) || 0;
  if (!(W > 0) || !(H > 0)) return null;
  const raw = W / H;
  let apx = raw;                    // 夹取后的**比值**（算术用）
  let ar = `${W} / ${H}`;           // 夹取后的**比率 token**（CSS 用 + 读数面）
  let clamped = false;
  if (raw > ATT_INLINE_AR_MAX) { apx = ATT_INLINE_AR_MAX; ar = '2 / 1'; clamped = true; }
  else if (raw < ATT_INLINE_AR_MIN) { apx = ATT_INLINE_AR_MIN; ar = '1 / 2'; clamped = true; }
  const boxApx = ATT_INLINE_BOX_W / ATT_INLINE_BOX_H;
  let bw = apx >= boxApx ? ATT_INLINE_BOX_W : ATT_INLINE_BOX_H * apx;
  let bh = bw / apx;
  if (bw > W) { bw = W; bh = bw / apx; }   // 禁放大（横向量）
  if (bh > H) { bh = H; bw = bh * apx; }   // 禁放大（纵向量）
  // 向下取整写宽（`ceil`/`round` 会把高度反向顶破 200px 上限：`h = w / r` 随 w 单调增）。
  const wi = Math.floor(bw);
  return { w: wi, h: Math.round((wi / apx) * 100) / 100, apx, ar, clamped };
}

/** 把预览盒写进槽 `<img>`（**单点写面**）：CSS 只读这两个变量 + 三个读数面。
 *  `data-att-nat` = **本次所测字节源**的内禀尺寸（发送侧乐观面 = 原图；确认面按小图复量，
 *  同比不同 token）——它是读数面，不是「原图恒等式」的断言面。
 *  @param {HTMLImageElement} img @param {number} nw @param {number} nh
 *  @returns {{w:number, h:number, apx:number, ar:string, clamped:boolean}|null} */
function applyInlineRatio(img, nw, nh) {
  const g = inlineBoxFor(nw, nh);
  if (!img || !g) return null;
  img.style.setProperty('--fm-att-w', `${g.w}px`);
  img.style.setProperty('--fm-att-ar', g.ar);
  // 读数面（不参与任何行为分支；与既有 `data-att-src` 同族）：
  //   `data-att-nat` 原图内禀尺寸 · `data-att-ar` 夹取后比率 token · `data-att-box` 计算盒。
  img.dataset.attNat = `${nw}x${nh}`;
  img.dataset.attAr = g.ar;
  img.dataset.attBox = `${g.w}x${g.h}`;
  return g;
}

/** 取 URL 的内禀尺寸（解码一次；失败 ⇒ `null`，绝不阻断出帧）。
 *  @param {string} url @returns {Promise<{w:number,h:number}|null>} */
function decodeDims(url) {
  return new Promise((resolve) => {
    try {
      const probe = new Image();
      probe.onload = () => resolve({ w: probe.naturalWidth || 0, h: probe.naturalHeight || 0 });
      probe.onerror = () => resolve(null);
      probe.src = url;
    } catch { resolve(null); }
  });
}

/** 出帧：**先把盒比落地、再点 `src`**（同一帧 ⇒ 图片从不以错误比例被绘制、绘制后零重排）。
 *
 *  · 比例已知（发送侧本机帧带内禀尺寸）⇒ 同步落地；
 *  · 比例未知（接收侧/设备面/回落原文件）⇒ 先解码取内禀尺寸，**再**点 `src`
 *    —— 骨架期保持 CSS 回退盒（既有上限盒），与真帧之间**只发生一次**几何变更。
 *  @param {HTMLImageElement} img @param {string} url @param {{w:number,h:number}|null} [dims] */
function paintInlineFrame(img, url, dims) {
  const set = (d) => {
    if (d && d.w > 0 && d.h > 0) applyInlineRatio(img, d.w, d.h);
    img.src = url;
  };
  if (dims && dims.w > 0 && dims.h > 0) { set(dims); return; }
  decodeDims(url).then(set);
}

/** 图片附件 → **对话框内直显**（uifix 批 · 作者令 2026-09-17 逐字：
 *  「另外，如果传的附件是图片的话，要支持直接在对话框显示，（好友、群聊、设备）。」）
 *
 *  ── 判据（**现读既有单源，禁第二张表**）──
 *  类型判据 = `itemTypeForFileName`（`fileViewers.js`，仓内 ext→itemType 真源，
 *  `attachmentPreview.js:14` 已 import 同一函数）⇒ 命中 `'image'` 才直显。
 *  🔴 **非图片附件零行为变化**：本函数第一行即返回，卡片仍走原路径（名称/体积/
 *  下载键/整卡预览）。
 *  📌 **取代关系（勿回退）**：uifix 批原口径「字节在手时再以真字节复核
 *  （`blob.type` 前缀 `image/`）」已被作者 2026-09-19 睡前令**取代**（msgfix 批 ②）：
 *  取字节路由 `/api/friends/attachments/{id}` 的响应头**契约上恒为**
 *  `application/octet-stream`（`RestApiRoutes.scala` 写死；服务端 DTO 注释逐字
 *  「Advisory only — never trusted for security」）⇒ 那道复判对**所有**好友/群图片
 *  恒假、直显腿恒不可达（实测：同一条消息、只换响应头 ⇒ 槽从有到无）。
 *  判据现为**单点**：调用前的文件名判据（`isImageAttachmentName`）+ 体积上限
 *  （`MAX_INLINE_IMAGE_BYTES`），响应头不参与判定（禁按上游 mime 建立信任面）。
 *
 *  ── 取字节（🔴 两套**既有**面，禁第三条路）──
 *  · 好友 / 群聊：`api.downloadAttachment(att.id)` = 应用内鉴权路由
 *    `/api/friends/attachments/{id}`（与下载腿、Canvas 预览腿**同一个**取字节口）。
 *  · 设备：本机落盘件 `att.deviceSavedPath` ⇒ `nfTicket.ticketUrl(path)` 取
 *    `/api/nf-file?path=…&ticket=…`（**既有票据面**：per-path 票据、TTL 1800s、
 *    票据内不限次读、三连失败熔断 15s —— 常量与语义见 `nfTicket.js:12-31`）。
 *
 *  ── 与 `imgticket` 口径的对齐（逐条，任务书要求）──
 *  · **票据面只用于「本机路径」**：`nfTicket` 的键是 `path`（本机绝对路径），
 *    好友/群附件的字节在**远端**、没有本机路径 ⇒ 它们**不得**走 nf-ticket，
 *    仍走既有鉴权路由。两套面**不混淆**（本函数按会话类型分流，非按类型猜测）。
 *  · **零新真源**：不新增端点、不新增票据类型、不新增 URL 拼接（nf-file 的 URL
 *    拼法仍由 `nfTicket.ticketUrl` 单点产出）。
 *  · **零新增内联预算**：字节上限复用 `attachmentPreview.js` 的
 *    `MAX_INLINE_IMAGE_BYTES`（**同值同源于** `MAX_TEXT_BYTES` 的 10MB —— 同一份
 *    字节从同一条鉴权路由取回，禁第二把尺）。超限 ⇒ 不直显（卡片原样，可见降级）。
 *  · **失败失败静默、可行动**：取字节失败 ⇒ 摘掉直显槽、卡片原样（下载键/预览腿
 *    仍在）⇒ 不造破图、不静默无反应。
 *
 *  ── 重绘零重拉（与 ② 同一纪律）──
 *  好友/群面按 `att.id` 复用**同一枚 objectURL**（`inlineObjectUrls`，有界 LRU）：
 *  气泡因增量刷新重建时，`<img>` 复用已解码 URL ⇒ 不重拉、不重解码。
 *  设备面 `ticketUrl` 自带票据缓存 ⇒ 同样不重取。
 *
 *  @param {HTMLElement} card 附件卡
 *  @param {any} att 已归一附件对象
 *  @param {string} kind `attStateOf` 的结果（`ready` / `device` / …）
 *  @param {{kind:'blob',file:File}|{kind:'path',path:string}|{kind:'none'}} [src] 设备面字节源
 *    （调用方 `attachmentCard` 已算好；缺席时本函数自算，**同一单点**）
 *  @returns {boolean} 是否挂了直显槽
 *
 *  ── ③ 预览盒（作者 2026-09-19 01:29 令：**按原图真实宽高比** + min/max 夹取）──
 *  · **预留**：`<img>` 元素在**字节请求发出之前**入 DOM（无 `src` ⇒ 只渲染 CSS 盒 =
 *    可见骨架）。盒比 = 原图真实比（夹取后），由 `inlineBoxFor` 单点算出（见上段常数）。
 *  · **新占位语义（显式申报，非静默变更）**：骨架期 CSS 回退盒 = 既有上限盒
 *    （`260 / 200`）；比例可知的**同一帧**里换成真实比 —— 本函数/`fillLocalThumbs`
 *    **先解码后点 `src`** ⇒ 图片从不以错误比例被绘制、绘制后**零重排**；骨架 ↔ 真帧
 *    之间恰好**一次**几何变更，量值 = 由原图 `W×H` 精确算出的预测值。
 *  · 📌 **取代关系（勿回退）**：imgmsg 批（2026-09-18）的「`aspect-ratio: 13/10` 固定盒 ⇒
 *    字节到达前后零位移（Δ=0）」**已被 01:29 令取代** ⇒ 真实比与 Δ=0 在数学上互斥
 *    （骨架期不可能预知真实比），故「零位移」改由「真帧后零重排 + 单次变更 = 预测值」
 *    承接（旧裁定记 provisional / archived，禁按旧口径描述本槽行为）。
 *  · ① **发送侧本机句柄**：`att.localFace`（**非枚举**属性，只可能挂在发送侧乐观面上）
 *    ⇒ 走本机 blob 腿（② 本地小图优先出帧）。无 `localFace` 的卡（= 接收侧全部、
 *    群面、设备面、历史卡片）⇒ **逐字现状**。
 *  `data-att-src` 是字节源的**读数面**（不参与任何行为分支）：
 *  `local-thumb` / `local-file` / `local-handle` / `server` / `server-cached` / `server-ticket`；
 *  `data-att-nat` / `data-att-ar` / `data-att-box` 是几何读数面（同上，非行为分支）。 */
function attachInlineImage(card, att, kind, src) {
  if (!card || !att) return false;
  /** 发送侧乐观面（本批唯一新腿）：本机字节句柄。 */
  const localFace = att.localFace || null;
  if (!localFace && kind !== 'ready' && kind !== 'device') return false;
  if (card.querySelector('.fm-att-inline')) return false; // 幂等（防同卡重入）
  const name = (att && att.name) ? String(att.name) : '';
  if (!isImageAttachmentName(name)) return false; // 非图片 ⇒ 零行为变化

  const box = el('div', 'fm-att-inline');
  box.dataset.attInline = '1';
  // ③ 骨架 = 这个 `<img>` 自身（无 `src` ⇒ 无内容，只剩 CSS 盒的底色/描边 ⇒ 可见骨架）。
  const img = document.createElement('img');
  img.alt = ''; // 骨架期无内容 ⇒ 装饰性（真名在字节就位时补上）
  box.appendChild(img);
  let mounted = false;
  /** @param {string} url @param {string} [tag] 字节源读数（`data-att-src`）
   *  @param {{w:number,h:number}|null} [dims] 已知内禀尺寸（省一次解码；缺席则本函数自取） */
  const mount = (url, tag, dims) => {
    if (mounted || !url) return;
    mounted = true;
    img.alt = name;
    if (tag) img.dataset.attSrc = tag;
    // ③ 先落地盒比再点 `src`（同一帧）⇒ 禁画后重排（见本函数头注「新占位语义」）。
    paintInlineFrame(img, url, dims || null);
  };
  // 兜底：任何绕过 `mount`/`fillLocalThumbs` 就点 `src` 的路径（未来腿）也不留
  // 「以回退比绘制、之后再改比」的画面 —— 幂等（`data-att-box` 已在场即不重复写）。
  img.addEventListener('load', () => {
    if (img.dataset.attBox) return;
    if (img.naturalWidth > 0 && img.naturalHeight > 0) applyInlineRatio(img, img.naturalWidth, img.naturalHeight);
  }, { once: true });
  img.addEventListener('error', () => box.remove(), { once: true });
  card.insertBefore(box, card.firstChild);

  if (kind === 'device') {
    // 设备面：字节 = **按字节源分派**（selfattach 批单点）——
    //   · blob 腿（A 腿 · 发送侧本机句柄）：`URL.createObjectURL(File)`，登记进有界 LRU
    //     （键 = transferId；同件重绘复用**同一枚 URL** ⇒ 不重解码）；
    //   · path 腿（收侧落点 / B′ 腿发送端路径）：既有 `nfTicket` ⇒ `/api/nf-file` 链**逐字保留**；
    //   · none（未完成 / 失败 / 无本机件）：不挂槽（状态位文案即说明面）。
    const s = src || deviceByteSourceOf(att);
    if (s.kind === 'none') { box.remove(); return false; }
    if (s.kind === 'blob') {
      bindOutHandleEvicted();
      const key = String((att && att.deviceTransferId) || (att && att.name) || '');
      mount(outInlineUrlOf(key) || rememberOutInlineUrl(key, URL.createObjectURL(s.file)), 'local-file');
      return true;
    }
    ticketUrl(s.path)
      .then((url) => { if (url) mount(url, 'server-ticket'); else box.remove(); })
      .catch(() => box.remove());
    return true;
  }

  // ① **发送侧乐观面**（imgmsg 批唯一新腿）：字节源 = 本机句柄（② 本地小图优先）。
  //    句柄尚未生成 ⇒ **不摘槽**（骨架在位 = ③ 的预留高度已经成立），生成完成由
  //    `fillLocalThumb` 在**同一枚 img 元素**上就地点 `src`（不重建、不重排）。
  //    🔴 本支只在 `att.localFace` 在场时可达，而该属性是**非枚举**且只由发送侧乐观
  //       面挂载 ⇒ 接收侧 / 群面 / 历史卡片**不可能**进入本支（逐字现状）。
  if (localFace && kind !== 'ready' && kind !== 'device') {
    localFace.img = img;
    // ③ 本机帧已在手 ⇒ 内禀尺寸同帧带上（`fillLocalThumbs` 解码时就量过，禁第二次解码）。
    const lfDims = (localFace.natW > 0 && localFace.natH > 0) ? { w: localFace.natW, h: localFace.natH } : null;
    if (localFace.url) mount(localFace.url, localFace.isThumb ? 'local-thumb' : 'local-file', lfDims);
    return true;
  }

  // 好友 / 群聊：字节 = 既有鉴权下载路由。
  const id = att.id ? String(att.id) : '';
  if (!id) { box.remove(); return false; }
  if (typeof att.size === 'number' && att.size > 0 && att.size > MAX_INLINE_IMAGE_BYTES) {
    box.remove(); // 超内联预算 ⇒ 可见降级（卡片原样）
    return false;
  }
  const cached = inlineObjectUrlOf(id);
  if (cached) {
    // ① A1-min：命中本机句柄（本机刚发出的那件）⇒ **零服务端往返**。
    mount(cached, localOutAttachmentIds.has(id) ? 'local-handle' : 'server-cached');
    return true;
  }
  api.downloadAttachment(id)
    .then(({ blob }) => {
      // ② 类型复判已删（msgfix 批 · 作者 2026-09-19 睡前令）：该路由的响应头
      // **契约上恒为** `application/octet-stream` ⇒ 旧的 `blob.type` 前缀判据恒假、
      // 直显腿恒不可达。判据保留在上游（`isImageAttachmentName(name)`）+ 体积上限。
      if (!blob
        || (typeof blob.size === 'number' && blob.size > MAX_INLINE_IMAGE_BYTES)) {
        box.remove();
        return;
      }
      mount(rememberInlineObjectUrl(id, URL.createObjectURL(blob)), 'server');
    })
    .catch(() => box.remove()); // 410 过期 / 网络 / 鉴权：卡片本身即降级面
  return true;
}

/** 好友/群图片附件的 objectURL 复用表（有界 LRU；淘汰即撤销，禁泄漏）。
 *  🔴 键 = 附件 id（同一次取字节 = 同一份字节 ⇒ 同一枚 URL），重绘零重拉。
 *  @type {Map<string, string>} */
const inlineObjectUrls = new Map();
/** 上限 = 同屏可见附件数的数倍；超出即撤销最久未用者（气泡滚动是 LRU 序）。 */
const INLINE_URL_MAX = 64;

/** @param {string} id */
function inlineObjectUrlOf(id) {
  const url = inlineObjectUrls.get(id);
  if (!url) return '';
  inlineObjectUrls.delete(id);
  inlineObjectUrls.set(id, url); // LRU touch
  return url;
}

/** @param {string} id @param {string} url */
function rememberInlineObjectUrl(id, url) {
  inlineObjectUrls.set(id, url);
  while (inlineObjectUrls.size > INLINE_URL_MAX) {
    const oldest = inlineObjectUrls.keys().next();
    if (oldest.done) break;
    const victim = inlineObjectUrls.get(oldest.value);
    inlineObjectUrls.delete(oldest.value);
    const stillUsed = document.querySelector(`.fm-att-inline img[src="${victim}"]`);
    if (!stillUsed) { try { URL.revokeObjectURL(victim); } catch { /* non-critical */ } }
    else { inlineObjectUrls.set(oldest.value, victim); break; } // 仍在屏上 ⇒ 不淘汰它
  }
  return url;
}

// ── 发送侧（A 腿）设备卡内联图的 objectURL 复用表（selfattach 批 · 片 2）────────────
// 定位：与好友面 `inlineObjectUrls` **同款有界 LRU + 同款屏幕在用的例外规则**（禁破图），
// 唯一差别是键 = `transferId`（发送侧句柄无附件 id、无路径可作稳定键）。
// 🔴 退场即撤销（三条路径，逐条给读数，见报告 §2.4）：
//   ① 本表溢出淘汰；② dropbox.js 句柄表淘汰（`dropbox-out-handle-evicted` 事件）；
//   ③ 页面刷新/关窗（整页销毁，浏览器随对象回收）。
/** @type {Map<string, string>} transferId → objectURL */
const outInlineUrls = new Map();
/** 上限 = 同屏可见附件数量级（与好友面同值口径）。 */
const OUT_INLINE_URL_MAX = 32;
let outEvictedBound = false;

/** 句柄表淘汰 ⇒ 撤销由该句柄派生的 objectURL（**仍在屏上者不撤**，防破图；与好友面同规则）。 */
function bindOutHandleEvicted() {
  if (outEvictedBound) return;
  outEvictedBound = true;
  document.addEventListener('dropbox-out-handle-evicted', (/** @type {CustomEvent} */ e) => {
    const key = e && e.detail ? String(e.detail.transferId || '') : '';
    if (!key) return;
    const url = outInlineUrls.get(key);
    if (!url) return;
    outInlineUrls.delete(key);
    const stillUsed = document.querySelector(`.fm-att-inline img[src="${url}"]`);
    if (!stillUsed) { try { URL.revokeObjectURL(url); } catch { /* non-critical */ } }
    else { outInlineUrls.set(key, url); } // 仍在屏上 ⇒ 不淘汰它
  });
}

/** @param {string} key @returns {string} */
function outInlineUrlOf(key) {
  const url = key ? outInlineUrls.get(key) : '';
  if (!url) return '';
  outInlineUrls.delete(key);
  outInlineUrls.set(key, url); // LRU touch
  return url;
}

/** @param {string} key @param {string} url @returns {string} */
function rememberOutInlineUrl(key, url) {
  if (!key) return url;
  outInlineUrls.set(key, url);
  while (outInlineUrls.size > OUT_INLINE_URL_MAX) {
    const oldest = outInlineUrls.keys().next();
    if (oldest.done) break;
    const victim = outInlineUrls.get(oldest.value);
    outInlineUrls.delete(oldest.value);
    const stillUsed = document.querySelector(`.fm-att-inline img[src="${victim}"]`);
    if (!stillUsed) { try { URL.revokeObjectURL(victim); } catch { /* non-critical */ } }
    else { outInlineUrls.set(oldest.value, victim); break; } // 仍在屏上 ⇒ 不淘汰它
  }
  return url;
}

// ── ① 好友图片**发送侧**本机字节（imgmsg 批 · 作者 2026-09-18 五项全裁）───────────
// 决策点 ① A1-full (min)：本机刚发出的图片，字节来源 = **本机已有的那份**——
//   · 乐观面（上传在飞）用 ② 的「本地小图」先出帧，字节不来自网络；
//   · 确认面（服务端行已落）沿用**既有** `inlineObjectUrls`（键 = 附件 id）：
//     出帧前把本机句柄登记进那张表 ⇒ `attachInlineImage` 命中缓存，
//     **不再走 `api.downloadAttachment`**（= 作者症状①里「把自己刚传的图整件取回来」
//     那一段往返消失）。
// 本表只记「这条 id 的字节是本机的」——供 `data-att-src` 读数与语义自证用，
// **不参与任何行为分支**（行为分支的唯一判据 = 字节表命中与否，与改前同一行）。
// 🔴 有界（FIFO ≤ `INLINE_URL_MAX`，与字节表同上限；同族先例 = 去重表口径）。
/** @type {Set<string>} 本机句柄登记的附件 id */
const localOutAttachmentIds = new Set();
/** @type {string[]} FIFO 序（淘汰最老者，防无界增长） */
const localOutAttachmentOrder = [];

/** ② 本地小图的生成参数：**同一份**（唯一常量点）。长边 640 覆盖 260px 槽的
 *  2× 物理像素（260 × 2 = 520 ≤ 640），观感不糊而字节极小。 */
const LOCAL_THUMB_EDGE = 640;
const LOCAL_THUMB_QUALITY = 0.82;

/** ② 发送端本地小图（**唯一生成点**）：`File` ⇒ 有界长边的 image Blob + **原图内禀尺寸**。
 *
 *  走 `createImageBitmap` 的解码线程（不阻塞主线程、不在 UI 帧里做像素搬运）；
 *  WebP 优先（保 alpha；透明 PNG 不被 JPEG 压成黑底），JPEG 兜底。
 *  🔴 能力缺失 / 解码失败 / 编码失败 ⇒ 返回 `null`（调用方回落**原文件句柄**：
 *     首帧照样出，只是字节大一些）——**绝不为难用户**、绝不阻断发送链。
 *  ⚠ `w`/`h` 是**原图**的（不是小图的）：③ 的气泡预览盒按原图比出盒，而本函数
 *     解码时手上正好就是原图 ⇒ 顺带带出，免第二次解码（本批唯一新增回流面）。
 *  @param {File|Blob} file
 *  @returns {Promise<{blob:Blob, w:number, h:number}|null>} */
async function makeLocalThumbFrame(file) {
  try {
    if (!file || typeof createImageBitmap !== 'function') return null;
    const bmp = await createImageBitmap(file);
    const w = bmp.width || 0;
    const h = bmp.height || 0;
    if (!w || !h) { if (bmp.close) bmp.close(); return null; }
    const scale = Math.min(1, LOCAL_THUMB_EDGE / Math.max(w, h));
    const tw = Math.max(1, Math.round(w * scale));
    const th = Math.max(1, Math.round(h * scale));
    const cv = document.createElement('canvas');
    cv.width = tw;
    cv.height = th;
    const ctx = cv.getContext('2d');
    if (!ctx) { if (bmp.close) bmp.close(); return null; }
    ctx.drawImage(bmp, 0, 0, tw, th);
    if (bmp.close) bmp.close();
    const encode = (type) => new Promise((res) => {
      try { cv.toBlob((b) => res(b && b.size > 0 ? b : null), type, LOCAL_THUMB_QUALITY); }
      catch { res(null); }
    });
    const blob = (await encode('image/webp')) || (await encode('image/jpeg'));
    if (!blob) return null;
    return { blob, w, h };
  } catch { return null; }
}

/** 登记「这条附件 id 的字节在本机」（LRU/字节表仍是唯一字节源，本表只做标记）。 */
function markLocalOutAttachment(id) {
  const k = String(id || '');
  if (!k || localOutAttachmentIds.has(k)) return;
  localOutAttachmentIds.add(k);
  localOutAttachmentOrder.push(k);
  while (localOutAttachmentOrder.length > INLINE_URL_MAX) {
    const oldest = localOutAttachmentOrder.shift();
    if (oldest !== undefined) localOutAttachmentIds.delete(oldest);
  }
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

// ── 设备面（legacy 本机落盘件）预览 / 下载腿 ────────────────────────────────
// 作者令 2026-09-17 12:31（本批判据）：设备面附件行为 = 好友/群面**对齐** ——
//   · 点击 ⇒ `attachmentPreview.previewBlob`（与好友面**同一条**渲染腿）；
//   · 下载 ⇒ 既有 `.fm-att-dl` 键族 + `saveBlob`（同款呈现/反馈）。
// 🔴 字节只走**既有**路由，禁新造第三条：
//   `nfTicket.ticketUrl(path)`（= `/api/nf-file?path=…&ticket=…`，与设备内联图片腿
//   同一个字节源，见 `attachInlineImage` 设备支）——回落腿才是改前的 `previewLocalPath`。
// 🔴 旧登记「legacy 卡无下载键属设备面设计」已被本令推翻（出处 = 作者 2026-09-17 12:31 令；
//   原注见 `attachmentCard` 设备分支的推翻说明）。

/** 本机落盘件的字节（票据路）。失败/拒绝 ⇒ `null`（**不抛**：两条腿各有自己的降级面）。
 *
 *  `nf-file` 是**扩展名白名单**端点（服务端 `NfFileAllowedExt`，
 *  `WebSocketRoutes.scala:5203-5241`）⇒ 白名单外的类型（.md/.csv/.yaml/.txt…）会
 *  400「File type not allowed」⇒ 返回 `null` ⇒ 预览腿回落 `previewLocalPath`、
 *  下载腿**根本不挂键**（`canFetchLocalBytes`，见 attachmentPreview.js）。
 *  同一惯例见 `input.js:1893-1907`（nf-file 先、readFile 后）。
 *  @param {string} path @returns {Promise<Blob|null>} */
async function savedPathBlob(path) {
  try {
    const resp = await fetch(await ticketUrl(path));
    if (!resp.ok) return null;
    return await resp.blob();
  } catch { return null; }
}

/** 设备面（legacy）预览：**与好友/群同路**（`previewBlob`）。
 *
 *  两条字节腿（都是**既有**路，禁第三条）——分派判据 = 该件的票据路由可服务性
 *  （`canFetchLocalBytes`，见 attachmentPreview.js）：
 *   ① blob 腿（image/pdf/docx/xlsx/pptx/epub…）= **本批新主路**：票据路由字节
 *      （`savedPathBlob`）⇒ `previewBlob`（判据/渲染腿与好友面同族，点开即 Canvas 预览）；
 *   ② 文本腿（markdown/csv/yaml/json/code…）= **改前那条路逐字**（`previewLocalPath`
 *      ⇒ `workspace-open-item` ⇒ Canvas `pop.readFile`）：它们的字节不经 nf-file 白名单，
 *      故不在此发那次注定被拒的请求（零多余请求、零行为变化 = ④ 的要求）。
 *  路由**运行期**失败（票据铸造故障 / 文件已被删除 / 白名单外的 blob 腿扩展名如 .doc）
 *      ⇒ 仍回落 ② 这条既有路（保证「点得动」，同 `input.js:1893-1907` 的两段式惯例）。
 *  📌 selfattach 批（片 2 · A 腿）：**新增 blob 支**（发送侧本机 `File` 句柄在手 ⇒ 字节已在手，
 *     无需任何路由）——`previewBlob` 仍是**同一条**渲染腿（与好友面同族）；path 支
 *     **逐字保留**（判据源 `canFetchLocalBytes` 与三条分支一字未改）。
 *  @param {any} att @param {HTMLElement} card
 *  @param {{kind:'blob',file:File}|{kind:'path',path:string}|{kind:'none'}} src 字节源（单点判定）
 *  @returns {Promise<boolean>} true = 需要**可见降级**文案（同 `previewFriendAttachment` 契约） */
async function previewDeviceSavedAttachment(att, card, src) {
  const s = src || { kind: 'none' };
  if (s.kind === 'none') return true;
  const title = (att && att.name)
    || (s.kind === 'path' ? String(s.path).split('/').pop() : '')
    || '';
  const id = devicePreviewIdOf(att, s);
  if (card.dataset.attPreviewBusy === '1') return false;
  // 已在面板里预览这一件 ⇒ 只激活，**不取第二份字节**（同一次点击 = 同一次取数）。
  if (isPreviewOpen(id)) return false;
  card.dataset.attPreviewBusy = '1';
  try {
    // ① blob 腿（selfattach 片 2）：发送侧本机句柄 = 字节已在手，**零路由** ⇒ 直接进既有渲染腿。
    if (s.kind === 'blob') {
      const r = await previewBlob({ id, title, fileName: title, blob: s.file });
      if (r === 'unsupported') {
        modalToast(t('messages.attachPreviewUnsupported', { name: title }));
        return false; // 文案已就位，不再叠一条通用失败提示
      }
      return r !== 'ok';
    }
    if (!canFetchLocalBytes(s.path)) return previewLocalPath({ path: s.path, title }) !== 'ok'; // ② 文本腿
    const blob = await savedPathBlob(s.path);
    if (!blob) return previewLocalPath({ path: s.path, title }) !== 'ok'; // 路由运行期失败 ⇒ 回落
    const r = await previewBlob({ id, title, fileName: title, blob });
    if (r === 'unsupported') {
      modalToast(t('messages.attachPreviewUnsupported', { name: title }));
      return false; // 文案已就位，不再叠一条通用失败提示
    }
    return r !== 'ok';
  } finally {
    card.dataset.attPreviewBusy = '0';
  }
}

/** 设备面（legacy）下载键：与好友面同款键/同款就地反馈，字节 = 同一条票据路由。
 *
 *  与好友面 `downloadAttachment` 的**两处有意差异**（各有依据，非两套逻辑）：
 *   · **无 410 分支**：410 = 服务端附件的「已过期」终态语义（好友面远端件）；本机落盘件
 *     不存在「过期」，票据路由给的是 404/400/401 ⇒ 一律按**可重试**处置（同好友面
 *     「传输/本地失败」那一格的处置面，不误标成「已过期」）。
 *  · **无 `ackAttachmentLanded`**：E4 回执入参 = 附件 id + 服务端声明 digest（§F.1b），
 *    legacy 行**两者皆无** ⇒ 不发回执（禁造无 id 的回执）。
 *  📌 selfattach 批（片 2 · A 腿）：**新增 blob 支**（发送侧 `File` 直接交 `saveBlob`，
 *    与好友面**同一条**保存腿）；path 支（票据路由）**逐字保留**。
 *  @param {HTMLElement} card @param {HTMLButtonElement} btn @param {HTMLElement} note
 *  @param {{kind:'blob',file:File}|{kind:'path',path:string}|{kind:'none'}} src */
async function downloadDeviceSavedAttachment(att, card, btn, note, src) {
  const s = src || { kind: 'none' };
  if (s.kind === 'none') return;
  if (card.dataset.busy === '1') return;
  card.dataset.busy = '1';
  btn.disabled = true;
  note.classList.remove('visually-hidden-note');
  note.textContent = t('messages.attachDownloading');
  try {
    if (s.kind === 'blob') {
      saveBlob(s.file, (att && att.name) || '');
      note.textContent = t('messages.attachDownloaded');
      return;
    }
    const blob = await savedPathBlob(s.path);
    if (!blob) throw new Error('nf-file refused');
    saveBlob(blob, (att && att.name) || String(s.path).split('/').pop());
    note.textContent = t('messages.attachDownloaded');
  } catch {
    // 可重试（就地文案，不升终态、不动传输状态位：下载面与传输面互不覆盖）。
    note.textContent = t('messages.attachDownloadFailed');
  } finally {
    card.dataset.busy = '0';
    btn.disabled = false;
  }
}

/** 气泡内容填充（**单点**：新建与就地升级共用，禁两套渲染）。 */
function fillBubble(bubble, m, conv) {
  bubble.innerHTML = '';
  const atts = m && Array.isArray(m.attachments) ? m.attachments : [];
  const raw = (m && m.body) || '';
  // 引用块（msgmenu 一期）：信封在正文**首行** ⇒ 块渲染在正文之前，正文只渲染**回复**
  // （信封不重复显示）。无信封 ⇒ 逐字走下方既有两行（零行为变化）。
  const quote = parseQuoteBody(raw);
  const quoteCoord = quote ? quoteCoordOf(m, conv) : null;
  if (quote) bubble.appendChild(quoteBlockEl(quote, quoteCoord));
  const body = quote ? quote.reply : raw;
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
  // 设备分支（devnotif 批 2026-09-16）：设备窗**无好友档案**（`conv.friend` 恒缺席，见
  // `deviceConvs` 的窗构造）⇒ 修前落到下面的 direct 兜底（`fid` 缺席 ⇒ `null`）⇒
  // 「对方所发」判据对设备窗**三重恒假**（取证位 ②因①）。设备面的方向证据 =
  // `senderDeviceId`（契约 §8.3：**仅**设备会话行/帧携带它；legacy `DropboxMessage` 永
  // 不带）⇒ 浏览器侧要判设备方向，先要 `frameMessage` 把它透出来（同批加性补键）。
  // 🔴 与 `adaptDeviceMessage` 的方向重算**同一条纪律**（契约 §8.3 逐字：「方向（out/in）
  // 由 `sender_device_id == 本机 id` 重算，不落库」）——同一字段、同一比对基准
  // （`selfDeviceId()` 单点），只是消费点不同（那里画面向，这里画未读）。
  // 🔴 证据缺席（键缺席 / 空串）⇒ 返回 `null`（**不判**）：调用方按 `=== false` 读 ⇒
  // 不计（禁把「无证据」当「对方所发」白涨角标）。本机身份缺席（`selfDeviceId()` 为空
  // 串）⇒ 比对落 false = 「非本机所发」——与 `adaptDeviceMessage` 同一降级方向
  // （无本机身份时**不**把一切判成本机所发，那会把对端消息全画到右侧）。
  // ⚠ 本支**在**函数首行的 `senderId` 证据闸之**后**（闸保留原样不动）：`senderId`
  // 缺席的帧（本机自播形态，见 `resolveOut` 注释）在闸处即返回 `null` ⇒ 走不到本支
  // ⇒ 未读面按 `=== false` 读 = **不计**（保守向，与「self 不计未读」同向）。
  if (conv && conv.kind === 'device') {
    const did = m && m.senderDeviceId;
    if (did === undefined || did === null || did === '') return null;
    return String(did) === selfDeviceId();
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
  fillBubble(bubble, m, conv);
  wrap.appendChild(bubble);

  // msgmenu 一期：被引消息 id 挂**气泡节点** dataset（🔴「跳转」的**唯一**预留挂点；
  // 跳转逻辑本批不写）。同一函数也在 `keyedDiff` 复用分支调用（收敛点单一）。
  syncQuoteDataset(wrap, m, conv);

  // #290 addendum §3.3: bubble right-click = primary desktop entry for
  // 转发给 agent (same action as the hover/header buttons - one handler).
  // msgmenu 一期（作者 2026-09-18 四答）：菜单壳扩为 4 项 —— 复制 / 转发给智能助手
  // （**既有项保留、行为一字不变**）/ 引用 / 多选转发。
  // 🔴 零删除、零撤回项（含文案键）：本批硬禁，禁在此回填。
  wrap.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    showPopupMenu(e.clientX, e.clientY, [
      { label: t('messages.copy'), onClick: () => { void copyBubbleText(wrap); } },
      { label: t('messages.forwardToAgent'), onClick: () => forwardBubble(wrap, conv) },
      { label: t('messages.quote'), onClick: () => startQuote(wrap, conv) },
      { label: t('messages.multiSelect'), onClick: () => enterSelection() },
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
  // 多选态下新到/新渲染的消息也带勾选面（与既有节点同一条 `attachCheck`）。
  if (selectionMode) attachCheck(wrap);
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
        if (b) fillBubble(b, m, conv);
        if (m.body !== undefined) node.dataset.body = m.body || '';
      }
      // msgmenu 一期：复用节点的引用挂点与可用性一并收敛（不变量「新 UI 态必须挂
      // dataset 并在此处收敛」——见 `keyedDiff` 头注）。
      syncQuoteDataset(node, m, conv);
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
    // 附件上传卡（attachcl 批）：**好友窗 / 群窗 / 设备窗的唯一挂载点**（本函数服务三种
    // 会话面）⇒ 三面共用同一渲染器（`attachUpload.renderUploadCards`）。
    // 📌 uxconsist Phase B（§4.1-#4）：挂载判据**放开到设备窗**（改前设备窗不挂 ⇒ 动作面
    // 与反馈面结构性脱节）；设备腿的传输链**零触碰**（仍由 dropbox.js 单点执行，本挂载点
    // 只承载「显示」，见 `registerExternalTransfer`）。
    if (attachAvailable(conv) || conv.kind === 'device') renderUploadCards(flow, conv.conversationId, conv.kind);
    // 回执槽位（R5 两格 + 设备面）：**唯一挂载点**（与上传卡同一条纪律）。零网络 ——
    // 只把**已到手**的回执态补画到本机所发气泡（取数在 `refreshConvReceipts`）；数据
    // 未到 / 该消息无确认 ⇒ 不画（无空槽）。
    applyConvReceipts(conv);
    // msgmenu 一期：引用块可用性在**每次** diff 后重判（加载更早 / 增量补齐都会改变答案）。
    syncQuoteStates(flow);
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

// ══════════════════════════════════════════════════════════════════════════
// msgmenu 一期（客户端）· 作者 2026-09-18 19:2x 四答（**唯一规格**）：
//   「引用——必须可跳转」（跳转前置 = 外仓 `neblink-server` 加 1 个可选引用字段；
//   字段落地前**跳转联调阻塞** ⇒ 🔴 本批**不写跳转**、禁猜外仓字段名/协议形态）；
//   「多选转发——落点 = 其他会话」（含选目标界面）；
//   🔴 删除（本地/双侧）与撤回**先不做** ⇒ 本段零删除/零撤回语义、零 tombstone、
//   零本地名单（任何入口、任何文案、任何键）。
// ──────────────────────────────────────────────────────────────────────────

// ── 引用 · 通道真实形态（开工现读，行号 = 现读；禁凭 recon 转述当读数）──────────
//  · 统一工厂 `reference.js:97-177 makeReference()`；`friend-message` 分支 `:152-174`
//    ⇒ `{ type:'ref', refType:'friend-message', id:'ref:fm:<messageId>',
//         source:{conversationId,messageId,friendName,friendNeblinkId,direction},
//         content:{preview,fullText}, meta:{icon,typeLabel,date},
//         display:{label:'来自 {name}', preview, pageBadge:date} }`（**id 钉在被引消息上**）。
//  · 输入框面渲染器 = `renderRefBlock(ref,{mode:'input'})` → `renderFriendInputRef`
//    （`reference.js:293-383`）：类型图标 + 「来自 {name}」 + 日期角 + 正文 2 行截断
//    + 「装不下才出现的」展开键 + **× 移除** ⇒ 「输入框面显示被引消息摘要 + 可取消」
//    两件都已现成（零新渲染器）。
//  · 消息内渲染器 = `renderRefBlock(ref,{mode:'message'})`（`reference.js:237-240` /
//    `:560-607`；既有消费者 = 主对话气泡 `chat.js:322` 与重挂载 `persistence.js:282`）。
//  · 🔴 既有 `appendRefToActiveView`（`input.js:1820-1828`）的落点 = **主对话（agent）
//    输入框**的 `attPreview` 槽（线上序列化 = agent 帧 `refs:[…]`，`input.js:793-800`）；
//    好友会话窗**没有**该槽，且好友消息 REST 载荷只有 `{body,attachments,clientMsgId}`
//    （`friendsApi.js:197-204 sendPayload`）⇒ **好友消息无法经 agent 通道出站**。
//  ⇒ **接法判定**（本批取 recon §3.3 路 (a)：纯客户端正文承载，服务端零改动）：
//     ① 复用**同一个工厂** `makeReference`（禁第二套引用模型/第二份 pretty-print）；
//     ② 复用**同一个输入框渲染器** `renderRefBlock(mode:'input')` 作「引用态」载面；
//     ③ 出站 = 正文**首行信封**（id + 上下文摘要）⇒ 本侧与对端（同一客户端）都能渲染
//        引用块、都能从 `dataset` 读出被引 id；**零 wire 字段、零外仓改动、零协议面改动**；
//     ④ 🔴 跳转本批不写（外仓字段落地前联调阻塞）：信封里的 id 是**预留挂点**，
//        不是跳转实现 —— 禁写「点了跳不动」的半成品入口。
// ⚠ 已申报后果（报告「开放项」）：正文首行带信封 ⇒ ①「复制」复制到原始正文（含信封，
//    既有行为不变）；② 老客户端（无引用渲染）把它当普通文本行显示（降级可见、不崩）。
const QUOTE_LINE_RE = /^> \[引用 #([^\]\s]+)\](?:[ \t]+(.*))?$/;

/** 单行化（信封只占**首行** ⇒ 摘要内的换行/连续空白折成一个空格）。 */
function oneLine(s, max) {
  const flat = String(s || '').replace(/\s+/g, ' ').trim();
  const n = max || 80;
  return flat.length > n ? flat.slice(0, n) + '…' : flat;
}

/** 引用态 → 出站正文（信封首行 + 空行 + 用户正文）。**唯一**编码点。 */
function buildQuoteBody(ref, text) {
  const src = ref.source || {};
  const ctx = [
    oneLine(src.friendName, 40),
    ref.meta?.date || '',
    oneLine(ref.content?.preview, 80),
  ].filter(Boolean).join(' · ');
  return `> [引用 #${src.messageId}]${ctx ? ' ' + ctx : ''}\n\n${text}`;
}

/** 出站正文 → 引用关系（无信封 ⇒ null）。本侧刷新后与**对端**的**唯一**解析点。 */
function parseQuoteBody(body) {
  const raw = String(body || '');
  const nl = raw.indexOf('\n');
  const first = nl >= 0 ? raw.slice(0, nl) : raw;
  const m = QUOTE_LINE_RE.exec(first);
  if (!m) return null;
  return {
    messageId: m[1],
    context: (m[2] || '').trim(),
    reply: nl >= 0 ? raw.slice(nl + 1).replace(/^\r?\n/, '') : '',
  };
}

/** 外仓结构化引用键的**收侧**取值闸（`replyToMessageId`：i64；缺席 / null / 非法 ⇒ ''）。
 *  与 `frameMessage` / REST 面同款加性纪律：键缺席 = 旧形态（无结构化坐标）⇒ 不猜值。 */
function wireQuoteId(v) {
  if (v === undefined || v === null || v === '') return '';
  const n = Number(v);
  return (Number.isInteger(n) && n > 0) ? String(n) : '';
}

/** 🔴 引用**坐标**（`(messageId, conversationId)` 对）——跳转的**唯一**判据源。
 *
 *  来源优先级（双读：结构化字段优先、信封兜底）：
 *   ① 外仓结构化字段对（wire `replyToMessageId` + `replyToConversationId`；帧面经
 *      `frameMessage` 平铺透传）⇒ 坐标 = 写库当时解析出的目标会话；
 *   ② 正文首行信封的 id（`parseQuoteBody`）+ **本消息所在会话**兜底 ⇒ 坐标会话退化为
 *      本会话（旧服 / 旧网关 / 群腿：外仓 D-6 刻意不带该键 ⇒ 结构化坐标不可得）。
 *  🔴 客户端**不猜**会话坐标：没有结构化字段时，兜底值 = 本消息所在会话（同会话引用的
 *  恒真取值），绝不凭空造第三个会话 id。
 *  @returns {{messageId: string, conversationId: string, source: 'wire'|'envelope'}|null}
 */
function quoteCoordOf(m, conv) {
  const q = parseQuoteBody(m && m.body);
  const wireId = wireQuoteId(m && m.replyToMessageId);
  const id = wireId || (q ? String(q.messageId) : '');
  if (!id) return null;
  const wireConv = (m && typeof m.replyToConversationId === 'string' && m.replyToConversationId)
    ? m.replyToConversationId
    : '';
  const own = wireConv || (conv && conv.conversationId) || openConvId || '';
  return { messageId: id, conversationId: own, source: wireId ? 'wire' : 'envelope' };
}

/** 被引消息是否仍在**当前已载窗口**内（降级显示判据 · 单点）。
 *  窗口 = `chatMsgs`（本文件 `:313`；加载更早消息会扩充它 ⇒ 每次 keyed diff 后重判）。
 *  🔴 坐标会话 ≠ 当前会话 ⇒ 目标**必然**不在本窗已载窗口内（跨会话目标由跳转腿
 *  按需回退/切会话取，不在本判据内）。 */
function quoteTargetExists(id, conversationId) {
  const k = String(id || '');
  if (!k) return false;
  if (conversationId && openConvId && conversationId !== openConvId) return false;
  return chatMsgs.some(m => String(m.id) === k);
}

/** 引用块可用性落地（**单点**）：dataset = QA/跳转挂点，class/文案只管视觉。 */
function paintQuoteState(box, available) {
  box.dataset.quoteState = available ? 'available' : 'unavailable';
  const note = box.querySelector('.fm-quote-note');
  if (!note) return;
  note.textContent = available ? '' : t('messages.quoteUnavailable');
  note.hidden = available;
}

/** 🔴 可点性落地（**单点** · 作者令「必须可跳」＋「禁点了没反应」）：
 *  只有 `data-quote-jump="ready"`（坐标可解析且目标会话在本机可见集内）才给**按钮语义**
 *  ＋键盘可达（role/tabindex）；不可得 ⇒ 撤除按钮语义（不留半成品入口）。
 *  事件监听**不在此挂**（节点会被 keyed diff 复用 ⇒ 挂这里会累积多份）——
 *  监听在 [[quoteBlockEl]] 一次性挂上，处理函数按 dataset 判活（幂等、可复用）。 */
function paintJumpAffordance(box) {
  const ready = box.dataset.quoteJump === 'ready';
  if (ready) {
    box.setAttribute('role', 'button');
    box.setAttribute('tabindex', '0');
    const txt = (box.querySelector('.fm-quote-text') || /** @type {any} */ ({})).textContent || '';
    box.setAttribute('aria-label', t('messages.quoteJumpAria', { text: txt }));
  } else {
    box.removeAttribute('role');
    box.removeAttribute('tabindex');
    box.removeAttribute('aria-label');
  }
  return ready;
}

/** 引用跳转可用性判据（**单点**）：坐标可解析 ∧ 目标会话 ∈ {当前会话} ∪ 本机会话集。
 *  不可得 ⇒ `none`（不给可点入口）。 */
function quoteJumpState(coord) {
  if (!coord || !coord.messageId) return 'none';
  const cid = coord.conversationId;
  if (!cid) return 'none';
  if (cid === openConvId) return 'ready';
  const known = conversations.some(c => c && c.conversationId === cid && c.kind !== 'device');
  return known ? 'ready' : 'none';
}

/** 气泡内引用块（**单点渲染器**；零新色值 —— 材质取既有 token）。
 *  🔴 不用左缘色条（设计硬约束「无 accent bars」）⇒ 既有卡面 + 发丝描边分组。 */
function quoteBlockEl(q, coord) {
  const box = el('div', 'fm-quote-block');
  const c = coord || { messageId: q.messageId, conversationId: openConvId || '' };
  box.dataset.refMessageId = c.messageId;
  if (c.conversationId) box.dataset.refConversationId = c.conversationId;
  box.dataset.quoteJump = quoteJumpState(c);
  box.dataset.quoteJumpSource = c.source || '';
  box.appendChild(el('div', 'fm-quote-text', q.context || t('messages.quotePlaceholder')));
  box.appendChild(el('div', 'fm-quote-note', ''));
  paintJumpAffordance(box);
  // 一次性挂载（节点复用安全）：处理函数按当前 dataset 判活。
  box.addEventListener('click', () => { void jumpToQuote(box); });
  box.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ' && e.key !== 'Spacebar') return;
    e.preventDefault();
    void jumpToQuote(box);
  });
  paintQuoteState(box, quoteTargetExists(box.dataset.refMessageId, box.dataset.refConversationId));
  return box;
}

/** keyed diff 之后统一重判引用可用性 + 可点性（加载更早 / 增量补齐 / 节点复用 /
 *  切会话都会改变答案）。
 *  🔴 遵守既有不变量（`keyedDiff` 头注的「新 UI 态必须挂 dataset 并在 keyed diff 处
 *  一并收敛」/ 复用分支）：否则刷新/复用后丢态。 */
function syncQuoteStates(flow) {
  for (const node of flow.querySelectorAll('.fm-msg[data-ref-message-id]')) {
    const id = node.dataset.refMessageId || '';
    const cid = node.dataset.refConversationId || '';
    const available = quoteTargetExists(id, cid);
    const jump = node.dataset.quoteJump === 'ready' ? 'ready' : 'none';
    node.dataset.quoteState = available ? 'available' : 'unavailable';
    const box = node.querySelector('.fm-quote-block');
    if (!box) continue;
    box.dataset.refMessageId = id;
    if (cid) box.dataset.refConversationId = cid;
    box.dataset.quoteJump = jump;
    paintJumpAffordance(box);
    if (box.dataset.quoteState !== (available ? 'available' : 'unavailable')) paintQuoteState(box, available);
  }
}

/** 引用挂点 + 可用性 + 可点性（气泡节点 `dataset`）——`bubbleEl` 与 `keyedDiff`
 *  复用分支**共用同一函数**（禁两处各写一份解析/挂点，避免漂移）。 */
function syncQuoteDataset(node, m, conv) {
  const coord = quoteCoordOf(m, conv);
  if (!coord) {
    delete node.dataset.refMessageId;
    delete node.dataset.refConversationId;
    delete node.dataset.quoteState;
    delete node.dataset.quoteJump;
    return;
  }
  node.dataset.refMessageId = coord.messageId;
  node.dataset.refConversationId = coord.conversationId;
  node.dataset.quoteJump = quoteJumpState(coord);
  node.dataset.quoteState = quoteTargetExists(coord.messageId, coord.conversationId) ? 'available' : 'unavailable';
  const box = node.querySelector('.fm-quote-block');
  if (box) {
    box.dataset.refMessageId = coord.messageId;
    if (coord.conversationId) box.dataset.refConversationId = coord.conversationId;
    box.dataset.quoteJump = node.dataset.quoteJump;
    box.dataset.quoteJumpSource = coord.source;
    paintJumpAffordance(box);
  }
}

// ── 引用跳转（quotejump 批 · 作者令「必须可跳」）────────────────────────────
/** 定向回退拉取的步数上限（与既有 `loadOlderMessages` / `probeOlderHistory` 同族上限）。 */
const QUOTE_JUMP_MAX_STEPS = 20;
let quoteFlashTimer = 0;

/** 目标节点查表（当前窗内）。 */
function quoteNodeById(id) {
  if (!modalEls || !id) return null;
  return /** @type {HTMLElement|null} */ (modalEls.flow.querySelector(`.fm-msg[data-message-id="${CSS.escape(String(id))}"]`));
}

/** 滚动到目标节点（**在 `.fm-flow` 内**滚动，非 window）+ 一次高亮。
 *  🔴 不使用 `scrollIntoView`：它会把滚动写进**任一** overflow:hidden 祖先
 *  （既有教训：相机型/含裁剪祖先的落点全错）⇒ 只改 flow.scrollTop 这一处。 */
function revealQuoteTarget(node) {
  const flow = modalEls && modalEls.flow;
  if (!flow || !node) return false;
  const fr = flow.getBoundingClientRect();
  const nr = node.getBoundingClientRect();
  const max = Math.max(0, flow.scrollHeight - flow.clientHeight);
  const delta = (nr.top - fr.top) - Math.max(0, (flow.clientHeight - nr.height) / 2);
  flow.scrollTop = Math.max(0, Math.min(max, flow.scrollTop + delta));
  node.classList.remove('fm-quote-target');
  void node.offsetWidth; // 重放动画（同一节点连点两次）
  node.classList.add('fm-quote-target');
  if (quoteFlashTimer) clearTimeout(quoteFlashTimer);
  quoteFlashTimer = setTimeout(() => node.classList.remove('fm-quote-target'), 1800);
  return true;
}

/** ② 不在已载窗口 ⇒ **按需回退拉取**（复用既有 keyset 向后走法，有界 ≤20 步 + 命中即停）。
 *  🔴 每步复查会话未被切走；退到 `after<=0` 或服务端返回空页仍未见 ⇒ **确定态：不在了**。
 *  @returns {Promise<boolean>} true = 目标已进入窗口并渲染 */
async function fetchQuoteTarget(convId, targetId) {
  let after = Math.max(0, oldestLoadedId - 1 - HISTORY_WINDOW);
  let steps = 0;
  while (steps < QUOTE_JUMP_MAX_STEPS) {
    let batch = [];
    try { batch = await api.getMessages(convId, { after, limit: HISTORY_WINDOW }); }
    catch { return false; } // 取数失败 ⇒ 不假跳（降级由调用方给可见反馈）
    if (openConvId !== convId || !modalEls) return false; // 会话已换/窗已关
    const fresh = (batch || []).filter(m => !chatMsgs.some(x => String(x.id) === String(m.id)));
    if (fresh.length) {
      chatMsgs = fresh.concat(chatMsgs);
      oldestLoadedId = Number(chatMsgs[0].id) || oldestLoadedId;
      prependMessages(fresh);
      if (chatMsgs.some(m => String(m.id) === String(targetId))) return true;
    }
    if (after <= 0) return false;                  // 退到表首仍未见 ⇒ 确定态
    if ((batch || []).length === 0) return false;  // 服务端水位已到（空页）
    after = Math.max(0, after - HISTORY_WINDOW);
    steps++;
  }
  return chatMsgs.some(m => String(m.id) === String(targetId));
}

/** ③ 做不到 ⇒ **诚实降级**：块失去可点语义 + 「原消息不可用」+ 一次性**可见**反馈
 *  （🔴 禁静默、禁「点了没反应」）。 */
function degradeQuoteJump(box) {
  box.dataset.quoteJump = 'none';
  box.dataset.quoteJumpOutcome = 'unavailable';
  paintJumpAffordance(box);
  paintQuoteState(box, false);
  modalToast(t('messages.quoteUnavailable'));
  return false;
}

/** 🔴 引用跳转（**唯一**实现 · 作者令「必须可跳」）。三条路径：
 *   ① 目标在本会话**已载窗口** ⇒ 滚动到它（+ 高亮）；
 *   ② 不在已载窗口 ⇒ 定向回退拉取（[[fetchQuoteTarget]]）后真跳；
 *   ③ 坐标会话 ≠ 当前会话 ⇒ 先切到目标会话（本机可见集内）再定位；
 *   ④ 都到不了 ⇒ 诚实降级（[[degradeQuoteJump]]，不留可点入口）。
 *  读数面：`data-quote-jump-outcome` = `hit` / `unavailable`（QA 二值判据）。
 *  @returns {Promise<boolean>} true = 真跳（位移 + 高亮已发生） */
async function jumpToQuote(box) {
  if (!box || box.dataset.quoteJump !== 'ready') return false;
  const id = box.dataset.refMessageId || '';
  const cid = box.dataset.refConversationId || '';
  if (!id || !cid) return false;
  // ③ 跨会话：目标不在当前会话 ⇒ 切过去（会话集内）再定位；不在会话集 ⇒ 降级。
  if (cid !== openConvId) {
    const target = conversations.find(c => c && c.conversationId === cid && c.kind !== 'device');
    if (!target) return degradeQuoteJump(box);
    await openConversation(cid, null);
    if (openConvId !== cid || !modalEls) return false; // 切会话失败/被抢 ⇒ 不假跳
  }
  let node = quoteNodeById(id);
  if (!node) {
    const found = await fetchQuoteTarget(cid, id);
    if (openConvId !== cid || !modalEls) return false;
    if (found) node = quoteNodeById(id);
  }
  if (!node) return degradeQuoteJump(box);
  if (modalEls) syncQuoteStates(modalEls.flow); // 命中 ⇒ 相关块可用性一并收敛
  revealQuoteTarget(node);
  box.dataset.quoteJumpOutcome = 'hit';
  return true;
}

// ── 引用态（输入框面：被引消息摘要 + 可取消）────────────────────────────
/** 进入引用态。只读气泡既有 `dataset`（`data-message-id`/`data-body`/`data-created-at`，
 *  见 `bubbleEl`）⇒ **零新增数据面**。 */
function startQuote(wrap, conv) {
  if (!wrap || !conv) return;
  const rawBody = wrap.dataset.body || '';
  // 引用一条「本身就是引用的」消息 ⇒ 只取它的正文（信封不再嵌套：一层引用一个信封）。
  const inner = parseQuoteBody(rawBody);
  // D-1（群面署名，作者已裁随批）：发送者 id 的**唯一**查询点 —— 群窗入站气泡挂
  // `.fm-msg-sender[data-sender-id]`（既有渲染点，仅群 + 仅入站）；群内**自发言**
  // 无该子节点 ⇒ 走既有 viewer 身份单点 `groupSelfUserId()`（零新增数据面）。
  // 名册未命中 ⇒ `groupSenderNameOf` 返回 ''（维持现状空值，不造值）。
  const senderEl = wrap.querySelector('.fm-msg-sender');
  const senderId = (senderEl && senderEl.dataset ? senderEl.dataset.senderId : '') || groupSelfUserId();
  const ref = makeReference({
    refType: 'friend-message',
    source: {
      conversationId: conv.conversationId || '',
      messageId: wrap.dataset.messageId || '',
      friendName: conv.kind === 'device' ? deviceLabel(conv.device)
        : conv.kind === 'group' ? groupSenderNameOf(conv, senderId)
        : (conv.friend?.name || ''),
      friendNeblinkId: conv.friend?.neblinkId || '',
      direction: wrap.classList.contains('out') ? 'out' : 'in',
      date: refDate(wrap.dataset.createdAt),
    },
    content: { fullText: inner ? inner.reply : rawBody },
  });
  if (!ref) return;
  pendingQuoteRef = ref;
  renderQuoteStrip();
}

/** 取消/消费引用态（× 键、发送、关窗三条路径共用）。 */
function clearQuote() {
  pendingQuoteRef = null;
  const strip = modalEls && modalEls.quoteStrip;
  if (strip) { strip.innerHTML = ''; strip.hidden = true; }
}

/** 引用态落面：复用既有输入框渲染器（含 ✕ 取消 ⇒ onRemove = clearQuote）。
 *  🔴 修正①（2026-09-19 04:22）：❌ 取**主窗口同类件**形态（`closeStyle:'disc'`
 *  ⇒ 实心圆白 ✕，与主窗口图片/文件附件 ❌ 同一声明块 + 同一字形）——
 *  不落设计稿的 ⊗ 变体。 */
function renderQuoteStrip() {
  const strip = modalEls && modalEls.quoteStrip;
  if (!strip) return;
  strip.innerHTML = '';
  if (!pendingQuoteRef) { strip.hidden = true; return; }
  strip.appendChild(renderRefBlock(pendingQuoteRef, { mode: 'input', closeStyle: 'disc' }, clearQuote));
  strip.hidden = false;
  createIconsIn(strip);
}

// ── 多选转发（落点 = **其他会话**：好友 / 群）─────────────────────────────
/** 逐条可转发性（**读码判据**，报告 §可转发性表逐条给 file:line）：
 *  · 空正文 ⇒ 跳过（服务端占位/无正文）；
 *  · 带附件 ⇒ 跳过（附件字节**不可**随转发复制，且禁新协议 ⇒ 转发它等于丢内容）；
 *  · 设备会话来源 ⇒ 跳过（本批落点只含好友/群，设备腿语义不跨面）；
 *  · 乐观项（`fm-tmp-*`，尚未落行）⇒ 跳过（避免把未确认内容转出去）。 */
function forwardabilityOf(m, conv) {
  const body = String((m && m.body) || '');
  if (!body) return { ok: false, why: 'empty' };
  if (Array.isArray(m.attachments) && m.attachments.length > 0) return { ok: false, why: 'attachment' };
  if (conv && conv.kind === 'device') return { ok: false, why: 'device' };
  if (String(m.id).startsWith('fm-tmp-')) return { ok: false, why: 'unsent' };
  return { ok: true, why: '' };
}

/** 勾选面（真实可点控件，`role=checkbox`；挂 dataset + class 双面）。 */
function attachCheck(node) {
  const id = String(node.dataset.messageId || '');
  node.classList.add('fm-selecting');
  const on = selectedIds.has(id);
  node.dataset.selected = on ? '1' : '0';
  node.classList.toggle('fm-selected', on);
  if (node.querySelector(':scope > .fm-msg-check')) return;
  const btn = el('button', 'fm-msg-check');
  btn.type = 'button';
  btn.setAttribute('role', 'checkbox');
  btn.setAttribute('aria-checked', on ? 'true' : 'false');
  btn.dataset.messageId = id;
  btn.title = t('messages.multiSelect');
  btn.setAttribute('aria-label', t('messages.multiSelect'));
  btn.innerHTML = '<i data-lucide="check"></i>';
  btn.addEventListener('click', (e) => { e.stopPropagation(); toggleSelected(node); });
  node.appendChild(btn);
}

function toggleSelected(node) {
  const id = String(node.dataset.messageId || '');
  if (!id) return;
  if (selectedIds.has(id)) selectedIds.delete(id); else selectedIds.add(id);
  const on = selectedIds.has(id);
  node.dataset.selected = on ? '1' : '0';
  node.classList.toggle('fm-selected', on);
  const btn = node.querySelector(':scope > .fm-msg-check');
  if (btn) btn.setAttribute('aria-checked', on ? 'true' : 'false');
  syncSelectBar();
}

function syncSelectBar() {
  if (!modalEls || !modalEls.selectBar) return;
  const n = selectedIds.size;
  // D5：计数的**唯一落点** = 窗头（底部条不再显示计数）。
  if (modalEls.selectCount) {
    modalEls.selectCount.textContent = t('messages.selectedCount', { n });
    modalEls.selectCount.hidden = !selectionMode;
  }
  // 转发族两键随选中数门控（合并转发 = 二期，恒 disabled）。
  if (modalEls.selectForward) modalEls.selectForward.disabled = n === 0;
  if (modalEls.selectForwardEach) modalEls.selectForwardEach.disabled = n === 0;
}

/** 进多选态（入口 = 右键「多选转发」）。 */
function enterSelection() {
  if (!modalEls) return;
  selectionMode = true;
  selectedIds.clear();
  modalEls.selectBar.hidden = false;
  // D4：多选期消息行放开为**通栏**（判据类，样式在 friends.css 的 fm-select-mode 段）
  // ⇒ 勾选圆落在同一条左列（进/出向圆心 x 逐值相等）。
  modalEls.flow.classList.add('fm-select-mode');
  for (const node of modalEls.flow.querySelectorAll('.fm-msg')) attachCheck(node);
  syncSelectBar();
  createIconsIn(modalEls.selectBar);
}

/** 出多选态（Esc / 点外 / 工具条退出 / 转发后 / 关窗 **共用**同一条收口）。 */
function exitSelection() {
  selectionMode = false;
  selectedIds.clear();
  if (!modalEls) return;
  modalEls.selectBar.hidden = true;
  modalEls.flow.classList.remove('fm-select-mode');
  closeForwardWindow();
  for (const node of modalEls.flow.querySelectorAll('.fm-msg')) {
    node.classList.remove('fm-selecting', 'fm-selected');
    node.dataset.selected = '0';
    node.querySelector(':scope > .fm-msg-check')?.remove();
  }
  syncSelectBar();
}

/** 选目标界面（**其他会话**：好友 / 群）。
 *  数据源 = 既有 `conversations`（本文件 `:70` / `refreshConversations`）——🔴 零新数据源、
 *  零新协议；UI = **既有菜单组件** `showPopupMenu`（`contextMenu.js:38-65`，第二消费者先例
 *  = `contacts.js:455-476`）⇒ role=menu/menuitem、越界夹取、Esc/点外关闭、零新色值全现成。 */
function openTargetPicker(conv, anchor) {
  const others = conversations.filter(c =>
    c.conversationId && c.conversationId !== conv.conversationId && c.kind !== 'device');
  if (!others.length) { modalToast(t('messages.forwardNoTarget')); return; }
  const items = others.slice(0, 12).map(c => ({
    // 会话名 = 既有单点 `convTitleLabel`（群名 / 好友备注>显示名 / 设备名）——禁第二套取名法。
    label: convTitleLabel(c),
    onClick: () => { void forwardSelectedTo(c); },
  }));
  const r = anchor && anchor.getBoundingClientRect ? anchor.getBoundingClientRect() : null;
  showPopupMenu(r ? r.left : Math.round(window.innerWidth / 2), r ? r.top : Math.round(window.innerHeight / 2), items);
}

/** 逐条转发到目标会话（**每条选中消息 = 一条新消息、保持原顺序**）。
 *  🔴 不引入服务端新协议（无声明的「合并成一张卡片」形态）；发送走**既有**两条 REST 腿
 *  （好友 `api.sendFriendMessage` / 群 `api.sendGroupMessage`，与 `sendCurrent` 同两个函数）。
 *  `note`（可选 · D7 附言）：非空时**先**以一条独立消息发给目标会话（附言 = 转发方的话，
 *  不篡改被转消息正文 —— 与既有「转发不附言」路径逐字兼容：缺省 `undefined` ⇒ 零行为变化）。 */
async function forwardSelectedTo(target, note) {
  const conv = currentConv();
  if (!conv || !target) return;
  // 顺序 = 窗口序（`chatMsgs`，ASC）= 对话原顺序；**禁**用 Set 插入序（点击序）当发送序。
  const picked = chatMsgs.filter(m => selectedIds.has(String(m.id)));
  const rows = [];
  let skipped = 0;
  for (const m of picked) {
    const f = forwardabilityOf(m, conv);
    if (!f.ok) { skipped += 1; continue; }
    rows.push(String(m.body));
  }
  exitSelection();
  if (!rows.length) { modalToast(t('messages.forwardNoneSelected')); return; }
  let failed = 0;
  const body = String(note == null ? '' : note).trim();
  const outbound = body ? [body, ...rows] : rows;
  for (const b of outbound) {
    try {
      if (target.kind === 'group') await api.sendGroupMessage(target.conversationId, b, undefined, newClientMsgId());
      else await api.sendFriendMessage(target.friend.userId, b, undefined, newClientMsgId());
    } catch (err) { failed += 1; console.error('[messages] forward failed:', err); }
  }
  const sent = outbound.length - failed;
  if (failed > 0) modalToast(t('messages.forwardPartial', { n: sent, f: failed }));
  else if (skipped > 0) modalToast(t('messages.forwardSkipped', { n: sent, k: skipped }));
  else modalToast(t('messages.forwardedCount', { n: sent }));
  void refreshConversations({ friends: 'reuse' });
}

// ── 转发窗口（**D7 · 本批新增面**）───────────────────────────────────────────
// 形态（设计稿 §3③ 照案）：① 附言（下沉面 2 行）② 已选 chip 回显（可单个移除）
// ③ 好友/会话多选列表（勾选圆 22px，与多选态同一族）④ 目标会话行 + 动作行。
// 🔴 铁律 1：**无 overlay 遮罩**（面板直接浮在聊天窗上方，零背景暗化/模糊），
//    面板本体毛玻璃 = 既有 `.cfg-modal`（零新材质）。
// 🔴 **二期边界（照案标注）**：多目标（多选转发到其他会话）为既定二期 ⇒ 本批
//    列表虽为多选形态，**行为 = 单一目标**（点一行换目标），多目标只需把目标行扩成
//    多值（布局不变）。该边界挂在 `data-forward-multi="phase2"` + `data-phase` 上，
//    可机械核（禁把二期当已实现呈现）。
let fwdState = null;

/** 关闭转发窗口（出多选 / 转发完成 / 再点转发键 共用同一条收口）。 */
function closeForwardWindow() {
  if (fwdState) {
    // 两件资源、同拍收口：① Esc 捕获监听（幂等撤除）；② 面板节点（`remove()` 幂等）。
    if (fwdState.onEsc) document.removeEventListener('keydown', fwdState.onEsc, true);
    if (fwdState.el && typeof fwdState.el.remove === 'function') fwdState.el.remove();
  }
  fwdState = null;
}

/** 目标会话是否可在本批充当落点（**与 `openTargetPicker` 同一条判据**）。 */
function forwardTargetsOf(conv) {
  return conversations.filter(c =>
    c.conversationId && c.conversationId !== conv.conversationId && c.kind !== 'device');
}

function openForwardWindow(conv, anchor) {
  if (!modalEls || !modalEls.overlay) return;
  if (fwdState) { closeForwardWindow(); return; }        // 再点 = 关（幂等切换）
  if (selectedIds.size === 0) { modalToast(t('messages.forwardNoneSelected')); return; }
  const others = forwardTargetsOf(conv);
  if (!others.length) { modalToast(t('messages.forwardNoTarget')); return; }

  const panel = el('div', 'cfg-modal fm-fwd-modal');
  panel.dataset.forwardMulti = 'phase2';                  // 多目标 = 二期（机械可核）
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-label', t('messages.forward'));

  // 窗头（复用既有窗头语言 ⇒ 修正③ 的标题/间距对齐**由构造继承**）
  const head = el('div', 'fm-modal-header');
  head.appendChild(el('div', 'fm-modal-name', t('messages.forward')));
  panel.appendChild(head);

  const bodyEl = el('div', 'fm-fwd-body');
  const note = document.createElement('textarea');
  note.className = 'fm-fwd-note';
  note.rows = 2;
  note.placeholder = t('messages.forwardNotePlaceholder');
  note.setAttribute('aria-label', t('messages.forwardNotePlaceholder'));
  bodyEl.appendChild(note);

  const chips = el('div', 'fm-fwd-chips');
  bodyEl.appendChild(chips);

  const list = el('div', 'fm-fwd-list');
  const targetRow = el('div', 'fm-fwd-target');
  const targetLabel = el('span', 'fm-fwd-target-label', t('messages.forwardToTitle'));
  const targetName = el('span', 'fm-fwd-target-name', t('messages.forwardNoPick'));
  targetRow.append(targetLabel, targetName);
  panel.appendChild(bodyEl);

  // 列表行 = 真实 `<button>`（键盘可达）；勾选圆 = 22px 同族（选中 = sapphire 实心 + 白勾）。
  let picked = null;
  const syncPick = () => {
    for (const row of list.querySelectorAll('.fm-fwd-row')) {
      const on = !!picked && row.dataset.conversationId === picked.conversationId;
      row.classList.toggle('on', on);
      row.setAttribute('aria-pressed', String(on));
      const check = row.querySelector('.fm-fwd-check');
      if (check) check.setAttribute('aria-checked', String(on));
    }
    chips.innerHTML = '';
    if (picked) {
      const chip = el('span', 'fm-fwd-chip');
      chip.appendChild(avatarEl(picked.kind === 'group' ? { name: convTitleLabel(picked) } : picked.friend, 28));
      chip.appendChild(el('span', '', convTitleLabel(picked)));
      const x = el('button', 'fm-fwd-chip-x');
      x.type = 'button';
      x.innerHTML = '<i data-lucide="x"></i>';
      x.title = t('messages.forwardChipRemove', { name: convTitleLabel(picked) });
      x.setAttribute('aria-label', x.title);
      x.addEventListener('click', (e) => { e.stopPropagation(); picked = null; syncPick(); });
      chip.appendChild(x);
      chips.appendChild(chip);
      createIconsIn(chips);
    }
    const kindLabel = picked
      ? (picked.kind === 'group' ? t('messages.forwardTargetGroup') : t('messages.forwardTargetDirect'))
      : '';
    targetName.textContent = picked ? `${convTitleLabel(picked)}${kindLabel ? ' · ' + kindLabel : ''}` : t('messages.forwardNoPick');
    if (okBtn) okBtn.disabled = !picked;
  };

  for (const c of others) {
    const row = el('button', 'fm-fwd-row');
    row.type = 'button';
    row.dataset.conversationId = c.conversationId;
    row.dataset.kind = c.kind;
    row.setAttribute('aria-pressed', 'false');
    const check = el('span', 'fm-fwd-check');
    check.setAttribute('role', 'checkbox');
    check.setAttribute('aria-checked', 'false');
    check.innerHTML = '<i data-lucide="check"></i>';
    row.appendChild(check);
    row.appendChild(avatarEl(c.kind === 'group' ? { name: convTitleLabel(c) } : c.friend, 36));
    const mid = el('span', 'fm-fwd-row-mid');
    mid.appendChild(el('span', 'fm-fwd-row-name', convTitleLabel(c)));
    mid.appendChild(el('span', 'fm-fwd-row-sub', c.kind === 'group'
      ? t('messages.forwardTargetGroup')
      : t('messages.forwardTargetDirect')));
    row.appendChild(mid);
    row.addEventListener('click', () => { picked = (picked && picked.conversationId === c.conversationId) ? null : c; syncPick(); });
    list.appendChild(row);
  }
  bodyEl.appendChild(list);
  bodyEl.appendChild(targetRow);

  const foot = el('div', 'fm-fwd-footer');
  const cancel = el('button', 'fm-fwd-cancel', t('messages.forwardCancel'));
  cancel.type = 'button';
  cancel.addEventListener('click', () => closeForwardWindow());
  const okBtn = el('button', 'cfg-btn cfg-btn-primary fm-send-btn fm-fwd-ok', t('messages.forwardSend'));
  okBtn.type = 'button';
  okBtn.disabled = true;
  okBtn.addEventListener('click', () => {
    const target = picked;
    if (!target) return;
    const text = note.value;
    closeForwardWindow();
    void forwardSelectedTo(target, text);
  });
  foot.append(cancel, okBtn);
  panel.appendChild(foot);

  modalEls.overlay.appendChild(panel);
  createIconsIn(panel);
  // 🔴 面板的关闭路径**全部**汇聚到 `closeForwardWindow()`：窗内空白一击（`modal` 的
  // 点外分支，见上）/ 退多选（`exitSelection`）/ 关窗（`closeChat` → `exitSelection`）/
  // Esc / 取消键 / 发送后。**不注册** outside-click 监听（点外关闭由既有宿主承接）
  // ⇒ 结构性零泄漏；**唯一**新增监听 = 下面这一枚 Esc 捕获监听（面板在场才在册，
  // 收口时随面板同拍撤除，见 `closeForwardWindow`）。
  // 🔴 为什么是**捕获**相：Esc 的层序必须由**最顶层**决定，而与焦点落在哪无关
  // （用户点了 chip 的 ✕ 后焦点所在节点已被移除 ⇒ 事件目标退化为 `body`，若靠
  // 面板自身的冒泡监听就吃不到这一下 Esc，反而会被 document 上的既有 `escClose`
  // 吃掉而直接退多选）。捕获相在 document 上先手 ⇒ 「面板在场 ⇒ 第一下 Esc 只关面板」。
  const onEsc = (e) => {
    if (!fwdState || e.key !== 'Escape') return;
    e.stopPropagation();
    closeForwardWindow();
  };
  document.addEventListener('keydown', onEsc, true);
  fwdState = { el: panel, onEsc };
  syncPick();
  note.focus();
}

/** 右键菜单「复制」项（既有复制键的同一动作；菜单项点击后菜单即关 ⇒ 反馈走 toast）。 */
async function copyBubbleText(wrap) {
  try {
    await navigator.clipboard.writeText((wrap && wrap.dataset.body) || '');
    modalToast(t('messages.copied'));
  } catch (err) {
    console.error('[messages] Copy failed:', err);
    modalToast(t('messages.copyFailed'));
  }
}

// ── 幂等键（P2-b）：**发送动作**粒度 ───────────────────────────────────
/** 幂等键生成（`clientMsgId`）：一次**发送动作**一个键。
 *
 *  产品语义（P2-b 裁定）：**不同**发送动作各得新键 ⇒ 逐条照常落行；**同一**动作的
 *  重复提交（失败重试 / 原地再发）**复用**同键 ⇒ 服务端回放原行（§8.6：同键重复仍是
 *  201，`existing:true` 仅表示回放，不是失败也不是新行），不再各落一条消息。
 *  ⇒ 键的生命周期 = **动作**的生命周期，不是函数调用的生命周期 —— 后者正是修前的
 *  病灶：每次调用都发一条无键新请求，服务端无从判重，客户端 `messageId` 去重又只
 *  覆盖「已到帧」，结构上拦不住。
 *
 *  形态：优先 `crypto.randomUUID()`（原生）；不可用（老 WebView / 非安全上下文）时
 *  回落 时间戳 + 单调序号 + 随机尾。幂等键只需在**同一发送者**的短窗内唯一，不跨端
 *  协商 ⇒ 两种形态都满足，无需引入依赖。 */
let clientMsgSeq = 0;
export function newClientMsgId() {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  clientMsgSeq += 1;
  return `fm-${Date.now().toString(36)}-${clientMsgSeq}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 设备腿的「同动作重试」键记忆（P2-b）：失败 ⇒ 正文回填输入框，用户原地再发属
 *  **同一动作** ⇒ 复用同键。判据 = (会话 id, 正文) 逐字相等；任一不等 = 新动作 ⇒
 *  新键，并清掉陈旧记忆（防「很久以后又发同一句话」被静默判重）。
 *  成功 ⇒ 清空（下一次同文本发送是**新动作** ⇒ 新键 ⇒ 照常落新行）。 */
let deviceRetry = null; // { convId, body, clientMsgId } | null

// ── Send (§6.2 sending/delivered/failed) ─────────────────
/** 文本腿**单一分派**（§4.1-#1）：三面的传输适配器选择只在此处出现一次
 *  （`conv.kind` 的另一处消费面 = `dispatchFiles` 的同名判据 —— 两处都是「入口」）。 */
function dispatchSend(conv) {
  return conv.kind === 'device' ? sendDeviceCurrent(conv) : sendCurrent(conv);
}

/** 附件腿**单一分派**（§4.1-#2 纸夹 / #3 拖放 共用）：闸位、上限、受理面三面同源。
 *  不可用面（既非设备窗、又无附件寻址）⇒ 可见提示（禁静默吞文件）。 */
function dispatchFiles(conv, fileList) {
  if (!conv) return;
  if (conv.kind === 'device') {
    sendDeviceFiles(conv.device.deviceId, fileList);
    return;
  }
  if (attachAvailable(conv)) {
    void sendAttachCurrent(conv, fileList);
    return;
  }
  modalToast(t('messages.attachUnsupported'));
}

async function sendCurrent(conv, opts) {
  if (!modalEls) return;
  const text = modalEls.input.value.trim();
  // 长度闸只管**用户正文**（引用信封是加性前缀，~≤200 字符；见报告「开放项」）。
  if (!text || text.length > 2000) return;
  // 引用态消费（**动作边界**）：信封在出站正文首行承载引用关系；引用态随即收口
  // ⇒ 失败重试（同键重发）把**已含信封**的正文原样再发一次，不会二次包信封。
  const quote = pendingQuoteRef;
  const body = quote ? buildQuoteBody(quote, text) : text;
  // 结构化坐标腿（quotejump 批 · 双写）：被引消息 id 以**整数**随请求体带出（好友腿）。
  // 🔴 只在**好友腿**给（外仓 D-6：群 / 设备腿刻意不带该键；群腿网关又是原文转发
  // ⇒ 硬塞只会被服务端静默忽略）。id 域非整数（mock 面字符串 id）⇒ 不发该键。
  // 重试腿（`fm-retry`）正文已含信封、`pendingQuoteRef` 已收口 ⇒ 坐标由 opts 原样带回
  // （同一动作 ⇒ 同一坐标，不因重试丢结构化腿）。
  const quoteRefId = quote ? wireQuoteId(quote.source?.messageId) : wireQuoteId(opts && opts.replyToMessageId);
  if (quote) clearQuote();
  // 幂等键：缺省面 = **新的发送动作** ⇒ 新键；重试面由调用方传入**同一**键（见下
  // `fm-retry` 分支）。🔴 生成点在动作边界，不在 API 层——API 层分不清「重试」与
  // 「用户又想发一句一样的」。
  const clientMsgId = (opts && opts.clientMsgId) || newClientMsgId();
  modalEls.input.value = '';
  syncComposerSend(); // 已清空 ⇒ 发送键回禁用态（判据单源）

  // ── 乐观面**三面共用调用序**（uxconsist Phase B · §4.1-#6）──────────────────
  // 入列 → 建节点 → 登记未决项 → 置 S2：设备文本腿调用**同一序**（见 `sendDeviceCurrent`），
  // 三面不再各写一套（改前设备腿无乐观面 ⇒ 双往返期间屏上零变化 = R2-device 格）。
  const pending = armOptimisticText(conv, body, chatMsgs);
  const wrap = pending.node;
  const tempId = pending.tempId;

  try {
    // 群发分支（补充卡 §5.1 逐字契约：POST /api/groups/{id}/messages {body} →
    // SendMessageResponse 同形 {messageId, conversationId, createdAt, ...}）。
    // 🔴 UI 直发无 origin 字段（补充卡 §5.4 写权矩阵第一行）⇒ 服务端落 'user'。
    // P2-b：第四位 = 幂等键（`attachments` 位此处**不传** ⇒ 无附件消息请求体与
    // 加键前逐字节同形 + `clientMsgId` 一个键；群腿网关是原文转发，键直达服务端）。
    const resp = conv.kind === 'group'
      ? await api.sendGroupMessage(conv.conversationId, body, undefined, clientMsgId)
      : await api.sendFriendMessage(conv.friend.userId, body, undefined, clientMsgId, quoteRefId ? Number(quoteRefId) : undefined);
    const realId = resp.messageId || tempId;
    noteSentRealId(realId); // self 识别关联源（群方向判据的学习输入，见 §状态段）
    // 群发回执面 `selfUserId`（契约终版 §1.1 #8）= 发送者鉴权身份 ⇒ 最强证据，
    // 即时收敛 viewer 身份（不等自播帧/后续拉取）。🔴 单聊路径**刻意忽略**该键
    // （同形共享信封的外溢字段，已裁：单聊面不消费 —— 单聊方向判据走既有
    // conv.friend.userId 双员封闭，无需 viewer 身份）。
    if (conv.kind === 'group' && resp && resp.selfUserId) learnSelfUserId(resp.selfUserId);
    // S6 送达（uxconsist Phase B）：摘载面 = 既有 `fm-sending`（**无痕**，无「已发送」残条）
    // ＋ `data-send-phase="confirmed"` —— 单一槽位由 `applyPhase` 收口（禁各处手改类）。
    applyPhase(wrap, PHASE.SENT);
    // U-b 唯一锚定点：回显已先到时此处**幂等**（同一后置条件，节点/条目数不变）；
    // 回显未到时即既有的「temp id → 服务端 id」换键。
    if (!anchorSendToRealId(pending, realId)) {
      // 窗口已关/重开（乐观节点脱离文档）⇒ 退回最小改键（不触碰新窗口的状态）。
      wrap.dataset.messageId = realId;
      const idx = chatMsgs.findIndex(x => x.id === tempId);
      if (idx >= 0) chatMsgs[idx] = { ...pending.entry, id: realId }; // temp id → 服务端 id
    }
    forgetPendingSend(pending);
    if (!conv.conversationId && resp.conversationId) {
      // First send created the conversation (friend-addressed send)
      conv.conversationId = resp.conversationId;
      openConvId = resp.conversationId;
    }
    // delivered: silent (§6.2 克制)
    conv.lastMessage = { ...pending.entry, id: realId };
    conv.lastMessage.agentSent = false;
    resortAndRender();
    persistConversation(conv); // ⑨ 落盘（temp id 由缓存层过滤，不会存成幻影）
  } catch (err) {
    forgetPendingSend(pending);
    // S4 错误态载面（气泡左侧红圈位由 `.fm-failed` 承担；§2.2 S4 行）。
    applyPhase(wrap, PHASE.ERROR);
    if (conv.kind === 'group') {
      // 群发终态错误（补充卡 §5.3：404 group_not_found / 403 group_disbanded /
      // 403 not_member）⇒ 就地移除该群行 + 可见反馈（退群后历史不可见的呈现）；
      // 其余（网络/5xx/429）照既有失败旗标重试面（正文不丢）。
      const code = err && err.data && (err.data.error || err.data.code);
      const terminal = (err && err.status === 404 && code === 'group_not_found')
        || (err && err.status === 403 && (code === 'group_disbanded' || code === 'not_member'));
      if (terminal) {
        // S7 失败（语义终态）：气泡撤除 + 分态提示（§2.2 S7 行；逐字沿用既有实现）。
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
    // S5 重试键（§3.1 唯一装配点 `bindRetry`；`title`/`aria-label` = `contacts.retry` 逐字，
    // 见 §8.2 改写①——改前用的是 `messages.send`「发送」，与键语义不符）。
    bindRetry(wrap, () => {
      wrap.remove();
      // 重试会生成新的 temp id ⇒ 旧条目必须出窗口，否则 keyed diff 把刚删掉的
      // 失败气泡又插回来（一屏两个失败气泡）。
      const i = chatMsgs.findIndex(x => x.id === tempId);
      if (i >= 0) chatMsgs.splice(i, 1);
      modalEls.input.value = body;
      syncComposerSend(); // 回填非空文本 ⇒ 发送键回可用态（重试路径不得留假禁用）
      // P2-b：重试 = **同一动作** ⇒ **复用同键**。上游若其实已落库（响应丢失/超时），
      // 服务端按同键回放原行 ⇒ 不再落第二条；若首投真的没到，键首见 ⇒ 正常落一行。
      // quotejump：结构化引用坐标同属该动作 ⇒ 一并带回（见 `quoteRefId`）。
      sendCurrent(conv, { clientMsgId, replyToMessageId: quoteRefId });
    });
  }
}

/**
 * 文本腿乐观面的**三面共用调用序**（§4.1-#6）：入列 → 建节点 → 置 S2 → 登记未决项。
 * 好友 / 群（`list = chatMsgs`）与设备服务端腿 / legacy 腿（`list = deviceMsgs`）共用；
 * 回显认领面（`claim`）据此**原地换键**，不新增气泡。
 * @param {any} conv @param {string} body 出站正文 @param {any[]} list 本窗消息数组
 * @returns {any} 未决项登记（`{tempId, convId, body, node, entry, anchoredTo}`）
 */
function armOptimisticText(conv, body, list) {
  const tempId = 'fm-tmp-' + (++msgSeq);
  const optimistic = { id: tempId, senderId: 'me', kind: 'text', body, createdAt: new Date().toISOString() };
  // ⑨-E：乐观回显登记进窗口 —— keyed diff 才知道这个节点「该在」，否则任何一次
  // 增量补齐的重排都会把它当差集删掉（用户会看到自己刚发的消息凭空消失）。
  list.push(optimistic);
  const wrap = bubbleEl(optimistic, conv);
  applyPhase(wrap, PHASE.SENDING); // S2：气泡左侧 14px 环（既有 CSS，零新规则）
  modalEls.flow.appendChild(wrap);
  createIconsIn(modalEls.flow);
  modalEls.flow.scrollTop = modalEls.flow.scrollHeight;
  // U-b：登记为「发送中的乐观项」——回显（WS 自播帧 / REST keyset 增量 / 设备 keyset
  // 重取）若先于响应到达，由 `claim` 认领回这一条（不新增气泡）。
  const pending = { tempId, convId: conv.conversationId || '', body, node: wrap, entry: optimistic, anchoredTo: null };
  trackPending(pending);
  return pending;
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
 *
 *  ── imgmsg 批（作者 2026-09-18 五项全裁）：**好友图片消息路径**的乐观面 ──
 *  范围闸 = `scopedFriendImageSend`（好友窗 + 本批件**全部**是图片）。命中时：
 *   ① 气泡在**上传开始前**上屏（A1-full）、占位与首帧见 ②③；
 *   ④ 发送侧反馈 = 乐观直显（气泡先出现）⇒ 本路径的上传卡**不给百分比**
 *      （`quiet`；既有实时 WS 进度条形式只在非本路径保留，逐字不变）；
 *   ① 确认面**原地接管**乐观面（同一锚定点 `anchorSendToRealId` + `keyedDiff` 的
 *      `data-message-id` 身份）⇒ **零重复面**；
 *   ① 确认面字节 = **本机已有的那份**（登记进既有 `inlineObjectUrls`）⇒
 *      不再把自己刚上传的原图整件取回来（`api.downloadAttachment` 零调用）。
 *  🔴 未命中范围闸（群窗 / 混批 / 非图片 / 空批）⇒ **逐字走既有路径**（零行为变化）。
 * @returns {Promise<void>}
 */
async function sendAttachCurrent(conv, fileList) {
  if (!modalEls || !conv) return;
  const text = modalEls.input.value.trim();
  // 件集在**第一个 await 之前**取定：调用方（纸夹 input）随后即清 `value`，
  // 而 `File` 对象本身在本次任务内保持有效。
  const files = Array.from(fileList || []);
  // ①A1-full 范围闸（唯一判据点；见函数头）。
  const optimistic = scopedFriendImageSend(conv, files);
  let pending = null;
  if (optimistic) {
    // 乐观面上屏**先于**任何网络动作（上传尚未开始）—— 正文随气泡入屏 ⇒ 输入框此刻清空
    // （与 `sendCurrent` 的文本乐观面同拍：正文在屏上只有一处）。
    modalEls.input.value = '';
    syncComposerSend();
    pending = mountOptimisticAttachBubble(conv, files, text);
  }
  // 能力位（§3.1）：**数值进度**由能力位决定，不再由调用点各写一套 ——
  // 「有乐观气泡的腿 = 卡不出百分比（反馈由气泡承担）；无乐观面的腿 = 卡给进度条 + 数值」
  // 由 R6-friend / R6-group 两格的「个案」升为**规则**（消除三面漂移）。
  const caps = capabilitiesOf(conv, optimistic ? SEND_LEG.ATTACH_OPTIMISTIC : SEND_LEG.ATTACH);
  const res = await sendFiles(conv, files, text, optimistic ? {
    quiet: !caps.numericProgress, // ④：本路径不给百分比（反馈 = 乐观直显）
    onUploaded: (attachmentId, file) => { if (pending) noteUploadedForPending(pending, attachmentId, file); },
  } : undefined);
  if (!res.ok) {
    if (pending) rollbackOptimisticAttach(pending, text);
    modalToast(res.reason || t('messages.attachFailed'));
    return; // 正文回填见 rollbackOptimisticAttach（与 sendCurrent 的失败面同语义：正文不丢）
  }
  modalEls.input.value = '';
  syncComposerSend();
  // 确认面**原地接管**（锚定 + 权威附件元数据 + 同一条单点渲染）——不新增节点/条目。
  if (pending) settleOptimisticAttach(conv, pending, res);
  await refreshAfterAttachSend(conv);
  // 让位（作者 2026-09-16 令「发送附件的感受还不够流畅」· 取证 P1）：服务端权威行
  // （含气泡内附件卡）已到屏 ⇒ 本地「已发送」上传卡退场 —— 同一次发送在屏上**只剩
  // 一个附件面**。修前同一次发送占**两个互不相关的面**（上传卡 `data-upload-id` +
  // 气泡内附件卡 `data-message-id`，两者无共享键），作者读作「重复且没有意义」。
  // 🔴 复用既有唯一清理点（`clearSettledUploads`，与关窗路径同款实现），不新造第二套。
  // 🔴 时机放在 `refreshAfterAttachSend` **之后**：刷新链未返回时上传卡仍是唯一可见
  // 回执（见 refreshAfterAttachSend 的落盘兜底注释），不让屏幕出现「零回执」窗口。
  // ⚠ 已知边角（如实登记，报告 §⑦）：该刷新链自身吞错 ⇒ 刷新真失败时上传卡同样退场，
  // 可见回执此时由后续 WS 帧 / 下次开窗补齐（不新增任何重试/轮询面）。
  // 📌 imgmsg 批更新（旧语义**已被本批取代**，不得与本实现并存）：本批后**屏上的主面
  // = 气泡本身**（上传开始即上屏 = 乐观面，确认时原地接管成确认面 ⇒ 逐帧恒**一个**气泡面）。
  // 上传卡自本批起退居 **transfer 控制条**（取消键 + 失败文案，uid 不带图片面）——
  // 「只剩一个附件面」在图片路径上的新读法 = **只剩一个持有图片字节的面**（= 气泡）；
  // 气泡与上传卡的短暂并存是作者在卡片「冲突说明在场」下选 A1-full 时**已知并接受**的
  // 那一项（卡件 §二 A1-full 行 + §附录 A），不再是违令面。
  clearSettledUploads(conv.conversationId);
}

/** ①A1-full **范围闸（唯一判据点）**：仅「无设备面的会话 + 本批件全部是图片」走乐观面。
 *
 *  📌 uxconsist Phase B（§5 R1-group 行 / §4.1-#9）：**放开 group** —— 群窗与好友窗的
 *  图片腿**同款**（改前范围闸排 group ⇒ 同一动作两面感受不同 = R1-group 偏差格）；
 *  `device` 排除**保留**（设备腿不作气泡乐观面：其权威面是传输台账，无 message row
 *  可锚定，见 §5 R1-device 行）。
 *  函数名保留为**落点锚**（设计件按此名给点）；判据源仍 = `isImageAttachmentName`
 *  （与卡片直显腿**同一函数**，禁第二张类型表）。 */
function scopedFriendImageSend(conv, files) {
  if (!conv || conv.device) return false;
  if (!Array.isArray(files) || files.length === 0) return false;
  return files.every(f => f && isImageAttachmentName(f.name || ''));
}

/** ① 乐观面上屏（**发送侧唯一新渲染腿**）：气泡 + 逐件本机句柄 + ③ 预留骨架。
 *
 *  与文本腿 `sendCurrent` 同款三件事，一个不少：
 *   · 条目入窗（否则任何一次增量补齐的重排都会把它当差集删掉）；
 *   · `bubbleEl`（**既有单点渲染器**）建节点 —— 附件卡由 `attachmentCard` 渲染，
 *     其中 `att.localFace` 触发本机字节腿（见 `attachInlineImage`）；
 *   · 登记进 `pendingSends`（U-b 登记表）⇒ 回显腿可**原地认领**（不新增气泡）。
 *  ④ 上传期间本件的卡片状态 = 既有 `uploading` 态（无下载键、无假按钮），
 *  不再有百分比进度面（`quiet`）。
 *  @returns {any} pending 登记 */
function mountOptimisticAttachBubble(conv, files, text) {
  const tempId = 'fm-tmp-' + (++msgSeq);
  const faces = [];
  const atts = files.map((f) => {
    const att = { id: '', name: f.name, size: f.size, state: 'uploading' };
    /** 本机句柄台账（**非枚举** ⇒ 不进任何序列化 / 不污染 wire 形态与缓存比对）。
     *  `natW`/`natH` = **原图**内禀尺寸（③ 盒比来源；0 = 未知 ⇒ 渲染层自行解码）。 */
    const face = { file: f, url: '', isThumb: false, img: null, settled: false, bytes: 0, natW: 0, natH: 0 };
    faces.push(face);
    try {
      Object.defineProperty(att, 'localFace', { value: face, enumerable: false, configurable: true });
    } catch { /* 冻结对象：退回无本机面（此时等于既有路径，不阻断发送） */ }
    return att;
  });
  const entry = {
    id: tempId,
    senderId: 'me',
    kind: 'text',
    body: text,
    createdAt: new Date().toISOString(),
    attachments: atts,
  };
  chatMsgs.push(entry);
  const wrap = bubbleEl(entry, conv);
  wrap.classList.add('fm-sending');
  wrap.dataset.sendPhase = 'local-pending'; // QA 读数面（发送阶段）
  modalEls.flow.appendChild(wrap);
  createIconsIn(modalEls.flow);
  modalEls.flow.scrollTop = modalEls.flow.scrollHeight;
  /** @type {any} */
  const pending = {
    tempId, convId: conv.conversationId || '', body: text, node: wrap, entry,
    anchoredTo: null, attachIds: [], files, faces, kind: 'attach-image',
  };
  trackPending(pending);
  // ② 本地小图：生成完成即在同一枚 `<img>` 上出帧（不重建气泡、不重排）。
  void fillLocalThumbs(pending);
  return pending;
}

/** ② 逐件生成/挂载本地小图（**异步、无阻塞**：解码在浏览器解码线程）。
 *  已确认（`settled`）或已就绪（`url` 非空）⇒ 丢弃本次产物（**不生成 URL ⇒ 无泄漏**）。 */
async function fillLocalThumbs(pending) {
  await Promise.all((pending.faces || []).map(async (face) => {
    /** 首帧产物 = 小图 blob + **原图**内禀尺寸（③ 的盒比来源；见 `makeLocalThumbFrame`）。 */
    let frame = null;
    try { frame = await makeLocalThumbFrame(face.file); } catch { frame = null; }
    if (face.settled || face.url) return; // 确认面已定/已出帧 ⇒ 本次产物作废
    const blob = frame && frame.blob ? frame.blob : null;
    // 内联预算（既有尺 `MAX_INLINE_IMAGE_BYTES`，本批**不放宽**）：超预算 ⇒ 不出本机帧
    // （回落到确认面的服务端腿，与改前同一条降级面）。
    const bytes = blob ? blob.size : (face.file && face.file.size) || 0;
    if (bytes > MAX_INLINE_IMAGE_BYTES) return;
    face.bytes = bytes;
    face.isThumb = !!blob;
    face.natW = frame ? frame.w : 0;   // ③ 原图内禀尺寸（比例已知的判据）
    face.natH = frame ? frame.h : 0;
    face.url = URL.createObjectURL(blob || face.file);
    const img = (face.img && face.img.isConnected) ? face.img : null;
    if (img && !img.getAttribute('src')) {
      img.alt = (face.file && face.file.name) || '';
      img.dataset.attSrc = face.isThumb ? 'local-thumb' : 'local-file';
      // ③ 比例先落地、再点 `src`（同一帧；解码失败 ⇒ 走 `decodeDims` 兜底，仍先比后帧）。
      paintInlineFrame(img, face.url, (face.natW > 0 && face.natH > 0) ? { w: face.natW, h: face.natH } : null);
    }
  }));
}

/** ① 上传回执 → 未决项：记强键（附件 id）+ 本机件句柄（`face.file` 已按序在场）。 */
function noteUploadedForPending(pending, attachmentId, file) {
  const id = (attachmentId === undefined || attachmentId === null) ? '' : String(attachmentId);
  if (!id) return;
  if (!pending.attachIds.includes(id)) pending.attachIds.push(id);
  const face = (pending.faces || []).find(f => !f.attachmentId && f.file === file);
  if (face) face.attachmentId = id;
}

/** ① 确认面**原地接管**（唯一锚定点 + 既有单点渲染器，零新增节点/条目）：
 *   ① 权威附件元数据（id = 上传回执；name/size = 本机件；state = `ready`——上传回执
 *      已回 ⇒ 服务端可下载）；
 *   ② 字节源登记 = **本机句柄**（键 = 附件 id，进既有 `inlineObjectUrls`）⇒ 确认面
 *      渲染时命中缓存，`api.downloadAttachment` **零调用**（回环往返消失）；
 *   ③ 锚定 → 条目/节点**原地换键**（不新增气泡），再走 `renderMessages`（= 既有唯一
 *      渲染入口）→ `keyedDiff` 按附件签名**就地重填同一节点**。
 *  @param {any} conv @param {any} pending @param {{ids?: string[], messageId?: string}} res */
function settleOptimisticAttach(conv, pending, res) {
  const ids = Array.isArray(res && res.ids) ? res.ids : [];
  const atts = (pending.files || []).map((f, i) => ({
    id: ids[i] !== undefined && ids[i] !== null ? String(ids[i]) : '',
    name: f.name,
    size: f.size,
    state: 'ready',
  }));
  (pending.faces || []).forEach((face, i) => {
    face.settled = true; // 之后到达的小图产物一律作废（防把确认面降级成小图）
    const id = atts[i] ? atts[i].id : '';
    if (!id) return;
    if (!face.url) {
      // 小图未及生成 ⇒ 回落**原文件句柄**（同一条本机字节腿；超预算则不出本机帧）。
      const bytes = (face.file && face.file.size) || 0;
      if (bytes > MAX_INLINE_IMAGE_BYTES) return;
      face.bytes = bytes;
      face.isThumb = false;
      face.url = URL.createObjectURL(face.file);
    }
    rememberInlineObjectUrl(id, face.url);
    markLocalOutAttachment(id);
  });
  const realId = (res && res.messageId !== undefined && res.messageId !== null && res.messageId !== '')
    ? res.messageId : pending.tempId;
  noteSentRealId(realId);
  if (!anchorSendToRealId(pending, realId)) {
    // 窗口已关/重开（乐观节点脱离文档）⇒ 退回最小改键（不触碰新窗口的状态），
    // 与 `sendCurrent` 的同名回落**逐字同款**。
    pending.node.dataset.messageId = String(realId);
    const idx = chatMsgs.findIndex(x => x.id === pending.tempId);
    if (idx >= 0) chatMsgs[idx] = { ...pending.entry, id: realId };
    pending.entry = chatMsgs[idx] || pending.entry;
  }
  forgetPendingSend(pending);
  // ── E 项（作者设计令 · 成功态**无痕**）· 本批**唯一的行为面修复** ──────────────
  // 摘掉 `fm-sending` = 「发送态载面」的收口条件。修前本函数**不摘**它：只改
  // `data-send-phase` + 就地重填气泡，而 `keyedDiff` 复用节点、不重置 wrapper 的 class
  // ⇒ 该类一直挂到该节点被换掉为止，表现为「时间戳**永久**脉冲」（改后 = 环**永久**转）。
  // 🔴 只摘类：锚定 / 结算 / 附件元数据 / 清理时机**一行不动**（零语义改动）。
  // 文本腿的同款收口在 `sendCurrent`（成功 `:2928` / 失败 `:2950` 各自摘除）——两腿同语义。
  if (pending.node) pending.node.classList.remove('fm-sending');
  pending.entry.attachments = atts;
  if (pending.node && pending.node.isConnected) {
    pending.node.dataset.sendPhase = 'confirmed';
    // 既有唯一渲染入口（`renderMessages` → `keyedDiff`）：附件签名已变 ⇒ 该节点**就地
    // 重填**（`fillBubble` 单点），节点身份（`data-message-id`）与位置都不动。
    renderMessages(chatMsgs, { stickBottom: true });
    persistConversation(conv);
  }
}

/** ① 上传/发送失败 ⇒ 乐观面**回滚**（唯一失败面定义，卡件要求「失败怎么回滚要定义」）：
 *   · 气泡与条目**同拍撤除**（不留悬空面，也不留幽灵条目）；
 *   · 本机句柄撤销（未登记进字节表的 URL 由本函数回收；已登记者随字节表 LRU 生命周期）；
 *   · 正文回填输入框（与既有失败面**同语义**：正文不丢）——仅当输入框仍空（用户没在
 *     失败窗口里另起一句；另起的正文优先，禁被回填覆盖）；
 *   · 失败线索**不静默**：`sendFiles` 已把失败/取消卡与原因留在原地（fail-closed
 *     呈现逐字不变），调用方再叠一条 toast。 */
function rollbackOptimisticAttach(pending, text) {
  forgetPendingSend(pending);
  const idx = chatMsgs.findIndex(x => x === pending.entry || String(x.id) === String(pending.tempId));
  if (idx >= 0) chatMsgs.splice(idx, 1);
  if (pending.node && pending.node.isConnected) pending.node.remove();
  (pending.faces || []).forEach((face) => {
    face.settled = true;
    if (!face.url) return;
    const stillUsed = document.querySelector(`.fm-att-inline img[src="${face.url}"]`);
    if (!stillUsed) { try { URL.revokeObjectURL(face.url); } catch { /* non-critical */ } }
  });
  if (modalEls && text && !modalEls.input.value) {
    modalEls.input.value = text;
    syncComposerSend();
  }
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
    // 设备维度（devnotif 批 2026-09-16）：设备消息的**发送设备**判别键。与上方
    // `attachments` / `origin` **同款加性扩面**（键缺席 = `undefined` ⇒ 前端按
    // 「无设备证据」读，逐字节现状）：服务端只在设备会话消息上带它
    // （neblink-server `message_new_payload` 的**条件键**：`if let Some(sender_device_id)`
    // ⇒ 直聊/群聊帧与老服务端**键缺席**）。
    // 🔴 不补这一键，浏览器侧**根本**拿不到「这条是哪台设备发的」（取证位 ②因③：本
    // 白名单是**唯一**帧→本地对象的映射点，丢字段 = 未读判据无据可判、全程静默）。
    senderDeviceId: p.senderDeviceId,
    // msgquote 结构化引用坐标对（quotejump 批加性扩面，与本函数其它键**同款**纪律）：
    // 外仓 `reply_to_message_id` / `reply_to_conversation_id` 两列在**下行帧**是
    // **条件平键**（无引用 ⇒ 整键不出现；见 neblink-server `src/friends.rs` 的
    // `message_new_payload`）。本函数是**唯一**的「帧 → 本地消息对象」映射点
    // ⇒ 不在此透出，浏览器侧**根本**拿不到跨会话跳转所需的会话坐标（丢字段、不丢消息）。
    // 🔴 键缺席 = `undefined` ⇒ `quoteCoordOf` 退回「信封 id + 本会话」兜底（旧服/
    // 旧网关/群腿形态，逐字节现状）。
    replyToMessageId: p.replyToMessageId,
    replyToConversationId: p.replyToConversationId,
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
      // 🔴 devnotif 批（2026-09-16）加**设备支**：设备窗无 `friend` 档案、且设备消息的
      // `senderId` = **本账号** user id（服务端 payload builder 逐字：`sender` =
      // `user_public(m.sender_id)`）⇒ 单聊支对设备窗恒假（取证位 ②因①②：不是「没有
      // 数据」，是「判据形状是好友形状」）。判据**不在此另写一套**——收口到同一方向单点
      // `oursBySenderId` 的设备分支（按会话类型分派，见其函数体）⇒ 三支同源。
      // 好友/群两支逐字不变（群支的表达式与修前逐字相同，仅参与条件并入设备支）。
      const fromPeer = (conv.kind === 'group' || conv.kind === 'device')
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
  if (msg.event === EV_RECEIPT_UPDATE) {
    // E3 推帧（契约 §1.1 E3 行 + §4.2 帧形）：本分支**不发任何帧**（前端处置契约①：
    // 不得 ack）；幂等按 `eventId` 塌缩（§1.3）⇒ 重复帧零正确性后果。
    if (!markReceiptEventSeen(msg.eventId)) return;
    // payload 取法：网关 `FriendEvent.frontendFrame` 已把 `payload` 展平到帧顶层
    // （`message_new` 分支同款形态）；此处**兼容**未展平的 `{payload:{…}}` 形态
    // （判据 = 「payload 是不是对象」，不猜字段值）。
    const p = (msg.payload && typeof msg.payload === 'object') ? msg.payload : msg;
    const convId = p.conversationId;
    if (!convId) return;
    const conv = conversations.find(c => c.conversationId === convId);
    // 设备面帧（`conversationKind='device'`/设备会话 id）不走本支路：射程外（E2EE）。
    if (!conv || conv.kind === 'device') return;
    // 只有**开着的那一个窗**需要立刻刷新（未开的会话在开窗时取一次 —— 取数点唯一，
    // 禁在此另建一条未开窗的取数链）。
    if (openConvId === convId) void refreshConvReceipts(conv);
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
  if (open) {
    await syncConversation(convId, { pages: MAX_SYNC_PAGES, trigger: 'beacon_backfill' });
    // 契约 §1.1 前端处置契约③（「重连 / 切回窗口**必须**重拉 E1」）在客户端侧的落点：
    // 本函数 = 唤醒面（回前台 / 回网）与通道降级兜底（帧静默 / 隧道判死）**同一实现**
    // ⇒ 两类窗口一起覆盖，且不新增定时器。「健康帧流路径零额外请求」的纪律不变
    // （本函数不被健康路径调用；调用面 = `wakeResync` 与 `backfillTick` 的门控腿）。
    const conv = currentConv();
    if (conv && conv.conversationId === convId) void refreshConvReceipts(conv);
  }
}

/** 本地层后台对账的节流（> beacon 周期 ⇒ 至多一拍一次；低频率是本条的纪律）。 */
const LOCAL_RECONCILE_THROTTLE_MS = 60000;
let lastLocalReconcileAt = 0;

/** 本地层后台对账拍（sessperf Phase B · 卡 §4②「后台对账：挂靠**既有** 10 s
 *  beacon，**禁新增定时器**」）：预算收口（L5）+ 头像 TTL 兜底刷新。
 *  🔴 判据独立于下面的帧静默门控 —— 「预算是否超限 / 头像是否过期」与「帧是否在流」
 *  无关；自身 60 s 节流 + 每拍有界（`reconcile` 内），故帧健康态也只多一次无网络
 *  的对账（仅超限/过期时才动网）。 */
function localReconcileTick() {
  if (!isLocalStoreEnabled()) return;
  const now = Date.now();
  if (now - lastLocalReconcileAt < LOCAL_RECONCILE_THROTTLE_MS) return;
  lastLocalReconcileAt = now;
  void reconcileLocalStore();
}

async function backfillTick() {
  if (!loggedIn() || document.hidden) return;
  localReconcileTick(); // 本地层对账（无网时为纯记账 + 驱逐；见上注）
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
  // ①-b 设备腿**传输生命周期**（uxconsist Phase B · §3.3）：通知源 = dropbox.js 的
  //     `onDeviceTransfer` 单点（入队 / 受理 / 块级进度 / 完成 / 失败 / 取消）。
  //     本模块据此**登记显示态**（附件的动作面上屏）—— 只共享显示，传输链一行不碰：
  //     设备腿的反馈面因此**与数据面选路解耦**（`onDeviceMessageChanged` 里
  //     `sourceServer` 的早退只挡 legacy 数据面重渲，不再连带挡掉附件反馈）。
  onDeviceTransfer((evt) => { onDeviceTransferEvent(evt); });
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

/**
 * 设备附件腿的**显示态登记**（uxconsist Phase B · §3.2 登记口 + §3.3 emission）。
 *
 * 把 dropbox.js 的传输生命周期映射成卡表条目（八态的附件面取值）：
 *   `queued`→S1 卡行 · `transferring`→S3 起 · `progress`→S3 数值 · `sent`→S6（无痕前卡行收口）
 *   · `failed`→S4 失败卡 + 重试键 · `cancelled`→S8 · `rejected`（闸位/未连接/offer 被拒）→S4 就地可见。
 *  🔴 只登记**显示**：本模块不发起、不中止任何设备传输（取消/重试都经 dropbox.js 的导出入口）。
 *  🔴 文案零新增：全部取 §8 白名单键（失败 = `messages.attachFailed`，取消 = `messages.attachCancelled`）。
 *  @param {any} evt dropbox.js 的传输 emission */
function onDeviceTransferEvent(evt) {
  if (!evt || !evt.deviceId || !evt.id) return;
  const convId = DEVICE_CONV_PREFIX + evt.deviceId;
  const retryFiles = evt.file ? [evt.file] : (Array.isArray(evt.files) ? evt.files : []);
  const onRetry = retryFiles.length > 0 ? () => sendDeviceFiles(evt.deviceId, retryFiles) : null;
  const onCancel = () => cancelDeviceTransfer(evt.deviceId, evt.id);
  const base = { transferId: evt.id, name: evt.name || '', size: Number(evt.size) || 0 };
  switch (evt.phase) {
    case 'queued': // S1
      registerExternalTransfer(convId, { ...base, state: 'queued', onRetry, onCancel });
      break;
    case 'rejected': // 闸位 / 未连接 / offer 被拒 ⇒ S4（就地可见，禁静默）
      registerExternalTransfer(convId, {
        ...base, state: 'failed', code: evt.code || 'rejected',
        error: evt.message || t('messages.attachFailed'), onRetry, onCancel: null,
      });
      break;
    case 'transferring': // S3 起（受理）
      updateExternalTransfer(convId, evt.id, { state: 'uploading', onCancel });
      break;
    case 'progress': // S3 数值（唯一来源 = 服务端确认分块）
      updateExternalTransfer(convId, evt.id, {
        bytesSent: Number(evt.bytesSent) || 0,
        totalBytes: Number(evt.totalBytes) || base.size,
      });
      break;
    case 'sent': // S6
      updateExternalTransfer(convId, evt.id, { state: 'sent', bytesSent: base.size, totalBytes: base.size, onCancel: null });
      break;
    case 'failed': // S4
      updateExternalTransfer(convId, evt.id, {
        state: 'failed', code: evt.code || 'upload_failed',
        error: evt.message || t('messages.attachFailed'), onRetry, onCancel: null,
      });
      break;
    case 'cancelled': // S8
      updateExternalTransfer(convId, evt.id, {
        state: 'cancelled', code: 'cancelled',
        error: evt.message || t('messages.attachCancelled'), onRetry: null, onCancel: null,
      });
      break;
    default:
      break;
  }
}

/** 设备工作集（本模块持有的适配后消息数组；与 `chatMsgs` 同构，但按设备分开）。 */
let deviceMsgs = [];

/** 设备工作集**上次快照的 id 集合**（uxconsist Phase B · R2-device 认领判据）：
 *  认领只认**新落地**的行 —— 否则「窗口里早有一条同文消息」会把本次乐观面误锚到旧行上。 */
let deviceSeenIds = new Set();

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
    // 收侧语义（逐字不变）：接收端落点。
    deviceSavedPath: m.savedPath || '',
    // selfattach 片 2（A 腿）：**发送侧**本机真字节句柄（`File`，只在本次页面会话内有效；
    // 刷新即无 ⇒ 按作者 D-2 回落「名称 + 状态」形态）。🔴 另表另键，禁借用 `deviceSavedPath`。
    deviceOutHandle: ours ? outFileHandleOf(m.transferId) : null,
    // selfattach 片 1（B′ 腿）：**发送端本机真实路径**（后台台账字段，跨刷新存活，agent 腿专有）。
    deviceOutPath: m.deviceOutPath || '',
    // 传输 id：本机句柄表与预览 tab id 的稳定键（收/发两向都有，值来自台账/wire）。
    deviceTransferId: m.transferId || '',
    deviceOut: ours,
    devicePct: pct,
  };
}

/** 设备分支**唯一**字节源判定点（selfattach 批；禁第二处判据、禁第二张表）。
 *
 *  优先级（逐条给理由，非随手排序）：
 *    ① **`blob`** —— 发送侧本机 `File` 句柄（A 腿）。它在手 = 本机确有这件字节，
 *       比任何路径都直接（`File` 是盘上惰性句柄，不是副本）；
 *    ② **`path`** —— 本机绝对路径：收侧落点 `deviceSavedPath`（收腿）/ 发送端真路径
 *       `deviceOutPath`（B′ 腿，agent 发）；两者都是「本机有可读件」的既证，走既有
 *       `nfTicket` ⇒ `/api/nf-file` 链；
 *    ③ **`none`** —— 无任何本机字节可读 ⇒ **不挂**可点面/下载键/直显槽（§B.7 ③
 *       「禁可点但点了报错」），状态位文案即用户可见的说明面。
 *
 *  🔴 **未完成 / 失败 / 被拒态恒 `none`**：改前该门控是**隐式**的（发送侧压根没有路径，
 *  接收侧 `savedPath` 只在完成时写）——本批把这条既有语义**显式化**，防「传输中就能点开」
 *  的新面（任务书 N4：五态仍 `hasDlKey:false / previewable:false / inline:false`）。
 *  @param {any} att 归一附件对象 @returns {{kind:'blob', file:File}|{kind:'path', path:string}|{kind:'none'}} */
function deviceByteSourceOf(att) {
  const status = (att && att.deviceStatus) ? String(att.deviceStatus) : '';
  if (status !== 'completed') return { kind: 'none' };
  const file = (att && att.deviceOutHandle) ? att.deviceOutHandle : null;
  if (file) return { kind: 'blob', file };
  const saved = (att && att.deviceSavedPath) ? String(att.deviceSavedPath) : '';
  if (saved) return { kind: 'path', path: saved };
  const out = (att && att.deviceOutPath) ? String(att.deviceOutPath) : '';
  if (out) return { kind: 'path', path: out };
  return { kind: 'none' };
}

/** 设备卡预览 tab id（**同件重复点击 = 同一 tab** 的稳定键）。
 *  path 腿沿用改前的 `attach-local:<路径>`（逐字不变，收侧行为零回归）；
 *  blob 腿用 `attach-out:<transferId>`（句柄无路径可作键；同一 transferId = 同一件）。 */
function devicePreviewIdOf(att, src) {
  if (src.kind === 'blob') return `attach-out:${(att && att.deviceTransferId) || (att && att.name) || ''}`;
  return `attach-local:${src.path}`;
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

// ── legacy 台账腿未读（作者令 2026-09-17 12:58，**翻转** 2026-09-16 的「不计数」）──
// 设备会话里经 **legacy 台账腿**（`~/.nebflow/dropbox/messages.json`，带 `dir in/out`）
// **到达**的入向消息计入未读/角标；打开会话 ⇒ 按**既有已读语义**清零（不改其语义）。
//
// 口径（逐条给理由，与好友/群面的「self 不计未读 / 回放不计未读」同族）：
//  · **只计入向**：`direction === 'in'`（网关侧已按本机视角判定的方向）⇒ 计入；
//    `dir=out`（我发出的）**恒不计**；方向键缺席 / 越界值 ⇒ 不计（**不猜**，保守向）。
//    与 `adaptDeviceMessage` 的 legacy 支**同一条证据**（那里 `direction === 'out'` ⇒
//    ours）—— 同一字段、同一语义，只是消费点不同（那里画面向，这里画未读）。
//  · **只计「到达」，不计基线**：L2 缓存（`fmDropboxCache`）灌回的既有消息 = 上次会话
//    已见过 ⇒ 作**基线**入 `seen` 集，**不涨角标**（与好友面「回放帧不计数」同一条纪律：
//    否则冷启动/开窗回补就把历史刷成角标虚高）。此后工作集里**新出现**的入向 id
//    才是「到达」。
//  · **窗开着 ⇒ 即已读**（既有语义）：窗开时不涨（到达的 id 仍入 `seen`，否则关窗瞬间
//    会**补涨** —— 阅读中到达的消息在关窗时变成「未读」是反语义）；开窗动作本身清零。
//  · **服务端腿不介入**：有服务端行（`sourceServer`）的窗未读 = **服务端权威**（§8.1），
//    本支恒不介入（`deviceConvs` 的 `if (w.serverRows.length)` 支照旧读服务端行）。
//  · **零新取数通道**：只消费既有刷新链的两拍 —— `deviceConvs` 的 `hydrateDeviceCache`
//    （基线）与 `onDeviceMessageChanged`（= dropbox.js `afterDeviceMessageChange` 单点，
//    由 `dropbox-message` / `dropbox-history` 驱动）。**禁**在此另挂 WS 帧监听。
//  · **禁新造已读状态机**：清零只落本地读数（`markDeviceConvRead` 对 legacy 腿首行即
//    return —— 无服务端行 = 无可上报游标）；不清 L2、不写 cursor、不加 wire 字段。
//  · **有界**：每设备 `seen` FIFO ≤512（与 `countedUnreadIds` 同族上限）；设备表 ≤256
//    （FIFO 淘汰，淘汰即连同未读读数归零 —— 与「不在列表里就没有角标可显示」自洽）。
const LEGACY_SEEN_MAX = 512;
const LEGACY_DEV_MAX = 256;
/** deviceId → {seen: Set<string>, order: string[]}（已入账的入向 id；基线亦入此集）。 */
const legacyInboundSeen = new Map();
/** deviceId → number（legacy 台账腿未读读数；开窗清零）。 */
const legacyUnread = new Map();

/** 取（惰性建）某设备的入向 id 台账，并按设备数上限淘汰。 */
function legacySeenOf(deviceId) {
  let e = legacyInboundSeen.get(deviceId);
  if (!e) {
    e = { seen: new Set(), order: [] };
    legacyInboundSeen.set(deviceId, e);
    if (legacyInboundSeen.size > LEGACY_DEV_MAX) {
      const oldest = legacyInboundSeen.keys().next().value;
      if (oldest !== undefined && oldest !== deviceId) {
        legacyInboundSeen.delete(oldest);
        legacyUnread.delete(oldest);
      }
    }
  }
  return e;
}

/** 入向 id 首次入账（有界 FIFO）。@returns {boolean} true = 首次见（= 「到达」）。 */
function markLegacyInboundSeen(deviceId, id) {
  if (id === undefined || id === null || id === '') return false; // 无稳定键 ⇒ 不判（不猜）
  const e = legacySeenOf(deviceId);
  const k = String(id);
  if (e.seen.has(k)) return false;
  e.seen.add(k);
  e.order.push(k);
  if (e.order.length > LEGACY_SEEN_MAX) {
    const oldest = e.order.shift();
    if (oldest !== undefined) e.seen.delete(oldest);
  }
  return true;
}

/** 入向判据单点（legacy 行 = `msgId`/`direction` 形态，见 `adaptDeviceMessage`）。 */
function isLegacyInbound(m) {
  return !!m && m.direction === 'in';
}

/** 基线灌入：L2 灌回的既有入向只入 `seen`，**不涨未读**（由 `deviceConvs` 与
 *  `clearLegacyUnread` 两处调用 —— 两处都是「这些消息已被见过」的语义点）。 */
function seedLegacyInboundBaseline(deviceId) {
  for (const m of deviceMessagesOf(deviceId)) {
    if (isLegacyInbound(m)) markLegacyInboundSeen(deviceId, m.msgId);
  }
}

/** legacy 台账腿未读读数（`deviceConvs` 的 legacy 窗与到达拍共用，单一读数点）。 */
function legacyUnreadOf(deviceId) {
  return legacyUnread.get(deviceId) || 0;
}

/** 到达一拍：工作集里**新出现**的入向 id 计入未读（`onDeviceMessageChanged` 调用）。
 *  @returns {number} 本拍新增条数（0 = 无变化）。 */
function noteLegacyInboundArrivals(deviceId) {
  const convId = DEVICE_CONV_PREFIX + deviceId;
  const conv = conversations.find(c => c.conversationId === convId) || null;
  if (conv && conv.sourceServer) return 0; // 服务端腿 = 服务端权威（本支不介入）
  const reading = openConvId === convId; // 窗开着 ⇒ 即已读
  let added = 0;
  for (const m of deviceMessagesOf(deviceId)) {
    if (!isLegacyInbound(m)) continue;
    if (!markLegacyInboundSeen(deviceId, m.msgId)) continue;
    if (!reading) added += 1;
  }
  if (added > 0) {
    legacyUnread.set(deviceId, legacyUnreadOf(deviceId) + added);
    if (conv) conv.unreadCount = legacyUnreadOf(deviceId);
    updateBadge();
  }
  return added;
}

/** 开窗清零（**既有已读语义**：窗开着 ⇒ 即已读；对偶 = `markDeviceConvRead` 的服务端腿）。
 *  legacy 腿无服务端游标可上报 ⇒ 只落本地读数 + 当前工作集入基线（关窗后**新到达**才再涨）。 */
function clearLegacyUnread(conv) {
  const did = conv && conv.device ? conv.device.deviceId : '';
  if (!did) return;
  legacyUnread.set(did, 0);
  seedLegacyInboundBaseline(did);
  conv.unreadCount = 0;
  // 列表行对象可能不是同一个（`openDeviceChat` 的空缺支造的临时 conv 不入列表）⇒ 按 id 找行同步。
  const row = conversations.find(c => c.conversationId === DEVICE_CONV_PREFIX + did);
  if (row) row.unreadCount = 0;
  updateBadge();
  renderList();
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
      // D5（2026-09-17 12:58 卡**翻转** 09-16 采纳项）：无服务端行 ⇒ **服务端**无未读可读；
      // 未读改由 **legacy 台账腿**读数承担（`legacyUnreadOf`，口径见本段上方块注释）——
      // 改前此处恒 0（= 旧登记「legacy 不计数」，已 archived）。
      // ⑥：本地缓存的**唯一入口**是 `hydrateDeviceCache`（L2 → 内存工作集）——先灌
      // 再读，否则「上次会话聊过、本次未开窗」的设备行会显示空预览（看着像没聊过）。
      // 🔴 灌回顺序即判据顺序：**先基线、后读数** —— L2 灌回的既有入向 = 已见过，
      // 不得计入本次未读（与好友面「回放帧不计数」同一条纪律）。
      hydrateDeviceCache(did);
      seedLegacyInboundBaseline(did);
      w.unreadCount = legacyUnreadOf(did);
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
  const conv = currentConv();
  adoptDeviceMsgs(next, (conv && conv.kind === 'device'
    && conv.conversationId === DEVICE_CONV_PREFIX + deviceId) ? conv : null);
}

/** 设备窗消息数组的**唯一换装点**（uxconsist Phase B · §4.1-#7「先认领再重渲」）。
 *
 *  两条取数腿（服务端 keyset 重取 / legacy 台账同步）都经此 ⇒ 认领判据只有一份
 *  （禁在各取数腿各写一次）。`deviceSeenIds` = 上一次窗口快照 ⇒ 认领**只认新落地**的行
 *  （禁把历史里同文的旧行当成这次动作的权威行）。
 *  @param {any[]} next 新窗口数组 @param {any|null} conv 设备会话（非设备窗 ⇒ `null`） */
function adoptDeviceMsgs(next, conv) {
  const prev = deviceSeenIds;
  deviceMsgs = next;
  deviceSeenIds = new Set(next.map(x => String(x.id)));
  if (conv) claimDeviceEcho(conv, prev);
}

/** **R2-device 认领**：把本窗**新落地**的本机所发行认领到未决乐观项上
 *  （→ `sendState.claim` 的原地换键：不新增气泡、节点数不变，data-send-phase 由调用方
 *  收口为 `confirmed`）。服务端腿与 legacy 腿**共用**本函数。
 *  @param {any} conv @param {Set<string>|null} prevIds 发送前/上次同步的 id 快照 */
function claimDeviceEcho(conv, prevIds) {
  if (!conv || !conv.conversationId) return false;
  let claimed = false;
  for (const m of deviceMsgs) {
    if (!m || !m.id) continue;
    if (prevIds && prevIds.has(String(m.id))) continue; // 旧行：不是本次动作的权威行
    if (resolveOut(m, conv) !== true) continue; // 只认本机所发（无证据 ⇒ 不认）
    // `presentOk`：设备取数是**整窗替换** ⇒ 权威行必然已在窗内（§ sendState.claim 注释）。
    if (claimSend(deviceMsgs, m, { convId: conv.conversationId, ours: true, presentOk: true })) {
      claimed = true;
      claimDeviceNodePhase(m, conv);
    }
  }
  return claimed;
}

/** 认领成功的节点置 **S6**（送达 = 无痕）：按 `data-message-id` 找节点（`claim` 已把节点
 *  换键成权威 id ⇒ 此处只读结果）。用**逐个比对**而非选择器拼接（id 可能是任意字符串，
 *  禁把 id 直接拼进选择器）。 */
function claimDeviceNodePhase(m, conv) {
  if (!modalEls || !modalEls.conv || modalEls.conv.kind !== 'device') return;
  const key = String(m.id);
  for (const n of modalEls.flow.querySelectorAll('.fm-msg')) {
    if (n.dataset.messageId === key) applyPhase(n, PHASE.SENT);
  }
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
  // 判据收敛进能力位（§4.1-#8）：设备服务端腿才有回执源；好友 / 群 = 候源（R5 两格）。
  if (!conv || !capabilitiesOf(conv, SEND_LEG.TEXT).receipt) return;
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
 *  不走重渲染（`renderMessages` 会重建全窗）——回执到达不移动任何气泡。
 *  📌 uxconsist Phase B（§4.1-#8）：出现判据收敛进 `capabilitiesOf(conv).receipt`；
 *  槽位的**呈现**（有 slot 才画、无 slot 不画空槽）收敛到 `sendState.applyReceipt` ——
 *  该函数同时是 R5 两格（好友 / 群）的**预留接口位**（候后端回执源契约，本批禁实现）。 */
function applyDeviceReceipts(conv) {
  if (!modalEls || !modalEls.conv || modalEls.conv.kind !== 'device') return;
  if (openConvId !== (conv && conv.conversationId)) return;
  if (!capabilitiesOf(conv, SEND_LEG.TEXT).receipt) return;
  for (const wrap of modalEls.flow.querySelectorAll('.fm-msg.out')) {
    applyReceipt(wrap, deviceReceiptStateOf(wrap.dataset.messageId));
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

// ── 账号回执源：好友 / 群回执槽位接线（R5 两格 · uxconsist Phase B 段 2）──────
// 契约正本 = neblink-server `.nebflow/Spec/20260920_192859_friend-group-receipt-source.md`
// **v1.2**（判词 PASS 6/6；sha256 `640de17b2d527b73ce7038bdd3eb80a7ce4c1ece03d0a004451d74bce2d35c20`，
// 本批开工首动作留痕）：§1.1 E1 读面（窗口口径）/ E2 上报面（不变）/ E3 推帧 envelope、
// §1.2 状态机、§1.3 幂等、§1.4 错误码、§4.2 帧形、§4.5（帧可丢，E1 是真相源）。
// 🔴 设备面回执源（`deviceReceipts` / `refreshDeviceReceipts` / `applyDeviceReceipts`）
//    **零改动**（卡射程外：设备面走 E2EE 原则）——本段只服务好友（direct）/ 群两面。

/** 每会话回执**读面**缓存（会话 id → `{rows, kind, memberCount}`）。
 *  派生态、不落盘（与 `deviceReceipts` / `markOurs` 同一条「派生态不落盘」纪律）；
 *  取数失败**保留上次已知**（回执是增强信息，不是消息本体的承重面）。 */
const convReceipts = new Map();

/** E1 窗口的 `limit`（契约 §1.1：计数单位 = **消息 id**，服务端 `clamp(1, 500)`）。 */
const RECEIPT_WINDOW_LIMIT = 500;

/** E1 窗口（契约 §1.1）：`after` = 已载窗口**最早**消息 id − 1 ⇒ 窗口内我发出的消息
 *  **全部**在射程内（服务端判据 `messageId > after`）；无已载消息 ⇒ 无「我发出的」面
 *  可读 ⇒ null（不发请求；也避免缺 `after` 时响应体随会话长度线性增长，观察项 O1）。 */
function receiptWindow() {
  let oldest = 0;
  for (const m of chatMsgs) {
    const n = Number(m.id);
    if (Number.isFinite(n) && n > 0 && (oldest === 0 || n < oldest)) oldest = n;
  }
  return oldest > 0 ? { after: oldest - 1, limit: RECEIPT_WINDOW_LIMIT } : null;
}

/** E1 响应 → 逐消息计数索引（**窗口内**口径，契约 §1.1 派生式）。
 *
 *  计数正确性由契约保证（窗口按**消息 id** 计 `limit`，窗口内每条消息的**全部**确认者
 *  行都返回）⇒ 每条消息要么**计数完整**、要么**整条不在返回集内**（不出现）——不存在
 *  「部分行」形态 ⇒ 不会出现少报的计数（未覆盖的消息只是**不画**槽位）。
 *  直聊确认者恒 1（1:1，`userId`/`updatedAt` 键整键省略）；群聊按确认者行累加。 */
function indexReceiptRows(resp) {
  const rows = new Map();
  for (const r of (resp && Array.isArray(resp.receipts) ? resp.receipts : [])) {
    const mid = Number(r && r.messageId);
    if (!Number.isFinite(mid) || mid <= 0) continue;
    const cur = rows.get(mid) || { read: 0, delivered: 0 };
    cur.delivered += 1; // sent ∪ read —— read 蕴含送达（§1.2 状态机）
    if (r && r.state === 'read') cur.read += 1;
    rows.set(mid, cur);
  }
  const mc = Number(resp && resp.memberCount);
  return {
    rows,
    kind: (resp && resp.conversationKind === 'group') ? 'group' : 'direct',
    // `memberCount` = 可确认者数 = 成员数 − 1（请求者自己）；直聊概念上恒为 1（§1.1）。
    memberCount: (Number.isFinite(mc) && mc > 0) ? mc : 1,
  };
}

/** 某条消息的回执态 + 计数面（`{state, counts}`；无确认 ⇒ `null` = **不画**）。
 *  判据 = 契约 §1.1 派生式（`readCount` / `deliveredCount` 逐条从 `receipts[]` 派生），
 *  **不用**两个高水位反推 —— 高水位是全会话 MAX，用它反推会给「窗口内无确认」的消息
 *  画出假状态（高水位蕴含的区间并不等于「每条都已确认」）。
 *  @returns {{state: 'sent'|'read', counts: {read: number, delivered: number, total: number}|null}|null} */
function convReceiptStateOf(convId, messageId) {
  const entry = convReceipts.get(String(convId));
  if (!entry) return null;
  const n = Number(messageId);
  if (!Number.isFinite(n) || n <= 0) return null;
  const row = entry.rows.get(n);
  if (!row) return null; // 无确认的消息**不出现**（= 未送达）⇒ 无空槽
  const counted = entry.kind === 'group';
  return {
    state: row.read > 0 ? 'read' : 'sent',
    counts: counted ? { read: row.read, delivered: row.delivered, total: entry.memberCount } : null,
  };
}

/** 降级痕迹（**一次 / 会话 / 原因**，禁每次刷新刷屏）。
 *  §1.4 的处置形态是「槽位关闭降级」而不是「裸红」：用户面**零噪声**（无 toast、无错误条），
 *  但保留一条可诊断痕迹（否则「不支持 / 非成员 / 网络」与「本就无回执」不可分）。 */
const receiptDegraded = new Map();
function noteReceiptDegrade(convId, err) {
  const status = (err && err.status) || 0;
  const data = err && err.data;
  // §1.4 逐码：403 鉴权失效（`req()` 已派全局登录链）/ 403 not_member（非成员 *或* 会话
  // 不存在——同一函数闸）/ 400（窗口参数被拒：axum 默认**纯文本**体 ⇒ `err.data` 缺席）/
  // 其余（5xx / 网络 / 422）。
  const code = (data && typeof data === 'object' && data.error) || (status ? 'non_json_body' : 'network');
  const reason = status ? `${status}:${code}` : 'network';
  if (receiptDegraded.get(convId) === reason) return;
  receiptDegraded.set(convId, reason);
  console.warn(`[fm] 回执面降级（槽位关闭、保留上次已知）conversationId=${convId} `
    + `reason=receipt_source_unavailable status=${status} code=${code}`);
}

/** 回执面刷新的单飞 + 尾随合并（同一会话并发只发一次请求；请求在飞期间到达的后续变化
 *  记一个「还要再来一次」⇒ **不丢最后一次状态变化**，也不因帧风暴并发打同一端点）。 */
const convReceiptInflight = new Set();
const convReceiptAgain = new Set();

/** **E1 读半程**（唯一的回执取数点）：拉 `GET …/receipts`（v1.2 窗口口径）⇒ 就地补画。
 *
 *  触发面（全部只此一处取数）：① 开窗（好友 / 群）② E3 帧到达（咨询性唤醒）
 *  ③ 唤醒 / 通道降级腿（`incrementalResync`，契约 §1.1 前端处置契约③「重连 / 切回窗口
 *  必须重拉 E1」）。🔴 失败一律**降级**：保留上次已知、槽位无数据即不画、零重试
 *  （回执是增强信息，禁「持续无输出」式循环）。 */
async function refreshConvReceipts(conv) {
  if (!conv || !conv.conversationId) return;
  if (conv.kind === 'device') return;                        // 设备面零触碰（E2EE 原则）
  if (!capabilitiesOf(conv, SEND_LEG.TEXT).receipt) return;   // 能力位（单点判据）
  const win = receiptWindow();
  if (!win) return;                                           // 空窗 ⇒ 无可读面，不打请求
  const convId = String(conv.conversationId);
  if (convReceiptInflight.has(convId)) { convReceiptAgain.add(convId); return; }
  convReceiptInflight.add(convId);
  try {
    const resp = await api.getConversationReceipts(convId, win);
    convReceipts.set(convId, indexReceiptRows(resp));
    receiptDegraded.delete(convId); // 恢复 ⇒ 清降级痕迹（下次失败重新留痕）
    applyConvReceipts(conv);
  } catch (err) {
    noteReceiptDegrade(convId, err);
  } finally {
    convReceiptInflight.delete(convId);
    if (convReceiptAgain.delete(convId)) void refreshConvReceipts(conv); // 尾随合并
  }
}

/** 把回执态就地补画到已渲染的**本机所发**气泡（只碰当前窗；**不重渲染**——回执到达
 *  不移动任何气泡，与设备面同一条纪律）。挂载点 = `sendState.applyReceipt`（呈现单点，
 *  设备 / 直聊 / 群三面同源；有 slot 才画、无 slot 不画空槽）。 */
function applyConvReceipts(conv) {
  if (!modalEls || !conv || conv.kind === 'device') return;
  if (openConvId !== conv.conversationId) return;
  if (!capabilitiesOf(conv, SEND_LEG.TEXT).receipt) return;
  for (const wrap of modalEls.flow.querySelectorAll('.fm-msg.out')) {
    const st = convReceiptStateOf(conv.conversationId, wrap.dataset.messageId);
    applyReceipt(wrap, st ? st.state : null, st ? st.counts : null);
  }
}

// ── E3 推送帧（契约 §1.1 / §4.2）：`event.type = "receipt_update"` ────────────
// 前端处置契约（卡 §1.1 逐字）：① **不得 ack**（本分支**不发任何帧**；`receipt-` 前缀
// 不在 ack 分派任何一条臂内，误 ack 亦无副作用）② 可本地合并计数或**直接重拉 E1**
// ③ 重连 / 切回窗口**必须**重拉 E1（E1 是真相源；帧只是咨询性唤醒、可能被丢弃 §4.5）。
// ⇒ 本实现取②的后一支：**一律重拉 E1**（不本地合并计数 —— 计数唯一来源保持单点，
// 「已读 3/8」不会因丢帧而停在旧值）。
/** 事件名常量（与 `EV_MESSAGE_NEW` 同一条「两侧不得各写一套字面量」纪律；服务端
 *  字面量 = `receipt_update`）。 */
const EV_RECEIPT_UPDATE = 'receipt_update';

/** E3 帧级去重（§1.3）：`eventId` **确定性**（同 (会话,确认者,状态,水位) 恒同串）
 *  ⇒ 按 `eventId` 塌缩；重复帧**零正确性后果**（连 E1 请求都不发）。
 *  有界 FIFO ≤512（与 `seenFrameMessageIds` 同族上限）；缺 `eventId` **不拦**
 *  （「不猜」口径同族先例：拦掉会让真帧静默消失，放宽只会多一次幂等 E1 拉取）。 */
const seenReceiptEvents = new Set();
const seenReceiptEventOrder = [];
const SEEN_RECEIPT_EVENT_MAX = 512;
/** @returns {boolean} true = 首次见到（继续）；false = 重复（调用方直接 return）。 */
function markReceiptEventSeen(id) {
  if (id === undefined || id === null || id === '') return true;
  const k = String(id);
  if (seenReceiptEvents.has(k)) return false;
  seenReceiptEvents.add(k);
  seenReceiptEventOrder.push(k);
  if (seenReceiptEventOrder.length > SEEN_RECEIPT_EVENT_MAX) {
    const oldest = seenReceiptEventOrder.shift();
    if (oldest !== undefined) seenReceiptEvents.delete(oldest);
  }
  return true;
}

/** 落盘条目 → `adaptDeviceMessage` 的入参形状（**唯一**转换点）。
 *
 *  两腿共用：服务端行条目（`senderDeviceId` 面）与 legacy 导入条目（`direction`/
 *  `ts` 面）。方向**不落盘**（见 `localStore.devEntry`）⇒ 读回时按同一单点重算，
 *  换机 / 换账号后的判据不被冻结。 */
function deviceEntryToWire(e) {
  if (!e || typeof e !== 'object') return null;
  if (e.legacy) {
    return {
      msgId: e.id, direction: e.direction, ts: e.ts, kind: e.kind, text: e.body,
      status: e.status, transferId: e.transferId, fileName: e.fileName, fileSize: e.fileSize,
    };
  }
  return {
    id: e.id, senderDeviceId: e.senderDeviceId, body: e.body,
    createdAtMs: e.createdAtMs, kind: e.kind, attachments: e.attachments,
  };
}

/** 一行会话的本地层条目（同步）→ 已 adapt 的消息（只认**服务端行**条目：
 *  legacy 导入条目无 keyset 语义、由 `dropbox.js` 既有层消费，禁混入本腿）。 */
function cachedDeviceRowMsgs(convId) {
  const cached = readMessages(convId);
  if (!cached) return [];
  const out = [];
  for (const e of cached.msgs) {
    if (!e || e.shape !== 'dev' || e.legacy) continue;
    const a = adaptDeviceMessage(deviceEntryToWire(e));
    if (a && a.id) out.push(a);
  }
  return out;
}

/** 设备服务端腿**首帧**：本地层条目 → 归并窗（同步、零 await、零网络）。 */
function cachedDeviceServerMsgs(conv) {
  const merged = [];
  for (const row of (conv && conv.serverRows) || []) {
    for (const m of cachedDeviceRowMsgs(row.conversationId)) merged.push(m);
  }
  merged.sort(compareDeviceMsg);
  return merged;
}

/** 设备会话服务端取数（keyset 尾窗，D9：MVP-2 由「网关全量」转 keyset）。
 *  🔴 **归并窗 = 两条会话各自取数后按 id 归并**（§9.3）：对端行给出「对端发来的」、
 *  本机行给出「我发出的」，两路都在同一实现面取（禁各自演化）。
 *  任一路取数失败 ⇒ 整窗降级 legacy dropbox 腿（可见提示），不半窗呈现。
 *
 *  sessperf Phase B（卡 §4②「开窗零全量重拉」+ §5.3 改动面）：本地层有该行条目 ⇒
 *  走 `after=<水位>&limit=50` keyset 增量（≤4 页，`MAX_SYNC_PAGES` 数值不变）；
 *  本地层无条目 ⇒ 冷路径仍是既有尾窗（M6「冷缓存不倒退」基线不变）。
 *  写回本地层（键 = 该行 `conversationId`）⇒ 下一次开窗首帧零网络。 */
async function fetchDeviceMsgsServer(conv) {
  const rows = (conv && conv.serverRows) || [];
  if (!rows.length) { deviceMsgs = []; return; }
  let failure = null;
  for (const row of rows) {
    const convId = row.conversationId;
    const cached = readMessages(convId);
    const prevEntries = cached ? cached.msgs.filter(e => e && e.shape === 'dev' && !e.legacy) : [];
    let watermark = cached ? cached.watermark : 0;
    const fetched = [];
    try {
      if (watermark > 0) {
        for (let i = 0; i < MAX_SYNC_PAGES; i++) {
          const batch = await api.getMessages(convId, { after: watermark, limit: SYNC_PAGE });
          const list = batch || [];
          for (const m of list) fetched.push(m);
          const maxId = list.reduce((n, m) => Math.max(n, Number(m && m.id) || 0), 0);
          if (maxId > watermark) watermark = maxId;
          if (list.length < SYNC_PAGE) break; // 不满页 ⇒ 已到服务端水位
        }
      } else {
        // 本地层无条目 ⇒ 冷路径（与改前逐字同：锚 = 会话行 lastMessage.id 回退一窗）
        const anchor = Number(row.lastMessage && row.lastMessage.id);
        const after = Number.isFinite(anchor) && anchor > HISTORY_WINDOW ? anchor - HISTORY_WINDOW : 0;
        const list = await api.getMessages(convId, { after, limit: HISTORY_WINDOW }) || [];
        for (const m of list) fetched.push(m);
        const maxId = list.reduce((n, m) => Math.max(n, Number(m && m.id) || 0), 0);
        watermark = Math.max(watermark, maxId);
      }
    } catch (err) {
      failure = err; // 任一路失败 ⇒ 整窗降级（不半窗呈现，既有语义）
      break;
    }
    if (isLocalStoreEnabled()) {
      void writeMessages(convId, prevEntries.concat(fetched), {
        watermark,
        lastMessageAt: toEpochMs(row.lastMessage && row.lastMessage.createdAt),
        shape: 'dev',
      });
    }
  }
  if (failure) {
    // 判红④：服务端面不可达（404 neblinkOff / 未登录 / 网络）⇒ **可见降级**，
    // 落 legacy dropbox 腿，绝不留白窗、绝不抛错崩窗。
    syncDeviceMsgs(conv.device.deviceId);
    deviceMsgs = deviceMsgs.map(m => ({ ...m }));
    if (failure && (failure.status === 404 || failure.status === 403 || failure.status === 401)) {
      modalToast(t('messages.deviceServerUnavailable'));
    }
    return;
  }
  if (openConvId !== conv.conversationId) return; // 窗已被替换
  const next = [];
  for (const row of rows) {
    // 渲染面 = **本地层**（写回后的镜像：同一条读取路径 ⇒ 与下一次开窗首帧逐字同形）
    for (const m of cachedDeviceRowMsgs(row.conversationId)) next.push(m);
  }
  next.sort(compareDeviceMsg);
  // 🔴 唯一换装点：先认领（未决乐观项原地换键）再交调用方重渲（§4.1-#7「先认领再重渲」）。
  adoptDeviceMsgs(next, conv);
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
 *
 *  📌 uxconsist Phase B（§4.1-#7 · 格 R1/R2/R3/R4：设备文本腿）：
 *   · **R1/R2**：发送前先上**乐观气泡 + 左侧环**（与 `sendCurrent` **同一调用序**），
 *     keyset 权威行回来时**原地认领**（不新增气泡）；
 *   · **R3**：失败主面 = **气泡 S4**（红圈 + 重试键），不再 toast-only、不再回填输入框；
 *     语义终态码（403/404）⇒ **S7**：气泡撤除 + 分态提示（`messages.deviceSendFailed`
 *     由「唯一面 toast」降级为 S7 专用面，§8.2 改写②）；
 *   · **R4**：重试键复用**同一 `clientMsgId`**（`deviceRetry` 键记忆的同一语义，禁新造键面）。 */
async function sendDeviceCurrent(conv, opts) {
  if (!modalEls || !conv || !conv.device) return;
  // 重试面（气泡上的重试键）把正文与键**显式带来**（正文此时只在气泡上，不回填输入框）。
  const fromOpts = !!(opts && opts.body);
  const body = String(fromOpts ? opts.body : modalEls.input.value).trim();
  if (!body || body.length > 2000) return; // D8：与好友窗同闸（2000，服务端无 enforcement）
  const selfId = selfDeviceId();
  if (conv.sourceServer && !selfId) {
    modalToast(t('messages.deviceSendUnavailable'));
    return; // 内容留在输入框（输入框此刻是禁用态，正常路径到不了这里）
  }
  if (!fromOpts) {
    modalEls.input.value = '';
    syncComposerSend();
  }
  // P2-b 幂等键：服务端腿才走契约幂等面（legacy 腿数据面在 dropbox.js，不在本键面）。
  // 同动作判定 = (会话 id, 正文) 逐字相等 ⇒ 复用失败时记下的键；否则新动作 ⇒ 新键。
  const retryConvId = conv.conversationId || '';
  const remembered = (opts && opts.clientMsgId)
    || (deviceRetry && deviceRetry.convId === retryConvId && deviceRetry.body === body
      ? deviceRetry.clientMsgId : null);
  const clientMsgId = remembered || newClientMsgId();
  if (!remembered) deviceRetry = null; // 新动作 ⇒ 陈旧记忆作废（禁跨动作误判重）

  // ── R1-device / R2-device：乐观面（**同一调用序**，见 `armOptimisticText`）──────
  // 「双往返期间屏上零变化」= 本格病灶：改前这里**不上屏任何东西**，只有 2s toast +
  // 输入框回填，用户读不出「发出去了没有」。
  const pending = armOptimisticText(conv, body, deviceMsgs);

  if (!conv.sourceServer) {
    // legacy 腿（旧网关 / peers 派生行）：数据面在 dropbox.js，发送 = WS 文本帧。
    // 乐观面留在窗内 ⇒ 回显行到达时由 `claimDeviceEcho`（`syncDeviceMsgs` → `adoptDeviceMsgs`）
    // 认领（与好友面的「回显先到」同一条收敛语义）。
    sendDeviceText(conv.device.deviceId, body);
    return;
  }

  try {
    await api.sendDeviceMessage(selfId, body, clientMsgId);
    deviceRetry = null; // 成功 ⇒ 下一次同文本发送是新动作（新键、照常落新行）
    await fetchDeviceMsgsServer(conv);
    if (openConvId !== conv.conversationId) return;
    // 认领已在 `fetchDeviceMsgsServer` → `adoptDeviceMsgs` 内完成（先认领再重渲）：
    // 乐观节点已换键成权威 id 并置 S6（`claimDeviceNodePhase`）⇒ 本次重渲不会新增气泡。
    renderMessages(deviceMsgs, { stickBottom: true });
    conv.lastMessage = deviceMsgs[deviceMsgs.length - 1] || conv.lastMessage;
    renderList();
    void refreshDeviceReceipts(conv);
  } catch (err) {
    // 语义终态（403 not_my_device / 404 设备或会话不存在）⇒ **S7**：气泡撤除 + 分态提示。
    // 其余（网络 / 5xx / 429）⇒ **S4**：气泡载面红圈 + 重试键（正文与键都在手上，零丢失）。
    const terminal = !!(err && (err.status === 403 || err.status === 404));
    if (terminal) {
      forgetPendingSend(pending);
      pending.node.remove();
      const i = deviceMsgs.findIndex(x => x === pending.entry || String(x.id) === String(pending.tempId));
      if (i >= 0) deviceMsgs.splice(i, 1);
      deviceRetry = null;
      renderMessages(deviceMsgs, { stickBottom: false });
      modalToast(t('messages.deviceSendFailed')); // S7 终态提示面（唯一消费点，见 §8.2 改写②）
      return;
    }
    // S4 / S5：载面 + 重试键（**同一动作 ⇒ 同一 `clientMsgId`**，服务端按同键回放原行）。
    // 🔴 未决项**留在表里**：若首投其实已落库（响应丢失/超时），那条权威行到达时仍会被
    //    认领到**这个失败气泡**上（原地换键 + 置 S6）——「看似失败其实已发出」不会变成
    //    屏上两条消息（与好友面 `claimPendingSend` 的同一条纪律）。
    deviceRetry = { convId: retryConvId, body, clientMsgId };
    applyPhase(pending.node, PHASE.ERROR);
    bindRetry(pending.node, () => {
      // 重试 = **同一动作**：键与正文原样带出；失败气泡与它的未决登记同拍退场
      // （新的乐观面接管同一动作的载面）。
      forgetPendingSend(pending);
      pending.node.remove();
      const i = deviceMsgs.findIndex(x => x === pending.entry || String(x.id) === String(pending.tempId));
      if (i >= 0) deviceMsgs.splice(i, 1);
      renderMessages(deviceMsgs, { stickBottom: false });
      sendDeviceCurrent(conv, { clientMsgId, body });
    });
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
  // 🔴 legacy 台账腿未读的**到达拍**（作者令 2026-09-17 12:58）：本函数 = dropbox.js
  // `afterDeviceMessageChange` 单点 ⇒ `dropbox-message`（到达）/ 传输态 / 闸位提示 /
  // `dropbox-history`（回包）四路都在此汇流（禁另行挂帧监听）。必须在 `renderList()`
  // **之前** —— 本拍涨的未读随这一拍的行渲染出来（零额外重渲）。
  noteLegacyInboundArrivals(deviceId);
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
    // sessperf Phase B（卡 §5.3「服务端腿删 `deviceMsgs = []`，改 store 首帧 → 增量」）：
    // 首帧来源 = 本地层（**同步、零 await、零网络**）；加载态只服务「本地层无条目」
    // 这一档（与好友/群面同一条纪律：禁先清空再等网络）。
    deviceMsgs = cachedDeviceServerMsgs(conv);
    if (deviceMsgs.length) renderMessages(deviceMsgs);
    else showFlowLoading();
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
  // 🔴 legacy 台账腿开窗清零（**既有已读语义**：窗开着 ⇒ 即已读；对偶 = 服务端腿
  // `markDeviceConvRead`）。位置有两重判据：① 在 `hydrateDeviceCache` **之后** ——
  // 基线要含 L2 灌回的既有入向（否则关窗后它们会被当成「到达」补涨）；② 在
  // `openConvId` 置位**之后**（本函数首行已置）—— 随后到达的 `dropbox-history` 回包
  // 按「窗开着」入账不涨。
  clearLegacyUnread(conv);
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
