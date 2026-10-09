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
  setChildrenSource,
  rowFromRegistry,
  chainFromStoreRecord,
} from './delegateChainStub.js';
import { DELEGATE_API } from './delegateApi.js';

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
/** 星型子代理镜像（G3；delegateId → children[]）。refresh 臂随行集刷新逐父拉取；
 *  fail-soft：单父失败保留旧值（与链镜像同纪律），首刷前 = 空集。 */
const childrenMirror = new Map();

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
  };
}

/** 拉某父的星型子代理入镜像（fail-soft：任何失败保留旧值；404 = 父无记录 → 清空）。 */
async function refreshChildren(delegateId) {
  const body = await fetchJson(DELEGATE_API.children(delegateId));
  if (!body) {
    if (!childrenMirror.has(delegateId)) childrenMirror.set(delegateId, []);
    return;
  }
  const kids = Array.isArray(body.children) ? body.children.map(projectChild) : [];
  childrenMirror.set(delegateId, kids);
}

/** 全量刷新：行投影 + 各行键链视图（并发逐键、单键失败不连累）＋ G3 星型子代理
 *  逐父刷新（仅内部行——外部执行器零平台工具，children 恒空集，免打点）。
 *  刷新完成即广播 delegate-chain-mirror-updated（#56 残项 · 换源即切 UI 承接位，
 *  晨裁② 2026-10-07）：面板渲染层监听此事件重渲——数据权威恒在本镜像（REST），
 *  事件零载荷、零帧解读（「帧只当触发器」纪律不变）。 */
async function refresh() {
  const seq = ++mirror.seq;
  const body = await fetchJson(CHAINS_API.rows());
  if (!body || seq !== mirror.seq) return; // 已有更新刷新在途，丢弃旧结果
  const rows = (Array.isArray(body.rows) ? body.rows : []).map(projectRow);
  // 两层架构批（§D 自动归档到达侦测）：记录面零删除（归档只加 archivedAt 旗），
  // 在册窗消失的行 = 已归档（引擎终态单点写旗）。差集行细节随 delegate-archived
  // 事件下发（L2 徽章脉冲 + toast 5s 撤销——消费方 delegateChainView；本件零 UI）。
  if (mirror.rows.length && typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
    const nextKeys = new Set(rows.map(rowKey).filter(Boolean));
    for (const prev of mirror.rows) {
      const k = rowKey(prev);
      if (k && !nextKeys.has(k)) {
        window.dispatchEvent(new CustomEvent('delegate-archived', { detail: {
          delegateId: String(prev.delegateId || ''),
          taskNo: String(prev.taskNo || ''),
          title: String(prev.title || ''),
          status: String(prev.status || ''),
        } }));
      }
    }
  }
  mirror.rows = rows;
  emptyKeys.clear();
  const keys = Array.from(new Set(rows.map(rowKey).filter(Boolean)));
  const childIds = Array.from(new Set(
    rows.filter((r) => !r.external && r.delegateId).map((r) => r.delegateId),
  ));
  await Promise.all([
    ...keys.map((k) => refreshChain(k).catch(() => {})),
    ...childIds.map((id) => refreshChildren(id).catch(() => {})),
  ]);
  if (seq !== mirror.seq) return; // 刷新期间又有更新在途——广播交给最新一轮
  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
    window.dispatchEvent(new CustomEvent('delegate-chain-mirror-updated'));
  }
}

/** 归档动作后的镜像即时对账（bgAgentPopup 归档入口回调；fire-and-forget fail-soft
 *  ——归档行移历史窗，在册窗重刷后行自然退场）。 */
export function refreshMirror() {
  refresh().catch(() => {});
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

// G3 星型子代理读口挂接（delegate-ui 批）：渲染面（main.js 面板两级分组 /
// bgAgentPopup 星型区 / taskList S1 标注）经 stub listDelegateChildren 读取本镜像。
setChildrenSource({
  listDelegateChildren(delegateId) {
    return childrenMirror.get(String(delegateId == null ? '' : delegateId)) || [];
  },
});

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

// delegate 生命周期触发（e2e 断点①修 · 2026-10-07）：裸 delegate spawn/续聊/终态
// 不经作用域图——node* 帧族在「无 AgentFlow 链活动」的整段在飞期为零（实测：
// rig :8245 /api/chains 22s 即有行，页面不刷新则任务列表恒空直到 reload）。本组
// 触发器把 delegate/external 会话自身的 turn 边界帧（nodeSessionId 前缀判据）
// 也接进同一防抖刷——帧仍只当触发器，数据权威恒在 REST /api/chains；tool 级
// 帧（agentToolStart/End）不接（每工具一帧太频，行状态变更已由 turn 边界覆盖：
// spawn=agentStart、delegate_report=turn 内写盘 + 该 turn 的 agentDone 兜底）。
const DELEGATE_TRIGGER_RE = /^(delegate|external)-/;
function onDelegateLifecycle(msg) {
  const nsid = String((msg && (msg.nodeSessionId || msg.agentId)) || '');
  if (DELEGATE_TRIGGER_RE.test(nsid)) scheduleRefresh();
}
for (const evt of ['agentStart', 'agentDone', 'agentEnd']) {
  onMessage(evt, onDelegateLifecycle);
}
onReconnect(() => refresh().catch(() => {}));
