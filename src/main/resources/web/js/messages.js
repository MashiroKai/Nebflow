// messages.js — Messages panel (conversation list) + friend chat modal.
// friends-messaging-spec §3.2/§3.3/§4: WeChat-style conversation rows,
// cfg-modal chat window (560px glass), forward-to-agent (one-way), agent-sent
// chips, pure-badge notifications (no sound/banner/title — [U3]).
import { t } from './i18n.js';
import { createIconsIn, markCopyFailed } from './utils.js';
import state from './state.js';
import { onMessage, onReconnect, onDisconnect } from './ws.js';
import { getNeblinkState } from './neblink.js';
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
// memberCount, myRole }（friendGroups.refreshGroups 归一出口）。

// 「本机是否发送者」判据源（主卡 F-2 #3 点名的群新增面）。冻结契约里没有
// viewer 身份字段 ⇒ 客户端按「发送关联」自证：
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

function summaryOf(conv) {
  const m = conv.lastMessage;
  if (!m || !m.body) return t('messages.systemNowFriends');
  return (isAgentSent(m) ? '[Agent] ' : '') + m.body;
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
    if (grp) {
      // pendingInvites 的消费方是 contacts 面的群邀请区（它自己调 refreshGroups，
      // 与面板独立刷新同构）；messages 面只消费 groups。
      conversations = direct.concat(grp.groups || []);
    } else {
      // keep-last-known：群面取数失败（auth/网络/5xx）⇒ 既有群行原样保留，
      // 只刷新单聊行（与好友域「失败≠空」同口径，禁闪空列表）。
      const prevGroups = conversations.filter(c => c && c.kind === 'group');
      conversations = direct.concat(prevGroups);
    }
    conversations = conversations.sort((a, b) =>
      (toEpochMs(b.lastMessage?.createdAt) || 0) - (toEpochMs(a.lastMessage?.createdAt) || 0));
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
  const row = el('div', 'fm-row fm-conv-row');
  row.setAttribute('role', 'option');
  row.setAttribute('tabindex', '0');
  row.setAttribute('aria-selected', String(conv.conversationId === openConvId));
  row.dataset.conversationId = conv.conversationId;
  if (isGroup) row.dataset.group = '1'; // QA 断言面：群行可机械定位

  // O④：群头像 = 标题首字母占位（avatarEl 无 avatarUrl 即走首字母分支，零新实现）。
  row.appendChild(avatarEl(isGroup ? { name: groupTitleOf(conv) } : conv.friend, 40));
  const meta = el('div', 'fm-row-meta');
  const top = el('div', 'fm-conv-top');
  top.appendChild(el('span', 'fm-row-name', isGroup ? groupTitleOf(conv) : personLabel(conv.friend)));
  if (isGroup) top.appendChild(el('span', 'fm-group-tag', t('messages.groupTag')));
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
  return (conv && conv.kind === 'group') ? groupTitleOf(conv) : personLabel(conv && conv.friend);
}

// ── 群气泡发送者名（腿B §5.2 #2 的群新增面）───────────────────────────
// 名册来源 = GET /api/groups/{id}/members（主卡:249 接口清单；显示名而非好友
// 备注，主卡 H 节口径）。开群窗时惰性取一次；首帧早于名册时先挂空槽（带
// data-sender-id），名册到达后就地回填 —— 消息本体渲染不受名册成败影响。
const groupMemberNames = new Map(); // conversationId -> Map(senderId -> displayName)

/** 群发送者显示名（未命中 ⇒ ''，渲染层留空槽等待回填）。 */
function groupSenderNameOf(conv, senderId) {
  const map = groupMemberNames.get(String(conv && conv.conversationId));
  return (map && map.get(String(senderId))) || '';
}

/** 开群窗时的成员名册惰性装载（每窗一次；失败降级为无发送者名）。 */
async function hydrateGroupSenderNames(conv) {
  try {
    const members = await api.getGroupMembers(conv.conversationId);
    const map = new Map();
    for (const mem of members || []) {
      if (mem && mem.userId) map.set(String(mem.userId), mem.name || String(mem.userId));
    }
    groupMemberNames.set(String(conv.conversationId), map);
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
  const title = el('div', 'fm-modal-title');
  title.appendChild(el('span', 'fm-modal-name', convTitleLabel(conv)));
  // 群窗副行 = 成员数（有读数才挂）；单聊副行不变（neblinkId）。
  if (conv.kind === 'group') {
    if (conv.memberCount > 0) title.appendChild(el('span', 'fm-modal-id', t('messages.memberCount', { n: conv.memberCount })));
  } else {
    title.appendChild(el('span', 'fm-modal-id', conv.friend?.neblinkId || ''));
  }
  header.appendChild(title);
  // 群设置入口（仅群窗）：成员/邀请/改名/退群/解散抽屉（friendGroups.js 唯一属主）。
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
          if (modalEls && groupSettingsMounted) mountDrawer();
        },
      });
      header.insertAdjacentElement('afterend', drawer);
      groupSettingsMounted = true;
    };
    settingsBtn.addEventListener('click', () => {
      if (groupSettingsMounted) {
        modalEls?.overlay.querySelector('.fm-group-settings')?.remove();
        groupSettingsMounted = false;
        return;
      }
      mountDrawer();
    });
    header.appendChild(settingsBtn);
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
  const sendBtn = el('button', 'cfg-btn fm-send-btn', t('messages.send'));
  bar.appendChild(input);
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
    // 群窗同款提示（加性键，不改既有好友窗文案）：本腿附件面 = 接收/下载渲染，
    // 无发送入口（分发器 2026-09-15 11:32 A③ 翻案口径：人群附件既有发送面语义不变）。
    modalToast(t(conv.kind === 'group' ? 'messages.attachUnsupportedGroup' : 'messages.attachUnsupported'));
  });

  const doSend = () => sendCurrent(conv);
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
  const blocked = conv.kind !== 'group' && !isStillFriend(conv);
  if (blocked) {
    const bar = el('div', 'fm-blocked-bar', t('messages.notFriendBlocked'));
    modalEls.flow.parentNode.insertBefore(bar, modalEls.flow);
  }
  modalEls.input.disabled = blocked || !state.connected;
  syncComposerSend(); // 发送键 = 输入框可用 ∧ 输入非空（判据单源，见下）
}

/** ③ 同病同修（2026-09-14 交付批 · 好友面板「发送按钮组」同族）：
 *  发送键可用态 = 输入框可用 ∧ 输入非空（trim）。旧形态只跟「拉黑/断连」同步，
 *  空输入/纯空格时按键看着可用、点了**静默 no-op**（同族反极性缺陷）。
 *  判据唯一来源：applyBlockState / onDisconnect / input 事件 / sendCurrent 共用。 */
function syncComposerSend() {
  if (!modalEls) return;
  modalEls.sendBtn.disabled = modalEls.input.disabled || !modalEls.input.value.trim();
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
  if (s === 'ready') return att.id ? 'ready' : 'unreadable'; // 有 ready 无 id = 不可下载（降级而非假按钮）
  if (s === 'expired') return 'expired';
  if (s === 'uploading') return 'uploading';
  return 'unreadable'; // 越界值 / 键缺失：可判读的降级态（不是「无附件」）
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
  return a.map(x => `${(x && x.id) || ''}:${(x && x.state) || ''}`).join(',');
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
  } else {
    card.appendChild(note);
    card.setAttribute('aria-disabled', 'true');
  }
  return card;
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
  // 群版文案（补充卡 §4.2 + A② 裁定）：群气泡按会话 kind 选键
  // `messages.agentGroupBadge`（zh-CN「由 Agent 发」/ en "Sent by Agent"），
  // 单聊既有键逐字不动 —— 渲染分支复用（`isAgentSent` 判据零改动），附件帧
  // 与徽章同帧共存（fillBubble 附件卡渲染与本分支正交 ⇒ 结构性支持 A③ 翻案）。
  if (isAgentSent(m)) {
    meta.appendChild(el('span', 'fm-msg-agent-badge',
      t(conv.kind === 'group' ? 'messages.agentGroupBadge' : 'messages.agentBadge')));
  }
  if (hasForwarded(m.id)) meta.appendChild(el('span', 'fm-msg-forwarded-badge', t('messages.forwarded')));
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
  const ref = makeReference({
    refType: 'friend-message',
    source: {
      conversationId: conv.conversationId || '',
      messageId: messageId || '',
      friendName: conv.friend?.name || '',
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
  // No ACTIVE chat view (nothing open in the main window) → appendRef returns
  // false. Never silent: guide the user to open a session first (0904 audit
  // break-point fix — previously a silent no-op).
  modalToast(ok ? t('messages.forwardToast') : t('messages.forwardNoSession'));
}

// forwardedIds members arrive as strings (fm-refs-sent detail) while live
// message ids may be numbers — normalize at the boundary.
function hasForwarded(id) { return forwardedIds.has(id) || forwardedIds.has(String(id)); }

/** Stamp the 「已转发给 agent」 chip (set + open bubble, if rendered). Idempotent. */
function stampForwarded(id) {
  forwardedIds.add(id);
  forwardedIds.add(String(id));
  if (!modalEls) return;
  const wrap = modalEls.flow.querySelector(`.fm-msg[data-message-id="${CSS.escape(String(id))}"]`);
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
  window.addEventListener('fm-auth-required', () => { openLoginModal(); });
  window.addEventListener('fm-network-error', () => { window.__showToast?.(t('messages.networkError'), 'error'); });
  // 群域变更（建群/退群/解散/踢人/邀请响应/可用性翻面）：群列表重取；建群成功
  // 带 openConversationId ⇒ 列表就绪后直接开群窗（新群必在服务端返回里）。
  window.addEventListener('fm-groups-changed', async (e) => {
    await refreshConversations();
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
