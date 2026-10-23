// main.js 抽取(FE组件化批次七 2026-09-28):后台代理下拉族 模块(行为保持)。
// USTC 合并移植（2026-10-10）：面板体更新为本地 delegate-ui 两级化批（C2/R6 ·
// 定稿蓝本 §1.2/§2）与「修订批（用户九点②/⑤ · 2026-10-09）」语义——远端拆分时
// 携带的旧快照面板体由本地新版整体替换（SubTask→Delegate 显示词归一、C1 过渡
// 过滤、两级组头/子行、空态自动收起），本文件骨架（indicator/uptime/fetch/stuck
// 族）沿用远端抽取形态。
import state from '../state.js';
import { sendWs } from '../ws.js';
import { t } from '../i18n.js';
import { escapeHtml, isBgAgentId, createIconsIn, statusIcon } from '../utils.js';
import { findViewBySessionId, activeView } from '../chatView.js';
import { fmtUptime, isFailedSnapshotStatus } from '../managePanel.js';
import { openStepPopup as openFlowStepPopup } from '../flowAgentPopup.js';
import { openStepPopup as openBgAgentPopup } from '../bgAgentPopup.js';
// 两级面板数据侧 stub 读取口（delegate-ui 批：rows/children/chain——provider 镜像；
// liveSource 挂载在 delegateChainProvider.js，由 main.js 侧 import 触发）。
import { listDelegateRows, listDelegateChildren, getChainByTask } from '../delegateChainStub.js';

// --- Multi-agent events ---
// Background sub-agent activity shows a header indicator (like Bash background tasks).
// No tool cards or text rendered in the chat — the parent's Delegate tool
// card (spinner → result) is the only chat-level feedback.
// Click the indicator to see a dropdown with per-agent status.

export function updateBgAgentIndicator(targetSid, opts) {
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
  // Author ruling 2026-09-14 16:30: the badge counts RUNNING rows only (an idle
  // dispatcher inside its keep-alive window counts zero). Same predicate as the
  // list (visibleBgAgentEntries) — the two must never disagree (see the view
  // resolution note above: count>0 with an empty dropdown was a real defect).
  const count = visibleBgAgentEntries(bgAgents).length;
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
  if (info.waiting || info.status === 'Waiting') return 'waiting';
  if (info.frozen) return 'frozen';
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

/** 空闲/waiting 行的细分注记（null = 无——行保持既有形态）。 */
function bgIdleDetail(info) {
  const bgWaiting = Array.isArray(info.bgWaiting) ? info.bgWaiting : [];
  const receipts = (Number(info.heldReceipts) || 0) + (Number(info.unreadReceipts) || 0)
    + (Number(info.outstandingSubagents) || 0);
  const parts = [];
  if (bgWaiting.length > 0) {
    const w = bgWaiting[0];
    const dur = w.startedAt ? fmtUptime(Date.now() - w.startedAt) : '';
    parts.push(t('subagents.waitingBg', {
      count: bgWaiting.length,
      desc: String(w.description || w.taskId || ''),
      dur,
    }));
  }
  if (receipts > 0) parts.push(t('subagents.waitingReceipts', { count: receipts }));
  if (parts.length > 0) return { text: parts.join(' · '), stale: false };
  // 长空闲：无在飞工作（①②皆空）且 agent 侧活动戳距今 > 10min。
  const idleMs = Number(info.lastActivityMs) > 0 ? Date.now() - Number(info.lastActivityMs) : 0;
  if (idleMs > BG_LONG_IDLE_MS) {
    return { text: t('subagents.longIdle', { mins: Math.floor(idleMs / 60000) }), stale: true };
  }
  return null;
}

/** Session-id prefix predicate (single point): `dispatcher-` = Project task
 *  dispatcher session. `node-` = Project Flow Map node session (its panel
 *  visibility口径 is deliberately UNCHANGED by this batch), `delegate-` /
 *  `subtask-` = Delegate / SubTask sub-agents. */
function isDispatcherSession(id) {
  return typeof id === 'string' && id.startsWith('dispatcher-');
}

/** C1 过渡呈现纪律（delegate-ui 批 2026-10-08 · 定稿蓝本 §1.2）：`dispatcher-*` /
 *  `node-*` 是 delegate 编排的引擎内部件——过渡期（P0-P3）**在册但不入一等陈列面**：
 *  数据侧 activeAgents 快照保持全量（管理/调试面照读），仅渲染入口过滤此处单点
 *  （计数与列表同走 visibleBgAgentEntries，badge 与面板永不打架）。终态（P4）内部
 *  会话形态删除，过滤自然成空。取代旧「dispatcher 仅 active 才准入」口径
 *  （2026-09-14 16:30 author ruling）——显示侧收得更紧，活性/注册语义零触碰。 */
function isC1InternalOrchestration(id, info) {
  const sid = String((info && info.sessionId) || id || '');
  return isDispatcherSession(sid) || isDispatcherSession(info && info.sessionId) ||
    sid.startsWith('node-');
}

/** Panel admission口径 (author ruling 2026-09-14 16:30, asknb-6d626dd4307a4d31):
 *  「只显示运行中的，计数也只计算运行中的，空闲不显示。」
 *
 *  delegate-ui 批（2026-10-08）更新：`dispatcher-` 与 `node-` 前缀全滤（C1 过渡纪律，
 *  见 isC1InternalOrchestration）；其余前缀沿用既有口径——hidden = not rendered AND
 *  not counted（same bucket, same 口径 as the list, so the badge can never
 *  disagree with the dropdown）。 */
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

/** G1 mode → 组头徽标词（kind 词 = 面板既有数据词，非 locale 文案）。权威 = rows 行内
 *  `orchestrationMode`；subagent-star → Subagent、agentflow → Flow（R6：badge 跟模式
 *  走，项目内 delegate 不再标 Flow）。single/''（旧盘 null）= 过渡启发兜底：快照 kind
 *  优先（external- → External 信息保留），前缀启发（bgAgentKindFromSession）殿后——
 *  单一切换点收敛在该启发函数，P4 后自然消失。 */
function bgDelegateBadgeWord(info, row) {
  const mode = String((row && row.orchestrationMode) || '');
  if (mode === 'subagent-star') return 'Subagent';
  if (mode === 'agentflow') return 'Flow';
  // 修订批九点②补漏：快照 kind（AgentKind.SubTask）同走显示词归一（数据面不动）。
  return bgKindDisplayWord(info && info.kind) ||
    bgAgentKindFromSession((info && info.sessionId) || '') || '';
}

/** Store 子条目状态 → 面板行状态（SubAgentTask 值域 running|completed|failed|restarting；
 *  未知归 active 保守侧——不臆造终态）。 */
function bgStoreChildState(status) {
  if (status === 'completed') return 'done';
  if (status === 'failed') return 'error';
  return 'active';
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
 * @param {Array<[string, any]>} entries visibleBgAgentEntries 产出
 * @returns {{groups: Array<Object>, leftovers: Array<Array<any>>}}
 */
function bgDelegateGroups(entries) {
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
  // Pass 1：组头认领（delegate-* / external-*；被 G3 认领的嵌套 delegate 不作组头）。
  const groups = [];
  const groupByDelegate = new Map();
  const leftovers = [];
  for (const [id, info] of entries) {
    const sid = String((info && info.sessionId) || id || '').replace(/^team-/, '');
    if (!childOwner.has(sid) && (sid.startsWith('delegate-') || sid.startsWith('external-'))) {
      const row = rowByDelegate.get(sid) || null;
      const key = String((row && (row.taskNo || row.delegateId)) || sid);
      const g = { id, info, sid, row, key, children: [] };
      groups.push(g);
      groupByDelegate.set(sid, g);
    } else {
      leftovers.push([id, info]);
    }
  }
  // Pass 2：live 子行认领（父组缺席 = 跨窗/竞态 ⇒ 按 leftover 原样呈现，零丢失）。
  for (const pair of leftovers.slice()) {
    const [id, info] = pair;
    const sid = String((info && info.sessionId) || id || '').replace(/^team-/, '');
    const parent = childOwner.get(sid);
    const g = parent ? groupByDelegate.get(parent) : null;
    if (g) {
      g.children.push({ type: 'live', id, info, sid });
      leftovers.splice(leftovers.indexOf(pair), 1);
    }
  }
  // Pass 3：store 子条目（去重——已在场的 live 子行不重复）+ flow 结构行。
  for (const g of groups) {
    const present = new Set(g.children.map((c) => c.sid));
    for (const c of listDelegateChildren(g.sid)) {
      const cid = String((c && c.taskId) || '');
      if (cid && !present.has(cid)) g.children.push({ type: 'store', child: c, sid: cid });
    }
    const chain = g.key ? getChainByTask(g.key) : null;
    const flowSteps = chain && Array.isArray(chain.nodes)
      ? chain.nodes.filter((n) => n && n.kind === 'flow-step')
      : [];
    for (const n of flowSteps) g.children.push({ type: 'flow', node: n });
  }
  return { groups, leftovers };
}

/**
 * 面板行 HTML（原 renderBgAgentDropdown map 体原样抽出——行渲染语义零改动；新增
 * opts.childLevel/headLevel 两级锚与 opts.badgeWord 徽标覆写两个纯呈现参数）。
 * 显示名契约批（2026-10-22）：delegate 组头行（opts.headLevel）新增
 * opts.rowTitle（DelegateRegistry.Record.title 的 provider 镜像——spawn 落盘、
 * 跨重启存活），主名 = 批次标题/任务描述首行（全生命周期稳定，不再收缩成
 * sessionId）；session id 降为 title tooltip 次要信息。
 * @param {string} id @param {any} info @param {{childLevel?: boolean, headLevel?: boolean, badgeWord?: string, rowTitle?: string}} [opts]
 * @returns {string}
 */
function bgTaskRowHtml(id, info, opts = {}) {
  const rowState = bgRowState(info);
  const terminal = rowState === 'done' || rowState === 'error';
  const statusLabel = t('subagents.status.' + (rowState === 'active' ? 'running' : rowState));
  const dotClass = {
    active: 'bg-status-active', idle: 'bg-status-idle', stuck: 'bg-status-stuck',
    error: 'bg-status-error', frozen: 'bg-status-frozen', done: 'bg-status-done',
    waiting: 'bg-status-waiting',
  }[rowState];
  // 修订批九点②补漏：快照 kind（AgentKind.SubTask）同走显示词归一（数据面不动）。
  const kind = (opts && opts.badgeWord) || bgKindDisplayWord(info.kind) ||
    bgAgentKindFromSession(info.sessionId || id);
  const kindPart = kind ? '<span class="bg-task-kind">' + escapeHtml(kind) + '</span>' : '';
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
  const sid = String((info && info.sessionId) || id || '').replace(/^team-/, '');
  let displayName;
  let nameTooltip;
  if (opts.headLevel) {
    const fromName = (info.name && String(info.name) !== sid) ? String(info.name) : '';
    const fullTitle = String(opts.rowTitle || '').trim() || bgFirstLine(info.task) || fromName;
    displayName = bgFirstLine(fullTitle) || info.name || sid || id;
    nameTooltip = displayName + (sid && sid !== displayName ? ' · ' + sid : '');
  } else {
    displayName = info.name || id;
    nameTooltip = info.task ? displayName + ' · ' + info.task : displayName;
  }
  const namePart = '<span class="bg-task-name" title="' + escapeHtml(nameTooltip) + '">' + escapeHtml(displayName) + '</span>';
  // Uptime (spec §2.2): relative duration while non-terminal; hidden once done.
  const uptimePart = (!terminal && info.startedAt)
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
  const waitingPart = (rowState === 'waiting')
    ? '<span class="bg-task-waiting">' + escapeHtml(t('subagents.status.waitingMerge')) + '</span>'
    : '';
  // #752 空闲细分注记：idle/waiting 行显示「在等什么」（等后台任务名+时长 /
  // 等子代理回执 N 条 / 长空闲提醒徽标）。仅在停靠态出现，active 行零变化。
  const idleDetail = (rowState === 'idle' || rowState === 'waiting') ? bgIdleDetail(info) : null;
  const idlePart = idleDetail
    ? '<span class="bg-task-idle-detail' + (idleDetail.stale ? ' bg-idle-stale' : '') + '" role="' + (idleDetail.stale ? 'alert' : 'note') + '">'
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
  return '<div class="bg-task-row' + (rowState === 'done' ? ' done' : '') + levelCls + '" role="listitem" tabindex="0" ' + clickAttr + '>' +
    '<span class="bg-task-status ' + dotClass + '" aria-hidden="true"></span>' +
    '<div class="bg-task-info">' +
      // Single-line row (2026-09-27 badge-inline batch, author order: task
      // badge joins the uptime line): state / kind / project / name / badge
      // / retries / uptime share ONE line — uptime stays pinned right via
      // .bg-task-uptime margin-left:auto; project / name / badge truncate
      // (CSS ellipsis + the existing title tooltips, subagents.taskBadge
      // family included); fixed chips never shrink. Pure layout: data
      // sources, tooltip keys, dot / kind / state semantics and counting
      // are untouched. Supersedes the old meta+name two-line split for THIS
      // panel only (#bgagent-dropdown); the background-tasks panel
      // (#bg-dropdown) keeps .bg-task-meta / .bg-task-name-line untouched.
      '<div class="bg-task-line bg-task-main">' +
        '<span class="bg-task-state bg-state-' + rowState + '">' +
          (rowState === 'stuck' ? statusIcon('timer-off', 11, 'st-err')
            : rowState === 'error' ? statusIcon('circle-x', 11, 'st-err')
            : '') +
          escapeHtml(statusLabel) + '</span>' +
        kindPart + projectPart + namePart + taskPart + retriesPart + uptimePart +
      '</div>' +
      stuckPart + waitingPart + idlePart + toolPart +
    '</div>' +
  '</div>';
}

/** Store 子条目轻行（不在注册表的终态子——结构可见、状态由 store 条目承载；词复用
 *  面板状态词既有键）。点击 = 与 live 子行同一详情承载（bgAgentPopup 历史恢复）。 */
function bgStoreChildHtml(child) {
  const c = child && typeof child === 'object' ? child : {};
  const sid = String(c.taskId || '');
  const state = bgStoreChildState(String(c.status || ''));
  const statusLabel = t('subagents.status.' + (state === 'active' ? 'running' : state));
  const dotClass = { active: 'bg-status-active', done: 'bg-status-done', error: 'bg-status-error' }[state] || 'bg-status-idle';
  const kindWord = bgChildKindWord(String(c.source || ''));
  const name = String(c.agentName || '') || sid;
  const desc = String(c.description || c.prompt || '');
  const namePart = '<span class="bg-task-name" title="' + escapeHtml(desc ? name + ' · ' + desc : name) + '">' + escapeHtml(name) + '</span>';
  const kindPart = kindWord ? '<span class="bg-task-kind">' + escapeHtml(kindWord) + '</span>' : '';
  const retries = c.retryCount || 0;
  const retriesPart = retries > 0
    ? '<span class="bg-task-retries" title="' + escapeHtml(t('manage.retry')) + ' ×' + retries + '">×' + retries + '</span>'
    : '';
  return '<div class="bg-task-row bg-task-row-child" role="listitem" tabindex="0" data-child-session="' + escapeHtml(sid) + '">' +
    '<span class="bg-task-status ' + dotClass + '" aria-hidden="true"></span>' +
    '<div class="bg-task-info">' +
      '<div class="bg-task-line bg-task-main">' +
        '<span class="bg-task-state bg-state-' + (state === 'active' ? 'running' : state) + '">' + escapeHtml(statusLabel) + '</span>' +
        kindPart + namePart + retriesPart +
      '</div>' +
    '</div>' +
  '</div>';
}

/** agentflow flow 结构行（chain.nodes 的 flow-step 投影；徽标 = 自身 kind AgentFlow）。
 *  点击 = S10 per-task 链视图（flow 节点详情 = 卡面即详情，「零新增弹层」纪律）。 */
function bgFlowChildHtml(node, delegateKey) {
  const n = node && typeof node === 'object' ? node : {};
  const label = String(n.label || n.id || '');
  const st = String(n.status || 'pending');
  const wordKey = BG_FLOW_WORD_KEY[st] || '';
  const dotClass = {
    pending: 'bg-status-idle', running: 'bg-status-active', completed: 'bg-status-done',
    failed: 'bg-status-error', cancelled: 'bg-status-done', blocked: 'bg-status-stuck',
  }[st] || 'bg-status-idle';
  const deps = Array.isArray(n.deps) ? n.deps.map((d) => String(d || '')).filter(Boolean) : [];
  const depPart = deps.length
    ? '<span class="bg-task-project" title="deps: ' + escapeHtml(deps.join(',')) + '">← ' + escapeHtml(deps.join(',')) + '</span>'
    : '';
  return '<div class="bg-task-row bg-task-row-child" role="listitem" tabindex="0" data-flow-delegate-key="' + escapeHtml(String(delegateKey || '')) + '" title="' + escapeHtml(label) + '">' +
    '<span class="bg-task-status ' + dotClass + '" aria-hidden="true"></span>' +
    '<div class="bg-task-info">' +
      '<div class="bg-task-line bg-task-main">' +
        '<span class="bg-task-state">' + escapeHtml(wordKey ? t(wordKey) : st) + '</span>' +
        '<span class="bg-task-kind">AgentFlow</span>' +
        '<span class="bg-task-name">' + escapeHtml(label) + '</span>' +
        depPart +
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
  const bgAgents = (sid && state.sessionBgAgents[sid]) || {};
  // Author ruling 2026-09-14 16:30: an idle dispatcher row is neither listed nor
  // counted (it survives in the keep-alive window and the snapshot reports it —
  // see isBgRowVisible). Header count, empty state and rows all read this one
  // filtered set — 0 visible ⇒ count 0 + empty text, never a self-contradiction.
  const entries = visibleBgAgentEntries(bgAgents);
  // Panel header carries the live count (aria-live polite, spec §5).
  const headerEl = dropdownEl ? dropdownEl.querySelector('.bg-dropdown-header') : null;
  if (headerEl) headerEl.textContent = t('subagents.header', { count: entries.length });
  if (entries.length === 0) {
    // 空态自动收起（修订批九点⑤ · 2026-10-09）：无内容不渲染空态区块——列表留白，
    // 头部计数（·0）即空态真值（原 spec §6 空态文案行停用，键保留）。
    listEl.innerHTML = '';
    return;
  }
  // ── 两级渲染（C2/R6）：delegate 组头（徽标=编排模式）＋缩进一格子行 ──
  const { groups, leftovers } = bgDelegateGroups(entries);
  const htmlParts = [];
  for (const g of groups) {
    htmlParts.push(bgTaskRowHtml(g.id, g.info, {
      headLevel: true,
      badgeWord: bgDelegateBadgeWord(g.info, g.row),
      // 显示名契约批（2026-10-22）：组头注册行标题（DelegateRegistry.Record.title
      // 的 provider 镜像——跨重启存活，存量记录自带）透传为主名第一源。
      rowTitle: (g.row && g.row.title) || '',
    }));
    for (const c of g.children) {
      if (c.type === 'live') htmlParts.push(bgTaskRowHtml(c.id, c.info, { childLevel: true }));
      else if (c.type === 'store') htmlParts.push(bgStoreChildHtml(c.child));
      else htmlParts.push(bgFlowChildHtml(c.node, g.key));
    }
  }
  for (const [id, info] of leftovers) htmlParts.push(bgTaskRowHtml(id, info, {}));
  listEl.innerHTML = htmlParts.join('');
  // ui-status-redesign：statusIcon() 兜底占位（<i data-lucide>）在此升级为真图标
  // （vendor lib 已就绪时上面已是内联 SVG，此调用零替换、幂等）。
  createIconsIn(listEl);

  // Wire click handlers for sub-agent rows
  listEl.querySelectorAll('[data-node-session-id]').forEach(row => {
    row.addEventListener('click', (e) => {
      e.stopPropagation();
      const rawSessionId = row.getAttribute('data-node-session-id');
      const info = bgAgents[row.getAttribute('data-bg-key')];
      if (info && rawSessionId) {
        // Capture the dropdown BEFORE opening the popup — openStepPopup
        // switches activeView to the popup view whose fakeDom has no dropdown.
        // 后台合并批（2026-10-23 ⑥）：行所在面 = 合并载体 #bg-dropdown。
        const dropdown = activeView.dom.bgDropdownEl;
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
        openBgAgentPopup(sid, String(c.agentName || '') || sid, String(c.description || c.prompt || ''));
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
