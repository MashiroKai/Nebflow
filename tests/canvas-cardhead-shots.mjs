// canvas-cardhead-shots.mjs — UI 批⑨ 卡头毛玻璃胶囊 + 手势 前后对照截图（作者令 2026-10-21）
//
// 产出（SHOTS_DIR）：
//   desktop-dark-1-capsule-normal.png   桌面胶囊常态（居中毛玻璃 pill + 右缘 deck fab）
//   desktop-dark-2-capsule-pressed.png  按压态（is-pressed scale .97 + brightness）
//   desktop-dark-3-under-capsule.png    内容穿底（长文滚动态，文字从胶囊底下穿过 + 裙边渐隐）
//   desktop-dark-4-deck-open.png        deck 开（胶囊与堆叠层并存）
//   w430-1-capsule.png / w375-1-capsule.png          手机胶囊常态（通栏变体）
//   w375-2-longpress-ref.png            长按触感 + 引用钮浮出
//   w375-3-under-capsule.png            手机内容穿底滚动态
//
// 运行：SHOTS_DIR=… node tests/canvas-cardhead-shots.mjs

import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync } from 'node:fs';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8167);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || resolve(HERE, '../evidence/canvas-cardhead-shots');
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

const TALL = '# bravo 长文穿底样本\n\n' + Array.from({ length: 160 }, (_, i) => `bravo 行 ${i + 1}：滚动时文字从胶囊底下穿过，下缘毛玻璃裙边渐隐透出。`).join('\n') + '\n';
const FILE_CONTENTS = new Map([
  ['/tmp/ch-shots/alpha.md', { content: '# alpha\n\nalpha 正文样本行。\n\n- 列表 A\n- 列表 B\n- 列表 C\n' }],
  ['/tmp/ch-shots/bravo.md', { content: TALL }],
]);

const browser = await chromium.launch();

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
        const rec = FILE_CONTENTS.get(m.path);
        ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: rec ? rec.content : '', itemType: 'markdown', fileName: m.path.split('/').pop(), size: 1 }));
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
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: { id: 'file:' + pp, itemType: 'markdown', title: pp.split('/').pop(), absPath: pp, pinned: true },
  }));
}, p);

/* ════════ 桌面 ════════ */
{
  const { ctx, page } = await boot();
  await openFile(page, '/tmp/ch-shots/alpha.md');
  await openFile(page, '/tmp/ch-shots/bravo.md');      // bravo 长文活动
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(600);

  await page.screenshot({ path: join(SHOTS_DIR, 'desktop-dark-1-capsule-normal.png') });

  // 按压态：按住胶囊（不松手时截图）
  const pt = await page.evaluate(() => {
    const hdr = document.querySelector('#canvas-panel .canvas-header');
    const r = hdr.getBoundingClientRect();
    return { x: r.left + r.width * 0.4, y: r.top + r.height / 2 };
  });
  await page.mouse.move(pt.x, pt.y);
  await page.mouse.down();
  await sleep(260);
  await page.screenshot({ path: join(SHOTS_DIR, 'desktop-dark-2-capsule-pressed.png') });
  await page.mouse.up();
  await sleep(200);

  // 穿底：滚一点内容（文字在胶囊底下 + 裙边渐隐可见）
  await page.evaluate(() => {
    const pane = document.querySelector('.canvas-tab-pane.active');
    const sc = [...pane.querySelectorAll('*')].filter((el) => el.scrollHeight > el.clientHeight + 4);
    if (sc.length) sc[0].scrollTop = 90;
  });
  await sleep(350);
  await page.screenshot({ path: join(SHOTS_DIR, 'desktop-dark-3-under-capsule.png') });

  // deck 开：胶囊与堆叠层并存
  await page.click('#deck-fab');
  await sleep(800);
  await page.screenshot({ path: join(SHOTS_DIR, 'desktop-dark-4-deck-open.png') });
  await page.keyboard.press('Escape');
  await sleep(300);
  await ctx.close();
}

/* ════════ 手机 430 / 375 ════════ */
for (const W of [430, 375]) {
  const { ctx, page } = await boot({ viewport: { width: W, height: 900 } });
  await openFile(page, '/tmp/ch-shots/alpha.md');
  await openFile(page, '/tmp/ch-shots/bravo.md');
  await page.evaluate(() => document.querySelector('.canvas-tab[data-tab-id="plugins"] .canvas-tab-close')?.click());
  await sleep(400);
  await page.evaluate(() => {
    const key = document.querySelector('.mh-dd[data-dd="right"] [data-face="canvas"]');
    if (key) key.click();
  });
  await sleep(900);
  const tag = `w${W}`;
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-1-capsule.png`) });

  if (W === 375) {
    // 长按触感 + 引用钮浮出
    const lp = await page.evaluate(() => {
      const head = document.querySelector('.mobile-card-head');
      const r = head.getBoundingClientRect();
      return { x: r.left + r.width * 0.35, y: r.top + r.height / 2 };
    });
    await page.dispatchEvent('.mobile-card-head', 'pointerdown', { pointerId: 9, pointerType: 'touch', isPrimary: true, button: 0, buttons: 1, clientX: lp.x, clientY: lp.y, bubbles: true });
    await sleep(750);
    await page.screenshot({ path: join(SHOTS_DIR, 'w375-2-longpress-ref.png') });
    await page.dispatchEvent('.mobile-card-head', 'pointerup', { pointerId: 9, pointerType: 'touch', isPrimary: true, button: 0, buttons: 0, clientX: lp.x, clientY: lp.y, bubbles: true });
    await sleep(300);
    // 收掉引用钮后穿底滚动帧
    await page.evaluate(() => {
      const btn = document.querySelector('.mobile-card-ref-btn');
      if (btn) btn.remove();
      const pane = document.querySelector('.canvas-tab-pane.active');
      const sc = [...pane.querySelectorAll('*')].filter((el) => el.scrollHeight > el.clientHeight + 4);
      if (sc.length) sc[0].scrollTop = 90;
    });
    await sleep(350);
    await page.screenshot({ path: join(SHOTS_DIR, 'w375-3-under-capsule.png') });
  }
  await ctx.close();
}

await browser.close();
server.close();
console.log(`⑨ shots done → ${SHOTS_DIR}`);
