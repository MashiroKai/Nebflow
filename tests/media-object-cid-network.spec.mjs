#!/usr/bin/env node
// media-object-cid-network.spec.mjs — 「按对象传输 / 内容寻址取字节出口」批
// （2026-11-18）的**网络读数装置**第二件：入口 `/api/nf-obj/<cid>/<name>`。
//
// 与同批 `media-object-cache-network.spec.mjs`（叠卡→大图复用读数）同族做法：
//   ① 真 HTTP 服务器（不是 page.route 拦截）—— 静态面 = 本仓 src/main/resources/web；
//      媒体面 = 与真网关**同形状同响应头**的端点：
//        POST /api/nf-ticket           → { tickets: {path:{t,exp,size,cid}} }（cid 并键）
//        GET  /api/nf-obj/<cid>/<name> → 内容字节 + 强 ETag `"<cid>"` +
//                                        `Cache-Control: private, max-age=31536000, immutable`
//                                        （值逐字 = NfFileRoutes.NfObjCacheControl）
//                                        + Last-Modified；If-None-Match 命中 ⇒ 304
//                                        + 凭据 = 会话 cookie（**URL 里没有凭据**）
//        GET  /api/nf-file?path=&ticket= → 旧票据路由逐字不变（过渡期双读）
//   ② 真浏览器（Chromium）+ CDP `Network.*` —— 请求数/URL/响应头/传输字节全部来自
//      浏览器网络域；**权威读数 = 服务端是否真收到**（缓存承接的请求根本不到服务端）。
//   ③ 手机全屏形态：390×844 / dsf 2 / isMobile / hasTouch / body.mobile-layer。
//   ④ 驱动**产品既有模块**（零复制）：
//        · Card/iframe 腿 = `nfTicket.mintTickets` → `nfTicket.injectTickets` 产出的
//          srcdoc（真 `<img>`）注入 sandbox+allow-same-origin 的 iframe（cardRegistry 同形）；
//        · 账本/叠卡/大图腿 = `popArtifacts.buildMediaStack`（叠卡本体）+
//          `lightbox.openImageGallery` → 内部 `hydrateMedia`/`nfViewerSource`。
//
// 判据（EXIT 0 = 全绿）：
//   C1  铸票应答带回内容 cid（32 hex = 整件 sha256 前缀）
//   C2  内容寻址应答头 = 强 ETag `"<cid>"` + `private, max-age=31536000, immutable`
//   C3  账本腿首次取数走 /api/nf-obj/<cid>/<name>（恰 3 次服务端请求）
//   C4  叠卡→大图（同对象）第二次 = 0 服务端媒体请求
//   C5  Card/iframe 腿 URL = 内容寻址 /api/nf-obj/<cid>/<name>
//   C6  ★核心：Card 腿已直显（浏览器缓存已落）⇒ 大图腿 0 服务端媒体请求
//   C7  另一浏览上下文复用同一对象（0 全量重取）
//   C8  拒绝路径实测状态码：无凭据 401 / 无效会话 403 / 未知 cid 404 / 条件 304
//   C9  过渡期双读：/api/nf-file?path&ticket 仍 200 且与 cid 腿同字节
//   C10 视频仍走票据腿（不内容寻址，Range 语义保留）
//   C11 零 pageerror
//
// 跑法：
//   node tests/media-object-cid-network.spec.mjs                 # 读数（当前树）
//   BEFORE_TREE=/path/to/broken node …                           # 破坏树（验红）
//   OUT=/Users/kaiyu/stand-evidence/media-object-cid-20261118 node …
import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync, copyFileSync } from 'node:fs';
import { createServer, request as httpRequest } from 'node:http';
import { createHash } from 'node:crypto';
import { join, resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const TREE = process.env.BEFORE_TREE || resolve(HERE, '..');
const WEB = resolve(TREE, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8453);
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = process.env.OUT || '/Users/kaiyu/stand-evidence/media-object-cid-20261118';
const TAG = process.env.TAG || 'after';
const SHOTS = join(OUT, `${TAG}-shots`);
mkdirSync(SHOTS, { recursive: true });
// evidence-manifests 闸：写 stand-evidence 命名空间的装置必须落 manifest.json。
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({
  createdAt: new Date().toISOString(), project: 'stand', batch: 'media-object-cid-20261118',
}) + '\n');

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp',
  '.gif': 'image/gif', '.mp4': 'video/mp4', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.ico': 'image/x-icon', '.map': 'application/json',
};

// ── 素材 ────────────────────────────────────────────────────────────────
const CARD_DIR = '/Users/kaiyu/.stand/projects/视觉工作室/assets/cards/deepseek-super-agent-xhs/output-textcard';
const ASSET_DIR = '/tmp/stand-media-cid';
mkdirSync(ASSET_DIR, { recursive: true });
const IMGS = ['dsa-tc-01.png', 'dsa-tc-02.png', 'dsa-tc-03.png'].map((f) => {
  const src = join(CARD_DIR, f);
  if (!existsSync(src)) { console.error('FATAL: 测试素材缺失 ' + src); process.exit(2); }
  const dst = join(ASSET_DIR, f);
  copyFileSync(src, dst);
  return dst;
});
const IMG4 = (() => {
  const src = join(CARD_DIR, 'dsa-tc-04.png');
  if (!existsSync(src)) { console.error('FATAL: 测试素材缺失 ' + src); process.exit(2); }
  const dst = join(ASSET_DIR, 'dsa-tc-04.png');
  copyFileSync(src, dst);
  return dst;
})();
const VIDEO = join(ASSET_DIR, 'clip.mp4');
if (!existsSync(VIDEO)) {
  const { execFileSync } = await import('node:child_process');
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i',
    'testsrc=size=640x360:rate=15:duration=2', '-pix_fmt', 'yuv420p', VIDEO]);
}

// ── 服务端状态（cid 索引 = 铸票时注册；与真网关同语义）────────────────────
const reqLog = [];
let mediaCount = 0;
let ticketSeq = 0;
const tickets = new Map();          // ticket → abs path
const byCid = new Map();            // cid → abs path
const VALID_SESSION = 'SESS-VALID';
const SESSION_COOKIE = 'stand_session';

const sha256hex = (buf) => createHash('sha256').update(buf).digest('hex');
/** cid = 整件 sha256 前 32 hex（= NfObjStore.CidHexChars）。 */
const cidOfBuf = (buf) => sha256hex(buf).slice(0, 32);
const etagOf = (buf) => '"' + createHash('sha1').update(buf).digest('hex') + '"';
const IMMUTABLE = 'private, max-age=31536000, immutable';

function cookiesOf(req) {
  const out = {};
  for (const part of (req.headers.cookie || '').split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return out;
}

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  const p = decodeURIComponent(url.pathname);
  const entry = { m: req.method, p, q: url.search, ts: Date.now(), bytes: 0, status: 0, cc: '', etag: '' };
  reqLog.push(entry);
  const finish = (code, headers, body) => {
    entry.status = code;
    entry.cc = headers['Cache-Control'] || '';
    entry.etag = headers['ETag'] || '';
    entry.bytes = body ? body.length : 0;
    res.writeHead(code, headers);
    res.end(body || undefined);
  };
  const isMediaPath = p === '/api/nf-file' || p.startsWith('/api/nf-obj/') || p.startsWith('/uploads/');
  if (isMediaPath) { mediaCount += 1; entry.media = true; }

  // ── 铸票（凭据 = 会话 cookie；cid 并键）────────────────────────────────
  if (p === '/api/nf-ticket' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      if (cookiesOf(req)[SESSION_COOKIE] !== VALID_SESSION) {
        return finish(401, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' }, Buffer.from('credential-required'));
      }
      let parsed = {};
      try { parsed = JSON.parse(body || '{}'); } catch { parsed = {}; }
      const paths = Array.isArray(parsed.paths) ? parsed.paths : [];
      const outTickets = {}; const rejected = [];
      for (const raw of paths) {
        if (!existsSync(raw)) { rejected.push({ path: raw, reason: 'not-found' }); continue; }
        const tk = 'TK' + (++ticketSeq);
        tickets.set(tk, raw);
        const buf = readFileSync(raw);
        const cid = cidOfBuf(buf);
        byCid.set(cid, raw);
        outTickets[raw] = { t: tk, exp: Date.now() + 1800_000, size: buf.length, cid };
      }
      finish(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
        Buffer.from(JSON.stringify({ tickets: outTickets, rejected })));
    });
    return;
  }

  // ── 内容寻址出口：凭据 = cookie；强 ETag = cid；immutable ──────────────
  if (p.startsWith('/api/nf-obj/')) {
    const segs = p.split('/').filter(Boolean);   // ['api','nf-obj',cid,name]
    const cid = segs[2] || '';
    const jar = cookiesOf(req);
    if (!Object.prototype.hasOwnProperty.call(jar, SESSION_COOKIE)) {
      return finish(401, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store', 'X-Nf-Reason': 'credential-required' }, Buffer.from('authentication required'));
    }
    if (jar[SESSION_COOKIE] !== VALID_SESSION) {
      return finish(403, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store', 'X-Nf-Reason': 'credential-required' }, Buffer.from('Invalid session'));
    }
    if (!/^[0-9a-f]{32}$/.test(cid)) return finish(404, { 'Cache-Control': 'no-store' }, Buffer.from(''));
    const abs = byCid.get(cid);
    if (!abs || !existsSync(abs)) return finish(404, { 'Cache-Control': 'no-store' }, Buffer.from(''));
    const buf = readFileSync(abs);
    const st = statSync(abs);
    const etag = '"' + cid + '"';
    const lm = new Date(st.mtimeMs).toUTCString();
    const inm = req.headers['if-none-match'];
    if (inm && inm.split(',').map((s) => s.trim()).includes(etag)) {
      return finish(304, { ETag: etag, 'Cache-Control': IMMUTABLE, 'Last-Modified': lm }, Buffer.alloc(0));
    }
    return finish(200, {
      'Content-Type': MIME[extname(abs)] || 'application/octet-stream',
      ETag: etag, 'Cache-Control': IMMUTABLE, 'Last-Modified': lm,
    }, buf);
  }

  // ── 旧票据路由（过渡期双读；逐字不变）────────────────────────────────
  if (p === '/api/nf-file') {
    const tk = url.searchParams.get('ticket');
    if (!tk) return finish(401, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' }, Buffer.from("Missing 'ticket' parameter"));
    const abs = tickets.get(tk) || url.searchParams.get('path');
    if (!abs || !existsSync(abs)) return finish(404, { 'Content-Type': 'text/plain' }, Buffer.from('not found'));
    const buf = readFileSync(abs);
    const st = statSync(abs);
    return finish(200, {
      'Content-Type': MIME[extname(abs)] || 'application/octet-stream',
      'Cache-Control': 'private, max-age=300', ETag: etagOf(buf),
      'Last-Modified': new Date(st.mtimeMs).toUTCString(), 'Accept-Ranges': 'bytes',
    }, buf);
  }

  if (p === '/__stats') {
    return finish(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
      Buffer.from(JSON.stringify({ media: mediaCount })));
  }
  if (p === '/__reset') {
    mediaCount = 0; reqLog.length = 0;
    return finish(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }, Buffer.from('{"ok":true}'));
  }
  if (p === '/' || p === '/__harness.html') {
    return finish(200, {
      'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
      'Set-Cookie': `${SESSION_COOKIE}=${VALID_SESSION}; Path=/; HttpOnly`,
    }, Buffer.from(SHELL));
  }
  if (p === '/__harness.js') {
    return finish(200, { 'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-store' }, Buffer.from(HARNESS_JS));
  }

  const file = join(WEB, p);
  if (!file.startsWith(WEB)) return finish(403, {}, Buffer.from('forbidden'));
  try {
    const buf = readFileSync(file);
    return finish(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' }, buf);
  } catch { return finish(404, { 'Content-Type': 'text/plain' }, Buffer.from('nf')); }
});

const SHELL = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/stand-tokens.css">
<link rel="stylesheet" href="/css/design-system.css">
<link rel="stylesheet" href="/css/chat.css">
<link rel="stylesheet" href="/css/lightbox.css">
<link rel="stylesheet" href="/css/mobileLayer.css">
<style>html,body{height:100%;margin:0;background:#0b0d10;color:#eee}
#host{padding:16px;display:flex;justify-content:center;flex-direction:column;gap:12px;align-items:center}
#hud{position:fixed;top:0;left:0;right:0;z-index:99999;padding:8px 12px;font:600 13px/1.4 ui-monospace,monospace;
  background:rgba(20,22,28,.92);color:#7ee0a8;border-bottom:1px solid #2a2f3a}
#hud b{color:#fff}#hud .t{color:#9aa7b4;font-weight:400}</style>
</head><body class="mobile-layer">
<div id="hud">服务端媒体请求 <b id="hud-n">0</b></div>
<div id="host"></div>
<script type="module" src="/__harness.js"></script>
</body></html>`;

const HARNESS_JS = `
import { buildMediaStack } from '/js/popArtifacts.js';
import { openImageGallery, closeLightbox } from '/js/lightbox.js';
import * as nfTicket from '/js/nfTicket.js';
import { nfViewerSource } from '/js/nfFileCache.js';
const host = document.getElementById('host');
let frame = null;
window.__h = {
  mount(items) {
    host.innerHTML = '';
    const area = buildMediaStack(items);
    host.appendChild(area);
    return !!area;
  },
  clickStack() {
    const view = host.querySelector('.pop-stack-view');
    if (!view) return false;
    view.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return true;
  },
  galleryOpen() {
    const lb = document.querySelector('.nf-lightbox');
    return !!(lb && lb.classList.contains('on') && lb.getAttribute('data-nf-lb-gallery') === 'on');
  },
  close() { closeLightbox(); },
  faceUrls() {
    return [...host.querySelectorAll('img.pop-img, video.pop-video')]
      .map((el) => ({ tag: el.tagName, src: el.currentSrc || el.src || '' }));
  },
  async cardLeg(path) {
    const tickets = await nfTicket.mintTickets([path]);
    const html = '<!doctype html><html><body><img id="ci" src="/api/nf-file?path=' +
      encodeURIComponent(path) + '"></body></html>';
    return { cid: nfTicket.cidOf(path), srcdoc: nfTicket.injectTickets(html, tickets) };
  },
  mountCardFrame(srcdoc) {
    if (frame) { frame.remove(); frame = null; }
    frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-scripts allow-same-origin');
    frame.style.width = '220px'; frame.style.height = '160px'; frame.style.border = '0';
    frame.srcdoc = srcdoc;
    host.appendChild(frame);
    return true;
  },
  cardFrameImgSrc() {
    try {
      const img = frame && frame.contentDocument && frame.contentDocument.querySelector('img');
      return img ? (img.currentSrc || img.src || '') : '';
    } catch (e) { return ''; }
  },
  cardFrameImgLoaded() {
    try {
      const img = frame && frame.contentDocument && frame.contentDocument.querySelector('img');
      return !!(img && img.naturalWidth > 0);
    } catch (e) { return false; }
  },
  async viewerSource(path) {
    const r = await nfViewerSource({ absPath: path, objectUrl: null, pane: null });
    return !!r.cached;
  },
  openGallery(path) {
    openImageGallery([{ kind: 'image', name: path.split('/').pop(), path }], 0);
    return true;
  },
  cidOf(path) { return nfTicket.cidOf(path); },
};
setInterval(async () => {
  try {
    const s = await (await fetch('/__stats', { cache: 'no-store' })).json();
    const n = document.getElementById('hud-n');
    if (n) n.textContent = String(s.media);
  } catch (e) { /* 卸载中 */ }
}, 150);
`;

function listen() { return new Promise((r) => server.listen(PORT, '127.0.0.1', r)); }
function closeServer() { return new Promise((r) => server.close(r)); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 服务端口径的拒绝探针（Node 直发，凭据头完全受控）。 */
function probe(path, cookie) {
  return new Promise((res) => {
    const headers = {};
    if (cookie !== undefined) headers.Cookie = cookie;
    const r = httpRequest({ host: '127.0.0.1', port: PORT, path, method: 'GET', headers }, (resp) => {
      const chunks = [];
      resp.on('data', (c) => chunks.push(c));
      resp.on('end', () => res({
        status: resp.statusCode, reason: resp.headers['x-nf-reason'] || '',
        cc: resp.headers['cache-control'] || '', etag: resp.headers['etag'] || '',
        bytes: Buffer.concat(chunks).length,
      }));
    });
    r.on('error', () => res({ status: 0 }));
    r.end();
  });
}

async function attachCdp(page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.enable');
  try { await cdp.send('Network.setCacheDisabled', { cacheDisabled: false }); } catch { /* older engines */ }
  const events = [];
  cdp.on('Network.requestWillBeSent', (e) => events.push({ id: e.requestId, url: e.request.url, type: e.type }));
  cdp.on('Network.requestServedFromCache', (e) => {
    const ev = events.find((x) => x.id === e.requestId);
    if (ev) ev.servedFromCache = true;
  });
  cdp.on('Network.responseReceived', (e) => {
    const ev = events.find((x) => x.id === e.requestId);
    if (ev) {
      ev.status = e.response.status;
      ev.fromCache = !!e.response.fromDiskCache || !!e.response.fromServiceWorker;
      const h = {};
      for (const [k, v] of Object.entries(e.response.headers || {})) h[k.toLowerCase()] = v;
      ev.headers = h;
    }
  });
  cdp.on('Network.loadingFinished', (e) => {
    const ev = events.find((x) => x.id === e.requestId);
    if (ev) ev.encoded = e.encodedDataLength;
  });
  return { events };
}

const isMediaReq = (url) => /\/api\/nf-file|\/api\/nf-obj|\/uploads\//.test(url);

async function run() {
  await listen();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
  });
  const page = await context.newPage();
  const { events } = await attachCdp(page);
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));

  const report = { tree: TREE, tag: TAG, phases: [], pageErrors: [] };
  async function phase(name, fn) {
    events.length = 0;
    const before = reqLog.length;
    await fn();
    await sleep(400);
    const media = events.filter((e) => isMediaReq(e.url));
    const srv = reqLog.slice(before).filter((r) => isMediaReq(r.p));
    const s = {
      label: name,
      cdpMediaRequests: media.length,
      serverMedia: srv.length,
      bytes: media.reduce((a, e) => a + (e.encoded || 0), 0),
      urls: media.map((e) => ({
        url: e.url, status: e.status, fromCache: !!(e.servedFromCache || e.fromCache),
        encoded: e.encoded, cc: e.headers && e.headers['cache-control'], etag: e.headers && e.headers.etag,
      })),
      serverRequests: srv.map((r) => ({ p: r.p, q: r.q.slice(0, 90), status: r.status, cc: r.cc, etag: r.etag, bytes: r.bytes })),
    };
    report.phases.push(s);
    console.log(`\n── ${name} ── 服务端媒体请求=${s.serverMedia}（CDP 见到 ${s.cdpMediaRequests}）bytes=${s.bytes}`);
    for (const u of s.urls) console.log(`   ${u.status} cache=${u.fromCache ? 'HIT' : '-'} ${u.encoded || 0}B cc=${u.cc || '-'} ${u.url.slice(0, 110)}`);
    return s;
  }

  const items = IMGS.map((p) => ({ kind: 'image', name: p.split('/').pop(), path: p, mime: 'image/png' }));

  await page.goto(BASE + '/__harness.html', { waitUntil: 'load' });
  await sleep(500);

  await phase('S1_stack_mount', async () => {
    await page.evaluate((it) => window.__h.mount(it), items);
    await page.waitForFunction(() => {
      const im = [...document.querySelectorAll('img.pop-img')];
      return im.length >= 3 && im.every((i) => i.naturalWidth > 0);
    }, { timeout: 15000 }).catch(() => {});
    await page.screenshot({ path: join(SHOTS, 'S1-stack.png') });
  });
  report.cids = await page.evaluate((ps) => ps.map((p) => window.__h.cidOf(p)), IMGS);

  const openPhase = (label) => phase(label, async () => {
    await page.evaluate(() => window.__h.clickStack());
    await page.waitForFunction(() => window.__h.galleryOpen(), { timeout: 8000 }).catch(() => {});
    await page.waitForFunction(() => {
      const i = document.querySelector('.nf-lightbox-img');
      return i && i.naturalWidth > 0;
    }, { timeout: 8000 }).catch(() => {});
    await page.screenshot({ path: join(SHOTS, label + '.png') });
  });
  await openPhase('S2_open_big_first');
  await page.evaluate(() => window.__h.close());
  await sleep(300);
  await openPhase('S3_open_big_second');
  await page.evaluate(() => window.__h.close());
  await sleep(300);

  // S4：Card/iframe 腿（mint ⇒ injectTickets ⇒ srcdoc ⇒ 真 <img>）
  report.cardLegInfo = await page.evaluate((p) => window.__h.cardLeg(p), IMG4);
  await phase('S4_card_frame_load', async () => {
    await page.evaluate((s) => window.__h.mountCardFrame(s), report.cardLegInfo.srcdoc);
    await page.waitForFunction(() => window.__h.cardFrameImgLoaded(), { timeout: 8000 }).catch(() => {});
    await page.screenshot({ path: join(SHOTS, 'S4-card-frame.png') });
  });
  report.cardFrameSrc = await page.evaluate(() => window.__h.cardFrameImgSrc());

  // S5 ★核心：同一对象走大图腿 ⇒ Card 腿已把字节落进浏览器缓存 ⇒ 0 请求
  await phase('S5_card_then_big', async () => {
    await page.evaluate((p) => window.__h.openGallery(p), IMG4);
    await page.waitForFunction(() => {
      const i = document.querySelector('.nf-lightbox-img');
      return i && i.naturalWidth > 0;
    }, { timeout: 8000 }).catch(() => {});
    await page.screenshot({ path: join(SHOTS, 'S5-card-big.png') });
  });
  await page.evaluate(() => window.__h.close());
  await sleep(300);

  // S6：另一浏览上下文（新标签页）复用同一对象
  const page2 = await context.newPage();
  await page2.goto(BASE + '/__harness.html', { waitUntil: 'load' });
  await sleep(400);
  await phase('S6_second_context', async () => {
    await page2.evaluate((p) => window.__h.viewerSource(p), IMGS[0]);
    await sleep(800);
  });
  await page2.close();

  // S7：视频仍走票据腿（不内容寻址）
  await phase('S7_video_mount', async () => {
    await page.evaluate(([img, vid]) => {
      document.querySelectorAll('iframe').forEach((f) => f.remove());
      window.__h.mount([
        { kind: 'image', name: 'dsa-tc-01.png', path: img, mime: 'image/png' },
        { kind: 'video', name: 'clip.mp4', path: vid, mime: 'video/mp4' },
      ]);
    }, [IMGS[0], VIDEO]);
    await sleep(1800);
  });
  report.videoFaceUrls = await page.evaluate(() => window.__h.faceUrls());

  // ── 拒绝路径（服务端口径，Node 直发）──────────────────────────────────
  const cid0 = report.cids[0];
  const unknownCid = 'f'.repeat(31) + '0';
  const okProbe = await probe(`/api/nf-obj/${cid0}/dsa-tc-01.png`, `${SESSION_COOKIE}=${VALID_SESSION}`);
  report.rejections = {
    noCredential: await probe(`/api/nf-obj/${cid0}/dsa-tc-01.png`, undefined),
    badCookie: await probe(`/api/nf-obj/${cid0}/dsa-tc-01.png`, `${SESSION_COOKIE}=BOGUS`),
    unknownCid: await probe(`/api/nf-obj/${unknownCid}/x.png`, `${SESSION_COOKIE}=${VALID_SESSION}`),
    malformedCid: await probe(`/api/nf-obj/not-a-cid/x.png`, `${SESSION_COOKIE}=${VALID_SESSION}`),
    ok: okProbe,
  };
  report.ticketLegProbe = await probe(`/api/nf-file?path=${encodeURIComponent(IMGS[0])}`, undefined);
  report.pageErrors = pageErrors;

  writeFileSync(join(OUT, `${TAG}-cid-network.json`), JSON.stringify(report, null, 2));
  await browser.close();
  await closeServer();

  // ── 判据 ────────────────────────────────────────────────────────────
  const P = Object.fromEntries(report.phases.map((p) => [p.label, p]));
  const results = [];
  const check = (name, ok, detail) => {
    results.push({ name, ok: !!ok, detail: detail || '' });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
  };
  const sm = (label) => (P[label] ? P[label].serverMedia : -1);
  const urls = (label) => (P[label] ? P[label].urls.map((u) => u.url) : []);

  check('C1 铸票应答带回内容 cid（32 hex sha256 前缀）',
    Array.isArray(report.cids) && report.cids.length === 3 && report.cids.every((c) => /^[0-9a-f]{32}$/.test(c || '')),
    JSON.stringify(report.cids));
  const objResp = (P.S1_stack_mount ? P.S1_stack_mount.urls : [])[0] || {};
  check('C2 内容寻址应答 = 强 ETag `"<cid>"` + immutable',
    String(objResp.cc || '') === IMMUTABLE && String(objResp.etag || '') === '"' + report.cids[0] + '"',
    `cc=${objResp.cc} etag=${objResp.etag} cid=${report.cids[0]}`);
  check('C3 叠卡首次取数走 /api/nf-obj（恰 3 次服务端请求）',
    sm('S1_stack_mount') === 3 && urls('S1_stack_mount').length === 3 && urls('S1_stack_mount').every((u) => u.includes('/api/nf-obj/')),
    `server=${sm('S1_stack_mount')} obj-urls=${urls('S1_stack_mount').filter((u) => u.includes('/api/nf-obj/')).length}`);
  check('C4 叠卡→大图第二次零服务端媒体请求',
    sm('S2_open_big_first') === 0 && sm('S3_open_big_second') === 0,
    `first=${sm('S2_open_big_first')} second=${sm('S3_open_big_second')}`);
  const cardUrl = (report.cardLegInfo && report.cardLegInfo.srcdoc || '').match(/\/api\/nf-obj\/[^"'\s>]+/);
  check('C5 Card/iframe 腿 URL = 内容寻址 /api/nf-obj/<cid>/<name>',
    !!cardUrl && cardUrl[0] === `/api/nf-obj/${report.cardLegInfo.cid}/dsa-tc-04.png`,
    `srcdoc-url=${cardUrl && cardUrl[0]} cid=${report.cardLegInfo.cid}`);
  check('C6 ★核心：Card 腿已直显 ⇒ 大图腿零服务端媒体请求（同一对象、同一 cid）',
    sm('S5_card_then_big') === 0,
    `server=${sm('S5_card_then_big')} cdp=${P.S5_card_then_big ? P.S5_card_then_big.cdpMediaRequests : -1}` +
      (P.S5_card_then_big && P.S5_card_then_big.serverRequests[0] ? ` / ${P.S5_card_then_big.serverRequests[0].status} ${P.S5_card_then_big.serverRequests[0].bytes}B` : ''));
  check('C7 另一浏览上下文复用同一对象（0 全量重取）', sm('S6_second_context') === 0, `server=${sm('S6_second_context')}`);
  const R = report.rejections || {};
  check('C8 拒绝路径实测：无凭据 401 / 无效会话 403 / 未知 cid 404 / 畸形 cid 404 / 正答 200',
    (R.noCredential || {}).status === 401 && (R.badCookie || {}).status === 403 &&
      (R.unknownCid || {}).status === 404 && (R.malformedCid || {}).status === 404 &&
      (R.ok || {}).status === 200 && /immutable/.test((R.ok || {}).cc || '') && (R.ok || {}).bytes === statSync(IMGS[0]).size,
    JSON.stringify({ no: R.noCredential && R.noCredential.status, bad: R.badCookie && R.badCookie.status,
      unk: R.unknownCid && R.unknownCid.status, mal: R.malformedCid && R.malformedCid.status,
      ok: R.ok && R.ok.status, bytes: R.ok && R.ok.bytes }));
  check('C9 过渡期双读：/api/nf-file 缺票仍 401（票据鉴权未放宽）',
    (report.ticketLegProbe || {}).status === 401,
    `status=${report.ticketLegProbe && report.ticketLegProbe.status}`);
  check('C10 视频仍走票据腿（不内容寻址 ⇒ Range 语义保留）',
    (report.videoFaceUrls || []).some((f) => f.tag === 'VIDEO' && /\/api\/nf-file/.test(f.src)),
    JSON.stringify((report.videoFaceUrls || []).map((f) => f.src.slice(0, 70))));
  check('C11 零 pageerror', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));

  writeFileSync(join(OUT, `${TAG}-cid-verdict.json`), JSON.stringify({ results, phases: report.phases, rejections: report.rejections, pageErrors }, null, 2));
  const failed = results.filter((r) => !r.ok);
  console.log(`\n=== ${TAG}: ${results.length - failed.length}/${results.length} PASS ===`);
  process.exit(failed.length ? 1 : 0);
}

run().catch((e) => { console.error('FATAL', e); process.exit(3); });
