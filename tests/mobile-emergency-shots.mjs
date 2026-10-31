// mobile-emergency-shots.mjs — 手机端 UI 急诊批巡检（作者 10-31 20:14 报
// 「前端手机端 UI 现在很不正常」，17:58 重启点亮今天十条攒批后手机端未再验证）。
//
// 五个面 × 390×844 × 亮/暗双主题整视口截图 + 几何探针（横向溢出/元素盒）：
//   1. 对话根（消息流含 <output> 摘除态 + 归因行 + input + dock）
//   2. 消息页（会话列表 + 展开式搜索态）
//   3. 联系人页（好友行 + 展开式搜索态）
//   4. canvas 页
//   5. 工作台 + meFace 层级（me / me-user / me-avatar / me-bind）
//
// 用法：node tests/mobile-emergency-shots.mjs [outDir]
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.argv[3] ? process.argv[3] : join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261031_mobile-emergency-inspect');
mkdirSync(OUT, { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const SID = 'emergency-inspect';
const NOW = Date.now();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 历史夹具：AI/用户交替，含 output 块消息（摘除态=只渲染块内）、归因行/工具行语境。
const HISTORY = Array.from({ length: 12 }, (_, i) => {
  const ts = NOW - 600_000 + i * 10_000;
  if (i === 3) {
    return {
      type: 'ai',
      text: '框外思考叙述一段，不该在摘除态出现。<output>output 块结论：手机端急诊巡检消息流渲染正常，块外文字已摘除。</output>框外尾巴也不该出现。',
      timestamp: ts,
    };
  }
  return i % 2 === 0
    ? { type: 'ai', text: `急诊巡检对照消息 ${i + 1}：消息流排版、气泡边距、归因行折行只断「·」。`, timestamp: ts }
    : { type: 'user', text: `收到，第 ${i + 1} 项继续。`, timestamp: ts };
});

const WIRE_FRIEND = { userId: 'u-f', username: 'buddy7', display_name: '老友', avatar: '', remark: null };
const WIRE_FRIEND2 = { userId: 'u-g', username: 'kai8', display_name: '凯', avatar: '', remark: '同事小凯' };

const browser = await chromium.launch();
const consoleErrors = [];

async function makePage(colorScheme) {
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 }, colorScheme, deviceScaleFactor: 2, hasTouch: true,
  });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${colorScheme}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${colorScheme}] pageerror: ${e.message}`));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
    if (p === '/api/friends') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [WIRE_FRIEND, WIRE_FRIEND2], incoming: [], outgoing: [] }) });
    if (p === '/api/conversations') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ conversationId: 'c-1', friend: WIRE_FRIEND, lastMessage: { body: '晚上一起吃饭吗', timestamp: NOW - 3000 }, unreadCount: 2 }, { conversationId: 'c-2', friend: WIRE_FRIEND2, lastMessage: { body: '文档我传到 canvas 了', timestamp: NOW - 40000 }, unreadCount: 0 }]) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '笨笨的会话', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getMemory') ws.send(JSON.stringify({ type: 'memoryData', scope: m.scope, content: '' }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(1000);
  return { ctx, page };
}

/** 几何探针：横向溢出 + 关键元素盒 + live 页面壳盒。 */
async function probe(page) {
  return page.evaluate(() => {
    const de = document.documentElement;
    const vw = window.innerWidth;
    const wide = [];
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect();
      if (r.width > vw + 1) {
        const cn = (el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className || '').toString();
        wide.push(`${el.tagName}.${cn.split(' ').slice(0, 3).join('.')} w=${Math.round(r.width)}`);
      }
    }
    const boxes = {};
    const grab = (sel, key) => {
      const el = document.querySelector(sel);
      if (el) { const r = el.getBoundingClientRect(); boxes[key] = { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; }
    };
    grab('#mobile-dock', 'dock');
    grab('#input-area', 'input');
    grab('#mobile-pages .mobile-page.live', 'page');
    const live = document.querySelector('#mobile-pages .mobile-page.live');
    if (live) {
      const strip = live.querySelector('.mf-strip');
      if (strip) { const r = strip.getBoundingClientRect(); boxes.strip = { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; }
    }
    return { scrollW: de.scrollWidth, clientW: de.clientWidth, vw, wideCount: wide.length, wide: wide.slice(0, 8), boxes };
  });
}

async function shot(page, name, scheme) {
  const p = await probe(page);
  console.log(`probe ${name}/${scheme}: ${JSON.stringify(p)}`);
  await page.screenshot({ path: join(OUT, `${name}-${scheme}.png`), fullPage: false });
}

for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await makePage(scheme);
  // 1. 对话根（消息流 + output 摘除态 + input + dock）
  await shot(page, '1-chat-root', scheme);
  // 2. 消息页
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await sleep(600);
  await shot(page, '2-messages', scheme);
  // 2b. 消息页展开式搜索态
  const fab = page.locator('.mobile-page[data-page="messages"] .mh-fab-search');
  if (await fab.count()) { await fab.click(); await sleep(500); await shot(page, '2b-messages-search', scheme); await fab.click(); await sleep(300); }
  // 3. 联系人页
  await page.locator('#mobile-dock .dock-key[data-dock="contacts"]').click();
  await sleep(600);
  await shot(page, '3-contacts', scheme);
  const cfab = page.locator('.mobile-page[data-page="contacts"] .mh-fab-search');
  if (await cfab.count()) { await cfab.click(); await sleep(500); await shot(page, '3b-contacts-search', scheme); await cfab.click(); await sleep(300); }
  // 4. canvas 页
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(800);
  await shot(page, '4-canvas', scheme);
  // 5. 工作台 + meFace 层级
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await sleep(600);
  await shot(page, '5-workbench', scheme);
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await sleep(500);
  await shot(page, '5b-me', scheme);
  await page.locator('.mobile-page[data-page="me"] .mf-hub-row[data-me-page="me-user"]').click();
  await sleep(500);
  await shot(page, '5c-me-user', scheme);
  await page.locator('.mobile-page[data-page="me-user"] .mobile-back-btn').click();
  await sleep(300);
  await page.locator('.mobile-page[data-page="me"] .mf-hub-row[data-me-page="me-avatar"]').click();
  await sleep(500);
  await shot(page, '5d-me-avatar', scheme);
  await page.locator('.mobile-page[data-page="me-avatar"] .mobile-back-btn').click();
  await sleep(300);
  await page.locator('.mobile-page[data-page="me"] .mf-hub-row[data-me-page="me-bind"]').click();
  await sleep(500);
  await shot(page, '5e-me-bind', scheme);
  await ctx.close();
}

console.log(`console errors: ${consoleErrors.length}`);
consoleErrors.slice(0, 10).forEach((e) => console.log(`  ${e}`));
console.log(`shots → ${OUT}`);
await browser.close();
