// cognition-phase2.spec.mjs — 认知二期产品四件验收（认知二期产品批，作者批
// 2026-10-30 14:43 入台账；设计锚 docs/design/20261030_human-readable-presentation
// .zh-CN.md §6/§5 P6/§6.5）。
//
// 四件：
//   ① 选档器 — 设置面板五档选档 + 档参数逐项覆盖；切档即时生效（human 过滤层
//      折叠默认消费：备考冲刺档 Findings 默认展开，其余档折叠）
//   ② 角标可行动判据（P6）— 提醒徽标只报待办+已超时（未来任务不上徽标）；
//      messages 徽标 = 可行动聚合（actionable.js 单件断言）
//   ③ 打卡 UI — 待办组置顶 + 打卡圆钮发 completeTodo（留痕销账）+ 取消 × +
//      缺卡调整建议（missCount ≥ 3）
//   ④ 周报浮出面（收尾回填 §6.4 接合面）— weeklyReportReady 帧 → 通知横幅退役，
//      改为收件箱刷新：周报 todo 条目落待办组置顶（打卡销账 = P2 闭合回路），
//      徽标 +1（可行动量）
//
// 被测真实链路：照 human-face-render.spec.mjs 同一套替身纪律（静态服务真
// web/ 树 + WS 替身 + ticket mock），前端零替身。
//
// 运行：node tests/cognition-phase2.spec.mjs
// 可选 env：PORT=8173、SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8173);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, 'evidence', '20261030_cognition-phase2', 'shots');
const VIEWPORT = { width: 1280, height: 900 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────── WS 替身夹具 ─────────────── */
const TODO_ID = 'td-0001';
const nowMs = Date.now();
const FIXTURE_TASKS = [
  // 待办：缺卡 4 次（≥阈值 ⇒ 行内调整建议）
  { id: TODO_ID, content: '今天：深蹲 5×5', triggerAt: nowMs + 86_400_000, createdAt: nowMs - 3 * 86_400_000,
    triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true, kind: 'todo', missCount: 4 },
  // 未来定时任务：不可行动（未来时间 ≠ 要动手）⇒ 不上徽标
  { id: 'tk-0002', content: '例行巡检', triggerAt: nowMs + 3_600_000, createdAt: nowMs,
    triggered: false, triggeredAt: null, referencePath: null, repeat: 'daily', enabled: true, kind: 'task', missCount: 0 },
];
// §6.4 回填：引擎侧 weeklyReportReady 时已 upsert 的周报待办条目帧形
// （content = 反悬念结论 + 报告路径 = 来源动作；referencePath 落报告现场）。
const WEEKLY_TODO = {
  id: 'td-week', content: '打卡周报 2026-W44 已生成：打卡 5/6，趋势↑ — 周报：/tmp/x/REPORT.md（读后可打卡销账）',
  triggerAt: nowMs + 86_400_000, createdAt: nowMs, triggered: false, triggeredAt: null,
  referencePath: '/tmp/x/REPORT.md', repeat: null, enabled: true, kind: 'todo', missCount: 0,
};
// listScheduledTasks 替身应答 = 可变列表（R6 切换为「周报入账后」的库态）。
let currentTasks = FIXTURE_TASKS;

const PROFILE_DEFS = [
  { key: 'adhd-friendly', maxLines: 8, keyNumbers: 4, foldDefault: 'fold', imageDensity: 'high', reminderCadence: 'single', supervision: 'fine', pendingStyle: 'single', teachingAllowed: false },
  { key: 'deep-work', maxLines: 4, keyNumbers: 3, foldDefault: 'all-fold', imageDensity: 'low', reminderCadence: 'quiet', supervision: 'weekly', pendingStyle: 'batch', teachingAllowed: false },
  { key: 'quick-browse', maxLines: 3, keyNumbers: 4, foldDefault: 'strong', imageDensity: 'medium', reminderCadence: 'aggregate', supervision: 'coarse', pendingStyle: 'none', teachingAllowed: false },
  { key: 'expert-minimal', maxLines: 3, keyNumbers: 2, foldDefault: 'anomaly-top', imageDensity: 'low', reminderCadence: 'quiet', supervision: 'exception', pendingStyle: 'blocking', teachingAllowed: false },
  { key: 'exam-sprint', maxLines: 6, keyNumbers: 5, foldDefault: 'expand-findings', imageDensity: 'high', reminderCadence: 'single', supervision: 'fine', pendingStyle: 'deadline', teachingAllowed: true },
];

const FENCE_MD = [
  '```human',
  '## 印象',
  '结论一句话。',
  '',
  '**关键数字**：打卡 3/4',
  '```',
  '',
  '## 发现',
  '',
  '机器面细节：逐日记录与读数照常写全。',
  '',
].join('\n');

/* ─────────────── 静态服务（真 web/ 树；自有端口） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ─────────────── 应用引导 ─────────────── */
async function boot(browser, captures) {
  const ctx = await browser.newContext({ viewport: VIEWPORT });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(30000);
  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 300)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(String(m.text()).slice(0, 300)); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  await installTicketMock(page);

  let wsRef = null;
  await page.routeWebSocket(/\/ws/, (ws) => {
    wsRef = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type === 'getConfig') {
        return ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
      }
      if (m.type === 'listScheduledTasks') {
        captures.listRequests.push(m);
        return ws.send(JSON.stringify({ type: 'scheduledTaskList', sessionId: m.sessionId, tasks: currentTasks }));
      }
      if (m.type === 'getPresentationProfile') {
        return ws.send(JSON.stringify({ type: 'presentationProfileData', profile: { current: 'expert-minimal', profiles: PROFILE_DEFS, overrides: {} } }));
      }
      if (m.type === 'completeTodo') captures.completeTodo.push(m);
      if (m.type === 'setPresentationProfile') captures.setProfile.push(m);
      if (m.type === 'setProfileParamOverride') captures.setOverride.push(m);
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await sleep(500);
  return { ctx, page, pageErrors, consoleErrors, push: (obj) => wsRef && wsRef.send(JSON.stringify(obj)) };
}

/* ─────────────── 断言 ─────────────── */
let failed = 0;
const ok = (cond, label) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`);
  if (!cond) failed++;
};

const browser = await chromium.launch();
const captures = { completeTodo: [], setProfile: [], setOverride: [], listRequests: [] };
try {
  const { page, pageErrors, consoleErrors, push } = await boot(browser, captures);

  // ── R1 打卡 UI：待办组置顶 + 缺卡建议 + 取消钮 ─────────────────────
  await page.click('#reminder-btn');
  await page.waitForSelector('#reminder-panel.open', { timeout: 5000 });
  await sleep(300);
  const r1 = await page.evaluate(() => {
    const body = document.querySelector('#reminder-panel .reminder-panel-body');
    if (!body) return null;
    const groupLabel = body.querySelector('.mh-rd-grp');
    const rows = Array.from(body.querySelectorAll('.reminder-row'));
    const todoRow = rows.find(r => r.dataset.id === 'td-0001');
    const timedRow = rows.find(r => r.dataset.id === 'tk-0002');
    return {
      groupText: groupLabel ? groupLabel.textContent : '',
      todoFirst: rows.length > 0 && rows[0].dataset.id === 'td-0001',
      hasCheckin: !!(todoRow && todoRow.querySelector('.reminder-circle[data-action="checkin"]')),
      hasCancel: !!(todoRow && todoRow.querySelector('.reminder-cancel')),
      missText: todoRow ? (todoRow.querySelector('.reminder-miss-suggest') || {}).textContent || '' : '',
      timedHasCancel: !!(timedRow && timedRow.querySelector('.reminder-cancel')),
      timedAction: timedRow ? (timedRow.querySelector('.reminder-circle') || {}).dataset.action : '',
    };
  });
  ok(r1 && r1.groupText.includes('打卡'), 'R1a 待办组签「打卡」在');
  ok(r1 && r1.todoFirst, 'R1b 待办组置顶');
  ok(r1 && r1.hasCheckin, 'R1c 待办圆钮 = 打卡销账（data-action=checkin）');
  ok(r1 && r1.hasCancel && !r1.timedHasCancel, 'R1d 待办有取消 ×，定时任务无');
  ok(r1 && r1.missText.includes('4') && r1.missText.includes('建议'), 'R1e 缺卡调整建议在（连续 4 次）');
  ok(r1 && r1.timedAction === 'delete', 'R1f 定时任务圆钮仍走 delete（零回归）');

  // ── R2 可行动徽标：未来任务不上徽标 ⇒ 1（仅待办），非 2 ─────────────
  const badge = await page.evaluate(() => {
    const b = document.querySelector('#reminder-btn .reminder-badge');
    return b ? b.textContent : '0';
  });
  ok(badge === '1', `R2 提醒徽标只报可行动量（=${badge}，未来定时任务排除）`);

  // ── R3 打卡发送 completeTodo（留痕销账腿） ─────────────────────────
  await page.click('.reminder-row[data-id="td-0001"] .reminder-circle');
  await sleep(400);
  ok(captures.completeTodo.length === 1 && captures.completeTodo[0].id === TODO_ID,
    'R3 completeTodo 帧带正确 id');
  const afterCheckin = await page.evaluate(() => {
    const b = document.querySelector('#reminder-btn .reminder-badge');
    return b ? b.textContent : '0';
  });
  ok(afterCheckin === '0', `R3b 打卡后徽标归零（=${afterCheckin}）`);

  // ── R4 选档器：设置面板区块 + 切档帧 + 渲染即时生效 ────────────────
  await page.click('#settings-btn');
  await page.waitForSelector('#profile-section', { timeout: 5000 });
  const r4a = await page.evaluate(() => {
    const sel = document.getElementById('cfg-profile-select');
    const rows = document.querySelectorAll('#profile-section .settings-row').length;
    return { options: sel ? sel.options.length : 0, rows };
  });
  ok(r4a.options === 5, `R4a 选档器五档（=${r4a.options}）`);
  ok(r4a.rows >= 8, `R4b 档参数逐项覆盖行在（=${r4a.rows} 行）`);

  await page.selectOption('#cfg-profile-select', 'exam-sprint');
  await sleep(300);
  ok(captures.setProfile.length === 1 && captures.setProfile[0].profile === 'exam-sprint',
    'R4c 切档发 setPresentationProfile 帧');

  // 引擎回执 ⇒ 渲染面即时生效。围栏语义定案（作者令 2026-10-30 20:13）：
  // 围栏在时框外一律默认折叠（可展开），五档无渲染豁免——备考冲刺档
  // foldDefault=expand-findings 的框外不折豁免退役。
  push({ type: 'presentationProfileChanged', profile: { current: 'exam-sprint', profiles: PROFILE_DEFS, overrides: {} } });
  await sleep(200);
  const examRender = await page.evaluate(async (md) => {
    const utils = await import('/js/utils.js');
    const host = document.createElement('div');
    document.body.appendChild(host);
    host.innerHTML = utils.renderMarkdownWithMath(md);
    return {
      cards: host.querySelectorAll('.nf-human-card').length,
      details: host.querySelectorAll('.nf-human-details').length,
    };
  }, FENCE_MD);
  ok(examRender.cards === 1 && examRender.details === 1,
    `R4d 备考冲刺档框外也折叠（卡=${examRender.cards} 抽屉=${examRender.details}）——围栏语义定案，无档豁免`);

  push({ type: 'presentationProfileChanged', profile: { current: 'expert-minimal', profiles: PROFILE_DEFS, overrides: {} } });
  await sleep(200);
  const expertRender = await page.evaluate(async (md) => {
    const utils = await import('/js/utils.js');
    const host = document.createElement('div');
    document.body.appendChild(host);
    host.innerHTML = utils.renderMarkdownWithMath(md);
    return host.querySelectorAll('.nf-human-details').length;
  }, FENCE_MD);
  ok(expertRender === 1, `R4e 专家极简档框外折叠（抽屉=${expertRender}）`);

  // ── R5 actionable.js 单件：提供者聚合 + 变更广播 ────────────────────
  const r5 = await page.evaluate(async () => {
    const a = await import('/js/actionable.js');
    const off1 = a.registerActionableProvider(() => 2);
    const off2 = a.registerActionableProvider(() => 3);
    let seen = -1;
    const offL = a.onActionableChanged((n) => { seen = n; });
    a.notifyActionableChanged();
    const total = a.actionableTotal();
    off1(); off2(); offL();
    a.notifyActionableChanged();
    return { total, seen, afterOff: a.actionableTotal() };
  });
  ok(r5.total === 5 && r5.seen === 5, `R5a 可行动聚合（=${r5.total}）`);
  ok(r5.afterOff === 0, `R5b 注销后归零（=${r5.afterOff}）`);

  // ── R6 周报浮出面（§6.4 回填）：横幅退役 → 待办收件箱浮出 ─────────────
  // 库态切到「周报入账后」：td-0001 已打卡销账（R3），引擎 upsert 周报待办。
  currentTasks = [WEEKLY_TODO, FIXTURE_TASKS[1]];
  const listsBefore = captures.listRequests.length;
  push({ type: 'weeklyReportReady', weekKey: '2026-W44', reportPath: '/tmp/x/REPORT.md',
    summary: '打卡周报 2026-W44 已生成：打卡 5/6，趋势↑（reports/weekly-checkin）' });
  await sleep(400);
  const r6 = await page.evaluate(() => {
    const notifs = Array.from(document.querySelectorAll('.notif-item')).map((i) => i.textContent || '');
    const body = document.querySelector('#reminder-panel .reminder-panel-body');
    const rows = body ? Array.from(body.querySelectorAll('.reminder-row')) : [];
    const weeklyRow = rows.find((r) => r.dataset.id === 'td-week');
    const badge = document.querySelector('#reminder-btn .reminder-badge');
    return {
      notifs,
      weeklyFirst: rows.length > 0 && rows[0].dataset.id === 'td-week',
      weeklyCheckin: !!(weeklyRow && weeklyRow.querySelector('.reminder-circle[data-action="checkin"]')),
      weeklyCancel: !!(weeklyRow && weeklyRow.querySelector('.reminder-cancel')),
      weeklyText: weeklyRow ? weeklyRow.textContent || '' : '',
      badge: badge ? badge.textContent : '0',
    };
  });
  ok(!r6.notifs.some((n) => n.includes('打卡周报')), 'R6a 零横幅（浮出面从横幅回填为收件箱）');
  ok(captures.listRequests.length > listsBefore,
    `R6b weeklyReportReady 触发收件箱刷新（listScheduledTasks ×${captures.listRequests.length - listsBefore}）`);
  ok(r6.weeklyFirst, 'R6c 周报条目落待办组置顶');
  ok(r6.weeklyCheckin && r6.weeklyCancel, 'R6d 周报条目带打卡销账钮 + 取消 ×（P2 闭合回路）');
  ok(r6.weeklyText.includes('打卡周报') && r6.weeklyText.includes('趋势↑'),
    'R6e 条目文案 = 反悬念结论（来源动作在）');
  ok(r6.badge === '1', `R6f 徽标 +1（周报待办 = 可行动量，=${r6.badge}）`);

  // ── R7 截图 + 零错误 ────────────────────────────────────────────────
  mkdirSync(SHOTS_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOTS_DIR, 'cognition-phase2-panel.png') });
  ok(pageErrors.length === 0, `R7a 零 pageerror（${pageErrors.length}）`);
  ok(consoleErrors.length === 0, `R7b 零 console 错误（${consoleErrors.length}）`);
  if (pageErrors.length) console.log('pageErrors:', pageErrors.slice(0, 5));
  if (consoleErrors.length) console.log('consoleErrors:', consoleErrors.slice(0, 5));
} finally {
  await browser.close();
  webServer.close();
}

console.log(failed === 0 ? 'ALL PASS' : `${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
