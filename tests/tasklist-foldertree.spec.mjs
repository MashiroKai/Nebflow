// tasklist-foldertree.spec.mjs — 任务列表「文件夹树」批（2026-10-09 · 对账报告
// 20261009_221900_tasklist-foldertree-recon §二 定稿 + 四条裁定）前端位验收：
//   T1 根行收起/展开（chevron 示能旋转；解释帧退场=纯结构树；行内零 workflow 钮；
//      折叠记忆跨重渲）
//   T2 子节点行点击 = 跳该 delegate 的 L1 委托流程图（R4 语义平移；零弹窗）
//   T3 flow 第三层（用户手绘新增）：flow 子行 chevron 展开/收起链 Node 行族
//      （节点名+状态点）；点具体 Node = 跳 L1 并锚到对应节点（anchorNode——flow
//      层展开 + sapphire 描边短闪）；点 flow 行本体 = expandFlow 锚点（R5 语义不变）
//   T4 叶行（待合并/外部/空树）= 不可见占位 + 零动作（九点④/§二.3 Finder 对齐纪律）
//   T5 L2 入口迁移（方案 A2）：项目面板「项目流程图」圆钮（「+」上方叠钮）→ L2；
//      delegate-chain 视图摘双钮（视图守卫）；项目卡标题点按入口保留
//   T6 混合树（评审修正① 2026-10-10）：同一 root delegate 同挂 subagent 子行族
//      （×3）+ flow 子行（含第三层 Node）——双徽标同排、同展开面共存、层序、
//      子行/Node 交互语义在混合态不回归
//   T7 执行器图标 = 本地原生资产（评审修正②③ 2026-10-10）：图标资产源断言——
//      引用仓库本地自托管资产（零运行时外链）、退役手绘品牌路径零残留；DOM 面
//      （树行——delegate/node 面板徽标同件自动跟随）解析真实资源；zcode/hermes
//      无官方件如实保留内联件
//
// 数据面 = stub 读取口夹具（同 delegate-ui-twolayer harness）；零后端、零 sbt。
// Run: node node_modules/@playwright/test/cli.js test tests/tasklist-foldertree.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'tft-root-session';
const T0 = Date.now();

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], total: 0, offset: 0, hasMore: false }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 装 stub 夹具并重渲。 */
async function setFixture(page, fx) {
  await page.evaluate(async ({ f, sid }) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, { f: fx, sid: ROOT_SID });
  await page.waitForTimeout(100);
}

/** 入场动画类摘除后再交互（行点击稳定性，同 delegate-ui-twolayer 惯例）。 */
async function settlePanel(page) {
  await page.waitForFunction(() => !document.querySelector('#task-list .task-node-entering'), null, { timeout: 5000 }).catch(() => {});
}

// ── 夹具（twolayer-impl-shots 同源三行 + 内部待合并行）─────────────────────
const rowStar = {
  taskNo: '71', delegateId: 'delegate-nebflow-w1', title: '星型编排任务',
  executor: 'nebflow', status: 'running', external: false, project: 'Nebflow',
  orchestrationMode: 'subagent-star', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
  createdAt: T0 - 60_000,
};
const rowFlow = {
  taskNo: '72', delegateId: 'delegate-nebflow-w2', title: 'flow 编排任务',
  executor: 'nebflow', status: 'running', external: false, project: 'Nebflow',
  orchestrationMode: 'agentflow', archivedAt: null, mergeQueue: null, mergeQueuePos: null,
  createdAt: T0 - 30_000,
};
const rowExternal = {
  taskNo: '73', delegateId: 'delegate-zcode-x73', title: '外部执行器行',
  executor: 'zcode', status: 'running', external: true, project: '',
  createdAt: T0 - 20_000,
};
const rowQueued = {
  taskNo: '74', delegateId: 'delegate-nebflow-q74', title: '内部待合并行',
  executor: 'nebflow', status: 'ready-to-merge', external: false, project: 'Nebflow',
  mergeQueuePos: { position: 1, total: 2, branch: 'delegate/task-74' },
  createdAt: T0 - 15_000,
};
const rowEmptyTree = {
  taskNo: '75', delegateId: 'delegate-nebflow-e75', title: '空树内部行',
  executor: 'nebflow', status: 'running', external: false, project: 'Nebflow',
  createdAt: T0 - 10_000,
};
const chainFlow = {
  taskId: '72', delegateId: 'delegate-nebflow-w2',
  nodes: [
    { id: 'delegate-nebflow-w2', kind: 'delegate', label: '#72', description: '', status: 'running', deps: [], executor: '' },
    { id: 's1', kind: 'flow-step', label: '步骤一', description: '', status: 'running', deps: [], executor: '' },
    { id: 's2', kind: 'flow-step', label: '步骤二', description: '', status: 'pending', deps: ['s1'], executor: '' },
  ],
  worktree: null, verdict: null, mergeQueue: [], frames: [],
};
const chainEmptyTree = {
  taskId: '75', delegateId: 'delegate-nebflow-e75',
  nodes: [{ id: 'delegate-nebflow-e75', kind: 'delegate', label: '#75', description: '', status: 'running', deps: [], executor: 'nebflow' }],
  worktree: { branch: 'delegate/task-75', worktree: '/wt/75', cwd: '/wt/75', baseBranch: 'main' },
  verdict: { verdict: 'running', reason: '', source: '', at: 0 }, mergeQueue: [], frames: [],
};
const childrenStar = {
  'delegate-nebflow-w1': [
    { taskId: 'subtask-a', agentName: 'scout', status: 'running', source: 'subtask', spawnedAt: T0 },
    { taskId: 'subtask-b', agentName: 'audit', status: 'completed', source: 'subtask', retryCount: 1, spawnedAt: T0 - 60_000, completedAt: T0 - 30_000 },
    { taskId: 'subagent-c', agentName: 'recon', status: 'running', source: 'subagent', spawnedAt: T0 },
  ],
};

// ── 评审修正批夹具（2026-10-10）────────────────────────────────────────────
// 混合形态链：#71 同挂 subagent 子行族（childrenStar）+ flow 链（m1/m2/m3）。
const chainMixed = {
  taskId: '71', delegateId: 'delegate-nebflow-w1',
  nodes: [
    { id: 'delegate-nebflow-w1', kind: 'delegate', label: '#71 混合编排任务', description: '', status: 'running', deps: [], executor: 'nebflow' },
    { id: 'm1', kind: 'flow-step', label: '盘点现状', description: '', status: 'completed', deps: [], executor: '' },
    { id: 'm2', kind: 'flow-step', label: '并行落渲染面', description: '', status: 'running', deps: ['m1'], executor: '' },
    { id: 'm3', kind: 'flow-step', label: '验收回归', description: '', status: 'pending', deps: ['m2'], executor: '' },
  ],
  worktree: null, verdict: null, mergeQueue: [], frames: [],
};
// 图标面夹具：claude-code（外部行）/ codex（内部空树行）——executorIcon 全 id 消费。
const rowClaude = {
  taskNo: '76', delegateId: 'delegate-claude-c76', title: '外部执行器行（Claude Code）',
  executor: 'claude-code', status: 'running', external: true, project: '',
  createdAt: T0 - 5_000,
};
const rowCodex = {
  taskNo: '77', delegateId: 'delegate-codex-c77', title: '空树内部行（Codex）',
  executor: 'codex', status: 'running', external: false, project: '',
  createdAt: T0 - 4_000,
};

test('T1 根行收起/展开：chevron 示能旋转＋纯结构树（解释帧退场）＋行内零 workflow 钮＋折叠记忆跨重渲', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: [rowStar, rowFlow, rowExternal, rowQueued, rowEmptyTree], chains: [chainFlow, chainEmptyTree], children: childrenStar });
  await settlePanel(page);

  const panel = page.locator('#task-list');
  const r71 = panel.locator('.task-delegate[data-task-no="71"]');
  // 收起态：aria-expanded=false；chevron 示能在场（非叶）；内部行零行尾钮。
  await expect(r71).toHaveAttribute('aria-expanded', 'false');
  await expect(r71.locator('> .task-tree-chev')).toHaveCount(1);
  await expect(r71.locator('> .task-tree-chev')).not.toHaveClass(/task-tree-chev-leaf/);
  await expect(r71.locator('.task-delegate-btn')).toHaveCount(0);
  await expect(panel.locator('.task-delegate-detail')).toHaveCount(0);

  // 点行展开 = 纯结构树：子代理行族在场；解释帧族零渲染（R3）。
  await r71.click();
  await expect(r71).toHaveAttribute('aria-expanded', 'true');
  const detail = panel.locator('.task-delegate-detail[data-delegate-detail="71"]');
  await expect(detail).toHaveCount(1);
  await expect(detail.locator('.bgagent-child-row')).toHaveCount(3);
  await expect(detail.locator('[class*="task-delegate-frame"]')).toHaveCount(0);
  // chevron 随 aria-expanded 旋转 90°（computed transform 钉）。
  const rot = await r71.locator('> .task-tree-chev svg').evaluate((el) => getComputedStyle(el).transform);
  expect(rot).toBe('matrix(0, 1, -1, 0, 0, 0)');

  // 折叠记忆跨重渲（显式重渲入口）。
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  await expect(panel.locator('.task-delegate-detail[data-delegate-detail="71"]')).toHaveCount(1);
  await expect(r71).toHaveAttribute('aria-expanded', 'true');

  // 再点收起。
  await r71.click();
  await expect(panel.locator('.task-delegate-detail')).toHaveCount(0);
  await expect(r71).toHaveAttribute('aria-expanded', 'false');

  expect(pageErrors).toEqual([]);
});

test('T2 子节点行点击 = 跳该 delegate 的 L1 委托流程图（R4；任务列表零弹窗）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: [rowStar], children: childrenStar });
  await settlePanel(page);

  const panel = page.locator('#task-list');
  const r71 = panel.locator('.task-delegate[data-task-no="71"]');
  await r71.click();
  const detail = panel.locator('.task-delegate-detail[data-delegate-detail="71"]');
  await expect(detail).toHaveCount(1);
  const kidRow = detail.locator('.bgagent-child-row[data-child-session="subtask-a"]');
  await expect(kidRow).toHaveAttribute('aria-label', '委托流程图'); // §五.6 子行 aria 复用既有键
  await kidRow.click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1).toHaveAttribute('data-delegate-key', '71');
  await expect(l1.locator('.delegate-structure-entry')).toHaveCount(0); // R7 顶栏纯导航化
  // 任务列表路径不开对话窗（对话窗入口无损平移到 L1 根卡/子卡）。
  await expect(page.locator('.flow-agent-overlay')).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('T3 flow 第三层：chevron 展开/收起链 Node 行族（名+状态点）＋点 Node 锚跳 L1＋点 flow 行=expandFlow＋第三层记忆', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: [rowFlow], chains: [chainFlow] });
  await settlePanel(page);

  const panel = page.locator('#task-list');
  const r72 = panel.locator('.task-delegate[data-task-no="72"]');
  await r72.click();
  const detail = panel.locator('.task-delegate-detail[data-delegate-detail="72"]');
  const flowRow = detail.locator('.task-flow-row');
  await expect(flowRow).toHaveCount(1);
  // 默认收起（零空态区块纪律延伸到第三层）：节点行族缺席。
  await expect(flowRow).toHaveAttribute('aria-expanded', 'false');
  await expect(detail.locator('.task-flow-nodes')).toHaveCount(0);

  // chevron 热区 = 展开/收起第三层（行其余热区 = 跳 L1）。
  await flowRow.locator('> .task-tree-chev').click();
  await expect(flowRow).toHaveAttribute('aria-expanded', 'true');
  const nodes = detail.locator('.task-flow-nodes .task-flow-node-row');
  await expect(nodes).toHaveCount(2);
  await expect(nodes.nth(0)).toHaveAttribute('data-flow-node-id', 's1');
  await expect(nodes.nth(0).locator('.task-flow-node-name')).toHaveText('步骤一');
  await expect(nodes.nth(0).locator('.delegate-chain-dot')).toHaveClass(/st-running/);
  await expect(nodes.nth(1)).toHaveAttribute('data-flow-node-id', 's2');
  await expect(nodes.nth(1).locator('.delegate-chain-dot')).toHaveClass(/st-pending/);

  // 第三层记忆：收起 root 再展开，节点行族仍在（expandedFlowTrees 不随 root 收起清除）。
  await r72.click();
  await expect(panel.locator('.task-delegate-detail')).toHaveCount(0);
  await r72.click();
  await expect(detail.locator('.task-flow-node-row')).toHaveCount(2);

  // 点具体 Node = 跳 L1 并锚到对应节点（anchorNode：flow 层展开 + sapphire 描边短闪）。
  await detail.locator('.task-flow-node-row[data-flow-node-id="s2"]').click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1.locator('.delegate-structure-entry')).toHaveCount(0);
  await expect(l1.locator('.delegate-flow-chain-toggle')).toHaveAttribute('aria-expanded', 'true');
  const anchorCard = l1.locator('.fm-node[data-node-id="s2"]');
  await expect(anchorCard).toHaveCount(1);
  await expect(anchorCard).toHaveClass(/delegate-node-anchor/);

  // 返回任务列表（关页签）→ 点 flow 行本体 = L1 expandFlow 锚点（R5 语义不变）。
  await l1.locator('[data-back-to-tasks]').click();
  await expect(l1).toHaveCount(0);
  await expect(detail.locator('.task-flow-node-row')).toHaveCount(2); // 面板重渲后仍在
  await flowRow.click();
  const l1b = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1b).toHaveCount(1);
  await expect(l1b.locator('.delegate-flow-chain-toggle')).toHaveAttribute('aria-expanded', 'true');
  await expect(l1b.locator('.delegate-flow-steps')).toBeVisible();
  expect(pageErrors).toEqual([]);
});

test('T4 叶行（待合并/外部/空树）= 不可见占位 chevron＋零动作（九点④·§二.3）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: [rowQueued, rowExternal, rowEmptyTree], chains: [chainEmptyTree] });
  await settlePanel(page);

  const panel = page.locator('#task-list');
  // 待合并行（内部）：九点④名次终态——占位 chevron、无 aria-expanded、点击零动作。
  const r74 = panel.locator('.task-delegate[data-task-no="74"]');
  await expect(r74.locator('> .task-tree-chev')).toHaveClass(/task-tree-chev-leaf/);
  await expect(r74).not.toHaveAttribute('aria-expanded');
  await r74.click();
  await expect(panel.locator('.task-delegate-detail')).toHaveCount(0);
  // 外部行：S16 守卫终态同款占位；openNative 出口钮保留（R8 边界）。
  const r73 = panel.locator('.task-delegate[data-task-no="73"]');
  await expect(r73.locator('> .task-tree-chev')).toHaveClass(/task-tree-chev-leaf/);
  await expect(r73).not.toHaveAttribute('aria-expanded');
  await expect(r73.locator('.task-delegate-btn')).toHaveCount(1);
  await r73.click();
  await expect(panel.locator('.task-delegate-detail')).toHaveCount(0);
  // 空树内部行：worktree/verdict 在场也不构成展开面（树批守卫 = children ∪ flow-step）。
  const r75 = panel.locator('.task-delegate[data-task-no="75"]');
  await expect(r75.locator('> .task-tree-chev')).toHaveClass(/task-tree-chev-leaf/);
  await expect(r75).not.toHaveAttribute('aria-expanded');
  await r75.click();
  await expect(panel.locator('.task-delegate-detail')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T5 L2 入口迁移（方案 A2）：项目面板「项目流程图」圆钮→L2＋delegate-chain 视图摘双钮＋标题点按入口保留', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  // 具体路由后注册 = 优先命中：给项目面板一张卡（标题点按入口需要非空列表）。
  await page.route('**/api/projects', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ projects: [{ name: 'Nebflow', path: '/tmp/nebflow' }] }),
  }));

  await page.evaluate(async () => {
    const m = await import('/js/projectTab.js');
    await m.openProjectsTab();
  });
  const pane = page.locator('.canvas-tab-pane[data-tab-id="projects"]');
  await expect(pane).toHaveAttribute('data-projects-view', 'list');
  // A2 圆钮：叠在「+」上方，title/aria = 既有键「项目流程图」（零新 locale 键）。
  const fab = pane.locator('.proj-flowmap-fab');
  await expect(fab).toHaveCount(1);
  await expect(fab).toHaveAttribute('title', '项目流程图');
  await expect(fab).toHaveAttribute('aria-label', '项目流程图');
  await expect(pane.locator('.proj-create-fab')).toHaveCount(1);

  // 点圆钮 → L2 结构总视图。
  await fab.click();
  await expect(pane).toHaveAttribute('data-projects-view', 'delegate-chain');
  await expect(pane).toHaveAttribute('data-delegate-mode', 'structure');
  // 视图守卫：delegate-chain 视图内双钮全摘（create-fab 同款守卫纪律）。
  await expect(pane.locator('.proj-flowmap-fab')).toHaveCount(0);
  await expect(pane.locator('.proj-create-fab')).toHaveCount(0);

  // 回列表视图：双钮随 ensureScroll 单点挂载回归。
  await page.evaluate(async () => {
    const m = await import('/js/projectTab.js');
    await m.openProjectsTab();
  });
  await expect(pane.locator('.proj-flowmap-fab')).toHaveCount(1);
  await expect(pane.locator('.proj-create-fab')).toHaveCount(1);
  // 既有入口保留：项目卡标题点按 → L2（flowmaptab-retire 改指面原行为不动）。
  await pane.locator('.project-card .team-card-title').click();
  await expect(pane).toHaveAttribute('data-projects-view', 'delegate-chain');
  await expect(pane).toHaveAttribute('data-delegate-mode', 'structure');

  expect(pageErrors).toEqual([]);
});

test('T6 混合树：同一 root delegate 同挂 subagent 子行族 + flow 子行（第三层 Node）——双徽标同排、同展开面共存、层序与交互语义', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: [rowStar], chains: [chainMixed], children: childrenStar });
  await settlePanel(page);

  const panel = page.locator('#task-list');
  const r71 = panel.locator('.task-delegate[data-task-no="71"]');
  // 收起态：混合行双徽标同排（users+3 ∧ flow+3——appendInlineBadges 两条独立读取口）。
  await expect(r71.locator('.task-kids-badge')).toHaveCount(1);
  await expect(r71.locator('.task-flow-badge')).toHaveCount(1);
  await expect(r71.locator('.task-kids-badge-n')).toHaveText('3');
  await expect(r71.locator('.task-flow-badge-n')).toHaveText('3');

  await r71.click();
  const detail = panel.locator('.task-delegate-detail[data-delegate-detail="71"]');
  // 同一展开面共存：3 子代理行 ∧ 1 flow 子行；层序 = 子代理行族在前、flow 行收尾。
  await expect(detail.locator('.bgagent-child-row')).toHaveCount(3);
  await expect(detail.locator('.task-flow-row')).toHaveCount(1);
  const order = await detail.evaluate((box) => [...box.querySelectorAll('.bgagent-child-row, .task-flow-row')]
    .map((el) => (el.classList.contains('task-flow-row') ? 'flow' : 'kid')).join(','));
  expect(order).toBe('kid,kid,kid,flow');

  // 混合态第三层：flow 行 chevron 展开 → 3 节点行（名 + 状态点），id/状态保真。
  const flowRow = detail.locator('.task-flow-row');
  await flowRow.locator('> .task-tree-chev').click();
  await expect(flowRow).toHaveAttribute('aria-expanded', 'true');
  const nodes = detail.locator('.task-flow-nodes .task-flow-node-row');
  await expect(nodes).toHaveCount(3);
  await expect(nodes.nth(0)).toHaveAttribute('data-flow-node-id', 'm1');
  await expect(nodes.nth(0).locator('.task-flow-node-name')).toHaveText('盘点现状');
  await expect(nodes.nth(0).locator('.delegate-chain-dot')).toHaveClass(/st-completed/);
  await expect(nodes.nth(1)).toHaveAttribute('data-flow-node-id', 'm2');
  await expect(nodes.nth(1).locator('.task-flow-node-name')).toHaveText('并行落渲染面');
  await expect(nodes.nth(1).locator('.delegate-chain-dot')).toHaveClass(/st-running/);
  await expect(nodes.nth(2)).toHaveAttribute('data-flow-node-id', 'm3');
  await expect(nodes.nth(2).locator('.delegate-chain-dot')).toHaveClass(/st-pending/);

  // 混合态子行交互语义不回归（R4）：子行点击 = 跳该 delegate 的 L1（键 71）。
  await detail.locator('.bgagent-child-row[data-child-session="subtask-a"]').click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1).toHaveAttribute('data-delegate-key', '71');
  await l1.locator('[data-back-to-tasks]').click();
  await expect(l1).toHaveCount(0);
  // 折叠记忆跨重渲：root 收起再展开，子行族 + 第三层节点行族俱在。
  await r71.click();
  await expect(panel.locator('.task-delegate-detail')).toHaveCount(0);
  await r71.click();
  await expect(detail.locator('.bgagent-child-row')).toHaveCount(3);
  await expect(detail.locator('.task-flow-node-row')).toHaveCount(3);

  expect(pageErrors).toEqual([]);
});

test('T7 执行器图标 = 本地原生资产：图标资产源引用自托管件（零外链）＋退役手绘品牌路径零残留＋DOM 真实解析（树行面；面板徽标同件跟随）', async ({ page }) => {
  // 静态断言（资产源头 executorIcons.js）：官方件 = 本地资产引用；手绘近似退役。
  const src = readFileSync(join(WEB, 'js', 'executorIcons.js'), 'utf8');
  expect(src).toContain('vendor/logos/claude-code.svg'); // simple-icons `claudecode`
  expect(src).toContain('vendor/logos/codex.svg');       // simple-icons v13 `openai`
  expect(src).toContain('css/logo-dark-4.png');          // nebflow 像素 logo 对（header 同源）
  expect(src).toContain('css/logo-bright-4.png');
  expect(src).not.toContain('exg-nebflow');     // 退役：手绘轨道渐变（nebflow）
  expect(src).not.toContain('d="M12 3.5v5"');   // 退役：手绘星芒（claude-code）
  expect(src).not.toContain('M12 3.4l7.2');     // 退役：手绘结环（codex）
  // 资产文件：官方上游形制（svg+title）、自包含零外链（xmlns 命名空间标识符
  // 非运行时引用，豁免——SVG 规范强制）。
  for (const f of ['claude-code.svg', 'codex.svg']) {
    const svg = readFileSync(join(WEB, 'vendor', 'logos', f), 'utf8');
    expect(svg.startsWith('<svg')).toBe(true);
    expect(svg).toMatch(/<title>[^<]+<\/title>/);
    expect(svg.replace(/xmlns="[^"]*"/g, '')).not.toMatch(/https?:\/\//);
  }

  // DOM 断言：树行消费 executorIcon（delegate/node 面板徽标同件自动跟随）。
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: [rowStar, rowExternal, rowClaude, rowCodex] });
  await settlePanel(page);

  const panel = page.locator('#task-list');
  // nebflow = 像素 logo 对（<picture> 主题配对，header 同款 pattern）。
  const nf = panel.locator('.task-delegate[data-task-no="71"] .executor-icon-nebflow');
  await expect(nf.locator('picture > source[media*="prefers-color-scheme"]')).toHaveCount(1);
  await expect(nf.locator('img')).toHaveAttribute('src', /css\/logo-(bright|dark)-4\.png$/);
  // claude-code / codex = 官方 SVG（本地自托管；请求真实落盘解析——img complete）。
  const cc = panel.locator('.task-delegate[data-task-no="76"] .executor-icon-claude-code img');
  await expect(cc).toHaveAttribute('src', /vendor\/logos\/claude-code\.svg$/);
  await expect.poll(() => cc.evaluate((el) => el.complete && el.naturalWidth > 0)).toBe(true);
  const cx = panel.locator('.task-delegate[data-task-no="77"] .executor-icon-codex img');
  await expect(cx).toHaveAttribute('src', /vendor\/logos\/codex\.svg$/);
  await expect.poll(() => cx.evaluate((el) => el.complete && el.naturalWidth > 0)).toBe(true);
  // zcode = 无公开官方件如实保留（内联件，申报项）；全部图标引用零 http(s) 外链。
  await expect(panel.locator('.task-delegate[data-task-no="73"] .executor-icon-zcode svg')).toHaveCount(1);
  const srcs = await panel.locator('.executor-icon img').evaluateAll((imgs) => imgs.map((i) => i.getAttribute('src')));
  expect(srcs.length).toBeGreaterThanOrEqual(3);
  for (const s of srcs) expect(String(s).startsWith('http')).toBe(false);
  expect(pageErrors).toEqual([]);
});
