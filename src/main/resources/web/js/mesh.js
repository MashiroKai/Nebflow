/**
 * Mesh account — Nebflow account login/register for device discovery.
 * Exports a settings section renderer, used by sidebar.js.
 */
import state from './state.js';
import { escapeHtml } from './utils.js';
import { t } from './i18n.js';

// ---- Mesh state ----
let meshState = {
  loggedIn: false,
  username: null,
  device: null,
  peers: [],
  cloudUrl: null
};

let authTab = 'login'; // 'login' | 'register'

// Read auth token from localStorage (port-scoped, avoids multi-instance conflict)
function getAuthToken() {
  return localStorage.getItem('nebflow_token') || '';
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
    let msg = text.slice(0, 200);
    try { msg = JSON.parse(text).error || msg; } catch {}
    throw new Error(msg);
  }
  return resp.json();
}

// ---- Fetch status ----
export async function fetchMeshStatus() {
  try {
    const data = await meshApi('status');
    meshState = { ...meshState, ...data };
  } catch (e) {
    meshState.loggedIn = false;
  }
}

// ===== Login Gate — full-screen login required before app use =====

let gateTab = 'login'; // 'login' | 'register'
let gateChecking = false;

/** Load saved server URL from localStorage. */
function getSavedServerUrl() {
  return localStorage.getItem('nebflow_server_url') || '';
}

/** Save server URL to localStorage. */
function saveServerUrl(url) {
  localStorage.setItem('nebflow_server_url', url);
}

/** Configure the Nebflow backend to use a self-hosted server URL. */
async function configureServerUrl(url) {
  if (!url) return;
  const token = getAuthToken();
  await fetch('/api/mesh/config', {
    method: 'PATCH',
    headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ cloudUrl: url })
  });
}

/** Check login status and show/hide the login gate accordingly. */
export async function checkLoginGate() {
  if (gateChecking) return;
  gateChecking = true;
  await fetchMeshStatus();
  gateChecking = false;

  const gate = document.getElementById('mesh-login-gate');
  if (!gate) return;

  if (meshState.loggedIn) {
    hideLoginGate();
  } else {
    showLoginGate();
    // Pre-fill server URL if saved
    const saved = getSavedServerUrl();
    const urlInput = document.getElementById('gate-server-url');
    if (urlInput && saved) urlInput.value = saved;
  }
}

function showLoginGate() {
  const gate = document.getElementById('mesh-login-gate');
  if (!gate) return;
  gate.classList.remove('hidden');
  bindGateEvents();
}

function hideLoginGate() {
  const gate = document.getElementById('mesh-login-gate');
  if (!gate) return;
  gate.classList.add('hidden');
}

function bindGateEvents() {
  // Tab switching
  document.querySelectorAll('.login-gate-tab').forEach(tab => {
    tab.onclick = () => {
      gateTab = tab.dataset.tab;
      document.querySelectorAll('.login-gate-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      const btn = document.getElementById('gate-submit-btn');
      if (btn) btn.textContent = gateTab === 'login' ? '登录' : '注册';
      const errEl = document.getElementById('gate-error');
      if (errEl) errEl.textContent = '';
    };
  });

  // Submit
  const btn = document.getElementById('gate-submit-btn');
  if (btn && !btn.dataset.bound) {
    btn.dataset.bound = '1';
    btn.onclick = doGateSubmit;
  }

  // Enter key — server URL → username → password → submit
  const urlInput = document.getElementById('gate-server-url');
  if (urlInput && !urlInput.dataset.bound) {
    urlInput.dataset.bound = '1';
    urlInput.onkeydown = (e) => { if (e.key === 'Enter') document.getElementById('gate-username')?.focus(); };
  }
  const passInput = document.getElementById('gate-password');
  if (passInput && !passInput.dataset.bound) {
    passInput.dataset.bound = '1';
    passInput.onkeydown = (e) => { if (e.key === 'Enter') doGateSubmit(); };
  }
  const userInput = document.getElementById('gate-username');
  if (userInput && !userInput.dataset.bound) {
    userInput.dataset.bound = '1';
    userInput.onkeydown = (e) => { if (e.key === 'Enter') document.getElementById('gate-password')?.focus(); };
  }
}

async function doGateSubmit() {
  const serverUrl = document.getElementById('gate-server-url')?.value?.trim();
  const username = document.getElementById('gate-username')?.value?.trim();
  const password = document.getElementById('gate-password')?.value;
  const errEl = document.getElementById('gate-error');
  const btn = document.getElementById('gate-submit-btn');

  if (!serverUrl) {
    if (errEl) errEl.textContent = '请填写服务器地址';
    return;
  }
  if (!username || !password) {
    if (errEl) errEl.textContent = '请输入用户名和密码';
    return;
  }
  if (errEl) errEl.textContent = '';
  if (btn) { btn.disabled = true; btn.textContent = '...'; }

  try {
    // Step 1: save server URL and configure backend
    saveServerUrl(serverUrl);
    await configureServerUrl(serverUrl);

    // Step 2: login or register
    const action = gateTab === 'login' ? 'login' : 'register';
    await meshApi(action, 'POST', { username, password });
    await fetchMeshStatus();
    if (meshState.loggedIn) {
      hideLoginGate();
    } else {
      if (errEl) errEl.textContent = '登录失败，请检查服务器地址和凭据';
    }
  } catch (e) {
    if (errEl) errEl.textContent = e.message;
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = gateTab === 'login' ? '登录' : '注册'; }
  }
}

/** Export for external logout handling — re-shows the gate. */
export function showLoginGateFromLogout() {
  meshState.loggedIn = false;
  showLoginGate();
}

// ---- Inline error display ----
function showMeshError(msg) {
  const el = document.getElementById('mesh-error');
  if (!el) return;
  el.textContent = msg;
  el.style.display = 'block';
}
function hideMeshError() {
  const el = document.getElementById('mesh-error');
  if (el) el.style.display = 'none';
}

// ---- Username availability check (debounced) ----
let usernameCheckTimer = null;
function scheduleUsernameCheck() {
  clearTimeout(usernameCheckTimer);
  const input = document.getElementById('mesh-reg-user');
  if (!input) return;
  usernameCheckTimer = setTimeout(async () => {
    const val = input.value.trim();
    if (val.length < 3) return;
    try {
      const data = await meshApi('check-username?username=' + encodeURIComponent(val));
      const hint = document.getElementById('mesh-username-hint');
      if (hint) {
        if (data.available) {
          hint.textContent = '✓ ' + t('mesh.usernameAvailable');
          hint.style.color = 'var(--color-success)';
        } else {
          hint.textContent = t('mesh.usernameTaken');
          hint.style.color = 'var(--color-error)';
        }
        hint.style.display = 'block';
      }
    } catch {}
  }, 400);
}

// ---- Settings section HTML ----
export function meshSettingsHTML() {
  if (meshState.loggedIn) {
    return meshLoggedInHTML();
  } else if (authTab === 'register') {
    return meshRegisterHTML();
  } else {
    return meshLoginHTML();
  }
}

function meshLoginHTML() {
  return `
    <div class="mesh-auth-form">
      <div id="mesh-error" class="mesh-error" style="display:none"></div>
      <input type="text" id="mesh-login-user" class="cfg-input"
             placeholder="${t('mesh.username')}" autocomplete="username" style="margin-bottom:8px">
      <input type="password" id="mesh-login-pass" class="cfg-input"
             placeholder="${t('mesh.password')}" autocomplete="current-password" style="margin-bottom:8px">
      <button class="cfg-btn" id="mesh-login-btn" style="width:100%">${t('mesh.login')}</button>
      <div class="mesh-auth-switch">
        ${t('mesh.noAccount')} <a href="#" id="mesh-goto-register">${t('mesh.register')}</a>
      </div>
    </div>`;
}

function meshRegisterHTML() {
  return `
    <div class="mesh-auth-form">
      <div id="mesh-error" class="mesh-error" style="display:none"></div>
      <input type="text" id="mesh-reg-user" class="cfg-input"
             placeholder="${t('mesh.username')} (3+)" autocomplete="username" style="margin-bottom:4px">
      <div id="mesh-username-hint" style="display:none;font-size:11px;margin-bottom:6px"></div>
      <input type="password" id="mesh-reg-pass" class="cfg-input"
             placeholder="${t('mesh.password')} (6+)" autocomplete="new-password" style="margin-bottom:8px">
      <button class="cfg-btn" id="mesh-register-btn" style="width:100%">${t('mesh.createAccount')}</button>
      <div class="mesh-auth-switch">
        ${t('mesh.hasAccount')} <a href="#" id="mesh-goto-login">${t('mesh.login')}</a>
      </div>
    </div>`;
}

function meshLoggedInHTML() {
  const allDevices = [
    { ...meshState.device, deviceId: 'local', isLocal: true },
    ...meshState.peers
  ];
  const deviceRows = allDevices.map(d => {
    const caps = d.capabilities ? Object.keys(d.capabilities) : [];
    const desc = d.userDescription || '';
    const capStr = caps.length > 0 ? `<span class="mesh-peer-caps">${caps.join(', ')}</span>` : '';
    const descStr = desc ? `<span class="mesh-peer-desc">${escapeHtml(desc)}</span>` : '';
    return `
    <div class="mesh-peer">
      <span class="mesh-peer-dot dot-on"></span>
      <span class="mesh-peer-name">${escapeHtml(d.deviceName || d.name || d.platform || 'Unknown')}</span>
      ${d.isLocal ? '<span class="mesh-peer-status local-tag">' + t('mesh.thisDevice') + '</span>' : '<span class="mesh-peer-status">' + t('mesh.connected') + '</span>'}
      ${capStr}
      ${descStr}
    </div>`;
  }).join('');

  const localDesc = meshState.device?.userDescription || '';
  const localCaps = meshState.device?.capabilities ? Object.keys(meshState.device.capabilities) : [];
  const capsDisplay = localCaps.length > 0
    ? `<div class="mesh-caps-display">${t('mesh.detectedTools')}: ${localCaps.join(', ')}</div>`
    : '';

  return `
    <div class="mesh-logged-in">
      <div id="mesh-error" class="mesh-error" style="display:none"></div>
      <div class="mesh-user-row">
        <span class="mesh-user-badge">${escapeHtml(meshState.username || '')}</span>
        <button class="cfg-btn mesh-logout-btn" id="mesh-logout">${t('mesh.logout')}</button>
      </div>
      <div class="mesh-section-label">${t('mesh.devices')}</div>
      <div class="mesh-peers-list">${deviceRows}</div>
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
  hideMeshError();
  // Login
  document.getElementById('mesh-login-btn')?.addEventListener('click', () => doLogin(rerender));
  ['mesh-login-user', 'mesh-login-pass'].forEach(id => {
    document.getElementById(id)?.addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(rerender); });
  });
  // Register
  document.getElementById('mesh-register-btn')?.addEventListener('click', () => doRegister(rerender));
  ['mesh-reg-user', 'mesh-reg-pass'].forEach(id => {
    document.getElementById(id)?.addEventListener('keydown', e => { if (e.key === 'Enter') doRegister(rerender); });
  });
  // Username availability check
  document.getElementById('mesh-reg-user')?.addEventListener('input', scheduleUsernameCheck);
  // Tab switching
  document.getElementById('mesh-goto-register')?.addEventListener('click', e => {
    e.preventDefault(); authTab = 'register'; rerender();
  });
  document.getElementById('mesh-goto-login')?.addEventListener('click', e => {
    e.preventDefault(); authTab = 'login'; rerender();
  });
  // Logged in actions
  document.getElementById('mesh-logout')?.addEventListener('click', () => doLogout(rerender));
  document.getElementById('mesh-save-desc')?.addEventListener('click', () => doSaveDescription(rerender));
  document.getElementById('mesh-device-desc')?.addEventListener('keydown', e => { if (e.key === 'Enter') doSaveDescription(rerender); });
}

// ---- Actions ----
async function doLogin(rerender) {
  const username = document.getElementById('mesh-login-user')?.value?.trim();
  const password = document.getElementById('mesh-login-pass')?.value;
  if (!username || !password) { showMeshError(t('mesh.fillBoth')); return; }
  try {
    hideMeshError();
    await meshApi('login', 'POST', { username, password });
    meshState.loggedIn = true;
    meshState.username = username;
    await fetchMeshStatus();
    rerender();
  } catch (e) {
    showMeshError(e.message);
  }
}

async function doRegister(rerender) {
  const username = document.getElementById('mesh-reg-user')?.value?.trim();
  const password = document.getElementById('mesh-reg-pass')?.value;
  if (!username || !password) { showMeshError(t('mesh.fillBoth')); return; }
  if (username.length < 3) { showMeshError(t('mesh.usernameShort')); return; }
  if (password.length < 6) { showMeshError(t('mesh.passwordShort')); return; }
  try {
    hideMeshError();
    await meshApi('register', 'POST', { username, password });
    meshState.loggedIn = true;
    meshState.username = username;
    await fetchMeshStatus();
    rerender();
  } catch (e) {
    showMeshError(e.message);
  }
}

async function doLogout(rerender) {
  try {
    await meshApi('logout', 'POST');
    meshState.loggedIn = false;
    meshState.username = null;
    meshState.peers = [];
    authTab = 'login';
    rerender();
    // Re-show the login gate after logout
    showLoginGate();
  } catch (e) {
    showMeshError(e.message);
  }
}

async function doSaveDescription(rerender) {
  const desc = document.getElementById('mesh-device-desc')?.value?.trim() || '';
  try {
    hideMeshError();
    await meshApi('device-info', 'PUT', { userDescription: desc });
    if (meshState.device) meshState.device.userDescription = desc;
    rerender();
  } catch (e) {
    showMeshError(e.message);
  }
}

// ---- Init (called once from main.js) ----
export function initMesh() {
  checkLoginGate();
}
