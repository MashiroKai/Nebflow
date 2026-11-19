// agentcontrol-address-chip.spec.mjs — AgentControl name/address 批验收（2026-11-05）
//
// 作者令（2026-11-05 11:15 逐字）：「我觉得你的agentcontrol工具应该要能返回在跑的
// delegate 的name 和地址，方便你讲任务投递到相关的delegate」——工具面在
// AgentControlNameAddressSpec（Scala）里钉，**面板面**在这里钉。
//
// 契约（后端 = MailAddress 单点推导，快照 activeAgentEntryJson 与 live agentStart
// 两条路径同字段 `mailAddress`）：
//   · 行内地址 chip `.bg-task-addr` 的**唯一**数据源 = info.mailAddress（前端零推导）
//     ⇒ 夹具直接给字段，断言渲染出的 chip 文本与 data-bg-copy **全文一致**；
//   · 视觉可截断、复制恒全文：长地址 chip 的 ellipsis 只是版式，data-bg-copy 是
//     可粘贴令牌（title 也带全文）；
//   · 无 Mail 面的行（mailAddress 缺失/空串）零 chip——不编造地址；
//   · 点击 chip 复制 + 停冒泡（不打开详情弹窗）。
//
// 数据面 = WS activeAgents 快照直种，零后端、零 sbt、零端口（静态文件 + mock WS）。
// Run: node node_modules/@playwright/test/cli.js test tests/agentcontrol-address-chip.spec.mjs

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

const ROOT_SID = 'addr-root-session';
const T0 = Date.now();

const DEL_SID = 'delegate-Explorer-aaaa1111';
const KER_SID = 'delegate-kernel-1f2e3d4c';
const NODE_SID = 'node-819';
const TEAM_SID = 'team-nebflow-project-Backend';

/** 快照行（形状 = WebSocketRoutes.activeAgentEntryJson 的真实出帧字段子集）。 */
function agentRow(over) {
  return {
    sessionId: over.agentId || over.sessionId,
    agentId: over.sessionId,
    agentName: 'Explorer',
    kind: 'Delegate',
    status: 'Processing',
    startedAt: T0 - 60_000,
    retryCount: 0,
    // 归桶键：面板按 rootSessionId 分桶（缺省会落到自己的 sid 桶 → 主会话桶空）
    rootSessionId: ROOT_SID,
    parentSessionId: ROOT_SID,
    delegateName: '',
    mailAddress: '',
    project: '',
    task: '',
    taskId: '',
    taskTitle: '',
    heldReceipts: 0,
    unreadReceipts: 0,
    outstandingSubagents: 0,
    bgWaiting: [],
    bgWaitingCount: 0,
    lastActivityMs: T0 - 5_000,
    currentTool: '',
    ...over,
  };
}

const ROWS = [
  // Delegate 批次：地址 = delegate:<sessionId>，短名 = 批次名（面板行首显示的就是它）
  agentRow({ sessionId: DEL_SID, agentName: 'Explorer', delegateName: 'alpha', mailAddress: `delegate:${DEL_SID}` }),
  // kernel 实例：地址形态换成 kernel:<id>（同一 Mail 腿的精确形）
  agentRow({ sessionId: KER_SID, agentName: 'kernel', delegateName: 'kern', mailAddress: `kernel:${KER_SID}` }),
  // Project 节点会话：node:<节点名>
  agentRow({ sessionId: NODE_SID, agentName: 'worker-1', kind: 'Flow', project: 'stand', mailAddress: 'node:worker-1' }),
  // 无 Mail 面的行（Team）：mailAddress 缺省 ⇒ 零 chip
  agentRow({ sessionId: TEAM_SID, agentName: 'Backend', kind: 'Team', delegateName: '' }),
];

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
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], activeId: ROOT_SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ name: 'Nebula', displayName: 'Spider' }) });
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
      selfDevice: { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro' },
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.send(JSON.stringify({ type: 'activeAgents', agents: ROWS }));
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
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

/** 打开后台面板（合并载体 #bg-dropdown；子智能体行居 .bg-section-agents）。 */
async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
}

function rowFor(page, sid) {
  return page.locator('#bg-dropdown .bg-section-agents .bg-dropdown-list')
    .locator(`.bg-task-row[data-node-session-id="${sid}"]`);
}

test.beforeEach(async ({ page }) => {
  await bootContext(page);
});

/** 剪贴板读权限（复制断言用；只在本 spec 内申请）。 */
async function bootContext(page) {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin: 'http://localhost:1' });
  await bootApp(page);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test('T1 Delegate row: address chip = delegate:<id>, full value in data-bg-copy + title, batch name still first', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const row = rowFor(page, DEL_SID);
  await expect(row).toHaveCount(1);
  const chip = row.locator('.bg-task-addr');
  await expect(chip).toHaveCount(1);
  await expect(chip).toHaveText(`delegate:${DEL_SID}`);
  await expect(chip).toHaveAttribute('data-bg-copy', `delegate:${DEL_SID}`);
  await expect(chip).toHaveAttribute('title', new RegExp(`delegate:${DEL_SID}`));
  // name 与地址同一行（作者令：一眼看到是谁 + 往哪儿投）
  await expect(row.locator('.bg-task-name')).toHaveText('alpha');
  expect(errors).toEqual([]);
});

test('T2 kernel row: chip keeps the kernel:<id> form (not delegate:)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const chip = rowFor(page, KER_SID).locator('.bg-task-addr');
  await expect(chip).toHaveText(`kernel:${KER_SID}`);
  await expect(chip).toHaveAttribute('data-bg-copy', `kernel:${KER_SID}`);
  expect(errors).toEqual([]);
});

test('T3 Project node row: chip = node:<nodeName>', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const chip = rowFor(page, NODE_SID).locator('.bg-task-addr');
  await expect(chip).toHaveCount(1);
  await expect(chip).toHaveText('node:worker-1');
  expect(errors).toEqual([]);
});

test('T4 row without a Mail face (Team): zero chip — addresses are never invented', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const row = rowFor(page, TEAM_SID);
  await expect(row).toHaveCount(1);
  await expect(row.locator('.bg-task-addr')).toHaveCount(0);
  expect(errors).toEqual([]);
});

test('T5 clicking the chip copies the FULL address and does not open the detail popup', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const chip = rowFor(page, DEL_SID).locator('.bg-task-addr');
  await chip.click();
  // 停冒泡：行体点击的详情弹窗不得打开（弹窗 = body 下的 .flow-agent-overlay）
  await expect(page.locator('.flow-agent-overlay')).toHaveCount(0);
  const clip = await page.evaluate(() => navigator.clipboard.readText());
  expect(clip).toBe(`delegate:${DEL_SID}`);
  await expect(chip).toHaveClass(/is-copied/);
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  expect(errors).toEqual([]);
});
