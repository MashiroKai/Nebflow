// verify-ref-d4.cjs — #303 D4: refreshed history rebuilds a ref card.
//
// Full-page boot (mock WS) → set up a session chat container → call the
// restoreFromBackendHistory path with a message carrying a type:'ref'
// attachment → assert the rendered chat contains an .att-ref-card (not a
// bare [file] tag).

const { chromium } = require('playwright');
const BASE = 'http://127.0.0.1:8193';
const results = [];
function ok(name, cond, detail) { results.push({ name, pass: !!cond, detail }); console.log((cond ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : '')); }

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e)));
  await page.route('**/api/running-flows', r => r.fulfill({ status:200, contentType:'application/json', body: JSON.stringify({ flows: [] }) }));
  await page.route('**/api/teams/mounted', r => r.fulfill({ status:200, contentType:'application/json', body: JSON.stringify({ teams: [] }) }));
  await page.route('**/api/nf-tasks*', r => r.fulfill({ status:200, contentType:'application/json', body: '[]' }));
  await page.route('**/api/agents', r => r.fulfill({ status:200, contentType:'application/json', body: JSON.stringify({ agents: [] }) }));
  await page.addInitScript(() => {
    class MockWS { static CONNECTING=0; static OPEN=1; static CLOSING=2; static CLOSED=3; constructor(u){this.url=u;this.readyState=MockWS.CONNECTING;window.__wsMock=this;} send(){} close(){} onopen=null; onmessage=null; onclose=null; }
    Object.defineProperty(window,'WebSocket',{value:MockWS}); localStorage.setItem('nebflow_token','t');
  });
  await page.goto(BASE + '/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await page.waitForTimeout(300);
  const df = f => page.evaluate(fr => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
  await df({ type:'sessionList', sessionId:'sess-main', activeId:'sess-main', sessions:[{id:'sess-main',agentName:'N',updatedAt:0}] });
  await df({ type:'configData', config: JSON.stringify({configured:true}), onboarding:'done', llm:{}, models:{} });
  await df({ type:'serverConfig', serverConfig:{}, schedule:null, stt:null, workSchedule:null });
  await df({ type:'agentList', agents:[] });
  await page.waitForTimeout(300);

  const res = await page.evaluate(async () => {
    const { restoreFromBackendHistory } = await import('/js/persistence.js');
    const { default: state } = await import('/js/state.js');
    // Ensure the primary view's chat container is a real DOM node (already
    // present in index.html #chat); set the active session.
    const { chatViews, setActiveView } = await import('/js/chatView.js');
    const view = chatViews.primary;
    if (view) { view.sessionId = 'sess-main'; setActiveView(view); }
    else { return { noView: true }; }
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    const ref = {
      type: 'ref', refType: 'file', id: 'ref:file:x1', name: 'spec.pdf',
      source: { kind: 'workspace', path: 'docs/spec.pdf', fileName: 'spec.pdf', title: 'spec.pdf', mimeType: 'application/pdf' },
      anchor: { kind: 'page', pageStart: 3, pageEnd: 5 },
      meta: { mimeType: 'application/pdf', icon: 'file-text', typeLabel: 'PDF' },
      display: { label: 'spec.pdf', preview: 'Design spec abstract…', pageBadge: 'p.3–5' },
    };
    restoreFromBackendHistory([{ type: 'user', text: '返修说明', attachments: [ref], timestamp: Date.now() }], { scrollToBottom: false });
    return {
      hasRefCard: !!chat.querySelector('.att-ref-card'),
      hasRefBubble: !!chat.querySelector('.att-ref-bubble'),
      cardTitle: chat.querySelector('.att-ref-card-title')?.textContent || '',
      cardBadge: chat.querySelector('.att-ref-card-badge')?.textContent || '',
      hasBareFileTag: !!chat.querySelector('.att-file-tag'),
    };
  });

  ok('D4 restore renders ref card (not bare file tag)', res && res.hasRefCard && !res.hasBareFileTag, JSON.stringify(res));
  ok('D4 ref card bubble widened (.att-ref-bubble)', res && res.hasRefBubble);
  ok('D4 card title is the file label', res && res.cardTitle === 'spec.pdf', 'title=' + (res && res.cardTitle));
  ok('D4 card badge shows page anchor', res && res.cardBadge && res.cardBadge.includes('p.3'), 'badge=' + (res && res.cardBadge));
  ok('no page errors', errs.length === 0, errs.slice(0, 3).join(' | '));

  await browser.close();
  const failed = results.filter(r => !r.pass);
  console.log('\n=== SUMMARY ===');
  console.log(`${results.length - failed.length}/${results.length} PASS`);
  process.exit(failed.length ? 1 : 0);
})();
