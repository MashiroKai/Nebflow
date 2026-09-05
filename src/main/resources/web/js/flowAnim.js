// flowAnim.js — rAF-driven orbit animation for Flow Map / flow-run node dots.
//
// Replaces the old CSS-keyframe orbit (@keyframes solar-spin + negative
// animation-delays), which had three defects:
//
//  1. Loop seam — the keyframes declared no `from`, so the implicit start was
//     each ring's base transform: ring-2 (base 120°) swept only 120°→360° per
//     iteration and ring-3 (base 240°) only 240°→360°, then both SNAPPED back
//     at the loop boundary. This driver always sweeps a full 360° per
//     revolution, so every loop ends exactly where it began (首尾相接).
//
//  2. Hard cut on completion — `.solar-node.completed .solar-dot-wrap
//     { animation: none; }` froze the dots mid-orbit the instant a node
//     finished. Here the dots coast to the current revolution's endpoint
//     (identical to the resting angle) and only then fade out — no snap.
//
//  3. Rebuild jitter — renderFlowMap / renderFlowRunInto rebuild the card via
//     innerHTML on every status change; a CSS animation restarts from its
//     negative-delay phase and the dots teleport. Rotation state is keyed by
//     (canvas pane tab id, node id), so a rebuilt element resumes at the same
//     angle and the motion never restarts.
//
// Self-contained side-effect module: imported once by flowCanvas.js. A
// MutationObserver wakes the rAF loop when solar nodes appear; the loop runs
// only while there is something to animate.

const RECONCILE_MS = 250;   // in-loop DOM re-scan cadence
const FADE_MS = 450;        // dot fade-out after the final revolution
const MAX_FRAME_MS = 250;   // dt clamp — no teleport after tab-hidden stalls

// Per-ring config (ring-1/2/3): resting angle (which is ALSO the loop
// endpoint — a full revolution returns to it) and rotation direction
// (ring-2 counter-rotates). Periods live in ringPeriod below.
const RING_PHASE = [0, 120, 240];
const RING_DIR = [1, -1, 1];
let ringPeriod = [3, 4.5, 6]; // seconds per revolution; mutable via test hook

/**
 * @typedef {{ el: HTMLElement, wraps: HTMLElement[], angle: number[],
 *   mode: 'running'|'finishing'|'fading', targets: (number|null)[],
 *   opacity: number, fadeStart: number }} OrbitState
 */

/** @type {Map<string, OrbitState>} key = `${paneTabId}|${nodeId}` */
const states = new Map();
const uidOf = new WeakMap(); // fallback identity for nodes without data ids

let rafId = 0;
let lastTs = 0;
let lastReconcile = 0;

function keyOf(el) {
  const pane = el.closest('.canvas-tab-pane');
  const tab = pane ? (pane.dataset.tabId || '') : '';
  const id = el.getAttribute('data-node-id') || el.getAttribute('data-node') || '';
  if (id) return tab + '|' + id;
  let uid = uidOf.get(el);
  if (!uid) { uid = '#' + Math.random().toString(36).slice(2); uidOf.set(el, uid); }
  return tab + '|' + uid;
}

const isRunning = (el) => el.classList.contains('running');
const isTerminal = (el) =>
  el.classList.contains('completed') || el.classList.contains('failed') ||
  el.classList.contains('cancelled');

/** Push the current state onto the DOM (transform + fade opacity). */
function write(st) {
  for (let i = 0; i < st.wraps.length; i++) {
    const w = st.wraps[i];
    if (!w) continue;
    w.style.transform = 'rotate(' + st.angle[i] + 'deg)';
    w.style.opacity = String(st.opacity);
  }
}

/** running → finishing: set each ring's target to the NEXT loop endpoint in
 *  its own direction. The endpoint equals the ring's resting angle, so after
 *  the coast the dots sit exactly where a non-running node's dots rest. */
function startFinishing(st) {
  st.mode = 'finishing';
  st.targets = st.angle.map((a, i) => {
    const norm = ((a % 360) + 360) % 360;
    const phase = RING_PHASE[i];
    if (RING_DIR[i] > 0) return a + ((phase - norm + 360) % 360);
    return a - ((norm - phase + 360) % 360);
  });
}

/** Scan the DOM: adopt new running nodes, re-attach rebuilt elements, hand
 *  finished nodes over to the completion sequence, drop dead state. */
function reconcile() {
  const seen = new Set();
  // .solar-node cards are always HTML elements (div); narrow the Element
  // nodes so OrbitState.el/wraps keep their HTMLElement typing.
  /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.solar-node')).forEach((el) => {
    const key = keyOf(el);
    seen.add(key);
    let st = states.get(key);
    if (!st) {
      if (!isRunning(el) || isTerminal(el)) return;
      const wraps = /** @type {HTMLElement[]} */ (Array.from(el.querySelectorAll('.solar-dot-wrap')));
      if (wraps.length !== RING_PHASE.length) return; // not an orbit node
      st = { el, wraps, angle: RING_PHASE.slice(), mode: 'running',
             targets: [null, null, null], opacity: 1, fadeStart: 0 };
      states.set(key, st);
      write(st); // start at the resting angles — same as the CSS base
      return;
    }
    if (st.el !== el) {
      // Element was rebuilt (innerHTML re-render): re-attach, keep the angle.
      const wraps = /** @type {HTMLElement[]} */ (Array.from(el.querySelectorAll('.solar-dot-wrap')));
      if (wraps.length !== RING_PHASE.length) { states.delete(key); return; }
      st.el = el;
      st.wraps = wraps;
      write(st);
    }
    if (st.mode === 'running' && (isTerminal(el) || !isRunning(el))) {
      startFinishing(st);
    } else if (st.mode !== 'running' && isRunning(el) && !isTerminal(el)) {
      // Node re-entered running (flow re-run) mid-finish/fade — resume.
      st.mode = 'running';
      st.targets = [null, null, null];
      st.opacity = 1;
    }
  });
  for (const key of states.keys()) {
    if (!seen.has(key)) states.delete(key); // tab closed / node archived
  }
  return states.size;
}

function step(dtSec) {
  const finished = [];
  for (const [key, st] of states) {
    // NOTE: a detached element is NOT dropped here — it is mid-rebuild (the
    // innerHTML replace has not been observed yet). Dropping would restart
    // the animation at the resting phase; reconcile() re-attaches the new
    // element by key within ≤1 scan, and its seen-set is the only drop point.
    if (st.mode === 'running') {
      for (let i = 0; i < st.angle.length; i++) {
        st.angle[i] += (360 / ringPeriod[i]) * RING_DIR[i] * dtSec;
      }
      write(st);
    } else if (st.mode === 'finishing') {
      let allDone = true;
      for (let i = 0; i < st.angle.length; i++) {
        st.angle[i] += (360 / ringPeriod[i]) * RING_DIR[i] * dtSec;
        const reached = RING_DIR[i] > 0 ? st.angle[i] >= st.targets[i] : st.angle[i] <= st.targets[i];
        if (reached) st.angle[i] = st.targets[i]; // land exactly on the loop endpoint
        else allDone = false;
      }
      write(st);
      if (allDone) { st.mode = 'fading'; st.fadeStart = performance.now(); }
    } else { // fading — dots rest at the endpoint and dissolve
      const p = Math.min(1, (performance.now() - st.fadeStart) / FADE_MS);
      st.opacity = 1 - p;
      write(st);
      if (p >= 1) finished.push(key);
    }
  }
  for (const key of finished) states.delete(key);
}

function frame(now) {
  const dtSec = Math.min(now - lastTs, MAX_FRAME_MS) / 1000;
  lastTs = now;
  if (now - lastReconcile >= RECONCILE_MS) { lastReconcile = now; reconcile(); }
  step(dtSec);
  rafId = states.size > 0 ? requestAnimationFrame(frame) : 0;
}

function ensureLoop() {
  if (!rafId) { lastTs = performance.now(); rafId = requestAnimationFrame(frame); }
}

/** Reconcile now and keep the loop alive — called by the observer, renders
 *  and tests. */
export function orbitSync() {
  if (reconcile() > 0) ensureLoop();
}

// DOM churn anywhere in the app can (re)introduce solar nodes (canvas renders
// replace innerHTML wholesale) — watch and re-sync cheaply (throttled).
let syncTimer = 0;
function scheduleSync() {
  if (syncTimer) return;
  syncTimer = setTimeout(() => { syncTimer = 0; orbitSync(); }, 80);
}

if (typeof document !== 'undefined') {
  const isOrbitNode = (n) => n.nodeType === 1 &&
    ((n.classList && n.classList.contains('solar-node')) ||
     (n.querySelector && n.querySelector('.solar-node')));
  const mo = new MutationObserver((muts) => {
    for (const m of muts) {
      if (m.type !== 'childList') continue;
      // MutationRecord.target is typed Node; childList records always carry
      // an Element here (the runtime classList guard below stays anyway).
      const tgt = /** @type {Element} */ (m.target);
      if ((tgt.classList && tgt.classList.contains('solar-node')) ||
          [...m.addedNodes].some(isOrbitNode) ||
          [...m.removedNodes].some(isOrbitNode)) {
        scheduleSync();
        return;
      }
    }
  });
  mo.observe(document.body, { childList: true, subtree: true });
  // Boot: pick up nodes already in the DOM (tab restore path).
  requestAnimationFrame(() => orbitSync());
}

// Test hook — exposed for tests/orbit-anim.spec.mjs verification. The
// intersection cast declares the test-only global on Window for checkJs
// (runtime augmentation; no code depends on reading it back statically).
if (typeof window !== 'undefined') {
  const orbitWindow = /** @type {Window & { __orbitTest?: object }} */ (window);
  orbitWindow.__orbitTest = {
    states: () => Array.from(states.entries()).map(([key, s]) => ({
      key, mode: s.mode, angle: s.angle.slice(), targets: s.targets.slice(),
      opacity: s.opacity,
    })),
    periods: () => ringPeriod.slice(),
    phases: () => RING_PHASE.slice(),
    directions: () => RING_DIR.slice(),
    sync: orbitSync,
    setPeriods: (p) => { ringPeriod = p.slice(); },
    setAngle: (angles) => {
      const st = states.values().next().value; // single-node harness usage
      if (st) { st.angle = angles.slice(); write(st); }
    },
  };
}
