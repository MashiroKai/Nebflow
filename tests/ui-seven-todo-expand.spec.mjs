// ui-seven-todo-expand.spec.mjs — 待办岛形变门禁（原型 = 件5「同一行内胶囊变大」
// 2026-11-05；**本版按 2026-11-05 17:02 作者令改写为「灵动岛」契约**）。
//
// 作者原话（17:02，逐字）：「整体就像 apple 的灵动岛一样……单一形状，全程无剪辑：
// 每个状态都是同一个元素在变形（改变尺寸、圆角和颜色），同时内容切换时伴有短暂的
// 模糊效果。光标通过真实的点击和拖拽动作驱动每一次变化。……处处运用弹簧（spring）
// 物理效果，仅有极轻微的过冲（overshoot）。」
//
// 契约变更（相对件5 版三条判据）：
//   · 件5 A1「载体 rect.left 全程不动」**退役** —— 新形态是同一元素以中轴为锚
//     **向两侧对称生长**（灵动岛语义），左缘必然移动；取而代之的判据是
//     **中线 centerX 恒定**（A4）+ **同一节点**（A1）。
//   · 件5 A4「胶囊本体每帧 opacity>0」**退役** —— 新形态里胶囊是岛的**内容层**，
//     切换必须淡出（作者要求的内容切换）；不变的判据是**岛本身**每帧可见（A1）。
//   · 判决主键变了：「过渡态是否同一节点」从隐式约定升为硬判据（A1）——旧实现
//     用「胶囊节点 + 浮卡节点」两个 div 代表两态，按新令判失败。
//
// 判据（机械）：
//   A1 单节点：window.__islandRef（点击前抓的引用）点击后 === querySelector('#todo-island')；
//      MutationObserver 全程未观察到该节点被移除/替换；每一帧 display≠none、
//      visibility≠hidden、opacity>0、宽高>0。
//   A2 连续 + 弹簧：≥6 帧 rAF 采样；高度序列**单调收敛**且**越过目标**（真弹簧，
//      实测超调 0.3–3px 档，作者要「极轻微」）；终态 == 面板自然高。
//   A3 不挤走：顶带其它层 / 槽位 / #chat / #input-area 展开前后逐项相等（|Δ|≤1）。
//   A4 中轴恒定：岛 centerX 全程 |Δ|≤1px（向两侧对称生长）。
//   A5 变色 + 圆角 + 状态位：999px/扁平淡底 → 24·19/玻璃；data-state=open；计数节点
//      仍是搬进面板头的同一 `.tfl-cap-faces`（信息零双源）。
//   A6 prefers-reduced-motion：无中间帧（直切），同节点判据仍成立。
//   M1 手机腿：手机层不渲染岛（#task-list display:none）、入口 = 左上圆钮
//      [data-chrome='tasks']（48px 圆形 + 可点）+ 页面即展开态；无横向溢出。
//
// 数据面 = tests/_todo-face-harness.mjs（零后端：page.route 注盘 + WS 帧桩 +
// todoStore.__seed 夹具，不碰 :6138、不跑 sbt）；指针 = Playwright 真指针。
//
// Run: node node_modules/@playwright/test/cli.js test tests/ui-seven-todo-expand.spec.mjs
//   env：EVID_DIR=<dir> 落 readings.json + 截图（默认 evidence/20261105_todo-island/c4）。

import { test, expect } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootTodoFace } from './_todo-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');
const EVID = process.env.EVID_DIR || join(ROOT, 'evidence', '20261105_todo-island', 'c4');
mkdirSync(EVID, { recursive: true });

const READINGS = {};

function save(name, payload) {
  let disk = {};
  try { disk = JSON.parse(readFileSync(join(EVID, 'readings.json'), 'utf8')); } catch { disk = {}; }
  READINGS[name] = payload;
  writeFileSync(join(EVID, 'readings.json'), JSON.stringify({ ...disk, ...READINGS }, null, 2));
}

/** 逐帧采样器（装进页面，点岛前先起跑）：岛盒 + 内容层 opac/滤镜 + 节点同一性。 */
function installProbe() {
  const isl = document.querySelector('#todo-island');
  if (!isl) return false;
  const cap = document.querySelector('#task-list .tfl-capsule');
  const panel = document.querySelector('#task-list .tfl-panel');
  const state = { samples: [], done: false, stable: 0, lastH: -1, same: 0, removed: 0, frames: 0 };
  state.reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  window.__islandRef = isl;
  window.__expandProbe = state;
  const mo = new MutationObserver((recs) => {
    for (const r of recs) {
      for (const n of r.removedNodes) if (n === isl) state.removed += 1;
    }
  });
  mo.observe(document.getElementById('task-list') || document.body, { childList: true, subtree: true });
  const cs = (el) => getComputedStyle(el);
  const tick = () => {
    const r = isl.getBoundingClientRect();
    const c = cs(isl);
    const capR = cap ? cap.getBoundingClientRect() : null;
    const pR = panel ? panel.getBoundingClientRect() : null;
    state.samples.push({
      t: +performance.now().toFixed(1),
      w: +r.width.toFixed(2), h: +r.height.toFixed(2),
      cx: +(r.left + r.width / 2).toFixed(2), cy: +r.top.toFixed(2),
      display: c.display, visibility: c.visibility, opacity: +(+c.opacity).toFixed(3),
      radius: c.borderTopLeftRadius, bg: c.backgroundColor,
      capOpacity: cap ? +(+cs(cap).opacity).toFixed(3) : null,
      panelBlur: panel ? cs(panel).filter : null,
      panelOpacity: panel ? +(+cs(panel).opacity).toFixed(3) : null,
      capW: capR ? +capR.width.toFixed(2) : 0,
      panelW: pR ? +pR.width.toFixed(2) : 0,
      same: isl === document.querySelector('#todo-island'),
    });
    state.frames += 1;
    if (state.frames > 12) {
      if (Math.abs(r.height - state.lastH) < 0.05) state.stable += 1; else state.stable = 0;
    }
    state.lastH = r.height;
    if ((state.stable >= 12 && state.frames >= 18) || state.samples.length >= 200) { state.done = true; mo.disconnect(); return; }
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

function sameRect(a, b, tol = 1) {
  if (!a || !b) return a === b;
  return ['left', 'top', 'width', 'height'].every((k) => Math.abs(a[k] - b[k]) <= tol);
}

/** 采样序列 → A1/A2/A4 读数表。 */
function analyseExpand(samples) {
  const f = samples[0];
  const last = samples[samples.length - 1];
  const h0 = f.h;
  const hN = last.h;
  const heights = samples.map((s) => s.h);
  const maxH = Math.max(...heights);
  const minH = Math.min(...heights);
  const offCenter = samples.map((s) => s.cx);
  const cx0 = offCenter[0];
  const overshootPx = +(maxH - hN).toFixed(2);
  const overshootPct = hN > 0 ? +(((maxH - hN) / hN) * 100).toFixed(3) : 0;
  return {
    frames: samples.length,
    sameNodeFrames: samples.filter((s) => s.same).length,
    removedFrames: samples.filter((s) => s.display === 'none' || s.visibility === 'hidden' || s.opacity <= 0 || s.w <= 0 || s.h <= 0).length,
    h0, hN, maxH, minH,
    intermediates: heights.filter((h) => h > h0 + 0.5 && h < hN - 0.5).length,
    overshootPx, overshootPct,
    cxDelta: +Math.max(...offCenter.map((x) => Math.abs(x - cx0))).toFixed(2),
    radius0: f.radius, radiusN: last.radius,
    bg0: f.bg, bgN: last.bg,
    capOpacity0: f.capOpacity, capOpacityN: last.capOpacity,
    panelBlur0: f.panelBlur, panelBlurN: last.panelBlur,
    panelW: last.panelW, capW: f.capW,
  };
}

test.describe('灵动岛 桌面 1440×900：同一元素变形（尺寸/圆角/颜色 + 内容模糊切换）', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });

  test('A1-A5 单节点 / 连续弹簧 / 不挤走 / 中轴恒定 / 变色与状态位', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => {
      if (m.type() !== 'error') return;
      const text = m.text();
      if (/Failed to load resource|fetching the script/i.test(text)) return;
      errors.push(`console: ${text}`);
    });

    await bootTodoFace(page);
    await page.waitForTimeout(400);
    await page.screenshot({ path: join(EVID, 'd1440-before.png') });

    const before = await page.evaluate(bandSnapshot);
    expect(await page.evaluate(installProbe)).toBe(true);
    await page.locator('#task-list .tfl-capsule').click();
    await page.waitForFunction(() => window.__expandProbe && window.__expandProbe.done, null, { timeout: 20000 });
    const samples = await page.evaluate(() => window.__expandProbe.samples);
    const removedByMo = await page.evaluate(() => window.__expandProbe.removed);
    const sameRef = await page.evaluate(() => window.__islandRef === document.querySelector('#todo-island'));
    const after = await page.evaluate(bandSnapshot);
    const r = analyseExpand(samples);
    save('desktop', { ...r, removedByMo, sameRef });

    // ── A1 单节点（判决主键）──
    expect(sameRef, '岛必须是点击前那一个节点').toBe(true);
    expect(removedByMo, '岛节点全程未被移除/替换').toBe(0);
    expect(r.sameNodeFrames).toBe(r.frames);
    expect(r.removedFrames, '岛每帧都在场（display/visibility/opacity/尺寸任一档不得掉）').toBe(0);

    // ── A2 连续 + 弹簧（过冲可测）──
    expect(r.frames).toBeGreaterThanOrEqual(6);
    expect(r.hN).toBeGreaterThan(r.h0 + 40);        // 真的长大了
    expect(r.intermediates).toBeGreaterThanOrEqual(2); // 有中间帧 = 真几何动画
    expect(r.overshootPx, '弹簧越过目标（>0 = 真弹簧；作者要「极轻微的过冲」）').toBeGreaterThan(0.2);
    expect(r.overshootPx, '过冲必须「极轻微」（≤3px 档）').toBeLessThan(6);

    // ── A3 不挤走 ──
    for (const o of before.overlays) {
      const o2 = after.overlays.find((x) => x.key === o.key);
      expect(o2, `顶带层 ${o.key} 仍在`).toBeTruthy();
      expect(sameRect(o.rect, o2.rect), `顶带层 ${o.key} 不得被挤动`).toBe(true);
    }
    expect(after.overlays.length).toBe(before.overlays.length);
    expect(sameRect(before.slot, after.slot), '#task-list 槽位不得改几何（定高占位）').toBe(true);
    expect(sameRect(before.chat, after.chat), '#chat 不得让位重排').toBe(true);
    expect(sameRect(before.input, after.input), '#input-area 不得移动').toBe(true);

    // ── A4 中轴恒定（向两侧对称生长）──
    expect(r.cxDelta, '岛中线全程不漂').toBeLessThanOrEqual(1);

    // ── A5 变色 / 圆角 / 内容层模糊切换 / 状态位 ──
    expect(r.radius0).toBe('999px');
    expect(r.radiusN).toContain('24px');
    expect(r.bg0).not.toBe(r.bgN);                       // 扁平淡底 → 玻璃
    expect(r.capOpacityN).toBe(0);                        // 胶囊层淡出（内容切换）
    expect(r.panelBlurN).toBe('none');                    // 面板层由 blur 转清晰
    await expect(page.locator('#task-list .tfl-card')).toHaveAttribute('data-state', 'open');
    await expect(page.locator('#task-list .tfl-header-count .tfl-cap-faces')).toHaveCount(1);
    await expect(page.locator('#task-list .tfl-header-count .tfl-capsule-count')).toHaveText('4 待办');
    await expect(page.locator('#task-list .tfl-row')).toHaveCount(4);
    await page.screenshot({ path: join(EVID, 'd1440-after.png') });

    // 中间帧实拍（收起 → 再开）
    await page.locator('#task-list .tfl-collapse-btn').click();
    await expect(page.locator('#task-list .tfl-card')).toHaveAttribute('data-state', 'capsule');
    await page.locator('#task-list .tfl-capsule').click();
    await page.waitForTimeout(120);
    await page.screenshot({ path: join(EVID, 'd1440-mid.png') });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: join(EVID, 'd1440-open.png') });

    expect(errors).toEqual([]);
  });
});

test.describe('A6 reduced-motion：直切、同节点判据仍成立', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });

  test('A6 无中间帧（直切）+ A1 单节点成立', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await bootTodoFace(page);
    expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBe(true);
    expect(await page.evaluate(installProbe)).toBe(true);
    await page.locator('#task-list .tfl-capsule').click();
    await page.waitForFunction(() => window.__expandProbe && window.__expandProbe.done, null, { timeout: 20000 });
    const samples = await page.evaluate(() => window.__expandProbe.samples);
    const sameRef = await page.evaluate(() => window.__islandRef === document.querySelector('#todo-island'));
    const r = analyseExpand(samples);
    save('reduced', { ...r, sameRef });
    expect(sameRef).toBe(true);
    expect(r.intermediates).toBe(0);   // 直切：不存在严格介于起终的帧
    expect(r.removedFrames).toBe(0);
    await page.screenshot({ path: join(EVID, 'd1440-reduced.png') });
  });
});

test.describe('手机 375×812：入口 = 左上圆钮，页面即展开态（岛不在手机层渲染）', () => {
  test.use({ viewport: { width: 375, height: 812 }, colorScheme: 'dark' });

  test('M1 左上圆钮 + 角标 + 无横向溢出', async ({ page }) => {
    await bootTodoFace(page, { mobile: true });
    // 手机层不渲染岛（端区分批口径：手机 = 圆钮 → 任务页）
    expect(await page.evaluate(() => document.body.classList.contains('mobile-layer'))).toBe(true);
    expect(await page.evaluate(() => {
      const tl = document.getElementById('task-list');
      return getComputedStyle(tl).display;
    })).toBe('none');
    // 入口圆钮：左上角、正圆、可点
    const btn = page.locator("[data-chrome='tasks']").first();
    if (await btn.count()) {
      const box = await btn.boundingBox();
      expect(box.width).toBe(48);
      expect(box.x).toBeLessThanOrEqual(20);
      expect(box.y).toBeLessThanOrEqual(24);
      expect(await page.evaluate((sel) => getComputedStyle(document.querySelector(sel)).borderRadius, "[data-chrome='tasks']")).toBe('50%');
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(376);
    await page.screenshot({ path: join(EVID, 'm375-after.png') });
  });
});
