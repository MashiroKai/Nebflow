/**
 * Mesh — Tailscale P2P device discovery.
 * No login, no relay server. Tailscale is the trust boundary.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';

let meshState = {
  device: null,
  peers: []
};

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
    return `
      <div class="mesh-peer">
        <span class="mesh-peer-dot dot-on"></span>
        <span class="mesh-peer-name">${escapeHtml(d.deviceName || d.platform || 'Unknown')}</span>
        ${d.isLocal
          ? '<span class="mesh-peer-status local-tag">' + t('mesh.thisDevice') + '</span>'
          : '<span class="mesh-peer-status">' + t('mesh.connected') + '</span>'}
        ${capStr}
        ${descStr}
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
  document.getElementById('mesh-save-desc')?.addEventListener('click', () => doSaveDescription(rerender));
  document.getElementById('mesh-device-desc')?.addEventListener('keydown', e => {
    if (e.key === 'Enter') doSaveDescription(rerender);
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
}
