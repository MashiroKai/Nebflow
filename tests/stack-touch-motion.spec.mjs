import { test, expect } from '@playwright/test';
import { spawn, execFileSync } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = process.env.STACK_EVIDENCE || '/private/tmp/stand-stack-evidence';
let server, base;
test.beforeAll(async () => {
  mkdirSync(out, { recursive: true });
  const probe = createServer(); await new Promise(r => probe.listen(0, '127.0.0.1', r));
  const port = probe.address().port; await new Promise(r => probe.close(r));
  base = `http://127.0.0.1:${port}`;
  server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', root], { stdio: 'ignore' });
  await expect.poll(async () => { try { return (await fetch(base + '/src/main/resources/web/index.html')).status; } catch { return 0; } }).toBe(200);
});
test.afterAll(() => server?.kill());
async function touch(page, x, y, dx, dy) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] });
  for (let i = 1; i <= 5; i++) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx*i/5, y: y + dy*i/5, id: 1 }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
}
async function canvasPage(browser) {
  const ctx = await browser.newContext({ hasTouch: true, isMobile: true, viewport: { width: 375, height: 812 }, deviceScaleFactor: 3 });
  await ctx.addInitScript(() => { localStorage.setItem('nebflow_token','t'); localStorage.setItem('neblink_token','t'); localStorage.setItem('nebflow_locale','zh-CN'); localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({v:2,tabs:[]})); localStorage.setItem('neblink_canvas_tabs',JSON.stringify({v:2,tabs:[]})); });
  const page = await ctx.newPage();
  await page.route('**/api/**', r => r.fulfill({json:{}}));
  await page.route(/\/api\/nf-file\?/, async r => { if (!(await ticketGuard(r))) await r.fulfill({contentType:'text/markdown',body:'# mock'}); });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, ws => { ws.onMessage(raw => { let m; try { m=JSON.parse(raw); } catch { return; } if(m.type==='getHistory') ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0})); }); ws.send(JSON.stringify({type:'configData',configured:true,onboarding:'done',models:[],defaults:{}})); ws.send(JSON.stringify({type:'sessionList',sessions:[{id:'sess-1',agentName:'Nebula',title:'T',updatedAt:Date.now()}],activeId:'sess-1',folders:[]})); });
  await page.goto(base+'/src/main/resources/web/index.html');
  await page.waitForSelector('#activity-bar', { state: 'attached' });
  for (const name of ['alpha.md','bravo.md','charlie.md']) {
    await page.evaluate(name => { const absPath='/tmp/canvas-stack-fixture/'+name; window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:'file:'+absPath,itemType:'markdown',title:name,content:'# '+name,absPath,pinned:true}})); }, name);
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:/tmp/canvas-stack-fixture/${name}"]`, { state: 'attached' });
  }
  return {ctx,page};
}
for (const [name,dx,dy,close] of [['up',0,-80,true],['down',0,80,true],['below',0,40,false],['deadzone',5,5,false],['horizontal',-100,0,false],['up47',0,-47,false],['up48',0,-48,true],['up49',0,-49,true],['down47',0,47,false],['down48',0,48,true],['down49',0,49,true]]) {
 test(`mobile canvas ${name}`, async ({browser}) => {
  const {ctx,page}=await canvasPage(browser);
  await page.screenshot({path:`${out}/canvas-${name}-setup.png`});
  await page.getByRole('tab', { name: '看板' }).click();
  const stack=page.locator('.mf-cv-stack'); await expect(stack).toBeVisible(); await stack.click();
  const panel=page.locator('.mf-deck-card.on'); await expect(panel).toBeVisible(); const box=await panel.boundingBox(); box.x = Math.round(box.x); box.y = Math.round(box.y);
  const before=await page.locator('.canvas-tab-pane').evaluateAll(els=>els.map(e=>e.dataset.tabId));
  const activeBefore=await page.locator('.mf-deck-card.on').getAttribute('data-tab-id');
  await touch(page,box.x+box.width/2,box.y+30,dx,dy);
  if(close) await expect.poll(async()=>page.locator('.canvas-tab-pane').count(),{timeout:10000}).toBe(before.length-1);
  else await expect(page.locator('.canvas-tab-pane')).toHaveCount(before.length);
  if (name === 'horizontal') await expect.poll(() => page.locator('.mf-deck-card.on').getAttribute('data-tab-id')).not.toBe(activeBefore);
  const after=await page.locator('.canvas-tab-pane').evaluateAll(els=>els.map(e=>e.dataset.tabId));
  if (close) expect(after).not.toContain(activeBefore);
  console.log(JSON.stringify({case:name,activeBefore,before,after})); await page.screenshot({path:`${out}/canvas-${name}-after.png`}); await ctx.close();
 });
}
for (const variant of ['before','after']) test(`motion ${variant}`, async ({browser}) => {
 const ctx=await browser.newContext({viewport:{width:1000,height:800},recordVideo:{dir:out}}); const page=await ctx.newPage();
 if(variant==='before') { const old=execFileSync('git',['show','0f9f5aab762f601d382e8121fc7c994f366d4c06:src/main/resources/web/js/popArtifacts.js'],{cwd:root,encoding:'utf8'}); await page.route('**/src/main/resources/web/js/popArtifacts.js',r=>r.fulfill({contentType:'text/javascript',body:old})); }
 await page.goto(base+'/tests/fixtures/pop-artifacts/harness.html'); await page.waitForFunction(()=>window.__ready===true);
 await page.evaluate(()=>{document.querySelector('#popup-chat').style.display='none';window.__popStackMixedRatios();});
 const view=page.locator('#chat .pop-stack-view'); await expect(view.locator('.media-layer')).toHaveCount(3); await view.focus();
 await page.screenshot({path:`${out}/${variant}-start.png`});
 await page.evaluate(()=>{ window.__frameTimes=[]; let start; function frame(t){if(!start)start=t;window.__frameTimes.push(t);if(t-start<1000)requestAnimationFrame(frame);} requestAnimationFrame(frame); });
 const box=await view.boundingBox();
 await page.mouse.move(box.x+box.width/2,box.y+box.height/2); await page.mouse.down(); await page.mouse.move(box.x+box.width/2-75,box.y+box.height/2,{steps:5}); await page.mouse.up();
 await page.waitForFunction(()=>window.__frameTimes.length>2 && window.__frameTimes.at(-1)-window.__frameTimes[0]>=1000);
 const times=await page.evaluate(()=>window.__frameTimes); const deltas=times.slice(1).map((t,i)=>t-times[i]); const sorted=[...deltas].sort((a,b)=>a-b); const stat={frames:deltas.length,p50:sorted[Math.floor(sorted.length*.5)],p95:sorted[Math.floor(sorted.length*.95)],max:sorted.at(-1),over25:deltas.filter(d=>d>25).length};
 writeFileSync(`${out}/${variant}-frames.csv`,'timestamp_ms,interval_ms\n'+times.map((t,i)=>`${t},${i?deltas[i-1]:''}`).join('\n')+'\n'); writeFileSync(`${out}/${variant}-stats.json`,JSON.stringify(stat,null,2)); console.log(variant,JSON.stringify(stat)); await page.screenshot({path:`${out}/${variant}-end.png`}); const video=page.video(); await ctx.close(); console.log('video',variant,await video.path());
});
