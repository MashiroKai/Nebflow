// uifix-glass-shots.mjs — 第五件交付截图：硬切修复前后对照（glass/降级 × 亮/暗）
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = resolve(HERE, '../evidence/20261021_uifix-quad/shots');
mkdirSync(OUT, { recursive: true });
const server = createServer((req, res) => {
  let p = new URL(req.url, 'http://x').pathname;
  if (p === '/') p = '/index.html';
  const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { res.writeHead(404); res.end(); }
});
await new Promise((r) => server.listen(8167, '127.0.0.1', r));

const browser = await chromium.launch();
for (const scheme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1200, height: 800 }, colorScheme: scheme, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  await page.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; } if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 })); });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto('http://127.0.0.1:8167/index.html');
  await page.waitForSelector('#chat', { timeout: 15000 });
  await new Promise((r) => setTimeout(r, 700));
  // 布景：任务带 + 高对比消息层（穿帮可见）
  await page.evaluate(() => {
    const tl = document.getElementById('task-list');
    tl.classList.add('has-tasks');
    tl.innerHTML = '<div class="task-card" style="height:64px"><div class="task-header" style="height:28px;padding:0 12px;font-weight:600"></div></div>';
    document.getElementById('top-overlays').style.width = 'min(520px, 92%)';
    document.getElementById('top-overlays').style.alignSelf = 'flex-start';
    const main = document.getElementById('main');
    const strip = document.createElement('div');
    strip.style.cssText = 'position:absolute;z-index:5;left:24px;width:60%;top:120px;height:300px;' +
      'background:repeating-linear-gradient(90deg,#111 0 10px,#f8f8f8 10px 20px);border-radius:10px;';
    main.appendChild(strip);
  });
  await new Promise((r) => setTimeout(r, 900));
  const clip = await page.evaluate(() => {
    const r = document.getElementById('top-overlays').getBoundingClientRect();
    return { x: 8, y: Math.max(0, r.top - 30), width: 560, height: 170 };
  });
  await page.screenshot({ path: join(OUT, `glass-${scheme}-frosted.png`), clip });
  await page.evaluate(() => window.__scrollUnderGlass.degrade(true));
  await new Promise((r) => setTimeout(r, 200));
  await page.screenshot({ path: join(OUT, `glass-${scheme}-degraded.png`), clip });
  await ctx.close();
}
await browser.close();
server.close();
console.log('glass shots done');
