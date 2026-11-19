// neblink-paircode-card.spec.mjs — 「设置页账户栏 / 账号卡整件退役」后的模块边界验收
// （2026-11-18 作者令改写；原文 = 配对码卡移除后的**未登录设置面**真渲染验收）。
//
// 🔴 原文的渲染面 = 生产 `js/neblink.js` 的 `neblinkSettingsHTML()` /
//    `bindNeblinkEvents()`（设置账号卡 + 个人主页底部账号卡的单点）。2026-11-18 作者令
//    （16:36「设置里的账户这一栏完全移除」+ 16:45「底部的账户栏 功能重复 信息重复…
//    不要有重复的功能和信息」）已把该账户卡**整件退役** ⇒ 原文全部断言作废。
// 🔴 判据反转 = 本 spec 现在的受检点：
//   · 账户卡渲染/绑定 API 不再导出（不是隐藏）；
//   · 未登录态卡内独有面（登录失败/降级/凭据问题行、清理并重登键）随之零发射；
//   · 账号卡选择器族 DOM 零命中（见 kickold-cb-signedout.spec.mjs 同口径）。
//   登出链本体 `openEndSessionHandoff` **保留**（activityBar 登录失败面共用）。
//
// 渲染面 = 生产模块本身（真 CSS + 真 i18n + 真 `js/neblink.js`）。
// 🔴 状态载荷全为 FIXTURE（人工构造，非生产实测）——禁把 fixture 读数冒充生产实测。
//
// 运行（cwd = 主仓，node_modules 在那里）：
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

const HOST_PAGE = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>account-card retirement harness</title>
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
  await page.routeWebSocket(/\/ws/, () => { /* 外壳未 boot，WS 保持静默 */ });
  await page.goto('http://localhost:1/harness');
  await page.evaluate(async () => {
    const nb = await import('/js/neblink.js');
    await nb.fetchNeblinkStatus();
  });
  await page.waitForTimeout(120);
}

test.describe('账户栏 / 账号卡整件退役（2026-11-18 作者令）', () => {
  test('账户卡渲染与绑定 API 不再导出；登出链本体保留', async ({ page }) => {
    await bootPanel(page, STATUS_LOGGED_OUT);
    const api = await page.evaluate(async () => {
      const nb = await import('/js/neblink.js');
      return {
        settingsHTML: typeof nb.neblinkSettingsHTML,
        accountCard: typeof nb.accountCardHTML,
        bind: typeof nb.bindNeblinkEvents,
        endSession: typeof nb.openEndSessionHandoff,
        failureText: typeof nb.loginFailureText,
      };
    });
    expect(api.settingsHTML).toBe('undefined');
    expect(api.accountCard).toBe('undefined');
    expect(api.bind).toBe('undefined');
    // 未登录失败面（缺陷 A 的三段式文案 + 清理并重登）现在只由 activityBar 承载；
    // 其共享实现留在 neblink.js。
    expect(api.endSession).toBe('function');
    expect(api.failureText).toBe('function');
  });

  test('账户卡选择器族零发射（未登录态：配对码卡 / 未登录提示 / 清理并重登键 / 错误行）', async ({ page }) => {
    await bootPanel(page, STATUS_LOGGED_OUT);
    const sels = [
      '#account-card', '#neblink-cleanup-btn', '#neblink-paircode-input', '#neblink-paircode-btn',
      '.neblink-paircode-card', '.neblink-login-section', '.neblink-logged-out-hint',
      '.neblink-account-actions', '.neblink-switch-btn', '.neblink-logged-in', '.neblink-account-ro',
    ];
    for (const sel of sels) {
      await expect(page.locator(sel), `retired selector ${sel} must be zero-hit`).toHaveCount(0);
    }
    // 原文对「配对码模块内部 API」的断言保留其反转形态：submitPairCode 仍不得导出。
    const exported = await page.evaluate(async () => typeof (await import('/js/neblink.js')).submitPairCode);
    expect(exported).toBe('undefined');
  });

  test('降级态与凭据问题载荷同样零发射（卡已不存在，载荷不产生任何行）', async ({ page }) => {
    await bootPanel(page, {
      ...STATUS_LOGGED_OUT,
      statusDegraded: { status: 503 },
      credentialIssue: { code: 'credential-file-corrupt' },
    });
    await expect(page.locator('.neblink-error, .neblink-logged-out-hint, .neblink-login-section')).toHaveCount(0);
  });
});
