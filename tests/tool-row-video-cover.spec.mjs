// tool-row-video-cover.spec.mjs — 两件一体验收（作者令 2026-11-05 20:18 / 20:28）：
//   ① 工具调用行：`>` 箭头 / spinner / 文字 **上下居中**（中线差 ≤1px）；
//   ② 工具调用行：**去模糊/去渐显**，结构定死为 `工具名` + 固定间距 + `参数` 流式，
//      工具名 x 坐标恒定；
//   ③ 视频内容封面（对话框 + Canvas）+ Canvas 视频圆角。
//
// 读数口径 = **真实浏览器 + 真实模块图 + 真实 CSS + 真实 DOM 读数**
// （getBoundingClientRect / getComputedStyle / canvas 像素统计），不靠目测。
// 静态文件面 = 本仓真 web 树（`WEB_ROOT` 可指到导出树跑「改前腿」），
// 数据面 = WS 帧替身，零后端、零 sbt、不碰 :6138。
//
// 运行：
//   node tests/tool-row-video-cover.spec.mjs --shots <dir>            # 改后门禁档
//   BEFORE=1 WEB_ROOT=/tmp/nf-web-before node tests/tool-row-video-cover.spec.mjs \
//     --shots <dir>                                                   # 改前取证档
// 可选 env：PORT=8143

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = process.env.WEB_ROOT ? resolve(process.env.WEB_ROOT) : resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8143);
const PORT_X = PORT + 1;               // 跨域腿（canvas 污染判定）第二个源
const BASE = `http://127.0.0.1:${PORT}`;
const BASE_X = `http://localhost:${PORT_X}`;
const BEFORE = process.env.BEFORE === '1';
const shotsArgIdx = process.argv.indexOf('--shots');
const OUT = shotsArgIdx > 0 ? resolve(process.argv[shotsArgIdx + 1])
  : process.env.SHOTS_DIR ? resolve(process.env.SHOTS_DIR)
    : resolve(ROOT, `.nebflow/evidence/tool-row-video-cover${BEFORE ? '-before' : ''}`);
const READINGS = join(OUT, BEFORE ? 'readings-before' : 'readings-after');
const FIX = join(tmpdir(), 'tool-row-video-cover-fixtures');
mkdirSync(OUT, { recursive: true });
mkdirSync(FIX, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function ok(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`  ${cond ? '✓' : '✗'} ${name}${cond ? '' : ` — ${detail}`}`);
  return !!cond;
}

/* ───────────── 夹具（运行时生成，不入 repo） ───────────── */
function makeVideo(name, w, h, dur = 3) {
  const out = join(FIX, name);
  execFileSync('ffmpeg', [
    '-y', '-loglevel', 'error',
    '-f', 'lavfi', '-i', `testsrc=duration=${dur}:size=${w}x${h}:rate=12`,
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-preset', 'ultrafast',
    '-movflags', '+faststart', out,
  ], { stdio: 'pipe' });
  return out;
}
/** 坏视频：mp4 扩展名 + 文本字节（解码必失败）。 */
function makeBrokenVideo() {
  const out = join(FIX, 'broken.mp4');
  writeFileSync(out, 'this is not a video file at all\n'.repeat(40));
  return out;
}

/* ───────────── 静态服务（真 web 树；两端口 = 两个源） ───────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ico': 'image/x-icon',
};
function serve(port) {
  const srv = createServer((req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${port}`);
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
  return new Promise((r) => srv.listen(port, '127.0.0.1', () => r(srv)));
}

/* ───────────── /api/nf-file 替身（票闸 + Range 206） ───────────── */
async function nfFileRoute(route, cors) {
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
  headers['Content-Type'] = 'video/mp4';
  // 跨域腿：带 CORS 头但 video 不带 crossorigin ⇒ 仍是「污染 canvas」的跨域媒体
  if (cors) headers['Access-Control-Allow-Origin'] = '*';
  if (rangeHeader) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader.trim());
    if (m && (m[1] !== '' || m[2] !== '')) {
      let start; let end;
      if (m[1] === '') { const n = Number(m[2]); start = Math.max(body.length - n, 0); end = body.length - 1; }
      else { start = Number(m[1]); end = m[2] === '' ? body.length - 1 : Math.min(Number(m[2]), body.length - 1); }
      if (start <= end && start < body.length) {
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

/* ───────────── 应用启动（与 canvas-video-stage.spec.mjs 同一档） ───────────── */
async function boot(browser, { viewport = { width: 1440, height: 900 } } = {}) {
  const ctx = await browser.newContext({ viewport, locale: 'zh-CN', colorScheme: 'light' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, (r) => nfFileRoute(r, false));
  await page.route(new RegExp(`^http://localhost:${PORT_X}/api/nf-file`), (r) => nfFileRoute(r, true));
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
        const ext = (rawPath.split('.').pop() || '').toLowerCase();
        const msg = {
          type: 'fileContent', path: rawPath, absPath: rawPath,
          itemType: ext === 'mp4' ? 'video' : 'code',
          fileName: rawPath.split('/').pop(), size: buf.length,
        };
        if (ext !== 'mp4') msg.content = buf.toString('utf8');
        ws.send(JSON.stringify(msg));
      } catch (e) {
        ws.send(JSON.stringify({ type: 'fileContent', error: String((e && e.message) || e), path: rawPath, absPath: rawPath }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 25000 });
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(500);
  return { ctx, page, errors };
}

async function shot(page, name, clip) {
  const out = join(OUT, name);
  await page.screenshot({ path: out, fullPage: false, ...(clip ? { clip } : {}) });
  console.log(`  [shot] ${out}`);
  return out;
}

/* ═════════════ ① 工具行探针（真实 DOM 读数） ═════════════ */
const WL_PROBE = () => {
  const row = document.querySelector('#chat .nf-workline') || document.querySelector('#chat .turn-header');
  if (!row) return { missing: 'row' };
  // 换项窗口内槽里会同时有两项（离场项 .is-out 在 380ms 后才摘）：只量**当前项**。
  const live = [...row.querySelectorAll('.nf-wl-item')].filter((x) => !x.classList.contains('is-out'));
  const item = live[live.length - 1] || null;
  const q = (s) => (item && item.querySelector(s)) || row.querySelector(s);
  const R = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { l: +r.left.toFixed(2), t: +r.top.toFixed(2), r: +r.right.toFixed(2), b: +r.bottom.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2), cy: +(((r.top + r.bottom) / 2)).toFixed(2) };
  };
  const chevron = R(q('.nf-chevron'));
  const chevronSvg = R(q('.nf-chevron svg'));
  const iconBox = R(q('.nf-wl-icon'));
  const spin = R(q('.nf-wl-spin'));
  const label = R(q('.nf-wl-label'));
  const detail = R(q('.nf-wl-detail'));
  const rowBox = R(row);
  const cs = (el) => (el ? getComputedStyle(el) : null);
  const ics = cs(item);
  const lcs = cs(q('.nf-wl-label'));
  const dcs = cs(q('.nf-wl-detail'));
  const rcs = cs(row);
  return {
    row: rowBox,
    rects: { chevron, chevronSvg, iconBox, spin, label, detail },
    centers: {
      row: rowBox ? rowBox.cy : null,
      chevron: chevron ? chevron.cy : null,
      chevronSvg: chevronSvg ? chevronSvg.cy : null,
      iconBox: iconBox ? iconBox.cy : null,
      spin: spin ? spin.cy : null,
      label: label ? label.cy : null,
      detail: detail ? detail.cy : null,
    },
    deltas: {
      chevronVsLabel: chevron && label ? +(chevron.cy - label.cy).toFixed(2) : null,
      spinVsLabel: spin && label ? +(spin.cy - label.cy).toFixed(2) : null,
      iconVsLabel: iconBox && label ? +(iconBox.cy - label.cy).toFixed(2) : null,
      detailVsLabel: detail && label ? +(detail.cy - label.cy).toFixed(2) : null,
      chevronVsRow: chevron && rowBox ? +(chevron.cy - rowBox.cy).toFixed(2) : null,
    },
    styles: {
      row: rcs && { lineHeight: rcs.lineHeight, fontSize: rcs.fontSize, opacity: rcs.opacity, animation: rcs.animationName, filter: rcs.filter, alignItems: rcs.alignItems },
      item: ics && { alignItems: ics.alignItems, gap: ics.gap, animation: ics.animationName, opacity: ics.opacity, filter: ics.filter, textShadow: ics.textShadow },
      label: lcs && {
        flex: lcs.flex, maxWidth: lcs.maxWidth, textOverflow: lcs.textOverflow, whiteSpace: lcs.whiteSpace,
        maskImage: lcs.maskImage, scrollLeft: q('.nf-wl-label').scrollLeft, fontFamily: lcs.fontFamily, color: lcs.color,
      },
      detail: dcs && {
        flex: dcs.flex, textOverflow: dcs.textOverflow, whiteSpace: dcs.whiteSpace,
        maskImage: dcs.maskImage, fontFamily: dcs.fontFamily, fontSize: dcs.fontSize,
        color: dcs.color, marginLeft: dcs.marginLeft,
        scrollLeft: q('.nf-wl-detail').scrollLeft,
        scrollWidth: q('.nf-wl-detail').scrollWidth, clientWidth: q('.nf-wl-detail').clientWidth,
        truncated: q('.nf-wl-detail').scrollWidth > q('.nf-wl-detail').clientWidth + 1,
        textLen: (q('.nf-wl-detail').textContent || '').length,
      },
    },
    itemCls: item ? item.className : '',
    labelX: label ? label.l : null,
    count: row.querySelectorAll('.nf-wl-item').length,
  };
};

async function buildWorkline(page, { label, detail }) {
  await page.evaluate(async ({ label, detail }) => {
    const wl = await import('/js/workline.js');
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    window.__view = { dom: { chat }, motionEnabled: () => true };
    wl.startWorklineItem(window.__view, label, 'tool');
    if (detail) wl.worklineToolDetail(window.__view, detail);
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  }, { label, detail });
}

/* 覆盖层像素统计（封面）：drawImage 进 canvas → 均值/方差/抽样色值。 */
const PIXEL_PROBE = async (sel) => {
  const img = document.querySelector(sel);
  if (!img) return { missing: sel };
  if (img.tagName === 'IMG' && img.decode) {
    try { await img.decode(); } catch { return { decodeFailed: true }; }
  }
  const w = 64;
  const ratio = img.naturalWidth ? img.naturalHeight / img.naturalWidth : 0.5625;
  const h = Math.max(1, Math.round(64 * ratio));
  const cvs = document.createElement('canvas');
  cvs.width = w; cvs.height = h;
  const ctx = cvs.getContext('2d', { willReadFrequently: true });
  try { ctx.drawImage(img, 0, 0, w, h); } catch (e) { return { drawFailed: String((e && e.name) || e) }; }
  let data;
  try { data = ctx.getImageData(0, 0, w, h).data; } catch (e) { return { tainted: String((e && e.name) || e) }; }
  let n = 0, sum = 0, sum2 = 0;
  const lum = [];
  for (let i = 0; i < data.length; i += 4) {
    const v = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
    lum.push(v); sum += v; sum2 += v * v; n += 1;
  }
  const mean = sum / n;
  const variance = sum2 / n - mean * mean;
  lum.sort((a, b) => a - b);
  const pick = (x, y) => { const i = (y * w + x) * 4; return `rgb(${data[i]},${data[i + 1]},${data[i + 2]})`; };
  return {
    samples: { w, h },
    mean: +mean.toFixed(2),
    variance: +variance.toFixed(2),
    min: +lum[0].toFixed(1),
    max: +lum[lum.length - 1].toFixed(1),
    distinctBuckets: new Set(lum.map((v) => Math.round(v / 8))).size,
    swatches: [pick(2, 2), pick(Math.floor(w / 2), Math.floor(h / 2)), pick(w - 3, h - 3), pick(Math.floor(w / 4), Math.floor(h / 4))],
  };
};

/* 光学中线探针：把「盒子中线」推进到「墨迹中线」。
   `getBoundingClientRect` 给的是**盒子**中线；作者要的是「看起来齐」。文字/字形在
   自己的行盒里并不垂直居中（基线上下留白不等、◜ 字形的墨迹又只占 em 方的一角），
   所以这里把每个件里**实际画出来的像素**量出来：
     ① 用 canvas 以同一字体把该件的字/字形画一次，扫像素求墨迹上下边界；
     ② 由字体 ascent/descent 与 line-height 反推该件行盒内基线位置（Chrome 的半行距
        均分规则），得到「墨迹中线距盒顶」；
     ③ 与盒中线（lineHeight/2）相减 = 该件自身的光学偏移；
     ④ 互相比 = 真正的光学中线差。 */
const OPTICAL_PROBE = () => {
  const row = document.querySelector('#chat .nf-workline');
  if (!row) return { missing: 'row' };
  const label = row.querySelector('.nf-wl-label');
  const spin = row.querySelector('.nf-wl-spin');
  const chev = row.querySelector('.nf-chevron svg');
  const cvs = document.createElement('canvas');
  cvs.width = 240; cvs.height = 64;
  const ctx = cvs.getContext('2d', { willReadFrequently: true });
  const ink = (text, font) => {
    ctx.clearRect(0, 0, 240, 64);
    ctx.font = font;
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = '#000';
    ctx.fillText(text, 10, 48);
    const d = ctx.getImageData(0, 0, 240, 64).data;
    let minY = 1e9, maxY = -1;
    for (let y = 0; y < 64; y++) {
      for (let x = 0; x < 240; x++) {
        if (d[(y * 240 + x) * 4 + 3] > 10) { if (y < minY) minY = y; if (y > maxY) maxY = y; }
      }
    }
    if (maxY < 0) return null;
    return { top: minY, bottom: maxY, cy: (minY + maxY) / 2, baseline: 48 };
  };
  const opticalOf = (text, css) => {
    const m = ink(text, `${css.fontWeight} ${css.fontSize} ${css.fontFamily}`);
    if (!m) return null;
    const mm = ctx.measureText('Mg');
    const asc = mm.fontBoundingBoxAscent;
    const desc = mm.fontBoundingBoxDescent;
    const lh = parseFloat(css.lineHeight) || (asc + desc);
    const halfLeading = (lh - (asc + desc)) / 2;
    const baselineFromTop = halfLeading + asc;
    const inkFromTop = baselineFromTop + (m.cy - m.baseline);
    return { lineHeight: lh, asc: +asc.toFixed(2), desc: +desc.toFixed(2), inkFromTop: +inkFromTop.toFixed(2), boxMid: +(lh / 2).toFixed(2), offset: +(inkFromTop - lh / 2).toFixed(2) };
  };
  const labelCs = getComputedStyle(label);
  const spinCs = getComputedStyle(spin);
  const labelOpt = opticalOf(label.textContent || 'M', labelCs);
  const spinOpt = opticalOf('\u25DC', spinCs);
  const R = (el) => { const r = el.getBoundingClientRect(); return { top: +r.top.toFixed(2), cy: +((r.top + r.bottom) / 2).toFixed(2) }; };
  const labelR = R(label);
  const spinR = R(spin);
  const chevR = chev ? R(chev) : null;
  return {
    labelOpt, spinOpt,
    labelInkCy: +(labelR.top + labelOpt.inkFromTop).toFixed(2),
    spinInkCy: +(spinR.top + spinOpt.inkFromTop).toFixed(2),
    chevInkCy: chevR ? chevR.cy : null,
    opticalDelta: {
      spinVsLabel: +((spinR.top + spinOpt.inkFromTop) - (labelR.top + labelOpt.inkFromTop)).toFixed(2),
      chevronVsLabel: chevR ? +(chevR.cy - (labelR.top + labelOpt.inkFromTop)).toFixed(2) : null,
    },
  };
};

/* 像素级「墨迹中线」读数：把**已落盘的截图**读回页面里扫像素（模型无关 —— 量的
   就是用户在屏幕上看到的那些像素，不是按字体度量的推演）。
   每件在自己的 x 区间内求「与底色有差异」的像素的上下边界 → 墨迹中线。
   这份读数用来回答作者的原话「没有上下居中对齐」（盒子中线已由 WL_PROBE 给出）。 */
async function inkReadings(page, shotPath, clip, pieces) {
  const b64 = readFileSync(shotPath).toString('base64');
  return page.evaluate(async ({ b64, clip, pieces }) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b64;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.naturalWidth; c.height = img.naturalHeight;
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    const data = ctx.getImageData(0, 0, c.width, c.height).data;
    const dpr = c.width / clip.width;
    const px = (x, y) => { const i = (y * c.width + x) * 4; return [data[i], data[i + 1], data[i + 2]]; };
    const out = {};
    for (const p of pieces) {
      const x0 = Math.max(0, Math.min(c.width - 1, Math.floor((p.l - clip.x) * dpr)));
      const x1 = Math.max(0, Math.min(c.width - 1, Math.ceil((p.r - clip.x) * dpr)));
      const y0 = Math.max(0, Math.min(c.height - 1, Math.floor((p.t - clip.y) * dpr)));
      const y1 = Math.max(0, Math.min(c.height - 1, Math.ceil((p.b - clip.y) * dpr)));
      // 底色 = 该矩形左上角像素（行底色，恒为纯色）
      const bg = px(x0, y0);
      let minY = 1e9, maxY = -1, count = 0;
      for (let y = y0; y <= y1; y++) {
        for (let x = x0; x <= x1; x++) {
          const q = px(x, y);
          const dist = Math.abs(q[0] - bg[0]) + Math.abs(q[1] - bg[1]) + Math.abs(q[2] - bg[2]);
          if (dist > 30) { count++; if (y < minY) minY = y; if (y > maxY) maxY = y; }
        }
      }
      out[p.name] = maxY < 0
        ? { empty: true }
        : {
          inkTop: +(y0 / dpr + clip.y + (minY - y0) / dpr).toFixed(2),
          inkBottom: +(clip.y + (maxY + 1) / dpr).toFixed(2),
          inkCy: +(clip.y + ((minY + maxY + 1) / 2) / dpr).toFixed(2),
          inkH: +((maxY - minY + 1) / dpr).toFixed(2),
          px: count,
        };
    }
    out._dpr = dpr;
    return out;
  }, { b64, clip, pieces });
}

function rowClipOf(p) {
  return {
    x: Math.max(0, p.row.l - 8), y: Math.max(0, p.row.t - 8),
    width: Math.min(560, p.row.w + 16), height: p.row.h + 16,
  };
}
function inkPieces(p, names = ['chevron', 'iconBox', 'label', 'detail']) {
  const out = [];
  for (const n of names) {
    const r = p.rects[n];
    if (r) out.push({ name: n, l: r.l, r: r.r, t: r.t, b: r.b });
  }
  return out;
}
const inkDelta = (ink, a, b) => (ink[a] && ink[b] && !ink[a].empty && !ink[b].empty ? +(ink[a].inkCy - ink[b].inkCy).toFixed(2) : null);

/* ═════════════ 主流程 ═════════════ */
const srv = await serve(PORT);
const srvX = await serve(PORT_X);
const browser = await chromium.launch();
function save(name, obj) {
  mkdirSync(READINGS, { recursive: true });
  writeFileSync(join(READINGS, `${name}.json`), JSON.stringify(obj, null, 2));
}

try {
  const { page, ctx, errors } = await boot(browser);
  await page.evaluate(() => document.documentElement.style.setProperty('--canvas-width', '720px'));
  await sleep(400);

  /* ── ① spinner 单行态 ── */
  console.log('\n══ ① 工具行 · spinner 单行态 ══');
  await buildWorkline(page, { label: 'Mail', detail: '{"to":"spider@local"}' });
  await sleep(500);
  const wl1 = await page.evaluate(WL_PROBE);
  save('wl-spinner', wl1);
  console.log(`   row ${wl1.row.w}×${wl1.row.h} | chevron cy=${wl1.centers.chevron} spin cy=${wl1.centers.spin} label cy=${wl1.centers.label}`);
  console.log(`   Δ chevron-label=${wl1.deltas.chevronVsLabel} spin-label=${wl1.deltas.spinVsLabel} icon-label=${wl1.deltas.iconVsLabel} detail-label=${wl1.deltas.detailVsLabel}`);
  console.log(`   row styles=${JSON.stringify(wl1.styles.row)}`);
  console.log(`   item styles=${JSON.stringify(wl1.styles.item)}`);
  console.log(`   label styles=${JSON.stringify(wl1.styles.label)}`);
  console.log(`   detail styles=${JSON.stringify(wl1.styles.detail)}`);
  const rowClip = {
    x: Math.max(0, wl1.row.l - 8), y: Math.max(0, wl1.row.t - 8),
    width: Math.min(560, wl1.row.w + 16), height: wl1.row.h + 16,
  };
  const shotSpin = await shot(page, `${BEFORE ? 'before' : 'after'}-wl-spinner.png`, rowClip);
  const opt1 = await page.evaluate(OPTICAL_PROBE);
  save('wl-spinner-optical', opt1);
  const ink1 = await inkReadings(page, shotSpin, rowClip, [
    { name: 'chevron', l: wl1.rects.chevron.l, r: wl1.rects.chevron.r, t: wl1.rects.chevron.t, b: wl1.rects.chevron.b },
    { name: 'icon', l: wl1.rects.iconBox.l, r: wl1.rects.iconBox.r, t: wl1.rects.iconBox.t, b: wl1.rects.iconBox.b },
    { name: 'label', l: wl1.rects.label.l, r: wl1.rects.label.r, t: wl1.rects.label.t, b: wl1.rects.label.b },
  ]);
  save('wl-spinner-ink', ink1);
  const inkDeltas = (r) => ({
    chevronVsLabel: r.chevron && r.label && !r.chevron.empty ? +(r.chevron.inkCy - r.label.inkCy).toFixed(2) : null,
    iconVsLabel: r.icon && r.label && !r.icon.empty ? +(r.icon.inkCy - r.label.inkCy).toFixed(2) : null,
  });
  console.log(`   PIXEL ink (dpr=${ink1._dpr}): chevron cy=${ink1.chevron.inkCy} h=${ink1.chevron.inkH} | spinner cy=${ink1.icon.inkCy} h=${ink1.icon.inkH} | label cy=${ink1.label.inkCy} h=${ink1.label.inkH}`);
  console.log(`   PIXEL Δ: chevron-label=${inkDeltas(ink1).chevronVsLabel} spinner-label=${inkDeltas(ink1).iconVsLabel}`);
  console.log(`   MODEL Δ: spin-label=${opt1.opticalDelta.spinVsLabel} chevron-label=${opt1.opticalDelta.chevronVsLabel}`);  await shot(page, `${BEFORE ? 'before' : 'after'}-wl-spinner-full.png`);
  execFileSync('sips', ['--resampleWidth', '1120', shotSpin, '--out', shotSpin.replace('.png', '-x4.png')], { stdio: 'pipe' });

  /* ── ② 流式三帧 ── */
  console.log('\n══ ② 工具行 · 参数流式三帧 ══');
  const FRAMES = [
    '{"command":"cd /Users/kaiyu/Claude code/Stand"',
    '{"command":"cd /Users/kaiyu/Claude code/Stand && git log -1 --oneline"',
    '{"command":"cd /Users/kaiyu/Claude code/Stand && git log -1 --oneline && git status --porcelain | head -20 && echo done-done-done"',
  ];
  await page.evaluate(async () => {
    const wl = await import('/js/workline.js');
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    window.__view = { dom: { chat }, motionEnabled: () => true };
    wl.startWorklineItem(window.__view, 'Bash', 'tool');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  });
  const frames = [];
  let rowClip2 = rowClip;
  for (let i = 0; i < FRAMES.length; i++) {
    await page.evaluate(async (t) => {
      const wl = await import('/js/workline.js');
      wl.worklineToolDetail(window.__view, t);
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    }, FRAMES[i]);
    const f = await page.evaluate(WL_PROBE);
    rowClip2 = { x: Math.max(0, f.row.l - 8), y: Math.max(0, f.row.t - 8), width: Math.min(560, f.row.w + 16), height: f.row.h + 16 };
    frames.push({
      frame: i + 1, labelX: f.labelX, detailX: f.rects.detail ? f.rects.detail.l : null,
      detailW: f.rects.detail ? f.rects.detail.w : null, truncated: f.styles.detail.truncated,
      textLen: f.styles.detail.textLen, deltas: f.deltas,
    });
    const s = await shot(page, `${BEFORE ? 'before' : 'after'}-wl-stream-${i + 1}.png`, rowClip2);
    execFileSync('sips', ['--resampleWidth', '1120', s, '--out', s.replace('.png', '-x4.png')], { stdio: 'pipe' });
    console.log(`   frame${i + 1}: labelX=${f.labelX} detailX=${f.rects.detail ? f.rects.detail.l : null} detailW=${f.rects.detail ? f.rects.detail.w : null} truncated=${f.styles.detail.truncated} textLen=${f.styles.detail.textLen} Δspinner=${f.deltas.spinVsLabel}`);
  }
  save('wl-stream-frames', { frames });
  const xs = frames.map((f) => Math.round(f.labelX * 100) / 100);
  const labelStable = xs.every((x) => Math.abs(x - xs[0]) <= 0.5);

  /* ── ③ 无 spinner（完成态勾 / 失败态叉） ── */
  console.log('\n══ ③ 工具行 · 完成态（勾）/失败态（叉）══');
  await page.evaluate(async () => {
    const wl = await import('/js/workline.js');
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    window.__view = { dom: { chat }, motionEnabled: () => true };
    wl.startWorklineItem(window.__view, 'Read', 'tool');
    wl.worklineToolDetail(window.__view, '/Users/kaiyu/Claude code/Stand/build.sbt');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    wl.doneWorklineItem(window.__view, 'Read', 'Read — 已读取 build.sbt');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  });
  await sleep(700); // > ROLL_MS(380)：换项窗口关掉后再量
  const wl3 = await page.evaluate(WL_PROBE);
  save('wl-done', wl3);
  console.log(`   done: Δ chevron-label=${wl3.deltas.chevronVsLabel} icon-label=${wl3.deltas.iconVsLabel} itemCls="${wl3.itemCls}" anim=${wl3.styles.item && wl3.styles.item.animation} opacity=${wl3.styles.item && wl3.styles.item.opacity}`);
  const s3 = await shot(page, `${BEFORE ? 'before' : 'after'}-wl-done.png`, {
    x: Math.max(0, wl3.row.l - 8), y: Math.max(0, wl3.row.t - 8), width: Math.min(560, wl3.row.w + 16), height: wl3.row.h + 16,
  });
  execFileSync('sips', ['--resampleWidth', '1120', s3, '--out', s3.replace('.png', '-x4.png')], { stdio: 'pipe' });
  const ink3 = await inkReadings(page, s3, rowClipOf(wl3), inkPieces(wl3, ['chevron', 'iconBox', 'label']));
  save('wl-done-ink', ink3);
  console.log(`   done PIXEL ink: chevron cy=${ink3.chevron.inkCy} | check cy=${ink3.iconBox.inkCy} h=${ink3.iconBox.inkH} | label cy=${ink3.label.inkCy}`);
  console.log(`   done PIXEL Δ: chevron-label=${inkDelta(ink3, 'chevron', 'label')} check-label=${inkDelta(ink3, 'iconBox', 'label')}`);

  await page.evaluate(async () => {
    const wl = await import('/js/workline.js');
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    window.__view = { dom: { chat }, motionEnabled: () => true };
    wl.startWorklineItem(window.__view, 'Bash', 'tool');
    wl.worklineToolDetail(window.__view, 'rm -rf /tmp/nope');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    wl.failWorklineItem(window.__view, 'Bash', 'Bash — 退出码 1');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  });
  await sleep(700);
  const wlErr = await page.evaluate(WL_PROBE);
  save('wl-error', wlErr);
  console.log(`   error: Δ chevron-label=${wlErr.deltas.chevronVsLabel} itemCls="${wlErr.itemCls}"`);
  await shot(page, `${BEFORE ? 'before' : 'after'}-wl-error.png`, {
    x: Math.max(0, wlErr.row.l - 8), y: Math.max(0, wlErr.row.t - 8), width: Math.min(560, wlErr.row.w + 16), height: wlErr.row.h + 16,
  });

  /* ── ④ 两行摘要 + 展开态箭头 ── */
  console.log('\n══ ④ 工具行 · 两行摘要 + 展开态箭头 ══');
  await page.evaluate(async () => {
    const wl = await import('/js/workline.js');
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    window.__view = { dom: { chat }, motionEnabled: () => true };
    wl.startWorklineItem(window.__view, 'Bash', 'tool');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const row = wl.settleWorklineAsHeader(window.__view, ['3 次工具调用', '128 行输出', '2 个文件已改写', '1 个测试套件全绿'], 't');
    // 强制两行：宽度收窄到 380px（摘要在这些段边界处必然折到第二行；段内 nowrap
    // 是既定契约，折行只发生在「·」处）。
    if (row) { row.style.width = '380px'; row.setAttribute('aria-expanded', 'true'); }
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  });
  await sleep(700);
  const wlWrap = await page.evaluate(() => {
    const row = document.querySelector('#chat .turn-header');
    if (!row) return { missing: 'header' };
    const R = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { l: +r.left.toFixed(2), t: +r.top.toFixed(2), b: +r.bottom.toFixed(2), w: +r.width.toFixed(2), h: +r.height.toFixed(2), cy: +(((r.top + r.bottom) / 2)).toFixed(2) };
    };
    const chev = row.querySelector('.nf-chevron');
    const liveItems = [...row.querySelectorAll('.nf-wl-item')].filter((x) => !x.classList.contains('is-out'));
    const liveItem = liveItems[liveItems.length - 1] || row;
    const label = liveItem.querySelector('.nf-wl-label');
    const cs = getComputedStyle(chev || row);
    const lcs = label ? getComputedStyle(label) : null;
    const rcsh = getComputedStyle(row);
    const lhRaw = (lcs && parseFloat(lcs.lineHeight)) || parseFloat(rcsh.lineHeight) || 18;
    return {
      row: R(row), chevron: R(chev), label: R(label),
      chevronTransform: cs.transform, rowAlign: rcsh.alignItems,
      labelLineHeight: lhRaw,
      lines: label ? Math.round(label.getBoundingClientRect().height / lhRaw) : 0,
      delta: (R(chev) && R(label)) ? +(R(chev).cy - R(label).cy).toFixed(2) : null,
    };
  });
  save('wl-wrap', wlWrap);
  console.log(`   wrap: lines=${wlWrap.lines} Δ=${wlWrap.delta} transform=${wlWrap.chevronTransform} rowAlign=${wlWrap.rowAlign}`);
  const sWrap = await shot(page, `${BEFORE ? 'before' : 'after'}-wl-wrap-expanded.png`, wlWrap.row ? {
    x: Math.max(0, wlWrap.row.l - 8), y: Math.max(0, wlWrap.row.t - 8),
    width: Math.min(620, wlWrap.row.w + 16), height: Math.min(140, wlWrap.row.h + 16),
  } : undefined);
  const wrapClip = {
    x: Math.max(0, wlWrap.row.l - 8), y: Math.max(0, wlWrap.row.t - 8),
    width: Math.min(620, wlWrap.row.w + 16), height: Math.min(140, wlWrap.row.h + 16),
  };
  const inkWrap = await inkReadings(page, sWrap, wrapClip, [
    { name: 'chevron', l: wlWrap.chevron.l, r: wlWrap.chevron.l + wlWrap.chevron.w, t: wlWrap.chevron.t, b: wlWrap.chevron.b },
    { name: 'label', l: wlWrap.label.l, r: wlWrap.label.l + wlWrap.label.w, t: wlWrap.label.t, b: wlWrap.label.b },
  ]);
  save('wl-wrap-ink', inkWrap);
  console.log(`   wrap PIXEL ink: chevron cy=${inkWrap.chevron.inkCy} (${wlWrap.lines} 行文本块) label cy=${inkWrap.label.inkCy} ⇒ Δ=${inkDelta(inkWrap, 'chevron', 'label')}`);

  /* ═════════════ 视频封面 / 圆角 ═════════════ */
  const sample = makeVideo('sample.mp4', 480, 270);
  const portrait = makeVideo('portrait.mp4', 270, 480);
  const broken = makeBrokenVideo();

  console.log('\n══ ⑤ Canvas 视频面：封面 + 圆角 ══');
  await page.evaluate((p) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + p, itemType: '', content: '', absPath: p, size: 0, title: p.split('/').pop() },
    }));
  }, sample);
  await page.waitForFunction(() => {
    const v = document.querySelector('.canvas-tab-pane.active video');
    return v && v.readyState >= 1 && v.videoWidth > 0;
  }, { timeout: 12000 });
  await page.waitForFunction(() => !!document.querySelector('.canvas-tab-pane.active .nf-video-cover'), { timeout: 9000 }).catch(() => {});
  await sleep(700);
  const canvasProbe = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const video = pane.querySelector('video');
    // 画面盒 = 视频元素自身（本批收口：不引入包裹层 —— 包裹层会让
    // `video.parentElement` 不再是可用区舞台，撞坏 canvas-video-stage.spec.mjs 的
    // 「可用区即被利用 ≥60% pane」四档读数，实测 0.427/0.417/0.190/0.427 全红）。
    // 改前树同口径（那里画面盒本来也是 video 自己），两档可比。
    const frame = video;
    const cover = pane.querySelector('.nf-video-cover');
    const R = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { l: +r.left.toFixed(1), t: +r.top.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
    const cs = getComputedStyle(frame);
    return {
      coverPresent: !!cover,
      coverBox: R(cover), frameBox: R(frame), videoBox: R(video),
      radius: cs.borderRadius, overflow: cs.overflow,
      coverRadius: cover ? getComputedStyle(cover).borderRadius : null,
      videoRadius: getComputedStyle(video).borderRadius,
      objectFit: getComputedStyle(video).objectFit,
      ar: +(video.videoWidth / (video.videoHeight || 1)).toFixed(3),
      frameAR: +(R(frame).w / R(frame).h).toFixed(3),
      stats: window.__nfVideoCover ? { ...window.__nfVideoCover } : null,
    };
  });
  const coverPix = await page.evaluate(PIXEL_PROBE, '.canvas-tab-pane.active .nf-video-cover');
  save('canvas-cover', { ...canvasProbe, coverPixels: coverPix });
  console.log(`   cover=${canvasProbe.coverPresent} videoBox ${canvasProbe.frameBox.w}×${canvasProbe.frameBox.h} radius=${canvasProbe.radius} coverRadius=${canvasProbe.coverRadius} overflow=${canvasProbe.overflow}`);
  console.log(`   coverBox ${canvasProbe.coverBox ? canvasProbe.coverBox.w + '×' + canvasProbe.coverBox.h : 'none'} (Δ vs video ${canvasProbe.coverBox ? (canvasProbe.coverBox.w - canvasProbe.videoBox.w).toFixed(1) + '/' + (canvasProbe.coverBox.h - canvasProbe.videoBox.h).toFixed(1) : '-'})`);
  console.log(`   frameAR=${canvasProbe.frameAR} videoAR=${canvasProbe.ar} objectFit=${canvasProbe.objectFit}`);
  console.log(`   cover pixels: mean=${coverPix.mean} var=${coverPix.variance} buckets=${coverPix.distinctBuckets} swatches=${JSON.stringify(coverPix.swatches)}`);
  await shot(page, `${BEFORE ? 'before' : 'after'}-canvas-video.png`);
  const closeup = await shot(page, `${BEFORE ? 'before' : 'after'}-canvas-video-closeup.png`, canvasProbe.frameBox ? {
    x: canvasProbe.frameBox.l + canvasProbe.frameBox.w - 120,
    y: canvasProbe.frameBox.t + canvasProbe.frameBox.h - 120,
    width: 120, height: 120,
  } : undefined);
  execFileSync('sips', ['--resampleWidth', '720', closeup, '--out', closeup.replace('.png', '-x6.png')], { stdio: 'pipe' });

  console.log('\n══ ⑤b 竖屏：比例不变形 ══');
  await page.evaluate((p) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + p, itemType: '', content: '', absPath: p, size: 0, title: p.split('/').pop() },
    }));
  }, portrait);
  await page.waitForFunction(() => {
    const v = document.querySelector('.canvas-tab-pane.active video');
    return v && v.videoWidth === 270;
  }, { timeout: 12000 }).catch(() => {});
  await sleep(900);
  const portraitRead = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const video = pane.querySelector('video');
    // 画面盒 = 视频元素自身（同 ⑤ 口径）：contain 尺寸 + 圆角都挂在它上面，
    // 竖屏因此必须与视频自身 AR 一致（不拉伸、不裁切）。
    const frame = video;
    const r = frame.getBoundingClientRect();
    return {
      frame: { w: +r.width.toFixed(1), h: +r.height.toFixed(1) }, ar: +(r.width / r.height).toFixed(3),
      videoAR: +(video.videoWidth / video.videoHeight).toFixed(3),
      radius: getComputedStyle(frame).borderRadius, objectFit: getComputedStyle(video).objectFit,
      cover: !!pane.querySelector('.nf-video-cover'),
    };
  });
  save('canvas-portrait', portraitRead);
  console.log(`   portrait: frame ${portraitRead.frame.w}×${portraitRead.frame.h} ar=${portraitRead.ar} videoAR=${portraitRead.videoAR} cover=${portraitRead.cover}`);
  await shot(page, `${BEFORE ? 'before' : 'after'}-canvas-portrait.png`);

  /* ── ⑤c Canvas 内嵌可播：点自研播放钮 → 真在走帧 + 封面让位 ──
     判据不是「画布里有 <video>」，而是①不暂停②currentTime 真的增长（走帧）③封面已让位。
     改前档（BEFORE=1）同样跑这段：那里没有封面层 ⇒ cover=false 而播放读数照旧 ——
     这既是「断言只钉行为、不钉实现」的证据，也是本批没把播放面改坏的对照。 */
  console.log('\n══ ⑤c Canvas 内嵌可播 ══');
  const cvBeforePlay = await page.evaluate(() => {
    const v = document.querySelector('.canvas-tab-pane.active video');
    return { paused: v.paused, t: +v.currentTime.toFixed(2), readyState: v.readyState, controls: v.controls };
  });
  await page.evaluate(() => {
    const btn = document.querySelector('.canvas-tab-pane.active .canvas-video-bar .canvas-video-btn');
    if (btn) btn.click();
    else { const v = document.querySelector('.canvas-tab-pane.active video'); if (v) v.play().catch(() => {}); }
  });
  await sleep(1000);
  const cvAfterPlay = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const v = pane.querySelector('video');
    return { paused: v.paused, t: +v.currentTime.toFixed(2), cover: !!pane.querySelector('.nf-video-cover') };
  });
  save('canvas-play', { before: cvBeforePlay, after: cvAfterPlay });
  console.log(`   播放前: paused=${cvBeforePlay.paused} t=${cvBeforePlay.t} controls=${cvBeforePlay.controls} ; 播放后: paused=${cvAfterPlay.paused} t=${cvAfterPlay.t} coverPresent=${cvAfterPlay.cover}`);
  await shot(page, `${BEFORE ? 'before' : 'after'}-canvas-video-playing.png`);

  /* ── ⑥ 对话框视频面 ── */
  console.log('\n══ ⑥ 对话框视频面：封面 ══');
  await page.evaluate(async ({ p }) => {
    const pop = await import('/js/popArtifacts.js');
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    const row = document.createElement('div');
    row.className = 'row pop-artifact';
    row.id = 'cover-test-row';
    row.appendChild(pop.buildMediaFlat({ kind: 'video', name: 'sample.mp4', path: p }, { onOpen: null, corner: null }));
    chat.appendChild(row);
  }, { p: sample });
  await page.waitForFunction(() => {
    const v = document.querySelector('#cover-test-row video');
    return v && v.videoWidth > 0;
  }, { timeout: 12000 });
  await page.waitForFunction(() => !!document.querySelector('#cover-test-row .nf-video-cover'), { timeout: 9000 }).catch(() => {});
  await sleep(700);
  const chatRead = await page.evaluate(() => {
    const rowEl = document.querySelector('#cover-test-row');
    const video = rowEl.querySelector('video');
    const cover = rowEl.querySelector('.nf-video-cover');
    const box = rowEl.querySelector('.pop-media-flat');
    const R = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { l: +r.left.toFixed(1), t: +r.top.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
    const bcs = getComputedStyle(box);
    return {
      coverPresent: !!cover, coverBox: R(cover), videoBox: R(video), cardBox: R(box),
      cardRadius: bcs.borderRadius, cardOverflow: bcs.overflow,
      videoBg: getComputedStyle(video).backgroundColor,
      stats: window.__nfVideoCover ? { ...window.__nfVideoCover } : null,
    };
  });
  const chatPix = await page.evaluate(PIXEL_PROBE, '#cover-test-row .nf-video-cover');
  save('chat-cover', { ...chatRead, coverPixels: chatPix });
  console.log(`   chat cover=${chatRead.coverPresent} card ${chatRead.cardBox.w}×${chatRead.cardBox.h} radius=${chatRead.cardRadius}`);
  console.log(`   cover pixels: mean=${chatPix.mean} var=${chatPix.variance} buckets=${chatPix.distinctBuckets} swatches=${JSON.stringify(chatPix.swatches)}`);
  const chatClip = chatRead.cardBox ? {
    x: Math.max(0, chatRead.cardBox.l - 12), y: Math.max(0, chatRead.cardBox.t - 12),
    width: chatRead.cardBox.w + 24, height: chatRead.cardBox.h + 24,
  } : undefined;
  const sChat = await shot(page, `${BEFORE ? 'before' : 'after'}-chat-video.png`, chatClip);
  execFileSync('sips', ['--resampleWidth', '736', sChat, '--out', sChat.replace('.png', '-x3.png')], { stdio: 'pipe' });
  await shot(page, `${BEFORE ? 'before' : 'after'}-chat-video-full.png`);

  console.log('\n══ ⑦ 对话框内嵌可播：点播放 → 真在走帧 + 封面让位 ══');
  const chatBeforePlay = await page.evaluate(() => {
    const v = document.querySelector('#cover-test-row video');
    return { paused: v.paused, t: +v.currentTime.toFixed(2), readyState: v.readyState };
  });
  await page.evaluate(() => { const v = document.querySelector('#cover-test-row video'); if (v) v.play().catch(() => {}); });
  await sleep(1000);
  const afterPlay = await page.evaluate(() => {
    const v = document.querySelector('#cover-test-row video');
    return {
      cover: !!document.querySelector('#cover-test-row .nf-video-cover'),
      paused: v.paused,
      t: +v.currentTime.toFixed(2),
      // 画面真的解出帧（不是「有个 video 元素」就算过）
      decoded: v.videoWidth > 0 && v.readyState >= 2,
    };
  });
  save('chat-after-play', { before: chatBeforePlay, after: afterPlay });
  console.log(`   播放前: paused=${chatBeforePlay.paused} t=${chatBeforePlay.t} ; 播放后: paused=${afterPlay.paused} t=${afterPlay.t} decoded=${afterPlay.decoded} coverPresent=${afterPlay.cover}`);
  await shot(page, `${BEFORE ? 'before' : 'after'}-chat-video-playing.png`, chatClip);

  /* ── ⑧ 懒加载 ── */
  console.log('\n══ ⑧ 懒加载：不可见不抓帧 ══');
  /* 24 份**不同路径**的同内容视频：缓存键 = 元素 src（票据 URL 逐 path 唯一）⇒
     每份都是独立的抓帧任务，计数才名副其实（同路径 24 份会全命中封面缓存，
     grabs 恒 0 —— 那种读数证明不了懒加载）。 */
  const lazyPaths = [];
  for (let i = 0; i < 24; i++) {
    const p = join(FIX, `lazy-${i}.mp4`);
    writeFileSync(p, readFileSync(sample));
    lazyPaths.push(p);
  }
  const lazyRead = await page.evaluate(async ({ paths }) => {
    const pop = await import('/js/popArtifacts.js');
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    chat.style.overflow = 'auto';
    if (window.__nfVideoCover) window.__nfVideoCover.reset();
    paths.forEach((p, i) => {
      const row = document.createElement('div');
      row.className = 'row pop-artifact';
      row.appendChild(pop.buildMediaFlat({ kind: 'video', name: `v${i}.mp4`, path: p }, { onOpen: null, corner: null }));
      chat.appendChild(row);
    });
    await new Promise((r) => setTimeout(r, 4000));
    const rows = [...chat.querySelectorAll('.row.pop-artifact')];
    const cb = chat.getBoundingClientRect();
    const visibleRows = rows.filter((r) => {
      const b = r.getBoundingClientRect();
      return b.bottom > cb.top && b.top < cb.bottom;
    }).length;
    const snap = () => ({ ...window.__nfVideoCover });
    const first = snap();
    const coversFirst = chat.querySelectorAll('.nf-video-cover').length;
    chat.scrollTop = chat.scrollHeight;
    await new Promise((r) => setTimeout(r, 4000));
    const second = snap();
    const coversSecond = chat.querySelectorAll('.nf-video-cover').length;
    const playheads = [...chat.querySelectorAll('video')].map((v) => Math.round(v.currentTime * 100) / 100);
    return {
      total: paths.length, visibleRowsBeforeScroll: visibleRows,
      grabsBeforeScroll: first.grabs, cacheHitsBefore: first.cacheHits, coversBeforeScroll: coversFirst,
      grabsAfterScroll: second.grabs, cacheHitsAfter: second.cacheHits, coversAfterScroll: coversSecond,
      fails: second.fails,
      playheadMax: Math.max(...playheads),
    };
  }, { paths: lazyPaths });
  save('lazy', lazyRead);
  console.log(`   ${lazyRead.total} 个视频面 · 首屏可见行=${lazyRead.visibleRowsBeforeScroll} ⇒ grabs=${lazyRead.grabsBeforeScroll} cacheHits=${lazyRead.cacheHitsBefore} covers=${lazyRead.coversBeforeScroll}`);
  console.log(`   滚到底再读：grabs=${lazyRead.grabsAfterScroll} cacheHits=${lazyRead.cacheHitsAfter} covers=${lazyRead.coversAfterScroll} fails=${lazyRead.fails}`);
  console.log(`   抓帧后播放头最大值=${lazyRead.playheadMax}（应 0：抓帧不动用户播放头）`);
  await shot(page, `${BEFORE ? 'before' : 'after'}-lazy-bottom.png`);

  /* ── ⑨ 失败降级 ── */
  console.log('\n══ ⑨ 失败降级 ══');
  const failRead = await page.evaluate(async ({ broken, sample }) => {
    const pop = await import('/js/popArtifacts.js');
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    chat.style.overflow = '';
    chat.scrollTop = 0;
    if (window.__nfVideoCover) window.__nfVideoCover.reset();
    const mk = (id, p, name) => {
      const row = document.createElement('div');
      row.className = 'row pop-artifact';
      row.id = id;
      row.appendChild(pop.buildMediaFlat({ kind: 'video', name, path: p }, { onOpen: null, corner: null }));
      chat.appendChild(row);
    };
    mk('fail-decode', broken, 'broken.mp4');
    mk('ok-control', sample, 'sample.mp4');
    await new Promise((r) => setTimeout(r, 6000));
    const r = (id) => {
      const el = document.getElementById(id);
      const v = el.querySelector('video');
      const b = el.querySelector('.pop-media-flat').getBoundingClientRect();
      return {
        cover: !!el.querySelector('.nf-video-cover'),
        failed: v.dataset.nfMediaFailed === '1' || v.classList.contains('nf-img-failed'),
        videoBg: getComputedStyle(v).backgroundColor,
        box: { w: Math.round(b.width), h: Math.round(b.height) },
      };
    };
    return { decode: r('fail-decode'), ok: r('ok-control'), stats: { ...window.__nfVideoCover } };
  }, { broken, sample });
  save('fail-decode', failRead);
  console.log(`   坏视频: cover=${failRead.decode.cover} failed=${failRead.decode.failed} bg=${failRead.decode.videoBg} box=${failRead.decode.box.w}×${failRead.decode.box.h}`);
  console.log(`   对照:   cover=${failRead.ok.cover} box=${failRead.ok.box.w}×${failRead.ok.box.h} stats=${JSON.stringify(failRead.stats)}`);
  await shot(page, `${BEFORE ? 'before' : 'after'}-fail-fallback.png`);

  /* 跨域污染腿 */
  const corsRead = await page.evaluate(async ({ url }) => {
    let mod;
    try { mod = await import('/js/videoCover.js'); } catch { return { moduleAbsent: true, cover: false }; }
    const { attachVideoCover } = mod;
    const wrap = document.createElement('div');
    wrap.className = 'pop-media';
    wrap.style.cssText = 'position:relative;width:220px;height:124px';
    wrap.id = 'cors-face';
    const v = document.createElement('video');
    v.className = 'pop-video';
    v.preload = 'metadata';
    v.playsInline = true;
    wrap.appendChild(v);
    document.getElementById('chat').appendChild(wrap);
    if (window.__nfVideoCover) window.__nfVideoCover.reset();
    v.src = url;
    attachVideoCover(v, {});
    await new Promise((r) => setTimeout(r, 7000));
    return { cover: !!wrap.querySelector('.nf-video-cover'), stats: { ...window.__nfVideoCover }, readyState: v.readyState, w: v.videoWidth };
  }, { url: `${BASE_X}/api/nf-file?ticket=t&path=${encodeURIComponent(sample)}` });
  save('fail-cors', corsRead);
  console.log(`   跨域腿（另一源 localhost:${PORT_X}）: cover=${corsRead.cover} readyState=${corsRead.readyState} w=${corsRead.w} stats=${JSON.stringify(corsRead.stats)}`);

  save('page-errors', { errors });
  console.log(`\n   page errors: ${JSON.stringify(errors)}`);

  await ctx.close();

  /* ────────── 判定（改后档） ────────── */
  if (!BEFORE) {
    console.log('\n══ 判定 ══');
    const d1 = wl1.deltas;
    const maxDelta = Math.max(Math.abs(d1.chevronVsLabel || 0), Math.abs(d1.spinVsLabel || 0), Math.abs(d1.iconVsLabel || 0));
    ok('① 箭头/spinner/文字 中线差 ≤1px', maxDelta <= 1, `max=${maxDelta}`);
    ok('② 工具行无入场渐显（animation:none 且 opacity:1）', wl1.styles.item.animation === 'none' && wl1.styles.item.opacity === '1', JSON.stringify(wl1.styles.item));
    ok('③ 工具名无左缘 mask / 无尾锚滚动', wl1.styles.label.maskImage === 'none' && wl1.styles.label.scrollLeft === 0, JSON.stringify(wl1.styles.label));
    ok('④ 参数右侧省略号截断', wl1.styles.detail.textOverflow === 'ellipsis', JSON.stringify(wl1.styles.detail));
    ok('⑤ 参数等宽字（--ds-font-mono）', /mono/i.test(wl1.styles.detail.fontFamily), wl1.styles.detail.fontFamily);
    ok('⑥ 工具名 x 三帧恒定（±0.5px）', labelStable, JSON.stringify(xs));
    ok('⑦ 长参数触发截断（frame3 truncated）', frames[2].truncated === true, JSON.stringify(frames.map((f) => f.truncated)));
    ok('⑧ 完成态中线差 ≤1px', Math.abs(wl3.deltas.chevronVsLabel || 0) <= 1, JSON.stringify(wl3.deltas));
    ok('⑨ 两行态箭头中线差 ≤1px', Math.abs(wlWrap.delta || 0) <= 1, String(wlWrap.delta));
    ok('⑩ Canvas 视频封面在场', canvasProbe.coverPresent, '');
    ok('⑪ Canvas 封面有内容（variance > 100）', coverPix.variance > 100, `var=${coverPix.variance}`);
    ok('⑫ Canvas 画面盒圆角 = 8px（--ds-radius-input）', canvasProbe.radius === '8px', canvasProbe.radius);
    ok('⑫b 封面层圆角同档（圆角不被封面盖成直角）', canvasProbe.coverRadius === canvasProbe.radius,
      `cover=${canvasProbe.coverRadius} video=${canvasProbe.radius}`);
    ok('⑫c 封面与画面盒逐像素同框（同 AR contain，误差 ≤1px）',
      !!canvasProbe.coverBox && canvasProbe.coverBox.w === canvasProbe.videoBox.w && canvasProbe.coverBox.h === canvasProbe.videoBox.h,
      `cover=${JSON.stringify(canvasProbe.coverBox)} video=${JSON.stringify(canvasProbe.videoBox)}`);
    ok('⑬ 对话框视频封面在场且有内容', chatRead.coverPresent && chatPix.variance > 100, `cover=${chatRead.coverPresent} var=${chatPix.variance}`);
    ok('⑭ 播放后封面让位', afterPlay.cover === false, JSON.stringify(afterPlay));
    // 两处「内嵌可播」的行为判据（作者令 2026-11-05 20:18 的封面不得把播放面变成死图）：
    // ①点下去真的不暂停 ②currentTime 真增长（走帧，不是只换了个图标）③画面已解出帧。
    ok('⑭b 对话框内嵌可播（不暂停 + 走帧 + 已解帧）',
      afterPlay.paused === false && afterPlay.t > chatBeforePlay.t && afterPlay.decoded === true,
      `before=${JSON.stringify(chatBeforePlay)} after=${JSON.stringify(afterPlay)}`);
    ok('⑲ Canvas 内嵌可播（自研播放钮 → 不暂停 + 走帧）',
      cvAfterPlay.paused === false && cvAfterPlay.t > cvBeforePlay.t,
      `before=${JSON.stringify(cvBeforePlay)} after=${JSON.stringify(cvAfterPlay)}`);
    ok('⑲b Canvas 播放后封面让位', cvAfterPlay.cover === false, JSON.stringify(cvAfterPlay));
    ok('⑮ 懒加载：首屏抓帧数 ≈ 可见数且 < 总数', lazyRead.grabsBeforeScroll > 0 && lazyRead.grabsBeforeScroll < lazyRead.total, JSON.stringify(lazyRead));
    ok('⑮b 懒加载：滚到底后才继续抓（抓帧数增长）', lazyRead.grabsAfterScroll > lazyRead.grabsBeforeScroll, JSON.stringify(lazyRead));
    ok('⑮c 抓帧不动用户播放头（currentTime 全 0）', lazyRead.playheadMax === 0, String(lazyRead.playheadMax));
    ok('⑯ 解码失败不挂封面（回现状面）', failRead.decode.cover === false && failRead.decode.failed === true, JSON.stringify(failRead.decode));
    ok('⑰ 竖屏比例不变形（frame AR = 视频 AR）', Math.abs(portraitRead.ar - portraitRead.videoAR) < 0.02, `${portraitRead.ar} vs ${portraitRead.videoAR}`);
    ok('⑱ 页面零 JS 异常', errors.length === 0, JSON.stringify(errors));
  }

  writeFileSync(join(OUT, BEFORE ? 'SUMMARY-before.json' : 'SUMMARY-after.json'),
    JSON.stringify({ mode: BEFORE ? 'before' : 'after', webRoot: WEB_DIR, results }, null, 2));
  console.log(`\n读数目录：${READINGS}`);
} finally {
  await browser.close().catch(() => {});
  await new Promise((r) => srv.close(r));
  await new Promise((r) => srvX.close(r));
}
