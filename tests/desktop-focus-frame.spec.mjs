import { test, expect } from '@playwright/test';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out=process.env.MOBILE_AUDIT_EVIDENCE;
test('desktop keyboard chat focus frame clears overlays at both scroll ends', async ({page}) => {
  await page.setViewportSize({width:1280,height:800});
  await bootChat(page,{history:historyFixture(40)});
  await page.locator('#input').fill('Keyboard focus probe'); await page.locator('#input').press('Enter');
  await expect(page.locator('#chat')).toBeFocused();
  await page.keyboard.press(']');
  if (!(await page.locator('#chat').evaluate(el=>el===document.activeElement))) await page.keyboard.press(']');
  await expect(page.locator('#chat')).toBeFocused();
  const frame=page.locator('.nf-chat-focus-frame'); await expect(frame).toBeVisible();
  const before=await page.locator('#chat .row').first().boundingBox();
  for(const [name,f] of [['top',0],['bottom',1]]) {
    await page.locator('#chat').evaluate((el,x)=>{el.style.scrollBehavior='auto';el.scrollTop=(el.scrollHeight-el.clientHeight)*x},f);
    const g=await page.evaluate(()=>{const rect=s=>{const r=document.querySelector(s).getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right}}; const r=rect('.nf-chat-focus-frame'); const points=[[r.left+r.right,r.top*2],[r.left+r.right,r.bottom*2-2],[r.left*2+2,r.top+r.bottom],[r.right*2-2,r.top+r.bottom]].map(([x,y])=>{const n=document.elementFromPoint(x/2,y/2);return n?.closest('#header,#input-area,#top-overlays')?.id||null});return {frame:r,header:rect('#header'),input:rect('#input-area'),points};});
    expect(g.frame.top).toBeGreaterThanOrEqual(g.header.bottom); expect(g.frame.bottom).toBeLessThanOrEqual(g.input.top); expect(g.frame.left).toBeGreaterThanOrEqual(0); expect(g.frame.right).toBeLessThanOrEqual(1280); expect(g.frame.bottom).toBeGreaterThan(g.frame.top); expect(g.points).toEqual([null,null,null,null]);
    if(out){mkdirSync(out,{recursive:true});await page.screenshot({path:join(out,`desktop-focus-after-${name}.png`),animations:'disabled'});}
  }
  await page.locator('#chat').evaluate(el=>{el.scrollTop=0}); const after=await page.locator('#chat .row').first().boundingBox(); expect(after.x).toBe(before.x);
  const coordinates=()=>page.locator('#chat .row').evaluateAll(rows=>[rows[0],rows[Math.floor(rows.length/2)],rows[rows.length-1]].map(el=>{const r=el.getBoundingClientRect();return [r.x,r.y,r.width,r.height]}));
  const focused=await coordinates();
  const area=await frame.boundingBox();
  await page.keyboard.press('Space'); await expect(page.locator('#input')).toBeFocused(); await expect(frame).toBeHidden(); expect(await coordinates()).toEqual(focused);
  await page.locator('#input').fill('resize probe'); await page.locator('#input').press('Enter'); await expect(frame).toBeVisible();
  await page.setViewportSize({width:1280,height:680}); await expect.poll(async()=>page.evaluate(()=>{const f=document.querySelector('.nf-chat-focus-frame').getBoundingClientRect(),h=document.querySelector('#header').getBoundingClientRect(),i=document.querySelector('#input-area').getBoundingClientRect();return f.top>=h.bottom && f.bottom<=i.top && f.height>0})).toBe(true);
  await page.keyboard.press('Space'); await expect(frame).toBeHidden();
  await page.mouse.click(area.x+20,(area.y+area.height/2)); await expect(frame).toBeHidden();
});

test('baseline and fixed fixture geometry evidence', async ({page})=>{
  const phase=process.env.FOCUS_PHASE; if(!phase) test.skip();
  const mobile=process.env.FOCUS_MOBILE==='1'; await page.setViewportSize(mobile?{width:375,height:812}:{width:1280,height:800});
  await bootChat(page,{history:historyFixture(40)}); await page.locator('#input').fill('Keyboard focus probe'); await page.locator('#input').press('Enter'); await expect(page.locator('#chat')).toBeFocused();
  const results={};
  for(const [name,f] of [['top',0],['bottom',1]]) {
    await page.locator('#chat').evaluate((el,x)=>{el.style.scrollBehavior='auto';el.scrollTop=(el.scrollHeight-el.clientHeight)*x},f); await page.waitForTimeout(400);
    results[name]=await page.evaluate(()=>{const rect=el=>{const r=el.getBoundingClientRect();return [r.x,r.y,r.width,r.height]};const rows=[...document.querySelectorAll('#chat .row')];return {rows:[rows[0],rows[Math.floor(rows.length/2)],rows[rows.length-1]].map(rect),chat:rect(document.querySelector('#chat')),header:rect(document.querySelector('#header')),input:rect(document.querySelector('#input-area'))}});
    if(out){mkdirSync(out,{recursive:true});await page.screenshot({path:join(out,`compare-${mobile?'mobile':'desktop'}-${phase}-${name}.png`),animations:'disabled'});}
  }
  if(out) writeFileSync(join(out,`compare-${mobile?'mobile':'desktop'}-${phase}.json`),JSON.stringify(results,null,2));
});

test('mobile does not display desktop frame', async ({page})=>{
  await page.setViewportSize({width:375,height:812}); await bootChat(page,{history:historyFixture(20)});
  await page.locator('#input').fill('mobile guard'); await page.locator('#input').press('Enter');
  await expect(page.locator('.nf-chat-focus-frame')).toBeHidden();
});
