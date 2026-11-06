// profile-settings-batch-acceptance.spec.mjs — profile-settings 批（作者令 2026-11-02）
// **七条机械断言的单点可复跑总闸**：把四件（件一 双描述 / 件二+件三 三会话页头 /
// 件四 设置身份卡 / 件五 联系人设备更新退役）的验收口径收成一支 spec，逐条打印实测
// 读数并断言。各件的深面断言仍由各自 spec 承担（本文件只钉「交付七条」）：
//
//   ① 三会话页头同构 + 无横贯顶栏带          → conversation-heads-unify ①②
//   ② 三会话身份头与主会话视觉一致（≤1px）    → conversation-heads-unify ③
//   ③ 两描述独立可编辑且回读一致              → profile-dual-desc D1/D3
//   ④ 描述进检索（好友 / 群各一次）            → profile-dual-desc D2/D4
//   ⑤ 设置身份卡点击分流正确且无外链跳转        → settings-identity-card T2/T3/T4
//   ⑥ 可改字段改后回读生效 + 无假数据控件        → profile-dual-desc D1/D3 + settings-identity-card T5
//   ⑦ 联系人设备更新入口不存在且无死链          → profile-device-update-retire A–E
//
// ⚠️ ⑥ 的口径（**不造假**）：本批账号服务**唯一可写能力集为空**（见件四能力矩阵：
//    username / nickname / email / avatar / GitHub / Google 六位的写接口逐条 404/502），
//    因此 ⑥ 断言的是「**本批能改的字段全部回读生效**（两个描述字段 × 好友侧 + 群侧）
//    + **零假可编辑控件**（身份卡与资料面 input/select/textarea/[contenteditable]/
//    [role=switch] 计数恒 0）」。缺口已在交付报告"账号服务能力矩阵"逐条列出。
//
// 零 console error 是硬判据：全腿 page.on('console'|'pageerror') 采集，末尾断言空数组。
//
// Self-contained（零后端）：静态路由直服 src/main/resources/web + fm_api_mock 数据面 +
// MockWS 配置/会话帧。手机腿 375×812、桌面腿 1280×800。
//
// Run: node node_modules/@playwright/test/cli.js test tests/profile-settings-batch-acceptance.spec.mjs

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
const SID = 'batch-acceptance-session';
const CONV = '.mobile-page[data-page="conversation"]';

const SELF = {
  id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '',
  displayName: '测试用户', email: 'me@example.com', avatarUrl: '',
};
const PEERS = [{ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true }];

const REMARK0 = '同事 · 后端组';
const BIO0 = '写 Scala，也爱养多肉。';
const REMARK1 = '改后备注甲';
const BIO1 = '改后介绍乙';
const GD0 = '群介绍丙-初始';
const GR0 = '群备注丁-初始';
const BIO_NEEDLE = '量子花园';
const GROUP_NEEDLE = '陨石坑';

const F_MAIN = {
  userId: 'u-f1', neblinkId: 'buddy1', name: '老友', avatarUrl: '',
  remark: REMARK0, bio: BIO0, since: 1762000000000,
};
const G_MAIN = {
  groupId: 'g-1', title: '项目群', role: 'owner', memberCount: 2,
  lastMessage: { id: 101, senderId: 'u-f1', kind: 'text', body: '今天开会', createdAt: 1762000000000 },
  unreadCount: 0, lastMessageId: 101, createdAt: 1762000000000,
  description: GD0, remark: GR0,
};

function seed() {
  const NOW = Date.now();
  /** @type {any[]} */
  const msgs = [];
  for (let i = 1; i <= 40; i += 1) {
    msgs.push({
      id: i, senderId: i % 2 ? 'u-f1' : 'me', kind: 'text',
      body: `第 ${i} 条消息 —— 撑出滚轴`, createdAt: NOW - (40 - i) * 60e3,
    });
  }
  return {
    self: { userId: 'me', username: 'Mashiro', neblinkId: 'me@example.com', name: '我自己', avatarUrl: '', bio: '' },
    users: [],
    friends: [{ ...F_MAIN }],
    incoming: [],
    outgoing: [],
    // 🔴 落地节点（2026-11-02）夹具修正：此处原内联过一条 `g-1` 群行，与下方
    // `groups: [{ ...G_MAIN }]`（GET /api/groups 真值行，带 description/remark）**重复**。
    // `messages.js:527` 的归并是 `directNonDevice.concat(grp.groups)` ⇒ 同一群出两行，
    // 内联那条**不带两个描述字段**（`find()` 取首个 ⇒ 读到空描述），于是 ④-b 群描述
    // 检索恒 0 命中（profile-dual-desc 的夹具没有这条内联项，故那边恒绿）。
    // 群行的唯一数据源 = `groups[]`（与产品同口径），此处只留好友会话行。
    conversations: [
      { conversationId: 'c-f1', friend: { ...F_MAIN }, lastMessage: msgs[msgs.length - 1], unreadCount: 0 },
    ],
    messages: { 'c-f1': msgs, 'g-1': [] },
    groups: [{ ...G_MAIN }],
    groupInvites: [],
    groupMembers: {
      'g-1': [
        { userId: 'me', username: null, display_name: '我自己', avatar: null, role: 'owner', joinedAt: 0 },
        { userId: 'u-f1', username: null, display_name: '老友', avatar: null, role: 'member', joinedAt: 0 },
      ],
    },
    _now: NOW,
  };
}

/** @type {string[]} */
let consoleErrors = [];

async function bootApp(page) {
  const s = seed();
  await page.addInitScript((seedJson) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify(seedJson));
    localStorage.setItem('fm_api_mock_delay', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    // ⑤ 的页面级探针：window.open 换计数桩 ⇒ 任何 JS 跳转都会被记下
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
  }, s);

  const tag = String(page.viewportSize()?.width || 0);
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`${tag}: ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`${tag}: pageerror: ${e.message}`));

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
          body: JSON.stringify({ loggedIn: true, device: SELF, peers: PEERS }),
        });
      }
      if (p === '/api/users/search') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            found: true,
            user: { userId: 'u-self', username: 'Mashiro', display_name: '测试用户', avatar: null },
            relation_status: 'self',
          }),
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

/** 开三会话之一（好友 / 群 / 设备）。 */
async function openConv(page, kind) {
  await dockTo(page, 'messages');
  if (kind === 'device') {
    await page.evaluate(async (peer) => {
      const m = await import('/js/messages.js');
      m.openDeviceChat(peer);
    }, PEERS[0]);
  } else {
    await page.evaluate(async (id) => {
      const m = await import('/js/messages.js');
      await m.openConversation(id, null);
    }, kind === 'group' ? 'g-1' : 'c-f1');
  }
  await expect(page.locator(CONV)).toHaveClass(/live/, { timeout: 10000 });
  await page.waitForSelector(`${CONV} .fm-modal .mh-idhead`, { state: 'attached', timeout: 10000 });
  await settlePage(page, 'conversation');
  await page.evaluate((sel) => {
    const flow = document.querySelector(`${sel} .fm-flow`);
    if (flow) { flow.scrollTop = 0; flow.dispatchEvent(new Event('scroll')); }
  }, CONV);
  await page.waitForTimeout(150);
}

async function closeConv(page) {
  await page.locator(`${CONV} .mobile-back-btn`).click();
  await expect(page.locator(`${CONV} .fm-modal`)).toHaveCount(0, { timeout: 8000 });
  await expect(page.locator(CONV)).not.toHaveClass(/live/);
}

/** fixed 元素扫描（顶栏带判据：height≥40 && width≥90vw && 有背景或描边 && 上半屏）。 */
async function scanBands(page) {
  return page.evaluate(() => {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const rows = [];
    for (const el of document.querySelectorAll('*')) {
      const cs = getComputedStyle(el);
      if (cs.position !== 'fixed') continue;
      if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) continue;
      const b = el.getBoundingClientRect();
      if (!b.width || !b.height) continue;
      const hasBg = cs.backgroundColor !== 'rgba(0, 0, 0, 0)' && cs.backgroundColor !== 'transparent';
      const hasBorder = parseFloat(cs.borderTopWidth) > 0 || parseFloat(cs.borderBottomWidth) > 0
        || parseFloat(cs.borderLeftWidth) > 0 || parseFloat(cs.borderRightWidth) > 0;
      const geom = b.height >= 40 && b.width >= vw * 0.9 && (hasBg || hasBorder);
      rows.push({ sel: el.id ? `#${el.id}` : el.tagName.toLowerCase(), geom, upper: b.top < vh / 2, w: +b.width.toFixed(1), h: +b.height.toFixed(1) });
    }
    return { bands: rows.filter((r) => r.geom && r.upper), exempt: rows.filter((r) => r.geom && !r.upper) };
  });
}

async function headReading(page, sel) {
  return page.evaluate((s) => {
    const head = document.querySelector(s);
    if (!head) return null;
    const avatar = head.querySelector('#agent-avatar-btn, .mh-idhead-avatar > .fm-modal-avatar');
    // 三会话 = `.mh-idhead-name`（件二/三工厂）；主对话 = 迁入的共享节点 `#session-name`
    // （chatChrome 的节点迁移形态，禁第二份文本）—— 两种读法对应同一个视觉槽位。
    const name = head.querySelector('.mh-idhead-name, #session-name');
    const r = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return {
        x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1),
        cx: +(b.x + b.width / 2).toFixed(1), cy: +(b.y + b.height / 2).toFixed(1),
      };
    };
    const ncs = name ? getComputedStyle(name) : null;
    return {
      cls: head.className, position: getComputedStyle(head).position, bg: getComputedStyle(head).backgroundColor,
      avatar: r(avatar), name: r(name),
      nameStyle: ncs ? {
        fontSize: ncs.fontSize, fontWeight: ncs.fontWeight, borderRadius: ncs.borderRadius,
        background: ncs.backgroundColor,
        pad: [ncs.paddingTop, ncs.paddingRight, ncs.paddingBottom, ncs.paddingLeft],
      } : null,
    };
  }, sel);
}

// ════════════════════════════════════════════════════════════════════════
// 手机腿：①② ③ ④-b ⑤ ⑦
// ════════════════════════════════════════════════════════════════════════
test.describe('七条机械断言 · 手机腿（375×812）', () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

  test('①② 三会话页头同构 + 与主对话视觉一致（≤1px）+ 无横贯顶栏带', async ({ page }) => {
    await bootApp(page);

    // 主对话基准
    await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
    await expect(page.locator('#chat > .g2-chat-head')).toHaveCount(1);
    const main = await headReading(page, '#chat > .g2-chat-head');
    const mainBands = await scanBands(page);
    console.log('[①/main] 身份头', JSON.stringify(main));

    const heads = {};
    const bands = {};
    for (const kind of ['friend', 'group', 'device']) {
      await openConv(page, kind);
      heads[kind] = await headReading(page, `${CONV} .fm-modal .mh-idhead`);
      bands[kind] = await scanBands(page);
      const back = await page.evaluate((sel) => {
        const b = document.querySelector(`${sel} .mobile-back-btn`)?.getBoundingClientRect();
        const m = document.querySelector(`${sel} .fm-modal .mh-sess-more`)?.getBoundingClientRect();
        const f = (r) => (r ? { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1), cx: +(r.x + r.width / 2).toFixed(1) } : null);
        return { back: f(b), more: f(m) };
      }, CONV);
      heads[kind].back = back.back;
      heads[kind].more = back.more;
      await closeConv(page);
    }
    console.log('[①] 三会话读数', JSON.stringify(Object.fromEntries(Object.entries(heads).map(([k, h]) => [k, {
      cls: h.cls, back: h.back, more: h.more, avatar: h.avatar, name: h.name, nameStyle: h.nameStyle, position: h.position, bg: h.bg,
    }]))));
    console.log('[②] 顶栏带', JSON.stringify({ main: mainBands.bands, ...Object.fromEntries(Object.entries(bands).map(([k, b]) => [k, b.bands])) }),
      '下半屏豁免(底 dock)', JSON.stringify(mainBands.exempt));

    // ── ① 结构同构 ──
    for (const [kind, h] of Object.entries(heads)) {
      expect(h.back, `① ${kind}：左上返回钮在场`).not.toBeNull();
      expect(h.more, `① ${kind}：右上三点钮在场`).not.toBeNull();
      expect(Math.abs(h.back.w - 48) <= 1 && Math.abs(h.back.h - 48) <= 1, `① ${kind}：返回钮 48×48（读数 ${h.back.w}×${h.back.h}）`).toBe(true);
      expect(Math.abs(h.more.w - 48) <= 1 && Math.abs(h.more.h - 48) <= 1, `① ${kind}：三点钮 48×48（读数 ${h.more.w}×${h.more.h}）`).toBe(true);
    }
    // ── ① 无横贯顶栏带（四场景）──
    expect(mainBands.bands, '① 主对话：无横贯顶栏带').toEqual([]);
    for (const [kind, b] of Object.entries(bands)) {
      expect(b.bands, `① ${kind}：无横贯顶栏带（height≥40 && width≥90vw && 有背景/描边 && 上半屏）`).toEqual([]);
    }

    // ── ② 与主对话视觉一致（≤1px）──
    const mainCx = main.avatar.cx;
    const mainCy = main.avatar.cy;
    const mainW = main.avatar.w;
    for (const [kind, h] of Object.entries(heads)) {
      const dx = Math.abs(h.avatar.cx - mainCx);
      const dy = Math.abs(h.avatar.cy - mainCy);
      expect(Math.abs(h.avatar.w - mainW) <= 1, `② ${kind}：头像外径与主对话差 ≤1px（${h.avatar.w} vs ${mainW}）`).toBe(true);
      expect(dx, `② ${kind}：头像中心 x 与主对话差 ${dx.toFixed(2)}px ≤1px`).toBeLessThanOrEqual(1);
      expect(dy, `② ${kind}：头像中心 y 与主对话差 ${dy.toFixed(2)}px ≤1px`).toBeLessThanOrEqual(1);
      expect(h.position, `② ${kind}：身份头 absolute（非 fixed 顶栏）`).toBe('absolute');
      expect(h.bg, `② ${kind}：身份头本体零背景`).toBe('rgba(0, 0, 0, 0)');
      expect(h.nameStyle.fontSize, `② ${kind}：胶囊字号 = 主对话 ${main.nameStyle.fontSize}`).toBe(main.nameStyle.fontSize);
      expect(h.nameStyle.borderRadius, `② ${kind}：胶囊圆角 = 主对话 ${main.nameStyle.borderRadius}`).toBe(main.nameStyle.borderRadius);
      expect(h.nameStyle.background, `② ${kind}：胶囊背景 = 主对话 ${main.nameStyle.background}`).toBe(main.nameStyle.background);
      const pillDev = Math.abs(h.name.cx - h.avatar.cx);
      expect(pillDev, `② ${kind}：胶囊中心 vs 头像中心差 ${pillDev.toFixed(2)}px ≤1px`).toBeLessThanOrEqual(1);
    }
  });

  test('⑤ 设置身份卡点击分流（胶囊→资料面 / 头像→换头像）+ 无外链跳转', async ({ page }) => {
    await bootApp(page);
    await dockTo(page, 'workbench');
    await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
    await page.waitForSelector('#settings-identity-card', { timeout: 15000 });
    await expect(page.locator('.settings-identity-username')).toHaveText('Mashiro', { timeout: 10000 });

    // 胶囊 → 资料面（App 内）
    await page.locator('#settings-identity-capsule').click();
    await expect(page.locator('#settings-profile-overlay')).toBeVisible({ timeout: 8000 });
    const face = await page.evaluate(() => {
      const ov = document.getElementById('settings-profile-overlay');
      const f = (id) => ov?.querySelector(`.sp-field[data-sp-field="${id}"] .sp-value`)?.textContent?.trim() || '';
      return {
        cls: ov?.className || '', username: f('username'), nickname: f('nickname'), email: f('email'),
        github: f('github'), google: f('google'),
        editable: ov ? ov.querySelectorAll('input, select, textarea, [contenteditable], [role="switch"]').length : -1,
        anchors: ov ? ov.querySelectorAll('a').length : -1,
      };
    });
    console.log('[⑤] 资料面读数', JSON.stringify(face));
    expect(face.username, '⑤ 资料面 username = 真实读路值').toBe('Mashiro');
    expect(face.github, '⑤ GitHub 绑定 = 未知（上游无接口，禁造假态）').toBe('未知');
    expect(face.google, '⑤ Google 绑定 = 未知（上游无接口）').toBe('未知');
    await page.locator('#settings-profile-overlay > .mfs-back').click();
    await expect(page.locator('#settings-profile-overlay')).toBeHidden();

    // 头像 → 换头像流程（file chooser），且不开资料面
    const chooser = page.waitForEvent('filechooser', { timeout: 8000 });
    await page.locator('#settings-avatar-entry').click();
    await chooser;
    await page.keyboard.press('Escape');
    await expect(page.locator('#settings-profile-overlay')).toBeHidden();

    const audit = await page.evaluate(() => ({
      openCalls: (/** @type {any} */ (window)).__openCalls.length,
      portalHrefs: [...document.querySelectorAll('a[href]')].map((a) => a.getAttribute('href') || '')
        .filter((h) => /mystand\.space/i.test(h)),
      cardAnchors: document.getElementById('settings-identity-card')?.querySelectorAll('a').length ?? -1,
    }));
    console.log('[⑤] 外链审计', JSON.stringify(audit));
    expect(audit.openCalls, '⑤ 全程 window.open 调用 0 次').toBe(0);
    expect(audit.portalHrefs, '⑤ 零指向官网账号页的 <a href>').toEqual([]);
    expect(audit.cardAnchors, '⑤ 身份卡内零 <a> 元素').toBe(0);
  });

  test('④-b 群描述进检索 + ③⑥ 群两描述独立可编辑且回读一致', async ({ page }) => {
    await bootApp(page);
    await openConv(page, 'group');

    // 群三点菜单 → 群设置 → 查看资料（唯一入口，禁第二套）
    await page.locator(`${CONV} .mh-sess-more`).click();
    await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
    await page.locator('.fm-context-menu button').filter({ hasText: '群设置' }).first().click();
    await page.waitForSelector(`${CONV} .fm-group-settings`, { timeout: 6000 });
    await page.locator(`${CONV} .fm-group-settings .fm-gs-profile`).click();
    await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });

    const read = () => page.evaluate(() => {
      const out = {};
      for (const el of document.querySelectorAll('#profile-face-overlay [data-pf-input]')) {
        out[/** @type {HTMLElement} */ (el).dataset.pfInput] = /** @type {HTMLInputElement} */ (el).value;
      }
      return out;
    });
    const set = async (f, v) => {
      const i = page.locator(`#profile-face-overlay [data-pf-input="${f}"]`);
      await i.fill(v);
      await page.locator(`#profile-face-overlay [data-pf-save="${f}"]`).click();
      await expect(i).toHaveValue(v, { timeout: 5000 });
    };

    const initial = await read();
    expect(initial, '③ 群 Profile 初值 = wire 两个描述').toEqual({ remark: GR0, description: GD0 });

    await set('remark', '群备注-独特串');
    const afterR = await read();
    expect(afterR.remark, '③ 改我的群备注 ⇒ 回读一致').toBe('群备注-独特串');
    expect(afterR.description, '③ 改备注不覆盖群介绍（两字段独立）').toBe(GD0);

    await set('description', GROUP_NEEDLE);
    const afterD = await read();
    expect(afterD.description, '③ 改群介绍 ⇒ 回读一致').toBe(GROUP_NEEDLE);
    expect(afterD.remark, '③ 改群介绍不覆盖我的群备注').toBe('群备注-独特串');
    console.log('[③] 群 Profile 回读', JSON.stringify({ initial, afterR, afterD }));

    await page.locator('#profile-face-close').click();
    await expect(page.locator('#profile-face-overlay')).toBeHidden();
    // 关群窗回到消息页（常驻搜索条所在页）——与 profile-dual-desc D4 同一条出口。
    await page.locator(`${CONV} .mobile-back-btn`).click();
    await expect(page.locator(`${CONV} .fm-modal`)).toHaveCount(0, { timeout: 8000 });
    await settlePage(page, 'messages');

    // ④-b 常驻搜索条：群介绍命中「资料命中」组
    const input = page.locator('.mobile-page[data-page="messages"] .mh-search-input');
    await expect(input, '④-b 常驻搜索条在消息页').toBeVisible({ timeout: 8000 });
    // 🔴 落地节点（2026-11-02）测试侧去时序耦合（与 profile-dual-desc D4 同一根因）：
    // 常驻条检索是 **300ms 防抖**任务，且 `fm-groups-changed ⇒ refreshConversations()`
    // 是**异步** REST 重取（搜索面读的 conversationSnapshot 可能仍是旧描述）。
    // 两条并发腿在并发批次里偶发「防抖落在重取之前」⇒ 0 命中；单跑恒绿 = 典型未等
    // 异步。处理：先等快照读到新值，再轮询式**重填**（每次 fill 重跑一次防抖检索），
    // 直到命中行出现 —— 断言面不变（仍是「描述进检索」），不赌单次防抖窗口。
    await expect.poll(async () => page.evaluate(async () => {
      const m = await import('/js/messages.js');
      const c = m.conversationSnapshot().find((x) => x.conversationId === 'g-1');
      return c ? String(c.description || '') : '';
    }), { timeout: 10000, message: '群会话快照已随 fm-groups-changed 重取到新群介绍' }).toBe(GROUP_NEEDLE);
    const searchUntil = async (q, sel) => {
      for (let i = 0; i < 12; i += 1) {
        await input.fill(q);
        if (await page.locator(sel).count()) return true;
        await page.waitForTimeout(400);
      }
      return (await page.locator(sel).count()) > 0;
    };
    expect(await searchUntil(GROUP_NEEDLE, '.mh-sr-row-desc'), '④-b 群介绍命中 ⇒ 资料命中行').toBe(true);
    await expect(page.locator('.mh-sr-row-desc').first()).toContainText('项目群');
    console.log('[④/b] 群介绍命中行数', await page.locator('.mh-sr-row-desc').count());

    expect(await searchUntil('群备注-独特串', '.mh-sr-row-desc'), '④-b 我的群备注同样命中').toBe(true);

    // 否定面：无关串零命中（防恒命中假绿）—— 防抖窗口后仍须为 0。
    await input.fill('绝无此串zzz');
    await page.waitForTimeout(700);
    await expect(page.locator('.mh-sr-row-desc')).toHaveCount(0);
  });

  test('⑦ 联系人设备更新入口不存在 + 无死链 + 行激活不回退', async ({ page }) => {
    await bootApp(page);
    await dockTo(page, 'contacts');
    await page.locator('[data-devices-entry="1"]').click();
    await expect(page.locator('[data-device-section="1"]')).toHaveCount(1, { timeout: 6000 });

    const read = await page.evaluate(() => {
      const sec = document.querySelector('[data-device-section="1"]');
      const rows = [...sec.querySelectorAll('.fm-device-row')];
      return {
        rows: rows.length,
        kidCounts: rows.map((r) => r.children.length),
        updateBtns: document.querySelectorAll('[data-device-update-btn], .neblink-peer-update-btn').length,
        updateStates: document.querySelectorAll('[data-device-update-state]').length,
        updateText: rows.filter((r) => /更新|Update/i.test(r.textContent || '')).length,
        anchors: [...sec.querySelectorAll('a[href]')].map((a) => a.getAttribute('href')),
        names: rows.map((r) => r.querySelector('.neblink-peer-name')?.textContent || ''),
      };
    });
    console.log('[⑦] 设备段读数', JSON.stringify(read));
    expect(read.updateBtns, '⑦ 更新钮命中数 = 0').toBe(0);
    expect(read.updateStates, '⑦ 行内更新状态文本命中数 = 0').toBe(0);
    expect(read.updateText, '⑦ 含「更新」文案的设备行 = 0（不是留按钮只换文案）').toBe(0);
    expect(read.anchors, '⑦ 设备段内零 <a href>（无死链）').toEqual([]);
    expect(read.kidCounts.every((n) => n === 4), `⑦ 每行四件（读数 ${JSON.stringify(read.kidCounts)}）`).toBe(true);
    expect(read.names).toEqual(['Mashiro-Mac', 'KAI']);

    // 行激活不回退：点 KAI 行仍开设备会话窗
    await page.locator('[data-device-section="1"] .fm-device-row').nth(1).click();
    await expect(page.locator(`${CONV} .fm-modal .fm-modal-name`)).toHaveText('KAI', { timeout: 8000 });
    await expect(page.locator(`${CONV} .fm-modal [data-device-update-btn]`)).toHaveCount(0);
  });

  test('零 console error（手机腿全流程）', async () => {
    expect(consoleErrors, `手机腿 console/pageerror 必须为空（读数 ${consoleErrors.length}）`).toEqual([]);
  });
});

// ════════════════════════════════════════════════════════════════════════
// 桌面腿：③ ④-a ⑥（能改的字段全部回读生效 + 零假控件）
// ════════════════════════════════════════════════════════════════════════
test.describe('七条机械断言 · 桌面腿（1280×800）', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('③⑥ 好友两描述独立可编辑回读一致 + 零假可编辑控件；④-a 描述进检索', async ({ page }) => {
    await bootApp(page);
    await page.locator('#contacts-btn').click();
    await page.waitForSelector('#fm-contacts-body .fm-friend-row', { timeout: 15000 });

    const open = async (name) => {
      const row = page.locator(`.fm-friend-row:has(.fm-row-name:text-is("${name}"))`).first();
      await row.click({ button: 'right' });
      await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
      await page.locator('.fm-context-menu button').filter({ hasText: '查看资料' }).first().click();
      await page.waitForSelector('#profile-face-overlay:not([hidden]) .pf-modal', { timeout: 6000 });
    };
    const read = () => page.evaluate(() => {
      const out = {};
      for (const el of document.querySelectorAll('#profile-face-overlay [data-pf-input]')) {
        out[/** @type {HTMLElement} */ (el).dataset.pfInput] = /** @type {HTMLInputElement} */ (el).value;
      }
      return out;
    });
    const set = async (f, v) => {
      const i = page.locator(`#profile-face-overlay [data-pf-input="${f}"]`);
      await i.fill(v);
      await page.locator(`#profile-face-overlay [data-pf-save="${f}"]`).click();
      await expect(i).toHaveValue(v, { timeout: 5000 });
    };
    const close = async () => {
      await page.locator('#profile-face-close').click();
      await expect(page.locator('#profile-face-overlay')).toBeHidden();
      await page.waitForTimeout(350);
    };

    await open(REMARK0);
    const initial = await read();
    expect(initial, '③ 好友 Profile 初值 = wire 两个描述').toEqual({ remark: REMARK0, bio: BIO0 });

    await set('remark', REMARK1);
    const afterR = await read();
    expect(afterR.remark, '③ 改我的备注 ⇒ 回读一致').toBe(REMARK1);
    expect(afterR.bio, '③ 改备注不覆盖自我介绍').toBe(BIO0);

    await set('bio', BIO1);
    const afterB = await read();
    expect(afterB.bio, '③ 改自我介绍 ⇒ 回读一致').toBe(BIO1);
    expect(afterB.remark, '③ 改自我介绍不覆盖我的备注').toBe(REMARK1);
    console.log('[③/好友] 回读', JSON.stringify({ initial, afterR, afterB }));

    // ⑥ 零假可编辑控件：两个描述是**真能改**的字段；身份面（只读面）零控件另测。
    const controls = await page.evaluate(() => {
      const ov = document.getElementById('profile-face-overlay');
      const inputs = [...ov.querySelectorAll('[data-pf-input]')].map((e) => /** @type {HTMLInputElement} */ (e).dataset.pfInput);
      const saves = [...ov.querySelectorAll('[data-pf-save]')].map((e) => /** @type {HTMLElement} */ (e).dataset.pfSave);
      return { inputs, saves };
    });
    console.log('[⑥] 控件面', JSON.stringify(controls));
    expect(controls.inputs, '⑥ 可编辑控件 = 恰好两个描述字段（无多余假控件）').toEqual(['remark', 'bio']);
    expect(controls.saves, '⑥ 每个可编辑字段一枚保存键').toEqual(['remark', 'bio']);
    // 保存键在提交期间短暂 disabled（`commit()` 的 in-flight 闸）⇒ 等它回落再断言
    // 「无一枚假禁用态」；evaluate 取的是此刻读数，改用轮询。
    await expect.poll(async () => page.evaluate(
      () => document.querySelectorAll('#profile-face-overlay [data-pf-save][disabled]').length,
    ), { timeout: 5000, message: '⑥ 保存键提交后回落为可点（无残留假禁用态）' }).toBe(0);

    // 关面重开 ⇒ 落库值（不是只活在输入框里）
    await close();
    await open(REMARK1);
    const reopened = await read();
    expect(reopened, '⑥ 重开面回读 = 落库值（关面不丢）').toEqual({ remark: REMARK1, bio: BIO1 });
    await close();

    // ④-a 好友侧描述进检索
    const rowsFor = async (q) => {
      await page.evaluate(async (query) => {
        const m = await import('/js/contacts.js');
        await m.submitSearch(query, { throttle: false });
      }, q);
      await page.waitForTimeout(150);
      return page.$$eval('#fm-contacts-body .fm-friend-row .fm-row-name', (els) => els.map((e) => e.textContent || ''));
    };
    await open(REMARK1);
    await set('bio', BIO_NEEDLE);
    await close();
    const hit = await rowsFor(BIO_NEEDLE);
    console.log('[④/a] 好友自我介绍命中', JSON.stringify(hit));
    expect(hit, '④-a 好友自我介绍进检索（命中且唯一）').toEqual([REMARK1]);
    const miss = await rowsFor('绝无此串zzz');
    expect(miss, '④-a 无关串 ⇒ 零命中（过滤真在生效）').toEqual([]);
  });

  test('零 console error（桌面腿全流程）', async () => {
    expect(consoleErrors, `桌面腿 console/pageerror 必须为空（读数 ${consoleErrors.length}）`).toEqual([]);
  });
});
