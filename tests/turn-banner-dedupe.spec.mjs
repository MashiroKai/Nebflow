// turn-banner-dedupe.spec.mjs — 2026-09-04 user ruling (turn 统计标语条去重).
//
// Ruling: the decorative per-turn stats bar (`.turn-summary` — ✻ phrase ·
// model · 工具 N 次) stacked one-per-run-group in multi-turn sessions. The
// session must show only the LATEST turn's bar(s): a newly completed turn's
// bars replace all earlier turns' bars visually; history replay renders only
// the last banner. Render-level ONLY — superseded bars stay in the DOM
// (collapse structure + E10 search-expand intact) hidden via a marker class
// on the GROUP element, because closed groups' innerHTML must stay
// byte-stable (#403 invariant, asserted by turn-collapse-keep-text 验收 b).
//
// Boundary (documented deviation, spec-safe): the LATEST TURN keeps all of
// its run-group bars (turn-collapse-keep-text 验收 a asserts per-run bars and
// clicks the first one) — with single-run turns (the common case) the
// session therefore shows exactly one bar. 落盘数据与后端零改动；过程文字
// （thinking/tool/injected/AI text）渲染路径一字不动。
//
// Drives the REAL render modules through tests/fixtures/turn-collapse/
// harness.html over a throwaway static server (never the 8080 host).
//
// Run: npx playwright test tests/turn-banner-dedupe.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/turn-collapse/harness.html';
const SHOT_DIR = process.env.NB_BANNER_SHOT_DIR || '/tmp/nb-stat-banner-dedupe/shots';

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

async function waitUntilUp(url, tries = 50) {
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
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  port = await freePort();
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 900, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** Banner census: per-bar visibility + text, group marker classes. */
function bannerCensus(page) {
  return page.evaluate(() => {
    const bars = Array.from(document.querySelectorAll('.turn-group > .turn-summary'));
    return {
      total: bars.length,
      visibleTexts: bars
        .filter(b => b.offsetHeight > 0)
        .map(b => (b.querySelector('.turn-summary-text')?.textContent || '').trim()),
      hidden: bars.filter(b => b.offsetHeight === 0).length,
      markedGroups: document.querySelectorAll('.turn-group.turn-banner-superseded').length,
    };
  });
}

test.describe('banner dedupe — live multi-turn path', () => {
  test('second turn hides the first turn bars; exactly one visible bar with the latest stats', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());

    // Turn 1 (2 run-groups): both bars visible — current turn keeps its runs.
    let c = await bannerCensus(page);
    expect(c.total).toBe(2);
    expect(c.visibleTexts.length).toBe(2);

    const innerHTMLBefore = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-group')).map(g => g.innerHTML));

    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);

    // Turn 2 done: exactly ONE visible bar — turn 2's own stats.
    c = await bannerCensus(page);
    expect(c.total).toBe(3);            // superseded bars stay in the DOM
    expect(c.visibleTexts.length).toBe(1);
    expect(c.hidden).toBe(2);
    expect(c.markedGroups).toBe(2);     // marker on the GROUP element…
    const marked = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-group.turn-banner-superseded'))
        .map(g => !!g.querySelector('.turn-summary')));
    expect(marked).toEqual([true, true]);
    // …and the visible bar belongs to turn 2 (phrase + model + tool count).
    expect(c.visibleTexts[0]).toBe('✻ 快速确认 2 秒 · test-model · 工具 1 次');

    // #403 byte-stability: the marker never touches children — closed
    // groups' innerHTML is identical to before the second turn.
    const innerHTMLAfter = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-group')).map(g => g.innerHTML));
    expect(innerHTMLAfter[0]).toBe(innerHTMLBefore[0]);
    expect(innerHTMLAfter[1]).toBe(innerHTMLBefore[1]);

    // 过程文字红线: every assistant text row still visible, original order.
    const texts = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#chat .row.ai'))
        .filter(r => !r.classList.contains('thinking-row') && !r.closest('.turn-steps'))
        .map(r => ({ t: (r.querySelector('.bubble.ai')?.textContent || '').trim(), v: r.offsetHeight > 0 })));
    expect(texts.map(x => x.t)).toEqual([
      '好的，我先检索资料，再逐段汇报进展。',
      '找到关键线索了：收起分组在 turnGroup.js，继续核对细节。',
      '这是最终回复：中间文字全部保留，工具过程已折叠。',
      '新回合的最终回复。',
    ]);
    expect(texts.every(x => x.v)).toBe(true);

    await context.close();
  });

  test('superseded group still expands via E10 search; its bar stays hidden', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    await page.evaluate(() => window.__postClosureTurn());
    expect(await bannerCensus(page)).toMatchObject({ visibleTexts: ['✻ 快速确认 2 秒 · test-model · 工具 1 次'] });

    // E10 entry point on a tool row inside the FIRST (superseded) group.
    const r = await page.evaluate(() => {
      const row = document.querySelector('.turn-group .turn-steps .row.tool');
      const steps = row.closest('.turn-steps');
      const before = steps.style.display;
      const expanded = window.__expandContaining(row);
      return { before, expanded, after: steps.style.display };
    });
    expect(r.before).toBe('none');
    expect(r.expanded).toBe(true);
    expect(r.after).toBe('');

    // Steps are visible; the superseded bar is NOT.
    const c = await bannerCensus(page);
    expect(c.visibleTexts.length).toBe(1);
    expect(await page.locator('.turn-group').first().locator('.turn-steps .row.tool').evaluate(el => el.offsetHeight > 0)).toBe(true);
    expect(await page.locator('.turn-group').first().locator('.turn-summary').evaluate(el => el.offsetHeight === 0)).toBe(true);

    await context.close();
  });

  test('text-only turn builds no bar and leaves the previous latest bar visible', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);

    // A zero-process turn (E5): collapseTurn runs, builds nothing — the
    // latest bar must NOT be superseded by a bar that does not exist.
    await page.evaluate(() => window.__textOnlyTurn());
    expect(pageErrors).toEqual([]);
    let c = await bannerCensus(page);
    expect(c.total).toBe(3);              // no fourth bar appeared
    expect(c.visibleTexts).toEqual(['✻ 快速确认 2 秒 · test-model · 工具 1 次']);

    // Same guarantee through the history path: last turn is text-only.
    const T = 1735689600000;
    await page.evaluate(m => window.__history(m, { busyTail: false }), [
      { type: 'user', text: '第一轮', timestamp: T },
      { type: 'tool', label: 'Bash("ls")', summary: 'ok', content: 'out', isError: false, input: '{"command":"ls"}' },
      { type: 'ai', text: '第一轮完成。', durationMs: 30000, model: 'm-one', timestamp: T + 1000 },
      { type: 'user', text: '第二轮（纯文字）', timestamp: T + 2000 },
      { type: 'ai', text: '纯文字收尾。', durationMs: 5000, model: 'm-two', timestamp: T + 3000 },
    ]);
    expect(pageErrors).toEqual([]);
    // The text-only turn renders no bar; turn 1's bar remains the latest one.
    c = await bannerCensus(page);
    expect(c.total).toBe(1);
    expect(c.visibleTexts.length).toBe(1);
    expect(c.visibleTexts[0]).toContain('m-one');

    await context.close();
  });
});

test.describe('banner dedupe — history replay path (restoreFromBackendHistory / restoreFromStorage)', () => {
  test('multi-turn history renders only the last banner with the latest turn stats', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const T = 1735689600000;
    await page.evaluate(m => window.__history(m, { busyTail: false }), [
      { type: 'user', text: '第一轮：调研两个工具。', timestamp: T },
      { type: 'tool', label: 'Read("a.md")', summary: '10 lines', content: 'body', isError: false, input: '{"file_path":"a.md"}' },
      { type: 'tool', label: 'Bash("ls")', summary: 'ok', content: 'out', isError: false, input: '{"command":"ls"}' },
      { type: 'ai', text: '第一轮完成。', durationMs: 93000, model: 'zhipu/GLM-5.3-Flash', timestamp: T + 93000 },
      { type: 'user', text: '第二轮：只查一处。', timestamp: T + 100000 },
      { type: 'tool', label: 'Grep("p")', summary: '1 hit', content: 'hit', isError: false, input: '{"pattern":"p"}' },
      { type: 'ai', text: '第二轮完成，收工。', durationMs: 69000, model: 'zhipu/GLM-5.3-Flash', timestamp: T + 169000 },
    ]);
    expect(pageErrors).toEqual([]);

    const c = await bannerCensus(page);
    expect(c.total).toBe(2);            // both bars in DOM…
    expect(c.visibleTexts.length).toBe(1); // …only the LAST turn's renders
    expect(c.markedGroups).toBe(1);
    // Latest turn's stats: duration 1m 9s (random phrase embeds it), model,
    // and that turn's tool count — none of turn 1's (1m 33s / 工具 2 次).
    expect(c.visibleTexts[0]).toContain('1m 9s');
    expect(c.visibleTexts[0]).toContain('zhipu/GLM-5.3-Flash');
    expect(c.visibleTexts[0]).toContain('工具 1 次');
    expect(c.visibleTexts[0]).not.toContain('1m 33s');
    expect(c.visibleTexts[0]).not.toContain('工具 2 次');

    // 过程文字红线: all four history text rows visible in order.
    const texts = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#chat .row.ai'))
        .filter(r => !r.classList.contains('thinking-row') && !r.closest('.turn-steps'))
        .map(r => (r.querySelector('.bubble.ai')?.textContent || '').trim()));
    expect(texts).toEqual(['第一轮完成。', '第二轮完成，收工。']);

    const shot = path.join(SHOT_DIR, 'spec-replay-single-banner.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);

    await context.close();
  });

  test('refresh mid-turn (busyTail) keeps the last closed bar; next live done supersedes it', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const T = 1735689600000;
    await page.evaluate(m => window.__history(m, { busyTail: true }), [
      { type: 'user', text: '第一轮', timestamp: T },
      { type: 'tool', label: 'Bash("ls")', summary: 'ok', content: 'out', isError: false, input: '{"command":"ls"}' },
      { type: 'ai', text: '第一轮完成。', durationMs: 30000, model: 'm-one', timestamp: T + 1000 },
      { type: 'user', text: '第二轮（刷新时还在跑）', timestamp: T + 2000 },
      { type: 'ai', text: '流式中间文字。' },
      { type: 'tool', label: 'Read("b.md")', summary: '2 lines', content: 'body', isError: false, input: '{"file_path":"b.md"}' },
    ]);
    expect(pageErrors).toEqual([]);

    // Open turn has no bar yet; the last CLOSED turn's bar stays visible.
    let c = await bannerCensus(page);
    expect(c.total).toBe(1);
    expect(c.visibleTexts[0]).toContain('m-one');

    // The open turn completes (live terminal on top of a rebuilt session):
    // its bar becomes the only visible one; the history bar is superseded.
    // (The flat tail [ai text, tool] + the new injected/tool land in ONE
    // contiguous collapsible run → one group carrying both tool cards.)
    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);
    c = await bannerCensus(page);
    expect(c.total).toBe(2);
    expect(c.visibleTexts).toEqual(['✻ 快速确认 2 秒 · test-model · 工具 2 次']);
    expect(c.markedGroups).toBe(1);

    await context.close();
  });
});
