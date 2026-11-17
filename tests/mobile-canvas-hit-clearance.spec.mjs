// Mobile reader viewport must not extend behind its bottom controls.
// Real DOM/browser, route-backed frontend; no runtime server or data access.
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const out = process.env.CANVAS_HIT_EVIDENCE || '/private/tmp/stand-overlay-diagnostics/canvas-hit-fixed';
mkdirSync(out, {recursive:true});
const text = readFileSync(new URL('../openspec/changes/decision-card-mvp/proposal.md', import.meta.url), 'utf8');
const {mod}=loadPlaywright(); const browser=await mod.chromium.launch({headless:true});
try {
 const context=await browser.newContext({viewport:{width:375,height:812},hasTouch:true});
 const {page,boot}=await createSession({newPage:()=>context.newPage()},{width:375,height:812,baseURL:'https://stand.test/'});
 await boot(); await page.waitForSelector('body.mobile-layer');
 for(let n=1;n<=3;n++) { await page.evaluate(async ({n,text})=>{window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:`file:/tmp/decision-proposal-${n}.md`,itemType:'markdown',title:`proposal ${n}`,content:text,pinned:true,absPath:`/tmp/decision-proposal-${n}.md`}})); (await import('/js/mobile/navController.js')).dockTo('canvas');},{n,text}); await settle(page); }
 await page.waitForSelector('.mf-cv-host .canvas-md-scroll');
 const measure = () => page.evaluate(()=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll'), control=document.querySelector('.mf-cv-host .canvas-find-toggle'), el=sc.querySelector('[data-occlusion-probe]'); const sr=sc.getBoundingClientRect(), cr=control.getBoundingClientRect(), r=el?.getBoundingClientRect(); const x=r && Math.max(cr.left+10,Math.min(cr.right-10,r.left+10)), y=r && r.top+12; const hit=r && document.elementFromPoint(x,y); return {scroller:sr.toJSON(),control:cr.toJSON(),target:r?.toJSON(),x,y,scrollTop:sc.scrollTop,hit:hit?.outerHTML.slice(0,180),hitTarget:!!el&&(hit===el||el.contains(hit)),clear:sr.bottom<=cr.top};});
 // Restore old geometry as a negative control. Pick the exact same actual paragraph.
 await page.evaluate(()=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll');sc.style.setProperty('bottom','0px','important');sc.style.setProperty('padding-bottom','76px','important');sc.scrollTop=0; const el=[...sc.querySelectorAll('li')].find(e=>e.textContent.includes('验收须覆盖只读映射')); if(!el) throw Error('expected real paragraph absent');el.dataset.occlusionProbe='true';});
 await settle(page); const before=await measure(); assert.equal(before.clear,false,'negative control must fail clearance');
 // Hit a point within the overlap, not necessarily the first line of this multiline paragraph.
 const covered=await page.evaluate(()=>{const el=document.querySelector('[data-occlusion-probe]'),cr=document.querySelector('.mf-cv-host .canvas-find-toggle').getBoundingClientRect(),r=el.getBoundingClientRect(),x=cr.left+38,y=cr.top+4,hit=document.elementFromPoint(x,y);return {x,y,target:r.toJSON(),hit:hit?.outerHTML.slice(0,180),hitButton:!!hit?.closest('.canvas-find-toggle')};});
 assert.ok(covered.target.bottom>covered.y && covered.hitButton,'negative control hits the overlay on the paragraph');
 await page.screenshot({path:`${out}/before.png`,animations:'disabled'});
 await page.evaluate(()=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll');sc.style.removeProperty('bottom');sc.style.removeProperty('padding-bottom');const el=sc.querySelector('[data-occlusion-probe]');sc.scrollTop += el.getBoundingClientRect().top - 200;});
 await settle(page); const after=await measure(); assert.ok(after.clear,'viewport ends above controls'); assert.ok(after.hitTarget,'same paragraph becomes hittable');
 await page.screenshot({path:`${out}/after.png`,animations:'disabled'});
 // Probe top, middle, bottom viewport geometry and the ask/neighbour control rectangles.
 const matrix=[];
 for(const where of ['top','middle','bottom']) { const row=await page.evaluate(where=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll');sc.scrollTop=where==='top'?0:where==='middle'?(sc.scrollHeight-sc.clientHeight)/2:sc.scrollHeight;const controls=[...document.querySelectorAll('.mf-cv-host .canvas-find-toggle,.mf-cv-host .canvas-ref-select,.ask-fab,.mf-dots')].filter(e=>e.getClientRects().length&&getComputedStyle(e).display!=='none').map(e=>({name:e.className,rect:e.getBoundingClientRect().toJSON()}));return {where,scrollTop:sc.scrollTop,scroller:sc.getBoundingClientRect().toJSON(),controls};},where); const ask=row.controls.find(e=>e.name.includes('ask-fab')&&!e.name.includes('note-fab')); for(const other of row.controls.filter(e=>e!==ask)){const a=ask.rect,b=other.rect;assert.ok(a.right<=b.left||b.right<=a.left||a.bottom<=b.top||b.bottom<=a.top,`ask overlaps ${other.name} at ${where}`);} assert.ok(row.scroller.bottom<=Math.min(...row.controls.map(e=>e.rect.top)));matrix.push(row); }
 const before64=readFileSync(`${out}/before.png`).toString('base64'),after64=readFileSync(`${out}/after.png`).toString('base64');const compare=await context.newPage();await compare.setViewportSize({width:750,height:812});await compare.setContent(`<style>body{margin:0;display:flex}img{width:375px;height:812px}</style><img src="data:image/png;base64,${before64}"><img src="data:image/png;base64,${after64}">`);await compare.screenshot({path:`${out}/comparison.png`});
 writeFileSync(`${out}/geometry.json`,JSON.stringify({before,covered,after,matrix},null,2)); console.log(JSON.stringify({before,covered,after,matrix},null,2));
} finally {await browser.close();}
