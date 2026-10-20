// mobile-p4-daemons.spec.mjs — P4「心跳进程」手机面验收
//（mobile-secondary-screens 实施批；样张 v3.5 P4 §2 行结构 + §4 脉冲 + 开放点③④）。
//
// 样张→实机 DOM 映射（实施批口径）：
//   §8-1 盒模型 …… #daemon-panel：left 6 / top 58+safe / 308×560 / r16。
//   进程行（状态点+名+:port+命令+自启开关）…… 桌面 .daemon-row 全字段沿用
//        （手机层两行化：行 1 = 点/名/:port/开关，行 2 = 命令 + 动作钮）。
//   §4 心跳运行点 1.6s 脉冲 …… 桌面既有 daemon-pulse 1.6s infinite 原样。
//   开放点③ 脚注文案沿用 …… .mh-daemon-foot =「每 5 秒轮询 · 」+ 现网
//        daemons.emptyHint2 原文。
//   开放点④ 自启开关双向 …… 同一 DOM 开关（桌面 optimistic PUT 原样），本 spec
//        守护 flip → PUT /api/daemons/:id。
//   开放点①②（时间线/延迟毫秒）…… 后端无字段，不实施不伪造（见实施 spec）。
//
// 数据面 = HTTP /api/daemons 夹具，零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-p4-daemons.spec.mjs

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

const SID = 'p4-root-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

function daemonFixture() {
  return [
    { id: 'd1', name: 'dev-server', port: 8111, portOpen: true, status: 'running', autoStart: true, command: 'npm run dev', hasConfigPanel: false },
    { id: 'd2', name: 'watcher', port: 9222, portOpen: false, status: 'starting', autoStart: false, command: 'node watch.js --verbose', hasConfigPanel: false },
    { id: 'd3', name: 'legacy', port: 0, portOpen: false, status: 'error', autoStart: false, command: '', hasConfigPanel: false },
  ];
}

async function bootApp(page) {
  /** @type {{method:string,path:string,body:any}[]} */
  const writes = [];
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/daemons' || p.startsWith('/api/daemons/')) {
      if (route.request().method() !== 'GET') {
        writes.push({ method: route.request().method(), path: p, body: route.request().postDataJSON() });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(daemonFixture()) });
    }
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
  return writes;
}

/** 开面板：左列液态下拉 → 心跳键（.open 判据 → floatController morph）。 */
async function openPanel(page) {
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await page.locator('#daemon-btn').click();
  await expect(page.locator('#daemon-panel')).toHaveClass(/open/);
  await expect(page.locator('#daemon-panel')).toBeVisible();
  // morph 终态：类摘除 ∧ transform 归位（类可能尚未挂上 —— 160ms 延时腿）。
  await page.waitForFunction(() => {
    const m = document.getElementById('daemon-panel');
    if (!m) return false;
    if (m.classList.contains('mh-morph-anim') || m.classList.contains('mh-morph-close')) return false;
    const tf = getComputedStyle(m).transform;
    return tf === 'none' || tf === 'matrix(1, 0, 0, 1, 0, 0)';
  }, undefined, { timeout: 4000 });
  await expect(page.locator('#daemon-panel .daemon-row')).toHaveCount(3);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  writes = await bootApp(page);
});
/** @type {{method:string,path:string,body:any}[]} */
let writes = [];

test('T1 panel geometry: top-hung 308×560, left 6, radius 16', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const box = await page.locator('#daemon-panel').boundingBox();
  expect(box).toBeTruthy();
  expect(box.x).toBe(6);
  expect(box.width).toBe(308);
  expect(box.height).toBe(560);
  const radius = await page.locator('#daemon-panel').evaluate((el) => getComputedStyle(el).borderRadius);
  expect(radius).toBe('16px');
  expect(errors).toEqual([]);
});

test('T2 process row anatomy: status dot + name + :port + command + autostart switch', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const rows = page.locator('#daemon-panel .daemon-row');

  // 行 1 dev-server：running 点 + 可点名字链（portOpen）+ :8111 + 命令 + 开关勾选。
  const r1 = rows.nth(0);
  await expect(r1.locator('.daemon-status')).toHaveClass(/running/);
  // 运行点 1.6s 脉冲（桌面既有 daemon-pulse，样张 §4 沿用 —— 动画名守护）。
  const anim = await r1.locator('.daemon-status').evaluate((el) => getComputedStyle(el).animationName);
  expect(anim).toBe('daemon-pulse');
  await expect(r1.locator('a.daemon-name')).toHaveAttribute('href', 'http://localhost:8111');
  await expect(r1.locator('.daemon-port')).toHaveText(':8111');
  await expect(r1.locator('.daemon-command')).toHaveText('npm run dev');
  await expect(r1.locator('.daemon-autostart-input')).toBeChecked();

  // 行 2 watcher：starting 点 + 非链名 + 开关未选。
  const r2 = rows.nth(1);
  await expect(r2.locator('.daemon-status')).toHaveClass(/starting/);
  await expect(r2.locator('a.daemon-name')).toHaveCount(0);
  await expect(r2.locator('.daemon-autostart-input')).not.toBeChecked();
  // 行 3 legacy：error 点。
  await expect(rows.nth(2).locator('.daemon-status')).toHaveClass(/error/);
  expect(errors).toEqual([]);
});

test('T3 footnote: poll cadence + drop-restart copy (open point ③ 文案沿用)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const foot = page.locator('#daemon-panel .mh-daemon-foot');
  await expect(foot).toBeVisible();
  const text = await foot.textContent();
  expect(text).toContain('每 5 秒轮询');
  expect(text).toContain('掉线约 10 秒后自动拉起');
  expect(errors).toEqual([]);
});

test('T4 autostart toggle flips → PUT /api/daemons/:id (open point ④)', async ({ page }) => {
  const errors = watchErrors(page);
  await openPanel(page);
  const r1 = page.locator('#daemon-panel .daemon-row').nth(0);
  const r1input = r1.locator('.daemon-autostart-input');
  // 开关 = 自绘 track/thumb（input 本体不可视点）—— 用户路径点 label。
  const r1label = r1.locator('label.daemon-autostart');
  await expect(r1input).toBeChecked();
  await r1label.click();
  await expect(r1input).not.toBeChecked();
  await expect.poll(() => writes.filter((w) => w.method === 'PUT').length, { timeout: 5000 }).toBe(1);
  expect(writes.find((w) => w.method === 'PUT')?.path).toBe('/api/daemons/d1');
  expect(writes.find((w) => w.method === 'PUT')?.body).toEqual({ autoStart: false });
  await r1label.click();
  await expect(r1input).toBeChecked();
  await expect.poll(() => writes.filter((w) => w.method === 'PUT').length, { timeout: 5000 }).toBe(2);
  expect(writes[writes.length - 1]?.body).toEqual({ autoStart: true });
  expect(errors).toEqual([]);
});
