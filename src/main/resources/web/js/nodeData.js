// nodeData.js — Project / Node / Flow Map 数据与契约常量的单一来源。
//
// 契约：~/.nebflow/docs/Nebflow/20260901_project-node-contract.md（Backend #28 0b）。
// 所有 REST 端点与 WS 事件路径集中在本文件，前端据此对接后端。
//
//   GET /api/projects → {projects:[{name,workspace,agentFile,description,createdAt}]}
//   GET /api/projects/<name>/flow-map → NodeList 载荷 {nodes[],worktrees[],chains?[],meta}
//     （链级抽象 P0：chains = 顶层旁挂条件键 [{id,title,entries,ends,memberIds}]，
//     title 后端三级推导下发；节点 payload 条件键 chainId——可能缺失，缺失 = 孤立
//     节点。旧后端无这两键，前端零派生优雅降级）
//     未挂载 → 404 {error}
//   GET /api/projects/<name>/flow-map/archive → Flow Archive 分批 {batches[],ttlMs,count}
//     （裁定④「TTL 分开」批：归档面板后端数据源；显示窗 24h）未挂载 → 404 {error}
//   GET /api/projects/<name>/agent.md → {content}（项目不存在/无 AGENTS.md → 404；旧 .nebflow/Agent.md 兼容回落）
//   PUT /api/projects/<name>/agent.md → body {content} → {saved:true}（写回工作区根 AGENTS.md）
// WS 事件广播帧 {type,project,nodeId,node}：
//   nodeCreated / nodeUpdated / nodeCompleted / nodeRemoved

import { authHeaders } from './flowHelpers.js';

// ── 契约常量（与 Backend #28 0b 对齐）──────────────────────
export const API = {
  // 项目列表（契约 §1）：GET /api/projects → {projects:[...]}
  projects: '/api/projects',
  // 某项目 Flow Map 快照（契约 §3 NodeList 载荷）：GET /api/projects/<name>/flow-map；未挂载 404
  flowMap: (name) => `/api/projects/${encodeURIComponent(name)}/flow-map`,
  // 节点结果全文（20260904 归档详情窗「全文完整显示」；20260905 载荷收敛后为
  // 结果全文的两条按需通道之一——另一条 = NodeList(detail=<nodeId>) 工具参数；
  // 快照/事件载荷只带 hasResult 标记（元数据 only），全文仅此端点按需提供；
  // 未挂载/无节点 404
  nodeResult: (name, nodeId) => `/api/projects/${encodeURIComponent(name)}/flow-map/nodes/${encodeURIComponent(nodeId)}/result`,
  // Flow Archive 分批内容（裁定④「TTL 分开」批 2026-09-07）：归档面板数据源——
  // GET → {batches:[{id,archivedAt,completedAt,members:[NodePayload 元数据]}],ttlMs,count}；
  // 服务端按显示窗（24h）过滤聚合；未挂载 404
  flowMapArchive: (name) => `/api/projects/${encodeURIComponent(name)}/flow-map/archive`,
  // 项目 AGENTS.md（契约 §1）：GET 读 → {content} / PUT 存 → body {content} → {saved:true}；
  // URL 不变，磁盘读写工作区根 AGENTS.md（旧 .nebflow/Agent.md 由后端回落兼容）
  agentFile: (name) => `/api/projects/${encodeURIComponent(name)}/agent.md`,
};

export const NODE_WS = {
  created: 'nodeCreated',
  updated: 'nodeUpdated',
  completed: 'nodeCompleted',
  removed: 'nodeRemoved',
};

// blocked（20260902 反馈路径设计 §4.2）：Node→分发器反馈重入的终态——turn 正常结束
// 但节点声明无法继续，需分发器调整任务/拓扑；待办语义（ttlExpireAt=None 常驻活动图）。
export const NODE_STATUS = ['pending', 'running', 'completed', 'failed', 'blocked', 'cancelled', 'wiring'];

// 节点状态 → 面板状态色 class（复用 flow-run 的 solar 状态色，见 flowCss.js）
export const NODE_STATUS_CLS = {
  pending: 'pending',
  running: 'running',
  completed: 'completed',
  failed: 'failed',
  blocked: 'blocked',
  cancelled: 'cancelled',
  wiring: 'pending',
};

// ── 数据访问层（真实 REST，契约 §1/§3）───────────────────
/** 项目列表。GET /api/projects（需 auth）→ {projects:[...]} */
export async function fetchProjects() {
  const r = await fetch(API.projects, { headers: authHeaders() });
  if (!r.ok) throw new Error(`projects ${r.status}`);
  const data = await r.json();
  return Array.isArray(data?.projects) ? data.projects : [];
}

/** 某项目 Flow Map 快照。GET /api/projects/<name>/flow-map（需 auth）→ NodeList 载荷。
 *  未挂载（404）返回带 notMounted 标记的空 NodeList——调用方据此区分「项目没在跑」
 *  与「项目在跑但没有节点」（两者旧版都渲染成"暂无节点"，无法诊断）；
 *  其余错误上抛由调用方展示 loadFail。 */
export async function fetchFlowMap(projectName) {
  const r = await fetch(API.flowMap(projectName), { headers: authHeaders() });
  if (r.status === 404) {
    return { nodes: [], worktrees: [], meta: { project: projectName, updatedAt: Date.now() }, notMounted: true };
  }
  if (!r.ok) throw new Error(`flow-map ${r.status}`);
  return await r.json();
}

/** 节点结果全文取用超时（ms，2026-09-11 fmresult 批）。取值理由：结果端点是网关
 *  本地内存读（单节点 results/<id>.md 水合全文），正常往返为毫秒级（实测 <20ms，
 *  见 .nebflow/evidence/20260911_fmresult-placeholder/）；12s 留足网关在 agent 回合
 *  高峰/归档大批量下的抖动余量，同时把「占位无限挂起」压缩为有界等待——超过即落
 *  明确错误态（原因 + 可操作提示），用户不再面对无终态的「…」。 */
export const NODE_RESULT_TIMEOUT_MS = 12000;
/** 超时标记 message（与 fetch 自身的网络拒绝区分）。 */
const NODE_RESULT_TIMEOUT_MSG = 'nf-result-timeout';

/**
 * 节点结果三态结果对象（见 fetchNodeResultDetail 文档）。
 * @typedef {{state: 'ok', result: string}
 *   | {state: 'empty'}
 *   | {state: 'http', status: number}
 *   | {state: 'network'}
 *   | {state: 'timeout'}} NodeResultOutcome
 */

/** 节点结果全文。GET /api/projects/<name>/flow-map/nodes/<nodeId>/result（需 auth）→ {id,name,status,result}。
 *  20260905 载荷收敛：NodePayload 快照/事件为元数据 only（无 result 键，hasResult 标记），
 *  详情窗打开时经本调用按需取全文。
 *
 *  2026-09-11 fmresult 批：返回值由「全文 | null」升级为**三态结果对象**——旧形态把
 *  「节点无结果（200 + result:null）」与「端点不可达（404/5xx/网络/超时）」压成同一个
 *  null，详情窗只能一律落空态文案 ⇒ 「错误态与空态混淆」且挂死请求让占位「…」永无终态。
 *  状态语义（消费端 = flowMapArchive 结果区终态判定）：
 *    ok      200 且 result 为非空字符串（全文）
 *    empty   200 但 result 非字符串/空白（节点确实没有结果——不是错误）
 *    http    非 2xx（404 = 节点不在活动区/归档区或项目未挂载；5xx = 服务异常）
 *    network fetch 拒绝或响应不可解析（断网/连接被拒/坏响应）
 *    timeout 超时无响应（AbortController 中止，见 NODE_RESULT_TIMEOUT_MS）
 *  调用方按 state 分流：ok → 换装、empty → 空态、其余 → 明确错误态。
 *  @param {string} projectName @param {string} nodeId @param {number=} timeoutMs
 *  @returns {Promise<NodeResultOutcome>} */
export async function fetchNodeResultDetail(projectName, nodeId, timeoutMs = NODE_RESULT_TIMEOUT_MS) {
  const ctrl = typeof AbortController === 'function' ? new AbortController() : null;
  /** @type {any} */
  let timer = 0;
  try {
    /** 超时守（2026-09-11 fmresult 批）：结果端点无响应时旧实现让详情窗占位「…」
     *  无限挂起（用户无任何终态、无重试线索）。本 Promise 到点即 abort 底层请求并
     *  以固定 message 标记超时（与网络拒绝区分）。 */
    const timeout = new Promise((_, rej) => {
      timer = setTimeout(() => {
        rej(new Error(NODE_RESULT_TIMEOUT_MSG)); // 先落超时判定（abort 触发的 AbortError 会先入微任务队列抢跑）
        if (ctrl) ctrl.abort();
      }, timeoutMs);
    });
    const res = await /** @type {Response} */ (await Promise.race([
      fetch(API.nodeResult(projectName, nodeId), ctrl ? { headers: authHeaders(), signal: ctrl.signal } : { headers: authHeaders() }),
      timeout,
    ]));
    if (!res.ok) return { state: 'http', status: res.status };
    const data = await res.json();
    const result = typeof data?.result === 'string' ? data.result : '';
    return result.trim() ? { state: 'ok', result } : { state: 'empty' };
  } catch (e) {
    const err = /** @type {any} */ (e);
    // AbortError 仅由本函数的超时 abort 产生（无其它取消源）⇒ 同样归入 timeout。
    const isTimeout = !!(err && (err.message === NODE_RESULT_TIMEOUT_MSG || err.name === 'AbortError'));
    return { state: isTimeout ? 'timeout' : 'network' };
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/** Flow Archive 分批内容（裁定④「TTL 分开」批）。GET .../flow-map/archive（需 auth）
 *  → batches 数组（显示窗 24h 内，服务端已过滤）。未挂载/异常 → 返回 null（调用方
 *  静默保留既有派生数据，下触发重试）。 */
export async function fetchFlowMapArchive(projectName) {
  try {
    const r = await fetch(API.flowMapArchive(projectName), { headers: authHeaders() });
    if (!r.ok) return null;
    const data = await r.json();
    return Array.isArray(data?.batches) ? data.batches : null;
  } catch (_) {
    return null;
  }
}

/** 从 NodeList 汇总项目简要状态（「当前后台运行 agent 数」+「简要状态」）。 */
export function summarize(fm) {
  const nodes = fm?.nodes || [];
  const running = nodes.filter((n) => n.status === 'running').length;
  const failed = nodes.filter((n) => n.status === 'failed').length;
  const pending = nodes.filter((n) => n.status === 'pending').length;
  const completed = nodes.filter((n) => n.status === 'completed').length;
  let brief;
  if (running > 0) brief = `${running} 节点运行中`;
  else if (failed > 0) brief = `${failed} 节点失败`;
  else if (pending > 0) brief = `${pending} 节点等待`;
  else if (nodes.length === 0) brief = '空闲';
  else brief = `${completed} 节点已完成`;
  // total：完整节点数（含已归档终态节点）——项目面板据此判断「有节点才可点进 Flow Map」。
  return { total: nodes.length, running, failed, pending, completed, brief, notMounted: !!fm?.notMounted };
}

// ── AGENTS.md 读取/保存（契约 §1：GET / PUT /api/projects/<name>/agent.md）──

/** 读取项目 AGENTS.md。GET → {content}；404（项目不存在/无 AGENTS.md）返回缺省文本。 */
export async function fetchAgentFile(name) {
  const r = await fetch(API.agentFile(name), { headers: authHeaders() });
  if (r.status === 404) return '# AGENTS.md\n\n（暂无内容）\n';
  if (!r.ok) throw new Error(`agent.md ${r.status}`);
  const data = await r.json();
  return data?.content ?? '';
}

/** 保存项目 AGENTS.md。PUT body {content} → {saved:true}（写回工作区根 AGENTS.md）。 */
export async function saveAgentFile(name, content) {
  const r = await fetch(API.agentFile(name), {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
  if (!r.ok) throw new Error(`agent.md save ${r.status}`);
  return { ok: true };
}
