#!/usr/bin/env node
// settings-account-removed.mjs — 「设置页账户栏 / 个人主页底部账户栏整件退役」的**渲染级**闸
// （作者令 2026-11-18 16:36「设置里的账户这一栏完全移除」+ 16:45「底部的账户栏 功能重复
//  信息重复…不要有重复的功能和信息」；配套**源码级**闸 = scripts/check-panel-uniform.mjs）。
//
// 判据 = 在**真实形态**下断言「该栏不存在」（不是隐藏）：
//   R1 mobile 设置页：全屏页 `.mobile-page[data-page="settings"].live`（非弹窗）+ 覆盖
//      整个 375×812 视口 ⇒ 这是作者真实看到的手机全屏设置页；页内
//      `[data-panel-group="account"]` / `#account-card` / `.neblink-account-ro` /
//      `#settings-devices-slot` 全族零命中。
//   R2 desktop 设置页：`#settings-modal` 内同一族零命中。
//   R3 mobile 个人主页：`.mf-account-card` / `.neblink-account-ro` 零命中（底部重复栏）。
//   R4 源码级复核：sidebar.js / meFace.js 的 emitted code（剥注释）里无 account 栏发射点。
//
// 自证检测力（两次退出码）：本脚本支持 `SA_WEB_ROOT` 指向另一棵树 —— 指向**退役前**的
//   干净树 ⇒ 必红（exit 1，逐条列出命中）；指向本仓 web ⇒ 必绿（exit 0）。
//   Run（cwd = 主仓）：
//     node tests/settings-account-removed.mjs
//     SA_WEB_ROOT=<before-tree>/src/main/resources/web node tests/settings-account-removed.mjs   # 期望 exit 1
//
// 退出码：0 = 绿；1 = 红（渲染面或源码面命中退役面）；2 = 环境错误（浏览器/夹具失败）。

import { chromium } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.SA_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'settings-account-removed';
const SELF = {
  id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '',
  displayName: 'Mashiro', email: 'mashiro@example.com', avatarUrl: '',
};
const PEERS = [{ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true }];

/** 退役面清单（DOM 选择器）—— 全族零命中 = 该栏不存在。 */
const RETIRED_SELECTORS = [
  '[data-panel-group="account"]', '#account-card', '.neblink-account-ro',
  '.neblink-logged-in', '.neblink-login-section', '.neblink-logged-out-hint',
  '.neblink-kicked-notice', '.neblink-account-actions', '#settings-devices-slot',
];
const ME_RETIRED_SELECTORS = ['.mf-account-card', '.neblink-account-ro', '.neblink-logged-in'];

const errors = [];

async function wire(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('fm_api_mock', '1');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: SELF, peers: PEERS }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); ses();
    ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg(); else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 })); });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws && s.ws.readyState === 1; }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
}

/** 「真实形态」证据：手机 = 全屏页面（不是弹窗），且覆盖整个视口。 */
async function formEvidence(page, sel) {
  return page.evaluate((s) => {
    const box = document.querySelector(s);
    const r = box ? box.getBoundingClientRect() : null;
    const vw = window.innerWidth, vh = window.innerHeight;
    return {
      isMobileLayer: document.body.classList.contains('mobile-layer'),
      isFullscreenPage: !!(box && box.classList.contains('mobile-page')),
      isModalOverlay: !!(box && box.classList.contains('cfg-modal-overlay')),
      coverW: r ? +(r.width / vw).toFixed(3) : 0,
      coverH: r ? +(r.height / vh).toFixed(3) : 0,
    };
  }, sel);
}

async function scanAll(page, containerSel, sels, label) {
  const out = {};
  for (const sel of sels) {
    const scoped = await page.evaluate(({ c, s }) => {
      const root = c ? document.querySelector(c) : document;
      return root ? root.querySelectorAll(s).length : -1;
    }, { c: containerSel, s: sel });
    out[sel] = scoped;
    if (scoped !== 0) errors.push(`${label}: retired selector ${sel} must be zero-hit, got ${scoped}`);
  }
  return out;
}

async function runMobile(browser) {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark', deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`mobile pageerror: ${e.message}`));
  await wire(page);
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'));
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForFunction(() => { const el = document.querySelector('.mobile-page[data-page="workbench"]'); return el && el.classList.contains('live'); });
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await page.locator('.mobile-page[data-page="settings"].live').waitFor({ timeout: 10000 });
  await page.waitForTimeout(600);
  const form = await formEvidence(page, '.mobile-page[data-page="settings"]');
  // 真实形态铁律：作者看到的是**全屏**手机设置页，不是弹窗 / 内嵌面板。
  if (!form.isMobileLayer) errors.push('mobile: body must carry .mobile-layer');
  if (!form.isFullscreenPage) errors.push('mobile: target must be a .mobile-page (fullscreen), not a modal');
  if (form.isModalOverlay) errors.push('mobile: target must not be a .cfg-modal-overlay');
  if (form.coverW < 0.99 || form.coverH < 0.99) errors.push(`mobile: settings page must cover the viewport (coverW=${form.coverW} coverH=${form.coverH})`);
  const groups = await page.evaluate(() => {
    const c = document.getElementById('settings-content');
    return c ? [...c.querySelectorAll(':scope > [data-panel-group]')].map((n) => (n.dataset.panelGroup || '')) : [];
  });
  if (groups.includes('account')) errors.push('mobile: settings groups must not contain "account"');
  const settingsHits = await scanAll(page, '#settings-content', RETIRED_SELECTORS, 'mobile settings');
  // 个人主页（me）：底部重复栏同族零命中。
  await page.locator('.mobile-page[data-page="settings"] .mobile-back-btn').click();
  await page.waitForTimeout(300);
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await page.locator('.mobile-page[data-page="me"].live').waitFor({ timeout: 10000 });
  await page.waitForTimeout(500);
  const meHits = await scanAll(page, '.mobile-page[data-page="me"]', ME_RETIRED_SELECTORS, 'mobile me page');
  await ctx.close();
  return { form, groups, settingsHits, meHits };
}

async function runDesktop(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`desktop pageerror: ${e.message}`));
  await wire(page);
  await page.locator('#settings-btn').click();
  await page.locator('#settings-modal').waitFor({ timeout: 10000 });
  await page.waitForTimeout(600);
  const groups = await page.evaluate(() => {
    const c = document.getElementById('settings-content');
    return c ? [...c.querySelectorAll(':scope > [data-panel-group]')].map((n) => (n.dataset.panelGroup || '')) : [];
  });
  if (groups.includes('account')) errors.push('desktop: settings groups must not contain "account"');
  const hits = await scanAll(page, '#settings-modal', RETIRED_SELECTORS, 'desktop settings');
  await ctx.close();
  return { groups, hits };
}

/** 源码级复核（emitted code，剥注释后）：不得再有账户栏发射点。 */
function sourceScan() {
  const strip = (src) => src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^(\s*)\/\/.*$/gm, '$1');
  const read = (p) => strip(readFileSync(normalize(join(WEB, p)), 'utf8'));
  const files = {
    'js/sidebar.js': [/data-panel-group="account"/, /accountCardHTML|neblinkSettingsHTML/],
    'js/mobile/meFace.js': [/accountCardHTML|neblinkSettingsHTML|mf-account-card/],
    'js/neblink.js': [/export function (accountCardHTML|neblinkSettingsHTML|bindNeblinkEvents)\b/],
    'js/messages.js': [/settings-devices-slot|settingsDevicesHTML|mountSettingsDevices/],
  };
  const report = {};
  for (const [p, pats] of Object.entries(files)) {
    let src = '';
    try { src = read(p); } catch { errors.push(`source: cannot read ${p}`); continue; }
    report[p] = pats.map((re) => re.test(src));
    pats.forEach((re, i) => { if (report[p][i]) errors.push(`source: ${p} still emits the retired account bar (pattern #${i + 1})`); });
  }
  return report;
}

const browser = await chromium.launch();
let mobile, desktop, source;
try {
  mobile = await runMobile(browser);
  desktop = await runDesktop(browser);
  source = sourceScan();
} catch (e) {
  console.error('environment error:', e?.message || e);
  await browser.close();
  process.exit(2);
}
await browser.close();

console.log(JSON.stringify({ web: WEB, mobile, desktop, source, errors }, null, 2));
if (errors.length) {
  for (const e of errors) console.error('FAIL settings-account-removed:', e);
  process.exit(1);
}
console.log('PASS settings-account-removed: the settings account column and the personal-home duplicate account bar are absent (fullscreen mobile + desktop), across render and source');
