// phone-access-removal-shots.mjs — 「手机接入」入口撤除批截图取证（作者令
// 2026-10-31 22:55）：手机端联系人页顶无入口行 + 桌面端同入口原样不动。
//
// 用法：node /tmp/phone-access-removal-shots.mjs [outDir]
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261031_phone-access-entry-removal');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const SID = 'phone-access-removal';
const NOW = Date.now();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const WIRE_FRIEND = { userId: 'u-f', username: 'buddy7', display_name: '老友', avatar: '', remark: null };

const browser = await chromium.launch();
const consoleErrors = [];

async function makePage({ viewport, hasTouch = false }) {
  const ctx = await browser.newContext({
    viewport, colorScheme: 'dark', deviceScaleFactor: 2, hasTouch,
  });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: '取证', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
    if (p === '/api/friends') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [WIRE_FRIEND], incoming: [], outgoing: [] }) });
    if (p === '/api/conversations') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ conversationId: 'c-1', friend: WIRE_FRIEND, lastMessage: { body: '晚上一起吃饭吗', timestamp: NOW - 3000 }, unreadCount: 2 }]) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '取证会话', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(1200);
  return { ctx, page };
}

// ── 手机端：联系人页顶无「手机接入」入口行 ──────────────────────────
{
  const { ctx, page } = await makePage({ viewport: { width: 390, height: 844 }, hasTouch: true });
  const layer = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  await page.locator('#mobile-dock .dock-key[data-dock="contacts"]').click();
  await sleep(800);
  const probe = await page.evaluate(() => {
    const body = document.querySelector('.mobile-page[data-page="contacts"] .mobile-page-body');
    const firstRows = body ? [...body.children].slice(0, 4).map((el) => {
      const label = el.querySelector?.('.fm-nf-label');
      return el.className.toString().split(' ')[0] + (label ? `(${label.textContent})` : '');
    }) : [];
    return {
      entryRows: document.querySelectorAll('.mf-social-entry').length,
      socialPages: document.querySelectorAll('.mobile-page[data-page="social"]').length,
      contactsBodyChildren: body ? body.children.length : -1,
      firstRows,
      activityBarDisplay: getComputedStyle(document.getElementById('activity-bar')).display,
      socialOverlayOn: document.getElementById('social-overlay')?.classList.contains('on') ?? null,
    };
  });
  console.log(`MOBILE mobile-layer=${layer} probe=${JSON.stringify(probe)}`);
  await page.screenshot({ path: join(OUT, 'mobile-contacts-no-entry.png') });
  await ctx.close();
}

// ── 桌面端：同入口（#social-btn → #social-overlay）原样不动 ─────────
{
  const { ctx, page } = await makePage({ viewport: { width: 1440, height: 900 } });
  const layer = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
  const btnBox = await page.evaluate(() => {
    const b = document.getElementById('social-btn');
    if (!b) return null;
    const r = b.getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, visible: getComputedStyle(b).display !== 'none' && r.width > 0 };
  });
  console.log(`DESKTOP mobile-layer=${layer} social-btn=${JSON.stringify(btnBox)}`);
  await page.screenshot({ path: join(OUT, 'desktop-root-social-btn.png') });
  await page.locator('#social-btn').click();
  await sleep(600);
  const overlayOn = await page.evaluate(() => document.getElementById('social-overlay')?.classList.contains('on'));
  console.log(`DESKTOP social-overlay.on=${overlayOn}`);
  await page.screenshot({ path: join(OUT, 'desktop-social-overlay-open.png') });
  await ctx.close();
}

console.log(`console errors: ${consoleErrors.length}`);
consoleErrors.slice(0, 10).forEach((e) => console.log(`  ${e}`));
console.log(`shots → ${OUT}`);
await browser.close();
