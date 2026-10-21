// canvas-cardwin-shots.mjs — UI 批⑧ 单卡独立窗口 前后对照截图（作者令 2026-10-21）
//
// 产出（SHOTS_DIR）：
//   desktop-dark-1-toggle-normal.png    标题头「独立」形（未独立态）
//   desktop-dark-2-toggle-independent.png 标题头「合并」形高亮 + 卡面「独立中」浮标（独立态）
//   desktop-dark-3-cardwin.png          独立窗本尊（?card-window=1 真容单卡）
//   desktop-dark-4-merged.png           合并后原位恢复（toggle 回「独立」形 + 内容原样）
//   w430 / w375 mobile card head（.mobile-card-pop 在位）+ popped 新标签页
//
// 运行：SHOTS_DIR=… node tests/canvas-cardwin-shots.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8164);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, '../evidence/canvas-cardwin-shots');
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

const FILE_CONTENTS = new Map([
  ['/tmp/cw-shots/alpha.md', { content: '# alpha\n\nalpha 正文样本行。\n\n- 列表 A\n- 列表 B\n', itemType: 'markdown' }],
  ['/tmp/cw-shots/bravo.md', { content: '# bravo 独立窗样本\n\n这一行用于独立窗真容对照：**加粗**、`code`、列表。\n\n1. 第一\n2. 第二\n', itemType: 'markdown' }],
]);

async function boot({ viewport = { width: 1440, height: 900 }, colorScheme = 'dark' } = {}) {
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
        const rec = FILE_CONTENTS.get(m.path) || { content: '', itemType: 'markdown' };
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: rec.content, itemType: rec.itemType, fileName: m.path.split('/').pop(), size: rec.content.length }));
      }
    });
  });
  const page = await ctx.newPage();
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('body.mobile-layer, #activity-bar', { timeout: 15000 });
  await sleep(500);
  return { ctx, page };
}

const openFile = (page, p) => page.evaluate((pp) => {
  const rec = { t: pp.split('/').pop() };
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + pp, itemType: 'markdown', title: rec.t, content: FILE_TEXT(pp), absPath: pp, pinned: true },
  }));
  function FILE_TEXT() { return null; }
}, p).then(() => page.evaluate((pp) => {
  // 直灌内容（与 mock 表同文）
  const rec = FILE_CONTENTS_GET(pp);
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + pp, itemType: 'markdown', title: pp.split('/').pop(), content: rec.content, absPath: pp, pinned: true },
  }));
  function FILE_CONTENTS_GET() { return null; }
}, p));

const browser = await chromium.launch();

/* 桌面 dark：toggle 两形态 + 独立窗 + 合并恢复 */
{
  const { ctx, page } = await boot();
  for (const p of FILE_CONTENTS.keys()) {
    const rec = FILE_CONTENTS.get(p);
    await page.evaluate(({ p, c, t }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + p, itemType: 'markdown', title: t, content: c, absPath: p, pinned: true },
      }));
    }, { p, c: rec.content, t: p.split('/').pop() });
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${p}"]`, { timeout: 10000, state: 'attached' });
  }
  await sleep(600);
  const hdr = await page.evaluate(() => {
    const p = document.getElementById('canvas-panel');
    const b = p.getBoundingClientRect();
    return { x: Math.max(0, b.x), y: Math.max(0, b.y), width: Math.min(900, b.width), height: 64 };
  });
  await page.screenshot({ path: join(SHOTS_DIR, 'desktop-dark-1-toggle-normal.png'), clip: { x: hdr.x, y: hdr.y, width: hdr.width, height: hdr.height } });
  // 弹出 bravo（活动卡）
  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await page.click('#canvas-pop-btn');
  const popup = await popupP;
  await popup.waitForLoadState('domcontentloaded');
  await popup.waitForSelector('body.card-window', { timeout: 15000 });
  await sleep(1800);
  await page.screenshot({ path: join(SHOTS_DIR, 'desktop-dark-2-toggle-independent.png'), clip: { x: hdr.x, y: hdr.y, width: hdr.width, height: hdr.height } });
  await popup.screenshot({ path: join(SHOTS_DIR, 'desktop-dark-3-cardwin.png') });
  // 合并回主窗
  await page.click('#canvas-pop-btn');
  await sleep(1000);
  await page.screenshot({ path: join(SHOTS_DIR, 'desktop-dark-4-merged.png'), clip: { x: hdr.x, y: Math.max(0, hdr.y - 10), width: hdr.width, height: 380 } });
  console.log('[desktop] shots done, popup closed =', popup.isClosed());
  await ctx.close();
}

/* 430 / 375：手机卡片头 toggle（.mobile-card-pop）+ 弹出新标签页 */
for (const [tag, vp] of [['w430', { width: 430, height: 932 }], ['w375', { width: 375, height: 812 }]]) {
  const { ctx, page } = await boot({ viewport: vp, colorScheme: 'dark' });
  for (const p of FILE_CONTENTS.keys()) {
    const rec = FILE_CONTENTS.get(p);
    await page.evaluate(({ p, c, t }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + p, itemType: 'markdown', title: t, content: c, absPath: p, pinned: true },
      }));
    }, { p, c: rec.content, t: p.split('/').pop() });
    await sleep(200);
  }
  await page.evaluate(() => {
    const key = document.querySelector('.mh-dd[data-dd="right"] [data-face="canvas"]');
    if (key) key.click();
  });
  await sleep(1000);
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-1-cardhead-normal.png`) });
  const popupP = ctx.waitForEvent('page', { timeout: 8000 });
  await page.click('.mobile-card-pop');
  const popup = await popupP;
  await popup.waitForLoadState('domcontentloaded');
  await sleep(1800);
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-2-cardhead-independent.png`) });
  await popup.screenshot({ path: join(SHOTS_DIR, `${tag}-3-newtab-cardwin.png`) });
  await page.click('.mobile-card-pop');
  await sleep(900);
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-4-cardhead-merged.png`) });
  console.log(`[${tag}] popup closed =`, popup.isClosed());
  await ctx.close();
}

await browser.close();
server.close();
console.log('cardwin shots →', SHOTS_DIR);
