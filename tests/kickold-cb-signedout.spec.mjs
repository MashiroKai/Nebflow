// kickold-cb-signedout.spec.mjs — 「被踢下线」被动提示（案 B 客户端腿）真渲染验收。
//
// 批：登录「踢旧下线」实施批（作者 2026-09-14 17:07 裁定 C+B · 客户端一刀）。
// 契约（前端只读不派生；真源 = 网关本地状态面 /api/neblink/status 的
// `relay.signedOutElsewhere` / `relay.autoReconnectParked`，见 NeblinkRelayTunnel.statusJson）：
//   形态 = **状态行级**（与设备行同族的一行）——🔴 无横幅、无浮层、无 toast、无声音
//        （一期口径 /Users/kaiyu/.nebflow/User.md:36；代码锚 messages.js「无横幅无提示音」）；
//   文案 = 「已在别处登录 · 本机会话已被另一台设备接管，自动重连已暂停——重新登录即可恢复」；
//   降级 = 字段缺失（老网关）⇒ **不渲染**（宁可不显示，不误报）；
//   布局 = 状态行不出面板容器、文案不被裁（三端视口 × 亮/暗双主题）。
//
// 🔴 **本文件全部状态载荷均为 FIXTURE（人工构造，非生产实测）**：`/api/neblink/status`
//    的响应由本文件内联定义并经 `page.route` 注入，不来自任何真实运行实例；真实
//    「被踢态」实例在真浏览器里的读数未在本批完成（见节点报告「未证项」）。
//    🔴 禁把本 fixture 读数冒充生产实测。
//    与之互补的真实链路读数（非法装置面）：真 JVM 实例 + 真 WS socket 推 `disconnect`
//    帧 ⇒ 停摆 + 零自动重登，见 src/test/.../NeblinkRelayTunnelKickParkSpec.scala。
//
// 渲染面 = **生产模块本身**：本页只挂载生产 CSS（base.css + neblink.css，亮/暗由
//    prefers-color-scheme 供给）+ 生产 `js/neblink.js` 的 `neblinkSettingsHTML()`。
//    不 boot 整个 app 外壳（零 ws/会话 fixture ⇒ 读数稳定、与外壳状态无关）。
//
// 运行（cwd = 主仓，node_modules 在那里）：
//   node node_modules/@playwright/test/cli.js test --config /tmp/qa-kickold/pw.config.mjs signedout
// 改动前对照（验红）：NEBFLOW_WEB_ROOT=/Users/kaiyu/Claude\ code/Nebflow/src/main/resources/web
//   ⇒ 同一命令必红（改动前无该状态行）。

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
/** 降级态（老网关 / 未被踢）：字段缺失 ⇒ 不得渲染状态行。 */
const STATUS_CLEAN = {
  loggedIn: true,
  relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
  device: STATUS_KICKED.device,
  peers: [],
};

/** 最小宿主页：真 CSS + 真 i18n locale + 生产 neblink 模块。 */
const HOST_PAGE = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>kickold-cb render harness</title>
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
  // 用**生产模块**渲染面板（真 i18n + 真状态归一化 + 真 HTML 模板）。
  await page.evaluate(async () => {
    const nb = await import('/js/neblink.js');
    await nb.fetchNeblinkStatus();
    document.getElementById('host').innerHTML = nb.neblinkSettingsHTML();
  });
  await page.waitForTimeout(120);
}

const THEMES = ['light', 'dark'];
const VIEWPORTS = [
  { label: 'narrow', width: 900, height: 700 },
  { label: 'mid', width: 1280, height: 800 },
  { label: 'wide', width: 1680, height: 1000 },
];

for (const theme of THEMES) {
  for (const vp of VIEWPORTS) {
    test.describe(`被踢下线被动提示 — ${theme} / ${vp.label}(${vp.width}x${vp.height})`, () => {
      test.use({ colorScheme: theme, viewport: { width: vp.width, height: vp.height } });

      test('状态行渲染 / 形态合规（无横幅无声音）/ 不溢出裁切 / 降级不渲染（fixture 载荷）', async ({ page }) => {
        const pageErrors = [];
        page.on('pageerror', (e) => pageErrors.push(e.message));
        await bootPanel(page, STATUS_KICKED);

        const notice = page.locator('.neblink-kicked-notice');
        await expect(notice).toHaveCount(1);

        const r = await page.evaluate(() => {
          const n = document.querySelector('.neblink-kicked-notice');
          const host = document.getElementById('host');
          const cs = getComputedStyle(n);
          const nb = n.getBoundingClientRect();
          const hb = host.getBoundingClientRect();
          // 横幅/浮层/声音面探测：固定定位元素、aria-live、<audio>、toast 类名。
          const fixed = [...document.querySelectorAll('*')].filter((el) => {
            const s = getComputedStyle(el);
            return s.position === 'fixed' || s.position === 'sticky';
          }).map((el) => el.className || el.tagName);
          const live = [...document.querySelectorAll('[aria-live]')].map((el) => el.className || el.tagName);
          return {
            title: n.querySelector('strong')?.textContent || '',
            text: n.querySelector('.neblink-kicked-text')?.textContent || '',
            dotBg: getComputedStyle(n.querySelector('.neblink-kicked-dot')).backgroundColor,
            color: cs.color,
            bg: cs.backgroundColor,
            borderLeft: cs.borderLeftColor,
            noticeOverflow: n.scrollWidth - n.clientWidth,
            insideHost: nb.left >= hb.left - 1 && nb.right <= hb.right + 1,
            fixedCount: fixed.length,
            fixed,
            liveCount: live.length,
            audioCount: document.querySelectorAll('audio').length,
            toastCount: document.querySelectorAll('[class*="toast" i], [class*="banner" i], .neblink-error').length,
          };
        });

        // ① 文案（真 i18n，zh-CN）
        expect(r.title).toBe('已在别处登录');
        expect(r.text).toContain('已在别处登录');
        expect(r.text).toContain('自动重连已暂停');
        expect(r.text).toContain('重新登录即可恢复');
        // ② 形态合规：零横幅 / 零 aria-live / 零声音 / 零 toast
        expect(r.fixedCount, `fixed/sticky elements: ${JSON.stringify(r.fixed)}`).toBe(0);
        expect(r.liveCount).toBe(0);
        expect(r.audioCount).toBe(0);
        expect(r.toastCount).toBe(0);
        // ③ 可读（两主题都有实色底/字色，不是透明继承）
        expect(r.bg).not.toBe('rgba(0, 0, 0, 0)');
        expect(r.color).not.toBe('rgba(0, 0, 0, 0)');
        expect(r.dotBg).not.toBe('rgba(0, 0, 0, 0)');
        // ④ 布局：不出容器、不裁切
        expect(r.noticeOverflow, 'notice text truncated').toBeLessThanOrEqual(1);
        expect(r.insideHost, 'notice escaped its panel container').toBe(true);
        // ⑤ 零脚本错误
        expect(pageErrors).toEqual([]);

        await page.screenshot({
          path: `/tmp/qa-kickold/render-${theme}-${vp.label}.png`,
          clip: { x: 0, y: 0, width: vp.width, height: Math.min(vp.height, 400) },
        });
        console.log(`[reading ${theme}/${vp.label}] ${JSON.stringify(r)}`);
      });

      test('降级态：relay 无 signedOutElsewhere 字段 ⇒ 不渲染状态行（老网关兼容）', async ({ page }) => {
        await bootPanel(page, STATUS_CLEAN);
        await expect(page.locator('.neblink-kicked-notice')).toHaveCount(0);
        // 面板其余部分仍渲染（不是整块塌掉）
        await expect(page.locator('.neblink-logged-in')).toHaveCount(1);
      });
    });
  }
}
