import { chromium, boot, injectDelegate } from './lib.mjs';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const HERE = dirname(fileURLToPath(import.meta.url));
const delegate = JSON.parse(readFileSync(join(HERE, '..', 'fixtures', 'delegate.json'), 'utf8'));
const browser = await chromium.launch();
const { page } = await boot(browser, { width: 375, height: 812, scheme: 'dark', mobile: true, dsf: 3 });
await injectDelegate(page, delegate);
console.log('body classes:', await page.evaluate(() => document.body.className));
await page.locator('.bubble.injected .nf-rcpt-note').last().click();
await page.waitForTimeout(400);
console.log('nav:', await page.evaluate(() => import('/js/mobile/navController.js').then((m) => { m.navigateTo('canvas'); return { nav: m.navState && m.navState() }; })));
await page.waitForTimeout(900);
console.log('after nav body:', await page.evaluate(() => document.body.className));
console.log(await page.evaluate(() => {
  const pane = document.querySelector('.canvas-tab-pane[data-type="filechanges"]');
  const panel = document.getElementById('canvas-panel');
  const d = (e) => { if (!e) return null; const cs = getComputedStyle(e); const r = e.getBoundingClientRect(); return { display: cs.display, visibility: cs.visibility, opacity: cs.opacity, w: +r.width.toFixed(1), h: +r.height.toFixed(1), top: +r.top.toFixed(1), cls: e.className.toString().slice(0, 120), transform: cs.transform, position: cs.position }; };
  return {
    body: document.body.className,
    panel: d(panel),
    pane: d(pane),
    view: d(document.querySelector('.fcv-view')),
    body2: d(document.querySelector('.nf-diff-body')),
    deck: d(document.querySelector('.deck-layer')),
    cards: [...document.querySelectorAll('[class*="deck-card"], .mf-card')].map(e => e.className.toString().slice(0, 80)),
    sidebar: d(document.getElementById('sidebar')),
  };
}, null));
await page.screenshot({ path: '/Users/kaiyu/stand-evidence/mobile-diff-survey-20261118/current/debug-mobile.png' });
await browser.close();
