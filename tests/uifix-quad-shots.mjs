// uifix-quad-shots.mjs — 四件批交付截图：叠卡三版 look（作者挑版）+ 快照/× 卡面
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = 8155;
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = resolve(HERE, '../evidence/20261021_uifix-quad/shots');
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };
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

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'light', deviceScaleFactor: 2 });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
});
const page = await ctx.newPage();
await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
await installTicketMock(page);
await page.routeWebSocket(/\/ws/, (ws) => {
  ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; } if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 })); });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
});
await page.goto(BASE + '/index.html');
await page.waitForSelector('#activity-bar', { timeout: 15000 });
await sleep(600);

for (const f of ['发布说明.md', '架构总览.md', 'roadmap.md', 'meeting-notes.md', 'sprint-plan.md', 'api-design.md']) {
  await page.evaluate(({ f }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:/tmp/docs/' + f, itemType: 'markdown', title: f, content: `# ${f}\n\n## 概要\n\n叠卡快照验收——本段文字应出现在卡片快照内（真实内容克隆，非 mock 行条）。\n\n- 列表项甲\n- 列表项乙\n\n\`\`\`js\nconst x = 1;\n\`\`\`\n`, absPath: '/tmp/docs/' + f, pinned: true },
    }));
  }, { f });
  await sleep(200);
}
await sleep(600);
const pr = await page.evaluate(() => { const p = document.getElementById('canvas-panel'); return { x: p.offsetLeft - 8, y: p.offsetTop - 8, width: p.offsetWidth + 16, height: p.offsetHeight + 16 }; });

await page.click('#deck-fab');
await sleep(3200);
await page.screenshot({ path: join(OUT, 'look-v2-default.png'), clip: pr });

for (const v of ['v1', 'v3']) {
  await page.evaluate((vv) => window.__canvasDeck.setLook(vv), v);
  await sleep(1500);
  await page.screenshot({ path: join(OUT, `look-${v}.png`), clip: pr });
}
await page.evaluate(() => window.__canvasDeck.setLook('v2'));

// 前景换到第二张：快照 + × 可见帧
await page.evaluate(() => { const p = document.getElementById('canvas-panel'); p.dispatchEvent(new WheelEvent('wheel', { deltaY: 0 })); });
const r2 = await page.evaluate(() => { const p = document.getElementById('canvas-panel'); const r = p.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; });
await page.mouse.move(r2.x, r2.y);
for (let i = 0; i < 10; i++) { await page.mouse.wheel(0, 24); await sleep(10); }
await sleep(3000);
await page.screenshot({ path: join(OUT, 'card-anatomy-snap-x.png'), clip: pr });

await browser.close();
server.close();
console.log('shots done');
