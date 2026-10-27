// hot-stamp-reload.spec.mjs — 热更新地基前端钩（台账 #919，2026-10-27）。
//
// 锁定（hotStamp.js 的判路面；端点面 AssetStampRoutesSpec 钉）：
//  ① stamp 变化 + 空闲 ⇒ 自动 location.reload()（零手动刷新——设计件 §1.2 生效链闭环）；
//  ② 忙时让位：busySessionIds 非空 ⇒ 挂 pending 不 reload；busy 清零 ⇒ 下一拍落 reload；
//  ③ 防环闸：reload 后 20s 内（sessionStorage 时间戳）第二次变化不再 reload；
//  ④ stamp 未变 = 零动作（no-op 轮询零副作用）。
//
// Run: npx playwright test tests/hot-stamp-reload.spec.mjs

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

/** 服务端 stamp 读数（可变：测试中换值模拟 bundle 重建）。 */
let serverStamp;

async function bootApp(page) {
  serverStamp = 'aaaa0000aaaa0000';
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/nf-asset-stamp') {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ stamp: serverStamp, source: 'dev-tree', version: 'test', ts: Date.now() }),
      });
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
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: 'root',
    }));
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') {
        ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'root' }));
      } else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, null, { timeout: 15000 });
}

const hooks = (page) => page.evaluate(async () => {
  const m = await import('/js/hotStamp.js');
  return m._hotStampTestHooks();
});

test('①② busy-deferral: idle reloads on change; busy defers and lands the reload when idle', async ({ page }) => {
  await bootApp(page);
  // 基线捕获（首拍 fetch 是 fire-and-forget——轮询前显式等 read数）
  await expect.poll(() => page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).state()).then((s) => s.bootStamp), { timeout: 5000 }).toBe('aaaa0000aaaa0000');

  // ④ 未变 = no-op（判路回 'same'，页面原地）
  const same = await page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).checkNow());
  expect(same).toBe('same');

  // ② 忙时：stamp 变化 ⇒ busy-deferred（不 reload）
  await page.evaluate(() => {
    const s = (/** @type {any} */ (window)).Nebflow?.state;
    s.busySessionIds.add('sess-busy');
  });
  serverStamp = 'bbbb1111bbbb1111';
  const deferred = await page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).checkNow());
  expect(deferred).toBe('busy-deferred');
  let reloaded = 0;
  page.on('framenavigated', () => { reloaded += 1; });
  await page.waitForTimeout(300);
  await page.evaluate(() => true); // 页面未换（evaluate 存活即证据）

  // busy 清零 ⇒ 下一拍落 reload（framenavigated 证明）
  await page.evaluate(() => {
    const s = (/** @type {any} */ (window)).Nebflow?.state;
    s.busySessionIds.delete('sess-busy');
  });
  await page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).checkNow());
  await expect.poll(() => reloaded, { timeout: 5000 }).toBeGreaterThan(0);
});

test('① idle reload on stamp change; ③ reload guard blocks the second immediate reload', async ({ page }) => {
  await bootApp(page);
  await expect.poll(() => page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).state()).then((s) => s.bootStamp), { timeout: 5000 }).toBe('aaaa0000aaaa0000');

  // ① 空闲 + 变化 ⇒ reload（页面导航后 bootStamp 归零 = 新页面重新捕获）
  serverStamp = 'cccc2222cccc2222';
  const verdict = await page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).checkNow());
  expect(verdict).toBe('reloading');
  // reload 与读数赛跑：context 可能随导航销毁——容错轮询新页面的 bootStamp 捕获
  await expect.poll(async () => {
    try {
      return await page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).state()).then((s) => s.bootStamp);
    } catch (e) {
      return 'navigating'; // 旧 context 销毁瞬间：继续轮询新页面
    }
  }, { timeout: 10000 }).toBe('cccc2222cccc2222');

  // ③ 防环闸：sessionStorage 时间戳在场 ⇒ 20s 内第二次变化 guard-skipped
  serverStamp = 'dddd3333dddd3333';
  await expect.poll(() => page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).state()).then((s) => s.bootStamp), { timeout: 5000 }).toBe('cccc2222cccc2222');
  const second = await page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).checkNow());
  expect(second).toBe('guard-skipped');
});

test('missing endpoint degrades to silent no-op (unavailable verdict, no reload)', async ({ page }) => {
  serverStamp = 'aaaa0000aaaa0000';
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'e2e-token'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/nf-asset-stamp') return route.fulfill({ status: 404, body: '{"error":"unavailable"}' });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'root' }));
  });
  await page.goto('http://localhost:1/');
  const verdict = await page.evaluate(async () => (await (await import('/js/hotStamp.js'))._hotStampTestHooks()).checkNow());
  expect(['unavailable', 'no-boot']).toContain(verdict);
});
