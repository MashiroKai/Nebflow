import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
test.use({ viewport: { width: Number(process.env.THINK_WIDTH || 375), height: 812 } });
test('mobile live thinking stays single-line and anchored to its tail', async ({ page }) => {
  await page.route('**/*', route => {
    const path = decodeURIComponent(new URL(route.request().url()).pathname);
    const file = normalize(join(root, path));
    if (!file.startsWith(root + '/')) return route.fulfill({ status: 403 });
    try { return route.fulfill({ body: readFileSync(file), contentType: types[extname(file)] || 'application/octet-stream' }); }
    catch { return route.fulfill({ status: 404 }); }
  });
  await page.goto('http://localhost:1/tests/fixtures/stream-ux/harness.html');
  await page.waitForFunction(() => window.__ready === true);
  await page.evaluate(async () => {
    for(let i=0;i<30;i++) await window.__userSlide();
    await window.__thinkingRun();
    const chat = await import('/src/main/resources/web/js/chat.js');
    chat.appendThinkingDelta(' Long internal progress text that is wider than a mobile screen and must not silently disappear '.repeat(6));
  });
  const think = page.locator('.nf-workline .nf-wl-think');
  await expect(think).toBeVisible();
  if (process.env.THINK_SHOT) await page.screenshot({path: process.env.THINK_SHOT, animations: 'disabled'});
  const metrics = await think.evaluate(el => ({ left: el.scrollLeft, width: el.clientWidth, scroll: el.scrollWidth, ellipsis: getComputedStyle(el).textOverflow, line: getComputedStyle(el).webkitLineClamp, wrap: getComputedStyle(el).whiteSpace }));
  expect(metrics.wrap).toBe('nowrap');
  expect(metrics.scroll).toBeGreaterThan(metrics.width);
  expect(Math.abs(metrics.left - (metrics.scroll - metrics.width))).toBeLessThanOrEqual(1);
  const chat=page.locator('#chat'); await chat.evaluate(el=>{el.style.scrollBehavior='auto'});
  for(const f of [0,0.5,1]) {
    const state=await chat.evaluate((el,fraction)=>{el.scrollTop=(el.scrollHeight-el.clientHeight)*fraction; return {scroll:el.scrollTop,max:el.scrollHeight-el.clientHeight}},f);
    expect(state.max).toBeGreaterThan(0); expect(Math.abs(state.scroll-state.max*f)).toBeLessThan(2);
  }
  await think.evaluate(el=>el.scrollIntoView({block:'center',behavior:'instant'}));
  const rect = await think.boundingBox();
  expect(rect.x).toBeGreaterThanOrEqual(0); expect(rect.x+rect.width).toBeLessThanOrEqual(375);
  const collision = await think.evaluate(el => {const a=el.getBoundingClientRect(), s=el.closest('.nf-wl-slot').getBoundingClientRect(); return {height:a.height,slotHeight:s.height,top:a.top,slotTop:s.top,bottom:a.bottom,slotBottom:s.bottom};});
  expect(collision.height).toBeLessThanOrEqual(collision.slotHeight + 6);
  const out=process.env.MOBILE_AUDIT_EVIDENCE;
  if(out) {mkdirSync(out,{recursive:true}); await page.screenshot({path:join(out,'聚焦条.png'),animations:'disabled'});}
  await page.evaluate(()=>window.__fullTurnCollapse());
  const settled=page.locator('.turn-header').last(); await expect(settled).toBeVisible(); await settled.click();
  await expect(settled).toBeVisible();
});

test('mobile workline full shell stays clear of composer and expands', async ({page}) => {
  const web=join(root,'src/main/resources/web'), sid='workline-full-shell'; let live;
  await page.addInitScript(()=>{localStorage.setItem('nebflow_token','t'); localStorage.setItem('nebflow_locale','zh-CN');});
  await page.route('**/*',route=>{let path=decodeURIComponent(new URL(route.request().url()).pathname); if(path==='/')path='/index.html'; if(path==='/api/sessions')return route.fulfill({json:{sessions:[{id:sid,name:'test'}],activeId:sid}}); if(path.startsWith('/api/'))return route.fulfill({json:{}}); const file=normalize(join(web,path)); if(!file.startsWith(web+'/'))return route.fulfill({status:403}); try{return route.fulfill({body:readFileSync(file),contentType:types[extname(file)]||'application/octet-stream'});}catch{return route.fulfill({status:404});}});
  await page.routeWebSocket(/\/ws/,ws=>{live=ws; ws.send(JSON.stringify({type:'configData',config:'{}',configured:true,onboarding:'done'})); ws.send(JSON.stringify({type:'sessionList',sessions:[{id:sid,name:'test'}],activeId:sid,folders:[]})); ws.onMessage(raw=>{let m;try{m=JSON.parse(raw)}catch{return} if(m.type==='getHistory')ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0}));});});
  await page.goto('http://localhost:1/'); await page.waitForFunction(async id=>{const s=(await import('/js/state.js')).default;return s.activeSessionId===id && s.ws?.readyState===1},sid);
  await page.evaluate(async()=>{const chat=await import('/js/chat.js'); for(let i=0;i<30;i++) chat.renderUserBubble(`prior message ${i}`,[],Date.now());});
  await page.locator('#input').fill('mobile full shell'); await page.locator('#input').press('Enter');
  for(const frame of [{type:'sessionBusy',sessionId:sid,busy:true},{type:'thinking',sessionId:sid},{type:'thinkingDelta',sessionId:sid,delta:'Long mobile thinking content '.repeat(20)}]) live.send(JSON.stringify(frame));
  const text=page.locator('#chat .nf-workline .nf-wl-think'); await expect(text).toBeVisible();
  const scroller=page.locator('#chat'); await scroller.evaluate(el=>{el.style.scrollBehavior='auto'});
  for(const f of [0,0.5,1]) {const g=await scroller.evaluate((el,x)=>{el.scrollTop=(el.scrollHeight-el.clientHeight)*x;return {top:el.scrollTop,max:el.scrollHeight-el.clientHeight}},f); expect(g.max).toBeGreaterThan(0); expect(Math.abs(g.top-g.max*f)).toBeLessThan(2);}
  await text.evaluate(el=>el.scrollIntoView({block:'center',behavior:'instant'}));
  const shellHeader=page.locator('#chat .g2-chat-head'); await expect(shellHeader).toBeAttached();
  const overlap=await text.evaluate(el=>{const a=el.getBoundingClientRect(),h=document.querySelector('#chat .g2-chat-head').getBoundingClientRect();return a.left<h.right && a.right>h.left && a.top<h.bottom && a.bottom>h.top}); expect(overlap).toBe(false);
  const geom=await text.evaluate(el=>{const r=el.getBoundingClientRect(),input=document.querySelector('#input-area').getBoundingClientRect(); return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,inputTop:input.top,scroll:el.scrollWidth,width:el.clientWidth}});
  expect(geom.left).toBeGreaterThanOrEqual(0); expect(geom.right).toBeLessThanOrEqual(375); expect(geom.top).toBeGreaterThanOrEqual(0); expect(geom.bottom).toBeLessThanOrEqual(geom.inputTop); expect(geom.scroll).toBeGreaterThan(geom.width);
  for(const frame of [{type:'textDelta',sessionId:sid,delta:'Answer.'},{type:'textDone',sessionId:sid},{type:'done',sessionId:sid,model:'test',durationMs:47000}]) live.send(JSON.stringify(frame));
  const header=page.locator('#chat .turn-header').last(); await expect(header).toBeVisible(); await header.click(); await expect(header).toHaveAttribute('aria-expanded','true');
  await expect(page.locator('#chat .thinking-row:not(.nf-tucked)').last()).toBeVisible();
});
