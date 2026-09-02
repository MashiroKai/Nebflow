// orbit-anim.spec.mjs — regression spec for the Flow Map orbit animation
// (src/main/resources/web/js/flowAnim.js + flowCss.js solar-node section).
//
// Verifies the three requirements the rAF driver was built for:
//   R1 流畅     — per-frame angular velocity is constant (no stalls/jumps).
//   R2 首尾相接 — the sweep over any window is exactly linear time × speed
//               (a loop seam / restart would break linearity), and a full
//               period returns the dot to its resting phase.
//   R3 收尾     — on running→completed the dots coast to the loop endpoint
//               (angle ≡ resting phase), THEN fade out; no mid-orbit snap.
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

const norm360 = (a) => ((a % 360) + 360) % 360;

/** Rendered rotation angle of an element from its computed transform. */
const renderedAngle = `(el) => {
  const tr = getComputedStyle(el).transform;
  const m = new DOMMatrixReadOnly(tr && tr !== 'none' ? tr : 'rotate(0deg)');
  return Math.atan2(m.m12, m.m11) * 180 / Math.PI;
}`;

test('R1: running dots rotate at constant angular velocity (no stalls/jumps)', async ({ page }) => {
  const report = await page.evaluate(`new Promise((resolve) => {
    const angleOf = ${renderedAngle};
    const wraps = [...document.querySelectorAll('#n1 .solar-dot-wrap')];
    window.__orbitTest.sync(); // deterministic attach, then sample frames
    const samples = [];
    const t0 = performance.now();
    function tick(now) {
      samples.push({ t: now - t0, angles: wraps.map(angleOf) });
      if (now - t0 < 1400) requestAnimationFrame(tick);
      else resolve({ samples, periods: window.__orbitTest.periods(), dirs: window.__orbitTest.directions() });
    }
    requestAnimationFrame(tick);
  })`);

  const { samples, periods, dirs } = report;
  expect(samples.length).toBeGreaterThan(30); // rAF actually ticking

  for (let ring = 0; ring < 3; ring++) {
    const omega = 360 / periods[ring] * dirs[ring]; // deg per second
    // Consecutive rendered angles must match omega*dt within tolerance —
    // catches stalls (delta≈0 then catch-up jump) and seams (delta >> expected).
    let checked = 0;
    for (let i = 1; i < samples.length; i++) {
      const dt = (samples[i].t - samples[i - 1].t) / 1000;
      if (dt <= 0 || dt > 0.2) continue; // ignore clamped/held frames
      let d = samples[i].angles[ring] - samples[i - 1].angles[ring];
      if (d > 180) d -= 360; if (d < -180) d += 360; // unwrap wraparound
      const expected = omega * dt;
      expect(Math.abs(d - expected)).toBeLessThan(Math.abs(expected) * 0.5 + 3);
      checked++;
    }
    expect(checked).toBeGreaterThan(20);
  }
});

test('R2: loop is seamless — sweep over a multi-period window is exactly linear', async ({ page }) => {
  // 2.1s ≈ two full revolutions of the slowest ring (1.0s), ~4 of ring-1.
  // A seam (snap back at the loop boundary, the old bug) would make the swept
  // angle diverge from linear time × speed by hundreds of degrees.
  const res = await page.evaluate(`new Promise((resolve) => {
    window.__orbitTest.sync();
    const t0 = performance.now();
    const a0 = window.__orbitTest.states()[0].angle.slice();
    setTimeout(() => {
      resolve({ t0, t1: performance.now(), a0,
                a1: window.__orbitTest.states()[0].angle.slice(),
                periods: window.__orbitTest.periods(),
                dirs: window.__orbitTest.directions() });
    }, 2100);
  })`);
  const dtSec = (res.t1 - res.t0) / 1000;
  expect(dtSec).toBeGreaterThan(2.0);
  for (let ring = 0; ring < 3; ring++) {
    const swept = res.a1[ring] - res.a0[ring];
    const expected = 360 * dtSec / res.periods[ring] * res.dirs[ring];
    // 2% + 12° slack covers rAF frame quantization (≈16ms × 720°/s ≈ 12°).
    expect(Math.abs(swept - expected)).toBeLessThan(Math.abs(expected) * 0.02 + 12);
  }
});

test('R3: rebuild mid-run keeps the angle (no restart jitter)', async ({ page }) => {
  const res = await page.evaluate(`new Promise((resolve) => {
    window.__orbitTest.sync();
    // Deterministic start: ring-1 parked at 300°.
    window.__orbitTest.setAngle([300, 130, 250]);
    const before = window.__orbitTest.states()[0].angle[0];
    // Simulate the innerHTML rebuild renderFlowMap performs on every update:
    // the element is replaced by a fresh clone, driver state must survive.
    const pane = document.querySelector('.canvas-tab-pane');
    const old = document.getElementById('n1');
    const fresh = old.cloneNode(true);
    pane.replaceChild(fresh, old);
    setTimeout(() => {
      const st = window.__orbitTest.states()[0];
      resolve({ before, after: st ? st.angle[0] : null,
                mode: st ? st.mode : null, el: st ? st.el : null });
    }, 150);
  })`);
  expect(res.mode).toBe('running');
  expect(res.el).not.toBeNull();
  // Driver advanced from the pre-rebuild angle — it did NOT restart at the
  // resting phase (0°) nor snap anywhere: 150ms at 720°/s ≈ 108°.
  expect(res.after).not.toBeNull();
  expect(res.after - res.before).toBeGreaterThan(40);
  expect(res.after - res.before).toBeLessThan(250);
});

test('R3: completion coasts to the loop endpoint, then fades (no hard cut)', async ({ page }) => {
  const seq = await page.evaluate(`new Promise((resolve) => {
    const angleOf = ${renderedAngle};
    const log = [];
    window.__orbitTest.sync();
    const node = document.getElementById('n1');
    const wraps = [...node.querySelectorAll('.solar-dot-wrap')];
    // Deterministic pre-completion angles: ring-1 at 356° (4° short of its
    // 0°/360° endpoint), ring-2 at 130° (10° past 120°, rotating backwards),
    // ring-3 at 250° (350° short of 240°) — the coast phase is guaranteed to
    // last ~970ms on ring-3, long enough to observe every phase transition.
    window.__orbitTest.setAngle([356, 130, 250]);
    // Complete the node the way a status render would: running → completed.
    node.classList.remove('running');
    node.classList.add('completed');
    window.__orbitTest.sync(); // reconcile picks the transition up immediately

    const poll = () => {
      const st = window.__orbitTest.states()[0];
      if (!st) { resolve({ log }); return; }
      log.push({
        mode: st.mode,
        angle: st.angle.slice(),
        targets: st.targets.slice(),
        opacity: st.opacity,
        domAngle: angleOf(wraps[2]), // ring-3, slowest — visible coast
        domOpacity: getComputedStyle(wraps[2]).opacity,
      });
      requestAnimationFrame(poll);
    };
    requestAnimationFrame(poll);
    // Safety stop: coast ≤1s + fade 450ms + slack.
    setTimeout(() => resolve({ log }), 2500);
  })`);

  const log = seq.log;
  expect(log.length).toBeGreaterThan(10);

  // Phase 1 — finishing: ring-3 angle advances monotonically (coasting, not
  // snapped), toward a target that is the resting phase 240° + one turn.
  const finishing = log.filter(e => e.mode === 'finishing');
  expect(finishing.length).toBeGreaterThan(10);
  for (let i = 1; i < finishing.length; i++) {
    expect(finishing[i].angle[2]).toBeGreaterThanOrEqual(finishing[i - 1].angle[2] - 0.01);
  }
  expect(norm360(finishing[0].targets[2])).toBeCloseTo(240, 0);
  // First sample trails setAngle by ≥1 rAF frame (≈6° at the test speed).
  expect(Math.abs(norm360(finishing[0].angle[2]) - 250)).toBeLessThan(15);

  // Phase 2 — fading: entered only once EVERY ring reached its endpoint, and
  // the endpoints are exactly the resting angles (0/120/240) — loop closed.
  const fading = log.filter(e => e.mode === 'fading');
  expect(fading.length).toBeGreaterThan(2);
  [0, 120, 240].forEach((phase, i) => {
    expect(norm360(fading[0].angle[i])).toBeCloseTo(phase, 0);
  });
  // Opacity decays from 1 toward 0 (a fade, not a snap to invisible). The
  // first fading sample may still read 1 (fade starts that same frame).
  expect(Math.min(...fading.map(e => e.opacity))).toBeLessThan(0.5);
  expect(fading[fading.length - 1].opacity).toBeLessThanOrEqual(fading[0].opacity);

  // Phase 3 — after the fade the state is released; the DOM keeps the dots at
  // the resting angle with opacity ≈ 0 (no mid-orbit residue, no restart).
  await page.waitForFunction('window.__orbitTest.states().length === 0', null, { timeout: 3000 });
  const finalDot = await page.evaluate(`(() => {
    const angleOf = ${renderedAngle};
    const w = document.querySelectorAll('#n1 .solar-dot-wrap')[2]; // ring-3
    return { ang: angleOf(w), op: parseFloat(getComputedStyle(w).opacity) };
  })()`);
  expect(norm360(finalDot.ang)).toBeCloseTo(240, 0);
  expect(finalDot.op).toBeLessThan(0.05);
});

test('pending nodes are never tracked; pane-scoped keys isolate projects', async ({ page }) => {
  const states = await page.evaluate('window.__orbitTest.sync() || window.__orbitTest.states()');
  // #n1 is running (tracked), #n2 is pending (ignored).
  expect(states).toHaveLength(1);
  expect(states[0].key).toBe('flow-map-testproj|N1');
  expect(states[0].mode).toBe('running');
  // A freshly attached running node starts at the resting phases.
  const freshPhase = await page.evaluate(`(() => {
    const pane = document.querySelector('.canvas-tab-pane');
    const el = document.createElement('div');
    el.className = 'solar-node running';
    el.dataset.nodeId = 'NFRESH';
    el.innerHTML = '<div class="solar-orbit">' +
      '<div class="solar-ring ring-1"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>' +
      '<div class="solar-ring ring-2"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>' +
      '<div class="solar-ring ring-3"><div class="solar-dot-wrap"><div class="solar-dot"></div></div></div>' +
      '</div>';
    pane.appendChild(el);
    window.__orbitTest.sync();
    const st = window.__orbitTest.states().find(s => s.key.endsWith('NFRESH'));
    el.remove();
    window.__orbitTest.sync();
    return st ? st.angle.slice() : null;
  })()`);
  expect(freshPhase).toEqual([0, 120, 240]);
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
