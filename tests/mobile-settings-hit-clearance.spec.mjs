// Route-backed browser check for the settings list, without a backend.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const out = process.env.EVIDENCE_DIR || '/private/tmp/stand-overlay-diagnostics/settings-bottom';
mkdirSync(out, { recursive: true });
const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true });
  const { page, boot } = await createSession({ newPage: () => context.newPage() }, { width: 375, height: 812, baseURL: 'https://stand.test/' });
  await boot(); await page.waitForSelector('body.mobile-layer');
  await page.evaluate(async () => (await import('/js/mobile/navController.js')).pushPage('settings'));
  await settle(page);
  await page.locator('#settings-content').evaluate(e => { e.scrollTop = e.scrollHeight; });
  await settle(page);
  const result = await page.evaluate(() => {
    const scroller = document.querySelector('#settings-content');
    const visible = e => !!(e.offsetWidth && e.offsetHeight) && getComputedStyle(e).visibility !== 'hidden';
    const all = [...scroller.querySelectorAll('button, input, select, a[href], [role="button"]')].filter(visible);
    const el = all.at(-1);
    const r = el.getBoundingClientRect();
    const s = scroller.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const hit = document.elementFromPoint(x, y);
    return { scrollTop: scroller.scrollTop, scrollHeight: scroller.scrollHeight, clientHeight: scroller.clientHeight,
      target: el.outerHTML.slice(0, 400), rect: r.toJSON(), scroller: s.toJSON(), x, y,
      hit: hit?.outerHTML.slice(0, 400), hitTarget: hit === el || el.contains(hit),
      previewHidden: getComputedStyle(document.querySelector('.decision-preview-entry')).display === 'none' };
  });
  writeFileSync(`${out}/geometry.json`, JSON.stringify(result, null, 2));
  await page.screenshot({ path: `${out}/settings-bottom.png`, animations: 'disabled' });
  console.log(JSON.stringify(result, null, 2));
  assert.ok(result.previewHidden);
  assert.ok(result.rect.top >= result.scroller.top && result.rect.bottom <= result.scroller.bottom, 'last clickable control is entirely within scroll viewport');
  assert.ok(result.hitTarget, 'last clickable control receives hit test');
} finally { await browser.close(); }
