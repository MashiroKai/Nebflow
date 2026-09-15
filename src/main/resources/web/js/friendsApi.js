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

/** err → 分态 kind（0908 作者令·好友面板报错分态，好友域内复用）：
 *  'auth'       = 401/403 登录失效（withAuth 鉴权失败；req() 已另派
 *                 fm-auth-required → openLoginModal 全局链，卡片/toast 为兜底可见反馈）
 *  'neblinkOff' = 404（friendService None → "NebLink not enabled"；勿与
 *                 「未找到该用户」混态——found:false 恒 200，不进错误面）
 *  'retryable'  = 5xx/422/429/网络及其余（网络错无 status，req() 已另派
 *                 fm-network-error 全局 toast；消费方可凭 err.status 缺失跳过
 *                 本地 toast 避免双提示，卡片态与全局 toast 并存不冲突）。 */
export function errKind(err) {
  const s = err && err.status;
  if (s === 401 || s === 403) return 'auth';
  if (s === 404) return 'neblinkOff';
  return 'retryable';
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
    groups: s.groups || [],         // 群会话行（normalizeGroupRow 消费形状）
    groupInvites: s.groupInvites || [], // [{inviteId,conversationId,inviterId,inviteeId,status}]
    groupMembers: s.groupMembers || {}, // groupId -> [{userId,name,avatarUrl,role}]
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

/** 契约 wire 档案 {userId, username, display_name, avatar, ..., remark} → 内部
 *  wire 对象 {userId, neblinkId, name, avatarUrl, remark}（值域=同一人；无
 *  snake_case 键的对象原样透传——内部/mock 形态单点判定安全）。
 *  ⑦ 备注（作者裁定 2026-09-12，冻结契约 §2）：`remark` 键恒在，`string|null`
 *  （null = 无备注）——这里**必须同步加键**，否则 wire 键在此边界被丢弃，
 *  下游三处显示面永远看不到备注。 */
function personFromWire(p) {
  if (!p || typeof p !== 'object') return p;
  if (p.username === undefined && p.display_name === undefined && p.avatar === undefined) return p;
  return { userId: p.userId, neblinkId: p.username ?? '', name: p.display_name ?? '', avatarUrl: p.avatar ?? '', remark: p.remark ?? null };
}

/** 搜索响应归一：契约 {found:true, user:{username,display_name,avatar},
 *  relation_status} → 内部扁平 {found, userId?, username, displayName,
 *  avatar, relation_status, remark?}。miss 恒 {found:false}（无 user/
 *  relation_status 冗余键，防枚举形状一致 §4.1/§5.1）。
 *  ⑦ 备注：搜索命中若带 `remark` 则原样透传（缺省 null）——`/api/users/search`
 *  本身不下发备注（备注只是好友属性），此处仅为「搜索回落路径」保持同一 wire
 *  形状，不让键在中途丢掉。 */
function normalizeSearch(raw) {
  if (!raw || typeof raw !== 'object' || raw.found !== true) return { found: false };
  const u = raw.user && typeof raw.user === 'object' ? raw.user : {};
  return {
    found: true,
    userId: u.userId,
    username: u.username ?? '',
    displayName: u.display_name ?? '',
    avatar: u.avatar ?? '',
    remark: u.remark ?? null,
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
      friends: [{ userId: 'u-lin', neblinkId: 'lin@example.com', name: '林小满', avatarUrl: '', remark: null, since: new Date().toISOString() }],
      conversations: [{
        conversationId: 'c-lin',
        friend: { userId: 'u-lin', neblinkId: 'lin@example.com', name: '林小满', avatarUrl: '', remark: null },
        lastMessage: null,
        unreadCount: 0,
      }],
      messages: { 'c-lin': [] },
      groups: [{
        conversationId: 'g-demo',
        kind: 'group',
        title: '项目群',
        lastMessage: null,
        unreadCount: 0,
        memberCount: 2,
        myRole: 'owner',
      }],
      groupInvites: [],
      groupMembers: {
        'g-demo': [
          { userId: 'me', name: 'Me', avatarUrl: '', role: 'owner' },
          { userId: 'u-lin', name: '林小满', avatarUrl: '', role: 'member' },
        ],
      },
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
  // ⑦ 搜索回落路径与好友表同步备注（mock 面同形；无备注 = null）。
  const fr = m.friends.find(x => x.userId === hit.userId);
  return {
    found: true,
    userId: hit.userId,
    username: uName(hit),
    displayName: uDisplay(hit) || uName(hit) || hit.userId || '',  // display_name 永不空 fallback 链镜像（§3.1）
    avatar: uAvatar(hit),
    remark: (fr && fr.remark) || null,
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
    return rememberFriendState(convergeRequests({
      friends: (data.friends || []).map(f => ({ ...personFromWire(f), since: f.since, blocked: f.blocked })),
      incoming: (data.incoming || []).map(r => ({ ...r, from: personFromWire(r.from) })),
      outgoing: (data.outgoing || []).map(r => ({ ...r, to: personFromWire(r.to) })),
    }));
  }
  await delay();
  const m = mockStore();
  return rememberFriendState(
    convergeRequests({ friends: [...m.friends], incoming: [...m.incoming], outgoing: [...m.outgoing] })
  );
}

// ── 批 B（§3.4）：申请状态机收敛 + 判据单点 ────────────────────────────
// **真源 = 服务端关系态**。跨仓已证（neblink-server 报告 `## 跨仓引用节` §6.1）：
//  · `friendships` 一对一行（`UNIQUE(user_lo,user_hi)`）⇒ accept 是**原地 UPDATE**，
//    建立关系即**双向**收敛（一行即两侧），服务端**不存在 pending 残留路径**；
//  · `GET /api/friends` 的 `outgoing[]` **只可能含 pending** —— accepted 行按 `status`
//    分流进 `friends[]`，随即从 `outgoing[]` 消失（`store.rs:4238` / `:4245-4257`）；
//  · `outgoing[]`/`incoming[]` 行走上**没有 `status` 键** ⇒ 客户端必须按「数组成员
//    资格 + `friends[]` 成员资格」判态，不能靠行内 `status`。
// ⇒ 本端**唯一**合法的收敛判据 = `friends[]` 命中；**禁**按本地点击 / `sentTo` 乐观态
// 收敛（红线③：收敛过早会掩盖真实未决请求）。
//
// `convergeRequests` 是这条判据的**单点**（`getFriends` 出口唯一经它）：命中 `friends[]`
// 的对方行从 incoming/outgoing 两侧**同时**移除 ⇒ 双向待处理在源头上就不可能残留。
// 为什么放在**取数出口**而不是各渲染点：出参面只有一个缝，渲染点有四处（搜索卡 /
// 请求区 / 行内编辑 / 面板重建）——放到渲染点就是四份判据，必然漂移。
let lastFriendState = { fetchedAt: 0, friends: [], incoming: [], outgoing: [] };

function convergeRequests(data) {
  const friends = data.friends || [];
  const friendIds = new Set(friends.map(f => f.userId).filter(Boolean));
  return {
    friends,
    incoming: (data.incoming || []).filter(r => !(r.from?.userId && friendIds.has(r.from.userId))),
    outgoing: (data.outgoing || []).filter(r => !(r.to?.userId && friendIds.has(r.to.userId))),
  };
}

/** 最近一次 `getFriends()` 的**已收敛**快照（供判据函数与断言面读取；不落盘）。
 *  `fetchedAt` 是「无证据 vs 已收敛」的判别子（见 `friendStateLoaded`）。 */
function rememberFriendState(data) {
  lastFriendState = {
    fetchedAt: Date.now(),
    friends: data.friends || [],
    incoming: data.incoming || [],
    outgoing: data.outgoing || [],
  };
  return data;
}

/** 判据②（§3.4）的**判据函数**：该 userId 是否仍「我方出站待处理」。
 *  语义 = `relationOf`（本文件 `:169`）同判据，但作用在**真服务端快照**上而不是 mock 仓：
 *  ① `friends[]` 命中 ⇒ **false**（关系已建立，出站待处理必然已终结）；
 *  ② 否则按 `outgoing[]` 成员资格 + `status` 缺省视为 pending（与既有行内口径逐字一致）。
 *  导出面：机械断言与面板渲染**同源** ⇒ 「判据绿」与「UI 无待处理行」不会各说各话。 */
export function pendingOutgoing(userId) {
  if (!userId) return false;
  if (lastFriendState.friends.some(f => f.userId === userId)) return false;
  return lastFriendState.outgoing.some(r => r.to?.userId === userId && (r.status || 'pending') === 'pending');
}

/** `pendingOutgoing` 的入站同族判据（双向互加后两侧必须**同时**为 false）。 */
export function pendingIncoming(userId) {
  if (!userId) return false;
  if (lastFriendState.friends.some(f => f.userId === userId)) return false;
  return lastFriendState.incoming.some(r => r.from?.userId === userId && (r.status || 'pending') === 'pending');
}

/** 快照是否**已装载**（`getFriends()` 至少成功过一次）。
 *
 *  为什么必须能判它：`pendingOutgoing`/`pendingIncoming` 在**无快照**时返回 `false`
 *  ——那一态的 `false` 是「无证据」，不是「已收敛」。若断言只看 `false`，一个**从未
 *  取过数**的环境会被读成「判据成立」= 假的绿。⇒ 判据必须同时要求 `friendStateLoaded()
 *  === true`（机械可判），把「无证据」与「收敛」分开。
 *  `lastFriendState.fetchedAt` 亦可用于断言快照**在事件之后**刷新过（时效面）。 */
export function friendStateLoaded() {
  return lastFriendState.fetchedAt > 0;
}

/** 快照的只读副本（判据面用；返回浅拷贝，调用方改不动内部态）。 */
export function friendStateSnapshot() {
  return {
    fetchedAt: lastFriendState.fetchedAt,
    friends: [...lastFriendState.friends],
    incoming: [...lastFriendState.incoming],
    outgoing: [...lastFriendState.outgoing],
  };
}

/** mock 链路的 `GET /api/friends` 注入点（既有测试夹具用）：注入后**同一套**收敛
 *  判据生效（判据面与生产链路同源，禁两份）。 */
export function __convergeForTest(data) {
  return rememberFriendState(convergeRequests(data));
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

/** GET /api/friends/attachments/{id} → {blob, filename}（4b 腿 A-3）。
 *
 *  🔴 **唯一取字节入口 = 应用内鉴权路由**（作者裁定②）：本函数只打网关的
 *  `/api/friends/attachments/{id}`（`withAuth`），带 Bearer 应用令牌 —— 前端
 *  **拿不到也拼不出**服务端地址或静态/公开 URL（服务端附件目录不挂 Caddy，
 *  跨仓契约件 §D.1/§A.2 N3）。禁在此另写第二条取字节路径。
 *
 *  错误面**保状态码**（`err.status`）× 语义：`410` = 附件已过期（**终态**，
 *  UI 据此升级为「附件已过期」，不提供重试）；`404` = 不存在/不可见；
 *  `403` = 非好友（关系被拉黑等）；其余（含 5xx/网络）⇒ 可重试的下载失败。 */
export async function downloadAttachment(attachmentId) {
  const path = `/api/friends/attachments/${encodeURIComponent(attachmentId)}`;
  if (MOCK) {
    // Mock 模式没有字节面：**显式失败**（不伪造文件），UI 落「下载失败，点击重试」。
    const e = /** @type {Error & {status?: number}} */ (new Error(`mock mode: no attachment bytes for ${attachmentId}`));
    e.status = 0;
    throw e;
  }
  let resp;
  try {
    resp = await fetch(path, { headers: { 'Authorization': `Bearer ${getAuthToken()}` } });
  } catch (e) {
    window.dispatchEvent(new CustomEvent('fm-network-error'));
    throw e;
  }
  if (!resp.ok) {
    const err = /** @type {Error & {status?: number, data?: any}} */ (new Error(`${path} -> ${resp.status}`));
    err.status = resp.status;
    try { err.data = await resp.json(); } catch { /* no body */ }
    if (resp.status === 401 || resp.status === 403) {
      window.dispatchEvent(new CustomEvent('fm-auth-required'));
    }
    throw err;
  }
  const blob = await resp.blob();
  return { blob, filename: filenameFromDisposition(resp.headers.get('Content-Disposition')) };
}

/** POST /api/friends/attachments/{id}/received —— E4 接收完毕回执（补件批 4b1 · §B.1 E4 / §F.1b）。
 *
 *  🔴 **纯上报，不判定**：本函数只把「客户端持有的落盘证据」交给网关；**能不能发 E4**
 *  由引擎侧的 fail-closed 闸决定（`AttachmentAck.decide`）。禁在前端复制第二套判定 ——
 *  复制即双实现，两侧判据迟早漂移，正是 §F.1b② 「防误删」最怕的形态。
 *
 *  🔴 **调用方必须 fire-and-forget**：本函数**不抛**（传输/4xx/5xx 一律吞成 null），
 *  且**不得**被 await 进下载/保存/UI 路径 —— 回执失败对用户零影响（§F.1b 规则 4：
 *  丢 ack 的兜底 = 服务端 24 h 强删，用户侧零损失、盘不泄漏）。
 *
 *  @param {string} attachmentId 附件 id（E1/E3 同一个 id）
 *  @param {{wholeSha256: (string|null), declaredSha256: string, receivedBytes: (number|null),
 *           expectedBytes: (number|null), landedFinal: boolean}} evidence 落盘证据（见 §F.1b①）
 *  @returns {Promise<any>} 网关结局体 `{ack, reason?}`；失败 ⇒ null（调用方无需区分）
 */
export async function ackAttachmentReceived(attachmentId, evidence) {
  if (MOCK) return null; // mock 面没有字节也没有服务端 ⇒ 零回执（不伪造）
  try {
    return await req('POST', `/api/friends/attachments/${encodeURIComponent(attachmentId)}/received`, evidence);
  } catch {
    return null; // 静默：回执面永不弹错、永不改调用方结果
  }
}

/** `Content-Disposition: attachment; filename*=UTF-8''<pct-encoded>`（§B.1 E3 恒定头）
 *  → 文件名；解析不出 ⇒ null（调用方回落附件元数据的 `name`）。 */
function filenameFromDisposition(cd) {
  if (!cd) return null;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  if (star) {
    try { return decodeURIComponent(star[1].trim()); } catch { return null; }
  }
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  return plain ? plain[1].trim() : null;
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

/** PUT /api/friends/{friendUserId}/remark {remark} → 200 {ok:true}
 *  ⑦ 好友备注（作者裁定 2026-09-12，冻结契约 §1/§4）：body `{"remark":"<string>"}`
 *  （提交前 trim，长度 ≤64 由调用方 maxlength 与 trim 共同保证）；空串 = 清除备注。
 *  缺参 ⇒ 400、未认证 ⇒ 403（走 req() 错误面）；mock 面同形实现（含会话档案同步，
 *  否则 mock 下三处显示面看不到备注）。 */
export async function setFriendRemark(friendUserId, remark) {
  const body = { remark: String(remark ?? '') };
  if (!MOCK) return req('PUT', `/api/friends/${encodeURIComponent(friendUserId)}/remark`, body);
  await delay();
  const m = mockStore();
  const f = m.friends.find(x => x.userId === friendUserId);
  if (!f) throw mockError('not a friend', 403);
  const v = body.remark.trim();
  f.remark = v ? v : null;
  for (const c of m.conversations) if (c.friend && c.friend.userId === friendUserId) c.friend.remark = f.remark;
  return { ok: true };
}

/** POST /api/conversations/{id}/read {lastReadMessageId} → 200 */
export async function markConversationRead(conversationId, lastReadMessageId) {
  if (!MOCK) { await req('POST', `/api/conversations/${encodeURIComponent(conversationId)}/read`, { lastReadMessageId }); return; }
  await delay();
  const conv = mockStore().conversations.find(c => c.conversationId === conversationId);
  if (conv) conv.unreadCount = 0;
}

// ── 群组一期（friendgroups 客户端腿）──────────────────────────────────
// 契约来源（冻结，禁改）：补充卡「服务端契约逐字草案」§5.1（POST
// /api/groups/{id}/messages 的路由/请求体/响应/校验序）+ 主卡案1②接口清单
// （主卡:249）。🔴 UI 直发无 origin 字段（补充卡 §5.4 写权矩阵第一行 + §6.5
// 网关路由草案「请求体只读 body 一个字段」先例）⇒ 本层群函数一律不发 origin，
// 网关/服务端按缺省落 'user'。
// 群 id 与 user id 命名空间不相交（主卡 A-1）⇒ 群函数全部按 conversationId 寻址，
// 不走 sendFriendMessage 的 friendUserId 路径。
// 404 fail-closed（主卡 G-2 :204-206）：旧网关/旧服务端无群路由 ⇒ 404 ⇒
// errKind 'neblinkOff' ⇒ 调用方隐藏群入口（不静默、不降级假入口）。
// 错误面沿用既有分态：404 group_not_found 与「路由缺失」同为 404 —— 本层把
// err.data（req() 已解析 JSON body）原样带给调用方，由调用方按语义码分态。

/** 群会话行归一：群行消费字段 = conversationId / title / unreadCount /
 *  lastMessage / memberCount / myRole（主卡 A-6「群设置 = 群名 + 成员列表 +
 *  三个动作」的最小消费集）。字段缺席一律降级（禁渲染 undefined 字面）。 */
function normalizeGroupRow(row) {
  if (!row || typeof row !== 'object') return null;
  const id = row.conversationId ?? row.id;
  if (id === undefined || id === null || id === '') return null;
  return {
    conversationId: String(id),
    kind: 'group',
    title: typeof row.title === 'string' ? row.title : '',
    lastMessage: row.lastMessage || null,
    unreadCount: Number(row.unreadCount) || 0,
    memberCount: Number(row.memberCount) || 0,
    myRole: row.myRole || row.role || 'member',
  };
}

/** GET /api/groups 响应归一（防御性双形态）：数组 = 纯群列表；信封对象 =
 *  {groups, pendingInvites}（pendingInvites 为加性假设字段，见 impl 报告契约
 *  注记；`invites` 拼写一并容忍）。两形态都归一为同一消费形状。 */
function normalizeGroupsEnvelope(raw) {
  if (Array.isArray(raw)) return { groups: raw, pendingInvites: [] };
  if (raw && typeof raw === 'object') {
    const invites = Array.isArray(raw.pendingInvites) ? raw.pendingInvites
      : Array.isArray(raw.invites) ? raw.invites : [];
    return { groups: Array.isArray(raw.groups) ? raw.groups : [], pendingInvites: invites };
  }
  return { groups: [], pendingInvites: [] };
}

/** 群成员行归一：显示名（H 节口径 = 显示名而非好友备注）缺省链 name →
 *  username → neblinkId → userId；role 缺省 member（admin 字段留置不开放，O⑧）。 */
function normalizeMemberRow(row) {
  if (!row || typeof row !== 'object') return null;
  const uid = row.userId ?? row.user_id;
  if (uid === undefined || uid === null || uid === '') return null;
  return {
    userId: String(uid),
    name: row.name || row.displayName || row.display_name || row.username || row.neblinkId || String(uid),
    avatarUrl: row.avatarUrl || row.avatar || '',
    role: row.role || 'member',
  };
}

/** GET /api/groups → {groups:[群会话行], pendingInvites:[入站群邀请]} */
export async function getGroups() {
  if (!MOCK) return normalizeGroupsEnvelope(await req('GET', '/api/groups'));
  await delay();
  const m = mockStore();
  return normalizeGroupsEnvelope({
    groups: m.groups.map(g => ({ ...g })),
    pendingInvites: m.groupInvites.filter(i => (i.status || 'pending') === 'pending').map(i => ({ ...i })),
  });
}

/** POST /api/groups {title?, memberIds} → 群会话行（归一后；无法归一 ⇒ null，
 *  调用方以 refreshGroups 兜底）。成员上限 50 的权威闸在服务端；调用方只做
 *  UX 预检。 */
export async function createGroup(title, memberIds) {
  const body = { ...(title ? { title } : {}), memberIds: memberIds || [] };
  if (!MOCK) return normalizeGroupRow(await req('POST', '/api/groups', body));
  await delay();
  const m = mockStore();
  const conv = {
    conversationId: 'g-' + (++m._msgSeq),
    kind: 'group',
    title: title || '',
    lastMessage: null,
    unreadCount: 0,
    memberCount: (memberIds || []).length + 1,
    myRole: 'owner',
  };
  m.groups.push({ ...conv });
  m.groupMembers[conv.conversationId] = [
    { userId: m.self.userId, name: m.self.displayName || m.self.username, avatarUrl: '', role: 'owner' },
    ...(memberIds || []).map(uid => {
      const f = m.friends.find(x => x.userId === uid);
      return { userId: uid, name: (f && (f.remark || f.name)) || uid, avatarUrl: (f && f.avatarUrl) || '', role: 'member' };
    }),
  ];
  m.messages[conv.conversationId] = [];
  return { ...conv };
}

/** GET /api/groups/{id}/members → [{userId,name,avatarUrl,role}] */
export async function getGroupMembers(groupId) {
  if (!MOCK) {
    const raw = await req('GET', `/api/groups/${encodeURIComponent(groupId)}/members`);
    return (Array.isArray(raw) ? raw : []).map(normalizeMemberRow).filter(Boolean);
  }
  await delay();
  const m = mockStore();
  return (m.groupMembers[groupId] || []).map(x => ({ ...x }));
}

/** POST /api/groups/{id}/messages {body} → SendMessageResponse 同形
 *  {messageId, conversationId, createdAt, createdAtMs?, existing?}（补充卡 §5.1）。
 *  🔴 body 只有一个字段：UI 面在协议上无 origin（§5.4 矩阵第一行）。 */
export async function sendGroupMessage(groupId, body) {
  if (!MOCK) return req('POST', `/api/groups/${encodeURIComponent(groupId)}/messages`, { body });
  await delay();
  const m = mockStore();
  const conv = m.groups.find(g => g.conversationId === groupId);
  if (!conv) throw mockError('group not found', 404);
  const msg = { id: 'm-' + (++m._msgSeq), senderId: m.self.userId, kind: 'text', body, createdAt: new Date().toISOString() };
  (m.messages[groupId] = m.messages[groupId] || []).push(msg);
  conv.lastMessage = msg;
  return { messageId: msg.id, conversationId: groupId, createdAt: msg.createdAt };
}

/** POST /api/groups/{id}/invites {inviteeId} → {}（A-4：被邀请人 accept 后才入群）。 */
export async function inviteToGroup(groupId, inviteeId) {
  if (!MOCK) return req('POST', `/api/groups/${encodeURIComponent(groupId)}/invites`, { inviteeId });
  await delay();
  const m = mockStore();
  m.groupInvites.push({ inviteId: 'gi-' + (++m._reqSeq), conversationId: groupId, inviterId: m.self.userId, inviteeId, status: 'pending' });
  return {};
}

/** POST /api/groups/{id}/invites/{inviteId}/accept|decline → {} */
export async function respondGroupInvite(groupId, inviteId, accept) {
  const action = accept ? 'accept' : 'decline';
  if (!MOCK) return req('POST', `/api/groups/${encodeURIComponent(groupId)}/invites/${encodeURIComponent(inviteId)}/${action}`);
  await delay();
  const m = mockStore();
  const inv = m.groupInvites.find(i => i.inviteId === inviteId);
  if (inv) {
    inv.status = accept ? 'accepted' : 'declined';
    if (accept) {
      const g = m.groups.find(x => x.conversationId === inv.conversationId);
      if (g) {
        g.memberCount = (g.memberCount || 1) + 1;
        const me = { userId: m.self.userId, name: m.self.displayName || m.self.username, avatarUrl: '', role: 'member' };
        (m.groupMembers[inv.conversationId] = m.groupMembers[inv.conversationId] || []).push(me);
      }
    }
  }
  return {};
}

/** POST /api/groups/{id}/leave → {}（owner 禁退群：服务端闸；客户端只做入口隐藏）。 */
export async function leaveGroup(groupId) {
  if (!MOCK) return req('POST', `/api/groups/${encodeURIComponent(groupId)}/leave`);
  await delay();
  const m = mockStore();
  m.groups = m.groups.filter(g => g.conversationId !== groupId);
  delete m.groupMembers[groupId];
  delete m.messages[groupId];
  return {};
}

/** POST /api/groups/{id}/members/{userId}/kick → {} */
export async function kickGroupMember(groupId, userId) {
  if (!MOCK) return req('POST', `/api/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}/kick`);
  await delay();
  const m = mockStore();
  const list = m.groupMembers[groupId] || [];
  const idx = list.findIndex(x => x.userId === userId);
  if (idx >= 0) list.splice(idx, 1);
  const g = m.groups.find(x => x.conversationId === groupId);
  if (g) g.memberCount = Math.max(1, (g.memberCount || 1) - 1);
  return {};
}

/** PUT /api/groups/{id}/title {title} → {} */
export async function renameGroup(groupId, title) {
  if (!MOCK) return req('PUT', `/api/groups/${encodeURIComponent(groupId)}/title`, { title });
  await delay();
  const m = mockStore();
  const g = m.groups.find(x => x.conversationId === groupId);
  if (g) g.title = title;
  return {};
}

/** DELETE /api/groups/{id} → {}（owner 解散 = 软标记 group_disbanded，主卡 A-5）。 */
export async function dissolveGroup(groupId) {
  if (!MOCK) return req('DELETE', `/api/groups/${encodeURIComponent(groupId)}`);
  await delay();
  const m = mockStore();
  m.groups = m.groups.filter(g => g.conversationId !== groupId);
  delete m.groupMembers[groupId];
  delete m.messages[groupId];
  return {};
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
