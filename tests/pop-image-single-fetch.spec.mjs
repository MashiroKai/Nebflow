// pop-image-single-fetch.spec.mjs — Pop 图片「预览 + Canvas 打开 = 恰一次网络抓取」
// （imgdoor 二次加载批 2026-10-24 · 作者报障 2026-10-24 15:0x 逐字：「我看你pop的
// 图片 预览会加载一次 点击在canvas打开好像又会加载一次」）的回归网。
//
// 根因（修前形态）：popArtifacts.hydrateMedia 的票腿 = 裸 `<img src=票据URL>` 直取，
// 字节**不进 nfFileCache 账本**；Canvas 打开（image viewer → nfViewerSource）按
// `nf:<path>` 查账未命中 ⇒ 按「未命中取数」把同一件**再全量下载一次**（取数腿
// `cache:'no-store'`，浏览器 HTTP 缓存帮不上）。两条取数路零共享 ⇒ 同图两次抓取。
//
// 修复（前端单点 popArtifacts.hydrateMedia）：图片脸字节改经 `nfViewerUrl` 账本单点
// ——预览取数即入账，Canvas 打开（同一单点）命中零取数；灯箱复用同一 hydrateMedia
// 同样零追加。失败回落 legacyTicketFace（改前失败面逐字），blob 解码失败先逐出账本
// 再走既有 reMint 自愈链（坏字节不粘死账本）。
//
// spec 钉住（作者令三条）：
//   T1  同图预览 + 打开只产生一次网络抓取（playwright 计数验证）；
//   T2  缓存命中路径（重开脸/再打开零取数）；
//   T3  内容变更后缓存失效（事件失效逐出 ⇒ 重取新字节，不拿旧图）；
//   T4  降级安全（不存在的文件 ⇒ 既有终态失败注记恰一次，账本腿不改变失败面）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/pop-image-single-fetch.spec.mjs

import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-image-single-fetch/harness.html';
const MIME = { '.png': 'image/png', '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript' };

/** 最小 PNG 编码器（真 CRC/IHDR/IDAT/IEND）：1×1 与 2×1 两版字节 ⇒
 *  T3 用 naturalWidth 断言「重取的是新字节」，不依赖缓存陈旧面。 */
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}
function makePng(w, h, [r, g, b]) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 2;  // color type RGB
  const stride = w * 3 + 1;
  const raw = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = y * stride + 1 + x * 3;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}
const PNG_V1 = makePng(1, 1, [255, 0, 0]); // 1×1 红
const PNG_V2 = makePng(2, 1, [0, 96, 255]); // 2×1 蓝（字节不同 ⇒ naturalWidth 可断言）

let port;
let server;
let tmpRoot;
let servedRoot;
let mintCount = 0;
/** 每次 GET /api/nf-file 的 [path] 记录（抓取计数 = 计数验证的读数面）。 */
let nfFileGets = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

/** 票腿协议（与 nfTicket.js mintRound/ticketUrl 同形）：
 *  POST /api/nf-ticket  {sessionId, paths[]} → {tickets: {path: {t, exp}}, rejected[]}
 *  GET  /api/nf-file?path=…&ticket=…        → 200 字节（计入 nfFileGets）/ 404。 */
function startOrigin(p) {
  const tickets = new Map(); // ticket → path
  server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://127.0.0.1');
    if (u.pathname === '/api/nf-ticket' && req.method === 'POST') {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        mintCount += 1;
        let paths = [];
        try { paths = (JSON.parse(body).paths) || []; } catch { /* malformed → 全拒 */ }
        const out = {};
        const rejected = [];
        for (const pth of paths) {
          if (typeof pth === 'string' && fs.existsSync(pth)) {
            const t = 'tk-' + Math.random().toString(36).slice(2);
            tickets.set(t, pth);
            out[pth] = { t, exp: Date.now() + 30 * 60 * 1000 };
          } else {
            rejected.push({ path: pth, reason: 'not-found' });
          }
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ tickets: out, rejected }));
      });
      return;
    }
    if (u.pathname === '/api/nf-file' && req.method === 'GET') {
      const rawPath = u.searchParams.get('path') || '';
      const tk = u.searchParams.get('ticket') || '';
      const real = tickets.get(tk);
      let file = null;
      try { file = rawPath && decodeURIComponent(rawPath); } catch { file = null; }
      nfFileGets.push(file || rawPath); // 计数面：404 的失败抓取也计入（T4 读数）
      if (real && file === real && fs.existsSync(real)) {
        res.writeHead(200, {
          'Content-Type': MIME[path.extname(real).toLowerCase()] || 'application/octet-stream',
          'Content-Length': String(fs.statSync(real).size),
        });
        fs.createReadStream(real).pipe(res);
      } else {
        res.writeHead(404);
        res.end('not found');
      }
      return;
    }
    // 静态：防穿越后从 tmpRoot 出件（harness 页面 + 前端模块树）。
    const rel = decodeURIComponent(u.pathname).replace(/^\/+/, '');
    if (rel.includes('..')) { res.writeHead(404); res.end(); return; }
    const file = path.join(tmpRoot, rel);
    if (!file.startsWith(tmpRoot)) { res.writeHead(404); res.end(); return; }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(p, '127.0.0.1', resolve));
}

test.beforeAll(async () => {
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nf-pop-single-fetch-'));
  fs.symlinkSync(path.join(REPO_ROOT, 'src'), path.join(tmpRoot, 'src'), 'dir');
  fs.symlinkSync(path.join(REPO_ROOT, 'tests'), path.join(tmpRoot, 'tests'), 'dir');
  servedRoot = path.join(tmpRoot, 'served');
  fs.mkdirSync(servedRoot, { recursive: true });
  fs.writeFileSync(path.join(servedRoot, 'plot.png'), PNG_V1);
  port = await freePort();
  await startOrigin(port);
});

test.afterAll(async () => {
  if (server) await new Promise((r) => server.close(r));
  if (tmpRoot) fs.rmSync(tmpRoot, { recursive: true, force: true });
});

test.beforeEach(async ({ page }) => {
  mintCount = 0;
  nfFileGets = [];
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction('window.__ready === true');
});

const imgPath = () => path.join(servedRoot, 'plot.png');

/** T1（spec 钉①）：同图预览 + Canvas 打开 = 恰一次网络抓取。 */
test('T1: Pop 图片预览 + Canvas 打开 ⇒ nf-file 恰一次抓取（修前 = 两次）', async ({ page }) => {
  // 预览脸：hydrateMedia 账本腿 ⇒ 未命中取数（第 1 次）+ 入账。
  const mounted = await page.evaluate((pp) => window.__mountPopRow([
    { kind: 'image', name: 'plot.png', path: pp },
  ]), imgPath());
  expect(mounted).toBe(true);
  expect(nfFileGets).toHaveLength(1); // 预览抓取恰一次

  const faces = await page.evaluate(() => window.__popImgs());
  expect(faces).toHaveLength(1);
  expect(faces[0].isBlob).toBe(true);   // 账本腿：blob URL 直显（字节已入账）
  expect(faces[0].loaded).toBe(true);

  const stats = await page.evaluate(() => window.__cacheStats());
  expect(stats.enabled).toBe(true);
  expect(stats.entries).toBe(1);        // 字节已入账（nf:<path>）

  // Canvas 打开：image viewer 同走 nfViewerUrl ⇒ 账本命中 ⇒ 零新增抓取。
  const open = await page.evaluate((ctx) => window.__openInCanvas(ctx), {
    absPath: imgPath(), fileName: 'plot.png', size: PNG_V1.length,
  });
  expect(open.found).toBe(true);
  expect(open.isBlob).toBe(true);       // 命中路径 = blob URL（非票据 URL）
  expect(open.loaded).toBe(true);
  expect(nfFileGets).toHaveLength(1);   // 🔴 修前此处为 2 —— 打开又全量下载一次
});

/** T2（spec 钉②）：缓存命中路径——重开预览脸 / 再开 Canvas 都零取数。 */
test('T2: 缓存命中路径 ⇒ 重开脸、再打开均零新增抓取', async ({ page }) => {
  await page.evaluate((pp) => window.__mountPopRow([
    { kind: 'image', name: 'plot.png', path: pp },
  ]), imgPath());
  expect(nfFileGets).toHaveLength(1);

  // 第二条预览脸（模拟历史重放/另一条气泡）＝账本命中，零抓取。
  await page.evaluate((pp) => window.__mountPopRow([
    { kind: 'image', name: 'plot.png', path: pp },
  ]), imgPath());
  expect(await page.evaluate(() => window.__popImgs())).toHaveLength(2);
  expect(nfFileGets).toHaveLength(1);

  // Canvas 打开两次（重开同件）＝两次命中，零抓取。
  for (let i = 0; i < 2; i++) {
    const open = await page.evaluate((ctx) => window.__openInCanvas(ctx), {
      absPath: imgPath(), fileName: 'plot.png', size: PNG_V1.length,
    });
    expect(open.found).toBe(true);
    expect(open.loaded).toBe(true);
  }
  expect(nfFileGets).toHaveLength(1);
  const stats = await page.evaluate(() => window.__cacheStats());
  expect(stats.entries).toBe(1); // 一份字节，多面共享
});

/** T3（spec 钉③）：内容变更后缓存失效——事件失效逐出 ⇒ 重取新字节（不拿旧图）。 */
test('T3: 内容变更（事件失效）后 ⇒ 账本逐出、重取新字节', async ({ page }) => {
  await page.evaluate((pp) => window.__mountPopRow([
    { kind: 'image', name: 'plot.png', path: pp },
  ]), imgPath());
  expect(nfFileGets).toHaveLength(1);
  const first = await page.evaluate(() => window.__popImgs());
  expect(first[0].naturalWidth).toBe(1); // v1 = 1×1

  // 引擎写路径广播 `nf-cache-invalidate {path}` ⇒ main.js 调的同一函数。
  const dropped = await page.evaluate((pp) => window.__invalidate(pp), imgPath());
  expect(dropped).toBe(1);
  const stats = await page.evaluate(() => window.__cacheStats());
  expect(stats.entries).toBe(0); // 账本已逐出

  // 文件内容变了（1×1 → 2×1）。
  fs.writeFileSync(imgPath(), PNG_V2);

  // 重开：账本未命中 ⇒ 重取（第 2 次抓取）且字节是新的（naturalWidth = 2）。
  await page.evaluate((pp) => window.__mountPopRow([
    { kind: 'image', name: 'plot.png', path: pp },
  ]), imgPath());
  expect(nfFileGets).toHaveLength(2);
  const second = await page.evaluate(() => window.__popImgs());
  expect(second[second.length - 1].loaded).toBe(true);
  expect(second[second.length - 1].naturalWidth).toBe(2); // 新字节，非陈旧缓存

  // 变更后的 Canvas 打开仍命中新字节、零新增抓取。
  const open = await page.evaluate((ctx) => window.__openInCanvas(ctx), {
    absPath: imgPath(), fileName: 'plot.png', size: PNG_V2.length,
  });
  expect(open.loaded).toBe(true);
  expect(open.naturalWidth).toBe(2);
  expect(nfFileGets).toHaveLength(2);
});

/** T4（spec 钉④降级安全）：不存在的文件 ⇒ 账本腿失败回落既有票腿，
 *  终态失败注记恰一次（禁静默契约不破），失败面与改前逐字。 */
test('T4: 文件不存在 ⇒ 账本腿失败回落票腿，终态失败注记恰一次', async ({ page }) => {
  const gone = path.join(servedRoot, 'gone.png');
  const mounted = await page.evaluate((pp) => window.__mountPopRow([
    { kind: 'image', name: 'gone.png', path: pp },
  ]), gone);
  expect(mounted).toBe(true);

  const faces = await page.evaluate(() => window.__popImgs());
  expect(faces).toHaveLength(1);
  expect(faces[0].failed).toBe(true);
  expect(faces[0].loaded).toBe(false);

  const notes = await page.evaluate(() => window.__failedNotes());
  expect(notes).toHaveLength(1); // 终态闩：恰一行（失败面逐字保留）
  expect(notes[0]).toContain('gone.png');
  expect(nfFileGets.length).toBeGreaterThanOrEqual(2); // 账本腿 + 票腿 + reMint 的失败抓取都发生过
  expect(await page.evaluate(() => window.__cacheStats())).toMatchObject({ entries: 0 }); // 失败件不入账
});
