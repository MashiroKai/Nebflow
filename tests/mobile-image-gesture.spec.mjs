#!/usr/bin/env node
// mobile-image-gesture.spec.mjs — 手机端图片查看纯手势批（作者令 2026-11-02 23:26）
// 「去控制栏、捏合缩放/拖动平移/双击复位，对标微信与 iOS 相册」机械验收。
//
// 双模式（同脚本两轮，改前/改后对照，imgviewer-controls-shots.mjs 惯例）：
//   BEFORE=1 node tests/mobile-image-gesture.spec.mjs   → 现状组：N1 控制栏在
//     （选择器+可见性+尺寸读数+截图）+ N11 桌面改前对照读数。
//   node tests/mobile-image-gesture.spec.mjs            → 改后组：N2..N12 全绿面。
//
// 断言面（编号对齐任务书）：
//   N1  改前现状：.canvas-zoom-bar 存在、可见、含 ≥4 钮 + % 读数；截图（BEFORE only）
//   N2  改后：pane 内 + 全文档 .canvas-zoom-bar 零命中（手机层，image pane）
//   N3  放大态双击 → 动画过渡到 fit（中间帧落在两端之间；两帧截图）
//   N4  双击 fit → 放大档 → fit 往返一致（两轮同值）
//   N5  捏合锚点：双指中点下的内容点捏合前后漂移 ≤3px；比例钳制 [fit, 4×fit]
//   N6  放大后拖出边界 → 拖动中橡皮筋阻尼 + 松手回弹到边界（不驻留界外）
//   N7  关闭入口 ≥44×44、点击生效（navController closeCard 路径）
//   N8  正文 md 面竖滚不受影响；图片面拖动零正文滚动
//   N9  捏合+平移过程 rAF 帧率采样读数（≥45fps 近似门）
//   N10 reduced-motion：双击直切（无过渡帧）
//   N11 桌面回归：控制栏在、四钮齐、双击 fit↔100%
//   N12 压力：≥20 轮捏合/平移/双击，零 pageerror、DOM 节点数不增长、变换恒有限
//
// 运行：隔离静态服务器 + workspace-open-item 直灌页签 + nf-ticket 假票 +
// /api/nf-file 字节路由（真字节 = deepseek-super-agent-xhs 黑底文字卡 PNG，
// 1242×1656，即作者实拍同款素材）。永不触碰宿主 8080/6138。
import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8231);
const BASE = `http://127.0.0.1:${PORT}`;
const BEFORE = !!process.env.BEFORE;
const OUT = process.env.SHOTS_DIR ||
  `/Users/kaiyu/Claude code/Stand/evidence/20261102_mobile-image-gesture/${BEFORE ? 'before' : 'after'}`;
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });

// ── 真实测试素材：Deepseek Super Agent 黑底文字卡（作者实拍同款）──────────
const IMG_CANDIDATES = [
  '/Users/kaiyu/.stand/projects/视觉工作室/assets/cards/deepseek-super-agent-xhs/output-textcard/dsa-tc-01.png',
];
const IMG_DISK = '/tmp/stand-mobile-gesture/dsa-tc-01.png';
const MD_DISK = '/tmp/stand-mobile-gesture/report.md';
const MD_BODY = '# 手机端滚动共存探针\n\n' +
  '这是一段用于验证正文滚动的说明文字。'.repeat(3) + '\n\n' +
  Array.from({ length: 16 }, (_, i) => `## 段 ${i + 1}\n\n正文段落 ${i + 1}：滚动共存验证用长文，确保页面高度超过一屏，产生可滚动余量。`.repeat(2)).join('\n\n');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let imgBytes = null;
for (const c of IMG_CANDIDATES) {
  if (existsSync(c)) { imgBytes = readFileSync(c); break; }
}
if (!imgBytes) {
  console.error('FATAL: 测试素材不存在（deepseek-super-agent-xhs output-textcard）');
  process.exit(2);
}
mkdirSync('/tmp/stand-mobile-gesture', { recursive: true });
writeFileSync(IMG_DISK, imgBytes);
writeFileSync(MD_DISK, MD_BODY);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

let pass = 0, fail = 0;
const rows = [];
function ok(name, cond, detail = '') {
  rows.push(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' : ''}${detail}`);
  if (cond) pass++; else fail++;
}

function wsMocks(ctx) {
  return ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
}

/** 手机层 context（390×844 DPR3 hasTouch = iPhone 14/15 级）。 */
async function mobileCtx(opts = {}) {
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 3,
    hasTouch: true,
    colorScheme: 'dark',
    ...opts,
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  wsMocks(ctx);
  const page = await ctx.newPage();
  // 按路径分派：图片卡 → PNG 字节；md 卡 → 文本字节（catch-all png 会毒化 md 腿）。
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const path = decodeURIComponent((url.searchParams.get('path') || '').replace(/\+/g, ' '));
    if (path.endsWith('.png')) return route.fulfill({ status: 200, contentType: 'image/png', body: imgBytes });
    if (path.endsWith('.md')) return route.fulfill({ status: 200, contentType: 'text/markdown; charset=utf-8', body: readFileSync(MD_DISK) });
    return route.fulfill({ status: 404, json: {} });
  });
  return { ctx, page };
}

/** 桌面 context（1440×900 无触 = 回归腿）。 */
async function desktopCtx() {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  wsMocks(ctx);
  const page = await ctx.newPage();
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    return route.fulfill({ status: 200, contentType: 'image/png', body: imgBytes });
  });
  return { ctx, page };
}

async function openImage(page) {
  await page.evaluate(({ absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'image', title: 'dsa-tc-01.png', absPath, pinned: true },
    }));
  }, { absPath: IMG_DISK });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${IMG_DISK}"]`, { timeout: 15000, state: 'attached' });
  await page.waitForSelector('.canvas-image-viewport img', { timeout: 10000 });
  await sleep(600);
}

async function openMd(page) {
  // 正文直灌 detail.content（canonical 腿：canvas-chrome-size-align / chrome 族
  // 既有 spec 同一条路径），不依赖 /api/nf-file 的 md 字节腿。
  await page.evaluate(({ absPath, content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title: 'report.md', absPath, content, pinned: true },
    }));
  }, { absPath: MD_DISK, content: MD_BODY });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${MD_DISK}"]`, { timeout: 15000, state: 'attached' });
  await sleep(700);
}

/** 切卡：手机层环形标签条（.mf-strip-chip）优先，回落桌面 .canvas-tab。 */
async function activate(page, idSubstr) {
  const clicked = await page.evaluate((frag) => {
    const chip = [...document.querySelectorAll('.mf-strip-chip')].find((c) => (c.dataset.tabId || '').includes(frag));
    if (chip) { chip.click(); return 'chip'; }
    const tab = [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')].find((t) => (t.dataset.tabId || '').includes(frag));
    if (tab) { tab.click(); return 'tab'; }
    return '';
  }, idSubstr);
  await sleep(900);
  return clicked;
}

/** 一帧读图片变换与几何。 */
const READ = `(() => {
  const pane = document.querySelector('.canvas-tab-pane[data-tab-id="file:${IMG_DISK}"]');
  if (!pane) return { missing: true };
  const img = pane.querySelector('.canvas-image-viewport img');
  const vp = pane.querySelector('.canvas-image-viewport');
  if (!img || !vp) return { missing: true };
  const t = getComputedStyle(img).transform;
  const m = new DOMMatrixReadOnly(t === 'none' ? '' : t);
  const vr = vp.getBoundingClientRect();
  const bar = pane.querySelector('.canvas-zoom-bar');
  const barR = bar ? bar.getBoundingClientRect() : null;
  return {
    s: m.a, tx: m.e, ty: m.f,
    vw: vr.width, vh: vr.height,
    iw: img.naturalWidth, ih: img.naturalHeight,
    barInPane: !!bar,
    barVisible: !!bar && barR.width > 0 && barR.height > 0 && getComputedStyle(bar).visibility !== 'hidden',
    barRect: barR ? { x: Math.round(barR.x), y: Math.round(barR.y), w: Math.round(barR.width), h: Math.round(barR.height) } : null,
    barDoc: document.querySelectorAll('.canvas-zoom-bar').length,
    btns: bar ? [...bar.querySelectorAll('button')].map(b => b.getAttribute('aria-label') || b.title) : [],
    pct: bar ? ((bar.querySelector('.canvas-zoom-pct') || {}).textContent || '') : '',
    statusLabel: (() => { const el = pane.querySelector('[role="img"]'); return el ? el.getAttribute('aria-label') : null; })(),
    live: (() => { const el = pane.querySelector('[aria-live]'); return el ? el.textContent : null; })(),
  };
})()`;

/** CDP 双指捏合：中心 (cx,cy)，指距 dist0 → dist1（屏幕 px）。 */
async function pinch(page, cx, cy, dist0, dist1, steps = 12) {
  const cdp = await page.context().newCDPSession(page);
  const at = (d) => [{ x: cx - d / 2, y: cy, id: 1 }, { x: cx + d / 2, y: cy, id: 2 }];
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: at(dist0) });
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: at(dist0 + (dist1 - dist0) * (i / steps)) });
    await sleep(12);
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach().catch(() => {});
}

/** CDP 单指拖动（from → to）。 */
async function dragTouch(page, x0, y0, x1, y1, steps = 10, release = true) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x0, y: y0, id: 1 }] });
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: x0 + (x1 - x0) * (i / steps), y: y0 + (y1 - y0) * (i / steps), id: 1 }],
    });
    await sleep(12);
  }
  if (release) await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach().catch(() => {});
}

/** 双击（两次 tap，间隔 120ms）。 */
async function doubleTap(page, x, y) {
  await page.touchscreen.tap(x, y);
  await sleep(120);
  await page.touchscreen.tap(x, y);
}

const round2 = (n) => Math.round(n * 100) / 100;

/* ══════════════════ 手机腿 ══════════════════ */
let fitS = 0;
{
  const { ctx, page } = await mobileCtx();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click().catch(() => {});
  await sleep(400);
  await openImage(page);
  await sleep(400);
  const r0 = await page.evaluate(READ);
  if (r0.missing) { console.error('FATAL: 图片 pane 未渲染'); await ctx.close(); await browser.close(); server.close(); process.exit(2); }

  fitS = round2(Math.min(r0.vw / r0.iw, r0.vh / r0.ih, 1));

  if (BEFORE) {
    /* ── N1 改前现状：控制栏存在 + 读数 + 截图 ── */
    ok('N1a 前置 图片卡真渲染（img loaded）', r0.iw > 0, `iw=${r0.iw} fit≈${fitS}`);
    ok('N1b 改前现状：pane 内 .canvas-zoom-bar 存在', r0.barInPane, JSON.stringify(r0.barRect));
    ok('N1c 控制栏可见且横条形态（h>0，宽 > 120）', r0.barVisible && !!r0.barRect && r0.barRect.w > 120,
      r0.barRect ? `w=${r0.barRect.w} h=${r0.barRect.h}` : '');
    ok('N1d 条内读数 = % 形态', /%\s*$/.test(String(r0.pct).trim()), `pct="${String(r0.pct).trim()}"`);
    ok('N1e 条内按钮 ≥4（含抓手/−/+/适应视图）', r0.btns.length >= 4, r0.btns.join(' · '));
    await page.screenshot({ path: join(SHOTS, 'before-bar-present.png') });
    const paneEl = await page.$(`.canvas-tab-pane[data-tab-id="file:${IMG_DISK}"]`);
    const pb = paneEl ? await paneEl.boundingBox() : null;
    if (pb) await page.screenshot({ path: join(SHOTS, 'before-pane.png'), clip: pb });
    console.log(`\n[N1 现状读数] barRect=${JSON.stringify(r0.barRect)} pct="${String(r0.pct).trim()}" btns=${r0.btns.length} [${r0.btns.join(' · ')}]`);
  } else {
    /* ── N2 控制栏零命中 ── */
    ok('N2a 手机层图片 pane 零 .canvas-zoom-bar（DOM 零命中）', !r0.barInPane, `barDoc=${r0.barDoc}`);
    ok('N2b 全文档零 .canvas-zoom-bar（image 场景）', r0.barDoc === 0, `count=${r0.barDoc}`);
    await page.screenshot({ path: join(SHOTS, 'after-bar-gone.png') });

    const vpBox = await (await page.$('.canvas-image-viewport')).boundingBox();
    const cx = vpBox.x + vpBox.width / 2, cy = vpBox.y + vpBox.height / 2;
    const zoomTarget = Math.min(fitS * 2.5, fitS * 4);

    /* ── N4 双击两档往返 ── */
    await doubleTap(page, cx, cy);
    await sleep(420);
    const r1 = await page.evaluate(READ);
    ok('N4a 双击 1x → 放大档（≈2.5×fit ±5%）', Math.abs(r1.s - zoomTarget) <= zoomTarget * 0.05,
      `s=${round2(r1.s)} expect≈${round2(zoomTarget)} fit=${fitS}`);
    ok('N4a2 缩放状态对读屏可感知（role=img aria-label 含百分比）',
      !!r1.statusLabel && /%|倍/.test(r1.statusLabel), `aria="${r1.statusLabel}"`);
    await doubleTap(page, cx, cy);
    await sleep(420);
    const r2 = await page.evaluate(READ);
    ok('N4b 再双击 → 回 fit（±1%）', Math.abs(r2.s - fitS) <= fitS * 0.01 + 1e-4, `s=${round2(r2.s)} fit=${fitS}`);
    await doubleTap(page, cx, cy);
    await sleep(420);
    const r3 = await page.evaluate(READ);
    ok('N4c 第二轮双击放大 = 同一档（往返一致）', Math.abs(r3.s - zoomTarget) <= zoomTarget * 0.05, `s=${round2(r3.s)}`);
    await doubleTap(page, cx, cy);
    await sleep(420);
    const r4 = await page.evaluate(READ);
    ok('N4d 第二轮双击复位 = fit（往返一致）', Math.abs(r4.s - fitS) <= fitS * 0.01 + 1e-4, `s=${round2(r4.s)}`);

    /* ── N3 放大态双击 → 动画过渡到 fit ── */
    await doubleTap(page, cx, cy);
    await sleep(420);
    const sBefore = (await page.evaluate(READ)).s;
    await doubleTap(page, cx, cy);
    await sleep(70);
    const mid = await page.evaluate(READ);
    await page.screenshot({ path: join(SHOTS, 'n3-mid-transition.png') });
    await sleep(600);
    const end = await page.evaluate(READ);
    await page.screenshot({ path: join(SHOTS, 'n3-end-fit.png') });
    const span = sBefore - end.s;
    const animating = mid.s > end.s + span * 0.15 && mid.s < sBefore - span * 0.15;
    ok('N3a 放大态双击 → 终值 = fit（不是 100% 像素档）', Math.abs(end.s - fitS) <= fitS * 0.01 + 1e-4,
      `end=${round2(end.s)} fit=${fitS} (from=${round2(sBefore)})`);
    ok('N3b 动画不是瞬跳（70ms 中间帧落在两端之间）', animating, `mid=${round2(mid.s)} ∈ (${round2(end.s)}, ${round2(sBefore)})`);

    /* ── N5 捏合锚点 + 钳制 ── */
    const ax = vpBox.x + vpBox.width * 0.3, ay = vpBox.y + vpBox.height * 0.4;
    const b = await page.evaluate(`(() => {
      const pane = document.querySelector('.canvas-tab-pane[data-tab-id="file:${IMG_DISK}"]');
      const m = new DOMMatrixReadOnly(getComputedStyle(pane.querySelector('.canvas-image-viewport img')).transform);
      const vp = pane.querySelector('.canvas-image-viewport').getBoundingClientRect();
      const px = ${ax} - vp.x, py = ${ay} - vp.y;
      return { content: { x: (px - m.e) / m.a, y: (py - m.f) / m.d }, s: m.a };
    })()`);
    await pinch(page, ax, ay, 90, 210, 12);
    await sleep(150);
    const a = await page.evaluate(`(() => {
      const pane = document.querySelector('.canvas-tab-pane[data-tab-id="file:${IMG_DISK}"]');
      const m = new DOMMatrixReadOnly(getComputedStyle(pane.querySelector('.canvas-image-viewport img')).transform);
      const vp = pane.querySelector('.canvas-image-viewport').getBoundingClientRect();
      const cx2 = ${ax} - vp.x, cy2 = ${ay} - vp.y;
      const proj = { x: m.a * ${b.content.x} + m.e, y: m.d * ${b.content.y} + m.f };
      return { s: m.a, drift: Math.hypot(proj.x - cx2, proj.y - cy2) };
    })()`);
    ok('N5a 捏合缩放生效（比例上升 ≥1.5×）', a.s > b.s * 1.5, `${round2(b.s)} → ${round2(a.s)}`);
    ok('N5b 锚点正确性：双指中点下的内容点漂移 ≤3px', a.drift <= 3, `drift=${round2(a.drift)}px`);
    await pinch(page, ax, ay, 210, 720, 10);
    await sleep(150);
    const clamped = await page.evaluate(READ);
    const maxS = fitS * 4;
    ok('N5c 缩放钳制上限（≤4×fit，+2% 容差）', clamped.s <= maxS * 1.02, `s=${round2(clamped.s)} max=${round2(maxS)}`);
    await pinch(page, ax, ay, 210, 45, 10);
    await sleep(400);
    const down = await page.evaluate(READ);
    ok('N5d 缩放钳制下限（≥fit，−2% 容差）', down.s >= fitS * 0.98, `s=${round2(down.s)} fit=${fitS}`);

    /* ── N6 平移钳制与回弹 ── */
    await doubleTap(page, cx, cy);
    await sleep(420);
    const bx = await page.evaluate(READ);
    const zS = bx.s;
    const iwPx = r0.iw * zS;
    const dragDx = -(iwPx * 0.5 + 140);
    const cdp = await page.context().newCDPSession(page);
    let midR = null;
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: cx, y: cy, id: 1 }] });
    for (let i = 1; i <= 10; i++) {
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: cx + dragDx * (i / 10), y: cy, id: 1 }] });
      await sleep(12);
      if (i === 8) midR = await page.evaluate(READ);
    }
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await cdp.detach().catch(() => {});
    await sleep(450);
    const settled = await page.evaluate(READ);
    const bound = Math.min(0, r0.vw - r0.iw * settled.s);
    ok('N6a 拖动中越界有阻尼（越界位移被衰减，未达原始拖距）',
      !!midR && midR.tx < bound && midR.tx > bound + dragDx * 0.75,
      midR ? `mid tx=${round2(midR.tx)} bound=${round2(bound)} rawEnd=${round2(bound + dragDx)}` : 'no sample');
    ok('N6b 松手回弹到边界（不驻留界外，±2px）', Math.abs(settled.tx - bound) <= 2, `tx=${round2(settled.tx)} bound=${round2(bound)}`);
    ok('N6c 回弹只动平移不改缩放', Math.abs(settled.s - zS) <= zS * 0.01, `s=${round2(settled.s)} from=${round2(zS)}`);
    await page.screenshot({ path: join(SHOTS, 'n6-bounce.png') });

    /* ── N7 关闭入口 ── */
    const closeInfo = await page.evaluate(`(() => {
      const b = document.querySelector('.mf-cv-close');
      if (!b) return null;
      const r = b.getBoundingClientRect();
      return { w: r.width, h: r.height, label: b.getAttribute('aria-label') };
    })()`);
    ok('N7a 关闭入口在场（.mf-cv-close，非手势等价入口）', !!closeInfo, closeInfo ? `aria="${closeInfo.label}"` : 'missing');
    ok('N7b 关闭热区 ≥44×44', !!closeInfo && closeInfo.w >= 44 && closeInfo.h >= 44,
      closeInfo ? `${Math.round(closeInfo.w)}×${Math.round(closeInfo.h)}` : '');
    await page.locator('.mf-cv-close').click();
    await sleep(700);
    const closed = await page.evaluate(`(() => ({
      paneGone: !document.querySelector('.canvas-tab-pane[data-tab-id="file:${IMG_DISK}"]'),
      empty: !!document.querySelector('.mf-cv-empty'),
    }))()`);
    ok('N7c 点击关闭生效（pane 出画 / 空态在）', closed.paneGone && closed.empty, JSON.stringify(closed));

    /* ── N8 与正文滚动共存 ── */
    await openImage(page);
    await sleep(500);
    await openMd(page);
    await sleep(800);
    await activate(page, 'report.md');
    await sleep(600);
    const SC = '.canvas-tab-pane.active .canvas-md-scroll';
    const readSc = `(() => { const sc = document.querySelector(${JSON.stringify(SC)});
      return sc ? { top: sc.scrollTop, sh: sc.scrollHeight, ch: sc.clientHeight } : null; })()`;
    const sBefore8 = await page.evaluate(readSc);
    await dragTouch(page, 195, 600, 195, 240, 10);
    await sleep(700);
    const sAfter8 = await page.evaluate(readSc);
    ok('N8a 正文 md 面竖向滑动照常滚动（不触发图片缩放）',
      !!sBefore8 && !!sAfter8 && sBefore8.sh > sBefore8.ch && sAfter8.top > sBefore8.top + 40,
      sBefore8 ? `scrollTop ${sBefore8.top} → ${sAfter8.top}（sh=${sBefore8.sh} ch=${sBefore8.ch}）` : 'no scroller');
    await activate(page, 'dsa-tc-01');
    await sleep(600);
    const mdScroll = `(() => {
      const mdPane = [...document.querySelectorAll('.canvas-tab-pane')].find(p => p.dataset.tabId && p.dataset.tabId.includes('report.md'));
      const sc = mdPane && mdPane.querySelector('.canvas-md-scroll');
      return sc ? sc.scrollTop : -1;
    })()`;
    const scA = await page.evaluate(mdScroll);
    await dragTouch(page, 195, 520, 195, 240, 8);
    await sleep(450);
    const scB = await page.evaluate(mdScroll);
    ok('N8b 图片查看态拖动不滚正文（md scrollTop 不变）', scA === scB && scA >= 0, `${scA} → ${scB}`);

    /* ── N9 rAF 帧率采样（捏合 + 平移窗口）── */
    await page.evaluate(`(() => {
      window.__rafFrames = 0; window.__rafT0 = performance.now();
      const loop = () => { window.__rafFrames++; requestAnimationFrame(loop); };
      requestAnimationFrame(loop);
    })()`);
    await pinch(page, cx, cy, 100, 280, 14);
    await dragTouch(page, cx, cy, cx - 90, cy - 50, 8);
    await sleep(150);
    const fps = await page.evaluate(`(() => {
      const dt = performance.now() - window.__rafT0;
      return { frames: window.__rafFrames, ms: dt, fps: Math.round(window.__rafFrames / dt * 1000) };
    })()`);
    ok('N9 手势过程帧率 ≥45fps（rAF 近似采样）', fps.fps >= 45, `frames=${fps.frames} ms=${Math.round(fps.ms)} fps=${fps.fps}`);

    /* ── N12 压力 ── */
    const nodeBefore = await page.evaluate('document.getElementsByTagName("*").length');
    for (let i = 0; i < 20; i++) {
      await doubleTap(page, cx, cy);
      await sleep(140);
      if (i % 4 === 0) await pinch(page, cx, cy, 120, 230, 6);
      if (i % 4 === 1) await dragTouch(page, cx, cy, cx - 60, cy - 30, 6);
      await sleep(60);
    }
    await sleep(700);
    const st = await page.evaluate(READ);
    const nodeAfter = await page.evaluate('document.getElementsByTagName("*").length');
    ok('N12a 压力 20 轮后零 pageerror', errors.length === 0, errors.slice(0, 3).join(' | '));
    ok('N12b DOM 节点数不增长（±20 内）', Math.abs(nodeAfter - nodeBefore) <= 20, `${nodeBefore} → ${nodeAfter}`);
    ok('N12c 变换恒有限（无 NaN 卡死）', Number.isFinite(st.s) && Number.isFinite(st.tx) && Number.isFinite(st.ty),
      `s=${st.s} tx=${st.tx} ty=${st.ty}`);
    ok('N12d 压力后仍停在合法档（fit 或放大档）',
      Math.abs(st.s - fitS) <= fitS * 0.02 || Math.abs(st.s - Math.min(fitS * 2.5, fitS * 4)) <= fitS * 2.5 * 0.06,
      `s=${round2(st.s)}`);
    await page.screenshot({ path: join(SHOTS, 'n12-after-stress.png') });
  }

  await ctx.close();
}

/* ── N10 reduced-motion（独立 context）── */
if (!BEFORE) {
  const rm = await mobileCtx({ reducedMotion: 'reduce' });
  const p2 = rm.page;
  const errs2 = [];
  p2.on('pageerror', (e) => errs2.push(String(e.message || e).slice(0, 120)));
  await p2.goto(BASE + '/index.html');
  await p2.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await p2.locator('#mobile-dock .dock-key[data-dock="canvas"]').click().catch(() => {});
  await sleep(400);
  await openImage(p2);
  await sleep(500);
  const fit2 = await p2.evaluate(READ);
  const fit2S = round2(Math.min(fit2.vw / fit2.iw, fit2.vh / fit2.ih, 1));
  const box2 = await (await p2.$('.canvas-image-viewport')).boundingBox();
  const cx2 = box2.x + box2.width / 2, cy2 = box2.y + box2.height / 2;
  await p2.touchscreen.tap(cx2, cy2);
  await sleep(100);
  await p2.touchscreen.tap(cx2, cy2);
  await sleep(45);                      // 45ms 采一帧：有动画时此处仍在过渡中
  const fast = await p2.evaluate(READ);
  const target2 = Math.min(fit2S * 2.5, fit2S * 4);
  ok('N10a reduced-motion：双击直切（45ms 已到放大档，无过渡）',
    Math.abs(fast.s - target2) <= target2 * 0.03, `s=${round2(fast.s)} target=${round2(target2)}`);
  await p2.screenshot({ path: join(SHOTS, 'n10-reduced-motion.png') });
  ok('N10b reduced-motion 零 JS 错误', errs2.length === 0, errs2.slice(0, 2).join(' | '));
  await rm.ctx.close();
}

/* ══════════════════ 桌面腿（N11）══════════════════ */
{
  const { ctx, page } = await desktopCtx();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(500);
  await openImage(page);
  await sleep(500);
  const d = await page.evaluate(READ);
  const dFit = round2(Math.min(d.vw / d.iw, d.vh / d.ih, 1));
  ok('N11a 桌面：控制栏在场（改前改后一致）', d.barInPane && d.barVisible, d.barRect ? JSON.stringify(d.barRect) : '');
  ok('N11b 桌面：四钮齐（抓手/−/+/适应视图）+ % 读数', d.btns.length === 4 && /%\s*$/.test(String(d.pct).trim()),
    `${d.btns.join(' · ')} "${String(d.pct).trim()}"`);
  const dBox = await (await page.$('.canvas-image-viewport')).boundingBox();
  const dcx = dBox.x + dBox.width / 2, dcy = dBox.y + dBox.height / 2;
  await page.mouse.dblclick(dcx, dcy);
  await sleep(350);
  const d2 = await page.evaluate(READ);
  ok('N11c 桌面：双击 → 100%（scale=1 ±1%）', Math.abs(d2.s - 1) <= 0.01, `s=${round2(d2.s)}`);
  await page.mouse.dblclick(dcx, dcy);
  await sleep(350);
  const d3 = await page.evaluate(READ);
  ok('N11d 桌面：再双击 → 回 fit', Math.abs(d3.s - dFit) <= dFit * 0.01 + 1e-4, `s=${round2(d3.s)} fit=${dFit}`);
  ok('N11e 桌面：零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));
  await page.screenshot({ path: join(SHOTS, 'desktop-regression.png') });
  await ctx.close();
}

await browser.close();
server.close();

const header = BEFORE
  ? '\n═══ mobile-image-gesture [BEFORE 现状组] ═══'
  : '\n═══ mobile-image-gesture [AFTER 改后组] ═══';
console.log(header);
console.log(`${pass} PASS / ${fail} FAIL`);
writeFileSync(join(OUT, `result-${BEFORE ? 'before' : 'after'}.txt`), rows.join('\n') + header + `\n${pass} PASS / ${fail} FAIL\n`);
process.exit(fail ? 1 : 0);
