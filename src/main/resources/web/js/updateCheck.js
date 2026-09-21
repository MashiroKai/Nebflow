/**
 * updateCheck.js — app update check chain owner (09-05 五项裁定⑤).
 *
 * One module owns everything update-related on the frontend:
 *   • WS handlers: updateCheckResult / updateStarted / updateCompleted
 *     (moved here from main.js so the auto path and the manual path share
 *     one routing table — no second listener can double-echo).
 *   • Silent auto checks: one shot 60s after boot, then every 6h.
 *   • The settings-btn green dot (activityBar.setUpdateDot) lit when a check
 *     finds a newer version; cleared when the user opens settings (see
 *     activityBar.bindSettingsButton) or an update completes successfully.
 *
 * Design decisions (documented per task requirement ⑤):
 *   • 60s boot delay — the first minute is the startup thundering herd (WS
 *     handshake, serverConfig echo, plugin catalog, session history). An
 *     update check is in no hurry; deferring it avoids competing with the
 *     boot window and guarantees `state.connected` has settled.
 *   • 6h interval — release cadence is far slower than hourly; 6h finds a new
 *     build the same day at negligible cost.
 *   • Page hidden → keep running. Browsers already throttle background
 *     setInterval to ≥1/min, which is irrelevant at a 6h cadence; a
 *     visibilitychange pause/resume state machine would add complexity for
 *     zero user-visible difference (the check is silent and cheap).
 *   • Failure/offline → silent. No toast, no modal, no dot; a failed cycle
 *     simply yields and the next one retries (requirement f, 零打扰).
 *   • Manual/auto disambiguation: the About button flags its check via
 *     notifyManualUpdateCheck() BEFORE the WS roundtrip. A flagged response
 *     is echoed into the About section and clears the flag; an unflagged
 *     (auto) response updates state + dot and only writes the About section
 *     if it is currently in the DOM — it can never stomp an in-flight manual
 *     echo, because auto cycles are skipped while the flag is up.
 */
import state from './state.js';
import { t } from './i18n.js';
import { onMessage, sendWs } from './ws.js';

const FIRST_CHECK_DELAY_MS = 60_000;
const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;
// Safety valve: if a manual check's response never arrives (offline, gateway
// restart), the flag must not block auto checks forever.
const MANUAL_FLAG_TTL_MS = 30_000;

let manualCheckPending = false;
let initialized = false;

/**
 * The update-available dot on #settings-btn. Lives here (not activityBar.js)
 * because the dot is a feature of the update-check chain: this module lights
 * it on a found update and clears it on completion; activityBar.js imports
 * this same function for its clear-on-open behavior. Keeping it here also
 * keeps the static import graph acyclic (activityBar -> sidebar already).
 */
export function setUpdateDot(show) {
  const btn = document.getElementById('settings-btn');
  if (!btn) return;
  let dot = btn.querySelector('.settings-update-dot');
  if (show && !dot) {
    dot = document.createElement('span');
    dot.className = 'settings-update-dot';
    btn.appendChild(dot);
  } else if (!show && dot) {
    dot.remove();
  }
}

// ── 统一进度面（hotupdate 批 3 · G6）────────────────────────────────────────
//
// 消费面（**本模块是唯一消费点**，与批 1 的契约单点同构——禁第二套订阅/第二套渲染器）：
//   · `updateProgress`（12 键：type/phase/messageKey/state/reason/detail/source/channel/
//     idempotencyKey/currentVersion/latestVersion/progress）——相位文案**一律**由帧内
//     `messageKey` 解析 locale 表（🔴 本文件不写任何相位展示字面）；
//   · `updateResult`（受理回执三分支 already-in-flight / busy / refused）；
//   · `restartStatus` / `restartResult`（既有热重启进度帧及其应答 = 统一帧的子集）。
//
// 视觉（设计 §7:139-145 五行逐条，色值一律走既有 token，零新色）：
//   未完成态 pending      = 中性次要文本      ⇔ var(--color-frame-text-muted)
//   进行态   in-progress  = 单一强调色（每视口至多一个彩色事件）⇔ rgb(var(--sapphire))
//   失败态   failed       = 中性灰 + 明确原因（**不用红块**）  ⇔ var(--color-frame-text)
//   回滚态   rolled-back  = 中性灰 + 版本对照（**不用红块**）  ⇔ var(--color-frame-text)
//   终态     completed    = 静默收敛         ⇔ var(--color-frame-text-muted)
// 🔴 裁定 10：失败 / 回滚**仅状态行**——不新增轻提示、不弹窗、无红色块。
const UPDATE_STATE_VISUAL = {
  'pending': { color: 'var(--color-frame-text-muted)' },
  'in-progress': { color: 'rgb(var(--sapphire))' },
  'failed': { color: 'var(--color-frame-text)' },
  'rolled-back': { color: 'var(--color-frame-text)' },
  'completed': { color: 'var(--color-frame-text-muted)' },
};

/** 既有重启帧的相位 → 统一四态归属（**机器 token 映射**，展示文案仍从 locale 表解析）。 */
const RESTART_PHASE_STATE = {
  'quiesce': 'in-progress',
  'draining': 'in-progress',
  'spawning': 'in-progress',
  'handing-over': 'in-progress',
  'completed': 'completed',
  'failed': 'failed',
};

/** 最近一帧的统一进度快照。面板关闭时元素不存在 ⇒ 只更新快照不渲染（既有
 *  「元素不存在即静默」模式）；面板重开由 [[restoreUpdateProgress]] 复现
 *  （与既有 `state.updateAvailable` 回填同款口径）。 */
let lastProgress = null;
/** 是否已见到统一进度帧（legacy `updateStarted`/`updateCompleted` 的文案降级标志位）。 */
let unifiedProgressSeen = false;
/** 更新是否在途（未到终态）——重启子集帧的仲裁标志（见 `restartStatus` 订阅）。 */
let updateActive = false;

/** 相位文案：键取自帧内 `messageKey`（缺 ⇒ 由 `phase` 派生同名键），
 *  `{version}`/`{reason}` 两个占位由帧字段解析（原因走 `update.reason.*` 表）。
 *  🔴 无任何展示字面——全部经 `t()`。 */
function phaseText(snap) {
  const key = snap.messageKey || (snap.phase ? 'update.phase.' + snap.phase : '');
  if (!key) return '';
  const version = snap.latestVersion || (lastProgress && lastProgress.latestVersion)
    || state.serverVersion || '';
  const reason = snap.reason ? t('update.reason.' + snap.reason) : t('update.reason.unspecified');
  return t(key, { version, reason });
}

/** 统一进度面渲染（单点）：状态行 = 相位（四态视觉）+ 进度块 = 影响面/诊断明细 + 版本对照。 */
function renderUnifiedProgress() {
  const snap = lastProgress;
  if (!snap) return;
  const statusEl = document.getElementById('update-status');
  const progressEl = document.getElementById('update-progress');
  const impactEl = document.getElementById('update-progress-impact');
  const versionsEl = document.getElementById('update-progress-versions');
  if (statusEl) {
    statusEl.textContent = phaseText(snap);
    statusEl.style.color = UPDATE_STATE_VISUAL[snap.state || 'pending'].color;
    statusEl.dataset.updateState = snap.state || 'pending';
    statusEl.dataset.updatePhase = snap.phase || '';
  }
  if (progressEl) {
    progressEl.style.display = 'block';
    if (impactEl) {
      // 影响面预告（设计 §7:147「直出五域详情」）：数据源 = 帧内 `detail`
      // （冻结相位承载既有五域快照 `QuiesceReport.detail` 的唯一现成透出面）。
      impactEl.textContent = snap.detail
        ? (snap.phase === 'freezing' ? t('update.impact', { detail: snap.detail }) : snap.detail)
        : '';
    }
    if (versionsEl) {
      versionsEl.textContent = (snap.currentVersion || snap.latestVersion)
        ? t('update.versions', {
          current: snap.currentVersion || '—',
          latest: snap.latestVersion || '—',
        })
        : '';
    }
  }
}

/** 「立即更新」按钮复位（拒绝 / 忙 / 已在途三分支都不会有 updateStarted 帧）。 */
function restoreUpdateButton() {
  const btn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-do-update'));
  if (btn) { btn.textContent = t('settings.updateNow'); btn.disabled = false; }
}

/** 面板重开时复现最近一帧的统一进度（设置面板每次 renderSettings 都会重建元素）。
  *  @returns {boolean} true = 已按统一进度帧渲染（调用方据此让出状态行回填）。 */
export function restoreUpdateProgress() {
  if (!lastProgress) return false;
  renderUnifiedProgress();
  return true;
}

export function initUpdateCheck() {
  if (initialized) return;
  initialized = true;

  onMessage('updateCheckResult', (msg) => {
    const wasManual = manualCheckPending;
    manualCheckPending = false;
    const statusEl = document.getElementById('update-status');
    const actionEl = document.getElementById('update-action');

    if (msg.error) {
      // Manual check → surface the error in About; auto check → silent.
      if (wasManual && statusEl) statusEl.textContent = t('settings.updateError');
      return;
    }

    // Record the outcome in state (single source of truth for the dot and
    // for the About echo after any settings re-render).
    state.updateAvailable = !!msg.hasUpdate;
    state.latestVersion = msg.latestVersion || '';
    setUpdateDot(state.updateAvailable);

    // Both paths reuse the same About display; with the settings panel
    // closed the elements simply don't exist and the result stays silent.
    if (!statusEl) return;
    if (msg.hasUpdate) {
      statusEl.textContent = t('settings.updateAvailable', { version: msg.latestVersion });
      if (actionEl) actionEl.style.display = 'block';
    } else {
      statusEl.textContent = t('settings.upToDate');
      if (actionEl) actionEl.style.display = 'none';
    }
  });

  onMessage('updateStarted', () => {
    const statusEl = document.getElementById('update-status');
    // 批 1 的既有开始帧保留原样（向后兼容）；统一进度帧一旦到达即以 `messageKey`
    // 解析的相位文案为准，本帧不再抢状态行（两帧到达次序无保证，故按「谁更具体谁说话」仲裁）。
    if (statusEl && !unifiedProgressSeen) statusEl.textContent = t('settings.updating');
  });

  onMessage('updateCompleted', (msg) => {
    const btn = /** @type {HTMLButtonElement|null} */ (document.getElementById('btn-do-update'));
    const statusEl = document.getElementById('update-status');
    if (btn) { btn.textContent = t('settings.checkUpdate'); btn.disabled = false; }
    if (msg.success) {
      state.updateAvailable = false;
      state.latestVersion = '';
      setUpdateDot(false);
      if (statusEl && !unifiedProgressSeen) statusEl.textContent = '✓ ' + t('settings.upToDate');
      const actionEl = document.getElementById('update-action');
      if (actionEl) actionEl.style.display = 'none';
    } else if (statusEl && !unifiedProgressSeen) {
      statusEl.textContent = '✗ ' + (msg.error || t('settings.updateError'));
    }
  });

  // ── 统一进度帧消费（hotupdate 批 3 · G6）──────────────────────────────────
  // 订阅面 = 既有广播通道上的既有帧：updateProgress（12 键，批 1 契约）+
  // updateResult（受理/已在途/更新中/拒绝三分支）。🔴 零新消息类型。
  onMessage('updateProgress', (msg) => {
    unifiedProgressSeen = true;
    lastProgress = {
      messageKey: msg.messageKey || (msg.phase ? 'update.phase.' + msg.phase : ''),
      state: msg.state || '',
      phase: msg.phase || '',
      reason: msg.reason || '',
      detail: msg.detail || '',
      source: msg.source || '',
      currentVersion: msg.currentVersion || '',
      latestVersion: msg.latestVersion || '',
      phaseKind: 'update',
    };
    updateActive = (lastProgress.state !== 'completed' && lastProgress.state !== 'failed'
      && lastProgress.state !== 'rolled-back');
    renderUnifiedProgress();
  });

  onMessage('updateResult', (msg) => {
    const statusEl = document.getElementById('update-status');
    const phaseTxt = msg.messageKey || msg.phase
      ? phaseText({ messageKey: msg.messageKey, phase: msg.phase })
      : '';
    let text = '';
    if (msg.status === 'already-in-flight') {
      text = t('update.receipt.alreadyInFlight', { phase: phaseTxt });
    } else if (msg.status === 'busy') {
      text = t('update.receipt.busy', {
        source: msg.source ? t('update.source.' + msg.source) : '—',
        phase: phaseTxt,
      });
    } else if (msg.status === 'refused') {
      const reason = msg.reason ? t('update.reason.' + msg.reason) : t('update.reason.unspecified');
      text = t('update.receipt.refused', { reason });
      if (msg.error) text += ' — ' + msg.error;
    } else {
      text = msg.error || t('settings.updateError');
    }
    // 显式可见态（裁定 10：仅状态行，不加轻提示/弹窗/红块）+ 按钮复位
    // （拒绝/忙/已在途都不会有 updateStarted ⇒ 不复位就会永久停在「更新中…」禁点态）。
    if (statusEl) {
      statusEl.textContent = text;
      statusEl.style.color = UPDATE_STATE_VISUAL[(msg.status === 'refused') ? 'failed' : 'pending'].color;
      statusEl.dataset.updateState = (msg.status === 'refused') ? 'failed' : 'pending';
    }
    restoreUpdateButton();
  });

  // 既有热重启进度帧（restartStatus）= 统一进度帧的**子集**（设计 §4:104「既有重启进度帧
  // 作为其子集保留不删」）⇒ 复用同一进度面与同一渲染器，零第二通道。
  // 🔴 仲裁：更新在途时统一帧是权威面（更新自身的重启相也会发本帧）⇒ 该期忽略子集帧，
  // 否则「正在重启」会被「正在派生新进程」覆盖。
  onMessage('restartStatus', (msg) => {
    if (updateActive) return;
    lastProgress = {
      messageKey: 'restart.phase.' + msg.phase,
      state: RESTART_PHASE_STATE[msg.phase] || 'in-progress',
      phase: msg.phase,
      reason: '',
      detail: msg.detail || '',
      source: '',
      currentVersion: state.serverVersion || '',
      latestVersion: state.latestVersion || '',
      phaseKind: 'restart',
    };
    renderUnifiedProgress();
  });

  onMessage('restartResult', (msg) => {
    const statusEl = document.getElementById('update-status');
    if (statusEl) {
      statusEl.textContent = msg.ok
        ? t('settings.restartAccepted')
        : t('settings.restartFailed', { error: msg.error || '' });
      statusEl.style.color = UPDATE_STATE_VISUAL[msg.ok ? 'in-progress' : 'failed'].color;
      statusEl.dataset.updateState = msg.ok ? 'in-progress' : 'failed';
    }
  });

  const autoCheck = () => {
    // Skip while disconnected (response could never come) or while a manual
    // check is in flight (never interleave with its About echo).
    if (!state.connected || manualCheckPending) return;
    sendWs({ type: 'checkUpdate' });
  };

  setTimeout(autoCheck, FIRST_CHECK_DELAY_MS);
  setInterval(autoCheck, CHECK_INTERVAL_MS);
}

/**
 * Flag a user-initiated check (sidebar About button). Must be called BEFORE
 * sendWs({type:'checkUpdate'}) so the response is routed as manual.
 */
export function notifyManualUpdateCheck() {
  manualCheckPending = true;
  setTimeout(() => { manualCheckPending = false; }, MANUAL_FLAG_TTL_MS);
}
