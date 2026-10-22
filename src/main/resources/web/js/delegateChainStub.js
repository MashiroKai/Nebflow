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
//
// delegate-ui 批（2026-10-08 · 定稿蓝本 §3 三值归属）加宽两件（均纯加宽、旧行
// 缺键容忍不臆造）：
//   ① DelegateRow 加宽 G1 `orchestrationMode`（single/subagent-star/agentflow；
//      null → ''，读面禁折叠 None→single——过渡启发兜底归渲染层）＋ G6
//      `archivedAt` ＋ G5 行内排队条件键 `mergeQueue`/`mergeQueuePos`（引擎闸
//      单点判据透传，未排队 = null；🔴 禁前端复刻判据）。
//   ② 星型子代理读取口（G3 `GET /api/delegates/:id/children` 投影）：
//      setChildrenSource 挂 provider（照 setSource 换源缝同款），渲染面经
//      listDelegateChildren 读取——零 WS 直连。

// ── 规范 schema（最小集）─────────────────────────────────────────────────

/**
 * 任务行/链节点共用的状态最小集（S1「状态」列的值域）。
 *
 * 推导：引擎任务行终态（taskStatus：completed / failed / cancelled / ready-to-merge
 * ——SubAgentTaskStore 值域，#566 起行投影携带）∪ 判定机判词（completed(+注记) /
 * ready-to-merge——#566 起 blocked 判词退役，「没做成」只是报告注记，作者令：
 * 「blocked 是给 agentflow 节点的」）∪ 活性态（running / pending，SubAgentTask
 * store 与外部适配流的 live 面）。
 * `ready-to-merge` 为 #1224 新态（S11 FIFO 入队锚）。BLOCKED 值保留 = agentflow
 * 节点（flow-step 成员）合法态；delegate 行不再产生。
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
  // 重启打断（任务列表行重启对账批 2026-10-22）：boot 对账判定「在飞时进程死亡」
  // 的行——灰态显示，非终态非活态（打勾只给真完成，本态永不渲染完成语言）。
  INTERRUPTED: 'interrupted',
};

/** 合法值域（归一白名单；未知值 → RUNNING 由调用方降级，本表只声明）。 */
const ROW_STATUS_VALUES = new Set(Object.values(ROW_STATUS));

/**
 * 编排模式值域（G1，delegate-ui 批 2026-10-08）：引擎 DelegateRegistry.Mode* 同族。
 * 权威 = rows 行内 `orchestrationMode`；旧盘记录 = null → 前端过渡启发兜底
 * （渲染层禁折叠 null→single——null 表示「未知」，不是「单干」）。
 * @enum {string}
 */
export const ORCHESTRATION_MODES = {
  SINGLE: 'single',
  SUBAGENT_STAR: 'subagent-star',
  AGENTFLOW: 'agentflow',
};

/**
 * 帧类型最小集——「已落集」＝ #1224 已落库的 ChainStore 三行族（逐键契约见
 * 引擎面各 payload 构造点，下方 ADAPTER 注释逐键对照）；「候定集」＝ N1 结构帧
 * （整链登记/链节点状态帧），**仅声明、不解读**——适配层原样透传进
 * ChainView.frames，换源批按 N1 定稿并入。
 * @enum {string}
 */
export const FRAME_KINDS = {
  // 已落集（#1224 ChainStore 写入行，键逐字对齐引擎面）：
  DELEGATE_SPAWN: 'delegate-spawn',   // DelegateWorkspace.spawnPayload：delegateId/executor/project/branch/worktree/cwd/baseWorkspace/baseBranch/baseSha；jev-plugins additive: plugins[]/allocation (optional — absent in old rows)
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
 * @property {number} createdAt         Record.createdAt 透传（epoch ms；0 = 缺键/非数）
 * @property {string} orchestrationMode G1 编排模式（ORCHESTRATION_MODES 值域；'' = 旧盘
 *                                      null/缺键——过渡启发兜底，禁折叠为 single）
 * @property {number|null} archivedAt   G6 归档时间戳 epoch ms（null = 在册未归档）
 * @property {{projectKey: string, ahead: string[], holder: string|null, reminderSent: boolean}|null} mergeQueue
 *                                      G5 行内排队可见性槽（引擎闸透传；未排队 = null）
 * @property {{position: number, total: number, readyAt: number, branch: string, baseBranch: string}|null} mergeQueuePos
 *                                      G5 行内排队位次（引擎自排序透传；未排队 = null）
 * @property {number|null} restartInterruptedAt  重启打断旗（对账批 2026-10-22，epoch ms；
 *                                      null = 未打断）——引擎 boot 对账单写，行显示态
 *                                      （徽标/灰态/折叠）的判据字段
 * @property {string} continuedBy       接续者 delegateId（引擎读面 continuationLinks
 *                                      推导；'' = 未被接续）——旧行折叠进新行的指针
 */

/**
 * 星型子代理条目（G3 `GET /api/delegates/:id/children` 投影；SubAgentTask 形）。
 * @typedef {Object} DelegateChild
 * @property {string} taskId            子会话 id（subtask- / subagent- / 嵌套 delegate- 前缀）
 * @property {string} parentSessionId   父 delegate 会话 id
 * @property {string} agentName         子代理名（数据原文）
 * @property {string} prompt            派发 prompt 全文
 * @property {string} description       任务描述
 * @property {string} status            running|completed|failed|restarting（store 值域原样）
 * @property {number} retryCount
 * @property {number} spawnedAt         epoch ms
 * @property {number|null} completedAt  epoch ms
 * @property {string} lastError
 * @property {string} source            subagent|subtask|delegate（过滤归消费端）
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
 * @property {{branch: string, worktree: string, cwd: string, baseWorkspace: string, baseBranch: string, baseSha: string, plugins: string[], allocation: string}|null} worktree
 *                                      spawn 行投影（S11 自动建 worktree 面的数据源；无分支腿 = null）。
 *                                      plugins/allocation = jev-plugins 批 additive 两键（旧行缺键 → []/''）
 * @property {string[]} plugins          分配集顶层投影（#523 补齐批：jev-plugins spawn 行 additive 键
 *                                      原名透传——无分支腿的 spawn 也有分配面，旧盘/缺键 → []）
 * @property {string} allocation         分配模式词顶层投影（jev / llm-fallback / fail-open /
 *                                      empty-catalog；惰性态 disabled/off/auto-off 与旧行缺键 → ''）
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
 * 状态推导（适配层单点，权威仍在后端；#566 修复批 2026-10-19）：
 *   ① `taskStatus`（行投影新键——引擎任务行终态，SubAgentTaskStore 值域）＝
 *      **真完成权威**：completed / ready-to-merge / cancelled 直映（delegate 终态后
 *      行必须在 UI 反映终态——reportStatus/live 双缺席时旧推导回落 running 的陈旧行
 *      根因修补）。failed 降级到⑤（重启打断旗可覆写——见④）。
 *   ② record.reportStatus 引擎状态槽（'finish' | 'merge' | 退役前 'blocked'）——
 *      finish → completed；**blocked → completed**（#566：delegate 面无 blocked
 *      状态——申报 blocked 的行按 completed 交付，阻塞事实是报告注记）。
 *   ③ 缺省读 `live.status` 提示（stub/换源 provider 供给的活性态）＝**行状态真源
 *      绑定 live 会话实时态**：旗后复活（Mail 续起同 id 重进 agentRegistry）的行按
 *      live 面——打断旗（④）只管死了没人接的尸体行，live 在场时旗让位。
 *   ④ `restartInterruptedAt`（重启对账批 2026-10-22）＝ boot 对账判定「在飞时进程
 *      死亡」→ **'interrupted' 灰态**：打勾只给真完成（①②），打断态永不渲染完成
 *      语言；覆写 failed 与 running 回落（死会话冻结活态/勾态的根因修补）。
 *   ⑤ 其余引擎终态 failed（无打断旗的常规失败——#566 终态权威原样）。
 *   ⑥ running 兜底。
 * external = executor 非空且 ≠ 'nebflow'（内置执行器唯一
 * id，ExecutorRegistry.Definitions 同口径）⇒ S16 粗粒度行。
 * @param {Record<string, any>} record DelegateRegistry.Record 形 JSON（缺键容忍）
 * @param {{status?: string}} [live]   活性态提示（可选；缺省无）
 * @returns {DelegateRow}
 */
export function rowFromRegistry(record, live) {
  const r = record && typeof record === 'object' ? record : {};
  const executor = String(r.executor || '');
  const reported = String(r.reportStatus || '');
  const engine = String(r.taskStatus || '');
  const ENGINE_TERMINAL = new Set(['completed', 'failed', 'cancelled', 'ready-to-merge']);
  const restartInterruptedAt = typeof r.restartInterruptedAt === 'number' && r.restartInterruptedAt > 0
    ? r.restartInterruptedAt
    : null;
  let status;
  if (engine === 'completed' || engine === 'ready-to-merge' || engine === 'cancelled') status = engine; // ① 真完成/真取消
  else if (reported === 'finish') status = ROW_STATUS.COMPLETED; // ② 报告交付
  else if (reported === 'blocked') status = ROW_STATUS.COMPLETED; // ② 退役词 → completed（注记在报告）
  else if (live && live.status) status = normalizeStatus(live.status); // ③ live 实时态（旗后复活先行）
  else if (restartInterruptedAt != null) status = ROW_STATUS.INTERRUPTED; // ④ 重启打断（灰态）
  else if (ENGINE_TERMINAL.has(engine)) status = engine; // ⑤ 常规失败（#566 终态权威）
  else status = ROW_STATUS.RUNNING;
  // G1/G5/G6 加宽（delegate-ui 批）：缺键容忍——旧盘 null → mode '' / archivedAt
  // null / 队列两键 null（读面禁折叠 None→single；未排队缺键纪律在引擎侧）。
  const q = r.mergeQueue && typeof r.mergeQueue === 'object' ? r.mergeQueue : null;
  const qp = r.mergeQueuePos && typeof r.mergeQueuePos === 'object' ? r.mergeQueuePos : null;
  return {
    taskNo: String(r.taskId || ''),
    delegateId: String(r.id || ''),
    title: String(r.title || ''),
    executor,
    status,
    external: executor !== '' && executor !== 'nebflow',
    project: String(r.project || ''),
    createdAt: typeof r.createdAt === 'number' ? r.createdAt : 0,
    orchestrationMode: String(r.orchestrationMode || ''),
    archivedAt: typeof r.archivedAt === 'number' ? r.archivedAt : null,
    // 重启对账批 2026-10-22：打断旗 + 接续指针（缺键容忍——旧引擎行 = null/''）。
    restartInterruptedAt,
    continuedBy: String(r.continuedBy || ''),
    mergeQueue: q ? {
      projectKey: String(q.projectKey || ''),
      ahead: Array.isArray(q.ahead) ? q.ahead.map((x) => String(x || '')) : [],
      holder: q.holder == null ? null : String(q.holder),
      reminderSent: q.reminderSent === true,
    } : null,
    mergeQueuePos: qp ? {
      position: typeof qp.position === 'number' ? qp.position : 0,
      total: typeof qp.total === 'number' ? qp.total : 0,
      readyAt: typeof qp.readyAt === 'number' ? qp.readyAt : 0,
      branch: String(qp.branch || ''),
      baseBranch: String(qp.baseBranch || ''),
    } : null,
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
  // #523 补齐批：spawn 行分配两键的顶层投影（与 worktree 投影同帧同源——挂在
  // 顶层是为了让无分支腿（worktree 投影为 null）的 spawn 也有分配可见面）。
  let allocPlugins = [];
  let allocMode = '';
  for (const e of entries) {
    if (!e || typeof e !== 'object') continue;
    const kind = String(e.kind || '');
    const at = typeof e.at === 'number' ? e.at : 0;
    const p = e.payload && typeof e.payload === 'object' ? /** @type {Object<string, any>} */ (e.payload) : {};
    if (kind === FRAME_KINDS.DELEGATE_SPAWN) {
      // 最新一行生效（重 spawn 覆盖旧投影；行序 = 时间序，引擎面 append 保序）。
      if (at >= spawnAt) {
        spawnAt = at;
        // plugins/allocation: jev-plugins additive spawn payload keys (#1226) —
        // absent in old rows → []/'' (missing-tolerant, no fabrication).
        allocPlugins = Array.isArray(p.plugins) ? p.plugins.map((x) => String(x || '')) : [];
        allocMode = String(p.allocation || '');
        worktree = {
          branch: String(p.branch || ''),
          worktree: String(p.worktree || ''),
          cwd: String(p.cwd || ''),
          baseWorkspace: String(p.baseWorkspace || ''),
          baseBranch: String(p.baseBranch || ''),
          baseSha: String(p.baseSha || ''),
          plugins: Array.isArray(p.plugins) ? p.plugins.map((x) => String(x || '')) : [],
          allocation: String(p.allocation || ''),
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
    plugins: allocPlugins,
    allocation: allocMode,
    verdict,
    mergeQueue: mergeQueueFifo(queue),
    frames,
  };
}

/**
 * 判词 → 行状态（#566 判词映射：completed(+注记) / ready-to-merge 两态；引擎
 * failed/cancelled 终态行写真值。退役 blocked 判词（旧盘行）→ completed——「没
 * 做成」是报告注记，不再是状态；未知判词 → running）。
 * @param {string} v @returns {string}
 */
export function verdictToStatus(v) {
  if (v === 'ready-to-merge') return ROW_STATUS.READY_TO_MERGE;
  if (v === 'failed') return ROW_STATUS.FAILED;
  if (v === 'cancelled') return ROW_STATUS.CANCELLED;
  if (v === 'blocked') return ROW_STATUS.COMPLETED; // #566：退役词 → completed
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

// ── 星型子代理读取口（G3 投影；setSource 换源缝同款第二读口）───────────────

/** @type {Map<string, DelegateChild[]>} */
const fixtureChildren = new Map();
/** @type {{listDelegateChildren: function(string): DelegateChild[]}|null} */
let childrenSource = null;

/**
 * 星型子代理换源钩子（与 setSource 同款唯一写入点；传 null/缺形 → 回落空源）。
 * @param {{listDelegateChildren: function(string): DelegateChild[]}} [provider]
 */
export function setChildrenSource(provider) {
  childrenSource = provider && typeof provider.listDelegateChildren === 'function'
    ? provider
    : null;
}

/**
 * 某 delegate 的星型子代理清单（G3 读取口；查无/源故障 → []，绝不崩渲染面）。
 * 夹具在场 = 恒夹具优先（harness 兼容缝，同 rows/chains 口径）。
 * @param {string} delegateId @returns {DelegateChild[]}
 */
export function listDelegateChildren(delegateId) {
  const k = String(delegateId == null ? '' : delegateId);
  if (fixtureChildren.has(k)) return fixtureChildren.get(k) || [];
  if (fixtureChildren.size > 0) return []; // 夹具态且该父无条目 = 空集（不落 provider）
  try {
    const out = childrenSource ? childrenSource.listDelegateChildren(k) : [];
    return Array.isArray(out) ? out : [];
  } catch (_) {
    return [];
  }
}

// ── harness 注入口（测试专用；产品路径零调用）────────────────────────────

/**
 * 装 stub 夹具（harness/单测专用）。rows/chains/children 均可选；chains 以 taskId 键挂，
 * children 以 parentSessionId（delegateId）键挂。
 * @param {{rows?: DelegateRow[], chains?: ChainView[], children?: Object<string, DelegateChild[]>}} [fx]
 */
export function __setFixture(fx) {
  const f = fx && typeof fx === 'object' ? fx : {};
  fixtureRows.clear();
  fixtureChains.clear();
  fixtureChildren.clear();
  for (const r of (Array.isArray(f.rows) ? f.rows : [])) {
    if (r && typeof r === 'object') fixtureRows.set(String(r.delegateId || r.taskNo || ''), r);
  }
  for (const c of (Array.isArray(f.chains) ? f.chains : [])) {
    if (c && typeof c === 'object') fixtureChains.set(String(c.taskId || ''), c);
  }
  const kids = f.children && typeof f.children === 'object' ? f.children : null;
  if (kids) {
    for (const k of Object.keys(kids)) {
      const arr = Array.isArray(kids[k]) ? kids[k].filter((c) => c && typeof c === 'object') : [];
      fixtureChildren.set(String(k), arr);
    }
  }
}

/** 清空 stub 夹具（回归默认空态）。 */
export function __resetFixture() {
  fixtureRows.clear();
  fixtureChains.clear();
  fixtureChildren.clear();
}
