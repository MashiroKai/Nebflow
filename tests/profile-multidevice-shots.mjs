#!/usr/bin/env node
// profile-multidevice-shots.mjs — 静态预览实拍（profile-multidevice 批）。
//
// 零实例、零生产数据、零受保护端口：web 根目录由本地文件直读，全部 REST/WS 输入
// 走 Playwright 路由桩，页面只 goto 一个不会监听任何端口的地址（http://localhost:1/）。
//
// 场景（手机 390×844 亮 / 暗 + 桌面 1600×1000 亮）：
//   S1 手机个人资料页（整屏）—— 单页资料 + 只读身份 + 官网指引 + 设备清单
//   S2 手机设备清单近景
//   S3 桌面个人资料面（整屏）—— 与手机同一份设备清单标记
//   S4 桌面身份卡（胶囊入口，对照）
//
// 全程 console error 采集：任何一条进 manifest 并让退出码 = 1（零错误是硬判据）。
//
// Run: node tests/profile-multidevice-shots.mjs
//      SHOTS_OUT=<abs dir> node tests/profile-multidevice-shots.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.SHOTS_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.SHOTS_OUT || '/private/tmp/stand-profile-delivery/profile-multidevice/evidence';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const SELF = { id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', displayName: 'Fixture User', email: 'fixture@example.test' };
const PEERS = [
  { deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', lastSeen: Date.now() - 120_000, online: true, directOnline: true },
  { deviceId: 'dev-old', deviceName: 'OldBox', platform: 'linux', lastSeen: Date.now() - 3 * 86_400_000, online: false, directOnline: false },
];

/** @type {{scene: string, file: string, ok: boolean, readings?: any}[]} */
const manifest = [];
/** @type {string[]} */
const consoleErrors = [];

async function boot(page, scheme) {
  await page.addInitScript(({ scheme }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  }, { scheme });
  const tag = `${page.viewportSize()?.width || 0}`;
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`${tag}: ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`${tag}: pageerror: ${e.message} :: ${String(e.stack || '').split('\n').slice(0, 4).join(' | ')}`));
  page.on('response', (r) => { if (r.status() === 404) consoleErrors.push(`${tag}: http404 ${r.url()}`); });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/neblink/status') return route.fulfill({ json: { loggedIn: true, device: SELF, peers: PEERS } });
      if (p === '/api/users/search') {
        return route.fulfill({
          json: { found: true, user: { userId: 'u-self', username: 'Mashiro', display_name: 'Fixture User', avatar: null }, relation_status: 'self' },
        });
      }
      return route.fulfill({ json: {} });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    config();
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'root' }));
    ws.onMessage((raw) => { try { if (JSON.parse(raw).type === 'getConfig') config(); } catch { /* 非 JSON 帧 */ } });
  });
  await page.goto('http://localhost:1/');
}

async function shot(page, scene, file, clip = null, readings = undefined) {
  mkdirSync(OUT, { recursive: true });
  const path = join(OUT, file);
  try {
    await page.screenshot({ path, ...(clip ? { clip } : {}) });
    manifest.push({ scene, file: path, ok: true, ...(readings ? { readings } : {}) });
  } catch (e) {
    manifest.push({ scene, file: path, ok: false, readings: { error: String((e && e.message) || e) } });
  }
}

/** 设备清单读数（两棵树同一判据口径，供 manifest 佐证「同一份标记」）。 */
async function deviceReadings(page, rootSel) {
  return page.evaluate((sel) => {
    const host = document.querySelector(sel);
    const rows = [...(host?.querySelectorAll('[data-device-list="1"] .pd-device-row') || [])];
    return {
      rows: rows.length,
      items: rows.map((r) => ({
        id: r.dataset.deviceId, current: r.dataset.deviceCurrent, online: r.dataset.deviceOnline,
        name: r.querySelector('.pd-device-name-text')?.textContent?.trim() || '',
        meta: r.querySelector('.pd-device-meta')?.textContent?.trim() || '',
        badge: r.querySelector('.pd-device-badge')?.textContent?.trim() || '',
      })),
      editable: host ? host.querySelectorAll('input, select, textarea, [contenteditable], [role="switch"]').length : -1,
      anchors: host ? host.querySelectorAll('a[href]').length : -1,
      openCalls: Array.isArray(window.__openCalls) ? window.__openCalls.length : -1,
    };
  }, rootSel);
}

async function runMobile(browser, scheme) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: scheme, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  await boot(page, scheme);
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="me"]')?.classList.contains('live'), null, { timeout: 15000 });
  await page.waitForTimeout(500);
  const r = await deviceReadings(page, '.mobile-page[data-page="me"]');
  await shot(page, 'mobile-profile', `m-${scheme}-s1-profile.png`, null, r);
  // 设备清单近景（滚动到清单）
  await page.locator('.mobile-page[data-page="me"] [data-device-list="1"]').scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  await shot(page, 'mobile-devices', `m-${scheme}-s2-devices.png`, null, r);
  await ctx.close();
}

async function runDesktop(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  await boot(page, 'light');
  await page.waitForSelector('#settings-btn', { timeout: 20000 });
  await page.waitForTimeout(1000);
  await page.locator('#settings-btn').click();
  await page.waitForSelector('#settings-content', { timeout: 15000 });
  await page.waitForTimeout(800);
  // S4 身份卡（可选帧：卡片随设置账号段渲染；不在场就跳过，不阻断主场景）。
  if (await page.locator('#settings-identity-card').count()) {
    await page.waitForTimeout(400);
    await shot(page, 'desktop-identity-card', 'd-light-s4-settings-identity-card.png');
  } else {
    manifest.push({ scene: 'desktop-identity-card', file: '', ok: false, readings: { absent: '#settings-identity-card 未渲染（桌面设置账号段）—— 非阻塞' } });
  }
  // S3 个人资料面（桌面唯一入口：身份卡胶囊；本条走模块 API 以不受账号段渲染影响）。
  await page.evaluate(async () => (await import('/js/settingsIdentity.js')).openProfileFace());
  await page.waitForSelector('#settings-profile-overlay:not([hidden])', { timeout: 10000 });
  await page.waitForTimeout(600);
  const r = await deviceReadings(page, '#settings-profile-overlay');
  await shot(page, 'desktop-profile', 'd-light-s3-profile.png', null, r);
  await ctx.close();
}

const browser = await chromium.launch();
try {
  for (const scheme of ['light', 'dark']) await runMobile(browser, scheme);
  await runDesktop(browser);
} finally {
  await browser.close();
}

mkdirSync(OUT, { recursive: true });
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ web: WEB, out: OUT, manifest, consoleErrors }, null, 2));
const okCount = manifest.filter((m) => m.ok).length;
console.log(`shots -> ${OUT}`);
console.log(`scenes ok=${okCount} failed=${manifest.length - okCount} consoleErrors=${consoleErrors.length}`);
for (const m of manifest) console.log(`${m.ok ? 'OK  ' : 'FAIL'} ${m.scene} ${m.file}`);
if (consoleErrors.length) { console.log('CONSOLE ERRORS:'); for (const e of consoleErrors) console.log('  ' + e); }
process.exitCode = consoleErrors.length ? 1 : 0;
