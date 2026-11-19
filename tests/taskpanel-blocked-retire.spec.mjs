// taskpanel-blocked-retire.spec.mjs — 修复批 #566 验收（任务面板陈旧状态 + delegate
// 砍掉 blocked 语义）。作者令逐字：「delegate 是不应该有 blocked 型状态的，直接上报
// 就行了，blocked 是给 agentflow 用的」。
//
// 验收覆盖：
//   A1 伪造终态委托 → 行消失（completed/failed/cancelled 行经 ACTIVE 闸不渲染——
//      「终态委托的行必须在 UI 反映终态或按既有归档规则消失」的消失面）；
//   A2 行翻状态面：在飞行 → 引擎终态读数到达（taskStatus 权威）→ 下一个镜像帧行
//      退场（零「进行中」陈旧行滞留）；
//   A3 ready-to-merge 在窗（排队仍是现役工作）＋ archivedAt 行退场（既有归档规则，
//      #506 现役窗闸不回退的对照面）；
//   B1 退役 blocked 申报（reportStatus='blocked' 旧盘行）→ 行词「已完成」，零
//      「阻塞」徽章（zh-CN）；
//   B2 判词注记面：chain verdict='blocked'（判官「没找到合并」类注记行）→ 行状态
//      派生 completed，面板零 blocked 徽章——zh-CN 与 en 双语言键同验（已阻断/
//      Blocked 均不出现）；
//   B3 flow 成员（agentflow 节点）的 blocked 语义保留对照：flow-step 成员行仍渲染
//      阻塞词（blocked 只归 agentflow 节点——语义不误伤）。
//
// 数据面 = stub read-port 夹具（tasklist-chain-blocks.spec.mjs 同族 harness；零后端
// 零 sbt）。
// Run: node node_modules/@playwright/test/cli.js test tests/taskpanel-blocked-retire.spec.mjs

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

const ROOT_SID = 'tbr-root-session';
const T0 = Date.now();

async function bootApp(page, locale = 'zh-CN') {
  await page.addInitScript((loc) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', loc);
  }, locale);
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

/** Load stub fixtures and re-render the panel (harness seam = the mirror-refresh analogue). */
async function setFixture(page, fx) {
  await page.evaluate(async (f) => {
    const stub = await import('/js/delegateChainStub.js');
    stub.__setFixture(f);
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, 'tbr-root-session');
  }, fx);
  await page.waitForFunction(
    () => !document.querySelector('#task-list .task-node-entering'), null, { timeout: 5000 },
  ).catch(() => {});
}

/** Registry Record 夹具：经 rowFromRegistry 投影（provider 行投影同链）再渲染 ——
 *  适配层推导（taskStatus 权威 / 退役 blocked 申报折 completed）的渲染面直验。 */
async function setRegistryFixture(page, records) {
  await page.evaluate(async (recs) => {
    const stub = await import('/js/delegateChainStub.js');
    const rows = recs.map((r) => stub.rowFromRegistry(r));
    stub.__setFixture({ rows });
    const { renderTaskList } = await import('/js/taskList.js');
    renderTaskList([], undefined, 'tbr-root-session');
  }, records);
  await page.waitForFunction(
    () => !document.querySelector('#task-list .task-node-entering'), null, { timeout: 5000 },
  ).catch(() => {});
}

/** Delegate 根行夹具（status 列 = 适配层产物；夹具直供 rowFromRegistry 产形）。 */
function row(taskNo, delegateId, status, extra = {}) {
  return {
    taskNo, delegateId,
    title: extra.title || `任务 ${taskNo}`,
    executor: extra.executor || 'nebflow',
    status, external: false, project: '',
    createdAt: T0 - Number(taskNo) * 10_000,
    ...extra,
  };
}

test('A1+A3 伪造终态委托 → 行消失；ready-to-merge 在窗；archivedAt 行退场（#506 现役窗闸不回退）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  await setFixture(page, {
    rows: [
      row('71', 'delegate-nebflow-r71', 'running'),
      row('72', 'delegate-nebflow-c72', 'completed'),
      row('73', 'delegate-nebflow-f73', 'failed'),
      row('74', 'delegate-nebflow-x74', 'cancelled'),
      row('75', 'delegate-nebflow-a75', 'running', { archivedAt: T0 - 1000 }), // 已归档 = 历史窗
      row('76', 'delegate-nebflow-q76', 'ready-to-merge', {
        mergeQueuePos: { position: 1, total: 2, readyAt: T0 - 50_000, branch: 'delegate/task-76', baseBranch: 'main' },
      }),
    ],
  });

  const panel = page.locator('#task-list');
  // 现役窗只有 running + ready-to-merge 两行；终态三行 + 已归档一行全部退场。
  await expect(panel.locator('.task-chain-head')).toHaveCount(2);
  await expect(panel.locator('.task-chain-head[data-task-no="71"]')).toHaveCount(1);
  await expect(panel.locator('.task-chain-head[data-task-no="76"]')).toHaveCount(1);
  for (const gone of ['72', '73', '74', '75']) {
    await expect(panel.locator(`[data-task-no="${gone}"]`)).toHaveCount(0);
  }
  // 排队行 = 现役工作：名次 chip 在位（G5 引擎闸透传）。
  await expect(panel.locator('.task-chain-head[data-task-no="76"] .task-delegate-rank')).toHaveText('#1');

  expect(pageErrors).toEqual([]);
});

test('A2 行翻状态面：在飞行 → 引擎终态读数（taskStatus）到达 → 行退场，零「进行中」滞留', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page);
  // 帧一：在飞行（reportStatus/live 双缺席 = 旧 bug 的 running 回落形态）。
  await setFixture(page, { rows: [row('81', 'delegate-nebflow-s81', 'running')] });
  const panel = page.locator('#task-list');
  await expect(panel.locator('.task-chain-head[data-task-no="81"] .task-node-word')).toHaveText('进行中');

  // 帧二：镜像刷新携带引擎终态（taskStatus=failed 权威 → 适配层翻 failed → ACTIVE 闸退场）。
  await setFixture(page, {
    rows: [
      {
        ...row('81', 'delegate-nebflow-s81', 'failed'),
        archivedAt: null,
      },
    ],
  });
  await expect(panel.locator('.task-chain-head[data-task-no="81"]')).toHaveCount(0);
  // 面板整体收起（零行 = .has-tasks 缺席）。
  await expect(panel).not.toHaveClass(/has-tasks/);

  expect(pageErrors).toEqual([]);
});

test('B1 退役 blocked 申报（旧盘行）→ 派生 completed → 行退场，零「阻塞」徽章（zh-CN）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page, 'zh-CN');
  // reportStatus='blocked' 的旧盘申报行：经行投影链（Record → rowFromRegistry → 面板）
  // ——#566 起按 completed 交付（阻塞事实是报告注记）；终态行按既有规则退场。
  await setRegistryFixture(page, [
    {
      id: 'delegate-nebflow-b82', executor: 'nebflow', cwd: '/w', project: '', taskId: '82',
      parentSessionId: 's', title: '旧申报行', createdAt: T0 - 10_000,
      reportStatus: 'blocked', reportDetail: 'conflict — waiting',
    },
  ]);
  // 适配层推导钉：退役申报 → completed（无 blocked 产物）。
  const derived = await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    return stub.rowFromRegistry({
      id: 'delegate-nebflow-b82', executor: 'nebflow', cwd: '/w', project: '', taskId: '82',
      parentSessionId: 's', title: '旧申报行', createdAt: Date.now() - 10_000,
      reportStatus: 'blocked', reportDetail: 'conflict — waiting',
    }).status;
  });
  expect(derived).toBe('completed');
  // 渲染面：completed 行不在现役窗（行退场——「按既有归档规则消失」面）。
  const panel = page.locator('#task-list');
  await expect(panel.locator('.task-chain-head')).toHaveCount(0);
  const bodyText = await panel.textContent();
  expect(bodyText || '').not.toContain('阻塞');

  expect(pageErrors).toEqual([]);
});

test('B2 判词注记面（zh-CN）：chain verdict=blocked → 行派生 completed，面板零 blocked 徽章', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page, 'zh-CN');
  await setFixture(page, {
    rows: [row('83', 'delegate-nebflow-v83', 'running')],
    chains: [
      {
        taskId: '83', delegateId: 'delegate-nebflow-v83',
        nodes: [{ id: 'delegate-nebflow-v83', kind: 'delegate', label: '#83', description: '', status: 'blocked', deps: [], executor: '' }],
        worktree: null, plugins: [], allocation: '',
        // 判官注记行（退役判词——「没找到合并」类）：旧盘遗留，面板不得出 blocked 徽章。
        verdict: { verdict: 'blocked', reason: 'merge target not found on record', source: 'judge', at: T0 - 1000 },
        mergeQueue: [], frames: [],
      },
    ],
  });
  const panel = page.locator('#task-list');
  await expect(panel.locator('.task-chain-head')).toHaveCount(1);
  const bodyText = await panel.textContent();
  expect(bodyText).not.toContain('阻塞');

  // L1 投影单点同验：verdictToStatus('blocked') = completed（适配层归一钉）。
  const mapped = await page.evaluate(async () => {
    const stub = await import('/js/delegateChainStub.js');
    return stub.verdictToStatus('blocked');
  });
  expect(mapped).toBe('completed');

  expect(pageErrors).toEqual([]);
});

test('B2b 判词注记面（en）：零 "Blocked" 徽章——两语言键同验', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page, 'en');
  await setFixture(page, {
    rows: [row('84', 'delegate-nebflow-v84', 'running')],
    chains: [
      {
        taskId: '84', delegateId: 'delegate-nebflow-v84',
        nodes: [{ id: 'delegate-nebflow-v84', kind: 'delegate', label: '#84', description: '', status: 'blocked', deps: [], executor: '' }],
        worktree: null, plugins: [], allocation: '',
        verdict: { verdict: 'blocked', reason: 'merge target not found on record', source: 'judge', at: T0 - 1000 },
        mergeQueue: [], frames: [],
      },
    ],
  });
  const panel = page.locator('#task-list');
  await expect(panel.locator('.task-chain-head')).toHaveCount(1);
  const bodyText = await panel.textContent();
  expect(bodyText).not.toContain('Blocked');

  expect(pageErrors).toEqual([]);
});

test('B3 对照钉：agentflow flow 成员的 blocked 语义保留（blocked 只归 agentflow 节点）', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));

  await bootApp(page, 'zh-CN');
  await setFixture(page, {
    rows: [row('85', 'delegate-nebflow-w85', 'running')],
    chains: [
      {
        taskId: '85', delegateId: 'delegate-nebflow-w85',
        nodes: [
          { id: 'delegate-nebflow-w85', kind: 'delegate', label: '#85', description: '', status: 'running', deps: [], executor: '' },
          { id: 's1', kind: 'flow-step', label: '步骤一', description: '', status: 'blocked', deps: [], executor: '' },
        ],
        worktree: null, plugins: [], allocation: '',
        verdict: null, mergeQueue: [], frames: [],
      },
    ],
  });
  await page.locator('.task-chain-head[data-task-no="85"]').click(); // 展开
  await page.waitForTimeout(200);
  const panel = page.locator('#task-list');
  // flow 成员行（agentflow 节点）的阻塞词在位——语义保留面。
  await expect(panel.locator('.task-chain-member').filter({ hasText: '步骤一' }).locator('.task-chain-member-word'))
    .toHaveText('阻塞');

  expect(pageErrors).toEqual([]);
});
