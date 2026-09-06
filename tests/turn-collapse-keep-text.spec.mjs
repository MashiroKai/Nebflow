// turn-collapse-keep-text.spec.mjs — #346 v2 "decompression model"
// (2026-09-05 23:44 author ruling: 思考直播回归 + 收起/展开语义重做;
// 2026-09-06 08:19 author feedback: 动画太卡顿 → WAAPI 动画层全摘,
// 直落/直剥; 总结行换行完整显示; ✻ 设计字句恢复进题头).
//
// v2 semantics: the turn keeps its ordered row list IN PLACE — collapse is a
// render-layer transform. A persistent `.turn-header` (✻ 字句 · model ·
// 思考 Ns · 工具 M 次 · 读写 K 文件 + chevron) lands at the turn top; ONLY
// thinking rows + tool-call rows tuck away (`.nf-tucked`). Assistant text
// rows, card deliverables and injected bubbles stay visible. Expanding
// restores every middle block to its ORIGINAL position (they never moved).
// The toggle is INSTANT — no transition layer (the 批② 320ms/stagger WAAPI
// animation was removed 2026-09-06, author feedback 「动画太卡顿」).
//
// Streaming regression (思考直播回归): thinking streams EXPANDED; only the
// terminal tuck folds it.
//
// Drives the REAL render modules (chatView.js / chat.js / turnGroup.js /
// persistence.js) in a static harness page
// (tests/fixtures/turn-collapse/harness.html) — mock data matches the real
// session ui.json message shape. One throwaway static file server is spawned
// by this spec and killed in afterAll (never the 8080 host).
//
// Run: npx playwright test tests/turn-collapse-keep-text.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/turn-collapse/harness.html';
// Screenshots land in the docs dir by default (host runs); NB_KEEP_TEXT_SHOT_DIR
// overrides for sandboxed runs where ~/.nebflow is not writable (banner-dedupe
// spec precedent: NB_BANNER_SHOT_DIR).
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

/** Top-level DOM sequence of #chat. v2: rows NEVER move — the header is one
 *  flat node; tucked rows carry the :tucked marker. */
function readSeq(page) {
  return page.evaluate(() => {
    const chat = document.getElementById('chat');
    return Array.from(chat.children).map(k => {
      if (k.classList.contains('turn-header')) return 'header';
      if (k.classList.contains('row')) {
        const tuck = k.classList.contains('nf-tucked') ? ':tucked' : '';
        if (k.querySelector('.bubble.injected')) return 'injected-flat';
        if (k.classList.contains('user')) return 'user';
        if (k.classList.contains('thinking-row')) return 'thinking' + tuck;
        if (k.classList.contains('tool')) return 'tool' + tuck;
        if (k.classList.contains('card-content')) return 'card' + tuck;
        if (k.classList.contains('ai')) return 'ai:' + norm(k);
      }
      return 'other:' + k.className;
    });
    function norm(row) {
      return ((row.querySelector('.bubble.ai')?.textContent) || '').trim().replace(/\s+/g, '');
    }
  });
}

/** Assistant TEXT rows (thinking rows are not text replies) that are NOT
 *  tucked — full text + visibility. */
function visibleTexts(page) {
  return page.evaluate(() => {
    return Array.from(document.querySelectorAll('#chat .row.ai'))
      .filter(r => !r.classList.contains('thinking-row') && !r.classList.contains('nf-tucked'))
      .map(r => ({
        text: ((r.querySelector('.bubble.ai')?.textContent) || '').trim().replace(/\s+/g, ''),
        visible: r.offsetHeight > 0,
      }));
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

/** Negative animation pin (2026-09-06): after any toggle there must be ZERO
 *  running/pending SCRIPT-DRIVEN (WAAPI) animations on tool/thinking rows —
 *  the 批② animation layer is gone, the toggle is an instant class flip.
 *  (CSS entrance animations like fadeIn are unrelated and excluded.) */
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

const T_A = '好的，我先检索资料，再逐段汇报进展。';
const T_B = '找到关键线索了：收起分组在turnGroup.js，继续核对细节。';
const T_FINAL = '这是最终回复：中间过程已收起，回复与卡片照常可见。';
const T_FINAL2 = '新回合的最终回复。';

test.describe('decompression model — live alternating turn (验收 ②④)', () => {
  test('done tucks thinking+tools behind a persistent stats header; texts/injected stay visible', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    expect(pageErrors).toEqual([]);

    // ONE header for the whole turn; it sits right after the user row.
    expect(await page.locator('.turn-header').count()).toBe(1);

    // Top-level order (rows never moved): user → header → thinking(tucked)
    // → 文字A → 工具1(tucked) → 文字B → 注入(visible) → 工具2(tucked) → 最终.
    expect(await readSeq(page)).toEqual([
      'user',
      'header',
      'thinking:tucked',
      `ai:${T_A}`,
      'tool:tucked',
      `ai:${T_B}`,
      'injected-flat',
      'tool:tucked',
      `ai:${T_FINAL}`,
    ]);

    // All three text replies visible, chronological order, final LAST.
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual([T_A, T_B, T_FINAL]);
    expect(texts.every(t => t.visible)).toBe(true);

    // Stripped pieces are invisible; the injected bubble stays visible.
    const vis = await page.evaluate(() => {
      const chat = document.getElementById('chat');
      const pick = (sel) => Array.from(chat.querySelectorAll(sel)).map(r => r.offsetHeight > 0);
      return {
        thinking: pick('.row.thinking-row'),
        tools: pick('.row.tool'),
        injected: pick('.row .bubble.injected'),
      };
    });
    expect(vis.thinking).toEqual([false]);
    expect(vis.tools).toEqual([false, false]);
    expect(vis.injected).toEqual([true]);

    // The header carries the restored ✻ design phrase + model + 思考 +
    // whole-turn tool count + file count (Read turnGroup.js → 1 file;
    // WebSearch has no file payload).
    const headerText = await page.locator('.turn-header-text').first().textContent();
    expect(headerText).toContain('✻ 整理线索 4 秒'); // v1-designed copy, restored 2026-09-06
    expect(headerText).toContain('test-model');
    expect(headerText).toContain('思考'); // duration is live-measured (< 1s here)
    expect(headerText).toContain('工具 2 次');
    expect(headerText).toContain('读写 1 文件');

    // 收起态 header 可见（常驻题头）。
    expect(await page.locator('.turn-header').first().evaluate(el => el.offsetHeight > 0)).toBe(true);

    await context.close();
  });

  test('expand restores every middle block in place INSTANTLY; collapse strips instantly (验收 ③④, 2026-09-06 无动画)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    const before = await readSeq(page);

    // Expand: instant class flip — no WAAPI animation may be running on the
    // tucked rows (negative pin for the removed 320ms/stagger layer).
    await page.locator('.turn-header').first().click();
    await expectNoAnimations(page);

    // Settled state (synchronous): everything visible in the ORIGINAL order
    // (identical sequence, no :tucked markers), header unchanged and visible.
    const after = await readSeq(page);
    expect(after).toEqual(before.map(s => s.replace(':tucked', '')));
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual([T_A, T_B, T_FINAL]);
    expect(texts.every(t => t.visible)).toBe(true);

    // 展开态数字常驻不闪：header text identical, still visible.
    const headerTextExpanded = await page.locator('.turn-header-text').first().textContent();
    expect(headerTextExpanded).toContain('工具 2 次');
    expect(await page.locator('.turn-header').first().evaluate(el => el.offsetHeight > 0)).toBe(true);
    expect(await page.locator('.turn-header').first().getAttribute('aria-expanded')).toBe('true');

    // Collapse back: instant strip ends in the tucked state again.
    await page.locator('.turn-header').first().click();
    await expectNoAnimations(page);
    expect(await page.locator('.nf-tucked').count()).toBe(3);
    expect(await readSeq(page)).toEqual(before);
    expect(await page.locator('.turn-header').first().getAttribute('aria-expanded')).toBe('false');

    await context.close();
  });
});

test.describe('思考直播回归 (验收 ①)', () => {
  test('thinking streams EXPANDED; stays visible after finishThinking; only the terminal tuck folds it', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);

    // Streaming: content div must be visible while deltas land.
    await page.evaluate(() => window.__thinkingStreamStart());
    expect(pageErrors).toEqual([]);
    const streaming = await page.evaluate(() => {
      const row = document.querySelector('#chat .row.thinking-row');
      const content = row?.querySelector('.thinking-content');
      return {
        rowVisible: row ? row.offsetHeight > 0 : false,
        contentVisible: content ? content.offsetHeight > 0 : false,
        hasText: !!content && content.textContent.includes('第二段思考'),
        label: row?.querySelector('.thinking-label-text')?.textContent || '',
      };
    });
    expect(streaming.rowVisible).toBe(true);
    expect(streaming.contentVisible).toBe(true);
    expect(streaming.hasText).toBe(true);
    expect(streaming.label).toContain('思考中');

    // Mid-turn finish (text reply follows): thinking stays visible — only
    // the terminal folds it (「运行中 turn 中间过程照旧实时可见，只有终态才收」).
    await page.evaluate(() => window.__thinkingStreamMid());
    expect(pageErrors).toEqual([]);
    const mid = await page.evaluate(() => {
      const row = document.querySelector('#chat .row.thinking-row');
      const content = row?.querySelector('.thinking-content');
      const aiRow = Array.from(document.querySelectorAll('#chat .row.ai'))
        .find(r => !r.classList.contains('thinking-row'));
      return {
        rowVisible: row ? row.offsetHeight > 0 : false,
        contentVisible: content ? content.offsetHeight > 0 : false,
        label: row?.querySelector('.thinking-label-text')?.textContent || '',
        aiText: aiRow ? (aiRow.querySelector('.bubble.ai')?.textContent || '').trim() : '',
      };
    });
    expect(mid.rowVisible).toBe(true);
    expect(mid.contentVisible).toBe(true);
    expect(mid.label).toBe('思考过程'); // done label, pre-#345 design
    expect(mid.aiText).toBe('思考后的答案。');

    await context.close();
  });

  test('terminal tuck folds the finished thinking row with the turn', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__thinkingStreamStart());
    await page.evaluate(() => window.__thinkingStreamMid());
    await page.evaluate(() => window.__thinkingStreamFinish());
    expect(pageErrors).toEqual([]);

    // After done: thinking row tucked behind the header; text reply visible.
    expect(await readSeq(page)).toEqual([
      'user',
      'header',
      'thinking:tucked',
      'ai:思考后的答案。',
    ]);
    const headerText = await page.locator('.turn-header-text').first().textContent();
    expect(headerText).toContain('test-model');
    expect(headerText).toContain('思考');

    await context.close();
  });
});

test.describe('decompression model — closure semantics (验收 #403)', () => {
  test('#403: external injection after closure opens a NEW turn; the closed turn is untouched', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    expect(await page.locator('.turn-header').count()).toBe(1);
    const before = await rowsSnapshot(page);

    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);

    // Second header appeared (turn 2 holds a tool → it gets its own header);
    // no fusion with the closed turn-1.
    expect(await page.locator('.turn-header').count()).toBe(2);
    expect(await readSeq(page)).toEqual([
      'user',
      'header',
      'thinking:tucked',
      `ai:${T_A}`,
      'tool:tucked',
      `ai:${T_B}`,
      'injected-flat',
      'tool:tucked',
      `ai:${T_FINAL}`,
      'header',
      'injected-flat',
      'tool:tucked',
      `ai:${T_FINAL2}`,
    ]);

    // The closed turn's ROWS are byte-identical to before the new turn
    // (rows never move; the new turn appends strictly after the cursor).
    // The banner-dedupe marker on turn-1's header (a header-attribute
    // change) is a legal render-layer update and excluded here.
    const after = await rowsSnapshot(page);
    expect(after.startsWith(before)).toBe(true);

    // Every text reply still visible, in order, across both turns.
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual([T_A, T_B, T_FINAL, T_FINAL2]);
    expect(texts.every(t => t.visible)).toBe(true);

    await context.close();
  });

  test('same-turn re-terminal heal: failTurn after done dissolves the header and reveals every row', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    expect(await page.locator('.turn-header').count()).toBe(1);

    await page.evaluate(() => window.__refailTurn());
    expect(pageErrors).toEqual([]);

    // Failed: NO header (spec A5 spirit), zero tucked rows, everything flat
    // and visible (rows never moved — the heal just clears the chrome).
    expect(await page.locator('.turn-header').count()).toBe(0);
    expect(await page.locator('.nf-tucked').count()).toBe(0);
    expect(await readSeq(page)).toEqual([
      'user',
      'thinking',
      `ai:${T_A}`,
      'tool',
      `ai:${T_B}`,
      'injected-flat',
      'tool',
      `ai:${T_FINAL}`,
    ]);
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual([T_A, T_B, T_FINAL]);
    expect(texts.every(t => t.visible)).toBe(true);

    await context.close();
  });
});

test.describe('decompression model — history rebuild path (验收 ②⑤)', () => {
  test('history: header + tuck derived from badges; mid-turn injection stays; card deliverable stays visible', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // Real ui.json entry shape (user/ai/tool/injected/card; durationMs badge
    // only on turn-final ai entries — intermediate ai entries carry none).
    const T = 1735689600000;
    const CARD = '___DEMO_HTML___' + JSON.stringify({ html: '<html><body><h1>交付卡片</h1></body></html>', title: 'demo' });
    const msgs = [
      { type: 'user', text: '开工', timestamp: T },
      { type: 'ai', thinking: '历史思考内容：先读文件。', text: '历史文字A：先读文件。' },
      { type: 'tool', label: 'Read("a.md")', summary: '10 lines', content: 'file body', isError: false, input: '{"file_path":"a.md"}' },
      { type: 'ai', text: '历史文字B：继续。' },
      { type: 'user', injected: true, source: 'team/swift-dev', text: '【Team】中途注入', timestamp: T + 1000, eventType: 'mail', sender: 'swift-dev' },
      { type: 'tool', label: 'Write("report.html")', summary: 'card', content: CARD, isError: false, input: '{"file_path":"report.html"}' },
      { type: 'ai', text: '历史最终回复。', durationMs: 3000, model: 'test-model', timestamp: T + 2000 },
      { type: 'user', injected: true, source: 'schedule', text: '【Schedule】闭合后事件', timestamp: T + 3000, eventType: 'schedule', sender: '' },
      { type: 'ai', text: '新回合回复。', durationMs: 1200, model: 'test-model', timestamp: T + 4000 },
    ];
    await page.evaluate(m => window.__history(m, { busyTail: false }), msgs);
    expect(pageErrors).toEqual([]);

    // Turn 1: header at top, thinking + both tools tucked; the card
    // deliverable (card-content row) and texts stay visible. Turn 2 is a
    // lone injection + text reply (v2: injected is not stripped, no tool) →
    // nothing to tuck → NO second header (E5 boundary default).
    expect(await page.locator('.turn-header').count()).toBe(1);
    expect(await readSeq(page)).toEqual([
      'user',
      'header',
      'thinking:tucked',
      'ai:历史文字A：先读文件。',
      'tool:tucked',
      'ai:历史文字B：继续。',
      'injected-flat',
      'tool:tucked',
      'card',
      'ai:历史最终回复。',
      'injected-flat',
      'ai:新回合回复。',
    ]);

    // Card deliverable: visible, NOT tucked (交付物单独露出). The iframe
    // renders async — wait for it to gain height.
    await page.waitForFunction(() => {
      const c = document.querySelector('#chat .row.card-content');
      return !!c && !c.classList.contains('nf-tucked') && c.offsetHeight > 0;
    }, null, { timeout: 5000 });

    // Turn-1 header counts both tools + 2 distinct files (a.md, report.html);
    // history has no thinking timing → the 思考 segment is omitted. The ✻
    // design phrase rides the done badge (seed = message index 6 → think.6,
    // deterministic) and leads the header (2026-09-06 restoration).
    const h1 = await page.locator('.turn-header-text').nth(0).textContent();
    expect(h1).toContain('✻ 从这颗星逛到那颗星，溜达了 3s');
    expect(h1).toContain('test-model');
    expect(h1).toContain('工具 2 次');
    expect(h1).toContain('读写 2 文件');
    expect(h1).not.toContain('思考');

    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual(['历史文字A：先读文件。', '历史文字B：继续。', '历史最终回复。', '新回合回复。']);
    expect(texts.every(t => t.visible)).toBe(true);

    await context.close();
  });

  test('busyTail: badge-less mid-turn tail stays flat (cursor at tail start); badge-less tail stays flat when closed', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const T = 1735689600000;
    const mkMsgs = () => ([
      { type: 'user', text: '第一轮', timestamp: T },
      { type: 'tool', label: 'Bash("ls")', summary: 'ok', content: 'out', isError: false, input: '{"command":"ls"}' },
      { type: 'ai', text: '第一轮完成。', durationMs: 3000, model: 'test-model', timestamp: T + 1000 },
      { type: 'user', text: '第二轮（还在跑）', timestamp: T + 2000 },
      { type: 'ai', text: '流式中的中间文字。' },
      { type: 'tool', label: 'Read("b.md")', summary: '2 lines', content: 'body', isError: false, input: '{"file_path":"b.md"}' },
    ]);

    // busyTail=true: the open turn's tail rows stay FLAT — live stream keeps
    // appending; the terminal will build the header.
    await page.evaluate(m => window.__history(m, { busyTail: true }), mkMsgs());
    expect(pageErrors).toEqual([]);
    expect(await page.locator('.turn-header').count()).toBe(1); // only the closed first turn
    expect(await readSeq(page)).toEqual([
      'user',
      'header',
      'tool:tucked',
      'ai:第一轮完成。',
      'user',
      'ai:流式中的中间文字。',
      'tool', // tail tool row: flat, NOT tucked (open turn)
    ]);
    // The closed turn is collapsed; the open tail's tool is visible.
    expect(await page.evaluate(() => {
      const rows = Array.from(document.getElementById('chat').children);
      const flat = rows[rows.length - 1];
      return flat.classList.contains('tool') && flat.offsetHeight > 0;
    })).toBe(true);
    await context.close();

    // busyTail=false (closed rebuild): the badge-less tail is failed/
    // unfinished — flat, NO header, texts still visible.
    const { context: ctx2, page: page2, pageErrors: err2 } = await newPage(browser);
    await page2.evaluate(m => window.__history(m, { busyTail: false }), mkMsgs());
    expect(err2).toEqual([]);
    expect(await page2.locator('.turn-header').count()).toBe(1); // only the success turn
    const seq2 = await readSeq(page2);
    expect(seq2[seq2.length - 1]).toBe('tool'); // failed tail: flat, not tucked
    expect(await page2.locator('.nf-tucked').count()).toBe(1); // only turn-1's tool
    const texts2 = await visibleTexts(page2);
    expect(texts2.map(t => t.text)).toEqual(['第一轮完成。', '流式中的中间文字。']);
    expect(texts2.every(t => t.visible)).toBe(true);
    await ctx2.close();
  });

  test('text-only turn renders no header (E5); thinking-only turn keeps thinking visible (E6)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const T = 1735689600000;
    await page.evaluate(m => window.__history(m, { busyTail: false }), [
      { type: 'user', text: '纯文字回合', timestamp: T },
      { type: 'ai', text: '只有一段中间文字。' },
      { type: 'ai', text: '最终文字。', durationMs: 800, model: 'test-model', timestamp: T + 500 },
      { type: 'user', text: '思考独占回合', timestamp: T + 1000 },
      { type: 'ai', thinking: '深度思考内容，没有文字回复。' },
    ]);
    expect(pageErrors).toEqual([]);
    expect(await page.locator('.turn-header').count()).toBe(0);
    expect(await page.locator('.nf-tucked').count()).toBe(0);
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual(['只有一段中间文字。', '最终文字。']);
    expect(texts.every(t => t.visible)).toBe(true);
    // The lone thinking row (only readable content of its turn) stays visible.
    expect(await page.locator('.thinking-row').count()).toBe(1);
    expect(await page.locator('.thinking-row').evaluate(el => el.offsetHeight > 0)).toBe(true);

    await context.close();
  });
});

test.describe('decompression model — theme screenshots (验收 d)', () => {
  for (const scheme of ['dark', 'light']) {
    test(`alternating fold renders in ${scheme} theme`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, { colorScheme: scheme });
      await page.evaluate(() => window.__liveTurn());
      expect(pageErrors).toEqual([]);

      // Fold head (persistent header) must be legible in this theme: its
      // color resolves from --color-text-dim, which differs between themes.
      const colors = await page.evaluate(() => ({
        bodyBg: getComputedStyle(document.body).backgroundColor,
        headerColor: getComputedStyle(document.querySelector('.turn-header')).color,
      }));
      expect(colors.headerColor).not.toBe(colors.bodyBg);

      const shot = path.join(SHOT_DIR, `20260903_collapse-keep-text-${scheme}.png`);
      await page.screenshot({ path: shot, fullPage: true });
      expect(fs.existsSync(shot)).toBe(true);
      await context.close();
    });
  }
});
