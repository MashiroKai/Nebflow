// Browser-enforced mobile composer geometry; route a synthetic backend, never a live instance.
import { chromium } from 'playwright-core';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve, extname, join } from 'node:path';

const web = resolve(process.env.MSS_WEB || 'src/main/resources/web');
const out = resolve(process.env.MSS_OUT || '/tmp/mobile-send-scale-evidence');
await mkdir(out, { recursive: true });
const browser = await chromium.launch();
let failed = false;
try {
  for (const mobile of [true, false]) for (const theme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: mobile ? {width: 390, height: 844} : {width: 1280, height: 860}, hasTouch: mobile, colorScheme: theme, deviceScaleFactor: 1 });
    await ctx.addInitScript(() => { localStorage.setItem('nebflow_token', 'fixture'); localStorage.setItem('nebflow_locale', 'zh-CN'); });
    const page = await ctx.newPage();
    await page.route('**/*', async route => {
      const p = decodeURIComponent(new URL(route.request().url()).pathname);
      if (p.startsWith('/api/')) return route.fulfill({json: {}});
      const f = resolve(join(web, p === '/' ? 'index.html' : p));
      if (!f.startsWith(web + '/')) return route.abort();
      try {
        const types = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml'};
        return route.fulfill({body: await readFile(f), contentType: types[extname(f)] || 'application/octet-stream'});
      } catch { return route.fulfill({status:404, body:''}); }
    });
    await page.routeWebSocket(/\/ws/, ws => {
      const config = () => ws.send(JSON.stringify({type:'configData',config:'{"features":{}}',configured:true,onboarding:'done'}));
      const sessions = () => ws.send(JSON.stringify({type:'sessionList',sessions:[{id:'fixture',name:'root',agentName:'Nebula'}],folders:[],activeId:'fixture'}));
      config(); sessions();
      ws.onMessage(raw => { let m; try { m = JSON.parse(raw); } catch { return; } if (m.type === 'getConfig') config(); if (m.type === 'getSessions' || m.type === 'listSessions') sessions(); if (m.type === 'getHistory') ws.send(JSON.stringify({type:'historyPage',sessionId:m.sessionId,messages:[],hasMore:false,offset:0,total:0})); });
    });
    await page.goto('http://fixture.invalid/index.html');
    await page.locator('#input').waitFor({state:'visible',timeout:20000});
    await page.locator('#send-btn').waitFor({state:'visible'});
    const data = await page.evaluate(() => {
      const measure = selector => { const el = document.querySelector(selector); if (!el) throw Error('missing '+selector); const r = el.getBoundingClientRect(), s = getComputedStyle(el); return {x:r.x,y:r.y,width:r.width,height:r.height,cx:r.x+r.width/2,cy:r.y+r.height/2,padding:s.padding,borderRadius:s.borderRadius,margin:s.margin,display:s.display}; };
      const send = document.querySelector('#send-btn');
      const pseudo = getComputedStyle(send, '::after');
      return {mobile:document.body.classList.contains('mobile-layer'),send:measure('#send-btn'),icon:measure('#send-btn svg, #send-btn i'),voice:measure('#voice-btn'),canvas:measure('#mic-canvas'),wrap:measure('#mic-orb-wrap'),row:measure('#input-bar'),hitInset:pseudo.inset, sendStyle: {width:getComputedStyle(send).width,height:getComputedStyle(send).height,padding:getComputedStyle(send).padding,borderRadius:getComputedStyle(send).borderRadius}, stopSize:getComputedStyle(document.querySelector('#stop-btn')).getPropertyValue('--ds-turn-btn-size')};
    });
    await page.screenshot({path:join(out,`${mobile?'mobile':'desktop'}-${theme}.png`)});
    console.log(JSON.stringify({view:mobile?'mobile':'desktop',theme,...data,ratio:data.send.height/data.voice.height,difference:data.send.height-data.voice.height}));
    if (mobile && (!data.mobile || Math.abs(data.send.width - data.voice.height * 0.625) > 0.5 || Math.abs(data.send.height - data.voice.height * 0.625) > 0.5 || Math.abs(parseFloat(data.stopSize) - data.send.height) > 0.5 || data.send.width - 2 * parseFloat(data.hitInset) < 43.5)) { console.error('FAIL mobile send/voice scale (expected 0.625 ±0.5px)'); failed = true; }
    if (!mobile && data.mobile) { console.error('FAIL desktop unexpectedly mobile'); failed = true; }
    await ctx.close();
  }
} finally { await browser.close(); }
if (failed) process.exitCode = 1;
else console.log('PASS mobile send/voice scale');
