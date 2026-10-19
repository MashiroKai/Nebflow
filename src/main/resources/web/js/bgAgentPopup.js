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
import { isBgAgentId, truncateMiddle, shouldFollowBottom, initScrollFollow, statusIcon } from './utils.js';
import state from './state.js';
import { key } from './branding.js';
import { t } from './i18n.js';
import { buildManageBar, bindManageActions, syncManageControls, onCancelResult, isFailedSnapshotStatus } from './managePanel.js';
import { isErrorReason, errorTileText, errorIcon } from './errorRecovery.js';
import { cleanupCardIframes } from './cardRegistry.js';
// 两层架构批（2026-10-09 · 规格 §A 净化）：弹窗只留 G4 单查一个数据缝——星型区
// （G3 children）迁任务列表（§B）、flow 节点区迁 L1 委托流程图（§C）、归档动作
// 全后台化（引擎终态自动归档 + L2 抽屉管理）。收起式摘要条 = 九点⑦承载面平移的
// 可见性保底（作者裁定：弹窗保留收起式摘要条承载 brief/结果）。
import { fetchDelegateDetail, isDelegateDomainId } from './delegateApi.js';
// 修订批（九点⑦ · 2026-10-09）：判词→状态映射（结果区词面）——stub 读取口单源。
import { verdictToStatus } from './delegateChainStub.js';

// P2-4 cycle cut（flowAgentPopup 同款先例）：showToast 动态 import 破环。净化批
// （2026-10-09）后本文件暂零 toast 消费点（归档入口已迁 L2 抽屉）——常量在册保留
// （失去调用点的件先留＝projcreate 先例，键 face 归清理批）。
const showToast = (msg, type) =>
  import('./modal.js').then((m) => m.showToast(msg, type)).catch(() => {});

// ── delegate 摘要条样式（两层架构批 2026-10-09 · 规格 WF-01 净化形态；自注入，
//    不动 flowAgentPopup 的共享 POPUP_CSS——两弹窗模块同载但样式域分离）──────────
// 净化（用户需求 1 + 规格 §A）：详情条旧件（子代理 ×N 计数／星型子代理行族／
// flow 节点区／头部归档小图标）全部移除——子代理显示迁任务列表（§B），归档动作
// 全后台化（引擎终态自动归档 + L2 抽屉管理）。保留 = 收起式摘要条（九点⑦承载面
// 平移的可见性保底：一行摘要 + 点开 brief/结果全文）。
if (typeof document !== 'undefined' && !document.getElementById('bgagent-delegate-strip-css')) {
  document.head.insertAdjacentHTML('beforeend', `<style id="bgagent-delegate-strip-css">
.bgagent-delegate-strip {
  border-bottom: 1px solid rgba(128,128,128,0.18);
  padding: 6px 14px;
  font-size: 12px;
}
/* 收起式摘要条（WF-01）：默认收起 = 一行（任务 chip + brief 摘要 + 结果词 +
   展开 chevron）；点开 = 任务 brief 全文 + 判词 + 报告全文（九点⑦正文不丢）。 */
.bgagent-strip-toggle {
  display: flex; align-items: center; gap: 8px; min-width: 0; width: 100%;
  padding: 2px 0; border: none; background: transparent; color: inherit;
  font: inherit; text-align: left; cursor: pointer;
}
.bgagent-strip-toggle:hover .bgagent-strip-briefline { color: var(--color-text, inherit); }
.bgagent-strip-chip {
  flex-shrink: 0; padding: 1px 8px; border-radius: 8px;
  font-size: 10px; font-weight: 600; letter-spacing: 0.02em;
  color: var(--color-frame-text-muted, rgba(128,128,128,0.9));
  background: var(--color-frame-hover, rgba(128,128,128,0.12));
}
.bgagent-strip-briefline {
  flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--color-frame-text-muted, rgba(128,128,128,0.9));
}
.bgagent-strip-result {
  flex-shrink: 0; font-weight: 500; color: var(--color-text, inherit);
}
.bgagent-strip-result[hidden] { display: none; }
.bgagent-strip-chev {
  flex-shrink: 0; width: 12px; height: 12px; display: inline-flex;
  color: var(--color-frame-text-dim, rgba(128,128,128,0.6));
  transition: transform 0.15s ease;
}
.bgagent-strip-chev svg { width: 12px; height: 12px; display: block; }
.bgagent-strip-toggle[aria-expanded="true"] .bgagent-strip-chev { transform: rotate(180deg); }
.bgagent-strip-body {
  margin-top: 6px; padding-top: 6px; border-top: 1px dashed rgba(128,128,128,0.18);
  max-height: 38vh; overflow-y: auto;
  display: flex; flex-direction: column; gap: 3px;
}
.bgagent-strip-body[hidden] { display: none; }
.bgagent-strip-plugins {
  color: var(--color-frame-text-dim, rgba(128,128,128,0.6));
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.bgagent-strip-sec {
  display: flex; flex-direction: column; gap: 3px; min-width: 0;
}
/* hidden 属性守卫（修订批截图期发现）：author display 规则会压过 UA 的
   [hidden]{display:none}——结果区（在飞无判词）收起时以空壳区透出。零空区
   纪律（数据到位才显）靠本守卫成立。 */
.bgagent-strip-sec[hidden],
.bgagent-strip-verdict[hidden] { display: none; }
.bgagent-strip-sec-label {
  font-weight: 600; font-size: 10px; letter-spacing: 0.02em;
  color: var(--color-frame-text-dim, rgba(128,128,128,0.6)); flex-shrink: 0;
}
.bgagent-strip-tasktext {
  font-size: 12px; line-height: 1.5; color: var(--color-text, inherit);
  white-space: pre-wrap; word-break: break-word;
  max-height: 88px; overflow-y: auto;
}
.bgagent-strip-verdict {
  display: flex; align-items: baseline; gap: 8px; min-width: 0;
  font-size: 12px; color: var(--color-text, inherit);
}
.bgagent-strip-verdict-word { flex-shrink: 0; font-weight: 500; }
.bgagent-strip-verdict-reason {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--color-frame-text-muted, rgba(128,128,128,0.9));
}
.bgagent-strip-report {
  font-size: 12px; line-height: 1.5; color: var(--color-text, inherit);
  white-space: pre-wrap; word-break: break-word;
  max-height: 132px; overflow-y: auto;
}
</style>`);
}

/** 判词 → 行状态词键（与 taskList.DELEGATE_WORD_KEY 同族映射；零 locale 新键）。
 *  #566：blocked 词退役（verdictToStatus 已把退役判词折 completed），不再出
 *  「已阻断」徽章；引擎 failed/cancelled 真值终态行照常出词。 */
const STRIP_VERDICT_KEY = {
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
  'ready-to-merge': 'delegate.readyToMerge',
};

/** 收起/展开 chevron（与 taskList 折叠钮同一 lucide 描边语言，内联 SVG 单源）。 */
const STRIP_CHEV_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>';

/**
 * 挂 delegate 收起式摘要条（两层架构批 2026-10-09 · 规格 WF-01）：任务 brief 摘要
 * 一行 + 结果词（收起态）；点开展开 = brief 全文 + 判词 + 报告全文（九点⑦正文面）。
 * 仅 delegate 域会话（delegate-* 与 external-* 前缀）。数据 = G4 聚合单查（record
 * .plugins/record.archivedAt/verdict/record.reportDetail/record.taskId）；来源失败
 * → 条整体缺席（fail-soft）。brief 正文 = 会话历史首条注入行（getHistoryPage 回执
 * 侧 fillStripTaskBody 单点回填——G4 task 键只有台账标题截断形，正文唯一权威在历史）。
 * @param {string} nodeSessionId @param {Element} modal @param {Element} chatContainer
 */
function mountDelegateStrip(nodeSessionId, modal, chatContainer) {
  if (!isDelegateDomainId(nodeSessionId)) return;
  const strip = document.createElement('div');
  strip.className = 'bgagent-delegate-strip';
  strip.dataset.delegateStrip = nodeSessionId;
  strip.hidden = true; // 数据到位才显（零空条）
  modal.insertBefore(strip, chatContainer);

  // 收起态一行：任务 chip + brief 摘要 + 结果词 + chevron。
  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = 'bgagent-strip-toggle';
  toggle.setAttribute('aria-expanded', 'false');
  const chip = document.createElement('span');
  chip.className = 'bgagent-strip-chip';
  chip.textContent = t('flowmap.archive.taskLabel'); // 「任务」——节点详情同键
  const briefLine = document.createElement('span');
  briefLine.className = 'bgagent-strip-briefline';
  const resultWord = document.createElement('span');
  resultWord.className = 'bgagent-strip-result';
  resultWord.hidden = true; // 在飞无判词 = 缺席（零空位）
  const chev = document.createElement('span');
  chev.className = 'bgagent-strip-chev';
  chev.innerHTML = STRIP_CHEV_SVG;
  chev.setAttribute('aria-hidden', 'true');
  toggle.appendChild(chip);
  toggle.appendChild(briefLine);
  toggle.appendChild(resultWord);
  toggle.appendChild(chev);
  strip.appendChild(toggle);

  // 展开态正文：plugins 数据行（G4）+ 任务 brief 全文 + 结果（判词/报告）。
  const body = document.createElement('div');
  body.className = 'bgagent-strip-body';
  body.hidden = true; // 默认收起（WF-01）
  const plugins = document.createElement('div');
  plugins.className = 'bgagent-strip-plugins';
  body.appendChild(plugins);

  const taskSec = document.createElement('div');
  taskSec.className = 'bgagent-strip-sec';
  const taskLabel = document.createElement('span');
  taskLabel.className = 'bgagent-strip-sec-label';
  taskLabel.textContent = t('flowmap.archive.taskLabel');
  taskSec.appendChild(taskLabel);
  const taskText = document.createElement('div');
  taskText.className = 'bgagent-strip-tasktext';
  taskText.hidden = true; // 正文到位才显（零空区）
  taskSec.appendChild(taskText);
  body.appendChild(taskSec);

  const resultSec = document.createElement('div');
  resultSec.className = 'bgagent-strip-sec';
  const resultLabel = document.createElement('span');
  resultLabel.className = 'bgagent-strip-sec-label';
  resultLabel.textContent = t('flowmap.archive.resultLabel'); // 「结果」——节点详情同键
  resultSec.appendChild(resultLabel);
  const verdictRow = document.createElement('div');
  verdictRow.className = 'bgagent-strip-verdict';
  const verdictWord = document.createElement('span');
  verdictWord.className = 'bgagent-strip-verdict-word';
  const verdictReason = document.createElement('span');
  verdictReason.className = 'bgagent-strip-verdict-reason';
  verdictRow.appendChild(verdictWord);
  verdictRow.appendChild(verdictReason);
  resultSec.appendChild(verdictRow);
  const reportText = document.createElement('div');
  reportText.className = 'bgagent-strip-report';
  resultSec.appendChild(reportText);
  resultSec.hidden = true; // 无判词无报告 = 缺席（在飞常态）
  body.appendChild(resultSec);
  strip.appendChild(body);

  // 收起/展开（单条内切换；对话流不受影响——脚注/弹窗机制零触碰）。
  toggle.addEventListener('click', () => {
    const open = body.hidden;
    body.hidden = !open;
    toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  });

  /** G4 单查（并行面已随星型区移除——弹窗只剩一个数据缝）；失败 → 条缺席。 */
  fetchDelegateDetail(nodeSessionId).then((detail) => {
    if (!popupOverlay || currentStepId !== nodeSessionId) return; // 弹窗已切走 → 静默弃
    const record = (detail && detail.record && typeof detail.record === 'object') ? detail.record : null;
    if (!detail) return; // 拉取失败 → 条缺席（fail-soft，弹窗主体不受影响）
    const pluginNames = record && Array.isArray(record.plugins)
      ? record.plugins.map((p) => String(p || '')).filter(Boolean) : [];
    if (pluginNames.length) {
      plugins.textContent = `plugins: ${pluginNames.join(', ')}`; // 数据原文非文案
      plugins.title = plugins.textContent;
    } else {
      plugins.hidden = true;
    }
    // 任务 brief：G4 task 键 = 台账标题（截断形）——先占位（收起行 + 全文同源），
    // 正文由历史回执回填（fillStripTaskBody 单点；历史不可达时标题即诚实降级面）。
    // 回执竞态守卫：历史 brief 已到（briefSource=history）→ 台账标题不再回写
    // （正文唯一权威在历史——台账标题只是历史不可达时的降级占位）。
    const ledgerTask = detail && detail.task && typeof detail.task === 'object' ? detail.task : null;
    const ledgerTitle = String((ledgerTask && ledgerTask.title) || (record && record.title) || '');
    if (ledgerTitle && strip.dataset.briefSource !== 'history') {
      briefLine.textContent = ledgerTitle;
      briefLine.title = ledgerTitle;
      taskText.textContent = ledgerTitle;
      taskText.title = ledgerTitle;
      taskText.hidden = false;
    }
    // 结果（九点⑦）：判词（词+reason）＋ 报告正文（record.reportDetail 全文）。
    const verdict = detail && detail.verdict && typeof detail.verdict === 'object' ? detail.verdict : null;
    const reportDetail = String((record && record.reportDetail) || '');
    if (verdict || reportDetail) {
      const st = verdict ? verdictToStatus(String(verdict.verdict || '')) : '';
      const wordKey = STRIP_VERDICT_KEY[st] || '';
      const word = wordKey ? t(wordKey) : String((verdict && verdict.verdict) || '');
      verdictWord.textContent = word;
      verdictReason.textContent = String((verdict && verdict.reason) || '');
      verdictReason.title = String((verdict && verdict.reason) || '');
      verdictRow.hidden = !verdict;
      reportText.textContent = reportDetail;
      reportText.hidden = !reportDetail;
      resultSec.hidden = !verdict && !reportDetail;
      if (word) {
        resultWord.textContent = word; // 收起态结果词
        resultWord.hidden = false;
      }
    }
    // 归档态回显随头部归档小图标一并退役（规格 §A 移除清单 4）——G6 单旗仍在
    // 引擎侧（自动归档写），弹窗零归档件。
    strip.hidden = false;
  }).catch(() => { /* fail-soft：条缺席，弹窗主体不受影响 */ });
}

/**
 * 任务 brief 正文回填（九点⑦单点）：getHistoryPage 首刷回执里首条 delegate 注入行
 * = 派发 brief 全文（引擎侧唯一持久正文面）。弹窗已切走 → 静默弃；条缺席 → 弃。
 * 回填双写点 = 收起态一行摘要 + 展开态正文（同一文本，两层呈现）。
 * @param {string} nodeSessionId @param {Array<any>} messages */
function fillStripTaskBody(nodeSessionId, messages) {
  if (currentStepId !== nodeSessionId) return;
  const strip = popupOverlay && popupOverlay.querySelector(`[data-delegate-strip="${CSS.escape(nodeSessionId)}"]`);
  if (!strip) return;
  const brief = (Array.isArray(messages) ? messages : []).find((m) => m && m.type === 'user' &&
    m.injected === true && (m.source === 'delegate' || m.source === 'subtask'));
  const text = brief && typeof brief.text === 'string' ? brief.text.trim() : '';
  if (!text) return;
  // 历史正文 = 唯一权威（覆盖台账标题占位——首刷回执与 G4 单查并行，到序不定）。
  const el = strip.querySelector('.bgagent-strip-tasktext');
  if (el) {
    el.textContent = text;
    el.title = text;
    el.hidden = false;
  }
  // 收起态一行摘要同源更新（首刷回执前 = 台账标题占位，正文到位即换真值）。
  const line = strip.querySelector('.bgagent-strip-briefline');
  if (line) {
    line.textContent = text;
    line.title = text;
  }
  strip.dataset.briefSource = 'history'; // G4 回执侧竞态守卫读位（台账标题让位）
}

// ── Per-sub-agent state ────────────────────────────────────
// nodeSessionId → { view: ChatView, container: div, meta: {}, historyLoaded: bool }
const stepViews = new Map();
let currentStepId = null;
let popupOverlay = null;
let popupResizeObs = null;

// ── Bounded history back-paging ─────────────────────────────────────────
// The panel used to fetch exactly ONE tail page and never ask for anything
// older, so for any session longer than a page the earliest rows — including
// the injected task prompt the sub-agent received at index 0 — never entered
// the panel at all. The primary window has always been able to walk back
// (main.js scroll-to-top → beforeIndex); the panel now reuses that same
// backend contract (SessionStore.getHistoryPage: beforeIndex = the ≤limit
// messages before that index) but with explicit ceilings, because the popup
// must stay cheap to open:
//   · BG_PAGE_LIMIT          rows per request (the first frame's limit, unchanged)
//   · BG_MAX_BACKFILL_PAGES  cap on back-fill requests per load cycle
//   · BG_MAX_BACKFILL_ROWS   cap on rows accumulated per load cycle
//   · BG_BACKFILL_BUDGET_MS  wall-clock budget for the automatic head-seek
// The automatic head-seek runs once per load cycle and stops at index 0 or at
// a ceiling; the user-driven scroll-up continues under the same page/row caps,
// so neither path can page without bound.
const BG_PAGE_LIMIT = 100;
const BG_MAX_BACKFILL_PAGES = 12;
const BG_MAX_BACKFILL_ROWS = 1300;
const BG_BACKFILL_BUDGET_MS = 8000;

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
  // （c）面（作者 2026-10-08 裁定 · 定向重开）：subagent 弹窗的**在飞过程行**走
  // 9.19 裸行形态——turn-line 剧场（workline 单行呈现 + 过程卡预 tucked，10-03
  // stream-ux）只属主窗。弹窗在飞 = thinking 气泡裸流（§③ 5 行窗上限照钉）+
  // 工具卡裸行可见（9.19 同形），弹窗内不建 turn-line；终态收拢（turn-header
  // + tuckRows）与主窗同一 9.19 统一面（883712c40），零变化。chat.js / main.js
  // 各在飞渲染点读此标志分流；主窗与 flow 弹窗不置位 ⇒ 呈现零触碰。
  view.nfBareProcessRows = true;
  // Hidden until the popup opens — ws.js gates DOM rendering while false.
  view.visible = false;
  // Register in the global view registry so findViewBySessionId() can route
  // live events here even while hidden (streamDispatchView then marks
  // dirtyWhileHidden → reopen forces a history refresh).
  chatViews[view.id] = view;

  const entry = {
    view, container, meta: { agentName: '', task: '', status: '' }, historyLoaded: false,
    // Bounded back-fill budget for the current load cycle (see BG_* above).
    backfill: { pages: 0, rows: 0, startedAt: 0, active: false },
  };
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
  // （e2e 断点②修曾在此按行活态补武装 turnExpecting——实测 T6（bgagent-injected-
  // bubbles）红：重开路径的缓冲重播会把思考缓冲多渲染一行。撤——页面刷新后在飞
  // 会话的思考尾流仍不入弹窗（残留边界，随批报告申报）；活 turn 的武装在
  // interceptBgAgentStep 的 agentStart 分支单点。
  if (entry.meta.status === 'running' || entry.meta.status === 'thinking' ||
      entry.meta.status === 'responding' || entry.meta.status === 'tool') {
    state.turnExpecting[nodeSessionId] = true;
  }

  // A panel that was CLOSED has no stream anchor to continue into: nothing
  // renders into this container while the panel is away, so the bubble left
  // behind belongs to the previous display cycle. Continuing it as-is is not
  // merely stale — `aiText` keeps accumulating across the cycle, so the next
  // live delta would re-render the PREVIOUS cycle's text plus the new tail.
  //
  // Two things must hold at once, and they pull in opposite directions:
  //   · the accumulated text must start from a clean slate (no cross-cycle /
  //     cross-turn concatenation), which is what resetStream gives;
  //   · the visible row must NOT be duplicated — resetStream drops the anchor,
  //     and a re-seeded `aiText` with no anchor would make chat.js open a
  //     SECOND bubble holding the same text again.
  // So the anchor is captured before the reset and re-adopted when it is still
  // attached: the same row keeps streaming the restored tail. When it is gone
  // (a terminal/tool boundary finalized that turn while the panel was closed)
  // there is nothing to continue, and the restored tail correctly opens a new
  // row — the same shape the primary window's history restore produces.
  // The tail itself is never lost either way: state.js accumulates per-session
  // buffers for EVERY session, the channel the primary window re-seeds from.
  if (entry.view.dirtyWhileHidden) {
    entry.view.dirtyWhileHidden = false;
    const anchor = entry.view.stream.currentAiBubble;
    const adopt = !!(anchor && anchor.isConnected);
    entry.view.resetStream();
    const sid = entry.view.sessionId;
    const buf = state.sessionTexts[sid];
    if (adopt) {
      // The buffer is the fuller of the two (it kept accumulating while the
      // panel was closed), so it wins; the anchor's own text is the fallback
      // when that buffer was already drained by a terminal.
      entry.view.stream.currentAiBubble = anchor;
      entry.view.stream.aiText = buf || anchor._nfText || anchor.textContent || '';
      if (state.sessionThinkingBuffers[sid]) entry.view.stream.thinkingText = state.sessionThinkingBuffers[sid];
    } else {
      if (buf) entry.view.stream.aiText = buf;
      if (state.sessionThinkingBuffers[sid]) entry.view.stream.thinkingText = state.sessionThinkingBuffers[sid];
    }
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

  // delegate 收起式摘要条（两层架构批 WF-01：brief 摘要一行 + 点开 brief/结果
  // 全文）——弹窗头与聊天体之间，数据到位才显（fail-soft 缺席）；弹窗本体内
  // 子代理显示/归档件为零（净化口径：弹窗 DOM 无「子代理」字样、无归档钮）。
  mountDelegateStrip(nodeSessionId, modal, entry.container);

  // Management panel (2026-08-22 ruling): no input bar — stop/retry + state.
  const v = entry.view;
  const bar = buildManageBar();
  footer.querySelector('.fa-manage-slot').replaceWith(bar.root);
  entry.manageBar = bar;
  bindManageActions(bar, () => currentStepId);
  import('./utils.js').then(({ createIconsIn }) => { createIconsIn(bar.root); });

  updateFooterStatus(entry);

  popupOverlay.addEventListener('click', (e) => {
    const target = e.target;
    // `id` is declared on Element, not on EventTarget — narrow before reading it.
    if (target === popupOverlay || (target instanceof Element && target.id === 'bgagent-close')) closeStepPopup();
  });

  // Scroll-to-top gate → the older page, same gate the primary window uses
  // (main.js scroll listener: scrollTop < 100 && hasMore && !loading &&
  // offset > 0). Shares the automatic head-seek's page/row budget, so a
  // partial scroll cannot push the panel past the caps either.
  // B4 (2026-10-04): the listener is installed by initScrollFollow below (the
  // single scroll-machinery installer, so the follow decision and the ↓ N pill
  // share ONE geometry probe per event). This is the pagination half it calls.
  const onScrollGate = () => {
    if (entry.container.scrollTop < 100) requestOlderPage(entry);
  };

  // Per-view scroll-follow machinery: row counting + "↓ N new messages" pill
  // (+ the scroll listener; the pagination gate above rides along with it).
  initScrollFollow(entry.view, { onScroll: onScrollGate });

  requestAnimationFrame(() => {
    entry.container.scrollTop = entry.container.scrollHeight;
  });
  entry.view.stream.scrollSnapped = true;

  popupResizeObs = new ResizeObserver(() => {
    resetCardWidths(entry.container);
  });
  popupResizeObs.observe(modal);

  // Load session history from backend. UNCONDITIONAL re-pull on open — the same
  // shape the sister popup has always used (flowAgentPopup.js: "always re-pull on
  // open"). Both channels must be merged on every open because they carry
  // DIFFERENT content for this session family: the injected rows (task dispatch /
  // mail / node notifications) exist ONLY in the persisted history, while the
  // dispatcher/node sessions' assistant text and tool rows are streamed live and
  // never reach .ui.json at all.
  //
  // The removed conjunction `entry.container.children.length === 0` was the
  // defect: ws.js renders bg-agent live frames into the HIDDEN container even
  // while the popup is closed (the "Gating EXEMPT" branch), so any single live
  // row made the container non-empty and the history leg was then skipped
  // forever — the panel showed exactly the live rows and not one injected
  // bubble. `historyLoaded` stays assigned (it still records "a load cycle has
  // been started" for callers/readers of the entry), it just no longer gates.
  if (nodeSessionId) {
    entry.historyLoaded = true;
    setActiveView(entry.view);
    entry.view.pagination.pendingInitialLoad = true;
    sendWs({ type: 'getHistory', sessionId: nodeSessionId, limit: BG_PAGE_LIMIT });
  }
}

export function closeStepPopup() {
  if (!popupOverlay) return;
  if (currentStepId) {
    const entry = stepViews.get(currentStepId);
    if (entry) {
      entry.view.visible = false; // hidden — ws.js gates DOM rendering again
      // Mark the view dirty on EVERY close, so the next open runs the existing
      // refresh branch above (resetStream + clear the stale container +
      // re-seed the in-flight live tail). Without this the container keeps its
      // old DOM and the view keeps its old stream state across a close/reopen
      // cycle — state that cannot heal on its own (stream text would keep
      // concatenating across turns, and rows rendered from the previous cycle
      // would survive into the next one).
      //
      // Note this is the *same* flag ws.js sets for skipped-while-hidden events;
      // for bg-agent sessions that branch is gating-EXEMPT (ws.js "Gating
      // EXEMPT"), so nothing else ever set it here — which is exactly why the
      // reopen case was broken.
      entry.view.dirtyWhileHidden = true;
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
  // ui-status-redesign（盘点 #49，B 组）：字面量 → token（经 style 消费）。
  if (ratio > 0.75) color = 'var(--status-error-icon)';
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
      <circle cx="18" cy="18" r="${R}" fill="none" stroke-width="3.5"
              style="stroke:${color};stroke-dasharray:${dashLen.toFixed(1)} ${CIRC.toFixed(1)};transition:stroke-dasharray 0.4s ease, stroke 0.4s ease;"
              stroke-linecap="round"
              transform="rotate(-90 18 18)"/>
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
    // e2e 断点②修（2026-10-07）：thinkingDelta 渲染门的武装位。门（main.js
    // onMessage('thinkingDelta')）要求 turnExpecting[sid] ∨ 已有思考/AI 气泡，
    // 否则丢弃 delta——本意防 done 后迟到的杂音思考泡，但 delegate 会话永远
    // 不经真人派发点（turnExpecting 只在 input.js 四个发送点置位）⇒ 弹窗里
    // delegate 的思考流整段被丢（作者 23:05 实测：delegate 纯 thinking 4m21s，
    // 弹窗体只剩注入气泡）。agentStart = 引擎侧「该会话一 turn 开始」的事实，
    // 与真人派发同语义 ⇒ 在此武装同一门位；agentDone 的既有清理路径
    // （main.js done/error 分支按 sessionId delete turnExpecting）照常拆闸，
    // 防杂音的原意不破。
    state.turnExpecting[msg.nodeSessionId] = true;
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

/** One bounded step of the back-fill walk — shared by the automatic head-seek
 *  (first load) and the user's scroll-to-top. `beforeIndex` = the index of the
 *  oldest row we already hold, so the backend answers with the ≤limit rows
 *  before it (SessionStore.getHistoryPage), exactly like the primary window.
 *  Returns false when no frame was sent: nothing older exists, a request is
 *  already in flight, or a page/row ceiling has been reached.
 *  @param {{ budgeted?: boolean }} [opts]  also apply the wall-clock budget (automatic run). */
function requestOlderPage(entry, opts = {}) {
  const pag = entry.view.pagination;
  const bf = entry.backfill;
  if (!bf || !pag || pag.loading || !pag.hasMore || !(pag.offset > 0)) return false;
  if (bf.pages >= BG_MAX_BACKFILL_PAGES || bf.rows >= BG_MAX_BACKFILL_ROWS) return false;
  if (opts.budgeted) {
    if (!bf.startedAt) bf.startedAt = Date.now();
    if (Date.now() - bf.startedAt > BG_BACKFILL_BUDGET_MS) return false;
  }
  pag.loading = true;
  setActiveView(entry.view);
  sendWs({ type: 'getHistory', sessionId: entry.view.sessionId, limit: BG_PAGE_LIMIT, beforeIndex: pag.offset });
  return true;
}

/** Does a historyPage payload carry rows that `restoreFromBackendHistory`
 *  renders in their own right (i.e. not the injected-bubble channel)?
 *  Those are exactly the ui.json row kinds that another writer — the WS
 *  recording layer wired to the engine's own wsSendFn — persists for the
 *  session families whose engines are bound to a per-session send, so a panel
 *  may safely rebuild its container from the frame.
 *  When NO such row is present the panel must NOT treat the frame as the whole
 *  timeline: for the dispatcher/node family the assistant text and tool rows
 *  never reach .ui.json at all and this container is their only copy.
 *  Read-only and conservative — an unknown/future row kind simply falls into
 *  "not reproducible", which keeps the container rather than dropping it. */
function historyCarriesRenderedRows(messages) {
  if (!Array.isArray(messages)) return false;
  return messages.some((m) => m && (m.type === 'ai' || m.type === 'tool' || m.type === 'agent'));
}

export function handleBgAgentHistory(msg) {
  const entry = stepViews.get(msg.sessionId);
  if (!entry) return false;

  const view = entry.view;
  view.pagination.loading = false;

  const isInitialLoad = view.pagination.pendingInitialLoad;
  if (isInitialLoad) {
    view.pagination.pendingInitialLoad = false;
    // ── Container reconciliation (rework round 1) ───────────────────────
    // This branch used to wipe the container unconditionally and rebuild it
    // from the frame alone. That is sound only when the frame can reproduce
    // what the container holds. It is true for the families whose live rows
    // the backend persists too (delegate/subtask/dag — their .ui.json carries
    // the ai/tool rows), and FALSE for the dispatcher/node family: a
    // launch-mounted engine broadcasts through a bare wsHub.broadcast, so for
    // it only injected user rows ever reach .ui.json (see
    // AgentActor#emitInjectedUserEvent and the /sessions census). For that
    // family this container is the ONLY copy of the assistant text and tool
    // rows, so the wipe destroyed them unrecoverably, and it also orphaned
    // view.stream.currentAiBubble — chat.js then reuses the detached node
    // (no new row) and drops the render on its `isConnected` guard, so even
    // the in-flight tail stopped rendering.
    //
    // The discriminator is the FRAME's own content — never a session-id
    // prefix: if the payload carries rows this view renders from history as
    // non-injected content, the frame fully reproduces the container and the
    // proven wipe + re-seed shape is kept; otherwise history is the
    // injected-row channel only, and every row the frame cannot re-provide
    // stays in place.
    const preserveLive = !historyCarriesRenderedRows(msg.messages);
    if (preserveLive) {
      // Detach the rows first (they are the LATER part of the timeline), then
      // let history render into the emptied container, then re-attach. Moving
      // nodes preserves identity and listeners, so a still-open stream bubble
      // stays attached and keeps rendering, and a closed round keeps its rows.
      // Injected rows are the one kind the frame re-provides (they are
      // persisted before they are broadcast), so they are dropped here to keep
      // the bubble count exact — the history copy is byte-identical.
      const keptLive = document.createDocumentFragment();
      for (const child of Array.from(entry.container.children)) {
        if (child.querySelector && child.querySelector('.bubble.injected')) continue;
        keptLive.appendChild(child);
      }
      entry.container.innerHTML = '';
      view.pagination.offset = msg.offset;
      view.pagination.total = msg.total;
      view.pagination.hasMore = msg.hasMore;
      // Fresh load cycle → fresh (bounded) back-fill budget.
      entry.backfill.pages = 0;
      entry.backfill.rows = 0;
      entry.backfill.startedAt = Date.now();
      entry.backfill.active = !!msg.hasMore;

      setActiveView(view);
      // #346 boundary fix: mid-turn tail stays flat when the agent is still
      // active (running/thinking/tool/frozen/stuck) — terminal event gathers it.
      const busyTail = ['running', 'thinking', 'tool', 'frozen', 'stuck'].includes(entry.meta.status);
      restoreFromBackendHistory(msg.messages, { busyTail });
      // 九点⑦：首刷回执含派发 brief（首条 delegate 注入行全文）→ 详情条任务区回填。
      fillStripTaskBody(msg.sessionId, msg.messages);
      entry.container.appendChild(keptLive);

      requestAnimationFrame(() => {
        entry.container.scrollTop = entry.container.scrollHeight;
      });
      // Bounded head-seek: the injected task prompt — and any other early row —
      // sits at index 0, which the tail page never reached.
      if (entry.backfill.active && !requestOlderPage(entry, { budgeted: true })) {
        entry.backfill.active = false;
      }
      return true;
    }
    // Frame reproduces the container (its rows are persisted live): keep the
    // pre-existing wipe shape, and re-seed the in-flight tail from the
    // per-session buffers, which state.js accumulates for EVERY session — so
    // the stream state broken by the wipe is restored, not dropped.
    cleanupCardIframes(entry.container);
    entry.container.innerHTML = '';
    view.resetStream();
    const sid = view.sessionId;
    if (state.sessionTexts[sid]) view.stream.aiText = state.sessionTexts[sid];
    if (state.sessionThinkingBuffers[sid]) view.stream.thinkingText = state.sessionThinkingBuffers[sid];
    view.pagination.offset = msg.offset;
    view.pagination.total = msg.total;
    view.pagination.hasMore = msg.hasMore;
    // Fresh load cycle → fresh (bounded) back-fill budget.
    entry.backfill.pages = 0;
    entry.backfill.rows = 0;
    entry.backfill.startedAt = Date.now();
    entry.backfill.active = !!msg.hasMore;

    setActiveView(view);
    // #346 boundary fix: mid-turn tail stays flat when the agent is still
    // active (running/thinking/tool/frozen/stuck) — terminal event gathers it.
    const busyTail = ['running', 'thinking', 'tool', 'frozen', 'stuck'].includes(entry.meta.status);
    restoreFromBackendHistory(msg.messages, { busyTail });
    // 九点⑦：首刷回执含派发 brief（首条 delegate 注入行全文）→ 详情条任务区回填。
    fillStripTaskBody(msg.sessionId, msg.messages);

    requestAnimationFrame(() => {
      entry.container.scrollTop = entry.container.scrollHeight;
    });
    // Bounded head-seek: the injected task prompt — and any other early row —
    // sits at index 0, which the tail page never reached. Walk back page by
    // page until index 0 is in hand or a ceiling trips.
    if (entry.backfill.active && !requestOlderPage(entry, { budgeted: true })) {
      entry.backfill.active = false;
    }
    return true;
  }

  // Older page → prepend. Same guard as the primary window's scroll-up path:
  // skip a response that is not older than what is already rendered (a
  // duplicate offset-0 page is NOT skipped — offset 0 is the oldest page).
  if (msg.offset >= view.pagination.offset) return true;
  // Panel closed mid-walk: stop fetching instead of filling a hidden
  // container. The next full load re-runs a fresh bounded cycle.
  if (!view.visible) { entry.backfill.active = false; return true; }

  const bf = entry.backfill;
  bf.pages += 1;
  bf.rows += (msg.messages || []).length;

  const prevScrollHeight = entry.container.scrollHeight;
  const prevScrollTop = entry.container.scrollTop;
  // restoreFromBackendHistory renders into activeView.dom.chat — point the view
  // at a detached staging div for the duration of the call (the primary
  // window's scroll-up path swaps the same way), then move the rendered rows
  // to the top and restore the reading position.
  const prevActive = activeView;
  setActiveView(view);
  const origChat = view.dom.chat;
  const stage = document.createElement('div');
  view.dom.chat = stage;
  restoreFromBackendHistory(msg.messages, { scrollToBottom: false });
  view.dom.chat = origChat;
  if (prevActive !== view) setActiveView(prevActive);
  const fragment = document.createDocumentFragment();
  while (stage.firstChild) {
    const child = stage.firstChild;
    // `classList` lives on Element, not on ChildNode; text nodes keep the old
    // "no classList ⇒ skip" branch and any Element (incl. SVG) is still tagged.
    if (child instanceof Element && child.classList.contains('row')) child.classList.add('prepend-skip-anim');
    fragment.appendChild(child);
  }
  entry.container.prepend(fragment);
  entry.container.scrollTop = prevScrollTop + (entry.container.scrollHeight - prevScrollHeight);
  view.pagination.offset = msg.offset;
  view.pagination.hasMore = msg.hasMore;
  if (!msg.hasMore) bf.active = false;
  if (bf.active && !requestOlderPage(entry, { budgeted: true })) bf.active = false;
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
      // Restrained stuck label (manage-panel, 2026-08-22) — ui-status-redesign
      // （盘点 #13，S2 同构）：词前 timer-off 红图标 + 中性字。
      // Hard-recovery P7 (2026-09-07): label mirrors the REAL backend action.
      taskEl.innerHTML = statusIcon('timer-off', 11, 'st-err') + '<span class="fa-task-text">' + (
        entry.meta.stuck.action === 'restart'
          ? esc(t('manage.stuckAutoRestart'))
          : entry.meta.stuck.action === 'hard-abort'
            ? esc(t('manage.stuckHardAbort'))
            : entry.meta.stuck.action === 'failed'
              ? esc(t('manage.stuckFailed'))
              : esc(t('manage.stuck', { secs: entry.meta.stuck.idleSecs ?? '' }))
      ) + '</span>';
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
