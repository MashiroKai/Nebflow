#!/usr/bin/env node
// profile-multidevice-video.mjs — 逐设备独立退出的**动态**证据（profile-multidevice 批）。
//
// 载体与边界（照实声明，勿读成真机证据）：
//   · 零实例、零生产数据、零受保护端口：web 根 = 本地文件直读，全部 REST/WS 输入走
//     Playwright 路由桩，页面只 goto `http://localhost:1/`（不会有任何监听）。
//   · 录的是**产物代码的真实运行**（profileDevices.js / meFace.js / settingsIdentity.js /
//     neblink.js 逐字执行），不是手绘动画；桩只替换服务端读数。
//   · 未证明：真机跨设备行为（对端设备的真实凭据拆除需要收端配合的命令通道，本批非
//     目标 —— 网关对已配对设备诚实回 409，见 proposal 的 non-goals）。
//
// 场景（两段，各一段视频）：
//   手机 375×812（触屏）  ·  桌面 1280×800
//     ① 浏览设备列表（本机 + 两个对端；名称 / 平台 / 状态 / 最近活动 / 本机标记）
//     ② 对**对端设备**（KAI）点退出 → 走 `/api/neblink/devices/dev-kai/sign-out`，
//        该行给出诚实读数（409 remote-sign-out-unsupported → 行内提示），
//        其余每行读数**逐字不变**
//     ③ 对**本机行**点退出 → 走既有唯一登出链（`POST /api/neblink/auth/end-session`），
//        本机行消失（生产读数回 loggedIn:false / device:null），对端行仍在且读数不变
//
// 判据（任一条不成立 ⇒ 退出码 1，且进 manifest）：
//   A 对端行退出 = **只**打该行自己的 deviceId（请求体/路径里不出现其它设备 id），
//     且**不发** end-session；
//   B 本机行退出 = 发 end-session，且不新增任何 `/devices/*/sign-out` 请求；
//   C 两段视频都落盘；全程零 console error（任何一条进 manifest 并置退出码 1）。
//
// Run: node tests/profile-multidevice-video.mjs
//      VIDEO_OUT=<abs dir> node tests/profile-multidevice-video.mjs

import { chromium } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = process.env.VIDEO_WEB_ROOT || join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.VIDEO_OUT || '/private/tmp/stand-profile-delivery/profile-multidevice/video';
const RAW = join(OUT, 'raw');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const PEER_ID = 'dev-kai';
const OTHER_PEER_ID = 'dev-old';

const manifest = { out: OUT, runs: [], consoleErrors: [], networkNotes: [] };
/** 入口/环境诊断（**禁静默**：任何回退都留痕进 manifest）。 */
const diag = [];
const runs = [];

/** 服务端读数桩（逐字对齐 /api/neblink/status 的真实形状）。 */
function stateStub() {
  return {
    status: {
      loggedIn: true,
      device: { id: 'dev-self', deviceId: 'dev-self', deviceName: 'Mashiro-Mac', platform: 'macos', displayName: 'Fixture User', email: 'fixture@example.test' },
      peers: [
        { deviceId: PEER_ID, deviceName: 'KAI', platform: 'windows', lastSeen: Date.now() - 120_000, online: true, directOnline: true },
        { deviceId: OTHER_PEER_ID, deviceName: 'OldBox', platform: 'linux', lastSeen: Date.now() - 3 * 86_400_000, online: false, directOnline: false },
      ],
    },
    /** 已发出的 /api 请求（方法 + 路径 + body），逐条留痕 —— 隔离判据的原始材料。 */
    requests: [],
  };
}

async function boot(page, state, tag) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    // 预约窗不开真窗（end-session 链的手势内预约仍发生，但不导航走）。
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return { location: { href: '' }, close() {} }; };
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  });
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    // 浏览器把「资源加载失败（404/409）」也报成 console error —— 那两笔是本夹具的
    // **预期**读数（静态根缺件 + 设备域退出的诚实 409），不是脚本错误。真错误 =
    // pageerror 或非资源类的 console error；资源类逐条进 manifest 的 networkNotes。
    const text = m.text();
    if (/Failed to load resource/i.test(text)) { manifest.networkNotes.push(`${tag}: ${text}`); return; }
    manifest.consoleErrors.push(`${tag}: ${text}`);
  });
  page.on('pageerror', (e) => manifest.consoleErrors.push(`${tag}: pageerror: ${e.message}`));
  page.on('response', (r) => { if (r.status() >= 400) manifest.networkNotes.push(`${tag}: http${r.status()} ${r.url()}`); });
  await page.route('**/*', (route) => {
    const req = route.request();
    const url = new URL(req.url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      state.requests.push({ method: req.method(), path: p, body: req.postData() || '' });
      if (p === '/api/neblink/status') return route.fulfill({ json: state.status });
      if (p === '/api/users/search') {
        return route.fulfill({
          json: { found: true, user: { userId: 'u-self', username: 'Mashiro', display_name: 'Fixture User', avatar: null }, relation_status: 'self' },
        });
      }
      // 设备域退出：对已配对设备 = 显式无能力（诚实读数，不假装已退出）。
      if (/^\/api\/neblink\/devices\/[^/]+\/sign-out$/.test(p)) {
        return route.fulfill({ status: 409, json: { ok: false, code: 'remote-sign-out-unsupported', error: 'remote device sign-out is not supported' } });
      }
      if (p === '/api/neblink/auth/end-session') {
        // 本机退出落定 ⇒ 生产读数随后回到未登录（device:null）；对端行不受影响。
        // 必须给**非空** endSessionUrl：空串进的是 `openEndSessionHandoff` 的
        // 「endSessionUrl missing」错误支（本地拆除**不执行**，见该函数 808-812），
        // 那正是「登出没发生就不该表现成已登出」的诚实口径 —— 桩要证明的是成功支。
        state.status = { ...state.status, loggedIn: false, device: null };
        return route.fulfill({ json: { endSessionUrl: 'https://example.test/auth/end-session' } });
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

/** 清单读数（两棵树同一判据口径）。 */
async function readings(page, rootSel) {
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
      notice: host?.querySelector('.pd-device-notice')?.hidden === false
        ? (host.querySelector('.pd-device-notice')?.textContent?.trim() || '') : '',
    };
  }, rootSel);
}

/** 某一行的读数（对端行的「逐字不变」判据）。 */
function rowOf(snap, id) { return snap.items.find((r) => r.id === id) || null; }

/** 逐设备退出两拍 + 隔离判据（手机 / 桌面共用；rootSel = 清单所在面）。 */
async function signOutRun(page, state, rootSel, log) {
  // ① 浏览设备列表
  await page.locator(`${rootSel} [data-device-list="1"]`).scrollIntoViewIfNeeded();
  await page.waitForTimeout(1200);
  const before = await readings(page, rootSel);
  log.push({ step: 'list', readings: before });

  // ② 对端行退出（KAI）
  const peerKeysBefore = state.requests.length;
  await page.locator(`${rootSel} .pd-device-row[data-device-id="${PEER_ID}"] [data-device-signout]`).click();
  await page.waitForSelector(`${rootSel} .pd-device-notice:not([hidden])`, { timeout: 8000 });
  await page.waitForTimeout(1400);
  const afterPeer = await readings(page, rootSel);
  const peerReqs = state.requests.slice(peerKeysBefore);
  log.push({ step: 'peer-signout', readings: afterPeer, requests: peerReqs });

  // ③ 本机行退出（既有唯一登出链）
  const selfKeysBefore = state.requests.length;
  await page.locator(`${rootSel} .pd-device-row[data-device-current="1"] [data-device-signout]`).click();
  await page.waitForTimeout(2200);
  const afterSelf = await readings(page, rootSel);
  const selfReqs = state.requests.slice(selfKeysBefore);
  log.push({ step: 'self-signout', readings: afterSelf, requests: selfReqs });

  // ── 判据 ────────────────────────────────────────────────────────────────
  const checks = [];
  const push = (name, ok, detail) => checks.push({ name, ok, detail });

  const peerSignOut = peerReqs.filter((r) => /\/devices\/[^/]+\/sign-out$/.test(r.path));
  push('A1 peer row issues exactly one device-scoped request', peerSignOut.length === 1, JSON.stringify(peerSignOut));
  push('A2 that request carries only its own deviceId',
    peerSignOut.length === 1 && peerSignOut[0].path === `/api/neblink/devices/${PEER_ID}/sign-out`
    && !peerSignOut[0].path.includes(OTHER_PEER_ID) && !peerSignOut[0].body.includes(OTHER_PEER_ID),
    JSON.stringify(peerSignOut));
  push('A3 peer sign-out never triggers the end-session chain',
    peerReqs.every((r) => r.path !== '/api/neblink/auth/end-session'), JSON.stringify(peerReqs));
  push('A4 peer row shows an honest refusal (no fabricated success)',
    afterPeer.notice.length > 0, afterPeer.notice);

  const selfEnd = selfReqs.filter((r) => r.path === '/api/neblink/auth/end-session');
  push('B1 current row triggers the existing end-session chain', selfEnd.length === 1, JSON.stringify(selfReqs));
  push('B2 current row issues no device-scoped sign-out request',
    selfReqs.every((r) => !/\/devices\/[^/]+\/sign-out$/.test(r.path)), JSON.stringify(selfReqs));

  const peerBefore = rowOf(before, PEER_ID);
  const peerAfter = rowOf(afterSelf, PEER_ID);
  const otherAfter = rowOf(afterSelf, OTHER_PEER_ID);
  push('C1 acting on one device never signs out another (peer rows survive)',
    !!peerAfter && !!otherAfter, JSON.stringify(afterSelf.items.map((i) => i.id)));
  push('C2 the untouched peer rows keep their readings verbatim',
    !!peerAfter && peerAfter.meta === peerBefore?.meta && peerAfter.name === peerBefore?.name,
    `before=${peerBefore?.meta} after=${peerAfter?.meta}`);
  push('C3 the current device is the only thing that changed',
    afterSelf.items.every((r) => r.current !== '1'), JSON.stringify(afterSelf.items.map((r) => r.current)));

  return { before, afterPeer, afterSelf, checks };
}

async function run(kind, label) {
  const mobile = kind === 'mobile';
  const viewport = mobile ? { width: 375, height: 812 } : { width: 1280, height: 800 };
  const state = stateStub();
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport, hasTouch: mobile, isMobile: mobile, colorScheme: 'light',
    recordVideo: { dir: RAW, size: viewport },
  });
  const page = await ctx.newPage();
  const video = page.video();
  const log = [];
  try {
    await boot(page, state, label);
    if (mobile) {
      await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
      await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
      await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
      await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="me"]')?.classList.contains('live'), null, { timeout: 15000 });
      await page.waitForTimeout(700);
    } else {
      await page.waitForSelector('#activity-avatar', { timeout: 20000 });
      await page.waitForTimeout(1200);
      await page.locator('#activity-avatar').click();               // 账号菜单（真实入口）
      let opened = 0;
      try {
        await page.locator('.fm-context-menu button').first().waitFor({ timeout: 4000 });
        opened = await page.locator('.fm-context-menu button').count();
      } catch { opened = 0; }
      let entry = 'activity-avatar-account-menu';
      if (opened) {
        // 菜单两枚键：① 个人主页（`activity.profile`）② 退出登录（`neblink.logout`）。
        // 取**第一枚**并按文案自证（禁按猜的字符串点——文案单点在 locales）。
        const labels = await page.locator('.fm-context-menu button').allTextContents();
        if (!String(labels[0] || '').includes('个人主页')) {
          throw new Error(`account menu item 1 is not the profile entry: ${JSON.stringify(labels)}`);
        }
        entry = `activity-avatar-account-menu[${labels.join('|')}]`;
        await page.locator('.fm-context-menu button').first().click();
      } else {
        // 菜单未开（记录诊断，禁静默）：回退到与既有点名脚本同一入口 —— 模块导出。
        entry = 'module-export-fallback';
        diag.push({ tag: label, note: 'account menu did not open', detail: await page.evaluate(() => {
          const a = document.getElementById('activity-avatar');
          return { avatar: !!a, bbox: a ? a.getBoundingClientRect().toJSON() : null, menus: document.querySelectorAll('.fm-context-menu').length };
        }) });
        await page.evaluate(async () => { const m = await import('/js/settingsIdentity.js'); m.openProfileFace(); });
      }
      await page.waitForSelector('#settings-profile-overlay:not([hidden])', { timeout: 10000 });
      await page.waitForTimeout(700);
      runs.push({ label, entry, menuItems: opened });
    }
    const root = mobile ? '.mobile-page[data-page="me"]' : '#settings-profile-overlay';
    const result = await signOutRun(page, state, root, log);
    log.push({
      step: 'request-trace',
      all: state.requests.map((r) => `${r.method} ${r.path}`),
      openCalls: await page.evaluate(() => (Array.isArray(window.__openCalls) ? window.__openCalls.length : -1)),
    });
    return { kind, label, viewport, log, ...result };
  } finally {
    await ctx.close();
    if (video) await video.saveAs(join(RAW, `${label}.webm`));
    await browser.close();
  }
}

mkdirSync(RAW, { recursive: true });
for (const [kind, label] of [['mobile', 'mobile-375x812'], ['desktop', 'desktop-1280x800']]) {
  const r = await run(kind, label);
  manifest.runs.push(r);
  console.log(`\n── ${label} (${r.viewport.width}×${r.viewport.height}) ──`);
  for (const step of r.log) {
    if (step.step === 'list') console.log(`  [browse] rows=${step.readings.rows} :: ${step.readings.items.map((i) => `${i.name}(${i.online}${i.current === '1' ? ',current' : ''})`).join(' / ')}`);
    if (step.step === 'peer-signout') console.log(`  [peer sign-out] requests=${step.requests.length} notice="${step.readings.notice}"`);
    if (step.step === 'self-signout') console.log(`  [self sign-out] requests=${step.requests.length} rowsAfter=${step.readings.rows}`);
    if (step.step === 'request-trace') console.log(`  [trace] ${step.all.join(' | ')}`);
  }
  for (const c of r.checks) console.log(`  ${c.ok ? 'PASS' : 'FAIL'} ${c.name}  ${c.ok ? '' : ':: ' + c.detail}`);
}

mkdirSync(OUT, { recursive: true });
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ ...manifest, entryRuns: runs, diag }, null, 2));
const failed = manifest.runs.flatMap((r) => r.checks.filter((c) => !c.ok));
const total = manifest.runs.flatMap((r) => r.checks).length;
console.log(`\nshares -> ${OUT}`);
console.log(`checks ${total - failed.length}/${total} pass; consoleErrors=${manifest.consoleErrors.length}; networkNotes=${manifest.networkNotes.length}`);
for (const e of manifest.networkNotes) console.log('  network-note ' + e);
for (const e of manifest.consoleErrors) console.log('  CONSOLE ERROR ' + e);
process.exitCode = failed.length || manifest.consoleErrors.length ? 1 : 0;
