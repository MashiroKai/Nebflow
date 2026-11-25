#!/usr/bin/env node
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const source = p => readFileSync(`${ROOT}/src/main/resources/web/js/${p}`, 'utf8');
for (const p of ['panelSearch.js', 'canvasFavoritesPage.js']) {
  assert.match(source(p), /import[^;]*\bbuildFieldShell\b[^;]*from '\.\/socialSearchCore\.js'/s);
  assert.match(source(p), /buildFieldShell\(input, \{ placeholder:/);
}
const { browser } = await launchBrowser(loadPlaywright().mod);
const s = await createSession(browser, { width: 1280, height: 800, colorScheme: 'light' });
try {
  await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'fav-v2', messages: [] });
  await s.page.evaluate(async () => {
    const f = await import('/js/canvasFavorites.js');
    f.toggleFavoritePath({path:'/w/a.md',title:'A'});
    window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:'file:/w/a.md',itemType:'markdown',title:'A',content:'A',pinned:true,absPath:'/w/a.md'}}));
  });
  await s.page.waitForSelector('#canvas-fav-btn.is-on');
  await s.page.click('#favorites-btn');
  await s.page.waitForSelector('#panel-favorites .cfp-remove');
  await s.page.evaluate(() => { window.__favoriteToasts=[]; window.__showToast=(...args)=>window.__favoriteToasts.push(args); });
  let count=0;
  await s.page.route('**/api/canvas-favorites', route => {
    if (route.request().method() !== 'PUT') return route.fallback();
    count++;
    return route.fulfill({status:500,body:'failure'});
  });
  await s.page.locator('#panel-favorites .cfp-remove').click();
  await s.page.waitForFunction(() => window.__favoriteToasts.length > 0);
  assert.equal(await s.page.locator('#panel-favorites .cfp-row').count(),1,'failure restores row');
  assert(await s.page.locator('#canvas-fav-btn').evaluate(el=>el.classList.contains('is-on')),'failure restores star');
  assert.equal(await s.page.evaluate(()=>window.__favoriteToasts[0][1]),'error');
  await s.page.unroute('**/api/canvas-favorites');
  count=0;
  await s.page.route('**/api/canvas-favorites', async route => {
    if (route.request().method() !== 'PUT') return route.fallback();
    count++;
    return route.fulfill({status:200,contentType:'application/json',body:'{}'});
  });
  await s.page.evaluate(() => {
    const button=document.querySelector('#panel-favorites .cfp-remove');
    button.click();
    button.click();
  });
  await s.page.waitForFunction(() => document.querySelector('#panel-favorites .cfp-empty'));
  assert.equal(count,1,'two immediate requests settle once');
  assert.equal(await s.page.locator('#panel-favorites .cfp-row').count(),0);
  assert.equal(await s.page.locator('#panel-favorites .cfp-empty').count(),1);
  assert.equal(await s.page.locator('#canvas-fav-btn').evaluate(el=>el.classList.contains('is-on')),false);
  console.log('favorites-slot-v2: PASS');
} finally { await s.close(); await browser.close(); }
