// main.js 抽取(FE组件化批次七 2026-09-28):后台代理下拉族 模块(行为保持)。
// 内网仓合并移植（2026-10-10）：面板体更新为本地 delegate-ui 两级化批（C2/R6 ·
// 定稿蓝本 §1.2/§2）与「修订批（用户九点②/⑤ · 2026-10-09）」语义——远端拆分时
// 携带的旧快照面板体由本地新版整体替换（SubTask→Delegate 显示词归一、C1 过渡
// 过滤、两级组头/子行、空态自动收起），本文件骨架（indicator/uptime/fetch/stuck
// 族）沿用远端抽取形态。
import state from '../state.js';
import { sendWs } from '../ws.js';
import { t } from '../i18n.js';
// 术语统一批（2026-11-06 作者令逐字「统一 根据英文」）：身份/类型徽标词**只**从单源
// 词表取（js/termWords.js）——本文件的三个产词函数（bgDelegateBadgeWord /
// bgAgentflowWord / bgMemberKindWord）不再自带词表、也不再走 locale 键族
// （subagents.kind* 已整族退役）。协议/来源数据词经 badgeFaceOfKind 单点归一。
import { badgeWord, badgeFaceOfKind, isMemberOnlyBadge } from '../termWords.js';
import { escapeHtml, isBgAgentId, createIconsIn, statusIcon, bgHealthNorm } from '../utils.js';
// 三行结构批（2026-11-05）：状态位的唯一发射点（图标 + 色调 + 无障碍文案单表）。
import { bgStatusHtml } from '../bgRowStatus.js';
import { findViewBySessionId, activeView, setActiveView, chatViews } from '../chatView.js';
import { fmtUptime, isFailedSnapshotStatus } from '../managePanel.js';
import { openStepPopup as openFlowStepPopup } from '../flowAgentPopup.js';
import { openStepPopup as openBgAgentPopup } from '../bgAgentPopup.js';
// 两级面板数据侧 stub 读取口（delegate-ui 批：rows/children/chain——provider 镜像；
// liveSource 挂载在 delegateChainProvider.js，由 main.js 侧 import 触发）。
import { listDelegateRows, listDelegateChildren, getChainByTask } from '../delegateChainStub.js';
// 模型粒度批（作者令 2026-11-06 20:36）：手动恢复两档的 REST 缝（逐个 / 一键全部）。
import { restoreDelegate, restoreAllDelegates } from '../delegateApi.js';
// 节点子智能体可见性批（2026-11-07）：节点父的 store 子条目补拉（provider 镜像的唯一
// 补拉口——读面仍是 delegateChainStub.listDelegateChildren 那一个）。
import { ensureChildren, listScopedTasks, refreshScopedTasks } from '../delegateChainProvider.js';
import { EVENTS } from '../events.js';
import { taskExecutionPresentation } from '../subagentRevision.js';
// 来源链批（作者令 2026-11-01 19:23）：孤儿行来源角标升级为名字链——父不可见行的
// 「挂在谁下面」标注读上层 delegate 名字（bgSourceChain 单点解析；解析不到回退
// 既有类型词）。
import { upperChainOf, chainNameOf, chainLabel } from '../bgSourceChain.js';
// 冻结态误报批（2026-11-05）：冻结行的呈现分类（自动恢复中 vs 需人工）单点判定。
// 纯函数叶子模块，零 DOM 零 import（scripts/check-freeze-state.mjs 直接跑它）。
import {
  FREEZE_KIND,
  isFrozenFamily,
  freezeKindOf,
  freezeReasonWire,
  showsRecoveryAction,
  wakeableOf,
  freezeBadgeKey,
  freezeReasonLabelKey,
  delegateFreezeWire,
  delegateFreezeIsInfra,
} from '../freezeState.js';

// --- Multi-agent events ---
// Background sub-agent activity shows a header indicator (like Bash background tasks).
// No tool cards or text rendered in the chat — the parent's Delegate tool
// card (spinner → result) is the only chat-level feedback.
// Click the indicator to see a dropdown with per-agent status.

/**
 * 作用域桶宿主反查（Flow 会话可见性批 2026-11-01）：delegate 作用域桶键 → 持有该
 * delegate 组头行的主桶 id。判据 = 主桶内存在 sessionId === 桶键 的行（引擎自根
 * 事实——DelegateFlowHosts.mount rootSessionId = delegateId——的显示面单点判读）。
 * 查无宿主 = live 行已离场，徽标回收走终态管线（main.js done 收尾 →
 * refreshAllBgAgentIndicators 全视图重算——见该处）。
 *
 * 导出面（终态管线共用）：main.js 会话级 done 收尾按桶键反查宿主视图。
 *
 * @param {string} delegateSid delegate 作用域桶键
 * @returns {string} 宿主主桶 id（'' = 未定位）
 */
export function bgScopeRootForBucket(delegateSid) {
  const d = String(delegateSid || '').replace(/^team-/, '');
  if (!d) return '';
  for (const [root, agents] of Object.entries(state.sessionBgAgents || {})) {
    if (String(root) === d) continue;
    for (const info of Object.values(agents || {})) {
      if (String((info && info.sessionId) || '').replace(/^team-/, '') === d) return String(root);
    }
  }
  return '';
}

export function updateBgAgentIndicator(targetSid, opts) {
  // Update the indicator of the view that DISPLAYS the session owning these
  // sub-agents — not blindly activeView. Sub-agent events are routed through
  // the popup's ChatView (activeView temporarily points there; popup views
  // have no indicator element), which previously left the primary badge stale
  // while the dropdown re-rendered from live data on click — showing e.g.
  // count=2 on the badge but 3 rows inside (a stuck sub-agent that emits no
  // further events never triggered another badge refresh).
  // Flow 会话可见性批（2026-11-01）：delegate 作用域桶（rootSessionId = delegate-*）
  // 不是任何视图的桶——链行挂在主桶 delegate 组头下，宿主视图经 bgScopeRootForBucket
  // 反查刷新（agentStart/agentToolStart/agentDone/会话级 done 各路径共用本缝；
  // 查无宿主 = live 行已离场，徽标回收走终态管线）。
  // 修订（同批，T1 实证）：反查门不是「查无视图」而是「查无可刷新视图」——链帧
  // 进场前拦截器（interceptBgAgentStep.ensureStepView）已为 delegate 会话本身建了
  // 隐藏 step 视图（chatViews['bgagent-<sid>']），findViewBySessionId(delegateSid)
  // 会抢先命中它，而 step 视图无指示器元素（旧代码在 el 检查处静默 return）——
  // 反查回退从未执行，宿主徽标恒滞留旧值。判据统一为「视图无 bgagentIndicatorEl
  // = 不可刷新」：命中 step/弹窗视图同样落入反查；终判同款（反查落点亦无指示器
  // = 无宿主，返回）。
  let view = targetSid ? findViewBySessionId(targetSid) : activeView;
  if (targetSid && (!view || !view.dom || !view.dom.bgagentIndicatorEl)) {
    view = findViewBySessionId(bgScopeRootForBucket(targetSid));
  }
  if (!view || !view.dom || !view.dom.bgagentIndicatorEl) return;
  const el = view.dom.bgagentIndicatorEl;
  const sid = view.sessionId;
  // Flow 会话可见性批（2026-11-01）：计数走 bgPanelModel 单点（主桶可见行 ∪ 作用
  // 域桶链行 ∪ 延续态组头）——delegate 已终态而链仍在跑的批，主桶已空，徽标计数
  // 只能来自链行/延续组头（「批死了」的前端症状一半就在这枚徽标恒隐）。
  // Author ruling 2026-09-14 16:30: the badge counts RUNNING rows only (an idle
  // dispatcher inside its keep-alive window counts zero). Same predicate as the
  // list (visibleBgAgentEntries) — the two must never disagree (see the view
  // resolution note above: count>0 with an empty dropdown was a real defect).
  const model = bgPanelModel(sid);
  const count = model.activeCount;
  if (model.total > 0) {
    el.classList.remove('hidden');
    el.querySelector('.bgagent-count').textContent = count;
  } else {
    el.classList.add('hidden');
    el.setAttribute('aria-expanded', 'false');
    const dropdown = view.dom.bgagentDropdownEl;
    // Author ruling 2026-09-19 (批 dropcol-impl), 令①: only the SNAPSHOT-BACKFILL
    // call site (onMessage('activeAgents') below) may not close the panel. The
    // user just clicked the indicator — "open" outranks a backfill that arrives
    // with an empty registry (isolated/test instance or a session whose
    // sub-agents just went terminal), which used to snap the panel shut one
    // frame after it opened. Every OTHER close source is deliberately untouched:
    // the terminal paths (agentDone → 2s cleanup, session-level done for
    // node-*/dispatcher-*) still collapse the panel through this same line, and
    // so do the outside-click (:2319) and toggle (:2301) paths. Hence the guard
    // is per CALL SITE, not global — a blanket "never close on count 0" would
    // break 令②(子任务全部结束面板自动收起).
    if (dropdown && !(opts && opts.keepDropdownOpen)) dropdown.classList.add('hidden');
  }
  if (view === activeView) renderBgAgentDropdown();
}

/**
 * 全视图徽标重算（Flow 会话可见性批 2026-11-01）：delegate 作用域桶整桶清空（链
 * 全终态）后，桶键再无任何行可供 bgScopeRootForBucket 反查宿主——延续态组头随
 * 行集消失的同一拍，持有它的主视图徽标必须归零回收，否则「链运行中」徽标滞留
 * 死链（T4 契约）。逐视图换场重算（与 main.js 终态管线的 setActiveView 舞步同
 * 款——renderBgAgentDropdown 只刷 activeView 载体），弹窗/step 视图无指示器元素
 * 跳过。语义 = updateBgAgentIndicator 原样（count=0 收面板走既有令①/②分岔）。
 */
export function refreshAllBgAgentIndicators() {
  for (const v of Object.values(chatViews)) {
    if (!v || !v.dom || !v.dom.bgagentIndicatorEl) continue;
    const saved = activeView;
    setActiveView(v);
    updateBgAgentIndicator();
    setActiveView(saved);
  }
}

// ── Sub-agents panel (2026-08-25 spec, frozen @142535e9) ─────────────
// Row = [status dot + label][kind chip][name · task][uptime][retries ×N]
//       + secondary lines: stuck alert label / current tool subtitle.
// User rulings locked in the spec: barrier hidden, retries only when >0,
// NO kind grouping (chip instead), NO inline stuck buttons (operations
// live in the agent popup — A13).

/** Derive the AgentKind from the session-id prefix when the snapshot kind is
 *  absent (live agentStart carries no kind field).
 *  修订批（用户九点② · 2026-10-09）：「SubTask」UI 标注更名 delegate 语义——显示词
 *  归一为 Delegate（subtask 工具引擎侧注册面照旧，见报告；会话 id 前缀 `subtask-`
 *  是后端协议保留不改，此处只改面板 kind 徽标词）。 */
export function bgAgentKindFromSession(sessionId) {
  const raw = sessionId || '';
  if (raw.startsWith('team-')) return 'Team';
  if (raw.startsWith('delegate-')) return 'Delegate';
  if (raw.startsWith('subtask-')) return 'Delegate';
  // builtin-merge 批（2026-10-03）：Subagent（只读侦察）与 Workflow 步级子会话。
  // N1 复名批（2026-10-06，v2 ①）：Workflow 工具复名 AgentFlow——显示标签随名
  // 翻转；会话 id 前缀 `workflow-` 是存储/归因命名空间，保留不改（v2 §3.4）。
  if (raw.startsWith('subagent-')) return 'Subagent';
  if (raw.startsWith('workflow-')) return 'AgentFlow';
  if (raw.startsWith('dag-')) return 'Flow';
  if (raw.startsWith('ephemeral-')) return 'Ephemeral';
  // #28 可观测接线: Project 节点/分发器后端注册为 AgentKind.Flow → 面板 kind
  // 徽章显示 Flow（与快照 kind 一致）。
  if (raw.startsWith('node-')) return 'Flow';
  if (raw.startsWith('dispatcher-')) return 'Flow';
  // external-executors 批 #1225：外部 CLI delegate（external-<exec>-<id>）。
  if (raw.startsWith('external-')) return 'External';
  return '';
}

/** Background-task bucket keying (2026-09-05 fix): every backend
 *  backgroundTaskUpdate envelope now carries rootSessionId (ToolContext.rootSessionId,
 *  falling back to the executor's own sessionId), and the BgTaskRegistry snapshot
 *  (activeBgTasks) is grouped by the same key — the frontend keys directly on it.
 *  The old heuristic reverse-lookup (bgTaskRootFor, added 2026-08-25) walked
 *  state.sessionBgAgents to map delegate-/subtask- sessions back to their root,
 *  but silently mis-keyed whenever that mapping was absent (host restart, page
 *  refresh, agent already finished) — tasks landed in orphan buckets and the
 *  owning window's badge went stale (count>0 with an empty dropdown). Deleted:
 *  the authoritative key makes the heuristic both unnecessary and harmful.
 *  Defensive fallback for envelopes lacking rootSessionId (old backend /
 *  REST-invoked tools): key on msg.sessionId as-is. */

/** Row state machine (spec §3): done > stuck > waiting > frozen > error > idle > active.
 *  `waiting`（held-receipt 投递可靠性批 2026-10-21 fix 4）：delegate ready-to-merge
 *  显式停靠态（引擎 Waiting 状态 / delegateWaiting 帧）——「等合并轮提醒」的显式
 *  等待，不再貌似 Idle 完成。 */
function bgRowState(info) {
  const execution = taskExecutionPresentation(info);
  if (execution) return execution.state;
  if (info.done) return 'done';
  if (info.stuck) return 'stuck';
  // 错误冻结批（2026-11-01）：**错误冻结**优先于 waiting/idle——冻结 = 就地停住
  // 等唤醒，落成「候合并/待命/空闲」会误导成自愿停靠语义。判据 = info.frozen
  // （delegateFrozen 帧 / 快照 frozenSince 复原）。
  // 冻结态误报批（2026-11-05）：判据补 `status === 'Frozen'`——actor 级冻结族
  // （循环检测 L2 / 错误退避 / 时间表）只写 registry 状态，盘上没有 delegate 冻结
  // 槽，刷新后 info.frozen 不在场；只看 frozen 会把冻结行画成「进行中」（作者报障
  // 时说「它显示已冻结」与面板/工具面读数打架的根因之一）。
  if (isFrozenFamily(info)) return 'frozen';
  if (info.waiting || info.status === 'Waiting') return 'waiting';
  if (isFailedSnapshotStatus(info.status)) return 'error';
  const st = info.status || '';
  if (st === 'Idle' || st === 'WaitingForUser' || st === 'idle') return 'idle';
  return 'active';
}

// ── #752 空闲 delegate 在等什么（可见化批）──────────────────────────────────
// 判据链（行状态 idle/waiting 时的细分次行；多判据命中全显，顿号连接）：
//   ① 等后台任务 —— 快照 bgWaiting（BgTaskRegistry waitingBySession，会话自有
//      等待型任务）非空：任务名 + 已运行时长（首条）+ 计数。数据源 = 引擎
//      bg-wait 屏障权威（BackoffSupervisor 同判据集的自有子集）。
//   ② 等子代理回执 —— heldReceipts（被屏障扣留的已产出回执）+ outstandingSubagents
//      （barrier 上的 spawn 计数）> 0。
//   ③ 等通知 —— Waiting 显式停靠态（等合并轮），既有 waitingPart 承载。
// 长空闲提醒徽标（同批）：Idle 且 ①②皆空、lastActivityMs 距今 > 10min、
// 无在飞工具 —— 琥珀字「空闲 N 分钟」，提示该行无在飞工作（可安全收编/合并）。
const BG_LONG_IDLE_MS = 10 * 60 * 1000;

/** 空闲/waiting 行的细分注记（null = 无——行保持既有形态）。
 *  P0 面板投影批（设计稿 20261030 §3.4）：等后台任务段随行健康色——首条任务
 *  的 health（快照 bgWaiting[].health，行内字段派生）经 bgHealthNorm 容错后
 *  以 {health: 'healthy'|'suspect'|'escalated'|''} 随返回值出（渲染层转色点）。 */
function bgIdleDetail(info) {
  const bgWaiting = Array.isArray(info.bgWaiting) ? info.bgWaiting : [];
  const receipts = (Number(info.heldReceipts) || 0) + (Number(info.unreadReceipts) || 0)
    + (Number(info.outstandingSubagents) || 0);
  const parts = [];
  let health = '';
  if (bgWaiting.length > 0) {
    const w = bgWaiting[0];
    const dur = w.startedAt ? fmtUptime(Date.now() - w.startedAt) : '';
    health = bgHealthNorm(w.health);
    // 归属批（作者令 2026-10-31）：等待行同样标归属——首条等待任务的 delegate
    // 批次短名（快照 bgWaiting[].ownerName）随段尾标注；空（非 delegate/旧后端）
    // 回退现显示（零附加）。
    const owner = String(w.ownerName || '').trim();
    parts.push(t('subagents.waitingBg', {
      count: bgWaiting.length,
      desc: String(w.description || w.taskId || ''),
      dur,
    }) + (owner ? ' · ' + owner : ''));
  }
  if (receipts > 0) parts.push(t('subagents.waitingReceipts', { count: receipts }));
  if (parts.length > 0) return { text: parts.join(' · '), stale: false, health };
  // 长空闲：无在飞工作（①②皆空）且 agent 侧活动戳距今 > 10min。
  const idleMs = Number(info.lastActivityMs) > 0 ? Date.now() - Number(info.lastActivityMs) : 0;
  if (idleMs > BG_LONG_IDLE_MS) {
    return { text: t('subagents.longIdle', { mins: Math.floor(idleMs / 60000) }), stale: true, health: '' };
  }
  return null;
}

/** Session-id prefix predicate (single point): `dispatcher-` = Project task
 *  dispatcher session. `node-` = Project Flow Map node session, `delegate-` /
 *  `subtask-` = Delegate / SubTask sub-agents. */
function isDispatcherSession(id) {
  return typeof id === 'string' && id.startsWith('dispatcher-');
}

/** Flow 会话可见性批（2026-11-01 · 作者令「控制台后台代理面板修复」）：C1 过渡
 *  过滤**退役**。考古：`dispatcher-*` / `node-*` 是 Project 域引擎会话（kind=Flow，
 *  注册点 ProjectActor / NodeStarter），delegate-ui 批（2026-10-08）以 C1 过渡纪律
 *  「在册但不入一等陈列面」整体滤除——终态（P4）内部会话形态删除并未发生，分发器
 *  模式的链会话（delegate 已终态、dispatcher/node 仍在册）在面板零渲染 =「批死了」
 *  （作者 15:18 实证）。本批口径：Flow 会话与 Delegate/SubTask 同一陈列标准——
 *  溯源到所属 delegate 的挂组头子行（分发器/节点徽标 + 状态/运行时长），溯源不到
 *  的顶层行直显并标 kind；活性/注册语义零触碰（快照面本就全量出帧）。 */
function isC1InternalOrchestration(id, info) {
  return false;
}

/** Panel admission口径 (author ruling 2026-09-14 16:30, asknb-6d626dd4307a4d31):
 *  「只显示运行中的，计数也只计算运行中的，空闲不显示。」
 *
 *  delegate-ui 批（2026-10-08）曾滤 `dispatcher-`/`node-`（C1）；Flow 会话可见性批
 *  （2026-11-01）退役该滤（isC1InternalOrchestration 恒 false——见上注）。其余前缀
 *  沿用既有口径——hidden = not rendered AND not counted（same bucket, same 口径 as
 *  the list, so the badge can never disagree with the dropdown）。 */
function isBgRowVisible(id, info) {
  if (isC1InternalOrchestration(id, info)) return false;
  return true;
}

/** The visible rows of a bucket — the ONE read used by the badge count, the
 *  panel-header count and the list rendering. The bucket itself keeps the raw
 *  set (presentation never rewrites the data面). */
function visibleBgAgentEntries(bgAgents) {
  return Object.entries(bgAgents || {}).filter(([id, info]) => isBgRowVisible(id, info));
}

// Uptime tick: while the dropdown is open, refresh .bg-task-uptime text in
// place every 15s (no full re-render — keeps keyboard focus). Self-terminates
// once the dropdown hides, regardless of which close path ran.
// 后台合并批（2026-10-23 ⑥）：子智能体行现居合并载体 #bg-dropdown —— 在开判据
// 随行落点改读载体（旧幽灵面恒 display:none，读它会让 uptimes 永停）。
let bgUptimeTick = null;
// bg-panel-ghost-resync 批（2026-10-28）：uptick 拍数计数（每 2 拍 = 30s 一次
// 快照回填，见 armBgUptimeTick 内注释）。
let bgUptimeTicks = 0;
export function armBgUptimeTick(view) {
  if (bgUptimeTick) clearTimeout(bgUptimeTick);
  bgUptimeTick = setTimeout(() => {
    bgUptimeTick = null;
    const dd = view && view.dom.bgDropdownEl;
    if (dd && !dd.classList.contains('hidden')) {
      dd.querySelectorAll('.bg-task-uptime[data-started-at]').forEach(el => {
        const started = Number(el.getAttribute('data-started-at'));
        if (started > 0) el.textContent = fmtUptime(Date.now() - started);
      });
      // bg-panel-ghost-resync 批（2026-10-28）：面板在开 ⇒ 每第 2 拍（30s，与
      // BG_SNAPSHOT_STALE_MS 对齐）回填一次 activeAgents 快照。幽灵行清道夫
      // （合并规则②记 _missSince + 规则③ 30s 陈旧淘汰）只吃快照，而快照旧来
      // 只在「开面板那一下」与「WS 重连」两个事件流——单张快照只给幽灵行**起表**，
      // 永无第二张来**销账**，终态帧丢失（断线/引擎重启/OS 休眠）的行便以
      // 「空闲 N 分钟」挂屏 12h+（作者面板 2026-10-27 实证：深空图源批止损后
      // 17 行僵尸滞留整天）。
      // 行集收敛批（2026-11-02 · RCA 必须修⑤「淘汰与标签页可见性解耦」）：旧实现
      // 在此再叠一道 `document.visibilityState === 'visible'` 门——后台标签页零取
      // 数 ⇒ 终态帧丢失的行在后台期起不了 _missSince、回前台才一次性清账；反向
      // 的「后台把行误淘汰」从来不可能发生（淘汰判据只吃快照未列 + 陈旧窗，与
      // 可见性无关）。本批把这道门拆掉：面板开着就取数（后台也取），淘汰判据
      // 「快照未列 + 连续未命中满窗」只看数据。取数节奏不变（30s 一拍），陈旧窗
      // 30s = 一次拉取间隔的 1 倍 ≤ 2 倍上界（RCA 建议），不另调。
      if ((++bgUptimeTicks % 2) === 0) {
        fetchActiveAgentsOnOpen();
      }
      armBgUptimeTick(view);
    }
  }, 15000);
}

// ── delegate-ui 批（2026-10-08 · 定稿蓝本 §1.2 / §2 R6+C2）：面板两级化 ─────────
// 层级总则（C2）：桶 = 主会话（root）不变；一级行 = delegate 行（delegate-*/external-*）
// 作为组头，徽标 = 编排模式（G1 权威）；二级行 = 缩进一格的子行——星型（G3 投影 ∪
// live 行）平铺挂父下、agentflow（chain.nodes flow-step 投影）按 flow 结构缩进；子行
// 徽标 = 自身 kind。数据侧全程走 stub 读取口（rows/children/chain——provider 镜像），
// 禁渲染面直连 WS/REST。非 delegate 编排形态中仅 C1 集（dispatcher-*/node-*）被滤
// （isBgRowVisible 单点）；其余未被认领的行（ephemeral/dag/team 等）按原序缀后照 render。
//
// 面板层级批（2026-10-31 ① · 作者令）：父行可折叠——组头行首 caret，点击收起/展开
// 其子行树（用户偏好记在折叠偏好表，重渲染不丢）；**默认展开进行中父**
// （非终态组头默认展开），终态组头默认收起（完成态灰化照旧，计数不变——签与
// 列表同走 visibleBgAgentEntries，折叠只影响子行渲染）。
//
// 层级缩进批（2026-11-05 腿② · 作者令「节点也分很多层，每一层都应该是上一层的缩进」）：
// 层级从「两级帽」放宽到**任意层**——深度按真实父子链接算（bgRowTree 四源），行上出
// data-bg-depth + 逐层累加的缩进；**任意有子行的行**都有 caret，收起即隐藏整棵子树；
// 折叠偏好按行定位键持久化到 localStorage（刷新后保持）。铁律不变：元素不许为了腾
// 地方而消失——只有用户主动折叠才隐藏，深度本身绝不裁行。

/** 折叠用户偏好：**行定位键**（bgRowLocatorKey，与 data-bg-key / data-bg-caret 同一条
 *  缝）→ true=收起（undefined = 走默认规——终态收起）。
 *  层级缩进批（2026-11-05 腿②）：面板层级批①的内存 Map 升级为 **localStorage 持久表**——
 *  折叠是用户主动行为，主动行为该跨刷新记住；折叠粒度从「组头」放宽到**任意有子行的行**
 *  （作者令 2026-11-05 08:47「节点也分很多层，每一层都应该是上一层的缩进」）。
 *  模块内**只此一处**碰 localStorage：读 = bgCollapseTable（JSON 容错），写 = bgCollapseSet。 */
const BG_COLLAPSE_STORAGE_KEY = 'nebflow.bgAgents.collapsed.v1';
/** @type {Record<string, boolean>|null} */
let bgCollapseCache = null;

/**
 * 折叠偏好表（惰性装载；**单点读口**）：命名空间键 + JSON 容错——坏 JSON / 非对象 /
 * 数组 / 非布尔值一律丢弃（绝不因一条脏数据让面板抛错，也绝不整表清空用户偏好）。
 * @returns {Record<string, boolean>}
 */
function bgCollapseTable() {
  if (bgCollapseCache) return bgCollapseCache;
  /** @type {Record<string, boolean>} */
  const table = {};
  try {
    const raw = localStorage.getItem(BG_COLLAPSE_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      for (const k of Object.keys(parsed)) {
        if (parsed[k] === true || parsed[k] === false) table[k] = parsed[k] === true;
      }
    }
  } catch (_) {
    // 坏值 ⇒ 空表（内存表仍可用），零抛错零 console 噪音。
  }
  bgCollapseCache = table;
  return table;
}

/**
 * 某行的折叠偏好（单点读口）：undefined = 用户没表态 ⇒ 调用方走默认规。
 * @param {string} key 行定位键
 * @returns {boolean|undefined}
 */
function bgCollapseGet(key) {
  const k = String(key || '');
  if (!k) return undefined;
  const table = bgCollapseTable();
  return Object.prototype.hasOwnProperty.call(table, k) ? table[k] === true : undefined;
}

/**
 * 写某行的折叠偏好（单点写口）：内存 + localStorage 同写；存储不可用（隐私模式 /
 * 配额满）⇒ 会话内仍生效，绝不让点击行为丢在半路。
 * @param {string} key 行定位键
 * @param {boolean} collapsed
 */
function bgCollapseSet(key, collapsed) {
  const k = String(key || '');
  if (!k) return;
  const table = bgCollapseTable();
  table[k] = !!collapsed;
  try {
    localStorage.setItem(BG_COLLAPSE_STORAGE_KEY, JSON.stringify(table));
  } catch (_) {
    // 存储不可用 ⇒ 内存表已生效（刷新后回默认规）。
  }
}

/** 默认折叠规（面板层级批 ① · 层级缩进批沿用）：终态（done/error）默认收起，进行中
 *  父默认展开；活链延续组头的默认展开在 bgRowCollapseDefault 里另行前置。 */
function bgHeadDefaultCollapsed(info) {
  const st = bgRowState(info);
  return st === 'done' || st === 'error';
}

/** 行默认折叠态（层级缩进批 2026-11-05 腿②）：任一有子行的行共用同一套默认规——
 *  · 整体行：活链延续组头 ⇒ 展开（既有规：活链可见即该批目的）；否则走终态收起；
 *  · 成员行：终态（done/error、store completed/failed、flow completed/failed/cancelled）
 *    ⇒ 收起；进行中 ⇒ 展开。
 *  @param {any} row 行计划项 @returns {boolean} */
function bgRowCollapseDefault(row) {
  const r = row && typeof row === 'object' ? row : {};
  if (r.level === 'head') {
    const g = r.group || {};
    if (g.chainLive) return false;
    return bgHeadDefaultCollapsed(g.info || {});
  }
  // 结构父（face C）：默认随子树态——有活跃后代 ⇒ 展开（活跃可见），全终态 ⇒ 收起
  // （与组头终态收起同规；用户偏好仍可覆写）。
  if (r.level === 'struct') return !!r.structAllTerminal;
  if (r.level !== 'child') return false;
  const c = r.child || {};
  if (c.type === 'store') {
    const st = bgStoreChildState(String((c.child && c.child.status) || ''));
    return st === 'done' || st === 'error';
  }
  if (c.type === 'flow') {
    const st = String((c.node && c.node.status) || '');
    return st === 'completed' || st === 'failed' || st === 'cancelled';
  }
  return bgRowTerminal(r.info || {});
}

/** 执行形态提取单点（来源字段 = `DelegateRegistry.Record.mode` 的 provider 镜像
 *  `row.mode`；值域 kernel | dispatcher，`''` = 旧盘 / 外部腿 / 非 delegate 会话）。
 *  形态判定**只认这一个字段**：不看会话 id 前缀、不看显示名、不看
 *  `orchestrationMode`（后者是 spawn 副产品、惰性升级：「这行代表什么」不能由
 *  「它派生过没有」反推）。值域外的词一律归一为 `''`（无信息胜过错信息）。
 *  @param {any} row @returns {'kernel'|'dispatcher'|''} */
function bgDelegateExecMode(row) {
  const mode = String((row && row.mode) || '');
  if (mode === 'kernel' || mode === 'dispatcher') return mode;
  return '';
}

/** 徽标归位批（2026-10-31 23:02 作者令 + 实拍截图）：**顶层 delegate 组头行只标一个
 *  徽标 = delegate 执行形态 `row.mode`（kernel / dispatcher，即 delegate:type）**——
 *  Delegate/Subagent/Flow 字样一律不上顶层；子级行的徽标 = 自身 kind（subagent /
 *  agentflow 等，视实际类型，渲染在 childLevel 分支）。
 *  agentflow 错标修复批（2026-11-06 作者令「kernel 被标记 agentflow」）：本函数的
 *  输入统一为 [[bgDelegateExecMode]] 的读数——形态词逐字上屏，徽标与形态同源。
 *  考古（顶层为何会标上 Subagent——徽章来源字段取错）：旧实现以 `orchestrationMode`
 *  兜底（subagent-star → Subagent / agentflow → Flow），再退快照 kind / 会话 id
 *  前缀（SubTask → Delegate）。这三个字段全是**历史类型残留**——编排形态随子代理
 *  spawn 记账惰性升级、kind 是 spawn 时刻的会话种类——与「这行在面板的层级」无关；
 *  双模式总闸关 / 旧盘 mode='' 时兜底链把残留词顶到顶层组头 ⇒ 作者实拍截图的
 *  「同层混排」乱象（有的 kernel、有的 Delegate、有的 Subagent）。
 *  行级判定锚 = **层级**（不是历史类型残留）：顶层 ⇒ 只认 row.mode；mode 缺席
 *  （总闸关/旧盘）⇒ **无徽标**（绝不回退残留词——无信息胜过错信息；子级 kind 词
 *  的显示词归一仍走 bgMemberKindWord，渲染点不动数据面）。
 *  术语分层批（2026-11-05）：本函数产出的是**执行形态词**，与整体行身份词
 *  （bgAgentflowWord）同行并存——两者不是同一个词位，也不许互相顶替。
 *  术语统一批（2026-11-06 作者令「统一 根据英文」）：词面 = 单源词表（termWords.js
 *  的 badgeWord），中文面同串（不再有「内核 / 分发器」这套译名）。
 *  @param {string} execMode [[bgDelegateExecMode]] 的读数
 *  @returns {string} */
function bgDelegateBadgeWord(execMode) {
  const mode = String(execMode || '');
  if (mode === 'kernel' || mode === 'dispatcher') return badgeWord(mode);
  return '';
}

/** Store 子条目状态 → 面板行状态（SubAgentTask 值域 running|completed|failed|restarting；
 *  未知归 active 保守侧——不臆造终态）。 */
function bgStoreChildState(status) {
  if (status === 'completed') return 'done';
  if (status === 'cancelled') return 'cancelled';
  if (status === 'failed') return 'error';
  // 模型粒度批（作者令 2026-11-06 20:36）：重启/崩溃中断 = 待手动恢复（后端零通知、
  // 不自动恢复；`pending-recovery` 由 SubAgentStartupRecovery 标记）——不是进行中。
  if (status === 'pending-recovery') return 'pending-recovery';
  return 'active';
}

// ── 台账 #929（后台子智能体跨设备透明）：远端投影行渲染 ─────────────────────
// 数据面 = state.remoteBgAgents（引擎 remoteAgents 帧；LWW/在线判读都在引擎侧，
// 渲染面原样呈现）。行 = 既有子智能体行语言（状态词/状态点/kind 徽标/uptime/
// 重试片）+ 两枚新角标：设备角标（bg.runsOn title，「跑在哪」轴——bg-device-
// origin-chip 批同轴）+ 离线角标（琥珀「设备离线」）。**展示专用**：无点击展开
// （跨设备会话详情不在本批边界），不入 per-session 徽标计数（跨会话集不归属
// 单会话）——计数红线见 main.js remoteAgents 处理器。
const REMOTE_ROW_STATE = {
  running: 'active',
  restarting: 'active',
  'ready-to-merge': 'waiting',
  completed: 'done',
  cancelled: 'done',
  failed: 'error',
};

/** 远端投影行状态（值域外归 active 保守侧——同 bgStoreChildState 纪律）。 */
function bgRemoteRowState(status) {
  return REMOTE_ROW_STATE[String(status || '')] || 'active';
}

/** 单行远端投影 HTML（设备分组内的行；display-only，零点击锚）。 */
function bgRemoteRowHtml(info, device) {
  const rowState = bgRemoteRowState(info.status);
  const terminal = rowState === 'done' || rowState === 'error';
  const statusLabel = t('subagents.status.' + (rowState === 'active' ? 'running' : rowState));
  const kindWord = bgMemberKindWord({ source: String(info.source || ''), sessionId: String(info.sessionId || '') });
  const kindPart = kindWord ? '<span class="bg-task-kind">' + escapeHtml(kindWord) + '</span>' : '';
  // 设备角标（「跑在哪」轴）+ 离线角标（保留最后态，不消失不假装死亡——在线性
  // 由引擎发送时刻现算；离线行 = 最后态 + 琥珀「设备离线」）。
  const deviceName = String((device && device.deviceName) || info.deviceName || '');
  const devicePart = deviceName
    ? '<span class="bg-task-device" title="' + escapeHtml(t('bg.runsOn', { origin: deviceName })) + '">'
      + escapeHtml(deviceName) + '</span>'
    : '';
  const offlinePart = (device && device.online === false)
    ? '<span class="bg-task-offline" role="note">' + statusIcon('wifi-off', 11, 'st-warn')
      + escapeHtml(t('subagents.deviceOffline')) + '</span>'
    : '';
  const displayName = bgFirstLine(info.title) || info.sessionId;
  const namePart = '<span class="bg-task-name" title="' + escapeHtml(displayName) + '">' + escapeHtml(displayName) + '</span>';
  const uptimePart = (!terminal && info.startedAt)
    ? '<span class="bg-task-uptime" data-started-at="' + info.startedAt + '">' + escapeHtml(fmtUptime(Date.now() - info.startedAt)) + '</span>'
    : '';
  const retries = info.retryCount || 0;
  const retriesPart = retries > 0
    ? '<span class="bg-task-retries" title="' + escapeHtml(t('manage.retry')) + ' ×' + retries + '">×' + retries + '</span>'
    : '';
  // data-remote-session = 行锚（E2E 取数面；无点击行为绑定——展示专用行）。
  return '<div class="bg-task-row bg-task-row-remote bg-task-row-child" role="listitem" tabindex="0" data-remote-session="' + escapeHtml(String(info.sessionId || '')) + '">' +
    '<div class="bg-task-info">' +
      '<div class="bg-task-line bg-task-main bg-task-row-line1">' +
        namePart +
        bgStatusHtml(rowState, { label: statusLabel }) +
        uptimePart +
      '</div>' +
      '<div class="bg-task-line bg-task-row-line2 bg-task-badges">' +
        kindPart + devicePart + offlinePart + retriesPart +
      '</div>' +
    '</div>' +
  '</div>';
}

/** 远端投影行整组 HTML（按设备分组、组内按 updatedAt 升序——引擎 rows() 序）。
 *  无行 = ''（零噪）。 */
function bgRemoteRowsHtml() {
  const remote = state.remoteBgAgents || {};
  const parts = [];
  for (const device of Object.values(remote)) {
    for (const info of Object.values(device.rows || {})) {
      parts.push(bgRemoteRowHtml(info, device));
    }
  }
  return parts.join('');
}

/** 术语分层批（2026-11-05 · 作者令「agentflow = 整个组织行为的代称（一次编排 = 一个
 *  agentflow），节点 = agentflow 里的成员，成员分层」）：**整体行身份词单点**。
 *  取值只来自单源徽标词表（js/termWords.js 的 badgeWord('agentflow') → 逐字
 *  `AgentFlow`）——唯一渲染点是 bgTaskRowHtml 的 headLevel 分支（组头行 / 合成延续
 *  组头行）；别的 web 模块不得再写这个词面（回归 spec bg-agentflow-terms H5 静态钉）。
 *  术语统一批（2026-11-06 作者令「统一 根据英文」）：本词原走 locale 键
 *  `subagents.kindAgentflow`（zh/en 同词面），本批整族退役 —— 词面统一从 termWords.js
 *  取，语言面不再有第二条路径。
 *
 *  agentflow 错标修复批（2026-11-06 · 作者令逐字「kernel 被标记 agentflow？agentflow
 *  是用来标记 dispatcher 派生出来的节点的」）：**本词 = dispatcher 派生的编排身份词**，
 *  判据 = 来源字段**两轴**（同一个注册行镜像）：`DelegateRegistry.Record.mode` 逐字等于
 *  `dispatcher`（有编排资格）且 `orchestrationMode` 逐字等于 `agentflow`（真的登记过链
 *  = 真的派生过节点；唯一写点 `ChainWriteTool.registerChain`）。三种行因此不落本词：
 *    · `kernel`——最小单会话执行器本体（actor-tree spec v1 §13.2 R-3a：kernel 不派生，
 *      只放行只读侦察腿；`kernelFace` 摘掉 NodeTools 四件 ⇒ 结构上也没有 ChainWrite，
 *      其 `orchestrationMode` 最多少到 `subagent-star`），它不是任何编排；身份词 = 逐字 `kernel`；
 *    · dispatcher 但尚未登记过链（`orchestrationMode` 仍是 `single`）——没有派生事实；
 *    · mode 缺席（旧盘 / 外部腿 / 非 delegate 会话）——无来源证据，不猜（无信息胜过错信息）。
 *    成员行同样不落本词——成员是节点，见 bgMemberKindWord（反向门同批保留）。
 *  错标根因（旧实现）：本词的发射条件是「这一行是组头（headLevel）」——层级是**排布
 *  事实**，不是来源事实；组头判据把 kernel / 旧盘行一并算进了 agentflow。修法 = 判据
 *  换回来源字段，渲染面只消费同一读数，不做字符串猜（无前缀/名字/包含判读）。
 *
 *  与两个作者术语的分工：kernel / dispatcher 是**执行形态词**（逐字保留、不改名、
 *  不翻译），本词是**层级词**（这一行代表一次 dispatcher 编排）。
 * @param {any} row 注册行镜像（DelegateRow；缺行 ⇒ 零徽标）
 * @returns {string} 空串 = 本行不是 dispatcher 派生的编排（零徽标）
 */
function bgAgentflowWord(row) {
  // 两轴同读**注册行镜像**（DelegateRegistry.Record 的 provider 镜像）：
  //   ① 执行形态 = dispatcher（`mode`）——编排的**资格**面；
  //   ② 编排形态 = agentflow（`orchestrationMode`，唯一写点 = ChainWriteTool.registerChain
  //      的成功回执）——真的登记过链 = 真的派生过节点。
  // kernel 结构上到不了 ②（kernelFace 摘掉 NodeTools 四件 ⇒ 没有 ChainWrite），
  // ① 再钉一道：两条来源都指向「dispatcher 派生的编排」才落词。
  const execMode = bgDelegateExecMode(row);
  const orchestrationMode = String((row && row.orchestrationMode) || '');
  if (execMode === 'dispatcher' && orchestrationMode === 'agentflow') return badgeWord('agentflow');
  return '';
}

/**
 * 成员行身份词单点（术语分层批 2026-11-05）：一次编排（agentflow）里的节点是谁——三源
 * （会话 id 前缀 / store 条目 source / 快照 kind）**只在这一处**收敛成词，三个渲染点
 * （live·chain 子行 / store 子行 / flow 结构子行）只许调本函数，不许各写一套词表。
 * 考古（术语审计 §② 混用举证）：旧形态是 bgFlowKindWord / bgChildKindWord /
 * bgKindDisplayWord / bgAgentKindFromSession 在三个构造器里各排一次链，flow 结构行更把
 * **整体词**硬编码成了成员徽标——同一个 flow 节点有两条互斥画法（「节点」/「AgentFlow」）。
 *
 * **成员位不落整体词**：引擎 kind `Flow` / `AgentFlow` / `flow-step` 在成员层统一读作
 * 节点词（节点 = 成员；`node-` / `dag-` / `workflow-` 前缀同判）。数据面原值一律不动
 * （bgAgentKindFromSession 供 main.js 的 info.kind 记账，返回的协议词保留——那是
 * **记账词/协议词**，不是上屏的徽标词面）。
 *
 * 术语统一批（2026-11-06 作者令「统一 根据英文」）：词面**只从单源表取**
 * （js/termWords.js）。三源（会话 id 前缀 / store 条目 source / 快照 kind）落成的
 * 数据词一律经 badgeFaceOfKind 归一（`SubTask`/`subtask` → delegate、`Flow` /
 * `AgentFlow` / `flow-step` → node）；本函数不再自带词表，也不再走 locale 键族
 * （`subagents.kindDispatcher` / `subagents.kindNode` 已于本批整族退役）。
 * @param {{source?: string, info?: any, sessionId?: string, id?: string}} [member]
 * @returns {string}
 */
function bgMemberKindWord(member) {
  const m = member && typeof member === 'object' ? member : {};
  const info = m.info && typeof m.info === 'object' ? m.info : {};
  const sid = String(m.sessionId || (info && info.sessionId) || m.id || '').replace(/^team-/, '');
  // 分发器 / 节点是前缀判读的正向 kind 徽标（与 isDispatcherSession / 引擎会话前缀同源
  // 契约）——词面同样来自单源表。
  if (isDispatcherSession(sid)) return badgeWord('dispatcher');
  if (sid.startsWith('node-')) return badgeWord('node');
  // 子智能体标签批（2026-11-07 · 作者报障「kernel 下面有个 delegate 标签，kernel 下面
  // 不应该是 subagent 吗」）：**`subagent-` 会话是 Subagent 工具腿的执行单元**——它是
  // kernel/执行体的**只读侦察子代理本体**（BlockingSubagent.scala:81 的唯一 spawn 点，
  // 机制固定 = AgentCore.SubagentFixedTools，叶子不派生），身份词 = `subagent`。
  // 为什么必须在这里抢先（不能靠 info.kind / source 链）：引擎把这条腿**按控制面
  // 归组**注册成 `AgentKind.SubTask`（BlockingSubagent.scala 的 registerAgent(kind=
  // AgentKind.SubTask)——为了 AgentControl 的 cancel/restart 白名单与无 Mail 面的语义），
  // 快照面 `kind` 因此逐字是 "SubTask"（WebSocketRoutes.activeAgentEntryJson 的
  // `"kind" -> rec.kind.toString`）；而术语统一批把 `SubTask` 归一成 `delegate`
  // （termWords.badgeFaceOfKind）——于是**真的子智能体在面板上被贴上了 delegate 标签**
  // （作者截图实证：kernel「节点卡点审计」下的两个子行，store 证据 = subagent-tasks/
  // delegate-nebflow-94ce9d8e.json 的 source:"subagent" + taskId "subagent-*"）。
  // 判据边界（防止把别的 delegate 混改）：只认会话 id 前缀 `subagent-`——`subtask-*`
  // （SubTaskTool 的并行工人）与 `delegate-*`（Delegate 批次）逐字不动，`SubTask`
  // 数据词在 store source / 协议 kind 两条既有路径上仍归一为 `delegate`。
  // 真实身份与路由零改动：本函数只产**显示词**，AgentKind / MailAddress / 控制面
  // 白名单全在原处（SubTask 依旧无 Mail 面、依旧可 cancel/restart）。
  if (sid.startsWith('subagent-')) return badgeWord('subagent');
  const source = String(m.source || '');
  const word = source || String(info.kind || '') || bgAgentKindFromSession(sid);
  return badgeFaceOfKind(word);
}

/**
 * delegate 作用域桶的链会话行（Flow 会话可见性批 2026-11-01 · 溯源规则②）。
 *
 * 引擎侧事实（溯源依据，零前端派生）：delegate-scope NodeEngine（DelegateFlowHosts.
 * mount）与 delegate 名下挂载的项目（ProjectCreateService sessionId 回落腿）都以
 * **delegate 会话 id 自根**（rootSessionId = delegateId）——其 dispatcher- 与
 * node- 前缀会话（含 subtask- 等）的 WS 帧（routeSubagentWsSend）与快照行全部归桶
 * `sessionBgAgents[delegateSid]`。该桶不是任何视图的桶（视图只读自己的主会话桶），
 * 此前整桶不可见 =「批死了」的直接根因。本函数 = 该桶的受控读口：渲染面按组头
 * delegate sid 逐桶拉取（self 守卫 + 分发器置顶 + startedAt 升序）。
 *
 * @param {string} delegateSid delegate 会话 id（组头 sid）
 * @returns {Array<{id: string, info: any, sid: string}>}
 */
function bgDelegateScopeChainRows(delegateSid) {
  const own = String(delegateSid || '').replace(/^team-/, '');
  const bucket = (own && state.sessionBgAgents[own]) || {};
  const out = [];
  for (const [id, info] of Object.entries(bucket)) {
    const sid = String((info && info.sessionId) || id || '').replace(/^team-/, '');
    if (!sid || sid === own) continue; // self 守卫（组头行永不在自己的作用域桶重复）
    out.push({ id, info, sid });
  }
  out.sort((a, b) => {
    const da = isDispatcherSession(a.sid) ? 0 : 1;
    const db = isDispatcherSession(b.sid) ? 0 : 1;
    if (da !== db) return da - db; // 分发器置顶（作者口径「分发器 + 节点」）
    return (Number(a.info && a.info.startedAt) || 0) - (Number(b.info && b.info.startedAt) || 0);
  });
  return out;
}

/** 行是否终态（done/error）——链活性判据（bgRowState 单点派生）。 */
function bgRowTerminal(info) {
  const st = bgRowState(info);
  return st === 'done' || st === 'error';
}

// ── 节点子智能体可见性批（2026-11-07 · 作者报障「dispatcher 的 agentflow 也就是 node
//    的子智能体好像没显示在上面」）────────────────────────────────────────────
// 数据面事实（引擎侧，逐条可核）：
//   ① node-/dispatcher- 会话的**子代理**同样是 BlockingSubagent 的唯一 spawn 点产物
//      （session id `subagent-*`、AgentKind.SubTask、store 条目 source `subagent`、
//      parentSessionId = 该 node 会话 id）——磁盘实据：<dataRoot>/subagent-tasks/
//      node-<uuid>.json（22 个文件，条目 source 逐字 `subagent`）。
//   ② **live 面**：这类会话的 WS 归桶键 = 节点上下文的 rootSessionId（= 项目 root），
//      因此进行中的子智能体行**已经**会进主桶、经 parentSessionId 挂到 node 行下
//      （既有三代层级批的属主边路径）。
//   ③ **终态面缺口**（本批修的漏显示）：BlockingSubagent 终态即 reap（registry 条目
//      移除、live 行随 2s 清理消失），结构只剩 store 条目；而面板的 store 子行只读
//      **组头（delegate 组）** 的子集（bgDelegateGroups Pass 3 的 listDelegateChildren(g.sid)）
//      ——node/dispatcher 行不是组头 ⇒ 它们的 store 子条目**没有任何读口**，终态后
//      从面板消失。这不是「确实没创建」，是 UI 漏显示。
// 读口：**沿用既有唯一读口** listDelegateChildren（delegateChainStub → provider 镜像；
//   夹具态读夹具，产品态读 /api/session-children/:id 镜像）。节点父的缺口只在
//   「provider 从不为 node-/dispatcher- 父拉这一拉」——每次读取时通过 provider 的
//   TTL 闸门按需补拉（ensureChildren，自带 60s TTL/在飞闸门），镜像
//   到达后由 'delegate-chain-mirror-updated' 触发重渲染。读面仍只有一个读口。
const BG_SCOPE_CHILD_TTL_MS = 60 * 1000;

/** 取某 node/dispatcher 会话的 store 子条目（缺缓存 ⇒ 触发一次补拉并返回当前读数）。
 *  **展示专用**：不改行集判据、不参与去重决策（去重仍在 bgRowPlan 收敛单点）。
 *  @param {string} sid 父会话 id @returns {Array<any>} */
function bgScopeStoreChildren(sid) {
  const key = String(sid || '').replace(/^team-/, '');
  if (!key) return [];
  const rows = listDelegateChildren(key);
  ensureChildren(key, BG_SCOPE_CHILD_TTL_MS);
  return Array.isArray(rows) ? rows : [];
}

/** store 条目 → 行计划 info 形（供树/去重/点击锚共用；渲染面走 bgStoreChildHtml 的
 *  store 分支，status 词/恢复 chip 仍由该构造器单点给）。缺键容忍、零臆造。
 *  @param {any} child @param {string} parentSid @returns {any} */
function bgScopeStoreInfo(child, parentSid) {
  const c = child && typeof child === 'object' ? child : {};
  const status = String(c.status || '');
  return {
    sessionId: String(c.taskId || ''),
    parentSessionId: String(c.parentSessionId || parentSid || ''),
    name: String(c.agentName || ''),
    task: String(c.description || c.prompt || ''),
    kind: String(c.source || ''),
    status: status === 'failed' || c.incomplete ? 'Error' : (status === 'completed' || status === 'cancelled') ? 'Idle' : 'Processing',
    done: !c.incomplete && (status === 'completed' || status === 'cancelled'),
    ...(c.executionStatus ? { frozen: false, freeze: null, waiting: null, stuck: null } : {}),
    executionStatus: String(c.executionStatus || ''),
    terminalReason: String(c.terminalReason || ''),
    currentTool: c.activityLabel || c.executionStatus || null,
    lastActivityMs: Number(c.activityAt) || 0,
    incomplete: c.incomplete === true,
    committedRevision: c.committedRevision,
    runId: c.runId,
    resultRef: c.resultRef,
    startedAt: Number(c.spawnedAt) || 0,
    retryCount: Number(c.retryCount) || 0,
    diagnostic: String(c.diagnostic || ''),
  };
}

/** Silent-completion diagnostic (delegate empty-receipt governance): terminal
 *  store rows carry "[delegate-diagnostic] no-final-text; turns=…; toolCalls=…;
 *  commits=…; changedFiles=…[; empty-run]" when the final assistant turn was
 *  silent. Shapes the L3 span (marker prefix stripped, full text in title);
 *  the empty-run variant takes the shared warn tone — a completed card that
 *  did nothing must not read as a clean completion. Returns '' when absent. */
function bgTaskDiagHtml(raw) {
  const diag = String(raw || '').replace(/^\s*\[delegate-diagnostic\]\s*/, '').trim();
  if (!diag) return '';
  const emptyRun = diag.indexOf('empty-run') !== -1;
  return '<span class="bg-task-diag' + (emptyRun ? ' bg-task-diag-warn' : '')
    + '" role="' + (emptyRun ? 'alert' : 'note')
    + '" title="' + escapeHtml(diag) + '">' + escapeHtml(diag) + '</span>';
}

/** flow-step node status → panel row state. One table for the row renderer and the active count (a dirty value
 *  falls back to the conservative idle). */
const BG_FLOW_NODE_STATE = {
  pending: 'idle', running: 'active', completed: 'done',
  failed: 'error', cancelled: 'done', blocked: 'stuck',
};

/** flow 节点状态 → 面板状态词键（ROW_STATUS 值域；既有键复用，零新键）。 */
const BG_FLOW_WORD_KEY = {
  pending: 'task.nodePending',
  running: 'task.inProgressShort',
  blocked: 'task.nodeBlocked',
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
};

/** 显示名契约批（2026-10-22 · 子智能体面板行显示名修复）：取文本首行（批次标题/
 *  任务描述均为多行全文，行主名只用首行——CSS 单行截断省略号 + title tooltip
 *  承载完整名）。空白文本 → ''（绝不产出空白主名）。 */
function bgFirstLine(text) {
  const s = String(text || '').trim();
  if (!s) return '';
  const nl = s.indexOf('\n');
  return (nl === -1 ? s : s.slice(0, nl)).trim();
}

/**
 * 两级结构组装（C2）：groups = delegate 组头（含子行三源）；leftovers = 未被认领的
 * 非 delegate 行（原序）。子行三源：
 *   ① live 行被 G3 映射认领（childSessionId → parent，映射单点 = G3 store，零前端派生）；
 *   ② G3 store 子条目（不在注册表的终态子——刷新/终态后 live 帧缺席，G3 投影 = 结构
 *      唯一供源）；
 *   ③ agentflow flow 结构行（chain.nodes 的 flow-step 投影；live workflow-* 会话在
 *      delegate 作用域自根成桶不经本面板，画布态细粒度结构 = S10 面非本面板职责）。
 * 嵌套 delegate（G3 source=delegate）作为**子行**渲染（C2 两级帽——其子结构随其
 * 详情弹窗下钻，不在本面板再展开）。
 *
 * 面板树批（2026-10-29 ①）：新增第④认领源 = live/快照行的 `parentSessionId`
 * （各 spawn 路由单点注入 + activeAgents 快照出帧）——store 镜像未及刷新时子行
 * 也能即时挂到可见父 delegate 组头下（同桶兄弟同级缩进）。孤儿裁定：ownerOf 命中
 * 但父组头不可见（父已终态 / 父为 C1 内部件 / 父即桶根会话）的行**提升顶层**并随
 * `orphanBy` 携带来源（渲染面出「来自 …」角标）；parent = 桶根会话的行**不算认领**
 * （根会话自身不是行——root 直派的 delegate 恒为一等组头，既有语义零回归）。
 *
 * Flow 会话可见性批（2026-11-01）：新增第⑤认领源 = **delegate 作用域桶链会话**
 * （`sessionBgAgents[delegateSid]`，引擎自根事实——见 bgDelegateScopeChainRows）；
 * 第⑥组源 = **延续态组头**（delegate live 行已离场而链仍在跑——注册行镜像合成
 * 组头，徽标「链运行中」，链全终态即随行集消失）。flow-step 结构投影（第③源）
 * 与 live 链行**互斥**：作用域桶在场（live 面更富：状态/运行时长实时）时结构投影
 * 让位，桶空时结构投影照旧兜底（既有形态零回归）。
 *
 * @param {Array<[string, any]>} entries visibleBgAgentEntries 产出
 * @param {string} bucketSid 桶根会话 id（root-parent 豁免判据）
 * @returns {{groups: Array<Object>, leftovers: Array<Array<any>>, orphanBy: Map<string, {parentSid: string, parentTitle: string}>}}
 */
function bgDelegateGroups(entries, bucketSid) {
  const rows = listDelegateRows();
  const rowByDelegate = new Map();
  for (const r of rows) rowByDelegate.set(String(r.delegateId || ''), r);
  const childOwner = new Map(); // childSessionId → parentDelegateId（G3 投影；首写胜出）
  for (const r of rows) {
    if (r.external === true) continue; // 外部执行器零平台工具 ⇒ children 恒空集（免打点）
    for (const c of listDelegateChildren(r.delegateId)) {
      const cid = String((c && c.taskId) || '');
      if (cid && !childOwner.has(cid)) childOwner.set(cid, String(r.delegateId || ''));
    }
  }
  // 面板树批：live/快照 parentSessionId 认领源（root-parent 与自指豁免——见上注）。
  const liveParentOf = new Map(); // childSessionId → parentSessionId
  for (const [id, info] of entries) {
    const sid = String((info && info.sessionId) || id || '').replace(/^team-/, '');
    const p = String((info && info.parentSessionId) || '').trim();
    if (p && p !== sid && p !== String(bucketSid || '')) liveParentOf.set(sid, p);
  }
  const ownerOf = (sid) => childOwner.get(sid) || liveParentOf.get(sid) || '';
  // Pass 1：组头认领（delegate-* / external-*；被任一源认领的嵌套 delegate 不作组头）。
  const groups = [];
  const groupByDelegate = new Map();
  const leftovers = [];
  for (const [id, info] of entries) {
    const sid = String((info && info.sessionId) || id || '').replace(/^team-/, '');
    if (!ownerOf(sid) && (sid.startsWith('delegate-') || sid.startsWith('external-'))) {
      const row = rowByDelegate.get(sid) || null;
      const key = String((row && (row.taskNo || row.delegateId)) || sid);
      const g = { id, info, sid, row, key, children: [] };
      groups.push(g);
      groupByDelegate.set(sid, g);
    } else {
      leftovers.push([id, info]);
    }
  }
  // Pass 2：live 子行认领（父组缺席 = 跨窗/竞态/父不可见 ⇒ leftover 原样呈现，
  // 零丢失；孤儿来源随 orphanBy 下传渲染面标注）。
  const orphanBy = new Map(); // childSid → {parentSid, parentTitle}
  for (const pair of leftovers.slice()) {
    const [id, info] = pair;
    const sid = String((info && info.sessionId) || id || '').replace(/^team-/, '');
    const parent = ownerOf(sid);
    const g = parent ? groupByDelegate.get(parent) : null;
    if (g) {
      g.children.push({ type: 'live', id, info, sid });
      leftovers.splice(leftovers.indexOf(pair), 1);
    } else if (parent) {
      const prow = rowByDelegate.get(parent) || null;
      orphanBy.set(sid, { parentSid: parent, parentTitle: String((prow && prow.title) || '') });
    }
  }
  // Pass 3：store 子条目（去重——已在场的 live 子行不重复）+ 作用域桶链会话
  // （第⑤源）+ flow 结构行。Flow 会话可见性批（2026-11-01）：flow-step 结构投影
  // 与 live 链行互斥——作用域桶在场（live 面更富：状态/运行时长实时）时结构投影
  // 整组让位（全量结构走链视图下钻），桶空时结构投影照旧（既有形态零回归）。
  for (const g of groups) {
    const present = new Set(g.children.map((c) => c.sid));
    for (const c of listDelegateChildren(g.sid)) {
      const cid = String((c && c.taskId) || '');
      if (cid && !present.has(cid)) {
        g.children.push({ type: 'store', child: c, sid: cid });
        present.add(cid);
      }
    }
    for (const c of bgDelegateScopeChainRows(g.sid)) {
      if (c.sid && !present.has(c.sid)) {
        g.children.push({ type: 'chain', id: c.id, info: c.info, sid: c.sid });
        present.add(c.sid);
      }
    }
    g.chainLive = g.children.some((c) => c.type === 'chain' && !bgRowTerminal(c.info));
    if (!g.children.some((c) => c.type === 'chain')) {
      const chain = g.key ? getChainByTask(g.key) : null;
      const flowSteps = chain && Array.isArray(chain.nodes)
        ? chain.nodes.filter((n) => n && n.kind === 'flow-step')
        : [];
      for (const n of flowSteps) g.children.push({ type: 'flow', node: n });
    }
  }
  // Pass 4（Flow 会话可见性批 · 第⑥组源）：delegate live 行已离场而链仍在跑——
  // 注册行镜像（DelegateRegistry.Record 投影，spawn 落盘跨重启存活）合成延续态
  // 组头。判据三连：镜像行在场 ∧ 本桶无该 delegate 组头（live 行离场）∧ 作用域桶
  // 非空。链全终态后作用域桶行被终态清理删除 → 判据失效 → 合成组头自然消失
  // （延续徽标绝不滞留死链——「链全终态后恢复普通终态样式」由 live 行在场窗口
  // 与桶残留终态行的短暂窗口承接）。合成组头只挂链子行（第⑤源；store/flow 投影
  // 不重复展开——链详情走既有链视图下钻）。
  const headSids = new Set(groups.map((g) => g.sid));
  for (const r of listDelegateRows()) {
    const d = String(r.delegateId || '').replace(/^team-/, '');
    if (!d || headSids.has(d)) continue;
    const chain = bgDelegateScopeChainRows(d);
    if (!chain.length) continue;
    const chainLive = chain.some((c) => !bgRowTerminal(c.info));
    const g = {
      id: 'chain:' + d,
      info: {
        name: String(r.name || ''),
        sessionId: d,
        kind: 'Delegate',
        // 终态呈现基座：failed → error 词档，其余（completed/cancelled/…）→ done。
        status: String(r.status || '') === 'failed' ? 'Error' : 'Completed',
        done: String(r.status || '') !== 'failed',
        startedAt: Number(r.createdAt) || 0,
        task: String(r.title || ''),
        delegateName: String(r.name || ''),
      },
      sid: d,
      row: r,
      key: String(r.taskNo || d),
      children: chain.map((c) => ({ type: 'chain', id: c.id, info: c.info, sid: c.sid })),
      synthesized: true,
      chainLive,
    };
    groups.push(g);
    headSids.add(d);
  }
  return { groups, leftovers, orphanBy };
}

// ── 行集收敛批（2026-11-02 · 依据 RCA reports/20261102_191631_agent-panel-dup-rows）
// 症状：面板里两行一模一样的 `[节点] stand conversation-heads`，引擎侧只有一个
// 会话（flow-map / 注册表 / 日志三证）。根因 = `bgPanelModel()` 只有**组内**去重
// （Pass3 的 present 集合是每组一份），没有任何**跨通路**的 sessionId 全局收敛：
// 同一个会话既能作某组头的链子行（作用域桶通路 sessionBgAgents[delegateSid]），
// 又能作主桶 leftover（快照 / live 认领通路 → 顶层行）再出现一次；两条通路各自
// 建记录、各自带起点戳（RCA §2 行 4 的 22m07s = 重建行的 Date.now()），于是
// 「同一会话两行」+ 计数里含同一实体两次。
//
// 本批三律（全在本函数族单点）：
//   ① **跨通路 sessionId 全局收敛**：二级结构组装完成后按 sessionId 收敛成一行——
//      去重键 = 行定位键（bgRowLocatorKey：剥 team- 前缀的会话 id，**与渲染锚
//      同一函数**），四条通路（作用域桶链行 / 快照 / live 认领 / 合成组头）
//      一视同仁。胜出序固定：层级（组头 > 子行 > 顶层——有结构的行胜过游离行）
//      × 同层起点戳最早（真 spawn 时刻）；落选行携带而胜出行缺失的上下文（孤儿
//      来源角标 / 活链判据）随收敛下传——收敛只去掉重复的那一行，不去掉它承载
//      的信息。
//   ② **计数由行集派生**：total = 收敛后的行集长度（bgPanelModel().total 内联
//      自 bgRowPlan().items），不再有 `entries.length + chainChildren` 这套独立账。
//   ③ **并列定序固定**：二级结构内保持树序（组头→其子行，bgDelegateScopeChainRows
//      的分发器置顶/起点升序口径不变）；顶层行（leftover——长空闲行的主要来源）
//      按 [终态沉底 → 起点戳升序 → 会话 id 字典序] 定序，跨帧逐位一致。

/** 行层级序（收敛胜出序的第一维）：组头 > 子行 > 顶层。子行胜过顶层 = 「挂在
 *  父下的行」承载的结构信息（缩进/归属）比游离的顶层行多——同一会话两条通路
 *  并存时，收敛保留有结构的那条（RCA 截图里刺眼的两行正是两条无结构顶层脸）。 */
const BG_ROW_LEVEL_RANK = { head: 0, child: 1, top: 2 };

/** 行定位键（行锚单点：`data-bg-key` / 点击详情解析的同一条缝）：info.sessionId 是
 *  会话真身（剥 team- 前缀 = ui.json 键），id 只是数据面键（可能来自某个已消失的
 *  通路）；两者皆缺 ⇒ 回 id。收敛判等与渲染锚共用本函数——判等用一个键、渲染用
 *  另一个键是「同一会话两行」的温床，此处不允许出现第二套。
 *  显式 `identity` 优先：store / flow 结构子行没有 info（状态由 store 条目 / 链节点
 *  承载），其身份 = 子任务 id / flow 节点 id——不能落回空键（空键会把整族结构行
 *  收敛成一行，那是本批的反向事故）。 */
function bgRowLocatorKey(row) {
  const explicit = String((row && row.identity) || '');
  if (explicit) return explicit.replace(/^team-/, '');
  const id = String((row && row.id) || '');
  const info = (row && row.info) || {};
  return String(info.sessionId || id || '').replace(/^team-/, '');
}

/** 行身份（收敛键）= 定位键（同一函数，不另立口径）。 */
function bgRowSessionKey(row) {
  return bgRowLocatorKey(row);
}

/** 起点戳（「保留最早者」判据；缺失视为最晚——有真实戳的记录更可信）。 */
function bgRowStartedAt(row) {
  const n = Number(((row && row.info) || {}).startedAt) || 0;
  return n > 0 ? n : Number.MAX_SAFE_INTEGER;
}

/** 并列定序键（第三律）：终态沉底 → 起点戳升序 → 会话 id 字典序。
 *  RCA §4 的教训：一堆「空闲 N 分钟」的长空闲行并排时，若顺序依赖对象键序或
 *  快照到达序，用户每帧看到的行序都会换位——把并列裁决交给唯一稳定的字符串序。 */
function bgRowOrderKey(row) {
  return [bgRowTerminal((row && row.info) || {}) ? 1 : 0, bgRowStartedAt(row), bgRowSessionKey(row)];
}

/** 数组字典序比较。 */
function bgRowOrderCmp(a, b) {
  for (let i = 0; i < a.length; i++) {
    if (a[i] < b[i]) return -1;
    if (a[i] > b[i]) return 1;
  }
  return 0;
}

/**
 * 行树（层级缩进批 2026-11-05 腿② · 作者令「节点也分很多层，每一层都应该是上一层的
 * 缩进」）：**深度 / 子树 / 树序**三合一单点——渲染面与回归 dump 只读这一份，绝不在
 * 第二个地方重新推算深度（两套深度口径 = 层级与折叠打架的温床）。
 *
 * 深度按**真实父子链接**算，不按渲染顺序猜。父链接来源沿用既有四源（与 bgDelegateGroups
 * 同一批源，不另立口径）：
 *   ① live / 快照行 `info.parentSessionId`（各 spawn 路由单点注入 + 快照出帧）；
 *   ② G3 child owner（store 子条目自带 parentSessionId；缺失 ⇒ 所属整体行）；
 *   ③ 作用域桶链行（同①：`routeSubagentWsSend` 注入的 parentSessionId 原样在 info 上）；
 *   ④ flow 结构投影（chain.nodes flow-step：bgDelegateGroups 既有归属 = 整体行——
 *      `deps` 是 DAG 依赖边不是父子链接，不据此加层）。
 *
 * 规约：整体行 depth=0；成员行 depth = 父行 depth+1，**任意层数都成立**；父不在行集 /
 * 跨组父 / 自指 / 环 ⇒ 回落「所属整体行下一格」（行绝不因为父缺席而消失——铁律：
 * 元素不许为了腾地方而消失）。成树只在**同一整体行内**（跨组父链接是组间关系，
 * 不是该组内的层）。
 *
 * 面板三代层级批（2026-11-06 · 作者令「root → dispatcher → node 三级真缩进——每一层
 * 都是上一层的缩进」）：顶层行（leftover——dispatcher-、node- 等 flow 行的主脸）参与
 * 父链接，父键装配单点 = `bgTreeParentKeyOf`（白名单 + deps 显式判定，见其注释）。
 * **属主边（parentSessionId 来的父子）不受同组约束限制，跨组照挂**：顶层行的父锚可以
 * 是 root 锚（属主=桶根——锚无组）、另一条顶层行（dispatcher → node）、合成延续组头
 * （父行在场的情形）；同组约束 `cand.group === row.group` 原样留在**成员行**挂接路径
 * （delegate 域零回归——design 残差②/⑨）。组头与 root 锚是根位两档，永不挂父（组头
 * 恒 depth 0 的既有帽不回退既有形态）。deps 仍不算父子：该口径从注释升级为代码结论
 * （bgDepsParentPolicy——作者令「不许只有注释」），deps/chain 结构字段不进白名单。
 *
 * @param {Array<any>} rows 行计划项（bgRowPlan 基座序）
 * @returns {{order: Array<any>, roots: Array<any>, parentOf: Map<any, any>, kids: Map<any, Array<any>>, depth: Map<any, number>}}
 */
function bgRowTree(rows) {
  const list = Array.isArray(rows) ? rows : [];
  /** 定位键 → 行（键收敛后本应唯一；重键只取首个，零丢失靠下面的兜底序）。 */
  const byKey = new Map();
  for (const row of list) {
    const k = bgRowSessionKey(row);
    if (k && !byKey.has(k)) byKey.set(k, row);
  }
  // root 锚（三代层级批）：锚在行集里至多一条（bgRowPlan 合成守卫）——判定表②
  // 「父应在位置」的挂接基座。其定位键 = 桶根会话键（锚条款的 bucketKey 入参）。
  let rootAnchor = null;
  for (const row of list) {
    if (row && row.level === 'root') { rootAnchor = row; break; }
  }
  const anchorKey = rootAnchor ? bgRowSessionKey(rootAnchor) : '';
  const regBySid = bgRegistryRowsBySid();
  /** 父行表（null = 根）。 */
  const parentOf = new Map();
  for (const row of list) {
    parentOf.set(row, null);
    // 根位两档永不挂父：组头（既有帽：组头恒 depth 0——delegate 域零回归）与
    // root 锚（树 depth 0 基座本身，三代层级批）。
    if (!row || row.level === 'head' || row.level === 'root') continue;
    const self = bgRowSessionKey(row);
    // ①②③④ 父键装配单点（bgTreeParentKeyOf：白名单 + deps 显式判定）。
    const pk = bgTreeParentKeyOf(row);
    const cand = (pk && pk !== self) ? byKey.get(pk) : null;
    if (cand && cand !== row) {
      if (row.level === 'top' || row.level === 'struct') {
        // 属主边（parentSessionId 来的父子）不受同组约束限制，跨组照挂——顶层行的
        // 父锚：root 锚（锚无组）、另一条顶层行（dispatcher → node）、合成延续组头
        // （父行在场的情形）。作者令逐字「每一层都是上一层的缩进」。face C：结构父
        // 行（level='struct'）同款跨组挂接（嵌套 delegate 的结构父可挂另一结构父下）。
        parentOf.set(row, cand);
        continue;
      }
      // 成员行（delegate 域）：同组约束原样（design 残差②/⑨——delegate 域零回归）。
      if (cand.group === row.group) { parentOf.set(row, cand); continue; }
    }
    // 三代层级批·判定表②（有父但不在场 → 父应在位置）：父键非空而父行缺席的三代
    // 链行挂 root 锚（父的槽位 = 锚直下 depth1）——绝不落野组、绝不伪造 depth2。
    // 判定读单点（bgTopRowParentStateOf）；锚缺席（异常直调）⇒ 走既有兜底留根位。
    if (row.level === 'top' && pk && !cand && rootAnchor && rootAnchor !== row
        && bgTopRowParentStateOf(row, byKey, anchorKey, regBySid) === 'absent-parent') {
      parentOf.set(row, rootAnchor);
      continue;
    }
    // 回落：所属整体行下一格（成员行：父不在行集 / 跨组父 / 自指——既有兜底原样；
    // 顶层行无组 ⇒ head 恒空：判定表③ wild 行留待野组归置，registered-absent 行
    // 留根位（既有口径），不伪造深度）。
    const g = row.group;
    const groupKey = bgRowLocatorKey({ id: g && g.id, info: g && g.info });
    const head = groupKey ? byKey.get(groupKey) : null;
    if (head && head !== row && head.group === row.group) parentOf.set(row, head);
  }
  /** 深度（沿父链走，seen 防环；父链断在根 ⇒ 0）。 */
  const depth = new Map();
  for (const row of list) {
    let d = 0;
    let cur = parentOf.get(row) || null;
    const seen = new Set([row]);
    while (cur && !seen.has(cur)) { seen.add(cur); d += 1; cur = parentOf.get(cur) || null; }
    depth.set(row, d);
  }
  /** 子树索引（兄弟序 = 基座序）。 */
  const kids = new Map();
  const roots = [];
  for (const row of list) {
    const p = parentOf.get(row) || null;
    if (p) {
      const arr = kids.get(p) || [];
      arr.push(row);
      kids.set(p, arr);
    } else roots.push(row);
  }
  /** 树序（前序：父行后紧跟其整棵子树）——同父兄弟保持基座序，无层级的行序零变化。 */
  const order = [];
  const placed = new Set();
  const walk = (row) => {
    if (!row || placed.has(row)) return;
    placed.add(row);
    order.push(row);
    for (const k of kids.get(row) || []) walk(k);
  };
  for (const r of roots) walk(r);
  // 环/异常父链防御：树外的行按基座序兜底出镜（绝不因结构算不清而丢行）。
  for (const row of list) if (!placed.has(row)) walk(row);
  return { order, roots, parentOf, kids, depth };
}

/**
 * deps 父子边显式判定（面板三代层级批 2026-11-06 · 作者令「立一个具名函数，返回显式
 * 结论，渲染只读它；不许只有注释」）：bgRowTree 源注释旧 ：949-955 明文「deps 是 DAG
 * 依赖边不是父子链接，不据此加层」——本批把这句话升级为**代码判定**。
 *
 * 结论（显式对象，一句话理由随结论携带）：
 *   `parentEdgeFromDeps: false` —— deps（flow-step 的 DAG 依赖字段）**不产生**父子
 *   缩进边。理由：deps 是执行结算顺序事实（谁先谁后跑），不是会话属主关系；面板树
 *   的父子唯一来源 = 注册属主边 `parentSessionId`（成员行另有「所属整体行」兜底）；
 *   按 deps 连边会造出假层级与环（A deps B 且 B deps A 的 DAG 合法），且 deps 增删
 *   会拖动行深度——树的形状必须只随属主事实走。
 *
 * 渲染面消费点：`bgTreeParentKeyOf`（白名单装配单点）在装配前读本结论——白名单扩容
 * 必须先改这份判定，不许在第二处内联放行。
 * @returns {{parentEdgeFromDeps: boolean, reason: string}}
 */
export function bgDepsParentPolicy() {
  return {
    parentEdgeFromDeps: false,
    reason: 'deps 是 DAG 执行依赖边（结算顺序事实），不是会话属主关系；树父子唯一来源 = 注册属主边 parentSessionId（成员行另有「所属整体行」兜底）。按 deps 连边会造出假层级与环。',
  };
}

/**
 * 行树父键白名单单点（面板三代层级批 2026-11-06 · design 残差⑤）：一行「挂在谁下面」
 * 的**唯一**装配点——bgRowTree 的挂父路径与 bgRowParentKeyOf（野判定输入面）都只读它，
 * 不留第二套内联装配（防「第二套深度口径」）。
 *
 * 白名单（正序短路；deps/chain 结构字段**不进白名单**——准入与否由 bgDepsParentPolicy()
 * 结论判定，本函数在装配前读它）：
 *   ① live / 快照行 `info.parentSessionId`（注册属主边——各 spawn 路由单点注入 +
 *      快照出帧）；
 *   ② G3 store 子条目自身 `parentSessionId`（终态子行，无 info 的结构行）；
 *   ③ 成员行（level='child'）①②皆空 ⇒ 所属整体行定位键（flow 结构投影等无父字段
 *      成员行的既有兜底——「挂所属整体行」口径原样）；
 *   ④ 顶层行 ①空 ⇒ null（无父字段 = 无父——不是「父是根」；落野判定面处置）。
 * @param {any} row @returns {string|null} 父定位键；null = 无父
 */
export function bgTreeParentKeyOf(row) {
  const info = (row && row.info) || {};
  const c = (row && row.child) || {};
  // deps 显式判定（作者令「渲染只读它的结论」）：白名单装配前先读——结论为 false 时
  // deps 分支不可达，deps/chain 字段永不充当父子键。
  const policy = bgDepsParentPolicy();
  if (policy.parentEdgeFromDeps) {
    // 白名单扩容位：结论放行时才允许读 deps 形字段（今天不可达——结论恒 false）。
    const deps = (c.type === 'flow' && Array.isArray(c.node && c.node.deps))
      ? c.node.deps.map((d) => String(d || '').replace(/^team-/, '').trim()).filter(Boolean)
      : [];
    if (deps.length) return deps[0];
  }
  let pk = String(info.parentSessionId || '').replace(/^team-/, '').trim();
  if (!pk && c.type === 'store') {
    pk = String((c.child && c.child.parentSessionId) || '').replace(/^team-/, '').trim();
  }
  if (!pk && row && row.level === 'child') {
    const g = row.group;
    pk = bgRowLocatorKey({ id: g && g.id, info: g && g.info });
  }
  return pk || null;
}

/** 行父键（野节点判定式的输入面单点）：一行「挂在谁下面」——与 bgRowTree 的父链接
 *  同源（bgTreeParentKeyOf 白名单装配：live/快照 parentSessionId → store 子条目自身
 *  parentSessionId → 所属整体行定位键），不另立口径。顶层 leftover 行没有 group ⇒
 *  只剩 parentSessionId 一源（无该字段 = 无父，不是「父是空串」）。字符串返回形
 *  （'' = 无父）由本包装维护——树面用 bgTreeParentKeyOf 的 null 形。
 *  @param {any} row @returns {string} */
function bgRowParentKeyOf(row) {
  return bgTreeParentKeyOf(row) || '';
}

/** 三代链行判定（面板三代层级批 2026-11-06）：这行是不是「root → dispatcher → node」
 *  三代链上的行——父态判定表（bgTopRowParentStateOf）②态的**作用域判据**。判据 =
 *  行自身会话键前缀（dispatcher- = 分发器代 / node- = 节点代；两代的 parentSessionId
 *  由后端 spawn 注册单点写入——ProjectActor.spawnDispatcher / NodeStarter /
 *  NodeLoopRunner）。delegate-/subagent-/旧盘前缀不进本批作用域（既有野组归置口径
 *  零回归——bg-agentflow-orphans 契约不动）。
 *  @param {string} sid @returns {boolean} */
function bgThreeGenRowSid(sid) {
  const s = String(sid || '');
  return s.startsWith('dispatcher-') || s.startsWith('node-');
}

/** 注册行镜像索引（按 delegate id；同 bgDelegateGroups 的 rowByDelegate 形制）。
 *  @returns {Map<string, any>} */
function bgRegistryRowsBySid() {
  const m = new Map();
  for (const r of listDelegateRows()) {
    const d = String((r && r.delegateId) || '').replace(/^team-/, '');
    if (d) m.set(d, r);
  }
  return m;
}

/** 父会话在册可解析（判定式③条款单点——自野判定式原文收编，语义零改动）：
 *  注册行镜像（DelegateRegistry.Record，spawn 落盘、跨重启存活）命中**且**其链可解析
 *  （getChainByTask 有 nodes，或该 delegate 的作用域桶里有链行）——此时 bgDelegateGroups
 *  第⑤/⑥组源会为它产出整体行，父键迟早可定位（跨帧竞态不算野）。
 *  @param {string} pk @param {Map<string, any>=} regBySid 预建镜像索引（缺席则现建）
 *  @returns {boolean} */
function bgRegistryResolvable(pk, regBySid) {
  const key = String(pk || '').replace(/^team-/, '');
  if (!key) return false;
  const reg = (regBySid || bgRegistryRowsBySid()).get(key);
  if (!reg) return false;
  const chain = getChainByTask(String(reg.taskNo || key));
  if (chain && Array.isArray(chain.nodes) && chain.nodes.length) return true;
  return bgDelegateScopeChainRows(key).length > 0;
}

/**
 * 顶层行父态判定单点（面板三代层级批 2026-11-06 · 单点函数 + 判定表）。
 *
 * **野节点三态判定表**（作者令「有父且在册→真子行 / 有父但不在场→父应在位置+「父不在场」
 * 标，不进归置组 / 无父→既有归置组」；作用域 = level='top' 顶层行——head/child/root
 * 结构性有根，不进本表；正序短路）：
 *
 *   态①（attached）        条件：pk 非空 ∧ 父在场（父行在本轮行集，含 pk=桶根 ⇒ root 锚恒在条款）
 *                           落位：真子行——挂父行（bgRowTree 属主边），depth 沿父链 0/1/2
 *                           标：无——父可见，「来自 …」来源标即撒谎，不带
 *   态②（absent-parent）   条件：pk 非空 ∧ 父不在行集 ∧ 三代链行（dispatcher-/node- 前缀）
 *                                ∧ 父不在册可解析
 *                           落位：父应在位置——挂 root 锚直下（父的槽位 depth1，**不伪造
 *                                depth2**；锚缺席由 bgRowPlan 按需合成）——**不进归置组**
 *                           标：「父不在场」标（tooltip=父 sid），不进野组
 *   态③（wild）            条件：pk 空 / 自指；或不可解析且非三代链行（subagent-/dd-/
 *                                legacy uuid …）
 *                           落位：既有野组 data-bg-orphan-group（2026-11-05 腿③口径逐字不变）
 *                           标：既有「来自 …」来源角标（逐字不变）
 *
 * 附（非三态的第四出口）：pk 非空 ∧ 父不在行集 ∧ **在册可解析**（bgRegistryResolvable
 * 命中——delegate 域跨帧竞态，父的归属结构迟早成行）＝既有「非野顶层行」口径原样
 * （`registered-absent`：不挂锚、不加标、不进野组——delegate 域零回归）。
 *
 * **为什么必须是一个函数**：三态若散在渲染分支里，会出现「同一行在两个通路两个态」
 * ——树挂接（bgRowTree）、锚合成（bgRowPlan）、野判定（野判定式单点）三处消费面
 * 只读本函数结论，判定点唯一。
 * @param {any} row 行计划项（level='top'）
 * @param {Map<string, any>} byKey 本轮行集索引（bgRowSessionKey → row）
 * @param {string} [bucketKey] 桶根会话键（root 锚条款；空 = 无桶根信息）
 * @param {Map<string, any>=} [regBySid] 预建注册镜像索引（性能位，缺席现建）
 * @returns {'attached'|'absent-parent'|'registered-absent'|'wild'}
 */
function bgTopRowParentStateOf(row, byKey, bucketKey, regBySid) {
  const self = bgRowSessionKey(row);
  const pk = bgRowParentKeyOf(row);                         // 输入面单点（字符串形：'' = 无父）
  if (!pk || pk === self) return 'wild';                    // 态③：无父字段 / 自指（父键不可用作事实）
  const bk = String(bucketKey || '').replace(/^team-/, '');
  if (bk && pk === bk) return 'attached';                   // 态①：属主=桶根（root 锚恒在）
  if (byKey && byKey.has(pk)) return 'attached';            // 态①：父行在本轮行集（整体行/顶层行/成员行/锚）
  if (bgRegistryResolvable(pk, regBySid)) return 'registered-absent'; // 既有③条款：在册+链可解析 ⇒ 跨帧竞态不算野
  if (bgThreeGenRowSid(self)) return 'absent-parent';       // 态②：三代链行·父不在场（父应在位置，不进野组）
  return 'wild';                                            // 态③：其余不可解析（既有野组口径）
}

/**
 * ***野节点判定单点***（野节点归置批 2026-11-05 腿③ · 作者点名「很多的野节点」）。
 *
 * **判定式**：一行是野节点 ⇔ 它**没有可解析父**。父可解析三条款（逐条在案，任一成立
 * 即「有父」）：
 *   ① **父整体行 key 可定位** —— 父键在本轮行集里命中一条 level='head' 的行
 *      （整体行 = 一次编排的根；其定位键 = bgRowLocatorKey，与 data-bg-key 同一把缝）；
 *   ② **父行同屏可见且键可定位** —— 父键命中本轮行集里的**任何**一行（含成员行：
 *      父是一个节点时，子行仍属同一棵树，bgRowTree 按真实父链接成层）；
 *   ③ **父会话在册且链可解析** —— 父键命中注册行镜像（DelegateRegistry.Record，
 *      spawn 落盘、跨重启存活）**且**其链可解析（getChainByTask 有 nodes，或该
 *      delegate 的作用域桶里有链行）——此时 bgDelegateGroups 第⑤/⑥组源会为它产出
 *      整体行，父键迟早可定位（跨帧竞态不算野）。
 * 三条皆不成立 ⇒ 野：父会话已离场**且**注册行缺席，或父链根本不可解析（无父字段、
 * 父是历史 uuid 会话、父是永不 recap 的 node/子代理会话）。
 *
 * 成员行（level='child'）恒有父：它的结构父 = 所属整体行，而整体行就在同一行集里
 * （跨组父断链的成员行按 bgRowTree 回落挂在整体行下一格——回落是层级问题，不是
 * 血缘缺失）。所以本函数只在**顶层 leftover 行**上做判定，返回野行的定位键集合。
 *
 * **为什么必须是一个函数**：野性若散在渲染分支里，会出现「同一行在两个通路上一个
 * 算野一个不算」——本批的铁律是渲染面只读本函数结论（row.wild），判定点唯一。
 *
 * @param {Array<any>} rows 行计划项（bgRowPlan 的 items：已收敛、已树序）
 * @returns {Set<string>} 野行的定位键（bgRowSessionKey 值）
 */
export function bgOrphanRowKeys(rows, bucketSid) {
  const list = Array.isArray(rows) ? rows : [];
  const byKey = new Map();
  for (const row of list) {
    const k = bgRowSessionKey(row);
    if (k && !byKey.has(k)) byKey.set(k, row);
  }
  // 三代层级批（2026-11-06）：桶根键（root 锚 = 树 depth 0 基座，恒在——见 bgRowPlan
  // 锚合成）。属主 = 桶根的行（dispatcher 行等）永不野。
  const bucketKey = String(bucketSid || '').replace(/^team-/, '');
  // ③ 的在册面：注册行镜像按 delegate id 索引（预建一次随判定下传）。
  const regBySid = bgRegistryRowsBySid();
  const wild = new Set();
  for (const row of list) {
    if (!row || row.level === 'head' || row.level === 'root') continue; // 整体行 / root 锚是根，永非野
    if (row.level === 'struct') continue;       // 结构父（face C 标签面）是基座，永非野
    if (row.level === 'child') continue;        // 成员行的父 = 所属整体行（在行集里）
    // 三代层级批：野 ⇔ 父态判定表③（判定单点 bgTopRowParentStateOf——②态「父不在场」
    // 的三代链行在此**不算野**：它们的归置面 = 父应在位置（root 锚直下），不进野组；
    // ①态真子行与在册可解析（registered-absent）同样非野——条款原文见判定表注释）。
    if (bgTopRowParentStateOf(row, byKey, bucketKey, regBySid) !== 'wild') continue;
    const self = bgRowSessionKey(row);
    if (self) wild.add(self);
  }
  return wild;
}

/**
 * Panel state of a planned row, for the active count. A row that carries an info record uses the shared row state.
 * Group children built from a store entry or a flow-step node carry their source object instead, so they use the
 * state tables their own renderers use: the badge and the list never disagree about what is running.
 * @param {any} row
 * @returns {string} '' when the row has no state source
 */
function bgPlanRowState(row) {
  if (row.info) return bgRowState(row.info);
  const c = row.child;
  if (c && c.type === 'store') return bgStoreChildState(String((c.child && c.child.status) || ''));
  if (c && c.type === 'flow') return BG_FLOW_NODE_STATE[String((c.node && c.node.status) || 'pending')] || 'idle';
  return '';
}

/**
 * 跨通路行集组装（收敛批第一律 + 第三律）：把二级结构的对位行与顶层 leftover
 * 合成一个**有序、按 sessionId 唯一**的渲染计划。渲染面与计数面只读本函数产出——
 * 「签与列表永不打架」自此是结构事实，而不是两次独立计算碰巧相等。
 * 野节点归置批（2026-11-05 腿③）：另返回 `orphanCount` = 归置面上的野行数（判定式
 * 单点 `bgOrphanRowKeys` 的结论计数）；它与 `total` 不同源不冲突——`total` 计全部
 * 行（野行照旧计入、一行不丢），`orphanCount` 只计其中换面出镜的那部分。
 * 面板三代层级批（2026-11-06 · 作者令「root → dispatcher → node 三级真缩进」）：
 * root 锚合成（design 残差①）——桶根会话（顶层会话）作树的 **depth 0 基座**。判定 =
 * 行集中确有属主边指向桶根的**顶层行**（bgTreeParentKeyOf === bucketSid），或确有
 * 「父不在场」的三代链行（判定表②——父应在位置 = 锚直下，锚是它们的挂接基座）才出锚
 * （锚有子树才出镜——空树不出空锚，既有形态零回归）。锚是**标签面行**：不入行集
 * 计数（total 排除 level='root'）、不占 data-bg-row-total、不入头部计数（先例 =
 * 野分组头 bgOrphanGroupHeadHtml 的「组头不是行」纪律）；锚有子树 ⇒ caret 合法。
 * 与真行同键（桶根自身在册的畸形帧）⇒ 让位真行、不出锚（防御）。
 *
 * @param {{groups: Array<Object>, leftovers: Array<Array<any>>, orphanBy: Map<string, any>}} model
 * @param {string} [bucketSid] 桶根会话 id（root 锚合成判据 + 野判定桶根条款）
 * @returns {{items: Array<Object>, total: number, activeCount: number, tree: {order: Array<any>, roots: Array<any>, parentOf: Map<any, any>, kids: Map<any, Array<any>>, depth: Map<any, number>}, orphanCount: number}}
 */
function bgRowPlan(model, bucketSid) {
  const groups = (model && model.groups) || [];
  const leftovers = (model && model.leftovers) || [];
  const orphanBy = (model && model.orphanBy) || new Map();
  /** 候选行（未收敛）：level = head/top/child；承载源对象供渲染面复用。 */
  const raw = [];
  for (const g of groups) {
    raw.push({ level: 'head', id: g.id, info: g.info, group: g });
    for (const c of g.children) {
      // store / flow 结构子行的身份 = 子任务 id / flow 节点 id（显式 identity）。
      const identity = c.type === 'store'
        ? String((c.child && c.child.taskId) || '')
        : c.type === 'flow' ? String((c.node && c.node.id) || '') : '';
      raw.push({ level: 'child', id: c.id, info: c.info, child: c, group: g, identity });
    }
  }
  for (const [id, info] of leftovers) {
    raw.push({
      level: 'top', id, info,
      orphan: orphanBy.get(String((info && info.sessionId) || id || '').replace(/^team-/, '')) || null,
    });
  }
  // 节点子智能体可见性批（2026-11-07）：node-/dispatcher- 作用域会话的 store 子条目
  // （= 终态后的子智能体结构面，见 bgScopeStoreChildren 头注的引擎侧事实）在这里并成
  // 顶层候选行——父键 = 该 node 会话 id（属主边照旧由 bgRowTree 装配 ⇒ 挂在 node 行
  // 下真缩进）。两条防重铁律：①已在本轮行集里的会话（live/快照/组内子行）不重复出；
  // ②同一父的多条 store 子条目之间也不重复（present 集合同点维护）。
  const presentSid = new Set();
  for (const row of raw) {
    const k = bgRowSessionKey(row);
    if (k) presentSid.add(k);
  }
  for (const row of raw.slice()) {
    const sid = bgRowSessionKey(row);
    if (!sid || !/^(node-|dispatcher-)/.test(sid)) continue;
    for (const child of bgScopeStoreChildren(sid)) {
      const cid = String((child && child.taskId) || '');
      if (!cid || presentSid.has(cid)) continue;
      presentSid.add(cid);
      raw.push({
        level: 'top', id: cid, info: bgScopeStoreInfo(child, sid),
        storeChild: child, orphan: null,
      });
    }
  }
  // ① 收敛：sessionId → 胜出行（先去重再定序——否则并列裁决会做在重复行上）。
  const bySid = new Map();
  for (const row of raw) {
    const sid = bgRowSessionKey(row);
    const keeper = bySid.get(sid);
    if (!keeper) { bySid.set(sid, row); continue; }
    const lv = BG_ROW_LEVEL_RANK[row.level] - BG_ROW_LEVEL_RANK[keeper.level];
    const win = lv < 0 || (lv === 0 && bgRowStartedAt(row) < bgRowStartedAt(keeper)) ? row : keeper;
    const lose = win === row ? keeper : row;
    bySid.set(sid, win);
    // 落选者携带而胜出行缺失的上下文随收敛下传（孤儿来源角标 / 活链判据）。
    if (!win.orphan && lose.orphan) win.orphan = lose.orphan;
    if (win.level === 'head' && win.group && lose.child && lose.child.type === 'chain'
        && !bgRowTerminal(lose.info || {})) {
      win.group.chainLive = true;
    }
  }
  // ③ 定序：树序基座（组头 → 该组幸存子行，组序 = bgDelegateGroups 产出序、
  // 子行序 = 组内既有序）⊕ 顶层行按稳定的并列规则排序后缀尾（长空闲行并排时
  // 不换位）。落位集合防重（每行恰好一个槽位），树外幸存行防御性兜底不丢行。
  const kept = [...bySid.values()];
  const topRows = kept.filter((r) => r.level === 'top')
    .sort((a, b) => bgRowOrderCmp(bgRowOrderKey(a), bgRowOrderKey(b)));
  const items = [];
  const placed = new Set();
  for (const g of groups) {
    for (const row of kept) {
      if (row.group !== g) continue;
      if (row.level === 'head' || row.level === 'child') { items.push(row); placed.add(row); }
    }
  }
  items.push(...topRows);
  for (const r of topRows) placed.add(r);
  // ⑥ root 锚合成（面板三代层级批 2026-11-06，见函数头注）：锚先行入列（树序 =
  // 锚子树打头——渲染循环前出镜），已入 placed 防止尾兜底重排。出锚判据两条：
  // 有属主边指向桶根的顶层行（既有），**或**有「父不在场」的三代链行（判定表②——
  // 它们的「父应在位置」= 锚直下，锚是它们的挂接基座）。
  const bucketKey = String(bucketSid || '').replace(/^team-/, '');
  const keptByKey = new Map();
  for (const r of kept) {
    const k = bgRowSessionKey(r);
    if (k && !keptByKey.has(k)) keptByKey.set(k, r);
  }
  // ── ⑦ 结构父合成（face C · 全局后台面板层级）────────────────────────────
  // 活跃/在场后代（kept 顶层行）的 parentSessionId 指向**已注册** delegate（镜像行
  // 在场）而父无 live 行 ⇒ 从注册行合成**结构节点**（level='struct'，标签面）：
  //   · 身份单源 = 注册行镜像（name/title/mode/taskStatus——spawn 落盘，零臆造；
  //     镜像缺席 ⇒ 不合成，后代走既有野组面 = 标未知而非猜）；
  //   · 已完成后代保留：结构父的 store 子条目（completed/failed——终态子行集）
  //     挂结构父下（与组头 Pass 3 同读口同窗）；镜像未到 ⇒ 下轮
  //     delegate-chain-mirror-updated 重渲补齐（增量/重连同缝）；
  //   · 不入计数（total 见下）——「父可 idle 且不计活跃数」；
  //   · 嵌套：结构父自身的 parentSessionId（注册行透传）再指注册 delegate ⇒ 链式
  //     合成（visited 防环，深度上限防御）。
  const regBySidLocal = bgRegistryRowsBySid();
  const structRows = [];
  const structByKey = new Map();
  const claimersByParent = new Map();
  for (const r of kept) {
    if (r.level !== 'top') continue;
    const pk = bgTreeParentKeyOf(r);
    if (!pk || pk === bgRowSessionKey(r) || pk === bucketKey) continue;
    if (keptByKey.has(pk)) continue; // parent already visible as a real row
    if (!claimersByParent.has(pk)) claimersByParent.set(pk, []);
    claimersByParent.get(pk).push(r);
  }
  const pending = [...claimersByParent.keys()];
  const seenStruct = new Set();
  while (pending.length && structRows.length < 24) {
    const pk = pending.shift();
    if (!pk || seenStruct.has(pk)) continue;
    const reg = regBySidLocal.get(pk) || null;
    if (!reg) continue; // unregistered parent — wild face keeps the honest unknown
    seenStruct.add(pk);
    const structRow = {
      level: 'struct',
      id: 'bg-struct:' + pk,
      identity: pk,
      info: { sessionId: pk, parentSessionId: String(reg.parentSessionId || '') },
      structRow: reg,
      group: null,
      claimers: claimersByParent.get(pk) || [],
      storeChildren: [],
    };
    structRows.push(structRow);
    structByKey.set(pk, structRow);
    const grand = String(reg.parentSessionId || '').replace(/^team-/, '').trim();
    if (grand && grand !== pk && grand !== bucketKey && !keptByKey.has(grand)) pending.push(grand);
  }
  // Store children of struct parents（已完成后代保留 + 补拉去重——present 集 = 行集
  // 键 ∪ 结构父键，同一子行不双出）。
  if (structRows.length) {
    const present = new Set([...keptByKey.keys(), ...structByKey.keys()]);
    for (const structRow of structRows) {
      for (const child of listDelegateChildren(bgRowSessionKey(structRow))) {
        const cid = String((child && child.taskId) || '');
        if (!cid || present.has(cid)) continue;
        present.add(cid);
        const info = bgScopeStoreInfo(child, bgRowSessionKey(structRow));
        const row = { level: 'top', id: cid, info, storeChild: child, orphan: null };
        structRow.storeChildren.push(row);
        kept.push(row); // participates in the tree below; not in keptByKey→placed loops above
      }
    }
    // Terminal-ness for the collapse default: all claimers + store children terminal.
    for (const structRow of structRows) {
      structRow.structAllTerminal = [...structRow.claimers, ...structRow.storeChildren]
        .every((r) => bgRowTerminal((r && r.info) || {}));
    }
    items.push(...structRows);
    for (const s of structRows) placed.add(s);
  }
  let anchorRow = null;
  if (bucketKey) {
    const hasRootChildren = kept.some((r) => r.level === 'top'
      && (bgTreeParentKeyOf(r) === bucketKey
        || bgTopRowParentStateOf(r, keptByKey, bucketKey) === 'absent-parent'));
    const keyTaken = kept.some((r) => bgRowSessionKey(r) === bucketKey);
    if (hasRootChildren && !keyTaken) {
      anchorRow = {
        level: 'root',
        id: 'bg-root:' + bucketKey,
        identity: bucketKey,
        // 标签面行：渲染走 bgRootAnchorHtml，info 仅承载定位键（状态/主名语义无消费面）。
        info: { sessionId: bucketKey },
        group: null,
      };
    }
  }
  if (anchorRow) { placed.add(anchorRow); items.unshift(anchorRow); }
  for (const r of kept) if (!placed.has(r)) items.push(r);
  // ④ 层级缩进批（2026-11-05 腿②）：基座序 → **树序**（父行后紧跟整棵子树，同父兄弟
  // 保持基座序；无嵌套时与基座序逐位相同——既有行序零回归）。深度/子树同点产出，
  // 渲染面（折叠剪枝）与回归 dump（data-bg-depth）共用这一份。
  const tree = bgRowTree(items);
  // ⑤ 野节点归置批（2026-11-05 腿③）：判定式单点（bgOrphanRowKeys）在**收敛+树序之后**
  // 跑一次，结论写到 row.wild——渲染面只读这个布尔，不在渲染分支里自算野性。行集与
  // 计数（total）**不因归置而变**：野行照旧计入（一行不丢），只是换一个归置面出镜。
  // 三代层级批：bucketSid 随参下传（桶根条款——属主=桶根的行永不野，root 锚恒在）；
  // row.parentState = 判定表单点结论（仅顶层行），渲染面据此带「父不在场」标 /
  // 抑制「来自 …」标（父可见时不撒谎）；row.absentParent = ②态的父键（chip tooltip 面）。
  const wildKeys = bgOrphanRowKeys(tree.order, bucketSid);
  const treeByKey = new Map();
  for (const row of tree.order) {
    const k = bgRowSessionKey(row);
    if (k && !treeByKey.has(k)) treeByKey.set(k, row);
  }
  for (const row of tree.order) {
    row.wild = wildKeys.has(bgRowSessionKey(row));
    row.parentState = (row && row.level === 'top')
      ? bgTopRowParentStateOf(row, treeByKey, bucketKey) : null;
    if (row.parentState === 'absent-parent') {
      row.absentParent = { parentSid: String(bgTreeParentKeyOf(row) || '') };
    }
  }
  // total = 行集长度，root 锚与结构父（face C 标签面行）除外——「签 == 实际
  // .bg-task-row 行数」口径不破；锚/结构父子树里的行照常计入（idle 父不计活跃数）。
  let total = 0;
  let activeCount = 0;
  for (const row of tree.order) {
    if (!row || row.level === 'root' || row.level === 'struct') continue;
    total += 1;
    if (bgPlanRowState(row) === 'active') activeCount += 1;
  }
  return { items: tree.order, total, activeCount, tree, orphanCount: wildKeys.size };
}

/**
 * 面板模型单点（Flow 会话可见性批 2026-11-01 · 收敛批 2026-11-02 改口径）：
 * badge 计数 / 头部计数 / 空态 / 行集四个消费面共读此一处——「签与列表永不打架」
 * 的口径随模型延续。`total` = 收敛后的行集长度（`bgRowPlan().items.length`）：
 * 同一 sessionId 无论从哪条通路进来只计一次，行数与计数由**同一份行集**派生。
 * 层级缩进批（2026-11-05 腿②）：`rows` 已是树序、`tree` 带深度/子树（折叠剪枝用）。
 *
 * @param {string} bucketSid 主会话桶 id（视图 sessionId）
 * @returns {{bgAgents: Object, entries: Array<[string, any]>, groups: Array<Object>, leftovers: Array<Array<any>>, orphanBy: Map<string, any>, rows: Array<Object>, total: number, activeCount: number, tree: {order: Array<any>, roots: Array<any>, parentOf: Map<any, any>, kids: Map<any, Array<any>>, depth: Map<any, number>}, orphanCount: number}}
 */
function bgPanelModel(bucketSid) {
  const bgAgents = { ...((bucketSid && state.sessionBgAgents[bucketSid]) || {}) };
  for (const child of listScopedTasks(bucketSid)) {
    const existing = Object.keys(bgAgents).find(key =>
      String(bgAgents[key]?.sessionId || key).replace(/^team-/, '') === child.taskId);
    // Durable status wins; the existing key preserves management/click routing.
    bgAgents[existing || child.taskId] = {
      ...(existing ? bgAgents[existing] : {}),
      ...bgScopeStoreInfo(child, child.parentSessionId),
      durableTask: true,
    };
  }
  const entries = visibleBgAgentEntries(bgAgents);
  const { groups, leftovers, orphanBy } = bgDelegateGroups(entries, bucketSid);
  // 面板三代层级批（2026-11-06）：bucketSid 随计划下传（root 锚合成 + 野判定桶根条款）。
  const plan = bgRowPlan({ groups, leftovers, orphanBy }, bucketSid);
  return {
    bgAgents, entries, groups, leftovers, orphanBy,
    rows: plan.items,
    total: plan.total,
    activeCount: plan.activeCount,
    tree: plan.tree,
    // 野节点归置批（2026-11-05 腿③）：归置分组的行数（判定式单点结论的计数读口——
    // 渲染面据此决定要不要出组头，回归 dump 与报告读数也读它）。
    orphanCount: plan.orphanCount,
  };
}

/**
 * 泛名判读（面板行说明批 2026-10-29 ②）：行主名是不是「无说明名」——builtin def
 * 名（subagent/nebflow/kernel…）、会话 id 形回落名（subagent-1a2b3c4d）、或等于
 * 行 id 本身。泛名行主名让位任务摘要（摘要缺席时回落 id，绝不渲染纯「subagent」
 * 空洞行——作者报障 2026-10-29 ②）。
 */
const BG_GENERIC_NAMES = new Set(['subagent', 'nebflow', 'nebula', 'kernel', 'general', 'worker', 'step']);
function bgGenericName(name, sid) {
  const n = String(name || '').trim();
  if (!n) return true;
  if (n === String(sid || '')) return true;
  if (BG_GENERIC_NAMES.has(n.toLowerCase())) return true;
  if (/^(subagent|workflow|subtask|ephemeral)-[0-9a-z-]+$/i.test(n)) return true;
  return false;
}

/** 孤儿来源角标（面板树批 2026-10-29 ①）：父不可见行的「来自 …」标注（中性 muted
 *  chip 族 = .bg-task-origin 同款；tooltip 承载完整父会话 id）。入参 = 行 opts
 *  （orphanFrom/orphanTitle——bgDelegateGroups.orphanBy 下传形）。
 *  来源链批（作者令 2026-11-01 19:23）：链级都是**名字**不是 type——父链可解析
 *  （bgSourceChain 既有镜像读数）⇒ chip = 上层名字链（「上层 · 父」，子行自己
 *  的名字已在行首主名位，角标只补「挂在谁下面」）；解析不到（父无名/旧数据）⇒
 *  既有类型词逐字回退（零臆造）。 */
function bgOrphanChipHtml(orphan, bucketSid) {
  const o = orphan && typeof orphan === 'object' ? orphan : {};
  const parentSid = String(o.orphanFrom || '');
  if (!parentSid) return '';
  const chainSegs = parentSid
    ? [...upperChainOf(parentSid, bucketSid), chainNameOf(parentSid)].filter(Boolean)
    : [];
  const chainText = chainLabel(chainSegs);
  let label;
  if (chainText) label = chainText;
  else if (/^(node-|dag-|dispatcher-)/.test(parentSid)) label = t('subagents.fromFlowNode');
  else if (/^(subagent-|subtask-|workflow-)/.test(parentSid)) label = t('subagents.fromSubagent');
  else {
    const title = bgFirstLine(o.orphanTitle);
    label = title || t('subagents.fromDelegate');
  }
  return '<span class="bg-task-origin bg-task-orphan" title="' +
    escapeHtml(t('subagents.orphanTip', { sid: parentSid }) +
      (chainText ? ' · ' + chainText : '')) + '">' + escapeHtml(label) + '</span>';
}

/**
 * 「父不在场」标（面板三代层级批 2026-11-06 · 判定表②）：有父而父行不在场的行的诚实
 * 标注——判定表①的真子行不带本标（父可见，「来自 …」即撒谎）；③态野行走既有来源角标。
 * 版式 = 中性 muted chip 族（.bg-task-origin 同款——零新 CSS 零新令牌），文案 = locale
 * 单点（zh「父不在场」/ en 同键），tooltip 承载父会话 id（「父在哪」的事实不丢）。
 * 机器锚：`data-bg-absent-parent="1"`（chip 自身）+ 行级 `data-bg-parent-absent="1"`
 * （bgTaskRowHtml 写点）。
 * @param {string} parentSid 父会话 id @returns {string}
 */
function bgAbsentParentChipHtml(parentSid) {
  const sid = String(parentSid || '').replace(/^team-/, '');
  return '<span class="bg-task-origin bg-task-absent-parent" data-bg-absent-parent="1" title="' +
    escapeHtml(t('subagents.parentAbsentTip', { sid })) + '">' +
    escapeHtml(t('subagents.parentAbsent')) + '</span>';
}

/**
 * 行深度锚（层级缩进批 2026-11-05 腿②）：机器可读 `data-bg-depth` + 视觉缩进变量
 * `--bg-row-depth`（缩进档在 chat.css：子行 padding-left = 既有间距令牌按深度累加，
 * 每深一层再加一格；零新增色值、零新增档位）。depth=0（整体行/顶层行）不带属性，
 * 视觉与改动前逐位相同。
 * @param {number} [depth] @returns {string}
 */
function bgDepthAttrs(depth) {
  const d = Math.max(0, Math.floor(Number(depth) || 0));
  return ' data-bg-depth="' + d + '"' + (d > 0 ? ' style="--bg-row-depth:' + d + '"' : '');
}

// listitem does not support aria-level. Expose the same depth as readable text instead.
function bgDepthText(depth) {
  const level = Math.max(0, Math.floor(Number(depth) || 0)) + 1;
  return '<span class="bg-row-sr bg-hierarchy-sr">' + escapeHtml(t('subagents.hierarchyLevel', { level })) + '</span>';
}

/** 是否父行的末位子行（转折角 `└` 与延续列 `│` 的判据）——直接读 bgRowTree 的兄弟表
 *  （同父兄弟序 = 基座序；渲染面不另算顺序）。 @param {any} row @param {any} tree
 *  @returns {boolean} */
function bgIsLastSibling(row, tree) {
  if (!row || !tree || !tree.kids) return true;
  const parent = tree.parentOf.get(row) || null;
  if (!parent) return true;
  const kids = tree.kids.get(parent) || [];
  return kids.length === 0 || kids[kids.length - 1] === row;
}

/**
 * 层级连接符（视觉导轨）单点——行首的「转折号」（2026-11-07 · 作者令逐字「这里的
 * 缩减显示还不够明显，最好有转折号来在前面」）。
 *
 * 每行用本级转折角（`└` 末位子 / `├` 非末位）加 CSS 深度缩进。
 * 纪律（逐条，本批成立的全部理由）：
 *   · **唯一产点**：树形/深度/兄弟序全部读 bgRowTree 的那一份（`tree.parentOf` /
 *     `tree.kids`）——渲染面不重算第二种深度（第二套口径 = 层级与折叠打架的温床）；
 *   · **纯装饰**：`aria-hidden="true"`；data-bg-depth 供机器读取视觉深度。
 *   · **零新增色值**：字形走 currentColor + 既有 muted 色阶——亮/暗两档自动跟随主题，
 *     不加任何 hex；
 *   · **不改行集**：本函数只产前缀字符串，行/计数/折叠剪枝全在既有单点上；
 *   · **depth 0 零字节**（顶层行/整体行/root 锚/野组头形态逐字不变）。
 * @param {any} row 行计划项 @param {any} tree bgRowTree 产出
 * @returns {string} HTML（'' = 无导轨）
 */
function bgTreeRailHtml(row, tree) {
  if (!row || !tree || !tree.depth) return '';
  const depth = Number(tree.depth.get(row) || 0);
  if (!(depth > 0)) return '';
  // 缩进由 CSS 单独负责；只画本级转折，避免祖先字符叠加缩进造成列错位。
  const elbow = (bgIsLastSibling(row, tree) ? '└' : '├') + '─';
  return '<span class="bg-tree-rail" aria-hidden="true">' + escapeHtml(elbow) + '</span>';
}

/**
 * 折叠态锚（层级缩进批）：只有**有子行的行**带 `data-bg-collapsed`（"true"/"false"）——
 * 机器读点（回归 dump / 读屏 / 刷新后状态核对）；无子行的行不带此属性（零噪声）。
 * @param {number} [childCount] @param {boolean} [collapsed] @returns {string}
 */
function bgCollapseAttrs(childCount, collapsed) {
  if (!(Number(childCount) > 0)) return '';
  return ' data-bg-collapsed="' + (collapsed ? 'true' : 'false') + '"';
}

/**
 * 行首 caret 折叠锚（面板层级批 2026-10-31 ① 形态原样沿用：muted 小图标 + 停冒泡
 * 点击热区 + aria/title 随折叠态翻转）。层级缩进批：从「只有组头」放宽到**任意有
 * 子行的行**（作者令「每一层都应该是上一层的缩进」⇒ 每一层都该能收起自己那棵子树）。
 * @param {string} key 行定位键（data-bg-caret 值 = 折叠偏好的持久化键，同一把缝）
 * @param {boolean} collapsed @returns {string}
 */
function bgCaretHtml(key, collapsed) {
  const word = collapsed ? t('subagents.expandGroup') : t('subagents.collapseGroup');
  return '<button type="button" class="bg-task-caret' + (collapsed ? ' is-collapsed' : '') + '" data-bg-caret="' +
    escapeHtml(String(key || '')) + '" aria-expanded="' + String(!collapsed) + '" aria-label="' + escapeHtml(word) + '" title="' +
    escapeHtml(word) + '">' + statusIcon(collapsed ? 'chevron-right' : 'chevron-down', 11) + '</button>';
}

/**
 * 野节点归置面 · 分组头（野节点归置批 2026-11-05 腿③）。
 *
 * 独立归置面 = **组头标签 + 组内野行**：组头带机器锚 `data-bg-orphan-group` 与
 * `data-bg-depth="0"`，组内每行带 `data-bg-orphan="1"`。铁律两条：
 *   · **不许静默丢弃**——野行照旧进行集、照旧计入面板签（归置只换出镜面，不删行）；
 *   · **不许混在正常树里假装有父**——野行不带正常树归属锚 `data-bg-agentflow`，
 *     也不带 caret（它们本来就没有子树可收）。
 * 组头是**标签不是行**：不带 .bg-task-row 类、不进 data-bg-row-total、不入头部计数
 * ——「签 == 实际行数」的口径不因归置而破（组头不是行，行集里没有它）。
 *
 * **回收 / 关闭规则（作者令「拿不准 ⇒ 只展示」——本批结论 = 只展示，零处置）**：
 * 组内与组头一律**无关闭动作、无回收按钮、无自动归档**。依据（逐条，引擎侧事实）：
 *   ① 控制面不在前端——delegate 记录的归档/删除判据归引擎 DelegateRegistry.archive，
 *      前端只有读口（listDelegateRows / listDelegateChildren 是镜像读，非控制面）；
 *   ② 引擎侧「零删除纪律」（DelegateRegistry.scala:121-127）——记录是审计事实，前端
 *      不得替引擎判「可以回收」；
 *   ③ 野行的可回收判据里**有一条前端判定不了**：该 taskId 是否还存在 live 会话帧
 *      （需 WS/鉴权快照；术语审计 §④ 实测 /api/delegates、/api/chains 均 403）；
 *      「父已离场 + 终态」只满足三条判据里的两条，拿不准 ⇒ 不提供动作。
 * 因此野节点只有一种处置面：**看见它们**（归置 + 既有「来自 …」来源角标）。等引擎侧
 * 给出明确判据（例如 delegateArchive 的显式入参 + 回执帧）再谈关闭按钮，那是独立一批。
 * @param {number} count 野行数（只用于 aria 语义，不上屏）
 * @returns {string}
 */
function bgOrphanGroupHeadHtml(count) {
  const n = Number(count) || 0;
  return '<div class="bg-orphan-group" data-bg-orphan-group="1" data-bg-depth="0" role="presentation" aria-label="' +
    escapeHtml(t('subagents.orphanGroup') + ' · ' + n) + '">' +
    '<span class="bg-orphan-group-name">' + escapeHtml(t('subagents.orphanGroup')) + '</span>' +
  '</div>';
}

/**
 * root 锚（面板三代层级批 2026-11-06 · 作者令「root → dispatcher → node 三级真缩进」，
 * design 残差①）：桶根会话（顶层会话）的**标签面行**——树的 depth 0 基座，属主=桶根
 * 的行（dispatcher 行等）挂它下面 depth 1。纪律沿用野分组头先例：
 *   · **标签不是行**——不带 .bg-task-row 类、不进 data-bg-row-total、不入头部计数
 *     （「签 == 实际 .bg-task-row 行数」口径不破；行集里没有它）；
 *   · **锚有子树 ⇒ caret 合法**（bgCaretHtml 已放宽到任意有子行行；野组头「无 caret」
 *     纪律不适用于锚——锚是树基座，收起 = 根下整棵子树跟随隐藏）；
 *   · 折叠偏好走同一 localStorage 缝（键 = 桶根会话 id，bgCollapseGet/Set 单点），
 *     刷新后保持；默认展开（偏好 true 才收——基座不该替用户做主）。
 * 机器锚：`data-bg-root-anchor="1"` + `data-bg-depth="0"` + `data-bg-collapsed`。
 * @param {string} key 行定位键（= 桶根会话 id；data-bg-caret 值 = 折叠偏好持久化键）
 * @param {number} childCount 根下子行数（0 = 不带 caret 与折叠锚）
 * @param {boolean} collapsed @returns {string}
 */
function bgRootAnchorHtml(key, childCount, collapsed) {
  const word = t('subagents.rootAnchor');
  return '<div class="bg-root-anchor" data-bg-root-anchor="1" data-bg-depth="0" role="presentation"' +
    bgCollapseAttrs(childCount, collapsed) +
    ' aria-label="' + escapeHtml(word) + '">' +
    bgDepthText(0) +
    (Number(childCount) > 0 ? bgCaretHtml(key, collapsed) : '') +
    '<span class="bg-root-anchor-name" title="' + escapeHtml(String(key || '')) + '">' +
      escapeHtml(word) + '</span>' +
  '</div>';
}

// ── Registry-backed structural parent（全局后台面板层级 face C）────────────────
// 契约：活跃后代在场而父 delegate 无 live 行（父已终态/空闲/离场）时，父必须作为
// **结构节点**显示——不猜标题、不隐藏文案：父的身份只来自注册行镜像
// （DelegateRegistry.Record——spawn 落盘、跨重启存活），镜像缺席 ⇒ 不合成（后代
// 走既有野组面，标未知而非猜）。结构节点是**标签面**：不入行集计数、不占
// data-bg-row-total、不带 .bg-task-row（「父可 idle 且不计活跃数」的机械形态——
// 徽标/头部计数只数真实后代行）。已完成后代（store 子条目）随结构保留展示；
// 有限历史窗 = 镜像现读（children 按父分区，一批的子集，非全局历史）。

/** Registry row status → status word key (existing keys only; '' = no word). */
function bgStructStatusWordKey(registryRow) {
  const task = String((registryRow && registryRow.taskStatus) || '');
  const st = task || String((registryRow && registryRow.status) || '');
  if (st === 'completed') return 'flowmap.done';
  if (st === 'failed') return 'flowmap.fail';
  if (st === 'cancelled') return 'flowmap.st.cancelled';
  if (st === 'interrupted') return 'task.nodeInterrupted';
  if (st === 'ready-to-merge') return 'delegate.readyToMerge';
  if (st === 'running') return 'task.inProgressShort';
  return '';
}

/** Structural parent label face（caret + kind badge + name + muted status word）。
 *  Machine anchors: data-bg-struct-parent="1" + data-bg-depth；sid 全文在 title。
 *  @param {any} row 结构父行计划项（level='struct'，row.structRow = 注册行镜像）
 *  @param {number} childCount 子行数（0 = 不带 caret 与折叠锚）
 *  @param {boolean} collapsed @returns {string} */
function bgStructParentHtml(row, childCount, collapsed) {
  const reg = (row && row.structRow) || {};
  const sid = bgRowSessionKey(row);
  const kind = bgDelegateExecMode(reg);
  const kindPart = kind
    ? '<span class="bg-task-kind">' + escapeHtml(bgDelegateBadgeWord(kind)) + '</span>'
    : '';
  const shortName = String(reg.name || '').trim();
  const titleLine = bgFirstLine(reg.title || '');
  const label = shortName || titleLine || sid;
  const tip = [shortName, titleLine, sid].filter(Boolean).join(' · ');
  const wordKey = bgStructStatusWordKey(reg);
  const word = wordKey ? t(wordKey) : '';
  return '<div class="bg-struct-parent" data-bg-struct-parent="1" data-bg-depth="0" role="presentation"' +
    bgCollapseAttrs(childCount, collapsed) +
    ' aria-label="' + escapeHtml(label) + '">' +
    (Number(childCount) > 0 ? bgCaretHtml(sid, collapsed) : '') +
    '<span class="bg-struct-parent-name" title="' + escapeHtml(tip) + '">' +
      escapeHtml(label) + '</span>' +
    kindPart +
    (word ? '<span class="bg-struct-parent-state">' + escapeHtml(word) + '</span>' : '') +
  '</div>';
}

/** 野节点行（组内一行）：复用面板行构造器（状态点/主名/状态词/来源角标全同款），
 *  只多两个归置锚——`data-bg-orphan="1"`（组内成员）与 depth=0（不进树，无缩进层）。
 *  既有「来自 …」来源角标（bgOrphanChipHtml）原样带上：**那是事实，不删**。
 *  @param {any} row 行计划项（row.orphan = {parentSid, parentTitle} 可能缺席）
 *  @param {string} bucketSid 主会话桶 id（角标名字链解析的父判据输入）
 *  @returns {string} */
function bgOrphanRowHtml(row, bucketSid) {
  const o = (row && row.orphan) || null;
  const opts = { wild: true, depth: 0, childCount: 0, collapsed: false };
  if (o) {
    opts.orphanFrom = o.parentSid;
    opts.orphanTitle = o.parentTitle;
    opts.bucketSid = bucketSid;
  }
  return bgTaskRowHtml(row.id, row.info, opts);
}

/**
 * 面板行 HTML（原 renderBgAgentDropdown map 体原样抽出——行渲染语义零改动；新增
 * opts.childLevel/headLevel 两级锚与 opts.registryRow（注册行镜像来源对象）两个纯呈现参数）。
 * 显示名契约批（2026-10-22）：delegate 组头行（opts.headLevel）新增
 * opts.rowTitle（DelegateRegistry.Record.title 的 provider 镜像——spawn 落盘、
 * 跨重启存活），主名 = 批次标题/任务描述首行（全生命周期稳定，不再收缩成
 * sessionId）；session id 降为 title tooltip 次要信息。
 * 面板行说明批（2026-10-29 ②）：非组头行主名 = 任务摘要首行（泛名让位——见
 * bgGenericName）；实名行附任务摘要次行（.bg-task-subline，全文 tooltip）。
 * 面板树批（2026-10-29 ①）：opts.orphanFrom/orphanTitle 孤儿来源角标（父不可见
 * 行的「来自 …」标注）。
 * 面板命名批（2026-10-31 ② · 作者锚点「行首必须显短名」）：opts.shortName
 * （DelegateRegistry.Record.name / 快照 delegateName，≤12 字）在场 ⇒ 行首主名 =
 * 短名，批次标题降为次行（全文 tooltip）——扫一眼分辨哪批是哪批；缺席 ⇒ 旧链
 * 逐字（标题截断显示，老数据零迁移）。
 * 面板层级批（2026-10-31 ①）：opts.childCount/opts.collapsed——组头行首 caret
 * 折叠锚（有子行的组头才渲染；caret 点击 = 收起/展开子行树，行体点击照旧开详情）。
 * Flow 会话可见性批（2026-11-01）：opts.chainContinued/opts.chainCount——delegate
 * 已终态而链仍在跑的**延续态**呈现覆写：状态词位改琥珀「链运行中」（workflow
 * 图标 + status-warn token，tooltip 带链会话数），状态点改 active 绿脉冲（活链
 * ≠ 死行），行不加 .done 灰化（链活着）；链全终态后覆写入参自然缺席 → 行回
 * 普通终态样式（既有 done 灰化/点形）。
 * 术语分层批（2026-11-05）：opts.agentflowKey——成员行的归属锚（所属整体行定位键，
 * 与 data-bg-key / bgRowLocatorKey 同口径）；整体行由 opts.headLevel 自动带实体锚
 * （agentflow 错标修复批 2026-11-06：实体锚改为**来源条件**——只有 opts.registryRow
 * 判定为 dispatcher 派生的编排才带，见 bgAgentflowWord）。
 * 层级缩进批（2026-11-05 腿②）：opts.depth——**真实父子链接**算出的深度（整体行 0、
 * 成员行 = 父行 +1，任意层数）；输出 data-bg-depth + --bg-row-depth 视觉缩进。
 * opts.childCount/opts.collapsed 随之从「组头专属」放宽到**任意有子行的行**（caret 形态
 * 与热区照旧），收起即隐藏整棵子树（剪枝在渲染循环里做，行集合/计数不受影响）。
 * 野节点归置批（2026-11-05 腿③）：opts.wild——野节点行（父不可解析）的归置锚
 * `data-bg-orphan="1"`（判定式单点见 bgOrphanRowKeys；行构造器只负责把结论上锚）。
 * @param {string} id @param {any} info @param {{childLevel?: boolean, headLevel?: boolean, registryRow?: any, agentflowKey?: string, depth?: number, rowTitle?: string, shortName?: string, childCount?: number, collapsed?: boolean, wild?: boolean, orphanFrom?: string, orphanTitle?: string, absentParent?: {parentSid: string}, bucketSid?: string, chainContinued?: boolean, chainCount?: number}} [opts]
 * @returns {string}
 */
/** Mail 续传地址 chip（AgentControl name/address 批 2026-11-05 · 作者令「agentcontrol
 *  工具应该要能返回在跑的 delegate 的 name 和地址，方便投递任务」的面板面）。
 *
 *  数据源 = **后端** MailAddress 单点推导出的行字段 `info.mailAddress`（快照
 *  activeAgentEntryJson 与 live agentStart 帧同键）——面板**零推导**：地址是「原样
 *  粘进 Mail」的令牌，前端若自己拼一遍就有了第二个形态来源（错一位就投错人）。
 *  空 = 本 kind 无 Mail 面（Root/Team/Ephemeral/SubTask/旧 dag-*）⇒ 零渲染。
 *
 *  版式与可复制性分家：视觉上 ellipsis 截断（行宽有限），**复制目标恒为全文**
 *  （data-bg-copy 承载完整地址，点击写剪贴板）——截断只是排版，不是数据。 */
function bgAddrPart(info) {
  const addr = String((info && info.mailAddress) || '').trim();
  if (!addr) return '';
  return '<span class="bg-task-addr" role="button" tabindex="-1" data-bg-copy="'
    + escapeHtml(addr) + '" title="' + escapeHtml(addr + ' — ' + t('subagents.copyAddress')) + '">'
    + escapeHtml(addr) + '</span>';
}

export function bgTaskRowHtml(id, info, opts = {}) {
  const rawState = bgRowState(info);
  // 延续态覆写只对终态基座行生效（进行中 delegate 行的链子行走普通两级呈现）。
  const chainContinued = !!(opts.chainContinued && (rawState === 'done' || rawState === 'error'));
  const rowState = chainContinued ? 'active' : rawState; // 展示态：点形/词档按活链走
  const terminal = !chainContinued && (rawState === 'done' || rawState === 'error');
  // 冻结态误报批（2026-11-05）：冻结族的**行首状态词按呈现分类给**——自动恢复族
  // （时间表/退避/探测）不写「已冻结」，只在需要人时写；状态点同款分色（自动恢复族
  // 用既有的 waiting 点，需人介入保持 frozen 点）。
  const freezeKind = rowState === 'frozen' ? freezeKindOf(info) : '';
  const execution = taskExecutionPresentation(info);
  const statusLabel = execution ? execution.label : chainContinued
    ? t('subagents.chainRunning')
    : rowState === 'frozen'
      ? t(freezeBadgeKey(freezeKind))
      : t('subagents.status.' + (rowState === 'active' ? 'running' : rowState));
  const chainTip = chainContinued
    ? t('subagents.chainRunningTip', { n: Number(opts.chainCount) || 0 })
    : '';
  // 状态色调（三行结构批 2026-11-05）：**词表已上收**到 bgRowStatus.js 的
  // BG_STATUS_VISUAL 单表（状态 → 图标/色调/文案键），本行只给「状态键」与可选的
  // 色调覆写——冻结态误报批的「自动恢复族走 waiting 色」覆写在此保留为唯一一处。
  const statusState = chainContinued ? 'chain' : rowState;
  // 修复批 2026-11-06：可恢复冻结（等待恢复中 / 自动恢复中 / 时段等待）一律走
  // waiting 色，只有真失败（manual）才是 frozen 色。
  const statusTone = (freezeKind && freezeKind !== FREEZE_KIND.manual) ? 'frozen-auto' : '';
  // 术语分层批（2026-11-05 作者令「整体行 vs 成员行」）：身份词分两个显示位，各由一个
  // 函数定词，没有一个构造器自带词表——
  //   · 整体行（opts.headLevel：dispatcher 派生的一次编排）⇒ 形态词 .bg-task-kind
  //     （bgDelegateBadgeWord：kernel / dispatcher，作者术语逐字保留）＋ 整体词
  //     .bg-task-entity（bgAgentflowWord）；
  //   · 成员行（编排里的节点）⇒ bgMemberKindWord（成员位绝不落整体词）。
  // agentflow 错标修复批（2026-11-06）：两个词**同读一个来源对象** opts.registryRow
  // （注册行镜像；成员行恒 null）——组头层级不再自己决定身份词，kernel / 旧盘行因此
  // 拿不到 agentflow 词。
  // 修订批九点②补漏：快照 kind（AgentKind.SubTask）同走显示词归一（数据面不动）。
  const registryRow = opts.headLevel ? (opts.registryRow || null) : null;
  const kind = opts.headLevel
    ? bgDelegateBadgeWord(bgDelegateExecMode(registryRow))
    : bgMemberKindWord({ info, sessionId: info.sessionId || id, id });
  // 徽标归位批（2026-10-31 23:02 作者裁定）：**顶层行**（组头 + 未被认领的 leftover/
  //  孤儿行，即一切非 childLevel 行）不渲染 Delegate/Subagent 字样——层级锚不是历史
  //  类型残留。组头徽标只认 row.mode（bgDelegateBadgeWord，mode 缺席即无徽标）；此处
  //  兜底摘掉 leftover 行的 kind 残留词（「来自 …」孤儿角标/任务归属徽标不受影响）。
  //  术语统一批（2026-11-06）：判据读单源表（isMemberOnlyBadge），不再写死词面字面量
  //  ——词面换了（Subagent→subagent、Delegate→delegate）摘词行为逐位不变。
  //  子智能体标签批（2026-11-07 · 作者报障「node 的子智能体没显示」）：摘词门补一个
  //  例外 opts.member——**父行在场（真子行）的顶层行也是成员**，它挂在可见父行下、
  //  有真实 depth，「这一行是子智能体/批次」正是它该说的话（node 派生的 subagent 行
  //  此前因此丢了徽标，面板上只剩一个无名缩进行）。孤儿行（父不可见）不带本旗，
  //  摘词门逐字不变（「来自 …」角标形态零回归）。
  const kindWord = (!opts.childLevel && !opts.member && isMemberOnlyBadge(kind)) ? '' : kind;
  const kindPart = kindWord ? '<span class="bg-task-kind">' + escapeHtml(kindWord) + '</span>' : '';
  // 整体行实体词（术语分层批 + agentflow 错标修复批）：**只有 dispatcher 派生的编排**
  // 才带本词（bgAgentflowWord 的两轴来源判定）；kernel 行 / 未登记链的行 / mode 缺席行
  // = 零徽标。成员行永不出现（反向门：整体行也不出现节点词，两词位互斥）。
  const entityWord = opts.headLevel ? bgAgentflowWord(registryRow) : '';
  const entityPart = entityWord
    ? '<span class="bg-task-entity">' + escapeHtml(entityWord) + '</span>'
    : '';
  // Project attribution (2026-09-06 author ruling): Flow rows carry the
  // owning project's name next to the kind chip — muted plain text,
  // visually subordinate to the chip, never competing with the node name.
  // Absent (Root/direct delegates) → no badge at all.
  const projectPart = info.project
    ? '<span class="bg-task-project" title="' + escapeHtml(info.project) + '">' + escapeHtml(info.project) + '</span>'
    : '';
  // Task attribution badge (2026-09-27 taskbadge batch): `#<id> · <title>` chip
  // per row — the per-row task marker under the multi-task dispatcher
  // architecture (rows from different tasks sit side by side in one bucket;
  // the badge is what makes them distinguishable). No attribution (kernel /
  // system / legacy / slot cleared) → no badge at all — an explicit empty
  // badge would be noise on every non-task row. Long titles truncate at the
  // RENDER layer only (CSS ellipsis + full text in the title tooltip).
  const taskBadgeText = info.taskId
    ? (info.taskTitle ? '#' + info.taskId + ' · ' + info.taskTitle : '#' + info.taskId)
    : '';
  const taskPart = taskBadgeText
    ? '<span class="bg-task-badge" title="' + escapeHtml(t('subagents.taskBadge', { text: taskBadgeText })) + '">'
      + escapeHtml(taskBadgeText) + '</span>'
    : '';
  // 孤儿来源角标（面板树批 2026-10-29 ①）：父不可见行提升顶层后仍标注来源。
  // 来源链批（2026-11-01 19:23）：桶键随 opts 下传——角标名字链解析的父判据输入。
  const orphanPart = (opts && opts.orphanFrom) ? bgOrphanChipHtml(opts, opts.bucketSid) : '';
  // 「父不在场」标（三代层级批·判定表②）：有父而父行不在场的诚实标注——与「来自 …」
  // 互斥（②态走本标，父会话 id 在 tooltip；③态走既有来源角标）。
  const absentPart = (opts && opts.absentParent && opts.absentParent.parentSid)
    ? bgAbsentParentChipHtml(opts.absentParent.parentSid) : '';
  // ── 显示名契约（2026-10-22 · 作者「先长名后收缩成 sessionId」批）────────────
  // 症状根因：恢复脸（activeAgents 快照）对 delegate 会话 meta/displayName 恒缺席
  // ⇒ agentName 回落 sessionId；live 脸 agentId == sessionId，同 key 合并「快照为准」
  // 每次开面板都把 live 长名行覆盖成短 id；重启后只剩快照脸 ⇒ 全行 id。
  // 主名数据链（全生命周期稳定 = 批次标题/任务描述首行）：
  //   rowTitle（DelegateRegistry.Record.title 的 provider 镜像——spawn 落盘、
  //   跨重启存活，含存量记录回填）→ 快照/实时 task 首行（live 脸的 taskDescription =
  //   批次标题；恢复脸后端已把标题回填进 agentName）→ name（≠ sid 时——后端恢复脸
  //   已把标题写入本档）→ sid → 桶 id。session id 一律降级为 title tooltip 次要
  //   信息（完整名兜底）。非组头行（子行/leftover 非 delegate 行）逐字走旧形态。
  // 面板行说明批（2026-10-29 ②）：非组头行主名 = 任务摘要首行优先（泛名让位）；
  // 实名行任务摘要降为次行（subLine）。纯「subagent」空洞行自此绝迹。
  const sid = String((info && info.sessionId) || id || '').replace(/^team-/, '');
  let displayName;
  let nameTooltip;
  let taskSummary = '';
  if (opts.headLevel) {
    const fromName = (info.name && String(info.name) !== sid) ? String(info.name) : '';
    const fullTitle = String(opts.rowTitle || '').trim() || bgFirstLine(info.task) || fromName;
    const shortName = String(opts.shortName || (info && info.delegateName) || '').trim();
    if (shortName) {
      // 面板命名批（2026-10-31 ②）：行首显短名——批次标题降为次行（全文 tooltip
      // 承载标题 + sid）；「扫一眼面板上的名字就知道哪批是哪批」。
      displayName = shortName;
      const titleLine = bgFirstLine(fullTitle);
      nameTooltip = shortName +
        (titleLine && titleLine !== shortName ? ' · ' + String(fullTitle || '') : '') +
        (sid && sid !== shortName ? ' · ' + sid : '');
      taskSummary = (titleLine && titleLine !== shortName) ? titleLine : '';
    } else {
      displayName = bgFirstLine(fullTitle) || info.name || sid || id;
      nameTooltip = displayName + (sid && sid !== displayName ? ' · ' + sid : '');
    }
  } else {
    const taskLine = bgFirstLine(info.task);
    const generic = bgGenericName(info.name, sid || id);
    if (taskLine && generic) {
      // 泛名行：任务摘要上屏为主名（原「subagent」空洞位的替代）；原名/oid 降 tooltip。
      displayName = taskLine;
      const genericName = (info.name && String(info.name) !== taskLine) ? String(info.name) : '';
      nameTooltip = displayName + (genericName ? ' · ' + genericName : '') + (sid ? ' · ' + sid : '');
    } else if (taskLine) {
      // 实名行：主名保留，任务摘要降为次行（全文 tooltip）。
      displayName = info.name || id;
      nameTooltip = displayName + ' · ' + info.task;
      taskSummary = taskLine;
    } else {
      // 无任务摘要：泛名回落行 id（可辨识），实名照旧——绝不渲染纯「subagent」。
      displayName = info.name || id;
      if (generic && String(info.name || '') === String(displayName)) displayName = id;
      nameTooltip = info.task ? displayName + ' · ' + info.task : displayName;
    }
  }
  const namePart = '<span class="bg-task-name" title="' + escapeHtml(nameTooltip) + '">' + escapeHtml(displayName) + '</span>';
  // #752 空闲细分注记（先于 uptime 计算——收敛批 2026-11-02 长空闲口径：行尾的
  // 运行时长与「空闲 N 分钟」不再并排出镜。两条时长并列时量纲相近、起点不同
  // （uptime=createdAt 总时长 / 注记=lastActivityMs 空闲时长），RCA §2 实证会
  // 被读成自相矛盾（7h04m vs 415分钟）⇒ 长空闲态（stale 注记在场）时 uptime
  // 槽让位：注记自带时长，行尾不再挂第二个时钟。短空闲/waitingBg 注记不触发
  // （无并列时长，形态不变）。
  const idleDetail = (rowState === 'idle' || rowState === 'waiting') ? bgIdleDetail(info) : null;
  const longIdle = !!(idleDetail && idleDetail.stale);
  // Uptime (spec §2.2): relative duration while non-terminal; hidden once done.
  const uptimePart = (!terminal && info.startedAt && !longIdle)
    ? '<span class="bg-task-uptime" data-started-at="' + info.startedAt + '">' + escapeHtml(fmtUptime(Date.now() - info.startedAt)) + '</span>'
    : '';
  // Retries chip: only when >0 (0 is noise — user ruling ②).
  const retries = info.retryCount || 0;
  const retriesPart = retries > 0
    ? '<span class="bg-task-retries" title="' + escapeHtml(t('manage.retry')) + ' ×' + retries + '">×' + retries + '</span>'
    : '';
  // Stuck: restrained label with idle seconds (manage-panel semantics) —
  // ui-status-redesign（S2 / 盘点 #12）：词前 timer-off 红图标 + 中性字。
  // NO inline cancel/restart buttons — operations live in the agent popup (A13).
  // Hard-recovery P7 (2026-09-07): the label mirrors the REAL action the
  // backend performed (halt / hard-abort / restart / failed) — "auto-
  // restarting" only ever shows when an actual restart was triggered.
  const stuckPart = (rowState === 'stuck' && info.stuck)
    ? (() => {
      const stuckLabel = info.stuck.action === 'restart'
        ? t('manage.stuckAutoRestart')
        : info.stuck.action === 'hard-abort'
          ? t('manage.stuckHardAbort')
          : info.stuck.action === 'failed'
            ? t('manage.stuckFailed')
            : t('manage.stuck', { secs: info.stuck.idleSecs ?? '' });
      const stuckDetail = String(info.frozenDetail || info.stuck.reason || '');
      return '<span class="bg-task-stuck" role="alert"' + (stuckDetail ? ' title="' + escapeHtml(stuckDetail) + '"' : '') + '>'
        + statusIcon('timer-off', 11, 'st-err')
        + escapeHtml(stuckLabel + (stuckDetail ? ' · ' + stuckDetail : ''))
        + '</span>';
    })()
    : '';
  // Waiting（held-receipt 投递可靠性批 2026-10-21 fix 4）：显式停靠标签——
  // 「等合并轮」的 delegate 行显示等待词，不再貌似 Idle 完成。
  // 台账 #896 交通引导员制（2026-10-26 · 前身 = panel-merge-status 批 2026-10-25）：
  // 停靠行（waiting）：mode=standby = 申报待命（徽标词 subagents.status.standby，
  // tooltip = 申报的「在等什么」）。合并队列细节段已随合并队列机制移除。
  let waitingText = '';
  let waitingTip = '';
  const waitingMode = (info && info.waiting && info.waiting.mode) || '';
  // 错误冻结批（2026-11-01 · 作者令「冻结态方便被唤醒 接着做」）：冻结行的
  // **原因徽标 + 唤醒入口**。与 waitingPart（候合并/待命，自愿停靠）显式分家：
  //   · 徽标词 = subagents.status.frozen（已冻结），tooltip = 冻结原因（中文短名 /
  //     详情原文）+「这不是失败判词」的说明 + 长期冻结龄（dormantHours）；
  //   · 「唤醒」按钮 = 面板显式唤醒动作（WS `delegateWake`）——后端 live 腿注入原
  //     actor（同 session 续跑）/ 不 live 腿同 id 复活；绝不新建会话。
  // 数据源 = info.frozen / frozenReason / frozenReasonLabel / frozenDetail /
  // dormantHours（delegateFrozen / delegateFrozenDormant 帧与快照 frozenSince）。
  //
  // 冻结态误报批（2026-11-05）：上面这一整段只对**需人介入**的冻结成立。分类
  // 判定走 freezeState.js（后端 FreezeDisplay 的呈现面）：
  //   · 自动恢复族（时间表出窗 / LLM/网络退避 / 供应商探测 / 断点续跑）⇒ 徽标 =
  //     「自动恢复中 · 原因」（无「已冻结」措辞）、**不给按钮**（引擎正在自己做的事
  //     不该教用户去做）；tooltip 说明「到期自动续跑，无需操作」；
  //   · 需人介入族（循环检测 L2 / delegate 错误冻结 / 已升级到人）⇒ 既有「已冻结 ·
  //     原因」徽标 + 「唤醒」按钮 + 三个真实出口的 tooltip。
  const frozenPart = (rowState === 'frozen')
    ? (() => {
      // 冻结通知策略批（2026-11-05 作者令两连）：delegate 错误冻结族**一律**走本支
      // ——它是「provider 故障 = 基础设施故障，不向上汇报，面板待你处理 + 引擎自愈」
      // 的呈现面。判据 = freezeState.delegateFreezeWire（线值归一单点：实时帧送裸
      // 原因码、快照送 `delegate-frozen:*`，两种形态都认）。
      //   · 徽标 = 「已冻结 · 原因（待你处理）」，**带「唤醒」按钮**（唤醒腿 = 幂等
      //     UserInput 注入 / 同 id 复活，不花 supervisor 重启预算）；
      //   · tooltip = 这一族到底在等什么（供应商/额度）+ 原因 + 错误摘要 + 已自动续跑
      //     次数（耗尽则明说需要你：充值/换模型/取消）+ 长期冻结龄。
      const dwire = delegateFreezeWire(info);
      if (dwire) {
        const infra = delegateFreezeIsInfra(info);
        const whyKey = freezeReasonLabelKey(dwire);
        const why = info.frozenReasonLabel || (whyKey ? t(whyKey) : '');
        const detail = String(info.frozenDetail || '');
        const hours = Number(info.dormantHours) || 0;
        const tries = Number(info.autoResumes) || 0;
        const tip = [];
        // 修复批 2026-11-06：可恢复 ↔ 真失败在**词**上就不许同形。
        //   · infra（可恢复：llm-failure / 额度）⇒「等待恢复中」+ 自愈说明，无「待你处理」；
        //   · tool-failure / engine-fault（真失败）⇒「已冻结 … 待你处理」+ 唤醒按钮。
        tip.push(t(infra ? 'subagents.freezeRecoveringTip' : 'subagents.frozenTip'));
        if (why) tip.push(t('subagents.frozenReason', { reason: why }));
        if (detail) tip.push(detail);
        if (tries > 0) tip.push(t('subagents.frozenAutoResumes', { n: String(tries) }));
        if (info.autoResumesExhausted) tip.push(t('subagents.frozenAutoResumesSpent'));
        if (hours > 0) tip.push(t('subagents.frozenDormantTip', { hours: String(hours) }));
        const diagnostic = detail ? ' · ' + detail : '';
        const label = infra
          ? (why ? t('subagents.freezeRecoveringReason', { reason: why }) : t('subagents.freezeRecovering'))
          : (why
            ? t('subagents.frozenAwaitingYou', { reason: why })
            : t('subagents.frozenAwaitingYouNoReason'));
        // Diagnostic text is appended to the existing status label; no style or layout changes.
        const labelWithDetail = label + diagnostic;
        return '<span class="bg-task-frozen" role="status" title="' + escapeHtml(tip.join('\n')) + '">'
          + statusIcon('snowflake', 11, 'st-warn')
          + escapeHtml(labelWithDetail)
          + '</span>'
          + (wakeableOf(info) ? '<button class="bg-task-wake" type="button" data-bg-wake="'
          + escapeHtml(String((info && info.sessionId) || '').replace(/^team-/, ''))
          + '" title="' + escapeHtml(t('subagents.wakeTip')) + '">'
          + escapeHtml(t('subagents.wake')) + '</button>' : '');
      }
      const kind = freezeKindOf(info);
      const whyKey = freezeReasonLabelKey(freezeReasonWire(info));
      const why = info.frozenReasonLabel || (whyKey ? t(whyKey) : '');
      const detail = String(info.frozenDetail || '');
      const hours = Number(info.dormantHours) || 0;
      const tipLines = [];
      // 冻结状态两套真相批（2026-11-05）：按钮与否只认后端权威块的 `wakeable`
      // （与唤醒闸同一份读数）；旧后端无块才退回分类表。
      if (!wakeableOf(info)) {
        // 自动恢复族：说清「在等什么 + 会不会自己好」。
        tipLines.push(t(kind === FREEZE_KIND.window ? 'subagents.freezeWindowTip' : 'subagents.freezeAutoTip'));
        if (why) tipLines.push(t('subagents.frozenReason', { reason: why }));
        if (detail) tipLines.push(detail);
        return '<span class="bg-task-waiting" role="status" title="'
          + escapeHtml(tipLines.join('\n')) + '">'
          + statusIcon('snowflake', 11, 'st-warn')
          + escapeHtml(why ? t('subagents.freezeBadgeWithReason', {
            word: t(freezeBadgeKey(kind)), reason: why,
          }) : t(freezeBadgeKey(kind)))
          + '</span>';
      }
      tipLines.push(t('subagents.frozenTip'));
      if (why) tipLines.push(t('subagents.frozenReason', { reason: why }));
      if (detail) tipLines.push(detail);
      if (hours > 0) tipLines.push(t('subagents.frozenDormantTip', { hours: String(hours) }));
      const tip = tipLines.join('\n');
      return '<span class="bg-task-frozen" role="status" title="' + escapeHtml(tip) + '">'
        + statusIcon('snowflake', 11, 'st-warn')
        + escapeHtml(why ? t('subagents.frozenBadgeWithReason', { reason: why }) : t('subagents.status.frozen'))
        + '</span>'
        + '<button class="bg-task-wake" type="button" data-bg-wake="'
        + escapeHtml(String((info && info.sessionId) || '').replace(/^team-/, ''))
        + '" title="' + escapeHtml(t('subagents.wakeTip')) + '">'
        + escapeHtml(t('subagents.wake')) + '</button>';
    })()
    : '';
  const waitingPart = (rowState === 'waiting')
    ? (waitingMode === 'standby'
      ? '<span class="bg-task-waiting"'
        + (info.waiting.detail ? ' title="' + escapeHtml(info.waiting.detail) + '"' : '')
        + '>' + escapeHtml(t(info.waiting?.roundComplete ? 'subagents.status.roundComplete' : 'subagents.status.standby')) + '</span>'
      : '<span class="bg-task-waiting"' + (waitingTip ? ' title="' + escapeHtml(waitingTip) + '"' : '') + '>'
        + escapeHtml(t('subagents.status.waitingMerge') + waitingText) + '</span>')
    : '';
  // #752 空闲细分注记：idle/waiting 行显示「在等什么」（等后台任务名+时长+健康色 /
  // 等子代理回执 N 条 / 长空闲提醒徽标）。仅在停靠态出现，active 行零变化。
  // （计算已上移至 uptime 槽之前——长空闲态收敛批：uptime 与注记不并列。）
  const idleHealthDot = idleDetail && idleDetail.health
    ? '<span class="bg-health-dot bg-health-' + idleDetail.health + '" title="' + escapeHtml(t('bg.health.' + idleDetail.health)) + '" aria-hidden="true"></span>'
    : '';
  const idlePart = idleDetail
    ? '<span class="bg-task-idle-detail' + (idleDetail.stale ? ' bg-idle-stale' : '') + '" role="' + (idleDetail.stale ? 'alert' : 'note') + '">'
      + idleHealthDot
      + (idleDetail.stale ? statusIcon('timer-off', 11, 'st-warn') : '')
      + escapeHtml(idleDetail.text) + '</span>'
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
  const levelCls = (opts.headLevel ? ' bg-task-row-head' : '') + (opts.childLevel ? ' bg-task-row-child' : '');
  // 行集收敛批（2026-11-02 · RCA 必须修③）：行层级稳定 DOM 标识——组头 head /
  // 子行 child / 顶层 top 三档（CSS 视觉档随 .bg-task-row-head/-child 两级类；
  // 本属性 = 回归 dump 与读屏之外的第③个可辨锚，class 之外的机器可读面）。
  // 实体锚（agentflow 错标修复批 2026-11-06）：与实体徽标同源同条件——属性是「这一行
  // 代表一次 dispatcher 编排」的机器可读面，徽标没有的事实不许由属性编出来（层级锚
  // data-bg-row-level="head" 恒在场，两者不是一回事）。
  const levelAttr = opts.headLevel
    ? ' data-bg-row-level="head"' + (entityWord ? ' data-bg-entity="agentflow"' : '')
    : opts.childLevel ? ' data-bg-row-level="child"' : ' data-bg-row-level="top"';
  // 野节点归置批（2026-11-05 腿③）：野行只多一个归置锚——`data-bg-orphan="1"`
  // （over 锚面：这一行在独立归置分组里）。野行**不带** data-bg-agentflow（归属锚是
  // 「我属于哪次编排」的事实，野行的父不可解析 ⇒ 没有这个事实，不许凭空编一个）。
  const wildAttr = opts.wild ? ' data-bg-orphan="1"' : '';
  // 三代层级批·判定表②：父不在场行的机器锚（真子行无此锚；野行走野组锚面 data-bg-orphan）。
  const absentAttr = (opts && opts.absentParent && opts.absentParent.parentSid)
    ? ' data-bg-parent-absent="1"' : '';
  // 归属锚（术语分层批 2026-11-05 · 铁律 8「归属靠结构，不写解释句」）：成员行带**所属
  // 整体行的定位键**（与 data-bg-key / bgRowLocatorKey 同一条缝）；整体行的实体锚
  // data-bg-entity="agentflow" 见上（来源条件，非层级恒真）。归属是结构事实，渲染面
  // 不写任何一句术语关系说明。
  const agentflowAttr = (opts.childLevel && opts.agentflowKey)
    ? ' data-bg-agentflow="' + escapeHtml(String(opts.agentflowKey)) + '"'
    : '';
  // 面板层级批（2026-10-31 ①）：组头折叠 caret——有子行的行首渲染（中性 muted、行体
  // 点击开详情不变；caret 自带停冒泡点击收起/展开子行树）。aria/title 随折叠态翻转
  // （屏幕阅读器与悬停同知）。层级缩进批（2026-11-05 腿②）：**任意有子行的行**都带
  // caret（每一层都能收起自己那棵子树），key = 行定位键（折叠偏好持久化的同一把缝）。
  const childCount = Number(opts.childCount) || 0;
  const collapsedNow = !!(childCount > 0 && opts.collapsed);
  const caretPart = childCount > 0 ? bgCaretHtml(sid || id, collapsedNow) : '';
  // 层级连接符批（2026-11-07）：导轨（转折号）在 caret 之前——`│  └─ ▾ 名字` 是树形
  // 的常规读法；导轨本身 aria-hidden。
  const railPart = String(opts.railHtml || '');
  // ── 三行结构（UI 批 2026-11-05 · 作者令逐字「分三行显示。第一行 name · 状态
  //    （用图标显示）· time。第二行 Badge。第三行 当前动作」）──────────────────
  //   · L1 `.bg-task-main`：name · 状态图标（bgRowStatus 单点发射，中文状态词转
  //     sr-only/aria-label，不再上屏）· uptime（margin-left:auto 钉右）；
  //   · L2 `.bg-task-badges`：身份/归属徽标（entity AgentFlow——仅 dispatcher 派生的整体行
  //     / kind kernel·dispatcher /
  //     Mail 地址 / 项目 / 孤儿来源 / 任务徽标 / 重试 ×N）——独立成行、允许换行；
  //   · L3 `.bg-task-subline`：当前动作（↳ 工具 / 空闲 N 分钟 / 等待合并轮 / 冻结原因 /
  //     卡死原因）＋任务摘要（有则跟随，muted；全文 tooltip）。L3 无内容 ⇒ 零渲染
  //     （行不因空行膨胀）。
  // 旧形态的三处叠字（左侧圆点 + 中文状态词 + 词前图标）在此收成**一个状态位**。
  const actionPart = stuckPart + frozenPart + waitingPart + idlePart + toolPart;
  const summaryPart = taskSummary
    ? '<span class="bg-task-sub" title="' + escapeHtml(String(info.task || '')) + '">' + escapeHtml(taskSummary) + '</span>'
    : '';
  // Silent-completion diagnostic: store-backed rows only (live rows never carry
  // one — info.diagnostic is '' there, so this is a zero-diff no-op for them).
  const diagPart = bgTaskDiagHtml(info.diagnostic);
  const line3 = (actionPart || summaryPart || diagPart)
    ? '<div class="bg-task-line bg-task-row-line3 bg-task-subline">' + actionPart + summaryPart + diagPart + '</div>'
    : '';
  return    '<div class="bg-task-row' + (rawState === 'done' && !chainContinued ? ' done' : '') + levelCls + '"' + levelAttr + wildAttr + absentAttr + agentflowAttr + bgDepthAttrs(Number(opts.depth) || 0) + bgCollapseAttrs(childCount, collapsedNow) + ' role="listitem" tabindex="0" ' + clickAttr + '>' +
    bgDepthText(opts.depth) +
    railPart +
    caretPart +
    '<div class="bg-task-info">' +
      '<div class="bg-task-line bg-task-main bg-task-row-line1">' +
        namePart +
        bgStatusHtml(statusState, { label: statusLabel, title: execution?.reason || chainTip, tone: statusTone }) +
        uptimePart +
      '</div>' +
      '<div class="bg-task-line bg-task-row-line2 bg-task-badges">' +
        entityPart + kindPart + bgAddrPart(info) + projectPart + orphanPart + absentPart + taskPart + retriesPart +
      '</div>' +
      line3 +
    '</div>' +
  '</div>';
}

/** Store 子条目轻行（不在注册表的终态子——结构可见、状态由 store 条目承载；词复用
 *  面板状态词既有键）。点击 = 与 live 子行同一详情承载（bgAgentPopup 历史恢复）。
 *  面板行说明批（2026-10-29 ②）：与 live 行同契约——泛名（def 名）主名让位任务
 *  摘要首行；实名行附摘要次行。
 *  术语分层批（2026-11-05）：徽标词走成员单词点 bgMemberKindWord（成员位不落整体词）；
 *  agentflowKey = 所属整体行定位键（归属靠结构的机器可读锚，不写解释句）。
 *  @param {any} child @param {string} [agentflowKey]
 *  @param {{depth?: number, childCount?: number, collapsed?: boolean, railHtml?: string}} [opts] */
function bgStoreChildHtml(child, agentflowKey, opts = {}) {
  const c = child && typeof child === 'object' ? child : {};
  const sid = String(c.taskId || '');
  const state = bgStoreChildState(String(c.status || ''));
  // 模型粒度批（作者令 2026-11-06 20:36）：待恢复行 = 「重启中断 · 待恢复」词 +
  // **逐个恢复**按钮（REST POST /api/delegates/restore，body {taskId}）——作者令
  // 「粒度可以是逐个点击」；点击不冒泡（行体点击仍是开详情）。
  const pendingRecovery = state === 'pending-recovery';
  const statusLabel = pendingRecovery
    ? t('delegate.pendingRecovery')
    : state === 'cancelled' ? t('flowmap.st.cancelled')
    : t('subagents.status.' + (state === 'active' ? 'running' : state));
  const restoreChip = pendingRecovery
    ? '<button class="bg-task-wake" type="button" data-bg-restore="' + escapeHtml(sid) +
      '" title="' + escapeHtml(t('delegate.restore')) + '">' + escapeHtml(t('delegate.restore')) + '</button>'
    : '';
  const kindWord = bgMemberKindWord({ source: String(c.source || ''), sessionId: sid });
  const rawName = String(c.agentName || '') || sid;
  const desc = String(c.description || c.prompt || '');
  const descLine = bgFirstLine(desc);
  const generic = bgGenericName(rawName, sid);
  const displayName = (descLine && generic) ? descLine : rawName;
  const taskSummary = (descLine && !generic && descLine !== displayName) ? descLine : '';
  const tipParts = [displayName];
  if (descLine && descLine !== displayName) tipParts.push(desc);
  else if (desc) tipParts.push(desc);
  tipParts.push(sid);
  const namePart = '<span class="bg-task-name" title="' + escapeHtml(tipParts.join(' · ')) + '">' + escapeHtml(displayName) + '</span>';
  const kindPart = kindWord ? '<span class="bg-task-kind">' + escapeHtml(kindWord) + '</span>' : '';
  const retries = c.retryCount || 0;
  const retriesPart = retries > 0
    ? '<span class="bg-task-retries" title="' + escapeHtml(t('manage.retry')) + ' ×' + retries + '">×' + retries + '</span>'
    : '';
  const childCount = Number(opts.childCount) || 0;
  const collapsedNow = !!(childCount > 0 && opts.collapsed);
  const caretPart = childCount > 0 ? bgCaretHtml(sid, collapsedNow) : '';
  // 层级连接符批（2026-11-07）：store 成员行同款导轨（构造器各带一行，产点仍唯一——
  // 字符串由 bgTreeRailHtml 一处生成，本处只消费）。
  const railPart = String(opts.railHtml || '');
  // Silent-completion diagnostic: L3 slot for completed-but-silent terminal children
  // (muted line after the summary; empty-run gets warn tone — an empty run no longer
  // reads as a clean completion).
  const diagPart = bgTaskDiagHtml(c.diagnostic);
  const line3Part = (taskSummary || diagPart)
    ? '<div class="bg-task-line bg-task-row-line3 bg-task-subline">'
      + (taskSummary
        ? '<span class="bg-task-sub" title="' + escapeHtml(desc) + '">' + escapeHtml(taskSummary) + '</span>'
        : '')
      + diagPart
      + '</div>'
    : '';
  return '<div class="bg-task-row bg-task-row-child" data-bg-row-level="child" data-bg-agentflow="' + escapeHtml(String(agentflowKey || '')) + '"' + bgDepthAttrs(opts.depth) + bgCollapseAttrs(childCount, collapsedNow) + ' role="listitem" tabindex="0" data-child-session="' + escapeHtml(sid) + '">' +
    bgDepthText(opts.depth) +
    railPart +
    caretPart +
    '<div class="bg-task-info">' +
      // 三行结构批：members 行同款（L1 名·状态图标·空 time 槽 / L2 kind+重试 / L3 摘要）。
      '<div class="bg-task-line bg-task-main bg-task-row-line1">' +
        namePart +
        bgStatusHtml(state === 'cancelled' ? 'done' : state, { label: statusLabel }) +
        restoreChip +
      '</div>' +
      '<div class="bg-task-line bg-task-row-line2 bg-task-badges">' + kindPart + retriesPart + '</div>' +
      line3Part +
    '</div>' +
  '</div>';
}

/** agentflow flow 结构行（chain.nodes 的 flow-step 投影）。徽标 = 成员词
 *  （bgMemberKindWord：flow-step 是这次编排里的**节点**——术语分层批 2026-11-05 退役了
 *  此处硬编码的整体词 AgentFlow，那个词此后只作整体行身份词）。
 *  点击 = S10 per-task 链视图（flow 节点详情 = 卡面即详情，「零新增弹层」纪律）。
 *  层级缩进批（2026-11-05 腿②）：opts.depth/childCount/collapsed——flow 结构投影行也走
 *  同一套深度锚与折叠锚（投影归属 = 整体行，见 bgRowTree 源④）。
 *  @param {any} node @param {string} [delegateKey] @param {string} [agentflowKey]
 *  @param {{depth?: number, childCount?: number, collapsed?: boolean, railHtml?: string}} [opts] */
function bgFlowChildHtml(node, delegateKey, agentflowKey, opts = {}) {
  const n = node && typeof node === 'object' ? node : {};
  const label = String(n.label || n.id || '');
  const st = String(n.status || 'pending');
  const wordKey = BG_FLOW_WORD_KEY[st] || '';
  const kindWord = bgMemberKindWord({ info: { kind: String(n.kind || 'flow-step') }, sessionId: String(n.id || '') });
  // flow 投影态 → bgRowStatus 状态键（单表；脏值回落 pending/idle 保守档）。
  const flowState = BG_FLOW_NODE_STATE[st] || 'idle';
  const deps = Array.isArray(n.deps) ? n.deps.map((d) => String(d || '')).filter(Boolean) : [];
  // DAG dependency chip (face C): execution-order edges are NOT parent-child nesting —
  // they render as a flat chip with the ⇢ glyph + dashed underline, visually distinct
  // from the tree rails (├─/└─) that carry the ownership hierarchy. Machine anchor
  // data-bg-deps; tooltip lists the upstream ids in full.
  const depPart = deps.length
    ? '<span class="bg-flow-deps" data-bg-deps="' + escapeHtml(deps.join(',')) + '" title="'
      + escapeHtml(t('subagents.dependsOnTip', { deps: deps.join(', ') })) + '">⇢ '
      + escapeHtml(t('subagents.dependsOn')) + ' ' + escapeHtml(deps.join(', ')) + '</span>'
    : '';
  const childCount = Number(opts.childCount) || 0;
  const collapsedNow = !!(childCount > 0 && opts.collapsed);
  const caretPart = childCount > 0 ? bgCaretHtml(String(n.id || ''), collapsedNow) : '';
  // 层级连接符批（2026-11-07）：flow 结构行同款导轨（产点仍唯一 = bgTreeRailHtml）。
  const railPart = String(opts.railHtml || '');
  return '<div class="bg-task-row bg-task-row-child" data-bg-row-level="child" data-bg-agentflow="' + escapeHtml(String(agentflowKey || '')) + '"' + bgDepthAttrs(opts.depth) + bgCollapseAttrs(childCount, collapsedNow) + ' role="listitem" tabindex="0" data-flow-delegate-key="' + escapeHtml(String(delegateKey || '')) + '" title="' + escapeHtml(label) + '">' +
    bgDepthText(opts.depth) +
    railPart +
    caretPart +
    '<div class="bg-task-info">' +
      '<div class="bg-task-line bg-task-main bg-task-row-line1">' +
        '<span class="bg-task-name">' + escapeHtml(label) + '</span>' +
        bgStatusHtml(flowState, { label: wordKey ? t(wordKey) : st }) +
      '</div>' +
      '<div class="bg-task-line bg-task-row-line2 bg-task-badges">' +
        '<span class="bg-task-kind">' + escapeHtml(kindWord) + '</span>' + depPart +
      '</div>' +
    '</div>' +
  '</div>';
}

/** 待恢复行计数（面板模型单点扫描）：store 子行 `status=pending-recovery` ∪ live 行
 *  同名状态位。冻结行不算（它有自己的 WS 唤醒腿 `data-bg-wake`）；本计数只服务
 *  「一键全部恢复」按钮的显隐（模型粒度批 2026-11-06，作者令两档恢复）。 */
function countPendingRecovery(model) {
  let n = 0;
  for (const [, info] of (model.entries || [])) {
    if (info && info.status === 'pending-recovery') n += 1;
  }
  for (const g of (model.groups || [])) {
    for (const c of (g.children || [])) {
      if (c.type === 'store' && String((c.child && c.child.status) || '') === 'pending-recovery') n += 1;
    }
  }
  return n;
}

export function renderBgAgentDropdown() {
  if (!activeView) return;
  const dropdownEl = activeView.dom.bgagentDropdownEl;
  const listEl = activeView.dom.bgagentDropdownListEl;
  const carrier = activeView.dom.bgDropdownEl || dropdownEl;
  if (!listEl || !carrier || carrier.classList.contains('hidden')) return;
  const sid = activeView?.sessionId;
  // Flow 会话可见性批（2026-11-01）：badge/头部计数/空态/行集全部走同一模型
  // （bgPanelModel 单点 = 主桶可见行 ∪ 作用域桶链行 ∪ 延续态组头）——计数签与
  // 列表永不打架的口径随模型单点延续。
  const model = bgPanelModel(sid);
  const bgAgents = model.bgAgents;
  // Author ruling 2026-09-14 16:30: an idle dispatcher row is neither listed nor
  // counted (it survives in the keep-alive window and the snapshot reports it —
  // see isBgRowVisible). Header count, empty state and rows all read this one
  // filtered set — 0 visible ⇒ count 0 + empty text, never a self-contradiction.
  const entries = model.entries;
  // 台账 #929：远端投影行（展示专用组，缀在本地行后；不入计数——见处理器红线注）。
  const remoteHtml = bgRemoteRowsHtml();
  // Panel header carries the live count (aria-live polite, spec §5).
  const headerEl = dropdownEl ? dropdownEl.querySelector('.bg-dropdown-header') : null;
  if (headerEl) {
    // 冻结通知策略批（2026-11-05）：头部计数后缀 = **冻结静默统计**（后端
    // `delegateFreezeDigest` 帧 / 快照同一形态）——「N 条已冻结（可唤醒）」/「M 条
    // 冻结待你处理」。这是被压掉的逐条通知的**唯一去处**：作者主动打开面板才看到，
    // 零弹窗零消息。
    const digest = state.freezeDigest || null;
    let digestText = '';
    if (digest && (Number(digest.recoverable) > 0 || Number(digest.needsHuman) > 0)) {
      const segs = [];
      if (Number(digest.recoverable) > 0) {
        segs.push(t('subagents.freezeDigestRecoverable', { count: Number(digest.recoverable) }));
      }
      if (Number(digest.needsHuman) > 0) {
        segs.push(t('subagents.freezeDigestNeedsHuman', { count: Number(digest.needsHuman) }));
      }
      digestText = ' · ' + segs.join(' · ');
    }
    // 模型粒度批（作者令 2026-11-06 20:36）：面板有重启中断行（pending-recovery）
    // ⇒ 头部给**一键全部恢复**按钮（「也可以是子agent面板一键全部恢复」）。行内逐个
    // 恢复 chip 在 bgStoreChildHtml；两档共用同一 REST 缝（delegateApi.restore*）。
    // DOM 构建器写法（component-contract R5：不裸写 innerHTML——文本全 textContent）。
    const pendingCount = countPendingRecovery(model);
    headerEl.textContent = t('subagents.header', { count: model.total }) + digestText;
    for (const group of digest?.providerGroups || []) {
      const line = document.createElement('div');
      line.textContent = t('subagents.providerFailureGroup', {
        provider: group.provider, count: group.count,
      });
      line.title = group.reason || '';
      headerEl.appendChild(line);
    }
    if (pendingCount > 0) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'bg-task-wake bg-restore-all';
      btn.setAttribute('data-bg-restore-all', '');
      btn.title = t('delegate.restoreAll');
      btn.textContent = t('delegate.restoreAll');
      headerEl.appendChild(btn);
    }
  }
  if (model.total === 0 && remoteHtml === '') {
    // 空态自动收起（修订批九点⑤ · 2026-10-09）：无内容不渲染空态区块——列表留白，
    // 头部计数（·0）即空态真值（原 spec §6 空态文案行停用，键保留）。
    // #929：远端行在场时本地空 ≠ 面板空——远端行使本早退失效（列表仍渲远端组）。
    // Flow 会话可见性批：total 含作用域桶链行与延续态组头——活链在场时本早退失效。
    // 收敛批第二律：空态同样写回「本帧本地行数 = 0」，签与行集任一时刻同源。
    listEl.innerHTML = '';
    listEl.setAttribute('data-bg-row-total', '0');
    return;
  }
  // ── 两级渲染（C2/R6 + 面板树批 2026-10-29 ①）：delegate 组头（徽标=编排模式）
  // ＋缩进一格子行；孤儿行提升顶层并带「来自 …」来源角标。 ──
  // 面板层级批（2026-10-31 ①）：组头可折叠——用户偏好 > 默认规（终态收起/进行中
  // 展开）；收起组头的子行不进本轮 HTML（计数签不受影响）。
  // Flow 会话可见性批（2026-11-01）：延续态组头（终态基座 + 活链）默认**展开**——
  // 活链可见即本批目的；链全终态（覆写缺席）回既有默认规。
  // 行集收敛批（2026-11-02）：渲染顺序改读模型产出的**行计划**（model.rows）——
  // 同一 sessionId 只出现一次（收敛已做在模型里），顺序 = 树序基座 + 顶层行的
  // 稳定并列序；计数（model.total）与行数自此由同一份行集派生。
  const { groups } = model;
  const htmlParts = [];
  // 链子行信息表（作用域桶行不在主桶 bgAgents——点击详情经此表解析，同 store 子行
  // 的 storeChildBySid 形制）。
  const chainBySid = new Map();
  // 层级缩进批（2026-11-05 腿② · 作者令「每一层都应该是上一层的缩进」）：
  // 深度 / 子树 / 树序全部读模型单点（model.tree，bgRowTree 一次算完）——本函数只做
  // **折叠剪枝**：收起某行 = 它的整棵子树本轮不出镜（hidden 沿父链下传，天然覆盖任意层），
  // 行集合与计数（model.total）不受影响（既有「折叠不改计数」口径）。
  const tree = model.tree || bgRowTree(model.rows);
  const hidden = new Set(); // 祖先被收起 ⇒ 本行及其子树整棵不出镜
  // 野节点归置批（2026-11-05 腿③）：野行不在主循环出镜——收集到 wildRows，循环后由
  // **独立归置分组**统一出镜（组头标签 + 组内行）。归置不改行集/计数，只换出镜面。
  const wildRows = [];
  for (const g of groups) {
    // 合成延续组头的行体点击 = delegate 详情弹窗（历史恢复）——info 不在主桶，
    // 经 chainBySid 兜底解析（与链子行同一缝）。
    if (g.synthesized) chainBySid.set(g.sid, g.info);
  }
  for (const row of tree.order) {
    const parentRow = tree.parentOf.get(row) || null;
    if (parentRow && hidden.has(parentRow)) { hidden.add(row); continue; }
    const depth = tree.depth.get(row) || 0;
    const kids = tree.kids.get(row) || [];
    const childCount = kids.length;
    // 折叠态 = 用户偏好（持久表）> 默认规（终态收起 / 进行中展开 / 活链延续组头展开）。
    const pref = childCount > 0 ? bgCollapseGet(bgRowSessionKey(row)) : undefined;
    const collapsed = childCount > 0 && (pref !== undefined ? pref : bgRowCollapseDefault(row));
    if (collapsed) hidden.add(row);
    // 深度/子树/折叠三锚（成员行与顶层行共用同一入参形；标签页与层级无关）。
    // 层级连接符批（2026-11-07）：导轨字符串同点产出（背景：作者令「缩进不明显，要有
    // 转折号」）——树形/深度一处算（bgTreeRailHtml 读同一份 tree），渲染面只消费。
    const rowOpts = { depth, childCount, collapsed, railHtml: bgTreeRailHtml(row, tree) };
    if (row.level === 'head') {
      const g = row.group;
      const continued = !!(g.chainLive && (bgRowState(g.info) === 'done' || bgRowState(g.info) === 'error'));
      htmlParts.push(bgTaskRowHtml(g.id, g.info, {
        headLevel: true,
        depth,
        // 来源对象（注册行镜像 = DelegateRegistry.Record 的 provider 镜像）：身份词
        // （kernel/dispatcher 逐字）读其 mode，agentflow 徽标读其 mode + orchestrationMode
        // ——一处给源，渲染面不各自读（agentflow 错标修复批 2026-11-06）。
        registryRow: g.row || null,
        // 显示名契约批（2026-10-22）：组头注册行标题（DelegateRegistry.Record.title
        // 的 provider 镜像——跨重启存活，存量记录自带）透传为主名第一源。
        rowTitle: (g.row && g.row.title) || '',
        // 面板命名批（2026-10-31 ②）：批次短名第一源 = 注册行镜像（spawn 落盘），
        // 快照/live 帧 delegateName 殿后（存量记录回填前 live 行即时可用）。
        shortName: (g.row && g.row.name) || g.info.delegateName || '',
        childCount,
        collapsed,
        chainContinued: continued,
        chainCount: g.children.filter((c) => c.type === 'chain').length,
      }));
      continue;
    }
    if (row.level === 'child') {
      const c = row.child || {};
      // 术语分层批（2026-11-05）：成员行的归属锚 = **所属整体行（组头）的定位键**
      // （bgRowLocatorKey 同一条缝——判等与渲染共用一个键，不留第二套口径）。
      const agentflowKey = bgRowLocatorKey({ id: row.group && row.group.id, info: row.group && row.group.info });
      if (c.type === 'live') htmlParts.push(bgTaskRowHtml(c.id, c.info, { childLevel: true, agentflowKey, ...rowOpts }));
      else if (c.type === 'store') htmlParts.push(bgStoreChildHtml(c.child, agentflowKey, rowOpts));
      else if (c.type === 'chain') {
        chainBySid.set(c.sid, c.info);
        htmlParts.push(bgTaskRowHtml(c.id, c.info, { childLevel: true, agentflowKey, ...rowOpts }));
      }
      else htmlParts.push(bgFlowChildHtml(c.node, row.group && row.group.key, agentflowKey, rowOpts));
      continue;
    }
    // root 锚（三代层级批）：标签面行——bgRootAnchorHtml 出镜（不带 .bg-task-row，
    // 不进行集/计数）；caret/折叠走同一把缝（键 = 桶根会话 id），收起 = 根下整棵子树隐藏。
    // 节点子智能体可见性批（2026-11-07）：store 子条目行（终态子智能体的结构面）先于
    // root/顶层分支——渲染走 bgStoreChildHtml 的 store 形态（状态词/恢复 chip/描述主名
    // 仍是该构造器单点），层级/导轨/折叠照树走（深度已由 bgRowTree 按属主边算出）。
    if (row.storeChild) {
      htmlParts.push(bgStoreChildHtml(row.storeChild, '', rowOpts));
      continue;
    }
    if (row.level === 'root') {
      htmlParts.push(bgRootAnchorHtml(bgRowSessionKey(row), childCount, collapsed));
      continue;
    }
    // 结构父（face C）：注册行合成的标签面行——活跃后代在场而父无 live 行时，父作为
    // 结构节点显示（不入计数、不带 .bg-task-row；折叠走同一把缝，键 = 父会话 id）。
    if (row.level === 'struct') {
      htmlParts.push(bgStructParentHtml(row, childCount, collapsed));
      continue;
    }
    // 顶层行（未被认领的 leftover / 收敛落点）：层级随树走（判定表①②带真实深度锚）。
    // 野节点归置批（2026-11-05 腿③）：判定式结论（row.wild，由野判定式单点算出）为真
    // 的行**不进正常树面**——它们没有可解析父，混在顶层行里等于假装自己
    // 是「游离但正常」的行；收进独立归置分组（循环后统一出镜，一行不丢）。
    if (row.wild) { wildRows.push(row); continue; }
    // 三代层级批·判定表：①态（父在场）不带「来自 …」来源标（父可见，标即撒谎）；
    // ②态（父不在场）带「父不在场」标（tooltip = 父会话 id）——两态都不进野组；
    // registered-absent（在册可解析的跨帧竞态行）保持既有孤儿角标随行（零回归）。
    if (row.parentState === 'absent-parent') {
      htmlParts.push(bgTaskRowHtml(row.id, row.info, { ...rowOpts, absentParent: row.absentParent || null }));
      continue;
    }
    // 子智能体标签批（2026-11-07）：父行在场（判定单点结论 = attached）的顶层行**是
    // 成员**——它挂在可见父行下，身份词该上屏（node 派生的 subagent 行此前被摘词门
    // 当孤儿处理，面板上只剩缩进没有徽标）。attached 之外的形态（野/在册缺席）不
    // 带本旗：摘词门逐字不变。
    htmlParts.push(bgTaskRowHtml(row.id, row.info, (row.orphan && row.parentState !== 'attached')
      ? { orphanFrom: row.orphan.parentSid, orphanTitle: row.orphan.parentTitle, bucketSid: sid, ...rowOpts }
      : { ...rowOpts, member: row.parentState === 'attached' }));
  }
  // 野节点独立归置分组（组头标签 → 组内行；组头不是行，不入行集与计数）。
  if (wildRows.length) {
    htmlParts.push(bgOrphanGroupHeadHtml(wildRows.length));
    for (const row of wildRows) {
      // 节点子智能体可见性批：父 node 行不在场（跨桶/竞态）的 store 子条目仍按 store
      // 形态出镜（不伪造深度、不带导轨——与野行同档；「来自 …」角标对 store 行无
      // 意义：它的父键就在 store 条目里，tooltip 已由构造器给）。
      htmlParts.push(row.storeChild
        ? bgStoreChildHtml(row.storeChild, '', { depth: 0, childCount: 0, collapsed: false })
        : bgOrphanRowHtml(row, sid));
    }
  }
  // 台账 #929：远端投影行缀尾（本地行为主的合并语义——本地空时本组独立成面）。
  // 行集收敛批：远端行是**跨会话集**（不入 per-session 计数，见 main.js 红线注），
  // 与本地行不同轴——本批不动其归属。
  htmlParts.push(remoteHtml);
  listEl.innerHTML = htmlParts.join('');
  // 行集收敛批（2026-11-02 第二律）：模型单点把「本帧渲染的本地行数」写进列表
  // 属性（data-bg-row-total），与头部计数同源同值——回归可机械核对「签 vs 行集」。
  listEl.setAttribute('data-bg-row-total', String(model.total));
  // ui-status-redesign：statusIcon() 兜底占位（<i data-lucide>）在此升级为真图标
  // （vendor lib 已就绪时上面已是内联 SVG，此调用零替换、幂等）。
  createIconsIn(listEl);

  // Wire click handlers for sub-agent rows
  listEl.querySelectorAll('[data-node-session-id]').forEach(row => {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      const rawSessionId = row.getAttribute('data-node-session-id');
      // Flow 会话可见性批：作用域桶链行不在主桶——chainBySid 兜底解析（主桶命中
      // 优先，零行为差）。
      const info = bgAgents[row.getAttribute('data-bg-key')] || chainBySid.get(rawSessionId.replace(/^team-/, ''));
      if (info && rawSessionId) {
        // Capture the dropdown BEFORE opening the popup — openStepPopup
        // switches activeView to the popup view whose fakeDom has no dropdown.
        // 后台合并批（2026-10-23 ⑥）：行所在面 = 合并载体 #bg-dropdown。
        const dropdown = activeView.dom.bgDropdownEl;
        // team-<sid> wraps the agent's bare session id (its ui.json key) —
        // strip it, matching how the retired Team panel used to open these
        // popups; live team-agent events keep flowing into the same view.
        const sessionId = rawSessionId.replace(/^team-/, '');
        // 面板命名批（2026-10-31 ② · 作者锚点）：弹窗头名 = 行首已解析显示名
        // （短名在场 = 短名；否则 = 标题/摘要首行）——不再把执行器 def 名
        // （「nebflow」）顶在弹窗头。
        const rowNameEl = row.querySelector('.bg-task-name');
        const rowName = rowNameEl ? rowNameEl.textContent : '';
        if (isBgAgentId(rawSessionId)) {
          // delegate-*/subtask-* → bg-agent popup (ephemeral sessions,
          // live-rendered; history only persists at completion).
          openBgAgentPopup(rawSessionId, rowName || info.name, info.task);
        } else {
          // dag-*/bare sid → flow popup — the SAME entry the Flow/Team panel
          // uses, so live events flow into one shared view instead of
          // building a duplicate hidden view per popup module.
          openFlowStepPopup(row.getAttribute('data-bg-key'), rowName || info.name, info.name, '', sessionId);
        }
        // Close the dropdown
        if (dropdown) dropdown.classList.add('hidden');
      }
    });
  });

  // name/address 批（2026-11-05）：地址 chip 点击 = 复制**完整**地址（视觉可截断，
  // 复制绝不截断——data-bg-copy 是全文）。停冒泡：行体点击仍是开详情；复制成功给
  // 一瞬 is-copied 着色（不弹 toast——复制是最轻的动作，反馈不该打断）。
  listEl.querySelectorAll('[data-bg-copy]').forEach(chip => {
    chip.addEventListener('click', (e) => {
      e.stopPropagation();
      const full = chip.getAttribute('data-bg-copy') || '';
      if (!full) return;
      try {
        const p = navigator.clipboard && navigator.clipboard.writeText(full);
        if (p && p.catch) p.catch(() => {});
      } catch (_) { /* 老引擎/无权限：粘不到剪贴板也不报错，chip 文本仍可手选 */ }
      chip.classList.add('is-copied');
      setTimeout(() => chip.classList.remove('is-copied'), 1200);
    });
  });

  // 面板层级批（2026-10-31 ①）：caret 点击 = 收起/展开**整棵子树**（停冒泡——行体
  // 点击开详情的语义不变）；偏好落 bgCollapseSet（行定位键 → localStorage 持久表，
  // 重渲染与刷新后都保持——层级缩进批 2026-11-05 腿②改口径）。
  listEl.querySelectorAll('[data-bg-caret]').forEach(caret => {
    caret.addEventListener('click', (e) => {
      e.stopPropagation();
      const caretKey = caret.getAttribute('data-bg-caret');
      if (!caretKey) return;
      const restoreFocus = document.activeElement === caret;
      bgCollapseSet(caretKey, !caret.classList.contains('is-collapsed'));
      renderBgAgentDropdown();
      if (restoreFocus) {
        const replacement = [...listEl.querySelectorAll('[data-bg-caret]')]
          .find(el => el.getAttribute('data-bg-caret') === caretKey);
        if (replacement) replacement.focus();
      }
    });
  });

  // 错误冻结批（2026-11-01）：冻结行的「唤醒」按钮 = 显式唤醒动作（WS
  // `delegateWake`）。停冒泡（不触发行体开详情）；后端回 `delegateWakeResult`
  // 由 main.js 收口（成功撤冻结标记，失败保留 + toast）。
  listEl.querySelectorAll('[data-bg-wake]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const sid = btn.getAttribute('data-bg-wake');
      if (!sid) return;
      btn.disabled = true;
      sendWs({ type: 'delegateWake', sessionId: sid });
    });
  });

  // 模型粒度批（作者令 2026-11-06 20:36）：手动恢复两档的接线——
  //   · 逐个：待恢复行 chip（`data-bg-restore` = taskId）→ POST /api/delegates/restore
  //     body {taskId}（同 id 续原转录；资格闸/墓碑/预算照守）；
  //   · 一键全部：头部按钮（`data-bg-restore-all`）→ POST /api/delegates/restore-all。
  // 后端零通知（作者令「不自动恢复…我们只需要把状态保存做好」）：结果只回本响应
  // （toast），面板行状态随 revive 自身与下一次快照翻新。停冒泡：行体点击仍是开详情。
  listEl.querySelectorAll('[data-bg-restore]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const taskId = btn.getAttribute('data-bg-restore');
      if (!taskId) return;
      btn.disabled = true;
      restoreDelegate(taskId).then((r) => {
        import('../modal.js').then((m) => {
          if (r && r.state === 'ok') m.showToast(t('delegate.restoreDone', { count: 1 }));
          else m.showToast(t('delegate.restoreFailed', { error: String((r && r.data && r.data.error) || (r && r.status) || 'network') }), 'error');
        }).catch(() => {});
        renderBgAgentDropdown();
      }).catch(() => {});
    });
  });
  const restoreAllBtn = headerEl ? headerEl.querySelector('[data-bg-restore-all]') : null;
  if (restoreAllBtn) {
    restoreAllBtn.addEventListener('click', () => {
      restoreAllBtn.disabled = true;
      restoreAllDelegates().then((r) => {
        import('../modal.js').then((m) => {
          if (r && r.state === 'ok') {
            const revived = Array.isArray(r && r.data && r.data.revived) ? r.data.revived.length : 0;
            m.showToast(t('delegate.restoreDone', { count: revived }));
          } else {
            m.showToast(t('delegate.restoreFailed', { error: String((r && r.data && r.data.error) || (r && r.status) || 'network') }), 'error');
          }
        }).catch(() => {});
        renderBgAgentDropdown();
      }).catch(() => {});
    });
  }

  // delegate-ui 批（C2/R5）：store 子条目行（不在注册表的终态子）点击 = 同一套详情
  // 承载（bgAgentPopup 历史恢复）；agentflow flow 行点击 = S10 per-task 链视图
  // （flow 节点详情 = 卡面即详情，零新增弹层）。
  const storeChildBySid = new Map();
  for (const g of groups) {
    for (const c of g.children) {
      if (c.type === 'store') storeChildBySid.set(c.sid, c.child);
    }
  }
  for (const row of model.rows) {
    if (row.storeChild && row.storeChild.taskId) {
      storeChildBySid.set(String(row.storeChild.taskId), row.storeChild);
    }
  }
  listEl.querySelectorAll('[data-child-session]').forEach(row => {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      const sid = row.getAttribute('data-child-session');
      const c = sid ? storeChildBySid.get(sid) : null;
      if (c) {
        const dropdown = activeView.dom.bgDropdownEl;
        // 面板命名批（2026-10-31 ②）：弹窗头名 = 行首已解析显示名（store 子行 =
        // 摘要首行），不再泛名「subagent」顶上头。
        const rowNameEl = row.querySelector('.bg-task-name');
        openBgAgentPopup(sid, rowNameEl ? rowNameEl.textContent : (String(c.agentName || '') || sid), String(c.description || c.prompt || ''));
        if (dropdown) dropdown.classList.add('hidden');
      }
    });
  });
  listEl.querySelectorAll('[data-flow-delegate-key]').forEach(row => {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      const key = row.getAttribute('data-flow-delegate-key');
      if (!key) return;
      const dropdown = activeView.dom.bgDropdownEl;
      import('../delegateChainView.js').then(({ openDelegateChainTask }) => {
        openDelegateChainTask(key, {});
      }).catch(() => {});
      if (dropdown) dropdown.classList.add('hidden');
    });
  });

  // Keyboard (spec §7 / APG listbox): Enter/Space opens the row's popup,
  // ArrowUp/ArrowDown move between rows. Property assignment — idempotent
  // across re-renders (no listener accumulation).
  listEl.onkeydown = (e) => {
    // Native controls handle their own keyboard activation; do not open the row instead.
    if (e.target && e.target.closest && e.target.closest('button, a, input, select, textarea')) return;
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

// Snapshot-on-open (2026-09-13 memory-track visibility forensics, fix B).
// The registry snapshot used to be fetched ONCE per WS (re)connect (the
// getActiveAgents send inside onReconnect below) — enough for every sub-agent
// whose live frames reach the client, but NOT for the (now retired, govmemory
// batch) memory-consolidation track: the backend silenced that track's frames
// on purpose (track events must not pollute the parent view), so a run was
// invisible unless the browser happened to refresh inside its window.
// Fetch on open instead: user-driven, reuses the existing getActiveAgents
// request and the existing activeAgents consumer (since 批 snapmerge-impl that
// consumer MERGES the snapshot into the rendered row set instead of rebuilding
// it, so a backfill arriving empty no longer wipes the open panel's rows)
// (zero backend change, no new protocol). The 1s cooldown only debounces rapid
// open/close taps — a normal open sends exactly one frame, closing sends none.
let lastActiveAgentsFetchAt = 0;
export function fetchActiveAgentsOnOpen() {
  const now = Date.now();
  if (now - lastActiveAgentsFetchAt < 1000) return;
  lastActiveAgentsFetchAt = now;
  sendWs({ type: 'getActiveAgents' });
  refreshScopedTasks();
}

// Sub-agents panel (2026-08-25): any activity on a stuck entry clears the
// stuck flag — the row falls back to its live state on the next render.
export function clearBgStuck(msg) {
  const sid = msg.rootSessionId || msg.sessionId;
  const aid = msg.agentId;
  if (!sid || !aid) return;
  const entry = state.sessionBgAgents[sid] && state.sessionBgAgents[sid][aid];
  if (entry && entry.stuck) {
    entry.stuck = null;
    updateBgAgentIndicator(sid);
  }
}

// 节点子智能体可见性批（2026-11-07）：provider 镜像补拉到达 ⇒ 面板在开时重渲染
// （帧只当触发器，零帧解读——与 delegateChainView / taskList 的同名监听同园口径）。
// 只在合并载体开着时刷：关着不打扰（下一次打开会读到新镜像）。
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener(EVENTS.DELEGATE_CHAIN_MIRROR_UPDATED, () => {
    updateBgAgentIndicator(null, { keepDropdownOpen: true });
    const dd = activeView && activeView.dom && activeView.dom.bgDropdownEl;
    if (dd && !dd.classList.contains('hidden')) renderBgAgentDropdown();
  });
}
