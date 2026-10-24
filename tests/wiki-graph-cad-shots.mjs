// wiki-graph-cad-shots.mjs — CAD 鼠标交互批验收证据（2026-10-24 作者令）。
//
// 交互对照（前后实拍 + 锚点断言）：
//   1. cad-before-wheel-zoom.png → cad-after-wheel-zoom.png —— 鼠标滚轮（无修饰、
//      整数档）在画布 25%/30% 偏心锚点连滚 6 格：放大 ≈2.95x，锚点下的世界点
//      屏幕位置漂移 < 2px（CAD 式光标锚定），收敛无跳变；
//   2. cad-after-middle-pan.png —— 中键拖拽平移（+120/+80），1:1 跟手、松手不点选；
//   3. cad-after-left-select.png —— 左键点节点语义不变（选中 + 左栏详情卡）。
// 用法：node tests/wiki-graph-cad-shots.mjs（隔离静态服务器 + 页内 mock，8100+ 端口
// 纪律——本脚本用 8195，永不触碰宿主 8080）。
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261024_wiki-graph-cad', 'shots');
mkdirSync(OUT, { recursive: true });
const PORT = 8195;
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };
const PINCH_K = 0.0015; // 必须与 wikiGraph.js 的 PINCH_K 同值

const WIKI_GRAPH = {
  projects: [{ name: 'alpha', vault: '/w/alpha', docCount: 2 }],
  nodes: [
    { id: 'alpha:docs/a.md', project: 'alpha', rel: 'docs/a.md', title: 'Alpha One', kind: 'doc', type: 'spec' },
    { id: 'alpha:docs/b.md', project: 'alpha', rel: 'docs/b.md', title: 'Beta Two', kind: 'doc' },
    { id: 'alpha:docs/c.md', project: 'alpha', rel: 'docs/c.md', title: 'Gamma Three', kind: 'doc' },
    { id: 'alpha:docs/d.md', project: 'alpha', rel: 'docs/d.md', title: 'Delta Four', kind: 'doc' },
    { id: 'alpha:docs/e.md', project: 'alpha', rel: 'docs/e.md', title: 'Epsilon Five', kind: 'doc' },
  ],
  edges: [
    { from: 'alpha:docs/a.md', to: 'alpha:docs/b.md' },
    { from: 'alpha:docs/a.md', to: 'alpha:docs/c.md' },
    { from: 'alpha:docs/b.md', to: 'alpha:docs/d.md' },
    { from: 'alpha:docs/c.md', to: 'alpha:docs/e.md' },
    { from: 'alpha:docs/d.md', to: 'alpha:docs/e.md' },
  ],
};

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

const server = createServer(async (req, res) => {
  try {
    let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p === '/') p = '/index.html';
    if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
    const file = resolve(join(WEB, p));
    if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
    const data = await readFile(file);
    res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(data);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.emulateMedia({ colorScheme: 'dark' });
await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('neblink_token', 't');
  localStorage.setItem('stand_locale', 'en');
});
await page.route('**/api/**', r => r.fulfill({ json: {} }));
await page.route('**/api/wiki/graph', r => r.fulfill({ json: WIKI_GRAPH }));
await page.route('**/agents/**', r => r.fulfill({ json: {} }));
await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));
await page.routeWebSocket(/\/ws/, ws => {
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
});
const errors = [];
page.on('pageerror', e => errors.push('pageerror: ' + String(e)));
page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

await page.goto(`http://127.0.0.1:${PORT}`);
await page.click('#wiki-btn');
await page.waitForSelector('.wiki-graph-pane', { state: 'attached', timeout: 10000 });
await page.waitForFunction(() => {
  const p = window.__wikiGraphProbe && window.__wikiGraphProbe();
  return p && p.alpha < 0.02;
}, { timeout: 15000 });
// settled：等 late tab-refetch 的 debounce rebuild 窗口过去（坐标交互前）
await page.waitForFunction(async () => {
  const a = window.__wikiGraphProbe();
  await new Promise(r => setTimeout(r, 400));
  const b = window.__wikiGraphProbe();
  return a.alpha < 0.02 && b.alpha < 0.02 && a.scale === b.scale && a.vx === b.vx && a.vy === b.vy;
}, { timeout: 15000 });

const hint = await page.textContent('#wiki-graph-side .wg-hint');
ok('图例注明 CAD 约定（滚轮=缩放/中键=平移）', /zoom/i.test(hint) && /pan/i.test(hint), hint.trim());
await page.screenshot({ path: join(OUT, 'cad-before-wheel-zoom.png') });

// ── 1. 滚轮锚点缩放（鼠标档：无修饰、整数 Δ-120 ×6 格，偏心锚点 25%/30%）──
const zoom = await page.evaluate(() => new Promise((resolveDone) => {
  const c = document.querySelector('.wiki-canvas');
  const r = c.getBoundingClientRect();
  const ax = r.left + r.width * 0.25, ay = r.top + r.height * 0.30; // 偏心锚点（非画布中心）
  const before = window.__wikiGraphProbe();
  // 世界坐标 = wikiGraph.js toWorld 精确形（screen = (w − W/2)·s + W/2 + v 的逆）。
  const wOf = (p) => ({
    x: ((ax - r.left) - p.W / 2 - p.vx) / p.scale + p.W / 2,
    y: ((ay - r.top) - p.H / 2 - p.vy) / p.scale + p.H / 2,
  });
  const wAnchor = wOf(before);
  const s0 = before.scale;
  let i = 0;
  const tick = () => {
    if (i < 6) {
      c.dispatchEvent(new WheelEvent('wheel', {
        deltaY: -120, clientX: ax, clientY: ay, bubbles: true, cancelable: true, // 鼠标档：整数、无修饰、≥48px
      }));
      i++;
      requestAnimationFrame(tick);
      return;
    }
    const t0 = performance.now();
    const wait = () => {
      const p = window.__wikiGraphProbe();
      if (Math.abs(p.scale - p.zoomTarget) <= 0.0005 || performance.now() - t0 > 4000) {
        const scr = { x: (wAnchor.x - p.W / 2) * p.scale + p.W / 2 + p.vx, y: (wAnchor.y - p.H / 2) * p.scale + p.H / 2 + p.vy };
        resolveDone({ s0, s1: p.scale, target: p.zoomTarget, anchorScreen: scr, ax: ax - r.left, ay: ay - r.top });
        return;
      }
      requestAnimationFrame(wait);
    };
    wait();
  };
  requestAnimationFrame(tick);
}));
ok('滚轮 = 缩放（无修饰鼠标档）', zoom.s1 > zoom.s0 * 2.5, `scale ${zoom.s0.toFixed(3)} → ${zoom.s1.toFixed(3)}`);
ok('缩放收敛 zoomTarget（阻尼追帧收尾）', Math.abs(zoom.s1 - zoom.target) <= 0.001, `scale=${zoom.s1.toFixed(4)} target=${zoom.target.toFixed(4)}`);
const expected = Math.min(4.0, Math.exp(6 * 120 * PINCH_K)); // 无钳制期望 ≈2.95
ok('缩放数学 = exp 曲线（6 格 → ≈2.95x）', Math.abs(zoom.s1 - expected) < 0.02, `got ${zoom.s1.toFixed(4)}, expect ${expected.toFixed(4)}`);
const anchorDrift = Math.hypot(zoom.anchorScreen.x - zoom.ax, zoom.anchorScreen.y - zoom.ay);
ok('光标锚点保持（锚点世界点屏幕漂移 < 2px）', anchorDrift < 2, `drift=${anchorDrift.toFixed(3)}px`);
await page.screenshot({ path: join(OUT, 'cad-after-wheel-zoom.png') });

// ── 2. 中键拖拽 = 平移（CAD 核心手势）──
const v0 = await page.evaluate(() => { const p = window.__wikiGraphProbe(); return { vx: p.vx, vy: p.vy }; });
const empty = await page.evaluate(() => {
  const p = window.__wikiGraphProbe();
  const c = document.querySelector('.wiki-canvas');
  const r = c.getBoundingClientRect();
  let best = null, bestD = 0;
  for (let gx = 1; gx < 8; gx++) for (let gy = 1; gy < 6; gy++) {
    const sx = r.width * gx / 8, sy = r.height * gy / 6;
    let dmin = Infinity;
    for (const n of p.nodes) {
      const nx = (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx;
      const ny = (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy;
      dmin = Math.min(dmin, Math.hypot(nx - sx, ny - sy));
    }
    if (dmin > bestD) { bestD = dmin; best = { sx, sy }; }
  }
  return { ...best, dmin: bestD, left: r.left, top: r.top };
});
ok('找到距节点 ≥30px 的空画布点', empty.dmin > 30, `${empty.dmin.toFixed(1)}px`);
const ex = empty.left + empty.sx, ey = empty.top + empty.sy;
await page.mouse.move(ex, ey);
await page.mouse.down({ button: 'middle' });
await page.mouse.move(ex + 120, ey + 80, { steps: 6 });
const vMid = await page.evaluate(() => { const p = window.__wikiGraphProbe(); return { vx: p.vx, vy: p.vy }; });
await page.mouse.up({ button: 'middle' });
ok('中键拖拽 = 平移（1:1 跟手 +120/+80）',
  Math.round(vMid.vx - v0.vx) === 120 && Math.round(vMid.vy - v0.vy) === 80,
  `d=(${(vMid.vx - v0.vx).toFixed(1)}, ${(vMid.vy - v0.vy).toFixed(1)})`);
ok('中键松手不触发点选', await page.evaluate(() => window.__wikiGraphProbe().selected) === null);
await page.screenshot({ path: join(OUT, 'cad-after-middle-pan.png') });

// ── 3. 左键语义回归：点节点 → 选中 + 详情卡 ──
await page.evaluate(() => { // shift+wheel = 保底平移，把目标摇到画布中心
  const p = window.__wikiGraphProbe();
  const c = document.querySelector('.wiki-canvas');
  const r = c.getBoundingClientRect();
  const n = p.nodes.find((m) => m.id === 'alpha:docs/a.md');
  const sx = (n.x - p.W / 2) * p.scale + p.W / 2 + p.vx;
  const sy = (n.y - p.H / 2) * p.scale + p.H / 2 + p.vy;
  c.dispatchEvent(new WheelEvent('wheel', {
    deltaX: p.W / 2 - sx, deltaY: p.H / 2 - sy, shiftKey: true,
    clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true, cancelable: true,
  }));
});
await page.waitForFunction(async () => {
  const a = window.__wikiGraphProbe();
  await new Promise(r => setTimeout(r, 300));
  const b = window.__wikiGraphProbe();
  return a.vx === b.vx && a.vy === b.vy && a.scale === b.scale;
}, { timeout: 8000 });
const p1 = await page.evaluate(() => window.__wikiGraphProbe());
const hub = p1.nodes.find(n => n.id === 'alpha:docs/a.md');
const box = await page.locator('.wiki-canvas').boundingBox();
await page.mouse.click(
  box.x + (hub.x - p1.W / 2) * p1.scale + p1.W / 2 + p1.vx,
  box.y + (hub.y - p1.H / 2) * p1.scale + p1.H / 2 + p1.vy,
);
await page.waitForFunction(() => window.__wikiGraphProbe().selected === 'alpha:docs/a.md', { timeout: 5000 });
ok('左键点节点 → 选中（语义回归）', true);
ok('左栏详情卡刷新', (await page.locator('#wiki-graph-side .wg-detail-body h2').textContent()) === 'Alpha One');
await page.screenshot({ path: join(OUT, 'cad-after-left-select.png') });

ok('console 零错误', errors.length === 0, errors.slice(0, 3).join(' | '));

await browser.close();
server.close();
console.log(`\nshots → ${OUT}`);
console.log(failures ? `\n${failures} FAIL` : '\nALL PASS');
process.exit(failures ? 1 : 0);
