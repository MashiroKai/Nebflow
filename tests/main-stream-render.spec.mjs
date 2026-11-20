// main-stream-render.spec.mjs — 主窗助手正文「边流边显」专项闸（stream-latency 批,
// 2026-11-19 作者令 + 四腿诊断候选②）。
//
// 【这批解决什么】LLM 流式慢四腿诊断（作者侧文档
//   `~/.stand/projects/stand/.stand/docs/stream-latency-four-leg-diagnosis__20261119_145015.md`）
//   判定：慢的主因在供应商首字 TTFT（~3–5s，Stand 侧不可直降），但最容易被读成
//   「流得慢」的是**可见性缺口** —— 主窗助手正文不逐块渲染（唯一渲染点 finishAi，
//   长纯文本回复「等到结尾才出现」）。本批把正文接进**既有**增量渲染器
//   （chat.js `renderStreamMarkdown`）+ **既有** rAF 合批（`scheduleStreamRender(view,'ai',…)`），
//   不新增第二套渲染路径。
//
// 【本闸的四条判据】
//   N1 逐帧推进：流式期每一采样帧都带已渲染正文（旧契约：每一帧都空、段末整段弹出）
//   N2 终帧等价（防退化关键）：流式收口后的最终 DOM 与「一次性全量重渲」**逐字节等价**，
//      且与「关腿走旧路径」的终态 DOM **逐字节等价** —— 流式只是过程变了、结果没变
//   N3 关腿负控：关掉本次新增的腿 ⇒ 回到「段末整段弹出」（流式期零非空帧 + 终态整条
//      入场 pop 回来），且 N1 的判据在同一状态下为 **false**（= 绿腿必红这个反例，
//      在闸里机械可读）
//   N4 帧预算：每帧至多 1 次渲染（rAF 合批）+ 每帧渲染耗时读数（防 O(n²)）
//
// 【关腿负控的可复跑红腿】（反例必红，实测 transcript 见本批证据目录）
//   MSR_LEG=off npx playwright test tests/main-stream-render.spec.mjs
//   ⇒ 每个页面在开机后把「边流边显」腿关掉（= 模拟本批新增腿缺席/被拿掉），随后
//     **照原样**跑全部判据 ⇒ N1 必红（流式期零非空帧）。默认（腿在场）全绿。
//
// 判据面 = 渲染后的 DOM / 帧级读数，不读源码措辞。静态服务器由本 spec 自起自杀，
// 宿主 :8080 永不触碰（按端口断言 + 构造上：根 = 本 spec 指向的那棵树）。

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
// 专用夹具：与 stream-ux 的夹具同构，但**装载真 marked**（同 index.html 的
// vendor/marked.min.js）⇒ markdown 渲染链是真的，「终帧逐字节等价」不空泛。
const HARNESS_PATH = '/tests/fixtures/main-stream-render/harness.html';
const SHOT_DIR = process.env.MSR_SHOT_DIR ||
  path.join(os.homedir(), '.stand', 'tmp', 'main-stream-render-20261119');

// 关腿负控：把本批新增的腿模拟成「不在场」。默认 = 腿在场（绿腿）。
const LEG_OFF = process.env.MSR_LEG === 'off';

// 一条足够长的多段回复：有 `\n\n` 边界（走 renderStreamMarkdown 的**尾块增量**路径：
// 已闭合块只解析一次、每帧只重解析未闭合尾块），也含围栏 / 列表 / 行内代码。
const TEXT = [
  '# 摘要',
  '',
  '正文第一段：这是一条长回复的开头，用来验证主窗助手正文边流边显，而不是等到段末整段弹出。',
  '',
  '正文第二段：`inline code` 与 **加粗** 混排，看逐帧推进是否稳定。',
  '',
  '- 列表项 A',
  '- 列表项 B',
  '',
  '```js',
  'const a = 1;',
  '```',
  '',
  '正文第三段：结尾。',
].join('\n');

/** 把 TEXT 切成 n 段（按字符均匀切，保持顺序拼回 === TEXT）。 */
function chunkText(text, n) {
  const size = Math.ceil(text.length / n);
  const parts = [];
  for (let i = 0; i < text.length; i += size) parts.push(text.slice(i, i + size));
  return parts;
}

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
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(spawn('python3',
    ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', REPO_ROOT],
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

async function newPage(browser, { legOn = !LEG_OFF } = {}) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  // 腿开关（负控的唯一手段）：本批新增的 `setAiBodyStreamRender`。
  await page.evaluate(async (on) => {
    const chatMod = await import('/src/main/resources/web/js/chat.js');
    chatMod.setAiBodyStreamRender(on);
  }, legOn);
  return { context, page, pageErrors };
}

const legEnabled = (page) => page.evaluate(async () => {
  const chatMod = await import('/src/main/resources/web/js/chat.js');
  return chatMod.aiBodyStreamRenderEnabled();
});

/** 驱动器：喂 N 段 delta（段间让帧跑完），返回采样器读到的逐帧读数。 */
async function streamAndSample(page, parts) {
  await page.evaluate(() => window.__reset());
  await page.evaluate(() => window.__startAiSampler());
  await page.evaluate((p) => window.__aiStream(p), parts);
  const frames = await page.evaluate(() => window.__stopAiSampler());
  const withBubble = frames.filter(f => f.childElems >= 0);
  const nonEmpty = withBubble.filter(f => f.htmlLen > 0);
  return {
    frames,
    withBubble,
    nonEmpty,
    emptyWithBubble: withBubble.filter(f => f.htmlLen === 0),
    distinctSizes: new Set(nonEmpty.map(f => f.textLen)).size,
    monotone: frames.every((f, i) => i === 0 || f.textLen >= frames[i - 1].textLen),
  };
}

/** 终态读数：气泡 DOM（含 innerHTML）+ 入场动画名。 */
const terminalRead = (page) => page.evaluate(() => {
  const rows = Array.from(document.querySelectorAll('#chat .row.ai'))
    .filter(r => !r.classList.contains('thinking-row'));
  const row = rows[rows.length - 1];
  const b = row ? row.querySelector('.bubble.ai') : null;
  return b ? {
    innerHTML: b.innerHTML,
    textLen: (b.textContent || '').length,
    animNames: b.getAnimations().map(a => a.animationName || '(script)'),
    cls: Array.from(b.classList).join('.'),
  } : null;
});

/** 「一次性全量重渲」的参考产物（终态收口走的就是这条：renderMarkdownWithMath(text)）。
 *  刻意**回读 serialized innerHTML**（而不是拿原始 HTML 串）——与读到的那一侧同口径：
 *  DOM 序列化会把 SVG 的自闭合标签规范化（`<rect/>` → `<rect></rect>`），拿原始串比
 *  会误报差异。 */
const fullRenderRef = (page, text) => page.evaluate(async (t) => {
  const utilsMod = await import('/src/main/resources/web/js/utils.js');
  const holder = document.createElement('div');
  holder.innerHTML = utilsMod.renderMarkdownWithMath(t, true);
  return holder.innerHTML;
}, text);

test.describe('N1 — 主窗正文逐帧推进（边流边显）', () => {
  test('every streamed frame carries rendered markdown; the text only grows', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    expect(await legEnabled(page), 'the 边流边显 leg is ON (red leg: MSR_LEG=off flips this)').toBe(true);

    const parts = chunkText(TEXT, 24);
    const r = await streamAndSample(page, parts);
    expect(pageErrors).toEqual([]);
    expect(r.withBubble.length, 'the reply bubble was on screen across the stream').toBeGreaterThan(5);
    // 旧契约（整条一次画出）下这一条恰为 0；新契约下**每一**绘制帧都带正文，
    // 至多允许「首个绘制帧之前那一拍」是空的（采样器先于渲染 rAF 取数）。
    expect(r.nonEmpty.length, 'the streamed frames carry rendered markdown (旧契约：0)')
      .toBeGreaterThanOrEqual(r.withBubble.length - 1);
    expect(r.emptyWithBubble.length, 'at most the pre-first-paint frame is empty').toBeLessThanOrEqual(1);
    expect(r.monotone, 'the text only ever grew across the stream (逐帧推进)').toBe(true);
    expect(r.distinctSizes, 'more than one size across the painted frames (真的在推进)').toBeGreaterThan(2);

    await page.evaluate(() => window.__aiStreamFinish());
    const term = await terminalRead(page);
    expect(term, 'the terminal bubble is readable').not.toBeNull();
    expect(term.textLen, 'the whole reply is present at the terminal').toBeGreaterThan(TEXT.length * 0.8);
    expect(term.animNames.length,
      'no whole-message entrance pop on a bubble that already streamed in (入场动画 = 整条一次画出的标记)')
      .toBe(0);
    await page.screenshot({ path: path.join(SHOT_DIR, 'n1-on-terminal.png') });
    await context.close();
  });
});

test.describe('N2 — 终帧等价（防退化：过程变了，结果没变）', () => {
  test('the streamed terminal DOM is byte-identical to a one-shot full render', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const parts = chunkText(TEXT, 24);
    await streamAndSample(page, parts);
    await page.evaluate(() => window.__aiStreamFinish());
    const on = await terminalRead(page);
    const ref = await fullRenderRef(page, TEXT);
    expect(pageErrors).toEqual([]);
    // 非空泛化：参考产物必须真的带结构（否则「逐字节相等」是空话）。
    expect(ref.includes('<h1'), 'the reference render carries the rendered heading (equality is not vacuous)')
      .toBe(true);
    expect(ref.includes('<ul>') && ref.includes('<pre>'),
      'and the rendered list + code fence').toBe(true);
    expect(on.innerHTML, 'the streamed terminal DOM equals the from-scratch full render byte-for-byte')
      .toBe(ref);
    await context.close();
  });

  test('and it equals the leg-OFF (legacy path) terminal DOM for the same text', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const parts = chunkText(TEXT, 24);
    // 流式腿（ON）跑一遍
    await streamAndSample(page, parts);
    await page.evaluate(() => window.__aiStreamFinish());
    const on = await terminalRead(page);
    // 同一页里关掉腿（旧路径）再跑同一文本
    await page.evaluate(async () => {
      const chatMod = await import('/src/main/resources/web/js/chat.js');
      chatMod.setAiBodyStreamRender(false);
    });
    await streamAndSample(page, parts);
    await page.evaluate(() => window.__aiStreamFinish());
    const off = await terminalRead(page);
    expect(pageErrors).toEqual([]);
    expect(off.innerHTML, 'the legacy path terminal DOM is non-trivial').toBe(await fullRenderRef(page, TEXT));
    expect(on.innerHTML, 'the two paths land on the SAME terminal DOM (流式只是过程变了)')
      .toBe(off.innerHTML);
    await context.close();
  });
});

test.describe('N3 — 关腿负控（关掉本批新增的腿 ⇒ 回到段末整段弹出）', () => {
  test('with the leg OFF the bubble stays EMPTY while streaming, and N1 reads false', async ({ browser }) => {
    // 本块**总是**在页内把腿关掉（与 MSR_LEG 无关），因为它断言的就是关腿后的旧行为；
    // 若把 N1 的判据原样套到同一状态 ⇒ 必为 false ⇒ 绿腿在关腿状态下必红。
    const { context, page, pageErrors } = await newPage(browser, { legOn: false });
    expect(await legEnabled(page), 'the leg is OFF in this control').toBe(false);

    const parts = chunkText(TEXT, 24);
    const r = await streamAndSample(page, parts);
    expect(pageErrors).toEqual([]);
    expect(r.withBubble.length, 'the reply row still exists while its text streams').toBeGreaterThan(3);
    // 反例本体：旧契约的形状——流式期**零**非空帧。
    expect(r.nonEmpty.length, 'LEGACY: no streamed frame holds rendered markdown (= 段末整段弹出)').toBe(0);
    expect(r.distinctSizes, 'LEGACY: the bubble never grew (single size = 0)').toBe(0);
    // N1 的判据在同一状态下的读数（机械可读的反例 = 绿腿会红在这里）。
    const n1Holds = r.nonEmpty.length >= r.withBubble.length - 1 && r.withBubble.length > 5;
    expect(n1Holds, 'the N1 criterion reads FALSE with the leg off (= 若本批新增腿被拿掉，N1 必红)')
      .toBe(false);

    await page.evaluate(() => window.__aiStreamFinish());
    const term = await terminalRead(page);
    expect(term.textLen, 'the whole reply lands in ONE terminal paint').toBeGreaterThan(TEXT.length * 0.8);
    expect(term.animNames.length,
      'and the whole-message entrance pop is BACK (旧行为在场的直接证据)').toBeGreaterThan(0);
    expect(term.innerHTML, 'the legacy terminal DOM still equals the from-scratch full render')
      .toBe(await fullRenderRef(page, TEXT));
    await page.screenshot({ path: path.join(SHOT_DIR, 'n3-leg-off-terminal.png') });
    await context.close();
  });
});

test.describe('N4 — 帧预算（rAF 合批，不重犯 O(n²)）', () => {
  test('12 deltas in ONE task render ONCE; per-frame render time is reported', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const read = await page.evaluate(async () => {
      const chatMod = await import('/src/main/resources/web/js/chat.js');
      const raf = () => new Promise(r => requestAnimationFrame(r));
      const stats = chatMod.enableAiStreamStats();
      chatMod.renderUserBubble('把结论写给我', [], Date.now());
      for (let i = 0; i < 12; i++) chatMod.appendAiText(`段${i} `);
      const before = stats.frames;
      await raf(); await raf();
      const afterBurst = stats.frames;
      const t0 = performance.now();
      for (let i = 0; i < 4; i++) { chatMod.appendAiText(`逐帧${i} `); await raf(); }
      await raf();
      const steady = stats.frames - afterBurst;
      return {
        rendersBeforeFrame: before,
        rendersAfterBurst: afterBurst,
        steadyRenders: steady,
        frames: stats.frames,
        maxMs: stats.maxMs,
        meanMs: stats.frames ? stats.totalMs / stats.frames : -1,
        msSamples: stats.msSamples.slice(),
        wallMs: performance.now() - t0,
      };
    });
    expect(read.rendersBeforeFrame, 'no render before the scheduled rAF (12 deltas coalesce)').toBe(0);
    expect(read.rendersAfterBurst, 'twelve deltas in one task ⇒ EXACTLY ONE render').toBe(1);
    expect(read.steadyRenders, 'one-delta-per-frame renders at least once per frame').toBeGreaterThanOrEqual(3);
    expect(read.steadyRenders, 'and never more than one render per frame (rAF batching)').toBeLessThanOrEqual(5);
    console.log(`[N4 frame budget] renders=${read.frames} maxMs=${read.maxMs.toFixed(2)} `
      + `meanMs=${read.meanMs.toFixed(2)} samples=[${read.msSamples.map(v => v.toFixed(2)).join(', ')}]`);
    expect(read.maxMs, 'per-frame render stays inside a generous frame budget (no O(n²) blow-up)')
      .toBeLessThan(50);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
