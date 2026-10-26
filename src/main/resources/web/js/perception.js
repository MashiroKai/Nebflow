/*
 * perception.js — 感知域前端（mail-perception-impl 批）
 *
 * 三脸：① 感知面板（条目化 + 两问配置卡） ② OTP 单码片（ask 卡内）
 *      ③ 快照卡（ask 卡内感知草稿面）+ Canvas 邮件编辑器
 *
 * 纪律：
 *   · 视觉走 stand-tokens.css（官网单源 token）+ perception.css；无 emoji。
 *   · OTP 等待期零 UI（r3 裁决 2）：码到才出码片，点击即填并撤出。
 *   · 快照卡 = 只读快照；发送动作只由用户点击（卡片「照此发送」或画布「发送」）。
 *   · 动态文本一律 textContent / esc()，禁 innerHTML 注入不可信串。
 *   · ask 卡增强经 renderAskUser 注入的单一钩子（window.__perceptionEnhanceAskCard），
 *     对 chat.js 零结构改动（增强缺席 = 行为逐字节现状）。
 */

import { onMessage } from './ws.js';
import { key } from './branding.js';
import { t } from './i18n.js';
import { openTab, isCanvasOpen } from './canvas.js';
import state from './state.js';
import { removePendingAsk } from './askPending.js';
// 鉴权拒绝伞（sessionGate 零静态依赖，本导入无环）。感知面板是控制台最后一个
// 未接伞的 REST 面——修前 401/403 只会把服务端错误体原样印进卡片死底。
import { noteAuthRejected } from './sessionGate.js';

// ── api ─────────────────────────────────────────────────────────────────

function getToken() { return localStorage.getItem(key('token')) || ''; }
function authHeaders() {
  const tok = getToken();
  return tok ? { Authorization: `Bearer ${tok}` } : {};
}

/** JSON API call; resolves { ok, status, data }. Never throws. */
async function api(method, path, body) {
  try {
    const opts = { method, headers: { ...authHeaders(), 'Content-Type': 'application/json' } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const resp = await fetch(path, opts);
    // 会话伞（Missing token 报障批 2026-10-26）：感知域业务面从不出 401/403——
    // 引擎 withAuth 拒 = 403「Unauthorized」+X-Nf-Reason，relay ingress 门拒 =
    // 403「Missing token」无 reason 头（不落隧道帧，引擎日志零痕）；两者都是
    // 会话面死亡 ⇒ 命中即拉 sessionGate（静默重座优先，单飞+60s 冷却防环，
    // 同页多调用并发命中无害化）。WS 活着 + HTTP 403 的盲区由该伞统一收口。
    if (resp.status === 401 || resp.status === 403) noteAuthRejected('perception');
    let data = null;
    try { data = await resp.json(); } catch { data = null; }
    return { ok: resp.ok, status: resp.status, data };
  } catch (e) {
    return { ok: false, status: 0, data: null };
  }
}

/** 失败文案面：鉴权拒绝显示会话引导，不透传裸错误体——修前「Missing token」
 *  原样印进连接卡红字（报障 2026-10-26 12:43）；服务商测连失败等业务错误仍
 *  透传服务端 error 原文（要直达作者）。 */
function authErrMsg(r, fallback) {
  if (r.status === 401 || r.status === 403) return t('perception.sessionExpired');
  return (r.data && r.data.error) || fallback;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

/** 卡面答复：与 chat.js ask 卡同一帧形（answers + requestId；secret 键缺席）。 */
function answerAsk(targetSid, requestId, labels) {
  try {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({
        type: 'askUserAnswer',
        sessionId: targetSid,
        answers: labels,
        ...(requestId && { requestId })
      }));
    }
    if (requestId) removePendingAsk(requestId);
    window.dispatchEvent(new CustomEvent('session-attention', { detail: { sessionId: targetSid, attention: false } }));
  } catch (e) { /* 卡已关闭/WS 断开：静默（与卡面兜底同口径） */ }
}

// ── state ───────────────────────────────────────────────────────────────

let entriesCache = [];
let panelOpen = false;
let panelEl = null;
// windowId → { requestId }（码片事件 → 卡面路由）
const otpWindows = new Map(); // windowId → requestId
// requestId → handler（码片渲染进对应 ask 卡）
const otpCardHandlers = new Map(); // requestId → fn(frame)
// draftId → handler（sent/updated 帧路由到快照卡 / 画布页）
const draftCardHandlers = new Map(); // draftId → fn(frame)

// ── init ────────────────────────────────────────────────────────────────

export function initPerception() {
  onMessage('perception.state.changed', (msg) => {
    if (Array.isArray(msg.entries)) {
      entriesCache = msg.entries;
      if (panelOpen) renderPanel();
    }
  });

  onMessage('perception.otp.code', routePerceptionFrame);
  onMessage('perception.otp.expired', routePerceptionFrame);
  onMessage('perception.draft.sent', routePerceptionFrame);
  onMessage('perception.draft.updated', routePerceptionFrame);

  // ask 卡增强钩子（renderAskUser 注入；缺席 = 零增强）。
  window.__perceptionEnhanceAskCard = enhanceAskCard;

  // 面板入口按钮（挂在 daemon 按钮旁；缺席则挂 header 右端）。
  wirePanelButton();
}

/**
 * WS 帧路由单点（initPerception 的 onMessage 全部走这里；导出给静态
 * harness 用——spec 直接投帧，无需真 WebSocket）。帧契约：
 *   perception.otp.code{windowId,requestId,from,fromDomain,receivedAt}（无码值）
 *   perception.otp.expired{requestId}
 *   perception.draft.sent{draftId,messageId,sentAt}
 *   perception.draft.updated{draftId,...}
 */
export function routePerceptionFrame(msg) {
  if (!msg || !msg.type) return;
  switch (msg.type) {
    case 'perception.otp.code': {
      if (!msg.windowId) return;
      otpWindows.set(msg.windowId, msg.requestId || '');
      const handler = otpCardHandlers.get(msg.requestId);
      if (handler) handler(msg);
      break;
    }
    case 'perception.otp.expired': {
      const handler = msg.requestId ? otpCardHandlers.get(msg.requestId) : null;
      if (handler) handler({ type: 'expired' });
      break;
    }
    case 'perception.draft.sent':
    case 'perception.draft.updated': {
      if (!msg.draftId) return;
      const handler = draftCardHandlers.get(msg.draftId);
      if (handler) handler(msg);
      break;
    }
    default: break; // 未知感知帧：静默（前向兼容）
  }
}

function wirePanelButton() {
  const make = () => {
    const daemonBtn = document.getElementById('daemon-btn');
    const btn = document.createElement('button');
    btn.id = 'perception-btn';
    btn.className = daemonBtn ? daemonBtn.className : '';
    btn.title = t('perception.title');
    btn.setAttribute('aria-label', t('perception.title'));
    btn.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3 8l9 6 9-6"/></svg>';
    btn.addEventListener('click', (e) => { e.stopPropagation(); togglePanel(); });
    if (daemonBtn && daemonBtn.parentNode) daemonBtn.parentNode.insertBefore(btn, daemonBtn);
    else document.body.appendChild(btn);
  };
  if (document.getElementById('daemon-btn') || document.readyState === 'complete') make();
  else window.addEventListener('load', make, { once: true });
}

// ═══════════════════ ① 感知面板 ═══════════════════

function togglePanel() {
  if (panelOpen) { closePanel(); return; }
  openPerceptionPanel();
}

/** 打开感知面板（幂等；导出面 = harness 与未来复用点，行为与 toggle 打开腿一致）。 */
export function openPerceptionPanel() {
  if (!panelEl) {
    panelEl = document.createElement('div');
    panelEl.id = 'perception-panel';
    panelEl.innerHTML = `
      <div class="pp-header">
        <span class="pp-dot" id="pp-dot"></span>
        <span class="pp-title">${esc(t('perception.title'))}</span>
        <div class="pp-header-right">
          <button class="pp-icon-btn" id="pp-close-btn" title="${esc(t('perception.close'))}">
            <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>
          </button>
        </div>
      </div>
      <div class="pp-body" id="pp-body"></div>
      <div class="pp-footer">${esc(t('perception.footerPrivacy'))}</div>`;
    document.body.appendChild(panelEl);
    panelEl.querySelector('#pp-close-btn').addEventListener('click', (e) => { e.stopPropagation(); closePanel(); });
  }
  panelEl.style.display = 'block';
  panelOpen = true;
  positionPanel();
  renderPanel();
  positionPanel();                                   // renderPanel 后体量落定 → 重钳一次（翻转判定用真高）
  api('GET', '/api/perception/entries').then(r => {
    if (r.ok && r.data && Array.isArray(r.data.entries)) { entriesCache = r.data.entries; renderPanel(); }
  });
  window.addEventListener('resize', positionPanel);
  document.addEventListener('click', panelOutsideClose);
}

/** 锚定弹出（作者令 2026-10-26「弹窗从点击的入口按钮锚定」）：面板右缘对齐入口
 *  按钮（#perception-btn）右缘、顶缘贴按钮下缘 +8px；下方放不下（视口底越界）
 *  整体翻到按钮上方；左右按视口 8px 内缝钳制。open 与 resize（开着时）都走这里
 *  ——固定 top56/right16 硬编码退役。 */
function positionPanel() {
  if (!panelEl) return;
  const btn = document.getElementById('perception-btn');
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const r = btn && btn.isConnected ? btn.getBoundingClientRect() : null;
  // 按钮缺席（理论不可达——面板入口即该按钮）：回落旧固定位，零回归。
  if (!r || (!r.width && !r.height)) {
    Object.assign(panelEl.style, { position: 'fixed', top: '56px', right: '16px', left: 'auto', zIndex: 250, display: 'block' });
    return;
  }
  const w = panelEl.offsetWidth || 400;
  const h = panelEl.offsetHeight || panelEl.scrollHeight || 200;
  const GAP = 8, EDGE = 8;
  // 右缘对齐按钮右缘，再双侧内缝钳制（按钮在屏幕左半时不得把面板推出去左侧）
  const right = Math.max(EDGE, Math.min(vw - r.right, vw - w - EDGE));
  let top = r.bottom + GAP;
  if (top + h > vh - EDGE) {
    const above = r.top - GAP - h;                   // 下方越界 → 翻按钮上方
    top = above >= EDGE ? above : Math.max(EDGE, vh - EDGE - h);
  }
  Object.assign(panelEl.style, {
    position: 'fixed', top: Math.round(top) + 'px',
    right: Math.round(right) + 'px', left: 'auto', zIndex: 250, display: 'block'
  });
}

function panelOutsideClose(e) {
  if (panelOpen && panelEl && !panelEl.contains(e.target) && e.target && !e.target.closest('#perception-btn')) closePanel();
}

function closePanel() {
  panelOpen = false;
  if (panelEl) panelEl.style.display = 'none';
  window.removeEventListener('resize', positionPanel);
  document.removeEventListener('click', panelOutsideClose);
}

let openEntryId = null;

function renderPanel() {
  if (!panelEl || !panelOpen) return;
  const body = panelEl.querySelector('#pp-body');
  if (!body) return;
  const dot = panelEl.querySelector('#pp-dot');
  const hasBad = entriesCache.some(en => en.state === 'AuthFailed' || en.state === 'Error');
  const hasWarn = entriesCache.some(en => en.state === 'Backoff' || en.state === 'Degraded');
  dot.className = 'pp-dot' + (hasBad ? ' show bad' : hasWarn ? ' show warn' : '');
  const parts = [];
  if (entriesCache.length === 0) parts.push(entryHtml({ id: '__mail__', icon: 'mail', name: t('perception.mailEntry'), state: 'Unconfigured', summary: t('perception.notConfigured'), notice: '' }));
  for (const en of entriesCache) parts.push(entryHtml(en));
  // 占位条目退役（作者令 2026-10-26「去掉文件/日历即将推出占位」）：面板只承载
  // 真实能力（邮件），结构随内容收敛——r3 假设 6（预留两枚虚位）随之作废。
  body.innerHTML = parts.join('');

  body.querySelectorAll('.pp-entry .pp-entry-head').forEach(head => {
    head.addEventListener('click', () => {
      const entry = head.closest('.pp-entry');
      const wasOpen = entry.classList.contains('open');
      body.querySelectorAll('.pp-entry.open').forEach(x => x.classList.remove('open'));
      if (!wasOpen) { entry.classList.add('open'); openEntryId = entry.dataset.id; renderEntryDetail(entry); }
      else openEntryId = null;
      positionPanel();                                 // 手风琴开合变体量 → 锚定重钳（越界翻转跟手）
    });
  });
  body.querySelectorAll('.pp-entry.open').forEach(renderEntryDetail);
  positionPanel();
}

function entryHtml(en) {
  const colorClass = stateColor(en.state);
  const sum = en.summary || '';
  return `
  <div class="pp-entry ${openEntryId === en.id ? 'open' : ''}" data-id="${esc(en.id)}">
    <button class="pp-entry-head" aria-expanded="${openEntryId === en.id}">
      <span class="pp-e-ic"><svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3 8l9 6 9-6"/></svg></span>
      <span class="pp-e-main">
        <span class="pp-e-name">${esc(en.name)}</span>
        <span class="pp-e-sum">${esc(sum)}</span>
      </span>
      <span class="pp-e-dot ${colorClass}"></span>
      <svg class="pp-e-chev" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>
    </button>
    <div class="pp-e-detail"></div>
  </div>`;
}

function stateColor(state) {
  switch (state) {
    case 'Idle': return 'ok';
    case 'Backoff': case 'Degraded': return 'warn';
    case 'AuthFailed': case 'Error': return 'bad';
    default: return '';
  }
}

function renderEntryDetail(entryEl) {
  const detail = entryEl.querySelector('.pp-e-detail');
  const id = entryEl.dataset.id;
  const en = entriesCache.find(x => x.id === id);
  if (!en) {
    // 未配置占位条目：两步直达 hero
    detail.innerHTML = `
      <div class="pp-setup-hero">
        <svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M3 8l9 6 9-6"/></svg>
        <div class="pp-setup-h">${esc(t('perception.setupTitle'))}</div>
        <div class="pp-setup-p">${esc(t('perception.setupDesc'))}</div>
        <button class="pp-btn primary">${esc(t('perception.connect'))}</button>
      </div>`;
    detail.querySelector('.pp-btn').addEventListener('click', () => openConfigCard(null));
    return;
  }
  const isPaused = en.state === 'Paused';
  const isAuthFail = en.state === 'AuthFailed';
  const isBackoff = en.state === 'Backoff' || en.state === 'Degraded';
  const addr = (en.summary || '').split(' · ')[0] || '';
  const provider = providerOf(addr);
  let html = '';
  if (isAuthFail) {
    html += `
      <div class="pp-alert bad">
        <svg viewBox="0 0 24 24"><path d="M12 3l10 18H2L12 3zM12 10v5M12 18h.01"/></svg>
        <div style="flex:1;min-width:0">
          <div class="ah">${esc(t('perception.authFailTitle'))}</div>
          <div class="ad">${esc(t('perception.authFailDesc'))}</div>
          <div class="abtns">
            <button class="pp-btn primary" data-act="reconnect">${esc(t('perception.reconnect'))}</button>
            <button class="pp-btn danger-line" data-act="remove">${esc(t('perception.removeMailbox'))}</button>
          </div>
        </div>
      </div>`;
  } else if (isBackoff) {
    html += `
      <div class="pp-alert warnbox">
        <svg viewBox="0 0 24 24"><path d="M12 9v4M12 17h.01"/><path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg>
        <div style="flex:1;min-width:0">
          <div class="ah">${esc(t('perception.backoffTitle'))}</div>
          <div class="ad">${esc(t('perception.backoffDesc'))}</div>
        </div>
      </div>`;
  }
  if (isPaused) html += `<div class="pp-paused-banner show">${esc(t('perception.pausedBanner'))}</div>`;
  html += `
    <div class="pp-mailrow">
      <div style="flex:1;min-width:0">
        <div class="addr">${esc(addr)}</div>
        <div class="srv">${esc(provider)}</div>
      </div>
      <button class="pp-toggle ${en.enabled ? 'on' : ''}" data-act="toggle" title="${esc(t('perception.toggleTitle'))}" aria-label="${esc(t('perception.toggleTitle'))}"></button>
    </div>
    <div style="height:8px"></div>
    <div class="pp-quiet-line">
      <svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg>
      <span>${esc(t('perception.quietLine'))}</span>
    </div>
    <div class="pp-actions">
      <button class="pp-btn" data-act="config">${esc(t('perception.configBtn'))}</button>
    </div>`;
  detail.innerHTML = html;
  detail.querySelector('[data-act="toggle"]').addEventListener('click', async (e) => {
    const on = !e.currentTarget.classList.contains('on');
    e.currentTarget.classList.toggle('on', on);
    await api('POST', '/api/perception/mail/toggle', { id, enabled: on });
  });
  detail.querySelector('[data-act="config"]').addEventListener('click', () => openConfigCard(en));
  const reconn = detail.querySelector('[data-act="reconnect"]');
  if (reconn) reconn.addEventListener('click', () => openConfigCard(en));
  const rm = detail.querySelector('[data-act="remove"]');
  if (rm) rm.addEventListener('click', async () => {
    await api('POST', '/api/perception/mail/remove', { id });
    api('GET', '/api/perception/entries').then(r => {
      if (r.ok && r.data && Array.isArray(r.data.entries)) { entriesCache = r.data.entries; renderPanel(); }
    });
  });
}

function providerOf(addr) {
  const dom = (addr.split('@')[1] || '').toLowerCase();
  const map = { 'qq.com': 'QQ', 'foxmail.com': 'QQ', '163.com': '网易 163', '126.com': '网易 126', 'gmail.com': 'Gmail', 'googlemail.com': 'Gmail', 'outlook.com': 'Outlook', 'hotmail.com': 'Outlook', 'live.com': 'Outlook', 'icloud.com': 'iCloud', 'me.com': 'iCloud' };
  return map[dom] || dom;
}

// ── 两问配置卡 ──────────────────────────────────────────────────────────

/** 两问配置卡（导出面 = harness 直驱；行为同条目「邮箱设置/重新连接」入口）。 */
export function openPerceptionConfig(existing) {
  return openConfigCard(existing);
}

function openConfigCard(existing) {
  closeConfigCard();
  const overlay = document.createElement('div');
  overlay.className = 'pp-overlay open';
  overlay.id = 'pp-cfg-overlay';
  overlay.innerHTML = `
    <div class="pp-cfg" role="dialog" aria-label="${esc(t('perception.cfgTitle'))}">
      <div class="pp-cfg-h">${esc(t('perception.cfgTitle'))}</div>
      <div class="pp-cfg-sub">${esc(t('perception.cfgSub'))}</div>
      <div class="pp-cfg-b">
        <div class="pp-field">
          <label for="pp-cfg-addr">${esc(t('perception.cfgAddress'))}</label>
          <input id="pp-cfg-addr" type="email" autocomplete="off" spellcheck="false" placeholder="name@example.com">
        </div>
        <div class="pp-field">
          <label for="pp-cfg-code">${esc(t('perception.cfgAuthCode'))}</label>
          <input id="pp-cfg-code" type="password" autocomplete="new-password" placeholder="· · · · · · · ·">
          <div class="hint">${esc(t('perception.cfgAuthCodeHint'))}</div>
        </div>
        <div class="pp-auto-note">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 8h.01M11 12h1v4h1"/></svg>
          <span>${esc(t('perception.cfgAutoNote'))}</span>
        </div>
        <details class="pp-adv">
          <summary>
            <svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>
            ${esc(t('perception.cfgAdvanced'))}
          </summary>
          <div class="pp-adv-body">
            <div class="pp-field-grid">
              <div class="pp-field"><label for="pp-cfg-imap">${esc(t('perception.cfgImapHost'))}</label><input id="pp-cfg-imap" placeholder="imap.qq.com"></div>
              <div class="pp-field"><label for="pp-cfg-imapport">${esc(t('perception.cfgPort'))}</label><input id="pp-cfg-imapport" placeholder="993"></div>
            </div>
            <div class="pp-field-grid">
              <div class="pp-field"><label for="pp-cfg-smtp">${esc(t('perception.cfgSmtpHost'))}</label><input id="pp-cfg-smtp" placeholder="smtp.qq.com"></div>
              <div class="pp-field"><label for="pp-cfg-smtpport">${esc(t('perception.cfgPort'))}</label><input id="pp-cfg-smtpport" placeholder="465"></div>
            </div>
            <div class="pp-field"><label for="pp-cfg-name">${esc(t('perception.cfgDisplayName'))}</label><input id="pp-cfg-name" placeholder="${esc(t('perception.cfgDisplayNamePh'))}"></div>
            <div class="pp-field"><label for="pp-cfg-trusted">${esc(t('perception.cfgTrusted'))}</label><input id="pp-cfg-trusted" placeholder="${esc(t('perception.cfgTrustedPh'))}"><div class="hint">${esc(t('perception.cfgTrustedHint'))}</div></div>
          </div>
        </details>
      </div>
      <div class="pp-cfg-result" id="pp-cfg-result"></div>
      <div class="pp-cfg-foot">
        <span class="spacer"></span>
        <button class="pp-btn" id="pp-cfg-cancel">${esc(t('perception.cancel'))}</button>
        <button class="pp-btn primary" id="pp-cfg-connect">${esc(t('perception.connect'))}</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) closeConfigCard(); });
  overlay.querySelector('#pp-cfg-cancel').addEventListener('click', closeConfigCard);
  overlay.querySelector('#pp-cfg-connect').addEventListener('click', async () => {
    const result = overlay.querySelector('#pp-cfg-result');
    const addrEl = overlay.querySelector('#pp-cfg-addr');
    const codeEl = overlay.querySelector('#pp-cfg-code');
    const addr = addrEl instanceof HTMLInputElement ? addrEl.value.trim() : '';
    const code = codeEl instanceof HTMLInputElement ? codeEl.value : '';
    if (!addr || !code) { setResult(result, t('perception.cfgNeedBoth'), false); return; }
    const btn = overlay.querySelector('#pp-cfg-connect');
    if (btn instanceof HTMLButtonElement) btn.disabled = true;
    result.textContent = t('perception.cfgTesting');
    result.className = 'pp-cfg-result';
    const body = {
      address: addr,
      authCode: code,
      imapHost: val(overlay, 'pp-cfg-imap') || undefined,
      imapPort: intOrNull(val(overlay, 'pp-cfg-imapport')) || undefined,
      smtpHost: val(overlay, 'pp-cfg-smtp') || undefined,
      smtpPort: intOrNull(val(overlay, 'pp-cfg-smtpport')) || undefined,
      displayName: val(overlay, 'pp-cfg-name') || undefined,
      trustedSenders: (val(overlay, 'pp-cfg-trusted') || '').split(/[,;\s]+/).map(s => s.trim()).filter(Boolean)
    };
    const r = await api('POST', '/api/perception/mail/connect', body);
    if (btn instanceof HTMLButtonElement) btn.disabled = false;
    if (r.ok) {
      setResult(result, t('perception.cfgConnected'), true);
      setTimeout(closeConfigCard, 600);
      api('GET', '/api/perception/entries').then(rr => {
        if (rr.ok && rr.data && Array.isArray(rr.data.entries)) { entriesCache = rr.data.entries; renderPanel(); }
      });
    } else {
      setResult(result, authErrMsg(r, t('perception.cfgFailed')), false);
    }
  });
  const addrFocus = overlay.querySelector('#pp-cfg-addr');
  if (addrFocus instanceof HTMLElement) addrFocus.focus();
  function val(root, id) {
    const el = root.querySelector('#' + id);
    return (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) ? el.value.trim() : '';
  }
  function intOrNull(s) { const n = parseInt(s, 10); return Number.isFinite(n) ? n : null; }
  function setResult(el, msg, ok) { el.textContent = msg; el.className = 'pp-cfg-result ' + (ok ? 'ok' : 'err'); }
}

function closeConfigCard() {
  const overlay = document.getElementById('pp-cfg-overlay');
  if (overlay) overlay.remove();
}

// ═══════════════════ ② ③ ask 卡增强（OTP 单码片 + 快照卡）═══════════════════

const OTP_INTENT_RE = /(验证码|校验码|确认码|动态码|安全码|verification\s*code|verify\s*code|security\s*code|one[-\s]?time|otp\b|passcode|login\s*code)/i;

/**
 * renderAskUser 注入点：气泡 + items + requestId/targetSid（答复走 answerAsk 同形帧）。
 * - secret 题 & OTP 意图 → 开窗（零 UI），码到渲染单码片。
 * - perceptionDraft 题 → 快照面替换默认选项区。
 */
function enhanceAskCard(bubble, items, requestId, targetSid) {
  if (!Array.isArray(items) || items.length === 0) return;
  // ── OTP 单码片 ──
  const otpItemIdx = items.findIndex(i => i && i.secret && OTP_INTENT_RE.test(i.question || ''));
  if (otpItemIdx >= 0 && requestId) {
    api('POST', '/api/perception/otp/window/open', { requestId, windowSec: 600 });
    const inputFound = bubble.querySelector('input[type="password"]');
    const input = inputFound instanceof HTMLInputElement ? inputFound : null;
    otpCardHandlers.set(requestId, (frame) => {
      if (frame.type === 'expired') { removeOtpChip(bubble); return; }
      renderOtpChip(bubble, input, frame, otpItemIdx);
    });
  }
  // ── 快照卡 ──
  const draftItem = items.find(i => i && typeof i.id === 'string' && i.id.startsWith('perceptionDraft:'));
  if (draftItem) {
    const draftId = draftItem.id.slice('perceptionDraft:'.length);
    renderSnapshotFace(bubble, items, draftItem, draftId, targetSid, requestId);
  }
}

function renderOtpChip(bubble, input, frame, otpItemIdx) {
  removeOtpChip(bubble);
  const chip = document.createElement('div');
  chip.className = 'otp-chip';
  chip.setAttribute('role', 'button');
  chip.setAttribute('aria-label', t('perception.otpFill'));
  const codeEl = document.createElement('span');
  codeEl.className = 'code';
  codeEl.textContent = '····';
  const meta = document.createElement('span');
  meta.className = 'meta';
  const fromEl = document.createElement('div');
  fromEl.className = 'from';
  fromEl.textContent = `${frame.from || frame.fromDomain || t('perception.otpSource')} · ${t('perception.otpArrived')}`;
  const hint = document.createElement('div');
  hint.className = 'fill-hint';
  hint.textContent = t('perception.otpFill');
  meta.appendChild(fromEl); meta.appendChild(hint);
  chip.appendChild(codeEl); chip.appendChild(meta);
  chip.addEventListener('click', async () => {
    if (chip.classList.contains('filling')) return;
    const r = await api('GET', `/api/perception/otp/consume?windowId=${encodeURIComponent(frame.windowId)}`);
    if (r.ok && r.data && r.data.code && input) {
      codeEl.textContent = r.data.code;
      chip.classList.add('filling');
      setTimeout(() => {
        chip.remove();
        input.value = r.data.code;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
        // 一次性通道：值直接进密码框（与手输同路，用户仍点确认——不自动提交）
        input.focus();
      }, 200);
    } else {
      chip.remove(); // 码已被消费/窗口过期：零残留
    }
  });
  // 挂在密码框之后（输入框下方展开——作者逐字形态）
  if (input && input.parentNode) input.parentNode.insertBefore(chip, input.nextSibling);
  else bubble.appendChild(chip);
}

function removeOtpChip(bubble) {
  bubble.querySelectorAll('.otp-chip').forEach(el => el.remove());
}

// ── 快照卡（只读；两动作 + 放弃）───────────────────────────────────────

function renderSnapshotFace(bubble, items, draftItem, draftId, targetSid, requestId) {
  // 默认选项区（fallback 面）隐藏——快照面接管；CLI/降级不受影响（后端 options 仍在）
  const optionsBox = bubble.querySelector('.option-box');
  const face = document.createElement('div');
  face.className = 'pdraft';
  bubble.appendChild(face);
  if (optionsBox && optionsBox.parentNode === bubble) optionsBox.style.display = 'none';

  const head = document.createElement('div');
  head.innerHTML = `
    <div class="pdraft-mailhead">
      <div class="pdraft-mrow"><span class="pdraft-mlabel">${esc(t('perception.to'))}</span><span class="pdraft-mval" data-f="to"></span></div>
      <div class="pdraft-mrow" data-cc-row hidden><span class="pdraft-mlabel">${esc(t('perception.cc'))}</span><span class="pdraft-mval" data-f="cc"></span></div>
      <div class="pdraft-mrow"><span class="pdraft-mlabel">${esc(t('perception.subject'))}</span><span class="pdraft-mval pdraft-subject" data-f="subject"></span></div>
    </div>`;
  face.appendChild(head);
  const bodyEl = document.createElement('div');
  bodyEl.className = 'pdraft-body';
  face.appendChild(bodyEl);
  const attWrap = document.createElement('div');
  face.appendChild(attWrap);
  const staleEl = document.createElement('div');
  staleEl.className = 'pdraft-stale';
  staleEl.innerHTML = `<i></i><span>${esc(t('perception.staleNote'))}</span>`;
  face.appendChild(staleEl);
  const btnrow = document.createElement('div');
  btnrow.className = 'pp-actions';
  btnrow.innerHTML = `
    <button class="pp-btn primary" data-act="send">${esc(t('perception.sendAsIs'))}</button>
    <button class="pp-btn" data-act="canvas">${esc(t('perception.openCanvas'))}</button>
    <button class="pp-btn danger-line" data-act="abandon">${esc(t('perception.abandon'))}</button>`;
  face.appendChild(btnrow);
  const foot = document.createElement('div');
  foot.className = 'pdraft-foot';
  face.appendChild(foot);

  const answer = (label) => answerAsk(targetSid, requestId, [label]);
  // checkJs：querySelector 产出 Element——按钮一次性窄化为 HTMLButtonElement。
  const asBtn = (el) => (el instanceof HTMLButtonElement ? el : null);

  const sendBtn = asBtn(btnrow.querySelector('[data-act="send"]'));
  sendBtn?.addEventListener('click', async () => {
    sendBtn.disabled = true;
    const r = await api('POST', '/api/perception/mail/send', { draftId, snapshot: true });
    sendBtn.disabled = false;
    if (r.ok) { showReceipt(face, true, r.data); answer(t('perception.answerSent')); }
    else if (r.status === 409) staleEl.classList.add('show');
    else showReceipt(face, false, null, authErrMsg(r, t('perception.sendFailed')));
  });
  btnrow.querySelector('[data-act="canvas"]').addEventListener('click', () => {
    openMailEditor(draftId);
  });
  const abandonBtn = asBtn(btnrow.querySelector('[data-act="abandon"]'));
  abandonBtn?.addEventListener('click', async () => {
    abandonBtn.disabled = true;
    await api('POST', `/api/perception/mail/drafts/${encodeURIComponent(draftId)}/abandon`, {});
    answer(t('perception.answerAbandoned'));
  });

  draftCardHandlers.set(draftId, (frame) => {
    if (frame.type === 'perception.draft.sent') {
      showReceipt(face, true, frame);
      answer(t('perception.answerSent'));
    } else if (frame.type === 'perception.draft.updated') {
      if (frame.state === 'abandoned') answer(t('perception.answerAbandoned'));
      else loadDraft(draftId).then(d => { if (d && d.stale) staleEl.classList.add('show'); });
    }
  });

  loadDraft(draftId).then(d => {
    if (!d) {
      bodyEl.textContent = t('perception.draftGone');
      btnrow.querySelectorAll('button').forEach(b => { b.disabled = true; });
      return;
    }
    fillSnapshot(face, d);
    if (d.stale) staleEl.classList.add('show');
  });
}

async function loadDraft(draftId) {
  const r = await api('GET', `/api/perception/mail/drafts/${encodeURIComponent(draftId)}`);
  if (r.ok && r.data) return r.data;
  return null;
}

function fillSnapshot(face, d) {
  const doc = d.doc || {};
  const setF = (f, v) => { const el = face.querySelector(`[data-f="${f}"]`); if (el) el.textContent = v || '—'; };
  setF('to', (doc.to || []).join('、'));
  const ccRow = face.querySelector('[data-cc-row]');
  if (ccRow) { if ((doc.cc || []).length) { ccRow.hidden = false; setF('cc', doc.cc.join('、')); } else ccRow.hidden = true; }
  setF('subject', doc.subject);
  const bodyEl = face.querySelector('.pdraft-body');
  if (bodyEl) {
    bodyEl.innerHTML = sanitizeMailHtml(doc.bodyHtml || '');
    const quote = doc.quoteHtml;
    if (quote) {
      const q = document.createElement('div');
      q.className = 'mail-quote';
      q.innerHTML = sanitizeMailHtml(quote);
      bodyEl.appendChild(q);
    }
  }
  const attWrap = face.querySelector('.pdraft-att') ? face : null;
  const atts = doc.attachments || [];
  const foot = face.querySelector('.pdraft-foot');
  if (foot) foot.textContent = atts.length ? `${t('perception.attachments')}：${atts.map(a => String(a).split('/').pop()).join('、')}` : '';
}

function showReceipt(face, ok, data, errMsg) {
  let receipt = face.querySelector('.pdraft-receipt');
  if (!receipt) {
    receipt = document.createElement('div');
    face.appendChild(receipt);
  }
  receipt.className = 'pdraft-receipt ' + (ok ? 'ok' : 'bad');
  const time = data && data.sentAt ? new Date(data.sentAt).toLocaleString() : '';
  receipt.textContent = ok
    ? `${t('perception.receiptSent')} · ${time}${data && data.messageId ? ' · ' + String(data.messageId).slice(0, 40) : ''}`
    : `${t('perception.receiptFail')}${errMsg ? ' · ' + errMsg : ''}`;
  const btnrow = face.querySelector('.pp-actions');
  if (btnrow) btnrow.querySelectorAll('button').forEach(b => { b.disabled = true; });
}

/** 快照/引用 HTML 白化：剥 script/style/事件属性（卡面只读展示）。 */
function sanitizeMailHtml(html) {
  const tpl = document.createElement('template');
  tpl.innerHTML = String(html || '');
  tpl.content.querySelectorAll('script,style,link,iframe,object,embed').forEach(el => el.remove());
  tpl.content.querySelectorAll('*').forEach(el => {
    [...el.attributes].forEach(attr => {
      const name = attr.name.toLowerCase();
      if (name.startsWith('on') || (name === 'href' && /^\s*javascript:/i.test(attr.value))) el.removeAttribute(attr.name);
    });
  });
  return tpl.innerHTML;
}

// ═══════════════════ ④ Canvas 邮件编辑器 ═══════════════════

export async function openMailEditor(draftId, opts = {}) {
  const d = await loadDraft(draftId);
  if (!d || !d.doc) { window.__showToast?.(t('perception.draftGone'), 'error'); return; }
  const doc = d.doc;
  const tabId = 'perception-draft:' + draftId;
  const entry = openTab(tabId, t('perception.newMail'), { type: 'perception-draft' });
  if (!entry) return;
  const pane = entry.paneEl;
  pane.innerHTML = '';
  pane.classList.add('perception-draft-pane');
  pane.style.cssText += 'overflow-y:auto;position:relative;';

  const tools = document.createElement('div');
  tools.className = 'canvas-tools';
  tools.style.cssText = 'position:absolute;top:10px;right:14px;z-index:30;display:flex;align-items:center;gap:6px;padding:4px 6px 4px 10px;border-radius:999px;background:var(--st-control-bg);border:1px solid var(--st-control-border);box-shadow:inset 0 1px 0 var(--st-control-highlight),inset 0 -1px 0 var(--st-control-underedge),0 1px 3px var(--st-control-glow),0 2px 8px var(--st-control-shadow)';
  tools.innerHTML = `
    <button class="pp-btn" data-t="abandon" style="border:none;background:transparent;box-shadow:none;color:var(--st-text-muted)">${esc(t('perception.abandon'))}</button>
    <button class="pp-btn primary" data-t="send" style="border-radius:999px;padding:4px 14px">${esc(t('perception.send'))}</button>`;
  pane.appendChild(tools);

  const wrap = document.createElement('div');
  wrap.className = 'mailcompose';
  wrap.style.paddingTop = '56px';
  pane.appendChild(wrap);

  // ── 收件人 chips ──
  wrap.appendChild(crow(t('perception.to'), toLine(doc.to || [], 'to')));
  const ccLine = toLine(doc.cc || [], 'cc');
  const ccRow = crow(t('perception.cc'), ccLine);
  ccRow.classList.add('cc-row');
  if ((doc.cc || []).length) ccRow.classList.add('show');
  const bccLine = toLine(doc.bcc || [], 'bcc');
  const bccRow = crow(t('perception.bcc'), bccLine);
  bccRow.classList.add('cc-row');
  if ((doc.bcc || []).length) bccRow.classList.add('show');
  const toRow = wrap.lastChild;
  const ccBtn = document.createElement('button');
  ccBtn.className = 'cc-toggle';
  ccBtn.textContent = t('perception.ccBcc');
  ccBtn.addEventListener('click', () => {
    ccRow.classList.toggle('show'); bccRow.classList.toggle('show');
  });
  toRow.appendChild(ccBtn);
  wrap.appendChild(ccRow);
  wrap.appendChild(bccRow);

  // ── 主题 ──
  const subRow = document.createElement('div');
  subRow.className = 'crow';
  subRow.innerHTML = `<span class="clabel"></span>`;
  const subInput = document.createElement('input');
  subInput.className = 'subject-input';
  subInput.value = doc.subject || '';
  subInput.placeholder = t('perception.subjectPh');
  const subVal = document.createElement('div');
  subVal.className = 'cval';
  subVal.appendChild(subInput);
  subRow.appendChild(subVal);
  subRow.querySelector('.clabel').textContent = t('perception.subject');
  wrap.appendChild(subRow);

  // ── 工具栏 ──
  const toolbar = document.createElement('div');
  toolbar.className = 'mail-toolbar';
  toolbar.innerHTML = `
    <button class="mt-btn" data-cmd="bold" title="${esc(t('perception.tbBold'))}"><span class="bi">B</span></button>
    <button class="mt-btn" data-cmd="italic" title="${esc(t('perception.tbItalic'))}"><span style="font-style:italic">I</span></button>
    <span class="mt-sep"></span>
    <button class="mt-btn" data-cmd="insertUnorderedList" title="${esc(t('perception.tbUl'))}"><svg viewBox="0 0 24 24"><path d="M9 6h12M9 12h12M9 18h12"/><circle cx="4" cy="6" r="1"/><circle cx="4" cy="12" r="1"/><circle cx="4" cy="18" r="1"/></svg></button>
    <button class="mt-btn" data-cmd="insertOrderedList" title="${esc(t('perception.tbOl'))}"><svg viewBox="0 0 24 24"><path d="M10 6h11M10 12h11M10 18h11"/><path d="M4 6h1v4M4 14h2v4H4v-2"/></svg></button>
    <button class="mt-btn" data-cmd="formatBlock" data-val="blockquote" title="${esc(t('perception.tbQuote'))}"><svg viewBox="0 0 24 24"><path d="M3 21c3-1 4-3 4-6V6h6v9H7c0 3-1 5-4 6zM14 21c3-1 4-3 4-6V6h6v9h-6c0 3-1 5-4 6z" transform="scale(.8) translate(3 1)"/></svg></button>
    <button class="mt-btn" data-cmd="link" title="${esc(t('perception.tbLink'))}"><svg viewBox="0 0 24 24"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/></svg></button>`;
  wrap.appendChild(toolbar);

  // ── 引用上下文（只读）──
  if (doc.quoteHtml) {
    const quote = document.createElement('div');
    quote.className = 'mail-quote';
    quote.innerHTML = sanitizeMailHtml(doc.quoteHtml);
    wrap.appendChild(quote);
  }

  // ── 正文 ──
  const bodyEl = document.createElement('div');
  bodyEl.className = 'mail-body-edit';
  bodyEl.contentEditable = 'true';
  bodyEl.setAttribute('data-placeholder', t('perception.bodyPh'));
  bodyEl.innerHTML = sanitizeMailHtml(doc.bodyHtml || '');
  wrap.appendChild(bodyEl);

  // ── 附件（本批只读展示 + ×移除）──
  let attachments = (doc.attachments || []).slice();
  const attRow = document.createElement('div');
  attRow.className = 'att-row';
  const renderAtts = () => {
    attRow.innerHTML = '';
    attachments.forEach((a) => {
      const chip = document.createElement('span');
      chip.className = 'att-chip';
      chip.innerHTML = `<svg viewBox="0 0 24 24"><path d="M21.4 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg><span class="nm"></span><button class="x" title="${esc(t('perception.removeAtt'))}"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>`;
      chip.querySelector('.nm').textContent = String(a).split('/').pop();
      chip.querySelector('.x').addEventListener('click', () => {
        attachments = attachments.filter(x => x !== a);
        renderAtts();
        scheduleSave();
      });
      attRow.appendChild(chip);
    });
  };
  renderAtts();
  wrap.appendChild(attRow);

  const note = document.createElement('div');
  note.className = 'mail-send-note';
  wrap.appendChild(note);

  // ── 行为：编辑 → 防抖保存（实时写盘语义）；工具栏命令 ──
  toolbar.querySelectorAll('.mt-btn').forEach(node => {
    if (!(node instanceof HTMLElement)) return; // dataset 窄化（contacts.js 同法）
    const btn = node;
    btn.addEventListener('mousedown', (e) => e.preventDefault());
    btn.addEventListener('click', () => {
      const cmd = btn.dataset.cmd;
      if (cmd === 'link') {
        const url = window.prompt(t('perception.linkPrompt'), 'https://');
        if (url) document.execCommand('createLink', false, url);
      } else if (cmd === 'formatBlock') {
        document.execCommand('formatBlock', false, btn.dataset.val || 'blockquote');
      } else {
        document.execCommand(cmd, false, null);
      }
      bodyEl.focus();
      scheduleSave();
    });
  });

  let saveTimer = null;
  function scheduleSave() {
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(saveDraftContent, 600);
  }
  subInput.addEventListener('input', scheduleSave);
  bodyEl.addEventListener('input', scheduleSave);

  async function serialize() {
    const tos = collectChips('to');
    const ccs = collectChips('cc');
    const bccs = collectChips('bcc');
    const subject = subInput.value.trim();
    let bodyHtml = bodyEl.innerHTML.trim();
    if (doc.quoteHtml) bodyHtml += '\n' + doc.quoteHtml.trim();
    const lines = ['---', 'draft: perception-mail-send', `to: ${tos.join(', ')}`];
    if (ccs.length) lines.push(`cc: ${ccs.join(', ')}`);
    if (bccs.length) lines.push(`bcc: ${bccs.join(', ')}`);
    lines.push(`subject: ${subject}`);
    attachments.forEach(a => lines.push(`attach: ${a}`));
    lines.push('---', '', bodyHtml, '');
    return lines.join('\n');
  }
  function collectChips(kind) {
    const line = kind === 'to' ? toLine0 : kind === 'cc' ? ccLine : bccLine;
    return [...line.querySelectorAll('.to-chip')].map(ch => ch.dataset.addr).filter(Boolean);
  }
  async function saveDraftContent() {
    const content = await serialize();
    await api('PUT', `/api/perception/mail/drafts/${encodeURIComponent(draftId)}`, { content });
    if (opts.onEdited) opts.onEdited();
  }

  // ── 发送 / 放弃 ──
  const toolBtn = (sel) => {
    const el = tools.querySelector(sel);
    return el instanceof HTMLButtonElement ? el : null;
  };
  const sendToolBtn = toolBtn('[data-t="send"]');
  sendToolBtn?.addEventListener('click', async () => {
    sendToolBtn.disabled = true;
    if (saveTimer) { clearTimeout(saveTimer); await saveDraftContent(); }
    const r = await api('POST', '/api/perception/mail/send', { draftId, snapshot: false });
    sendToolBtn.disabled = false;
    if (r.ok) {
      note.className = 'mail-send-note ok show';
      note.textContent = t('perception.receiptSent');
      if (opts.onSent) opts.onSent(r.data);
    } else {
      note.className = 'mail-send-note show ' + (r.status === 409 ? 'warn' : 'err');
      note.textContent = authErrMsg(r, t('perception.sendFailed'));
    }
  });
  tools.querySelector('[data-t="abandon"]').addEventListener('click', async () => {
    await api('POST', `/api/perception/mail/drafts/${encodeURIComponent(draftId)}/abandon`, {});
    note.className = 'mail-send-note warn show';
    note.textContent = t('perception.abandoned');
    if (opts.onAbandoned) opts.onAbandoned();
  });

  // sent 帧也路由到画布页（画布与卡片同时开着时状态一致）
  const prevHandler = draftCardHandlers.get(draftId);
  draftCardHandlers.set(draftId, (frame) => {
    if (prevHandler) prevHandler(frame);
    if (frame.type === 'perception.draft.sent') {
      note.className = 'mail-send-note ok show';
      note.textContent = t('perception.receiptSent');
    }
  });

  // ── chips 行构造 ──
  function crow(label, lineEl) {
    const row = document.createElement('div');
    row.className = 'crow';
    const lb = document.createElement('span');
    lb.className = 'clabel';
    lb.textContent = label;
    const valEl = document.createElement('div');
    valEl.className = 'cval';
    valEl.appendChild(lineEl);
    row.appendChild(lb); row.appendChild(valEl);
    return row;
  }
  function toLine(addrs, kind) {
    const line = document.createElement('div');
    line.className = 'to-line';
    addrs.forEach(a => line.appendChild(toChip(a)));
    const input = document.createElement('input');
    input.className = 'to-input';
    input.placeholder = kind === 'to' ? t('perception.toPh') : t('perception.addrPh');
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ',') {
        e.preventDefault();
        const v = input.value.trim();
        if (v && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) { line.insertBefore(toChip(v), input); input.value = ''; scheduleSave(); }
        else if (v) input.classList.add('bad');
      } else if (e.key === 'Backspace' && !input.value) {
        const chips = line.querySelectorAll('.to-chip');
        if (chips.length) { chips[chips.length - 1].remove(); scheduleSave(); }
      }
    });
    input.addEventListener('blur', () => {
      const v = input.value.trim();
      if (v && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) { line.insertBefore(toChip(v), input); input.value = ''; scheduleSave(); }
    });
    input.addEventListener('input', () => input.classList.remove('bad'));
    line.appendChild(input);
    return line;
  }
  let toLine0 = null; // set below after construction
  function toChip(addr) {
    const chip = document.createElement('span');
    chip.className = 'to-chip';
    chip.dataset.addr = addr;
    const txt = document.createElement('span');
    txt.textContent = addr;
    const x = document.createElement('button');
    x.className = 'x';
    x.setAttribute('aria-label', t('perception.removeAddr'));
    x.innerHTML = '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>';
    x.addEventListener('click', () => { chip.remove(); scheduleSave(); });
    chip.appendChild(txt); chip.appendChild(x);
    return chip;
  }
  // bind toLine0 (the 'to' line) for collectChips
  toLine0 = wrap.querySelector('.to-line');
  // register canvas-side handler cleanup on tab close
  // (draftCardHandlers entry overwritten above intentionally)
  return tabId;
}
