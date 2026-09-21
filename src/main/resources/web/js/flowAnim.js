// flowAnim.js — orbit phase continuity + completion coast for the Flow Map node
// dots. The per-frame rAF driver is gone: running rotation is a CSS keyframes
// animation on .solar-dot-spin (see the orbit block in flowCss.js for the phase
// layer / animation layer split, the keyframes, the .running class gate and the
// reduced-motion block).
//
// What replaced the rAF loop, and why each piece is here:
//
//  1. Loop seam (old CSS defect ①) — fixed in CSS, no JS: @keyframes solar-spin
//     declares an explicit from AND to, so each revolution sweeps a full 360°
//     whose endpoint coincides with the resting phase. No snap at the boundary.
//     (The pre-2026-09 CSS version omitted `from`, so ring-2/3 swept only
//     360°-phase per loop and jumped back — that is why the rAF driver existed.)
//
//  2. Completion coast (defect ②) — old CSS froze the dots mid-orbit the instant
//     a node finished (`.solar-node.completed .solar-dot-wrap { animation: none }`).
//     CSS cannot express "finish this revolution, then stop, then fade", so on
//     running → terminal we write a ONE-SHOT override per ring: a copy of the same
//     keyframes (a different animation-name forces a fresh animation, which is what
//     lets the negative delay take effect) with iteration-count 1 + fill-mode
//     forwards + delay = -(progress already elapsed) ⇒ the dot keeps moving from
//     where it is to this revolution's endpoint (= the resting phase) and holds
//     there. animationend then adds .fm-orbit-faded for the 0.45s fade (FADE_MS of
//     the old driver). One write per ring, no per-frame work, no timers.
//
//  3. Rebuild continuity (defect ③) — renderFlowMap / renderFlowRunInto rebuild
//     cards via innerHTML; a freshly parsed element restarts a CSS animation at
//     phase 0 and the dots teleport. We keep a per-(pane tab id, node id) phase
//     origin and seed `animation-delay: -(elapsed mod period)` once per freshly
//     attached spin layer, so a rebuilt element resumes at the angle the previous
//     one had (the incremental path — transplantNodeContent — keeps the orbit DOM,
//     so nothing is needed there at all).
//
// Reduced motion: the CSS @media block turns these animations off declaratively.
// Every write below is additionally short-circuited under the same media query, so
// the assist never introduces an inline animation in the reduce state — B-state
// `document.getAnimations()` running count stays 0.
//
// Self-contained side-effect module: imported once by main.js. A MutationObserver
// wakes the reconcile when solar nodes appear, change status class or are rebuilt.

const FADE_CLASS = 'fm-orbit-faded';
const COAST_CLASS = 'fm-orbit-coast';
const FADE_MS = 450;         // kept for documentation; the fade itself is the CSS transition
const RING_DIR = [1, -1, 1]; // ring-2 counter-rotates — mirrors the CSS reverse flag
const DEFAULT_PERIOD_S = [3, 4.5, 6]; // mirrors the :root --solar-period-* defaults
const PERIOD_VARS = ['--solar-period-1', '--solar-period-2', '--solar-period-3'];

/** @typedef {{ phase: 'running'|'coasting', startTs: number[] }} NodePhase */

/** @type {Map<string, NodePhase>} key = `${paneTabId}|${nodeId}` */
const phases = new Map();
const uidOf = new WeakMap(); // fallback identity for nodes without data ids
/** @type {WeakMap<Element, Set<Element>>} node element → spin layers still coasting */
const coasting = new WeakMap();

const reduced = () =>
  typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

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

/** @param {Element} el @returns {HTMLElement[]} */
const spinsOf = (el) => /** @type {HTMLElement[]} */ (Array.from(el.querySelectorAll('.solar-dot-spin')));

/** Resolve each ring's period (ms) from the live CSS custom properties, so the CSS
 *  stays the single source of truth (the specs shorten the periods on :root). */
function periodsMs(el) {
  const cs = getComputedStyle(el);
  return PERIOD_VARS.map((name, i) => {
    const raw = cs.getPropertyValue(name).trim();
    const n = parseFloat(raw);
    if (!isFinite(n) || n <= 0) return DEFAULT_PERIOD_S[i] * 1000;
    return /ms$/.test(raw) ? n : n * 1000;
  });
}

/** The ring's live spin animation, if any (undefined when the .running gate is off). */
function spinAnim(s) {
  const list = typeof s.getAnimations === 'function' ? s.getAnimations() : [];
  return list.find((a) => a.animationName === 'solar-spin');
}

/** Anchor every ring's animation ORIGIN (the moment its current revolution began).
 *  Read from the live animation rather than assumed from the scan time, so the
 *  analytic phase below stays exact even when the observer fires late. Holding the
 *  origin — not the animation object — is what lets the completion coast start from
 *  the right angle AFTER the .running gate has already removed the animation. */
function anchorOrigin(el, now) {
  const spins = spinsOf(el);
  if (spins.length !== RING_DIR.length) return null;
  return spins.map((s) => {
    const a = spinAnim(s);
    const c = a && typeof a.currentTime === 'number' ? a.currentTime : 0;
    return now - c;
  });
}

/** Progress (ms) of ring i within its own revolution, from the anchored origin. */
const progressOf = (st, i, p, now) => (((now - st.startTs[i]) % p) + p) % p;

/** Seed the resumed phase on spin layers that have never been anchored. One write
 *  per fresh DOM node (data-orbit-phase marker); never re-written for a live
 *  animation, whose start time must stay untouched. */
function seedPhase(el, st, now) {
  const spins = spinsOf(el);
  if (spins.length !== RING_DIR.length || !st.startTs.length) return;
  const periods = periodsMs(spins[0]);
  for (let i = 0; i < spins.length; i++) {
    const s = spins[i];
    if (s.dataset.orbitPhase === '1') continue;
    s.dataset.orbitPhase = '1';
    const progress = progressOf(st, i, periods[i], now);
    if (progress > 1) s.style.animationDelay = '-' + progress.toFixed(1) + 'ms';
  }
}

/** Drop the one-shot completion override (node re-entered running, or rebuilt). */
function clearCoast(el, key) {
  el.classList.remove(FADE_CLASS);
  el.classList.remove(COAST_CLASS);
  coasting.delete(el);
  spinsOf(el).forEach((s) => {
    s.style.animationName = '';
    s.style.animationDuration = '';
    s.style.animationDirection = '';
    s.style.animationIterationCount = '';
    s.style.animationFillMode = '';
    s.style.animationDelay = '';
    delete s.dataset.orbitPhase;
  });
  if (key) phases.delete(key);
}

/** running → terminal: coast every ring to this revolution's endpoint, then let the
 *  animationend handler fade the wrapper out. The phase comes from the anchored
 *  origin, so this works even though the .running gate has already removed the live
 *  animation — the dots never snap back to the resting phase in between. */
function startCoast(el, st, now) {
  // 🔴 MUST run before any style read below (getComputedStyle/getAnimations force a
  // style flush): it has to be in effect in the SAME style computation that dropped
  // .running, otherwise the animation is cancelled first and the coast would be
  // re-anchored from a brand-new animation at phase 0.
  el.classList.add(COAST_CLASS);
  const spins = spinsOf(el);
  if (spins.length !== RING_DIR.length) return;
  const periods = periodsMs(spins[0]);
  const pending = new Set();
  for (let i = 0; i < spins.length; i++) {
    const s = spins[i];
    const p = periods[i];
    const live = spinAnim(s);
    const c = live && typeof live.currentTime === 'number'
      ? ((live.currentTime % p) + p) % p          // exact: read off the running animation
      : progressOf(st, i, p, now);                // fallback: stored origin (fresh/rebuilt DOM)
    // A different animation-name is deliberate: it forces a new animation object,
    // which is the only way a negative delay can place the dot at the angle it is
    // already showing (the animation is created now, so localTime = -delay = c).
    s.style.animationName = 'solar-spin-coast';
    s.style.animationDuration = p + 'ms';
    s.style.animationTimingFunction = 'linear';
    s.style.animationDirection = RING_DIR[i] < 0 ? 'reverse' : 'normal';
    s.style.animationIterationCount = '1';
    s.style.animationFillMode = 'forwards';
    s.style.animationDelay = '-' + c.toFixed(1) + 'ms';
    pending.add(s);
  }
  coasting.set(el, pending);
}

/** Fade only after EVERY ring has landed on its endpoint (matches the old driver,
 *  which entered its 450ms fade once all three targets were reached). */
function settleCoast(target, node) {
  const pending = coasting.get(node);
  if (!pending) return;
  pending.delete(target);
  if (pending.size === 0) { coasting.delete(node); node.classList.add(FADE_CLASS); }
}

function onAnimEnd(ev) {
  const target = ev.target;
  if (!(target instanceof Element) || ev.animationName !== 'solar-spin-coast') return;
  const node = target.closest('.solar-node');
  if (node) settleCoast(target, node);
}

function onAnimCancel(ev) {
  const target = ev.target;
  if (!(target instanceof Element) || ev.animationName !== 'solar-spin-coast') return;
  const node = target.closest('.solar-node');
  if (!node) return;
  const pending = coasting.get(node);
  if (!pending) return;
  pending.delete(target);
  if (pending.size === 0) coasting.delete(node);
}

/** Scan the DOM: anchor new running nodes, hand finished nodes to the coast, drop
 *  dead phase origins. Pure bookkeeping — no animation frames are requested. */
function reconcile(now) {
  if (reduced()) return 0;
  const seen = new Set();
  // .solar-node cards are always HTML elements (div); narrow the Element nodes so
  // the HTMLElement helpers below keep their typing.
  /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('.solar-node')).forEach((el) => {
    const key = keyOf(el);
    seen.add(key);
    let st = phases.get(key);
    if (isRunning(el) && !isTerminal(el)) {
      if (!st || st.phase !== 'running') {
        clearCoast(el, key);              // re-run after a stop: drop the override…
        st = { phase: 'running', startTs: [] };
        phases.set(key, st);
      }
      if (!st.startTs.length) st.startTs = anchorOrigin(el, now) || [now, now, now]; // fallback origin only
      seedPhase(el, st, now);             // …and resume this revolution's phase origin
      return;
    }
    if (isTerminal(el)) {
      if (!st) return;                      // rendered terminal from the start: leave it static
      if (st.phase === 'running') {
        st.phase = 'coasting';            // set first so a re-entrant scan cannot double-apply
        startCoast(el, st, now);
      } else if (!el.classList.contains(FADE_CLASS) && !coasting.has(el)) {
        // Rebuilt mid-coast: a fresh element has no animation, so re-apply the coast
        // from the anchored phase (the old driver carried its state across rebuilds).
        startCoast(el, st, now);
      }
      return;
    }
    phases.delete(key);                   // pending / wiring: not an orbit node
  });
  for (const key of phases.keys()) {
    if (!seen.has(key)) phases.delete(key); // tab closed / node archived
  }
  return phases.size;
}

/** Reconcile now — called by the observer, the boot scan and the specs. */
export function orbitSync() {
  return reconcile(performance.now());
}

if (typeof document !== 'undefined') {
  document.addEventListener('animationend', onAnimEnd);
  document.addEventListener('animationcancel', onAnimCancel);
  const isOrbitNode = (n) => n.nodeType === 1 &&
    ((n.classList && n.classList.contains('solar-node')) ||
     (n.querySelector && n.querySelector('.solar-node')));
  // Reconcile inline (not on a timer): a status class rewrite must be turned into
  // the coast in the same task, otherwise the dots paint one or two frames at the
  // resting phase before the coast re-anchors them. The observer only fires when a
  // solar node is actually involved, and one callback covers a whole mutation batch,
  // so this stays rare — the counters below are exposed for the specs/verification.
  const stats = { reconciles: 0, totalMs: 0 };
  const runReconcile = () => {
    const t0 = performance.now();
    reconcile(t0);
    stats.reconciles += 1;
    stats.totalMs += performance.now() - t0;
  };
  const mo = new MutationObserver((muts) => {
    for (const m of muts) {
      if (m.type === 'attributes') {
        // Status transitions (running → completed/cancelled/failed) arrive as an
        // in-place class rewrite (transplantNodeContent) — that is the coast trigger.
        const t = /** @type {Element} */ (m.target);
        if (t.classList && t.classList.contains('solar-node')) { runReconcile(); return; }
        continue;
      }
      if (m.type !== 'childList') continue;
      const tgt = /** @type {Element} */ (m.target);
      if ((tgt.classList && tgt.classList.contains('solar-node')) ||
          [...m.addedNodes].some(isOrbitNode) ||
          [...m.removedNodes].some(isOrbitNode)) {
        runReconcile();
        return;
      }
    }
  });
  mo.observe(document.body, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['class'],
  });
  // Boot scan: pick up nodes already in the DOM (tab restore path). Deliberately NOT
  // a requestAnimationFrame — this module schedules no frames at all.
  setTimeout(runReconcile, 0);
  if (typeof window !== 'undefined') {
    const w = /** @type {Window & { __orbitStats?: object }} */ (window);
    w.__orbitStats = stats;
  }
}

// Test hook — exposed for tests/orbit-anim.spec.mjs. The specs observe the RENDERED
// transform/opacity of the real elements; these accessors only drive/verify the
// test-controlled inputs (shortened periods) and expose reconcile diagnostics.
if (typeof window !== 'undefined') {
  const orbitWindow = /** @type {Window & { __orbitTest?: object }} */ (window);
  orbitWindow.__orbitTest = {
    sync: orbitSync,
    periods: () => periodsMs(document.documentElement).map((ms) => ms / 1000),
    setPeriods: (p) => {
      const root = /** @type {HTMLElement} */ (document.documentElement);
      PERIOD_VARS.forEach((name, i) => root.style.setProperty(name, String(p[i]) + 's'));
    },
    states: () => Array.from(phases.entries()).map(([key, s]) => ({
      key, phase: s.phase, startTs: s.startTs.map((v) => Math.round(v)),
    })),
    fadeMs: () => FADE_MS,
  };
}
