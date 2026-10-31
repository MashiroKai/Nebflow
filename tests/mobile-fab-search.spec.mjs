// mobile-fab-search.spec.mjs — 悬浮钮统一批 + 消息/联系人页搜索批主 spec
//（作者令 2026-10-31 16:03；规格书 docs/20261031_160300_mobile-fab-unify-search.md §8）。
//
// 覆盖（规格书 §8 A1-A8）：
//   F1 钮规统一：消息/联系人页 .mh-fab-search 48px @ top16+safe/right16；详情页
//      返回钮 48px g2-fab @ top16+safe/left16；canvas 页 ✕/堆叠钮 48px 同带；
//      材质 --g2-fab-bg token 探针同源；按压 spring 曲线断言。
//   F2 展开交互：点搜索钮 → .mh-search-open + aria-expanded + 焦点入输入框；
//      再点/Esc 收合；展开动效 transition 断言（max-height 220ms spring）。
//   F3 联系人承载：展开后 .fm-search-block 可见，既有搜好友逻辑出结果卡
//      （mock searchUser；桌面逻辑零改动承载）。
//   F4 消息检索：关键词 → 按会话分组（组头标签+计数）+ mark 高亮；范围 chip
//      过滤（群聊档只出群）；点结果行 → 会话页 live + 目标消息 .fm-reveal-flash。
//   F5 封存态：features.friends=false ⇒ 两页无 .mh-fab-search。
//
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-fab-search.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo, settlePage } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'fab-search-session';
const NOW = Date.now();

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 静态路由 + WS 夹具（bootApp 同源 mobile-dock.spec.mjs）。friends 域 =
 *  两个单聊（分页君/阿凯）+ 一个群（周末球局 g-1）；消息含共同关键词「部署」。 */
async function bootApp(page, { friends = true, sealed = false } = {}) {
  await page.addInitScript(({ friends: f, sealed: sd }) => {
    const NOW = Date.now(); // initScript 序列化不捕获模块级常量 —— 就地取材
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    if (f) {
      localStorage.setItem('fm_api_mock', '1');
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({
        self: { userId: 'me', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '' },
        users: [
          { userId: 'u-new', username: 'xintongxue', email: 'xin@example.com', displayName: '新同学', avatar: '' },
        ],
        friends: [
          { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: NOW - 86400e3 },
          { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '', since: NOW - 172800e3 },
        ],
        incoming: [],
        outgoing: [],
        conversations: [
          {
            conversationId: 'c-p', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
            lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 },
            unreadCount: 0,
          },
          {
            conversationId: 'c-k', friend: { userId: 'u-k', neblinkId: 'kaifriend', name: '阿凯', avatarUrl: '' },
            lastMessage: { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 },
            unreadCount: 0,
          },
        ],
        messages: {
          'c-p': [
            { id: 210, senderId: 'me', kind: 'text', body: '周五能交付吗', createdAt: NOW - 3600e3 },
            { id: 220, senderId: 'u-p', kind: 'text', body: '部署计划已经就绪', createdAt: NOW - 60e3 },
          ],
          'c-k': [
            { id: 330, senderId: 'me', kind: 'text', body: '部署清单在这里', createdAt: NOW - 120e3 },
          ],
          'g-1': [
            { id: 440, senderId: 'u-p', kind: 'text', body: '部署场地已经订好', createdAt: NOW - 1800e3 },
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
    }
  }, { friends, sealed });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (friends && p === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '我自己' }, peers: [] }) });
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
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData',
      config: sealed ? '{"features":{"friends":false}}' : (friends ? '{"features":{"friends":true}}' : '{}'),
      configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test('F1 button unification: 48px g2-fab geometry + material + spring press (A1/A2/A3)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  // 消息/联系人页搜索钮 + 详情页返回钮 + canvas 页工具钮，一族断言。
  await dockTo(page, 'messages');
  await dockTo(page, 'contacts');
  await dockTo(page, 'workbench');
  await page.locator('.mf-hub-row[data-hub-page="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  // canvas 页工具钮：开一张文档卡（走桌面 openTab API）。
  await page.evaluate(async () => {
    const c = await import('/js/canvas.js');
    c.openTab('doc-f1', '统一钮样例.md', { type: 'file' });
  });
  await page.waitForTimeout(400);
  const geo = await page.evaluate(() => {
    const fabBgProbe = document.createElement('span');
    fabBgProbe.style.background = 'var(--g2-fab-bg)';
    document.body.appendChild(fabBgProbe);
    const fabBg = getComputedStyle(fabBgProbe).backgroundColor;
    fabBgProbe.remove();
    const read = (sel, root = document) => {
      const el = root.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      return {
        box: { w: Math.round(r.width), h: Math.round(r.height) },
        top: Math.round(r.top), left: Math.round(r.left), right: Math.round(r.right),
        bg: cs.backgroundColor, radius: cs.borderRadius,
        tf: cs.transitionTimingFunction, tp: cs.transitionProperty,
      };
    };
    const msgsPage = document.querySelector('.mobile-page[data-page="messages"]');
    const contactsPage = document.querySelector('.mobile-page[data-page="contacts"]');
    const settingsPage = document.querySelector('.mobile-page[data-page="settings"]');
    const canvasPage = document.querySelector('.mobile-page[data-page="canvas"]');
    return {
      fabBg,
      msgFab: read('.mh-fab-search', msgsPage),
      ctFab: read('.mh-fab-search', contactsPage),
      back: read('.mobile-back-btn', settingsPage),
      cvClose: read('.mf-cv-close', canvasPage),
      cvStack: read('.mf-cv-stack', canvasPage),
      chrome: read('.mh-chrome-btn[data-chrome="tasks"]'),
    };
  });
  // A1 几何：48×48，top=16（safe=0），搜索钮 right=375-16=359，canvas/返回钮 left=16。
  for (const [k, v] of Object.entries({ msgFab: geo.msgFab, ctFab: geo.ctFab, back: geo.back, cvClose: geo.cvClose, cvStack: geo.cvStack })) {
    expect(v, k).not.toBeNull();
    expect(v.box, k).toEqual({ w: 48, h: 48 });
    expect(v.top, k).toBe(16);
  }
  expect(geo.msgFab.right).toBe(359);
  expect(geo.ctFab.right).toBe(359);
  expect(geo.back.left).toBe(16);
  expect(geo.cvClose.left).toBe(16);
  expect(geo.cvStack.right).toBe(359);
  // 与聊天根 chrome 任务键同高同材质族（基准规格不动它们本身）。
  expect(geo.chrome.box).toEqual({ w: 48, h: 48 });
  expect(geo.chrome.top).toBe(16);
  // A2 材质：全部 = --g2-fab-bg 探针同源。
  for (const [k, v] of Object.entries({ msgFab: geo.msgFab, ctFab: geo.ctFab, back: geo.back, cvClose: geo.cvClose, cvStack: geo.cvStack })) {
    expect(v.bg, k).toBe(geo.fabBg);
    expect(v.radius, k).toBe('50%');
  }
  // A3 按压：spring 曲线在 transition 族内（g2 配方 0.34,1.56,0.64,1）。
  expect(geo.msgFab.tf).toContain('cubic-bezier(0.34, 1.56, 0.64, 1)');
  expect(geo.back.tf).toContain('cubic-bezier(0.34, 1.56, 0.64, 1)');
  expect(errors).toEqual([]);
});

test('F2 expand interaction: toggle class, aria, focus, spring transition (A4)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await dockTo(page, 'messages');
  const fab = page.locator('.mobile-page[data-page="messages"] .mh-fab-search');
  await fab.click();
  await expect(page.locator('.mobile-page[data-page="messages"]')).toHaveClass(/mh-search-open/);
  expect(await fab.getAttribute('aria-expanded')).toBe('true');
  // 焦点进输入框（展开动效首段落位后聚焦，120ms）。
  await page.waitForTimeout(250);
  await expect(page.locator('.mh-search-input')).toBeFocused();
  // 动效 transition 断言（220ms spring，max-height 通道）。
  const tr = await page.evaluate(() => {
    const cs = getComputedStyle(document.querySelector('.mh-search-dock'));
    return { td: cs.transitionDuration, tf: cs.transitionTimingFunction, mh: cs.maxHeight };
  });
  expect(tr.td).toContain('0.22s');
  expect(tr.tf).toContain('cubic-bezier(0.34, 1.3, 0.64, 1)');
  // 展开态高度 > 0（下方内容整体下拉让位 = in-flow 推挤，由 A4 dock 在位承载）。
  expect(tr.mh).not.toBe('0px');
  // 再点收合（图标回 search）。
  await fab.click();
  await expect(page.locator('.mobile-page[data-page="messages"]')).not.toHaveClass(/mh-search-open/);
  expect(await fab.getAttribute('aria-expanded')).toBe('false');
  // Esc 展开后收合。
  await fab.click();
  await expect(page.locator('.mobile-page[data-page="messages"]')).toHaveClass(/mh-search-open/);
  await page.keyboard.press('Escape');
  await expect(page.locator('.mobile-page[data-page="messages"]')).not.toHaveClass(/mh-search-open/);
  expect(errors).toEqual([]);
});

test('F3 contacts expanding search carries existing find-friends logic (A5)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await dockTo(page, 'contacts');
  const fab = page.locator('.mobile-page[data-page="contacts"] .mh-fab-search');
  await fab.click();
  await expect(page.locator('.mobile-page[data-page="contacts"]')).toHaveClass(/mh-search-open/);
  // 真展开断言（computed 值 —— Playwright toBeVisible 不判 opacity，视觉可见性
  // 以 max-height/opacity 为准）：收合规则带 #mobile-pages ID 特异性，展开规则
  // 必须同维度对打（截图批实证过回归）。
  await page.waitForTimeout(300); // 展开动效落位
  const expanded = await page.evaluate(() => {
    const el = document.querySelector('.mobile-page[data-page="contacts"] .fm-search-block');
    const cs = getComputedStyle(el);
    return { maxHeight: cs.maxHeight, opacity: cs.opacity };
  });
  expect(expanded.maxHeight).not.toBe('0px');
  expect(expanded.opacity).toBe('1');
  // 既有桌面搜索块承载：输入 + 提交 → mock 结果卡（桌面逻辑零改动）。
  const input = page.locator('.mobile-page[data-page="contacts"] .fm-search-block .fm-search-input');
  await input.fill('xintongxue');
  await page.locator('.mobile-page[data-page="contacts"] .fm-search-btn').click();
  // 加载态卡（.fm-searching 三点脉冲）过后出结果卡。
  await expect(page.locator('.mobile-page[data-page="contacts"] .fm-result-card:not(.fm-searching)')).toBeVisible({ timeout: 5000 });
  const name = await page.locator('.mobile-page[data-page="contacts"] .fm-result-card').innerText();
  expect(name).toContain('新同学');
  expect(errors).toEqual([]);
});

test('F4 messages search: grouped results, mark highlight, scope filter, jump-to-message (A6/A7)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page);
  await dockTo(page, 'messages');
  const fab = page.locator('.mobile-page[data-page="messages"] .mh-fab-search');
  await fab.click();
  await page.locator('.mh-search-input').fill('部署');
  await page.waitForTimeout(700); // 300ms debounce + mock 30ms + 并发扫描
  // A6：按会话分组（分页君/阿凯/周末球局 三组），组头含标签与计数；行内 mark 高亮。
  await expect(page.locator('.mh-sr-group')).toHaveCount(3, { timeout: 5000 });
  const groups = await page.locator('.mh-sr-group').allInnerTexts();
  expect(groups.some((g) => g.includes('分页君'))).toBe(true);
  expect(groups.some((g) => g.includes('阿凯'))).toBe(true);
  expect(groups.some((g) => g.includes('周末球局'))).toBe(true);
  expect(await page.locator('.mh-sr-row')).toHaveCount(3);
  expect(await page.locator('.mh-sr-row mark')).toHaveCount(3);
  // 范围切换：群聊档只出群。
  await page.locator('.mh-scope-chip[data-scope="group"]').click();
  await page.waitForTimeout(700);
  await expect(page.locator('.mh-sr-group')).toHaveCount(1, { timeout: 5000 });
  expect((await page.locator('.mh-sr-group').innerText()).includes('周末球局')).toBe(true);
  // A7：点结果行 → 会话页 live + 目标消息 reveal 闪烁（g-1/440 在窗口内）。
  await page.locator('.mh-sr-row').first().click();
  await expect(page.locator('.mobile-page[data-page="conversation"]')).toHaveClass(/live/, { timeout: 5000 });
  await expect(page.locator('.fm-msg.fm-reveal-flash')).toHaveCount(1, { timeout: 8000 });
  expect(errors).toEqual([]);
});

test('F5 sealed mode: no search fabs (A8)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: false, sealed: true });
  await dockTo(page, 'messages');
  await dockTo(page, 'contacts');
  expect(await page.locator('.mh-fab-search')).toHaveCount(0);
  await settlePage(page, 'contacts');
  expect(errors).toEqual([]);
});
