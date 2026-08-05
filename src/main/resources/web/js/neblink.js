/**
 * NebLink — P2P device discovery via NebLink Server.
 * Device pairing: nebflow.space login → auto-configure NebLink.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';
import { onMessage, sendWs } from './ws.js';
import { openDropbox } from './dropbox.js';

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
  enrollMsg: ''
};

/** Read-only accessor for the current NebLink state (used by the Activity Bar). */
export function getNeblinkState() {
  return neblinkState;
}

// Per-device remote update state: 'idle' | 'select' | 'updating' | 'done' | 'error'
let deviceUpdateState = {};
let _rerender = null;

function getAuthToken() {
  return localStorage.getItem('nebflow_token') || '';
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
    // Normalize local device fields to match peer field names
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
          ? '认证失败。请从 Nebflow 终端重新打开浏览器页面，然后重试登录。'
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

  // If no device configured — show login button + pairing code option
  if (!local.deviceId) {
    const pairErr = neblinkState.pairError
      ? `<div class="neblink-error">${escapeHtml(neblinkState.pairError)}</div>` : '';
    const enrollMsg = neblinkState.enrollMsg
      ? `<div class="neblink-error" style="color:var(--success,#4ecdc4)">${escapeHtml(neblinkState.enrollMsg)}</div>` : '';
    return `<div class="neblink-login-section">
      <div class="neblink-login-hint">${t('neblink.loginHint') || '登录 NebLink Server 连接你的设备'}</div>
      ${pairErr}
      <button class="neblink-login-btn" id="neblink-login-btn">${t('neblink.login') || '通过网页登录'}</button>
      <div style="margin-top:16px;border-top:1px solid var(--border,#2d2d4a);padding-top:14px">
        <div class="neblink-login-hint" style="margin-bottom:8px">${t('neblink.pairByCodeHint') || '或输入配对码加入网络'}</div>
        <input id="neblink-enroll-server" class="neblink-input" placeholder="${t('neblink.serverUrl') || 'NebLink Server 地址 (https://neblink.nebflow.space)'}" style="width:100%;margin-bottom:8px;padding:8px;border-radius:6px;border:1px solid var(--border,#2d2d4a);background:var(--bg,#0f0f17);color:var(--text,#e4e4ef);font-size:13px" />
        <input id="neblink-enroll-code" class="neblink-input" placeholder="${t('neblink.pairCode') || '配对码（6 位数字）'}" style="width:100%;margin-bottom:8px;padding:8px;border-radius:6px;border:1px solid var(--border,#2d2d4a);background:var(--bg,#0f0f17);color:var(--text,#e4e4ef);font-size:13px" />
        <button class="neblink-login-btn" id="neblink-enroll-btn">${t('neblink.enroll') || '配对加入'}</button>
        ${enrollMsg}
      </div>
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
    ? `<div class="cfg-hint" style="margin-top:6px">${t('neblink.noPeersHint') || 'No other devices found. Ensure NebLink Server is configured on both devices.'}</div>`
    : '';

  return `
    <div class="neblink-logged-in">
      <div class="neblink-section-label">${t('neblink.devices')}</div>
      <div class="neblink-peers-list">${deviceRows}</div>
      ${peerHint}
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

// ---- Bind events after HTML insert ----
export function bindNeblinkEvents(rerender) {
  _rerender = rerender;

  // Login button — opens nebflow.space/connect
  // CRITICAL: Include the local auth token in the redirect URL so it survives
  // the round-trip through nebflow.space. Without this, the POST /api/neblink/pair
  // has no Authorization header and returns 403 (login silently fails).
  const loginBtn = document.getElementById('neblink-login-btn');
  if (loginBtn) {
    loginBtn.addEventListener('click', () => {
      const origin = window.location.origin;
      const token = getAuthToken();
      // Encode token into redirect URL — connect page preserves existing query params
      const redirectUrl = token
        ? `${origin}/?token=${encodeURIComponent(token)}`
        : origin;
      window.open(`https://nebflow.space/connect?redirect=${encodeURIComponent(redirectUrl)}`, '_blank');
    });
  }

  // Pairing-code enrollment — POST {server, pairCode} to the Scala backend,
  // which proxies to the NebLink Server's /api/device/enroll.
  const enrollBtn = document.getElementById('neblink-enroll-btn');
  if (enrollBtn) {
    enrollBtn.addEventListener('click', async () => {
      const server = document.getElementById('neblink-enroll-server')?.value.trim();
      const pairCode = document.getElementById('neblink-enroll-code')?.value.trim();
      if (!server || !pairCode) {
        neblinkState.pairError = '请填写服务器地址和配对码';
        neblinkState.enrollMsg = '';
        rerender();
        return;
      }
      enrollBtn.disabled = true;
      enrollBtn.textContent = '...';
      try {
        const resp = await fetch('/api/neblink/enroll', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getAuthToken() },
          body: JSON.stringify({ server, pairCode })
        });
        const data = await resp.json();
        if (resp.ok && data.ok) {
          neblinkState.enrollMsg = data.message || '配对成功，请重启 Nebflow 生效。';
          neblinkState.pairError = '';
        } else {
          neblinkState.pairError = data.error || '配对失败';
          neblinkState.enrollMsg = '';
        }
      } catch (e) {
        neblinkState.pairError = '网络错误: ' + e.message;
        neblinkState.enrollMsg = '';
      }
      rerender();
    });
  }

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
      openDropbox({
        deviceId: el.dataset.deviceId,
        deviceName: el.dataset.deviceName,
        platform: el.dataset.platform,
        userDescription: el.dataset.desc,
        isLocal: el.dataset.isLocal === '1'
      });
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
