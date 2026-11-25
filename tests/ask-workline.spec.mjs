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
const d=createAskDialog({tt:(zh)=>zh,placeholder:'提问',panelClass:'ask-dock',activeSession:()=>s,workline:true,frameKind:m=>({textDelta:'delta',done:'done',error:'error',interrupted:'interrupted'}[m.type])});
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
  await page.screenshot({path:`${process.env.SHOTS_DIR}/after-working-${width}-${theme}.png`});
  await page.evaluate(()=>fixture.d.processWorkline(fixture.s,{type:'toolStart',sessionId:'ask-fixture',label:'Read'}));
  await page.locator('.nf-wl-tool').waitFor();
  assert.equal(await page.locator('.nf-workline').count(),1);
  await page.evaluate(()=>{fixture.s.msgs.push({role:'user',text:'问题'});fixture.d.render();fixture.d.consume({type:'textDelta',delta:'第一'},fixture.s);});
  assert.equal(await page.locator('.ai').count(),0,'partial text must not appear');
  assert.equal(await page.locator('.nf-workline').count(),1,'workline remains during text');
  await page.evaluate(()=>{fixture.d.processWorkline(fixture.s,{type:'toolCallDetected',name:'Read'});fixture.d.consume({type:'textDelta',delta:'条'},fixture.s);});
  assert.equal(await page.locator('.ai').count(),0,'tool detection is not a boundary');
  await page.evaluate(()=>fixture.d.processWorkline(fixture.s,{type:'toolStart',label:'Read'}));
  assert.equal(await page.locator('.ai').count(),1,'tool boundary commits one message');
  assert.equal(await page.locator('.ai').first().innerText(),'第一条');
  await page.evaluate(()=>fixture.d.consume({type:'textDelta',delta:'第二条'},fixture.s));
  assert.equal(await page.locator('.ai').count(),1,'second partial stays hidden');
  await page.evaluate(()=>fixture.d.consume({type:'done'},fixture.s));
  assert.deepEqual(await page.locator('.ai').allInnerTexts(),['第一条','第二条']);
  assert.equal(await page.locator('.ask-process-details').count(),0,'no expandable residue');
  await page.screenshot({path:`${process.env.SHOTS_DIR}/after-done-${width}-${theme}.png`});
  await page.evaluate(()=>{fixture.d.armTurn(fixture.s);fixture.d.consume({type:'textDelta',delta:'第三条'},fixture.s);fixture.d.processWorkline(fixture.s,{type:'roundComplete'});fixture.d.consume({type:'textDelta',delta:'中止文本'},fixture.s);fixture.d.consume({type:'interrupted'},fixture.s);});
  assert.deepEqual(await page.locator('.ai').allInnerTexts(),['第一条','第二条','第三条','中止文本']);
  assert.equal(await page.evaluate(()=>fixture.s.askBufferedText),'','interruption clears buffer');
  await page.evaluate(()=>{fixture.d.armTurn(fixture.s);fixture.d.consume({type:'textDelta',delta:'错误前文本'},fixture.s);fixture.d.consume({type:'error',message:'failure'},fixture.s);});
  assert.equal(await page.evaluate(()=>fixture.s.askBufferedText),'','error clears buffer');
  assert.ok((await page.locator('.ai').allInnerTexts()).includes('错误前文本'));
  await page.evaluate(()=>{fixture.d.armTurn(fixture.s);fixture.d.consume({type:'textDelta',delta:'   '},fixture.s);fixture.d.consume({type:'done'},fixture.s);});
  assert.equal(await page.locator('.ai').count(),6,'blank text does not create a message');
  const frames = JSON.parse(readFileSync(resolve('tests/ask-display-frames.json'),'utf8'));
  await page.evaluate((events)=>{fixture.s.msgs=[];fixture.d.armTurn(fixture.s);fixture.d.render();for(const e of events){if(e.type==='textDelta'||e.type==='done')fixture.d.consume(e,fixture.s);else fixture.d.processWorkline(fixture.s,e);}},frames);
  assert.deepEqual(await page.locator('.ai').allInnerTexts(),['前后','第二条','第三条长文本：abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789','终结'],'shared frame sequence must preserve boundaries');
  console.log(`PASS ${width} ${theme}: single line, complete messages, no process residue`);
  await page.close();
 }
} finally { await browser.close(); server.close(); }
