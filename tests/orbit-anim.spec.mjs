// orbit-anim.spec.mjs — regression spec for the Flow Map orbit animation
// (src/main/resources/web/js/flowAnim.js + flowCss.js solar-node section).
//
// As of 2026-09-19 the running rotation is a CSS keyframes animation on the inner
// .solar-dot-spin layer (was: a per-frame rAF driver writing inline transforms).
// Only the OBSERVATION channel changed — every behaviour the spec asserted is still
// asserted, now against the real render (computed transform/opacity + the live
// CSSAnimation objects) instead of driver internals:
//
//   R1 流畅     — per-frame angular velocity is constant (no stalls/jumps).
//   R2 首尾相接 — the swept angle over a multi-revolution window is exactly
//               linear in time (a loop seam / restart breaks linearity and shows a
//               backward jump).
//   R3 收尾     — on running→completed the dots coast to the loop endpoint
//               (angle ≡ resting phase), THEN fade out; no mid-orbit snap.
//   R3 重建     — an innerHTML rebuild mid-run keeps the angle (no restart jitter).
//   S2 减动效   — reduce: zero running animations AND the dots stay statically
//               visible at the resting phase.
//
// Self-contained: spins up its own static server on an ephemeral port serving
// src/main/resources/web (never touches the running Nebflow instance).

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };

/** Static server for the web dir + /harness fixture page. */
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/' || p === '/harness') p = '/tests/fixtures/orbit-anim-harness.html';
        else if (p.startsWith('/fixtures/')) p = '/tests' + p;
        const file = p.startsWith('/js/') ? join(WEB, p) : join(HERE, '..', p);
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
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__harnessReady === true && !!window.__orbitTest');
  // Shrink periods ~6x so a full revolution of the slowest ring takes 1s.
  await page.evaluate('window.__orbitTest.setPeriods([0.5, 0.75, 1.0])');
});

// ── observation primitives (rendered, not driver-internal) ──────────────────
const norm360 = (a) => ((a % 360) + 360) % 360;
const unwrap = (d) => { if (d > 180) return d - 360; if (d < -180) return d + 360; return d; };

/** Angle of one element's own computed transform (deg). */
const ANGLE_OF = `(el) => {
  const tr = getComputedStyle(el).transform;
  const m = new DOMMatrixReadOnly(tr && tr !== 'none' ? tr : 'rotate(0deg)');
  return Math.atan2(m.m12, m.m11) * 180 / Math.PI;
}`;

/** Composed angle of a ring's dot = phase layer (static) + animation layer (spun). */
const RING_ANGLE = `(node, ring) => {
  const angleOf = ${ANGLE_OF};
  const w = node.querySelector('.ring-' + ring + ' .solar-dot-wrap');
  const s = node.querySelector('.ring-' + ring + ' .solar-dot-spin');
  return angleOf(w) + angleOf(s);
}`;

const DIRS = [1, -1, 1];      // ring-2 counter-rotates (CSS animation-direction: reverse)
const PHASES = [0, 120, 240]; // resting phases == loop endpoints (CSS .ring-N .solar-dot-wrap)

test('R1: running dots rotate at constant angular velocity (no stalls/jumps)', async ({ page }) => {
  const report = await page.evaluate(`new Promise((resolve) => {
    const angleOf = ${ANGLE_OF};
    const spins = [...document.querySelectorAll('#n1 .solar-dot-spin')];
    window.__orbitTest.sync();
    const samples = [];
    const t0 = performance.now();
    function tick(now) {
      samples.push({ t: now - t0, angles: spins.map(angleOf) });
      if (now - t0 < 1400) requestAnimationFrame(tick);
      else resolve({ samples, periods: window.__orbitTest.periods() });
    }
    requestAnimationFrame(tick);
  })`);

  const { samples, periods } = report;
  expect(samples.length).toBeGreaterThan(30); // the animation is actually painting frames

  for (let ring = 0; ring < 3; ring++) {
    const omega = 360 / periods[ring] * DIRS[ring]; // deg per second
    // Consecutive rendered angles must match omega*dt within tolerance — catches
    // stalls (delta≈0 then catch-up jump) and seams (delta >> expected).
    let checked = 0;
    for (let i = 1; i < samples.length; i++) {
      const dt = (samples[i].t - samples[i - 1].t) / 1000;
      if (dt <= 0 || dt > 0.2) continue; // ignore clamped/held frames
      const d = unwrap(samples[i].angles[ring] - samples[i - 1].angles[ring]);
      const expected = omega * dt;
      expect(Math.abs(d - expected)).toBeLessThan(Math.abs(expected) * 0.5 + 3);
      checked++;
    }
    expect(checked).toBeGreaterThan(20);
  }
});

test('R1: shipped CSS animation shape — period / direction / phase / radius', async ({ page }) => {
  // Fresh page: the harness default periods are the SHIPPED ones (3 / 4.5 / 6 s).
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__harnessReady === true && !!window.__orbitTest');
  await page.evaluate('window.__orbitTest.sync()');

  const shape = await page.evaluate(`(() => {
    const node = document.getElementById('n1');
    const pending = document.getElementById('n2');
    const spins = [...node.querySelectorAll('.solar-dot-spin')];
    const anims = spins.map(s => (s.getAnimations()[0] || null));
    const geom = {};
    [1, 2, 3].forEach(r => {
      const dot = node.querySelector('.ring-' + r + ' .solar-dot');
      const wrap = node.querySelector('.ring-' + r + ' .solar-dot-wrap');
      const spin = node.querySelector('.ring-' + r + ' .solar-dot-spin');
      const cs = getComputedStyle(dot);
      geom[r] = { left: parseFloat(cs.left), width: parseFloat(cs.width),
                  wrapTransform: getComputedStyle(wrap).transform,
                  spinOrigin: getComputedStyle(spin).transformOrigin };
    });
    return {
      durations: anims.map(a => a && a.effect.getTiming().duration),
      directions: anims.map(a => a && a.effect.getTiming().direction),
      iterations: anims.map(a => { const t = a && a.effect.getTiming().iterations; return t === Infinity ? 'Infinity' : t; }),
      easing: anims.map(a => a && a.effect.getTiming().easing),
      names: spins.map(s => { const a = s.getAnimations()[0]; return a ? a.animationName : null; }),
      playStates: anims.map(a => a && a.playState),
      runningCount: document.getAnimations().filter(a => a.playState === 'running').length,
      pendingAnims: pending.querySelectorAll('.solar-dot-spin').length
        ? pending.querySelectorAll('.solar-dot-spin')[0].getAnimations().length : -1,
      geom,
    };
  })()`);

  // Periods 3s / 4.5s / 6s, linear, infinite, ring-2 reversed — the rAF driver's
  // RING_PHASE/RING_DIR/ringPeriod values, now living in CSS.
  expect(shape.durations).toEqual([3000, 4500, 6000]);
  expect(shape.directions).toEqual(['normal', 'reverse', 'normal']);
  expect(shape.iterations).toEqual(['Infinity', 'Infinity', 'Infinity']);
  expect(shape.easing).toEqual(['linear', 'linear', 'linear']);
  expect(shape.names).toEqual(['solar-spin', 'solar-spin', 'solar-spin']);
  expect(shape.playStates).toEqual(['running', 'running', 'running']);
  expect(shape.runningCount).toBe(3);
  expect(shape.pendingAnims).toBe(0); // pending nodes are never animated

  // Radius parity with the shipped rAF version: dot center radius = left + width/2
  // must equal the ring's border midline (ring width/2 − 0.5px).
  const midlines = { 1: 6 / 2 - 0.5, 2: 14 / 2 - 0.5, 3: 24 / 2 - 0.5 };
  for (const r of [1, 2, 3]) {
    expect(shape.geom[r].left + shape.geom[r].width / 2).toBeCloseTo(midlines[r], 3);
    // Phase layer still carries the static base phase (0/120/240).
    const m = shape.geom[r].wrapTransform.match(/matrix\(([^)]+)\)/);
    const [a, b] = m ? m[1].split(',').map(Number) : [1, 0];
    expect(norm360(Math.atan2(b, a) * 180 / Math.PI)).toBeCloseTo(PHASES[r - 1], 1);
    expect(shape.geom[r].spinOrigin).toBe('0px 0px');
  }
});

test('R2: loop is seamless — sweep over a multi-period window is exactly linear', async ({ page }) => {
  // 2.2s ≈ two full revolutions of the slowest ring (1.0s), ~4 of ring-1. A seam
  // (snap back at the loop boundary, the old bug) shows up as a backward jump and
  // makes the accumulated sweep diverge from linear time × speed.
  const res = await page.evaluate(`new Promise((resolve) => {
    const ringAngle = ${RING_ANGLE};
    const node = document.getElementById('n1');
    window.__orbitTest.sync();
    const t0 = performance.now();
    const prev = [1, 2, 3].map(r => ringAngle(node, r));
    const acc = [0, 0, 0];
    const steps = [];
    const iv = setInterval(() => {
      const now = performance.now();
      [1, 2, 3].forEach((r, i) => {
        const cur = ringAngle(node, r);
        const d = ((cur - prev[i] + 540) % 360) - 180; // shortest-path delta
        acc[i] += d;
        prev[i] = cur;
        steps.push({ ring: r, d });
      });
    }, 40);
    setTimeout(() => { clearInterval(iv); resolve({ dtSec: (performance.now() - t0) / 1000, acc,
      periods: window.__orbitTest.periods(), steps }); }, 2200);
  })`);

  expect(res.dtSec).toBeGreaterThan(2.0);
  expect(res.steps.length).toBeGreaterThan(120);
  // No snap-back anywhere in the window: every sampled step must move in the ring's
  // own direction (a loop seam shows as a ~120–360° backward jump).
  expect(res.steps.filter(s => s.d * DIRS[s.ring - 1] < -10).length).toBe(0);
  for (let ring = 0; ring < 3; ring++) {
    const expected = 360 * res.dtSec / res.periods[ring] * DIRS[ring];
    // 2% + 12° slack covers frame quantization of the 40ms sampler.
    expect(Math.abs(res.acc[ring] - expected)).toBeLessThan(Math.abs(expected) * 0.02 + 12);
  }
});

test('R3: rebuild mid-run keeps the angle (no restart jitter)', async ({ page }) => {
  // Long periods make a restart unmistakable: after ~2.5s of running, ring-1 sits
  // near 90°, so a phase reset to 0 would read as a large backward jump.
  await page.evaluate('window.__orbitTest.setPeriods([10, 12, 14])');
  const res = await page.evaluate(`new Promise((resolve) => {
    const ringAngle = ${RING_ANGLE};
    window.__orbitTest.sync();
    const node = document.getElementById('n1');
    setTimeout(() => {
      const t0 = performance.now();
      const a0 = ringAngle(node, 1);
      // Simulate the innerHTML rebuild renderFlowMap performs: the card element is
      // replaced by a freshly parsed clone (fresh CSS animation, fresh DOM nodes).
      // A real innerHTML parse carries no inline state, so shed the phase markers /
      // inline animation-delay the live element accumulated (cloneNode copies them).
      const pane = document.querySelector('.canvas-tab-pane');
      const fresh = node.cloneNode(true);
      [...fresh.querySelectorAll('.solar-dot-spin')].forEach((s) => {
        s.removeAttribute('data-orbit-phase'); s.removeAttribute('style');
      });
      pane.replaceChild(fresh, node);
      window.__orbitTest.sync();
      setTimeout(() => {
        const t1 = performance.now();
        const seeded = [...fresh.querySelectorAll('.solar-dot-spin')].map(s => ({
          marker: s.dataset.orbitPhase || null, delay: s.style.animationDelay || null,
        }));
        resolve({ a0, a1: ringAngle(fresh, 1), dtSec: (t1 - t0) / 1000, seeded,
                  periods: window.__orbitTest.periods() });
      }, 700);
    }, 2500);
  })`);

  expect(res.seeded.map((s) => s.marker)).toEqual(['1', '1', '1']);
  const omega = 360 / res.periods[0] * DIRS[0];
  const expected = omega * res.dtSec;
  const delta = unwrap(res.a1 - res.a0);
  // The dot advanced at the running rate across the rebuild — it neither restarted
  // at the resting phase (that would give delta ≈ -a0) nor stalled.
  expect(Math.abs(delta - expected)).toBeLessThan(Math.abs(expected) * 0.25 + 4);
});

test('R3: completion coasts to the loop endpoint, then fades (no hard cut)', async ({ page }) => {
  const seq = await page.evaluate(`new Promise((resolve) => {
    const ringAngle = ${RING_ANGLE};
    const norm = (a) => ((a % 360) + 360) % 360;
    const END = [0, 120, 240];   // resting phase of each ring == its loop endpoint
    window.__orbitTest.sync();
    const node = document.getElementById('n1');
    const wraps = [...node.querySelectorAll('.solar-dot-wrap')];
    const spins = [...node.querySelectorAll('.solar-dot-spin')];
    const periods = window.__orbitTest.periods().map(p => p * 1000);
    // Time each ring still needs to reach this revolution's endpoint, read from the
    // live animation (the coast must take exactly that long — a hard cut takes 0).
    const remaining = () => spins.map((s, i) => {
      const a = s.getAnimations()[0];
      const c = a && typeof a.currentTime === 'number' ? a.currentTime : 0;
      return periods[i] - (((c % periods[i]) + periods[i]) % periods[i]);
    });
    // Non-degeneracy precondition — determinism, NOT a weaker assertion: the coast is
    // only observable if the slowest ring still has a real distance to travel. The
    // page-load instant is arbitrary mod the shortened period, so instead of trusting
    // it, wait for a flip point where ring-3 sits mid-revolution (40°..320° of travel
    // left) and only then complete the node.
    const degLeft = () => ((END[2] - norm(ringAngle(node, 3))) % 360 + 360) % 360;
    const waitStart = performance.now();
    const wait = setInterval(() => {
      const d = degLeft();
      if ((d > 40 && d < 320) || performance.now() - waitStart > 4000) {
        clearInterval(wait);
        // Complete the node the way a status render does: running → completed, in place
        // (transplantNodeContent keeps the orbit DOM and rewrites the root class).
        const t0 = performance.now();
        const remain = remaining();
        const pre = [1, 2, 3].map(r => ringAngle(node, r));
        node.classList.remove('running');
        node.classList.add('completed');
        window.__orbitTest.sync();
        const log = [];
        const iv = setInterval(() => {
          log.push({
            t: Math.round(performance.now() - t0),
            ang: [1, 2, 3].map(r => ringAngle(node, r)),
            op: wraps.map(w => parseFloat(getComputedStyle(w).opacity)),
            faded: node.classList.contains('fm-orbit-faded'),
            anims: spins.map(s => { const a = s.getAnimations()[0]; return a ? a.animationName : null; }),
          });
          if (performance.now() - t0 > 2600) { clearInterval(iv); resolve({ log, pre, remaining: remain }); }
        }, 20);
      }
    }, 16);
  })`);

  const log = seq.log;
  expect(log.length).toBeGreaterThan(20);

  const firstFade = log.findIndex((e) => e.faded);
  expect(firstFade).toBeGreaterThan(2); // the coast is observable — not an instant cut

  // Phase 1 — the coast runs exactly as long as the slowest ring needed to reach
  // its endpoint (a hard cut would fade at t≈0), and ring-3 passes through ≥4
  // intermediate angles strictly between its start angle and the endpoint.
  const ring3Start = norm360(seq.pre[2]);
  const expectedCoast = ((PHASES[2] - ring3Start) % 360 + 360) % 360;
  expect(expectedCoast).toBeGreaterThan(15);
  const expectedFadeMs = Math.max(...seq.remaining);
  expect(Math.abs(log[firstFade].t - expectedFadeMs)).toBeLessThan(130);
  const intermediates = log.slice(0, firstFade)
    .filter((e) => norm360(e.ang[2]) !== ring3Start && norm360(e.ang[2]) !== PHASES[2]);
  expect(intermediates.length).toBeGreaterThan(4);
  // All three rings run the one-shot coast animation (same keyframes, held endpoint).
  expect(log[0].anims).toEqual(['solar-spin-coast', 'solar-spin-coast', 'solar-spin-coast']);

  // Phase 2 — endpoint landed exactly on the resting phases (loop closed).
  const atFade = log[firstFade];
  [0, 1, 2].forEach((i) => expect(norm360(atFade.ang[i])).toBeCloseTo(PHASES[i], 0));

  // Phase 3 — fade: opacity decays from 1 toward 0 (a fade, not a snap to invisible).
  const ops = log.slice(firstFade).map((e) => e.op[2]);
  expect(Math.min(...ops)).toBeLessThan(0.1);
  expect(ops[ops.length - 1]).toBeLessThan(0.05);
  expect(ops[0]).toBeGreaterThan(0.3);

  // Phase 4 — after the fade the dots hold the resting angle statically (no resume,
  // no mid-orbit residue).
  const tail = log.slice(-2).map((e) => e.ang[2]);
  expect(Math.abs(tail[1] - tail[0])).toBeLessThan(0.5);
  expect(norm360(tail[1])).toBeCloseTo(PHASES[2], 0);
});

test('S2 (reduced motion): zero running animations, dots statically visible', async ({ browser }) => {
  const page = await browser.newPage({ reducedMotion: 'reduce' });
  await page.goto(base + '/harness');
  await page.waitForFunction('window.__harnessReady === true && !!window.__orbitTest');
  await page.evaluate('window.__orbitTest.sync()');

  const r = await page.evaluate(`(() => {
    const node = document.getElementById('n1');
    const angleOf = ${ANGLE_OF};
    return {
      running: document.getAnimations().filter(a => a.playState === 'running').length,
      total: document.getAnimations().length,
      dotOpacity: [...node.querySelectorAll('.solar-dot')].map(d => parseFloat(getComputedStyle(d).opacity)),
      wrapOpacity: [...node.querySelectorAll('.solar-dot-wrap')].map(w => parseFloat(getComputedStyle(w).opacity)),
      phases: [1, 2, 3].map(rr => angleOf(node.querySelector('.ring-' + rr + ' .solar-dot-wrap'))),
      inlineAnim: [...node.querySelectorAll('.solar-dot-spin')].map(s => s.getAttribute('style') || ''),
      mq: matchMedia('(prefers-reduced-motion: reduce)').matches,
    };
  })()`);
  await page.close();

  expect(r.mq).toBe(true);
  expect(r.running).toBe(0);            // the acceptance assertion: B state runs nothing
  expect(r.total).toBe(0);
  expect(r.dotOpacity.every((o) => o > 0)).toBe(true);   // still visible…
  expect(r.wrapOpacity.every((o) => o > 0)).toBe(true);
  r.phases.forEach((a, i) => expect(norm360(a)).toBeCloseTo(PHASES[i], 1)); // …at rest
  expect(r.inlineAnim.every((s) => s === '')).toBe(true); // the JS assist stayed out
});

test('pending nodes are never animated; running toggles the CSS animation', async ({ page }) => {
  const r = await page.evaluate(`(() => {
    const n1 = document.getElementById('n1');
    const n2 = document.getElementById('n2');
    const count = (el) => el.querySelectorAll('.solar-dot-spin')[0].getAnimations().length;
    const before = { n1: count(n1), n2: count(n2) };
    // Stop the running node: the class gate must remove the animations entirely
    // (this is what replaces the rAF driver's "run only while there is work" rule).
    n1.classList.remove('running');
    n1.classList.add('pending');
    window.__orbitTest.sync();
    const stopped = count(n1);
    // …and re-entering running brings them back.
    n1.classList.remove('pending');
    n1.classList.add('running');
    window.__orbitTest.sync();
    const resumed = count(n1);
    return { before, stopped, resumed,
             anims: n1.querySelectorAll('.solar-dot-spin').length,
             inline: [...n1.querySelectorAll('.solar-dot-spin')].map(s => s.getAttribute('style') || '') };
  })()`);
  // #n1 is running (animated), #n2 pending (never animated).
  expect(r.before.n1).toBe(1);
  expect(r.before.n2).toBe(0);
  expect(r.anims).toBe(3);
  expect(r.stopped).toBe(0);
  expect(r.resumed).toBe(1);
  // A plain re-entry starts clean — no leftover one-shot override from a stop.
  expect(r.inline.every((s) => s === '')).toBe(true);
});

test('visual record: running vs completed screenshots', async ({ page }) => {
  await page.evaluate(() => window.__orbitTest.sync());
  await page.waitForTimeout(400);
  await page.screenshot({ path: '/tmp/orbit-running.png' });
  await page.evaluate(`(() => {
    const n = document.getElementById('n1');
    n.classList.remove('running');
    n.classList.add('completed');
    window.__orbitTest.sync();
  })()`);
  await page.waitForTimeout(1800); // coast + fade done
  await page.screenshot({ path: '/tmp/orbit-completed.png' });
  expect(true).toBe(true);
});
