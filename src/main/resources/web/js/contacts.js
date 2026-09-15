// contacts.js — Contacts panel (friends-messaging-spec §3.1).
// Search by Username or email (submit-style, no incremental search; 含 @ 判
// 邮箱、否则 Username——双识别语义上移服务端，前端单框单 q)，「新的朋友」
// request inbox (incoming + outgoing), friend list. Badges: pending incoming
// count on #contacts-btn (pure-badge model, [U3]).
import { t } from './i18n.js';
import { createIconsIn, escapeHtml } from './utils.js';
import { getNeblinkState, presenceBadgeHTML, onNeblinkStatus, platformDisplay } from './neblink.js';
import { setActivityBadge, openLoginModal } from './activityBar.js';
import { onMessage } from './ws.js';
import * as api from './friendsApi.js';
// 设备会话统一批 MVP-1（2026-09-15）：设备段入本面板。数据源/开窗入口都从
// messages.js 取（设备会话的唯一属主面），本面板只做**行渲染**（禁第二份取数）。
import { openChatWithFriend, fmtTime, isFriendTrusted, setFriendTrusted, devicePeers, deviceLabel, openDeviceChat } from './messages.js';
// 群组一期（friendgroups 客户端腿）：建群对话框 + 群取数/可用性 = friendGroups.js
// 唯一属主；本面板只挂入口（fail-closed，主卡 G-2）与入站群邀请区。
import { openCreateGroupDialog, refreshGroups, groupsAvailable, groupErrToast } from './friendGroups.js';
import { showPopupMenu } from './contextMenu.js';
// ⑨-6（作者 2026-09-12 预授权令）：本模块两个裸键（已看请求 / 拉黑镜像）迁入
// `key()` 品牌命名空间；存量值经 branding.js 的 LEGACY_IRREGULAR 启动即迁移。
import { key } from './branding.js';
// ⑥ 信任好友封存（作者裁定 2026-09-12）：静态常量，非配置读取、不过 latch。
import { TRUST_SEALED } from './featureFlags.js';
// ⑤ 中文输入收归（作者裁定 2026-09-12）：组字判定唯一来源 = imeGuard.js。
// 本模块三个面：搜索框 / 验证附言 / ⑦ 备注行内编辑器（新增面必须同批接入，
// 否则就是第 16 个分叉点）。
import { bindImeGuard, isImeComposing } from './imeGuard.js';

let friends = [];
let incoming = [];
let outgoing = [];
let groupInvites = [];      // 入站群邀请（契约端点 GET /api/groups/invites → {incoming}；归一后
                            // [{inviteId,groupId,title,inviter:{userId,neblinkId,name,avatarUrl},createdAt}]）
let requestsExpanded = false;
// ②（作者 2026-09-15）：「Device 最好是想 New Friends 一样放在点进展开的里面」
// —— 设备入口与「新的朋友」**同构**：一行入口 + chevron 状态 + 展开体按态入 DOM。
// 🔴 复用 `.fm-nf-entry` 家族的类名与样式（friends.css），禁新造折叠式样。
let devicesExpanded = false;
let lastSearchAt = 0;
let searchResult = null;   // null | {found:false} | 契约搜索结果（归一内部形态，含 relation_status）

// ── 批 B 根因修复（增补①，2026-09-14「根因反转」裁定）──────────────────
// **关系态事件到达时必须重置搜索卡片缓存**。
//
// 修的是什么（真凶，不是服务端）：跨仓评估已否证「服务端 pending 残留」（`friendships`
// `UNIQUE(user_lo,user_hi)`；accept = 同行原地 UPDATE、同临界区；8 条写路径零残留）。
// 而「等待对方处理」**卡住**的机制在客户端：`buildResultCard` 读的是**缓存快照**
// `searchResult.relation_status`（下一行起的 `const rs = r.relation_status || 'addable'`），
// 而关系态事件只 `refresh()` 列表、**从不失效这个快照** ⇒ 卡片上的 `outgoing_pending`
// 是 **sticky** 的：对方同意之后本端列表已经对了（`friends[]` 命中、`outgoing[]` 清空），
// 搜索卡片却永远停在「等待对方处理」，直到用户手动再搜一次（重搜才会拿到服务端的
// `already_friends`）。⇒ 手动刷新能恢复、自动路径不能，正是本症状的指纹。
//
// 修法（跨仓建议的**首选 B**）：**失效**（置 `null`，让下一次渲染走服务端真态），
// 🔴 **不是**就地改 `relation_status` —— 就地写 `'addable'` 会退回 0908 症状①
// （关系态靠本地猜测 ⇒ 与「服务端是唯一权威」口径冲突）；就地写 `'already_friends'`
// 则是本地断言关系成立 ⇒ 违反红线③（收敛只依据服务端 `friends` 命中）。
const RELATION_STATE_EVENTS = new Set(['friend_request', 'friend_accepted', 'friend_rejected']);

/** 失效搜索卡片缓存（**唯一**写点；两个事件入口都调它，禁第二处判据）。 */
function invalidateSearchCard(reason) {
  if (!searchResult) return;
  searchResult = null;
  console.debug(`[contacts] search card cache invalidated (reason=${reason})`);
}
let verifyFor = null;      // username awaiting verification-note input
let sentTo = new Set();    // 本会话已发出请求的乐观回显键集（服务端态 = relation_status）；
                           // 键 = username 小写 或 `id:<userId>` 双键（userId 不受服务端档案形态影响）
let searching = false;     // search in flight → button loading state
let searchQ = '';          // preserved across re-renders (panel rebuilds on state change)
let searchErrorKind = null; // null | 'auth' | 'neblinkOff' | 'retryable'（api.errKind 分态；≠「未找到」；0908 作者令按 err.status 拆分三态）
let listErrorKind = null;   // null | 'auth' | 'neblinkOff' | 'retryable'（好友列表加载失败分态，F4 20260910：
                            // 「列表失败」≠「空列表」——失败且无缓存数据时以错误态替代空态文案；
                            // 有缓存数据仍 keep-last-known 不打扰）
                            // R4 20260911：同一分态亦由「新的朋友」请求区消费（buildRequests——
                            // 失败≠「暂无好友请求」，同规则：仅有缓存数据时 keep-last-known）

// ── 红点语义（0904 批次，微信常识）：未看过的请求才亮。展开「新的朋友」
// 即视为已看（与查看后即清的微信口径一致），新 friend_event 再亮；同意/
// 拒绝后条目离开 pending，自然熄灭。seen 集合持久化 localStorage。
const LS_SEEN_REQ = key('fm_seen_requests');
function loadSeenRequests() {
  try { return new Set(JSON.parse(localStorage.getItem(LS_SEEN_REQ) || '[]')); } catch { return new Set(); }
}
function saveSeenRequests(set) {
  try { localStorage.setItem(LS_SEEN_REQ, JSON.stringify([...set])); } catch { /* non-critical */ }
}
function unseenIncomingCount() {
  const seen = loadSeenRequests();
  // F3：与 F1 同一缺省约定——网关 incoming 行亦无 status（FriendRequestSummary
  // 无该字段），严格 === 'pending' 使直连模式红点恒 0；缺失视为 pending。
  return incoming.filter(r => (r.status === undefined || r.status === 'pending') && !seen.has(r.requestId)).length;
}

// #290 §1.2 WeChat-style blacklist: blocked friends stay in the list, greyed.
// The server (neblink-server list_friendships) currently excludes blocked
// rows from GET /api/friends - so the blocked set is mirrored client-side
// (localStorage) and merged back into the list on refresh. When the server
// starts returning blocked rows (with a blocked flag), the server data wins
// and the cache entry is dropped.
const LS_BLOCKED = key('fm_blocked');
function loadBlockedCache() {
  try { return JSON.parse(localStorage.getItem(LS_BLOCKED) || '[]'); } catch { return []; }
}
function saveBlockedCache(list) {
  try { localStorage.setItem(LS_BLOCKED, JSON.stringify(list)); } catch { /* non-critical */ }
}

function loggedIn() { return !!getNeblinkState().loggedIn; }

export function getFriendList() { return friends; }

// ── Data ─────────────────────────────────────────────────
//
// 批 B（§3.1③ + 事件风暴）：取数**单飞 + 合并**。
// 为什么需要：修前 `refresh()` 是裸 `GET /api/friends` —— 一次 `friend_request` 会
// 同时命中两个消费点（`messages.js` 派发的 `fm-friends-changed` 与 `contacts.js`
// 自己的 `onMessage('friend_event')`）⇒ **同拍两次并发同请求**；多帧连到时按帧数线性
// 放大（取证稿 §3.1「事件风暴 ⇒ 需沿用现有节流」）。单飞后：同拍最多一次在飞请求；
// 在飞期间到达的调用只**置一个重跑位**，请求落地后**恰好**再跑一次（不是每帧一次）。
//
// 正确性不依赖节流：`refresh()` 每次落地都整体覆盖 `friends/incoming/outgoing`
// （服务端是权威），故合并只减少请求数、不改变终态。
let refreshInFlight = null;
let refreshAgain = false;
/** 惰性标记（§3.1③）：关系态事件在**面板未挂载 / 未激活**期间到达且那一拍取数
  * **失败**时置位 ⇒ 面板激活时强制再取一次。为什么需要：这是「面板未打开时打开即见」
  * 的唯一缺口 —— 事件→取数→失败时，面板打开那一刻看到的是**事件前**的态，而修前的
  * 激活腿与失败腿之间没有任何粘合剂。取数成功时不置位（已是最新态，无须重复往返）。 */
let relationStateDirty = false;

/** 惰性标记的清点动作（唯一消费点 = 面板激活腿）。 */
function consumeRelationStateDirty() {
  const was = relationStateDirty;
  relationStateDirty = false;
  return was;
}

async function refresh() {
  if (refreshInFlight) { refreshAgain = true; return refreshInFlight; }
  refreshInFlight = (async () => {
    try { await doRefresh(); } finally { refreshInFlight = null; }
  })();
  await refreshInFlight;
  if (refreshAgain) { refreshAgain = false; await refresh(); }
}

async function doRefresh() {
  if (!loggedIn()) { friends = []; incoming = []; outgoing = []; groupInvites = []; listErrorKind = null; render(); return; }
  try {
    // 批 B（§3.4）：出口唯一 —— `getFriends()` 已按「服务端 `friends[]` 命中」收敛
    // 双向待处理（`friendsApi.convergeRequests`）⇒ 本模块**不再**自行过滤（禁第二份判据）。
    const data = await api.getFriends();
    listErrorKind = null; // F4: a successful load clears any previous failure state
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
    // F1（症状②主修复）：网关出参 OutgoingRequestSummary 无 status 字段
    // （NeblinkModel.scala:336-340 deriveEncoder），严格 === 'pending' 恒空 →
    // 「等待对方处理」一次 refresh 即被 wipe。缺省约定：status 缺失视为 pending
    // （对齐 requestRow 的 rq.status || 'pending' 既有写法）；wire 将来带
    // status 时仍尊重。双键索引：username 键依赖服务端回填，userId 键兜底。
    sentTo = new Set();
    for (const r of outgoing) {
      if (r.status !== undefined && r.status !== 'pending') continue;
      if (r.to?.neblinkId) sentTo.add(String(r.to.neblinkId).toLowerCase());
      if (r.to?.userId) sentTo.add(`id:${r.to.userId}`);
    }
    // 群邀请（入站 pending）：与 messages 面同源取数口（refreshGroups，禁第二
    // 份归一）；null = keep-last-known（与好友列表失败同口径，不清空）。
    // pendingInvites 现由契约端点 GET /api/groups/invites 供数（行键 groupId），
    // 已是归一后的入站邀请数组。
    const grp = await refreshGroups();
    if (grp) groupInvites = grp.pendingInvites || [];
  } catch (err) {
    // F4（20260910）：失败≠空。记录分态供 render 区分「空列表」与「加载失败」；
    // 已有缓存数据时 keep-last-known 行为不变（不闪错误态）。
    listErrorKind = api.errKind(err);
    // §3.1③ 惰性标记：**这一拍没取到**且面板未激活 ⇒ 置位，面板激活时强制重取。
    if (!panelActive()) relationStateDirty = true;
  }
  render();
  updateBadge();
}

/** 面板是否**已挂载且处于激活态**（`render()` 的 DOM 前提 + 事件可见性前提）。 */
function panelActive() {
  const panel = document.getElementById('panel-contacts');
  return !!(panel && panel.classList.contains('active'));
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
      for (const r of incoming) if (r.status === undefined || r.status === 'pending') seen.add(r.requestId);
      saveSeenRequests(seen);
    }
    render();
  };
  nf.addEventListener('click', toggleReq);
  nf.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleReq(); } });
  body.appendChild(nf);

  if (requestsExpanded) body.appendChild(buildRequests());

  // ── 群组一期（friendgroups 客户端腿）──
  // 入站群邀请（pending 态；「邀请需对方确认」= 主卡 A-4/O② 的被邀请侧确认面）。
  if (groupInvites.length > 0) body.appendChild(buildGroupInvites());
  // 发起群聊入口（fail-closed：群路由 404 ⇒ groupsAvailable()=false ⇒ 隐藏，
  // 主卡 G-2「404 ⇒ 隐藏群入口，不降级、不静默」——翻面时 friendGroups 已 toast）。
  if (groupsAvailable()) body.appendChild(buildCreateGroupEntry());

  // Friend list
  const list = el('div', 'fm-friend-list');
  list.setAttribute('role', 'listbox');
  list.setAttribute('aria-label', t('panel.contacts'));
  if (friends.length === 0 && listErrorKind) {
    // F4（20260910）：加载失败且无缓存数据 → 错误态替代空态（复用搜索卡
    // 分态 keys：auth/neblinkOff 与搜索共用文案；retryable 走列表专属 key）。
    const key = listErrorKind === 'auth' ? 'contacts.searchAuthError'
      : listErrorKind === 'neblinkOff' ? 'contacts.neblinkOff'
      : 'contacts.listError';
    const errWrap = el('div', 'fm-empty');
    errWrap.setAttribute('data-fm-list-error', listErrorKind); // 断言契约（与视觉文案解耦）
    errWrap.appendChild(el('div', null, t(key)));
    const retry = el('button', 'glass-control', t('contacts.retry'));
    retry.style.marginTop = '8px';
    retry.addEventListener('click', () => { listErrorKind = null; refresh(); });
    errWrap.appendChild(retry);
    list.appendChild(errWrap);
  } else if (friends.length === 0) {
    list.appendChild(el('div', 'fm-empty', t('contacts.empty')));
  } else {
    for (const f of friends) list.appendChild(friendRow(f));
  }
  body.appendChild(list);
  // ── 设备入口（②，作者 2026-09-15）─────────────────────────────────────
  // 作者原话：「然后 Device 最好是想 New Friends 一样放在点进展开的里面」。
  // 形态 = **复用**「新的朋友」的折叠结构（`.fm-nf-entry` 入口行 + chevron 态切换
  // + 展开体按态入 DOM），插入点仍在好友列表之后（仅改形态，不改面板分区顺序）。
  // 语义：本账号**自有设备**（非好友关系域）。
  body.appendChild(buildDevicesEntry());
  if (devicesExpanded) body.appendChild(buildDeviceRows());
  createIconsIn(body);
}

/** 设备入口行（②）：与「新的朋友」**同族形态**（复刻 contacts.js `:249-271` 的
 *  结构逐件：`.fm-nf-entry` 容器 + `.fm-nf-icon` 图标槽 + `.fm-nf-label` 标签 +
 *  `.fm-row-badge` 计数 + `.fm-nf-chevron` 态图标；开关态变量 `devicesExpanded`）。
 *  🔴 复用面（零新增折叠样式）：类名全部取自既有 `.fm-nf-*` 家族，CSS 吃
 *  `friends.css:443-475`（`.fm-nf-entry` / `:hover` / `:focus-visible` / `.fm-nf-icon` /
 *  `.fm-nf-label` / `.fm-nf-chevron`）；计数徽章吃好友行既有 `.fm-row-badge`。 */
function buildDevicesEntry() {
  const devs = devicePeers();
  const entry = el('div', 'fm-nf-entry fm-devices-entry');
  entry.setAttribute('role', 'button');
  entry.setAttribute('tabindex', '0');
  entry.setAttribute('aria-expanded', String(devicesExpanded));
  entry.dataset.devicesEntry = '1'; // QA 断言面：设备入口可机械定位（②）
  const icon = el('span', 'fm-nf-icon');
  icon.innerHTML = '<i data-lucide="smartphone"></i>';
  entry.appendChild(icon);
  entry.appendChild(el('span', 'fm-nf-label', t('contacts.sectionDevices')));
  if (devs.length > 0) entry.appendChild(el('span', 'fm-row-badge', String(devs.length)));
  const chevron = el('span', 'fm-nf-chevron', '');
  chevron.innerHTML = `<i data-lucide="${devicesExpanded ? 'chevron-down' : 'chevron-right'}"></i>`;
  entry.appendChild(chevron);
  const toggle = () => { devicesExpanded = !devicesExpanded; render(); };
  entry.addEventListener('click', toggle);
  entry.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
  });
  return entry;
}

/** 设备展开体（行列表；空态可见，禁静默空段）。
 *  🔴 只在 `devicesExpanded` 为真时入 DOM（与 `requestsExpanded` 同口径）⇒
 *  `[data-device-section="1"]` 在收起态**不存在**（②的可机械判据）。
 *  🔴 去重：`devicePeers()` 按 `deviceId` 去重（卡 O12/P9，上游 peers 可能重行）。 */
function buildDeviceRows() {
  const wrap = el('div', 'fm-device-list');
  // QA 断言面（与群/会话行的 dataset 契约同族）：设备段可机械定位 + 行数可对账。
  wrap.dataset.deviceSection = '1';
  const devs = devicePeers();
  wrap.dataset.deviceCount = String(devs.length);
  if (devs.length === 0) {
    wrap.appendChild(el('div', 'fm-empty', t('contacts.devicesEmpty')));
    return wrap;
  }
  for (const d of devs) wrap.appendChild(deviceRow(d));
  return wrap;
}

/** 设备行（③，作者 2026-09-15：「Device 的样式沿用目前的设置里的样式呀」）。
 *
 *  🔴 **复用清单**（逐件，禁另造一套；本函数**零新增样式家族**）：
 *   · 结构 = 设置账号段原设备行模板（`neblink.js:395-409`，逐件：图标槽 → 名 → 在线
 *     徽章 → 状态文本）——该模板随 ① 摘除后**唯一存续处即本行**（无第二份副本）；
 *   · 类名 = `.neblink-peer` / `.neblink-peer-offline` / `.neblink-peer-icon` /
 *     `.neblink-peer-name` / `.neblink-peer-status`（`neblink.css:91/104/117/127/144-145`）；
 *   · 平台图标 = `platformDisplay(platform).icon`（`neblink.js:456-469` 单点，禁第二份映射）；
 *   · 在线徽章 = `presenceBadgeHTML`（`neblink.js:292-301` 唯一实现，判据/文案零复制）；
 *   · 显示名 = `deviceLabel`（`messages.js:2510-2513` 单点：描述 > 设备名 > id）。
 *  交互 = 点击开设备会话窗（`openDeviceChat` → `renderChatModal`，与好友窗同一渲染器）。
 *  ⚠ 与设置页旧行的**唯一**差异 = 无「远程更新」键组（随 ④ 摘除，见报告被移除项）。 */
function deviceRow(d) {
  const displayName = escapeHtml(deviceLabel(d));
  const icon = platformDisplay(d.platform).icon;
  const statusText = escapeHtml(platformDisplay(d.platform).text || '');
  const row = el('div', 'neblink-peer fm-device-row'
    + (d.online === true ? '' : ' neblink-peer-offline'));
  row.setAttribute('role', 'option');
  row.setAttribute('tabindex', '0');
  row.setAttribute('aria-selected', 'false');
  row.dataset.deviceId = String(d.deviceId || '');
  // 元素顺序逐字照抄设置设备行模板（icon → name → presence → status）。
  row.innerHTML = `<span class="neblink-peer-icon">${icon}</span>`
    + `<span class="neblink-peer-name">${displayName}</span>`
    + presenceBadgeHTML({ ...d, isLocal: false })
    + `<span class="neblink-peer-status">${statusText}</span>`;
  const open = () => openDeviceChat(d);
  row.addEventListener('click', open);
  row.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); open(); }
  });
  return row;
}

function friendRow(f) {
  const blocked = !!f.blocked;
  const row = el('div', 'fm-row fm-friend-row' + (blocked ? ' fm-blocked' : ''));
  row.setAttribute('role', 'option');
  row.setAttribute('tabindex', '0');
  row.setAttribute('aria-selected', 'false');
  row.appendChild(avatarEl(f, 36));
  const meta = el('div', 'fm-row-meta');
  // ⑦ 显示优先级：备注 > 显示名（第三字段 username/NL 号不变，仍在次行）。
  meta.appendChild(el('div', 'fm-row-name', f.remark || f.name || f.neblinkId));
  meta.appendChild(el('div', 'fm-row-sub', f.neblinkId));
  row.appendChild(meta);
  if (blocked) row.appendChild(el('span', 'fm-status-text fm-blocked-tag', t('contacts.blocked')));
  const open = () => openChatWithFriend(f);
  // ⑦ 行内编辑器在场时行激活让位：编辑器里的 click / Enter 会**冒泡**到本行，
  // 若不挡就会一边编辑一边开会话窗（Enter 提交后直接开窗）。既有同款先例 =
  // sidebar.js 会话行 `e.target.closest('.session-name[contenteditable="true"]')` 让位。
  const inInlineEditor = (e) => !!(e.target instanceof Element && e.target.closest('.fm-remark-edit'));
  row.addEventListener('click', (e) => { if (inInlineEditor(e)) return; open(); });
  row.addEventListener('keydown', (e) => {
    if (inInlineEditor(e)) return; // 编辑器的 Enter/Esc 自管，不触发行激活
    if (e.key === 'Enter') { e.preventDefault(); open(); }
  });
  // #290 §1.1/§1.2: WeChat-style row context menu (delete / block / unblock).
  // 信任模式 v1: 非拉黑好友多出「信任此好友/取消信任」（纯本地标记；拉黑态
  // 不显示——黑名单优先于信任，trusted 标记对 blocked 行无意义）。
  // ⑦ 备注（作者裁定 2026-09-12）：入口**仅此一处** = 右键菜单「设置备注」
  // → 行内编辑（⑦-D2 否决玻璃弹窗 / 否决会话窗头第二入口）。
  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const items = [];
    items.push({ label: t('contacts.menuSetRemark'), onClick: () => startRemarkEdit(f, row) });
    // SEALED (author ruling 2026-09-12): 信任好友入口隐藏——`TRUST_SEALED`
    // 为 true（默认）时菜单项不推入；整块代码保留原样，回退 = featureFlags.js
    // 把常量改回 false（回退步骤见该文件注释）。
    if (!blocked && !TRUST_SEALED) {
      const trusted = isFriendTrusted(f.userId);
      items.push({
        label: trusted ? t('contacts.menuUntrust') : t('contacts.menuTrust'),
        onClick: () => setFriendTrusted(f.userId, !trusted),
      });
    }
    items.push(
      { label: t('contacts.menuDelete'), danger: true, onClick: () => confirmDeleteFriend(f) },
      blocked
        ? { label: t('contacts.menuUnblock'), onClick: () => unblockFriend(f) }
        : { label: t('contacts.menuBlock'), danger: true, onClick: () => confirmBlockFriend(f) },
    );
    showPopupMenu(e.clientX, e.clientY, items);
  });
  return row;
}

// ── ⑦ 好友备注：行内编辑（新增面；⑤ 同批接入 imeGuard）─────────────
// 形态对齐既有行内编辑惯例（sidebar.js 的 .folder-new-row、explorer.js 的
// .explorer-name-input、modal.js 的 startInlineNewSession）：就在行内把名字那
// 一行换成输入框，Enter 提交 / Esc 取消 / blur 取消；trim 空串 = 清除备注；
// 长度 ≤64（maxlength + 提交前 trim）。禁第二入口、禁玻璃弹窗（⑦-D2）。
const REMARK_MAX = 64;

function startRemarkEdit(f, row) {
  if (!row || row.querySelector('.fm-remark-edit')) return;
  const nameEl = row.querySelector('.fm-row-name');
  if (!nameEl || !nameEl.parentNode) return;

  const input = document.createElement('input');
  input.className = 'fm-remark-edit';
  input.type = 'text';
  input.maxLength = REMARK_MAX;
  input.value = f.remark || '';
  input.placeholder = t('contacts.remarkPlaceholder');
  input.title = t('contacts.remarkHint');
  input.setAttribute('aria-label', t('contacts.menuSetRemark'));
  input.autocomplete = 'off';
  bindImeGuard(input);

  let done = false;
  const restore = () => {
    if (done) return;
    done = true;
    input.remove();
    nameEl.hidden = false;
  };
  const commit = () => {
    if (done) return;
    // trim + 长度上限：maxlength 只管键盘输入，程序化赋值不受其约束 ⇒ 提交边界
    // 再夹一次（冻结契约 §4「长度 ≤64（trim 后）」在 PUT 出口处必然成立）。
    const next = input.value.trim().slice(0, REMARK_MAX);
    restore(); // 先还原行（PUT 失败时列表照旧可读），再落库
    const prev = f.remark || '';
    if (next === prev) return; // 无变化：零请求
    persistRemark(f, next);
  };
  const cancel = () => restore();

  input.addEventListener('keydown', (e) => {
    if (isImeComposing(e, input)) return; // 组字期间所有键交还输入法（⑤）
    if (e.key === 'Enter') { e.preventDefault(); commit(); return; }
    if (e.key === 'Escape') { e.preventDefault(); cancel(); }
  });
  input.addEventListener('blur', cancel);

  nameEl.hidden = true;
  nameEl.parentNode.insertBefore(input, nameEl);
  input.focus();
  input.setSelectionRange(input.value.length, input.value.length);
}

async function persistRemark(f, remark) {
  try {
    await api.setFriendRemark(f.userId, remark);
    f.remark = remark || null;
    // 三处显示面同步：本行 render() 就地重画；会话列表行 / 聊天窗头由
    // messages.js 订阅该事件就地更新（③ 处显示优先级）。
    render();
    window.dispatchEvent(new CustomEvent('fm-remark-changed', { detail: { userId: f.userId, remark: f.remark } }));
  } catch (err) {
    friendErrToast(err);
  }
}

// ── Delete / block flows (#290 §1.1/§1.2, WeChat-style confirm) ─────────
function confirmDeleteFriend(f) {
  const name = f.name || f.neblinkId || '';
  const run = async () => {
    try {
      await api.removeFriend(f.userId);
      friends = friends.filter(x => x.userId !== f.userId);
      saveBlockedCache(loadBlockedCache().filter(b => b.userId !== f.userId));
      setFriendTrusted(f.userId, false); // 删除好友连同本地信任标记一起清
      render();
      window.dispatchEvent(new CustomEvent('fm-friends-changed'));
    } catch (err) { friendErrToast(err); }
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
        cache.push({ userId: f.userId, neblinkId: f.neblinkId || '', name: f.name || '', avatarUrl: f.avatarUrl || '', remark: f.remark || null });
        saveBlockedCache(cache);
      }
      const row = friends.find(x => x.userId === f.userId);
      if (row) row.blocked = true;
      render();
      window.dispatchEvent(new CustomEvent('fm-friends-changed'));
    } catch (err) { friendErrToast(err); }
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
  } catch (err) { friendErrToast(err); }
}

// ── Search (submit-style, 1s min interval) ───────────────
// 0906 布局修复（作者反馈「结果在同一行，把其他内容挤到一边」）：搜索块
// .fm-search-block 为列布局——输入行 .fm-search 只承载 input+按钮，结果区
// （命中卡/未找到/加载/失败）作为其下方的独立整宽块，永不做行内 flex 子项。
function buildSearch() {
  const block = el('div', 'fm-search-block');
  const row = el('div', 'fm-search');
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
    searchErrorKind = null;
    render(); // button → loading state (input value survives via searchQ)
    try {
      searchResult = await api.searchUser(searchQ.trim());
      searchErrorKind = null;
    } catch (err) {
      // 失败分态（09-06 作者令拆分「未找到」；0908 作者令再按 err.status 三分）：
      // 401/403→登录失效卡+重登按钮（fm-auth-required 全局链保留，卡片为兜底
      // 可见反馈）；404→「Neblink 未启用」（≠「未找到」，found:false 恒 200）；
      // 5xx/422/429/网络/窗口期→可重试卡。
      searchResult = null;
      searchErrorKind = api.errKind(err);
    }
    searching = false;
    render();
  };
  btn.addEventListener('click', submit);
  // ⑤ A4：搜索框组字 Enter 不得提交（此前零判定）。
  bindImeGuard(input);
  input.addEventListener('keydown', (e) => {
    if (isImeComposing(e, input)) return;
    if (e.key === 'Enter') { e.preventDefault(); submit(); }
  });
  if (searching) {
    // ④-P5：加载态**不换文案**（label 保持「搜索/Search」），只走禁用材质 +
    // 区域三点脉冲 + aria-busy/aria-label 无障碍语义。旧实现切「搜索中…」会把
    // 按钮从 50px 撑到 74px（en 64.03→92.05），180px 侧栏下把输入框压到 60.73px。
    btn.disabled = true;
    btn.setAttribute('aria-busy', 'true');
    btn.setAttribute('aria-label', t('contacts.searching'));
  }
  block.appendChild(row);
  row.appendChild(input);
  row.appendChild(btn);
  if (searching) {
    block.appendChild(buildSearching());
  } else if (searchErrorKind) {
    block.appendChild(buildSearchError());
  } else if (searchResult) {
    block.appendChild(buildResultCard());
  }
  return block;
}

// 加载态：区域级三点脉冲（+按钮「搜索中…」双反馈；禁静默空白）
function buildSearching() {
  const card = el('div', 'fm-result-card fm-searching');
  card.setAttribute('role', 'status');
  card.setAttribute('aria-label', t('contacts.searching'));
  card.appendChild(el('i'));
  card.appendChild(el('i'));
  card.appendChild(el('i'));
  return card;
}

// 请求失败态（0908 作者令三分态）：明确反馈，不冒充「未找到」。
// auth → 登录失效卡 + 重登按钮（→openLoginModal；全局 fm-auth-required 链
// 保留，卡片为兜底可见反馈）；neblinkOff → 「Neblink 未启用」；retryable →
// 可重试文案（网络错另有 fm-network-error 全局 toast，并存不冲突）。
function buildSearchError() {
  const card = el('div', 'fm-result-card fm-search-error');
  card.setAttribute('role', 'alert');
  if (searchErrorKind === 'auth') {
    card.appendChild(el('div', 'fm-empty', t('contacts.searchAuthError')));
    const foot = el('div', 'fm-result-foot');
    const btn = el('button', 'glass-control fm-login-btn', t('contacts.relogin'));
    btn.addEventListener('click', () => openLoginModal());
    foot.appendChild(btn);
    card.appendChild(foot);
  } else if (searchErrorKind === 'neblinkOff') {
    card.appendChild(el('div', 'fm-empty', t('contacts.neblinkOff')));
  } else {
    card.appendChild(el('div', 'fm-empty', t('contacts.searchError')));
  }
  return card;
}

// 好友操作失败 toast 分态（0908 作者令：delete/block/unblock 同族折叠拆分）：
// auth→登录引导；404→Neblink 未启用；其余 HTTP 错→networkError 现文案。
// 网络错（err 无 status）已由 fm-network-error 全局 toast 覆盖，本地跳过
// 避免双提示。
function friendErrToast(err) {
  if (!err || err.status === undefined) return;
  const kind = api.errKind(err);
  const key = kind === 'auth' ? 'contacts.searchAuthError'
    : kind === 'neblinkOff' ? 'contacts.neblinkOff'
    : 'messages.networkError';
  window.__showToast?.(t(key), 'error');
}

function buildResultCard() {
  const card = el('div', 'fm-result-card');
  const r = searchResult;
  if (!r.found) {
    // 空结果态：主文案 + 常识提示（对方可能未设置用户名 / 输入有误）
    card.appendChild(el('div', 'fm-empty', t('contacts.notFound')));
    card.appendChild(el('div', 'fm-empty-hint', t('contacts.notFoundHint')));
    return card;
  }
  // 契约 v1.0 搜索结果（friendsApi 归一后内部扁平形态）：username 可空、
  // displayName 永不空（服务端 fallback 链镜像）、relation_status 六态。
  // 分层结构（微信式）：头像 / 显示名 / @Username。
  const person = el('div', 'fm-result-person');
  person.appendChild(avatarEl({ avatarUrl: r.avatar, name: r.displayName }, 40));
  const meta = el('div', 'fm-row-meta');
  meta.appendChild(el('div', 'fm-row-name', r.displayName || r.username));
  if (r.username) {
    // email 形态（旧 seed 回退）不加 @ 前缀；Username 契约形态加 @
    meta.appendChild(el('div', 'fm-row-sub', r.username.includes('@') ? r.username : `@${r.username}`));
  }
  person.appendChild(meta);
  card.appendChild(person);

  // ── relation_status 六态 → 状态文案 + 动作区（契约 §4.1 逐态映射；值缺失按
  // addable 兜底，窗口期/载荷残缺时不渲染成死卡）。可加性判断以服务端
  // relation_status 为唯一事实源（本端不再做好友/在途推断）；唯一保留的本地
  // 乐观态是本次会话刚发出的请求（sentTo），服务端列表刷新前给出即时反馈。
  const rs = r.relation_status || 'addable';
  const username = r.username || '';
  const foot = el('div', 'fm-result-foot');

  if (rs === 'self') {
    foot.appendChild(el('span', 'fm-status-text', t('contacts.self')));
    card.appendChild(foot);
    return card;
  }

  if (rs === 'already_friends') {
    // 已好友 → 状态「已是好友」+「发消息」（复用好友行同款 openChatWithFriend 链路）
    foot.appendChild(el('span', 'fm-status-text', t('contacts.alreadyFriends')));
    const msgBtn = el('button', 'glass-control fm-msg-btn', t('contacts.sendMessage'));
    msgBtn.addEventListener('click', () => {
      const fr = friends.find(f => f.userId === r.userId)
        || { userId: r.userId, neblinkId: username, name: r.displayName, avatarUrl: r.avatar };
      openChatWithFriend(fr);
    });
    foot.appendChild(msgBtn);
    card.appendChild(foot);
    return card;
  }

  if (rs === 'outgoing_pending' || (rs === 'addable' && (sentTo.has(username.toLowerCase()) || (r.userId && sentTo.has(`id:${r.userId}`))))) {
    // 我方出站待处理 → 「等待对方处理」（sentTo 为乐观回显，非服务端态）
    foot.appendChild(el('span', 'fm-status-text', t('contacts.outgoingPending')));
    card.appendChild(foot);
    return card;
  }

  if (rs === 'incoming_pending') {
    // 对方入站待我处理 → 「回应请求」accept/decline（requestId 从已加载的
    // 请求列表取；列表落后时后台 refresh，按钮态随重渲染回归）
    const rq = incoming.find(x => x.from?.userId === r.userId && (x.status || 'pending') === 'pending');
    if (!rq) { refresh(); return card; }
    foot.appendChild(el('span', 'fm-status-text', t('contacts.respondRequest')));
    const accept = el('button', 'glass-control fm-req-accept', t('contacts.accept'));
    accept.addEventListener('click', async () => {
      accept.disabled = true;
      try { await api.acceptFriendRequest(rq.requestId); } catch { /* keep */ }
      searchResult = null;
      await refresh();
      window.dispatchEvent(new CustomEvent('fm-friends-changed'));
    });
    const decline = el('button', 'fm-req-decline', t('contacts.decline'));
    decline.addEventListener('click', async () => {
      decline.disabled = true;
      try { await api.declineFriendRequest(rq.requestId); } catch { /* keep */ }
      searchResult = null;
      await refresh();
    });
    foot.appendChild(accept);
    foot.appendChild(decline);
    card.appendChild(foot);
    return card;
  }

  if (rs === 'blocked_by_me') {
    // 我拉黑对方（调用者私有信息可安全显示）→ 禁用添加 + 「取消拉黑」入口
    foot.appendChild(el('span', 'fm-status-text fm-blocked-tag', t('contacts.blocked')));
    const ub = el('button', 'glass-control fm-unblock-btn', t('contacts.unblock'));
    ub.addEventListener('click', async () => {
      ub.disabled = true;
      try {
        await api.unblockFriend(r.userId);
        saveBlockedCache(loadBlockedCache().filter(b => b.userId !== r.userId));
      } catch (err) { friendErrToast(err); }
      searchResult = null;
      await refresh();
      window.dispatchEvent(new CustomEvent('fm-friends-changed'));
    });
    foot.appendChild(ub);
    card.appendChild(foot);
    return card;
  }

  // addable（默认兜底）：无关系/可发起（含被对方拉黑，不可区分 §5.4）——
  // 微信式验证消息流（≤50 字，可选，Enter 直发）
  if (verifyFor === username) {
    const box = el('div', 'fm-verify-box');
    const input = document.createElement('input');
    input.className = 'fm-verify-input';
    input.maxLength = 50;
    input.placeholder = t('contacts.verifyMessagePlaceholder');
    // ① 发送键（作者 2026-09-14 主诉）：自带类名 `fm-verify-send`——旧形态只带
    // 裸 `glass-control`，样式只能靠结构选择器 `.fm-verify-box button.glass-control
    // :not(.fm-verify-cancel)` 命中（sapphire.css），断言也无从定位本键本体。
    const sendBtn = el('button', 'glass-control fm-verify-send', t('messages.send'));
    // ① 可提交性谓词（唯一来源）：按钮 enabled 态与 Enter 路径**共用**它，
    // 杜绝「按钮禁用而 Enter 照发」的双写分叉。
    // 唯一真「不能提交」态 = 在飞（sending）；附言为**可选**（i18n 逐字
    // 「发送验证消息（可选）」/『Add a message (optional)』、friendsApi.js:263-266
    // `note?`）⇒ 空附言是可提交的合法输入，不得按「空=禁用」处理（那会删掉
    // 无附言发请求这条既有活路径）。username 缺失（契约允许 username 可空）时
    // 请求无法成立 ⇒ 一并 fail-closed 为禁用（不再「看着能点、点了发不出去」）。
    let sending = false;
    const canSend = () => !sending && !!username;
    const syncSendState = () => {
      sendBtn.disabled = !canSend();
      if (sending) sendBtn.setAttribute('aria-busy', 'true');
      else sendBtn.removeAttribute('aria-busy');
    };
    const doSend = async () => {
      if (!canSend()) return; // click / Enter 双路同闸（旧形态 Enter 无判定 ⇒ 在飞中再按 Enter 会重复 POST）
      sending = true;
      syncSendState();
      try {
        await api.sendFriendRequest(username, input.value.trim());
        sentTo.add(username.toLowerCase());
        if (searchResult?.userId) sentTo.add(`id:${searchResult.userId}`);
        // F2：就地翻转搜索卡 relation_status——此后任何 refresh/重渲染都不再
        // 依赖 sentTo 或搜索时刻的陈旧 rs，卡片稳定停「等待对方处理」。
        if (searchResult?.found) searchResult.relation_status = 'outgoing_pending';
      } catch (err) {
        if (err && err.status === 409) {
          // 重复申请（neblink-server friends.rs:399-404 语义：同对 pending 已
          // 存在）——请求确实在服务端在途，按「已申请」处理：就地翻转卡片态，
          // 绝不回退「加好友」可点态（0908 分发器对焦①b；旧统一 catch 吞掉
          // 409 是「验证中弹回加好友」根因之一）。
          sentTo.add(username.toLowerCase());
          if (searchResult?.userId) sentTo.add(`id:${searchResult.userId}`);
          if (searchResult?.found) searchResult.relation_status = 'outgoing_pending';
          window.__showToast?.(t('contacts.alreadyRequested'), 'info');
        } else {
          friendErrToast(err); // F4：HTTP 类失败分态 toast（网络错由全局 fm-network-error 覆盖，friendErrToast 内已跳过）
        }
      }
      sending = false;
      verifyFor = null;
      render();
    };
    sendBtn.addEventListener('click', doSend);
    // Enter 直发 + 取消回退（微信常识：附言后点发送；不想发可退出）
    // ⑤ A4：验证附言组字 Enter 不得直发（此前零判定）。
    // ① 2026-09-14：Enter 与 click 同闸——`doSend()` 自带 `canSend()` 判定
    // （本行不再写第二份判据，避免「按钮禁用、Enter 照发」的旧分叉复活）。
    bindImeGuard(input);
    input.addEventListener('keydown', (e) => {
      if (isImeComposing(e, input)) return;
      if (e.key === 'Enter') { e.preventDefault(); doSend(); }
    });
    const cancelBtn = el('button', 'glass-control fm-verify-cancel', t('contacts.cancelVerify'));
    cancelBtn.addEventListener('click', () => { verifyFor = null; render(); });
    box.appendChild(input);
    box.appendChild(sendBtn);
    box.appendChild(cancelBtn);
    card.appendChild(box);
    setTimeout(() => input.focus(), 0);
    return card;
  }
  const addBtn = el('button', 'glass-control fm-add-btn', t('contacts.addFriend'));
  addBtn.addEventListener('click', () => { verifyFor = username; render(); });
  foot.appendChild(addBtn);
  card.appendChild(foot);
  return card;
}

// ── Requests (「新的朋友」) ───────────────────────────────
function buildRequests() {
  const wrap = el('div', 'fm-requests');
  if (incoming.length === 0 && outgoing.length === 0) {
    if (listErrorKind) {
      // R4（20260911）：请求区此前无错误分支——加载失败时 incoming/outgoing
      // 保持 []，于是「失败」被折叠成「暂无好友请求」（用户以为真的没有请求）。
      // 与列表区同规则：仅在无缓存数据（in/out 皆空）时以错误态替代空态；
      // 有缓存数据仍 keep-last-known 不打扰。分态与列表区同源（api.errKind）：
      // auth → 登录失效 + 重新登录；neblinkOff → Neblink 未启用；
      // 其余（retryable）→ 请求区专属文案 contacts.requestsError + 重试。
      const errWrap = el('div', 'fm-empty');
      errWrap.setAttribute('data-fm-req-error', listErrorKind); // 断言契约（与视觉文案解耦，镜像 data-fm-list-error）
      if (listErrorKind === 'auth') {
        errWrap.appendChild(el('div', null, t('contacts.searchAuthError')));
        const relogin = el('button', 'glass-control fm-login-btn', t('contacts.relogin'));
        relogin.addEventListener('click', () => openLoginModal());
        errWrap.appendChild(relogin);
      } else if (listErrorKind === 'neblinkOff') {
        errWrap.appendChild(el('div', null, t('contacts.neblinkOff')));
      } else {
        errWrap.appendChild(el('div', null, t('contacts.requestsError')));
        const retry = el('button', 'glass-control', t('contacts.retry'));
        retry.style.marginTop = '8px';
        retry.addEventListener('click', () => { listErrorKind = null; refresh(); });
        errWrap.appendChild(retry);
      }
      wrap.appendChild(errWrap);
      return wrap;
    }
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
    row.appendChild(el('span', 'fm-status-text', t('contacts.outgoingPending')));
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

// ── 群组一期：发起群聊入口 + 群邀请区（建群/邀请契约 = 主卡案1② + A-4）──
function buildCreateGroupEntry() {
  const entry = el('div', 'fm-nf-entry');
  entry.setAttribute('role', 'button');
  entry.setAttribute('tabindex', '0');
  const icon = el('span', 'fm-nf-icon');
  icon.innerHTML = '<i data-lucide="users"></i>';
  entry.appendChild(icon);
  entry.appendChild(el('span', 'fm-nf-label', t('contacts.createGroup')));
  const chev = el('span', 'fm-nf-chevron', '');
  chev.innerHTML = '<i data-lucide="chevron-right"></i>';
  entry.appendChild(chev);
  const open = () => openCreateGroupDialog();
  entry.addEventListener('click', open);
  entry.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } });
  return entry;
}

/** 入站群邀请行（accept/decline = POST /api/groups/{id}/invites/{inviteId}/accept|decline，
 *  主卡:249 接口清单逐字）。行内消费字段 = **契约为准**（GroupInviteEntry，
 *  model.rs:872-880 经 friendsApi.normalizeInviteRow 归一）：群行键 `groupId`、
 *  群名 `title`、邀请人 `inviter`（平铺 FriendPublic → 内部 {userId,neblinkId,name,
 *  avatarUrl}）。旧的多键猜测读法（groupName/conversationId/inviterId）已删——
 *  承载件里没有这些键，猜读只会把 undefined 渲染成 "undefined"。 */
function buildGroupInvites() {
  const wrap = el('div', 'fm-requests fm-group-invites');
  wrap.appendChild(el('div', 'fm-req-group', t('contacts.groupInvites')));
  for (const inv of groupInvites) {
    const row = el('div', 'fm-row fm-req-row fm-invite-row');
    const groupName = inv.title || '';
    const inviterName = (inv.inviter && (inv.inviter.name || inv.inviter.neblinkId || inv.inviter.userId)) || '';
    row.appendChild(avatarEl({ name: String(groupName) }, 36));
    const meta = el('div', 'fm-row-meta');
    meta.appendChild(el('div', 'fm-row-name', String(groupName)));
    meta.appendChild(el('div', 'fm-row-sub', t('contacts.groupInviteFrom', { name: String(inviterName) })));
    row.appendChild(meta);
    const btns = el('span', 'fm-req-btns');
    const accept = el('button', 'glass-control fm-req-accept', t('contacts.accept'));
    accept.addEventListener('click', async (e) => {
      e.stopPropagation();
      accept.disabled = true;
      try {
        await api.respondGroupInvite(String(inv.groupId), String(inv.inviteId), true);
        groupInvites = groupInvites.filter(x => x !== inv);
        render();
        window.dispatchEvent(new CustomEvent('fm-groups-changed', {
          detail: { openConversationId: String(inv.groupId) },
        }));
      } catch (err) {
        accept.disabled = false;
        groupErrToast(err);
      }
    });
    const decline = el('button', 'fm-req-decline', t('contacts.decline'));
    decline.addEventListener('click', async (e) => {
      e.stopPropagation();
      decline.disabled = true;
      try {
        await api.respondGroupInvite(String(inv.groupId), String(inv.inviteId), false);
        groupInvites = groupInvites.filter(x => x !== inv);
        render();
      } catch (err) {
        decline.disabled = false;
        groupErrToast(err);
      }
    });
    btns.appendChild(accept);
    btns.appendChild(decline);
    row.appendChild(btns);
    wrap.appendChild(row);
  }
  return wrap;
}

// ── Wiring ───────────────────────────────────────────────
let initialized = false;

export function initContacts() {
  if (initialized) return;
  initialized = true;

  onMessage('friend_event', (msg) => {
    if (RELATION_STATE_EVENTS.has(msg.event)) {
      // 批 B 根因修复（增补①）：关系态事件到达 ⇒ **重置搜索卡片缓存** + 重取列表。
      invalidateSearchCard(msg.event);
      refresh();
    }
  });
  // P3 error surface — friendsApi dispatches on auth failure / network error.
  window.addEventListener('fm-auth-required', () => { openLoginModal(); });
  window.addEventListener('fm-network-error', () => { window.__showToast?.(t('messages.networkError'), 'error'); });
  // `fm-friends-changed` 由**他模块**派发（`messages.js` 的 friend_accepted/friend_request
  // 分支、以及本模块自身 accept/unblock 之后）⇒ 同一条关系态判据必须同样生效，否则
  // 「消息面板收到帧」这条路径会绕过缓存失效（禁两份判据：缓存失效只在
  // `invalidateSearchCard` 一处，两个入口都调它）。
  window.addEventListener('fm-friends-changed', () => {
    invalidateSearchCard('fm-friends-changed');
    refresh();
  });

  // Refresh when the panel becomes active (covers login-state changes + §3.1③ 惰性标记)。
  const panel = document.getElementById('panel-contacts');
  if (panel) {
    new MutationObserver(() => {
      if (panel.classList.contains('active')) {
        // 惰性标记清点（§3.1③）：事件期间取数失败过 ⇒ 打开即见的保证不能只靠
        // 「上一次那拍成功」，此处强制重取一次（`refresh()` 恒走 `GET /api/friends`）。
        if (consumeRelationStateDirty()) {
          console.debug('[contacts] panel activated while relation-state refresh had failed — re-fetching');
        }
        refresh();
      }
    }).observe(panel, { attributes: true, attributeFilter: ['class'] });
  }

  // 设备段在线态 = **推送驱动**（卡 O10）：唯一推送源 = neblink.js 的
  // `onNeblinkStatus`（`/api/neblink/status` 落地拍，WS `peerListChanged` 已在其上游
  // 汇流）。本面板**不引入任何轮询**（修前的 3s 轮询属于设置面板账号段，已解绑）。
  // 面板不在场 ⇒ 不渲染（下次激活由既有 MutationObserver 触发 refresh/render）。
  onNeblinkStatus(() => { if (panelActive()) render(); });

  // ── ⑦（作者 2026-09-15）：搜索结果提示小面板必须能收起 ────────────────
  // 作者原话：「搜索用户之后出现的 User not found / The user may not have set a
  // Username yet, or the input may be wrong 小面板，还是不会自动收起。」
  //
  // 口径 = **全站悬浮面板统一惯例**：点面板外即收起（本处「面板」= 搜索块
  // `.fm-search-block`，卡片 `.fm-result-card` 是其子项）。逐字对齐既有先例
  // `ctxthresh.js:483-493`（用 `mousedown` 而非 `click`：拖动时松手点可能落在面板外，
  // click 会把「拖完松手」误判成「点外面」；外加 Escape —— 同族面板同款成对口径）。
  // 🔴 **不引入定时器**：全站面板族无超时惯例（超时只属 toast 族 —— `modal.js:221-224` /
  // `utils.js:880-885`）⇒ 本面板加超时就是自创双标。
  // 🔴 搜索输入框/搜索键都在 `.fm-search-block` 内 ⇒ 「点搜索框即收起」被 contains 判据挡掉。
  // 🔴 历史考证（forensic §6）：本面**从未有过**收起实现（全历史 0 命中）⇒ 首次修复。
  const dismissSearchCard = (reason) => {
    if (!searchResult && !searchErrorKind) return;
    searchResult = null;
    searchErrorKind = null;
    console.debug(`[contacts] search card dismissed (reason=${reason})`);
    render();
  };
  document.addEventListener('mousedown', (e) => {
    if (!searchResult && !searchErrorKind) return;
    const block = document.querySelector('.fm-search-block');
    if (block && e.target instanceof Node && block.contains(e.target)) return;
    dismissSearchCard('outside-mousedown');
  });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    // 行内编辑器（备注 / 验证附言）自己有 Esc 语义 ⇒ 让它们先处理（同一键不得双消费）。
    if (document.querySelector('.fm-remark-edit, .fm-verify-input')) return;
    dismissSearchCard('escape');
  });

  render();
  if (loggedIn()) refresh();
}

/** Used by messages.js for the not-friend gate (§3.3/R8). Blocked rows stay
 *  in the list (WeChat-style) but are not messageable - same gate as deleted. */
export function isFriendUser(userId) {
  return friends.some(f => f.userId === userId && !f.blocked);
}
