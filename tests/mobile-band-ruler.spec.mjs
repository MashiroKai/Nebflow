#!/usr/bin/env node
// Isolated browser geometry probe; BAND_WEB selects the asset tree, BAND_NEGATIVE injects a regression.
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, extname, resolve } from 'node:path';
const web = resolve(process.env.BAND_WEB || 'src/main/resources/web');
const out = resolve(process.env.BAND_OUT || '/private/tmp/stand-band-evidence');
mkdirSync(out, {recursive:true});
const browser = await chromium.launch();
let errors = 0;
for (const width of [375,390]) {
 const context = await browser.newContext({viewport:{width,height:812}, deviceScaleFactor:2, hasTouch:true, reducedMotion:'reduce'});
 const page = await context.newPage();
 await page.addInitScript(() => {localStorage.setItem('nebflow_token','e2e-token'); localStorage.setItem('stand_sw','0');});
 await page.route('**/*', route => {
  const p = decodeURIComponent(new URL(route.request().url()).pathname);
  if(p.startsWith('/api/')) return route.fulfill({json:p === '/api/sessions' ? {sessions:[{id:'band-test',name:'root',agentName:'Nebula'}],activeId:'band-test'} : {}});
  const f = resolve(web, '.' + (p === '/' ? '/index.html' : p));
  if (!f.startsWith(web + '/')) return route.fulfill({status:403});
  const mime = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.json':'application/json','.png':'image/png','.woff2':'font/woff2'};
  try { return route.fulfill({body:readFileSync(f),contentType:mime[extname(f)] || 'application/octet-stream'}); } catch {return route.fulfill({status:404});}
 });
 await page.routeWebSocket(/\/ws/, ws => {
  const config=()=>ws.send(JSON.stringify({type:'configData',config:'{}',configured:true,onboarding:'done'}));
  const sessions=()=>ws.send(JSON.stringify({type:'sessionList',sessions:[{id:'band-test',name:'root',agentName:'Nebula'}],folders:[],activeId:'band-test'}));
  config(); sessions(); ws.onMessage(raw=>{let m; try {m=JSON.parse(raw)} catch{return} if(m.type==='getConfig') config(); if(['getSessions','listSessions'].includes(m.type)) sessions(); if(m.type==='getHistory') ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0}));});
 });
 await page.goto('http://localhost:8899/');
 await page.waitForSelector('body.mobile-layer');
 await page.waitForFunction(async()=>{const s=(await import('/js/state.js')).default; return s.ws?.readyState===1});
 // Fixture-only ownership: no live backend exists. Remove takeover overlay and assert it no longer intercepts.
 await page.evaluate(async()=>{const s=(await import('/js/state.js')).default; s.consoleSuperseded=false; const o=document.querySelector('#modal-overlay'); o.classList.remove('on','takeover-live','takeover-blur'); document.querySelector('#takeover-box').style.display='none';});
 if(await page.locator('#modal-overlay').isVisible()) throw Error('fixture takeover overlay remains visible');
 console.log(`PASS fixture overlay absent width=${width}`);
 if(process.env.BAND_NEGATIVE) await page.addStyleTag({content:'body.mobile-layer .mh-search-dock.mh-search-collapsed .mh-search-bar { width: 31px !important; height:31px !important }'});
 const rows=[];
 for(const id of ['chat','messages','contacts','canvas','workbench','settings','tasks']) {
  if(id==='canvas') await page.evaluate(()=>window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:'band-document',title:'Canvas test',itemType:'markdown',pinned:false,background:false,content:'# Canvas'}})));
  await page.evaluate(async id=>{const n=await import('/js/mobile/navController.js'); if(id==='chat') n.goHome(); else n.navigateTo(id);},id);
  if(id!=='chat') await page.waitForSelector(`.mobile-page.live[data-page="${id}"]`,{timeout:12000});
  if(['messages','contacts'].includes(id)) await page.evaluate(()=>{const dock=document.querySelector('.mobile-page.live .mh-search-dock'); if(dock){dock.classList.add('mh-search-collapsed');dock.querySelector('.mh-search-bar')?.classList.add('g2-fab');}});
  await page.waitForTimeout(400);
  const data=await page.evaluate(id=>{
   const root=id==='chat'?document:document.querySelector('.mobile-page.live');
   const sel=id==='chat'?'.mh-chrome-btn[data-chrome="search"]': ['messages','contacts'].includes(id)?'.mh-search-bar': id==='canvas'?'.mf-cv-toolbar .g2-fab':'.mobile-back-btn';
   const els=[...root.querySelectorAll(sel)].filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width&&r.height&&s.visibility!=='hidden'&&s.display!=='none'});
   const rect=e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height,right:innerWidth-r.right,cy:r.y+r.height/2}};
   const avatar=root.querySelector('.mh-idhead img,.g2-chat-head img,.mh-idhead-avatar');
   return {id,buttons:els.map(e=>({box:rect(e),icon:e.querySelector('svg')?rect(e.querySelector('svg')):null})),avatar:avatar?rect(avatar):null, bottom:getComputedStyle(document.body).getPropertyValue('--mcv-size-circle').trim(),capsule:getComputedStyle(document.body).getPropertyValue('--mcap-h').trim()};
  },id);
  rows.push(data); console.log(JSON.stringify({width,...data}));
  if(['messages','contacts','canvas'].includes(id)) await page.screenshot({path:join(out,`${width}-${id}.png`)});
 }
 writeFileSync(join(out,`${width}.json`),JSON.stringify(rows,null,2));
 const values=rows.flatMap(r=>r.buttons.map(b=>({id:r.id,...b})));
 const expected=Math.min(52,Math.max(44,width*.128));
 for(const b of values) if(Math.abs(b.box.w-expected)>.15||Math.abs(b.box.h-expected)>.15||!b.icon||Math.abs(b.icon.w-expected*.45)>.15||Math.abs(b.box.y-16)>.5||Math.min(b.box.x,b.box.right)>16.5) {console.log('FAIL geometry',JSON.stringify({width,...b,expected}));errors++;}
 for(const id of ['chat','messages','contacts','canvas','settings','tasks']) if(!rows.find(r=>r.id===id).buttons.length) {console.log('FAIL missing top button',id); errors++;}
 if(rows.find(r=>r.id==='canvas').buttons.length!==2) { console.log('FAIL canvas requires two top controls'); errors++; }
 if(rows.find(r=>r.id==='workbench').buttons.length) { console.log('FAIL workbench unexpectedly has a top button'); errors++; }
 await context.close();
}
await browser.close();
console.log(`${errors?'FAIL':'PASS'} geometry errors=${errors}`);
process.exitCode=errors?1:0;
