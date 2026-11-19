// pop-optimistic-evidence.mjs — Pop 文件卡「乐观打开」批（2026-11-18）的证据采集器
// （形态沿用 tests/*-shots.mjs：真 app + 真链路，只落图/读数，不作为闸）。
//
// 产出（落 --out 目录，缺省 evidence/）：
//   mobile-<theme>.gif         手机形态 375×812 交互 GIF（点击 → 占位环走进度 → 内容）
//   shots/mobile-<theme>-NN.png 逐帧原始 PNG（GIF 素材，可逐帧复核）
//   shots/desktop-{success,failure,cancelled}.png  桌面三态
//   timing.json                占位时序读数（点击 → 占位出现 → 请求起 / 止，毫秒）
//
// 字节腿由**本脚本自己**的 HTTP 服务器流式供给（Content-Length + 分块延时）——
// 不用 page.route：只有真分块才让进度环走**确定性**读数；同时 /api/nf-ticket 也由
// 本服务器应答（真票据形状）。/ws 仍走 Playwright 的 routeWebSocket。
//
// 运行：node tests/pop-optimistic-evidence.mjs [--out <dir>]

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.EV_PORT || 8171);
const BASE = `http://127.0.0.1:${PORT}`;
const outFlag = process.argv.indexOf('--out');
const OUT = outFlag > -1 ? process.argv[outFlag + 1] : resolve(HERE, '../evidence/pop-optimistic-canvas');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const FIX = '/tmp/pop-optimistic';
const PHOTO = join(FIX, 'photo.png');       // 大图（流式分批送，让环走确定性进度）
const IMG_PATH = FIX + '/photo.png';
const BAD_PATH = FIX + '/denied.png';

if (!existsSync(PHOTO)) {
  console.error(`[evidence] missing ${PHOTO} — 生成: ffmpeg -f lavfi -i testsrc=size=1200x700:rate=1 -frames:v 1 -y ${PHOTO}`);
  process.exit(2);
}
const PHOTO_BYTES = readFileSync(PHOTO);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css', '.json': 'application/json', '.png': 'image/png',
};

// ── 证据服务器：静态面 + /api/nf-ticket + /api/nf-file（真分块流）──────────────
let fileFail = false;      // true ⇒ /api/nf-file 回 403 credential-denied
let chunkDelayMs = 0;      // >0 ⇒ 分块送字节（确定性进度环）
let headDelayMs = 0;       // >0 ⇒ 应答头也延后（取消腿要让 fetch 处于在飞态）
const streamTicks = [];

const server = createServer(async (req, res) => {
  const u = new URL(req.url, BASE);
  const p = decodeURIComponent(u.pathname);

  if (p === '/api/nf-ticket') {
    let paths = [];
    try { paths = JSON.parse(await readBody(req)).paths || []; } catch { paths = []; }
    const tickets = {};
    for (const x of paths) tickets[x] = { t: 'ev-ticket-' + Math.random().toString(36).slice(2), exp: Date.now() + 3600000, uses: -1 };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ tickets, rejected: [] }));
    return;
  }
  if (p === '/api/canvas-writer' || p === '/api/canvas-writer/claim') {
    // 写权授予（证据采集用；否则页面会挂「Canvas 保存失败」提示，是 harness 噪声）
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ role: 'writer', epoch: 1 }));
    return;
  }
  if (p === '/api/nf-file') {
    if (headDelayMs) await sleep(headDelayMs);
    if (fileFail) {
      res.writeHead(403, { 'Content-Type': 'text/plain', 'X-Nf-Reason': 'credential-denied' });
      res.end('denied');
      return;
    }
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': String(PHOTO_BYTES.length) });
    if (!chunkDelayMs) { res.end(PHOTO_BYTES); return; }
    const CHUNK = Math.max(1, Math.ceil(PHOTO_BYTES.length / 24));
    for (let off = 0; off < PHOTO_BYTES.length; off += CHUNK) {
      res.write(PHOTO_BYTES.subarray(off, off + CHUNK));
      streamTicks.push({ at: Date.now(), sent: Math.min(off + CHUNK, PHOTO_BYTES.length), total: PHOTO_BYTES.length });
      await sleep(chunkDelayMs);
    }
    res.end();
    return;
  }
  if (p.startsWith('/api/')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
    return;
  }

  let f = p === '/' ? '/index.html' : p;
  if (f === '/sw.js') { res.writeHead(404); res.end('nf'); return; }
  const file = join(WEB_DIR, f);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('nf'); }
});
function readBody(req) {
  return new Promise((r) => { let b = ''; req.on('data', (c) => { b += c; }); req.on('end', () => r(b)); });
}
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── 页面探针（与 spec 同型：点击 / tab 出现 / 请求起止）────────────────────────
const PROBE = () => {
  /** @type {any} */ (window).__po = { clickAt: null, tabAt: null, nfStart: null, nfEnd: null, count: 0, aborted: 0 };
  const of = window.fetch;
  /** @type {any} */ (window).fetch = function (input) {
    const u = typeof input === 'string' ? input : ((input && input.url) || '');
    const hit = u.includes('/api/nf-file');
    if (hit) { const p = /** @type {any} */ (window).__po; p.count++; if (p.nfStart == null) p.nfStart = performance.now(); }
    const out = of.apply(this, arguments);
    if (!hit) return out;
    return Promise.resolve(out).then(
      (r) => { /** @type {any} */ (window).__po.nfEnd = performance.now(); return r; },
      (e) => { const p = /** @type {any} */ (window).__po; p.nfEnd = performance.now(); if (e && e.name === 'AbortError') p.aborted++; throw e; },
    );
  };
  const mo = new MutationObserver(() => {
    const p = /** @type {any} */ (window).__po;
    if (p.tabAt != null) return;
    if (document.querySelector('.canvas-tab-pane[data-tab-id^="file:"]')) p.tabAt = performance.now();
  });
  const arm = () => mo.observe(document.body, { childList: true, subtree: true });
  if (document.body) arm(); else document.addEventListener('DOMContentLoaded', arm);
};

async function boot({ width = 1600, height = 1000, colorScheme = 'light' } = {}) {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  await ctx.addInitScript(PROBE);
  const page = await ctx.newPage();
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', config: JSON.stringify({ features: {} }) }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForFunction(() => !!document.getElementById('activity-bar'), null, { timeout: 20000 });
  if (width <= 768) await page.waitForFunction(() => !!(/** @type {any} */ (window).__nfMobileLayer && /** @type {any} */ (window).__nfMobileLayer.active), null, { timeout: 8000 }).catch(() => {});
  await sleep(500);
  return { browser, page };
}

const click = (page, path, name) => page.evaluate(
  ([pp, nn]) => import('/js/popArtifacts.js').then((m) => {
    const p = /** @type {any} */ (window).__po; p.clickAt = performance.now();
    return m.openPopArtifact(pp, nn);
  }), [path, name],
);

const probe = (page) => page.evaluate(() => {
  const p = /** @type {any} */ (window).__po;
  const e = document.querySelector('[data-nf-progress]');
  return {
    clickAt: p.clickAt, tabAt: p.tabAt, nfStart: p.nfStart, nfEnd: p.nfEnd, nfCount: p.count, aborted: p.aborted,
    progress: e ? e.getAttribute('data-nf-progress') : null,
    pct: e ? e.getAttribute('data-nf-pct') : null,
  };
});

const timing = { note: 'ms, performance.now() 基准；tabAt = 占位页签出现帧；nfStart/nfEnd = /api/nf-file 请求起止', runs: [] };

// ══ 1. 手机形态 375×812：逐帧采集 + GIF（暗 / 亮两档）════════════════════════
for (const theme of ['dark', 'light']) {
  chunkDelayMs = 70;                     // 24 块 × 70ms ≈ 1.7s 的分块流
  fileFail = false;
  const { browser, page } = await boot({ width: 375, height: 812, colorScheme: theme });
  const id = 'file:' + IMG_PATH;
  const frames = [];
  let n = 0;
  const shot = async (tag) => {
    const file = join(SHOTS, `mobile-${theme}-${String(++n).padStart(2, '0')}-${tag}.png`);
    await page.screenshot({ path: file });
    frames.push(file);
  };
  await shot('before-click');
  click(page, IMG_PATH, 'photo.png');             // 不 await：立刻抓占位
  await page.waitForFunction((tid) => !!document.querySelector(`.canvas-tab-pane[data-tab-id="${tid}"]`), id, { timeout: 5000 });
  await shot('placeholder-ring');
  const t0 = await probe(page);
  for (const d of [150, 250, 300, 300, 300, 400]) { await sleep(d); await shot('ring'); }
  await page.waitForSelector('.canvas-tab-pane img', { timeout: 10000 });
  await sleep(250);
  await shot('content');
  const t1 = await probe(page);
  timing.runs.push({ theme, viewport: '375x812', click: t0.clickAt, tabAt: t0.tabAt, nfStart: t0.nfStart, nfEnd: t1.nfEnd, nfCount: t1.nfCount,
    tabAfterClickMs: t0.tabAt - t0.clickAt, responseAfterClickMs: t1.nfEnd - t0.clickAt });
  await browser.close();
  console.log(`[evidence] mobile-${theme}: ${frames.length} frames`);
}

// ══ 2. 桌面三态（成功 / 失败 + 重试 / 取消）══════════════════════════════════
{
  chunkDelayMs = 0; fileFail = false;
  const { browser, page } = await boot({});
  await click(page, IMG_PATH, 'photo.png');
  await page.waitForSelector('.canvas-tab-pane img', { timeout: 10000 });
  await sleep(300);
  await page.screenshot({ path: join(SHOTS, 'desktop-success.png') });
  await browser.close();
}
{
  chunkDelayMs = 0; fileFail = true;
  const { browser, page } = await boot({});
  await click(page, BAD_PATH, 'denied.png');
  await page.waitForSelector('[data-nf-error]', { timeout: 8000 });
  await sleep(250);
  await page.screenshot({ path: join(SHOTS, 'desktop-failure.png') });
  const text = await page.textContent('[data-nf-error]');
  timing.failureReasonText = text;
  // 重试腿：改路 200 → 点重试
  fileFail = false;
  await page.click('[data-nf-retry]');
  await page.waitForSelector('.canvas-tab-pane img', { timeout: 10000 }).catch(() => {});
  await sleep(250);
  await page.screenshot({ path: join(SHOTS, 'desktop-retry-success.png') });
  await browser.close();
}
{
  chunkDelayMs = 0; headDelayMs = 2500; fileFail = false;
  const { browser, page } = await boot({});
  const id = 'file:' + IMG_PATH;
  await click(page, IMG_PATH, 'photo.png');
  await page.waitForFunction((tid) => !!document.querySelector(`.canvas-tab-pane[data-tab-id="${tid}"]`), id, { timeout: 5000 });
  await sleep(300);
  await page.screenshot({ path: join(SHOTS, 'desktop-cancelling.png') });
  await page.evaluate((tid) => import('/js/canvas.js').then((c) => c.closeTab(tid)), id);
  await sleep(600);
  const st = await probe(page);
  timing.cancelAborted = st.aborted;
  headDelayMs = 0;
  await page.screenshot({ path: join(SHOTS, 'desktop-cancelled.png') });
  await browser.close();
}

writeFileSync(join(OUT, 'timing.json'), JSON.stringify(timing, null, 2));
writeFileSync(join(OUT, 'stream-ticks.json'), JSON.stringify(streamTicks.slice(0, 60), null, 2));
server.close();
console.log(`[evidence] wrote ${OUT}/timing.json`);
