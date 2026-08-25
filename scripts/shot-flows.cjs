const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  for (const colorScheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, colorScheme });
    await page.route('**/api/running-flows', r => r.fulfill({ status:200, contentType:'application/json', body: JSON.stringify({ flows: [] }) }));
    await page.route('**/api/teams/mounted', r => r.fulfill({ status:200, contentType:'application/json', body: JSON.stringify({ teams: [] }) }));
    await page.route('**/api/nf-tasks*', r => r.fulfill({ status:200, contentType:'application/json', body: '[]' }));
    await page.route('**/api/agents', r => r.fulfill({ status:200, contentType:'application/json', body: JSON.stringify({ agents: [] }) }));
    await page.addInitScript(() => {
      class MockWS { static CONNECTING=0; static OPEN=1; static CLOSING=2; static CLOSED=3; constructor(u){this.url=u;this.readyState=MockWS.CONNECTING;window.__wsMock=this;} send(){} close(){} onopen=null; onmessage=null; onclose=null; }
      Object.defineProperty(window,'WebSocket',{value:MockWS}); localStorage.setItem('nebflow_token','t');
    });
    await page.goto('http://127.0.0.1:8198/index.html', { waitUntil:'domcontentloaded' });
    await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
    await page.evaluate(() => { window.__wsMock.readyState=1; window.__wsMock.onopen && window.__wsMock.onopen(); });
    await page.waitForTimeout(300);
    const df = f => page.evaluate(fr => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
    await df({ type:'sessionList', sessionId:'sess-main', activeId:'sess-main', sessions:[{id:'sess-main',agentName:'N',updatedAt:0}] });
    await df({ type:'configData', config: JSON.stringify({configured:true}), onboarding:'done', llm:{}, models:{} });
    await df({ type:'serverConfig', serverConfig:{}, schedule:null, stt:null, workSchedule:null });
    await df({ type:'agentList', agents:[] });
    await page.waitForTimeout(300);
    await df({ type:'flowStarted', instanceId:'deck-64p', flowName:'deck-64p', description:'Optimize 64-page deck', entry:'a', nodes:[{nodeId:'a',agent:'w'},{nodeId:'b',agent:'w2'},{nodeId:'c',agent:'w3'},{nodeId:'d',agent:'w4'}], edges:[{from:'a',to:'b',condition:null}] });
    await page.waitForTimeout(300);
    // Mark 2 nodes done + 1 running for a realistic progress line.
    await df({ type:'flowProgress', instanceId:'deck-64p', nodeId:'a', status:'done' });
    await df({ type:'flowProgress', instanceId:'deck-64p', nodeId:'b', status:'done' });
    await df({ type:'flowProgress', instanceId:'deck-64p', nodeId:'c', status:'running' });
    await page.waitForTimeout(300);
    // Open the flows dropdown.
    await page.locator('#flows-indicator').click();
    await page.waitForTimeout(250);
    await page.screenshot({ path: `/tmp/flows-indicator-${colorScheme}.png` });
    await page.close();
  }
  await browser.close();
  console.log('screenshots saved');
})();
