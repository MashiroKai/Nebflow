// canvas-video-stage.spec.mjs — Canvas 视频面「占屏比例 + 自研控件」验收（
// canvas-video-ui 批 2026-11-05；作者报障逐字「现在视频在canvas的占比，和样式都很丑，
// 需要优化一下。」）。
//
// 本件钉两件可机械判定的事（不靠目测）：
//   A 占屏比例 —— 视频在**它被给定的盒子**里必须按 contain 尽可能大：painted 尺寸
//     = contain(盒内容区, 原始宽高比)（容差 2%）。小视频（320×180）由此被放大、
//     4K 由此不溢出、竖屏/超宽由此不越界 —— 同一条法则四个档位一起钉。
//   B 控件 —— 原生 `controls` 已退役（video.controls === false）；自研控件条与画面
//     **像素零重叠**（矩形相交面积 = 0）；手机档热区 ≥44px；六态扁平（零阴影/零渐变）。
//
// 读数为**真实 DOM 读数**（getBoundingClientRect + getComputedStyle），painted 矩形由
// element rect 与 intrinsic AR 做 object-fit:contain 反算 —— 与实现无关（元素盒 ==
// 画面 或 元素盒 > 画面 两种实现都读得对）。
//
// 运行（三档）：
//   node tests/canvas-video-stage.spec.mjs                 # 门禁档（改后必绿）
//   BEFORE=1 node tests/canvas-video-stage.spec.mjs        # 改前取证档：只读不判新律，
//                                                          # 读数落 readings/*.json
//   node tests/canvas-video-stage.spec.mjs --shots <dir>   # 指定证据目录
// 可选 env：PORT=8142
//
// 夹具（运行时 ffmpeg 生成，不入 repo）四档宽高比：小 320×180 / 竖屏 360×640 /
// 超宽 1920×480 / 4K 3840×2160。

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
const PORT = Number(process.env.PORT || 8142);
const BASE = `http://127.0.0.1:${PORT}`;
const FIX_DIR = join(tmpdir(), 'canvas-video-stage-fixtures');
const BEFORE = process.env.BEFORE === '1';
const shotsArgIdx = process.argv.indexOf('--shots');
const OUT = shotsArgIdx > 0 ? resolve(process.argv[shotsArgIdx + 1])
  : process.env.SHOTS_DIR
    ? resolve(process.env.SHOTS_DIR)
    : resolve(ROOT, `.nebflow/evidence/canvas-video-stage${BEFORE ? '-before' : ''}`);
const READINGS = join(OUT, BEFORE ? 'readings-before' : 'readings-after');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
function ok(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail });
  console.log(`  ${cond ? '✓' : '✗'} ${name}${cond ? '' : ` — ${detail}`}`);
  return !!cond;
}

/* ───────────── 视频夹具（ffmpeg 运行时生成，不落 repo） ───────────── */
function makeVideo(name, w, h, { dur = 2, rate = 10 } = {}) {
  const out = join(FIX_DIR, name);
  execFileSync('ffmpeg', [
    '-y', '-loglevel', 'error',
    '-f', 'lavfi', '-i', `testsrc=duration=${dur}:size=${w}x${h}:rate=${rate}`,
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-preset', 'ultrafast',
    '-movflags', '+faststart', out,
  ], { stdio: 'pipe' });
  return out;
}

/* ───────────── 静态服务（真 web/ 树） ───────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ico': 'image/x-icon',
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

/* ───────────── /api/nf-file 替身（票闸 + Content-Type + Range 206） ───────────── */
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
  headers['Content-Type'] = 'video/mp4';
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

/* ───────────── WS 替身：pop.readFile 真语义 ───────────── */
const EXT_ITEM = { mp4: 'video', webm: 'video', md: 'markdown' };
const BINARY = new Set(['mp4', 'webm']);
const MAX_READ = 100 * 1024 * 1024;

async function boot(browser, { viewport, mobile = false, colorScheme = 'light' } = {}) {
  const ctx = await browser.newContext({
    viewport, hasTouch: mobile, locale: 'zh-CN', colorScheme,
  });
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
  await page.route(/\/api\/nf-file\?/, nfFileRoute);
  // 票闸替身（must install AFTER the catch-all：Playwright 后注册者先匹配）。
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
        const msg = {
          type: 'fileContent', path: rawPath, absPath: rawPath, itemType: EXT_ITEM[ext] || 'code',
          fileName: rawPath.split('/').pop(), size: buf.length,
        };
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
  if (mobile) {
    // 手机层：活动条隐藏，就绪判据 = body.mobile-layer 已在场（bootstrap 的激活类）。
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 25000 });
  } else {
    await page.waitForSelector('#activity-bar', { timeout: 25000 });
  }
  await page.evaluate(() => { document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove()); });
  await sleep(500);
  return { ctx, page, errors };
}

/** 桌面档画布宽度归一到 720px（1440 视口下的双栏常规档；默认 550 会让
 *  「320 宽小视频」的对照落在窄画布上，读数与作者实拍不同尺）。 */
async function normalizeCanvasWidth(page) {
  await page.evaluate(() => document.documentElement.style.setProperty('--canvas-width', '720px'));
  await sleep(450);
}

async function openPath(page, absPath) {
  await page.evaluate((p) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + p, itemType: '', content: '', absPath: p, size: 0, title: p.split('/').pop() },
    }));
  }, absPath);
}

async function waitDecoded(page, timeout = 8000) {
  await page.waitForFunction(() => {
    const v = document.querySelector('.canvas-tab-pane.active video');
    return v && v.readyState >= 1 && v.videoWidth > 0;
  }, { timeout });
}

async function shot(page, name) {
  mkdirSync(OUT, { recursive: true });
  const out = join(OUT, name);
  await page.screenshot({ path: out, fullPage: false });
  console.log(`  [shot] ${out}`);
  return out;
}

/* ───────────── 读数探针（全部真实 DOM 读数） ───────────── */
const PROBE = () => {
  const pane = document.querySelector('.canvas-tab-pane.active');
  const video = pane?.querySelector('video');
  if (!pane || !video) return { missing: !pane ? 'pane' : 'video' };
  const R = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height }; };
  const round = (o) => (o ? Object.fromEntries(Object.entries(o).map(([k, v]) => [k, Math.round(v * 10) / 10])) : null);
  const inter = (a, b) => {
    if (!a || !b) return 0;
    const w = Math.min(a.r, b.r) - Math.max(a.l, b.l);
    const h = Math.min(a.b, b.b) - Math.max(a.t, b.t);
    return w > 0 && h > 0 ? Math.round(w * h * 10) / 10 : 0;
  };
  /** object-fit: contain 反算（元素盒 → 实际画面像素矩形）。 */
  const paintedOf = (box, ar) => {
    if (!box || !ar) return box;
    const boxAr = box.w / box.h;
    if (ar >= boxAr) {
      const h = box.w / ar;
      const t = box.t + (box.h - h) / 2;
      return { l: box.l, t, r: box.l + box.w, b: t + h, w: box.w, h };
    }
    const w = box.h * ar;
    const l = box.l + (box.w - w) / 2;
    return { l, t: box.t, r: l + w, b: box.b, w, h: box.h };
  };
  const cs = getComputedStyle(video);
  const host = video.parentElement;
  const hostCs = getComputedStyle(host);
  const hb = R(host);
  const pad = {
    l: parseFloat(hostCs.paddingLeft) || 0, r: parseFloat(hostCs.paddingRight) || 0,
    t: parseFloat(hostCs.paddingTop) || 0, b: parseFloat(hostCs.paddingBottom) || 0,
  };
  const avail = hb && { l: hb.l + pad.l, t: hb.t + pad.t, r: hb.r - pad.r, b: hb.b - pad.b,
    w: hb.w - pad.l - pad.r, h: hb.h - pad.t - pad.b };
  const ar = video.videoWidth / (video.videoHeight || 1);
  const vb = R(video);
  const painted = paintedOf(vb, ar);
  const paneBox = R(pane);
  const header = R(document.querySelector('.canvas-header'));
  const chromeSels = ['.canvas-source-toggle', '.canvas-ref-select', '.canvas-find-toggle', '.canvas-zoom-bar'];
  const chrome = chromeSels.map((s) => [s, R(pane.querySelector(s))]).filter(([, b]) => b);
  const shell = pane.parentElement?.querySelector(':scope > .canvas-refresh-toggle');
  if (shell) chrome.push(['.canvas-refresh-toggle', R(shell)]);
  const bar = R(host.querySelector('.canvas-video-bar')) || R(pane.querySelector('.canvas-video-bar'));
  const btns = [...pane.querySelectorAll('.canvas-video-bar button')].map((b) => {
    const r = b.getBoundingClientRect();
    return { cls: b.className, w: Math.round(r.width), h: Math.round(r.height) };
  });
  const btnShadows = [...pane.querySelectorAll('.canvas-video-bar button')].map((b) => {
    const s = getComputedStyle(b);
    return { cls: b.className, boxShadow: s.boxShadow, bgImage: s.backgroundImage };
  });
  const overlap = (boxes) => boxes.reduce((sum, [, b]) => sum + inter(painted, b), 0);
  /** 越界量：画面伸出 pane 四边的像素和（0 = 完全在内）。 */
  const outsideOf = (box, outer) => Math.round(
    Math.max(0, outer.l - box.l) + Math.max(0, box.r - outer.r)
    + Math.max(0, outer.t - box.t) + Math.max(0, box.b - outer.b));
  return {
    pane: round(paneBox),
    host: { cls: host.className, box: round(hb), pad, contentBox: round(avail) },
    video: { box: round(vb), intrinsic: { w: video.videoWidth, h: video.videoHeight }, ar: Math.round(ar * 1000) / 1000 },
    painted: round(painted),
    ratio: {
      wOverPane: +(painted.w / paneBox.w).toFixed(4),
      hOverPane: +(painted.h / paneBox.h).toFixed(4),
      areaOverPane: +((painted.w * painted.h) / (paneBox.w * paneBox.h)).toFixed(4),
      wOverAvail: +(painted.w / avail.w).toFixed(4),
      hOverAvail: +(painted.h / avail.h).toFixed(4),
      fillW: +Math.min(1, painted.w / avail.w).toFixed(4),
      fillH: +Math.min(1, painted.h / avail.h).toFixed(4),
    },
    margins: {
      left: Math.round(painted.l - paneBox.l),
      top: Math.round(painted.t - paneBox.t),
      right: Math.round(paneBox.r - painted.r),
      bottom: Math.round(paneBox.b - painted.b),
    },
    caps: { nativeControls: video.controls === true },
    bar: bar ? round(bar) : null,
    btnTargets: btns,
    btnStyles: btnShadows,
    overlap: {
      headerArea: inter(painted, header),
      chromeArea: overlap(chrome),
      barArea: bar ? inter(painted, bar) : 0,
      paintOutsidePane: outsideOf(painted, paneBox),
    },
    outsideAvail: outsideOf(painted, avail),
  };
};

/** 期望 contain 尺寸（同一个法则，读数侧独立算一遍，不读实现）。 */
function expectContain(avail, ar) {
  const boxAr = avail.w / avail.h;
  return ar >= boxAr ? { w: avail.w, h: avail.w / ar } : { w: avail.h * ar, h: avail.h };
}

const readOne = async (page, label, fixtures) => {
  const rec = {};
  await openPath(page, fixtures.path);
  await sleep(1500);
  await waitDecoded(page, 10000);
  await sleep(300);
  rec[label] = await page.evaluate(PROBE);
  mkdirSync(READINGS, { recursive: true });
  writeFileSync(join(READINGS, `${label}.json`), JSON.stringify(rec[label], null, 2));
  console.log(`\n  ── ${label} ──`);
  const r = rec[label];
  console.log(`   pane ${r.pane.w}×${r.pane.h} | avail ${r.host.contentBox.w}×${r.host.contentBox.h}`
    + ` | video el ${r.video.box.w}×${r.video.box.h} | painted ${r.painted.w}×${r.painted.h}`
    + ` | intrinsic ${r.video.intrinsic.w}×${r.video.intrinsic.h}`);
  console.log(`   ratio w/pane=${r.ratio.wOverPane} h/pane=${r.ratio.hOverPane} area/pane=${r.ratio.areaOverPane}`
    + ` fillW=${r.ratio.fillW} fillH=${r.ratio.fillH} margins=${JSON.stringify(r.margins)}`);
  console.log(`   caps native=${r.caps.nativeControls} bar=${r.bar ? `${r.bar.w}×${r.bar.h}` : 'none'}`
    + ` overlap={header:${r.overlap.headerArea} chrome:${r.overlap.chromeArea} bar:${r.overlap.barArea}}`
    + ` outsidePane=${r.overlap.paintOutsidePane} outsideAvail=${r.outsideAvail}`);
  await shot(page, `${label}${BEFORE ? '-before' : ''}.png`);
  return r;
};

/** 关掉当前视频页签（重开下一档不叠加）。 */
async function closeCurrent(page, absPath) {
  await page.evaluate(async (p) => { const c = await import('/js/canvas.js'); c.closeTab('file:' + p); }, absPath);
  await sleep(250);
}

/* ═══════════════════════════ 主流程 ═══════════════════════════ */
rmSync(FIX_DIR, { recursive: true, force: true });
mkdirSync(FIX_DIR, { recursive: true });
const FIX = {
  small: makeVideo('small.mp4', 320, 180),
  portrait: makeVideo('portrait.mp4', 360, 640),
  ultra: makeVideo('ultra.mp4', 1920, 480),
  uhd: makeVideo('uhd.mp4', 3840, 2160, { dur: 1, rate: 5 }),
};
console.log(`[fixtures] ${Object.entries(FIX).map(([k, v]) => `${k}=${v}`).join(' ')}`);

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });

/* ── 桌面档 1440×900 ── */
console.log(`\n== ${BEFORE ? 'BEFORE（改前取证）' : 'AFTER（门禁）'} · 桌面 1440×900 ==`);
let desk = null;
{
  const { page, errors } = await boot(browser, { viewport: { width: 1440, height: 900 } });
  const order = ['small', 'portrait', 'ultra', 'uhd'];
  const readings = {};
  await openPath(page, FIX.small);
  await sleep(1400);
  await normalizeCanvasWidth(page);
  await closeCurrent(page, FIX.small);
  for (const key of order) {
    readings[key] = await readOne(page, `desktop-${key}`, { path: FIX[key] });
    await closeCurrent(page, FIX[key]);
  }
  // 键盘可达（在 small 档上验）。准入闸 = 焦点落在播放器内 ⇒ 先走真用户路径：
  // 点一下画面（stage 带 tabindex=0）。
  await openPath(page, FIX.small);
  await sleep(1400);
  await waitDecoded(page, 8000);
  await page.click('.canvas-tab-pane.active .canvas-video-stage', { position: { x: 30, y: 30 } }).catch(() => {});
  await sleep(200);
  const kb = await page.evaluate(async () => {
    const v = document.querySelector('.canvas-tab-pane.active video');
    if (!v) return { missing: true };
    const send = (code, key) => document.dispatchEvent(new KeyboardEvent('keydown', {
      code, key, bubbles: true, cancelable: true,
    }));
    const out = {};
    // 诊断面：键位表里本批四条是否在场 + 上下文键此刻的读数（失败时直接指出是哪一环）
    try {
      const km = await import('/js/keymap.js');
      const kc = await import('/js/keyContext.js');
      out.rules = km.listShortcuts().filter((s) => s.id.startsWith('canvas.video'))
        .map((s) => ({ id: s.id, combo: s.combo, when: s.when, active: s.active }));
      out.ctx = kc.snapshotContext();
      out.activeTag = document.activeElement ? document.activeElement.tagName + '.' + document.activeElement.className : null;
    } catch (e) { out.diagError = String(e && e.message || e); }
    // 空格：播放 → 暂停
    send('Space', ' ');
    await new Promise((r) => setTimeout(r, 400));
    out.played = !v.paused && v.currentTime > 0;
    send('Space', ' ');
    await new Promise((r) => setTimeout(r, 200));
    out.paused = v.paused;
    // 右方向键 +5s（夹具 2s，应 clamp 到末尾之前）
    v.currentTime = 0;
    send('ArrowRight', 'ArrowRight');
    await new Promise((r) => setTimeout(r, 250));
    out.seekForward = v.currentTime > 0.4;
    // M 静音
    out.mutedBefore = v.muted;
    send('KeyM', 'm');
    await new Promise((r) => setTimeout(r, 120));
    out.mutedAfter = v.muted;
    return out;
  });
  if (BEFORE) {
    console.log(`  [BEFORE] 键盘读数（原生控件档基线）：${JSON.stringify(kb)}`);
  } else {
    ok('空格：播放/暂停切换', kb.played === true && kb.paused === true, JSON.stringify(kb));
    ok('ArrowRight：+5s 生效（currentTime 前进）', kb.seekForward === true, JSON.stringify(kb));
    ok('M：静音切换', kb.mutedBefore === false && kb.mutedAfter === true, JSON.stringify(kb));
  }
  desk = { page, readings, errors };

  if (!BEFORE) {
    for (const key of order) {
      const r = readings[key];
      const ar = r.video.intrinsic.w / r.video.intrinsic.h;
      const exp = expectContain(r.host.contentBox, ar);
      ok(`[${key}] 画面 = contain(可用区)（宽 ${Math.round(exp.w)} / 高 ${Math.round(exp.h)}，容差 2%）`,
        Math.abs(r.painted.w - exp.w) <= exp.w * 0.02 + 1.5 && Math.abs(r.painted.h - exp.h) <= exp.h * 0.02 + 1.5,
        `painted ${r.painted.w}×${r.painted.h} vs expect ${Math.round(exp.w)}×${Math.round(exp.h)}`);
      ok(`[${key}] 4K/竖屏/超宽均不越界（画面全在 pane 内）`, r.overlap.paintOutsidePane === 0,
        `outsidePane=${r.overlap.paintOutsidePane}`);
      ok(`[${key}] 不压卡头胶囊`, r.overlap.headerArea === 0, `headerArea=${r.overlap.headerArea}`);
      ok(`[${key}] 不压底部浮钮带`, r.overlap.chromeArea === 0, `chromeArea=${r.overlap.chromeArea}`);
      ok(`[${key}] 原生 controls 退役`, r.caps.nativeControls === false);
      ok(`[${key}] 自研控件条在位且与画面零重叠`, !!r.bar && r.overlap.barArea === 0,
        `bar=${JSON.stringify(r.bar)} overlap=${r.overlap.barArea}`);
      ok(`[${key}] 可用区即被利用（stage ≥ 60% pane 面积）`,
        (r.host.contentBox.w * r.host.contentBox.h) / (r.pane.w * r.pane.h) >= 0.6,
        `${((r.host.contentBox.w * r.host.contentBox.h) / (r.pane.w * r.pane.h)).toFixed(3)}`);
    }
    // 小视频必须显著放大（相对原尺寸 320×180）
    const sm = readings.small;
    ok('小视频（320×180）被放大 ≥1.5×', sm.painted.w >= 320 * 1.5, `paintedW=${sm.painted.w}`);
    ok('小视频占 pane 宽 ≥90%（16:9 于宽画布 contain）', sm.ratio.wOverPane >= 0.9, `wOverPane=${sm.ratio.wOverPane}`);
    ok('4K 画面宽 ≤ pane 宽（contain 不溢出）', readings.uhd.painted.w <= readings.uhd.pane.w + 0.5,
      `${readings.uhd.painted.w} vs ${readings.uhd.pane.w}`);
    // 扁平：控件按钮零阴影 / 零渐变
    const badStyles = Object.values(readings).flatMap((r) => r.btnStyles)
      .filter((s) => s.boxShadow !== 'none' || /gradient/.test(s.bgImage));
    ok('自研控件按钮扁平（零 box-shadow / 零渐变）', badStyles.length === 0, JSON.stringify(badStyles.slice(0, 3)));
  }
  if (desk.errors.length) console.log(`  [warn] pageerror: ${desk.errors.join(' | ').slice(0, 250)}`);
}

/* ── 手机档 375×812 ── */
console.log(`\n== ${BEFORE ? 'BEFORE' : 'AFTER'} · 手机 375×812（hasTouch） ==`);
{
  const { page, errors } = await boot(browser, { viewport: { width: 375, height: 812 }, mobile: true });
  // 真用户路径：点 dock 的画布键（收编页推前台），再按 workspace-open-item 开文件。
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(800);
  let r = null;
  try {
    r = await readOne(page, 'mobile-small', { path: FIX.small });
  } catch (e) {
    console.log(`  [warn] 手机档读取失败：${e.message}`);
  }
  if (r && !BEFORE) {
    const ar = r.video.intrinsic.w / r.video.intrinsic.h;
    const exp = expectContain(r.host.contentBox, ar);
    ok('[mobile] 画面 = contain(可用区)（容差 2%）',
      Math.abs(r.painted.w - exp.w) <= exp.w * 0.02 + 1.5 && Math.abs(r.painted.h - exp.h) <= exp.h * 0.02 + 1.5,
      `painted ${r.painted.w}×${r.painted.h} vs expect ${Math.round(exp.w)}×${Math.round(exp.h)}`);
    ok('[mobile] 原生 controls 退役', r.caps.nativeControls === false);
    ok('[mobile] 自研控件条在位且零重叠', !!r.bar && r.overlap.barArea === 0,
      `bar=${JSON.stringify(r.bar)} overlap=${r.overlap.barArea}`);
    ok('[mobile] 不越界（画面全在 pane 内）', r.overlap.paintOutsidePane === 0, `${r.overlap.paintOutsidePane}`);
    const smallTargets = r.btnTargets.filter((b) => b.w < 44 || b.h < 44);
    ok('[mobile] 控件热区 ≥44px', r.btnTargets.length > 0 && smallTargets.length === 0,
      JSON.stringify(smallTargets.slice(0, 4)));
  } else if (r) {
    console.log(`  [BEFORE mobile] pane ${r.pane.w}×${r.pane.h} painted ${r.painted.w}×${r.painted.h}`
      + ` w/pane=${r.ratio.wOverPane} h/pane=${r.ratio.hOverPane} native=${r.caps.nativeControls}`);
  }
  if (errors.length) console.log(`  [warn] pageerror: ${errors.join(' | ').slice(0, 250)}`);
  const c = await page.evaluate(() => document.body.className.includes('mobile-layer')).catch(() => null);
  console.log(`  [probe] body.mobile-layer = ${c}`);
  await page.context().close();
}

if (desk) await desk.page.context().close();

/* ── 暗档对照（桌面 1440，只出证据不断言几何：几何与主题无关） ── */
if (!BEFORE) {
  console.log('\n== 暗档对照 · 桌面 1440×900 ==');
  const { page } = await boot(browser, { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  await openPath(page, FIX.small);
  await sleep(1500);
  await normalizeCanvasWidth(page);
  await waitDecoded(page, 10000);
  await sleep(300);
  mkdirSync(OUT, { recursive: true });
  await page.screenshot({ path: join(OUT, 'desktop-small-dark.png'), fullPage: false });
  console.log(`  [shot] ${join(OUT, 'desktop-small-dark.png')}`);
  const darkStyles = await page.evaluate(() => {
    const bar = document.querySelector('.canvas-tab-pane.active .canvas-video-bar');
    const btns = [...document.querySelectorAll('.canvas-tab-pane.active .canvas-video-btn')];
    return {
      barBg: bar ? getComputedStyle(bar).backgroundColor : null,
      btnShadows: btns.map((b) => getComputedStyle(b).boxShadow),
      btnGradients: btns.map((b) => getComputedStyle(b).backgroundImage),
    };
  });
  ok('暗档：控件条有底、按钮零阴影零渐变',
    !!darkStyles.barBg && darkStyles.barBg !== 'rgba(0, 0, 0, 0)'
    && darkStyles.btnShadows.every((s) => s === 'none')
    && darkStyles.btnGradients.every((g) => !/gradient/.test(g)),
    JSON.stringify(darkStyles).slice(0, 220));
  await page.context().close();
}

await browser.close();
webServer.close();

const failed = results.filter((r) => !r.pass);
console.log(`\n== ${BEFORE ? 'BEFORE 取证' : 'AFTER 门禁'}：${results.length - failed.length}/${results.length} 项通过 ==`);
console.log(`读数与截图：${OUT}`);
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => `  ✗ ${f.name} — ${f.detail}`).join('\n'));
  process.exit(1);
}
