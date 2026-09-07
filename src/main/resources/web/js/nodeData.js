// nodeData.js — Project / Node / Flow Map 数据与契约常量的单一来源。
//
// 契约：~/.nebflow/docs/Nebflow/20260901_project-node-contract.md（Backend #28 0b）。
// 所有 REST 端点与 WS 事件路径集中在本文件，前端据此对接后端。
//
//   GET /api/projects → {projects:[{name,workspace,agentFile,description,createdAt}]}
//   GET /api/projects/<name>/flow-map → NodeList 载荷 {nodes[],worktrees[],meta}
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

/** 节点结果全文。GET /api/projects/<name>/flow-map/nodes/<nodeId>/result（需 auth）→ {id,name,status,result}。
 *  20260905 载荷收敛：NodePayload 快照/事件为元数据 only（无 result 键，hasResult 标记），
 *  详情窗打开时经本调用按需取全文；节点不存在/项目未挂载（404）返回 null——调用方
 *  静默兜底。result 为 null（节点无结果）→ null。 */
export async function fetchNodeResult(projectName, nodeId) {
  const r = await fetch(API.nodeResult(projectName, nodeId), { headers: authHeaders() });
  if (!r.ok) return null;
  const data = await r.json();
  return typeof data?.result === 'string' ? data.result : null;
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
