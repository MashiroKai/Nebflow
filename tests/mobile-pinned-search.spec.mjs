// mobile-pinned-search.spec.mjs — 常驻顶部搜索条批主 spec
//（作者令 2026-11-02 07:13「手机端『消息』和『联系人』两个 tab 的搜索框固定在
//  页面顶部，不再用右上角的悬浮按钮来控制搜索」）。
//
// 契约换代（相对旧 tests/mobile-fab-search.spec.mjs 的「浮钮呼出 + 展开式搜索区」）：
//   · 两页搜索条 = 页面体流内**首件常驻**（.mh-search-dock > .mh-search-bar），
//     静止态即在位；输入即搜、清空即回列表。
//   · 右上搜索浮钮 .mh-fab-search 与页面 section 的 .mh-search-open 收合语汇
//     在两页退役（P1/P2/P9 断言其计数为 0）。
//   · 其它面的搜索入口零触碰（P7：聊天根 chrome 搜索键 + 详情页搜索页仍可用）。
//   · 搜索回跳胶囊（searchReturnPill）在固定条形态下仍要工作 ⇒ 手机层抬层
//     170（> 页面容器 156，< dock 200）由 P8 钉住。
//
// 覆盖：
//   P1 消息页静止态：常驻条几何（44px 全圆角 / 左右 16 / 贴页面顶）+ 材质
//      （--glass-control-* 单源）+ 列表首行在条下 + 浮钮/收合态双零。
//   P2 联系人页静止态：同款几何材质 + 桌面搜索块输入行收起 + 好友首行在条下。
//   P3 消息页输入即搜：3 组结果 / mark 高亮 / 会话列表整块让位 / 首结果在条下。
//   P4 清空即回列表（清空键）。
//   P5 Esc = 清空回列表。
//   P6 联系人页常驻条 = 该页唯一搜索入口（桌面输入行收起）：输入出结果卡、
//      输入串跨面板重渲保留、清空后卡片消失且首行回位。
//   P7 其它面零触碰：聊天根 chrome 搜索键 48×48 @ right359（垂直落点由会话顶栏
//      单行三段式批 2026-11-02 改写为 top 4+safe）且仍能开搜索页；
//      详情页（设置）「<」48 + 大标题在；搜索页/详情页无常驻条。
//   P8 搜索回跳胶囊手机层 z 抬层 = 170，层序 156 < 170 < 200。
//   P9 封存态（features.friends=false）：两页既无常驻条也无浮钮。
//   P10 双主题材质：亮/暗两档 --glass-control-bg 解析值同源（0.60 / 0.14）。
//
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-pinned-search.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo, openHubPage, settlePage } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'pinned-search-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 静态路由 + WS 夹具（bootApp 同源 mobile-dock.spec.mjs）。friends 域 =
 *  两个单聊（分页君/阿凯）+ 一个群（周末球局 g-1）；消息含共同关键词「部署」；
 *  users 含 xintongxue（联系人页搜索命中面）。 */
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

/** 页面内探针：常驻条几何/材质/状态 + 浮钮缺席 + 列表让位（单一读数面）。 */
async function readPinnedBar(page, pageId) {
  return page.evaluate((pid) => {
    const section = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
    const body = section?.querySelector('.mobile-page-body') || null;
    const rectOf = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return {
        top: Math.round(r.top), bottom: Math.round(r.bottom),
        left: Math.round(r.left), right: Math.round(r.right),
        w: Math.round(r.width), h: Math.round(r.height),
      };
    };
    // 玻璃单源探针（见 sapphire.css :root / prefers-color-scheme: dark 两档）。
    const probe = document.createElement('span');
    probe.style.background = 'var(--glass-control-bg)';
    document.body.appendChild(probe);
    const tokenBg = getComputedStyle(probe).backgroundColor;
    probe.remove();
    // 件七（2026-11-04 搜索扁平化批）：扁平场底单源 = --color-surface（同法探针）。
    const probe2 = document.createElement('span');
    probe2.style.background = 'var(--color-surface)';
    document.body.appendChild(probe2);
    const tokenSurface = getComputedStyle(probe2).backgroundColor;
    probe2.remove();
    // 件八（2026-11-05 12:28 扁平长条批）：浅灰底单源 = --ds-wash-16（微信形态 +
    // 我方令牌；微信截图只借形态不借数值）。同法探针。
    const probe3 = document.createElement('span');
    probe3.style.background = 'var(--ds-wash-16)';
    document.body.appendChild(probe3);
    const tokenWash = getComputedStyle(probe3).backgroundColor;
    probe3.remove();
    // 材质换代（glass-top-overlays 2026-11-06）：条浮在滚动列表之上 ⇒ 走唯一
    // 毛玻璃标准。判据仍取「计算样式 == token 解析值」的单源对偶形式，token 面
    // 换成 --nf-glass-*（底 / 配方 / 浮影三探针）。
    const probeGlass = document.createElement('span');
    probeGlass.style.cssText = 'position:absolute;left:-9999px;background:var(--nf-glass-bg);'
      + 'border-top:1px solid var(--nf-glass-border-color);box-shadow:var(--nf-glass-shadow);';
    document.body.appendChild(probeGlass);
    const gs = getComputedStyle(probeGlass);
    const tokenGlassBg = gs.backgroundColor;
    const tokenGlassBorder = gs.borderTopColor;
    const tokenGlassShadow = gs.boxShadow;
    probeGlass.remove();
    const probeFilter = document.createElement('span');
    probeFilter.style.cssText = 'position:absolute;left:-9999px;backdrop-filter:var(--nf-glass-filter);';
    document.body.appendChild(probeFilter);
    const tokenGlassFilter = getComputedStyle(probeFilter).backdropFilter || 'none';
    probeFilter.remove();

    const dock = body?.querySelector(':scope > .mh-search-dock') || null;
    const bar = dock?.querySelector('.mh-search-bar') || null;
    const cs = bar ? getComputedStyle(bar) : null;
    const input = dock?.querySelector('.mh-search-input') || null;
    return {
      sectionLive: !!section?.classList.contains('live'),
      dockExists: !!dock,
      dockFirstChild: !!dock && body?.firstElementChild === dock,
      dockRole: dock?.getAttribute('role') || null,
      dockDataMh: dock?.getAttribute('data-mh') || null,
      hasQuery: !!dock?.classList.contains('has-query'),
      bar: rectOf(bar),
      pageTop: rectOf(section)?.top ?? null,
      bg: cs?.backgroundColor ?? null,
      tokenBg,
      tokenSurface,
      tokenWash,
      tokenGlassBg,
      tokenGlassBorder,
      tokenGlassShadow,
      tokenGlassFilter,
      borderTopColor: cs?.borderTopColor ?? null,
      hasGlassClass: !!bar?.classList.contains('nf-glass'),
      radius: cs?.borderRadius ?? null,
      shadow: cs?.boxShadow ?? null,
      backdrop: cs ? (cs.webkitBackdropFilter || cs.backdropFilter || 'none') : null,
      transform: cs?.transform ?? null,
      inputValue: input?.value ?? null,
      inputPlaceholder: input?.placeholder ?? null,
      fabCount: section?.querySelectorAll('.mh-fab-search').length ?? 0,
      openClass: !!section?.classList.contains('mh-search-open'),
      // 让位读数：消息页 = 会话列表容器/首行；联系人页 = 好友列表首行。
      listDisplay: (() => {
        const list = body?.querySelector(':scope > #fm-conversations') || null;
        return list ? getComputedStyle(list).display : null;
      })(),
      firstListRow: rectOf(
        body?.querySelector('#fm-conversations .fm-conv-row')
        || body?.querySelector('#fm-contacts-body .fm-friend-row')
        || null,
      ),
      // 结果面位置：消息页在条内（.mh-sr-row），联系人页在收编面内（.fm-result-card）。
      firstResultRow: rectOf(
        dock?.querySelector('.mh-sr-row')
        || body?.querySelector('#fm-contacts-body .fm-result-card')
        || null,
      ),
      desktopSearchInput: (() => {
        const el = body?.querySelector('#fm-contacts-body .fm-search-input') || null;
        if (!el) return null;
        const row = el.closest('.fm-search') || el;
        return { value: el.value, display: getComputedStyle(row).display };
      })(),
      searchBlockDisplay: (() => {
        const el = body?.querySelector('#fm-contacts-body .fm-search-block') || null;
        return el ? getComputedStyle(el).display : null;
      })(),
    };
  }, pageId);
}

/** P1/P2 共用几何+材质断言（主题无关：材质走 token 探针同源比对）。 */
function expectPinnedGeometry(st, pageId) {
  expect(st.dockExists, `${pageId}: 常驻条存在`).toBe(true);
  expect(st.dockFirstChild, `${pageId}: 条是页面体首件`).toBe(true);
  expect(st.dockRole, `${pageId}: role=search`).toBe('search');
  expect(st.bar, `${pageId}: bar 有几何`).not.toBeNull();
  // 44px 高、全圆角、左右 16（375 视口 ⇒ right = 359）。
  expect(st.bar.h, `${pageId}: 44px`).toBe(44);
  expect(st.bar.left, `${pageId}: left 16`).toBe(16);
  expect(st.bar.right, `${pageId}: right 359`).toBe(359);
  // 材质换代（glass-top-overlays 2026-11-06 作者令「所有悬浮在顶部 可以透出背景的
  // 都用毛玻璃效果 比如消息和联系人的手机端的搜索框」）：条 = `.nf-glass` 唯一
  // 毛玻璃标准 —— 玻璃底 / 细边 / 内高光浮影 / blur(40px) saturate(1.4) 四项
  // 逐值 == --nf-glass-* 解析值。几何（44 高 / 圆角 12 / 左右 16 / 常驻不位移）
  // 与「浮钮退役」语义零改。判据面单源 = tests/mobile-search-flat.spec.mjs。
  expect(st.radius, `${pageId}: 圆角 = --ds-radius-xl 12px`).toBe('12px');
  // 贴页面顶（dock 顶 padding 8px，允许 0-12 摆动）。
  expect(st.bar.top - st.pageTop, `${pageId}: 贴页面顶`).toBeLessThanOrEqual(12);
  expect(st.bar.top - st.pageTop).toBeGreaterThanOrEqual(0);
  expect(st.hasGlassClass, `${pageId}: 条挂 .nf-glass（唯一工具类）`).toBe(true);
  expect(st.bg, `${pageId}: 玻璃底 = --nf-glass-bg 单源`).toBe(st.tokenGlassBg);
  expect(st.borderTopColor, `${pageId}: 细边 = --nf-glass-border-color 单源`).toBe(st.tokenGlassBorder);
  expect(st.backdrop, `${pageId}: 毛玻璃配方 = --nf-glass-filter 单源`).toBe(st.tokenGlassFilter);
  expect(st.backdrop, `${pageId}: 配方含 blur(40px) saturate(1.4)`).toMatch(/blur\(40px\)\s+saturate\(1\.4\)/);
  expect(st.shadow, `${pageId}: 浮影 = --nf-glass-shadow 单源（面级浮件档）`).toBe(st.tokenGlassShadow);
  expect(st.transform, `${pageId}: 无 transform（常驻不位移）`).toBe('none');
  // 旧浮钮语汇在这两页退役。
  expect(st.fabCount, `${pageId}: 右上搜索浮钮退役`).toBe(0);
  expect(st.openClass, `${pageId}: .mh-search-open 语汇退役`).toBe(false);
  // 静止态：有查询态元素不占位。
  expect(st.hasQuery, `${pageId}: 静止态无查询`).toBe(false);
}

test('P1 messages page: pinned bar geometry/material + list starts below it + fab retired', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'messages');
  await expect(page.locator('.mobile-page[data-page="messages"] .mobile-page-body > #fm-conversations')).toHaveCount(1);

  const st = await readPinnedBar(page, 'messages');
  expectPinnedGeometry(st, 'messages');
  expect(st.dockDataMh).toBe('msgsearch');
  expect(st.inputPlaceholder).toBe('搜索聊天记录');
  expect(st.inputValue).toBe('');
  // 静止态列表在流内起排：首行顶不早于条底（不遮挡首条消息）。
  expect(st.listDisplay).not.toBe('none');
  expect(st.firstListRow, 'messages: 会话首行在位').not.toBeNull();
  expect(st.firstListRow.top, 'messages: 首行在条下').toBeGreaterThanOrEqual(st.bar.bottom);

  // 静止态可见面 = 一条纯净搜索条（范围 chips / 脚注 / 状态行都不占位）。
  const still = await page.evaluate(() => {
    const dock = document.querySelector('.mobile-page[data-page="messages"] .mh-search-dock');
    const cs = (sel) => {
      const el = dock?.querySelector(sel);
      return el ? getComputedStyle(el).display : null;
    };
    return { chips: cs('.mh-scope-row'), note: cs('.mh-search-note'), status: cs('.mh-search-status') };
  });
  expect(still.chips).toBe('none');
  expect(still.note).toBe('none');
  expect(still.status).toBe('none');
  expect(errors).toEqual([]);
});

test('P2 contacts page: same pinned bar, desktop input row retired, friend rows below it', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'contacts');
  await expect(page.locator('.mobile-page[data-page="contacts"] .mobile-page-body > #fm-contacts-body')).toHaveCount(1);

  const st = await readPinnedBar(page, 'contacts');
  expectPinnedGeometry(st, 'contacts');
  expect(st.dockDataMh).toBe('contactsearch');
  expect(st.inputPlaceholder).toBe('用户名 / 邮箱');
  // 同页不并存第二枚输入框：桌面搜索块输入行在手机层收起（结果卡仍在其下渲染）。
  expect(st.desktopSearchInput, 'contacts: 桌面输入行在位但收起').not.toBeNull();
  expect(st.desktopSearchInput.display).toBe('none');
  expect(st.searchBlockDisplay, 'contacts: 无结果卡时搜索块不留空档').toBe('none');
  // 好友首行在条下。
  expect(st.firstListRow, 'contacts: 好友首行在位').not.toBeNull();
  expect(st.firstListRow.top, 'contacts: 首行在条下').toBeGreaterThanOrEqual(st.bar.bottom);
  expect(errors).toEqual([]);
});

test('P3 messages page: type-to-search yields, conversation list yields, first result under the bar', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'messages');
  const input = page.locator('.mobile-page[data-page="messages"] .mh-search-input');
  await input.fill('部署');
  // 300ms 去抖 + mock 30ms + 并发扫描。
  await expect(page.locator('.mh-sr-group')).toHaveCount(3, { timeout: 8000 });
  const groups = await page.locator('.mh-sr-group').allInnerTexts();
  expect(groups.some((g) => g.includes('分页君'))).toBe(true);
  expect(groups.some((g) => g.includes('阿凯'))).toBe(true);
  expect(groups.some((g) => g.includes('周末球局'))).toBe(true);
  expect(await page.locator('.mh-sr-row')).toHaveCount(3);
  expect(await page.locator('.mh-sr-row mark')).toHaveCount(3);

  const st = await readPinnedBar(page, 'messages');
  expect(st.hasQuery, 'messages: 有查询态').toBe(true);
  expect(st.listDisplay, 'messages: 会话列表整块让位').toBe('none');
  expect(st.firstResultRow, 'messages: 首结果在位').not.toBeNull();
  expect(st.firstResultRow.top, 'messages: 首结果不被条遮住').toBeGreaterThanOrEqual(st.bar.bottom);
  expect(errors).toEqual([]);
});

test('P4 messages page: clear button returns to the list', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'messages');
  const input = page.locator('.mobile-page[data-page="messages"] .mh-search-input');
  await input.fill('部署');
  await expect(page.locator('.mh-sr-row')).toHaveCount(3, { timeout: 8000 });
  await page.locator('.mobile-page[data-page="messages"] .mh-search-clear').click();
  await expect(page.locator('.mh-sr-row')).toHaveCount(0);
  const st = await readPinnedBar(page, 'messages');
  expect(st.inputValue).toBe('');
  expect(st.hasQuery).toBe(false);
  expect(st.listDisplay).not.toBe('none');
  expect(st.firstListRow.top).toBeGreaterThanOrEqual(st.bar.bottom);
  expect(errors).toEqual([]);
});

test('P5 messages page: Esc clears the query (single clear semantics preserved)', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'messages');
  const input = page.locator('.mobile-page[data-page="messages"] .mh-search-input');
  await input.fill('部署');
  await expect(page.locator('.mh-sr-row')).toHaveCount(3, { timeout: 8000 });
  await input.press('Escape');
  await expect(page.locator('.mh-sr-row')).toHaveCount(0);
  const st = await readPinnedBar(page, 'messages');
  expect(st.inputValue).toBe('');
  expect(st.listDisplay).not.toBe('none');
  // 常驻条保持可见（无收合位移）。
  expect(await input.isVisible()).toBe(true);
  expect(errors).toEqual([]);
});

test('P6 contacts page: pinned bar is the single search entry; query survives panel re-render; clear restores list', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'contacts');
  const input = page.locator('.mobile-page[data-page="contacts"] .mh-search-input');
  await input.fill('xintongxue');
  // 450ms 去抖 → 同一提交流水 → 结果卡（mock searchUser 30ms）。
  await expect(page.locator('#fm-contacts-body .fm-result-card:not(.fm-searching)')).toBeVisible({ timeout: 8000 });
  expect(await page.locator('#fm-contacts-body .fm-result-card').innerText()).toContain('新同学');

  const st = await readPinnedBar(page, 'contacts');
  expect(st.inputValue, 'contacts: 输入串不被重渲清掉').toBe('xintongxue');
  expect(st.desktopSearchInput.value, 'contacts: 同一查询源 searchQ').toBe('xintongxue');
  expect(st.searchBlockDisplay, 'contacts: 有结果卡时搜索块可见').not.toBe('none');
  expect(st.firstResultRow, 'contacts: 结果卡行在位').not.toBeNull();
  expect(st.firstResultRow.top, 'contacts: 结果卡在条下').toBeGreaterThanOrEqual(st.bar.bottom);

  // 清空 = 清场回列表（结果卡收起、搜索块不留空档、首行回位）。
  await page.locator('.mobile-page[data-page="contacts"] .mh-search-clear').click();
  await expect(page.locator('#fm-contacts-body .fm-result-card')).toHaveCount(0);
  const back = await readPinnedBar(page, 'contacts');
  expect(back.inputValue).toBe('');
  expect(back.hasQuery).toBe(false);
  expect(back.searchBlockDisplay).toBe('none');
  expect(back.firstListRow.top).toBeGreaterThanOrEqual(back.bar.bottom);
  expect(errors).toEqual([]);
});

test('P7 other faces untouched: chat chrome search key + detail pages keep the floating language', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'messages');

  // 聊天根顶缘搜索键（本批零触碰）：48×48 @ right359 / 圆钮材质不变。
  // 垂直落点沿革：top 16（本批）→ 4+safe（会话顶栏单行三段式批 2026-11-02 07:23）
  // → **16+safe（会话页头同构批 件二+件三 2026-11-02，fixed 真顶栏整条退役）**：
  // 钮回到悬浮顶缘 16，与左上任务键同档；尺寸/材质/水平足印仍按本批契约零改动。
  const chrome = await page.evaluate(() => {
    const el = document.querySelector('.mh-chrome-btn[data-chrome="search"]');
    if (!el) return null;
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return {
      w: Math.round(r.width), h: Math.round(r.height),
      top: Math.round(r.top), right: Math.round(r.right),
      radius: cs.borderRadius,
    };
  });
  expect(chrome, 'chrome 搜索键在位').not.toBeNull();
  expect({ w: chrome.w, h: chrome.h }).toEqual({ w: 48, h: 48 });
  expect(chrome.top).toBe(16);
  expect(chrome.right).toBe(359);
  expect(chrome.radius).toBe('50%');

  // 该键仍能开搜索页（桌面搜索面在手机层页面化）——搜索入口未被本批影响。
  await page.evaluate(() => { (document.querySelector('.mh-chrome-btn[data-chrome="search"]')).click(); });
  await expect(page.locator('.mobile-page[data-page="search"]')).toHaveClass(/live/, { timeout: 5000 });
  await settlePage(page, 'search');
  expect(await page.locator('.mobile-page[data-page="search"] .mh-search-dock').count(), 'search 页无常驻条').toBe(0);

  // 详情页面壳语言不变（「<」48 + 大标题在），搜索条只属于那两个 dock 根页。
  await openHubPage(page, 'settings');
  const detail = await page.evaluate(() => {
    const pg = document.querySelector('.mobile-page[data-page="settings"]');
    const back = pg?.querySelector('.mobile-back-btn');
    const r = back?.getBoundingClientRect();
    return {
      back: r ? { w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top), left: Math.round(r.left) } : null,
      title: pg?.querySelectorAll('.mobile-page-title').length ?? 0,
      dock: pg?.querySelectorAll('.mh-search-dock').length ?? 0,
    };
  });
  expect(detail.back).not.toBeNull();
  expect({ w: detail.back.w, h: detail.back.h }).toEqual({ w: 48, h: 48 });
  expect(detail.back.top).toBe(16);
  expect(detail.back.left).toBe(16);
  expect(detail.title).toBe(1);
  expect(detail.dock, 'detail 页无常驻条').toBe(0);
  expect(errors).toEqual([]);
});

test('P8 search-return pill: mobile layer lifts it above the page container, below the dock', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'messages');
  // 胶囊真身由 searchReturnPill.js 懒建在 #main —— 这里按同一属性选择器注入
  // 一枚探针节点，读的正是抬层规则命中的 computed z-index。
  const z = await page.evaluate(() => {
    const main = document.getElementById('main');
    const probe = document.createElement('button');
    probe.setAttribute('data-nf-search-return', '1');
    main.appendChild(probe);
    const out = {
      pill: getComputedStyle(probe).zIndex,
      pages: getComputedStyle(document.getElementById('mobile-pages')).zIndex,
      dock: getComputedStyle(document.getElementById('mobile-dock')).zIndex,
    };
    probe.remove();
    return out;
  });
  expect(z.pill, '搜索回跳胶囊抬到 170').toBe('170');
  expect(Number(z.pages), '页面容器 156').toBe(156);
  expect(Number(z.dock), 'dock 200').toBe(200);
  expect(Number(z.pages) < Number(z.pill) && Number(z.pill) < Number(z.dock), '层序 156 < 170 < 200').toBe(true);
  expect(errors).toEqual([]);
});

test('P9 sealed mode: neither pinned bar nor fab search on the two tabs', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: false, sealed: true });
  await dockTo(page, 'messages');
  await dockTo(page, 'contacts');
  expect(await page.locator('.mh-search-dock').count()).toBe(0);
  expect(await page.locator('.mh-fab-search').count()).toBe(0);
  expect(await page.locator('.mobile-page[data-page="contacts"] .mf-social-sealed').count()).toBe(1);
  expect(errors).toEqual([]);
});

test.describe('light scheme material', () => {
  test.use({ colorScheme: 'light' });

  // 件七（2026-11-04 扁平化）：材质判据从玻璃包改扁平场（--color-surface 亮档
  // #ffffff）——判据仍是「条底 == token 解析值」的单源对偶，只换 token 面。
  // 换代表 2026-11-06（glass-top-overlays）：token 面换成 --nf-glass-bg（条是
  // 顶部悬浮可透出面 ⇒ 毛玻璃）；亮/暗两档各自解析，判据形式不变。
  test('P10 light: pinned bar fill = --nf-glass-bg 单源（亮档玻璃底）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page, { friends: true });
    await dockTo(page, 'messages');
    const st = await readPinnedBar(page, 'messages');
    expect(st.bg).toBe(st.tokenGlassBg);
    expect(st.tokenGlassBg, '亮档玻璃底 α 落进 [0.45, 0.75]').toMatch(/rgba\(255, 255, 255, 0\.5[0-9]+\)/);
    await dockTo(page, 'contacts');
    const ct = await readPinnedBar(page, 'contacts');
    expect(ct.bg).toBe(ct.tokenGlassBg);
    expect(errors).toEqual([]);
  });
});

test('P10b dark: pinned bar fill = --nf-glass-bg 单源（暗档玻璃底）', async ({ page }) => {
  const errors = watchErrors(page);
  await bootApp(page, { friends: true });
  await dockTo(page, 'messages');
  const st = await readPinnedBar(page, 'messages');
  expect(st.bg).toBe(st.tokenGlassBg);
  expect(st.tokenGlassBg, '暗档玻璃底 α 落进 [0.45, 0.75]').toMatch(/rgba\(24, 28, 38, 0\.6[0-9]+\)/);
  await dockTo(page, 'contacts');
  const ct = await readPinnedBar(page, 'contacts');
  expect(ct.bg).toBe(ct.tokenGlassBg);
  expect(errors).toEqual([]);
});
