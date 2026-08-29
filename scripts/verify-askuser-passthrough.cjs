// verify-askuser-passthrough.cjs — AskUser chat-input passthrough (author
// ruling 2026-08-29 23:50) frontend adaptation mock-WS E2E.
//
// Backend (660a0490): while an AskUser card is pending, a message typed into
// the input box is consumed as that tool call's answer; the backend then
// broadcasts {type:'askUserAnswered', sessionId, requestId, via:'chat-input'}
// so every client locks the card locally. The user's text lands as a normal
// user bubble (same as the card's Other path).
//
//   P1  askUser frame → card renders tagged with data-request-id, enabled
//   P2  askUserAnswered r1 → card locked (buttons disabled, confirm/cancel
//       hidden, .option-answer with localized label) + NOTHING sent back +
//       attention cleared (no other pending card)
//   P3  two pending cards (r2 oldest, r3 newest) → askUserAnswered r2 locks
//       ONLY r2; r3 stays enabled; attention NOT cleared (r3 still pending)
//   P4  unknown requestId → no-op, no error
//   P5  answer r3 on the card (click option + confirm) → askUserAnswer sent
//       with requestId; late askUserAnswered r3 → no-op (one .option-answer)
//   P6  zero page errors throughout

const { chromium } = require('playwright');
const PORT = process.argv[2] || '8381';
const BASE = `http://127.0.0.1:${PORT}`;

const results = [];
function ok(name, cond, detail) {
  results.push({ name, pass: !!cond, detail });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (detail !== undefined ? ' — ' + JSON.stringify(detail) : ''));
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e)));

  await page.route('**/api/nf-tasks*', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/api/agents', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ agents: [] }) }));
  await page.route('**/api/teams/mounted', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ teams: [] }) }));

  await page.addInitScript(() => {
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(url) { this.url = url; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
      send(d) {
        (window.__wsSent = window.__wsSent || []).push(d);
        try { const f = JSON.parse(d); if (f.type === 'ping') setTimeout(() => this.onmessage && this.onmessage({ data: '{"type":"pong"}' }), 0); } catch { /* not json */ }
      }
      close() { this.readyState = MockWS.CLOSED; }
      onopen = null; onmessage = null; onclose = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
    localStorage.setItem('nebflow_token', 'test-token');
    window.__attention = [];
    window.addEventListener('session-attention', (e) => window.__attention.push(e.detail));
  });

  await page.goto(BASE + '/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await page.waitForTimeout(300);

  const dispatch = (frame) => page.evaluate((f) => { window.__wsMock.onmessage({ data: JSON.stringify(f) }); }, frame);

  await dispatch({ type: 'sessionList', sessionId: 'sess-main', activeId: 'sess-main', sessions: [{ id: 'sess-main', agentName: 'Nebula', updatedAt: 0 }] });
  await dispatch({ type: 'configData', config: JSON.stringify({ configured: true }), onboarding: 'done', llm: {}, models: {} });
  await dispatch({ type: 'serverConfig', serverConfig: {}, schedule: null, stt: null, workSchedule: null });
  await dispatch({ type: 'agentList', agents: [] });
  await page.waitForTimeout(300);

  const askFrame = (rid, q) => ({ type: 'askUser', sessionId: 'sess-main', requestId: rid, items: [{ id: 'q1', question: q, options: ['A', 'B'] }] });
  const answeredFrame = (rid) => ({ type: 'askUserAnswered', sessionId: 'sess-main', requestId: rid, via: 'chat-input' });
  const cardState = (rid) => page.evaluate((id) => {
    const box = document.querySelector('.option-box[data-request-id="' + id + '"]');
    if (!box) return null;
    const btns = [...box.querySelectorAll('.option-btn')];
    const confirm = box.querySelector('.option-confirm');
    const cancel = box.querySelector('.option-cancel');
    const ans = box.querySelectorAll('.option-answer');
    return {
      btnCount: btns.length,
      allDisabled: btns.every(b => b.disabled),
      confirmHidden: confirm ? confirm.style.display === 'none' : null,
      cancelHidden: cancel ? cancel.style.display === 'none' : null,
      answerDivs: ans.length,
      answerText: ans.length ? ans[ans.length - 1].textContent : null,
    };
  }, rid);
  const sentOfType = (t) => page.evaluate((ty) =>
    (window.__wsSent || []).map(s => { try { return JSON.parse(s); } catch { return null; } }).filter(f => f && f.type === ty), t);

  // ---------- P1: card renders tagged + enabled ----------
  await dispatch(askFrame('r1', 'Pick one'));
  await page.waitForTimeout(200);
  let st = await cardState('r1');
  ok('P1 card renders with data-request-id + enabled buttons', !!st && st.btnCount >= 2 && st.allDisabled === false, st);

  // ---------- P2: passthrough frame locks the card, sends nothing ----------
  await dispatch(answeredFrame('r1'));
  await page.waitForTimeout(150);
  st = await cardState('r1');
  ok('P2 card locked (buttons disabled, confirm/cancel hidden, answer div)',
    !!st && st.allDisabled === true && st.confirmHidden === true && st.cancelHidden === true && st.answerDivs === 1, st);
  ok('P2 answer label localized (-> prefix, not raw key)',
    !!st && /^-> /.test(st.answerText || '') && !String(st.answerText).includes('chat.answeredViaChatInput'), st && st.answerText);
  const answersSent1 = await sentOfType('askUserAnswer');
  ok('P2 passthrough lock sends NO askUserAnswer frame', answersSent1.length === 0, answersSent1);
  const att1 = await page.evaluate(() => window.__attention.filter(d => d.sessionId === 'sess-main' && d.attention === false).length);
  ok('P2 attention cleared (no other pending card)', att1 === 1, att1);

  // ---------- P3: oldest-of-two locked, newest untouched ----------
  await dispatch(askFrame('r2', 'Second'));
  await dispatch(askFrame('r3', 'Third'));
  await page.waitForTimeout(200);
  await dispatch(answeredFrame('r2'));
  await page.waitForTimeout(150);
  const st2 = await cardState('r2');
  const st3 = await cardState('r3');
  ok('P3 oldest card (r2) locked', !!st2 && st2.allDisabled === true && st2.answerDivs === 1, st2);
  ok('P3 newest card (r3) stays enabled', !!st3 && st3.allDisabled === false && st3.answerDivs === 0, st3);
  const att2 = await page.evaluate(() => window.__attention.filter(d => d.sessionId === 'sess-main' && d.attention === false).length);
  ok('P3 attention NOT re-cleared while r3 pending', att2 === 1, att2);

  // ---------- P4: unknown requestId is a no-op ----------
  await dispatch(answeredFrame('rX'));
  await page.waitForTimeout(150);
  const st3b = await cardState('r3');
  ok('P4 unknown requestId no-op (r3 still enabled)', !!st3b && st3b.allDisabled === false && st3b.answerDivs === 0, st3b);

  // ---------- P5: card-path answer still works; late frame is a no-op ----------
  await page.evaluate(() => {
    const box = document.querySelector('.option-box[data-request-id="r3"]');
    box.querySelector('.option-btn').click(); // pick 'A'
  });
  await page.waitForTimeout(80);
  await page.evaluate(() => {
    const box = document.querySelector('.option-box[data-request-id="r3"]');
    box.querySelector('.option-confirm').click();
  });
  await page.waitForTimeout(120);
  const answersSent2 = await sentOfType('askUserAnswer');
  ok('P5 card confirm sends askUserAnswer with requestId r3',
    answersSent2.length === 1 && answersSent2[0].requestId === 'r3' && answersSent2[0].sessionId === 'sess-main', answersSent2);
  await dispatch(answeredFrame('r3')); // late/duplicate frame
  await page.waitForTimeout(120);
  const st3c = await cardState('r3');
  ok('P5 late askUserAnswered on answered card = no-op (single answer div)', !!st3c && st3c.answerDivs === 1, st3c);
  ok('P5 card-path answer label unchanged (not overwritten by passthrough label)',
    !!st3c && /A/.test(st3c.answerText || ''), st3c && st3c.answerText);

  // ---------- P6: no page errors ----------
  ok('P6 zero page errors', errs.length === 0, errs);

  // evidence screenshot: r1/r2 locked + r3 answered via card
  await page.evaluate(() => { document.getElementById('chat').scrollTop = 0; });
  await page.screenshot({ path: process.env.SHOT || '/tmp/askuser-passthrough.png' });

  await browser.close();
  const fails = results.filter(r => !r.pass);
  console.log(`\n${results.length - fails.length}/${results.length} PASS`);
  process.exit(fails.length ? 1 : 0);
})();
