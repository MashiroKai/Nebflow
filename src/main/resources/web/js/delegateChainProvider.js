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

import { onMessage, onReconnect } from './ws.js';
import { authHeaders } from './flowHelpers.js';
import {
  setSource,
  rowFromRegistry,
  chainFromStoreRecord,
} from './delegateChainStub.js';

/** 端点表（与 PresenceRoutes 挂载路径逐字对齐；Router("/api") 前缀）。 */
export const CHAINS_API = {
  rows: () => '/api/chains',
  chain: (key) => `/api/chains/${encodeURIComponent(String(key || ''))}`,
};

/** 行键（taskNo ‖ delegateId——ChainStore.keyFor 同口径，与 taskList.delegateRowKey 一致）。 */
function rowKey(r) {
  return String((r && (r.taskNo || r.delegateId)) || '');
}

/** @type {{rows: any[], chains: Map<string, any>, seq: number}} 本地镜像（同步读）。 */
const mirror = { rows: [], chains: new Map(), seq: 0 };
/** 已请求过、确认查无链的键（防 miss 键反复打点；行集刷新时清空重探）。 */
const emptyKeys = new Set();

async function fetchJson(url) {
  const r = await fetch(url, { headers: authHeaders() });
  if (!r.ok) return null; // 404/5xx 都不是崩面——调用方按「无读数」降级
  return await r.json();
}

/** 行投影：record×live×verdict → rowFromRegistry（原样）→ ready-to-merge 覆写单点。 */
function projectRow(entry) {
  const src = entry && typeof entry === 'object' ? entry : {};
  const row = rowFromRegistry(src, src.live);
  const v = src.verdict;
  if (v && typeof v === 'object' && String(v.verdict || '') === 'ready-to-merge') {
    return { ...row, status: 'ready-to-merge' };
  }
  return row;
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

/** 拉某键链视图入镜像（fail-soft：任何失败不动现值）。 */
async function refreshChain(key) {
  const body = await fetchJson(CHAINS_API.chain(key));
  if (!body) {
    // 404 = 查无记录：镜像置 null（视图走 fail-soft 重试态），记空键防打点。
    mirror.chains.set(key, null);
    emptyKeys.add(key);
    return;
  }
  emptyKeys.delete(key);
  mirror.chains.set(key, projectChain(body));
}

/** 全量刷新：行投影 + 各行键链视图（并发逐键、单键失败不连累）。 */
async function refresh() {
  const seq = ++mirror.seq;
  const body = await fetchJson(CHAINS_API.rows());
  if (!body || seq !== mirror.seq) return; // 已有更新刷新在途，丢弃旧结果
  const rows = (Array.isArray(body.rows) ? body.rows : []).map(projectRow);
  mirror.rows = rows;
  emptyKeys.clear();
  const keys = Array.from(new Set(rows.map(rowKey).filter(Boolean)));
  await Promise.all(keys.map((k) => refreshChain(k).catch(() => {})));
}

/** 按需补拉（getChainByTask 未命中键；fire-and-forget，读口本调用仍返回 null）。 */
function fetchChainLazy(key) {
  if (!key || emptyKeys.has(key)) return;
  refreshChain(key).catch(() => {});
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

// ── 镜像驱动：boot 首刷 ＋ WS 触发防抖刷 ＋ 重连对账 ─────────────────────────

refresh().catch(() => {}); // boot 首刷（fail-soft：失败 = 空镜像，下轮 WS/重连再试）

const DEBOUNCE_MS = 500;
let debounceTimer = null;
function scheduleRefresh() {
  if (debounceTimer) return;
  debounceTimer = setTimeout(() => {
    debounceTimer = null;
    refresh().catch(() => {});
  }, DEBOUNCE_MS);
}

// WS 增量 = 刷新触发器（非数据源）：delegate 作用域链的 node* 帧族经既有广播到达。
for (const evt of ['nodeCreated', 'nodeUpdated', 'nodeCompleted', 'nodeRemoved']) {
  onMessage(evt, scheduleRefresh);
}
onReconnect(() => refresh().catch(() => {}));
