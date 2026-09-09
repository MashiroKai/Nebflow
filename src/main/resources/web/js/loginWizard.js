// loginWizard.js — Self-drawn Login Wizard (方案A 玻璃登录自绘向导)
//
// 2026-09-09 design `20260909_login-selfdrawn-design.md` §2.2 (plan A): the
// gateway BFF holds the Logto interaction and drives the Experience API; the
// browser renders ZERO auth.nebflow.space pages. This module is the frontend
// half (S2): a multi-step glass wizard that talks to the BFF's selfdrawn REST
// surface and absorbs the hosted-page entry points E1-E8 (see design §1.1).
//
// Reuses the existing login-modal skeleton (the .nebflow-login-modal glass
// frame from activityBar.js) and the .glass-control / .glass-etched-* tokens
// (sapphire.css) — no new wheel. E9 (logout) is untouched (S0 ops); E10
// (account center) is out of scope (design §3).
//
// Contract source: the S1 backend report REST table. Step routes carry the
// upstream {error, code, upstreamStatus} so the wizard can branch on code;
// submit runs the consent + token orchestration server-side (E4 dissolved);
// the final loopback is resolved server-side, the wizard just renders 200.
//
// 待锁语义 (design §2.3, QA locks with a REAL account; does not block this
// batch): register/forgot pipeline ordering, error-code → inline-text census,
// social query → connectorData mapping. Where uncertain, the code is written
// to surface the upstream code so QA can adjust a single seam.

import { escapeHtml } from './utils.js';
import { t } from './i18n.js';
import {
  getAuthToken,
  getNeblinkState,
  fetchNeblinkStatus,
  startDeviceFlow,
  pollDeviceFlow,
  cancelDeviceFlow,
  pollPkceState,
  cancelPkceFlow,
} from './neblink.js';

// ── Error-code → inline text (provisional; QA real-account locks §2.3 #3) ──
// Logto answers {code, message}; the BFF passes the code through. Known codes
// map to friendly copy; unknown codes fall back to the server describe text.
const CODE_MAP = {
  'session.invalid_credentials': '用户名或密码错误',
  'session.identifier_not_found': '账号不存在，请检查后重试或注册新账号',
  'session.identifier_already_registered': '该邮箱已注册，请直接登录',
  'session.verification_code_invalid': '验证码错误或已过期',
  'session.verification_code_exceed_limit': '验证码发送过于频繁，请稍后重试',
  'guard.invalid_input': '输入格式有误',
  'user.username_already_in_use': '该用户名已被占用',
  'user.email_already_in_use': '该邮箱已被占用',
  'user.password_policy_violation': '密码不符合安全要求（8-256 位，至少包含 3 种字符类型）',
  'session.mfa_required': '需要两步验证',
};
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CODE_SEND_COOLDOWN = 60; // s

/** Map an API error ({code, message}) or fetch TypeError to inline text. */
function mapError(e) {
  if (e && e.code && CODE_MAP[e.code]) return CODE_MAP[e.code];
  if (e instanceof TypeError) return '网络错误，请重试';
  return (e && e.message) || '操作失败，请重试';
}
function isMfaError(e) {
  return !!(e && e.code && String(e.code).toLowerCase().includes('mfa'));
}
function isLogtoNotConfigured(e) {
  return e && e.status === 404 && e.code === 'logto-not-configured';
}

// ── Styles (injected once; self-contained like the old login modal) ────────
let stylesInjected = false;
function injectStyles() {
  if (stylesInjected) return;
  stylesInjected = true;
  const style = document.createElement('style');
  style.textContent = `
.nebflow-login-modal {
  position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%);
  width: 320px; z-index: 1000; overflow: hidden;
  background: var(--glass-bg, rgba(24, 28, 38, 0.68));
  -webkit-backdrop-filter: blur(var(--glass-blur, 24px)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur, 24px)) saturate(1.15);
  border: 1px solid var(--glass-border, rgba(255,255,255,0.1));
  border-radius: 16px;
  box-shadow:
    inset 0 1px 0 0 rgba(255, 255, 255, 0.25),
    0px 2px 8px rgba(0, 0, 0, 0.04),
    0px 8px 24px rgba(0, 0, 0, 0.06);
  animation: nebflowLoginIn 0.2s ease;
}
@media (prefers-color-scheme: dark) {
  .nebflow-login-modal {
    box-shadow:
      inset 0 1px 0 0 rgba(255, 255, 255, 0.04),
      0px 2px 8px rgba(0, 0, 0, 0.20),
      0px 8px 24px rgba(0, 0, 0, 0.35);
  }
}
.nebflow-login-modal::before {
  content: '';
  position: absolute; top: 0; left: 10%; right: 10%; height: 1px;
  background: linear-gradient(90deg, transparent 10%, var(--sapphire-refraction, rgba(255,255,255,0.45)) 50%, transparent 90%);
  pointer-events: none; z-index: 1;
}
@keyframes nebflowLoginIn {
  from { opacity: 0; transform: translate(-50%, -50%) scale(0.96) translateY(8px); }
  to   { opacity: 1; transform: translate(-50%, -50%) scale(1) translateY(0); }
}
.login-modal-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 16px; border-bottom: 1px solid var(--glass-border, rgba(255,255,255,0.05));
}
.login-modal-header h3 { font-size: 14px; margin: 0; color: var(--color-text); font-weight: 600; letter-spacing: -0.01em; }
.login-modal-close {
  background: none; border: none; color: var(--color-text-muted);
  font-size: 18px; cursor: pointer; padding: 0 4px; line-height: 1;
  opacity: 0.5; transition: opacity 0.15s;
}
.login-modal-close:hover { opacity: 1; }
.login-modal-body { padding: 16px; text-align: center; }
.login-hint { font-size: 12px; color: var(--color-text-muted); line-height: 1.5; margin-bottom: 12px; text-align: left; }
.login-user-code {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 26px; font-weight: 600; letter-spacing: 2px;
  color: var(--color-primary); margin-bottom: 4px;
  font-variant-numeric: tabular-nums;
}
.login-code-caption { font-size: 12px; color: var(--color-text-muted); margin-bottom: 14px; }
.login-waiting { margin-top: 12px; font-size: 12px; color: var(--color-text-muted); }
.login-success { font-size: 14px; color: var(--color-primary); padding: 12px 0; }
/* ── Wizard form fields (etched inputs, sapphire.css tokens) ────────────── */
.login-label {
  font-size: 12px; color: var(--color-text-muted);
  margin-bottom: 6px; text-align: left; font-weight: 500;
}
.login-input {
  width: 100%; background: var(--glass-etched-bg);
  color: var(--color-frame-input-text);
  border: 1px solid var(--glass-etched-border);
  border-radius: 8px; padding: 9px 12px; font-size: 13px;
  outline: none; box-sizing: border-box; text-align: left;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.login-input:focus {
  border-color: rgba(91, 127, 191, 0.5);
  box-shadow: 0 0 0 2px rgba(91, 127, 191, 0.08), 0 0 0 4px rgba(91, 127, 191, 0.04);
}
@media (prefers-color-scheme: dark) {
  .login-input:focus {
    border-color: rgba(91, 127, 191, 0.4);
    box-shadow: 0 0 0 2px rgba(91, 127, 191, 0.12), 0 0 0 4px rgba(91, 127, 191, 0.06);
  }
}
.login-input::placeholder { color: var(--color-text-muted); opacity: 0.7; }
.login-row { display: flex; gap: 8px; }
.login-row .login-input { flex: 1; min-width: 0; }
/* ── Buttons (glass-control material; primary = restrained sapphire CTA) ── */
.login-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 100%; padding: 10px 16px; border-radius: 10px;
  font-size: 13px; font-weight: 500; color: var(--color-text);
  cursor: pointer; transition: filter 0.15s, background 0.15s, box-shadow 0.15s;
  box-sizing: border-box;
}
.login-btn.glass-control {
  background: var(--glass-control-bg);
  -webkit-backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  border: 1px solid var(--glass-control-border);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge),
    0 1px 3px var(--glass-control-glow),
    0 2px 8px var(--glass-control-shadow);
}
.login-btn.glass-control:hover:not(:disabled) {
  background: var(--glass-control-bg-hover);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight-hover),
    inset 0 -1px 0 var(--glass-control-underedge),
    0 2px 8px var(--glass-control-glow-hover),
    0 4px 12px var(--glass-control-shadow-hover);
}
.login-btn-primary {
  color: rgb(var(--sapphire));
  font-weight: 600;
}
.login-btn-primary:not(:disabled) { background: rgb(var(--sapphire) / 0.12); }
.login-btn-primary:not(:disabled):hover {
  background: rgb(var(--sapphire) / 0.18);
  color: rgb(var(--sapphire));
  filter: none;
}
.login-btn[disabled] { opacity: 0.55; cursor: default; }
.login-btn[disabled]:hover { filter: none; }
/* Inline (in-row) variant: .login-btn is full-width by default — the send-code
   button shares a row with its input and must size to content instead. */
.login-btn.login-inline-btn {
  width: auto; flex-shrink: 0;
  padding: 8px 12px; font-size: 12px; white-space: nowrap;
}
.login-social { display: flex; gap: 8px; }
.login-social-btn { flex: 1; font-size: 12px; }
.login-social-btn:not(:disabled) { background: var(--glass-control-bg); }
.login-form-links {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 12px;
}
.login-link {
  background: none; border: none; color: rgb(var(--sapphire));
  font-size: 12px; font-weight: 500; cursor: pointer;
  padding: 2px 4px; border-radius: 6px; transition: opacity 0.15s;
  text-align: left;
}
.login-link:hover { opacity: 0.75; text-decoration: underline; }
.login-divider {
  display: flex; align-items: center; gap: 10px;
  color: var(--color-text-muted); font-size: 11px; margin: 14px 0 12px;
}
.login-divider::before, .login-divider::after {
  content: ''; flex: 1; height: 1px; background: var(--glass-border);
}
.login-error {
  background: rgba(244, 67, 54, 0.08);
  border: 1px solid rgba(244, 67, 54, 0.25);
  color: var(--color-error); border-radius: 8px;
  padding: 8px 10px; font-size: 12px; margin-bottom: 12px;
  line-height: 1.5; text-align: left;
}
.login-muted { font-size: 12px; color: var(--color-text-muted); text-align: left; }
`;
  document.head.appendChild(style);
}

// ── Popup-blocker resilience (moved from activityBar.js — login flow only) ──
let reservedPopup = null;
function reservePopup() {
  try { reservedPopup = window.open('about:blank', '_blank'); } catch (e) { reservedPopup = null; }
  return reservedPopup;
}
function navigateReserved(url) {
  const w = reservedPopup;
  reservedPopup = null;
  if (!w) return false;
  try {
    if (url) { w.location.href = url; return true; }
    w.close();
  } catch (e) { /* cross-origin or already closed */ }
  return false;
}
function popupBlockedFallback(url) {
  const toast = document.createElement('div');
  toast.className = 'nebflow-toast nebflow-toast-info';
  const msg = document.createElement('span');
  msg.textContent = t('login.popupBlocked');
  const a = document.createElement('a');
  a.className = 'glass-control nebflow-toast-link';
  a.href = url;
  a.target = '_blank';
  a.rel = 'noopener';
  a.textContent = t('login.openPage');
  toast.append(msg, a);
  document.body.appendChild(toast);
  requestAnimationFrame(() => toast.classList.add('show'));
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 300);
  }, 8000);
}

// ── Selfdrawn BFF API ─────────────────────────────────────────────────────
async function selfdrawnApi(path, body) {
  const resp = await fetch('/api/neblink/auth/selfdrawn/' + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getAuthToken() },
    body: JSON.stringify(body || {}),
  });
  let data = {};
  try { data = await resp.json(); } catch (_) { data = {}; }
  if (!resp.ok) {
    // Augmented error: BFF error bodies carry {error, code, upstreamStatus}
    // (S1 contract) — the wizard branches on `code` (mfa/not-configured).
    const err = /** @type {Error & {code?: string, status?: number, upstreamStatus?: number}} */ (new Error(data.error || '操作失败，请重试'));
    err.code = data.code || '';
    err.status = resp.status;
    err.upstreamStatus = data.upstreamStatus;
    throw err;
  }
  return data;
}

/** Open/open-to the BFF interaction event; the BFF re-acquires the cookie
    jar + PKCE verifier/state for a fresh interaction. */
async function startInteraction(event) {
  await selfdrawnApi('start', { interactionEvent: event });
  state.interactionEvent = event;
}

// ── Wizard state ──────────────────────────────────────────────────────────
let modal = null;
let opts = null;
let state = null;
let finished = false;
let _countdownTimer = null;

function freshState() {
  return {
    step: 'starting',          // starting | identifier | password | code | register | forgot | mfa | waiting | device | success | error
    interactionEvent: 'SignIn',
    identifier: { type: 'username', value: '' },
    register: { email: '', username: '' },
    forgot: { email: '' },
    registerFlags: { codeSent: false, codeVerified: false, identified: false, usernameDone: false, passwordDone: false },
    forgotFlags: { codeSent: false, codeVerified: false, passwordDone: false },
    verificationId: '',
    codeSent: false,
    mfa: { pending: false },
    deviceFlow: null,
    provider: '',
    error: '',
    busy: false,
    countdown: 0,
  };
}

function setPairing(v) { opts.onPairingChange?.(v); }

function clearTimers() {
  cancelPkceFlow();
  cancelDeviceFlow();
  if (_countdownTimer) { clearInterval(_countdownTimer); _countdownTimer = null; }
}

// ── Re-render ──────────────────────────────────────────────────────────────
function headerTitle() {
  switch (state.step) {
    case 'register': return '注册 nebflow 账号';
    case 'forgot': return '重置密码';
    case 'mfa': return '两步验证';
    case 'code': return '验证码登录';
    case 'password': return '登录 nebflow 账号';
    default: return '登录 nebflow 账号';
  }
}
function errorHtml() {
  return state.error ? `<div class="login-error">${escapeHtml(state.error)}</div>` : '';
}
function sendBtnText() {
  return state.countdown > 0 ? `${state.countdown}s` : '发送验证码';
}

function stepBody() {
  const e = errorHtml();
  switch (state.step) {
    case 'starting':
      return `<div class="login-waiting" style="margin-top:0;padding:12px 0">正在启动登录…</div>`;
    case 'identifier':
      return e + `
        <div class="login-label">用户名或邮箱</div>
        <input class="login-input" id="login-identifier" aria-label="用户名或邮箱" type="text" value="${escapeHtml(state.identifier.value)}" placeholder="username 或 you@example.com" autocomplete="username" />
        <button class="login-btn glass-control login-btn-primary" id="login-continue" style="margin-top:10px">继续</button>
        <div class="login-form-links">
          <button class="login-link" id="login-register-link">注册新账号</button>
          <button class="login-link" id="login-forgot-link">忘记密码</button>
        </div>
        <div class="login-divider">或</div>
        <div class="login-social">
          <button class="login-btn login-social-btn glass-control" id="login-gh">使用 GitHub 登录</button>
          <button class="login-btn login-social-btn glass-control" id="login-google">使用 Google 登录</button>
        </div>`;
    case 'password':
      return e + `
        <div class="login-muted" style="margin-bottom:8px">登录 ${escapeHtml(state.identifier.value)}</div>
        <input class="login-input" id="login-password" aria-label="密码" type="password" placeholder="密码" autocomplete="current-password" />
        <button class="login-btn glass-control login-btn-primary" id="login-submit-password" style="margin-top:10px">登录</button>
        <div class="login-form-links">
          <button class="login-link" id="login-password-modify">修改账号</button>
          ${state.identifier.type === 'email' ? `<button class="login-link" id="login-code-switch">验证码登录</button>` : ''}
          <button class="login-link" id="login-forgot-link">忘记密码</button>
        </div>`;
    case 'code':
      return e + `
        <div class="login-hint" style="margin-bottom:8px">验证码将发送至 ${escapeHtml(state.identifier.value)}</div>
        <div class="login-row">
          <input class="login-input" id="login-code" aria-label="验证码" type="text" inputmode="numeric" placeholder="6 位验证码" autocomplete="one-time-code" />
          <button class="login-btn login-inline-btn glass-control" id="login-send-code" ${state.countdown > 0 ? 'disabled' : ''}>${sendBtnText()}</button>
        </div>
        <button class="login-btn glass-control login-btn-primary" id="login-submit-code" style="margin-top:10px">登录</button>
        <button class="login-link" id="login-code-back" style="display:block;width:100%;margin-top:10px;text-align:center">返回</button>`;
    case 'register':
      return e + `
        <div class="login-label">邮箱</div>
        <div class="login-row">
          <input class="login-input" id="login-re-email" aria-label="注册邮箱" type="email" value="${escapeHtml(state.register.email)}" placeholder="you@example.com" autocomplete="email" />
          <button class="login-btn login-inline-btn glass-control" id="login-re-send" ${state.countdown > 0 ? 'disabled' : ''}>${sendBtnText()}</button>
        </div>
        <div class="login-label" style="margin-top:10px">邮箱验证码</div>
        <input class="login-input" id="login-re-code" aria-label="邮箱验证码" type="text" inputmode="numeric" placeholder="6 位验证码" autocomplete="one-time-code" />
        <div class="login-label" style="margin-top:10px">用户名</div>
        <input class="login-input" id="login-re-username" aria-label="用户名" type="text" value="${escapeHtml(state.register.username)}" placeholder="登录用户名" autocomplete="username" />
        <div class="login-label" style="margin-top:10px">密码</div>
        <input class="login-input" id="login-re-password" aria-label="密码" type="password" placeholder="8-256 位" autocomplete="new-password" />
        <div class="login-label" style="margin-top:10px">确认密码</div>
        <input class="login-input" id="login-re-password2" aria-label="确认密码" type="password" placeholder="再次输入密码" autocomplete="new-password" />
        <div class="login-hint" style="margin-top:4px">8-256 位，至少包含 3 种字符类型（大小写字母、数字、符号）</div>
        <button class="login-btn glass-control login-btn-primary" id="login-re-submit" style="margin-top:12px">注册并登录</button>
        <button class="login-link" id="login-re-back" style="display:block;width:100%;margin-top:10px;text-align:center">返回登录</button>`;
    case 'forgot':
      return e + `
        <div class="login-hint" style="margin-bottom:8px">输入注册邮箱，我们将发送验证码以重置密码</div>
        <div class="login-label">邮箱</div>
        <div class="login-row">
          <input class="login-input" id="login-fp-email" aria-label="注册邮箱" type="email" value="${escapeHtml(state.forgot.email)}" placeholder="you@example.com" autocomplete="email" />
          <button class="login-btn login-inline-btn glass-control" id="login-fp-send" ${state.countdown > 0 ? 'disabled' : ''}>${sendBtnText()}</button>
        </div>
        <div class="login-label" style="margin-top:10px">邮箱验证码</div>
        <input class="login-input" id="login-fp-code" aria-label="邮箱验证码" type="text" inputmode="numeric" placeholder="6 位验证码" autocomplete="one-time-code" />
        <div class="login-label" style="margin-top:10px">新密码</div>
        <input class="login-input" id="login-fp-password" aria-label="新密码" type="password" placeholder="8-256 位" autocomplete="new-password" />
        <div class="login-label" style="margin-top:10px">确认新密码</div>
        <input class="login-input" id="login-fp-password2" aria-label="确认新密码" type="password" placeholder="再次输入密码" autocomplete="new-password" />
        <button class="login-btn glass-control login-btn-primary" id="login-fp-submit" style="margin-top:12px">重置密码并登录</button>
        <button class="login-link" id="login-fp-back" style="display:block;width:100%;margin-top:10px;text-align:center">返回登录</button>`;
    case 'mfa':
      return e + `
        <div class="login-hint" style="margin-bottom:6px">此账号已开启两步验证（TOTP），请输入验证器 App 中的 6 位数字</div>
        <input class="login-input" id="login-mfa-code" aria-label="两步验证码" type="text" inputmode="numeric" placeholder="6 位数字" autocomplete="one-time-code" />
        <button class="login-btn glass-control login-btn-primary" id="login-mfa-submit" style="margin-top:10px">验证</button>
        <button class="login-link" id="login-mfa-back" style="display:block;width:100%;margin-top:10px;text-align:center">返回</button>`;
    case 'waiting':
      return `
        <div class="login-waiting" style="margin-top:0;padding:12px 0">等待 ${escapeHtml(state.provider)} 授权完成…</div>
        <button class="login-link" id="login-wait-cancel" style="display:block;width:100%;margin-top:8px;text-align:center">取消</button>`;
    case 'device':
      return `
        <div class="login-hint">在浏览器中完成授权以连接此设备</div>
        <div class="login-user-code">${escapeHtml(state.deviceFlow?.userCode || '')}</div>
        <div class="login-code-caption">授权码</div>
        <button class="login-btn glass-control" id="login-device-open">打开授权页面</button>
        <div class="login-waiting">等待授权完成…</div>
        <button class="login-link" id="login-device-back" style="display:block;width:100%;margin-top:8px;text-align:center">返回</button>`;
    case 'success':
      return `<div class="login-success">✓ 登录成功，设备已加入网络</div>`;
    case 'error':
      return `
        <div class="login-error">${escapeHtml(state.error)}</div>
        <button class="login-btn glass-control" id="login-error-retry">重试</button>
        <button class="login-link" id="login-error-back" style="display:block;width:100%;margin-top:10px;text-align:center">返回</button>`;
    default:
      return '';
  }
}

function render() {
  if (!modal) return;
  modal.innerHTML = `
    <div class="login-modal-header">
      <h3>${escapeHtml(headerTitle())}</h3>
      <button class="login-modal-close">&times;</button>
    </div>
    <div class="login-modal-body">${stepBody()}</div>`;
  bindStep();
  const closeBtn = modal.querySelector('.login-modal-close');
  if (closeBtn) closeBtn.addEventListener('click', closeWizard);
}

// ── Countdown (in-place on the send button; does not re-render entire step) ─
function startCountdown(seconds = CODE_SEND_COOLDOWN) {
  state.countdown = seconds;
  if (_countdownTimer) clearInterval(_countdownTimer);
  syncSendButton();
  _countdownTimer = setInterval(() => {
    state.countdown -= 1;
    if (state.countdown <= 0) {
      state.countdown = 0;
      clearInterval(_countdownTimer);
      _countdownTimer = null;
    }
    syncSendButton();
  }, 1000);
}
function syncSendButton() {
  const btn = modal && (modal.querySelector('#login-send-code') || modal.querySelector('#login-re-send') || modal.querySelector('#login-fp-send'));
  if (!btn) return;
  btn.textContent = sendBtnText();
  btn.disabled = state.countdown > 0;
}
function findSendButton() {
  return modal && (modal.querySelector('#login-send-code') || modal.querySelector('#login-re-send') || modal.querySelector('#login-fp-send'));
}

// ── Busy helper (targeted button; does not re-render on state.busy) ────────
async function withBusy(btn, busyText, fn, restore = true) {
  if (state.busy) return;
  state.busy = true;
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = busyText;
  try { await fn(); } finally {
    state.busy = false;
    if (restore && btn && btn.isConnected) { btn.disabled = false; btn.textContent = orig; }
  }
}

// ── Terminal helpers ──────────────────────────────────────────────────────
async function onSuccess() {
  setPairing(false);
  finished = true;
  state.step = 'success';
  render();
  await fetchNeblinkStatus();
  opts.onLoginDone?.();
  setTimeout(() => { close(); }, 1500);
}
function onErrorStep(msg) {
  setPairing(false);
  state.error = msg;
  state.step = 'error';
  render();
}

/** Submit + consent orchestration is server-side; here we just render the
    outcome. A 422 mfa code → drive the TOTP wizard step then re-submit. */
async function submitOrMfa() {
  try {
    await selfdrawnApi('submit', {});
    await onSuccess();
  } catch (e) {
    if (isMfaError(e)) {
      state.mfa.pending = true;
      state.error = '';
      state.step = 'mfa';
      render();
    } else {
      throw e; // let the calling pipeline surface mapError
    }
  }
}

// ── Step bindings ─────────────────────────────────────────────────────────
function bindStep() {
  const on = (sel, handler) => modal.querySelector(sel)?.addEventListener('click', handler);
  switch (state.step) {
    case 'identifier':
      on('#login-continue', onIdentifierContinue);
      on('#login-register-link', () => switchInteraction('Register', 'register'));
      on('#login-forgot-link', () => switchInteraction('ForgotPassword', 'forgot'));
      on('#login-gh', () => onSocial('github', 'GitHub'));
      on('#login-google', () => onSocial('google', 'Google'));
      break;
    case 'password':
      on('#login-submit-password', onSubmitPassword);
      on('#login-password-modify', backToIdentifier);
      on('#login-code-switch', () => toCodeStep());
      on('#login-forgot-link', () => switchInteraction('ForgotPassword', 'forgot'));
      break;
    case 'code':
      on('#login-send-code', () => onSendCode());
      on('#login-submit-code', onSubmitCode);
      on('#login-code-back', backToIdentifier);
      break;
    case 'register':
      on('#login-re-send', () => onSendCode());
      on('#login-re-submit', onRegisterSubmit);
      on('#login-re-back', () => switchInteraction('SignIn', 'identifier'));
      break;
    case 'forgot':
      on('#login-fp-send', () => onSendCode());
      on('#login-fp-submit', onForgotSubmit);
      on('#login-fp-back', () => switchInteraction('SignIn', 'identifier'));
      break;
    case 'mfa':
      on('#login-mfa-submit', onMfaSubmit);
      on('#login-mfa-back', backToIdentifier);
      break;
    case 'waiting':
      on('#login-wait-cancel', () => { cancelPkceFlow(); backToIdentifier(); });
      break;
    case 'device':
      on('#login-device-open', openDevicePage);
      on('#login-device-back', backToIdentifier);
      break;
    case 'error':
      on('#login-error-retry', () => { state.error = ''; switchInteraction('SignIn', 'identifier'); });
      on('#login-error-back', () => { state.error = ''; switchInteraction('SignIn', 'identifier'); });
      break;
  }
}

// ── Flow handlers ─────────────────────────────────────────────────────────
function backToIdentifier() {
  clearTimers();
  state.step = 'identifier';
  state.error = '';
  state.codeSent = false;
  state.verificationId = '';
  resetFlowFlags();
  render();
}
function resetFlowFlags() {
  state.registerFlags = { codeSent: false, codeVerified: false, identified: false, usernameDone: false, passwordDone: false };
  state.forgotFlags = { codeSent: false, codeVerified: false, passwordDone: false };
}

/** Switch interaction mode; opens a FRESH BFF interaction for the target
    event (the start route re-acquires jar + PKCE verifier/state). Flow
    flags reset too — re-entering a mode must never inherit the previous
    attempt's pipeline progress. */
async function switchInteraction(event, targetStep) {
  resetFlowFlags();
  state.verificationId = '';
  state.codeSent = false;
  setPairing(true);
  state.error = '';
  state.step = 'starting';
  render();
  try {
    await startInteraction(event);
    state.step = targetStep;
    render();
  } catch (e) {
    if (isLogtoNotConfigured(e)) { await startDeviceFlowFallback(); }
    else { onErrorStep(mapError(e)); }
  } finally {
    setPairing(false);
  }
}

function onIdentifierContinue() {
  const val = getInput('login-identifier').trim();
  if (!val) {
    state.error = '请输入用户名或邮箱';
    render();
    return;
  }
  state.identifier.value = val;
  state.identifier.type = val.includes('@') ? 'email' : 'username';
  state.error = '';
  state.step = 'password';
  render();
}
function toCodeStep() {
  state.step = 'code';
  state.error = '';
  state.codeSent = false;
  state.verificationId = '';
  render();
}
function getInput(id) {
  const el = modal.querySelector('#' + id);
  return el ? el.value : '';
}

async function onSubmitPassword() {
  const pw = getInput('login-password');
  const idv = state.identifier.value;
  if (!idv || !pw) { state.error = '请输入用户名或邮箱和密码'; render(); return; }
  const btn = modal.querySelector('#login-submit-password');
  withBusy(btn, '登录中…', async () => {
    try {
      const v = await selfdrawnApi('password', { identifier: { type: state.identifier.type, value: idv }, password: pw });
      state.verificationId = v.verificationId || '';
      await selfdrawnApi('identification', { verificationId: state.verificationId });
      await submitOrMfa();
    } catch (e) {
      state.error = mapError(e);
      render();
    }
  });
}

async function onSubmitCode() {
  const code = getInput('login-code');
  if (!code) { state.error = '请输入验证码'; render(); return; }
  const btn = modal.querySelector('#login-submit-code');
  withBusy(btn, '登录中…', async () => {
    try {
      if (!state.codeSent) {
        const s = await selfdrawnApi('verification-code', { interactionEvent: 'SignIn', identifier: { type: 'email', value: state.identifier.value } });
        state.verificationId = s.verificationId || '';
        state.codeSent = true;
      }
      const v = await selfdrawnApi('verification-code/verify', { verificationId: state.verificationId, code });
      state.verificationId = v.verificationId || state.verificationId;
      await selfdrawnApi('identification', { verificationId: state.verificationId });
      await submitOrMfa();
    } catch (e) {
      state.error = mapError(e);
      render();
    }
  });
}

/** Send a verification code (shared by code/register/forgot steps). The
    button is driven by the countdown (no restore in finally). */
async function onSendCode() {
  if (state.busy) return; // re-entry guard (this handler manages its own button)
  let email, event;
  if (state.step === 'register') {
    email = getInput('login-re-email').trim();
    event = 'Register';
  } else if (state.step === 'forgot') {
    email = getInput('login-fp-email').trim();
    event = 'ForgotPassword';
  } else {
    email = state.identifier.value;
    event = 'SignIn';
  }
  if (!email || !EMAIL_RE.test(email)) { state.error = '请输入有效的邮箱地址'; render(); return; }
  if (state.step === 'register') state.register.email = email;
  if (state.step === 'forgot') state.forgot.email = email;
  const btn = findSendButton();
  const orig = btn ? btn.textContent : '';
  if (btn) { btn.disabled = true; btn.textContent = '发送中…'; }
  try {
    const s = await selfdrawnApi('verification-code', { interactionEvent: event, identifier: { type: 'email', value: email } });
    state.verificationId = s.verificationId || '';
    state.codeSent = true;
    // Per-flow sent flags: the register/forgot pipelines key off these so the
    // submit click never re-sends the code the send button just sent.
    if (state.step === 'register') state.registerFlags.codeSent = true;
    if (state.step === 'forgot') state.forgotFlags.codeSent = true;
    state.error = '';
    startCountdown();
  } catch (e) {
    state.error = mapError(e);
    render();
  }
}

async function onRegisterSubmit() {
  const email = getInput('login-re-email').trim();
  const code = getInput('login-re-code').trim();
  const username = getInput('login-re-username').trim();
  const pw = getInput('login-re-password');
  const pw2 = getInput('login-re-password2');
  if (!email || !EMAIL_RE.test(email)) { state.error = '请输入有效的邮箱地址'; render(); return; }
  if (!code) { state.error = '请输入邮箱验证码'; render(); return; }
  if (!username) { state.error = '请输入用户名'; render(); return; }
  if (!pw) { state.error = '请输入密码'; render(); return; }
  if (pw !== pw2) { state.error = '两次输入的密码不一致'; render(); return; }
  state.register.email = email;
  state.register.username = username;
  const flags = state.registerFlags;
  const btn = modal.querySelector('#login-re-submit');
  withBusy(btn, '注册中…', async () => {
    try {
      if (!flags.codeSent) {
        const s = await selfdrawnApi('verification-code', { interactionEvent: 'Register', identifier: { type: 'email', value: email } });
        state.verificationId = s.verificationId || '';
        flags.codeSent = true;
      }
      if (!flags.codeVerified) {
        const v = await selfdrawnApi('verification-code/verify', { verificationId: state.verificationId, code });
        state.verificationId = v.verificationId || state.verificationId;
        flags.codeVerified = true;
      }
      if (!flags.identified) {
        await selfdrawnApi('identification', { verificationId: state.verificationId });
        flags.identified = true;
      }
      if (!flags.usernameDone) {
        await selfdrawnApi('profile', { type: 'username', value: username });
        flags.usernameDone = true;
      }
      if (!flags.passwordDone) {
        await selfdrawnApi('profile', { type: 'password', value: pw });
        flags.passwordDone = true;
      }
      await submitOrMfa();
    } catch (e) {
      state.error = mapError(e);
      render();
    }
  });
}

async function onForgotSubmit() {
  const email = getInput('login-fp-email').trim();
  const code = getInput('login-fp-code').trim();
  const pw = getInput('login-fp-password');
  const pw2 = getInput('login-fp-password2');
  if (!email || !EMAIL_RE.test(email)) { state.error = '请输入有效的邮箱地址'; render(); return; }
  if (!code) { state.error = '请输入邮箱验证码'; render(); return; }
  if (!pw) { state.error = '请输入新密码'; render(); return; }
  if (pw !== pw2) { state.error = '两次输入的密码不一致'; render(); return; }
  state.forgot.email = email;
  const flags = state.forgotFlags;
  const btn = modal.querySelector('#login-fp-submit');
  withBusy(btn, '重置中…', async () => {
    try {
      if (!flags.codeSent) {
        const s = await selfdrawnApi('verification-code', { interactionEvent: 'ForgotPassword', identifier: { type: 'email', value: email } });
        state.verificationId = s.verificationId || '';
        flags.codeSent = true;
      }
      if (!flags.codeVerified) {
        const v = await selfdrawnApi('verification-code/verify', { verificationId: state.verificationId, code });
        state.verificationId = v.verificationId || state.verificationId;
        flags.codeVerified = true;
      }
      if (!flags.passwordDone) {
        await selfdrawnApi('profile', { type: 'password', value: pw });
        flags.passwordDone = true;
      }
      await submitOrMfa();
    } catch (e) {
      state.error = mapError(e);
      render();
    }
  });
}

async function onMfaSubmit() {
  const code = getInput('login-mfa-code');
  if (!code) { state.error = '请输入 6 位验证码'; render(); return; }
  const btn = modal.querySelector('#login-mfa-submit');
  withBusy(btn, '验证中…', async () => {
    try {
      await selfdrawnApi('mfa/totp', { code });
      await submitOrMfa();
    } catch (e) {
      state.error = mapError(e);
      render();
    }
  });
}

function onSocial(target, label) {
  reservePopup(); // synchronous gesture reservation
  state.provider = label;
  state.error = '';
  state.step = 'waiting';
  setPairing(true);
  render();
  (async () => {
    try {
      const r = await selfdrawnApi('social', { target });
      const url = r.redirectTo;
      if (!url) throw new Error('未获取到授权地址');
      if (!navigateReserved(url)) popupBlockedFallback(url);
      pollPkceState(onSuccess, onErrorStep);
    } catch (e) {
      onErrorStep(mapError(e));
    }
  })();
}

function openDevicePage() {
  const df = state.deviceFlow;
  if (!df) return;
  window.open(df.verificationUri, '_blank', 'noopener');
  pollDeviceFlow(df.deviceCode, df.interval || 3, df.expiresIn || 900, onSuccess, onErrorStep);
}

async function startDeviceFlowFallback() {
  setPairing(true);
  state.step = 'starting';
  render();
  try {
    const df = await startDeviceFlow();
    state.deviceFlow = df;
    state.step = 'device';
    render();
  } catch (e) {
    onErrorStep(e.message || '启动登录失败');
  } finally {
    setPairing(false);
  }
}

// ── Boot ──────────────────────────────────────────────────────────────────
async function boot() {
  setPairing(true);
  try {
    await startInteraction('SignIn');
    state.step = 'identifier';
    render();
  } catch (e) {
    if (isLogtoNotConfigured(e)) { await startDeviceFlowFallback(); }
    else onErrorStep(mapError(e));
  } finally {
    setPairing(false);
  }
}

// ── Public entry ──────────────────────────────────────────────────────────
/**
 * Open the self-drawn login wizard. Replaces the old hosted-page login modal.
 * @param {{ onPairingChange?: (v:boolean)=>void, onLoginDone?: ()=>void }} [o]
 */
export function openLoginWizard(o = {}) {
  if (document.getElementById('nebflow-login-modal')) return;
  opts = { onPairingChange: () => {}, onLoginDone: () => {}, ...o };
  finished = false;
  injectStyles();

  modal = document.createElement('div');
  modal.id = 'nebflow-login-modal';
  modal.className = 'nebflow-login-modal';
  document.body.appendChild(modal);

  state = freshState();
  render();

  document.addEventListener('keydown', closeOnEscape);
  // Outside click closes — CAPTURE phase + isConnected guard. Both matter:
  // wizard buttons render the next step SYNCHRONOUSLY (innerHTML replaces
  // the node the click started on); by the time a bubble-phase document
  // listener ran, e.target was already DETACHED and modal.contains() was
  // false — the wizard closed itself on every step advance. Capture runs
  // before the target handler (target still attached), and the isConnected
  // guard is the belt: a detached target can only be a mid-render bubble.
  setTimeout(() => {
    document.addEventListener('click', function outside(e) {
      const target = /** @type {Element|null} */ (e.target);
      if (!document.body.contains(modal)) {
        document.removeEventListener('click', outside, true);
      } else if (!modal.contains(target) && target && target.isConnected) {
        closeWizard();
        document.removeEventListener('click', outside, true);
      }
    }, true);
  }, 100);

  boot();
}

function closeOnEscape(e) { if (e.key === 'Escape') closeWizard(); }

function closeWizard() {
  if (!modal) return;
  // clearTimers is safe post-success too (the poll timers are already done;
  // the countdown would otherwise tick against a detached modal for ≤60s).
  clearTimers();
  if (!finished) setPairing(false);
  document.removeEventListener('keydown', closeOnEscape);
  // The outside-click listener self-removes on its next fire (the modal is
  // gone by then), so no explicit removal is needed here.
  modal.remove();
  modal = null;
}
