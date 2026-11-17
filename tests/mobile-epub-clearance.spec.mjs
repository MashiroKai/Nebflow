// Exercise the real EPUB parser and reader chrome in a route-backed browser.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { loadPlaywright, createSession, settle } from '../scripts/lib/visual-harness.mjs';
const py = `import io,zipfile,base64
b=io.BytesIO()
with zipfile.ZipFile(b,'w',zipfile.ZIP_STORED) as z:
 z.writestr('META-INF/container.xml','<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>')
 z.writestr('book.opf','<package><manifest><item id="ch" href="ch.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch"/></spine></package>')
 z.writestr('ch.xhtml','<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body>'+''.join('<p>Real EPUB chapter line %d.</p>'%i for i in range(100))+'</body></html>')
print(base64.b64encode(b.getvalue()).decode())`;
const fixture=execFileSync('python3',['-c',py],{encoding:'utf8'}).trim();
const {mod}=loadPlaywright();const browser=await mod.chromium.launch({headless:true});
try {
 const context=await browser.newContext({viewport:{width:375,height:812},hasTouch:true});
 const {page,boot}=await createSession({newPage:()=>context.newPage()},{width:375,height:812,baseURL:'https://stand.test/'});
 await boot();await page.waitForSelector('body.mobile-layer');
 await page.evaluate(async fixture=>{window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:'file:/tmp/epub-fixture.md',itemType:'markdown',title:'EPUB geometry',content:'# Reader',pinned:true,absPath:'/tmp/epub-fixture.md'}}));(await import('/js/mobile/navController.js')).dockTo('canvas');const pane=document.querySelector('.mf-cv-host .canvas-tab-pane');const bytes=Uint8Array.from(atob(fixture),c=>c.charCodeAt(0));await (await import('/js/viewers/epub.js')).renderEpubBuffer(pane,bytes.buffer,'Fixture',{format:'epub',bookRef:'/tmp/fixture.epub'});},fixture);
 await page.waitForSelector('.epub-reader-content p'); await settle(page);
 const geo=await page.evaluate(()=>{const v=document.querySelector('.epub-reader-viewport'), r=v.getBoundingClientRect(); const controls=[...document.querySelectorAll('.ask-fab')].filter(e=>e.getClientRects().length).map(e=>({name:e.className,rect:e.getBoundingClientRect().toJSON()}));return {viewport:r.toJSON(),controls,marginBottom:getComputedStyle(v).marginBottom,paragraphs:v.querySelectorAll('p').length,scrollHeight:v.scrollHeight,clientHeight:v.clientHeight};});
 console.log(JSON.stringify(geo,null,2)); assert.ok(geo.paragraphs===100);assert.ok(geo.controls.length>0);assert.ok(geo.viewport.bottom<=Math.min(...geo.controls.map(c=>c.rect.top)),'EPUB viewport clears floating buttons');assert.ok(geo.scrollHeight>geo.clientHeight);await page.screenshot({path:'/private/tmp/stand-overlay-diagnostics/epub-mobile.png',animations:'disabled'});
} finally {await browser.close();}
