#!/usr/bin/env node
// media-object-cache-network.spec.mjs — 媒体重复加载「按对象寻址 + 缓存复用」批
// （作者令 2026-11-18 18:07 逐字：「我从手机上 看你发的东西 会出现重复加载的问题
// 就是比如图片视频 在你发给我的叠卡中已经加载好了 但是我点开大图 又需要重新加载
// 一遍 … 我们这些网络数据传输 能不能也是按对象传输这样 文件存储复用 也是按对象来
// 这样 加载过的内容 不会重复加载」）的**网络读数装置**。
//
// 它做什么：
//   ① 真 HTTP 服务器（不是 page.route 拦截）—— 静态面 = 本仓 src/main/resources/web，
//      媒体面 = 与真网关**同形状同响应头**的两个端点：
//        POST /api/nf-ticket  → { tickets: {path:{t,exp,size}}, rejected:[…] }
//        GET  /api/nf-file    → 真字节 + `Cache-Control: private, max-age=300`
//                               （值逐字 = NfFileRoutes.NfCacheControl）
//                               + ETag + Last-Modified（StaticFile 同源：node 侧按
//                               sha1 与 mtime 出，语义等价）
//        GET  /uploads/…      → 真字节 + `private, max-age=300`（StaticRoutes 同值）
//   ② 真浏览器（Chromium）+ CDP `Network.*` —— 请求数 / URL 逐字 / 响应头 / 传输字节
//      全部来自浏览器网络域，不从服务器日志推断（服务器日志作交叉校验）。
//   ③ 手机全屏形态：viewport 390×844 / deviceScaleFactor=2 / isMobile / hasTouch，
//      `body.mobile-layer`（`isMobileLayer()` 的判据）——即作者手机端看到的那层。
//   ④ 驱动**产品既有模块**（零复制）：`popArtifacts.buildMediaStack`（叠卡组件本体）+
//      默认 `openItem` → `openMediaItemClick` → `mediaOpenRoute.routeMediaClick`
//      → 手机图片腿 `lightbox.openImageGallery`（大图）。叠卡卡面的字节 = 组件内部
//      `hydrateMedia`（账本 nfViewerUrl / 票据链）——与主会话/社交窗**同一段代码**。
//
// 判据（EXIT 0 = 全绿）：
//   Q1 叠卡阶段 N 件媒体 → 恰 N 次 /api/nf-file 取字节（每件一次，无重复）
//   Q2 同会话内点开大图 → 媒体请求数 = 0（复用叠卡已取回的字节）
//   Q3 同会话内第二次点开 → 媒体请求数 = 0
//   Q4 叠卡与大图取回的 URL 逐字一致（同内容恒同 URL）
//   Q5 媒体应答头可缓存（Cache-Control 含 max-age 且非 no-store；ETag 在场）
//   Q6 刷新页面后重开 → 走浏览器缓存（HTTP 缓存命中）或账本，不产生完整字节二次传输
//   Q7 视频：叠卡封面 vs 点开播放 → 同一 URL（不逐字变）
//   Q8 零 pageerror
//
// 跑法：
//   node tests/media-object-cache-network.spec.mjs                 # 读数（当前树）
//   BEFORE_TREE=/path/to/before node …                            # 改前树（验红）
//   OUT=/Users/kaiyu/stand-evidence/media-object-cache-20261118 node …
import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync, copyFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { join, resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const TREE = process.env.BEFORE_TREE || resolve(HERE, '..');
const WEB = resolve(TREE, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8341);
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = process.env.OUT || '/Users/kaiyu/stand-evidence/media-object-cache-20261118';
const TAG = process.env.TAG || 'after';
const SHOTS = join(OUT, `${TAG}-shots`);
mkdirSync(SHOTS, { recursive: true });
// evidence-manifests 闸（check-evidence-manifests.mjs）：写 stand-evidence 命名空间的
// 装置必须落 manifest.json（EvidenceTemp 的 TTL 清扫对缺 manifest 的目录 fail-closed）。
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({
  createdAt: new Date().toISOString(), project: 'stand', batch: 'media-object-cache-20261118',
}) + '\n');

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp',
  '.gif': 'image/gif', '.mp4': 'video/mp4', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.ico': 'image/x-icon', '.map': 'application/json',
};

// ── 真实测试素材（作者实拍同款素材族：deepseek-super-agent-xhs 黑底文字卡）──
const CARD_DIR = '/Users/kaiyu/.stand/projects/视觉工作室/assets/cards/deepseek-super-agent-xhs/output-textcard';
const ASSET_DIR = '/tmp/stand-media-object';
mkdirSync(ASSET_DIR, { recursive: true });
const IMGS = ['dsa-tc-01.png', 'dsa-tc-02.png', 'dsa-tc-03.png'].map((f) => {
  const src = join(CARD_DIR, f);
  if (!existsSync(src)) { console.error('FATAL: 测试素材缺失 ' + src); process.exit(2); }
  const dst = join(ASSET_DIR, f);
  copyFileSync(src, dst);
  return dst;
});
// 第 4 件：**只**用于「另一条取字节腿」测（从不进叠卡 ⇒ 账本对它零条目）
const IMG4 = (() => {
  const src = join(CARD_DIR, 'dsa-tc-04.png');
  if (!existsSync(src)) { console.error('FATAL: 测试素材缺失 ' + src); process.exit(2); }
  const dst = join(ASSET_DIR, 'dsa-tc-04.png');
  copyFileSync(src, dst);
  return dst;
})();
// 视频素材：ffmpeg 合成的 2s 真 mp4（无外部依赖；尺寸/帧率见 ffprobe 读数）
const VIDEO = join(ASSET_DIR, 'clip.mp4');
if (!existsSync(VIDEO)) {
  const { execFileSync } = await import('node:child_process');
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i',
    'testsrc=size=640x360:rate=15:duration=2', '-pix_fmt', 'yuv420p', VIDEO]);
}

// 服务器侧请求账（交叉校验；判据面用 CDP 读数）
const reqLog = [];
let mediaCount = 0;      // 服务端真实收到的媒体请求数（HUD 读数 = 网络帧上真出现的那些）
let mediaBytes = 0;
let ticketSeq = 0;
const tickets = new Map();
const etagOf = (buf) => '"' + createHash('sha1').update(buf).digest('hex') + '"';

function fileEtagAndMtime(abs) {
  const buf = readFileSync(abs);
  const st = statSync(abs);
  return { buf, etag: etagOf(buf), lm: new Date(st.mtimeMs).toUTCString() };
}

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  const p = decodeURIComponent(url.pathname);
  const entry = { m: req.method, p, q: url.search, ts: Date.now(), bytes: 0, status: 0,
    cc: '', etag: '', lm: '', ctype: '' };
  reqLog.push(entry);
  const finish = (code, headers, body) => {
    entry.status = code;
    entry.cc = headers['Cache-Control'] || '';
    entry.etag = headers['ETag'] || '';
    entry.lm = headers['Last-Modified'] || '';
    entry.ctype = headers['Content-Type'] || '';
    entry.bytes = body ? body.length : 0;
    if (entry.media) mediaBytes += entry.bytes;
    res.writeHead(code, headers);
    res.end(body || undefined);
  };

  if (p === '/api/nf-ticket' && req.method === 'POST') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let parsed = {};
      try { parsed = JSON.parse(body || '{}'); } catch { parsed = {}; }
      const paths = Array.isArray(parsed.paths) ? parsed.paths : [];
      const outTickets = {}; const rejected = [];
      for (const raw of paths) {
        if (!existsSync(raw)) { rejected.push({ path: raw, reason: 'not-found' }); continue; }
        const tk = 'TK' + (++ticketSeq);
        tickets.set(tk, raw);
        // size 并键（真铸票面顺带 stat；LAN 直传阈值判定用）
        outTickets[raw] = { t: tk, exp: Date.now() + 1800_000, size: statSync(raw).size };
      }
      const payload = Buffer.from(JSON.stringify({ tickets: outTickets, rejected }));
      finish(200, { 'Content-Type': 'application/json' }, payload);
    });
    return;
  }

  if (p === '/__stats') {
    return finish(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
      Buffer.from(JSON.stringify({ media: mediaCount, bytes: mediaBytes })));
  }
  if (p === '/__reset') { mediaCount = 0; mediaBytes = 0; reqLog.length = 0;
    return finish(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }, Buffer.from('{"ok":true}')); }

  if (p === '/api/nf-file') {
    mediaCount += 1; entry.media = true;
    const tk = url.searchParams.get('ticket');
    const abs = (tk && tickets.get(tk)) || url.searchParams.get('path');
    if (!abs || !existsSync(abs)) return finish(404, { 'Content-Type': 'text/plain' }, Buffer.from('not found'));
    const { buf, etag, lm } = fileEtagAndMtime(abs);
    const inm = req.headers['if-none-match'];
    if (inm && inm === etag) {
      entry.partial = false;
      return finish(304, { 'Cache-Control': 'private, max-age=300', ETag: etag, 'Last-Modified': lm }, Buffer.alloc(0));
    }
    // Range 协商（与 NfFileRoutes 同语义：单段 bytes=a-b / bytes=a- / bytes=-n）
    const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
    if (range) {
      const size = buf.length;
      const start = range[1] === '' ? Math.max(0, size - Number(range[2])) : Number(range[1]);
      const end = range[2] === '' || range[1] === '' ? size - 1 : Math.min(Number(range[2]), size - 1);
      if (start <= end && start < size) {
        const slice = buf.subarray(start, end + 1);
        entry.partial = true;
        return finish(206, {
          'Content-Type': MIME[extname(abs)] || 'application/octet-stream',
          'Cache-Control': 'private, max-age=300',
          ETag: etag, 'Last-Modified': lm, 'Accept-Ranges': 'bytes',
          'Content-Range': `bytes ${start}-${end}/${size}`,
        }, slice);
      }
    }
    entry.partial = false;
    return finish(200, {
      'Content-Type': MIME[extname(abs)] || 'application/octet-stream',
      'Cache-Control': 'private, max-age=300',
      ETag: etag, 'Last-Modified': lm, 'Accept-Ranges': 'bytes',
    }, buf);
  }

  if (p.startsWith('/uploads/')) {
    mediaCount += 1; entry.media = true;
    const abs = join('/tmp/stand-media-object/uploads', p.slice('/uploads'.length));
    if (!existsSync(abs)) return finish(404, { 'Content-Type': 'text/plain' }, Buffer.from('nf'));
    const { buf, etag, lm } = fileEtagAndMtime(abs);
    return finish(200, {
      'Content-Type': MIME[extname(abs)] || 'application/octet-stream',
      'Cache-Control': 'private, max-age=300', ETag: etag, 'Last-Modified': lm,
    }, buf);
  }

  if (p === '/' || p === '/__harness.html') {
    return finish(200, { 'Content-Type': 'text/html; charset=utf-8' }, Buffer.from(SHELL));
  }
  if (p === '/__harness.js') {
    return finish(200, { 'Content-Type': 'text/javascript; charset=utf-8' }, Buffer.from(HARNESS_JS));
  }

  // 静态面
  const file = join(WEB, p);
  if (!file.startsWith(WEB)) return finish(403, {}, Buffer.from('forbidden'));
  try {
    const buf = readFileSync(file);
    return finish(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-cache' }, buf);
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
#hud b{color:#fff}
#hud .t{color:#9aa7b4;font-weight:400}</style>
</head><body class="mobile-layer">
<div id="hud">网络媒体请求 <b id="hud-n">0</b> <span class="t">·</span> 传输 <b id="hud-b">0</b> <span class="t">B</span></div>
<div id="host"></div>
<script type="module" src="/__harness.js"></script>
</body></html>`;

const HARNESS_JS = `
import { buildMediaStack } from '/js/popArtifacts.js';
import { openImageGallery, closeLightbox, openLightbox } from '/js/lightbox.js';
import { hydrateMedia } from '/js/popArtifacts.js';
const host = document.getElementById('host');
let current = null;
window.__h = {
  mount(items) {
    host.innerHTML = '';
    const area = buildMediaStack(items);
    host.appendChild(area);
    current = { items, area };
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
  // 叠卡卡面已水合到什么 URL（当前的 URL 逐字读数）
  faceUrls() {
    return [...host.querySelectorAll('img.pop-img, video.pop-video')]
      .map((el) => ({ tag: el.tagName, src: el.currentSrc || el.src || '', blob: (el.currentSrc || el.src || '').startsWith('blob:') }));
  },
  gallerySrc() {
    const img = document.querySelector('.nf-lightbox-img');
    return img ? (img.currentSrc || img.src || '') : '';
  },
  mounted() { return !!current; },
};
// HUD：服务端**真实收到**的媒体请求读数（缓存承接的请求根本不到服务端 ⇒ 不计）。
setInterval(async () => {
  try {
    const s = await (await fetch('/__stats', { cache: 'no-store' })).json();
    const n = document.getElementById('hud-n'); const b = document.getElementById('hud-b');
    if (n) n.textContent = String(s.media);
    if (b) b.textContent = String(s.bytes);
  } catch (e) { /* 页面卸载中 */ }
}, 150);
`;

function listen() {
  return new Promise((r) => server.listen(PORT, '127.0.0.1', r));
}
function closeServer() {
  return new Promise((r) => server.close(r));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── CDP 请求账 ───────────────────────────────────────────────────────────
async function attachCdp(page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.enable');
  try { await cdp.send('Network.setCacheDisabled', { cacheDisabled: false }); } catch { /* older engines */ }
  const events = [];
  const bodies = new Map();
  cdp.on('Network.requestWillBeSent', (e) => {
    events.push({ id: e.requestId, url: e.request.url, type: e.type, ts: e.timestamp, phase: '' });
  });
  cdp.on('Network.requestServedFromCache', (e) => {
    const ev = events.find((x) => x.id === e.requestId);
    if (ev) ev.servedFromCache = true;   // 内存/磁盘缓存命中（responseReceived 不见得带 fromDiskCache）
  });
  cdp.on('Network.responseReceived', (e) => {
    const ev = events.find((x) => x.id === e.requestId);
    if (ev) {
      ev.status = e.response.status;
      ev.mime = e.response.mimeType;
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
  return { cdp, events };
}

const isMediaReq = (url) => /\/api\/nf-file|\/uploads\//.test(url);
const isTicketReq = (url) => /\/api\/nf-ticket/.test(url);

function summarize(events, label) {
  const media = events.filter((e) => isMediaReq(e.url) && !/\/__/.test(e.url));
  const ticket = events.filter((e) => isTicketReq(e.url));
  const bytes = media.reduce((s, e) => s + (e.encoded || 0), 0);
  return {
    label,
    mediaRequests: media.length,
    // 「真的产生网络请求」= 未被缓存承接的那些（CDP 对缓存命中同样发 requestWillBeSent）
    networkRequests: media.filter((e) => !e.servedFromCache && !e.fromCache).length,
    ticketRequests: ticket.length,
    bytes,
    urls: media.map((e) => ({ url: e.url, status: e.status, fromCache: e.servedFromCache || e.fromCache,
      encoded: e.encoded, cc: e.headers && e.headers['cache-control'], etag: e.headers && e.headers.etag })),
  };
}

async function run() {
  await listen();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
  });
  const page = await context.newPage();
  const { events } = await attachCdp(page);
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));

  const report = { tree: TREE, tag: TAG, port: PORT, phases: [], pageErrors: [] };
  let mark = () => { events.length = 0; return events; };
  async function phase(name, fn) {
    mark();
    const before = reqLog.length;
    await fn();
    await sleep(400);
    const s = summarize(events, name);
    s.serverRequests = reqLog.slice(before).filter((r) => /\/api\/nf-file|\/uploads\//.test(r.p))
      .map((r) => ({ p: r.p, q: r.q.slice(0, 80), status: r.status, cc: r.cc, etag: r.etag, bytes: r.bytes }));
    // **权威读数**：某条响应若被浏览器缓存承接，它根本不会到服务端 ⇒ 服务端账 = 真·网络请求数。
    s.serverMedia = s.serverRequests.length;
    report.phases.push(s);
    console.log(`\n── ${name} ──  server媒体请求=${s.serverMedia}（CDP 见到 ${s.mediaRequests}）ticket=${s.ticketRequests} bytes=${s.bytes}`);
    for (const u of s.urls) console.log(`   ${u.status} cache=${u.fromCache ? 'HIT' : '-'} ${u.encoded || 0}B cc=${u.cc || '-'} ${u.url.slice(0, 110)}`);
    return s;
  }

  const items = IMGS.map((p) => ({ kind: 'image', name: p.split('/').pop(), path: p, mime: 'image/png' }));

  await page.goto(BASE + '/__harness.html', { waitUntil: 'load' });
  await sleep(500);

  await phase('S1_mount_stack', async () => {
    await page.evaluate((it) => window.__h.mount(it), items);
    await page.waitForFunction(() => {
      const imgs = [...document.querySelectorAll('img.pop-img')];
      return imgs.length >= 3 && imgs.every((i) => i.naturalWidth > 0);
    }, { timeout: 15000 }).catch(() => {});
    await page.screenshot({ path: join(SHOTS, 'S1-stack.png') });
  });
  const faceUrls = await page.evaluate(() => window.__h.faceUrls());
  report.faceUrls = faceUrls;

  const openPhase = (label) => phase(label, async () => {
    await page.evaluate(() => window.__h.clickStack());
    await page.waitForFunction(() => window.__h.galleryOpen(), { timeout: 8000 }).catch(() => {});
    await page.waitForFunction(() => {
      const img = document.querySelector('.nf-lightbox-img');
      return img && img.naturalWidth > 0;
    }, { timeout: 8000 }).catch(() => {});
    await page.screenshot({ path: join(SHOTS, label + '.png') });
  });

  await openPhase('S2_open_big_first');
  report.gallerySrcAfterOpen = await page.evaluate(() => window.__h.gallerySrc());
  await page.evaluate(() => window.__h.close());
  await sleep(300);

  await openPhase('S3_open_big_second');
  await page.evaluate(() => window.__h.close());
  await sleep(300);

  // S4：刷新页面后重开（账本持久面 = Cache Storage + localStorage 索引）
  await phase('S4_after_reload_mount', async () => {
    await page.reload({ waitUntil: 'load' });
    await sleep(400);
    await page.evaluate((it) => window.__h.mount(it), items);
    await page.waitForFunction(() => {
      const imgs = [...document.querySelectorAll('img.pop-img')];
      return imgs.length >= 3 && imgs.every((i) => i.naturalWidth > 0);
    }, { timeout: 15000 }).catch(() => {});
  });
  await openPhase('S4b_after_reload_open');

  // S5：视频（叠卡封面 → 点开播放）——组 = 图 + 视频（≥2 才走叠卡，产品同形）
  const vItems = [
    { kind: 'image', name: 'dsa-tc-01.png', path: IMGS[0], mime: 'image/png' },
    { kind: 'video', name: 'clip.mp4', path: VIDEO, mime: 'video/mp4' },
  ];
  await phase('S5_video_mount', async () => {
    await page.evaluate(() => window.__h.close());
    await page.evaluate((it) => window.__h.mount(it), vItems);
    await sleep(1800);
  });
  const vFaces = await page.evaluate(() => window.__h.faceUrls());
  report.videoFaceUrls = vFaces;
  await phase('S5b_video_open', async () => {
    // 翻到视频张（叠卡 front=1）再点开
    await page.evaluate(() => {
      const v = document.querySelector('.pop-stack-view');
      if (v) v.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    });
    await sleep(500);
    await page.evaluate(() => window.__h.clickStack());
    await sleep(1800);
  });
  report.videoGallerySrc = await page.evaluate(() => {
    const m = document.querySelector('.nf-lightbox-av');
    return m ? (m.currentSrc || m.src || '') : '';
  });

  // S6：**两条取字节腿**——Card/iframe 腿先以裸票据 URL 直显同件（浏览器自取，
  // 不进账本），再从大图入口打开同件（灯箱/画廊走账本腿）。同对象是否二次全量传输？
  //   对象 = 第 4 件（从不出现在叠卡里 ⇒ 账本对它零条目，无需任何人为清账）。
  await phase('S6a_cardleg_load', async () => {
    await page.evaluate((it) => { window.__h.close(); window.__h.mount(it); }, vItems);
    await sleep(600);
    await page.evaluate(async (p) => {
      const { ticketUrl } = await import('/js/nfTicket.js');
      const url = await ticketUrl(p);
      const img = document.createElement('img');
      img.id = 'cardleg';
      img.style.width = '200px';
      img.src = url;                        // 裸票据 URL 直显（Card iframe srcdoc 腿同形）
      document.getElementById('host').appendChild(img);
      await new Promise((r) => { img.onload = r; img.onerror = r; });
      window.__cardlegUrl = url;
    }, IMG4);
    await sleep(400);
  });
  report.cardlegUrl = await page.evaluate(() => window.__cardlegUrl || '');
  await phase('S6b_cardleg_open_big', async () => {
    await page.evaluate(async (p) => {
      const url = window.__cardlegUrl;
      const { openImageGallery } = await import('/js/lightbox.js');
      // 产品手机腿入口（routeMediaClick 的手机图片腿落点）：item 同时带 src + path
      openImageGallery([{ kind: 'image', name: 'dsa-tc-04.png', path: p, src: url }], 0);
    }, IMG4);
    await page.waitForFunction(() => {
      const i = document.querySelector('.nf-lightbox-img');
      return i && i.naturalWidth > 0;
    }, { timeout: 8000 }).catch(() => {});
    await sleep(600);
    await page.screenshot({ path: join(SHOTS, 'S6b-cardleg-open.png') });
  });
  report.cardlegGallerySrc = await page.evaluate(() => window.__h.gallerySrc());
  await page.evaluate(() => { const c = document.getElementById('cardleg'); if (c) c.remove(); });

  // S7：另一处入口（第二个浏览上下文 / 新标签页）打开同一件
  await page.evaluate(() => window.__h.close());
  const page2 = await context.newPage();
  await page2.goto(BASE + '/__harness.html', { waitUntil: 'load' });
  await sleep(400);
  await phase('S7_second_context', async () => {
    await page2.evaluate(async (p) => {
      const { nfViewerSource } = await import('/js/nfFileCache.js');
      window.__r = await nfViewerSource({ absPath: p, objectUrl: null, pane: null });
    }, IMGS[0]);
    await sleep(800);
  });
  report.secondContextCached = await page2.evaluate(() => !!(window.__r && window.__r.cached));
  await page2.close();

  report.pageErrors = pageErrors;

  // ── 证据腿（EVIDENCE=1）：手机全屏录像，HUD = 服务端真实收到的媒体请求读数 ──
  if (process.env.EVIDENCE === '1') {
    const vdir = join(SHOTS, 'video');
    mkdirSync(vdir, { recursive: true });
    const ectx = await browser.newContext({
      viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true,
      recordVideo: { dir: vdir, size: { width: 390, height: 844 } },
      userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
    });
    const ep = await ectx.newPage();
    const vid = ep.video();
    await ep.goto(BASE + '/__harness.html', { waitUntil: 'load' });
    await sleep(700);
    await ep.evaluate(() => fetch('/__reset', { cache: 'no-store' }));
    await sleep(900);                       // HUD 归零
    await ep.evaluate((it) => window.__h.mount(it), items);
    await ep.waitForFunction(() => {
      const im = [...document.querySelectorAll('img.pop-img')];
      return im.length >= 3 && im.every((i) => i.naturalWidth > 0);
    }, { timeout: 15000 }).catch(() => {});
    await sleep(1400);                      // HUD = 3（叠卡已加载好）
    await ep.evaluate(() => window.__h.clickStack());
    await ep.waitForFunction(() => {
      const i = document.querySelector('.nf-lightbox-img');
      return i && i.naturalWidth > 0;
    }, { timeout: 8000 }).catch(() => {});
    await sleep(1800);                      // ★ 关键帧：大图已开，HUD 仍 = 3
    await ep.evaluate(() => window.__h.close());
    await sleep(700);
    await ep.evaluate(async (p) => {        // 跨腿：Card 腿裸票据直显同件（第 4 件）
      const { ticketUrl } = await import('/js/nfTicket.js');
      const url = await ticketUrl(p);
      const img = document.createElement('img');
      img.id = 'cardleg'; img.style.width = '240px'; img.style.borderRadius = '10px';
      img.src = url;
      document.getElementById('host').appendChild(img);
      await new Promise((r) => { img.onload = r; img.onerror = r; });
      window.__cardlegUrl = url;
    }, IMG4);
    await sleep(1400);                      // HUD = 4（Card 腿这一件真走了网络）
    await ep.evaluate(async (p) => {
      const { openImageGallery } = await import('/js/lightbox.js');
      openImageGallery([{ kind: 'image', name: 'dsa-tc-04.png', path: p, src: window.__cardlegUrl }], 0);
    }, IMG4);
    await ep.waitForFunction(() => {
      const i = document.querySelector('.nf-lightbox-img');
      return i && i.naturalWidth > 0;
    }, { timeout: 8000 }).catch(() => {});
    await sleep(2200);                      // ★ 关键帧：改前 HUD 4→5；改后仍 4
    const stats = await ep.evaluate(async () => (await (await fetch('/__stats', { cache: 'no-store' })).json()));
    report.evidence = { hud: stats };
    await ep.close();
    await ectx.close();
    report.evidence.video = await vid.path();
  }
  writeFileSync(join(OUT, `${TAG}-network.json`), JSON.stringify(report, null, 2));
  await browser.close();
  await closeServer();

  // ── 判据 ────────────────────────────────────────────────────────────
  const P = Object.fromEntries(report.phases.map((p) => [p.label, p]));
  const results = [];
  const check = (name, ok, detail) => { results.push({ name, ok: !!ok, detail: detail || '' });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`); };
  const uniq = (arr) => new Set(arr).size;
  const sm = (label) => (P[label] ? P[label].serverMedia : -1);
  const sw = (label) => (P[label] ? P[label].mediaRequests : -1);

  check('Q1 叠卡阶段每件恰好一次取字节（N=3 ⇒ 服务端 3 请求 / CDP 3 条）', sm('S1_mount_stack') === 3 && sw('S1_mount_stack') === 3,
    `server=${sm('S1_mount_stack')} cdp=${sw('S1_mount_stack')}`);
  check('Q2 同会话首次点开大图零网络请求', sm('S2_open_big_first') === 0, `server=${sm('S2_open_big_first')} cdp=${sw('S2_open_big_first')}`);
  check('Q3 同会话第二次点开零网络请求', sm('S3_open_big_second') === 0, `server=${sm('S3_open_big_second')} cdp=${sw('S3_open_big_second')}`);
  const mUrls = (P.S1_mount_stack ? P.S1_mount_stack.urls : []).map((u) => u.url);
  check('Q4 叠卡取回 URL 互不相同（3 件 3 URL）', uniq(mUrls) === 3, `唯一 ${uniq(mUrls)}`);
  check('Q5 媒体应答可缓存（max-age 且非 no-store + ETag）',
    (P.S1_mount_stack ? P.S1_mount_stack.urls : []).every((u) => /max-age=\d+/.test(u.cc || '') && !/no-store/.test(u.cc || '') && u.etag),
    JSON.stringify((P.S1_mount_stack ? P.S1_mount_stack.urls[0] : {}) || {}).slice(0, 120));
  check('Q6 刷新页面后重开零网络请求（账本/缓存承接）', sm('S4b_after_reload_open') === 0, `server=${sm('S4b_after_reload_open')}`);
  const vMount = (P.S5_video_mount ? P.S5_video_mount.urls : []).map((u) => u.url);
  const vOpen = (P.S5b_video_open ? P.S5b_video_open.urls : []).map((u) => u.url);
  check('Q7 视频叠卡与大图同 URL（不逐字变）',
    vOpen.length === 0 || (vMount.length > 0 && (vOpen[0] === vMount[0] || vOpen.every((u) => vMount.includes(u)))),
    `mount=${(vMount[0] || '').slice(0, 80)} open=${(vOpen[0] || '').slice(0, 80)}`);
  check('Q8 零 pageerror', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));
  check('Q9 两条取字节腿共用同一对象（Card 腿已直显 ⇒ 大图腿零网络请求）',
    sm('S6b_cardleg_open_big') === 0,
    `S6b server=${sm('S6b_cardleg_open_big')} cdp=${sw('S6b_cardleg_open_big')}` +
      (P.S6b_cardleg_open_big && P.S6b_cardleg_open_big.serverRequests[0] ? ` / ${P.S6b_cardleg_open_big.serverRequests[0].status} ${P.S6b_cardleg_open_big.serverRequests[0].bytes}B` : ''));
  check('Q10 另一浏览上下文复用同一对象（非全量重取）',
    sm('S7_second_context') === 0, `S7 server=${sm('S7_second_context')} cdp=${sw('S7_second_context')}`);
  check('Q11 视频叠卡后再点开播放零全量重传',
    (P.S5b_video_open ? P.S5b_video_open.urls : []).filter((u) => u.status === 200).length === 0,
    `S5b 200 请求 ${(P.S5b_video_open ? P.S5b_video_open.urls : []).filter((u) => u.status === 200).length} 条`);

  writeFileSync(join(OUT, `${TAG}-verdict.json`), JSON.stringify({ results, phases: report.phases, pageErrors }, null, 2));
  const failed = results.filter((r) => !r.ok);
  console.log(`\n=== ${TAG}: ${results.length - failed.length}/${results.length} PASS ===`);
  process.exit(failed.length ? 1 : 0);
}

run().catch((e) => { console.error('FATAL', e); process.exit(3); });
