// cardwin-content-shots.mjs — 独立窗口内容物收口批（2026-10-22）亮暗截图证据。
//
// 产出（evidence/20261022_cardwin-content-only/shots/）：
//   {light,dark}-1-cardwin-content.png      内容物独立窗真容（首帧即卡面，壳退役）
//   {light,dark}-2-panel-entry-blocked.png  面板页签：pop 钮隐藏 + 右键菜单禁用态项（带提示 title）
//   {light,dark}-3-nopath-fail-card.png     无 path 卡窗深链：即刻失败卡（非内容物目标收口面）
//
// 运行：node tests/cardwin-content-shots.mjs

import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const OUT = resolve(HERE, '../evidence/20261022_cardwin-content-only/shots');
mkdirSync(OUT, { recursive: true });
const PORT = Number(process.env.PORT || 8170);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ALPHA = '/tmp/cw-content-only/alpha.md';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const ROOT_ALLOW = new Set(['style.css', 'app.js', 'sw.js', 'favicon-32.png', 'favicon-16.png',
  'favicon.svg', 'favicon.ico', 'favicon-180.png', 'favicon-192.png', 'favicon-512.png']);
const ALLOW_PREFIXES = ['/js/', '/css/', '/vendor/', '/brand/', '/assets/'];

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  const p = url.pathname;
  const serveIndex = () => {
    try {
      res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-cache' });
      res.end(readFileSync(join(WEB_DIR, 'index.html')));
    } catch { res.writeHead(404); res.end(); }
  };
  if (p === '/' || p === '/index.html') { serveIndex(); return; }
  const serve = (rel) => {
    const file = join(WEB_DIR, rel);
    if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
    try {
      res.writeHead(200, { 'Content-Type': MIME[rel.slice(rel.lastIndexOf('.'))] || 'application/octet-stream' });
      res.end(readFileSync(file));
    } catch { res.writeHead(404); res.end(); }
  };
  if (ALLOW_PREFIXES.some((q) => p.startsWith(q))) { serve(p); return; }
  if (ROOT_ALLOW.has(p.slice(1))) { serve(p); return; }
  res.writeHead(404, { 'Content-Length': '0' });
  res.end();
});
server.on('upgrade', (_q, socket) => {
  try { socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); } catch { /* gone */ }
  socket.destroy();
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));
const browser = await chromium.launch();

async function bootCtx(colorScheme) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  await ctx.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(ctx);
  await ctx.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'pop.readFile') ws.send(JSON.stringify({ type: 'fileContent', path: m.path, absPath: m.path, content: '# 项目周报\n\n内容物收口后的独立窗真容：**加粗**、`code`、列表。\n\n1. 第一\n2. 第二\n', itemType: 'markdown', fileName: 'alpha.md', size: 80 }));
    });
  });
  return ctx;
}

for (const scheme of ['light', 'dark']) {
  /* 1 — 内容物独立窗真容（首帧即卡面） */
  {
    const ctx = await bootCtx(scheme);
    const page = await ctx.newPage();
    await page.goto(BASE + '/?card-window=1&path=' + encodeURIComponent(ALPHA) + '&title=alpha.md');
    await page.waitForSelector('body.card-window', { timeout: 15000 });
    let txt = '';
    for (let i = 0; i < 40 && !txt.includes('内容物收口后的独立窗真容'); i++) {
      await sleep(200);
      txt = await page.evaluate(() => document.querySelector('.canvas-tab-pane')?.textContent || '');
    }
    for (let i = 0; i < 25; i++) {
      if (await page.evaluate(() => !document.getElementById('nf-cardwin-boot'))) break;
      await sleep(200);
    }
    await sleep(600);
    await page.screenshot({ path: join(OUT, `${scheme}-1-cardwin-content.png`) });
    console.log(`${scheme}-1 content ok:`, txt.includes('内容物收口后的独立窗真容'), '| url:', page.url());
    await ctx.close();
  }

  /* 2 — 面板页签：入口隐藏 + 右键菜单禁用态项 */
  {
    const ctx = await bootCtx(scheme);
    const page = await ctx.newPage();
    await page.goto(BASE + '/');
    await page.waitForSelector('#activity-bar', { timeout: 15000 });
    await sleep(400);
    await page.evaluate(async () => { (await import('/js/plugins.js')).openPlugins(); });
    await page.waitForSelector('.canvas-tab-pane[data-tab-id="plugins"]', { timeout: 10000, state: 'attached' });
    await sleep(600);
    const popHidden = await page.evaluate(() => {
      const b = document.getElementById('canvas-pop-btn');
      return !!b && b.hidden && getComputedStyle(b).display === 'none';
    });
    await page.click('.canvas-header', { button: 'right' });
    await page.waitForSelector('.canvas-tab-context-menu', { timeout: 5000 });
    await sleep(250);
    await page.screenshot({ path: join(OUT, `${scheme}-2-panel-entry-blocked.png`) });
    const menuInfo = await page.evaluate(() => {
      const pop = [...document.querySelectorAll('.canvas-tab-context-menu button')]
        .find((b) => (b.textContent || '').includes('独立窗口打开'));
      return pop ? { disabled: pop.classList.contains('disabled'), title: pop.title } : null;
    });
    console.log(`${scheme}-2 panel blocked ok:`, popHidden, JSON.stringify(menuInfo));
    await ctx.close();
  }

  /* 3 — 无 path 卡窗深链：即刻失败卡 */
  {
    const ctx = await bootCtx(scheme);
    const page = await ctx.newPage();
    await page.goto(BASE + '/?card-window=1');
    await page.waitForSelector('body.card-window', { timeout: 15000 });
    let failed = false;
    for (let i = 0; i < 20 && !failed; i++) {
      await sleep(200);
      failed = await page.evaluate(() => document.getElementById('nf-cardwin-boot')?.getAttribute('data-state') === 'failed');
    }
    await sleep(300);
    await page.screenshot({ path: join(OUT, `${scheme}-3-nopath-fail-card.png`) });
    console.log(`${scheme}-3 nopath fail card ok:`, failed);
    await ctx.close();
  }
}

await browser.close();
server.close();
console.log('done →', OUT);
