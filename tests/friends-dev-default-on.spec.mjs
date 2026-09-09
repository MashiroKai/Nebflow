// friends-dev-default-on.spec.mjs — 好友功能构建环境区分·本地 dev 默认开
// （作者令 2026-09-10：好友功能只在 CI/CD 构建产物剔除/关闭；本地 dev
// （sbt run 直跑的未剥离构建）好友 flag 默认开，好友入口可见可调试）。
//
// 覆盖（featureFlags.js dev 分支语义，源码树直跑 = 无 __NEBFLOW_RELEASE__）：
//   D1  空配置（configData 不含 features.friends）→ Messages/Contacts 入口回挂可见
//   D2  点击 Contacts → 面板激活（入口可进，面板注册生效）
//   D3  显式 features.friends:false → 入口缺席（dev 两态调试：关态可达）
//   D4  显式 features.friends:true → 入口可见（gating 批测试注入口径不变）
// 自包含：route 拦截静态文件 + mock WS，无后端、无真实端口。
// Run: node tests/friends-dev-default-on.spec.mjs
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();

async function bootPage({ configJson = '{}' } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 't'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: configJson, configured: true, onboarding: 'done', models: [], defaults: {} }));
    sendConfig();
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async () => {
    const s = (await import('/js/state.js')).default;
    return s.ws && s.ws.readyState === 1;
  }, undefined, { timeout: 15000 });
  await sleep(600); // configData latch → enableFriendPanels 回挂
  return { ctx, page, pageErrors };
}

// ── D1/D2: 空配置 → dev 默认开，入口可见可进 ──
{
  const { ctx, page, pageErrors } = await bootPage({ configJson: '{}' });
  ok('D1 #messages-btn attached (dev default ON)', await page.locator('#messages-btn').count() === 1 && await page.locator('#messages-btn').evaluate(el => el.isConnected));
  ok('D1 #contacts-btn attached (dev default ON)', await page.locator('#contacts-btn').count() === 1 && await page.locator('#contacts-btn').evaluate(el => el.isConnected));
  // 顺序契约：通讯入口在 spacer 之前（index.html 静态位置由 enableFriendPanels 保持）
  const order = await page.locator('#activity-bar > *').evaluateAll(els => els.map(e => e.id || e.className));
  ok('D1 entries sit before the spacer', order.indexOf('messages-btn') !== -1 && order.indexOf('contacts-btn') !== -1 &&
    order.indexOf('contacts-btn') < order.findIndex(c => String(c).includes('activity-spacer')), JSON.stringify(order));
  await page.click('#contacts-btn');
  await sleep(200);
  ok('D2 #panel-contacts activated on click', await page.locator('#panel-contacts.active').count() === 1 &&
    (await page.locator('#contacts-btn').getAttribute('aria-pressed')) === 'true');
  ok('D1 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ── D3: 显式 false → dev 关态（入口缺席） ──
{
  const { ctx, page, pageErrors } = await bootPage({ configJson: '{"features":{"friends":false}}' });
  ok('D3 #messages-btn absent (explicit off)', await page.locator('#messages-btn').count() === 0);
  ok('D3 #contacts-btn absent (explicit off)', await page.locator('#contacts-btn').count() === 0);
  ok('D3 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ── D4: 显式 true → 开（gating 批 6 个 spec 的注入口径不回归） ──
{
  const { ctx, page, pageErrors } = await bootPage({ configJson: '{"features":{"friends":true}}' });
  ok('D4 #messages-btn attached (explicit on)', await page.locator('#messages-btn').count() === 1);
  ok('D4 #contacts-btn attached (explicit on)', await page.locator('#contacts-btn').count() === 1);
  ok('D4 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILED'}`);
process.exit(failures ? 1 : 0);
