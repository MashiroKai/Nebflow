// verify-unified-panel.cjs — 20260830 unified panel spec §9 A1-A12 + 0825 frozen regression
//   A1  primary 13px three-panel  A2 weight discipline  A3 baseline align
//   A4  time column tabular+mono  A5 time column flush right  A6 flow yellow
//   A7  agent blue distinct       A8 light contrast (table value)  A9 flow single-line + 6px dot
//   A10 retries chip              A11 zero new animation  A12 dual theme
//   R1-R8 0825 subagents frozen items (dot 6px + color ladder / kind chip / empty / stuck /
//         focus bar / reduced-motion / header L1 / done opacity)
// usage: node scripts/verify-unified-panel.cjs [port]  (port = static serve of web/ root, default 8390)
const { chromium } = require('playwright');

const PORT = process.argv[2] || '8390';
const BASE = `http://127.0.0.1:${PORT}/index.html`;
const results = [];
const ok = (name, cond, extra) => { results.push({ name, pass: !!cond }); console.log((cond ? 'PASS ' : 'FAIL ') + name + (cond ? '' : '  ' + JSON.stringify(extra))); };

const INIT = `
  localStorage.setItem('nebflow_token', 'harness-token');
  window.__wsSent = [];
  class MockWS {
    static OPEN = 1; static CONNECTING = 0; static CLOSING = 2; static CLOSED = 3;
    constructor(url) { this.url = url; this.readyState = 0; window.__wsMock = this; }
    send(d) { window.__wsSent.push(JSON.parse(d)); try { const m = JSON.parse(d); if (m.type==='ping'&&this.onmessage) this.onmessage({data:JSON.stringify({type:'pong'})}); } catch(e){} }
    close() { this.readyState = 3; if (this.onclose) this.onclose(); }
  }
  window.WebSocket = MockWS;
`;

async function boot(page) {
  await page.goto(BASE, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 8000 });
  await page.evaluate(() => {
    const ws = window.__wsMock; ws.readyState = 1; if (ws.onopen) ws.onopen();
    const feed = o => ws.onmessage({ data: JSON.stringify(o) });
    feed({ type: 'serverConfig', config: { configured: true, onboarding: 'done' } });
    feed({ type: 'configData', config: JSON.stringify({ providers: {} }) });
    feed({ type: 'sessionList', sessions: [{ id: 'sess1', title: 'S1', agentName: 'Nebula', createdAt: Date.now() }], activeId: 'sess1' });
    feed({ type: 'agentList', agents: [] });
  });
  await page.waitForTimeout(300);
}

async function seed(page) {
  // subagent rows (six states incl. retries + stuck + frozen + done)
  await page.evaluate(async () => {
    const state = (await import('/js/state.js')).default;
    const now = Date.now();
    state.sessionBgAgents['sess1'] = {
      'delegate-a1': { name: 'Explorer', task: '排查日志配对', sessionId: 'delegate-a1', kind: 'Delegate', startedAt: now - 42000, status: 'Processing', retryCount: 2, stuck: null, frozen: false, currentTool: 'Grep', done: false },
      'delegate-i2': { name: 'Coder', task: '并发门控', sessionId: 'delegate-i2', kind: 'Delegate', startedAt: now - 600000, status: 'Idle', retryCount: 0, stuck: null, frozen: false, currentTool: null, done: false },
      'subtask-s3': { name: 'w3', task: '渲染页', sessionId: 'subtask-s3', kind: 'SubTask', startedAt: now - 1800000, status: 'Processing', retryCount: 0, stuck: { idleSecs: 620, action: 'notice' }, frozen: false, currentTool: null, done: false },
      'team-f4': { name: 'Backend', task: 'WS', sessionId: 'team-s4', kind: 'Team', startedAt: now - 90000, status: 'Processing', retryCount: 0, stuck: null, frozen: true, currentTool: 'Edit', done: false },
      'delegate-d5': { name: 'qa', task: '复跑', sessionId: 'delegate-d5', kind: 'Delegate', startedAt: now - 3600000, status: 'Idle', retryCount: 0, stuck: null, frozen: false, currentTool: null, done: true },
    };
  });
  // running flow (2 nodes, 1 done)
  await page.evaluate(() => {
    const ws = window.__wsMock;
    ws.onmessage({ data: JSON.stringify({ type: 'flowStarted', instanceId: 'flow-abc', flowName: 'my-flow', sessionId: 'sess1', nodes: [{ nodeId: 'a', agent: 'w1' }, { nodeId: 'b', agent: 'w2' }] }) });
    ws.onmessage({ data: JSON.stringify({ type: 'flowProgress', instanceId: 'flow-abc', nodeId: 'a', status: 'done' }) });
  });
  // task rows (in_progress with desc + pending)
  await page.evaluate(() => {
    const ws = window.__wsMock;
    ws.onmessage({ data: JSON.stringify({ type: 'taskListUpdate', sessionId: 'sess1', tasks: [
      { id: 1, subject: '实现统一面板', description: '三面板 type ramp 对齐与计数器色', status: 'in_progress', createdAt: Date.now() - 500000, updatedAt: Date.now() - 60000 },
      { id: 2, subject: '排队任务', status: 'pending', createdAt: Date.now() - 400000, updatedAt: Date.now() - 400000 },
    ] }) });
  });
  await page.waitForTimeout(300);
  // open both dropdowns
  await page.evaluate(() => {
    document.getElementById('bgagent-indicator').click();
    document.getElementById('flows-indicator').click();
  });
  await page.waitForTimeout(300);
}

const PROBE = () => {
  const cs = (el) => el ? getComputedStyle(el) : null;
  const q = (s) => document.querySelector(s);
  const name = q('.bg-task-name'), fname = q('.flows-name'), tlabel = q('.task-item:not(.task-active) .task-label') || q('.task-label');
  const uptime = q('.bg-task-uptime'), elapsed = q('.flows-elapsed'), tlast = q('.task-last-active');
  const bgRow = q('.bg-task-row'), fRow = q('.flows-row');
  const retries = q('.bg-task-retries');
  const flowsInd = q('#flows-indicator'), bgInd = q('#bgagent-indicator');
  const rightDelta = (row, el, pad) => row && el ? (row.getBoundingClientRect().right - pad - el.getBoundingClientRect().right) : null;
  // task row: horizontal padding lives on the card, measure actual row-to-el delta
  const tRow = tlast ? tlast.closest('.task-item') : null;
  const anims = new Set();
  ['#bgagent-dropdown', '#flows-dropdown', '#task-list'].forEach(sel => {
    const root = q(sel);
    if (!root) return;
    [root, ...root.querySelectorAll('*')].forEach(el => {
      const a = getComputedStyle(el).animationName;
      if (a && a !== 'none') a.split(',').forEach(n => anims.add(n.trim()));
    });
  });
  return {
    name: name && { size: cs(name).fontSize, weight: cs(name).fontWeight, color: cs(name).color },
    fname: fname && { size: cs(fname).fontSize, weight: cs(fname).fontWeight },
    tlabel: tlabel && { size: cs(tlabel).fontSize, weight: cs(tlabel).fontWeight },
    tlabelActive: (() => { const el = q('.task-active .task-label'); return el ? cs(el).fontWeight : null; })(),
    header: (() => { const el = q('.bg-dropdown-header'); return el ? cs(el).fontWeight : null; })(),
    bgLine: (() => { const el = q('.bg-task-line'); return el ? cs(el).alignItems : null; })(),
    fLine: (() => { const el = q('.flows-line'); return el ? cs(el).alignItems : null; })(),
    uptime: uptime && { fvn: cs(uptime).fontVariantNumeric, fam: cs(uptime).fontFamily },
    elapsed: elapsed && { fvn: cs(elapsed).fontVariantNumeric, fam: cs(elapsed).fontFamily, inLine: !!(elapsed && elapsed.closest('.flows-line')) },
    tlast: tlast && { fvn: cs(tlast).fontVariantNumeric, fam: cs(tlast).fontFamily },
    dUptime: rightDelta(bgRow, uptime, 12),
    dElapsed: rightDelta(fRow, elapsed, 12),
    dTlast: tRow && tlast ? (tRow.getBoundingClientRect().right - tlast.getBoundingClientRect().right) : null,
    flowsColor: flowsInd && cs(flowsInd).color,
    bgColor: bgInd && cs(bgInd).color,
    retries: retries && { radius: cs(retries).borderRadius, bg: cs(retries).backgroundColor },
    fDot: (() => { const el = q('.flows-status'); return el ? cs(el).width : null; })(),
    bgDot: (() => { const el = q('.bg-task-status'); return el ? cs(el).width : null; })(),
    anims: [...anims].sort(),
    // 0825 frozen regression probes
    stateColors: [...document.querySelectorAll('.bg-task-state')].map(el => ({ cls: el.className, color: cs(el).color })),
    dotColors: [...document.querySelectorAll('.bg-task-status')].map(el => ({ cls: el.className, bg: cs(el).backgroundColor })),
    kind: (() => { const el = q('.bg-task-kind'); return el ? { bg: cs(el).backgroundColor, radius: cs(el).borderRadius, border: cs(el).borderTopWidth } : null; })(),
    doneOpacity: (() => { const el = q('.bg-task-row.done'); return el ? cs(el).opacity : null; })(),
    stuck: (() => { const el = q('.bg-task-stuck'); return el ? { color: cs(el).color, role: el.getAttribute('role') } : null; })(),
    infoGap: (() => { const el = q('.bg-task-info'); return el ? cs(el).rowGap : null; })(),
    descMargin: (() => { const el = q('.task-desc'); return el ? cs(el).marginTop : null; })(),
    statsFvn: (() => { const el = q('.task-stats'); return el ? cs(el).fontVariantNumeric : null; })(),
    cancelOpacity: (() => { const el = q('.flows-cancel'); return el ? cs(el).opacity : null; })(),
  };
};

const BASELINE_ANIMS = ['bg-pulse', 'bg-status-blink', 'spin', 'task-enter', 'task-zone-enter', 'task-leave', 'task-leaving'];

(async () => {
  const browser = await chromium.launch();
  for (const scheme of ['dark', 'light']) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme, deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', e => errs.push(e.message));
    await page.addInitScript(INIT);
    await boot(page);
    await seed(page);
    const p = await page.evaluate(PROBE);
    const tag = scheme === 'dark' ? 'D' : 'L';

    ok(tag + ' A1 primary 13px x3', p.name && p.fname && p.tlabel &&
      p.name.size === '13px' && p.fname.size === '13px' && p.tlabel.size === '13px', p);
    ok(tag + ' A2 weights (400/500/400, active 500, header 600)',
      p.name.weight === '400' && p.fname.weight === '500' && p.tlabel.weight === '400' &&
      p.tlabelActive === '500' && p.header === '600', p);
    ok(tag + ' A3 baseline align x2', p.bgLine === 'baseline' && p.fLine === 'baseline', p);
    ok(tag + ' A4 time column tabular (+mono x2)',
      /tabular-nums/.test(p.uptime.fvn) && /tabular-nums/.test(p.elapsed.fvn) && /tabular-nums/.test(p.tlast.fvn) &&
      /monospace/i.test(p.uptime.fam) && /monospace/i.test(p.elapsed.fam), p);
    ok(tag + ' A5 time flush right (subagent/flow ±1, task ±3 card pad)',
      p.dUptime !== null && Math.abs(p.dUptime) <= 1 && Math.abs(p.dElapsed) <= 1 && p.dTlast !== null && Math.abs(p.dTlast) <= 3,
      { dUptime: p.dUptime, dElapsed: p.dElapsed, dTlast: p.dTlast });
    const flowOk = scheme === 'dark' ? p.flowsColor === 'rgb(232, 183, 63)' : (p.flowsColor === 'rgb(138, 106, 0)' || p.flowsColor === 'rgb(122, 98, 0)');
    ok(tag + ' A6 flow yellow per table', flowOk, p.flowsColor);
    const agentOk = scheme === 'dark' ? p.bgColor === 'rgb(91, 127, 191)' : p.bgColor === 'rgb(70, 99, 156)';
    ok(tag + ' A7 agent blue per table + distinct from flow', agentOk && p.flowsColor !== p.bgColor, p);
    if (scheme === 'light') ok('L A8 light agent contrast (table value #46639c = 5.96:1)', p.bgColor === 'rgb(70, 99, 156)', p.bgColor);
    ok(tag + ' A9 flow single-line (elapsed in .flows-line) + 6px dot', p.elapsed.inLine && p.fDot === '6px', p);
    ok(tag + ' A10 retries chip (6px radius + non-transparent bg)', p.retries && p.retries.radius === '6px' && p.retries.bg !== 'rgba(0, 0, 0, 0)', p.retries);
    const newAnims = p.anims.filter(a => !BASELINE_ANIMS.includes(a));
    ok(tag + ' A11 zero new animation', newAnims.length === 0, { anims: p.anims, newAnims });
    // 0825 frozen regression
    ok(tag + ' R1 dots 6px + ladder (green/amber/red/sapphire/muted present)',
      p.bgDot === '6px' &&
      p.dotColors.some(d => d.cls.includes('bg-status-active')) &&
      p.dotColors.some(d => d.cls.includes('bg-status-idle') && d.bg === 'rgb(212, 160, 48)') &&
      p.dotColors.some(d => d.cls.includes('bg-status-stuck') && d.bg === 'rgb(244, 67, 54)') &&
      p.dotColors.some(d => d.cls.includes('bg-status-frozen') && (scheme === 'dark' ? d.bg === 'rgb(91, 127, 191)' : d.bg === 'rgb(70, 99, 156)')) &&
      p.dotColors.some(d => d.cls.includes('bg-status-done')), p.dotColors);
    ok(tag + ' R2 kind chip glass (6px + border + bg)', p.kind && p.kind.radius === '6px' && p.kind.bg !== 'rgba(0, 0, 0, 0)' && p.kind.border === '1px', p.kind);
    ok(tag + ' R4 stuck label red + role=alert', p.stuck && p.stuck.color === 'rgb(244, 67, 54)' && p.stuck.role === 'alert', p.stuck);
    ok(tag + ' R8 done row opacity 0.6', p.doneOpacity === '0.6', p.doneOpacity);
    ok(tag + ' S1 two-line gap 2px (subagent info + task desc)', p.infoGap === '2px' && p.descMargin === '2px', { infoGap: p.infoGap, descMargin: p.descMargin });
    ok(tag + ' S2 stats tabular', /tabular-nums/.test(p.statsFvn || ''), p.statsFvn);
    ok(tag + ' S3 cancel hidden by default (hover-reveal)', p.cancelOpacity === '0', p.cancelOpacity);
    ok(tag + ' zero page errors', errs.length === 0, errs);

    // cancel hover-reveal works (keyboard/pointer affordance intact)
    await page.hover('.flows-row');
    await page.waitForTimeout(100);
    const hov = await page.evaluate(() => getComputedStyle(document.querySelector('.flows-cancel')).opacity);
    ok(tag + ' S4 cancel reveals on hover', hov === '1', hov);

    // focus-visible left-edge bar (0825 R5)
    await page.evaluate(() => document.querySelector('.bg-task-row').focus());
    const focusBar = await page.evaluate(() => getComputedStyle(document.querySelector('.bg-task-row:focus')).boxShadow);
    ok(tag + ' R5 focus left-edge bar', /inset/.test(focusBar), focusBar);

    // screenshot: header dual badge + three panels same frame
    await page.screenshot({ path: `/tmp/unified-panel-${scheme}.png` });
    await ctx.close();
  }

  // reduced-motion context (0825 R6)
  const rmCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
  const rmPage = await rmCtx.newPage();
  await rmPage.addInitScript(INIT);
  await boot(rmPage);
  await seed(rmPage);
  const rm = await rmPage.evaluate(() => {
    const dot = document.querySelector('.bg-task-status.bg-status-active') || document.querySelector('.bg-task-status');
    return dot ? getComputedStyle(dot).animationName : null;
  });
  ok('RM R6 reduced-motion stops pulse', rm === 'none', rm);
  await rmCtx.close();

  // empty state (0825 R3) on a fresh page without seeds
  const eCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const ePage = await eCtx.newPage();
  await ePage.addInitScript(INIT);
  await boot(ePage);
  await ePage.evaluate(() => document.getElementById('bgagent-indicator').click());
  await ePage.waitForTimeout(200);
  const empty = await ePage.evaluate(() => !!document.querySelector('.bg-dropdown-empty'));
  ok('E R3 empty state renders', empty, empty);
  await eCtx.close();

  await browser.close();
  const failed = results.filter(r => !r.pass);
  console.log('\n=== ' + (results.length - failed.length) + '/' + results.length + ' PASS ===');
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error('HARNESS ERROR', e); process.exit(2); });
