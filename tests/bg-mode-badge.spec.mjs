// bg-mode-badge.spec.mjs — 任务模式标注批（2026-10-30 作者令「派发任务显示其执行
// 模式——极简内核（kernel）还是分发器（dispatcher），任务卡/后台任务面板加模式徽标」）
// 后台任务面板面验收：
//  ① backgroundTaskUpdate 帧带 ownerMode ⇒ 行内 mode chip 渲染（内核/分发器，
//    样式类 bg-mode-kernel / bg-mode-dispatcher）；
//  ② ownerMode 缺席（旧后端/非 delegate/双模式闸关）⇒ 零渲染（行高不膨胀）；
//  ③ activeBgTasks 快照帧（刷新恢复路径）ownerMode 同行下发 ⇒ chip 同样渲染。
//
// 数据面 = WS 夹具（backgroundTaskUpdate / activeBgTasks 直推），零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/bg-mode-badge.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '.nebflow', 'evidence', '20261030_todo-split', 'shots');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'bg-mode-root';

test.use({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  let live = null;
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
    live = ws;
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
  return {
    send: (msg) => live?.send(JSON.stringify(msg)),
  };
}

/** 行数据面：kernel delegate 任务 / dispatcher delegate 任务 / 无 ownerMode 旧形态。 */
const LIVE_TASKS = [
  { taskId: 'j1', ownerMode: 'kernel', description: 'kernel delegate 的构建', kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
  { taskId: 'j2', ownerMode: 'dispatcher', description: 'dispatcher delegate 的测试', kind: 'local', origin: 'node', originLabel: 'scan' },
  { taskId: 'j3', ownerMode: '', description: '旧形态后台任务', kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
];

async function injectLive(harness) {
  for (const t of LIVE_TASKS) {
    harness.send({
      type: 'backgroundTaskUpdate',
      sessionId: SID,
      rootSessionId: SID,
      taskId: t.taskId,
      description: t.description,
      status: 'running',
      startedAt: Date.now(),
      kind: t.kind,
      origin: t.origin,
      originLabel: t.originLabel,
      ownerMode: t.ownerMode,
    });
  }
}

async function openBgDropdown(page) {
  await page.locator('#bg-indicator').click();
  await expect(page.locator('#bg-dropdown')).toBeVisible();
}

test('①② 实时帧：ownerMode ⇒ mode chip（内核/分发器样式类）；缺席 ⇒ 零渲染', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const harness = await bootApp(page);
  await injectLive(harness);
  await page.waitForTimeout(400);
  await openBgDropdown(page);

  const chip1 = page.locator('.bg-task-row[data-task-id="j1"] .bg-task-mode');
  await expect(chip1).toHaveText('内核');
  await expect(chip1).toHaveClass(/bg-mode-kernel/);
  const chip2 = page.locator('.bg-task-row[data-task-id="j2"] .bg-task-mode');
  await expect(chip2).toHaveText('分发器');
  await expect(chip2).toHaveClass(/bg-mode-dispatcher/);
  // 旧形态行：mode chip 零渲染。
  await expect(page.locator('.bg-task-row[data-task-id="j3"] .bg-task-mode')).toHaveCount(0);
  await page.screenshot({ path: join(SHOT_DIR, '04-bg-mode-chips.png') });

  expect(errors).toEqual([]);
});

test('③ 快照帧（activeBgTasks 刷新恢复路径）：ownerMode 同行下发 ⇒ chip 渲染', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const harness = await bootApp(page);
  const snapshotTasks = LIVE_TASKS.map((t) => ({
    taskId: t.taskId,
    description: t.description,
    status: 'running',
    startedAt: Date.now(),
    kind: t.kind,
    origin: t.origin,
    originLabel: t.originLabel,
    ownerMode: t.ownerMode,
    sessionId: SID,
  }));
  harness.send({ type: 'activeBgTasks', tasks: { [SID]: snapshotTasks } });
  await page.waitForTimeout(400);
  await openBgDropdown(page);

  await expect(page.locator('.bg-task-row[data-task-id="j1"] .bg-task-mode')).toHaveText('内核');
  await expect(page.locator('.bg-task-row[data-task-id="j2"] .bg-task-mode')).toHaveText('分发器');
  await expect(page.locator('.bg-task-row[data-task-id="j3"] .bg-task-mode')).toHaveCount(0);

  expect(errors).toEqual([]);
});
