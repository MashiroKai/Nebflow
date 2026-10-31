// capture-deck-perf.mjs — 堆叠性能批⑤ 帧率/长帧对比探针。
// 同一脚本跑两棵树（改前 = git worktree at HEAD / 改后 = 本批工作树），
// 输出 JSON 读数（fps / 长帧数 / p95 帧间隔），双主题各一组。
// Run: node tests/capture-deck-perf.mjs           （默认 1440×900 dark）
//      OUT=/path/perf.json node tests/capture-deck-perf.mjs
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8193);
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = process.env.OUT || resolve(HERE, '../evidence/20261031_topbar-batch/perf-after.json');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync(dirname(OUT), { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { res.writeHead(404); res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch({ args: ['--enable-gpu'] });

const NAMES = Array.from({ length: 8 }, (_, i) => `deck-perf-doc-${String(i + 1).padStart(2, '0')}.md`);
const FILE_CONTENTS = new Map(NAMES.map((n) => ['/tmp/dp-fixture/' + n, { content: '# ' + n + '\n\n' + 'body '.repeat(200), itemType: 'markdown' }]));

async function boot({ colorScheme }) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: rec ? rec.content : '', itemType: 'markdown', fileName: (m.path || '').split('/').pop(), size: rec ? rec.content.length : 0 }));
      }
    });
  });
  const page = await ctx.newPage();
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page };
}

/** 页面内帧采样器：rAF 计数 + >50ms 长帧数 + 帧间隔 p95。 */
async function startSampler(page) {
  await page.evaluate(() => {
    window.__perf = { frames: 0, long: 0, deltas: [] };
    let last = performance.now();
    const tick = (t) => {
      const d = t - last; last = t;
      window.__perf.frames++;
      if (d > 50) window.__perf.long++;
      if (window.__perf.deltas.length < 5000) window.__perf.deltas.push(Math.round(d * 10) / 10);
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}
async function stopSampler(page, label) {
  return page.evaluate(({ label }) => {
    const p = window.__perf;
    const ds = [...p.deltas].sort((a, b) => a - b);
    const p95 = ds[Math.floor(ds.length * 0.95)] || 0;
    const avg = ds.reduce((a, b) => a + b, 0) / (ds.length || 1);
    window.__perf = null;
    return { label, frames: p.frames, longFrames: p.long, avgDelta: +avg.toFixed(1), p95Delta: p95 };
  }, { label });
}

const results = { viewport: '1440x900', cards: NAMES.length, schemes: {} };

for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await boot({ colorScheme: scheme });
  for (const n of NAMES) {
    await page.evaluate(({ absPath, content }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + absPath, itemType: 'markdown', title: absPath.split('/').pop(), content, absPath, pinned: true },
      }));
    }, { absPath: '/tmp/dp-fixture/' + n, content: FILE_CONTENTS.get('/tmp/dp-fixture/' + n).content });
    await sleep(220);
  }
  await page.waitForSelector('body.canvas-open', { timeout: 8000 });
  await sleep(400);

  const phases = [];
  // 相 1：开卡（spread 0→1 + 快照分帧挂载）
  await startSampler(page);
  await page.click('#deck-fab');
  await sleep(1400);
  phases.push(await stopSampler(page, 'open(1.4s)'));
  await page.waitForTimeout(300);

  // 相 2：滚轮擦洗 3s（鼠标离散格，逐格换卡 = 全程位姿动画 + 快照换窗）
  await startSampler(page);
  const c = await page.evaluate(() => {
    const r = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
  });
  const t0 = Date.now();
  let dir = 1;
  while (Date.now() - t0 < 3000) {
    await page.evaluate(({ x, y, dy }) => {
      document.dispatchEvent(new WheelEvent('wheel', { deltaY: dy, deltaMode: 0, bubbles: true, cancelable: true, clientX: x, clientY: y }));
    }, { x: c.x, y: c.y, dy: 120 * dir });
    dir = -dir;
    await sleep(140);
  }
  phases.push(await stopSampler(page, 'wheel-scrub(3s)'));

  // 相 3：卡面横拖跟手 1.6s（pointer 拖划 = 每帧 transform 直写）
  await startSampler(page);
  const card = await page.evaluate(() => {
    const el = document.querySelector('#deck-layer .deck-card.front') || document.querySelector('#deck-layer .deck-card');
    const r = el.getBoundingClientRect();
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
  });
  await page.mouse.move(card.x, card.y);
  await page.mouse.down();
  const dragT0 = Date.now();
  let dx = 0;
  while (Date.now() - dragT0 < 1600) {
    dx += 14; if (dx > 220) dx = -220;
    await page.mouse.move(card.x + dx, card.y, { steps: 2 });
    await sleep(16);
  }
  await page.mouse.up();
  await sleep(120);
  phases.push(await stopSampler(page, 'card-drag(1.6s)'));

  // 相 4：收卡淡出
  await startSampler(page);
  await page.click('#deck-fab');
  await sleep(700);
  phases.push(await stopSampler(page, 'close(0.7s)'));

  results.schemes[scheme] = phases.map((p) => ({ ...p, fps: +(1000 / p.avgDelta).toFixed(1) }));
  await ctx.close();
}

writeFileSync(OUT, JSON.stringify(results, null, 2));
console.log(JSON.stringify(results, null, 2));
await browser.close();
server.close();
