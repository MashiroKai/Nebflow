#!/usr/bin/env node
// video-ticket-selfheal.spec.mjs — B1 同构：Canvas `<video>` **元素腿**票据自愈
// （票据自愈批 2026-11-19 · 作者令「判据与 <img> 同一套；先建控制组再修」）。
//
// 被测真实链路（前端零替身，plumbing 照 canvas-video-viewer.spec.mjs）：
//   workspace-open-item(size>64MiB) → canvas.js openWorkspaceItem → WS pop.readFile
//   → fileViewers.renderFile → viewers/video.js viewVideo → nfViewerUrl
//   （`maxBytes` 超限 ⇒ **保持票据 URL 流式/Range**）→ <video src=票URL>
//   → 元素 error → onError（既有：一生一次重铸；本批：票因闸 + 最多 3 次 + 流式保护）
//
// 端点语义逐字照 NfFileRoutes：无票 401 / 票不认 403+`X-Nf-Reason` / 有效 200(+Range 206)。
// 「票仓清空」= 代际 +1 并清空已发票集合（网关重启 / 票 TTL 过期的等价物）。
//
// 判据：
//   V0 基线：视频真解码（readyState≥1、videoWidth>0），且 src = **票 URL**（流式腿）
//   V1 首开遇失效票（重启）⇒ 自愈重铸后仍解码
//   V2 **非票因（404）不自愈**：不得白发铸票（票因闸），落终局面
//   V3 **第二次重启仍自愈**（每次失败至多重铸 3 次，替代旧「一生一次」）
//   V4 **流式腿硬闸**：已起播/已收数据时触发 error ⇒ **不得重铸**（重铸=换 src
//      = 打断 Range 续传与播放位 —— 作者点名的等价陷阱），播放位保持
//   控制组 `BREAK=1`（自愈分支改死代码）⇒ V1/V3 必红（元素停在对不起来的错误面）
//
// 运行：node tests/video-ticket-selfheal.spec.mjs
// 控制组：BREAK=1 node tests/video-ticket-selfheal.spec.mjs
// env：PORT=8186、SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8186);
const BASE = `http://127.0.0.1:${PORT}`;
const BREAK = process.env.BREAK === '1' ? '1' : (process.env.BREAK === 'blob' ? 'blob' : '');
const BREAK_ANY = !!BREAK;
const SHOTS_DIR = process.env.SHOTS_DIR
  || join(ROOT, '.nebflow', 'evidence', '20261119_video-ticket-selfheal', BREAK ? 'break' : 'fixed', 'shots');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ───────── 夹具：ffmpeg 造 webm（VP9，Chromium 必解）───────── */
const FIX_DIR = join(tmpdir(), `qa-video-heal-${process.pid}`);
rmSync(FIX_DIR, { recursive: true, force: true });
mkdirSync(FIX_DIR, { recursive: true });
const VIDEO = join(FIX_DIR, 'clip.webm');
execFileSync('ffmpeg', ['-y', '-f', 'lavfi', '-i', 'testsrc=duration=3:size=320x240:rate=12',
  '-c:v', 'libvpx-vp9', '-b:v', '200k', '-an', VIDEO], { stdio: 'ignore' });
for (const c of ['clipB.webm', 'clipC.webm', 'clipD.webm']) execFileSync('cp', [VIDEO, join(FIX_DIR, c)]);
const VIDEO_B = join(FIX_DIR, 'clipB.webm');
const VIDEO_C = join(FIX_DIR, 'clipC.webm');
const VIDEO_D = join(FIX_DIR, 'clipD.webm');   // 账本 blob 腿（≤64MiB）：首次投递**坏字节**
const BAD_CT = { 'video/webm': 'video/webm' };
const MISSING = join(FIX_DIR, 'nope.webm');   // 不存在 ⇒ 真 404（非票因）

/* ───────── 静态服务（真 web/ 树；BREAK=1 把自愈分支改死代码）───────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8', '.webm': 'video/webm',
};
const BREAK_FIND = 'if (pane.isConnected && absPath && rerolls < 3) {';
const BREAK_REPL = 'if (false && pane.isConnected && absPath && rerolls < 3) {';
// 账本 blob 腿自愈分支（第二控制档 BREAK=blob）
const BREAK_BLOB_FIND = "if (failing.startsWith('blob:')) {";
const BREAK_BLOB_REPL = "if (false && failing.startsWith('blob:')) {";
let brokeApplied = false;
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    let body = readFileSync(file);
    if (BREAK && p === '/js/viewers/video.js') {
      let src = body.toString('utf8');
      if (BREAK === '1') {
        if (!src.includes(BREAK_FIND)) throw new Error('BREAK anchor not found — 控制组不可信');
        src = src.replace(BREAK_FIND, BREAK_REPL);
        brokeApplied = true;
      } else if (BREAK === 'blob') {
        if (!src.includes(BREAK_BLOB_FIND)) throw new Error('BREAK-blob anchor not found');
        src = src.replace(BREAK_BLOB_FIND, BREAK_BLOB_REPL);
        brokeApplied = true;
      }
      body = Buffer.from(src);
    }
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(body);
  } catch { if (!res.headersSent) { res.writeHead(404); res.end('not found'); } }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ───────── 票据仓（有状态，可「重启清空」）+ /api/nf-file（真端点语义 + Range） ───────── */
let gen = 1;
let issued = 0;
const valid = new Set();
const mints = [];
const nfReqs = [];
const badDelivered = new Set();   // clipD 首投坏字节只发一次
function restartStore() { gen++; valid.clear(); return gen; }
const VLIVE_CT = { '.webm': 'video/webm', '.mp4': 'video/mp4' };

async function nfFileRoute(route) {
  const url = new URL(route.request().url());
  const tk = url.searchParams.get('ticket');
  const p = url.searchParams.get('path') || '';
  const range = route.request().headers()['range'] || '';
  const rec = { path: p, ticket: tk || '', range, status: 0, genAt: gen };
  if (!tk) { rec.status = 401; nfReqs.push(rec); return route.fulfill({ status: 401, contentType: 'text/plain', body: "Missing 'ticket' parameter" }); }
  if (!valid.has(tk)) {
    rec.status = 403; nfReqs.push(rec);
    return route.fulfill({ status: 403, contentType: 'text/plain', headers: { 'X-Nf-Reason': 'invalid-ticket' }, body: 'ticket rejected' });
  }
  let body = null;
  try { body = readFileSync(p); } catch { body = null; }
  if (!body) { rec.status = 404; nfReqs.push(rec); return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' }); }
  // 账本坏字节夹具：clipD 的**首次**投递给坏字节（其后一律真字节）⇒ 账本键里落一份
  // 解不开的 blob；重试腿（票 URL）拿到的才是真字节。
  if (p === VIDEO_D && !badDelivered.has(p)) {
    badDelivered.add(p);
    rec.status = 200; rec.corrupt = true; nfReqs.push(rec);
    return route.fulfill({ status: 200, contentType: 'video/webm', body: Buffer.from('NOT A VIDEO — corrupt ledger bytes') });
  }
  const headers = { 'Accept-Ranges': 'bytes', 'Content-Type': VLIVE_CT[extname(p).toLowerCase()] || 'application/octet-stream' };
  let status = 200;
  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
    if (m && (m[1] !== '' || m[2] !== '')) {
      let start; let end;
      if (m[1] === '') { const n = Number(m[2]); start = Math.max(body.length - n, 0); end = body.length - 1; }
      else { start = Number(m[1]); end = m[2] === '' ? body.length - 1 : Math.min(Number(m[2]), body.length - 1); }
      if (start <= end && start < body.length) {
        headers['Content-Range'] = `bytes ${start}-${end}/${body.length}`;
        status = 206; body = body.subarray(start, end + 1);
      }
    }
  }
  rec.status = status; nfReqs.push(rec);
  return route.fulfill({ status, headers, body });
}

const EXT_ITEM = { webm: 'video', mp4: 'video', mov: 'video', ogv: 'video' };
const BINARY = new Set(['webm', 'mp4', 'mov', 'ogv', 'png', 'jpg', 'svg', 'pdf']);

async function boot(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute);
  await page.route('**/api/nf-ticket*', async (route) => {
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
    paths = Array.isArray(paths) ? paths : [];
    mints.push({ gen, paths: paths.slice() });
    const tickets = {};
    for (const p of paths) { const t = `tk-g${gen}-${++issued}`; valid.add(t); tickets[p] = { t, exp: Date.now() + 3600e3, uses: -1 }; }
    return route.fulfill({ json: { tickets, rejected: [] } });
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type !== 'pop.readFile') return;
      const rawPath = String(m.path || '');
      try {
        const buf = readFileSync(rawPath);
        const ext = (rawPath.split('.').pop() || '').toLowerCase();
        const msg = { type: 'fileContent', path: rawPath, absPath: rawPath, itemType: EXT_ITEM[ext] || 'code', fileName: rawPath.split('/').pop(), size: buf.length };
        if (!BINARY.has(ext)) msg.content = buf.toString('utf8');
        ws.send(JSON.stringify(msg));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: rawPath, absPath: rawPath }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(400);
  return { ctx, page, pageErrors };
}

const V = '.canvas-tab-pane.active';
/** 打开视频（size 声明 >64MiB ⇒ 走「保持票 URL 流式」腿）。 */
async function openVideo(page, absPath, size) {
  await page.evaluate(({ absPath, size }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'video', content: '', absPath, size, title: absPath.split('/').pop() },
    }));
  }, { absPath, size });
}
const vstate = (page) => page.evaluate((sel) => {
  const v = document.querySelector(`${sel} video`);
  if (!v) return null;
  return {
    src: String(v.getAttribute('src') || '').slice(0, 140),
    scheme: (v.getAttribute('src') || '').startsWith('blob:') ? 'blob' : ((v.getAttribute('src') || '').includes('ticket=') ? 'ticket' : 'other'),
    readyState: v.readyState, w: v.videoWidth, t: Number(v.currentTime.toFixed(2)),
    started: !v.paused || v.currentTime > 0,
    errCode: v.error ? v.error.code : 0,
    terminal: !!document.querySelector(`${sel} .canvas-error`), hasVideo: true,
  };
}, V);
/** 把元素 src 换成指定形态（真网络请求 → 真 401/403/404 → 元素 error）。
 *  mode='stale'：带指定旧票；mode='noticket'：无票；mode='valid'：带指定有效票。
 *  path 由调用方给（每个破坏性场景用自己的文件 ⇒ 各自独立的 <video> 元素）。 */
const retarget = (page, mode, { path, ticket = '' }) => page.evaluate(
  ({ sel, mode, base, path, ticket }) => {
    const v = document.querySelector(`${sel} video`);
    if (!v) return 0;
    const q = `${base}/api/nf-file?path=${encodeURIComponent(path)}`;
    v.setAttribute('src', mode === 'noticket' ? q : `${q}&ticket=${ticket}`);
    return 1;
  }, { sel: V, mode, base: BASE, path, ticket });
/** 现铸一枚**当下有效**的票（Node 侧票仓直接发票 —— 等价于一次成功铸票）。 */
function freshTicket() { const t = `tk-g${gen}-${++issued}`; valid.add(t); return t; }
const paneTerminal = (page) => page.evaluate((sel) => !!document.querySelector(`${sel} .canvas-error`), V);

let failed = 0;
function ok(cond, label, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}${extra ? '  — ' + extra : ''}`);
  if (!cond) failed++;
}
const waitReady = (page, ms) => page.waitForFunction((sel) => {
  const v = document.querySelector(`${sel} video`);
  return !!v && v.readyState >= 1 && v.videoWidth > 0;
}, V, { timeout: ms }).then(() => true).catch(() => false);

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();
try {
  const { page, pageErrors } = await boot(browser);
  if (BREAK_ANY) ok(brokeApplied, `BREAK=${BREAK} 模式：对应自愈分支已改为死代码（锚点命中）`);

  // ── V0 基线（流式腿）──
  await openVideo(page, VIDEO, 70 * 1024 * 1024);
  const v0ok = await waitReady(page, 12000);
  let st = await vstate(page);
  ok(v0ok, 'V0 视频真解码（readyState≥1 且 videoWidth>0）', JSON.stringify(st));
  ok(st && st.scheme === 'ticket', 'V0b src = **票 URL**（>64MiB 流式/Range 腿，非 blob）', st && st.src);
  ok(st && st.scheme === 'ticket' && nfReqs.some((r) => r.status === 206), 'V0c Range 206 真发生过（流式语义在场）',
    JSON.stringify(nfReqs.filter((r) => r.status === 206).slice(0, 2).map((r) => r.range)));
  await page.screenshot({ path: join(SHOTS_DIR, 'V0-baseline.png') });
  // 场景确定性：把元素按回「未起播」态（本场景讲的是**首开**载入失败，不是播放中失败）。
  const resetPlayback = () => page.evaluate((sel) => {
    const v = document.querySelector(`${sel} video`);
    if (!v) return;
    try { v.pause(); } catch { /* noop */ }
    try { v.currentTime = 0; } catch { /* noop */ }
  }, V);

  // ── V1 首开遇失效票（网关重启清空票仓）· 元素 A ──
  const m0 = mints.length;
  await resetPlayback();
  restartStore();
  await retarget(page, 'stale', { path: VIDEO, ticket: 'tk-g1-1' });   // gen≥2 ⇒ 旧票已不认（403）
  const v1ok = await waitReady(page, 12000);
  st = await vstate(page);
  ok(v1ok, 'V1 首开遇失效票 ⇒ 自愈重铸后仍解码', JSON.stringify(st));
  ok(mints.length > m0, 'V1b 自愈靠**重铸新票**', `mints ${m0} → ${mints.length}`);
  ok(nfReqs.some((r) => r.status === 403), 'V1c 确实收到 403（票仓已清空的旧票被拒）');
  await page.screenshot({ path: join(SHOTS_DIR, BREAK ? 'V1-BREAK.png' : 'V1-healed.png') });

  // ── V3 同一元素**第二次**失效仍自愈（至多重铸 3 次，替代旧「一生一次 retried」）· 元素 A ──
  const m3 = mints.length;
  await resetPlayback();
  restartStore();                                   // 第二次重启：上一轮刚铸的票也失效
  await page.evaluate((sel) => {
    const v = document.querySelector(`${sel} video`);
    if (v) v.dispatchEvent(new Event('error'));     // 同一元素再次载入失败
  }, V);
  await sleep(2500);
  const v3ok = await waitReady(page, 12000);
  st = await vstate(page);
  ok(v3ok && !!(st && !st.terminal), 'V3 同一元素第二次失效仍自愈（旧形态一生一次 ⇒ 此处落错误面）', JSON.stringify(st));
  ok(mints.length > m3, 'V3b 第二次确实重铸了新票', `mints ${m3} → ${mints.length}`);
  await page.screenshot({ path: join(SHOTS_DIR, BREAK ? 'V3-BREAK.png' : 'V3-second-heal.png') });

  // ── V4 流式腿硬闸：已起播/有播放位 ⇒ 不得重铸（不打断 Range 续传/播放位）· 元素 B ──
  await openVideo(page, VIDEO_B, 70 * 1024 * 1024);
  await waitReady(page, 12000);
  await page.evaluate((sel) => {
    const v = document.querySelector(`${sel} video`);
    if (!v) return;
    v.muted = true;
    try { v.currentTime = 0.5; } catch { /* 无 seekable 时不强求 */ }
    try { v.play && v.play(); } catch { /* 无用户手势时忽略 */ }
  }, V);
  await sleep(800);
  const before = await vstate(page);
  const m4 = mints.length;
  const srcBefore = before && before.src;
  ok(!!(before && before.started), 'V4(前置) 元素确有播放位/已在播（流式会话在场）', JSON.stringify(before));
  await page.evaluate((sel) => {
    const v = document.querySelector(`${sel} video`);
    if (v) v.dispatchEvent(new Event('error'));      // 模拟**起播后**的载入错误
  }, V);
  await sleep(2000);
  st = await vstate(page);
  ok(mints.length === m4, 'V4 起播后 error **不重铸**（重铸=换 src=打断 Range 续传/播放位）',
    `mints ${m4} → ${mints.length}`);
  ok(!!(st && st.src === srcBefore), 'V4b src 未被替换（流式会话未被重置）', JSON.stringify({ before: srcBefore, after: st && st.src }));
  ok(!!(st && st.hasVideo && !st.terminal), 'V4c 元素与播放面仍在（未被 fatal 面板抹掉）', JSON.stringify(st));
  await page.screenshot({ path: join(SHOTS_DIR, BREAK ? 'V4-BREAK.png' : 'V4-stream-protected.png') });

  // ── V2 非票因（404）不自愈：不得白发铸票，逐字交回既有终局面 · 元素 C ──
  await openVideo(page, VIDEO_C, 70 * 1024 * 1024);
  await waitReady(page, 12000);
  await sleep(600);                                  // C 自己的铸票先落定，再开始计数
  const m2 = mints.length;
  await retarget(page, 'valid', { path: MISSING, ticket: freshTicket() });   // 票有效，坏的是路径 ⇒ 真 404
  await sleep(2500);
  ok(await paneTerminal(page), 'V2 非票因（404）落既有终局面（逐字交回）');
  ok(mints.length === m2, 'V2b 非票因**零铸票**（票因闸：只对 401/403 重铸）', `mints ${m2} → ${mints.length}`);
  ok(nfReqs.some((r) => r.status === 404), 'V2c 确实收到 404（非票因起点真实）');
  await page.screenshot({ path: join(SHOTS_DIR, BREAK ? 'V2-404-BREAK.png' : 'V2-404-no-reroll.png') });

  // ── V6 账本 blob 腿（≤64MiB）坏字节：**逐出账本 + 换票重试一次**（与 <img> ① 同构）· 元素 D ──
  await openVideo(page, VIDEO_D, 1000);          // ≤64MiB ⇒ 走账本（blob URL）腿
  await sleep(3000);
  const v6ok = await waitReady(page, 12000);
  st = await vstate(page);
  const ledger = await page.evaluate(async (p) => {
    const m = await import('/js/nfFileCache.js');
    const hit = await m.nfCachedBlob('nf:' + p);
    return { hasEntry: !!hit, size: hit ? hit.size : 0 };
  }, VIDEO_D);
  ok(st && st.scheme === 'ticket', 'V6(前置) 账本腿坏字节后已换票 URL（blob 解不开 ⇒ 自愈腿给票 URL）', JSON.stringify(st));
  ok(v6ok, 'V6 账本坏字节 ⇒ 逐出 + 重试后仍解码', JSON.stringify(st));
  ok(!ledger.hasEntry, 'V6b **坏字节已逐出账本**（不逐出则 TTL 内每次渲染都命中同一份坏字节）', JSON.stringify(ledger));
  ok(nfReqs.some((r) => r.corrupt), 'V6c 确实先投递过坏字节（夹具真实）');
  await page.screenshot({ path: join(SHOTS_DIR, BREAK ? 'V6-BREAK.png' : 'V6-ledger-dropped.png') });

  if (!BREAK_ANY) ok(pageErrors.length === 0, 'V5 零 pageerror', pageErrors.join(' | '));
  writeFileSync(join(SHOTS_DIR, 'readings.json'), JSON.stringify({
    break_mode: BREAK, mints, nfReqs,
    summary: {
      mints_total: mints.length,
      mints_by_gen: mints.reduce((a, m) => (a[m.gen] = (a[m.gen] || 0) + 1, a), {}),
      nf_by_status: nfReqs.reduce((a, r) => (a[r.status] = (a[r.status] || 0) + 1, a), {}),
    },
  }, null, 2));
  console.log('\n[readings] ' + JSON.stringify({
    mode: BREAK ? 'BREAK(控制组)' : 'FIXED', mints_total: mints.length,
    nf_by_status: nfReqs.reduce((a, r) => (a[r.status] = (a[r.status] || 0) + 1, a), {}),
  }));
} finally {
  await browser.close();
  webServer.close();
  try { rmSync(FIX_DIR, { recursive: true, force: true }); } catch { /* best effort */ }
}
console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAIL`);
if (BREAK_ANY) {
  console.log(failed > 0 ? `CONTROL OK (BREAK=${BREAK}) — 自愈分支被打断 ⇒ 必红` : 'CONTROL BROKEN — 打断后竟然全绿 ⇒ 判据不可信');
  process.exit(failed > 0 ? 0 : 1);
}
process.exit(failed === 0 ? 0 : 1);
