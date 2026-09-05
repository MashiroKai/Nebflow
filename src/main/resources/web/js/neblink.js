/**
 * NebLink — P2P device discovery via NebLink Server.
 * Device pairing: nebflow.space login → auto-configure NebLink.
 */
import state from './state.js';
import { key } from './branding.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';
import { onMessage, sendWs } from './ws.js';
import { brand } from './brand.js';
import { lookupAvatar, rememberAvatarProfile, lastKnownAvatarProfile, forgetAvatarProfile } from './avatarCache.js';
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
  deviceCode: ''
};

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

/** Read-only accessor for the current NebLink state (used by the Activity Bar). */
export function getNeblinkState() {
  return neblinkState;
}

// Per-device remote update state: 'idle' | 'select' | 'updating' | 'done' | 'error'
let deviceUpdateState = {};
let _rerender = null;

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
    if (!resp.ok) return;
    const data = await resp.json();
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
      avatarUrl: d.avatarUrl || ''
    } : null;
    neblinkState.peers = data.peers || [];
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
export function neblinkSettingsHTML() {
  const local = neblinkState.device || {};
  const peers = neblinkState.peers || [];

  // If pairing in progress
  if (neblinkState.pairing) {
    return `<div class="neblink-login-section">
      <div class="neblink-pairing-status">${t('neblink.pairing') || '正在配对...'}</div>
    </div>`;
  }

  // If not logged in — show a hint pointing to the avatar login entry.
  // Login happens exclusively through the avatar (device-flow modal);
  // there is intentionally no login button here.
  if (!neblinkState.loggedIn) {
    const pairErr = neblinkState.pairError
      ? `<div class="neblink-error">${escapeHtml(neblinkState.pairError)}</div>` : '';
    return `<div class="neblink-login-section">
      <div class="neblink-logged-out">
        <picture>
          <source media="(prefers-color-scheme: dark)" srcset="css/logo-dark-4.png">
          <img class="neblink-logged-out-logo" src="css/logo-bright-4.png" alt="">
        </picture>
        <div class="neblink-logged-out-text">未登录，设备互联不可用</div>
        <div class="neblink-logged-out-hint">点击左上角头像登录</div>
      </div>
      ${pairErr}
    </div>`;
  }

  const allDevices = [
    { ...local, isLocal: true },
    ...peers
  ];

  const deviceRows = allDevices.map(d => {
    // Build update UI for peer devices
    let updateUI = '';
    if (!d.isLocal) {
      const st = deviceUpdateState[d.deviceName] || { status: 'idle' };
      const dn = escapeHtml(d.deviceName);
      switch (st.status) {
        case 'select':
          updateUI = `<span class="neblink-update-inline">` +
            `<button class="neblink-ch-btn" data-device="${dn}" data-beta="false">${t('neblink.stable')}</button>` +
            `<button class="neblink-ch-btn neblink-ch-beta" data-device="${dn}" data-beta="true">${t('neblink.beta')}</button>` +
            `<button class="neblink-ch-cancel" data-device="${dn}">${t('neblink.cancel')}</button>` +
            `</span>`;
          break;
        case 'updating':
          updateUI = `<span class="neblink-update-status updating">${t('neblink.updating')}</span>`;
          break;
        case 'done':
          updateUI = `<span class="neblink-update-status done">${t('neblink.restarting')}</span>`;
          break;
        case 'error':
          updateUI = `<span class="neblink-update-status error" title="${escapeHtml(st.message || '')}">${escapeHtml(st.message || 'Error')}</span>`;
          break;
        default:
          updateUI = `<button class="neblink-peer-update-btn" data-device="${dn}">${t('neblink.update')}</button>`;
      }
    }

    const did = escapeHtml(d.deviceId || '');
    const descVal = escapeHtml(d.userDescription || '');
    // Display name: prefer the user-set description, fall back to the device's
    // host name. Raw device names look technical; the friendly name reads better.
    const displayName = escapeHtml(d.userDescription || d.deviceName || t('neblink.unknownDevice') || 'Unknown');
    const platformLabel = platformDisplay(d.platform);

    return `
      <div class="neblink-peer">
        <span class="neblink-peer-icon">${platformLabel.icon}</span>
        <span class="neblink-peer-name dropbox-clickable"
          data-device-id="${did}"
          data-device-name="${escapeHtml(d.deviceName || '')}"
          data-platform="${escapeHtml(d.platform || '')}"
          data-desc="${descVal}"
          data-is-local="${d.isLocal ? '1' : '0'}">${displayName}</span>
        ${d.isLocal
          ? '<span class="neblink-peer-status local-tag">' + t('neblink.thisDevice') + '</span>'
          : '<span class="neblink-peer-status">' + platformLabel.text + '</span>'}
        ${updateUI}
      </div>`;
  }).join('');

  const peerHint = peers.length === 0
    ? `<div class="cfg-hint" style="margin-top:6px">${t('neblink.noPeersHint') || 'No other devices found. Ensure the device link service is configured on both devices.'}</div>`
    : '';

  // NL 号入口已移除（作者 2026-09-05 裁定：NL 号统一 = 官网 Username，官网
  // 已有修改功能，客户端不再重复提供）——原「查看 + 修改 + live 可用性检测」
  // 区块连同 friendsApi 的 setNeblinkId/neblinkIdAvailable 一并删除。

  return `
    <div class="neblink-logged-in">
      <div class="neblink-section-label">${t('neblink.devices')}</div>
      <div class="neblink-peers-list">${deviceRows}</div>
      ${peerHint}
      <button class="neblink-logout-btn" id="neblink-logout-btn">退出登录</button>
    </div>`;
}

/** Map a raw platform string (e.g. "macos", "windows") to a friendly label +
 *  inline SVG icon for the device row. Falls back to a generic device icon. */
function platformDisplay(platform) {
  const p = (platform || '').toLowerCase();
  const mac = '<svg viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M16.36 12.93c.02 2.3 2.02 3.07 2.04 3.08-.02.05-.32 1.1-1.06 2.18-.64.93-1.3 1.86-2.34 1.88-1.02.02-1.35-.6-2.52-.6-1.17 0-1.53.58-2.5.62-1 .04-1.77-1-2.42-1.93-1.32-1.9-2.33-5.39-.97-7.74.67-1.17 1.88-1.91 3.19-1.93.99-.02 1.92.66 2.52.66.6 0 1.74-.82 2.93-.7.5.02 1.9.2 2.8 1.52-.07.05-1.67.98-1.65 2.92M14.6 5.4c.55-.67.92-1.6.82-2.52-.79.03-1.75.53-2.32 1.2-.51.59-.96 1.53-.84 2.44.88.07 1.79-.45 2.34-1.12"/></svg>';
  const win = '<svg viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M3 5.48 10.4 4.4v7.1H3V5.48m0 13.04V13.4h7.4v7.1L3 18.52M11.4 4.26 21 3v8.5H11.4V4.26m0 15.48V13.4H21V21l-9.6-1.26"/></svg>';
  const linux = '<svg viewBox="0 0 24 24" fill="currentColor" width="15" height="15"><path d="M12.5 2c-1.3 0-2 1.1-2 2.4 0 .4.1.8.2 1.1-.5.5-1 1.4-1.4 2.5-.4 1.2-1 2.2-1.5 2.7-.5.4-1 .9-1.3 1.6-.3.7-.4 1.9.3 2.7-.3.5-.6 1.4-.3 2.3.2.7.7 1.2.8 1.7.1.5 0 .9.3 1.3.4.5 1 .5 1.6.3.4.6 1.1.9 1.9.9.9 0 1.6-.4 2-1 .4.2.9.3 1.4.1.8-.3 1.2-1 1.2-1.8 0-.4-.1-.7-.2-1 .3-.4.6-.9.6-1.6 0-.6-.2-1.1-.5-1.5.2-.4.3-.9.1-1.5-.2-.7-.7-1.2-.8-1.7-.1-.5 0-.9-.3-1.3-.4-.5-1-.5-1.6-.3-.4-.6-1.1-.9-1.9-.9-.5 0-.9.1-1.3.3.1-.3.2-.7.2-1.1 0-1.3-.7-2.4-2-2.4"/></svg>';
  const generic = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" width="15" height="15"><rect x="3" y="4" width="18" height="12" rx="1"/><path d="M8 20h8M12 16v4"/></svg>';
  if (p.includes('mac')) return { icon: mac, text: 'macOS' };
  if (p.includes('win')) return { icon: win, text: 'Windows' };
  if (p.includes('linux')) return { icon: linux, text: 'Linux' };
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
  if (!resp.ok) throw new Error(data.error || '启动设备流程失败');
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

  const fail = (errMsg) => {
    neblinkState.flowState = 'idle';
    neblinkState.pairError = errMsg;
    neblinkState.userCode = '';
    neblinkState.deviceCode = '';
    if (_rerender) _rerender();
    onError?.(errMsg);
  };

  const poll = async () => {
    if (Date.now() > deadline) {
      fail('授权超时，请重试');
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
      // Other error (expired, denied, etc.)
      fail(data.error || '授权失败');
    } catch (e) {
      fail('网络错误: ' + e.message);
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
 */
export async function startPkceLogin() {
  const resp = await fetch('/api/neblink/auth/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getAuthToken() },
  });
  let data = {};
  try { data = await resp.json(); } catch (_) { data = {}; }
  if (resp.status === 404 && data.error === 'logto-not-configured') return null;
  if (!resp.ok || !data.authorizeUrl) throw new Error(data.error || '启动登录失败');
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

  const fail = (errMsg) => {
    neblinkState.flowState = 'idle';
    neblinkState.pairError = errMsg;
    if (_rerender) _rerender();
    onError?.(errMsg);
  };

  const poll = async () => {
    if (Date.now() > deadline) {
      fail('登录超时，请重试');
      return;
    }
    try {
      const resp = await fetch('/api/neblink/auth/state', {
        headers: { 'Authorization': 'Bearer ' + getAuthToken() },
      });
      const data = await resp.json();
      if (!resp.ok) {
        fail(data.error || '登录失败');
        return;
      }
      if (data.status === 'success') {
        // Success - device registered, credential persisted.
        neblinkState.flowState = 'success';
        neblinkState.pairError = '';
        if (_rerender) _rerender();
        // Refresh neblink status after a short delay so the profile shows up.
        setTimeout(() => fetchNeblinkStatus(), 1500);
        onSuccess?.();
        return;
      }
      if (data.status === 'error') {
        fail(data.error || '登录失败');
        return;
      }
      // idle | pending - keep polling (idle is possible right after start).
      _pkcePollTimer = setTimeout(poll, intervalMs);
    } catch (e) {
      fail('网络错误: ' + e.message);
    }
  };
  _pkcePollTimer = setTimeout(poll, intervalMs);
}

/** Cancel an in-progress PKCE state poll (e.g. the login modal was closed). */
export function cancelPkceFlow() {
  if (_pkcePollTimer) { clearTimeout(_pkcePollTimer); _pkcePollTimer = null; }
}

// ---- Bind events after HTML insert ----
export function bindNeblinkEvents(rerender) {
  _rerender = rerender;

  // Logout button — clears the device credential + disables NebLink via
  // POST /api/neblink/logout, then refreshes status so the settings panel
  // and the avatar both return to the logged-out state.
  const logoutBtn = document.getElementById('neblink-logout-btn');
  if (logoutBtn) {
    logoutBtn.addEventListener('click', async () => {
      logoutBtn.disabled = true;
      logoutBtn.textContent = '正在退出…';
      try {
        await fetch('/api/neblink/logout', {
          method: 'POST',
          headers: { 'Authorization': 'Bearer ' + getAuthToken() },
        });
      } catch (e) { /* non-critical — refresh state either way */ }
      forgetAvatarProfile(); // drop the last-known snapshot: a logged-out user must not resurrect offline
      await fetchNeblinkStatus();
      rerender();
    });
  }

  // NL 号入口已移除（09-05 裁定）——原 edit/cancel/input/save 绑定随区块删除。

  // Peer update buttons
  document.querySelectorAll('.neblink-peer-update-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const device = btn.dataset.device;
      deviceUpdateState[device] = { status: 'select' };
      rerender();
    });
  });

  // Channel selection (stable / beta)
  document.querySelectorAll('.neblink-ch-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const device = btn.dataset.device;
      const beta = btn.dataset.beta === 'true';
      deviceUpdateState[device] = { status: 'updating' };
      rerender();
      sendWs({ type: 'remoteUpdate', device, beta });
    });
  });

  // Cancel channel selection
  document.querySelectorAll('.neblink-ch-cancel').forEach(btn => {
    btn.addEventListener('click', () => {
      const device = btn.dataset.device;
      delete deviceUpdateState[device];
      rerender();
    });
  });

  // Device name click → open device modal (all devices, including local)
  document.querySelectorAll('.dropbox-clickable').forEach(el => {
    el.addEventListener('click', () => {
      // Dynamic import - P2-4 cycle cut (neblink <-> dropbox).
      import('./dropbox.js').then(({ openDropbox }) => openDropbox({
        deviceId: el.dataset.deviceId,
        deviceName: el.dataset.deviceName,
        platform: el.dataset.platform,
        userDescription: el.dataset.desc,
        isLocal: el.dataset.isLocal === '1'
      }));
    });
  });
}

// ---- Init (called once from main.js) ----
export async function initNeblink() {
  await fetchNeblinkStatus();
  // Push-based peer status: when backend broadcasts peerListChanged,
  // immediately re-fetch neblink status instead of waiting for poll.
  onMessage('peerListChanged', () => { fetchNeblinkStatus().then(() => _rerender?.()); });

  // Handle remote update result
  onMessage('remoteUpdateResult', (msg) => {
    // msg.device may not be set on error, but we update all 'updating' devices
    const targetDevice = msg.device;
    if (targetDevice) {
      deviceUpdateState[targetDevice] = msg.success
        ? { status: 'done' }
        : { status: 'error', message: msg.error || 'Failed' };
      // Clear 'done' state after 10 seconds (device should be back online)
      if (msg.success) {
        setTimeout(() => { delete deviceUpdateState[targetDevice]; _rerender?.(); }, 10000);
      }
    } else {
      // No device specified — update all 'updating' entries
      for (const [dn, st] of Object.entries(deviceUpdateState)) {
        if (st.status === 'updating') {
          deviceUpdateState[dn] = msg.success
            ? { status: 'done' }
            : { status: 'error', message: msg.error || 'Failed' };
          if (msg.success) setTimeout(() => { delete deviceUpdateState[dn]; _rerender?.(); }, 10000);
        }
      }
    }
    // Clear 'error' state after 10 seconds
    for (const dn of Object.keys(deviceUpdateState)) {
      if (deviceUpdateState[dn].status === 'error') {
        const devName = dn;
        setTimeout(() => { delete deviceUpdateState[devName]; _rerender?.(); }, 10000);
      }
    }
    _rerender?.();
  });
}
