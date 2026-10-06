// delegateChainStub.js — ⑤A stub 接口：per-task delegate 链的前端本地契约模块
// （#1228 重划版提前开工批 · 与 N1 并行不互阻塞；定稿后换源，本件即适配缝）。
//
// 角色定位（任务书「可落地段」）：
//   - **契约单源 = 规格 ⑤A 定稿**（引擎面 `ChainStore.scala`，#1224 已落库）＋
//     `DelegateRegistry.Record` ＋ S1/S10/S11/S16 规格句。**不候 N1 定稿**：
//     N1 结构帧的字段未定 ⇒ 帧类型在此拆成「已落集」（#1224 三行族，逐键在案）
//     与「候定集」（PENDING，仅声明不解读），差异一律由下方**适配层**吸收。
//   - **读取口（read port）**＝未来真实数据源的同形接口（listDelegateRows /
//     getChainByTask）。N1 刀3 换源已落：delegateChainProvider.js 经 setSource()
//     挂真 provider（REST /api/chains 快照 + WS 触发的防抖镜像），渲染面
//     （taskList.js delegate 行骨架）零改动；夹具在场 = 恒夹具优先（harness 兼容缝）。
//   - **适配层**＝纯函数归一（引擎 Record/行 JSON → 规范 schema），字段缺失
//     容忍（旧盘面/中间态不崩、不臆造）。本件**零 import**（无环、可单测）。
//
// 纪律：本件零用户可见文案（无 locale 触碰）、零引擎/Scala 触碰；schema 变更
// 只允许「加宽」（N1 定稿字段并入），已命名键是跨批契约、禁改名。

// ── 规范 schema（最小集）─────────────────────────────────────────────────

/**
 * 任务行/链节点共用的状态最小集（S1「状态」列的值域）。
 *
 * 推导：DelegateJudge 三判词（completed / blocked / ready-to-merge，引擎面
 * `DelegateJudge.Verdict`）∪ 引擎终态（failed / cancelled）∪ 活性态
 *（running / pending，SubAgentTask store 与外部适配流的 live 面）。
 * `ready-to-merge` 为 #1224 新态（S11 FIFO 入队锚）。
 * @enum {string}
 */
export const ROW_STATUS = {
  PENDING: 'pending',
  RUNNING: 'running',
  BLOCKED: 'blocked',
  READY_TO_MERGE: 'ready-to-merge',
  COMPLETED: 'completed',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
};

/** 合法值域（归一白名单；未知值 → RUNNING 由调用方降级，本表只声明）。 */
const ROW_STATUS_VALUES = new Set(Object.values(ROW_STATUS));

/**
 * 帧类型最小集——「已落集」＝ #1224 已落库的 ChainStore 三行族（逐键契约见
 * 引擎面各 payload 构造点，下方 ADAPTER 注释逐键对照）；「候定集」＝ N1 结构帧
 * （整链登记/链节点状态帧），**仅声明、不解读**——适配层原样透传进
 * ChainView.frames，换源批按 N1 定稿并入。
 * @enum {string}
 */
export const FRAME_KINDS = {
  // 已落集（#1224 ChainStore 写入行，键逐字对齐引擎面）：
  DELEGATE_SPAWN: 'delegate-spawn',   // DelegateWorkspace.spawnPayload：delegateId/executor/project/branch/worktree/cwd/baseWorkspace/baseBranch/baseSha
  TERMINAL_VERDICT: 'terminal-verdict', // DelegateJudge.verdictPayload：delegateId/taskId/verdict/reason/source
  MERGE_QUEUE: 'merge-queue',         // MergeQueue.record：action/delegateId/branch/projectKey/readyAt(+extra)
  // 候定集（N1 结构帧，字段未定稿——透传不解读）：
  CHAIN_REGISTERED: 'chain-registered',
  CHAIN_NODE_STATE: 'chain-node-state',
};

/** N1 定稿前不得消费的帧集（适配层只透传；防「对未定字段造第二权威」）。 */
const PENDING_N1_KINDS = new Set([FRAME_KINDS.CHAIN_REGISTERED, FRAME_KINDS.CHAIN_NODE_STATE]);

/**
 * @typedef {Object} DelegateRow 任务行（S1：task#N＋标题＋执行器图标＋状态）
 * @property {string} taskNo            TaskLedger 台账号（task #N 的 N；'' = 派发期台账失败回落形态）
 * @property {string} delegateId        delegate 会话 id（`delegate:<id>` 续聊地址 payload）
 * @property {string} title             任务摘要（全文在 TaskLedger）
 * @property {string} executor          执行器 id（nebflow|zcode|claude-code|codex|hermes；'' = 未知）
 * @property {string} status            ROW_STATUS 值域
 * @property {boolean} external         外部执行器（executor ≠ nebflow）⇒ S16 粗粒度行
 * @property {string} project           挂载项目名（'' = 默认 general 目录）
 */

/**
 * @typedef {Object} ChainNode 链节点（S10：卡片点开可见 node 任务与描述）
 * @property {string} id
 * @property {string} kind              'delegate' | 'subagent' | 'flow-step'（最小三值；N1 定稿可加宽）
 * @property {string} label             任务（行显）
 * @property {string} description       描述（卡片显；'' = 未提供）
 * @property {string} status            ROW_STATUS 值域
 * @property {string[]} deps            上游依赖 id
 * @property {string} executor          执行器 id（'' = 继承链）
 */

/**
 * @typedef {Object} ChainView per-task 链视图（S10 数据源；S11 的 worktree+FIFO 面）
 * @property {string} taskId            存储键（台账号优先，delegateId 回落——ChainStore.keyFor 同口径）
 * @property {string} delegateId        主 delegate（单 delegate 链现形；N1 多节点链 = nodes 加宽）
 * @property {ChainNode[]} nodes        链节点（N1 定稿前 = 单 delegate 节点形）
 * @property {{branch: string, worktree: string, cwd: string, baseWorkspace: string, baseBranch: string, baseSha: string}|null} worktree
 *                                      spawn 行投影（S11 自动建 worktree 面的数据源；无分支腿 = null）
 * @property {{verdict: string, reason: string, source: string, at: number}|null} verdict
 *                                      terminal-verdict 行投影（三判词 + reason）
 * @property {Array<{delegateId: string, branch: string, projectKey: string, readyAt: number, action: string, at: number}>} mergeQueue
 *                                      merge-queue 行投影——**FIFO 排序键 = 完成时间**
 *                                     （readyAt ↑；S11 改义：非创建时间）
 * @property {Array<{kind: string, at: number, payload: Object<string, any>}>} frames
 *                                      原样行时序（含 N1 候定帧——透传不解读）
 */

// ── 适配层（纯函数 · 引擎 Record/行 → 规范 schema）────────────────────────

/**
 * 状态值域归一：白名单外一律 'running'（stub 期最常见态；未知态不崩）。
 * @param {any} s @returns {string}
 */
export function normalizeStatus(s) {
  const v = String(s == null ? '' : s);
  return ROW_STATUS_VALUES.has(v) ? v : ROW_STATUS.RUNNING;
}

/**
 * DelegateRegistry.Record JSON → 任务行（S1 行数据源的适配半边）。
 *
 * 状态推导（适配层单点，权威仍在后端）：record.reportStatus 是引擎状态槽
 *（'finish' | 'blocked' | 缺省）——finish → completed、blocked → blocked；
 * 缺省时读 `live.status` 提示（stub/换源 provider 供给的活性态）；两者皆无
 * → 'running'。external = executor 非空且 ≠ 'nebflow'（内置执行器唯一
 * id，ExecutorRegistry.Definitions 同口径）⇒ S16 粗粒度行。
 * @param {Record<string, any>} record DelegateRegistry.Record 形 JSON（缺键容忍）
 * @param {{status?: string}} [live]   活性态提示（可选；缺省无）
 * @returns {DelegateRow}
 */
export function rowFromRegistry(record, live) {
  const r = record && typeof record === 'object' ? record : {};
  const executor = String(r.executor || '');
  const reported = String(r.reportStatus || '');
  let status;
  if (reported === 'finish') status = ROW_STATUS.COMPLETED;
  else if (reported === 'blocked') status = ROW_STATUS.BLOCKED;
  else if (live && live.status) status = normalizeStatus(live.status);
  else status = ROW_STATUS.RUNNING;
  return {
    taskNo: String(r.taskId || ''),
    delegateId: String(r.id || ''),
    title: String(r.title || ''),
    executor,
    status,
    external: executor !== '' && executor !== 'nebflow',
    project: String(r.project || ''),
  };
}

/**
 * ChainStore.Record JSON → ChainView（S10/S11 链视图数据源的适配半边）。
 *
 * 三行族逐键投影（键面见 FRAME_KINDS 注释；缺键 = 空投影，不臆造）：
 *   - delegate-spawn（最新一行生效）→ worktree 投影；
 *   - terminal-verdict（最新一行生效）→ verdict 投影；
 *   - merge-queue（全量收）→ mergeQueue，经 mergeQueueFifo 按**完成时间**
 *     （readyAt ↑）排序——S11 排序键改义在此落定；
 *   - 其余 kind（含 N1 候定帧）→ frames 原样透传。
 * @param {Record<string, any>} record ChainStore Record 形 JSON（{taskId, entries[]}；缺形容忍）
 * @returns {ChainView}
 */
export function chainFromStoreRecord(record) {
  const rec = record && typeof record === 'object' ? record : {};
  const entries = Array.isArray(rec.entries) ? rec.entries : [];
  /** @type {{kind: string, at: number, payload: Object<string, any>}[]} */
  const frames = [];
  /** @type {{delegateId: string, branch: string, projectKey: string, readyAt: number, action: string, at: number}[]} */
  const queue = [];
  let worktree = null;
  let verdict = null;
  let spawnAt = -1;
  let verdictAt = -1;
  for (const e of entries) {
    if (!e || typeof e !== 'object') continue;
    const kind = String(e.kind || '');
    const at = typeof e.at === 'number' ? e.at : 0;
    const p = e.payload && typeof e.payload === 'object' ? /** @type {Object<string, any>} */ (e.payload) : {};
    if (kind === FRAME_KINDS.DELEGATE_SPAWN) {
      // 最新一行生效（重 spawn 覆盖旧投影；行序 = 时间序，引擎面 append 保序）。
      if (at >= spawnAt) {
        spawnAt = at;
        worktree = {
          branch: String(p.branch || ''),
          worktree: String(p.worktree || ''),
          cwd: String(p.cwd || ''),
          baseWorkspace: String(p.baseWorkspace || ''),
          baseBranch: String(p.baseBranch || ''),
          baseSha: String(p.baseSha || ''),
        };
      }
    } else if (kind === FRAME_KINDS.TERMINAL_VERDICT) {
      if (at >= verdictAt) {
        verdictAt = at;
        verdict = {
          verdict: String(p.verdict || ''),
          reason: String(p.reason || ''),
          source: String(p.source || ''),
          at,
        };
      }
    } else if (kind === FRAME_KINDS.MERGE_QUEUE) {
      queue.push({
        delegateId: String(p.delegateId || ''),
        branch: String(p.branch || ''),
        projectKey: String(p.projectKey || ''),
        readyAt: typeof p.readyAt === 'number' ? p.readyAt : at,
        action: String(p.action || ''),
        at,
      });
    } else if (!PENDING_N1_KINDS.has(kind)) {
      // 未声明帧（未来行族）也透传——账本是审计面，前端不丢行。
      frames.push({ kind, at, payload: p });
    } else {
      // N1 候定帧：透传进 frames（结构可见、不解读——字段定稿前禁造第二权威）。
      frames.push({ kind, at, payload: p });
    }
  }
  const spawnEntry = /** @type {any} */ (entries.find((e) => e && e.kind === FRAME_KINDS.DELEGATE_SPAWN) || null);
  const delegateId = String((spawnEntry && spawnEntry.payload && spawnEntry.payload.delegateId) || '');
  /** @type {ChainNode[]} */
  const nodes = delegateId
    ? [{
        id: delegateId,
        kind: 'delegate',
        label: String(rec.taskId || delegateId),
        description: '',
        status: verdict ? verdictToStatus(verdict.verdict) : ROW_STATUS.RUNNING,
        deps: [],
        executor: '',
      }]
    : [];
  return {
    taskId: String(rec.taskId || ''),
    delegateId,
    nodes,
    worktree: worktree && (worktree.branch || worktree.cwd) ? worktree : null,
    verdict,
    mergeQueue: mergeQueueFifo(queue),
    frames,
  };
}

/**
 * 判词 → 行状态（三判词映射；未知判词 → running）。
 * @param {string} v @returns {string}
 */
export function verdictToStatus(v) {
  if (v === 'ready-to-merge') return ROW_STATUS.READY_TO_MERGE;
  if (v === 'blocked') return ROW_STATUS.BLOCKED;
  if (v === 'completed') return ROW_STATUS.COMPLETED;
  return ROW_STATUS.RUNNING;
}

/**
 * merge-queue FIFO 排序（S11 排序键改义单点）：**完成时间**（readyAt）↑，
 * 同刻按到达序（at ↑）、再按 delegateId（确定性收尾）。返回新数组（不改入参）。
 * @param {Array<{delegateId: string, branch: string, projectKey: string, readyAt: number, action: string, at: number}>} queue
 * @returns {Array<{delegateId: string, branch: string, projectKey: string, readyAt: number, action: string, at: number}>}
 */
export function mergeQueueFifo(queue) {
  const list = Array.isArray(queue) ? queue.slice() : [];
  list.sort((a, b) => {
    if (a.readyAt !== b.readyAt) return a.readyAt - b.readyAt;
    if (a.at !== b.at) return a.at - b.at;
    return a.delegateId < b.delegateId ? -1 : a.delegateId > b.delegateId ? 1 : 0;
  });
  return list;
}

// ── 读取口（read port）＋ 换源钩子 ────────────────────────────────────────

/** @type {Map<string, DelegateRow>} */
const fixtureRows = new Map();
/** @type {Map<string, ChainView>} */
const fixtureChains = new Map();

/**
 * 夹具在场否（harness/单测判据；产品路径恒 false——零调用）。读取口在夹具在场时
 * 恒读夹具（provider 换源后夹具注入面零回归——N1 刀3 换源批的兼容缝；优先级 =
 * 夹具 > provider，产品无夹具即纯 provider 面）。
 * @returns {boolean}
 */
export function __hasFixture() {
  return fixtureRows.size > 0 || fixtureChains.size > 0;
}

/** 本地 stub 数据源（默认）：读 fixture（默认空）。 */
const localStubSource = {
  /** @returns {DelegateRow[]} */
  listDelegateRows() {
    return Array.from(fixtureRows.values());
  },
  /** @param {string} taskNo @returns {ChainView|null} */
  getChainByTask(taskNo) {
    return fixtureChains.get(String(taskNo)) || null;
  },
};

/**
 * 当前数据源。换源（N1/#1228 定稿后）：setSource({listDelegateRows,
 * getChainByTask}) 挂真实 provider（REST 快照 + WS 增量维护的本地镜像），
 * 渲染面零改动。
 */
let source = localStubSource;

/**
 * 换源钩子（适配缝的唯一写入点）。传 null/缺形 → 回落本地 stub。
 * @param {{listDelegateRows: function(): DelegateRow[], getChainByTask: function(string): ChainView|null}} [provider]
 */
export function setSource(provider) {
  source = provider && typeof provider.listDelegateRows === 'function' &&
    typeof provider.getChainByTask === 'function'
    ? provider
    : localStubSource;
}

/** 任务行清单（S1 行数据源读取口；返回源侧快照，渲染面不改写）。夹具在场 = 恒夹具
 *  优先（换源后 harness 注入口零回归；产品路径无夹具 = 纯 source 面）。 @returns {DelegateRow[]} */
export function listDelegateRows() {
  if (__hasFixture()) return Array.from(fixtureRows.values());
  try {
    return source.listDelegateRows();
  } catch (_) {
    return []; // 源故障 ⇒ 空行集（面板无 delegate 区块），绝不崩面板
  }
}

/**
 * per-task 链视图（S10 点进 delegate 的数据源读取口；查无 → null）。夹具在场 = 恒
 * 夹具优先（同上）。
 * @param {string} taskNo @returns {ChainView|null}
 */
export function getChainByTask(taskNo) {
  const k = String(taskNo == null ? '' : taskNo);
  if (__hasFixture()) return fixtureChains.get(k) || null;
  try {
    return source.getChainByTask(k);
  } catch (_) {
    return null;
  }
}

// ── harness 注入口（测试专用；产品路径零调用）────────────────────────────

/**
 * 装 stub 夹具（harness/单测专用）。rows/chains 均可选；chains 以 taskId 键挂。
 * @param {{rows?: DelegateRow[], chains?: ChainView[]}} [fx]
 */
export function __setFixture(fx) {
  const f = fx && typeof fx === 'object' ? fx : {};
  fixtureRows.clear();
  fixtureChains.clear();
  for (const r of (Array.isArray(f.rows) ? f.rows : [])) {
    if (r && typeof r === 'object') fixtureRows.set(String(r.delegateId || r.taskNo || ''), r);
  }
  for (const c of (Array.isArray(f.chains) ? f.chains : [])) {
    if (c && typeof c === 'object') fixtureChains.set(String(c.taskId || ''), c);
  }
}

/** 清空 stub 夹具（回归默认空态）。 */
export function __resetFixture() {
  fixtureRows.clear();
  fixtureChains.clear();
}
