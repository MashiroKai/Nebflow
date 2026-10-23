// device-provenance.spec.mjs — 入站消息来源设备标记 · 客户端声明面（E2E）
//
// 契约（device-provenance 批 2026-10-23，作者令「消息也标记设备…是否为设备列表
// 里的设备」）：
//   1. 稳定身份：clientId localStorage 持久、跨刷新不变；deviceStamp() 形状 =
//      {clientId, platform, browser} + 可选 {name, boundDeviceId}（缺席键省略）。
//   2. 真人发送帧随帧带 `device` 声明：输入框直投（typeless 帧）端到端截获断言；
//      队列「立即发送」（immediateInput）等其余发送点的随帧声明由
//      DeviceProvenanceInjectionGateSpec（源契约门）钉死。
//   3. 设置面「消息设备标记」区：默认名可读（未登记-Mac Chrome 族）、命名持久、
//      绑定下拉来自 /api/neblink/status 花名册（self + peers）、绑定后 boundDeviceId
//      随帧上行、解绑回默认名。
//
// 自包含：page.route 静态面 + page.routeWebSocket 假 WS（branding-migration
// 同法），无后端、无 sbt。花名册面 mock /api/neblink/status。
//
// Run: node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/device-provenance.spec.mjs

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

const ROOT_SID = 'e2e-root-session';
const ROSTER = {
  device: { id: 'self-mac-1', name: 'Mashiros-MacBook-Pro' },
  peers: [
    { deviceId: 'peer-win-1', deviceName: 'DESKTOP-IJBBEOT', online: true },
    { deviceId: 'peer-phone-1', deviceName: 'Kaiyu-iPhone', online: false },
  ],
};

/**
 * Minimal boot（branding-migration 同法）：configData + sessionList + 假 WS。
 * 所有客户端 → 服务器帧被截进 `frames` 供逐帧断言。
 */
async function bootApp(page, frames) {
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ROSTER) });
    }
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
      frames.push(msg);
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
  await page.waitForTimeout(200);
}

/** 最近一次被截获的、带 content 的用户帧（typeless 或 immediateInput）。 */
function lastUserFrame(frames, pred = () => true) {
  for (let i = frames.length - 1; i >= 0; i--) {
    const f = frames[i];
    if (typeof f.content === 'string' && pred(f)) return f;
  }
  return null;
}

test('device identity: stable clientId across reloads; stamp shape with UA-assisted platform/browser', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames);

  const stamp1 = await page.evaluate(async () => (await import('/js/deviceIdentity.js')).deviceStamp());
  expect(stamp1.clientId, 'clientId 必须在场（身份主键）').toBeTruthy();
  // Playwright 默认桌面 UA（本机 = macOS headless chrome）。
  expect(stamp1.platform).toBe('mac');
  expect(stamp1.browser).toBe('Chrome');
  expect(stamp1, '未绑定时不得携带 boundDeviceId 键').not.toHaveProperty('boundDeviceId');
  expect(stamp1, '未命名时不得携带 name 键').not.toHaveProperty('name');

  // 跨刷新稳定：reload 后同一 clientId。
  await page.reload();
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  const stamp2 = await page.evaluate(async () => (await import('/js/deviceIdentity.js')).deviceStamp());
  expect(stamp2.clientId).toBe(stamp1.clientId);

  // 命名 + 绑定随 stamp 上行；解绑即摘键。
  await page.evaluate(async () => {
    const d = await import('/js/deviceIdentity.js');
    d.setDeviceName('我的手机');
    d.setDeviceBinding('peer-phone-1');
  });
  const stamp3 = await page.evaluate(async () => (await import('/js/deviceIdentity.js')).deviceStamp());
  expect(stamp3.name).toBe('我的手机');
  expect(stamp3.boundDeviceId).toBe('peer-phone-1');

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('typeless send: the input-box frame carries the device declaration', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames);

  await page.fill('#input', '设备标记探针消息');
  await page.press('#input', 'Enter');
  await page.waitForTimeout(300);

  const frame = lastUserFrame(frames);
  expect(frame, '必须截获 typeless 用户帧').toBeTruthy();
  expect(frame.content).toBe('设备标记探针消息');
  expect(frame.device, 'typeless 帧必须带 device 声明').toBeTruthy();
  expect(frame.device.clientId).toBeTruthy();
  expect(frame.device.platform).toBe('mac');
  expect(frame.device.browser).toBe('Chrome');

  expect(pageErrors, '零新增页面错误').toEqual([]);
});

test('settings face: device stamp section renders readable default, rename and roster binding', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const frames = [];
  await bootApp(page, frames);

  await page.evaluate(async () => {
    const { openSettingsPanel } = await import('/js/sidebar.js');
    openSettingsPanel();
  });
  const section = page.locator('#device-stamp-section');
  await expect(section).toBeVisible();

  // 默认读数：未登记 + UA 派生可读名（桌面 headless chrome → 未登记-Mac Chrome）。
  await expect(page.locator('#device-stamp-current')).toHaveText(/未登记-Mac Chrome（未登记）/);

  // 绑定下拉来自花名册：不绑定 + self + 两个 peers。
  const optTexts = await page.locator('#cfg-device-stamp-bind option').allTextContents();
  expect(optTexts).toContain('不绑定（未登记）');
  expect(optTexts).toContain('Mashiros-MacBook-Pro');
  expect(optTexts).toContain('DESKTOP-IJBBEOT');
  expect(optTexts).toContain('Kaiyu-iPhone');

  // 命名持久 + 读数翻面（用户命名（未登记））。存储键经 branding.js key()
  // 命名空间（静态服务无注入 ⇒ fallback lowerName='stand'），不写死字面量。
  await page.fill('#cfg-device-stamp-name', '客厅 iPad');
  await page.click('#btn-device-stamp-name-save');
  await expect(page.locator('#device-stamp-current')).toHaveText(/客厅 iPad（未登记）/);
  const nameKeyOf = await page.evaluate(async () => (await import('/js/branding.js')).key('device_name'));
  const stored = await page.evaluate((k) => localStorage.getItem(k), nameKeyOf);
  expect(stored).toBe('客厅 iPad');

  // 绑定到花名册设备 → 读数翻登记名（已登记），绑定值持久。
  await page.selectOption('#cfg-device-stamp-bind', 'peer-win-1');
  await expect(page.locator('#device-stamp-current')).toHaveText(/DESKTOP-IJBBEOT（已登记）/);
  const boundKeyOf = await page.evaluate(async () => (await import('/js/branding.js')).key('device_bound_id'));
  const bound = await page.evaluate((k) => localStorage.getItem(k), boundKeyOf);
  expect(bound).toBe('peer-win-1');

  // 绑定后随帧上行 boundDeviceId（下一条 typeless 帧）。
  await page.keyboard.press('Escape');
  await page.evaluate(() => document.getElementById('settings-overlay')?.classList.remove('on'));
  await page.fill('#input', '绑定后探针');
  await page.press('#input', 'Enter');
  await page.waitForTimeout(300);
  const frame = lastUserFrame(frames);
  expect(frame?.device?.boundDeviceId).toBe('peer-win-1');

  // 解绑回默认名。
  await page.evaluate(async () => {
    const { openSettingsPanel } = await import('/js/sidebar.js');
    openSettingsPanel();
  });
  await page.selectOption('#cfg-device-stamp-bind', '');
  await expect(page.locator('#device-stamp-current')).toHaveText(/未登记-Mac Chrome（未登记）|客厅 iPad（未登记）/);
  const boundAfter = await page.evaluate((k) => localStorage.getItem(k), boundKeyOf);
  expect(boundAfter).toBeNull();

  expect(pageErrors, '零新增页面错误').toEqual([]);
});
