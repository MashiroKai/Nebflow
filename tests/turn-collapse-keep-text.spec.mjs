// turn-collapse-keep-text.spec.mjs — 2026-09-03 ruling regression (工具过程
// 自动收起——保留 LLM 中间文字回复) + 2026-09-05 turn 级单组化修订.
//
// Ruling: the collapse set is tool blocks + tool results + injected events
// ONLY. EVERY assistant text row stays visible — text is never moved into
// (or out of) the group. 2026-09-05 ruling (author report: multi-round turn
// showed one badge PER LLM ROUND): a turn interleaving text with tool rounds
//   文字A → 工具1 → 文字B → 注入 → 工具2 → 最终回复
// gathers ALL collapsible rows into ONE `.turn-group` (turn-level
// aggregation), landed immediately before the final reply; the texts render
// flat in front of it, chronological order preserved, and the bar counts the
// WHOLE turn's tools. Turn-level closure semantics unchanged (#403
// 2026-08-26: events arriving after closure open a NEW group).
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

/** Top-level DOM sequence of #chat: user / injected-flat / ai:<full text> /
 *  group:<row kinds>. Group row kinds = tool | injected | thinking. */
function readSeq(page) {
  return page.evaluate(() => {
    const chat = document.getElementById('chat');
    return Array.from(chat.children).map(k => {
      if (k.classList.contains('turn-group')) {
        const rows = Array.from(k.querySelectorAll('.turn-steps > .row'));
        const kinds = rows.map(r =>
          r.querySelector('.bubble.injected') ? 'injected'
            : r.classList.contains('tool') ? 'tool'
            : r.classList.contains('thinking-row') ? 'thinking'
            : `other:${r.className}`);
        return `group:${kinds.join('+')}`;
      }
      if (k.classList.contains('row')) {
        if (k.querySelector('.bubble.injected')) return 'injected-flat';
        if (k.classList.contains('user')) return 'user';
        if (k.classList.contains('ai')) return 'ai:' + norm(k);
      }
      return 'other:' + k.className;
    });
    function norm(row) {
      return ((row.querySelector('.bubble.ai')?.textContent) || '').trim().replace(/\s+/g, '');
    }
  });
}

/** Assistant TEXT rows (thinking rows are not text replies) NOT inside a
 *  collapse group — full text + visibility. */
function visibleTexts(page) {
  return page.evaluate(() => {
    return Array.from(document.querySelectorAll('#chat .row.ai'))
      .filter(r => !r.classList.contains('thinking-row') && !r.closest('.turn-steps'))
      .map(r => ({
        text: ((r.querySelector('.bubble.ai')?.textContent) || '').trim().replace(/\s+/g, ''),
        visible: r.offsetHeight > 0,
      }));
  });
}

const T_A = '好的，我先检索资料，再逐段汇报进展。';
const T_B = '找到关键线索了：收起分组在turnGroup.js，继续核对细节。';
const T_FINAL = '这是最终回复：中间文字全部保留，工具过程已折叠。';
const T_FINAL2 = '新回合的最终回复。';

test.describe('collapse keeps LLM text — live alternating turn (验收 a)', () => {
  test('文字→工具→文字→注入→工具→最终: ONE turn group, texts visible in front, bar counts whole turn', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    expect(pageErrors).toEqual([]);

    // 2026-09-05: ONE group for the whole turn (was one per contiguous run).
    expect(await page.locator('.turn-group').count()).toBe(1);

    // Top-level order: user → 文字A → 文字B → G(工具1+注入+工具2) → 最终.
    // Texts stay flat in front of the single group; the group sits right
    // before the final reply (badge-before-answer).
    expect(await readSeq(page)).toEqual([
      'user',
      `ai:${T_A}`,
      `ai:${T_B}`,
      'group:tool+injected+tool',
      `ai:${T_FINAL}`,
    ]);

    // All three text replies visible, chronological order, final LAST.
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual([T_A, T_B, T_FINAL]);
    expect(texts.every(t => t.visible)).toBe(true);

    // No assistant text inside the collapse group; the group is collapsed.
    expect(await page.locator('.turn-steps .bubble.ai').count()).toBe(0);
    for (const steps of await page.locator('.turn-steps').all()) {
      expect(await steps.evaluate(el => el.style.display)).toBe('none');
    }

    // The single bar carries the WHOLE-turn tool count (工具1+工具2 = 2).
    const summaries = await page.locator('.turn-summary-text').allTextContents();
    expect(summaries.length).toBe(1);
    expect(summaries[0]).toContain('工具 2 次');
    expect(summaries[0]).toContain('test-model');

    // Expanding the bar reveals 工具1+注入+工具2 exactly between 文字B and 最终.
    await page.locator('.turn-summary').first().click();
    await expect(page.locator('.turn-group').first().locator('.turn-steps')).toBeVisible();
    const pos = await page.evaluate(() => {
      const rows = Array.from(document.getElementById('chat').children);
      const tB = rows[2], g1 = rows[3], fin = rows[4];
      return {
        between: !!(tB.compareDocumentPosition(g1) & Node.DOCUMENT_POSITION_FOLLOWING)
          && !!(g1.compareDocumentPosition(fin) & Node.DOCUMENT_POSITION_FOLLOWING),
        toolsVisible: g1.querySelectorAll('.row.tool').length === 2
          && Array.from(g1.querySelectorAll('.row.tool')).every(r => r.offsetHeight > 0),
      };
    });
    expect(pos.between).toBe(true);
    expect(pos.toolsVisible).toBe(true);

    await context.close();
  });
});

test.describe('collapse keeps LLM text — closure semantics (验收 b)', () => {
  test('#403: external injection after closure opens a NEW group; closed groups untouched', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    expect(await page.locator('.turn-group').count()).toBe(1);
    const before = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-group')).map(g => g.innerHTML));

    await page.evaluate(() => window.__postClosureTurn());
    expect(pageErrors).toEqual([]);

    // Second group appeared (turn 2); it alone holds the post-closure
    // injected + tool — no fusion with the closed turn-1 group.
    expect(await page.locator('.turn-group').count()).toBe(2);
    expect(await readSeq(page)).toEqual([
      'user',
      `ai:${T_A}`,
      `ai:${T_B}`,
      'group:tool+injected+tool',
      `ai:${T_FINAL}`,
      'group:injected+tool',
      `ai:${T_FINAL2}`,
    ]);

    // The closed turn-1 group is byte-identical to before the new turn.
    const after = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.turn-group')).map(g => g.innerHTML));
    expect(after[0]).toBe(before[0]);

    // Every text reply still visible, in order, across both turns.
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual([T_A, T_B, T_FINAL, T_FINAL2]);
    expect(texts.every(t => t.visible)).toBe(true);

    await context.close();
  });

  test('same-turn re-terminal heal: failTurn after done dissolves and re-gathers the turn group', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__liveTurn());
    expect(await page.locator('.turn-group').count()).toBe(1);

    await page.evaluate(() => window.__refailTurn());
    expect(pageErrors).toEqual([]);

    // Re-gathered as failed: still ONE turn group, but NO summary bar (spec
    // A5) and steps expanded.
    expect(await page.locator('.turn-group').count()).toBe(1);
    expect(await page.locator('.turn-summary').count()).toBe(0);
    for (const steps of await page.locator('.turn-steps').all()) {
      expect(await steps.evaluate(el => el.style.display)).toBe('');
    }
    // Texts stayed flat and visible through the heal.
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual([T_A, T_B, T_FINAL]);
    expect(texts.every(t => t.visible)).toBe(true);

    await context.close();
  });
});

test.describe('collapse keeps LLM text — history rebuild path (验收 b/c)', () => {
  test('history: mid-turn injection collapses into the turn; post-badge injection opens a new group', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // Real ui.json entry shape (user/ai/tool/injected; durationMs badge only
    // on turn-final ai entries — intermediate ai entries carry none).
    const T = 1735689600000;
    const msgs = [
      { type: 'user', text: '开工', timestamp: T },
      { type: 'ai', text: '历史文字A：先读文件。' },
      { type: 'tool', label: 'Read("a.md")', summary: '10 lines', content: 'file body', isError: false, input: '{"file_path":"a.md"}' },
      { type: 'ai', text: '历史文字B：继续。' },
      { type: 'user', injected: true, source: 'team/swift-dev', text: '【Team】中途注入', timestamp: T + 1000, eventType: 'mail', sender: 'swift-dev' },
      { type: 'tool', label: 'Bash("ls")', summary: 'ok', content: 'out', isError: false, input: '{"command":"ls"}' },
      { type: 'ai', text: '历史最终回复。', durationMs: 3000, model: 'test-model', timestamp: T + 2000 },
      { type: 'user', injected: true, source: 'schedule', text: '【Schedule】闭合后事件', timestamp: T + 3000, eventType: 'schedule', sender: '' },
      { type: 'ai', text: '新回合回复。', durationMs: 1200, model: 'test-model', timestamp: T + 4000 },
    ];
    await page.evaluate(m => window.__history(m, { busyTail: false }), msgs);
    expect(pageErrors).toEqual([]);

    // Turn 1 = ONE group holding 工具A+中途注入+工具B, landed before the
    // final reply; the post-badge injection opens turn 2 with its own group.
    expect(await page.locator('.turn-group').count()).toBe(2);
    expect(await readSeq(page)).toEqual([
      'user',
      'ai:历史文字A：先读文件。',
      'ai:历史文字B：继续。',
      'group:tool+injected+tool',
      'ai:历史最终回复。',
      'group:injected',
      'ai:新回合回复。',
    ]);

    // Mid-turn injection is INSIDE its turn group; the post-badge injection
    // got its own group (history side of the #403 closure ruling).
    expect(await page.locator('.turn-steps .bubble.injected').count()).toBe(2);
    expect(await page.locator('.turn-steps .bubble.ai').count()).toBe(0);

    // Both tool runs collapsed (badges = success).
    for (const steps of await page.locator('.turn-steps').all()) {
      expect(await steps.evaluate(el => el.style.display)).toBe('none');
    }

    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual(['历史文字A：先读文件。', '历史文字B：继续。', '历史最终回复。', '新回合回复。']);
    expect(texts.every(t => t.visible)).toBe(true);

    await context.close();
  });

  test('busyTail: badge-less mid-turn tail stays flat (cursor at tail start); badge-less tail groups as failed when closed', async ({ browser }) => {
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
    // appending; the terminal will gather them.
    await page.evaluate(m => window.__history(m, { busyTail: true }), mkMsgs());
    expect(pageErrors).toEqual([]);
    expect(await page.locator('.turn-group').count()).toBe(1); // only the closed first turn
    expect(await readSeq(page)).toEqual([
      'user',
      'group:tool',
      'ai:第一轮完成。',
      'user',
      'ai:流式中的中间文字。',
      'other:row tool', // tail tool row: flat, NOT gathered (open turn)
    ]);
    // The closed turn is collapsed; the open tail's tool is visible.
    expect(await page.locator('.turn-group .turn-steps').first().evaluate(el => el.style.display)).toBe('none');
    expect(await page.evaluate(() => {
      const rows = Array.from(document.getElementById('chat').children);
      const flat = rows[rows.length - 1];
      return flat.classList.contains('tool') && flat.offsetHeight > 0;
    })).toBe(true);
    await context.close();

    // busyTail=false (closed rebuild): the badge-less tail groups as failed —
    // expanded, NO summary bar (A5), texts still visible.
    const { context: ctx2, page: page2, pageErrors: err2 } = await newPage(browser);
    await page2.evaluate(m => window.__history(m, { busyTail: false }), mkMsgs());
    expect(err2).toEqual([]);
    expect(await page2.locator('.turn-group').count()).toBe(2);
    expect(await page2.locator('.turn-summary').count()).toBe(1); // only the success turn has a bar
    const g1 = await page2.locator('.turn-group .turn-steps').first().evaluate(el => el.style.display);
    const g2 = await page2.locator('.turn-group .turn-steps').last().evaluate(el => el.style.display);
    expect(g1).toBe('none'); // success turn collapsed
    expect(g2).toBe('');     // failed tail expanded
    const texts2 = await visibleTexts(page2);
    expect(texts2.map(t => t.text)).toEqual(['第一轮完成。', '流式中的中间文字。']);
    expect(texts2.every(t => t.visible)).toBe(true);
    await ctx2.close();
  });

  test('text-only turn collapses nothing (E5); thinking-only turn keeps thinking visible (E6)', async ({ browser }) => {
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
    expect(await page.locator('.turn-group').count()).toBe(0);
    const texts = await visibleTexts(page);
    expect(texts.map(t => t.text)).toEqual(['只有一段中间文字。', '最终文字。']);
    expect(texts.every(t => t.visible)).toBe(true);
    // The lone thinking row (only readable content of its turn) stays visible.
    expect(await page.locator('.thinking-row').count()).toBe(1);
    expect(await page.locator('.thinking-row').evaluate(el => el.offsetHeight > 0)).toBe(true);

    await context.close();
  });
});

test.describe('collapse keeps LLM text — theme screenshots (验收 d)', () => {
  for (const scheme of ['dark', 'light']) {
    test(`alternating fold renders in ${scheme} theme`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, { colorScheme: scheme });
      await page.evaluate(() => window.__liveTurn());
      expect(pageErrors).toEqual([]);

      // Fold head (summary bar) must be legible in this theme: its color
      // resolves from --color-text-dim, which differs between themes.
      const colors = await page.evaluate(() => ({
        bodyBg: getComputedStyle(document.body).backgroundColor,
        summaryColor: getComputedStyle(document.querySelector('.turn-summary')).color,
      }));
      expect(colors.summaryColor).not.toBe(colors.bodyBg);

      const shot = path.join(SHOT_DIR, `20260903_collapse-keep-text-${scheme}.png`);
      await page.screenshot({ path: shot, fullPage: true });
      expect(fs.existsSync(shot)).toBe(true);
      await context.close();
    });
  }
});
