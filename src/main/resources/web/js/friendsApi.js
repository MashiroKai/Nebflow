// friendsApi.js — A2A friends/messaging REST adapter (friends-messaging-arch §6.1).
//
// PRIMARY mode (default): calls the gateway's real REST endpoints
// (/api/friends*, /api/conversations*, /api/users/lookup — RestApiRoutes
// withNeblink+withAuth, contract pinned by FriendApiRoutesSpec).
//
// Debug-only mock mode (kept as an escape hatch for offline UI work — P3):
//   localStorage 'fm_api_mock' = '1'   or   URL ?fmMock=1
//   Optional deterministic seed for tests:
//   localStorage 'fm_api_mock_seed' = JSON {self, users, friends, incoming,
//     outgoing, conversations, messages} — see normalizeSeed() for shapes.
//   WITHOUT the flag the real path is always used.
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
    // lookup directory: searchable by email (q 含 @) or username (否则)，均精确
    // 大小写不敏感。新旧两种 user 形态都吃：新 {userId,username,email,
    // displayName,avatar}；旧 {userId,neblinkId,name,avatarUrl}（str accessor
    // 回退读，friend-chain-ui 旧 seed 不改即用）。
    users: s.users || [],
    friends: s.friends || [],       // [{userId,neblinkId,name,avatarUrl,since}] (wire 形态)
    incoming: s.incoming || [],     // [{requestId,from:{userId,neblinkId,name,avatarUrl},note,status}]
    outgoing: s.outgoing || [],     // [{requestId,to:{...},note,status}]
    conversations: s.conversations || [], // [{conversationId,friend:{userId,neblinkId,name,avatarUrl},lastMessage,unreadCount}]
    messages: s.messages || {},     // conversationId -> [{id,senderId,kind,body,createdAt,agentSent?}]
    _msgSeq: 1000,
    _reqSeq: 100,
  };
}

// ── Username 契约（作者 2026-09-05 裁定：NL 号 = 官网 Username，同一事物）──
// lookup 结果新契约 {found, self?, userId?, username, displayName, avatar,
// email}；旧形态 {neblinkId,name,avatarUrl} 在 accessor 层回退读取（网关
// lookup 为纯透传，字段归一在 JS 侧完成——形态 A，friend-chain-ui Decoder
// 双形态先例的对应物）。好友/会话等 wire 对象保持 neblinkId 字段不动（其值
// 即 username，最小改名口径，见 batch 报告）。
const uName = (u) => u.username ?? u.neblinkId ?? '';
const uDisplay = (u) => u.displayName ?? u.name ?? '';
const uAvatar = (u) => u.avatar ?? u.avatarUrl ?? '';
const uEmail = (u) => u.email ?? (/^[^\s@]+@[^\s@]+$/.test(u.neblinkId || '') ? u.neblinkId : '');

/** 双识别（微信「手机号/邮箱/微信号」→ 本产品「邮箱/Username」）：q 含 @ 按
 *  邮箱精确匹配，否则按 Username 精确大小写不敏感匹配。 */
function matchUser(m, s) {
  if (!s) return null;
  const norm = s.toLowerCase();
  const pred = s.includes('@')
    ? (u) => uEmail(u).toLowerCase() === norm
    : (u) => uName(u).toLowerCase() === norm;
  return m.users.find(pred) || null;
}

function lookupOf(u, extra) {
  return {
    found: true,
    userId: u.userId,
    username: uName(u),
    displayName: uDisplay(u),
    avatar: uAvatar(u),
    email: uEmail(u),
    ...extra,
  };
}

/** 真实链路响应归一：上游新契约字段直取，旧形态（neblink-server 新 lookup
 *  端点就绪前）回退映射。legacy 键原样保留（...raw），消费方只读新字段。 */
function normalizeLookup(raw) {
  if (!raw || typeof raw !== 'object' || !raw.found) return { found: false };
  return {
    ...raw,
    username: raw.username ?? raw.neblinkId ?? '',
    displayName: raw.displayName ?? raw.name ?? '',
    avatar: raw.avatar ?? raw.avatarUrl ?? '',
  };
}

/** lookup 命中（新契约形态）→ wire 人对象（outgoing/requestRow/accept 等
 *  下游消费 neblinkId/name/avatarUrl 字段）。 */
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

/** GET /api/users/lookup?q= → 新契约 {found, self?, userId?, username,
 *  displayName, avatar, email}。q 含 @ 按邮箱精确匹配，否则按 Username 精确
 *  大小写不敏感匹配（双识别语义上移 neblink-server——形态 A，网关纯透传，
 *  调用形态不变；旧形态响应在 normalizeLookup 归一）。self 命中：任一自身
 *  标识（username/neblinkId/email）命中即视为本人。 */
export async function lookupUser(q) {
  if (!MOCK) return normalizeLookup(await req('GET', `/api/users/lookup?q=${encodeURIComponent(q)}`));
  await delay();
  const m = mockStore();
  const s = String(q || '').trim();
  const norm = s.toLowerCase();
  const selfIds = [uName(m.self), m.self.neblinkId, uEmail(m.self)]
    .filter(Boolean).map((v) => v.toLowerCase());
  if (s && selfIds.includes(norm)) return lookupOf(m.self, { self: true });
  const hit = matchUser(m, s);
  return hit ? lookupOf(hit) : { found: false };
}

/** GET /api/friends → {friends, incoming, outgoing} */
export async function getFriends() {
  if (!MOCK) return req('GET', '/api/friends');
  await delay();
  const m = mockStore();
  return { friends: [...m.friends], incoming: [...m.incoming], outgoing: [...m.outgoing] };
}

/** POST /api/friends/requests {query, note?} → {requestId} (201)。
 *  query 双识别同 lookup（邮箱 / Username）。 */
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

/** GET /api/conversations → [{conversationId, friend, lastMessage, unreadCount}] */
export async function getConversations() {
  if (!MOCK) return req('GET', '/api/conversations');
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

/** PUT /api/users/me/neblink-id {neblinkId} → 200 {neblinkId} ([U3] 号自定义).
 *  Upstream 409 taken / 422 invalid collapse to 502 + error string via the
 *  gateway — the UI pre-validates (regex + available check) so these only
 *  surface as rare races. */
export async function setNeblinkId(neblinkId) {
  if (!MOCK) return req('PUT', '/api/users/me/neblink-id', { neblinkId });
  await delay();
  const m = mockStore();
  m.self.neblinkId = neblinkId;
  return { neblinkId };
}

/** GET /api/users/me/neblink-id/available?q= → {available, reason?} ([U3]).
 *  reason: 'taken' | 'invalid'; 20/min shared with lookup (server limiter). */
export async function neblinkIdAvailable(q) {
  if (!MOCK) return req('GET', `/api/users/me/neblink-id/available?q=${encodeURIComponent(q)}`);
  await delay();
  const m = mockStore();
  const v = String(q || '').trim();
  if (!/^[a-zA-Z0-9]{3,32}$/.test(v)) return { available: false, reason: 'invalid' };
  // Server semantics: uniqueness excludes SELF (own current id stays available).
  const taken = m.users.some(u => (u.neblinkId || '').toLowerCase() === v.toLowerCase());
  return taken ? { available: false, reason: 'taken' } : { available: true };
}

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
