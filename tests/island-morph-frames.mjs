// island-morph-frames.mjs — 形变逐帧证据（真截图，非录屏转码帧）。
// 点岛后用 clip 连续抓 8 帧（≈25–45ms 间隔），证明「同一片岛在长大」，
// 同时落一份逐帧几何读数（宽/高/圆角/中线），供报告逐帧表引用。
import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootTodoFace, itemFixture, TAG_FIXTURE } from './_todo-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const OUT = process.env.EVID_DIR || join(ROOT, 'evidence', '20261105_todo-island', 'shots');
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, colorScheme: 'dark' });
const page = await ctx.newPage();
await bootTodoFace(page, { tags: TAG_FIXTURE, items: itemFixture() });
await sleep(500);

const clip = { x: 340, y: 60, width: 500, height: 340 };
const shot = (p) => page.screenshot({ path: join(OUT, p), clip });
const frameInfo = () => page.evaluate(() => {
  const isl = document.querySelector('#todo-island');
  const r = isl.getBoundingClientRect();
  const cs = getComputedStyle(isl);
  return {
    t: +performance.now().toFixed(0),
    w: +r.width.toFixed(2), h: +r.height.toFixed(2),
    cx: +(r.left + r.width / 2).toFixed(2),
    radius: cs.borderRadius, bg: cs.backgroundColor,
    capOp: +(+getComputedStyle(document.querySelector('.tfl-capsule')).opacity).toFixed(2),
    panelOp: +(+getComputedStyle(document.querySelector('.tfl-panel')).opacity).toFixed(2),
    same: isl === window.__isl,
  };
});

await page.evaluate(() => { window.__isl = document.querySelector('#todo-island'); });
await shot('frame-0-collapsed.png');
const frames = [await frameInfo()];

await page.locator('#task-list .tfl-capsule').click();
for (let i = 1; i <= 6; i++) {
  await sleep(i === 1 ? 20 : 45);
  frames.push(await frameInfo());
  await shot(`frame-${i}-morph.png`);
}
await sleep(700);
frames.push(await frameInfo());
await shot('frame-7-open.png');
writeFileSync(join(OUT, 'morph-frames.json'), JSON.stringify(frames, null, 2));
console.log(JSON.stringify(frames, null, 2));
await browser.close();
