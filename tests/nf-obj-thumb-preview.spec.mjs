#!/usr/bin/env node
// nf-obj-thumb-preview.spec.mjs — D5-A 缩略腿「真机读数」批（2026-11-19 作者令：
// 三处接线在真 Chromium 跑一轮，对照「开缩略 vs 关缩略」：请求数 / 总字节 /
// 首图可见时间（各 ≥3 次取中位）；并补同图对照截图）。
//
// 被测真实链路（**前端零替身**）：
//   popArtifacts.hydrateMedia(el, item)（真产品函数，媒体脸调用点）
//   → nfFileCache.nfViewerUrl({absPath, width: previewWidth()})
//   → nfFileCache.nfViewerSource(width) → nfTicket.contentAddressedUrl(path, w)
//   → nfTicket.objUrl(path, cid, w) = `/api/nf-obj/<cid>/<name>?w=<px>`
//   → 网关镜像按 (cid,w) 回**缩略字节**
//
// 对照档：
//   after（默认）= 本工作树三处接线（请求带 `?w=`）
//   before       = `MODE=before`：静态服务把 nfFileCache.js / nfTicket.js /
//                  popArtifacts.js 三件换成 `git show HEAD:` 版（改前形态，
//                  请求**不带** `w`）—— 同一装置、同一夹具、同一浏览器。
//
// 运行：
//   node tests/nf-obj-thumb-preview.spec.mjs                  # after 档
//   MODE=before node tests/nf-obj-thumb-preview.spec.mjs      # before 档
// env：PORT=8188、RUNS=3、SHOTS_DIR

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, rmSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8188);
const BASE = `http://127.0.0.1:${PORT}`;
const MODE = process.env.MODE === 'before' ? 'before' : 'after';
// 对照基点 = **本批起点**（整合线 tip），不是 HEAD（HEAD 是本批提交，改后形态）。
const BASE_REF = process.env.BASE_REF || '5b99db0b9b88ef458709ffc8af991bbc65b04840';
const RUNS = Number(process.env.RUNS || 3);
const N_IMG = 6;
const SHOTS_DIR = process.env.SHOTS_DIR
  || join(ROOT, '.nebflow', 'evidence', '20261119_nf-obj-thumb-preview', MODE, 'shots');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ───────── 夹具：6 张真 PNG + 各档真缩略（ffmpeg 生成，字节真实）───────── */
const FIX = join(tmpdir(), `qa-thumb-${process.pid}`);
rmSync(FIX, { recursive: true, force: true });
mkdirSync(FIX, { recursive: true });
const WIDTHS = [240, 480, 960, 1280, 1600];      // previewWidth() 可能取到的档
const images = [];
for (let i = 0; i < N_IMG; i++) {
  const full = join(FIX, `img${i}.png`);
  execFileSync('ffmpeg', ['-y', '-f', 'lavfi', '-i',
    `testsrc2=duration=1:size=2000x1400:rate=1`, '-frames:v', '1', full], { stdio: 'ignore' });
  const cid = createHash('sha256').update(readFileSync(full)).digest('hex').slice(0, 32);
  const thumbs = new Map();
  for (const w of WIDTHS) {
    const t = join(FIX, `img${i}@w${w}.png`);
    execFileSync('ffmpeg', ['-y', '-i', full, '-vf', `scale=${w}:-2`, t], { stdio: 'ignore' });
    thumbs.set(w, t);
  }
  images.push({ path: full, cid, name: `img${i}.png`, thumbs, fullBytes: statSync(full).size });
}
console.log(`[fixtures] ${N_IMG} 张 2000x1400 PNG；full=${images[0].fullBytes}B；`
  + `thumbs@1280=${statSync(images[0].thumbs.get(1280)).size}B；`
  + `thumbs@480=${statSync(images[0].thumbs.get(480)).size}B`);

/* ───────── 静态服务（真 web/ 树；before 档替换三件为 HEAD 版）───────── */
const SWAP = [
  ['/js/nfFileCache.js', 'src/main/resources/web/js/nfFileCache.js'],
  ['/js/nfTicket.js', 'src/main/resources/web/js/nfTicket.js'],
  ['/js/popArtifacts.js', 'src/main/resources/web/js/popArtifacts.js'],
];
const HEAD_SRC = new Map();
if (MODE === 'before') {
  for (const [url, repoPath] of SWAP) {
    HEAD_SRC.set(url, execFileSync('git', ['show', `${BASE_REF}:${repoPath}`], { cwd: ROOT, encoding: 'utf8' }));
  }
}
const PAGE = `<!doctype html><html><head><meta charset="utf-8"><title>thumb harness</title>
<style>body{margin:0;font:13px system-ui}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;padding:8px}
.cell{background:#eee;border-radius:6px;padding:4px}.cell img{width:100%;display:block;border-radius:4px}</style>
</head><body><div class="grid" id="g"></div>
<script type="module">
import { hydrateMedia } from '/js/popArtifacts.js';
window.__runThumbs = async (items) => {
  const g = document.getElementById('g'); g.innerHTML = '';
  const t0 = performance.now(); const marks = [];
  await Promise.all(items.map((it, i) => new Promise((done) => {
    const cell = document.createElement('div'); cell.className = 'cell';
    const im = document.createElement('img'); im.dataset.nfName = it.name;
    cell.appendChild(im); g.appendChild(cell);
    im.addEventListener('load', () => { marks.push({ i, at: performance.now() - t0 }); done(); }, { once: true });
    hydrateMedia(im, { path: it.path }, () => done());
    setTimeout(done, 15000);
  })));
  return { firstVisibleMs: marks.length ? Math.min(...marks.map((m) => m.at)) : null, loaded: marks.length };
};
</script></body></html>`;

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  const p = decodeURIComponent(u.pathname);
  if (p === '/__thumb_harness.html') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(PAGE);
  }
  if (MODE === 'before' && HEAD_SRC.has(p)) {
    res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(HEAD_SRC.get(p));
  }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(readFileSync(file));
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ───────── 网关镜像：铸票（带 cid）+ /api/nf-obj（(cid,w) 缩略）+ /api/nf-file ───────── */
const reqs = [];      // 每次请求读数
const byCid = new Map();   // cid → image
for (const im of images) byCid.set(im.cid, im);
const valid = new Set();
let issued = 0;
const urlOf = (p) => join(FIX, p);

async function installRoutes(page) {
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));   // 兜底先注册（后注册者优先）
  await page.route('**/api/nf-ticket*', async (route) => {
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
    const tickets = {};
    for (const p of paths) {
      const img = images.find((i) => i.path === p || urlOf(i.name) === p);
      const t = `tk-${++issued}`; valid.add(t);
      tickets[p] = { t, exp: Date.now() + 3600e3, uses: -1, ...(img ? { cid: img.cid } : {}) };
    }
    reqs.push({ kind: 'ticket', n: paths.length, bytes: 0 });
    return route.fulfill({ json: { tickets, rejected: [] } });
  });
  await page.route('**/api/nf-obj/**', async (route) => {
    const u = new URL(route.request().url());
    const parts = u.pathname.split('/');            // ['', 'api','nf-obj', cid, name]
    const cid = decodeURIComponent(parts[3] || '');
    const w = Number(u.searchParams.get('w') || 0);
    const img = byCid.get(cid);
    if (!img) { reqs.push({ kind: 'obj', cid, w, status: 404, bytes: 0 }); return route.fulfill({ status: 404, body: '' }); }
    const file = w > 0 && img.thumbs.has(w) ? img.thumbs.get(w) : (w > 0 ? null : img.path);
    if (!file) { reqs.push({ kind: 'obj', cid, w, status: 404, thumbMissing: true, bytes: 0 }); return route.fulfill({ status: 404, body: '' }); }
    const body = readFileSync(file);
    reqs.push({ kind: 'obj', cid, w, status: 200, bytes: body.length, thumb: w > 0 });
    return route.fulfill({
      status: 200, body, headers: {
        'Content-Type': 'image/png',
        'Cache-Control': 'private, max-age=31536000, immutable',
        ETag: '"' + (w > 0 ? `t${w}${cid.slice(0, 8)}` : cid) + '"',
      },
    });
  });
  await page.route('**/api/nf-file**', async (route) => {
    const u = new URL(route.request().url());
    const p = u.searchParams.get('path') || '';
    const img = images.find((i) => i.path === p);
    if (!img) { reqs.push({ kind: 'file', status: 404, bytes: 0 }); return route.fulfill({ status: 404, body: '' }); }
    const body = readFileSync(img.path);
    reqs.push({ kind: 'file', status: 200, bytes: body.length, full: true });
    return route.fulfill({ status: 200, body, headers: { 'Content-Type': 'image/png' } });
  });
}

/* ───────── 主流程：每档 RUNS 次冷跑（清账本 + 清 HTTP 缓存）───────── */
const summarize = (list) => {
  const req = list.reduce((a, r) => a + r.requests, 0) / list.length;
  const bytes = list.reduce((a, r) => a + r.bytes, 0) / list.length;
  const med = (xs) => { const s = [...xs].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; };
  return {
    runs: list.length,
    medianRequests: med(list.map((r) => r.requests)),
    medianBytes: med(list.map((r) => r.bytes)),
    medianFirstVisibleMs: med(list.map((r) => r.firstVisibleMs).filter((x) => typeof x === 'number')),
    meanRequests: Number(req.toFixed(2)), meanBytes: Math.round(bytes),
    perRun: list,
  };
};

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();
let results = [];
try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('[pageerror]', String(e.message).slice(0, 300)));
  const conErrs = [];
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') conErrs.push(String(m.text()).slice(0, 200)); });
  page.on('requestfailed', (r) => console.log('[reqfail]', r.url().slice(0, 120), (r.failure() || {}).errorText));
  await installRoutes(page);
  // 真 web 模块从这里加载（同源）；`/__thumb_harness.html` 由本装置提供。
  for (let run = 0; run < RUNS; run++) {
    // 冷跑：清账本（localStorage + Cache Storage）+ 清浏览器 HTTP 缓存（新 context 才清得干净）
    await page.goto(BASE + '/__thumb_harness.html');
    await page.evaluate(async () => {
      try { localStorage.clear(); } catch { /* noop */ }
      try { const ks = await caches.keys(); await Promise.all(ks.map((k) => caches.delete(k))); } catch { /* noop */ }
    });
    reqs.length = 0;
    const t0 = Date.now();
    let out;
    try {
      out = await page.evaluate(async (items) => {
        if (typeof window.__runThumbs !== 'function') return { error: '__runThumbs missing' };
        return await window.__runThumbs(items);
      }, images.map((i) => ({ path: i.path, name: i.name })));
    } catch (e) { out = { error: String(e && e.message || e).slice(0, 300) }; }
    if (out && out.error) console.log('[eval error]', out.error);
    if (conErrs.length) { console.log('[console]', conErrs.slice(0, 6).join(' | ')); conErrs.length = 0; }
    if (!reqs.length) console.log('[reqs] EMPTY');
    await sleep(300);
    const requests = reqs.filter((r) => r.kind !== 'ticket').length;
    const bytes = reqs.reduce((a, r) => a + (r.bytes || 0), 0);
    const thumbs = reqs.filter((r) => r.kind === 'obj' && r.thumb).length;
    results.push({ run, requests, bytes, firstVisibleMs: out.firstVisibleMs, loaded: out.loaded, thumbs, wallMs: Date.now() - t0, reqDetail: reqs.slice() });
    console.log(`[${MODE}] run${run}: requests=${requests} bytes=${bytes} thumbs=${thumbs} firstVisible=${out.firstVisibleMs}ms loaded=${out.loaded}`);
  }
  await page.goto(BASE + '/__thumb_harness.html');
  await page.evaluate(async () => { try { const ks = await caches.keys(); await Promise.all(ks.map((k) => caches.delete(k))); } catch { /* noop */ } });
  reqs.length = 0;
  await page.evaluate(async (items) => window.__runThumbs(items), images.map((i) => ({ path: i.path, name: i.name })));
  await sleep(400);
  await page.screenshot({ path: join(SHOTS_DIR, MODE === 'before' ? 'before-full-grid.png' : 'after-thumb-grid.png') });

  const sum = summarize(results);
  const thumbReqs = results.reduce((a, r) => a + r.thumbs, 0);
  console.log(`\n[${MODE}] 请求数中位=${sum.medianRequests} 总字节中位=${sum.medianBytes} 首图可见中位=${sum.medianFirstVisibleMs}ms`
    + ` | 缩略请求合计数=${thumbReqs}`);
  writeFileSync(join(SHOTS_DIR, 'readings.json'), JSON.stringify({
    mode: MODE, runs: RUNS, nImages: N_IMG, widths: WIDTHS,
    fixtureBytes: images.map((i) => ({ name: i.name, full: i.fullBytes, thumb1280: statSync(i.thumbs.get(1280)).size })),
    summary: sum, thumbRequestsTotal: thumbReqs,
  }, null, 2));
} finally {
  await browser.close();
  webServer.close();
  try { rmSync(FIX, { recursive: true, force: true }); } catch { /* best effort */ }
}
