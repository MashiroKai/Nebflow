// messages.js — Messages panel (conversation list) + friend chat modal.
// friends-messaging-spec §3.2/§3.3/§4: WeChat-style conversation rows,
// cfg-modal chat window (560px glass), forward-to-agent (one-way), agent-sent
// chips, pure-badge notifications (no sound/banner/title — [U3]).
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import state from './state.js';
import { onMessage, onReconnect, onDisconnect } from './ws.js';
import { getNeblinkState } from './neblink.js';
import { setActivityBadge, openLoginModal, onStatusTick } from './activityBar.js';
import { key } from './branding.js';
// ⑨ 缓存与增量（方案 §2.3 B+E）：L2 持久层 = fmMessageCache.js（唯一属主，
// 键/上限/账号分区都在那边）；本模块只消费 + 负责 L1（内存会话列表）新鲜度。
import {
  TTL_MS as CACHE_TTL_MS, SYNC_PAGE, MAX_SYNC_PAGES,
  loadConversation, saveConversation,
} from './fmMessageCache.js';
import * as api from './friendsApi.js';
import { makeReference } from './reference.js';
import { appendRefToActiveView } from './input.js';
import { showPopupMenu } from './contextMenu.js';
// ⑥ 信任好友封存（作者裁定 2026-09-12）：静态常量，非配置读取、不过 latch。
import { TRUST_SEALED } from './featureFlags.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
// 本模块的好友会话输入框（作者点名的面）此前**零组字判定** —— 组字 Enter 会
// 直接 doSend()。
import { bindImeGuard, isImeComposing } from './imeGuard.js';

let conversations = [];
let friendsCache = [];          // accepted friends — source of truth for §3.3 gate
let friendsFetchedAt = 0;       // ⑨ L1 新鲜度锚：好友列表最后一次成功拉取时刻
let openConvId = null;          // conversation shown in the chat modal
let modalEls = null;            // {overlay, flow, input, sendBtn, toast}
let triggeringRow = null;       // for focus return (A18)
let triggeringConvId = null;    // row may be re-rendered after open (unread clear) — refind by id
const forwardedIds = new Set(); // session-persistent 「已转发」 chips (§3.3)
let msgSeq = 0;

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
  const d = new Date(ms);
  const now = new Date();
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
  if (sameDay) return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.getFullYear() === y.getFullYear() && d.getMonth() === y.getMonth() && d.getDate() === y.getDate()) {
    return t('messages.yesterday');
  }
  return `${d.getMonth() + 1}/${d.getDate()}`;
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
    const [convs, fr] = await Promise.all([
      api.getConversations(),
      wantFriends ? api.getFriends() : Promise.resolve(null),
    ]);
    conversations = (convs || []).sort((a, b) =>
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
  const row = el('div', 'fm-row fm-conv-row');
  row.setAttribute('role', 'option');
  row.setAttribute('tabindex', '0');
  row.setAttribute('aria-selected', String(conv.conversationId === openConvId));
  row.dataset.conversationId = conv.conversationId;

  row.appendChild(avatarEl(conv.friend, 40));
  const meta = el('div', 'fm-row-meta');
  const top = el('div', 'fm-conv-top');
  top.appendChild(el('span', 'fm-row-name', personLabel(conv.friend)));
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
 * @param {{pages?: number}} [opts] pages = 最大翻页数（默认 1：单次探测）
 * @returns {Promise<number>} 补进来的条数
 */
async function syncConversation(conversationId, { pages = 1 } = {}) {
  if (!conversationId || syncingConvId === conversationId) return 0;
  const maxPages = Math.max(1, Math.min(Number(pages) || 1, MAX_SYNC_PAGES));
  syncingConvId = conversationId;
  let fetched = 0;
  try {
    for (let i = 0; i < maxPages; i++) {
      const after = syncWatermark();
      if (after <= 0) break; // 无可信水位（缺席/temp id 占位）⇒ 不猜、不改窗口
      let batch = [];
      try { batch = await api.getMessages(conversationId, { after, limit: SYNC_PAGE }); }
      catch { break; } // 网络/鉴权失败：保留已渲染内容，不冒泡成用户可见错误
      if (openConvId !== conversationId || !modalEls) break; // 会话已换/窗已关
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
    await syncConversation(conversationId, { pages: cached.fresh ? 1 : MAX_SYNC_PAGES });
    persistConversation(conv);
    return;
  }

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

function renderChatModal(conv) {
  document.getElementById('fm-chat-overlay')?.remove();

  const overlay = el('div', 'cfg-modal-overlay');
  overlay.id = 'fm-chat-overlay';

  const modal = el('div', 'cfg-modal fm-modal');
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-label', personLabel(conv.friend));

  // header: name · neblinkId | trust slot | ×
  // 窗头转发按钮已移除（作者 2026-09-12 裁定，方案 §3.1 S5）：转发入口只保留
  // 按消息的两条 —— 气泡内按钮 + 气泡右键，共用 forwardBubble（无第二实现）。
  const header = el('div', 'fm-modal-header');
  const title = el('div', 'fm-modal-title');
  title.appendChild(el('span', 'fm-modal-name', personLabel(conv.friend)));
  title.appendChild(el('span', 'fm-modal-id', conv.friend?.neblinkId || ''));
  header.appendChild(title);
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
  oldestLoadedId = 0;
  hasMoreHistory = false;
  loadingHistory = false;

  overlay.addEventListener('click', (e) => { if (e.target === overlay) closeChat(); });
  document.addEventListener('keydown', escClose);

  // ③A8 / ③-B：好友面零附件入口（不挂入口、也不做灰置假入口），但**静默吞文件
  // 不可接受**（项目纪律：失败必须可见）——好友窗内落文件 ⇒ 一次显式提示，文件
  // 不被任何通道接收、无副作用。附件通道（跨账号字节通路）本轮零动作。
  overlay.addEventListener('dragover', (e) => {
    if (hasFiles(e)) e.preventDefault();
  });
  overlay.addEventListener('drop', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    modalToast(t('messages.attachUnsupported'));
  });

  const doSend = () => sendCurrent(conv);
  sendBtn.addEventListener('click', doSend);
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

// ⑦ 窗头标题面（显示优先级第三处）：备注改动后就地重打，不整窗重建。
function updateModalTitle(conv) {
  if (!modalEls) return;
  const nameEl = modalEls.overlay.querySelector('.fm-modal-name');
  if (nameEl) nameEl.textContent = personLabel(conv && conv.friend);
  const dlg = modalEls.overlay.querySelector('.fm-modal');
  if (dlg) dlg.setAttribute('aria-label', personLabel(conv && conv.friend));
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
  const blocked = !isStillFriend(conv);
  if (blocked) {
    const bar = el('div', 'fm-blocked-bar', t('messages.notFriendBlocked'));
    modalEls.flow.parentNode.insertBefore(bar, modalEls.flow);
  }
  const disabled = blocked || !state.connected;
  modalEls.input.disabled = disabled;
  modalEls.sendBtn.disabled = disabled;
}

// ── Bubbles ──────────────────────────────────────────────
function bubbleEl(m, conv) {
  const out = m.senderId !== conv.friend?.userId; // not from the friend = ours
  const wrap = el('div', `fm-msg ${out ? 'out' : 'in'}`);
  wrap.dataset.messageId = m.id;
  wrap.dataset.body = m.body || '';
  wrap.dataset.createdAt = String(toEpochMs(m.createdAt) || '');

  const bubble = el('div', 'fm-msg-bubble');
  bubble.textContent = m.body || '';
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
  if (out && isAgentSent(m)) meta.appendChild(el('span', 'fm-msg-agent-badge', t('messages.agentBadge')));
  if (hasForwarded(m.id)) meta.appendChild(el('span', 'fm-msg-forwarded-badge', t('messages.forwarded')));
  meta.appendChild(el('span', 'fm-msg-time', fmtTime(m.createdAt)));

  const actions = el('span', 'fm-msg-actions');
  const copyBtn = el('button', 'fm-msg-act');
  copyBtn.innerHTML = '<i data-lucide="copy"></i>';
  copyBtn.title = t('messages.copy');
  copyBtn.setAttribute('aria-label', t('messages.copy')); // title 不保证被 AT 播报
  copyBtn.addEventListener('click', () => {
    navigator.clipboard?.writeText(m.body || '');
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
    const id = node.dataset && node.dataset.messageId;
    if (id !== undefined && id !== null && id !== '') existing.set(String(id), /** @type {HTMLElement} */ (node));
  }
  const wanted = new Set(msgs.map(m => String(m.id)));
  for (const [k, node] of [...existing]) {
    if (!wanted.has(k)) { node.remove(); existing.delete(k); }
  }
  let cursor = loadMore || null;
  for (const m of msgs) {
    const k = String(m.id);
    const reused = existing.get(k);
    if (reused) existing.delete(k);
    const node = reused || bubbleEl(m, conv);
    const after = cursor ? cursor.nextSibling : flow.firstChild;
    if (node !== after) flow.insertBefore(node, after);
    cursor = node;
  }
}

function renderMessages(msgs, { stickBottom = true } = {}) {
  if (!modalEls) return;
  const conv = currentConv();
  if (!conv) return;
  const flow = modalEls.flow;
  const prevHeight = flow.scrollHeight;
  const prevTop = flow.scrollTop;
  const atBottom = flow.scrollHeight - flow.scrollTop - flow.clientHeight <= 40;
  updateLoadMoreRow();
  keyedDiff(flow, msgs, conv);
  createIconsIn(flow);
  if (stickBottom || atBottom) flow.scrollTop = flow.scrollHeight;
  else flow.scrollTop = prevTop + (flow.scrollHeight - prevHeight); // 阅读中：补偿高度，不拽回底部
}

/** ⑨ 增量补齐落到 DOM（保持窗口有序 + 阅读位置不跳）。
 *  不重排：keyset 页本身 ASC、且 `after=` 恒取窗口最大 id ⇒ 追加序即升序
 *  （不引入 `Number(id)` 排序 —— mock 面的字符串 id 会被 NaN 打乱既有顺序）。 */
function appendMessages(msgs) {
  for (const m of msgs) {
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
    catch { return; } // 探针失败：保持「不显示」，绝不冒泡成用户可见错误
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

function appendMessage(m) {
  const conv = currentConv();
  if (!modalEls || !conv) return;
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

  try {
    const resp = await api.sendFriendMessage(conv.friend.userId, body);
    const realId = resp.messageId || tempId;
    wrap.classList.remove('fm-sending');
    wrap.dataset.messageId = realId;
    const idx = chatMsgs.findIndex(x => x.id === tempId);
    if (idx >= 0) chatMsgs[idx] = { ...optimistic, id: realId }; // temp id → 服务端 id
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
  } catch {
    wrap.classList.remove('fm-sending');
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
async function onFriendEvent(msg) {
  if (msg.event === 'message_new') {
    const p = msg;
    const conv = conversations.find(c => c.conversationId === p.conversationId);
    if (!conv) { await refreshConversations({ friends: 'force' }); return; } // REST is truth
    const m = { id: p.messageId, senderId: p.senderId || p.sender?.userId, kind: p.kind, body: p.body, createdAt: p.createdAt };
    conv.lastMessage = m;
    const isOpen = openConvId === p.conversationId;
    if (isOpen) {
      appendMessage(m); // 内部已落 ⑨ 缓存
      api.markConversationRead(p.conversationId, m.id).catch(() => {});
      conv.unreadCount = 0;
    } else {
      // inbound only counts; our own/agent-sent never unread (§4)
      if (m.senderId && conv.friend && m.senderId === conv.friend.userId) {
        conv.unreadCount = (conv.unreadCount || 0) + 1;
      }
    }
    // 信任模式 v1：trusted 好友的新到消息自动起草进 agent 输入（单向）。
    // Note: 全新会话的首条消息走上方 refreshConversations 早退分支，不在此
    // 自动转发（会话缓存缺失时的已知边界，后续消息正常覆盖）。
    maybeAutoForward(m, conv);
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
    window.dispatchEvent(new CustomEvent('fm-friends-changed'));
  }
}

// ── ①opt-A3：降级增量回补（方案 §2.1，判据 P4）────────────────────────
// 载体 = activityBar.js **既有** 10s 状态 beacon（`onStatusTick`）——不新增定时器、
// 不新增端点、不改协议。只在「推送通道不可用」时动作：健康的 WS 路径零额外请求
// （⑨ M1②/M3 红线：不得出现多余 REST）。
/** 通道是否可用。`relay` 未知（状态未取到 / 老网关无该字段）按**不可用**处理 ——
 *  降级兜底的方向是「多花一次极小请求」，不是「静默不补」。 */
function relayUsable() {
  const r = getNeblinkState().relay;
  return !!(r && r.available === true);
}

/** 节流 > beacon 周期 ⇒ 至多一拍一次；`document.hidden` 守卫与 beacon 同语义。 */
const BACKFILL_THROTTLE_MS = 9000;

async function backfillTick() {
  if (!loggedIn() || document.hidden) return;
  if (relayUsable() && state.connected) return; // 健康路径：零请求
  const convId = openConvId;
  if (!convId || String(convId).startsWith('__pending__')) return; // 会话尚未建立
  const now = Date.now();
  if (now - lastBackfillAt < BACKFILL_THROTTLE_MS) return;
  lastBackfillAt = now;
  await syncConversation(convId, { pages: MAX_SYNC_PAGES });
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
  onStatusTick(() => { void backfillTick(); });
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
  // P3 error surface — friendsApi dispatches on auth failure / network error.
  window.addEventListener('fm-auth-required', () => { openLoginModal(); });
  window.addEventListener('fm-network-error', () => { window.__showToast?.(t('messages.networkError'), 'error'); });
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
      modalEls.sendBtn.disabled = true;
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
