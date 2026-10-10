// neblink-paircode-card.spec.mjs — 门户 MVP E7：设置面板「配对码入网」卡真渲染验收。
//
// 契约（方案 §3.2 E7 + 本批任务书）：
//   · 未登录态区新增配对码卡：6 位码输入 + 提交钮 → POST /api/neblink/enroll
//     `{pairCode}`（server 缺省 = 后端回落链，卡上不带 server 输入）；
//   · 非 6 位输入 ⇒ 不发请求、卡内可见错误（输入不清空）；
//   · 成功（200 {ok:true,...}）⇒ 刷新 status ⇒ 面板切到已登录视图；
//   · 失败（4xx {error}）⇒ 卡内可见错误原文，无横幅无 toast。
//
// 渲染面 = 生产模块本身：生产 CSS（base.css + neblink.css）+ 生产 js/neblink.js 的
// neblinkSettingsHTML() / bindNeblinkEvents() / fetchNeblinkStatus()。状态载荷全部
// FIXTURE（page.route 注入，🔴 非生产实测）。
//
// 运行（cwd = 主仓）：
//   node node_modules/@playwright/test/cli.js test --config <pw.config> neblink-paircode-card
// 改动前对照（验红）：同一命令必红（改动前无该卡片）。

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
<title>paircode-card render harness</title>
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

test.describe('设置面板配对码卡（门户 MVP E7）', () => {
  test('卡片渲染：输入 + 提交钮 + 提示（未登录态）', async ({ page }) => {
    await bootPanel(page, STATUS_LOGGED_OUT);
    await expect(page.locator('#neblink-paircode-input')).toHaveCount(1);
    await expect(page.locator('#neblink-paircode-btn')).toHaveCount(1);
    const input = page.locator('#neblink-paircode-input');
    // 数字键盘语义 + 6 位上限
    expect(await input.getAttribute('inputmode')).toBe('numeric');
    expect(await input.getAttribute('maxlength')).toBe('6');
    // 错误行存在且初始不可见
    await expect(page.locator('#neblink-paircode-error')).toHaveCount(1);
  });

  test('非 6 位输入：不发请求，卡内可见错误，输入保留', async ({ page }) => {
    let enrollCalls = 0;
    await page.route('**/api/neblink/enroll', (route) => { enrollCalls += 1; return route.fulfill({ json: { ok: true } }); });
    await bootPanel(page, STATUS_LOGGED_OUT);
    await page.fill('#neblink-paircode-input', '12ab');
    await page.click('#neblink-paircode-btn');
    await page.waitForTimeout(150);
    expect(enrollCalls).toBe(0);
    const err = page.locator('#neblink-paircode-error');
    await expect(err).toBeVisible();
    expect((await err.textContent()) || '').not.toContain('undefined');
    expect(await page.inputValue('#neblink-paircode-input')).toBe('12ab');
  });

  test('成功路径：POST {pairCode} → 200 ⇒ 刷新 status 切已登录视图', async ({ page }) => {
    // 🔴 路由注册次序：Playwright 按 LIFO 匹配 —— 专属 mock 必须在 bootPanel 的
    // `**/*` 兜底**之后**注册才会胜出（先注册会被兜底吞掉，假 200）。
    await bootPanel(page, STATUS_LOGGED_OUT);
    let statusCalls = 0;
    const enrollBodies = [];
    await page.route('**/api/neblink/status', (route) => {
      statusCalls += 1;
      return route.fulfill({ json: STATUS_LOGGED_IN });
    });
    await page.route('**/api/neblink/enroll', (route) => {
      enrollBodies.push(route.request().postDataJSON());
      return route.fulfill({ json: { ok: true, message: 'Enrolled. Connected.', networkId: 'n1' } });
    });
    await page.fill('#neblink-paircode-input', '654321');
    await page.click('#neblink-paircode-btn');
    // enroll 载荷 = 仅 pairCode（server 缺省走后端回落链）
    await expect.poll(() => enrollBodies.length).toBe(1);
    expect(enrollBodies[0]).toEqual({ pairCode: '654321' });
    // 成功 ⇒ refreshNeblink()（status 重取 + 重渲到已登录视图）
    await expect(page.locator('.neblink-logged-in')).toHaveCount(1);
    await expect(page.locator('#neblink-paircode-input')).toHaveCount(0);
    expect(statusCalls).toBeGreaterThanOrEqual(1);
  });

  test('失败路径：4xx {error} ⇒ 卡内可见错误原文，无横幅', async ({ page }) => {
    await bootPanel(page, STATUS_LOGGED_OUT);
    await page.route('**/api/neblink/enroll', (route) =>
      route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ error: 'Enrollment failed: invalid pair code' }) }));
    await page.fill('#neblink-paircode-input', '000000');
    await page.click('#neblink-paircode-btn');
    const err = page.locator('#neblink-paircode-error');
    await expect(err).toBeVisible();
    expect((await err.textContent()) || '').toContain('Enrollment failed');
    // 未切换视图（仍在未登录卡片）
    await expect(page.locator('#neblink-paircode-input')).toHaveCount(1);
  });
});
