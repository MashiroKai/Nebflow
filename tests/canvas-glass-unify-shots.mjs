// canvas-glass-unify-shots.mjs — canvas 三件 UI/UX 修（作者令 2026-10-28）前后对照截图
//
// 件一 独立窗口胶囊行居中 / 件二 header 行排他 / 件三 玻璃模板统一。
// 产出（SHOTS_DIR，暗色两套视口：面板形态 + 独立窗口形态 + 门户视口宽度）：
//   <vp>-1-panel-md.png            面板形态：md 页签全幅（胶囊+堆叠钮+圆钮簇）
//   <vp>-2-find-open-band.png      开查找态：header 行 + 行下查找条（件二排他）
//   <vp>-3-cluster-html.png        html 页签底右簇（source/ref/find/缩放条 件三同材）
//   <vp>-4-independent-band.png    独立中态：header 行（角标+堆叠钮同排不叠）
//   cardwin-<vp>.png               独立窗口形态：胶囊行水平居中（件一）
//
// 运行：WEB_DIR=<tree> SHOTS_DIR=<dir> node tests/canvas-glass-unify-shots.mjs
// （WEB_DIR 指向改造前树即可产出修前对照；缺省 = 当前工作树）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, process.env.WEB_DIR || '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8172);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, '../evidence/canvas-glass-unify');
mkdirSync(SHOTS_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const MD_PATH = '/tmp/cgu-shots/glass-unify-sample.md';
const MD_CONTENT = '# glass-unify sample\n\n' +
  '对照样张正文：**加粗**、`code`、列表与足够行数让阅读面可滚。\n\n' +
  Array.from({ length: 24 }, (_, i) => `第 ${i + 1} 段：毛玻璃控件统一对照样本行 —— 胶囊/堆叠钮/圆钮/查找条/缩放条。`).join('\n\n') + '\n';

const HTML_FIXTURE = resolve(HERE, 'fixtures/canvas-html-zoom/poster.html');

async function boot({ viewport, colorScheme = 'dark' }) {
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
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: MD_CONTENT, itemType: 'markdown', fileName: m.path.split('/').pop(), size: MD_CONTENT.length }));
      }
    });
  });
  const page = await ctx.newPage();
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(600);
  return { ctx, page };
}

async function openMd(page) {
  await page.evaluate(({ p, c }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:' + p, itemType: 'markdown', title: p.split('/').pop(), content: c, absPath: p, pinned: true },
    }));
  }, { p: MD_PATH, c: MD_CONTENT });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${MD_PATH}"] .canvas-md-viewer`, { timeout: 10000 });
  await sleep(400);
}

async function openHtml(page) {
  const content = readFileSync(HTML_FIXTURE, 'utf8');
  await page.evaluate(({ content }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: 'file:/tmp/cgu-shots/poster.html', itemType: 'html', title: 'poster.html', content, absPath: '/tmp/cgu-shots/poster.html', pinned: true },
    }));
  }, { content });
  await page.waitForSelector('.canvas-zoom-bar', { timeout: 10000 });
  await sleep(500);
}

const panelBox = (page) => page.evaluate(() => {
  const b = document.getElementById('canvas-panel').getBoundingClientRect();
  return { x: Math.max(0, b.x), y: Math.max(0, b.y), width: b.width, height: b.height };
});

const browser = await chromium.launch();

for (const [tag, vp] of [['desktop', { width: 1440, height: 900 }], ['portal', { width: 1024, height: 768 }]]) {
  // ── 面板形态腿 ──
  {
    const { ctx, page } = await boot({ viewport: vp });
    await openMd(page);
    await page.screenshot({ path: join(SHOTS_DIR, `${tag}-1-panel-md.png`), clip: await panelBox(page) });
    // 开查找态（件二：查找条在 header 行下方）
    await page.click('.canvas-find-toggle');
    await page.waitForSelector('.nf-findbar', { timeout: 5000 });
    await sleep(350);
    const pb = await panelBox(page);
    await page.screenshot({ path: join(SHOTS_DIR, `${tag}-2-find-open-band.png`), clip: { x: pb.x, y: pb.y, width: pb.width, height: 150 } });
    await page.keyboard.press('Escape');
    await sleep(250);
    // html 簇（件三：缩放条与圆钮同材）
    await openHtml(page);
    const pb2 = await panelBox(page);
    await page.screenshot({ path: join(SHOTS_DIR, `${tag}-3-cluster-html.png`), clip: { x: pb2.x, y: pb2.y + pb2.height - 120, width: pb2.width, height: 120 } });
    // 独立中态（件二：角标 + 堆叠钮同排）
    const popupP = ctx.waitForEvent('page', { timeout: 8000 });
    await page.click('#canvas-pop-btn');
    const popup = await popupP;
    await popup.waitForLoadState('domcontentloaded');
    await popup.waitForSelector('body.card-window', { timeout: 15000 });
    await sleep(1600);
    await page.screenshot({ path: join(SHOTS_DIR, `${tag}-4-independent-band.png`), clip: { x: pb2.x, y: pb2.y, width: pb2.width, height: 150 } });
    await popup.screenshot({ path: join(SHOTS_DIR, `cardwin-${tag}.png`) });
    console.log(`[${tag}] shots done, popup closed =`, popup.isClosed());
    await ctx.close();
  }
}

await browser.close();
server.close();
console.log('glass-unify shots →', SHOTS_DIR);
