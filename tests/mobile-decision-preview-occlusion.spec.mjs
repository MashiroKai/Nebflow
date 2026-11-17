// Mobile decision-preview emergency occlusion regression. Route-backed real browser; no server.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const out = process.env.EVIDENCE_DIR || '/private/tmp/stand-decision-preview-evidence';
mkdirSync(out, { recursive: true });
const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, deviceScaleFactor: 1 });
  const { page, boot } = await createSession({ newPage: () => context.newPage() }, { width: 375, height: 812, baseURL: 'https://stand.test/' });
  await boot();
  await page.waitForSelector('body.mobile-layer');
  await page.evaluate(async () => (await import('/js/mobile/navController.js')).pushPage('settings'));
  await settle(page);
  await page.evaluate(() => { for (const el of document.querySelectorAll('.mobile-page[data-page="settings"] *')) if (el.scrollHeight > el.clientHeight + 10) el.scrollTop = el.scrollHeight; });
  await settle(page);
  const entry = page.locator('.decision-preview-entry');
  assert.equal(await entry.isVisible(), false, 'mobile preview is not an overlay');
  // Recreate the previous rule as a negative control, keeping the real entry and real page.
  await entry.evaluate(el => el.style.setProperty('display', 'block', 'important'));
  await settle(page);
  const old = await page.evaluate(() => {
    const el = document.querySelector('.decision-preview-entry');
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const hit = document.elementFromPoint(x, y);
    return { rect: r.toJSON(), x, y, hit: hit?.outerHTML.slice(0, 250), entryHit: el === hit || el.contains(hit) };
  });
  assert.equal(old.entryHit, true, 'negative control must intercept the point');
  await page.screenshot({ path: `${out}/before.png`, animations: 'disabled' });
  await entry.evaluate(el => el.style.removeProperty('display'));
  await settle(page);
  const after = await page.evaluate(({x,y}) => {
    const el = document.elementFromPoint(x,y);
    const r = el?.getBoundingClientRect();
    return { hit: el?.outerHTML.slice(0,250), rect: r?.toJSON(), entryHit: !!el?.closest('.decision-preview-entry') };
  }, old);
  assert.equal(after.entryHit, false, 'previously intercepted point must be released');
  assert.ok(after.hit && !after.hit.startsWith('<body'), 'released point hits page content');
  await page.screenshot({ path: `${out}/after.png`, animations: 'disabled' });
  // Render the two saved browser screenshots side by side, without image-processing dependencies.
  const { readFileSync } = await import('node:fs');
  const before = readFileSync(`${out}/before.png`).toString('base64');
  const after64 = readFileSync(`${out}/after.png`).toString('base64');
  const comparison = await context.newPage();
  await comparison.setViewportSize({ width: 750, height: 812 });
  await comparison.setContent(`<style>body{margin:0;display:flex}img{width:375px;height:812px}</style><img src="data:image/png;base64,${before}"><img src="data:image/png;base64,${after64}">`);
  await comparison.screenshot({ path: `${out}/comparison.png` });
  writeFileSync(`${out}/geometry.json`, JSON.stringify({ viewport: [375,812], old, after, negativeControl: 'old entry intercepted the same point' }, null, 2));
  console.log(JSON.stringify({ old, after, out }));
} finally { await browser.close(); }
