// main.js 抽取(FE组件化批次七 2026-09-28):后台代理下拉族 模块(行为保持)。
// USTC 合并移植（2026-10-10）：面板体更新为本地 delegate-ui 两级化批（C2/R6 ·
// 定稿蓝本 §1.2/§2）与「修订批（用户九点②/⑤ · 2026-10-09）」语义——远端拆分时
// 携带的旧快照面板体由本地新版整体替换（SubTask→Delegate 显示词归一、C1 过渡
// 过滤、两级组头/子行、空态自动收起），本文件骨架（indicator/uptime/fetch/stuck
// 族）沿用远端抽取形态。
import state from '../state.js';
import { sendWs } from '../ws.js';
import { t } from '../i18n.js';
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
// panel-merge-status 批（2026-10-25）：候合并行队列细节（位次/重试/拒绝摘要）——
// /api/merge-queue 只读状态面单点（叶子模块，事件驱动 + 节流退避，禁空转轮询）。
import { ensureMergeQueueStatus, mergeQueueEntryFor } from '../mergeQueueStatus.js';
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
  const count = bgPanelModel(sid).total;
  if (count > 0) {
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
      // panel-merge-status 批（2026-10-25）：面板在开且含候合并行 ⇒ uptick 同拍取
      // 一次合并队列状态面（模块内 15s 节流 + 失败 60s 退避兜底——位次/拒绝摘要
      // 随队列推进在开面板期间自愈刷新；无候合并行零取数）。取到新内容重渲染。
      if (dd.querySelector('.bg-task-waiting')) {
        ensureMergeQueueStatus().then((changed) => {
          if (changed) renderBgAgentDropdown();
        }).catch(() => {});
      }
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
 *  @param {string} execMode [[bgDelegateExecMode]] 的读数
 *  @returns {string} */
function bgDelegateBadgeWord(execMode) {
  const mode = String(execMode || '');
  if (mode === 'kernel' || mode === 'dispatcher') return mode;
  return '';
}

/** Store 子条目状态 → 面板行状态（SubAgentTask 值域 running|completed|failed|restarting；
 *  未知归 active 保守侧——不臆造终态）。 */
function bgStoreChildState(status) {
  if (status === 'completed') return 'done';
  if (status === 'failed') return 'error';
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

/** Store 子条目 kind 词（source 数据词：subagent/subtask/delegate；大写形态与面板
 *  既有 kind 词族对齐——数据非文案，零 locale 键）。修订批九点②（2026-10-09）：
 *  「subtask」显示词更名 delegate 语义（SubTask→Delegate；数据原文保留在
 *  source 字段与 title，不丢真源）。 */
function bgChildKindWord(source) {
  if (source === 'subagent') return 'Subagent';
  if (source === 'subtask' || source === 'delegate') return 'Delegate';
  return String(source || '');
}

/** 快照/帧 kind 值 → 面板显示词（修订批九点②补漏 · 2026-10-09）：activeAgents
 *  快照与 taskStuck 帧携带引擎协议 kind 值（AgentKind.SubTask = "SubTask"）——
 *  显示面统一更名 delegate 语义（SubTask→Delegate）；权限矩阵消费的协议值
 *  （managePanel.OPERABLE_KINDS 'SubTask'）不受影响——本函数只在**渲染点**用，
 *  不改写 info.kind 数据面（真源原样保留）。 */
function bgKindDisplayWord(kind) {
  const k = String(kind || '');
  if (k === 'SubTask' || k === 'subtask') return 'Delegate';
  return k;
}

/** 术语分层批（2026-11-05 · 作者令「agentflow = 整个组织行为的代称（一次编排 = 一个
 *  agentflow），节点 = agentflow 里的成员，成员分层」）：**整体行身份词单点**。
 *  取值只来自 locale 键 `subagents.kindAgentflow`（zh/en 同词面）——唯一渲染点是
 *  bgTaskRowHtml 的 headLevel 分支（组头行 / 合成延续组头行）；别的 web 模块不得再写
 *  这个词面（回归 spec bg-agentflow-terms H5 静态钉）。
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
  if (execMode === 'dispatcher' && orchestrationMode === 'agentflow') return t('subagents.kindAgentflow');
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
 * （bgAgentKindFromSession 供 main.js 的 info.kind 记账，返回的协议词保留）。
 * @param {{source?: string, info?: any, sessionId?: string, id?: string}} [member]
 * @returns {string}
 */
function bgMemberKindWord(member) {
  const m = member && typeof member === 'object' ? member : {};
  const info = m.info && typeof m.info === 'object' ? m.info : {};
  const sid = String(m.sessionId || (info && info.sessionId) || m.id || '').replace(/^team-/, '');
  // 分发器 / 节点是前缀判读的正向 kind 徽标（与 isDispatcherSession / 引擎会话前缀同源
  // 契约）；两者都从 locale 取词，成员位只有这一个字面量表。
  if (isDispatcherSession(sid)) return t('subagents.kindDispatcher');
  const nodeWord = t('subagents.kindNode');
  if (sid.startsWith('node-')) return nodeWord;
  const source = String(m.source || '');
  const word = source
    ? bgChildKindWord(source)
    : (bgKindDisplayWord(String(info.kind || '')) || bgAgentKindFromSession(sid));
  // 整体词面在成员层归并为节点词（flow 结构行 / dag-* / workflow-* 同一判据）。
  if (word === 'Flow' || word === 'AgentFlow' || word === 'flow-step') return nodeWord;
  return word;
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
  /** 父行表（null = 根）。 */
  const parentOf = new Map();
  for (const row of list) {
    parentOf.set(row, null);
    if (!row || row.level !== 'child') continue;
    const self = bgRowSessionKey(row);
    const info = row.info || {};
    const c = row.child || {};
    const groupKey = bgRowLocatorKey({ id: row.group && row.group.id, info: row.group && row.group.info });
    // ①②③：真实父链接（store 子条目无 info，父链接落在 G3 条目自身上）。
    let pk = String(info.parentSessionId || '').replace(/^team-/, '');
    if (!pk && c.type === 'store') pk = String((c.child && c.child.parentSessionId) || '').replace(/^team-/, '');
    // ④：无父链接的成员行（flow 结构投影等）挂所属整体行。
    if (!pk) pk = groupKey;
    const cand = (pk && pk !== self) ? byKey.get(pk) : null;
    if (cand && cand !== row && cand.group === row.group) { parentOf.set(row, cand); continue; }
    // 回落：所属整体行下一格（父不在行集 / 跨组父 / 自指）。
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

/** 行父键（野节点判定式的输入面单点）：一行「挂在谁下面」——与 bgRowTree 的父链接
 *  四源**同一批源**（live/快照 parentSessionId → store 子条目自身 parentSessionId →
 *  所属整体行定位键），不另立第五套口径。顶层 leftover 行没有 group ⇒ 只剩
 *  parentSessionId 一源（无该字段 = 无父，不是「父是空串」）。
 *  @param {any} row @returns {string} */
function bgRowParentKeyOf(row) {
  const info = (row && row.info) || {};
  const c = (row && row.child) || {};
  let pk = String(info.parentSessionId || '').replace(/^team-/, '').trim();
  if (!pk && c.type === 'store') {
    pk = String((c.child && c.child.parentSessionId) || '').replace(/^team-/, '').trim();
  }
  if (!pk) {
    const g = row && row.group;
    pk = bgRowLocatorKey({ id: g && g.id, info: g && g.info });
  }
  return pk;
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
export function bgOrphanRowKeys(rows) {
  const list = Array.isArray(rows) ? rows : [];
  const byKey = new Map();
  for (const row of list) {
    const k = bgRowSessionKey(row);
    if (k && !byKey.has(k)) byKey.set(k, row);
  }
  // ③ 的在册面：注册行镜像按 delegate id 索引（同 bgDelegateGroups 的 rowByDelegate）。
  const regBySid = new Map();
  for (const r of listDelegateRows()) {
    const d = String((r && r.delegateId) || '').replace(/^team-/, '');
    if (d) regBySid.set(d, r);
  }
  const resolvable = (pk, self) => {
    if (!pk || pk === self) return false;   // 自指 / 无父字段 ⇒ 不可解析
    if (byKey.has(pk)) return true;         // ① ②：父行在本轮行集里（整体行或成员行）
    const reg = regBySid.get(pk);           // ③：父会话在册
    if (!reg) return false;
    const chain = getChainByTask(String(reg.taskNo || pk));
    if (chain && Array.isArray(chain.nodes) && chain.nodes.length) return true;
    return bgDelegateScopeChainRows(pk).length > 0;
  };
  const wild = new Set();
  for (const row of list) {
    if (!row || row.level === 'head') continue; // 整体行是根，永非野
    if (row.level === 'child') continue;        // 成员行的父 = 所属整体行（在行集里）
    const self = bgRowSessionKey(row);
    if (resolvable(bgRowParentKeyOf(row), self)) continue;
    if (self) wild.add(self);
  }
  return wild;
}

/**
 * 跨通路行集组装（收敛批第一律 + 第三律）：把二级结构的对位行与顶层 leftover
 * 合成一个**有序、按 sessionId 唯一**的渲染计划。渲染面与计数面只读本函数产出——
 * 「签与列表永不打架」自此是结构事实，而不是两次独立计算碰巧相等。
 * 野节点归置批（2026-11-05 腿③）：另返回 `orphanCount` = 归置面上的野行数（判定式
 * 单点 `bgOrphanRowKeys` 的结论计数）；它与 `total` 不同源不冲突——`total` 计全部
 * 行（野行照旧计入、一行不丢），`orphanCount` 只计其中换面出镜的那部分。
 *
 * @param {{groups: Array<Object>, leftovers: Array<Array<any>>, orphanBy: Map<string, any>}} model
 * @returns {{items: Array<Object>, total: number, tree: {order: Array<any>, roots: Array<any>, parentOf: Map<any, any>, kids: Map<any, Array<any>>, depth: Map<any, number>}, orphanCount: number}}
 */
function bgRowPlan(model) {
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
  for (const r of kept) if (!placed.has(r)) items.push(r);
  // ④ 层级缩进批（2026-11-05 腿②）：基座序 → **树序**（父行后紧跟整棵子树，同父兄弟
  // 保持基座序；无嵌套时与基座序逐位相同——既有行序零回归）。深度/子树同点产出，
  // 渲染面（折叠剪枝）与回归 dump（data-bg-depth）共用这一份。
  const tree = bgRowTree(items);
  // ⑤ 野节点归置批（2026-11-05 腿③）：判定式单点（bgOrphanRowKeys）在**收敛+树序之后**
  // 跑一次，结论写到 row.wild——渲染面只读这个布尔，不在渲染分支里自算野性。行集与
  // 计数（total）**不因归置而变**：野行照旧计入（一行不丢），只是换一个归置面出镜。
  const wildKeys = bgOrphanRowKeys(tree.order);
  for (const row of tree.order) row.wild = wildKeys.has(bgRowSessionKey(row));
  return { items: tree.order, total: tree.order.length, tree, orphanCount: wildKeys.size };
}

/**
 * 面板模型单点（Flow 会话可见性批 2026-11-01 · 收敛批 2026-11-02 改口径）：
 * badge 计数 / 头部计数 / 空态 / 行集四个消费面共读此一处——「签与列表永不打架」
 * 的口径随模型延续。`total` = 收敛后的行集长度（`bgRowPlan().items.length`）：
 * 同一 sessionId 无论从哪条通路进来只计一次，行数与计数由**同一份行集**派生。
 * 层级缩进批（2026-11-05 腿②）：`rows` 已是树序、`tree` 带深度/子树（折叠剪枝用）。
 *
 * @param {string} bucketSid 主会话桶 id（视图 sessionId）
 * @returns {{bgAgents: Object, entries: Array<[string, any]>, groups: Array<Object>, leftovers: Array<Array<any>>, orphanBy: Map<string, any>, rows: Array<Object>, total: number, tree: {order: Array<any>, roots: Array<any>, parentOf: Map<any, any>, kids: Map<any, Array<any>>, depth: Map<any, number>}, orphanCount: number}}
 */
function bgPanelModel(bucketSid) {
  const bgAgents = (bucketSid && state.sessionBgAgents[bucketSid]) || {};
  const entries = visibleBgAgentEntries(bgAgents);
  const { groups, leftovers, orphanBy } = bgDelegateGroups(entries, bucketSid);
  const plan = bgRowPlan({ groups, leftovers, orphanBy });
  return {
    bgAgents, entries, groups, leftovers, orphanBy,
    rows: plan.items,
    total: plan.total,
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
  return '<span class="bg-task-caret' + (collapsed ? ' is-collapsed' : '') + '" data-bg-caret="' +
    escapeHtml(String(key || '')) + '" role="button" aria-label="' + escapeHtml(word) + '" title="' +
    escapeHtml(word) + '">' + statusIcon(collapsed ? 'chevron-right' : 'chevron-down', 11) + '</span>';
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
 * @param {string} id @param {any} info @param {{childLevel?: boolean, headLevel?: boolean, registryRow?: any, agentflowKey?: string, depth?: number, rowTitle?: string, shortName?: string, childCount?: number, collapsed?: boolean, wild?: boolean, orphanFrom?: string, orphanTitle?: string, bucketSid?: string, chainContinued?: boolean, chainCount?: number}} [opts]
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

function bgTaskRowHtml(id, info, opts = {}) {
  const rawState = bgRowState(info);
  // 延续态覆写只对终态基座行生效（进行中 delegate 行的链子行走普通两级呈现）。
  const chainContinued = !!(opts.chainContinued && (rawState === 'done' || rawState === 'error'));
  const rowState = chainContinued ? 'active' : rawState; // 展示态：点形/词档按活链走
  const terminal = !chainContinued && (rawState === 'done' || rawState === 'error');
  // 冻结态误报批（2026-11-05）：冻结族的**行首状态词按呈现分类给**——自动恢复族
  // （时间表/退避/探测）不写「已冻结」，只在需要人时写；状态点同款分色（自动恢复族
  // 用既有的 waiting 点，需人介入保持 frozen 点）。
  const freezeKind = rowState === 'frozen' ? freezeKindOf(info) : '';
  const statusLabel = chainContinued
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
  const kindWord = (!opts.childLevel && (kind === 'Delegate' || kind === 'Subagent')) ? '' : kind;
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
    ? '<span class="bg-task-stuck" role="alert">' + statusIcon('timer-off', 11, 'st-err') + escapeHtml(
        info.stuck.action === 'restart'
          ? t('manage.stuckAutoRestart')
          : info.stuck.action === 'hard-abort'
            ? t('manage.stuckHardAbort')
            : info.stuck.action === 'failed'
              ? t('manage.stuckFailed')
              : t('manage.stuck', { secs: info.stuck.idleSecs ?? '' })
      ) + '</span>'
    : '';
  // Waiting（held-receipt 投递可靠性批 2026-10-21 fix 4）：显式停靠标签——
  // 「等合并轮」的 delegate 行显示等待词，不再貌似 Idle 完成。
  // 台账 #896 交通引导员制（2026-10-26 · 前身 = panel-merge-status 批 2026-10-25）：
  // 候合并行在静态「等待合并轮」词之上追加队列细节段（' · ' 连接，一段一行）：
  //   · 放行 —— state=turn-granted（合并窗持有中）→「轮到你合并」段（位次段让位）；
  //   · 位次 —— state=waiting 且前面还有人（N = position-1，载荷只读）→「前面还有
  //     N 位」；
  //   · 原因 —— lastBlockReason 非空（waiting：为什么还没放行——base 未净空等）→
  //     一句原因摘要（展示截断，全文进 tooltip）。
  // 数据 = /api/merge-queue 状态面（mergeQueueStatus 单点；join key = 行 sid）。
  // 降级红线：查无条目 / API 未生效（404）⇒ 各段全缺，行回静态「等待合并轮」
  // 既有形态——零报错、零占位、零 console 噪音。
  let waitingText = '';
  let waitingTip = '';
  // 案A 待命批 2026-10-31（状态矩阵批收编）：mode=standby 行（delegateWaiting 帧 /
  // 快照 standbySince 复原）跳过 merge-queue 查询——「待命」不是合并态；徽标词换
  // subagents.status.standby（tooltip = 申报的「在等什么」）。
  const waitingMode = (info && info.waiting && info.waiting.mode) || '';
  if (rowState === 'waiting' && waitingMode !== 'standby') {
    const mq = mergeQueueEntryFor(String((info && info.sessionId) || '').replace(/^team-/, ''));
    if (mq) {
      const segs = [];
      if (mq.state === 'turn-granted') segs.push(t('subagents.mergeQueue.turn'));
      else if (mq.ahead > 0) segs.push(t('subagents.mergeQueue.ahead', { n: mq.ahead }));
      if (mq.reason) segs.push(t('subagents.mergeQueue.reason', { reason: mq.reason }));
      if (segs.length) {
        waitingText = ' · ' + segs.join(' · ');
        waitingTip = (mq.reasonFull || mq.reason)
          ? t('subagents.mergeQueue.reason', { reason: mq.reasonFull || mq.reason })
          : '';
      }
    }
  }
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
        tip.push(t(infra ? 'subagents.frozenInfraTip' : 'subagents.frozenTip'));
        if (why) tip.push(t('subagents.frozenReason', { reason: why }));
        if (detail) tip.push(detail);
        if (tries > 0) tip.push(t('subagents.frozenAutoResumes', { n: String(tries) }));
        if (info.autoResumesExhausted) tip.push(t('subagents.frozenAutoResumesSpent'));
        if (hours > 0) tip.push(t('subagents.frozenDormantTip', { hours: String(hours) }));
        const label = why
          ? t('subagents.frozenAwaitingYou', { reason: why })
          : t('subagents.frozenAwaitingYouNoReason');
        return '<span class="bg-task-frozen" role="status" title="' + escapeHtml(tip.join('\n')) + '">'
          + statusIcon('snowflake', 11, 'st-warn')
          + escapeHtml(label)
          + '</span>'
          + '<button class="bg-task-wake" type="button" data-bg-wake="'
          + escapeHtml(String((info && info.sessionId) || '').replace(/^team-/, ''))
          + '" title="' + escapeHtml(t('subagents.wakeTip')) + '">'
          + escapeHtml(t('subagents.wake')) + '</button>';
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
        + '>' + escapeHtml(t('subagents.status.standby')) + '</span>'
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
  const line3 = (actionPart || summaryPart)
    ? '<div class="bg-task-line bg-task-row-line3 bg-task-subline">' + actionPart + summaryPart + '</div>'
    : '';
  return '<div class="bg-task-row' + (rawState === 'done' && !chainContinued ? ' done' : '') + levelCls + '"' + levelAttr + wildAttr + agentflowAttr + bgDepthAttrs(Number(opts.depth) || 0) + bgCollapseAttrs(childCount, collapsedNow) + ' role="listitem" tabindex="0" ' + clickAttr + '>' +
    caretPart +
    '<div class="bg-task-info">' +
      '<div class="bg-task-line bg-task-main bg-task-row-line1">' +
        namePart +
        bgStatusHtml(statusState, { label: statusLabel, title: chainTip, tone: statusTone }) +
        uptimePart +
      '</div>' +
      '<div class="bg-task-line bg-task-row-line2 bg-task-badges">' +
        entityPart + kindPart + bgAddrPart(info) + projectPart + orphanPart + taskPart + retriesPart +
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
 *  @param {{depth?: number, childCount?: number, collapsed?: boolean}} [opts] */
function bgStoreChildHtml(child, agentflowKey, opts = {}) {
  const c = child && typeof child === 'object' ? child : {};
  const sid = String(c.taskId || '');
  const state = bgStoreChildState(String(c.status || ''));
  const statusLabel = t('subagents.status.' + (state === 'active' ? 'running' : state));
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
  return '<div class="bg-task-row bg-task-row-child" data-bg-row-level="child" data-bg-agentflow="' + escapeHtml(String(agentflowKey || '')) + '"' + bgDepthAttrs(opts.depth) + bgCollapseAttrs(childCount, collapsedNow) + ' role="listitem" tabindex="0" data-child-session="' + escapeHtml(sid) + '">' +
    caretPart +
    '<div class="bg-task-info">' +
      // 三行结构批：members 行同款（L1 名·状态图标·空 time 槽 / L2 kind+重试 / L3 摘要）。
      '<div class="bg-task-line bg-task-main bg-task-row-line1">' +
        namePart +
        bgStatusHtml(state === 'active' ? 'active' : state, { label: statusLabel }) +
      '</div>' +
      '<div class="bg-task-line bg-task-row-line2 bg-task-badges">' + kindPart + retriesPart + '</div>' +
      (taskSummary
        ? '<div class="bg-task-line bg-task-row-line3 bg-task-subline"><span class="bg-task-sub" title="' + escapeHtml(desc) + '">' + escapeHtml(taskSummary) + '</span></div>'
        : '') +
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
 *  @param {{depth?: number, childCount?: number, collapsed?: boolean}} [opts] */
function bgFlowChildHtml(node, delegateKey, agentflowKey, opts = {}) {
  const n = node && typeof node === 'object' ? node : {};
  const label = String(n.label || n.id || '');
  const st = String(n.status || 'pending');
  const wordKey = BG_FLOW_WORD_KEY[st] || '';
  const kindWord = bgMemberKindWord({ info: { kind: String(n.kind || 'flow-step') }, sessionId: String(n.id || '') });
  // flow 投影态 → bgRowStatus 状态键（单表；脏值回落 pending/idle 保守档）。
  const flowState = {
    pending: 'idle', running: 'active', completed: 'done',
    failed: 'error', cancelled: 'done', blocked: 'stuck',
  }[st] || 'idle';
  const deps = Array.isArray(n.deps) ? n.deps.map((d) => String(d || '')).filter(Boolean) : [];
  const depPart = deps.length
    ? '<span class="bg-task-project" title="deps: ' + escapeHtml(deps.join(',')) + '">← ' + escapeHtml(deps.join(',')) + '</span>'
    : '';
  const childCount = Number(opts.childCount) || 0;
  const collapsedNow = !!(childCount > 0 && opts.collapsed);
  const caretPart = childCount > 0 ? bgCaretHtml(String(n.id || ''), collapsedNow) : '';
  return '<div class="bg-task-row bg-task-row-child" data-bg-row-level="child" data-bg-agentflow="' + escapeHtml(String(agentflowKey || '')) + '"' + bgDepthAttrs(opts.depth) + bgCollapseAttrs(childCount, collapsedNow) + ' role="listitem" tabindex="0" data-flow-delegate-key="' + escapeHtml(String(delegateKey || '')) + '" title="' + escapeHtml(label) + '">' +
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

export function renderBgAgentDropdown() {
  if (!activeView) return;
  const dropdownEl = activeView.dom.bgagentDropdownEl;
  const listEl = activeView.dom.bgagentDropdownListEl;
  if (!listEl) return;
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
  // panel-merge-status 批（2026-10-25）：面板含候合并行 ⇒ 事件驱动取一次合并队列
  // 状态面（mergeQueueStatus 单点；模块内节流+退避，本次渲染先以缓存/静态形态落）。
  // 取到新内容 → 面板在开才重渲染（关着不打扰；throttle 使重渲染链自然收敛）。
  if (entries.some(([, info]) => bgRowState(info) === 'waiting')) {
    ensureMergeQueueStatus().then((changed) => {
      const carrier = activeView && activeView.dom.bgDropdownEl;
      if (changed && carrier && !carrier.classList.contains('hidden')) renderBgAgentDropdown();
    }).catch(() => {});
  }
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
    headerEl.textContent = t('subagents.header', { count: model.total }) + digestText;
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
    const rowOpts = { depth, childCount, collapsed };
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
    // 顶层行（未被认领的 leftover / 收敛落点）：孤儿来源角标随行（depth=0——顶层行是
    // 未成树的行，层级语义与既有形态一致）。
    // 野节点归置批（2026-11-05 腿③）：判定式结论（row.wild，由 bgOrphanRowKeys 单点
    // 算出）为真的行**不进正常树面**——它们没有可解析父，混在顶层行里等于假装自己
    // 是「游离但正常」的行；收进独立归置分组（循环后统一出镜，一行不丢）。
    if (row.wild) { wildRows.push(row); continue; }
    htmlParts.push(bgTaskRowHtml(row.id, row.info, row.orphan
      ? { orphanFrom: row.orphan.parentSid, orphanTitle: row.orphan.parentTitle, bucketSid: sid, ...rowOpts }
      : rowOpts));
  }
  // 野节点独立归置分组（组头标签 → 组内行；组头不是行，不入行集与计数）。
  if (wildRows.length) {
    htmlParts.push(bgOrphanGroupHeadHtml(wildRows.length));
    for (const row of wildRows) htmlParts.push(bgOrphanRowHtml(row, sid));
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
      bgCollapseSet(caretKey, !caret.classList.contains('is-collapsed'));
      renderBgAgentDropdown();
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

  // delegate-ui 批（C2/R5）：store 子条目行（不在注册表的终态子）点击 = 同一套详情
  // 承载（bgAgentPopup 历史恢复）；agentflow flow 行点击 = S10 per-task 链视图
  // （flow 节点详情 = 卡面即详情，零新增弹层）。
  const storeChildBySid = new Map();
  for (const g of groups) {
    for (const c of g.children) {
      if (c.type === 'store') storeChildBySid.set(c.sid, c.child);
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
