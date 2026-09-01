// nodeData.js — Project / Node / Flow Map 数据与契约常量的单一来源。
//
// 契约：~/.nebflow/docs/Nebflow/20260901_project-node-contract.md（Backend #28 0b）。
// 所有 REST 端点与 WS 事件路径集中在本文件，前端据此对接后端。
//
//   GET /api/projects → {projects:[{name,workspace,agentFile,description,createdAt}]}
//   GET /api/projects/<name>/flow-map → NodeList 载荷 {nodes[],worktrees[],meta}
//     未挂载 → 404 {error}
//   GET /api/projects/<name>/agent.md → {content}（项目不存在/无 Agent.md → 404）
//   PUT /api/projects/<name>/agent.md → body {content} → {saved:true}（写回 .nebflow/Agent.md 原子写）
// WS 事件广播帧 {type,project,nodeId,node}：
//   nodeCreated / nodeUpdated / nodeCompleted / nodeRemoved

import { authHeaders } from './flowHelpers.js';

// ── 契约常量（与 Backend #28 0b 对齐）──────────────────────
export const API = {
  // 项目列表（契约 §1）：GET /api/projects → {projects:[...]}
  projects: '/api/projects',
  // 某项目 Flow Map 快照（契约 §3 NodeList 载荷）：GET /api/projects/<name>/flow-map；未挂载 404
  flowMap: (name) => `/api/projects/${encodeURIComponent(name)}/flow-map`,
  // 项目 Agent.md（契约 §1）：GET 读 → {content} / PUT 存 → body {content} → {saved:true}；写回工作区 .nebflow/Agent.md
  agentFile: (name) => `/api/projects/${encodeURIComponent(name)}/agent.md`,
};

export const NODE_WS = {
  created: 'nodeCreated',
  updated: 'nodeUpdated',
  completed: 'nodeCompleted',
  removed: 'nodeRemoved',
};

export const NODE_STATUS = ['pending', 'running', 'completed', 'failed', 'cancelled', 'wiring'];

// 节点状态 → 面板状态色 class（复用 flow-run 的 solar 状态色，见 flowCss.js）
export const NODE_STATUS_CLS = {
  pending: 'pending',
  running: 'running',
  completed: 'completed',
  failed: 'failed',
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
  return { running, failed, pending, completed, brief, notMounted: !!fm?.notMounted };
}

// ── Agent.md 读取/保存（契约 §1：GET / PUT /api/projects/<name>/agent.md）──

/** 读取项目 Agent.md。GET → {content}；404（项目不存在/无 Agent.md）返回缺省文本。 */
export async function fetchAgentFile(name) {
  const r = await fetch(API.agentFile(name), { headers: authHeaders() });
  if (r.status === 404) return '# Agent.md\n\n（暂无内容）\n';
  if (!r.ok) throw new Error(`agent.md ${r.status}`);
  const data = await r.json();
  return data?.content ?? '';
}

/** 保存项目 Agent.md。PUT body {content} → {saved:true}（写回工作区 .nebflow/Agent.md 原子写）。 */
export async function saveAgentFile(name, content) {
  const r = await fetch(API.agentFile(name), {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  });
  if (!r.ok) throw new Error(`agent.md save ${r.status}`);
  return { ok: true };
}
