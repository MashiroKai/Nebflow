// pop-media-trackpad-swipe.spec.mjs — 图片叠卡触控板横滑切换批
// （2026-10-22 作者令逐字：「你发的图片叠卡和我发的图片叠卡，除了长按左右拖动
//   来切换，mac的双指左右滑动也要支持。」）
//
//   T1 双指轻扫翻页 — real wheel 事件流（page.mouse.wheel）横滑 ≥56px 累积
//      ⇒ 下一张；反方向 ⇒ 上一张；环序回绕成立。LLM Pop 叠卡与用户附件叠卡
//      同 builder（「你发的和我发的」两侧同款），T6 用用户侧再钉一遍。
//   T2 阈值防误触 — 累积不足 56px（微 deltaX 抖动量级）⇒ 不翻页。
//   T3 惯性不重复 — 一次横扫翻一页后，惯性尾巴（紧接着的 wheel 流）不重复
//      翻页；事件流静默 ≥160ms 后重新武装，新一轮横扫照常翻页。
//   T4 纵滚放行 — 叠卡上双指上下滚不劫持：聊天列原生滚动照旧（scrollTop
//      实动）且叠卡不翻页（含斜漂微 deltaX 噪声的纵滚）。
//   T5 与长按拖动共存 — wheel 翻页后 pointer 拖动照常换卡；按下未释放的拖划
//      进行中 wheel 不翻页（判定窗口分开，不打架）。
//   T7 Shift+纵滚 — 显式横意（环同款仲裁）⇒ 翻页。
//
// 手法同图谱胶囊环批（canvas.js initCapsuleRing）：deltaX 累积 + 阈值步进 +
// 动量抑制；纵滚不劫持是该批（叠卡嵌在聊天流里）相对环批的收敛点。
//
// 事件一律走 REAL 输入管线：page.mouse.wheel 在叠卡盒心派发（Playwright 的
// wheel 驱动 Input.dispatchMouseEvent mouseWheel，带当前修饰键态）。
//
// The spec drives the REAL render modules through
// tests/fixtures/pop-artifacts/harness.html (same self-spawned static server as
// pop-media-alignment.spec.mjs — never the host instance). Probes read the
// RENDERED DOM (front = .layer-front 的那张卡).
//
// Run: node node_modules/@playwright/test/cli.js test tests/pop-media-trackpad-swipe.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-artifacts/harness.html';

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
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors };
}

/** Render the LLM Pop 3-image stack (shot-1/2/3.png). */
async function renderStack(page, layers = 3) {
  await page.evaluate(() => window.__popStack());
  await page.waitForFunction(
    n => document.querySelectorAll('#chat .pop-stack-view .media-layer').length === n,
    layers, { timeout: 10000 },
  );
  await page.waitForTimeout(300); // data-URI bytes decode; boxes pin their ratio
}

/** The front card's file name in the collapsed stack (rowSel scopes user vs Pop). */
async function frontName(page, rowSel = '#chat > .row.pop-artifact .pop-stack-view') {
  return page.evaluate((sel) =>
    document.querySelector(sel + ' .media-layer.layer-front .pop-img')?.dataset.nfName || '', rowSel);
}

/** Park the mouse at the stack's center (scroll into view + settle). */
async function parkAtView(page, view) {
  await view.scrollIntoViewIfNeeded();
  await page.waitForTimeout(120);
  const box = await view.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(60);
}

/** REAL trackpad burst: `steps` wheel events of (dx, dy) at `gapMs` intervals,
 *  dispatched at the CURRENT mouse position (park first with parkAtView).
 *  Playwright's wheel drives Input.dispatchMouseEvent mouseWheel — the real
 *  input path; no re-parking between bursts, so momentum windows measured
 *  from the event stream stay truthful. */
async function wheelSteps(page, { dx = 0, dy = 0, steps = 7, gapMs = 15, shift = false } = {}) {
  if (shift) await page.keyboard.down('Shift');
  try {
    for (let i = 0; i < steps; i++) {
      await page.mouse.wheel(dx, dy);
      if (i < steps - 1) await page.waitForTimeout(gapMs);
    }
  } finally {
    if (shift) await page.keyboard.up('Shift');
  }
}

/** One-shot convenience: park + one burst. */
async function wheelBurst(page, view, opts = {}) {
  await parkAtView(page, view);
  await wheelSteps(page, opts);
}

/* ═══════════ T1 — 双指轻扫翻页（LLM Pop 叠卡）═══════════ */

test.describe('T1 — trackpad two-finger horizontal swipe flips the deck', () => {
  test('swipe left = next, swipe right = previous, ring wraps (Pop stack)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page);
    expect(await frontName(page), 'front before any swipe').toBe('shot-1.png');

    const view = page.locator('#chat .pop-stack-view');
    await wheelBurst(page, view, { dx: 10, steps: 7 });   // 70px 累积 ≥ 56
    expect(await frontName(page), 'swipe left (deltaX>0) → 下一张').toBe('shot-2.png');

    await wheelBurst(page, view, { dx: -10, steps: 7 });
    expect(await frontName(page), 'swipe right (deltaX<0) → 上一张').toBe('shot-1.png');

    // 环序回绕：shot-1 连翻三张 ⇒ 2 → 3 → 回 1（动量窗之间各静默一次）。
    await wheelBurst(page, view, { dx: 10, steps: 7 });
    expect(await frontName(page), 'flip 1 of the wrap').toBe('shot-2.png');
    await page.waitForTimeout(220);                        // 动量窗静默后重新武装
    await wheelBurst(page, view, { dx: 10, steps: 7 });
    expect(await frontName(page), 'flip 2 of the wrap').toBe('shot-3.png');
    await page.waitForTimeout(220);
    await wheelBurst(page, view, { dx: 10, steps: 7 });
    expect(await frontName(page), 'flip 3 wraps the ring back to shot-1').toBe('shot-1.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ T2 — 阈值防误触（微 deltaX 抖动不过阈）═══════════ */

test.describe('T2 — below-threshold jitter never flips', () => {
  test('40px of micro deltas (jitter class) leaves the front untouched', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page);
    await wheelBurst(page, page.locator('#chat .pop-stack-view'), { dx: 5, steps: 8, gapMs: 10 }); // 40px < 56
    await page.waitForTimeout(200);
    expect(await frontName(page), 'sub-threshold accumulation never flips').toBe('shot-1.png');

    // 同一事件流补到阈上 ⇒ 照常翻页（累积语义不是一次性闸门）。
    await wheelBurst(page, page.locator('#chat .pop-stack-view'), { dx: 8, steps: 7 }); // 再 56px
    expect(await frontName(page), 'crossing the threshold flips exactly once').toBe('shot-2.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ T3 — 惯性滚动尾巴不重复翻页；静默后重新武装 ═══════════ */

test.describe('T3 — momentum tail does not re-flip; quiet re-arms', () => {
  test('one swipe = one page; the inertia tail is swallowed; a fresh swipe after the quiet window flips again', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page);
    const view = page.locator('#chat .pop-stack-view');
    await parkAtView(page, view);

    // 横扫（翻 1 页）——紧接着、同一事件流惯性相位内立刻喂尾巴，
    // 中间不插任何 evaluate/移动（动量窗从事件流计量才真实）。
    await wheelSteps(page, { dx: 10, steps: 7, gapMs: 12 });
    await wheelSteps(page, { dx: 8, steps: 8, gapMs: 12 });   // 尾巴再贡献 64px ≥ 阈值量
    expect(await frontName(page), 'the swipe flipped once; the momentum tail did NOT re-flip (一次动量窗口只翻一页)').toBe('shot-2.png');

    // 事件流静默 ≥160ms 后重新武装：新一轮横扫照常翻页。
    await page.waitForTimeout(240);
    await wheelSteps(page, { dx: 10, steps: 7, gapMs: 12 });
    expect(await frontName(page), 'a fresh swipe after the quiet window flips again').toBe('shot-3.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ T4 — 纵滚放行：聊天列原生滚动不被劫持 ═══════════ */

test.describe('T4 — vertical scroll over the stack stays native', () => {
  test('two-finger vertical scroll scrolls the chat column and never flips the deck', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page);
    // 填满聊天列让 #chat 可滚（叠卡嵌在聊天流里——纵滚必须继续滚它）。
    await page.evaluate(() => {
      const chat = document.getElementById('chat');
      for (let i = 0; i < 30; i++) {
        const row = document.createElement('div');
        row.className = 'row ai';
        const b = document.createElement('div');
        b.className = 'bubble ai';
        b.textContent = '填充行 ' + i + ' —— 让聊天列出现纵向滚动条。';
        row.appendChild(b);
        chat.insertBefore(row, chat.firstChild);
      }
    });
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
    const view = page.locator('#chat .pop-stack-view');
    const before = await page.evaluate(() => document.getElementById('chat').scrollTop);

    // 纯纵滚 + 斜漂微 deltaX 噪声（竖向主导）：不翻页，聊天列真滚。
    await wheelBurst(page, view, { dx: 3, dy: 60, steps: 6, gapMs: 12 });
    const after = await page.evaluate(() => ({
      scrollTop: document.getElementById('chat').scrollTop,
      front: document.querySelector('#chat .pop-stack-view .media-layer.layer-front .pop-img')?.dataset.nfName || '',
    }));
    expect(after.scrollTop, 'the chat column scrolled natively over the stack').toBeGreaterThan(before);
    expect(after.front, 'vertical scroll never flips the deck').toBe('shot-1.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ T5 — 与长按左右拖动共存（判定窗口分开）═══════════ */

test.describe('T5 — wheel swipe coexists with the press-drag gesture', () => {
  test('drag still works after wheel flips; wheel yields while a drag is in flight', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page);
    const view = page.locator('#chat .pop-stack-view');

    // 先 wheel 翻一页。
    await wheelBurst(page, view, { dx: 10, steps: 7 });
    expect(await frontName(page), 'wheel swipe flipped first').toBe('shot-2.png');

    // 长按拖动照常换卡（pointer 通路未被 wheel 改动）。
    await view.scrollIntoViewIfNeeded();
    await page.waitForTimeout(150);
    const box = await view.boundingBox();
    const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    for (let i = 1; i <= 4; i++) await page.mouse.move(cx + (-60 * i) / 4, cy, { steps: 2 });
    await page.mouse.up();
    await page.waitForTimeout(300);
    expect(await frontName(page), 'the press-drag still flips after wheel swipes').toBe('shot-3.png');

    // 拖划进行中（按下未释放）wheel 不翻页；松手后也不补翻。
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    for (let i = 1; i <= 4; i++) await page.mouse.move(cx + (-10 * i) / 4, cy, { steps: 2 });
    await wheelBurst(page, view, { dx: 10, steps: 7 });
    await page.mouse.up();
    await page.waitForTimeout(300);
    expect(await frontName(page), 'wheel yields while the drag owns the window').toBe('shot-3.png');

    // wheel 通路完好：静默后再横扫照常翻页。
    await page.waitForTimeout(220);
    await wheelBurst(page, view, { dx: 10, steps: 7 });
    expect(await frontName(page), 'wheel swipe intact after the drag').toBe('shot-1.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ T6 — 「你发的和我发的」同款：用户侧叠卡同 builder 同手势 ═══════════ */

test.describe('T6 — the user-side deck flips the same way', () => {
  test('trackpad swipe flips the user-attachment stack too (same builder)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 台面修正（只影响本页）：harness 的 #popup-chat 装饰容器右锚 420px，与栏
    // 右缘重叠——真实 app 里 popup 容器在屏外（getHiddenRoot）永不可交互，故按
    // 真实形态关掉它的命中，让双指滑动落在用户叠卡本体上。
    await page.evaluate(() => { document.getElementById('popup-chat').style.pointerEvents = 'none'; });
    await page.evaluate(() => window.__userMediaLive());
    await page.waitForFunction(
      () => document.querySelectorAll('#chat .row.user-media .pop-stack-view .media-layer').length === 2,
      null, { timeout: 10000 },
    );
    await page.waitForTimeout(300);
    const rowSel = '#chat > .row.user-media .pop-stack-view';
    expect(await frontName(page, rowSel), 'user stack front before swipe').toBe('u1.png');

    await wheelBurst(page, page.locator('#chat > .row.user-media .pop-stack-view'), { dx: 10, steps: 7 });
    expect(await frontName(page, rowSel), 'swipe left → 下一张（用户侧同款）').toBe('u2.png');
    await page.waitForTimeout(220);
    await wheelBurst(page, page.locator('#chat > .row.user-media .pop-stack-view'), { dx: -10, steps: 7 });
    expect(await frontName(page, rowSel), 'swipe right → 回绕上一张（两张环）').toBe('u1.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ T7 — Shift+纵滚 = 显式横意（环同款仲裁）═══════════ */

test.describe('T7 — Shift+vertical wheel counts as horizontal intent', () => {
  test('Shift+two-finger vertical scroll flips the deck', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await renderStack(page);
    await wheelBurst(page, page.locator('#chat .pop-stack-view'), { dx: 0, dy: 10, steps: 7, shift: true });
    expect(await frontName(page), 'Shift+纵滚 → 下一张（与环批同仲裁）').toBe('shot-2.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
