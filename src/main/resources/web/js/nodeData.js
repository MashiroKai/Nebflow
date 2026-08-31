// nodeData.js — Project / Node / Flow Map 数据与契约常量的单一来源。
//
// #27 阶段 0：后端 REST/WS 契约（Backend #28 0b）尚未到达，UI 骨架先按 v4 方案
// §2.2/§3.5 结构开发，数据层用 mock 渲染。**契约到达后只需替换 fetch* 实现**——
// 所有 REST/WS 路径集中在本文件，避免散落各处造成返工。
//
// 字段以 §2.2 NodeList 返回结构为准：
//   nodes[]: id/name/agent/status/in/out/hasWorktree/worktree/result/retries/
//            createdAt/completedAt/ttlLeftSec  + worktrees[] + meta
// WS 事件 §2.6：nodeCreated/nodeUpdated/nodeCompleted/nodeRemoved

// ── 契约常量（Backend #28 0b 到达后核对/替换）────────────────
export const API = {
  // 项目列表（§1.1 每项 name/description/workspace/agentFile/createdAt）
  projects: '/api/projects/list',
  // 某项目 Flow Map 快照（§2.2 NodeList 返回结构）
  flowMap: (name) => `/api/projects/${encodeURIComponent(name)}/flowmap`,
  // 在文件浏览器中打开工作区（§3.5；后端未定，先占位）
  openWorkspace: (name) => `/api/projects/${encodeURIComponent(name)}/open`,
  // Agent.md 查看/编辑（§3.5；后端未定，先占位）
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

// ── Mock 数据源（契约到达后整体替换为 fetch）────────────────
// 阶段 0 演示用：3 个项目，覆盖 running / completed(TTL 倒计时) / failed /
// worktree 徽标 / barrier 拓扑 / 空闲等状态。

const MOCK_PROJECTS = [
  {
    name: 'phd-notebook',
    description: '博士课题文献调研与成稿（调研/写作类试点）',
    workspace: '~/phd-notebook',
    agentFile: '~/.nebflow/projects/phd-notebook/Agent.md',
    createdAt: 1725123456789,
  },
  {
    name: 'nebflow',
    description: 'Nebflow 开源项目开发维护',
    workspace: '~/Claude code/Nebflow',
    agentFile: '~/.nebflow/projects/nebflow/Agent.md',
    createdAt: 1725150000000,
  },
  {
    name: 'writer-blog',
    description: '技术博客写作，周更一名',
    workspace: '~/writer-blog',
    agentFile: '~/.nebflow/projects/writer-blog/Agent.md',
    createdAt: 1725200000000,
  },
];

// §2.2 NodeList 返回结构（nodes[] + worktrees[] + meta）
const MOCK_FLOWMAPS = {
  'phd-notebook': {
    nodes: [
      { id: 'n-3f9a2b', name: '调研-康普顿成像原理', agent: 'Explorer', status: 'completed',
        in: [], out: 'n-7c1d2e', hasWorktree: false, worktree: null,
        result: '【调研结论摘要】康普顿成像的核心是单光子/电子散射截面…（约 500 字摘要，点击查看全文）',
        retries: 0, createdAt: 1725123456789, completedAt: 1725123499999, ttlLeftSec: 132 },
      { id: 'n-8a1b3c', name: '调研-同步辐射光源', agent: 'Explorer', status: 'completed',
        in: [], out: 'n-7c1d2e', hasWorktree: false, worktree: null,
        result: '【调研结论摘要】同步辐射光源的谱线特性…（约 420 字摘要，点击查看全文）',
        retries: 0, createdAt: 1725123457000, completedAt: 1725123500000, ttlLeftSec: 118 },
      { id: 'n-7c1d2e', name: '合并-两条线成稿', agent: 'Explorer', status: 'running',
        in: ['n-3f9a2b', 'n-8a1b3c'], out: 'Nebula', hasWorktree: true, worktree: 'worktrees/wt-new',
        result: null, retries: 1, createdAt: 1725123500000, completedAt: null, ttlLeftSec: null },
      { id: 'n-5c6d7e', name: '成文-初稿', agent: 'Writer', status: 'pending',
        in: ['n-7c1d2e'], out: 'Nebula', hasWorktree: false, worktree: null,
        result: null, retries: 0, createdAt: 1725123600000, completedAt: null, ttlLeftSec: null },
    ],
    worktrees: ['worktrees/wt-new'],
    meta: { project: 'phd-notebook', updatedAt: 1725123600000 },
  },
  'nebflow': {
    nodes: [
      { id: 'n-a10001', name: '修复-静态资源可达性', agent: 'Coder', status: 'failed',
        in: [], out: 'n-a20002', hasWorktree: false, worktree: null,
        result: '【失败】sbt compile 报错：参数类型不匹配…（查看错误详情）',
        retries: 2, createdAt: 1725125000000, completedAt: 1725125600000, ttlLeftSec: 42 },
      { id: 'n-a20002', name: '复审-静资源修复', agent: 'Reviewer', status: 'pending',
        in: ['n-a10001'], out: 'Nebula', hasWorktree: false, worktree: null,
        result: null, retries: 0, createdAt: 1725125600000, completedAt: null, ttlLeftSec: null },
    ],
    worktrees: [],
    meta: { project: 'nebflow', updatedAt: 1725125600000 },
  },
  'writer-blog': {
    nodes: [
      { id: 'n-b10001', name: '选题-本周主题', agent: 'Writer', status: 'completed',
        in: [], out: 'Nebula', hasWorktree: false, worktree: null,
        result: '【选题结论】本周从「分布式系统的可观测性」切入…（约 300 字）',
        retries: 0, createdAt: 1725127000000, completedAt: 1725127100000, ttlLeftSec: 0 },
    ],
    worktrees: [],
    meta: { project: 'writer-blog', updatedAt: 1725127100000 },
  },
};

// ── 数据访问层（mock；契约到达后替换为 fetch）───────────────
/** 项目列表。契约后替换为：fetch(API.projects).then(r => r.json()) */
export async function fetchProjects() {
  // 模拟网络延迟，让骨架的加载态可见
  await new Promise((r) => setTimeout(r, 120));
  return MOCK_PROJECTS.map((p) => ({ ...p }));
}

/** 某项目 Flow Map 快照。契约后替换为：
 *  fetch(API.flowMap(name)).then(r => r.json()) */
export async function fetchFlowMap(projectName) {
  await new Promise((r) => setTimeout(r, 140));
  const fm = MOCK_FLOWMAPS[projectName];
  if (fm) return JSON.parse(JSON.stringify(fm));
  return { nodes: [], worktrees: [], meta: { project: projectName, updatedAt: Date.now() } };
}

/** 从 NodeList 汇总项目简要状态（§3.5「当前后台运行 agent 数」+「简要状态」）。 */
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
