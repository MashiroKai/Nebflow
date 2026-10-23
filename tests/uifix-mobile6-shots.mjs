// uifix-mobile6-shots.mjs — 手机追加六件交付截图（375/430 × 六件逐一，前后对照）。
// 用法：node tests/uifix-mobile6-shots.mjs <before|after>
//   before = git stash 后的 HEAD 树（五件批止），after = 六件工作树。
// 产物 = evidence/20261021_uifix-quad/shots/mobile6-<件>-<视口>-<相位>.png
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const PHASE = process.argv[2] || 'after';
const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261021_uifix-quad', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'mobile6-session-1';

const browser = await chromium.launch();

async function boot(width) {
  const page = await (await browser.newContext({
    viewport: { width, height: 812 }, colorScheme: 'dark', deviceScaleFactor: 2, hasTouch: true,
  })).newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p === '/api/projects') {
      const now = Date.now();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: [
        { name: 'nebflow 主仓', workspace: '/Users/kaiyu/nebflow', description: '核心调度与 Flow Map', createdAt: now - 86400e3 * 30 },
        { name: 'stand 移动端', workspace: '/Users/kaiyu/stand-mobile', description: '手机层 v3 视觉落地', createdAt: now - 86400e3 * 12 },
        { name: '证据链巡检', workspace: '/Users/kaiyu/evidence', description: '截图与规格回归', createdAt: now - 86400e3 * 3 },
      ] }) });
    }
    if (/^\/api\/projects\/[^/]+/.test(p)) {
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"nf"}' });
    }
    if (p.includes('/agents/Nebula/agent.json')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root 的会话', agentName: 'Nebula' }], folders: [], activeId: SID }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'getProjects' || m.type === 'listProjects') ws.send(JSON.stringify({ type: 'projects', projects: [] }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
  await page.waitForTimeout(1200);
  return page;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

for (const width of [375, 430]) {
  const page = await boot(width);
  const tag = `${width}`;

  // 件① 会话面顶部：条带压 #header 之下，看毛玻璃渐隐裙边（前=硬切）。
  await page.evaluate(() => {
    const main = document.getElementById('main');
    const strip = document.createElement('div');
    strip.id = 'm6-strip';
    strip.style.cssText = 'position:absolute;z-index:5;left:12px;right:12px;top:0;height:320px;' +
      'background:repeating-linear-gradient(90deg,#0c0e14 0 10px,#f2f4f8 10px 20px);border-radius:10px;';
    main.appendChild(strip);
  });
  await sleep(500);
  await page.screenshot({ path: join(OUT, `mobile6-1-topglass-${tag}-${PHASE}.png`), clip: { x: 0, y: 0, width, height: 200 } });
  await page.evaluate(() => document.getElementById('m6-strip')?.remove());

  // 件② 左列竖排药丸（徽标挂点/间距/底板长度）。
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await sleep(700);
  await page.screenshot({ path: join(OUT, `mobile6-2-leftcol-${tag}-${PHASE}.png`) });
  await page.locator('.mh-dd[data-dd="left"] .mh-dd-hd').click();
  await sleep(450);

  // 件③ 右列触发钮 + 面板（激活态/图标重复）。
  await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  await sleep(700);
  await page.screenshot({ path: join(OUT, `mobile6-3-rightcol-${tag}-${PHASE}.png`) });

  // 件④ 设置页：默认零自动弹（前=自动停靠常显）。
  await page.locator('.mh-dd[data-dd="right"] [data-face="settings"]').click();
  await page.waitForSelector('.mobile-page[data-page="settings"].live', { timeout: 8000 });
  await sleep(900);
  await page.screenshot({ path: join(OUT, `mobile6-4-settings-${tag}-${PHASE}.png`) });
  // 面板合并批（2026-10-23 ④）：设置页 profile 入口行退役 —— after 树里
  // 入口行不应存在（残留即回归）。
  if (await page.locator('.profile-entry').count()) {
    console.warn('[mobile6-shots] profile-entry 残留（面板合并批 ④ 违约）');
  }
  await page.locator('.mobile-page[data-page="settings"] .mobile-page-header .mobile-back-btn').click();
  await sleep(600);

  // 件⑤ token 用量页：单返回入口（前=双层返回）。
  await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  await sleep(500);
  await page.locator('.mh-dd[data-dd="right"] [data-face="usage"]').click();
  await page.waitForSelector('.mobile-page[data-page="usage"].live', { timeout: 8000 });
  await sleep(1100);
  await page.screenshot({ path: join(OUT, `mobile6-5-usage-${tag}-${PHASE}.png`) });
  await page.locator('.mobile-page[data-page="usage"] .mobile-page-header .mobile-back-btn').first().click();
  await sleep(600);

  // 件⑥ 项目面板：垂直紧凑卡片列表（前=整屏一张/大片空白）。
  await page.locator('.mh-dd[data-dd="right"] .mh-dd-hd').click();
  await sleep(500);
  await page.locator('.mh-dd[data-dd="right"] [data-face="projects"]').click();
  await page.waitForSelector('.mobile-page[data-page="projects"].live', { timeout: 8000 });
  await sleep(1100);
  await page.screenshot({ path: join(OUT, `mobile6-6-projects-${tag}-${PHASE}.png`) });

  await page.context().close();
}
await browser.close();
console.log(`mobile6 shots done (${PHASE})`);
