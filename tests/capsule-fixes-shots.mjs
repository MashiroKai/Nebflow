// capsule-fixes-shots.mjs — 胶囊双 bug 修复批证据截图（亮暗 × 关键面）
// 证据面：① 环开（图谱 tab 活跃——报障主要现场）② 悬停 pill 标签让位（修前 pill+标签同名复读）
//         ③ 引用边 pill（.td 零复读）④ 收起常态。输出 evidence/20261025_capsule-fixes/shots/。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = resolve(HERE, '../evidence/20261025_capsule-fixes/shots');
mkdirSync(OUT, { recursive: true });
const PORT = Number(process.env.PORT || 8191);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
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

const GRAPH = {
  projects: [{ name: 'proj', docCount: 5 }],
  nodes: [
    { id: 'n1', rel: 'alpha.md', title: 'alpha', degree: 3 },
    { id: 'n2', rel: 'bravo.md', title: 'bravo', degree: 2 },
    { id: 'n3', rel: 'notes/charlie.md', title: 'charlie', degree: 2 },
    { id: 'n4', rel: 'delta.md', title: 'delta', degree: 1 },
    { id: 'n5', rel: 'echo.md', title: 'echo', degree: 2 },
  ],
  edges: [
    { from: 'n1', to: 'n2', anchor: null }, { from: 'n2', to: 'n3', anchor: null },
    { from: 'n3', to: 'n1', anchor: null }, { from: 'n4', to: 'n1', anchor: null },
    { from: 'n5', to: 'n1', anchor: null },
  ],
};
const FILE_CONTENTS = new Map([
  ['/tmp/cf/alpha.md', { content: '# alpha\n\nalpha body\n', itemType: 'markdown' }],
  ['/tmp/cf/bravo.md', { content: '# bravo\n\nbravo body\n', itemType: 'markdown' }],
]);

async function boot(colorScheme) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await ctx.route('**/api/wiki/graph**', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GRAPH) }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') {
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: rec ? rec.content : '', itemType: rec ? rec.itemType : 'markdown', fileName: (m.path || '').split('/').pop(), size: 10 }));
      }
    });
  });
  const page = await ctx.newPage();
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page };
}

async function openFile(page, absPath) {
  const rec = FILE_CONTENTS.get(absPath);
  await page.evaluate(({ absPath, title, content, itemType }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { id: 'file:' + absPath, itemType, title, content, absPath, pinned: true } }));
  }, { absPath, title: absPath.split('/').pop(), content: rec.content, itemType: rec.itemType });
  await sleep(300);
}

async function panelBox(page) {
  return page.evaluate(() => {
    const p = document.getElementById('canvas-panel').getBoundingClientRect();
    return { x: Math.max(0, p.left), y: Math.max(0, p.top - 56), width: p.width, height: 300 };
  });
}

for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await boot(scheme);
  await openFile(page, '/tmp/cf/alpha.md');
  await openFile(page, '/tmp/cf/bravo.md');
  await page.click('#wiki-btn');
  await sleep(2600); // 图谱渲染 + sim 稳定（报障主要现场 = 图谱 tab 活跃）

  // ③（先拍：图谱仍活跃）图谱节点悬停 pill：标签让位（同屏同名消除）
  await sleep(1400); // sim 稳定
  const probe = await page.evaluate(() => window.__wikiGraphProbe());
  if (probe) {
    const n = probe.nodes.find((m) => m.id === 'n1') || probe.nodes[0];
    const c = await page.evaluate(() => document.querySelector('.wiki-canvas').getBoundingClientRect().toJSON());
    const sx = c.left + (n.x - probe.W / 2) * probe.scale + probe.W / 2 + probe.vx;
    const sy = c.top + (n.y - probe.H / 2) * probe.scale + probe.H / 2 + probe.vy;
    await page.mouse.move(sx, sy, { steps: 6 });
    await sleep(500);
    await page.screenshot({
      path: join(OUT, `3-node-hover-pill-${scheme}.png`),
      clip: { x: Math.max(0, sx - 260), y: Math.max(0, sy - 90), width: 520, height: 220 },
    });
  }

  // ① 环开（图谱 tab 活跃）——5 页签环（plugins + alpha + bravo + 文档图谱 + 收起前态）
  const hdr = await page.evaluate(() => {
    const r = document.querySelector('#canvas-panel .canvas-header').getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  await page.mouse.move(hdr.x, hdr.y);
  await sleep(600);
  await page.screenshot({ path: join(OUT, `1-ring-open-graph-${scheme}.png`), clip: await panelBox(page) });

  // ② 换签链一轮回证：滚轮 + 中心点击 → 环保持开（修复面）
  await page.mouse.wheel(0, 100);
  await sleep(260);
  await page.mouse.click(hdr.x, hdr.y);
  await sleep(350);
  await page.mouse.wheel(0, 100);
  await sleep(260);
  await page.screenshot({ path: join(OUT, `2-post-switch-wheel-alive-${scheme}.png`), clip: await panelBox(page) });

  // ③b 面板页签胶囊（尺寸一致批）：插件/图谱激活态各一张顶带特写
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"]')?.click());
  await sleep(500);
  await page.screenshot({
    path: join(OUT, `5-panel-tab-capsule-${scheme}.png`),
    clip: await page.evaluate(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: 130 };
    }),
  });
  await page.click('#wiki-btn');
  await sleep(1600);
  await page.screenshot({
    path: join(OUT, `6-graph-tab-capsule-${scheme}.png`),
    clip: await page.evaluate(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: 130 };
    }),
  });

  // ④ 收起常态
  await page.mouse.move(hdr.x, hdr.y + 340);
  await sleep(500);
  await page.screenshot({
    path: join(OUT, `4-rest-${scheme}.png`),
    clip: await page.evaluate(() => {
      const p = document.getElementById('canvas-panel').getBoundingClientRect();
      return { x: Math.max(0, p.left), y: Math.max(0, p.top), width: p.width, height: 130 };
    }),
  });
  await ctx.close();
  console.log(`${scheme} leg done`);
}

await browser.close();
server.close();
console.log('shots done → ' + OUT);
