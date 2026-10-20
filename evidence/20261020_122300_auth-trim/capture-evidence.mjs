// capture-evidence.mjs — auth-trim 批（2026-10-20 作者令）验收截图。
// 形态参照 tests/settings-cleanup.spec.mjs 的静态 harness（隔离静态服务器 +
// routeWebSocket mock WS + route mock API，零真实后端）。
// Run: node evidence/20261020_122300_auth-trim/capture-evidence.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', '..', 'src', 'main', 'resources', 'web');
const OUT = HERE;
const PORT = 8187; // 隔离静态端口（8100+ 纪律，非宿主 8080）
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };
const PX_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
const AVATAR_URL = 'https://cdn.neblink-space-mock.test/av.png';

const server = createServer(async (req, res) => {
  console.log('[srv]', req.url);
  try {
    let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p === '/') p = '/index.html';
    const file = resolve(join(WEB, p));
    if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
    res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(await readFile(file));
  } catch {
    if (!res.headersSent) { res.writeHead(404); res.end(); }
  }
});
await new Promise(r => server.listen(PORT, '127.0.0.1', r));
const base = `http://127.0.0.1:${PORT}`;

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
await page.emulateMedia({ colorScheme: 'light' });
await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('neblink_locale', 'zh-CN');
});
await page.route('**/api/**', r => r.fulfill({ json: {} }));
await page.route('**/api/neblink/status', r => r.fulfill({
  json: {
    loggedIn: true,
    device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: AVATAR_URL, email: 'me@example.com', displayName: '测试用户' },
    peers: [],
  },
}));
await page.route('**/cdn.neblink-space-mock.test/**', r => r.fulfill({ contentType: 'image/png', body: PX_PNG }));
await page.routeWebSocket(/\/ws/, ws => {
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
});
page.on('request', r => { if (!r.url().includes('127.0.0.1')) console.log('[req]', r.url()); });
await page.goto(base + '/index.html', { waitUntil: 'domcontentloaded', timeout: 20000 });
await page.waitForSelector('#activity-bar', { timeout: 15000 });
await page.waitForTimeout(800);

// 1) 顶栏/活动栏新形态（设置钮无更新绿点）
await page.screenshot({ path: join(OUT, '01-topbar-activitybar.png') });
const sb = await page.evaluate(() => {
  const r = document.getElementById('settings-btn').getBoundingClientRect();
  return { x: Math.max(0, r.x - 12), y: Math.max(0, r.y - 12), width: r.width + 24, height: r.height + 24 };
});
await page.screenshot({ path: join(OUT, '02-settings-btn-closeup.png'), clip: sb });

// 2) 设置页新形态（登录态：只读身份行，无切换/退出两键；关于区无更新行）
await page.click('#settings-btn');
await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
await page.waitForTimeout(500);
await page.screenshot({ path: join(OUT, '03-settings-full-loggedin.png') });

// 3) 账号块特写（只读身份行）
const acc = await page.evaluate(() => {
  const el = document.querySelector('.neblink-logged-in, .neblink-login-section');
  const r = (el || document.body).getBoundingClientRect();
  return { x: Math.max(0, r.x - 24), y: Math.max(0, r.y - 40), width: r.width + 48, height: r.height + 64 };
});
await page.screenshot({ path: join(OUT, '04-account-block-ro.png'), clip: acc });

// 4) 关于区特写（无检查更新/立即更新；重启服务在位）
await page.evaluate(() => {
  const about = [...document.querySelectorAll('.settings-section')]
    .find(s => s.querySelector('.settings-section-title')?.textContent.trim() === '关于');
  if (about) {
    about.scrollIntoView({ block: 'center' });
    about.dataset.__probe = '1';
  }
});
await page.waitForTimeout(300);
const aboutBox = await page.evaluate(() => {
  const about = [...document.querySelectorAll('.settings-section')]
    .find(s => s.querySelector('.settings-section-title')?.textContent.trim() === '关于');
  const r = (about || document.body).getBoundingClientRect();
  return { x: Math.max(0, r.x - 8), y: Math.max(0, r.y - 16), width: r.width + 16, height: r.height + 32 };
});
await page.screenshot({ path: join(OUT, '05-about-section-no-update.png'), clip: aboutBox });

// DOM 佐证随图落盘
const proof = await page.evaluate(() => {
  const c = document.getElementById('settings-content');
  return {
    logoutBtn: !!c.querySelector('#neblink-logout-btn'),
    switchBtn: !!c.querySelector('#neblink-switch-btn'),
    accountRo: c.querySelector('.neblink-account-ro')?.textContent || null,
    checkUpdateBtn: !!c.querySelector('#btn-check-update'),
    doUpdateBtn: !!c.querySelector('#btn-do-update'),
    updateStatus: !!c.querySelector('#update-status'),
    restartBtn: !!c.querySelector('#btn-restart-gateway'),
    settingsDot: !!document.querySelector('#settings-btn .settings-update-dot'),
  };
});
await writeFile('dom-proof.json', JSON.stringify(proof, null, 2));

await browser.close();
server.close();
console.log('shots + dom-proof.json written to', OUT);
console.log(JSON.stringify(proof));
