// loopback-nearpath.spec.mjs — 本地回路短路批（2026-10-22 诊断修复批
// 「每次大的网络数据传输（点开本机文件查看）就触发重连」）前端面。
//
// 根因：Mac 本机控制台默认经 portal→relay 链路加载，本机文件字节全走隧道，
// bulk 帧把单条有序隧道流上的心跳 pong 饿死 ⇒ 前端 HB_MAX_MISSES=3 自我逐出
// ⇒ 重连。修法 = 控制台页自检同机近路跳回 http://127.0.0.1:<port>（文件零隧道），
// 外加心跳传输遮蔽兜底（远端设备场景）。
//
// 近路批 2026-11-04（四道闸根因修复）新增腿：
//   T5 闸③ 腿 A 被策略拦（fetch throw = WebKit mixed content 形态）+ 身份像素
//      （腿 B）命中 ⇒ 仍按正证判定并跳转（beacon hit reason=pixel）。
//   T6 腿 A 被拦 ∧ 像素 404（老引擎 / 本机无网关 / 远端设备）⇒ 停留隧道腿、WS 不断、
//      无错误页、**不铸票**、miss reason=probe_unreachable。
//   T7 腿 A 被拦 ∧ 像素 200 但尺寸不符（占位进程回图）⇒ 停留，reason=pixel_mismatch。
//   T8 铸票 4xx ⇒ 不跳（reason=mint_http_403，且不发导航）。
//   T9 beacon 端点 404（老引擎）⇒ 判定不变（仍命中跳转）——观测面不许影响判定。
// T2/T3 同时加断言：sessionStorage miss 原因 + miss beacon 一致（历史证据链不断）。
//
// 本 spec 既有四腿：
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
import { deflateSync } from 'node:zlib';
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
 * 造一张最小 PNG（哨兵图 = 引擎 HealthRoutes.LoopbackPixel 的 5×3；本函数也造
 * 「占位进程回图」的 1×1 反例）。手写 IHDR/IDAT/IEND，不引第三方库。
 */
function pngBytes(width, height) {
  const table = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    table[n] = c >>> 0;
  }
  const crc32 = (buf) => {
    let c = 0xFFFFFFFF;
    for (const b of buf) c = table[(c ^ b) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  };
  const chunk = (type, data) => {
    const t = Buffer.from(type, 'ascii');
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
    return Buffer.concat([len, t, data, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 0; // 8-bit grayscale
  const raw = [];
  for (let y = 0; y < height; y++) {
    raw.push(0);
    for (let x = 0; x < width; x++) raw.push(0x80);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(Buffer.from(raw))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/**
 * 通用 boot。shape 覆盖：
 *  - portalStatus: 200 JSON / '404' / 'mismatch'（环回探测回别的 deviceId）
 *  - probeRefused: true = 环回 /api/health 的跨源 fetch 被拦（route.abort ⇒
 *    TypeError，= WebKit mixed content / 本机无网关 / PNA 拒绝的同一页面侧形态）
 *  - pixel: '404'（默认：老引擎没有像素臂 / 本机无网关）/ 'ok'（5×3 哨兵）/
 *    'wrongdims'（1×1：占位进程回图）
 *  - mintStatus: 铸票应答状态（非 200 = 铸票失败腿）
 *  - beaconMode: 'ok' 收下 /api/health/nearpath-event；'404' 模拟老引擎无该端点
 *  - ws: WS mock 行为（autoPong / withhold 由调用方翻）
 * 返回句柄：计数器、挂起路由释放口、withhold 开关。
 */
async function boot(page, {
  portalStatus = '200', probeRefused = false, wsAutoPong = true,
  pixel = '404', mintStatus = 200, beaconMode = 'ok',
} = {}) {
  const counters = { mintCalls: 0, wsConnections: 0, pongs: 0, pings: 0, beacons: [], pixelProbes: [], mintBodies: [] };
  const state = { withhold: false, pendingFileRoute: null };
  const nearOrigin = `http://127.0.0.1:${NEAR_PORT}`;
  // 真网关在 127.0.0.1 与 localhost 两个 host 上是**同一个监听面**（双 host 腿的
  // 前提）；台子必须照此模拟，否则「127.0.0.1 被拦、localhost 落到 catch-all」
  // 会造出产品里不存在的 device_mismatch。
  const nearOrigins = new Set([nearOrigin, `http://localhost:${NEAR_PORT}`]);

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
    if (nearOrigins.has(url.origin) && url.pathname === "/") {
      // 近路跳转账面的 document 请求（真浏览器会导航过去；rawPath 判定——
      // 下方的 '/'→'/index.html' 重写只服务 boot 源静态树，不适用于近源）。
      return route.fulfill({ status: 200, contentType: 'text/html', body: 'NEARPATH-REDEEMED ' + (url.searchParams.get('ticket') || '') });
    }
    if (nearOrigins.has(url.origin) && p === '/api/health') {
      // 环回身份探测（必须先于 document 分支判定无关，但同 origin 同拦截面）。
      if (probeRefused) return route.abort(); // 策略拦截 / 本机无网关 / PNA 拒答 形
      const id = portalStatus === 'mismatch' ? 'dev-e2e-OTHER' : DEVICE_ID;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        headers: { 'Access-Control-Allow-Origin': '*' },
        body: JSON.stringify({ status: 'ok', deviceId: id }),
      });
    }
    if (nearOrigins.has(url.origin) && p === '/api/health/loopback-pixel.png') {
      // 闸③ 腿 B：身份像素（引擎新臂 HealthRoutes.LoopbackPixel 的台面等价物）。
      counters.pixelProbes.push(url.searchParams.get('expect'));
      if (pixel === 'ok' || pixel === 'wrongdims') {
        return route.fulfill({
          status: 200, contentType: 'image/png',
          body: pixel === 'ok' ? pngBytes(5, 3) : pngBytes(1, 1),
        });
      }
      return route.fulfill({ status: 404, body: '' });
    }
    if (p === '/api/health/nearpath-event') {
      // 近路事件上报面（引擎侧 NearPathStats）：'404' = 老引擎无该端点。
      if (beaconMode === '404') return route.fulfill({ status: 404, body: '' });
      try { counters.beacons.push(JSON.parse(route.request().postData() || '{}')); } catch { /* 坏 body */ }
      return route.fulfill({ status: 204, body: '' });
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
      try { counters.mintBodies.push(JSON.parse(route.request().postData() || '{}')); } catch { /* 坏 body */ }
      if (mintStatus !== 200) return route.fulfill({ status: mintStatus, body: '{}' });
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
    const consoleLines = [];
    page.on('console', (m) => consoleLines.push(m.text()));
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
      ok('T1 hit beacon fired with reason=fetch (the CORS leg won, Chromium path unchanged)',
        counters.beacons.some((b) => b.event === 'hit' && b.reason === 'fetch' && b.port === NEAR_PORT), counters.beacons);
      ok('T1 segment timings emitted (g1/g2/probe/mint/total)',
        consoleLines.some((l) => /\[nearpath\] timings g1=\d+ms g2=\d+ms probe=\d+ms mint=\d+ms/.test(l)),
        consoleLines.filter((l) => l.includes('nearpath')));
      ok('T1 no pixel probe on the CORS leg (nothing extra to pay on Chromium)',
        counters.pixelProbes.length === 0, counters.pixelProbes);
    } finally {
      await page.close();
    }
  }

  // ── T2 探测被拒（本机无网关 / 远端设备 / PNA 拒答）⇒ 停留 relay 源，WS 照常 ──
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
      // 停留 = 没导航 ⇒ 不可能出现浏览器错误页（文档始终是本页，WS 还活着即证）。
      const wsUp = await page.evaluate(async () => {
        const s = (await import('/js/state.js')).default;
        return !!(s.ws && s.ws.readyState === 1);
      });
      const miss = await page.evaluate(() => {
        try { return sessionStorage.getItem('stand_ws_nearpath_loopback_miss'); } catch { return null; }
      });
      ok('T3 deviceId mismatch: page stays on the relay origin', stayed, { url: page.url() });
      ok('T3 no mint on the mismatch path', counters.mintCalls === 0, { mintCalls: counters.mintCalls });
      ok('T3 stay path keeps the WS (no error page: the document is still ours)', wsUp);
      ok('T3 miss reason preserved for the evidence chain', String((JSON.parse(miss || '{}') || {}).reason || '').startsWith('device_mismatch'), { miss });
      ok('T3 miss beacon carries the clean token',
        counters.beacons.some((b) => b.event === 'miss' && b.reason === 'device_mismatch'), counters.beacons);
    } finally {
      await page.close();
    }
  }

  // ── T5 闸③ 腿 A 被策略拦（WebKit mixed content 形态）+ 身份像素命中 ⇒ 跳 ──
  // 近路批 2026-11-04 的主修腿：fetch throw（= WebKit 对 https→http 跨源 fetch 的
  // 网络层前拦截，实测台 FINDINGS §3 闸③）时，闸③ 改用 no-cors 身份像素取证 ——
  // 「同机正证」依旧成立（网关侧环回腿 ∧ expect == 本机 deviceId ∧ 哨兵尺寸）。
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      const { counters } = await boot(page, { probeRefused: true, pixel: 'ok' });
      await page.waitForURL((u) => u.origin === `http://127.0.0.1:${NEAR_PORT}`, { timeout: 15000 });
      const finalUrl = page.url();
      ok('T5 pixel-leg hit: navigated to the loopback redemption face despite the blocked fetch', finalUrl.startsWith(`http://127.0.0.1:${NEAR_PORT}/?ticket=`), { finalUrl });
      ok('T5 pixel leg got the expect anchor from gate ①', counters.pixelProbes.length >= 1 && counters.pixelProbes[0] === DEVICE_ID, { pixelProbes: counters.pixelProbes });
      ok('T5 ticket minted once (only after the identity proof)', counters.mintCalls === 1, { mintCalls: counters.mintCalls });
      ok('T5 mint body faces the bound device only',
        counters.mintBodies.length === 1 && counters.mintBodies[0].deviceId === DEVICE_ID, { mintBodies: counters.mintBodies });
      ok('T5 hit beacon fired before navigation with reason=pixel + port',
        counters.beacons.some((b) => b.event === 'hit' && b.reason === 'pixel' && b.port === NEAR_PORT), counters.beacons);
    } finally {
      await page.close();
    }
  }

  // ── T6 腿 A 被拦 ∧ 像素 404（老引擎/本机无网关/远端设备）⇒ 停留，不铸票 ────
  // 判据不放宽：fetch 被拦 ≠ 可以跳；拿不到正证就永远停留隧道腿。「被策略拦」与
  // 「不同机」页面侧不可区分 —— 两者都必须停留（方向安全：永不误跳，只会误停）。
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      const { counters } = await boot(page, { probeRefused: true, pixel: '404' });
      await sleep(3000);
      const stayed = page.url().startsWith(`http://127.0.0.1:${PORT}/`);
      const wsUp = await page.evaluate(async () => {
        const s = (await import('/js/state.js')).default;
        return !!(s.ws && s.ws.readyState === 1);
      });
      const miss = await page.evaluate(() => {
        try { return sessionStorage.getItem('stand_ws_nearpath_loopback_miss'); } catch { return null; }
      });
      ok('T6 blocked fetch + pixel 404: page stays on the tunnel origin', stayed, { url: page.url() });
      ok('T6 no ticket minted on the stay path (no-ticket-on-miss)', counters.mintCalls === 0, { mintCalls: counters.mintCalls });
      ok('T6 WS still established (stay must not disturb the session)', wsUp);
      ok('T6 miss reason = probe_unreachable (blocked vs down indistinguishable by design)',
        (JSON.parse(miss || '{}') || {}).reason === 'probe_unreachable', { miss });
      ok('T6 miss beacon fired with the stable token',
        counters.beacons.some((b) => b.event === 'miss' && b.reason === 'probe_unreachable'), counters.beacons);
    } finally {
      await page.close();
    }
  }

  // ── T7 腿 A 被拦 ∧ 像素 200 但哨兵尺寸不符（占位进程回图）⇒ 停留 ──────────
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      const { counters } = await boot(page, { probeRefused: true, pixel: 'wrongdims' });
      await sleep(3000);
      const stayed = page.url().startsWith(`http://127.0.0.1:${PORT}/`);
      const miss = await page.evaluate(() => {
        try { return sessionStorage.getItem('stand_ws_nearpath_loopback_miss'); } catch { return null; }
      });
      ok('T7 pixel with wrong sentinel dims: page stays (never jumps on an unproven answer)', stayed, { url: page.url() });
      ok('T7 no mint on the mismatch path', counters.mintCalls === 0, { mintCalls: counters.mintCalls });
      ok('T7 miss reason = pixel_mismatch (a new stable token)', (JSON.parse(miss || '{}') || {}).reason === 'pixel_mismatch', { miss });
    } finally {
      await page.close();
    }
  }

  // ── T8 铸票 4xx ⇒ 不跳 ────────────────────────────────────────────────
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      const { counters } = await boot(page, { mintStatus: 403 });
      await sleep(3000);
      const stayed = page.url().startsWith(`http://127.0.0.1:${PORT}/`);
      const miss = await page.evaluate(() => {
        try { return sessionStorage.getItem('stand_ws_nearpath_loopback_miss'); } catch { return null; }
      });
      ok('T8 mint 403: page stays on the tunnel origin', stayed, { url: page.url() });
      ok('T8 miss reason = mint_http_403', (JSON.parse(miss || '{}') || {}).reason === 'mint_http_403', { miss });
      ok('T8 miss beacon carries mint_http_403',
        counters.beacons.some((b) => b.event === 'miss' && b.reason === 'mint_http_403'), counters.beacons);
    } finally {
      await page.close();
    }
  }

  // ── T9 beacon 端点 404（老引擎）⇒ 判定不变（观测面永不影响判定）──────────
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      const { counters } = await boot(page, { beaconMode: '404' });
      await page.waitForURL((u) => u.origin === `http://127.0.0.1:${NEAR_PORT}`, { timeout: 15000 });
      ok('T9 beacon 404: the hit decision is unchanged (still navigates)',
        page.url().startsWith(`http://127.0.0.1:${NEAR_PORT}/?ticket=`), { url: page.url() });
      ok('T9 no beacon record (the old engine silently skipped the observation)', counters.beacons.length === 0, counters.beacons);
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
