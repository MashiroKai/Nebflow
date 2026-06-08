/**
 * Mesh panel — token-based device pairing, peer discovery.
 * Imported by main.js, adds a button to the header.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';

// ---- Mesh state ----
let meshState = {
  paired: false,
  device: null,
  peers: []
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
        <div class="mesh-pair-prompt">Enter the same token on all devices to pair them</div>
        <div class="mesh-token-input">
          <input type="text" id="mesh-token-input" class="mesh-input"
                 placeholder="e.g. kaiyu-lab-2025" minlength="6" autocomplete="off">
          <button class="mesh-pair-btn mesh-create" id="mesh-pair-btn">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14M12 5l7 7-7 7"/></svg>
            Join
          </button>
        </div>
        <div class="mesh-hint">Token must be at least 6 characters. All devices with the same token will discover each other automatically.</div>
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

        <div class="mesh-section-title" style="margin-top:12px">Cross-Network</div>
        <div class="mesh-cloud-config">
          <input type="text" id="mesh-cloud-url" class="mesh-input"
                 placeholder="Cloud discovery URL (optional)">
          <button class="mesh-save-btn" id="mesh-save-cloud">Save</button>
        </div>
        <div class="mesh-hint">Optional. Set a cloud function URL to discover peers across different networks (e.g. different WiFi, NAT). LAN peers are found automatically via UDP.</div>
      </div>

      <!-- Status message -->
      <div id="mesh-sync-status" class="mesh-sync-status hidden">
        <span id="mesh-sync-text"></span>
      </div>
    </div>
  `;
}

// ---- Events ----
function bindPanelEvents() {
  document.getElementById('mesh-close')?.addEventListener('click', () => {
    document.getElementById('mesh-panel')?.classList.add('hidden');
  });
  document.getElementById('mesh-pair-btn')?.addEventListener('click', doPair);
  document.getElementById('mesh-leave')?.addEventListener('click', doLeave);
  document.getElementById('mesh-sync-now')?.addEventListener('click', triggerSync);
  document.getElementById('mesh-save-cloud')?.addEventListener('click', saveCloudUrl);

  // Enter key in token input
  document.getElementById('mesh-token-input')?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doPair();
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
    const peerCount = meshState.peers.length;
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
      // Show masked token
      const t = meshState.device.groupId;
      badgeEl.textContent = t.length > 8 ? `Token: ${t.slice(0, 4)}${'*'.repeat(Math.min(t.length - 8, 8))}${t.slice(-4)}` : `Token: ${'*'.repeat(t.length)}`;
    }
    renderPeers();
    // Populate cloud URL input
    if (meshState.cloudDiscoveryUrl) {
      const input = document.getElementById('mesh-cloud-url');
      if (input) input.value = meshState.cloudDiscoveryUrl;
    }
  } else {
    pairSection?.classList.remove('hidden');
    statusSection?.classList.add('hidden');
  }
}

function renderPeers() {
  const container = document.getElementById('mesh-peers-list');
  if (!container) return;

  const allDevices = [
    { ...meshState.device, deviceId: 'local', isLocal: true },
    ...meshState.peers
  ];

  container.innerHTML = allDevices.map(d => `
    <div class="mesh-peer">
      <span class="mesh-peer-dot dot-on"></span>
      <span class="mesh-peer-name">${escapeHtml(d.deviceName || d.name || 'Unknown')}</span>
      <span class="mesh-peer-platform">${escapeHtml(d.platform || '')}</span>
      ${d.isLocal ? '<span class="mesh-peer-status local-tag">This device</span>' : '<span class="mesh-peer-status">Connected</span>'}
    </div>
  `).join('');
}

async function doPair() {
  const token = document.getElementById('mesh-token-input')?.value?.trim();
  if (!token || token.length < 6) {
    showStatus('Token must be at least 6 characters');
    return;
  }
  try {
    showStatus('Pairing...');
    await meshApi('pair', 'POST', { token });
    meshState.paired = true;
    meshState.device = { ...meshState.device, groupId: token };
    showStatus('Paired! Discovering peers...');
    updateUI();
    // Refresh to get discovered peers
    setTimeout(fetchMeshStatus, 3000);
  } catch (e) {
    showStatus('Failed: ' + e.message);
  }
}

async function doLeave() {
  if (!confirm('Leave this device group?')) return;
  try {
    await meshApi('leave', 'POST');
    meshState.paired = false;
    meshState.peers = [];
    meshState.device = { ...meshState.device, groupId: null };
    updateUI();
    showStatus('Left group');
  } catch (e) {
    showStatus('Failed: ' + e.message);
  }
}

async function triggerSync() {
  try {
    showStatus('Syncing...');
    await meshApi('sync', 'POST');
    showStatus('Sync complete');
    fetchMeshStatus();
  } catch (e) {
    showStatus('Sync failed: ' + e.message);
  }
}

async function saveCloudUrl() {
  const url = document.getElementById('mesh-cloud-url')?.value?.trim();
  try {
    await meshApi('config', 'PATCH', { cloudDiscoveryUrl: url || null });
    showStatus(url ? 'Cloud URL saved' : 'Cloud URL cleared');
  } catch (e) {
    showStatus('Failed: ' + e.message);
  }
}

function showStatus(text) {
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
