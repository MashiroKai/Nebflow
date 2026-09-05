// settings-cleanup.spec.mjs — 0905 设置页清理批（作者 10:54 五项裁定）验收 spec。
//
// 覆盖五项（静态 harness：隔离静态服务器 :8179 + routeWebSocket mock WS +
// route mock API，零真实后端）：
//   T1 ① MCP 区块移除 — zh/en 设置 DOM 无 MCP 标题/#mcp-server-list/[data-mcp]，
//      相邻区块照常渲染；locales 双语均已删 settings.mcpServers 组键。
//   T2 ② 新手指导入口封存 — #btn-rerun-onboarding 不渲染；源码保留
//      SHOW_RERUN_ONBOARDING flag + settings.rerunOnboarding 键（隐藏 ≠ 删除）。
//   T3 ③ NL 号入口移除 — neblink-nlid* 全选择器零命中；neblink 设备区仍在；
//      neblink.nlId* 键组双语已删；friendsApi 两个 NL API 包装已删。
//   T4 ④ 设置页头像双态 — 未登录 → logo 可见/photo 隐藏，点击弹登录 modal；
//      mock 登录+avatarUrl → photo 可见/logo 隐藏，点击 window.open 个人主页
//      （与 Activity Bar 头像共用行为：#settings-avatar-entry 转发原生 click）。
//   T5 ⑤ 检查更新自动化绿点 — 推送 updateCheckResult(hasUpdate) → settings-btn
//      出现 success token 绿点；打开设置 → 绿点清除 + About 回显新版本；
//      手动检查无新版本 → 无绿点且 About 回显已是最新。
//   P   i18n zh/en parity（全键集合相等）。
//
// 截图（双主题 ≥4 张）落 /tmp/nb-settings-cleanup/shots/。
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
        ? { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: avatarUrl || '' }
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
      if (m.type === 'checkUpdate') {
        // Manual check roundtrip: report up-to-date unless the test preempts it.
        if (m.__reply) wsSend(m.__reply);
        else ws.send(JSON.stringify({ type: 'updateCheckResult', hasUpdate: false }));
      }
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
    pushUpdate: (hasUpdate, latestVersion) => wsSend({ type: 'updateCheckResult', hasUpdate, latestVersion }),
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
      };
    });
    expect(dump.titles, `[${locale}] MCP section title must be gone`).not.toContain(mcpTitle);
    expect(dump.mcpList, `[${locale}] #mcp-server-list must not exist`).toBe(false);
    expect(dump.mcpCards, `[${locale}] no [data-mcp] cards`).toBe(0);
    expect(dump.mcpToggles, `[${locale}] no MCP toggles`).toBe(0);
    expect(dump.emptyMcp, `[${locale}] no "未配置 MCP" empty state`).toBe(false);
    // Adjacent sections survive the amputation-free removal.
    if (locale === 'zh-CN') {
      for (const t of ['账号', '设备互联', '运行时', 'LLM 服务商', '模型方案', '高级', '关于']) {
        expect(dump.titles, `section ${t} must remain`).toContain(t);
      }
    }
    if (locale === 'en') {
      for (const t of ['Account', 'Device Link', 'Runtime', 'LLM Providers', 'Model Presets', 'Advanced', 'About']) {
        expect(dump.titles, `section ${t} must remain`).toContain(t);
      }
    }
  });
}

// ══ T2 · ② 新手指导入口封存 ═══════════════════════════════
test('T2 rerun-onboarding entry sealed (not rendered), code + i18n key retained', async ({ page }) => {
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
  expect(dump.btn, '#btn-rerun-onboarding must not render while sealed').toBe(false);
  expect(dump.advancedTitle, 'advanced section must remain').toContain('高级');
  expect(dump.jsonBtn, 'sibling raw-JSON button must remain').toBe(true);

  // Sealed ≠ deleted: flag + binding + i18n key all still in source.
  const src = await page.evaluate(async () => (await (await fetch('/js/sidebar.js')).text()));
  expect(src, 'SHOW_RERUN_ONBOARDING flag must exist in source').toContain('SHOW_RERUN_ONBOARDING');
  expect(src, 'button template + binding must exist behind the flag').toContain('btn-rerun-onboarding');
  const zh = await page.evaluate(async () => (await import('/js/locales/zh-CN.js')).default);
  expect(zh['settings.rerunOnboarding'], 'i18n key settings.rerunOnboarding must be retained').toBeTruthy();
});

// ══ T3 · ③ NL 号入口移除 ══════════════════════════════════
test('T3 NL-ID entry gone (logged-in view), devices section intact, keys + API wrappers deleted', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', loggedIn: true });
  await openSettings(page);
  await page.waitForSelector('.neblink-logged-in', { timeout: 8000 });
  const dump = await page.evaluate(() => {
    const content = document.getElementById('settings-content');
    const sels = ['#neblink-nlid-edit', '.neblink-nlid', '.neblink-nlid-editing', '.neblink-nlid-input',
      '#neblink-nlid-save', '#neblink-nlid-cancel', '.neblink-nlid-statusline', '.neblink-nlid-value'];
    return {
      leaked: sels.filter(s => content.querySelector(s)),
      devices: [...content.querySelectorAll('.neblink-section-label')].map(e => e.textContent.trim()),
      logout: !!content.querySelector('#neblink-logout-btn'),
    };
  });
  expect(dump.leaked, 'NL-ID selectors must be zero-hit: ' + JSON.stringify(dump.leaked)).toEqual([]);
  expect(dump.devices, 'devices label must remain').toContain('设备');
  expect(dump.logout, 'logout button must remain').toBe(true);

  // i18n key group deleted in BOTH locales; friendsApi NL wrappers deleted.
  const code = await page.evaluate(async () => {
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    const nlKeys = (m) => Object.keys(m).filter(k => k.startsWith('neblink.nlId'));
    const apiSrc = await (await fetch('/js/friendsApi.js')).text();
    return {
      zhNl: nlKeys(zh), enNl: nlKeys(en),
      apiSetNeblinkId: apiSrc.includes('setNeblinkId'),
      apiAvailable: apiSrc.includes('neblinkIdAvailable'),
      apiDocNote: apiSrc.includes('NL 号入口已随设置页 NL 号入口移除') || apiSrc.includes('neblink-id'),
    };
  });
  expect(code.zhNl, 'zh neblink.nlId* keys must be gone').toEqual([]);
  expect(code.enNl, 'en neblink.nlId* keys must be gone').toEqual([]);
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
    const text = entry?.querySelector('.settings-avatar-text');
    const vis = el => !!el && el.hidden === false && getComputedStyle(el).display !== 'none';
    return { exists: !!entry, logoVisible: vis(logo), photoVisible: vis(photo), text: text?.textContent || '' };
  });
  expect(state.exists, 'avatar entry must exist').toBe(true);
  expect(state.logoVisible, 'logged out → logo visible').toBe(true);
  expect(state.photoVisible, 'logged out → photo hidden').toBe(false);
  expect(state.text, 'logged out copy').toContain('登录');

  // Click behavior = the Activity Bar avatar's (login modal), via forwarded click.
  await page.click('#settings-avatar-entry');
  await page.waitForSelector('.nebflow-login-modal', { timeout: 8000 });
  const modalOn = await page.evaluate(() => !!document.querySelector('.nebflow-login-modal'));
  expect(modalOn, 'click (logged out) must open the same login modal as the bar avatar').toBe(true);
});

test('T4b avatar section logged-in → photo + profile window.open on click', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', loggedIn: true, avatarUrl: AVATAR_URL });
  await openSettings(page);
  await page.evaluate(() => {
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
  });
  const state = await page.evaluate(() => {
    const entry = document.getElementById('settings-avatar-entry');
    const logo = entry?.querySelector('.settings-avatar-logo');
    const photo = entry?.querySelector('.settings-avatar-photo');
    const vis = el => !!el && el.hidden === false && getComputedStyle(el).display !== 'none';
    return { logoVisible: vis(logo), photoVisible: vis(photo), src: photo?.getAttribute('src') || '', text: entry?.querySelector('.settings-avatar-text')?.textContent || '' };
  });
  expect(state.photoVisible, 'logged in with avatarUrl → photo visible').toBe(true);
  expect(state.logoVisible, 'logo hidden while photo shows').toBe(false);
  expect(state.src, 'photo src = account avatarUrl').toBe(AVATAR_URL);
  expect(state.text, 'logged in copy').toContain('个人主页');

  await page.click('#settings-avatar-entry');
  await page.waitForTimeout(300);
  const opens = await page.evaluate(() => window.__openCalls?.length || 0);
  expect(opens, 'click (logged in) must open the profile page like the bar avatar').toBe(1);
});

// ══ T5 · ⑤ 检查更新自动化绿点 ══════════════════════════════
test('T5 auto check → green dot (success token) → opening settings clears + About echoes', async ({ page }) => {
  const h = await bootPage(page, { locale: 'zh-CN' });
  await page.waitForTimeout(400);

  // No update pushed yet → no dot.
  expect(await page.evaluate(() => !!document.querySelector('#settings-btn .settings-update-dot')),
    'no dot before any updateCheckResult').toBe(false);

  // Silent auto-style push (panel closed): dot lights, nothing else visible.
  h.pushUpdate(true, '9.9.9');
  await page.waitForSelector('#settings-btn .settings-update-dot', { timeout: 5000 });
  const dotColor = await page.evaluate(() => {
    const dot = document.querySelector('#settings-btn .settings-update-dot');
    return getComputedStyle(dot).backgroundColor;
  });
  const successRgb = await page.evaluate(() => {
    const probe = document.createElement('span');
    probe.style.color = 'var(--color-success)';
    document.body.appendChild(probe);
    const c = getComputedStyle(probe).color;
    probe.remove();
    return c;
  });
  expect(dotColor, 'dot color must be the success token').toBe(successRgb);

  // No toast/modal leaked by the silent path.
  const quiet = await page.evaluate(() => !document.querySelector('.nebflow-toast, .toast, .nebflow-login-modal'));
  expect(quiet, 'auto check must be silent (no toast/modal)').toBe(true);

  // Opening settings → dot cleared + About echoes the recorded update state.
  await openSettings(page);
  await page.waitForTimeout(200);
  expect(await page.evaluate(() => !!document.querySelector('#settings-btn .settings-update-dot')),
    'dot must clear once settings is opened').toBe(false);
  const about = await page.evaluate(() => ({
    status: document.getElementById('update-status')?.textContent || '',
    actionVisible: document.getElementById('update-action')?.style.display === 'block',
  }));
  expect(about.status, 'About echo of the auto result').toContain('9.9.9');
  expect(about.actionVisible, 'update action row revealed').toBe(true);
});

test('T5b manual check up-to-date → no dot, About echoes latest; badge not lit', async ({ page }) => {
  const h = await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await page.click('#btn-check-update');
  await page.waitForFunction(() => (document.getElementById('update-status')?.textContent || '').length > 0, null, { timeout: 5000 });
  const about = await page.evaluate(() => ({
    status: document.getElementById('update-status')?.textContent || '',
    dot: !!document.querySelector('#settings-btn .settings-update-dot'),
  }));
  expect(about.status, 'manual roundtrip echo (已最新)').toContain('最新');
  expect(about.dot, 'no green dot when up to date').toBe(false);
});

// ══ P · i18n zh/en parity ═════════════════════════════════
test('P locale parity: zh/en key sets identical', async ({ page }) => {
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  const { onlyZh, onlyEn, zhCount, enCount } = await page.evaluate(async () => {
    const zh = Object.keys((await import('/js/locales/zh-CN.js')).default);
    const en = Object.keys((await import('/js/locales/en.js')).default);
    return {
      onlyZh: zh.filter(k => !en.includes(k)),
      onlyEn: en.filter(k => !zh.includes(k)),
      zhCount: zh.length, enCount: en.length,
    };
  });
  expect(onlyZh, 'keys only in zh').toEqual([]);
  expect(onlyEn, 'keys only in en').toEqual([]);
  expect(zhCount, 'same key count').toBe(enCount);
});

// ══ 双主题截图（设置全貌 / 绿点特写 / 头像双态）══════════════
async function shots(colorScheme, tag) {
  test(`shots[${tag}] settings full + dot closeup + avatar dual state`, async ({ page }) => {
    // 1) 清理后设置页全貌（含头像区，无 MCP/NL 区）
    await bootPage(page, { locale: 'zh-CN', colorScheme, loggedIn: true, avatarUrl: AVATAR_URL });
    await openSettings(page);
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${SHOTS}/settings-clean-${tag}.png`, fullPage: false });

    // 2) 绿点特写（推送新版本后，设置未开）
    const h = await bootPage(page, { locale: 'zh-CN', colorScheme });
    h.pushUpdate(true, '9.9.9');
    await page.waitForSelector('#settings-btn .settings-update-dot', { timeout: 5000 });
    const box = await page.evaluate(() => {
      const r = document.getElementById('settings-btn').getBoundingClientRect();
      return { x: Math.max(0, r.x - 12), y: Math.max(0, r.y - 12), width: r.width + 24, height: r.height + 24 };
    });
    await page.screenshot({ path: `${SHOTS}/update-dot-${tag}.png`, clip: box });

    // 3) 头像双态特写（登录态已拍全貌；这里补未登录 + 登录态设置页顶部）
    await page.click('#settings-btn');
    await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
    const top = await page.evaluate(() => {
      const r = document.getElementById('settings-avatar-entry').getBoundingClientRect();
      return { x: Math.max(0, r.x - 24), y: Math.max(0, r.y - 40), width: r.width + 48, height: r.height + 64 };
    });
    await page.screenshot({ path: `${SHOTS}/avatar-in-${tag}.png`, clip: top });

    const h2 = await bootPage(page, { locale: 'zh-CN', colorScheme, loggedIn: false });
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
