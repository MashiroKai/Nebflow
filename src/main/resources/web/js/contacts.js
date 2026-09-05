// contacts.js — Contacts panel (friends-messaging-spec §3.1).
// Search by Username or email (submit-style, no incremental search; 含 @ 判
// 邮箱、否则 Username——双识别语义上移服务端，前端单框单 q)，「新的朋友」
// request inbox (incoming + outgoing), friend list. Badges: pending incoming
// count on #contacts-btn (pure-badge model, [U3]).
import { t } from './i18n.js';
import { createIconsIn } from './utils.js';
import { getNeblinkState } from './neblink.js';
import { setActivityBadge, openLoginModal } from './activityBar.js';
import { onMessage } from './ws.js';
import * as api from './friendsApi.js';
import { openChatWithFriend, fmtTime } from './messages.js';
import { showPopupMenu } from './contextMenu.js';

let friends = [];
let incoming = [];
let outgoing = [];
let requestsExpanded = false;
let lastSearchAt = 0;
let searchResult = null;   // null | {found:false} | {found:true,...}
let verifyFor = null;      // username awaiting verification-note input
let sentTo = new Set();    // usernames with a pending outgoing request (this load)
let searching = false;     // search in flight → button loading state
let searchQ = '';          // preserved across re-renders (panel rebuilds on state change)

// ── 红点语义（0904 批次，微信常识）：未看过的请求才亮。展开「新的朋友」
// 即视为已看（与查看后即清的微信口径一致），新 friend_event 再亮；同意/
// 拒绝后条目离开 pending，自然熄灭。seen 集合持久化 localStorage。
const LS_SEEN_REQ = 'fm_seen_requests';
function loadSeenRequests() {
  try { return new Set(JSON.parse(localStorage.getItem(LS_SEEN_REQ) || '[]')); } catch { return new Set(); }
}
function saveSeenRequests(set) {
  try { localStorage.setItem(LS_SEEN_REQ, JSON.stringify([...set])); } catch { /* non-critical */ }
}
function unseenIncomingCount() {
  const seen = loadSeenRequests();
  return incoming.filter(r => r.status === 'pending' && !seen.has(r.requestId)).length;
}

// #290 §1.2 WeChat-style blacklist: blocked friends stay in the list, greyed.
// The server (neblink-server list_friendships) currently excludes blocked
// rows from GET /api/friends - so the blocked set is mirrored client-side
// (localStorage) and merged back into the list on refresh. When the server
// starts returning blocked rows (with a blocked flag), the server data wins
// and the cache entry is dropped.
const LS_BLOCKED = 'fm_blocked';
function loadBlockedCache() {
  try { return JSON.parse(localStorage.getItem(LS_BLOCKED) || '[]'); } catch { return []; }
}
function saveBlockedCache(list) {
  try { localStorage.setItem(LS_BLOCKED, JSON.stringify(list)); } catch { /* non-critical */ }
}

function loggedIn() { return !!getNeblinkState().loggedIn; }

export function getFriendList() { return friends; }

// ── Data ─────────────────────────────────────────────────
async function refresh() {
  if (!loggedIn()) { friends = []; incoming = []; outgoing = []; render(); return; }
  try {
    const data = await api.getFriends();
    const serverFriends = data.friends || [];
    // Merge the client-side blocked mirror: rows the server excluded (blocked)
    // reappear greyed; if the server does return a blocked row, its data wins.
    const cache = loadBlockedCache();
    const serverIds = new Set(serverFriends.map(f => f.userId));
    const merged = [...serverFriends];
    const keepCache = [];
    for (const b of cache) {
      if (serverIds.has(b.userId)) continue; // server row wins
      merged.push({ ...b, blocked: true });
      keepCache.push(b);
    }
    saveBlockedCache(keepCache);
    friends = merged;
    incoming = (data.incoming || []);
    outgoing = (data.outgoing || []);
    sentTo = new Set(outgoing.filter(r => r.status === 'pending').map(r => (r.to?.neblinkId || '').toLowerCase()));
  } catch { /* keep last known */ }
  render();
  updateBadge();
}

function updateBadge() {
  const n = loggedIn() ? unseenIncomingCount() : 0;
  setActivityBadge('contacts-btn', n, t('contacts.ariaRequests', { n }));
}

// ── Render ───────────────────────────────────────────────
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function avatarEl(person, size) {
  const a = el('span', `fm-avatar fm-avatar-${size}`);
  if (person.avatarUrl) {
    const img = document.createElement('img');
    img.src = person.avatarUrl;
    img.alt = '';
    a.appendChild(img);
  } else {
    a.textContent = (person.name || person.neblinkId || '?').trim().charAt(0).toUpperCase();
  }
  a.setAttribute('aria-hidden', 'true');
  return a;
}

function render() {
  const body = document.getElementById('fm-contacts-body');
  if (!body) return;
  body.innerHTML = '';
  updateBadge();

  if (!loggedIn()) {
    const empty = el('div', 'fm-login-empty');
    empty.appendChild(el('div', 'fm-login-text', t('messages.loginRequired')));
    const btn = el('button', 'glass-control fm-login-btn', t('messages.login'));
    btn.addEventListener('click', () => openLoginModal());
    empty.appendChild(btn);
    body.appendChild(empty);
    return;
  }

  body.appendChild(buildSearch());

  // 「新的朋友」entry — badge counts UNSEEN pending requests (WeChat-style:
  // viewing the inbox clears the dot; a new request re-lights it).
  const pendingN = unseenIncomingCount();
  const nf = el('div', 'fm-nf-entry');
  nf.setAttribute('role', 'button');
  nf.setAttribute('tabindex', '0');
  const nfIcon = el('span', 'fm-nf-icon');
  nfIcon.innerHTML = '<i data-lucide="user-plus"></i>';
  nf.appendChild(nfIcon);
  nf.appendChild(el('span', 'fm-nf-label', t('contacts.newFriends')));
  if (pendingN > 0) nf.appendChild(el('span', 'fm-row-badge', String(pendingN)));
  nf.appendChild(el('span', 'fm-nf-chevron', ''));
  nf.querySelector('.fm-nf-chevron').innerHTML = `<i data-lucide="${requestsExpanded ? 'chevron-down' : 'chevron-right'}"></i>`;
  const toggleReq = () => {
    requestsExpanded = !requestsExpanded;
    if (requestsExpanded) {
      // Viewing the inbox = seen (WeChat-style red-dot semantics).
      const seen = loadSeenRequests();
      for (const r of incoming) if (r.status === 'pending') seen.add(r.requestId);
      saveSeenRequests(seen);
    }
    render();
  };
  nf.addEventListener('click', toggleReq);
  nf.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleReq(); } });
  body.appendChild(nf);

  if (requestsExpanded) body.appendChild(buildRequests());

  // Friend list
  const list = el('div', 'fm-friend-list');
  list.setAttribute('role', 'listbox');
  list.setAttribute('aria-label', t('panel.contacts'));
  if (friends.length === 0) {
    list.appendChild(el('div', 'fm-empty', t('contacts.empty')));
  } else {
    for (const f of friends) list.appendChild(friendRow(f));
  }
  body.appendChild(list);
  createIconsIn(body);
}

function friendRow(f) {
  const blocked = !!f.blocked;
  const row = el('div', 'fm-row fm-friend-row' + (blocked ? ' fm-blocked' : ''));
  row.setAttribute('role', 'option');
  row.setAttribute('tabindex', '0');
  row.setAttribute('aria-selected', 'false');
  row.appendChild(avatarEl(f, 36));
  const meta = el('div', 'fm-row-meta');
  meta.appendChild(el('div', 'fm-row-name', f.name || f.neblinkId));
  meta.appendChild(el('div', 'fm-row-sub', f.neblinkId));
  row.appendChild(meta);
  if (blocked) row.appendChild(el('span', 'fm-status-text fm-blocked-tag', t('contacts.blocked')));
  const open = () => openChatWithFriend(f);
  row.addEventListener('click', open);
  row.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); open(); } });
  // #290 §1.1/§1.2: WeChat-style row context menu (delete / block / unblock).
  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const items = [
      { label: t('contacts.menuDelete'), danger: true, onClick: () => confirmDeleteFriend(f) },
      blocked
        ? { label: t('contacts.menuUnblock'), onClick: () => unblockFriend(f) }
        : { label: t('contacts.menuBlock'), danger: true, onClick: () => confirmBlockFriend(f) },
    ];
    showPopupMenu(e.clientX, e.clientY, items);
  });
  return row;
}

// ── Delete / block flows (#290 §1.1/§1.2, WeChat-style confirm) ─────────
function confirmDeleteFriend(f) {
  const name = f.name || f.neblinkId || '';
  const run = async () => {
    try {
      await api.removeFriend(f.userId);
      friends = friends.filter(x => x.userId !== f.userId);
      saveBlockedCache(loadBlockedCache().filter(b => b.userId !== f.userId));
      render();
      window.dispatchEvent(new CustomEvent('fm-friends-changed'));
    } catch (err) { window.__showToast?.(err.message || t('messages.networkError'), 'error'); }
  };
  if (typeof window.__showConfirm === 'function') {
    window.__showConfirm(t('contacts.deleteTitle'), t('contacts.deleteConfirm', { name }), run);
  } else { run(); }
}

function confirmBlockFriend(f) {
  const name = f.name || f.neblinkId || '';
  const run = async () => {
    try {
      await api.blockFriend(f.userId);
      const cache = loadBlockedCache();
      if (!cache.some(b => b.userId === f.userId)) {
        cache.push({ userId: f.userId, neblinkId: f.neblinkId || '', name: f.name || '', avatarUrl: f.avatarUrl || '' });
        saveBlockedCache(cache);
      }
      const row = friends.find(x => x.userId === f.userId);
      if (row) row.blocked = true;
      render();
      window.dispatchEvent(new CustomEvent('fm-friends-changed'));
    } catch (err) { window.__showToast?.(err.message || t('messages.networkError'), 'error'); }
  };
  if (typeof window.__showConfirm === 'function') {
    window.__showConfirm(t('contacts.blockTitle'), t('contacts.blockConfirm', { name }), run);
  } else { run(); }
}

async function unblockFriend(f) {
  try {
    await api.unblockFriend(f.userId);
    saveBlockedCache(loadBlockedCache().filter(b => b.userId !== f.userId));
    await refresh();
    window.dispatchEvent(new CustomEvent('fm-friends-changed'));
  } catch (err) { window.__showToast?.(err.message || t('messages.networkError'), 'error'); }
}

// ── Search (submit-style, 1s min interval) ───────────────
function buildSearch() {
  const wrap = el('div', 'fm-search');
  const input = document.createElement('input');
  input.className = 'fm-search-input';
  input.type = 'text';
  input.placeholder = t('contacts.searchPlaceholder');
  input.autocomplete = 'off';
  input.value = searchQ; // preserved across panel re-renders
  input.addEventListener('input', () => { searchQ = input.value; });
  const btn = el('button', 'glass-control fm-search-btn', t('contacts.search'));
  const submit = async () => {
    const q = input.value.trim();
    if (!q || searching) return;
    const now = Date.now();
    if (now - lastSearchAt < 1000) return; // anti-crawl min interval
    lastSearchAt = now;
    verifyFor = null;
    searching = true;
    render(); // button → loading state (input value survives via searchQ)
    try {
      searchResult = await api.lookupUser(searchQ.trim());
    } catch { searchResult = { found: false }; }
    searching = false;
    render();
  };
  btn.addEventListener('click', submit);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); submit(); } });
  if (searching) {
    btn.disabled = true;
    btn.textContent = t('contacts.searching');
  }
  wrap.appendChild(input);
  wrap.appendChild(btn);
  if (searchResult && !searching) wrap.appendChild(buildResultCard());
  return wrap;
}

function buildResultCard() {
  const card = el('div', 'fm-result-card');
  const r = searchResult;
  if (!r.found) {
    // 未找到态：主文案 + 常识提示（对方可能未设置用户名 / 输入有误）
    card.appendChild(el('div', 'fm-empty', t('contacts.notFound')));
    card.appendChild(el('div', 'fm-empty-hint', t('contacts.notFoundHint')));
    return card;
  }
  // lookup 新契约 {username, displayName, avatar}（friendsApi 归一后统一形态）
  card.appendChild(avatarEl({ avatarUrl: r.avatar, name: r.displayName }, 36));
  const meta = el('div', 'fm-row-meta');
  meta.appendChild(el('div', 'fm-row-name', r.displayName || r.username));
  meta.appendChild(el('div', 'fm-row-sub', r.username));
  card.appendChild(meta);

  if (r.self) {
    card.appendChild(el('span', 'fm-status-text', t('contacts.self')));
    return card;
  }
  // wire 对象（好友行/在途请求）的 neblinkId 字段值即 username——同值域比较
  const alreadyFriend = friends.some(f => (f.neblinkId || '').toLowerCase() === (r.username || '').toLowerCase());
  const pending = sentTo.has((r.username || '').toLowerCase());
  if (alreadyFriend || pending) {
    card.appendChild(el('span', 'fm-status-text', t('contacts.pendingVerification')));
    return card;
  }
  if (verifyFor === r.username) {
    // Verification-note input (WeChat-style, ≤50 chars, optional)
    const box = el('div', 'fm-verify-box');
    const input = document.createElement('input');
    input.className = 'fm-verify-input';
    input.maxLength = 50;
    input.placeholder = t('contacts.verifyMessagePlaceholder');
    const sendBtn = el('button', 'glass-control', t('messages.send'));
    const doSend = async () => {
      sendBtn.disabled = true;
      try {
        await api.sendFriendRequest(r.username, input.value.trim());
        sentTo.add(r.username.toLowerCase());
      } catch { /* keep state */ }
      verifyFor = null;
      render();
    };
    sendBtn.addEventListener('click', doSend);
    // Enter 直发 + 取消回退（微信常识：附言后点发送；不想发可退出）
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); doSend(); } });
    const cancelBtn = el('button', 'glass-control fm-verify-cancel', t('contacts.cancelVerify'));
    cancelBtn.addEventListener('click', () => { verifyFor = null; render(); });
    box.appendChild(input);
    box.appendChild(sendBtn);
    box.appendChild(cancelBtn);
    card.appendChild(box);
    setTimeout(() => input.focus(), 0);
  } else {
    const addBtn = el('button', 'glass-control fm-add-btn', t('contacts.addFriend'));
    addBtn.addEventListener('click', () => { verifyFor = r.username; render(); });
    card.appendChild(addBtn);
  }
  return card;
}

// ── Requests (「新的朋友」) ───────────────────────────────
function buildRequests() {
  const wrap = el('div', 'fm-requests');
  if (incoming.length === 0 && outgoing.length === 0) {
    wrap.appendChild(el('div', 'fm-empty', t('contacts.noRequests')));
    return wrap;
  }
  for (const rq of incoming) wrap.appendChild(requestRow(rq, 'in'));
  if (outgoing.length > 0) {
    wrap.appendChild(el('div', 'fm-req-group', t('contacts.sentRequests')));
    for (const rq of outgoing) wrap.appendChild(requestRow(rq, 'out'));
  }
  return wrap;
}

function requestRow(rq, dir) {
  const person = dir === 'in' ? rq.from : rq.to;
  const row = el('div', 'fm-row fm-req-row');
  row.appendChild(avatarEl(person || {}, 36));
  const meta = el('div', 'fm-row-meta');
  meta.appendChild(el('div', 'fm-row-name', person?.name || person?.neblinkId || ''));
  // 验证消息优先展示（微信常识），无附言回退号；行尾另附请求时间
  meta.appendChild(el('div', 'fm-row-sub', rq.note || person?.neblinkId || ''));
  row.appendChild(meta);
  if (rq.createdAt) row.appendChild(el('span', 'fm-req-time', fmtTime(rq.createdAt)));

  const status = rq.status || 'pending';
  if (status !== 'pending') {
    row.appendChild(el('span', 'fm-status-text',
      status === 'accepted' ? t('contacts.accepted') : t('contacts.declined')));
    return row;
  }
  if (dir === 'out') {
    row.appendChild(el('span', 'fm-status-text', t('contacts.pendingVerification')));
    return row;
  }
  const accept = el('button', 'glass-control fm-req-accept', t('contacts.accept'));
  accept.addEventListener('click', async (e) => {
    e.stopPropagation();
    accept.disabled = true;
    try { await api.acceptFriendRequest(rq.requestId); } catch { /* keep */ }
    await refresh();
    window.dispatchEvent(new CustomEvent('fm-friends-changed'));
  });
  const decline = el('button', 'fm-req-decline', t('contacts.decline'));
  decline.addEventListener('click', async (e) => {
    e.stopPropagation();
    decline.disabled = true;
    try { await api.declineFriendRequest(rq.requestId); } catch { /* keep */ }
    await refresh();
  });
  const btns = el('span', 'fm-req-btns');
  btns.appendChild(accept);
  btns.appendChild(decline);
  row.appendChild(btns);
  return row;
}

// ── Wiring ───────────────────────────────────────────────
let initialized = false;

export function initContacts() {
  if (initialized) return;
  initialized = true;

  onMessage('friend_event', (msg) => {
    if (msg.event === 'friend_request' || msg.event === 'friend_accepted') refresh();
  });
  // P3 error surface — friendsApi dispatches on auth failure / network error.
  window.addEventListener('fm-auth-required', () => { openLoginModal(); });
  window.addEventListener('fm-network-error', () => { window.__showToast?.(t('messages.networkError'), 'error'); });
  window.addEventListener('fm-friends-changed', () => refresh());

  // Refresh when the panel becomes active (covers login-state changes).
  const panel = document.getElementById('panel-contacts');
  if (panel) {
    new MutationObserver(() => {
      if (panel.classList.contains('active')) refresh();
    }).observe(panel, { attributes: true, attributeFilter: ['class'] });
  }

  render();
  if (loggedIn()) refresh();
}

/** Used by messages.js for the not-friend gate (§3.3/R8). Blocked rows stay
 *  in the list (WeChat-style) but are not messageable - same gate as deleted. */
export function isFriendUser(userId) {
  return friends.some(f => f.userId === userId && !f.blocked);
}
