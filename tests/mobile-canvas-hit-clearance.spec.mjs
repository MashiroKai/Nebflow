// Mobile reader extends under floating controls, while text can scroll clear.
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
 await page.evaluate(()=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll');sc.style.setProperty('bottom','0px','important');sc.style.setProperty('padding-bottom','76px','important');sc.scrollTop=200; const el=[...sc.querySelectorAll('li, p')].filter(e=>e.getBoundingClientRect().height>=20).at(-1); if(!el) throw Error('expected real paragraph absent');el.dataset.occlusionProbe='true';});
 await settle(page); const before=await measure(); assert.equal(before.clear,false,'negative control must fail clearance');
 // Use a visible hit-tested control and scroll the same real paragraph under
 // its center. The dock can cover the find button; do not assume a corner is
 // inside a circular control or that the original paragraph is already there.
 const covered=await page.evaluate(()=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll'),el=sc.querySelector('[data-occlusion-probe]'); const controls=[...document.querySelectorAll('.mf-cv-host .canvas-find-toggle,.ask-fab')]; for(const c of controls){const cr=c.getBoundingClientRect(),x=cr.left+cr.width/2,y=cr.top+cr.height/2; const hit=document.elementFromPoint(x,y);if(!(hit===c||c.contains(hit)))continue;sc.scrollTop+=el.getBoundingClientRect().top-(y-12);const r=el.getBoundingClientRect();if(x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom)return {x,y,target:r.toJSON(),hit:hit.outerHTML.slice(0,180),hitButton:true};}return {hitButton:false};});
 assert.ok(covered.hitButton,'negative control places paragraph under a hittable overlay');
 await page.screenshot({path:`${out}/before.png`,animations:'disabled'});
 await page.evaluate(()=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll');sc.style.removeProperty('bottom');sc.style.removeProperty('padding-bottom');const el=sc.querySelector('[data-occlusion-probe]');sc.scrollTop += el.getBoundingClientRect().top - 200;});
 await settle(page); const after=await measure(); assert.ok(!after.clear,'reader extends underneath floating controls'); assert.ok(after.hitTarget,'same paragraph can scroll clear and become hittable');
 await page.screenshot({path:`${out}/after.png`,animations:'disabled'});
 // Probe top, middle, bottom viewport geometry and the ask/neighbour control rectangles.
 const matrix=[];
 for(const where of ['top','middle','bottom']) { const row=await page.evaluate(where=>{const sc=document.querySelector('.mf-cv-host .canvas-md-scroll');sc.scrollTop=where==='top'?0:where==='middle'?(sc.scrollHeight-sc.clientHeight)/2:sc.scrollHeight;const controls=[...document.querySelectorAll('.mf-cv-host .canvas-find-toggle,.mf-cv-host .canvas-ref-select,.ask-fab,.mf-dots')].filter(e=>e.getClientRects().length&&getComputedStyle(e).display!=='none').map(e=>({name:e.className,rect:e.getBoundingClientRect().toJSON()}));return {where,scrollTop:sc.scrollTop,scroller:sc.getBoundingClientRect().toJSON(),controls};},where); const ask=row.controls.find(e=>e.name.includes('ask-fab')&&!e.name.includes('note-fab')); for(const other of row.controls.filter(e=>e!==ask)){const a=ask.rect,b=other.rect;assert.ok(a.right<=b.left||b.right<=a.left||a.bottom<=b.top||b.bottom<=a.top,`ask overlaps ${other.name} at ${where}`);} assert.ok(row.scroller.bottom>=Math.min(...row.controls.map(e=>e.rect.top)), 'reader extends under floating controls');matrix.push(row); }
 const before64=readFileSync(`${out}/before.png`).toString('base64'),after64=readFileSync(`${out}/after.png`).toString('base64');const compare=await context.newPage();await compare.setViewportSize({width:750,height:812});await compare.setContent(`<style>body{margin:0;display:flex}img{width:375px;height:812px}</style><img src="data:image/png;base64,${before64}"><img src="data:image/png;base64,${after64}">`);await compare.screenshot({path:`${out}/comparison.png`});
 writeFileSync(`${out}/geometry.json`,JSON.stringify({before,covered,after,matrix},null,2)); console.log(JSON.stringify({before,covered,after,matrix},null,2));
} finally {await browser.close();}
