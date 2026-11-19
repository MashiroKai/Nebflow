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
  const middle = await page.locator('#settings-content').evaluate(e => {
    e.scrollTop = (e.scrollHeight - e.clientHeight) / 2;
    const s = e.getBoundingClientRect();
    const back = document.querySelector('.mobile-page[data-page="settings"] .mobile-back-btn').getBoundingClientRect();
    const candidates = [...e.querySelectorAll('.set-card-title, .set-card, button')].filter(el => { const r = el.getBoundingClientRect(); return r.bottom > s.top && r.top < s.bottom; });
    const first = candidates[0]; const r = first?.getBoundingClientRect();
    const x = r ? Math.max(r.left + 5, back.left + 5) : 0, y = s.top + 2;
    const card = first?.closest('.set-card') || first;
    const sticky = [...e.querySelectorAll('*')].filter(el => getComputedStyle(el).position === 'sticky').map(el => ({html:el.outerHTML.slice(0,120),rect:el.getBoundingClientRect().toJSON()}));
    const clippedHit = document.elementFromPoint(x, Math.min(s.top - 1, Math.max(1, r.top + 5)));
    const clipped = { scrollTop: e.scrollTop, rect: r?.toJSON(), hit:clippedHit?.outerHTML.slice(0,200) };
    e.scrollTop += r.top - (s.top + 12);
    const rr = first.getBoundingClientRect(), hit = document.elementFromPoint(rr.left + 20, rr.top + 10);
    return {scroller:s.toJSON(), back:back.toJSON(), clipped, card:card?.outerHTML.slice(0,200), sticky, revealed: {scrollTop:e.scrollTop, rect:rr.toJSON(), hit:hit?.outerHTML.slice(0,200), hitTarget:first===hit || first.contains(hit)}};
  });
  assert.ok(middle.scroller.top >= middle.back.bottom, 'settings scroller begins below the top control');
  assert.ok(middle.clipped.rect.top < middle.scroller.top, 'middle position clips the card at the scroll viewport');
  assert.ok(middle.revealed.rect.top >= middle.scroller.top && middle.revealed.hitTarget, 'scrolling upward reveals the same card');
  console.log('settings middle:', JSON.stringify(middle));
  await page.screenshot({path:`${out}/settings-middle.png`,animations:'disabled'});
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
      previewHidden: !document.querySelector('.decision-preview-entry') || getComputedStyle(document.querySelector('.decision-preview-entry')).display === 'none' };
  });
  writeFileSync(`${out}/geometry.json`, JSON.stringify(result, null, 2));
  await page.screenshot({ path: `${out}/settings-bottom.png`, animations: 'disabled' });
  console.log(JSON.stringify(result, null, 2));
  assert.ok(result.previewHidden);
  assert.ok(result.rect.top >= result.scroller.top && result.rect.bottom <= result.scroller.bottom, 'last clickable control is entirely within scroll viewport');
  assert.ok(result.hitTarget, 'last clickable control receives hit test');
} finally { await browser.close(); }
