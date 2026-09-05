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
    self: s.self || { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
    // lookup directory: all users searchable by neblinkId (exact, case-insensitive)
    users: s.users || [],
    friends: s.friends || [],       // [{userId,neblinkId,name,avatarUrl,since}]
    incoming: s.incoming || [],     // [{requestId,from:{userId,neblinkId,name,avatarUrl},note,status}]
    outgoing: s.outgoing || [],     // [{requestId,to:{...},note,status}]
    conversations: s.conversations || [], // [{conversationId,friend:{userId,neblinkId,name,avatarUrl},lastMessage,unreadCount}]
    messages: s.messages || {},     // conversationId -> [{id,senderId,kind,body,createdAt,agentSent?}]
    _msgSeq: 1000,
    _reqSeq: 100,
  };
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
      users: [{ userId: 'u-lin', neblinkId: 'lin@example.com', name: '林小满', avatarUrl: '' }],
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

/** GET /api/users/lookup?q= → {found, neblinkId?, name?, avatarUrl?, self?} */
export async function lookupUser(q) {
  if (!MOCK) return req('GET', `/api/users/lookup?q=${encodeURIComponent(q)}`);
  await delay();
  const m = mockStore();
  const norm = String(q || '').trim().toLowerCase();
  if (norm && norm === m.self.neblinkId.toLowerCase()) {
    return { found: true, self: true, neblinkId: m.self.neblinkId, name: m.self.name, avatarUrl: m.self.avatarUrl };
  }
  const hit = m.users.find(u => u.neblinkId.toLowerCase() === norm);
  return hit
    ? { found: true, neblinkId: hit.neblinkId, name: hit.name, avatarUrl: hit.avatarUrl || '', userId: hit.userId }
    : { found: false };
}

/** GET /api/friends → {friends, incoming, outgoing} */
export async function getFriends() {
  if (!MOCK) return req('GET', '/api/friends');
  await delay();
  const m = mockStore();
  return { friends: [...m.friends], incoming: [...m.incoming], outgoing: [...m.outgoing] };
}

/** POST /api/friends/requests {query, note?} → {requestId} (201) */
export async function sendFriendRequest(query, note) {
  if (!MOCK) return req('POST', '/api/friends/requests', { query, ...(note ? { note } : {}) });
  await delay();
  const m = mockStore();
  const norm = String(query || '').trim().toLowerCase();
  const hit = m.users.find(u => u.neblinkId.toLowerCase() === norm);
  if (!hit) throw mockError('not found', 404);
  const requestId = 'rq-' + (++m._reqSeq);
  m.outgoing.push({ requestId, to: { ...hit }, note: note || '', status: 'pending' });
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

// NL 号 API（PUT/GET /api/users/me/neblink-id*）已随设置页 NL 号入口移除
// （作者 2026-09-05 裁定：NL 号统一 = 官网 Username，官网已有修改功能）——
// 前端唯一消费者（neblink.js 号自定义区）已删，包装不再保留。

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
