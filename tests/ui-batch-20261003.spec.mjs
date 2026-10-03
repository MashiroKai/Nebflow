// ui-batch-20261003.spec.mjs — the 2026-10-03 UI batch (UI-A…UI-G) regression net.
//
// Author rulings this file pins (verbatim, from the batch brief §3):
//   UI-A  「思考过程的那一行要能换行显示，而不是一直是一行」
//   UI-B  「Badge 按轮次切分没有做的很好」
//   UI-C  「动画最好以纯文本的形式来做，符合Badge原有的风格」
//   UI-G  「只需要主Nebula有流式输出，气泡动画等效果，subagent窗口要尽量克制，节省资源。」
//
// Every probe reads the RENDERED DOM through the real modules, driven by
// tests/fixtures/turn-collapse/harness.html over a throwaway static server
// this spec spawns and kills itself (never the 8080 host).
//
// UI-B's judgement surface is worth stating explicitly, because the wording
// 「按轮次切分」 invites the wrong reading. 切分 is a QUESTION OF TURN SCOPE,
// not of splitting one turn into several headers: the pinned regression
// tests/turn-single-badge.spec.mjs forbids two headers for a multi-round turn,
// and that ruling stands. What was wrong is the ROW-LEVEL round attribution and
// the LINE LEAK across turns — a second turn's live items were appended into
// the FIRST turn's already-settled badge. B1 below is exactly that shape.
//
// Run: node node_modules/@playwright/test/cli.js test tests/ui-batch-20261003.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/turn-collapse/harness.html';

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

async function newPage(browser) {
  const context = await browser.newContext({ viewport: { width: 1000, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors };
}

/* ═══════════ UI-A — the thinking / work line WRAPS ═══════════ */

test.describe('UI-A — 思考行可换行', () => {
  test('A1: a long thinking text wraps onto multiple rows instead of clipping to one', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(() => window.__wrapRead());
    expect(read, 'the work line carried a thinking item').not.toBeNull();
    expect(read.whiteSpace, 'the think box no longer forbids wrapping').toBe('normal');
    expect(read.overflow, 'and no longer clips its overflow').not.toBe('hidden');
    expect(read.rows, 'the text really occupies more than one text row').toBeGreaterThan(1);
    expect(read.lineHeight, 'the line itself grew past a single row')
      .toBeGreaterThan(read.lineHeightPx * 1.5);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('A2: wrapping does not break the switch interaction (chevron + slot intact)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(() => window.__wrapRead());
    expect(read.hasChevron, 'the chevron survives the wrap (the toggle face)').toBe(true);

    // The toggle face must still work: complete the turn and expand it.
    const toggle = await page.evaluate(async () => {
      const chatMod = await import('/src/main/resources/web/js/chat.js');
      const turnMod = await import('/src/main/resources/web/js/turnGroup.js');
      const { chatViews } = await import('/src/main/resources/web/js/chatView.js');
      const view = chatViews.primary;
      const chat = document.getElementById('chat');
      chatMod.finishThinking();
      chatMod.appendAiText('换行之后仍然可以收起与展开。');
      chatMod.finishAi(1200, 'test-model');
      turnMod.collapseTurn(view, { durationMs: 1200, model: 'test-model', phrase: '', title: 't', sessionId: 'harness-session' });
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
      const header = chat.querySelector('.turn-header');
      const tuckedAfterCollapse = chat.querySelectorAll('.nf-tucked').length;
      header.click();
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
      const tuckedAfterExpand = chat.querySelectorAll('.nf-tucked').length;
      return {
        hasHeader: !!header,
        ariaExpanded: header.getAttribute('aria-expanded'),
        tuckedAfterCollapse,
        tuckedAfterExpand,
      };
    });
    expect(toggle.hasHeader, 'the wrapped line settled into the turn header').toBe(true);
    expect(toggle.tuckedAfterCollapse, 'collapsing still tucks the process rows').toBeGreaterThan(0);
    expect(toggle.tuckedAfterExpand, 'expanding still reveals them').toBeLessThan(toggle.tuckedAfterCollapse);
    expect(toggle.ariaExpanded, 'and the toggle state is published').toBe('true');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ UI-B — round attribution ═══════════ */

test.describe('UI-B — 轮次切分', () => {
  test('B1: a second turn builds its OWN badge — no item leaks into the first', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__twoTurnsEachWithTool());
    const read = await page.evaluate(() => window.__roundSplit());

    expect(read.headers.length, 'each turn settled its own header').toBe(2);
    // The decisive assertion: the FIRST turn's header must hold exactly its own
    // final item. Before the fix the settle left `wl.row` pointing at the
    // header, so turn 2's line items were appended INSIDE turn 1's badge.
    for (const h of read.headers) {
      expect(h.items, `header "${h.text}" holds exactly one item (its own final)`)
        .toBe(1);
    }
    expect(read.headers[0].text, 'header 1 counts turn 1 only').toContain('工具 1 次');
    expect(read.headers[1].text, 'header 2 counts turn 2 only').toContain('工具 1 次');
    expect(read.liveLine, 'no live line survives a settled turn').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('B2: rows carry the round they belong to; rounds advance on the boundary', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__multiRoundTurn());
    const read = await page.evaluate(() => window.__roundSplit());

    // One turn, two LLM rounds ⇒ ONE badge (tests/turn-single-badge.spec.mjs
    // pins the same rule; this is the UI-B reading of it).
    expect(read.headers.length, 'a multi-round turn still produces exactly ONE badge').toBe(1);
    expect(read.headers[0].text, 'and it counts the whole turn').toContain('工具 9 次');

    const rounds = read.rowRounds.map(r => r.round);
    const tagged = rounds.filter(r => r !== null && r !== undefined);
    expect(tagged.length, 'the live render path stamps every process row').toBeGreaterThan(0);
    expect(tagged.every(r => /^\d+$/.test(String(r))),
      `every stamped row carries a numeric round; got ${JSON.stringify(tagged)}`).toBe(true);
    // The two LLM rounds must actually be distinguishable in the DOM.
    expect(new Set(tagged).size,
      `the split is readable: rounds ${JSON.stringify([...new Set(tagged)])} present`)
      .toBeGreaterThan(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ UI-C — pure-text animation ═══════════ */

test.describe('UI-C — 纯文本动画', () => {
  test('C1: the badge animates through TEXT/CSS only — no transform keyframes', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__toolRunHere());

    const read = await page.evaluate(() => {
      const line = document.querySelector('#chat .nf-workline');
      if (!line) return null;
      const spin = line.querySelector('.nf-wl-spin');
      const spinBefore = spin ? getComputedStyle(spin, '::before').content : '';
      const anims = line.getAnimations({ subtree: true });
      const names = anims.map(a => a.animationName || '(script)');
      // Every animation running in the line, resolved to its keyframe text.
      const frames = [];
      for (const a of anims) {
        const kf = a.effect && a.effect.getKeyframes
          ? a.effect.getKeyframes().map(k => JSON.stringify(k)).join(' ')
          : '';
        frames.push({ name: a.animationName, transform: /translate|scale|rotate|matrix/i.test(kf) });
      }
      return {
        spinHasGlyph: spinBefore && spinBefore !== 'none' && spinBefore !== 'normal',
        animations: names,
        transformFrames: frames.filter(f => f.transform).map(f => f.name),
      };
    });

    expect(read, 'the work line exists').not.toBeNull();
    expect(read.spinHasGlyph, 'the spinner is a TEXT glyph (a ::before content)').toBe(true);
    expect(read.animations.length, 'the line still animates (pure text/form)').toBeGreaterThan(0);
    expect(read.transformFrames,
      `NO keyframe moves/rotates/scales anything; offenders: ${JSON.stringify(read.transformFrames)}`)
      .toEqual([]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
