// island-static-checks.mjs — 禁止项自查的 DOM 侧读数（图标描边统一 / 形状单源 /
// 无发光面 / 无渐变面）。输出 JSON 供报告逐条引用。
import { chromium } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootTodoFace, itemFixture, TAG_FIXTURE } from './_todo-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const OUT = process.env.EVID_DIR || join(ROOT, 'evidence', '20261105_todo-island', 'shots');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, colorScheme: 'dark' });
const page = await ctx.newPage();
await bootTodoFace(page, { tags: TAG_FIXTURE, items: itemFixture() });
await sleep(400);
await page.locator('#task-list .tfl-capsule').click();
await sleep(900);

const out = await page.evaluate(() => {
  const isl = document.querySelector('#todo-island');
  const shells = [isl, document.querySelector('.tfl-panel'), document.querySelector('.tfl-header')].filter(Boolean);
  const cs = (el) => getComputedStyle(el);
  // ① 图标描边统一：岛内所有 svg 的 stroke-width（lucide 默认 2）
  const strokes = {};
  for (const svg of document.querySelectorAll('#todo-island svg')) {
    const sw = svg.getAttribute('stroke-width') || cs(svg).strokeWidth;
    strokes[sw] = (strokes[sw] || 0) + 1;
  }
  // ② 形状单源：岛节点个数（必须 = 1；过渡态不许有第二个「假岛」）
  const islands = document.querySelectorAll('#todo-island').length;
  const cards = document.querySelectorAll('#task-list .tfl-card').length;
  // ③ 无渐变面：岛及其内容层的 background-image
  const gradients = shells.map((el) => ({ cls: el.className, bgImage: cs(el).backgroundImage }))
    .filter((x) => x.bgImage && x.bgImage !== 'none');
  // ④ 无发光：岛壳的 box-shadow / filter 读数
  const shellFx = shells.map((el) => ({ cls: el.className, boxShadow: cs(el).boxShadow, filter: cs(el).filter }));
  // ⑤ 面内静态发光件（逐元素列出，供报告如实申报「非形变面」）
  const glowy = [];
  for (const el of document.querySelectorAll('#todo-island *')) {
    const f = cs(el).filter;
    const bs = cs(el).boxShadow;
    if ((f && f !== 'none' && /drop-shadow|blur/.test(f)) || (bs && bs !== 'none' && /rgb/.test(bs))) {
      glowy.push({ cls: typeof el.className === 'string' ? el.className : '', filter: f, boxShadow: bs });
    }
  }
  return {
    strokes,
    islandNodes: islands,
    cardNodes: cards,
    gradients,
    shellFx,
    glowy: glowy.slice(0, 12),
    glowyCount: glowy.length,
  };
});
writeFileSync(join(OUT, 'static-checks.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
await browser.close();
