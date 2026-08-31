// nodeData.js — Project / Node / Flow Map 数据与契约常量的单一来源。
//
// 契约：~/.nebflow/docs/Nebflow/20260901_project-node-contract.md（Backend #28 0b）。
// 所有 REST 端点与 WS 事件路径集中在本文件，前端据此对接后端。
//
//   GET /api/projects → {projects:[{name,workspace,agentFile,description,createdAt}]}
//   GET /api/projects/<name>/flow-map → NodeList 载荷 {nodes[],worktrees[],meta}
//     未挂载 → 404 {error}
// WS 事件广播帧 {type,project,nodeId,node}：
//   nodeCreated / nodeUpdated / nodeCompleted / nodeRemoved
//
// ⚠️ #28 0b 契约未提供 agent.md REST 端点（只有 /api/projects 与 flow-map）；
//    Agent.md 读写暂用本地 mock，待后端补充 GET/POST 后替换。

import { authHeaders } from './flowHelpers.js';

// ── 契约常量（与 Backend #28 0b 对齐）──────────────────────
export const API = {
  // 项目列表（契约 §1）：GET /api/projects → {projects:[...]}
  projects: '/api/projects',
  // 某项目 Flow Map 快照（契约 §3 NodeList 载荷）：GET /api/projects/<name>/flow-map；未挂载 404
  flowMap: (name) => `/api/projects/${encodeURIComponent(name)}/flow-map`,
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
 *  未挂载（404）返回空 NodeList（dag-empty 态），其余错误上抛由调用方展示 loadFail。 */
export async function fetchFlowMap(projectName) {
  const r = await fetch(API.flowMap(projectName), { headers: authHeaders() });
  if (r.status === 404) {
    return { nodes: [], worktrees: [], meta: { project: projectName, updatedAt: Date.now() } };
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
  return { running, failed, pending, completed, brief };
}

// ── Agent.md 读取/保存（⚠️ 契约无 REST 端点，暂用本地 mock）──
const MOCK_AGENT_FILES = {
  'phd-notebook': [
    '# Agent.md',
    '',
    '项目级 agent 指令（phd-notebook 示例）。',
    '- 文献调研优先走学术检索与引用链。',
    '- 成稿前先列提纲，评审后定稿。',
    '',
  ].join('\n'),
  'nebflow': [
    '# Agent.md',
    '',
    'Nebflow 开发指令（示例）。',
    '- 改动前先读 CODEBASE.md。',
    '- 前端改动须过 verify-web-assets.mjs。',
    '',
  ].join('\n'),
  'writer-blog': [
    '# Agent.md',
    '',
    '技术博客写作（示例）。',
    '- 每周一选题，短小精悍。',
    '',
  ].join('\n'),
};

/** 读取项目 Agent.md（mock；契约无 agent.md GET 端点，后端补后替换）。 */
export async function fetchAgentFile(name) {
  await new Promise((r) => setTimeout(r, 90));
  return MOCK_AGENT_FILES[name] || '# Agent.md\n\n（暂无内容）\n';
}

/** 保存项目 Agent.md（mock；契约无 agent.md POST 端点，后端补后替换）。 */
export async function saveAgentFile(name, content) {
  await new Promise((r) => setTimeout(r, 90));
  if (MOCK_AGENT_FILES[name] !== undefined) MOCK_AGENT_FILES[name] = content;
  return { ok: true };
}
