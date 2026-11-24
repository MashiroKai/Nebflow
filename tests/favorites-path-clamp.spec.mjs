#!/usr/bin/env node
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';

const shots = join(ROOT.split('/.stand/worktrees/')[0], '.stand/tmp/favorites-path-clamp/evidence');
const { browser, label } = await launchBrowser(loadPlaywright().mod);
const before = process.env.CLAMP_BEFORE === '1';
try {
  for (const theme of ['light', 'dark']) for (const size of [256, 512, 1024]) {
    const s = await createSession(browser, { width: 1280, height: 800, colorScheme: theme, explorerEntries: [] });
    try {
      await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'path-clamp', messages: [] });
      const path = '/' + 'p'.repeat(size - 4) + '.md';
      await s.page.evaluate(async path => (await import('/js/canvasFavorites.js')).toggleFavoritePath({ path, title: 'Path sample' }), path);
      await s.page.click('#favorites-btn');
      const row = s.page.locator('#panel-favorites .cfp-row').first();
      await row.waitFor();
      await s.page.locator('#sidebar').evaluate(el => { el.style.transition = 'none'; el.style.flex = '0 0 180px'; });
      await settle(s.page, 100);
      const state = await row.evaluate(el => {
        const src = el.querySelector('.cfp-row-src'), style = getComputedStyle(src);
        return { sidebar: document.querySelector('#sidebar').getBoundingClientRect().width, height: src.getBoundingClientRect().height, lineHeight: parseFloat(style.lineHeight), scrollHeight: src.scrollHeight, clamp: style.webkitLineClamp, overflow: style.overflow, title: src.title, text: src.textContent };
      });
      assert.equal(state.sidebar, 180); assert.equal(state.text, path);
      if (!before) {
        assert.equal(state.clamp, '2'); assert.equal(state.overflow, 'hidden');
        assert(state.height <= state.lineHeight * 2 + 1); assert(state.scrollHeight > state.height);
        assert.equal(state.title, path);
      }
      console.log(JSON.stringify({ phase: before ? 'before' : 'after', theme, bytes: size, browser: label, ...state, title: undefined, text: undefined }));
      await s.page.screenshot({ path: join(shots, `${before ? 'before' : 'after'}-${theme}-${size}.png`), animations: 'disabled' });
      if (!before) {
        const rowHeight = await row.evaluate(el => el.getBoundingClientRect().height);
        await s.page.locator('#panel-favorites .cfp-search-input').focus();
        for (let i = 0; i < 5 && !(await row.evaluate(el => el === document.activeElement)); i++) await s.page.keyboard.press('Tab');
        assert(await row.evaluate(el => el === document.activeElement), 'keyboard reaches row');
        const tooltip = s.page.locator('.cfp-path-tooltip');
        async function checkTip(mode) {
          assert.equal(await row.evaluate(el => el.getBoundingClientRect().height), rowHeight, 'disclosure must not grow row');
          const tip = await tooltip.evaluate(el => { const r=el.getBoundingClientRect(); return { text:el.textContent, x:r.left, y:r.top, right:r.right, bottom:r.bottom, width:innerWidth, height:innerHeight, scroll:el.scrollHeight, client:el.clientHeight, hit:document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)===el }; });
          assert.equal(tip.text,path); assert(tip.x>=0 && tip.y>=0 && tip.right<=tip.width && tip.bottom<=tip.height, `tooltip out of viewport ${JSON.stringify(tip)}`);
          assert(tip.hit, 'tooltip is not obscured'); assert(tip.scroll<=tip.client+1,'tooltip is clipped');
          if(size===1024) await s.page.screenshot({path:join(shots,`tooltip-${theme}-${mode}.png`),animations:'disabled'});
        }
        await checkTip('focus');
        await s.page.locator('#panel-favorites .cfp-search-input').focus();
        await row.hover();
        await checkTip('hover');
      }
    } finally { await s.close(); }
  }
  if (!before) {
    const s = await createSession(browser, { width: 1280, height: 800, colorScheme: 'light', explorerEntries: [] });
    try {
      await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'overflow-tooltip', messages: [] });
      const path = '/' + 'p'.repeat(4092) + '.md';
      await s.page.evaluate(async path => (await import('/js/canvasFavorites.js')).toggleFavoritePath({path, title:'Overflow tooltip'}), path);
      await s.page.click('#favorites-btn');
      const row = s.page.locator('#panel-favorites .cfp-row').first(); await row.waitFor(); await row.hover();
      const tip = s.page.locator('.cfp-path-tooltip');
      const state = await tip.evaluate(el => { el.scrollTop = el.scrollHeight; const r=el.getBoundingClientRect(); return {text:el.textContent, top:r.top, bottom:r.bottom, left:r.left, right:r.right, width:innerWidth, height:innerHeight, scrollTop:el.scrollTop, scrollHeight:el.scrollHeight, clientHeight:el.clientHeight}; });
      assert.equal(state.text,path); assert(state.top>=0 && state.left>=0 && state.right<=state.width && state.bottom<=state.height);
      assert(state.scrollHeight>state.clientHeight && state.scrollTop>0 && state.scrollTop+state.clientHeight>=state.scrollHeight-1, JSON.stringify(state));
      await s.page.screenshot({path:join(shots,'tooltip-4096-scroll-bottom.png'),animations:'disabled'});
    } finally { await s.close(); }
    const titleSession = await createSession(browser, { width: 1280, height: 800, colorScheme: 'light', explorerEntries: [] });
    {
    const s = titleSession;
    try {
      await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'long-title', messages: [] });
      const title = 'LongTitle'.repeat(20);
      await s.page.evaluate(async title => (await import('/js/canvasFavorites.js')).toggleFavoritePath({ path: '/short.md', title }), title);
      await s.page.click('#favorites-btn');
      await s.page.locator('#sidebar').evaluate(el => { el.style.transition = 'none'; el.style.flex = '0 0 180px'; });
      const item = s.page.locator('#panel-favorites .cfp-row-title').first(); await item.waitFor(); await settle(s.page, 100);
      const metrics = await item.evaluate(el => ({ text: el.textContent, height: el.clientHeight, scrollHeight: el.scrollHeight, lineHeight: parseFloat(getComputedStyle(el).lineHeight) }));
      assert(metrics.text.startsWith(title)); assert(metrics.height > metrics.lineHeight * 2); assert(metrics.scrollHeight <= metrics.height + 1);
      console.log('LONG-TITLE', JSON.stringify({ ...metrics, text: undefined }));
      await s.page.screenshot({ path: join(shots, 'after-long-title.png'), animations: 'disabled' });
    } finally { await s.close(); }
    }
  }
  console.log('favorites-path-clamp: PASS');
} finally { await browser.close(); }
