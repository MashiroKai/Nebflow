import assert from 'node:assert/strict';
import { createSession, loadPlaywright } from '../scripts/lib/visual-harness.mjs';
const expected = {
 light: {background:'rgba(255, 255, 255, 0.97)',border:'rgba(255, 255, 255, 0.35)',radius:'8px',shadow:'rgba(255, 255, 255, 0.25) 0px 1px 0px 0px inset, rgba(0, 0, 0, 0.04) 0px 2px 8px 0px, rgba(0, 0, 0, 0.06) 0px 8px 24px 0px',color:'rgb(58, 61, 69)',selected:'rgba(91, 127, 191, 0.15)'},
 dark: {background:'rgba(24, 28, 38, 0.94)',border:'rgba(255, 255, 255, 0.06)',radius:'8px',shadow:'rgba(255, 255, 255, 0.04) 0px 1px 0px 0px inset, rgba(0, 0, 0, 0.2) 0px 2px 8px 0px, rgba(0, 0, 0, 0.35) 0px 8px 24px 0px',color:'rgb(200, 202, 208)',selected:'rgba(91, 127, 191, 0.15)'}
};
const { mod } = loadPlaywright();
const browser = await mod.chromium.launch({ headless: true });
try {
 for (const theme of ['light', 'dark']) {
  const s = await createSession(browser, { width:1280, height:844, colorScheme:theme, baseURL:'https://stand.test/', apiStubs:{
   '/api/projects':{body:{projects:[{name:'Example',workspace:'/example/workspace',description:'Project workspace'}]}},
   '/api/project-open-apps':{body:{apps:[{id:'editor',label:'Editor',icon:'/favicon-32.png'}]}},
   '/api/projects/Example/open-in-app':{body:{ok:true}}
  }});
  await s.boot(); const {page}=s;
  await page.evaluate(async theme=>{document.documentElement.dataset.theme=theme; (await import('/js/projectTab.js')).openProjectsTab();},theme);
  const button=page.locator('.project-card [data-open-workspace]').first(); await button.waitFor({state:'visible'});
  for(let i=0;i<20;i++) {
   await button.click(); const menu=page.locator('.project-open-menu'); await menu.waitFor({state:'visible'});
   const items=menu.locator('button'); const selected=items.first(); const other=items.nth(1);
   const geometry=await page.evaluate(()=>{const a=document.querySelector('.project-open-btn').getBoundingClientRect(),b=document.querySelector('.project-open-menu').getBoundingClientRect();return {ar:a.right,ab:a.bottom,br:b.right,bt:b.top};});
   assert.ok(Math.abs(geometry.ar-geometry.br)<1 && Math.abs(geometry.bt-geometry.ab-4)<1, JSON.stringify(geometry));
   const styles=await menu.evaluate(el=>{const c=getComputedStyle(el),s=getComputedStyle(el.querySelector('button')); return {background:c.backgroundColor,border:c.borderColor,radius:c.borderRadius,shadow:c.boxShadow,color:s.color,selected:s.backgroundColor};});
   if(i===0) console.log('RECORDED',theme,JSON.stringify(styles));
   assert.deepEqual(styles,expected[theme],`${theme} computed menu styles`);
   await other.hover(); const hover=await other.evaluate(el=>getComputedStyle(el).backgroundColor); assert.equal(hover,expected[theme].selected,`${theme} hover color`);
   await page.keyboard.press('ArrowDown'); const outline=await other.evaluate(el=>({style:getComputedStyle(el).outlineStyle,width:getComputedStyle(el).outlineWidth,color:getComputedStyle(el).outlineColor})); assert.deepEqual(outline,{style:'solid',width:'2px',color:'rgb(91, 127, 191)'});
   await page.keyboard.press('Escape'); assert.equal(await menu.count(),0); assert.ok(await button.evaluate(el=>document.activeElement===el));
   await button.click(); await page.mouse.click(2,2); assert.equal(await menu.count(),0);
   await button.click(); await menu.locator('button').nth(1).click(); await button.locator('img.project-open-current-icon').waitFor({state:'visible'});
   await button.click(); assert.equal(await menu.locator('button').nth(1).getAttribute('aria-checked'),'true'); await page.keyboard.press('Escape');
   // Restore the baseline selected state for the next iteration.
   await button.click(); await menu.locator('button').first().click();
  }
  console.log(`PASS real project dropdown ${theme}: 20 iterations`); await s.close();
 }
} finally {await browser.close();}
