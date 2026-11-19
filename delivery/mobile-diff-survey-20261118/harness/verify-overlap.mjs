// verify-overlap.mjs — numeric check: does each diff face's own header collide
// with the floating canvas chrome (card header pill / close / stack button) on
// the mobile layer?
import { chromium, boot, injectAsk, ensureAskSession, injectDelegate, openDelegateReader, raf } from './lib.mjs';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ask = JSON.parse(readFileSync(join(HERE, '..', 'fixtures', 'ask.json'), 'utf8'));
const delegate = JSON.parse(readFileSync(join(HERE, '..', 'fixtures', 'delegate.json'), 'utf8'));
const ASK_ABS = '/Users/kaiyu/Claude code/Stand/README.md';
const EVID = '/Users/kaiyu/stand-evidence/mobile-diff-survey-20261118/current';

const OVERLAP = `
window.__overlap = (aSel, bSel) => {
  const a = document.querySelector(aSel), b = document.querySelector(bSel);
  if (!a || !b) return null;
  const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
  if (ar.width === 0 || br.width === 0) return { aSel, bSel, aVisible: ar.width > 0, bVisible: br.width > 0, hit: false };
  const hit = !(ar.left >= br.right || br.left >= ar.right || ar.top >= br.bottom || br.top >= ar.bottom);
  return { aSel, bSel, hit, aTop: +ar.top.toFixed(1), aBottom: +ar.bottom.toFixed(1), aLeft: +ar.left.toFixed(1), aRight: +ar.right.toFixed(1),
           bTop: +br.top.toFixed(1), bBottom: +br.bottom.toFixed(1), bLeft: +br.left.toFixed(1), bRight: +br.right.toFixed(1),
           overlapX: +Math.max(0, Math.min(ar.right, br.right) - Math.max(ar.left, br.left)).toFixed(1),
           overlapY: +Math.max(0, Math.min(ar.bottom, br.bottom) - Math.max(ar.top, br.top)).toFixed(1) };
};
window.__chrome = () => [...document.querySelectorAll('#canvas-panel .canvas-header, .canvas-header, #deck-fab, .deck-fab, .canvas-fab, [class*="canvas-header"]')].map((e) => { const r = e.getBoundingClientRect(); return { cls: e.className.toString().slice(0, 60), top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), left: +r.left.toFixed(1), right: +r.right.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; }).filter((x) => x.w > 0);
window.__padTop = (sel) => { const e = document.querySelector(sel); return e ? getComputedStyle(e).paddingTop : null; };
`;

const browser = await chromium.launch();
const out = {};
try {
  // ask face
  {
    const { page } = await boot(browser, { width: 375, height: 812, scheme: 'dark', mobile: true, dsf: 3 });
    await page.evaluate(OVERLAP);
    const sid = await ensureAskSession(page, ASK_ABS, 'README.md');
    await injectAsk(page, { ...ask, sessionId: sid });
    await raf(page, 3);
    out.ask = await page.evaluate(() => ({
      chrome: window.__chrome(),
      headVsHeader: window.__overlap('[data-tab-id^="askreview:"] .nf-ar-head', '[data-tab-id^="askreview:"] .nf-ar-head'),
      bodyVsChrome: window.__chrome().map((c) => window.__overlap('[data-tab-id^="askreview:"] .nf-ar-head', c.cls ? '.' + c.cls.split(' ')[0] : 'body')),
      bodyPadTop: window.__padTop('[data-tab-id^="askreview:"] .nf-ar-body'),
      panePadTop: window.__padTop('[data-tab-id^="askreview:"]'),
      firstHunkBarRect: (() => { const e = document.querySelector('[data-tab-id^="askreview:"] .nf-ar-hunkbar'); if (!e) return null; const r = e.getBoundingClientRect(); return { top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1) }; })(),
      acceptAllRect: (() => { const e = document.querySelector('[data-tab-id^="askreview:"] .nf-ar-accept-all'); if (!e) return null; const r = e.getBoundingClientRect(); return { top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), left: +r.left.toFixed(1), right: +r.right.toFixed(1) }; })(),
    }));
    await page.close();
  }
  // delegate face
  {
    const { page } = await boot(browser, { width: 375, height: 812, scheme: 'dark', mobile: true, dsf: 3 });
    await page.evaluate(OVERLAP);
    await injectDelegate(page, delegate);
    await openDelegateReader(page);
    await raf(page, 3);
    out.delegate = await page.evaluate(() => ({
      chrome: window.__chrome(),
      viewPadTop: window.__padTop('[data-type="filechanges"] .fcv-view'),
      headPillRect: (() => { const e = document.querySelector('[data-type="filechanges"] .fcv-headpill'); if (!e) return null; const r = e.getBoundingClientRect(); return { top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), left: +r.left.toFixed(1), right: +r.right.toFixed(1) }; })(),
      overlaps: window.__chrome().map((c) => window.__overlap('[data-type="filechanges"] .fcv-headpill', '.' + String(c.cls).split(' ')[0])),
    }));
    await page.close();
  }
} finally { await browser.close(); }
writeFileSync(join(EVID, 'overlap-check.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
