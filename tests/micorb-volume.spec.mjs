// micorb-volume.spec.mjs — regression spec for the micOrb listening-state
// voice-responsive deformation (v8.4.0; author request 2026-09-03: 听写中
// 音量越大形变越大，波形有机、不要整齐正弦).
//
// Verifies (levels injected through renderer.setVoiceLevel — the test seam
// over the mic sources; NO microphone needed):
//   T1 幅度单调跟随 — synthetic level steps 0.15→0.5→0.9: the smoothed
//      uVol amplitude (renderer.voiceLevel) follows monotonically and
//      settles inside a ±0.06 band of each target (attack τ=110ms).
//      Also proves the production setVolume alias lands on the same raw.
//   T2 电平归零回落 — from a settled 0.9, zeroing the level decays the
//      amplitude back to the base (≤0.04 after 1.4s; release τ=340ms).
//   T3 非 listening 态注入不生效 — injecting 0.9 in every non-listening
//      state leaves the amplitude at ≤0.02 (target forced to 0); the
//      listening control in the same session still rises (mechanism alive).
//   T4 非整齐正弦 — the shared waveform source-of-truth (VOICE_WAVE →
//      voiceWaveAt, mirrored 1:1 into the generated GLSL block):
//      (a) multi-harmonic: time-averaged angular DFT energy sits in exactly
//          the {3,5,8} wavenumber bins (integer k → zero bin leakage), top
//          bin share < 0.75 — a single-frequency control concentrates >99%
//          in one bin (criterion discriminative);
//      (b) non-fixed waveform: profile correlation across 1.07s / 2.41s
//          gaps < 0.98 (drift + amplitude jitter keep reshaping it);
//      (c) geometric soundness: |w(π)−w(−π)| < 1e-6 (integer wavenumbers
//          keep the rim 2π-continuous — no seam at the atan2 wrap).
//   T5 e2e 渲染 + 截图 — listening @0.15 vs @0.9 screenshots differ on
//      ≥3% of orb pixels (V10-consistent visible response); both frames
//      land in ~/.nebflow/docs/Nebflow/20260903_micorb-volume-{small,large}.png.
//   T6 约束保全 — F1: phaseTime monotonic with no jump >0.5 per 50ms across
//      idle→listening→frozen→processing switches; frozen converges to a
//      plateau (Δ<0.08 over the final 350ms). F5: theme flip while
//      listening+voice never re-triggers the pulse. 预乘: at max deformation
//      the annulus past the displaced silhouette (uv r≥0.78 > 0.66+0.085)
//      composites pure background. VoiceTap: with the harness disable flag
//      cleared, entering listening attempts the real getUserMedia and
//      degrades gracefully (no uncaught error, renderer stays alive).
//   Every test also asserts zero uncaught page errors (降级不抛异常).
//
// Self-contained: spins up its own static server on an ephemeral port
// serving src/main/resources/web (never touches the running instance).

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const DOCS = join(process.env.HOME, '.nebflow', 'docs', 'Nebflow');
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };

/** Static server for the web dir + this spec's harness page. */
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/' || p === '/volharness') p = '/tests/fixtures/micorb-volume-harness.html';
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
/** Uncaught page errors of the current test's main page (降级不抛异常). */
let pageErrors = [];

test.beforeAll(async () => {
  ({ server, port } = await startServer());
  base = `http://127.0.0.1:${port}`;
});
test.afterAll(async () => { server.close(); });

test.beforeEach(async ({ page }) => {
  pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto(base + '/volharness');
  await page.waitForFunction('window.__harnessReady === true && window.__volTest.ready');
});

test.afterEach(async () => {
  expect(pageErrors, 'no uncaught page errors').toEqual([]);
});

/** Settle the smoothed level at `v` and read back the uVol amplitude. */
async function injectAndSettle(page, v, ms = 600) {
  await page.evaluate((v) => window.__volTest.inject(v), v);
  await page.waitForTimeout(ms);
  return page.evaluate('window.__volTest.snapVoice()');
}

test('T1: 幅度单调跟随 — synthetic level steps drive the uVol amplitude up monotonically', async ({ page }) => {
  await page.evaluate(() => window.__volTest.apply('listening'));
  let snap = await page.evaluate('window.__volTest.snapVoice()');
  expect(snap.driven, 'listening must be volume-driven').toBe(true);
  expect(snap.webglOk, 'headless Chromium must provide WebGL (SwiftShader)').toBe(true);

  const l1 = await injectAndSettle(page, 0.15);
  expect(l1.raw).toBe(0.15);
  expect(Math.abs(l1.level - 0.15)).toBeLessThanOrEqual(0.06);

  const l2 = await injectAndSettle(page, 0.5);
  expect(l2.level).toBeGreaterThan(l1.level + 0.2); // strictly following up
  expect(Math.abs(l2.level - 0.5)).toBeLessThanOrEqual(0.06);

  const l3 = await injectAndSettle(page, 0.9);
  expect(l3.level).toBeGreaterThan(l2.level + 0.2);
  expect(Math.abs(l3.level - 0.9)).toBeLessThanOrEqual(0.06);

  // Production feed path (voiceEngine → setVolume alias) lands identically.
  await page.evaluate(() => window.__volTest.injectAlias(0.7));
  await page.waitForTimeout(700); // v8.4.1: release τ=340ms → 700ms to settle inside the ±0.06 band (400ms left ~0.061 residual)
  const viaAlias = await page.evaluate('window.__volTest.snapVoice()');
  expect(viaAlias.raw).toBe(0.7);
  expect(Math.abs(viaAlias.level - 0.7)).toBeLessThanOrEqual(0.06);
});

test('T2: 电平归零回落 — zeroed level decays back to the base amplitude (release)', async ({ page }) => {
  await page.evaluate(() => window.__volTest.apply('listening'));
  const loud = await injectAndSettle(page, 0.9);
  expect(loud.level).toBeGreaterThan(0.8);

  await page.evaluate(() => window.__volTest.inject(0));
  await page.waitForTimeout(1400); // e^(-1400/340) ≈ 0.016 → level ≈ 0.015 (v8.4.1 τ_release)
  const rest = await page.evaluate('window.__volTest.snapVoice()');
  expect(rest.raw).toBe(0);
  expect(rest.level, 'released to the fixed base amplitude').toBeLessThanOrEqual(0.04);
});

test('T3: 非 listening 态注入电平不生效 — all 8 other states force the amplitude to 0', async ({ page }) => {
  const states = ['processing', 'nebula-busy', 'bg-agents', 'frozen', 'frozen-error', 'mic-error', 'offline', 'idle'];
  for (const k of states) {
    await page.evaluate((k) => window.__volTest.apply(k), k);
    await page.evaluate(() => window.__volTest.inject(0.9));
    await page.waitForTimeout(400);
    const snap = await page.evaluate('window.__volTest.snapVoice()');
    expect(snap.state, k).toBe(k);
    expect(snap.driven, `${k} must not be volume-driven`).toBe(false);
    expect(snap.raw, `${k} raw echoes the injection (renderer accepts)`).toBe(0.9);
    expect(snap.level, `${k} smoothed amplitude must stay at the base`).toBeLessThanOrEqual(0.02);
  }
  // Control: the same session's listening state still responds to injection.
  await page.evaluate(() => window.__volTest.apply('listening'));
  const ctrl = await injectAndSettle(page, 0.9);
  expect(ctrl.level, 'listening control rises — the mechanism is alive').toBeGreaterThan(0.8);
});

/* ---- T4 helpers: waveform-shape analysis of the shared source of truth -- */

/** Sample voiceWaveAt profiles at several times; time-averaged angular-DFT
 *  spectrum metrics + the raw profiles for correlation checks. */
async function waveMetrics(page, times) {
  return page.evaluate((times) => {
    const w = window.__volTest.voiceWaveAt;
    const N = 144;
    const profiles = times.map((t) => {
      const p = [];
      for (let i = 0; i < N; i++) p.push(w((i / N) * Math.PI * 2, t));
      return p;
    });
    const energy = new Array(12).fill(0);
    for (const prof of profiles) {
      for (let k = 1; k <= 12; k++) {
        let re = 0, im = 0;
        for (let i = 0; i < N; i++) {
          const th = (i / N) * Math.PI * 2;
          re += prof[i] * Math.cos(k * th);
          im += prof[i] * Math.sin(k * th);
        }
        energy[k - 1] += re * re + im * im;
      }
    }
    const avg = energy.map((e) => e / profiles.length);
    const emax = Math.max(...avg);
    const etot = avg.reduce((s, x) => s + x, 0);
    // v8.4.1: detection floor 0.03*emax — the 8th-harmonic weight drop
    // (0.20 → 0.12) puts its time-averaged energy at ~5% of the top bin, so
    // the old 0.12 floor went blind to it. 0.03 keeps exactly {3,5,8}
    // detected (off-bins are numerically ~0: integer k → zero leakage) and
    // stays discriminative vs the neat-sine control (>99% in one bin).
    const strongBins = avg.map((e, i) => ({ k: i + 1, e })).filter((x) => x.e >= 0.03 * emax).map((x) => x.k);
    const top3 = avg.map((e, i) => ({ k: i + 1, e })).sort((a, b) => b.e - a.e).slice(0, 3).map((x) => x.k);
    return { profiles, topShare: emax / etot, strongBins, top3 };
  }, times);
}

function pearson(a, b) {
  const n = a.length;
  const ma = a.reduce((s, x) => s + x, 0) / n;
  const mb = b.reduce((s, x) => s + x, 0) / n;
  let num = 0, da = 0, db = 0;
  for (let i = 0; i < n; i++) { const x = a[i] - ma, y = b[i] - mb; num += x * y; da += x * x; db += y * y; }
  return num / Math.sqrt(da * db);
}

test('T4: 非整齐正弦 — multi-harmonic spectrum, evolving shape, 2π-continuous rim', async ({ page }) => {
  const TIMES = [0.3, 0.75, 1.0, 1.37, 1.9, 2.4, 2.71, 3.2, 3.65];
  const m = await waveMetrics(page, TIMES);

  // (a) 单频拒绝: time-averaged energy spread across the designed bins.
  expect(m.topShare, `time-averaged top DFT bin share ${m.topShare.toFixed(3)} must be < 0.75`).toBeLessThan(0.75);
  expect(m.strongBins, 'exactly the 3 designed wavenumbers {3,5,8} carry energy').toEqual([3, 5, 8]);
  expect(m.top3, 'dominant bins ordered by weight: 3, 5, 8').toEqual([3, 5, 8]);

  // Criterion discriminative: a "neat sine" control concentrates >99%.
  const ctrlShare = await page.evaluate(() => {
    const N = 144, k = 3, t = 1.0;
    const energy = new Array(12).fill(0);
    for (let kb = 1; kb <= 12; kb++) {
      let re = 0, im = 0;
      for (let i = 0; i < N; i++) {
        const th = (i / N) * Math.PI * 2;
        const v = Math.sin(th * k + t); // single frequency + phase
        re += v * Math.cos(kb * th); im += v * Math.sin(kb * th);
      }
      energy[kb - 1] = re * re + im * im;
    }
    const emax = Math.max(...energy);
    const etot = energy.reduce((s, x) => s + x, 0);
    return emax / etot;
  });
  expect(ctrlShare, 'control neat-sine concentrates >99% in one bin — the criterion separates them').toBeGreaterThan(0.99);

  // (b) 非固定波形: the profile reshapes over time (drift + amplitude jitter).
  const r1 = Math.abs(pearson(m.profiles[0], m.profiles[3])); // Δt = 1.07s
  const r2 = Math.abs(pearson(m.profiles[0], m.profiles[6])); // Δt = 2.41s
  expect(r1, `profiles 1.07s apart decorrelate (|r|=${r1.toFixed(3)} < 0.98)`).toBeLessThan(0.98);
  expect(r2, `profiles 2.41s apart decorrelate (|r|=${r2.toFixed(3)} < 0.98)`).toBeLessThan(0.98);

  // (c) 2π-continuity: integer wavenumbers leave no seam at the atan2 wrap.
  const seam = await page.evaluate(() => {
    const w = window.__volTest.voiceWaveAt;
    let worst = 0;
    for (const t of [0.5, 1.0, 1.37, 2.71]) worst = Math.max(worst, Math.abs(w(Math.PI, t) - w(-Math.PI, t)));
    return worst;
  });
  expect(seam, 'rim profile is 2π-continuous (no angular seam)').toBeLessThan(1e-6);
});

/* ---- T5 helpers: composited-pixel diff between two screenshots ---------- */

/** Load two PNGs in a scratch page and return the share of box pixels whose
 *  RGB channel-sum delta exceeds 45. `box` is in IMAGE pixels. */
async function diffShare(browser, pngA, pngB, box) {
  const p2 = await browser.newPage();
  const share = await p2.evaluate(async ({ a64, b64, box }) => {
    const load = async (b64) => {
      const img = new Image();
      img.src = 'data:image/png;base64,' + b64;
      await img.decode();
      const cv = document.createElement('canvas');
      cv.width = img.width; cv.height = img.height;
      const ctx = cv.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(img, 0, 0);
      return { data: ctx.getImageData(0, 0, cv.width, cv.height).data, w: cv.width, h: cv.height };
    };
    const [A, B] = [await load(a64), await load(b64)];
    expectSameDims(A, B);
    let diff = 0, total = 0;
    for (let y = Math.round(box.y); y < Math.round(box.y + box.height); y++) {
      for (let x = Math.round(box.x); x < Math.round(box.x + box.width); x++) {
        const i = (y * A.w + x) * 4;
        const d = Math.abs(A.data[i] - B.data[i]) + Math.abs(A.data[i + 1] - B.data[i + 1]) + Math.abs(A.data[i + 2] - B.data[i + 2]);
        if (d > 45) diff++;
        total++;
      }
    }
    return diff / total;
    function expectSameDims(a, b) {
      if (a.w !== b.w || a.h !== b.h) throw new Error(`dim mismatch ${a.w}x${a.h} vs ${b.w}x${b.h}`);
    }
  }, { a64: pngA.toString('base64'), b64: pngB.toString('base64'), box });
  await p2.close();
  return share;
}

test('T5: e2e 渲染响应 — small vs large voice level visibly differ; shots to docs', async ({ page, browser }) => {
  test.setTimeout(30000);
  // High-dpr context so the 64px orb lands as a viewable 192px shot.
  const ctx = await browser.newContext({ viewport: { width: 400, height: 300 }, deviceScaleFactor: 3, colorScheme: 'dark' });
  const hp = await ctx.newPage();
  const ctxErrors = [];
  hp.on('pageerror', (e) => ctxErrors.push(String(e)));
  await hp.goto(base + '/volharness');
  await hp.waitForFunction('window.__harnessReady === true && window.__volTest.ready');
  await hp.evaluate(() => window.__volTest.apply('listening'));

  const box = await hp.locator('#mic-canvas').boundingBox();
  expect(box).not.toBeNull();
  const clip = { x: box.x - 12, y: box.y - 12, width: box.width + 24, height: box.height + 24 };

  await hp.evaluate(() => window.__volTest.inject(0.15));
  await hp.waitForTimeout(700); // attack settles; wobble moves on regardless
  const small = await hp.screenshot({ clip });

  await hp.evaluate(() => window.__volTest.inject(0.9));
  await hp.waitForTimeout(700);
  const large = await hp.screenshot({ clip });

  await mkdir(DOCS, { recursive: true });
  await writeFile(join(DOCS, '20260903_micorb-volume-small.png'), small);
  await writeFile(join(DOCS, '20260903_micorb-volume-large.png'), large);
  await ctx.close();
  expect(ctxErrors, 'no uncaught page errors (screenshot context)').toEqual([]);

  // Orb box within the clipped image = 12px CSS margin × dpr 3.
  const share = await diffShare(browser, small, large, {
    x: 36, y: 36, width: box.width * 3, height: box.height * 3,
  });
  expect(share, `small-vs-large significant-diff pixel share ${share.toFixed(4)} must be ≥ 0.03 (V10-consistent)`).toBeGreaterThanOrEqual(0.03);
});

test('T6: 约束保全 — F1 phase continuity, F5 pulse, premultiplied annulus, tap degradation', async ({ page, browser }) => {
  test.setTimeout(40000);

  // ── F1: phaseTime accumulates continuously across state switches ──
  await page.evaluate(() => window.__volTest.apply('idle'));
  const samples = [];
  const grab = async () => samples.push(await page.evaluate('window.__volTest.phaseTime()'));
  const step = async (ms, n) => { for (let i = 0; i < n; i++) { await page.waitForTimeout(ms); await grab(); } };
  await step(50, 12);                                        // idle @ ts 0.5
  await page.evaluate(() => window.__volTest.apply('listening'));
  await step(50, 12);                                        // listening @ ts 1.6
  await page.evaluate(() => window.__volTest.apply('frozen'));
  await step(50, 24);                                        // frozen @ ts → 0
  await page.evaluate(() => window.__volTest.apply('processing'));
  await step(50, 8);                                         // processing @ ts 1.3
  for (let i = 1; i < samples.length; i++) {
    const d = samples[i] - samples[i - 1];
    expect(d, `phaseTime monotonic (sample ${i}: ${samples[i - 1].toFixed(4)} → ${samples[i].toFixed(4)})`).toBeGreaterThanOrEqual(-1e-9);
    expect(d, `no phase jump per 50ms window (Δ=${d.toFixed(4)})`).toBeLessThanOrEqual(0.5);
  }
  // Frozen stretch = samples 24..47 (12 idle + 12 listening precede it);
  // plateau check on its final 8 samples (≈350ms), not the array tail.
  const frozenTail = samples.slice(24 + 24 - 8, 24 + 24);
  expect(frozenTail[frozenTail.length - 1] - frozenTail[0] < 0.08,
    `frozen converges to a still frame (tail Δ=${(frozenTail[frozenTail.length - 1] - frozenTail[0]).toFixed(4)})`).toBe(true);

  // ── F5: theme flip while listening + voice never re-triggers the pulse ──
  await page.evaluate(() => window.__volTest.apply('listening'));
  await page.evaluate(() => window.__volTest.inject(0.5));
  await page.waitForTimeout(2600); // wait out the state-switch pulse decay
  await page.emulateMedia({ colorScheme: 'light' });
  await page.waitForTimeout(150);
  let snap = await page.evaluate('window.__volTest.snapVoice()');
  expect(snap.pulse, 'theme switch must not re-trigger the pulse (F5)').toBe(0);
  expect(snap.state).toBe('listening');
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(150);

  // ── 预乘: at max deformation nothing leaks past the displaced silhouette ──
  await page.evaluate(() => window.__volTest.inject(0.9));
  await page.waitForTimeout(700); // deformation fully settled at max amplitude
  const box = await page.locator('#mic-canvas').boundingBox();
  const png = await page.screenshot();
  const p2 = await browser.newPage();
  const BG = [11, 14, 20]; // #0B0E14
  const maxDelta = await p2.evaluate(async ({ b64, box, BG }) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b64;
    await img.decode();
    const cv = document.createElement('canvas');
    cv.width = img.width; cv.height = img.height;
    const ctx = cv.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);
    let worst = 0;
    // Annulus past the max displaced edge: body r0 ≤ 0.66 + voice 0.029
    // (v8.4.1: 0.9 × amp 0.032; was 0.085 at amp 0.062) → sample uv radii
    // 0.78/0.86/0.94 (box fraction r/2), 24 angles each.
    for (const r of [0.78, 0.86, 0.94]) {
      for (let a = 0; a < 24; a++) {
        const u = 0.5 + (r / 2) * Math.cos((a / 24) * 2 * Math.PI);
        const v = 0.5 + (r / 2) * Math.sin((a / 24) * 2 * Math.PI);
        const d = ctx.getImageData(Math.round(box.x + u * box.width), Math.round(box.y + v * box.height), 1, 1).data;
        worst = Math.max(worst, Math.abs(d[0] - BG[0]), Math.abs(d[1] - BG[1]), Math.abs(d[2] - BG[2]));
      }
    }
    return worst;
  }, { b64: png.toString('base64'), box, BG });
  await p2.close();
  expect(maxDelta, `annulus past the displaced silhouette is pure background (max channel delta ${maxDelta})`).toBeLessThanOrEqual(2);

  // ── VoiceTap degradation: real getUserMedia attempt, no uncaught error ──
  await page.evaluate(() => { window.__MICORB_TAP_DISABLE__ = false; });
  await page.evaluate(() => window.__volTest.apply('processing')); // leave listening first
  await page.evaluate(() => window.__volTest.apply('listening'));  // re-enter → tap start attempt
  await page.waitForTimeout(800); // headless denies getUserMedia → caught → 'failed'
  snap = await page.evaluate('window.__volTest.snapVoice()');
  expect(snap.webglOk, 'renderer alive through the tap attempt').toBe(true);
  expect(['failed', 'starting', 'running'], `tap degrades gracefully (state=${snap.tapState})`).toContain(snap.tapState);
  await page.evaluate(() => window.__volTest.inject(0.5));
  await page.waitForTimeout(400);
  const after = await page.evaluate('window.__volTest.snapVoice()');
  expect(after.level, 'level injection still works after tap failure').toBeGreaterThan(0.4);
});
