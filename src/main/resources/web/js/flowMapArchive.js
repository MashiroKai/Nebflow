// flowMapArchive.js — Flow Map 归档面板（v4 单源收口：链级抽象 P0，
//   设计 20260910_flowmap-chain-abstraction-spec.md §4.2/§7-B）。
//
// 语义（P0 单源收口后）：
//   ① 归档资格判定唯一存在于后端（FlowMapStore sweep，拓扑链口径）：链内全部成员
//      终态且资格齐备 → 后端 sweep 整链出活动区 → 逐成员 nodeRemoved 广播 → 主图/
//      任务列表由既有增量管线消化退场。前端零派生零判据——chainEligible 旧判据与
//      clusterBatches 时间批镜像（CHAIN_BATCH_MS/refreshChains/deriveArchivedIds）
//      已删除：「同一派生逻辑双端实现必然漂移」的根治（spec §1.2 教训；前端
//      chainEligible cancelled 判据曾落后后端一代）。
//   ② 归档面板 = 画布内右上角悬浮钮 + mailbox 式链条目（交互不变，§4/§5）。数据
//      单源 = 后端 GET flow-map/archive 批次聚合（权威源，重启/刷新后仍在；历史批
//      id 自然保留，spec C5 零迁移）：拉取时机 = 悬浮层创建首拉 + 面板打开 +
//      nodeRemoved 防抖。refetch 出现新链 id → 徽章脉冲 + 条目闪烁 + toast
//      （sweep 事实驱动的到达反馈，取代旧「派生链齐即报」的提前量）。
//   ③ 链上下文消费（spec §6.2 契约）：节点 payload 条件键 chainId（**可能缺失**
//      ——读不到 = 孤立节点处理，链 UI 自然降级，勿假设恒有）；快照顶层 chains
//      旁挂 [{id,title,entries,ends,memberIds}]（title 后端三级推导下发，前端禁再
//      推导）。本模块消费面 = 详情窗「所属链」行；旧后端（无新键）一切照旧不炸。
//   ④ 右侧详情面板（z 70 > 面板 60）：主图点节点与链内点成员两路触发；宽视口与归档
//      面板并排零重叠（dock-left 404px），窄视口同位浮前。
//   ⑤ 全局空白点击统一收起（click 判定 + >3px 拖拽豁免 + 节点/面板内部豁免）。
//   ⑥ 条目 24h TTL：服务端显示窗单点把关，本地 purge 为时钟走动期间兜底。

import { esc, fmtTime } from './flowHelpers.js';
import { t } from './i18n.js';
import { renderMarkdownWithMath } from './utils.js';
import { fetchNodeResultDetail, fetchFlowMapArchive } from './nodeData.js';

// P2-4 cycle cut（同 sidebar.js → modal 先例）：静态链 modal → sidebar → taskList
// → flowMapArchive → modal 成环（68de01d6 引入 taskList 边后闭合），showToast
// 转动态 import 破环——toast 本就是 fire-and-forget UI，微任务级延迟无感。
/** @param {string} msg @param {string} [type] */
const showToast = (msg, type) =>
  import('./modal.js').then((m) => m.showToast(msg, type)).catch(() => {});

// ── 常量（规格 §3.5/§5.9）─────────────────────────────────
/** 终态集合：链齐判定与归档口径（规格 §3.1）。 */
export const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled']);

/** @param {string|undefined} st @returns {boolean} */
export function isTerminalStatus(st) {
  return TERMINAL_STATUSES.has(String(st || ''));
}

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

// ══ 每项目归档 store（随 flowMapTab store 生命周期）═══

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
 * @property {string=} chainId 所属链 id（NodePayload 条件键，链级抽象 P0 契约：
 *   后端拓扑链单源下发；可能缺失——缺失 = 孤立节点，链 UI 自然降级）
 * @property {string[]=} chainIds 多链归属集（U1 批·作者裁定① NodePayload 条件键：
 *   **仅 merge 节点且可达成员链数 ≥2 带**，值 = 主链 :: 全量成员链（主链恒首项 =
 *   chainId 逐字同值）；普通节点恒缺失。前端「多链 vs 单链合并节点」判据 =
 *   **除自身主链外的成员链数 ≥2**（见 chainHighlightIds——不是拼上主链项后的裸长度，
 *   否则「主链 + 1 条成员链」形态会被误判多链）；缺失 = 单链或非 merge，走整链语义）
 * @property {number=} notifySentAt 异常终态（failed/cancelled）上报分发器时间戳
 *   （NodePayload 条件序列化：已上报才带。P0 起归档资格判定唯一在后端，前端
 *   不再消费此键做链判据。）
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
 * @property {Map<string, ChainMember>} input 最近一次快照导入的活动节点
 *           （详情窗/链名/hover 联动数据源；flowMapTab 快照与 WS 增量路径经
 *           ingestNodes 汇入）
 * @property {Map<string, Object>} snapshotChains 后端快照 chains 旁挂
 *           （chainId → {id,title,entries,ends,memberIds}；旧后端缺键 = 空，
 *           详情「所属链」行自然降级；title 后端下发，前端零推导）
 * @property {Map<string, Chain>} remoteChains 后端 Flow Archive 批次链（面板唯一
 *           数据源，权威、重启/刷新后仍在；历史批 id 自然保留）
 * @property {Map<string, ChainMember>} remoteMembers 后端归档成员（详情/链名查找兜底）
 * @property {boolean} remoteFetched 已成功首拉（悬浮层创建时触发一次；首拉为
 *           toast/flash 基线——存量归档不是「新闻」）
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
      input: new Map(),
      snapshotChains: new Map(),
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
 * 归档链条目显示名（§5.3 确定性三级，**仅 /archive 批次兜底**）：该端点本批零改动、
 * 载荷无 title 键 → 条目名由成员名/task 推导渲染。这不是链归属判定（归属唯一来源
 * = 后端）；快照 chains 旁挂（活动区链上下文）的 title 由后端三级推导下发，前端
 * 禁再推导（spec §6.2 契约）。
 * 推导规则：① ≥2 成员 task 含【…】且公共前缀 ≥2 字（产品载荷暂无 task 字段，
 * 出现时自动启用）→ ② 成员名去角色前缀后公共前缀 ≥3 字 → ③ 链首（createdAt
 * 最早）节点名。
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
 * 快照/WS 增量导入（链级抽象 P0：取代旧 refreshChains 链派生）。**零判定零派生**
 * ——只把 flowMapTab 已有的快照数据记入 store，供详情窗（openDetail）、链上下文
 * （chainOfNode）与 hover 联动读取：
 *   - fmNodes：全量活动节点（快照路径 = fetchFlowMap().nodes；WS 路径 = 事件并入后
 *     的完整节点集——两路都传全集，直接整体替换）。
 *   - fmChains：快照顶层 chains 旁挂（§6.2 契约，条件键——可能缺失）。传 undefined
 *     = 本次载荷未带该键（旧后端/中间态），旁挂缓存原样保留；传数组（含空数组）
 *     = 整体替换。title 由后端下发，本函数原样存储、零推导。
 * @param {string} project
 * @param {ChainMember[]=} fmNodes
 * @param {any[]=} fmChains 快照 chains 旁挂（undefined = 键缺失，保留现缓存）
 */
export function ingestNodes(project, fmNodes, fmChains) {
  const s = storeOf(project);
  if (fmNodes) {
    const input = new Map();
    for (const n of fmNodes) input.set(n.id, n);
    s.input = input;
  }
  if (fmChains !== undefined) {
    const sc = new Map();
    if (Array.isArray(fmChains)) {
      for (const c of fmChains) {
        if (c && c.id) sc.set(String(c.id), c);
      }
    }
    s.snapshotChains = sc;
  }
}

/**
 * TTL 清理（§5.9）：到期链从面板移除 + 徽章同步减。P0 单源收口后仅剩 remote 条目
 * ——服务端按 24h 显示窗过滤是单点把关，本地时钟走动期间在此兜底；「派生输入
 * 排除防复活」随派生删除一并退役（主图可见性 = 后端活动区快照，无复活面）。
 * @param {string} project
 * @param {number=} now
 * @returns {number} 移除条数
 */
export function purgeExpired(project, now = Date.now()) {
  const s = storeOf(project);
  let removed = 0;
  for (const [cid, c] of Array.from(s.remoteChains)) {
    if (now - (c.completedAt || 0) <= ARCHIVE_TTL_MS) continue;
    for (const m of c.members) s.remoteMembers.delete(m.id);
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

/** 拉取后端归档批次 → remoteChains/remoteMembers（归档面板唯一数据源）。
 *  失败（未挂载/网络）静默保留现状，下次触发重试；成功后重渲染该项目全部存活层。
 *  到达反馈（P0 sweep 事实驱动，取代旧派生「链齐即报」的提前量）：首拉为基线
 *  （存量归档不报）；后续 refetch 出现新增链 id → 徽章脉冲 + 条目闪烁 + toast
 *  ——事实级判定：条目已出现在后端归档里，零误报（abandon 等非归档出库不进此路）。 */
async function fetchRemoteChains(project) {
  const s = storeOf(project);
  if (s.remoteInflight) return;
  s.remoteInflight = true;
  try {
    const batches = await fetchFlowMapArchive(project);
    if (batches) {
      const now = Date.now();
      const baseline = s.remoteFetched; // 首拉 = 基线
      const prevIds = baseline ? new Set(s.remoteChains.keys()) : null;
      s.remoteChains = new Map();
      s.remoteMembers = new Map();
      /** @type {Chain[]} 新增条目（相对上次成功拉取） */
      const freshChains = [];
      for (const b of batches) {
        const chain = remoteChainOf(b);
        if (!chain.id || !chain.members.length) continue;
        if (now - (chain.completedAt || 0) > ARCHIVE_TTL_MS) continue; // 本地时钟兜底
        s.remoteChains.set(chain.id, chain);
        for (const m of chain.members) s.remoteMembers.set(m.id, m);
        if (prevIds && !prevIds.has(chain.id)) freshChains.push(chain);
      }
      s.remoteFetched = true;
      let liveCtx = null;
      for (const ctx of Array.from(layerCtxs)) {
        if (ctx.project !== project || !ctx.layer.isConnected) continue;
        if (!liveCtx) liveCtx = ctx;
        renderArchiveUi(ctx, freshChains.length
          ? { pulse: true, flashChainIds: freshChains.map((c) => c.id) }
          : {});
      }
      if (liveCtx) {
        for (const c of freshChains) {
          showToast(t('flowmap.chain.archivedToast', { chain: c.title, n: String(c.nodeCount) }), 'info');
        }
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

/** 面板条目 = remoteChains 单源（P0 单源收口：旧「派生 chains 补 sweep 在途窗口」
 *  随派生删除退役——条目在后端 sweep 落库 + 本函数 refetch 后出现，晚 ≤30s+防抖，
 *  换取归档资格判定唯一存在于后端）。completedAt 倒序。 */
function panelChains(s) {
  return Array.from(s.remoteChains.values())
    .sort((a, b) => (b.completedAt || 0) - (a.completedAt || 0) || a.title.localeCompare(b.title));
}

/**
 * nodeRemoved 出库信号 → 防抖刷新 remote（链级 sweep 按成员逐条广播，合并为一次
 * 拉取）。P0 起出库事实本身即主图/任务列表退场的驱动（增量管线消化），本函数只
 * 承担面板数据新鲜度。旧墓碑机制（终态事实保留参与链齐判定）随派生删除退役。
 */
export function recordNodeRemoved(project, _node) {
  scheduleRemoteRefresh(project);
}

/** nodeId → 所属链（详情「所属链」行数据源）。两路查证，**无任何本地派生**：
 *  ① 后端归档链（remoteChains，成员行点击/已归档节点）；
 *  ② 活动区链上下文：节点 payload 的 chainId 条件键（可能缺失）→ 快照 chains 旁挂
 *     摘要（spec §6.2 契约；title/成员集后端下发，缺旁挂或缺 chainId = null，
 *     详情链行自然降级——旧后端形态恒走此降级，零 crash）。 */
export function chainOfNode(project, nodeId) {
  const s = stores.get(project);
  if (!s) return null;
  for (const rc of s.remoteChains.values()) {
    if (rc.members.some((m) => m.id === nodeId)) return rc;
  }
  const n = s.input.get(nodeId);
  const chainId = n && n.chainId ? String(n.chainId) : '';
  if (!chainId) return null;
  const sc = s.snapshotChains.get(chainId);
  if (!sc) return null;
  const memberIds = (Array.isArray(sc.memberIds) ? sc.memberIds : []).map(String);
  const members = memberIds
    .map((id) => s.input.get(id) || s.remoteMembers.get(id))
    .filter(Boolean)
    .sort(byCompletedDesc);
  return {
    id: chainId,
    title: String(sc.title || ''),
    members,
    nodeCount: memberIds.length || members.length,
    status: chainStatusOf(members),
    completedAt: members.reduce((mx, m) => Math.max(mx, m.completedAt || 0), 0),
  };
}

/** 节点所属链的**全量成员 id**（U1 批·链高亮集合数据源）。两路查证、零本地派生：
 *  ① 活动节点 payload 的 chainId 条件键 → 快照 chains 旁挂 memberIds（后端分量全量，
 *     含已归档成员）；② 归档成员不在快照（不带动节点）→ remoteChains 条目 members。
 *  链不可知（孤立单节点链 / 旧后端缺键）→ 只含自身（降级为单节点集合，零 crash）。
 *  @param {string} project @param {string} nodeId @returns {string[]} */
export function chainMembersOf(project, nodeId) {
  const s = stores.get(project);
  if (!s) return [nodeId];
  const n = s.input.get(nodeId) || s.remoteMembers.get(nodeId);
  const cid = n && n.chainId ? String(n.chainId) : '';
  if (cid) {
    const sc = s.snapshotChains.get(cid);
    if (sc && Array.isArray(sc.memberIds) && sc.memberIds.length) return sc.memberIds.map(String);
  }
  for (const rc of s.remoteChains.values()) {
    if (rc.members.some((m) => m.id === nodeId)) return rc.members.map((m) => String(m.id));
  }
  return [nodeId];
}

/**
 * 点击 / 悬浮的高亮集合（U1 批 · 作者 2026-09-11 裁定③④，图上零视觉标识）。
 *
 * 语义（**只对合并节点分叉**，普通节点零改动）：
 *  - **多链合并节点**（`merge === true` 且**除自身主链外的成员链数 ≥2**）⇒ 集合 = 仅自身
 *    ——它同属多条成员链，「整条链」指向歧义 ⇒ 只亮自己，不牵动任何其他链节点（裁定③）。
 *  - **单链合并节点 / 普通节点** ⇒ 集合 = 该链全部成员（含链上全部合并节点——
 *    裁定④「不能漏掉合并节点」）。
 *  多链判据 = 引擎下发字段（`chainIds`，普通节点恒缺席）+ **剔除自身主链项后计数**
 *  （`chainIds` 首项恒 = `chainId`，故等价于「除自身主链外的成员链数 ≥2」）：
 *  按裸长度判会把「主链 + 1 条成员链」载荷误判为多链（该形态应按单链处理），
 *  前端零派生拓扑。
 * @param {string} project @param {string} nodeId
 * @returns {{ ids: string[], multi: boolean, chainId: string | null }} */
export function chainHighlightIds(project, nodeId) {
  const s = stores.get(project);
  const n = s ? (s.input.get(nodeId) || s.remoteMembers.get(nodeId)) : null;
  const chainId = n && n.chainId ? String(n.chainId) : null;
  const ids = n && Array.isArray(n.chainIds) ? n.chainIds : null;
  const otherChains = ids ? ids.filter((id) => id !== chainId).length : 0;
  const multi = !!(n && n.merge === true && ids !== null && otherChains >= 2);
  return { ids: multi ? [nodeId] : chainMembersOf(project, nodeId), multi, chainId };
}

/** merge 节点「收的是哪些 worktree/分支」（U1 批 · 作者裁定⑤；详情卡数据源）。
 *  **数据源 = 引擎已持有的节点载荷 `worktree` 字段**（= worktree 目录名，与同名分支
 *  一致；含 `hasWorktree`/`worktree` 的 NodePayload 恒带键、未配为 null）——沿 **in
 *  边**（合并账本，指南 §0.1）向上做闭包遍历，收集所有带 worktree 的上游节点值；
 *  零新增引擎字段、零硬编码、零臆造。上游节点不在前端缓存（活动区快照只含活动节点；
 *  归档成员经 remoteMembers 兜底）→ 该支线贡献为空，渲染层如实落「不可得」文案。
 *  @param {string} project @param {string} nodeId @returns {string[]} 去重升序 worktree 名 */
export function mergeUpstreamWorktrees(project, nodeId) {
  const s = stores.get(project);
  if (!s) return [];
  const seen = new Set([nodeId]);
  const found = new Set();
  let frontier = [nodeId];
  while (frontier.length) {
    const next = [];
    for (const id of frontier) {
      const n = s.input.get(id) || s.remoteMembers.get(id);
      if (!n) continue;
      for (const up of (Array.isArray(n.in) ? n.in : [])) {
        if (seen.has(up)) continue;
        seen.add(up);
        const un = s.input.get(up) || s.remoteMembers.get(up);
        if (un && un.worktree) found.add(String(un.worktree));
        next.push(up);
      }
    }
    frontier = next;
  }
  return Array.from(found).sort();
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
  // 状态词（2026-09-07 状态保真批）：glyph 旁恒带状态文本——cancelled 的减号
  // glyph 单看是「-」无从分辨，未知状态原渲染空白；文本 = statusLabel（状态原文
  // 兜底），空状态落「无」。「-」/空白不再可能出现。
  const stWord = esc(statusLabel(st) || t('flowmap.detail.none'));
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
        <span class="fm-entry-stw ${statusClass(st)}">${stWord}</span>
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
      <span class="fm-entry-stw ${statusClass(c.status)}">${esc(statusLabel(c.status) || t('flowmap.detail.none'))}</span>
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
    return renderMarkdownWithMath(text) || emptyStateHtml();
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
  // 链状态三分（2026-09-07 状态保真批）：全 completed = 已归档；含 failed/cancelled
  // = 异常终态保留主图待清理（不再称「链未齐」——此类链永不自动归档，语义必须诚实）；
  // 其余 = 链未齐保留。
  const chainStateKey = chain
    ? (chain.members.every((m) => String(m.status || '') === 'completed')
        ? 'flowmap.archive.chainArchived'
        : (chain.members.some((m) => m.status === 'failed' || m.status === 'cancelled')
            ? 'flowmap.archive.chainAbnormal'
            : 'flowmap.archive.chainRetained'))
    : '';
  const chainLine = chain
    ? `${esc(t('flowmap.archive.chain'))}：${esc(chain.title)}（${esc(t('flowmap.archive.nodes', { n: String(chain.nodeCount) }))} · ${esc(t(chainStateKey))}）`
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
  // 拓扑区：in 恒带数组（空 → muted 无）；out 兼容双形态——D6 批 E1（5a45c788）
  // 载荷改边对象数组 [{to,on,mode}]（归一化单点 buildNodeJson，缺键=无出边），
  // legacy 字符串/字符串数组（双读解码前的存量盘上形态）一并防御，取目标名列表
  // 逗号连接，空 → muted 无；deps 条件字段有才列。（修前 String(对象数组) 隐式
  // 序列化产出 "[object Object]"；边门控展示等 F3 前端适配批落地。）
  const inList = Array.isArray(n.in) ? n.in : [];
  /** @type {string[]} 出边目标名列表（边对象取 .to；legacy 字符串原样）。 */
  const outTargets = Array.isArray(n.out)
    ? n.out.map((e) => (e && typeof e === 'object') ? String(e.to ?? '') : String(e ?? '')).filter(Boolean)
    : (n.out ? [String(n.out)] : []);
  /** @type {[string, string, boolean?][]} */
  const topoRows = [
    [t('flowmap.detail.in'), inList.length ? esc(inList.join(', ')) : esc(t('flowmap.detail.none')), !inList.length],
    [t('flowmap.detail.out'), outTargets.length ? esc(outTargets.join(', ')) : esc(t('flowmap.detail.none')), !outTargets.length],
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
  if (n.merge === true) {
    execRows.push([t('flowmap.flag.merge'), esc(t('flowmap.detail.yes'))]);
    // U1 批 · 作者裁定⑤：合并节点卡补「收的是哪些 worktree/分支」一行——数据源 =
    // 上游 in 边闭包内节点载荷的 worktree 字段（引擎已持有；见 mergeUpstreamWorktrees）。
    // 取不到（上游不在前端缓存：归档超窗 / 旧载荷无该键）→ 如实落「不可得 + 原因」。
    const wts = mergeUpstreamWorktrees(ctx.project, String(n.id || ''));
    execRows.push([
      t('flowmap.detail.mergeUpstreams'),
      wts.length ? esc(wts.join(' · ')) : esc(t('flowmap.detail.mergeUpstreamsNone')),
    ]);
  }
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
    resultHtml = `<details class="flow-blocked-raw"><summary>${esc(t('flowmap.blockedRawTitle'))}</summary><div class="flow-agent-block-readonly">${RESULT_PLACEHOLDER}</div></details>`;
  } else if (hasResult) {
    // 正文先占位，upgradeDetailResult 换装全文（取全文失败落明确错误态，空结果落空态）
    resultHtml = `<span class="md-empty">${RESULT_PLACEHOLDER}</span>`;
  } else {
    resultHtml = emptyStateHtml();
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
  // 初始状态落地（2026-09-11 fmresult 批）：有全文待取 → loading（占位，**必须**
  // 被 loaded/empty/error 替换）；无全文 → empty（已是终态，不发请求）。
  markResultState(ctx, fb || hasResult ? RESULT_LOADING : RESULT_EMPTY);
  // 按需全文拉取（20260904 全文完整显示 + 20260905 载荷收敛改无条件触发）：
  // hasResult 即取全文换装（不再依赖「摘要长度 ≥500 才可能截断」的旧判据——
  // 新载荷根本没有摘要）。只替换 .fm-detail-result，不重建详情窗；响应按
  // ctx.detailRenderSeq 守卫（swapCurrent）。blocked 原文换装进 .flow-agent-block-readonly。
  if (hasResult) upgradeDetailResult(ctx, seq, n.id, fb);
}

// ── 详情结果按需全文（20260904「归档详情窗节点结果完整显示」；20260905 载荷收敛
//    后 hasResult 即无条件拉取——载荷不再携带摘要判据）─────────
/** 全文缓存（project\x00nodeId → 全文；LRU 30 条防长会话内存膨胀）。 */
const resultFullCache = new Map();
const RESULT_FULL_CACHE_CAP = 30;

// ── 结果区四态（2026-09-11 fmresult 批：占位必须有终态）──────────────────
// 结果区状态机（互斥四态，落 data-fm-result-state 供 harness/QA 与文案解耦断言）：
//   loading → 占位「…」（详情已渲染、全文在途；**只允许是中间态**）
//   loaded  → 真实全文（markdown 渲染串 / blocked 原文纯文本）
//   empty   → 结果为空（节点无结果 / 结果空白：不是错误）
//   error   → 明确错误态（可读原因 + 可操作提示）
// 收敛不变式：任一 loading 必被 loaded/empty/error 之一替换（本批把「换装守卫」
// 与「取全文失败态」两处缺口补上——旧实现两处都会让占位永久停留）。
/** 占位文本（纯省略号，零 i18n 键）。 */
const RESULT_PLACEHOLDER = '…';
/** 结果区状态取值。 */
const RESULT_LOADING = 'loading';
const RESULT_LOADED = 'loaded';
const RESULT_EMPTY = 'empty';
const RESULT_ERROR = 'error';

/** blocked 原文区元素（blocked 节点的结果落此处，非结果容器）。
 *  @param {LayerCtx} ctx @returns {HTMLElement|null} */
function blockedRawEl(/** @type {LayerCtx} */ ctx) {
  return /** @type {HTMLElement|null} */ (ctx.detailBody.querySelector('.flow-blocked-raw .flow-agent-block-readonly'));
}

/** 结果区内容宿主：blocked 原文区优先（renderDetail 的 fb 分支产出），否则结果
 *  容器 .fm-detail-result（卡内唯一竖向滚动容器）。两者皆无 = 详情结构未就绪。
 *  @param {LayerCtx} ctx @returns {HTMLElement|null} */
function resultSlot(/** @type {LayerCtx} */ ctx) {
  return blockedRawEl(ctx) || /** @type {HTMLElement|null} */ (ctx.detailBody.querySelector('.fm-detail-result'));
}

/** 结果区状态落地（四态互斥；宿主 = resultSlot）。
 *  @param {LayerCtx} ctx @param {string} state */
function markResultState(/** @type {LayerCtx} */ ctx, /** @type {string} */ state) {
  const el = resultSlot(ctx);
  if (el) el.dataset.fmResultState = state;
}

/** 结果区空态 HTML（无结果/结果为空——非错误，与 error 分形）。 @returns {string} */
function emptyStateHtml() {
  return `<span class="md-empty">${esc(t('flowmap.archive.noResult'))}</span>`;
}

/** 错误态原因文案（i18n；404 单列——归档 24h 过期后节点已出活动区/归档区）。
 *  @param {string} state @param {number=} status @returns {string} */
function resultErrorReason(/** @type {string} */ state, /** @type {number=} */ status) {
  if (state === 'timeout') return t('flowmap.archive.resultFailTimeout');
  if (state === 'http') {
    return Number(status) === 404
      ? t('flowmap.archive.resultFailGone')
      : t('flowmap.archive.resultFailHttp', { status: String(status ?? '') });
  }
  return t('flowmap.archive.resultFailNetwork');
}

/** 错误态文案（原因 + 可操作提示）。 @param {string} state @param {number=} status @returns {string} */
function resultErrorText(/** @type {string} */ state, /** @type {number=} */ status) {
  return `${t('flowmap.archive.resultLoadFail', { reason: resultErrorReason(state, status) })} · ${t('flowmap.archive.resultLoadFailHint')}`;
}

/** 错误态 HTML（可读原因 + 可操作提示）。色用既有 --color-error token（与卡片
 *  failed 状态同源），inline 覆盖仅因结果区无对应错误类且 flowMap.css 在飞——
 *  CSS 解冻后应收编为 .md-error 一类（见批报告「需拍板项」）。
 *  @param {string} state @param {number=} status @returns {string} */
function resultErrorHtml(/** @type {string} */ state, /** @type {number=} */ status) {
  return `<span class="md-empty" style="font-style:normal;opacity:1;color:var(--color-error)">${esc(resultErrorText(state, status))}</span>`;
}

/** 换装守卫（2026-09-11 fmresult 批）＝**仅「渲染代未变」**。
 *  detailRenderSeq 唯一自增点 = renderDetail ⇒ seq 唯一标识最新一次详情渲染：
 *  换节点/关详情/重开后的迟到响应代不符必被丢弃（竞态防护语义不变）。
 *  **可见性（ctx.detail.hidden）不再参与判定**——这正是旧缺陷根因：openDetail 的
 *  时序是「先 renderDetail 落占位、后置 hidden=false」，而 LRU 命中/已取回全文
 *  的换装是**同步**的，于是被 hidden 早退吞掉且再无第二次写入机会 ⇒ 重开同一节点
 *  占位「…」永久停留（实测 stuckPlaceholder=true）。隐藏窗上的换装无副作用：内容
 *  宿主随重开必被 renderDetail 重建，且同 tick 内完成不产生可见中间帧。
 *  @param {LayerCtx} ctx @param {number} seq @returns {boolean} */
function swapCurrent(/** @type {LayerCtx} */ ctx, /** @type {number} */ seq) {
  return seq === ctx.detailRenderSeq;
}

/** 按需取全文（nodeData.fetchNodeResultDetail，活动区/归档区后端单点）并换装：
 *  常规 → 只替换 .fm-detail-result（markdown 渲染单点 resultInnerHtml）；
 *  blocked（fb=true）→ 换装 .flow-agent-block-readonly（原文折叠区）。
 *  响应按 ctx.detailRenderSeq 守卫（swapCurrent）：换节点/关详情后的迟到响应丢弃。
 *  **三态分流（2026-09-11 fmresult 批）**：ok → 全文换装并入 LRU；empty → 空态；
 *  http/network/timeout → 明确错误态。旧实现把「失败」静默折成空态 noResult ——
 *  「错误态与空态混淆」且挂死请求永无终态；现三条路径皆有终态，占位不留悬。 */
function upgradeDetailResult(/** @type {LayerCtx} */ ctx, /** @type {number} */ seq, /** @type {string} */ nodeId, /** @type {boolean} */ blocked) {
  const cacheKey = `${ctx.project}\u0000${nodeId}`;
  const cached = resultFullCache.get(cacheKey);
  if (cached !== undefined) {
    resultFullCache.delete(cacheKey);
    resultFullCache.set(cacheKey, cached); // LRU 触碰
    applyFullResult(ctx, seq, cached, blocked);
    return;
  }
  fetchNodeResultDetail(ctx.project, nodeId)
    .then((res) => {
      if (res.state === 'empty') { applyNoResult(ctx, seq); return; }
      if (res.state !== 'ok') { applyResultError(ctx, seq, res.state, res.state === 'http' ? res.status : 0); return; }
      resultFullCache.set(cacheKey, res.result);
      if (resultFullCache.size > RESULT_FULL_CACHE_CAP) {
        resultFullCache.delete(resultFullCache.keys().next().value); // 最老淘汰
      }
      applyFullResult(ctx, seq, res.result, blocked);
    })
    .catch(() => { applyResultError(ctx, seq, 'network', 0); /* 数据层意外抛出：仍落明确错误态，不留悬 */ });
}

/** 全文换装：仅替换结果区内容（渲染代守卫），详情窗本体与滚动状态零扰动。
 *  形态错位兜底（2026-09-11 fmresult 批）：blocked 标记与 block 原文区不一致时
 *  内容照落结果容器——绝不因宿主解析落空留下永不收敛的占位。
 *  @param {LayerCtx} ctx @param {number} seq @param {string} full @param {boolean=} blocked */
function applyFullResult(/** @type {LayerCtx} */ ctx, /** @type {number} */ seq, /** @type {string} */ full, /** @type {boolean=} */ blocked) {
  if (!swapCurrent(ctx, seq)) return;
  const slot = resultSlot(ctx);
  if (!slot) return;
  if (blocked && slot === blockedRawEl(ctx)) {
    slot.textContent = full; // blocked 原文：纯文本（结构化反馈面板在独立区块，不受影响）
    slot.dataset.fmResultState = RESULT_LOADED;
    return;
  }
  slot.innerHTML = resultInnerHtml(full);
  // markdown 渲染为空（全文存在但渲染不出内容）→ empty 而非 loaded：状态诚实。
  slot.dataset.fmResultState = slot.querySelector('.md-empty') ? RESULT_EMPTY : RESULT_LOADED;
}

/** 结果区落终态·空（节点无结果 / 结果为空——非错误）：槽位形态无关（结果容器与
 *  blocked 原文区都收敛），不再依赖「容器 textContent 恰为占位」的脆弱判据——旧判据
 *  在 blocked 形态下永不成立（原文区嵌套在详情 details 内，textContent 含标题），
 *  blocked 节点取全文失败时占位「…」永久停留（本批同类缺陷之二）。 */
function applyNoResult(/** @type {LayerCtx} */ ctx, /** @type {number} */ seq) {
  if (!swapCurrent(ctx, seq)) return;
  const slot = resultSlot(ctx);
  if (!slot) return;
  if (slot === blockedRawEl(ctx)) slot.textContent = t('flowmap.archive.noResult'); // 原文区 = textContent 宿主（无 HTML 解析）
  else slot.innerHTML = emptyStateHtml();
  slot.dataset.fmResultState = RESULT_EMPTY;
}

/** 结果区落明确错误态（取全文失败：http / network / timeout）。原因 + 可操作提示，
 *  与空态（empty）和占位（loading）严格分形。
 *  @param {LayerCtx} ctx @param {number} seq @param {string} state @param {number=} status */
function applyResultError(/** @type {LayerCtx} */ ctx, /** @type {number} */ seq, /** @type {string} */ state, /** @type {number=} */ status) {
  if (!swapCurrent(ctx, seq)) return;
  const slot = resultSlot(ctx);
  if (!slot) return;
  if (slot === blockedRawEl(ctx)) slot.textContent = resultErrorText(state, status);
  else slot.innerHTML = resultErrorHtml(state, status);
  slot.dataset.fmResultState = RESULT_ERROR;
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
  const chain = s.remoteChains.get(chainId); // 面板条目 = remote 单源（P0）
  if (!chain) return;
  clearAdjHighlight(ctx);
  const memberIds = new Set(chain.members.map((m) => m.id));
  for (const n of s.input.values()) {
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

// ── 链事件通知（P0 单源收口后仅剩「到达反馈」——已上移 fetchRemoteChains
//    refetch diff：条目出现在后端归档才报，资格/时机判定全部在后端）。
//    旧 notifyChainArchived/notifyChainRetained（派生链齐即报 + 保留判据 toast）
//    随前端派生删除退役。

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
