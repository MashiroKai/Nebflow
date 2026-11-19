#!/usr/bin/env node
// settings-account-capture.mjs — 「设置页账户栏移除」批证据实拍（作者令 2026-11-18 16:36）。
//
// 单支脚本跑两棵树：SHOTS_WEB_ROOT 未设 ⇒ 跑本仓 web（改后树）；设 = 改前树。
// 输出到 SHOTS_OUT（默认 worktree 之外的 /tmp/stand-settings-account-evidence/<phase>）。
//
// 两个视口：手机 **全屏**设置页 375×812（body.mobile-layer，经工作台 dock → 设置 hub 行，
// `.mobile-page[data-page="settings"].live`）+ 桌面设置页 1280×800（#settings-btn → #settings-modal）。
// 手机形态**不是弹窗**：脚本对两者断言「真实形态」——
//   · body 有 mobile-layer 类；
//   · 目标容器 = `.mobile-page[data-page="settings"]`（全屏页面），不是 `.cfg-modal-overlay`；
//   · 容器包围盒 ≈ 整个视口（宽=375、高=812），且覆盖面 ≈ 100%。
//
// Run:
//   SHOTS_OUT=/tmp/stand-settings-account-evidence/after node tests/settings-account-capture.mjs
//   SHOTS_WEB_ROOT=<before-tree>/src/main/resources/web \
//   SHOTS_OUT=/tmp/stand-settings-account-evidence/before node tests/settings-account-capture.mjs

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.SHOTS_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.SHOTS_OUT || '/tmp/stand-settings-account-evidence/after';
const PHASE = process.env.SHOTS_PHASE || 'after';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'settings-account-capture';
const SELF = {
  id: 'dev-self', name: 'Mashiro-Mac', platform: 'macos', userDescription: '',
  displayName: 'Mashiro', email: 'mashiro@example.com', avatarUrl: '',
};
const PEERS = [{ deviceId: 'dev-kai', deviceName: 'KAI', platform: 'windows', online: true, directOnline: true }];

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

/** 「真实形态」读数：全屏页面 vs 弹窗的机械证据。 */
async function formEvidence(page, sel) {
  return page.evaluate((s) => {
    const box = document.querySelector(s);
    const r = box ? box.getBoundingClientRect() : null;
    const vw = window.innerWidth, vh = window.innerHeight;
    return {
      isMobileLayer: document.body.classList.contains('mobile-layer'),
      isFullscreenPage: !!(box && box.classList.contains('mobile-page')),
      isModalOverlay: !!(box && box.classList.contains('cfg-modal-overlay')),
      viewport: { w: vw, h: vh },
      box: r ? { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) } : null,
      coverW: r ? +(r.width / vw).toFixed(3) : 0,
      coverH: r ? +(r.height / vh).toFixed(3) : 0,
    };
  }, sel);
}

/** 账户栏读数：本批要消除的目标。 */
async function accountRead(page) {
  return page.evaluate(() => {
    const content = document.getElementById('settings-content');
    const q = (s) => (content ? content.querySelectorAll(s).length : -1);
    const acc = content ? content.querySelector('[data-panel-group="account"]') : null;
    return {
      groups: content ? [...content.querySelectorAll(':scope > [data-panel-group]')].map((n) => n.dataset.panelGroup) : [],
      accountSection: q('[data-panel-group="account"]'),
      accountCard: q('#account-card'),
      loginRoLine: q('.neblink-account-ro'),
      devicesSlot: q('#settings-devices-slot'),
      accountH2: acc ? (acc.querySelector('h2')?.textContent || '').trim() : '',
    };
  });
}

const manifest = [];
const consoleErrors = [];
async function shot(page, file, sel) {
  mkdirSync(OUT, { recursive: true });
  const path = join(OUT, file);
  const el = sel ? page.locator(sel).first() : null;
  if (el) await el.screenshot({ path, animations: 'disabled' });
  else await page.screenshot({ path, animations: 'disabled' });
  manifest.push({ phase: PHASE, file: path });
}

async function runMobile(browser) {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark', deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`mobile: ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`mobile: pageerror: ${e.message}`));
  await wire(page);
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'));
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForFunction(() => { const el = document.querySelector('.mobile-page[data-page="workbench"]'); return el && el.classList.contains('live'); });
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await page.locator('.mobile-page[data-page="settings"].live').waitFor({ timeout: 10000 });
  await page.waitForTimeout(700);
  const form = await formEvidence(page, '.mobile-page[data-page="settings"]');
  const acct = await accountRead(page);
  await shot(page, `mobile-375x812-settings.png`, '.mobile-page[data-page="settings"]');
  const hasAcc = await page.locator('#settings-content [data-panel-group="account"]').count();
  if (hasAcc) await shot(page, `mobile-375x812-account-zoom.png`, '#settings-content [data-panel-group="account"]');
  // 个人主页（me）：工作台 hero → me 页，作为「最底下重复栏」候选面实拍对照。
  await page.locator('.mobile-page[data-page="settings"] .mobile-back-btn').click();
  await page.waitForTimeout(300);
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await page.locator('.mobile-page[data-page="me"].live').waitFor({ timeout: 10000 });
  await page.waitForTimeout(600);
  const meRead = await page.evaluate(() => {
    const me = document.querySelector('.mobile-page[data-page="me"]');
    const kids = me ? [...me.querySelectorAll('.mobile-page-body > *')].map((n) => n.className) : [];
    return {
      accountCard: me ? me.querySelectorAll('.mf-account-card').length : -1,
      loginRoLine: me ? me.querySelectorAll('.neblink-account-ro').length : -1,
      loginRoText: (me?.querySelector('.neblink-account-ro')?.textContent || '').trim(),
      fieldValues: me ? [...me.querySelectorAll('.mf-me-field-value')].map((n) => n.textContent.trim()) : [],
      deviceRows: me ? me.querySelectorAll('[data-device-list="1"] .pd-device-row').length : -1,
      childOrder: kids,
    };
  });
  await shot(page, `mobile-375x812-me.png`, '.mobile-page[data-page="me"]');
  await ctx.close();
  return { form, acct, meRead };
}

async function runDesktop(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`desktop: ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`desktop: pageerror: ${e.message}`));
  await wire(page);
  await page.locator('#settings-btn').click();
  await page.locator('#settings-modal').waitFor({ timeout: 10000 });
  await page.waitForTimeout(700);
  const acct = await accountRead(page);
  // ① 全视口 1280×800（形态证据：桌面设置面 = `#settings-modal`，在真实视口里的样子）
  await shot(page, `desktop-1280x800-settings.png`, null);
  // ② 设置面特写（读数字形复核）
  await shot(page, `desktop-1280x800-settings-modal.png`, '#settings-modal');
  const hasAcc = await page.locator('#settings-content [data-panel-group="account"]').count();
  if (hasAcc) await shot(page, `desktop-1280x800-account-zoom.png`, '#settings-content [data-panel-group="account"]');
  await ctx.close();
  return { acct };
}

const browser = await chromium.launch();
let mobile, desktop;
try {
  mobile = await runMobile(browser);
  desktop = await runDesktop(browser);
} finally { await browser.close(); }

mkdirSync(OUT, { recursive: true });
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ web: WEB, out: OUT, phase: PHASE, mobile, desktop, manifest, consoleErrors }, null, 2));
console.log(`shots → ${OUT}`);
console.log(`mobile form: ${JSON.stringify(mobile.form)}`);
console.log(`mobile account: ${JSON.stringify(mobile.acct)}`);
console.log(`desktop account: ${JSON.stringify(desktop.acct)}`);
console.log(`consoleErrors=${consoleErrors.length}`);
if (consoleErrors.length) { for (const e of consoleErrors) console.log('  ' + e); }
