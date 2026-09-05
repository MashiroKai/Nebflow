// turn-single-badge.spec.mjs — 2026-09-05 author ruling (一 turn 一 badge).
//
// Author report 2026-09-05 08:48 (screenshot): a turn with TWO LLM tool
// rounds (MemoryEdit×5 → text → Task×4 → final) rendered TWO collapsed
// badges with identical summary text but split tool counts (工具 5 次 /
// 工具 4 次) — grouping was per contiguous RUN (per LLM round), not per turn.
//
// Ruling (08-25/08-26 semantics baseline, turn-level completion): ONE badge
// per TURN — every LLM round's tool calls aggregate into the single bar's
// 工具 N 次 count; the bar lands immediately before the turn's final reply;
// intermediate LLM text rows stay flat and visible (2026-09-03 ruling); a
// post-closure external event still opens a NEW turn/group (#403, no
// fusion); failed turns build no bar (A5); busyTail boundary preserved
// (efa5e6a6).
//
// Summary-text note: the badge's first segment is the frozen decorative
// status phrase (✻ …, v1.2 ruling 081274ee) sourced from THIS turn's
// terminal meta — the reported "stale trigger text" suspicion was a
// misdiagnosis (the phrase is `think.11` from locales, not a trigger-message
// extraction). These specs therefore assert per-turn freshness (turn 2's
// bar carries turn 2's phrase/model/count, never turn 1's), not trigger
// text.
//
// Drives the REAL render modules through tests/fixtures/turn-collapse/
// harness.html over a throwaway static server (never the 8080 host).
//
// Run: npx playwright test tests/turn-single-badge.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/turn-collapse/harness.html';
const SHOT_DIR = process.env.NB_SINGLE_BADGE_SHOT_DIR || '/tmp/nb-turn-badge-fix/shots';

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

const T_A = '第一轮记忆更新完成，接着派 4 个子任务并行收集数据。';
const T_B = '子任务全部派出，等待回收结果后汇总。';
const T_FINAL = '全部完成：记忆已更新，4 路数据收集任务已启动并回收。';
const PHRASE_1 = '✻ 买了张去星辰的车票，3m 25s 到站';
const MODEL_1 = 'zhipu/GLM-5.3-Flash';

/** Full census of the turn-collapse state for the multi-round turn. */
function badgeCensus(page) {
  return page.evaluate(() => {
    const groups = Array.from(document.querySelectorAll('.turn-group'));
    return {
      groupCount: groups.length,
      bars: groups.map(g => ({
        text: (g.querySelector('.turn-summary-text')?.textContent || '').trim(),
        visible: !!g.querySelector('.turn-summary') && g.querySelector('.turn-summary').offsetHeight > 0,
      })),
      toolCardsPerGroup: groups.map(g => g.querySelectorAll('.tool-card').length),
      // badge adjacency: each group must sit immediately before an ai text row
      nextIsAiText: groups.map(g => {
        const nx = g.nextElementSibling;
        return !!nx && nx.classList.contains('row') && nx.classList.contains('ai')
          && !!nx.querySelector('.bubble.ai');
      }),
      flatTexts: Array.from(document.querySelectorAll('#chat .row.ai'))
        .filter(r => !r.classList.contains('thinking-row') && !r.closest('.turn-steps'))
        .map(r => ((r.querySelector('.bubble.ai')?.textContent) || '').trim()),
    };
  });
}

test.describe('one badge per turn — multi-round tool loop (author repro)', () => {
  test('MemoryEdit×5 → text → Task×4 → text → final: exactly ONE badge, count 9, before the final reply', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__multiRoundTurn());
    expect(pageErrors).toEqual([]);

    const c = await badgeCensus(page);
    // 恰一个 badge（修复前：两个 —— 工具 5 次 / 工具 4 次）。
    expect(c.groupCount).toBe(1);
    expect(c.bars.length).toBe(1);
    expect(c.bars[0].visible).toBe(true);
    // 计数 = 全轮合计 9（5+4），并带本 turn 的 phrase 与 model。
    expect(c.toolCardsPerGroup).toEqual([9]);
    expect(c.bars[0].text).toBe(`${PHRASE_1} · ${MODEL_1} · 工具 9 次`);
    // badge 位置 = 贴最终回复前（与截图现状位置一致但唯一）。
    expect(c.nextIsAiText).toEqual([true]);

    // 中间文字全部可见（2552fb57 红线）：A、B、最终都在折叠区外，时序不乱。
    expect(c.flatTexts).toEqual([T_A, T_B, T_FINAL]);

    // 无任何文字段被收进折叠区。
    expect(await page.locator('.turn-steps .bubble.ai').count()).toBe(0);
    // 步骤区默认收起，展开后 9 张工具卡按原顺序可见。
    expect(await page.locator('.turn-steps').first().evaluate(el => el.style.display)).toBe('none');
    await page.locator('.turn-summary').first().click();
    const tools = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-steps .row.tool'))
        .map(r => ({ v: r.offsetHeight > 0, label: r.querySelector('.tool-card')?.textContent || '' })));
    expect(tools.length).toBe(9);
    expect(tools.every(t => t.v)).toBe(true);
    expect(tools.filter(t => t.label.includes('MemoryEdit')).length).toBe(5);
    expect(tools.filter(t => t.label.includes('Task')).length).toBe(4);

    // 真渲染取证截图（报告用）。
    const shot = path.join(SHOT_DIR, 'single-badge-multiround.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);

    await context.close();
  });

  test('post-closure turn opens its OWN badge — no fusion, no cross-turn summary bleed', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__multiRoundTurn());
    const before = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-group')).map(g => g.innerHTML));
    expect(before.length).toBe(1);

    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);

    const c = await badgeCensus(page);
    // 跨 turn 不融合：两个 turn 各一个 badge。
    expect(c.groupCount).toBe(2);
    expect(c.bars.map(b => b.visible)).toEqual([false, true]); // turn1 bar superseded (9906bca5)
    // turn1 badge byte-stable（#403 不变量）。
    const after = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-group')).map(g => g.innerHTML));
    expect(after[0]).toBe(before[0]);
    // 摘要各归各 turn：turn2 的 bar 是 turn2 的 phrase/model/count，
    // 不含 turn1 的任何字段（计数 9 / phrase1 / model1 都不出现）。
    expect(c.bars[1].text).toBe('✻ 快速确认 2 秒 · test-model · 工具 1 次');
    expect(c.toolCardsPerGroup).toEqual([9, 1]);
    expect(c.bars[1].text).not.toContain('工具 9 次');
    expect(c.bars[1].text).not.toContain(PHRASE_1);
    expect(c.bars[1].text).not.toContain(MODEL_1);
    // turn1 的 badge 文本仍带着本 turn 的完整摘要（隐藏但 DOM 保留）。
    expect(c.bars[0].text).toBe(`${PHRASE_1} · ${MODEL_1} · 工具 9 次`);

    await context.close();
  });

  test('failed multi-round turn re-gathers to ONE group with NO badge (spec A5)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__multiRoundTurn());
    expect(await badgeCensus(page)).toMatchObject({ groupCount: 1 });

    await page.evaluate(() => window.__refailTurn());
    expect(pageErrors).toEqual([]);

    const c = await badgeCensus(page);
    expect(c.groupCount).toBe(1);                    // still ONE group
    expect(c.bars[0].text).toBe('');                 // A5: the bar does not exist
    expect(c.toolCardsPerGroup).toEqual([9]);        // all 9 tools still inside
    expect(c.flatTexts).toEqual([T_A, T_B, T_FINAL]); // texts untouched
    expect(await page.locator('.turn-summary').count()).toBe(0);
    expect(await page.locator('.turn-group').first().evaluate(el => el.dataset.turnState)).toBe('failed');
    expect(await page.locator('.turn-steps').first().evaluate(el => el.style.display)).toBe('');

    await context.close();
  });
});

test.describe('one badge per turn — history rebuild path', () => {
  test('multi-round history segment gathers into ONE group with the aggregated count', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const T = 1735689600000;
    await page.evaluate(m => window.__history(m, { busyTail: false }), [
      { type: 'user', text: '多轮回合（历史）', timestamp: T },
      { type: 'tool', label: 'MemoryEdit("a.md")', summary: 'ok', content: 'p1', isError: false, input: '{"file_path":"a.md"}' },
      { type: 'tool', label: 'MemoryEdit("b.md")', summary: 'ok', content: 'p2', isError: false, input: '{"file_path":"b.md"}' },
      { type: 'ai', text: '历史中间文字A。' },
      { type: 'tool', label: 'Task("w1")', summary: 'ok', content: 'd1', isError: false, input: '{"name":"w1"}' },
      { type: 'tool', label: 'Task("w2")', summary: 'ok', content: 'd2', isError: false, input: '{"name":"w2"}' },
      { type: 'tool', label: 'Task("w3")', summary: 'ok', content: 'd3', isError: false, input: '{"name":"w3"}' },
      { type: 'ai', text: '历史中间文字B。' },
      { type: 'ai', text: '历史最终回复。', durationMs: 205000, model: 'zhipu/GLM-5.3-Flash', timestamp: T + 205000 },
    ]);
    expect(pageErrors).toEqual([]);

    const c = await badgeCensus(page);
    expect(c.groupCount).toBe(1);
    expect(c.toolCardsPerGroup).toEqual([5]); // 2+3 aggregated
    expect(c.bars[0].text).toContain('工具 5 次');
    expect(c.bars[0].text).toContain('zhipu/GLM-5.3-Flash');
    expect(c.nextIsAiText).toEqual([true]);
    expect(c.flatTexts).toEqual(['历史中间文字A。', '历史中间文字B。', '历史最终回复。']);

    await context.close();
  });

  test('busyTail boundary: a mid-turn multi-round tail stays flat (no premature badge)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const T = 1735689600000;
    await page.evaluate(m => window.__history(m, { busyTail: true }), [
      { type: 'user', text: '已闭合回合', timestamp: T },
      { type: 'tool', label: 'Bash("ls")', summary: 'ok', content: 'out', isError: false, input: '{"command":"ls"}' },
      { type: 'ai', text: '已闭合回复。', durationMs: 3000, model: 'm-one', timestamp: T + 1000 },
      { type: 'user', text: '还在跑的多轮回合', timestamp: T + 2000 },
      { type: 'tool', label: 'Read("b.md")', summary: '2 lines', content: 'body', isError: false, input: '{"file_path":"b.md"}' },
      { type: 'ai', text: '流式中间文字。' },
      { type: 'tool', label: 'Grep("p")', summary: '1 hit', content: 'hit', isError: false, input: '{"pattern":"p"}' },
    ]);
    expect(pageErrors).toEqual([]);

    const c = await badgeCensus(page);
    // 只有已闭合的 turn1 有组+bar；在跑的 tail（已跨一轮工具+文字+工具）不分组。
    expect(c.groupCount).toBe(1);
    expect(c.bars[0].text).toContain('m-one');
    expect(c.flatTexts).toEqual(['已闭合回复。', '流式中间文字。']);
    const tailFlat = await page.evaluate(() => {
      const kids = Array.from(document.getElementById('chat').children);
      // 在跑的 tail 两张工具卡必须是 chat 的直接子级（未入组）且可见。
      const tools = kids.filter(k => k.classList && k.classList.contains('tool'));
      return tools.length === 2 && tools.every(t => t.offsetHeight > 0);
    });
    expect(tailFlat).toBe(true);

    await context.close();
  });
});
