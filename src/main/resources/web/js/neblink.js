/**
 * NebLink — P2P device discovery via NebLink Server.
 * Device pairing: mystand.space login → auto-configure NebLink.
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
import { lookupAvatar, rememberAvatarProfile, lastKnownAvatarProfile, forgetAvatarProfile } from './avatarCache.js';
// knownAccounts.js（切换账号的本地账号记忆）已随 auth-trim 批（2026-10-20 作者令）
// 整体删除：设置页「切换账号/退出登录」两键退役 ⇒ 记忆的唯一消费面不存在。
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
  // Login-flow state: 'idle' | 'waiting' | 'success' (PKCE browser login).
  flowState: 'idle',
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

// ── 隔离护栏拒绝的可判别化（coldstart DX 批②，2026-10-14 作者令）──────────
// 修复前形态：登录链路上护栏拒绝若不带结构化 `code` 到达前端（老网关 / 带「Failed to
// start device flow: 」前缀的包装串），登录框只能裸打原文 —— 22:20 截图的「裸报
// Unauthorized/拒绝原文」即此族。本判据按**拒绝文案签名**（两段护栏原文共有的稳定短语）
// 反查回稳定码 `enroll-refused-isolated-home`，让失败面走既有 i18n 分类分支（原因 +
// 两出路 + 诊断码），替换裸串。签名取自 EnrollGuard 两段 refusal 文案的共同稳定子串
// （"this instance runs on an isolated data root"），不匹配任何普通网络错误。
export const GUARD_REFUSAL_SIGNATURE = 'runs on an isolated data root';
export const GUARD_REFUSAL_CODE = 'enroll-refused-isolated-home';

// ── 机器指纹闸 409 的可判别化（单设备单账号批）──────────────────────────────
// 服务端 register 409 的两条稳定 reason（wire 字面量跨仓钉死：link-server
// `model.rs` ↔ 引擎 `CredentialFailure` code ↔ 本表 ↔ i18n 键后缀）。
// 结构化通道（`payload.code`）存在时直接命中；老网关/包装串把 code 通道丢掉时，
// 按**字面量反查**——失败原文（诊断码行「诊断码：<code>」或裸 reason）里必然携带
// 该码，匹配任何普通网络错误都不可能撞上（下划线 + 专有短语形态）。
export const REGISTER_CONFLICT_CODES = [
  'device_bound_other_account',
  'device_already_logged_in'
];

/** 从失败载荷反查分类码：优先 `code`（后端结构化通道）；缺失时按护栏签名与
 *  机器指纹闸 reason 字面量判别。返回 '' = 无分类（保持原样渲染，不猜）。 */
export function classifyLoginFailureCode(payload = {}) {
  const code = payload && payload.code ? String(payload.code) : '';
  if (code) return code;
  const raw = `${(payload && payload.reason) || ''}${(payload && payload.error) || ''}`;
  const conflict = REGISTER_CONFLICT_CODES.find((c) => raw.includes(c));
  if (conflict) return conflict;
  return raw.includes(GUARD_REFUSAL_SIGNATURE) ? GUARD_REFUSAL_CODE : '';
}

/** 失败面文案的**唯一前端组装点**（§8.3 三段式）。
 *  文案来源优先级：i18n（`login.reason.<code>` / `login.action.<code>` —— UI 文案的
 *  唯一来源，zh/en 成对）⇒ 后端 `reason`/`action`（老网关 / 未知码）⇒ 原始 `error`。
 *  `code` 恒为后端给的稳定枚举（结构化通道缺失时由 [[classifyLoginFailureCode]]
 *  按护栏签名反查），直接进「诊断码：」尾串（可让用户报给我们 grep 日志）。 */
export function loginFailureText(payload = {}) {
  const code = classifyLoginFailureCode(payload);
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
    // 账号记忆捕获（rememberAccount，原切换账号 spec §6）已随 auth-trim 批删除：
    // 「切换账号」入口退役 ⇒ 本地账号记忆（knownAccounts.js）连同其写入点一并退场。
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

// 旧 `checkPairingRedirect`（旧域 /connect 重定向 → POST /api/neblink/pair）
// 已随门户 MVP E7 批（2026-10-10）整体删除：后端从未有 /api/neblink/pair 路由
// （死路径技术债，方案 §6 偏差 #12 顺手清），main.js 初始化流程亦早已摘除调用。
// 配对码入网卡（E7 引入）已按作者令（2026-10-13「不需要本机配对码」）整体移除：
// 唯一入网路径 = 头像点击的账号登录（PKCE → /api/device/register，服务端
// get_or_create_default_network 即账号即网络——登录即入网，无码、无建网步骤）。

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
    // 登录入口 = 头像（本块上方由 avatar entry 渲染并持有登录点击）。配对码卡
    // 已按作者令移除：账号登录即入网（/api/device/register 自动默认网络）。
    return `<div class="neblink-login-section">
      <div class="neblink-logged-out-hint">${t('neblink.loggedOutHint')}</div>
      ${degraded}
      ${pairErr}
      ${issue}
      ${cleanup}
    </div>`;
  }

  // ── 登录态设置账号块（auth-trim 批，2026-10-20 作者令）────────────────────
  // 作者裁定：产品全走纯官网路径，控制台不承担认证态管理 ⇒ 设置页「切换账号 /
  // 退出登录」两键整体退役（登出/切号只在官网门户操作）。本块降为**只读登录态展示**：
  //   · `kickedLine`（2026-09-14 作者令 C+B 的被动可见状态行，语义不变，保留）；
  //   · `whoLine`（谁登录着，只读 — `neblink.loggedInAs`，无任何动作入口）。
  // 历史备注：切换账号弹窗（knownAccounts.js 账号记忆 + 单窗续登链
  // `switchLogoutAndLogin`/`watchSwitchHandoff`/`openSwitchAccountModal`）随两键
  // 整体删除；RP end-session 链本体（`openEndSessionHandoff`）仍服务「清理并重登」
  // 修复入口（本文件未登录失败面 + activityBar 登录错误面共用），后端 /auth
  // 落地页族（含 switch handoff 标记）照旧服务官网/修复路径。
  //
  // 早期史（设备列表 ①④，作者 2026-09-15）：设备入口已迁联系人面板
  // （`contacts.js` `buildDevicesEntry`/`buildDeviceRows`/`deviceRow`）；
  // NL 号入口已随 09-05 裁定移除（NL 号统一 = 官网 Username）。

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

  // 只读登录态：谁登录着（displayName 优先，回退 email）。纯展示，零动作。
  const who = (neblinkState.device?.displayName || neblinkState.device?.email || '').trim();
  const whoLine = who
    ? `<div class="neblink-account-ro" role="status">${escapeHtml(t('neblink.loggedInAs', { name: who }))}</div>`
    : '';

  return `
    <div class="neblink-logged-in">
      ${kickedLine}
      ${whoLine}
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

// ---- PKCE login (Authorization Code + PKCE, loopback redirect) ----
// The login path (统一登录批, 台账 #924): the browser does the full hosted
// login and redirects back to 127.0.0.1/auth/callback; this side just opens
// the URL and polls the flow state. (The legacy device-flow fallback was
// retired with the flow itself, 2026-10-26 — the gateway no longer serves
// /api/neblink/device-flow/*; a logto-not-configured answer is an error
// face, not a fallback trigger.)

/**
 * Start a Logto Authorization Code + PKCE login.
 * Returns {authorizeUrl} on success; returns null when the gateway reports
 * 404 logto-not-configured (caller surfaces the not-configured error face —
 * the legacy device-flow fallback is retired, 台账 #924);
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
 *
 * `provider` (social one-click leg, 2026-10-15 login-card batch): "github" |
 * "google" → the gateway adds `direct_sign_in=social:<provider>` to the
 * authorize URL, so the hosted leg jumps STRAIGHT into that connector
 * (core 303 → /direct/social/<p>, BYUI direct-sign-in path channel; a failed
 * direct leg degrades to the sign-in page via `fallback=sign-in`). The caller
 * MUST pair provider with forceLogin=true: the quick-social-entry guardrail
 * (forced fresh IdP auth against the automaticAccountLinking silent merge,
 * fix-plan §7.3) is the same contract the website face ships. Any other
 * value (or "") → the field is OMITTED from the body entirely (conditional
 * spread), so the plain mail chain's request is byte-identical to the
 * pre-social shape.
 */
export async function startPkceLogin(forceLogin = false, provider = '') {
  const resp = await fetch('/api/neblink/auth/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getAuthToken() },
    body: JSON.stringify({
      forceLogin: !!forceLogin,
      uiLocales: getLocale() === 'en' ? 'en' : 'zh',
      // Additive field, present ONLY on the social leg: an empty/absent
      // provider keeps the serialized body byte-identical to the legacy
      // mail chain (zero behavior regression on the existing path).
      ...(provider ? { provider } : {}),
    }),
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
    neblinkState.pairErrorCode = classifyLoginFailureCode(payload);
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

/** 预约一个空白窗。**必须**在手势栈内同步调用（popup-blocker 只认同步 `window.open`）。 */
function reserveEndSessionWindow() {
  let w = null;
  try {
    w = window.open('about:blank', '_blank');
  } catch (e) {
    w = null;
  }
  // `noopener` 语义：预约式**不能**用 feature 串（`window.open(url,'_blank','noopener')`
  // 返回 null ⇒ 窗就再也导航不了）。改为同 tick 置 `w.opener = null`（about:blank
  // 同源，可写）——隔离性等价，且登录流（activityBar.js `reservePopup`）今天已是同形态。
  if (w) {
    try { w.opener = null; } catch (e) { /* 不致命：仅影响跨窗引用隔离 */ }
  }
  return w;
}

/** 把已预约的窗导航到 url；url 为空 / 窗不在 ⇒ 关窗并回 false。 */
function navigateEndSessionWindow(w, url) {
  if (!w) return false;
  try {
    if (url) { w.location.href = url; return true; }
    w.close();
  } catch (e) { /* 跨域或已关闭 —— 忽略 */ }
  return false;
}

/** 可见失败面（本批新链路的失败支**禁静默**）：复用既有 `.nebflow-toast*` CSS 类与既有
 *  i18n 键；带 `link` 时附一个人工入口（popup-blocker 兜底）。形态 = activityBar.js
 *  `popupBlockedFallback` 同款；本模块**不** import activityBar.js（既有依赖方向是
 *  activityBar → neblink，反向会成环）。 */
function endSessionNotice(message, link) {
  try {
    const toast = document.createElement('div');
    toast.className = 'nebflow-toast nebflow-toast-info';
    const msg = document.createElement('span');
    msg.className = 'nebflow-toast-msg';
    msg.textContent = message;
    toast.append(msg);
    if (link) {
      const a = document.createElement('a');
      a.className = 'glass-control nebflow-toast-link';
      a.href = link;
      a.target = '_blank';
      a.rel = 'noopener';
      a.textContent = t('login.openPage');
      toast.append(a);
    }
    document.body.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add('show'));
    setTimeout(() => {
      toast.classList.remove('show');
      setTimeout(() => toast.remove(), 300);
    }, 8000);
  } catch (e) { /* 提示失败绝不反噬登出链 */ }
}

/** RP-initiated logout hop — the **single** window-navigation point of the
 *  logout / switch-account / cleanup-and-relogin chains.
 *
 *  2026-09-20 收尾批（作者裁定 + 分发器定形）：服务端该路由由「GET 直接导航」改成
 *  **POST + 令牌**（`withAuth`），出口由 302 改成 `200 {"endSessionUrl": …}`；旧 GET = 门内
 *  405（副作用不可达）。前端因此改形为「手势内同步预约空白窗 → fetch POST（带
 *  Authorization）→ 把预约窗导航到服务端回传的 provider URL」：
 *   · 预约窗**必须**同步开（`fetch` 之后再 `window.open` 会被 popup-blocker 拦）⇒ 预约/
 *     导航两段形态与登录流的 `reservePopup`/`navigateReserved` 同款（既有模式，非新造）。
 *   · 302 无法被 fetch 消费（跨域 + opaque，拿不到目标地址）⇒ 服务端改**回传数据**，由
 *     **顶层导航**消费（顶层导航无 CORS），provider 回跳语义零改。
 *   · 失败支禁静默：非 2xx / 缺 `endSessionUrl` / 网络异常 ⇒ 关窗 + 一条可见提示。
 *
 *  `continueToLogin` = the one-window switch shape（`scenario=switch`）：the landing page
 *  this hop ends on continues into the login in the SAME window.（原主窗看门狗
 *  `watchSwitchHandoff` 已随切换账号入口退役；本形态现仅服务「清理并重登」。）
 *
 *  🔴 缺陷 A（上游 §8.2 第 6 项）：「清理并重登」入口走的就是本函数（`continueToLogin=true`）
 *  —— 它是「本地凭据坏了、清理掉再登一次」这条自救路径的既有实现，本批零新增链路。
 *
 *  Extracted from the `bindNeblinkEvents` closure so the login modal
 *  (activityBar.js) can share it verbatim instead of keeping a second copy.
 * @param {boolean} continueToLogin */
export function openEndSessionHandoff(continueToLogin = false) {
  // ① 手势内**同步**预约空白窗（popup-blocker 语义与旧形态一致：仍是用户手势内的同步 open）。
  const win = reserveEndSessionWindow();
  const token = getAuthToken();
  // ② `scenario` / `uiLocales` 由 query 迁入 POST body（本批契约；字段名与
  //    POST /api/neblink/auth/start 一致）。
  const body = continueToLogin
    ? { scenario: 'switch', uiLocales: getLocale() === 'en' ? 'en' : 'zh' }
    : {};
  // ③ 主窗后置清理：内容与时机逐字沿用，唯一区别 = 只在**服务端拆除已落定**后启动
  //    （失败支不再清缓存：登出没发生就不该表现成已登出）。
  const afterTeardown = () =>
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
  // ④ fetch POST（带令牌）→ 顶层导航预约窗。
  fetch('/api/neblink/auth/end-session', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
    .then(async (resp) => {
      if (!resp.ok) {
        navigateEndSessionWindow(win, null);
        endSessionNotice(t('login.networkError', { msg: `HTTP ${resp.status}` }));
        return;
      }
      const data = await resp.json().catch(() => null);
      const url = data && typeof data.endSessionUrl === 'string' ? data.endSessionUrl : '';
      if (!url) {
        navigateEndSessionWindow(win, null);
        endSessionNotice(t('login.networkError', { msg: 'endSessionUrl missing' }));
        return;
      }
      if (!navigateEndSessionWindow(win, url)) {
        // 预约窗被 popup-blocker 拦下（或已关闭）：给人工入口，禁静默。
        endSessionNotice(t('login.popupBlocked'), url);
      }
      afterTeardown();
    })
    .catch((e) => {
      navigateEndSessionWindow(win, null);
      endSessionNotice(t('login.networkError', { msg: e.message }));
    });
}

// ---- Bind events after HTML insert ----
export function bindNeblinkEvents(rerender) {
  _rerender = rerender;

  // 「切换账号 / 退出登录」两键及其绑定已随 auth-trim 批（2026-10-20 作者令）删除。
  // 原共用 RP-initiated logout 链（`initiateLogout`，2026-09-06 起：POST + 预约窗 →
  // 本地 8 步拆除 → 提供方 end-session → /auth/logged-out 落地页；`continueToLogin`
  // 单窗续登标记 2026-09-16）**本体保留**为 `openEndSessionHandoff` —— 它现在是
  // 「清理并重登」修复入口的独占持有者（见下），后端 /auth 路由族照旧。

  // 「清理并重登」入口（缺陷 A / 上游 §8.2 第 6 项）：**复用既有链路**，零新轮子 ——
  // 走 `openEndSessionHandoff(true)`（RP end-session → 本地拆除 → 落地页续登）。
  // 它之所以能真的「清理」，靠的是后端那笔修复：end-session 的凭据读点失败**不再跳过
  // 本地拆除**（判据 G5）⇒ 坏件即使读不开，拆除仍执行（`DeviceCredential.clear`
  // 删不掉时再走改名留档兜底）⇒ 下一次登录从干净盘重建。跳转必须在手势栈里同步
  // 发生（popup-blocker），故直接调用。
  const cleanupBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('neblink-cleanup-btn'));
  if (cleanupBtn) {
    cleanupBtn.addEventListener('click', () => {
      cleanupBtn.disabled = true;
      openEndSessionHandoff(true);
    });
  }

  // 配对码卡提交绑定已随卡片移除（作者令 2026-10-13「不需要本机配对码」）——
  // 唯一入网路径 = 头像账号登录。

  // NL 号入口已移除（09-05 裁定）——原 edit/cancel/input/save 绑定随区块删除。

  // 🔴 设置账号段的**设备列表绑定整组已摘除**（①④，作者 2026-09-15）：随设备行模板
  // 一并删除的有 —— 「更新」键、stable/beta 频道键、取消键三组绑定，以及
  // `.dropbox-clickable`（设备名 → **旧设备窗** `openDropbox`）绑定。这些选择器的
  // DOM 来源（`.neblink-peers-list` / `.neblink-peer`）已不存在（见上方本文件
  // `neblinkSettingsHTML` 的移除项清单），留着就是永不命中的死绑定。
  // 设备入口本体见 `contacts.js` 的「设备」点进展开（→ `openDeviceChat`，新设备会话窗）。
}

// ---- Switch-account modal（已退役，auth-trim 批 2026-10-20 作者令）----
// 原整段切换账号机制 —— `switchLogoutAndLogin`（单窗续登链）、`watchSwitchHandoff`
// （handoff 标记看门狗）、`openSwitchAccountModal`（玻璃弹窗：记住的账号列表 +
// 「使用其他账号登录…」）—— 随设置页「切换账号/退出登录」两键一并删除。
// 切换账号能力回归官网门户；客户端的 RP end-session 修复入口见上方
// `openEndSessionHandoff`（「清理并重登」仍在用）。

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
