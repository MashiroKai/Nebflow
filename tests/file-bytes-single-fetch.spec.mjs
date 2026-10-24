// file-bytes-single-fetch.spec.mjs — 文件字节消费面「账本单点·恰一次抓取」回归网
// （二次加载清剿批 2026-10-24 · 作者令 2026-10-24 15:41 逐字：「探查一下，还有没有
// 类似的问题。比如pop的文档，文件浏览器查看的文件等，全面核查，降低网络开支。」）
//
// #816（pop-image-single-fetch）修了 Pop 图片「预览 + Canvas 打开」双取数；本批把
// 同型面清剿（#816 REPORT §5 点名的 messages.js::attachInlineImage 同型双取数 +
// 盘点新发现的灯箱/markdown 文档图/搜索缩略双取数），逐面一条抓取计数断言：
//   T1  设备面直显 + Canvas 打开 ⇒ /api/nf-file 恰 1 次抓取（修前 = 2，#816 同型）；
//   T2  设备面重渲（历史重放/会话重开）×2 + 打开 ×2 ⇒ 仍 1 次（账本共享）；
//   T3  好友面直显 ⇒ 鉴权路恰 1 次 + 字节入账（`attach:<id>`，Canvas 预览同键
//       共享）；重渲（LRU 复用）零追加；
//   T4  灯箱（frame postMessage 同形）⇒ 已显示过的同件开灯箱零追加抓取（修前
//       每开必 reMint+全量重拉一次）；
//   T5  Markdown 文档：同一图在两个文档 pane 渲染 + Canvas 打开 ⇒ 恰 1 次抓取
//       （修前每 pane 一抓 + 打开一抓；票据换新后每次重渲再多一抓）；
//   T6  降级安全（设备面账本腿失败 ⇒ 回落既有票腿摘槽面；坏字节 ⇒ 逐出账本自愈
//       恰一次，失败面逐字现状）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/file-bytes-single-fetch.spec.mjs

import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/file-bytes-single-fetch/harness.html';
const MIME = {
  '.png': 'image/png', '.html': 'text/html', '.css': 'text/css',
  '.js': 'text/javascript', '.mjs': 'text/javascript', '.svg': 'image/svg+xml',
};

/** 最小 PNG 编码器（真 CRC/IHDR/IDAT/IEND）——loaded/naturalWidth 断言需要真图。 */
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
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
  ihdr[8] = 8;
  ihdr[9] = 2;
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
const PNG_V1 = makePng(1, 1, [255, 0, 0]);
const PNG_V2 = makePng(2, 1, [0, 96, 255]);
const SVG_BYTES = Buffer.from(
  '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><rect width="24" height="24" fill="#3b82f6"/></svg>'
);
/** 坏字节：.png 名 + 非图字节 ⇒ blob 解码必败（T6 自愈面）。 */
const BAD_BYTES = Buffer.from('this is not a png');

let port;
let server;
let tmpRoot;
let servedRoot;
let mintCount = 0;
/** 每次 GET /api/nf-file 的 [path]（抓取计数读数面）。 */
let nfFileGets = [];
/** 每次 GET /api/friends/attachments/:id 的 [id]（好友面抓取计数）。 */
let friendGets = [];

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

/** 票腿协议（与 nfTicket.js mintRound/ticketUrl 同形；成功面带 #538 同款头：
 *  Cache-Control private,max-age=300 + ETag + Last-Modified + If-None-Match ⇒ 304，
 *  mirror 真服务端 NfFileRoutes —— force-cache/304 语义只有在真头下才可断言）。 */
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
            out[pth] = { t, exp: Date.now() + 30 * 60 * 1000, size: fs.statSync(pth).size };
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
      nfFileGets.push(file || rawPath); // 计数面：失败抓取也计入
      const ok = real && file === real && fs.existsSync(real);
      if (ok) {
        const buf = fs.readFileSync(real);
        const etag = `"${buf.length}-${buf.slice(0, 64).toString('hex')}"`;
        const inm = req.headers['if-none-match'];
        if (inm && inm === etag) {
          res.writeHead(304, { 'Cache-Control': 'private, max-age=300', ETag: etag });
          res.end();
          return;
        }
        res.writeHead(200, {
          'Content-Type': MIME[path.extname(real).toLowerCase()] || 'application/octet-stream',
          'Content-Length': String(buf.length),
          'Cache-Control': 'private, max-age=300',
          ETag: etag,
          'Last-Modified': new Date(fs.statSync(real).mtimeMs).toUTCString(),
        });
        res.end(buf);
      } else {
        res.writeHead(404);
        res.end('not found');
      }
      return;
    }
    if (u.pathname.startsWith('/api/friends/attachments/') && req.method === 'GET') {
      const id = decodeURIComponent(u.pathname.split('/').pop());
      friendGets.push(id);
      const blob = FRIEND_BLOBS.get(id);
      if (!blob) { res.writeHead(404); res.end('not found'); return; }
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': String(blob.length),
        'Cache-Control': 'private, max-age=300',
      });
      res.end(blob);
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

/** 好友面鉴权路字节表（id → bytes）。 */
const FRIEND_BLOBS = new Map();

test.beforeAll(async () => {
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'nf-bytes-single-fetch-'));
  fs.symlinkSync(path.join(REPO_ROOT, 'src'), path.join(tmpRoot, 'src'), 'dir');
  fs.symlinkSync(path.join(REPO_ROOT, 'tests'), path.join(tmpRoot, 'tests'), 'dir');
  // 前端 vendored 资产面（markdown 渲染的 marked/katex 等从 /vendor/… 装载）。
  fs.symlinkSync(path.join(REPO_ROOT, 'src/main/resources/web/vendor'), path.join(tmpRoot, 'vendor'), 'dir');
  servedRoot = path.join(tmpRoot, 'served');
  fs.mkdirSync(servedRoot, { recursive: true });
  fs.writeFileSync(path.join(servedRoot, 'plot.png'), PNG_V1);
  fs.writeFileSync(path.join(servedRoot, 'diagram.svg'), SVG_BYTES);
  fs.writeFileSync(path.join(servedRoot, 'broken.png'), BAD_BYTES);
  FRIEND_BLOBS.set('att-1', PNG_V1);
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
  friendGets = [];
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction('window.__ready === true');
  // messages.js 依赖图重——导入失败必须显性红（禁静默假绿）。
  const err = await page.evaluate(() => window.__importError);
  expect(err, `messages.js import failed: ${err}`).toBe('');
});

const imgPath = () => path.join(servedRoot, 'plot.png');
const badPath = () => path.join(servedRoot, 'broken.png');

/** T1（spec 钉①）：设备面直显 + Canvas 打开 = 恰一次抓取（#816 同型，修前 = 2）。 */
test('T1: 设备面内联直显 + Canvas 打开 ⇒ nf-file 恰一次抓取（修前 = 两次）', async ({ page }) => {
  const inline = await page.evaluate((pp) => window.__deviceInline(pp), imgPath());
  expect(inline.ok).toBe(true);
  expect(inline.boxInDom).toBe(true);
  expect(inline.isBlob).toBe(true);      // 账本腿：blob URL 直显（字节已入账）
  expect(inline.loaded).toBe(true);
  expect(nfFileGets).toHaveLength(1);    // 直显抓取恰一次

  const stats = await page.evaluate(() => window.__cacheStats());
  expect(stats.entries).toBe(1);         // nf:<path> 已入账

  // Canvas 打开（image viewer 同走 nfViewerUrl）⇒ 账本命中 ⇒ 零新增抓取。
  const open = await page.evaluate((ctx) => window.__openInCanvas(ctx), {
    absPath: imgPath(), fileName: 'plot.png', size: PNG_V1.length,
  });
  expect(open.found).toBe(true);
  expect(open.isBlob).toBe(true);
  expect(open.loaded).toBe(true);
  expect(nfFileGets).toHaveLength(1);    // 🔴 修前此处为 2（#816 REPORT §5 点名同型面）
});

/** T2（spec 钉②）：重渲共享——同件直显 ×2（历史重放/会话重开）+ 打开 ×2 ⇒ 仍 1 次。 */
test('T2: 设备面重渲 ×2 + Canvas 打开 ×2 ⇒ 仍恰一次抓取', async ({ page }) => {
  await page.evaluate((pp) => window.__deviceInline(pp), imgPath());
  expect(nfFileGets).toHaveLength(1);
  // 第二张卡（同件重渲）= 账本命中，零抓取。
  const second = await page.evaluate((pp) => window.__deviceInline(pp), imgPath());
  expect(second.isBlob).toBe(true);
  expect(second.loaded).toBe(true);
  expect(nfFileGets).toHaveLength(1);
  // Canvas 打开两次（重开同件）= 两次命中，零抓取。
  for (let i = 0; i < 2; i++) {
    const open = await page.evaluate((ctx) => window.__openInCanvas(ctx), {
      absPath: imgPath(), fileName: 'plot.png', size: PNG_V1.length,
    });
    expect(open.loaded).toBe(true);
  }
  expect(nfFileGets).toHaveLength(1);
  const stats = await page.evaluate(() => window.__cacheStats());
  expect(stats.entries).toBe(1); // 一份字节，多面共享
});

/** T3（spec 钉③）：好友面直显 = 鉴权路恰 1 次 + 字节入账（Canvas 预览同键共享）+ LRU 复用。 */
test('T3: 好友面直显 ⇒ 鉴权路恰一次 + attach:<id> 入账共享', async ({ page }) => {
  const inline = await page.evaluate((ctx) => window.__friendInline(ctx.id, ctx.name, ctx.size), {
    id: 'att-1', name: 'photo.png', size: PNG_V1.length,
  });
  expect(inline.ok).toBe(true);
  expect(inline.isBlob).toBe(true);
  expect(inline.loaded).toBe(true);
  expect(friendGets).toEqual(['att-1']); // 鉴权路恰一次

  // 字节入账（键 = Canvas 预览腿 previewFriendAttachment 同一把 attach:<id> ⇒
  // 气泡与 Canvas 预览共享同一份字节）。
  const ledger = await page.evaluate(() => window.__ledgerBlob('attach:att-1'));
  expect(ledger).not.toBeNull();
  expect(ledger.size).toBe(PNG_V1.length);

  // 同件重渲（新卡）= inlineObjectUrls LRU 命中 ⇒ 零新增往返。
  const second = await page.evaluate((ctx) => window.__friendInline(ctx.id, ctx.name, ctx.size), {
    id: 'att-1', name: 'photo.png', size: PNG_V1.length,
  });
  expect(second.loaded).toBe(true);
  expect(friendGets).toEqual(['att-1']);
});

/** T4（spec 钉④）：灯箱（frame postMessage 同形）= 已显示过的同件零追加抓取。 */
test('T4: 灯箱打开已入账同件 ⇒ 零追加抓取（修前每开必 reMint 全量重拉）', async ({ page }) => {
  await page.evaluate((pp) => window.__deviceInline(pp), imgPath());
  expect(nfFileGets).toHaveLength(1);
  const ticketed = `/api/nf-file?path=${encodeURIComponent(imgPath())}`;
  const lb = await page.evaluate((ctx) => window.__openLightbox(ctx.src, 'plot', ctx.path), {
    src: ticketed, path: imgPath(),
  });
  expect(lb.isBlob).toBe(true);   // 账本命中 = blob URL（不再新铸票据重拉）
  expect(lb.loaded).toBe(true);
  expect(lb.failed).toBe(false);
  expect(nfFileGets).toHaveLength(1); // 🔴 修前此处为 2（reMint + fetch 必再抓一次）
  await page.evaluate(() => window.__closeLightbox());

  // 关掉重开（第二 open）= 仍零追加。
  const lb2 = await page.evaluate((ctx) => window.__openLightbox(ctx.src, 'plot', ctx.path), {
    src: ticketed, path: imgPath(),
  });
  expect(lb2.loaded).toBe(true);
  expect(nfFileGets).toHaveLength(1);
});

/** T5（spec 钉⑤）：Markdown 文档图——两个 pane 渲染 + Canvas 打开 = 恰一次抓取。 */
test('T5: Markdown 文档图两 pane 渲染 + Canvas 打开 ⇒ 恰一次抓取（修前每面各一抓）', async ({ page }) => {
  const mdContent = '![plot](plot.png)\n';
  const abs = path.join(servedRoot, 'doc.md');
  const first = await page.evaluate((ctx) => window.__renderMd('md-pane-1', ctx.content, ctx.abs), {
    content: mdContent, abs,
  });
  expect(first).toHaveLength(1);
  expect(first[0].isBlob).toBe(true);   // 出帧前经账本解析 = blob URL
  expect(first[0].loaded).toBe(true);
  expect(first[0].natW).toBe(1);
  // 账本腿成功 ⇒ 回落属性按设计在场（自愈腿 = blob 解码败时回落票据 URL 恰一次；
  // markdown.js 两段替换语义：finalUrl ≠ 票据 URL 时 fb 占位符替换为票据 URL）。
  expect(first[0].fallback).toContain('/api/nf-file');
  expect(nfFileGets).toHaveLength(1);

  // 第二个 pane（新标签重开同文档）= 账本命中，零追加。
  const second = await page.evaluate((ctx) => window.__renderMd('md-pane-2', ctx.content, ctx.abs), {
    content: mdContent, abs,
  });
  expect(second[0].isBlob).toBe(true);
  expect(second[0].loaded).toBe(true);
  expect(nfFileGets).toHaveLength(1);

  // 文档图点开 Canvas（image viewer）= 同键命中，零追加。
  const open = await page.evaluate((ctx) => window.__openInCanvas(ctx), {
    absPath: imgPath(), fileName: 'plot.png', size: PNG_V1.length,
  });
  expect(open.loaded).toBe(true);
  expect(nfFileGets).toHaveLength(1);
});

/** T6（spec 钉⑥降级安全）：账本腿失败 ⇒ 回落票腿摘槽面；坏字节 ⇒ 逐出账本自愈恰一次。 */
test('T6: 好图直显后内容换成坏字节（事件失效）⇒ blob 解码败逐出账本、票腿自愈恰一次、终态摘槽', async ({ page }) => {
  // 先用好字节入账。
  await page.evaluate((pp) => window.__deviceInline(pp), imgPath());
  expect(nfFileGets).toHaveLength(1);
  // 引擎写路径广播 `nf-cache-invalidate {path}` ⇒ 账本逐出；盘上内容换成坏字节。
  const dropped = await page.evaluate((pp) => window.__invalidate(pp), imgPath());
  expect(dropped).toBe(1);
  fs.writeFileSync(imgPath(), BAD_BYTES);

  // 重渲：账本未命中 ⇒ 取数（第 2 次抓取）入账坏字节 ⇒ blob 解码失败 ⇒ 自愈腿
  // （nfCacheDrop + 既有票腿恰一次）⇒ 票腿字节同样非图 ⇒ 终态摘槽（失败面逐字现状）。
  const again = await page.evaluate((pp) => window.__deviceInline(pp), imgPath());
  expect(again.boxInDom).toBe(false); // 终态：直显槽已摘（禁静默无反应，卡片原样）
  expect(nfFileGets.length).toBeGreaterThanOrEqual(3); // 直显1 + 重取1 + 自愈票腿1
  // 坏字节不粘死账本：自愈已把该键逐出。
  const stats = await page.evaluate(() => window.__cacheStats());
  expect(stats.entries).toBe(0);
});

/** T7：SVG 内联化（html.js）——同文档内 img 取件后，内联 fetch force-cache 零追加。 */
test('T7: HTML 文档 SVG 内联化 ⇒ 同一 SVG 恰一次网络抓取', async ({ page }) => {
  const abs = path.join(servedRoot, 'doc.html');
  const r = await page.evaluate((ctx) => window.__renderHtmlDoc(ctx.content, ctx.abs), {
    content: '<!DOCTYPE html><html><body><img src="diagram.svg"></body></html>', abs,
  });
  expect(r.hasFrame).toBe(true);
  expect(r.svgInlined.imgGone).toBe(true); // 内联化成功（img 已被 <svg> 替换）
  const svgFetches = nfFileGets.filter((p) => p.endsWith('diagram.svg'));
  expect(svgFetches).toHaveLength(1);      // 🔴 修前 = img 一抓 + 内联 fetch 依赖启发式
});
