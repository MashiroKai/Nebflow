#!/usr/bin/env node
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { resolve, extname } from 'node:path';
import assert from 'node:assert/strict';
const root = resolve(process.env.WEB_ROOT || 'src/main/resources/web');
const server = createServer((req,res) => {
  const path = new URL(req.url,'http://localhost').pathname;
  if (path === '/fixture') { res.setHeader('content-type','text/html'); res.end(`<!doctype html><meta charset="utf-8"><link rel="stylesheet" href="/css/base.css"><link rel="stylesheet" href="/css/design-system.css"><link rel="stylesheet" href="/css/chat.css"><link rel="stylesheet" href="/css/ask.css"><body><script type="module">
import {createAskDialog} from '/js/askDialog.js';
const s={sid:'ask-fixture',msgs:[],busy:true};
const d=createAskDialog({tt:(zh)=>zh,placeholder:'提问',panelClass:'ask-dock',activeSession:()=>s,workline:true});
document.body.append(d.panel); d.panel.classList.remove('closed'); d.isOpen=()=>true; d.appendTyping(); window.fixture={d,s};
</script>`); return; }
  try { const f=resolve(root,'.'+path); if(!f.startsWith(root+'/')) throw 0; res.setHeader('content-type',({'.js':'text/javascript','.css':'text/css','.html':'text/html'}[extname(f)]||'application/octet-stream')); res.end(readFileSync(f)); } catch { res.statusCode=404;res.end(); }
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch();
try {
 for(const width of [1280,390]) for(const theme of ['light','dark']) {
  const page=await browser.newPage({viewport:{width,height:850},colorScheme:theme});
  await page.goto(`http://127.0.0.1:${server.address().port}/fixture`);
  await page.waitForFunction(()=>window.fixture);
  await page.evaluate(()=>fixture.d.processWorkline(fixture.s,{type:'thinkingDelta',sessionId:'ask-fixture',delta:'正在分析文件中的关键结论以及相关上下文'}));
  await page.locator('.nf-wl-think').waitFor({state:'attached',timeout:2000});
  const style = await page.locator('.nf-wl-think').evaluate(e => ({whiteSpace:getComputedStyle(e).whiteSpace,height:e.getBoundingClientRect().height}));
  assert.equal(style.whiteSpace,'nowrap',`thinking must remain single line at ${width}`);
  assert.ok(style.height<=19,`thinking height ${style.height}`);
  const count=await page.locator('.nf-workline').count(); assert.equal(count,1);
  await page.screenshot({path:`${process.env.SHOTS_DIR}/${width}-${theme}.png`});
  await page.evaluate(()=>fixture.d.processWorkline(fixture.s,{type:'toolStart',sessionId:'ask-fixture',label:'Read'}));
  await page.locator('.nf-wl-tool').waitFor();
  assert.equal(await page.locator('.nf-workline').count(),1);
  await page.evaluate(()=>{fixture.s.msgs.push({role:'user',text:'问题'});fixture.d.render();});
  await page.locator('.ask-process-details summary').click();
  assert.ok((await page.locator('.ask-process-details').innerText()).includes('正在分析文件'));
  console.log(`PASS ${width} ${theme}: single line, shared workline, expandable process`);
  await page.close();
 }
} finally { await browser.close(); server.close(); }
