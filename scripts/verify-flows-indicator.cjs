// verify-flows-indicator.cjs — #412 阶段 1 · Running-flows indicator mock-WS E2E.
//
// Full-page harness against the REAL app boot (index.html + main.js +
// flowCanvas.js + ws.js) with a mocked WebSocket and mocked REST snapshots.
// Verifies the 进展重入 mechanism:
//   A1  badge hidden when no flows
//   A2  flowStarted → badge visible count=1 + flow-run tab auto-opens
//   A3  close flow-run tab → dismiss recorded + persisted to sessionStorage
//   A4  badge stays visible while running; dropdown row renders
//   A5  click row → tab reopens + dismiss cleared + persist updated
//   A6  reload → badge visible (snapshot) + dismiss persists + NO auto-reopen
//   A7  manual re-open from indicator after reload still opens the tab
//   A8  flowCompleted → badge hidden (running count zero)
//
// REST mock (mockFlows) drives the /api/running-flows snapshot fetch so the
// reload-restore path is exercised without a live backend.

const { chromium } = require('playwright');
const BASE = 'http://127.0.0.1:8198';

const inst = () => ({
  instanceId: 'flow-abc',
  flowName: 'my-flow',
  description: 'smoke',
  entry: 'a',
  status: 'running',
  startedAt: Date.now() - 90000, // 1m30s ago
  nodes: [
    { nodeId: 'a', agent: 'worker', status: 'done' },
    { nodeId: 'b', agent: 'worker2', status: 'running' },
  ],
  edges: [{ from: 'a', to: 'b', condition: null }],
});
function flowStartedFrame() {
  return {
    type: 'flowStarted', instanceId: 'flow-abc', flowName: 'my-flow',
    description: 'smoke', entry: 'a',
    nodes: [{ nodeId: 'a', agent: 'worker' }, { nodeId: 'b', agent: 'worker2' }],
    edges: [{ from: 'a', to: 'b', condition: null }],
  };
}
function flowCompletedFrame(success) {
  return { type: 'flowCompleted', instanceId: 'flow-abc', success };
}

const results = [];
function ok(name, cond, detail) {
  results.push({ name, pass: !!cond, detail });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : ''));
}

(async () => {
  const browser = await chromium.launch();
  // mockFlows shared with the page route handlers.
  let mockFlows = [];

  async function bootPage() {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    page.on('pageerror', (e) => console.log('PAGEERROR ' + e));

    // REST mocks (fetch by JS).
    await page.route('**/api/running-flows', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ flows: mockFlows }) }));
    await page.route('**/api/teams/mounted', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ teams: [] }) }));
    await page.route('**/api/nf-tasks*', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
    await page.route('**/api/agents', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ agents: [] }) }));

    await page.addInitScript(() => {
      class MockWS {
        static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
        constructor(url) { this.url = url; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
        send() {}
        close() { this.readyState = MockWS.CLOSED; }
        onopen = null; onmessage = null; onclose = null;
      }
      Object.defineProperty(window, 'WebSocket', { value: MockWS });
      localStorage.setItem('nebflow_token', 'test-token');
    });

    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e)));
    await page.goto(BASE + '/index.html', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
    await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
    await page.waitForTimeout(300);

    const dispatchFrame = (frame) => page.evaluate((f) => {
      window.__wsMock.onmessage({ data: JSON.stringify(f) });
    }, frame);

    await dispatchFrame({ type: 'sessionList', sessionId: 'sess-main', activeId: 'sess-main', sessions: [{ id: 'sess-main', agentName: 'Nebula', updatedAt: 0 }] });
    await dispatchFrame({ type: 'configData', config: JSON.stringify({ configured: true }), onboarding: 'done', llm: {}, models: {} });
    await dispatchFrame({ type: 'serverConfig', serverConfig: {}, schedule: null, stt: null, workSchedule: null });
    await dispatchFrame({ type: 'agentList', agents: [] });
    await page.waitForTimeout(300);
    return { page, dispatchFrame, errs };
  }

  let p = await bootPage();
  let page = p.page, dispatchFrame = p.dispatchFrame;

  const indicator = page.locator('#flows-indicator');
  const count = page.locator('#flows-indicator .flows-count');
  const dropdown = page.locator('#flows-dropdown');
  const hasFlowRunTab = (id) => page.evaluate((tid) => !!document.querySelector(`.canvas-tab[data-tab-id="flow-run-${tid}"]`), id);
  // Real close: click the tab's X (canvas.js delegated close → closeTab →
  // dispatches 'canvas-tab-closed' on document). This actually removes the tab.
  const closeFlowRun = (id) => page.evaluate((tid) => {
    const btn = document.querySelector(`.canvas-tab[data-tab-id="flow-run-${tid}"] .canvas-tab-close`);
    if (btn) btn.click();
    return !!btn;
  }, id);

  // A1 — badge hidden when no running flows.
  ok('A1 badge hidden initial', await indicator.evaluate((el) => el.classList.contains('hidden')));

  // A2 — flowStarted → badge visible count=1 + tab auto-opens.
  await dispatchFrame(flowStartedFrame());
  await page.waitForTimeout(300);
  ok('A2 badge visible count=1', await indicator.evaluate((el) => !el.classList.contains('hidden')) && (await count.textContent()) === '1', 'count=' + (await count.textContent()));
  ok('A2 flow-run tab auto-opens', await hasFlowRunTab('flow-abc'));

  // A3 — close the flow-run tab → dismiss recorded + persisted to sessionStorage.
  await closeFlowRun('flow-abc');
  await page.waitForTimeout(200);
  ok('A3 dismiss recorded', await page.evaluate(() => window.__testFlow._dismissed().includes('flow-abc')));
  ok('A3 dismiss persisted to sessionStorage', await page.evaluate(() => (sessionStorage.getItem('nebflow.dismissedFlowRuns') || '').includes('flow-abc')));

  // A9 — v4 「关闭后不自动重开」: a later flowProgress must NOT auto-reopen the tab.
  await dispatchFrame({ type: 'flowProgress', instanceId: 'flow-abc', nodeId: 'b', status: 'done' });
  await page.waitForTimeout(250);
  ok('A9 no auto-reopen after dismiss (flowProgress)', !(await hasFlowRunTab('flow-abc')));

  // A4 — badge stays visible while running; dropdown row renders (still reopen-able).
  ok('A4 badge still visible while running', await indicator.evaluate((el) => !el.classList.contains('hidden')));
  // Mark node 'a' done via flowProgress so the dropdown shows real progress.
  await dispatchFrame({ type: 'flowProgress', instanceId: 'flow-abc', nodeId: 'a', status: 'done' });
  await page.waitForTimeout(150);
  await indicator.click();
  await page.waitForTimeout(200);
  ok('A4 dropdown open', !(await dropdown.evaluate((el) => el.classList.contains('hidden'))));
  ok('A4 row count=1', (await page.locator('#flows-dropdown .flows-row').count()) === 1);
  ok('A4 row name', (await page.locator('#flows-dropdown .flows-name').first().textContent()) === 'my-flow');
  ok('A4 row progress text', (await page.locator('#flows-dropdown .flows-progress').first().textContent()).includes('/2'));

  // A5 — click row → tab reopens + dismiss cleared + persist updated.
  await page.locator('#flows-dropdown .flows-row').first().click();
  await page.waitForTimeout(200);
  ok('A5 tab reopened', await hasFlowRunTab('flow-abc'));
  ok('A5 dismiss cleared', await page.evaluate(() => !window.__testFlow._dismissed().includes('flow-abc')));
  ok('A5 persist updated', await page.evaluate(() => !(sessionStorage.getItem('nebflow.dismissedFlowRuns') || '').includes('flow-abc')));
  ok('A5 dropdown closed after re-open', await dropdown.evaluate((el) => el.classList.contains('hidden')));

  // A6 — reload → snapshot restores running flows; dismiss persists; a live
  //      flowStarted arriving after reload must NOT auto-reopen (dismiss suppressed).
  mockFlows = [inst()]; // backend snapshot now has the running flow
  // Establish a dismissal before reload (the user closed it in a prior session).
  await closeFlowRun('flow-abc');
  await page.waitForTimeout(100);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
  await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
  await page.waitForTimeout(600);
  // badge visible (snapshot had 1 running flow).
  ok('A6 badge visible after reload', !(await indicator.evaluate((el) => el.classList.contains('hidden'))));
  ok('A6 dismissed persisted across reload', await page.evaluate(() => window.__testFlow._dismissed().includes('flow-abc')));
  // A live re-start/re-progress event arrives for the still-running flow after reload.
  await dispatchFrame(flowStartedFrame());
  await page.waitForTimeout(250);
  ok('A6 NO auto-reopen after reload (dismiss persisted)', !(await hasFlowRunTab('flow-abc')));
  ok('A6 badge still 1', (await count.textContent()) === '1');

  // A7 — manual re-open from indicator still works after reload.
  await indicator.click();
  await page.waitForTimeout(200);
  ok('A7 dropdown open after reload', !(await dropdown.evaluate((el) => el.classList.contains('hidden'))));
  await page.locator('#flows-dropdown .flows-row').first().click();
  await page.waitForTimeout(200);
  ok('A7 tab reopens after reload', await hasFlowRunTab('flow-abc'));

  // A8 — flowCompleted(success=true) → badge hidden (running count zero).
  mockFlows = []; // backend snapshot reflects the terminal flow (cleaned up)
  await dispatchFrame(flowCompletedFrame(true));
  await page.waitForTimeout(400);
  ok('A8 badge hidden after completed', await indicator.evaluate((el) => el.classList.contains('hidden')));
  ok('A8 dropdown closed after completed', await dropdown.evaluate((el) => el.classList.contains('hidden')));

  // Gate: no uncaught JS exceptions (page errors). REST 404 noise from
  // unmocked endpoints (static serve) is pre-existing and filtered.
  ok('A10 no page errors', p.errs.length === 0, p.errs.slice(0, 3).join(' | '));

  await page.close();
  await browser.close();

  const failed = results.filter(r => !r.pass);
  console.log('\n=== SUMMARY ===');
  console.log(`${results.length - failed.length}/${results.length} PASS`);
  process.exit(failed.length ? 1 : 0);
})();
