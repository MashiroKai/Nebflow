// bgAgentPopup.js — Live message viewer for background sub-agents.
//
// When an agent uses the Delegate/SubTask tool, sub-agent events arrive with
// nodeSessionId = "delegate-<agentName>-<uuid>" / "subtask-<uuid>" (backend protocol). This
// module intercepts those events in ws.js (via setBgAgentStepInterceptor)
// and renders them into a popup ChatView — the same pattern as flowAgentPopup.js.
//
// The user opens the popup by clicking the bg-agent dropdown entry.

import { ChatView, setActiveView, activeView, chatViews } from './chatView.js';
import { sendWs, onMessage, setBgAgentStepInterceptor } from './ws.js';
import { restoreFromBackendHistory } from './persistence.js';
import { isBgAgentId, truncateMiddle, updateScrollSnapped, shouldFollowBottom, initScrollFollow } from './utils.js';
import state from './state.js';
import { key } from './branding.js';
import { t } from './i18n.js';
import { buildManageBar, bindManageActions, syncManageControls, onCancelResult, isFailedSnapshotStatus } from './managePanel.js';
import { isErrorReason, errorTileText, errorIcon } from './errorRecovery.js';
import { cleanupCardIframes } from './cardRegistry.js';

// ── Per-sub-agent state ────────────────────────────────────
// nodeSessionId → { view: ChatView, container: div, meta: {}, historyLoaded: bool }
const stepViews = new Map();
let currentStepId = null;
let popupOverlay = null;
let popupResizeObs = null;

// ── CSS (shared with flowAgentPopup — same modal style) ───
// The CSS is injected by flowAgentPopup.js at module load time.
// Both modules are always loaded together since they're imported by other
// modules, so the shared class names are always available.

// ── Hidden container for background rendering ─────────────
let hiddenRoot = null;
function getHiddenRoot() {
  if (!hiddenRoot) {
    hiddenRoot = document.createElement('div');
    hiddenRoot.className = 'flow-agent-hidden';
    document.body.appendChild(hiddenRoot);
  }
  return hiddenRoot;
}

// ── Ensure a ChatView exists for a nodeSessionId ──────────

function ensureStepView(sessionId) {
  if (stepViews.has(sessionId)) return stepViews.get(sessionId);

  const container = document.createElement('div');
  container.className = 'flow-agent-chat';
  getHiddenRoot().appendChild(container);

  const fakeDom = {
    chat: container,
    input: null, sendBtn: null, stopBtn: null, attachBtn: null,
    attPreview: null, slashDropdown: null, queueBar: null,
    voiceBtn: null, voiceOverlay: null, voiceText: null,
    headerModelInfoEl: null, bgIndicatorEl: null, bgCountEl: null,
    bgDropdownEl: null, bgDropdownListEl: null,
    bgagentIndicatorEl: null, bgagentDropdownEl: null, bgagentDropdownListEl: null,
    sessionNameEl: null,
  };
  const view = new ChatView('bgagent-' + sessionId, fakeDom);
  view.mounted = true;
  view.sessionId = sessionId;
  // Hidden until the popup opens — ws.js gates DOM rendering while false.
  view.visible = false;
  // Register in the global view registry so findViewBySessionId() can route
  // live events here even while hidden (streamDispatchView then marks
  // dirtyWhileHidden → reopen forces a history refresh).
  chatViews[view.id] = view;

  const entry = { view, container, meta: { agentName: '', task: '', status: '' }, historyLoaded: false };
  stepViews.set(sessionId, entry);
  enforceStepViewCap();
  return entry;
}

function resetCardWidths(container) {
  const wraps = container.querySelectorAll('.html-card-wrap');
  wraps.forEach(w => { w.style.width = ''; });
}

// ── Open popup ────────────────────────────────────────────

export function openStepPopup(nodeSessionId, agentName, taskDescription) {
  closeStepPopup();
  currentStepId = nodeSessionId;

  const entry = ensureStepView(currentStepId);
  entry.view.visible = true;
  // Permission matrix: bg popups are Delegate/SubTask/Ephemeral (operable);
  // snapshot kind from activeAgents refines when present (backend gap: kind
  // arrives via sessionBgAgents once main.js stores it).
  entry.meta.kind = entry.meta.kind || 'Delegate';
  for (const byRoot of Object.values(state.sessionBgAgents || {})) {
    const row = byRoot && byRoot[nodeSessionId];
    if (row) {
      if (row.kind) entry.meta.kind = row.kind;
      if (row.startedAt) entry.meta.startedAt = entry.meta.startedAt || row.startedAt;
      // Snapshot refresh fields (@179a009e): restore status/retries across a
      // page refresh. Live events win — only seed when meta has no live state.
      if (!entry.meta.status && row.status) {
        if (isFailedSnapshotStatus(row.status)) entry.meta.status = 'failed';
        else if (row.status === 'Processing') entry.meta.status = 'running';
        else if (row.status === 'Frozen') entry.meta.status = 'frozen';
      }
      if (row.retryCount > 0) entry.meta.retries = Math.max(entry.meta.retries || 0, row.retryCount);
      break;
    }
  }

  // Events were skipped while hidden → DOM is stale or empty. Force a full
  // refresh from backend history (same pipeline as first open) and seed
  // in-flight stream text so the current turn's tail renders live.
  if (entry.view.dirtyWhileHidden) {
    entry.view.dirtyWhileHidden = false;
    entry.view.resetStream();
    entry.container.innerHTML = '';
    entry.historyLoaded = false;
    const sid = entry.view.sessionId;
    if (state.sessionTexts[sid]) entry.view.stream.aiText = state.sessionTexts[sid];
    if (state.sessionThinkingBuffers[sid]) entry.view.stream.thinkingText = state.sessionThinkingBuffers[sid];
  }

  popupOverlay = document.createElement('div');
  popupOverlay.className = 'flow-agent-overlay fullscreen';
  lastModelBadgeHtml = null; // fresh badge element — force first render on open

  // Mount on document.body for bg-agent popups (not inside a flow card)
  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-name">${esc(agentName || entry.meta.agentName || 'Sub-agent')}</span>
        <span class="flow-agent-subtitle">${esc(taskDescription || entry.meta.task || '')}</span>
        <span class="flow-agent-model" id="bgagent-model"></span>
        <span class="flow-agent-ctx" id="bgagent-ctx"></span>
        <div class="flow-agent-close" id="bgagent-close">✕</div>
      </div>
      <div class="flow-agent-footer" id="bgagent-footer">
        <span class="fa-status-dot"></span>
        <span class="fa-task">${esc(entry.meta.task || 'Session')}</span>
        <span class="fa-phase" style="display:none"></span>
        <span class="fa-manage-slot"></span>
      </div>
    </div>
  `;

  // Async-fetch agent model info for header badge
  if (agentName) {
    fetchAgentModelBadge(agentName);
  }

  document.body.appendChild(popupOverlay);

  const modal = popupOverlay.querySelector('.flow-agent-modal');

  const footer = popupOverlay.querySelector('#bgagent-footer');
  modal.insertBefore(entry.container, footer);
  entry.footerEl = footer;

  // Management panel (2026-08-22 ruling): no input bar — stop/retry + state.
  const v = entry.view;
  const bar = buildManageBar();
  footer.querySelector('.fa-manage-slot').replaceWith(bar.root);
  entry.manageBar = bar;
  bindManageActions(bar, () => currentStepId);
  import('./utils.js').then(({ createIconsIn }) => { createIconsIn(bar.root); });

  updateFooterStatus(entry);

  popupOverlay.addEventListener('click', (e) => {
    if (e.target === popupOverlay || e.target.id === 'bgagent-close') closeStepPopup();
  });

  entry.container.addEventListener('scroll', () => {
    // Follow-intent latch for THIS view (shared near-bottom unit, was 40)
    updateScrollSnapped(entry.view, entry.container);
  });

  // Per-view scroll-follow machinery: row counting + "↓ N new messages" pill
  initScrollFollow(entry.view);

  requestAnimationFrame(() => {
    entry.container.scrollTop = entry.container.scrollHeight;
  });
  entry.view.stream.scrollSnapped = true;

  popupResizeObs = new ResizeObserver(() => {
    resetCardWidths(entry.container);
  });
  popupResizeObs.observe(modal);

  // Load session history from backend
  if (nodeSessionId && !entry.historyLoaded && entry.container.children.length === 0) {
    entry.historyLoaded = true;
    setActiveView(entry.view);
    entry.view.pagination.pendingInitialLoad = true;
    sendWs({ type: 'getHistory', sessionId: nodeSessionId, limit: 100 });
  }
}

export function closeStepPopup() {
  if (!popupOverlay) return;
  if (currentStepId) {
    const entry = stepViews.get(currentStepId);
    if (entry) {
      entry.view.visible = false; // hidden — ws.js gates DOM rendering again
      getHiddenRoot().appendChild(entry.container);
      entry.footerEl = null;
      if (entry.meta.status === 'done') cleanupBgAgentView(currentStepId);
    }
  }
  if (popupResizeObs) {
    popupResizeObs.disconnect();
    popupResizeObs = null;
  }
  popupOverlay.remove();
  popupOverlay = null;
  currentStepId = null;
}

export function removeStepView(sessionId) {
  const entry = stepViews.get(sessionId);
  if (entry) {
    delete chatViews[entry.view.id];
    // Release card iframe observers/browsing contexts BEFORE the container
    // detaches (D1, mem-diag 20260907 — same contract as P0-1 chat paths).
    cleanupCardIframes(entry.container);
    entry.container.remove();
    stepViews.delete(sessionId);
  }
}

/** Simple LRU — evict oldest views (Map insertion order) beyond the cap.
 *  Never evicts the currently open view. */
const STEP_VIEW_LRU_CAP = 20;
function enforceStepViewCap() {
  while (stepViews.size > STEP_VIEW_LRU_CAP) {
    let evicted = false;
    for (const key of stepViews.keys()) {
      if (key === currentStepId && popupOverlay) continue;
      removeStepView(key);
      evicted = true;
      break;
    }
    if (!evicted) break;
  }
}

// ── Context usage ring ───────────────────────────────────

function fmtTokens(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return Math.round(n / 1000) + 'k';
  return String(n);
}

function updatePopupCtxRing() {
  if (!popupOverlay || !currentStepId) return;
  const el = popupOverlay.querySelector('#bgagent-ctx');
  if (!el) return;
  const info = state.sessionModelInfo[currentStepId];
  if (!info || !info.contextWindow) { el.innerHTML = ''; el.style.display = 'none'; return; }
  const ratio = info.inputTokens != null ? info.inputTokens / info.contextWindow : 0;
  const pct = Math.min(Math.round(ratio * 100), 100);
  let color = '#4caf50';
  if (ratio > 0.5) color = '#d4a030';
  if (ratio > 0.75) color = '#e53935';
  const R = 15;
  const CIRC = 2 * Math.PI * R;
  const dashLen = CIRC * pct / 100;
  const thresholdPct = Math.round((info.compactThreshold || 0.8) * 100);
  const thresholdAngle = thresholdPct * 3.6;
  const tooltip = info.inputTokens != null
    ? `${fmtTokens(info.inputTokens)} / ${fmtTokens(info.contextWindow)} tokens (${pct}%) · threshold ${thresholdPct}%`
    : `${fmtTokens(info.contextWindow)} context window`;
  el.title = tooltip;
  el.style.display = 'inline-flex';
  el.innerHTML = `<div class="ctx-ring-wrap ctx-compact" title="${tooltip}">
    <svg width="28" height="28" viewBox="0 0 36 36" class="ctx-ring-svg">
      <circle cx="18" cy="18" r="${R}" fill="none" stroke="rgba(128,128,128,0.15)" stroke-width="3.5"/>
      <circle cx="18" cy="18" r="${R}" fill="none" stroke="${color}" stroke-width="3.5"
              stroke-dasharray="${dashLen.toFixed(1)} ${CIRC.toFixed(1)}"
              stroke-linecap="round"
              transform="rotate(-90 18 18)"
              style="transition:stroke-dasharray 0.4s ease, stroke 0.4s ease;"/>
      <line x1="18" y1="1.5" x2="18" y2="5" stroke="rgba(200,80,80,0.7)" stroke-width="1.5"
            class="ctx-ring-threshold"
            transform="rotate(${thresholdAngle.toFixed(1)} 18 18)"/>
    </svg>
    <span class="ctx-ring-pct">${pct}</span>
  </div>`;
}

onMessage('usageUpdate', () => { if (popupOverlay) { updatePopupCtxRing(); renderModelBadge(); } });
onMessage('done', () => { if (popupOverlay) { updatePopupCtxRing(); renderModelBadge(); } });

// ── WS event interception ────────────────────────────────

export function interceptBgAgentStep(msg) {
  // "delegate-"/"subtask-" prefix is backend protocol (DelegateTool/SubTaskTool session naming).
  if (!msg.nodeSessionId || !isBgAgentId(msg.nodeSessionId)) return false;
  const entry = ensureStepView(msg.nodeSessionId);

  if (msg.type === 'agentStart') {
    entry.meta.agentName = msg.name || '';
    entry.meta.task = msg.taskDescription || '';
    entry.meta.status = 'running';
    entry.meta.startedAt = entry.meta.startedAt || Date.now(); // uptime anchor
    entry.meta.stuck = null;
  } else if (msg.type === 'agentDone' || msg.type === 'agentEnd') {
    entry.meta.status = 'done';
    entry.meta.stuck = null;
  } else if (msg.type === 'agentFrozen') {
    entry.meta.status = 'frozen';
    entry.meta.frozenResumeAt = msg.resumeAt || null;
    entry.meta.freezeReason = msg.reason; // UI-7: error-family → amber tile
    entry.meta.escalation = msg.escalation ? msg.escalation.level : null;
  } else if (msg.type === 'agentResumed') {
    entry.meta.status = 'running';
    entry.meta.frozenResumeAt = null;
    entry.meta.freezeReason = null;
    entry.meta.escalation = null;
  } else if (msg.type === 'agentThinking') {
    // Granular phase (#343): LLM reasoning in progress — set once, subsequent
    // delta chunks no-op so the footer doesn't churn on every token.
    if (entry.meta.status !== 'thinking') entry.meta.status = 'thinking';
  } else if (msg.type === 'agentToolStart') {
    entry.meta.status = 'tool';
    entry.meta.toolLabel = msg.label || '';
  } else if (msg.type === 'agentToolEnd') {
    entry.meta.status = 'running';
    entry.meta.toolLabel = '';
  } else if (msg.type === 'agentTextDelta') {
    if (entry.meta.status !== 'responding') entry.meta.status = 'responding';
  } else if (msg.type === 'interrupted') {
    // User pressed stop — the turn ended by intent, not failure. The agent
    // goes idle; retry is offered (restart makes sense for a stopped task).
    entry.meta.status = 'stopped';
    entry.meta.stuck = null;
  }

  setActiveView(entry.view);

  if (currentStepId === msg.nodeSessionId) {
    requestAnimationFrame(() => {
      const snapped = entry.view.stream.scrollSnapped;
      if (snapped || shouldFollowBottom(entry.view, entry.container)) {
        entry.container.scrollTop = entry.container.scrollHeight;
      }
    });
    updateFooterStatus(entry);
  }
  return true;
}

setBgAgentStepInterceptor(interceptBgAgentStep);

// ── historyPage handler ───────────────────────────────────

export function handleBgAgentHistory(msg) {
  const entry = stepViews.get(msg.sessionId);
  if (!entry) return false;

  const view = entry.view;
  view.pagination.loading = false;

  const isInitialLoad = view.pagination.pendingInitialLoad;
  if (isInitialLoad) {
    view.pagination.pendingInitialLoad = false;
    entry.container.innerHTML = '';
    view.pagination.offset = msg.offset;
    view.pagination.total = msg.total;
    view.pagination.hasMore = msg.hasMore;

    setActiveView(view);
    // #346 boundary fix: mid-turn tail stays flat when the agent is still
    // active (running/thinking/tool/frozen/stuck) — terminal event gathers it.
    const busyTail = ['running', 'thinking', 'tool', 'frozen', 'stuck'].includes(entry.meta.status);
    restoreFromBackendHistory(msg.messages, { busyTail });

    requestAnimationFrame(() => {
      entry.container.scrollTop = entry.container.scrollHeight;
    });
  }
  return true;
}

function updateFooterStatus(entry) {
  if (!entry.footerEl) return;
  const status = entry.meta.status || '';
  entry.footerEl.classList.remove('running', 'done', 'failed', 'frozen', 'thinking', 'tool', 'responding', 'stuck', 'stopped', 'error');
  if (status) entry.footerEl.classList.add(status);
  const taskEl = entry.footerEl.querySelector('.fa-task');
  const isErrorFrozen = status === 'frozen' && isErrorReason(entry.meta.freezeReason);
  // UI-7: error-recovery frozen → amber tile (add .error modifier).
  if (isErrorFrozen) entry.footerEl.classList.add('error');
  if (taskEl) {
    if (status === 'frozen') {
      if (isErrorFrozen) {
        // Error family: reason short text (重试中 / 等恢复 / 等待上级决策).
        const escTxt = errorTileText(entry.meta);
        taskEl.innerHTML = `${errorIcon(entry.meta.freezeReason, 'error-icon')}<span class="fa-task-text">${escTxt}</span>`;
      } else {
        // Frozen tile: "已冻结 · HH:mm 恢复" — resumeAt epoch → local HH:mm
        const at = entry.meta.frozenResumeAt;
        const clock = at ? (() => {
          const d = new Date(at);
          if (Number.isNaN(d.getTime())) return '';
          return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        })() : '';
        taskEl.textContent = clock ? t('chat.frozenShort', { time: clock }) : t('chat.frozenNoTime');
      }
    } else if (status === 'stuck' && entry.meta.stuck) {
      // Restrained red stuck label (manage-panel, 2026-08-22).
      // Hard-recovery P7 (2026-09-07): label mirrors the REAL backend action.
      taskEl.textContent = entry.meta.stuck.action === 'restart'
        ? t('manage.stuckAutoRestart')
        : entry.meta.stuck.action === 'hard-abort'
          ? t('manage.stuckHardAbort')
          : entry.meta.stuck.action === 'failed'
            ? t('manage.stuckFailed')
            : t('manage.stuck', { secs: entry.meta.stuck.idleSecs ?? '' });
    } else {
      taskEl.textContent = entry.meta.task || 'Session';
    }
  }
  // Phase text (#343): granular activity — thinking / tool / responding.
  const phaseEl = entry.footerEl.querySelector('.fa-phase');
  if (phaseEl) {
    // 2026-09-03 toolline truncate fix: truncate at the RENDER layer only —
    // meta.toolLabel keeps the full label; the tooltip below shows it whole.
    const fullLabel = entry.meta.toolLabel || '';
    const phaseMap = {
      running: 'Working…',
      thinking: 'Thinking…',
      responding: 'Responding…',
      tool: fullLabel ? `Using tool: ${truncateMiddle(fullLabel, 96, 24)}` : 'Running tool…',
      done: 'Done',
      failed: 'Failed',
      stuck: 'Stuck',
      stopped: 'Stopped',
    };
    const text = phaseMap[status] || '';
    phaseEl.textContent = text;
    // Unified panel spec §7: long text → single-line ellipsis + title with
    // the full text (hover tooltip). Non-tool phases are short — no tooltip.
    phaseEl.title = (status === 'tool' && fullLabel) ? `Using tool: ${fullLabel}` : '';
    phaseEl.style.display = text ? '' : 'none';
  }
  // Management cluster: state-linked buttons + permission matrix.
  syncManageControls(entry.manageBar, entry.meta, currentStepId);
}

// ── cancelAgent result linkage (stop button, @179a009e) ────────────────
// ok=true: terminal cancel accepted — settle the footer to stopped
// immediately (retry offered); the backend's terminal event (agentEnd/
// interrupted) refines/cleans up afterwards. ok=false is toasted by
// managePanel's own handler (stale id / read-only kind).
onCancelResult((msg) => {
  if (!msg || msg.sessionId !== currentStepId) return;
  const entry = stepViews.get(msg.sessionId);
  if (!entry || msg.ok !== true) return;
  entry.meta.status = 'stopped';
  entry.meta.stuck = null;
  updateFooterStatus(entry);
});

// ── Stuck visibility (taskStuck broadcast, whitelisted 2026-08-22) ──────
// taskStuck carries the child's bare sessionId — the same key stepViews uses.
// action=restart counts as one auto-restart (the visible "retries" number);
// any subsequent activity event clears stuck in the interceptor.
onMessage('taskStuck', (msg) => {
  const entry = stepViews.get(msg.sessionId);
  if (!entry) return;
  entry.meta.status = 'stuck';
  entry.meta.stuck = { idleSecs: msg.idleSecs, action: msg.action };
  if (msg.action === 'restart') entry.meta.retries = (entry.meta.retries || 0) + 1;
  if (currentStepId === msg.sessionId) updateFooterStatus(entry);
});

// ── Cleanup when a sub-agent session ends ─────────────────
// Called from main.js agentDone handler when a background sub-agent finishes.
export function cleanupBgAgentView(nodeSessionId) {
  // Keep the view for a few seconds so the user can read the output,
  // then remove it.
  setTimeout(() => {
    // Never destroy the view while the user is looking at it.
    if (currentStepId === nodeSessionId && popupOverlay) return;
    removeStepView(nodeSessionId);
  }, 5000);
}

// ── Utils ────────────────────────────────────────────────
function esc(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&').replace(/</g, '<').replace(/>/g, '>')
    .replace(/"/g, '"').replace(/'/g, '&#039;');
}

// #308 actual-model display: the header badge shows the model this agent
// ACTUALLY used on its last LLM round (live from state.sessionModelInfo),
// falling back to the backend health-resolved candidate (cfg.current), then
// to the configured preferred. Never show "preferred" as if it were live.
let popupModelCfg = null;      // last fetched /api/agents/:name/model response
let lastModelBadgeHtml = null; // value-change guard — keep DOM stable

function modelBadgeHtml(current, preferred) {
  if (!current) return '';
  const isFallback = !!(preferred && current !== preferred);
  return isFallback
    ? `<span class="flow-agent-model-badge">${esc(current)}</span>`
    : `<span class="flow-agent-subtitle">${esc(current)}</span>`;
}

function renderModelBadge() {
  const el = popupOverlay?.querySelector('#bgagent-model');
  if (!el) return;
  const live = currentStepId ? state.sessionModelInfo[currentStepId]?.model : null;
  const cfg = popupModelCfg || {};
  const current = live || cfg.current || cfg.preferred || '';
  const html = modelBadgeHtml(current, cfg.preferred);
  if (html === lastModelBadgeHtml) return; // unchanged — no DOM write
  lastModelBadgeHtml = html;
  el.innerHTML = html;
}

/** Fetch agent model config and render a badge in the popup header. */
async function fetchAgentModelBadge(agentName) {
  try {
    const token = localStorage.getItem(key('token')) || '';
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const resp = await fetch(`/api/agents/${encodeURIComponent(agentName)}/model`, { headers });
    if (!resp.ok) return;
    popupModelCfg = await resp.json();
    renderModelBadge();
  } catch (e) { /* non-critical */ }
}
