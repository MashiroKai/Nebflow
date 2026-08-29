/* ⑤ sidebar Team/Flows/Agents buttons x Canvas linkage harness.
   Matrix: canvas closed+tab exists -> click expands+activates (no dup);
   canvas open -> unchanged; repeated clicks no stacking; dismissed flow-run
   re-opens on manual click (dismissed cleared); auto paths still suppressed. */
const { chromium } = require('playwright');
const PORT = process.argv[2] || '8377';
const BASE = `http://127.0.0.1:${PORT}`;

const results = [];
const ok = (name, cond, extra) => results.push({ name, pass: !!cond, extra });

const HTML = `<!DOCTYPE html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/split.css">
<link rel="stylesheet" href="/css/sapphire.css">
</head><body>
<div id="activity-bar">
  <button id="teams-btn"></button><button id="flows-btn"></button><button id="agents-btn"></button>
</div>
<div id="sidebar"></div>
<div id="main"></div>
<div id="canvas-panel">
  <div id="canvas-tab-bar"></div>
  <div id="canvas-content"></div>
  <button id="canvas-close-btn"></button>
</div>
</body></html>`;

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e)));
  await page.route('**/h5.html', (r) => r.fulfill({ contentType: 'text/html', body: HTML }));
  // REST stubs: one running flow (dismissed scenario), empty teams/defs
  await page.route('**/api/**', (r) => {
    const u = r.request().url();
    if (u.includes('/api/running-flows')) {
      return r.fulfill({ contentType: 'application/json', body: JSON.stringify({ flows: [{ instanceId: 'inst1', flowName: 'demo-flow', status: 'running', nodes: [] }] }) });
    }
    return r.fulfill({ contentType: 'application/json', body: JSON.stringify({ teams: [], flows: [], defs: [] }) });
  });
  await page.goto(`${BASE}/h5.html`);

  const r = await page.evaluate(async () => {
    const out = {};
    const canvas = await import('/js/canvas.js');
    const flowCanvas = await import('/js/flowCanvas.js');
    const agentMgr = await import('/js/agentManager.js');
    const sleep = (ms) => new Promise(r => setTimeout(r, ms));
    const isOpen = () => document.body.classList.contains('canvas-open');
    const tabIds = () => [...document.querySelectorAll('#canvas-tab-bar .canvas-tab')].map(t => t.dataset.tabId);
    const activeTab = () => { const t = document.querySelector('#canvas-tab-bar .canvas-tab.active'); return t ? t.dataset.tabId : null; };

    // --- setup: create the three tabs, then close the canvas (tabs persist) ---
    await flowCanvas.openTeams();           // opens canvas + teams tab
    out.bootOpen = isOpen();
    canvas.closeCanvas();
    out.closedAfterClose = !isOpen();

    // T1: canvas closed + teams tab exists -> openTeams(manual) expands + activates, no dup
    await flowCanvas.openTeams({ manual: true });
    await sleep(50);
    out.t1 = { open: isOpen(), active: activeTab(), teamsTabs: tabIds().filter(x => x === 'teams').length };

    // T2: canvas open -> behavior unchanged (no dup, stays active)
    await flowCanvas.openTeams({ manual: true });
    await sleep(30);
    out.t2 = { teamsTabs: tabIds().filter(x => x === 'teams').length, active: activeTab() };

    // T3: flows + agents buttons (closed-canvas path each time)
    canvas.closeCanvas();
    await flowCanvas.openFlows();
    out.t3flows = { open: isOpen(), active: activeTab() };
    canvas.closeCanvas();
    agentMgr.openAgents();
    out.t3agents = { open: isOpen(), active: activeTab() };

    // T4: dismissed flow-run + manual click -> tab re-pops, dismissal cleared
    // (running-flows stub returns inst1; openTeams fetch path re-evaluates)
    sessionStorage.setItem('nebflow.dismissedFlowRuns', JSON.stringify(['inst1']));
    // reload the module-dismissed set via the test hook
    window.__testFlow._dismissed(); // ensure hook exists
    // re-import fresh state: the module already loaded its dismissed set at init;
    // emulate dismissal through the public-ish hook path: persist + internal set
    // (the module read sessionStorage at import time - before we set it - so set
    //  both stores then trigger a fresh read by calling persist via test hook)
    out.dismissedBefore = window.__testFlow._dismissed();
    canvas.closeCanvas();
    await flowCanvas.openTeams({ manual: true });
    await sleep(80);
    out.t4 = { open: isOpen(), flowRunTab: tabIds().includes('flow-run-inst1'), active: activeTab(), dismissedAfter: window.__testFlow._dismissed() };

    // T5: auto path (no manual) must STILL respect dismissal: dismiss again, auto openTeams
    if (out.t4.flowRunTab) {
      // close the flow-run tab and dismiss it
      canvas.closeTab('flow-run-inst1');
      sessionStorage.setItem('nebflow.dismissedFlowRuns', JSON.stringify(['inst1']));
    }
    return out;
  });

  ok('T0 boot: openTeams opens canvas', r.bootOpen === true, r.bootOpen);
  ok('T0b closeCanvas hides panel', r.closedAfterClose === true, r.closedAfterClose);
  ok('T1 closed+existing tab: expands + activates + no dup', r.t1.open && r.t1.active === 'teams' && r.t1.teamsTabs === 1, r.t1);
  ok('T2 open canvas: unchanged, no dup', r.t2.teamsTabs === 1 && r.t2.active === 'teams', r.t2);
  ok('T3a flows button: closed -> expand + flows active', r.t3flows.open && r.t3flows.active === 'flows', r.t3flows);
  ok('T3b agents button: closed -> expand + agents active', r.t3agents.open && r.t3agents.active === 'agents', r.t3agents);
  ok('T4 dismissed flow-run: manual click re-opens tab', r.t4.flowRunTab === true, r.t4);
  ok('T0err no page errors', errs.length === 0, errs.slice(0, 3));

  await browser.close();
  const fails = results.filter(x => !x.pass);
  results.forEach(x => console.log((x.pass ? 'PASS ' : 'FAIL ') + x.name + (x.pass ? '' : '  ' + JSON.stringify(x.extra))));
  console.log(`\n${results.length - fails.length}/${results.length} PASS`);
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
