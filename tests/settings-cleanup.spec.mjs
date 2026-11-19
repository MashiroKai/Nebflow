// settings-cleanup.spec.mjs — 0905 设置页清理批（作者 10:54 五项裁定）验收 spec。
//
// 覆盖五项（静态 harness：隔离静态服务器 :8179 + routeWebSocket mock WS +
// route mock API，零真实后端）：
//   T1 ① MCP 区块移除 — zh/en 设置 DOM 无 MCP 标题/#mcp-server-list/[data-mcp]，
//      相邻区块照常渲染；locales 双语均已删 settings.mcpServers 组键。
//   T2 ② 新手指导入口 —— 2026-09-05 批曾裁定封存（不渲染）；2026-10-30
//      「Onboarding 跳过批」作者令（更新、更具体）要求设置里保留重新进入
//      入口，SHOW_RERUN_ONBOARDING 翻回 true ⇒ #btn-rerun-onboarding 渲染；
//      源码保留 flag + settings.rerunOnboarding 键不变。
//   T3 ③ NL 号入口移除 — neblink-nlid* 全选择器零命中；neblink 设备区仍在；
//      neblink.nlId* 键组双语已删；friendsApi 两个 NL API 包装已删。
//      （auth-trim 批 2026-10-20 改写：设置页「切换账号/退出登录」两键退役。）
//      （账户栏整件退役批 2026-11-18：账号分组/账号卡/只读身份行/设备槽全族零命中，
//       `neblink.loggedInAs` 键随之删除。）
//   T4 ④ 设置页头像双态 — 未登录 → logo 可见/photo 隐藏，点击弹登录 modal；
//      mock 登录+avatarUrl → photo 可见/logo 隐藏。（件四·2026-11-02 翻新：已登录态
//      点头像 = **换头像流程**（file chooser），不再弹账号菜单、不再 window.open 官网；
//      账号菜单里的「个人资料」改为开 **App 内**面）。
//   T4c 已登录态登出入口 — ⚠️ 2026-11-18 账户栏整件退役批已**删除该用例**（其载体
//      = 设置页账号卡的 `#neblink-logout`，随卡退役）。登出权威面 = 个人主页设备清单
//      逐设备退出（profileDevices.js `[data-device-signout]` → 同一条
//      `openEndSessionHandoff(false)` 链）。
//   🔴 2026-11-01 作者豁免：T3 的 `neblink.logout` 键黑名单与 T4b 的「一点即跳」
//      断言都是 2026-10-20 auth-trim 口径，作者已豁免 ⇒ 改为「键该在」+「两击才跳」。
//   T5 ⑤ 更新功能退役（auth-trim 批 2026-10-20 作者令）— 设置页无 检查更新/立即更新/
//      进度面/绿点任何残留；不发 checkUpdate/doUpdate 帧；updateCheck.js 模块已删；
//      update.* 键组双语已删；重启服务键与其状态行保留。
//   P   i18n zh/en 对称基线（既有不对称已冻结成基线，新增漂移仍然红；件四重锚）。
//
// 截图（双主题 ≥3 张）落 /tmp/nb-settings-cleanup/shots/。
// Run: npx playwright test tests/settings-cleanup.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8179; // fixed, 8100+ per task discipline — never the host 8080
const SHOTS = '/tmp/nb-settings-cleanup/shots';
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };
// 1x1 transparent PNG — served for the mocked account avatar URL so <img> loads.
const PX_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
const AVATAR_URL = 'https://cdn.neblink-space-mock.test/av.png';

/** Static server rooted at the web dir (path-traversal safe). */
function startServer() {
  return new Promise((resolveDone) => {
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
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;
test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

/**
 * Boot the real shell against mocks.
 * @param {import('@playwright/test').Page} page
 * @param {{locale?: string, colorScheme?: 'light'|'dark', loggedIn?: boolean, avatarUrl?: string|null}} opts
 * @returns {Promise<{send: (obj: object) => void, frames: object[]}>}
 */
async function bootPage(page, opts = {}) {
  const { locale = 'zh-CN', colorScheme = 'light', loggedIn = false, avatarUrl = null } = opts;
  await page.emulateMedia({ colorScheme });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.route('**/api/**', r => r.fulfill({ json: {} })); // catch-all first
  await page.route('**/api/neblink/status', r => r.fulfill({
    json: {
      loggedIn,
      device: loggedIn
        ? { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: avatarUrl || '',
            // （账号栏整件退役 2026-11-18：原只读登录态行 .neblink-account-ro 的取数已删，
            //  此处保留 status 夹具的 email/displayName 供头像与身份读数消费。）
            email: 'me@example.com', displayName: '测试用户' }
        : null,
      peers: [],
    },
  }));
  if (avatarUrl) await page.route('**/cdn.neblink-space-mock.test/**', r => r.fulfill({ contentType: 'image/png', body: PX_PNG }));

  const frames = [];
  let wsSend = () => {};
  await page.routeWebSocket(/\/ws/, ws => {
    wsSend = obj => ws.send(JSON.stringify(obj));
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      frames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  return {
    frames,
    send: obj => wsSend(obj),
  };
}

/** Open the settings modal and wait for render. */
async function openSettings(page) {
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
}

// ══ T1 · ① MCP 区块移除 ═══════════════════════════════════
for (const [locale, mcpTitle] of [['zh-CN', 'MCP 服务器'], ['en', 'MCP Servers']]) {
  test(`T1 [${locale}] settings DOM has no MCP section, neighbors intact`, async ({ page }) => {
    await bootPage(page, { locale });
    await openSettings(page);
    const dump = await page.evaluate(() => {
      const content = document.getElementById('settings-content');
      return {
        titles: [...content.querySelectorAll('.settings-section-title')].map(e => e.textContent.trim()),
        mcpList: !!content.querySelector('#mcp-server-list'),
        mcpCards: content.querySelectorAll('[data-mcp]').length,
        mcpToggles: content.querySelectorAll('.cfg-toggle[data-mcp]').length,
        emptyMcp: [...content.querySelectorAll('.cfg-empty')].some(e => /MCP/.test(e.textContent)),
        // 2026-09-06 合并钉（设备区入账号区）：账号区 = 头像入口 + neblink 区内嵌。
        avatarEntry: !!content.querySelector('#settings-avatar-entry'),
        // 账户栏整件退役（作者令 2026-11-18 16:36/16:45）：账号区不再有 neblink 内嵌块
        // （账号卡 / 只读登录账号行 / 未登录提示行全族）。
        neblinkArea: !!content.querySelector('.neblink-login-section, .neblink-logged-in, .neblink-account-ro, #account-card'),
      };
    });
    expect(dump.titles, `[${locale}] MCP section title must be gone`).not.toContain(mcpTitle);
    expect(dump.mcpList, `[${locale}] #mcp-server-list must not exist`).toBe(false);
    expect(dump.mcpCards, `[${locale}] no [data-mcp] cards`).toBe(0);
    expect(dump.mcpToggles, `[${locale}] no MCP toggles`).toBe(0);
    expect(dump.emptyMcp, `[${locale}] no "未配置 MCP" empty state`).toBe(false);
    // Adjacent sections survive; Device Link lives inside the unified account
    // block (author 2026-09-06, 7caf42724) — the standalone section title is
    // merged away and the account section must carry the avatar entry + the
    // neblink (device) area.
    if (locale === 'zh-CN') {
      // 2026-10-20 校准：模型方案节已随早前批次并入 LLM 服务商。
      // 2026-11-18（本批）：账号栏整件退役 ⇒ 「账号」组题不再在场（判据反转）。
      for (const t of ['运行时', 'LLM 服务商', '密钥', '高级', '关于']) {
        expect(dump.titles, `section ${t} must remain`).toContain(t);
      }
      expect(dump.titles, 'account column heading must be retired (2026-11-18 作者令)').not.toContain('账号');
      expect(dump.titles, 'standalone Device Link section must stay merged away').not.toContain('设备互联');
    }
    if (locale === 'en') {
      for (const t of ['Runtime', 'LLM Providers', 'Secrets', 'Advanced', 'About']) {
        expect(dump.titles, `section ${t} must remain`).toContain(t);
      }
      expect(dump.titles, 'account column heading must be retired (2026-11-18 作者令)').not.toContain('Account');
      expect(dump.titles, 'standalone Device Link section must stay merged away').not.toContain('Device Link');
    }
    expect(dump.avatarEntry, 'unified account block must keep the avatar entry').toBe(true);
    expect(dump.neblinkArea, 'account column (neblink embed) must be retired — zero-hit, not hidden').toBe(false);
  });
}

// ══ T2 · ② 新手指导入口（2026-10-30 跳过批翻回可见）══════════
test('T2 rerun-onboarding entry rendered (unsealed by 2026-10-30 skip batch), code + i18n key retained', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  const dump = await page.evaluate(() => {
    const content = document.getElementById('settings-content');
    return {
      btn: !!content.querySelector('#btn-rerun-onboarding'),
      advancedTitle: [...content.querySelectorAll('.settings-section-title')].map(e => e.textContent.trim()),
      jsonBtn: !!content.querySelector('#btn-toggle-json'),
    };
  });
  expect(dump.btn, '#btn-rerun-onboarding must render while unsealed (2026-10-30 跳过批作者令)').toBe(true);
  expect(dump.advancedTitle, 'advanced section must remain').toContain('高级');
  expect(dump.jsonBtn, 'sibling raw-JSON button must remain').toBe(true);

  // 封存期「隐藏 ≠ 删除」的保留断言沿用：flag + binding + i18n key 都在源码里。
  const src = await page.evaluate(async () => (await (await fetch('/js/sidebar.js')).text()));
  expect(src, 'SHOW_RERUN_ONBOARDING flag must exist in source').toContain('SHOW_RERUN_ONBOARDING');
  expect(src, 'button template + binding must exist behind the flag').toContain('btn-rerun-onboarding');
  const zh = await page.evaluate(async () => (await import('/js/locales/zh-CN.js')).default);
  expect(zh['settings.rerunOnboarding'], 'i18n key settings.rerunOnboarding must be retained').toBeTruthy();
});

// ══ T3 · ③ NL 号入口移除 + auth-trim：两键退役、只读身份行 ════════════════
// 🔴 2026-09-15 设备/好友统一返工（作者八点）改写了设备侧断言（设备入口迁联系人面板）。
// 🔴 auth-trim 批（2026-10-20 作者令）：设置页「切换账号/退出登录」两键退役 ——
//    产品全走纯官网路径，控制台不承担认证态管理；登录态只留只读身份行
//    （.neblink-account-ro，neblink.loggedInAs）。键组 switchAccount/logout 族双语已删。
test('T3 NL-ID entry gone (logged-in view), auth buttons removed with read-only identity, keys + API wrappers deleted', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', loggedIn: true });
  await openSettings(page);
  await page.waitForSelector('#settings-content [data-panel-group="general"]', { timeout: 8000 });
  const dump = await page.evaluate(() => {
    const content = document.getElementById('settings-content');
    const sels = ['#neblink-nlid-edit', '.neblink-nlid', '.neblink-nlid-editing', '.neblink-nlid-input',
      '#neblink-nlid-save', '#neblink-nlid-cancel', '.neblink-nlid-statusline', '.neblink-nlid-value'];
    return {
      leaked: sels.filter(s => content.querySelector(s)),
      // ①④（作者 2026-09-15）：旧内联设备列表已移出设置 ⇒ 账号段无 .neblink-peers-list。
      peersList: !!content.querySelector('.neblink-peers-list'),
      // friendseal ①-A（2026-09-25）设置页曾加受许可设备展开区（#settings-devices-slot）；
      // 账户栏整件退役批（2026-11-18）已把该槽连账号卡一并删除 ⇒ 裸计数即判据。
      deviceRows: content.querySelectorAll('.neblink-peer').length,
      oldWindowEntry: content.querySelectorAll('.dropbox-clickable').length,
      // auth-trim（2026-10-20）：两键零残留 + 只读身份行在位。
      switchBtn: !!content.querySelector('#neblink-switch-btn'),
      switchModal: !!content.querySelector('.neblink-switch-modal, #nebflow-switch-account-modal'),
      // 旧 id `#neblink-logout-btn`（10-20 退役的那枚）不得复活；2026-11 补回的登出
      // 入口是**另一个 id** `#neblink-logout`（见 T4c），两者不是一回事。
      logout: !!content.querySelector('#neblink-logout-btn'),
      // 账户栏整件退役（作者令 2026-11-18）：账号分组 / 账号卡 / 只读身份行全族零命中。
      accountGroup: !!content.querySelector('[data-panel-group="account"]'),
      accountCard: !!content.querySelector('#account-card'),
      accountBlock: !!content.querySelector('.neblink-logged-in'),
      roLine: content.querySelector('.neblink-account-ro')?.textContent || '',
    };
  });
  expect(dump.leaked, 'NL-ID selectors must be zero-hit: ' + JSON.stringify(dump.leaked)).toEqual([]);
  expect(dump.peersList, '① device list must be GONE from settings (moved to the contacts panel)').toBe(false);
  expect(dump.deviceRows, '① zero device rows left in the settings account block').toBe(0);
  expect(dump.oldWindowEntry, '① the old device-window entry (.dropbox-clickable) must be gone').toBe(0);
  expect(dump.switchBtn, 'auth-trim: switch-account button must be GONE from the console').toBe(false);
  expect(dump.logout, 'auth-trim: logout button must be GONE from the console').toBe(false);
  expect(dump.switchModal, 'auth-trim: the switch-account modal must never render').toBe(false);
  expect(dump.accountGroup, 'settings account group must be retired (2026-11-18 作者令)').toBe(false);
  expect(dump.accountCard, 'settings account card must be retired').toBe(false);
  expect(dump.accountBlock, 'settings account block must be retired').toBe(false);
  expect(dump.roLine, 'read-only signed-in line must be retired').toBe('');

  // i18n key group deleted in BOTH locales; friendsApi NL wrappers deleted.
  // （p547b 2026-09-15 误报修复：探针剥掉注释再匹配——注释里的退役墓碑不算代码。）
  const code = await page.evaluate(async () => {
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    const nlKeys = (m) => Object.keys(m).filter(k => k.startsWith('neblink.nlId'));
    // 2026-11-01 作者豁免：neblink.logout 已从黑名单移出（该键随已登录态退出登录
    // 入口补回而该存在：账号段次级键 + 头像菜单项共用同一文案）。
    const authKeys = (m) => Object.keys(m).filter(k =>
      ['neblink.switchAccount', 'neblink.loggingOut', 'neblink.useOtherAccount', 'neblink.currentTag'].includes(k));
    const raw = await (await fetch('/js/friendsApi.js')).text();
    // strip block comments + line comments, then match code only.
    const apiSrc = raw.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
    return {
      zhNl: nlKeys(zh), enNl: nlKeys(en),
      zhAuth: authKeys(zh), enAuth: authKeys(en),
      zhRo: zh['neblink.loggedInAs'] || '', enRo: en['neblink.loggedInAs'] || '',
      apiSetNeblinkId: apiSrc.includes('setNeblinkId'),
      apiAvailable: apiSrc.includes('neblinkIdAvailable'),
    };
  });
  expect(code.zhNl, 'zh neblink.nlId* keys must be gone').toEqual([]);
  expect(code.enNl, 'en neblink.nlId* keys must be gone').toEqual([]);
  expect(code.zhAuth, 'zh auth-trim key group must be gone').toEqual([]);
  expect(code.enAuth, 'en auth-trim key group must be gone').toEqual([]);
  expect(code.zhRo, 'zh read-only identity key must be gone (account card retired 2026-11-18)').toBe('');
  expect(code.enRo, 'en read-only identity key must be gone (account card retired 2026-11-18)').toBe('');
  expect(code.apiSetNeblinkId, 'friendsApi.setNeblinkId must be deleted').toBe(false);
  expect(code.apiAvailable, 'friendsApi.neblinkIdAvailable must be deleted').toBe(false);
});

// ══ T4 · ④ 设置页头像双态（行为与侧边栏头像一致）═══════════
test('T4a avatar section logged-out → logo + login modal on click', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', loggedIn: false });
  await openSettings(page);
  const state = await page.evaluate(() => {
    const entry = document.getElementById('settings-avatar-entry');
    const logo = entry?.querySelector('.settings-avatar-logo');
    const photo = entry?.querySelector('.settings-avatar-photo');
    // 头像区文案 span 已随 2026-09-06 修整退场（avatar-only 入口，无
    // .settings-avatar-text；登录态判定改由 logo/photo 双态 + 点击行为承载）。
    const text = entry?.querySelector('.settings-avatar-text');
    const vis = el => !!el && el.hidden === false && getComputedStyle(el).display !== 'none';
    return { exists: !!entry, logoVisible: vis(logo), photoVisible: vis(photo), textSpan: !!text };
  });
  expect(state.exists, 'avatar entry must exist').toBe(true);
  expect(state.logoVisible, 'logged out → logo visible').toBe(true);
  expect(state.photoVisible, 'logged out → photo hidden').toBe(false);
  expect(state.textSpan, 'in-area text span must stay removed (avatar-only entry, author 2026-09-06)').toBe(false);

  // Click behavior = the Activity Bar avatar's (login modal), via forwarded click.
  await page.click('#settings-avatar-entry');
  await page.waitForSelector('.nebflow-login-modal', { timeout: 8000 });
  const modalOn = await page.evaluate(() => !!document.querySelector('.nebflow-login-modal'));
  expect(modalOn, 'click (logged out) must open the same login modal as the bar avatar').toBe(true);
});

// 件四翻新（作者令 2026-11-02「现在的设计是跳转到官网，但是我是要在这里直接修改」）：
// 旧断言（两选一菜单 + 菜单里的「个人主页」一击 window.open）已被本批**有意翻新**——
// 设置页头像的语义改为作者原话的「点头像就是直接换头像」；账号菜单仍在
// （活动条头像路径），但菜单里「个人资料」改为开 App 内面，window.open 归零。
test('T4b settings avatar logged-in → photo + click starts avatar-change flow (zero window.open)', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', loggedIn: true, avatarUrl: AVATAR_URL });
  await openSettings(page);
  await page.evaluate(() => {
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
  });
  // 就绪门（paintAvatarSlot 先等字节可绘制才翻面）：等 photo 真正可见再读
  // 读数（否则偶发抢跑：首帧时 photo 仍隐/废弃 logo 占槽）。
  await page.waitForFunction(() => {
    const p = document.querySelector('#settings-avatar-entry .settings-avatar-photo');
    return !!p && p.hidden === false && getComputedStyle(p).display !== 'none';
  }, { timeout: 8000 });
  const state = await page.evaluate(() => {
    const entry = document.getElementById('settings-avatar-entry');
    const logo = entry?.querySelector('.settings-avatar-logo');
    const photo = entry?.querySelector('.settings-avatar-photo');
    // 同 T4a：文案 span 已退场（avatar-only 入口）。
    const text = entry?.querySelector('.settings-avatar-text');
    const vis = el => !!el && el.hidden === false && getComputedStyle(el).display !== 'none';
    return { logoVisible: vis(logo), photoVisible: vis(photo), src: photo?.getAttribute('src') || '', textSpan: !!text };
  });
  expect(state.photoVisible, 'logged in with avatarUrl → photo visible').toBe(true);
  expect(state.logoVisible, 'logo hidden while photo shows').toBe(false);
  expect(state.src, 'photo src = account avatarUrl').toBe(AVATAR_URL);
  expect(state.textSpan, 'in-area text span must stay removed (avatar-only entry, author 2026-09-06)').toBe(false);

  // 件四：点头像 = 换头像流程（隐藏 file input 被触发），不弹菜单、零外链。
  const chooser = page.waitForEvent('filechooser', { timeout: 8000 });
  await page.click('#settings-avatar-entry');
  await chooser;
  const stage = await page.evaluate(() => ({
    opens: window.__openCalls.length,
    menuOpen: !!document.querySelector('.fm-context-menu'),
    inputPresent: !!document.getElementById('settings-avatar-file'),
  }));
  expect(stage.inputPresent, 'the hidden file input (avatar-change flow) must be mounted').toBe(true);
  expect(stage.opens, 'clicking the avatar must NOT window.open anything').toBe(0);
  expect(stage.menuOpen, 'clicking the avatar must NOT open the account menu any more').toBe(false);

  // 账号菜单路径（活动条头像）：「个人资料」开 App 内面，仍零 window.open。
  await bootPage(page, { locale: 'zh-CN', loggedIn: true, avatarUrl: AVATAR_URL });
  await page.evaluate(() => {
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
  });
  await page.click('#activity-avatar');
  await page.waitForSelector('.fm-context-menu', { timeout: 5000 });
  const labels = await page.evaluate(() =>
    [...document.querySelectorAll('.fm-context-menu button[role="menuitem"]')].map(b => b.textContent.trim()));
  expect(labels, 'account menu must still offer [个人主页, 退出登录]').toEqual(['个人主页', '退出登录']);
  await page.locator('.fm-context-menu button[role="menuitem"]', { hasText: '个人主页' }).click();
  await page.waitForTimeout(300);
  const menuStage = await page.evaluate(() => ({
    opens: window.__openCalls.length,
    profileOpen: (() => { const ov = document.getElementById('settings-profile-overlay'); return !!ov && ov.hidden === false; })(),
    menuGone: !document.querySelector('.fm-context-menu'),
  }));
  expect(menuStage.opens, '「个人主页」must NOT window.open any more (in-app face instead)').toBe(0);
  expect(menuStage.profileOpen, '「个人主页」must open the in-app profile face').toBe(true);
  expect(menuStage.menuGone, 'the menu closes after the item click').toBe(true);
});

// ══ T4c · 已登录态登出入口 —— 已随账户栏整件退役（2026-11-18 作者令）═════════
// 原文（2026-11-01「退出登录没有入口」修复批）：设置页账号段渲染 `#neblink-logout`，
// 点击走既有 `openEndSessionHandoff(false)`。
// 🔴 账户栏整件退役（16:36「设置里的账户这一栏完全移除」+ 16:45「底部的账户栏 功能
// 重复 信息重复…不要有重复的功能和信息」）⇒ 该入口的载体（账号卡）不存在 ⇒ 用例删除。
// 登出动作的权威面 = 个人主页设备清单的**逐设备退出**（`profileDevices.js`，
// `[data-device-signout]` → 本机行走同一条 `openEndSessionHandoff(false)` 链）；
// 设置页零复述。该链本体（neblink.js `openEndSessionHandoff`）保留。
// 现行受检点见 T3（账号栏零命中）。

// ══ T5 · ⑤ 更新功能退役（auth-trim 批 2026-10-20 作者令）═══════════════════
// 设置页「更新」整链退役（未来由官网统一检测更新取代）：入口 DOM 零残留、不发
// checkUpdate/doUpdate 帧、无绿点、updateCheck.js 模块已删、update.* 键组双语已删；
// 「重启服务」键与其状态行（restartStatus/restartResult 消费）保留。
test('T5 update feature fully retired: no entry DOM, no update WS frames, no dot, module + keys gone; restart kept', async ({ page }) => {
  const h = await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await page.waitForTimeout(400);
  const dump = await page.evaluate(() => {
    const content = document.getElementById('settings-content');
    const sels = ['#btn-check-update', '#update-status', '#update-progress', '#update-progress-impact',
      '#update-progress-versions', '#update-action', '#btn-do-update', '#btn-dismiss-update'];
    return {
      leaked: sels.filter(s => content.querySelector(s)),
      dot: !!document.querySelector('#settings-btn .settings-update-dot'),
      restartBtn: !!content.querySelector('#btn-restart-gateway'),
      restartStatus: !!content.querySelector('#restart-status'),
      aboutText: content.querySelector('.about-info')?.textContent || '',
    };
  });
  expect(dump.leaked, 'update selectors must be zero-hit: ' + JSON.stringify(dump.leaked)).toEqual([]);
  expect(dump.dot, 'no update dot on the settings button').toBe(false);
  expect(dump.restartBtn, 'restart service button must remain').toBe(true);
  expect(dump.restartStatus, 'restart status line must remain').toBe(true);
  expect(dump.aboutText, 'About section still renders version + connection').toContain('1.4.0');

  // No update WS frame may be sent (silent auto check retired) — probe all frames.
  const updateFrames = h.frames.filter(m => m.type === 'checkUpdate' || m.type === 'doUpdate');
  expect(updateFrames, 'no checkUpdate/doUpdate frames may be sent').toEqual([]);

  // A legacy updateCheckResult push must stay fully inert (no dot, no crash).
  h.send({ type: 'updateCheckResult', hasUpdate: true, latestVersion: '9.9.9' });
  await page.waitForTimeout(300);
  expect(await page.evaluate(() => !!document.querySelector('#settings-btn .settings-update-dot')),
    'legacy updateCheckResult frame must be inert (no dot)').toBe(false);

  // Chain retired at the source: module gone, keys gone, no sender in sidebar code.
  const probe = await page.evaluate(async () => {
    const mod = await fetch('/js/updateCheck.js');
    const sidebarSrc = await (await fetch('/js/sidebar.js')).text();
    const mainSrc = await (await fetch('/js/main.js')).text();
    const activitySrc = await (await fetch('/js/activityBar.js')).text();
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    const updKeys = (m) => Object.keys(m).filter(k => k.startsWith('update.') || k.startsWith('settings.check') || k.startsWith('settings.update') || k.startsWith('settings.upT'));
    const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
    return {
      modStatus: mod.status,
      updZh: updKeys(zh), updEn: updKeys(en),
      zhRestart: Object.keys(zh).filter(k => k.startsWith('restart.phase.') || k.startsWith('settings.restart')),
      sendsSidebar: ['checkUpdate', 'doUpdate'].filter(s => strip(sidebarSrc).includes(`'${s}'`)),
      importSidebar: strip(sidebarSrc).includes('updateCheck'),
      importMain: strip(mainSrc).includes('updateCheck'),
      importActivity: strip(activitySrc).includes('updateCheck'),
      knownAccountsStatus: (await fetch('/js/knownAccounts.js')).status,
    };
  });
  expect(probe.modStatus, 'updateCheck.js must be deleted').toBe(404);
  expect(probe.updZh, 'zh update keys must be gone').toEqual([]);
  expect(probe.updEn, 'en update keys must be gone').toEqual([]);
  expect(probe.zhRestart.length, 'restart keys must be retained').toBeGreaterThanOrEqual(10);
  expect(probe.sendsSidebar, 'sidebar must not send checkUpdate/doUpdate').toEqual([]);
  expect(probe.importSidebar, 'sidebar must not reference updateCheck').toBe(false);
  expect(probe.importMain, 'main must not reference updateCheck').toBe(false);
  expect(probe.importActivity, 'activityBar must not reference updateCheck').toBe(false);
  expect(probe.knownAccountsStatus, 'knownAccounts.js must be deleted (auth-trim)').toBe(404);
});

// ══ P · i18n zh/en 对称基线（非零偏差基线化；件四·2026-11-02 重锚）═══════
// 修前本测试断言两侧键集**完全相等**；但仓库既有不对称（实测 HEAD e667976dd：
// zh 2356 / en 2349，净差 7；onlyZh 8 项 = chat.allowTool / chat.permSource /
// chat.permLevel.{warning,dangerous,critical} / chat.permExecCmd / chat.copied /
// chat.copy；onlyEn 1 项 = mermaid.renderFailed）⇒ 该断言在 HEAD 上本来就是红的
// （本批未加剧：新增 14 键两侧逐字对称，净差仍为 7）。
// 重锚方式 = 与 check-js-types 基线同一体例：把**既有**不对称冻结成基线，新增任何
// 破坏对称性仍然红；基线只许变小。
// 基线按字典序（与测试代码里的 .sort() 对位，避免排序差异造成的假红）。
const LOCALE_ASYM_BASELINE = {
  onlyZh: [
    'chat.allowTool',
    'chat.copied',
    'chat.copy',
    'chat.permExecCmd',
    'chat.permLevel.critical',
    'chat.permLevel.dangerous',
    'chat.permLevel.warning',
    'chat.permSource',
  ],
  onlyEn: ['mermaid.renderFailed'],
};
test('P locale parity: zh/en asymmetry held at the frozen baseline (no new drift)', async ({ page }) => {
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  const counts = await page.evaluate(async () => {
    const zh = Object.keys((await import('/js/locales/zh-CN.js')).default);
    const en = Object.keys((await import('/js/locales/en.js')).default);
    return {
      onlyZh: zh.filter(k => !en.includes(k)).sort(),
      onlyEn: en.filter(k => !zh.includes(k)).sort(),
      zh: zh.length, en: en.length,
    };
  });
  expect(counts.onlyZh, 'keys only in zh must stay within the frozen baseline')
    .toEqual(LOCALE_ASYM_BASELINE.onlyZh);
  expect(counts.onlyEn, 'keys only in en must stay within the frozen baseline')
    .toEqual(LOCALE_ASYM_BASELINE.onlyEn);
  // 净差（zh - en）恒等基线差值 7：新增键必须两侧对称，不得加剧不对称。
  expect(counts.zh - counts.en, 'zh/en net key-count difference must stay at the baseline (7)').toBe(7);
});

// ══ 双主题截图（设置全貌 / 账号块特写 / 头像双态）══════════════
async function shots(colorScheme, tag) {
  test(`shots[${tag}] settings full + account block closeup + avatar dual state`, async ({ page }) => {
    // 1) auth-trim 后设置页全貌（含头像区 + 关于区，无 MCP/NL 区、无更新入口）
    await bootPage(page, { locale: 'zh-CN', colorScheme, loggedIn: true, avatarUrl: AVATAR_URL });
    await openSettings(page);
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${SHOTS}/settings-clean-${tag}.png`, fullPage: false });

    // 2) 首屏特写（账户栏已整件退役 2026-11-18 ⇒ 原「账号块特写」改为设置页首组特写，
    //    用于肉眼复核「账号栏零命中、首组 = 通用」）
    const account = await page.evaluate(() => {
      const el = document.querySelector('#settings-content [data-panel-group="general"]');
      const r = (el || document.body).getBoundingClientRect();
      return { x: Math.max(0, r.x - 24), y: Math.max(0, r.y - 40), width: r.width + 48, height: r.height + 64 };
    });
    await page.screenshot({ path: `${SHOTS}/account-ro-${tag}.png`, clip: account });

    // 3) 头像双态特写（登录态已拍全貌 + 账号块特写；此处面板仍开，直接取头像区）
    await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
    const top = await page.evaluate(() => {
      const r = document.getElementById('settings-avatar-entry').getBoundingClientRect();
      return { x: Math.max(0, r.x - 24), y: Math.max(0, r.y - 40), width: r.width + 48, height: r.height + 64 };
    });
    await page.screenshot({ path: `${SHOTS}/avatar-in-${tag}.png`, clip: top });

    await bootPage(page, { locale: 'zh-CN', colorScheme, loggedIn: false });
    await page.click('#settings-btn');
    await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
    const top2 = await page.evaluate(() => {
      const r = document.getElementById('settings-avatar-entry').getBoundingClientRect();
      return { x: Math.max(0, r.x - 24), y: Math.max(0, r.y - 40), width: r.width + 48, height: r.height + 64 };
    });
    await page.screenshot({ path: `${SHOTS}/avatar-out-${tag}.png`, clip: top2 });
  });
}
await shots('light', 'light');
await shots('dark', 'dark');
