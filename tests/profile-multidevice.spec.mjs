// profile-multidevice.spec.mjs — 单页资料面 / 只读身份 + 官网指引 / 设备清单 +
// 逐设备独立退出（profile-multidevice 批）。
//
// 本 spec 是**浏览器消费面**测试（REST/WS 全部桩化、零实例、零生产端口），
// 判据四条（与 openspec/changes/profile-multidevice/proposal.md 的验收一一对应）：
//   ① 单页资料：手机个人资料页无「编辑资料」键、无层级子页入口；三个子页 id
//      在导航表里不复存在（点不到 = 真退役，不是藏起来）。
//   ② 只读身份 + 官网编辑指引：资料面零可编辑控件、零指向官网账号页的 <a href>、
//      零 window.open；指引以**纯文本**给出落点。
//   ③ 设备清单：名称 / 平台 / 最近活动 / 状态 / 本机标记 —— 每行都有。
//   ④ 逐设备独立退出：本机行 = 既有唯一登出链（end-session），**不发**设备域请求；
//      对端行 = 只带**该行自己的** deviceId 的设备域请求，**不发** end-session、
//      请求里不出现其它设备 id（隔离判据）。
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json' };

const SELF = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos' };
const PEERS = [
  { deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', lastSeen: Date.now() - 120_000, online: true, directOnline: true },
  { deviceId: 'dev-old', deviceName: 'OldBox', platform: 'linux', lastSeen: Date.now() - 3 * 86_400_000, online: false, directOnline: false },
];

/** 桩化 boot：设备读数 = 上游 /api/neblink/status 的真实形状；请求全量留痕。 */
async function boot(page, mobile) {
  await page.setViewportSize(mobile ? { width: 390, height: 844 } : { width: 1600, height: 1000 });
  await page.addInitScript(({ peers }) => {
    localStorage.setItem('nebflow_token', 'test');
    localStorage.setItem('nebflow_locale', 'en');
    localStorage.setItem('stand_sw', '0');
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return { location: { href: '' }, close() {} }; };
    window.__reqs = [];
    window.__peers = peers;
  }, { peers: PEERS });
  await page.route('**/*', route => {
    const req = route.request();
    const url = new URL(req.url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      void page.evaluate((entry) => { window.__reqs.push(entry); },
        { method: req.method(), path: p }).catch(() => {});
      if (p === '/api/neblink/status') {
        return route.fulfill({
          json: { loggedIn: true, device: { ...SELF, displayName: 'Fixture User', email: 'fixture@example.test' }, peers: PEERS },
        });
      }
      if (p === '/api/users/search') {
        return route.fulfill({ json: { found: true, user: { userId: 'u-self', username: 'Mashiro', display_name: 'Fixture User', avatar: null }, relation_status: 'self' } });
      }
      if (p === '/api/neblink/auth/end-session') return route.fulfill({ json: {} });
      if (/^\/api\/neblink\/devices\/[^/]+\/sign-out$/.test(p)) {
        return route.fulfill({ status: 409, json: { ok: false, code: 'remote-sign-out-unsupported', error: 'remote device sign-out is not supported' } });
      }
      return route.fulfill({ json: {} });
    }
    const file = normalize(join(web, p));
    if (!file.startsWith(web)) return route.fulfill({ status: 403 });
    try { return route.fulfill({ status: 200, contentType: mime[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404 }); }
  });
  await page.routeWebSocket(/\/ws/, ws => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    config();
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'root' }));
    ws.onMessage(raw => { try { if (JSON.parse(raw).type === 'getConfig') config(); } catch { /* 忽略非 JSON 帧 */ } });
  });
  await page.goto('http://localhost:1/');
  if (mobile) {
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'));
  } else {
    await page.waitForSelector('#settings-btn', { timeout: 20000 });
    await page.locator('#settings-btn').click();
    await expect(page.locator('#settings-content')).toBeVisible();
  }
}

/** 手机：工作台 → 个人资料页。 */
async function openMobileMe(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await expect(page.locator('.mobile-page[data-page="me"]')).toHaveClass(/live/);
}

/** 桌面：设置页身份卡胶囊 → 资料面（手机同一条腿，见 openProfileFace 唯一入口）。 */
async function openProfileFace(page) {
  await page.locator('#settings-identity-capsule').click();
  await expect(page.locator('#settings-profile-overlay')).toBeVisible();
}

for (const mobile of [false, true]) {
  const tag = mobile ? 'mobile' : 'desktop';

  test(`${tag}: profile is a single page — no edit action, no subpages`, async ({ page }) => {
    await boot(page, mobile);

    // ① 导航表里三个子页 id 已整族退役（不是隐藏：pushPage 也到不了）。
    const pages = await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      const before = await import('/js/mobile/navController.js');
      void before;
      return { hasPushPage: typeof nav.pushPage === 'function' };
    });
    expect(pages.hasPushPage).toBe(true);
    const subpageReachable = await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      nav.dockTo('workbench');
      for (const id of ['me-user', 'me-avatar', 'me-bind']) {
        nav.pushPage(id);
        if (window.__nfMobileNav && window.__nfMobileNav.top === id) return id;
      }
      return '';
    });
    expect(subpageReachable, 'me-user / me-avatar / me-bind 必须不可达').toBe('');

    if (mobile) {
      await openMobileMe(page);
      const me = page.locator('.mobile-page[data-page="me"]');
      await expect(me.locator('#me-edit-profile')).toHaveCount(0);
      await expect(me.locator('[data-me-page]')).toHaveCount(0);
      await expect(me.locator('.mf-me-card.sp-identity')).toHaveCount(1);
      await expect(me.locator('.mf-me-avatar-frame.huge')).toHaveCount(1);
    } else {
      await openProfileFace(page);
    }

    // ② 只读身份 + 官网编辑指引（纯文本；零可编辑控件 / 零 <a href> / 零 window.open）。
    if (!mobile) {
      const overlay = page.locator('#settings-profile-overlay');
      await expect(overlay.locator('input, select, textarea, [contenteditable="true"], [role="switch"]')).toHaveCount(0);
      await expect(overlay.locator('a[href]')).toHaveCount(0);
      const guidance = await overlay.locator('.sp-note').allTextContents();
      expect(guidance.join(' ')).toContain('mystand.space');
      expect(await page.evaluate(() => window.__openCalls.length)).toBe(0);
    } else {
      const me = page.locator('.mobile-page[data-page="me"]');
      await expect(me.locator('input, select, textarea, [contenteditable="true"], [role="switch"]')).toHaveCount(0);
      await expect(me.locator('a[href]')).toHaveCount(0);
      await expect(me.locator('.mf-me-hint')).toContainText('mystand.space');
      expect(await page.evaluate(() => window.__openCalls.length)).toBe(0);
    }
  });

  test(`${tag}: device list shows name / platform / recent activity / status / current device`, async ({ page }) => {
    await boot(page, mobile);
    if (mobile) await openMobileMe(page);
    else await openProfileFace(page);

    const host = mobile ? page.locator('.mobile-page[data-page="me"]') : page.locator('#settings-profile-overlay');
    await page.waitForTimeout(300);
    const rows = host.locator('[data-device-list="1"] .pd-device-row');
    await expect(rows).toHaveCount(3);

    const read = () => rows.evaluateAll(nodes => nodes.map((n) => ({
      id: n.dataset.deviceId,
      current: n.dataset.deviceCurrent,
      online: n.dataset.deviceOnline,
      name: n.querySelector('.pd-device-name-text')?.textContent?.trim() || '',
      meta: n.querySelector('.pd-device-meta')?.textContent?.trim() || '',
      badge: n.querySelector('.pd-device-badge')?.textContent?.trim() || '',
      signout: n.querySelector('[data-device-signout]')?.getAttribute('data-device-signout') || '',
    })));
    const list = await read();
    const self = list.find((d) => d.id === 'dev-self');
    const kai = list.find((d) => d.id === 'dev-kai');
    expect(self.current).toBe('1');
    expect(self.name).toBe('Mashiro-Mac');
    expect(self.meta).toContain('macOS');
    expect(self.badge).toBe('This device');
    expect(kai.current).toBe('0');
    expect(kai.name).toBe('KAI');
    expect(kai.meta).toContain('Windows');
    expect(kai.meta).toContain('Online');
    expect(kai.meta).toContain('Last active');
    expect(kai.signout).toBe('dev-kai');
    // 每行一枚退出键，各带自己的 deviceId（禁共用一枚）。
    expect(list.map((d) => d.signout)).toEqual(['dev-self', 'dev-kai', 'dev-old']);
  });

  test(`${tag}: per-device independent sign-out is scoped and isolated`, async ({ page }) => {
    await boot(page, mobile);
    if (mobile) await openMobileMe(page);
    else await openProfileFace(page);
    const host = mobile ? page.locator('.mobile-page[data-page="me"]') : page.locator('#settings-profile-overlay');
    await page.waitForTimeout(300);

    const paths = () => page.evaluate(() => window.__reqs.filter(r => r.path.startsWith('/api/')).map(r => `${r.method} ${r.path}`));

    // ④-a 对端行：只发**该行自己的**设备域请求；不发 end-session；勿碰别的设备。
    await host.locator('.pd-device-row[data-device-id="dev-kai"] [data-device-signout]').click();
    await page.waitForTimeout(400);
    const afterPeer = await paths();
    const peerSignOuts = afterPeer.filter(p => p.endsWith('/sign-out'));
    expect(peerSignOuts).toEqual(['POST /api/neblink/devices/dev-kai/sign-out']);
    expect(afterPeer.filter(p => p.includes('end-session'))).toEqual([]);
    expect(peerSignOuts.filter(p => p.includes('dev-self'))).toEqual([]);
    expect(peerSignOuts.filter(p => p.includes('dev-old'))).toEqual([]);
    // 失败禁静默：无此能力必须有一句可见提示（不假装已退出）。
    await expect(host.locator('.pd-device-notice')).toBeVisible();
    await expect(host.locator('.pd-device-notice')).toContainText('sign out on that device itself');

    // ④-b 本机行：走既有唯一登出链（end-session）；不发设备域请求。
    await host.locator('.pd-device-row[data-device-id="dev-self"] [data-device-signout]').click();
    await page.waitForTimeout(600);
    const afterSelf = await paths();
    expect(afterSelf.filter(p => p.includes('end-session')).length).toBeGreaterThanOrEqual(1);
    expect(afterSelf.filter(p => p.endsWith('/sign-out'))).toEqual(peerSignOuts);
  });
}
