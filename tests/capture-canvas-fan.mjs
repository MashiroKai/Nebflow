// capture-canvas-fan.mjs — canvas 顶部文件扇 修前/修后对照截图。
// 全应用 harness（隔离静态服务器，同 canvas-capsule-ring.spec 的 boot 形态）：
// 开 4 个 markdown 页签 → hover 标题头胶囊开环/扇 → 截 canvas 面板顶带。
// 桌面亮/暗 + 移动（去重证据：移动层不开环）。用法:
//   node tests/capture-canvas-fan.mjs <outDir> <tag>
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const [outDir, tag] = process.argv.slice(2);
if (!outDir || !tag) { console.error('usage: node tests/capture-canvas-fan.mjs <outDir> <tag>'); process.exit(1); }

const PORT = Number(process.env.PORT || 8189);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

const FILE_CONTENTS = new Map([
  ['/tmp/cfan-fixture/alpha.md', '# alpha\n\nalpha body\n'],
  ['/tmp/cfan-fixture/bravo.md', '# bravo\n\nbravo body\n'],
  ['/tmp/cfan-fixture/charlie.md', '# charlie\n\ncharlie body\n'],
  ['/tmp/cfan-fixture/delta.md', '# delta\n\ndelta body\n'],
]);

const browser = await chromium.launch();
const errors = [];

async function boot({ colorScheme, viewport }) {
  const ctx = await browser.newContext({ viewport, colorScheme });
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
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          content: rec || '', itemType: 'markdown',
          fileName: (m.path || '').split('/').pop(), size: rec ? rec.length : 0,
        }));
      }
    });
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page };
}

async function openFile(page, absPath) {
  const title = absPath.split('/').pop();
  await page.evaluate(({ absPath, title }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + absPath, itemType: 'markdown', title, content: '# x\n', absPath, pinned: true },
    }));
  }, { absPath, title });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { timeout: 10000, state: 'attached' });
}

for (const run of [
  { name: 'desktop-dark', colorScheme: 'dark', viewport: { width: 1440, height: 900 } },
  { name: 'desktop-light', colorScheme: 'light', viewport: { width: 1440, height: 900 } },
  { name: 'mobile', colorScheme: 'dark', viewport: { width: 390, height: 844 } },
]) {
  const { ctx, page } = await boot(run);
  const files = [...FILE_CONTENTS.keys()];
  for (const f of files) await openFile(page, f);   // delta 活动
  await sleep(400);
  const isMobile = run.name === 'mobile';
  if (!isMobile) {
    await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
    await sleep(300);
    const p = await page.evaluate(() => {
      const hdr = document.querySelector('#canvas-panel .canvas-header');
      const r = hdr.getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    });
    await page.mouse.move(p.x, p.y);
    await sleep(600);                                // 开环过渡走完
  }
  const shotBox = isMobile ? null : await page.evaluate(() => {
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: Math.min(p.height, 420) };
  });
  const out = join(outDir, `${tag}-${run.name}.png`);
  await page.screenshot({ path: out, clip: shotBox || undefined });
  console.log(`NEBFLOW_SHOT:${out}`);
  await ctx.close();
}

await browser.close();
server.close();
if (errors.length) { console.error('page errors:', errors); process.exit(1); }
console.log('capture done');
