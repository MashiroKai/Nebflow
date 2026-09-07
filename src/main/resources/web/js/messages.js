// messages.js — Messages panel (conversation list) + friend chat modal.
// friends-messaging-spec §3.2/§3.3/§4: WeChat-style conversation rows,
// cfg-modal chat window (560px glass), forward-to-agent (one-way), agent-sent
// chips, pure-badge notifications (no sound/banner/title — [U3]).
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import state from './state.js';
import { onMessage, onReconnect, onDisconnect } from './ws.js';
import { getNeblinkState } from './neblink.js';
import { setActivityBadge, openLoginModal } from './activityBar.js';
import * as api from './friendsApi.js';
import { makeReference } from './reference.js';
import { appendRefToActiveView } from './input.js';
import { showPopupMenu } from './contextMenu.js';

let conversations = [];
let friendsCache = [];          // accepted friends — source of truth for §3.3 gate
let openConvId = null;          // conversation shown in the chat modal
let modalEls = null;            // {overlay, flow, input, sendBtn, toast}
let triggeringRow = null;       // for focus return (A18)
let triggeringConvId = null;    // row may be re-rendered after open (unread clear) — refind by id
const forwardedIds = new Set(); // session-persistent 「已转发」 chips (§3.3)
let msgSeq = 0;

// ── 好友信任模式 v1（作者令：信任的好友，新消息自动走既有「转发给 agent」
// 通道）── 纯客户端本地标记，localStorage 持久化（与 fm_seen_requests /
// fm_blocked 同一家族）。存 userId 数组（与黑名单缓存同键——userId 比
// neblinkId/Username 稳定，friend_event 的 senderId 匹配也用它）。零后端改动；
// 跨设备同步 = v2 候选。本模块是 store 唯一属主，contacts.js 经导出入口读写。
const LS_TRUSTED = 'fm_trusted';
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
let chatMsgs = [];              // ascending messages currently loaded in the modal
let oldestLoadedId = 0;         // keyset anchor for load-more
let hasMoreHistory = false;
let loadingHistory = false;

function loggedIn() { return !!getNeblinkState().loggedIn; }

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
async function refreshConversations() {
  if (!loggedIn()) { conversations = []; friendsCache = []; renderList(); return; }
  try {
    const [convs, friends] = await Promise.all([api.getConversations(), api.getFriends()]);
    conversations = (convs || []).sort((a, b) =>
      (toEpochMs(b.lastMessage?.createdAt) || 0) - (toEpochMs(a.lastMessage?.createdAt) || 0));
    friendsCache = friends.friends || [];
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
  top.appendChild(el('span', 'fm-row-name', conv.friend?.name || conv.friend?.neblinkId || ''));
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

async function openConversation(conversationId, rowEl) {
  const conv = conversations.find(c => c.conversationId === conversationId);
  if (!conv) return;
  triggeringRow = rowEl || null;
  triggeringConvId = conversationId;
  openConvId = conversationId;
  renderChatModal(conv);

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
  hasMoreHistory = chatMsgs.length > 0 && oldestLoadedId > 1;
  renderMessages(chatMsgs);
  const last = chatMsgs[chatMsgs.length - 1];
  if (last) {
    conv.unreadCount = 0;
    api.markConversationRead(conversationId, last.id).catch(() => {});
    updateBadge();
    renderList();
  }
  if (modalEls) modalEls.input.focus();
}

function renderChatModal(conv) {
  document.getElementById('fm-chat-overlay')?.remove();

  const overlay = el('div', 'cfg-modal-overlay');
  overlay.id = 'fm-chat-overlay';

  const modal = el('div', 'cfg-modal fm-modal');
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-label', conv.friend?.name || '');

  // header: name · neblinkId | forward-btn ×
  const header = el('div', 'fm-modal-header');
  const title = el('div', 'fm-modal-title');
  title.appendChild(el('span', 'fm-modal-name', conv.friend?.name || ''));
  title.appendChild(el('span', 'fm-modal-id', conv.friend?.neblinkId || ''));
  header.appendChild(title);
  // 信任模式 v1: 窗头信任状态指示（开启态一眼可辨；开关在好友行右键菜单）。
  header.appendChild(el('span', 'fm-trust-slot'));
  const fwdBtn = el('button', 'glass-control fm-forward-btn');
  fwdBtn.innerHTML = '<i data-lucide="forward"></i>';
  fwdBtn.title = t('messages.forwardToAgent');
  fwdBtn.setAttribute('aria-label', t('messages.forwardToAgent'));
  fwdBtn.addEventListener('click', () => {
    // header button forwards the latest INCOMING message (A9)
    const last = [...(modalEls?.flow.querySelectorAll('.fm-msg.in') || [])].pop();
    if (last) forwardBubble(last, conv);
  });
  header.appendChild(fwdBtn);
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

  const doSend = () => sendCurrent(conv);
  sendBtn.addEventListener('click', doSend);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); doSend(); } });

  applyBlockState(conv);
  updateTrustBadge(conv);
  createIconsIn(overlay);
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
  copyBtn.addEventListener('click', () => {
    navigator.clipboard?.writeText(m.body || '');
  });
  const fwd = el('button', 'fm-msg-act');
  fwd.innerHTML = '<i data-lucide="forward"></i>';
  fwd.title = t('messages.forwardToAgent');
  fwd.addEventListener('click', () => forwardBubble(wrap, conv));
  actions.appendChild(copyBtn);
  actions.appendChild(fwd);
  meta.appendChild(actions);

  wrap.appendChild(meta);
  return wrap;
}

function renderMessages(msgs) {
  if (!modalEls) return;
  const conv = currentConv();
  if (!conv) return;
  modalEls.flow.innerHTML = '';
  updateLoadMoreRow();
  for (const m of msgs) modalEls.flow.appendChild(bubbleEl(m, conv));
  createIconsIn(modalEls.flow);
  modalEls.flow.scrollTop = modalEls.flow.scrollHeight;
}

// ── 加载更早消息（keyset id-window backward walk）────────
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
  btn.disabled = state === 'loading';
  btn.textContent = state === 'loading' ? t('messages.loading') : t('messages.loadingOlder');
}

async function loadOlderMessages() {
  if (!modalEls || !hasMoreHistory || loadingHistory) return;
  const convId = openConvId;
  if (!convId) return;
  loadingHistory = true;
  setLoadMoreState('loading');
  try {
    let fetched = [];
    let after = Math.max(0, oldestLoadedId - 1 - HISTORY_WINDOW);
    let steps = 0;
    // Ids are table-wide AUTOINCREMENT — a window may contain zero messages of
    // THIS conversation (ids owned by others). Auto-step further back, bounded.
    while (steps < 20) {
      fetched = await api.getMessages(convId, { after, limit: HISTORY_WINDOW });
      if (openConvId !== convId) return; // modal replaced mid-flight
      if (fetched.length > 0 || after <= 0) break;
      after = Math.max(0, after - HISTORY_WINDOW);
      steps++;
    }
    if (openConvId !== convId || !modalEls) return;
    const existing = new Set(chatMsgs.map(x => String(x.id)));
    const fresh = fetched.filter(m => !existing.has(String(m.id)));
    if (fresh.length) {
      chatMsgs = fresh.concat(chatMsgs);
      oldestLoadedId = Number(chatMsgs[0].id) || oldestLoadedId;
      hasMoreHistory = oldestLoadedId > 1;
      prependMessages(fresh);
    } else {
      // Nothing found all the way to id 0 → this is the history start.
      hasMoreHistory = after > 0;
    }
  } catch { /* keep state; button stays for retry */ }
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
  modalEls.flow.appendChild(bubbleEl(m, conv));
  createIconsIn(modalEls.flow);
  modalEls.flow.scrollTop = modalEls.flow.scrollHeight;
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
  const wrap = bubbleEl(optimistic, conv);
  wrap.classList.add('fm-sending');
  modalEls.flow.appendChild(wrap);
  createIconsIn(modalEls.flow);
  modalEls.flow.scrollTop = modalEls.flow.scrollHeight;

  try {
    const resp = await api.sendFriendMessage(conv.friend.userId, body);
    wrap.classList.remove('fm-sending');
    wrap.dataset.messageId = resp.messageId || tempId;
    if (!conv.conversationId && resp.conversationId) {
      // First send created the conversation (friend-addressed send)
      conv.conversationId = resp.conversationId;
      openConvId = resp.conversationId;
    }
    // delivered: silent (§6.2 克制)
    conv.lastMessage = { ...optimistic, id: resp.messageId || tempId };
    conv.lastMessage.agentSent = false;
    resortAndRender();
  } catch {
    wrap.classList.remove('fm-sending');
    wrap.classList.add('fm-failed');
    const flag = el('button', 'fm-retry', '!');
    flag.title = t('messages.send');
    flag.addEventListener('click', () => {
      wrap.remove();
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
    let conv = conversations.find(c => c.conversationId === p.conversationId);
    if (!conv) { await refreshConversations(); return; } // REST is truth
    const m = { id: p.messageId, senderId: p.senderId || p.sender?.userId, kind: p.kind, body: p.body, createdAt: p.createdAt };
    conv.lastMessage = m;
    const isOpen = openConvId === p.conversationId;
    if (isOpen) {
      appendMessage(m);
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
    await refreshConversations();
    window.dispatchEvent(new CustomEvent('fm-friends-changed'));
    return;
  }
  if (msg.event === 'friend_request') {
    window.dispatchEvent(new CustomEvent('fm-friends-changed'));
  }
}

// ── Wiring ───────────────────────────────────────────────
let initialized = false;

export function initMessages() {
  if (initialized) return;
  initialized = true;

  onMessage('friend_event', onFriendEvent);
  window.addEventListener('fm-refs-sent', onRefsSent);
  // #290: deleting/blocking a friend (contacts panel) flips the open chat
  // into the read-only gate - resync the friend cache and re-apply.
  window.addEventListener('fm-friends-changed', async () => {
    await refreshConversations();
    if (modalEls) applyBlockState(currentConv());
  });
  // 信任开关在 contacts 右键菜单——开着的聊天窗头指示随之刷新。
  window.addEventListener('fm-trust-changed', () => {
    if (modalEls) updateTrustBadge(currentConv());
  });
  // P3 error surface — friendsApi dispatches on auth failure / network error.
  window.addEventListener('fm-auth-required', () => { openLoginModal(); });
  window.addEventListener('fm-network-error', () => { window.__showToast?.(t('messages.networkError'), 'error'); });
  onReconnect(() => {
    if (modalEls) {
      modalEls.offline.hidden = true;
      applyBlockState(currentConv());
    }
    refreshConversations(); // resync after reconnect (REST is truth)
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
