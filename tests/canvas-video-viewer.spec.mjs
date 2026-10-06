// canvas-video-viewer.spec.mjs — Canvas 视频查看器（canvas-video 批 2026-10-06）。
//
// 作者截图令 2026-10-06 13:24：merged.mp4 在 Canvas 打开 = 空 Monaco 编辑器
// （`code` 兜底）。根因链（file:line 以开工树为准）：
//   服务端 WsFileOpsHandlers.readFile / pop.readFile → FileTypeRegistry.detect("mp4")
//   → Entry("video", binary=true)（FileTypeRegistry.scala:72）→ 前端
//   renderFile（fileViewers.js:86）registry.get('video') 落空 → registry.get('code')
//   → Monaco 收到空 content ⇒ 空 Monaco。
// 本 spec 验收双层修：
//   ① 路由面：viewers/video.js（HTML5 <video controls> 原生控件，零自绘文案）
//     注册进 fileViewers.js；
//   ② 服务面：/api/nf-file 对视频族 Content-Type 正确（video/mp4 等）＋ HTTP
//     Range 206（本批在 NfFileRoutes 补刀：http4s 0.23.30 StaticFile 实测零 Range
//     处理）。服务面真读数由 NfFileRoutesSpec（Scala）＋本件 LIVE 档双重取证。
//
// 被测真实链路（前端零替身，mock 档）：
//   workspace-open-item（itemType:'' + absPath）→ canvas.js openWorkspaceItem
//   → WS pop.readFile（替身按 WebSocketRoutes 真语义：FileTypeRegistry 扩展判定
//   ＋二进制腿只回元数据）→ explorer fileContent 重派发 → renderFile →
//   viewers/video.js → nfTicket 铸票 → /api/nf-file（替身带 Range 206 /
//   Accept-Ranges / video/* Content-Type，同真端点语义）→ <video> 出画面。
//
// 运行：
//   node tests/canvas-video-viewer.spec.mjs                  # 门禁档（改后必绿；改前跑会红）
//   BEFORE=1 node tests/canvas-video-viewer.spec.mjs         # 改前取证档：断言 Monaco 兜底在位（改后跑会红）
//   NEBFLOW_LIVE_BASE=http://127.0.0.1:8199 NEBFLOW_LIVE_HOME=/tmp/nf-video-verify-home \
//     node tests/canvas-video-viewer.spec.mjs                # 追加真实例档（真铸票＋真 nf-file Range）
// 可选 env：PORT=8141、SHOTS_DIR=<dir>（缺省 .nebflow/evidence/20261006_videocanvas/）
//
// 视频样本：运行时 ffmpeg 造（仓内现成工具，小样本不入 repo）：
//   ffmpeg -f lavfi -i testsrc=duration=3:size=320x240:rate=12 \
//          -f lavfi -i sine=frequency=440:duration=3 \
//          -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart sample.mp4
//   （webm 同源：-c:v libvpx-vp9 -b:v 200k -c:a libopus）
// harness 浏览器先探 mp4 可解；headless Chromium 若缺 H.264 则用 webm（VP9）
// 断言同一 viewer 机制；mp4 的真浏览器可解性由 LIVE/真实 Chrome 面承载。

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8141);
const BASE = `http://127.0.0.1:${PORT}`;
const FIX_DIR = join(tmpdir(), 'canvas-video-viewer-fixtures');
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(ROOT, '.nebflow/evidence/20261006_videocanvas');
const BEFORE = process.env.BEFORE === '1';
const LIVE_BASE = process.env.NEFLOW_LIVE_BASE || '';
const LIVE_HOME = process.env.NEFLOW_LIVE_HOME || '';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function ok(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`  ${cond ? '✓' : '✗'} ${name}${cond ? '' : ` — ${detail}`}`);
  return !!cond;
}

/* ───────────── 视频夹具（ffmpeg 运行时生成，不落 repo） ───────────── */
function makeVideo(name, vcodec, acodec) {
  const out = join(FIX_DIR, name);
  execFileSync('ffmpeg', [
    '-y', '-loglevel', 'error',
    '-f', 'lavfi', '-i', 'testsrc=duration=3:size=320x240:rate=12',
    '-f', 'lavfi', '-i', 'sine=frequency=440:duration=3',
    '-c:v', vcodec, ...(vcodec === 'libx264' ? ['-pix_fmt', 'yuv420p'] : ['-b:v', '200k']),
    '-c:a', acodec, ...(vcodec === 'libx264' ? ['-movflags', '+faststart'] : []),
    out,
  ]);
  return out;
}

/* ───────────── 静态服务（真 web/ 树） ───────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const webServer = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ───────────── /api/nf-file 替身（真端点语义：票闸＋Content-Type＋Range 206） ───────────── */
const MP4_CTYPE = { '.mp4': 'video/mp4', '.webm': 'video/webm', '.mov': 'video/quicktime', '.ogv': 'video/ogg', '.ogg': 'video/ogg', '.md': 'text/markdown' };
const rangeHits = [];
async function nfFileRoute(route) {
  const url = new URL(route.request().url());
  if (!url.searchParams.has('ticket')) {
    return route.fulfill({ status: 401, contentType: 'text/plain', body: 'Missing ticket' });
  }
  const p = url.searchParams.get('path') || '';
  const rangeHeader = route.request().headers()['range'] || '';
  let body; let status = 200; const headers = {};
  try { body = readFileSync(p); } catch {
    return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
  }
  headers['Accept-Ranges'] = 'bytes';
  headers['Content-Type'] = MP4_CTYPE[extname(p).toLowerCase()] || 'application/octet-stream';
  if (rangeHeader) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader.trim());
    if (m && (m[1] !== '' || m[2] !== '')) {
      let start; let end;
      if (m[1] === '') { const n = Number(m[2]); start = Math.max(body.length - n, 0); end = body.length - 1; }
      else { start = Number(m[1]); end = m[2] === '' ? body.length - 1 : Math.min(Number(m[2]), body.length - 1); }
      if (start <= end && start < body.length) {
        rangeHits.push(rangeHeader);
        headers['Content-Range'] = `bytes ${start}-${end}/${body.length}`;
        status = 206;
        body = body.subarray(start, end + 1);
      } else {
        return route.fulfill({ status: 416, headers: { 'Content-Range': `bytes */${body.length}` }, body: '' });
      }
    }
  }
  return route.fulfill({ status, headers, body });
}

/* ───────────── WS 替身：pop.readFile 真语义（FileTypeRegistry 视频族对齐） ───────────── */
// 对齐 nebflow.core.workspace.FileTypeRegistry.BuiltIn（2026-10-05 canvas-media 批）：
// video = mp4 webm ogv ogg mov；audio = mp3 wav oga flac aac m4a；全部 binary。
const EXT_ITEM = {
  html: 'html', htm: 'html', md: 'markdown', markdown: 'markdown', json: 'json',
  png: 'image', jpg: 'image', svg: 'image', pdf: 'pdf',
  mp4: 'video', webm: 'video', ogv: 'video', ogg: 'video', mov: 'video', m4v: 'video',
  mp3: 'audio', wav: 'audio', oga: 'audio', flac: 'audio', aac: 'audio', m4a: 'audio',
};
const BINARY = new Set(['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'pdf',
  'mp4', 'webm', 'ogv', 'ogg', 'mov', 'm4v', 'mp3', 'wav', 'oga', 'flac', 'aac', 'm4a']);
const MAX_READ = 100 * 1024 * 1024; // = WebSocketRoutes.MaxPopReadFileBytes

/* ───────────── 应用引导 ───────────── */
let LAST = null;
async function boot(browser, { live = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  let token = 't';
  if (live) {
    try {
      const raw = readFileSync(join(LIVE_HOME, 'auth.json'), 'utf8').trim();
      let val = raw;
      try { const j = JSON.parse(raw); val = (typeof j === 'string' ? j : (j.token || j.gatewayToken || raw)); } catch { /* 裸串 */ }
      if (val) token = val;
    } catch { /* 无 auth.json */ }
  }
  await ctx.addInitScript((tok) => {
    localStorage.setItem('nebflow_token', tok); localStorage.setItem('neblink_token', tok);
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  }, token);
  const page = await ctx.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  const nfFileReqs = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  page.on('request', (r) => {
    if (r.url().includes('/api/nf-file')) {
      nfFileReqs.push({ url: decodeURIComponent(r.url()), range: r.headers()['range'] || '' });
    }
  });
  if (!live) {
    await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
    await page.route(/\/api\/nf-file\?/, nfFileRoute);
    await installTicketMock(page);
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
          if (buf.length > MAX_READ) throw new Error('file exceeds 100MB limit');
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
  }
  await page.goto(live ? LIVE_BASE + '/' : BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000 });
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(400);
  const h = { ctx, page, errors, nfFileReqs, mocked: !live };
  LAST = h;
  return h;
}

/** 以 workspace-open-item 打开文件（mock 档默认 = 空内容＋空 itemType 的 Pop 卡同款腿）。 */
async function openPath(page, absPath, { itemType = '', content = '', size = 0 } = {}) {
  await page.evaluate(({ absPath, itemType, content, size }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType, content, absPath, size, title: absPath.split('/').pop() },
    }));
  }, { absPath, itemType, content, size });
}

async function shot(page, name) {
  mkdirSync(SHOTS_DIR, { recursive: true });
  const out = join(SHOTS_DIR, name);
  await page.screenshot({ path: out, fullPage: false });
  console.log(`  [shot] ${out}`);
  return out;
}

const paneHas = (page, sel) => page.evaluate((s) => !!document.querySelector('.canvas-tab-pane.active')?.querySelector(s), sel);
const paneText = (page) => page.evaluate(() => document.querySelector('.canvas-tab-pane.active')?.textContent || '');
const waitDecoded = (page, timeout) => page.waitForFunction(() => {
  const v = document.querySelector('.canvas-tab-pane.active video');
  return v && v.readyState >= 1 && v.videoWidth > 0;
}, { timeout });

/* ═══════════════════════════ 主流程 ═══════════════════════════ */
rmSync(FIX_DIR, { recursive: true, force: true });
mkdirSync(FIX_DIR, { recursive: true });
const sampleMp4 = makeVideo('sample.mp4', 'libx264', 'aac');
const sampleWebm = makeVideo('sample.webm', 'libvpx-vp9', 'libopus');
const sampleMd = join(FIX_DIR, 'doc.md');
writeFileSync(sampleMd, '# MD-REGRESSION-MARKER\n\n正文保持可渲染。\n');
console.log(`[fixtures] mp4=${sampleMp4} (${readFileSync(sampleMp4).length}B) webm=${sampleWebm} (${readFileSync(sampleWebm).length}B) md=${sampleMd}`);

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });

console.log(`\n== ${BEFORE ? 'BEFORE（改前取证：Monaco 兜底应在位）' : 'AFTER（门禁：视频应出画面可播可拖）'} · mock 档 ==`);
{
  const { page, errors } = await boot(browser);

  // ── ① 视频打开链（workspace-open-item 全真链）──
  await openPath(page, sampleMp4);
  await sleep(1600); // openWorkspaceItem → pop.readFile 往返 + renderFile

  if (BEFORE) {
    const monaco = await paneHas(page, '.monaco-editor');
    const hasVideo = await paneHas(page, 'video');
    ok('[BEFORE] 空 Monaco 兜底在位（根因证据）', monaco && !hasVideo, `monaco=${monaco} video=${hasVideo}`);
    await shot(page, 'before-canvas-video-monaco-fallback.png');
  } else {
    let decoded = false;
    try { await waitDecoded(page, 4000); decoded = true; } catch { /* codec probe */ }
    if (!decoded) {
      // headless Chromium 缺 H.264 时换 webm 夹具（同一 viewer 机制；新开即激活）。
      console.log('  [codec] mp4 无 metadata → 换 webm 夹具重试');
      await openPath(page, sampleWebm);
      await sleep(1500);
      try { await waitDecoded(page, 4000); decoded = true; } catch { /* fallthrough */ }
    }
    const dims = await page.evaluate(() => {
      const v = document.querySelector('.canvas-tab-pane.active video');
      return v ? { w: v.videoWidth, h: v.videoHeight, dur: v.duration } : null;
    });
    const hasVideo = await paneHas(page, 'video.canvas-video');
    ok('视频元素在位（video.canvas-video）', hasVideo);
    ok('Monaco 兜底不在位', !(await paneHas(page, '.monaco-editor')));
    const hasControls = await page.evaluate(() => document.querySelector('.canvas-tab-pane.active video')?.controls === true);
    ok('controls=原生控件（无自绘播放器）', hasControls);
    ok('loadedmetadata 出画面（videoWidth>0）', decoded && !!dims && dims.w > 0, JSON.stringify(dims));

    // 播放推进
    const played = await page.evaluate(async () => {
      const v = document.querySelector('.canvas-tab-pane.active video');
      if (!v) return false;
      try { await v.play(); } catch { return false; }
      await new Promise((r) => setTimeout(r, 900));
      return !v.paused && v.currentTime > 0;
    }).catch(() => false);
    ok('可播放（play 后 currentTime 推进）', played);
    await shot(page, 'after-canvas-video-playing.png');

    // 拖进度条 = seek；真端点语义下 seek 依赖 Range 206
    const seeked = await page.evaluate(async () => {
      const v = document.querySelector('.canvas-tab-pane.active video');
      if (!v) return false;
      const target = Math.min(1.5, (v.duration || 3) * 0.5);
      const done = new Promise((res) => v.addEventListener('seeked', () => res(true), { once: true }));
      v.currentTime = target;
      const sawEvent = await Promise.race([done, new Promise((r) => setTimeout(() => r(false), 3000))]);
      return sawEvent === true && Math.abs(v.currentTime - target) < 0.3;
    }).catch(() => false);
    ok('进度条机制（seek 到位＝seeked 事件）', seeked);
    ok('Range 请求发生过（nf-file 替身收到 Range 头）', LAST.nfFileReqs.some((r) => r.range), JSON.stringify(LAST.nfFileReqs.slice(0, 3)));
    ok('替身响应 206（进度条拖动依赖）', rangeHits.length > 0, `rangeHits=${rangeHits.length}`);
    await shot(page, 'after-canvas-video-seeked.png');
  }

  // ── ② 回归抽查：md 打开不受影响（抽 1） ──
  await openPath(page, sampleMd);
  await sleep(1200);
  const mdText = await paneText(page);
  ok('回归抽查：md 打开仍渲染（MARKER 可见）', mdText.includes('MD-REGRESSION-MARKER'), mdText.slice(0, 80));
  await shot(page, 'after-regression-md-open.png');

  if (errors.length) console.log(`  [warn] pageerror（仅记录不断言）: ${errors.join(' | ').slice(0, 300)}`);
  await LAST.ctx.close();
}

/* ── LIVE 档（可选）：真网关真铸票真 nf-file ── */
if (LIVE_BASE && LIVE_HOME) {
  console.log('\n== LIVE 档（真实例：真铸票＋真 nf-file Range/Content-Type） ==');
  const { page, errors, nfFileReqs } = await boot(browser, { live: true });
  const tryLive = async (abs) => {
    await openPath(page, abs, { itemType: 'video', content: '' });
    await sleep(2000);
    try { await waitDecoded(page, 6000); return true; } catch { return false; }
  };
  let which = null;
  if (await tryLive(sampleMp4)) which = 'mp4';
  else if (await tryLive(sampleWebm)) which = 'webm';
  ok(`[LIVE] 真链出画面（真铸票＋真 nf-file；夹具=${which || '均不可解'}）`, !!which);
  ok('[LIVE] 真 nf-file 请求带 Range 头', nfFileReqs.some((r) => r.range), JSON.stringify(nfFileReqs.slice(0, 3)));
  if (which) await shot(page, `after-live-canvas-video-playing-${which}.png`);
  if (errors.length) console.log(`  [warn] pageerror（仅记录不断言）: ${errors.join(' | ').slice(0, 300)}`);
  await LAST.ctx.close();
} else {
  console.log('\n[LIVE 档跳过] 设 NEBFLOW_LIVE_BASE + NEBFLOW_LIVE_HOME 追加真实例档');
}

await browser.close();
webServer.close();
const failed = results.filter((r) => !r.pass);
console.log(`\n== ${results.length - failed.length}/${results.length} 项通过 ==`);
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => `  ✗ ${f.name} — ${f.detail}`).join('\n'));
  process.exit(1);
}
