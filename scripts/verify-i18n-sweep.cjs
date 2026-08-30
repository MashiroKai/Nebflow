// verify-i18n-sweep.cjs — 中文化补漏批验收
//   A1 实体类型名（Standalone Agents 组头→独立智能体）
//   A2 Flow 术语 zh 无「流程」（locale 值级）
//   A3 Flow Definitions 头中文化
//   A4 View DAG 按钮中文化
//   A5 nodes count 中文化（{count} 节点）
//   A6 节点状态列（solar-card-status running/completed/failed + terminal banner + manager/idle role）
//   A7 parity + 零 page error
const { chromium } = require('playwright');
const PORT = process.argv[2] || '8387';
const BASE = `http://127.0.0.1:${PORT}`;
const results = [];
const ok = (name, cond, extra) => { results.push({ name, pass: !!cond }); console.log((cond ? 'PASS ' : 'FAIL ') + name + (extra !== undefined ? ' — ' + JSON.stringify(extra) : '')); };

const HTML = `<!DOCTYPE html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="/css/base.css">
<link rel="stylesheet" href="/css/sapphire.css">
</head><body style="display:block !important">
<div id="canvas-panel"><div id="canvas-tab-bar"></div><div id="canvas-body"></div></div>
</body></html>`;

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(String(e)));
  await page.addInitScript(() => { localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('nebflow_token', 't'); });
  await page.route('**/h.html', (r) => r.fulfill({ contentType: 'text/html', body: HTML }));
  await page.goto(`${BASE}/h.html`);

  // A2 + A7 parity: locale level
  const loc = await page.evaluate(async () => {
    const zh = (await import('/js/locales/zh-CN.js')).default;
    const en = (await import('/js/locales/en.js')).default;
    const zhProc = Object.entries(zh).filter(([k, v]) => typeof v === 'string' && v.includes('流程')).map(([k]) => k);
    return { zhProc, zhN: Object.keys(zh).length, enN: Object.keys(en).length,
      standalone: zh['agents.group.standalone'], viewDag: zh['flows.viewDag'],
      defs: zh['flows.definitions'], nodes: zh['flows.nodesCount'],
      running: zh['flows.status.running'], completed: zh['flows.status.completed'], failed: zh['flows.status.failed'],
      tCompleted: zh['flows.completed'], tFailed: zh['flows.failed'] };
  });
  ok('A2 zh locale has zero 流程', loc.zhProc.length === 0, loc.zhProc);
  ok('A7 parity zh=en', loc.zhN === loc.enN, { zh: loc.zhN, en: loc.enN });
  ok('A1/A3/A4/A5 keys present', loc.standalone === '独立智能体' && loc.viewDag === '查看 DAG' && loc.defs === '工作流定义' && loc.nodes === '{count} 节点', loc);

  // A3/A4/A5/A6: render flow-def card + dag card via real modules
  const r = await page.evaluate(async () => {
    const out = {};
    try {
      const fl = await import('/js/flowList.js');
      out.flowListImported = true;
    } catch (e) { out.flowListErr = String(e); }
    const fd = await import('/js/flowDag.js');
    const rf = { instanceId: 'i1', flowName: 'test', status: 'running', nodes: [{ nodeId: 'n1', agent: 'a1', status: 'running' }], edges: [] };
    out.dagRunning = fd.dagCardHtml(rf);
    out.dagDone = fd.dagCardHtml({ ...rf, status: 'completed' }, { statusCls: 'completed', onClose: () => {} });
    out.dagFailed = fd.dagCardHtml({ ...rf, status: 'failed' }, { statusCls: 'failed', onClose: () => {} });
    return out;
  });
  ok('A6 running status zh', r.dagRunning && r.dagRunning.includes('运行中'), r.dagRunning && r.dagRunning.slice(0, 0));
  ok('A6 completed terminal banner zh', r.dagDone && r.dagDone.includes('工作流已完成'), r.dagDone && r.dagDone.match(/solar-terminal-text">([^<]*)</) ? r.dagDone.match(/solar-terminal-text">([^<]*)</)[1] : null);
  ok('A6 failed terminal banner zh', r.dagFailed && r.dagFailed.includes('工作流失败'), r.dagFailed && r.dagFailed.match(/solar-terminal-text">([^<]*)</) ? r.dagFailed.match(/solar-terminal-text">([^<]*)</)[1] : null);
  ok('A6 close button zh', r.dagDone && r.dagDone.includes('>关闭<'), r.dagDone && r.dagDone.match(/dag-card-close[^>]*>([^<]*)</) ? r.dagDone.match(/dag-card-close[^>]*>([^<]*)</)[1] : null);

  // flowTeams role/status
  const r2 = await page.evaluate(async () => {
    const ft = await import('/js/flowTeams.js');
    const flow = { name: 'teamX', agents: [{ name: 'Manager', manager: true, sessionId: 's1' }, { name: 'Worker', sessionId: 's2' }], flows: [] };
    const status = new Map([['s2', 'running']]);
    return ft.flowCardHtml(flow, status, new Set(), []);
  });
  ok('A6 manager role zh', r2.includes('管理者'), r2.match(/team-tile-role">([^<]*)</g));
  ok('A6 running agent status zh', r2.includes('运行中') && !r2.includes('>idle<') && !r2.includes('>running<'), r2.match(/team-tile-role">([^<]*)</g));
  ok('A6 summary zh (1 running)', r2.includes('1 运行中'), r2.match(/team-card-summary[^>]*>.*?<\/div>/));

  // zero page errors
  ok('A7 zero page errors', errs.length === 0, errs.slice(0, 3));

  await browser.close();
  const failed = results.filter(x => !x.pass);
  console.log(`\n${results.length - failed.length}/${results.length} PASS`);
  process.exit(failed.length ? 1 : 0);
})();
