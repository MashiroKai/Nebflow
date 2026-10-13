// neblink-paircode-card.spec.mjs — the settings logged-out face AFTER the
// pairing-code card removal (author ruling 2026-10-13, "no local pairing
// code").
//
// Contract now:
//   · The logged-out settings face renders NO pairing card (no input, no
//     submit button, no card-local error row) — the ONLY enrollment path is
//     the avatar account login (PKCE → /api/device/register; the server's
//     get_or_create_default_network makes account = network, so signing in
//     joins the network with zero extra steps);
//   · the module no longer exports submitPairCode;
//   · the logged-out hint names the avatar sign-in as the entry;
//   · the degraded-status and credential-issue faces still render on the
//     logged-out card; the logged-in face is unchanged (switch + logout).
//
// Render surface = the production modules themselves: production CSS
// (base.css + neblink.css) + production js/neblink.js neblinkSettingsHTML() /
// bindNeblinkEvents() / fetchNeblinkStatus(). All status payloads are
// FIXTURES (page.route injection, never a live backend).
//
// Run (cwd = main repo):
//   node node_modules/@playwright/test/cli.js test --config <pw.config> neblink-paircode-card

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = process.env.NEBFLOW_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const STATUS_LOGGED_OUT = { loggedIn: false, device: null, peers: [] };
const STATUS_LOGGED_IN = {
  loggedIn: true,
  device: { id: 'qa-device-0001', name: 'qa-mac', platform: 'macos', capabilities: {}, userDescription: '', avatarUrl: '', email: 'qa@example.com', displayName: 'QA Probe' },
  peers: [],
};

const HOST_PAGE = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>logged-out face render harness</title>
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/neblink.css">
<style>body{display:block;padding:24px}#host{width:320px;background:var(--color-surface);padding:12px;border-radius:10px}</style>
</head><body><div id="host"></div></body></html>`;

async function bootPanel(page, status) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/harness') return route.fulfill({ status: 200, contentType: 'text/html', body: HOST_PAGE });
    if (p === '/api/neblink/status') return route.fulfill({ json: status });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, () => {});
  await page.goto('http://localhost:1/harness');
  await page.evaluate(async () => {
    const nb = await import('/js/neblink.js');
    await nb.fetchNeblinkStatus();
    const host = document.getElementById('host');
    host.innerHTML = nb.neblinkSettingsHTML();
    nb.bindNeblinkEvents(() => { host.innerHTML = nb.neblinkSettingsHTML(); });
  });
  await page.waitForTimeout(120);
}

test.describe('设置面板未登录态（配对码卡移除后）', () => {
  test('无配对码卡：无输入 / 无提交钮 / 无卡内错误行', async ({ page }) => {
    await bootPanel(page, STATUS_LOGGED_OUT);
    await expect(page.locator('#neblink-paircode-input')).toHaveCount(0);
    await expect(page.locator('#neblink-paircode-btn')).toHaveCount(0);
    await expect(page.locator('#neblink-paircode-error')).toHaveCount(0);
    await expect(page.locator('.neblink-paircode-card')).toHaveCount(0);
  });

  test('submitPairCode 不再导出；提示指向头像登录', async ({ page }) => {
    await bootPanel(page, STATUS_LOGGED_OUT);
    const exported = await page.evaluate(async () => {
      const nb = await import('/js/neblink.js');
      return typeof nb.submitPairCode;
    });
    expect(exported).toBe('undefined');
    const hint = await page.locator('.neblink-logged-out-hint').textContent();
    // The hint names the avatar sign-in as the single path (zh locale fixture).
    expect(hint || '').toContain('头像');
    expect(hint || '').toContain('登录');
  });

  test('降级态与凭据问题面仍渲染（未登录卡）', async ({ page }) => {
    await bootPanel(page, {
      ...STATUS_LOGGED_OUT,
      statusDegraded: { status: 503 },
      credentialIssue: { code: 'credential-file-corrupt' },
    });
    await expect(page.locator('.neblink-error').first()).toBeVisible();
  });

  test('已登录视图不变：切换账号 + 退出登录两键', async ({ page }) => {
    await bootPanel(page, STATUS_LOGGED_IN);
    await expect(page.locator('.neblink-logged-in')).toHaveCount(1);
    await expect(page.locator('#neblink-switch-btn')).toHaveCount(1);
    await expect(page.locator('#neblink-logout-btn')).toHaveCount(1);
  });
});
