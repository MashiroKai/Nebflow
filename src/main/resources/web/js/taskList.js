// taskList.js — Collapsible task panel (floats above the input area)
// 任务面板 = 纯 Flow Map 节点视图（作者 2026-09-05 10:54 裁定，taskpanel-audit
// 批落地；推翻 67e69bf1 的「旧任务路径保留」取舍）：
//   - 旧任务区整体退役：taskListUpdate / teamTaskListUpdate 数据路径（渲染层
//     + main.js handler + state 字段 + ws.js 路由表项）、.task-section-progress
//     区块、旧任务行/三级分组、统计中的旧任务计数，全部移除。
//   - 头部统计 = 仅节点数（键复用 task.statsProgress）。
//   - 节点视图机制（2026-09-02 作者裁定 + 2026-09-05 徽章裁定）保留不动：
//     WS nodeCreated/Updated/Completed/Removed 自包含订阅 + REST 快照对齐
//     （_wsTs 防回滚）+ 八态徽章映射 + 点击行 → Flow Map 跳转聚焦。
//   - 主图同源过滤（2026-09-05 12:59 作者裁定 → 链级抽象 P0 单源收口）：可见性
//     = 后端活动区事实本身——整链归档由后端 sweep 出活动区并广播 nodeRemoved
//     （缓存删除）+ 快照对账消化，前端零链派生零判据（旧 deriveArchivedIds/
//     clusterBatches 时间批镜像已删除，判据唯一存在于后端 FlowMapStore）。
import { t } from './i18n.js';
import { createIconsIn, onMinuteTick } from './utils.js';
import state from './state.js';
import { onMessage, onReconnect } from './ws.js';
import { fetchProjects, fetchFlowMap, NODE_STATUS_CLS, controlChain } from './nodeData.js';

// ── Flow Map 节点条目（2026-09-02 作者裁定：节点作为条目并入任务列表）──────
// [节点区块 · 独立命名空间] 数据 = WS 节点广播帧（nodeCreated/Updated/Completed/
// Removed，全局广播 {type, project, nodeId, node}，契约 20260901_project-node-contract
// §2）增量维护本地缓存 + 连接建立时 NodeList 全量快照（GET /api/projects/<n>/flow-map）
// 对齐一次；事件驱动，无轮询。渲染/CSS 类（.task-node-*）/判定条件全部节点自有，
// 与上方 team 区块零交叉引用（v2 解耦，2026-09-02 作者反馈③）。终态节点在 5min
// TTL 窗口内仍显示（与 Flow Map 一致），到期由 nodeRemoved 移除。

/** 节点区块自有判定：Nebula 统一面板才显示节点条目。独立命名空间（不复用
 *  已退役的旧任务域判定）——team/旧任务区退役不影响节点区块。 */
function sessionShowsNodeEntries(sid) {
  if (!sid) return false;
  const agent = state.sessionAgentMap ? state.sessionAgentMap[sid] : null;
  return !agent || agent === 'Nebula';
}

/** project → Map(nodeId → node)。node 附带 _wsTs（本帧落地时刻，防快照回滚）。 */
const nodeCache = new Map();

// ── 链徽标数据源（P1 · spec §7-B ⭐）：节点 payload 的 chainId 条件键 → 快照 chains
//   旁挂 title。**面板侧零链派生**（前端已有 chainEligible 漂移教训，spec §7-B 红线）：
//   只按后端下发的 chainId/memberIds/title 建映射缓存，绝不自行聚簇/判链。
//   缓存生命周期与 nodeCache 同（项目粒度，随快照整体替换）。WS 无链级帧 → 链标题
//   随快照到达；WS 帧携带陌生 chainId（新链首现）时防抖拉一次快照补齐（下方
//   scheduleChainSnapshot），避免「新链徽标永不出现」。
/** @type {Map<string, Map<string, {id: string, title: string, memberIds: string[], status: string, pausedAt: number|null, cancelledAt: number|null}>>} */
const chainCache = new Map();
let nodeSnapshotLoaded = false;
let nodeSnapshotSeq = 0;
/** 最近一次渲染面板的会话：WS 事件到达时按它判断是否重渲（Nebula 统一面板）。 */
let lastPanelSessionId = null;
/** 展开的链 id 集合（面板内折叠态记忆；跨重渲保留，同 chainId 恒同态）。 */
const expandedChains = new Set();
/** In-flight chain-control actions (`project:chainId` → action). Serves double duty:
 *  it blocks duplicate clicks AND is the **single** source of the optimistic state —
 *  `buildChainRow` reads it to pick the badge text, to disable both buttons and to set
 *  `data-chain-pending`. It lives in the render layer rather than in the DOM so that any
 *  re-render (e.g. an unrelated WS frame) *preserves* the in-flight state instead of
 *  silently wiping it (§10.7(b) reconciliation rule ③). Released on landing / failure /
 *  arrival of the authoritative frame. */
const chainPendingAction = new Map();
/** 「未分组」组被手动收起的项目集合（§二.1.5：默认展开；手动收起后跨重渲保留
 *  ——面板每次 WS 事件都整块重绘，不记忆则用户收起的组下一秒又张开）。 */
const ungroupedCollapsed = new Set();

/** 节点时间戳：契约 §4 是 epoch ms 数字；容忍 ISO 字符串（旧帧兼容）。 */
function nodeTs(n) {
  const raw = n && (n.completedAt || n.startedAt || n.createdAt);
  if (typeof raw === 'number') return raw;
  const ms = Date.parse(raw);
  return isNaN(ms) ? 0 : ms;
}

/** 终态且 ttlLeftSec 已到期（≤0）的节点不再显示——与 flowMapTab.visibleNodes 同规则。 */
function nodeIsLive(n) {
  const terminal = n.status === 'completed' || n.status === 'failed' || n.status === 'cancelled';
  if (terminal && Number.isFinite(n.ttlLeftSec) && n.ttlLeftSec <= 0) return false;
  return true;
}

/** 面板节点条目数据：全部项目的活跃节点，最近活动在前。主图同源可见性
 *  （2026-09-05 12:59 作者裁定，P0 单源收口）= 后端活动区事实本身：节点留在
 *  缓存即显示（含已终态、等待后端 sweep 的成员），后端 sweep 整链出库广播
 *  nodeRemoved → 缓存删除即隐藏，快照对账兜底收敛——前端零链派生零判据
 *  （旧 deriveArchivedIds 链资格镜像已删，归档资格判定唯一存在于后端）。
 *  过滤后无节点 = 主图空 = 面板收起（既有空态兜底）。 */
function collectNodes() {
  const out = [];
  for (const [project, byId] of nodeCache) {
    for (const n of byId.values()) {
      if (n && n.id && nodeIsLive(n)) out.push({ node: n, project });
    }
  }
  out.sort((a, b) => nodeTs(b.node) - nodeTs(a.node));
  return out;
}

/** WS 节点事件 → 缓存增量 + 面板重渲。导出供测试直接驱动（与 ws.js 分发等价）。 */
export function applyNodeWsEvent(msg) {
  const project = msg && msg.project;
  if (!project) return;
  const type = String(msg.type || '');
  let byId = nodeCache.get(project);
  if (!byId) { byId = new Map(); nodeCache.set(project, byId); }
  const node = msg.node;
  if (type === 'nodeRemoved') {
    byId.delete(String(msg.nodeId || (node && node.id) || ''));
  } else if (node && node.id) {
    byId.set(node.id, { ...node, _wsTs: Date.now() });
    // 链徽标（spec §7-B）：帧带 chainId 但链标题缓存未知（新链首现）→ 防抖补一次
    // 快照（WS 无链级帧，chainId→title 只能来自快照 chains 旁挂）。
    const cid = node.chainId ? String(node.chainId) : '';
    if (cid && !(chainCache.get(project) || new Map()).has(cid)) scheduleChainSnapshot();
  }
  if (byId.size === 0) nodeCache.delete(project);
  rerenderWithNodes();
}

/** 未知链 id 出现时的防抖快照补齐（多帧并发合并为一次；与 rerenderProjectsTab 同式）。 */
let chainSnapshotTimer = null;
function scheduleChainSnapshot() {
  if (chainSnapshotTimer) return;
  chainSnapshotTimer = setTimeout(() => {
    chainSnapshotTimer = null;
    refreshNodeSnapshot();
  }, 500);
}

function rerenderWithNodes() {
  const sid = lastPanelSessionId;
  if (!sid || !sessionShowsNodeEntries(sid)) return;
  renderTaskList([], undefined, sid); // 首参占位——旧任务入参已退役（纯节点视图）
}

/** NodeList 全量快照对齐（连接建立首渲一次 + 断线重连收敛事件缺口）。逐项目容错：
 *  单项目拉取失败不连累其他项目。防回滚：快照在途期间落地的 WS 增量（_wsTs 晚于
 *  本次刷新起点）比快照新，保留缓存版本——否则旧快照会把已完成的节点滚回 running。 */
export async function refreshNodeSnapshot() {
  const seq = ++nodeSnapshotSeq;
  const fetchStart = Date.now();
  let projects;
  try {
    projects = await fetchProjects();
  } catch (_) { return; }
  const results = await Promise.all((projects || []).map(async (p) => {
    try { return [p.name, await fetchFlowMap(p.name)]; }
    catch (_) { return [p.name, null]; }
  }));
  if (seq !== nodeSnapshotSeq) return; // 已有更新的刷新，丢弃旧结果
  for (const [name, fm] of results) {
    if (!fm) continue; // 网络失败保留旧缓存（对账无依据时不破坏现状）
    const byId = nodeCache.get(name) || new Map();
    const next = new Map();
    for (const n of (fm.nodes || [])) {
      if (!n || !n.id) continue;
      const cached = byId.get(n.id);
      next.set(n.id, (cached && (cached._wsTs || 0) > fetchStart) ? cached : { ...n });
    }
    if (next.size) nodeCache.set(name, next); else nodeCache.delete(name);
    // 链标题缓存（spec §7-B）：快照 chains 旁挂整体替换（同 nodeCache 粒度与生命周期）。
    // 键缺失（旧后端/中间态）→ 保留现缓存（与 ingestNodes 的 undefined 语义一致）。
    if (Array.isArray(fm.chains)) {
      /** @type {Map<string, {id: string, title: string, memberIds: string[], status: string, pausedAt: number|null, cancelledAt: number|null}>} */
      const chains = new Map();
      for (const c of fm.chains) {
        if (!c || !c.id) continue;
        chains.set(String(c.id), {
          id: String(c.id),
          title: String(c.title || ''),
          memberIds: (Array.isArray(c.memberIds) ? c.memberIds : []).map(String),
          // 链控三态条件键（chainview 批，engine-impl 位）：既有五键之外的新增键，
          // 旧后端缺键 ⇒ 归一为 'active' 零值（缺键 = 未受链控，不是错误态）。
          status: normalizeChainStatus(c.status),
          pausedAt: typeof c.pausedAt === 'number' ? c.pausedAt : null,
          cancelledAt: typeof c.cancelledAt === 'number' ? c.cancelledAt : null,
        });
      }
      if (chains.size) chainCache.set(name, chains); else chainCache.delete(name);
    }
  }
  // 项目删除收敛（D5，mem-diag 20260907）：快照项目清单来自 fetchProjects()
  // 全集——不在清单里的项目 = 已删除（后端无 projectRemoved 帧），其缓存条目
  // 随本次对账移除（此前只增不减，删除的项目永久滞留内存）。
  const seenProjects = new Set(results.map(([name]) => name));
  for (const name of Array.from(nodeCache.keys())) {
    if (!seenProjects.has(name)) nodeCache.delete(name);
  }
  for (const name of Array.from(chainCache.keys())) {
    if (!seenProjects.has(name)) chainCache.delete(name);
  }
  rerenderWithNodes();
}

for (const evt of ['nodeCreated', 'nodeUpdated', 'nodeCompleted', 'nodeRemoved']) {
  onMessage(evt, applyNodeWsEvent);
}
// 链控状态广播（chainview 批 · 引擎面 `ProjectActor.chainStateFrame` 单点）：
// {type:'chainState', project, chainId, status, pausedAt, cancelledAt} —— 引擎面每条链控
// 成功腿（含他端发起的动作）发一帧。前端只更新链缓存三态键并重渲（🔴 零派生、零判定；
// 状态权威恒在后端）。帧内无链标题/成员 —— 从既有快照缓存取，未知链忽略（下次快照补齐）。
onMessage('chainState', (msg) => {
  const project = msg && msg.project;
  const cid = msg && msg.chainId ? String(msg.chainId) : '';
  if (!project || !cid) return;
  const byId = chainCache.get(project);
  const meta = byId ? byId.get(cid) : null;
  if (!meta) return; // 未知链（标题/成员皆无）→ 忽略；防抖快照会补齐
  meta.status = normalizeChainStatus(msg.status);
  meta.pausedAt = typeof msg.pausedAt === 'number' ? msg.pausedAt : null;
  meta.cancelledAt = typeof msg.cancelledAt === 'number' ? msg.cancelledAt : null;
  // Reconciliation (§10.7(b)): the frame is the authority — it clears the in-flight
  // optimistic state for this chain **unconditionally**, whether the outcome matches
  // the request or not. Frames for other chains leave other rows' in-flight state alone.
  chainPendingAction.delete(`${project}:${cid}`);
  rerenderWithNodes();
});
onReconnect(() => { if (nodeSnapshotLoaded) refreshNodeSnapshot(); });

// ── §15.7 relative time (任务区) ─────────────────────────────────────────
function formatLastActive(updatedAt) {
  if (!updatedAt) return '';
  const ms = Date.parse(updatedAt);
  if (isNaN(ms)) return '';
  const diff = Date.now() - ms;
  if (diff < 60_000) return t('task.justNow');
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

/** §15.7: 60s interval, only while the panel has visible rows; textContent
 *  direct update, no animation. */
let lastActiveTimer = null;
function refreshLastActive() {
  const container = document.getElementById('task-list');
  if (!container) return;
  const els = /** @type {NodeListOf<HTMLElement>} */ (
    // 纯节点视图（2026-09-05 裁定）：面板行只剩节点行（.task-node-time）
    container.querySelectorAll('.task-node-time'));
  els.forEach((el) => {
    const ts = el.dataset.ts;
    if (ts) el.textContent = formatLastActive(ts);
  });
}
function ensureLastActiveTimer(running) {
  if (running && !lastActiveTimer) {
    // perf-481 A5: was a private `setInterval(60_000)`. Relative timestamps
    // ("3m ago") only need minute granularity, so this refresh shares the
    // page-wide wall-clock minute grid (utils.onMinuteTick) instead of owning a
    // second 1-minute timer whose phase drifts from the project-tab fallback.
    lastActiveTimer = onMinuteTick(() => {
      if (document.hidden) return;
      refreshLastActive();
    });
  } else if (!running && lastActiveTimer) {
    lastActiveTimer();
    lastActiveTimer = null;
  }
}

const REDUCED_MOTION = typeof matchMedia === 'function' &&
  matchMedia('(prefers-reduced-motion: reduce)').matches;

// ── Row builders ────────────────────────────────────────────────────────
// 旧任务行构建器（buildCheck/buildRow：四态 glyph + subject/desc/meta + 状态词 +
// 相对时间）随旧任务区退役整体移除（2026-09-05 10:54 裁定）——面板行只剩
// 节点行 buildNodeRow。

// ── Flow Map 节点行（2026-09-02）：复用任务行设计语言 ────────────────────

// 状态词映射（2026-09-05 作者裁定：wiring/pending→待处理、running→进行中、
// blocked→阻塞、completed→已完成、failed→失败、cancelled→已取消）。
// 复用既有键 running→task.inProgressShort（进行中）、completed→flowmap.done、
// failed→flowmap.fail；新增两键（zh/en 成对）：task.nodePending / task.nodeBlocked。
// 🔴 chainview 批（2026-10-01）就地修一处**先于本批**的悬挂键：`cancelled` 原指
// `flows.status.cancelled`，该键在 zh-CN.js / en.js **均不存在**（`flows.` 域只有
// `flows.cancel`）⇒ `t()` 回落到键名本身，面板渲染出字面量 `flows.status.cancelled`。
// 本批展开态成员行与既有节点行都走本表 ⇒ 改指既有键 `flowmap.st.cancelled`
// （「已取消」，zh/en 成对在册）。零新增键、零新权威，仅去掉一处可见残缺。
const NODE_WORD_KEY = {
  wiring: 'task.nodePending',
  pending: 'task.nodePending',
  running: 'task.inProgressShort',
  blocked: 'task.nodeBlocked',
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
};

/**
 * 节点条目行（v2 简约化，2026-09-02 作者反馈）：节点名 + 状态 glyph + 状态词 +
 * 相对时间 + agent 标注。设计规范：
 *   - glyph 统一 12px 节奏（对齐任务行 pending 小方框，宁小勿大）；
 *     running = 品牌绿（--color-primary）转动圆环，与 completed 静态实心绿点
 *     （--color-success）靠形态+色相双区分。
 *   - 状态词全部 muted（颜色信号由 glyph 独占——减装饰）。
 *   - meta 只标 agent（project 已由分组头承载——信息减法）。
 *   - 未知状态（后续 blocked 等新状态）优雅降级：中性半透明点、无状态词，不崩。
 * 点击行 → 打开该项目 Flow Map 就地视图并高亮节点（动态 import projectTab，
 * 避免模块图静态耦合）。
 */
function buildNodeGlyph(st, cls) {
  const g = document.createElement('span');
  g.setAttribute('aria-hidden', 'true');
  if (st === 'running') {
    g.className = 'task-node-spin';
  } else if (st === 'blocked') {
    // blocked（阻塞）：琥珀实心点（警示态，区别于 failed 终结红——与
    // flowmap.css .fm-node.blocked 的 --amber 描边语义一致）
    g.className = 'task-node-dot task-node-dot-blocked';
  } else if (st === 'completed' || st === 'failed' || st === 'cancelled') {
    g.className = `task-node-dot task-node-dot-${cls}`;
  } else if (st === 'wiring' || st === 'pending') {
    g.className = 'task-node-box';
  } else {
    g.className = 'task-node-dot task-node-dot-neutral'; // 未知状态中性降级
  }
  return g;
}

/** 链控状态归一（缺键/未知值 → 'active'）。合法值域 = 引擎面 `ChainLedger.statusOf`
 *  三态单点（active / paused / cancelled）；前端只读不派生。 @param {any} s @returns {string} */
function normalizeChainStatus(s) {
  const v = String(s || '');
  return v === 'paused' || v === 'cancelled' ? v : 'active';
}
// `_` 前缀标记：本模块内部件（首渲前的 hoisting 引用点，见下）。

// ── 类型五色（chainview 批 §二.1.4 · **单源**）────────────────────────────
// mockup 已批五值。🔴 载荷**无 node type 字段**（后端 `NodePayload.buildNodeJson`
// 无类型键，`agent` 恒 'general' —— 见本批报告「假设」节的现读读数）⇒ 类型由
// **角色 + merge + 链内位置**派生（下方 `nodeTypeOf`），色值**只此一处**定义；
// CSS 侧同名变量（taskList.css `:root --task-node-type-*`）与 JS 常量逐字同值，
// 由本批证据脚本 `assert-type-color-single-source.cjs` 断言两处相等（防漂移）。
// 中文类型名（分发器/实现/验证/落地/诊断设计）只作 aria/title 文案 —— 见 §16 草案键表。
const NODE_TYPE = {
  dispatcher: { key: 'dispatcher', color: '#8a93a5' },
  impl: { key: 'impl', color: '#4a8fd4' },
  verify: { key: 'verify', color: '#e0a03c' },
  sink: { key: 'sink', color: '#9b6fd4' },
  design: { key: 'design', color: '#3aa8a0' },
};

// ── 尺寸常量（§二.1.1/1.2 · 单源）──────────────────────────────────────────
// 折叠链行 28px / 展开节点行 22px / 图标钮 22×20。CSS 侧同名变量逐字同值
// （同上，证据脚本断言两处相等）。返回值用于渲染后像素读数核验（≤30px 预算）。
const ROW_H_CHAIN_COLLAPSED = 28;
const ROW_H_NODE_EXPANDED = 22;
const BTN_W = 22;
const BTN_H = 20;
/** 点图节点圆点直径（折叠链行内联拓扑缩略）。 */
const DOT_R = 3;

// ── Topology / motion constants (this batch · §4.5 — **SINGLE SOURCE**) ──────
// Each value is mirrored verbatim into the taskList.css `:root` token of the same
// name; the evidence script assert-type-color-single-source.cjs (extended in this
// batch) asserts every pair equal, so neither side may drift. 🔴 No scattered
// literals, no second authority.
/** Check-mark stroke length (demo verbatim; ≥ path length at both viewBox scales). */
const CHECK_DASH = 12;
/** Check bounce easing (demo verbatim — overshoot bezier, second control point y=1.6). */
const CHECK_EASE = 'cubic-bezier(.34,1.6,.64,1)';
/** Roll-in duration in ms (demo verbatim). */
const ROLL_MS = 350;
/** Indent step per depth level, px (this batch's own value). */
const NEST_STEP = 8;
/** Indent cap in levels (this batch's own value — §2.2 measured layout budget). */
const NEST_CAP = 3;
/** Overflow threshold: a chain renders at most this many member rows (§6.3). */
const CHAIN_ROW_LIMIT = 100;

/** Completed glyph — circle + check, demo geometry verbatim (viewBox 16, rendered 12px).
 *  Path length ≈10.41 ≤ CHECK_DASH, so the draw animation never shows a partial stroke. */
const CHECK_SVG = '<svg viewBox="0 0 16 16"><circle cx="8" cy="8" r="7"/><path d="M4.5 8.2 L7 10.7 L11.5 5.5"/></svg>';
/** Failed glyph — circle + cross, same geometry language as the check. Each stroke is
 *  ≈5.94 long; the CSS dash length is 6 (≤ real length, so the draw starts hidden). */
const CROSS_SVG = '<svg viewBox="0 0 16 16"><circle cx="8" cy="8" r="7"/><path d="M5.9 5.9 L10.1 10.1 M10.1 5.9 L5.9 10.1"/></svg>';

/** Chain keys (`project:chainId`) present on screen — the first-appearance memory for the
 *  chain row's pop. Same swap discipline as the member-status memory: without it, the pop
 *  would replay on every full repaint (i.e. on every WS frame), which §4.3 forbids. */
let chainSeenPrev = new Set();
let chainSeenNext = new Set();

/** Previous-frame status per member row (`project:nodeId` → status) — the §4.3
 *  transition memory. Lives in the render layer only (never in the DOM / storage:
 *  a refresh must start from an empty memory so already-terminal rows stay silent). */
let memberStatusPrev = new Map();
/** Status memory accumulated by the frame currently being built; swapped in at the
 *  end of redraw. Rows that are not rendered (collapsed chain) simply drop out. */
let memberStatusNext = new Map();

// ── Chain topology (§2.4 / §5.2): edges are rebuilt from the nodes' OWN declared
//    fields (out ∪ deps ∪ in) — `chains[].edges` is not serialised (§5.1 ❌).
//    🔴 This is NOT the retired chain-membership derivation: what is rebuilt is the
//    *edge* (a per-node declaration), not the aggregate membership authority, so the
//    "no front-end chain derivation" red line (§7-B) stays intact (§5.3).
//    Target resolution mirrors the engine single point `OutEdge.resolveTargetId`:
//    id hit first, node-name fallback second, unresolvable ⇒ dropped.
/** @param {any[]} members @returns {{ids: Set<string>, adj: Map<string, Set<string>>}} */
function chainEdgesOf(members) {
  const list = Array.isArray(members) ? members : [];
  const ids = new Set(list.map((m) => String(m.id)));
  const byName = new Map();
  for (const m of list) {
    const nm = String(m.name || '');
    if (nm && !byName.has(nm)) byName.set(nm, String(m.id));
  }
  /** @type {Map<string, Set<string>>} */
  const adj = new Map();
  for (const id of ids) adj.set(id, new Set());
  /** @param {any} raw @returns {string|null} */
  const resolve = (raw) => {
    const s = String(raw == null ? '' : raw).replace(/^chain:/, '');
    if (!s) return null;
    if (ids.has(s)) return s;
    return byName.get(s) || null;
  };
  /** @param {string|null} from @param {string|null} to */
  const link = (from, to) => {
    if (from == null || to == null || from === to) return;
    const set = adj.get(from);
    if (set && !set.has(to)) set.add(to);
  };
  for (const m of list) {
    const self = String(m.id);
    for (const e of (Array.isArray(m.out) ? m.out : [])) {
      if (!e || typeof e !== 'object') continue;
      // loop edges are re-entry back-edges and 'Nebula' is the panel seat, not a member
      if (String(e.mode || '') === 'loop' || String(e.to || '') === 'Nebula') continue;
      link(self, resolve(e.to));
    }
    for (const raw of (Array.isArray(m.in) ? m.in : [])) link(resolve(raw), self);
    for (const raw of (Array.isArray(m.deps) ? m.deps : [])) link(resolve(raw), self);
  }
  return { ids, adj };
}

/** Longest-path depth per member (Kahn sweep — one relaxation round per node, so an
 *  already-deepened node cannot be under-relaxed the way a visited-set walk would).
 *  Members inside an unresolved cycle (no cycle is expected: loop edges are stripped)
 *  fall back to depth 0 instead of being mis-ordered.
 *  @param {{ids: Set<string>, adj: Map<string, Set<string>>}} edges
 *  @returns {Map<string, number>} */
function chainDepthsOf(edges) {
  const indeg = new Map();
  for (const id of edges.ids) indeg.set(id, 0);
  for (const [, set] of edges.adj) for (const to of set) indeg.set(to, (indeg.get(to) || 0) + 1);
  const depth = new Map();
  const queue = [];
  for (const id of edges.ids) {
    if ((indeg.get(id) || 0) === 0) { depth.set(id, 0); queue.push(id); }
  }
  for (let i = 0; i < queue.length; i++) {
    const from = queue[i];
    for (const to of (edges.adj.get(from) || [])) {
      depth.set(to, Math.max(depth.get(to) || 0, (depth.get(from) || 0) + 1));
      indeg.set(to, (indeg.get(to) || 0) - 1);
      if ((indeg.get(to) || 0) === 0) queue.push(to);
    }
  }
  for (const id of edges.ids) if (!depth.has(id)) depth.set(id, 0);
  return depth;
}

/** Node createdAt as epoch ms (contract §4 sends a number; ISO strings tolerated). */
function nodeCreatedAt(n) {
  const raw = n && n.createdAt;
  if (typeof raw === 'number') return raw;
  const ms = Date.parse(raw);
  return isNaN(ms) ? 0 : ms;
}

/** Topological order — the §2.1 deterministic three-key sort (depth ↑, createdAt ↑, id ↑). */
function chainOrderOf(members, depths) {
  const list = Array.isArray(members) ? members : [];
  return [...list].sort((a, b) => {
    const da = depths.get(String(a.id)) || 0;
    const db = depths.get(String(b.id)) || 0;
    if (da !== db) return da - db;
    const ta = nodeCreatedAt(a);
    const tb = nodeCreatedAt(b);
    if (ta !== tb) return ta - tb;
    const ia = String(a.id);
    const ib = String(b.id);
    return ia < ib ? -1 : (ia > ib ? 1 : 0);
  });
}

/** Hierarchical sequence labels ("1", "1.1", "1.1.2") in topological order — these
 *  carry the depth signal once the CSS indent is capped (§2.1/§2.3).
 *  @param {any[]} ordered @param {Map<string, number>} depths @returns {Map<string, string>} */
function chainSeqLabels(ordered, depths) {
  const counters = [];
  const labels = new Map();
  for (const m of ordered) {
    const d = depths.get(String(m.id)) || 0;
    counters.length = d + 1;
    for (let i = 0; i <= d; i++) if (!counters[i]) counters[i] = 0;
    counters[d] += 1;
    labels.set(String(m.id), counters.slice(0, d + 1).join('.'));
  }
  return labels;
}

/**
 * Node-type derivation (the payload carries no node-type field — see the report's
 * assumptions section). Criteria order = priority (first hit wins); every criterion
 * but the last is a payload fact:
 *   1. `merge === true` → sink (merge landing seat)
 *   2. `role === 'verifier'` → verify
 *   3. name carries a diagnosis word-root → design
 *   4. chain entry (`in` and `deps` both empty) AND name carries a dispatch word-root
 *      → dispatcher
 *   5. otherwise → impl
 * 🔴 C2 correction (§1.3-C2): the previous rule "`in` empty ∧ `deps` empty ⇒ dispatcher"
 * equated *chain entry* with *dispatcher seat*. Measured on real chains, 17.5% of the
 * seats it painted as dispatcher are in fact impl/verify seats (a real chain's entry is
 * usually the impl seat itself), so the colour signal lied. The entry condition is kept
 * as a necessary conjunct but is no longer sufficient: it must be paired with an explicit
 * dispatch word-root, and an entry seat without that word-root falls through to impl —
 * the measured majority case. The card states no replacement rule; this reading is
 * evidence-driven and is declared in the report.
 * @param {{role?: string, merge?: boolean, in?: string[], deps?: string[], name?: string}} n
 * @param {string} _project @returns {string} NODE_TYPE 键
 */
function nodeTypeOf(n, _project) {
  const inLen = Array.isArray(n.in) ? n.in.length : 0;
  const depsLen = Array.isArray(n.deps) ? n.deps.length : 0;
  const name = String(n.name || '');
  if (n.merge === true) return 'sink';
  if (String(n.role || '') === 'verifier') return 'verify';
  if (/design|diag|forensic|audit|probe|recon|survey|investigat/i.test(name)) return 'design';
  if (inLen === 0 && depsLen === 0 && /dispatch|triage|intake|orchestrat/i.test(name)) return 'dispatcher';
  return 'impl';
}

/** 五态状态徽标映射（§二.1.3 运行/暂停/完成/失败/取消 ← 节点 status）。 */
const NODE_BADGE_KEY = {
  running: 'task.chain.badge.running',
  paused: 'task.chain.badge.paused',
  completed: 'task.chain.badge.completed',
  failed: 'task.chain.badge.failed',
  cancelled: 'task.chain.badge.cancelled',
};

/**
 * 状态徽标五态（§二.1.3）：链三态（引擎面 `status`）+ 成员聚合态。
 * 判据（**顺序即优先级**，首命中即返回，纯读载荷 + 链态缓存，🔴 零后端复刻）：
 *   取消：链 status==='cancelled' 或存在成员 cancelled
 *   暂停：链 status==='paused'
 *   失败：存在成员 status==='failed'
 *   运行：存在成员 status==='running'
 *   完成：其余（含全 completed；空成员视作完成——链已收敛）
 * @param {{status?: string}} chainMeta @param {any[]} members @returns {string} 五态字面量
 */
function chainBadgeState(chainMeta, members) {
  const status = normalizeChainStatus(chainMeta && chainMeta.status);
  if (status === 'cancelled') return 'cancelled';
  if (status === 'paused') return 'paused';
  const list = Array.isArray(members) ? members : [];
  if (list.some((m) => m && m.status === 'cancelled')) return 'cancelled';
  if (list.some((m) => m && m.status === 'failed')) return 'failed';
  if (list.some((m) => m && m.status === 'running')) return 'running';
  return 'completed';
}

/** 链聚合进度 n/N：N = 成员数，n = 终态成员数（completed/failed/cancelled）。
 *  @param {any[]} members @returns {{done: number, total: number}} */
function chainProgress(members) {
  const list = Array.isArray(members) ? members : [];
  const done = list.filter((m) => m && (m.status === 'completed' || m.status === 'failed' || m.status === 'cancelled')).length;
  return { done, total: list.length };
}

/** 链时刻（§二.1.1 折叠行的「· 时长」）：最早成员 createdAt → 当下（运行中链的
 *  墙钟时长）。格式与 `formatLastActive` 同族（<1min = task.justNow，否则 `${n}m`
 *  字面量——与任务行既有口径一致，零新增文案键）。无可用时刻 → ''（不渲染该段）。 */
function chainElapsed(chainMeta, members) {
  const list = Array.isArray(members) ? members : [];
  const stamps = list.map(nodeTs).filter((v) => v > 0);
  if (stamps.length === 0) return '';
  const start = Math.min(...stamps);
  const end = (normalizeChainStatus(chainMeta && chainMeta.status) === 'active')
    ? Date.now()
    : Math.max(start, ...list.map((m) => nodeTs(m) || 0));
  const mins = Math.max(0, Math.floor((end - start) / 60_000));
  return mins < 1 ? t('task.justNow') : `${mins}m`;
}

/**
 * Inline dotmap (§二.1.1): node topology thumbnail — one dot per member (coloured by type)
 * plus links on real edges.
 *   空心（背景色填充 + 类型色描边）= 未起跑；实心 = 已完成/失败/取消；
 *   实心 + 描边放大 = 运行中（`--running` 类，CSS 侧 stroke-width/尺寸放大）。
 * 宽度封顶溢出省略（CSS `overflow:hidden` + `flex-shrink`）——点图只作缩略，不作判据。
 *
 * 🔴 C1 correction (§1.3-C1 / §2.4): the previous version sorted members by timestamp
 * and drew a link between every *adjacent pair*. On real chains that painted edges
 * which do not exist in the payload (measured 75/123 chains) and ordered the chain by
 * activity rather than by dependency (26/123). The dots now follow the topological
 * order (§2.1), and a link is inserted **only** where a real edge (out ∪ deps ∪ in)
 * connects the two adjacent dots. DOM structure and class names are unchanged.
 * @param {any[]} ordered members already in topological order
 * @param {{adj: Map<string, Set<string>>}} edges @returns {HTMLElement} */
function buildChainDotmap(ordered, edges) {
  const map = document.createElement('span');
  map.className = 'task-chain-map';
  map.setAttribute('aria-hidden', 'true');
  const list = Array.isArray(ordered) ? ordered : [];
  list.forEach((m, i) => {
    if (i > 0) {
      const prevId = String(list[i - 1].id);
      const id = String(m.id);
      const fromSet = edges && edges.adj ? edges.adj.get(prevId) : null;
      if (fromSet && fromSet.has(id)) {
        const link = document.createElement('span');
        link.className = 'task-chain-map-link';
        map.appendChild(link);
      }
    }
    const dot = document.createElement('span');
    const type = nodeTypeOf(m, '');
    dot.className = `task-chain-map-dot t-${type}`;
    const st = String(m.status || 'pending');
    if (st === 'running') dot.classList.add('is-running');
    else if (st === 'completed' || st === 'failed' || st === 'cancelled') dot.classList.add('is-done');
    map.appendChild(dot);
  });
  return map;
}

/** 链控图标钮（22×20 · §二.1.2）：pause/resume 同钮双态 + cancel。lucide 名由调用方给。 */
function buildChainIconBtn(icon, label, cls) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = `task-chain-btn ${cls}`;
  btn.title = label;
  btn.setAttribute('aria-label', label);
  btn.innerHTML = `<i data-lucide="${icon}"></i>`;
  return btn;
}

/** Chain-control failure toast. `modal.js` is imported **dynamically**: it statically
 *  imports sidebar.js, which imports this module — a static import here would close the
 *  documented sidebar↔modal cycle. Same pattern as the other dynamic imports in this file.
 *  If the toast module is unavailable the panel still rolls back (the rollback never
 *  depends on the notification). @param {string} message */
function toastChainControlFailure(message) {
  import('./modal.js')
    .then(({ showToast }) => { if (typeof showToast === 'function') showToast(message, 'error'); })
    .catch(() => {});
}

/** Chain control (the three REST legs + state refresh): calls `controlChain`.
 *  On failure ⇒ **roll back the optimistic state + notify** (the panel never breaks).
 *  On success it does **not** rewrite the cache in place — the state authority is the
 *  engine-side `chainState` frame (the frame refreshes on arrival, same single source as
 *  WS; the REST body is only used to triage errors).
 *
 *  🔴 Optimistic UI (§10.2③ gap / §10.7(b), the freeze-style semantics ordered by the
 *  author on 2026-10-01 21:03): from the instant the user clicks, the authoritative frame
 *  is still one round-trip away. That window previously had **zero** visual feedback on the
 *  chain row (measured: with an injected 800ms round-trip the row showed its old state for
 *  224–1223ms), which is exactly the reported symptom "a running chain does not pause".
 *  The fix mirrors the freeze bar: **set the local state in the same frame** (write
 *  `chainPendingAction` + re-render ⇒ badge becomes "pausing/cancelling" + both buttons
 *  disabled + `data-chain-pending`), while the authority stays with the `chainState` frame
 *  (arrival clears the in-flight state unconditionally). The failure leg (http / network)
 *  falls back to the server state and notifies. Zero engine change, zero new CSS.
 *  @param {string} project @param {string} chainId
 *  @param {'pause'|'resume'|'cancel'} action */
async function runChainAction(project, chainId, action) {
  const key = `${project}:${chainId}`;
  if (chainPendingAction.has(key)) return;
  chainPendingAction.set(key, action);
  rerenderWithNodes(); // same-frame visual state: badge swap + both buttons disabled
  let res;
  try {
    res = await controlChain(project, chainId, action);
  } catch (_) {
    res = { state: 'network' };
  }
  // The optimistic window closes as soon as the request settles — the local state was never
  // rewritten, so re-rendering here shows exactly the engine-side truth, and the
  // authoritative `chainState` frame applies the new state moments later (its own clear is
  // idempotent). Holding the marker past the response would leave both buttons dead on a
  // row whose action already succeeded whenever a frame is lost.
  if (chainPendingAction.get(key) === action) chainPendingAction.delete(key);
  rerenderWithNodes();
  if (res.state === 'ok') return;
  // Failure leg ⇒ rollback is what the render above already produced (server state intact).
  // A 404 carries an actionable engine reason (CHAIN_NOT_FOUND / CHAIN_SINGLE_MEMBER /
  // CHAIN_CANCELLED). This card fixes no locale key for those codes, so the code itself
  // is the only truthful actionable text available; otherwise fall back to a generic line.
  const code = res.state === 'http' && res.data && res.data.error ? String(res.data.error) : '';
  const httpStatus = res.state === 'http' ? res.status : '';
  console.warn(`[tasklist] chain ${action} refused (${res.state}${httpStatus ? ` ${httpStatus}` : ''}): ${code}`);
  toastChainControlFailure(code || t('task.chain.ctrl.failed'));
}

/** 取消 = 不可逆 ⇒ 必须过二次确认（§二.1.6）。确认组件复用既有 `window.__showConfirm`
 *  （与 flowMapTab 的链级取消同范式）；🔴 缺席 ⇒ **什么都不做**（绝不无确认直发不可逆腿）。 */
function confirmChainCancel(project, chainId, title) {
  const label = String(title || chainId);
  if (typeof window.__showConfirm !== 'function') {
    console.warn('[tasklist] chain cancel needs the confirm dialog (window.__showConfirm) — refused');
    return;
  }
  window.__showConfirm(t('task.chain.cancel.title'), t('task.chain.cancel.confirm', { chain: label }), () => {
    runChainAction(project, chainId, 'cancel');
  });
}

/**
 * Collapsed chain row (§二.1.1 · 28px): `▸ name [badge] [dotmap] n/N · elapsed · {n} working ⏸ ✕`.
 * Row click = expand/collapse this chain (`expandedChains` memory); both buttons stopPropagation.
 *
 * Active-position signal #2 (§3.3 item 1): with ≥1 running member the meta appends
 * `{n} working` (`task.chain.activeCount`) — the collapsed row alone answers "how many are
 * running", while the dotmap's `is-running` dots sit at their real layers. 🔴 Count only,
 * never ordered: the front end does not invent a within-chain order (§3.2).
 *
 * In-flight optimistic state (§10.7(b)): while `chainPendingAction` holds this chain the
 * badge shows pausing/cancelling, both buttons are disabled and the row carries
 * `data-chain-pending` — taking precedence over the five-state badge.
 * @param {string} project
 * @param {{id: string, title: string, memberIds: string[], status?: string, pausedAt?: number|null, cancelledAt?: number|null}} chainMeta
 * @param {any[]} members @param {{ordered: any[], edges: any}} view @returns {HTMLElement} */
function buildChainRow(project, chainMeta, members, view) {
  const cid = String(chainMeta.id);
  const expanded = expandedChains.has(cid);
  const badge = chainBadgeState(chainMeta, members);
  const { done, total } = chainProgress(members);
  const title = String(chainMeta.title || cid);
  const pendingAction = chainPendingAction.get(`${project}:${cid}`) || '';
  const list = Array.isArray(members) ? members : [];
  const runningCount = list.filter((m) => m && m.status === 'running').length;

  const row = document.createElement('div');
  row.className = 'task-chain';
  row.dataset.chainKey = `${project}:${cid}`;
  row.dataset.chainId = cid;
  row.dataset.project = project;
  row.dataset.chainState = badge;
  if (pendingAction) {
    row.dataset.chainPending = pendingAction;
    // aria-busy is the in-flight marker consumed by the acceptance probe (§10.9-A-1);
    // no new CSS rides on it (the disabled rule already carries the visual).
    row.setAttribute('aria-busy', 'true');
  }
  row.setAttribute('role', 'button');
  row.setAttribute('aria-expanded', expanded ? 'true' : 'false');

  const twisty = document.createElement('span');
  twisty.className = 'task-chain-twisty' + (expanded ? ' is-open' : '');
  twisty.setAttribute('aria-hidden', 'true');
  twisty.innerHTML = '<i data-lucide="chevron-right"></i>';
  row.appendChild(twisty);

  const name = document.createElement('span');
  name.className = 'task-chain-name';
  name.textContent = title;
  row.appendChild(name);

  const badgeEl = document.createElement('span');
  // In-flight: the optimistic word replaces the five-state badge text but keeps the
  // same `.task-chain-badge.s-*` element and colour token (zero new CSS, §10.8).
  badgeEl.className = `task-chain-badge s-${badge}`;
  badgeEl.textContent = pendingAction
    ? t(pendingAction === 'cancel' ? 'task.chain.cancelling' : 'task.chain.pausing')
    : t(NODE_BADGE_KEY[badge]);
  row.appendChild(badgeEl);

  row.appendChild(buildChainDotmap(view.ordered, view.edges));

  const meta = document.createElement('span');
  meta.className = 'task-chain-meta';
  const elapsed = chainElapsed(chainMeta, members);
  // §3.2: the meta carries `{n} working` for the **concurrent** case (the card's own
  // headline case: 29.3% of real chains run ≥2 seats at once). A uniquely-running chain is
  // already identified by its own row (marker + status word) and, in the collapsed row, by
  // the dotmap's `is-running` dot sitting at its real layer (§3.3) — so no count is appended.
  // This is the §3.2 table's specific wording; §7.1 states the terser "≥1". Declared in the report.
  const count = runningCount >= 2 ? ` · ${t('task.chain.activeCount', { n: String(runningCount) })}` : '';
  meta.textContent = `${done}/${total}${count}${elapsed ? ` · ${elapsed}` : ''}`;
  row.appendChild(meta);

  const actions = document.createElement('span');
  actions.className = 'task-chain-actions';
  const isPaused = badge === 'paused';
  const isCancelled = badge === 'cancelled';
  const pauseBtn = buildChainIconBtn(
    isPaused ? 'play' : 'pause',
    isPaused ? t('task.chain.resume') : t('task.chain.pause'),
    'task-chain-btn-pause',
  );
  // Cancelled chains are inert; an in-flight request locks both buttons (§10.7(a)).
  if (isCancelled || !!pendingAction) pauseBtn.disabled = true;
  pauseBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    runChainAction(project, cid, isPaused ? 'resume' : 'pause');
  });
  actions.appendChild(pauseBtn);
  const cancelBtn = buildChainIconBtn('x', t('task.chain.cancel'), 'task-chain-btn-cancel');
  if (isCancelled || !!pendingAction) cancelBtn.disabled = true;
  cancelBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    confirmChainCancel(project, cid, title);
  });
  actions.appendChild(cancelBtn);
  row.appendChild(actions);

  // A pending cancel would make two contradictory controls look live; §10.7(b) settles
  // that by disabling both, so the row hint stays the five-state wording minus the
  // click affordance while in flight.
  row.title = t('task.chain.rowHint', { chain: title, n: String(total) });
  row.addEventListener('click', (e) => {
    e.stopPropagation();
    if (expandedChains.has(cid)) expandedChains.delete(cid); else expandedChains.add(cid);
    rerenderWithNodes();
  });
  return row;
}

/**
 * Four-state motion mark (§4.2 / §4.3): a 12px glyph beside the member row's status word.
 *   running   = brand-green rotating ring (reuses the existing `task-node-spin-rot` — no
 *               second animation language)
 *   completed = circle + check; the check draws itself then the container bounces in
 *               (`task-chain-draw` + `task-chain-check-pop`)
 *   failed    = circle + red cross, same geometry language and timings (stroke = `--color-error`)
 *   paused / other = no mark at all (the "paused" semantic *is* "stopped", so an animation
 *               would contradict it — §4.2 deliberately adds none)
 *
 * 🔴 Trigger discipline (§4.3, the card's most critical motion rule) — play ⟺ **this frame
 * entered the terminal state from a non-terminal one**:
 *   let prev = this nodeKey's status in the previous frame (undefined on first paint)
 *   play ⟺ (now ∈ {completed, failed}) ∧ prev ≠ undefined ∧ prev ≠ now
 *   · first paint already terminal ⇒ silent (prev === undefined) — prevents "refresh replays
 *     the check"
 *   · same status both frames ⇒ silent (the whole panel is rebuilt every frame, so without
 *     the diff every frame would replay)
 * The memory lives in the render layer (`memberStatusPrev` / `memberStatusNext`) — never in
 * a DOM dataset or localStorage, so a refresh clears it, which is the correct semantics.
 * Under `prefers-reduced-motion` the mark lands in its static final state (no animation
 * class, no memory write) — §4.6, the JS channel.
 * @param {string} st @param {boolean} transition @returns {HTMLElement|null} */
function buildMemberMark(st, transition) {
  if (st === 'running') {
    // Reuses the existing rotating-ring CSS verbatim (no second animation language); the
    // reduced-motion channel stops it via the CSS `animation: none` rule (§4.6).
    const spin = document.createElement('span');
    spin.className = 'task-node-spin task-chain-mark';
    spin.setAttribute('aria-hidden', 'true');
    return spin;
  }
  if (st !== 'completed' && st !== 'failed') return null;
  const mark = document.createElement('span');
  mark.className = 'task-chain-mark' + (st === 'failed' ? ' task-chain-mark-failed' : ' task-chain-mark-done');
  mark.setAttribute('aria-hidden', 'true');
  mark.innerHTML = st === 'failed' ? CROSS_SVG : CHECK_SVG;
  // Reduced motion, an already-terminal first paint and a same-state frame all land in the
  // final state; only a real transition plays the draw (§4.3 / §4.6).
  if (REDUCED_MOTION || !transition) mark.classList.add('task-chain-mark-static');
  return mark;
}

/** Expanded member row (§二.1.2 · 22px): sequence label + type dot + name + status word +
 *  motion mark + elapsed.
 *
 *  §2 layered list: row order = **topological order** (`view.ordered`, keyed
 *  `(depth↑, createdAt↑, id↑)`); the depth travels to CSS via the inline `--nest` property
 *  (`padding-left: calc(16px + min(var(--nest),3) * 8px)`). Once the indent is capped, the
 *  `.task-chain-depth` sequence label ("1" / "1.1" / "1.1.2") keeps carrying the depth signal.
 *  Dot colour = type colour (single source).
 *
 *  🔴 C3 correction (§1.3-C3): rows used to be laid out in the incoming `members` order,
 *  which descends from the panel's most-recent-activity sort — i.e. reverse chronological
 *  and unrelated to the chain's upstream/downstream structure.
 *  @param {any} node @param {string} project
 *  @param {Map<string, number>} depths @param {Map<string, string>} seqs @returns {HTMLElement} */
function buildChainMemberRow(node, project, depths, seqs) {
  const st = String(node.status || 'pending');
  const cls = NODE_STATUS_CLS[st] || '';
  const type = nodeTypeOf(node, project);
  const id = String(node.id);
  const depth = depths.get(id) || 0;
  // §4.3 transition DIFF (single read for this frame): the previous frame's status for this
  // member → undefined on first paint, so an already-terminal row stays silent; equal status
  // across frames is silent too (the panel rebuilds every frame). Recorded into the next
  // frame's memory — skipped under reduced motion, which never animates (§4.6 JS channel).
  const prevStatus = memberStatusPrev.get(`${project}:${id}`);
  const transition = !REDUCED_MOTION && prevStatus !== undefined && prevStatus !== st;
  if (!REDUCED_MOTION) memberStatusNext.set(`${project}:${id}`, st);
  const row = document.createElement('div');
  row.className = 'task-chain-member' + (cls ? ` task-node-${cls}` : '');
  row.dataset.nodeKey = `${project}:${id}`;
  row.dataset.project = project;
  row.dataset.nodeId = id;
  row.style.setProperty('--nest', String(depth));
  row.setAttribute('role', 'button');
  const wordKey = NODE_WORD_KEY[st];
  row.setAttribute('aria-label', `${node.name || id}${wordKey ? ` — ${t(wordKey)}` : ''}`);
  row.title = t('project.openFlowMap', { name: project });

  const seq = document.createElement('span');
  seq.className = 'task-chain-depth';
  seq.setAttribute('aria-hidden', 'true');
  seq.textContent = seqs.get(id) || String(depth + 1);
  row.appendChild(seq);

  const dot = document.createElement('span');
  dot.className = `task-chain-dot t-${type}`;
  dot.setAttribute('aria-hidden', 'true');
  row.appendChild(dot);

  const label = document.createElement('span');
  label.className = 'task-chain-member-name';
  label.textContent = node.name || id;
  row.appendChild(label);

  if (wordKey) {
    // §4.4 three-state switch transition: the status word rolls in only on a real change
    // (first paint and same-state frames stay still). `both` fill, no `forwards` tail, so it
    // never parks an animation on top of the layers that follow (§4.4).
    const word = document.createElement('span');
    word.className = 'task-chain-member-word' + (transition ? ' task-chain-roll-in' : '');
    word.setAttribute('aria-hidden', 'true');
    word.textContent = t(wordKey);
    row.appendChild(word);
  }

  const mark = buildMemberMark(st, transition);
  if (mark) row.appendChild(mark);

  const ms = nodeTs(node);
  if (ms > 0) {
    const time = document.createElement('span');
    time.className = 'task-chain-member-time';
    time.setAttribute('aria-hidden', 'true');
    time.dataset.ts = new Date(ms).toISOString();
    time.textContent = formatLastActive(time.dataset.ts);
    row.appendChild(time);
  }

  row.addEventListener('click', (e) => {
    e.stopPropagation();
    import('./projectTab.js').then(({ openProjectFlowMapAt }) => {
      openProjectFlowMapAt(project, node.id);
    }).catch(() => {});
  });
  return row;
}

/** Chain view model for one chain block: topology edges, per-member depth, the
 *  topological row order and the hierarchical sequence labels. Derived once per block
 *  (§6.2: the depth pass is a pure function measured at 0.1ms for the largest real chain).
 *  @param {any[]} members @returns {{ordered: any[], edges: any, depths: Map<string, number>, seqs: Map<string, string>}} */
function chainViewOf(members) {
  const edges = chainEdgesOf(members);
  const depths = chainDepthsOf(edges);
  const ordered = chainOrderOf(members, depths);
  return { ordered, edges, depths, seqs: chainSeqLabels(ordered, depths) };
}

/** Chain control group (collapsed row + member rows when expanded). `chainMeta.status`
 *  drives the badge / button two-state. Overflow fallback (§6.1/§6.3): with more than
 *  `CHAIN_ROW_LIMIT` members only the first 100 rows are built, plus a
 *  `{n} more not shown` row (`task.chain.overflow`) — the threshold is a **vertical
 *  readability** wall, not a performance one (100 × 22px = 5.4 screens); the largest real
 *  chain is 14 members and the brief's scale is 40, so it never fires in practice. */
function buildChainBlock(project, chainMeta, members) {
  const block = document.createElement('div');
  block.className = 'task-chain-block';
  // §4.3 trigger discipline applies to the chain row's pop too: animate only on first
  // appearance, not on every repaint (the panel is fully rebuilt per WS frame).
  const chainKey = `${project}:${String(chainMeta.id)}`;
  if (!chainSeenPrev.has(chainKey)) block.classList.add('task-chain-enter');
  chainSeenNext.add(chainKey);
  const view = chainViewOf(members);
  block.appendChild(buildChainRow(project, chainMeta, members, view));
  if (expandedChains.has(String(chainMeta.id))) {
    const body = document.createElement('div');
    body.className = 'task-chain-body';
    const shown = view.ordered.slice(0, CHAIN_ROW_LIMIT);
    for (const m of shown) body.appendChild(buildChainMemberRow(m, project, view.depths, view.seqs));
    const hidden = view.ordered.length - shown.length;
    if (hidden > 0) {
      const more = document.createElement('div');
      more.className = 'task-chain-more';
      more.setAttribute('aria-hidden', 'true');
      more.textContent = t('task.chain.overflow', { n: String(hidden) });
      body.appendChild(more);
    }
    block.appendChild(body);
  }
  return block;
}

// ── 链徽标（P1 · spec §7-B ⭐ 任务面板标识）──────────────────────────────
// 行级胶囊：同链同色圆点 + 链短名（title 截断 ≤12 字符）。色 = chainId 稳定散列取
// 6 色循环（同链恒同色、无序入——禁把「链序」做成隐含语义）。数据单源 = 快照 chains
// 旁挂（title 后端三级推导下发）；无 chainId（孤立节点/旧后端）或无标题缓存（新链
// 快照未到）→ 不渲染徽标（零空胶囊，优雅缺席）。面板侧**零链派生**（spec 红线）。
const CHAIN_COLOR_CYCLE = 5; // 与 taskList.css .task-node-chain.c0..c4 一一对应
/** 链短名截断长度（字符；CSS 另有 max-width 省略号兜底）。 */
const CHAIN_NAME_MAX = 12;

/** @param {string} chainId @returns {number} 0..CHAIN_COLOR_CYCLE-1（确定性散列）。 */
function chainColorIndex(chainId) {
  const s = String(chainId);
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return h % CHAIN_COLOR_CYCLE;
}

/** 链短名（超长截断 + 省略号；空 title 回落 id）。 @param {string} title @returns {string} */
function chainShortName(title) {
  const s = String(title || '');
  return s.length > CHAIN_NAME_MAX ? `${s.slice(0, CHAIN_NAME_MAX)}…` : s;
}

/** 行级链徽标元素（无链上下文 → null；点击 = 跳主图并 fit 整链，不冒泡到行点击）。 */
function buildChainBadge(node, project) {
  const cid = node && node.chainId ? String(node.chainId) : '';
  if (!cid) return null;
  const meta = (chainCache.get(project) || new Map()).get(cid);
  if (!meta) return null; // 链标题未知 → 优雅缺席（不臆造名）
  const badge = document.createElement('span');
  badge.className = `task-node-chain c${chainColorIndex(cid)}`;
  badge.dataset.chainId = cid;
  const hint = t('flowmap.chain.badgeHint', { chain: meta.title || cid });
  badge.title = hint;
  badge.setAttribute('role', 'button');
  badge.setAttribute('aria-label', hint);
  const dot = document.createElement('span');
  dot.className = 'task-node-chain-dot';
  dot.setAttribute('aria-hidden', 'true');
  badge.appendChild(dot);
  const name = document.createElement('span');
  name.className = 'task-node-chain-name';
  name.textContent = chainShortName(meta.title || cid);
  badge.appendChild(name);
  badge.addEventListener('click', (e) => {
    e.stopPropagation(); // 徽标 = 链定位（行点击语义是节点定位，二者不叠加）
    import('./projectTab.js').then(({ openProjectFlowMapChain }) => {
      openProjectFlowMapChain(project, cid);
    }).catch(() => {});
  });
  return badge;
}

function buildNodeRow(node, project) {
  const st = String(node.status || 'pending');
  const cls = NODE_STATUS_CLS[st] || ''; // wiring → pending（等待色）；未知 → ''
  const wordKey = NODE_WORD_KEY[st];

  const row = document.createElement('div');
  row.className = 'task-node' + (cls ? ` task-node-${cls}` : '');
  row.dataset.nodeKey = `${project}:${node.id}`;
  row.dataset.project = project;
  row.dataset.nodeId = node.id;
  row.setAttribute('role', 'button');
  row.setAttribute('aria-label',
    `${node.name || node.id}${wordKey ? ` — ${t(wordKey)}` : ''}`);
  row.title = t('project.openFlowMap', { name: project });

  row.appendChild(buildNodeGlyph(st, cls));

  const text = document.createElement('div');
  text.className = 'task-node-text';
  // 首行 = 节点名 + 链徽标（P1 链级抽象 spec §7-B ⭐：徽标紧贴节点名之后）。
  // 包一层 flex 行容器：名保持 ellipsis 收缩，徽标不收缩（徽标略先于名让位）。
  const labelRow = document.createElement('span');
  labelRow.className = 'task-node-label-row';
  const label = document.createElement('span');
  label.className = 'task-node-label';
  label.textContent = node.name || node.id;
  labelRow.appendChild(label);
  const chainBadge = buildChainBadge(node, project);
  if (chainBadge) labelRow.appendChild(chainBadge);
  text.appendChild(labelRow);
  // project 已由分组头承载，meta 只标节点自身信息：description 优先（agent 字段已
  // 退役——新建节点恒 "general" 零信息量，节点行副行显示它等于空信息）；缺失
  // （存量节点）→ 回落 agent 名；两者皆无 → 不渲染（行更矮更净，零空行）。
  // 数据源 = 默认载荷 description（ProjectTypes.scala NodePayload 基础字段，随
  // fetchFlowMap 快照与 WS 节点帧同构到达，零新增取数）。窄栏由 CSS 省略号截断，
  // 悬停全文挂 metaEl.title——row.title 的「打开 Flow Map」语义与 aria-label 不动。
  const desc = typeof node.description === 'string' ? node.description.trim() : '';
  const agent = typeof node.agent === 'string' ? node.agent : '';
  const metaText = desc || agent;
  if (metaText) {
    const metaEl = document.createElement('span');
    metaEl.className = 'task-node-meta';
    metaEl.setAttribute('aria-hidden', 'true');
    metaEl.textContent = metaText;
    if (desc) metaEl.title = desc; // 截断（CSS text-overflow: ellipsis）→ 悬停全文
    text.appendChild(metaEl);
  }
  row.appendChild(text);

  if (wordKey) {
    const wordEl = document.createElement('span');
    wordEl.className = 'task-node-word';
    wordEl.setAttribute('aria-hidden', 'true');
    wordEl.textContent = t(wordKey);
    row.appendChild(wordEl);
  }

  const ms = nodeTs(node);
  if (ms > 0) {
    const time = document.createElement('span');
    time.className = 'task-node-time';
    time.setAttribute('aria-hidden', 'true');
    // ISO 字符串进 dataset.ts——60s ticker 复用 formatLastActive（Date.parse 可解）
    time.dataset.ts = new Date(ms).toISOString();
    time.textContent = formatLastActive(time.dataset.ts);
    row.appendChild(time);
  }

  row.addEventListener('click', (e) => {
    e.stopPropagation();
    import('./projectTab.js').then(({ openProjectFlowMapAt }) => {
      openProjectFlowMapAt(project, node.id);
    }).catch(() => {});
  });
  return row;
}

/** 节点区块分区装配（chainview 批 2026-10-01 · 链视图）。
 *  结构：项目分组 → [链块（折叠行 28px；展开=成员行 22px）…] → 「未分组」折叠组（置底）。
 *  🔴 判据单源：链归属 = 后端下发的 `chainId` + 快照 `chains` 旁挂（前端零派生，spec §7-B
 *  红线）；`chainId` 存在但链标题缓存未到 ⇒ 回落到「未分组」区（**不臆造链名**，
 *  快照到达后自然归位）。
 *  2026-09-06 显示优化批：「Flow Map」分区标题元素整体移除（作者 00:35 裁定；
 *  flowmap.title i18n 键保留——Flow Map 标签页域共用）。无节点返回 null。 */
function buildNodeSection(nodes) {
  if (!nodes || nodes.length === 0) return null;
  const section = document.createElement('div');
  section.className = 'task-section task-section-nodes';
  const byProject = new Map();
  for (const it of nodes) {
    if (!byProject.has(it.project)) byProject.set(it.project, []);
    byProject.get(it.project).push(it);
  }
  for (const [project, items] of byProject) {
    const group = document.createElement('div');
    group.className = 'task-node-group';
    const h = document.createElement('div');
    h.className = 'task-node-group-header';
    h.textContent = project;
    group.appendChild(h);

    // 1) 链块：按链在列表中的首现顺序（列表已按最近活动排序 ⇒ 链序 = 活动序）。
    const chainsById = chainCache.get(project) || new Map();
    const byChain = new Map();
    /** @type {Array<{node: any, project: string}>} */
    const loose = [];
    for (const it of items) {
      const cid = it.node && it.node.chainId ? String(it.node.chainId) : '';
      if (cid && chainsById.has(cid)) {
        if (!byChain.has(cid)) byChain.set(cid, []);
        byChain.get(cid).push(it.node);
      } else {
        loose.push(it);
      }
    }
    for (const [cid, members] of byChain) {
      group.appendChild(buildChainBlock(project, chainsById.get(cid), members));
    }

    // 2) 未分组折叠组（§二.1.5）：无链节点（含 chainId 未知标题者）收拢，**置底**。
    //    默认展开（内核/无链实例的面板内容与改动前保持可见——折叠能力保留，行行可见）。
    if (loose.length > 0) {
      const isCollapsed = ungroupedCollapsed.has(project);
      const ung = document.createElement('div');
      ung.className = 'task-node-ungrouped' + (isCollapsed ? ' is-collapsed' : '');
      const uh = document.createElement('div');
      uh.className = 'task-node-ungrouped-header';
      uh.setAttribute('role', 'button');
      uh.setAttribute('aria-expanded', isCollapsed ? 'false' : 'true');
      const twisty = document.createElement('span');
      // 默认展开 ⇒ twisty 初值即 is-open（与 aria-expanded="true" 同口径）。
      twisty.className = 'task-chain-twisty' + (isCollapsed ? '' : ' is-open');
      twisty.setAttribute('aria-hidden', 'true');
      twisty.innerHTML = '<i data-lucide="chevron-right"></i>';
      uh.appendChild(twisty);
      const uhLabel = document.createElement('span');
      uhLabel.textContent = t('task.chain.ungrouped');
      uh.appendChild(uhLabel);
      uh.addEventListener('click', (e) => {
        e.stopPropagation();
        const collapsed = ung.classList.toggle('is-collapsed');
        uh.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        twisty.classList.toggle('is-open', !collapsed);
        if (collapsed) ungroupedCollapsed.add(project); else ungroupedCollapsed.delete(project);
      });
      ung.appendChild(uh);
      for (const { node } of loose) ung.appendChild(buildNodeRow(node, project));
      group.appendChild(ung);
    }
    section.appendChild(group);
  }
  return section;
}

/** Empty-state builder（兜底保留，2026-09-05 10:54 裁定「空态兜底保留」）：
 *  主路径不接——无节点时面板整体收起（.has-tasks 才展开，与 67e69bf1 行为
 *  一致）。键 task.progressEmpty 保留（zh/en 成对），供后续区块复用。 */
function buildEmpty(label) {
  const empty = document.createElement('div');
  empty.className = 'task-empty';
  empty.setAttribute('aria-hidden', 'true');
  empty.textContent = label;
  return empty;
}

// ── Main render（纯节点视图，2026-09-05 10:54 裁定）─────────────────────

/**
 * 渲染任务面板 = 纯 Flow Map 节点视图。首参为旧任务入参占位（_tasks 忽略，
 * 调用方兼容——旧任务数据路径已退役，无消费方再传真实旧任务）。
 * @param {Array} [_tasks] 旧任务入参——2026-09-05 裁定退役，忽略
 * @param {HTMLElement} [container]
 * @param {string} [sessionId] owning session — gates node visibility
 */
export function renderTaskList(_tasks, container, sessionId) {
  if (!container) container = document.getElementById('task-list');
  if (!container) return;

  lastPanelSessionId = sessionId || null;
  // 2026-09-02: Nebula 统一面板显示 Flow Map 节点条目（成员会话面板不显示）。
  // 首渲全量对齐一次快照（此后事件驱动），补齐页面打开前已在跑的节点。
  const showNodes = sessionShowsNodeEntries(sessionId);
  if (showNodes && !nodeSnapshotLoaded) {
    nodeSnapshotLoaded = true;
    refreshNodeSnapshot();
  }
  const nodes = showNodes ? collectNodes() : [];

  // 纯节点视图：统计/行全节点口径。无节点 → 面板收起（.has-tasks 才展开）。
  container.classList.toggle('has-tasks', nodes.length > 0);
  container.classList.toggle('task-ws-down', !state.connected);
  ensureLastActiveTimer(nodes.length > 0);
  if (nodes.length === 0) {
    container.innerHTML = '';
    chainSeenPrev = new Set();     // nothing on screen ⇒ no "previous frame" to diff against
    memberStatusPrev = new Map();
    return;
  }

  redraw(container, nodes);
}

/** Map nodeKey → 存在性 from the current DOM (entry-animation comparison:
 *  已存在的行不重播入场动画；纯节点视图单区，zone-diff 概念随旧任务区退役)。 */
function collectRowKeys(container) {
  const keys = new Set();
  container.querySelectorAll('.task-node[data-node-key]').forEach((el) => {
    keys.add(el.dataset.nodeKey);
  });
  return keys;
}

function redraw(container, nodes) {
  // §4.3 transition memory: `chainSeenPrev` / `memberStatusPrev` describe the frame that is
  // on screen right now; the builders fill the `*Next` counterparts for the frame being
  // built. The swap happens only after the new frame is mounted, so "previous" always means
  // "rendered" — which is what makes first paint (of an already-terminal row) silent.
  chainSeenNext = new Set();
  memberStatusNext = new Map();
  container.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'task-card' + (container.dataset.collapsed === '1' ? ' collapsed' : '');

  // ── Header: toggle + stats（统计 = 仅节点数，2026-09-05 10:54 裁定；
  //  键复用 task.statsProgress「{progress} 任务」——语义仍成立）──
  const header = document.createElement('div');
  header.className = 'task-header';

  const toggle = document.createElement('button');
  toggle.className = 'task-toggle';
  const collapsed = card.classList.contains('collapsed');
  toggle.title = collapsed ? t('task.expand') : t('task.collapse');
  toggle.innerHTML = `<i data-lucide="${collapsed ? 'chevron-down' : 'chevron-up'}"></i>`;

  const stats = document.createElement('span');
  stats.className = 'task-stats';
  stats.textContent = t('task.statsProgress', { progress: nodes.length });

  header.appendChild(toggle);
  header.appendChild(stats);

  // ── Body: 节点区块（面板唯一内容区）──
  const body = document.createElement('div');
  body.className = 'task-body';
  const inner = document.createElement('div');
  inner.className = 'task-body-inner';

  // 节点区块装配在 buildNodeSection 内全程使用 .task-node-* 命名空间。
  const nodesSection = buildNodeSection(nodes);
  if (nodesSection) inner.appendChild(nodesSection);

  body.appendChild(inner);
  card.appendChild(header);
  card.appendChild(body);
  container.appendChild(card);

  createIconsIn(container);
  chainSeenPrev = chainSeenNext;
  memberStatusPrev = memberStatusNext;

  toggle.addEventListener('click', () => {
    const nowCollapsed = card.classList.toggle('collapsed');
    container.dataset.collapsed = nowCollapsed ? '1' : '';
    toggle.innerHTML = `<i data-lucide="${nowCollapsed ? 'chevron-down' : 'chevron-up'}"></i>`;
    toggle.title = nowCollapsed ? t('task.expand') : t('task.collapse');
    createIconsIn(toggle);
  });

  // Entry animation for brand-new node rows（旧任务行 task-entering 随退役
  // 移除——节点行动画名独立 task-node-entering）。
  if (!REDUCED_MOTION) {
    const existingKeys = collectRowKeys(container);
    const items = /** @type {NodeListOf<HTMLElement>} */ (
      container.querySelectorAll('.task-node'));
    items.forEach((el) => {
      if (existingKeys.has(el.dataset.nodeKey)) return;
      el.classList.add('task-node-entering');
      el.addEventListener('animationend', () => el.classList.remove('task-node-entering'), { once: true });
    });
  }
}
