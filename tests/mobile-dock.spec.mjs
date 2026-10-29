// mobile-dock.spec.mjs — dock 批主 spec：五栏底 dock + 独立整页导航验收
//（作者令 2026-10-29 13:31「把手机端改成小红书式底部 dock：消息 联系人 对话
// canvas 设置」+ 13:36「canvas 独立整页卡片式切换」+ 13:38「各节目都是独立界面」）。
//
// 覆盖：
//   D1  五栏结构与顺序钉：消息/联系人/对话/canvas/设置逐字（作者令序）、
//       图标+文字结构、chat 根 dock 常驻无激活键、role=tablist。
//   D2  根页切换：dockTo → 页 live + 键 active/aria-selected 镜像；根页互斥
//       （唯一 .live）；根页「<」回聊天根。
//   D3  徽标镜像：桌面徽标（#messages-badge/#contacts-badge，手机层按钮不可见
//       但 WS 计数流照写）→ dock 红点隐/现零第二判据。
//   D4  canvas 独立页：dock 进页空态（chip 条/工具条隐、空态卡显）；
//       openTab 双卡 → chip 切换/‹ › 步进/X 关卡；#canvas-panel 恒 display:none。
//   D5  封存态（features.friends 缺席）：消息/联系人/对话三面 = 骨架空态卡
//       （.mf-social-sealed），无桌面社交面残留。
//   D6  好友 LIVE（fm_api_mock + features.friends:true）：对话列表行 → 会话页
//       （.fm-modal 收编）→ 返回；联系人面 + 「手机接入」行 → social 页 → 返回。
//   D7  任务页：聊天根顶栏「任务」chrome 键 → 页面板收编（quick：live + 收编 +
//       X 对账弹栈 —— 全量卡渲染见 mobile-v3 T2）。
//   D8  工作台 push：设置根页 hub 行 → 项目页（pane 收编）→ 返回设置根。
//   D9  退役零残留：.mh-dd/.mh-scrim/#mobile-task-key/#canvas-panel 不落地，
//       sheet 族 DOM 零存留，boot 探针 version 含 'dock'。
//
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-dock.spec.mjs

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

const SID = 'dock-root-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 静态路由 + WS 夹具（社交封存态：features 缺席 = friends 封存）。 */
async function bootApp(page, { friends = false } = {}) {
  await page.addInitScript(({ friends: f }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (f) {
      localStorage.setItem('fm_api_mock', '1');
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({
        self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
        users: [],
        friends: [
          { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: Date.now() - 86400e3 },
        ],
        incoming: [],
        outgoing: [],
        conversations: [
          {
            conversationId: 'c-p', friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
            lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '历史消息 #220', createdAt: Date.now() - 60e3 }, unreadCount: 0,
          },
        ],
        messages: {
          'c-p': [
            { id: 220, senderId: 'u-p', kind: 'text', body: '历史消息 #220', createdAt: Date.now() - 60e3 },
          ],
        },
      }));
    }
  }, { friends });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (friends && p === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
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
      config: friends ? '{"features":{"friends":true}}' : '{}',
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

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('D1 five-tab dock structure: author-order labels, icon+label, resident on chat root, tablist semantics', async ({ page }) => {
  const errors = watchErrors(page);
  const dock = await page.evaluate(() => {
    const el = document.getElementById('mobile-dock');
    const keys = [...el.querySelectorAll('.dock-key')];
    return {
      role: el.getAttribute('role'),
      ariaLabel: el.getAttribute('aria-label'),
      keys: keys.map((k) => ({
        id: k.dataset.dock,
        label: k.querySelector('.dock-label')?.textContent,
        hasIcon: !!k.querySelector('.dock-ic i svg, .dock-ic svg'),
        ariaSelected: k.getAttribute('aria-selected'),
        active: k.classList.contains('active'),
      })),
      activeCount: el.querySelectorAll('.dock-key.active').length,
    };
  });
  expect(dock.role).toBe('tablist');
  // 作者令逐字顺序：消息 / 联系人 / 对话 / canvas / 设置。
  expect(dock.keys.map((k) => k.label)).toEqual(['消息', '联系人', '对话', 'canvas', '设置']);
  expect(dock.keys.map((k) => k.id)).toEqual(['messages', 'contacts', 'conversations', 'canvas', 'settings']);
  for (const k of dock.keys) expect(k.hasIcon, k.id).toBe(true);
  // 聊天根 = home，dock 常驻但无激活键（aria-selected 全 false）。
  expect(dock.activeCount).toBe(0);
  for (const k of dock.keys) expect(k.ariaSelected, k.id).toBe('false');
  expect(errors).toEqual([]);
});

test('D2 root switching: live page mirror, exclusive .live, active key, root back button returns to chat', async ({ page }) => {
  const errors = watchErrors(page);
  for (const id of ['messages', 'conversations', 'canvas', 'settings']) {
    await dockTo(page, id);
    const st = await page.evaluate((pid) => ({
      live: document.querySelector(`.mobile-page[data-page="${pid}"]`)?.classList.contains('live'),
      liveCount: document.querySelectorAll('.mobile-page.live').length,
      keyActive: document.querySelector(`#mobile-dock .dock-key[data-dock="${pid}"]`)?.classList.contains('active'),
      keySel: document.querySelector(`#mobile-dock .dock-key[data-dock="${pid}"]`)?.getAttribute('aria-selected'),
      top: window.__nfMobileNav?.top,
      home: window.__nfMobileNav?.home,
    }), id);
    expect(st.live, id).toBe(true);
    expect(st.liveCount, id).toBe(1);          // 互斥：各节目独立界面
    expect(st.keyActive, id).toBe(true);
    expect(st.keySel, id).toBe('true');
    expect(st.top, id).toBe(id);
    expect(st.home, id).toBe(id);
  }
  // 根页「<」= 回聊天根（home 复位 + dock 无激活键）。
  await page.locator('.mobile-page[data-page="settings"] .mobile-back-btn').click();
  await expect(page.locator('.mobile-page.live')).toHaveCount(0);
  expect(await page.evaluate(() => window.__nfMobileNav?.home)).toBe('chat');
  expect(await page.evaluate(() => document.querySelectorAll('#mobile-dock .dock-key.active').length)).toBe(0);
  expect(errors).toEqual([]);
});

test('D3 dock badge mirrors desktop badge streams (zero second source)', async ({ page }) => {
  const errors = watchErrors(page);
  // 桌面徽标写数（模拟 WS 计数流）：#messages-badge 出数 → dock 消息键红点现。
  // 注：徽标节点由桌面社交模块按数据建出 —— 封存态 harness 无该数据，此处按
  // 桌面节点形态（id + hidden + text）补建后再写数，镜像判据面不变。
  await page.evaluate(() => {
    for (const id of ['messages-badge', 'contacts-badge']) {
      if (!document.getElementById(id)) {
        const b = document.createElement('span');
        b.id = id;
        b.hidden = true;
        document.body.appendChild(b);
      }
    }
    const m = document.getElementById('messages-badge');
    m.hidden = false;
    m.textContent = '3';
    const c = document.getElementById('contacts-badge');
    c.hidden = false;
    c.textContent = '';
  });
  const badges = await page.evaluate(() => ({
    messages: {
      hidden: document.querySelector('#mobile-dock .dock-key[data-dock="messages"] .dock-badge').hidden,
      text: document.querySelector('#mobile-dock .dock-key[data-dock="messages"] .dock-badge').textContent,
    },
    contacts: document.querySelector('#mobile-dock .dock-key[data-dock="contacts"] .dock-badge').hidden,
  }));
  expect(badges.messages.hidden).toBe(false);  // 有数 → 红点现
  expect(badges.contacts).toBe(true);          // 空串 → 红点隐
  // 清零回流：徽标隐 → 红点隐。
  await page.evaluate(() => { const m = document.getElementById('messages-badge'); m.hidden = true; });
  expect(await page.evaluate(() => document.querySelector('#mobile-dock .dock-key[data-dock="messages"] .dock-badge').hidden)).toBe(true);
  expect(errors).toEqual([]);
});

test('D4 canvas page: empty state on entry, doc cards via openTab (chips/stepper/close), #canvas-panel never displays', async ({ page }) => {
  const errors = watchErrors(page);
  // 空态：首开 canvas 页（无文档卡）= chip 条/工具条隐、空态卡显。
  await dockTo(page, 'canvas');
  const empty0 = await page.evaluate(() => {
    const pg = document.querySelector('.mobile-page[data-page="canvas"]');
    return {
      stripHidden: pg.querySelector('.mf-strip').hidden,
      toolbarHidden: pg.querySelector('.mf-cv-toolbar').hidden,
      hostHidden: pg.querySelector('.mf-cv-host').hidden,
      emptyVisible: getComputedStyle(pg.querySelector('.mf-cv-empty')).display !== 'none',
      panelDisplay: getComputedStyle(document.getElementById('canvas-panel')).display,
    };
  });
  expect(empty0.stripHidden).toBe(true);
  expect(empty0.toolbarHidden).toBe(true);
  expect(empty0.hostHidden).toBe(true);
  expect(empty0.emptyVisible).toBe(true);
  expect(empty0.panelDisplay).toBe('none');

  // 双卡：chip 条两枚 + 工具条 ‹计数› 步进 + X 关卡（详情交互见
  // mobile-canvas-sheet.spec.mjs —— 此处钉「页内呈现/切换」面）。
  await page.evaluate(() => {
    import('/js/canvas.js').then((c) => {
      c.openTab('dock-a', '文档甲', { type: 'dock-a' });
      c.openTab('dock-b', '文档乙', { type: 'dock-b' });
    });
  });
  await page.waitForTimeout(300);
  const st = await page.evaluate(() => {
    const pg = document.querySelector('.mobile-page[data-page="canvas"]');
    const chips = [...pg.querySelectorAll('.mf-strip-chip')].map((c) => ({ id: c.dataset.tabId, on: c.classList.contains('on') }));
    return {
      chips,
      count: pg.querySelector('.mf-cv-count').textContent,
      prevDisabled: pg.querySelector('.mf-cv-prev').disabled,
      adopted: pg.querySelectorAll('.mf-cv-host .canvas-tab-pane[data-tab-id="dock-b"]').length,
      emptyHidden: pg.querySelector('.mf-cv-empty').hidden,
    };
  });
  expect(st.chips).toEqual([{ id: 'dock-a', on: false }, { id: 'dock-b', on: true }]);
  expect(st.count).toBe('2/2');
  expect(st.prevDisabled).toBe(false);
  expect(st.adopted).toBe(1);
  expect(st.emptyHidden).toBe(true);
  // chip tap 切卡 + X 关卡。
  await page.locator('.mf-strip-chip[data-tab-id="dock-a"]').click();
  await expect(page.locator('.mf-cv-count')).toHaveText('1/2');
  await page.locator('.mf-cv-close').click();
  await expect.poll(async () => page.evaluate(async () => (await import('/js/mobile/navController.js')).docTabs().length)).toBe(1);
  expect(await page.evaluate(() => getComputedStyle(document.getElementById('canvas-panel')).display)).toBe('none');
  expect(errors).toEqual([]);
});

test('D7 tasks page via chat chrome key: page live, panel adopted, X close reconciles', async ({ page }) => {
  const errors = watchErrors(page);
  await page.locator('[data-chrome="tasks"]').click();
  await expect(page.locator('.mobile-page[data-page="tasks"]')).toHaveClass(/live/);
  await settlePage(page, 'tasks');
  // 面板收编进页面体（懒建 + 收编同步腿）。
  await expect(page.locator('.mobile-page[data-page="tasks"] .mobile-page-body > #mobile-task-panel')).toHaveCount(1);
  await expect(page.locator('#mobile-task-panel')).toBeVisible();
  // X 关闭 → hidden + 弹栈回聊天根。
  await page.locator('#mobile-task-panel .tp-x').click();
  await expect(page.locator('#mobile-task-panel')).toBeHidden();
  expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('chat');
  expect(errors).toEqual([]);
});

test('D8 hub rows push detail pages: projects pane adopted, back returns to settings root', async ({ page }) => {
  const errors = watchErrors(page);
  await dockTo(page, 'settings');
  const rows = await page.evaluate(() => [...document.querySelectorAll('.mf-hub-row')].map((r) => r.dataset.hubPage));
  // graph 行随 wikiGraphEnabled 门控（夹具无 flag → 封存缺席）。
  expect(rows).toEqual(['projects', 'plugins', 'usage', 'reminder', 'daemon', 'background']);
  await page.locator('.mf-hub-row[data-hub-page="projects"]').click();
  await expect(page.locator('.mobile-page[data-page="projects"]')).toHaveClass(/live/);
  await settlePage(page, 'projects');
  await expect(page.locator(
    '.mobile-page[data-page="projects"] .mobile-page-body .canvas-tab-pane[data-tab-id="projects"]',
  )).toHaveCount(1);
  await page.locator('.mobile-page[data-page="projects"] .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('settings');
  expect(errors).toEqual([]);
});

test('D9 retired floats zero-residue: dd columns/scrim/task-key gone, sheet DOM gone, boot probe version', async ({ page }) => {
  const errors = watchErrors(page);
  const residue = await page.evaluate(() => ({
    ddCols: document.querySelectorAll('.mh-dd').length,
    scrim: document.querySelectorAll('.mh-scrim').length,
    taskKey: document.querySelectorAll('#mobile-task-key').length,
    canvasPanelDisplay: getComputedStyle(document.getElementById('canvas-panel')).display,
    sheetDom: document.querySelectorAll('.mobile-card-head, .mobile-card-switchbar, .mobile-canvas-edgezone').length,
    morphClass: !!document.querySelector('.mh-morph-anim, .mh-morph-close'),
    version: window.__nfMobileNav?.version || '',
  }));
  expect(residue.ddCols).toBe(0);
  expect(residue.scrim).toBe(0);
  expect(residue.taskKey).toBe(0);
  expect(residue.canvasPanelDisplay).toBe('none');
  expect(residue.sheetDom).toBe(0);
  expect(residue.morphClass).toBe(false);
  expect(residue.version).toContain('dock');
  expect(errors).toEqual([]);
});

test('D5 sealed state: contacts/conversations pages show skeleton empty cards; messages page shows the notification feed', async ({ page }) => {
  const errors = watchErrors(page);
  // 好友封存域 = 对话/联系人两面（消息页 = 站内通知流，不分封存态）。
  for (const id of ['contacts', 'conversations']) {
    await dockTo(page, id);
    const st = await page.evaluate((pid) => {
      const pg = document.querySelector(`.mobile-page[data-page="${pid}"]`);
      return {
        sealed: pg.querySelectorAll('.mf-social-sealed').length,
        skels: pg.querySelectorAll('.mf-social-sealed-skel').length,
        // 封存态 = 桌面社交面无收编残留。
        fmConv: pg.querySelectorAll('#fm-conversations').length,
        fmContacts: pg.querySelectorAll('#fm-contacts-body').length,
      };
    }, id);
    expect(st.sealed, id).toBe(1);
    expect(st.skels, id).toBeGreaterThan(0);
    expect(st.fmConv, id).toBe(0);
    expect(st.fmContacts, id).toBe(0);
  }
  // 消息页 = 通知流镜像（无通知 = 空态文案）。
  await dockTo(page, 'messages');
  await expect(page.locator('.mobile-page[data-page="messages"] .mf-msgfeed')).toHaveCount(1);
  await expect(page.locator('.mobile-page[data-page="messages"] .mf-msgfeed-empty')).toHaveText('暂无消息通知');
  expect(errors).toEqual([]);
});

test.describe('friends-ON social pages', () => {
  test.beforeEach(async ({ page }) => {
    await bootApp(page, { friends: true });
  });

  test('D6 friends-ON: conversation row → conversation page adoption; contacts face + phone-access row → social page', async ({ page }) => {
    const errors = watchErrors(page);
    // 对话页：桌面 #fm-conversations 收编 + 行点击 → 会话页（.fm-modal 收编）→ 返回。
    await dockTo(page, 'conversations');
    await expect(page.locator('.mobile-page[data-page="conversations"] .mobile-page-body #fm-conversations')).toHaveCount(1);
    const row = page.locator('.mobile-page[data-page="conversations"] .mobile-page-body #fm-conversations .fm-conv-row').first();
    await expect(row).toBeVisible({ timeout: 8000 });
    await row.click();
    await expect(page.locator('.mobile-page[data-page="conversation"]')).toHaveClass(/live/);
    await settlePage(page, 'conversation');
    // 会话窗节点收编进会话页体（桌面 renderChatModal 整建语义 —— 窗体在页体内）。
    await expect(page.locator('.mobile-page[data-page="conversation"] .mobile-page-body .fm-modal')).toHaveCount(1);
    await page.locator('.mobile-page[data-page="conversation"] .mobile-back-btn').click();
    await expect(page.locator('.mobile-page[data-page="conversation"]')).not.toHaveClass(/live/);
    await expect(page.locator('.mobile-page[data-page="conversations"]')).toHaveClass(/live/);

    // 联系人页：#fm-contacts-body 收编 + 「手机接入」行 → social 页 → 返回。
    await dockTo(page, 'contacts');
    await expect(page.locator('.mobile-page[data-page="contacts"] .mobile-page-body #fm-contacts-body')).toHaveCount(1);
    await page.locator('.mf-social-entry').click();
    await expect(page.locator('.mobile-page[data-page="social"]')).toHaveClass(/live/);
    await settlePage(page, 'social');
    await expect(page.locator('.mobile-page[data-page="social"] .mobile-page-body #social-modal')).toHaveCount(1);
    await page.locator('.mobile-page[data-page="social"] .mobile-back-btn').click();
    await expect(page.locator('.mobile-page[data-page="social"]')).not.toHaveClass(/live/);
    await expect(page.locator('.mobile-page[data-page="contacts"]')).toHaveClass(/live/);
    expect(errors).toEqual([]);
  });
});
