import { renderProviderFailureStatus } from './providerFailureStatus.js';
// bgAgentPopup.js — Live message viewer for background sub-agents.
//
// When an agent uses the Delegate/SubTask tool, sub-agent events arrive with
// nodeSessionId = "delegate-<agentName>-<uuid>" / "subtask-<uuid>" (backend protocol). This
// module intercepts those events in ws.js (via setBgAgentStepInterceptor)
// and renders them into a popup ChatView — the same pattern as flowAgentPopup.js.
//
// The user opens the popup by clicking the bg-agent dropdown entry.

import { ChatView, setActiveView, activeView, chatViews } from './chatView.js';
import { sendWs, onMessage, onReconnect, setBgAgentStepInterceptor } from './ws.js';
import { listScopedTasks, onDurableTasksChanged, refreshScopedTasks } from './delegateChainProvider.js';
import { taskExecutionPresentation } from './subagentRevision.js';
import { restoreFromBackendHistory } from './persistence.js';
import { isBgAgentId, truncateMiddle, shouldFollowBottom, initScrollFollow, statusIcon } from './utils.js';
import state from './state.js';
import { key } from './branding.js';
import { t } from './i18n.js';
import { buildManageBar, bindManageActions, syncManageControls, onCancelResult, isFailedSnapshotStatus } from './managePanel.js';
import { isErrorReason, errorTileText, errorIcon, reasonText } from './errorRecovery.js';
// 冻结态误报批（2026-11-05）：页脚冻结词按呈现分类分词（自动恢复族 vs 需人介入族）。
import { FREEZE_KIND, freezeKindOf } from './freezeState.js';
import { cleanupCardIframes } from './cardRegistry.js';
// 两层架构批（2026-10-09 · 规格 §A 净化）：弹窗只留 G4 单查一个数据缝——星型区
// （G3 children）迁任务列表（§B）、flow 节点区迁 L1 委托流程图（§C）、归档动作
// 全后台化（引擎终态自动归档 + L2 抽屉管理）。收起式摘要条 = 九点⑦承载面平移的
// 可见性保底（作者裁定：弹窗保留收起式摘要条承载 brief/结果）。
import { fetchDelegateDetail, isDelegateDomainId } from './delegateApi.js';
// 修订批（九点⑦ · 2026-10-09）：判词→状态映射（结果区词面）——stub 读取口单源。
import { verdictToStatus } from './delegateChainStub.js';
// #752 面板进展可见化：弹窗内「会话在做什么」条（最近动作 + 本会话后台任务行，
// 点击行 = 输出详情卡）。挂载/卸载与 openStepPopup/closeStepPopup 成对。
import { mountSessionWorkStrip, unmountSessionWorkStrip } from './bgAgentWorkStrip.js';
// 弹窗退场批（作者令 2026-10-31②）：手机层查看器铺满屏 + 左上返回（桌面零变化）。
import { mobileSheetBack, isMobileLayer } from './mobile/popSheet.js';
// 手机端简化批（作者令 2026-11-06 10:00/12:03）：手机层 chrome 化——顶带状态胶囊
// / ⋯ 详情浮层 / 右下悬浮终止钮 / 标题行 ✕ 退役；摘要条与「会话在做什么」条迁入
// ⋯ 详情（六条口径见 bgAgentMobileChrome.js 文件头）。桌面零变化（模块首闸
// body.mobile-layer；返回 null = 桌面原路径）。
import { mountBgAgentMobileChrome, closeBgAgentMobileChrome } from './bgAgentMobileChrome.js';

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
/* panel-ia 批（2026-11-03）：摘要条材质/字阶全面走 --ds-* 槽（原来裸灰值 +
   非阶梯字号），条内两层（收起行 / 展开体）语言同源。 */
.bgagent-delegate-strip {
  border-bottom: var(--ds-border-width) solid var(--ds-border-hairline);
  padding: var(--ds-space-2) var(--ds-space-3);
  font-size: var(--ds-fs-hint);
}
/* 模型粒度批（作者令 2026-11-06 20:36「点击具体的对话框里去选择切换」）：弹窗头
   模型**单切**下拉（会话级 override，WS setSessionModel 单点）——类切 = 同类全部
   会话一起变（/model 面板 / 设置面）；这里是单会话粒度。
   ── 下拉收编批（作者令 2026-11-07「选择模式的下拉有bug，不美观」）────────────
   断因：本块自己画了一套原生 select 的脸（透明底 + 裸 hairline 边 + 8px 圆角 +
   零字族 + 零上下内距），类名末段是 switch ⇒ 没进任何输入面成员表 ⇒
   全站唯一输入面标准（css/inputSurface.css）根本管不到它：实测 16px 高、11px 字、
   原生箭头 + 自画边框两套箭头语言并存，这就是作者看到的「不美观」。
   修法：造型全部交还标准（裸件档 + 下拉档，本类已登记进
   css/inputSurface.css 的两个成员表），本块**只留几何**（flex/宽度上限/字档/
   纵向内距）——底/边/圆角/影/箭头/焦点环一处都不许在这里写（I2 判据）。 */
.bgagent-external-model-input { color: var(--color-text); }
.bgagent-model-switch {
  flex: 0 1 auto;
  min-width: 0; max-width: 14rem;
  padding-block: var(--ds-space-half); padding-left: var(--ds-space-2);
  font-size: var(--ds-fs-meta); color: var(--ds-text-muted);
  text-overflow: ellipsis;
}
/* 头部布局（作者令 2026-11-07「窗口关闭按钮应该靠右」）：名字槽吃掉全部剩余宽度，
   模型控件与关闭键因此被推到行尾（右缘对齐）。只挂 .bgagent-overlay 作用域——
   姊妹弹窗 flowAgentPopup 的头部（有 .flow-agent-subtitle 自带 margin-right:auto）
   逐像素不变。 */
.bgagent-overlay .flow-agent-header .flow-agent-name { margin-right: auto; }
/* 收起式摘要条（WF-01）：默认收起 = 一行（任务 chip + brief 摘要 + 结果词 +
   展开 chevron）；点开 = 任务 brief 全文 + 判词 + 报告全文（九点⑦正文不丢）。 */
.bgagent-strip-toggle {
  display: flex; align-items: center; gap: var(--ds-space-2); min-width: 0; width: 100%;
  padding: var(--ds-space-half) 0; border: none; background: transparent; color: inherit;
  font: inherit; text-align: left; cursor: pointer;
}
.bgagent-strip-toggle:hover .bgagent-strip-briefline { color: var(--color-text, inherit); }
.bgagent-strip-chip {
  flex-shrink: 0; padding: var(--ds-space-hair) var(--ds-space-2); border-radius: var(--ds-radius-input);
  font-size: var(--ds-fs-meta); font-weight: var(--ds-fw-title);
  letter-spacing: var(--ds-ls-body);
  color: var(--ds-text-muted);
  background: var(--color-frame-hover);
}
.bgagent-strip-briefline {
  flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--ds-text-muted);
}
.bgagent-strip-result {
  flex-shrink: 0; font-weight: 500; color: var(--color-text, inherit);
}
.bgagent-strip-result[hidden] { display: none; }
.bgagent-strip-chev {
  flex-shrink: 0; width: 12px; height: 12px; display: inline-flex;
  color: var(--ds-text-dim);
  transition: transform var(--ds-dur-hover) var(--ds-ease-std);
}
.bgagent-strip-chev svg { width: 12px; height: 12px; display: block; }
.bgagent-strip-toggle[aria-expanded="true"] .bgagent-strip-chev { transform: rotate(180deg); }
.bgagent-strip-body {
  margin-top: var(--ds-space-2); padding-top: var(--ds-space-2); border-top: var(--ds-border-width) dashed var(--ds-border-hairline);
  max-height: 38vh; overflow-y: auto;
  display: flex; flex-direction: column; gap: var(--ds-space-half);
}
.bgagent-strip-body[hidden] { display: none; }
.bgagent-strip-plugins {
  color: var(--ds-text-dim);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.bgagent-strip-sec {
  display: flex; flex-direction: column; gap: var(--ds-space-half); min-width: 0;
}
/* hidden 属性守卫（修订批截图期发现）：author display 规则会压过 UA 的
   [hidden]{display:none}——结果区（在飞无判词）收起时以空壳区透出。零空区
   纪律（数据到位才显）靠本守卫成立。toggle 亦在册（任务面去重批 2026-11-07：
   桌面结果条在飞期整行缺席，flex 声明同样会压掉 [hidden]）。 */
.bgagent-strip-sec[hidden],
.bgagent-strip-verdict[hidden],
.bgagent-strip-toggle[hidden] { display: none; }
.bgagent-strip-sec-label {
  font-weight: var(--ds-fw-title); font-size: var(--ds-fs-meta);
  letter-spacing: var(--ds-ls-body);
  color: var(--ds-text-dim); flex-shrink: 0;
}
.bgagent-strip-tasktext {
  font-size: var(--ds-fs-hint); line-height: var(--ds-lh-body); color: var(--ds-text);
  white-space: pre-wrap; word-break: break-word;
  max-height: 88px; overflow-y: auto;
}
.bgagent-strip-verdict {
  display: flex; align-items: baseline; gap: var(--ds-space-2); min-width: 0;
  font-size: var(--ds-fs-hint); color: var(--ds-text);
}
.bgagent-strip-verdict-word { flex-shrink: 0; font-weight: 500; }
.bgagent-strip-verdict-reason {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--ds-text-muted);
}
.bgagent-strip-report {
  font-size: var(--ds-fs-hint); line-height: var(--ds-lh-body); color: var(--ds-text);
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
 * @param {{ hideTask?: boolean }} [opts] 任务面去重批（作者令 2026-11-07「顶部的任务
 *   可以不显示，因为蓝色气泡里已经包含了」）：桌面头部**不再承载任务**——任务全文的
 *   权威呈现面 = 会话流里的注入气泡（点来源行可展开逐字全文，见
 *   js/chat/injectedRows.js「Default-collapsed」段）+ 页脚 .fa-task 单行。桌面条因此
 *   收成**结果条**（判词/报告/批次 plugins），在飞期（无判词无报告）整条缺席 =
 *   作者要的「顶部不显示任务」形态。手机层不动（⋯ 详情里的任务全文是 2026-11-06
 *   手机简化批口径③的载力面，收进详情正是为了不在主界面重复）。
 */
function mountDelegateStrip(nodeSessionId, modal, chatContainer, opts) {
  if (!isDelegateDomainId(nodeSessionId)) return;
  const hideTask = !!(opts && opts.hideTask);
  const strip = document.createElement('div');
  strip.className = 'bgagent-delegate-strip';
  strip.dataset.delegateStrip = nodeSessionId;
  if (hideTask) strip.dataset.taskFace = 'hidden'; // 任务面去重批：桌面条不承载任务
  strip.hidden = true; // 数据到位才显（零空条）
  modal.insertBefore(strip, chatContainer);

  // 收起态一行：任务 chip + brief 摘要 + 结果词 + chevron（手机层）；
  // 桌面（hideTask）= 结果 chip + 结果词 + chevron（任务面整体不建）。
  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = 'bgagent-strip-toggle';
  toggle.setAttribute('aria-expanded', 'false');
  const chip = document.createElement('span');
  chip.className = 'bgagent-strip-chip';
  chip.textContent = t(hideTask ? 'flowmap.archive.resultLabel' : 'flowmap.archive.taskLabel');
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
  if (!hideTask) toggle.appendChild(briefLine);
  toggle.appendChild(resultWord);
  toggle.appendChild(chev);
  if (hideTask) toggle.hidden = true; // 有结果才出（在飞期整条缺席）
  strip.appendChild(toggle);

  // 展开态正文：plugins 数据行（G4）+ 任务 brief 全文（仅手机层）+ 结果（判词/报告）。
  const body = document.createElement('div');
  body.className = 'bgagent-strip-body';
  body.hidden = true; // 默认收起（WF-01）
  const plugins = document.createElement('div');
  plugins.className = 'bgagent-strip-plugins';
  body.appendChild(plugins);

  let taskText = null; // 桌面 hideTask ⇒ null（fillStripTaskBody 侧零写点）
  if (!hideTask) {
    const taskSec = document.createElement('div');
    taskSec.className = 'bgagent-strip-sec';
    const taskLabel = document.createElement('span');
    taskLabel.className = 'bgagent-strip-sec-label';
    taskLabel.textContent = t('flowmap.archive.taskLabel');
    taskSec.appendChild(taskLabel);
    taskText = document.createElement('div');
    taskText.className = 'bgagent-strip-tasktext';
    taskText.hidden = true; // 正文到位才显（零空区）
    taskSec.appendChild(taskText);
    body.appendChild(taskSec);
  }

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
    // 任务 brief（手机层承载；桌面 hideTask ⇒ 三处写点全空转）：G4 task 键 =
    // 台账标题（截断形）——先占位（收起行 + 全文同源），正文由历史回执回填
    // （fillStripTaskBody 单点；历史不可达时标题即诚实降级面）。
    // 回执竞态守卫：历史 brief 已到（briefSource=history）→ 台账标题不再回写
    // （正文唯一权威在历史——台账标题只是历史不可达时的降级占位）。
    const ledgerTask = detail && detail.task && typeof detail.task === 'object' ? detail.task : null;
    const ledgerTitle = String((ledgerTask && ledgerTask.title) || (record && record.title) || '');
    if (!hideTask && ledgerTitle && strip.dataset.briefSource !== 'history') {
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
    // 任务面去重批（作者令 2026-11-07）：桌面条现在只有「结果」一件事可承载 ⇒
    // 无判词无报告时整条缺席（在飞期 = 顶部落空，零空条纪律）；有结果才出。
    if (hideTask) {
      const hasResult = !resultSec.hidden;
      toggle.hidden = !hasResult;
      strip.hidden = !hasResult;
    } else {
      strip.hidden = false;
    }
  }).catch(() => { /* fail-soft：条缺席，弹窗主体不受影响 */ });
}

/**
 * 任务 brief 正文回填（九点⑦单点）：getHistoryPage 首刷回执里首条 delegate 注入行
 * = 派发 brief 全文（引擎侧唯一持久正文面）。弹窗已切走 → 静默弃；条缺席 → 弃。
 * 回填双写点 = 收起态一行摘要 + 展开态正文（同一文本，两层呈现）。
 * 任务面去重批（2026-11-07）：桌面条不建这两处 DOM（hideTask）⇒ 查询全落空、
 * 本函数在桌面是空转（数据集位 briefSource 照旧写，G4 侧竞态守卫口径不变）。
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
// 手机端简化批：手机层 chrome 的活跃 api（状态胶囊 refresh 直呼口；桌面恒 null）。
let bgMobileChromeApi = null;

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
    bgIndicatorEl: null, bgCountEl: null,
    bgDropdownEl: null, bgDropdownListEl: null,
    bgagentIndicatorEl: null, bgagentDropdownEl: null, bgagentDropdownListEl: null,
    sessionNameEl: null,
  };
  const view = new ChatView('bgagent-' + sessionId, fakeDom);
  view.mounted = true;
  view.sessionId = sessionId;
  renderProviderFailureStatus(view, state.freezeDigest, id => openStepPopup(id));
  // （c）面（作者 2026-10-08 裁定 · 定向重开）：subagent 弹窗的**在飞过程行**走
  // 9.19 裸行形态——turn-line 剧场（workline 单行呈现 + 过程卡预 tucked，10-03
  // stream-ux）只属主窗。弹窗在飞 = thinking 气泡裸流（§③ 5 行窗上限照钉）+
  // 工具卡裸行可见（9.19 同形），弹窗内不建 turn-line；终态收拢（turn-header
  // + tuckRows）与主窗同一 9.19 统一面（883712c40），零变化。chat.js / main.js
  // 各在飞渲染点读此标志分流；主窗与 flow 弹窗不置位 ⇒ 呈现零触碰。
  (/** @type {{nfBareProcessRows?: boolean}} */ (view)).nfBareProcessRows = true;
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

let durableHistoryTimer = null;
const dirtyDurableHistories = new Set();
function refreshDurablePopupHistory() {
  const entry = stepViews.get(currentStepId);
  if (!entry?.view.visible) return;
  if (entry.view.pagination.pendingInitialLoad) {
    dirtyDurableHistories.add(currentStepId);
    return;
  }
  dirtyDurableHistories.delete(currentStepId);
  entry.view.pagination.pendingInitialLoad = true;
  sendWs({ type: 'getHistory', sessionId: currentStepId, limit: BG_PAGE_LIMIT });
}

function applyDurableTaskMeta(entry, row) {
  const execution = taskExecutionPresentation(row);
  if (!execution) return;
  entry.meta.executionStatus = row.executionStatus;
  entry.meta.terminalReason = row.terminalReason;
  entry.meta.incomplete = row.incomplete === true;
  entry.meta.status = execution.state === 'pending-recovery' ? 'stopped'
    : execution.state === 'error' ? 'failed'
      : execution.state === 'done' ? 'done' : 'running';
}

onDurableTasksChanged((rows) => {
  const row = rows.find(task => task.taskId === currentStepId);
  if (!row) return;
  const entry = stepViews.get(currentStepId);
  if (entry && row.executionStatus) {
    applyDurableTaskMeta(entry, row);
    updateFooterStatus(entry);
  }
  if (durableHistoryTimer) return;
  durableHistoryTimer = setTimeout(() => {
    durableHistoryTimer = null;
    refreshDurablePopupHistory();
  }, 150);
});
onReconnect(refreshDurablePopupHistory);

export function openStepPopup(nodeSessionId, agentName, taskDescription) {
  closeStepPopup();
  currentStepId = nodeSessionId;

  const entry = ensureStepView(currentStepId);
  entry.view.visible = true;
  // Permission matrix: bg popups are Delegate/SubTask/Ephemeral (operable);
  // snapshot kind from activeAgents refines when present (backend gap: kind
  // arrives via sessionBgAgents once main.js stores it).
  entry.meta.kind = entry.meta.kind || 'Delegate';
  const durable = listScopedTasks(state.activeSessionId).find(row => row.taskId === nodeSessionId);
  if (durable) {
    applyDurableTaskMeta(entry, durable);
    entry.meta.startedAt = durable.spawnedAt;
    entry.meta.kind = durable.source === 'external' ? 'External' : entry.meta.kind;
  }
  refreshScopedTasks();
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
        else if (row.status === 'Frozen') {
          entry.meta.status = 'frozen';
          // 冻结态误报批（2026-11-05）：呈现分类随快照恢复（缺字段 ⇒ freezeState.js
          // 按原因线值回落同一张表）。没有这三行，刷新后的冻结页脚只能从 reason 猜。
          entry.meta.freezeKind = row.frozenKind || '';
          entry.meta.needsHuman = row.needsHuman === true;
          entry.meta.freezeReasonWire = row.freezeReasonWire || '';
        }
        // held-receipt 投递可靠性批（2026-10-21 fix 4）：显式停靠态快照恢复——
        // 「等合并轮」的 delegate 行不再貌似 Idle。
        else if (row.status === 'Waiting') entry.meta.status = 'waiting';
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
  // `bgagent-overlay` = 手机端简化批的作用域钩（mobileLayer.css 的手机 chrome 档
  // 全挂 body.mobile-layer .bgagent-overlay —— 姊妹弹窗 flowAgentPopup 零波及）。
  popupOverlay.className = 'flow-agent-overlay fullscreen bgagent-overlay';
  lastModelBadgeHtml = null; // fresh badge element — force first render on open
  // 下拉收编批（2026-11-07 作者报障「选择模式的下拉有bug」= 关一次再开下拉全空）：
  // 根因 = 本守卫是**模块级**的，open 时不复位 ⇒ 第二个弹窗拿到新 <select>（零选项）
  // 却因 sig 与上个弹窗相同而整段跳过填充，下拉表 empty。开窗即复位（与上行
  // lastModelBadgeHtml 同一纪律：新 DOM 必须重绘一次）。
  lastModelSwitchSig = null;
  popupModelCfg = null; // 配置属于本次会话，不能沿用上一个窗口的异步结果

  // Mount on document.body for bg-agent popups (not inside a flow card)
  // panel-ia 批（2026-11-03 · 作者报障「信息太多、太杂」+ 竖排字实拍）：弹窗头收敛为
  // 单一身份行（名字 + 模型徽标 + 关闭）——原第二行 .flow-agent-subtitle 承载的任务
  // 全文与页脚 .fa-task / delegate 摘要条 briefline 三处重复，属同一信息的二次上屏；
  // 退役头部副本后（任务全文单源 = 页脚），头部在窄屏不再把名字挤成逐字竖排
  // （.flow-agent-name 抗压缩契约见 POPUP_CSS 同批注释）。功能零删：页脚与摘要条
  // 两承载面原样保留。
  // 3(d) 按钮去重（panel-language 批 2026-11-06）：手机层返回键在（下方
  // mobileSheetBack 注入 .mfs-back）⇒ 头部 ✕ 不建（作者令「已经有返回键了，就不要
  // 再放叉号键（X）」）；桌面层无返回键 ⇒ ✕ 保留（点外关闭路径不变）。模板每次
  // open 重建 ⇒ 守卫逐次评估。🔴 守卫与 ✕ 同行相邻（机检 P2 = scripts/
  // check-panel-unify.mjs；判定单源 = popSheet.isMobileLayer，禁自写）。
  // 手机端 chrome 化批同期口径（3(d) 按钮去重）不变；下拉收编批（作者令
  // 2026-11-07）另补两条：① ✕ 由 `<div>` 改 `<button>`（键盘可达 = Tab 到 +
  // Enter/Space 触发，焦点环取 --ds-focus-ring 单源；此前是纯点击件，键盘用户
  // 关不掉窗口）；② 靠右改由 `.flow-agent-name { margin-right: auto }` 实现
  // （见上方注入样式块），关闭键因此贴行尾右缘。`id` 不变 ⇒ 既有点击腿、
  // 探针与规格（bg-merged-panel / subagent-panel-open-geometry）零改动。
  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-name">${esc(agentName || entry.meta.agentName || 'Sub-agent')}</span>
        <span class="flow-agent-model" id="bgagent-model"></span>
        <select class="bgagent-model-switch" id="bgagent-model-switch" aria-label="${esc(t('delegate.modelSwitch'))}" title="${esc(t('delegate.modelSwitch'))}"></select>
        ${isMobileLayer() ? '' : `<button type="button" class="flow-agent-close" id="bgagent-close" aria-label="${esc(t('modal.close'))}" title="${esc(t('modal.close'))}">✕</button>`}
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
  renderModelBadge();
  if (nodeSessionId.startsWith('external-')) mountExternalModelControl(popupOverlay, nodeSessionId); // 即使没有 agentName 或模型配置请求失败，也先建立可操作的选项表
  mobileSheetBack(popupOverlay, closeStepPopup);

  const modal = /** @type {HTMLElement} */ (popupOverlay.querySelector('.flow-agent-modal'));

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

  // 手机端简化批（作者令 2026-11-06 10:00/12:03）：手机层 chrome 化——顶带状态
  // 胶囊 / 右上 ⋯ 详情浮层 / 右下悬浮终止钮 / 标题行 ✕ 退役；stopBtn/retryBtn 是
  // **同一节点**迁位（syncManageControls 线路零复制）。桌面 ⇒ null，零影响。
  bgMobileChromeApi = mountBgAgentMobileChrome({
    overlay: popupOverlay, modal, bar, nodeSessionId, agentName,
  });

  // delegate 收起式摘要条（两层架构批 WF-01：brief 摘要一行 + 点开 brief/结果
  // 全文）——桌面挂弹窗头与聊天体之间；手机层迁入 ⋯ 详情浮层
  // （口径③任务内容全文在详情里；自动展开一次，二级折叠保留）。
  // 任务面去重批（作者令 2026-11-07「顶部的任务可以不显示，因为蓝色气泡里已经
  // 包含了」）：桌面传 hideTask（条收成结果条，任务只由蓝色气泡 + 页脚承载）；
  // 手机层不传（⋯ 详情里的任务全文是口径③的载力面，主界面本来就没有它）。
  // #752「会话在做什么」条同迁（口径⑥：灰行——最近动作 / 本会话无后台任务——
  // 收进 ⋯ 详情，主界面只留顶栏 + 消息流 + 悬浮终止钮）。15s 自刷新拍、关闭即停。
  const stripHost = bgMobileChromeApi ? bgMobileChromeApi.sheetBody : modal;
  const stripAnchor = bgMobileChromeApi ? bgMobileChromeApi.sheetAnchor : entry.container;
  mountDelegateStrip(nodeSessionId, stripHost, stripAnchor, { hideTask: !bgMobileChromeApi });
  if (bgMobileChromeApi) bgMobileChromeApi.autoExpandStrip(bgMobileChromeApi.sheetBody, nodeSessionId);
  if (stripHost instanceof HTMLElement) {
    mountSessionWorkStrip(nodeSessionId, stripHost, stripAnchor);
  }

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
  closeBgAgentMobileChrome(); // 手机端简化批：chrome 账本（胶囊节拍）随弹窗关闭停（幂等）
  bgMobileChromeApi = null;
  unmountSessionWorkStrip(); // #752「会话在做什么」条随弹窗关闭停拍、摘除（幂等）
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

// ── Model badge (context usage ring retired 2026-10-23) ──────────────────

// Update the popup model badge when model info arrives for any session.
// (弹窗头部用量环已随 2026-10-23 上下文用量显示退役批删除 ——
// 防复活钉：tests/remove-context-usage.spec.mjs。)
onMessage('usageUpdate', () => { if (popupOverlay) renderModelBadge(); });
onMessage('done', () => { if (popupOverlay) renderModelBadge(); });
// main.js 保存 modelOptions；等同一帧的所有监听器执行后再读取，避免打开时选项迟到而不刷新。
onMessage('modelOptions', () => { queueMicrotask(() => { if (popupOverlay) renderModelBadge(); }); });
onMessage('sessionModelSet', (msg) => {
  if (popupOverlay && msg.sessionId === currentStepId) queueMicrotask(() => renderModelBadge());
});

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
    // 冻结态误报批（2026-11-05）：呈现分类随帧到（后端 FreezeDisplay 判定）。
    entry.meta.freezeKind = msg.freezeKind || '';
    entry.meta.needsHuman = msg.needsHuman === true;
    entry.meta.freezeReasonWire = msg.reason || '';
    entry.meta.escalation = msg.escalation ? msg.escalation.level : null;
  } else if (msg.type === 'agentResumed') {
    entry.meta.status = 'running';
    entry.meta.frozenResumeAt = null;
    entry.meta.freezeReason = null;
    entry.meta.freezeKind = '';
    entry.meta.needsHuman = false;
    entry.meta.freezeReasonWire = '';
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
    if (dirtyDurableHistories.has(msg.sessionId)) {
      dirtyDurableHistories.delete(msg.sessionId);
      setTimeout(refreshDurablePopupHistory, 0);
    }
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
  // 冻结态误报批（2026-11-05）：冻结页脚按**呈现分类**分词——
  //   · 需人介入族（循环检测 / 错误冻结 / 已升级到人）：amber 原因词（既有 UI-7），
  //     恢复入口在页脚管理条（managePanel 的「重启并续跑」）；
  //   · 自动恢复族（时间表/退避/探测/断点续跑）：只写「在等什么 + 会自己续跑」，
  //     绝不写「已冻结」（那是需人工措辞——作者报障的原文就是被这句话骗的）。
  const freezeKind = status === 'frozen' ? freezeKindOf(entry.meta) : '';
  const isErrorFrozen = status === 'frozen' && freezeKind === FREEZE_KIND.manual;
  // UI-7: error-recovery frozen → amber tile (add .error modifier).
  if (isErrorFrozen) entry.footerEl.classList.add('error');
  if (taskEl) {
    if (status === 'frozen') {
      if (!isErrorFrozen) {
        // 自动恢复族：状态事实（原因 + 在等什么），零按钮。
        const why = reasonText(entry.meta.freezeReason) || '';
        taskEl.textContent = freezeKind === FREEZE_KIND.window
          ? t('chat.freezeWindowTile')
          : (why ? t('chat.freezeAutoTile', { reason: why }) : t('chat.freezeAutoTile', { reason: '' }).replace(/\s*·\s*$/, ''));
      } else if (isErrorReason(entry.meta.freezeReason)) {
        // 需人介入 + 错误族原因（循环检测 L2 / 已升级到人）：amber 原因词自带判词
        // （loop ⇒「循环检测暂停 · 不会自动恢复」），恢复入口在页脚管理条。
        const escTxt = errorTileText(entry.meta);
        taskEl.innerHTML = `${errorIcon(entry.meta.freezeReason, 'error-icon')}<span class="fa-task-text">${escTxt}</span>`;
      } else {
        // 需人介入但非错误族（delegate 错误冻结 / 原因未记录）：如实说「需处理」。
        taskEl.textContent = t('subagents.freezeBadge');
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
  const durableExecution = taskExecutionPresentation(entry.meta);
  if (taskEl && durableExecution) {
    taskEl.title = durableExecution.reason;
    if (durableExecution.reason) taskEl.textContent = durableExecution.reason;
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
    const execution = taskExecutionPresentation(entry.meta);
    const text = execution?.label || phaseMap[status] || '';
    phaseEl.textContent = text;
    // Unified panel spec §7: long text → single-line ellipsis + title with
    // the full text (hover tooltip). Non-tool phases are short — no tooltip.
    phaseEl.title = execution ? [execution.label, execution.reason].filter(Boolean).join(' · ')
      : (status === 'tool' && fullLabel) ? `Using tool: ${fullLabel}` : '';
    phaseEl.style.display = text ? '' : 'none';
  }
  // Management cluster: state-linked buttons + permission matrix.
  syncManageControls(entry.manageBar, entry.meta, currentStepId);
  // 手机端简化批：顶带状态胶囊与页脚同一拍刷新（词面/时长/动作区开合）。
  if (bgMobileChromeApi) bgMobileChromeApi.refresh(entry.meta);
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
// 转义（下拉收编批 2026-11-07 顺带修）：原实现五条 replace 全都把字符换成**它自己**
// （`&` → `&`、`<` → `<`…），等于什么都没转义——agent 名/任务行里出现 `<` 时
// 模板串 innerHTML 会被它截断。按 HTML 文本/属性双料口径补齐实体，`&` 先行。
function esc(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
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
  renderModelSwitch(); // 模型粒度批：单切下拉与徽标同帧刷新（自带值变化守卫）
  const el = popupOverlay?.querySelector('#bgagent-model');
  if (!el) return;
  const live = currentStepId ? state.sessionModelInfo[currentStepId]?.model : null;
  const cfg = popupModelCfg || {};
  const current = live || cfg.current || cfg.preferred || '';
  let html = modelBadgeHtml(current, cfg.preferred);
  // 一并收掉重复串（下拉收编批 2026-11-07 作者报障「不美观」的实拍构成）：下拉
  // 选的当前值本身就等于 current（选项表由 current 领衔）⇒ 徽标再印一遍同一串
  // 就是同一个模型名在标题行出现两次。徽标只在**它说的是另一件事**时出现：
  // 会话实际在用 B 而下拉已指向 C（下一 turn 才生效 / 引擎回落），此时 B 是有
  // 增量的信息（#308 口径：不许把配置值当实际值展示）。
  const shown = popupOverlay?.querySelector('#bgagent-model-switch')?.value || '';
  if (shown && current === shown) html = '';
  // 空徽标不留位（flex gap 会给零宽件也留一格缝）：徽标是条件件，无内容即隐形。
  el.hidden = !html;
  if (html === lastModelBadgeHtml) return; // unchanged — no DOM write
  lastModelBadgeHtml = html;
  el.innerHTML = html;
}

function mountExternalModelControl(overlay, sessionId) {
  const executor = ['claude-code', 'codex', 'zcode'].find(id => sessionId.startsWith(`external-${id}-`));
  if (!executor) return;
  const select = overlay.querySelector('#bgagent-model-switch');
  select.hidden = true;
  const container = document.createElement('div');
  container.className = 'bgagent-delegate-strip';
  const label = document.createElement('label');
  label.textContent = t('delegate.externalModelFuture');
  const status = document.createElement('span');
  status.setAttribute('role', 'status');
  container.append(label, status);
  overlay.querySelector('.flow-agent-header').after(container);
  const token = localStorage.getItem(key('token')) || '';
  const headers = token ? { Authorization: `Bearer ${token}` } : {};
  const url = `/api/executors/${encodeURIComponent(executor)}/model`;
  fetch(url, { headers }).then(response => {
    if (!response.ok) throw new Error('load failed');
    return response.json();
  }).then(config => {
    if (!overlay.isConnected) return;
    if (!config.supported) { status.textContent = t('delegate.externalModelUnsupported'); return; }
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'bgagent-external-model-input';
    input.value = config.model || '';
    input.placeholder = t('delegate.externalModelDefault');
    input.setAttribute('aria-label', t('delegate.externalModelFuture'));
    const list = document.createElement('datalist');
    list.id = 'bgagent-external-model-suggestions';
    input.setAttribute('list', list.id);
    for (const suggestion of config.suggestions || []) {
      const option = document.createElement('option');
      option.value = suggestion;
      list.appendChild(option);
    }
    label.append(input, list);
    let saved = input.value;
    input.addEventListener('change', async () => {
      input.disabled = true;
      try {
        const response = await fetch(url, { method: 'PUT', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ model: input.value }) });
        if (!response.ok) throw new Error('save failed');
        const result = await response.json();
        saved = result.model;
        input.value = saved;
        status.textContent = t('delegate.externalModelSaved');
      } catch (_) {
        input.value = saved;
        status.textContent = t('delegate.externalModelError');
      } finally { input.disabled = false; }
    });
  }).catch(() => { if (overlay.isConnected) status.textContent = t('delegate.externalModelError'); });
}

// 模型粒度批（作者令 2026-11-06 20:36）：弹窗头**单切**下拉 = 单会话模型切换的
// 承载面（「点击具体的对话框里去选择切换」）。选项 = state.allModelRefs（WS
// modelOptions 帧，main.js 单点落 state）∪ 当前生效值；空值项 = 「跟随类别（默认）」
// ⇒ modelRef null（清会话 override，回落类别槽链/派发钉链/agent 自身链的既有
// 优先级）。发送 = WS `setSessionModel`（引擎 WsConfigHandlers 单点：解析 ref →
// sessionModelOverrides；模型失效时只在面板显示 + 静默等待，零向上通知）。
let lastModelSwitchSig = null; // value-change guard — keep DOM stable

function renderModelSwitch() {
  const el = popupOverlay?.querySelector('#bgagent-model-switch');
  if (!el) return;
  const live = currentStepId ? state.sessionModelInfo[currentStepId]?.model : null;
  const cfg = popupModelCfg || {};
  const selected = state.sessionModelSelections?.[currentStepId];
  const current = selected !== undefined ? selected : (live || cfg.current || cfg.preferred || '');
  const refs = (state.allModelRefs || []).slice();
  if (current && !refs.includes(current)) refs.unshift(current);
  const sig = current + '|' + refs.join(',');
  // 守卫双条件：sig 未变**且**这个 DOM 实例已经填过选项。只看 sig 会在「换到另一个
  // 同模型同选项表的会话」时把新 <select> 留成空壳（与 open 复位互为冗余防线）。
  if (sig === lastModelSwitchSig && el.options.length) return;
  lastModelSwitchSig = sig;
  // DOM 构建器写法（component-contract R5：不裸写 innerHTML——选项文本全部
  // textContent，无净化面绕过）。
  el.textContent = '';
  const def = document.createElement('option');
  def.value = '';
  def.textContent = t('delegate.modelFollowSlot');
  if (!current) def.selected = true;
  el.appendChild(def);
  for (const r of refs) {
    const opt = document.createElement('option');
    opt.value = r;
    opt.textContent = r;
    if (r === current) opt.selected = true;
    el.appendChild(opt);
  }
  el.onchange = () => {
    const v = el.value || '';
    if (!currentStepId) return;
    sendWs({ type: 'setSessionModel', sessionId: currentStepId, modelRef: v || null });
    // Wait for the server acknowledgement; selecting is not a retry.
    el.value = current;
  };
}

/** Fetch agent model config and render a badge in the popup header. */
async function fetchAgentModelBadge(agentName) {
  const requestedOverlay = popupOverlay;
  const requestedSession = currentStepId;
  try {
    const token = localStorage.getItem(key('token')) || '';
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const resp = await fetch(`/api/agents/${encodeURIComponent(agentName)}/model`, { headers });
    if (!resp.ok) return;
    const cfg = await resp.json();
    if (popupOverlay !== requestedOverlay || currentStepId !== requestedSession) return;
    popupModelCfg = cfg;
    renderModelBadge();
  } catch (e) { /* non-critical */ }
}
