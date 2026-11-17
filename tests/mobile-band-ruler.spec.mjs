#!/usr/bin/env node
// Isolated browser geometry probe; BAND_WEB selects the asset tree, BAND_NEGATIVE injects a regression.
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync, realpathSync } from 'node:fs';
import { join, extname, resolve } from 'node:path';
// No backend is launched; require an existing explicitly isolated root before any browser starts.
if (!process.env.STAND_TEST_DATA_ROOT) throw Error('STAND_TEST_DATA_ROOT is required');
const dataRoot = realpathSync(process.env.STAND_TEST_DATA_ROOT);
const productionRoot = realpathSync('/Users/kaiyu/.stand');
console.log(`fixture data root: ${dataRoot}`);
if (dataRoot === productionRoot || dataRoot.startsWith(productionRoot + '/')) throw Error('refusing production data root');
console.log('PASS isolated data root');
const web = resolve(process.env.BAND_WEB || 'src/main/resources/web');
const out = resolve(process.env.BAND_OUT || '/private/tmp/stand-band-evidence');
mkdirSync(out, {recursive:true});
const browser = await chromium.launch();
let errors = 0;
for (const width of [320,375,390,430]) {
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
 // Geometry does not require input. Record ownership-card state without altering it.
 console.log(`INFO takeover card visible=${await page.locator('#modal-overlay.on #takeover-box').isVisible()} width=${width}`);
 if(process.env.BAND_NEGATIVE) await page.addStyleTag({content:'body.mobile-layer .mh-search-dock.mh-search-collapsed .mh-search-bar { width: 31px !important; height:31px !important }'});
 if(process.env.COMPOSER_NEGATIVE) await page.addStyleTag({content:'body.mobile-layer #input-bar {padding-left:12px !important;padding-right:12px !important;gap:8px !important} .ask-input {padding-left:6px !important;gap:6px !important}'});
 if(process.env.HIT_NEGATIVE) await page.addStyleTag({content:'@media(max-width:700px){.decision-preview-entry{top:auto !important;bottom:90px !important}}'});
 const rows=[];
 for(const id of ['chat','messages','contacts','canvas','workbench','settings','tasks','todo-apple-item']) {
  if(id==='canvas') await page.evaluate(()=>window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:'band-document',title:'Canvas test',itemType:'markdown',absPath:'/fixture/band-document.md',pinned:false,background:false,content:'# Canvas'}})));
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
   const tool=root.querySelector('.ta-tool');
   const bottomControls = id === 'canvas' ? [...root.querySelectorAll('.canvas-source-toggle, .canvas-ref-select, .canvas-find-toggle, .canvas-zoom-btn')].filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width&&r.height&&s.visibility!=='hidden'&&s.display!=='none'}).map(e=>{const s=getComputedStyle(e);return {selector:e.className,box:rect(e),icon:e.querySelector('svg')?rect(e.querySelector('svg')):null,padding:s.padding,radius:s.borderRadius,transform:s.transform}}) : [];
   return {id,buttons:els.map(e=>{const s=getComputedStyle(e);return {box:rect(e),icon:e.querySelector('svg')?rect(e.querySelector('svg')):null,padding:s.padding,radius:s.borderRadius,overflowX:document.documentElement.scrollWidth-innerWidth}}),bottomControls,avatar:avatar?rect(avatar):null, tool:tool?rect(tool):null,bottom:getComputedStyle(document.body).getPropertyValue('--mcv-size-circle').trim(),capsule:getComputedStyle(document.body).getPropertyValue('--mcap-h').trim()};
  },id);
  rows.push(data); console.log(JSON.stringify({width,...data}));
  if(id==='chat'||id==='canvas') {
   if(id==='canvas') {const fab=page.locator('.ask-fab:not(.note-fab):visible').first();if(await fab.count()) {await fab.click({timeout:3000});await page.waitForTimeout(400);}}
   const inputInfo=await page.evaluate(id=>{
    const bar=document.querySelector(id==='chat'?'#input-bar':'.ask-dock:not(.closed) .ask-input');
    if(!bar)return null;
    const input=bar.querySelector('textarea,input');if(!input)return null;
    const box=e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height,right:r.right}};
    const rect=box(bar),text=box(input),controls=[...bar.children].filter(e=>!e.contains(input)&&e!==input&&getComputedStyle(e).display!=='none'&&getComputedStyle(e).visibility!=='hidden'&&e.getBoundingClientRect().width>0).map(e=>{const r=box(e),hit=document.elementFromPoint(r.x+r.w/2,r.y+r.h/2);return {id:e.id||e.className,...r,hit:!!hit&&(e===hit||e.contains(hit)),disabled:e.disabled,chain:hit?[hit,...(function*(n){while(n.parentElement){n=n.parentElement;yield n}})(hit)].slice(0,5).map(n=>({tag:n.tagName,id:n.id,cls:String(n.className),pointer:getComputedStyle(n).pointerEvents,opacity:getComputedStyle(n).opacity,transform:getComputedStyle(n).transform})):[]}});
    const css=getComputedStyle(bar);return {bar:rect,input:text,controls,padding:css.padding,gap:css.gap,utilization:text.w/rect.w};
   },id);
   console.log('COMPOSER',JSON.stringify({width,id,...inputInfo}));
   if(!inputInfo) {console.log('FAIL missing composer',id,width);errors++;}
   else {
    const c=inputInfo.controls, side=c.reduce((n,e)=>n+e.w,0), expected=inputInfo.bar.w-side-(id==='chat'?16:12)-(c.length*4)-2;
    if(inputInfo.input.w<expected-.15 || c.some(e=>!e.hit||e.x<inputInfo.bar.x-.15||e.right>inputInfo.bar.right+.15||Math.min(e.right,inputInfo.input.right)-Math.max(e.x,inputInfo.input.x)>.15)) {console.log('FAIL composer width or overlap',JSON.stringify({width,id,expected,...inputInfo}));errors++;}
   }
   if(width===320||width===375) await page.screenshot({path:join(out,`${width}-${id}-composer.png`)});
  }
  if(id==='canvas') {
   const states=await page.evaluate(()=>{
    const top=document.querySelector('.mobile-page.live .mf-cv-close'), bottom=document.querySelector('.mobile-page.live .canvas-find-toggle');
    const read=e=>({opacity:getComputedStyle(e).opacity,cursor:getComputedStyle(e).cursor,transform:getComputedStyle(e).transform});
    if(!top||!bottom)return null;
    const rest=[read(top),read(bottom)]; top.disabled=true;bottom.disabled=true;
    const disabled=[read(top),read(bottom)]; top.disabled=false;bottom.disabled=false;
    return {rest,disabled};
   });
   console.log('STATES',JSON.stringify({width,states}));
   if(!states || states.disabled.some(s=>s.opacity!=='0.35'||s.cursor!=='not-allowed')) {console.log('FAIL disabled state',width,JSON.stringify(states));errors++;}
  }
  if (process.env.BAND_CLICKS && width===375 && ['messages','contacts','settings','canvas'].includes(id)) {
   const card=await page.locator('#modal-overlay.on #takeover-box').isVisible();
   console.log(`CLICK ${id} takeover-card-visible=${card}`);
   const hit=async selector=>{const locator=page.locator(selector).first(); const info=await locator.evaluate(e=>{const r=e.getBoundingClientRect();const t=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return {target:t?.outerHTML.slice(0,180),clear:e===t||e.contains(t)}});console.log('CLICK point',id,JSON.stringify(info));if(!info.clear) throw Error(`blocked ${id} ${JSON.stringify(info)}`); await locator.click({timeout:3000});};
   try {
    if(['messages','contacts'].includes(id)){await hit('.mobile-page.live .mh-search-bar'); const inp=page.locator('.mobile-page.live .mh-search-input'); await inp.waitFor({state:'visible',timeout:3000}); if(!await inp.evaluate(e=>e===document.activeElement)) throw Error('search input not focused');console.log(`PASS click ${id} search focused`);}
    if(id==='settings'){await hit('.mobile-page.live .mobile-back-btn'); await page.waitForFunction(()=>!document.querySelector('.mobile-page.live[data-page="settings"]'),null,{timeout:3000});console.log('PASS click settings back');}
    if(id==='canvas'){await hit('.mobile-page.live .mf-cv-stack');await page.waitForSelector('.mf-stack-view:not([hidden])',{timeout:3000});console.log('PASS click canvas stack');await hit('.mf-stack-x');await page.waitForSelector('.mf-stack-view:not([hidden])',{state:'hidden',timeout:3000});const n=await page.locator('.mobile-page.live .mf-strip-chip').count();await hit('.mobile-page.live .mf-cv-close');await page.waitForFunction(n=>document.querySelectorAll('.mobile-page.live .mf-strip-chip').length<n,n,{timeout:3000});console.log('PASS click canvas close');}
   } catch(e) { console.log(`FAIL click ${id}: ${e.message}`);await page.screenshot({path:join(out,`click-failure-${id}.png`)});errors++; }
  }
  if(['messages','contacts','canvas'].includes(id)) await page.screenshot({path:join(out,`${width}-${id}.png`)});
 }
 writeFileSync(join(out,`${width}.json`),JSON.stringify(rows,null,2));
 const values=rows.flatMap(r=>r.buttons.map(b=>({id:r.id,...b})));
 const expected=48;
 for(const b of values) if(Math.abs(b.box.w-expected)>.15||Math.abs(b.box.h-expected)>.15||!b.icon||Math.abs(b.icon.w-18)>.15||Math.abs(b.box.y-16)>.5||Math.min(b.box.x,b.box.right)>16.5 || b.box.x<0 || b.box.x+b.box.w>width+.15 || b.overflowX>0 || b.padding!=='0px' || !(b.radius==='50%' || parseFloat(b.radius)>=expected/2)) {console.log('FAIL geometry',JSON.stringify({width,...b,expected}));errors++;}
 for(const id of ['chat','messages','contacts','canvas','settings','tasks']) if(!rows.find(r=>r.id===id).buttons.length) {console.log('FAIL missing top button',id); errors++;}
 if(rows.find(r=>r.id==='canvas').buttons.length!==2) { console.log('FAIL canvas requires two top controls'); errors++; }
 const bottomControls=rows.find(r=>r.id==='canvas').bottomControls;
 if(!bottomControls.length) { console.log('FAIL canvas requires a visible bottom control for baseline measurement'); errors++; }
 for(const bottom of bottomControls) for(const top of values) {
  if(Math.abs(top.box.w-bottom.box.w)>.15 || Math.abs(top.box.h-bottom.box.h)>.15 || !top.icon || !bottom.icon || Math.abs(top.icon.w-bottom.icon.w)>.15 || Math.abs(top.icon.h-bottom.icon.h)>.15) {
   console.log('FAIL bottom baseline mismatch',JSON.stringify({width,id:top.id,top:top.box,topIcon:top.icon,bottom})); errors++;
  }
 }
 if(rows.find(r=>r.id==='workbench').buttons.length) { console.log('FAIL workbench unexpectedly has a top button'); errors++; }
 await context.close();
}
// Desktop uses the same real application assets and a routed, isolated backend fixture.
{
 const context=await browser.newContext({viewport:{width:1280,height:800},reducedMotion:'reduce'});
 const page=await context.newPage();
 await page.addInitScript(()=>{localStorage.setItem('nebflow_token','e2e-token');localStorage.setItem('stand_sw','0');});
 await page.route('**/*',route=>{
  const p=decodeURIComponent(new URL(route.request().url()).pathname);
  if(p.startsWith('/api/')) return route.fulfill({json:{}});
  const f=resolve(web,'.'+(p==='/'?'/index.html':p));
  if(!f.startsWith(web+'/')) return route.fulfill({status:403});
  const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.json':'application/json','.png':'image/png','.woff2':'font/woff2'};
  try{return route.fulfill({body:readFileSync(f),contentType:mime[extname(f)]||'application/octet-stream'});}catch{return route.fulfill({status:404});}
 });
 await page.routeWebSocket(/\/ws/,ws=>{
  const config=()=>ws.send(JSON.stringify({type:'configData',config:'{}',configured:true,onboarding:'done'}));
  const sessions=()=>ws.send(JSON.stringify({type:'sessionList',sessions:[{id:'band-test',name:'root',agentName:'Nebula'}],folders:[],activeId:'band-test'}));
  config();sessions();ws.onMessage(raw=>{let m;try{m=JSON.parse(raw)}catch{return}if(m.type==='getConfig')config();if(['getSessions','listSessions'].includes(m.type))sessions();if(m.type==='getHistory')ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0}));});
 });
 await page.goto('http://localhost:8899/');
 await page.waitForFunction(async()=>{const s=(await import('/js/state.js')).default;return s.ws?.readyState===1});
 const mainInput=await page.evaluate(()=>{const bar=document.querySelector('#input-bar'),input=bar.querySelector('textarea'),b=bar.getBoundingClientRect(),r=input.getBoundingClientRect(),s=getComputedStyle(bar);return {bar:b.width,input:r.width,padding:s.padding,gap:s.gap,children:[...bar.children].filter(e=>!e.contains(input)&&getComputedStyle(e).display!=='none'&&getComputedStyle(e).visibility!=='hidden'&&e.getBoundingClientRect().width>0).map(e=>({id:e.id,w:e.getBoundingClientRect().width,x:e.getBoundingClientRect().x}))}});
 console.log('DESKTOP COMPOSER',JSON.stringify(mainInput));
 const desktopExpected=mainInput.bar-mainInput.children.reduce((n,e)=>n+e.w,0)-2*8-mainInput.children.length*4-2;
 if(mainInput.input<desktopExpected-.15){console.log('FAIL desktop composer width',desktopExpected,JSON.stringify(mainInput));errors++;}
 await page.screenshot({path:join(out,'desktop-composer.png')});
 await page.evaluate(()=>window.dispatchEvent(new CustomEvent('workspace-open-item',{detail:{id:'desktop-band-document',title:'Canvas test',itemType:'markdown',absPath:'/fixture/desktop-band-document.md',pinned:false,content:'# Canvas'}})));
 await page.waitForSelector('.canvas-tab-pane .canvas-find-toggle',{timeout:12000});
 const askFab=page.locator('.ask-fab:not(.note-fab):visible').first();
 if(await askFab.count()) {
  await askFab.click({timeout:3000});
  await page.waitForSelector('.ask-dock:not(.closed) .ask-input:visible',{timeout:5000});
  await page.waitForFunction(()=>{const d=document.querySelector('.ask-dock:not(.closed) .ask-input textarea');return d&&d.getBoundingClientRect().width>10},{timeout:5000});
  const ask=await page.evaluate(()=>{const b=document.querySelector('.ask-dock:not(.closed) .ask-input'),i=b.querySelector('textarea'),br=b.getBoundingClientRect(),ir=i.getBoundingClientRect(),controls=[...b.children].filter(e=>!e.contains(i)&&e!==i&&getComputedStyle(e).display!=='none'&&e.getBoundingClientRect().width>0).map(e=>{const r=e.getBoundingClientRect();return {id:e.className,w:r.width,x:r.x,right:r.right}});return {bar:br.width,input:ir.width,ratio:ir.width/br.width,controls,padding:getComputedStyle(b).padding,gap:getComputedStyle(b).gap}});
  console.log('DESKTOP ASK',JSON.stringify(ask));
  const min=ask.bar-ask.controls.reduce((n,e)=>n+e.w,0)-12-4*ask.controls.length-2;
  if(ask.input<min-.15){console.log('FAIL desktop ask width',JSON.stringify(ask),min);errors++;}
 } else {console.log('FAIL desktop ask entry missing');errors++;}
 const rows=await page.evaluate(()=>[...document.querySelectorAll('#canvas-head-close, .canvas-tab-pane .canvas-find-toggle, .canvas-tab-pane .canvas-source-toggle, .canvas-tab-pane .canvas-ref-select')].map(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e),i=e.querySelector('svg')?.getBoundingClientRect();return {selector:e.className||e.id,w:r.width,h:r.height,icon:i?{w:i.width,h:i.height}:null,radius:s.borderRadius,padding:s.padding,visible:r.width>0&&r.height>0}}).filter(e=>e.visible));
 console.log('DESKTOP',JSON.stringify(rows));
 const desktopBottom=await page.locator('.canvas-tab-pane .canvas-find-toggle:visible').first().boundingBox();
 const desktopSource=await page.locator('.canvas-tab-pane .canvas-source-toggle:visible').first().boundingBox();
 const gap=desktopBottom.x-(desktopSource.x+desktopSource.width);
 console.log('DESKTOP GAP',gap);
 if(Math.abs(gap-8)>.15){console.log('FAIL desktop gap',gap);errors++;}
 if(!rows.some(r=>r.selector.includes('canvas-find-toggle'))) {console.log('FAIL desktop missing baseline');errors++;}
 for(const row of rows) if(Math.abs(row.w-40)>.15||Math.abs(row.h-40)>.15||!row.icon||Math.abs(row.icon.w-18)>.15||row.padding!=='0px'||!(row.radius==='50%'||parseFloat(row.radius)>=20)){console.log('FAIL desktop geometry',JSON.stringify(row));errors++;}
 await page.screenshot({path:join(out,'desktop.png')});
 await context.close();
}
await browser.close();
console.log(`${errors?'FAIL':'PASS'} geometry errors=${errors}`);
process.exitCode=errors?1:0;
