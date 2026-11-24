import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
const WEB=join(dirname(fileURLToPath(import.meta.url)),'..','src/main/resources/web');
const MIME={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.woff2':'font/woff2'};
test.use({viewport:{width:375,height:812},hasTouch:true});
test('mobile voice overlay mounts through voice gesture',async({page})=>{
 await page.addInitScript(()=>{localStorage.setItem('nebflow_token','test'); localStorage.setItem('nebflow_locale','zh-CN'); class MockSR {constructor(){window.__voiceRecognition=this} start(){this.onstart?.({});this.onaudiostart?.({})} abort(){queueMicrotask(()=>this.onend?.({}))} stop(){queueMicrotask(()=>this.onend?.({}))}} window.SpeechRecognition=MockSR;});
 await page.route('**/*',route=>{let p=decodeURIComponent(new URL(route.request().url()).pathname); if(p==='/')p='/index.html'; if(p.startsWith('/api/'))return route.fulfill({json:{}}); const file=normalize(join(WEB,p)); if(!file.startsWith(WEB+'/')) return route.fulfill({status:403}); try{return route.fulfill({body:readFileSync(file),contentType:MIME[extname(file)]||'application/octet-stream'})}catch{return route.fulfill({status:404})}});
 await page.routeWebSocket(/\/ws/,ws=>{ws.send(JSON.stringify({type:'configData',config:JSON.stringify({features:{voice:true}}),configured:true,onboarding:'done'})); ws.send(JSON.stringify({type:'sessionList',sessions:[{id:'s1',agentName:'Nebula',title:'test'}],activeId:'s1',folders:[]})); ws.onMessage(raw=>{let m;try{m=JSON.parse(raw)}catch{return} if(m.type==='getHistory')ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0}));});});
 await page.goto('http://localhost:1/');
 await page.waitForFunction(()=>{const overlay=document.querySelector('#voice-mode-overlay'); return overlay && !overlay.hasAttribute('hidden')});
 const button=page.locator('#voice-btn'); await expect(button).toBeVisible();
 await page.waitForFunction(()=>!!document.querySelector('#input')?.placeholder);
 await expect(button).toBeEnabled();
 await page.waitForFunction(() => { const b=document.querySelector('#voice-btn'); if(!b)return false; const r=b.getBoundingClientRect(), hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2); return b.contains(hit); });
 const box=await button.boundingBox(); const x=box.x+box.width/2, y=box.y+box.height/2;
 const cdp=await page.context().newCDPSession(page);
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});
 for(let dx=10;dx<=80;dx+=10) { await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+dx,y}]}); await page.evaluate(() => new Promise(requestAnimationFrame)); }
 await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 const overlay=page.locator('#voice-mode-overlay.on'); await expect(overlay).toBeVisible({timeout:10000});
 const bounds=await overlay.boundingBox(); expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.x+bounds.width).toBeLessThanOrEqual(375); expect(bounds.y).toBeGreaterThanOrEqual(0); expect(bounds.y+bounds.height).toBeLessThanOrEqual(812);
 const exit=page.locator('#vmo-exit'); await expect(exit).toBeVisible();
 await page.waitForFunction(()=>window.__voiceRecognition?.__vs?.alive===true);
 await page.evaluate(()=>{const result=[{transcript:'long voice transcript '.repeat(40)}]; result.isFinal=false; window.__voiceRecognition.onresult({resultIndex:0,results:[result]});});
 const transcript=page.locator('#vmo-transcript'); await expect(transcript).toBeVisible();
 const text=await transcript.evaluate(el=>{const r=el.getBoundingClientRect(),s=getComputedStyle(el);return {left:r.left,right:r.right,overflow:el.scrollWidth>el.clientWidth,ellipsis:s.textOverflow};});
 expect(text.left).toBeGreaterThanOrEqual(0); expect(text.right).toBeLessThanOrEqual(375); expect(text.overflow).toBe(true); expect(text.ellipsis).toBe('ellipsis');
 const out=process.env.MOBILE_AUDIT_EVIDENCE; if(out){mkdirSync(out,{recursive:true}); await page.screenshot({path:join(out,'语音.png'),animations:'disabled'});}
 await exit.click(); await expect(overlay).toHaveCount(0);
});
