// fleetUpdate.js — 全设备统一更新的**阻塞式状态卡**（fleet 批 2026-11-01，作者令
// 「一键把所有设备更新到同一版本，并全程有状态卡片引导」）。
//
// 一个模块 = 一张卡 + 三个动作（全部更新 / 中断 / 重试失败项），单一数据源 =
// 引擎侧协调器的 `fleetUpdate` 帧（`FleetUpdateCoordinator#snapshot`）：
//
//   { type:'fleetUpdate', minRemoteVersion, protocol, currentVersion,
//     job: { job:{jobId,status,statusKey,targetVersion,updatedCount,failedCount,
//                failed:[{deviceId,deviceName,failure,failureKey}],
//                manualCount,pendingOnlineCount,cancelledCount},
//            runs:[{deviceId,deviceName,isLocal,stage,stageKey,state,progress,
//                   detail,failure,failureKey,retryable,attempts,startedAt,updatedAt}],
//            manual:[FleetDeviceView], pending:[FleetDeviceView] },
//     inflight:[deviceId] }
//
// 纪律（逐条对应作者令）：
//   · **阻塞**：不是 toast——更新期间铺满视图的大卡，本机重启窗口内显式写「正在
//     更新，请稍候」+ 当前阶段 + 预计时间；
//   · **断线重连续续**：WS 重连 / 页面刷新后先发 `fleetUpdateSnapshot` 命令取快照
//     （服务端是内存 + 落盘的单点），卡片从不靠前端记忆；
//   · **失败分类**：五类（下载 / 校验 / 切换回滚 / 自检 / 失联）+ 可操作建议，
//     回滚档不给「一键重试」（需人工介入），其余档给重试；
//   · **单机失败不阻塞其他设备**：每台一行，各自终态；
//   · **可中断**：卡的次键恒为「中断」（已发起的单机安装如实标注不可撤回）。
//
// 文案一律走 i18n 键（`fleet.*`），帧里只带 `messageKey`（`stageKey`/`failureKey`/
// `statusKey`/`capabilityKey`）——零硬编码展示文案、零颜色字面量（CSS 走 token）。

import { onMessage, onReconnect, sendWs } from './ws.js';
import { t } from './i18n.js';
import { createIconsIn, escapeHtml } from './utils.js';
import { getAuthToken } from './neblink.js';

// ── 状态（唯一真源 = 引擎侧快照；此处只是最新一帧的镜像）─────────────────
/** @type {any} */
let fleetFrame = null;
/** 卡是否展开（作者主动打开或任务在飞 ⇒ 打开）。 */
let cardOpen = false;
/** 已展开单机细节的 deviceId 集合（默认全收起）。 */
let expanded = new Set();
/** 正在等待 `fleetUpdateResult` 回执的动作（用于卡上的按钮禁用态）。 */
let pendingAction = '';

const CARD_ID = 'fleet-update-card';
const OVERLAY_ID = 'fleet-update-overlay';

// ── 样式（全部走既有 token；零裸 hex）─────────────────────────────────────
const FLEET_CSS = `
<style id="fleet-update-css">
#${OVERLAY_ID} {
  position: fixed; inset: 0; z-index: 10050;
  display: none; align-items: center; justify-content: center;
  background: var(--overlay-bg);
}
#${OVERLAY_ID}.on { display: flex; }
#${CARD_ID} {
  position: relative; width: min(560px, 94vw); max-height: 86vh;
  display: flex; flex-direction: column;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border); border-radius: var(--ds-radius-shell);
  box-shadow: 0px 2px 8px rgba(0, 0, 0, 0.04), 0px 8px 24px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}
#${CARD_ID}::before {
  content: ''; position: absolute; top: 0; left: 10%; right: 10%; height: 1px;
  background: linear-gradient(90deg, transparent 10%, var(--sapphire-refraction) 50%, transparent 90%);
  pointer-events: none;
}
.fleet-head {
  padding: 14px var(--ds-space-4) var(--ds-space-block-y); border-bottom: 1px solid var(--color-frame-border-light);
  display: flex; align-items: center; gap: var(--ds-space-2);
}
.fleet-title { font-size: var(--ds-fs-subtitle); font-weight: 600; color: var(--color-frame-text); flex: 1; }
.fleet-close {
  background: none; border: none; cursor: pointer; padding: var(--ds-space-1); border-radius: var(--ds-radius-chip);
  color: var(--color-frame-text-muted); display: flex; align-items: center;
}
.fleet-close:hover { background: var(--glass-control-bg-hover); color: var(--color-frame-text); }
.fleet-body { padding: var(--ds-space-3) var(--ds-space-4); overflow-y: auto; flex: 1; }
.fleet-sub { font-size: var(--ds-fs-hint); color: var(--color-frame-text-muted); margin-bottom: var(--ds-space-stack-gap); line-height: 1.5; }
.fleet-blocking {
  font-size: var(--ds-fs-hint); color: var(--color-frame-text); padding: var(--ds-space-2) var(--ds-space-inline-x); margin-bottom: var(--ds-space-stack-gap);
  border-radius: var(--ds-radius-button); background: var(--glass-control-bg);
  border: 1px solid var(--glass-control-border); line-height: 1.5;
}
.fleet-row {
  border: 1px solid var(--color-frame-border-light); border-radius: var(--ds-radius-button);
  padding: var(--ds-space-2) var(--ds-space-inline-x); margin-bottom: var(--ds-space-2); cursor: pointer;
}
.fleet-row:hover { background: var(--glass-control-bg-hover); }
.fleet-row-main { display: flex; align-items: center; gap: var(--ds-space-2); font-size: var(--ds-fs-body); }
.fleet-row-name { color: var(--color-frame-text); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fleet-row-ver { font-size: var(--ds-fs-meta); color: var(--color-frame-text-muted); flex-shrink: 0; }
.fleet-row-stage { font-size: var(--ds-fs-meta); color: var(--color-frame-text-muted); flex-shrink: 0; }
.fleet-row-detail {
  font-size: var(--ds-fs-meta); color: var(--color-frame-text-muted); margin-top: var(--ds-space-1h);
  padding-top: var(--ds-space-1h); border-top: 1px dashed var(--color-frame-border-light); line-height: 1.5;
  word-break: break-word;
}
.fleet-bar { height: 3px; border-radius: var(--ds-radius-thin); background: var(--glass-control-bg); margin-top: var(--ds-space-1h); overflow: hidden; }
.fleet-bar > i { display: block; height: 100%; background: var(--color-frame-text-dim); width: 0; transition: width 0.3s; }
.fleet-badge {
  font-size: var(--ds-fs-meta); padding: var(--ds-space-hair) var(--ds-space-1h); border-radius: var(--ds-radius-chip); flex-shrink: 0;
  border: 1px solid var(--glass-control-border); color: var(--color-frame-text-muted);
}
.fleet-summary { font-size: var(--ds-fs-hint); color: var(--color-frame-text); line-height: 1.6; margin: var(--ds-space-stack-gap) 0 var(--ds-space-1); }
.fleet-foot { padding: var(--ds-space-block-y) var(--ds-space-4) 14px; display: flex; gap: var(--ds-space-2); justify-content: flex-end; }
.fleet-btn {
  font-size: var(--ds-fs-hint); padding: var(--ds-space-1h) var(--ds-space-3); border-radius: var(--ds-radius-button); cursor: pointer;
  border: 1px solid var(--glass-control-border); background: var(--glass-control-bg);
  color: var(--color-frame-text);
}
.fleet-btn:hover { background: var(--glass-control-bg-hover); }
.fleet-btn[disabled] { opacity: 0.5; cursor: default; }
.fleet-btn.primary { border-color: var(--sapphire-edge, var(--glass-control-border)); }
</style>`;

// ── 小工具 ────────────────────────────────────────────────────────────────
function keyOf(obj, field, wire) {
  const k = obj && typeof obj === 'object' ? obj[field] : null;
  if (typeof k === 'string' && k) return t(k);
  // 引擎未给键（老帧/降级）⇒ 用 wire 原值，绝不显示空串
  return wire ? String(wire) : '';
}

function wireOf(obj, field, fallback) {
  const v = obj && typeof obj === 'object' ? obj[field] : null;
  return typeof v === 'string' && v ? v : (fallback || '');
}

function ensureDom() {
  let styleEl = document.getElementById('fleet-update-css');
  if (!styleEl) {
    document.head.insertAdjacentHTML('beforeend', FLEET_CSS);
    styleEl = document.getElementById('fleet-update-css');
  }
  let overlay = document.getElementById(OVERLAY_ID);
  if (!overlay) {
    overlay = document.createElement('div');
    overlay.id = OVERLAY_ID;
    overlay.addEventListener('click', (e) => {
      // 点遮罩关闭仅在「无在飞任务」时允许（在飞时卡是阻塞面，先中断再说）
      if (e.target === overlay && !jobRunning()) closeCard();
    });
    document.body.appendChild(overlay);
  }
  return overlay;
}

function jobRunning() {
  const job = fleetFrame && fleetFrame.job ? fleetFrame.job.job : null;
  return !!(job && job.status === 'running');
}

// ── 渲染 ──────────────────────────────────────────────────────────────────
function stageLabel(run) {
  return keyOf(run, 'stageKey', wireOf(run, 'stage', 'queued'));
}

function capabilityLabel(dev) {
  const key = dev && dev.capabilityKey;
  if (typeof key === 'string' && key) return t(key);
  return dev && dev.remoteUpdatable ? t('fleet.capability.self-reported') : t('fleet.capability.version-too-old');
}

function renderDeviceRow(run) {
  const id = String(run.deviceId || '');
  const isOpen = expanded.has(id);
  const progress = typeof run.progress === 'number' ? Math.max(0, Math.min(1, run.progress)) : null;
  const failureKey = typeof run.failureKey === 'string' ? run.failureKey : '';
  const stageText = stageLabel(run);
  const failed = wireOf(run, 'stage', '') === 'failed';
  const detail = typeof run.detail === 'string' ? run.detail : '';
  const advice = failureKey ? t(failureKey + '.advice') : '';
  const adviceHtml = advice && advice !== failureKey + '.advice'
    ? `<div class="fleet-row-detail">${escapeHtml(advice)}</div>`
    : '';
  return `<div class="fleet-row" data-fleet-row="${escapeHtml(id)}">
    <div class="fleet-row-main">
      <span class="fleet-row-name">${escapeHtml(String(run.deviceName || id))}${run.isLocal ? ' · ' + escapeHtml(t('fleet.thisDevice')) : ''}</span>
      <span class="fleet-row-stage">${escapeHtml(stageText)}</span>
    </div>
    ${progress !== null ? `<div class="fleet-bar"><i style="width:${Math.round(progress * 100)}%"></i></div>` : ''}
    ${isOpen
      ? `<div class="fleet-row-detail">${escapeHtml(detail || '')}${run.attempts ? ` · ${escapeHtml(t('fleet.attempt', { n: String(run.attempts) }))}` : ''}</div>${adviceHtml}`
      : ''}
  </div>`;
}

function renderPlanRow(dev) {
  const version = dev.version ? 'v' + dev.version : t('fleet.versionUnknown');
  const branch = wireOf(dev, 'branch', 'manual');
  const branchText = t('fleet.branch.' + branch);
  const cap = capabilityLabel(dev);
  const online = dev.online === true;
  return `<div class="fleet-row" data-fleet-plan-row="${escapeHtml(String(dev.deviceId || ''))}">
    <div class="fleet-row-main">
      <span class="fleet-row-name">${escapeHtml(String(dev.deviceName || dev.deviceId || ''))}</span>
      <span class="fleet-row-ver">${escapeHtml(version)}</span>
      <span class="fleet-badge">${escapeHtml(online ? t('fleet.online') : t('fleet.offline'))}</span>
      <span class="fleet-badge">${escapeHtml(cap)}</span>
      <span class="fleet-badge">${escapeHtml(branchText)}</span>
    </div>
  </div>`;
}

function renderSummary(job) {
  if (!job) return '';
  const parts = [
    t('fleet.summary.counts', {
      updated: String(job.updatedCount || 0),
      failed: String(job.failedCount || 0),
      manual: String(job.manualCount || 0),
      pending: String(job.pendingOnlineCount || 0),
    }),
  ];
  const failed = Array.isArray(job.failed) ? job.failed : [];
  if (failed.length) {
    const lines = failed.map((f) => {
      const name = String(f.deviceName || f.deviceId || '');
      const why = keyOf(f, 'failureKey', wireOf(f, 'failure', 'unknown'));
      return `${name}：${why}`;
    });
    parts.push(lines.join('；'));
  }
  // 收口状态句（作者令「不许只报已发起」的反向面：**动手前**也要说清楚当前是否
  // 已经一致）。判据在引擎 `FleetSummary.of`，前端只解析 `messageKey`。
  parts.unshift(t(keyOf(job, 'statusKey', 'fleet.job.' + wireOf(job, 'status', 'partial'))));
  return `<div class="fleet-summary">${escapeHtml(parts.join(' — '))}</div>`;
}

/**
 * 计划相收口句（还没有任务时显示）：全部一致 / N 台需手动 / M 台待上线 / N 台待更新。
 * 同源 = 引擎 `FleetPlan.summary`（`status` + `messageKey`），前端零判据复制。
 */
function renderPlanSummary(plan) {
  if (!plan || !plan.summary) return '';
  const s = plan.summary;
  const line = t(keyOf(s, 'messageKey', 'fleet.summary.out-of-sync') || 'fleet.summary.out-of-sync');
  const counts = t('fleet.summary.counts', {
    updated: String(s.totalDevices || 0),
    failed: '0',
    manual: String(s.manualCount || 0),
    pending: String(s.pendingOnlineCount || 0),
  });
  return `<div class="fleet-summary">${escapeHtml(line + ' — ' + counts)}</div>`;
}

function renderCard() {
  const overlay = ensureDom();
  const job = fleetFrame && fleetFrame.job ? fleetFrame.job : null;
  const summaryJob = job && job.job ? job.job : null;
  const runs = job && Array.isArray(job.runs) ? job.runs : [];
  const plan = fleetFrame && fleetFrame.plan ? fleetFrame.plan : null;
  const running = jobRunning();
  const stages = t('fleet.stages');
  const curVersion = fleetFrame && fleetFrame.currentVersion ? String(fleetFrame.currentVersion) : '';
  const localRun = runs.find((r) => r && r.isLocal === true);
  const localStage = localRun ? wireOf(localRun, 'stage', '') : '';

  const blocking = running
    ? `<div class="fleet-blocking">${escapeHtml(t('fleet.blocking', {
        stage: localRun ? stageLabel(localRun) : t('fleet.stage.prepare'),
        eta: t('fleet.eta'),
      }))}</div>`
    : '';

  const bodyRows = runs.length
    ? runs.map(renderDeviceRow).join('')
    : (plan && Array.isArray(plan.devices) ? plan.devices.map(renderPlanRow).join('') : '');

  const manual = job && Array.isArray(job.manual) ? job.manual : [];
  const pending = job && Array.isArray(job.pending) ? job.pending : [];
  // 自解释批（2026-11-04 铁律 8）：本行原来是 `fleet.manualHow`（「到该设备上打开控制台 →
  // 设置 → 检查更新」式路径指引，细则 d 反例 1），已删；改挂**能力徽标事实**
  // （`fleet.capability.*`「需手动更新（版本过旧）」——细则 e 明列为徽标事实），
  // 与计划行（`renderPlanRow`）同一取数函数 `capabilityLabel`，一句话都不教。
  const manualHtml = manual.length
    ? `<div class="fleet-sub">${escapeHtml(t('fleet.manualTitle'))}</div>` +
      manual.map((d) => `<div class="fleet-row"><div class="fleet-row-main">
        <span class="fleet-row-name">${escapeHtml(String(d.deviceName || d.deviceId || ''))}</span>
        <span class="fleet-row-ver">${escapeHtml(d.version ? 'v' + d.version : t('fleet.versionUnknown'))}</span>
        <span class="fleet-badge">${escapeHtml(capabilityLabel(d))}</span>
      </div></div>`).join('')
    : '';
  const pendingHtml = pending.length
    ? `<div class="fleet-sub">${escapeHtml(t('fleet.pendingTitle'))}</div>` +
      pending.map((d) => `<div class="fleet-row"><div class="fleet-row-main">
        <span class="fleet-row-name">${escapeHtml(String(d.deviceName || d.deviceId || ''))}</span>
        <span class="fleet-row-ver">${escapeHtml(d.version ? 'v' + d.version : t('fleet.versionUnknown'))}</span>
        <span class="fleet-badge">${escapeHtml(t('fleet.stage.waiting-online'))}</span>
      </div></div>`).join('')
    : '';

  const canRetry = summaryJob && !running &&
    Array.isArray(summaryJob.failed) && summaryJob.failed.some((f) => f && f.failure !== 'switch-rolled-back');

  overlay.innerHTML = `<div id="${CARD_ID}" class="nf-glass" role="dialog" aria-modal="true">
    <div class="fleet-head">
      <span class="fleet-title">${escapeHtml(t('fleet.title'))}</span>
      <button class="fleet-close" data-fleet-close="1" title="${escapeHtml(t('fleet.close'))}"><i data-lucide="x"></i></button>
    </div>
    <div class="fleet-body">
      <div class="fleet-sub">${escapeHtml(t('fleet.subtitle', {
        current: curVersion || '?',
        target: (summaryJob && summaryJob.targetVersion) || (plan && plan.targetVersion) || t('fleet.versionUnknown'),
        stages: stages,
      }))}</div>
      ${blocking}
      ${bodyRows || `<div class="fleet-sub">${escapeHtml(t('fleet.empty'))}</div>`}
      ${manualHtml}
      ${pendingHtml}
      ${renderSummary(summaryJob) || renderPlanSummary(plan)}
      ${localStage === 'restart' ? `<div class="fleet-blocking">${escapeHtml(t('fleet.reconnecting'))}</div>` : ''}
    </div>
    <div class="fleet-foot">
      ${running
        ? `<button class="fleet-btn" data-fleet-action="cancel" ${pendingAction ? 'disabled' : ''}>${escapeHtml(t('fleet.cancel'))}</button>`
        : `<button class="fleet-btn" data-fleet-action="close">${escapeHtml(t('fleet.close'))}</button>`}
      ${canRetry ? `<button class="fleet-btn primary" data-fleet-action="retry" ${pendingAction ? 'disabled' : ''}>${escapeHtml(t('fleet.retry'))}</button>` : ''}
      ${!running && !summaryJob ? `<button class="fleet-btn primary" data-fleet-action="start" ${pendingAction ? 'disabled' : ''}>${escapeHtml(t('fleet.startConfirm'))}</button>` : ''}
    </div>
  </div>`;
  createIconsIn(overlay);
  syncOpen();
}

function syncOpen() {
  const overlay = document.getElementById(OVERLAY_ID);
  if (!overlay) return;
  overlay.classList.toggle('on', cardOpen || jobRunning());
}

// ── 交互 ──────────────────────────────────────────────────────────────────
function closeCard() {
  cardOpen = false;
  syncOpen();
}

/** 打开/刷新卡，并现取一次快照（服务端单点，前端不留记忆）。 */
export function openFleetUpdatePanel() {
  cardOpen = true;
  ensureDom();
  sendWs({ type: 'fleetUpdateSnapshot' });
  renderCard();
  return fetchPlan();
}

/** 现取计划（`GET /api/update/fleet`：版本核对表 + 收口四句）。失败静默降级到快照。 */
async function fetchPlan() {
  try {
    const resp = await fetch('/api/update/fleet', { headers: authHeaders() });
    if (!resp.ok) return false;
    const body = await resp.json();
    fleetFrame = Object.assign({}, fleetFrame || {}, body);
    renderCard();
    return true;
  } catch {
    return false;
  }
}

function authHeaders() {
  // 凭据单点：`getAuthToken()`（neblink.js 的 localStorage 键 = `key('token')` 品牌
  // 命名空间，禁止本模块另拼键名——拼错就是静默 401）。
  const token = getAuthToken();
  return token ? { Authorization: 'Bearer ' + token } : {};
}

/** 发起（必带 confirm：作者令「更新动作要有确认与审计」）。 */
export function startFleetUpdate(source) {
  pendingAction = 'start';
  renderCard();
  sendWs({ type: 'fleetUpdateStart', confirm: true, source: source || 'console' });
}

function cancelFleetUpdate() {
  pendingAction = 'cancel';
  renderCard();
  sendWs({ type: 'fleetUpdateCancel' });
}

function retryFleetFailures() {
  pendingAction = 'retry';
  renderCard();
  sendWs({ type: 'fleetUpdateRetry' });
}

/**
 * 单台「更新此设备」：走**既有** `remoteUpdate` 命令（hotupdate 批 3 的同一腿，
 * 零新消息类型），并打开本卡看全局进度——单台与批量共用一张卡。
 */
export function updateSingleDevice(deviceName) {
  if (!deviceName) return;
  sendWs({ type: 'remoteUpdate', device: deviceName, beta: false });
  openFleetUpdatePanel();
}

// ── 帧订阅 ────────────────────────────────────────────────────────────────
let bound = false;

/** 初始化（main.js 调一次；幂等）。 */
export function initFleetUpdate() {
  if (bound) return;
  bound = true;
  ensureDom();

  onMessage('fleetUpdate', (msg) => {
    // 帧就是快照（含 plan 合并面由 HTTP 腿给）——直接镜像，不合并旧值里的过时字段。
    const prevPlan = fleetFrame && fleetFrame.plan ? fleetFrame.plan : null;
    fleetFrame = Object.assign({}, msg, { plan: msg && msg.plan ? msg.plan : prevPlan });
    if (jobRunning()) cardOpen = true;
    renderCard();
  });

  onMessage('fleetUpdateResult', (msg) => {
    pendingAction = '';
    if (msg && msg.snapshot) {
      const prevPlan = fleetFrame && fleetFrame.plan ? fleetFrame.plan : null;
      fleetFrame = Object.assign({}, msg.snapshot, { plan: msg.snapshot.plan || prevPlan });
    }
    if (msg && msg.ok === false && msg.error) {
      // 回执失败也要可见（不静默）：卡内以阻断条显示。
      showInlineError(String(msg.error));
    }
    if (msg && msg.ok === true && msg.action === 'start') cardOpen = true;
    renderCard();
  });

  // 连接初始帧（serverConfig）里携带的 fleetUpdate 快照——刷新/换设备后一帧接续。
  onMessage('serverConfig', (msg) => {
    if (msg && msg.fleetUpdate && msg.fleetUpdate.type === 'fleetUpdate') {
      const prevPlan = fleetFrame && fleetFrame.plan ? fleetFrame.plan : null;
      fleetFrame = Object.assign({}, msg.fleetUpdate, { plan: prevPlan });
      if (jobRunning()) cardOpen = true;
      renderCard();
    }
  });

  // 断线重连：服务端是唯一真源 ⇒ 重连即重取快照（不靠前端记忆拼状态）。
  onReconnect(() => {
    sendWs({ type: 'fleetUpdateSnapshot' });
    if (jobRunning()) renderCard();
  });

  // 卡内交互（文档级委托：重渲染不叠监听）。
  document.addEventListener('click', (e) => {
    const target = e.target instanceof Element ? e.target : null;
    if (!target) return;
    const action = target.closest('[data-fleet-action]');
    if (action instanceof HTMLElement) {
      const kind = action.dataset.fleetAction;
      if (kind === 'start') startFleetUpdate('console');
      else if (kind === 'cancel') cancelFleetUpdate();
      else if (kind === 'retry') retryFleetFailures();
      else if (kind === 'close') closeCard();
      return;
    }
    if (target.closest('[data-fleet-close]')) {
      closeCard();
      return;
    }
    const row = target.closest('[data-fleet-row]');
    if (row instanceof HTMLElement) {
      const id = row.dataset.fleetRow || '';
      if (!id) return;
      if (expanded.has(id)) expanded.delete(id);
      else expanded.add(id);
      renderCard();
    }
  });
}

function showInlineError(text) {
  const overlay = document.getElementById(OVERLAY_ID);
  if (!overlay) return;
  const box = overlay.querySelector('.fleet-body');
  if (!box) return;
  let el = box.querySelector('.fleet-inline-error');
  if (!el) {
    el = document.createElement('div');
    el.className = 'fleet-blocking fleet-inline-error';
    box.insertBefore(el, box.firstChild);
  }
  el.textContent = text;
}
