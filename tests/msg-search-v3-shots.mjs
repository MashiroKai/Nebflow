// msg-search-v3-shots.mjs — visual self-review screenshots (light/dark/375px).
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'shot-session';
const NOW = Date.now();
const DAY = 86400000;
const HISTORY = [
  { type: 'user', text: '今晚把批3的迁移 spec 补完再睡', timestamp: NOW - 3600000 },
  { type: 'ai', text: '好的，迁移逻辑包含 localStorage 11 键与 cookie 同源处理。', timestamp: NOW - 3500000 },
  { type: 'user', text: '看下这张截图的布局问题', timestamp: NOW - DAY, attachments: [{ type: 'image', name: 'layout-bug.png' }] },
  { type: 'ai', text: '截图里弹层面板缺少毛玻璃质感，需要加 backdrop-filter。', timestamp: NOW - DAY + 60000 },
  { type: 'tool', label: 'Card(weekly-report)', summary: 'rendered HTML card', content: '<html>…</html>' },
];

const browser = await chromium.launch();

async function shot(name, { width, height, colorScheme, openCal }) {
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'shot-token'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Shots', agentName: 'Nebula' }], activeId: SID }) });
    if (p.startsWith(`/api/sessions/${SID}/history`)) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: HISTORY, total: HISTORY.length, sessionId: SID }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'Shots', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.click('#search-btn');
  await page.waitForSelector('#search-results .search-result', { timeout: 3000 });
  await page.waitForTimeout(400);
  if (openCal) {
    await page.click('#search-tab-date');
    await page.waitForSelector('#search-date-popover:not([hidden])');
    await page.waitForTimeout(300);
  }
  await page.screenshot({ path: `/tmp/msgsearch-v3-${name}.png` });
  console.log(`saved /tmp/msgsearch-v3-${name}.png`);

  if (width === 375) {
    // B12: popover bounds + no new horizontal scroll
    const base = await page.evaluate(() => document.scrollingElement.scrollWidth);
    const rect = await page.locator('#search-date-popover').boundingBox();
    console.log(`375px: scrollWidth=${base} popover left=${rect?.x} right=${rect ? rect.x + rect.width : 0}`);
  }
  await ctx.close();
}

await shot('light', { width: 1440, height: 900, colorScheme: 'light' });
await shot('dark', { width: 1440, height: 900, colorScheme: 'dark' });
await shot('light-cal', { width: 1440, height: 900, colorScheme: 'light', openCal: true });
await shot('dark-cal', { width: 1440, height: 900, colorScheme: 'dark', openCal: true });
await shot('375-cal', { width: 375, height: 812, colorScheme: 'light', openCal: true });
await browser.close();
