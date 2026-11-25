// Real Markdown viewer, native Chromium touch input; no live backend.
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const dir = dirname(fileURLToPath(import.meta.url));
const content = readFileSync(join(dir, 'fixtures/mobile-code-scroll.md'), 'utf8');
const out = process.env.CODE_SCROLL_SHOTS;
if (out) mkdirSync(out, { recursive: true });
const { mod } = loadPlaywright();
const structural = process.env.CODE_SCROLL_WEBKIT === '1';
const browser = await mod[structural ? 'webkit' : 'chromium'].launch({ headless: true });
let failed = 0;
try {
  for (const [width,height] of (structural ? [[390,844]] : [[390,844],[375,812]])) for (const theme of (structural ? ['light'] : ['light','dark'])) {
    const context = await browser.newContext({ hasTouch: true, deviceScaleFactor: 2 });
    try {
      const session = await createSession({newPage: opts => context.newPage(opts)}, { width, height, colorScheme: theme, baseURL:'https://stand.test/' });
      const {page} = session;
      await page.setViewportSize({width,height});
      await page.emulateMedia({colorScheme:theme});
      await session.boot();
      await page.waitForSelector('body.mobile-layer');
      await page.evaluate(async content => {
        for (const [n,c] of [[1,content],[2,'# Other document']]) window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:`file:/tmp/mobile-code-${n}.md`,itemType:'markdown',title:`mobile-code-${n}.md`,content:c,pinned:true,absPath:`/tmp/mobile-code-${n}.md`,size:c.length}}));
        const nav=await import('/js/mobile/navController.js'); nav.dockTo('canvas');
        (await import('/js/canvas.js')).setActiveTab(nav.docTabs()[0].id);
      },content);
      const code=page.locator('.mf-cv-host .canvas-md-viewer pre > code.hljs').first();
      await code.waitFor(); await settle(page);
      if (structural) {
        const state=await code.evaluate(el=>{const css=getComputedStyle(el); el.scrollLeft=el.scrollWidth; return {touchAction:css.touchAction, overflowX:css.overflowX, left:el.scrollLeft, width:el.clientWidth, total:el.scrollWidth};});
        const ok=state.touchAction==='pan-x pan-y' && state.overflowX==='auto' && state.left+state.width>=state.total-2 && !session.errors.length;
        console.log(`${ok?'PASS':'FAIL'} WebKit structural ${JSON.stringify(state)} errors=${JSON.stringify(session.errors)}`);
        if(out) await page.screenshot({path:join(out,'webkit-code-structural.png')});
        if(!ok) failed++;
        continue;
      }
      const box=await code.boundingBox(); const client=await context.newCDPSession(page);
      const before=await code.evaluate(el=>({left:el.scrollLeft, max:el.scrollWidth-el.clientWidth}));
      const x0=Math.min(width-35,box.x+box.width-20), x1=Math.max(30,box.x+20), y=box.y+box.height/2;
      await client.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:x0,y,id:10}]});
      for(let i=1;i<=12;i++){await client.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x0+(x1-x0)*i/12,y,id:10}]}); await page.waitForTimeout(16);}
      await client.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]}); await page.waitForTimeout(350);
      const after=await code.evaluate(el=>el.scrollLeft);
      for(let attempt=0;attempt<12;attempt++) {
        const previous=await code.evaluate(el=>el.scrollLeft);
        await client.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:x0,y,id:10}]});
        for(let i=1;i<=12;i++){await client.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x0+(x1-x0)*i/12,y,id:10}]}); await page.waitForTimeout(16);}
        await client.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]}); await page.waitForTimeout(350);
        if(await code.evaluate(el=>el.scrollLeft)<=previous) break;
      }
      const end=await code.evaluate(el=>({left:el.scrollLeft,width:el.clientWidth,total:el.scrollWidth}));
      const ok=before.max>100 && after-before.left>100 && end.left+end.width>=end.total-2 && !session.errors.length;
      console.log(`${ok?'PASS':'FAIL'} ${width}x${height} ${theme} scrollLeft=${before.left}->${after} end=${end.left}+${end.width}/${end.total} errors=${JSON.stringify(session.errors)}`);
      if (out) await page.screenshot({path:join(out,`after-${width}-${theme}.png`)});
      if(!ok) failed++;
    } finally { await context.close(); }
  }
} finally { await browser.close(); }
assert.equal(failed,0,`${failed} mobile code scroll cases failed`);
