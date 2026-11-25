// delegateChainProvider.js — delegate 链真 provider（N1 刀3 · #1228 setSource 换源）。
//
// 职责（v3 设计 §9「换的只是 provider」逐字落位）：把 delegateChainStub 的 read port
// （listDelegateRows / getChainByTask——接口形状零改动）换挂 **FlowMap 衍生投影**：
//   - REST 快照：GET /api/chains（行投影：DelegateRegistry 记录 × live 活性提示 ×
//     terminal-verdict 判词行）＋ GET /api/chains/:key（per-task：ChainStore 三行族
//     record + delegate 作用域 FlowMap 节点投影）——端点 = N1 刀3 A8 新读面
//     （PresenceRoutes `/api/chains*`，投影/fail-soft 语义在引擎侧 ChainsReadFace）。
//   - WS 增量镜像：nodeCreated/nodeUpdated/nodeCompleted/nodeRemoved（delegate 作用
//     域链经既有 node* 帧族广播——零新协议帧）到达 = 「有变化」信号 → 防抖刷一次
//     快照（🔴 帧只当触发器，不当数据源——链/行状态权威恒在 REST，前端零派生第二
//     权威）；断线重连（onReconnect）补一次对账刷新。
//   - 适配层：rowFromRegistry / chainFromStoreRecord **原样复用**（stub 既有纯函数，
//     形状不动吸收字段差异——v3 §9 逐字）；本件只加两处薄拼装：
//     ① 行状态：verdict=ready-to-merge 时覆写（v3 §9「ready-to-merge 由 terminal-
//        verdict 判词行供给」——rowFromRegistry 的 reportStatus/live 推导保持原样）；
//     ② 链节点：REST `chain.nodes` 在场（作用域图存在）→ 整组替换 adapter 的单
//        delegate 回退节点（后端已按 ChainNode 形投影，前端零字段映射）；缺席 →
//        adapter 回退形（三行族降级投影，与 stub 期一致）。
//
// 纪律：本件零用户可见文案（无 locale 触碰）；读取口异常一律 fail-soft（镜像保旧值
// /返回 null，绝不崩渲染面）；mirror 首刷前 = 空行集（与 stub 默认空态同观感）。

import { onMessage, onReconnect, setDurableTaskFrameObserver } from './ws.js';
import state from './state.js';
import { observeTaskRevision, taskRevision } from './subagentRevision.js';
import { EVENTS } from './events.js';
import { authHeaders } from './flowHelpers.js';
import {
  setSource,
  setChildrenSource,
  rowFromRegistry,
  chainFromStoreRecord,
} from './delegateChainStub.js';

/** 端点表（与 PresenceRoutes 挂载路径逐字对齐；Router("/api") 前缀）。 */
export const CHAINS_API = {
  // 行面（delegate 视图重设计实施批 2026-10-21 换源）：/api/delegates 列表读面 =
  // /api/chains 行投影同源加宽臂（同 rowsJson 单点：live/verdict/taskStatus 含 G1
  // `merging` 覆写/mergeQueue/mergeQueuePos）+ 行内 `topology` 子过程拓扑载荷。
  // 旧端点 /api/chains 保留（归档窗与既有消费面不动），行集权威自此单缝走本端点。
  rows: () => '/api/delegates',
  chain: (key) => `/api/chains/${encodeURIComponent(String(key || ''))}`,
  // 批量摘要面（重启重连风暴修复批 2026-11-21）：**一次请求**拿到全部链键的内容
  // 摘要（`{v,fingerprint,digests:{key:sha16}}`），取代逐键 /api/chains/<key> 的
  // N+1 重放。`?since=<上次 fingerprint>` 未变 ⇒ `{unchanged:true}`（零增量体）。
  digests: (since) =>
    since ? `/api/chain-digests?since=${encodeURIComponent(String(since))}` : '/api/chain-digests',
  // 批量星型子代理面（手机面板卡顿修复批 2026-11-21 · 根因一）：**一次请求**拿到
  // 多个父的子集，取代逐父 /api/delegates/<id>/children 的 N+1（修前一拍 116+ 条）。
  // 与 `/api/chain-digests` 同一手法。应答 `{v,requested,count,children:{<id>:[…]}}`；
  // 未在册的父**缺席**（缺席 = 无读数，空数组 = 确无子——判据与单父腿 404 同源）。
  childBatch: (ids) =>
    `/api/child-batch?ids=${encodeURIComponent(Array.from(ids).join(','))}`,
};

/** 行键（taskNo ‖ delegateId——ChainStore.keyFor 同口径，与 taskList.delegateRowKey 一致）。 */
function rowKey(r) {
  return String((r && (r.taskNo || r.delegateId)) || '');
}

/** @type {{rows: any[], chains: Map<string, any>, seq: number}} 本地镜像（同步读）。 */
const mirror = { rows: [], chains: new Map(), seq: 0 };
// These are projections and observed durable watermarks, not another task registry.
const scopedTasks = new Map();
const observedTasks = new Map();
const taskRequests = new Map();
const taskListeners = new Set();
let taskAuthEpoch = 0;
/** 每根最近一次已落地快照的强 ETag（ETag-skip 判据；与 scopedTasks 同步清理）。 */
const scopedEtags = new Map();

export function listScopedTasks(rootSessionId) {
  return Array.from((scopedTasks.get(String(rootSessionId || '')) || new Map()).values());
}

export function onDurableTasksChanged(listener) {
  taskListeners.add(listener);
  return () => taskListeners.delete(listener);
}

function notifyTasks(rows) {
  for (const listener of taskListeners) listener(rows);
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(EVENTS.DELEGATE_CHAIN_MIRROR_UPDATED));
  }
}

/** A current authenticated request is the only authority for this root's rows. */
export async function refreshScopedTasks(rootSessionId = state.activeSessionId) {
  const root = String(rootSessionId || '');
  if (!root || state.consoleSuperseded) return;
  if (taskRequests.has(root)) return taskRequests.get(root);
  const epoch = taskAuthEpoch;
  const request = (async () => {
    try {
      const response = await fetch(`/api/subagent-tasks/${encodeURIComponent(root)}`, {
        // 滚动面重验证批（2026-11-18）：改前本腿显式关掉浏览器 HTTP 缓存 ⇒ 每
        // 5s 一拍都全量收整个行集。服务端此腿已发内容 ETag + `private, no-cache`
        // （PresenceRoutes `/subagent-tasks/:id` 走 HttpCache），前端只需允许缓存
        // 参与：`'no-cache'` = **存、但每次先重验证**（有验证器就发 If-None-Match）
        // ——未变 ⇒ 304 零体、`fetch` 自动复用已存体，JS 侧拿到的仍是同一份 JSON；
        // 服务端缺席验证器（旧引擎）⇒ 照旧整取，**零陈旧风险**（绝不引入启发式
        // 新鲜度）。
        headers: authHeaders(), credentials: 'same-origin', cache: 'no-cache',
        signal: AbortSignal.timeout(10000),
      });
      if (response.status === 401 || response.status === 403) {
        taskAuthEpoch++;
        scopedTasks.clear();
        scopedEtags.clear();
        observedTasks.clear();
        notifyTasks([]);
        return;
      }
      if (!response.ok || epoch !== taskAuthEpoch || state.consoleSuperseded) return;
      // CPU 修复批（2026-11-21）：同一根的 ETag 与上一次已落地的一致 ⇒ 载荷内容相同
      // （服务端 ETag = 投影内容的强哈希），零 json() 解析、零逐行 diff、零广播。
      // 只在「已落地且缓存仍在」时跳过，避免清缓存后的空白面被误判为未变。
      const etag = (response.headers && typeof response.headers.get === 'function') ? response.headers.get('ETag') : null;
      if (etag && scopedEtags.get(root) === etag && scopedTasks.has(root)) return;
      const body = await response.json();
      if (epoch !== taskAuthEpoch || state.consoleSuperseded || body.rootSessionId !== root || !Array.isArray(body.tasks)) return;
      const previous = scopedTasks.get(root) || new Map();
      const next = new Map();
      const changed = [];
      for (const raw of body.tasks) {
        if (!raw?.taskId) continue;
        const row = projectChild(raw);
        const old = previous.get(row.taskId);
        const observed = observedTasks.get(row.taskId);
        if (!observeTaskRevision(observed || old, row, true).accept) {
          if (old) next.set(row.taskId, old);
          continue;
        }
        // Compare projected fields, not only revisions: legacy rows have no cursor,
        // and a complete snapshot can fill fields absent from a live invalidation.
        if (old && Object.keys(row).every(key => old[key] === row[key])) {
          next.set(row.taskId, old);
          continue;
        }
        next.set(row.taskId, row);
        observedTasks.set(row.taskId, row);
        changed.push(row);
      }
      // Empty changes alone do not mean no change: an authoritative snapshot can
      // remove rows. Keep those invalidations while silencing unchanged polls.
      if (!changed.length && next.size === previous.size) return;
      scopedTasks.set(root, next);
      if (etag) scopedEtags.set(root, etag); else scopedEtags.delete(root);
      notifyTasks(changed);
    } catch (_) { /* Keep the last good projection on transient failure. */ }
  })();
  taskRequests.set(root, request);
  try { await request; } finally { taskRequests.delete(root); }
}

setDurableTaskFrameObserver((msg) => {
  if (!msg?.taskId || !msg.runId || taskRevision(msg) === null) return false;
  const previous = observedTasks.get(msg.taskId);
  const decision = observeTaskRevision(previous, msg);
  if (decision.accept) {
    observedTasks.set(msg.taskId, msg);
    notifyTasks([{ ...msg, historyInvalidated: true }]);
  }
  // Even duplicate frames can announce a task that is not in the latest snapshot.
  refreshScopedTasks(msg.rootSessionId || state.activeSessionId);
  // Only task transcript/lifecycle events switch to committed history rendering.
  return /^(agent|text|thinking|tool|roundComplete|done)/.test(String(msg.type || ''));
});

/** 已请求过、确认查无链的键（防 miss 键反复打点；行集刷新时清空重探）。 */
const emptyKeys = new Set();
/** 星型子代理镜像（G3；delegateId → children[]）。refresh 臂随行集刷新逐父拉取；
 *  fail-soft：单父失败保留旧值（与链镜像同纪律），首刷前 = 空集。 */
const childrenMirror = new Map();
/** 子镜像取回时刻（节点子智能体可见性批 2026-11-07：ensureChildren 的 60s TTL 判据）。 */
const childrenFetchedAt = new Map();
/** 在飞父（防同一父并发打点；同 refreshChildren 的单飞闸门）。 */
const childrenInflight = new Set();
/** 链视图取回时刻（重启重连风暴修复批：活跃行的最小重取间隔 + 轮转顺序判据）。 */
const chainFetchedAt = new Map();
/** 链摘要镜像（key → 内容摘要 16 位）：**变更检测器**，不是读面权威——摘要变了
 *  只意味着「取一次详情」（读面恒为 /api/chains/<key>）。 */
const chainDigests = new Map();
/** 上一次摘要面的 fingerprint（`?since=` 零增量判据；空串 = 首次全量）。 */
let digestsFingerprint = '';
// Only the batch endpoint can establish that a key has a persisted chain.
let digestsReady = false;
/** 按需补拉队列（行键）与出队定时器：渲染面真用到某键时才排队，逐条、有间隔。 */
const lazyQueue = [];
const lazyQueued = new Set();
let lazyTimer = null;

// ── 取数闸（重启重连风暴修复批 2026-11-21）─────────────────────────────────
// 事故（作者手机端 01:38 实测 /tmp/nebflow-sbt-restart.log）：重连时对**每个行键**
// 各发一次 GET /api/chains/<key>（N+1 重放；生产盘 1108 行 ⇒ 单次重连即上千并发），
// tunnel guard 按 tunnel-per-ip-cap 整桶 429（一窗 691 条），面板永远加载不齐、
// 视图反复重建 ⇒ 加载慢 + 操作卡顿。本段是**唯一的请求咽喉**，四条纪律：
//   ① 单飞：同一时刻只有一轮刷新在飞，期间的新触发并成「再来一轮」（不叠加）；
//   ② 并发上限：详情腿与子代理腿共用同一个信号量（MAX_INFLIGHT=4）；
//   ③ 429 ⇒ 按**端点类**（不是逐 URL：逐 URL 的表会随键数涨）指数退避 + **抖动**，
//      尊重 Retry-After，窗口内零重试（禁紧循环）；
//   ④ 一轮内一旦撞 429，本轮后续详情请求直接放弃（让位给下一轮退避窗）。
const MAX_INFLIGHT = 4;
/** 一轮刷新最多发多少条详情/子代理请求（余量交给下一轮 + 按需补拉队列）。 */
const DETAIL_BUDGET = 24;
const CHILD_BUDGET = 16;
/**
 * 批量子代理腿单请求父数上限（手机面板卡顿修复批 2026-11-21 · 根因一）：
 * 服务端 `/api/child-batch` 的上限是 128，这里取 96 留余量（URL 长度也受控）。
 * 超过则内部分批串行发——**绝不**退化成逐父请求。
 */
const CHILD_BATCH_MAX = 96;
/** 活跃行链视图的最小重取间隔（活跃行的节点状态要新，但不必每帧都取）。 */
const DETAIL_TTL_MS = 5000;
/** 按需补拉的起始间隔（渲染面逐个真用到时才排队，绝不整批打点）。 */
const LAZY_GAP_MS = 50;
const MAX_BACKOFF_MS = 60000;
/** 任务行「活跃」值域（与 taskList.js:130 ACTIVE_ROW_STATUSES 同集：面板行渲染
 *  只需要这些行的链视图；终态行的链视图按需补拉）。 */
const ACTIVE_STATUSES = new Set(['pending', 'running', 'ready-to-merge', 'interrupted']);
/** 退避桶（类 → {attempts, until}）。 */
const backoff = new Map();
const RATE_LIMITED = Symbol('rate-limited');
/** 本轮刷新已撞 429（门只拦详情/子代理腿——行面与摘要面各有自己的桶）。 */
let stormOpen = false;

function backoffClassOf(url) {
  if (url.startsWith('/api/chain-digests')) return 'digests';
  if (url.startsWith('/api/child-batch')) return 'children';
  if (url === '/api/delegates' || url === '/api/chains') return 'rows';
  if (url.startsWith('/api/chains/')) return 'chain';
  return 'children';
}

/** 429 记账：类内指数退避（500ms 起、上限 60s）+ ±25% 抖动，serverDelay 下限。 */
function noteRateLimited(cls, retryAfterHeader) {
  const prev = backoff.get(cls);
  const attempts = ((prev && prev.attempts) || 0) + 1;
  const value = retryAfterHeader === null || retryAfterHeader === undefined ? NaN : Number(retryAfterHeader);
  const serverDelay = Number.isFinite(value) ? Math.max(0, value * 1000) : 0;
  const exp = Math.min(MAX_BACKOFF_MS, 500 * 2 ** Math.min(attempts - 1, 7));
  // 抖动：多端同时被限流时不会在同一毫秒一起醒来（正是紧循环放大的那一步）。
  const jittered = exp * (0.75 + Math.random() * 0.5);
  const delay = Math.max(serverDelay, Math.min(MAX_BACKOFF_MS, jittered));
  const until = Math.max((prev && prev.until) || 0, Date.now() + delay);
  backoff.set(cls, { attempts, until });
}

/**
 * 取 JSON（全模块唯一出口）。返回 JSON | null（404/5xx/网络错，按「无读数」降级）
 * | [[RATE_LIMITED]]（桶内退避中或本轮已撞 429，**未发请求**）。
 */
async function fetchJson(url) {
  const cls = backoffClassOf(url);
  const gate = backoff.get(cls);
  if (gate && Date.now() < gate.until) return RATE_LIMITED;
  if (stormOpen && (cls === 'chain' || cls === 'children')) return RATE_LIMITED;
  let r;
  try {
    r = await fetch(url, { headers: authHeaders() });
  } catch (_) {
    return null; // 网络抖动不是崩面——调用方保旧值
  }
  if (r.status === 429) {
    noteRateLimited(cls, r.headers && typeof r.headers.get === 'function' ? r.headers.get('Retry-After') : null);
    // 只有详情/子代理腿撞 429 才收本轮的门：行面与摘要面各有自己的桶，不该互相
    // 连坐（摘要面 429 时，活跃行的详情刷新照样该继续）。
    if (cls === 'chain' || cls === 'children') stormOpen = true;
    return RATE_LIMITED;
  }
  if (r.ok) backoff.delete(cls);
  if (!r.ok) return null; // 404/5xx 都不是崩面——调用方按「无读数」降级
  try {
    return await r.json();
  } catch (_) {
    return null;
  }
}

/** 并发信号量（详情/子代理腿共用；MAX_INFLIGHT 就是「绝不一次性放 N 个」）。 */
let inflightSlots = 0;
const slotWaiters = [];
async function withSlot(fn) {
  if (inflightSlots >= MAX_INFLIGHT) await new Promise((resolve) => slotWaiters.push(resolve));
  inflightSlots++;
  try {
    return await fn();
  } finally {
    inflightSlots--;
    const next = slotWaiters.shift();
    if (next) next();
  }
}

function uniq(list) {
  return Array.from(new Set(list));
}


/** 行投影：record×live×verdict → rowFromRegistry（原样）→ ready-to-merge 覆写单点
 *  → 全景载荷加宽（delegate 视图重设计实施批 2026-10-21）：全景视图需要的原始键
 *  原样随行透传（schema 加宽、缺键容忍零臆造——stub 契约「只加宽」纪律）：
 *    `taskStatus`（含 G1 `merging` 一等态——行 status 语义不折叠：任务列表继续按
 *    active 渲染，全景视图按 mergePhase 分泳道）、`branch`/`baseBranch`/`worktree`/
 *    `projectWorkspace`（泳道 chip 与汇点 base 分支）、`reportStatus`/`reportDetail`
 *    （报告条）、`verdict`（判词行原文：{verdict,reason,source,at}）、`topology`
 *    （子过程拓扑：{mode,children[],steps[]}；single 行缺键）。
 *  🔴 前端零派生：merging 判据 = 引擎 taskStatus 原值，本地不复算 holder。 */
function projectRow(entry) {
  const src = entry && typeof entry === 'object' ? entry : {};
  const row = rowFromRegistry(src, src.live);
  const v = src.verdict;
  const out = {
    ...row,
    settled: src.settled === true,
    taskStatus: String(src.taskStatus || ''),
    // Ownership edge (Record.parentSessionId — spawn-persisted, survives
    // restarts): carried as-is so hierarchy faces can resolve a registered
    // parent without live frames. Absent key → '' (never guessed).
    parentSessionId: String(src.parentSessionId || ''),
    branch: String(src.branch || ''),
    baseBranch: String(src.baseBranch || ''),
    worktreePath: String(src.worktree || ''),
    projectWorkspace: String(src.projectWorkspace || ''),
    reportStatus: String(src.reportStatus || ''),
    reportDetail: String(src.reportDetail || ''),
    topology: normalizeTopology(src.topology),
    // 重启对账批 2026-10-22：打断旗 + 接续指针透传（缺键容忍，零臆造）。
    restartInterruptedAt: typeof src.restartInterruptedAt === 'number' && src.restartInterruptedAt > 0
      ? src.restartInterruptedAt
      : null,
    continuedBy: String(src.continuedBy || ''),
  };
  if (v && typeof v === 'object' && String(v.verdict || '') === 'ready-to-merge') {
    out.status = 'ready-to-merge';
  }
  (/** @type {{verdict?: unknown}} */ (out)).verdict =
    v && typeof v === 'object'
      ? {
          verdict: String(v.verdict || ''),
          reason: String(v.reason || ''),
          source: String(v.source || ''),
          at: typeof v.at === 'number' ? v.at : 0,
        }
      : null;
  return out;
}

/** 子过程拓扑归一（缺键/坏形 = null——全景按「无子过程」渲染，零臆造）。 */
function normalizeTopology(t) {
  if (!t || typeof t !== 'object' || Array.isArray(t)) return null;
  const kids = Array.isArray(t.children) ? t.children : [];
  const steps = Array.isArray(t.steps) ? t.steps : [];
  return {
    mode: String(t.mode || ''),
    children: kids.map((c) => ({
      taskId: String((c && c.taskId) || ''),
      agentName: String((c && c.agentName) || ''),
      description: String((c && c.description) || ''),
      status: String((c && c.status) || ''),
      spawnedAt: c && typeof c.spawnedAt === 'number' ? c.spawnedAt : 0,
      completedAt: c && typeof c.completedAt === 'number' ? c.completedAt : null,
    })),
    steps: steps.map((s) => ({
      id: String((s && s.id) || ''),
      label: String((s && s.label) || ''),
      status: String((s && s.status) || ''),
      deps: s && Array.isArray(s.deps) ? s.deps.map((d) => String(d || '')) : [],
    })),
  };
}

/** 链视图投影：三行族经 chainFromStoreRecord（原样）+ 作用域 FlowMap 节点整组替换。 */
function projectChain(body) {
  const b = body && typeof body === 'object' ? body : {};
  const view = chainFromStoreRecord(b.record);
  const chain = b.chain && typeof b.chain === 'object' ? b.chain : null;
  const nodes = Array.isArray(chain && chain.nodes) ? chain.nodes.filter((n) => n && n.id) : [];
  if (nodes.length) return { ...view, nodes };
  return view;
}

/** 拉某键链视图入镜像（fail-soft：任何失败不动现值）。返回「镜像是否真的变了」
 *  —— 广播判据（零增量轮次不重渲面板，见 [[refresh]] 尾注）。 */
async function refreshChain(key) {
  const body = await fetchJson(CHAINS_API.chain(key));
  if (body === RATE_LIMITED) return false; // 退避窗内：**不记时刻**（下一轮照常到期）
  chainFetchedAt.set(key, Date.now());
  const had = mirror.chains.has(key);
  const prev = mirror.chains.get(key);
  if (!body) {
    // 404 = 查无记录：镜像置 null（视图走 fail-soft 重试态），记空键防打点。
    mirror.chains.set(key, null);
    emptyKeys.add(key);
    return !had || prev !== null;
  }
  emptyKeys.delete(key);
  const next = projectChain(body);
  mirror.chains.set(key, next);
  return !had || JSON.stringify(prev) !== JSON.stringify(next);
}

/**
 * 批量摘要（一次请求）：返回**内容变了的键集**。摘要面不可用（旧引擎 404 / 429 /
 * 坏形）⇒ 保留最后一次有效摘要；尚无有效摘要时不猜测链是否存在。
 */
async function refreshDigests() {
  const body = await fetchJson(CHAINS_API.digests(digestsFingerprint));
  if (body === RATE_LIMITED || !body || typeof body !== 'object') return new Set();
  const fingerprint = String(body.fingerprint || '');
  if (body.unchanged === true) {
    if (fingerprint) digestsFingerprint = fingerprint;
    return new Set();
  }
  const map = body.digests && typeof body.digests === 'object' && !Array.isArray(body.digests) ? body.digests : null;
  if (!map) return new Set();
  digestsReady = true;
  const changed = new Set();
  for (const [k, d] of Object.entries(map)) {
    const next = String(d || '');
    if (chainDigests.get(k) !== next) changed.add(k);
    chainDigests.set(k, next);
  }
  // 盘面上已消失的键从摘要镜像里剪掉（键集有界：不留无主条目）。
  for (const k of Array.from(chainDigests.keys())) {
    if (!Object.prototype.hasOwnProperty.call(map, k)) chainDigests.delete(k);
  }
  if (fingerprint) digestsFingerprint = fingerprint;
  return changed;
}

/** 按需补拉出队（单飞 + LAZY_GAP_MS 间隔：渲染面逐个真用到时才补，绝不成批打点）。 */
function drainLazy() {
  if (lazyTimer) return;
  const key = lazyQueue.shift();
  if (key === undefined) return;
  lazyQueued.delete(key);
  lazyTimer = setTimeout(() => {
    lazyTimer = null;
    if (!key || !digestsReady || !chainDigests.has(key) || emptyKeys.has(key) || mirror.chains.has(key)) {
      drainLazy();
      return;
    }
    withSlot(() => refreshChain(key).catch(() => false)).then((ok) => {
      if (ok) dispatchMirrorUpdated();
      drainLazy();
    });
  }, LAZY_GAP_MS);
}

function enqueueLazy(key) {
  if (!key || emptyKeys.has(key) || lazyQueued.has(key)) return;
  lazyQueued.add(key);
  lazyQueue.push(key);
  drainLazy();
}

function dispatchMirrorUpdated() {
  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
    window.dispatchEvent(new CustomEvent(EVENTS.DELEGATE_CHAIN_MIRROR_UPDATED));
  }
}

/** 星型子代理条目归一（G3 children[]；缺键容忍，零臆造——形状源 = SubAgentTask）。 */
function projectChild(c) {
  const src = c && typeof c === 'object' ? c : {};
  return {
    taskId: String(src.taskId || ''),
    parentSessionId: String(src.parentSessionId || ''),
    agentName: String(src.agentName || ''),
    prompt: String(src.prompt || ''),
    description: String(src.description || ''),
    status: String(src.status || ''),
    retryCount: typeof src.retryCount === 'number' ? src.retryCount : 0,
    spawnedAt: typeof src.spawnedAt === 'number' ? src.spawnedAt : 0,
    completedAt: typeof src.completedAt === 'number' ? src.completedAt : null,
    lastError: String(src.lastError || ''),
    source: String(src.source || ''),
    rootSessionId: String(src.rootSessionId || ''),
    committedRevision: taskRevision(src),
    runId: String(src.runId || ''),
    executor: String(src.executor || ''),
    executionStatus: String(src.executionStatus || ''),
    activityAt: typeof src.activityAt === 'number' ? src.activityAt : 0,
    activityKind: String(src.activityKind || ''),
    activityLabel: String(src.activityLabel || ''),
    resultRef: String(src.resultRef || ''),
    incomplete: src.incomplete === true,
    terminalReason: String(src.terminalReason || ''),
    // Parked child reports surface through the existing row diagnostic line.
    diagnostic: String(src.diagnostic || ''),
  };
}

/**
 * 批量拉多个父的星型子代理入镜像（手机面板卡顿修复批 2026-11-21 · 根因一）。
 *
 * 修前渲染面逐父调 [[ensureChildren]] ⇒ 逐父一条 `/children`（一拍 116+ 条），
 * 这是诊断批实测的「5s 轮询风暴」放大器。本函数把它压成**一次** `/api/child-batch`。
 *
 * 契约与单父腿**逐条对齐**（渲染面零改动即可受益：读口仍是 listDelegateChildren）：
 *   · 失败/限流 ⇒ **不写**任何父的镜像（保旧值，fail-soft 纪律）；
 *   · 应答里**缺席**的父 ⇒ 视为「无读数」：不写、不记时刻（下一轮重试）；
 *   · 应答里在场但空数组 ⇒ 「确无子」：写空集并记时刻（不是每次都问）；
 *   · server 侧单请求上限 128 ⇒ 内部按 [[CHILD_BATCH_MAX]] 自动分批。
 * 返回值 = 是否真的写入了新读数（广播判据：零增量的轮次不该触发面板重渲）。
 *
 * @param {Iterable<string>} ids 父会话 id 集（delegate / node / dispatcher 皆可）
 * @returns {Promise<boolean>}
 */
async function refreshChildrenBatch(ids) {
  const keys = uniq(Array.from(ids).map((id) => String(id == null ? '' : id).replace(/^team-/, '')))
    .filter((k) => k && !childrenInflight.has(k));
  if (!keys.length) return false;
  const want = new Set(keys);
  keys.forEach((k) => childrenInflight.add(k));
  let wrote = false;
  try {
    for (let i = 0; i < keys.length; i += CHILD_BATCH_MAX) {
      const chunk = keys.slice(i, i + CHILD_BATCH_MAX);
      const body = await fetchJson(CHAINS_API.childBatch(chunk));
      if (body === RATE_LIMITED || !body || typeof body !== 'object') continue;
      const map = body.children && typeof body.children === 'object' ? body.children : {};
      const now = Date.now();
      for (const key of chunk) {
        // Absent key = "no reading for this parent" — keep the old value, retry later.
        if (!Object.prototype.hasOwnProperty.call(map, key)) continue;
        const raw = map[key];
        const kids = Array.isArray(raw) ? raw.map(projectChild) : [];
        const prev = childrenMirror.get(key);
        const changed =
          !prev || prev.length !== kids.length || kids.some((k, idx) => {
            const o = prev[idx];
            return !o || o.taskId !== k.taskId || o.status !== k.status ||
              o.completedAt !== k.completedAt || o.committedRevision !== k.committedRevision;
          });
        childrenMirror.set(key, kids);
        childrenFetchedAt.set(key, now);
        if (changed) wrote = true;
      }
    }
  } catch (_) {
    /* fail-soft: keep last good mirror; the next round retries */
  } finally {
    keys.forEach((k) => childrenInflight.delete(k));
    if (wrote && typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
      window.dispatchEvent(new CustomEvent(EVENTS.DELEGATE_CHAIN_MIRROR_UPDATED));
    }
    // Parents the server did not return must be retried: they are exactly the
    // `ensureChildren` queue the render face will fill again next pass.
    void want;
  }
  return wrote;
}

/**
 * 按需补拉某父的星型子代理（节点子智能体可见性批 2026-11-07 · 作者报障「node 的子
 * 智能体没显示」）：refresh 臂只逐 **delegate 行** 拉子集，而子代理的父也可以是
 * Project 的 node-/dispatcher- 会话（引擎侧 SubAgentTaskStore 按父会话 id 分区，见
 * <dataRoot>/subagent-tasks/node-*.json）⇒ 这些父此前永远不进镜像，终态子智能体在
 * 面板上零读数。本函数是**唯一补拉口**（渲染面仍只读 listDelegateChildren 那一个读口）：
 *
 *   · TTL 闸门：同一父 ttlMs 内不重复请求（默认 60s；空集也记时刻——「确实没有子」
 *     不该每帧打点）；
 *   · 在飞闸门：同父并发请求只发一次；
 *   · 到达即广播 delegate-chain-mirror-updated（帧只当触发器，与 refresh 同园口径）。
 * fail-soft：任何失败都不抛（渲染面下一帧再试，不影响行集）。
 * @param {string} id 父会话 id（delegate / subtask / subagent / node / dispatcher 皆可）
 * @param {number} [ttlMs] @returns {Promise<void>}
 */
export function ensureChildren(id, ttlMs = 60000) {
  const key = String(id == null ? '' : id).replace(/^team-/, '');
  if (!key) return Promise.resolve();
  const at = childrenFetchedAt.get(key) || 0;
  if (at && (Date.now() - at) < ttlMs) return Promise.resolve();
  if (childrenInflight.has(key)) return Promise.resolve();
  // 🔴 手机面板卡顿修复批（2026-11-21 · 根因一）：渲染面每渲一轮都会对**每个父**
  // 调本函数（`bgDelegateGroups` Pass 3 的递归遍历 + `bgScopeStoreChildren`）——
  // 修前每个父各自 `fetch` 一条 `/children`，一拍 116+ 条，正是诊断批实测的
  // 「5s 轮询风暴」。现在只**排队**，由 [[CHILD_ENSURE_GAP_MS]] 后的**一次**
  // `/api/child-batch` 合并发出：同一渲染轮内的 N 个父 = 1 条请求。
  //
  // 语义保持（渲染面零改动）：返回的 Promise 仍在「该父读数落袋」时 resolve；
  // TTL / 在飞 / fail-soft 三道闸一条不动（仍可重复调用、仍不并发重问）。
  childrenInflight.add(key);
  childEnsureQueue.add(key);
  const promise = new Promise((resolve) => { childEnsureWaiters.set(key, resolve); });
  scheduleChildEnsureDrain();
  return promise;
}

/** 渲染面驱动的批量补拉队列（见 [[ensureChildren]]）：父键集 + 逐键 resolver。 */
const childEnsureQueue = new Set();
const childEnsureWaiters = new Map();
/** 合批窗口：同一渲染轮内陆续调用的 ensureChildren 并成一条请求。 */
const CHILD_ENSURE_GAP_MS = 40;
let childEnsureTimer = null;

function scheduleChildEnsureDrain() {
  if (childEnsureTimer) return;
  childEnsureTimer = setTimeout(() => {
    childEnsureTimer = null;
    const ids = Array.from(childEnsureQueue);
    childEnsureQueue.clear();
    const done = () => {
      for (const id of ids) {
        const resolve = childEnsureWaiters.get(id);
        childEnsureWaiters.delete(id);
        if (resolve) resolve();
      }
    };
    if (!ids.length) return done();
    // 单飞 + 并发闸：本批走 withSlot（与详情腿共用 MAX_INFLIGHT），且不与
    // refresh() 里的子代理腿抢占同一批父（在飞集合已在 ensureChildren 入口把门）。
    withSlot(() => refreshChildrenBatch(ids))
      .catch(() => { for (const id of ids) childrenFetchedAt.set(id, Date.now()); })
      .then(done, done);
  }, CHILD_ENSURE_GAP_MS);
}

/**
 * 一轮刷新（重连风暴修复后的形状）：
 *   1. **一次**行面请求（/api/delegates，ETag 已服务端就位 ⇒ 未变即 304）；
 *   2. **一次**批量摘要请求（/api/chain-digests，`?since=` 未变即零增量体）；
 *   3. 只对「缺缓存 ∨ 摘要变了 ∨ 活跃行到期」的键取详情，且**一轮有预算**
 *      （DETAIL_BUDGET），余量交给下一轮与按需补拉队列；
 *   4. 并发上限 MAX_INFLIGHT；429 即按类退避（本轮剩下的详情腿直接放弃）。
 * 稳态（重连且盘面未变）⇒ 2 个请求、零详情重放——这正是事故前做不到的。
 * 刷新完成即广播 delegate-chain-mirror-updated（面板渲染层监听此事件重渲）：
 * 数据权威恒在本镜像（REST），事件零载荷、零帧解读（「帧只当触发器」纪律不变）。
 */
async function refresh() {
  const seq = ++mirror.seq;
  stormOpen = false;
  let body = await fetchJson(CHAINS_API.rows());
  // 形状判据：行面载荷必须带 rows 数组——404/空壳 200（旧引擎无 /api/delegates 路由，
  // 或同前缀异面响应）一律回落 /api/chains（行投影同源；缺 topology/merging 两键，
  // 全景子过程面走镜像组合臂，零崩面）。两臂都无形状 = 空刷新（fail-soft 保旧值）。
  if (!body || !Array.isArray(body.rows)) {
    body = await fetchJson('/api/chains');
  }
  if (!body || !Array.isArray(body.rows) || seq !== mirror.seq) return; // 已有更新刷新在途，丢弃旧结果
  const rows = (Array.isArray(body.rows) ? body.rows : []).map(projectRow);
  // 两层架构批（§D 自动归档到达侦测）：记录面零删除（归档只加 archivedAt 旗），
  // 在册窗消失的行 = 已归档（引擎终态单点写旗）。差集行细节随 delegate-archived
  // 事件下发（L2 徽章脉冲 + toast 5s 撤销——消费方 delegateChainView；本件零 UI）。
  if (mirror.rows.length && typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
    const nextKeys = new Set(rows.map(rowKey).filter(Boolean));
    for (const prev of mirror.rows) {
      const k = rowKey(prev);
      if (k && !nextKeys.has(k)) {
        window.dispatchEvent(new CustomEvent(EVENTS.DELEGATE_ARCHIVED, { detail: {
          delegateId: String(prev.delegateId || ''),
          taskNo: String(prev.taskNo || ''),
          title: String(prev.title || ''),
          status: String(prev.status || ''),
        } }));
      }
    }
  }
  const prevRows = mirror.rows;
  const rowsChanged = prevRows.length !== rows.length ||
    rows.some((r, i) => JSON.stringify(r) !== JSON.stringify(prevRows[i]));
  mirror.rows = rows;
  emptyKeys.clear();
  const keys = new Set(rows.map(rowKey).filter(Boolean));
  // 视图缓存剪枝：行集里已无此键 ⇒ 丢掉（再需要时按需补拉）。**连带剪摘要**——
  // 否则摘要镜像会随历史累积成无主表。
  for (const k of Array.from(mirror.chains.keys())) {
    if (!keys.has(k)) {
      mirror.chains.delete(k);
      chainFetchedAt.delete(k);
    }
  }

  const changed = await refreshDigests();

  const now = Date.now();
  const priority = [];
  const rest = [];
  const activeChildIds = [];
  const otherChildIds = [];
  for (const row of rows) {
    const k = rowKey(row);
    const active = ACTIVE_STATUSES.has(String(row.status || ''));
    if (k && digestsReady && chainDigests.has(k)) {
      const cached = mirror.chains.has(k);
      const due = !cached || changed.has(k) ||
        (active && now - (chainFetchedAt.get(k) || 0) > DETAIL_TTL_MS);
      if (due) (active ? priority : rest).push(k);
    }
    // 子代理腿同一条纪律：TTL 到期（或从未取过）才取（旧行为是每轮对每个内部行
    // 全量重取——那同样是重连风暴的一半体量）。
    if (!row.external && row.delegateId &&
      (!childrenFetchedAt.has(row.delegateId) || now - childrenFetchedAt.get(row.delegateId) > DETAIL_TTL_MS)) {
      (active ? activeChildIds : otherChildIds).push(row.delegateId);
    }
  }
  // 非活跃键按「最久没取过」轮转（对象属性序不可依赖；这一序保证不饿死尾部行）。
  rest.sort((a, b) => (chainFetchedAt.get(a) || 0) - (chainFetchedAt.get(b) || 0));
  const batch = uniq(priority.concat(rest)).slice(0, DETAIL_BUDGET);
  // 子代理腿：活跃行优先，其余按最久未取轮转，一轮上限 CHILD_BUDGET。
  // 🔴 手机面板卡顿修复批（2026-11-21 · 根因一）：本腿修前是**逐父一条**
  // `/api/delegates/<id>/children`（一拍 116+ 条，与 5s 轮询叠加成风暴）——
  // 现在整批压成**一条** `/api/child-batch`（内部按 CHILD_BATCH_MAX 分批）。
  // 预算语义不变：仍是「一轮最多 CHILD_BUDGET 个父，活跃优先 + 最久未取轮转」。
  otherChildIds.sort((a, b) => (childrenFetchedAt.get(a) || 0) - (childrenFetchedAt.get(b) || 0));
  const childBatch = uniq(activeChildIds.concat(otherChildIds)).slice(0, CHILD_BUDGET);
  const [chainResults, childWrote] = await Promise.all([
    Promise.all(batch.map((k) => withSlot(() => refreshChain(k).catch(() => false)))),
    withSlot(() => refreshChildrenBatch(childBatch).catch(() => false)),
  ]);
  const results = chainResults;
  if (seq !== mirror.seq) return; // 刷新期间又有更新在途——广播交给最新一轮
  // 广播判据（既有契约不变：零增量的轮次不重渲面板——「未变 ⇒ 无事件」正是
  // subagent-visibility 的既有断言）。修复前 refresh 只在行面形状通过时广播且
  // 详情腿全量重写，故「有请求 ⇒ 有事件」；现在请求被预算压到 24 条，必须显式
  // 判「这一轮到底改了什么」：行变了 ∨ 摘要变了 ∨ 任一详情腿真的写了新读数。
  if (rowsChanged || changed.size > 0 || results.some((ok) => ok === true) || childWrote === true) {
    dispatchMirrorUpdated();
  }
}

/** 单飞闸：同一时刻只有一轮刷新在飞；期间的新触发并成「再来一轮」（不叠加）。 */
let refreshInFlight = null;
let refreshAgain = false;
function requestRefresh() {
  if (refreshInFlight) {
    refreshAgain = true;
    return refreshInFlight;
  }
  refreshInFlight = refresh()
    .catch(() => {})
    .then(() => {
      refreshInFlight = null;
      if (refreshAgain) {
        refreshAgain = false;
        return requestRefresh();
      }
      return undefined;
    });
  return refreshInFlight;
}

/** 归档动作后的镜像即时对账（bgAgentPopup 归档入口回调；fire-and-forget fail-soft
 *  ——归档行移历史窗，在册窗重刷后行自然退场）。 */
export function refreshMirror() {
  requestRefresh();
}

/** 按需补拉（getChainByTask 未命中键）：排队 + 间隔 + 并发闸，本次调用仍返回 null。 */
function fetchChainLazy(key) {
  if (!key || emptyKeys.has(key)) return;
  enqueueLazy(key);
}

// ── read port（setSource 契约形；夹具缝在 stub 读取口——夹具在场时本源不被读）────

const liveSource = {
  listDelegateRows() {
    return mirror.rows.slice();
  },
  getChainByTask(taskNo) {
    const key = String(taskNo == null ? '' : taskNo);
    if (mirror.chains.has(key)) return mirror.chains.get(key);
    fetchChainLazy(key); // 未命中：后台补拉（下次渲染/重试可见），本次 fail-soft null
    return null;
  },
};

setSource(liveSource);

// G3 星型子代理读口挂接（delegate-ui 批）：渲染面（main.js 面板两级分组 /
// bgAgentPopup 星型区 / taskList S1 标注）经 stub listDelegateChildren 读取本镜像。
setChildrenSource({
  listDelegateChildren(delegateId) {
    return childrenMirror.get(String(delegateId == null ? '' : delegateId)) || [];
  },
});

// ── 镜像驱动：boot 首刷 ＋ WS 触发防抖刷 ＋ 重连对账 ─────────────────────────

// boot 首刷（fail-soft：失败 = 空镜像，下轮 WS/重连再试）。走单飞闸——首屏与
// 紧随其后的重连触发不会各发一套全量。
requestRefresh();

const DEBOUNCE_MS = 500;
let debounceTimer = null;
function scheduleRefresh() {
  if (debounceTimer) return;
  debounceTimer = setTimeout(() => {
    debounceTimer = null;
    requestRefresh(); // 单飞：在飞的那一轮之后只再补一轮（不叠加、不成风暴）
  }, DEBOUNCE_MS);
}

// WS 增量 = 刷新触发器（非数据源）：delegate 作用域链的 node* 帧族经既有广播到达。
for (const evt of ['nodeCreated', 'nodeUpdated', 'nodeCompleted', 'nodeRemoved']) {
  onMessage(evt, scheduleRefresh);
}

// delegate 生命周期触发（e2e 断点①修 · 2026-10-07）：裸 delegate spawn/续聊/终态
// 不经作用域图——node* 帧族在「无 AgentFlow 链活动」的整段在飞期为零（实测：
// rig :8245 /api/chains 22s 即有行，页面不刷新则任务列表恒空直到 reload）。本组
// 触发器把 delegate/external 会话自身的 turn 边界帧（nodeSessionId 前缀判据）
// 也接进同一防抖刷——帧仍只当触发器，数据权威恒在 REST /api/chains；tool 级
// 帧（agentToolStart/End）不接（每工具一帧太频，行状态变更已由 turn 边界覆盖：
// spawn=agentStart、终态状态槽=turn 内写盘 + 该 turn 的 agentDone 兜底）。
const DELEGATE_TRIGGER_RE = /^(delegate|external)-/;
function onDelegateLifecycle(msg) {
  const nsid = String((msg && (msg.nodeSessionId || msg.agentId)) || '');
  if (DELEGATE_TRIGGER_RE.test(nsid)) scheduleRefresh();
  const parent = String((msg && msg.parentSessionId) || '').replace(/^team-/, '');
  if (/^(node-|dispatcher-)/.test(parent)) {
    childrenFetchedAt.delete(parent);
    ensureChildren(parent);
  }
}
for (const evt of ['agentStart', 'agentDone', 'agentEnd']) {
  onMessage(evt, onDelegateLifecycle);
}
onReconnect(() => {
  // 重连 = 新网络路径：清掉退避桶（否则重连后还要白等一个 Retry-After 窗口），
  // 但**不清**镜像与摘要——视图复用缓存，只刷新增量（正是本次修复的目标）。
  backoff.clear();
  stormOpen = false;
  requestRefresh();
  refreshScopedTasks();
});
if (typeof window !== 'undefined') {
  window.addEventListener(EVENTS.SESSION_CHANGE, () => refreshScopedTasks());
  // Bounded polling discovers accepted work even when no start event arrives.
  const timer = setInterval(() => {
    if (document.visibilityState !== 'hidden') refreshScopedTasks();
  }, 5000);
  window.addEventListener('pagehide', () => clearInterval(timer), { once: true });
}
