import { test, expect } from '@playwright/test';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';
import { mkdirSync } from 'node:fs';
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
  const area=await frame.boundingBox();
  await page.keyboard.press('Space'); await expect(page.locator('#input')).toBeFocused(); await expect(frame).toBeHidden();
  await page.mouse.click(area.x+20,(area.y+area.height/2)); await expect(frame).toBeHidden();
});

test('mobile does not display desktop frame', async ({page})=>{
  await page.setViewportSize({width:375,height:812}); await bootChat(page,{history:historyFixture(20)});
  await page.locator('#input').fill('mobile guard'); await page.locator('#input').press('Enter');
  await expect(page.locator('.nf-chat-focus-frame')).toBeHidden();
});
