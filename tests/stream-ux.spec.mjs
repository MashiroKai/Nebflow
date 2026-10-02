// stream-ux.spec.mjs — running-stream display redesign (chain chain-streamux).
//
// 判据面 = the RENDERED DOM / animation events, never the source wording
// (§二.1). Every probe below is written form-agnostically — it names NO class
// introduced by this batch — so the SAME file runs green on this branch and RED
// on the baseline tree (`main`), which is the mutation-safety leg (§二.4):
//
//   node node_modules/@playwright/test/cli.js test tests/stream-ux.spec.mjs
//   # red leg (never `git checkout main`; the working tree is untouched):
//   mkdir -p /tmp/nb-streamux-base && git archive main src/main/resources/web \
//     | tar -x -C /tmp/nb-streamux-base
//   mkdir -p /tmp/nb-streamux-base/tests/fixtures && \
//     cp -R tests/fixtures/stream-ux /tmp/nb-streamux-base/tests/fixtures/
//   cp -R tests/stream-ux.spec.mjs /tmp/nb-streamux-base/tests/  # optional
//   NB_UX_ROOT=/tmp/nb-streamux-base PIN_EXPECT=red \
//     node node_modules/@playwright/test/cli.js test tests/stream-ux.spec.mjs
//
// A1 工具运行期 badge 行含 Spinner + 工具名 / 完成瞬间打勾 / 切换滚动过渡
// A2 思考期单行流式揭示 → 完成打勾
// A3 助手回复整条出现 + 回复 DOM 内零逐字流式渲染（帧级读数）
// A4 用户输入气泡入场动画
// A5 终态：工作行交还给既有 `.turn-header` 展开面（与现状一致）
// A7 长回复不再逐帧 markdown 重排（mutation 读数）
//
// The static server is spawned and killed by this spec; the host :8080 is never
// touched (asserted by port, and by construction: the root is the tree this
// spec is pointed at).

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
// The served tree root (repo root on this branch; an exported baseline tree on
// the red leg). The URL shape stays identical because both trees carry the same
// `src/main/resources/web/...` layout.
const SERVE_ROOT = process.env.NB_UX_ROOT || REPO_ROOT;
const HARNESS_PATH = '/tests/fixtures/stream-ux/harness.html';
const SHOT_DIR = process.env.NB_UX_SHOT_DIR ||
  path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');

const TOOL_A = 'Grep("mergeQueue")';
const TOOL_B = 'Read("NodeTools.scala")';
// The indicator shows the tool's LOCALIZED label (the same first line the card
// carries), so the probe matches the localized substring, not the raw name.
const TOOL_A_TEXT = '搜索';
const TOOL_B_TEXT = '读取';

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

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  expect(SERVE_ROOT).not.toBe('');
  expect(fs.existsSync(path.join(SERVE_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(spawn('python3',
    ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', SERVE_ROOT],
    { stdio: 'ignore' }));
  const url = `http://127.0.0.1:${port}${HARNESS_PATH}`;
  for (let i = 0; i < 60; i++) {
    try { const res = await fetch(url); if (res.ok) break; } catch { /* not up */ }
    await new Promise(r => setTimeout(r, 100));
    if (i === 59) throw new Error(`static server never came up at ${url}`);
  }
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** The running-turn single-line indicator, detected WITHOUT naming its class:
 *  the top-level child of #chat that is neither a process row nor the terminal
 *  summary header. Baseline renders every live node as a `.row` (and the
 *  terminal `.turn-header`), so this reads null there. */
const READ_INDICATOR = () => {
  const chat = document.getElementById('chat');
  const el = Array.from(chat.children).find(k =>
    !k.classList.contains('row') && !k.classList.contains('turn-header'));
  if (!el) return null;
  const rect = el.getBoundingClientRect();
  const spins = el.querySelectorAll('.spinner, [class*="spin"]').length;
  const svgPaths = el.querySelectorAll('svg path').length;
  const anims = el.getAnimations({ subtree: true });
  // The slot is a single-line scroller; a rolling-out item is still in the DOM
  // for the duration of its exit animation. Read the labels as a LIST so the
  // probe can assert "the new tool arrived" without depending on whether the
  // outgoing item has already been detached.
  const labels = Array.from(el.querySelectorAll('*'))
    .filter(n => n.tagName === 'SPAN' && n.children.length === 0 && (n.textContent || '').trim())
    .map(n => n.textContent.trim());
  return {
    height: Math.round(rect.height),
    text: (el.textContent || '').trim(),
    labels,
    spins,
    svgPaths,
    errors: el.querySelectorAll('[class*="error"], [class*="err"]').length,
    animNames: anims.map(a => a.animationName || '(script)').sort(),
    running: anims.filter(a => a.playState === 'running' || a.playState === 'pending').length,
  };
};

async function indicator(page) {
  return page.evaluate(READ_INDICATOR);
}

/** Frame-level reading of the assistant reply bubble: child-element count,
 *  markup length and text length, sampled per animation frame. */
async function aiFrameStats(page) {
  return page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#chat .row.ai'))
      .filter(r => !r.classList.contains('thinking-row'));
    const row = rows[rows.length - 1];
    const b = row ? row.querySelector('.bubble.ai') : null;
    return {
      found: !!b,
      childElems: b ? b.children.length : -1,
      htmlLen: b ? b.innerHTML.length : -1,
      textLen: b ? (b.textContent || '').length : -1,
      animNames: b ? b.getAnimations().map(a => a.animationName || '(script)') : [],
      cls: b ? Array.from(b.classList).join('.') : '',
    };
  });
}

test.describe('A1 — 工具运行期单行 badge 行（Spinner → 打勾 → 翻动切换）', () => {
  test('spinner + tool name on one line; check on done; switch rolls to the next tool', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    await page.evaluate(() => window.__toolRun());
    expect(pageErrors).toEqual([]);
    const running = await indicator(page);
    expect(running, 'a single-line indicator exists OUTSIDE the process rows while a tool runs').not.toBeNull();
    expect(running.spins, 'it carries a running spinner').toBeGreaterThan(0);
    expect(running.labels.join(' '), 'and names the running tool (its localized label)')
      .toContain(TOOL_A_TEXT);
    expect(running.svgPaths, 'no check mark yet while the tool runs').toBe(0);
    expect(running.height, 'the badge is ONE line (never a card)').toBeLessThanOrEqual(48);
    expect(running.running, 'the spinner is a live CSS animation').toBeGreaterThan(0);
    await page.screenshot({ path: path.join(SHOT_DIR, 'streamux-a1-running.png') });

    // Done: the spinner is replaced by a drawn check, in place (same line).
    await page.evaluate(() => window.__toolDone());
    const done = await indicator(page);
    expect(done.spins, 'the spinner is gone once the tool completed').toBe(0);
    expect(done.svgPaths, 'a check glyph is drawn in its place').toBeGreaterThan(0);
    expect(done.height, 'still one line — the completion does not grow the row').toBeLessThanOrEqual(48);

    // Let the completion animation (roll-in 0.35s + the 0.1s-delayed bounce,
    // 0.5s total) finish. The exit trace below measures MOTION of this same
    // element, so a still-running entry animation would be credited to the
    // exit leg and mask a missing roll-out entirely.
    await page.waitForTimeout(650);

    // Switch: the badge rolls the outgoing item out and the next tool in.
    // The EXIT leg is traced frame-by-frame — a reading taken only after the
    // 380ms settle cannot tell "rolled up and out" from "sat still, then was
    // detached", because both leave the same final DOM. The user-visible
    // property the spec sentence promises (「旧上滚出」) is MOTION of the
    // outgoing item while it is still on screen, so that is what gets
    // asserted. Form-agnostic: no class of this batch is named — the item is
    // found by the label it carries. The switch is triggered INSIDE this same
    // evaluate so the sampler is provably already recording when it fires.
    const switchRead = await page.evaluate(async ({ textA, textB }) => {
      const chat = document.getElementById('chat');
      const ind = Array.from(chat.children).find(k =>
        !k.classList.contains('row') && !k.classList.contains('turn-header'));
      if (!ind) return null;
      const itemFor = (txt) => {
        const leaf = Array.from(ind.querySelectorAll('*')).find(n =>
          n.children.length === 0 && (n.textContent || '').includes(txt));
        return leaf ? leaf.parentElement : null; // the animated item box
      };
      const raf = () => new Promise(r => requestAnimationFrame(r));
      const outgoing = itemFor(textA);
      if (!outgoing) return null;
      // let the badge settle before the switch, so the only motion measured
      // belongs to the exit leg
      await raf(); await raf();
      const baseTop = outgoing.getBoundingClientRect().top;
      window.__toolNext();
      await raf(); // let the switch commit before reading the live animations
      const liveAnims = ind.getAnimations({ subtree: true })
        .filter(a => a.playState === 'running' || a.playState === 'pending').length;
      let minTop = baseTop;
      let sawRunning = false;
      let frames = 0;
      // Cross-fade guard: sample BOTH sides of the switch every frame. The
      // criterion is "no double exposure" — the two slot items must never be
      // simultaneously legible, the way a hard switch reads (old out, a beat
      // of nothing, new in). Drawn from computed opacity, not from any class
      // of this batch, so the assertion stays form-agnostic. A cross-fade
      // (the round-2 defect) shows up here as a long run of frames where both
      // opacities are > 0.05; a hard switch shows a run where NEITHER is.
      const opa = (el) => (el && el.isConnected) ? +getComputedStyle(el).opacity : 0;
      let bothFrames = 0;
      let peakOverlap = 0;
      let gapFrames = 0;
      // Ordering, in wall-clock terms, of the two visibility edges. Sampling a
      // frame COUNT is rAF-rate dependent and can read 0 under load even when
      // the switch is correct; the temporal order of the edges cannot.
      let tOut = null;  // the leaving item became invisible
      let tIn = null;   // the entering item became visible
      const t0 = performance.now();
      // Time-budgeted, not frame-budgeted: under load a frame count can expire
      // before the held-back entering item has even started, which would read
      // as a false pass. 1300ms covers rollout (0.35s) + the stagger + roll-in.
      for (let i = 0; i < 400 && performance.now() - t0 < 1300; i++) {
        if (outgoing.isConnected) {
          const top = outgoing.getBoundingClientRect().top;
          if (top < minTop) minTop = top;
          if (outgoing.getAnimations().some(a => a.playState === 'running')) sawRunning = true;
        }
        // the entering item is born by the switch, so re-resolve it each frame
        const incoming = itemFor(textB);
        const oa = opa(outgoing);
        const ob = opa(incoming);
        const dt = performance.now() - t0;
        if (tOut === null && oa <= 0.05) tOut = dt;
        if (tIn === null && ob > 0.05) tIn = dt;
        if (oa > 0.05 && ob > 0.05) {
          bothFrames++;
          // same-line slot: the two label boxes share x, so vertical overlap
          // is the legibility proxy the verifier scored (peak label overlap)
          const ra = outgoing.getBoundingClientRect();
          const rb = incoming.getBoundingClientRect();
          const ov = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top);
          if (ov > peakOverlap) peakOverlap = +ov.toFixed(1);
        }
        if (oa <= 0.05 && ob <= 0.05) gapFrames++;
        frames++;
        await raf();
      }
      const spins = ind.querySelectorAll('.spinner, [class*="spin"]').length;
      const labels = Array.from(ind.querySelectorAll('*'))
        .filter(n => n.tagName === 'SPAN' && n.children.length === 0 && (n.textContent || '').trim())
        .map(n => n.textContent.trim());
      return {
        exit: { baseTop: +baseTop.toFixed(2), minTop: +minTop.toFixed(2), frames, sawRunning },
        cross: {
          bothFrames, peakOverlap, gapFrames,
          tOut: tOut === null ? null : +tOut.toFixed(1),
          tIn: tIn === null ? null : +tIn.toFixed(1),
        },
        mid: { running: liveAnims, spins, labels },
      };
    }, { textA: TOOL_A_TEXT, textB: TOOL_B_TEXT });
    expect(switchRead, 'the switch was traceable').not.toBeNull();
    expect(switchRead.mid.running, 'the switch is an animation, not an instant swap').toBeGreaterThan(1);
    expect(switchRead.mid.labels.join(' '), 'the next tool is named on the line').toContain(TOOL_B_TEXT);

    const exit = switchRead.exit;
    expect(exit.frames, 'the exit was sampled over multiple animation frames').toBeGreaterThan(3);
    expect(exit.sawRunning, 'the outgoing item animates on its way out (it does not just vanish)').toBe(true);
    expect(exit.baseTop - exit.minTop,
      'the outgoing item ROLLS UP as it leaves (its top actually rises on screen)').toBeGreaterThan(2);

    // No double exposure: the two items must never be legible at once, and the
    // old one must be gone before the new one arrives (the demo's hard switch
    // reads as a visible gap, not a cross-fade). This is the round-2 criterion
    // the frame trace above exists to catch.
    expect(switchRead.cross.bothFrames,
      'the two slot items are never simultaneously visible (no cross-fade)').toBe(0);
    expect(switchRead.cross.peakOverlap,
      'the leaving and entering labels never overlap on screen').toBe(0);
    // Ordering, not frame count (rAF-rate independent): the leaving item is
    // fully gone BEFORE the entering one starts to appear. Under a cross-fade
    // both edges land at ~0ms — the entering item is already up while the
    // leaving one is still fading, so the gap between the two edges collapses.
    expect(switchRead.cross.tOut, 'the leaving item is observed going invisible').not.toBeNull();
    expect(switchRead.cross.tIn,
      'the entering item is held back until the leaving item has gone').toBeGreaterThanOrEqual(
      switchRead.cross.tOut + 100);

    // Settled: the outgoing item is gone; only the new tool owns the line.
    await page.waitForTimeout(600);
    const next = await indicator(page);
    expect(next.labels.join(' '), 'the badge switched to the next tool').toContain(TOOL_B_TEXT);
    expect(next.labels.join(' '), 'and the finished one rolled away').not.toContain(TOOL_A_TEXT);
    expect(next.spins, 'the new item is running again').toBeGreaterThan(0);
    expect(next.height, 'still one line after the switch').toBeLessThanOrEqual(48);
    expect(pageErrors).toEqual([]);

    await context.close();
  });

  test('a failed tool ends with the error ink (not a success check)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__toolRun());
    await page.evaluate(() => window.__toolFail());
    expect(pageErrors).toEqual([]);
    const failed = await indicator(page);
    expect(failed).not.toBeNull();
    expect(failed.errors, 'the failed badge carries an error-marked cell').toBeGreaterThan(0);
    expect(failed.spins, 'and it is no longer spinning').toBe(0);
    await context.close();
  });

  // The boundary leg: the incoming tool COMPLETES inside the switch's hold
  // window, i.e. while the outgoing item is still rolling away. The round-2
  // shape rebuilt the completing item as a fresh element and dropped its hold,
  // so the two labels re-appeared together (both-visible frames climbing as the
  // completion got earlier). The property under test is the SAME one as the
  // switch above — at most one item legible at a time — asserted to hold
  // independently of WHEN the completion lands, not only for a settled switch.
  test('a completion landing inside the switch hold still never double-exposes', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__toolRun());
    await page.evaluate(() => window.__toolDone());
    // let tool A's own completion animation settle, so the only motion measured
    // below is the switch's exit leg (same discipline as the test above)
    await page.waitForTimeout(650);

    const read = await page.evaluate(async ({ textA, textB, gapMs }) => {
      const chat = document.getElementById('chat');
      const ind = Array.from(chat.children).find(k =>
        !k.classList.contains('row') && !k.classList.contains('turn-header'));
      if (!ind) return null;
      const itemFor = (txt) => {
        const leaf = Array.from(ind.querySelectorAll('*')).find(n =>
          n.children.length === 0 && (n.textContent || '').includes(txt));
        return leaf ? leaf.parentElement : null;
      };
      const raf = () => new Promise(r => requestAnimationFrame(r));
      const sleep = (ms) => new Promise(r => setTimeout(r, ms));
      const opa = (el) => (el && el.isConnected) ? +getComputedStyle(el).opacity : 0;
      if (!itemFor(textA)) return null;

      window.__toolNext();          // switch A -> B (B enters behind A's roll-out)
      await raf();
      await sleep(gapMs);
      window.__toolBDone();         // B completes WHILE A is still leaving
      await raf();

      let bothFrames = 0;
      let peakOverlap = 0;
      let frames = 0;
      const t0 = performance.now();
      // Time-budgeted (rAF-rate independent), spanning the remaining roll-out,
      // B's held entry and B's completion bounce.
      for (let i = 0; i < 300 && performance.now() - t0 < 1100; i++) {
        const a = itemFor(textA);
        const b = itemFor(textB);
        const oa = opa(a);
        const ob = opa(b);
        if (oa > 0.05 && ob > 0.05) {
          bothFrames++;
          // same-line slot: the label boxes share x, so vertical overlap is the
          // legibility proxy (the reading the ENG verifier scored)
          const ra = a.getBoundingClientRect();
          const rb = b.getBoundingClientRect();
          const ov = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top);
          if (ov > peakOverlap) peakOverlap = +ov.toFixed(1);
        }
        frames++;
        await raf();
      }
      const box = (txt) => {
        const el = itemFor(txt);
        return el ? { op: opa(el), cls: el.className } : null;
      };
      return { bothFrames, peakOverlap, frames, a: box(textA), b: box(textB) };
    }, { textA: TOOL_A_TEXT, textB: TOOL_B_TEXT, gapMs: 120 });

    expect(read, 'the switch was traceable').not.toBeNull();
    expect(read.frames, 'the window was sampled over multiple animation frames').toBeGreaterThan(3);
    expect(read.bothFrames,
      'a completion inside the hold window never makes both items legible at once').toBe(0);
    expect(read.peakOverlap,
      'and the leaving and entering labels never overlap on screen').toBe(0);
    // and the completed tool is the one that ends up owning the line
    expect(read.b && read.b.cls, 'the completing tool settled into the slot').toContain('is-done');
    expect(read.b.op, 'and it is legible at the end of the window').toBeGreaterThan(0.05);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('A2 — 思考 = 单行流式揭示 → 打勾', () => {
  test('thinking reveals on ONE line and completes with a check', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__thinkingRun());
    expect(pageErrors).toEqual([]);

    const stream = await indicator(page);
    expect(stream, 'thinking owns the single-line indicator while it streams').not.toBeNull();
    expect(stream.text, 'the revealed line carries the thinking text').toContain('mergeQueue');
    expect(stream.height, 'the reveal is ONE line (no multi-line feed in the badge)').toBeLessThanOrEqual(48);
    expect(stream.running, 'the reveal is animated (CSS), not a static jump').toBeGreaterThan(0);

    // The full thinking feed still lives in the .row.thinking-row below (the
    // existing expand surface is untouched — spec §二.1.4).
    expect(await page.evaluate(() =>
      (document.querySelector('#chat .row.thinking-row .thinking-content')?.textContent || '')
        .includes('互斥闸'))).toBe(true);

    await page.evaluate(() => window.__thinkingDone());
    const done = await indicator(page);
    expect(done.spins, 'the thinking item stopped spinning').toBe(0);
    expect(done.svgPaths, 'and it completed with a drawn check').toBeGreaterThan(0);
    expect(done.height, 'still a single line after completion').toBeLessThanOrEqual(48);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('A3/A7 — 助手回复整条出现，气泡内零逐字渲染', () => {
  test('the reply bubble stays EMPTY for every streaming frame, then paints once', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__startAiSampler());
    await page.evaluate(() => window.__aiStream());

    // Frame-level reading #1: the bubble was alive across all the delta frames
    // (a row exists) but NEVER received per-token content.
    const mid = await aiFrameStats(page);
    expect(mid.found, 'the assistant row exists while its text streams').toBe(true);
    expect(mid.textLen, 'zero per-token text in the reply bubble (frame-level)').toBe(0);
    expect(mid.childElems, 'and zero per-token child elements').toBe(0);

    const frames = await page.evaluate(() => window.__stopAiSampler());
    expect(frames.length, 'the sampler actually sampled frames').toBeGreaterThan(3);
    const nonEmpty = frames.filter(f => f.htmlLen > 0);
    expect(nonEmpty.length, 'NO frame of the streaming window held rendered markdown').toBe(0);

    // Frame-level reading #2: the single paint happens at the end.
    await page.evaluate(() => window.__aiStreamFinish());
    expect(pageErrors).toEqual([]);
    const after = await aiFrameStats(page);
    expect(after.textLen, 'the whole reply appears at once').toBeGreaterThan(10);
    expect(after.htmlLen, 'and the bubble was painted (single render)').toBeGreaterThan(0);
    expect(after.animNames.length,
      'and it enters with an entrance animation (whole-message, not per-token)').toBeGreaterThan(0);
    await page.screenshot({ path: path.join(SHOT_DIR, 'streamux-a3-reply.png') });
    await context.close();
  });

  test('A7: zero DOM mutations inside the reply bubble while the text streams', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => {
      window.__mut = { count: 0, kinds: [] };
      window.__startMut = () => {
        window.__mut = { count: 0, kinds: [] };
      };
      window.__observe = () => {
        const chat = document.getElementById('chat');
        const obs = new MutationObserver((recs) => {
          for (const r of recs) {
            const t = r.target.nodeType === 1 ? r.target : r.target.parentElement;
            if (!t) continue;
            const row = t.closest('.row.ai');
            if (row && !row.classList.contains('thinking-row')) {
              window.__mut.count++;
              window.__mut.kinds.push(r.type);
            }
          }
        });
        obs.observe(chat, { childList: true, subtree: true, characterData: true });
        window.__obs = obs;
      };
      window.__stopObs = () => { if (window.__obs) window.__obs.disconnect(); return window.__mut; };
    });
    await page.evaluate(() => { window.__startMut(); window.__observe(); });
    await page.evaluate(() => window.__aiStream());
    const mut = await page.evaluate(() => window.__stopObs());
    expect(mut.count, 'the streaming window never touches the reply bubble subtree').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('A4 — 用户输入气泡入场动画', () => {
  test('the user bubble enters with its own animation', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userSlide());
    expect(pageErrors).toEqual([]);
    const anim = await page.evaluate(() => {
      const b = document.querySelector('#chat .row.user .bubble.user');
      if (!b) return null;
      return {
        exists: true,
        names: b.getAnimations().map(a => a.animationName || '(script)'),
        state: b.getAnimations().map(a => a.playState),
      };
    });
    expect(anim, 'the user bubble exists').not.toBeNull();
    expect(anim.names.filter(n => n !== '(script)').length,
      'the user bubble carries a CSS entrance animation').toBeGreaterThan(0);
    expect(anim.state.some(s => s === 'running' || s === 'pending'),
      'and it is actually playing on entry').toBe(true);
    await context.close();
  });
});

test.describe('A5 — 终态与现状一致（工作行交还既有展开面）', () => {
  test('the terminal leaves ONLY the existing .turn-header; its text and expand face are unchanged', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__fullTurnCollapse());
    expect(pageErrors).toEqual([]);

    // No transient chrome may survive the terminal: every top-level child is
    // either a process row or the summary header.
    const chatShape = await page.evaluate(() => {
      const chat = document.getElementById('chat');
      return {
        total: chat.children.length,
        stray: Array.from(chat.children)
          .filter(k => !k.classList.contains('row') && !k.classList.contains('turn-header'))
          .map(k => k.className),
        headers: chat.querySelectorAll(':scope > .turn-header').length,
      };
    });
    expect(chatShape.stray, 'the work-line is gone at the terminal').toEqual([]);
    expect(chatShape.headers, 'exactly the existing summary header remains').toBe(1);

    // The expand face is the unchanged, pre-existing one (spec §二.1.4).
    const headerText = (await page.locator('.turn-header-text').first().textContent() || '').trim();
    expect(headerText).toContain('test-model');
    expect(headerText).toContain('工具 1 次');
    const tucked = await page.locator('.nf-tucked').count();
    expect(tucked, 'the process rows are tucked behind the header').toBeGreaterThan(0);

    await page.locator('#chat > .turn-header').first().click();
    const expanded = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#chat .row.tool')).every(r => r.offsetHeight > 0));
    expect(expanded, 'clicking the header reveals every tool result (existing面不动)').toBe(true);
    await page.screenshot({ path: path.join(SHOT_DIR, 'streamux-a5-terminal.png') });
    await context.close();
  });

  test('a header-less turn (E6) still leaves no stray top-level node', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__thinkingOnlyTurn());
    expect(pageErrors).toEqual([]);

    const shape = await page.evaluate(() => {
      const chat = document.getElementById('chat');
      return {
        stray: Array.from(chat.children)
          .filter(k => !k.classList.contains('row') && !k.classList.contains('turn-header'))
          .map(k => k.className),
        headers: chat.querySelectorAll(':scope > .turn-header').length,
      };
    });
    // No header here (boundary default: nothing tuckable) — and, the point of
    // this leg, the transient work-line is gone all the same: the `readSeq`
    // contract of turn-collapse-keep-text.spec.mjs enumerates every top-level
    // child, so a leftover node would read as `other:nf-workline`.
    expect(shape.headers, 'a process-only-thinking turn renders no header').toBe(0);
    expect(shape.stray, 'and still no stray top-level node survives it').toEqual([]);
    await context.close();
  });
});

test.describe('animation events — CSS only (no WAAPI in the process chrome)', () => {
  test('the indicator animations are CSSAnimation objects, not script-driven', async ({ browser }) => {
    const { context, page } = await newPage(browser);
    await page.evaluate(() => window.__toolRun());
    const kinds = await page.evaluate(() => {
      const chat = document.getElementById('chat');
      const el = Array.from(chat.children).find(k =>
        !k.classList.contains('row') && !k.classList.contains('turn-header'));
      if (!el) return null;
      return el.getAnimations({ subtree: true }).map(a =>
        a instanceof CSSAnimation ? 'css' : 'script');
    });
    expect(kinds).not.toBeNull();
    expect(kinds.length, 'the running badge animates').toBeGreaterThan(0);
    expect(kinds.every(k => k === 'css'),
      'every badge animation is CSS (WAAPI is pinned out by the turn-* specs)').toBe(true);
    await context.close();
  });
});
