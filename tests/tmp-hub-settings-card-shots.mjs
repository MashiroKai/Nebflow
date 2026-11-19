// tmp-hub-settings-card-shots.mjs — 分组卡片换装批截图自验（2026-10-31 作者令 12:33）
// 双端四连拍：mobile 375×812 工作台 hub + desktop 1440×900 设置面板，亮/暗各一。
// BEFORE=1 时 /css/sidebar.css 与 /css/mobileLayer.css 改从 /tmp/nfsb-before/ 供给
// （改前基线，零 git 变异）。自足：静态服务 + API mock + WS mock，无真实引擎。
import { chromium } from 'playwright-core';
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const BEFORE_DIR = '/tmp/nfsb-before';
const IS_BEFORE = process.env.BEFORE === '1';
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261031_hub-settings-card-reskin');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };
const SID = 'smoke-session-1';

const browser = await chromium.launch();
const consoleErrors = [];

async function makePage(colorScheme, viewport) {
  const page = await (await browser.newContext({ viewport, colorScheme, deviceScaleFactor: 2 })).newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${colorScheme}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${colorScheme}] PAGEERROR ${e.message}`));
  await page.addInitScript(() => localStorage.setItem('nebflow_token', 'smoke-token'));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], activeId: SID }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (IS_BEFORE && (p === '/css/sidebar.css' || p === '/css/mobileLayer.css') && existsSync(join(BEFORE_DIR, p.slice(5)))) {
      return route.fulfill({ status: 200, contentType: 'text/css', body: readFileSync(join(BEFORE_DIR, p.slice(5))) });
    }
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'Smoke', agentName: 'Nebula' }], folders: [], activeId: SID }));
  });
  await page.goto('http://localhost:1/');
  await page.waitForTimeout(1800);
  return page;
}

const tag = IS_BEFORE ? 'before' : 'after';

// ── Desktop 1440×900：设置面板 ────────────────────────────────────────
for (const scheme of ['light', 'dark']) {
  const page = await makePage(scheme, { width: 1440, height: 900 });
  await page.click('#settings-btn');
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${OUT}/settings-1440-${scheme}-${tag}.png` });
  await page.close();
}

// ── Mobile 375×812：工作台 hub ────────────────────────────────────────
for (const scheme of ['light', 'dark']) {
  const page = await makePage(scheme, { width: 375, height: 812 });
  await page.waitForFunction(() => window.__nfMobileLayer && window.__nfMobileLayer.active, null, { timeout: 8000 });
  await page.click('.dock-key[data-dock="workbench"]');
  await page.waitForTimeout(600);
  await page.screenshot({ path: `${OUT}/workbench-375-${scheme}-${tag}.png` });
  // 附带：手机端设置页（同一份 settings-content 样式收编面回归）
  await page.click('.mf-hub-row[data-hub-page="settings"]');
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${OUT}/mobile-settings-375-${scheme}-${tag}.png` });
  await page.close();
}

await browser.close();
console.log(`shots saved to ${OUT} (tag=${tag})`);
console.log('console errors:', consoleErrors.length ? consoleErrors.join('\n') : '(none)');
