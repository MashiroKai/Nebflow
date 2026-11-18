#!/usr/bin/env node
// tests/canvas-button-unify-media.mjs — canvas-button-unify 收口：动效证据采集。
//
// 零端口、零进程（沿用 scripts/lib/visual-harness.mjs 的 page.route 磁盘根 + MockWS
// 真分发路线，结构上不可能碰到宿主实例）。
//
// 采集方式：**逐帧 PNG 序列**（page.screenshot 按固定节拍 12fps）→ ffmpeg 合成
// GIF / MP4。为什么不用 playwright 的 recordVideo：① 需求钉死真实像素尺寸
// 1280×800 / 375×812，而 recordVideo 的 size 必须偶数且会相对视口做缩放；
// ② 逐帧序列的每一帧本身就是可复核的原始读数（帧目录随证据一起留存）。
//
// 装置（两段）：
//   desktop 1280×800 —— canvas 图像面：底部「多按钮组合」胶囊组（.canvas-zoom-bar
//     + 两枚 48 圆钮）+ 画布多样单钮（.canvas-pop-btn 26 / .g2-fab 40 / .deck-fab 40）。
//     指针扫过每枚钮（hover）+ 逐枚按下/松开（active）。
//   mobile 375×812 —— 待办页右上角参考组（.ta-pill + .ta-pill-btn），同一套指针动作。
//
// 用法：node tests/canvas-button-unify-media.mjs <outDir>
// 退出码：0 = 帧目录写完；2 = 装置错误。
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const FPS = 12;
const TICK = 1000 / FPS;
const OUT = process.argv[2] || '/private/tmp/stand-canvas-buttons-evidence/canvas-button-unify/frames';
mkdirSync(OUT, { recursive: true });

const IMG_URI = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="400"><rect width="640" height="400" fill="#22304a"/></svg>');

/** 按固定节拍拍 n 帧（保持指针状态不变）。 */
async function hold(page, dir, state, n) {
  for (let i = 0; i < n; i += 1) {
    const t0 = Date.now();
    await page.screenshot({ path: join(dir, `frame-${String(state.i).padStart(4, '0')}.png`) });
    state.i += 1;
    const rest = TICK - (Date.now() - t0);
    if (rest > 0) await page.waitForTimeout(rest);
  }
}

async function rects(page, selector) {
  return page.evaluate((sel) => [...document.querySelectorAll(sel)]
    .filter((el) => el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none')
    .map((el) => { const r = el.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2, cls: el.className, w: r.width, h: r.height }; }), selector);
}

/** 指针动线：把每个目标跑一遍 move / press / release。 */
async function sweep(page, dir, targets, state, label) {
  const notes = [];
  for (const t of targets) {
    notes.push(`${label} move ${t.cls} (${Math.round(t.x)},${Math.round(t.y)}) box ${t.w}x${t.h}`);
    await page.mouse.move(t.x - 140, t.y - 140, { steps: 4 });
    await page.mouse.move(t.x, t.y, { steps: 6 });
    await hold(page, dir, state, 7);
    await page.mouse.down();
    await hold(page, dir, state, 5);
    await page.mouse.up();
    await hold(page, dir, state, 5);
  }
  await page.mouse.move(4, 300, { steps: 8 });
  await hold(page, dir, state, 8);
  return notes;
}

const { mod } = loadPlaywright();
const pw = mod.playwright || mod;
const { browser, label } = await launchBrowser(pw);
const notes = [`browser=${label}`, `fps=${FPS}`, `out=${OUT}`];

for (const kind of ['mobile', 'desktop']) {
  const width = kind === 'mobile' ? 375 : 1280;
  const height = kind === 'mobile' ? 812 : 800;
  const dir = join(OUT, kind);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1, colorScheme: 'dark', locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
  const s = await createSession({ newPage: () => ctx.newPage() }, { width, height, baseURL: 'https://stand.test/' });
  await s.boot();
  const state = { i: 0 };

  if (kind === 'mobile') {
    await s.page.waitForSelector('body.mobile-layer', { timeout: 15000 });
    await s.page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); nav.activateNav(); nav.pushPage('tasks'); });
    await settle(s.page, 350);
    await s.page.mouse.move(width / 2, 700, { steps: 2 });
    await hold(s.page, dir, state, 10);
    const members = await rects(s.page, '.mobile-page[data-page="tasks"] .ta-pill-btn');
    const group = await rects(s.page, '.mobile-page[data-page="tasks"] .ta-pill');
    notes.push(`mobile group=${JSON.stringify(group.map((g) => `${g.w}x${g.h}`))} members=${members.length}`);
    notes.push(...await sweep(s.page, dir, members, state, 'mobile'));
  } else {
    await s.page.evaluate((d) => { window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d })); }, {
      id: 'file:/tmp/cbu-media.svg', itemType: 'image', title: 'cbu-media.svg', pinned: true,
      content: '', absPath: '/tmp/cbu-media.svg', size: 0, mtimeMs: 1757000000000, objectUrl: IMG_URI,
    });
    await s.page.waitForSelector('.canvas-tab-pane .canvas-zoom-bar', { state: 'visible', timeout: 15000 });
    await settle(s.page, 350);
    await s.page.mouse.move(200, 400, { steps: 2 });
    await hold(s.page, dir, state, 10);
    const group = await rects(s.page, '.canvas-zoom-bar');
    const members = await rects(s.page, '.canvas-zoom-bar .canvas-zoom-btn');
    const singles = await rects(s.page, '.canvas-pop-btn, .g2-fab, .deck-fab, .canvas-refback-btn');
    notes.push(`desktop group=${JSON.stringify(group.map((g) => `${g.w}x${g.h}`))} members=${members.length} singles=${singles.length}`);
    notes.push(...await sweep(s.page, dir, members, state, 'desktop/group'));
    notes.push(...await sweep(s.page, dir, singles, state, 'desktop/single'));
  }
  writeFileSync(join(dir, 'frames.txt'), `frames=${state.i}\n` + notes.join('\n') + '\n');
  console.log(`${kind}: ${state.i} frames -> ${dir}`);
  await ctx.close();
}

await browser.close();
writeFileSync(join(OUT, 'capture-notes.txt'), notes.join('\n') + '\n');
console.log('notes:\n' + notes.join('\n'));
