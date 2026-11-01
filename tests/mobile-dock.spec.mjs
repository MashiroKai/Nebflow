// mobile-dock.spec.mjs — dock 批主 spec：五栏底 dock + 独立整页导航验收
//（作者令 2026-10-29 13:31「把手机端改成小红书式底部 dock：消息 联系人 对话
// canvas 设置」+ 13:36「canvas 独立整页卡片式切换」+ 13:38「各节目都是独立界面」
// + 23:36 中键改版「中间那个对话 就是进入我跟你的这个输入框…最左侧的消息就是
// 跟联系人的对话消息」）。
// 页面切换与导航修复批（作者令 2026-10-30 20:17 五条）增量：
//   D15 工作台根：第五栏 设置 → 工作台（设置降级为工作台 hub 末行，push 设置页）。
//   D16 工作台误路由修复：hub 行进项目 ⇒ 栈顶恒 projects（canvas 页不抢导航）。
//   D17 页面切换动画退役：无过渡零位移直切（微信口径）。
//   D18 顶部胶囊语言统一：页头无通栏底面、标题玻璃胶囊、返回钮 glass-control
//       悬浮圆、玻璃空间相对图标居中。
//   D19 重复返回钮清查：收编面窗头关闭/返回钮（搜索 X/心跳 X/任务 X/会话 ✕/
//       链视图返回条）页面壳内全隐。
//
// 覆盖：
//   D1  五栏结构与顺序钉：消息/联系人/对话/canvas/设置逐字（作者令序）、
//       chat home 中键 + 常规四栏图标结构、聊天根中键激活、role=tablist。
//   D2  根页切换：dockTo → 页 live + 键 active/aria-selected 镜像；根页互斥
//       （唯一 .live）；中键 = chat home（详情全清回聊天根）；根页「<」回聊天根。
//   D3  徽标镜像：桌面徽标（#messages-badge/#contacts-badge，手机层按钮不可见
//       但 WS 计数流照写）→ dock 红点隐/现零第二判据。
//   D4  canvas 独立页：dock 进页空态（chip 条/工具条隐、空态卡显）；
//       openTab 双卡 → chip 切换/‹ › 步进/X 关卡；#canvas-panel 恒 display:none。
//   D5  封存态（features.friends 缺席）：消息/联系人两面 = 骨架空态卡
//       （.mf-social-sealed）+ 消息页通知段恒渲染；无桌面社交面残留。
//   D6  好友 LIVE（fm_api_mock + features.friends:true）：消息页 = 跟联系人的
//       对话消息列表 → 会话页（.fm-modal 收编）→ 返回；联系人页顶「手机接入」
//       入口撤除（作者令 2026-10-31 22:55，仅手机端）：无入口行、social 详情页
//       不注册、事件环外发无 push 路径；消息键气泡图标。
//   D7  任务页：聊天根顶栏「任务」chrome 键 → 页面板收编（quick：live + 收编 +
//       X 对账弹栈 —— 全量卡渲染见 mobile-v3 T2）。
//   D8  工作台 push：设置根页 hub 行 → 项目页（pane 收编）→ 返回设置根。
//   D9  退役零残留：.mh-dd/.mh-scrim/#mobile-task-key/#canvas-panel 不落地，
//       sheet 族 DOM 零存留，boot 探针 version 含 'dock'。
//   D10 中键形态（暗档）：sapphire 实心圆 44×44 高出基线、圆裁、无头像回落
//       气泡字形（冷启动批⑧ 2026-10-30 从 50 缩一档）；D11 头像两态（种子 avatar 圆图 + identity 变更活链重绘 +
//       清除回落）；D12 亮档 fill = --accent-agent-light 深档 token。
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

/** 静态路由 + WS 夹具。社交三态（功能开放批 2026-10-30 18:22 后语义）：
 *  `friends:false`（缺省）= 缺省开放（未登录 ⇒ 消息/联系人页 = 桌面面登录态）；
 *  `friends:true`  = 好友 LIVE（fm_api_mock + features.friends:true）；
 *  `sealed:true`   = 显式再封（features.friends:false ⇒ 封存空态卡面）。
 *  `unread` = mock 会话未读数（真实未读链夹具，D14 用；默认 0）。
 *  `pendingAsk` = 该会话未读里点名作者行动的条数（认知二期可行动判据夹具，
 *    D14 用；默认 0 —— 纯未读不上徽标，见 messages.js actionableUnread）。 */
async function bootApp(page, { friends = false, unread = 0, pendingAsk = 0, sealed = false } = {}) {
  await page.addInitScript(({ friends: f, unread: u, pendingAsk: pa }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // harness：stub 掉 SW 注册（swReg.js 在 localhost 安全上下文 register /sw.js；
    // 404 会记 console error 混进 errors 断言；D5 封存态二次 boot 同样经此腿）。
    // configurable：二次 boot 的 initScript 重跑时重定义不炸（pageerror 污染断言）。
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
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
            lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '历史消息 #220', createdAt: Date.now() - 60e3 }, unreadCount: u,
            pendingAskCount: pa,
          },
        ],
        messages: {
          'c-p': [
            { id: 220, senderId: 'u-p', kind: 'text', body: '历史消息 #220', createdAt: Date.now() - 60e3 },
          ],
        },
      }));
    }
  }, { friends, unread, pendingAsk, sealed });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      // agent 档案缺省（app 走默认腿；非 2xx 会被浏览器记 console error）。
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
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

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('D1 five-tab dock structure: author-order labels, chat-home center key, icon+label, resident on chat root, tablist semantics', async ({ page }) => {
  const errors = watchErrors(page);
  const dock = await page.evaluate(() => {
    const el = document.getElementById('mobile-dock');
    const keys = [...el.querySelectorAll('.dock-key')];
    return {
      role: el.getAttribute('role'),
      ariaLabel: el.getAttribute('aria-label'),
      keys: keys.map((k) => ({
        id: k.dataset.dock,
        label: k.querySelector('.dock-label')?.textContent ?? null,
        hasIcon: !!k.querySelector('.dock-ic i svg, .dock-ic svg'),
        hasCenter: !!k.querySelector('.dock-center'),
        ariaSelected: k.getAttribute('aria-selected'),
        active: k.classList.contains('active'),
      })),
      activeCount: el.querySelectorAll('.dock-key.active').length,
    };
  });
  expect(dock.role).toBe('tablist');
  // 作者令逐字顺序：消息 / 联系人 / 对话 / 看板 / 工作台（工作台批：设置
  // 自顶层导航降级为工作台成员，第五栏 = 工作台；④改名批 2026-10-31：canvas
  // 中文面统一「看板」，route id = canvas 不变）。
  expect(dock.keys.map((k) => k.label)).toEqual(['消息', '联系人', null, '看板', '工作台']);
  // 收形批②（作者令 10-31 22:50「中间对话钮只留图案」）：中键零可见标签，
  // 语义由 aria-label 承接（图标即语义）。
  expect(dock.keys.find((k) => k.id === 'chat')?.label ?? null).toBe(null);
  expect(await page.evaluate(() =>
    document.querySelector('#mobile-dock .dock-key[data-dock="chat"]')?.getAttribute('aria-label'))).toBe('对话');
  // 中键改版批：第三栏 = chat home 键（对话 = 进主对话输入框；非页面路由）。
  expect(dock.keys.map((k) => k.id)).toEqual(['messages', 'contacts', 'chat', 'canvas', 'workbench']);
  for (const k of dock.keys) {
    if (k.id === 'chat') expect(k.hasCenter, k.id).toBe(true);
    else expect(k.hasIcon, k.id).toBe(true);
  }
  // 聊天根 = home：中键（对话 = chat home 键）激活，其余四键零激活。
  expect(dock.activeCount).toBe(1);
  expect(dock.keys.find((k) => k.id === 'chat')?.active).toBe(true);
  for (const k of dock.keys) expect(k.ariaSelected, k.id).toBe(k.id === 'chat' ? 'true' : 'false');
  expect(errors).toEqual([]);
});

test('D2 root switching: live page mirror, exclusive .live, active key, center key clears details back to chat', async ({ page }) => {
  const errors = watchErrors(page);
  for (const id of ['messages', 'contacts', 'canvas', 'workbench']) {
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
  // 中键 = chat home 键（作者令 23:36「就是进入我跟你的这个输入框」）：详情全清
  // + 回聊天根（无 .live 页 + 中键激活）。
  await dockTo(page, 'workbench');
  await page.locator('.mf-hub-row[data-hub-page="projects"]').click();
  await expect(page.locator('.mobile-page[data-page="projects"]')).toHaveClass(/live/);
  await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
  await expect(page.locator('.mobile-page.live')).toHaveCount(0);
  expect(await page.evaluate(() => window.__nfMobileNav)).toMatchObject({ home: 'chat', top: 'chat', stack: ['chat'] });
  expect(await page.evaluate(() => document.querySelector('#mobile-dock .dock-key[data-dock="chat"]')?.classList.contains('active'))).toBe(true);
  // 批② 2026-10-31（作者令「canvas/工作台/联系人/消息是 dock 一级面板，禁左上角
  // 返回钮」）：dock 根页无「<」——回聊天根 = 点 dock 中键（上段已证）。原「根页
  // < = 回聊天根」腿随返回钮退役删除。
  expect(await page.evaluate(() => document.querySelectorAll('.mobile-page[data-page="workbench"] .mobile-back-btn').length)).toBe(0);
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

test('D13 messages key numeric badge (WeChat style): count mirrors desktop readout, 99+ cap, clears to hidden, top-right of icon', async ({ page }) => {
  const errors = watchErrors(page);
  const keySel = '#mobile-dock .dock-key[data-dock="messages"]';
  const badgeSel = `${keySel} .dock-badge`;
  // 起步：无未读 ⇒ 角标不出现（作者令「有新消息…加数字角标」= 条件显示）。
  expect(await page.locator(badgeSel).isVisible()).toBe(false);
  // 造桌面读数（桌面 setActivityBadge 已归一：>99 ⇒ '99+'，0 ⇒ hidden+空串；
  // 手机层只镜像该读数，零换算 ⇒ 此处置真值后校验镜像）。
  const writeDesktop = (hidden, text) => page.evaluate(({ h, tx }) => {
    let b = document.getElementById('messages-badge');
    if (!b) {
      b = document.createElement('span');
      b.id = 'messages-badge';
      b.className = 'activity-badge';
      document.querySelector('#messages-btn')?.appendChild(b) || document.body.appendChild(b);
    }
    b.hidden = h;
    b.textContent = tx;
  }, { h: hidden, tx: text });

  // ① 出现 + 计数：未读 3 ⇒ 角标可见且读 '3'，形态 = 数字胶囊（is-count）。
  await writeDesktop(false, '3');
  await expect(page.locator(badgeSel)).toBeVisible();
  await expect(page.locator(badgeSel)).toHaveText('3');
  const form = await page.evaluate((sel) => {
    const b = document.querySelector(sel);
    const key = b.closest('.dock-key');
    const br = b.getBoundingClientRect();
    const kr = key.getBoundingClientRect();
    const cs = getComputedStyle(b);
    return {
      isCount: b.classList.contains('is-count'),
      radius: cs.borderRadius,
      color: cs.color,
      bg: cs.backgroundColor,
      // 右上角：角标右缘越过图标中线，且顶缘落在键上部（图标行）。
      rightOfCenter: br.left > kr.left + kr.width / 2,
      inTopHalf: br.top < kr.top + kr.height / 2,
    };
  }, badgeSel);
  expect(form.isCount).toBe(true);
  expect(form.radius).toBe('999px');       // 微信式胶囊（>1 字符时）
  expect(form.color).toBe('rgb(255, 255, 255)');
  expect(form.bg).not.toBe('rgba(0, 0, 0, 0)');
  expect(form.rightOfCenter).toBe(true);   // 「消息按钮的右上角」
  expect(form.inTopHalf).toBe(true);

  // ② 两位数 + 上限：读数 12 ⇒ '12'；99+ 原样镜像（不新造换算）。
  await writeDesktop(false, '12');
  await expect(page.locator(badgeSel)).toHaveText('12');
  await writeDesktop(false, '99+');
  await expect(page.locator(badgeSel)).toHaveText('99+');

  // ③ 清零 ⇒ 角标消失（不是显示 '0'），is-count 摘除复位成纯红点形态。
  await writeDesktop(true, '');
  await expect(page.locator(badgeSel)).toBeHidden();
  expect(await page.evaluate((sel) => {
    const b = document.querySelector(sel);
    return { text: b.textContent, isCount: b.classList.contains('is-count') };
  }, badgeSel)).toEqual({ text: '', isCount: false });

  // ④ 打开会话已读（真实清零腿）：桌面 updateBadge 在 markConvRead 后把徽标
  //    置 hidden+空串 —— 角标随之熄灭（同一条镜像腿，零第二册判据）。
  await writeDesktop(false, '2');
  await expect(page.locator(badgeSel)).toHaveText('2');
  await page.evaluate(() => {
    const b = document.getElementById('messages-badge');
    b.hidden = true;
    b.textContent = '';
  });
  await expect(page.locator(badgeSel)).toBeHidden();
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
      stackBtn: !!pg.querySelector('.mf-cv-stack'),
      popGone: !pg.querySelector('.mf-cv-pop') && !pg.querySelector('.mf-cv-prev'),
      adopted: pg.querySelectorAll('.mf-cv-host .canvas-tab-pane[data-tab-id="dock-b"]').length,
      emptyHidden: pg.querySelector('.mf-cv-empty').hidden,
    };
  });
  expect(st.chips).toEqual([{ id: 'dock-a', on: false }, { id: 'dock-b', on: true }]);
  expect(st.count).toBe('2/2');
  expect(st.stackBtn).toBe(true);
  expect(st.popGone).toBe(true);   // 批⑤：独立窗/步进钮退役
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

test('D7 tasks page via chat chrome key: page live, panel adopted, header back pops (panel X retired in page)', async ({ page }) => {
  const errors = watchErrors(page);
  await page.locator('[data-chrome="tasks"]').click();
  await expect(page.locator('.mobile-page[data-page="tasks"]')).toHaveClass(/live/);
  await settlePage(page, 'tasks');
  // 面板收编进页面体（懒建 + 收编同步腿）。
  await expect(page.locator('.mobile-page[data-page="tasks"] .mobile-page-body > #mobile-task-panel')).toHaveCount(1);
  await expect(page.locator('#mobile-task-panel')).toBeVisible();
  // 重复返回钮清查批：面板头 X 在页面壳内退场（返回 = 页面壳唯一「<」）。
  expect(await page.locator('#mobile-task-panel .tp-x').evaluate((el) => getComputedStyle(el).display)).toBe('none');
  // 「<」关闭 → 弹栈回聊天根。
  await page.locator('.mobile-page[data-page="tasks"] .mobile-back-btn').click();
  await expect(page.locator('#mobile-task-panel')).toBeHidden();
  expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('chat');
  expect(errors).toEqual([]);
});

test('D8 hub rows push detail pages: projects pane adopted, back returns to workbench root', async ({ page }) => {
  const errors = watchErrors(page);
  await dockTo(page, 'workbench');
  const rows = await page.evaluate(() => [...document.querySelectorAll('.mf-hub-row')].map((r) => r.dataset.hubPage));
  // graph 行随 wikiGraphEnabled 门控（夹具无 flag → 封存缺席）；末行 = 设置
  // （工作台批：设置降级为工作台成员）。
  expect(rows).toEqual(['projects', 'plugins', 'usage', 'reminder', 'daemon', 'background', 'settings']);
  await page.locator('.mf-hub-row[data-hub-page="projects"]').click();
  await expect(page.locator('.mobile-page[data-page="projects"]')).toHaveClass(/live/);
  await settlePage(page, 'projects');
  await expect(page.locator(
    '.mobile-page[data-page="projects"] .mobile-page-body .canvas-tab-pane[data-tab-id="projects"]',
  )).toHaveCount(1);
  await page.locator('.mobile-page[data-page="projects"] .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="workbench"]')).toHaveClass(/live/);
  expect(await page.evaluate(() => window.__nfMobileNav?.top)).toBe('workbench');
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

test('D5 sealed state: messages/contacts pages show skeleton empty cards; messages page has NO notification section', async ({ page }) => {
  const errors = watchErrors(page);
  // 功能开放批（2026-10-30 18:22）后 friends 缺省解封 —— 封存态须显式
  // features.friends=false 再封（本用例钉的正是再封面）。
  await bootApp(page, { sealed: true });
  // 好友封存域 = 消息/联系人两面（中键改版批：消息页主体 = 跟联系人的对话消息，
  // 原独立「对话」面退役）。
  for (const id of ['messages', 'contacts']) {
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
  // 通知段退役（作者令 2026-10-30 08:29「这个图的消息的底部，通知是干嘛的，
  // 我觉得不用显示」）：消息页**无**通知段 —— 段容器/标题/空态文案/镜像行
  // 四层零残留，页面体最后子节点 = 封存卡（段曾恒追加在主体面之后）。
  await dockTo(page, 'messages');
  const notify = await page.evaluate(() => {
    const pg = document.querySelector('.mobile-page[data-page="messages"]');
    const body = pg.querySelector('.mobile-page-body');
    const last = body.lastElementChild;
    return {
      sec: pg.querySelectorAll('.mf-notify-sec, .mf-notify-cap, .mf-notify-list, .mf-notify-empty').length,
      msgrow: pg.querySelectorAll('.mf-msgrow').length,
      text: pg.textContent.includes('通知') || pg.textContent.includes('暂无消息通知'),
      lastClass: last ? last.className : '',
      banner: document.querySelectorAll('#notification-banner').length, // 桌面源头仍在（非孤儿数据）
    };
  });
  expect(notify.sec).toBe(0);
  expect(notify.msgrow).toBe(0);
  expect(notify.text).toBe(false);
  expect(notify.lastClass).toContain('mf-social-sealed');
  expect(notify.banner).toBe(1);
  expect(errors).toEqual([]);
});

test('D10 center chat-home key form: sapphire circle 44×44 inside dock bounds, stand-logo fallback (dark scheme; 收形批 10-31 22:50)', async ({ page }) => {
  const errors = watchErrors(page);
  const st = await page.evaluate(() => {
    const key = document.querySelector('#mobile-dock .dock-key[data-dock="chat"]');
    const circle = key?.querySelector('.dock-center');
    if (!key || !circle) return { present: false };
    const r = circle.getBoundingClientRect();
    const dockR = document.getElementById('mobile-dock').getBoundingClientRect();
    const cs = getComputedStyle(circle);
    // token 同源探针（frontend-verification 口径）：等色判据来自同一 var()，
    // 断言与 token 定义解耦 —— fill 恒等于 rgb(var(--sapphire)) 计算值。
    const probe = document.createElement('span');
    probe.style.background = 'rgb(var(--sapphire))';
    document.body.appendChild(probe);
    const tokenColor = getComputedStyle(probe).backgroundColor;
    probe.remove();
    return {
      present: true,
      w: r.width,
      h: r.height,
      circleTop: r.top,
      dockTop: dockR.top,
      inside: r.top >= dockR.top - 0.5,      // 收形批⑤：圆顶不探出 dock 顶缘
      shadow: cs.boxShadow,
      radius: cs.borderRadius,
      bg: cs.backgroundColor,
      tokenColor,
      logo: !!circle.querySelector('img.dock-center-logo'), // 收形批③：回落 = stand logo
      hasAvatarClass: circle.classList.contains('has-avatar'),
    };
  });
  expect(st.present).toBe(true);
  expect(st.w).toBe(44);                  // --dock-center-size（冷启动批⑧ 2026-10-30「缩小一档」50→44）
  expect(st.h).toBe(44);
  expect(st.inside).toBe(true);           // 收形批⑤：中键收回 dock 边界内（lift=0）
  expect(st.shadow).toBe('none');         // 收形批④：边缘收简 = 纯色零浮影零光环
  expect(st.radius).toBe('50%');
  expect(st.bg).toBe(st.tokenColor);      // 暗档 fill = --sapphire 本值（不新造色）
  expect(st.logo).toBe(true);             // 无头像回落 = stand logo（不显示空白/默认人形）
  expect(st.hasAvatarClass).toBe(false);
  expect(errors).toEqual([]);
});

// D13 冷启动批⑧（2026-10-30 作者令「输入框与 dock 无缝贴合」）→ 收形批⑤
//（10-31 22:50「中键收回 dock + 间隙收窄」）：lift 归 0，输入卡底缘与 dock
// 顶缘的缝 = lift = 0（修前 12px 抬升缝随收形收窄为零）。
// 几何恒等式：gap = (dock-h + lift + safe) − (dock-h + safe) = lift。
test('D13 input card flush-fit above dock: gap == --dock-center-lift (lift=0, no protrusion seam)', async ({ page }) => {
  const errors = watchErrors(page);
  const g = await page.evaluate(() => {
    const bar = document.getElementById('input-bar').getBoundingClientRect();
    const dock = document.getElementById('mobile-dock').getBoundingClientRect();
    const probe = document.createElement('span');
    probe.style.marginTop = 'var(--dock-center-lift, 0px)';
    document.body.appendChild(probe);
    const lift = parseFloat(getComputedStyle(probe).marginTop) || 0;
    probe.remove();
    return { gap: dock.top - bar.bottom, lift, barBottom: bar.bottom, dockTop: dock.top };
  });
  expect(g.lift).toBe(0);
  expect(Math.abs(g.gap - g.lift)).toBeLessThanOrEqual(1.5); // 亚像素容差
  expect(errors).toEqual([]);
});

test('D11 center key avatar: seeded agent avatar paints circular img; live repaint on identity change; cleared falls back to stand logo', async ({ page }) => {
  const errors = watchErrors(page);
  // 活链重绘腿：种子写会话头同链存储条目（branding.key('agent_avatar_Nebula')
  // —— 键名走 app 同一 key() 派生，夹具不硬编码品牌前缀）+ 触发
  // agentProfile.paintSlot（nebflow-session-change）⇒ 订阅面（dock 中键）与
  // 会话头同帧同像 —— 零 reload 走真实事件链。
  const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" rx="32" fill="#07C160"/><text x="32" y="42" font-size="28" text-anchor="middle" fill="#fff">K</text></svg>',
  );
  await page.evaluate(async (dataUrl) => {
    const { key } = await import('/js/branding.js');
    localStorage.setItem(key('agent_avatar_Nebula'), JSON.stringify({ dataUrl, sha: 'seed-sha', at: Date.now() }));
    window.dispatchEvent(new CustomEvent('nebflow-session-change', { detail: { sessionId: 'dock-root-session' } }));
  }, AV);
  await expect(page.locator('#mobile-dock .dock-key[data-dock="chat"] .dock-center img.dock-center-img')).toHaveCount(1);
  const withAvatar = await page.evaluate(() => {
    const circle = document.querySelector('#mobile-dock .dock-key[data-dock="chat"] .dock-center');
    const img = circle.querySelector('img');
    return {
      hasAvatar: circle.classList.contains('has-avatar'),
      circular: getComputedStyle(img).borderRadius,
      cover: getComputedStyle(img).objectFit,
      src: img.src.slice(0, 30),
    };
  });
  expect(withAvatar.hasAvatar).toBe(true);
  expect(withAvatar.circular).toBe('50%');   // 圆形裁切
  expect(withAvatar.cover).toBe('cover');
  expect(withAvatar.src.startsWith('data:image/svg+xml')).toBe(true);
  // 清除（他端 reset 收敛同路径：键删除 + 重绘）→ 回落 stand logo（收形批③）。
  await page.evaluate(async () => {
    const { key } = await import('/js/branding.js');
    localStorage.removeItem(key('agent_avatar_Nebula'));
    window.dispatchEvent(new CustomEvent('nebflow-session-change', { detail: { sessionId: 'dock-root-session' } }));
  });
  await expect(page.locator('#mobile-dock .dock-key[data-dock="chat"] .dock-center img.dock-center-logo')).toHaveCount(1);
  expect(await page.evaluate(
    () => !document.querySelector('#mobile-dock .dock-key[data-dock="chat"] .dock-center img.dock-center-img'),
  )).toBe(true);
  expect(errors).toEqual([]);
});

test.describe('center key light scheme', () => {
  test.use({ colorScheme: 'light' });

  test('D12 center fill = light-deep sapphire token (--accent-agent-light); regular keys unchanged', async ({ page }) => {
    const errors = watchErrors(page);
    const st = await page.evaluate(() => {
      const circle = document.querySelector('#mobile-dock .dock-key[data-dock="chat"] .dock-center');
      const probe = document.createElement('span');
      probe.style.background = 'rgb(var(--accent-agent-light))';
      document.body.appendChild(probe);
      const token = getComputedStyle(probe).backgroundColor;
      probe.remove();
      return { bg: getComputedStyle(circle).backgroundColor, token };
    });
    expect(st.bg).toBe(st.token); // 亮档深档（unified panel design §6.2 同款双档）
    expect(errors).toEqual([]);
  });
});

test.describe('friends-ON social pages', () => {
  test.beforeEach(async ({ page }) => {
    await bootApp(page, { friends: true });
  });

  test('D6 friends-ON: messages page = contacts conversation list → conversation page; bubble icon; contacts face + phone-access entry withdrawn (no row, no social page)', async ({ page }) => {
    const errors = watchErrors(page);
    // 消息键图标 = 消息气泡（作者令 23:36「图标换位」：气泡自原中键迁入最左）。
    expect(await page.evaluate(
      () => !!document.querySelector('#mobile-dock .dock-key[data-dock="messages"] .dock-ic svg.lucide-message-circle'),
    )).toBe(true);
    // 消息页 = 跟联系人的对话消息：桌面 #fm-conversations 收编 + 行点击 →
    // 会话页（.fm-modal 收编）→ 返回消息根。
    await dockTo(page, 'messages');
    await expect(page.locator('.mobile-page[data-page="messages"] .mobile-page-body #fm-conversations')).toHaveCount(1);
    const row = page.locator('.mobile-page[data-page="messages"] .mobile-page-body #fm-conversations .fm-conv-row').first();
    await expect(row).toBeVisible({ timeout: 8000 });
    await row.click();
    await expect(page.locator('.mobile-page[data-page="conversation"]')).toHaveClass(/live/);
    await settlePage(page, 'conversation');
    // 会话窗节点收编进会话页体（桌面 renderChatModal 整建语义 —— 窗体在页体内）。
    await expect(page.locator('.mobile-page[data-page="conversation"] .mobile-page-body .fm-modal')).toHaveCount(1);
    await page.locator('.mobile-page[data-page="conversation"] .mobile-back-btn').click();
    await expect(page.locator('.mobile-page[data-page="conversation"]')).not.toHaveClass(/live/);
    await expect(page.locator('.mobile-page[data-page="messages"]')).toHaveClass(/live/);

    // 联系人页：#fm-contacts-body 收编 + 「手机接入」入口撤除批（作者令
    // 2026-10-31 22:55，仅手机端；桌面 #social-btn/#social-overlay 不动）——
    // 页顶无入口行、social 详情页不注册（PAGES 表无 social）、已撤事件环
    // 外发不触发 push（监听腿随批摘除）。
    await dockTo(page, 'contacts');
    await expect(page.locator('.mobile-page[data-page="contacts"] .mobile-page-body #fm-contacts-body')).toHaveCount(1);
    await expect(page.locator('.mf-social-entry')).toHaveCount(0);
    await expect(page.locator('.mobile-page[data-page="social"]')).toHaveCount(0);
    await page.evaluate(() => document.dispatchEvent(new CustomEvent('nf:mobile-social-entry')));
    await expect(page.locator('.mobile-page[data-page="social"]')).toHaveCount(0);
    expect(errors).toEqual([]);
  });
});

test.describe('friends-ON unread chain', () => {
  // 认知二期产品批②角标可行动判据（9f016dfb3；设计稿 §5 P6）：
  // messages 徽标只报「需要作者行动」的量 —— 未读 ∩ 点名行动（pendingAsk/mention）
  // + 跨面板可行动提供者；纯「有更新」不上徽标（新鲜度 ≠ 可行动量）。
  test('D14 actionable unread lights the numeric badge: mock unread=1 + pendingAsk=1 paints "1"; opening the conversation clears it', async ({ page }) => {
    await bootApp(page, { friends: true, unread: 1, pendingAsk: 1 });
    const errors = watchErrors(page);
    const badgeSel = '#mobile-dock .dock-key[data-dock="messages"] .dock-badge';
    // 可行动未读链（mock 会话 unreadCount=1 + pendingAskCount=1 ⇒
    // messages.js actionableUnread()=1 → setActivityBadge('messages-btn') →
    // 手机层镜像），零夹具直写徽标。
    await expect(page.locator(badgeSel)).toBeVisible({ timeout: 8000 });
    await expect(page.locator(badgeSel)).toHaveText('1');
    // 进消息页 → 点会话 → 开窗即已读（markConvRead ⇒ 未读清零 ⇒ 可行动量归零
    // ⇒ 角标熄灭）。
    await dockTo(page, 'messages');
    const row = page.locator('.mobile-page[data-page="messages"] .mobile-page-body #fm-conversations .fm-conv-row').first();
    await expect(row).toBeVisible({ timeout: 8000 });
    await row.click();
    await expect(page.locator('.mobile-page[data-page="conversation"]')).toHaveClass(/live/);
    await expect(page.locator(badgeSel)).toBeHidden({ timeout: 8000 });
    // 返回消息页：会话列表未读数同步归零（角标与行徽标同一条读数额），
    // 且页面无通知段（退役面不复活）。行徽标判据收窄到 .fm-conv-row ——
    // 待办改版批（e51dabcdd）在 #fm-conversations 顶加了入口行（自带恒在
    // DOM 的 hidden .fm-row-badge，不挂 .fm-conv-row），页级宽选会误计。
    await page.locator('.mobile-page[data-page="conversation"] .mobile-back-btn').click();
    await expect(page.locator('.mobile-page[data-page="messages"]')).toHaveClass(/live/);
    expect(await page.evaluate(() => ({
      sec: document.querySelectorAll('.mobile-page[data-page="messages"] .mf-notify-sec').length,
      rowBadge: document.querySelectorAll('.mobile-page[data-page="messages"] .fm-conv-row .fm-row-badge').length,
    }))).toEqual({ sec: 0, rowBadge: 0 });
    expect(errors).toEqual([]);
  });

  test('D14b pure unread (no actionable flag) does NOT light the dock badge; the row face still reports it', async ({ page }) => {
    await bootApp(page, { friends: true, unread: 1 });
    const errors = watchErrors(page);
    // 可行动判据负向锚：unread=1 但无 pendingAsk/mention ⇒ actionableUnread()=0
    // ⇒ 徽标恒隐（「有更新」不上徽标）。
    await expect(page.locator('#mobile-dock .dock-key[data-dock="messages"] .dock-badge')).toBeHidden({ timeout: 8000 });
    // 行面语义不变：会话列表行徽标照报纯未读（行面 ≠ 徽标面，两判据分钉）。
    await dockTo(page, 'messages');
    const row = page.locator('.mobile-page[data-page="messages"] .mobile-page-body #fm-conversations .fm-conv-row').first();
    await expect(row).toBeVisible({ timeout: 8000 });
    await expect(page.locator('.mobile-page[data-page="messages"] .fm-conv-row .fm-row-badge')).toHaveCount(1);
    await expect(page.locator('.mobile-page[data-page="messages"] .fm-conv-row .fm-row-badge')).toHaveText('1');
    expect(errors).toEqual([]);
  });
});

// ── 页面切换与导航修复批（作者令 2026-10-30 20:17 五条）────────────────

test('D15 workbench root: settings demoted to hub row; settings row pushes settings page with adopted modal', async ({ page }) => {
  const errors = watchErrors(page);
  await dockTo(page, 'workbench');
  // 设置行 = hub 末行（图标/标签 = 设置）。
  const settingsRow = page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]');
  await expect(settingsRow).toHaveCount(1);
  await expect(settingsRow.locator('.mf-hub-row-label')).toHaveText('设置');
  await settingsRow.click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  await settlePage(page, 'settings');
  // 设置面收编进设置页体（overlay 载体原位留作状态源）。
  await expect(page.locator('.mobile-page[data-page="settings"] .mobile-page-body > #settings-modal')).toHaveCount(1);
  await expect(page.locator('.mobile-page[data-page="settings"] .mobile-page-title')).toHaveText('设置');
  // 返回 = 弹一层回工作台根（非聊天根）。
  await page.locator('.mobile-page[data-page="settings"] .mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="workbench"]')).toHaveClass(/live/);
  expect(await page.evaluate(() => window.__nfMobileNav)).toMatchObject({ home: 'workbench', top: 'workbench' });
  expect(errors).toEqual([]);
});

test('D16 workbench misroute fix: hub row to projects keeps projects on top (canvas page does not hijack)', async ({ page }) => {
  const errors = watchErrors(page);
  // canvas 自动弹页的启动抑制窗 = 3s（navController bootedAt）——真实用户点击远
  // 在抑制窗之后，等过它再点（误路由只在抑制窗外触发，必须实测该时序）。
  await page.waitForFunction(() => !!window.__nfMobileNav, null, { timeout: 15000 });
  await page.waitForTimeout(3300);
  await dockTo(page, 'workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="projects"]').click();
  await expect(page.locator('.mobile-page[data-page="projects"]')).toHaveClass(/live/);
  await settlePage(page, 'projects');
  // 修前：openProjectsTab 抬 canvas-open 上升沿 ⇒ dockTo('canvas') 把根顶成
  // canvas（用户点工作台行进的是 canvas 空页）。修后：栈顶恒 projects。
  const nav = await page.evaluate(() => ({
    top: window.__nfMobileNav?.top,
    home: window.__nfMobileNav?.home,
    stack: window.__nfMobileNav?.stack,
    canvasLive: document.querySelector('.mobile-page[data-page="canvas"]')?.classList.contains('live'),
    canvasKeyActive: document.querySelector('#mobile-dock .dock-key[data-dock="canvas"]')?.classList.contains('active'),
  }));
  expect(nav.top).toBe('projects');
  expect(nav.home).toBe('workbench');
  expect(nav.stack).toEqual(['workbench', 'projects']);
  expect(nav.canvasLive).toBe(false);
  expect(nav.canvasKeyActive).toBe(false);
  // 项目 pane 收编进项目页体（面板页承载自己的 pane）。
  await expect(page.locator(
    '.mobile-page[data-page="projects"] .mobile-page-body .canvas-tab-pane[data-tab-id="projects"]',
  )).toHaveCount(1);
  expect(errors).toEqual([]);
});

test('D17 page switch animation retired: zero transition, zero transform, direct cut', async ({ page }) => {
  const errors = watchErrors(page);
  const cs = await page.evaluate(() => {
    const pg = document.querySelector('.mobile-page[data-page="workbench"]');
    const s = getComputedStyle(pg);
    const probe = document.createElement('span');
    probe.style.background = 'var(--color-bg, #f5f6f8)';
    document.body.appendChild(probe);
    const pageBg = getComputedStyle(probe).backgroundColor;
    probe.remove();
    return {
      transitionProperty: s.transitionProperty,
      transitionDuration: s.transitionDuration,
      transform: s.transform,
      visibility: s.visibility,
      bg: s.backgroundColor,
      pageBg,
    };
  });
  // 动画退役批：过渡全删（无显式 transition ⇒ 计算值 = all/0s 默认对），
  // 隐藏页无 translateX 位移。
  expect(cs.transitionDuration).toBe('0s');
  expect(cs.transitionProperty).toBe('all'); // 未声明任何过渡（默认初始值）
  expect(cs.transform).toBe('none');
  expect(cs.visibility).toBe('hidden');
  expect(cs.bg).toBe(cs.pageBg);
  // 直切时序：live 翻真同帧即终态（无 0.26s 位移中段可踩）。
  await dockTo(page, 'messages');
  const instant = await page.evaluate(() => {
    const el = document.querySelector('.mobile-page[data-page="workbench"]');
    const live = document.querySelector('.mobile-page.live');
    return {
      hiddenVisibility: getComputedStyle(el).visibility,
      liveVisibility: getComputedStyle(live).visibility,
      liveTransform: getComputedStyle(live).transform,
    };
  });
  expect(instant.hiddenVisibility).toBe('hidden');
  expect(instant.liveVisibility).toBe('visible');
  expect(instant.liveTransform).toBe('none');
  expect(errors).toEqual([]);
});

test('D18 floating chrome band: zero-height transparent band, capsule title centered, 48px g2-fab back at 16/16+safe, icon centered', async ({ page }) => {
  const errors = watchErrors(page);
  // 悬浮钮统一批 2026-10-31 作者令 16:03：页头 = 悬浮 chrome 带（零高透明不拦截），
  // 返回钮归 48px g2-fab 基准（材质 --g2-fab-bg 探针同源），悬浮位 = 聊天根任务键
  // 同几何（top 16+safe / left 16）；标题胶囊带内居中。判据载体 = 设置详情页。
  await dockTo(page, 'workbench');
  await page.locator('.mf-hub-row[data-hub-page="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  const st = await page.evaluate(() => {
    const page = document.querySelector('.mobile-page[data-page="settings"]');
    const head = page.querySelector('.mobile-page-header');
    const title = head.querySelector('.mobile-page-title');
    const back = head.querySelector('.mobile-back-btn');
    const svg = back.querySelector('svg');
    // token 同源探针（frontend-verification 口径）：等值判据取自同一 var()。
    const probe = document.createElement('span');
    probe.style.background = 'var(--g2-fab-bg)';
    document.body.appendChild(probe);
    const fabBg = getComputedStyle(probe).backgroundColor;
    probe.remove();
    const probe2 = document.createElement('span');
    probe2.style.background = 'var(--glass-control-bg)';
    document.body.appendChild(probe2);
    const glassBg = getComputedStyle(probe2).backgroundColor;
    probe2.remove();
    const pr = page.getBoundingClientRect();
    const br = back.getBoundingClientRect();
    const sr = svg.getBoundingClientRect();
    const tr = title.getBoundingClientRect();
    const hs = getComputedStyle(head);
    const ts = getComputedStyle(title);
    const bs = getComputedStyle(back);
    return {
      // 带无通栏底面：零高透明、不拦截指针。
      headBg: hs.backgroundColor,
      headHeight: hs.height,
      headPointerEvents: hs.pointerEvents,
      // 标题 = 玻璃胶囊、带内水平居中（页面中心 ±2px）。
      titleRadius: ts.borderRadius,
      titleBg: ts.backgroundColor,
      titleGlassBg: glassBg,
      titleCenterDx: Math.abs((tr.left + tr.width / 2) - (pr.left + pr.width / 2)),
      // 返回钮 = 48px g2-fab（材质与 chrome 键同源 token 族），悬浮位同 chrome 几何。
      backBg: bs.backgroundColor,
      backFabBg: fabBg,
      backRadius: bs.borderRadius,
      backBox: { w: Math.round(br.width), h: Math.round(br.height) },
      backPos: { top: Math.round(br.top), left: Math.round(br.left) },
      // 玻璃空间相对图标居中（水平+垂直 ≤1px）。
      backDx: Math.abs((sr.left + sr.width / 2) - (br.left + br.width / 2)),
      backDy: Math.abs((sr.top + sr.height / 2) - (br.top + br.height / 2)),
    };
  });
  expect(st.headBg).toBe('rgba(0, 0, 0, 0)');
  expect(st.headHeight).toBe('0px');
  expect(st.headPointerEvents).toBe('none');
  expect(st.titleRadius).toBe('999px');
  expect(st.titleBg).toBe(st.titleGlassBg);
  expect(st.titleCenterDx).toBeLessThanOrEqual(2);
  expect(st.backBg).toBe(st.backFabBg); // 返回钮 = g2-fab 族（统一批新基准）
  expect(st.backRadius).toBe('50%');
  expect(st.backBox).toEqual({ w: 48, h: 48 });
  expect(st.backPos).toEqual({ top: 16, left: 16 }); // safe-area 测试视口 = 0
  expect(st.backDx).toBeLessThanOrEqual(1);
  expect(st.backDy).toBeLessThanOrEqual(1);
  expect(errors).toEqual([]);
});

test('D19 duplicate back/close buttons audited: face-owned header controls hidden inside pages', async ({ page }) => {
  const errors = watchErrors(page);
  // 搜索页（入口 = 聊天根顶栏搜索 chrome 键，非 hub 行）：#search-modal-close
  // 退场（返回 = 页面壳唯一「<」）。
  await page.locator('[data-chrome="search"]').click();
  await expect(page.locator('.mobile-page[data-page="search"]')).toHaveClass(/live/);
  await settlePage(page, 'search');
  expect(await page.locator('#search-modal-close').evaluate((el) => getComputedStyle(el).display)).toBe('none');
  await page.locator('.mobile-page[data-page="search"] .mobile-back-btn').click();
  // 搜索从聊天根推入（chrome 键）——pop 回聊天根；心跳行在工作台根，重进。
  await expect(page.locator('.mobile-page.live')).toHaveCount(0);
  await dockTo(page, 'workbench');
  // 心跳页：#daemon-close-btn 退场。
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="daemon"]').click();
  await expect(page.locator('.mobile-page[data-page="daemon"]')).toHaveClass(/live/);
  await settlePage(page, 'daemon');
  expect(await page.locator('#daemon-close-btn').evaluate((el) => getComputedStyle(el).display)).toBe('none');
  // 合成节点钉 CSS 清查面：会话窗 ✕ / 任务面板 X（页面壳内作用域）/
  // 项目页链视图返回条按钮 —— 同一条退役规则覆盖族。
  const synth = await page.evaluate(() => {
    const body = document.querySelector('.mobile-page[data-page="workbench"] .mobile-page-body');
    const mk = (html) => {
      const wrap = document.createElement('div');
      wrap.innerHTML = html;
      const el = wrap.firstElementChild;
      body.appendChild(el);
      const d = getComputedStyle(el.querySelector('button, span') || el).display;
      el.remove();
      return d;
    };
    return {
      fmClose: mk('<div class="fm-modal"><span class="fm-modal-close"></span></div>'),
      tpX: mk('<div class="mh-tpanel"><button type="button" class="tp-x"></button></div>'),
      flowBack: mk('<div class="flowmap-nav-bar"><button type="button" class="flowmap-back-btn"></button></div>'),
    };
  });
  expect(synth.fmClose).toBe('none');
  expect(synth.tpX).toBe('none');
  expect(synth.flowBack).toBe('none');
  expect(errors).toEqual([]);
});
