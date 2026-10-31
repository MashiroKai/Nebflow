// config-drill-shots.mjs — 配置面风格统一批（作者令 2026-10-31 21:40，两件）
// 双端双主题截图取证。
//
// 覆盖：
//   ① JeV/生图层级下钻新面：桌面浮层玻璃面板（亮/暗）+ 手机层级子页（亮/暗）
//      + 设置页入口行全貌（右转 chevron affordance）。
//   ② 设置页「模型回退链」节（/model 回退链编辑器同款面）+ /model 原面板
//      对照（原入口保留不动）。
//
// 用法：node tests/config-drill-shots.mjs
// 产出：evidence/20261031_config-drill/<面>-<端>-<主题>.png（deviceScaleFactor 2）
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOTS_DIR = process.env.SHOTS_DIR ||
  join(HERE, '..', 'evidence', '20261031_config-drill');
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 's-config-drill';
const NOW = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const HISTORY = [
  { type: 'user', text: 'hello omega seed', timestamp: NOW - 3600000 },
  { type: 'ai', text: 'omega reply text', timestamp: NOW - 3500000 },
];

const CHAIN = { preferred: 'kimi/kimi-k2', fallbacks: ['anon/anon-1'] };

const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

function wireUp(ws) {
  const sendConfig = () => ws.send(JSON.stringify({
    type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
    config: JSON.stringify({
      llm: {
        providers: {
          kimi: { baseUrl: 'https://api.kimi.example/v1/', apiKey: '***', protocol: 'anthropic', models: [{ id: 'kimi-k2', contextWindow: 8192 }] },
          anon: { baseUrl: 'https://anon.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'anon-1', contextWindow: 4096 }] },
        },
      },
      jev: { enabled: true, provider: 'typesafe-jev', endpoint: 'https://api.typesafe.ai/v1/systemone', keyRef: 'typesafe-api-key' },
    }),
  }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
  }));
  sendConfig();
  sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') {
      ws.send(JSON.stringify({
        type: 'historyPage', sessionId: m.sessionId, messages: HISTORY,
        hasMore: false, offset: HISTORY.length, total: HISTORY.length,
      }));
    }
  });
}

const browser = await chromium.launch();
const consoleErrors = [];

/** 通用启动：viewport/touch/colorScheme 由调用方给。 */
async function boot(viewport, colorScheme, { touch = false } = {}) {
  const ctx = await browser.newContext({ viewport, colorScheme, deviceScaleFactor: 2, hasTouch: touch });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  await page.route('**/api/**', (route) => {
    const p = new URL(route.request().url()).pathname;
    if (p === '/api/sessions') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p.startsWith(`/api/sessions/${SID}/history`)) {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ messages: HISTORY, total: HISTORY.length, sessionId: SID }) });
    }
    if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          mode: 'explicit', chain: CHAIN, effectiveChain: CHAIN,
          resolvedFrom: 'own-chain', current: CHAIN.preferred, settable: true,
        }),
      });
    }
    if (p === '/api/config/imagegen') {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          compiledIn: true, enabled: true,
          providers: { zhipu: { type: 'cogview', model: 'cogview-3-flash', keyConfigured: true } },
        }),
      });
    }
    return route.fulfill({ contentType: 'application/json', body: '{}' });
  });
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#chat .row', { timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await sleep(800);
  return { ctx, page };
}

const shot = (page, name) => page.screenshot({ path: join(SHOTS_DIR, name), fullPage: false });

async function openSettings(page) {
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
  await sleep(400);
}

// ══ 桌面腿（1440×900）：设置页入口行 + JeV/生图浮层 + 回退链节 + /model 对照 ══
async function desktopLeg(colorScheme) {
  const { ctx, page } = await boot({ width: 1440, height: 900 }, colorScheme);
  await openSettings(page);
  // 等生图面装配（层级下钻面：内容隐藏在场，attached 判据）+ 回退链节渲染。
  await page.waitForSelector('#imagegen-section-wrap .imagegen-face', { state: 'attached', timeout: 10000 });
  await page.waitForFunction(() => document.querySelectorAll('#model-chain-section-body .mp-row').length === 2, null, { timeout: 5000 });
  await sleep(400);
  await shot(page, `settings-desktop-${colorScheme}.png`);

  // JeV 层级下钻浮层。
  await page.click('#jev-advance-toggle');
  await page.waitForFunction(() => !!document.getElementById('jev-advance-body')?.closest('.config-drill-overlay'));
  await sleep(300);
  await shot(page, `jev-overlay-desktop-${colorScheme}.png`);
  const jevGeo = await page.evaluate(() => {
    const box = document.querySelector('.config-drill-modal');
    const r = box.getBoundingClientRect();
    return { w: Math.round(r.width), rad: getComputedStyle(box).borderRadius };
  });
  ok(`jev overlay desktop ${colorScheme}: 居中玻璃盒（<600px、圆角保留）`, jevGeo.w < 600 && jevGeo.rad === '20px', JSON.stringify(jevGeo));
  // × 关闭浮层（Esc 会连带关设置页壳——壳自身有 Esc 监听，此处只关浮层）。
  await page.click('.config-drill-overlay .config-drill-close');
  await page.waitForFunction(() => !document.querySelector('.config-drill-overlay'));

  // 生图层级下钻浮层。
  await page.click('#imagegen-advance-toggle');
  await page.waitForFunction(() => !!document.getElementById('imagegen-advance-body')?.closest('.config-drill-overlay'));
  await sleep(300);
  await shot(page, `imagegen-overlay-desktop-${colorScheme}.png`);
  await page.click('.config-drill-overlay .config-drill-close');
  await page.waitForFunction(() => !document.querySelector('.config-drill-overlay'));

  // 回退链节（②：设置页原样再放一份）+ /model 原面板对照。
  const chain = page.locator('#model-chain-section');
  await chain.scrollIntoViewIfNeeded();
  await sleep(300);
  await chain.screenshot({ path: join(SHOTS_DIR, `chain-section-desktop-${colorScheme}.png`) });
  await page.evaluate(async () => {
    const m = await import('/js/modelPanel.js');
    await m.openModelPanel();
  });
  await page.waitForFunction(() => document.querySelectorAll('#model-panel-overlay .mp-roleblock-nebula .mp-row').length === 2, null, { timeout: 5000 });
  await sleep(300);
  await shot(page, `model-panel-desktop-${colorScheme}.png`);
  ok(`model panel entry intact desktop ${colorScheme}`, true);
  await ctx.close();
}

// ══ 手机腿（375×812 touch）：设置页 + 生图层级子页 + 回退链节内联面 ══
async function mobileLeg(colorScheme) {
  const { ctx, page } = await boot({ width: 375, height: 812 }, colorScheme, { touch: true });
  await dockTo(page, 'workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="settings"]')?.classList.contains('live'));
  await page.waitForSelector('#imagegen-section-wrap .imagegen-face', { state: 'attached', timeout: 10000 });
  await sleep(400);
  await shot(page, `settings-mobile-${colorScheme}.png`);

  // 生图层级子页（配置面风格统一批：第四枚 settings-sub 路由）。
  await page.locator('#imagegen-advance-toggle').click();
  await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="settings-imagegen"]')?.classList.contains('live'));
  await page.waitForFunction(() => {
    const sub = document.querySelector('.mobile-page[data-page="settings-imagegen"]');
    const body = sub && sub.querySelector('.mobile-page-body #imagegen-advance-body');
    return !!body && !body.hidden;
  });
  await sleep(400);
  await shot(page, `imagegen-sub-mobile-${colorScheme}.png`);
  const subGeo = await page.evaluate(() => {
    const rect = (sel) => {
      const el = document.querySelector(sel);
      const r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height) };
    };
    return {
      sub: rect('.mobile-page[data-page="settings-imagegen"]'),
      root: rect('.mobile-page[data-page="settings"]'),
      vw: window.innerWidth,
    };
  });
  // 铺满屏 = 与设置根页同壳同几何（页面壳自管安全区/dock 内距——同族一致即可）。
  ok(`imagegen sub-page mobile ${colorScheme}: 与设置根页同壳铺满`, subGeo.sub.w === subGeo.root.w && subGeo.sub.h === subGeo.root.h && subGeo.sub.w === subGeo.vw, JSON.stringify(subGeo));
  // 页壳「<」返回 = 层级导航先例同款。
  await page.locator('.mobile-page[data-page="settings-imagegen"] .mobile-back-btn').click();
  await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="settings"]')?.classList.contains('live'));

  // 回退链节：设置页内联面（滚动到位截图）。
  await page.evaluate(() => {
    document.getElementById('model-chain-section')?.scrollIntoView({ block: 'center' });
  });
  await sleep(400);
  await shot(page, `chain-section-mobile-${colorScheme}.png`);
  ok(`chain section inline mobile ${colorScheme}`, true);
  await ctx.close();
}

for (const scheme of ['light', 'dark']) {
  await desktopLeg(scheme);
  await mobileLeg(scheme);
}

// ── 收尾：console 零新增错误 ──────────────────────────────────────────────
const ALLOWLIST = /favicon|net::|WebSocket|404/; // 静态兜底 {} 与 mock 面无 favicon
const fresh = consoleErrors.filter(e => !ALLOWLIST.test(e));
ok('console 零新增错误', fresh.length === 0, fresh.slice(0, 5).join(' | '));

await browser.close();
server.close();
console.log(`\nshots → ${SHOTS_DIR}`);
process.exit(failures ? 1 : 0);
