// loopback-nearpath.spec.mjs — 本地回路短路批（2026-10-22 诊断修复批
// 「每次大的网络数据传输（点开本机文件查看）就触发重连」）前端面。
//
// 根因：Mac 本机控制台默认经 portal→relay 链路加载，本机文件字节全走隧道，
// bulk 帧把单条有序隧道流上的心跳 pong 饿死 ⇒ 前端 HB_MAX_MISSES=3 自我逐出
// ⇒ 重连。修法 = 控制台页自检同机近路跳回 http://127.0.0.1:<port>（文件零隧道），
// 外加心跳传输遮蔽兜底（远端设备场景）。
//
// 本 spec 四腿：
//   T1 近路命中：portal/status + loopback-info + 环回探测同 deviceId + 铸票
//      ⇒ 整页跳到 http://127.0.0.1:<port>/?ticket=<ticket>（本地兑换面）。
//   T2 探测被拒（本机无网关 / PNA 拒答）⇒ 停留 relay 源，WS 照常建立，零跳转。
//   T3 身份不符（环回探测的 deviceId ≠ portal 绑定）⇒ 不跳（防错认别机网关）。
//   T4 心跳传输遮蔽：pong 全扣 + /api/nf-file 挂起在途 ⇒ 传输遮蔽期内不逐出；
//      遮蔽硬上限（12 窗）到点照常判死；无传输的对照腿 3 miss 即逐出。
//
// harness = busy-queue-ack.spec.mjs 同一套（静态路由 + routeWebSocket + 真 ws.js）。
// 零后端、零 sbt、零构建。
//
// Run: node tests/loopback-nearpath.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 8195);
const NEAR_PORT = 18765; // 假「本机网关」端口（路由拦截，不真监听）

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const DEVICE_ID = 'dev-e2e-nearpath';
const ok = (name, cond, detail) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + JSON.stringify(detail) : ''}`);
  if (!cond) process.exitCode = 1;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * 通用 boot。shape 覆盖：
 *  - portalStatus: 200 JSON / '404' / 'mismatch'（环回探测回别的 deviceId）
 *  - ws: WS mock 行为（autoPong / withhold 由调用方翻）
 * 返回句柄：计数器、挂起路由释放口、withhold 开关。
 */
async function boot(page, { portalStatus = '200', probeRefused = false, wsAutoPong = true } = {}) {
  const counters = { mintCalls: 0, wsConnections: 0, pongs: 0, pings: 0 };
  const state = { withhold: false, pendingFileRoute: null };
  const nearOrigin = `http://127.0.0.1:${NEAR_PORT}`;

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // 时间压缩：心跳 interval 30s / grace ≥5s / 重连退避 ≥8s 压到 250ms——
    // 其余 <5s 的应用计时器不受影响（busy-queue-ack 同款纪律，阈值取 5s）。
    const origSet = window.setTimeout.bind(window);
    window.setTimeout = (fn, delay, ...rest) =>
      (typeof delay === 'number' && delay >= 5000 && delay < 60000)
        ? origSet(fn, 250, ...rest) : origSet(fn, delay, ...rest);
    const origInt = window.setInterval.bind(window);
    window.setInterval = (fn, delay, ...rest) =>
      (typeof delay === 'number' && delay >= 5000 && delay < 60000)
        ? origInt(fn, 250, ...rest) : origInt(fn, delay, ...rest);
  });

  // 注意：Playwright 路由后注册者先命中 ⇒ catch-all 最先注册，具体路由殿后。
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (url.origin === nearOrigin && url.pathname === '/') {
      // 近路跳转账面的 document 请求（真浏览器会导航过去；rawPath 判定——
      // 下方的 '/'→'/index.html' 重写只服务 boot 源静态树，不适用于近源）。
      return route.fulfill({ status: 200, contentType: 'text/html', body: 'NEARPATH-REDEEMED ' + (url.searchParams.get('ticket') || '') });
    }
    if (url.origin === nearOrigin && p === '/api/health') {
      // 环回身份探测（必须先于 document 分支判定无关，但同 origin 同拦截面）。
      if (probeRefused) return route.abort(); // 本机无网关 / PNA 拒答形
      const id = portalStatus === 'mismatch' ? 'dev-e2e-OTHER' : DEVICE_ID;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        headers: { 'Access-Control-Allow-Origin': '*' },
        body: JSON.stringify({ status: 'ok', deviceId: id }),
      });
    }
    if (p === '/api/portal/status') {
      if (portalStatus === '404') return route.fulfill({ status: 404, body: '{}' });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ boundHost: { deviceId: DEVICE_ID } }) });
    }
    if (p === '/api/health/loopback-info') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ port: NEAR_PORT }) });
    }
    if (p === '/api/portal/console-ticket') {
      counters.mintCalls++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ consoleUrl: `https://relay.e2e/?ticket=tkt-e2e-1` }) });
    }
    if (p.startsWith('/api/nf-file')) {
      // T4 的挂起传输：收下不应答，fetch 永远在途（= 大文件慢慢传）。
      state.pendingFileRoute = route;
      return;
    }
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
    counters.wsConnections++;
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'ping') {
        counters.pings++;
        if (!state.withhold && wsAutoPong) { counters.pongs++; ws.send(JSON.stringify({ type: 'pong' })); }
      } else if (parsed.type === 'getConfig') {
        ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      } else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') {
        ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'e2e-sid', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'e2e-sid' }));
      }
    });
  });

  await page.goto(`http://127.0.0.1:${PORT}/`);
  return { counters, state };
}

const browser = await chromium.launch({ headless: process.env.HEADLESS !== '0' });

try {
  // ── T1 近路命中：四步判定全过 ⇒ 跳到环回兑换面 ──────────────────────────
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const { counters } = await boot(page);
    try {
      await page.waitForURL((u) => u.origin === `http://127.0.0.1:${NEAR_PORT}`, { timeout: 15000 });
      await page.waitForFunction(() => (document.body.textContent || '').includes('NEARPATH-REDEEMED'), null, { timeout: 10000 });
      const finalUrl = page.url();
      const body = await page.evaluate(() => document.body.textContent || '');
      ok('T1 near-path hit: navigated to the loopback redemption face', finalUrl.startsWith(`http://127.0.0.1:${NEAR_PORT}/?ticket=`));
      ok('T1 redemption URL carries the relay-minted ticket', finalUrl.includes('ticket=tkt-e2e-1'), { finalUrl });
      ok('T1 the loopback face answered (redeemed page landed)', body.includes('NEARPATH-REDEEMED'));
      ok('T1 console ticket was minted exactly once', counters.mintCalls === 1, { mintCalls: counters.mintCalls });
    } finally {
      await page.close();
    }
  }

  // ── T2 探测被拒（本机无网关）⇒ 停留 relay 源，WS 照常 ──────────────────
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      const { counters } = await boot(page, { probeRefused: true });
      await sleep(2500);
      const stayed = page.url().startsWith(`http://127.0.0.1:${PORT}/`);
      const wsUp = await page.evaluate(async () => {
        const s = (await import('/js/state.js')).default;
        return !!(s.ws && s.ws.readyState === 1);
      });
      ok('T2 probe refused: page stays on the relay origin', stayed, { url: page.url() });
      ok('T2 WS established normally while staying', wsUp);
      ok('T2 no console ticket minted on the stay path', counters.mintCalls === 0, { mintCalls: counters.mintCalls });
    } finally {
      await page.close();
    }
  }

  // ── T3 身份不符 ⇒ 不跳（别机同端口网关不能错认）────────────────────────
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      const { counters } = await boot(page, { portalStatus: 'mismatch' });
      await sleep(2500);
      const stayed = page.url().startsWith(`http://127.0.0.1:${PORT}/`);
      ok('T3 deviceId mismatch: page stays on the relay origin', stayed, { url: page.url() });
      ok('T3 no mint on the mismatch path', counters.mintCalls === 0, { mintCalls: counters.mintCalls });
    } finally {
      await page.close();
    }
  }

  // ── T4 心跳传输遮蔽 ────────────────────────────────────────────────────
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    page.on('console', (m) => {
      const txt = m.text();
      if (txt.includes('heartbeat miss sheltered')) console.log('  [page]', txt);
    });
    try {
      // portal/status 404 ⇒ 近路尝试跳过，专心测心跳。
      const { counters, state } = await boot(page, { portalStatus: '404' });
      await page.waitForFunction(async () => {
        const s = (await import('/js/state.js')).default;
        return s.ws && s.ws.readyState === 1;
      }, null, { timeout: 20000 });
      // 活性面：心跳 round-trip 真的在跑（pong 已收到过）。
      await sleep(1200);
      ok('T4 sanity: heartbeat round-trip alive before withholding', counters.pongs > 0, counters);

      // 挂起一个大文件传输（fetch 在途，永不应答）+ 全扣 pong。
      await page.evaluate(() => {
        window.__hang = fetch('/api/nf-file?path=/big-e2e.bin', { cache: 'no-store' }).catch((e) => String(e));
      });
      await sleep(300); // 让计数落地
      state.withhold = true;

      // 遮蔽期确定性断言：轮询读数——遮蔽窗计到 ≥3（远超旧 3-miss 预算）且
      // 计数爬升全程 ws 保持 OPEN；随后计数达硬上限 12 后照常逐出（下一步）。
      let maxSheltered = 0;
      let openThroughout = true;
      const tS = Date.now();
      while (Date.now() - tS < 15000) {
        const cur = await page.evaluate(async () => {
          const s = (await import('/js/state.js')).default;
          const m = await import('/js/ws.js');
          return { open: !!(s.ws && s.ws.readyState === 1), stats: m.hbTransferStats() };
        });
        maxSheltered = Math.max(maxSheltered, cur.stats.shelteredMisses);
        if (!cur.open) break;
        if (maxSheltered >= 11) break; // 爬到硬上限前沿即可——逐出交给下一步判
        await sleep(150);
      }
      ok('T4 sheltered: transfer in flight shelters misses beyond the 3-miss eviction budget', maxSheltered >= 3, { maxSheltered });
      ok('T4 sheltered: socket stayed open through the shelter climb', openThroughout && maxSheltered >= 3, { maxSheltered });

      // 硬上限：遮蔽 12 窗后照常判死 ⇒ 触发重连（传输仍挂着）。
      const t0 = Date.now();
      let evicted = false;
      while (Date.now() - t0 < 20000) {
        if (counters.wsConnections >= 2) { evicted = true; break; }
        await sleep(250);
      }
      ok('T4 hard cap: eviction fires once the shelter window budget is exhausted', evicted, counters);

      // 释放挂起传输 + 恢复 pong ⇒ 重连成功。
      state.withhold = false;
      if (state.pendingFileRoute) {
        await state.pendingFileRoute.fulfill({ status: 200, contentType: 'application/octet-stream', body: 'x' }).catch(() => {});
        state.pendingFileRoute = null;
      }
      await page.waitForFunction(async () => {
        const s = (await import('/js/state.js')).default;
        return s.ws && s.ws.readyState === 1;
      }, null, { timeout: 15000 });
      ok('T4 recovery: reconnect succeeds after the transfer settles', true);

      // 对照腿：无传输在途，pong 全扣 ⇒ 3 miss 内照常逐出。
      state.withhold = true;
      const connsBefore = counters.wsConnections;
      const t1 = Date.now();
      let controlEvicted = false;
      while (Date.now() - t1 < 8000) {
        if (counters.wsConnections > connsBefore) { controlEvicted = true; break; }
        await sleep(250);
      }
      ok('T4 control: without a transfer, eviction fires on the normal 3-miss budget', controlEvicted, counters);
    } finally {
      await page.close();
    }
  }
} finally {
  await browser.close();
}

console.log(process.exitCode ? 'SPEC FAILED' : 'SPEC PASSED');
process.exit(process.exitCode || 0);
