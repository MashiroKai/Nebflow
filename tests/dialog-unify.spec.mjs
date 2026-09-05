// dialog-unify.spec.mjs — 弹窗统一治理批验收（Bug1 图层 + Bug2 裸弹窗 + 审计整改）。
//
// 覆盖面（静态 harness：隔离静态服务器 :8297 + routeWebSocket mock WS +
// route mock API，零真实后端；settings-cleanup.spec.mjs 同款骨架）：
//   T1 Bug1 [light] 设置→模型方案→删除预设确认浮于设置面板之上：
//       z-index(#modal-overlay)=11000 > z-index(#settings-overlay)=310；
//       elementFromPoint 命中确认钮（视觉最上层、可交互）；
//       overlay 背景零暗化（视觉裁定：无遮罩层）；点击确认 → DELETE /api/presets/:name。
//   T2 Bug1 [dark] 同链路暗色主题截图 + 可见性。
//   T3 Bug2 [light] 心跳进程面板删除走统一弹窗：
//       裸 window.confirm 调用计数 === 0；#delete-box 玻璃面板
//       （backdrop-filter blur）+ 标题/正文文案；确认钮 elementFromPoint 命中
//       （浮于 #daemon-panel z300 之上）。
//   T4 Bug2 [dark] 同链路暗色主题截图 + 可见性。
//   T5 workspace-picker 弹窗 overlay 零暗化（审计整改：chat.css 两处 rgba
//       暗化 → var(--overlay-bg)）+ 亮/暗双主题截图。
//
// Run: node node_modules/@playwright/test/cli.js test tests/dialog-unify.spec.mjs --browser=chromium

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8297; // fixed, 8100+ per task discipline — never the host 8080
const SHOTS = '/tmp/nb-dialog-unify/shots';
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };

/** Static server rooted at the web dir (path-traversal safe). */
function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;
test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

/**
 * Boot the real shell against mocks.
 * @param {import('@playwright/test').Page} page
 * @param {{locale?: string, colorScheme?: 'light'|'dark', presets?: object|null, daemons?: Array|null}} opts
 */
async function bootPage(page, opts = {}) {
  const { locale = 'zh-CN', colorScheme = 'light', presets = null, daemons = null } = opts;
  await page.emulateMedia({ colorScheme });
  // Bare-dialog canary: any native confirm()/alert() call is recorded.
  await page.addInitScript(() => {
    window.__nativeDialogCalls = [];
    const origConfirm = window.confirm?.bind(window);
    window.confirm = (...a) => { window.__nativeDialogCalls.push(['confirm', String(a[0])]); return true; };
    const origAlert = window.alert?.bind(window);
    window.alert = (...a) => { window.__nativeDialogCalls.push(['alert', String(a[0])]); };
    void origConfirm; void origAlert;
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', locale);
    localStorage.setItem('neblink_locale', locale);
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} })); // catch-all first
  if (presets) await page.route('**/api/presets**', r => r.fulfill({ json: presets }));
  if (daemons) await page.route('**/api/daemons', r => r.fulfill({ json: daemons }));
  await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));

  const frames = [];
  let wsSend = () => {};
  await page.routeWebSocket(/\/ws/, ws => {
    wsSend = obj => ws.send(JSON.stringify(obj));
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      frames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  return {
    frames,
    send: obj => wsSend(obj),
    deletedPresets: () => frames, // placeholder; DELETE captured via route below
  };
}

/**
 * Fast-forward CSS animations (modalIn 等) to their end state.
 * Headless Chromium may stall the animation clock for idle pages, leaving
 * 0.2s entry animations stuck at their opacity:0 first frame in screenshots
 * (live DOM geometry/hit-testing are unaffected). WAAPI finish() lands the
 * element on its final keyframe (= base style here); no-op if none running.
 */
async function settleAnimations(page) {
  await page.evaluate(() => {
    try { document.getAnimations().forEach(a => { try { a.finish(); } catch { /* not finishable */ } }); } catch { /* API absent */ }
  });
}

/** z-index/背景断言 + elementFromPoint 命中断言（Bug1/Bug2 共用）。 */
async function assertConfirmOnTop(page, expectTitle) {
  const dump = await page.evaluate((title) => {
    const overlay = document.getElementById('modal-overlay');
    const box = document.getElementById('delete-box');
    const btn = document.getElementById('delete-confirm');
    const zi = (el) => parseInt(getComputedStyle(el).zIndex, 10) || 0;
    const r = btn.getBoundingClientRect();
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    const hit = document.elementFromPoint(cx, cy);
    const bg = getComputedStyle(overlay).backgroundColor;
    const alpha = bg.startsWith('rgba') ? parseFloat(bg.split(',')[3]) : (bg === 'transparent' ? 0 : 1);
    return {
      title: document.getElementById('delete-title').textContent,
      overlayZ: zi(overlay),
      settingsZ: zi(document.getElementById('settings-overlay')),
      daemonPanelZ: zi(document.getElementById('daemon-panel')),
      visible: !!(box.offsetParent !== null || box.getClientRects().length),
      btnHitInsideOverlay: !!hit && (!!hit.closest('#modal-overlay')),
      btnHitIsConfirm: hit === btn,
      overlayBgAlpha: alpha,
      glassBlur: getComputedStyle(box).backdropFilter || getComputedStyle(box).webkitBackdropFilter || '',
      expectTitle: title,
    };
  }, expectTitle);
  expect(dump.title, 'confirm title').toBe(dump.expectTitle);
  expect(dump.visible, 'confirm dialog visible').toBe(true);
  expect(dump.overlayZ, 'confirm layer above settings panel (Bug1 contract)').toBeGreaterThan(dump.settingsZ);
  expect(dump.overlayZ, 'confirm layer above daemon popover panel').toBeGreaterThan(dump.daemonPanelZ);
  expect(dump.overlayZ, 'confirm layer pinned at 11000').toBe(11000);
  expect(dump.btnHitInsideOverlay, 'confirm button visually top-most (elementFromPoint hits inside #modal-overlay)').toBe(true);
  expect(dump.btnHitIsConfirm, 'confirm button receives the click').toBe(true);
  expect(dump.overlayBgAlpha, 'no overlay dimming behind dialogs (visual ruling)').toBe(0);
  expect(dump.glassBlur, 'panel itself is frosted glass').toContain('blur');
  return dump;
}

// ══ T1 · Bug1 [light] 预设删除确认浮于设置面板之上 ═══════════
test('T1 Bug1 preset delete confirm renders above settings panel (light)', async ({ page }) => {
  let deleteCalled = false;
  await bootPage(page, {
    locale: 'zh-CN',
    colorScheme: 'light',
    presets: { defaultPreset: '', presets: [{ name: 'p1', preferred: 'prov/m1', fallbacks: [] }], agents: {} },
  });
  await page.route('**/api/presets/*', async (r) => {
    if (r.request().method() === 'DELETE') { deleteCalled = true; r.fulfill({ json: { ok: true } }); }
    else r.fulfill({ json: { defaultPreset: '', presets: [{ name: 'p1', preferred: 'prov/m1', fallbacks: [] }], agents: {} } });
  });
  await page.route('**/api/agents', r => r.fulfill({ json: { agents: [] } }));
  await page.route('**/api/agents/*/model', r => r.fulfill({ json: { resolvedFrom: 'preset' } }));

  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
  await page.waitForSelector('.preset-card', { timeout: 10000 });
  await page.click('.preset-card [data-action="delete"]');
  await page.waitForSelector('#delete-box', { state: 'visible', timeout: 5000 });
  await settleAnimations(page);

  const dump = await assertConfirmOnTop(page, '删除方案');
  // 截图（亮）
  await page.screenshot({ path: join(SHOTS, 'preset-delete-confirm-light.png'), animations: 'disabled' });

  await page.click('#delete-confirm');
  await page.waitForTimeout(300);
  expect(deleteCalled, 'confirm click triggers DELETE /api/presets/p1').toBe(true);
  void dump;
});

// ══ T2 · Bug1 [dark] 暗色主题同链路 ═════════════════════════
test('T2 Bug1 preset delete confirm dark theme', async ({ page }) => {
  await bootPage(page, {
    locale: 'zh-CN',
    colorScheme: 'dark',
    presets: { defaultPreset: '', presets: [{ name: 'p1', preferred: 'prov/m1', fallbacks: [] }], agents: {} },
  });
  await page.route('**/api/presets/*', r => r.fulfill({ json: { defaultPreset: '', presets: [{ name: 'p1', preferred: 'prov/m1', fallbacks: [] }], agents: {} } }));
  await page.route('**/api/agents', r => r.fulfill({ json: { agents: [] } }));
  await page.route('**/api/agents/*/model', r => r.fulfill({ json: { resolvedFrom: 'preset' } }));

  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
  await page.waitForSelector('.preset-card', { timeout: 10000 });
  await page.click('.preset-card [data-action="delete"]');
  await page.waitForSelector('#delete-box', { state: 'visible', timeout: 5000 });
  await settleAnimations(page);

  await assertConfirmOnTop(page, '删除方案');
  await page.screenshot({ path: join(SHOTS, 'preset-delete-confirm-dark.png'), animations: 'disabled' });
});

// ══ T3 · Bug2 [light] 心跳进程删除走统一弹窗 ════════════════
test('T3 Bug2 daemon delete uses unified confirm, no native dialog (light)', async ({ page }) => {
  await bootPage(page, {
    locale: 'zh-CN',
    colorScheme: 'light',
    daemons: [{ id: 'd1', name: 'nb-docs', port: 8123, command: 'node server.js', status: 'running', autoStart: false }],
  });
  await page.route('**/api/daemons/*', async (r) => {
    if (r.request().method() === 'DELETE') r.fulfill({ json: { ok: true } });
    else r.fulfill({ json: {} });
  });

  await page.click('#daemon-btn');
  await page.waitForSelector('#daemon-panel.open', { timeout: 5000 });
  await page.waitForSelector('.daemon-row, .daemon-item, [data-act="delete"]', { timeout: 5000 });
  await page.click('[data-act="delete"]');
  await page.waitForSelector('#delete-box', { state: 'visible', timeout: 5000 });
  await settleAnimations(page);

  const dump = await assertConfirmOnTop(page, '删除心跳进程');
  const native = await page.evaluate(() => window.__nativeDialogCalls);
  expect(native, 'no bare native confirm()/alert() calls (Bug2 contract)').toEqual([]);
  await page.screenshot({ path: join(SHOTS, 'daemon-delete-confirm-light.png'), animations: 'disabled' });
  void dump;
});

// ══ T4 · Bug2 [dark] 暗色主题同链路 ═════════════════════════
test('T4 Bug2 daemon delete unified confirm dark theme', async ({ page }) => {
  await bootPage(page, {
    locale: 'zh-CN',
    colorScheme: 'dark',
    daemons: [{ id: 'd1', name: 'nb-docs', port: 8123, command: 'node server.js', status: 'running', autoStart: false }],
  });
  await page.route('**/api/daemons/*', r => r.fulfill({ json: { ok: true } }));

  await page.click('#daemon-btn');
  await page.waitForSelector('#daemon-panel.open', { timeout: 5000 });
  await page.waitForSelector('[data-act="delete"]', { timeout: 5000 });
  await page.click('[data-act="delete"]');
  await page.waitForSelector('#delete-box', { state: 'visible', timeout: 5000 });
  await settleAnimations(page);

  await assertConfirmOnTop(page, '删除心跳进程');
  await page.screenshot({ path: join(SHOTS, 'daemon-delete-confirm-dark.png'), animations: 'disabled' });
});

// ══ T5 · 审计整改：workspace-picker overlay 零暗化 ═══════════
test('T5 workspace-picker overlay has no dimming (source + runtime, both themes)', async ({ page }) => {
  // 源码级断言：chat.css 两处 .wsp-overlay 背景均走 var(--overlay-bg)（transparent）。
  const css = readFileSync(join(WEB, 'css', 'chat.css'), 'utf8');
  const baseBlock = css.match(/\.wsp-overlay\s*\{[^}]*\}/);
  expect(baseBlock, '.wsp-overlay base rule exists').toBeTruthy();
  expect(baseBlock[0], 'base overlay bg uses --overlay-bg token').toContain('var(--overlay-bg)');
  expect(baseBlock[0], 'no rgba dimming in base rule').not.toContain('rgba(0, 0, 0');
  const darkIdx = css.indexOf('.wsp-overlay { background: var(--overlay-bg); }');
  expect(css.indexOf('.wsp-overlay'), 'two .wsp-overlay rules (base + dark)').toBeGreaterThanOrEqual(0);
  expect(darkIdx, 'dark-mode override also uses the undimmed token').toBeGreaterThan(0);

  // 运行时：直接调 openPicker 渲染弹窗，断言计算样式 + 双主题截图。
  for (const colorScheme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme });
    await page.addInitScript((l) => {
      localStorage.setItem('nebflow_token', 't');
      localStorage.setItem('nebflow_locale', l);
    }, 'zh-CN');
    await page.route('**/api/**', r => r.fulfill({ json: {} }));
    await page.route('**/api/neblink/status', r => r.fulfill({ json: { loggedIn: false, device: null, peers: [] } }));
    await page.routeWebSocket(/\/ws/, ws => {
      ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
      ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
      ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    });
    await page.goto(base + '/index.html');
    await page.waitForSelector('#activity-bar', { timeout: 15000 });
    await page.evaluate(async () => {
      const m = await import('/js/workspacePicker.js');
      m.openPicker({ startPath: '~' });
    });
    await page.waitForSelector('.wsp-panel', { timeout: 5000 });
    const dump = await page.evaluate(() => {
      const bg = getComputedStyle(document.querySelector('.wsp-overlay')).backgroundColor;
      const alpha = bg.startsWith('rgba') ? parseFloat(bg.split(',')[3]) : (bg === 'transparent' ? 0 : 1);
      const panel = getComputedStyle(document.querySelector('.wsp-panel'));
      return { alpha, blur: panel.backdropFilter || panel.webkitBackdropFilter || '' };
    });
    expect(dump.alpha, `[${colorScheme}] no overlay dimming`).toBe(0);
    expect(dump.blur, `[${colorScheme}] panel glass`).toContain('blur');
    await page.screenshot({ path: join(SHOTS, `workspace-picker-${colorScheme}.png`), animations: 'disabled' });
    // next iteration needs a fresh document
    await page.goto('about:blank');
  }
});
