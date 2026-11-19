// Hierarchy fixture regressions. These do not substitute for a real gateway test.
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };

const ROOT_SID = 'probe-root-session';
const KERNEL_SID = 'delegate-nebflow-94ce9d8e';
const SUBAGENT_SID = 'subagent-9c3d5c8a';
const NESTED_DELEGATE_SID = 'delegate-nebflow-aaaa1111';
const DISP_SID = 'dispatcher-probe0001';
const NODE_SID = 'node-probe0002';
const NODE_SUBAGENT_SID = 'subagent-57d09085';
const T0 = Date.now();

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function inject(page, obj) {
  return page.evaluate(async (o) => { const s = (await import('/js/state.js')).default; s.ws.onmessage({ data: JSON.stringify(o) }); }, obj);
}
async function setFixture(page, fx) {
  await page.evaluate(async (f) => { const stub = await import('/js/delegateChainStub.js'); stub.__setFixture(f); }, fx);
}
async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  await page.click('#bgagent-indicator');
  await page.waitForSelector('#bg-dropdown:not(.hidden)', { timeout: 8000 });
  await page.waitForTimeout(250);
}
const LIST = '#bg-dropdown .bg-section-agents .bg-dropdown-list';
function dump(page) {
  return page.evaluate((sel) => {
    const list = document.querySelector(sel);
    if (!list) return [];
    return [...list.children].map((el) => ({
      cls: el.className,
      level: el.getAttribute('data-bg-row-level') || '',
      depth: el.getAttribute('data-bg-depth'),
      sid: el.getAttribute('data-node-session-id') || el.getAttribute('data-child-session') || '',
      kindText: [...el.querySelectorAll('.bg-task-kind')].map((x) => x.textContent).join('|'),
      rail: (el.querySelector('.bg-tree-rail') || {}).textContent || '',
      level2: el.getAttribute('aria-level'),
      addr: [...el.querySelectorAll('.bg-task-addr')].map((x) => x.getAttribute('data-bg-copy')).join('|'),
      name: (el.querySelector('.bg-task-name') || {}).textContent || '',
      orphan: el.getAttribute('data-bg-orphan'),
      absent: el.getAttribute('data-bg-parent-absent'),
      pad: getComputedStyle(el).paddingLeft,
    }));
  }, LIST);
}

test('scope child request failures are throttled and can retry', async ({ page }) => {
  await bootApp(page);
  let requests = 0;
  await page.route('**/api/session-children/node-retry-test', route => {
    requests++;
    return route.abort();
  });
  const result = await page.evaluate(async () => {
    const { ensureChildren } = await import('/js/delegateChainProvider.js');
    await ensureChildren('node-retry-test');
    await ensureChildren('node-retry-test');
    await ensureChildren('node-retry-test', 0);
    return true;
  });
  expect(result).toBe(true);
  expect(requests).toBe(2);
});

test('probe A: kernel + subagent child (real snapshot shapes)', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await bootApp(page);
  await setFixture(page, {
    rows: [{ taskNo: '1825', delegateId: KERNEL_SID, title: '用户要求：「帮我审计一下agentflow的节点」', name: '节点卡点审计', executor: 'nebflow', status: 'running', external: false, project: '', createdAt: T0 - 26 * 60_000, orchestrationMode: 'subagent-star', mode: 'kernel', archivedAt: null }],
    children: { [KERNEL_SID]: [{ taskId: SUBAGENT_SID, parentSessionId: KERNEL_SID, agentName: 'subagent', description: 'Node-stall test coverage inventory', status: 'running', source: 'subagent', spawnedAt: T0 - 7 * 60_000, retryCount: 0 }] },
  });
  await inject(page, { type: 'activeAgents', agents: [
    { sessionId: KERNEL_SID, agentId: KERNEL_SID, rootSessionId: ROOT_SID, parentSessionId: ROOT_SID, kind: 'Delegate', agentName: '节点卡点审计', delegateName: '节点卡点审计', mailAddress: 'delegate:' + KERNEL_SID, status: 'Processing', startedAt: T0 - 26 * 60_000, task: '用户要求：「帮我审计一下agentflow的节点」' },
    // 真实形状：BlockingSubagent 注册 kind=SubTask，会话 id 前缀 subagent-
    { sessionId: SUBAGENT_SID, agentId: SUBAGENT_SID, rootSessionId: KERNEL_SID, parentSessionId: KERNEL_SID, kind: 'SubTask', agentName: 'subagent', status: 'Processing', startedAt: T0 - 7 * 60_000, task: 'Node-stall test coverage inventory' },
    { sessionId: NESTED_DELEGATE_SID, agentId: NESTED_DELEGATE_SID, rootSessionId: KERNEL_SID, parentSessionId: KERNEL_SID, kind: 'Delegate', agentName: '子批', delegateName: '子批', mailAddress: 'delegate:' + NESTED_DELEGATE_SID, status: 'Processing', startedAt: T0 - 6 * 60_000 },
  ] });
  await openPanel(page);
  const rows = await dump(page);
  expect(rows.filter(r => r.sid === SUBAGENT_SID)).toHaveLength(1);
  expect(rows.find(r => r.sid === SUBAGENT_SID)).toMatchObject({ depth: '1', kindText: 'subagent', rail: '├─' });
  expect(rows.find(r => r.sid === NESTED_DELEGATE_SID)).toMatchObject({ depth: '1', kindText: 'delegate', rail: '└─' });
});

test('probe B: dispatcher -> node -> node subagent', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await bootApp(page);
  await setFixture(page, { rows: [] });
  await inject(page, { type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID, agentId: DISP_SID, nodeSessionId: DISP_SID, name: 'dispatcher/Nebflow', taskDescription: '分发器', parentSessionId: ROOT_SID, status: 'Processing' });
  await inject(page, { type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID, agentId: NODE_SID, nodeSessionId: NODE_SID, name: 'stand', taskDescription: '节点', parentSessionId: DISP_SID, status: 'Processing' });
  await inject(page, { type: 'agentStart', sessionId: ROOT_SID, rootSessionId: ROOT_SID, agentId: NODE_SUBAGENT_SID, nodeSessionId: NODE_SUBAGENT_SID, name: 'subagent', taskDescription: '节点派生的侦察', parentSessionId: NODE_SID, status: 'Processing' });
  await openPanel(page);
  const live = await dump(page);
  expect(live.find(r => r.sid === DISP_SID)).toMatchObject({ depth: '1', rail: '└─' });
  expect(live.find(r => r.sid === NODE_SID)).toMatchObject({ depth: '2', rail: '└─' });
  expect(live.find(r => r.sid === NODE_SUBAGENT_SID)).toMatchObject({ depth: '3', kindText: 'subagent', rail: '└─' });

  // store-only（终态：live 行离场，磁盘 store 仍有一条 source=subagent 记录）
  await setFixture(page, {
    rows: [],
    children: { [NODE_SID]: [{ taskId: 'subagent-deadbeef', parentSessionId: NODE_SID, agentName: 'subagent', description: '终态侦察', status: 'completed', source: 'subagent', spawnedAt: T0 - 30 * 60_000, retryCount: 0 }] },
  });
  await page.evaluate(async () => { (await import('/js/main/bgAgents.js')).renderBgAgentDropdown(); });
  const history = await dump(page);
  expect(history.filter(r => r.sid === 'subagent-deadbeef')).toHaveLength(1);
  expect(history.find(r => r.sid === 'subagent-deadbeef')).toMatchObject({ depth: '3', kindText: 'subagent', rail: '└─' });
  // A scoped store row must expose its cancelled state and open its history.
  await setFixture(page, {
    rows: [],
    children: { [NODE_SID]: [{ taskId: 'subagent-deadbeef', parentSessionId: NODE_SID, agentName: 'subagent', description: '终态侦察', status: 'cancelled', source: 'subagent' }] },
  });
  await page.evaluate(async () => { (await import('/js/main/bgAgents.js')).renderBgAgentDropdown(); });
  const storedRow = page.locator('[data-child-session="subagent-deadbeef"]');
  await expect(storedRow.locator('.bg-task-state')).toHaveText('已取消');
  await storedRow.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#bg-dropdown')).toHaveClass(/hidden/);
  // Close the details and reopen the panel for the remaining checks.
  await page.click('#bgagent-close');
  await page.click('#bgagent-indicator');
  for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.evaluate(t => document.documentElement.setAttribute('data-theme', t), theme);
    const metrics = await page.locator(`[data-child-session="subagent-deadbeef"]`).evaluate(el => ({
      width: el.getBoundingClientRect().width,
      nameWidth: el.querySelector('.bg-task-name').getBoundingClientRect().width,
      railColor: getComputedStyle(el.querySelector('.bg-tree-rail')).color,
    }));
    expect(metrics.width).toBeGreaterThan(0);
    expect(metrics.nameWidth).toBeGreaterThan(0);
    expect(metrics.railColor).not.toBe('rgba(0, 0, 0, 0)');
  }
  await page.locator(`[data-bg-caret="${NODE_SID}"]`).click();
  expect((await dump(page)).some(r => r.sid === 'subagent-deadbeef')).toBe(false);
});
