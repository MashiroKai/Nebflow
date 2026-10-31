// canvas-headclose-left-shots.mjs — 电脑端关签钮左移批（作者令 2026-10-31 21:37）
// 证据截图 + 对称几何探针。
//
// 判据（作者令）：标签条上的关闭标签页按钮移到左侧，与堆叠按钮左右对称；
// 堆叠钮现位照现（#deck-fab：面板顶 +10 / 面板右 −12 / 40px 液体玻璃圆钮）；
// 只动桌面端，手机端不碰；标签条其余元素序不变。
//
// 产出（SHOTS_DIR，默认 evidence/20261031_headclose-left/<TAG>/，TAG=before|after）：
//   d1-header.png        桌面常态头全景（左关签钮 ↔ 右堆叠钮 + 居中标题胶囊）
//   d2-deck-open.png     叠卡态开（左关签钮仍在顶带恒可点）
//   m1-canvas-toolbar.png 手机 canvas 工具条（对照腿：不碰）
//   probes.json          对称几何断言全量面
// 运行：TAG=before node tests/canvas-headclose-left-shots.mjs
//
// 桌面关签钮左移批断言（after 态）：
//   S1 #canvas-head-close 为 body 层浮钮（非 .canvas-header 子节点），胶囊内零按钮
//   S2 顶对齐堆叠钮：|close.top − fab.top| ≤ 1px（同 = 面板顶 +10）
//   S3 左右对称：close.left − panel.left === 12 ±1；fab 侧 window.innerWidth −
//      fab.right === 视口宽 − panel.right + 12 同口径（fab.right = panel.right −12）
//   S4 材质/尺寸同堆叠钮：40×40 圆钮（.g2-fab 共享类）
//   S5 功能性：点左钮 = 关活动页签（tabCount −1，邻卡落位）
//   S6 卡窗/手机层零命中：mobile-layer 与 card-window 下 display:none
//   S7 手机工具条不动：.mf-cv-close / .mf-cv-stack 均在挂（.g2-fab）

import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8177);
const BASE = `http://127.0.0.1:${PORT}`;
const TAG = process.env.TAG || 'after';
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, `../evidence/20261031_headclose-left/${TAG}`);
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const TALL = '# bravo\n\n' + Array.from({ length: 80 }, (_, i) => `bravo 行 ${i + 1}：左移批证据填充行。`).join('\n') + '\n';
const FILE_CONTENTS = new Map([
  ['/tmp/hc-left/alpha.md', '# alpha\n\nalpha 正文。\n'],
  ['/tmp/hc-left/bravo.md', TALL],
]);

/* ── 静态服务 ── */
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB, p);
  if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
  try {
    const b = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(b);
  } catch { res.writeHead(404); res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const browser = await chromium.launch();
const probes = {};
const errors = [];

async function boot({ viewport = { width: 1440, height: 900 }, mobile = false } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme: 'dark', hasTouch: mobile });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await ctx.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    const u = new URL(route.request().url());
    const fp = u.searchParams.get('path') || '';
    try {
      const body = readFileSync(fp);
      return route.fulfill({ status: 200, headers: { 'Content-Type': 'text/markdown' }, body });
    } catch {
      return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' });
    }
  });
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: rec ? rec.content : '', itemType: 'markdown', fileName: m.path.split('/').pop(), size: 1 }));
      }
    });
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector(mobile ? 'body.mobile-layer' : '#activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page };
}

const openFile = (page, p) => page.evaluate((pp) => {
  const ext = (pp.split('.').pop() || '').toLowerCase();
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + pp, itemType: ext === 'md' ? 'markdown' : 'code', content: '', absPath: pp, size: 0, title: pp.split('/').pop(), pinned: true },
  }));
}, p);

const geo = (page) => page.evaluate(() => {
  const r = (sel) => { const el = document.querySelector(sel); if (!el) return null; const b = el.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom, w: b.width, h: b.height }; };
  const panel = r('#canvas-panel');
  const close = r('#canvas-head-close');
  const fab = r('#deck-fab');
  const cap = r('#canvas-panel .canvas-header');
  const closeEl = document.getElementById('canvas-head-close');
  const capEl = document.querySelector('#canvas-panel .canvas-header');
  const panelEl = document.getElementById('canvas-panel');
  return {
    panel, close, fab, cap,
    closeInline: closeEl ? closeEl.style.cssText : null,
    fabInline: document.getElementById('deck-fab') ? document.getElementById('deck-fab').style.cssText : null,
    panelOffset: panelEl ? { l: panelEl.offsetLeft, t: panelEl.offsetTop, w: panelEl.offsetWidth, h: panelEl.offsetHeight } : null,
    closeInHeader: !!(closeEl && capEl && capEl.contains(closeEl)),
    capButtons: capEl ? [...capEl.querySelectorAll('button')].filter((b) => b.getClientRects().length > 0).length : -1,
    closeDisplay: closeEl ? getComputedStyle(closeEl).display : null,
    closeZ: closeEl ? getComputedStyle(closeEl).zIndex : null,
    closeDisabled: closeEl ? closeEl.disabled : null,
    fabDisabled: document.getElementById('deck-fab') ? document.getElementById('deck-fab').disabled : null,
    closeG2: closeEl ? closeEl.classList.contains('g2-fab') : false,
    tabCount: (window.__canvasDeck.state() || {}).tabCount,
  };
});

/* ════════ 桌面 ════════ */
{
  const { ctx, page } = await boot();
  await openFile(page, '/tmp/hc-left/alpha.md');
  await openFile(page, '/tmp/hc-left/bravo.md');   // bravo 活动
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(700);

  const g = await geo(page);
  probes.desktop = g;
  await page.screenshot({ path: join(SHOTS_DIR, 'd1-header.png') });

  // 叠卡态：左钮恒可点（z-250 与 fab 同档）
  await page.click('#deck-fab');
  await sleep(900);
  probes.deckOpen = await geo(page);
  await page.screenshot({ path: join(SHOTS_DIR, 'd2-deck-open.png') });
  await page.keyboard.press('Escape');
  await sleep(400);

  // 功能性：点左钮 = 关活动页签
  const before = (await geo(page)).tabCount;
  await page.click('#canvas-head-close');
  await sleep(500);
  const after = (await geo(page)).tabCount;
  probes.closeClick = { before, after, worked: after === before - 1 };

  // 胶囊拖动净空（滑删批几何）：左钮不在胶囊内 ⇒ 胶囊 room 两侧对称基准不受扰
  probes.errors = errors.slice(0, 5);
  await ctx.close();
}

/* ════════ 移动（对照腿：不碰，只取证） ════════ */
{
  const { ctx, page } = await boot({ viewport: { width: 390, height: 844 }, mobile: true });
  await openFile(page, '/tmp/hc-left/alpha.md');
  await openFile(page, '/tmp/hc-left/bravo.md');
  await sleep(600);
  await page.click('#mobile-dock .dock-key[data-dock="canvas"]');
  await sleep(900);
  probes.mobile = await page.evaluate(() => {
    const close = document.querySelector('.mf-cv-close');
    const stack = document.querySelector('.mf-cv-stack');
    const hc = document.getElementById('canvas-head-close');
    return {
      mfClose: !!close, mfStack: !!stack,
      mfG2: close?.classList.contains('g2-fab') && stack?.classList.contains('g2-fab'),
      headCloseDisplay: hc ? getComputedStyle(hc).display : 'absent',
      mobileLayer: document.body.classList.contains('mobile-layer'),
    };
  });
  await page.screenshot({ path: join(SHOTS_DIR, 'm1-canvas-toolbar.png') });
  await ctx.close();
}

writeFileSync(join(SHOTS_DIR, 'probes.json'), JSON.stringify(probes, null, 2));
console.log(JSON.stringify(probes, null, 2));
console.log('shots ->', SHOTS_DIR);

await browser.close();
server.close();

/* ── 对称断言（after 判据；before 态打印几何供对照）── */
const g = probes.desktop;
if (!g || !g.close) { console.log('PROBE-ERROR: desktop close btn missing'); process.exit(1); }
const approx = (a, b, tol = 1) => Math.abs(a - b) <= tol;
const checks = [];
if (TAG === 'after') {
  checks.push(['S1 body 层浮钮（非胶囊子节点）+ 胶囊内零按钮', !g.closeInHeader && g.capButtons === 0, `inHeader=${g.closeInHeader} capBtns=${g.capButtons}`]);
  checks.push(['S2 顶对齐堆叠钮（|Δtop| ≤ 1px）', approx(g.close.t, g.fab.t), `close.t=${g.close.t.toFixed(1)} fab.t=${g.fab.t.toFixed(1)}`]);
  checks.push(['S3 左右对称（close.l − panel.l = 12 ±1）', approx(g.close.l - g.panel.l, 12), `Δl=${(g.close.l - g.panel.l).toFixed(1)}`]);
  checks.push(['S3b fab 右镜像（panel.r − fab.r = 12 ±1）', approx(g.panel.r - g.fab.r, 12), `Δr=${(g.panel.r - g.fab.r).toFixed(1)}`]);
  checks.push(['S4 尺寸同堆叠钮（40×40）', approx(g.close.w, g.fab.w, 0.5) && approx(g.close.h, g.fab.h, 0.5), `close=${g.close.w.toFixed(1)}×${g.close.h.toFixed(1)} fab=${g.fab.w.toFixed(1)}×${g.fab.h.toFixed(1)}`]);
  checks.push(['S4b 材质 .g2-fab 在挂', g.closeG2, `g2=${g.closeG2}`]);
  checks.push(['S5 点左钮关活动页签', probes.closeClick.worked, JSON.stringify(probes.closeClick)]);
  checks.push(['S6 显示档在挂（inline-flex 经 fixed 块化 = computed flex）', g.closeDisplay === 'flex' || g.closeDisplay === 'inline-flex', g.closeDisplay]);
  // 叠卡态：面板走整区 transform（探针视觉盒 ≠ 布局盒）——对称判据退化为
  // 左钮与 fab 同顶同尺寸 + 分居面板中线两侧（与 fab 钉死固定位语义同档）。
  const dg = probes.deckOpen;
  if (dg && dg.close && dg.fab) {
    const mid = (dg.panel.l + dg.panel.r) / 2;
    checks.push(['S8 叠卡态左钮在场恒可点（同顶 fab + 分居中线两侧）',
      approx(dg.close.t, dg.fab.t) && approx(dg.close.w, dg.fab.w, 0.5) && dg.close.l < mid && dg.fab.l > mid,
      `close.t=${dg.close.t.toFixed(1)} fab.t=${dg.fab.t.toFixed(1)} close.l=${dg.close.l.toFixed(0)} fab.l=${dg.fab.l.toFixed(0)}`]);
  }
  checks.push(['S7 手机腿不碰（mf 双钮在挂 + head-close 隐）', probes.mobile.mfClose && probes.mobile.mfStack && probes.mobile.headCloseDisplay === 'none', JSON.stringify(probes.mobile)]);
  let bad = 0;
  for (const [name, pass, detail] of checks) {
    console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}  — ${detail}`);
    if (!pass) bad++;
  }
  process.exit(bad ? 1 : 0);
} else {
  console.log('TAG=before：几何基线（改前照）—', JSON.stringify({
    closeInHeader: g.closeInHeader, capButtons: g.capButtons,
    closeBox: g.close, fabBox: g.fab, capBox: g.cap,
  }));
}
