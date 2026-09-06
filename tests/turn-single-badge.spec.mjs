// turn-single-badge.spec.mjs — 2026-09-05 turn 级聚合回归（作者 08:48 复现：
// 多轮 turn 出现两个 badge）, updated for the #346 v2 "decompression model"
// (2026-09-05 23:44 ruling).
//
// A turn interleaving text with several tool rounds (MemoryEdit×5 → text →
// Task×4 → text → final) gets exactly ONE persistent stats header
// (`.turn-header`) at the turn top, counting the WHOLE turn: ✻ 字句 ·
// model · 思考 · 工具 9 次 · 读写 5 文件. v2: rows never move — the header
// is the only node inserted; expanding reveals the 9 tool cards in their
// original order. 2026-09-06: the toggle is INSTANT (批② WAAPI animation
// layer removed, author feedback 「动画太卡顿」); the ✻ 设计字句恢复进题头。
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
const SHOT_DIR = process.env.NB_KEEP_TEXT_SHOT_DIR || path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');

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
const MODEL_1 = 'zhipu/GLM-5.3-Flash';

/** Full census of the turn-chrome state: one entry per `.turn-header`, with
 *  the turn scope (header → next user/header) rolled in. */
function badgeCensus(page) {
  return page.evaluate(() => {
    const chat = document.getElementById('chat');
    const headers = Array.from(chat.children).filter(k => k.classList.contains('turn-header'));
    return {
      groupCount: headers.length,
      bars: headers.map(h => {
        const rows = [];
        let n = h.nextElementSibling;
        while (n && !n.classList.contains('turn-header') &&
               !(n.classList.contains('row') && n.classList.contains('user') && !n.querySelector('.bubble.injected'))) {
          rows.push(n);
          n = n.nextElementSibling;
        }
        return {
          text: (h.querySelector('.turn-header-text')?.textContent || '').trim(),
          visible: h.offsetHeight > 0,
          toolCards: rows.filter(r => r.classList.contains('tool')).length,
          tuckedRows: rows.filter(r => r.classList.contains('nf-tucked')).length,
          // header position: immediately after the turn's user row
          followsUser: !!h.previousElementSibling?.classList?.contains('user'),
        };
      }),
      flatTexts: Array.from(document.querySelectorAll('#chat .row.ai'))
        .filter(r => !r.classList.contains('thinking-row') && !r.classList.contains('nf-tucked'))
        .map(r => ((r.querySelector('.bubble.ai')?.textContent) || '').trim()),
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

/** Negative animation pin (2026-09-06): toggles are instant class flips —
 *  no running/pending SCRIPT-DRIVEN (WAAPI) animation may exist on
 *  tool/thinking rows (CSS entrance animations like fadeIn excluded). */
async function expectNoAnimations(page) {
  const count = await page.evaluate(() => {
    const rows = document.querySelectorAll('#chat .row.tool, #chat .row.thinking-row');
    return Array.from(rows).flatMap(r => r.getAnimations())
      // CSSAnimations (row fadeIn entrance etc.) are unrelated chrome — the
      // pin targets script-driven (WAAPI) animations, the removed 批② layer.
      .filter(a => !(a instanceof CSSAnimation))
      .filter(a => a.playState === 'running' || a.playState === 'pending').length;
  });
  expect(count).toBe(0);
}

test.describe('one header per turn — multi-round tool loop (author repro)', () => {
  test('MemoryEdit×5 → text → Task×4 → text → final: exactly ONE header, count 9, files 5, at the turn top', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__multiRoundTurn());
    expect(pageErrors).toEqual([]);

    const c = await badgeCensus(page);
    // 恰一个 header（修复前：两个 —— 工具 5 次 / 工具 4 次）。
    expect(c.groupCount).toBe(1);
    expect(c.bars.length).toBe(1);
    expect(c.bars[0].visible).toBe(true);
    // 计数 = 全轮合计 9（5+4）；读写文件 = MemoryEdit 的 5 个去重 file_path
    // （Task 载荷无文件字段）。✻ 设计字句为首段（2026-09-06 恢复）。
    expect(c.bars[0].toolCards).toBe(9);
    expect(c.bars[0].text).toBe(`✻ 买了张去星辰的车票，3m 25s 到站 · ${MODEL_1} · 工具 9 次 · 读写 5 文件`);
    // header 位置 = 紧贴 turn 的 user 行后（turn 顶）。
    expect(c.bars[0].followsUser).toBe(true);

    // 中间文字全部可见（红线）：A、B、最终都在收起区外，时序不乱。
    expect(c.flatTexts).toEqual([T_A, T_B, T_FINAL]);

    // 步骤区默认收起（9 张工具卡全 tucked），展开后按原顺序可见（瞬时）。
    expect(c.bars[0].tuckedRows).toBe(9);
    await page.locator('.turn-header').first().click();
    await expectNoAnimations(page);
    const tools = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#chat .row.tool'))
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

  test('post-closure turn opens its OWN header — no fusion, no cross-turn summary bleed', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__multiRoundTurn());
    const domBefore = await rowsSnapshot(page);

    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);

    const c = await badgeCensus(page);
    // 跨 turn 不融合：两个 turn 各一个 header。
    expect(c.groupCount).toBe(2);
    expect(c.bars.map(b => b.visible)).toEqual([false, true]); // turn1 header superseded
    // turn1 rows byte-stable（#403 不变量）。
    const domAfter = await rowsSnapshot(page);
    expect(domAfter.startsWith(domBefore)).toBe(true);
    // 摘要各归各 turn：turn2 的 header 是 turn2 的 ✻ 字句/model/count（Bash
    // 无文件载荷 → 无读写段），不含 turn1 的任何字段。
    expect(c.bars[1].text).toBe('✻ 快速确认 2 秒 · test-model · 工具 1 次');
    expect(c.bars.map(b => b.toolCards)).toEqual([9, 1]);
    expect(c.bars[1].text).not.toContain('工具 9 次');
    expect(c.bars[1].text).not.toContain(MODEL_1);
    // turn1 的 header 文本仍带着本 turn 的完整摘要（隐藏但 DOM 保留）。
    expect(c.bars[0].text).toBe(`✻ 买了张去星辰的车票，3m 25s 到站 · ${MODEL_1} · 工具 9 次 · 读写 5 文件`);

    await context.close();
  });

  test('failed multi-round turn re-terminalizes with NO header and everything visible (spec A5)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__multiRoundTurn());
    expect(await badgeCensus(page)).toMatchObject({ groupCount: 1 });

    await page.evaluate(() => window.__refailTurn());
    expect(pageErrors).toEqual([]);

    const c = await badgeCensus(page);
    expect(c.groupCount).toBe(0);                     // header dissolved
    expect(await page.locator('.turn-header').count()).toBe(0);
    expect(await page.locator('.nf-tucked').count()).toBe(0); // nothing tucked
    expect(c.flatTexts).toEqual([T_A, T_B, T_FINAL]); // texts untouched
    // All 9 tool rows back to flat visibility (rows never moved).
    expect(await page.evaluate(() =>
      Array.from(document.querySelectorAll('#chat .row.tool')).every(r => r.offsetHeight > 0))).toBe(true);

    await context.close();
  });
});

test.describe('one header per turn — history rebuild path', () => {
  test('multi-round history segment aggregates into ONE header with the whole-turn count', async ({ browser }) => {
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
    expect(c.bars[0].toolCards).toBe(5); // 2+3 aggregated
    // ✻ phrase: seed = msg index 8 → think.8「没什么方向地飘了 3m 25s」(deterministic).
    expect(c.bars[0].text).toBe('✻ 没什么方向地飘了 3m 25s · zhipu/GLM-5.3-Flash · 工具 5 次 · 读写 2 文件');
    expect(c.bars[0].followsUser).toBe(true);
    expect(c.flatTexts).toEqual(['历史中间文字A。', '历史中间文字B。', '历史最终回复。']);

    await context.close();
  });

  test('busyTail boundary: a mid-turn multi-round tail stays flat (no premature header)', async ({ browser }) => {
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
    // 只有已闭合的 turn1 有 header；在跑的 tail（已跨一轮工具+文字+工具）不收。
    expect(c.groupCount).toBe(1);
    expect(c.bars[0].text).toContain('m-one');
    expect(c.flatTexts).toEqual(['已闭合回复。', '流式中间文字。']);
    const tailFlat = await page.evaluate(() => {
      const kids = Array.from(document.getElementById('chat').children);
      // v2: ALL rows are chat's direct children (nothing is gathered). The
      // open turn's tail tools are the NOT-tucked ones — exactly 2, visible.
      const tools = kids.filter(k => k.classList && k.classList.contains('tool'));
      const flat = tools.filter(t => !t.classList.contains('nf-tucked'));
      return tools.length === 3 && flat.length === 2 && flat.every(t => t.offsetHeight > 0);
    });
    expect(tailFlat).toBe(true);

    await context.close();
  });
});
