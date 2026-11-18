// probe-chrome.mjs — list the floating canvas chrome that occupies the top band
// of the mobile canvas page, and test it against each diff face's own header.
import { chromium, boot, injectAsk, ensureAskSession, injectDelegate, openDelegateReader, raf } from './lib.mjs';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ask = JSON.parse(readFileSync(join(HERE, '..', 'fixtures', 'ask.json'), 'utf8'));
const delegate = JSON.parse(readFileSync(join(HERE, '..', 'fixtures', 'delegate.json'), 'utf8'));
const EVID = '/Users/kaiyu/stand-evidence/mobile-diff-survey-20261118/current';

const CHROME = `window.__topFloats = () => {
  const out = [];
  for (const e of document.querySelectorAll('#canvas-head-close, #deck-fab, #canvas-panel .canvas-header, #canvas-panel .canvas-header *, #canvas-head-close *, #deck-fab *')) {
    const cs = getComputedStyle(e); const r = e.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue;
    if (r.top < 70 && r.bottom > 0 && (cs.position === 'fixed' || cs.position === 'absolute' || cs.position === 'sticky') && cs.visibility !== 'hidden') {
      out.push({ sel: (e.id ? '#' + e.id : '') + (e.className ? '.' + String(e.className).trim().split(/\\s+/).slice(0,2).join('.') : ''),
        pos: cs.position, z: cs.zIndex, top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), left: +r.left.toFixed(1), right: +r.right.toFixed(1) });
    }
  }
  return out;
};
window.__at = (x, y) => document.elementsFromPoint(x, y).map((e) => ({ sel: (e.id ? '#' + e.id : '') + (e.className ? '.' + String(e.className).trim().split(/\s+/).slice(0, 2).join('.') : ''), tag: e.tagName })).slice(0, 10);
window.__hit = (aSel, bRect) => {
  const a = document.querySelector(aSel); if (!a) return null; const ar = a.getBoundingClientRect();
  const hit = !(ar.left >= bRect.right || bRect.left >= ar.right || ar.top >= bRect.bottom || bRect.top >= ar.bottom);
  return { face: aSel, hit, faceTop: +ar.top.toFixed(1), faceBottom: +ar.bottom.toFixed(1),
    ovX: +Math.max(0, Math.min(ar.right, bRect.right) - Math.max(ar.left, bRect.left)).toFixed(1),
    ovY: +Math.max(0, Math.min(ar.bottom, bRect.bottom) - Math.max(ar.top, bRect.top)).toFixed(1) };
};`;

const browser = await chromium.launch();
const out = {};
try {
  for (const scheme of ['dark', 'light']) {
    { // ask
      const { page } = await boot(browser, { width: 375, height: 812, scheme, mobile: true, dsf: 3 });
      await page.evaluate(CHROME);
      const sid = await ensureAskSession(page, '/Users/kaiyu/Claude code/Stand/README.md', 'README.md');
      await injectAsk(page, { ...ask, sessionId: sid });
      await raf(page, 3);
      out[`ask-${scheme}`] = await page.evaluate(() => {
        const floats = window.__topFloats();
        return { floats, hits: floats.map((f) => window.__hit('[data-tab-id^="askreview:"] .nf-ar-head', f)),
          stackAt_close: window.__at(30, 35), stackAt_stackbtn: window.__at(345, 35), stackAt_acceptall: window.__at(50, 42),
          facePadTop: getComputedStyle(document.querySelector('[data-tab-id^="askreview:"] .nf-ar-body')).paddingTop,
          faceRect: (() => { const e = document.querySelector('[data-tab-id^="askreview:"] .nf-ar-head'); const r = e.getBoundingClientRect(); return { top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1) }; })() };
      });
      await page.close();
    }
    { // delegate
      const { page } = await boot(browser, { width: 375, height: 812, scheme, mobile: true, dsf: 3 });
      await page.evaluate(CHROME);
      await injectDelegate(page, delegate);
      await openDelegateReader(page);
      await raf(page, 3);
      out[`delegate-${scheme}`] = await page.evaluate(() => {
        const floats = window.__topFloats();
        return { floats, hits: floats.map((f) => window.__hit('[data-type="filechanges"] .fcv-headpill', f)),
          stackAt_close: window.__at(30, 35), stackAt_stackbtn: window.__at(345, 35), stackAt_pill: window.__at(180, 75),
          facePadTop: getComputedStyle(document.querySelector('[data-type="filechanges"] .fcv-view')).paddingTop,
          faceRect: (() => { const e = document.querySelector('[data-type="filechanges"] .fcv-headpill'); const r = e.getBoundingClientRect(); return { top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1) }; })() };
      });
      await page.close();
    }
  }
} finally { await browser.close(); }
writeFileSync(join(EVID, 'chrome-overlap.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
