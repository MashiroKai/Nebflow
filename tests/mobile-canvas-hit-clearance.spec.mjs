// Browser regression: body lines under mobile canvas chrome remain reachable by scrolling.
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const out = '/private/tmp/stand-overlay-diagnostics/canvas-hit'; mkdirSync(out,{recursive:true});
const documentText = readFileSync(new URL('../openspec/changes/decision-card-mvp/proposal.md', import.meta.url),'utf8');
const { mod }=loadPlaywright(); const browser=await mod.chromium.launch({headless:true});
try {
 const context=await browser.newContext({viewport:{width:375,height:812},hasTouch:true});
 const {page,boot}=await createSession({newPage:()=>context.newPage()},{width:375,height:812,baseURL:'https://stand.test/'});
 await boot(); await page.waitForSelector('body.mobile-layer');
 for(let n=1;n<=3;n++) { await page.evaluate(async ({n,documentText})=>{window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:`file:/tmp/decision-proposal-${n}.md`,itemType:'markdown',title:`proposal ${n}`,content:documentText,pinned:true,absPath:`/tmp/decision-proposal-${n}.md`}})); (await import('/js/mobile/navController.js')).dockTo('canvas');},{n,documentText}); await settle(page); }
 await page.waitForSelector('.mf-cv-host .canvas-md-scroll');
 const neighbors = await page.evaluate(() => [...document.querySelectorAll('.mf-cv-host .canvas-find-toggle,.mf-cv-host .canvas-ref-select,.ask-fab,.mf-dots')].filter(e=>e.getClientRects().length && getComputedStyle(e).display !== 'none').map(e=>({name:e.className,rect:e.getBoundingClientRect().toJSON()})));
 const ask = neighbors.find(e=>e.name.includes('ask-fab') && !e.name.includes('note-fab'));
 assert.ok(ask);
 for (const other of neighbors.filter(e=>e!==ask)) assert.ok(ask.rect.right <= other.rect.left || other.rect.right <= ask.rect.left || ask.rect.bottom <= other.rect.top || other.rect.bottom <= ask.rect.top, `ask overlaps ${other.name}`);
 const base=await page.evaluate(()=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll'); const control=document.querySelector('.mf-cv-host .canvas-find-toggle');const cr=control.getBoundingClientRect(); const candidates=[...sc.querySelectorAll('p,li,h2,h3')]; for(const el of candidates){ sc.scrollTop += el.getBoundingClientRect().top-(cr.top+10); const r=el.getBoundingClientRect(); const x=Math.max(cr.left+10,Math.min(cr.right-10,r.left+10)),y=Math.max(cr.top+4,Math.min(cr.bottom-4,r.top+Math.min(r.height/2,12))); const hit=document.elementFromPoint(x,y);if(r.top<cr.bottom&&r.bottom>cr.top&&r.left<cr.right&&r.right>cr.left&&(hit===control||control.contains(hit))){el.dataset.occlusionProbe='true';return {scrollTop:sc.scrollTop,control:cr.toJSON(),target:r.toJSON(),x,y,hit:hit?.outerHTML.slice(0,200),text:el.textContent.slice(0,120)};}} return null;});
 assert.ok(base,'a real document line should pass underneath search at a scroll position');
 await page.screenshot({path:`${out}/covered.png`,animations:'disabled'});
 const clear=await page.evaluate(()=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll');const el=sc.querySelector('[data-occlusion-probe]');sc.scrollTop+=el.getBoundingClientRect().top-200;const r=el.getBoundingClientRect();const x=Math.max(r.left+10,26),y=r.top+Math.min(r.height/2,12);const hit=document.elementFromPoint(x,y);return {scrollTop:sc.scrollTop,target:r.toJSON(),x,y,hit:hit?.outerHTML.slice(0,200),hitTarget:hit===el||el.contains(hit)};});
 await page.screenshot({path:`${out}/clear.png`,animations:'disabled'});
 writeFileSync(`${out}/geometry.json`,JSON.stringify({neighbors,base,clear},null,2)); console.log(JSON.stringify({neighbors,base,clear},null,2)); assert.ok(clear.hitTarget,'scrolling makes the covered line readable/hittable');
} finally {await browser.close();}
