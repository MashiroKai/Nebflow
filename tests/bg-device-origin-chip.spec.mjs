// bg-device-origin-chip.spec.mjs — 多设备后台任务可见批验收
//（作者令 2026-10-25「这里有个问题就是多设备的后台任务我们也要可见」· 需求⑤前端面）
//
// 契约（backend = BgTaskRegistry.deviceOrigin 单点 / RemoteExecutor 注册与 WS
// 发射面同源；快照 activeBgTasks 与实时 backgroundTaskUpdate 两条路径同字段）：
//   · 设备通道后台任务（Bash device=KAI + run_in_background）的 origin chip =
//     设备名（如「KAI」），类 .bg-origin-device，title 用独立词「跑在 KAI」
//     （bg.runsOn——「跑在哪」轴 ≠ nebula/dispatcher/node 的「谁开启」轴）；
//   · 与子代理/本地任务芯片并列可辨：同一行内 origin chip（设备名）+ kind chip
//     （远程）并存，样式沿用既有中性 chip 族（无新色令牌）；
//   · 旧后端快照回退：origin 字段缺席时从描述 `[<设备>] ` 前缀提取设备名
//     （RemoteExecutor 描述形态），兜底 'device'；
//   · 对照行：nebula 行 chip 逐字回归（Nebula / 由 Nebula 开启）。
//
// 数据面 = WS activeBgTasks 快照直种，零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/bg-device-origin-chip.spec.mjs

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

const SID = 'devchip-root-session';
const T0 = Date.now();

function bgTasksFixture() {
  return { [SID]: [
    // 行 1：新后端设备任务（origin=device + originLabel=KAI + kind=remote）。
    { taskId: 'job-kai', description: '[KAI] sbt full test', status: 'running', startedAt: T0 - 60_000, sessionId: SID, kind: 'remote', origin: 'device', originLabel: 'KAI' },
    // 行 2：旧后端快照形态（无 origin 字段；描述带 `[KAI] ` 前缀）→ 前缀回退。
    { taskId: 'job-legacy', description: '[DESKTOP-IJBBEOT] npm run dev', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'remote' },
    // 行 3：对照行 —— nebula 类别 chip 逐字回归（本地任务）。
    { taskId: 'job-local', description: '本地构建任务', status: 'running', startedAt: T0 - 90_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 5_000, outputLines: 42 } },
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

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 device-origin row: chip = device name, bg-origin-device class, 「跑在」title, remote kind chip', async ({ page }) => {
  const errors = watchErrors(page);
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const row = page.locator('#bg-dropdown > .bg-dropdown-list').first()
    .locator('.bg-task-row[data-task-id="job-kai"]');
  await expect(row).toHaveCount(1);
  const chip = row.locator('.bg-task-origin');
  await expect(chip).toHaveText('KAI');
  await expect(chip).toHaveClass(/bg-origin-device/);
  // title 用独立词（bg.runsOn）——设备 chip 回答「跑在哪」，非「谁开启」。
  await expect(chip).toHaveAttribute('title', '跑在 KAI');
  // 与 kind chip 并列可辨：同一行内 origin chip（设备名）+ kind chip（远程）。
  await expect(row.locator('.bg-task-kind')).toHaveText('远程');
  expect(errors).toEqual([]);
});

test('T2 legacy snapshot fallback: no origin fields → device name extracted from "[KAI] " description prefix', async ({ page }) => {
  const errors = watchErrors(page);
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const row = page.locator('#bg-dropdown > .bg-dropdown-list').first()
    .locator('.bg-task-row[data-task-id="job-legacy"]');
  await expect(row).toHaveCount(1);
  const chip = row.locator('.bg-task-origin');
  await expect(chip).toHaveText('DESKTOP-IJBBEOT');
  await expect(chip).toHaveClass(/bg-origin-device/);
  expect(errors).toEqual([]);
});

test('T3 control: nebula-origin row chip unchanged (Started-by semantics intact)', async ({ page }) => {
  const errors = watchErrors(page);
  await page.click('#bg-indicator');
  await expect(page.locator('#bg-dropdown')).toBeVisible();
  const row = page.locator('#bg-dropdown > .bg-dropdown-list').first()
    .locator('.bg-task-row[data-task-id="job-local"]');
  await expect(row).toHaveCount(1);
  const chip = row.locator('.bg-task-origin');
  await expect(chip).toHaveText('Nebula');
  await expect(chip).toHaveClass(/bg-origin-nebula/);
  await expect(chip).not.toHaveClass(/bg-origin-device/);
  await expect(chip).toHaveAttribute('title', '由 Nebula 开启');
  await expect(row.locator('.bg-task-kind')).toHaveText('本地');
  expect(errors).toEqual([]);
});
