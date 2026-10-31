// mobile-topbar-shots.mjs — 手机端工作台顶栏批双主题截图（作者令 2026-10-31）：
//   工作台新顶（头像入口 + 无顶栏标题）/ 个人主页 / 个人主页层级子页（用户名）
//   × dark/light。产物 = <outDir>（由参数指定）。
//
// 用法：node tests/mobile-topbar-shots.mjs <outDir>
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || '/tmp/mobile-topbar-shots';
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'topbar-shots-session';

const browser = await chromium.launch();

async function makePage(colorScheme) {
  const page = await (await browser.newContext({
    viewport: { width: 375, height: 812 }, colorScheme, deviceScaleFactor: 2, hasTouch: true,
  })).newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '笨笨的会话', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getMemory') ws.send(JSON.stringify({ type: 'memoryData', scope: m.scope, content: '' }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(800);
  return page;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

for (const scheme of ['dark', 'light']) {
  const m = await makePage(scheme);
  // 工作台新顶（头像入口 + 顶栏标题退役，内容上移）。
  await m.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await sleep(500);
  await m.screenshot({ path: join(OUT, `workbench-top-${scheme}.png`) });
  // 个人主页（头像点击进）。
  await m.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await sleep(500);
  await m.screenshot({ path: join(OUT, `me-home-${scheme}.png`) });
  // 层级子页（用户名 → 铺满屏配置层，左上「<」返回）。
  await m.locator('.mobile-page[data-page="me"] .mf-hub-row[data-me-page="me-user"]').click();
  await sleep(500);
  await m.screenshot({ path: join(OUT, `me-username-${scheme}.png`) });
  // 设置页（头像位/设备区已撤，账号读数行保留）。
  await m.locator('.mobile-page[data-page="me-user"] .mobile-back-btn').click();
  await sleep(300);
  await m.locator('.mobile-page[data-page="me"] .mobile-back-btn').click();
  await sleep(300);
  await m.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await sleep(600);
  await m.screenshot({ path: join(OUT, `settings-no-avatar-${scheme}.png`) });
  await m.close();
}
await browser.close();
console.log(`mobile-topbar shots → ${OUT}`);
