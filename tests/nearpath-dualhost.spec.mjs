// nearpath-dualhost.spec.mjs — 近路环回腿双 host 探测 + miss 诊断曝光
// （门户身份透传批·方案 B 小刀①②，2026-10-28）。
//
// 设计 §6.1：Safari 对 `127.0.0.1` 与 `localhost` 的 PNA/混合内容处理不一致是
// 近路 miss 的头号嫌疑 ⇒ 环回探测 host 双试（127.0.0.1 先行，失败换 localhost，
// 命中哪个跳哪个）；§6.2：判定链每环失败把 miss 原因写进 sessionStorage 诊断键
// + console.info 一行（「为什么没走 localhost」从猜测变成可答）。
//
// 腿（harness = loopback-nearpath.spec.mjs 同套：静态路由 + routeWebSocket，
// 零后端、零 sbt、零构建）：
//   T1 双 host 命中（Safari 形）：127.0.0.1 拒答、localhost 应答同 deviceId
//      ⇒ 整页跳 `http://localhost:<port>/?ticket=`（命中 host 即跳转 host）。
//   T2 双 host 皆拒答（本机无网关/PNA）⇒ 停留 + miss 原因 probe_unreachable。
//   T3 首 host 应答但 deviceId 不等（别机网关）⇒ 停留 + device_mismatch (127.0.0.1)。
//   T4 铸票失败 ⇒ 停留 + mint_http_500（近路判定链后环的 miss 也有因可查）。
// Run: node tests/nearpath-dualhost.spec.mjs
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8197);
const NEAR_PORT = 18766; // 假「本机网关」端口（路由拦截，不真监听）

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};

const DEVICE_ID = 'dev-e2e-dualhost';
const MISS_KEY = 'stand_ws_nearpath_loopback_miss'; // branding 缺省 lowerName=stand 的命名空间字面
const ok = (name, cond, detail) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + JSON.stringify(detail) : ''}`);
  if (!cond) process.exitCode = 1;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function boot(page, { blockHosts = [], probeDeviceId = DEVICE_ID, mintStatus = 200 } = {}) {
  const near127 = `http://127.0.0.1:${NEAR_PORT}`;
  const nearLocal = `http://localhost:${NEAR_PORT}`;

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    const origSet = window.setTimeout.bind(window);
    window.setTimeout = (fn, delay, ...rest) =>
      (typeof delay === 'number' && delay >= 5000 && delay < 60000)
        ? origSet(fn, 250, ...rest) : origSet(fn, delay, ...rest);
    const origInt = window.setInterval.bind(window);
    window.setInterval = (fn, delay, ...rest) =>
      (typeof delay === 'number' && delay >= 5000 && delay < 60000)
        ? origInt(fn, 250, ...rest) : origInt(fn, delay, ...rest);
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    const rawPath = decodeURIComponent(url.pathname);
    // 近源（命中跳转账）的 document 请求先于 boot 源 '/' 重写判定（原 spec 同序）。
    if ((url.origin === near127 || url.origin === nearLocal) && rawPath === '/') {
      return route.fulfill({ status: 200, contentType: 'text/html', body: 'NEARPATH-REDEEMED ' + (url.searchParams.get('ticket') || '') });
    }
    let p = rawPath;
    if (p === '/') p = '/index.html';
    if ((url.origin === near127 || url.origin === nearLocal) && p === '/api/health') {
      if (blockHosts.includes(url.hostname)) return route.abort();
      return route.fulfill({
        status: 200, contentType: 'application/json',
        headers: { 'Access-Control-Allow-Origin': '*' },
        body: JSON.stringify({ status: 'ok', deviceId: probeDeviceId }),
      });
    }
    if (p === '/api/portal/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ boundHost: { deviceId: DEVICE_ID } }) });
    }
    if (p === '/api/health/loopback-info') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ port: NEAR_PORT }) });
    }
    if (p === '/api/portal/console-ticket') {
      if (mintStatus !== 200) return route.fulfill({ status: mintStatus, body: '{}' });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ consoleUrl: `https://relay.e2e/?ticket=tkt-dualhost-1` }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getConfig') ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      else if (m.type === 'getSessions' || m.type === 'listSessions') {
        ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'e2e-sid', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'e2e-sid' }));
      }
    });
  });

  await page.goto(`http://127.0.0.1:${PORT}/`);
}

const readMiss = (page) => page.evaluate((k) => {
  try { return JSON.parse(sessionStorage.getItem(k) || 'null'); } catch { return null; }
}, MISS_KEY);

const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });

try {
  // T1 双 host 命中：127.0.0.1 拒答 ⇒ localhost 腿赢 ⇒ 跳 localhost（命中即跳）。
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      await boot(page, { blockHosts: ['127.0.0.1'] });
      await page.waitForURL((u) => u.hostname === 'localhost' && Number(u.port) === NEAR_PORT, { timeout: 15000 });
      await page.waitForFunction(() => (document.body.textContent || '').includes('NEARPATH-REDEEMED'), null, { timeout: 10000 });
      ok('T1 first-host refused ⇒ localhost leg wins the probe', true);
      ok('T1 navigates to the WINNING host (localhost, never hardcoded 127.0.0.1)', page.url().startsWith(`http://localhost:${NEAR_PORT}/?ticket=`), { url: page.url() });
      const body = await page.evaluate(() => document.body.textContent || '');
      ok('T1 redemption page landed with the relay-minted ticket', body.includes('NEARPATH-REDEEMED tkt-dualhost-1'));
    } catch (e) {
      ok('T1 dual-host hit navigates to localhost', false, String(e).slice(0, 120));
    } finally { await page.close(); }
  }

  // T2 双 host 皆拒答 ⇒ 停留 + probe_unreachable 诊断。
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      await boot(page, { blockHosts: ['127.0.0.1', 'localhost'] });
      await sleep(2500);
      ok('T2 both hosts refused: page stays on the boot origin', page.url().startsWith(`http://127.0.0.1:${PORT}/`), { url: page.url() });
      const miss = await readMiss(page);
      ok('T2 miss reason exposed: probe_unreachable', miss && miss.reason === 'probe_unreachable', miss);
    } finally { await page.close(); }
  }

  // T3 首 host 应答但身份不符 ⇒ 停留 + device_mismatch (127.0.0.1)。
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      await boot(page, { probeDeviceId: 'dev-e2e-OTHER' });
      await sleep(2500);
      ok('T3 deviceId mismatch: page stays', page.url().startsWith(`http://127.0.0.1:${PORT}/`), { url: page.url() });
      const miss = await readMiss(page);
      ok('T3 miss reason names the winning probe host', miss && String(miss.reason).startsWith('device_mismatch (127.0.0.1)'), miss);
    } finally { await page.close(); }
  }

  // T4 判定链后环失败（铸票 500）⇒ mint_http_500 可查。
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      await boot(page, { mintStatus: 500 });
      await sleep(2500);
      ok('T4 mint failure: page stays', page.url().startsWith(`http://127.0.0.1:${PORT}/`), { url: page.url() });
      const miss = await readMiss(page);
      ok('T4 miss reason exposed: mint_http_500', miss && miss.reason === 'mint_http_500', miss);
    } finally { await page.close(); }
  }
} finally {
  await browser.close();
}

console.log(process.exitCode ? 'SPEC FAILED' : 'SPEC PASSED');
process.exit(process.exitCode || 0);
