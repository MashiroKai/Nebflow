// micorb-settings-hidden.spec.mjs — settings-page gating spec for the micOrb
// appearance section (author ruling 2026-09-03: the orb is preset-driven, the
// settings UI entry is hidden; code and config-reading logic stay intact).
//
// Verifies (read-only w.r.t. every other spec):
//   H1 DOM 断言 — the REAL /js/sidebar.js renderSettings() (full shell +
//      mocked WS) contains no settings.appearance section card and no
//      orb-* config element of any kind, in zh-CN AND en. Adjacent sections
//      (runtime/providers) still render — the whole card is gated off, no
//      empty "外观" shell left behind.
//   H2 代码保留证据 — importing the REAL /js/orbSettingsUI.js and calling
//      renderAppearanceSection() still returns the full markup (same source
//      the micorb-presets T4 harness mounts): hiding ≠ deleting.
//   H3 自定义配置仍生效 — without touching any UI, orbPresets.saveSaved()
//      with a custom selection drives the shell's LIVE orb through
//      CHANGE_EVENT → loadSaved → renderer boards (hidden entry ≠ dropped
//      config; existing user selections keep working).
//
// Self-contained: static server on 127.0.0.1:8177 (never 8080) serving
// src/main/resources/web only; WS/API mocked in-page; server closed at end.

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8177; // fixed, 8100+ per task discipline — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

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

test.beforeEach(async ({ page }) => {
  // Dark scheme (factory boards are dark-first) + token/locale init.
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  // Mock backend: API returns {} and the app WS gets the minimal handshake —
  // same shape as tests/tmp-usage-display-verify.mjs (no real server touched).
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });
});

/** Load the real shell, then render settings via the REAL sidebar.js module. */
async function renderRealSettings(page, locale) {
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await page.evaluate(async () => {
    const sb = await import('/js/sidebar.js'); // production module, real path
    sb.renderSettings();
  });
}

// Every orb-config element class/id the appearance section can produce
// (from orbSettingsUI.js: base select, 9-state map rows, custom board editor,
// live preview, reset button).
const ORB_SELECTORS = [
  '#orb-base-select',
  '#orb-appearance-body',
  '#orb-appearance-reset',
  '#orb-custom-toggle',
  '#orb-custom-body',
  '#orb-custom-source',
  '#orb-custom-slots',
  '#orb-preview-slot',
  '.orb-state-select',
  '.orb-state-row',
  '.orb-state-map',
  '.orb-state-map-title',
  '.orb-slot-row',
  '.orb-slot-label',
  '.orb-slot-color',
  '.orb-theme-btn',
  '.orb-theme-seg',
  '.orb-swatches',
];

const APPEARANCE_TITLE = { 'zh-CN': '外观', en: 'Appearance' };

test('H1: settings DOM has no orb config entry (zh-CN + en), no empty shell card', async ({ page }) => {
  for (const locale of ['zh-CN', 'en']) {
    await renderRealSettings(page, locale);
    const dump = await page.evaluate((sels) => {
      const content = document.getElementById('settings-content');
      return {
        leaked: sels.filter((s) => content.querySelector(s)),
        titles: [...content.querySelectorAll('.settings-section-title')].map((e) => e.textContent.trim()),
        sections: content.querySelectorAll('.settings-section').length,
        htmlLen: content.innerHTML.length,
      };
    }, ORB_SELECTORS);

    expect(dump.leaked, `[${locale}] orb config elements leaked into settings DOM`).toEqual([]);
    expect(dump.titles, `[${locale}] appearance section card must be gone entirely`).not.toContain(APPEARANCE_TITLE[locale]);
    // Gating, not amputation: the neighboring sections still render.
    expect(dump.sections, `[${locale}] settings page must keep its other sections`).toBeGreaterThan(3);
    for (const tt of dump.titles) expect(tt.length, `[${locale}] section titles must be non-empty`).toBeGreaterThan(0);
  }
});

test('H2: orbSettingsUI module intact — renderAppearanceSection still emits full markup', async ({ page }) => {
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  const markup = await page.evaluate(async () => {
    const ui = await import('/js/orbSettingsUI.js'); // same source the T4 harness mounts
    return ui.renderAppearanceSection();
  });
  expect(markup.length, 'module must still render non-empty markup').toBeGreaterThan(500);
  for (const token of ['id="orb-base-select"', 'orb-state-select', 'id="orb-appearance-reset"', 'id="orb-preview-slot"']) {
    expect(markup, `markup must still contain ${token}`).toContain(token);
  }
});

/* ---- H3 helpers: read the live orb's renderer targets ------------------- */

const hexToRgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
async function targetBoard(page) {
  const pal = await page.evaluate(async () => {
    const micOrb = await import('/js/micOrb.js');
    const r = micOrb.getMicOrb().renderer;
    return { a: r.target.pal.a.slice(), b: r.target.pal.b.slice(), c: r.target.pal.c.slice() };
  });
  for (const slot of ['a', 'b', 'c']) {
    pal[slot] = pal[slot].map((v) => Math.round(v * 255));
  }
  return pal;
}
function expectSlot(slot, rgb, hex, ctx) {
  const want = hexToRgb(hex).map((v) => Math.round(v * 255));
  expect(rgb, `${ctx} slot ${slot} → ${hex}`).toEqual(want);
}

test('H3: hidden entry ≠ dropped config — saveSaved custom selection drives the live orb', async ({ page }) => {
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  // Live orb is the production MicOrb on #mic-canvas (WebGL must be up,
  // same requirement as micorb-presets T1 in this environment).
  const webglOk = await page.evaluate(async () => (await import('/js/micOrb.js')).getMicOrb().webglOk);
  expect(webglOk, 'headless Chromium must provide WebGL (SwiftShader)').toBe(true);

  // Preset a custom selection WITHOUT any UI (what a returning user's
  // localStorage would hold): emerald base, custom dark slot a, listening→sunset.
  await page.evaluate(async () => {
    const presets = await import('/js/orbPresets.js');
    presets.saveSaved({
      base: 'emerald',
      map: { listening: 'sunset' },
      custom: { base: 'emerald', dark: { a: '#40E0FF' }, light: {} },
    });
  });
  // CHANGE_EVENT → loadSaved → board re-resolve (target is set synchronously).
  let board = await targetBoard(page);
  expectSlot('a', board.a, '#40E0FF', 'idle w/ custom dark override');
  expectSlot('b', board.b, '#B8E05A', 'idle w/ emerald base');
  expectSlot('c', board.c, '#0A5230', 'idle w/ emerald base');

  // Mapped states keep the preset language (custom overrides the base only).
  await page.evaluate(async () => (await import('/js/micOrb.js')).getMicOrb().apply('listening'));
  board = await targetBoard(page);
  expectSlot('a', board.a, '#FFA26B', 'listening → sunset map');
  expectSlot('b', board.b, '#F060A0', 'listening → sunset map');
  expectSlot('c', board.c, '#7A2050', 'listening → sunset map');
});
