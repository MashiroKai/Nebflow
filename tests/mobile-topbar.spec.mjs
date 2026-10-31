// mobile-topbar.spec.mjs — 手机端工作台顶栏批 spec（作者令 2026-10-31 15:31，四件）：
//   ① 三个 tab 根页（消息/联系人/工作台，canvas 同族）顶部标题文字全去掉——
//      无页头/标题胶囊，内容上移（mobile-page-nohead 壳级安全区顶距）。
//   ② 设置页头像入口挪到工作台顶部（圆形头像，与设置页现头像同源同态），
//      设置页原头像位同步撤掉（仅手机层；桌面由 unifyfix/settings-cleanup 钉）。
//   ③ 工作台列表「设备」行去掉（设置账号段设备区手机层不渲染），联系人页
//      设备段完好（data-devices-entry 在位）。
//   ④ 点头像进个人主页：me 页 + 用户名/头像/绑定层级子页（苹果层级式铺满屏、
//      左上「<」返回 = 既有详情页壳）；字段 = neblink status 既有读数；配置动作
//      统一官网个人主页出口（不新造配置面）。
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
  // 详情页（设置）顶栏照旧：「<」+ 标题胶囊在场（先回工作台根——根页互斥，
  // canvas 在位时工作台 hub 行不可点）。
  await dockTo(page, 'workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  await expect(page.locator('.mobile-page[data-page="settings"] .mobile-page-title')).toHaveText('设置');
  await expect(page.locator('.mobile-page[data-page="settings"] .mobile-back-btn')).toHaveCount(1);
});

test('T2 头像搬家：工作台顶部头像入口在场同态，设置页头像位/设备区撤掉（手机层）', async ({ page }) => {
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
  // 设置页：头像入口与设备槽手机层不渲染；hub 行集不变（设置行末位在场）。
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  await expect(page.locator('#settings-avatar-entry')).toHaveCount(0);
  await expect(page.locator('#settings-devices-slot')).toHaveCount(0);
  await expect(page.locator('#settings-modal .neblink-peer')).toHaveCount(0);
  await page.locator('.mobile-page[data-page="settings"] .mobile-back-btn').click();
  // 联系人页设备段完好（设备只在联系人展示）。
  await dockTo(page, 'contacts');
  await expect(page.locator('.mobile-page[data-page="contacts"] .fm-devices-entry[data-devices-entry="1"]')).toHaveCount(1);
});

test('T3 个人主页层级：点头像进 me 页，用户名/头像/绑定子页铺满屏可返回', async ({ page }) => {
  await bootApp(page);
  await dockTo(page, 'workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await expect(page.locator('.mobile-page[data-page="me"]')).toHaveClass(/live/);
  // 苹果层级壳：子页 = 既有详情页（左上「<」+ 标题胶囊）。
  for (const [row, sub, titleText] of [
    ['me-user', 'me-user', '用户名'],
    ['me-avatar', 'me-avatar', '头像'],
    ['me-bind', 'me-bind', '绑定'],
  ]) {
    await page.locator(`.mobile-page[data-page="me"] .mf-hub-row[data-me-page="${row}"]`).click();
    const subPage = page.locator(`.mobile-page[data-page="${sub}"]`);
    await expect(subPage).toHaveClass(/live/);
    await expect(subPage.locator('.mobile-back-btn')).toHaveCount(1);
    await expect(subPage.locator('.mobile-page-title')).toHaveText(titleText);
    // 返回 = pop 一层回个人主页（层级栈语义）。
    await subPage.locator('.mobile-back-btn').click();
    await expect(page.locator('.mobile-page[data-page="me"]')).toHaveClass(/live/);
  }
  // 字段 = 官网个人页已有字段的本地读数（neblink status）；配置出口 = 官网
  // 个人主页（既有接口），客户端零账号编辑控件。
  await expect(page.locator('.mobile-page[data-page="me"] .mf-me-portal')).toHaveCount(1);
  await page.locator('.mobile-page[data-page="me"] .mf-hub-row[data-me-page="me-user"]').click();
  await expect(page.locator('.mobile-page[data-page="me-user"] .mf-me-field-value').first()).toHaveText('测试用户');
  await expect(page.locator('.mobile-page[data-page="me-user"] .mf-me-field-value').nth(1)).toHaveText('me@example.com');
  // 绑定子页：设备绑定读数 = 本机 + 1 台（peers 夹具一行）。
  await page.locator('.mobile-page[data-page="me-user"] .mobile-back-btn').click();
  await page.locator('.mobile-page[data-page="me"] .mf-hub-row[data-me-page="me-bind"]').click();
  await expect(page.locator('.mobile-page[data-page="me-bind"] .mf-me-field-value').nth(1)).toHaveText('本机 + 1 台');
});
