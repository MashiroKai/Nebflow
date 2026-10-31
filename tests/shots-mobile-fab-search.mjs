// shots-mobile-fab-search.mjs — 悬浮钮统一+搜索批 双主题截图 + 展开动效帧取证。
// Self-contained（route 拦截 + fm_api_mock 夹具，零后端）。
// Run: node tests/shots-mobile-fab-search.mjs [outDir]
// 产物：f1 聊天根基准钮 / f2 详情页悬浮返回钮 / f3 canvas 浮带工具钮 /
//       f4 消息页搜索钮 / f5 展开动效 4 帧 / f6 检索分组结果 / f7 联系人展开搜索 /
//       f8 跳转定位闪烁；亮暗双主题（f4-f7 亮档补齐）。

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || '/tmp/shots-mobile-fab-search';
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'fab-search-shots';

async function boot(browser, theme) {
  const page = await (await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: theme })).newPage();
  await page.addInitScript(() => {
    const NOW = Date.now();
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '' },
      users: [{ userId: 'u-new', username: 'xintongxue', email: 'xin@example.com', displayName: '新同学', avatar: '' }],
      friends: [
        { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: NOW - 86400e3 },
        { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '', since: NOW - 172800e3 },
      ],
      incoming: [], outgoing: [],
      conversations: [
        {
          conversationId: 'c-p', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
          lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 }, unreadCount: 2,
        },
        {
          conversationId: 'c-k', friend: { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '' },
          lastMessage: { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 }, unreadCount: 0,
        },
      ],
      messages: {
        'c-p': [
          { id: 210, senderId: 'me', kind: 'text', body: '周五能交付吗，方便的话把部署计划发我一份', createdAt: NOW - 3600e3 },
          { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪，明早可以先灰度一版看看效果', createdAt: NOW - 60e3 },
        ],
        'c-k': [
          { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里，依赖项都标好了', createdAt: NOW - 120e3 },
        ],
        'g-1': [
          { id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好，周六下午两点', createdAt: NOW - 1800e3 },
        ],
      },
      groups: [{
        groupId: 'g-1', title: '周末球局', role: 'owner', memberCount: 2,
        lastMessage: { id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好', createdAt: NOW - 1800e3 },
        unreadCount: 0, lastMessageId: 440, createdAt: NOW - 86400e3 * 7,
      }],
      groupInvites: [],
      groupMembers: {
        'g-1': [
          { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
          { userId: 'u-p', username: null, display_name: '分页君', avatar: null, role: 'member', joinedAt: 0 },
        ],
      },
    }));
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: [] }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let parsed; try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);
  // 取证环境注记：headless Chromium 无法栅格化 liquid-glass 折射层
  // （filter:url(#g2-refract) → 固态色盘），真机 WebKit/Chrome 正常。
  // 截图前摘折射类，钮体材质/几何/按压其余各层均真渲。
  await page.evaluate(() => document.documentElement.classList.remove('g2-refract-on'));
  return page;
}

const shot = (page, name) => page.screenshot({ path: join(OUT, name) });
const dockTo = async (page, id) => {
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await page.waitForTimeout(350);
};

const browser = await chromium.launch();
const errors = [];

for (const theme of ['dark', 'light']) {
  const page = await boot(browser, theme);
  page.on('pageerror', (e) => errors.push(`${theme}: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`${theme}: ${m.text()}`); });
  const T = theme === 'dark' ? '暗' : '亮';

  // f1 聊天根基准双钮（基准规格本身不动）。
  await shot(page, `f1-chat-root-${theme}.png`);

  // f2 详情页悬浮返回钮 + 标题带内居中。
  await dockTo(page, 'workbench');
  await page.locator('.mf-hub-row[data-hub-page="settings"]').click();
  await page.waitForTimeout(400);
  await shot(page, `f2-detail-back-${theme}.png`);

  // f3 canvas 页浮带工具钮（左 ✕ 右堆叠视角）。
  await page.evaluate(() => {
    import('/js/canvas.js').then((c) => c.openTab('doc-f1', '统一钮样例.md', { type: 'generic' }));
  });
  await page.waitForTimeout(300);
  await dockTo(page, 'canvas');
  await shot(page, `f3-canvas-toolbar-${theme}.png`);

  // f4 消息页搜索钮（dock 根页右上）。
  await dockTo(page, 'messages');
  await shot(page, `f4-messages-fab-${theme}.png`);

  // f5 展开动效 4 帧（点击后 60/120/220/420ms）。
  await page.locator('.mobile-page[data-page="messages"] .mh-fab-search').click();
  const t0 = Date.now();
  for (const [name, at] of [['f5-expand-frame0', 60], ['f5-expand-frame1', 120], ['f5-expand-frame2', 220], ['f5-expand-frame3', 420]]) {
    const wait = at - (Date.now() - t0);
    if (wait > 0) await page.waitForTimeout(wait);
    await shot(page, `${name}-${theme}.png`);
  }

  // f6 检索分组结果（mark 高亮）。
  await page.locator('.mh-search-input').fill('部署');
  await page.waitForSelector('.mh-sr-group', { timeout: 6000 });
  await page.waitForTimeout(350);
  await shot(page, `f6-messages-results-${theme}.png`);

  // f7 联系人页展开式搜索（既有搜好友逻辑承载）。
  await dockTo(page, 'contacts');
  await page.locator('.mobile-page[data-page="contacts"] .mh-fab-search').click();
  await page.waitForTimeout(450);
  await page.locator('.mobile-page[data-page="contacts"] .fm-search-block .fm-search-input').fill('xintongxue');
  await page.locator('.mobile-page[data-page="contacts"] .fm-search-btn').click();
  await page.waitForSelector('.mobile-page[data-page="contacts"] .fm-result-card:not(.fm-searching)', { timeout: 6000 });
  await page.waitForTimeout(250);
  await shot(page, `f7-contacts-search-${theme}.png`);

  // f8 跳转定位闪烁（点结果行进群会话，命中消息居中 + 闪烁中段）。
  await dockTo(page, 'messages');
  await page.evaluate(() => {
    const sec = document.querySelector('.mobile-page[data-page="messages"]');
    if (!sec.classList.contains('mh-search-open')) sec.querySelector('.mh-fab-search').click();
  });
  await page.locator('.mh-search-input').fill('部署');
  await page.waitForSelector('.mh-sr-row', { timeout: 6000 });
  await page.locator('.mh-sr-row').first().click();
  await page.waitForSelector('.mobile-page[data-page="conversation"] .fm-msg.fm-reveal-flash', { timeout: 8000 });
  await page.waitForTimeout(500); // 闪烁动画中段
  await shot(page, `f8-jump-flash-${theme}.png`);

  await page.close();
  console.log(`${T}档截图完成`);
}

await browser.close();
if (errors.length) {
  console.log('CONSOLE/PAGE ERRORS:');
  for (const e of [...new Set(errors)]) console.log(' -', e);
  process.exit(1);
}
console.log(`OK → ${OUT}`);
