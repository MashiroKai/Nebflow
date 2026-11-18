// mobile-topbar.spec.mjs — 手机端工作台顶栏批 spec（作者令 2026-10-31 15:31，四件）：
//   ① 三个 tab 根页（消息/联系人/工作台，canvas 同族）顶部标题文字全去掉——
//      无页头/标题胶囊，内容上移（mobile-page-nohead 壳级安全区顶距）。
//   ② 设置页头像入口挪到工作台顶部（圆形头像，与设置页现头像同源同态）。
//      ⚠️ 2026-11-02 件四翻新：「设置页原头像位同步撤掉（仅手机层）」已被作者新令
//      推翻 —— 设置页身份卡两档共用同一 DOM、手机层也在场（头像在上 + 用户名/
//      邮箱胶囊）；设备区仍在联系人页（不搬回）。
//      ⚠️ 2026-11-07 account-hero-dedupe 批（commit 488d8f8bb，作者令「手机端工作台
//      上面的账户 做出 hero 那种感觉 并且去重 把设置的统一到这里」）再推翻上面那条：
//      设置页**整件**不再复述身份（`settingsIdentityCardHTML()` 已无调用点，位置留
//      一行指路 `[data-acct-dedup-hint]`）—— 身份与头像的唯一权威展示 = 工作台 hero
//      （`.mf-me-entry`）。T2 判据按此现状改写（2026-11-18 修残批：旧判据在本 spec
//      里已经红了，且红的原因不是产品坏了，是判据停留在 2026-11-02 口径）。
//   ③ 工作台列表「设备」行去掉（设置账号段设备区手机层不渲染），联系人页
//      设备段完好（data-devices-entry 在位）。
//   ④ 点头像进个人主页：me 页（苹果层级式铺满屏、左上「<」返回 = 既有详情页壳）；
//      字段 = 账号服务真实读数。
//      ⚠️ 2026-11-02 件四翻新：四页零官网出口（旧 `.mf-me-portal` 键已退役），
//      编辑入口 = App 内个人资料面（与设置页胶囊同一面）。
//      ⚠️ 2026-11-18 profile-multidevice 批（作者令「单页资料 / 只读身份 / 设备清单
//      含逐设备退出 / 无空动作、无子页」）：me-user / me-avatar / me-bind 三个层级
//      子页与「编辑资料」空动作**整族退役**（导航表里也不存在 ⇒ pushPage 到不了）；
//      me 页收成单页 = 资料卡 + 只读身份读数 + 官网编辑指引（纯文本）+ 设备清单
//      （逐设备独立退出）。T3 判据随之改写 —— 旧判据断言的正是这批已退役的子页。
//
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-topbar.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'topbar-session';

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark' });

/** 静态路由 + WS 夹具；friends=true 时联系人/消息面 LIVE（fm_api_mock），
 *  neblink status 恒返回已登录账号读数（displayName/email/avatarUrl/peers）。 */
async function bootApp(page, { friends = false } = {}) {
  await page.addInitScript(({ friends: f }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    if (f) {
      localStorage.setItem('fm_api_mock', '1');
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({
        self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
        users: [],
        friends: [],
        incoming: [],
        outgoing: [],
        conversations: [],
        messages: {},
      }));
    }
  }, { friends });
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
      if (p === '/api/neblink/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
          loggedIn: true,
          device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' },
          peers: [{ deviceId: 'd2', deviceName: '笔记本', platform: 'windows', userDescription: '', avatarUrl: '', online: true }],
        }) });
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
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(400);
}

test('T1 根页顶栏退役：消息/联系人/工作台/canvas 无页头无标题胶囊，详情页顶栏不变', async ({ page }) => {
  await bootApp(page);
  for (const id of ['messages', 'contacts', 'workbench', 'canvas']) {
    await dockTo(page, id);
    const pg = page.locator(`.mobile-page[data-page="${id}"]`);
    await expect(pg).toHaveClass(/mobile-page-nohead/); // 壳级安全区顶距标记
    await expect(pg.locator('.mobile-page-header')).toHaveCount(0);
    await expect(pg.locator('.mobile-page-title')).toHaveCount(0);
  }
  // 详情页（设置）顶栏只剩「<」圆钮（大标题退役（ui-unify-panels B 批 2026-11-17 作者令「一级与次级面板都不渲染大标题」）；
  // 先回工作台根——根页互斥，canvas 在位时工作台 hub 行不可点）。
  await dockTo(page, 'workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  await expect(page.locator('.mobile-page[data-page="settings"] .mobile-page-title')).toHaveCount(0);
  await expect(page.locator('.mobile-page[data-page="settings"] .mobile-back-btn')).toHaveCount(1);
});

test('T2 头像搬家：工作台顶部 hero 是身份唯一展示，设置页不复述身份（手机层）', async ({ page }) => {
  await bootApp(page, { friends: true });
  await dockTo(page, 'workbench');
  // 工作台顶头像入口：圆形双态骨架（logo/photo 同设置页字段名族）+ 名/副行。
  const entry = page.locator('.mobile-page[data-page="workbench"] .mf-me-entry');
  await expect(entry).toHaveCount(1);
  await expect(entry.locator('.mf-me-avatar-frame')).toBeVisible();
  await expect(entry.locator('.mf-me-avatar-logo')).toHaveCount(1);
  await expect(entry.locator('.mf-me-avatar-photo')).toHaveCount(1);
  // 读数 = neblink status 账号字段（displayName/email）。
  await expect(entry.locator('.mf-me-name')).toHaveText('测试用户');
  await expect(entry.locator('.mf-me-sub')).toHaveText('me@example.com');
  // ⚠️ 2026-11-18 修残（本 spec 修前红、且与产品现状矛盾）：旧判据断言设置页
  // 「身份卡在场（头像 + 用户名/邮箱胶囊）」，但 author 令 2026-11-07（commit
  // 488d8f8bb，逐字「手机端工作台上面的账户 做出 hero 那种感觉 并且去重 把设置的
  // 统一到这里」）已把身份卡**整件**移出设置页 —— `sidebar.js renderSettings` 不再
  // 调 `settingsIdentityCardHTML()`（导入还在，但已无调用点），位置只留一行指路
  // `[data-acct-dedup-hint]`。故新判据 = 身份展示只有一处（工作台 hero），设置页
  // 零身份复述 + 零设备槽；设备段仍只在联系人页。
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  await expect(page.locator('#settings-identity-card')).toHaveCount(0);
  await expect(page.locator('#settings-avatar-entry')).toHaveCount(0);
  await expect(page.locator('#settings-identity-capsule')).toHaveCount(0);
  await expect(page.locator('#settings-devices-slot')).toHaveCount(0);
  await expect(page.locator('#settings-modal .neblink-peer')).toHaveCount(0);
  // 账号段保留的只有**功能分项**（既有账号卡 = 登录账号读数 + 退出登录），不是身份复述。
  await expect(page.locator('#settings-content #account-card')).toHaveCount(1);
  await expect(page.locator('#settings-content #neblink-logout')).toHaveCount(1);
  await page.locator('.mobile-page[data-page="settings"] .mobile-back-btn').click();
  // 联系人页设备段完好（设备只在联系人展示）。
  await dockTo(page, 'contacts');
  await expect(page.locator('.mobile-page[data-page="contacts"] .fm-devices-entry[data-devices-entry="1"]')).toHaveCount(1);
});

test('T3 个人主页单页：子页与空动作整族退役，身份读数与设备清单为真实读数', async ({ page }) => {
  await bootApp(page);
  await dockTo(page, 'workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  const me = page.locator('.mobile-page[data-page="me"]');
  await expect(me).toHaveClass(/live/);

  // ① 单页（2026-11-18 profile-multidevice 批）：三个层级子页入口行与「编辑资料」
  //    空动作整族退役 —— 不是隐藏（行不在场，点不到）。
  await expect(me.locator('[data-me-page]')).toHaveCount(0);
  await expect(me.locator('#me-edit-profile')).toHaveCount(0);
  await expect(me.locator('.mf-me-portal')).toHaveCount(0);

  // ② 页面成员（自上而下）：资料卡（共享居中身份结构 = check-panel-uniform 登记判据）。
  await expect(me.locator('.mf-me-card.sp-identity')).toHaveCount(1);
  await expect(me.locator('.mf-me-avatar-frame.huge')).toHaveCount(1);
  await expect(me.locator('.mf-me-avatar-logo')).toHaveCount(1);
  await expect(me.locator('.mf-me-avatar-photo')).toHaveCount(1);

  // ③ 只读身份读数（件四口径不变）：字段 = 账号服务真实读数；零可编辑控件、
  //    零 <a href>、官网编辑指引为纯文本。
  await expect(me.locator('input, select, textarea, [contenteditable="true"], [role="switch"]')).toHaveCount(0);
  await expect(me.locator('a[href]')).toHaveCount(0);
  await expect(me.locator('.mf-me-hint')).toContainText('mystand.space');
  // 字段序（meFace 单页）：用户名 / 昵称 / 邮箱 / GitHub 绑定 / Google 绑定。
  // 用户名 = 自证探针（GET /api/users/search 且 relation_status==='self'）；本夹具未
  // stub 该端点 ⇒ **未知**（禁拿昵称/邮箱冒充）；昵称/邮箱 = status 的 id_token claims。
  const values = me.locator('.mf-me-field-value');
  await expect(values).toHaveCount(5);
  await expect(values.nth(0)).toHaveText('未知');
  await expect(values.nth(1)).toHaveText('测试用户');
  await expect(values.nth(2)).toHaveText('me@example.com');
  await expect(values.nth(3)).toHaveText('未知');
  await expect(values.nth(4)).toHaveText('未知');

  // ④ 设备清单（读数 = neblink status 夹具：本机 d1 + 对端 d2）：本机在首位并带
  //    「本机」标记；退出键各带**该行自己的** deviceId。
  const rows = me.locator('[data-device-list="1"] .pd-device-row');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toHaveAttribute('data-device-id', 'd1');
  await expect(rows.nth(0)).toHaveAttribute('data-device-current', '1');
  await expect(rows.nth(0).locator('.pd-device-name-text')).toHaveText('本机');
  await expect(rows.nth(0).locator('.pd-device-badge')).toHaveText('本机');
  await expect(rows.nth(1)).toHaveAttribute('data-device-id', 'd2');
  await expect(rows.nth(1).locator('.pd-device-name-text')).toHaveText('笔记本');
  await expect(rows.nth(1).locator('.pd-device-meta')).toContainText('在线');
  await expect(rows.nth(0).locator('[data-device-signout]')).toHaveAttribute('data-device-signout', 'd1');
  await expect(rows.nth(1).locator('[data-device-signout]')).toHaveAttribute('data-device-signout', 'd2');

  // ⑤ 苹果层级壳不变：左上「<」回工作台根页（大标题仍退役）。
  await expect(me.locator('.mobile-page-title')).toHaveCount(0);
  await me.locator('.mobile-back-btn').click();
  await expect(page.locator('.mobile-page[data-page="workbench"]')).toHaveClass(/live/);

  // ⑥ 退役必须是真的到不了：导航表里点开三个子页 id 也不换页（pushPage 无路可走）。
  const reachable = await page.evaluate(async () => {
    const nav = await import('/js/mobile/navController.js');
    nav.dockTo('workbench');
    for (const id of ['me-user', 'me-avatar', 'me-bind']) {
      nav.pushPage(id);
      if (window.__nfMobileNav && window.__nfMobileNav.top === id) return id;
    }
    return '';
  });
  expect(reachable, 'me-user / me-avatar / me-bind 必须不可达').toBe('');
});
