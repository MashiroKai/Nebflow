// console-no-new-tab.spec.mjs — 「工作着却冒出个新标签页」批（2026-11-05）的**零新标签**钉。
//
// ── 作者报障（2026-11-05 10:06 逐字）────────────────────────────────────────
// 「我已经在控制台工作了。但是莫名其妙会打开一个标签页，然后再次进入控制台。」
//
// ── 本测钉的契约 ───────────────────────────────────────────────────────────
// 控制台**活跃**（页面在跑、WS 反复失败触发重连腿）时，控制台的任何自动腿
//   · WS 断线重连（ws.js onclose → 退避重连）
//   · 静默重座（ws.js remintHostSessionAndReload：portal status → console-ticket
//     → consoleUrl 整页重进）
//   · 近路跳腿（ws.js 环回/LAN：mint → 整页跳 http://host:port/?ticket=…）
// 只许**原地重进**，一条都不许开出新标签/新窗口。断言面 = Playwright 的
// `context.on('page')` 计数（不是目测截图）：事件前后 page 数恒为 1，popup 恒 0。
//
// 两层（缺一不可）：
//   T1 行为层 —— 真页面 + 真模块（静态服务 src/main/resources/web，零引擎、零 6138），
//      /api/** 全打桩；让重连腿自己跑 N 秒，page 计数不许变。
//   T2 逻辑层 —— 直接 import 三条腿共用的 `reseatTarget.js`（叶子模块，零 DOM 副作用），
//      断言它**只产出可赋给 location.href 的字符串**（没有任何开窗入口），且卡窗契约守恒。
//
// 零实例、零固定端口（127.0.0.1 随机口）、afterAll 全清。
//
// Run: node node_modules/@playwright/test/cli.js test tests/console-no-new-tab.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const WEB_ROOT = path.join(REPO_ROOT, 'src', 'main', 'resources', 'web');

/** 观察窗：足够覆盖一次「WS 失败 → 退避 2s → 再连」的三四拍。 */
const OBSERVE_MS = Number(process.env.NO_NEW_TAB_OBSERVE_MS || 12000);

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

async function waitUntilUp(url, tries = 60) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.describe('console stays in one tab while active', () => {
  let server;
  let base;

  test.beforeAll(async () => {
    const port = await freePort();
    base = `http://127.0.0.1:${port}`;
    // 静态面 = 真前端资源（零引擎）。python3 -m http.server 在 127.0.0.1 随机口。
    server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', WEB_ROOT], {
      stdio: 'ignore',
    });
    await waitUntilUp(`${base}/index.html`);
  });

  test.afterAll(async () => {
    if (server) server.kill('SIGTERM');
  });

  test('T1 活跃控制台 + WS 反复失败 ⇒ 零新标签 / 零新窗口（context.on("page") 计数）', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const pages = [];
    const popups = [];
    context.on('page', (p) => pages.push(p));

    const page = await context.newPage();
    page.on('popup', (p) => popups.push(p));

    // /api/** 全打桩：console-ticket 慢一点给，模拟「重连腿真的在跑」；其余返回空对象。
    const apiHits = [];
    await page.route('**/api/**', async (route) => {
      const url = new URL(route.request().url());
      apiHits.push(url.pathname);
      if (url.pathname === '/api/portal/status') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ boundHost: { deviceId: 'dev-test' } }) });
      }
      if (url.pathname === '/api/portal/console-ticket') {
        // 重座腿拿到的 consoleUrl：指向**本站**（同源原地重进），票值用短夹具。
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ consoleUrl: `${base}/index.html?ticket=tk-nonnewtab` }) });
      }
      if (url.pathname === '/api/health/loopback-info') {
        return route.fulfill({ status: 404, body: '' });
      }
      if (url.pathname === '/api/auth/login-config') {
        return route.fulfill({ status: 404, body: '' });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });

    await page.goto(`${base}/index.html`, { waitUntil: 'domcontentloaded' });

    // 观察窗：重连腿（页面在、WS 打不通 ⇒ onclose → 退避 → 再连）自己跑。
    await page.waitForTimeout(OBSERVE_MS);

    // 硬断言：整个观察窗内**没有任何新页面**（page 事件数 = 1，就是初始那一张）。
    expect(pages.length, `新标签页/新窗口出现了 ${pages.length - 1} 个：${pages.slice(1).map((p) => p.url()).join(', ')}`).toBe(1);
    expect(popups.length, `popup 事件 ${popups.length} 次`).toBe(0);
    expect(context.pages().length).toBe(1);

    // 反空虚：页面真的在跑（至少发过 API，说明 JS 起来了、腿在跑，不是死页面）。
    expect(apiHits.length, '页面一次 /api 都没发 —— 断言会变成空转，测本身失效').toBeGreaterThan(0);

    // 记录命中面（供对账：哪几条自动腿真的跑到了）
    console.log(`[no-new-tab] api hits (${apiHits.length}): ${[...new Set(apiHits)].join(' ')}`);
    console.log(`[no-new-tab] pages=${pages.length} popups=${popups.length} url=${page.url()}`);

    await context.close();
  });

  test('T2 三条重座腿的共用出口只产「原地导航串」——无开窗入口，卡窗契约守恒', async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.route('**/api/**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
    await page.goto(`${base}/index.html`, { waitUntil: 'domcontentloaded' });

    const out = await page.evaluate(async () => {
      const mod = await import('/js/reseatTarget.js');
      const consoleUrl = 'https://link.example.test/?ticket=tk-1';
      const main = mod.resolveReseatTarget(consoleUrl, mod.readReseatContext(''), 'https://link.example.test/');
      const cardCtx = mod.readReseatContext('?card-window=1&path=chat&title=T');
      const card = mod.resolveReseatTarget('http://127.0.0.1:6138/?ticket=tk-2', cardCtx, 'http://127.0.0.1:6138/');
      // 导出面审计：模块不许提供任何开窗 API（window.open 指纹）
      return {
        exports: Object.keys(mod).sort(),
        main,
        card,
        cardHasContract: /card-window=1/.test(card) && /path=chat/.test(card) && /title=T/.test(card),
        mainUnchanged: main === consoleUrl,
        // 返回值可直接赋给 location.href（字符串、非空、无布局/窗口特征串）
        isPlainString: typeof main === 'string' && main.length > 0 && !/popup=|noopener|width=|height=/.test(main),
      };
    });

    expect(out.isPlainString).toBe(true);
    expect(out.mainUnchanged, '非卡窗重座目标被改写 —— 主窗腿必须逐字不变').toBe(true);
    expect(out.cardHasContract, '卡窗重座丢了卡窗契约（会把独立卡窗升级成整个控制台）').toBe(true);
    // 该模块只暴露「解析/读取」两个纯函数；它没有、也不许有开窗入口。
    expect(out.exports).toEqual(['readReseatContext', 'resolveReseatTarget']);

    await context.close();
  });
});
