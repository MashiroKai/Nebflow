// bg-task-output-viewer.spec.mjs — 后台任务输出查看补全批验收
//（作者令 2026-10-25「这个截图是我们后台任务的进展查看面板，现在就是远端任务
// 看不到，本地任务细节好像也看不到」· 详情面板输出区断言）
//
// 契约（backend = PresenceRoutes /bg-tasks/:id/remote-output 按需拉对端日志尾窗 +
// BgTaskOutputStore.fillRemote 终态灌账 + bg-task-logs/ 落盘；本 spec 用路由桩
// 模拟设备通道面——REST 面即设备通道的服务端，桩设备无需真实对端）：
//   · 远端运行中任务：详情卡轮询 remote-output → 输出区 = 对端日志尾窗真实内容
//     （不再「远端任务暂不支持输出查看」），subtitle 带 outputPath，运行态光标在；
//   · 无 outputPath（命令无重定向）：占位 = 真实原因（「输出重定向」），且不再
//     出现「暂不支持」措辞；永久态停止轮询（只打一拍）；
//   · 远端任务完成（remote-output 404 → 引擎注销 + 灌账）：回退 /output 单拍取
//     终态留存——输出内容 + exit chip + 终态 label；
//   · 本地任务回归：offset 游标增量轮询逐拍追加（既有行为零漂移）。
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/bg-task-output-viewer.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/svg+xml', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'bgout-root-session';
const T0 = Date.now();

function bgTasksFixture() {
  return { [SID]: [
    // 行 1：远端运行中（带 outputPath 语义——路由桩按 jobId 分流）。
    { taskId: 'job-remote-live', description: '[KAI] KAI 全量 test5', status: 'running', startedAt: T0 - 60_000, sessionId: SID, kind: 'remote', origin: 'device', originLabel: 'KAI' },
    // 行 2：远端运行中，但命令无输出重定向（noOutputPath 臂）。
    { taskId: 'job-remote-nopath', description: '[KAI] interactive cmd', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'remote', origin: 'device', originLabel: 'KAI' },
    // 行 3：远端已完成（回看臂：remote-output 404 → /output 终态留存）。
    { taskId: 'job-remote-done', description: '[KAI] finished build', status: 'completed', startedAt: T0 - 90_000, sessionId: SID, kind: 'remote', origin: 'device', originLabel: 'KAI' },
    // 行 4：本地运行中（回归臂：offset 增量轮询）。
    { taskId: 'job-local-live', description: '本地构建任务', status: 'running', startedAt: T0 - 45_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
  ] };
}

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
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
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
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.send(JSON.stringify({ type: 'activeBgTasks', tasks: bgTasksFixture() }));
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
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

async function openCard(page, taskId) {
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const row = page.locator('#bg-dropdown > .bg-dropdown-list').first()
    .locator(`.bg-task-row[data-task-id="${taskId}"]`);
  await expect(row).toHaveCount(1);
  await row.click();
  const card = page.locator('.bgt-overlay');
  await expect(card).toBeVisible();
  return card;
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 remote running task: card shows the pulled log tail (placeholder gone, path in subtitle, running cursor)', async ({ page }) => {
  const errors = watchErrors(page);
  let pulls = 0;
  // 测试桩「设备通道」：remote-output 面返回对端日志尾窗（引擎侧行为的桩形态）。
  await page.route('**/api/bg-tasks/job-remote-live/remote-output', (route) => {
    pulls += 1;
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        taskId: 'job-remote-live', status: 'running', available: true,
        output: 'win test5 line 1\nwin test5 line 2\nwin test5 line 3',
        tailBytes: 51, truncated: false,
        deviceName: 'KAI', outputPath: '/c/Users/Kai/stand-win-test5.log',
      }),
    });
  });
  const card = await openCard(page, 'job-remote-live');
  // 输出区 = 对端日志尾窗真实内容（不再是「远端任务暂不支持输出查看」）。
  await expect(card.locator('.bgt-output')).toContainText('win test5 line 3', { timeout: 5000 });
  await expect(card.locator('.bgt-placeholder')).toBeHidden();
  const body = card.locator('.bgt-output');
  await expect(body).not.toContainText('暂不支持');
  // 运行态光标（终端语言）仍在。
  await expect(body).toHaveClass(/bgt-running/);
  // subtitle/meta 带 outputPath（记录路径语义）。
  await expect(card.locator('.bgt-subtitle')).toContainText('/c/Users/Kai/stand-win-test5.log');
  await expect(card.locator('.bgt-meta-text')).toContainText('/c/Users/Kai/stand-win-test5.log');
  await expect(card.locator('.bgt-meta-text')).toContainText('已运行');
  // 轮询在续（3s 节拍）——运行中至少两拍。
  await page.waitForTimeout(6500);
  expect(pulls).toBeGreaterThanOrEqual(2);
  expect(errors).toEqual([]);
});

test('T2 remote no output path: placeholder = real reason (redirect hint), no 「暂不支持」, polling stops after first beat', async ({ page }) => {
  const errors = watchErrors(page);
  let pulls = 0;
  await page.route('**/api/bg-tasks/job-remote-nopath/remote-output', (route) => {
    pulls += 1;
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        taskId: 'job-remote-nopath', status: 'running', available: false,
        reason: 'no output file detected in the command (needs a redirect like `> run.log 2>&1`)',
        reasonKind: 'noOutputPath', deviceName: 'KAI', outputPath: '',
      }),
    });
  });
  const card = await openCard(page, 'job-remote-nopath');
  const ph = card.locator('.bgt-placeholder');
  await expect(ph).toBeVisible();
  await expect(ph).toContainText('输出重定向');
  await expect(ph).not.toContainText('暂不支持');
  await page.waitForTimeout(7000);
  expect(pulls).toBe(1); // 永久态：首拍后停轮询（不再空转打设备）
  expect(errors).toEqual([]);
});

test('T3 remote finished task: remote-output 404 → /output terminal fill (content + exit chip + done label)', async ({ page }) => {
  const errors = watchErrors(page);
  await page.route('**/api/bg-tasks/job-remote-done/remote-output', (route) =>
    route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not running (finished)' }) }));
  await page.route('**/api/bg-tasks/job-remote-done/output*', (route) =>
    route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        taskId: 'job-remote-done', status: 'completed', totalBytes: 80, totalLines: 4,
        truncated: false, output: 'build ok\nall tests passed', nextOffset: 80,
        finishedAtMs: T0, exitCode: 0, errorHint: null,
        kind: 'remote', deviceName: 'KAI', outputPath: '/c/Users/Kai/stand-win-build.log',
        startedAtMs: T0 - 90_000, description: '[KAI] finished build',
      }),
    }));
  const card = await openCard(page, 'job-remote-done');
  await expect(card.locator('.bgt-output')).toContainText('all tests passed', { timeout: 5000 });
  await expect(card.locator('.bgt-exit-label')).toHaveText('exit 0');
  await expect(card.locator('.bgt-state-label')).toContainText('已完成');
  // 终态收口后运行态光标撤下。
  await expect(card.locator('.bgt-output')).not.toHaveClass(/bgt-running/);
  expect(errors).toEqual([]);
});

test('T4 local task regression: offset-cursor incremental polling still appends beat by beat', async ({ page }) => {
  const errors = watchErrors(page);
  let beat = 0;
  await page.route('**/api/bg-tasks/job-local-live/output*', (route) => {
    beat += 1;
    const b = beat === 1
      ? { output: 'compile step 1', nextOffset: 15, status: 'running', totalLines: 1, totalBytes: 15, truncated: false, exitCode: null, kind: 'local' }
      : { output: 'compile step 2', nextOffset: 30, status: 'running', totalLines: 2, totalBytes: 30, truncated: false, exitCode: null, kind: 'local' };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
  });
  const card = await openCard(page, 'job-local-live');
  await expect(card.locator('.bgt-output')).toContainText('compile step 1', { timeout: 5000 });
  // 第二拍（2s 轮询）增量追加——既有游标协议零漂移。
  await expect(card.locator('.bgt-output')).toContainText('compile step 1\ncompile step 2', { timeout: 6000 });
  expect(errors).toEqual([]);
});
