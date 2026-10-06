// delegate-chain-stub.spec.mjs — ⑤A stub 接口＋taskList delegate 行骨架验收
//（#1228 重划版提前开工批 · 可落地段）。
//
// 覆盖面：
//   T1 默认零变化：stub 无夹具 ⇒ delegate 区块零渲染，面板空态收起行为与改前同
//   T2 骨架行渲染：fixture 注入 ⇒ task#N＋执行器图标＋状态 glyph/状态词；
//      ready-to-merge 状态词留空（零新增 locale 键纪律的可执行证据）；
//      外部行 data-external="1"（S16 粗粒度标记）
//   T3 混合面板：delegate 区块先于节点区块；统计仍节点口径（骨架批不改统计面）
//   T4 适配层单测（页面内纯函数）：rowFromRegistry / chainFromStoreRecord /
//      mergeQueueFifo（S11 排序键=完成时间 readyAt ↑，非插入序）＋常量钉死
//   T5 读取口 null 安全：查无链 → null；行清单默认空
//
// 事件帧/面板驱动与 tasklist-nodes.spec.mjs 同 rig（真实 ws.js 分发路径注入）。
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

test('T2 骨架行渲染：task#N＋执行器图标＋状态 glyph/词；ready-to-merge 词留空；外部行 coarse 标记', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [row31, row32, row33] });

  const panel = page.locator('#task-list');
  await expect(panel).toHaveClass(/has-tasks/);
  await expect(panel.locator('.task-section-delegates')).toHaveCount(1);
  await expect(panel.locator('.task-delegate')).toHaveCount(3);

  // #31 nebflow running：编号、标题、图标、绿转圈、状态词=既有键（task.inProgressShort）。
  const r31 = panel.locator('.task-delegate[data-task-no="31"]');
  await expect(r31.locator('.task-delegate-no')).toHaveText('#31');
  await expect(r31.locator('.task-node-label')).toHaveText('重构任务面板数据源');
  await expect(r31.locator('.executor-icon-nebflow')).toHaveCount(1);
  await expect(r31.locator('.task-node-spin')).toHaveCount(1);
  await expect(r31.locator('.task-node-word')).toHaveText('进行中');
  await expect(r31).not.toHaveAttribute('data-external');
  await expect(r31).toHaveAttribute('data-delegate-id', 'delegate-nebflow-aa11');

  // #32 zcode blocked（外部）：琥珀点 + 阻塞（既有键 task.nodeBlocked）+ coarse 标记。
  const r32 = panel.locator('.task-delegate[data-task-no="32"]');
  await expect(r32.locator('.executor-icon-zcode')).toHaveCount(1);
  await expect(r32.locator('.task-node-dot-blocked')).toHaveCount(1);
  await expect(r32.locator('.task-node-word')).toHaveText('阻塞');
  await expect(r32).toHaveAttribute('data-external', '1');

  // #33 ready-to-merge：🔴 零新增 locale 键纪律 ⇒ 状态词留空（候 §16 文案过目），
  // glyph 中性降级（未知态同族），行本身照常渲染不崩。
  const r33 = panel.locator('.task-delegate[data-task-no="33"]');
  await expect(r33).toHaveCount(1);
  await expect(r33).toHaveAttribute('data-delegate-status', 'ready-to-merge');
  await expect(r33.locator('.task-node-word')).toHaveCount(0);
  await expect(r33.locator('.task-node-dot-neutral')).toHaveCount(1);
  await expect(r33).toHaveAttribute('data-external', '1');

  expect(pageErrors).toEqual([]);
});

test('T3 混合面板：delegate 区块先于节点区块；统计仍节点口径（骨架批不改统计面）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, { rows: [row31, row32] });
  await inject(page, {
    type: 'nodeCreated', project: 'alpha', nodeId: 'n-run',
    node: { hasWorktree: false, worktree: null, result: null, retries: 0, id: 'n-run', name: 'scan-repo', agent: 'Backend', status: 'running', createdAt: T0, startedAt: T0, completedAt: null, ttlLeftSec: null },
  });

  const panel = page.locator('#task-list');
  await expect(panel.locator('.task-delegate')).toHaveCount(2);
  await expect(panel.locator('.task-chain, .task-node:not(.task-delegate)')).toHaveCount(1);

  // 顺序：delegate 区块在前（S1 delegate 任务 = 面板主行）。
  const order = await panel.locator('.task-body-inner > *').evaluateAll((els) => els.map((e) => e.className));
  const dIdx = order.findIndex((c) => c.includes('task-section-delegates'));
  const nIdx = order.findIndex((c) => c.includes('task-section-nodes'));
  expect(dIdx).toBeGreaterThanOrEqual(0);
  expect(nIdx).toBeGreaterThan(dIdx);

  // 统计仍节点口径（骨架批纪律：统计面零改动）。
  await expect(panel.locator('.task-stats')).toHaveText('1 任务');

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
