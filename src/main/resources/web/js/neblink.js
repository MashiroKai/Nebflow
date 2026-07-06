/**
 * NebLink — Tailscale P2P device discovery.
 * No login, no relay server. Tailscale is the trust boundary.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';
import { onMessage, sendWs } from './ws.js';

let neblinkState = {
  device: null,
  peers: []
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
    neblinkState.device = data.device || null;
    neblinkState.peers = data.peers || [];
  } catch (e) {
    // neblink not available yet
  }
}

// ---- Settings section HTML ----
export function neblinkSettingsHTML() {
  const local = neblinkState.device || {};
  const peers = neblinkState.peers || [];

  const allDevices = [
    { ...local, isLocal: true },
    ...peers
  ];

  const deviceRows = allDevices.map(d => {
    const caps = d.capabilities ? Object.keys(d.capabilities) : [];
    const capStr = caps.length > 0
      ? `<span class="neblink-peer-caps">${caps.join(', ')}</span>`
      : '';
    const descStr = d.userDescription
      ? `<span class="neblink-peer-desc">${escapeHtml(d.userDescription)}</span>`
      : '';

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

    return `
      <div class="neblink-peer">
        <span class="neblink-peer-dot dot-on"></span>
        <span class="neblink-peer-name">${escapeHtml(d.deviceName || d.platform || 'Unknown')}</span>
        ${d.isLocal
          ? '<span class="neblink-peer-status local-tag">' + t('neblink.thisDevice') + '</span>'
          : '<span class="neblink-peer-status">' + t('neblink.connected') + '</span>'}
        ${capStr}
        ${descStr}
        ${updateUI}
      </div>`;
  }).join('');

  const localDesc = local.userDescription || '';
  const localCaps = local.capabilities ? Object.keys(local.capabilities) : [];
  const capsDisplay = localCaps.length > 0
    ? `<div class="neblink-caps-display">${t('neblink.detectedTools')}: ${localCaps.join(', ')}</div>`
    : '';

  const peerHint = peers.length === 0
    ? `<div class="cfg-hint" style="margin-top:6px">${t('neblink.noPeersHint') || 'No other devices found. Ensure Tailscale is running on both devices.'}</div>`
    : '';

  return `
    <div class="neblink-logged-in">
      <div class="neblink-section-label">${t('neblink.devices')}</div>
      <div class="neblink-peers-list">${deviceRows}</div>
      ${peerHint}
      ${capsDisplay}
      <div class="neblink-section-label" style="margin-top:10px">${t('neblink.deviceDescription')}</div>
      <input type="text" id="neblink-device-desc" class="cfg-input"
             placeholder="${t('neblink.deviceDescHint')}"
             value="${escapeHtml(localDesc)}"
             style="margin-bottom:6px">
      <button class="cfg-btn" id="neblink-save-desc" style="width:100%">${t('neblink.save')}</button>
    </div>`;
}

// ---- Bind events after HTML insert ----
export function bindNeblinkEvents(rerender) {
  _rerender = rerender;

  document.getElementById('neblink-save-desc')?.addEventListener('click', () => doSaveDescription(rerender));
  document.getElementById('neblink-device-desc')?.addEventListener('keydown', e => {
    if (e.key === 'Enter') doSaveDescription(rerender);
  });

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
}

// ---- Actions ----
async function doSaveDescription(rerender) {
  const desc = document.getElementById('neblink-device-desc')?.value?.trim() || '';
  try {
    const token = getAuthToken();
    await fetch('/api/neblink/device-info', {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ userDescription: desc })
    });
    if (neblinkState.device) neblinkState.device.userDescription = desc;
    rerender();
  } catch (e) {
    // ignore
  }
}

// ---- Init (called once from main.js) ----
export async function initNeblink() {
  await fetchNeblinkStatus();
  // Push-based peer status: when backend broadcasts peerListChanged,
  // immediately re-fetch neblink status instead of waiting for poll.
  onMessage('peerListChanged', () => { fetchNeblinkStatus(); });

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
