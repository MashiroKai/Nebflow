// verify-micorb-hide-qa.spec.mjs — INDEPENDENT QA verification for node
// 「验证-隐藏光球配置区」(2026-09-03). All evidence generated fresh by the QA
// node: does NOT reuse the implementer's micorb-settings-hidden.spec.mjs
// assertions, preset combo, or report numbers.
//
// Coverage:
//   V1 隐藏断言四象限 — REAL /js/sidebar.js renderSettings() on the real
//      shell (static server 8188, never 8080), zh-CN/en × dark/light.
//      Asserts: no appearance section card, zero orb-* config elements in
//      DOM (even display:none ones), adjacent sections intact. Also sweeps
//      document.styleSheets for surviving orb-* CSS rules — rule presence
//      is evidence of "code kept" (dead rules, nothing visible).
//   V2a 刷新后自定义配置仍生效 — preset localStorage directly (no UI), page
//      load = the "returning user" path: live orb resolves the custom board.
//   V2b 不经 UI 切换 — orbPresets.saveSaved() (no UI) re-drives the live orb
//      via CHANGE_EVENT → state map override honored.
//
// QA preset combo differs from the implementer's H3 (magma/iceberg here vs
// emerald/sunset there) so a copied implementation could not pass by luck.

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8188; // QA-own port, 8100+ band — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' };

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
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
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

async function loadShellAndRenderSettings(page, locale, colorScheme) {
  await page.emulateMedia({ colorScheme });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await page.evaluate(async () => {
    const sb = await import('/js/sidebar.js'); // the REAL production module
    sb.renderSettings();
  });
}

// orb-* config surface, QA-compiled from orbSettingsUI.js markup — ids and
// classes of the base selector, state-map rows, custom editor, preview, reset.
const ORB_CFG_SELECTORS = [
  '#orb-base-select', '#orb-appearance-body', '#orb-appearance-reset',
  '#orb-custom-toggle', '#orb-custom-body', '#orb-custom-source', '#orb-custom-slots',
  '#orb-preview-slot', '.orb-state-select', '.orb-state-row', '.orb-state-map',
  '.orb-state-map-title', '.orb_slot-row', '.orb-slot-label', '.orb-slot-color',
  '.orb-theme-btn', '.orb-theme-seg', '.orb-swatches',
];
const APPEARANCE_TITLE = { 'zh-CN': '外观', en: 'Appearance' };

for (const locale of ['zh-CN', 'en']) {
  for (const scheme of ['dark', 'light']) {
    test(`V1 [${locale}/${scheme}]: real renderSettings DOM has zero orb config surface`, async ({ page }) => {
      await loadShellAndRenderSettings(page, locale, scheme);
      const dump = await page.evaluate((sels) => {
        const content = document.getElementById('settings-content');
        // ANY node whose id/class contains "orb" counts — stricter than a
        // fixed selector list (catches display:none survivors too).
        const orbNodes = [...content.querySelectorAll('[id*="orb"], [class*="orb"]')]
          .map((e) => `${e.tagName}#${e.id}.${e.className}`);
        const styles = [];
        for (const sheet of document.styleSheets) {
          let rules; try { rules = sheet.cssRules; } catch { continue; }
          for (const r of rules) {
            if (r.selectorText && /orb/i.test(r.selectorText)) styles.push(r.selectorText);
          }
        }
        return {
          orbNodes,
          leakedSelectors: sels.filter((s) => content.querySelector(s)),
          titles: [...content.querySelectorAll('.settings-section-title')].map((e) => e.textContent.trim()),
          sections: content.querySelectorAll('.settings-section').length,
          orbCssRules: styles,
        };
      }, ORB_CFG_SELECTORS);

      expect(dump.leakedSelectors, `[${locale}/${scheme}] orb config selectors must not exist`).toEqual([]);
      expect(dump.orbNodes, `[${locale}/${scheme}] no element with orb id/class may exist (even hidden)`).toEqual([]);
      expect(dump.titles, `[${locale}/${scheme}] appearance card title must be gone`).not.toContain(APPEARANCE_TITLE[locale]);
      expect(dump.sections, `[${locale}/${scheme}] neighboring sections must survive`).toBeGreaterThan(3);
      for (const tt of dump.titles) expect(tt.trim().length, `[${locale}/${scheme}] no empty section titles`).toBeGreaterThan(0);
      // CSS layer: surviving orb-* rules are DEAD (no DOM matches them — the
      // assertions above already guarantee zero matching nodes). Recorded as
      // "code kept" evidence; a non-empty list is expected, not a failure.
      console.log(`[V1 ${locale}/${scheme}] orb-* CSS rules still present (dead, code-kept evidence): ${dump.orbCssRules.length}`);
    });
  }
}

const hexToRgb255 = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
async function targetBoard(page) {
  const pal = await page.evaluate(async () => {
    const micOrb = await import('/js/micOrb.js');
    const r = micOrb.getMicOrb().renderer;
    return { a: r.target.pal.a.slice(), b: r.target.pal.b.slice(), c: r.target.pal.c.slice() };
  });
  for (const s of ['a', 'b', 'c']) pal[s] = pal[s].map((v) => Math.round(v * 255));
  return pal;
}
function expectSlot(slot, rgb, hex, ctx) {
  expect(rgb, `${ctx}: slot ${slot} → ${hex}`).toEqual(hexToRgb255(hex));
}

test('V2a: localStorage-preset custom board survives page load (returning-user path)', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  // QA combo — magma base + frozen→iceberg + custom dark slot-a override.
  // Written straight to localStorage, exactly what an old user's profile
  // holds; NO UI is touched anywhere in this test.
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_micOrb.palette', JSON.stringify({
      base: 'magma',
      map: { 'frozen': 'iceberg' },
      custom: { base: 'magma', dark: { a: '#FF00AA' }, light: {} },
    }));
  });
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  const webglOk = await page.evaluate(async () => (await import('/js/micOrb.js')).getMicOrb().webglOk);
  expect(webglOk, 'headless WebGL must be up for board assertions').toBe(true);

  const board = await targetBoard(page);
  expectSlot('a', board.a, '#FF00AA', 'idle = magma dark + custom slot-a override');
  expectSlot('b', board.b, '#F0483C', 'idle = magma dark');
  expectSlot('c', board.c, '#7A1220', 'idle = magma dark');
});

test('V2b: UI-less saveSaved re-drives the live orb via CHANGE_EVENT (state map honored)', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });

  await page.evaluate(async () => {
    const presets = await import('/js/orbPresets.js');
    presets.saveSaved({ base: 'dawn', map: { 'processing': 'neon' }, custom: null });
  });
  let board = await targetBoard(page);
  expectSlot('a', board.a, '#FFB3A0', 'idle = dawn dark (map has no idle)');
  expectSlot('b', board.b, '#8FA0F5', 'idle = dawn dark');
  expectSlot('c', board.c, '#5C3A78', 'idle = dawn dark');

  await page.evaluate(async () => (await import('/js/micOrb.js')).getMicOrb().apply('processing'));
  board = await targetBoard(page);
  expectSlot('a', board.a, '#3DF2F2', 'processing → neon dark via saved map');
  expectSlot('b', board.b, '#F05AD8', 'processing → neon dark via saved map');
  expectSlot('c', board.c, '#1A1A66', 'processing → neon dark via saved map');
});
