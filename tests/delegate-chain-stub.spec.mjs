// delegate-chain-stub.spec.mjs — ⑤A stub 接口＋taskList delegate 行骨架验收
//（#1228 重划版提前开工批 · 可落地段）＋ 换源接线批扩例（#1228 两裁落派）。
//
// 覆盖面：
//   T1 默认零变化：stub 无夹具 ⇒ delegate 区块零渲染，面板空态收起行为与改前同
//   T2 骨架行渲染：fixture 注入 ⇒ task#N＋执行器图标＋状态 glyph/状态词；
//      ready-to-merge 状态词 = §16 键 1「待合并」逐字（#1228 两裁①落库）；
//      外部行 data-external="1"（S16 粗粒度标记）
//   T3 换源即切（#56 残项 · 晨裁②「并行一窗不保留」）：节点区块零渲染（nodeCreated
//      帧腿照发也不渲染）；统计 = delegate 行口径（换源批统计面）
//   T4 适配层单测（页面内纯函数）：rowFromRegistry / chainFromStoreRecord /
//      mergeQueueFifo（S11 排序键=完成时间 readyAt ↑，非插入序）＋常量钉死
//   T5 读取口 null 安全：查无链 → null；行清单默认空
//   ── 换源接线批（switch-impl）──
//   T6 §16 七键逐字钉（zh/en 双语值提取比对）＋ fm-node 拆出件单源钉（静态）
//   T7 S1 展开：已落三行族解读渲染＋候定集透传不解读；折叠记忆跨重渲；外部行守卫
//   T8 S10 per-task 链视图：ChainNode 卡（fmNodeCard 单源渲染）＋返回钮键2＋fail-soft 键7 重试
//   T9 S11 结构总视图：头键3＋FIFO 泳道键4 readyAt 序＋worktree 座键5＋链卡点进
//   T10 S16 外部行：openNative 出口（POST 断言）＋进度 offset 增量＋展开守卫
//   ── N1 刀3 真源换源（A8 REST provider）扩例 ──
//   T11 换源生效：/api/chains 路由 ⇒ 零夹具行渲染自 live provider（live/verdict 投影）
//   T12 per-task 臂：/api/chains/:key ⇒ S10 渲染一致（节点整组替换；渲染面零改动钉）
//
// 事件帧/面板驱动走真实 ws.js 分发路径注入（原 tasklist-nodes 同 rig spec 已随
// 节点区块退役除名——退役脸断言移 legacy-ui-retire 族 H 段）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/delegate-chain-stub.spec.mjs

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

const ROOT_SID = 'dcs-root-session';
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
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0,
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

/** 走真实 ws.js 分发路径注入一帧。 */
function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

/** 面板渲染入口（与 sidebar.js 会话切换同一入口）。 */
async function renderPanel(page) {
  await page.evaluate(async (sid) => {
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, sid);
  }, ROOT_SID);
  await page.waitForTimeout(100);
}

/** 装 stub 夹具并重渲（harness 注入口——产品路径零调用的专用面）。 */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, 'dcs-root-session');
  }, fx);
  await page.waitForTimeout(100);
}

const row31 = {
  taskNo: '31', delegateId: 'delegate-nebflow-aa11', title: '重构任务面板数据源',
  executor: 'nebflow', status: 'running', external: false, project: 'Nebflow', createdAt: T0 - 120_000,
};
const row32 = {
  taskNo: '32', delegateId: 'delegate-zcode-bb22', title: 'zcode 修断链',
  executor: 'zcode', status: 'blocked', external: true, project: '', createdAt: T0 - 240_000,
};
const row33 = {
  taskNo: '33', delegateId: 'delegate-claude-cc33', title: 'claude 文案批',
  executor: 'claude-code', status: 'ready-to-merge', external: true, project: 'docs', createdAt: T0 - 360_000,
};

test('T1 默认零变化：stub 无夹具 ⇒ delegate 区块零渲染、面板空态收起与改前同', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await renderPanel(page);

  const panel = page.locator('#task-list');
  // 骨架空转：无 delegate 行、无区块、面板收起（.has-tasks 缺席）——与改前逐字节同。
  await expect(panel.locator('.task-section-delegates')).toHaveCount(0);
  await expect(panel.locator('.task-delegate')).toHaveCount(0);
  await expect(panel).not.toHaveClass(/has-tasks/);

  expect(pageErrors).toEqual([]);
});

test('T2 骨架行渲染：task#N＋执行器图标＋状态 glyph/词；ready-to-merge=§16键1「待合并」；外部行 coarse 标记', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [row31, row32, row33] });

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);
  await expect(panel.locator('.task-section-nodes')).toHaveCount(1);
  await expect(panel.locator('.task-delegate')).toHaveCount(3);

  // #31 nebflow running：编号、标题、图标、运行态三点轨道（修订批九点⑥：flowmap
  // 节点同款视觉，替代旧单环 spinner）、状态词=既有键（task.inProgressShort）。
  const r31 = panel.locator('.task-delegate[data-task-no="31"]');
  await expect(r31.locator('.task-delegate-no')).toHaveText('#31');
  await expect(r31.locator('.task-node-label')).toHaveText('重构任务面板数据源');
  await expect(r31.locator('.executor-icon-nebflow')).toHaveCount(1);
  await expect(r31.locator('.task-node-spin')).toHaveCount(1); // 运行态 = 单环转动圈（列表尺度经典脸）
  await expect(r31.locator('.task-node-word')).toHaveText('进行中');
  await expect(r31).not.toHaveAttribute('data-external');
  await expect(r31).toHaveAttribute('data-delegate-id', 'delegate-nebflow-aa11');

  // #32 zcode blocked（外部）：琥珀点 + 阻塞（既有键 task.nodeBlocked）+ coarse 标记。
  const r32 = panel.locator('.task-delegate[data-task-no="32"]');
  await expect(r32.locator('.executor-icon-zcode')).toHaveCount(1);
  await expect(r32.locator('.task-node-dot-blocked')).toHaveCount(1);
  await expect(r32.locator('.task-node-word')).toHaveText('阻塞');
  await expect(r32).toHaveAttribute('data-external', '1');

  // #33 ready-to-merge：§16 键 1 落库（#1228 两裁①）⇒ 状态词「待合并」逐字；
  // glyph 仍中性降级（色信号候 N1 结构帧值域定稿），行本身照常渲染不崩。
  const r33 = panel.locator('.task-delegate[data-task-no="33"]');
  await expect(r33).toHaveCount(1);
  await expect(r33).toHaveAttribute('data-delegate-status', 'ready-to-merge');
  await expect(r33.locator('.task-node-word')).toHaveText('待合并');
  await expect(r33.locator('.task-node-dot-neutral')).toHaveCount(1);
  await expect(r33).toHaveAttribute('data-external', '1');

  expect(pageErrors).toEqual([]);
});

test('T3 换源即切（#56 残项 · 晨裁②）：并行一窗摘除——nodeCreated 帧腿照发零节点区块渲染；统计=delegate 行口径', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [row31, row32] });
  // 换源前并行一窗的节点帧腿照发（WS node* 帧族 = provider 刷新触发器面，不撤）：
  // 面板零节点区块渲染——帧只当触发器，数据权威恒在读取口（REST 镜像/夹具优先缝）。
  await inject(page, {
    type: 'nodeCreated', project: 'alpha', nodeId: 'n-run',
    node: { hasWorktree: false, worktree: null, result: null, retries: 0, id: 'n-run', name: 'scan-repo', agent: 'Backend', status: 'running', createdAt: T0, startedAt: T0, completedAt: null, ttlLeftSec: null },
  });
  await page.waitForTimeout(900); // 越过 provider 500ms 防抖（帧→刷→镜像事件→重渲全链）

  const panel = page.locator('#task-list');
  await expect(panel.locator('.task-delegate')).toHaveCount(2);
  // Node frames are only provider refresh triggers — they add no rows of their
  // own: exactly the two delegate header rows render, zero member rows (the
  // fixture carries no children/chains), and the panel section is the chain
  // section.
  await expect(panel.locator('.task-chain-member')).toHaveCount(0);
  await expect(panel.locator('.task-node')).toHaveCount(2);
  await expect(panel.locator('.task-section-nodes')).toHaveCount(1);

  // Stats = visible row count (roots only here — the fixture has no members).
  await expect(panel.locator('.task-stats')).toHaveText('2 任务');

  expect(pageErrors).toEqual([]);
});

test('T4 适配层（页面内纯函数）：registry 行归一＋ChainStore 行投影＋FIFO 排序键=完成时间', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  const out = await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    // DelegateRegistry.Record 形 JSON（#1225 落库面：reportStatus 状态槽 + cliSessionId）。
    const recA = { id: 'delegate-nebflow-x', executor: 'nebflow', cwd: '/w/p', project: 'p', taskId: '44', parentSessionId: 's', title: 'T-A', createdAt: 1, reportStatus: 'finish', reportDetail: 'ok' };
    const recB = { id: 'delegate-zcode-y', executor: 'zcode', cwd: '/w/q', project: '', taskId: '', parentSessionId: 's', title: 'T-B', createdAt: 2, reportStatus: 'blocked', reportDetail: 'conflict' };
    const recC = { id: 'delegate-nebflow-z', executor: 'nebflow', cwd: '/g', project: '', taskId: '46', parentSessionId: 's', title: 'T-C', createdAt: 3 };
    // ChainStore.Record 形 JSON（#1224 三行族；merge-queue 故意乱序入）。
    const store = {
      taskId: '44',
      entries: [
        { kind: 'delegate-spawn', at: 1000, payload: { delegateId: 'delegate-nebflow-x', executor: 'nebflow', project: 'p', branch: 'delegate/d-x', worktree: '/wt/x', cwd: '/wt/x', baseWorkspace: '/w/p', baseBranch: 'main', baseSha: 'abc' } },
        { kind: 'merge-queue', at: 5000, payload: { action: 'enqueued', delegateId: 'late', branch: 'delegate/late', projectKey: 'p', readyAt: 9000 } },
        { kind: 'terminal-verdict', at: 4000, payload: { delegateId: 'delegate-nebflow-x', taskId: '44', verdict: 'ready-to-merge', reason: '', source: 'judge' } },
        { kind: 'merge-queue', at: 6000, payload: { action: 'enqueued', delegateId: 'early', branch: 'delegate/early', projectKey: 'p', readyAt: 7000 } },
        { kind: 'chain-registered', at: 7000, payload: { nodes: [{ id: 'a', deps: [] }] } },
      ],
    };
    return {
      rowA: stub.rowFromRegistry(recA),
      rowB: stub.rowFromRegistry(recB),
      rowC: stub.rowFromRegistry(recC, { status: 'pending' }),
      view: stub.chainFromStoreRecord(store),
      viewEmpty: stub.chainFromStoreRecord(null),
      consts: { ready: stub.ROW_STATUS.READY_TO_MERGE, spawn: stub.FRAME_KINDS.DELEGATE_SPAWN },
      statuses: stub.verdictToStatus('ready-to-merge') + '|' + stub.verdictToStatus('blocked') + '|' + stub.verdictToStatus('completed') + '|' + stub.verdictToStatus('weird'),
    };
  });

  // 行归一：reportStatus 状态槽映射 + external 判定（≠nebflow）。
  expect(out.rowA).toMatchObject({ taskNo: '44', status: 'completed', external: false, executor: 'nebflow' });
  expect(out.rowB).toMatchObject({ taskNo: '', status: 'blocked', external: true });
  expect(out.rowC).toMatchObject({ status: 'pending', external: false });

  // 链视图投影：spawn 行 → worktree；verdict 行 → 判词；FIFO 按 readyAt（完成时间）
  // 而非行到达序（late@9000 排 early@7000 之后）——S11 排序键改义的可执行钉。
  expect(out.view.taskId).toBe('44');
  expect(out.view.worktree).toMatchObject({ branch: 'delegate/d-x', baseBranch: 'main', baseSha: 'abc' });
  expect(out.view.verdict).toMatchObject({ verdict: 'ready-to-merge', source: 'judge', at: 4000 });
  expect(out.view.mergeQueue.map((q) => q.delegateId)).toEqual(['early', 'late']);
  // N1 候定帧透传不解读（frames 原样保真）。
  expect(out.view.frames).toHaveLength(1);
  expect(out.view.frames[0]).toMatchObject({ kind: 'chain-registered', at: 7000 });
  // 链节点（N1 定稿前单 delegate 形）：判词映射状态。
  expect(out.view.nodes).toHaveLength(1);
  expect(out.view.nodes[0]).toMatchObject({ id: 'delegate-nebflow-x', kind: 'delegate', status: 'ready-to-merge' });

  // 空形容忍：null 入参 → 全空投影，不抛。
  expect(out.viewEmpty).toMatchObject({ taskId: '', nodes: [], worktree: null, verdict: null, mergeQueue: [], frames: [] });

  // 常量钉死（跨批契约键禁漂移）。
  expect(out.consts).toEqual({ ready: 'ready-to-merge', spawn: 'delegate-spawn' });
  expect(out.statuses).toBe('ready-to-merge|blocked|completed|running');

  expect(pageErrors).toEqual([]);
});

test('T5 读取口 null 安全：查无链 → null；行清单默认空；reset 归零', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  const before = await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    return { rows: stub.listDelegateRows().length, chain: stub.getChainByTask('999') };
  });
  expect(before.rows).toBe(0);
  expect(before.chain).toBeNull();

  await setFixture(page, { rows: [row31], chains: [{ taskId: '31', delegateId: 'delegate-nebflow-aa11', nodes: [], worktree: null, verdict: null, mergeQueue: [], frames: [] }] });
  const during = await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    return { hit: stub.getChainByTask('31')?.taskId || '', miss: stub.getChainByTask('nope') };
  });
  expect(during.hit).toBe('31');
  expect(during.miss).toBeNull();

  await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__resetFixture();
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, 'dcs-root-session');
  });
  const panel = page.locator('#task-list');
  await expect(panel.locator('.task-delegate')).toHaveCount(0);
  await expect(panel.locator('.task-section-delegates')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

// ── 换源接线批（switch-impl · #1228 两裁落派）扩例 ──────────────────────────

const row34 = {
  taskNo: '34', delegateId: 'delegate-zcode-run44', title: 'zcode 跑长任务',
  executor: 'zcode', status: 'running', external: true, project: '', createdAt: T0 - 30_000,
};

/** S1 展开夹具链（#31 内部行：已落三行族投影 + 候定集透传帧）。 */
const chain31 = {
  taskId: '31', delegateId: 'delegate-nebflow-aa11',
  nodes: [{ id: 'delegate-nebflow-aa11', kind: 'delegate', label: '重构任务面板数据源',
    description: '把 delegate 行接上 ⑤A 读取口', status: 'running', deps: [], executor: 'nebflow' }],
  worktree: { branch: 'delegate/task-31', worktree: '/wt/31', cwd: '/wt/31', baseWorkspace: '/w', baseBranch: 'main', baseSha: 'abc123' },
  verdict: { verdict: 'running', reason: '', source: '', at: 0 },
  mergeQueue: [],
  frames: [{ kind: 'chain-registered', at: T0 - 60_000, payload: { nodes: [{ id: 'a', deps: [] }] } }],
};

/** 外部行夹具链（#32：数据在场也**不得**展开——S16 守卫终态钉）。 */
const chain32 = {
  taskId: '32', delegateId: 'delegate-zcode-bb22',
  nodes: [{ id: 'delegate-zcode-bb22', kind: 'delegate', label: 'zcode 修断链',
    description: 'internal details', status: 'blocked', deps: [], executor: 'zcode' }],
  worktree: { branch: 'delegate/task-32', worktree: '/wt/32', cwd: '/wt/32', baseWorkspace: '/w', baseBranch: 'main', baseSha: 'def456' },
  verdict: null, mergeQueue: [], frames: [],
};

/** S11 夹具链族（merge-queue 乱序 readyAt——泳道序钉）。 */
const chain33 = {
  taskId: '33', delegateId: 'delegate-claude-cc33',
  nodes: [{ id: 'delegate-claude-cc33', kind: 'delegate', label: 'claude 文案批',
    description: 'docs', status: 'ready-to-merge', deps: [], executor: 'claude-code' }],
  worktree: { branch: 'delegate/task-33', worktree: '/wt/33', cwd: '/wt/33', baseWorkspace: '/w', baseBranch: 'main', baseSha: 'aaa' },
  verdict: { verdict: 'ready-to-merge', reason: 'docs pass', source: 'judge', at: T0 - 50_000 },
  mergeQueue: [{ delegateId: 'delegate-claude-cc33', branch: 'delegate/task-33', projectKey: 'docs', readyAt: T0 - 50_000, action: 'enqueued', at: T0 - 50_000 }],
  frames: [],
};
const chain34 = {
  taskId: '34', delegateId: 'delegate-zcode-run44',
  nodes: [{ id: 'delegate-zcode-run44', kind: 'delegate', label: 'zcode 跑长任务',
    description: '', status: 'running', deps: [], executor: 'zcode' }],
  worktree: null,
  verdict: null,
  mergeQueue: [{ delegateId: 'delegate-zcode-run44', branch: 'delegate/task-34', projectKey: 'p', readyAt: T0 - 10_000, action: 'enqueued', at: T0 - 10_000 }],
  frames: [],
};

/** 进 per-task 链视图的 UI 路径（树批 2026-10-09 后）：任务列表行内零 L1 入口钮
 *  （内部行行尾 workflow 钮退役）——项目面板「项目流程图」圆钮（方案 A2）→ L2 →
 *  点该 delegate 链卡。 */
async function openChainViewViaProjects(page, taskNo) {
  await page.evaluate(async () => {
    const m = await import('/js/projectTab.js');
    await m.openProjectsTab();
  });
  const pane = page.locator('.canvas-tab-pane[data-tab-id="projects"]');
  await pane.locator('.proj-flowmap-fab').click();
  await expect(pane).toHaveAttribute('data-projects-view', 'delegate-chain');
  await pane.locator('.delegate-chain-list .solar-node.fm-node', { hasText: `#${taskNo}` }).first().click();
  await expect(pane).toHaveAttribute('data-delegate-mode', 'task');
  await page.waitForTimeout(250);
}

test('T6 §16 七键逐字钉（zh/en 双语提取比对）＋ fm-node 拆出件单源钉（静态）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await bootApp(page);

  // 页面内 zh 词面（i18n 单源 = locales 模块）。
  const zh = await page.evaluate(async () => {
    const { t } = await import('/js/i18n.js');
    return {
      readyToMerge: t('delegate.readyToMerge'),
      backToTasks: t('delegate.backToTasks'),
      structure: t('delegate.structure'),
      mergeQueue: t('delegate.mergeQueue'),
      worktree: t('delegate.worktree'),
      openNative: t('delegate.openNative'),
      chainUnavailable: t('delegate.chainUnavailable'),
    };
  });
  expect(zh).toEqual({
    readyToMerge: '待合并',
    backToTasks: '返回任务列表',
    structure: '委托结构',
    mergeQueue: '合并队列',
    worktree: '工作树',
    openNative: '在原软件打开',
    chainUnavailable: '链视图暂时打不开，点击重试',
  });

  // 文件级双语逐字钉：zh/en locale 源码里七个键值逐字存在（值禁改一字的落库证据）。
  const zhSrc = readFileSync(join(WEB, 'js', 'locales', 'zh-CN.js'), 'utf8');
  const enSrc = readFileSync(join(WEB, 'js', 'locales', 'en.js'), 'utf8');
  const pairs = [
    ['delegate.readyToMerge', '待合并', 'Ready to merge'],
    ['delegate.backToTasks', '返回任务列表', 'Back to tasks'],
    ['delegate.structure', '委托结构', 'Delegate structure'],
    ['delegate.mergeQueue', '合并队列', 'Merge queue'],
    ['delegate.worktree', '工作树', 'Worktree'],
    ['delegate.openNative', '在原软件打开', 'Open in original app'],
    ['delegate.chainUnavailable', '链视图暂时打不开，点击重试', 'Chain view unavailable — click to retry'],
  ];
  for (const [key, zhv, env] of pairs) {
    expect(zhSrc).toContain(`'${key}': '${zhv}'`);
    expect(enSrc).toContain(`'${key}': '${env}'`);
  }

  // 拆出件单源钉：fmNodeCard.js 是 fm-node 卡渲染唯一单源；flowMapTab / delegateChainView
  // 都引它，且 flowMapTab 本体零卡渲染函数残留（禁复制双权威）。
  const fmCardSrc = readFileSync(join(WEB, 'js', 'fmNodeCard.js'), 'utf8');
  const fmTabSrc = readFileSync(join(WEB, 'js', 'flowMapTab.js'), 'utf8');
  const dcvSrc = readFileSync(join(WEB, 'js', 'delegateChainView.js'), 'utf8');
  expect(fmCardSrc).toContain('export function nodeHtml');
  expect(fmCardSrc).toContain("from './flowMapArchive.js'"); // isTerminalStatus 单源
  expect(fmTabSrc).toContain("from './fmNodeCard.js'");
  expect(fmTabSrc).not.toMatch(/function nodeHtml|function chainCardHtml/);
  expect(dcvSrc).toContain("from './fmNodeCard.js'");
  expect(dcvSrc).not.toMatch(/function nodeHtml|solar-orbit/); // 视图层零卡面副本

  // DOM 侧：ready-to-merge 行词 = 键 1（zh 环境词面钉）。
  await setFixture(page, { rows: [row33] });
  await expect(page.locator('.task-delegate[data-task-no="33"] .task-node-word')).toHaveText('待合并');

  expect(pageErrors).toEqual([]);
});

test('T7 链块折叠（链结构行脸）：成员行默认折叠 + 头行点击展开 + 成员行点击跳 L1 地图（锚点闪）＋外部行守卫', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, {
    rows: [row31, row32],
    chains: [chain31, chain32],
    children: {
      'delegate-nebflow-aa11': [
        { taskId: 'subtask-t7', agentName: 'scout', status: 'running', source: 'subtask', spawnedAt: T0 },
      ],
    },
  });

  const panel = page.locator('#task-list');
  const r31 = panel.locator('.task-chain-head[data-task-no="31"]');
  // Collapsed by default: zero member rows; the header carries the fold twisty
  // (aria-expanded=false) + dotmap + progress meta.
  await expect(panel.locator('.task-chain-member')).toHaveCount(0);
  await expect(r31).toHaveAttribute('aria-expanded', 'false');
  await expect(r31.locator('.task-chain-twisty')).toHaveCount(1);
  await expect(r31.locator('.task-chain-jump')).toHaveCount(1); // chain-level L1 jump zone
  await expect(r31.locator('.task-chain-meta')).toHaveText(/^0\/2/);

  // Header click = expand (classic chain-row semantics).
  await r31.click();
  await expect(r31).toHaveAttribute('aria-expanded', 'true');
  const kid = panel.locator('.task-chain-member[data-node-key="31:subtask-t7"]');
  await expect(kid).toHaveCount(1);
  await expect(kid.locator('.task-chain-member-name')).toHaveText('scout');
  await expect(kid.locator('.task-chain-member-word')).toHaveText('进行中');

  // Member row click = the L1 flowmap anchored at that node (camera + flash).
  await kid.click();
  const l1 = page.locator('.canvas-tab-pane[data-projects-view="delegate-chain"][data-delegate-mode="task"]');
  await expect(l1).toHaveCount(1);
  await expect(l1).toHaveAttribute('data-delegate-key', '31');
  await expect(l1.locator('.delegate-flowmap-host .fm-node')).toHaveCount(2); // root + scout
  await expect(l1.locator('.fm-node[data-node-id="subtask-t7"].fm-node-flash')).toHaveCount(1);
  await page.locator('[data-back-to-tasks]').click();
  await expect(l1).toHaveCount(0);

  // External-row guard: display-only (no role, no fold) even with chain data
  // present; the open-native exit button stays.
  const r32 = panel.locator('.task-chain-head[data-task-no="32"]');
  await expect(r32).not.toHaveAttribute('role', 'button');
  await expect(r32.locator('.task-chain-twisty-leaf')).toHaveCount(1);
  await r32.click();
  await expect(page.locator('.canvas-tab-pane[data-delegate-mode="task"]')).toHaveCount(0);
  await expect(r32.locator('.task-delegate-btn')).toHaveCount(1);

  expect(pageErrors).toEqual([]);
});

test('T8 S10 per-task 链视图：ChainNode 卡（单源渲染件）＋返回钮键2＋fail-soft 键7 重试', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  // 相 1：无链夹具 → fail-soft 态（键 7）+ 重试（仍 fail-soft，面板不崩）。
  await setFixture(page, { rows: [row31] });
  await openChainViewViaProjects(page, '31');

  const pane = page.locator('.canvas-tab-pane[data-tab-id="projects"]');
  await expect(pane).toHaveCount(1);
  await expect(pane).toHaveAttribute('data-projects-view', 'delegate-chain');
  await expect(pane).toHaveAttribute('data-delegate-key', '31');
  const body = pane.locator('.delegate-chain-view-body');
  const failsoft = body.locator('.delegate-chain-failsoft');
  await expect(failsoft).toHaveText('链视图暂时打不开，点击重试');
  await failsoft.click(); // 重试腿：行保留、视图重拉（仍无链 → 同态，不崩）
  await expect(body.locator('.delegate-chain-failsoft')).toHaveCount(1);

  // 返回钮 = §16 键 2 逐字。
  const back = pane.locator('[data-back-to-tasks]');
  await expect(back).toContainText('返回任务列表');
  await back.click();
  await expect(pane).toHaveCount(0); // 关页签 = 回任务列表

  // 相 2：装链夹具 → L1 = 该链的 flowmap（引擎面）：单成员链 = 画布上一张根卡
  // （无折叠控件——可见成员 <2 恒无）；链标识挂导航栏 identity 槽，摘要槽 = 引擎
  // 计数行；worktree = 根卡 wt 徽标；点卡 = 详情浮层（.fm-detail 经典脸）。
  await setFixture(page, { rows: [row31], chains: [chain31] });
  await openChainViewViaProjects(page, '31');
  await expect(pane).toHaveAttribute('data-projects-view', 'delegate-chain');
  const mapHost = pane.locator('.delegate-flowmap-host');
  await expect(mapHost).toHaveCount(1);
  const card = mapHost.locator('.fm-node[data-node-id="delegate-nebflow-aa11"]');
  await expect(card).toHaveCount(1);
  await expect(card.locator('.solar-node-label')).toHaveText('#31 重构任务面板数据源');
  await expect(card.locator('.fm-worktree-badge')).toHaveCount(1); // worktree 座 → 卡面 wt 徽标
  await expect(pane.locator('.delegate-chain-title')).toHaveText('#31 · 委托流程图');
  await expect(pane.locator('.flowmap-summary')).toContainText('运行中'); // 引擎计数行
  // 单成员链：零折叠控件、零取消钮（control:false 作用域）、归档 FAB 隐藏。
  await expect(mapHost.locator('.fm-chain-chev')).toHaveCount(0);
  await expect(mapHost.locator('.fm-chain-cancel')).toHaveCount(0);
  const fab = pane.locator('.fm-fab');
  await expect(fab).toHaveCSS('display', 'none');
  // 点根卡 = 详情浮层（对话窗入口随地图面退役——详情卡承载节点信息）。
  await card.click();
  const detail = page.locator('.fm-detail');
  await expect(detail).toHaveClass(/open/);
  await expect(detail.locator('.fm-detail-title')).toHaveText('#31 重构任务面板数据源');
  await page.keyboard.press('Escape');
  await expect(detail).toBeHidden();

  expect(pageErrors).toEqual([]);
});

test('T9 S11 结构总视图：头键3＋worktree 座键5＋链卡点进＋九点②④⑧（FIFO 泳道撤·名次 chip·归档 FAB）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  // 归档历史窗路由（FAB 徽章/抽屉清单源；后注册 = 优先命中）：空归档 = 徽章缺席。
  await page.route('**/api/chains?archived=1', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ rows: [] }) }));
  // 行集：#31 内部行 ＋ #34/#33 外部行（merge-queue 夹具在链上，乱序 readyAt——
  // #34 T0-10s 在前、#33 T0-50s 在后 ⇒ 名次回落臂按 readyAt 重排）。
  await setFixture(page, { rows: [row31, row34, row33], chains: [chain31, chain34, chain33] });
  // 树批（2026-10-09）入口迁移：进 L2 = 项目面板「项目流程图」圆钮（方案 A2）。
  await page.evaluate(async () => {
    const m = await import('/js/projectTab.js');
    await m.openProjectsTab();
  });
  const pane = page.locator('.canvas-tab-pane[data-tab-id="projects"]');
  await pane.locator('.proj-flowmap-fab').click();
  await expect(pane).toHaveAttribute('data-projects-view', 'delegate-chain');

  // 头 = 两层架构批 §D L2 标题（多项目行集 → 裸「项目流程图」）；worktree 座 = 键 5；
  // 九点②④⑧（2026-10-09）：FIFO 泳道撤销（合并态不显示 FIFO、不连线、零明细行）
  // ——唯一可见面 = 待合并链卡旁名次 chip。
  await expect(pane.locator('.delegate-structure-head')).toHaveText('项目流程图');
  const sections = pane.locator('.delegate-structure-section');
  await expect(sections).toHaveCount(1);
  await expect(sections.nth(0).locator('.delegate-structure-label')).toHaveText('工作树');
  await expect(pane.locator('.delegate-structure-lane')).toHaveCount(0);

  // worktree 座：三行链中 #31/#33 有 worktree 投影（#34 null）⇒ 两座（行序）。
  const seats = sections.nth(0).locator('.delegate-structure-seat');
  await expect(seats).toHaveCount(2);
  await expect(seats.nth(0).locator('.delegate-chain-mono')).toContainText('delegate/task-31');
  await expect(seats.nth(1).locator('.delegate-chain-mono')).toContainText('delegate/task-33');

  // §D WF-05 三段分组（组序 = 生命周期，段内序 = 行序首现）：运行中(#31,#34) →
  // 待合并(#33)；「已完成」段空 ⇒ 组头不渲染（零空段）。名次 chip（九点④口径）只
  // 属待合并段，位次回落臂 = mergeQueueFifo 全局序（#33 T0-50s ⇒ #1；#34 运行段
  // 不显名次）。外部行（#33/#34）卡无下钻（G7 守卫）；内部行 #31 点卡 → 该链 L1。
  const groups = pane.locator('.delegate-structure-group');
  await expect(groups).toHaveCount(2);
  await expect(groups).toHaveText(['进行中', '待合并']);
  const card = pane.locator('.delegate-chain-list .solar-node.fm-node');
  await expect(card).toHaveCount(3);
  await expect(card.nth(0).locator('.solar-node-label')).toHaveText('#31 重构任务面板数据源');
  await expect(card.nth(1).locator('.solar-node-label')).toHaveText('#34 zcode 跑长任务');
  await expect(card.nth(2).locator('.solar-node-label')).toHaveText('#33 claude 文案批');
  await expect(card.nth(2).locator('.fm-st-word')).toHaveText('待合并');
  await expect(card.nth(2).locator('.delegate-card-rank')).toHaveText('#1');
  await expect(card.nth(0).locator('.delegate-card-rank')).toHaveCount(0); // 运行段无名次
  await expect(card.nth(1).locator('.delegate-card-rank')).toHaveCount(0);
  // L2 卡面徽标（§D 与 §B 行内徽标同件）：夹具零 children/零 flow-step ⇒ 徽标缺席。
  await expect(card.locator('.delegate-card-kids')).toHaveCount(0);
  await expect(card.locator('.delegate-card-flow')).toHaveCount(0);
  await expect(card.nth(0)).toHaveAttribute('role', 'button');
  await expect(card.nth(1)).not.toHaveAttribute('role', 'button');
  await expect(card.nth(1)).toHaveAttribute('data-external-card', '1');
  await expect(card.nth(2)).not.toHaveAttribute('role', 'button');
  await expect(card.nth(2)).toHaveAttribute('data-external-card', '1');
  // §D WF-07：右上角归档件 = FAB（flowmap fm-fab 同语言）→ 抽屉（唯一归档视图）。
  const fab = pane.locator('.delegate-archive-fab');
  await expect(fab).toHaveCount(1);
  await expect(fab).toHaveAttribute('aria-expanded', 'false');
  await expect(pane.locator('.delegate-archive-badge')).toBeHidden(); // 空归档 = 徽章缺席
  await fab.click();
  const drawer = pane.locator('.delegate-archive-drawer');
  await expect(drawer).toBeVisible();
  await expect(drawer.locator('.delegate-archive-drawer-head')).toContainText('已归档委托');
  await expect(drawer.locator('.delegate-archive-empty')).toHaveText('暂无已归档任务链');
  await drawer.locator('.delegate-archive-drawer-close').click();
  await expect(drawer).toBeHidden();
  await expect(fab).toHaveAttribute('aria-expanded', 'false');
  await card.nth(0).click();
  await expect(pane).toHaveAttribute('data-delegate-mode', 'task');
  await expect(pane).toHaveAttribute('data-delegate-key', '31');
  await expect(pane.locator('.delegate-chain-view-body .solar-node.fm-node')).toHaveCount(1);

  expect(pageErrors).toEqual([]);
});

test('T10 S16 外部行：openNative 出口（POST 断言）＋进度 offset 增量＋展开守卫', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);

  // #1225 落库端点夹具（PresenceRoutes 形）：output = offset 增量；open-native = fire-and-forget。
  const outputCalls = [];
  const nativeCalls = [];
  await page.route('**/api/delegates/delegate-zcode-run44/output*', (route) => {
    const url = new URL(route.request().url());
    const offset = Number(url.searchParams.get('offset') || '0');
    outputCalls.push(offset);
    const base = { taskId: '34', status: 'running', totalBytes: 64, totalLines: 3, truncated: false, nextOffset: 0, startedAtMs: T0, finishedAtMs: null, exitCode: null, errorHint: null, cliSessionId: 'sid-44', mailboxDepth: 0 };
    if (offset === 0) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...base, output: 'scanning files\nfound 3 issues\n', nextOffset: 26 }) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...base, output: 'still working\n', nextOffset: 40 }) });
  });
  await page.route('**/api/delegates/delegate-zcode-run44/open-native', (route) => {
    nativeCalls.push(route.request().method());
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ opened: true, command: 'zcode --resume sid-44' }) });
  });

  await setFixture(page, { rows: [row34] });

  const r34 = page.locator('.task-delegate[data-task-no="34"]');
  // 出口钮：title/aria = §16 键 6 逐字。
  const nativeBtn = r34.locator('.task-delegate-btn');
  await expect(nativeBtn).toHaveAttribute('title', '在原软件打开');
  await expect(nativeBtn).toHaveAttribute('aria-label', '在原软件打开');

  // 进度腿（粗粒度）：首轮 offset=0 → meta 行显输出尾行（数据）。
  await expect(r34.locator('.task-node-meta')).toHaveText('found 3 issues', { timeout: 8000 });
  expect(outputCalls[0]).toBe(0);

  // offset 增量钉：强制第二轮 → 请求带 nextOffset(26)，meta 换新尾行。
  await page.evaluate(async () => {
    const m = await import('/js/taskList.js');
    await m.__pollDelegateProgress(true);
  });
  await expect(r34.locator('.task-node-meta')).toHaveText('still working', { timeout: 8000 });
  expect(outputCalls).toContain(26);

  // 出口腿：点钮 → POST /api/delegates/:id/open-native 恰一次（防重入单发）。
  await nativeBtn.click();
  await page.waitForTimeout(200);
  expect(nativeCalls).toEqual(['POST']);

  // Display-only guard: external rows carry no click-through (no role=button,
  // click opens no L1 pane) — the open-native button is the only exit.
  await expect(r34).not.toHaveAttribute('role', 'button');
  await r34.click();
  await expect(page.locator('.canvas-tab-pane[data-delegate-mode="task"]')).toHaveCount(0);

  expect(pageErrors).toEqual([]);
});

// ── N1 刀3 真源换源（A8 REST provider · #1228 setSource 落真）扩例 ─────────────
//
//   T11 换源生效：路由 /api/chains → 行投影 JSON，boot 后**零夹具**——task list
//      delegate 行渲染自 live provider（WS 帧只当刷新触发器；行权威 = REST）。
//   T12 per-task 臂：/api/chains/:key → record + chain，S10 视图渲染一致
//      （渲染面零改动——同一 fmNodeCard/delegateChainView 代码路径）。

/** DelegateRegistry.Record 形 JSON（A8 rows 臂输入：record × live × verdict）。 */
const rec44 = {
  id: 'delegate-nebflow-run44', executor: 'nebflow', cwd: '/w/p', project: 'p',
  taskId: '44', parentSessionId: 'nebula-root', title: 'REST 行投影任务', createdAt: T0 - 90_000,
};
const rec45 = {
  id: 'delegate-nebflow-merge45', executor: 'nebflow', cwd: '/w/p', project: 'p',
  taskId: '45', parentSessionId: 'nebula-root', title: 'REST 判词任务', createdAt: T0 - 80_000,
};

/** REST 路由后置注册（Playwright 后注册者优先）＋ 一帧 node* 触发防抖刷 + 重渲。 */
async function serveChains(page, rowsBody, chainByKey) {
  await page.route('**/api/chains', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rowsBody) }));
  await page.route('**/api/chains/*', (route) => {
    const url = new URL(route.request().url());
    const key = decodeURIComponent(url.pathname.split('/').pop() || '');
    const body = chainByKey[key]
      ? { key, record: { taskId: key, entries: [] }, chain: chainByKey[key] }
      : { error: 'chain record not found' };
    return route.fulfill({ status: chainByKey[key] ? 200 : 404, contentType: 'application/json', body: JSON.stringify(body) });
  });
  // WS 帧 = 刷新触发器（provider 500ms 防抖）→ 等防抖窗过后重渲一次（真实侧栏
  // 重渲循环同款入口）。
  await inject(page, { type: 'nodeUpdated', project: 'p', nodeId: 'n-trigger' });
  await page.waitForTimeout(800);
  await renderPanel(page);
}

test('T11 换源生效：零夹具 ⇒ 行渲染自 live provider（live 活性提示＋verdict 覆写单点）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await serveChains(page, {
    rows: [
      { ...rec44, live: { status: 'running' } },
      { ...rec45, live: { status: 'running' }, verdict: { verdict: 'ready-to-merge', reason: 'branch ready', source: 'self-report', at: T0 } },
    ],
  }, {});

  // 换源钉：夹具缝**未使用**——行来自 REST 镜像（零 __setFixture 调用）。
  const seam = await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    return { hasFixture: stub.__hasFixture(), rows: stub.listDelegateRows().length };
  });
  expect(seam.hasFixture).toBe(false);
  expect(seam.rows).toBe(2);

  const panel = page.locator('#task-list');
  // #44：live {running} → 行状态进行中（rowFromRegistry 活性推导原样）。
  const r44 = panel.locator('.task-delegate[data-task-no="44"]');
  await expect(r44).toHaveCount(1);
  await expect(r44.locator('.task-node-label')).toHaveText('REST 行投影任务');
  await expect(r44.locator('.task-node-word')).toHaveText('进行中');
  // #45：verdict=ready-to-merge → provider 覆写单点（v3 §9 判词供给）→「待合并」。
  const r45 = panel.locator('.task-delegate[data-task-no="45"]');
  await expect(r45).toHaveCount(1);
  await expect(r45.locator('.task-node-word')).toHaveText('待合并');
  await expect(r45).toHaveAttribute('data-delegate-status', 'ready-to-merge');

  expect(pageErrors).toEqual([]);
});

test('T12 per-task 臂：REST record+chain ⇒ S10 渲染一致（节点整组替换；渲染面零改动钉）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await serveChains(
    page,
    { rows: [{ ...rec44, live: { status: 'running' } }] },
    {
      44: {
        scope: 'delegate-nebflow-run44', mounted: true,
        nodes: [
          { id: 'n-a', kind: 'flow-step', label: 'REST 链节点甲', description: '后端投影描述', status: 'pending', deps: ['n-b'], executor: '' },
          { id: 'n-b', kind: 'flow-step', label: 'REST 链节点乙', description: '', status: 'completed', deps: [], executor: '' },
        ],
      },
    },
  );

  // 链镜像：getChainByTask('44') 节点 = REST 整组替换（adapter 回退节点零残留）。
  const view = await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    const v = stub.getChainByTask('44');
    return { n: v && v.nodes ? v.nodes.length : -1, ids: v && v.nodes ? v.nodes.map((x) => x.id) : [] };
  });
  expect(view.n).toBe(2);
  expect(view.ids).toEqual(['n-a', 'n-b']);

  // L1（地图面）：链 44 = 根卡（行投影合成）+ 两条 flow-step 卡（REST 投影整组
  // 替换），边 = root→n-b（入口 spawn 线）+ n-b→n-a（deps 结构线）——画布引擎
  // 渲染，卡面 = fmNodeCard 单源（图内路径）。
  await openChainViewViaProjects(page, '44');
  const pane = page.locator('.canvas-tab-pane[data-tab-id="projects"]');
  await expect(pane).toHaveAttribute('data-projects-view', 'delegate-chain');
  const mapHost = pane.locator('.delegate-flowmap-host');
  const mapCards = mapHost.locator('.fm-node');
  await expect(mapCards).toHaveCount(3);
  await expect(mapHost.locator('.fm-node[data-node-id="delegate-nebflow-run44"]')).toHaveCount(1);
  await expect(mapHost.locator('.fm-node[data-node-id="n-a"] .solar-node-label')).toHaveText('REST 链节点甲');
  await expect(mapHost.locator('.fm-node[data-node-id="n-b"] .solar-node-label')).toHaveText('REST 链节点乙');
  await expect(mapHost.locator('path.fm-edge')).toHaveCount(2);
  // executor:'' 继承链契约 → 节点卡零执行器挂牌（后端投影零字段映射的可执行钉）。
  await expect(mapHost.locator('.executor-icon')).toHaveCount(0);
  // 链 3 成员 ⇒ 根卡带折叠 chevron（无 ChainLedger 面 ⇒ 零取消钮）。
  await expect(mapHost.locator('.fm-chain-chev')).toHaveCount(1);
  await expect(mapHost.locator('.fm-chain-cancel')).toHaveCount(0);

  // 渲染面零改动（静态钉）：视图/骨架两文件不**静态引** provider——换源只在
  // main.js 单缝（两层架构批：delegateChainView 抽屉「恢复」臂动态 import
  // refreshMirror 对账——动态 import 不构成静态耦合，钉面只禁 from 形态）；
  // stub 读取口形状四导出不变（read port 契约）。
  const taskListSrc = readFileSync(join(WEB, 'js', 'taskList.js'), 'utf8');
  const dcvSrc = readFileSync(join(WEB, 'js', 'delegateChainView.js'), 'utf8');
  const stubSrc = readFileSync(join(WEB, 'js', 'delegateChainStub.js'), 'utf8');
  const provSrc = readFileSync(join(WEB, 'js', 'delegateChainProvider.js'), 'utf8');
  expect(taskListSrc).not.toContain('delegateChainProvider');
  expect(dcvSrc).not.toMatch(/from\s+['"]\.\/delegateChainProvider(\.js)?['"]/);
  expect(stubSrc).toContain('export function setSource');
  expect(stubSrc).toContain('export function listDelegateRows');
  expect(stubSrc).toContain('export function getChainByTask');
  expect(stubSrc).toContain('export function rowFromRegistry');
  expect(provSrc).toContain("from './delegateChainStub.js'");

  expect(pageErrors).toEqual([]);
});

test('T13 badge 行×任务行盒模型对齐（ui-train-threefix C·2026-10-07 截图令）：统计徽标文案列与 delegate 行文案列同线', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [row31, row32] });
  // Box-model reads must settle: new rows play the entry animation
  // (translateX(-12px) → 0, 0.3s) — wait it out before measuring.
  await page.waitForFunction(() => !document.querySelector('#task-list .task-node-entering'), null, { timeout: 5000 }).catch(() => {});

  // 盒模型读数（card-relative）：badge 行（.task-header/.task-stats）文案左缘 ==
  // 任务行（.task-node-text）文案左缘。方程（链块行脸）：toggle 20 + gap 8 +
  // 对齐余量 44 == 组缩进 10 + twisty 12 + gap 8 + glyph 12 + gap 8 + 执行器
  // 图标 14 + gap 8（taskList.css .task-stats 注）。本钉 = 同线（±0.75px）。
  const boxes = await page.evaluate(() => {
    const card = document.querySelector('.task-card');
    const c = card.getBoundingClientRect();
    const rel = (el) => {
      const r = el.getBoundingClientRect();
      return { x: +(r.left - c.left).toFixed(1), y: +(r.top - c.top).toFixed(1) };
    };
    return {
      stats: rel(document.querySelector('.task-stats')),
      rowText: rel(document.querySelector('.task-node.task-delegate .task-node-text')),
      toggle: rel(document.querySelector('.task-toggle')),
    };
  });
  expect(Math.abs(boxes.stats.x - boxes.rowText.x)).toBeLessThanOrEqual(0.75);
  // 方向守卫：badge 文案必须仍在其 toggle 右侧（修的是列对齐，不是行序/换行）。
  expect(boxes.stats.x).toBeGreaterThan(boxes.toggle.x + 20);
  // 纵向序守卫：badge 行在任务行上方（两行盒模型 = 上下两行，不是同行元素）。
  expect(boxes.stats.y).toBeLessThan(boxes.rowText.y);

  expect(pageErrors).toEqual([]);
});

test('T14 delegate 生命周期触发器静态钉（e2e 断点①修 · 2026-10-07）：裸 delegate 在飞期 node* 帧族为零 ⇒ provider 把 turn 边界帧接进同一防抖刷', async () => {
  // 判据单源 = provider 源码静态钉（触发器面无渲染面，无 DOM 可断言）：
  //   ① turn 边界帧族（agentStart/agentDone/agentEnd）注册为触发器；
  //   ② 会话判据 = delegate-/external- 前缀（registry 写入的两类会话；
  //      subtask- 不在行源——BlockingSubagent 子会话不落 DelegateRegistry）；
  //   ③ 触发只进 scheduleRefresh 防抖（帧只当触发器，数据权威恒在 REST）。
  const provSrc = readFileSync(join(WEB, 'js', 'delegateChainProvider.js'), 'utf8');
  expect(provSrc).toContain("for (const evt of ['agentStart', 'agentDone', 'agentEnd'])");
  expect(provSrc).toContain('DELEGATE_TRIGGER_RE = /^(delegate|external)-/');
  expect(provSrc).toContain('function onDelegateLifecycle');
  // 同一防抖单缝：生命周期触发与 node* 帧族共用 scheduleRefresh（零第二刷新路径）。
  const refreshCalls = provSrc.match(/scheduleRefresh\(\)/g) || [];
  expect(refreshCalls.length).toBeGreaterThanOrEqual(2);
});
