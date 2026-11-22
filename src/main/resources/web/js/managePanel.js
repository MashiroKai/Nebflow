// managePanel.js — Sub-agent management controls (2026-08-22 user ruling).
//
// User ruling 2026-08-22: sub-agent windows are MANAGEMENT surfaces, not
// conversation surfaces — the message input bar is removed and replaced by
// state display + control buttons mapped onto the backend's AgentControl
// capabilities (WS layer landed 2026-08-22 @179a009e):
//   - stop   → WS {type:'cancelAgent', sessionId} (terminal cancel; result
//              frame cancelAgentResult {ok, sessionId, message?|error?})
//              with graceful fallback to {type:'interrupt'} when the result
//              reports the handler missing (pre-restart instances)
//   - retry  → WS {type:'restartAgent', sessionId, level:'soft'} (soft only —
//              rollback/prune/full mutate session history, never bare-exposed)
//   - stuck  ← WS taskStuck {sessionId, kind, idleSecs, action}
//   - retries← WS taskStuck action=restart count + snapshot retryCount
//   - uptime ← snapshot startedAt (epoch ms, 0=unknown) / agentStart arrival
//   - failed ← snapshot status startsWith('Error') (no WS failed event yet)
//
// Permission matrix (AgentControl spec §4): Delegate/SubTask/Ephemeral are
// operable; Team/Flow/Root are read-only (buttons greyed + tooltip reason).
// Buttons are state-linked — no permanent dead buttons: stop only while the
// agent is processing, retry only on failed/stuck.

import { sendWs, onMessage } from './ws.js';
import { t } from './i18n.js';
import { showToast } from './modal.js';
import { isBgAgentId } from './utils.js';
// 冻结态误报批（2026-11-05）：冻结行的恢复入口判据（需人介入才给按钮）。
// 冻结状态两套真相批（2026-11-05）：改读后端权威块的 `wakeable`（单一真相）。
// 冻结通知策略批（2026-11-05）：provider 故障族额外排除「重启并续跑」——这一族不是任务
// 失败，RestartAgent 会扣 supervisor 重启预算；它只给「唤醒」（幂等 UserInput 腿）。
import { wakeableOf, delegateFreezeIsInfra } from './freezeState.js';

/** Kinds the user may stop/restart (AgentControl §4 operable bucket). */
// 'SubTask' 自阶段 B3 换标后不再由引擎产出（子代理会话 = Delegate），保留该成员是
// 对旧快照/旧盘行的兼容容忍（无副作用：新帧永不为该词）。
export const OPERABLE_KINDS = new Set(['Delegate', 'SubTask', 'Ephemeral']);

/** Read-only kinds get greyed buttons with a reason tooltip.
 *  Project 会话卡死修复：新 Project 系统的 Flow 会话（node- 前缀与 dispatcher- 前缀，
 *  会话 id 可区分——dag- 旧 flow 会话不在此列）有观察桥取消通道（后端
 *  AgentControl cancel / WS cancelAgent 已放行），stop 可操作；restart 对
 *  单次会话无意义（cancel + 重新触发），retry 恒隐。 */
export function managePolicy(kind, sessionId) {
  // B5：`kind === 'Team'` 分支已删——枚举值退场（Mail 激活的长驻会话现为 Root），
  // 该分支再无生产者；Root 仍只读，但原因文案改为自我保护口径（不再借用团队措辞）。
  if (kind === 'Flow') {
    if (isBgAgentId(sessionId)) return { operable: true, projectFlow: true, reason: '' };
    return { operable: false, reason: t('manage.readonlyFlow') };
  }
  if (kind === 'Root') return { operable: false, reason: t('manage.readonlyRoot') };
  return { operable: true, reason: '' };
}

/** True while the agent is in a processing phase (stop button visible). */
export function isProcessingStatus(status) {
  return status === 'running' || status === 'thinking' || status === 'tool' || status === 'responding';
}

/** True when a restart makes sense (retry button visible).
 *  'failed' comes from the activeAgents snapshot status ("Error(msg)" form —
 *  the backend has no WS failed event for sub-agents yet).
 *
 *  冻结态误报批（2026-11-05）：`frozen` **按分类**进白名单——需人介入的冻结
 *  （循环检测 L2 / delegate 错误冻结 / 已升级到人）必须有可见的恢复入口（这里 =
 *  「重启并续跑」= restartAgent soft，与 AgentControl restart 同机制，同会话从
 *  检查点续跑）；自动恢复族（时间表/退避/探测）**不给按钮**（引擎正在自己做的事
 *  不该变成用户的按钮）。判据单点 = freezeState.js（后端 FreezeDisplay 的呈现面）。
 *  @param {string} status  @param {any} [meta] 行/弹窗 meta（读 freezeKind / 原因线值） */
export function isRetryableStatus(status, meta) {
  // 冻结状态两套真相批（2026-11-05）：恢复入口只认后端权威块的 `wakeable`
  // （旧后端无块 ⇒ 退回分类表，同一张表的退化副本）。
  // 冻结通知策略批（2026-11-05 作者令 + 对齐清单第③条）：provider 故障族再叠一把闸——
  // 即便后端块说「可唤醒」，也**不给**「重启并续跑」（RestartAgent(Soft) 扣 2 次/5min
  // 重启预算，而 provider 故障不是任务失败）；该族的入口是行上的「唤醒」按钮。
  if (status === 'frozen') {
    const m = meta || { frozen: true };
    // ① 权威块优先：恢复入口只认后端 FreezeTruth 的 wakeable（两套真相批）。
    if (!wakeableOf(meta || { frozen: true })) return false;
    // ② 再叠 provider 故障族闸：即便可唤醒，也不给「重启并续跑」。
    if (delegateFreezeIsInfra(m)) return false;
    return true;
  }
  return status === 'failed' || status === 'stuck' || status === 'stopped';
}

/** Snapshot status parse: AgentStatus.toString yields "Error(msg)" for the
 *  failed state (msg may contain spaces/parens — prefix check only). */
export function isFailedSnapshotStatus(status) {
  return typeof status === 'string' && status.startsWith('Error');
}

// ── cancelAgent result-driven stop with graceful fallback ─────────────
// The WS cancelAgent handler landed 2026-08-22 (@179a009e). Pre-restart
// instances silently drop unknown frames (handleMessage case _ => IO.unit) —
// no cancelAgentResult ever arrives. So: send cancelAgent, arm a short timer;
// a result frame settles it, timer expiry falls back to interrupt (the
// pre-upgrade semantics) so the stop button never goes dead on old backends.
const CANCEL_FALLBACK_MS = 1500;
const pendingCancelTimers = new Map(); // sessionId → timeout handle
const cancelListeners = new Set();

/** Popup modules register a listener for settled cancel outcomes
 *  (bg and flow popups each watch their own open session). */
export function onCancelResult(fn) { cancelListeners.add(fn); }

/** Feed a cancelAgentResult frame: clear the fallback timer, notify listeners. */
export function noteCancelResult(msg) {
  const timer = pendingCancelTimers.get(msg.sessionId);
  if (timer) { clearTimeout(timer); pendingCancelTimers.delete(msg.sessionId); }
  for (const fn of cancelListeners) fn(msg);
}

// WS result frame for the panel stop action (whitelisted in ws.js TERMINAL).
// ok=false → error toast (stale id / read-only kind); ok=true is silent — the
// footer transition rides the subsequent interrupted/agentEnd event.
onMessage('cancelAgentResult', (msg) => {
  noteCancelResult(msg);
  if (msg && msg.ok === false && msg.error) {
    showToast(t('manage.stopFailed') + ' — ' + msg.error, 'error');
  }
});

/** Relative uptime formatter — shared with the Sub-Agents dropdown rows
 *  (main.js renderBgAgentDropdown, subagents-panel spec 2026-08-25). */
export function fmtUptime(ms) {
  if (ms == null || ms < 0) return '';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + (s % 60) + 's';
  const h = Math.floor(m / 60);
  return h + 'h ' + (m % 60) + 'm';
}

/** Build the right-side management cluster for a popup footer.
 *  Returns { root, stopBtn, retryBtn, uptimeEl, retriesEl }. */
export function buildManageBar() {
  const root = document.createElement('div');
  root.className = 'fa-manage';
  const retriesEl = document.createElement('span');
  retriesEl.className = 'fa-retries';
  retriesEl.style.display = 'none';
  const uptimeEl = document.createElement('span');
  uptimeEl.className = 'fa-uptime';
  uptimeEl.style.display = 'none';
  // 按钮形态统一批（2026-11-18）：两枚管理钮挂设置页动作族（.cfg-btn 配色 +
  // .set-btn 几何令牌），**不再**挂 .glass-control（材质类只给配方不给几何 ⇒ 修前
  // 桌面 28px 圆钮 / 手机 44px 方钮两档并存，且带 glass 投影）。族类落地见
  // scripts/check-settings-button-uniform.mjs 的登记表。
  const stopBtn = document.createElement('button');
  stopBtn.className = 'fa-mgmt-btn fa-mgmt-stop cfg-btn set-btn';
  stopBtn.title = t('manage.stop');
  stopBtn.setAttribute('aria-label', t('manage.stop'));
  stopBtn.innerHTML = '<i data-lucide="square"></i>';
  const retryBtn = document.createElement('button');
  retryBtn.className = 'fa-mgmt-btn fa-mgmt-retry cfg-btn set-btn';
  retryBtn.title = t('manage.retry');
  retryBtn.setAttribute('aria-label', t('manage.retry'));
  retryBtn.innerHTML = '<i data-lucide="rotate-cw"></i>';
  root.append(retriesEl, uptimeEl, stopBtn, retryBtn);
  return { root, stopBtn, retryBtn, uptimeEl, retriesEl };
}

/** Wire stop/retry actions for a session.
 *  stop = cancelAgent (terminal) with interrupt fallback for pre-upgrade
 *  backends (see CANCEL_FALLBACK_MS); retry = restartAgent soft only. */
export function bindManageActions(bar, getSessionId) {
  bar.stopBtn.addEventListener('click', () => {
    const sid = getSessionId();
    if (!sid) return;
    sendWs({ type: 'cancelAgent', sessionId: sid });
    showToast(t('manage.stopSent'), 'info');
    // Fallback: pre-restart instances drop unknown frames silently — if no
    // cancelAgentResult settles this session within the window, send the
    // legacy interrupt so the button never goes dead.
    if (pendingCancelTimers.has(sid)) clearTimeout(pendingCancelTimers.get(sid));
    pendingCancelTimers.set(sid, setTimeout(() => {
      pendingCancelTimers.delete(sid);
      sendWs({ type: 'interrupt', sessionId: sid });
    }, CANCEL_FALLBACK_MS));
  });
  bar.retryBtn.addEventListener('click', () => {
    const sid = getSessionId();
    if (!sid) return;
    sendWs({ type: 'restartAgent', sessionId: sid, level: 'soft' });
    showToast(t('manage.retrySent'), 'info');
  });
}

/** State-linked visibility + permission matrix.
 *  entry.meta: { status, kind, stuck: {idleSecs, action}|null, retries, startedAt }
 *  sessionId（可选）：node-/dispatcher- 单次会话的 stop-only 放行判定键。 */
export function syncManageControls(bar, meta, sessionId) {
  if (!bar) return;
  const policy = managePolicy(meta.kind || '', sessionId);
  const status = meta.status || '';
  const processing = isProcessingStatus(status);
  const retryable = isRetryableStatus(status, meta);

  if (!policy.operable) {
    // Read-only: greyed, disabled, tooltip explains why.
    for (const btn of [bar.stopBtn, bar.retryBtn]) {
      btn.disabled = true;
      btn.classList.add('readonly');
      btn.title = policy.reason;
      btn.style.display = 'flex';
    }
    return;
  }
  // Operable: show only when the action makes sense (no dead buttons).
  bar.stopBtn.disabled = false;
  bar.retryBtn.disabled = false;
  bar.stopBtn.classList.remove('readonly');
  bar.retryBtn.classList.remove('readonly');
  bar.stopBtn.title = t('manage.stop');
  bar.retryBtn.title = t('manage.retry');
  bar.stopBtn.style.display = processing ? 'flex' : 'none';
  // 单次会话（node-/dispatcher-）：cancel + 重新触发取代 restart——retry 恒隐。
  bar.retryBtn.style.display = policy.projectFlow ? 'none' : (retryable ? 'flex' : 'none');

  // Retries chip (agentRetryStatus count) — visible once > 0.
  if (bar.retriesEl) {
    const n = meta.retries || 0;
    bar.retriesEl.style.display = n > 0 ? '' : 'none';
    bar.retriesEl.textContent = '×' + n;
    bar.retriesEl.title = t('manage.retry') + ' ×' + n;
  }
  // Uptime — from agentStart arrival (frontend) or snapshot startedAt.
  if (bar.uptimeEl) {
    const started = meta.startedAt || null;
    if (started && status !== 'done') {
      bar.uptimeEl.style.display = '';
      bar.uptimeEl.textContent = t('manage.uptime', { time: fmtUptime(Date.now() - started) });
    } else {
      bar.uptimeEl.style.display = 'none';
    }
  }
}
