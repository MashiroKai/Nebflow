// Offline real-shell regression: expanded Canvas header geometry and hit testing.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const out = process.env.CANVAS_INSET_EVIDENCE;
if (!out) throw new Error('CANVAS_INSET_EVIDENCE required');
mkdirSync(out, { recursive: true });
const browser = await loadPlaywright().mod.chromium.launch({ headless: true });
const readings = [];
const failures = [];
try {
  for (const width of [375, 320]) for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({ hasTouch: true, deviceScaleFactor: 2 });
    const session = await createSession({ newPage: opts => context.newPage(opts) }, { width, height: 812, colorScheme: theme, baseURL: 'https://stand.test/' });
    const { page } = session;
    await page.setViewportSize({ width, height: 812 });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await session.boot();
    await page.evaluate(async () => {
      const title = '中子谱剂量贡献角长标题示例测试文件';
      window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: {
        id: 'file:/tmp/inset-test.md', itemType: 'markdown', title, content: '# 标题\n\n文档内容', pinned: true, absPath: '/tmp/inset-test.md', size: 20,
      } }));
      (await import('/js/mobile/navController.js')).dockTo('canvas');
    });
    await page.waitForSelector('.mf-strip-chip.is-expanded .mf-cv-star:not([hidden])');
    await settle(page);
    const data = await page.evaluate(() => {
      const chip = document.querySelector('.mf-strip-chip.is-expanded');
      const star = chip.querySelector('.mf-cv-star');
      const label = chip.querySelector('.mf-strip-label');
      const quote = chip.querySelector('.mf-strip-quote');
      const rect = el => { const r = el.getBoundingClientRect(); return { x:r.x, y:r.y, width:r.width, height:r.height, right:r.right }; };
      const r = star.getBoundingClientRect();
      const hit = [];
      for (const x of [0.1, 0.5, 0.9]) for (const y of [0.1, 0.5, 0.9]) {
        const e = document.elementFromPoint(r.x + r.width*x, r.y + r.height*y);
        hit.push(e === star || star.contains(e));
      }
      return { padding: [getComputedStyle(chip).paddingLeft, getComputedStyle(chip).paddingRight], star: rect(star), label: rect(label), quote: rect(quote), chip: rect(chip), labelMax: getComputedStyle(label).maxWidth, hit, text: label.textContent, glass: getComputedStyle(chip, '::after').backgroundColor };
    });
    readings.push({ width, theme, ...data, errors: session.errors });
    await page.locator('.mf-canvas-page').screenshot({ path: join(out, `${width}-${theme}.png`), animations: 'disabled' });
    if (data.padding.join(',') !== '12px,12px') failures.push(`${width}/${theme}: padding ${data.padding}`);
    if (!data.hit.every(Boolean)) failures.push(`${width}/${theme}: star hit ${data.hit}`);
    if (data.star.width < 44 || data.star.height < 44) failures.push(`${width}/${theme}: star below 44`);
    if (data.label.right > data.quote.x + 0.5) failures.push(`${width}/${theme}: label overlaps quote`);
    if (parseFloat(data.labelMax) > data.label.width / 1.1 + 0.1) failures.push(`${width}/${theme}: flex clips the fitted label`);
    if (session.errors.length) failures.push(`${width}/${theme}: ${session.errors.join('; ')}`);
    await context.close();
  }
} finally {
  await browser.close();
  writeFileSync(join(out, 'readings.json'), JSON.stringify({ readings, failures }, null, 2));
}
console.log(JSON.stringify({ readings, failures }, null, 2));
assert.deepEqual(failures, []);
