#!/usr/bin/env node
// verify.mjs — 对照页自证：三方案截图 + 真像素对比度 + 几何断言 + 渐隐坡剖面
//
// 全部对着 index.html 的真渲染量（不读设计意图、不按令牌折算）：
//   ① 每个方案 × 亮/暗 拍一张 375×812@2x 的面板图（shots/variant-<id>-<theme>.png）——
//      回执里那三条绝对路径就是它们；
//   ② 对比度 = 前景/背景**逐像素配对法**：同一裁剪区拍两帧，一帧正常、一帧把 label
//      藏起来 ⇒ 前帧 = 文字实际合成色、后帧 = 同点无字底；
//   ③ 几何：签高 / 签心与圆钮同心 / 带与钮的净空 / 逐签圆角；
//   ④ 渐隐坡剖面：逐行亮度标准差（签与钮先藏起来），看玻璃把内容糊到什么程度、
//      以及边缘是「一条线」还是「一条坡」。
//
// 用法：node design/mobile-canvas-tabs/verify.mjs

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadPlaywright, launchBrowser } from '../../scripts/lib/visual-harness.mjs';
import { decodePng } from '../../scripts/lib/png-diff.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SHOTS = join(HERE, 'shots');
const DSF = 2;
const PANELS = ['a-light', 'a-dark', 'b-light', 'b-dark', 'c-light', 'c-dark'];

const chan = (c) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
const relLum = ([r, g, b]) => 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b);
const wcag = (fg, bg) => {
  const a = relLum(fg); const b = relLum(bg);
  const hi = Math.max(a, b); const lo = Math.min(a, b);
  return +(((hi + 0.05) / (lo + 0.05)).toFixed(2));
};
const hex = ([r, g, b]) => `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`;

const { mod: pw } = loadPlaywright();
const { browser, label } = await launchBrowser(pw);
const page = await browser.newPage({
  viewport: { width: 1780, height: 1000 }, deviceScaleFactor: DSF,
  colorScheme: 'dark', locale: 'zh-CN', reducedMotion: 'reduce',
});
const errors = [];
page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));

await page.goto(pathToFileURL(join(HERE, 'index.html')).href, { waitUntil: 'load' });
await page.waitForSelector('.panel-stage[data-panel="c-dark"] .mf-strip-chip', { timeout: 20000 });
await page.evaluate(async () => { await document.fonts.ready; await new Promise((r) => requestAnimationFrame(r)); });
await page.waitForTimeout(400);
// 隐身开关（藏字量背景色用）
await page.addStyleTag({ content: '.hide-label .mf-strip-label{visibility:hidden !important}\n.hide-chrome .mf-strip-chip,.hide-chrome .mf-cv-toolbar{visibility:hidden !important}' });

const readings = { generatedAt: new Date().toISOString(), browser: label, viewport: [375, 812], dsf: DSF, panels: {}, contrast: {}, geometry: {}, fade: {}, errors };

const sel = (panel) => `.panel-stage[data-panel="${panel}"]`;

// ── ① 面板截图 ─────────────────────────────────────────────────────────────
for (const panel of PANELS) {
  const el = page.locator(sel(panel));
  const buf = await el.screenshot({ animations: 'disabled', caret: 'hide' });
  writeFileSync(join(SHOTS, `variant-${panel}.png`), buf);
  const png = decodePng(buf);
  readings.panels[panel] = { file: `shots/variant-${panel}.png`, px: [png.width, png.height] };
}
console.log('面板截图：6 张');

// ── ② 对比度（前景/背景配对）────────────────────────────────────────────────
async function contrastOf(panel, i) {
  const el = page.locator(sel(panel));
  await el.scrollIntoViewIfNeeded();
  await page.waitForTimeout(90);
  // 注意：元素的 getBoundingClientRect 已经是**视口坐标**（含面板自身偏移），
  // 直接当裁切框用；不要再加一次面板偏移（加了会双计，右列面板直接越界）。
  const meta = await el.evaluate((root, idx) => {
    const chip = root.querySelectorAll('.mf-strip-chip')[idx];
    const r = chip.querySelector('.mf-strip-label').getBoundingClientRect();
    const cr = chip.getBoundingClientRect();
    return {
      text: chip.textContent.trim(), on: chip.classList.contains('on'),
      label: { x: r.x, y: r.y, w: r.width, h: r.height },
      chip: { x: cr.x, y: cr.y, w: cr.width, h: cr.height },
      fontSize: getComputedStyle(chip).fontSize,
    };
  }, i);
  const pad = 1;
  const clip = {
    x: meta.label.x + pad, y: meta.label.y + pad,
    width: Math.max(4, meta.label.w - pad * 2), height: Math.max(4, meta.label.h - pad * 2),
  };
  const A = await page.screenshot({ clip, animations: 'disabled', caret: 'hide' });
  await el.evaluate((root) => root.classList.add('hide-label'));
  await page.waitForTimeout(60);
  const B = await page.screenshot({ clip, animations: 'disabled', caret: 'hide' });
  await el.evaluate((root) => root.classList.remove('hide-label'));

  const a = decodePng(A); const b = decodePng(B);
  const px = [];
  for (let y = 0; y < a.height; y++) {
    for (let x = 0; x < a.width; x++) {
      const i4 = (y * a.width + x) * 4;
      const fg = [a.data[i4], a.data[i4 + 1], a.data[i4 + 2]];
      const bg = [b.data[i4], b.data[i4 + 1], b.data[i4 + 2]];
      const d = Math.max(Math.abs(fg[0] - bg[0]), Math.abs(fg[1] - bg[1]), Math.abs(fg[2] - bg[2]));
      if (d > 10) px.push({ d, fg, bg, ratio: wcag(fg, bg) });
    }
  }
  if (!px.length) return { text: meta.text, on: meta.on, glyphPixels: 0, note: '零墨迹像素' };
  px.sort((p, q) => q.d - p.d);
  const core = px.slice(0, Math.max(1, Math.round(px.length * 0.15)));
  const ratios = core.map((p) => p.ratio).sort((m, n) => m - n);
  const top = px[0];
  return {
    text: meta.text, on: meta.on, fontSize: meta.fontSize,
    chip: { w: +meta.chip.w.toFixed(1), h: +meta.chip.h.toFixed(1) },
    glyphPixels: px.length,
    sample: { fgHex: hex(top.fg), bgHex: hex(top.bg), ratio: top.ratio },
    coreMedian: +ratios[Math.floor(ratios.length / 2)].toFixed(2),
    coreMin: +ratios[0].toFixed(2),
    coreMax: +ratios[ratios.length - 1].toFixed(2),
  };
}

for (const panel of PANELS) {
  const rows = [];
  for (let i = 0; i < 3; i++) rows.push(await contrastOf(panel, i));
  readings.contrast[panel] = rows;
  console.log(`对比度 ${panel}: ` + rows.map((r) => `${r.text}${r.on ? '(on)' : ''} ${r.coreMedian}:1 (min ${r.coreMin})`).join(' / '));
}

// ── ③ 几何 ─────────────────────────────────────────────────────────────────
for (const panel of PANELS) {
  const g = await page.locator(sel(panel)).evaluate((root) => {
    const R = (n) => Math.round(n * 100) / 100;
    const pr = root.getBoundingClientRect();
    const b = (el) => { const r = el.getBoundingClientRect(); return { x: R(r.x - pr.x), y: R(r.y - pr.y), w: R(r.width), h: R(r.height), cx: R(r.x - pr.x + r.width / 2), cy: R(r.y - pr.y + r.height / 2), right: R(r.right - pr.x), bottom: R(r.bottom - pr.y) }; };
    const strip = root.querySelector('.mf-strip');
    const sc = getComputedStyle(strip);
    const chips = [...root.querySelectorAll('.mf-strip-chip')];
    const fabs = [...root.querySelectorAll('.mf-cv-toolbar .g2-fab')];
    return {
      strip: b(strip),
      stripStyle: { backdrop: sc.backdropFilter, mask: sc.maskImage, bg: sc.backgroundColor, radius: sc.borderRadius, height: sc.height, z: sc.zIndex },
      chips: chips.map((c) => ({ text: c.textContent.trim(), on: c.classList.contains('on'), box: b(c), radius: getComputedStyle(c).borderRadius, hit: (() => { const p = getComputedStyle(c, '::before'); return { w: parseFloat(p.width), h: parseFloat(p.height) }; })() })),
      fabs: fabs.map((f) => b(f)),
      // 圆钮实测：A 自带；B/C 由底衬承担（值取自 capture 探针：x16..64 / x311..359，y16..64）
      fabRef: { left: [16, 64], right: [311, 359], y: [16, 64] },
    };
  });
  readings.geometry[panel] = g;
  const cy = g.chips.map((c) => c.box.cy);
  console.log(`几何 ${panel}: 带 ${g.strip.w}×${g.strip.h} @y${g.strip.y} (r ${g.stripStyle.radius}); 签高 ${g.chips.map((c) => c.box.h.toFixed(1)).join('/')}; 签心 ${cy.join('/')}; 命中 ${g.chips.map((c) => `${c.hit.w?.toFixed(0)}x${c.hit.h?.toFixed(0)}`).join('/')}; 钮 ${g.fabs.length ? g.fabs.map((f) => `${f.w}×${f.h}@y${f.y}`).join(' ') : '底衬自带(16..64/311..359)'}`);
}

// ── ④ 渐隐坡剖面 ───────────────────────────────────────────────────────────
const PROFILE_X = { a: [8, 367], b: [80, 295], c: [8, 367] };
async function rowProfile(panel, xr) {
  const el = page.locator(sel(panel));
  await el.scrollIntoViewIfNeeded();
  await page.waitForTimeout(80);
  const off = await el.boundingBox();
  await el.evaluate((root) => root.classList.add('hide-chrome'));
  await page.waitForTimeout(80);
  const clip = { x: off.x + xr[0], y: off.y, width: xr[1] - xr[0], height: 132 };
  const buf = await page.screenshot({ clip, animations: 'disabled', caret: 'hide' });
  await el.evaluate((root) => root.classList.remove('hide-chrome'));
  const { width, height, data } = decodePng(buf);
  const rows = [];
  for (let y = 0; y < height; y++) {
    let sum = 0; let sum2 = 0; let n = 0;
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const l = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
      sum += l; sum2 += l * l; n++;
    }
    const mean = sum / n;
    rows.push(+Math.sqrt(Math.max(0, sum2 / n - mean * mean)).toFixed(2));
  }
  // 每 8 个 CSS px 一窗取峰值（行内噪声：文字行与行间空白同一行的方差差很多）
  const win = (y0, y1) => {
    let peak = 0;
    for (let y = Math.round(y0 * DSF); y < Math.min(rows.length, Math.round(y1 * DSF)); y++) peak = Math.max(peak, rows[y]);
    return +peak.toFixed(2);
  };
  return { w0_24: win(0, 24), w24_48: win(24, 48), w48_64: win(48, 64), w64_80: win(64, 80), w80_96: win(80, 96), w96_132: win(96, 132) };
}
for (const panel of PANELS) {
  const id = panel[0];
  readings.fade[panel] = await rowProfile(panel, PROFILE_X[id]);
  const f = readings.fade[panel];
  console.log(`剖面峰值 ${panel}: 0-24 ${f.w0_24} / 24-48 ${f.w24_48} / 48-64 ${f.w48_64} / 64-80 ${f.w64_80} / 80-96 ${f.w80_96} / 96-132 ${f.w96_132}`);
}

writeFileSync(join(HERE, 'readings.json'), JSON.stringify(readings, null, 2));
console.log(`readings.json 已写（pageErrors=${errors.length}）`);
await browser.close();
