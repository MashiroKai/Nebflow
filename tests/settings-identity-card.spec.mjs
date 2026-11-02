// settings-identity-card.spec.mjs — 件四 · 设置页身份卡本地化（作者令 2026-11-02）验收 spec。
//
// 作者原令（逐字）：「设置里，要是头像在上，然后下面是用户名和邮箱。然后点击用户和邮箱
// 的这个胶囊，点击头像就是直接换头像进去就是自己的 profile 设置页…现在的设计是跳转到
// 官网，但是我是要在这里直接修改。」
//
// 本 spec 钉五条机械事实（桌面 1280 + 手机层 375×812 两档，同一份断言）：
//   ① 结构 —— 头像在身份卡上（`#settings-identity-card` 内 `#settings-avatar-entry`），
//      用户名与邮箱在**同一枚**胶囊 `#settings-identity-capsule` 内（同胶囊 = 同父节点）；
//   ② 点头像 → 走换头像流程（file chooser 被触发），且**没有**开 profile 面
//      （不是「点头像进 profile」）；
//   ③ 点胶囊 → 开 profile 面 `#settings-profile-overlay`（App 内，不跳官网）；
//   ④ 全程零外链 —— DOM 内 `a[href]` 无一指向 mystand.space 账号页；页面级
//      `window.open` 换成计数桩后，全套交互走完读数恒为 0；
//   ⑤ 回读一致 + 零假可编辑面 —— profile 面里**没有任何可编辑控件**
//      （input/select/textarea/[contenteditable] 计数 0：本批上游确实没有身份写接口，
//      见下），唯一的真实读路（`GET /api/users/search` 自证）在**重开面后**读数一致。
//
// ══ 关于 ⑤ 的契约来源（⚠️ 显式标注，禁止误读为真实上游）═════════════════════
// 本 spec 全程用 **stub 化的本地契约**（route mock），因为账号接口的真实上游需要
// 活的账号会话（CI/本地 e2e 不可能有）：
//   · `GET /api/neblink/status` → 已登录 + device{email, displayName, avatarUrl}
//   · `GET /api/users/search?q=<email>` → `{found:true,user:{username,…},relation_status:'self'}`
//     （= friendsApi.searchUser 的单点读路；`relation_status==='self'` 是自证判据）
// 2026-11-02 真实上游实测（本机 :6138，仅供记录，spec 不依赖）：
//   · `GET /api/users/search?q=<自己的 email>` → **200** `…"relation_status":"self"`
//   · `PUT /api/users/me/neblink-id` / `GET …/neblink-id/available` → 网关 502
//     （上游 `HTTP 403 {"error":"Invalid or expired token"}`）⇒ 已不可用
//   · `PUT|POST /api/users/me/avatar`、`/api/users/me`、`/api/users/me/bindings` → 404
//   ⇒ 「能就地改的字段」本批**为空集**，所以 ⑤ 断言的是「零假可编辑面 + 真实读数回读
//     一致」，而不是「改完回读」（改了会失败——那是假 UI，作者令明禁）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/settings-identity-card.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8181; // 8100+ 段，绝不占宿主 8080
const MIME = {
  '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.ico': 'image/x-icon',
};
// 1x1 透明 PNG（mock 头像字节，让 <img> 真的能加载）
const PX_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64');
const AVATAR_URL = 'https://cdn.neblink-space-mock.test/av.png';
const EMAIL = 'me@example.com';
const STUB_USERNAME = 'Mashiro';

function startServer() {
  return new Promise((done) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(PORT, '127.0.0.1', () => done(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;
test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

/**
 * Boot the real shell at a given viewport against stub contracts.
 * @param {import('@playwright/test').Page} page
 * @param {{mobile?: boolean}} opts
 */
async function boot(page, { mobile = false } = {}) {
  await page.setViewportSize(mobile ? { width: 375, height: 812 } : { width: 1280, height: 800 });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    // ④ 的页面级探针：**在被测页面里**把 window.open 换成计数桩，之后任何一处
    // window.open 都会被记下来（含非 <a> 的 JS 跳转路径）。
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
  });

  await page.route('**/api/**', (r) => r.fulfill({ json: {} })); // 兜底（放前面，后面更具体的优先）
  await page.route('**/api/neblink/status', (r) => r.fulfill({
    json: {
      loggedIn: true,
      device: {
        id: 'd1', name: '本机', platform: 'macos', userDescription: '',
        avatarUrl: AVATAR_URL, email: EMAIL, displayName: '测试用户',
      },
      peers: [],
    },
  }));
  // 唯一真实身份读路（contract v1.0 §4.1 形状；自证靠 relation_status='self'）
  await page.route('**/api/users/search**', (r) => r.fulfill({
    json: {
      found: true,
      user: {
        userId: 'u-self', username: STUB_USERNAME, display_name: '测试用户',
        avatar: AVATAR_URL,
      },
      relation_status: 'self',
    },
  }));
  await page.route('**/api/friends**', (r) => r.fulfill({ json: { friends: [], incoming: [], outgoing: [] } }));
  await page.route('**/cdn.neblink-space-mock.test/**', (r) => r.fulfill({ contentType: 'image/png', body: PX_PNG }));

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.goto(base + '/index.html');
  if (mobile) {
    // 手机交互层（独立层，不是响应式补丁）：激活判据 = body.mobile-layer 类
    // （mobile/bootstrap.js，matchMedia '(max-width: 768px)'）。
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), { timeout: 20000 });
    await page.waitForSelector('#mobile-dock', { timeout: 20000 });
  } else {
    await page.waitForSelector('#activity-bar', { timeout: 20000 });
  }
}

/** 打开设置页（桌面 = 设置钮；手机层 = 工作台根页的「设置」hub 行）。 */
async function openSettings(page, { mobile = false } = {}) {
  if (mobile) {
    await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
    await expect(page.locator('.mobile-page[data-page="workbench"]')).toHaveClass(/live/);
    await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
    await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  } else {
    await page.click('#settings-btn');
  }
  await page.waitForSelector('#settings-identity-card', { timeout: 15000 });
}

/** 关个人资料面：桌面 = 右上「×」；手机层 = 注入的左上「<」（mobileSheetBack，
 *  作者令「手机端不再有弹窗、一律铺满屏 + 左上返回」）。 */
async function closeFace(page, { mobile = false } = {}) {
  if (mobile) await page.click('#settings-profile-overlay > .mfs-back');
  else await page.click('#settings-profile-close');
}

/** 等自证探针落地（username 从「未知」变真值）。 */
async function waitUsername(page) {
  await expect(page.locator('#settings-identity-card .settings-identity-username'))
    .toHaveText(STUB_USERNAME, { timeout: 10000 });
}

// ══ ① 结构：头像在上，用户名 + 邮箱同胶囊 ══════════════════════════════
for (const leg of [{ name: 'desktop', mobile: false }, { name: 'mobile', mobile: true }]) {
  test(`T1 [${leg.name}] identity card: avatar on top, username+email in ONE capsule`, async ({ page }) => {
    await boot(page, { mobile: leg.mobile });
    await openSettings(page, { mobile: leg.mobile });
    await waitUsername(page);
    // 就绪门（paintAvatarSlot 先等字节可绘制才翻面）：等 photo 真可见再读读数。
    await page.waitForFunction(() => {
      const p = document.querySelector('#settings-avatar-entry .settings-avatar-photo');
      return !!p && p.hidden === false && getComputedStyle(p).display !== 'none';
    }, { timeout: 8000 });

    const dump = await page.evaluate(() => {
      const card = document.getElementById('settings-identity-card');
      const entry = document.getElementById('settings-avatar-entry');
      const capsule = document.getElementById('settings-identity-capsule');
      const un = card?.querySelector('.settings-identity-username');
      const em = card?.querySelector('.settings-identity-email');
      const r = (el) => (el ? el.getBoundingClientRect() : null);
      return {
        cardExists: !!card,
        avatarInCard: !!entry && !!card && card.contains(entry),
        capsuleExists: !!capsule,
        // 「同一胶囊」= 用户名与邮箱是同一次胶囊按钮的**共同后代**
        usernameInCapsule: !!un && !!capsule && capsule.contains(un),
        emailInCapsule: !!em && !!capsule && capsule.contains(em),
        usernameText: un?.textContent?.trim() || '',
        emailText: em?.textContent?.trim() || '',
        // 「头像在上」= 头像块底边不高于胶囊顶边
        avatarRect: r(entry),
        capsuleRect: r(capsule),
        // 头像双态骨架在场（照片已加载 = 登录态有头像）
        photoVisible: !!card?.querySelector('.settings-avatar-photo')
          && card.querySelector('.settings-avatar-photo').hidden === false,
      };
    });

    expect(dump.cardExists, 'identity card must exist').toBe(true);
    expect(dump.avatarInCard, 'avatar entry must live inside the identity card').toBe(true);
    expect(dump.capsuleExists, 'capsule button must exist').toBe(true);
    expect(dump.usernameInCapsule, 'username must be inside the capsule').toBe(true);
    expect(dump.emailInCapsule, 'email must be inside the same capsule').toBe(true);
    expect(dump.usernameText, 'capsule shows the real username read from the self-probe').toBe(STUB_USERNAME);
    expect(dump.emailText, 'capsule shows the account email claim').toBe(EMAIL);
    expect(dump.avatarRect && dump.capsuleRect, 'both rects must be measurable').toBeTruthy();
    expect(dump.avatarRect.bottom, 'avatar must sit ABOVE the capsule (author: 头像在上)')
      .toBeLessThanOrEqual(dump.capsuleRect.top + 1);
    expect(dump.photoVisible, 'logged-in avatar photo must be painted').toBe(true);
  });

  // ══ ② 点头像 → 换头像流程（不是进 profile 面）══════════════════════════
  test(`T2 [${leg.name}] clicking the avatar starts the avatar-change flow, NOT the profile face`, async ({ page }) => {
    await boot(page, { mobile: leg.mobile });
    await openSettings(page, { mobile: leg.mobile });
    await waitUsername(page);

    // 隐藏文件选择器在场（换头像流程的机械载体，与 agentProfile 头像盒同款）。
    const input = await page.evaluate(() => {
      const i = document.getElementById('settings-avatar-file');
      return i ? { type: i.type, accept: i.accept, hidden: i.hidden } : null;
    });
    expect(input, 'a hidden file input must exist for the avatar-change flow').not.toBeNull();
    expect(input.type).toBe('file');
    expect(input.accept).toBe('image/*');

    // 点击落在文件选择器上 = 换头像流程被触发（Playwright 的 filechooser 事件
    // 只在真实 file input 被触发时出现 —— 这是「走的是换头像流程」的硬证据）。
    const chooser = page.waitForEvent('filechooser', { timeout: 8000 });
    await page.click('#settings-avatar-entry');
    await chooser;

    // 且**没有**开 profile 面（作者令：点头像 ≠ 进 profile；胶囊才是）。
    const after = await page.evaluate(() => {
      const ov = document.getElementById('settings-profile-overlay');
      return { profileOpen: !!ov && ov.hidden === false, opens: window.__openCalls.length };
    });
    expect(after.profileOpen, 'clicking the avatar must NOT open the profile face').toBe(false);
    expect(after.opens, 'clicking the avatar must not call window.open').toBe(0);
  });

  // ══ ③ 点胶囊 → App 内 profile 面 ═══════════════════════════════════════
  test(`T3 [${leg.name}] clicking the capsule opens the in-app profile face`, async ({ page }) => {
    await boot(page, { mobile: leg.mobile });
    await openSettings(page, { mobile: leg.mobile });
    await waitUsername(page);

    await page.click('#settings-identity-capsule');
    const face = page.locator('#settings-profile-overlay');
    await expect(face, 'profile overlay must be visible').toBeVisible();

    const dump = await page.evaluate(() => {
      const ov = document.getElementById('settings-profile-overlay');
      const field = (id) => ov?.querySelector(`.sp-field[data-sp-field="${id}"] .sp-value`)?.textContent?.trim() || '';
      return {
        inDocument: !!ov && document.body.contains(ov),
        // 面壳 = 既有覆层族（不是新造的弹层形态）
        overlayClass: ov?.className || '',
        modalExists: !!ov?.querySelector('.settings-profile-modal'),
        username: field('username'),
        nickname: field('nickname'),
        email: field('email'),
        github: field('github'),
        google: field('google'),
        warn: ov?.querySelector('.sp-note-warn')?.textContent?.trim() || '',
        opens: window.__openCalls.length,
      };
    });
    expect(dump.inDocument, 'profile face must be a live DOM node').toBe(true);
    expect(dump.overlayClass).toContain('cfg-modal-overlay'); // 既有覆层族（零新弹层形态）
    expect(dump.modalExists, 'the in-app face must render its modal shell').toBe(true);
    expect(dump.username).toBe(STUB_USERNAME);
    expect(dump.nickname, 'nickname = id_token name claim (real reading)').toBe('测试用户');
    expect(dump.email).toBe(EMAIL);
    // GitHub / Google 绑定：上游无接口 ⇒ 「未知」+ 一句明确提示（禁造假开关）
    expect(dump.github).toBe('未知');
    expect(dump.google).toBe('未知');
    expect(dump.warn).toContain('账号服务暂未提供该接口');
    expect(dump.opens, 'opening the profile face must not call window.open').toBe(0);
  });

  // ══ ④ 全程零外链 ════════════════════════════════════════════════════════
  test(`T4 [${leg.name}] zero external links anywhere on the identity chain`, async ({ page }) => {
    await boot(page, { mobile: leg.mobile });
    await openSettings(page, { mobile: leg.mobile });
    await waitUsername(page);

    // 走完全套交互：胶囊开面 → 关 → 头像（换头像流程）。
    await page.click('#settings-identity-capsule');
    await expect(page.locator('#settings-profile-overlay')).toBeVisible();
    await closeFace(page, { mobile: leg.mobile });
    await expect(page.locator('#settings-profile-overlay')).toBeHidden();

    const chooser = page.waitForEvent('filechooser', { timeout: 8000 });
    await page.click('#settings-avatar-entry');
    await chooser;
    await page.keyboard.press('Escape');

    const audit = await page.evaluate(() => {
      /** @type {string[]} */
      const hrefs = [];
      for (const a of document.querySelectorAll('a[href]')) {
        const href = a.getAttribute('href') || '';
        if (href) hrefs.push(href);
      }
      const card = document.getElementById('settings-identity-card');
      const ov = document.getElementById('settings-profile-overlay');
      return {
        hrefs,
        cardAnchors: card ? card.querySelectorAll('a').length : -1,
        profileAnchors: ov ? ov.querySelectorAll('a').length : -1,
        openCalls: window.__openCalls.length,
        openArgs: window.__openCalls.map((a) => String(a[0] ?? '')),
      };
    });
    const accountLinks = audit.hrefs.filter((h) => /mystand\.space/i.test(h));
    expect(accountLinks, 'no <a href> may point at the mystand.space account pages').toEqual([]);
    expect(audit.cardAnchors, 'identity card must contain zero <a> elements').toBe(0);
    expect(audit.profileAnchors, 'profile face must contain zero <a> elements').toBe(0);
    expect(audit.openCalls, 'window.open must be called zero times on the whole identity chain').toBe(0);
    expect(audit.openArgs).toEqual([]);
  });

  // ══ ⑤ 回读一致 + 零假可编辑面 ══════════════════════════════════════════
  test(`T5 [${leg.name}] no fake edit controls; the real read path reads back consistently`, async ({ page }) => {
    await boot(page, { mobile: leg.mobile });
    await openSettings(page, { mobile: leg.mobile });
    await waitUsername(page);

    const first = await page.evaluate(() => {
      const card = document.getElementById('settings-identity-card');
      return {
        username: card?.querySelector('.settings-identity-username')?.textContent?.trim() || '',
        email: card?.querySelector('.settings-identity-email')?.textContent?.trim() || '',
      };
    });

    // 开面 → 关 → 再开：读数必须一致（真实读路 `/api/users/search` 自证 + status claims）。
    await page.click('#settings-identity-capsule');
    const face = page.locator('#settings-profile-overlay');
    await expect(face).toBeVisible();
    const read = () => page.evaluate(() => {
      const ov = document.getElementById('settings-profile-overlay');
      const field = (id) => ov?.querySelector(`.sp-field[data-sp-field="${id}"] .sp-value`)?.textContent?.trim() || '';
      return {
        username: field('username'), nickname: field('nickname'), email: field('email'),
        // 🔴 零假可编辑面：本批上游没有身份写接口 ⇒ 面里不得有任何输入控件/开关
        editable: ov ? ov.querySelectorAll('input, select, textarea, [contenteditable=""], [contenteditable="true"], [role="switch"], [role="checkbox"]').length : -1,
      };
    });
    const open1 = await read();
    await closeFace(page, { mobile: leg.mobile });
    await expect(face).toBeHidden();
    await page.click('#settings-identity-capsule');
    await expect(face).toBeVisible();
    const open2 = await read();

    expect(open1.editable, 'the profile face must expose ZERO editable controls (no fake switches)').toBe(0);
    expect(open1.username).toBe(first.username); // 胶囊 ↔ 面 同源
    expect(open1.email).toBe(first.email);
    expect(open2, 're-opening the face must read back identically (same single read path)').toEqual(open1);
    // 真实读路 = stub 化本地契约（见文件头注「关于 ⑤ 的契约来源」）：值必须等于契约里的
    // username，而不是任何本地伪造/缓存的字符串。
    expect(open1.username).toBe(STUB_USERNAME);
  });
}
