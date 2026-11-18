// profile-complete.spec.mjs — 个人主页资料补全 + 头像跳官网（作者令 2026-11-18 16:45，
// 逐字：「我们个人主页里的信息很多都没获取全 比如github绑定信息 这些都[需要]补全
// 另外在个人主页再次点击头像要能跳转官网」）。
//
// 本 spec 是**浏览器消费面**测试（REST/WS 全部桩化、零实例、零生产端口；页面只
// goto `http://localhost:1/`，不会有任何监听）。判据五条，与交付报告逐条对应：
//
//   ① 用户名 = 真实读数：手机个人主页（`mobile/meFace.js`）**自己**触发自证探针
//      （`settingsIdentity.ensureIdentityLive()` → `GET /api/users/search`，
//      `relation_status==='self'`）。修前该页从不触发探针 ⇒ 用户名恒「未知」。
//   ② GitHub 绑定 = 真实读数：真源 = `status.device.githubLogin`（服务端
//      `github_username`，登录时下发）。修前该位写死「未知」。
//   ③ Google 绑定 = 「未知」**且带缺口说明**（作者令「不许留『未知』而不解释」：
//      本机没有任何可读来源，说明里点明谁维护、卡在哪层）。
//   ④ 头像跳官网：资料卡大头像 = `<a href="https://mystand.space" target="_blank"
//      rel="noopener noreferrer">`；点它**新开标签页**落到官网，本页不导航。
//   ⑤ 桌面资料面（`#settings-profile-overlay`）同读数：GitHub 真实值 + Google 未知
//      + 同一句缺口说明（两个面一份文案，禁第二份拼接）。
//
// 零 console error 是硬判据：全腿 page.on('console'|'pageerror') 采集，末尾断言空数组。
//
// Run: node node_modules/@playwright/test/cli.js test tests/profile-complete.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'profile-complete-session';
const HOME = 'https://mystand.space/';

// 官网出口的落地占位（**桩**，自报家门；真站零接触）。
const HOME_STUB_BODY = '<!doctype html><meta charset="utf-8"><title>mystand.space</title>'
  + '<body style="font:16px system-ui;padding:24px">'
  + '<h1>mystand.space</h1><p>destination stand-in (no live site is contacted)</p></body>';
const HOME_STUB = (route) => route.fulfill({ status: 200, contentType: 'text/html', body: HOME_STUB_BODY });
const HOME_URL = /^https:\/\/mystand\.space(\/|$|\?)/;

// 读数夹具 = 作者本机 2026-11-18 的真实值（`/api/neblink/status` 的 device 块
// + 登录 id_token 的 claim 解码，见交付报告「对照表」）。不是编出来的样例值。
const SELF = {
  id: 'dev-self', name: 'Mashiros-MacBook-Pro-iso-a659864a', platform: 'macos',
  displayName: 'Mashiro', email: 'kaimashiro@qq.com', avatarUrl: '',
  githubLogin: 'Mashiro',
};

/** @type {string[]} */
let consoleErrors = [];

async function boot(page, { mobile }) {
  await page.setViewportSize(mobile ? { width: 390, height: 844 } : { width: 1280, height: 800 });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
  });

  const tag = mobile ? 'mobile' : 'desktop';
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`${tag}: ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`${tag}: pageerror: ${e.message}`));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    if (HOME_URL.test(route.request().url())) return HOME_STUB(route);
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
          body: JSON.stringify({ loggedIn: true, device: SELF, peers: [] }),
        });
      }
      if (p === '/api/users/search') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            found: true,
            user: { userId: 'u-self', username: 'Mashiro', display_name: 'Mashiro', avatar: null },
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

  // 官网出口的桩必须挂在 **context** 上：`page.route` 只拦本页的请求，`target="_blank"`
  // 开出来的新页不经过它 ⇒ 会真连线上官网（证据也不可复跑，实测会落到真站的 `/en`）。
  await page.context().route(HOME_URL, HOME_STUB);

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
    });
  });

  await page.goto('http://localhost:1/');
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'));
  else await page.waitForSelector('#settings-btn', { timeout: 20000 });
  await page.waitForTimeout(300);
}

/** 手机：工作台 → 个人主页（`me`）。 */
async function openMobileMe(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await expect(page.locator('.mobile-page[data-page="me"]')).toHaveClass(/live/);
  // 探针 + 状态拍落地（读数两次重绘后再取）。
  await page.waitForTimeout(400);
}

/** 手机个人主页的五个字段读数 + 缺口说明。 */
function mobileFields(page) {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('.mobile-page[data-page="me"] .mf-me-field')];
    const out = {};
    for (const r of rows) {
      const label = r.querySelector('.mf-me-field-label')?.textContent?.trim() || '';
      out[label] = r.querySelector('.mf-me-field-value')?.textContent?.trim() || '';
    }
    const gap = document.querySelector('.mobile-page[data-page="me"] .mf-me-gapnote');
    return { fields: out, gap: gap && !gap.hidden ? (gap.textContent || '').trim() : '' };
  });
}

test.describe('资料补全 + 头像跳官网 · 手机腿（390×844，全屏页）', () => {
  test.use({ hasTouch: true, colorScheme: 'dark' });

  test('①②③ 手机个人主页：用户名 / GitHub 绑定 = 真实读数，Google 未知但附缺口说明', async ({ page }) => {
    await boot(page, { mobile: true });
    await openMobileMe(page);

    const read = await mobileFields(page);
    console.log('[①②③] 手机个人主页读数', JSON.stringify(read));

    expect(read.fields['用户名'], '① 用户名 = 自证探针读数（修前恒「未知」）').toBe('Mashiro');
    expect(read.fields['昵称'], '昵称 = id_token name claim').toBe('Mashiro');
    expect(read.fields['邮箱'], '邮箱 = id_token email claim').toBe('kaimashiro@qq.com');
    expect(read.fields['GitHub 绑定'], '② GitHub 绑定 = status.device.githubLogin（修前写死「未知」）').toBe('Mashiro');
    expect(read.fields['Google 绑定'], '③ Google 绑定本机无来源 ⇒「未知」').toBe('未知');
    expect(read.gap, '③ 未知必须有解释：缺口说明在场且点名 Google').toContain('Google');
    expect(read.gap, '③ 缺口说明点明是谁维护（官网账号页）').toContain('官网账号页');
    // 否定面：缺口说明不得把读到的字段也算进去（防「一律未知 + 一律出句」的假绿）。
    expect(read.gap, '③ 已读到的 GitHub 不进缺口说明').not.toContain('GitHub');

    // 只读面：零可编辑控件（假控件禁令不变）。
    await expect(page.locator('.mobile-page[data-page="me"] input, .mobile-page[data-page="me"] select, '
      + '.mobile-page[data-page="me"] textarea, .mobile-page[data-page="me"] [contenteditable="true"], '
      + '.mobile-page[data-page="me"] [role="switch"]')).toHaveCount(0);
  });

  test('④ 点头像 = 新标签页开官网（本页不导航；锚点三属性齐备）', async ({ page }) => {
    await boot(page, { mobile: true });
    await openMobileMe(page);

    const avatar = page.locator('.mobile-page[data-page="me"] .mf-me-avatar-frame.huge');
    await expect(avatar).toHaveCount(1);
    const attrs = await avatar.evaluate((el) => ({
      tag: el.tagName, href: el.getAttribute('href'), target: el.getAttribute('target'),
      rel: el.getAttribute('rel'), label: el.getAttribute('aria-label'),
      pointer: getComputedStyle(el).cursor,
    }));
    console.log('[④] 头像锚点', JSON.stringify(attrs));
    expect(attrs.tag, '④ 大头像本体就是锚点（不叠第二层壳）').toBe('A');
    expect(attrs.href, '④ 落点 = 官网根（作者令逐字 mystand.space）').toBe('https://mystand.space');
    expect(attrs.target, '④ 新标签页').toBe('_blank');
    expect(attrs.rel, '④ 反窃取（rel 逐字）').toBe('noopener noreferrer');

    // 全页只有这一枚外链（其它「官网」字样仍是纯文本指引）。
    await expect(page.locator('.mobile-page[data-page="me"] a[href]')).toHaveCount(1);

    const before = page.url();
    const popupPromise = page.context().waitForEvent('page');
    await avatar.click();
    const popup = await popupPromise;
    await popup.waitForLoadState('domcontentloaded').catch(() => {});
    console.log('[④] 新标签页 URL', popup.url());
    expect(popup.url(), '④ 新标签页落到官网').toBe(HOME);
    await expect(popup.locator('h1')).toHaveText('mystand.space');
    expect(page.url(), '④ 本页不导航（原地不动）').toBe(before);
    expect(await page.evaluate(() => window.__openCalls.length), '④ 走锚点，不走 window.open').toBe(0);
    await expect(page.locator('.mobile-page[data-page="me"]')).toHaveClass(/live/);
    await popup.close();
  });
});

test.describe('资料补全 · 桌面腿（1280×800）', () => {
  test('⑤ 桌面资料面同读数：GitHub 真实值 + Google 未知 + 同一句缺口说明', async ({ page }) => {
    await boot(page, { mobile: false });
    await page.evaluate(async () => (await import('/js/settingsIdentity.js')).openProfileFace());
    const overlay = page.locator('#settings-profile-overlay');
    await expect(overlay).toBeVisible();
    await page.waitForTimeout(400);

    const read = await page.evaluate(() => {
      const ov = document.getElementById('settings-profile-overlay');
      const f = (id) => ov?.querySelector(`.sp-field[data-sp-field="${id}"] .sp-value`)?.textContent?.trim() || '';
      const note = ov?.querySelector('#settings-profile-bindnote');
      return {
        username: f('username'), nickname: f('nickname'), email: f('email'),
        github: f('github'), google: f('google'),
        gap: note && !note.hidden ? (note.textContent || '').trim() : '',
      };
    });
    console.log('[⑤] 桌面资料面读数', JSON.stringify(read));
    expect(read.username, '⑤ 用户名 = 自证探针').toBe('Mashiro');
    expect(read.github, '⑤ GitHub 绑定 = status.device.githubLogin').toBe('Mashiro');
    expect(read.google, '⑤ Google 绑定 = 未知').toBe('未知');
    expect(read.gap, '⑤ 桌面面的缺口说明与手机面同源（单点 bindingGapNote）').toContain('官网账号页');
    expect(read.gap, '⑤ 已读到的 GitHub 不进缺口说明').not.toContain('GitHub');
    // 资料面本身仍零外链（官网出口只在手机个人主页那头像一处）。
    await expect(overlay.locator('a[href]')).toHaveCount(0);
  });

  test('零 console error（两腿全流程）', async () => {
    expect(consoleErrors, `console/pageerror 必须为空（读数 ${consoleErrors.length}）`).toEqual([]);
  });
});
