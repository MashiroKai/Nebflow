// tasklist-nodes.spec.mjs — 任务列表面板 Flow Map 节点条目验收（2026-09-02 作者裁定：
// 节点作为条目显示在任务列表中，纯前端；v2 迭代同步：绿色小圈 spinner + 简约化 +
// team 区块解耦（.task-node-* 独立命名空间））。
//
// 2026-09-05 作者裁定更新（taskpanel-audit 批）：状态词映射改为
// wiring/pending→待处理、running→进行中、held→待放行、blocked→阻塞、
// completed→已完成、failed→失败、cancelled→已取消（held 为 20260903 hold
// 闸门新状态，升为正式徽章——琥珀虚线方框；blocked——琥珀实心点）。
//
// 2026-09-05 10:54 作者裁定（同支叠加批）：任务面板 = 纯 Flow Map 节点视图，
// 旧任务区整体退役（推翻 67e69bf1「旧路径保留」取舍）。逐处：
//   T1 统计 = 仅节点数；progress 区块与旧任务行零存在断言
//   T7 重写：mock 注入旧 taskListUpdate/teamTaskListUpdate 事件 → 零渲染变化
//     （无旧任务行/progress 区/空态，节点视图与统计不受影响）
//   renderPanel 改道：旧 taskListUpdate 帧驱动首渲的路径已删，改为直接调
//     renderTaskList（与 sidebar.js 会话切换同一入口）
//
// 2026-09-05 12:59 作者裁定（同支叠加批）：任务列表主图同源过滤——整链已归档
// （批内全终态）节点不显示，判据与主图同一 clusterBatches 单点（flowMapArchive
// .deriveArchivedIds）。逐处：
//   T2/T3/T5 fixture 适配：原单节点完成后即「单节点全终态链」→ 新判据下整链
//     隐藏，原地徽章/移除/防回滚语义无从断言——各补同批 running 伴节点保持
//     链未齐（测试意图不变，断言不动）
//   T8 新增（核心混合态）：①活跃节点 + ②未齐终态链（同批 completed+running）
//     + ③全终态链（>120s 分链边界独立成批）→ 断言渲染集合=①+② 全部成员、
//     ③零渲染、统计=①+② 计数
//   T9 新增：全终态单链=列表空（面板收起兜底）；活跃成员并入后整链恢复
//     （含已完成成员）——「主图空=列表空」语义一致
//
// 其余覆盖：
//   T2 状态更新：nodeUpdated/nodeCompleted 原地反映（绿圈 → 终态色点）
//   T3 移除：nodeRemoved 条目消失
//   T4 交互：点击条目 → projects 标签页就地打开该项目 Flow Map 并高亮该节点
//   T5 快照对齐 + 防回滚：快照在途期间的 WS 增量不被旧快照滚回
//   T6 未知状态优雅降级
//
// 事件帧 = 后端契约帧 {type, project, nodeId, node}（20260901_project-node-contract
// §2），走真实 ws.js 分发路径注入（s.ws.onmessage），前端管线全真。
//
// Run: node node_modules/@playwright/test/cli.js test tests/tasklist-nodes.spec.mjs

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

const ROOT_SID = 'tl-root-session';
const T0 = Date.now();

const nodeJson = (over) => ({
  hasWorktree: false, worktree: null, result: null, retries: 0,
  createdAt: T0, completedAt: null, startedAt: null, ttlLeftSec: null, ...over,
});
const N_RUN = nodeJson({ id: 'n-run', name: 'scan-repo', agent: 'Backend', status: 'running', startedAt: T0 });
const N_REV = nodeJson({ id: 'n-rev', name: 'code-review', agent: 'qa-frontend', status: 'completed', completedAt: T0 - 60_000, ttlLeftSec: 240 });
const N_DOC = nodeJson({ id: 'n-doc', name: 'write-docs', agent: 'Docs', status: 'wiring' });
const N_PEND = nodeJson({ id: 'n-pend', name: 'gen-report', agent: 'czt-writer', status: 'pending' });
const N_FIX = nodeJson({ id: 'n-fix', name: 'fix-imports', agent: 'Backend', status: 'failed', completedAt: T0 - 120_000, ttlLeftSec: 180 });
// 2026-09-05 裁定：held（hold 闸门挂起）与 blocked 升为正式徽章
const N_HELD = nodeJson({ id: 'n-held', name: 'hold-gate', agent: 'Frontend', status: 'held', completedAt: T0 - 30_000 });
const N_BLOCKED = nodeJson({ id: 'n-blocked', name: 'await-dispatch', agent: 'Backend', status: 'blocked' });

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
      const body = readFileSync(file);
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body });
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
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
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

/** 走真实 ws.js 分发路径注入一帧（与后端广播等价）。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 让面板渲染管线跑起来：直接调 renderTaskList（与 sidebar.js 会话切换同一
 *  入口；旧 taskListUpdate 帧驱动路径已随旧任务区退役删除）。空 content 面板
 *  是 hidden 的（.has-tasks 才展开）——后续断言自行等待行出现。 */
async function renderPanel(page) {
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  await page.waitForTimeout(100);
}

test('T1 节点条目渲染：七状态徽章/状态词/时间/project·agent 标注/项目分组/统计计节点', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);

  // 两个项目的节点事件（真实 ws 分发路径）
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-rev', node: { ...N_REV } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-fix', node: { ...N_FIX } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-held', node: { ...N_HELD } });
  await inject(page, { type: 'nodeCreated', project: 'beta', nodeId: 'n-doc', node: { ...N_DOC } });
  await inject(page, { type: 'nodeCreated', project: 'beta', nodeId: 'n-pend', node: { ...N_PEND } });
  await inject(page, { type: 'nodeCreated', project: 'beta', nodeId: 'n-blocked', node: { ...N_BLOCKED } });

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);

  // 分区与项目分组头（节点区块自有类，不依赖 team 分组类）
  // 2026-09-06 显示优化批：「Flow Map」分区标题整体移除（作者 00:35 裁定）——
  // 断言其持续缺席作回归哨兵；flowmap.title 键保留（标签页域共用，不在此面板）。
  await expect(panel.locator('.task-section-nodes .task-node-section-title')).toHaveCount(0);
  await expect(panel.locator('.task-node-group-header', { hasText: 'alpha' })).toHaveCount(1);
  await expect(panel.locator('.task-node-group-header', { hasText: 'beta' })).toHaveCount(1);

  // 头部统计 = 仅节点数（2026-09-05 10:54 裁定：纯节点视图，旧任务计数退役）
  await expect(panel.locator('.task-stats')).toHaveText('7 任务');
  // 旧任务区零存在：无 progress 区块、无旧任务行、无空态
  await expect(panel.locator('.task-section-progress')).toHaveCount(0);
  await expect(panel.locator('.task-item')).toHaveCount(0);
  await expect(panel.locator('.task-empty')).toHaveCount(0);

  // running：品牌绿小圈（--color-primary #07C160，非任务行蓝色 sapphire）+ 12px
  const runRow = panel.locator('.task-node[data-node-key="alpha:n-run"]');
  await expect(runRow.locator('.task-node-label')).toHaveText('scan-repo');
  await expect(runRow.locator('.task-node-spin')).toHaveCount(1);
  const spinColor = await runRow.locator('.task-node-spin')
    .evaluate((el) => getComputedStyle(el).borderTopColor);
  expect(spinColor).toBe('rgb(7, 193, 96)'); // #07c160 品牌绿
  const spinSize = await runRow.locator('.task-node-spin')
    .evaluate((el) => ({ w: el.offsetWidth, h: el.offsetHeight }));
  expect(spinSize.w).toBe(12); // 缩小到 12px 节奏（= pending 小方框，宁小勿大）
  expect(spinSize.h).toBe(12); // offsetWidth/Height 不含 transform——转圈中的 AABB 会随角度变大
  // 2026-09-05 裁定：running → 进行中（复用 task.inProgressShort）
  await expect(runRow.locator('.task-node-word')).toHaveText('进行中');
  const wordColor = await runRow.locator('.task-node-word')
    .evaluate((el) => getComputedStyle(el).color);
  expect(wordColor).not.toBe('rgb(7, 193, 96)'); // 状态词 muted（颜色信号归 glyph）
  await expect(runRow.locator('.task-node-meta')).toHaveText('Backend'); // meta 只标 agent
  await expect(runRow.locator('.task-node-time')).not.toBeEmpty();
  // 行密度对齐任务行：13px 字号 + 3px 上下 padding；不携带任务域类（命名空间解耦）
  expect(await runRow.evaluate((el) => getComputedStyle(el).fontSize)).toBe('13px');
  expect(await runRow.evaluate((el) => getComputedStyle(el).paddingTop)).toBe('3px');
  await expect(runRow).not.toHaveClass(/task-item/);
  await expect(runRow).not.toHaveClass(/task-check/);

  // completed：绿色实心点 + 已完成
  const revRow = panel.locator('.task-node[data-node-key="alpha:n-rev"]');
  await expect(revRow.locator('.task-node-dot-completed')).toHaveCount(1);
  await expect(revRow.locator('.task-node-word')).toHaveText('已完成');

  // failed：红点
  const fixRow = panel.locator('.task-node[data-node-key="alpha:n-fix"]');
  await expect(fixRow.locator('.task-node-dot-failed')).toHaveCount(1);
  await expect(fixRow.locator('.task-node-word')).toHaveText('失败');

  // held（2026-09-05 裁定）：琥珀虚线方框 + 待放行
  const heldRow = panel.locator('.task-node[data-node-key="alpha:n-held"]');
  await expect(heldRow.locator('.task-node-box-held')).toHaveCount(1);
  await expect(heldRow.locator('.task-node-word')).toHaveText('待放行');
  const heldColor = await heldRow.locator('.task-node-box-held')
    .evaluate((el) => getComputedStyle(el).borderTopColor);
  expect(heldColor).toBe('rgba(255, 152, 0, 0.9)'); // --amber 亮色主题（rgb(255 152 0 / 0.9) 序列化）

  // wiring：待处理（2026-09-05 裁定，原「等待」）
  const docRow = panel.locator('.task-node[data-node-key="beta:n-doc"]');
  await expect(docRow.locator('.task-node-box')).toHaveCount(1);
  await expect(docRow.locator('.task-node-word')).toHaveText('待处理');

  // pending：待处理（2026-09-05 裁定，原「排队中」）
  const pendRow = panel.locator('.task-node[data-node-key="beta:n-pend"]');
  await expect(pendRow.locator('.task-node-box')).toHaveCount(1);
  await expect(pendRow.locator('.task-node-word')).toHaveText('待处理');

  // blocked（2026-09-05 裁定）：琥珀实心点 + 阻塞
  const blockedRow = panel.locator('.task-node[data-node-key="beta:n-blocked"]');
  await expect(blockedRow.locator('.task-node-dot-blocked')).toHaveCount(1);
  await expect(blockedRow.locator('.task-node-word')).toHaveText('阻塞');

  expect(pageErrors).toEqual([]);
});

test('T2 状态更新原地反映：running → completed 绿圈与色点切换', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  // 主图同源过滤（2026-09-05 12:59 裁定）fixture 适配：n-run 完成后若为单节点
  // 链即「全终态归档」会隐藏——补同批 running 伴节点保持链未齐，原地徽章
  // 切换语义照常断言（createdAt 同为 T0 → 同批聚簇）。
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-peer', node: { ...N_RUN, id: 'n-peer', name: 'peer-task' } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });

  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-run"]');
  await expect(row.locator('.task-node-spin')).toHaveCount(1);

  // 节点完成（nodeCompleted 全量 payload）
  await inject(page, {
    type: 'nodeCompleted', project: 'alpha', nodeId: 'n-run',
    node: { ...N_RUN, status: 'completed', result: 'repo scanned', completedAt: Date.now(), ttlLeftSec: 300 },
  });
  await expect(row.locator('.task-node-dot-completed')).toHaveCount(1);
  await expect(row.locator('.task-node-spin')).toHaveCount(0);
  await expect(row.locator('.task-node-word')).toHaveText('已完成');

  expect(pageErrors).toEqual([]);
});

test('T3 nodeRemoved 移除条目', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  // 主图同源过滤 fixture 适配（同 T2）：completed 单节点链会整链隐藏，补同批
  // running 伴节点让 n-rev 可见，移除语义照常断言。
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-peer', node: { ...N_RUN, id: 'n-peer', name: 'peer-task' } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-rev', node: { ...N_REV } });
  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-rev"]');
  await expect(row).toHaveCount(1);

  await inject(page, { type: 'nodeRemoved', project: 'alpha', nodeId: 'n-rev', node: {} });
  await expect(row).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T4 点击条目 → 就地打开该项目 Flow Map 并高亮节点', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', (r) => r.fulfill({
    json: { nodes: [{ ...N_RUN }, { ...N_REV }], worktrees: [], meta: { project: 'alpha', updatedAt: Date.now() } },
  }));
  await renderPanel(page);
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });

  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-run"]');
  await expect(row).toHaveCount(1);
  await row.click();

  // 同标签页就地视图 + 节点卡片渲染 + 定位高亮（fm-node-flash）
  const body = page.locator('.canvas-tab-pane.active .flowmap-view-body');
  await expect(body).toHaveCount(1);
  const nodeCard = body.locator('.fm-node[data-node-id="n-run"]');
  await expect(nodeCard).toHaveCount(1);
  await expect(nodeCard).toHaveClass(/fm-node-flash/, { timeout: 3000 });

  expect(pageErrors).toEqual([]);
});

test('T5 快照在途期间的 WS 增量不被旧快照回滚', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);

  // 慢快照桩（500ms）：内容是「n1 running」的旧权威快照 + 同批 running 伴节点
  // n2（主图同源过滤 fixture 适配：n1 完成后单节点链会整链隐藏，n2 保持链未齐）
  const STALE = { nodes: [{ ...N_RUN, id: 'n1', name: 'scan-repo' }, { ...N_RUN, id: 'n2', name: 'peer-run' }], worktrees: [], meta: { project: 'alpha', updatedAt: T0 } };
  await page.route('**/api/projects', (r) => r.fulfill({
    json: { projects: [{ name: 'alpha', workspace: '/w/alpha', agentFile: 'alpha', description: '', createdAt: new Date(T0).toISOString() }] },
  }));
  await page.route('**/api/projects/alpha/flow-map', async (r) => {
    await new Promise((res) => setTimeout(res, 500));
    return r.fulfill({ json: STALE });
  });

  // 触发快照对齐（重连 → onReconnect → refreshNodeSnapshot），快照在途……
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.forceReconnect();
  });
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, null, { timeout: 10000 });
  await page.waitForFunction(() => document.querySelector('#task-list .task-node[data-node-key="alpha:n1"]'), null, { timeout: 5000 });

  // ……期间 nodeCompleted 到达：缓存已更新为 completed
  await inject(page, {
    type: 'nodeCompleted', project: 'alpha', nodeId: 'n1',
    node: { ...N_RUN, id: 'n1', status: 'completed', completedAt: Date.now(), ttlLeftSec: 300 },
  });
  const row = page.locator('#task-list .task-node[data-node-key="alpha:n1"]');
  await expect(row.locator('.task-node-word')).toHaveText('已完成');

  // 慢快照（running）此时才落地：防回滚守卫生效，面板保持 completed
  await page.waitForTimeout(800);
  await expect(row.locator('.task-node-dot-completed')).toHaveCount(1);
  await expect(row.locator('.task-node-word')).toHaveText('已完成');

  expect(pageErrors).toEqual([]);
});

test('T6 未知节点状态优雅降级：中性点、无状态词、不崩', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  // 2026-09-05 裁定后 blocked/held 已是已知状态（正式徽章）——降级路径改用
  // 尚不存在的假想状态 'draft'：前端不认识它时必须中性降级而非崩/空白
  await inject(page, {
    type: 'nodeCreated', project: 'alpha', nodeId: 'n-draft',
    node: nodeJson({ id: 'n-draft', name: 'future-node', agent: 'Backend', status: 'draft' }),
  });

  const row = page.locator('#task-list .task-node[data-node-key="alpha:n-draft"]');
  await expect(row).toHaveCount(1);
  await expect(row.locator('.task-node-label')).toHaveText('future-node');
  await expect(row.locator('.task-node-dot-neutral')).toHaveCount(1); // 中性默认样式
  await expect(row.locator('.task-node-word')).toHaveCount(0); // 未知状态不出状态词
  await expect(row.locator('.task-node-spin')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

test('T7 旧任务事件零渲染：taskListUpdate/teamTaskListUpdate 注入后面板不变（2026-09-05 10:54 裁定纯节点视图）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-run', node: { ...N_RUN } });
  await inject(page, { type: 'nodeCreated', project: 'alpha', nodeId: 'n-rev', node: { ...N_REV } });

  const panel = page.locator('#task-list');
  // 基线：2 节点、统计=节点数
  await expect(panel.locator('.task-node')).toHaveCount(2);
  await expect(panel.locator('.task-stats')).toHaveText('2 任务');

  // 注入旧任务事件（真实 ws 分发路径）：1 条 session 域 + 1 条 team 域。
  // handler 已删 + ws 路由表项已出——帧必须零渲染影响。
  await inject(page, {
    type: 'taskListUpdate', sessionId: ROOT_SID,
    tasks: [{
      id: 'legacy-1', subject: 'legacy session task', description: '', status: 'in_progress',
      taskKind: 'agent', createdAt: new Date(T0).toISOString(), updatedAt: new Date(T0).toISOString(),
    }, {
      id: 'legacy-2', subject: 'legacy pending task', description: '', status: 'pending',
      taskKind: 'agent', createdAt: new Date(T0).toISOString(), updatedAt: new Date(T0).toISOString(),
    }],
  });
  await inject(page, {
    type: 'teamTaskListUpdate', team: 'some-team',
    tasks: [{
      id: 'legacy-team-1', subject: 'legacy team task', description: '', status: 'in_progress',
      taskKind: 'agent', teamId: 'some-team', assignee: 'Backend',
      createdAt: new Date(T0).toISOString(), updatedAt: new Date(T0).toISOString(),
    }],
  });
  await page.waitForTimeout(150);

  // 零渲染变化断言：旧任务行/progress 区块/空态均不存在；
  // 节点行与统计（仅节点口径）保持原样。
  await expect(panel.locator('.task-item')).toHaveCount(0);
  await expect(panel.locator('.task-section-progress')).toHaveCount(0);
  await expect(panel.locator('.task-empty')).toHaveCount(0);
  await expect(panel.locator('.task-node')).toHaveCount(2);
  await expect(panel.locator('.task-stats')).toHaveText('2 任务');

  expect(pageErrors).toEqual([]);
});

test('T8 主图同源过滤（核心混合态）：①活跃+②未齐链保留（含已完成成员）、③全终态链零渲染、>120s 分链边界、统计同口径', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);

  // fixture（项目 gamma，同批构造 ①②，>120s 分链边界隔离 ③）：
  //   ① 活跃节点（running/wiring/pending）+ ② 未齐终态链（同批 completed+running 混合）
  //   —— 全部落 t0..t0+4000，相邻间隔 ≤4s → 同一批（链未齐）→ 整批保留显示
  //   ③ 全终态链（completed+cancelled）—— 与 ② 首尾间隔 121s > CHAIN_BATCH_MS
  //   （120000ms）→ 独立成批且批内全终态 → 整链隐藏（这同时就是分链边界用例：
  //   若聚簇错误地把 ③ 并进 ①② 批，批内含 running → ③ 会复活渲染，断言即红）
  const t0 = Date.now() - 10 * 60_000;
  const chainAlive = [
    { id: 'g-run', name: 'gamma-run', agent: 'Backend', status: 'running', createdAt: t0, startedAt: t0 },
    { id: 'g-wire', name: 'gamma-wire', agent: 'Frontend', status: 'wiring', createdAt: t0 + 1_000 },
    { id: 'g-pend', name: 'gamma-pend', agent: 'Docs', status: 'pending', createdAt: t0 + 2_000 },
    { id: 'g-done', name: 'gamma-done', agent: 'Backend', status: 'completed', createdAt: t0 + 3_000, completedAt: t0 + 3_500 },
    { id: 'g-run2', name: 'gamma-run2', agent: 'Backend', status: 'running', createdAt: t0 + 4_000, startedAt: t0 + 4_000 },
  ];
  const chainArchived = [
    { id: 'g-arch1', name: 'gamma-arch1', agent: 'Backend', status: 'completed', createdAt: t0 + 125_000, completedAt: t0 + 125_500 },
    { id: 'g-arch2', name: 'gamma-arch2', agent: 'Docs', status: 'cancelled', createdAt: t0 + 126_000, completedAt: t0 + 126_500 },
  ];
  for (const n of [...chainAlive, ...chainArchived]) {
    await inject(page, { type: 'nodeCreated', project: 'gamma', nodeId: n.id, node: nodeJson(n) });
  }

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);

  // 断言：渲染集合 = ①+② 全部成员（5 行，含已完成 g-done）；③ 零渲染
  await expect(panel.locator('.task-node')).toHaveCount(5);
  for (const id of ['g-run', 'g-wire', 'g-pend', 'g-done', 'g-run2']) {
    await expect(panel.locator(`.task-node[data-node-key="gamma:${id}"]`)).toHaveCount(1);
  }
  await expect(panel.locator('.task-node[data-node-key="gamma:g-arch1"]')).toHaveCount(0);
  await expect(panel.locator('.task-node[data-node-key="gamma:g-arch2"]')).toHaveCount(0);

  // 头部统计 = 主图当前集合口径（过滤后节点数）
  await expect(panel.locator('.task-stats')).toHaveText('5 任务');

  // 链未齐 → 已完成成员 g-done 保留显示（整链保留语义的成员级证据）
  await expect(panel.locator('.task-node[data-node-key="gamma:g-done"] .task-node-word')).toHaveText('已完成');

  expect(pageErrors).toEqual([]);
});

test('T9 全终态单链=列表空：面板收起兜底；活跃成员并入后整链恢复（含已完成成员）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);
  const t0 = Date.now() - 60_000;

  // 唯一节点即整链且全终态 → 过滤后零节点 → 面板收起（.has-tasks 移除，
  // 与主图空=列表空语义一致；既有空态兜底 = 无 .has-tasks 不展开）
  await inject(page, {
    type: 'nodeCreated', project: 'solo', nodeId: 's-done',
    node: nodeJson({ id: 's-done', name: 'solo-done', agent: 'Backend', status: 'completed', createdAt: t0, completedAt: t0 + 1_000 }),
  });
  const panel = page.locator('#task-list');
  await expect(panel).not.toHaveClass(/has-tasks/);
  await expect(panel.locator('.task-node')).toHaveCount(0);

  // 同批并入活跃成员 → 链未齐 → 整链恢复显示（含已完成成员 s-done）
  await inject(page, {
    type: 'nodeCreated', project: 'solo', nodeId: 's-run',
    node: nodeJson({ id: 's-run', name: 'solo-run', agent: 'Backend', status: 'running', createdAt: t0, startedAt: t0 }),
  });
  await expect(panel).toHaveClass(/has-tasks/);
  await expect(panel.locator('.task-node')).toHaveCount(2);
  await expect(panel.locator('.task-node[data-node-key="solo:s-done"]')).toHaveCount(1);
  await expect(panel.locator('.task-node[data-node-key="solo:s-run"]')).toHaveCount(1);
  await expect(panel.locator('.task-stats')).toHaveText('2 任务');

  expect(pageErrors).toEqual([]);
});
