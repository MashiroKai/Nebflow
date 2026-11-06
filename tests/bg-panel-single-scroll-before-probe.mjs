#!/usr/bin/env node
// bg-panel-single-scroll-before-probe.mjs — 一次性读数探针（改造前后同夹具对照用，
// 不属于回归套件；交付报告 §1「改造前滚动结构读数」即由本脚本跑出）。
// 与 tests/bg-panel-single-scroll.spec.mjs 同夹具同 harness，但只打印读数不断言。
// Run: node tests/bg-panel-single-scroll-before-probe.mjs
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'bgss-root-session';
const T0 = Date.now();
const AGENT_ROWS = 24;

function bgTasksFixture() {
  return { [SID]: [
    { taskId: 'job-1', description: 'Post-merge spec run in base repo', status: 'completed', startedAt: T0 - 90_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 5_000, outputLines: 42 } },
    { taskId: 'job-2', description: '单滚批：面板改造验收夹具任务二', status: 'running', startedAt: T0 - 30_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
    { taskId: 'job-3', description: '单滚批：面板改造验收夹具任务三', status: 'running', startedAt: T0 - 8_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
  ] };
}
function agentEntries() {
  const rows = [{ id: 'delegate-fixture-1', info: {
    name: 'delegate-fixture-1', kind: 'Delegate', sessionId: 'delegate-fixture-1',
    status: 'Processing', startedAt: T0 - 120_000, parentSessionId: SID,
    task: '单滚批夹具：delegate 组头（子行 4 条）' } }];
  for (let i = 1; i <= 4; i += 1) rows.push({ id: `subtask-fixture-${i}`, info: {
    name: `subtask-fixture-${i}`, kind: 'SubTask', sessionId: `subtask-fixture-${i}`,
    status: 'Processing', startedAt: T0 - (60_000 + i * 1_000), parentSessionId: 'delegate-fixture-1',
    task: `单滚批夹具：派生第 ${i} 条子行` } });
  for (let i = 1; i <= 19; i += 1) rows.push({ id: `node-fixture-${i}`, info: {
    name: `node-fixture-${i}`, kind: 'AgentFlow', sessionId: `node-fixture-${i}`,
    status: i % 3 === 0 ? 'Completed' : 'Processing', startedAt: T0 - (i * 7_000), parentSessionId: SID,
    task: `单滚批夹具：平铺第 ${i} 条（AgentFlow 节点行）` } });
  return rows;
}

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 375, height: 812 }, colorScheme: 'dark', deviceScaleFactor: 2,
  hasTouch: true, locale: 'zh-CN',
});
const page = await ctx.newPage();
await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
});
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p.startsWith('/api/')) {
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  }
  const file = normalize(join(WEB, p));
  if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
  try {
    return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
  } catch { return route.fulfill({ status: 404, body: 'not found' }); }
});
await page.routeWebSocket(/\/ws/, (ws) => {
  const sendConfig = () => ws.send(JSON.stringify({
    type: 'configData', config: '{}', configured: true, onboarding: 'done',
    selfDevice: { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' } }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList',
    sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
    folders: [], activeId: SID }));
  const sendBg = (tasks) => ws.send(JSON.stringify({ type: 'activeBgTasks', tasks }));
  sendConfig(); sendSessions(); sendBg(bgTasksFixture());
  ws.onMessage((raw) => {
    let parsed; try { parsed = JSON.parse(raw); } catch { return; }
    if (parsed.type === 'getConfig') sendConfig();
    else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
    else if (parsed.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
    }
  });
});
await page.goto('http://localhost:1/');
await page.waitForFunction(async (rootSid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 15000 });
await page.waitForTimeout(300);

const seeded = await page.evaluate(({ sid, rows }) => import('/js/state.js').then((m) => {
  const bucket = {};
  for (const r of rows) bucket[r.id] = r.info;
  m.default.sessionBgAgents[sid] = bucket;
  m.default.updateBgAgentIndicator();
  return Object.keys(m.default.sessionBgAgents[sid]).length;
}), { sid: SID, rows: agentEntries() });
console.log('[probe] seeded agent rows = ' + seeded + ' (expect ' + AGENT_ROWS + ')');

await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
await page.locator('.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="background"]').click();
await page.waitForFunction(() => {
  const el = document.querySelector('.mobile-page[data-page="background"]');
  return !!el && el.classList.contains('live');
}, null, { timeout: 10000 });
await page.waitForSelector('.mobile-page[data-page="background"] .mobile-page-body > #bg-dropdown .bg-section-agents .bg-dropdown-list .bg-task-row', { timeout: 10000 });
await page.waitForTimeout(400);

const top = await page.evaluate(() => {
  const sec = document.querySelector('.mobile-page[data-page="background"]');
  const body = sec.querySelector('.mobile-page-body');
  const carrier = body.querySelector('#bg-dropdown');
  const over = (el) => {
    const cs = getComputedStyle(el);
    return (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight - el.clientHeight > 0;
  };
  const scrollers = [];
  for (const el of [body, ...body.querySelectorAll('*')]) {
    if (!over(el)) continue;
    scrollers.push({ sel: el.id ? '#' + el.id : '.' + String(el.className || el.tagName).split(' ')[0],
      overflowY: getComputedStyle(el).overflowY, surplus: el.scrollHeight - el.clientHeight,
      scrollHeight: el.scrollHeight, clientHeight: el.clientHeight });
  }
  const cs = getComputedStyle(carrier);
  return {
    scrollerCount: scrollers.length,
    scrollers,
    carrier: { overflowY: cs.overflowY, overflowX: cs.overflowX, overscroll: cs.overscrollBehaviorY,
      iOSMomentum: cs.webkitOverflowScrolling || cs.getPropertyValue('-webkit-overflow-scrolling'),
      scrollHeight: carrier.scrollHeight, clientHeight: carrier.clientHeight,
      surplus: carrier.scrollHeight - carrier.clientHeight,
      paddingBottom: cs.paddingBottom },
    lists: [...carrier.querySelectorAll('.bg-dropdown-list')].map((l) => ({
      section: l.parentElement.className, overflowY: getComputedStyle(l).overflowY,
      flex: getComputedStyle(l).flex, maxHeight: getComputedStyle(l).maxHeight,
      surplus: l.scrollHeight - l.clientHeight })),
  };
});
console.log('[PROBE top] ' + JSON.stringify(top, null, 1));

const perList = await page.evaluate(() => {
  const sec = document.querySelector('.mobile-page[data-page="background"]');
  const carrier = sec.querySelector('.mobile-page-body > #bg-dropdown');
  const before = carrier.scrollTop;
  const out = [...carrier.querySelectorAll('.bg-dropdown-list')].map((l) => {
    l.scrollTop = 40;
    return { surplus: l.scrollHeight - l.clientHeight, moved: l.scrollTop };
  });
  return { carrierScrollTopBefore: before, carrierScrollTopAfter: carrier.scrollTop, lists: out };
});
console.log('[PROBE write scrollTop=40 on each list] ' + JSON.stringify(perList));

const bottom = await page.evaluate(() => {
  const sec = document.querySelector('.mobile-page[data-page="background"]');
  const body = sec.querySelector('.mobile-page-body');
  const carrier = body.querySelector('#bg-dropdown');
  const over = (el) => {
    const cs = getComputedStyle(el);
    return (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight - el.clientHeight > 0;
  };
  const hit = [body, ...body.querySelectorAll('*')].filter(over);
  for (const el of hit) { el.scrollTop = el.scrollHeight; el.dispatchEvent(new Event('scroll')); }
  body.scrollTop = body.scrollHeight;
  const dock = document.getElementById('mobile-dock');
  const dockTop = Math.round(dock.getBoundingClientRect().top);
  const sections = [...carrier.querySelectorAll('.bg-section')].filter((s) => s.getClientRects().length);
  const lastSec = sections[sections.length - 1];
  const rows = [...lastSec.querySelectorAll('.bg-task-row')].filter((r) => r.getClientRects().length);
  const last = rows[rows.length - 1];
  const lb = last.getBoundingClientRect();
  const bodyB = body.getBoundingClientRect();
  const rowHs = [...carrier.querySelectorAll('.bg-task-row')].filter((r) => r.getClientRects().length)
    .map((r) => Math.round(r.getBoundingClientRect().height));
  return {
    scrollTargets: hit.map((el) => el.id || String(el.className).split(' ')[0]),
    dockTop, pageBodyBottom: Math.round(bodyB.bottom),
    lastRowText: (last.textContent || '').trim().slice(0, 40),
    lastRowTop: Math.round(lb.top), lastRowBottom: Math.round(lb.bottom),
    lastRowVisible: lb.top >= 0 && lb.bottom <= window.innerHeight,
    gapToDock: Math.round(dockTop - lb.bottom),
    minRowH: Math.min(...rowHs), rowCount: rowHs.length,
    carrierPadBottom: parseFloat(getComputedStyle(carrier).paddingBottom) || 0,
    bodyScrollWidth: body.scrollWidth, bodyClientWidth: body.clientWidth,
    carrierScrollWidth: carrier.scrollWidth, carrierClientWidth: carrier.clientWidth,
    groupTitles: [...carrier.querySelectorAll('.mh-bg-grp, .bg-dropdown-header')]
      .filter((el) => el.getClientRects().length).map((el) => el.textContent.trim()),
    sectionCount: sections.length,
    overlap: (() => {
      let n = 0;
      for (const row of carrier.querySelectorAll('.bg-task-row')) {
        const main = row.querySelector('.bg-task-main'); const sub = row.querySelector('.bg-task-subline');
        if (!main || !sub) continue;
        const a = main.getBoundingClientRect(); const b = sub.getBoundingClientRect();
        if (a.height === 0 || b.height === 0) continue;
        if (b.top < a.bottom - 0.5) n += 1;
      }
      return n;
    })(),
  };
});
console.log('[PROBE bottom (all scrollers at max)] ' + JSON.stringify(bottom));

await browser.close();
