// pop-artifacts.spec.mjs — the Pop artifact faces + the 2026-10-03 UI batch fixes.
//
// Root cause this file exists (2026-10-03 audit `03_ui.md` §2.2.2): the whole
// frontend Pop surface — `pop-artifacts.js` (435 lines) and `workline.js`
// (374 lines) — had **ZERO** automated coverage. A repo-wide search for 8 Pop
// identifiers (pop-artifact / buildPopArtifactRow / ___POP_JSON___ / fwd-btn /
// pop-stack-view / media-layer / applyLegacyPopCard / isPopPayload) returned 0
// matches under `tests/`. The audit's own wording: "S1's UI surface has a
// regression net; S2's UI surface has none."
//
// This spec drives the REAL render modules through
// tests/fixtures/pop-artifacts/harness.html over a throwaway static server it
// spawns and kills itself (never the 8080 host). Probes read the RENDERED DOM,
// never source wording.
//
// Cover (the audit's three named shapes, plus this batch's new behaviour):
//   P1  `pop-artifact` rows render (media face + file card) — the 0-coverage gap
//   P2  UI-D: the artifact sits at the END of its turn, not next to its tool row
//   P3  UI-D per-turn: two turns' artifacts do not cross the user-row boundary
//   P4  缺陷 D: a failed media face shows a visible placeholder + text note
//       (the old rule only covered `.bubble.ai img` — chat.css:1026)
//   P5  the media stack + expand/collapse toggle
//   P6  UI-G: the subagent window runs ZERO animations (computed animation-name)
//   P7  UI-G: the subagent window adds no periodic refresh (static grep + a
//       runtime instrumentation count of setInterval / rAF while it renders)
//   P8  UI-G: the main window keeps its effects (bubble pop + work-line motion)
//   P9  UI-G: the subagent window stays READABLE (content renders + static)
//
// Run: node node_modules/@playwright/test/cli.js test tests/pop-artifacts.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-artifacts/harness.html';

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 60) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

/** Fresh page per test: the harness keeps module-level state (the view
 *  registry, the artifact latch), so sharing a page would leak between cases. */
async function newPage(browser) {
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors };
}

/* ═══════════ P1 — the faces render at all (the 0-coverage gap) ═══════════ */

test.describe('P1 — Pop artifact faces render', () => {
  test('a Pop call with media + file renders one artifact row holding both faces', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__popThenMoreRows());

    const read = await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('#chat > .pop-artifact'));
      return {
        rows: rows.length,
        media: document.querySelectorAll('#chat .pop-artifact .pop-img').length,
        files: document.querySelectorAll('#chat .pop-artifact .file-card').length,
        fwdBtns: document.querySelectorAll('#chat .pop-artifact .fwd-btn').length,
        // The tool's own process row must still exist and be a `.row.tool`
        // (the artifact is never a `.row.tool` — that is what exempts it from
        // turnGroup's tuck set).
        toolRows: document.querySelectorAll('#chat > .row.tool').length,
        artifactIsToolRow: !!document.querySelector('#chat > .row.tool.pop-artifact'),
      };
    });

    expect(read.rows, 'exactly one artifact row for one Pop call').toBe(1);
    expect(read.media, 'the image face rendered').toBe(1);
    expect(read.files, 'the document face rendered').toBe(1);
    expect(read.fwdBtns, 'the file card carries its forward button').toBe(1);
    expect(read.toolRows, 'the Pop process row is still a tool row').toBeGreaterThanOrEqual(2);
    expect(read.artifactIsToolRow,
      'the artifact row is NEVER a .row.tool (that is the tuck exemption)').toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ P2/P3 — UI-D: the card sits at the turn bottom ═══════════ */

test.describe('P2 — UI-D: the artifact sits at the END of its turn', () => {
  test('rows appended after the Pop call never bury the card', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__popThenMoreRows());

    const seq = await page.evaluate(() => window.__seq('#chat'));
    const artifactIdx = seq.findIndex(s => s.kind === 'artifact');
    const lastIdx = seq.length - 1;

    expect(artifactIdx, 'the artifact row exists').toBeGreaterThan(-1);
    expect(artifactIdx,
      'the artifact is the LAST node of its turn, past the tool row + the reply')
      .toBe(lastIdx);
    // Sanity: the reply row really did arrive after the Pop call (otherwise the
    // assertion above would pass vacuously).
    expect(seq.some(s => s.cls.includes('row') && s.cls.includes('ai')),
      'the turn really appended a reply row after the Pop call').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('P3 — UI-D is per-turn: artifacts never cross the user boundary', () => {
  test('turn 1 keeps its own artifact when a second turn arrives with one', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__twoTurnsWithPop());

    const seq = await page.evaluate(() => window.__seq('#chat'));
    const userIdx = seq.map((s, i) => (s.kind === 'user' ? i : -1)).filter(i => i >= 0);
    const artIdx = seq.map((s, i) => (s.kind === 'artifact' ? i : -1)).filter(i => i >= 0);

    expect(userIdx.length, 'two user rows (two turns)').toBe(2);
    expect(artIdx.length, 'two artifact rows (one per turn)').toBe(2);
    // Each artifact must sit inside its own turn: after its user row and before
    // the NEXT user row.
    expect(artIdx[0], 'artifact 1 is after user 1').toBeGreaterThan(userIdx[0]);
    expect(artIdx[0], 'artifact 1 never crosses into turn 2').toBeLessThan(userIdx[1]);
    expect(artIdx[1], 'artifact 2 is in turn 2').toBeGreaterThan(userIdx[1]);
    // And the order of the two artifacts is preserved (no reversal by the pass).
    expect(artIdx[0]).toBeLessThan(artIdx[1]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ P4 — 缺陷 D: a failed media face is visible ═══════════ */

test.describe('P4 — failed media shows a visible placeholder (缺陷 D)', () => {
  test('a media item whose bytes cannot be fetched marks the face and prints a note', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__popBrokenMedia());
    // The ticket mint is a real network call to the static server (which 404s
    // /api/nf-ticket): the failure path runs on its rejection.
    await page.waitForFunction(
      () => !!document.querySelector('#chat .pop-img.nf-img-failed'),
      null, { timeout: 10000 },
    ).catch(() => {});

    const read = await page.evaluate(() => {
      const img = document.querySelector('#chat .pop-artifact .pop-img');
      if (!img) return null;
      const note = document.querySelector('#chat .pop-artifact .nf-media-failed-note');
      const cs = getComputedStyle(img);
      return {
        failed: img.classList.contains('nf-img-failed'),
        dataFlag: img.dataset.nfMediaFailed || '',
        noteText: note ? note.textContent.trim() : '',
        // The placeholder must be VISIBLE, not a zero-size hidden node.
        borderStyle: cs.borderStyle,
        minHeight: cs.minHeight,
        height: Math.round(img.getBoundingClientRect().height),
      };
    });

    expect(read, 'the media face exists').not.toBeNull();
    expect(read.failed, 'the terminal failure marks the element').toBe(true);
    expect(read.dataFlag, 'and records the state on the dataset').toBe('1');
    expect(read.noteText.length, 'a plain-text note names the failure').toBeGreaterThan(0);
    expect(read.borderStyle, 'the placeholder box is drawn (dashed)').toContain('dashed');
    expect(read.height, 'and it occupies real height (not a collapsed node)').toBeGreaterThan(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ P5 — the stack face + its toggle ═══════════ */

test.describe('P5 — media stack and its expand / collapse toggle', () => {
  test('three images stack (front + peeking layers) and the pill flattens them', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__popStack());

    const stacked = await page.evaluate(() => {
      const area = document.querySelector('#chat .pop-stack-area');
      return {
        exists: !!area,
        layers: document.querySelectorAll('#chat .pop-stack-view .media-layer').length,
        front: document.querySelectorAll('#chat .pop-stack-view .layer-front').length,
        pill: !!document.querySelector('#chat .pop-stack-area .pill'),
        expandHidden: getComputedStyle(document.querySelector('#chat .pop-expand-view')).display,
      };
    });
    expect(stacked.exists, 'a stack area exists for 3 media items').toBe(true);
    expect(stacked.layers, 'every item has a layer').toBe(3);
    expect(stacked.front, 'exactly one layer is the front card').toBe(1);
    expect(stacked.pill, 'the expand pill is present').toBe(true);
    expect(stacked.expandHidden, 'the expanded view starts collapsed').toBe('none');

    const toggled = await page.evaluate(() => window.__toggleExpand());
    expect(toggled, 'the pill was clickable').toBe(true);

    const expanded = await page.evaluate(() => ({
      viewHidden: getComputedStyle(document.querySelector('#chat .pop-stack-view')).display,
      expandShown: document.querySelector('#chat .pop-expand-view').classList.contains('show'),
      label: document.querySelector('#chat .pop-stack-area .pill').textContent.trim(),
    }));
    expect(expanded.viewHidden, 'expanding hides the stack').toBe('none');
    expect(expanded.expandShown, 'and shows the flattened view').toBe(true);
    expect(expanded.label.length, 'the pill relabels to the collapse action').toBeGreaterThan(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ P6/P9 — UI-G: the subagent window is static but readable ═══════ */

test.describe('UI-G — subagent window: no animation, still readable', () => {
  test('P6: zero computed animations inside the subagent container', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const role = await page.evaluate(() => window.__roleSubagent());
    expect(role, 'the subagent view was installed').toContain('bgagent-');

    // Run the SAME render calls the main window runs: dots + a tool run +
    // thinking + messages + a Pop artifact. If the role gate leaks anywhere,
    // one of these surfaces carries a live animation.
    await page.evaluate(() => window.__messagesHere());
    await page.evaluate(() => window.__dotsHere());
    await page.evaluate(() => window.__toolRunHere());
    await page.evaluate(() => window.__thinkingHere());
    await page.evaluate(() => window.__toolDoneHere());
    await page.evaluate(() => window.__popHere());

    const read = await page.evaluate(() => window.__animated('#popup-chat'));
    expect(read, 'the popup container exists').not.toBeNull();
    expect(read.hasStaticClass,
      'the popup container carries the single .nf-static application point').toBe(true);
    expect(read.animatedCount,
      `EVERY animation in the subagent window is off (computed animation-name); offenders: `
      + JSON.stringify(read.animated)).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('P9: the subagent window still shows its content (克制 ≠ 破功)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__roleSubagent());
    await page.evaluate(() => window.__messagesHere());
    await page.evaluate(() => window.__toolRunHere());
    await page.evaluate(() => window.__popHere());

    const read = await page.evaluate(() => {
      const root = document.querySelector('#popup-chat');
      return {
        rows: root.querySelectorAll('.row').length,
        toolLabel: (root.querySelector('.nf-workline .nf-wl-label') || {}).textContent || '',
        bubbleText: (root.querySelector('.bubble.ai') || {}).textContent || '',
        media: root.querySelectorAll('.pop-artifact .pop-img').length,
        // Counters / indicators must be legible: the work-line and the file
        // card are the two "tell the reader what happened" faces.
        fileCards: root.querySelectorAll('.pop-artifact .file-card').length,
      };
    });
    expect(read.rows, 'the subagent window rendered its rows').toBeGreaterThan(1);
    expect(read.toolLabel.length, 'the running tool is still NAMED on the line').toBeGreaterThan(0);
    expect(read.bubbleText.length, 'the reply text is still rendered').toBeGreaterThan(0);
    expect(read.media, 'the Pop media face still renders').toBe(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ P7 — UI-G: no added periodic refresh in the popup ═══════════ */

test.describe('UI-G — subagent window: no added periodic refresh', () => {
  test('P7: the subagent window arms NO timer and NO rAF loop of its own', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    // Instrument BEFORE any render. Two readings, in ONE page, so the only
    // difference is the module calls:
    //   (a) baseline — the harness helper's own raf2() × 5 awaits, no module
    //       call in between ⇒ the frames the HARNESS costs, not the module.
    //   (b) subagent — the identical five awaits PLUS the render calls.
    // Subtracting (a) from (b) leaves exactly the frames the MODULE scheduled in
    // the subagent role. That difference is the assertion; a raw upper bound
    // would have to encode the harness's own await count and would go stale the
    // moment the harness grows a step.
    //
    // (setInterval is read raw: the harness never arms one, so any count is the
    // module's.)
    const read = await page.evaluate(async () => {
      const baseline = async () => {
        const c = { interval: 0, timeout: 0, raf: 0 };
        const oi = window.setInterval, ot = window.setTimeout, orq = window.requestAnimationFrame;
        window.setInterval = function (...a) { c.interval++; return oi.apply(window, a); };
        window.setTimeout = function (...a) { c.timeout++; return ot.apply(window, a); };
        window.requestAnimationFrame = function (...a) { c.raf++; return orq.apply(window, a); };
        try { await window.__rafBaseline(); return c; }
        finally { window.setInterval = oi; window.setTimeout = ot; window.requestAnimationFrame = orq; }
      };
      const subagent = async () => {
        const c = { interval: 0, timeout: 0, raf: 0 };
        const oi = window.setInterval, ot = window.setTimeout, orq = window.requestAnimationFrame;
        window.setInterval = function (...a) { c.interval++; return oi.apply(window, a); };
        window.setTimeout = function (...a) { c.timeout++; return ot.apply(window, a); };
        window.requestAnimationFrame = function (...a) { c.raf++; return orq.apply(window, a); };
        try {
          await window.__roleSubagent();
          await window.__dotsHere();     // arms the live-seconds timer IF the role gate leaks
          await window.__toolRunHere();
          await window.__thinkingHere();
          await window.__toolDoneHere();
          return c;
        } finally { window.setInterval = oi; window.setTimeout = ot; window.requestAnimationFrame = orq; }
      };
      return { baseline: await baseline(), subagent: await subagent() };
    });

    const moduleRaf = read.subagent.raf - read.baseline.raf;
    // The one module-side frame in this sequence is chat.js appendThinkingDelta's
    // COALESCED one-shot (`if (!_pendingThinkingRAF) _pendingThinkingRAF = rAF(…)`
    // — a pending-flag guard, the callback self-clears): 1 frame, never a loop.
    // A loop would grow with the await count; 1 does not.
    expect(moduleRaf,
      `no rAF loop: the module scheduled ${moduleRaf} frame(s) over `
      + `${read.subagent.raf} total (harness baseline ${read.baseline.raf}); `
      + 'a coalescing one-shot is 1, a loop grows with the step count')
      .toBeLessThanOrEqual(1);
    expect(read.subagent.interval,
      'the subagent window armed NO setInterval (the dots live-seconds timer is gated)')
      .toBe(0);
    expect(read.subagent.timeout,
      'and no deferred work timer (the stack-expand setTimeout is gated too)').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('P7b: the PRIMARY window DOES arm the live-seconds timer (the gate is not a blanket off)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(async () => {
      let intervals = 0;
      const oi = window.setInterval;
      window.setInterval = function (...a) { intervals++; return oi.apply(window, a); };
      try {
        await window.__rolePrimary();
        await window.__dotsHere();
        return { role: 'primary', intervals };
      } finally {
        window.setInterval = oi;
      }
    });
    expect(read.intervals,
      'the main Nebula window still gets its live ticking (UI-G scope is the subagent only)')
      .toBeGreaterThan(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ P8 — UI-G: the main window keeps its effects ═══════════ */

test.describe('UI-G — main window effects intact', () => {
  test('P8: main window renders bubble pop + live work-line motion', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__rolePrimary());
    await page.evaluate(() => window.__messagesHere());
    await page.evaluate(() => window.__toolRunHere());

    const read = await page.evaluate(() => {
      const root = document.querySelector('#chat');
      return {
        hasStatic: root.classList.contains('nf-static'),
        // The bubble's entrance class is the main window's opt-in marker.
        popBubbles: root.querySelectorAll('.bubble.nf-pop').length,
        // The work-line's spinner is live CSS motion.
        animated: window.__animated('#chat'),
      };
    });
    expect(read.hasStatic, 'the main window is NOT marked static').toBe(false);
    expect(read.popBubbles, 'message bubbles carry the entrance animation class').toBeGreaterThan(0);
    expect(read.animated.animatedCount,
      'the main window still runs its animations').toBeGreaterThan(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
