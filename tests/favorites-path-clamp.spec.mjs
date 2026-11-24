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
          if (mode === 'focus') assert(tip.hit, 'tooltip is not obscured'); else assert(!tip.hit, 'hover tooltip must not intercept pointer'); assert(tip.scroll<=tip.client+1,'tooltip is clipped');
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
    for (const width of [1280, 390]) for (const theme of ['light', 'dark']) {
      const s = await createSession(browser, { width, height: width === 390 ? 844 : 800, colorScheme: theme, explorerEntries: [], hasTouch: width===390, isMobile: width===390 });
      try {
        const requests=[];
        await s.page.route('**/api/nf-ticket', route => {const body=JSON.parse(route.request().postData()||'{}'),tickets={};for(const p of body.paths||[]) tickets[p]={t:`tok${p.length}`,exp:Date.now()+1800000,size:1};return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({tickets})});});
        await s.page.route(/\/api\/nf-file/, route => {requests.push(new URL(route.request().url()).searchParams.get('path'));return route.fulfill({status:200,contentType:'text/markdown',body:'# doc'});});
        await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'tooltip-bottom', messages: [] });
        const path = '/' + 'p'.repeat(1020) + '.md';
        await s.page.evaluate(async ({path, mobile}) => {
          const f = await import('/js/canvasFavorites.js');
          f.toggleFavoritePath({path, title:'Bottom target'});
          for (let i=0;i<12;i++) f.toggleFavoritePath({path:`/filler-${i}.md`,title:`Filler ${i}`});
          if (mobile) (await import('/js/mobile/navController.js')).dockTo('workbench');
        }, {path, mobile: width===390});
        if(width===390) await s.page.locator('.mobile-page[data-page="workbench"].live .mf-hub-row[data-hub-page="favorites"]').click();
        else { await s.page.click('#favorites-btn'); await s.page.locator('#sidebar').evaluate(el=>{el.style.transition='none';el.style.flex='0 0 180px';}); }
        const root=width===390?'#canvas-fav-page':'#panel-favorites';
        const row=s.page.locator(`${root} .cfp-row`).filter({hasText:'Bottom target'}); await row.waitFor();
        await row.evaluate(el=>{const body=el.closest('.cfp-body');body.scrollTop=body.scrollHeight;}); await settle(s.page,100);
        const placement=await row.evaluate(el=>{const body=el.closest('.cfp-body'), r=el.getBoundingClientRect(),b=body.getBoundingClientRect();return {scrollTop:body.scrollTop, max:body.scrollHeight-body.clientHeight, rowTop:r.top,rowBottom:r.bottom,bodyBottom:b.bottom};});
        assert(placement.max>0 && Math.abs(placement.max-placement.scrollTop)<=1, `not at bottom ${JSON.stringify(placement)}`);
        async function check(mode) {
          const measured=await s.page.evaluate(({root})=>{const el=document.querySelector('.cfp-path-tooltip'), row=[...document.querySelectorAll(`${root} .cfp-row`)].find(x=>x.textContent.includes('Bottom target')), body=row.closest('.cfp-body'),r=el.getBoundingClientRect(), rr=row.getBoundingClientRect();return {tip:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:innerWidth,height:innerHeight,hit:document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)===el,text:el.textContent},row:{top:rr.top,bottom:rr.bottom,height:rr.height,scrollTop:body.scrollTop}};},{root});
          const {tip}=measured; console.log('BOTTOM-SAME-FRAME',JSON.stringify({width,theme,mode,before:placement,after:measured.row}));
          if(tip.bottom>measured.row.top+1) await s.page.screenshot({path:join(shots,`bottom-failure-${width}-${theme}-${mode}.png`),animations:'disabled'});
          assert.equal(tip.text,path); assert(tip.left>=0 && tip.top>=0 && tip.right<=tip.width && tip.bottom<=tip.height, `out of viewport ${JSON.stringify(tip)}`);
          if(mode==='focus') assert(tip.hit,'tooltip center covered'); else assert(!tip.hit,'hover tooltip intercepts pointer'); assert(tip.bottom<=measured.row.top+1, `tooltip not above row ${JSON.stringify({tip,placement,after:measured.row})}`);
          console.log('BOTTOM',JSON.stringify({width,theme,mode,placement,tip:{...tip,text:undefined}}));
          await s.page.screenshot({path:join(shots,`bottom-${width}-${theme}-${mode}.png`),animations:'disabled'});
        }
        if (width===390) {
          assert(await s.page.evaluate(()=>matchMedia('(hover: none)').matches), 'touch context must not support hover');
          const hit=await row.evaluate(el=>{const r=el.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return hit===el||el.contains(hit);});
          assert(hit,'touch target must not be covered');
          await s.page.waitForFunction(path=>document.querySelector(`.cfp-row[data-fav-path="${path}"]`)?.getAttribute('aria-disabled') !== 'true',path);
          assert.notEqual(await row.getAttribute('aria-disabled'),'true');
          await s.page.screenshot({path:join(shots,`touch-390-${theme}-before.png`),animations:'disabled'});
          const count=requests.filter(p=>p===path).length;
          await row.tap(); await s.page.waitForFunction(()=>!document.querySelector('#canvas-fav-page'),null,{timeout:10000});
          assert(requests.filter(p=>p===path).length>count,'tap must request selected path');
          assert.equal(await s.page.locator('.cfp-path-tooltip:visible').count(),0,'touch must not show hover tooltip');
          await s.page.screenshot({path:join(shots,`touch-390-${theme}-after.png`),animations:'disabled'});
          continue;
        } else {
          await row.hover(); await check('hover');
          const pass=await s.page.locator('.cfp-path-tooltip').evaluate(el=>{const r=el.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2,hit=document.elementFromPoint(x,y);return {x,y,tag:hit?.tagName,cls:hit?.className};});
          assert.notEqual(pass.cls,'cfp-path-tooltip','hover must pass pointer through');
        }
        await s.page.mouse.move(width-5,5);
        await s.page.locator(`${root} .cfp-search-input`).focus();
        await s.page.keyboard.press('Tab');
        await row.focus();
        assert(await row.evaluate(el=>document.activeElement===el && el.matches(':focus-visible')),'bottom row has keyboard-visible focus');
        await check('focus');
      } finally { await s.close(); }
    }
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
      await s.page.locator('#panel-favorites .cfp-search-input').focus();
      for (let i=0; i<5 && !(await row.evaluate(el=>document.activeElement===el)); i++) await s.page.keyboard.press('Tab');
      assert(await row.evaluate(el=>document.activeElement===el));
      await s.page.keyboard.press('Tab'); assert(await tip.evaluate(el=>document.activeElement===el),'tooltip receives keyboard focus');
      await s.page.keyboard.press('End');
      const keyboard = await tip.evaluate(el=>{const range=document.createRange();range.setStart(el.firstChild,el.firstChild.length-3);range.setEnd(el.firstChild,el.firstChild.length);const a=range.getBoundingClientRect(),r=el.getBoundingClientRect();return {top:a.top,bottom:a.bottom,tipTop:r.top,tipBottom:r.bottom,scroll:el.scrollTop};});
      assert(keyboard.scroll>0 && keyboard.top>=keyboard.tipTop && keyboard.bottom<=keyboard.tipBottom, `keyboard cannot see end ${JSON.stringify(keyboard)}`);
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
