#!/usr/bin/env node
// Full-shell long-text and sibling geometry checks. No DOM clone.
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const primary = ROOT.split('/.stand/worktrees/')[0];
const shots = process.env.FAV_SLOT_SHOTS || join(primary, '.stand/tmp/favorites-slot/evidence/geometry');
mkdirSync(shots, { recursive: true });
const samples = ['很长的中文收藏标题用于检验换行完整显示'.repeat(5), 'VeryLongUnbrokenEnglishWord'.repeat(8), '/long/path/'.repeat(22), '1234567890'.repeat(22), '😀🧭📚🌈'.repeat(20), '中文English123😀'.repeat(12), '短'];
const boot = { config: { llm: { providers: {} }, features: {} }, sessionId: 'slot-text', messages: [] };
const { browser } = await launchBrowser(loadPlaywright().mod);
async function measure(page, root) {
  return page.evaluate((sel) => {
    const root = document.querySelector(sel);
    const text = [...root.querySelectorAll('.cfp-row-title,.cfp-row-src,.cfp-row-date,.cfp-empty,.cfp-search-input')].map(el => ({name: el.className, sw: el.scrollWidth, cw: el.clientWidth, sh: el.scrollHeight, ch: el.clientHeight, overflow: getComputedStyle(el).textOverflow}));
    const pairs = [];
    // Compare visible, non-ancestor leaf elements clipped by each overflow ancestor.
    // Ancestor/descendant pairs are intentional containment, not overlap.
    // SVG paths form a single icon; compare its SVG bounding box, not its strokes.
    const leaves=[...root.querySelectorAll('*')].filter(el => (el.tagName.toLowerCase()==='svg' || (!el.children.length && !el.closest('svg'))) && getComputedStyle(el).visibility !== 'hidden' && getComputedStyle(el).display !== 'none');
    function visibleRect(el) {
      const r=el.getBoundingClientRect(); let left=r.left, top=r.top, right=r.right, bottom=r.bottom;
      for(let p=el.parentElement;p;p=p.parentElement) {
        const s=getComputedStyle(p), q=p.getBoundingClientRect();
        if(/(hidden|clip|auto|scroll)/.test(s.overflowX)) {left=Math.max(left,q.left);right=Math.min(right,q.right);}
        if(/(hidden|clip|auto|scroll)/.test(s.overflowY)) {top=Math.max(top,q.top);bottom=Math.min(bottom,q.bottom);}
      }
      return {left:Math.max(0,left),top:Math.max(0,top),right:Math.min(innerWidth,right),bottom:Math.min(innerHeight,bottom)};
    }
    const boxes=leaves.map(el=>({el,r:visibleRect(el)})).filter(({r})=>r.right>r.left && r.bottom>r.top);
    for(let i=0;i<boxes.length;i++) for(let j=i+1;j<boxes.length;j++) {
      const a=boxes[i],b=boxes[j];
      if(Math.min(a.r.right,b.r.right)-Math.max(a.r.left,b.r.left)>1 && Math.min(a.r.bottom,b.r.bottom)-Math.max(a.r.top,b.r.top)>1) pairs.push([a.el.outerHTML.slice(0,160),b.el.outerHTML.slice(0,160)]);
    }
    for (const row of root.querySelectorAll('.cfp-row')) {
      const els = [...row.querySelectorAll('.cfp-main,.cfp-thumb,.cfp-row-title,.cfp-row-meta,.cfp-row-src,.cfp-row-date')];
      for (let i=0;i<els.length;i++) for (let j=i+1;j<els.length;j++) {
        const a=els[i],b=els[j]; if (a.contains(b)||b.contains(a)) continue;
        const x=a.getBoundingClientRect(), y=b.getBoundingClientRect();
        if (Math.min(x.right,y.right)-Math.max(x.left,y.left)>1 && Math.min(x.bottom,y.bottom)-Math.max(x.top,y.top)>1) pairs.push([a.className,b.className]);
      }
    }
    const rows = [...root.querySelectorAll('.cfp-row')].map(el => el.getBoundingClientRect());
    for (let i=1;i<rows.length;i++) if (rows[i-1].bottom > rows[i].top+1) pairs.push(['row','row']);
    const sections = [...root.querySelectorAll('.cfp-bar,.cfp-search,.cfp-tabs,.cfp-body')].filter(el => el.getBoundingClientRect().height>0).map(el=>({n:el.className,r:el.getBoundingClientRect()}));
    for(let i=0;i<sections.length;i++) for(let j=i+1;j<sections.length;j++) { const a=sections[i],b=sections[j]; if(Math.min(a.r.bottom,b.r.bottom)-Math.max(a.r.top,b.r.top)>1) pairs.push([a.n,b.n]); }
    return { text, pairs };
  }, root);
}
function assertText(result) { for (const e of result.text) assert(e.sw <= e.cw+1 && e.sh <= e.ch+1 && e.overflow !== 'ellipsis', `clipped ${JSON.stringify(e)}`); }
function assertNoOverlap(result) { assert.deepEqual(result.pairs, [], 'independent visible siblings overlap'); }
try {
  for (const [width,height] of [[1280,800],[1440,900],[375,844],[390,844]]) {
    const s=await createSession(browser,{width,height,colorScheme:'light',explorerEntries:samples.map((name,i)=>({name:`${i}-${name.replaceAll('/','_')}.md`,type:'file',size:1}))});
    try {
      await s.boot(boot);
      await s.page.evaluate(async (texts) => {
        const f=await import('/js/canvasFavorites.js');
        for(let i=0;i<texts.length;i++) f.toggleFavoritePath({path:'/workspace/'+String(i)+'/'+texts[(i+2)%texts.length]+'.md',title:texts[i]});
        if (innerWidth < 500) (await import('/js/mobile/navController.js')).dockTo('workbench');
      },samples);
      if(width<500) await s.page.locator('.mobile-page[data-page="workbench"].live .mf-hub-row[data-hub-page="favorites"]').click(); else await s.page.click('#favorites-btn');
      const root=width<500?'#canvas-fav-page':'#panel-favorites';
      await s.page.waitForSelector(`${root} .cfp-row`); await settle(s.page,100);
      for (const position of [0,0.5,1]) {
        await s.page.locator(`${root} .cfp-body`).evaluate((el,p)=>{el.scrollTop=(el.scrollHeight-el.clientHeight)*p;},position);
        const result=await measure(s.page,root); assertText(result); assertNoOverlap(result);
      }
      if(width===1280) {
        await s.page.screenshot({path:join(shots,'desktop-1280-long-favorites.png'),animations:'disabled'});
        const title=s.page.locator(`${root} .cfp-row-title`).first();
        await title.evaluate(el=>{el.style.whiteSpace='nowrap';el.style.overflow='hidden';el.style.textOverflow='ellipsis';});
        const badText = await measure(s.page,root);
        assert.throws(() => assertText(badText), /clipped/);
        await title.evaluate(el=>el.removeAttribute('style'));
        const thumb=s.page.locator(`${root} .cfp-thumb`).first();
        await thumb.evaluate(el=>{el.style.position='absolute';el.style.left='0';el.style.width='100%';});
        const badOverlap=await measure(s.page,root);
        assert.throws(() => assertNoOverlap(badOverlap), /overlap/);
        await thumb.evaluate(el=>el.removeAttribute('style'));
        console.log('PASS independent clipping and overlap negative controls');
      }
      // Single-line search is an explicitly approved exception: keyboard navigation
      // must retain the complete value without widening the surrounding panel.
      const search=s.page.locator(`${root} .cfp-search-input`);
      const query='SearchLongValue0123456789'.repeat(12);
      await search.fill(query);
      await search.press('Home'); await search.press('End');
      const inputState=await search.evaluate(el=>({value:el.value, start:el.selectionStart, end:el.selectionEnd, scrollLeft:el.scrollLeft, panelScroll:el.closest('.cfp-page').scrollWidth, panelWidth:el.closest('.cfp-page').clientWidth, rect:el.getBoundingClientRect().toJSON()}));
      assert.equal(inputState.value,query); assert.equal(inputState.start,query.length); assert.equal(inputState.end,query.length); assert(inputState.scrollLeft>0);
      assert(inputState.panelScroll<=inputState.panelWidth+1);
      assert(await search.evaluate(el=>{const r=el.getBoundingClientRect();return [0.05,0.5,0.95].every(x=>[0.05,0.5,0.95].every(y=>{const hit=document.elementFromPoint(r.left+r.width*x,r.top+r.height*y);return hit===el||el.contains(hit);}));}), 'search not obscured at all nine points');
      const filtered=await measure(s.page,root); assertNoOverlap(filtered);
      if(width===1280||width===375) await s.page.screenshot({path:join(shots,`search-long-${width}.png`),animations:'disabled'});
      await search.fill('');
      console.log(`PASS favorites text and siblings ${width}`);
      if (width >= 1000) {
        await s.page.click('#files-btn');
        const rows=s.page.locator('#explorer-tree .explorer-file');
        await rows.first().waitFor();
        assertNoOverlap(await measure(s.page,'#panel-sessions'));
        const fileTree=s.page.locator('#explorer-tree');
        for(const position of [0,0.5,1]) {
          await fileTree.evaluate((el,p)=>{el.scrollTop=(el.scrollHeight-el.clientHeight)*p;},position);
          assertNoOverlap(await measure(s.page,'#panel-sessions'));
        }
        for(let i=0;i<samples.length;i++) {
          const row=rows.filter({hasText:samples[i].replaceAll('/','_')}).first();
          const details=await row.evaluate(el=>{const label=el.querySelector('.explorer-label');return {title:el.title, aria:el.getAttribute('aria-label'), text:label.textContent, overflow:getComputedStyle(label).textOverflow, sw:label.scrollWidth,cw:label.clientWidth};});
          assert.equal(details.title,details.text); assert.equal(details.aria,details.text);
          if(i!==6) assert(details.overflow==='ellipsis' && details.sw>details.cw, `expected documented ellipsis ${JSON.stringify(details)}`);
        }
        await s.page.screenshot({path:join(shots,`desktop-${width}-long-files.png`),animations:'disabled'});
        console.log(`EXCEPTION file labels single-line ellipsis with full title/aria ${width}`);
      }
    } finally {await s.close();}
  }
} finally {await browser.close();}
