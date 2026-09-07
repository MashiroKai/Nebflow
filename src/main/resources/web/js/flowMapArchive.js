// flowMapArchive.js — Flow Map 整链归档（v3，规格 20260903_flowmap-archive-panel-spec.md）。
//
// 语义（作者 2026-09-03 20:26 五条反馈，规格 §3/§4/§5/§18）：
//   ① 归档单位 = 任务链：同一次任务触发的节点按 createdAt 派发批次窗口（相邻间隔
//      ≤120s）聚簇成链；链内全部成员到达终态（completed/failed/cancelled）→ 整链
//      一起进归档（主图同帧集体淡出由 flowMapTab 的增量管线执行，本模块提供链判定
//      与 newly-completed 检测）；链未齐时终态成员保留主图（终态色卡）。
//   ② 归档面板 = 画布内右上角悬浮钮（元素选择器 .canvas-ref-select 同语言）+ mailbox
//      式链条目（倒序/独占展开/成员行/hover 联动/Esc 分层关闭/焦点归还）。
//   ③ 右侧详情面板（z 70 > 面板 60）：主图点节点与链内点成员两路触发；宽视口与归档
//      面板并排零重叠（dock-left 404px），窄视口同位浮前。
//   ④ 全局空白点击统一收起（click 判定 + >3px 拖拽豁免 + 节点/面板内部豁免）。
//   ⑤ 条目 24h TTL：按链完成时间到期清理、徽章同步减、成员从派生输入移除（防链
//      重判复活）；剩余 <60min 显示「即将过期」。
//
// 数据源（裁定④「TTL 分开」批 2026-09-07 起双源合并）：
//   ① 后端 Flow Archive（权威源，重启/刷新后仍在）：GET flow-map/archive 分批聚合
//      （服务端按显示窗 24h 过滤）→ remoteChains/remoteMembers；拉取时机 = 悬浮层
//      创建首拉 + nodeRemoved 防抖 + 面板打开刷新。
//   ② 前端派生链（在场源）：快照全量节点 + nodeRemoved 墓碑经 clusterBatches 派生——
//      仍驱动主图整链淡出动画与可见性（「主图立即消失」的当帧承担者），并补齐
//      「链刚齐、后端 sweep 在途（≤30s）」窗口的面板条目。两源批 id 同源（同一聚簇
//      算法 + 同一 createdAt 数据），面板条目按 id 去重 remote 优先。
// 节点字段 = NodePayload.buildNodeJson 单序列化点（规格 §1.4）：createdAt/completedAt/
// status/hasResult 齐备；载荷无 task 字段 → 链名第①级（task【】前缀）在有 task 时
// 才启用，产品载荷自然落到第②③级（名称公共前缀/链首名）。

import { esc, fmtTime } from './flowHelpers.js';
import { t } from './i18n.js';
import { showToast } from './modal.js';
import { renderMarkdownWithMath } from './utils.js';
import { fetchNodeResult, fetchFlowMapArchive } from './nodeData.js';

// ── 常量（规格 §3.5/§5.9）─────────────────────────────────
/** 终态集合：链齐判定与归档口径（规格 §3.1）。 */
export const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled']);

/** @param {string|undefined} st @returns {boolean} */
export function isTerminalStatus(st) {
  return TERMINAL_STATUSES.has(String(st || ''));
}

/** 派发批次窗口（§3.5）：同批 createdAt 相邻间隔 ≤120s（实测同批 ≤80s、跨批 ≥3min）。 */
const CHAIN_BATCH_MS = 120000;
/** 归档条目 TTL（§5.9）：自链完成时间起保留 24h。 */
export const ARCHIVE_TTL_MS = 86400000;
/** 「即将过期」标签窗口：剩余 <60min。 */
const TTL_TAG_WINDOW = 3600000;
/** TTL 周期检查（§5.9：30s 轮询 + 快照导入触发）。 */
const TTL_SWEEP_MS = 30000;

/** 状态图标 SVG（禁 emoji：勾/叉/横线，规格 §5.3）。 */
const ST_SVG = {
  completed: '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 6.5l2.5 2.5 4.5-5.5"/></svg>',
  failed: '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 3l6 6M9 3l-6 6"/></svg>',
  cancelled: '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 6h6"/></svg>',
};
const CHEV_SVG = '<svg viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 2l3.5 3-3.5 3"/></svg>';
// 关闭叉（替代 ✕ U+2715，2026-09-06 显示优化批：全批禁 emoji 渲染字符）
const CLOSE_SVG = '<svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true"><path d="M3 3l6 6M9 3l-6 6"/></svg>';
// 阻塞旗（替代 ⚑ U+2691，描边风格同 flowMapTab FM_STATUS_SVG.warn）
const FLAG_SVG = '<svg class="flow-blocked-flag" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3.5 1.5v9M3.5 2.5H9L7.5 4.75 9 7H3.5"/></svg>';
const ARCHIVE_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="5" x="2" y="3" rx="1"/><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"/><path d="M10 12h4"/></svg>';

/** 详情/条目状态色 class（terminal 三态有专属 SVG，活动态挂 sapphire 色类）。 */
function statusClass(st) {
  return TERMINAL_STATUSES.has(st) ? st : 'active';
}

// ══ 每项目链派生 store（§7.2，随 flowMapTab store 生命周期）═══

/**
 * @typedef {Object} ChainMember
 * @property {string} id
 * @property {string=} name
 * @property {string=} preset 节点预设名（NodePayload；未配置 → 前端空态「默认预设」）
 * @property {string[]=} plugins 分配插件名列表（NodePayload，条件序列化——非空才带）
 * @property {string=} status
 * @property {string=} description 一行描述（创建必写；2026-09-05 载荷收敛第一层）
 * @property {boolean=} hasResult 节点持有结果全文（载荷不含 result 本体——按需拉取标记）
 * @property {string=} taskPreview 存量无 description 节点的回退展示（task 首行截断）
 * @property {string=} result 兼容字段（旧载荷残留；新载荷不再下发）
 * @property {string=} task
 * @property {number=} createdAt
 * @property {number=} completedAt
 * @property {string[]=} in barrier 输入（NodePayload，恒带数组）
 * @property {string[]=} deps 依赖（NodePayload，条件序列化）
 * @property {string=} out 出边目标（NodePayload；null = 无出边）
 * @property {any=} blockedFeedback blocked 结构化反馈（NodePayload，blocked 态）
 * @property {number=} blockCount 阻断轮数（NodePayload）
 * @property {number=} ttlLeftSec 归档保留剩余秒（NodePayload 恒带键，null = 无 TTL）
 * @property {boolean=} hasWorktree 配独立 worktree（NodePayload 恒带）
 * @property {string=} worktree worktree 派生名（NodePayload；=分支名，未配 → null）
 * @property {{maxRounds:number, verify:string, enabled:boolean}=} loop LoopNode 配置（条件对象）
 * @property {number=} loopRound loop 运行态·轮数（条件：>0 才带）
 * @property {string=} loopPhase loop 运行态·阶段 worker/verify（条件：running 才有）
 * @property {string=} loopLastVerdict loop 运行态·最近 FAIL 摘要（条件：非空才带）
 * @property {boolean=} merge 合并节点标记（条件：true 才带）
 * @property {boolean=} notifyDispatcher 终态回流通知分发器（条件：true 才带）
 */

/**
 * @typedef {Object} Chain
 * @property {string} id
 * @property {string} title
 * @property {ChainMember[]} members 完成时间倒序（冻结副本）
 * @property {number} nodeCount
 * @property {string} status 最坏优先 failed > cancelled > completed
 * @property {number} completedAt 成员最晚完成时间
 */

/**
 * @typedef {Object} ArchiveStore
 * @property {Chain[]} chains 已归档链（面板条目，冻结成员；completedAt 倒序）
 * @property {Chain[]} bufferChains 链未齐链（≥1 终态成员；面板不显、徽章不计）
 * @property {Map<string, Chain>} chainOf nodeId → 所属链（含 buffer）
 * @property {Set<string>} archivedIds 已归档链成员 id（主图不可见集）
 * @property {Set<string>} expiredIds TTL 到期链成员 id（派生输入排除，防复活 §18-C10）
 * @property {Map<string, ChainMember>} tombstones nodeRemoved 出库的终态节点（§7.2：
 *           终态事实保留参与链齐判定；快照重现即清除）
 * @property {Map<string, ChainMember>} input 最近一次派生输入（详情/链名数据源）
 * @property {Map<string, Chain>} remoteChains 后端 Flow Archive 批次链（裁定④：
 *           归档面板权威数据源，重启/刷新后仍在；链 id 与派生链同源）
 * @property {Map<string, ChainMember>} remoteMembers 后端归档成员（详情/链名查找兜底）
 * @property {boolean} remoteFetched 已成功首拉（悬浮层创建时触发一次）
 * @property {boolean} remoteInflight 拉取在途（重入丢弃）
 * @property {number=} remoteTimer nodeRemoved 防抖拉取计时器
 * @property {string | null} expandedEntry 面板展开条目 chainId
 */

/** @type {Map<string, ArchiveStore>} */
const stores = new Map();

/** @param {string} project @returns {ArchiveStore} */
function storeOf(project) {
  let s = stores.get(project);
  if (!s) {
    s = {
      chains: [],
      bufferChains: [],
      chainOf: new Map(),
      archivedIds: new Set(),
      expiredIds: new Set(),
      tombstones: new Map(),
      input: new Map(),
      remoteChains: new Map(),
      remoteMembers: new Map(),
      remoteFetched: false,
      remoteInflight: false,
      remoteTimer: 0,
      expandedEntry: null,
    };
    stores.set(project, s);
  }
  return s;
}

/** 项目视图关闭时清 store（flowMapTab canvas-tab-closed 调用）。 */
export function dropStore(project) {
  stores.delete(project);
}

/** 白盒访问（供 flowMapTab 判定/测试脚本检查面板基线；勿在渲染路径外改写）。 */
export function getStore(project) {
  return storeOf(project);
}

const byCreated = (/** @type {ChainMember} */ a, /** @type {ChainMember} */ b) =>
  (a.createdAt || 0) - (b.createdAt || 0);
const byCompletedDesc = (/** @type {ChainMember} */ a, /** @type {ChainMember} */ b) =>
  (b.completedAt || 0) - (a.completedAt || 0);

/**
 * 链名推导（§5.3 确定性三级）：① ≥2 成员 task 含【…】且公共前缀 ≥2 字（产品载荷
 * 暂无 task 字段，出现时自动启用）→ ② 成员名去角色前缀后公共前缀 ≥3 字 → ③ 链首
 * （createdAt 最早）节点名。
 * @param {ChainMember[]} members createdAt 升序
 * @returns {string}
 */
function chainTitleOf(members) {
  const brackets = members
    .map((m) => { const mt = String(m.task || '').match(/【([^】]+)】/); return mt ? mt[1] : null; })
    .filter((/** @type {string | null} */ x) => !!x);
  if (brackets.length >= 2) {
    const pre = commonPrefix(/** @type {string[]} */ (brackets)).replace(/[\s·:：\-—/\d]+$/u, '').trim();
    if (pre.length >= 2) return pre;
  }
  const names = members.map((m) => String(m.name || '').replace(/^(诊断|修复|合并|实施|验收|部署|取证)-/, ''));
  const pre = commonPrefix(names).replace(/[\s\-—·]+$/u, '').trim();
  if (pre.length >= 3) return pre;
  return String(members[0]?.name || members[0]?.id || '');
}

/** @param {string[]} ss @returns {string} */
function commonPrefix(ss) {
  if (!ss.length) return '';
  let pre = ss[0];
  for (const s of ss.slice(1)) {
    while (pre && !s.startsWith(pre)) pre = pre.slice(0, -1);
  }
  return pre;
}

/** 链状态 = 成员最坏态（failed > cancelled > completed，§5.3）。 */
function chainStatusOf(members) {
  if (members.some((m) => m.status === 'failed')) return 'failed';
  if (members.some((m) => m.status === 'cancelled')) return 'cancelled';
  return 'completed';
}

/**
 * @param {string} id
 * @param {ChainMember[]} members createdAt 升序的当前输入成员
 * @returns {Chain}
 */
function buildChain(id, members) {
  const byCreatedList = members.slice().sort(byCreated);
  const frozen = byCreatedList.map((m) => ({ ...m }));
  const sorted = frozen.slice().sort(byCompletedDesc);
  return {
    id,
    title: chainTitleOf(byCreatedList),
    members: sorted,
    nodeCount: sorted.length,
    status: chainStatusOf(sorted),
    completedAt: sorted.reduce((mx, m) => Math.max(mx, m.completedAt || 0), 0),
  };
}

/**
 * 批次聚簇（§3.5 算法单点）：成员按 createdAt 升序、相邻间隔 ≤CHAIN_BATCH_MS
 * 归一批。refreshChains（主图归档判定）与 deriveArchivedIds（任务列表主图同源
 * 过滤，2026-09-05 作者裁定）共用本函数——链判定口径严格同源，消费侧不另写派生。
 * @param {ChainMember[]} members 全量成员（活动 + 终态，顺序不限）
 * @returns {{id: string, members: ChainMember[]}[]} 批次数组（批内 createdAt 升序）
 */
function clusterBatches(members) {
  const sorted = members.slice().sort(byCreated);
  /** @type {{id: string, members: ChainMember[]}[]} */
  const batches = [];
  let cur = null;
  let lastT = -Infinity;
  for (const n of sorted) {
    const ct = n.createdAt || 0;
    if (!cur || ct - lastT > CHAIN_BATCH_MS) {
      cur = { id: 'chain-' + n.id, members: [] };
      batches.push(cur);
    }
    cur.members.push(n);
    lastT = ct;
  }
  return batches;
}

/**
 * 链派生 + 归档判定（§7.2 单点函数；P2 triggerId 落地后仅此处换精确口径）。
 * 返回本次新完成（含重建）的链——调用方据此驱动整链同帧退场动画。
 * @param {string} project
 * @param {ChainMember[]=} fmNodes 快照全量节点（活动 + 终态）
 * @returns {Chain[]}
 */
export function refreshChains(project, fmNodes) {
  const s = storeOf(project);
  const input = new Map();
  for (const n of fmNodes || []) {
    input.set(n.id, n);
    s.tombstones.delete(n.id); // 快照重现 → 出库墓碑清除
  }
  for (const [id, tn] of s.tombstones) {
    if (!input.has(id)) input.set(id, tn);
  }
  s.input = input;

  // 批次聚簇（§3.5，单点 clusterBatches）：全量节点剔除 TTL 到期后按 createdAt 归批
  const batches = clusterBatches(
    Array.from(input.values()).filter((n) => !s.expiredIds.has(n.id)));

  const known = new Map(s.chains.map((c) => [c.id, c]));
  /** @type {Chain[]} */
  const buffers = [];
  /** @type {Chain[]} */
  const newChains = [];
  s.chainOf = new Map();
  for (const b of batches) {
    const allTerm = b.members.every((m) => TERMINAL_STATUSES.has(String(m.status || '')));
    if (!allTerm) {
      if (b.members.some((m) => TERMINAL_STATUSES.has(String(m.status || '')))) {
        buffers.push(buildChain(b.id, b.members)); // 链未齐（buffer，活数据派生）
      }
      continue;
    }
    const prev = known.get(b.id);
    // 成员集不变（id 集相等，与序无关）→ 幂等跳过（§7.2）：冻结条目按完成时间
    // 倒序、当前批按创建升序，逐位对比会把每条已归档链误判为重建（每次刷新重复
    // toast + 已归档成员被退场动画复活回主图）。成员集变化才算重建（迟到成员并入）。
    const prevIds = prev ? new Set(prev.members.map((m) => m.id)) : null;
    const identical = !!prevIds && prevIds.size === b.members.length
      && b.members.every((m) => prevIds.has(m.id));
    if (identical) continue; // 冻结条目原样保留（成员被服务端 TTL 出库由墓碑补位）
    const chain = buildChain(b.id, b.members);
    if (prev) Object.assign(prev, chain); // 迟到成员并入已归档链（重建条目，不重复）
    else s.chains.push(chain);
    newChains.push(chain);
  }
  for (const c of s.chains) {
    for (const m of c.members) {
      if (!s.chainOf.has(m.id)) s.chainOf.set(m.id, c);
    }
  }
  for (const c of buffers) {
    for (const m of c.members) s.chainOf.set(m.id, c);
  }
  s.bufferChains = buffers;
  s.archivedIds = new Set();
  for (const c of s.chains) {
    for (const m of c.members) s.archivedIds.add(m.id);
  }
  s.chains.sort((a, b) => (b.completedAt || 0) - (a.completedAt || 0) || a.title.localeCompare(b.title));
  return newChains;
}

/**
 * 任务列表主图同源过滤（2026-09-05 作者裁定 12:59）：纯派生、零 store 副作用——
 * 给定单项目节点全集，返回「整链已归档」节点 id 集。批次判定与 refreshChains
 * 共用 clusterBatches 单点：批内全终态（TERMINAL_STATUSES）→ 整链隐藏（含该链
 * 已完成成员）；链内任一非终态（含 blocked/held/wiring/pending/running）→ 整链
 * 保留。与主图可见判定（isVisibleNode）的差异仅 store 记忆面（主图冻结已归档链
 * 可跨快照保留，本函数只按当前全集派生）——节点全集相同时结果一致。
 * @param {ChainMember[]=} fmNodes 快照全量节点（活动 + 终态，单项目）
 * @returns {Set<string>} 已归档（应隐藏）节点 id 集
 */
export function deriveArchivedIds(fmNodes) {
  const archived = new Set();
  for (const b of clusterBatches(Array.from(fmNodes || []))) {
    if (b.members.every((m) => TERMINAL_STATUSES.has(String(m.status || '')))) {
      for (const m of b.members) archived.add(m.id);
    }
  }
  return archived;
}

/**
 * TTL 清理（§5.9）：到期链从面板移除 + 徽章同步减 + 成员从派生输入排除（§18-C10）。
 * 裁定④扩展：remoteChains 同款清理（服务端已按 24h 过滤，本地时钟走动期间兜底）。
 * @param {string} project
 * @param {number=} now
 * @returns {number} 移除条数
 */
export function purgeExpired(project, now = Date.now()) {
  const s = storeOf(project);
  let removed = 0;
  const keep = s.chains.filter((c) => now - (c.completedAt || 0) <= ARCHIVE_TTL_MS);
  removed += s.chains.length - keep.length;
  for (const c of s.chains) {
    if (keep.indexOf(c) !== -1) continue;
    for (const m of c.members) {
      s.expiredIds.add(m.id);
      s.tombstones.delete(m.id);
    }
    if (s.expandedEntry === c.id) s.expandedEntry = null;
  }
  s.chains = keep;
  for (const [cid, c] of Array.from(s.remoteChains)) {
    if (now - (c.completedAt || 0) <= ARCHIVE_TTL_MS) continue;
    for (const m of c.members) {
      s.expiredIds.add(m.id); // 防派生复活（同 §18-C10）
      s.remoteMembers.delete(m.id);
    }
    s.remoteChains.delete(cid);
    if (s.expandedEntry === cid) s.expandedEntry = null;
    removed++;
  }
  return removed;
}

// ── 后端 Flow Archive 数据源（裁定④「TTL 分开」批）────────────────

/** 后端批次 → Chain（复用 buildChain 冻结/链名推导/最坏态单点；成员顺序由 buildChain 自理）。 */
function remoteChainOf(batch) {
  const members = Array.isArray(batch?.members) ? batch.members : [];
  const chain = buildChain(String(batch?.id || ''), members);
  if (typeof batch?.completedAt === 'number' && batch.completedAt > 0) {
    chain.completedAt = batch.completedAt;
  }
  return chain;
}

/** 拉取后端归档批次 → remoteChains/remoteMembers（重启后归档面板的全量数据源）。
 *  失败（未挂载/网络）静默保留现状，下次触发重试；成功后重渲染该项目全部存活层。 */
async function fetchRemoteChains(project) {
  const s = storeOf(project);
  if (s.remoteInflight) return;
  s.remoteInflight = true;
  try {
    const batches = await fetchFlowMapArchive(project);
    if (batches) {
      const now = Date.now();
      s.remoteChains = new Map();
      s.remoteMembers = new Map();
      for (const b of batches) {
        const chain = remoteChainOf(b);
        if (!chain.id || !chain.members.length) continue;
        if (now - (chain.completedAt || 0) > ARCHIVE_TTL_MS) continue; // 本地时钟兜底
        s.remoteChains.set(chain.id, chain);
        for (const m of chain.members) s.remoteMembers.set(m.id, m);
      }
      s.remoteFetched = true;
      for (const ctx of Array.from(layerCtxs)) {
        if (ctx.project !== project || !ctx.layer.isConnected) continue;
        renderArchiveUi(ctx);
      }
    }
  } finally {
    s.remoteInflight = false;
  }
}

/** nodeRemoved 出库后防抖刷新（链级 sweep 按成员逐条广播，合并为一次拉取）。
 *  挂 recordNodeRemoved 内——flowMapTab 零改动。 */
function scheduleRemoteRefresh(project) {
  const s = storeOf(project);
  if (s.remoteTimer) clearTimeout(s.remoteTimer);
  s.remoteTimer = setTimeout(() => {
    s.remoteTimer = 0;
    // 该项目已无存活悬浮层（视图已关）→ 不拉（下次打开时首拉）
    for (const ctx of layerCtxs) {
      if (ctx.project === project && ctx.layer.isConnected) { fetchRemoteChains(project); return; }
    }
  }, 1500);
}

/** 面板条目并集（裁定④）：remoteChains（权威源）∪ 派生 chains（sweep 在途窗口
 *  补齐）——按链 id 去重 remote 优先；completedAt 倒序（与 refreshChains 排序同款）。 */
function panelChains(s) {
  const out = new Map();
  for (const c of s.chains) out.set(c.id, c);
  for (const c of s.remoteChains.values()) out.set(c.id, c); // remote 覆盖同 id
  return Array.from(out.values())
    .sort((a, b) => (b.completedAt || 0) - (a.completedAt || 0) || a.title.localeCompare(b.title));
}

/**
 * nodeRemoved 出库墓碑（§7.2）：终态节点出库保留终态事实参与链齐判定；
 * 非终态出库直接消失。裁定④：出库 = 后端归档落盘信号 → 防抖刷新 remote。
 */
export function recordNodeRemoved(project, node) {
  const s = storeOf(project);
  if (node && isTerminalStatus(node.status)) s.tombstones.set(node.id, node);
  scheduleRemoteRefresh(project);
}

/** 主图可见判定（§3.1）：可见 = 非已归档链成员 且 非 TTL 到期链成员。 */
export function isVisibleNode(project, node) {
  const s = stores.get(project);
  if (!s) return true;
  return !s.archivedIds.has(node.id) && !s.expiredIds.has(node.id);
}

/** nodeId → 所属链（含链未齐链 + 后端归档链；详情 meta 用）。 */
export function chainOfNode(project, nodeId) {
  const s = stores.get(project);
  if (!s) return null;
  const derived = s.chainOf.get(nodeId);
  if (derived) return derived;
  for (const rc of s.remoteChains.values()) {
    if (rc.members.some((m) => m.id === nodeId)) return rc;
  }
  return null;
}

// ══ 悬浮层 UI（悬浮钮 + 归档面板 + 右侧详情，§4/§5）═══

/**
 * @typedef {Object} LayerCtx
 * @property {string} project
 * @property {HTMLElement} container 渲染容器（卡片宿主，hover 联动定位用）
 * @property {HTMLElement} layer
 * @property {HTMLElement} fab
 * @property {HTMLElement} badge
 * @property {HTMLElement} panel
 * @property {HTMLElement} panelBody
 * @property {HTMLElement} detail
 * @property {HTMLElement} detailTitle
 * @property {HTMLElement} detailBody
 * @property {boolean} panelOpen
 * @property {number=} closeTimer
 * @property {number=} detailCloseTimer
 * @property {number} detailRenderSeq 详情渲染代：每次 renderDetail 递增；全文升级
 *           响应按代守卫（换节点/关详情后的迟到响应直接丢弃）
 */

/** @type {Set<LayerCtx>} 全部存活悬浮层（document 级管线遍历用）。 */
const layerCtxs = new Set();
/** @type {WeakMap<HTMLElement, HTMLElement>} host → layer（全量渲染 innerHTML 抹除后重建） */
const layerByHost = new WeakMap();
/** @type {WeakMap<HTMLElement, LayerCtx>} layer → ctx（避免在 DOM 元素上挂自定义属性） */
const ctxByLayer = new WeakMap();
/** 面板唯一 id 序号（多视图并存时 aria-controls 不串）。 */
let layerSeq = 0;
/** 悬浮层 DOM 模板（class 挂 fm- 前缀；testid 对齐原型回归选择器）。 */
const LAYER_HTML = `
  <div class="fm-float-layer">
    <button class="fm-fab" type="button" data-testid="archive-toggle" aria-expanded="false">
      ${ARCHIVE_ICON}
      <span class="fm-archive-badge hidden" data-testid="archive-badge"></span>
    </button>
    <div class="fm-archive-panel" role="region" aria-label="${esc(t('flowmap.archive.title'))}" data-testid="archive-panel" hidden>
      <div class="fm-panel-head">
        <span class="fm-panel-title" title="${esc(t('flowmap.archive.ttlTitle'))}">${esc(t('flowmap.archive.title'))}</span>
        <span class="fm-panel-count"></span>
        <button class="fm-panel-close" type="button" aria-label="${esc(t('flowmap.archive.close'))}">${CLOSE_SVG}</button>
      </div>
      <div class="fm-panel-body"></div>
    </div>
    <div class="fm-detail" role="region" aria-label="${esc(t('flowmap.archive.detailLabel'))}" data-testid="detail-panel" hidden>
      <div class="fm-detail-head">
        <span class="fm-detail-st"></span>
        <span class="fm-detail-title"></span>
        <button class="fm-detail-close" type="button" aria-label="${esc(t('flowmap.archive.detailClose'))}">${CLOSE_SVG}</button>
      </div>
      <div class="fm-detail-body"></div>
    </div>
  </div>`;

function prefersReducedMotion() {
  return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
}

/**
 * 确保 host 上存在悬浮层并绑定项目上下文（renderFlowMap 全量/增量路径后调用）。
 * host = 就地视图 .flowmap-view-body（自身定位上下文）或 legacy .canvas-tab-pane。
 * @param {HTMLElement} host position:relative 定位上下文
 * @param {HTMLElement} container 渲染容器（.team-scroll / .flowmap-view-body）
 * @param {string} project
 * @returns {LayerCtx}
 */
export function ensureArchiveLayer(host, container, project) {
  let layer = layerByHost.get(host);
  if (!layer || !layer.isConnected || layer.parentElement !== host) {
    if (layer) layer.remove();
    const holder = document.createElement('div');
    holder.innerHTML = LAYER_HTML;
    layer = /** @type {HTMLElement} */ (holder.firstElementChild);
    // aria-controls 指向本层面板（多视图并存 id 唯一）
    const panelId = `fm-archive-panel-${++layerSeq}`;
    const panelEl = layer.querySelector('.fm-archive-panel');
    if (panelEl) panelEl.id = panelId;
    layer.querySelector('.fm-fab')?.setAttribute('aria-controls', panelId);
    host.appendChild(layer);
    layerByHost.set(host, layer);
  }
  let ctx = ctxByLayer.get(layer);
  if (!ctx) {
    ctx = {
      project,
      container,
      layer,
      fab: /** @type {HTMLElement} */ (layer.querySelector('.fm-fab')),
      badge: /** @type {HTMLElement} */ (layer.querySelector('.fm-archive-badge')),
      panel: /** @type {HTMLElement} */ (layer.querySelector('.fm-archive-panel')),
      panelBody: /** @type {HTMLElement} */ (layer.querySelector('.fm-panel-body')),
      detail: /** @type {HTMLElement} */ (layer.querySelector('.fm-detail')),
      detailTitle: /** @type {HTMLElement} */ (layer.querySelector('.fm-detail-title')),
      detailBody: /** @type {HTMLElement} */ (layer.querySelector('.fm-detail-body')),
      panelOpen: false,
      detailRenderSeq: 0,
    };
    ctxByLayer.set(layer, ctx);
    bindLayerEvents(ctx);
    layerCtxs.add(ctx);
    observeHost(ctx); // D-QA1：host 尺寸落定回调重判 dock 形态（见文件尾 RO 块）
  }
  ctx.project = project;
  ctx.container = container;
  renderArchiveUi(ctx);
  // 裁定④：悬浮层创建触发后端归档首拉（每项目一次；失败下渲染重试）
  if (!storeOf(project).remoteFetched) fetchRemoteChains(project);
  return ctx;
}

/** 按 (容器, 项目) 找到（必要时创建）该视图的悬浮层上下文。 */
function ctxFor(container, project) {
  const host = container.classList.contains('flowmap-view-body')
    ? container
    : (container.closest('.canvas-tab-pane') || container.parentElement || container);
  return ensureArchiveLayer(/** @type {HTMLElement} */ (host), container, project);
}

/** flowMapTab 渲染管线统一入口：渲染后同步悬浮层（徽章/面板/详情计数）。 */
export function syncArchiveUi(container, project, opts = {}) {
  const ctx = ctxFor(container, project);
  renderArchiveUi(ctx, opts);
  return ctx;
}

/** 外部开详情（主图节点点击；§5.8 触发①）。 */
export function openDetailFor(container, project, nodeId) {
  openDetail(ctxFor(container, project), nodeId);
}

function updateBadge(/** @type {LayerCtx} */ ctx, /** @type {boolean} */ pulse) {
  const s = storeOf(ctx.project);
  const n = panelChains(s).length; // 徽章口径 = 面板链条目数（§4.3；裁定④ remote∪派生；TTL 清理同步减）
  ctx.badge.textContent = n > 99 ? '99+' : String(n);
  ctx.badge.classList.toggle('hidden', n === 0);
  const label = t('flowmap.archive.button', { n: String(n) });
  ctx.fab.setAttribute('aria-label', label);
  ctx.fab.title = label;
  if (pulse && !prefersReducedMotion()) {
    ctx.badge.classList.remove('pulse');
    void ctx.badge.getBoundingClientRect();
    ctx.badge.classList.add('pulse');
  }
}

/** @param {ChainMember} m @returns {string} 成员行（§5.5：状态/名称/元数据/时间/›） */
function memberHtml(m) {
  const st = String(m.status || '');
  // 载荷收敛（2026-09-05）：预览行 = description（回退 taskPreview）——载荷无 result
  const preview = String(m.description || m.taskPreview || '').slice(0, 60);
  // Agent 退役（node-flowmap-slim）：元数据槽 = preset 常显（未配置 → 空态「默认预设」），
  // plugins 有则并列（` · ` 分隔；超长 ellipsis 收边）。复用原 agent 槽位样式（改名义
  // .fm-member-meta，flowMap.css 同规则改名），存量节点零 preset/plugins → 空态兜底。
  const presetText = String(m.preset || '') || t('flowmap.presetDefault');
  const pluginsText = Array.isArray(m.plugins) ? m.plugins.join(', ') : '';
  const metaText = pluginsText ? `${presetText} · ${pluginsText}` : presetText;
  return `
    <div class="fm-member" role="button" tabindex="0" data-node="${esc(m.id)}" title="${esc(String(m.name || ''))} · ${esc(t('flowmap.archive.openMemberHint'))}">
      <div class="fm-member-row">
        <span class="fm-entry-st ${statusClass(st)}">${ST_SVG[/** @type {'completed'} */ (st)] || ''}</span>
        <span class="fm-member-name">${esc(String(m.name || m.id))}</span>
        <span class="fm-member-meta" title="${esc(metaText)}">${esc(metaText)}</span>
        <span class="fm-member-time">${esc(fmtTime(m.completedAt))}</span>
        <span class="fm-member-go">${CHEV_SVG}</span>
      </div>
      ${preview ? `<div class="fm-member-preview">${esc(preview)}</div>` : ''}
    </div>`;
}

/** @param {Chain} c @returns {string} 链条目（§5.3：徽章/链名/N 节点/时间/▾ + 摘要 + 成员区） */
function entryHtml(c) {
  const last = c.members[0];
  // 载荷收敛（2026-09-05）：预览行 = description（回退 taskPreview）——载荷无 result
  const preview = String(last?.description || last?.taskPreview || '').slice(0, 60);
  const remain = (c.completedAt || 0) + ARCHIVE_TTL_MS - Date.now();
  const ttlTag = remain > 0 && remain < TTL_TAG_WINDOW
    ? `<span class="fm-entry-ttl" title="${esc(t('flowmap.archive.ttlTitle'))}">${esc(t('flowmap.archive.expiringSoon'))}</span>`
    : '';
  return `
  <div class="fm-entry" role="button" tabindex="0" data-chain-id="${esc(c.id)}" data-ts="${c.completedAt || 0}"
       aria-expanded="false" data-testid="archive-entry" title="${esc(c.title)}">
    <div class="fm-entry-row1">
      <span class="fm-entry-st ${statusClass(c.status)}">${ST_SVG[/** @type {'completed'} */ (c.status)] || ''}</span>
      <span class="fm-entry-name">${esc(c.title)}</span>
      <span class="fm-entry-nodes">${esc(t('flowmap.archive.nodes', { n: String(c.nodeCount) }))}</span>
      <span class="fm-entry-time">${esc(fmtTime(c.completedAt))}</span>
      ${ttlTag}
      <span class="fm-entry-chev">${CHEV_SVG}</span>
    </div>
    ${preview ? `<div class="fm-entry-preview">${esc(preview)}</div>` : ''}
    <div class="fm-entry-members">${c.members.map(memberHtml).join('')}</div>
  </div>`;
}

/** 面板渲染（§5.2/§5.3/§5.4/§7.3；展开态跨渲染保留 C13；裁定④ 条目=remote∪派生）。 */
function renderArchiveUi(/** @type {LayerCtx} */ ctx, opts = {}) {
  const s = storeOf(ctx.project);
  const entries = panelChains(s);
  const totalNodes = entries.reduce((m, c) => m + c.nodeCount, 0);
  ctx.panelBody.innerHTML = entries.length
    ? entries.map(entryHtml).join('')
    : `<div class="fm-panel-empty">${esc(t('flowmap.archive.empty'))}</div>`;
  if (s.expandedEntry) {
    const el = ctx.panelBody.querySelector(`.fm-entry[data-chain-id="${CSS.escape(s.expandedEntry)}"]`);
    if (el) {
      el.classList.add('expanded');
      el.setAttribute('aria-expanded', 'true');
    }
  }
  if (opts.flashChainIds) {
    for (const cid of /** @type {string[]} */ (opts.flashChainIds)) {
      const el = ctx.panelBody.querySelector(`.fm-entry[data-chain-id="${CSS.escape(cid)}"]`);
      if (el) {
        el.classList.add('flash');
        el.scrollIntoView({ block: 'nearest', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
      }
    }
  }
  const countEl = ctx.panel.querySelector('.fm-panel-count');
  if (countEl) countEl.textContent = t('flowmap.archive.count', { n: String(entries.length), m: String(totalNodes) });
  updateBadge(ctx, !!opts.pulse);
  updateDetailDock(ctx); // 每次渲染重判 host 口径 dock 形态（取代媒体查询的自动跟随）
}

// ── 开合（§5.7/§6/§9.2）──────────────────────────────────
function openPanel(/** @type {LayerCtx} */ ctx) {
  if (ctx.closeTimer) { clearTimeout(ctx.closeTimer); ctx.closeTimer = 0; }
  fetchRemoteChains(ctx.project); // 裁定④：面板打开刷新后端归档（幂等 in-flight 丢弃）
  ctx.panel.hidden = false;
  ctx.panel.classList.remove('closing');
  void ctx.panel.getBoundingClientRect(); // 强制起始态生效
  ctx.panel.classList.add('open');
  ctx.fab.classList.add('active');
  ctx.fab.setAttribute('aria-expanded', 'true');
  ctx.panelOpen = true;
  updateDetailDock(ctx); // §5.8b：面板开 → 详情左靠并排
}

function closePanel(/** @type {LayerCtx} */ ctx, opts = {}) {
  if (!ctx.panelOpen && ctx.panel.hidden) return;
  ctx.panelOpen = false;
  ctx.panel.classList.remove('open');
  ctx.fab.classList.remove('active');
  ctx.fab.setAttribute('aria-expanded', 'false');
  updateDetailDock(ctx);
  if (prefersReducedMotion()) {
    ctx.panel.hidden = true;
    ctx.panel.classList.remove('closing');
  } else {
    ctx.panel.classList.add('closing');
    if (ctx.closeTimer) clearTimeout(ctx.closeTimer);
    ctx.closeTimer = setTimeout(() => {
      ctx.panel.hidden = true;
      ctx.panel.classList.remove('closing');
    }, 175);
  }
  if (!opts.keepFocus) ctx.fab.focus({ preventScroll: true }); // §5.7 焦点归还悬浮钮
}

function togglePanel(/** @type {LayerCtx} */ ctx) {
  if (ctx.panelOpen) closePanel(ctx, { keepFocus: true });
  else openPanel(ctx);
}

// ── 右侧详情（§5.8/§5.8b）────────────────────────────────
const STATUS_KEY = {
  wiring: 'flowmap.st.wiring',
  pending: 'flowmap.st.pending',
  running: 'flowmap.run',
  blocked: 'flowmap.blocked',
  completed: 'flowmap.done',
  failed: 'flowmap.fail',
  cancelled: 'flowmap.st.cancelled',
};

function statusLabel(st) {
  const key = STATUS_KEY[st];
  return key ? t(key) : String(st || '');
}

/** 并排最小 host 宽（§5.8b 2026-09-04 修订，host 口径非视口）：380 卡片 + 8 间距 + 360 dock + 16+16 边距。 */
const DOCK_MIN_HOST_W = 780;

function updateDetailDock(/** @type {LayerCtx} */ ctx) {
  const host = ctx.layer.parentElement;
  const canDock = !!host && host.clientWidth >= DOCK_MIN_HOST_W;
  ctx.detail.classList.toggle('dock-left', ctx.panelOpen && canDock); // §5.8b：面板同开且 host 足宽 → dock-left 404px
}

function openDetail(/** @type {LayerCtx} */ ctx, /** @type {string} */ nodeId) {
  const s = storeOf(ctx.project);
  // 裁定④：派生输入（快照+墓碑）优先，后端归档成员兜底（重启后归档详情可开）
  const n = s.input.get(nodeId) || s.remoteMembers.get(nodeId);
  if (!n) return;
  renderDetail(ctx, n);
  updateDetailDock(ctx);
  if (!ctx.detail.hidden && ctx.detail.classList.contains('open')) return; // 已开 → 原位换内容
  if (ctx.detailCloseTimer) { clearTimeout(ctx.detailCloseTimer); ctx.detailCloseTimer = 0; }
  ctx.detail.hidden = false;
  ctx.detail.classList.remove('closing');
  void ctx.detail.getBoundingClientRect();
  ctx.detail.classList.add('open');
}

function closeDetail(/** @type {LayerCtx} */ ctx) {
  if (ctx.detail.hidden) return;
  ctx.detail.classList.remove('open');
  if (prefersReducedMotion()) {
    ctx.detail.hidden = true;
    ctx.detail.classList.remove('closing');
  } else {
    ctx.detail.classList.add('closing');
    if (ctx.detailCloseTimer) clearTimeout(ctx.detailCloseTimer);
    ctx.detailCloseTimer = setTimeout(() => {
      ctx.detail.hidden = true;
      ctx.detail.classList.remove('closing');
    }, 175);
  }
}

/** @param {string} text @returns {string} 非 blocked 结果正文渲染（markdown 优先，
 *  渲染异常纯文本兜底 §5.8；同步渲染与全文升级换装共用，形态逻辑单点）。 */
function resultInnerHtml(/** @type {string} */ text) {
  try {
    return renderMarkdownWithMath(text) || `<span class="md-empty">${esc(t('flowmap.archive.noResult'))}</span>`;
  } catch (_) {
    return `<pre>${esc(text)}</pre>`;
  }
}

// ── 详情完整配置（2026-09-07 节点详情完整配置批）────────────────────
// 详情卡 = 完整节点配置（作者 2026-09-07 口径：节点卡=预览，详情卡=完整配置）。
// 三区在 meta/desc/blocked 之后、task/result 之前：配置（preset/plugins）→ 拓扑
// （in/out/deps）→ 执行形态（worktree/loop/merge/notifyDispatcher，有则显无则不
// 占位）。字段全部已在 NodePayload 载荷（条件字段缺键 = 非命中，防御不渲染）。

/** @param {number} sec @returns {string} TTL 剩余秒 → 紧凑时长（23h 12m / 45m / 30s；zh/en 同形数字式）。 */
function fmtTtl(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${s}s`;
}

/**
 * KV 栅格（label 列自适应最宽标签——zh/en 双语言不设定宽；value 列防长 id 撑出）。
 * @param {[string, string, boolean?][]} rows [label, valHtml(已 esc), muted?]
 * @returns {string}
 */
function kvHtml(rows) {
  return `<div class="fm-detail-kv">${rows.map(([l, v, m]) =>
    `<span class="fm-detail-kv-label">${esc(l)}</span><span class="fm-detail-kv-val${m ? ' muted' : ''}">${v}</span>`).join('')}</div>`;
}

function renderDetail(/** @type {LayerCtx} */ ctx, /** @type {ChainMember} */ n) {
  const seq = ++ctx.detailRenderSeq; // 全文升级响应竞态防护（见 upgradeDetailResult）
  const st = String(n.status || '');
  const stEl = ctx.detail.querySelector('.fm-detail-st');
  if (stEl) {
    stEl.className = 'fm-detail-st ' + statusClass(st);
    stEl.innerHTML = ST_SVG[/** @type {'completed'} */ (st)] || '';
  }
  ctx.detailTitle.textContent = String(n.name || n.id);
  // 描述区块（2026-09-06 显示优化批）：description 已在载荷（flowmap-slim 契约，
  // 节点卡同数据源）——本批只补渲染链：元数据区在前（名/描述/状态/时间），结果
  // 全文区在后。存量节点无 description → 区块整体不渲染（零 mock、零空态占位）。
  const descText = String(n.description || '');
  const chain = chainOfNode(ctx.project, n.id);
  const chainLine = chain
    ? `${esc(t('flowmap.archive.chain'))}：${esc(chain.title)}（${esc(t('flowmap.archive.nodes', { n: String(chain.nodeCount) }))} · ${esc(chain.members.every((m) => TERMINAL_STATUSES.has(String(m.status || ''))) ? t('flowmap.archive.chainArchived') : t('flowmap.archive.chainRetained'))}）`
    : '';
  // TTL（2026-09-07 完整配置批）：ttlLeftSec 恒带键（null = 无 TTL；仅终态节点
  // 有值）——有值显剩余/已到期，无值不占位。
  const ttlSec = (typeof n.ttlLeftSec === 'number' && isFinite(n.ttlLeftSec)) ? n.ttlLeftSec : null;
  const ttlLine = ttlSec === null ? '' : `TTL · ${esc(ttlSec > 0
    ? t('flowmap.detail.ttlLeft', { t: fmtTtl(ttlSec) })
    : t('flowmap.detail.ttlExpired'))}`;
  // meta 区：状态/id + 时间 + TTL + 所属链（preset/plugins 移入「配置」分区完整列出，
  // 成员行/节点卡的预览副行口径不变——详情卡承载完整配置，meta 回归纯元数据）。
  const metaRows = [
    `${esc(statusLabel(st))} · ${esc(n.id)}`,
    `${esc(t('flowmap.archive.created'))} ${esc(fmtTime(n.createdAt))}${n.completedAt ? ` · ${esc(t('flowmap.archive.completed'))} ${esc(fmtTime(n.completedAt))}` : ''}`,
    ttlLine,
    chainLine,
  ].filter(Boolean).join('<br>');
  // 配置区（核心诉求）：preset 恒带可 null（空态沿用「默认预设」口径）；plugins
  // 条件字段非空才有——全部列出，未分配 muted 空态。
  const presetText = String(n.preset || '') || t('flowmap.presetDefault');
  const plugins = Array.isArray(n.plugins) ? n.plugins : [];
  const configHtml = `
    <div class="fm-detail-sec">${esc(t('flowmap.detail.secConfig'))}</div>
    ${kvHtml([
      [t('flowmap.detail.preset'), esc(presetText), !n.preset],
      [t('flowmap.detail.plugins'), plugins.length ? esc(plugins.join(', ')) : esc(t('flowmap.detail.none')), !plugins.length],
    ])}`;
  // 拓扑区：in 恒带数组（空 → muted 无）；out null → muted 无；deps 条件字段有才列。
  const inList = Array.isArray(n.in) ? n.in : [];
  /** @type {[string, string, boolean?][]} */
  const topoRows = [
    [t('flowmap.detail.in'), inList.length ? esc(inList.join(', ')) : esc(t('flowmap.detail.none')), !inList.length],
    [t('flowmap.detail.out'), n.out ? esc(String(n.out)) : esc(t('flowmap.detail.none')), !n.out],
  ];
  if (Array.isArray(n.deps) && n.deps.length) {
    topoRows.push([t('flowmap.detail.deps'), esc(n.deps.join(', '))]);
  }
  const topoHtml = `
    <div class="fm-detail-sec">${esc(t('flowmap.detail.secTopo'))}</div>
    ${kvHtml(topoRows)}`;
  // 执行形态区（有则显、无则不占位）：worktree / loop（配置位 maxRounds/verify +
  // 运行态 round/phase/verdict 有则附）/ merge / notifyDispatcher。
  /** @type {[string, string, boolean?][]} */
  const execRows = [];
  if (n.worktree) execRows.push(['Worktree', esc(String(n.worktree))]);
  const loop = (n.loop && typeof n.loop === 'object') ? /** @type {any} */ (n.loop) : null;
  if (loop) {
    const cfgParts = [t('flowmap.detail.loopRounds', { n: String(loop.maxRounds ?? '?') })];
    if (loop.verify) cfgParts.push(t('flowmap.detail.loopVerify', { agent: String(loop.verify) }));
    if (loop.enabled === false) cfgParts.push(t('flowmap.detail.loopDisabled'));
    execRows.push([t('flowmap.detail.loop'), esc(cfgParts.join(' · '))]);
    const rtParts = [];
    if (typeof n.loopRound === 'number' && n.loopRound > 0) rtParts.push(t('flowmap.detail.loopRoundN', { n: String(n.loopRound) }));
    if (n.loopPhase) rtParts.push(t(`flowmap.loopPhase.${n.loopPhase === 'verify' ? 'verify' : 'worker'}`));
    if (rtParts.length) execRows.push([t('flowmap.detail.loopState'), esc(rtParts.join(' · '))]);
    if (n.loopLastVerdict) execRows.push([t('flowmap.detail.loopVerdict'), esc(String(n.loopLastVerdict))]);
  }
  if (n.merge === true) execRows.push([t('flowmap.flag.merge'), esc(t('flowmap.detail.yes'))]);
  if (n.notifyDispatcher === true) execRows.push([t('flowmap.detail.notify'), esc(t('flowmap.detail.yes'))]);
  const execHtml = execRows.length
    ? `<div class="fm-detail-sec">${esc(t('flowmap.detail.secExec'))}</div>${kvHtml(execRows)}`
    : '';
  // blocked 结构化反馈（20260902 设计 §4.3 既有载荷；面板化后保留，回归不回退）
  const fb = st === 'blocked' && n.blockedFeedback ? /** @type {any} */ (n.blockedFeedback) : null;
  const blockedPanel = fb ? `
    <div class="flow-blocked-panel">
      <div class="flow-blocked-head">
        <span class="flow-blocked-badge">${FLAG_SVG}${esc(t('flowmap.blockedTitle'))}</span>
        ${fb.category ? `<span class="flow-blocked-category">${esc(String(fb.category))}</span>` : ''}
        <span class="flow-blocked-count">${esc(t('flowmap.blockedRounds', { n: String(Number(n.blockCount) || 0) }))}</span>
      </div>
      ${fb.detail ? `<div class="flow-blocked-row"><span class="flow-blocked-label">${esc(t('flowmap.blockedDetail'))}</span><div class="flow-blocked-text">${esc(String(fb.detail))}</div></div>` : ''}
      ${fb.suggestion ? `<div class="flow-blocked-row"><span class="flow-blocked-label">${esc(t('flowmap.blockedSuggestion'))}</span><div class="flow-blocked-text">${esc(String(fb.suggestion))}</div></div>` : ''}
    </div>` : '';
  // 载荷收敛（2026-09-05）：默认载荷无 result 本体——hasResult 标记驱动按需拉取。
  // blocked 原文（旧版取 result 渲染串）同改走按需通道（feedback 面板数据源是
  // blockedFeedback，不受影响）。占位符用纯省略号（不加 i18n 键——零 locales 改动）。
  const hasResult = !!n.hasResult || !!String(n.result || '');
  const taskText = String(n.task || '');
  let resultHtml;
  if (fb) {
    // blocked：结构化反馈面板在上；原文折叠区先占位，异步按需换装
    resultHtml = `<details class="flow-blocked-raw"><summary>${esc(t('flowmap.blockedRawTitle'))}</summary><div class="flow-agent-block-readonly">…</div></details>`;
  } else if (hasResult) {
    // 正文先占位，upgradeDetailResult 静默换装全文（失败落 noResult）
    resultHtml = '<span class="md-empty">…</span>';
  } else {
    resultHtml = `<span class="md-empty">${esc(t('flowmap.archive.noResult'))}</span>`;
  }
  ctx.detailBody.innerHTML = `
    <div class="fm-detail-meta">${metaRows}</div>
    ${descText ? `<div class="fm-detail-desc">${esc(descText)}</div>` : ''}
    ${blockedPanel}
    ${configHtml}
    ${topoHtml}
    ${execHtml}
    ${taskText ? `<div class="fm-detail-sec">${esc(t('flowmap.archive.taskLabel'))}</div><div class="fm-detail-task">${esc(taskText)}</div>` : ''}
    <div class="fm-detail-sec">${esc(t('flowmap.archive.resultLabel'))}</div>
    <div class="fm-detail-result fm-md">${resultHtml}</div>`;
  // 按需全文拉取（20260904 全文完整显示 + 20260905 载荷收敛改无条件触发）：
  // hasResult 即取全文换装（不再依赖「摘要长度 ≥500 才可能截断」的旧判据——
  // 新载荷根本没有摘要）。只替换 .fm-detail-result，不重建详情窗；响应按
  // ctx.detailRenderSeq 守卫。blocked 原文换装进 .flow-agent-block-readonly。
  if (hasResult) upgradeDetailResult(ctx, seq, n.id, fb);
}

// ── 详情结果按需全文（20260904「归档详情窗节点结果完整显示」；20260905 载荷收敛
//    后 hasResult 即无条件拉取——载荷不再携带摘要判据）─────────
/** 全文缓存（project\x00nodeId → 全文；LRU 30 条防长会话内存膨胀）。 */
const resultFullCache = new Map();
const RESULT_FULL_CACHE_CAP = 30;

/** 按需取全文（nodeData.fetchNodeResult，活动区/归档区后端单点）并换装：
 *  常规 → 只替换 .fm-detail-result（markdown 渲染单点 resultInnerHtml）；
 *  blocked（fb=true）→ 换装 .flow-agent-block-readonly（原文折叠区）。
 *  响应按 ctx.detailRenderSeq 守卫：换节点/关详情后的迟到响应丢弃；404/网络
 *  异常静默落 noResult（升级失败不扰详情窗）。 */
function upgradeDetailResult(/** @type {LayerCtx} */ ctx, /** @type {number} */ seq, /** @type {string} */ nodeId, /** @type {boolean} */ blocked) {
  const cacheKey = `${ctx.project}\u0000${nodeId}`;
  const cached = resultFullCache.get(cacheKey);
  if (cached !== undefined) {
    resultFullCache.delete(cacheKey);
    resultFullCache.set(cacheKey, cached); // LRU 触碰
    applyFullResult(ctx, seq, cached, blocked);
    return;
  }
  fetchNodeResult(ctx.project, nodeId)
    .then((full) => {
      if (typeof full !== 'string' || !full.trim()) { applyNoResult(ctx, seq); return; }
      resultFullCache.set(cacheKey, full);
      if (resultFullCache.size > RESULT_FULL_CACHE_CAP) {
        resultFullCache.delete(resultFullCache.keys().next().value); // 最老淘汰
      }
      applyFullResult(ctx, seq, full, blocked);
    })
    .catch(() => { applyNoResult(ctx, seq); /* 全文不可达：落 noResult（后端未挂载/节点出库/网络故障） */ });
}

/** 全文换装：仅替换结果容器 innerHTML（渲染代守卫），详情窗本体与滚动状态零扰动。 */
function applyFullResult(/** @type {LayerCtx} */ ctx, /** @type {number} */ seq, /** @type {string} */ full, /** @type {boolean=} */ blocked) {
  if (seq !== ctx.detailRenderSeq || ctx.detail.hidden) return;
  if (blocked) {
    const raw = ctx.detailBody.querySelector('.flow-blocked-raw .flow-agent-block-readonly');
    if (raw) raw.textContent = full;
    return;
  }
  const box = ctx.detailBody.querySelector('.fm-detail-result');
  if (!box) return;
  box.innerHTML = resultInnerHtml(full);
}

/** 全文不可达兜底：结果区落 noResult（渲染代守卫；仅占位态替换，不覆盖已换装全文）。 */
function applyNoResult(/** @type {LayerCtx} */ ctx, /** @type {number} */ seq) {
  if (seq !== ctx.detailRenderSeq || ctx.detail.hidden) return;
  const box = ctx.detailBody.querySelector('.fm-detail-result');
  if (box && box.textContent.trim() === '…') {
    box.innerHTML = `<span class="md-empty">${esc(t('flowmap.archive.noResult'))}</span>`;
  }
}

// ── hover 联动（§5.6：条目 → 全员可见下游；成员行 → 该员下游）─────────
function clearAdjHighlight(/** @type {LayerCtx} */ ctx) {
  ctx.container.querySelectorAll('.fm-node.fm-adj-by-entry').forEach((x) => {
    x.classList.remove('fm-adj', 'fm-adj-by-entry');
  });
}

function highlightDownstream(/** @type {LayerCtx} */ ctx, /** @type {string | null} */ nodeId, /** @type {boolean} */ on) {
  clearAdjHighlight(ctx);
  if (!on || !nodeId) return;
  const s = storeOf(ctx.project);
  for (const n of s.input.values()) {
    if (!isVisibleNode(ctx.project, n)) continue;
    const hit = (Array.isArray(n.in) && n.in.includes(nodeId))
      || (Array.isArray(n.deps) && n.deps.includes(nodeId))
      || n.out === nodeId;
    if (!hit) continue;
    const card = ctx.container.querySelector(`.fm-node[data-node-id="${CSS.escape(n.id)}"]`);
    if (card) card.classList.add('fm-adj', 'fm-adj-by-entry');
  }
}

function highlightChainDownstream(/** @type {LayerCtx} */ ctx, /** @type {string} */ chainId, /** @type {boolean} */ on) {
  if (!on) { highlightDownstream(ctx, null, false); return; }
  const s = storeOf(ctx.project);
  const chain = s.chains.find((c) => c.id === chainId) || s.bufferChains.find((c) => c.id === chainId)
    || s.remoteChains.get(chainId); // 裁定④：后端归档链条目 hover 联动同款
  if (!chain) return;
  clearAdjHighlight(ctx);
  const memberIds = new Set(chain.members.map((m) => m.id));
  for (const n of s.input.values()) {
    if (!isVisibleNode(ctx.project, n)) continue;
    const refs = [...(Array.isArray(n.in) ? n.in : []), ...(Array.isArray(n.deps) ? n.deps : [])];
    if (!refs.some((x) => memberIds.has(x))) continue;
    const card = ctx.container.querySelector(`.fm-node[data-node-id="${CSS.escape(n.id)}"]`);
    if (card) card.classList.add('fm-adj', 'fm-adj-by-entry');
  }
}

// ── 条目交互（§5.5：独占式展开；成员点击 → 详情）─────────────
function toggleEntry(/** @type {LayerCtx} */ ctx, /** @type {Element} */ el) {
  const s = storeOf(ctx.project);
  const id = el.getAttribute('data-chain-id') || '';
  const wasOpen = el.classList.contains('expanded');
  ctx.panelBody.querySelectorAll('.fm-entry.expanded').forEach((x) => {
    x.classList.remove('expanded');
    x.setAttribute('aria-expanded', 'false');
  });
  if (!wasOpen) {
    el.classList.add('expanded');
    el.setAttribute('aria-expanded', 'true');
    s.expandedEntry = id;
  } else {
    s.expandedEntry = null;
  }
}

function bindLayerEvents(/** @type {LayerCtx} */ ctx) {
  ctx.fab.addEventListener('click', (e) => {
    e.stopPropagation();
    togglePanel(ctx);
  });
  const panelClose = ctx.panel.querySelector('.fm-panel-close');
  if (panelClose) panelClose.addEventListener('click', () => closePanel(ctx));
  const detailClose = ctx.detail.querySelector('.fm-detail-close');
  if (detailClose) detailClose.addEventListener('click', () => closeDetail(ctx));

  // 面板内代理事件（条目重建无需重绑）
  ctx.panelBody.addEventListener('click', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    const member = target.closest('.fm-member');
    if (member) {
      e.stopPropagation();
      openDetail(ctx, member.getAttribute('data-node') || '');
      return;
    }
    const entry = target.closest('.fm-entry');
    if (entry) toggleEntry(ctx, entry);
  });
  ctx.panelBody.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const target = /** @type {HTMLElement} */ (e.target);
    const member = target.closest('.fm-member');
    if (member) {
      e.preventDefault();
      e.stopPropagation();
      openDetail(ctx, member.getAttribute('data-node') || '');
      return;
    }
    const entry = target.closest('.fm-entry');
    if (entry) {
      e.preventDefault();
      toggleEntry(ctx, entry);
    }
  });
  ctx.panelBody.addEventListener('mouseover', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    const member = target.closest('.fm-member');
    if (member) {
      highlightDownstream(ctx, member.getAttribute('data-node') || '', true);
      return;
    }
    const entry = target.closest('.fm-entry');
    if (entry) highlightChainDownstream(ctx, entry.getAttribute('data-chain-id') || '', true);
  });
  ctx.panelBody.addEventListener('mouseout', (e) => {
    const target = /** @type {HTMLElement} */ (e.target);
    if (target.closest('.fm-member')) highlightDownstream(ctx, null, false);
    else if (target.closest('.fm-entry')) highlightChainDownstream(ctx, '', false);
  });
}

// ── 文档级管线：空白点击统一收起（§5.10）+ Esc 分层（§5.7）─────────
/** 指针按下位置（>3px 位移的拖拽豁免 §5.10②）。
 *  2026-09-04 修复：原在 pointerup 即清 pressPoint，而 click 事件晚于 pointerup
 *  触发，位移判定恒 null 短路 → 拖拽豁免失效（画布平移拖拽误收面板，A11.3 动态
 *  暴露）。改为 click 消费时自清（每次 pointerdown 重建）。 */
let pressPoint = null;
document.addEventListener('pointerdown', (e) => {
  pressPoint = { x: e.clientX, y: e.clientY };
});

document.addEventListener('click', (e) => {
  const target = /** @type {HTMLElement} */ (e.target);
  if (!target) return;
  // 拖拽豁免：位移 >3px 的按下-抬起不是「空白点击」
  const dragged = pressPoint && Math.hypot(e.clientX - pressPoint.x, e.clientY - pressPoint.y) > 3;
  pressPoint = null;
  if (dragged) return;
  // 面板/悬浮钮/详情内部豁免
  if (target.closest('.fm-float-layer')) return;
  // 节点卡豁免（点节点 = 选择开详情；卡片自身已 stopPropagation，此处兜底）
  if (target.closest('.fm-node')) return;
  let closed = false;
  for (const ctx of Array.from(layerCtxs)) {
    if (!ctx.layer.isConnected) { dropCtx(ctx); continue; }
    if (ctx.panelOpen || !ctx.detail.hidden) closed = true;
    closeDetail(ctx);
    closePanel(ctx, { keepFocus: true });
  }
  return closed;
});

document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  let detailClosed = false;
  let panelCtx = null;
  for (const ctx of Array.from(layerCtxs)) {
    if (!ctx.layer.isConnected) { dropCtx(ctx); continue; }
    if (!ctx.detail.hidden) { closeDetail(ctx); detailClosed = true; } // Esc 先关详情（z 70 顶层）
    else if (ctx.panelOpen) panelCtx = ctx;
  }
  if (detailClosed) {
    e.stopPropagation();
    return;
  }
  if (panelCtx) {
    e.stopPropagation();
    closePanel(panelCtx); // 焦点归还悬浮钮
  }
});

// host 口径 dock 判定无媒体查询自动跟随：host 尺寸变化 → 落定口径全量重判
// （§5.8b 2026-09-04；D-QA1 修复）。#canvas-panel 宽度走 flex-basis 0.32s 过渡
// （split.css），window resize 事件时刻读到的 host.clientWidth 永远是过渡前旧值，
// 320ms 落定后无重判 → dock 形态按旧宽锁死。ResizeObserver 在每帧布局落定后回调
// （过渡逐帧触发、终帧即最终宽度），天然免疫该时序，且附带覆盖分栏拖宽
// （col-resizer 改 --canvas-width 不派发 window resize）的同类缺口。
function rejudgeAllDocks() {
  for (const ctx of Array.from(layerCtxs)) {
    if (!ctx.layer.isConnected) { dropCtx(ctx); continue; }
    updateDetailDock(ctx);
  }
}

/** @type {ResizeObserver|null} host box 尺寸回调 → 重判（模块单例；RO 纯作触发时机，宽度仍读 live clientWidth，DOCK_MIN_HOST_W 语义不变）。 */
let hostRo = null;

function observeHost(/** @type {LayerCtx} */ ctx) {
  if (typeof ResizeObserver === 'undefined') return; // 降级路径见下方 resize+双 rAF+transitionend
  const host = ctx.layer.parentElement;
  if (!host) return;
  if (!hostRo) hostRo = new ResizeObserver(rejudgeAllDocks);
  hostRo.observe(host);
}

function unobserveHost(/** @type {LayerCtx} */ ctx) {
  if (!hostRo) return;
  const host = ctx.layer.parentElement;
  if (!host) return;
  for (const other of layerCtxs) { // 同 host 仍有存活 ctx（host 存活而层全量重建）→ 保留观察
    if (other !== ctx && other.layer.isConnected && other.layer.parentElement === host) return;
  }
  hostRo.unobserve(host);
}

/** 惰性清扫出口：层脱离文档 → 停观察 + 出册（观察者生命周期防泄漏）。 */
function dropCtx(/** @type {LayerCtx} */ ctx) {
  unobserveHost(ctx);
  layerCtxs.delete(ctx);
}

if (typeof ResizeObserver === 'undefined') {
  // 防御性降级（无 RO 环境才启用；RO 可用时整体旁路——事件时刻读数必为过渡前旧值，
  // 再判一次反而引入中间翻转抖动）：事件时刻立即重判（host 不随过渡变化时已正确）
  // + 双 rAF（无过渡/0 时长的布局落定）+ transitionend（flex-basis 0.32s 过渡自
  // #canvas-panel 冒泡至 window 的落定终值重判）。
  window.addEventListener('resize', () => {
    rejudgeAllDocks();
    requestAnimationFrame(() => requestAnimationFrame(rejudgeAllDocks));
  });
  window.addEventListener('transitionend', (e) => {
    if (e.propertyName === 'flex-basis') rejudgeAllDocks();
  });
}

// ── 链事件通知（flowMapTab 整链退场/链未齐保留时调用）─────────────
/** 链未齐提示 toast（§6.3/§14-8 拍板 A：说明终态卡为何保留主图）。 */
export function notifyChainRetained(project, chain, container) {
  const done = chain.members.filter((m) => TERMINAL_STATUSES.has(String(m.status || ''))).length;
  const ctx = findCtx(container, project);
  if (!ctx) return;
  showToast(t('flowmap.chain.retainedToast', {
    chain: chain.title, done: String(done), total: String(chain.nodeCount),
  }), 'info');
}

/** 整链归档提示 toast（§3.4 增量过渡的口播伴随）。 */
export function notifyChainArchived(project, chains, container) {
  const ctx = findCtx(container, project);
  if (!ctx) return;
  for (const c of chains) {
    showToast(t('flowmap.chain.archivedToast', { chain: c.title, n: String(c.nodeCount) }), 'info');
  }
}

function findCtx(container, project) {
  for (const ctx of layerCtxs) {
    if (ctx.project === project && ctx.container === container && ctx.layer.isConnected) return ctx;
  }
  return container && container.isConnected ? ctxFor(container, project) : null;
}

// ── TTL 周期检查（§5.9：30s 轮询；快照导入触发在 flowMapTab 播种路径）─────
setInterval(() => {
  const projects = new Set();
  for (const ctx of Array.from(layerCtxs)) {
    if (!ctx.layer.isConnected) { dropCtx(ctx); continue; }
    projects.add(ctx.project);
  }
  for (const project of projects) {
    const removed = purgeExpired(project);
    if (!removed) continue;
    for (const ctx of layerCtxs) {
      if (ctx.project !== project || !ctx.layer.isConnected) continue;
      renderArchiveUi(ctx);
      showToast(t('flowmap.archive.ttlPurged', { n: String(removed) }), 'info');
    }
  }
}, TTL_SWEEP_MS);
