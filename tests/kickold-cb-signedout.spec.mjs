// kickold-cb-signedout.spec.mjs — 「账户栏整件退役」后的模块边界验收
// （2026-11-18 作者令改写；原文 = 「被踢下线」被动提示案 B 客户端腿真渲染验收）。
//
// 🔴 原文的渲染面 = 生产 `js/neblink.js` 的 `neblinkSettingsHTML()`（设置账号卡 /
//    个人主页底部账号卡的单点）。2026-11-18 作者令（16:36「设置里的账户这一栏完全
//    移除」+ 16:45「底部的账户栏 功能重复 信息重复…不要有重复的功能和信息」）已把
//    该账户卡**整件退役** ⇒ 原文全部断言（状态行/提示行在位、文案、形态合规）作废。
// 🔴 判据随之反转 = 本 spec 现在的受检点：账户卡渲染 API 与其选择器族**零发射**
//    （不是隐藏；DOM 零命中）。
// 🔴 语义变化申报：被踢下线被动提示（`relay.signedOutElsewhere` 只读取数，2026-09-14
//    作者裁定 C+B）是账户卡的内件 ⇒ 随卡退役后**无 UI 载体**（见本批报告「未证/变化项」）；
//    登出链本体 `openEndSessionHandoff` **保留**（activityBar 登录失败面共用）。
//
// 渲染面 = 生产模块本身（真 CSS + 真 i18n + 真 `js/neblink.js`）。
// 🔴 状态载荷全为 FIXTURE（人工构造，非生产实测）——禁把 fixture 读数冒充生产实测。
//
// 运行（cwd = 主仓，node_modules 在那里）：
//   node node_modules/@playwright/test/cli.js test --config <pw.config> kickold

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

// 改动前/后对照开关：仅测试夹具用（缺省 = 本仓 src/main/resources/web）。
const WEB = process.env.NEBFLOW_WEB_ROOT
  || join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

// ── FIXTURE 载荷（人工构造；🔴 非生产实测）───────────────────────────────
/** 被踢态：relay 报「已在别处登录 + 自动重连已停摆」，其余字段照 statusJson 形状。 */
const STATUS_KICKED = {
  loggedIn: true,
  relay: {
    available: false,
    authRejected: false,
    lastRejectedStatusCode: null,
    lastRejectedAt: null,
    selfHeal: 'not-attempted',
    signedOutElsewhere: true,
    signedOutElsewhereAt: 1789377300000,
    autoReconnectParked: true,
  },
  device: {
    id: 'qa-device-0001', name: 'qa-mac', platform: 'macos', capabilities: {},
    userDescription: '', avatarUrl: '', githubLogin: '', email: 'qa@example.com',
    displayName: 'QA Probe',
  },
  peers: [],
};
/** 降级态（老网关 / 未被踢）：字段缺失。 */
const STATUS_CLEAN = {
  loggedIn: true,
  relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
  device: STATUS_KICKED.device,
  peers: [],
};

/** 最小宿主页：真 CSS + 真 i18n locale + 生产 neblink 模块。 */
const HOST_PAGE = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>kickold-cb retirement harness</title>
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
  // 真状态归一化仍走生产模块（fetchNeblinkStatus），但账户卡不再有渲染 API 可调。
  await page.evaluate(async () => {
    const nb = await import('/js/neblink.js');
    await nb.fetchNeblinkStatus();
  });
  await page.waitForTimeout(120);
}

/** 账户卡退役清单：渲染 API + 选择器族（DOM 零发射）。 */
const RETIRED_SELECTORS = [
  '#account-card', '#settings-devices-slot',
  '.neblink-login-section', '.neblink-logged-in', '.neblink-account-ro',
  '.neblink-logged-out-hint', '.neblink-kicked-notice', '.neblink-error',
  '.neblink-account-actions', '.neblink-switch-btn',
];

for (const theme of ['light', 'dark']) {
  test.describe(`账户栏整件退役 — ${theme}`, () => {
    test.use({ colorScheme: theme, viewport: { width: 1280, height: 800 } });

    test('neblink.js 不再导出账户卡渲染 API（登出链本体保留）', async ({ page }) => {
      const pageErrors = [];
      page.on('pageerror', (e) => pageErrors.push(e.message));
      await bootPanel(page, STATUS_KICKED);
      const api = await page.evaluate(async () => {
        const nb = await import('/js/neblink.js');
        return {
          settingsHTML: typeof nb.neblinkSettingsHTML,
          accountCard: typeof nb.accountCardHTML,
          bind: typeof nb.bindNeblinkEvents,
          endSession: typeof nb.openEndSessionHandoff,
        };
      });
      expect(api.settingsHTML, 'neblinkSettingsHTML must be retired (no longer exported)').toBe('undefined');
      expect(api.accountCard, 'accountCardHTML must be retired (no longer exported)').toBe('undefined');
      expect(api.bind, 'bindNeblinkEvents must be retired (no longer exported)').toBe('undefined');
      expect(api.endSession, 'openEndSessionHandoff must survive (activityBar shares it)').toBe('function');
      expect(pageErrors).toEqual([]);
    });

    test('账户卡选择器族零发射（含被踢提示行 / 只读身份行 / 未登录提示行）', async ({ page }) => {
      await bootPanel(page, STATUS_KICKED);
      for (const sel of RETIRED_SELECTORS) {
        await expect(page.locator(sel), `retired selector ${sel} must be zero-hit`).toHaveCount(0);
      }
    });

    test('降级态（老网关缺字段）同样零发射 —— 判据不依赖载荷', async ({ page }) => {
      await bootPanel(page, STATUS_CLEAN);
      for (const sel of RETIRED_SELECTORS) {
        await expect(page.locator(sel), `retired selector ${sel} must be zero-hit (clean payload)`).toHaveCount(0);
      }
    });
  });
}
