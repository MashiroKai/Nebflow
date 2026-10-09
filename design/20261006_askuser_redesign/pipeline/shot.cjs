#!/usr/bin/env node
// shot.cjs — HTML → PNG 双主题实渲管线（AskUser 重设计批 2026-10-06 工艺件）。
//
// 用法（下游三风格节点逐字照抄）：
//   node "design/20261006_askuser_redesign/pipeline/shot.cjs" <input.html> <outdir>
// 产物：<outdir>/light.png + <outdir>/dark.png（1280×800 @2x，各一张）。
//
// 工艺要点（与仓内既有工艺 scripts/shot-header-name.cjs 同族）：
//   - Playwright chromium，纯本地文件 file:// 直开——零端口、零进程、不碰 :8080；
//   - 主题切换 = Playwright newPage({ colorScheme })，驱动 CSS `prefers-color-scheme`
//     （本仓主题机制即 prefers-color-scheme，无类切换——mockup 必须用同一机制）；
//   - 视口固定 1280×800、deviceScaleFactor: 2（@2x 视网膜）；
//   - reducedMotion: 'reduce'——关动画，出图确定性（无中间帧）。
//
// 断言（自检，防空白图）：截图前后读取 <body> 文本长度与首元素可见尺寸，
// 任一为 0 → exit 1。输出文件非空再 exit 0。

const { chromium } = require('playwright');
const { readFileSync, statSync, mkdirSync } = require('node:fs');
const { join, resolve } = require('node:path');

const [, , inputArg, outArg] = process.argv;
if (!inputArg || !outArg) {
  console.error('usage: node shot.cjs <input.html> <outdir>');
  process.exit(2);
}
const input = resolve(inputArg);
const outDir = resolve(outArg);
mkdirSync(outDir, { recursive: true });

(async () => {
  const browser = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const page = await browser.newPage({
      viewport: { width: 1280, height: 800 },
      deviceScaleFactor: 2,
      colorScheme: scheme,
      reducedMotion: 'reduce',
    });
    await page.goto('file://' + input, { waitUntil: 'networkidle' });
    await page.waitForTimeout(400); // 字体/布局稳定
    const probe = await page.evaluate(() => {
      const body = document.body;
      const first = body.querySelector('*');
      const r = first ? first.getBoundingClientRect() : null;
      return {
        textLen: (body.innerText || '').trim().length,
        firstBox: r ? Math.round(r.width) + 'x' + Math.round(r.height) : 'none',
        scheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
      };
    });
    if (probe.textLen === 0 || probe.firstBox === 'none' || probe.firstBox === '0x0') {
      console.error(`shot.cjs [${scheme}]: blank render probe FAILED`, probe);
      process.exit(1);
    }
    if (probe.scheme !== scheme) {
      console.error(`shot.cjs [${scheme}]: colorScheme emulation did not land (got ${probe.scheme})`);
      process.exit(1);
    }
    const out = join(outDir, `${scheme}.png`);
    await page.screenshot({ path: out, fullPage: false });
    const bytes = statSync(out).size;
    if (bytes < 2000) {
      console.error(`shot.cjs [${scheme}]: output suspiciously small (${bytes}B)`);
      process.exit(1);
    }
    console.log(`shot.cjs [${scheme}] ok -> ${out} (${bytes}B, probe ${JSON.stringify(probe)})`);
    await page.close();
  }
  await browser.close();
  console.log('shot.cjs done');
})().catch((e) => { console.error(e); process.exit(1); });
