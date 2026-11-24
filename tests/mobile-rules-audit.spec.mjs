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
    if (p === '/api/model-rules') return route.fulfill({json:{mode:'rules',source:'long rule text '.repeat(30),diagram:{nodes:[]}}});
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
    expect(geo.items.length).toBeGreaterThan(0);
    for(const item of geo.items) { expect(item.left).toBeGreaterThanOrEqual(0); expect(item.right).toBeLessThanOrEqual(375); if(item.top>=geo.top) expect(item.top).toBeGreaterThanOrEqual(geo.headerBottom-1); }
  }
  if(process.env.MOBILE_AUDIT_EVIDENCE) { mkdirSync(process.env.MOBILE_AUDIT_EVIDENCE,{recursive:true}); await page.screenshot({path:join(process.env.MOBILE_AUDIT_EVIDENCE,'密钥.png')}); }
  await page.locator('#secrets-modal-close').click();
  await expect(page.locator('#secrets-modal')).toBeHidden();
  const disclosure=page.locator('[data-settings-disclosure="models"]');
  if(await disclosure.getAttribute('aria-expanded')!=='true') await disclosure.click();
  await page.locator('#model-chain-modes [data-rule-mode="rules"]').click();
  const draft=page.locator('.mr-draft'); await expect(draft).toBeVisible({timeout:15000});
  const header=page.locator('.mobile-page[data-page="settings"] .mobile-back-btn'); await expect(header).toBeVisible();
  const face=page.locator('.mobile-page[data-page="settings"] #settings-content');
  for (const fraction of [0,0.5,1]) {
    await face.evaluate((el,f) => {el.scrollTop=(el.scrollHeight-el.clientHeight)*f},fraction);
    const result=await face.evaluate(el => {const r=el.getBoundingClientRect(); return {left:r.left,right:r.right,scroll:el.scrollTop}});
    expect(result.left).toBeGreaterThanOrEqual(-1); expect(result.right).toBeLessThanOrEqual(376);
  }
  if(process.env.MOBILE_AUDIT_EVIDENCE) { mkdirSync(process.env.MOBILE_AUDIT_EVIDENCE,{recursive:true}); await page.screenshot({path:join(process.env.MOBILE_AUDIT_EVIDENCE,'规则.png')}); }
});
