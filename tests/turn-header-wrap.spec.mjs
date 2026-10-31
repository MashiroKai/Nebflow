// turn-header-wrap.spec.mjs — 归因行瘦身的折行规约钉（2026-10-31 作者令批）
//
// 作者 10-31 15:36 令，附现行形态原文：「✻ 沉在银河里，1m 18s 都没回过神 ·
// kimi/kimi-for-coding · 思考 15s · 工具 1 次」。两件：
//   ① 去掉「思考 15s」段——归因行不再显示思考耗时（工具次数保留）。
//   ② 折行规则：一行放不下折两行时，只能在「·」分隔处断开，不允许从段中
//      拦腰折断词。
//
// 本规格钉第 ② 件的渲染结构 + 第 ① 件在两张面孔上的负断言：
//   面孔 A  buildHeader（历史重建路径，tests/fixtures/turn-collapse/harness.html
//           __history 驱动真实 persistence.js → turnGroup.js）
//   面孔 B  settleWorklineAsHeader（活线终态 morph，harness __longBadgeTurn）
// 实现面：段 = `.nf-th-seg`（white-space:nowrap），分隔 = `.nf-th-sep`（' · '，
// 折行机会只存在于分隔符空格）——见 turnGroup.js headerSegments / workline.js
// buildBadgeLabel / chat.css .turn-header-text 注释。
//
// 断言核心：窄窗强制折行后，每个 .nf-th-seg 的 clientRects 必须恰为 1（一段
// 不跨行 = 没有词被拦腰折断）；textContent 契约不变（'seg · seg' 拼接，无思考段）。
//
// Run: npx playwright test tests/turn-header-wrap.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/turn-collapse/harness.html';
// 样张落盘：默认 ~/.nebflow/docs/Nebflow（宿主惯例）；evidence 跑法用
// NB_HEADER_WRAP_SHOT_DIR 指到仓内 evidence/<dir>/。
const SHOT_DIR = process.env.NB_HEADER_WRAP_SHOT_DIR || path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');

// 作者令附的现行形态原文（段①移除后以它为验收基准串）。
const PHRASE = '✻ 沉在银河里，1m 18s 都没回过神';
const MODEL = 'kimi/kimi-for-coding';

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

async function newPage(browser, opts = {}) {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 900, height: 1000 },
    ...opts,
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/* 折行读数：行数 + 每段矩形数 + 每段右缘 + 文本契约。
 * 行数口径：text span 是 flex 项（block 化），其自身 clientRects 恒为一个内容盒，
 * 不能反映内部折行——行数 = 各 .nf-th-seg rect top 的去重数（每行必含 ≥1 段，
 * 分隔符 ' · ' 永远不会独占一行）。一段跨行 ⇒ 该段 clientRects = 2 —— 正是
 * 「拦腰折断」的探针。 */
async function wrapProbe(page) {
  return page.evaluate(() => {
    const text = document.querySelector('.turn-header-text');
    if (!text) return null;
    const chat = document.getElementById('chat');
    const chatRight = chat.getBoundingClientRect().right;
    const segRects = Array.from(text.querySelectorAll('.nf-th-seg')).map(s => {
      const rs = Array.from(s.getClientRects());
      return { n: rs.length, top: Math.round(rs[0].top), right: Math.round(Math.max(...rs.map(r => r.right))) };
    });
    return {
      text: (text.textContent || '').trim(),
      lines: new Set(segRects.map(s => s.top)).size,
      segCount: segRects.length,
      segRects,
      maxSegRight: Math.max(...segRects.map(s => s.right), 0),
      chatRight: Math.round(chatRight),
    };
  });
}

function expectNoSplit(w, expectedText) {
  expect(w.text, 'textContent contract unchanged (seg · seg join)').toBe(expectedText);
  expect(w.text, 'no thinking-duration segment (10-31 ruling ①)').not.toContain('思考');
  expect(w.segCount, 'segment count').toBeGreaterThan(1);
  for (const s of w.segRects) {
    expect(s.n, 'every segment sits on exactly ONE line — no mid-word break').toBe(1);
  }
  expect(w.maxSegRight, 'no segment overflows the chat column').toBeLessThanOrEqual(w.chatRight + 1);
}

test.describe('面孔 B — settle 路径（活线终态 morph）', () => {
  // 工具不带 file_path ⇒ 无「读写」段，期望串确定。几何（12px 字号）：
  // phrase ≈ 210px · model ≈ 144px · 「工具 2 次」 ≈ 48px；窄窗 400px 时
  // header max-width = 85% × 384 ≈ 326px ⇒ 恰好两行（phrase / 其余）。
  const EXPECTED = `${PHRASE} · ${MODEL} · 工具 2 次`;

  test('宽窗单行：段结构与文本契约', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(({ p, m }) => window.__longBadgeTurn(p, m, 2), { p: PHRASE, m: MODEL });
    expect(pageErrors).toEqual([]);
    const w = await wrapProbe(page);
    expect(w.lines, 'wide window: one line').toBe(1);
    expectNoSplit(w, EXPECTED);
    await context.close();
  });

  for (const scheme of ['dark', 'light']) {
    test(`窄窗强制两行（${scheme} 主题）· 折行只在「·」分隔处`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, {
        viewport: { width: 400, height: 800 },
        colorScheme: scheme,
      });
      await page.evaluate(({ p, m }) => window.__longBadgeTurn(p, m, 2), { p: PHRASE, m: MODEL });
      expect(pageErrors).toEqual([]);
      const w = await wrapProbe(page);
      expect(w.lines, 'narrow window: the summary wraps to exactly two lines').toBe(2);
      expectNoSplit(w, EXPECTED);
      const header = page.locator('.turn-header').first();
      await header.screenshot({ path: path.join(SHOT_DIR, `header-wrap-settle-${scheme}-narrow.png`) });
      await context.close();
    });
  }
});

test.describe('面孔 A — buildHeader 路径（历史重建）', () => {
  // 历史 ui.json 形态：4 个工具（不带 file_path ⇒ 无「读写」段，期望串确定）+
  // 长模型名 + 终态徽章（durationMs 驱动 data-nf-phrase/data-nf-model 上 badge，
  // groupSegment 的 meta 来源）。窄窗 400px（header max-width ≈ 326px，文本盒
  // ≈ 286px）下 phrase / 其余 两段各据一行 ⇒ 恰好两行。
  const T = 1735689600000;
  const LONG_MODEL = 'anthropic/claude-sonnet-4-5';
  const mkMsgs = () => {
    const msgs = [{ type: 'user', text: '开工', timestamp: T }];
    for (let i = 1; i <= 4; i++) {
      msgs.push({
        type: 'tool', label: `Bash("step-${i}")`, summary: 'ok', content: 'out',
        isError: false, input: `{"command":"step-${i}"}`,
      });
    }
    msgs.push({
      type: 'ai', text: '历史最终回复。', durationMs: 3000, model: LONG_MODEL,
      timestamp: T + 2000,
    });
    return msgs;
  };
  // phrase = pickThinkingPhrase(3000, seed) —— 种子由消息下标决定，此处不赌种子，
  // 从 DOM 读回真实 phrase 拼期望串。
  const expectedText = (phrase) =>
    `${phrase} · ${LONG_MODEL} · 工具 4 次`;

  test('宽窗单行：段结构与文本契约（无思考段）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(m => window.__history(m, { busyTail: false }), mkMsgs());
    expect(pageErrors).toEqual([]);
    const w = await wrapProbe(page);
    expect(w.lines, 'wide window: one line').toBe(1);
    const phrase = w.text.split(' · ')[0];
    expectNoSplit(w, expectedText(phrase));
    await context.close();
  });

  for (const scheme of ['dark', 'light']) {
    test(`窄窗强制两行（${scheme} 主题）· 折行只在「·」分隔处`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, {
        viewport: { width: 400, height: 800 },
        colorScheme: scheme,
      });
      await page.evaluate(m => window.__history(m, { busyTail: false }), mkMsgs());
      expect(pageErrors).toEqual([]);
      const w = await wrapProbe(page);
      expect(w.lines, 'narrow window: the summary wraps to exactly two lines').toBe(2);
      const phrase = w.text.split(' · ')[0];
      expectNoSplit(w, expectedText(phrase));
      const header = page.locator('.turn-header').first();
      await header.screenshot({ path: path.join(SHOT_DIR, `header-wrap-history-${scheme}-narrow.png`) });
      await context.close();
    });
  }
});
