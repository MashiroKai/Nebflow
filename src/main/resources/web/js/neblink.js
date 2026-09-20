/**
 * NebLink — P2P device discovery via NebLink Server.
 * Device pairing: nebflow.space login → auto-configure NebLink.
 */
import state from './state.js';
import { key } from './branding.js';
// ⑨ 消息缓存生命周期挂靠点（作者 2026-09-12 口径：v1 介质 = localStorage 持久
// 落盘；登出清缓存）。方向是单向的：fmMessageCache 只依赖 branding.js，
// 本模块依赖它 —— 无环（scripts/check-circular.mjs 守）。
import { setCacheAccount, clearMessageCache } from './fmMessageCache.js';
// ⑩ Dropbox 消息缓存（fmDropboxCache.js）：与好友消息缓存**同一登出链、同一时机**。
// 分区键仍只有一份来源（`fmMessageCache.getCacheAccount()`），此处只补整槽清除。
import { clearDeviceMessageCache } from './fmDropboxCache.js';
// sessperf Phase B（2026-09-20）：本地优先层（IndexedDB，唯一属主 `localStore.js`）
// 的**生命周期两个挂点**都在这条既有链上：① 状态拍落位账号分区（`openLocalStore`，
// 与 `setCacheAccount` 同拍、同一分区键）；② 登出/换账号（`clear('all')`）。
import { openLocalStore, clear as clearLocalStore } from './localStore.js';
import { escapeHtml } from './utils.js';
import { t, getLocale } from './i18n.js';
import { onMessage, sendWs } from './ws.js';
import { brand } from './brand.js';
import { lookupAvatar, rememberAvatarProfile, lastKnownAvatarProfile, forgetAvatarProfile } from './avatarCache.js';
import { getKnownAccounts, rememberAccount } from './knownAccounts.js';
// NOTE: dropbox.js is dynamically imported at the click site - P2-4 cycle cut
// (neblink <-> dropbox mutual import).

/** Transient success banner shown after NebLink pairing completes. */
function showLoginSuccessBanner(message) {
  const banner = document.createElement('div');
  banner.textContent = message;
  banner.style.cssText = 'position:fixed;top:50px;left:50%;transform:translateX(-50%);background:var(--color-surface,rgba(20,25,35,0.92));color:var(--color-text);padding:8px 16px;border-radius:8px;z-index:1000;font-size:13px;box-shadow:0 2px 12px rgba(0,0,0,0.15);border:1px solid var(--glass-border);transition:opacity 0.3s;';
  document.body.appendChild(banner);
  setTimeout(() => { banner.style.opacity = '0'; }, 2700);
  setTimeout(() => banner.remove(), 3000);
}

let neblinkState = {
  device: null,
  peers: [],
  paired: false,
  pairing: false,
  pairError: '',
  enrollMsg: '',
  // User login state: true when a NebLink device credential exists and
  // NebLink is enabled (reported by the backend).
  loggedIn: false,
  // Device-flow state: 'idle' | 'waiting' | 'success'
  flowState: 'idle',
  userCode: '',
  deviceCode: '',
  // ①opt-A3（方案 §2.1）：relay 隧道状态进 state，供「降级增量回补」判据消费
  // （`relay.available === true` 才算通道可用）。形状 = /api/neblink/status 的
  // `relay` 对象原文 {available, authRejected, lastRejectedStatusCode,
  // lastRejectedAt, selfHeal}（NeblinkRelayTunnel.statusJson）；未取到 = null。
  relay: /** @type {{available?: boolean, authRejected?: boolean, lastRejectedStatusCode?: number|null, lastRejectedAt?: number|null, selfHeal?: string}|null} */ (null),
  // 缺陷 A（加法字段）：失败分类码（`/auth/state` 的 `code`）；'' = 无分类。
  pairErrorCode: '',
  // 缺陷 A（加法字段）：`/status` 的 `credentialIssue` 原文（{code, reason, action, error}）；
  // null = 本机凭据面读干净（老网关缺该键也走这里 ⇒ 降级安全）。
  credentialIssue: /** @type {{code?: string, reason?: string, action?: string, error?: string}|null} */ (null),
  // 缺陷 A（判据 G6）：`/status` 非 2xx 的降级读数（{status}）；null = 状态面健康。
  // 修前该端点 500 被 `if (!resp.ok) return;` 静默吞掉 ⇒ 假「已登录」中间态 + 零痕迹。
  statusDegraded: /** @type {{status: number}|null} */ (null)
};

// ── 凭据面失败分类（缺陷 A）──────────────────────────────────────────────
// 与后端 `nebflow.neblink.CredentialFailure` 的 code **逐字同源**（镜像漂移由
// `CredentialDiagnosticsSpec` 的断言钉住）。只有**本地凭据文件类**故障才给「清理并重登」
// 入口 —— 网络/服务端类故障点了也没用（§8.2 第 6 项的门控判据）。
export const LOCAL_FILE_CODES = [
  'credential-missing',
  'credential-unreadable',
  'credential-undecodable',
  'credential-write-denied',
  'credential-acl-not-applied',
  'credential-delete-denied'
];

/** 失败面文案的**唯一前端组装点**（§8.3 三段式）。
 *  文案来源优先级：i18n（`login.reason.<code>` / `login.action.<code>` —— UI 文案的
 *  唯一来源，zh/en 成对）⇒ 后端 `reason`/`action`（老网关 / 未知码）⇒ 原始 `error`。
 *  `code` 恒为后端给的稳定枚举，直接进「诊断码：」尾串（可让用户报给我们 grep 日志）。 */
export function loginFailureText(payload = {}) {
  const code = payload.code || '';
  const rk = code ? `login.reason.${code}` : '';
  const ak = code ? `login.action.${code}` : '';
  const tr = rk ? t(rk) : '';
  const ta = ak ? t(ak) : '';
  const reason = (tr && tr !== rk) ? tr : (payload.reason || payload.error || t('login.failed'));
  const action = (ta && ta !== ak) ? ta : (payload.action || '');
  const body = action ? t('login.failureLine', { reason, action }) : reason;
  return code ? `${body} ${t('login.diagnosticCode', { code })}` : body;
}

// ── NL 号（Username）入口说明 ────────────────────────────
// 2026-09-05 10:54 裁定：NL 号 = 官网 Username，客户端不提供修改入口——
// 原 [U3] NL 号自定义 UI（查看/修改/available 实时检测，走 PUT
// /api/users/me/neblink-id）已整体移除；Username 统一经官网账号中心设置，
// 旧 neblink-id 端点同 release 退役（friend-search-contract §4.7）。
// contacts 列表/搜索卡片的 username 展示保持（friendsApi 契约归一）。

// ── 头像双态判定（共享）─────────────────────────────────
// 设置页头像区（sidebar.js renderSettings 的账号区）与 Activity Bar 头像
// （activityBar.js renderAvatar）共用同一套「登录且账号头像可用 → 显示照片，
// 否则显示 logo」判定。逻辑唯一来源在此，两侧只消费视图状态。
// 失败 URL latch：记住加载失败过的头像 URL（如无代理时账号头像不可达），
// 否则轮询刷新会反复显示坏 <img>，onerror 回落 logo 永远粘不住。
let avatarFailedUrl = '';

/** Record an avatar URL that failed to load (called from <img> onerror). */
export function noteAvatarFailure(url) {
  if (url) avatarFailedUrl = url;
}

/** Shared dual-state avatar view: { loggedIn, url, showPhoto }.
 *  Placeholder/fake URLs are filtered the same way for every consumer.
 *  2026-09-05 本地缓存优先（avatarCache.js）：命中即以 dataURL 直出——两个
 *  消费端（Activity Bar + 设置页）零请求即时渲染，消灭「登录了没头像」闪失；
 *  仅当源标记（avatarUrl 本身）变化或 TTL 过期才由缓存层后台回源。渲染路径
 *  从不等待网络。离线兜底：本会话拿不到 status（如启动即离线）时回落缓存里
 *  的 last-known 登录快照，跨刷新不丢头像（登出时 forgetAvatarProfile 清除）。 */
export function avatarViewState() {
  const url = neblinkState.device?.avatarUrl || '';
  const validAvatarUrl = url && url.startsWith('http') && !url.includes('example.com') ? url : '';
  let displayUrl = validAvatarUrl;
  let loggedIn = !!neblinkState.loggedIn;
  if (validAvatarUrl) {
    const cached = lookupAvatar(validAvatarUrl);
    if (cached) displayUrl = cached;
    rememberAvatarProfile(loggedIn, validAvatarUrl); // value-change short-circuited inside
  } else if (!neblinkState.device) {
    // No live status this session (offline boot / gateway restarting) — fall
    // back to the last-known snapshot instead of flashing the logo.
    const known = lastKnownAvatarProfile();
    if (known) { loggedIn = true; displayUrl = known.dataUrl; }
  }
  const showPhoto = loggedIn && !!displayUrl && avatarFailedUrl !== validAvatarUrl;
  return { loggedIn, url: showPhoto ? displayUrl : '', showPhoto };
}

/** Paint the dual-state avatar slot: the photo when it is paintable, the logo
 *  otherwise. Both consumers (Activity Bar + settings account area) go through
 *  this one function, so the slot can never drift between them.
 *
 *  2026-09-15 闪烁修复（作者 12:16 报告①）。修前两个消费端都在**同一个同步任务**
 *  里翻转可见态：`photoEl.hidden = false` → 写 `src` → `logoEl.hidden = true`。
 *  可见态因此先于「照片可绘制」翻转，凡是需要真加载的一遍——冷字节首帧 /
 *  本地缓存未命中回落远端 URL 直拉（avatarCache.js 注释第 4 条的退化分支）/
 *  未命中→命中的 src 换帧 / 加载慢或失败——槽位都会**整个加载窗口空着**。
 *  实测（scripts/e2e-avatar-flicker.cjs）：空环 22~78 帧、持续 243~758 ms。
 *
 *  本函数把「翻到照片」推迟到字节就绪（`decode()` 落地 / 已 complete），
 *  期间由 logo 占着槽位 ⇒ 槽位永不空。**取舍链不变**（data URI → 远端 URL →
 *  logo），样式/尺寸/颜色/布局/语义一律不动——只是翻面的**时刻**改到
 *  「替代内容已就绪」。 */
export function paintAvatarSlot(photoEl, logoEl, url, showPhoto, onError) {
  if (onError) photoEl.onerror = onError;
  if (!showPhoto) {
    photoEl.hidden = true;
    if (logoEl) logoEl.hidden = false;
    return;
  }
  const show = () => { photoEl.hidden = false; if (logoEl) logoEl.hidden = true; };
  if (photoEl.getAttribute('src') !== url) {
    // New source: keep the logo on screen until the new bytes are paintable.
    photoEl.hidden = true;
    if (logoEl) logoEl.hidden = false;
    photoEl.setAttribute('src', url);
  }
  if (photoEl.complete && photoEl.naturalWidth > 0) { show(); return; }
  if (typeof photoEl.decode === 'function') {
    // decode() resolves once the image is decoded (ready to paint); it rejects
    // on a load error, which the onerror path owns (logo stays up).
    photoEl.decode().then(show, () => { /* onerror restores the logo */ });
  } else {
    photoEl.addEventListener('load', show, { once: true });
  }
}

/** Read-only accessor for the current NebLink state (used by the Activity Bar). */
export function getNeblinkState() {
  return neblinkState;
}

// Per-device remote update state: 'idle' | 'select' | 'updating' | 'done' | 'error' | 'timeout'
//
// 🔴 hotupdate 批 3 · G7：本变量是设备更新进度的**唯一**状态面——保留面原文（2026-09-15
// 摘除令注记）为「`deviceUpdateState` 的 WS 结果帧处理与其 i18n 键组**未删**，理由 =
// 远程更新是服务端面既有能力，本次只摘除其客户端入口」。入口恢复发生在**新家**
// （联系人面板设备行，`contacts.js`）⇒ 新家只**读/写**本变量与下述访问器，
// **禁**另建第二套进度状态变量、**禁**另建第二套结果帧处理（结果帧处理仍只有本文件
// `initNeblink()` 里那一处）。
//
// `timeout`（批 3 新增档）= 触发/受理面（裁定 7 的外层 300s）等不到任何结果帧时的
// 收口档：它是**同一条状态机**的追加档，不是第二个变量。
let deviceUpdateState = {};
let _rerender = null;

/** 设备更新状态订阅面（与既有 `onNeblinkStatus`/`statusSubscribers` 同款单点模式）：
 *  联系人面板设备行据此把进度回显刷成最新（结果帧的消费方仍只有本文件一处）。 */
const deviceUpdateSubscribers = new Set();

export function onDeviceUpdateChange(cb) {
  deviceUpdateSubscribers.add(cb);
  return () => deviceUpdateSubscribers.delete(cb);
}

/** 状态面变更的唯一播报点：既有保留面 `_rerender`（设置账号块）+ 新家订阅者。 */
function notifyDeviceUpdateChange() {
  _rerender?.();
  for (const cb of [...deviceUpdateSubscribers]) {
    try { cb(); } catch (e) { console.error('[neblink] device update subscriber failed:', e); }
  }
}

/** 现读某设备的更新状态（键 = `peer.deviceName`，与结果帧内 `device` 字段同口径）。 */
export function getDeviceUpdateState(deviceName) {
  return deviceUpdateState[deviceName] || null;
}

/** 触发面进入「更新中」（写入**同一条**状态机；`clientRequestId` 一并留存，供超时/重试
 *  复用同一枚幂等键——契约 §D.2「用户重试须复用同一个键」）。 */
export function markDeviceUpdating(deviceName, clientRequestId) {
  const prior = deviceUpdateState[deviceName];
  deviceUpdateState[deviceName] = {
    status: 'updating',
    clientRequestId: clientRequestId || prior?.clientRequestId || null,
  };
  notifyDeviceUpdateChange();
}

/** 触发/受理面等不到结果帧（外层 300s 上限）⇒ 同一条状态机的 `timeout` 档
 *  （幂等键沿用 ⇒ 重试仍复用同键）。 */
export function markDeviceUpdateTimeout(deviceName) {
  const cur = deviceUpdateState[deviceName];
  if (!cur || cur.status !== 'updating') return;
  deviceUpdateState[deviceName] = { status: 'timeout', clientRequestId: cur.clientRequestId || null };
  notifyDeviceUpdateChange();
  // 与既有 'done'/'error' 同款 TTL（10 秒后自动清除）：瞬态行状态不留陈旧回显。
  setTimeout(() => {
    if (deviceUpdateState[deviceName]?.status === 'timeout') {
      delete deviceUpdateState[deviceName];
      notifyDeviceUpdateChange();
    }
  }, 10000);
}

/** 重试复用：把该设备**当前这次逻辑请求**的幂等键交回触发点（契约 §D.2/H10
 *  「用户重试须复用同一个键」）。留存范围 = 触发/受理/超时/**错误**四档（错误档的含义
 *  是该次逻辑请求未被送达或未被受理 ⇒ 用户再点仍是「同一次请求」的重试）；
 *  触达 `done`（设备已受理并重启）⇒ 该次逻辑请求已成立，条目按既有 10s TTL 清空，
 *  此后再点即新请求（新键）。条目被 TTL 清除后同样返回 null。 */
export function pendingDeviceUpdateKey(deviceName) {
  const cur = deviceUpdateState[deviceName];
  if (!cur || !cur.clientRequestId) return null;
  if (cur.status === 'done') return null;
  return cur.clientRequestId;
}

export function getAuthToken() {
  return localStorage.getItem(key('token')) || '';
}

// ---- Fetch status ----
export async function fetchNeblinkStatus() {
  try {
    const token = getAuthToken();
    const resp = await fetch('/api/neblink/status', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (!resp.ok) {
      // 🔴 缺陷 A（上游 S4 / 判据 G6）：修前这里是 `if (!resp.ok) return;` —— 状态端点
      // 500 被**完全静默吞掉**（用户看到停在旧值的假「已登录」中间态，控制台也零痕迹，
      // 「坏」持续存在却不可见）。现在：留可见痕迹（降级读数进 state）+ 一条归因 console。
      // 刻意**不**改写 `loggedIn`：拿不到权威读数时不猜，降级提示由渲染面承担。
      neblinkState.statusDegraded = { status: resp.status };
      console.warn('[neblink] status poll failed', { status: resp.status });
      notifyStatusSubscribers();
      return;
    }
    const data = await resp.json();
    neblinkState.statusDegraded = null;
    // 凭据面读数（加法字段 `credentialIssue`；老网关缺该键 ⇒ null ⇒ 不渲染）。
    neblinkState.credentialIssue =
      (data.credentialIssue && typeof data.credentialIssue === 'object') ? data.credentialIssue : null;
    const wasLoggedIn = neblinkState.loggedIn;
    neblinkState.loggedIn = !!data.loggedIn;
    // Normalize local device fields to match peer field names.
    // githubLogin is deliberately NOT mapped: the client is Logto-only now
    // (login-chain unification 2026-09-01) and the field had no consumer in
    // web/ — it only ever carried the legacy GitHub-enroll handle.
    const d = data.device;
    neblinkState.device = d ? {
      deviceId: d.id,
      deviceName: d.name,
      platform: d.platform,
      capabilities: d.capabilities || {},
      userDescription: d.userDescription || '',
      avatarUrl: d.avatarUrl || '',
      // Account identity hints (switch-account, 2026-09-10): decoded
      // server-side from the persisted id_token's email/name claims
      // (read-only). Empty strings when the credential predates the claims
      // or the provider omitted them.
      email: d.email || '',
      displayName: d.displayName || ''
    } : null;
    // Account memory capture (switch-account spec §6): a live logged-in
    // status with an identity is the "login success" anchor — dedup + move
    // to head + cap inside rememberAccount. Stores ONLY email+displayName.
    if (neblinkState.loggedIn && neblinkState.device?.email) {
      rememberAccount({ email: neblinkState.device.email, displayName: neblinkState.device.displayName });
    }
    // F5（症状③防自过滤）：服务端 peers 若回显本机设备，下方 UI 合并
    // [{...local,isLocal}, ...peers] 会出现「本机 + 同名 peer」双行（数量虚增
    // 恰好 +1）。客户端整条链路零 self 防御（LAN announce 路径有、server 路径
    // 无），此处兜底；幽灵注册属服务端数据缺口（S3/S4），不在本修复面。
    const selfId = d ? d.id : null;
    neblinkState.peers = (data.peers || []).filter(p => !selfId || p.deviceId !== selfId);

    // ①opt-A3：relay 隧道状态（本批新增字段，加法语义——老网关缺该字段 ⇒ null，
    // 消费方按「不可用」处理 = 降级兜底方向）。
    neblinkState.relay = (data.relay && typeof data.relay === 'object') ? data.relay : null;

    // ⑨ 消息缓存账号分区（作者 2026-09-12 落盘口径）：登出/凭证失效 ⇒ 清缓存；
    // 分区键 = deviceId|email 复合（任一变化即「另一个账号」，跨账号绝不串数据）。
    if (wasLoggedIn && !neblinkState.loggedIn) { clearMessageCache(); clearDeviceMessageCache(); }
    setCacheAccount(neblinkState.device
      ? `${neblinkState.device.deviceId || ''}|${neblinkState.device.email || ''}`
      : '');
    // sessperf Phase B：同一分区的本地层落位/切换（幂等；账号未知 ⇒ 本层整体关闭）。
    // 🔴 与 `setCacheAccount` **同拍、同键** ⇒ 不存在两套分区状态（卡 §5.4 风险表
    // 「跨账号串数据 = 最高危」的唯一防线）。异步打开 + 预热，绝不阻塞状态拍。
    void openLocalStore(neblinkState.loggedIn && neblinkState.device
      ? `${neblinkState.device.deviceId || ''}|${neblinkState.device.email || ''}`
      : '');
    // 设备会话统一批 MVP-1/O10：状态拍落地 ⇒ 推送订阅面（联系人面板设备段等）。
    // 唯一推送源 = 本函数；WS `peerListChanged`（initNeblink）已在此汇流。
    notifyStatusSubscribers();
  } catch (e) {
    // neblink not available yet
  }
}

/** Re-fetch status and re-render settings panel. */
export async function refreshNeblink() {
  await fetchNeblinkStatus();
  _rerender?.();
}

// ---- Pairing flow ----

// 暂时禁用，邮箱注册功能完成后重新启用
// （已从 main.js 初始化流程中摘除调用，函数代码保留供后期邮箱注册复用）
/** Check URL for pairing redirect from nebflow.space/connect */
export function checkPairingRedirect() {
  const params = new URLSearchParams(window.location.search);
  const server = params.get('server');
  const networkId = params.get('networkId');
  const secret = params.get('secret');
  // Optional account avatar URL, if nebflow.space includes it on the redirect.
  const avatar = params.get('avatar');

  if (server && networkId && secret) {
    // Capture the auth token BEFORE wiping the URL. On the very first load
    // after the nebflow.space redirect, ws.js connect() (which stores the
    // ?token= URL param into localStorage) has NOT run yet, so localStorage is
    // still empty. Read the token from the URL first, fall back to localStorage
    // (subsequent pairing attempts). The backend /api/neblink/pair route is
    // guarded by checkAuth, which only accepts Authorization: Bearer or a
    // ?token= query param — without this header the POST returns Forbidden and
    // login silently fails ("登陆了都没反应").
    const token = params.get('token') || getAuthToken();
    // Clean URL — strip all params (incl. the secret/token) from history.
    const cleanUrl = window.location.origin + window.location.pathname;
    window.history.replaceState({}, document.title, cleanUrl);

    // Send pairing config to backend
    neblinkState.pairing = true;
    _rerender?.();
    const pairBody = { server, networkId, secret };
    if (avatar) pairBody.avatar = avatar;
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;
    fetch('/api/neblink/pair', {
      method: 'POST',
      headers,
      body: JSON.stringify(pairBody)
    }).then(r => r.json()).then(data => {
      if (data.ok) {
        neblinkState.pairing = false;
        neblinkState.paired = true;
        neblinkState.pairError = '';
        showLoginSuccessBanner('登录成功，设备已连接');
        setTimeout(() => fetchNeblinkStatus().then(() => _rerender?.()), 1500);
      } else {
        neblinkState.pairing = false;
        const err = data.error || '配对失败';
        neblinkState.pairError = err === 'Unauthorized'
          ? `认证失败。请从 ${brand.productName} 终端重新打开浏览器页面，然后重试登录。`
          : err;
      }
      _rerender?.();
    }).catch(e => {
      neblinkState.pairing = false;
      neblinkState.pairError = '网络错误: ' + e.message;
      _rerender?.();
    });
    return true;
  }
  return false;
}

// ---- Settings section HTML ----
// ── 设备在线态徽章：**单一实现**（设备会话统一批 MVP-1/O10）──────────────
// 抽取动因（卡 §6.1「在线/离线」行 + O10）：判据与标记原本**内联在
// neblinkSettingsHTML 的模板字符串里**（旧 :312-318），第二处复用（联系人面板
// 设备段）只能复制 ⇒ 两处必然漂移。现在唯一实现在此，两个消费面都只调它：
//   · 联系人面板设备行（contacts.js `deviceRow`，① 后设备行的宿主）
//   · 设备会话窗窗头（messages.js `renderChatModal` 的 presence 槽）
// 数据源唯一 = `/api/neblink/status`（本文件 fetchNeblinkStatus）。本函数**只做形态**，
// 不取数、不缓存 ⇒ 消费面各自决定何时重渲（O10：面板只依赖 WS `peerListChanged` 推送）。
/** @param {{isLocal?: boolean, online?: boolean, directOnline?: boolean, relayAvailable?: boolean}} d */
export function presenceBadgeHTML(d) {
  if (d && d.isLocal) return ''; // 本机行恒在线，不挂徽章（沿既有口径）
  const isOnline = !!(d && d.online === true);
  const reachHint = !d ? ''
    : d.directOnline ? t('neblink.reachDirect')
    : d.relayAvailable ? t('neblink.reachRelay')
    : t('neblink.reachServerOnly');
  return `<span class="neblink-presence ${isOnline ? 'online' : 'offline'}" title="${escapeHtml(reachHint)}">`
    + `${isOnline ? t('neblink.online') : t('neblink.offline')}</span>`;
}

/** 在线态**推送订阅**（O10）：状态拍落地后逐个通知。
 *  约束：回调必须同步、廉价、自吞异常（本函数已 try/catch，仍要求回调不阻塞取数链）。 */
const statusSubscribers = /** @type {Set<(s:any) => void>} */ (new Set());

/** @param {(s:any) => void} cb @returns {() => void} 注销函数 */
export function onNeblinkStatus(cb) {
  statusSubscribers.add(cb);
  return () => statusSubscribers.delete(cb);
}

function notifyStatusSubscribers() {
  for (const cb of [...statusSubscribers]) {
    try { cb(neblinkState); } catch (e) { console.error('[neblink] status subscriber failed:', e); }
  }
}

export function neblinkSettingsHTML() {
  const local = neblinkState.device || {};
  const peers = neblinkState.peers || [];

  // If pairing in progress
  if (neblinkState.pairing) {
    return `<div class="neblink-login-section">
      <div class="neblink-pairing-status">${t('neblink.pairing') || '正在配对...'}</div>
    </div>`;
  }

  // If not logged in — one-line "unavailable" note only (2026-09-06
  // unification: avatar + device link are one settings block). The logo is
  // already rendered by the avatar entry right above (avatarViewState falls
  // back to the product logo when logged out) and owns the login click, so
  // there is intentionally no second logo, no login button and no device
  // section here.
  if (!neblinkState.loggedIn) {
    // 失败面（缺陷 A）：文案已是**三段式**（原因 + 下一步 + 诊断码），由
    // `loginFailureText` 单点组装（i18n 优先，后端分类串兜底）。
    const pairErr = neblinkState.pairError
      ? `<div class="neblink-error">${escapeHtml(neblinkState.pairError)}</div>` : '';
    // 「清理并重登」入口的**门控**：只有本地凭据文件类分类才显示（网络/服务端类故障
    // 点了也没用）。修前本面板在未登录态**没有任何出口**（上游 §5.2）——登出键只在
    // 已登录分支渲染 ⇒ 用户被卡在「登录失败 + 无法清理」。
    const cleanupCode = neblinkState.pairErrorCode || neblinkState.credentialIssue?.code || '';
    const cleanup = LOCAL_FILE_CODES.includes(cleanupCode)
      ? `<div class="neblink-account-actions">
        <button class="neblink-switch-btn" id="neblink-cleanup-btn" type="button">${t('neblink.cleanupRelogin')}</button>
      </div>`
      : '';
    // 状态面降级（判据 G6）：非 2xx 的**可见**读数（修前 `if (!resp.ok) return;` 零痕迹）。
    const degraded = neblinkState.statusDegraded
      ? `<div class="neblink-error">${escapeHtml(t('neblink.statusDegraded', { status: String(neblinkState.statusDegraded.status) }))}</div>`
      : '';
    // 本机凭据面读数（被动来源：坏件在**下一次登录尝试之前**就已经可判读，上游 §4 S4）。
    const issue = (!pairErr && neblinkState.credentialIssue)
      ? `<div class="neblink-error">${escapeHtml(loginFailureText(neblinkState.credentialIssue))}</div>` : '';
    return `<div class="neblink-login-section">
      <div class="neblink-logged-out-hint">${t('neblink.loggedOutHint')}</div>
      ${degraded}
      ${pairErr}
      ${issue}
      ${cleanup}
    </div>`;
  }

  // ── 设备列表**已移出设置**（①④，作者 2026-09-15）───────────────────────
  // 作者原话：「我的意思是把入口从设置，转到联系人面板而已。」「而且我不是说这样
  // 设置里的样式就保留切换账号和登陆就可以了吗」。
  //
  // 🔴 本段（登录态设置账号块）现在只渲染：被踢状态行 + **切换账号 / 退出登录**两键。
  // 设备入口的新家 = 联系人面板的「设备」点进展开入口（`contacts.js`
  // `buildDevicesEntry` / `buildDeviceRows` / `deviceRow`），**样式沿用**本文件原设备行
  // 的 `.neblink-peer` 家族（类名/结构不动）+ `neblink.css:83-145`（样式表零改动）。
  //
  // 🔴 本令**移除项逐条**（全部在本文件内，随 ① 一次摘除）：
  //   1. 设备行模板 `allDevices` + `deviceRows`（本机行 + 远端行 + 「更新」键组）；
  //   2. `.neblink-peers-list` 容器插值 `${deviceRows}`；
  //   3. `peerHint`（无 peer 提示行 `neblink.noPeersHint` —— 设备列表内容，随列表一并摘除）；
  //   4. `.dropbox-clickable` 点击绑定（**旧设备窗入口**；旧窗随 ① 退役）；
  //   5. 「更新 / stable / beta / 取消」三组键绑定（其 DOM 即第 1 项，已不存在）。
  // 🔴 **保留项**：`kickedLine`（2026-09-14 作者令 C+B 的**被动可见状态行**，语义是
  // 「账号会话被踢」不是「设备列表内容」⇒ 不随本令摘除；见下原注释）。
  // 未裁项（呈分发器）：`deviceUpdateState` 的 WS 结果帧处理（本文件 :948-970）与其
  // i18n 键组**未删** —— 远程更新是服务端面既有能力，本次只摘除其客户端入口。
  //
  // NL 号入口已移除（作者 2026-09-05 裁定：NL 号统一 = 官网 Username，官网
  // 已有修改功能，客户端不再重复提供）——原「查看 + 修改 + live 可用性检测」
  // 区块连同 friendsApi 的 setNeblinkId/neblinkIdAvailable 一并删除；旧
  // neblink-id 端点同 release 退役（friend-search-contract §4.7）。

  // 2026-09-10: 「切换账号」joins 「退出登录」in one row (switch-account spec
  // §1 — green glass primary per the confirmed mockup; row layout styles in
  // neblink.css .neblink-account-actions).
  // 踢旧批（2026-09-14，作者 17:07 裁定 C+B·客户端一刀）——**案 B 客户端腿**：
  // 被服务端 `disconnect` 帧踢下线后，本机必须**被动可见**（修前零用户感知）。
  // 形态遵一期口径（User.md:36 / messages.js:991「无横幅无提示音」）：
  //   · 只做**状态行**（下方这一条），无横幅、无 toast、无声音；
  //   · `autoReconnectParked` ⇒ 明确告知「自动重连已暂停，需重新登录」——这是本批
  //     对「须用户显式再登录」的用户侧说明（后端停摆态见 NeblinkRelayTunnel.park）。
  // 取数 = /api/neblink/status 的 `relay.signedOutElsewhere`（本地网关↔浏览器侧加法
  // 字段；服务端 wire 零变化）。老网关缺该字段 ⇒ undefined ⇒ 不渲染（降级安全）。
  const kicked = neblinkState.relay?.signedOutElsewhere === true;
  const kickedLine = kicked
    ? `<div class="neblink-kicked-notice" role="status">
      <span class="neblink-kicked-dot" aria-hidden="true"></span>
      <span class="neblink-kicked-text"><strong>${t('neblink.signedOutElsewhere')}</strong> · ${t('neblink.signedOutElsewhereHint')}</span>
    </div>`
    : '';

  return `
    <div class="neblink-logged-in">
      ${kickedLine}
      <div class="neblink-account-actions">
        <button class="neblink-switch-btn" id="neblink-switch-btn" type="button">${t('neblink.switchAccount')}</button>
        <button class="neblink-logout-btn" id="neblink-logout-btn" type="button">${t('neblink.logout')}</button>
      </div>
    </div>`;
}

/** Map a raw platform string (e.g. "macos", "windows") to a friendly label +
 *  inline SVG icon for the device row. Falls back to a generic device icon. */
export function platformDisplay(platform) {
  const p = (platform || '').toLowerCase();
  const mac = '<svg viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M16.36 12.93c.02 2.3 2.02 3.07 2.04 3.08-.02.05-.32 1.1-1.06 2.18-.64.93-1.3 1.86-2.34 1.88-1.02.02-1.35-.6-2.52-.6-1.17 0-1.53.58-2.5.62-1 .04-1.77-1-2.42-1.93-1.32-1.9-2.33-5.39-.97-7.74.67-1.17 1.88-1.91 3.19-1.93.99-.02 1.92.66 2.52.66.6 0 1.74-.82 2.93-.7.5.02 1.9.2 2.8 1.52-.07.05-1.67.98-1.65 2.92M14.6 5.4c.55-.67.92-1.6.82-2.52-.79.03-1.75.53-2.32 1.2-.51.59-.96 1.53-.84 2.44.88.07 1.79-.45 2.34-1.12"/></svg>';
  const win = '<svg viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M3 5.48 10.4 4.4v7.1H3V5.48m0 13.04V13.4h7.4v7.1L3 18.52M11.4 4.26 21 3v8.5H11.4V4.26m0 15.48V13.4H21V21l-9.6-1.26"/></svg>';
  const linux = '<svg viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M12.5 2c-1.3 0-2 1.1-2 2.4 0 .4.1.8.2 1.1-.5.5-1 1.4-1.4 2.5-.4 1.2-1 2.2-1.5 2.7-.5.4-1 .9-1.3 1.6-.3.7-.4 1.9.3 2.7-.3.5-.6 1.4-.3 2.3.2.7.7 1.2.8 1.7.1.5 0 .9.3 1.3.4.5 1 .5 1.6.3.4.6 1.1.9 1.9.9.9 0 1.6-.4 2-1 .4.2.9.3 1.4.1.8-.3 1.2-1 1.2-1.8 0-.4-.1-.7-.2-1 .3-.4.6-.9.6-1.6 0-.6-.2-1.1-.5-1.5.2-.4.3-.9.1-1.5-.2-.7-.7-1.2-.8-1.7-.1-.5 0-.9-.3-1.3-.4-.5-1-.5-1.6-.3-.4-.6-1.1-.9-1.9-.9-.5 0-.9.1-1.3.3.1-.3.2-.7.2-1.1 0-1.3-.7-2.4-2-2.4"/></svg>';
  const generic = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" width="15" height="15"><rect x="3" y="4" width="18" height="12" rx="1"/><path d="M8 20h8M12 16v4"/></svg>';
  // 🔴 匹配精度修复（设备会话统一批 MVP-1 发现、就地修单点）：`'darwin'.includes('win')`
  // 为**真** ⇒ macOS 设备被标成「Windows」。旧面只消费 `icon`（字形），文本面从未被
  // 显示 ⇒ 缺陷不可见；MVP-1 的设备窗副行/设备行**要显示 .text** ⇒ 必须按平台词精确判。
  if (p.includes('mac') || p.includes('darwin') || p === 'ios') return { icon: mac, text: 'macOS' };
  if (p.includes('win')) return { icon: win, text: 'Windows' };
  if (p.includes('linux') || p === 'android') return { icon: linux, text: 'Linux' };
  return { icon: generic, text: platform || 'Device' };
}

// ---- Device-flow start / polling ----

/**
 * Start a NebLink device-flow authorization.
 * Returns the flow payload {deviceCode, userCode, verificationUri, interval,
 * expiresIn} on success; throws Error(message) otherwise.
 * Shared by the Settings panel button and the avatar login modal.
 */
export async function startDeviceFlow() {
  const resp = await fetch('/api/neblink/device-flow/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getAuthToken() },
  });
  const data = await resp.json();
  if (!resp.ok) throw new Error(data.error || t('login.deviceStartFailed'));
  return data;
}

/**
 * Poll the local gateway's /api/neblink/device-flow/poll until the device is
 * approved (or expired/errored). On success, the Scala backend persists the
 * credential and hot-swaps the client — the UI just needs to refresh state.
 *
 * Optional callbacks let non-Settings callers (avatar login modal) react to
 * the outcome; neblinkState is updated either way.
 */
let _flowPollTimer = null;
export function pollDeviceFlow(deviceCode, interval, expiresInSeconds, onSuccess, onError) {
  // Cancel any existing poll.
  if (_flowPollTimer) clearTimeout(_flowPollTimer);
  const deadline = Date.now() + expiresInSeconds * 1000;

  const fail = (failure) => {
    // 缺陷 A：载荷形态与 PKCE 面一致（对象 ⇒ 走 `loginFailureText`；字符串 ⇒ 兼容面）。
    const payload = (failure && typeof failure === 'object')
      ? failure
      : { error: failure == null ? '' : String(failure) };
    neblinkState.flowState = 'idle';
    neblinkState.pairError = loginFailureText(payload);
    neblinkState.pairErrorCode = payload.code || '';
    neblinkState.userCode = '';
    neblinkState.deviceCode = '';
    if (_rerender) _rerender();
    onError?.(neblinkState.pairError, payload);
  };

  const poll = async () => {
    if (Date.now() > deadline) {
      fail(t('login.deviceTimeout'));
      return;
    }
    try {
      const resp = await fetch('/api/neblink/device-flow/poll', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getAuthToken() },
        body: JSON.stringify({ deviceCode })
      });
      const data = await resp.json();
      if (resp.ok && data.ok) {
        // Success — device enrolled, client hot-swapped.
        neblinkState.flowState = 'success';
        neblinkState.pairError = '';
        neblinkState.userCode = '';
        neblinkState.deviceCode = '';
        if (_rerender) _rerender();
        // Refresh neblink status after a short delay so the new device shows up.
        setTimeout(() => fetchNeblinkStatus(), 1500);
        onSuccess?.();
        return;
      }
      if (data.error === 'authorization_pending') {
        // Keep polling.
        _flowPollTimer = setTimeout(poll, interval * 1000);
        return;
      }
      // Other error (expired, denied, etc.) — 载荷原样下沉（后端分类文案若带
      // `code` 则连诊断码一起渲染，缺 `code` 时与修前逐字同款）。
      fail(data.error ? data : t('login.deviceFailed'));
    } catch (e) {
      fail(t('login.networkError', { msg: e.message }));
    }
  };
  _flowPollTimer = setTimeout(poll, interval * 1000);
}

/** Cancel an in-progress device-flow poll (e.g. the login modal was closed). */
export function cancelDeviceFlow() {
  if (_flowPollTimer) { clearTimeout(_flowPollTimer); _flowPollTimer = null; }
  if (neblinkState.flowState === 'waiting') {
    neblinkState.flowState = 'idle';
    neblinkState.userCode = '';
    neblinkState.deviceCode = '';
    if (_rerender) _rerender();
  }
}

// ---- PKCE login (Authorization Code + PKCE, loopback redirect) ----
// Primary login path when the gateway has Logto configured: the browser does
// the full hosted login and redirects back to 127.0.0.1/auth/callback; this
// side just opens the URL and polls the flow state. When Logto is NOT
// configured the gateway answers 404 logto-not-configured and callers fall
// back to the device flow above.

/**
 * Start a Logto Authorization Code + PKCE login.
 * Returns {authorizeUrl} on success; returns null when the gateway reports
 * 404 logto-not-configured (caller should fall back to startDeviceFlow);
 * throws Error(message) on any other failure.
 *
 * `forceLogin` (RP-logout fix, 2026-09-06): true → the gateway adds
 * prompt="login consent" to the authorize URL, so the hosted page shows
 * the ACCOUNT form even when this browser still holds a Logto SSO
 * session — the switch-account entry. Default false = plain login
 * (fast path, consent only).
 *
 * `uiLocales` (BYUI handoff ①, 2026-09-09): the client UI language is
 * forwarded as the OIDC ui_locales hint (single tag: "zh"/"en") so the
 * hosted sign-in page matches the app language. Harmless pre-BYUI (the
 * stock hosted page uses it to pick its language); the server whitelists
 * the value and omits the param otherwise → navigator.language fallback.
 */
export async function startPkceLogin(forceLogin = false) {
  const resp = await fetch('/api/neblink/auth/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getAuthToken() },
    body: JSON.stringify({ forceLogin: !!forceLogin, uiLocales: getLocale() === 'en' ? 'en' : 'zh' }),
  });
  let data = {};
  try { data = await resp.json(); } catch (_) { data = {}; }
  if (resp.status === 404 && data.error === 'logto-not-configured') return null;
  if (!resp.ok || !data.authorizeUrl) throw new Error(data.error || t('login.startFailed'));
  return data;
}

/**
 * Poll the local gateway's /api/neblink/auth/state until the PKCE login
 * resolves. Statuses: idle | pending (keep polling) | success | error.
 * On success the backend has already registered the device and persisted the
 * credential - the UI just refreshes state.
 */
let _pkcePollTimer = null;
export function pollPkceState(onSuccess, onError, intervalMs = 1200, timeoutMs = 300000) {
  // Cancel any existing poll.
  if (_pkcePollTimer) clearTimeout(_pkcePollTimer);
  const deadline = Date.now() + timeoutMs;

  /**
   * 失败落地面（缺陷 A / 上游 §8.2 第 6 项）：入参可以是**字符串**（本地客户端失败，
   * 兼容面）或后端失败**载荷**（`{code, reason, action, error}`）。
   * 落三个读数：① `pairError` = 渲染用三段式（`loginFailureText`）；② `pairErrorCode`
   * = 稳定分类码（供「清理并重登」门控与 `data-*` 断言）；③ 回调第二参带上原始载荷
   * （老调用方只读第一参 ⇒ 向后兼容）。
   * @param {string|{code?: string, reason?: string, action?: string, error?: string}} failure
   */
  const fail = (failure) => {
    const payload = (failure && typeof failure === 'object')
      ? failure
      : { error: failure == null ? '' : String(failure) };
    const text = loginFailureText(payload);
    neblinkState.flowState = 'idle';
    neblinkState.pairError = text;
    neblinkState.pairErrorCode = payload.code || '';
    neblinkState.credentialIssue = payload.code ? payload : neblinkState.credentialIssue;
    if (_rerender) _rerender();
    onError?.(text, payload);
  };

  const poll = async () => {
    if (Date.now() > deadline) {
      fail(t('login.timeout'));
      return;
    }
    try {
      const resp = await fetch('/api/neblink/auth/state', {
        headers: { 'Authorization': 'Bearer ' + getAuthToken() },
      });
      const data = await resp.json();
      if (!resp.ok) {
        fail(data);
        return;
      }
      if (data.status === 'success') {
        // Success - device registered, credential persisted.
        neblinkState.flowState = 'success';
        neblinkState.pairError = '';
        neblinkState.pairErrorCode = '';
        if (_rerender) _rerender();
        // Refresh neblink status after a short delay so the profile shows up.
        setTimeout(() => fetchNeblinkStatus(), 1500);
        onSuccess?.();
        return;
      }
      if (data.status === 'error') {
        // 后端错误态现在恒带 `code`/`reason`/`action`（加法字段，缺陷 A）⇒ 前端按分类
        // 渲染原因 + 动作 + 诊断码，不再原样打印后端串（修前 `fail(data.error …)` 直透，
        // 上游 §4 的第 5 处丢失点）。
        fail(data);
        return;
      }
      // idle | pending - keep polling (idle is possible right after start).
      _pkcePollTimer = setTimeout(poll, intervalMs);
    } catch (e) {
      fail(t('login.networkError', { msg: e.message }));
    }
  };
  _pkcePollTimer = setTimeout(poll, intervalMs);
}

/** Cancel an in-progress PKCE state poll (e.g. the login modal was closed). */
export function cancelPkceFlow() {
  if (_pkcePollTimer) { clearTimeout(_pkcePollTimer); _pkcePollTimer = null; }
}

/** RP-initiated logout hop — the **single** window-navigation point of the
 *  logout / switch-account / cleanup-and-relogin chains.
 *
 *  `window.open('/api/neblink/auth/end-session…')` in the SAME gesture tick
 *  (synchronous → popup-blocker safe): the endpoint performs the local
 *  teardown (8 steps, defect-A fix: a credential **read** failure no longer
 *  skips it — judgement G5) and 302s the new tab to the provider's
 *  end_session_endpoint, killing the browser SSO session. Without that hop the
 *  next login silently re-enters the original account.
 *
 *  `continueToLogin` = the one-window switch shape (`?scenario=switch`): the
 *  landing page this hop ends on continues into the login in the SAME window;
 *  the main window learns the outcome through [[watchSwitchHandoff]].
 *
 *  🔴 缺陷 A（上游 §8.2 第 6 项）：「清理并重登」入口走的就是本函数（`continueToLogin=true`）
 *  —— 它是「本地凭据坏了、清理掉再登一次」这条自救路径的既有实现，本批零新增链路。
 *
 *  Extracted from the `bindNeblinkEvents` closure so the login modal
 *  (activityBar.js) can share it verbatim instead of keeping a second copy.
 * @param {boolean} continueToLogin */
export function openEndSessionHandoff(continueToLogin = false) {
  const handoff = continueToLogin
    ? `?scenario=switch&ui_locales=${getLocale() === 'en' ? 'en' : 'zh'}`
    : '';
  window.open('/api/neblink/auth/end-session' + handoff, '_blank', 'noopener');
  setTimeout(async () => {
    forgetAvatarProfile(); // drop the last-known snapshot: a logged-out user must not resurrect offline
    // ⑨ 登出清除（作者口径：消息持久落盘，但换账号/登出必须清）——与头像
    // last-known 同一条链、同一时机，不留「登出后本地仍躺着上一位的聊天记录」。
    clearMessageCache();
    clearDeviceMessageCache(); // ⑩ 与好友缓存同轮：登出后不留上一位的 Dropbox 记录
    void clearLocalStore('all'); // sessperf Phase B 本地优先层（IndexedDB）同轮清场
    await fetchNeblinkStatus();
    _rerender?.();
  }, 1000);
}

// ---- Bind events after HTML insert ----
export function bindNeblinkEvents(rerender) {
  _rerender = rerender;

  // Full RP-initiated logout chain — the SINGLE shared implementation used by
  // both the settings logout button and the switch-account modal (spec §4:
  // "原样复用既有 logout 链路"). RP-logout fix, 2026-09-06:
  // window.open('/api/neblink/auth/end-session') in the SAME gesture tick
  // (synchronous → popup-blocker safe): the endpoint performs the local
  // teardown (same 8 steps as the old POST /api/neblink/logout) and 302s
  // the new tab to Logto's end_session_endpoint, killing the provider's
  // browser SSO session. Without that hop, the next login silently
  // re-enters the original account (no account choice — the root cause of
  // the silent-relogin bug). The new tab ends on the provider's
  // logged-out page (or our /auth/logged-out landing once the
  // post_logout_redirect_uri is allow-listed on the Logto app).
  // The main window refreshes its status a beat later, after the local
  // teardown on the backend has landed.
  //
  // `opts.continueToLogin` (one-window switch, 2026-09-16): true marks this
  // logout as the SWITCH-ACCOUNT flow — the gateway arms its single-use handoff
  // marker, and the landing page this window ends on continues into the login
  // IN THIS SAME WINDOW (`?scenario=switch`). A plain logout passes nothing:
  // the landing stays the static 「已退出登录」 card and the gateway explicitly
  // clears any leftover marker (zero behaviour change for plain logout).
  // `ui_locales` is carried through the hop because the landing page is a bare
  // navigation and cannot read this app's locale.
  function initiateLogout(opts = {}) {
    // 🔴 唯一窗口跳转点已抽到 [[openEndSessionHandoff]]（缺陷 A）：「清理并重登」入口
    // 与「退出/换号」共用同一段 ⇒ 两条腿永不漂移（§13 禁造新轮子）。
    openEndSessionHandoff(opts.continueToLogin === true);
  }

  // Logout button.
  const logoutBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('neblink-logout-btn'));
  if (logoutBtn) {
    logoutBtn.addEventListener('click', () => {
      logoutBtn.disabled = true;
      logoutBtn.textContent = t('neblink.loggingOut');
      initiateLogout();
    });
  }

  // Switch-account modal entry (2026-09-10, switch-account spec §1-§6).
  const switchBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('neblink-switch-btn'));
  if (switchBtn) {
    switchBtn.addEventListener('click', () => { openSwitchAccountModal(initiateLogout); });
  }

  // 「清理并重登」入口（缺陷 A / 上游 §8.2 第 6 项）：**复用既有链路**，零新轮子 ——
  // 走的就是「切换账号」那条 `initiateLogout({continueToLogin:true})`（RP end-session →
  // 本地拆除 → 落地页续登）。它之所以能真的「清理」，靠的是本批后端那笔修复：
  // end-session 的凭据读点失败**不再跳过本地拆除**（判据 G5）⇒ 坏件即使读不开，拆除
  // 仍执行（`DeviceCredential.clear` 删不掉时再走改名留档兜底）⇒ 下一次登录从干净盘重建。
  const cleanupBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('neblink-cleanup-btn'));
  if (cleanupBtn) {
    cleanupBtn.addEventListener('click', () => {
      cleanupBtn.disabled = true;
      initiateLogout({ continueToLogin: true });
    });
  }

  // NL 号入口已移除（09-05 裁定）——原 edit/cancel/input/save 绑定随区块删除。

  // 🔴 设置账号段的**设备列表绑定整组已摘除**（①④，作者 2026-09-15）：随设备行模板
  // 一并删除的有 —— 「更新」键、stable/beta 频道键、取消键三组绑定，以及
  // `.dropbox-clickable`（设备名 → **旧设备窗** `openDropbox`）绑定。这些选择器的
  // DOM 来源（`.neblink-peers-list` / `.neblink-peer`）已不存在（见上方本文件
  // `neblinkSettingsHTML` 的移除项清单），留着就是永不命中的死绑定。
  // 设备入口本体见 `contacts.js` 的「设备」点进展开（→ `openDeviceChat`，新设备会话窗）。
}

// ---- Switch-account modal (2026-09-10, spec §2-§5) ----
// Glass panel per the user modal ruling: NO overlay/backdrop darkening (the
// panel floats directly over the UI), the panel itself is frosted glass
// (backdrop-filter blur) — same recipe as the Activity Bar login modal.
// Reuses the account-domain infra: neblink PKCE start (forceLogin), the
// shared initiateLogout chain, knownAccounts.js memory. List rows are built
// with DOM APIs + textContent (emails are untrusted strings — no innerHTML).

/**
 * Switch to a remembered account (spec §4): run the full logout chain
 * (RP end-session + local status refresh), then restart the login flow on
 * the hosted page. The authorize URL is built SERVER-side
 * (/api/neblink/auth/start, prompt="login consent"); this function only
 * navigates to it.
 *
 * ONE-WINDOW SHAPE (2026-09-16 author ruling: "切换账号全程只开一个窗"):
 * the logout hop is a real navigation to the provider (design-required — it
 * must kill the SSO session), and the window it ends on — our
 * `/auth/logged-out` landing page — CONTINUES INTO THE LOGIN THERE, because
 * the gateway marked this logout as a switch (`?scenario=switch`). So this
 * function opens NO window of its own: the old `window.open('about:blank')`
 * login reservation + `startPkceLogin()` + second `window.open` are gone, and
 * with them the second window. Forced fresh login is preserved end to end
 * (the continuation builds its authorize URL through the same server-side
 * login start with `forceLogin=true` ⇒ `prompt="login consent"`).
 *
 * Failure is never silent: [[watchSwitchHandoff]] watches the handoff marker
 * and, when the continuation does not arrive, shows the login panel in this
 * window (no automatic window retry — the panel owns the manual gesture).
 *
 * BYUI prefill slot (forensic verdict 2026-09-10): the auth.nebflow.space
 * BYUI sign-in card does NOT read any URL prefill param yet, and Logto's
 * custom-UI 303 landing does not forward arbitrary authorize query params
 * (production-probed: `first_screen` maps to a path, query is NOT passed
 * through). Per the fork-a ruling the client therefore ships WITHOUT the
 * param; when the BYUI prefill lands (website-side spec: read `login_hint`
 * off the landing URL and prefill the identifier input — one line in
 * auth-ui/src/views/signin.ts), enable prefill here by appending
 * `&login_hint=` + encodeURIComponent(prefillEmail) to `authorizeUrl`
 * before navigating, and pass the account email through from the row click.
 * @param {(opts?: {continueToLogin?: boolean}) => void} initiateLogout — shared RP-logout chain
 * @param {string|null} prefillEmail — account email to prefill (v1: unused)
 */
async function switchLogoutAndLogin(initiateLogout, prefillEmail = null) {
  // The ONE window of the whole switch: its landing page continues into the
  // login (see the doc comment above). Gesture-tick, popup-blocker safe.
  initiateLogout({ continueToLogin: true });
  void prefillEmail; // v1 ships without the param — see BYUI prefill slot note above
  watchSwitchHandoff();
}

/** Poll cadence / deadline of the switch handoff watchdog (2026-09-16).
 *  The continuation is taken over by the landing hop (log out → provider →
 *  landing) i.e. seconds; the deadline is a generous bound for a slow hop,
 *  not a login duration. */
const SWITCH_HANDOFF_POLL_MS = 1200;
const SWITCH_HANDOFF_DEADLINE_MS = 20000;
let _switchHandoffTimer = null;

/** Watch the gateway's single-use switch handoff marker after a switch
 *  gesture (2026-09-16, one-window switch).
 *
 *  - `consumed` → the logout window's landing page took the continuation over
 *    (the intended one-window path): stop silently, zero UI.
 *  - still not consumed at the deadline (typically: this gateway port is not
 *    on the provider's post_logout_redirect_uri allow-list, which the provider
 *    answers with 400, so the landing page is never reached) → show the login
 *    PANEL as the visible failure face. Deliberately the plain
 *    `openLoginModal({forceLogin:true, deferPopup:true})` call: it is the same
 *    user-gesture-chain surface the avatar uses (so the auto-flow guard is
 *    neither narrowed nor bypassed, and no window is opened from a non-gesture
 *    context — the panel's own button is the gesture that opens one), and
 *    `forceLogin` keeps the switch-account forced-fresh-login semantic instead
 *    of silently downgrading to a plain login.
 *  Exported for the batch harness (the measurement drives the same code the
 *  UI does). */
export async function watchSwitchHandoff() {
  if (_switchHandoffTimer) { clearTimeout(_switchHandoffTimer); _switchHandoffTimer = null; }
  const deadline = Date.now() + SWITCH_HANDOFF_DEADLINE_MS;
  const tick = async () => {
    _switchHandoffTimer = null;
    let state = '';
    try {
      const resp = await fetch('/api/neblink/auth/handoff', {
        headers: { 'Authorization': 'Bearer ' + getAuthToken() },
      });
      if (resp.ok) state = (await resp.json()).state || '';
    } catch (e) { /* transient readout failure — keep polling until the deadline */ }
    if (state === 'consumed') return; // continuation took over in the logout window
    if (Date.now() >= deadline) {
      import('./activityBar.js')
        .then(m => m.openLoginModal({ forceLogin: true, deferPopup: true }))
        .catch(() => {});
      return;
    }
    _switchHandoffTimer = setTimeout(tick, SWITCH_HANDOFF_POLL_MS);
  };
  _switchHandoffTimer = setTimeout(tick, SWITCH_HANDOFF_POLL_MS);
}

/**
 * Open the switch-account glass modal (spec §2): title + remembered account
 * list (current account pinned/highlighted with a 「当前」 tag) + the
 * bottom 「使用其他账号登录…」 entry. Empty list → only that entry.
 * Clicking the CURRENT account only closes the modal (zero side effects);
 * any other row runs the logout+login chain (§4); the bottom entry runs it
 * without a prefill target (§5).
 * @param {() => void} initiateLogout — shared RP-logout chain
 */
function openSwitchAccountModal(initiateLogout) {
  if (document.getElementById('nebflow-switch-account-modal')) return;

  const modal = document.createElement('div');
  modal.id = 'nebflow-switch-account-modal';
  modal.className = 'neblink-switch-modal';
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-label', t('neblink.switchAccount'));
  document.body.appendChild(modal);

  const currentEmail = (neblinkState.device?.email || '').trim().toLowerCase();
  // Spec §3: current account pinned to top (green frame + 「当前」 tag).
  // Status capture already keeps the current at head; the explicit stable
  // hoist makes the pin independent of the stored order.
  const accounts = getKnownAccounts().slice().sort((a, b) => {
    const am = currentEmail && a.email.trim().toLowerCase() === currentEmail ? 0 : 1;
    const bm = currentEmail && b.email.trim().toLowerCase() === currentEmail ? 0 : 1;
    return am - bm;
  });

  const close = () => {
    document.removeEventListener('keydown', onKey);
    modal.remove();
  };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);
  // Click outside to close (deferred so the opening click doesn't close it).
  setTimeout(() => {
    document.addEventListener('click', function outside(e) {
      if (!document.body.contains(modal)) {
        document.removeEventListener('click', outside);
      } else if (!modal.contains(/** @type {Node} */ (e.target))) {
        close();
        document.removeEventListener('click', outside);
      }
    });
  }, 100);

  // ── build DOM ──
  const header = document.createElement('div');
  header.className = 'neblink-switch-header';
  const h3 = document.createElement('h3');
  h3.textContent = t('neblink.switchAccount');
  const closeBtn = document.createElement('button');
  closeBtn.className = 'neblink-switch-close';
  closeBtn.type = 'button';
  closeBtn.setAttribute('aria-label', t('neblink.switchAccount'));
  closeBtn.textContent = '\u00d7';
  closeBtn.addEventListener('click', close);
  header.append(h3, closeBtn);

  const body = document.createElement('div');
  body.className = 'neblink-switch-body';

  const list = document.createElement('div');
  list.className = 'neblink-switch-list';
  for (const acct of accounts) {
    const row = document.createElement('button');
    row.type = 'button';
    const isCurrent = currentEmail && acct.email.trim().toLowerCase() === currentEmail;
    row.className = 'neblink-switch-row' + (isCurrent ? ' current' : '');

    const avatar = document.createElement('span');
    avatar.className = 'neblink-switch-avatar';
    avatar.setAttribute('aria-hidden', 'true');
    const initial = (acct.displayName || acct.email).trim().charAt(0).toUpperCase();
    avatar.textContent = initial || '?';

    const name = document.createElement('span');
    name.className = 'neblink-switch-name';
    name.textContent = acct.displayName || acct.email;
    name.title = acct.email;

    row.append(avatar, name);

    if (isCurrent) {
      const tag = document.createElement('span');
      tag.className = 'neblink-switch-tag';
      tag.textContent = t('neblink.currentTag');
      row.appendChild(tag);
      // Spec §4: clicking the current account = close only, zero side effects.
      row.addEventListener('click', close);
    } else {
      // Spec §4: switch = full logout chain + fresh login on the hosted
      // page (v1 ships without the BYUI prefill param — see the prefill
      // slot note on switchLogoutAndLogin).
      row.addEventListener('click', () => {
        close();
        void switchLogoutAndLogin(initiateLogout, acct.email);
      });
    }
    list.appendChild(row);
  }
  if (accounts.length) body.appendChild(list);

  // Spec §3/§5: bottom entry — logout + hosted login without a prefill
  // target. Rendered alone when the list is empty.
  const other = document.createElement('button');
  other.type = 'button';
  other.className = 'neblink-switch-other';
  other.textContent = t('neblink.useOtherAccount');
  other.addEventListener('click', () => {
    close();
    void switchLogoutAndLogin(initiateLogout, null);
  });
  body.appendChild(other);

  modal.append(header, body);
}

// ---- Init (called once from main.js) ----
export async function initNeblink() {
  await fetchNeblinkStatus();
  // Push-based peer status: when backend broadcasts peerListChanged,
  // immediately re-fetch neblink status instead of waiting for poll.
  onMessage('peerListChanged', () => { fetchNeblinkStatus().then(() => _rerender?.()); });

  // Handle remote update result
  // 🔴 本处理体是设备更新结果帧的**唯一**消费点（hotupdate 批 3 · G7 仅把播报面从
  // `_rerender` 扩到 `notifyDeviceUpdateChange()`，使联系人面板设备行同帧刷新）；
  // 状态机语义 / TTL / 泛化分支一律原样——禁另建第二套结果处理。
  onMessage('remoteUpdateResult', (msg) => {
    // msg.device may not be set on error, but we update all 'updating' devices
    const targetDevice = msg.device;
    // 幂等键**留存**（批 3 · G8/G7 增量，唯一改动点）：结果帧已回显 `clientRequestId`
    // ⇒ 终局条目也把键带上，使 H10「携带 clientRequestId 的重试须复用同键」在
    // **错误档**同样成立（重试窗 = 该条目既有 10s TTL；见 `pendingDeviceUpdateKey`）。
    const keyOf = (dn) => (deviceUpdateState[dn]?.clientRequestId)
      || (typeof msg.clientRequestId === 'string' ? msg.clientRequestId : null);
    if (targetDevice) {
      deviceUpdateState[targetDevice] = msg.success
        ? { status: 'done', clientRequestId: keyOf(targetDevice) }
        : { status: 'error', message: msg.error || 'Failed', clientRequestId: keyOf(targetDevice) };
      // Clear 'done' state after 10 seconds (device should be back online)
      if (msg.success) {
        setTimeout(() => { delete deviceUpdateState[targetDevice]; notifyDeviceUpdateChange(); }, 10000);
      }
    } else {
      // No device specified — update all 'updating' entries
      for (const [dn, st] of Object.entries(deviceUpdateState)) {
        if (st.status === 'updating') {
          deviceUpdateState[dn] = msg.success
            ? { status: 'done', clientRequestId: keyOf(dn) }
            : { status: 'error', message: msg.error || 'Failed', clientRequestId: keyOf(dn) };
          if (msg.success) setTimeout(() => { delete deviceUpdateState[dn]; notifyDeviceUpdateChange(); }, 10000);
        }
      }
    }
    // Clear 'error' state after 10 seconds
    for (const dn of Object.keys(deviceUpdateState)) {
      if (deviceUpdateState[dn].status === 'error') {
        const devName = dn;
        setTimeout(() => { delete deviceUpdateState[devName]; notifyDeviceUpdateChange(); }, 10000);
      }
    }
    notifyDeviceUpdateChange();
  });
}
