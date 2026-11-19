// shots-mobile-pinned-search.mjs — 常驻顶部搜索条批实拍（375×812 × 亮/暗双主题）。
// 作者令 2026-11-02 07:13：消息/联系人两 tab 搜索框固定页顶、右上搜索浮钮退役。
//
// 帧单（每主题一套）：
//   m1-messages-pinned   消息页静止态（常驻条在位 + 右上无浮钮 + 列表首条在条下）
//   m2-messages-results  消息页输入关键词结果态（has-query：列表让位 / 分组结果 / mark）
//   m3-messages-cleared  消息页清空回列表态
//   c1-contacts-pinned   联系人页静止态（同款常驻条）
//   c2-contacts-results  联系人页输入结果态（结果卡在条下）
//   c3-contacts-cleared  联系人页清空回列表态
//   p1-search-return-pill 搜索回跳胶囊一帧（手机层抬层 170 后压在会话页之上）
//
// Self-contained（route 拦截 + fm_api_mock 夹具，零后端）。
// Run: NODE_PATH=<repo>/node_modules node tests/shots-mobile-pinned-search.mjs [outDir]

import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || join(HERE, '..', 'evidence', `${process.env.SHOT_STAMP || '20261102_mobile-pinned-search'}`);
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'pinned-search-shots';
const MARKER = '胶囊锚点测试句';
const T0 = Date.now();

/** 聊天历史夹具（30 条；含一条可检索标记行 —— 胶囊一帧的真跳转目标）。 */
const HISTORY = Array.from({ length: 30 }, (_, i) => {
  const ts = T0 - 900_000 + i * 30_000;
  if (i === 6) return { type: 'user', text: `第 ${i + 1} 行：${MARKER}（回跳胶囊的落点）`, timestamp: ts };
  return i % 2 === 0
    ? { type: 'ai', text: `口令行 ${i + 1}：常驻搜索条批实拍对照文本。`, timestamp: ts }
    : { type: 'user', text: `收到，第 ${i + 1} 项继续。`, timestamp: ts };
});

async function boot(browser, theme) {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: theme });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    const NOW = Date.now();
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
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
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{"name":"Nebula","displayName":"Nebula"}' });
    }
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: '主会话', agentName: 'Nebula' }], activeId: SID }) });
    }
    const hm = p.match(/^\/api\/sessions\/([^/]+)\/history/);
    if (hm) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: HISTORY, total: HISTORY.length, sessionId: hm[1] }) });
    }
    // 索引快路（chatSearch.js）：命中行 = 标记行。
    if (p === '/api/search/chat') {
      const q = url.searchParams.get('q') || '';
      const hits = HISTORY
        .filter((m) => m.text && q && m.text.includes(q))
        .map((m, i) => ({ sid: SID, ord: i, kind: 'message', snippet: m.text, ts: m.timestamp, extra: { role: m.type === 'user' ? 'user' : 'ai' } }));
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ hits, total: hits.length }) });
    }
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: [] }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const send = (o) => ws.send(JSON.stringify(o));
    const sendConfig = () => send({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' });
    const sendSessions = () => send({ type: 'sessionList', sessions: [{ id: SID, name: '主会话', agentName: 'Nebula' }], folders: [], activeId: SID });
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        send({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 });
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForSelector('#chat .row', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(700);
  // 取证环境注记：headless Chromium 无法栅格化 liquid-glass 折射层
  // （filter:url(#g2-refract) → 固态色盘），真机 WebKit/Chrome 正常。
  await page.evaluate(() => document.documentElement.classList.remove('g2-refract-on'));
  return { ctx, page };
}

const shot = (page, name) => page.screenshot({ path: join(OUT, name) });
const dockTo = async (page, id) => {
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await page.waitForTimeout(400);
};

const browser = await chromium.launch();
const errors = [];

for (const theme of ['light', 'dark']) {
  const { ctx, page } = await boot(browser, theme);
  page.on('pageerror', (e) => errors.push(`${theme}: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`${theme}: ${m.text()}`); });

  // ── 消息页三态 ────────────────────────────────────────────────
  await dockTo(page, 'messages');
  await shot(page, `m1-messages-pinned-${theme}.png`);

  const msgInput = page.locator('.mobile-page[data-page="messages"] .mh-search-input');
  await msgInput.fill('部署');
  await page.waitForSelector('.mh-sr-group', { timeout: 8000 });
  await page.waitForTimeout(450);
  await shot(page, `m2-messages-results-${theme}.png`);

  await page.locator('.mobile-page[data-page="messages"] .mh-search-clear').click();
  await page.waitForTimeout(500);
  await shot(page, `m3-messages-cleared-${theme}.png`);

  // ── 联系人页三态 ──────────────────────────────────────────────
  await dockTo(page, 'contacts');
  await shot(page, `c1-contacts-pinned-${theme}.png`);

  const ctInput = page.locator('.mobile-page[data-page="contacts"] .mh-search-input');
  await ctInput.fill('xintongxue');
  await page.waitForSelector('#fm-contacts-body .fm-result-card:not(.fm-searching)', { timeout: 8000 });
  await page.waitForTimeout(400);
  await shot(page, `c2-contacts-results-${theme}.png`);

  await page.locator('.mobile-page[data-page="contacts"] .mh-search-clear').click();
  await page.waitForTimeout(500);
  await shot(page, `c3-contacts-cleared-${theme}.png`);

  // ── 搜索回跳胶囊一帧（真实链路：聊天根搜索键 → 命中行 → 胶囊）────
  await page.evaluate(() => { (document.querySelector('.mh-chrome-btn[data-chrome="search"]')).click(); });
  await page.waitForSelector('#search-overlay.on', { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(500);
  await page.fill('#search-keyword', MARKER);
  await page.waitForSelector('#search-results .search-result', { timeout: 10000 });
  await page.waitForTimeout(400);
  await page.locator('#search-results .search-result').first().click();
  await page.waitForSelector('[data-nf-search-return="1"]:not(.hidden)', { timeout: 15000 });
  await page.waitForTimeout(1600); // 落地滚动驻停 + 胶囊位置稳定
  await shot(page, `p1-search-return-pill-${theme}.png`);

  await ctx.close();
  console.log(`${theme} 档截图完成`);
}

await browser.close();
if (errors.length) {
  console.log('CONSOLE/PAGE ERRORS:');
  for (const e of [...new Set(errors)]) console.log(' -', e);
  process.exit(1);
}
console.log(`OK → ${OUT}`);
