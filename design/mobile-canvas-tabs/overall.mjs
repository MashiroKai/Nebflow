#!/usr/bin/env node
// overall.mjs — 把 overview.html（现状 + 三方案 × 亮/暗）整页拍成一张总览图
// 用法：node design/mobile-canvas-tabs/overall.mjs
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadPlaywright, launchBrowser } from '../../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const { mod: pw } = loadPlaywright();
const { browser, label } = await launchBrowser(pw);
const page = await browser.newPage({
  viewport: { width: 1700, height: 1900 }, deviceScaleFactor: 2,
  colorScheme: 'dark', locale: 'zh-CN', reducedMotion: 'reduce',
});
await page.goto(pathToFileURL(join(HERE, 'overview.html')).href, { waitUntil: 'load' });
await page.waitForSelector('.panel-stage[data-panel="c-dark"] .mf-strip-chip', { timeout: 20000 });
await page.evaluate(async () => { await document.fonts.ready; await new Promise((r) => requestAnimationFrame(r)); });
await page.waitForTimeout(400);
const buf = await page.screenshot({ fullPage: true, animations: 'disabled', caret: 'hide' });
writeFileSync(join(HERE, 'shots', 'overall.png'), buf);
const box = await page.evaluate(() => ({ w: document.documentElement.scrollWidth, h: document.documentElement.scrollHeight }));
console.log(`overall.png 已生成 · ${label} · 页面 ${box.w}×${box.h} @2x`);

// ── 推荐图：recommend.html（B 亮 + B 暗 并排）────────────────────────────
const rp = await browser.newPage({
  viewport: { width: 900, height: 1000 }, deviceScaleFactor: 2,
  colorScheme: 'dark', locale: 'zh-CN', reducedMotion: 'reduce',
});
await rp.goto(pathToFileURL(join(HERE, 'recommend.html')).href, { waitUntil: 'load' });
await rp.waitForSelector('.panel-stage[data-panel="b-dark"] .mf-strip-chip', { timeout: 20000 });
await rp.evaluate(async () => { await document.fonts.ready; await new Promise((r) => requestAnimationFrame(r)); });
await rp.waitForTimeout(400);
// 按内容盒裁剪：标题 + 两格手机（避免 fullPage 与溢出互相打架）
const rb = await rp.evaluate(() => {
  const r = document.querySelector('.orow').getBoundingClientRect();
  const h = document.querySelector('h1').getBoundingClientRect();
  return { x: Math.max(0, h.x - 8), y: Math.max(0, h.y - 8), w: r.right - h.x + 16, h: r.height + (r.y - h.y) + 24 };
});
writeFileSync(join(HERE, 'shots', 'recommend.png'), await rp.screenshot({
  clip: { x: rb.x, y: rb.y, width: rb.w, height: rb.h }, animations: 'disabled', caret: 'hide',
}));
console.log(`recommend.png 已生成 · ${Math.round(rb.w)}×${Math.round(rb.h)} @2x`);
await browser.close();
