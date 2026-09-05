// friendsApi.js — A2A friends/messaging REST adapter (friends-messaging-arch §6.1).
//
// PRIMARY mode (default, 直连): calls the gateway's real REST endpoints
// (/api/users/search, /api/friends*, /api/conversations* — RestApiRoutes
// withNeblink+withAuth). Wire contract = friend-search-contract v1.0+§8
// (作者 2026-09-05 裁定：NL 号 = 官网 Username；统一切换、无双写别名期 §4.7)：
// 档案四字段字面 snake_case（username / display_name / avatar / relation_status），
// 信封字段维持 camelCase（userId/requestId/conversationId/createdAt/...）。
// 联调依赖 = 服务端部署窗口（neblink-server 新端点 + beta.55 同窗口发版；
// 窗口期直连搜索 404 → 「搜索失败」卡——0906 失败分态拆分后不再伪装成
// 「未找到」，联调期以此判别端点未部署）。
//
// Debug-only mock mode (kept as an escape hatch for offline UI work — P3):
//   localStorage 'fm_api_mock' = '1'   or   URL ?fmMock=1
//   Optional deterministic seed for tests:
//   localStorage 'fm_api_mock_seed' = JSON {self, users, friends, incoming,
//     outgoing, conversations, messages} — see normalizeSeed() for shapes.
//   WITHOUT the flag the real path is always used. Mock 按同一契约实现
//   （新端点/新字段/六态 relation_status），与直连共享同一 public API 面。
//
// Error surface (P3): 401/403 → window 'fm-auth-required' event (login
// guidance); network failure → 'fm-network-error' (toast). Callers still get
// the rejected promise for their own handling (e.g. keep-last-known).

import { getAuthToken } from './neblink.js';

const MOCK = (() => {
  try {
    if (localStorage.getItem('fm_api_mock') === '1') return true;
    return new URLSearchParams(location.search).has('fmMock');
  } catch { return false; }
})();

export const FM_API_MOCK = MOCK;

async function req(method, path, body) {
  let resp;
  try {
    resp = await fetch(path, {
      method,
      headers: {
        'Authorization': `Bearer ${getAuthToken()}`,
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
  } catch (e) {
    // Network failure (offline / gateway unreachable) — surface a toast,
    // then rethrow so callers can keep-last-known.
    window.dispatchEvent(new CustomEvent('fm-network-error'));
    throw e;
  }
  if (!resp.ok) {
    const err = /** @type {Error & {status?: number, data?: any}} */ (new Error(`${method} ${path} -> ${resp.status}`));
    err.status = resp.status;
    try { err.data = await resp.json(); } catch { /* no body */ }
    // withAuth returns 403 for missing/invalid token (FriendApiRoutesSpec
    // "auth gate"); 401 also handled for robustness. Guide the user to log in.
    if (resp.status === 401 || resp.status === 403) {
      window.dispatchEvent(new CustomEvent('fm-auth-required'));
    }
    throw err;
  }
  if (resp.status === 204) return {};
  const text = await resp.text();
  return text ? JSON.parse(text) : {};
}

// ── Mock store ───────────────────────────────────────────
// In-memory per page load; seeded from localStorage so tests and manual
// previews get deterministic scenarios.
let M = null;

function normalizeSeed(raw) {
  const s = raw || {};
  return {
    self: s.self || { userId: 'me', username: 'me', email: 'me@example.com', displayName: 'Me', avatar: '' },
    // search directory: 契约双键（username OR email）NOCASE 精确。新旧两种
    // user 形态都吃：新 {userId,username,email,displayName,avatar}；旧
    // {userId,neblinkId,name,avatarUrl}（str accessor 回退读，旧 seed 不改即用）。
    users: s.users || [],
    friends: s.friends || [],       // [{userId,neblinkId,name,avatarUrl,since,blocked?}]（内部 wire 形态）
    incoming: s.incoming || [],     // [{requestId,from:{userId,neblinkId,name,avatarUrl},note,status}]
    outgoing: s.outgoing || [],     // [{requestId,to:{...},note,status}]
    conversations: s.conversations || [], // [{conversationId,friend:{userId,neblinkId,name,avatarUrl},lastMessage,unreadCount}]
    messages: s.messages || {},     // conversationId -> [{id,senderId,kind,body,createdAt,agentSent?}]
    _msgSeq: 1000,
    _reqSeq: 100,
  };
}

// ── Username 契约 v1.0（friend-search-contract §4；作者 2026-09-05 裁定：
// NL 号 = 官网 Username）── 搜索唯一入口 = GET /api/users/search?q=（双键
// username OR email NOCASE 精确；miss 恒 {"found":false} 防枚举，两维度无
// 差别；relation_status 六态见 relationOf）。wire 档案四字段 = {userId,
// username, display_name, avatar}（snake_case 字面，信封 camelCase 维持）。
// 本文件是唯一 wire↔内部形态边界：搜索结果归一为内部扁平字段（username/
// displayName/avatar + relation_status 原样透传）；列表链归一为内部 wire
// 对象（neblinkId/name/avatarUrl，值域即 username/display_name/avatar）——
// 消费方零散字段读不动（§4.7 单点归一，无双写别名期指 wire 而言）。
const uName = (u) => u.username ?? u.neblinkId ?? '';
const uDisplay = (u) => u.displayName ?? u.name ?? '';
const uAvatar = (u) => u.avatar ?? u.avatarUrl ?? '';
const uEmail = (u) => u.email ?? (/^[^\s@]+@[^\s@]+$/.test(u.neblinkId || '') ? u.neblinkId : '');

/** 契约双键匹配：q 对 username 与 email 同时 NOCASE 精确比较（服务端 §4.1
 *  语义镜像；不分维度分支）。 */
function matchUser(m, s) {
  if (!s) return null;
  const norm = s.toLowerCase();
  return m.users.find(u => uName(u).toLowerCase() === norm || uEmail(u).toLowerCase() === norm) || null;
}

/** 契约 wire 档案 {userId, username, display_name, avatar, ...} → 内部 wire
 *  对象 {userId, neblinkId, name, avatarUrl}（值域=同一人；无 snake_case 键
 *  的对象原样透传——内部/mock 形态单点判定安全）。 */
function personFromWire(p) {
  if (!p || typeof p !== 'object') return p;
  if (p.username === undefined && p.display_name === undefined && p.avatar === undefined) return p;
  return { userId: p.userId, neblinkId: p.username ?? '', name: p.display_name ?? '', avatarUrl: p.avatar ?? '' };
}

/** 搜索响应归一：契约 {found:true, user:{username,display_name,avatar},
 *  relation_status} → 内部扁平 {found, userId?, username, displayName,
 *  avatar, relation_status}。miss 恒 {found:false}（无 user/relation_status
 *  冗余键，防枚举形状一致 §4.1/§5.1）。 */
function normalizeSearch(raw) {
  if (!raw || typeof raw !== 'object' || raw.found !== true) return { found: false };
  const u = raw.user && typeof raw.user === 'object' ? raw.user : {};
  return {
    found: true,
    userId: u.userId,
    username: u.username ?? '',
    displayName: u.display_name ?? '',
    avatar: u.avatar ?? '',
    relation_status: raw.relation_status,
  };
}

/** mock relation_status 六态判定（契约 §4.1；镜像服务端可加性语义——被对方
 *  拉黑不可区分，落入 addable §5.4）。 */
function relationOf(m, u) {
  if (u.userId === m.self.userId) return 'self';
  const fr = m.friends.find(f => f.userId === u.userId);
  if (fr && fr.blocked) return 'blocked_by_me';
  if (fr) return 'already_friends';
  if (m.outgoing.some(r => r.to?.userId === u.userId && (r.status || 'pending') === 'pending')) return 'outgoing_pending';
  if (m.incoming.some(r => r.from?.userId === u.userId && (r.status || 'pending') === 'pending')) return 'incoming_pending';
  return 'addable';
}

/** 搜索命中 → wire 人对象（outgoing/requestRow/accept 等下游消费
 *  neblinkId/name/avatarUrl 字段）。 */
function wirePerson(u) {
  return { userId: u.userId, neblinkId: uName(u), name: uDisplay(u), avatarUrl: uAvatar(u) };
}

function mockStore() {
  if (M) return M;
  let seed = null;
  try {
    const raw = localStorage.getItem('fm_api_mock_seed');
    if (raw) seed = JSON.parse(raw);
  } catch { /* fall through to default */ }
  if (!seed) {
    seed = {
      users: [{ userId: 'u-lin', username: 'lin', email: 'lin@example.com', displayName: '林小满', avatar: '' }],
      friends: [{ userId: 'u-lin', neblinkId: 'lin@example.com', name: '林小满', avatarUrl: '', since: new Date().toISOString() }],
      conversations: [{
        conversationId: 'c-lin',
        friend: { userId: 'u-lin', neblinkId: 'lin@example.com', name: '林小满', avatarUrl: '' },
        lastMessage: null,
        unreadCount: 0,
      }],
      messages: { 'c-lin': [] },
    };
  }
  M = normalizeSeed(seed);
  return M;
}

function mockError(message, status) {
  const e = /** @type {Error & {status?: number}} */ (new Error(message));
  e.status = status;
  return e;
}

const delay = () => new Promise(r => setTimeout(r, Number((() => { try { return localStorage.getItem('fm_api_mock_delay'); } catch { return null; } })()) || 30));

// ── Public API (same surface in both modes) ─────────────

/** GET /api/users/search?q= → 契约 v1.0（§4.1）：{found:true, user:{username,
 *  display_name, avatar}, relation_status} | {found:false}（miss 恒等形状，
 *  username/email 两维度无差别防枚举 §5.1）。双键 NOCASE 精确语义在服务端；
 *  空白 q 服务端亦回 found:false；>256 字符 422 invalid_query、超频 429
 *  rate_limited 走 req() 错误面（err.status/err.data），调用方 catch 兜底
 *  「搜索失败」卡（0906 失败分态；窗口期服务端未部署 = 404，同一路径）。 */
export async function searchUser(q) {
  if (!MOCK) return normalizeSearch(await req('GET', `/api/users/search?q=${encodeURIComponent(q)}`));
  await delay();
  const m = mockStore();
  const s = String(q || '').trim();
  if (s.length > 256) throw mockError('invalid_query', 422);
  if (!s) return { found: false };
  const norm = s.toLowerCase();
  // self 命中：username / email 任一自身标识（relation_status='self' §4.1）
  const isSelf = [uName(m.self), uEmail(m.self)].some(v => v && v.toLowerCase() === norm);
  const hit = isSelf ? m.self : matchUser(m, s);
  if (!hit) return { found: false };
  return {
    found: true,
    userId: hit.userId,
    username: uName(hit),
    displayName: uDisplay(hit) || uName(hit) || hit.userId || '',  // display_name 永不空 fallback 链镜像（§3.1）
    avatar: uAvatar(hit),
    relation_status: relationOf(m, hit),
  };
}

/** GET /api/friends → {friends, incoming, outgoing}。直接链路：上游契约档案
 *  {userId, username, display_name, avatar, since, blocked}（§4.5）经
 *  personFromWire 归一为内部 wire 对象；信封字段 since/blocked 原样保留。
 *  mock 链路本就内部形态，原样返回。 */
export async function getFriends() {
  if (!MOCK) {
    const data = await req('GET', '/api/friends');
    return {
      friends: (data.friends || []).map(f => ({ ...personFromWire(f), since: f.since, blocked: f.blocked })),
      incoming: (data.incoming || []).map(r => ({ ...r, from: personFromWire(r.from) })),
      outgoing: (data.outgoing || []).map(r => ({ ...r, to: personFromWire(r.to) })),
    };
  }
  await delay();
  const m = mockStore();
  return { friends: [...m.friends], incoming: [...m.incoming], outgoing: [...m.outgoing] };
}

/** POST /api/friends/requests {query, note?} → {requestId} (201)。
 *  形状不变（§4.4）；query 语义 = username OR email 双键精确（服务端）。 */
export async function sendFriendRequest(query, note) {
  if (!MOCK) return req('POST', '/api/friends/requests', { query, ...(note ? { note } : {}) });
  await delay();
  const m = mockStore();
  const hit = matchUser(m, String(query || '').trim());
  if (!hit) throw mockError('not found', 404);
  const requestId = 'rq-' + (++m._reqSeq);
  // outgoing/requestRow/acceptFriendRequest 消费 wire 形态——新契约命中归一后再入队
  m.outgoing.push({ requestId, to: wirePerson(hit), note: note || '', status: 'pending' });
  return { requestId };
}

/** POST /api/friends/requests/{id}/accept → {friendshipId, conversationId} */
export async function acceptFriendRequest(requestId) {
  if (!MOCK) return req('POST', `/api/friends/requests/${encodeURIComponent(requestId)}/accept`);
  await delay();
  const m = mockStore();
  const rq = m.incoming.find(r => r.requestId === requestId);
  if (!rq) throw mockError('not found', 404);
  rq.status = 'accepted';
  const friend = { userId: rq.from.userId, neblinkId: rq.from.neblinkId, name: rq.from.name, avatarUrl: rq.from.avatarUrl || '', since: new Date().toISOString() };
  m.friends.push(friend);
  const conversationId = 'c-' + friend.userId;
  if (!m.conversations.some(c => c.conversationId === conversationId)) {
    m.conversations.unshift({ conversationId, friend, lastMessage: null, unreadCount: 0 });
    m.messages[conversationId] = [];
  }
  return { friendshipId: 'fs-' + requestId, conversationId };
}

/** POST /api/friends/requests/{id}/decline → 200 */
export async function declineFriendRequest(requestId) {
  if (!MOCK) return req('POST', `/api/friends/requests/${encodeURIComponent(requestId)}/decline`);
  await delay();
  const m = mockStore();
  const rq = m.incoming.find(r => r.requestId === requestId);
  if (rq) rq.status = 'declined';
  return {};
}

/** GET /api/conversations → [{conversationId, friend, lastMessage, unreadCount}]。
 *  直接链路：内嵌 friend 契约档案经 personFromWire 归一（§4.5 切换面）。 */
export async function getConversations() {
  if (!MOCK) {
    const convs = await req('GET', '/api/conversations');
    return (Array.isArray(convs) ? convs : []).map(c => ({ ...c, friend: personFromWire(c.friend) }));
  }
  await delay();
  return mockStore().conversations.map(c => ({ ...c }));
}

/** GET /api/conversations/{id}/messages?after=&limit= → [{id,senderId,kind,body,createdAt,origin?}] ascending.
 *  Server keyset is FORWARD-only (store.rs: id > after, ASC, limit clamp 1..200)
 *  — 「load older」 history windows backwards client-side via id arithmetic
 *  (messages.js HISTORY_WINDOW). */
export async function getMessages(conversationId, { after = 0, limit = 50 } = {}) {
  const a = Math.max(0, Math.floor(Number(after) || 0));
  const l = Math.min(200, Math.max(1, Math.floor(Number(limit) || 50)));
  if (!MOCK) return req('GET', `/api/conversations/${encodeURIComponent(conversationId)}/messages?after=${a}&limit=${l}`);
  await delay();
  const m = mockStore();
  const msgs = [...(m.messages[conversationId] || [])];
  const numOf = (x) => (typeof x === 'number' ? x : parseInt(x, 10));
  const filtered = a > 0 ? msgs.filter(x => !isNaN(numOf(x.id)) && numOf(x.id) > a) : msgs;
  return filtered.slice(0, l);
}

/** POST /api/friends/{friendUserId}/messages {body} → {messageId, conversationId, createdAt} */
export async function sendFriendMessage(friendUserId, body) {
  if (!MOCK) return req('POST', `/api/friends/${encodeURIComponent(friendUserId)}/messages`, { body });
  await delay();
  const m = mockStore();
  const friend = m.friends.find(f => f.userId === friendUserId);
  if (!friend) throw mockError('not a friend', 403);
  let conv = m.conversations.find(c => c.friend.userId === friendUserId);
  if (!conv) {
    conv = { conversationId: 'c-' + friendUserId, friend, lastMessage: null, unreadCount: 0 };
    m.conversations.unshift(conv);
    m.messages[conv.conversationId] = [];
  }
  const msg = { id: 'm-' + (++m._msgSeq), senderId: m.self.userId, kind: 'text', body, createdAt: new Date().toISOString() };
  m.messages[conv.conversationId].push(msg);
  conv.lastMessage = msg;
  return { messageId: msg.id, conversationId: conv.conversationId, createdAt: msg.createdAt };
}

/** DELETE /api/friends/{friendUserId} → 200 (#290 addendum §1.1) */
export async function removeFriend(friendUserId) {
  if (!MOCK) return req('DELETE', `/api/friends/${encodeURIComponent(friendUserId)}`);
  await delay();
  const m = mockStore();
  m.friends = m.friends.filter(f => f.userId !== friendUserId);
  return {};
}

/** POST /api/friends/{friendUserId}/block → 200 (#290 addendum §1.2) */
export async function blockFriend(friendUserId) {
  if (!MOCK) return req('POST', `/api/friends/${encodeURIComponent(friendUserId)}/block`);
  await delay();
  const m = mockStore();
  const f = m.friends.find(f => f.userId === friendUserId);
  if (f) f.blocked = true;
  return {};
}

/** POST /api/friends/{friendUserId}/unblock → 200 (#290 addendum §1.2) */
export async function unblockFriend(friendUserId) {
  if (!MOCK) return req('POST', `/api/friends/${encodeURIComponent(friendUserId)}/unblock`);
  await delay();
  const m = mockStore();
  const f = m.friends.find(f => f.userId === friendUserId);
  if (f) f.blocked = false;
  return {};
}

/** POST /api/conversations/{id}/read {lastReadMessageId} → 200 */
export async function markConversationRead(conversationId, lastReadMessageId) {
  if (!MOCK) { await req('POST', `/api/conversations/${encodeURIComponent(conversationId)}/read`, { lastReadMessageId }); return; }
  await delay();
  const conv = mockStore().conversations.find(c => c.conversationId === conversationId);
  if (conv) conv.unreadCount = 0;
}

// 旧端点同 release 移除（friend-search-contract §4.7）：GET /api/users/lookup、
// PUT /api/users/me/neblink-id、GET /api/users/me/neblink-id/available 的客户端
// 调用已全部摘除（setNeblinkId/neblinkIdAvailable 随 NL 号自定义 UI 一并删除——
// 10:54 裁定：客户端不提供修改入口，Username 经官网账号中心设置）。

/** Test/mock helper: inject an inbound message as if a friend_event arrived. */
export function mockInjectMessage(conversationId, msg) {
  const m = mockStore();
  const conv = m.conversations.find(c => c.conversationId === conversationId);
  if (!conv) return;
  (m.messages[conversationId] = m.messages[conversationId] || []).push(msg);
  conv.lastMessage = msg;
}

/** Test/mock helper: inject an incoming friend request (friend_event stand-in
 *  — the WS event only notifies; REST is the source of truth for the list). */
export function mockInjectIncomingRequest(entry) {
  mockStore().incoming.push(entry);
}
