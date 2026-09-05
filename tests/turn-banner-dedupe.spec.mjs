// turn-banner-dedupe.spec.mjs — 2026-09-04 user ruling (turn 统计标语去重),
// updated for the #346 v2 "decompression model" (2026-09-05 23:44 ruling).
//
// Ruling: the per-turn stats header (`.turn-header` — model · 思考 Ns ·
// 工具 M 次 · 读写 K 文件) must not stack one-per-turn. The session shows
// only the LATEST turn's header: a newly completed turn's header replaces
// all earlier turns' headers visually; history replay renders only the last
// one. Render-level ONLY — superseded headers stay in the DOM (E10
// search-expand intact) hidden via a marker class on the HEADER element;
// rows never move, so a closed turn's DOM stays byte-stable (#403 invariant).
//
// v2 note: the ✻ phrase is gone from the header text (superseded by the
// stats model: model · 思考 · 工具 · 读写); the dedupe mechanism itself is
// unchanged. 落盘数据与后端零改动；过程行（thinking/tool/injected/AI text）
// 渲染路径一字不动。
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

/** Header census: per-header visibility + text, marker classes. */
function bannerCensus(page) {
  return page.evaluate(() => {
    const bars = Array.from(document.querySelectorAll('.turn-header'));
    return {
      total: bars.length,
      visibleTexts: bars
        .filter(b => b.offsetHeight > 0)
        .map(b => (b.querySelector('.turn-header-text')?.textContent || '').trim()),
      hidden: bars.filter(b => b.offsetHeight === 0).length,
      markedGroups: document.querySelectorAll('.turn-header.turn-banner-superseded').length,
    };
  });
}

/** Row-only DOM snapshot (headers excluded): for #403 byte-stability checks.
 *  The banner-dedupe marker class legitimately changes a closed turn's
 *  HEADER attributes; the rows themselves must never change. */
function rowsSnapshot(page) {
  return page.evaluate(() =>
    Array.from(document.getElementById('chat').children)
      .filter(el => !el.classList.contains('turn-header'))
      .map(el => el.outerHTML).join('\n'));
}

/** Wait until every tool/thinking row animation has finished — the static
 *  .nf-tucked class (and natural geometry) only land in finish handlers. */
async function animationsSettled(page) {
  await page.waitForFunction(() => {
    const rows = document.querySelectorAll('#chat .row.tool, #chat .row.thinking-row');
    return Array.from(rows).flatMap(r => r.getAnimations())
      .every(a => a.playState !== 'running' && a.playState !== 'pending');
  }, null, { timeout: 5000 });
}

test.describe('banner dedupe — live multi-turn path', () => {
  test('second turn hides the first turn header; exactly one visible header with the latest stats', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());

    // Turn 1: exactly one header (v2 stats: model · 思考 · 工具 · 读写).
    let c = await bannerCensus(page);
    expect(c.total).toBe(1);
    expect(c.visibleTexts.length).toBe(1);

    const domBefore = await rowsSnapshot(page);

    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);

    // Turn 2 done: exactly ONE visible header — turn 2's own stats.
    c = await bannerCensus(page);
    expect(c.total).toBe(2);            // superseded headers stay in the DOM
    expect(c.visibleTexts.length).toBe(1);
    expect(c.hidden).toBe(1);
    expect(c.markedGroups).toBe(1);     // marker on the HEADER element…
    const marked = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-header.turn-banner-superseded'))
        .map(h => !!h.querySelector('.turn-header-text')));
    expect(marked).toEqual([true]);
    // …and the visible header belongs to turn 2 (model + tool count; the
    // Bash tool carries no file payload → no 读写 segment).
    expect(c.visibleTexts[0]).toBe('test-model · 工具 1 次');

    // #403 byte-stability: the marker never touches rows — the closed
    // turn-1 rows are identical to before the second turn.
    const domAfter = await rowsSnapshot(page);
    expect(domAfter.startsWith(domBefore)).toBe(true);

    // 过程文字红线: every assistant text row still visible, original order.
    const texts = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#chat .row.ai'))
        .filter(r => !r.classList.contains('thinking-row') && !r.classList.contains('nf-tucked'))
        .map(r => ({ t: (r.querySelector('.bubble.ai')?.textContent || '').trim(), v: r.offsetHeight > 0 })));
    expect(texts.map(x => x.t)).toEqual([
      '好的，我先检索资料，再逐段汇报进展。',
      '找到关键线索了：收起分组在 turnGroup.js，继续核对细节。',
      '这是最终回复：中间过程已收起，回复与卡片照常可见。',
      '新回合的最终回复。',
    ]);
    expect(texts.every(x => x.v)).toBe(true);

    await context.close();
  });

  test('superseded turn still expands via E10 search; its header stays hidden', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    await page.evaluate(() => window.__postClosureTurn());
    expect(await bannerCensus(page)).toMatchObject({ visibleTexts: ['test-model · 工具 1 次'] });

    // E10 entry point on a tucked tool row inside the FIRST (superseded) turn.
    const r = await page.evaluate(() => {
      const row = document.querySelector('#chat .row.tool.nf-tucked');
      const before = row.classList.contains('nf-tucked');
      const expanded = window.__expandContaining(row);
      return { before, expanded, after: row.classList.contains('nf-tucked') };
    });
    expect(r.before).toBe(true);
    expect(r.expanded).toBe(true);
    expect(r.after).toBe(false);

    // Turn-1 rows are visible; the superseded header is NOT. (turn 1 holds
    // BOTH its tool rows + the thinking row — all revealed.)
    await animationsSettled(page);
    const c = await bannerCensus(page);
    expect(c.visibleTexts.length).toBe(1);
    expect(await page.evaluate(() => {
      const firstTool = document.querySelectorAll('#chat .row.tool')[0];
      const secondTool = document.querySelectorAll('#chat .row.tool')[1];
      const thinking = document.querySelector('#chat .row.thinking-row');
      return [firstTool, secondTool, thinking].every(el => el && !el.classList.contains('nf-tucked') && el.offsetHeight > 0);
    })).toBe(true);
    expect(await page.locator('.turn-header').first().evaluate(el => el.offsetHeight === 0)).toBe(true);

    await context.close();
  });

  test('text-only turn builds no header and leaves the previous latest header visible', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);

    // A zero-process turn (E5): collapseTurn runs, builds nothing — the
    // latest header must NOT be superseded by a header that does not exist.
    await page.evaluate(() => window.__textOnlyTurn());
    expect(pageErrors).toEqual([]);
    let c = await bannerCensus(page);
    expect(c.total).toBe(2);              // no third header appeared
    expect(c.visibleTexts).toEqual(['test-model · 工具 1 次']);

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
    // The text-only turn renders no header; turn 1's header remains the latest.
    c = await bannerCensus(page);
    expect(c.total).toBe(1);
    expect(c.visibleTexts.length).toBe(1);
    expect(c.visibleTexts[0]).toContain('m-one');

    await context.close();
  });
});

test.describe('banner dedupe — history replay path (restoreFromBackendHistory / restoreFromStorage)', () => {
  test('multi-turn history renders only the last header with the latest turn stats', async ({ browser }) => {
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
    expect(c.total).toBe(2);               // both headers in DOM…
    expect(c.visibleTexts.length).toBe(1); // …only the LAST turn's renders
    expect(c.markedGroups).toBe(1);
    // Latest turn's stats: model + its own tool count — none of turn 1's
    // (工具 2 次 / 读写 1 文件). Grep has no file payload → no 读写 segment;
    // history rows carry no thinking timing → no 思考 segment.
    expect(c.visibleTexts[0]).toBe('zhipu/GLM-5.3-Flash · 工具 1 次');
    expect(c.visibleTexts[0]).not.toContain('工具 2 次');
    expect(c.visibleTexts[0]).not.toContain('读写');

    // 过程文字红线: all history text rows visible in order.
    const texts = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#chat .row.ai'))
        .filter(r => !r.classList.contains('thinking-row') && !r.classList.contains('nf-tucked'))
        .map(r => (r.querySelector('.bubble.ai')?.textContent || '').trim()));
    expect(texts).toEqual(['第一轮完成。', '第二轮完成，收工。']);

    const shot = path.join(SHOT_DIR, 'spec-replay-single-banner.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);

    await context.close();
  });

  test('refresh mid-turn (busyTail) keeps the last closed header; next live done supersedes it', async ({ browser }) => {
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

    // Open turn has no header yet; the last CLOSED turn's header stays visible.
    let c = await bannerCensus(page);
    expect(c.total).toBe(1);
    expect(c.visibleTexts[0]).toContain('m-one');

    // The open turn completes (live terminal on top of a rebuilt session):
    // its header becomes the only visible one; the history header is
    // superseded. The flat tail [ai text, tool Read] + the new injected +
    // Bash all belong to turn 2 — both tool cards count.
    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);
    c = await bannerCensus(page);
    expect(c.total).toBe(2);
    expect(c.visibleTexts).toEqual(['test-model · 工具 2 次 · 读写 1 文件']);
    expect(c.markedGroups).toBe(1);

    await context.close();
  });
});
