// plugins-install-link.spec.mjs — C 阶段（目录 schema v1 / stand:// 链接 / 三入口）
// 面板面验收：剪贴板检测（一次询问/绝不自动装/非 stand:// 零动作）、悬浮加号 +
// 粘贴对话框（resolve 预览 → 确认安装 → POST）、详情区审核时间线预留位（§12.7）。
//
// Self-contained: static server on 127.0.0.1:8180 (8100+ per task discipline, never
// the host 8080); WS/API mocked in-page; real UI code (plugins.js) executes fully.
// 规约依据：设计稿 §12.6 —— 剪贴板只识别 stand:// 专有格式；面板前台检查一次，
// 不持续监听；只弹询问，绝不自动安装；同一 sha256 只询问一次；剪贴板内容不落日志、
// 不上传（wire 断言：非 stand:// 内容零外发）。链接只是发现入口：预览 = GET
// /api/plugins/install-link/resolve?link=…；安装 = 用户确认后 POST
// /api/plugins/install-link {link}（authorize = 显式确认动作本身）。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8180; // fixed, 8100+ — never the host 8080/8091/8092
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

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

const MOCK_AGENTS = {
  agents: [
    { name: 'Nebula', displayName: 'Nebula', description: 'orchestrator', category: 'standalone', layer: 'global' },
  ],
};
const MOCK_MODEL = { preferred: 'gpt/test-model', current: 'gpt/test-model', preset: 'preset-one' };
const MOCK_PLUGIN = {
  name: 'e2e-hello',
  version: '1.0.0',
  description: 'E2E fixture plugin for the install-link spec',
  author: 'spec',
  digest: 'deadbeefcafe'.repeat(6),
  fileCount: 3,
  trusted: true,
  blocked: false,
  contentChanged: false,
  dispatch: { authorEnabled: true, transitionActive: false },
  trust: { status: 'untrusted', reason: 'legacy gate record' },
  skills: [{ id: 'e2e-hello/hello', description: 'Say hello', preview: '' }],
  mcpServers: [],
  toolsExtension: [],
  warnings: [],
};

const GOOD_SHA = 'a'.repeat(64);
const GOOD_LINK = `stand://plugin/install?id=acme.demo-kit&v=1.5.0&s=${GOOD_SHA}`;

test.use({ permissions: ['clipboard-read', 'clipboard-write'] });

test.beforeAll(async () => { server = await startServer(); });
test.afterAll(() => server.close());

// wire 断言计数：install-link 端点只在用户确认后出现 POST；resolve 只在预览时出现。
let resolveCalls;
let installPosts;

test.beforeEach(async ({ page }) => {
  resolveCalls = [];
  installPosts = [];
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/plugins/install-link/resolve**', r => {
    resolveCalls.push(new URL(r.request().url()).searchParams.get('link'));
    r.fulfill({ json: {
      ok: true,
      id: 'acme.demo-kit',
      version: '1.5.0',
      sha256: GOOD_SHA,
      size: 123,
      publisherClass: 'verified',
      publisherKeyId: 'acme-release-1',
      catalogSource: 'acme',
      yanked: false,
    } });
  });
  await page.route('**/api/plugins/install-link', r => {
    if (r.request().method() === 'POST') {
      installPosts.push(r.request().postDataJSON());
      r.fulfill({ json: { ok: true, message: "Plugin 'demo-kit' (v1.5.0) installed from catalog acme." } });
    } else r.fulfill({ json: {} });
  });
  await page.route('**/api/plugins', r => r.fulfill({ json: { plugins: [MOCK_PLUGIN], rejected: [] } }));
  await page.route('**/api/plugins/updates', r => r.fulfill({ json: { sources: [], updatable: 0 } }));
  await page.route('**/api/agents/*/model', r => r.fulfill({ json: MOCK_MODEL }));
  await page.route('**/api/agents', r => r.fulfill({ json: MOCK_AGENTS }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
});

/** Load the real shell; main.js restoreTabs fallback lands on the plugins pane. */
async function loadShell(page) {
  await page.goto(base + '/index.html');
  await page.waitForSelector('.plugins-card[data-plugin="e2e-hello"]', { timeout: 15000 });
}

async function setClipboard(page, text) {
  await page.evaluate(async (t) => { await navigator.clipboard.writeText(t); }, text);
}

async function fireFocus(page) {
  await page.evaluate(() => { window.dispatchEvent(new Event('focus')); });
}

// ── ① 剪贴板检测：只弹询问、绝不自动装、同 sha 只问一次 ──────────────

test('C1: clipboard stand:// link asks once on focus; never auto-installs; same sha not re-asked', async ({ page }) => {
  await loadShell(page);
  await setClipboard(page, GOOD_LINK);
  await fireFocus(page);

  const modal = page.locator('[data-install-link-modal]');
  await expect(modal).toBeVisible({ timeout: 5000 });
  await expect(page.locator('[data-install-link-id]')).toHaveText('acme.demo-kit');
  await expect(page.locator('[data-install-link-version]')).toHaveText('1.5.0');

  // 绝不自动安装：弹窗出现后（未点击）零 POST
  await page.waitForTimeout(300);
  expect(installPosts, 'no POST install-link without explicit confirm').toHaveLength(0);

  // 关掉；同 sha 第二次 focus 不再问
  await page.locator('[data-install-link-dismiss]').click();
  await expect(modal).toBeHidden();
  await fireFocus(page);
  await page.waitForTimeout(300);
  await expect(modal).toBeHidden();
  expect(installPosts).toHaveLength(0);
});

test('C1b: clipboard confirm installs via POST {link}', async ({ page }) => {
  await loadShell(page);
  await setClipboard(page, GOOD_LINK);
  await fireFocus(page);
  const modal = page.locator('[data-install-link-modal]');
  await expect(modal).toBeVisible({ timeout: 5000 });
  await page.locator('[data-install-link-install]').click();
  await expect.poll(() => installPosts.length).toBe(1);
  expect(installPosts[0]).toEqual({ link: GOOD_LINK });
  await expect(modal).toBeHidden();
});

test('C2: non-stand clipboard content produces no modal and zero install-link traffic', async ({ page }) => {
  await loadShell(page);
  await setClipboard(page, 'https://phisher.example/install?fake=1 — just some copied text');
  await fireFocus(page);
  await page.waitForTimeout(300);
  await expect(page.locator('[data-install-link-modal]')).toBeHidden();
  expect(resolveCalls, 'non-stand:// clipboard must not be sent anywhere').toHaveLength(0);
  expect(installPosts).toHaveLength(0);
});

// ── ② 悬浮加号：粘贴对话框 → resolve 预览 → 确认安装 ──────────────────

test('C3: floating add button opens paste dialog; resolve previews; install POSTs the link', async ({ page }) => {
  await loadShell(page);

  const btn = page.locator('[data-plugins-add]');
  await expect(btn).toBeVisible();
  await btn.click();

  const dialog = page.locator('[data-plugins-add-dialog]');
  await expect(dialog).toBeVisible();
  await page.locator('[data-plugins-add-input]').fill(GOOD_LINK);
  await page.locator('[data-plugins-add-resolve]').click();

  // 预览 = GET resolve（encodeURIComponent 链接）；字段来自已验目录条目
  await expect(page.locator('[data-plugins-add-preview]')).toBeVisible({ timeout: 5000 });
  await expect(page.locator('[data-plugins-add-preview] [data-install-link-id]')).toHaveText('acme.demo-kit');
  await expect(page.locator('[data-plugins-add-preview] [data-install-link-version]')).toHaveText('1.5.0');
  expect(resolveCalls).toHaveLength(1);
  expect(decodeURIComponent(resolveCalls[0])).toBe(GOOD_LINK);
  expect(installPosts).toHaveLength(0); // 预览 ≠ 安装

  await page.locator('[data-plugins-add-install]').click();
  await expect.poll(() => installPosts.length).toBe(1);
  expect(installPosts[0]).toEqual({ link: GOOD_LINK });
  await expect(dialog).toBeHidden();
});

test('C3b: resolve rejection shows the reason and blocks install (no POST)', async ({ page }) => {
  const BAD_LINK = `stand://plugin/install?id=acme.demo-kit&v=9.9.9&s=${'c'.repeat(64)}`;
  await page.unroute('**/api/plugins/install-link/resolve**');
  await page.route('**/api/plugins/install-link/resolve**', r => {
    resolveCalls.push(new URL(r.request().url()).searchParams.get('link'));
    r.fulfill({ json: { ok: false, error: 'version 9.9.9 is not in the verified catalog (INSTALL_LINK_NOT_IN_CATALOG)' } });
  });
  await loadShell(page);

  await page.locator('[data-plugins-add]').click();
  const dialog = page.locator('[data-plugins-add-dialog]');
  await expect(dialog).toBeVisible();
  await page.locator('[data-plugins-add-input]').fill(BAD_LINK);
  await page.locator('[data-plugins-add-resolve]').click();

  await expect(page.locator('[data-plugins-add-error]')).toBeVisible({ timeout: 5000 });
  await expect(page.locator('[data-plugins-add-error]')).toContainText('INSTALL_LINK_NOT_IN_CATALOG');
  // 拒绝态：安装钮不可用 ⇒ 零 POST
  await expect(page.locator('[data-plugins-add-install]')).toBeDisabled();
  expect(installPosts).toHaveLength(0);
});

// ── ③ 审核时间线预留位（§12.7 C 行）──────────────────────────────────

test('C4: plugin detail area carries the review-timeline placeholder', async ({ page }) => {
  await loadShell(page);
  // 展开条目详情（accordion）
  await page.locator('.plugins-card[data-plugin="e2e-hello"] [data-plugin-item]').click();
  const detail = page.locator('.plugins-card[data-plugin="e2e-hello"] .plugins-item-detail');
  await expect(detail).toBeVisible();
  await expect(detail.locator('[data-review-timeline]')).toHaveCount(1);
});
