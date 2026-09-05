import { key } from './branding.js'; // MUST be first: storage-key migration runs at module init, before state.js/i18n.js read localStorage.
import state from './state.js';
import { initBranding } from './brand.js';

// Branding first: correct the tab title before any other module body runs.
initBranding();

// Embedded-context gate (flag set by the inline classic script in index.html —
// see its comment). Module imports above already executed, but they are
// side-effect-free at import time (connect/restore/init all run from this
// file's body), so throwing here stops the boot before anything opens WS
// connections or restores canvas tabs — which is what recursed.
if (document.documentElement.dataset.nfEmbedded === '1') {
  document.title = 'nebflow (embedded)';
  document.addEventListener('DOMContentLoaded', () => {
    document.body.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100vh;font:13px -apple-system,BlinkMacSystemFont,sans-serif;color:#888;padding:24px;text-align:center">nebflow 预览已停止——该页面试图在应用内嵌套启动（已防止无限递归）。</div>';
  });
  throw new Error('[nf] embedded context — boot refused (anti-recursion guard)');
}
import { LS_SESSIONS_KEY, LS_MODEL_INFO_KEY } from './state.js';
import { initSpinner, initMarkdown, smartScroll, renderMarkdownWithMath } from './utils.js';
import { connect, onMessage, sendWs, onReconnect } from './ws.js';
import {
  setBusy, clearBusy, clearStatus,
  renderUserBubble, renderInjectedBubble, appendAiText, finishAi,
  appendAgentText, finishAgent, getAgentColor,
  renderTool, renderToolPending, renderError, renderTimeoutNotice,
  renderSystemBubble, renderRetryStatus, clearRetryStatus,
  renderCompactStartCard, renderCompactDoneCard, renderCompactFailCard,
  showOptions, renderAskUser, renderPermissionPrompt, closeAskUserCard,
  renderAttachmentPreview,
  appendAskAnswer, finishAskAnswer, renderAskError,
  appendThinkingDelta, finishThinking,
  appendToolStreamDelta, cancelToolStreamRAF,
  formatResumeClock
} from './chat.js';
import {
  initNavTabs, renderSessionSidebar, renderAgentList, renderSettings,
  deleteSession, formatSessionTime, setSessionAttention,
  initHeaderModelInfo,
  persistUnread, createNewFolder, getCurrentFolderId,
  resetChatForActiveSession,
  computeAgentStates
} from './sidebar.js';
import { initOnboarding } from './onboarding.js';
import {
  showNewSessionModal, hideModals, confirmNewSession,
  showDeleteModal, confirmDeleteSession,
  showDeleteFolderModal,
  showAgentModal, hideAgentModal, initModals
} from './modal.js';
import { send, handleSlash, addFileAttachment, initInput, initGlobalFileDrop, injectUserMessage, enterAskMode, cancelAskMode, registerSkillCommands, drainMessageQueue, restoreQueue } from './input.js';import { saveMsg, loadMsgs, restoreFromStorage, restoreFromBackendHistory, migrateLegacyIfNeeded, emergencyCacheCleanup, findLastRealMessage, saveAskMsgDedup } from './persistence.js';
import { initMicOrb } from './micOrb.js';
import { renderTaskList, sessionShowsTeamTasks } from './taskList.js';
import { renderWithRegistry, cleanupCardIframes } from './cardRegistry.js';
import { escapeHtml, isBgAgentId } from './utils.js';
import { showMemoryButton, handleMemoryData, handleMemoryChanged, initMemory, clearMemoryCache } from './memory.js';
import { handleRulesData, handleRulesSaved, handleRulesDeleted, handleBrowseResult, initRulesModal, initPathPicker } from './sidebar.js';
import { t, getLocale } from './i18n.js';
import { applyLocaleToHtml } from './i18n.js';
// #27 Project 标签页 + Flow Map 标签页（方向调整：均为 Canvas 标签页形态）
import { registerCanvasPanelButton } from './canvas.js';
import { openProjectsTab } from './projectTab.js';
import { initScheduledTask, refreshScheduledTasks } from './scheduled-task.js';
import { initDaemons } from './daemons.js';
import { initChatSearch } from './chatSearch.js';
import { initUsageDashboard } from './usageDashboard.js';
import { initExplorer, refreshExplorer } from './explorer.js';
import { initChatView, chatViews, findViewBySessionId, activeView, setActiveView } from './chatView.js';
import { isErrorReason, normalizeReason, applyErrorFrozen, clearErrorFrozen, renderEscalationCard } from './errorRecovery.js';
import { handleFlowAgentHistory, openStepPopup as openFlowStepPopup } from './flowAgentPopup.js';
import { handleBgAgentHistory, openStepPopup as openBgAgentPopup, cleanupBgAgentView } from './bgAgentPopup.js';
import { fmtUptime, isFailedSnapshotStatus } from './managePanel.js';
import { initNeblink } from './neblink.js';
import { initDropbox } from './dropbox.js';
import { initContacts } from './contacts.js';
import { initMessages } from './messages.js';
import { formatLiveDuration } from './chat.js';
import { collapseTurn, failTurn } from './turnGroup.js';
import * as planMode from './planMode.js';
import { initCanvas, restoreTabs, closeCanvas, openCanvas } from './canvas.js';
import { initLightbox } from './lightbox.js';
// Side-effect import: flowAnim.js is the rAF orbit driver for Flow Map /
// flow-run node dots (.solar-node). It used to ride in via the legacy
// flow-canvas module (deleted 2026-09-05 旧 UI 退役); anchor it here so
// Flow Map's orbit animation stays alive from boot.
import './flowAnim.js';
// Side-effect import: agentManager.js keeps the sealed agents panel +
// per-agent detail tabs alive (canvas-tab-restore for persisted 'agents'
// tabs); plugins.js owns the activity-bar entry now (2026-09-04 件 B).
import './agentManager.js';
import { openPlugins } from './plugins.js';
import { initColResizers } from './colResizer.js';
import { initActivityBar, toggleSideBar } from './activityBar.js';

// ---------- Live thinking timer ----------
let _thinkingTimerInterval = null;
let _thinkingTimerEl = null;

function startThinkingTimer() {
  stopThinkingTimer(); // clean any previous
  const sid = state.activeSessionId;
  const startTime = state.turnStartTimes[sid];
  if (!startTime) return;

  // Insert timer element into the existing thinking placeholder
  const placeholder = activeView.dom.chat.querySelector('.thinking-placeholder');
  if (!placeholder) return;

  // Remove old thinking text, replace with indicator structure
  placeholder.innerHTML = '';
  const indicator = document.createElement('div');
  indicator.className = 'thinking-indicator';

  // Animated dots
  for (let i = 0; i < 3; i++) {
    const dot = document.createElement('span');
    dot.className = 'thinking-dot';
    if (i === 1) dot.style.animationDelay = '0.15s';
    if (i === 2) dot.style.animationDelay = '0.3s';
    indicator.appendChild(dot);
  }

  // Label
  const label = document.createElement('span');
  label.className = 'thinking-indicator-label';
  label.textContent = t('chat.thinking.now') || '思考中';
  indicator.appendChild(label);

  // Live timer
  const timer = document.createElement('span');
  timer.className = 'thinking-timer';
  timer.textContent = formatLiveDuration(Date.now() - startTime);
  indicator.appendChild(timer);

  placeholder.appendChild(indicator);
  _thinkingTimerEl = timer;

  // Tick every second
  _thinkingTimerInterval = setInterval(() => {
    if (_thinkingTimerEl) {
      _thinkingTimerEl.textContent = formatLiveDuration(Date.now() - startTime);
    }
  }, 1000);
}

function stopThinkingTimer() {
  if (_thinkingTimerInterval) {
    clearInterval(_thinkingTimerInterval);
    _thinkingTimerInterval = null;
  }
  _thinkingTimerEl = null;
}
// Expose for cross-module cleanup (input.js)
window.__stopThinkingTimer = stopThinkingTimer;

// ---------- 1. Populate DOM refs ----------
state.dom = {
  chat: document.getElementById('chat'),
  input: document.getElementById('input'),
  sendBtn: document.getElementById('send-btn'),
  stopBtn: document.getElementById('stop-btn'),
  voiceBtn: document.getElementById('voice-btn'),
  attachBtn: document.getElementById('attach-btn'),
  attPreview: document.getElementById('attachment-preview'),
  voiceOverlay: document.getElementById('voice-overlay'),
  voiceText: document.getElementById('voice-text'),
  sessionList: document.getElementById('session-list'),
  sessionNameEl: document.getElementById('session-name'),
  slashDropdown: document.getElementById('slash-dropdown'),
  modalOverlay: document.getElementById('modal-overlay'),
  modalBox: document.getElementById('modal-box'),
  modalInput: document.getElementById('modal-input'),
  modalCancel: document.getElementById('modal-cancel'),
  modalConfirm: document.getElementById('modal-confirm'),
  deleteBox: document.getElementById('delete-box'),
  deleteTitle: document.getElementById('delete-title'),
  deleteMsg: document.getElementById('delete-msg'),
  deleteCancelBtn: document.getElementById('delete-cancel'),
  deleteConfirmBtn: document.getElementById('delete-confirm'),
  agentOverlay: document.getElementById('agent-overlay'),
  agentModal: document.getElementById('agent-modal'),
  agentSystemInput: document.getElementById('agent-system-input'),
  agentModalCancel: document.getElementById('agent-modal-cancel'),
  agentModalSave: document.getElementById('agent-modal-save'),
  bgIndicatorEl: document.getElementById('bg-indicator'),
  bgCountEl: document.getElementById('bg-indicator')?.querySelector('.bg-count'),
  bgDropdownEl: document.getElementById('bg-dropdown'),
  bgDropdownListEl: document.getElementById('bg-dropdown')?.querySelector('.bg-dropdown-list'),
  // Header status indicators — surfaced on state.dom for ws.js indicator updates.
  headerModelInfoEl: document.getElementById('header-model-info'),
  bypassToggleEl: document.getElementById('bypass-toggle'),
  bgagentIndicatorEl: document.getElementById('bgagent-indicator'),
  bgagentDropdownEl: document.getElementById('bgagent-dropdown'),
  bgagentDropdownListEl: document.getElementById('bgagent-dropdown')?.querySelector('.bg-dropdown-list'),
  memoryBtnEl: document.getElementById('memory-btn'),
};

// ── Initialize ChatView ───────────────────────────────────────────────
initLightbox();
// Single view instance for the main panel. The chatViews registry supports
// future multi-view expansion — additional views can register via
// chatViews.<id> = new ChatView(...).
initChatView(
  // Primary window DOM refs — field names MUST match state.dom keys exactly,
  // so Object.assign(state.dom, view.dom) correctly overrides each field.
  {
    chat: document.getElementById('chat'),
    inputBar: document.getElementById('input-bar'),
    input: document.getElementById('input'),
    sendBtn: document.getElementById('send-btn'),
    stopBtn: document.getElementById('stop-btn'),
    attachBtn: document.getElementById('attach-btn'),
    attPreview: document.getElementById('attachment-preview'),
    slashDropdown: document.getElementById('slash-dropdown'),
    queueBar: document.getElementById('queue-bar'),
    voiceBtn: document.getElementById('voice-btn'),
    voiceOverlay: document.getElementById('voice-overlay'),
    voiceText: document.getElementById('voice-text'),
    headerModelInfoEl: document.getElementById('header-model-info'),
    bgIndicatorEl: document.getElementById('bg-indicator'),
    bgCountEl: document.getElementById('bg-indicator')?.querySelector('.bg-count'),
    bgDropdownEl: document.getElementById('bg-dropdown'),
    bgDropdownListEl: document.getElementById('bg-dropdown')?.querySelector('.bg-dropdown-list'),
    bgagentIndicatorEl: document.getElementById('bgagent-indicator'),
    bgagentDropdownEl: document.getElementById('bgagent-dropdown'),
    bgagentDropdownListEl: document.getElementById('bgagent-dropdown')?.querySelector('.bg-dropdown-list'),
    sessionNameEl: document.getElementById('session-name'),
  }
);

// ---------- 2. Init libraries ----------
// Guard: Safari may execute module scripts before CDN scripts finish loading.
function waitForGlobals() {
  return new Promise((resolve) => {
    if (typeof marked !== 'undefined' && typeof lottie !== 'undefined' && typeof lucide !== 'undefined') {
      resolve();
    } else {
      const check = setInterval(() => {
        if (typeof marked !== 'undefined' && typeof lottie !== 'undefined' && typeof lucide !== 'undefined') {
          clearInterval(check);
          resolve();
        }
      }, 50);
      // Safety timeout: proceed after 5s even if some libs missing
      setTimeout(() => { clearInterval(check); resolve(); }, 5000);
    }
  });
}

await waitForGlobals();
if (typeof marked !== 'undefined') initMarkdown();
if (typeof lottie !== 'undefined') initSpinner();
if (typeof lucide !== 'undefined') lucide.createIcons();

// ---------- 3. Register WS message handlers ----------

// Mark a session as having unread activity
function markSessionUnread(sessionId) {
  if (state.unreadSessions.has(sessionId)) return;
  state.unreadSessions.add(sessionId);
  persistUnread();
  // Update agent-level unread count
  const agentName = state.sessionAgentMap[sessionId];
  if (agentName && agentName !== state.selectedAgent) {
    state.agentUnreadCounts[agentName] = (state.agentUnreadCounts[agentName] || 0) + 1;
    updateAgentNotificationDot(agentName);
  }
  window.dispatchEvent(new CustomEvent('session-unread', { detail: { sessionId } }));
}

// Show/hide notification dot on an agent avatar
function updateAgentNotificationDot(agentName) {
  const el = document.querySelector(`#nav-agent-list .nav-agent[data-name="${agentName}"]`);
  if (!el) return;
  const count = state.agentUnreadCounts[agentName] || 0;
  let dot = el.querySelector('.agent-notif-dot');
  if (count > 0) {
    if (!dot) {
      dot = document.createElement('div');
      dot.className = 'agent-notif-dot';
      el.appendChild(dot);
    }
  } else if (dot) {
    dot.remove();
  }
}

// Helper: compute and clear turn duration for a session
function consumeTurnDuration(sid) {
  const startTime = state.turnStartTimes[sid];
  if (!startTime) return undefined;
  delete state.turnStartTimes[sid];
  return Date.now() - startTime;
}

// Helper: clear busy for a specific session, then drain any queued messages.
// Called by ALL terminal events (done, error, interrupted, timeout, maxTokens,
// compactFailed) — not just 'done' — so the queue drains regardless of how the
// turn ended.
function clearBusyFor(msg) {
  const sid = msg.sessionId || state.activeSessionId;
  if (state.busySessionIds.has(sid)) {
    clearBusy(sid);
  }
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  // Freeze fallback (spec §7 risk table): a terminal event while frozen must
  // clear the frozen marker + input-bar visual too — 'done' arriving without a
  // resumed event would otherwise leave a stale frozen bar over a dead turn.
  if (sid && state.frozenSessions.has(sid)) {
    state.frozenSessions.delete(sid);
    delete state.errorRecovery[sid];
    const fv = findViewBySessionId(sid);
    if (fv && fv.dom && fv.dom.inputBar) {
      fv.dom.inputBar.classList.remove('frozen', 'frozen-error');
      delete fv.dom.inputBar.dataset.frozen;
      delete fv.dom.inputBar.dataset.errorFrozen;
      // Error-recovery family: also drop the amber reason strip (UI-7 cleanup).
      clearErrorFrozen(sid);
      // Restore the mode-appropriate placeholder when the frozen session is
      // the one on screen (otherwise it self-heals on next view activation).
      if (fv === activeView && fv.dom.input) {
        import('./input.js').then(({ applyInputModes }) => applyInputModes());
      }
    }
  }
  // Drain queued messages after a short delay to let the UI finalize first
  if (sid) {
    setTimeout(() => drainMessageQueue(sid), 50);
  }
  // Release the send lock for the view displaying this session
  const view = findViewBySessionId(sid);
  if (view) view.isSending = false;
}
// Helper: reset activity-based stream timeout for a busy session
function resetStreamTimeout(sid) {
  if (!sid || !state.busySessionIds.has(sid)) return;
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
  }
  state.sessionBusyTimeouts[sid] = setTimeout(() => {
    if (state.busySessionIds.has(sid)) {
      import('./chat.js').then(({ renderTimeoutNotice, clearBusy, clearStatus }) => {
        const v = findViewBySessionId(sid);
        if (v) { setActiveView(v); renderTimeoutNotice(); clearStatus(); }
        clearBusy(sid);
      });
    }
  }, state.streamTimeoutMs + 30000);
}

// ── Freeze schedule (work hours) events ──────────────────────────────────
// Backend emits 'frozen' (root session) / 'agentFrozen' (sub-agent) when an
// agent parks at a dispatch boundary outside work hours; 'resumed' /
// 'agentResumed' when it wakes (schedule re-open, config change, or a user
// message). Contract: protocol.scala AgentStreamEvent Frozen/Resumed.
onMessage('frozen', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  state.frozenSessions.add(sid);
  // Error-recovery family (frozen-error-recovery plan §4): reason≠schedule →
  // amber UI (input-bar tint + reason strip + retry/abandon buttons). The
  // park semantics (frozenSessions) are the same; only the presentation and the
  // actionable buttons differ.
  const reason = normalizeReason(msg.reason);
  const isError = isErrorReason(reason);
  if (isError) {
    state.errorRecovery[sid] = {
      reason,
      retryCount: msg.retryCount,
      detail: msg.detail,
      resumeAt: msg.resumeAt,
      escalation: msg.escalation,
    };
  }
  // F8/F4: a freeze can last hours — kill the activity stream timeout so it
  // cannot false-fire at streamTimeoutMs+30s and clear the busy state (which
  // would also drain the queue) mid-freeze. resumed re-arms it via activity.
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  const v = findViewBySessionId(sid);
  if (v) {
    setActiveView(v);
    // 2026-08-24 ruling: no standalone status bar — the input bar itself
    // carries the frozen state (ice-blue material + placeholder).
    if (v.dom && v.dom.inputBar) {
      if (isError) {
        // Amber family: .frozen-error (never .frozen — UI-1 mutex).
        applyErrorFrozen(v, { sessionId: sid, reason, retryCount: msg.retryCount, escalation: msg.escalation });
      } else {
        v.dom.inputBar.classList.add('frozen');
        v.dom.inputBar.dataset.frozen = 'true';
        setFrozenBarState(v, true);
        // Clear any stale error-family state (reason may change across events —
        // UI-1 mutex must hold in both directions).
        v.dom.inputBar.classList.remove('frozen-error');
        delete v.dom.inputBar.dataset.errorFrozen;
        const host = v.dom.inputBar.querySelector('#input-wrap');
        const strip = host && host.querySelector('.error-recovery-strip');
        if (strip) strip.remove();
      }
    }
    if (v.dom && v.dom.input && !isError) {
      const clock = formatResumeClock(msg.resumeAt || null);
      v.dom.input.placeholder = clock
        ? t('chat.frozenPlaceholder', { time: clock })
        : t('chat.frozenPlaceholderNoTime');
    }
  }
});

onMessage('resumed', (msg) => {
  const sid = msg.sessionId;
  if (!sid) return;
  if (!state.frozenSessions.has(sid)) return;   // stale/dup — no-op
  state.frozenSessions.delete(sid);
  delete state.errorRecovery[sid];
  // Error-recovery family: remove the amber tint + strip (if present).
  clearErrorFrozen(sid);
  const v = findViewBySessionId(sid);
  if (v) {
    setActiveView(v);
    if (v.dom && v.dom.inputBar) {
      v.dom.inputBar.classList.remove('frozen');
      delete v.dom.inputBar.dataset.frozen;
    }
    setFrozenBarState(v, false);
    // Restore the mode-appropriate placeholder (default / skill / ask / plan)
    import('./input.js').then(({ applyInputModes }) => applyInputModes());
  }
});

// ── ⑩ Local schedule freeze (2026-08-24 ruling) ───────────────────────────
// "处于冻结时间段，如果没有在工作过程（idle），也应该立即冻结消息输入框" —
// the frozen display follows the schedule window itself, not just the
// backend's park events (which only fire at dispatch boundaries mid-work).
// Determined locally from the serverConfig-echoed schedule; the event path
// (frozen/resumed) keeps authority over parked (frozenSessions) sessions.
function freezeWindowState() {
  const ws = state.workSchedule;
  if (!ws || !ws.enabled || !Array.isArray(ws.segments)) return null;
  const now = new Date();
  const cur = now.getHours() * 60 + now.getMinutes();
  for (const seg of ws.segments) {
    const [sh, sm] = String(seg.start || '').split(':').map(Number);
    const [eh, em] = String(seg.end || '').split(':').map(Number);
    if ([sh, sm, eh, em].some(Number.isNaN)) continue;
    const s = sh * 60 + sm, e = eh * 60 + em;
    // Blacklist semantics; cross-midnight segments (start > end) are legal.
    const inside = s < e ? (cur >= s && cur < e) : (cur >= s || cur < e);
    if (inside) {
      const end = new Date(now);
      end.setHours(eh, em, 0, 0);
      if (end <= now) end.setDate(end.getDate() + 1); // cross-midnight → tomorrow
      return { resumeAt: end.getTime() };
    }
  }
  return null;
}

// Frozen state makes the whole input bar inert EXCEPT the "跳过本次" button
// (08-25 22:28 user ruling: no more "send a message to wake" — the freeze window
// blocks input + mic until the user explicitly skips). The textarea and the
// mic/attach/send/stop controls all get disabled; only the skip control stays
// interactive. `disabled` (not readonly) so the inert textarea fires no keydown
// and can never send. Un-frees by removing the attributes.

// The system-freeze targets the MAIN composer (#input-bar = chatViews.primary's
// input bar). During a sub-agent event dispatch ws.js momentarily routes the
// module-global activeView to the popup view (or null); a freeze driven by the
// schedule window must still free/freeze the visible main composer, so never
// rely on the transient activeView here — resolve the primary view (which owns
// #input-bar) with an activeView fallback for pre-boot (before primary is built).
function freezeTargetView() {
  if (chatViews.primary && chatViews.primary.dom && chatViews.primary.dom.inputBar) return chatViews.primary;
  return activeView;
}
function setFrozenBarState(v, frozen) {
  const bar = v && v.dom && v.dom.inputBar;
  if (!bar) return;
  const input = v.dom.input;
  if (input) {
    if (frozen) { input.setAttribute('disabled', ''); input.setAttribute('aria-disabled', 'true'); }
    else { input.removeAttribute('disabled'); input.setAttribute('aria-disabled', 'false'); }
  }
  for (const sel of ['#voice-btn', '#attach-btn', '#send-btn', '#stop-btn']) {
    const el = bar.querySelector(sel);
    if (!el) continue;
    if (frozen) el.setAttribute('disabled', '');
    else el.removeAttribute('disabled');
  }
}

function applyLocalFreeze() {
  const v = freezeTargetView();
  const bar = v && v.dom && v.dom.inputBar;
  if (!bar || !v.sessionId) return;
  const win = freezeWindowState();
  const parked = state.frozenSessions.has(v.sessionId); // event path owns it
  const busy = state.busySessionIds.has(v.sessionId);   // woken mid-window: working
  // A user who clicked "跳过本次" voided the current window (08-25 ruling:
  // skip is not permanent — it expires at the window end, so the next window
  // re-freezes). skipActiveForWindow() merges the in-memory mirror with the
  // authoritative backend freezeState.skipped (which survives a reload — that
  // mirror is lost on refresh). See skip-active note below.
  const skipped = skipActiveForWindow(win);
  if (win && !parked && !busy && !skipped) {
    if (!bar.classList.contains('frozen')) {
      bar.classList.add('frozen');
      bar.dataset.frozen = 'true';
      setFrozenBarState(v, true);
      if (v.dom.input) v.dom.input.placeholder = t('chat.frozenPlaceholder', { time: formatResumeClock(win.resumeAt) });
    }
  } else if ((!win || busy || skipped) && !parked && bar.classList.contains('frozen')) {
    bar.classList.remove('frozen');
    delete bar.dataset.frozen;
    setFrozenBarState(v, false);
    import('./input.js').then(({ applyInputModes }) => applyInputModes());
  }
}
// Window boundary crossings (window start/end) without any event: tick.
setInterval(applyLocalFreeze, 60000);

// ── Skip-current-freeze (user ruling 08-25 14:40: "跳过本次" makes the current
//    freeze window void so the user can keep talking — the message "跳过了才让
//    说"). Skip is NOT permanent: it expires at the window end (skipFrozenUntil
//    = resumeAt), so the next schedule window freezes again. ──────────────────
// Mirrors the backend skipCurrentFreezeWindow (freezeSkipUntilRef). The button
// also sends {type:'skipFreeze'} — a no-op on backends that have not yet
// implemented the command (it hits the catch-all with empty content, recording
// nothing — no fake bubble, no mis-route), and the authoritative wake once they
// have (skipCurrentFreezeWindow unfreezes all parked agents). The local mirror
// is what makes the UI recover immediately, independent of the backend.
let skipFrozenUntil = 0;

// Is the CURRENT schedule window (win = freezeWindowState()) voided by a skip?
// Two signals — the in-session local mirror (skipFrozenUntil, set by the button
// click, survives immediate UI recovery) and the authoritative backend snapshot
// (state.freezeState.skipped, survives a reload where the mirror is lost). The
// snapshot is time-bounded by nextChangeAt (the skipped window's end) so a stale
// skip expires when that window ends and the next schedule window re-freezes.
function skipActiveForWindow(win) {
  if (!win) return false;
  if (skipFrozenUntil > Date.now()) return true;
  const fs = state.freezeState;
  if (fs && fs.skipped && (fs.nextChangeAt == null || fs.nextChangeAt > Date.now())) return true;
  return false;
}

function skipCurrentFreeze() {
  // Record the window end so applyLocalFreeze won't re-freeze the rest of this
  // window. Guarded: when no local window is found (e.g. the backend parked via
  // event before the schedule echo landed), we still un-freeze the UI — the
  // button appearing means .frozen is present, so the click must always work.
  const win = freezeWindowState();
  if (win) skipFrozenUntil = win.resumeAt;
  // Best-effort authoritative wake on the backend (safe no-op if unimplemented).
  sendWs({ type: 'skipFreeze' });
  // Immediate local UI unwind: un-freeze the active input bar (skip only the
  // schedule-frozen state — never the amber error-recovery family).
  const v = freezeTargetView();
  const bar = v && v.dom && v.dom.inputBar;
  if (bar) {
    if (!bar.classList.contains('frozen-error')) {
      bar.classList.remove('frozen');
      delete bar.dataset.frozen;
      setFrozenBarState(v, false);
    }
  }
  if (v && v.dom && v.dom.input) {
    import('./input.js').then(({ applyInputModes }) => applyInputModes());
  }
}

// Wire the frozen-mode "跳过本次" button (single static element; shown via CSS
// only while #input-bar has the schedule-frozen class).
document.getElementById('skip-freeze-btn')?.addEventListener('click', () => skipCurrentFreeze());

// Sub-agent freeze: mark the sessionBgAgents entry so the bg-agent dropdown
// badge reflects the parked state (agentFrozen carries agentId; bgAgentPopup
// intercepts the same event via nodeSessionId for its tile footer).
onMessage('agentFrozen', (msg, view) => {
  const sid = msg.rootSessionId || msg.sessionId || state.activeSessionId;
  if (!sid) return;
  const aid = msg.agentId || (view && view.stream.activeAgentId);
  if (aid && state.sessionBgAgents[sid] && state.sessionBgAgents[sid][aid]) {
    state.sessionBgAgents[sid][aid].frozen = true;
    state.sessionBgAgents[sid][aid].frozenResumeAt = msg.resumeAt || null;
    // Error-recovery family: pass the reason (UI-7 amber dot vs sapphire).
    state.sessionBgAgents[sid][aid].freezeReason = normalizeReason(msg.reason);
    if (view) renderBgAgentDropdown();
  }
  // R4-a (wait-timeout-fix, audit 20260903 Q2-A): a sub-agent freeze parks the
  // ROOT session's turn at its outstanding-subagent barrier — busy stays true
  // with zero activity events, so the root sessionBusyTimeout would false-fire
  // at streamTimeoutMs+30s, send interrupt and kill a turn that is merely
  // waiting for the barrier (「冻结期间响应超时」 across a multi-hour freeze).
  // Mirror the 'frozen' handler (F8/F4): clear the root session's timer;
  // agentResumed → next activity event re-arms it (existing contract).
  if (state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  // 现象2 fix (2026-08-30): a schedule freeze is SYSTEM-wide — when any
  // background agent parks, the foreground input must disable too (if the
  // schedule window is active). applyLocalFreeze reads the authoritative
  // freezeWindowState(); outside a schedule window it's a no-op (a lone
  // loop/error freeze of one agent must NOT disable the whole input).
  applyLocalFreeze();
});

onMessage('agentResumed', (msg, view) => {
  const sid = msg.rootSessionId || msg.sessionId || state.activeSessionId;
  if (!sid) return;
  const aid = msg.agentId || (view && view.stream.activeAgentId);
  if (aid && state.sessionBgAgents[sid] && state.sessionBgAgents[sid][aid]) {
    state.sessionBgAgents[sid][aid].frozen = false;
    state.sessionBgAgents[sid][aid].frozenResumeAt = null;
    state.sessionBgAgents[sid][aid].freezeReason = null;
    if (view) renderBgAgentDropdown();
  }
  // 现象2 fix: when background agents wake, re-evaluate the foreground freeze
  // (window may have ended, or a skip voided it — recompute so the input
  // un-freezes immediately instead of waiting for the 60s tick).
  applyLocalFreeze();
});

// Error-recovery escalation (frozen-error-recovery plan §5.3.2): auto-recovery
// exhausted → parent/user decision is required. Render the amber decision card
// at the top of the session view. Not auto-dismissed (user = final arbiter).
onMessage('errorEscalated', (msg) => {
  renderEscalationCard(msg);
});

// --- Chat streaming ---
// ALL sessions: save to localStorage. Active session only: render DOM.

onMessage('thinkingDelta', (msg, view) => {
  const sid = msg.sessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate thinking text for ALL sessions
  if (sid) state.sessionThinkingBuffers[sid] = (state.sessionThinkingBuffers[sid] || '') + msg.delta;
  resetStreamTimeout(sid);
  state.lastStreamActivity = Date.now();
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (view) {
    stopThinkingTimer();
    // Remove the generic thinking placeholder if it exists
    const existing = activeView.dom.chat.querySelector('.thinking-placeholder');
    if (existing) {
      const row = existing.closest('.row');
      if (row) row.remove();
      if (activeView.stream.currentAiBubble === existing) activeView.stream.currentAiBubble = null;
    }
    // Guard: only create thinking bubbles when a turn is expected (user sent
    // message or server explicitly started a new round). Prevents stray thinking
    // bubbles from late-arriving thinkingDelta after done has been processed.
    if (!state.turnExpecting[sid] && !activeView.stream.currentThinkingBubble && !activeView.stream.currentAiBubble) {
      return;
    }
    appendThinkingDelta(msg.delta);
  }
});

onMessage('textDelta', (msg, view) => {
  const sid = msg.sessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate text for ALL sessions
  if (sid) state.sessionTexts[sid] = (state.sessionTexts[sid] || '') + msg.delta;
  resetStreamTimeout(sid);
  state.lastStreamActivity = Date.now();
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (view) {
    stopThinkingTimer();
    clearRetryStatus();
    // Finish thinking bubble before first text delta
    if (activeView.stream.currentThinkingBubble) finishThinking();
    appendAiText(msg.delta);
  }
});

onMessage('textDone', (msg, view) => {
  const sid = msg.sessionId;
  if (view) {
    stopThinkingTimer();
    if (activeView.stream.currentThinkingBubble) finishThinking();
    const tThinking = state.sessionThinkingBuffers[sid] || '';
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    const durationMs = consumeTurnDuration(sid);
    const data = finishAi(durationMs);
    if (data) {
      data.thinking = tThinking || undefined;
      saveMsg(data, sid);
    }
  } else if (sid && state.sessionTexts[sid]) {
    // Non-active session: save to localStorage + stash in pendingRestore
    // for switch-back restoration, then reset sessionTexts/sessionThinkingBuffers
    // (must reset between turns to avoid cross-turn concatenation).
    const thinkingText = state.sessionThinkingBuffers[sid] || '';
    saveMsg({type: 'ai', text: state.sessionTexts[sid], thinking: thinkingText || undefined}, sid);
    state.pendingRestore[sid] = { text: state.sessionTexts[sid], thinking: thinkingText || undefined };
    delete state.sessionTexts[sid];
    if (state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
  }
});

onMessage('thinking', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  resetStreamTimeout(sid);
  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (view) {
    // Guard against duplicate thinking bubbles: check both state ref and DOM.
    const existing = activeView.dom.chat.querySelector('.thinking-placeholder');
    if (!activeView.stream.currentAiBubble && !existing) {
      const { chat } = activeView.dom;
      const row = document.createElement('div');
      row.className = 'row ai';
      activeView.stream.currentAiBubble = document.createElement('div');
      activeView.stream.currentAiBubble.className = 'bubble ai thinking-placeholder';
      row.appendChild(activeView.stream.currentAiBubble);
      chat.appendChild(row);
      smartScroll();
      // Start live timer after a brief moment so DOM is settled
      requestAnimationFrame(() => startThinkingTimer());
    }
  }
});

onMessage('toolCallDetected', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.name === 'AskUserQuestion') return;
  const sid = msg.sessionId;
  if (sid && !state.sessionPendingTools[sid]) state.sessionPendingTools[sid] = { label: msg.name };

  // Flush accumulated text buffer for ALL sessions at tool call boundary.
  // This prevents cross-round concatenation in sessionTexts[sid] for non-active
  // sessions (active sessions are handled by finishAi() below).
  if (sid && state.sessionTexts[sid]) {
    const thinkingText = state.sessionThinkingBuffers[sid] || '';
    if (!state.sessionPendingAiMessages[sid]) state.sessionPendingAiMessages[sid] = [];
    state.sessionPendingAiMessages[sid].push({
      type: 'ai',
      text: state.sessionTexts[sid],
      thinking: thinkingText || undefined
    });
    delete state.sessionTexts[sid];
    if (state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
  }

  if (sid && !state.busySessionIds.has(sid)) setBusy(sid);
  if (view) {
    clearRetryStatus();
    if (activeView.stream.currentThinkingBubble) finishThinking();
    // Flush thinking buffer for active sessions to prevent cross-turn concatenation.
    // finishAi() only returns text, so we read thinking separately from the buffer.
    const tThinking = state.sessionThinkingBuffers[sid] || '';
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
    // In ask mode, finalize the current ask bubble so the tool card renders below it
    if (activeView.stream.currentAskBubble) finishAskAnswer();
    // If the existing pending card was claimed by a previous tool (toolStart fired),
    // close its streaming display and clear the slot so a new card is created.
    const existingCard = state.sessionToolCards[sid];
    if (existingCard && existingCard.isConnected) {
      const cardEl = existingCard.querySelector('.tool-card');
      if (cardEl && cardEl.dataset.toolLabel) {
        cancelToolStreamRAF();
        existingCard.querySelectorAll('.cursor').forEach(el => el.remove());
        delete state.sessionToolCards[sid];
      }
    }
    renderToolPending(msg.name, msg.sessionId);
    // Reset tool argument streaming for the new tool call
    activeView.stream.toolStreamText = '';
    activeView.stream.toolStreamToolName = msg.name;
  }
});

onMessage('toolStart', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  // Skip pending card for AskUser — the askUser event handles rendering directly
  if (msg.label && msg.label.startsWith('AskUser')) return;
  const toolStartSid = msg.sessionId;
  if (toolStartSid) state.sessionPendingTools[toolStartSid] = { label: msg.label };

  // Flush accumulated text buffer for ALL sessions at tool boundary.
  // Same as toolCallDetected — ensures sessionTexts doesn't accumulate
  // across rounds for non-active sessions.
  if (toolStartSid && state.sessionTexts[toolStartSid]) {
    const thinkingText = state.sessionThinkingBuffers[toolStartSid] || '';
    if (!state.sessionPendingAiMessages[toolStartSid]) state.sessionPendingAiMessages[toolStartSid] = [];
    state.sessionPendingAiMessages[toolStartSid].push({
      type: 'ai',
      text: state.sessionTexts[toolStartSid],
      thinking: thinkingText || undefined
    });
    delete state.sessionTexts[toolStartSid];
    if (state.sessionThinkingBuffers[toolStartSid]) delete state.sessionThinkingBuffers[toolStartSid];
  }

  if (view) {
    if (!state.busySessionIds.has(msg.sessionId || state.activeSessionId)) setBusy(msg.sessionId || state.activeSessionId);
    clearRetryStatus();
    // Finish the current AI bubble so that text after tool execution goes into a new bubble
    if (activeView.stream.currentThinkingBubble) finishThinking();
    // Flush thinking buffer for active sessions (same as toolCallDetected)
    const tThinking = state.sessionThinkingBuffers[toolStartSid] || '';
    if (toolStartSid && state.sessionThinkingBuffers[toolStartSid]) delete state.sessionThinkingBuffers[toolStartSid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
    // In ask mode, finalize the current ask bubble so the tool card renders below it
    if (activeView.stream.currentAskBubble) finishAskAnswer();
    renderToolPending(msg.label, msg.sessionId);
    // Tag the card with the full tool label so toolEnd can find the right card
    // when multiple tools share the session (single sessionToolCards slot).
    const startedRow = state.sessionToolCards[msg.sessionId];
    if (startedRow) {
      const cardEl = startedRow.querySelector('.tool-card');
      if (cardEl) cardEl.dataset.toolLabel = msg.label;
    }
  }
});

onMessage('toolEnd', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.label && msg.label.startsWith('AskUser')) return;
  const toolEndSid = msg.sessionId;
  if (toolEndSid) delete state.sessionPendingTools[toolEndSid];
  if (view) {
    const data = renderTool(msg.label, msg.summary, msg.content, msg.isError, msg.input, msg.sessionId);
    if (data) saveMsg(data, msg.sessionId);
  } else {
    saveMsg({type: 'tool', label: msg.label, summary: msg.summary, content: msg.content, isError: msg.isError, input: msg.input}, msg.sessionId);
  }
});

onMessage('toolArgDelta', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (view) {
    appendToolStreamDelta(msg.toolName, msg.delta);
  }
});

// --- Terminal events ---
// Always clear busySessionId. DOM + status only for active session. Always mark unread for non-active.

// Header model info display
if (!state.sessionModelInfo) state.sessionModelInfo = {};

function formatTokens(n) {
  if (n == null) return '';
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return Math.round(n / 1000) + 'k';
  return String(n);
}

function updateHeaderModelInfo() {
  // The header element lives in the primary window only (popups pass
  // headerModelInfo: null), so always render the PRIMARY view's session —
  // using activeView here dropped repaints whenever a bg-agent/flow popup was
  // active while the primary session's usageUpdate/done arrived.
  const el = document.getElementById('header-model-info');
  if (!el) return;
  const sid = chatViews.primary?.sessionId || state.activeSessionId;
  const info = sid ? state.sessionModelInfo[sid] : null;
  // Header shows the context-usage ring only — the model name label was
  // removed per user request (#313). The model is still surfaced via the
  // ring's tooltip so the info isn't lost, just no longer visually present.
  const hasRing = !!(info && info.contextWindow);
  if (!hasRing) {
    el.textContent = '';
    el.style.display = 'none';
    el.dataset.mode = '';
    return;
  }
  const mode = 'r';

  const ratio = info.inputTokens != null ? info.inputTokens / info.contextWindow : 0;
  const pct = Math.min(Math.round(ratio * 100), 100);
  let barColor = '#4caf50';
  if (ratio > 0.5) barColor = '#d4a030';
  if (ratio > 0.75) barColor = '#e53935';

  const thresholdPct = Math.round((info.compactThreshold || state.COMPACT_THRESHOLD) * 100);
  const outPart = info.outputTokens != null ? ` · +${formatTokens(info.outputTokens)} out` : '';
  const tooltip = [
    info.model || '',
    info.inputTokens != null
      ? `${formatTokens(info.inputTokens)} / ${formatTokens(info.contextWindow)} tokens (${pct}%)${outPart} · threshold ${thresholdPct}%`
      : `${formatTokens(info.contextWindow)} context window`,
  ].filter(Boolean).join(' · ');

  const R = 15;
  const CIRC = 2 * Math.PI * R;
  const dashLen = CIRC * pct / 100;
  const thresholdAngle = thresholdPct * 3.6;

  el.style.display = 'inline-flex';

  // In-place update when the rendered structure matches the current mode.
  if (el.dataset.mode === mode) {
    const ring = /** @type {HTMLElement|null} */ (el.querySelector('.ctx-ring-wrap'));
    if (ring) {
      ring.title = tooltip;
      const ringFill = ring.querySelector('circle:nth-child(2)');
      if (ringFill) {
        ringFill.setAttribute('stroke', barColor);
        ringFill.setAttribute('stroke-dasharray', `${dashLen} ${CIRC}`);
      }
      const ringLine = ring.querySelector('.ctx-ring-threshold');
      if (ringLine) ringLine.setAttribute('transform', `rotate(${thresholdAngle} 18 18)`);
      const ringPct = ring.querySelector('.ctx-ring-pct');
      if (ringPct) ringPct.textContent = String(pct);
    }
    return;
  }

  // Structure change (ring appearing or disappearing) — rebuild.
  el.dataset.mode = mode;
  el.innerHTML = `
    <div class="ctx-ring-wrap ctx-compact" title="${tooltip}">
      <svg width="28" height="28" viewBox="0 0 36 36" class="ctx-ring-svg">
        <circle cx="18" cy="18" r="${R}" fill="none" stroke="rgba(128,128,128,0.15)" stroke-width="3.5"/>
        <circle cx="18" cy="18" r="${R}" fill="none" stroke="${barColor}" stroke-width="3.5"
                stroke-dasharray="${dashLen} ${CIRC}"
                stroke-linecap="round"
                transform="rotate(-90 18 18)"
                style="transition:stroke-dasharray 0.4s ease, stroke 0.4s ease;"/>
        <line x1="18" y1="1.5" x2="18" y2="5" stroke="rgba(200,80,80,0.7)" stroke-width="1.5"
              transform="rotate(${thresholdAngle} 18 18)"
              class="ctx-ring-threshold"/>
      </svg>
      <span class="ctx-ring-pct">${pct}</span>
    </div>
  `;
}
state.updateHeaderModelInfo = updateHeaderModelInfo;

// Real-time usage update after each LLM round (multi-round tool calling)
onMessage('usageUpdate', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && msg.inputTokens != null && msg.contextWindow) {
    state.sessionModelInfo[sid] = {
      // #308 actual model: usageUpdate now carries the model actually used
      // this round (backend B2); prefer it over the stale stored value.
      model: msg.model || state.sessionModelInfo[sid]?.model,
      contextWindow: msg.contextWindow,
      inputTokens: msg.inputTokens,
      // outputTokens absent (older backend) preserves the previous value
      outputTokens: msg.outputTokens ?? state.sessionModelInfo[sid]?.outputTokens,
      compactThreshold: msg.compactThreshold
    };
    try { localStorage.setItem(LS_MODEL_INFO_KEY, JSON.stringify(state.sessionModelInfo)); } catch(e) {}
    if (view) updateHeaderModelInfo();
  }
});

onMessage('done', (msg, view) => {
  // Node sessions (node-*) / dispatcher sessions (dispatcher-*) end with
  // session-level 'done' (not 'agentDone') — parentRef=None agents finish with
  // a session-level done (finishTurn: isSubagent = parentRef.isDefined).
  // Cleanup of their Sub-Agents rows happens here: the agentDone delete path
  // never fires for them. (node- rows: #28 可观测接线 now SHOWS them while
  // Processing — cleanup still terminal; dispatcher- same contract.)
  const doneSid = msg.sessionId;
  if (doneSid && (String(doneSid).startsWith('node-') || String(doneSid).startsWith('dispatcher-'))) {
    const touchedRoots = [];
    for (const [root, agents] of Object.entries(state.sessionBgAgents || {})) {
      let touched = false;
      for (const [key, entry] of Object.entries(agents)) {
        if (key === doneSid || (entry && entry.sessionId === doneSid)) { delete agents[key]; touched = true; }
      }
      if (Object.keys(agents).length === 0) delete state.sessionBgAgents[root];
      if (touched) touchedRoots.push(root);
    }
    // 取消/终态实时收尾（2026-09-03）：行已删，立即刷新归属桶徽标 + 打开中的
    // 面板——此前只删状态不渲染，面板行滞留到下次交互/刷新才消失。此刻活跃
    // 视图是拦截器切入的弹窗视图（ws.js bg/flow interceptor），须临时切到归属
    // 视图渲染再还原（与 agentDone 2s 收尾同款模式）；后端取消链路补发的
    // agentDone 帧先经 ws.js 转换为会话级 done 到达此处。
    for (const root of touchedRoots) {
      const targetView = findViewBySessionId(root);
      if (targetView) {
        const savedView = activeView;
        setActiveView(targetView);
        updateBgAgentIndicator();
        setActiveView(savedView);
      }
    }
  }
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  // Defensive: clear attention when turn ends (in case answer callback didn't fire)
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  const durationMs = consumeTurnDuration(sid);
  delete state.sessionPendingTools[sid];
  // Turn is complete — clear turnExpecting so stray thinkingDelta won't create bubbles
  if (sid) delete state.turnExpecting[sid];
  // Clean up answered permission tracking — turn is done
  if (sid) state.answeredPermissions.delete(sid);
  // Store model info for this session
  if (sid && (msg.model || msg.contextWindow || msg.inputTokens != null)) {
    state.sessionModelInfo[sid] = {
      model: msg.model || state.sessionModelInfo[sid]?.model,
      contextWindow: msg.contextWindow || state.sessionModelInfo[sid]?.contextWindow,
      inputTokens: msg.inputTokens != null ? msg.inputTokens : state.sessionModelInfo[sid]?.inputTokens,
      outputTokens: msg.outputTokens ?? state.sessionModelInfo[sid]?.outputTokens,
      compactThreshold: msg.compactThreshold != null ? msg.compactThreshold : state.sessionModelInfo[sid]?.compactThreshold
    };
    try { localStorage.setItem(LS_MODEL_INFO_KEY, JSON.stringify(state.sessionModelInfo)); } catch(e) {}
    if (view) updateHeaderModelInfo();
  }
  // Flush any remaining buffered text/thinking for this session
  if (msg.sessionId) {
    if (!view) {
      const thinkingText = state.sessionThinkingBuffers[msg.sessionId] || '';
      // Save ALL pending segments accumulated at tool boundaries, then the final round.
      const pendingSegments = state.sessionPendingAiMessages[msg.sessionId] || [];
      pendingSegments.forEach(seg => saveMsg(seg, msg.sessionId));
      delete state.sessionPendingAiMessages[msg.sessionId];

      if (state.sessionTexts[msg.sessionId] || thinkingText) {
        const text = state.sessionTexts[msg.sessionId] || '';
        // Save final round's text/thinking as the main message
        saveMsg({type: 'ai', text, thinking: thinkingText || undefined, durationMs, model: msg.model}, msg.sessionId);
        // pendingRestore only contains the LAST round's data (not concatenated across rounds),
        // so dedup against backend history (last Ai message) works correctly.
        state.pendingRestore[msg.sessionId] = { text, thinking: thinkingText || undefined, durationMs, model: msg.model };
      }
      delete state.sessionTexts[msg.sessionId];
      delete state.sessionThinkingBuffers[msg.sessionId];
    } else {
      delete state.sessionTexts[msg.sessionId];
      // Note: sessionThinkingBuffers is NOT deleted here for active sessions —
      // it's consumed by finishThinking() + the fallback read in the view block below.
    }
  }
  if (view) {
    stopThinkingTimer();
    // Clean up per-session pending tool card for this session
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    // Reset tool argument streaming state
    activeView.stream.toolStreamText = '';
    activeView.stream.toolStreamToolName = '';
    // Clean up pending segments accumulated at tool boundaries
    if (sid && state.sessionPendingAiMessages[sid]) delete state.sessionPendingAiMessages[sid];
    // Clean up pendingRestore (previous turn's data no longer needed)
    if (sid && state.pendingRestore[sid]) delete state.pendingRestore[sid];
    // Finish thinking bubble if still streaming
    const thinkingText = finishThinking() || (sid ? state.sessionThinkingBuffers[sid] || '' : '');
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    // Clear thinkingText unconditionally — when appendThinkingDelta skipped DOM
    // creation (second+ thinking block after text), finishThinking() had no bubble
    // to clear and activeView.stream.thinkingText retains skipped content.
    activeView.stream.thinkingText = '';
    const data = finishAi(durationMs, msg.model);
    if (data) {
      data.thinking = thinkingText || undefined;
      saveMsg(data, msg.sessionId);
    } else if (thinkingText) {
      // Thinking-only response (no text): keep thinking bubble expanded
      const thinkBubble = activeView.dom.chat.querySelector('.thinking-bubble');
      if (thinkBubble) {
        const content = thinkBubble.querySelector('.thinking-content');
        const label = thinkBubble.querySelector('.thinking-label');
        if (content) content.style.display = '';
        if (label) label.classList.add('expanded');
      }
      // Save without text field so restoreFromStorage doesn't render an empty bubble
      saveMsg({ type: 'ai', thinking: thinkingText, durationMs, model: msg.model }, msg.sessionId);
    }
    Object.keys(activeView.stream.agentBubbles).forEach(id => finishAgent(id));
    activeView.stream.agentBubbles = {};
    activeView.stream.activeAgentId = null;
    clearStatus();
    // #346: gather this turn's process rows and collapse immediately
    // (synchronous, no linger). The summary freezes the phrase + model
    // (both visible, v1.2); the timestamp rides in the title tooltip.
    const lastBadge = Array.from(activeView.dom.chat.querySelectorAll('.duration-badge')).pop();
    collapseTurn(activeView, {
      durationMs,
      model: msg.model,
      phrase: lastBadge?.dataset.nfPhrase || '',
      title: lastBadge?.querySelector('.duration-badge-time')?.textContent || '',
      sessionId: sid,
    });
  } else {
    markSessionUnread(msg.sessionId);
  }
  // Queue drainage handled by clearBusyFor above — no duplicate call here.
});

// roundComplete: backend signals the current round's text is finalized but a new
// LLM round is about to start (e.g. pendingEvents injection). Finalize the
// current AI bubble without ending the turn (no done/sessionBusy(false)).
onMessage('roundComplete', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (view) {
    if (activeView.stream.currentThinkingBubble) finishThinking();
    const tThinking = state.sessionThinkingBuffers[sid] || '';
    if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
    const prevData = finishAi();
    if (prevData) {
      prevData.thinking = tThinking || undefined;
      saveMsg(prevData, msg.sessionId);
    }
    // Show thinking placeholder for the upcoming round — backend sent sessionBusy(true)
    // after roundComplete, so the agent is still working.
    if (sid && state.busySessionIds.has(sid)) {
      if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
      const { chat } = activeView.dom;
      const existing = chat.querySelector('.thinking-placeholder');
      if (!activeView.stream.currentAiBubble && !existing) {
        const row = document.createElement('div');
        row.className = 'row ai';
        activeView.stream.currentAiBubble = document.createElement('div');
        activeView.stream.currentAiBubble.className = 'bubble ai thinking-placeholder';
        row.appendChild(activeView.stream.currentAiBubble);
        chat.appendChild(row);
        smartScroll();
        requestAnimationFrame(() => startThinkingTimer());
      }
    }
  }
});

onMessage('error', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  if (sid) delete state.pendingRestore[sid];
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) delete state.turnExpecting[sid];
  // Drop accumulated stream buffers — the turn is over (aligned with done path)
  if (sid) delete state.sessionTexts[sid];
  if (sid) delete state.sessionThinkingBuffers[sid];
  // Defensive: clear attention on error
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) state.answeredPermissions.delete(sid);
  // Reset history loading state — backend may fail mid-pagination
  const errView = findViewBySessionId(sid);
  if (errView) errView.pagination.loading = false;
  if (view) view.pagination.loading = false;
  hideHistoryLoader();
  if (view) {
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishThinking();
    finishAi();
    failTurn(activeView); // #346: group but keep expanded for troubleshooting
    renderError(msg.message);
    clearStatus();
  } else {
    saveMsg({type: 'error', text: msg.message}, msg.sessionId);
    markSessionUnread(msg.sessionId);
  }
});

onMessage('interrupted', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  if (sid) delete state.pendingRestore[sid];
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) delete state.turnExpecting[sid];
  // Drop accumulated stream buffers — the turn is over (aligned with done path)
  if (sid) delete state.sessionTexts[sid];
  if (sid) delete state.sessionThinkingBuffers[sid];
  // Defensive: clear attention on interrupt
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) state.answeredPermissions.delete(sid);
  if (view) {
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishThinking();
    finishAi();
    failTurn(activeView); // #346: interrupted turns stay expanded
    clearStatus();
  }
});

onMessage('timeout', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) delete state.sessionPendingAiMessages[sid];
  // Drop accumulated stream buffers — the turn is over (aligned with done path)
  if (sid) delete state.sessionTexts[sid];
  if (sid) delete state.sessionThinkingBuffers[sid];
  if (sid) state.answeredPermissions.delete(sid);
  if (view) {
    finishThinking();
    finishAi();
    failTurn(activeView); // #346: timed-out turns stay expanded
    renderTimeoutNotice();
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

onMessage('maxTokens', (msg, view) => {
  clearBusyFor(msg);
  const sid = msg.sessionId || state.activeSessionId;
  delete state.sessionPendingTools[sid];
  if (sid) delete state.pendingRestore[sid];
  if (sid) delete state.sessionPendingAiMessages[sid];
  if (sid) delete state.turnExpecting[sid];
  // Drop accumulated stream buffers — the turn is over (aligned with done path)
  if (sid) delete state.sessionTexts[sid];
  if (sid) delete state.sessionThinkingBuffers[sid];
  if (sid && state.attentionSessions.has(sid)) setSessionAttention(sid, false);
  if (sid) state.answeredPermissions.delete(sid);
  if (view) {
    if (sid && state.sessionToolCards[sid]) {
      state.sessionToolCards[sid].remove();
      delete state.sessionToolCards[sid];
    }
    finishThinking();
    finishAi();
    failTurn(activeView); // #346: truncated turns stay expanded
    renderError('Max tokens reached — response truncated');
    clearStatus();
  } else {
    markSessionUnread(msg.sessionId);
  }
});

// --- AskUser / Permission ---
onMessage('askUser', (msg, view) => {
  const sid = msg.sessionId;
  if (sid) setSessionAttention(sid, true);
  // AskUser waits for human response — suppress stream timeout indefinitely
  if (sid && state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  // 刷新存活 (2026-09-03): replayed frames — the hub snapshot re-sent by the
  // backend right after the initial historyPage of a session (re)subscribe
  // (browser refresh, WS reconnect, session switch). This is state
  // re-delivery of a card the backend still considers pending, NOT a new ask:
  //  - the history-restored card carries no data-request-id (UiMessage.AskUser
  //    persists only {type, items}), so the chat-input close frame
  //    (askUserAnswered{requestId}) and #12 precise answer routing cannot find
  //    it — rebind by re-rendering with the live requestId;
  //  - a duplicate replay would stack cards — remove THIS ask's unanswered
  //    cards first (answered/locked cards and other pending asks stay).
  // Answered-before-replay cannot race: the hub snapshot only lists slots
  // still pending, so an answered ask is never replayed.
  if (msg.replayed) {
    if (view) {
      view.dom.chat.querySelectorAll('.row.ai .option-box').forEach(box => {
        const rid = box.dataset.requestId || '';
        const answered = !!box.querySelector('.option-answer');
        if (!answered && (!rid || rid === msg.requestId)) box.closest('.row.ai').remove();
      });
      const rdata = renderAskUser(msg.items, msg.sessionId, msg.agentName, msg.requestId);
      if (rdata) saveAskMsgDedup(rdata, msg.sessionId, msg.requestId);
    } else if (sid) {
      // Non-active session: persist (deduped) so it can be restored on session switch
      saveAskMsgDedup({ type: 'askUser', items: msg.items, agentName: msg.agentName, requestId: msg.requestId }, sid, msg.requestId);
    }
    return;
  }
  if (view) {
    // Defensive: finalize any in-flight AI bubble before rendering the question.
    // Normally roundComplete (sent before askUser by the backend) handles this,
    // but guard against edge cases where the bubble is still pending.
    if (activeView.stream.currentAiBubble) {
      const prevData = finishAi();
      if (prevData) saveMsg(prevData, sid);
    }
    const data = renderAskUser(msg.items, msg.sessionId, msg.agentName, msg.requestId);
    if (data) saveMsg(data, msg.sessionId);
  } else if (sid) {
    // Non-active session: persist so it can be restored on session switch
    saveMsg({ type: 'askUser', items: msg.items, agentName: msg.agentName, requestId: msg.requestId }, sid);
  }
});

// Chat-input passthrough (author ruling 2026-08-29 23:50): while an AskUser
// card is pending, a message typed into the input box is consumed by the
// backend as that tool call's answer; it then broadcasts askUserAnswered so
// every attached client locks the card locally (same end-state as answering
// on the card). The user's text already landed as a normal user bubble.
onMessage('askUserAnswered', (msg) => {
  closeAskUserCard(msg.sessionId, msg.requestId);
});

// F4 (#433): global actionable toast for permission cards whose target root
// session is unreachable. Glass panel, no overlay dimming (弹窗禁令). Stack
// top-right; removed on answer or on permissionExpired for the same root sid.
let __permToastHost = null;
function showGlobalPermissionToast(msg) {
  const sid = msg.sessionId || '';
  if (!__permToastHost) {
    __permToastHost = document.createElement('div');
    __permToastHost.id = 'global-perm-toasts';
    document.body.appendChild(__permToastHost);
  }
  const card = document.createElement('div');
  card.className = 'global-perm-toast';
  card.dataset.rootSid = sid;
  const title = document.createElement('div');
  title.className = 'global-perm-toast-title';
  title.textContent = t('perm.fallbackTitle', { agent: msg.sourceAgent || '?' });
  const body = document.createElement('div');
  body.className = 'global-perm-toast-body';
  body.textContent = `${msg.toolName || ''}${msg.summary ? ' · ' + msg.summary : ''}`;
  const actions = document.createElement('div');
  actions.className = 'global-perm-toast-actions';
  const send = (approved) => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: sid, approved, ...(msg.requestId && { requestId: msg.requestId }) }));
    }
    card.remove();
  };
  const denyBtn = document.createElement('button');
  denyBtn.className = 'global-perm-toast-btn deny';
  denyBtn.textContent = t('perm.fallbackDeny');
  denyBtn.addEventListener('click', () => send(false));
  const okBtn = document.createElement('button');
  okBtn.className = 'global-perm-toast-btn approve';
  okBtn.textContent = t('perm.fallbackApprove');
  okBtn.addEventListener('click', () => send(true));
  actions.appendChild(denyBtn);
  actions.appendChild(okBtn);
  card.appendChild(title);
  card.appendChild(body);
  card.appendChild(actions);
  __permToastHost.appendChild(card);
  // Safety net: the backend auto-denies after 5 min — retire the card by then.
  setTimeout(() => card.remove(), 5 * 60 * 1000);
}

function dismissGlobalPermissionToasts(rootSid) {
  if (!__permToastHost) return;
  __permToastHost.querySelectorAll('.global-perm-toast').forEach(el => {
    if (!rootSid || el.dataset.rootSid === rootSid) el.remove();
  });
}

onMessage('askPermission', (msg, view) => {
  const sid = msg.sessionId;
  // F4 (#433): fallback card — the card's target root session is unreachable
  // (deleted / zombie / never had a client). The backend fanned it out to all
  // roots with fallback:true; render a global actionable toast instead of
  // routing the card into a session that cannot be opened. Also catch the
  // un-flagged variant whose sessionId is not in the session list (older
  // backend or non-flagged graveyard route). Answers match by requestId, so
  // answering from this toast completes the pending request from any window.
  if (msg.fallback || (sid && !state.sessionAgentMap[sid])) {
    showGlobalPermissionToast(msg);
    if (msg.sourceSession && state.sessionAgentMap[msg.sourceSession]) {
      setSessionAttention(msg.sourceSession, true);
    }
    return;
  }
  // Bypass mode: auto-approve immediately without showing attention indicator.
  // This must run for BOTH active and non-active sessions — previously only
  // active sessions got bypass treatment (inside renderPermissionPrompt),
  // leaving non-active sessions stuck with a yellow indicator that never clears.
  if (sid && state.bypassSessions.has(sid)) {
    if (view) {
      // Active session: renderPermissionPrompt detects bypass, sends approval,
      // and shows the "auto-approved" badge. Let it handle everything.
      renderPermissionPrompt(msg.toolName, msg.summary, msg.input, msg.sessionId, msg.dangerLevel, msg.sourceAgent, msg.sourceSession, msg.sourceTeam, msg.requestId, msg.safetyMode);
    } else {
      // Non-active session: auto-approve directly (renderPermissionPrompt is never called).
      if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: sid, approved: true, ...(msg.requestId && { requestId: msg.requestId }) }));
      }
      saveMsg({ type: 'askPermission', toolName: msg.toolName, summary: msg.summary, input: msg.input, dangerLevel: msg.dangerLevel, autoApproved: true, sourceAgent: msg.sourceAgent, sourceSession: msg.sourceSession, sourceTeam: msg.sourceTeam, requestId: msg.requestId }, sid);
    }
    return;
  }
  if (sid) setSessionAttention(sid, true);
  // Permission prompt waits for human response — suppress stream timeout indefinitely
  if (sid && state.sessionBusyTimeouts[sid]) {
    clearTimeout(state.sessionBusyTimeouts[sid]);
    delete state.sessionBusyTimeouts[sid];
  }
  // Clear stale "answered" tracking: a new permission request means any
  // previous answer in this session (same turn) is no longer relevant.
  // Without this, a second permission in the same turn would be stuck as
  // disabled because answeredPermissions still holds this sid.
  if (sid) state.answeredPermissions.delete(sid);
  if (view) {
    renderPermissionPrompt(msg.toolName, msg.summary, msg.input, msg.sessionId, msg.dangerLevel, msg.sourceAgent, msg.sourceSession, msg.sourceTeam, msg.requestId, msg.safetyMode);
  } else if (sid) {
    // Non-active session: persist so it can be restored on session switch
    saveMsg({ type: 'askPermission', toolName: msg.toolName, summary: msg.summary, input: msg.input, dangerLevel: msg.dangerLevel, sourceAgent: msg.sourceAgent, sourceSession: msg.sourceSession, sourceTeam: msg.sourceTeam, requestId: msg.requestId, safetyMode: msg.safetyMode }, sid);
  }
});

onMessage('permissionExpired', (msg, view) => {
  const sid = msg.sessionId;
  dismissGlobalPermissionToasts(sid); // F4 (#433): retire any fallback toast for this root
  if (!sid) return;
  // Mark as answered so the prompt isn't re-created on session switch
  state.answeredPermissions.add(sid);
  // Remove the permission prompt row from DOM
  if (view) {
    view.dom.chat.querySelectorAll('.row.ai').forEach(row => {
      if (row.querySelector('.permission-pending-box')) row.remove();
    });
  }
  // Clear attention indicator
  setSessionAttention(sid, false);
});

// --- Session list (global) ---
let restoredSessionId = null;
onMessage('sessionList', (msg, view) => {
  // Build sessionAgentMap from ALL sessions
  const allSessions = msg.sessions || [];
  const allFolders = msg.folders || [];
  allSessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || 'Nebula'; });

  // Restore safety mode from persisted session metadata
  state.safetyModes = {};
  allSessions.forEach(s => { if (s.safetyMode) state.safetyModes[s.id] = s.safetyMode; });
  // Derive bypassSessions (auto-all) for chat.js auto-approve compatibility
  state.bypassSessions = new Set(allSessions.filter(s => s.safetyMode === 'auto-all').map(s => s.id));

  state.folders = allFolders;
  state.foldersWithRules = new Set(msg.foldersWithRules || []);

  const activeId = msg.activeId;

  renderSessionSidebar(allSessions, activeId);
  initHeaderModelInfo();
  // Mark the initial session as restored — getHistory is already sent by
  // resetChatForActiveSession (called inside renderSessionSidebar when activeId changes).
  if (!restoredSessionId && activeId) {
    restoredSessionId = activeId;
  }
  migrateLegacyIfNeeded();
  // Request agent list on first connect (no tab to trigger it now)
  if (!state.selectedAgent) sendWs({ type: 'listAgents' });
  // 现象2 fix: if serverConfig (workSchedule) arrived BEFORE the active view was
  // established, applyLocalFreeze no-oped on it. Now that the active session is
  // set, re-evaluate so a freeze window active at boot still disables the input.
  applyLocalFreeze();
});

// --- History pagination indicators ---
function showHistoryLoader() {
  let loader = activeView.dom.chat.querySelector('.history-loader');
  if (loader) return;
  loader = document.createElement('div');
  loader.className = 'history-loader';
  loader.innerHTML = '<div class="history-spinner"></div><span>' + t('chat.loading') + '</span>';
  activeView.dom.chat.prepend(loader);
}

function hideHistoryLoader() {
  document.querySelectorAll('.history-loader').forEach(el => el.remove());
}

function showHistoryEnd() {
  if (activeView.dom.chat.querySelector('.history-end')) return;
  const end = document.createElement('div');
  end.className = 'history-end';
  end.textContent = t('chat.noMoreMessages');
  activeView.dom.chat.prepend(end);
}

function clearHistoryIndicators() {
  activeView.dom.chat.querySelectorAll('.history-loader, .history-end').forEach(el => el.remove());
}

// --- Backend history page ---
// For initial load: replaces chat content.
// For scroll-up pagination: prepends older messages before existing content.
onMessage('historyPage', (msg, view) => {
  // Background sub-agent sessions are handled by the bg-agent popup viewer.
  if (handleBgAgentHistory(msg)) return;
  // Flow agent sessions are handled by the popup viewer, not the primary chat.
  if (handleFlowAgentHistory(msg)) return;

  const sid = msg.sessionId;
  hideHistoryLoader();
  if (!view) return;
  view.pagination.loading = false;

  // Use explicit flag instead of historyOffset === 0 to prevent double-clear.
  const isInitialLoad = view.pagination.pendingInitialLoad;
  if (isInitialLoad) {
    view.pagination.pendingInitialLoad = false;
    // Initial load or full refresh — replace
    cleanupCardIframes(activeView.dom.chat);
    activeView.dom.chat.innerHTML = '';
    // Reset sessionToolCards — innerHTML clear above removes all tool pending
    // card DOM nodes, but renderToolPending uses sessionToolCards[sid] as an
    // existence check. A stale DOM reference causes it to skip card creation
    // (update-in-place on a detached node), leaving no spinner visible.
    Object.keys(state.sessionToolCards).forEach(sid => delete state.sessionToolCards[sid]);
    // Clear history indicators before rendering
    clearHistoryIndicators();
    view.pagination.offset = msg.offset;
    view.pagination.total = msg.total;
    view.pagination.hasMore = msg.hasMore;
    // #346 boundary fix (2026-08-24): when the session is still mid-turn at
    // reload/reconnect, the trailing segment must stay flat — grouping it
    // would stamp it 'failed' and strand a zombie group (see turnGroup.js).
    const isStillBusy = state.busySessionIds.has(sid);
    restoreFromBackendHistory(msg.messages, { busyTail: isStillBusy });

    // Detect if the agent is waiting for AskUser — in that case it's NOT actively streaming.
    // Issue #43 (2026-09-03): agent-injected user bubbles (delegate results,
    // Mail, flow/node notifications) legitimately queue AFTER a still-pending
    // askUser entry — requiring askUser to be the literal LAST message made
    // the pending detection fail exactly when results arrived during the
    // wait, and the restored card stayed locked with no way to answer.
    // findLastRealMessage (shared with persistence.js, spec-covered) scans
    // backward over injected bubbles; a non-injected user message after the
    // askUser means an answer was recorded (card click or chat-input
    // passthrough) — the ask is no longer pending.
    const histMsgs = msg.messages;
    const lastHistMsg = findLastRealMessage(histMsgs) || undefined;
    const isAskUserPending = lastHistMsg && lastHistMsg.type === 'askUser'
      && Array.isArray(lastHistMsg.items) && lastHistMsg.items.length > 0;
    const isAskPermissionPending = lastHistMsg && lastHistMsg.type === 'askPermission'
      && lastHistMsg.toolName;

    if (isAskUserPending || isAskPermissionPending) {
      // Agent is blocked on AskUser/AskPermission — clear stale sessionTexts so we don't create
      // a phantom streaming bubble for text that's already in history.
      delete state.sessionTexts[sid];
    }

    // Re-create streaming/completed state from sessionTexts/sessionThinkingBuffers/pendingRestore.
    // (isStillBusy computed above for the busyTail history-restore hint.)
    if (!isAskUserPending && !isAskPermissionPending) {
      // If the backend history already includes the completed message, clean up pendingRestore
      // to avoid duplication. Check last AI message text+thinking match.
      const pendingData = state.pendingRestore[sid];
      if (pendingData && !isStillBusy) {
        const histMsgs = msg.messages;
        const lastAiMsg = histMsgs && [...histMsgs].reverse().find(m => m.type === 'ai' && (m.text || m.thinking));
        const textMatch = lastAiMsg && lastAiMsg.text === pendingData.text;
        const thinkingMatch = lastAiMsg && lastAiMsg.thinking === pendingData.thinking;
        // Exact match: both text and thinking (or both absent) match.
        // Loose match: text matches and thinking is absent on both sides, or
        //             thinking matches and text is absent on both sides.
        const looseMatch = lastAiMsg && (
          (textMatch && (!lastAiMsg.thinking || !pendingData.thinking || thinkingMatch)) ||
          (!lastAiMsg.text && !pendingData.text && thinkingMatch)
        );
        if (textMatch || looseMatch) {
          delete state.pendingRestore[sid];
        }
        // If texts don't match, keep pendingRestore — it may be from a turn
        // the backend hasn't persisted yet. The sessionPendingAiMessages fix
        // ensures text is not concatenated across rounds, so even if rendered
        // as extra bubbles, the content is correct (not duplicated/concatenated).
      }

      const pd = state.pendingRestore[sid];
      // After historyPage restores the authoritative state, clean up any remaining
      // per-session buffer state that wasn't consumed by the active streaming path.
      // This prevents stale data from leaking through on subsequent restores.
      if (!isStillBusy && !pd && !state.sessionTexts[sid]) {
        delete state.sessionThinkingBuffers[sid];
        delete state.sessionPendingAiMessages[sid];
      }
      const thinkBuf = state.sessionThinkingBuffers[sid] || pd?.thinking;
      const txtBuf = state.sessionTexts[sid] || pd?.text;

      // Restore thinking bubble
      if (thinkBuf) {
        activeView.stream.thinkingText = thinkBuf;
        const hasText = !!txtBuf;
        const done = hasText || !isStillBusy;
        const chat = activeView.dom.chat;
        const row = document.createElement('div');
        row.className = 'row ai thinking-row';
        const bubble = document.createElement('div');
        bubble.className = 'bubble ai thinking-bubble' + (done ? ' thinking-done' : '');
        const label = document.createElement('div');
        label.className = 'thinking-label' + (done ? ' collapsible' : '');
        label.textContent = t('chat.thinkingLabel');
        const content = document.createElement('div');
        content.className = 'thinking-content';
        if (done) {
          content.style.display = 'none';
          label.classList.add('expanded');
          label.onclick = () => {
            const visible = content.style.display !== 'none';
            content.style.display = visible ? 'none' : '';
            label.classList.toggle('expanded', !visible);
          };
        }
        content.innerHTML = renderMarkdownWithMath(activeView.stream.thinkingText) + (!done ? '<span class="cursor"></span>' : '');
        bubble.appendChild(label);
        bubble.appendChild(content);
        row.appendChild(bubble);
        chat.appendChild(row);
        activeView.stream.currentThinkingBubble = done ? null : bubble;
      }

      // Restore text bubble
      if (txtBuf) {
        activeView.stream.aiText = txtBuf;
        const chat = activeView.dom.chat;
        const row = document.createElement('div');
        row.className = 'row ai';
        activeView.stream.currentAiBubble = document.createElement('div');
        activeView.stream.currentAiBubble.className = 'bubble ai';
        activeView.stream.currentAiBubble.innerHTML = renderMarkdownWithMath(activeView.stream.aiText) + (isStillBusy ? '<span class="cursor"></span>' : '');
        row.appendChild(activeView.stream.currentAiBubble);
        chat.appendChild(row);
        if (!isStillBusy) {
          activeView.stream.currentAiBubble = null;
          activeView.stream.aiText = '';
          delete state.pendingRestore[sid];
        }
      }

      const askBuf = state.sessionAskBuffers[sid];
      if (isStillBusy && askBuf && askBuf.answer) {
        activeView.stream.askAnswerText = askBuf.answer;
        const chat = activeView.dom.chat;
        const row = document.createElement('div');
        activeView.stream.currentAskBubble = document.createElement('div');
        activeView.stream.currentAskBubble.className = 'bubble ai';
        const label = document.createElement('div');
        label.className = 'ask-label';
        label.textContent = t('chat.askLabel');
        const content = document.createElement('div');
        content.innerHTML = renderMarkdownWithMath(activeView.stream.askAnswerText) + '<span class="cursor"></span>';
        activeView.stream.currentAskBubble.appendChild(label);
        activeView.stream.currentAskBubble.appendChild(content);
        row.appendChild(activeView.stream.currentAskBubble);
        chat.appendChild(row);
      }
      // Re-create pending tool card if this session has an in-progress tool.
      // Also restored in sidebar.js resetChatForActiveSession() for immediate
      // feedback before history arrives. This re-creation ensures the spinner
      // persists after the async getHistory roundtrip clears the DOM.
      if (state.sessionPendingTools[sid]) {
        renderToolPending(state.sessionPendingTools[sid].label, sid);
      }
    }

    // Re-create interactive AskUser if the last history message is an unanswered askUser.
    // Must be OUTSIDE the busySessionIds check — a non-active session that received
    // AskUser was never added to busySessionIds (setBusy only runs for the active session).
    // Simply check if the last history message is askUser with items — if already answered,
    // there would be subsequent Ai/Tool messages after it, so it wouldn't be the last message.
    if (isAskUserPending) {
      // Remove ALL disabled askUser rows (restored by restoreFromBackendHistory).
      activeView.dom.chat.querySelectorAll('.row.ai').forEach(row => {
        if (row.querySelector('.option-box')) row.remove();
      });
      renderAskUser(lastHistMsg.items, sid, lastHistMsg.agentName, lastHistMsg.requestId);
    }

    // Re-create interactive AskPermission if the last history message is an unanswered askPermission.
    // Same logic as AskUser — if already answered, there would be subsequent tool messages.
    // Guard: skip if the user has already answered this permission in the current session
    // (tool is still executing, askPermission is still the last history entry).
    if (isAskPermissionPending && !state.answeredPermissions.has(sid)) {
      // Remove the disabled askPermission row (restored by restoreFromBackendHistory).
      activeView.dom.chat.querySelectorAll('.row.ai').forEach(row => {
        if (row.querySelector('.permission-pending-box')) row.remove();
      });
      renderPermissionPrompt(lastHistMsg.toolName, lastHistMsg.summary, lastHistMsg.input, sid, lastHistMsg.dangerLevel, lastHistMsg.sourceAgent, lastHistMsg.sourceSession, lastHistMsg.sourceTeam, lastHistMsg.requestId, lastHistMsg.safetyMode);
    }

    // Final scroll-to-bottom: after all rendering (history + streaming bubbles + pending tools)
    // is complete, ensure the viewport shows the latest content.
    // Uses rAF to avoid layout thrashing — fires after any pending style calculations.
    requestAnimationFrame(() => {
      const chat = view.dom.chat;
      if (view.stream.scrollSnapped || chat.scrollHeight - chat.scrollTop - chat.clientHeight < 60) {
        chat.scrollTop = chat.scrollHeight;
        view.stream.scrollSnapped = true;
      }
      // Second pass after deferred markdown rendering settles
      setTimeout(() => { chat.scrollTop = chat.scrollHeight; }, 150);
    });

  } else {
    // Scroll-up pagination — prepend older messages
    // Guard against duplicate historyPage responses (e.g. from double getHistory on initial load):
    // skip when the response offset is NOT older than what we already have.
    // This also covers the fully-loaded case (current offset 0, duplicate
    // response offset 0 → 0 >= 0 skips). Note offset === 0 is NOT a skip
    // condition on its own: the server packs the oldest page into an
    // offset-0 response (SessionStore.getHistoryPage: offset=max(0,before-
    // limit), messages=slice(offset, ...)) — skipping it made the oldest
    // ≤50 messages unreachable and looped the top loader forever.
    if (msg.offset >= view.pagination.offset) {
      // Skipping duplicate response
      return;
    }
    const chat = activeView.dom.chat;
    const prevScrollHeight = chat.scrollHeight;
    const prevScrollTop = chat.scrollTop;
    // Insert before first child
    const fragment = document.createDocumentFragment();
    const tempDiv = document.createElement('div');
    const origChat = activeView.dom.chat;
    activeView.dom.chat = tempDiv;
    restoreFromBackendHistory(msg.messages, { scrollToBottom: false });
    activeView.dom.chat = origChat;
    // Move rendered children to fragment, skip animation on prepended rows
    while (tempDiv.firstChild) {
      const child = tempDiv.firstChild;
      if (child.classList && child.classList.contains('row')) {
        child.classList.add('prepend-skip-anim');
      }
      fragment.appendChild(child);
    }
    chat.prepend(fragment);
    // Restore scroll position so user stays at the same message
    const newScrollHeight = chat.scrollHeight;
    chat.scrollTop = prevScrollTop + (newScrollHeight - prevScrollHeight);
    view.pagination.offset = msg.offset;
    view.pagination.hasMore = msg.hasMore;
    if (!view.pagination.hasMore) showHistoryEnd();
  }
});

// --- Multi-agent events ---
// Background sub-agent activity shows a header indicator (like Bash background tasks).
// No tool cards or text rendered in the chat — the parent's Delegate tool
// card (spinner → result) is the only chat-level feedback.
// Click the indicator to see a dropdown with per-agent status.

function updateBgAgentIndicator(targetSid) {
  // Update the indicator of the view that DISPLAYS the session owning these
  // sub-agents — not blindly activeView. Sub-agent events are routed through
  // the popup's ChatView (activeView temporarily points there; popup views
  // have no indicator element), which previously left the primary badge stale
  // while the dropdown re-rendered from live data on click — showing e.g.
  // count=2 on the badge but 3 rows inside (a stuck sub-agent that emits no
  // further events never triggered another badge refresh).
  const view = targetSid ? findViewBySessionId(targetSid) : activeView;
  if (!view) return;
  const el = view.dom.bgagentIndicatorEl;
  if (!el) return;
  const sid = view.sessionId;
  const bgAgents = (sid && state.sessionBgAgents[sid]) || {};
  const count = Object.keys(bgAgents).length;
  if (count > 0) {
    el.classList.remove('hidden');
    el.querySelector('.bgagent-count').textContent = count;
  } else {
    el.classList.add('hidden');
    el.setAttribute('aria-expanded', 'false');
    const dropdown = view.dom.bgagentDropdownEl;
    if (dropdown) dropdown.classList.add('hidden');
  }
  if (view === activeView) renderBgAgentDropdown();
}
state.updateBgAgentIndicator = updateBgAgentIndicator;

// ── Sub-agents panel (2026-08-25 spec, frozen @142535e9) ─────────────
// Row = [status dot + label][kind chip][name · task][uptime][retries ×N]
//       + secondary lines: stuck alert label / current tool subtitle.
// User rulings locked in the spec: barrier hidden, retries only when >0,
// NO kind grouping (chip instead), NO inline stuck buttons (operations
// live in the agent popup — A13).

/** Derive the AgentKind from the session-id prefix when the snapshot kind is
 *  absent (live agentStart carries no kind field). */
function bgAgentKindFromSession(sessionId) {
  const raw = sessionId || '';
  if (raw.startsWith('team-')) return 'Team';
  if (raw.startsWith('delegate-')) return 'Delegate';
  if (raw.startsWith('subtask-')) return 'SubTask';
  if (raw.startsWith('dag-')) return 'Flow';
  if (raw.startsWith('ephemeral-')) return 'Ephemeral';
  // #28 可观测接线: Project 节点/分发器后端注册为 AgentKind.Flow → 面板 kind
  // 徽章显示 Flow（与快照 kind 一致）。
  if (raw.startsWith('node-')) return 'Flow';
  if (raw.startsWith('dispatcher-')) return 'Flow';
  return '';
}

/** Resolve which session bucket a background task belongs to for the UI.
 *  A task executed by a background (sub)agent carries the agent's OWN
 *  sessionId (delegate-/subtask- prefix), not the top-level session the user
 *  is viewing — so without this the task lands in a bucket updateBgTasksUI
 *  never reads and is neither counted nor shown. sessionBgAgents is keyed by
 *  the root session (agentStart carries rootSessionId) and each entry holds
 *  info.sessionId = the agent's node session, so reverse-look-up that mapping
 *  to route a sub-agent's background task into the owning root bucket.
 *  Backend-provided msg.rootSessionId wins when present. */
function bgTaskRootFor(sessionId) {
  if (!sessionId) return '';
  for (const [root, agents] of Object.entries(state.sessionBgAgents || {})) {
    if (!agents) continue;
    for (const entry of Object.values(agents)) {
      if ((entry.sessionId || '') === sessionId) return root;
    }
  }
  return sessionId;
}

/** Row state machine (spec §3): done > stuck > frozen > error > idle > active. */
function bgRowState(info) {
  if (info.done) return 'done';
  if (info.stuck) return 'stuck';
  if (info.frozen) return 'frozen';
  if (isFailedSnapshotStatus(info.status)) return 'error';
  const st = info.status || '';
  if (st === 'Idle' || st === 'WaitingForUser' || st === 'idle') return 'idle';
  return 'active';
}

// Uptime tick: while the dropdown is open, refresh .bg-task-uptime text in
// place every 15s (no full re-render — keeps keyboard focus). Self-terminates
// once the dropdown hides, regardless of which close path ran.
let bgUptimeTick = null;
function armBgUptimeTick(view) {
  if (bgUptimeTick) clearTimeout(bgUptimeTick);
  bgUptimeTick = setTimeout(() => {
    bgUptimeTick = null;
    const dd = view && view.dom.bgagentDropdownEl;
    if (dd && !dd.classList.contains('hidden')) {
      dd.querySelectorAll('.bg-task-uptime[data-started-at]').forEach(el => {
        const started = Number(el.getAttribute('data-started-at'));
        if (started > 0) el.textContent = fmtUptime(Date.now() - started);
      });
      armBgUptimeTick(view);
    }
  }, 15000);
}

function renderBgAgentDropdown() {
  if (!activeView) return;
  const dropdownEl = activeView.dom.bgagentDropdownEl;
  const listEl = activeView.dom.bgagentDropdownListEl;
  if (!listEl) return;
  const sid = activeView?.sessionId;
  const bgAgents = (sid && state.sessionBgAgents[sid]) || {};
  const entries = Object.entries(bgAgents);
  // Panel header carries the live count (aria-live polite, spec §5).
  const headerEl = dropdownEl ? dropdownEl.querySelector('.bg-dropdown-header') : null;
  if (headerEl) headerEl.textContent = t('subagents.header', { count: entries.length });
  if (entries.length === 0) {
    // Empty state (spec §6): restrained i18n line, never fake rows.
    listEl.innerHTML = '<div class="bg-dropdown-empty">' + escapeHtml(t('subagents.empty')) + '</div>';
    return;
  }
  listEl.innerHTML = entries.map(([id, info]) => {
    const rowState = bgRowState(info);
    const terminal = rowState === 'done' || rowState === 'error';
    const statusLabel = t('subagents.status.' + (rowState === 'active' ? 'running' : rowState));
    const dotClass = {
      active: 'bg-status-active', idle: 'bg-status-idle', stuck: 'bg-status-stuck',
      error: 'bg-status-error', frozen: 'bg-status-frozen', done: 'bg-status-done',
    }[rowState];
    const kind = info.kind || bgAgentKindFromSession(info.sessionId || id);
    const kindPart = kind ? '<span class="bg-task-kind">' + escapeHtml(kind) + '</span>' : '';
    const displayName = info.name || id;
    const taskText = info.task ? displayName + ' · ' + info.task : displayName;
    const namePart = '<span class="bg-task-name" title="' + escapeHtml(taskText) + '">' + escapeHtml(taskText) + '</span>';
    // Uptime (spec §2.2): relative duration while non-terminal; hidden once done.
    const uptimePart = (!terminal && info.startedAt)
      ? '<span class="bg-task-uptime" data-started-at="' + info.startedAt + '">' + escapeHtml(fmtUptime(Date.now() - info.startedAt)) + '</span>'
      : '';
    // Retries chip: only when >0 (0 is noise — user ruling ②).
    const retries = info.retryCount || 0;
    const retriesPart = retries > 0
      ? '<span class="bg-task-retries" title="' + escapeHtml(t('manage.retry')) + ' ×' + retries + '">×' + retries + '</span>'
      : '';
    // Stuck: restrained red label with idle seconds (manage-panel semantics).
    // NO inline cancel/restart buttons — operations live in the agent popup (A13).
    const stuckPart = (rowState === 'stuck' && info.stuck)
      ? '<span class="bg-task-stuck" role="alert">' + escapeHtml(
          info.stuck.action === 'restart'
            ? t('manage.stuckAutoRestart')
            : t('manage.stuck', { secs: info.stuck.idleSecs ?? '' })
        ) + '</span>'
      : '';
    const toolLabel = info.currentTool || '';
    const toolPart = (toolLabel && !terminal)
      ? '<span class="bgagent-tool" title="' + escapeHtml(toolLabel) + '">↳ ' + escapeHtml(toolLabel) + '</span>'
      : '';
    // The sub-agent's OWN session id — agentStart carries it as
    // msg.nodeSessionId (delegate-*/subtask-*/team-<sid>/dag-*). Every row
    // is clickable; the click handler strips the team- prefix and routes
    // dag-*/bare sids to the flow popup (same view the Flow panel uses).
    const sessionId = info.sessionId || (isBgAgentId(id) ? id : '');
    const clickAttr = sessionId
      ? `data-bg-key="${escapeHtml(id)}" data-node-session-id="${escapeHtml(sessionId)}"`
      : '';
    return '<div class="bg-task-row' + (rowState === 'done' ? ' done' : '') + '" role="listitem" tabindex="0" ' + clickAttr + '>' +
      '<span class="bg-task-status ' + dotClass + '" aria-hidden="true"></span>' +
      '<div class="bg-task-info">' +
        // Meta slots (state / kind / uptime) on the FIRST line — fixed set,
        // never wraps the name (user ruling 21: agent name is ALWAYS the
        // second line, visually separated from the labels).
        '<div class="bg-task-line bg-task-meta">' +
          '<span class="bg-task-state bg-state-' + rowState + '">' + escapeHtml(statusLabel) + '</span>' +
          kindPart + retriesPart + uptimePart +
        '</div>' +
        '<div class="bg-task-line bg-task-name-line">' + namePart + '</div>' +
        stuckPart + toolPart +
      '</div>' +
    '</div>';
  }).join('');

  // Wire click handlers for sub-agent rows
  listEl.querySelectorAll('[data-node-session-id]').forEach(row => {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      const rawSessionId = row.getAttribute('data-node-session-id');
      const info = bgAgents[row.getAttribute('data-bg-key')];
      if (info && rawSessionId) {
        // Capture the dropdown BEFORE opening the popup — openStepPopup
        // switches activeView to the popup view whose fakeDom has no dropdown.
        const dropdown = activeView.dom.bgagentDropdownEl;
        // team-<sid> wraps the agent's bare session id (its ui.json key) —
        // strip it, matching how the retired Team panel used to open these
        // popups; live team-agent events keep flowing into the same view.
        const sessionId = rawSessionId.replace(/^team-/, '');
        if (isBgAgentId(rawSessionId)) {
          // delegate-*/subtask-* → bg-agent popup (ephemeral sessions,
          // live-rendered; history only persists at completion).
          openBgAgentPopup(rawSessionId, info.name, info.task);
        } else {
          // dag-*/bare sid → flow popup — the SAME entry the Flow/Team panel
          // uses, so live events flow into one shared view instead of
          // building a duplicate hidden view per popup module.
          openFlowStepPopup(row.getAttribute('data-bg-key'), info.name, info.name, '', sessionId);
        }
        // Close the dropdown
        if (dropdown) dropdown.classList.add('hidden');
      }
    });
  });

  // Keyboard (spec §7 / APG listbox): Enter/Space opens the row's popup,
  // ArrowUp/ArrowDown move between rows. Property assignment — idempotent
  // across re-renders (no listener accumulation).
  listEl.onkeydown = (e) => {
    const row = e.target && e.target.closest ? e.target.closest('.bg-task-row') : null;
    if (!row) return;
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      row.click();
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const rows = [...listEl.querySelectorAll('.bg-task-row')];
      const i = rows.indexOf(row);
      const next = rows[e.key === 'ArrowDown' ? i + 1 : i - 1];
      if (next) next.focus();
    }
  };
}

// Toggle dropdown on indicator click — register for ALL views
Object.values(chatViews).forEach(v => {
  const indicator = v.dom.bgagentIndicatorEl;
  const dropdown = v.dom.bgagentDropdownEl;
  if (!indicator || !dropdown) return;
  const toggle = (e) => {
    e.stopPropagation();
    setActiveView(v);
    const opening = dropdown.classList.contains('hidden');
    if (opening) {
      renderBgAgentDropdown();
      dropdown.classList.remove('hidden');
      armBgUptimeTick(v);
    } else {
      dropdown.classList.add('hidden');
    }
    indicator.setAttribute('aria-expanded', String(opening));
  };
  indicator.addEventListener('click', toggle);
  // role=button keyboard parity (spec §7 — non-mouse reachable).
  indicator.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(e); }
  });
});

// Close dropdown on outside click
document.addEventListener('click', (e) => {
  Object.values(chatViews).forEach(v => {
    const dropdown = v.dom.bgagentDropdownEl;
    const indicator = v.dom.bgagentIndicatorEl;
    if (dropdown && !dropdown.contains(e.target) && indicator && !indicator.contains(e.target)) {
      if (!dropdown.classList.contains('hidden')) {
        dropdown.classList.add('hidden');
        if (indicator) indicator.setAttribute('aria-expanded', 'false');
      }
    }
  });
});

onMessage('agentStart', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  // rootSessionId points at the top-level main session even for nested
  // sub-agents (child → grandchild), so sessionBgAgents stays keyed by the
  // session the user is actually viewing.
  const sid = msg.rootSessionId || msg.sessionId || state.activeSessionId;
  if (!sid) return;
  const aid = msg.agentId || msg.name;
  if (view) view.stream.activeAgentId = aid;
  // #28 可观测接线: node-*/dispatcher-* 会话（Project Flow Map 节点 / 任务分发
  // 器）进 Sub-Agents 面板, 与 Delegate/SubTask 同一可观测性标准。旧实现在此
  // 过滤 node-*（当时节点事件缺 rootSessionId 归属 → 行落错桶且无法清理 →
  // 幽灵行; 根因是后端 wsSend 未接线, 已后端修复——事件带 rootSessionId,
  // 终态行由下方 done handler 按 sessionId 清理——parentRef=None 的 agent
  // 以会话级 done 收尾, 不走 agentDone 路径）。
  if (!state.sessionBgAgents[sid]) state.sessionBgAgents[sid] = {};
  // Cross-keyspace dedupe: snapshot-restored entries (activeAgents handler)
  // key on the bare sessionId (getActiveAgents pins agentId == sessionId),
  // while live events key on the actor-path agentId (mail-*). Without this,
  // the same agent renders twice in the dropdown whenever a snapshot row
  // coexists with a live turn (e.g. page refresh while a team agent is busy,
  // then its next turn starts). The live row is richer (carries
  // taskDescription) — drop the stale snapshot-keyed twin.
  const sessionKey = (msg.nodeSessionId || '').replace(/^team-/, '');
  if (sessionKey && sessionKey !== aid && state.sessionBgAgents[sid][sessionKey]) {
    delete state.sessionBgAgents[sid][sessionKey];
  }
  // Sub-agents panel (2026-08-25): preserve management fields across turns —
  // a per-turn agentStart overwrites the entry, so kind/startedAt/retryCount
  // carry over from the previous entry; a fresh turn clears stuck/frozen
  // (activity resumed) and marks the row active.
  const prev = state.sessionBgAgents[sid][aid] || null;
  state.sessionBgAgents[sid][aid] = {
    name: msg.name || aid,
    task: msg.taskDescription || '',
    // nodeSessionId is the sub-agent's OWN session id (delegate-*/subtask-*/
    // team-<sid>/dag-*; AgentStart carries no sessionId field of its own —
    // msg.sessionId on delegate/subtask events is the PARENT session (injected
    // by routeWsSend), so using it here loaded the host's history (串台).
    sessionId: msg.nodeSessionId || '',
    kind: (prev && prev.kind) || bgAgentKindFromSession(msg.nodeSessionId || ''),
    startedAt: (prev && prev.startedAt) || Date.now(),
    status: 'Processing',
    retryCount: (prev && prev.retryCount) || 0,
    stuck: null,
    frozen: false,
    currentTool: null,
    done: false,
  };
  // Always refresh the badge of the view displaying the OWNING session (sid),
  // regardless of which view this event was routed through (popup views have
  // no indicator; activeView-based updates silently no-op'd).
  updateBgAgentIndicator(sid);
});

// Sub-agents panel (2026-08-25): any activity on a stuck entry clears the
// stuck flag — the row falls back to its live state on the next render.
function clearBgStuck(msg) {
  const sid = msg.rootSessionId || msg.sessionId;
  const aid = msg.agentId;
  if (!sid || !aid) return;
  const entry = state.sessionBgAgents[sid] && state.sessionBgAgents[sid][aid];
  if (entry && entry.stuck) {
    entry.stuck = null;
    updateBgAgentIndicator(sid);
  }
}

onMessage('agentTextDelta', (msg, view) => { resetStreamTimeout(msg.sessionId); clearBgStuck(msg); });
onMessage('agentToolCallDetected', (msg, view) => { resetStreamTimeout(msg.sessionId); clearBgStuck(msg); });

onMessage('agentToolStart', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  clearBgStuck(msg);
  const sid = msg.rootSessionId || msg.sessionId || state.activeSessionId;
  if (!sid) return;
  const aid = msg.agentId || (view && view.stream.activeAgentId);
  if (aid && state.sessionBgAgents[sid] && state.sessionBgAgents[sid][aid]) {
    state.sessionBgAgents[sid][aid].currentTool = msg.label;
    if (view) renderBgAgentDropdown();
  }
});

onMessage('agentToolEnd', (msg, view) => { resetStreamTimeout(msg.sessionId); });
// 审计 20260903 子项①：工具执行期心跳——长工具执行（toolStart→toolEnd 之间零
// 事件段）由后端每 30s 推送心跳重置 busy timer，前端 630s 纯静默超时不再误杀
// 正在干活的 turn。agentToolHeartbeat（子代理原事件）与 toolHeartbeat（主会话
// /转换后）都重置对应会话计时器（与 agentToolEnd 既有重置面一致）。
onMessage('toolHeartbeat', (msg, view) => { resetStreamTimeout(msg.sessionId); });
onMessage('agentToolHeartbeat', (msg, view) => { resetStreamTimeout(msg.rootSessionId || msg.sessionId); });
onMessage('agentEnd', (msg, view) => { resetStreamTimeout(msg.sessionId); });

onMessage('agentThinking', (msg, view) => { resetStreamTimeout(msg.sessionId); clearBgStuck(msg); });
onMessage('agentRetryStatus', (msg, view) => { resetStreamTimeout(msg.sessionId); clearBgStuck(msg); });

// Sub-agents panel (2026-08-25 spec §3): taskStuck marks the dropdown row —
// taskStuck carries the child's BARE sessionId while sessionBgAgents entries
// store it as info.sessionId (possibly team- wrapped). action=restart counts
// as one auto-restart (the visible ×N retries chip, manage-panel semantics).
onMessage('taskStuck', (msg) => {
  const bare = (msg.sessionId || '').replace(/^team-/, '');
  if (!bare) return;
  for (const [rootSid, agents] of Object.entries(state.sessionBgAgents)) {
    for (const info of Object.values(agents)) {
      const entrySid = (info.sessionId || '').replace(/^team-/, '');
      if (entrySid !== bare) continue;
      info.stuck = { idleSecs: msg.idleSecs, action: msg.action };
      if (msg.kind) info.kind = msg.kind;
      if (msg.action === 'restart') info.retryCount = (info.retryCount || 0) + 1;
      updateBgAgentIndicator(rootSid);
    }
  }
});

onMessage('agentDone', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  const sid = msg.rootSessionId || msg.sessionId || state.activeSessionId;
  if (!sid) return;
  const aid = msg.agentId || (view && view.stream.activeAgentId);
  // W1-d dual-key cleanup: live events key sessionBgAgents entries on the
  // actor-path agentId, but entries rebuilt from the activeAgents snapshot
  // after a refresh key on the agent's SESSION id (backend pins agentId ==
  // sessionId for restored rows — WebSocketRoutes getActiveAgents). An
  // agentDone carrying the actor-path key can never clear a sessionId-keyed
  // restored row, so an agent that was mid-turn during a refresh ghosted as
  // "running" until the next refresh. Resolve the session-id key from
  // nodeSessionId (strip the team- routing prefix) and clear BOTH keys.
  const sessionKey = (msg.nodeSessionId || '').replace(/^team-/, '');
  const keys = [...new Set([aid, sessionKey].filter(Boolean))];
  if (keys.length && state.sessionBgAgents[sid]) {
    let touched = false;
    for (const k of keys) {
      if (state.sessionBgAgents[sid][k]) {
        state.sessionBgAgents[sid][k].done = true;
        state.sessionBgAgents[sid][k].stuck = null; // done supersedes stuck (bgRowState order)
        touched = true;
      }
    }
    if (touched && view) renderBgAgentDropdown();
    // Clean up bg-agent popup view after a delay
    if (aid && isBgAgentId(aid)) cleanupBgAgentView(aid);
    // Remove after 2s — always runs, even if the parent session isn't displayed
    setTimeout(() => {
      if (!state.sessionBgAgents[sid]) return;
      let removedAny = false;
      for (const k of keys) {
        if (state.sessionBgAgents[sid][k]) { delete state.sessionBgAgents[sid][k]; removedAny = true; }
      }
      if (!removedAny) return;
      // Clean up empty session entries
      if (Object.keys(state.sessionBgAgents[sid]).length === 0) {
        delete state.sessionBgAgents[sid];
      }
      // Update indicator if the affected view is currently displayed
      const targetView = findViewBySessionId(sid);
      if (targetView) {
        const saved = activeView;
        setActiveView(targetView);
        updateBgAgentIndicator();
        setActiveView(saved);
      }
    }, 2000);
  }
  if (view) view.stream.activeAgentId = null;
});

// --- Session switch → explorer / task list refresh ---
window.addEventListener('nebflow-session-change', (e) => {
  refreshExplorer(e.detail.sessionId);
  refreshScheduledTasks(e.detail.sessionId);
  if (e.detail.sessionId) sendWs({ type: 'getTaskList', sessionId: e.detail.sessionId });
  // ⑩ the frozen input-bar visual follows the newly activated session.
  applyLocalFreeze();
});

// --- Compaction events (per-session) ---
// These events include sessionId from the backend for root agents.
// We track compacting sessions globally so the sidebar shows an indicator
// even when the user is viewing a different session.
function setCompacting(sessionId, active) {
  if (!sessionId) return;
  if (active) {
    state.compactingSessionIds.add(sessionId);
  } else {
    state.compactingSessionIds.delete(sessionId);
  }
  window.dispatchEvent(new CustomEvent('session-compacting', { detail: { sessionId } }));
}

onMessage('compactStart', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
  setCompacting(sid, true);
  if (view) {
    renderCompactStartCard(view);
    // Persist the same system text as before — history restore renders it as
    // a quiet notice card; the live status card is a live-view-only element.
    saveMsg({ type: 'system', content: t('chat.compacting') }, sid);
  }
});

onMessage('compactComplete', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
  setCompacting(sid, false);
  // Compaction shrinks the live context — refresh the usage ring immediately
  // instead of leaving the pre-compaction (near-threshold) value up until the
  // next LLM round's usageUpdate. outputTokens is stale per-turn data, drop it.
  if (msg.after != null) {
    const prev = state.sessionModelInfo[sid] || {};
    state.sessionModelInfo[sid] = { ...prev, inputTokens: msg.after, outputTokens: undefined };
    try { localStorage.setItem(LS_MODEL_INFO_KEY, JSON.stringify(state.sessionModelInfo)); } catch(e) {}
    updateHeaderModelInfo();
  }
  // Compaction completes outside the normal done chain — finish any
  // streaming agent bubbles so their cursors don't linger.
  if (view) {
    Object.keys(view.stream.agentBubbles).forEach(id => finishAgent(id));
    view.stream.agentBubbles = {};
    view.stream.activeAgentId = null;
  }
  if (view) {
    const detail = msg.reportPath ? ` (report: ${msg.reportPath.split('/').pop()})` : '';
    const text = t('chat.compacted', { before: msg.before, after: msg.after, detail });
    renderCompactDoneCard(view, { before: msg.before, after: msg.after, detail });
    saveMsg({ type: 'system', content: text }, sid);
  }
  // Drain queued messages only if agent is NOT busy. During auto-compaction
  // with resume, the agent immediately starts a resume turn after compactComplete.
  // The 'done' event after the resume turn will drain correctly when idle.
  if (sid && !state.busySessionIds.has(sid)) {
    import('./input.js').then(({ drainMessageQueue }) => setTimeout(() => drainMessageQueue(sid), 50));
  }
});

onMessage('compactFailed', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  resetStreamTimeout(sid);
  setCompacting(sid, false);
  // A failed compact turn also ends the agent turn — clear cursors.
  if (view) {
    Object.keys(view.stream.agentBubbles).forEach(id => finishAgent(id));
    view.stream.agentBubbles = {};
    view.stream.activeAgentId = null;
  }
  if (view) {
    const text = t('chat.compactFailed', { attempt: msg.attempt, maxAttempts: msg.maxAttempts });
    renderCompactFailCard(view, text);
    saveMsg({ type: 'system', content: text }, sid);
  }
  if (msg.attempt >= msg.maxAttempts) {
    if (view) {
      renderError(t('chat.compactCircuitBreaker', { attempt: msg.attempt }));
    }
    clearBusyFor(msg);
  } else {
    // Retry path — also drain queue in case user sent messages during compaction
    if (sid) {
      import('./input.js').then(({ drainMessageQueue }) => setTimeout(() => drainMessageQueue(sid), 50));
    }
  }
});

// --- Agent panel events (global) ---
onMessage('agentList', (msg, view) => {
  state.agentsData = msg.agents || [];
  renderAgentList();
  // Auto-select first agent if none selected
  if (!state.selectedAgent && state.agentsData.length > 0) {
    import('./sidebar.js').then(({ selectAgent }) => {
      selectAgent(state.agentsData[0]?.name || 'Nebula');
    });
  }
});

onMessage('agentSessionList', (msg, view) => {
  // Unified list — accept all sessions regardless of which agent triggered the request
  const agentName = msg.agentName;
  let sessions = msg.sessions || [];
  const folders = msg.folders || [];
  state.folders = folders;
  state.foldersWithRules = new Set(msg.foldersWithRules || []);

  // Build sessionId -> agentName mapping
  sessions.forEach(s => { state.sessionAgentMap[s.id] = s.agentName || agentName; });

  // Restore bypass state from persisted session metadata
  state.bypassSessions = new Set(sessions.filter(s => s.safetyMode === 'auto-all').map(s => s.id));
  state.safetyModes = {};
  sessions.forEach(s => { if (s.safetyMode) state.safetyModes[s.id] = s.safetyMode; });

  // Render sidebar — active highlight shows the current active session
  renderSessionSidebar(sessions, state.activeSessionId);
  initHeaderModelInfo();
});

onMessage('agentSystemPrompt', (msg, view) => showAgentModal(msg.name, msg.systemMd || ''));
onMessage('agentSystemPromptSaved', () => sendWs({ type: 'listAgents' }));

// --- Server config ---
onMessage('serverConfig', (msg, view) => {
  if (msg.streamTimeoutMs) state.streamTimeoutMs = msg.streamTimeoutMs;
  if (msg.version) state.serverVersion = msg.version;
  if (msg.thinking !== undefined) {
    state.serverThinking = msg.thinking;
    state.thinkingMode = msg.thinking;
  }
  if (msg.tools) {
    state.availableTools = msg.tools;
  }
  if (msg.mcpServers) {
    state.mcpServers = msg.mcpServers;
  }
  // !== undefined (not truthiness): the schedule node must sync even when
  // falsy-but-present ({enabled:false,...}) so the settings panel always
  // echoes server truth (freeze-consistency fix 2026-08-27).
  if (msg.workSchedule !== undefined) {
    state.workSchedule = msg.workSchedule;
    // ⑩ schedule change re-evaluates the local freeze display immediately.
    applyLocalFreeze();
    // Re-render the settings panel if open so the schedule editor echoes the
    // authoritative server config (freeze-schedule spec F2/F5).
    const settingsOverlay = document.getElementById('settings-overlay');
    if (settingsOverlay && settingsOverlay.classList.contains('on')) {
      import('./sidebar.js').then(({ renderSettings }) => renderSettings());
    }
  }
  // 现象 2 契约（2026-08-30）：freezeState 节点随 serverConfig 广播（WS 连接
  // 初始态/配置热更/skipFreeze 后）。skipped 是 skip 语义的持久来源——刷新后
  // applyLocalFreeze 靠它保持「被跳过的窗口不冻结」，而非依赖会丢失的内存镜像。
  if (msg.freezeState !== undefined) {
    state.freezeState = msg.freezeState;
    applyLocalFreeze();
  }
  if (msg.stt) {
    state.stt = msg.stt;
    // STT config echo — re-render the settings panel so the status indicator
    // (configured / free-browser-path) stays authoritative (#295).
    const settingsOverlay = document.getElementById('settings-overlay');
    if (settingsOverlay && settingsOverlay.classList.contains('on')) {
      import('./sidebar.js').then(({ renderSettings }) => renderSettings());
    }
  }
});

// MCP server list updated (after background init completes)
onMessage('mcpServersUpdate', (msg, view) => {
  if (msg.mcpServers) state.mcpServers = msg.mcpServers;
});

onMessage('configData', (msg, view) => {
  state.configText = msg.config || '';
  state._freshConfigText = state.configText; // Cache for slider's fetch-before-save
  try { state.parsedConfig = JSON.parse(state.configText); } catch { state.parsedConfig = null; }
  state.configDirty = false;
  const editor = document.getElementById('config-editor');
  if (editor) editor.value = state.configText;
  // Re-render settings if the modal is open
  const settingsOverlay = document.getElementById('settings-overlay');
  if (settingsOverlay && settingsOverlay.classList.contains('on')) {
    renderSettings();
  }
  // First-run onboarding: fixed wizard (new user) or one-time greeting offer
  // (returning user). Triggers once per boot — configData re-fires on save.
  initOnboarding(msg);
});

onMessage('configUpdated', (msg, view) => {
  if (!msg.success) { renderError(t('chat.configUpdateFailed')); return; }
  // Re-fetch config from server so UI reflects what was actually saved
  sendWs({type: 'getConfig'});
});

// --- Model selection (input-bar picker) ---
// modelOptions now just feeds the input-bar picker's "add model" list; the
// /model slash command and the bubble chooser have been removed.
onMessage('modelOptions', (msg, view) => {
  const models = msg.models || [];
  state.allModelRefs = models.map(m => m.ref).filter(Boolean);
});

onMessage('sessionModelSet', (msg, view) => {
});

// --- Runtime model change (fallback kicked in) ---
onMessage('modelChanged', (msg, view) => {
  if (msg.newModel) {
    state.currentModel = msg.newModel;
    // Record the ACTUAL model on the session so the header model label /
    // tooltip switch from the configured to the used model immediately —
    // previously this event only set the global currentModel (unread by any
    // UI), so a GLM→deepseek fallback left the display showing the old model.
    const sid = msg.sessionId || state.activeSessionId;
    if (sid) {
      const prev = state.sessionModelInfo[sid] || {};
      state.sessionModelInfo[sid] = { ...prev, model: msg.newModel };
      try { localStorage.setItem(LS_MODEL_INFO_KEY, JSON.stringify(state.sessionModelInfo)); } catch(e) {}
      updateHeaderModelInfo();
    }
  }
});

// --- Retry / fallback status ---
onMessage('retryStatus', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (view) renderRetryStatus(msg.message);
});

// --- Injected user message (task P+Q: Mail/Delegate/SubTask/Skill/Flow injections
// and ExternalEvent result notifications). Backend emits {type:"user",
// injected:true, source, text, sessionId}. Render as a light-blue bubble so
// tool-originated prompts are visible and distinct from the user's own input.
onMessage('user', (msg, view) => {
  if (!msg.injected) return; // non-injected user events are not emitted; guard anyway
  const sid = msg.sessionId;
  if (!sid) return;
  state.turnExpecting[sid] = true;
  // Sub-agent events (nodeSessionId present — delegate-/subtask-/team-) belong
  // to the sub-agent's own session. DelegateTool.routeWsSend stamps the PARENT
  // sessionId for display routing, so caching under sid would pollute the
  // parent's localStorage history — the bubble would reappear in the parent
  // window on restore ("outgoing Delegate prompt shows as blue bubble").
  // Sub-agent streams restore from their own backend history instead.
  if (!msg.nodeSessionId) {
    saveMsg({ type: 'user', text: msg.text, injected: true, source: msg.source || null, eventType: msg.eventType || null, sender: msg.sender || null, senderTeam: msg.senderTeam || null, delivery: msg.delivery || null }, sid);
  }
  if (sid === state.activeSessionId && view) {
    renderInjectedBubble(msg.text, msg.source, msg.timestamp, msg.eventType, msg.sender, msg.senderTeam, msg.delivery);
    smartScroll();
  }
});

// --- Bridge user message (e.g. from external platform) ---
onMessage('bridgeUser', (msg, view) => {
  const sid = msg.sessionId;
  if (!sid) return;
  state.turnExpecting[sid] = true;
  saveMsg({type: 'user', text: msg.text}, sid);
  if (sid === state.activeSessionId) {
    renderUserBubble(msg.text, []);
    smartScroll();
  }
});

// --- Message recalled (backend confirms deletion) ---
// The optimistic DOM removal already happened in recallUserMessage() in utils.js.
// If the backend says it failed (e.g. AI already replied), we just reload the
// history to restore the message. If success, nothing more to do.
onMessage('messageRecalled', (msg) => {
  if (!msg.success) {
    // Recall failed — reload history to restore the removed row
    const sid = msg.sessionId;
    if (sid && sid === state.activeSessionId) {
      import('./persistence.js').then(({ restoreFromStorage }) => {
        if (activeView?.dom?.chat) {
          cleanupCardIframes(activeView.dom.chat);
          activeView.dom.chat.innerHTML = '';
        }
        restoreFromStorage({ busyTail: state.busySessionIds.has(sid) });
      });
    }
  }
});

// --- Session busy state (backend authority) ---
onMessage('sessionBusy', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (msg.busy) {
    setBusy(msg.sessionId);
    // Server explicitly set busy — mark as expecting a turn (e.g. pendingEvents round)
    if (sid) state.turnExpecting[sid] = true;
  } else {
    clearBusy(msg.sessionId);
    // Defensive: if the 'done' event was lost but backend sent busy=false,
    // finish any active streaming bubble so the cursor disappears and the
    // duration badge is rendered.
    delete state.sessionPendingTools[sid];
    delete state.sessionPendingAiMessages[sid];
    delete state.pendingRestore[sid];
    if (state.sessionTexts[sid]) delete state.sessionTexts[sid];
  }
  // DOM-related cleanup only for active session.
  // Guard: if we received a textDelta/thinkingDelta very recently, the stream
  // is still active — a stale sessionBusy(false) (e.g. from idle-entry delay,
  // DeathWatcher broadcast, or crash-recovery race) must NOT finalize the bubble.
  // Only do the defensive cleanup if the stream has been silent for 15s+,
  // indicating the 'done' event was truly lost.
  if (view && !msg.busy && activeView.stream.currentAiBubble) {
    const sinceActivity = Date.now() - (state.lastStreamActivity || 0);
    if (sinceActivity < 15000) {
      console.warn('[sessionBusy(false)] Ignoring stale sessionBusy(false) during active streaming'
        + ` (${sinceActivity}ms since last delta)`);
    } else {
      const durationMs = consumeTurnDuration(sid);
      // IMPORTANT: do NOT use sessionModelInfo[sid]?.model here.
      // If the user switched models (e.g. deepseek → glm), sessionModelInfo still
      // holds the PREVIOUS turn's model. When sessionBusy(false) wins the race
      // against 'done' (backend acknowledges this can happen), using the cache
      // renders the wrong model name on the duration badge. Passing null means
      // the badge shows the phrase without a model — the correct model comes from
      // history when the user switches sessions.
      const data = finishAi(durationMs, null);
      if (data) saveMsg(data, sid);
      Object.keys(activeView.stream.agentBubbles).forEach(id => finishAgent(id));
      activeView.stream.agentBubbles = {};
      activeView.stream.activeAgentId = null;
      clearStatus();
    }
  }
  // ⑩ busy start (woken mid-window) drops the frozen visual; busy end (idle
  // again inside the window) restores it.
  applyLocalFreeze();
});

// --- Task list ---
onMessage('taskListUpdate', (msg, view) => {
  resetStreamTimeout(msg.sessionId);
  if (msg.sessionId) state.sessionTasks[msg.sessionId] = msg.tasks;
  if (view) {
    renderTaskList(msg.tasks, undefined, msg.sessionId);
  }
});

// --- Team task list (裁定②: unified into the Nebula session task panel) ---
// Frame shape {type, team, tasks} — NO sessionId (TaskToolHelper.
// emitTeamTaskListUpdate); team tasks live in their own store directory and
// never enter state.sessionTasks. taskList.js merges them into the panel
// when the rendered session is a Nebula session (sessionShowsTeamTasks).
onMessage('teamTaskListUpdate', (msg) => {
  if (!msg.team) return;
  state.teamTasks[msg.team] = Array.isArray(msg.tasks) ? msg.tasks : [];
  const sid = state.activeSessionId;
  if (sid && sessionShowsTeamTasks(sid)) {
    renderTaskList(state.sessionTasks[sid] || [], undefined, sid);
  }
});

// --- Background task indicator in header ---
let _bgTimer = null;

function formatDuration(ms) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

function renderBgDropdown() {
  const tasks = state.sessionBgTasks[activeView?.sessionId] || [];
  const listEl = activeView.dom.bgDropdownListEl;
  const dropdown = activeView.dom.bgDropdownEl;
  if (!listEl || !dropdown) return;
  listEl.innerHTML = '';
  // Show running AND cancelling tasks (cancelling tasks stay visible until backend confirms)
  const visible = tasks.filter(t => t.status === 'running' || t.status === 'cancelling');
  if (visible.length === 0) {
    dropdown.classList.add('hidden');
    stopBgTimer();
    return;
  }
  const now = Date.now();
  visible.forEach(task => {
    const row = document.createElement('div');
    row.className = 'bg-task-row';
    if (task.status === 'cancelling') row.classList.add('bg-task-cancelling');
    const info = document.createElement('div');
    info.className = 'bg-task-info';
    const desc = document.createElement('span');
    desc.className = 'bg-task-desc';
    desc.textContent = task.description || task.taskId;
    const meta = document.createElement('div');
    meta.className = 'bg-task-meta';
    const idSpan = document.createElement('span');
    idSpan.className = 'bg-task-id';
    idSpan.textContent = task.taskId;
    const durationSpan = document.createElement('span');
    durationSpan.className = 'bg-task-duration';
    durationSpan.dataset.taskId = task.taskId;
    if (task.startedAt) durationSpan.textContent = formatDuration(now - task.startedAt);

    // Heartbeat status indicator (skip for cancelling tasks — it'll be gone soon)
    const hb = task.heartbeat;
    let statusDot = null;
    let linesSpan = null;
    if (hb && task.status !== 'cancelling') {
      const idleClass = hb.idleMs > 600000 ? 'bg-status-stuck' : (hb.idleMs > 120000 ? 'bg-status-idle' : 'bg-status-active');
      statusDot = document.createElement('span');
      statusDot.className = `bg-task-status ${idleClass}`;
      statusDot.title = hb.alive ? (hb.idleMs > 600000 ? t('bg.stuck') : t('bg.running')) : t('bg.ended');
      linesSpan = document.createElement('span');
      linesSpan.className = 'bg-task-lines';
      linesSpan.textContent = t('bg.lines', { count: hb.outputLines });
    }

    meta.appendChild(idSpan);
    if (statusDot) meta.appendChild(statusDot);
    meta.appendChild(durationSpan);
    if (linesSpan) meta.appendChild(linesSpan);
    info.appendChild(desc);
    info.appendChild(meta);
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'bg-task-cancel';
    cancelBtn.textContent = t('bg.cancel');
    cancelBtn.onclick = (e) => {
      e.stopPropagation();
      // Immediate visual feedback — optimistically show cancelling state
      cancelBtn.disabled = true;
      cancelBtn.classList.add('cancelling');
      cancelBtn.textContent = task.status === 'cancelling' ? t('bg.cancelling') : '...';
      task.status = 'cancelling';
      sendWs({ type: 'cancelBackgroundJob', sessionId: activeView?.sessionId, jobId: task.taskId });
    };
    // If already cancelling, show the cancelling state
    if (task.status === 'cancelling') {
      cancelBtn.disabled = true;
      cancelBtn.classList.add('cancelling');
      cancelBtn.textContent = t('bg.cancelling');
    }
    row.appendChild(info);
    row.appendChild(cancelBtn);
    listEl.appendChild(row);
  });
}

function startBgTimer() {
  if (_bgTimer) return;
  const v = activeView; // capture at start time — interval fires later when activeView may have drifted
  if (!v) return;
  _bgTimer = setInterval(() => {
    const dropdown = v.dom.bgDropdownEl;
    if (!dropdown || dropdown.classList.contains('hidden')) { stopBgTimer(); return; }
    const tasks = state.sessionBgTasks[v.sessionId] || [];
    const now = Date.now();
    dropdown.querySelectorAll('.bg-task-duration').forEach(el => {
      const t = tasks.find(t => t.taskId === el.dataset.taskId);
      if (t && t.startedAt) el.textContent = formatDuration(now - t.startedAt);
    });
  }, 1000);
}

function stopBgTimer() {
  if (_bgTimer) { clearInterval(_bgTimer); _bgTimer = null; }
}

function updateBgTasksUI() {
  // Background tasks only — running flows are tracked separately on the
  // flow canvas (getRunningFlows), not in this indicator.
  const tasks = state.sessionBgTasks[activeView?.sessionId] || [];
  const now = Date.now();
  const active = tasks.filter(task =>
    task.status === 'running' || task.status === 'cancelling' ||
    (task.finishedAt && (now - task.finishedAt < 3000))
  );
  const totalCount = active.length;
  const el = activeView.dom.bgIndicatorEl;
  const countEl = activeView.dom.bgCountEl;
  const dropdown = activeView.dom.bgDropdownEl;
  if (!el || !countEl) return;
  if (totalCount > 0) {
    el.classList.remove('hidden');
    countEl.textContent = totalCount;
  } else {
    el.classList.add('hidden');
    if (dropdown) dropdown.classList.add('hidden');
    stopBgTimer();
  }
  if (dropdown && !dropdown.classList.contains('hidden')) {
    renderBgDropdown();
    startBgTimer();
  }
}
state.updateBgTasksUI = updateBgTasksUI;

// Toggle dropdown on indicator click — register for ALL views
Object.values(chatViews).forEach(v => {
  const indicator = v.dom.bgIndicatorEl;
  const dropdown = v.dom.bgDropdownEl;
  if (!indicator || !dropdown) return;
  indicator.addEventListener('click', (e) => {
    e.stopPropagation();
    setActiveView(v);
    if (dropdown.classList.contains('hidden')) {
      renderBgDropdown();
      dropdown.classList.remove('hidden');
      startBgTimer();
    } else {
      dropdown.classList.add('hidden');
      stopBgTimer();
    }
  });
});

// Close dropdown when clicking outside — check all views' dropdowns
document.addEventListener('click', (e) => {
  Object.values(chatViews).forEach(v => {
    const dropdown = v.dom.bgDropdownEl;
    const indicator = v.dom.bgIndicatorEl;
    if (dropdown && !dropdown.contains(e.target) && indicator && !indicator.contains(e.target)) {
      dropdown.classList.add('hidden');
    }
  });
  stopBgTimer();
});

onMessage('backgroundTaskUpdate', (msg, view) => {
  // A background-task event emitted by a background (sub)agent carries the
  // agent's OWN sessionId (delegate-/subtask- prefix) in msg.sessionId, not
  // the top-level session the user is viewing — route it to the owning root
  // bucket so it's counted and shown in that window (bug fix 2026-08-25).
  const sid = msg.rootSessionId || bgTaskRootFor(msg.sessionId) || msg.sessionId;
  if (!sid) return;
  if (!state.sessionBgTasks[sid]) state.sessionBgTasks[sid] = [];
  const tasks = state.sessionBgTasks[sid];
  const idx = tasks.findIndex(t => t.taskId === msg.taskId);
  if (idx >= 0) {
    tasks[idx].status = msg.status;
    if (msg.description && !tasks[idx].description) tasks[idx].description = msg.description;
    if (msg.status === 'completed' || msg.status === 'failed') {
      tasks[idx].finishedAt = Date.now();
    }
    if (msg.heartbeat) tasks[idx].heartbeat = msg.heartbeat;
  } else {
    tasks.push({
      taskId: msg.taskId,
      description: msg.description,
      status: msg.status,
      startedAt: msg.startedAt || Date.now(),
      heartbeat: msg.heartbeat || null,
      finishedAt: (msg.status === 'completed' || msg.status === 'failed') ? Date.now() : undefined
    });
  }
  // Remove completed/failed tasks after a brief delay so user sees the count update
  if (msg.status === 'completed' || msg.status === 'failed') {
    const taskId = msg.taskId;
    const v = activeView; // capture before setTimeout
    setTimeout(() => {
      const existing = state.sessionBgTasks[sid];
      if (existing) {
        state.sessionBgTasks[sid] = existing.filter(t => t.taskId !== taskId);
        if (v && v.mounted) {
          const saved = activeView;
          setActiveView(v);
          updateBgTasksUI();
          setActiveView(saved);
        }
      }
    }, 3000);
  }
  if (view) updateBgTasksUI();
});

// --- /ask command ---
onMessage('askTextDelta', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid && !state.turnStartTimes[sid]) state.turnStartTimes[sid] = Date.now();
  // Accumulate ask text for ALL sessions
  if (sid) {
    if (!state.sessionAskBuffers[sid]) state.sessionAskBuffers[sid] = { question: '', answer: '' };
    state.sessionAskBuffers[sid].answer = (state.sessionAskBuffers[sid].answer || '') + msg.delta;
  }
  if (view) appendAskAnswer(msg.delta);
});
onMessage('askDone', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  const durationMs = consumeTurnDuration(sid) || msg.durationMs;
  const buf = sid ? state.sessionAskBuffers[sid] : null;
  if (view) {
    // Prefer buf.answer (complete accumulated text across tool boundaries)
    // over askAnswerText (only the last bubble segment)
    const answer = (buf ? buf.answer : '') || activeView.stream.askAnswerText || '';
    const question = buf ? buf.question : '';
    finishAskAnswer(durationMs, msg.model);
    if (question || answer) {
      saveMsg({ type: 'ask', question, answer, durationMs, model: msg.model }, msg.sessionId);
    }
  } else if (buf && (buf.question || buf.answer)) {
    // Non-active session: save buffered ask to localStorage
    saveMsg({ type: 'ask', question: buf.question, answer: buf.answer, durationMs, model: msg.model }, sid);
  }
  if (sid) delete state.sessionAskBuffers[sid];
  // Clean up thinking buffer so the subsequent 'done' event doesn't save
  // ask-mode thinking as a separate AI message (stray thinking fragment).
  if (sid && state.sessionThinkingBuffers[sid]) delete state.sessionThinkingBuffers[sid];
  if (activeView?.stream?.currentThinkingBubble) finishThinking();
});
onMessage('askError', (msg, view) => {
  const sid = msg.sessionId || state.activeSessionId;
  if (sid) delete state.sessionAskBuffers[sid];
  if (view) renderAskError(msg.message);
});

// --- Skills ---
onMessage('skillList', (msg, view) => {
  state.skills = msg.skills || [];
  registerSkillCommands(state.skills);
});

onMessage('skillError', (msg, view) => {
  if (view) renderSystemBubble(msg.message || 'Skill error');
});

onMessage('skillDeleted', (msg, view) => {
  if (view) {
    if (msg.success) {
      renderSystemBubble(t('slash.skillDeleted').replace('{skill}', msg.name));
    } else {
      renderSystemBubble(t('slash.skillDeleteFailed').replace('{skill}', msg.name));
    }
  }
});


// --- Memory ---
onMessage('memoryData', (msg, view) => handleMemoryData(msg));
onMessage('memoryChanged', (msg, view) => handleMemoryChanged(msg));
onMessage('memorySaved', () => { /* saved confirmation, no action needed */ });
onMessage('memoryStatus', (msg, view) => showMemoryButton());

// --- Rules ---
onMessage('rulesData', (msg, view) => handleRulesData(msg));
onMessage('rulesSaved', (msg, view) => handleRulesSaved(msg));
onMessage('rulesDeleted', (msg, view) => handleRulesDeleted(msg));

// --- Browse Result (path picker) ---
onMessage('browseResult', (msg, view) => handleBrowseResult(msg));

// --- Update check ---
onMessage('updateCheckResult', (msg, view) => {
  const statusEl = document.getElementById('update-status');
  const actionEl = document.getElementById('update-action');
  if (!statusEl) return;
  if (msg.error) {
    statusEl.textContent = t('settings.updateError');
    return;
  }
  if (msg.hasUpdate) {
    statusEl.textContent = t('settings.updateAvailable', { version: msg.latestVersion });
    actionEl.style.display = 'block';
  } else {
    statusEl.textContent = t('settings.upToDate');
    actionEl.style.display = 'none';
  }
});

onMessage('updateStarted', () => {
  const statusEl = document.getElementById('update-status');
  if (statusEl) statusEl.textContent = t('settings.updating');
});

onMessage('updateCompleted', (msg, view) => {
  const btn = document.getElementById('btn-do-update');
  const statusEl = document.getElementById('update-status');
  if (btn) { btn.textContent = t('settings.checkUpdate'); btn.disabled = false; }
  if (statusEl) {
    if (msg.success) {
      statusEl.textContent = '✓ ' + t('settings.upToDate');
      document.getElementById('update-action').style.display = 'none';
    } else {
      statusEl.textContent = '✗ ' + (msg.error || t('settings.updateError'));
    }
  }
});


// ---------- 4. Cross-module wiring ----------
window.__showDeleteModal = showDeleteModal;
window.__showDeleteFolderModal = showDeleteFolderModal;

// Fork complete — auto-switch to the forked session
onMessage('forkComplete', (msg, view) => {
  renderSessionSidebar(state.sessions, msg.sessionId);
});

// ── Global ESC handler: close any visible modal/overlay/dropdown ──────
(function initGlobalEscHandler() {
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;

    // Full-screen overlay modals — click the overlay to trigger its close handler
    const overlays = [
      '#memory-overlay', '#rules-overlay',
      '#path-picker-overlay', '#modal-overlay', '#agent-overlay',
    ];
    for (const sel of overlays) {
      const el = document.querySelector(sel);
      if (el && getComputedStyle(el).display !== 'none') {
        el.click();
        e.preventDefault();
        e.stopPropagation();
        return;
      }
    }

    // Dropdown menus
    const bypassMenu = document.getElementById('bypass-menu');
    if (bypassMenu?.classList.contains('show')) {
      bypassMenu.classList.remove('show');
      e.preventDefault();
      return;
    }

    const bgAgentDropdown = document.getElementById('bgagent-dropdown');
    if (bgAgentDropdown && !bgAgentDropdown.classList.contains('hidden')) {
      bgAgentDropdown.classList.add('hidden');
      const bgAgentIndicator = document.getElementById('bgagent-indicator');
      if (bgAgentIndicator) bgAgentIndicator.setAttribute('aria-expanded', 'false');
      e.preventDefault();
      return;
    }

    const bg = document.getElementById('bg-dropdown');
    if (bg && !bg.classList.contains('hidden')) {
      bg.classList.add('hidden');
      e.preventDefault();
      return;
    }

    const reminder = document.getElementById('reminder-panel');
    if (reminder?.classList.contains('open')) {
      reminder.classList.remove('open');
      e.preventDefault();
      return;
    }
  }, true); // capture phase — intercept before other handlers
})();

// #396 Header adaptive layout (spec 52b19a64, frozen): measurement-driven
// priority hiding + center clamp — ANY width zero icon overlap; no flex-wrap;
// no "⋯" overflow menu (user 2026-08-25 裁定: 仅自动隐藏). The fixed set
// (sidebar-toggle / header-model-info / memory-btn / bg-indicator / bgagent-
// indicator / canvas-toggle-btn) is never hidden; non-fixed right buttons hide
// by priority P1→P5 (bypass > voice > search > reminder > daemon), session-name
// truncates first (ellipsis) then hides (P5).
(function initHeaderResizeObserver() {
  const header = document.getElementById('header');
  if (!header || !window.ResizeObserver) return;
  const HIDE = 'nb-header-hide';
  // Non-fixed right-cluster controls in hide-priority order (lowest first).
  const rightPrio = [
    () => document.getElementById('bypass-dropdown'),
    () => document.getElementById('voice-toggle-btn'),
    () => document.getElementById('search-btn'),
    () => document.getElementById('reminder-btn'),
    () => document.getElementById('daemon-btn'),
  ];
  const centerEl = () => /** @type {HTMLElement|null} */ (header.querySelector('.header-center'));
  const leftEl = () => /** @type {HTMLElement|null} */ (header.querySelector('.header-left'));
  const rightEl = () => /** @type {HTMLElement|null} */ (header.querySelector('.header-right'));
  let raf = null;

  function layout() {
    if (raf) cancelAnimationFrame(raf);
    raf = requestAnimationFrame(() => {
      raf = null;
      const center = centerEl();
      const session = document.getElementById('session-name');
      const mem = document.getElementById('memory-btn');
      if (!center || !session || !mem) return;
      // Reset to the widest reasonable state each pass, then hide by priority.
      rightPrio.forEach(fn => { const el = fn(); if (el) el.classList.remove(HIDE); });
      session.classList.remove(HIDE);
      const headerW = header.clientWidth;
      const memW = mem.offsetWidth || 28;
      const CS_GAP = 8;         // .header-center flex gap (session ↔ memory)
      const MIN_SESSION = 28;   // §6: session hides if it can't seat a meaningful chunk
      // Zero-overlap clamp for the absolute-centered .header-center (left:50%,
      // translateX(-50%)). A centered box of width c clears the left cluster iff
      // c ≤ 2·(centerX - leftCluster.right), and the right cluster iff
      // c ≤ 2·(rightCluster.left - centerX). We measure the real pixel rects so
      // header border+padding are accounted for (§8 A2 zero-overlap guarantee).
      const measure = () => {
        const hr = header.getBoundingClientRect();
        const cx = hr.left + hr.width / 2;            // center of the sticky box
        const leftR = leftEl() ? leftEl().getBoundingClientRect() : { right: cx };
        const rightR = rightEl() ? rightEl().getBoundingClientRect() : { left: cx };
        const leftRoom = Math.max(0, 2 * (cx - leftR.right));
        const rightRoom = Math.max(0, 2 * (rightR.left - cx));
        const safe = Math.min(leftRoom, rightRoom);
        const leftW = leftEl() ? leftEl().offsetWidth : 0;
        const rightW = rightEl() ? rightEl().offsetWidth : 0;
        return { safe, leftW, rightW };
      };
      // Greedy: hide non-fixed right buttons P1→P5 (bypass first) until the
      // centered center box can seat the memory button plus a meaningful
      // MIN_SESSION chunk without overlap AND the right cluster no longer
      // collides with the left. §4.3 right-cluster fill; session hides last.
      for (let i = 0; i <= rightPrio.length; i++) {
        const { safe, leftW, rightW } = measure();
        const need = memW + CS_GAP + MIN_SESSION;
        if (safe >= need && leftW + rightW <= headerW) break;
        if (i < rightPrio.length) {
          const el = rightPrio[i]();
          if (el) { el.classList.add(HIDE); continue; }       // hide this priority
        }
        session.classList.add(HIDE);                          // P5: hide session
        break;
      }
      // Apply the clamp after the final visibility state. session-name ellipsizes
      // inside the center; if safe < memW (pathological <240px) we keep the mem
      // button floor and accept the §8 A10 theoretical clip rather than overlap.
      const { safe } = measure();
      center.style.maxWidth = Math.max(memW, safe) + 'px';
      center.style.minWidth = memW + 'px';
    });
  }

  const ro = new ResizeObserver(layout);
  if (header) ro.observe(header);
  const mainEl = document.getElementById('main');
  if (mainEl) ro.observe(mainEl);
  // Observe the fixed-set, content-driven elements too. When a dynamic fixed
  // element appears (context-ring / memory / bg-indicator / bgagent-indicator)
  // it grows its cluster WITHOUT resizing #header, so ResizeObserver on the
  // header alone would miss the reflow and leave a stale (too-loose) clamp
  // that overlaps (§8 A2). These elements are never toggled by layout() so
  // observing them cannot cause a feedback loop.
  ['sidebar-toggle', 'header-model-info', 'memory-btn',
    'bg-indicator', 'bgagent-indicator', 'canvas-toggle-btn']
    .forEach(id => { const el = document.getElementById(id); if (el) ro.observe(el); });
  layout();
})();

// ---------- 5. Initialize UI modules ----------
applyLocaleToHtml(); // Apply locale to static HTML elements
initNavTabs();
initModals();
initRulesModal();
initPathPicker();
initInput(chatViews.primary);
initMicOrb(); // Mic bubble orb v8.2.2 (#419) — binds #voice-btn canvas/css-orb
initGlobalFileDrop(); // #303 — document-level drag & drop onto input bars

// Keep the last chat message visible above the floating #input-area.
// #input-area (position:absolute; bottom:0) overlays #chat and has variable
// height (multi-line input, message queue, voice panel). All scroll code uses
// scrollTop = scrollHeight, so we grow #chat's padding-bottom to match the
// input area's height — then scrolling to the bottom lands the last message
// above the input bar instead of behind it.
(() => {
  const inputArea = document.getElementById('input-area');
  const chat = document.getElementById('chat');
  if (!inputArea || !chat || !window.ResizeObserver) return;
  const updateChatPadding = () => {
    // offsetHeight covers all in-flow children (queue-bar + input-bar + the
    // area's own 10px bottom padding). Absolutely-positioned children
    // (voice-overlay, slash-dropdown) are excluded — they overlay the chat
    // transiently above the bar, so they must not inflate the padding.
    const inputHeight = inputArea.offsetHeight;
    // +2px: minimal breathing room so the last message sits flush above the
    // input bar without touching the glass edge.
    chat.style.setProperty('padding-bottom', `${inputHeight + 2}px`, 'important');
    // Only re-scroll if the user is already near the bottom — don't yank
    // them away from history they're reading.
    const nearBottom = chat.scrollHeight - chat.scrollTop - chat.clientHeight < 100;
    if (nearBottom) chat.scrollTop = chat.scrollHeight;
  };
  const ro = new ResizeObserver(updateChatPadding);
  ro.observe(inputArea);
  updateChatPadding();
})();
initMemory();
initExplorer();
initCanvas();
initColResizers();
// #27: Project 标签页按钮（取代 Team/Flow 主导位置）——打开发布 projects 标签页
registerCanvasPanelButton('projects', 'projects-btn', () => openProjectsTab());

// Remove UI initialization lock — all layout setup is done.
// Double-rAF ensures the browser has painted at least one frame with
// the final layout before re-enabling transitions.
requestAnimationFrame(() => {
  requestAnimationFrame(() => {
    document.documentElement.classList.remove('ui-init');
  });
});

initActivityBar();
document.getElementById('canvas-toggle-btn')?.addEventListener('click', () => {
  // Toggle Canvas open/close — closing does NOT clear tabs.
  if (document.body.classList.contains('canvas-open')) {
    closeCanvas();
  } else {
    openCanvas();
  }
});
// Teams/Flows legacy panel buttons are gone (2026-09-05 旧 UI 退役) — the
// plugins (agents-btn) and projects (projects-btn) entries are bound by
// canvas.js registerCanvasPanelButton (plugins.js / main.js).
// Restore queued messages from localStorage (survives browser refresh)
restoreQueue();
// Restore Canvas tabs (server persisted, falls back to localStorage).
// 2026-09-04 件 A/件 B：空白启动 fallback 由 openTeams 改道 openPlugins——
// Team/Flow 旧入口已隐藏封存，首次启动不应把用户带进被封存面板；「插件」页
// 是智能体入口的继任默认页。restoreTabs 异步语义不变（F1, 2026-08-30）。
restoreTabs().then((restored) => {
  if (!restored) openPlugins();
});
// Auto-restore is triggered from sessionList handler (needs activeSessionId)
initScheduledTask();
initDaemons();
initChatSearch();
initUsageDashboard();
initNeblink();
initDropbox();
initContacts();
initMessages();
planMode.init();

// Preload Monaco Editor during idle time so first file open is instant.
// Monaco (~2MB from CDN) is the main cause of first-open lag.
const _idleCb = window.requestIdleCallback || ((fn) => setTimeout(fn, 2000));
_idleCb(() => import('./monacoEditor.js').then(({ preloadMonaco }) => preloadMonaco().catch(() => {})));

// ---------- Plan mode event handlers ----------
onMessage('planStart', (msg, view) => planMode.onPlanStart(msg, view));
onMessage('planReady', (msg, view) => planMode.onPlanReady(msg, view));
onMessage('planEnd', (msg, view) => planMode.onPlanEnd(msg, view));
onMessage('_planAgent', (msg) => planMode.onPlanAgentEvent(msg));

// ---------- Safety mode dropdown ----------
(function initSafetyToggle() {
  const TITLES = {
    'confirm-edits': '安全模式：确认编辑 (Write/Edit/Bash 需确认)',
    'auto-edits': '安全模式：编辑放行 (仅 Bash 需确认)',
    'auto-all': '安全模式：全部放行 (无需确认)',
  };

  state.updateSafetyToggle = function(view) {
    const v = view || activeView;
    if (!v || !v.sessionId) return;
    const mode = state.safetyModes[v.sessionId] || 'confirm-edits';
    const btn = document.getElementById('bypass-toggle');
    if (btn) {
      btn.setAttribute('data-mode', mode);
      btn.title = TITLES[mode] || TITLES['confirm-edits'];
    }
    // Highlight active option in dropdown
    document.querySelectorAll('#bypass-menu button').forEach(b => {
      b.classList.toggle('active', b.dataset.mode === mode);
    });
  };

  state.updateBypassToggle = state.updateSafetyToggle;

  const btn = document.getElementById('bypass-toggle');
  const menu = document.getElementById('bypass-menu');
  if (btn && menu) {
    const v = chatViews.primary;
    // Toggle dropdown on icon click
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      setActiveView(v);
      menu.classList.toggle('show');
    });
    // Select mode from dropdown
    menu.querySelectorAll('button').forEach(item => {
      item.addEventListener('click', (e) => {
        e.stopPropagation();
        const mode = item.dataset.mode;
        if (!v.sessionId) return;
        state.safetyModes[v.sessionId] = mode;
        if (mode === 'auto-all') {
          state.bypassSessions.add(v.sessionId);
        } else {
          state.bypassSessions.delete(v.sessionId);
        }
        state.updateSafetyToggle(v);
        sendWs({ type: 'setSafetyMode', sessionId: v.sessionId, safetyMode: mode });
        menu.classList.remove('show');
      });
    });
    // Close dropdown on outside click
    document.addEventListener('mousedown', (e) => {
      if (!menu.contains(e.target) && e.target !== btn) {
        menu.classList.remove('show');
      }
    }, true);
  }
})();

// Sidebar collapse toggle — visibility is owned by the activityBar panel
// registry (single state source); both entries below call the same API.
(function initSidebarToggle() {
  const btn = document.getElementById('sidebar-toggle');
  if (!btn) return;
  btn.addEventListener('click', () => toggleSideBar());
  // Keyboard shortcut: Cmd/Ctrl+B
  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key === 'b') {
      const tag = document.activeElement?.tagName;
      if (tag === 'TEXTAREA' || tag === 'INPUT') return;
      e.preventDefault();
      toggleSideBar();
    }
  });
})();

// ---------- Scrollbar: fixed slim width ----------
// Previously had an auto-slim mechanism that toggled scrollbar width between
// 6px and 10px on scroll/idle. This caused bubbles to re-flow (jump) because
// the content area width changed by 4px each time. Fixed width eliminates this.

// Re-apply locale when language changes
window.addEventListener('locale-changed', () => {
  applyLocaleToHtml();
  // Sub-agents panel: status labels/header/empty state are t()-driven
  renderBgAgentDropdown();
  // Force session sidebar rebuild by invalidating fingerprint cache
  const sl = state.dom.sessionList;
  if (sl) sl._lastFingerprint = null;
  if (state.sessions.length > 0) {
    renderSessionSidebar(state.sessions, state.activeSessionId);
  }
  // Re-render agent list to update localized labels
  if (state.agentsData.length > 0) {
    renderAgentList();
  }
  // Update input placeholders (may have been overwritten by skill/ask mode)
  if (chatViews.primary?.dom?.input) {
    const v = chatViews.primary;
    setActiveView(v);
    if (!v.skillMode && !v.stream.askMode) {
      v.dom.input.placeholder = t('input.placeholder');
    }
  }
});
// New Folder button
document.getElementById('new-folder-btn')?.addEventListener('click', () => createNewFolder(getCurrentFolderId()));


// ---------- Reconnect: refresh active session history ----------
// After OS sleep/wake or network drop, the agent may have produced output
// while the frontend was disconnected. On reconnect, re-fetch the active
// session's history so the user sees the latest state.
onReconnect(() => {
  const sid = state.activeSessionId;
  if (sid) {
    const view = findViewBySessionId(sid);
    if (view) {
      view.pagination.pendingInitialLoad = true;
      sendWs({ type: 'getHistory', sessionId: sid, limit: 50 });
    }
    // Re-fetch explorer tree — the initial load may have been dropped
    // if WS wasn't open when nebflow-session-change fired.
    refreshExplorer(sid);
    // Re-fetch task list for the same reason
    sendWs({ type: 'getTaskList', sessionId: sid });
  }
  // Sync background task state — completion events may have been missed
  sendWs({ type: 'getActiveBgTasks' });
  // Sync background sub-agent state — agentStart events are not replayed
  // after a page refresh, so the indicator count would be lost without this
  sendWs({ type: 'getActiveAgents' });
  // Re-fetch session list — sessions may have been created/removed during disconnect
  sendWs({ type: 'listSessions' });
});

// ---------- Reconnect: sync background tasks ----------
// Backend responds with active tasks grouped by sessionId.
// We remove any locally-tracked tasks that are no longer active on the backend
// (they completed during the disconnect), and keep tasks the backend confirms.
onMessage('activeBgTasks', (msg) => {
  const backendTasks = msg.tasks || {};
  // Backend groups background tasks by the executing agent's sessionId, which
  // for a background (sub)agent is a delegate-/subtask- session, not the
  // root session the user is viewing. Remap to root buckets before
  // reconciling so sub-agent background tasks are counted under the owning
  // window (mirrors bgTaskRootFor on the live backgroundTaskUpdate path).
  const byRoot = {};
  for (const [sid, tasks] of Object.entries(backendTasks)) {
    const root = bgTaskRootFor(sid);
    if (!byRoot[root]) byRoot[root] = [];
    byRoot[root].push(...tasks);
  }
  // Remove locally-tracked tasks that the backend no longer knows about
  for (const sid of Object.keys(state.sessionBgTasks)) {
    const backendSessionTasks = byRoot[sid] || [];
    const backendIds = new Set(backendSessionTasks.map(t => t.taskId));
    const before = state.sessionBgTasks[sid].length;
    state.sessionBgTasks[sid] = state.sessionBgTasks[sid].filter(t => backendIds.has(t.taskId));
    // If we removed tasks, also clean up finishedAt entries
    if (state.sessionBgTasks[sid].length < before) {
      const removed = before - state.sessionBgTasks[sid].length;
      // Silently clean — no UI update needed since these were already "running"
      // indicators that will disappear on next render
    }
    if (state.sessionBgTasks[sid].length === 0) {
      delete state.sessionBgTasks[sid];
    }
  }
  // Also clean stale sub-agent indicators — any session that has sub-agents
  // tracked locally but no longer has an active agent on the backend
  // can't be reliably detected here (sub-agents use actor system, not BgTaskRegistry).
  // The agentDone fix (global sessionBgAgents) already handles missed events.
  // Refresh the UI for the active view
  if (activeView) updateBgTasksUI();
});

// ---------- Reconnect: sync background sub-agents ----------
// Backend responds to getActiveAgents with currently-running sub-agents:
//   { type: "activeAgents", agents: [{ sessionId, agentId, agentName, rootSessionId, kind }] }
// Rebuild sessionBgAgents from backend truth — real-time agentStart events
// are not replayed after a page refresh (F5), and anything not listed has
// finished (the backend registry no longer tracks it).
onMessage('activeAgents', (msg) => {
  const agents = msg.agents || [];
  // Rebuild sessionBgAgents from backend truth
  state.sessionBgAgents = {};
  const activeRootSessions = new Set();
  for (const a of agents) {
    const sid = a.rootSessionId || a.sessionId;
    if (!sid || !a.agentId) continue;
    // #28 可观测接线: 不再过滤 node-* —— 节点/分发器会话与 Delegate/SubTask
    // 同一快照重建路径（旧 ghost-row 根因已后端修复: 事件现携带 rootSessionId,
    // 终态由 done handler 清理, 快照只报运行中的 registry 条目）。
    if (!state.sessionBgAgents[sid]) state.sessionBgAgents[sid] = {};
    state.sessionBgAgents[sid][a.agentId] = {
      name: a.agentName || a.agentId,
      task: a.task || '',
      sessionId: a.sessionId || '',
      // Management panel (2026-08-22): kind drives the permission matrix
      // (Delegate/SubTask/Ephemeral operable, Team/Flow read-only); startedAt
      // powers uptime restore; status ("Error(msg)" form) powers the failed
      // state and retryCount the retries chip after a page refresh (backend
      // fields landed @179a009e).
      kind: a.kind || '',
      startedAt: a.startedAt || null,
      status: a.status || '',
      retryCount: typeof a.retryCount === 'number' ? a.retryCount : 0,
      currentTool: null,
      done: false,
    };
    activeRootSessions.add(sid);
  }
  // Sync busySessionIds: clear sessions that are no longer active on the backend.
  // A 'done' event missed during WS disconnect leaves the session stuck as busy
  // forever — the agent panel shows "running" even though the agent finished.
  // The active primary session is exempt (its busy state is managed by the
  // streaming pipeline: setBusy on textDelta, clearBusy on done/error).
  for (const sid of [...state.busySessionIds]) {
    if (sid !== state.activeSessionId && !activeRootSessions.has(sid)) {
      clearBusy(sid);
    }
  }
  if (activeView) updateBgAgentIndicator();
  // Recompute agent nav states — the visual indicator depends on busySessionIds
  computeAgentStates();
});

// Scroll listener (primary window)
const _primChat = chatViews.primary.dom.chat;
_primChat.addEventListener('scroll', () => {
  const pv = chatViews.primary;
  pv.stream.scrollSnapped = _primChat.scrollTop + _primChat.clientHeight >= _primChat.scrollHeight - 40;
  // Scroll-to-top: load older messages
  if (_primChat.scrollTop < 100 && pv?.pagination?.hasMore && !pv?.pagination?.loading && pv?.pagination?.offset > 0) {
    pv.pagination.loading = true;
    setActiveView(pv);
    showHistoryLoader();
    sendWs({ type: 'getHistory', sessionId: state.activeSessionId, limit: 50, beforeIndex: pv.pagination.offset });
  }
}, { passive: true });

// ---------- 6. Expose global Nebflow API for plugins ----------
// Theme tokens extracted from CSS custom properties — agents can read these for consistency.
const _themeCache = {};
function getThemeTokens() {
  if (Object.keys(_themeCache).length) return _themeCache;
  const s = getComputedStyle(document.documentElement);
  const pick = (prop) => s.getPropertyValue(prop).trim();
  _themeCache.primary = pick('--color-primary') || '#07c160';
  _themeCache.primaryHover = pick('--color-primary-hover') || '#06ad56';
  _themeCache.error = pick('--color-error') || '#f44336';
  _themeCache.success = pick('--color-success') || '#4caf50';
  _themeCache.bubbleAi = pick('--color-bubble-ai') || '#fff';
  _themeCache.text = pick('--color-text') || '#000';
  _themeCache.textMuted = pick('--color-text-muted') || '#888';
  _themeCache.border = pick('--color-border') || '#ddd';
  return _themeCache;
}

window.Nebflow = {
  // --- Card rendering ---
  escapeHtml,
  getThemeTokens,
  /** Send a message as if the user typed it. */
  injectUserMessage,
  /** Get read-only state snapshot. */
  get state() { return state; },
  /** Currently active session ID. */
  get activeSessionId() { return state.activeSessionId; },
  /** Currently selected agent name. */
  get selectedAgent() { return state.selectedAgent; },
  /** Send a raw WebSocket message. */
  sendWs,
  /** Smart-scroll the chat to the bottom. */
  smartScroll,
};

// ---------- 8. Start ----------
emergencyCacheCleanup(); // Purge bloated localStorage cache before any writes
connect();
chatViews.primary.dom.input.focus();

// Cloud STT — no model preloading needed.
