// delegate-ui-twolayer.spec.mjs — delegate-ui 批（2026-10-08 · 定稿蓝本 §1.1/§1.2/§1.4）
// 前端位验收：面板两级化（C2/R6）＋ C1 过渡过滤 ＋ G1 模式徽标 ＋ G3 星型投影 ∪ live
// 行 ＋ agentflow flow 结构子行（面板 + S1 expanded）＋ 视图④详情条（G3 星型区/G4
// 插件/G6 归档入口）。
//
// 数据面 = stub 读取口（rows/children/chain 夹具优先——harness 兼容缝）＋真实 ws.js
// 分发路径注入；REST 面（G4/G3/G6）走路由 mock。零后端、零 sbt。
//
// Run: node node_modules/@playwright/test/cli.js test tests/delegate-ui-twolayer.spec.mjs

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

const ROOT_SID = 'dui-root-session';
const T0 = Date.now();

/** 可选历史夹具（sessionId → 行数组；Node 侧由用例直接写入）：弹窗九点⑦ brief
 *  回填路径（fillStripTaskBody）的 getHistory 回执面；缺省 = 空页（原行为）。 */
const historyFixture = new Map();

async function bootApp(page) {  await page.addInitScript(() => {
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
        const msgs = historyFixture.get(parsed.sessionId) || [];
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: msgs, total: msgs.length, offset: 0, hasMore: false }));
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

/** 走真实 ws.js 分发路径注入一帧。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** agentStart 真实形状（routeSubagentWsSend 注入三键同构）。 */
function agentStart(sessionId, over = {}) {
  return {
    type: 'agentStart',
    sessionId: ROOT_SID, rootSessionId: ROOT_SID,
    agentId: sessionId, nodeSessionId: sessionId,
    name: sessionId, taskDescription: '',
    ...over,
  };
}

/** 装 stub 夹具（rows/chains/children 三读口）。 */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
  }, fx);
}

/** 打开 Sub-Agents 面板（indicator 点击 → renderBgAgentDropdown）。 */
async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bgagent-dropdown:not(.hidden)', { timeout: 8000 });
}

// ── 夹具行（stub schema 加宽形）──────────────────────────────────────────
const rowStar = {
  taskNo: '71', delegateId: 'delegate-nebflow-w1', title: '星型编排任务',
  executor: 'nebflow', status: 'running', external: false, project: 'Nebflow',
  orchestrationMode: 'subagent-star', archivedAt: null,
  mergeQueue: null, mergeQueuePos: null, createdAt: T0 - 60_000,
};
const rowFlow = {
  taskNo: '72', delegateId: 'delegate-nebflow-w2', title: 'flow 编排任务',
  executor: 'nebflow', status: 'running', external: false, project: 'Nebflow',
  orchestrationMode: 'agentflow', archivedAt: null,
  mergeQueue: null, mergeQueuePos: null, createdAt: T0 - 30_000,
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

test('T1 stub schema 加宽（G1 mode/G5 队列键/G6 archivedAt）＋旧行缺键容忍＋子代理读取口', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  const out = await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    const full = stub.rowFromRegistry({
      id: 'delegate-nebflow-f1', taskId: '81', title: 'T', executor: 'nebflow',
      orchestrationMode: 'agentflow', archivedAt: 1728360000000,
      mergeQueue: { projectKey: 'nebflow', ahead: ['d0'], holder: 'd0', reminderSent: false },
      mergeQueuePos: { position: 2, total: 3, readyAt: 1728360000001, branch: 'delegate/x', baseBranch: 'main' },
    });
    const legacy = stub.rowFromRegistry({ id: 'delegate-nebflow-f2', taskId: '82' });
    stub.__setFixture({
      rows: [full.taskNo === '81' ? { taskNo: '81', delegateId: 'delegate-nebflow-f1', external: false } : {}],
      children: {
        'delegate-nebflow-f1': [{ taskId: 'subtask-z1', status: 'running', source: 'subtask' }],
      },
    });
    return {
      full, legacy,
      modes: { ...stub.ORCHESTRATION_MODES },
      kids: stub.listDelegateChildren('delegate-nebflow-f1'),
      kidsEmpty: stub.listDelegateChildren('delegate-nebflow-nobody'),
      reset: (stub.__resetFixture(), true),
    };
  });

  // G1/G5/G6 加宽键逐键
  expect(out.full.orchestrationMode).toBe('agentflow');
  expect(out.full.archivedAt).toBe(1728360000000);
  expect(out.full.mergeQueue).toEqual({ projectKey: 'nebflow', ahead: ['d0'], holder: 'd0', reminderSent: false });
  expect(out.full.mergeQueuePos).toEqual({ position: 2, total: 3, readyAt: 1728360000001, branch: 'delegate/x', baseBranch: 'main' });
  // 旧盘缺键容忍：mode ''（禁折叠 single）、archivedAt null、队列两键 null
  expect(out.legacy.orchestrationMode).toBe('');
  expect(out.legacy.archivedAt).toBe(null);
  expect(out.legacy.mergeQueue).toBe(null);
  expect(out.legacy.mergeQueuePos).toBe(null);
  // 值域常量钉（引擎 Mode* 同族）
  expect(out.modes).toEqual({ SINGLE: 'single', SUBAGENT_STAR: 'subagent-star', AGENTFLOW: 'agentflow' });
  // G3 读取口：夹具命中 / 空集非崩
  expect(out.kids).toHaveLength(1);
  expect(out.kids[0].taskId).toBe('subtask-z1');
  expect(out.kidsEmpty).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test('T2 面板两级化（C2/R6）：delegate 组头徽标=模式 Subagent；G3 store 子行 ∪ live 子行缩进一格；C1 dispatcher/node 零渲染', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, {
    rows: [rowStar],
    children: {
      'delegate-nebflow-w1': [
        { taskId: 'subtask-live1', agentName: 'scout', description: '侦察任务', status: 'running', source: 'subtask', spawnedAt: T0 },
        { taskId: 'subtask-done2', agentName: 'audit', description: '审计任务', status: 'completed', source: 'subtask', spawnedAt: T0 },
      ],
    },
  });
  await inject(page, agentStart('delegate-nebflow-w1', { name: 'Explorer', taskDescription: '星型编排任务' }));
  await inject(page, agentStart('dispatcher-zz9', { name: 'dispatcher', taskDescription: '内部编排件' }));
  await inject(page, agentStart('node-aa8', { name: 'node', taskDescription: '内部节点' }));
  await inject(page, {
    ...agentStart('subtask-live1', { agentId: 'mail-sub1', name: 'scout', taskDescription: '侦察任务' }),
  });
  await openPanel(page);

  const list = page.locator('#bgagent-dropdown-list, #bgagent-dropdown .bg-dropdown-list').first();
  const rows = page.locator('#bgagent-dropdown .bg-task-row');
  // 两级共 3 行：组头 + live 子 + store 子；dispatcher/node 被 C1 过滤零渲染。
  await expect(rows).toHaveCount(3);
  await expect(page.locator('#bgagent-dropdown .bg-task-row[data-node-session-id="dispatcher-zz9"]')).toHaveCount(0);
  await expect(page.locator('#bgagent-dropdown .bg-task-row[data-node-session-id="node-aa8"]')).toHaveCount(0);

  // 一级 = delegate 组头：bg-task-row-head + 徽标跟模式（subagent-star → Subagent）。
  const head = page.locator('#bgagent-dropdown .bg-task-row.bg-task-row-head');
  await expect(head).toHaveCount(1);
  await expect(head).toHaveAttribute('data-node-session-id', 'delegate-nebflow-w1');
  await expect(head.locator('.bg-task-kind')).toHaveText('Subagent');

  // 二级 = 缩进一格子行（live 行原富渲染 + store 行轻渲染）。
  const liveChild = page.locator('#bgagent-dropdown .bg-task-row.bg-task-row-child[data-node-session-id="subtask-live1"]');
  await expect(liveChild).toHaveCount(1);
  await expect(liveChild.locator('.bg-task-name')).toContainText('scout');
  const storeChild = page.locator('#bgagent-dropdown .bg-task-row.bg-task-row-child[data-child-session="subtask-done2"]');
  await expect(storeChild).toHaveCount(1);
  // 修订批九点②（2026-10-09）：「SubTask」UI 标注更名 delegate 语义（显示词 Delegate）。
  await expect(storeChild.locator('.bg-task-kind')).toHaveText('Delegate');
  await expect(storeChild.locator('.bg-task-state')).toHaveText('已完成');

  // 徽标计数 = 可见 entries（delegate + live 子；store 子不是 WS 条目不计）。
  await expect(page.locator('#bgagent-indicator .bgagent-count')).toHaveText('2');
  expect(pageErrors).toEqual([]);
});

test('T3 agentflow（R2/R6）：组头徽标=Flow；chain.nodes flow-step 面板子行 + S1 expanded flow 结构层', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, { rows: [rowFlow], chains: [chainFlow] });
  await inject(page, agentStart('delegate-nebflow-w2', { name: 'Planner', taskDescription: 'flow 编排任务' }));

  // ── 面板（视图②）：徽标=Flow，flow 子行缩进一格 ──
  await openPanel(page);
  const head = page.locator('#bgagent-dropdown .bg-task-row.bg-task-row-head');
  await expect(head).toHaveCount(1);
  await expect(head.locator('.bg-task-kind')).toHaveText('Flow');
  const flowRows = page.locator('#bgagent-dropdown .bg-task-row.bg-task-row-child[data-flow-delegate-key="72"]');
  await expect(flowRows).toHaveCount(2);
  await expect(flowRows.nth(0).locator('.bg-task-name')).toHaveText('步骤一');
  await expect(flowRows.nth(0).locator('.bg-task-kind')).toHaveText('AgentFlow');
  await expect(flowRows.nth(1).locator('.bg-task-state')).toHaveText('待处理');
  await expect(flowRows.nth(1).locator('.bg-task-project')).toHaveText('← s1');
  // 适配层单 delegate 回退节点（kind=delegate）不入子行层。
  await expect(page.locator('#bgagent-dropdown .bg-task-row')).toHaveCount(3);

  // ── 任务列表（视图①）：flow-step 链节点 = 平铺成员行（flowmap 行脸）──
  // 先收起面板下拉（外点关闭 = 既有 UI 路径）——否则浮层截走任务行的点击。
  await page.mouse.click(10, 300);
  await expect(page.locator('#bgagent-dropdown')).toHaveClass(/hidden/);
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  const row72 = page.locator('#task-list .task-delegate[data-task-no="72"]');
  await expect(row72).toHaveCount(1);
  // provider 防抖刷（agentStart 注入触发）会整段重建面板 DOM——等尘埃落定再点。
  await page.waitForTimeout(1500);
  await expect(row72).toHaveCount(1);
  // 入场动画（task-node-entering）期间行不稳定——等动画类摘除再点。
  await page.waitForFunction(() => !document.querySelector('#task-list .task-node-entering'), null, { timeout: 5000 }).catch(() => {});
  // 平铺行脸：两条 flow-step 成员行直渲（无行内徽标、无展开树）；delegate
  // 适配回退节点（kind=delegate）不入成员行（链根 = 根行本体）。
  await expect(row72.locator('.task-flow-badge')).toHaveCount(0);
  await expect(row72.locator('.task-kids-badge')).toHaveCount(0);
  const panelSteps = page.locator('#task-list .task-member[data-node-key^="72‖flow‖"]');
  await expect(panelSteps).toHaveCount(2);
  await expect(panelSteps.nth(0).locator('.task-node-label')).toHaveText('步骤一');
  await expect(panelSteps.nth(0).locator('.task-node-orbit')).toHaveCount(1); // running
  await expect(panelSteps.nth(1).locator('.task-node-label')).toHaveText('步骤二');
  await expect(panelSteps.nth(1).locator('.task-node-box')).toHaveCount(1); // pending
  // 成员行链徽标 = 所属链短名（delegate 标题）。
  await expect(panelSteps.nth(0).locator('.task-node-chain-name')).toHaveText('flow 编排任务');
  // 行点击 = L1 委托流程图下钻锚点（expandFlow——打开即展开 flow-step 层）。
  await panelSteps.nth(0).click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1.locator('.flowmap-summary')).toHaveText('#72 · 委托流程图');
  // L1 形态（§C 一期卡列表）：根卡（链 delegate 节点投影）+ AgentFlow 链卡 +
  // 展开的 flow-step 下钻层（2 卡）；树批 R7（2026-10-09）：L1 顶栏结构入口钮退役
  // （入口迁项目面板「项目流程图」圆钮 + 项目卡标题点按）。
  await expect(l1.locator('.delegate-structure-entry')).toHaveCount(0);
  await expect(l1.locator('.delegate-chain-taskview .fm-node[data-node-id="delegate-nebflow-w2"]')).toHaveCount(1);
  const chainToggle = l1.locator('.delegate-flow-chain-toggle');
  await expect(chainToggle).toHaveAttribute('aria-expanded', 'true');
  await expect(l1.locator('.delegate-flow-steps')).toBeVisible();
  await expect(l1.locator('.delegate-flow-steps .fm-node[data-node-id="s1"]')).toHaveCount(1);
  await expect(l1.locator('.delegate-flow-steps .fm-node[data-node-id="s2"]')).toHaveCount(1);
  expect(pageErrors).toEqual([]);
});

test('T4 星型子代理平铺行（flowmap 行脸）：逐子代理成员行直渲（状态 glyph/名/状态词/链徽标）＋ 子行点击跳 L1 委托流程图', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);
  await setFixture(page, {
    rows: [rowStar],
    children: {
      'delegate-nebflow-w1': [
        { taskId: 'subtask-a', agentName: 'scout', status: 'running', source: 'subtask', spawnedAt: T0 },
        { taskId: 'subtask-b', agentName: 'audit', status: 'completed', source: 'subtask', spawnedAt: T0 },
        { taskId: 'subagent-c', agentName: 'recon', status: 'running', source: 'subagent', spawnedAt: T0 },
      ],
    },
    chains: [{
      taskId: '71', delegateId: 'delegate-nebflow-w1', nodes: [],
      worktree: { branch: 'delegate/task-71', worktree: '/wt/71', cwd: '/wt/71', baseWorkspace: '', baseBranch: 'main', baseSha: '', plugins: [], allocation: '' },
      verdict: null, mergeQueue: [], frames: [],
    }],
  });
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  await page.waitForFunction(() => !document.querySelector('#task-list .task-node-entering'), null, { timeout: 5000 }).catch(() => {});
  const row71 = page.locator('#task-list .task-delegate[data-task-no="71"]');
  // 平铺行脸：行内计数徽标与展开树均退役——子代理逐行直渲。
  await expect(row71.locator('.task-kids-badge')).toHaveCount(0);
  await expect(row71.locator('.task-flow-badge')).toHaveCount(0);
  await expect(page.locator('#task-list .task-delegate-detail')).toHaveCount(0);
  // 三条子代理成员行：名 + 状态 glyph + 状态词 + 所属链徽标（delegate 标题）。
  const kidRows = page.locator('#task-list .task-member');
  await expect(kidRows).toHaveCount(3);
  const kidA = page.locator('#task-list .task-member[data-node-key="71‖subtask-a"]');
  await expect(kidA.locator('.task-node-label')).toHaveText('scout');
  await expect(kidA.locator('.task-node-orbit')).toHaveCount(1); // running
  await expect(kidA.locator('.task-node-word')).toHaveText('进行中');
  await expect(kidA.locator('.task-node-chain-name')).toHaveText('星型编排任务');
  const kidB = page.locator('#task-list .task-member[data-node-key="71‖subtask-b"]');
  await expect(kidB.locator('.task-node-dot-completed')).toHaveCount(1);
  await expect(kidB.locator('.task-node-word')).toHaveText('已完成');
  const kidC = page.locator('#task-list .task-member[data-node-key="71‖subagent-c"]');
  await expect(kidC.locator('.task-node-label')).toHaveText('recon');
  await expect(kidC.locator('.task-node-orbit')).toHaveCount(1);
  // 子行点击 = 跳该 delegate 的 L1 委托流程图（树层管导航、L1 管详情——对话窗
  // 入口在 L1 根卡/子卡点击，任务列表不开弹窗）。
  await kidA.click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1).toHaveAttribute('data-delegate-key', '71');
  await expect(page.locator('.flow-agent-overlay')).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('T5 弹窗净化（§A/WF-01）：收起式摘要条（任务 chip+brief+结果词+chevron）＋ 展开态（plugins/brief/结果分区）＋ 移除件全数退役（子代理×N 标题/星型子行族/头部归档图标）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 具体 API 路由后注册 = 优先命中（Playwright 后注册者胜）。G4 聚合单查 =
  // 弹窗唯一数据缝（并行面已随星型区移除）。
  await page.route('**/api/delegates/delegate-nebflow-d1', (route) => {
    if (route.request().method() !== 'GET') return route.fulfill({ status: 405, body: '' });
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        record: { id: 'delegate-nebflow-d1', executor: 'nebflow', plugins: ['visual-report', 'slideblocks'], archivedAt: null, title: '两层架构批任务' },
        live: { status: 'running' }, verdict: null, task: { title: '两层架构批任务' }, childrenCount: 2,
      }),
    });
  });

  // 历史夹具：首刷回执带首条 delegate 注入行 = 派发 brief 全文（九点⑦回填路径）。
  historyFixture.set('delegate-nebflow-d1', [{
    type: 'user', header: 'DELEGATE · NEBFLOW', source: 'delegate', injected: true,
    timestamp: Date.now(), text: '实现批：delegate/flowmap 两层架构', attachments: [],
  }]);

  await page.evaluate(async () => {
    const { openStepPopup } = await import('/js/bgAgentPopup.js');
    openStepPopup('delegate-nebflow-d1', 'Explorer', '星型详情任务');
  });
  const strip = page.locator('.bgagent-delegate-strip[data-delegate-strip="delegate-nebflow-d1"]');
  await expect(strip).toBeVisible();
  // ── §A 移除清单全数退役：子代理×N 标题行 / 星型子行族 / 条内归档钮 / 头部归档
  // 小图标（G6 归档面随 §A 移除——引擎自动归档承接，弹窗零归档件）。
  await expect(strip.locator('.bgagent-strip-title')).toHaveCount(0);
  await expect(strip.locator('.bgagent-child-row')).toHaveCount(0);
  await expect(strip.locator('.bgagent-archive-btn')).toHaveCount(0);
  await expect(page.locator('.flow-agent-header [data-testid="delegate-archive"]')).toHaveCount(0);
  await expect(page.locator('.flow-agent-header .flow-agent-archive')).toHaveCount(0);
  // ── WF-01 收起态：toggle aria-expanded=false + body hidden；任务 chip +
  // brief 一行（历史回执回填 = 派发 brief 全文）＋ 在飞无判词 → 结果词缺席。
  const toggle = strip.locator('.bgagent-strip-toggle');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(toggle.locator('.bgagent-strip-chip')).toHaveText('任务');
  await expect(toggle.locator('.bgagent-strip-briefline')).toHaveText('实现批：delegate/flowmap 两层架构');
  await expect(strip.locator('.bgagent-strip-body')).toBeHidden();
  await expect(strip.locator('.bgagent-strip-result')).toBeHidden();
  // ── 展开态（点 toggle）：plugins 数据原文 + 任务/结果分区骨架；无判词无报告
  // → 结果区缺席（在飞常态）。
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  const body = strip.locator('.bgagent-strip-body');
  await expect(body).toBeVisible();
  await expect(body.locator('.bgagent-strip-plugins')).toHaveText('plugins: visual-report, slideblocks');
  await expect(body.locator('.bgagent-strip-tasktext')).toHaveText('实现批：delegate/flowmap 两层架构');
  await expect(body.locator('.bgagent-strip-sec-label').nth(0)).toHaveText('任务');
  await expect(body.locator('.bgagent-strip-sec-label').nth(1)).toHaveText('结果');
  await expect(body.locator('.bgagent-strip-sec').nth(1)).toBeHidden();
  // 再点 = 收回（单条内切换，对话流不受影响）。
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(body).toBeHidden();
  expect(pageErrors).toEqual([]);
});
