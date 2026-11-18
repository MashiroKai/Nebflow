// Route-backed browser regression: no decision-preview float may cover composer or dock.
import assert from 'node:assert/strict';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 375, height: 812 }, hasTouch: true, deviceScaleFactor: 2 });
  const { page, boot } = await createSession({ newPage: () => context.newPage() }, { width: 375, height: 812, baseURL: 'https://stand.test/' });
  await boot();
  await page.waitForSelector('body.mobile-layer');
  await settle(page);
  const result = await page.evaluate(() => {
    const entry = document.querySelector('button.decision-preview-entry');
    const input = document.querySelector('#input-bar');
    const dock = [...document.querySelectorAll('.dock-key')].filter(el => el.getClientRects().length);
    function hit(el) {
      const r = el.getBoundingClientRect();
      const found = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return { ok: !!found && (found === el || el.contains(found)), hit: found?.outerHTML.slice(0, 160) };
    }
    return { entry: entry?.outerHTML ?? null, input: input && hit(input), dock: dock.map(hit) };
  });
  assert.equal(result.entry, null, 'preview entry must not exist even when hidden');
  assert.ok(result.input?.ok, `composer center is obstructed: ${result.input?.hit}`);
  assert.ok(result.dock.length >= 3, 'mobile dock controls must be present');
  for (const item of result.dock) assert.ok(item.ok, `dock control center is obstructed: ${item.hit}`);
  console.log('decision-preview absence and composer/dock hit tests:', JSON.stringify(result));
  await context.close();
} finally { await browser.close(); }
