import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dockTo } from './_mobile-dock-nav.mjs';
const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src/main/resources/web');
const MIME = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.woff2':'font/woff2','.json':'application/json'};
const SID = 'rules-audit-session';
test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });
test('model rules mobile mounting and scroll geometry', async ({page}) => {
  await page.addInitScript(() => { localStorage.setItem('nebflow_token','e2e'); localStorage.setItem('nebflow_locale','zh-CN'); });
  await page.route('**/*', route => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname); if (p === '/') p = '/index.html';
    if (p === '/api/secrets') return route.fulfill({json:{secrets:Array.from({length:12},(_,i)=>({name:`sample-long-secret-metadata-name-${i}`,variable:`SAMPLE_${i}`,purpose:'fixture only',updatedAt:'2024-01-01'}))}});
    if (p === '/api/model-rules/mode') return route.fulfill({json:{mode:'rules'}});
    if (p === '/api/model-rules') return route.fulfill({json:{mode:'rules',source:Array.from({length:60},(_,i)=>`rule ${i} ${'long-content '.repeat(12)}`).join('\n'),diagram:{nodes:Array.from({length:30},(_,i)=>({rule:'fixture',kind:'match',label:`rule node ${i}`,srcLine:i+1})),edges:[]}}});
    if (p === '/api/sessions') return route.fulfill({json:{sessions:[{id:SID,name:'root',agentName:'Nebula'}],activeId:SID}});
    if (p.startsWith('/api/')) return route.fulfill({json:{}});
    const file = normalize(join(WEB,p)); if (!file.startsWith(WEB + '/')) return route.fulfill({status:403});
    try { return route.fulfill({body:readFileSync(file),contentType:MIME[extname(file)] || 'application/octet-stream'}); } catch { return route.fulfill({status:404}); }
  });
  await page.routeWebSocket(/\/ws/, ws => {
    const config = () => ws.send(JSON.stringify({type:'configData',config:'{}',configured:true,onboarding:'done'}));
    const sessions = () => ws.send(JSON.stringify({type:'sessionList',sessions:[{id:SID,name:'root',agentName:'Nebula'}],folders:[],activeId:SID}));
    config(); sessions(); ws.onMessage(raw => { let m; try {m=JSON.parse(raw)} catch{return} if(m.type==='getConfig') config(); if(m.type==='getSessions'||m.type==='listSessions') sessions(); if(m.type==='getHistory') ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0})); });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async sid => {const s=(await import('/js/state.js')).default; return s.activeSessionId===sid && s.ws?.readyState===1},SID);
  await dockTo(page,'workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="settings"]').click();
  await expect(page.locator('.mobile-page[data-page="settings"]')).toHaveClass(/live/);
  await page.locator('#btn-open-secrets').click({timeout:10000});
  await expect(page.locator('#secrets-modal')).toBeVisible({timeout:10000});
  await expect(page.locator('.secrets-row').first()).toBeVisible();
  const name = page.locator('.secrets-row-name').last();
  const longName = await name.evaluate(el => { const cs=getComputedStyle(el); const r=el.getBoundingClientRect(); return {text:el.textContent, overflow:el.scrollWidth>el.clientWidth, ellipsis:cs.textOverflow, right:r.right}; });
  expect(longName.text).toContain('sample-long-secret');
  expect(longName.right).toBeLessThanOrEqual(375);
  if (longName.overflow) expect(longName.ellipsis).toBe('ellipsis');
  await page.locator('.secrets-row-head').first().click();
  await expect(page.locator('.secrets-modal-header')).toBeVisible();
  const secretsScroll = page.locator('.secrets-modal-body');
  for (const fraction of [0, 0.5, 1]) {
    const geo = await secretsScroll.evaluate((el, f) => {
      el.scrollTop = (el.scrollHeight - el.clientHeight) * f;
      const r = el.getBoundingClientRect(); const h = document.querySelector('.secrets-modal-header').getBoundingClientRect();
      const items=[...el.querySelectorAll('.secrets-row')].map(item=>item.getBoundingClientRect()).filter(b=>b.bottom>r.top && b.top<r.bottom);
      return {left:r.left,right:r.right,top:r.top,headerBottom:h.bottom,scroll:el.scrollTop,max:el.scrollHeight-el.clientHeight,items:items.map(b=>({left:b.left,right:b.right,top:b.top,bottom:b.bottom}))};
    }, fraction);
    expect(geo.left).toBeGreaterThanOrEqual(-1); expect(geo.right).toBeLessThanOrEqual(376);
    expect(geo.top).toBeGreaterThanOrEqual(geo.headerBottom - 1);
    expect(geo.max).toBeGreaterThan(0); expect(Math.abs(geo.scroll-geo.max*fraction)).toBeLessThan(2);
    const hit=await page.locator('#secrets-modal-close').evaluate(el=>{const b=el.getBoundingClientRect(),n=document.elementFromPoint(b.left+b.width/2,b.top+b.height/2);return n===el||el.contains(n)}); expect(hit).toBe(true);
    expect(geo.items.length).toBeGreaterThan(0);
    for(const item of geo.items) { expect(item.left).toBeGreaterThanOrEqual(0); expect(item.right).toBeLessThanOrEqual(375); if(item.top>=geo.top) expect(item.top).toBeGreaterThanOrEqual(geo.headerBottom-1); }
  }
  const rows=page.locator('.secrets-row');
  for(let i=0;i<await rows.count();i++) {const item=rows.nth(i); await item.evaluate(el=>el.scrollIntoView({block:'center',behavior:'instant'})); const b=await item.boundingBox(),h=await page.locator('.secrets-modal-header').boundingBox(); expect(b.y).toBeGreaterThanOrEqual(h.y+h.height-1); expect(b.y+b.height).toBeLessThanOrEqual(812);}
  if(process.env.MOBILE_AUDIT_EVIDENCE) { mkdirSync(process.env.MOBILE_AUDIT_EVIDENCE,{recursive:true}); await page.screenshot({path:join(process.env.MOBILE_AUDIT_EVIDENCE,'密钥.png')}); }
  await page.locator('#secrets-modal-close').click();
  await expect(page.locator('#secrets-modal')).toBeHidden();
  const disclosure=page.locator('[data-settings-disclosure="models"]');
  if(await disclosure.getAttribute('aria-expanded')!=='true') await disclosure.click();
  await page.locator('#model-chain-modes [data-rule-mode="rules"]').click();
  const draft=page.locator('.mr-draft'); await expect(draft).toBeVisible({timeout:15000});
  const header=page.locator('.mobile-page[data-page="settings"] .mobile-back-btn'); await expect(header).toBeVisible();
  const draftBox = await draft.boundingBox(); const headerBox = await header.boundingBox();
  expect(draftBox.x).toBeGreaterThanOrEqual(0); expect(draftBox.x+draftBox.width).toBeLessThanOrEqual(375);
  expect(draftBox.y).toBeGreaterThanOrEqual(headerBox.y+headerBox.height-1);
  await expect(page.locator('.mr-node')).toHaveCount(30);
  const face=page.locator('.mobile-page[data-page="settings"] #settings-content');
  await face.evaluate(el=>{el.style.scrollBehavior='auto'});
  for (const fraction of [0,0.5,1]) {
    await face.evaluate((el,f) => {el.scrollTop=(el.scrollHeight-el.clientHeight)*f},fraction);
    const result=await face.evaluate(el => {const r=el.getBoundingClientRect(),h=document.querySelector('.mobile-page[data-page="settings"] .mobile-back-btn').getBoundingClientRect(); const items=[...el.querySelectorAll('.mr-node')].map(n=>n.getBoundingClientRect()).filter(b=>b.bottom>r.top && b.top<r.bottom).map(b=>({left:b.left,right:b.right,top:b.top,bottom:b.bottom})); return {left:r.left,right:r.right,scroll:el.scrollTop,max:el.scrollHeight-el.clientHeight,headerBottom:h.bottom,headerLeft:h.left,headerRight:h.right,headerTop:h.top,top:r.top,items}});
    expect(result.left).toBeGreaterThanOrEqual(-1); expect(result.right).toBeLessThanOrEqual(376); expect(result.max).toBeGreaterThan(0); expect(Math.abs(result.scroll-result.max*fraction)).toBeLessThan(2);
    for(const item of result.items) {expect(item.left).toBeGreaterThanOrEqual(0); expect(item.right).toBeLessThanOrEqual(375); }
    const hit=await header.evaluate(el=>{const r=el.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return el===hit || el.contains(hit)}); expect(hit).toBe(true);
    if(fraction===0.5 && process.env.MOBILE_AUDIT_EVIDENCE) {mkdirSync(process.env.MOBILE_AUDIT_EVIDENCE,{recursive:true}); await page.screenshot({path:join(process.env.MOBILE_AUDIT_EVIDENCE,'规则-中部.png'),animations:'disabled'});}
  }
  for(const index of [0,29]) {
    const node=page.locator('.mr-node').nth(index); await node.evaluate(el=>el.scrollIntoView({block:'center',behavior:'instant'}));
    const b=await node.boundingBox(),h=await header.boundingBox(),d=await page.locator('#mobile-dock').boundingBox(); expect(b.y).toBeGreaterThanOrEqual(h.y+h.height); expect(b.y+b.height).toBeLessThanOrEqual(d.y);
  }
  for(let i=0;i<30;i++) {
    const node=page.locator('.mr-node').nth(i); await node.scrollIntoViewIfNeeded();
    const geometry=await node.evaluate(el=>{const r=el.getBoundingClientRect(),h=document.querySelector('.mobile-page[data-page="settings"] .mobile-back-btn').getBoundingClientRect(),d=document.querySelector('#mobile-dock').getBoundingClientRect(); return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,headerBottom:h.bottom,dockTop:d.top};});
    expect(geometry.top).toBeGreaterThanOrEqual(geometry.headerBottom); expect(geometry.bottom).toBeLessThanOrEqual(geometry.dockTop);
  }
  if(process.env.MOBILE_AUDIT_EVIDENCE) { mkdirSync(process.env.MOBILE_AUDIT_EVIDENCE,{recursive:true}); await page.screenshot({path:join(process.env.MOBILE_AUDIT_EVIDENCE,'规则.png')}); }
});
