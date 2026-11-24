import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
const WEB=join(dirname(fileURLToPath(import.meta.url)),'..','src/main/resources/web');
const MIME={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.woff2':'font/woff2'};
test.use({viewport:{width:375,height:812},hasTouch:true});
test('mobile voice overlay mounts through voice gesture',async({page})=>{
 await page.addInitScript(()=>{localStorage.setItem('nebflow_token','test'); localStorage.setItem('nebflow_locale','zh-CN'); class MockSR {start(){this.onstart?.({});this.onaudiostart?.({})} abort(){queueMicrotask(()=>this.onend?.({}))} stop(){queueMicrotask(()=>this.onend?.({}))}} window.SpeechRecognition=MockSR;});
 await page.route('**/*',route=>{let p=decodeURIComponent(new URL(route.request().url()).pathname); if(p==='/')p='/index.html'; if(p.startsWith('/api/'))return route.fulfill({json:{}}); const file=normalize(join(WEB,p)); if(!file.startsWith(WEB+'/')) return route.fulfill({status:403}); try{return route.fulfill({body:readFileSync(file),contentType:MIME[extname(file)]||'application/octet-stream'})}catch{return route.fulfill({status:404})}});
 await page.routeWebSocket(/\/ws/,ws=>{ws.send(JSON.stringify({type:'configData',config:JSON.stringify({features:{voice:true}}),configured:true,onboarding:'done'})); ws.send(JSON.stringify({type:'sessionList',sessions:[{id:'s1',agentName:'Nebula',title:'test'}],activeId:'s1',folders:[]})); ws.onMessage(raw=>{let m;try{m=JSON.parse(raw)}catch{return} if(m.type==='getHistory')ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0}));});});
 await page.goto('http://localhost:1/');
 const button=page.locator('#voice-btn'); await expect(button).toBeVisible();
 const box=await button.boundingBox(); await page.mouse.move(box.x+box.width/2,box.y+box.height/2); await page.mouse.down(); await page.mouse.move(box.x+box.width/2+80,box.y+box.height/2,{steps:10}); await page.mouse.up();
 const overlay=page.locator('#voice-mode-overlay.on'); await expect(overlay).toBeVisible({timeout:10000});
 const bounds=await overlay.boundingBox(); expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.x+bounds.width).toBeLessThanOrEqual(375); expect(bounds.y).toBeGreaterThanOrEqual(0); expect(bounds.y+bounds.height).toBeLessThanOrEqual(812);
 const exit=page.locator('#vmo-exit'); await expect(exit).toBeVisible();
 const out=process.env.MOBILE_AUDIT_EVIDENCE; if(out){mkdirSync(out,{recursive:true}); await page.screenshot({path:join(out,'语音.png'),animations:'disabled'});}
 await exit.click(); await expect(overlay).toHaveCount(0);
});
