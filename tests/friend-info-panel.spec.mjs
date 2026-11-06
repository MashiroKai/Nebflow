// friend-info-panel.spec.mjs — 「三点按钮直达好友信息页 + 搜索面板顶部收敛」批
// （作者令 2026-11-05 17:59）机械验收。
//
// 断言面（P 系列）：
//   P1 点击路径：好友会话点一次三点 ⇒ **好友信息面当场打开**，且**不弹** `.fm-context-menu`
//      （作者：「进去应该直接就是好友信息的界面。不需要多点击一次」）。群/设备仍走菜单。
//   P2 好友信息页形态：头像 = 既有 40 档（40×40，不再是无规则漂移的 `fm-avatar-56`）、
//      英雄区左对齐单行、字段行**零分隔线零卡片盒**（行间只靠留白）。
//   P3 三新行顺序与存在：搜索聊天记录 / 消息免打扰 / 顶置（`data-pf-nav` / `data-pf-toggle`）。
//   P4 开关 = 共享组件 `.nb-toggle`（js/toggle.js，禁自画），点一次 aria-checked 翻真，
//      关面重进仍真（真值来自好友行 = 后端 PUT 的等价面）。
//   P5 偏好落到好友行：mock 好友行 `muted/pinned === true`。
//   P6 搜索入口复用：从好友信息页点「搜索聊天记录」打开的面板与主对话搜索钮打开的
//      是**同一个 DOM 节点**（同一节点打标记后换入口仍在）+ 全页只有 1 个 `#search-modal`。
//   P7 搜索面板顶部：页面名胶囊 = 「搜索聊天记录」是唯一标题（面板头隐藏、无第二处标题），
//      底部说明行 `#mh-search-foot` 不存在。
//   P8 零 console error（全流程）。
//
// 夹具：静态站点 + `fm_api_mock` 好友域 mock（与 profile-settings-batch-shots.mjs 同源）。

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const SID = 'friend-info-panel-spec';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const F_MAIN = {
  userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '',
  remark: '同事 · 后端组', bio: '写 Scala，也爱养多肉。', since: 1762000000000,
};

function seed() {
  const NOW = Date.now();
  return {
    self: { userId: 'me', username: 'Mashiro', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [], friends: [{ ...F_MAIN }], incoming: [], outgoing: [],
    conversations: [{
      conversationId: 'c-f1', friend: { ...F_MAIN },
      lastMessage: { id: 1, senderId: 'u-f1', kind: 'text', body: '在吗', createdAt: NOW }, unreadCount: 1,
    }],
    messages: { 'c-f1': [{ id: 1, type: 'user', text: '在吗', timestamp: NOW }] },
    groups: [], groupInvites: [], groupMembers: {}, _now: NOW,
  };
}

async function boot(page) {
  await page.addInitScript((s) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify(s));
    localStorage.setItem('fm_api_mock_delay', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  }, seed());

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (/^\/api\/sessions\/[^/]+\/history$/.test(p)) {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ messages: [{ id: 'm1', type: 'user', text: '搜索聊天记录', timestamp: 1762000000000 }], hasMore: false }),
        });
      }
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: 'Mac', platform: 'macos' }, peers: [] }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const st = (await import('/js/state.js')).default;
    return st.activeSessionId === sid && st.ws && st.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
}

const CONV = '.mobile-page[data-page="conversation"]';

async function openFriendConv(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await page.waitForFunction(() => {
    const el = document.querySelector('.mobile-page[data-page="messages"]');
    return !!el && el.classList.contains('live');
  }, null, { timeout: 8000 });
  await page.locator('#fm-conversations .fm-conv-row[data-conversation-id="c-f1"]').first().click();
  await page.waitForSelector(`${CONV} .fm-modal`, { timeout: 10000 });
  await page.waitForTimeout(500);
}

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

test('P1–P5 三点直达好友信息 + 扁平形态 + 两个真开关', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });

  await boot(page);
  await openFriendConv(page);

  // ── P1 一次点击直达（不弹菜单）──
  await page.locator(`${CONV} .mh-sess-more`).click();
  await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });
  expect(await page.locator('.fm-context-menu').count(), '三点不再弹菜单（零第二跳）').toBe(0);

  // ── P2 头像档位 + 英雄区形态 + 零分隔线零卡片盒 ──
  const shape = await page.evaluate(() => {
    const ov = document.querySelector('#profile-face-overlay');
    const avatar = ov.querySelector('.pf-hero .fm-avatar');
    const ab = avatar.getBoundingClientRect();
    const fields = ov.querySelector('.pf-fields');
    const rows = [...fields.children];
    let sep = 0;
    for (const el of [...rows, ...rows.flatMap((r) => [...r.children])]) {
      if (['INPUT', 'BUTTON', 'SELECT'].includes(el.tagName)) continue;
      const cs = getComputedStyle(el);
      if ((parseFloat(cs.borderTopWidth) || 0) > 0 || (parseFloat(cs.borderBottomWidth) || 0) > 0) sep += 1;
    }
    const boxy = rows.filter((r) => {
      const cs = getComputedStyle(r);
      const bg = cs.backgroundColor;
      return (bg && bg !== 'rgba(0, 0, 0, 0)') || (parseFloat(cs.borderTopWidth) || 0) > 0
        || (parseFloat(cs.borderLeftWidth) || 0) > 0 || (cs.boxShadow && cs.boxShadow !== 'none');
    }).length;
    return {
      avatarClass: avatar.className, avatarW: Math.round(ab.width), avatarH: Math.round(ab.height),
      heroDir: getComputedStyle(ov.querySelector('.pf-hero')).flexDirection,
      separators: sep, boxy,
    };
  });
  expect(shape.avatarClass, '头像走既有 40 档（无规则漂移的 fm-avatar-56 退役）').toContain('fm-avatar-40');
  expect(shape.avatarW, '头像直径 40').toBe(40);
  expect(shape.avatarH, '头像直径 40').toBe(40);
  expect(shape.heroDir, '英雄区 = 左对齐单行').toBe('row');
  expect(shape.separators, '字段行零分隔线（留白分隔）').toBe(0);
  expect(shape.boxy, '字段行零卡片盒（无底/无框/无影）').toBe(0);

  // ── P3 三条新行按作者给的顺序 ──
  const order = await page.evaluate(() => [...document.querySelectorAll('#profile-face-overlay .pf-fields > *')]
    .map((r) => (r.dataset.pfField || r.dataset.pfNav || r.dataset.pfSwitch || '')));
  expect(order).toEqual(['remark', 'bio', 'search', 'muted', 'pinned', '']);

  // ── P4 开关 = 共享组件 + 点击翻真 ──
  const muted = page.locator('#profile-face-overlay [data-pf-toggle="muted"]');
  await expect(muted).toHaveClass(/nb-toggle/);
  await expect(muted).toHaveAttribute('aria-checked', 'false');
  await muted.click();
  await expect(muted).toHaveAttribute('aria-checked', 'true');
  await page.locator('#profile-face-overlay [data-pf-toggle="pinned"]').click();
  await page.waitForTimeout(400);

  // ── P5 落到好友行（后端 PUT 的等价面）──
  const row = await page.evaluate(async () => {
    const api = await import('/js/friendsApi.js');
    const list = await api.getFriends();
    const f = (list.friends || []).find((x) => String(x.userId) === 'u-f1');
    return { muted: f.muted ?? null, pinned: f.pinned ?? null };
  });
  expect(row).toEqual({ muted: true, pinned: true });

  // 关面重进：状态仍在（真值回读，不是纯前端暂态）
  // panel-language 批（2026-11-06）：手机层 X 键退役（3(d)「有返回键就不放 X」，
  // DOM 级不建）⇒ 本文件 375 视口的关闭路径 = 返回键 .mfs-back（唯一出口）。
  await page.locator('#profile-face-overlay .mfs-back').click();
  await page.waitForTimeout(300);
  await page.locator(`${CONV} .mh-sess-more`).click();
  await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });
  await expect(page.locator('#profile-face-overlay [data-pf-toggle="muted"]')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('#profile-face-overlay [data-pf-toggle="pinned"]')).toHaveAttribute('aria-checked', 'true');

  expect(errors).toEqual([]);
});

test('P6–P7 搜索入口复用同一面板 + 顶部只有一个胶囊', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });

  await boot(page);
  await openFriendConv(page);
  await page.locator(`${CONV} .mh-sess-more`).click();
  await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });

  // ── P6 从好友信息页进搜索（同一张面）──
  await page.locator('#profile-face-overlay [data-pf-nav="search"]').click();
  await page.waitForSelector('#search-overlay.on', { timeout: 8000 });
  await page.waitForTimeout(500);
  expect(await page.locator('#search-modal').count(), '全页只有一张搜索面板').toBe(1);
  await page.evaluate(() => { const m = document.getElementById('search-modal'); if (m) m.dataset.fipMark = '1'; });

  // 换入口：聊天根搜索圆钮 → 同一节点（标记仍在）
  await page.evaluate(async () => { const m = await import('/js/chatSearch.js'); m.closeSearchModal(); });
  await page.waitForTimeout(300);
  for (let i = 0; i < 3; i += 1) {
    const back = page.locator('.mobile-page.live .mobile-back-btn');
    if (await back.count()) { await back.first().click(); await page.waitForTimeout(350); } else break;
  }
  const chatDock = page.locator('#mobile-dock .dock-key[data-dock="chat"]');
  if (await chatDock.count()) await chatDock.first().click({ force: true }).catch(() => {});
  await page.waitForTimeout(500);
  await page.locator('#mobile-fab-layer .mh-chrome-btn[data-chrome="search"]').first().click();
  await page.waitForSelector('#search-overlay.on', { timeout: 8000 });
  await page.waitForTimeout(500);
  expect(await page.evaluate(() => document.getElementById('search-modal')?.dataset.fipMark || ''),
    '两条入口打开的是同一个 DOM 节点（不是复制品）').toBe('1');

  // ── P7 顶部收敛 ──
  await expect(page.locator('.mobile-page[data-page="search"] .mobile-page-title')).toHaveText('搜索聊天记录');
  await expect(page.locator('#search-modal .search-modal-header')).toBeHidden();
  await expect(page.locator('#mh-search-foot')).toHaveCount(0);
  const visibleTitles = await page.evaluate(() => [...document.querySelectorAll('#search-modal-title')]
    .filter((el) => {
      const b = el.getBoundingClientRect();
      return getComputedStyle(el).display !== 'none' && b.width > 0 && b.height > 0;
    }).length);
  expect(visibleTitles, '搜索面板内零重复标题').toBe(0);

  expect(errors).toEqual([]);
});
