// sidebar-unread-indicator.spec.mjs — 左侧 Activity Bar 未读提示断链回归钉
// （作者 2026-11-02 反馈逐字：「现在新消息的显示好像有问题，侧边栏 没通知我。」）。
//
// 断链形态（现场）：会话列表行「安之则」带绿色未读角标 `(1)`，但左侧竖排图标栏的
// 对话气泡图标上**没有任何未读数/红点**。行徽标读的是 `conv.unreadCount`（真值，
// messages.js convRow）；图标栏徽标读的是另一套聚合（`actionableUnread()`，只数
// 「未读 ∩ pendingAskCount/mentionCount」——这两个会话字段**全仓零生产者**，服务端
// 会话行契约只有 unreadCount）⇒ 图标栏恒 0。判定 = **两套计数不同源**（绑定层缺一条腿）。
//
// 五钉（判据 = 图标栏徽标与会话行徽标**同一读数源**）：
//   U1 两个会话各有 1 条未读、当前不在其中任何一个 ⇒ 图标栏徽标 `2`。
//   U2 进入其中一个会话（unreadCount 归零）⇒ 图标栏徽标 `1`（同步回落）。
//   U3 全部读完 ⇒ 徽标**消失**（hidden），不是显示 `0`。
//   U4 推送帧腿：「对方发来」未读 +1 ⇒ 徽标随动；「本账号在他处所发」（Agent 代发 /
//      同账号另一设备，`message_new_self`）**不涨未读**，徽标不动（§4 口径 sender != me）。
//   U5 窄桌面视口（800px，仍在桌面层）徽标照显（未读提示不得因屏宽消失）。
//
// 夹具 = 真模块图 + 真会话列表渲染（fm_api_mock 种两会话各 1 未读）+ routeWebSocket
// 桩服务端（真 `friend_event` 推送帧）；禁手写徽标数值（零第二判据）。
// 自包含：静态文件全走 route 拦截，无后端、无静态服务器端口占用。
//
// Run: node tests/sidebar-unread-indicator.spec.mjs

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'sidebar-unread-session';
const BADGE = '#messages-badge';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}

/** 会话夹具：两个单聊会话各有 1 条未读（行徽标面 = 与图标栏徽标同一读数源）。 */
function seed(now) {
  const mk = (id, userId, name, body) => ({
    conversationId: id,
    friend: { userId, neblinkId: userId + '@example.com', name, avatarUrl: '' },
    lastMessage: { id: 101, senderId: userId, kind: 'text', body, createdAt: now - 60e3 },
    unreadCount: 1,
  });
  return {
    self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
    users: [],
    friends: [
      { userId: 'u-a', neblinkId: 'anzhi@example.com', name: '安之则', avatarUrl: '', since: now - 86400e3 },
      { userId: 'u-b', neblinkId: 'bai@example.com', name: '白露', avatarUrl: '', since: now - 86400e3 },
    ],
    incoming: [],
    outgoing: [],
    conversations: [
      mk('c-a', 'u-a', '安之则', '[Agent 代发] 关于设计稿的一处修订'),
      mk('c-b', 'u-b', '白露', '晚饭吃什么'),
    ],
    messages: {
      'c-a': [{ id: 101, senderId: 'u-a', kind: 'text', body: '[Agent 代发] 关于设计稿的一处修订', createdAt: now - 60e3 }],
      'c-b': [{ id: 102, senderId: 'u-b', kind: 'text', body: '晚饭吃什么', createdAt: now - 120e3 }],
    },
  };
}

/** 会话视图 + WS 桩的共用装配；返回服务端侧 ws 句柄（推真帧用）。 */
async function boot(page, { width = 1280 } = {}) {
  await page.setViewportSize({ width, height: 860 });
  const now = Date.now();
  await page.addInitScript(({ seedJson }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', seedJson);
    localStorage.setItem('fm_api_mock_delay', '0');
    // harness：stub 掉 SW 注册（404 会污染 console error 断言）。
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  }, { seedJson: JSON.stringify(seed(now)) });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            loggedIn: true,
            device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' },
            peers: [],
          }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  let sock = null;
  await page.routeWebSocket(/\/ws/, (ws) => {
    sock = ws;
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  // 面板激活触发 refreshConversations（登录态就绪后才有行 + 徽标读数）。
  await page.click('#messages-btn');
  await page.waitForSelector('#fm-conversations .fm-conv-row', { state: 'visible', timeout: 15000 });
  return { push: (frame) => sock.send(JSON.stringify(frame)) };
}

/** 图标栏徽标读数（判据面 = 与行徽标同一读数源派生）。 */
function badgeState(page) {
  return page.evaluate((sel) => {
    const b = document.querySelector(sel);
    if (!b) return { missing: true };
    const cs = getComputedStyle(b);
    const r = b.getBoundingClientRect();
    const bar = document.getElementById('activity-bar');
    return {
      hidden: b.hidden,
      text: b.textContent,
      aria: b.getAttribute('aria-label'),
      visible: !b.hidden && cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0,
      // 角标矩形须落在图标栏盒子内（overflow:hidden 裁切面 = 真可见性判据）。
      insideBar: !!(bar && r.top >= bar.getBoundingClientRect().top && r.left >= bar.getBoundingClientRect().left
        && r.right <= bar.getBoundingClientRect().right && r.bottom <= bar.getBoundingClientRect().bottom),
      inViewport: r.top >= 0 && r.left >= 0 && r.right <= window.innerWidth,
      rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      barOverflow: bar ? getComputedStyle(bar).overflow : null,
      mobileLayer: document.body.classList.contains('mobile-layer'),
    };
  }, BADGE);
}

async function waitBadge(page, pred, label) {
  try {
    await page.waitForFunction(([sel, p]) => {
      const b = document.querySelector(sel);
      if (!b) return false;
      // eslint-disable-next-line no-new-func
      return new Function('b', `return (${p})(b);`)(b);
    }, [BADGE, pred], { timeout: 8000 });
    return true;
  } catch {
    console.log(`[waitBadge timeout] ${label}`);
    return false;
  }
}

const browser = await chromium.launch();
const errors = [];

async function newPage(width) {
  // 桌面视口（> 768px ⇒ 不激活手机层，图标栏在位 = 作者截图形态）。
  const ctx = await browser.newContext({ viewport: { width, height: 860 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return { ctx, page };
}

try {
  // ══ 桌面档（1280px）═══════════════════════════════════════════
  {
    const { ctx, page } = await newPage(1280);
    const { push } = await boot(page);

    // ── U1 两个会话各有未读、都不在其中 ⇒ 图标栏徽标 2 ────────────────
    const u1 = await badgeState(page);
    const rowBadges1 = await page.$$eval('#fm-conversations .fm-conv-row .fm-row-badge', els => els.map(e => e.textContent));
    ok('U1 两个未读会话 ⇒ 图标栏徽标 = 2（与行徽标同源派生）',
      !u1.missing && u1.hidden !== true && u1.text === '2' && u1.visible,
      `badge=${JSON.stringify(u1)} rows=${JSON.stringify(rowBadges1)}`);
    ok('U1b 行徽标两行各 1（同一条读数额，禁两套计数）',
      rowBadges1.length === 2 && rowBadges1.every(t => t === '1'), JSON.stringify(rowBadges1));
    ok('U1c 徽标未被容器裁切（落在图标栏盒内 + overflow:hidden 面核实）',
      u1.insideBar === true && u1.inViewport === true,
      `rect=${JSON.stringify(u1.rect)} insideBar=${u1.insideBar} overflow=${u1.barOverflow}`);

    // ── U2 进入其中一个会话（unread 归零）⇒ 图标栏徽标 1 ───────────────
    await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-a"]');
    await page.waitForFunction(() => document.querySelector('.fm-modal') !== null, null, { timeout: 8000 });
    await waitBadge(page, '(b) => !b.hidden && b.textContent === "1"', 'U2');
    const u2 = await badgeState(page);
    ok('U2 进入 c-a 后徽标回落 = 1（已读即时清零同源）',
      u2.text === '1' && u2.visible, JSON.stringify(u2));

    // ── U3 两个都读完 ⇒ 徽标消失（hidden，不是 0）─────────────────────
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelector('.fm-modal') === null, null, { timeout: 8000 });
    await page.click('#fm-conversations .fm-conv-row[data-conversation-id="c-b"]');
    await page.waitForFunction(() => document.querySelector('.fm-modal') !== null, null, { timeout: 8000 });
    await waitBadge(page, '(b) => b.hidden === true', 'U3');
    const u3 = await badgeState(page);
    const rowBadges3 = await page.$$eval('#fm-conversations .fm-conv-row .fm-row-badge', els => els.length);
    ok('U3 全部读完 ⇒ 徽标消失（hidden，不是显示 0）',
      u3.hidden === true && u3.visible === false && rowBadges3 === 0,
      `badge=${JSON.stringify(u3)} rows=${rowBadges3}`);

    // ── U4 推送帧腿：inbound 涨未读；Agent 代发（self 帧）不涨 ──────────
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelector('.fm-modal') === null, null, { timeout: 8000 });
    // ① 对方发来（都没开会话）⇒ 未读 +1 ⇒ 徽标 1。
    push({ type: 'friend_event', event: 'message_new', conversationId: 'c-b', messageId: 'm-in-1', senderId: 'u-b', kind: 'text', body: '在吗', createdAt: new Date().toISOString() });
    await waitBadge(page, '(b) => !b.hidden && b.textContent === "1"', 'U4a');
    const u4a = await badgeState(page);
    ok('U4a 对方推送一条（未开会话）⇒ 徽标 1（未读增量腿随动）',
      u4a.text === '1' && u4a.visible, JSON.stringify(u4a));
    // ② 「本账号在他处所发」（Agent 代发 / 同账号另一设备）⇒ 不涨未读 ⇒ 徽标不动。
    push({ type: 'friend_event', event: 'message_new_self', conversationId: 'c-b', messageId: 'm-self-1', senderId: 'me', kind: 'agent', origin: 'agent', body: '[Agent 代发] 已代为回复', createdAt: new Date().toISOString() });
    await page.waitForTimeout(900);
    const u4b = await badgeState(page);
    const rowBadgeB = await page.$eval('#fm-conversations .fm-conv-row[data-conversation-id="c-b"] .fm-row-badge', e => e.textContent).catch(() => null);
    ok('U4b Agent 代发（message_new_self）不涨未读：徽标仍 1、行徽标仍 1（同源同不动）',
      u4b.text === '1' && rowBadgeB === '1', `badge=${JSON.stringify(u4b)} row=${rowBadgeB}`);

    ok('U4c 桌面档零 pageerror / console error', errors.length === 0, JSON.stringify(errors.slice(0, 5)));
    await ctx.close();
  }

  // ══ 窄桌面档（800px，仍在桌面层：手机层折叠点 = 768px）══════════════
  {
    const { ctx, page } = await newPage(800);
    await boot(page, { width: 800 });
    const u5 = await badgeState(page);
    ok('U5 窄桌面视口（800px）徽标照显（未读提示不因屏宽消失）',
      u5.hidden !== true && u5.visible === true && u5.text === '2' && u5.mobileLayer === false && u5.insideBar === true,
      JSON.stringify(u5));
    await ctx.close();
  }
} finally {
  await browser.close();
}

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAIL`);
process.exit(failures === 0 ? 0 : 1);
