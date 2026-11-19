import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { extname, join, resolve } from 'node:path';
import { installTicketMock } from './nf-ticket-mock.mjs';

const web = resolve('src/main/resources/web');
let server, base;
test.beforeAll(async () => {
  server = createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    const file = join(web, pathname === '/' ? 'index.html' : pathname);
    if (!file.startsWith(web + '/')) { res.writeHead(403).end(); return; }
    try {
      const mime = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.json':'application/json' };
      const body = readFileSync(file);
      res.writeHead(200, {'Content-Type': mime[extname(file)] || 'application/octet-stream'}).end(body);
    } catch { res.writeHead(404).end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.afterAll(async () => { await new Promise(resolve => server.close(resolve)); });

test('lone window silently takes the seat; a refused write shows the real save-failure copy and no takeover surface', async ({ browser }, info) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await context.addInitScript(() => { localStorage.setItem('nebflow_token', 'test'); localStorage.setItem('neblink_token', 'test'); });
  let writes = 0;
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/canvas-writer/claim') return route.fulfill({json:{role:'writer',epoch:1}});
    if (url.pathname === '/api/canvas-writer') return route.fulfill({json:{role:'reader'}});
    if (url.pathname === '/api/canvas-tabs' && route.request().method() === 'PUT') { writes++; return route.fulfill({status:409,json:{error:'stale'}}); }
    if (url.pathname === '/api/canvas-tabs') return route.fulfill({json:{v:2,tabs:[]}});
    return route.fulfill({json:{}});
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({type:'configData',configured:true,onboarding:'done',models:[],defaults:{}}));
    ws.send(JSON.stringify({type:'sessionList',sessions:[{id:'test',agentName:'Nebula',title:'T',updatedAt:Date.now()}],activeId:'test',folders:[]}));
  });
  try {
    await page.goto(base, {waitUntil:'domcontentloaded'});
    await page.waitForSelector('#activity-bar', {state:'attached'});
    // Prepare a visible local browsing scenario against the actual Canvas module.
    await page.evaluate(async () => { const m = await import('/js/canvas.js'); m.openTab('reader-a','Reader A',{pinned:true}); m.openTab('reader-b','Reader B',{pinned:true}); });
    await page.screenshot({path: info.outputPath('reader.png')});
    await expect(page.locator('[data-tab-id="reader-b"].canvas-tab-pane')).toHaveCount(1);
    await expect(page.locator('[data-tab-id="reader-a"].canvas-tab-pane')).toHaveCount(1);
    // 2026-11-18 作者令：控制台多开只做检测，无卡片、无第二种提示。孤窗会**静默**
    // 申请写者席位并写穿；席位被服务端拒授（真多开）时才出真实「保存失败」文案。
    // 旧的「只读/取得控制权」提示文案已随状态机退役，这里钉它们绝不再出现。
    await expect(page.getByText('Canvas 当前为只读，请先取得控制权。')).toHaveCount(0);
    await expect(page.getByText('Canvas 只读：当前标签切换仅在本地显示，不会同步。')).toHaveCount(0);
    await expect(page.getByText('取回控制权')).toHaveCount(0);
    await expect(page.locator('#takeover-box')).toHaveCount(0);
    // 孤窗即位后写入真的送达服务端（桩回 409）⇒ 席位申请发生过，局地不再静默丢弃。
    // 注意写入走 debounce + 先申席位，必须轮询等待，同步断言会抢在首写之前。
    await expect.poll(() => writes, { timeout: 10000 }).toBeGreaterThan(0);
    await expect(page.getByText('Canvas 保存失败，本地变更已保留。')).toHaveCount(1);
    expect(errors).toEqual([]);
  } finally { await context.close(); }
});
