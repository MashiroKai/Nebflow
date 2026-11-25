const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const root = path.resolve(__dirname, '..');
const summary = '文'.repeat(100);
let provideSummary = true;
const graph = {projects:[{name:'test',device:'self',vault:'/vault'}],nodes:[{id:'test:target.md',project:'test',device:'self',rel:'target.md',title:'目标文档'},{id:'test:source.md',project:'test',device:'self',rel:'source.md',title:'来源文档'}],edges:[{from:'test:source.md',to:'test:target.md',kind:'link',line:1}]};
(async () => {
 const srv = http.createServer((req,res) => {
  const url = new URL(req.url,'http://localhost');
  if (url.pathname === '/api/wiki/doc') { res.setHeader('content-type','application/json'); res.end(JSON.stringify({available:true,path:'/vault/target.md',title:'目标文档',summary:provideSummary ? summary : '',status:'draft'})); return; }
  let file = path.join(root, decodeURIComponent(url.pathname));
  if (!file.startsWith(root + path.sep)) { res.writeHead(403).end(); return; }
  try { res.setHeader('content-type',file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':'text/javascript');res.end(fs.readFileSync(file)); } catch {res.writeHead(404).end();}
 });
 await new Promise(r=>srv.listen(0,'127.0.0.1',r));
 const port=srv.address().port; assert.notEqual(port,6138);
 const browser=await chromium.launch({headless:true});
 try {
  for (let i=1;i<=20;i++) {
   const page=await browser.newPage();
   await page.goto(`http://127.0.0.1:${port}/tests/fixtures/md-backlinks/harness.html`);
   await page.waitForFunction(()=>window.__ready);
   await page.evaluate(async g=>{window.__setGraph(g); await window.__openDoc('# 来源\n\n[[target]]','/vault/source.md','source.md'); window.__opens=[];window.addEventListener('workspace-open-item',e=>window.__opens.push(e.detail));},graph);
   const link=page.locator('.canvas-md-viewer a.nf-wikilink').filter({hasText:'target'});
   await link.click();
   const dialog=page.getByRole('dialog',{name:'文档词条'});
   await dialog.waitFor({timeout:2000});
   assert.equal(await page.evaluate(()=>window.__opens.length),0);
   await page.waitForFunction(expected => document.querySelector('.document-term-summary')?.textContent === expected, summary);
   assert.equal(await dialog.locator('.document-term-summary').textContent(),summary);
   await page.keyboard.press('Tab'); assert.equal(await dialog.getByRole('button',{name:'打开原文档'}).evaluate(e=>document.activeElement===e),true);
   await page.keyboard.press('Escape'); assert.equal(await link.evaluate(e=>document.activeElement===e),true);
   await link.click(); await dialog.waitFor(); await dialog.getByRole('button',{name:'打开原文档'}).click();
   assert.equal(await page.evaluate(()=>window.__opens.length),1);
   await page.evaluate(async()=>{const m=await import('/src/main/resources/web/js/reference.js'); const ref={refType:'document',source:{path:'test/.stand/docs/target.md'},display:{label:'目标文档'}};document.querySelector('.canvas-md-viewer').append(m.renderRefBlock(ref,{mode:'message'}));});
   await page.locator('.att-ref-card.file-lang').click(); await dialog.waitFor();
   await page.waitForFunction(expected => document.querySelector('.document-term-summary')?.textContent === expected, summary);
   assert.equal(await dialog.locator('.document-term-summary').textContent(),summary);
   await dialog.getByRole('button',{name:'打开原文档'}).click();
   assert.equal(await page.evaluate(()=>window.__opens.at(-1).absPath),'/vault/target.md');
   await page.locator('.att-ref-card.file-lang').click(); await dialog.waitFor();
   await page.mouse.click(5,5); assert.equal(await dialog.isVisible(),false);
   provideSummary=false;
   await page.locator('.att-ref-card.file-lang').click(); await dialog.waitFor();
   await page.waitForFunction(() => document.querySelector('.document-term-summary')?.textContent.startsWith('此文档暂无提要：'));
   await page.keyboard.press('Escape'); provideSummary=true;
   await page.evaluate(async()=>{await window.__openDoc('# 目标文档','/vault/target.md','target.md');});
   await page.locator('.nf-backlinks summary').click();
   await page.locator('.nf-backlink').click(); await dialog.waitFor();
   await page.waitForFunction(expected => document.querySelector('.document-term-summary')?.textContent === expected, summary);
   await page.keyboard.press('Escape');
   await page.close(); console.log('PASS',i);
  }
 } finally {await browser.close(); await new Promise(r=>srv.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1});
