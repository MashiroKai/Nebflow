#!/usr/bin/env node
// todo-shell-runtime-proof.mjs — 取证节点（验：全套机械验收 · todo-unify-backend）
// 目的：为「todoStore 是内存空壳」出**运行时**铁证，零后端、零 sbt、零 sbt/gateway 进程。
//
//   (i)   改前 DOM 断言：胶囊计数 / 展开行数 / 点完成前后的 DOM 读数
//   (ii)  刷新后状态回滚实测（reload ⇒ 回到 8 条 mock）
//   (iii) 零写帧实证：**真 HTTP+WS 服务器**侧的线上帧日志（非 page.route 垫片），
//         证明点击窗口内 createScheduledTask / completeTodo / deleteScheduledTask /
//         updateScheduledTask / toggleScheduledTask 一个字节都没有
//   (+)   阳性对照：同一台服务器的同一份日志里，待办页（#todo-page）圆钮**确实**
//         发出 completeTodo 帧 ⇒ 「零写帧」不是记录器坏了
//   (f)   改前截图：桌面 1280×800 / 手机 375×812
//
// 载体选择说明：本机实测 playwright `page.routeWebSocket` 对**首连**存在时序竞态
// （首批运行出现 ErrConnectionRefused 后重连才被拦下），不足以支撑「零写帧」这种
// 阴性结论；改用真服务器（127.0.0.1:8399，避开作者生产实例的 6138）+ ws 包，
// 帧日志 = 服务器 onmessage 原文，证明力更强。静态文件服务语义与
// tests/todo-face-shots.mjs 的 page.route 静态服务器等价（同一 WEB 根 + 同 MIME 表）。
//
// 用法：node reports-evidence/<batch>/recon/todo-shell-runtime-proof.mjs

import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import wsPkg from '/Users/kaiyu/Claude code/Stand/node_modules/ws/index.js';
const { WebSocketServer } = wsPkg;
import { createServer } from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..', '..', '..');
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
const OUT = HERE;
mkdirSync(OUT, { recursive: true });

const PORT = 8399; // 本机空闲端口；6138 = 作者生产实例，绝不触碰
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.map': 'application/json',
};

const ROOT_SID = 'todo-shell-root';
const SELF_DEVICE = { id: 'self-dev-1', name: 'Mashiros-MacBook-Pro-iso-a659864a' };
const WRITE_TYPES = [
  'createScheduledTask', 'completeTodo', 'deleteScheduledTask',
  'updateScheduledTask', 'toggleScheduledTask',
];
const BENIGN_CONSOLE = [
  /fetching the script/i, /service worker/i, /sw\.js/i,
];

let pass = 0, fail = 0;
const fails = [];
function ok(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  PASS ${name}`); }
  else { fail++; fails.push(`${name}${detail ? ` — ${detail}` : ''}`); console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`); }
}

// ── 真服务器：静态文件 + WS 夹具 + 线上帧日志 ────────────────────────────
const frames = [];                 // 所有 client→server 原始帧
const fixture = { todos: [], tag: 'boot' };
let wss = null;

function serveStatic(req, res) {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (p === '/api/nf-asset-stamp') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ stamp: 'e2e-fixed' }));
  }
  if (p.startsWith('/api/')) { res.writeHead(200, { 'content-type': 'application/json' }); return res.end('{}'); }
  if (p === '/agents/Nebula/agent.json') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ name: 'Nebula', displayName: 'Spider' }));
  }
  if (p.startsWith('/agents/')) { res.writeHead(200, { 'content-type': 'application/json' }); return res.end('{}'); }
  const file = normalize(join(WEB, p));
  if (!file.startsWith(normalize(WEB)) || !existsSync(file) || !statSync(file).isFile()) {
    res.writeHead(404); return res.end('nf');
  }
  res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
}

function startServer() {
  return new Promise((resolve) => {
    const server = createServer(serveStatic);
    wss = new WebSocketServer({ server, path: '/ws' });
    wss.on('connection', (ws) => {
      const send = (o) => ws.send(JSON.stringify(o));
      const sendConfig = () => send({ type: 'configData', config: '{}', configured: true, onboarding: 'done', selfDevice: SELF_DEVICE });
      const sendSessions = () => send({ type: 'sessionList', sessions: [{ id: ROOT_SID, name: 'todo-shell-root', agentName: 'Nebula' }], folders: [], activeId: ROOT_SID });
      sendConfig();
      sendSessions();
      ws.on('message', (raw) => {
        const text = String(raw);
        let type = '?';
        try { type = String(JSON.parse(text).type || '?'); } catch { type = '<unparsable>'; }
        frames.push({ seq: frames.length, at: Date.now(), tag: fixture.tag, type, raw: text });
        let m; try { m = JSON.parse(text); } catch { return; }
        if (m.type === 'getConfig') sendConfig();
        else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
        else if (m.type === 'getHistory') send({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 });
        else if (m.type === 'listScheduledTasks') send({ type: 'scheduledTaskList', sessionId: m.sessionId, scope: m.scope, tasks: fixture.todos });
      });
    });
    server.listen(PORT, '127.0.0.1', () => resolve(server));
  });
}

async function bootReady(page) {
  await page.goto(`http://127.0.0.1:${PORT}/`);
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 20000 });
  await page.waitForTimeout(800);
}

/** 切到侧栏「消息」面板（入口行宿主）：activity bar 桌面钮 index.html:621。 */
async function openMessagesPanel(page) {
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  const ready = await page.evaluate(() => !!document.getElementById('messages-btn'));
  ok('#messages-btn 在场（消息面板开面板钮 index.html:621）', ready);
  if (ready) await page.evaluate(() => document.getElementById('messages-btn').click());
  await page.waitForTimeout(500);
}

async function mountCapsule(page, { visible = true } = {}) {
  await page.evaluate(async (sid) => {
    const f = await import('/js/todoFace.js');
    f.renderTodoTaskList(sid); // sidebar.js:2461 同一函数入口
  }, ROOT_SID);
  // 手机层胶囊「在场但 display:none」= 取证事实本身 ⇒ 只等 attached。
  await page.waitForSelector('#task-list .tfl-capsule', { state: visible ? 'visible' : 'attached', timeout: 8000 });
}

const readState = (page) => page.evaluate(async () => {
  const { todoStore } = await import('/js/todoStore.js');
  return {
    openCount: todoStore.openCount(),
    visible: todoStore.visibleItems().map((x) => ({ id: x.id, title: x.title, priority: x.priority, completed: !!x.completed })),
    capsuleText: (document.querySelector('#task-list .tfl-capsule-count') || {}).textContent || null,
    rows: [...document.querySelectorAll('#task-list .tfl-row-title')].map((e) => e.textContent),
    taskListDisplay: getComputedStyle(document.getElementById('task-list')).display,
  };
});

const shot = (page, name) => page.screenshot({ path: join(OUT, name), fullPage: false });

// ─────────────────────────────────────────────────────────────────────────
async function desktopProof(browser) {
  fixture.tag = 'desktop-pre-reload';
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light', deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  const consoleErrors = []; const pageErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  await bootReady(page);
  await openMessagesPanel(page);
  await mountCapsule(page);
  await page.waitForTimeout(400);

  console.log('\n=== 记录器自证（服务器侧已录到的线上帧，证明日志活着）===');
  const bootFrames = frames.map((f) => f.type);
  console.log('  已录帧数:', frames.length);
  console.log('  帧型:', bootFrames.join(','));
  ok('服务器侧在交互之前已录到 ≥10 条 client→server 帧', frames.length >= 10, String(frames.length));
  ok('已录帧里含 listScheduledTasks（读口在场）', bootFrames.includes('listScheduledTasks'));
  ok('录制序列里零写帧', frames.filter((f) => WRITE_TYPES.includes(f.type)).length === 0);
  // 活跃探针：页面主动发一帧只读帧 ⇒ 证明日志此刻在线（否则「零写帧」可能是死记录器）
  const probeSeq = frames.length;
  await page.evaluate(async () => { const m = await import('/js/ws.js'); m.sendWs({ type: 'getConfig' }); });
  await page.waitForTimeout(600);
  const probeHit = frames.slice(probeSeq).find((f) => f.type === 'getConfig');
  console.log('  活跃探针帧:', probeHit ? `[${probeHit.seq}] ${probeHit.raw}` : '(未录到)');
  ok('活跃探针 getConfig 被服务器日志捕获（记录器在线）', !!probeHit);

  console.log('\n=== (i) 改前 DOM（点完成钮之前）===');
  const before = await readState(page);
  console.log('  state:', JSON.stringify({ openCount: before.openCount, capsuleText: before.capsuleText }));
  ok('胶囊读数 = 8 待办（todoStore.js:205 seed() 8 条 mock）', /8/.test(String(before.capsuleText)), String(before.capsuleText));
  ok('todoStore.openCount() = 8', before.openCount === 8, String(before.openCount));
  ok('入口行 .fm-todo-entry 在场（消息列表顶部 messages.js:644）', await page.locator('.fm-todo-entry').count() === 1);
  ok('入口行可见', await page.locator('.fm-todo-entry').first().isVisible());
  ok('桌面 #task-list display ≠ none', before.taskListDisplay !== 'none', before.taskListDisplay);
  const residue = await page.evaluate(() => ({
    chainBlock: document.querySelectorAll('#task-list .task-chain-block').length,
    taskCard: document.querySelectorAll('#task-list .task-card').length,
    chainHead: document.querySelectorAll('#task-list .task-chain-head').length,
  }));
  console.log('  #task-list 内旧 delegate 残留计数:', JSON.stringify(residue));
  ok('#task-list 内零旧 delegate 链块残留（现役渲染只出 .tfl-*）',
    residue.chainBlock === 0 && residue.taskCard === 0 && residue.chainHead === 0, JSON.stringify(residue));

  await shot(page, 'f1-desktop-1280x800-capsule+entryrow.png');

  await page.locator('.tfl-capsule').click({ timeout: 8000 });
  await page.waitForTimeout(900);
  const rowsBefore = await page.locator('#task-list .tfl-row').count();
  console.log('  展开后行数:', rowsBefore);
  ok('展开列表 8 行', rowsBefore === 8, String(rowsBefore));
  await shot(page, 'f2-desktop-1280x800-capsule-expanded-8rows.png');
  const targetTitle = (await page.locator('#task-list .tfl-row .tfl-row-title').first().textContent() || '').trim();
  console.log('  将点完成的首行标题:', targetTitle);

  const markIdx = frames.length;
  console.log('\n=== 点击胶囊首行完成钮（.tfl-complete → todoStore.completeItem，todoFace.js:641-670）===');
  await page.locator('#task-list .tfl-row').first().locator('.tfl-complete').click({ timeout: 8000 });
  await page.waitForTimeout(1600); // 完成三段动画 800ms 后才真改 store
  const after = await readState(page);
  console.log('  state:', JSON.stringify({ openCount: after.openCount, capsuleText: after.capsuleText }));
  console.log('  DOM 行:', JSON.stringify(after.rows));
  const rowsAfter = await page.locator('#task-list .tfl-row').count();
  ok('点击后 DOM 行数 = 7', rowsAfter === 7, String(rowsAfter));
  ok('点击后 todoStore.openCount() = 7（纯内存态）', after.openCount === 7, String(after.openCount));
  ok('点击后胶囊读数收敛为 7', /7/.test(String(after.capsuleText)), String(after.capsuleText));
  ok('被点行标题已从可见面退场', !after.visible.some((x) => x.title === targetTitle), targetTitle);
  await shot(page, 'f3-desktop-1280x800-after-complete-7rows.png');

  console.log('\n=== (iii) 点击窗口内的 client→server 线上帧（原始）===');
  const windowFrames = frames.slice(markIdx);
  if (windowFrames.length === 0) console.log('  （空 —— 点击窗口内页面一个字节都没有发往服务器）');
  windowFrames.forEach((f) => console.log(`  [${f.seq}] ${f.type}  ${f.raw}`));
  ok('点击窗口内 client→server 帧数 = 0', windowFrames.length === 0, `${windowFrames.length} 帧: ${windowFrames.map((f) => f.type).join(',')}`);
  const writeFrames = frames.filter((f) => WRITE_TYPES.includes(f.type));
  ok('整场零写帧（createScheduledTask/completeTodo/deleteScheduledTask/updateScheduledTask/toggleScheduledTask）',
    writeFrames.length === 0, writeFrames.map((f) => f.type).join(','));
  const byType = frames.reduce((acc, f) => { acc[f.type] = (acc[f.type] || 0) + 1; return acc; }, {});
  console.log('  全场帧型统计:', JSON.stringify(byType));

  console.log('\n=== (ii) 刷新后状态回滚 ===');
  fixture.tag = 'desktop-post-reload';
  await page.reload();
  await bootReady(page);
  await mountCapsule(page);
  await page.waitForTimeout(400);
  const reloaded = await readState(page);
  console.log('  state:', JSON.stringify({ openCount: reloaded.openCount, capsuleText: reloaded.capsuleText, taskListDisplay: reloaded.taskListDisplay }));
  ok('刷新后 todoStore.openCount() 回到 8（写操作零留存）', reloaded.openCount === 8, String(reloaded.openCount));
  ok('刷新后胶囊读数回到 8', /8/.test(String(reloaded.capsuleText)), String(reloaded.capsuleText));
  ok('刷新后被点过的行重新出现在可见面（回滚）', reloaded.visible.some((x) => x.title === targetTitle), targetTitle);
  await shot(page, 'f4-desktop-1280x800-after-reload-rollback.png');

  // ── 阳性对照：待办页圆钮 = 真写口 ─────────────────────────────────
  console.log('\n=== (+) 阳性对照：待办页（#todo-page）圆钮 → completeTodo 帧 ===');
  fixture.tag = 'positive-control';
  fixture.todos = [{
    id: 'st-1', content: '阳性对照：真后端待办（夹具经 WS 下发）', triggerAt: Date.now() + 3600_000,
    createdAt: Date.now() - 3600_000, triggered: false, triggeredAt: null, referencePath: null,
    repeat: null, enabled: true, kind: 'todo', name: null, missCount: 0,
    sessionId: ROOT_SID, project: 'stand',
  }];
  const ctlMark = frames.length;
  await openMessagesPanel(page);
  await page.evaluate(() => document.querySelector('.fm-todo-entry').click());
  await page.waitForSelector('#todo-page.open .reminder-row[data-id="st-1"]', { timeout: 8000 });
  await page.waitForTimeout(400);
  await shot(page, 'f8-desktop-1280x800-todopage-realrow.png');
  await page.locator('#todo-page .reminder-row[data-id="st-1"] .reminder-circle[data-action="checkin"]').click({ timeout: 8000 });
  await page.waitForTimeout(900);
  const ctlFrames = frames.slice(ctlMark);
  console.log('  对照窗口线上帧（原始）:');
  ctlFrames.forEach((f) => console.log(`  [${f.seq}] ${f.type}  ${f.raw}`));
  ok('待办页圆钮确实发出 completeTodo 帧（阳性对照 ⇒ 日志可见写帧）',
    ctlFrames.some((f) => f.type === 'completeTodo'), ctlFrames.map((f) => f.type).join(','));

  const benign = consoleErrors.filter((t) => BENIGN_CONSOLE.some((re) => re.test(t)));
  const realConsole = consoleErrors.filter((t) => !BENIGN_CONSOLE.some((re) => re.test(t)));
  ok('零 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
  ok('零真实 console error（已知 sw 注册噪声除外）', realConsole.length === 0, realConsole.slice(0, 3).join(' | '));
  if (benign.length) console.log('  已知无害 console 噪声:', JSON.stringify([...new Set(benign)]));

  const dump = { targetTitle, before, after, reloaded, frames, consoleErrors, pageErrors };
  writeFileSync(join(OUT, 'ws-frames-desktop.json'), JSON.stringify(dump, null, 2));
  await ctx.close();
}

async function mobileShots(browser) {
  fixture.todos = [];
  fixture.tag = 'mobile';
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, colorScheme: 'light', deviceScaleFactor: 2, hasTouch: true });
  const page = await ctx.newPage();
  const consoleErrors = []; const pageErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  await bootReady(page);
  await page.waitForSelector('[data-chrome="tasks"]', { timeout: 12000 });
  await page.waitForFunction(() => {
    const b = document.querySelector('[data-chrome="tasks"] .mh-chrome-badge');
    return b && !b.hidden && String(b.textContent).trim() === '8';
  }, null, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(600);

  console.log('\n=== (f) 手机 375×812 三入口取证 ===');
  ok('mobile-layer 激活（body.mobile-layer）', await page.evaluate(() => document.body.classList.contains('mobile-layer')));
  await mountCapsule(page, { visible: false }); // 产品路径同函数；手机层 CSS 负责屏蔽
  await page.waitForTimeout(300);
  const m = await page.evaluate(() => {
    const tl = document.getElementById('task-list');
    const f = document.querySelector('[data-chrome="tasks"]');
    return {
      taskListDisplay: tl ? getComputedStyle(tl).display : 'absent',
      taskListRect: tl ? { w: Math.round(tl.getBoundingClientRect().width), h: Math.round(tl.getBoundingClientRect().height) } : null,
      fabBox: f ? (() => { const r = f.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; })() : null,
      fabBadge: (document.querySelector('[data-chrome="tasks"] .mh-chrome-badge') || {}).textContent || null,
      mobileTaskPanelPresent: !!document.getElementById('mobile-task-panel'),
    };
  });
  console.log('  读数:', JSON.stringify(m));
  ok('手机层 #task-list display = none（mobileLayer.css:2727-2729）', m.taskListDisplay === 'none', m.taskListDisplay);
  ok('手机左上角任务圆钮在场（chatChrome.js:176-190 data-chrome="tasks"）', !!m.fabBox, JSON.stringify(m.fabBox));
  ok('任务圆钮徽标 = 待办数 8（chatChrome.js:88 taskBadgeCount → todoStore.openCount）', String(m.fabBadge).trim() === '8', String(m.fabBadge));
  await shot(page, 'f5-mobile-375x812-chat-fab+capsule-displaynone.png');

  await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); nav.pushPage('messages'); });
  await page.waitForSelector('.mobile-page.live[data-page="messages"] .fm-todo-entry', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(900);
  const entryVisible = await page.locator('.mobile-page.live[data-page="messages"] .fm-todo-entry').first().isVisible().catch(() => false);
  console.log('  消息页入口行可见:', entryVisible);
  ok('手机消息页入口行可见（navController.js:2657 nf:mobile-todo-entry → pushPage todo）', entryVisible);
  await shot(page, 'f6-mobile-375x812-messages-entryrow.png');

  await page.evaluate(async () => { const nav = await import('/js/mobile/navController.js'); nav.pushPage('tasks'); });
  await page.waitForSelector('#mobile-task-panel .tfl-row', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(800);
  const panelRows = await page.locator('#mobile-task-panel .tfl-row').count();
  console.log('  任务页行数:', panelRows);
  ok('手机任务页 = 待办列表 8 行（同一内存 mock；taskPanel.js:77 mountTodoFaceInto）', panelRows === 8, String(panelRows));
  await shot(page, 'f7-mobile-375x812-taskpage-8rows.png');

  const realConsole = consoleErrors.filter((t) => !BENIGN_CONSOLE.some((re) => re.test(t)));
  ok('手机零 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
  ok('手机零真实 console error', realConsole.length === 0, realConsole.slice(0, 3).join(' | '));
  writeFileSync(join(OUT, 'ws-frames-mobile.json'), JSON.stringify({ reads: m, entryVisible, panelRows, frames: frames.filter((f) => f.tag === 'mobile'), consoleErrors, pageErrors }, null, 2));
  await ctx.close();
}

(async () => {
  const server = await startServer();
  console.log(`静态+WS 服务器: http://127.0.0.1:${PORT}/  (WEB=${WEB})`);
  const browser = await chromium.launch();
  try {
    await desktopProof(browser);
    await mobileShots(browser);
    console.log(`\n===== 汇总: PASS ${pass} / FAIL ${fail} =====`);
    if (fails.length) console.log('FAILS:\n  ' + fails.join('\n  '));
    console.log(`证据落盘: ${OUT}`);
    process.exitCode = fail === 0 ? 0 : 1;
  } finally {
    await browser.close();
    await new Promise((r) => wss.close(r));
    await new Promise((r) => server.close(r));
  }
})().catch((e) => { console.error('HARNESS ERROR', e); process.exitCode = 2; });
