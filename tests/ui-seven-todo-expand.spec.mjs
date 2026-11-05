// ui-seven-todo-expand.spec.mjs — 件5「待办展开 = 同一行内胶囊变大」门禁
// （UI 批次审计 2026-11-05 · ui-seven 件5；作者原话逐字：
//   「然后待办的展开动画，要在同一行展开。有一种胶囊变大的感觉。」）。
//
// 口径（要求做法）：展开由**同一个元素的几何**驱动 —— 展开载体（`.tfl-card`）在
// 胶囊下缘原位长高，原点（left/top）全程不动；不走「先插一个满尺寸面板、再用
// transform scale 把它缩成胶囊再放回去」那条路（那条路会让载体的 rect.left 在
// 动画中横移半个宽差 ≈ 175px，等于横跳）。
//
// 判据（机械）：
//   A1 原点不跳：从点击到终态按 rAF 逐帧采样（≥6 帧），胶囊与载体的 rect.left /
//      rect.top 全程 |Δ| ≤ 1px；载体底缘只许向下（单调不减）。
//   A2 连续非瞬切：载体高度序列单调不减，且**至少一帧**严格介于 h_start 与 h_end
//      之间（= 真几何生长，不是 display 切换）。
//   A3 不挤走：顶带内其它元素（#top-overlays 的其它子层、#task-list 槽位本身、
//      #chat / #input-area）展开前后 rect 逐项相等（|Δ| ≤ 1）；列表内行只允许
//      整体下移（left / width 逐行相等，Δtop ≥ -1）。
//   A4 零要素消失：动画每一帧，胶囊本体与展开载体的 rect.width>0、height>0、
//      computed display ≠ none 且 visibility ≠ hidden、opacity > 0
//      （旧实现展开时把胶囊置 visibility:hidden —— 元素为了动画消失，本判据禁）。
//   A5 既有契约不破：`js/todoFace.js` 的 `.tfl-card[data-state="open"]` 状态位 +
//      面板承载全部信息（胶囊读数搬进面板头仍是同一节点）—— 本 spec 内抽样断言，
//      全套契约由 tests/todo-entry-collapse.spec.mjs 单独复跑。
//   A6 prefers-reduced-motion：reduce 下无中间帧（直切），A1 原点判据仍成立。
//
// 数据面 = tests/_todo-face-harness.mjs（零后端：page.route 注盘 + WS 帧桩 +
// todoStore.__seed 夹具，不碰 :6138、不跑 sbt）；指针 = Playwright 真指针。
//
// Run: node node_modules/@playwright/test/cli.js test tests/ui-seven-todo-expand.spec.mjs
//   env：EVID_DIR=<dir> 落 readings.json + 截图（默认 evidence/20261105_ui-seven/c4）。

import { test, expect } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootTodoFace } from './_todo-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const EVID = process.env.EVID_DIR || join(ROOT, 'evidence', '20261105_ui-seven', 'c4');
mkdirSync(EVID, { recursive: true });

const READINGS = {};

function save(name, payload) {
  let disk = {};
  try { disk = JSON.parse(readFileSync(join(EVID, 'readings.json'), 'utf8')); } catch { disk = {}; }
  READINGS[name] = payload;
  writeFileSync(join(EVID, 'readings.json'), JSON.stringify({ ...disk, ...READINGS }, null, 2));
}

/** 逐帧采样器（装进页面，点胶囊前先起跑）。 */
function installProbe() {
  const cap = document.querySelector('#task-list .tfl-capsule');
  const card = document.querySelector('#task-list .tfl-card');
  if (!cap || !card) return false;
  const state = { samples: [], done: false, stable: 0, lastH: -1, openFrames: 0 };
  state.reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  window.__expandProbe = state;
  const snap = (el) => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return {
      left: +r.left.toFixed(2), top: +r.top.toFixed(2),
      width: +r.width.toFixed(2), height: +r.height.toFixed(2),
      display: cs.display, visibility: cs.visibility, opacity: +(+cs.opacity).toFixed(3),
      inlineH: el.style.height || '',
    };
  };
  const tick = () => {
    const c = snap(cap);
    const d = snap(card);
    const rows = [...document.querySelectorAll('#task-list .tfl-row')].map((r) => {
      const b = r.getBoundingClientRect();
      return { left: +b.left.toFixed(2), top: +b.top.toFixed(2), width: +b.width.toFixed(2) };
    });
    state.samples.push({ t: +performance.now().toFixed(1), cap: c, card: d, rows });
    if (d.width > 0 && d.display !== 'none') {
      state.openFrames += 1;
      if (state.openFrames > 12) {
        if (Math.abs(d.height - state.lastH) < 0.05) state.stable += 1; else state.stable = 0;
      }
      state.lastH = d.height;
    }
    if ((state.stable >= 12 && state.openFrames >= 18) || state.samples.length >= 180) {
      state.done = true;
      return;
    }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
  return true;
}

/** 顶带/聊天/输入区几何快照（A3 不挤走判据的 before 拍）。 */
function bandSnapshot() {
  const rect = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { left: +r.left.toFixed(2), top: +r.top.toFixed(2), width: +r.width.toFixed(2), height: +r.height.toFixed(2) };
  };
  const overlays = [...document.querySelectorAll('#top-overlays > *')].map((el, i) => ({
    key: `${el.id || el.className || el.tagName}#${i}`, rect: rect(el),
  }));
  return {
    overlays,
    slot: rect(document.getElementById('task-list')),
    chat: rect(document.getElementById('chat')),
    input: rect(document.getElementById('input-area')),
  };
}

/** 逐项相等（|Δ| ≤ tol）。 */
function sameRect(a, b, tol = 1) {
  if (!a || !b) return a === b;
  return ['left', 'top', 'width', 'height'].every((k) => Math.abs(a[k] - b[k]) <= tol);
}

/** 采样序列 → A1/A2/A4 读数表。 */
function analyseExpand(samples) {
  const open = samples.filter((s) => s.card.width > 0 && s.card.display !== 'none');
  const first = open[0];
  const last = open[open.length - 1];
  const drift = (get) => Math.max(...open.map((s) => Math.abs(get(s) - get(first))));
  const heights = open.map((s) => s.card.height);
  let monotonic = true;
  for (let i = 1; i < heights.length; i++) if (heights[i] < heights[i - 1] - 0.05) monotonic = false;
  const h0 = heights[0];
  const hN = heights[heights.length - 1];
  const intermediates = heights.filter((h) => h > h0 + 1 && h < hN - 1).length;
  const bottoms = open.map((s) => s.card.top + s.card.height);
  const bottomMonotonic = bottoms.every((v, i) => i === 0 || v >= bottoms[i - 1] - 1);
  const vanished = open.filter((s) => !(
    s.cap.width > 0 && s.cap.height > 0 && s.cap.display !== 'none' &&
    s.cap.visibility !== 'hidden' && s.cap.opacity > 0 &&
    s.card.width > 0 && s.card.height > 0 && s.card.display !== 'none' &&
    s.card.visibility !== 'hidden'
  )).length;
  return {
    frames: open.length,
    capLeft: +drift((s) => s.cap.left).toFixed(2),
    capTop: +drift((s) => s.cap.top).toFixed(2),
    cardLeft: +drift((s) => s.card.left).toFixed(2),
    cardTop: +drift((s) => s.card.top).toFixed(2),
    h0: +h0.toFixed(2), hN: +hN.toFixed(2),
    monotonic, bottomMonotonic, intermediates, vanished,
    first: first.card, last: last.card, capFirst: first.cap,
    rowsFirst: first.rows, rowsLast: last.rows,
    series: heights.map((h) => +h.toFixed(1)),
  };
}

/** 一趟完整计量：装探针 → 点胶囊 → 等落地 → 分析。 */
async function measureExpand(page) {
  const before = await page.evaluate(bandSnapshot);
  expect(await page.evaluate(installProbe)).toBe(true);
  await page.locator('#task-list .tfl-capsule').click();
  await page.waitForFunction(() => window.__expandProbe && window.__expandProbe.done, null, { timeout: 20000 });
  const samples = await page.evaluate(() => window.__expandProbe.samples);
  const reduced = await page.evaluate(() => window.__expandProbe.reduced);
  const after = await page.evaluate(bandSnapshot);
  return { before, after, samples, reduced, r: { ...analyseExpand(samples), reduced } };
}

test.describe('件5 桌面 1440×900：展开 = 同行内胶囊变大', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });

  test('A1-A5 原点不跳 / 连续生长 / 不挤走 / 零要素消失 / 状态位', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => {
      if (m.type() !== 'error') return;
      const text = m.text();
      if (/Failed to load resource|fetching the script/i.test(text)) return;
      errors.push(`console: ${text}`);
    });

    await bootTodoFace(page);
    await page.screenshot({ path: join(EVID, 'd1440-before.png') });

    const { before, after, r } = await measureExpand(page);
    save('desktop', r);

    // ── A1 原点不跳 ──
    expect(r.frames).toBeGreaterThanOrEqual(6);          // ≥6 帧 rAF 采样
    expect(r.capLeft).toBeLessThanOrEqual(1);            // 胶囊横不移
    expect(r.capTop).toBeLessThanOrEqual(1);             // 胶囊上不跳
    expect(r.cardLeft).toBeLessThanOrEqual(1);           // 载体横不移（旧实现 ≈175px）
    expect(r.cardTop).toBeLessThanOrEqual(1);            // 载体原点不跳
    expect(r.bottomMonotonic).toBe(true);                // 只向下长高

    // ── A2 连续非瞬切 ──
    expect(r.hN).toBeGreaterThan(r.h0 + 40);             // 真的长高了
    expect(r.monotonic).toBe(true);
    expect(r.intermediates).toBeGreaterThanOrEqual(1);   // 有中间帧 = 动画

    // ── A3 不挤走 ──
    // 顶带其它层 / 槽位 / 聊天体 / 输入区：逐项相等
    for (const o of before.overlays) {
      const o2 = after.overlays.find((x) => x.key === o.key);
      expect(o2, `顶带层 ${o.key} 仍在`).toBeTruthy();
      expect(sameRect(o.rect, o2.rect), `顶带层 ${o.key} 不得被挤动`).toBe(true);
    }
    expect(after.overlays.length).toBe(before.overlays.length);
    expect(sameRect(before.slot, after.slot), '#task-list 槽位不得改几何').toBe(true);
    expect(sameRect(before.chat, after.chat), '#chat 不得让位重排').toBe(true);
    expect(sameRect(before.input, after.input), '#input-area 不得移动').toBe(true);
    // 列表内行：只许整体下移，不允许横向重排
    expect(r.rowsLast.length).toBe(r.rowsFirst.length);
    for (let i = 0; i < r.rowsFirst.length; i++) {
      const a = r.rowsFirst[i], b = r.rowsLast[i];
      expect(Math.abs(a.left - b.left), `行 ${i} left 不变`).toBeLessThanOrEqual(1);
      expect(Math.abs(a.width - b.width), `行 ${i} width 不变（无横向重排）`).toBeLessThanOrEqual(1);
      expect(b.top, `行 ${i} 只许下移`).toBeGreaterThanOrEqual(a.top - 1);
    }

    // ── A4 零要素消失 ──
    expect(r.vanished).toBe(0);

    // ── A5 既有状态位与信息承载仍成立 ──
    await expect(page.locator('#task-list .tfl-card')).toHaveAttribute('data-state', 'open');
    await expect(page.locator('#task-list .tfl-card')).toBeVisible();
    await expect(page.locator('#task-list .tfl-header-count .tfl-cap-faces')).toHaveCount(1);
    await expect(page.locator('#task-list .tfl-header-count .tfl-capsule-count')).toHaveText('4 待办');
    await expect(page.locator('#task-list .tfl-row')).toHaveCount(4);
    await page.screenshot({ path: join(EVID, 'd1440-after.png') });

    // 中间帧实拍（收起 → 再开，供人眼复核「同一行内长高」）
    await page.locator('#task-list .tfl-collapse-btn').click();
    await expect(page.locator('#task-list .tfl-card')).toHaveAttribute('data-state', 'capsule');
    await page.locator('#task-list .tfl-capsule').click();
    await page.waitForTimeout(130);
    await page.screenshot({ path: join(EVID, 'd1440-mid.png') });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: join(EVID, 'd1440-open.png') });

    expect(errors).toEqual([]);
  });
});

test.describe('A6 reduced-motion：直切、原点仍不跳', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });

  test('A6 无中间帧（直切）+ A1 原点判据成立', async ({ page }) => {
    // reduced-motion 用显式 emulateMedia 打开（describe 级 test.use 的 reducedMotion
    // 在本仓 playwright 版本下不进页面：实测 matchMedia 仍 false ⇒ 会在「动画腿」上
    // 假绿）。必须在 bootTodoFace **之前** —— todoFace.js 的 REDUCED_MOTION 是模块
    // 加载期常量。
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await bootTodoFace(page);
    expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBe(true);
    const { r } = await measureExpand(page);
    save('reduced', r);
    expect(r.reduced).toBe(true);
    expect(r.frames).toBeGreaterThanOrEqual(6);
    // 直切：展开后每一帧高度都等于终态（不存在严格介于 h0 与 hN 的帧）
    expect(r.intermediates).toBe(0);
    // 原点判据在直切下同样成立
    expect(r.capLeft).toBeLessThanOrEqual(1);
    expect(r.capTop).toBeLessThanOrEqual(1);
    expect(r.cardLeft).toBeLessThanOrEqual(1);
    expect(r.cardTop).toBeLessThanOrEqual(1);
    expect(r.vanished).toBe(0);
    await page.screenshot({ path: join(EVID, 'd1440-reduced.png') });
  });
});

test.describe('件5 手机 375×812：单列防回归', () => {
  test.use({ viewport: { width: 375, height: 812 }, colorScheme: 'light' });

  test('A1/A2/A3/A4 在单列下同样成立且不横向溢出', async ({ page }) => {
    await bootTodoFace(page);
    await expect(page.locator('body.mobile-layer')).toHaveCount(1);
    const { before, after, r } = await measureExpand(page);
    save('mobile', r);

    expect(r.frames).toBeGreaterThanOrEqual(6);
    expect(r.capLeft).toBeLessThanOrEqual(1);
    expect(r.capTop).toBeLessThanOrEqual(1);
    expect(r.cardLeft).toBeLessThanOrEqual(1);
    expect(r.cardTop).toBeLessThanOrEqual(1);
    expect(r.monotonic).toBe(true);
    expect(r.intermediates).toBeGreaterThanOrEqual(1);
    expect(r.vanished).toBe(0);
    // 单列：面板守 375−32 口径，展开后不产生横向滚动
    expect(r.last.width).toBeLessThanOrEqual(343);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(376);
    // 顶带/输入区不被挤动
    expect(sameRect(before.input, after.input), '#input-area 不得移动').toBe(true);
    expect(sameRect(before.slot, after.slot), '#task-list 槽位不得改几何').toBe(true);
    for (let i = 0; i < r.rowsFirst.length; i++) {
      expect(Math.abs(r.rowsFirst[i].left - r.rowsLast[i].left)).toBeLessThanOrEqual(1);
    }
    await page.screenshot({ path: join(EVID, 'm375-after.png') });
  });
});
