// micorb-presets.spec.mjs — regression spec for the micOrb palette preset
// system (v8.3.0; design 20260902_micorb-presets-design.md, author rulings
// 2026-09-02 20:59).
//
// Verifies:
//   T1 默认基调/状态映射 — factory state = Ocean base + the 2026-09-03
//      default map, hue zeroed (no double-shift of mapped boards).
//   T2 亮暗切换 — applyTheme swaps the dark/light board via setPalette and
//      NEVER re-triggers transitionPulse (v8.2.1 F5).
//   T3 持久化 — localStorage selection (base/map) drives boards; corrupt or
//      unknown data falls back to defaults without throwing.
//   T4 设置面板 — the real appearance section (orbSettingsUI.js) edits
//      base / state map / custom slots, persists, live-updates the orb via
//      CHANGE_EVENT, and 重置 clears everything.
//   T5 预乘取证 — after a wobble soak, every sampled pixel outside the body
//      silhouette (r ≥ 0.74 canvas radii) is the pure page background in the
//      COMPOSITED screenshot (2c0758a8 radial-profile method, compositor
//      level: premultiplied a=0 texels composite to exactly the backdrop).
//   T6 9态矩阵截图 — dark + light matrices written to the docs dir; the two
//      error states are mutually distinguishable and distinct from idle.
//
// Self-contained: spins up its own static server on an ephemeral port serving
// src/main/resources/web (never touches the running Nebflow instance).

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const DOCS = join(process.env.HOME, '.nebflow', 'docs', 'Nebflow');
const SHOT_STEM = '20260903_micorb-iceberg-9states';
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };

/** Static server for the web dir + harness fixture pages. */
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/' || p === '/harness') p = '/tests/fixtures/micorb-presets-harness.html';
        if (p.startsWith('/matrix')) p = '/tests/fixtures/micorb-presets-harness.html' + (new URL(req.url, 'http://x').search);
        else if (p.startsWith('/fixtures/')) p = '/tests' + p;
        const file = (p.startsWith('/js/') || p.startsWith('/css/')) ? join(WEB, p) : join(HERE, '..', p.split('?')[0]);
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

let server, port, base;

test.beforeAll(async () => {
  ({ server, port } = await startServer());
  base = `http://127.0.0.1:${port}`;
});
test.afterAll(async () => { server.close(); });

test.beforeEach(async ({ page }) => {
  // Playwright defaults to colorScheme:light — pin dark so factory boards
  // (and the dark page background) are deterministic; T2 flips explicitly.
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__harnessReady === true && window.__micorbTest.ready');
});

const OCEAN_DARK   = { a: '#3FE0D0', b: '#2E86E8', c: '#063A66' };
const AURORA_DARK  = { a: '#4BE3A0', b: '#6E7BF2', c: '#0B4A46' };
const NEBULA_DARK  = { a: '#8FA2FF', b: '#DC7CF0', c: '#2A1E5C' };
const NEON_DARK    = { a: '#3DF2F2', b: '#F05AD8', c: '#1A1A66' };
const ICEBERG_DARK = { a: '#BEE9FF', b: '#3EC8E8', c: '#0A2E52' };
const MAGMA_DARK   = { a: '#FF9A3D', b: '#F0483C', c: '#7A1220' };
const ASH_DARK     = { a: '#B9C2D2', b: '#8D99AE', c: '#2B3242' };
const OCEAN_LIGHT  = { a: '#1FBFBB', b: '#1F6FD0', c: '#05304F' };

/** Assert snap().pal matches a hex board within 1/255. */
async function expectBoard(page, hexes) {
  const pal = (await page.evaluate('window.__micorbTest.snap()')).pal;
  for (const slot of ['a', 'b', 'c']) {
    const want = [parseInt(hexes[slot].slice(1, 3), 16) / 255, parseInt(hexes[slot].slice(3, 5), 16) / 255, parseInt(hexes[slot].slice(5, 7), 16) / 255];
    pal[slot].forEach((v, i) => expect(v).toBeCloseTo(want[i], 2));
  }
  return pal;
}

const LS_KEY = 'nebflow_micOrb.palette'; // key('micOrb.palette') under the fallback brand

test('T1: factory defaults — Ocean base + default state map, hue zeroed', async ({ page }) => {
  const hexByState = {
    idle:         OCEAN_DARK,   // 基调 (ruling 2026-09-03: 开箱即 Ocean)
    listening:    AURORA_DARK,
    processing:   { a: '#FFB3A0', b: '#8FA0F5', c: '#5C3A78' }, // dawn
    'nebula-busy':NEBULA_DARK,
    'bg-agents':  NEON_DARK,
    frozen:       ICEBERG_DARK,
    'frozen-error': MAGMA_DARK,
    'mic-error':    MAGMA_DARK,
    offline:      ASH_DARK,
  };
  const webglOk = (await page.evaluate('window.__micorbTest.snap()')).webglOk;
  expect(webglOk, 'headless Chromium must provide WebGL (SwiftShader)').toBe(true);

  for (const [k, hexes] of Object.entries(hexByState)) {
    await page.evaluate((k) => window.__micorbTest.apply(k), k);
    await expectBoard(page, hexes);
  }
  // hue column zeroed: the mapped board IS the color language — no residual
  // relative rotation may double-shift it (processing was +22° pre-v8.3.0).
  await page.evaluate(() => window.__micorbTest.apply('processing'));
  expect((await page.evaluate('window.__micorbTest.snap()')).hue).toBe(0);
  // motion semantics preserved: listening still volume-driven @ ts 1.6,
  // frozen still frame.
  await page.evaluate(() => window.__micorbTest.apply('listening'));
  let snap = await page.evaluate('window.__micorbTest.snap()');
  expect(snap.ts).toBe(1.6);
  await page.evaluate(() => window.__micorbTest.apply('frozen'));
  snap = await page.evaluate('window.__micorbTest.snap()');
  expect(snap.ts).toBe(0);
  expect(snap.sat).toBeCloseTo(0.40, 5);
});

test('T2: theme switch swaps dark/light board with no transition pulse (F5)', async ({ page }) => {
  await page.evaluate(() => window.__micorbTest.apply('idle'));
  await expectBoard(page, OCEAN_DARK); // idle → 基调 → ocean dark
  // Wait out the state-switch pulse (soft-start + decay), then flip theme.
  await page.waitForFunction('window.__micorbTest.snap().pulse < 0.001', null, { timeout: 5000 });
  await page.emulateMedia({ colorScheme: 'light' });
  await page.waitForTimeout(120); // a few rAF frames for the listener
  const snap = await page.evaluate('window.__micorbTest.snap()');
  expect(snap.pulse).toBe(0); // F5: theme switch must not re-trigger the pulse
  await expectBoard(page, OCEAN_LIGHT);
  // State kept through the theme swap; back to dark restores the dark board.
  expect(snap.state).toBe('idle');
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(120);
  await expectBoard(page, OCEAN_DARK);
});

test('T3: persisted selection drives boards; corrupt/unknown data falls back', async ({ page }) => {
  // Valid selection: base emerald + listening→sunset.
  await page.addInitScript((v) => localStorage.setItem('nebflow_micOrb.palette', v),
    JSON.stringify({ base: 'emerald', map: { listening: 'sunset' }, custom: null }));
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__harnessReady === true && window.__micorbTest.ready');
  await expectBoard(page, { a: '#35D98A', b: '#B8E05A', c: '#0A5230' }); // emerald idle
  await page.evaluate(() => window.__micorbTest.apply('listening'));
  await expectBoard(page, { a: '#FFA26B', b: '#F060A0', c: '#7A2050' }); // sunset

  // Corrupt JSON → full factory fallback (Ocean + default map).
  await page.addInitScript(() => localStorage.setItem('nebflow_micOrb.palette', '{corrupt'));
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__harnessReady === true && window.__micorbTest.ready');
  await expectBoard(page, OCEAN_DARK);

  // Unknown base + unknown map value → dropped entries, defaults hold.
  await page.addInitScript((v) => localStorage.setItem('nebflow_micOrb.palette', v),
    JSON.stringify({ base: 'nope', map: { listening: 'nope', frozen: 'emerald' }, custom: { base: 'nope', dark: { a: 'zzz' } } }));
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__harnessReady === true && window.__micorbTest.ready');
  await expectBoard(page, OCEAN_DARK); // idle → default base (ocean)
  await page.evaluate(() => window.__micorbTest.apply('listening'));
  await expectBoard(page, AURORA_DARK); // aurora default
  await page.evaluate(() => window.__micorbTest.apply('frozen'));
  await expectBoard(page, { a: '#35D98A', b: '#B8E05A', c: '#0A5230' }); // valid map entry kept
});

test('T4: appearance section — base/map/custom edits persist, live orb follows, reset clears', async ({ page }) => {
  await page.evaluate(() => window.__micorbTest.mountAppearance());

  // 基调 → emerald: persisted + idle board follows through CHANGE_EVENT.
  await page.selectOption('#orb-base-select', 'emerald');
  await expectBoard(page, { a: '#35D98A', b: '#B8E05A', c: '#0A5230' });
  let dump = await page.evaluate('window.__micorbTest.localStorageDump()');
  expect(JSON.parse(dump[LS_KEY]).base).toBe('emerald');

  // 状态映射 listening → sunset: persisted + applied on next state enter.
  await page.selectOption('.orb-state-select[data-state="listening"]', 'sunset');
  await page.evaluate(() => window.__micorbTest.apply('listening'));
  await expectBoard(page, { a: '#FFA26B', b: '#F060A0', c: '#7A2050' });
  dump = await page.evaluate('window.__micorbTest.localStorageDump()');
  expect(JSON.parse(dump[LS_KEY]).map.listening).toBe('sunset');

  // Custom: enable, override slot a on the dark board.
  await page.evaluate(() => window.__micorbTest.apply('idle'));
  await page.click('#orb-custom-toggle');
  await page.waitForSelector('#orb-custom-body:not([hidden])');
  await page.fill('.orb-slot-hex[data-slot="a"]', '#40E0FF');
  await page.dispatchEvent('.orb-slot-hex[data-slot="a"]', 'change');
  await expectBoard(page, { a: '#40E0FF', b: '#B8E05A', c: '#0A5230' }); // emerald base + custom a
  dump = await page.evaluate('window.__micorbTest.localStorageDump()');
  const savedCustom = JSON.parse(dump[LS_KEY]).custom;
  expect(savedCustom.dark.a).toBe('#40E0FF');
  // The mapped states are untouched by the custom base board (配色=状态语言).
  await page.evaluate(() => window.__micorbTest.apply('mic-error'));
  await expectBoard(page, { a: '#FF9A3D', b: '#F0483C', c: '#7A1220' }); // magma
  await page.evaluate(() => window.__micorbTest.apply('idle'));

  // Reset → factory defaults everywhere.
  await page.click('#orb-appearance-reset');
  await expectBoard(page, OCEAN_DARK);
  dump = await page.evaluate('window.__micorbTest.localStorageDump()');
  expect(dump[LS_KEY]).toBeUndefined();

  // Preview orb exists (real OrbRenderer in the section).
  const previewCount = await page.locator('#orb-preview-slot canvas').count();
  expect(previewCount).toBe(1);
});

/* ---- T5 helpers: composite-level pixel sampling of a screenshot -------- */

/** Decode a PNG in a scratch page and sample box-relative points {u,v}. */
async function samplePoints(browser, png, box, points) {
  const p2 = await browser.newPage();
  const b64 = png.toString('base64');
  const values = await p2.evaluate(async ({ b64, box, points }) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b64;
    await img.decode();
    const cv = document.createElement('canvas');
    cv.width = img.width; cv.height = img.height;
    const ctx = cv.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    return points.map((pt) => {
      const d = ctx.getImageData(Math.round(box.x + pt.u * box.width), Math.round(box.y + pt.v * box.height), 1, 1).data;
      return [d[0], d[1], d[2]];
    });
  }, { b64, box, points });
  await p2.close();
  return values;
}

test('T5: premultiplied forensic — outside-silhouette pixels are pure background', async ({ page }) => {
  // Soak through several wobble excursions (r0 swings 0.56<->0.66) with the
  // default Neon board before probing the composited frame.
  await page.waitForTimeout(3000);
  const box = await page.locator('#mic-canvas').boundingBox();
  expect(box).not.toBeNull();
  const png = await page.screenshot();

  // Annulus sampling (2c0758a8 radial-profile method): 24 angles × 3 radii,
  // all ≥0.74 of the canvas half-size — comfortably past the max body radius
  // (r0 ≤ 0.66, ×1.02 idle breath) even after AA.
  const points = [];
  for (const r of [0.74, 0.84, 0.96]) {
    for (let a = 0; a < 24; a++) {
      points.push({ u: 0.5 + (r / 2) * Math.cos((a / 24) * 2 * Math.PI), v: 0.5 + (r / 2) * Math.sin((a / 24) * 2 * Math.PI) });
    }
  }
  points.push({ u: 0.004, v: 0.004 }, { u: 0.996, v: 0.004 }, { u: 0.004, v: 0.996 }, { u: 0.996, v: 0.996 });
  const BG = [11, 14, 20]; // #0B0E14
  const samples = await samplePoints(page.context().browser(), png, box, points);
  let maxDelta = 0;
  for (const rgb of samples) {
    for (let i = 0; i < 3; i++) maxDelta = Math.max(maxDelta, Math.abs(rgb[i] - BG[i]));
  }
  expect(maxDelta, `residue outside the silhouette (max channel delta ${maxDelta})`).toBeLessThanOrEqual(2);

  // Sanity: the body center actually rendered (not a dead canvas).
  const center = (await samplePoints(page.context().browser(), png, box, [{ u: 0.5, v: 0.5 }]))[0];
  const centerDelta = Math.abs(center[0] - BG[0]) + Math.abs(center[1] - BG[1]) + Math.abs(center[2] - BG[2]);
  expect(centerDelta).toBeGreaterThan(30);
});

test('T6: 9-state matrices (dark+light) shot to docs; error states distinguishable', async ({ browser }) => {
  await mkdir(DOCS, { recursive: true });

  for (const theme of ['dark', 'light']) {
    const mp = await browser.newPage();
    await mp.setViewportSize({ width: 880, height: 720 });
    await mp.goto(`${base}/matrix?matrix=1&theme=${theme}`);
    await mp.waitForFunction('window.__matrixReady === true', null, { timeout: 15000 });
    const path = join(DOCS, `${SHOT_STEM}-${theme}.png`);
    await mp.screenshot({ path });
    if (theme === 'dark') {
      // Mutual distinguishability, sampled from the composited screenshot:
      // cell centers of mic-error vs frozen-error vs idle must differ.
      const rects = await mp.evaluate(() => {
        const out = {};
        document.querySelectorAll('.mx-cell').forEach((c) => {
          const r = c.querySelector('canvas').getBoundingClientRect();
          out[c.dataset.state] = { x: r.x, y: r.y, width: r.width, height: r.height };
        });
        return out;
      });
      const png = await readFile(path);
      const center = { u: 0.5, v: 0.5 };
      const mkSample = async (key) =>
        (await samplePoints(browser, png, rects[key], [center]))[0];
      const idle = await mkSample('idle');
      const ferr = await mkSample('frozen-error');
      const merr = await mkSample('mic-error');
      const dist = (a, b) => Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
      expect(dist(idle, merr), 'mic-error must differ from idle').toBeGreaterThan(30);
      expect(dist(idle, ferr), 'frozen-error must differ from idle').toBeGreaterThan(30);
      expect(dist(ferr, merr), 'the two error states must be tellable apart').toBeGreaterThan(12);
    }
    await mp.close();
  }
});
