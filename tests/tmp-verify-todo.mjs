// tmp-verify-todo.mjs — re-verify A3/A4/A6/A13 after qa-frontend 打回修复.
// Full-mock WS harness. Run: node tests/tmp-verify-todo.mjs (static server :8975)
import { chromium } from 'playwright';

const BASE = 'http://127.0.0.1:8975';
const SID = 'sess-1';
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

const now = Date.now();
const iso = ms => new Date(ms).toISOString();
const TASKS = [
  { id: 'h1', subject: '人类任务甲', status: 'pending', taskKind: 'human', createdAt: iso(now - 3600e3) },
  { id: 'a1', subject: 'agent任务一', status: 'pending', taskKind: 'agent', createdAt: iso(now - 3000e3) },
  { id: 'a2', subject: 'agent任务二', status: 'in_progress', taskKind: 'agent', createdAt: iso(now - 2400e3), activeForm: '进行中' },
  { id: 'a3', subject: 'agent失败今日', status: 'failed', taskKind: 'agent', createdAt: iso(now - 1800e3) },
  { id: 'c1', subject: '已完成不可见', status: 'completed', taskKind: 'agent', createdAt: iso(now - 1200e3), completedAt: iso(now - 600e3) },
];

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
await ctx.addInitScript(() => {
  localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
});
const page = await ctx.newPage();
page.on('pageerror', e => console.log('[pageerror]', e.message));
await page.route('**/api/**', r => r.fulfill({ json: {} }));

const clientFrames = [];
let serverWs = null;
await page.routeWebSocket(/\/ws/, ws => {
  serverWs = ws;
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    clientFrames.push(m);
    if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
  ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: SID, folders: [] }));
});
const send = obj => serverWs.send(JSON.stringify(obj));

await page.goto(BASE + '/index.html');
await page.waitForSelector('#task-list', { state: 'attached', timeout: 10000 });
await sleep(600);

// seed tasks → panel renders
send({ type: 'taskListUpdate', sessionId: SID, tasks: TASKS });
await sleep(300);

// ── A6: section headers ────────────────────────────────────────────────
const titles = await page.$$eval('#task-list .task-section-title', els => els.map(e => e.textContent));
ok('A6a both kinds → exactly 2 .task-section-title', titles.length === 2, JSON.stringify(titles));
ok('A6b human section first', titles[0] === '人类待办' && titles[1] === 'Agent 任务', JSON.stringify(titles));
const dualClass = await page.$eval('#task-list .task-section-title', e => e.classList.contains('task-group-header'));
ok('A6-dual element carries both impl + spec classes', dualClass);

// ── F5: circle 20×20 / glyph 14px / border 1.5px ──────────────────────
// NB: headless Chromium truncates computed fractional border widths to whole
// device pixels even at DPR=2 (plain 1.5px solid red computes as 1px) — so
// the 1.5px freeze value is asserted on the stylesheet rule, not computed.
const dims = await page.$eval('.task-item[data-task-id="h1"] .task-check', e => {
  const cs = getComputedStyle(e);
  return { w: cs.width, h: cs.height };
});
const rule15 = await page.evaluate(() => {
  for (const sheet of document.styleSheets) {
    let rules; try { rules = sheet.cssRules; } catch { continue; }
    for (const r of rules) {
      if (r.selectorText === '.task-check') return r.style.border || r.style.cssText || null;
    }
  }
  return null;
});
ok('F5a circle 20x20 + stylesheet border 1.5px', dims.w === '20px' && dims.h === '20px' && !!rule15 && rule15.includes('1.5px'), `${JSON.stringify(dims)} rule=${rule15}`);
const glyph = await page.$eval('.task-item[data-task-id="h1"] svg.task-check-glyph', e => getComputedStyle(e).width).catch(() => 'missing');
ok('F5b glyph svg 14px', glyph === '14px', glyph);

// visible rows: human group first (h1), then agent group (a2 in_progress
// 置顶, a1 pending, a3 failed 沉底); c1 completed never renders (§3/§3.3)
const rowIds = await page.$$eval('#task-list .task-item', els => els.map(e => e.dataset.taskId));
ok('A6c row order (human 组恒在上, 组内 in_progress 置顶/failed 沉底, completed 不渲染)',
  JSON.stringify(rowIds) === JSON.stringify(['h1', 'a2', 'a1', 'a3']), JSON.stringify(rowIds));

// ── A3: completeTask outbound frame ────────────────────────────────────
const statsBefore = (await page.textContent('#task-list .task-stats')).trim();
ok('A4-pre stats shows 3 待办 (failed 不计)', statsBefore.startsWith('3'), statsBefore);

await page.click('.task-item[data-task-id="h1"] .task-check');
await sleep(60); // well within the 600ms window
const f = clientFrames.find(m => m.type === 'completeTask');
ok('A3 completeTask frame {type,sessionId,taskId} only',
  f && f.sessionId === SID && f.taskId === 'h1' && Object.keys(f).sort().join(',') === 'sessionId,taskId,type',
  JSON.stringify(f));

// ── A4: optimistic -1 on stats ─────────────────────────────────────────
const statsAfter = (await page.textContent('#task-list .task-stats')).trim();
ok('A4 stats optimistic -1 within 600ms', statsAfter.startsWith('2'), statsAfter);

// completing/collapse/remove sequence
await sleep(500);
const h1Gone = await page.$('.task-item[data-task-id="h1"]');
ok('A4b row removed after fill→collapse→remove', !h1Gone);

// ── A13: taskError WITHOUT msgType → rollback (pendingComplete gate) ────
send({ type: 'taskError', error: 'complete failed', taskId: 'h1', sessionId: SID });
await sleep(300);
const h1Back = await page.$('.task-item[data-task-id="h1"]');
ok('A13a taskError (no msgType) rolls row back', !!h1Back);
const statsRollback = (await page.textContent('#task-list .task-stats')).trim();
ok('A13b stats restored to 3 after rollback', statsRollback.startsWith('3'), statsRollback);
const toast = await page.evaluate(() => document.body.textContent.includes('标记完成失败'));
ok('A13c rollback toast shown', toast);

// negative: taskError for unknown taskId → no effect
send({ type: 'taskError', error: 'unrelated', taskId: 'nope', sessionId: SID });
await sleep(200);
const rowsAfterNope = await page.$$eval('#task-list .task-item', els => els.length);
ok('A13d taskError unknown taskId → no spurious change', rowsAfterNope === 4, `rows=${rowsAfterNope}`);

// ── A6 single-kind → 0 headers ─────────────────────────────────────────
send({ type: 'taskListUpdate', sessionId: SID, tasks: [TASKS[1]] });
await sleep(300);
const singleTitles = await page.$$eval('#task-list .task-section-title', els => els.length);
ok('A6d single kind → 0 section titles', singleTitles === 0, `got ${singleTitles}`);

// ── F3/A12: 375px — header fits, archive button never hidden ──────────
// NB: with the sidebar expanded at 375px #main collapses to 0 width app-wide
// (pre-existing 本底, separate backlog — daemon-panel 400px/canvas offscreen).
// F3 的断言口径 = 面板可见场景（sidebar 收起）下 header 不新增溢出。
send({ type: 'taskListUpdate', sessionId: SID, tasks: TASKS });
await sleep(250);
await page.setViewportSize({ width: 375, height: 812 });
await page.evaluate(() => document.body.classList.add('sidebar-collapsed'));
// collapse is animated (--panel-duration 0.32s); freeze transitions + wait a frame
await page.addStyleTag({ content: '* { transition-duration: 0s !important; animation-duration: 0s !important; }' });
await sleep(500);
const geom = await page.evaluate(() => {
  const card = document.querySelector('#task-list .task-card');
  const hdr = document.querySelector('#task-list .task-header');
  const stats = document.querySelector('#task-list .task-stats');
  const main = document.querySelector('#main');
  return { mainW: main?.clientWidth, cardW: card?.clientWidth, hdrSw: hdr?.scrollWidth, hdrCw: hdr?.clientWidth,
           statsSw: stats?.scrollWidth, statsCw: stats?.clientWidth };
});
console.log('  [375px sidebar-collapsed]', JSON.stringify(geom));
// F3a 搁置（Manager 2026-08-18 裁定）：375px 下 sidebar-collapsed 后 #main
// 仍塌 0 属本底族 backlog（daemon-panel 400px 定宽/canvas 离屏同族），header
// 子树在容器 0 宽下无测量意义。F3 的 CSS 适配（≤480px gap/min-width/ellipsis）
// 已落码，待本底修复后补断言。
console.log('SKIP  F3a 375px header no internal overflow  — 本底族 backlog（#main=0），Manager 裁定搁置');
const archVisible = await page.$eval('#task-list .task-archive-btn', e => e.offsetParent !== null);
ok('F3b archive button visible at 375px (never hidden)', archVisible);
await page.screenshot({ path: '/tmp/todo-375.png' });
await page.setViewportSize({ width: 1440, height: 900 });

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURES`);
await browser.close();
process.exit(failures === 0 ? 0 : 1);
