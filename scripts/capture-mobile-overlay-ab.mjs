// capture-mobile-overlay-ab.mjs — 手机端浮层三件批：同机位 before/after 视频 + 静帧取证。
//
// 用法：
//   node scripts/capture-mobile-overlay-ab.mjs <label> <webRoot> <outDir>
// 例：
//   node scripts/capture-mobile-overlay-ab.mjs before "/Users/kaiyu/Claude code/Stand/src/main/resources/web" /Users/kaiyu/stand-evidence/mobile-overlay-glass-20261118
//   node scripts/capture-mobile-overlay-ab.mjs after  "$PWD/src/main/resources/web"                          /Users/kaiyu/stand-evidence/mobile-overlay-glass-20261118
//
// 视口：375×812（作者真实形态）与 1280×800（桌面回归面）。
// 同一份 harness（静态文件面 = 真实 web 树；WS 帧桩；零后端、零 sbt、非 6138/8080/8097）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, extname, normalize, basename } from 'node:path';

const [label = 'x', WEB = process.cwd(), OUT = process.cwd()] = process.argv.slice(2);
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'overlay-evidence-session';
mkdirSync(OUT, { recursive: true });

async function newCtx(browser, size, record) {
  const ctx = await browser.newContext({
    viewport: size, deviceScaleFactor: 2, hasTouch: size.width < 768, colorScheme: 'dark',
    ...(record ? { recordVideo: { dir: join(OUT, 'raw'), size } } : {}),
  });
  const page = await ctx.newPage();
  await page.addInitScript(({ n }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
      users: [], friends: [{ userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: Date.now() - 86400e3 }],
      incoming: [], outgoing: [],
      conversations: [{ conversationId: 'c-p', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' }, lastMessage: { id: 1, senderId: 'u-p', kind: 'text', body: 'x', createdAt: Date.now() }, unreadCount: 0, pendingAskCount: 0 }],
      messages: { 'c-p': Array.from({ length: n }, (_, i) => ({ id: 200 + i, senderId: i % 2 ? 'me' : 'u-p', kind: 'text', body: `结论与范围 #${200 + i} — 历史输入有三项已修复错误，但剂量口径仍需复核；这是一条足够长的正文用于观察被浮层裁切的边界。`, createdAt: Date.now() - (600 - i * 10) * 1e3 })) },
    }));
  }, { n: 16 });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: 'Mashiro' }, peers: [] }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: '' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: '' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); ses();
    ws.onMessage((raw) => { let p; try { p = JSON.parse(raw); } catch { return; }
      if (p.type === 'getConfig') cfg(); else if (p.type === 'getSessions' || p.type === 'listSessions') ses();
      else if (p.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: p.sessionId, messages: [], hasMore: false, offset: 0 })); });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws && s.ws.readyState === 1; }, SID, { timeout: 25000 });
  await page.waitForTimeout(500);
  return { ctx, page };
}

const hold = (ms) => new Promise((r) => setTimeout(r, ms));

async function mobileWalk(page, tag) {
  const shot = (n) => page.screenshot({ path: join(OUT, `${tag}-${n}.png`) });
  await shot('01-chat-root');
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="messages"].live'));
  await page.locator('.mobile-page[data-page="messages"] .mobile-page-body #fm-conversations .fm-conv-row').first().click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="conversation"].live'));
  await hold(600);
  await shot('02-conversation-rest');
  // 滚动消息流：让气泡穿过输入条 / dock 玻璃带
  for (const t of [-360, -360, 360, 360, 0]) {
    await page.evaluate((d) => { const f = document.querySelector('.mobile-page[data-page="conversation"] .fm-flow'); f.scrollTop += d; }, t);
    await hold(500);
  }
  await page.evaluate(() => { const f = document.querySelector('.mobile-page[data-page="conversation"] .fm-flow'); f.scrollTop = f.scrollHeight; });
  await hold(700);
  await shot('03-conversation-bottom');
  await page.locator('.mobile-page[data-page="conversation"] .mobile-back-btn').click();
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="workbench"].live'));
  await hold(500);
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').first().click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="settings"].live'));
  await hold(800);
  await shot('04-settings-top');
  await page.evaluate(() => { const s = document.querySelector('.mobile-page[data-page="settings"] #settings-content'); s.scrollTop = Math.round((s.scrollHeight - s.clientHeight) / 2); });
  await hold(800);
  await shot('05-settings-middle');
  await page.evaluate(() => { const s = document.querySelector('.mobile-page[data-page="settings"] #settings-content'); s.scrollTop = s.scrollHeight; });
  await hold(900);
  await shot('06-settings-bottom');
  const probe = await page.evaluate(`(() => {
    const q = (s) => document.querySelector(s);
    const d = q('#mobile-dock').getBoundingClientRect();
    const c = q('.mobile-page[data-page="conversation"]') ? null : null;
    const sc = q('.mobile-page[data-page="settings"] #settings-content');
    return { viewport: [innerWidth, innerHeight], hasMobileLayer: document.body.classList.contains('mobile-layer'),
      dock: [d.left, d.top, d.width, d.height], settingsScrollerTop: sc.getBoundingClientRect().top,
      dockBackdrop: getComputedStyle(q('#mobile-dock')).backdropFilter, dockBg: getComputedStyle(q('#mobile-dock')).backgroundColor };
  })()`);
  return { probe };
}

async function desktopWalk(page, tag) {
  const shot = (n) => page.screenshot({ path: join(OUT, `${tag}-${n}.png`) });
  await shot('01-chat-root');
  // 桌面设置：走应用自身的打开路径（拿不到就如实记为缺席）
  let opened = false;
  try {
    await page.evaluate(async () => { const m = await import('/js/settings.js'); (m.openSettingsPanel || m.default || (() => {}))(); });
    await page.waitForTimeout(900);
    opened = !!(await page.evaluate(() => {
      const el = document.getElementById('settings-modal');
      return !!el && el.getBoundingClientRect().height > 100;
    }));
  } catch { opened = false; }
  if (opened) await shot('04-settings');
  const probe = await page.evaluate(`(() => {
    const el = document.getElementById('settings-modal');
    const r = el ? el.getBoundingClientRect() : null;
    return { viewport: [innerWidth, innerHeight], hasMobileLayer: document.body.classList.contains('mobile-layer'),
      mobileDock: !!document.getElementById('mobile-dock'), mobilePages: !!document.getElementById('mobile-pages'),
      settingsOpen: ${opened}, settingsRect: r ? [r.left, r.top, r.width, r.height] : null,
      settingsPosition: el ? getComputedStyle(el).position : null };
  })()`);
  return { probe };
}

async function run(browser, size, tag, walk, record) {
  const { ctx, page } = await newCtx(browser, size, record);
  const out = await walk(page, tag);
  const video = record ? await page.video()?.path() : null;
  await ctx.close();
  return { tag, size, probe: out.probe, video };
}

const browser = await chromium.launch();
const results = [];
results.push(await run(browser, { width: 375, height: 812 }, `mobile-${label}`, mobileWalk, true));
results.push(await run(browser, { width: 1280, height: 800 }, `desktop-${label}`, desktopWalk, false));
await browser.close();
for (const r of results) console.log(JSON.stringify({ tag: r.tag, size: r.size, video: r.video ? basename(r.video) : null, probe: r.probe }));
