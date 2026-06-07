/**
 * Mesh panel — multi-device status, login, sync, remote control.
 * Imported by main.js, adds a button to the header.
 */
import state from './state.js';
import { escapeHtml, renderMarkdownWithMath } from './utils.js';
import { t } from './i18n.js';

// ---- Mesh state ----
let meshState = {
  loggedIn: false,
  device: null,
  peers: [],
  cloudBaseUrl: null,
  syncing: false,
  lastSyncAt: null
};

// ---- Init ----
export function initMesh() {
  // Add mesh button to header (before conn indicator)
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
    const panel = document.getElementById('mesh-panel');
    if (panel && !panel.classList.contains('hidden') &&
        !panel.contains(e.target) && e.target.id !== 'mesh-btn' &&
        !e.target.closest('#mesh-btn')) {
      panel.classList.add('hidden');
    }
  });

  // Bind events
  bindPanelEvents();

  // Fetch initial status
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

      <!-- Not logged in -->
      <div id="mesh-login-section" class="mesh-section">
        <div class="mesh-login-prompt">Login to sync across devices</div>
        <div class="mesh-login-methods">
          <button class="mesh-login-btn mesh-wechat" id="mesh-login-wechat">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178A1.17 1.17 0 0 1 4.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178 1.17 1.17 0 0 1-1.162-1.178c0-.651.52-1.18 1.162-1.18zm3.68 4.025c-3.694 0-6.963 2.507-6.963 5.812 0 3.327 3.269 5.835 6.963 5.835.724 0 1.42-.103 2.083-.29a.722.722 0 0 1 .578.08l1.396.82a.26.26 0 0 0 .131.043c.13 0 .24-.11.24-.245 0-.06-.023-.118-.038-.176l-.287-1.088a.488.488 0 0 1 .169-.546C21.725 19.352 22.756 17.65 22.756 15.828c0-3.305-3.269-5.812-6.963-5.812h-.315zm-2.56 3.183c.527 0 .955.434.955.97a.963.963 0 0 1-.955.97.963.963 0 0 1-.956-.97c0-.536.428-.97.956-.97zm4.806 0c.527 0 .955.434.955.97a.963.963 0 0 1-.955.97.963.963 0 0 1-.956-.97c0-.536.429-.97.956-.97z"/></svg>
            WeChat Login
          </button>
        </div>
        <div class="mesh-setup">
          <label class="mesh-label">Cloud URL</label>
          <input type="text" id="mesh-cloud-url" class="mesh-input" placeholder="https://xxx.ap-shanghai.tcb.qcloud.la">
          <button class="mesh-save-btn" id="mesh-save-config">Save</button>
        </div>
      </div>

      <!-- Logged in -->
      <div id="mesh-status-section" class="mesh-section hidden">
        <div class="mesh-device-info">
          <span class="mesh-device-name" id="mesh-device-name"></span>
          <span class="mesh-user-badge" id="mesh-user-badge"></span>
        </div>

        <div class="mesh-section-title">Devices</div>
        <div id="mesh-peers-list" class="mesh-peers-list"></div>

        <div class="mesh-actions">
          <button class="mesh-action-btn" id="mesh-sync-now">
            <i data-lucide="refresh-cw" style="width:14px;height:14px"></i>
            Sync Now
          </button>
          <button class="mesh-action-btn mesh-logout" id="mesh-logout">Logout</button>
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
  document.getElementById('mesh-login-wechat')?.addEventListener('click', loginWechat);
  document.getElementById('mesh-sync-now')?.addEventListener('click', triggerSync);
  document.getElementById('mesh-logout')?.addEventListener('click', logout);
}

// ---- API calls (via existing REST API) ----
async function meshApi(path, method = 'GET', body = null) {
  const token = await getLocalToken();
  const headers = { 'Authorization': `Bearer ${token}` };
  if (body) headers['Content-Type'] = 'application/json';

  const resp = await fetch(`/api/mesh/${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : null
  });
  return resp.json();
}

async function getLocalToken() {
  // Read from the existing auth token (stored by Nebflow gateway)
  try {
    const resp = await fetch('/api/health');
    // Token is passed as query param or header — use the stored one
    return localStorage.getItem('nebflow_token') || '';
  } catch {
    return '';
  }
}

// ---- Actions ----
async function fetchMeshStatus() {
  try {
    const data = await meshApi('status');
    meshState = { ...meshState, ...data };
    updateUI();
  } catch (e) {
    // Mesh not enabled — hide button
    document.getElementById('mesh-btn')?.classList.add('hidden');
  }
}

function updateUI() {
  const btn = document.getElementById('mesh-btn');
  if (!btn) return;

  // Update button appearance
  if (meshState.loggedIn) {
    btn.classList.remove('mesh-off');
    btn.classList.add('mesh-on');
    const peerCount = meshState.peers.filter(p => p.online).length;
    btn.title = `Mesh: ${peerCount} device${peerCount !== 1 ? 's' : ''} online`;
  } else {
    btn.classList.remove('mesh-on');
    btn.classList.add('mesh-off');
    btn.title = 'Mesh: Not logged in';
  }

  // Update panel sections
  const loginSection = document.getElementById('mesh-login-section');
  const statusSection = document.getElementById('mesh-status-section');

  if (meshState.loggedIn) {
    loginSection?.classList.add('hidden');
    statusSection?.classList.remove('hidden');

    // Device info
    const nameEl = document.getElementById('mesh-device-name');
    const badgeEl = document.getElementById('mesh-user-badge');
    if (nameEl && meshState.device) {
      nameEl.textContent = meshState.device.name || 'Unknown Device';
    }
    if (badgeEl) {
      badgeEl.textContent = `User: ${(meshState.device?.userId || '').slice(0, 8)}...`;
    }

    // Peers list
    renderPeers();
  } else {
    loginSection?.classList.remove('hidden');
    statusSection?.classList.add('hidden');

    // Restore cloud URL if saved
    if (meshState.cloudBaseUrl) {
      const input = document.getElementById('mesh-cloud-url');
      if (input) input.value = meshState.cloudBaseUrl;
    }
  }
}

function renderPeers() {
  const container = document.getElementById('mesh-peers-list');
  if (!container) return;

  // Include local device + peers
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
    showSyncStatus('Failed to save config: ' + e.message);
  }
}

async function loginWechat() {
  if (!meshState.cloudBaseUrl) {
    showSyncStatus('Please set Cloud URL first');
    return;
  }
  // Redirect to cloud function for WeChat OAuth
  const loginUrl = `${meshState.cloudBaseUrl}/auth/wechat-login`;
  window.open(loginUrl, '_blank', 'width=600,height=500');
}

// Called from the OAuth callback page
window.__nebflowMeshLogin = async function(userId, jwt, expiresAt) {
  try {
    await meshApi('login', 'POST', { userId, jwt, expiresAt });
    meshState.loggedIn = true;
    await fetchMeshStatus();
    showSyncStatus('Logged in!');
  } catch (e) {
    showSyncStatus('Login failed: ' + e.message);
  }
};

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

function logout() {
  // Clear local JWT
  meshApi('config', 'PATCH', { enabled: false }).catch(() => {});
  meshState.loggedIn = false;
  meshState.peers = [];
  updateUI();
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
  // Refresh status when opening
  if (!panel.classList.contains('hidden')) {
    fetchMeshStatus();
  }
}
