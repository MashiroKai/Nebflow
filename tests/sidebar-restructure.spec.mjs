// sidebar-restructure.spec.mjs — 批4-② 收尾节点验收 spec（隔离静态服务器 +
// 页内 mock，真实 UI 代码全量执行；8100+ 端口纪律，永不触碰宿主 8080）。
//
//   T1 侧边栏重排：上方恰 contacts+messages；下方固定序 files→projects→
//      agents→usage→settings；旧面板入口（legacy-btn/teams-btn/flows-btn/
//      legacy-pop/flows-indicator/flows-dropdown）零复活。
//   T2 toggle 收编：设置页与插件面板开关同源 .nb-toggle class + computed
//      style 一致（dark/light 双主题截图落 /tmp/nb-sidebar-restructure/）；
//      旧手写 .toggle 类运行时零存在。
//   T3 头像本地缓存：首载建立缓存 → 二次加载 route abort 远端头像请求仍
//      正常显示（零头像请求）→ 源（avatarUrl）更新才重新拉取。
//   T4 i18n parity：zh 与 en 键集合完全一致（node 侧 import 断言）。
//   T5 静态收编断言：toggleHTML 接入点在源码、手写 .toggle 全灭、旧入口
//      零残留。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8183; // fixed, 8100+ — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };
const SHOT_DIR = '/tmp/nb-sidebar-restructure';

/** 1×1 transparent PNG (avater fixture — real decodable image bytes). */
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

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

// Mutable fixtures (per-test reset).
const neblinkState = { loggedIn: true, avatarPath: '/mock-avatar-a.png' };
let avatarRequests = 0;   // requests for the avatar PNG (route-abort counter)
let avatarMode = 'fulfill'; // 'fulfill' | 'abort'

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

test.beforeEach(async () => {
  neblinkState.loggedIn = true;
  neblinkState.avatarPath = '/mock-avatar-a.png';
  avatarRequests = 0;
  avatarMode = 'fulfill';
});

/** Shared page boot: API/WS mocks + avatar route with request counting.
 *  Playwright route matching is LIFO — the catch-all registers FIRST. */
async function boot(page) {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // Avatar fixture route — counts every attempt; abort mode simulates the
  // unreachable remote (acceptance ③).
  await page.route('**/mock-avatar-*.png', async (r) => {
    avatarRequests++;
    if (avatarMode === 'abort') r.abort();
    else r.fulfill({ body: PNG_1PX, contentType: 'image/png' });
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/neblink/status', r => r.fulfill({
    json: {
      loggedIn: neblinkState.loggedIn,
      device: neblinkState.loggedIn
        ? { id: 'dev-1', name: 'test-dev', platform: 'darwin', avatarUrl: base + neblinkState.avatarPath, capabilities: {} }
        : null,
      peers: [],
    },
  }));
  await page.route('**/api/plugins', r => r.fulfill({
    json: {
      plugins: [{
        name: 'e2e-hello', version: '1.0.0', description: 'fixture', author: 'spec',
        digest: 'a'.repeat(72), fileCount: 1,
        trust: { status: 'trusted', approvedAt: 1757000000, digest: 'a'.repeat(72) },
        skills: [], mcpServers: [], toolsExtension: [], warnings: [],
      }],
      rejected: [],
    },
  }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      if (m.type === 'autostartStatus') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: false, supported: true }));
      if (m.type === 'autostartSet') ws.send(JSON.stringify({ type: 'autostartStatusResult', enabled: m.enabled, supported: true }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
}

const ACTIVITY_ORDER_IDS = ['activity-avatar', 'contacts-btn', 'messages-btn', 'activity-spacer', 'files-btn', 'projects-btn', 'agents-btn', 'usage-btn', 'settings-btn'];

test('T1 § sidebar order: top = avatar+contacts+messages; bottom fixed order; no legacy revival', async ({ page }) => {
  await boot(page);
  await page.goto(base);
  const ids = await page.$$eval('#activity-bar > *', els => els.map(e => e.id || e.className));
  // Structural equality: exactly the spec elements, in the spec order — any
  // revived legacy entry would break equality.
  expect(ids).toEqual(ACTIVITY_ORDER_IDS);
  // Top group: exactly two .activity-btn (contacts + messages) above spacer.
  const topBtns = await page.$$eval('#activity-bar > *', els => {
    const out = [];
    for (const e of els) {
      if (e.classList.contains('activity-spacer')) break;
      if (e.classList.contains('activity-btn')) out.push(e.id);
    }
    return out;
  });
  expect(topBtns).toEqual(['contacts-btn', 'messages-btn']);
  // Dead entries must not exist anywhere in the document (not merely hidden).
  for (const dead of ['legacy-btn', 'teams-btn', 'flows-btn', 'legacy-pop', 'flows-indicator', 'flows-dropdown']) {
    expect(await page.locator(`#${dead}`).count(), `${dead} must stay deleted`).toBe(0);
  }
  // agents-btn semantic check: the blank-boot fallback (main.js:
  // restoreTabs().then(restored => { if (!restored) openPlugins(); }), the
  // 09-04 ruling「插件页是智能体入口的继任默认页」) opens the PLUGINS page —
  // the plugins tab must be mounted with its content, proving agents-btn's
  // registered target. (Clicking the button here would be a 4-state-machine
  // TOGGLE-OFF — the tab is already active — so we assert the mounted state.)
  await page.waitForFunction(() => !!document.querySelector('#plugins-content'), undefined, { timeout: 8000 });
  await expect(page.locator('#agents-btn')).toHaveAttribute('aria-pressed', 'true');
});

test('T2 § toggle consolidation: same .nb-toggle class + identical computed style, both themes', async ({ page }) => {
  await boot(page);
  await page.goto(base);
  // Open settings → settings page toggle (runtime section).
  await page.click('#settings-btn');
  await expect(page.locator('#toggle-llm-log')).toBeVisible();
  const settingsToggleLoc = page.locator('#toggle-llm-log');
  expect(await settingsToggleLoc.getAttribute('role')).toBe('switch');
  expect(await settingsToggleLoc.evaluate(el => el.classList.contains('nb-toggle'))).toBe(true);
  expect(await settingsToggleLoc.evaluate(el => el.tagName)).toBe('BUTTON');
  // No hand-rolled .toggle remains anywhere in the live document.
  expect(await page.locator('.toggle').count()).toBe(0);
  // autostart toggle also consolidated (button.nb-toggle, aria in lockstep).
  expect(await page.locator('#toggle-autostart').evaluate(el => el.classList.contains('nb-toggle'))).toBe(true);
  expect(await page.locator('#toggle-autostart').getAttribute('aria-checked')).toBe('false');

  // Computed-style SPEC-VALUE assertions (the «nb-toggle» CSS section is the
  // single style source): 36×20 geometry, 10px pill radius, 0.15s transitions.
  const styleOf = (loc) => loc.evaluate(el => {
    const cs = getComputedStyle(el);
    return { width: cs.width, height: cs.height, borderRadius: cs.borderRadius, transition: cs.transitionDuration };
  });
  const sDark = await styleOf(settingsToggleLoc);
  expect(sDark.width).toBe('36px');
  expect(sDark.height).toBe('20px');
  expect(sDark.borderRadius).toBe('10px');
  await page.screenshot({ path: `${SHOT_DIR}/toggle-dark.png`, fullPage: false });

  // Light theme — identical geometry (theme tokens must not change it).
  await page.emulateMedia({ colorScheme: 'light' });
  await page.waitForTimeout(150);
  expect(await styleOf(settingsToggleLoc)).toEqual(sDark);
  await page.screenshot({ path: `${SHOT_DIR}/toggle-light.png`, fullPage: false });

  // Plugins panel cross-check lives in T2b (conditional — see below).
  // Keyboard: real <button> — focus + Space flips the settings toggle.
  await settingsToggleLoc.focus();
  const before = await settingsToggleLoc.getAttribute('aria-checked');
  await page.keyboard.press('Space');
  await expect(settingsToggleLoc).toHaveAttribute('aria-checked', before === 'true' ? 'false' : 'true');
});

test('T2b § cross-panel toggle equality (activates once the plugins-ux branch merges)', async ({ page }) => {
  await boot(page);
  await page.goto(base);
  // The plugin card switch ships with the plugins-ux branch (plugins.js :200
  // toggleHTML + attrs data-plugin-switch). Until that branch merges, this
  // worktree's plugins.js is still the baseline — skip, rerun post-merge.
  const pluginSwitchCount = await page.locator('[data-plugin-switch].nb-toggle').count();
  test.skip(pluginSwitchCount === 0, 'plugin-side nb-toggle lands with the plugins-ux branch merge — rerun then');
  const pluginToggle = page.locator('[data-plugin-switch]').first();
  expect(await pluginToggle.getAttribute('role')).toBe('switch');

  // Settings page toggle for comparison.
  await page.click('#settings-btn');
  await expect(page.locator('#toggle-llm-log')).toBeVisible();
  const settingsToggleLoc = page.locator('#toggle-llm-log');

  const styleOf = (loc) => loc.evaluate(el => {
    const cs = getComputedStyle(el);
    return { width: cs.width, height: cs.height, borderRadius: cs.borderRadius, transition: cs.transitionDuration };
  });
  const sDark = await styleOf(settingsToggleLoc);
  const pDark = await styleOf(pluginToggle);
  expect(pDark).toEqual(sDark); // exact computed-style equality — one shared class
});

test('T3 § avatar local-first cache: rebuild-free second load, refetch only on source change', async ({ page }) => {
  await boot(page);
  await page.goto(base);
  // Load 1: remote URL is fetched (img + cache build) — ≥1 avatar request.
  await page.waitForFunction(() => {
    const raw = localStorage.getItem('nebflow_avatar_cache');
    try { return !!raw && !!JSON.parse(raw).dataUrl; } catch { return false; }
  }, undefined, { timeout: 8000 });
  expect(avatarRequests).toBeGreaterThanOrEqual(1);
  const entry = await page.evaluate(() => JSON.parse(localStorage.getItem('nebflow_avatar_cache')));
  expect(entry.url).toBe(base + '/mock-avatar-a.png');
  expect(entry.dataUrl.startsWith('data:image/png')).toBe(true);
  expect(entry.loggedIn).toBe(true);
  // Poll-driven re-render converges the visible <img> onto the cached dataURL.
  await page.waitForFunction(() => {
    const img = document.querySelector('#activity-avatar .activity-avatar-photo');
    return img && !img.hidden && (img.getAttribute('src') || '').startsWith('data:image/');
  }, undefined, { timeout: 12000 });

  // Load 2: same source — remote avatar route ABORTED, avatar still shows.
  avatarMode = 'abort';
  const before = avatarRequests;
  await page.reload();
  await page.waitForFunction(() => {
    const img = document.querySelector('#activity-avatar .activity-avatar-photo');
    return img && !img.hidden && (img.getAttribute('src') || '').startsWith('data:image/');
  }, undefined, { timeout: 12000 });
  expect(avatarRequests).toBe(before); // zero remote avatar requests on the cached path

  // Source change: new avatarUrl — the remote MUST be fetched again (allowed).
  neblinkState.avatarPath = '/mock-avatar-b.png';
  avatarMode = 'fulfill';
  const beforeB = avatarRequests;
  await page.reload();
  await page.waitForFunction(() => {
    const raw = localStorage.getItem('nebflow_avatar_cache');
    try { return !!raw && JSON.parse(raw).url.endsWith('mock-avatar-b.png') && !!JSON.parse(raw).dataUrl; } catch { return false; }
  }, undefined, { timeout: 10000 });
  expect(avatarRequests).toBeGreaterThan(beforeB); // source marker change → refetch
});

test('T4 § i18n parity: zh-CN and en key sets are identical', async () => {
  const zh = (await import(resolve(HERE, '..', 'src', 'main', 'resources', 'web', 'js', 'locales', 'zh-CN.js'))).default;
  const en = (await import(resolve(HERE, '..', 'src', 'main', 'resources', 'web', 'js', 'locales', 'en.js'))).default;
  const zk = Object.keys(zh).sort(); const ek = Object.keys(en).sort();
  expect(zk).toEqual(ek);
  for (const k of zk) expect(String(zh[k]).length, `zh key ${k} must be non-empty`).toBeGreaterThan(0);
});

test('T5 § static: toggleHTML adopted, hand-rolled .toggle extinct, legacy entries absent from HTML', async () => {
  const { readFile: rf } = await import('node:fs/promises');
  const sidebarSrc = await rf(resolve(WEB, 'js/sidebar.js'), 'utf-8');
  const orbSrc = await rf(resolve(WEB, 'js/orbSettingsUI.js'), 'utf-8');
  const cssSrc = await rf(resolve(WEB, 'css/sidebar.css'), 'utf-8');
  const htmlSrc = await rf(resolve(WEB, 'index.html'), 'utf-8');
  expect(sidebarSrc).toContain("from './toggle.js'");
  expect(orbSrc).toContain("from './toggle.js'");
  expect(sidebarSrc).not.toMatch(/class="toggle[ "]/); // no hand-written switch markup
  expect(orbSrc).not.toMatch(/class="toggle[ "]/);
  expect(cssSrc).not.toMatch(/^\.toggle \{/m); // legacy definition retired
  expect(cssSrc).toMatch(/\.nb-toggle \{/);    // shared component styles present
  for (const dead of ['legacy-btn', 'legacy-pop', 'teams-btn', 'flows-btn']) {
    // Match element markup only (`id="…"`), not the retirement tombstone
    // comments that intentionally mention the deleted ids.
    expect(htmlSrc.includes(`id="${dead}"`), `${dead} element must stay out of index.html`).toBe(false);
  }
});
