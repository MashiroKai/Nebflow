/**
 * Mesh — Tailscale P2P device discovery.
 * No login, no relay server. Tailscale is the trust boundary.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';
import { onMessage, sendWs } from './ws.js';

let meshState = {
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
export async function fetchMeshStatus() {
  try {
    const token = getAuthToken();
    const resp = await fetch('/api/mesh/status', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    if (!resp.ok) return;
    const data = await resp.json();
    meshState.device = data.device || null;
    meshState.peers = data.peers || [];
  } catch (e) {
    // mesh not available yet
  }
}

// ---- Settings section HTML ----
export function meshSettingsHTML() {
  const local = meshState.device || {};
  const peers = meshState.peers || [];

  const allDevices = [
    { ...local, isLocal: true },
    ...peers
  ];

  const deviceRows = allDevices.map(d => {
    const caps = d.capabilities ? Object.keys(d.capabilities) : [];
    const capStr = caps.length > 0
      ? `<span class="mesh-peer-caps">${caps.join(', ')}</span>`
      : '';
    const descStr = d.userDescription
      ? `<span class="mesh-peer-desc">${escapeHtml(d.userDescription)}</span>`
      : '';

    // Build update UI for peer devices
    let updateUI = '';
    if (!d.isLocal) {
      const st = deviceUpdateState[d.deviceName] || { status: 'idle' };
      const dn = escapeHtml(d.deviceName);
      switch (st.status) {
        case 'select':
          updateUI = `<span class="mesh-update-inline">` +
            `<button class="mesh-ch-btn" data-device="${dn}" data-beta="false">${t('mesh.stable')}</button>` +
            `<button class="mesh-ch-btn mesh-ch-beta" data-device="${dn}" data-beta="true">${t('mesh.beta')}</button>` +
            `<button class="mesh-ch-cancel" data-device="${dn}">${t('mesh.cancel')}</button>` +
            `</span>`;
          break;
        case 'updating':
          updateUI = `<span class="mesh-update-status updating">${t('mesh.updating')}</span>`;
          break;
        case 'done':
          updateUI = `<span class="mesh-update-status done">${t('mesh.restarting')}</span>`;
          break;
        case 'error':
          updateUI = `<span class="mesh-update-status error" title="${escapeHtml(st.message || '')}">${escapeHtml(st.message || 'Error')}</span>`;
          break;
        default:
          updateUI = `<button class="mesh-peer-update-btn" data-device="${dn}">${t('mesh.update')}</button>`;
      }
    }

    return `
      <div class="mesh-peer">
        <span class="mesh-peer-dot dot-on"></span>
        <span class="mesh-peer-name">${escapeHtml(d.deviceName || d.platform || 'Unknown')}</span>
        ${d.isLocal
          ? '<span class="mesh-peer-status local-tag">' + t('mesh.thisDevice') + '</span>'
          : '<span class="mesh-peer-status">' + t('mesh.connected') + '</span>'}
        ${capStr}
        ${descStr}
        ${updateUI}
      </div>`;
  }).join('');

  const localDesc = local.userDescription || '';
  const localCaps = local.capabilities ? Object.keys(local.capabilities) : [];
  const capsDisplay = localCaps.length > 0
    ? `<div class="mesh-caps-display">${t('mesh.detectedTools')}: ${localCaps.join(', ')}</div>`
    : '';

  const peerHint = peers.length === 0
    ? `<div class="cfg-hint" style="margin-top:6px">${t('mesh.noPeersHint') || 'No other devices found. Ensure Tailscale is running on both devices.'}</div>`
    : '';

  return `
    <div class="mesh-logged-in">
      <div class="mesh-section-label">${t('mesh.devices')}</div>
      <div class="mesh-peers-list">${deviceRows}</div>
      ${peerHint}
      ${capsDisplay}
      <div class="mesh-section-label" style="margin-top:10px">${t('mesh.deviceDescription')}</div>
      <input type="text" id="mesh-device-desc" class="cfg-input"
             placeholder="${t('mesh.deviceDescHint')}"
             value="${escapeHtml(localDesc)}"
             style="margin-bottom:6px">
      <button class="cfg-btn" id="mesh-save-desc" style="width:100%">${t('mesh.save')}</button>
    </div>`;
}

// ---- Bind events after HTML insert ----
export function bindMeshEvents(rerender) {
  _rerender = rerender;

  document.getElementById('mesh-save-desc')?.addEventListener('click', () => doSaveDescription(rerender));
  document.getElementById('mesh-device-desc')?.addEventListener('keydown', e => {
    if (e.key === 'Enter') doSaveDescription(rerender);
  });

  // Peer update buttons
  document.querySelectorAll('.mesh-peer-update-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const device = btn.dataset.device;
      deviceUpdateState[device] = { status: 'select' };
      rerender();
    });
  });

  // Channel selection (stable / beta)
  document.querySelectorAll('.mesh-ch-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const device = btn.dataset.device;
      const beta = btn.dataset.beta === 'true';
      deviceUpdateState[device] = { status: 'updating' };
      rerender();
      sendWs({ type: 'remoteUpdate', device, beta });
    });
  });

  // Cancel channel selection
  document.querySelectorAll('.mesh-ch-cancel').forEach(btn => {
    btn.addEventListener('click', () => {
      const device = btn.dataset.device;
      delete deviceUpdateState[device];
      rerender();
    });
  });
}

// ---- Actions ----
async function doSaveDescription(rerender) {
  const desc = document.getElementById('mesh-device-desc')?.value?.trim() || '';
  try {
    const token = getAuthToken();
    await fetch('/api/mesh/device-info', {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ userDescription: desc })
    });
    if (meshState.device) meshState.device.userDescription = desc;
    rerender();
  } catch (e) {
    // ignore
  }
}

// ---- Init (called once from main.js) ----
export async function initMesh() {
  await fetchMeshStatus();
  // Push-based peer status: when backend broadcasts peerListChanged,
  // immediately re-fetch mesh status instead of waiting for poll.
  onMessage('peerListChanged', () => { fetchMeshStatus(); });

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
