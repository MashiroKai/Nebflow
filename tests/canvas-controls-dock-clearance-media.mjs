#!/usr/bin/env node
// tests/canvas-controls-dock-clearance-media.mjs — canvas 底部控件行修复的**动效证据**：
// 手机 375×812 与桌面 1280×800（亮 + 暗）各一段，指针真实扫过并**逐枚按下**左下两钮、
// 中间三点条与右侧 ask 钮 —— 动图里能直接看见「悬空、毛玻璃真透过、按得动」。
//
// 零端口零进程（scripts/lib/visual-harness.mjs：page.route 从磁盘服务 web/ + MockWS
// 走真实 ws.js 分发）⇒ 结构上不可能碰到宿主实例。静态根可用 NEBFLOW_WEB_ROOT 切换
// ⇒ **同一脚本、同一机位**跑「改前」（旧 web 树）与「改后」（当前树）。
//
// 帧路线（同 tests/canvas-button-unify-media.mjs）：逐帧 PNG 序列（12fps，DPR=1
// ⇒ 帧像素 = 视口像素，逐张可复核）→ ffmpeg 合成 GIF / MP4。
//
// 用法：node tests/canvas-controls-dock-clearance-media.mjs <outDir> [side]
//   side 只进目录名（before/after），不改变采集行为。
// 退出码：0 = 帧 + GIF + MP4 全落盘；2 = 装置错误（缺 ffmpeg / 取不到控件）。
import { mkdirSync, rmSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { loadPlaywright, launchBrowser, createSession, settle, webRoot } from '../scripts/lib/visual-harness.mjs';

const FPS = 12;
const TICK = 1000 / FPS;
const OUT = process.argv[2];
const SIDE = process.argv[3] || 'after';
if (!OUT) { console.error('usage: node tests/canvas-controls-dock-clearance-media.mjs <outDir> [side]'); process.exit(2); }
const FFMPEG = ['/opt/homebrew/bin/ffmpeg', 'ffmpeg'].find((p) => p === 'ffmpeg' || existsSync(p));
if (!FFMPEG) { console.error('装置错误：找不到 ffmpeg'); process.exit(2); }

const IMG_URI = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="400"><rect width="640" height="400" fill="#22304a"/></svg>');

const state = { i: 0 };
/** 按固定节拍拍 n 帧（指针状态保持不变）。 */
async function hold(page, dir, n) {
  for (let k = 0; k < n; k += 1) {
    const t0 = Date.now();
    await page.screenshot({ path: join(dir, `frame-${String(state.i).padStart(4, '0')}.png`) });
    state.i += 1;
    const rest = TICK - (Date.now() - t0);
    if (rest > 0) await page.waitForTimeout(rest);
  }
}
/** 取一枚控件的中心（不可见则返回 null）。 */
async function center(page, selector) {
  return page.evaluate((sel) => {
    const el = [...document.querySelectorAll(sel)].find((e) => e.getClientRects().length
      && getComputedStyle(e).display !== 'none' && Number(getComputedStyle(e).opacity) > 0);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, cls: String(el.className), w: r.width, h: r.height };
  }, selector);
}
/** 逐枚：移入（hover）→ 按下 → 松开，每步都留帧。 */
async function tapSweep(page, dir, targets, notes) {
  for (const sel of targets) {
    const c = await center(page, sel);
    if (!c) { notes.push(`MISS ${sel}`); continue; }
    notes.push(`tap ${sel} @(${Math.round(c.x)},${Math.round(c.y)}) box ${c.w}x${c.h}`);
    await page.mouse.move(c.x, c.y - 60, { steps: 3 });
    await page.mouse.move(c.x, c.y, { steps: 5 });
    await hold(page, dir, 5);
    await page.mouse.down();
    await hold(page, dir, 4);
    await page.mouse.up();
    await hold(page, dir, 5);
  }
}
function encode(dir, stem) {
  const gif = join(dir, `${stem}.gif`);
  const mp4 = join(dir, `${stem}.mp4`);
  const palette = join(dir, 'palette.png');
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', join(dir, 'frame-%04d.png'), '-vf', 'palettegen', palette]);
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', join(dir, 'frame-%04d.png'), '-i', palette, '-lavfi', 'paletteuse', '-loop', '0', gif]);
  // MP4 用 yuv420p（兼容面最广）：奇数宽补 1px 偶数边（ffprobe 报的是补后真像素，不四舍五入）。
  execFileSync(FFMPEG, ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', join(dir, 'frame-%04d.png'),
    '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-r', String(FPS), mp4]);
  rmSync(palette, { force: true });
  return { gif, mp4 };
}

const { mod } = loadPlaywright();
const { browser, label } = await launchBrowser(mod);
const out = [];
try {
  for (const [width, height] of [[375, 812], [1280, 800]]) {
    for (const theme of ['dark', 'light']) {
      const scene = (width === 375 ? 'mobile' : 'desktop') + `-${width}x${height}-${theme}-${SIDE}`;
      const dir = join(OUT, scene, 'frames');
      mkdirSync(dir, { recursive: true });
      state.i = 0;
      const ctx = await browser.newContext({ viewport: { width, height }, hasTouch: width === 375 });
      const session = await createSession({ newPage: (o) => ctx.newPage(o) }, { width, height, colorScheme: theme, baseURL: 'https://stand.test/' });
      const { page } = session;
      await page.setViewportSize({ width, height });
      await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
      await session.boot();
      const notes = [];
      if (width === 375) {
        await page.waitForSelector('body.mobile-layer');
        await page.evaluate(async () => {
          const html = '<!doctype html><html><body><h1>Canvas first line</h1>' + '<p>Readable canvas content below floating controls.</p>'.repeat(40) + '</body></html>';
          const md = '# Canvas first line\n\n' + 'Readable canvas content below floating controls.\n\n'.repeat(40);
          const items = [
            { id: 'file:/tmp/cvcc-1.html', itemType: 'html', title: 'cvcc-1', content: html, absPath: '/tmp/cvcc-1.html' },
            { id: 'file:/tmp/cvcc-2.md', itemType: 'markdown', title: 'cvcc-2', content: md, absPath: '/tmp/cvcc-2.md' },
            { id: 'file:/tmp/cvcc-3.md', itemType: 'markdown', title: 'cvcc-3', content: md, absPath: '/tmp/cvcc-3.md' },
          ];
          for (const d of items) window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { ...d, pinned: true, size: d.content.length } }));
          const nav = await import('/js/mobile/navController.js');
          nav.dockTo('canvas');
          (await import('/js/canvas.js')).setActiveTab(nav.docTabs()[0].id);
        });
        await page.waitForSelector('.mf-cv-host > .canvas-tab-pane');
        await settle(page, 250);
        await hold(page, dir, 6);
        await tapSweep(page, dir, ['.mf-cv-host .canvas-find-toggle', '.mf-cv-host .canvas-ref-select', '.mf-dots', '.ask-fab:not(.note-fab)'], notes);
      } else {
        await page.evaluate((d) => { window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: d })); }, {
          id: 'file:/tmp/cvcc-desk.svg', itemType: 'image', title: 'cvcc-desk.svg', pinned: true,
          content: '', absPath: '/tmp/cvcc-desk.svg', size: 0, mtimeMs: 1757000000000, objectUrl: IMG_URI,
        });
        await page.waitForSelector('.canvas-tab-pane .canvas-zoom-bar', { state: 'visible', timeout: 15000 });
        await settle(page, 250);
        await hold(page, dir, 6);
        // 桌面证据 = 缩放组的两枚成员 + 左侧圆钮族顺序扫（悬空感 + 毛玻璃透出）。
        await tapSweep(page, dir, ['.canvas-zoom-bar .canvas-zoom-btn', '.canvas-zoom-bar .canvas-zoom-btn:nth-child(2)',
          '.canvas-zoom-bar .canvas-zoom-btn:nth-child(3)', '.canvas-find-toggle'], notes);
      }
      await ctx.close();
      const { gif, mp4 } = encode(dir, 'clip');
      out.push({ scene, frames: state.i, frames_dir: dir, gif, mp4, notes, webRoot: webRoot(), browser: label });
      console.log(`${scene}: ${state.i} frames -> ${gif} / ${mp4}`);
    }
  }
} finally {
  await browser.close();
}
console.log(JSON.stringify({ webRoot: webRoot(), browser: label, scenes: out }, null, 2));
