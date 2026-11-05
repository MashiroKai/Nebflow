#!/usr/bin/env node
// render.mjs — 方案页的截图采集器（真浏览器真渲染，非手绘）
//
// 产出：
//   <out>/compare-light.png   浅色总对照（5 方案 × 3 态）
//   <out>/compare-dark.png    深色总对照
//   <out>/option-A.png … option-E.png   单组放大（浅/深两栏，各 5 态）
//   <out>/manifest.json       视口尺寸、浏览器版本、图片尺寸、每张图的来源 URL
//
// 装置：scripts/lib/visual-harness.mjs 的 loadPlaywright（仓内既有的
// playwright 解析口径：NEBFLOW_PW_PATH → 常规安装 → playwright-core，不装新依赖）。
// 浏览器用**本机已安装的 Chrome**（channel: 'chrome'），并强制两件事：
//   --force-color-profile=srgb  截图色值 = CSS 色值（否则 Retina 下可能落进 P3，
//                               报告里列的色值与图上像素就对不上了）
//   --disable-lcd-text          关次像素抗锯齿（避免 1px 线出现彩色边）
//
// 用法：
//   NEBFLOW_PW_PATH=<playwright 目录> node design/toggle-options/render.mjs --out /tmp/shots

import { mkdirSync, writeFileSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadPlaywright } from '../../scripts/lib/visual-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const valOf = (f, d) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };

const OUT = resolve(valOf('--out', join(HERE, 'shots')));
// 视口 = 判据里要点名的「浏览器窗口尺寸」。1600 宽是为了让两栏并排时每栏仍有
// 730px，行内三态不挤；900 高足够一屏装下整页（不需要滚动拼接）。
const W = Number(valOf('--width', 1600));
const H = Number(valOf('--height', 900));
// DSF=2：Retina 双倍采样。这是**人眼评审用图**，不是跨机像素基线（基线恒 DSF=1，
// 见 visual-harness.mjs:130 的口径），所以这里取 2 让 1px 描边与 11px 字看得清。
const DSF = Number(valOf('--dsf', 2));

mkdirSync(OUT, { recursive: true });

const pw = loadPlaywright();          // → { mod, from }（visual-harness.mjs:69 的返回形态）
const chromium = (pw.mod.playwright || pw.mod).chromium;
const browser = await chromium.launch({
  channel: 'chrome',
  headless: true,
  args: ['--force-color-profile=srgb', '--disable-lcd-text'],
});

const ctx = await browser.newContext({
  viewport: { width: W, height: H },
  deviceScaleFactor: DSF,
  locale: 'zh-CN',
  colorScheme: 'light',
});
const page = await ctx.newPage();

const shots = [];
async function shoot(selector, name, sourceUrl) {
  const el = page.locator(selector);
  await el.waitFor({ state: 'visible' });
  const box = await el.boundingBox();
  const file = join(OUT, name);
  await el.screenshot({ path: file, animations: 'allow' });
  const px = { w: Math.round(box.width * DSF), h: Math.round(box.height * DSF) };
  shots.push({ name, file, selector, sourceUrl, cssBox: box, image: px, bytes: statSync(file).size });
  console.log(`✓ ${name}  ${px.w}×${px.h}px  (${Math.round(box.width)}×${Math.round(box.height)} css)`);
}

async function goto(file, query = '') {
  const url = pathToFileURL(join(HERE, file)).href + query;
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__togReady === true, null, { timeout: 15000 });
  await page.evaluate(() => document.fonts.ready);
  return url;
}

// ── 1. 总对照（浅 / 深各一张，同一次加载出图）──────────────────────────────
const indexUrl = await goto('index.html');
await shoot('section.pane[data-theme="light"]', 'compare-light.png', indexUrl);
await shoot('section.pane[data-theme="dark"]', 'compare-dark.png', indexUrl);

// ── 2. 单组放大（每方案一张，浅/深两栏同框）────────────────────────────────
for (const id of ['A', 'B', 'C', 'D', 'E']) {
  const url = await goto('zoom.html', `?option=${id}`);
  await shoot('main', `option-${id}.png`, url);
}

const meta = {
  generatedAt: new Date().toISOString(),
  browser: `${browser.version()}（本机 Chrome，channel=chrome）`,
  viewport: { width: W, height: H, deviceScaleFactor: DSF },
  launchArgs: ['--force-color-profile=srgb', '--disable-lcd-text'],
  shots,
};
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify(meta, null, 2) + '\n');
console.log(`\n浏览器 ${meta.browser} · 视口 ${W}×${H} @${DSF}x · 共 ${shots.length} 张 → ${OUT}`);

await ctx.close();
await browser.close();
