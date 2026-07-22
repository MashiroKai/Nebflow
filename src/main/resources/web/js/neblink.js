/**
 * NebLink — P2P device discovery via NebLink Server.
 * Device pairing: nebflow.space login → auto-configure NebLink.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';
import { onMessage, sendWs } from './ws.js';
import { openDropbox } from './dropbox.js';

let neblinkState = {
  device: null,
  peers: [],
  paired: false,
  pairing: false,
  pairError: ''
};

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
      userDescription: d.userDescription || ''
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

  if (server && networkId && secret) {
    // Clean URL first
    const cleanUrl = window.location.origin + window.location.pathname;
    window.history.replaceState({}, document.title, cleanUrl);

    // Send pairing config to backend
    neblinkState.pairing = true;
    _rerender?.();
    fetch('/api/neblink/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ server, networkId, secret })
    }).then(r => r.json()).then(data => {
      if (data.ok) {
        neblinkState.pairing = false;
        neblinkState.paired = true;
        neblinkState.pairError = '';
        setTimeout(() => fetchNeblinkStatus().then(() => _rerender?.()), 1500);
      } else {
        neblinkState.pairing = false;
        neblinkState.pairError = data.error || '配对失败';
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

  // If no device configured — show login button
  if (!local.deviceId) {
    const pairErr = neblinkState.pairError
      ? `<div class="neblink-error">${escapeHtml(neblinkState.pairError)}</div>` : '';
    return `<div class="neblink-login-section">
      <div class="neblink-login-hint">${t('neblink.loginHint') || '登录 nebflow.space 连接你的设备'}</div>
      ${pairErr}
      <button class="neblink-login-btn" id="neblink-login-btn">${t('neblink.login') || '登录连接'}</button>
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

    return `
      <div class="neblink-peer">
        <span class="neblink-peer-dot dot-on"></span>
        <span class="neblink-peer-name dropbox-clickable"
          data-device-id="${did}"
          data-device-name="${escapeHtml(d.deviceName || '')}"
          data-platform="${escapeHtml(d.platform || '')}"
          data-desc="${descVal}"
          data-is-local="${d.isLocal ? '1' : '0'}">${escapeHtml(d.deviceName || d.platform || 'Unknown')}</span>
        ${d.isLocal
          ? '<span class="neblink-peer-status local-tag">' + t('neblink.thisDevice') + '</span>'
          : '<span class="neblink-peer-status">' + t('neblink.connected') + '</span>'}
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

// ---- Bind events after HTML insert ----
export function bindNeblinkEvents(rerender) {
  _rerender = rerender;

  // Login button — opens nebflow.space/connect
  const loginBtn = document.getElementById('neblink-login-btn');
  if (loginBtn) {
    loginBtn.addEventListener('click', () => {
      const origin = window.location.origin;
      window.open(`https://nebflow.space/connect?redirect=${encodeURIComponent(origin)}`, '_blank');
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
