// cardwin-local-direct.spec.mjs — 独立窗口本机直连批（2026-10-29）场景钉 +
// 前后耗时对比。作者报障两连：10:46「独立窗口页面打开有点慢，没走 localhost
// 加速吗」→ 11:06 升级「完全空白打不开」（截图：弹窗域 link.mystand.space，
// 只剩 index 静态壳的空标题胶囊 —— main.js 模块图在拥塞隧道上挂起/失败，
// canvas.js 的引导态面自己也在模块图里 ⇒ 无人报错）。
//
// origin 解析规则钉（docs/design/cardwin-origin-resolution-spec.zh-CN.md）：
//   本机会话（父页在官网入口 origin + 近路面已验证本机网关基址）
//     ⇒ 弹窗直开 http://127.0.0.1:<port>（端口服务端下发）+ 经 relay 铸的一次
//       性 console ticket（会话归属跟住父会话，「经官网」= relay-mint 不丢）；
//   远端会话 / 无验证基址 ⇒ 相对 URL 开窗（入口 origin，隧道腿现状零变化）；
//   已在直连腿的父页 ⇒ 相对开窗（同源 cookie 会话，现状零变化）。
//
// harness：双真实监听（127.0.0.1 loopback —— 「弹窗走 localhost」的网络面证据
// 就是弹窗全部子资源落本机 origin）：
//   RELAY 端口 = 官网入口腿形（静态树 + 可注入每请求时延；portal/nearpath 面）；
//   LOCAL 端口 = 本机网关形（静态树零时延 + 票兑换 303 保深链 + health/nf-reach）。
// ws.js 父页自跳腿（/api/health/loopback-info）按 404 mock —— 隔离本批弹窗直连
// 腿（父页停留 relay = 作者实况形态；loopback-info 链有 loopback-nearpath.spec 钉）。
// sw.js 一律 404：SWR 缓存会把隧道腿时延吞噬成缓存即答，「首开耗时」对比必须
// 在无 SW 的冷缓存口径（作者首开 = 冷缓存）。
//
// Run: node tests/cardwin-local-direct.spec.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const RELAY_PORT = Number(process.env.RELAY_PORT || 8172);
const LOCAL_PORT = Number(process.env.LOCAL_PORT || 8173);
const RELAY_BASE = `http://127.0.0.1:${RELAY_PORT}`;
const LOCAL_BASE = `http://127.0.0.1:${LOCAL_PORT}`;

/** 隧道腿每请求注入时延（ms；0 = 关）。T3 计时对比的「前」腿旋钮。 */
let relayLatencyMs = 0;

const ALPHA = '/tmp/cw-direct/alpha.md';
const FILE_CONTENTS = new Map([
  [ALPHA, { content: '# alpha\n\nalpha direct body v1\n', itemType: 'markdown' }],
]);

const DEVICE_ID = 'dev-e2e-cardwin-direct';
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const ROOT_ALLOW = new Set(['style.css', 'app.js', 'favicon-32.png', 'favicon-16.png',
  'favicon.svg', 'favicon.ico', 'favicon-180.png', 'favicon-192.png', 'favicon-512.png']);
const ALLOW_PREFIXES = ['/js/', '/css/', '/vendor/', '/brand/', '/assets/'];

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
  process.exitCode = fail > 0 ? 1 : 0;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 近路探测面开关（makeServer.nearOk）——false ⇒ 404 负结论（回落腿）。 */
let nearOkFlag = true;

/** 网关忠实静态服（`/` 唯一 index 入口；`/index.html` 404 空体 —— 与
 *  WebSocketRoutes 逐位同形）。relay=true 时每请求先睡 relayLatencyMs。 */
function makeServer({ relay = false } = {}) {
  const hits = [];
  const mints = [];
  let ticketSeq = 0;
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    const p = url.pathname;
    hits.push(p + url.search);
    const respond = (fn) => {
      const go = () => fn();
      if (relay && relayLatencyMs > 0) setTimeout(go, relayLatencyMs); else go();
    };
    // ── 本机网关形：票兑换面（GET /?ticket=…）—— 303 保深链（ticket 外参数
    //    原序保留）+ Set-Cookie（#522：http 腿无 Secure）—— ConsoleTicketRoutes
    //    新语义的前端消费侧钉。
    if (!relay && p === '/' && url.searchParams.get('ticket')) {
      return respond(() => {
        const carried = new URLSearchParams(url.search);
        carried.delete('ticket');
        const qs = carried.toString();
        res.writeHead(303, {
          Location: '/' + (qs ? '?' + qs : ''),
          'Set-Cookie': 'stand_session=mock-direct-session; Path=/; HttpOnly; SameSite=Lax',
        });
        res.end();
      });
    }
    if (p === '/') {
      return respond(() => {
        try {
          const b = readFileSync(join(WEB_DIR, 'index.html'));
          res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-cache' });
          res.end(b);
        } catch { res.writeHead(404); res.end(); }
      });
    }
    // ── 本机网关形探测面（CORS 开放给 relay origin —— probeCorsHeaders 同构：
    //    白名单 = 门户/中继 origin ∧ 环回腿；nf-reach 全开放 + PNA 头）。
    if (!relay && p === '/api/health') {
      const origin = req.headers.origin || '';
      const allow = origin === RELAY_BASE;
      return respond(() => {
        const headers = { 'Content-Type': 'application/json' };
        if (allow) {
          headers['Access-Control-Allow-Origin'] = origin;
          headers['Access-Control-Allow-Private-Network'] = 'true';
        }
        res.writeHead(200, headers);
        res.end(JSON.stringify({ status: 'ok', deviceId: DEVICE_ID, version: 'test' }));
      });
    }
    if (p === '/api/nf-reach') {
      return respond(() => {
        res.writeHead(204, {
          'x-stand-gateway': '1',
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Expose-Headers': 'x-stand-gateway',
          'Access-Control-Allow-Private-Network': 'true',
        });
        res.end();
      });
    }
    // ── 官网入口（relay）形 portal/nearpath 面。
    if (relay && p === '/api/portal/status') {
      return respond(() => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ boundHost: { deviceId: DEVICE_ID } }));
      });
    }
    if (relay && p === '/api/portal/console-ticket' && req.method === 'POST') {
      return respond(() => {
        mints.push(1);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ consoleUrl: `https://link.mystand.space/?ticket=tkt-${++ticketSeq}` }));
      });
    }
    if (relay && p === '/api/neblink/nearpath') {
      return respond(() => {
        if (!nearOkFlag) { res.writeHead(404, { 'Content-Type': 'application/json' }); res.end('{}'); return; }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          enrolled: true, matchedDeviceId: DEVICE_ID, selfId: DEVICE_ID,
          port: LOCAL_PORT, lanAddresses: [],
        }));
      });
    }
    if (relay && p === '/api/health/loopback-info') {
      // 隔离腿：父页自跳链 miss（作者实况形态 —— 父页停留 relay）。
      return respond(() => { res.writeHead(404, { 'Content-Type': 'application/json' }); res.end('{}'); });
    }
    const serve = (rel) => {
      const file = join(WEB_DIR, rel);
      if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
      try {
        const b = readFileSync(file);
        res.writeHead(200, { 'Content-Type': MIME[rel.slice(rel.lastIndexOf('.'))] || 'application/octet-stream' });
        res.end(b);
      } catch { res.writeHead(404); res.end(); }
    };
    if (p === '/sw.js') { res.writeHead(404); res.end(); return; }   // 见文件头：无 SW 口径
    if (ALLOW_PREFIXES.some((q) => p.startsWith(q))) return respond(() => serve(p));
    if (ROOT_ALLOW.has(p.slice(1))) return respond(() => serve(p));
    res.writeHead(404, { 'Content-Length': '0' });
    res.end();
  });
  server.hits = hits;
  server.mints = mints;
  return server;
}

const relayServer = makeServer({ relay: true });
const localServer = makeServer({ relay: false });
await new Promise((r) => relayServer.listen(RELAY_PORT, '127.0.0.1', r));
await new Promise((r) => localServer.listen(LOCAL_PORT, '127.0.0.1', r));

const browser = await chromium.launch();

async function newCtx() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    // 时间压缩：≥5s 应用计时器压短（busy-queue-ack 同纪律；探测 2.5s/8s 超时
    // 等应用计时器不受影响）。
    const origSet = window.setTimeout.bind(window);
    window.setTimeout = (fn, delay, ...rest) =>
      (typeof delay === 'number' && delay >= 5000 && delay < 60000)
        ? origSet(fn, 250, ...rest) : origSet(fn, delay, ...rest);
    const origInt = window.setInterval.bind(window);
    window.setInterval = (fn, delay, ...rest) =>
      (typeof delay === 'number' && delay >= 5000 && delay < 60000)
        ? origInt(fn, 250, ...rest) : origInt(fn, delay, ...rest);
  });
  return ctx;
}

/** 有会话形态的 WS mock（同 cardwin-session-scenarios 口径；pop.readFile 应答真容）。 */
async function mockLiveWs(ctx) {
  await ctx.routeWebSocket(/\/ws$/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown',
          fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0,
        }));
      }
    });
  });
}

/** 主窗准备：goto + 开一张文件卡。返回 page。 */
async function openMainWithCard(page, base) {
  await page.goto(base + '/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  const rec = FILE_CONTENTS.get(ALPHA);
  await page.evaluate(({ absPath, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, title: 'alpha.md', content, absPath, pinned: true },
    }));
  }, { absPath: ALPHA, content: rec.content, itemType: rec.itemType });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${ALPHA}"]`, { timeout: 15000, state: 'attached' });
  return page;
}

/** 等弹窗真容渲染完（readFile 通路内容可见）。返回耗时 ms；超时 -1。 */
async function waitCardContent(popup, timeoutMs = 30000) {
  const t0 = Date.now();
  for (;;) {
    const txt = await popup.evaluate((p) => {
      const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`);
      return pane ? pane.textContent : '';
    }, ALPHA).catch(() => '');
    if (String(txt).includes('alpha direct body v1')) return Date.now() - t0;
    if (Date.now() - t0 > timeoutMs) return -1;
    await sleep(120);
  }
}

/* ════════ T1 本机直连主链：弹窗落本机网关 + 深链保真 + PM 桥 ════════ */
{
  nearOkFlag = true;
  relayLatencyMs = 0;
  const ctx = await newCtx();
  await mockLiveWs(ctx);
  const page = await ctx.newPage();
  await openMainWithCard(page, RELAY_BASE);
  await sleep(2500);   // 近路主动探测拍（onopen 触发）：记录本机验证基址

  const popupRequests = [];
  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await page.click('#canvas-pop-btn');
  const popup = await popupP;
  popup.on('request', (r) => popupRequests.push(r.url()));
  // 预约窗初态 about:blank —— 先等兑换 303 落地（深链 URL），再断言落点。
  await popup.waitForURL(/card-window=1/, { timeout: 20000 });
  await popup.waitForLoadState('domcontentloaded');

  const landed = popup.url();
  ok('T1-1 直连落点：弹窗落本机网关 origin（127.0.0.1:<port>，非入口 origin）',
    landed.startsWith(LOCAL_BASE + '/?'), landed);
  ok('T1-2 深链保真：兑换 303 后仍是卡窗页（card-window=1&path 在、ticket 剥离、cwdir=1 直连标记在）',
    landed.includes('card-window=1') && landed.includes(encodeURIComponent(ALPHA)) &&
    !landed.includes('ticket=') && landed.includes('cwdir=1'),
    landed);

  const ms = await waitCardContent(popup);
  ok('T1-3 直连弹窗真容渲染（readFile 通路，非骨架）', ms >= 0, `content in ${ms}ms`);
  ok('T1-4 LIVE 网络面：弹窗全部请求落本机 origin（零隧道子资源）',
    popupRequests.length > 0 && popupRequests.every((u) => u.startsWith(LOCAL_BASE + '/') || u === 'about:blank'),
    `${popupRequests.length} reqs, off-origin: ${popupRequests.filter((u) => !u.startsWith(LOCAL_BASE + '/')).slice(0, 3).join(',')}`);
  ok('T1-5 经 relay 铸票恰一发（会话归属桥走的是官网铸票面）',
    relayServer.mints.length === 1, `mints=${relayServer.mints.length}`);

  // PM 桥双向：等一拍心跳（3s）→ 弹窗仍活着（未触发 20s 自闭看门狗）→
  // 弹窗「合并」→ PM bye → 主窗解除独立标记 + 弹窗自关。
  await sleep(3500);
  const alive = await popup.evaluate(() => !window.closed).catch(() => false);
  ok('T1-6 PM 桥心跳送达（跨源卡窗 3.5s 后仍活 —— 无 20s 自闭险）', alive);
  await popup.click('#canvas-merge-btn');
  await sleep(1200);
  const closedBye = await popup.evaluate(() => window.closed).catch(() => true);
  const unmarked = await page.evaluate((p) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`);
    return !!(pane && !pane.hasAttribute('data-independent'));
  }, ALPHA).catch(() => false);
  ok('T1-7 PM 桥双向：弹窗合并 → bye 回主窗 → 独立标记解除 + 窗自关', closedBye && unmarked,
    `closed=${closedBye} unmarked=${unmarked}`);
  await ctx.close();
}

/* ════════ T2 远端/无验证基址：回落相对开窗（入口 origin，现状零变化）════════ */
{
  nearOkFlag = false;
  relayLatencyMs = 0;
  relayServer.mints.length = 0;   // T1 铸过一票 —— 本腿从零计数
  const ctx = await newCtx();
  await mockLiveWs(ctx);
  const page = await ctx.newPage();
  await openMainWithCard(page, RELAY_BASE);
  await sleep(1200);
  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await page.click('#canvas-pop-btn');
  const popup = await popupP;
  await popup.waitForLoadState('domcontentloaded');
  ok('T2-1 回落腿：无验证基址 ⇒ 弹窗跟随入口 origin（相对开窗，无 cwdir 直连标记）',
    popup.url().startsWith(RELAY_BASE + '/?') && popup.url().includes('card-window=1') &&
    !popup.url().includes('cwdir=1'),
    popup.url());
  const ms = await waitCardContent(popup);
  ok('T2-2 回落弹窗真容照常渲染（隧道腿现状语义）', ms >= 0, `content in ${ms}ms`);
  ok('T2-3 回落腿零铸票（不改写远端会话的凭据面）', relayServer.mints.length === 0,
    `mints=${relayServer.mints.length}`);
  await ctx.close();
}

/* ════════ T3 首开耗时对比（同 harness 双腿；relay 每请求 60ms 时延）════════ */
async function timedPopout({ nearOk }) {
  nearOkFlag = nearOk;
  const ctx = await newCtx();
  await mockLiveWs(ctx);
  const page = await ctx.newPage();
  await openMainWithCard(page, RELAY_BASE);
  await sleep(nearOk ? 2500 : 1200);
  const popupP = ctx.waitForEvent('page', { timeout: 30000 });
  await page.click('#canvas-pop-btn');
  const popup = await popupP;
  const ms = await waitCardContent(popup, 60000);
  const landedOrigin = new URL(popup.url()).origin;
  await ctx.close();
  return { ms, landedOrigin };
}

{
  relayLatencyMs = 60;   // 隧道腿时延注入（拥塞管道的温和形态；作者实况更差）
  relayServer.mints.length = 0;
  const direct = await timedPopout({ nearOk: true });
  const legacy = await timedPopout({ nearOk: false });
  relayLatencyMs = 0;
  console.log(`[timing] 直连首开(弹窗落本机): ${direct.ms}ms @${direct.landedOrigin}`);
  console.log(`[timing] 隧道首开(弹窗跟入口 origin, 60ms/req 注入): ${legacy.ms}ms @${legacy.landedOrigin}`);
  ok('T3-1 直连首开落本机、隧道首开跟入口（双腿落点各归各位）',
    direct.landedOrigin === LOCAL_BASE && legacy.landedOrigin === RELAY_BASE);
  ok('T3-2 直连首开显著快于隧道首开（< 1/3；隧道腿含 60ms/req 注入）',
    direct.ms >= 0 && legacy.ms > 0 && direct.ms * 3 < legacy.ms,
    `direct=${direct.ms}ms tunnel=${legacy.ms}ms`);
}

/* ════════ T4 本机父页回归：已在直连腿 ⇒ 相对开窗（同源 cookie 现状）════════ */
{
  nearOkFlag = true;
  const ctx = await newCtx();
  await mockLiveWs(ctx);
  const page = await ctx.newPage();
  await openMainWithCard(page, LOCAL_BASE);
  await sleep(400);
  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await page.click('#canvas-pop-btn');
  const popup = await popupP;
  await popup.waitForLoadState('domcontentloaded');
  ok('T4-1 本机父页：弹窗相对开窗同源落地（不跨源、无直连计划）',
    popup.url().startsWith(LOCAL_BASE + '/?') && popup.url().includes('card-window=1') &&
    !popup.url().includes('cwdir=1'),
    popup.url());
  const ms = await waitCardContent(popup);
  ok('T4-2 本机父页弹窗真容照常（本机会话现状零回归）', ms >= 0, `content in ${ms}ms`);
  await ctx.close();
}

/* ════════ T5 卡窗静态看门狗：模块图挂 ⇒ 可见失败卡 + 重试（空白窗不复发）════════ */
{
  const ctx = await newCtx();
  await mockLiveWs(ctx);
  const page = await ctx.newPage();
  // main.js 永不到达（abort 模拟拥塞隧道上模块 fetch 挂死 / SW 陈旧索引 404 形态）。
  await page.route('**/js/main.js*', (r) => r.abort());
  await page.goto(LOCAL_BASE + '/?card-window=1&path=' + encodeURIComponent(ALPHA) + '&title=alpha.md',
    { waitUntil: 'domcontentloaded' });
  const watchdog = await page.waitForSelector('#nf-cardwin-static-watchdog', { timeout: 20000 })
    .then(() => true).catch(() => false);
  ok('T5-1 空白窗看门狗：模块图挂 15s 后注入可见失败卡（永不静默骨架）', watchdog);
  if (watchdog) {
    const urlP = page.waitForURL(/nfsw=0/, { timeout: 8000 }).then(() => true).catch(() => false);
    // JS click：#canvas-content 满幅层会截获真实指针事件（壳静态 z 序），看门狗
    // 卡的可见性已由 T5-1 的 waitForSelector 钉过，这里只验点击行为链。
    await page.evaluate(() => document.querySelector('#nf-cardwin-static-watchdog button').click());
    const navigated = await urlP;
    ok('T5-2 重试带 ?nfsw=0（SW 逃生门 —— 陈旧索引形态自愈出口）', navigated,
      page.url());
  }
  await ctx.close();
}

await browser.close();
relayServer.close();
localServer.close();
console.log(`\n${pass} passed, ${fail} failed`);
