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
// A1 工具运行期 badge 行含 Spinner + 工具名 / 完成瞬间打勾 / 切换淡出过渡（UI-C 纯文本化后：无变换类滚动）
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
  test('spinner + tool name on one line; check on done; switch fades the outgoing item to the next tool', async ({ browser }) => {
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

    // Let the completion animation (the 0.3s text fade-in, `.nf-wl-item.is-done`)
    // finish. The exit trace below measures this same element's fade, so a
    // still-running entry animation would be credited to the exit leg and mask
    // a missing fade-out entirely.
    await page.waitForTimeout(650);

    // Switch: the badge FADES the outgoing item out and the next tool in.
    // (UI-C, 2026-10-03: the retired layer ROLLED the item up — a CSS
    // transform; the current layer is PURE TEXT and fades, so the exit leg is
    // asserted as a fade, never as vertical motion.)
    // The EXIT leg is traced frame-by-frame — a reading taken only after the
    // settle cannot tell "faded out while still on screen" from "sat still,
    // then was detached", because both leave the same final DOM. The
    // user-visible property the spec sentence promises (「旧上滚出」) is now
    // 「旧淡出」: the outgoing item's opacity falls while it is STILL connected.
    // Form-agnostic: no class of this batch is named — the item is found by the
    // label it carries. The switch is triggered INSIDE this same evaluate so the
    // sampler is provably already recording when it fires.
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
      const startOpa = outgoing.isConnected ? +getComputedStyle(outgoing).opacity : 0;
      let minTop = baseTop;
      let minConnectedOpa = startOpa;
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
          const oo = +getComputedStyle(outgoing).opacity;
          if (oo < minConnectedOpa) minConnectedOpa = oo;
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
        exit: {
          baseTop: +baseTop.toFixed(2), minTop: +minTop.toFixed(2),
          startOpa: +startOpa.toFixed(2), minConnectedOpa: +minConnectedOpa.toFixed(2),
          frames, sawRunning,
        },
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
    // UI-C (2026-10-03): the exit is a PURE-TEXT fade — the leaving item's
    // opacity actually falls while it is still connected. A still item that is
    // then detached would read minConnectedOpa ≈ startOpa (≈1); a fade reads
    // it near 0. Vertical motion is NOT required any more (the retired layer
    // rolled the item; the current layer fades it).
    expect(exit.minConnectedOpa,
      `the outgoing item FADES OUT on its way out (opacity ${exit.startOpa} → ${exit.minConnectedOpa} while still on screen)`)
      .toBeLessThan(0.4);

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

    // The full thinking feed still accumulates in the .row.thinking-row below
    // (pre-tucked since the 2026-10-03 redesign — the expand face renders
    // into the DOM while the line carries the reveal).
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

  test('a thinking-only turn (E6) settles the line into its own header (no stray node)', async ({ browser }) => {
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
        headerText: (chat.querySelector(':scope > .turn-header')?.textContent || '').trim(),
      };
    });
    // stream-ux redesign (2026-10-03): the thinking-only turn's process ran on
    // the line, so the line settles into its OWN header (思考 stats, no 工具
    // segment) — the old "no header for E6" default is gone with the run-time
    // pre-tuck (the thinking row is never visible during the run any more).
    // The readSeq contract still holds: the settled header is a `.turn-header`
    // top-level node, so no stray survives.
    expect(shape.headers, 'the E6 turn still gets its own settled header').toBe(1);
    expect(shape.headerText, 'the badge carries the model + 思考 segment').toContain('test-model');
    expect(shape.headerText).toContain('思考');
    expect(shape.stray, 'and no stray top-level node survives it').toEqual([]);

    // The settled header expands the pre-tucked thinking row (the expand face).
    await page.locator('#chat > .turn-header').first().click();
    expect(await page.evaluate(() => {
      const row = document.querySelector('#chat .row.thinking-row');
      return row ? row.offsetHeight > 0 : false;
    })).toBe(true);
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

/* ═══════════ A6 — zcode-484: the single-line streaming window ═══════════
 *
 * Author ruling 2026-10-04 09:29, verbatim:
 *   「我要的思考过程以及工具过程的流式，是在一行进行流式，像zcode一样。而且现在
 *    一次Pop多个图片和视频进行折叠的效果还有问题。」
 *
 * Acceptance (batch brief §4, mechanical):
 *   1. while streaming, the line container's `clientHeight` is CONSTANT at one
 *      row — feeding many deltas never grows it vertically;
 *   2. the row end fades (a tail `mask-image`);
 *   3. on completion the line collapses to a one-row checked badge;
 *   4. the FULL thinking text stays reachable.
 *
 * The readings below name NO class introduced by this batch (they go through the
 * batch-agnostic `__lineGeometry`, which finds the live line the same way
 * READ_INDICATOR does), so the SAME file is RED on the pre-batch tree.
 */

test.describe('A6 — 思考单行流式（zcode-484）', () => {
  test('the slot height is CONSTANT across a multi-delta feed (never grows vertically)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(() => window.__thinkSingleLineFeed(12));
    expect(read, 'the feed built a live line').not.toBeNull();
    expect(read.frames, 'every frame found the line').toBe(12);
    expect(read.textLenGrew, 'the text really kept growing (constancy must not be vacuous)').toBe(true);
    expect(read.distinctSlotH.length,
      `the slot height never changed across the whole feed (saw ${JSON.stringify(read.distinctSlotH)})`)
      .toBe(1);
    expect(read.distinctSlotComputedH.length,
      `nor did its computed height (saw ${JSON.stringify(read.distinctSlotComputedH)})`).toBe(1);
    expect(read.distinctThinkH.length,
      `nor the text box (saw ${JSON.stringify(read.distinctThinkH)})`).toBe(1);
    // The single row is exactly one line box high (the padding lives on the row,
    // not on the slot), which is what "strictly one row" means.
    expect(read.maxSlotH, 'the line is one text row high')
      .toBeLessThanOrEqual(read.last.thinkLineHeight * 1.2 + 2);
    expect(read.last.whiteSpace, 'and it forbids wrapping').toBe('nowrap');
    expect(read.last.overflow, 'and clips its overflow').toBe('hidden');
    expect(read.last.slotOverflow, 'the slot clips too').toBe('hidden');
    // The tail fade: new content flows in at the row end, old slides out left.
    expect(read.last.maskImage, `a tail fade is applied (was: ${read.last.maskImage})`).not.toBe('none');
    expect(read.last.scrollW, 'the text overflows the box (so the anchor matters)')
      .toBeGreaterThan(read.last.clientW);
    expect(read.last.tailAnchored, 'and the box stays pinned to its right edge (the tail anchor)').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('the TOOL-process row gets the same single-row treatment', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(() => window.__toolSingleLineFeed());
    expect(read, 'the tool feed built a live line').not.toBeNull();
    expect(read.frames, 'three successive tool labels were rendered').toBe(3);
    expect(read.distinctSlotH.length,
      `the slot stayed one row high across every tool item (saw ${JSON.stringify(read.distinctSlotH)})`)
      .toBe(1);
    expect(read.distinctSlotComputedH.length,
      `nor did its computed height change (saw ${JSON.stringify(read.distinctSlotComputedH)})`).toBe(1);
    // The LABEL is the tool face's text box — the very box the pre-batch rule
    // made wrap (`white-space: normal; overflow-wrap: anywhere`). A long label
    // must stay one row and be clipped, with the same tail fade.
    expect(read.last.labelTextLen, 'the label really is long (otherwise the clip is vacuous)')
      .toBeGreaterThan(80);
    expect(read.last.labelWhiteSpace, 'the tool label forbids wrapping').toBe('nowrap');
    expect(read.last.labelOverflow, 'and clips its overflow').toBe('hidden');
    expect(read.distinctLabelH.length,
      `the label box never grew a second row (saw ${JSON.stringify(read.distinctLabelH)})`).toBe(1);
    expect(read.last.labelScrollW, 'the label overflows its box (clipped, not shrunk)')
      .toBeGreaterThan(read.last.labelClientW);
    expect(read.last.labelMaskImage,
      `the tool label carries the same tail fade (was: ${read.last.labelMaskImage})`).not.toBe('none');
    // The completion face lands on that same single row.
    expect(read.done, 'the completed face was readable').not.toBeNull();
    expect(read.done.slotH, 'the check face is still one row').toBe(read.maxSlotH);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('I1: the stream APPENDS to the tail node — the #481 rewrite ban still holds', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(() => window.__appendOnlyProbe(8));
    expect(read, 'the probe found the thinking node').not.toBeNull();
    expect(read.frames, 'the feed ran eight deltas').toBe(8);
    // The #481 commit 2706962e2's invariant, read from the DOM: the FIRST text
    // node object survives the whole stream, still at index 0, its data only
    // growing. A per-delta `textContent = whole` write would replace the node.
    expect(read.allSameNode,
      'the first text node is never replaced (no whole-line rewrite per delta)').toBe(true);
    expect(read.allStillFirst, 'and it stays the leading node of the line').toBe(true);
    expect(read.nodeIndexes, 'it never moves').toEqual([0]);
    expect(read.firstLenMonotone, 'its data only ever grew (append-only)').toBe(true);
    expect(read.totalTextLen, 'the full text accumulated on the line')
      .toBeGreaterThan(8 * 15);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('completion collapses the line to a one-row checked badge; full text stays reachable', async ({ browser }) => {    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(() => window.__thinkCollapseRead());
    expect(read.liveSlotH, 'the line was live and one row before completion').toBeGreaterThan(0);
    expect(read.badgeH, 'the completed badge is one row high (never a card)')
      .toBeLessThanOrEqual(read.liveSlotH + 2);
    expect(read.spins, 'the spinner retired').toBe(0);
    expect(read.svgPaths, 'and a check glyph is drawn').toBeGreaterThan(0);
    expect(read.labelText.length, 'the badge carries the settled summary text').toBeGreaterThan(0);
    // The full thinking feed is still reachable through the pre-tucked row.
    expect(read.fullTextReachable,
      'the full thinking text remains reachable (the expand interaction is untouched)').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// A6b — the Pop running row's file-path progress (zcode-484 rework).
//
// WHY: commit b3c0851c8 claimed the running Pop row shows the deliverable path
// while its arguments stream, and the plan (OD-5 / §3.3-4) asks for the "first
// path (+N)" summary for the ARRAY form — the batch Pop shape the requirement
// is about. The first cut only fixed the single-string form: the extractor
// bailed with `null` as soon as the first non-space char after the colon was
// not `"`, so a `[` built NO `.tool-stream-body` at all. These two cases pin
// BOTH forms against the real render path (renderToolPending →
// appendToolStreamDelta), which had ZERO coverage before (repo-wide: no test
// referenced `TOOL_PRIMARY_FIELDS`, `.tool-stream-body` or `'Pop'`).
//
// Red leg: the baseline tree has no `'Pop'` entry in `TOOL_PRIMARY_FIELDS`, so
// BOTH cases fail there (no body node is ever created) — see the `git archive`
// recipe in the file header, never `git checkout main`.
// ─────────────────────────────────────────────────────────────────────────────
test.describe('A6b — Pop 运行期行内文件路径进展（数组 / 单值双形态）', () => {
  test('the SINGLE-string Pop form shows its path on the running row', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const payload = JSON.stringify({ filePath: '/tmp/lone.png' });
    const read = await page.evaluate(
      ([tool, p]) => window.__toolArgStreamRead(tool, p), ['Pop', payload]);
    expect(read.found, 'the pending Pop row was created').toBe(true);
    expect(read.bodyExists, 'the running row built its streaming body').toBe(true);
    expect(read.visible, 'and the body is visible when the turn is expanded').toBe(true);
    expect(read.text, 'it shows the file path').toContain('/tmp/lone.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('the ARRAY Pop form shows its first path (+N) on the running row', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const payload = JSON.stringify({
      filePath: ['/tmp/alpha.png', '/tmp/beta.png', '/tmp/gamma.png'],
    });
    const read = await page.evaluate(
      ([tool, p]) => window.__toolArgStreamRead(tool, p), ['Pop', payload]);
    expect(read.found, 'the pending Pop row was created').toBe(true);
    // The regression this pins: the array form used to build NO body (the
    // extractor returned null on `[`), so the expanded running turn showed
    // nothing while the single-string form showed its path.
    expect(read.bodyExists, 'the ARRAY form builds the same streaming body').toBe(true);
    expect(read.visible, 'and the body is visible when the turn is expanded').toBe(true);
    expect(read.text, 'it shows the first path').toContain('/tmp/alpha.png');
    expect(read.text, 'and counts the further paths as +2').toContain('+2');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
