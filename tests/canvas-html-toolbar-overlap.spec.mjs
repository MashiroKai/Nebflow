// canvas-html-toolbar-overlap.spec.mjs — Canvas HTML 预览底部工具条重叠回归
// （2026-10-19 作者截图：HTML 打开后底部浮动工具条按钮叠印——缩小/100%/放大
//   与第三个按钮（放大镜形态 = find toggle）叠在一起，右侧准星/代码视图正常。）
//
// 根因：HTML pane 底右 chrome 簇全部 absolute 右锚定——source 16 · ref-select 64 ·
//   find 112（html.js addFindToggle(pane, 112)，wiki-graph 批新设的槽位），
//   而 .canvas-tab-pane[data-type="html"] .canvas-zoom-bar 仍停在 right:112
//   （该值定版时簇里只有 16/64 两个圆钮）→ 缩放条右端（Fit 钮）压在 find 圆钮上。
// 修复：zoom bar 移到下一个 48px 槽位 right:160（split.css）。
//
// 断言面（几何实测 boundingBox，不Mock 布局）：
//   O1 打开态：四件 chrome 元素齐（source/ref/find/zoom-bar）；
//   O2 正常宽度：四元素两两矩形零交叠；zoom bar 右缘与 find 圆钮右缘间距 ≥8px
//      （48px 槽距节奏：40px 圆 + 8px 缝）；
//   O3 命中面：Fit 钮中心 elementFromPoint 命中 zoom bar 内部（修复前被 z-index
//      10 的 find 圆钮盖住）；find 圆钮中心命中自身；
//   O4 窄面板（--canvas-width:320px = canvas.js MIN_CANVAS_WIDTH，拖拽下限）：
//      同 O2/O3 零复发，zoom bar 完整落在 pane 内；
//   O5 窄窗口 overlay 形态（viewport 860 → body.canvas-overlay，抽屉浮起）：
//      同 O2/O3 零复发；
//   R0 主文档无 JS 运行时错误。
//
// 运行（仓库根 node_modules 提供 playwright）：
//   node tests/canvas-html-toolbar-overlap.spec.mjs
// 可选 env：
//   PORT=8123                            静态服务端口（≥8100，非 8080）
//   CANVAS_TOOLBAR_SHOTS_DIR=<dir>       截图输出目录

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const FIXTURE = resolve(HERE, 'fixtures/canvas-html-zoom/poster.html');
const PORT = Number(process.env.PORT || 8127);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.CANVAS_TOOLBAR_SHOTS_DIR ||
  resolve(HERE, '../evidence/20261019_161500_canvas-toolbar-overlap__delegate-nebflow-540b9857/shots');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（serve 真实 web/ 前端源码树；非 8080 端口）──
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

function nfFileRoute() {
  const ALLOWED = ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'ico', 'mp4', 'webm', 'mp3', 'wav', 'woff', 'woff2', 'ttf', 'otf', 'pdf', 'docx', 'xlsx', 'pptx', 'epub', 'js', 'mjs', 'css', 'json'];
  return async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    const ext = (p.split('.').pop() || '').toLowerCase();
    if (!ALLOWED.includes(ext)) return route.fulfill({ status: 400, contentType: 'text/plain', body: 'File type not allowed' });
    try {
      return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: readFileSync(p) });
    } catch {
      return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
    }
  };
}

async function bootPage(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, nfFileRoute());
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(400);
  const content = readFileSync(FIXTURE, 'utf8');
  const absPath = FIXTURE;
  await page.evaluate(({ content, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'html', title: 'poster.html', content, absPath, pinned: false },
    }));
  }, { content, absPath });
  await page.waitForSelector('iframe[data-nf-canvas-html]', { timeout: 10000 });
  await sleep(400);
  return { ctx, page, errors };
}

// 底右 chrome 簇四元素 rects（viewport 坐标）
async function chromeRects(page) {
  return page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const pick = (sel) => {
      const el = pane.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: r.x, y: r.y, width: r.width, height: r.height };
    };
    return {
      source: pick('.canvas-source-toggle'),
      refsel: pick('.canvas-ref-select'),
      find: pick('.canvas-find-toggle'),
      bar: pick('.canvas-zoom-bar'),
    };
  });
}

function overlapArea(a, b) {
  const w = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
  const h = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
  return Math.max(0, w) * Math.max(0, h);
}

// 命中面：viewport 点 → 命中元素最近的 chrome 祖先类名
async function hitAt(page, x, y) {
  return page.evaluate(([px, py]) => {
    const el = document.elementFromPoint(px, py);
    if (!el) return 'none';
    if (el.closest('.canvas-zoom-bar')) return 'zoom-bar';
    if (el.closest('.canvas-find-toggle')) return 'find';
    if (el.closest('.canvas-ref-select')) return 'refsel';
    if (el.closest('.canvas-source-toggle')) return 'source';
    return el.className?.toString().slice(0, 40) || el.tagName;
  }, [x, y]);
}

let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  if (cond) { pass++; results.push(`PASS  ${name}${detail ? '  — ' + detail : ''}`); }
  else { fail++; results.push(`FAIL  ${name}${detail ? '  — ' + detail : ''}`); }
}

async function assertCluster(page, tag, { shot = null, expectOverlay = null } = {}) {
  const rects = await chromeRects(page);
  const names = ['source', 'refsel', 'find', 'bar'];
  ok(`${tag} O-chrome 四件 chrome 元素齐`, names.every((n) => rects[n] && rects[n].width > 0),
    names.map((n) => `${n}=${rects[n] ? Math.round(rects[n].width) + 'x' + Math.round(rects[n].height) : 'MISSING'}`).join(' '));
  let pairs = [];
  for (let i = 0; i < names.length; i++) {
    for (let j = i + 1; j < names.length; j++) {
      const a = overlapArea(rects[names[i]], rects[names[j]]);
      if (a > 0) pairs.push(`${names[i]}∩${names[j]}=${Math.round(a)}px²`);
    }
  }
  ok(`${tag} O-nolap 两两矩形零交叠`, pairs.length === 0, pairs.join(' ') || 'clean');
  // 移位批（2026-10-22）：底右圆钮簇整体 +48（回退批同日 deck fab 迁回顶右
  // 原位、簇槽位维持不动）后，窄面板（<356px）的 zoom bar 走圆钮行上方第二行
  // （split.css @container nfpane）——间距钉改为行感知：同行（矩形纵向相交）
  // 要求水平缝 ≥8px；异行要求垂直缝 ≥8px。
  const vOverlap = Math.min(rects.find.y + rects.find.height, rects.bar.y + rects.bar.height) -
    Math.max(rects.find.y, rects.bar.y);
  let gap;
  if (vOverlap > 0) gap = rects.find.x - (rects.bar.x + rects.bar.width);
  else gap = rects.find.y - (rects.bar.y + rects.bar.height);
  ok(`${tag} O-gap zoom bar → find 圆钮缝 ≥8px（同行水平/异行垂直，48px 槽距节奏）`,
    gap >= 7.5, `gap=${gap.toFixed(1)}px row=${vOverlap > 0 ? 'same' : 'stacked'}`);
  // pane 内完整可见
  const pane = await page.evaluate(() => {
    const r = document.querySelector('.canvas-tab-pane.active').getBoundingClientRect();
    return { x: r.x, y: r.y, width: r.width, height: r.height };
  });
  const bar = rects.bar;
  const inside = bar.x >= pane.x && bar.y >= pane.y &&
    bar.x + bar.width <= pane.x + pane.width + 0.5 && bar.y + bar.height <= pane.y + pane.height + 0.5;
  ok(`${tag} O-inside zoom bar 完整落在 pane 内`, inside,
    `bar=[${Math.round(bar.x)},${Math.round(bar.y)},${Math.round(bar.width)}x${Math.round(bar.height)}] pane=${Math.round(pane.width)}x${Math.round(pane.height)}`);
  // 命中面：Fit 钮中心（bar 最右按钮 = 修复前被 find 圆钮盖住的 spot）
  const fit = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const btn = pane.querySelector('.canvas-zoom-btn[title="Fit to view"]');
    const r = btn.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  const hitFit = await hitAt(page, fit.x, fit.y);
  ok(`${tag} O-hit Fit 钮中心命中 zoom bar`, hitFit === 'zoom-bar', `hit=${hitFit}`);
  const fnd = await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const r = pane.querySelector('.canvas-find-toggle').getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  });
  const hitFind = await hitAt(page, fnd.x, fnd.y);
  ok(`${tag} O-hit find 圆钮中心命中自身`, hitFind === 'find', `hit=${hitFind}`);
  if (expectOverlay !== null) {
    const overlay = await page.evaluate(() => document.body.classList.contains('canvas-overlay'));
    ok(`${tag} O-mode overlay 形态=${expectOverlay}`, overlay === expectOverlay, `overlay=${overlay}`);
  }
  if (shot) {
    await page.screenshot({
      path: join(SHOTS_DIR, shot),
      clip: { x: Math.max(0, pane.x + pane.width - 480), y: Math.max(0, pane.y + pane.height - 150), width: 480, height: 150 },
    });
  }
  return rects;
}

mkdirSync(SHOTS_DIR, { recursive: true });
const browser = await chromium.launch();

/* ════════ 正常宽度（1600，面板 45vw 默认）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await assertCluster(page, 'T-normal', { shot: '20261019_toolbar-overlap-normal.png' });
  ok('R0 主文档无 JS 运行时错误（normal）', errors.length === 0, errors.slice(0, 3).join(' | '));
  await ctx.close();
}

/* ════════ 窄面板（--canvas-width:320px = MIN_CANVAS_WIDTH 拖拽下限）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await page.evaluate(() => document.documentElement.style.setProperty('--canvas-width', '320px'));
  await sleep(700); // flex-basis transition
  await assertCluster(page, 'T-narrow320', { shot: '20261019_toolbar-overlap-narrow320.png' });
  ok('R0 主文档无 JS 运行时错误（narrow320）', errors.length === 0, errors.slice(0, 3).join(' | '));
  await ctx.close();
}

/* ════════ 窄窗口 overlay 形态（viewport 860 → body.canvas-overlay 抽屉）════════ */
{
  const { ctx, page, errors } = await bootPage(browser);
  await page.setViewportSize({ width: 860, height: 700 });
  await sleep(700); // resize 监听 → overlay 模式 + fixed 抽屉
  await assertCluster(page, 'T-overlay', { shot: '20261019_toolbar-overlap-overlay.png', expectOverlay: true });
  ok('R0 主文档无 JS 运行时错误（overlay）', errors.length === 0, errors.slice(0, 3).join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(results.join('\n'));
console.log(`\n═══ canvas-html-toolbar-overlap: ${pass} PASS / ${fail} FAIL ═══`);
process.exit(fail ? 1 : 0);
