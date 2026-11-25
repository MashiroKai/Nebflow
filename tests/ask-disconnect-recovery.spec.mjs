#!/usr/bin/env node
// Real browser + stateful WebSocket stub: no gateway, no real model or external services.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { extname, join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { installTicketMock } from './nf-ticket-mock.mjs';
const root=resolve(fileURLToPath(new URL('..',import.meta.url)),'src/main/resources/web');
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.json':'application/json'};
const server=createServer((req,res)=>{let p=decodeURIComponent(new URL(req.url,'http://localhost').pathname);if(p==='/')p='/index.html';let f=join(root,p);try{const body=readFileSync(f);res.writeHead(200,{'Content-Type':mime[extname(f)]||'application/octet-stream'});res.end(body)}catch{res.writeHead(404);res.end()}});
await new Promise(ok=>server.listen(0,'127.0.0.1',ok));let browser,failed=0;
try{
 browser=await chromium.launch();const page=await browser.newPage();await page.clock.install();const events=[];let sock;let wake=[];
 const record=e=>{events.push(e);for(const f of wake.splice(0))f()};
 async function waitEvent(pred){for(;;){const e=events.find(pred);if(e)return e;await new Promise((ok,no)=>{const t=setTimeout(()=>no(Error('WS event timeout')),10000);wake.push(()=>{clearTimeout(t);ok()})})}}
 await page.addInitScript(()=>{localStorage.setItem('nebflow_token','t');localStorage.setItem('neblink_token','t');localStorage.setItem('nebflow_locale','zh-CN');localStorage.setItem('nebflow_canvas_tabs',JSON.stringify({v:2,tabs:[]}))});
 await page.route('**/api/**',r=>r.fulfill({json:{}}));await installTicketMock(page);
 await page.routeWebSocket(/\/ws/,s=>{sock=s;record({type:'connected'});s.onMessage(raw=>{let m;try{m=JSON.parse(raw)}catch{return}record(m);if(m.type==='askOpen'){const f=m.file.absPath;const sid='ask-'+createHash('sha256').update(f).digest('hex').slice(0,8);s.send(JSON.stringify({type:'askState',sessionId:sid,file:f,fileName:m.file.name,messages:[]}))}if(m.type==='getHistory')s.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0}))});s.send(JSON.stringify({type:'configData',configured:true,onboarding:'done',models:[],defaults:{}}));s.send(JSON.stringify({type:'sessionList',sessions:[{id:'sess-1',agentName:'Stand',title:'T'}],activeId:'sess-1',folders:[]}))});
 await page.goto('http://127.0.0.1:'+server.address().port+'/index.html');await page.waitForSelector('#activity-bar',{state:'attached'});const file='/tmp/ask-recovery/test.md';await page.evaluate(f=>window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:'file:'+f,itemType:'markdown',title:'test.md',content:'# test',absPath:f,pinned:true}})),file);await page.locator('.canvas-tab-pane[data-tab-id^="file:"]').first().waitFor({state:'visible'});await page.click('.ask-fab');await waitEvent(e=>e.type==='askOpen');await page.fill('.ask-dock textarea','test');await page.click('.ask-dock .ask-send');const q=await waitEvent(e=>e.type==='askQuestion');
 sock.send(JSON.stringify({type:'textDelta',sessionId:q.sessionId,delta:'progress marker'}));await page.getByText('progress marker').first().waitFor();
 async function test(name,fn){try{await fn();console.log('PASS '+name)}catch(e){failed++;console.error('FAIL '+name+': '+e.message)}}
 await test('帧事实跟踪生成进度',async()=>{const text=await page.locator('.ask-dock .ask-state-line').innerText();assert.match(text,/生成中/)});
 sock.close();await page.waitForFunction(async()=>{const m=await import('/js/state.js');return m.default.connected===false});
 await test('已发送后断连显示连接中断',async()=>{const text=await page.locator('.ask-dock .ask-state-line').innerText();assert.match(text,/连接中断/)});
 const shots=process.env.ASK_SHOTS;if(shots)await page.screenshot({path:shots+'/disconnected.png'});
 await page.clock.fastForward(40000);await page.waitForFunction(async()=>{const m=await import('/js/state.js');return m.default.connected===true});
 await test('重连未知与人工对账且不自动重发提问',async()=>{assert.match(await page.locator('.ask-dock .ask-state-line').innerText(),/状态未知/);await page.locator('.ask-state-retry').click();await waitEvent(e=>e.type==='askOpen'&&events.filter(x=>x.type==='askOpen').length>=2);assert.equal(events.filter(e=>e.type==='askQuestion').length,1)});
 if(shots)await page.screenshot({path:shots+'/retry.png'});
 await page.clock.fastForward(181000);
 await test('虚拟时间静默看门提示连接中断可重试',async()=>{assert.match(await page.locator('.ask-dock .ask-state-line').innerText(),/连接中断/);assert.equal(await page.locator('.ask-state-retry').isVisible(),true)});
}finally{if(browser)await browser.close();await new Promise(ok=>server.close(ok))}if(failed)process.exitCode=1;
