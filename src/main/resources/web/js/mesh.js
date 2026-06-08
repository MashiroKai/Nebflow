/**
 * Mesh panel — multi-device pairing, sync, remote control.
 * Imported by main.js, adds a button to the header.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';

// ---- Mesh state ----
let meshState = {
  paired: false,
  device: null,
  peers: [],
  cloudBaseUrl: null,
  syncing: false,
  lastSyncAt: null
};

// ---- Init ----
export function initMesh() {
  const headerRight = document.querySelector('.header-right');
  if (!headerRight) return;

  const btn = document.createElement('button');
  btn.id = 'mesh-btn';
  btn.title = 'Nebflow Mesh';
  btn.innerHTML = '<i data-lucide="radio"></i>';
  btn.addEventListener('click', toggleMeshPanel);
  headerRight.insertBefore(btn, headerRight.querySelector('#bg-indicator') || headerRight.querySelector('.conn'));

  // Create panel (hidden)
  const panel = document.createElement('div');
  panel.id = 'mesh-panel';
  panel.className = 'mesh-panel hidden';
  panel.innerHTML = buildPanelHTML();
  document.getElementById('main')?.appendChild(panel);

  // Close on outside click
  document.addEventListener('click', (e) => {
    const p = document.getElementById('mesh-panel');
    if (p && !p.classList.contains('hidden') &&
        !p.contains(e.target) && e.target.id !== 'mesh-btn' &&
        !e.target.closest('#mesh-btn')) {
      p.classList.add('hidden');
    }
  });

  bindPanelEvents();
  fetchMeshStatus();
}

// ---- Panel HTML ----
function buildPanelHTML() {
  return `
    <div class="mesh-panel-content">
      <div class="mesh-panel-header">
        <span class="mesh-panel-title">Nebflow Mesh</span>
        <button class="mesh-close-btn" id="mesh-close">&times;</button>
      </div>

      <!-- Not paired yet -->
      <div id="mesh-pair-section" class="mesh-section">
        <div class="mesh-pair-prompt">Pair devices to sync data</div>

        <div class="mesh-pair-actions">
          <button class="mesh-pair-btn mesh-create" id="mesh-create-group">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14"/></svg>
            Create Group
          </button>
          <button class="mesh-pair-btn mesh-join" id="mesh-join-group">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7"/></svg>
            Join Group
          </button>
        </div>

        <!-- Pairing code display (after create) -->
        <div id="mesh-code-display" class="mesh-code-display hidden">
          <div class="mesh-code-label">Pairing Code</div>
          <div class="mesh-code-value" id="mesh-code-value"></div>
          <div class="mesh-code-timer" id="mesh-code-timer"></div>
          <div class="mesh-code-hint">Enter this code on another device</div>
        </div>

        <!-- Join input -->
        <div id="mesh-join-input" class="mesh-join-input hidden">
          <label class="mesh-label">Enter Pairing Code</label>
          <input type="text" id="mesh-pairing-code" class="mesh-input mesh-code-input"
                 placeholder="000000" maxlength="6" inputmode="numeric" pattern="[0-9]*">
          <button class="mesh-join-submit" id="mesh-join-submit">Pair</button>
        </div>

        <div class="mesh-setup">
          <label class="mesh-label">Cloud URL</label>
          <input type="text" id="mesh-cloud-url" class="mesh-input"
                 placeholder="https://xxx.service.tcloudbase.com/nebflow-mesh">
          <button class="mesh-save-btn" id="mesh-save-config">Save</button>
        </div>
      </div>

      <!-- Paired -->
      <div id="mesh-status-section" class="mesh-section hidden">
        <div class="mesh-device-info">
          <span class="mesh-device-name" id="mesh-device-name"></span>
          <span class="mesh-group-badge" id="mesh-group-badge"></span>
        </div>

        <div class="mesh-section-title">Devices</div>
        <div id="mesh-peers-list" class="mesh-peers-list"></div>

        <div class="mesh-actions">
          <button class="mesh-action-btn" id="mesh-sync-now">
            <i data-lucide="refresh-cw" style="width:14px;height:14px"></i>
            Sync Now
          </button>
          <button class="mesh-action-btn mesh-leave" id="mesh-leave">Leave</button>
        </div>
      </div>

      <!-- Sync status -->
      <div id="mesh-sync-status" class="mesh-sync-status hidden">
        <span class="mesh-sync-spinner"></span>
        <span id="mesh-sync-text">Syncing...</span>
      </div>
    </div>
  `;
}

// ---- Events ----
function bindPanelEvents() {
  document.getElementById('mesh-close')?.addEventListener('click', () => {
    document.getElementById('mesh-panel')?.classList.add('hidden');
  });
  document.getElementById('mesh-save-config')?.addEventListener('click', saveConfig);
  document.getElementById('mesh-create-group')?.addEventListener('click', doCreateGroup);
  document.getElementById('mesh-join-group')?.addEventListener('click', showJoinInput);
  document.getElementById('mesh-join-submit')?.addEventListener('click', doJoinGroup);
  document.getElementById('mesh-sync-now')?.addEventListener('click', triggerSync);
  document.getElementById('mesh-leave')?.addEventListener('click', doLeave);

  // Auto-uppercase + auto-submit on 6 digits
  document.getElementById('mesh-pairing-code')?.addEventListener('input', (e) => {
    e.target.value = e.target.value.replace(/[^0-9]/g, '').slice(0, 6);
    if (e.target.value.length === 6) doJoinGroup();
  });
}

// Read auth token from cookie (where ws.js stores it)
function getAuthToken() {
  const m = document.cookie.match(/(?:^|;\s*)nebflow_token=([^;]*)/);
  return m ? decodeURIComponent(m[1]) : '';
}

// ---- API ----
async function meshApi(path, method = 'GET', body = null) {
  const token = getAuthToken();
  const headers = { 'Authorization': `Bearer ${token}` };
  if (body) headers['Content-Type'] = 'application/json';

  const resp = await fetch(`/api/mesh/${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : null
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => '');
    throw new Error(`Mesh API ${resp.status}: ${text.slice(0, 100)}`);
  }
  return resp.json();
}

// ---- Actions ----
async function fetchMeshStatus() {
  try {
    const data = await meshApi('status');
    meshState = { ...meshState, ...data };
    updateUI();
  } catch (e) {
    // API failed — still show the button but mark as not paired
    meshState.paired = false;
    updateUI();
  }
}

function updateUI() {
  const btn = document.getElementById('mesh-btn');
  if (!btn) return;

  if (meshState.paired) {
    btn.classList.remove('mesh-off');
    btn.classList.add('mesh-on');
    const peerCount = meshState.peers.filter(p => p.online).length;
    btn.title = `Mesh: ${peerCount + 1} device${peerCount !== 0 ? 's' : ''}`;
  } else {
    btn.classList.remove('mesh-on');
    btn.classList.add('mesh-off');
    btn.title = 'Mesh: Not paired';
  }

  const pairSection = document.getElementById('mesh-pair-section');
  const statusSection = document.getElementById('mesh-status-section');

  if (meshState.paired) {
    pairSection?.classList.add('hidden');
    statusSection?.classList.remove('hidden');

    const nameEl = document.getElementById('mesh-device-name');
    const badgeEl = document.getElementById('mesh-group-badge');
    if (nameEl && meshState.device) {
      nameEl.textContent = meshState.device.name || 'Unknown Device';
    }
    if (badgeEl && meshState.device?.groupId) {
      badgeEl.textContent = `Group: ${meshState.device.groupId.slice(0, 8)}...`;
    }
    renderPeers();
  } else {
    pairSection?.classList.remove('hidden');
    statusSection?.classList.add('hidden');
    if (meshState.cloudBaseUrl) {
      const input = document.getElementById('mesh-cloud-url');
      if (input) input.value = meshState.cloudBaseUrl;
    }
  }
}

function renderPeers() {
  const container = document.getElementById('mesh-peers-list');
  if (!container) return;

  const allDevices = [
    { ...meshState.device, deviceId: 'local', online: true },
    ...meshState.peers
  ];

  container.innerHTML = allDevices.map(d => `
    <div class="mesh-peer ${d.online ? 'online' : 'offline'}">
      <span class="mesh-peer-dot ${d.online ? 'dot-on' : 'dot-off'}"></span>
      <span class="mesh-peer-name">${escapeHtml(d.deviceName || d.name || 'Unknown')}</span>
      <span class="mesh-peer-platform">${escapeHtml(d.platform || '')}</span>
      ${d.deviceId !== 'local' ? `<span class="mesh-peer-status">${d.online ? 'Online' : 'Offline'}</span>` : '<span class="mesh-peer-status local-tag">This device</span>'}
    </div>
  `).join('');
}

async function saveConfig() {
  const url = document.getElementById('mesh-cloud-url')?.value?.trim();
  if (!url) return;
  try {
    await meshApi('config', 'PATCH', { cloudBaseUrl: url });
    meshState.cloudBaseUrl = url;
    showSyncStatus('Config saved');
  } catch (e) {
    showSyncStatus('Failed: ' + e.message);
  }
}

async function doCreateGroup() {
  if (!meshState.cloudBaseUrl) {
    showSyncStatus('Set Cloud URL first');
    return;
  }
  try {
    showSyncStatus('Creating group...');
    const data = await meshApi('create-group', 'POST');
    if (data.error) {
      showSyncStatus(data.error);
      return;
    }
    meshState.paired = true;
    meshState.device = { ...meshState.device, groupId: data.groupId };
    showPairingCode(data.pairingCode, data.expiresAt);
    updateUI();
  } catch (e) {
    showSyncStatus('Failed: ' + e.message);
  }
}

function showPairingCode(code, expiresAt) {
  const display = document.getElementById('mesh-code-display');
  const valueEl = document.getElementById('mesh-code-value');
  const timerEl = document.getElementById('mesh-code-timer');
  if (!display || !valueEl || !timerEl) return;

  // Format as XXX-XXX
  valueEl.textContent = code.slice(0, 3) + ' ' + code.slice(3);
  display.classList.remove('hidden');

  // Countdown
  const update = () => {
    const remaining = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
    if (remaining <= 0) {
      timerEl.textContent = 'Expired';
      display.classList.add('mesh-code-expired');
      return;
    }
    const min = Math.floor(remaining / 60);
    const sec = remaining % 60;
    timerEl.textContent = `${min}:${sec.toString().padStart(2, '0')}`;
    setTimeout(update, 1000);
  };
  update();
}

function showJoinInput() {
  const input = document.getElementById('mesh-join-input');
  if (input) {
    input.classList.remove('hidden');
    document.getElementById('mesh-pairing-code')?.focus();
  }
}

async function doJoinGroup() {
  const code = document.getElementById('mesh-pairing-code')?.value?.trim();
  if (!code || code.length !== 6) return;

  try {
    showSyncStatus('Pairing...');
    const data = await meshApi('join-group', 'POST', { pairingCode: code });
    if (data.error) {
      showSyncStatus(data.error);
      return;
    }
    meshState.paired = true;
    meshState.device = { ...meshState.device, groupId: data.groupId };
    showSyncStatus('Paired!');
    updateUI();
  } catch (e) {
    showSyncStatus('Failed: ' + e.message);
  }
}

async function triggerSync() {
  if (meshState.syncing) return;
  meshState.syncing = true;
  showSyncStatus('Syncing...');
  try {
    await meshApi('sync', 'POST');
    meshState.lastSyncAt = Date.now();
    showSyncStatus('Sync complete');
  } catch (e) {
    showSyncStatus('Sync failed: ' + e.message);
  } finally {
    meshState.syncing = false;
  }
}

async function doLeave() {
  if (!confirm('Leave this device group? Synced data stays in the cloud.')) return;
  try {
    await meshApi('config', 'PATCH', { enabled: false });
    // Reset local state
    meshState.paired = false;
    meshState.peers = [];
    meshState.device = { ...meshState.device, groupId: null };
    // Call backend to clear groupId
    fetch('/api/mesh/leave', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${getAuthToken()}` }
    }).catch(() => {});
    updateUI();
    showSyncStatus('Left group');
  } catch (e) {
    showSyncStatus('Failed: ' + e.message);
  }
}

function showSyncStatus(text) {
  const el = document.getElementById('mesh-sync-status');
  const textEl = document.getElementById('mesh-sync-text');
  if (!el || !textEl) return;
  textEl.textContent = text;
  el.classList.remove('hidden');
  setTimeout(() => el.classList.add('hidden'), 3000);
}

function toggleMeshPanel() {
  const panel = document.getElementById('mesh-panel');
  if (!panel) return;
  panel.classList.toggle('hidden');
  if (!panel.classList.contains('hidden')) {
    fetchMeshStatus();
  }
}
